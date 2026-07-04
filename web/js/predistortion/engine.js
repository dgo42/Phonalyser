/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.gui.fft.predistortion.PredistortionEngine
// (the closed-loop DAC predistortion loop) plus the
// org.edgo.audio.measure.dsp.FreqRespCalHelper.interpolate it relies on for the
// loaded-.frc de-embed (calResponseAt).
//
// The desktop engine is welded to SWT (Display.syncExec) and the live
// GeneratorController / FftController / FftView. This port keeps the loop ALGORITHM
// — the constants, the round-sizing formula (sizeAverages), the align poll, the
// accumulate → hot-apply → settle → reset cycle, the best-round snapshot, and the
// stop reasons — bit-faithful, and takes the desktop's SWT collaborators as an
// injected, async-friendly `host` object so the browser side can drive the same
// loop against worklets / workers. Every host method that the desktop ran on the UI
// thread is awaited here.
//
// All distortion / phasor math uses the JS `number` (IEEE-754 binary64,
// bit-identical to the desktop `double`); ratio / accumulator arrays inside the
// compensation classes are Float64Array.

import { HarmonicCompensation } from './harmonic-compensation.js';
import { IntermodCompensation } from './intermod-compensation.js';

/** Why the loop ended — drives the wizard's end-of-run message. */
export const StopReason = Object.freeze({
  TARGET_REACHED: 'TARGET_REACHED',
  STALLED: 'STALLED',
  USER_STOP: 'USER_STOP',
  ERROR: 'ERROR',
});

/** Live phase of the loop, polled to show "what's going on". */
export const Phase = Object.freeze({
  IDLE: 'IDLE',
  ALIGNING: 'ALIGNING',
  COLLECTING: 'COLLECTING',
  APPLYING: 'APPLYING',
  SETTLING: 'SETTLING',
  FINISHED: 'FINISHED',
});

/** LMS step μ — full residual per round (CLI default). */
const COMP_STEP = 1.0;
/** Ceiling on the THD0/THD averaging-count growth (64× base). */
const MAX_AVG_GROW = 64.0;
/** Averaging-margin factor in the round-sizing formula. */
const EXTEND_DROP_FACTOR = 0.75;
/** Tone counts as aligned when consecutive frames agree to within this fraction of a bin. */
const ALIGN_BIN_FRACTION = 0.1;
/** Consecutive settled polls required before the loop starts. */
const ALIGN_FRAMES = 2;
const ALIGN_TIMEOUT_MS = 10_000;
const POLL_MS = 150;
/** Settle after a live correction change before resetting statistics. */
const APPLY_SETTLE_MS = 200;

/**
 * Host the engine drives — the browser-side counterpart of the desktop's
 * GeneratorController / FftController / FftView / Preferences / clock, supplied by
 * the caller. Every method may be async (the engine awaits each); the desktop ran
 * these on the SWT UI thread.
 *
 * @typedef {Object} PredistortionHost
 * @property {() => number} maxHarmonics            Preferences.getFftCalcMaxHarmonic (≥1)
 * @property {() => boolean} isDualTone             gen signal form is dual-tone
 * @property {() => number} effectiveFrequency      F1 emit freq — the align target
 * @property {() => number} adcFsVoltageRms         Preferences.getAdcFsVoltageRms
 * @property {() => number} genAmplitudeVrms        Preferences.getGenAmplitudeVrms
 * @property {() => number} dualToneSplitPct        Preferences.getGenDualToneSplitPct
 * @property {() => void|Promise} configureForRun   set coherent ∞ averaging, FLL, fund-from-gen
 * @property {() => void|Promise} startRecording    ensure the FFT is recording
 * @property {() => void|Promise} clearCompensation drop any compensation on the generator
 * @property {() => void|Promise} resetStatistics   fresh baseline
 * @property {() => void|Promise} resetStatisticsAfterSignalChange
 * @property {() => number} completedAnalyses       FFT averages accumulated since the last reset
 * @property {() => ?import('../fft/fft-result.js').FftResult} readResult  deep copy of the last result
 * @property {(r) => number} imdPct                 combined intermod % of a dual-tone result
 * @property {(ampRatios, harmonicNumbers, phiInits) => void|Promise} applyCompensation
 * @property {(ampRatios, coefA, coefB, phiInits) => void|Promise} applyDualToneCompensation
 * @property {Array<{calibration: {left: ?FreqRespCalibration, right: ?FreqRespCalibration}}>} correctionEntries
 *           loaded .frc store entries (empty when none loaded)
 */

