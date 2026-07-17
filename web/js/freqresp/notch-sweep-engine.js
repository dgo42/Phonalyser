/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.gui.freqresp.NotchSweepEngine, plus
// the PURE analysis math of gui.freqresp.TuneNotchWizardDialog (power-of-two
// loop period, 25 ms seam fades, sweep-band widening, deepest-null with
// sub-bin parabolic refinement, target-frequency attenuation) exported as
// stateless functions so the Tune-notch wizard UI can drive them.
//
// Self-contained CONTINUOUS play+capture for the Tune-notch wizard's live
// sweep loop: start() configures the DDS worklet with a LOOPING Farina
// log-sweep (Hann-faded at each cycle seam) and acquires the shared capture
// ring; the wizard then grabs the most recent one-period window whenever it
// wants a fresh result (≈10 updates/s) — no per-sweep device open/close and no
// real-time recording wait.
//
// No alignment of the grabbed window to the sweep-cycle start is needed — but
// ONLY because the wizard makes the loop period a power of two, so
// computeFromLogSweep's nextPow2(period) FFT is exactly one period: a CIRCULAR
// transform. A window grabbed at any loop phase is then just a circular shift,
// which leaves the magnitude |H(f)| unchanged. (With a non-power-of-two period
// the FFT would be zero-padded/linear, an unaligned window would wrap the
// period across the buffer, and every bin would corrupt into a random spike.)
//
// Intended web adaptations (the Java engine owns JavaSound lines + its own
// ring + a MAX_PRIORITY generator thread; the web has neither raw lines nor
// threads):
//   - Playback goes through the shared dds-processor worklet, but THIS engine owns the
//     generator lifecycle for the session (like Java, whose NotchSweepEngine owns its own
//     playback line): start() snapshots the DDS config, points the generator at a silent
//     looping Farina sweep and starts it (injected startGenerator), drives it via postGen(msg),
//     then un-mutes at the real amplitude; close() stops the generator and restores the config.
//     The kernel's looping logSweepNext IS the port of the Java generator's, so the seam-fade
//     semantics are identical. The wizard UI no longer commandeers the DDS.
//   - Capture uses an injected DEDICATED measurement capture (acquire/release) — a SEPARATE
//     input device line from the live scope/FFT capture, so a measurement never rides the live
//     consumers' ring (Java opens its own device line per measurement: "no device-line reuse
//     across captures"). Its 22 s SignalBuffer ring stands in for Java's private RING_PERIODS
//     ring; latestPeriod() maps onto readLatest() with a session-start anchor so pre-session
//     audio can never be served. The capture worklet delivers normalised [-1, +1] floats, so
//     Java's raw-ADC-code/halfRange conversion (bitDepth/ditherBits) does not exist here — an
//     intended web divergence (no bit-depth selector).
//   - sweepRef() re-renders the reference locally (renderLogSweep, the port of
//     SignalGenerator#renderLogSweep the kernel itself uses) instead of taking
//     the generator thread's live buffer back; on setBand() it re-renders so
//     the reference tracks the played band exactly like Java's rebuilt buffer.
//   - Java assumes ONE exclusive-mode clock for DAC + ADC; the web's output
//     and capture AudioContexts are independent clocks. Like the existing
//     runSweep path, this engine assumes matching in/out rates (sampleRate is
//     the CAPTURE rate the deconvolution runs at).

import { GenSignalForm } from '../generator/dds-kernel.js';
import { nextPow2 } from '../dsp/mathutil.js';
import { renderLogSweep } from './farina-sweep.js';

// --- Sweep timing (TuneNotchWizardDialog constants) --------------------------
/** Sweep duration (s) the power-of-two loop period is derived from.
 *  Mirrors TuneNotchWizardDialog.SWEEP_DURATION_SEC. */
export const SWEEP_DURATION_SEC = 0.26;
/** Lead-in (s) — the continuous stream uses ZERO lead-in (the loop never
 *  re-emits silence); kept only as the reported sweep parameter.
 *  Mirrors TuneNotchWizardDialog.SWEEP_LEAD_IN_SEC. */
export const SWEEP_LEAD_IN_SEC = 0.05;
/** Per-side Hann fade length (seconds) applied to BOTH the played loop seam
 *  and the deconvolution reference. 25 ms was found on the scope to best
 *  suppress the loop-seam spikes; it overrides the shared 5% default.
 *  Mirrors TuneNotchWizardDialog.FADE_SEC. */
