/*
 * Phonalyser web - precision audio measurement workbench (browser port).
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
// - the constants, the round-sizing formula (sizeAverages), the align poll, the
// accumulate -> hot-apply -> settle -> reset cycle, the best-round snapshot, and the
// stop reasons - bit-faithful, and takes the desktop's SWT collaborators as an
// injected, async-friendly `host` object so the browser side can drive the same
// loop against worklets / workers. Every host method that the desktop ran on the UI
// thread is awaited here.
//
// All distortion / phasor math uses the JS `number` (IEEE-754 binary64,
// bit-identical to the desktop `double`); ratio / accumulator arrays inside the
// compensation classes are Float64Array.

import { HarmonicCompensation } from './harmonic-compensation.js';
import { IntermodCompensation } from './intermod-compensation.js';

/** Why the loop ended - drives the wizard's end-of-run message. */
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

/** LMS step μ - full residual per round (CLI default). */
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

/** FFT frames each of the two probe sub-rounds averages. The probe partial arrives
 *  tens of dB above the noise, so the measurement needs no depth - this is only
 *  enough to average out a single disturbed frame, which keeps the once-per-training
 *  probe short against the rounds that follow. */
const PROBE_AVERAGES = 4;
/** Highest harmonic the probe measures. The correction file runs to H9, and every
 *  harmonic above it keeps the identity plant. */
const PROBE_MAX_HARMONIC = 9;
/** Probe partial amplitude, dB relative to the DAC fundamental - the same for a
 *  harmonic and for an intermod product. High enough that its arrival stands far
 *  above the noise floor and far above the DUT's own distortion (so the difference
 *  of the two sub-rounds is the probe alone), low enough - 0.1% of the fundamental
 *  each - that neither the DAC nor the ADC is stressed and the chain stays in the
 *  same small-signal regime the corrections live in. The dual-tone grid carries the
 *  most partials (the two tones' harmonics plus the low-order intermod comb, of the
 *  order of twenty), which even added coherently stays a couple of percent of the
 *  fundamental - so the products need no amplitude of their own. */
const PROBE_AMPLITUDE_DB = -60.0;
/** Phase step between consecutive probe partials. Any known phase would measure the
 *  plant; stepping them apart means a partial delivered to the wrong DDS slot shows
 *  up as a plant phase that does not fit the measured pattern instead of hiding
 *  behind a common phase. */
const PROBE_PHASE_STEP_DEG = 45.0;
/** dB the probe's arrival must stand above the frame's noise floor before its plant
 *  is trusted. At 20 dB a noise phasor can turn the measured arrival by at most
 *  asin(0.1) = 5.7 degrees - an order below the per-harmonic phase error the plant
 *  exists to remove - and a harmonic that misses it keeps the identity plant rather
 *  than inverting noise. */
const PROBE_CLEARANCE_DB = 20.0;
/** Extra averages a DUAL-TONE probe sub-round may spend waiting for the analyzer to
 *  publish its de-rotated intermod-product grid. That grid is a per-tick decision,
 *  and a tick taken right after the statistics re-anchor the probe itself triggers -
 *  or after a discontinuity re-sync - can carry none of it. A plant measured from
 *  such a frame comes out HALF measured: the b=0 harmonics still read out of the
 *  harmonic table while every mixed product silently keeps the identity plant, so
 *  the whole a*f1 + b*f2 comb stays uncorrected with nothing marking the loss. A
 *  handful of further averages costs nothing against the rounds that follow; if the
 *  grid never appears the probe measures what it can, exactly as before. */
const PROBE_GRID_EXTRA_AVG = 8;