/**
 * Closed-loop DAC harmonic / intermod predistortion engine.
 *
 * Each round: reset stats, average for the sized depth, read THD (single tone) or
 * combined IMD % (dual tone), accumulate the LMS correction, hot-apply it to the
 * running generator, settle and repeat. Stops on Target THD, user stop, or error.
 * The best-distortion round's correction set is retained for saving.
 */
export class PredistortionEngine {
  /**
   * @param {PredistortionHost} host injected collaborators / preferences
   * @param {{onAligning?: () => void,
   *          onRound?: (round: number, distPct: number, averages: number, result) => void,
   *          onFinished?: (reason: string, bestDistPct: number, hasResult: boolean) => void}} [listener]
   */
  constructor(host, listener = {}) {
    this.host = host;
    this.listener = listener;

    this.stopRequested = false;
    /** Ends only the CURRENT averaging round early (the loop continues). */
    this.roundStopRequested = false;

    /** Live phase, polled by the wizard timer. */
    this.phase = Phase.IDLE;
    /** Current round number (0-based), polled live. */
    this.currentRound = 0;
    /** Target FFT-average count for the active COLLECTING round. */
    this.collectTargetAvg = 0;

    /** Best single-tone correction set (lowest distortion round), or null. */
    this.bestApplied = null;
    /** Best dual-tone correction set, or null. */
    this.bestIntermod = null;
    /** True when this run is compensating a two-tone signal. */
    this.dualTone = false;
    /** The lowest-distortion round's finalized result — header provenance. */
    this.bestResult = null;
    this.bestThdPct = Number.MAX_VALUE;
  }

  /** Requests a graceful stop after the current step. */
  stop() {
    this.stopRequested = true;
  }

  /** Ends the CURRENT averaging round early — the loop applies and continues. */
  stopRound() {
    this.roundStopRequested = true;
  }

  /** FFT averages still to collect in the current round; 0 outside COLLECTING. */
  getCollectRemainingAverages() {
    if (this.phase !== Phase.COLLECTING) return 0;
    return Math.max(0, this.collectTargetAvg - this.host.completedAnalyses());
  }