export const FADE_SEC = 0.025;
/** Safety multiplier on the sweep-band margin (see {@link sweepBand}). The
 *  Hann fades drive the sweep energy |X|→0 at the swept ends, so the displayed
 *  band must sit well inside the fully-excited middle; >1 keeps the displayed
 *  edges clear of the fade and the log sweep's 1/f roll-off (which otherwise
 *  flatten the trace at the edges).
 *  Mirrors TuneNotchWizardDialog.SWEEP_MARGIN_SAFETY. */
export const SWEEP_MARGIN_SAFETY = 2.5;

/**
 * The loop period in samples: the power of two NEAREST to
 * SWEEP_DURATION_SEC · sampleRate. The period MUST be a power of two:
 * computeFromLogSweep sizes its FFT to nextPow2(period), so a power-of-two
 * period makes that FFT exactly ONE period — a CIRCULAR transform, not a
 * zero-padded linear one. Only then is a window grabbed at an arbitrary loop
 * phase just a circular shift that leaves |H(f)| unchanged; a linear FFT of an
 * unaligned window wraps the period and corrupts every bin into a spike.
 * Faithful port of the loop-period derivation in
 * TuneNotchWizardDialog#sweepLoop.
 *
 * @param {number} sampleRate capture sample rate (Hz)
 * @returns {number} loop period in samples (a power of two)
 */
export function powerOfTwoSweepSamples(sampleRate) {
  const rawSamples = Math.round(SWEEP_DURATION_SEC * sampleRate);
  // Integer.highestOneBit(max(2, rawSamples)).
  const v = Math.max(2, rawSamples);
  let loPow2 = 1;
  while ((loPow2 << 1) > 0 && (loPow2 << 1) <= v) loPow2 <<= 1;
  return (rawSamples - loPow2 < (loPow2 << 1) - rawSamples) ? loPow2 : loPow2 << 1;
}

/**
 * Per-side seam-fade length in samples ({@link FADE_SEC} at the given rate) —
 * the SAME value must drive the played loop seam and the deconvolution
 * reference. Mirrors {@code fadeSamples} in TuneNotchWizardDialog#sweepLoop.
 *
 * @param {number} sampleRate capture sample rate (Hz)
 * @returns {number} per-side Hann fade length in samples
 */
export function notchFadeSamples(sampleRate) {
  return Math.round(FADE_SEC * sampleRate);
}

/**
 * The DDS sweep band: WIDER than the displayed [start, stop] so the Hann fades
 * — which zero the sweep energy |X| over FADE_SEC / SWEEP_DURATION_SEC of the
 * log range at each swept end — fall OUTSIDE the displayed band, with
 * {@link SWEEP_MARGIN_SAFETY} headroom for the log sweep's 1/f roll-off (so
 * the displayed edges aren't flattened). The view shows only [start, stop];
 * the widened ends are cut. Faithful port of TuneNotchWizardDialog#sweepBand.
 *
 * @param {number} startHz displayed band start (Hz)
 * @param {number} stopHz  displayed band stop (Hz)
 * @returns {[number, number]} the widened [f0, f1] the sweep actually plays
 */
export function sweepBand(startHz, stopHz) {
  const ff = FADE_SEC / SWEEP_DURATION_SEC;
  const marginFrac = SWEEP_MARGIN_SAFETY * ff / (1.0 - 2.0 * ff);
  const m = Math.log(stopHz / startHz) * marginFrac;
  return [startHz * Math.exp(-m), stopHz * Math.exp(m)];
}

/**
 * Finds the deepest notch (minimum linear magnitude) with sub-bin refinement.
 * The discrete bin minimum is only accurate to the bin spacing; a parabola is
 * fitted to the three points around it (in linear magnitude, where the
 * resolution-limited null is a smooth dip) to recover the SUB-bin null
 * frequency and depth — matching the smooth minimum the view draws.
 * Faithful port of TuneNotchWizardDialog#computeNotch.
 *
 * @param {ArrayLike<number>} freqs  ascending frequency grid (Hz)
 * @param {ArrayLike<number>} magLin linear magnitude at each grid point
 * @returns {{valid: boolean, hz: number, db: number}} the refined null
 *   frequency (Hz) and depth (dB); valid=false when the arrays are unusable
 */