/**
 * Host the engine drives - the browser-side counterpart of the desktop's
 * GeneratorController / FftController / FftView / Preferences / clock, supplied by
 * the caller. Every method may be async (the engine awaits each); the desktop ran
 * these on the SWT UI thread.
 *
 * @typedef {Object} PredistortionHost
 * @property {() => number} maxHarmonics            Preferences.getFftCalcMaxHarmonic (≥1)
 * @property {() => boolean} isDualTone             gen signal form is dual-tone
 * @property {() => number} effectiveFrequency      F1 emit freq - the align target
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
    /** True only between the probe partials reaching the generator and their
     *  removal. The abort path clears the generator only while this is set, so a
     *  normal finish leaves the round's correction where the wizard expects it. */
    this._probeActive = false;

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
    /** The lowest-distortion round's finalized result - header provenance. */
    this.bestResult = null;
    this.bestThdPct = Number.MAX_VALUE;
  }

  /** Requests a graceful stop after the current step. */
  stop() {
    this.stopRequested = true;
  }

  /** Ends the CURRENT averaging round early - the loop applies and continues. */
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
      const target = this.host.effectiveFrequency();          // F1 emit freq - the align target

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
        // tone did not stabilise within ALIGN_TIMEOUT_MS - proceed anyway.
      }
      // Measure what the chain does with a commanded correction BEFORE correcting
      // anything, so every round is built through the measured plant instead of
      // through the assumption of a perfect one - at the harmonics for a single
      // tone, at the whole product grid for two.
      await this._runProbe(harm, imd, maxH);
      await this.host.resetStatistics();                      // fresh baseline for round 0

      let round = 0;
      let baselineDist = NaN;   // round 0's distortion - the fixed reference
      let prevDist = NaN;       // last completed round's distortion
      while (!this.stopRequested) {
        this.currentRound = round;
        this.phase = Phase.COLLECTING;
        const maxAverages = Math.round(baseAverages * MAX_AVG_GROW);
        const r = await this._collectUntilAverages(baseAverages, baselineDist, prevDist, maxAverages);
        if (r == null) {
          reason = this.stopRequested ? StopReason.USER_STOP : StopReason.ERROR;
          // A null measurement that wasn't user-requested is a real failure - LOG it so the
          // wizard's "Measurement error - see the log" headline actually points at something.
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
      // A stop or a failure between applying the probe partials and removing them
      // would otherwise leave them on the generator. Guarded because this teardown
      // runs after the reason is already decided: a failing clear must not replace
      // it, nor keep the run's outcome from reaching the wizard.
      if (this._probeActive) {
        try { await this.host.clearCompensation(); } catch (e) {
          console.error('Predistortion: the probe partials could not be cleared', e);
        }
        this._probeActive = false;
      }
      this.phase = Phase.FINISHED;
      const hasResult = this.dualTone
        ? (this.bestIntermod != null && this.bestIntermod.hasCorrections())
        : (this.bestApplied != null && this.bestApplied.hasCorrections());
      this._emit('onFinished', reason, this.bestThdPct, hasResult);
    }
  }

  /**
   * Combined calibration response [magLin, phaseRad] the loaded .frc corrections
   * impose at `freqHz` for the analysed channel - the product of H(f) over every
   * loaded entry (magnitudes multiply, phases add). Entries whose calibration does
   * not span `freqHz` contribute unit response. Faithful port of
   * PredistortionEngine.calResponseAt.
   *
   * @param {number} freqHz
   * @param {boolean} left true -> left channel cal, false -> right
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

  /** The loaded .frc response as a frequency function for the given channel - so
   *  the save path applies the same de-embed as the live apply. */
  calResponseFor(left) {
    return f => this._calResponseAt(f, left);
  }

  /**
   * The F1 tone's DAC output level (Vrms) - the dual-tone correction's ratio
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

  /** Current cumulative distortion off the live FFT result - THD (single) or
   *  combined intermod % (dual). NaN when no result yet. */
  _liveDistPct() {
    const r = this.host.readResult();
    if (r == null) return NaN;
    return this.dualTone ? this._imdPct(r) : r.thdPct;
  }

  /**
   * Measures the plant transfer once, before round 0, and installs it in the live
   * accumulator: (1) a short BASELINE sub-round with nothing applied, (2) the same
   * length again with known partials applied through the very generator path the
   * corrections use - at H2..H9 for a single tone, at the whole a*f1 + b*f2 product
   * grid for two - (3) the difference per harmonic or product, where the DUT's own
   * distortion and the noise cancel, divided by the arrival the command should have
   * produced. The partials are removed again before round 0, so the loop starts
   * from the raw DAC exactly as it did before.
   *
   * Silently leaves the identity plant when a sub-round is cut short by a stop: an
   * unmeasured plant must never become a guessed one.
   *
   * @param {?HarmonicCompensation} harm live single-tone accumulator (null for dual)
   * @param {?IntermodCompensation} imd  live dual-tone accumulator (null for single)
   * @param {number} maxH accumulator harmonic slots
   */
  async _runProbe(harm, imd, maxH) {
    this.phase = Phase.COLLECTING;
    await this.host.resetStatistics();
    const baseline = await this._probeSubRound(imd);
    if (baseline == null) return;

    const ampRatio = Math.pow(10.0, PROBE_AMPLITUDE_DB / 20.0);
    const phaseStep = PROBE_PHASE_STEP_DEG * (Math.PI / 180.0);
    let dualCommand = null;
    let probeAmp = null;                   // accumulator-indexed, 0 = not probed
    let probePhi = null;
    if (this.dualTone) {
      // The accumulator owns the product grid, so it builds the command: the same
      // object goes to the generator and to the measurement.
      dualCommand = imd.probeCommand(ampRatio, phaseStep);
      await this.host.applyDualToneCompensation(dualCommand.ampRatios, dualCommand.coefA,
        dualCommand.coefB, dualCommand.phiInits);
    } else {
      const count = Math.min(maxH, PROBE_MAX_HARMONIC - 1);
      probeAmp = new Float64Array(maxH);
      probePhi = new Float64Array(maxH);
      const amps = new Float64Array(count);          // generator-indexed
      const nums = new Int32Array(count);
      const phis = new Float64Array(count);
      for (let h = 0; h < count; h++) {
        probeAmp[h] = ampRatio;
        probePhi[h] = h * phaseStep;
        amps[h] = ampRatio;
        nums[h] = h + 2;
        phis[h] = probePhi[h];
      }
      await this.host.applyCompensation(amps, nums, phis);
    }
    this._probeActive = true;
    await this._sleep(APPLY_SETTLE_MS);
    await this.host.resetStatisticsAfterSignalChange();
    const probed = await this._probeSubRound(imd);
    await this.host.clearCompensation();
    this._probeActive = false;
    await this._sleep(APPLY_SETTLE_MS);
    if (probed == null) return;

    const left = probed.channelLeft;
    if (this.dualTone) {
      imd.setPlant(imd.measurePlant(baseline, probed, dualCommand,
        f => this._calResponseAt(f, left), PROBE_CLEARANCE_DB));
    } else {
      harm.setPlant(harm.measurePlant(baseline, probed, probeAmp, probePhi,
        f => this._calResponseAt(f, left), PROBE_CLEARANCE_DB));
    }
  }

  /** One probe sub-round - a fixed, short average, sized by nothing but
   *  PROBE_AVERAGES (no residual to grow against yet). A dual-tone sub-round
   *  additionally keeps averaging while the frame it ends on carries no de-rotated
   *  intermod product, so the plant is measured off the same kind of frame a
   *  round's correction is - see PROBE_GRID_EXTRA_AVG. */
  async _probeSubRound(imd) {
    let r = await this._collectUntilAverages(PROBE_AVERAGES, NaN, NaN, PROBE_AVERAGES);
    if (!this.dualTone) return r;
    for (let extra = 1; extra <= PROBE_GRID_EXTRA_AVG
        && r != null && !imd.carriesProducts(r); extra++) {
      const target = PROBE_AVERAGES + extra;
      r = await this._collectUntilAverages(target, NaN, NaN, target);
    }
    return r;
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