  /**
   * Runs the iterative loop. Each round averages `baseAverages` FFT frames (more as
   * the distortion drops), corrects the measurable distortion and repeats until the
   * user stops it (an optional `targetThdPct > 0` ends it early when reached).
   *
   * @param {number} baseAverages base FFT-average count per round
   * @param {number} targetThdPct stop-early target distortion (%); ≤0 to disable
   * @returns {Promise<void>} resolves when the loop ends (onFinished already fired)
   */
  async runLoop(baseAverages, targetThdPct) {
    this.stopRequested = false;
    let reason = StopReason.USER_STOP;
    try {
      const maxH = Math.max(1, this.host.maxHarmonics());
      this.dualTone = this.host.isDualTone();
      // One of the two accumulators is live; the other stays null.
      const harm = this.dualTone ? null : new HarmonicCompensation(maxH);
      const imd = this.dualTone ? new IntermodCompensation(maxH) : null;
      // harmAppl / imdAppl: the snapshot of what's currently applied (empty = round 0).
      let harmAppl = this.dualTone ? null : harm.copy();
      let imdAppl = this.dualTone ? imd.copy() : null;
      const target = this.host.effectiveFrequency();          // F1 emit freq — the align target

      // Switch the FFT to INFINITE coherent generator-locked averaging.
      await this.host.configureForRun();
      await this.host.startRecording();

      // Always calibrate from the RAW DAC: drop any compensation already on the
      // generator so round 0 measures the true distortion.
      await this.host.clearCompensation();
      await this._sleep(APPLY_SETTLE_MS);

      this.phase = Phase.ALIGNING;
      this._emit('onAligning');
      if (!(await this._waitForAlign())) {
        if (this.stopRequested) { reason = StopReason.USER_STOP; return; }
        // tone did not stabilise within ALIGN_TIMEOUT_MS — proceed anyway.
      }
      await this.host.resetStatistics();                      // fresh baseline for round 0

      let round = 0;
      let baselineDist = NaN;   // round 0's distortion — the fixed reference
      let prevDist = NaN;       // last completed round's distortion
      while (!this.stopRequested) {
        this.currentRound = round;
        this.phase = Phase.COLLECTING;
        const maxAverages = Math.round(baseAverages * MAX_AVG_GROW);
        const r = await this._collectUntilAverages(baseAverages, baselineDist, prevDist, maxAverages);
        if (r == null) {
          reason = this.stopRequested ? StopReason.USER_STOP : StopReason.ERROR;
          // A null measurement that wasn't user-requested is a real failure — LOG it so the
          // wizard's "Measurement error — see the log" headline actually points at something.
          if (!this.stopRequested) console.error('Predistortion: measurement returned no result (analysis failed) at round ' + round + '.');
          break;
        }
        const roundAvgDone = this.host.completedAnalyses();
        const distPct = this.dualTone ? this._imdPct(r) : r.thdPct;
        if (!Number.isFinite(baselineDist) && distPct > 0.0) baselineDist = distPct;
        prevDist = distPct;
        if (distPct < this.bestThdPct) {
          this.bestThdPct = distPct;
          this.bestResult = r;
          if (this.dualTone) this.bestIntermod = imdAppl.copy();
          else this.bestApplied = harmAppl.copy();
        }
        this._emit('onRound', round, distPct, roundAvgDone, r);

        // Optional Target THD ends the loop early when set.
        if (targetThdPct > 0 && distPct <= targetThdPct) { reason = StopReason.TARGET_REACHED; break; }

        // Accumulate this round's residual and hot-apply for the next.
        this.phase = Phase.APPLYING;
        const chanLeft = r.channelLeft;
        if (this.dualTone) {
          imd.accumulate(r, r.fundamentalHzRefined, r.fundamental2HzRefined, COMP_STEP,
            this._calResponseAt(r.fundamentalHzRefined, chanLeft)[1],
            this._calResponseAt(r.fundamental2HzRefined, chanLeft)[1]);
          imdAppl = imd.copy();
          const gc = imd.toGeneratorCorrections(
            f => this._calResponseAt(f, chanLeft), this.host.adcFsVoltageRms(),
            this.dualToneFundamentalVrms());
          await this.host.applyDualToneCompensation(gc.ampRatios, gc.coefA, gc.coefB, gc.phiInits);
        } else {
          harm.accumulate(r, COMP_STEP, this._calResponseAt(r.fundamentalHzRefined, chanLeft)[1]);
          harmAppl = harm.copy();
          const gc = harm.toGeneratorCorrections(
            target, f => this._calResponseAt(f, chanLeft),
            this.host.adcFsVoltageRms(), this.host.genAmplitudeVrms());
          await this.host.applyCompensation(gc.ampRatios, gc.harmonicNumbers, gc.phiInits);
        }
        this.phase = Phase.SETTLING;
        await this._sleep(APPLY_SETTLE_MS);
        if (this.stopRequested) { reason = StopReason.USER_STOP; break; }
        await this.host.resetStatisticsAfterSignalChange();
        round++;
      }
    } catch (ex) {
      reason = StopReason.ERROR;
      // ALWAYS log the actual error so the wizard's "see the log" headline is meaningful
      // (the host has no logger of its own); also surface it to a host logger if one exists.
      console.error('Predistortion tuning failed:', ex);
      if (typeof this.host.onError === 'function') this.host.onError(ex);
    } finally {
      this.phase = Phase.FINISHED;
      const hasResult = this.dualTone
        ? (this.bestIntermod != null && this.bestIntermod.hasCorrections())
        : (this.bestApplied != null && this.bestApplied.hasCorrections());
      this._emit('onFinished', reason, this.bestThdPct, hasResult);
    }
  }