export function findDeepestNotch(freqs, magLin) {
  if (!magLin || !freqs || magLin.length === 0 || magLin.length !== freqs.length) {
    return { valid: false, hz: 0, db: 0 };
  }
  let idx = 0;
  for (let i = 1; i < magLin.length; i++) {
    if (magLin[i] < magLin[idx]) idx = i;
  }
  let f = freqs[idx];
  let m = magLin[idx];
  if (idx > 0 && idx < magLin.length - 1) {
    const ym = magLin[idx - 1], y0 = magLin[idx], yp = magLin[idx + 1];
    const denom = ym - 2.0 * y0 + yp;
    if (denom > 0.0) {
      const delta = Math.max(-0.5, Math.min(0.5, (0.5 * (ym - yp)) / denom));
      f = freqs[idx] + delta * (freqs[idx + 1] - freqs[idx]);
      const v = y0 - 0.25 * (ym - yp) * delta;
      if (v > 0.0) m = v;
    }
  }
  return { valid: true, hz: f, db: 20.0 * Math.log10(m) };
}

/**
 * Linear-interpolates the magnitude → dB at frequency {@code f} (the displayed
 * grid is monotonic ascending) — the measured attenuation AT the target
 * frequency that colours the wizard's target marker. Faithful port of
 * TuneNotchWizardDialog#dbAt.
 *
 * @param {ArrayLike<number>} freqs  ascending frequency grid (Hz)
 * @param {ArrayLike<number>} magLin linear magnitude at each grid point
 * @param {number} f                 frequency to sample (Hz)
 * @returns {number} magnitude in dB at f, or NaN when unusable/non-positive
 */
export function dbAtFrequency(freqs, magLin, f) {
  if (!freqs || !magLin || freqs.length < 2 || freqs.length !== magLin.length) {
    return NaN;
  }
  let hi = 1;
  while (hi < freqs.length - 1 && freqs[hi] < f) hi++;
  const lo = hi - 1;
  const span = freqs[hi] - freqs[lo];
  let w = span > 0.0 ? (f - freqs[lo]) / span : 0.0;
  w = Math.max(0.0, Math.min(1.0, w));
  const m = magLin[lo] * (1.0 - w) + magLin[hi] * w;
  return m > 0.0 ? 20.0 * Math.log10(m) : NaN;
}

/**
 * Self-contained continuous play+capture session for the Tune-notch wizard's
 * live sweep loop. Faithful port of gui.freqresp.NotchSweepEngine (see the
 * module header for the documented web adaptations of its JavaSound half).
 */
export class NotchSweepEngine {
  /**
   * @param {Object} deps injected collaborators (the web stand-ins for Java's
   *   DeviceRef pair; bitDepth/ditherBits are intended web divergences)
   * @param {import('../audio/shared-capture.js').SharedCapture} deps.sharedCapture
   *   the one ref-counted ADC capture (acquired on start, released on close)
   * @param {(msg: Object) => void} deps.postGen posts a live message to the
   *   running dds-processor worklet (the caller owns the generator lifecycle)
   * @param {number} deps.sampleRate capture sample rate (Hz) the deconvolution
   *   runs at
   */
  constructor({ capture, config, startGenerator, stopGenerator, postGen, sampleRate }) {
    this._capture = capture;                 // the dedicated MEASUREMENT capture (own device line)
    this._config = config;                   // shared DDS config — snapshot/set/restore within
    this._startGenerator = startGenerator;   // () => start the DDS generator (returns error key|null)
    this._stopGenerator = stopGenerator;     // () => stop the DDS generator
    this._postGen = postGen;
    this._sampleRate = sampleRate;
    // Session state, set up in start() and torn down in close().
    this._reader = null;         // SignalBufferReader over the measurement ring
    this._startWritePos = 0;     // ring write position at start() — the anchor
    this._sweepSamples = 0;
    this._fadeSamples = 0;
    this._sweepRefBuf = null;    // cached one-period reference X(t)
    this._savedGenConfig = null; // pre-session DDS config, restored on close()
    this._genStarted = false;
    this._running = false;
  }

  /**
   * Deconvolution FFT bin spacing (Hz) for a single captured period of
   * {@code sweepSamples} — sampleRate / nextPow2(sweepSamples). The wizard
   * matches its output-grid density to this so the bin→grid interpolation
   * never oversamples (oversampling facets the trace into a kink + comb).
   * Reflects the actual yRec length the wizard passes to computeFromLogSweep
   * (one period, leadIn 0).
   *
   * @param {number} sweepSamples loop period in samples
   * @returns {number} bin spacing in Hz
   */
  deconvBinHz(sweepSamples) {
    return this._sampleRate / nextPow2(sweepSamples);
  }