  /**
   * Combined calibration response [magLin, phaseRad] the loaded .frc corrections
   * impose at `freqHz` for the analysed channel — the product of H(f) over every
   * loaded entry (magnitudes multiply, phases add). Entries whose calibration does
   * not span `freqHz` contribute unit response. Faithful port of
   * PredistortionEngine.calResponseAt.
   *
   * @param {number} freqHz
   * @param {boolean} left true → left channel cal, false → right
   * @returns {[number, number]} [magLin, phaseRad]
   */
  _calResponseAt(freqHz, left) {
    let mag = 1.0, phase = 0.0;
    const entries = this.host.correctionEntries;
    if (entries != null && freqHz > 0.0) {
      for (const e of entries) {
        const cal = left ? e.calibration.left : e.calibration.right;
        if (cal == null || cal.freqs.length === 0) continue;
        if (freqHz < cal.freqs[0] || freqHz > cal.freqs[cal.freqs.length - 1]) continue;
        const h = interpolate(cal, freqHz);
        mag *= h[0];
        phase += h[1];
      }
    }
    return [mag, phase];
  }

  /** The loaded .frc response as a frequency function for the given channel — so
   *  the save path applies the same de-embed as the live apply. */
  calResponseFor(left) {
    return f => this._calResponseAt(f, left);
  }

  /**
   * The F1 tone's DAC output level (Vrms) — the dual-tone correction's ratio
   * reference. With weights w₁ = split%, w₂ = 100−split%, tone-1 carries
   * total·w₁/√(w₁²+w₂²) of the combined RMS. Faithful port of
   * PredistortionEngine.dualToneFundamentalVrms.
   */
  dualToneFundamentalVrms() {
    const w1 = this.host.dualToneSplitPct() / 100.0;
    const w2 = 1.0 - w1;
    const norm = Math.hypot(w1, w2);
    const amp = this.host.genAmplitudeVrms();
    return norm > 0.0 ? amp * w1 / norm : amp;
  }

  /** Dual-tone distortion figure: combined intermod % (falls back to THD). */
  _imdPct(r) {
    const v = this.host.imdPct(r);
    return Number.isFinite(v) ? v : r.thdPct;
  }

  /** Current cumulative distortion off the live FFT result — THD (single) or
   *  combined intermod % (dual). NaN when no result yet. */
  _liveDistPct() {
    const r = this.host.readResult();
    if (r == null) return NaN;
    return this.dualTone ? this._imdPct(r) : r.thdPct;
  }

  /** Blocks until the measured fundamental is stable (consecutive frames agree
   *  within ALIGN_BIN_FRACTION of a bin for ALIGN_FRAMES polls), the timeout
   *  elapses, or a stop is requested. */
  async _waitForAlign() {
    const deadline = this._now() + ALIGN_TIMEOUT_MS;
    let settled = 0;
    let prevHz = NaN;
    while (this._now() < deadline && !this.stopRequested) {
      const r = this.host.readResult();
      if (r != null && Number.isFinite(r.fundamentalHzRefined) && r.freqResolution > 0) {
        const hz = r.fundamentalHzRefined;
        if (Number.isFinite(prevHz)
            && Math.abs(hz - prevHz) <= r.freqResolution * ALIGN_BIN_FRACTION) {
          if (++settled >= ALIGN_FRAMES) return true;
        } else {
          settled = 0;
        }
        prevHz = hz;
      }
      await this._sleep(POLL_MS);
    }
    return false;
  }

  /** Averages until the FFT has accumulated the round's target frames (polling for
   *  a stop), then returns a deep copy of the finalized result, or null on stop.
   *  The target is re-evaluated against the LIVE residual on every poll. */
  async _collectUntilAverages(baseAverages, baselineDistPct, prevDistPct, maxAverages) {
    this.collectTargetAvg = this._sizeAverages(baseAverages, baselineDistPct, prevDistPct, maxAverages);
    this.roundStopRequested = false;
    while (this.host.completedAnalyses() < this.collectTargetAvg) {
      if (this.stopRequested) return null;
      // Manual "Stop round": take the frames averaged so far (≥1) and proceed.
      if (this.roundStopRequested && this.host.completedAnalyses() >= 1) break;
      const target = this._sizeAverages(baseAverages, baselineDistPct, this._liveDistPct(), maxAverages);
      if (target > this.collectTargetAvg) this.collectTargetAvg = target;
      await this._sleep(POLL_MS);
    }
    return this.host.readResult();
  }

  /** Round-sizing formula: baseAverages × (THD0/THD) × EXTEND_DROP_FACTOR, clamped
   *  to [baseAverages, maxAverages]. Returns baseAverages until a reference and a
   *  measurement exist. Faithful port of PredistortionEngine.sizeAverages. */
  _sizeAverages(baseAverages, baselineDistPct, distPct, maxAverages) {
    if (!(baselineDistPct > 0) || !(distPct > 0)) return baseAverages;
    const target = Math.round(baseAverages * (baselineDistPct / distPct) * EXTEND_DROP_FACTOR);
    return Math.max(baseAverages, Math.min(target, maxAverages));
  }

  _emit(name, ...args) {
    const fn = this.listener[name];
    if (typeof fn === 'function') fn(...args);
  }

  _now() {
    return (typeof performance !== 'undefined' && performance.now)
      ? performance.now() : Date.now();
  }

  _sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
  }
}

/**
 * Interpolates a FreqRespCalibration's H(f) at an arbitrary frequency. Uses log-
 * frequency as the interpolation variable; magnitude in dB; phase via complex
 * unit-phasor interpolation to handle ±180° wraps at notches. Faithful port of
 * org.edgo.audio.measure.dsp.FreqRespCalHelper.interpolate.
 *
 * @param {{freqs: number[]|Float64Array, magLin: number[]|Float64Array,
 *          phaseRad: number[]|Float64Array}} cal
 * @param {number} freq
 * @returns {[number, number]} [magLin, phaseRad]
 */
export function interpolate(cal, freq) {
  let lo = 0, hi = cal.freqs.length - 1;
  if (freq <= cal.freqs[lo]) return [cal.magLin[lo], cal.phaseRad[lo]];
  if (freq >= cal.freqs[hi]) return [cal.magLin[hi], cal.phaseRad[hi]];
  while (hi - lo > 1) {
    const mid = (lo + hi) >>> 1;
    if (cal.freqs[mid] <= freq) lo = mid; else hi = mid;
  }
  const f0 = cal.freqs[lo], f1 = cal.freqs[hi];
  const t = (Math.log(freq) - Math.log(f0)) / (Math.log(f1) - Math.log(f0));

  const m0 = cal.magLin[lo], m1 = cal.magLin[hi];
  const db0 = m0 > 0.0 ? 20.0 * Math.log10(m0) : -300.0;
  const db1 = m1 > 0.0 ? 20.0 * Math.log10(m1) : -300.0;
  const db = db0 + (db1 - db0) * t;
  const mag = Math.pow(10.0, db / 20.0);

  const re0 = Math.cos(cal.phaseRad[lo]), im0 = Math.sin(cal.phaseRad[lo]);
  const re1 = Math.cos(cal.phaseRad[hi]), im1 = Math.sin(cal.phaseRad[hi]);
  const reT = re0 + (re1 - re0) * t;
  const imT = im0 + (im1 - im0) * t;
  const phi = (reT === 0.0 && imT === 0.0) ? cal.phaseRad[lo] : Math.atan2(imT, reT);

  return [mag, phi];
}