  /**
   * The looping sweep's one-period reference X(t) — the buffer
   * computeFromLogSweep deconvolves against (unwindowed; the deconvolution
   * applies the seam fade itself via its fadeSamples parameter, exactly like
   * the Java generator's raw buffer).
   *
   * @returns {Float64Array|null} the reference, or null before start()
   */
  sweepRef() {
    return this._sweepRefBuf;
  }

  // ---------------------------------------------------------------------------
  // Session lifecycle: open once, stream forever, close once
  // ---------------------------------------------------------------------------

  /**
   * Acquires the shared capture ONCE, points the DDS worklet at a looping
   * Farina log-sweep (Hann fades at each cycle boundary: the kernel replays
   * the buffer back-to-back and the per-side fades smooth the loop seam — the
   * SAME fadeSamples must be applied to the deconvolution reference), and
   * anchors the ring so only session samples are ever served.
   *
   * @param {number} f0           sweep start (Hz) — the WIDENED band, see {@link sweepBand}
   * @param {number} f1           sweep stop (Hz)
   * @param {number} ampVrms      drive amplitude (V RMS)
   * @param {number} dacFsVrms    DAC full-scale voltage the amplitude scales against
   * @param {number} sweepSamples loop period in samples (a power of two)
   * @param {number} fadeSamples  per-side Hann seam-fade length in samples
   * @param {string} outputChannels which DAC lane(s) carry the sweep — 'BOTH' (legacy) /
   *   'LEFT' / 'RIGHT'; the un-driven lane is written as digital silence
   * @param {number} rightLaneScale right-lane full-scale scale (= fsLeft/fsRight) so a
   *   LINKED card with distinct DAC full-scales emits the same physical level on both
   *   lanes (Java NotchSweepEngine.start's setChannelScale(1.0, rightLaneScale)); the
   *   left lane is never scaled
   * @throws {Error} when the input device cannot be opened
   */
  async start(f0, f1, ampVrms, dacFsVrms, sweepSamples, fadeSamples,
    outputChannels = 'BOTH', rightLaneScale = 1.0) {
    this._sweepSamples = sweepSamples;
    this._fadeSamples = fadeSamples;
    this._sweepRefBuf = renderLogSweep(f0, f1, sweepSamples, this._sampleRate);

    // Snapshot the shared DDS config, then point the generator at a SILENT looping Farina
    // sweep and start it — this engine owns the generator lifecycle (the wizard no longer
    // touches engine.config or startGenerator; Java NotchSweepEngine owns its own playback).
    const c = this._config;
    this._savedGenConfig = {
      form: c.form, ampVrms: c.ampVrms,
      sweepStartHz: c.sweepStartHz, sweepEndHz: c.sweepEndHz,
      sweepDurationSec: c.sweepDurationSec, sweepLoop: c.sweepLoop,
      sweepFadeInSec: c.sweepFadeInSec, sweepFadeOutSec: c.sweepFadeOutSec,
      outputChannels: c.outputChannels, rightLaneScale: c.rightLaneScale,
    };
    c.form = GenSignalForm.LOG_SWEEP;
    c.ampVrms = 0;   // start silent — the postGen below un-mutes with the real amplitude
    c.sweepStartHz = f0; c.sweepEndHz = f1;
    c.sweepDurationSec = sweepSamples / this._sampleRate;
    c.sweepLoop = true;
    c.sweepFadeInSec = fadeSamples / this._sampleRate;
    c.sweepFadeOutSec = fadeSamples / this._sampleRate;
    // Match the RIGHT lane's physical level to the LEFT-referenced digital amplitude, then
    // gate the looping sweep to the selected DAC lane(s) — Java NotchSweepEngine.start's
    // setChannelScale(1.0, rightLaneScale) + setOutputChannels. startGenerator reads these
    // off the config into the worklet's processorOptions, so they apply from block 0; the
    // pre-session values are restored by _restoreGenConfig on close (snapshot above).
    c.outputChannels = outputChannels;
    c.rightLaneScale = rightLaneScale;
    const startErr = await this._startGenerator();
    if (startErr) { this._restoreGenConfig(); throw new Error(startErr); }
    this._genStarted = true;

    // Open OUR OWN measurement capture line (device-isolated from scope/FFT).
    this._reader = await this._capture.acquire();
    if (!this._reader) {
      const err = this._capture.getLastStartError() || 'capture start failed';
      await this._stopGeneratorAndRestore();
      throw new Error(err);
    }
    this._startWritePos = this._reader.getWritePos();

    // Point the now-running DDS at the looping sweep at the real amplitude.
    this._postGen({
      amplitudeVRms: ampVrms,
      dacFsVoltageAmpl: dacFsVrms,
      logSweep: { f0, f1, sweepSamples, leadInSamples: 0 },
      sweepParams: { loop: true, fadeInSamples: fadeSamples, fadeOutSamples: fadeSamples },
      resetSweepPosition: true,
    });
    this._postGen({ form: GenSignalForm.LOG_SWEEP });
    this._running = true;
  }

  /** Restores the pre-session DDS config snapshot (no generator action). */
  _restoreGenConfig() {
    if (this._savedGenConfig) {
      Object.assign(this._config, this._savedGenConfig);
      this._savedGenConfig = null;
    }
  }

  /** Stops the generator (if started) and restores the pre-session DDS config. */
  async _stopGeneratorAndRestore() {
    if (this._genStarted) {
      this._genStarted = false;
      try { await this._stopGenerator(); } catch (_) { /* ignore */ }
    }
    this._restoreGenConfig();
  }

  /**
   * Live band change WITHOUT restart: reconfigures the looping sweep in place
   * (the kernel re-renders its buffer and rewinds to the new sweep's start,
   * exactly like Java's rebuildLogSweepBuffer) and re-renders the local
   * reference to match. No-op before start() / after close().
   *
   * @param {number} f0 new sweep start (Hz, widened band)
   * @param {number} f1 new sweep stop (Hz)
   */
  setBand(f0, f1) {
    if (!this._running) return;
    this._sweepRefBuf = renderLogSweep(f0, f1, this._sweepSamples, this._sampleRate);
    this._postGen({ logSweep: { f0, f1, sweepSamples: this._sweepSamples, leadInSamples: 0 } });
  }

  /**
   * Live output-lane change WITHOUT restart: pushes the gate straight to the
   * running worklet (Java NotchSweepEngine.setOutputChannels). Only the gate is
   * pushed live — the right-lane scale was fixed at start(). Updates the shared
   * config too so a later worklet retune keeps the same lane. No-op before
   * start() / after close().
   *
   * @param {string} outputChannels 'BOTH' | 'LEFT' | 'RIGHT'
   */
  setOutputChannels(outputChannels) {
    if (!this._running) return;
    this._config.outputChannels = outputChannels;
    this._postGen({ outputChannels });
  }

  /**
   * Total samples captured since start() — drives the settle / fill
   * percentage.
   *
   * @returns {number} session sample count (0 before start)
   */
  availableSamples() {
    if (!this._reader) return 0;
    return this._reader.getWritePos() - this._startWritePos;
  }

  /**
   * Copies the most recent {@code n} samples (L and R) from the shared ring
   * into fresh Float64Arrays. Returns null if fewer than {@code n} samples
   * have been captured this session (still settling).
   *
   * @param {number} n window length in samples (one loop period)
   * @returns {{left: Float64Array, right: Float64Array}|null} the window,
   *   ending at the latest captured sample
   */
  latestPeriod(n) {
    if (!this._reader || this._reader.getWritePos() - this._startWritePos < n) {
      return null;
    }
    const left = new Float64Array(n);
    const right = new Float64Array(n);
    if (this._reader.readLatest(n, left, right) < n) return null;
    return { left, right };
  }

  /**
   * Ends the streaming session ONCE: releases the shared capture reference
   * (closing the input device if this was the last consumer). Restoring the
   * DDS worklet to the pre-session form is the CALLER's job (it commandeered
   * the shared generator — mirror of FreqRespHost.runSweep's restore), unlike
   * Java where the engine owned a private playback line. Idempotent; a stray
   * setBand() after close is a no-op.
   */
  async close() {
    if (!this._running && !this._reader && !this._genStarted) return;
    this._running = false;
    const reader = this._reader;
    this._reader = null;
    this._sweepRefBuf = null;
    if (reader) await this._capture.release();
    await this._stopGeneratorAndRestore();
  }
}
