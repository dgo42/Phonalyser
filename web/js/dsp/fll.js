/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of the desktop DerotationPhaseLock (org.edgo.audio.measure.fft).
//
// Coherent cross-tick averaging sums each tick's spectrum after de-rotating it
// back to a common time origin using a fractional fundamental bin `kappa`. If
// kappa is slightly wrong the de-rotated fundamental phase drifts linearly with
// the tick's sample offset, smearing the fundamental and shrinking its peak.
//
// This tracks kappa with a MEASURE-THEN-CORRECT loop (unconditionally stable -
// a naive per-tick PLL diverges because the growing offset lever-arm feeds back
// the kappa it is adjusting). Over a window of ticks kappa is held fixed while
// the de-rotated fundamental phase is observed; the phase advances linearly with
// sample offset at slope 2π·(κ_true − κ)/N, so a slope fit gives the error
// directly and one full correction nulls it. When the residual per-tick drift
// stays tiny for a few windows it declares lock.

/** Wrap an angle to (−π, π]. */
export function wrapToPi(a) {
  let x = (a + Math.PI) % (2 * Math.PI);
  if (x < 0) x += 2 * Math.PI;
  return x - Math.PI;
}

export class DerotationPhaseLock {
  /**
   * @param {number} fftSize       FFT length N.
   * @param {number} seedKappa     initial fractional fundamental bin.
   * @param {number} window        ticks per measurement window (≥ 2).
   * @param {number} lockDriftRad  per-tick drift below which a window is "stable".
   *                               Must be TIGHT (e.g. 0.001 rad): a tiny per-tick
   *                               drift integrates to a large smear over hundreds
   *                               of ticks.
   * @param {number} lockWindows   consecutive stable windows to declare lock.
   * @param {number} maxWindows    fallback: lock with best κ after this many
   *                               windows (so a noisy signal can't lock forever).
   */
  constructor(fftSize, seedKappa, window, lockDriftRad, lockWindows, maxWindows) {
    this._fftSize = fftSize;
    this._kappa = seedKappa;
    this._window = Math.max(2, window);
    this._lockDriftRad = lockDriftRad;
    this._lockWindows = Math.max(1, lockWindows);
    this._maxWindows = Math.max(lockWindows + 1, maxWindows);
    this._locked = false;
    this._hasPrev = false;
    this._prevPhase = 0; this._prevDelta = 0;
    this._sumDPhi = 0; this._sumDDelta = 0;
    this._count = 0; this._stableWindows = 0; this._totalWindows = 0;
  }

  get kappa()  { return this._kappa; }
  get locked() { return this._locked; }

  /**
   * Feed the de-rotated fundamental phase observed at sample offset `delta`
   * (relative to the de-rotation reference). kappa is held fixed within a window;
   * at each window end it is corrected by the measured phase slope.
   * @returns {boolean} true once locked (freeze kappa and start accumulating).
   */
  observe(phaseObs, delta) {
    if (this._locked) return true;
    if (this._hasPrev) {
      this._sumDPhi += wrapToPi(phaseObs - this._prevPhase);
      this._sumDDelta += (delta - this._prevDelta);
      this._count++;
    }
    this._prevPhase = phaseObs;
    this._prevDelta = delta;
    this._hasPrev = true;

    if (this._count >= this._window && this._sumDDelta !== 0) {
      this._totalWindows++;
      const slope = this._sumDPhi / this._sumDDelta;            // rad/sample
      const perTickDrift = Math.abs(this._sumDPhi) / this._count;
      if (perTickDrift < this._lockDriftRad) {
        if (++this._stableWindows >= this._lockWindows) this._locked = true;
      } else {
        this._stableWindows = 0;
        this._kappa += slope * this._fftSize / (2 * Math.PI);  // full correction
      }
      if (!this._locked && this._totalWindows >= this._maxWindows) this._locked = true;
      this._sumDPhi = 0; this._sumDDelta = 0; this._count = 0; this._hasPrev = false;
    }
    return this._locked;
  }
}

// Faithful port of the desktop FrequencyFll (org.edgo.audio.measure.gui.fft).
//
// One-step DEADBEAT loop with EXACT transport bookkeeping. When the app drives the
// signal (loopback) the generator's commanded frequency is steered onto the FFT bin
// grid by feeding back the per-frame error between the commanded `target` and the
// FFT's refined `detected` estimate. The published generator frequency is always
// `target + correction`.
//
// The crucial property - and the regression a prior pass broke by steering every
// frame undamped - is the TRANSPORT GATE: a correction published now still has the
// OLD tone queued ahead of it in the DAC's hardware buffer, so it only becomes
// MEASURABLE once the capture head advances past `latestSamplePos + drain`. Until a
// measurement window provably STARTS past that point, every update is held: such a
// measurement predates the correction and acting on it would stack a second
// correction onto an error the in-flight one already cancels - the sawtooth/runaway.
// Gating on capture SAMPLE POSITIONS (not call counts or wall-clock) makes the loop
// immune to overruns, display throttling and GC pauses: a transient mis-measurement
// costs exactly one bounded, fully observed round trip.
//
// On top of the deadbeat sits a DRIFT FEEDFORWARD: the EWMA-tracked rate at which
// the error rebuilds between corrections (the relative wander of the two free-running
// converter crystals) is applied predictively every update, so the error stays near
// zero instead of sawtoothing to the lock-band edge once per transport round-trip.
export class FrequencyFll {
  /** Steady-state lock band (ppm of target): hold within it, re-correct when drift
   *  leaves it. Must be ≈ the measurement's own frequency jitter. */
  static LOCK_PPM = 0.01;
  /** Output-pipeline drain guard (seconds): a correction published now still has the
   *  OLD tone queued ahead of it in the DAC's hardware buffer (≈480 ms render path). */
  static DRAIN_GUARD_SEC = 0.7;
  /** EWMA weight on the previous drift estimate when a deadbeat folds a fresh slope
   *  measurement in - smooths the estimator noise riding on each band exit. */
  static DRIFT_SMOOTH = 0.7;
  /** Drift sanity cap (ppm of target per second): anything larger is a mis-measurement. */
  static MAX_DRIFT_PPM_PER_SEC = 0.01;
  /** Minimum capture-time baseline between two deadbeats for the residual to qualify
   *  as a slope measurement - closer exits are estimator noise, not wander. */
  static MIN_DRIFT_BASELINE_SEC = 2.0;

  constructor() {
    this.reset();
  }

  /** Current correction in Hz - add to the snap target before publishing the trim. */
  get correction() { return this._correction; }

  /** Read-only view of the transport gate (Java FrequencyFll.correctionVisibleFrom):
   *  the absolute capture sample position from which a measurement window reflects the
   *  last issued correction; -1 = nothing in flight (every measurement is usable).
   *  Exposed so the FLL trace can report whether the gate is holding and when it opens. */
  get correctionVisibleFrom() { return this._correctionVisibleFrom; }

  /**
   * Feeds one FFT measurement in and updates the correction.
   * @param {number} target           commanded (snapped) target frequency, Hz.
   * @param {number} detected         FFT's refined fundamental estimate, Hz.
   * @param {number} absStartSamples  the analysis window's absolute capture start.
   * @param {number} latestSamplePos  live capture write head at publish time.
   * @param {number} sampleRate       capture sample rate.
   * @param {number} fftSize          analysis window length (unused; kept for parity).
   */
  update(target, detected, absStartSamples, latestSamplePos, sampleRate, fftSize) {
    if (!Number.isFinite(target) || !Number.isFinite(detected) || !(target > 0)) return;
    // Drift feedforward: predictive sub-band microsteps on EVERY update (held ones
    // included - prediction needs no transport verification; its residual is folded
    // back in at the next deadbeat). dt comes from capture positions.
    if (this._lastUpdateAbsPos >= 0 && sampleRate > 0 && absStartSamples > this._lastUpdateAbsPos) {
      const dt = (absStartSamples - this._lastUpdateAbsPos) / sampleRate;
      this._correction -= this._driftHzPerSec * dt;
    }
    this._lastUpdateAbsPos = absStartSamples;
    // Transport gate: if the window's frames begin before the corrected signal
    // reached the ADC, the measurement reflects the UNcorrected signal - hold.
    if (this._correctionVisibleFrom >= 0 && absStartSamples < this._correctionVisibleFrom) return;
    this._correctionVisibleFrom = -1;            // in-flight correction fully observed
    const error = detected - target;
    if (Math.abs(error) <= FrequencyFll.LOCK_PPM * 1e-6 * target) return;   // within lock band - hold
    // Fold the residual into the drift estimate: error that accumulated since the
    // last deadbeat DESPITE the feedforward measures the slope-estimation error.
    if (this._lastDeadbeatAbsPos >= 0 && sampleRate > 0) {
      const dtc = (absStartSamples - this._lastDeadbeatAbsPos) / sampleRate;
      if (dtc >= FrequencyFll.MIN_DRIFT_BASELINE_SEC) {
        const cap = target * FrequencyFll.MAX_DRIFT_PPM_PER_SEC * 1e-6;
        const sMeas = Math.max(-cap, Math.min(cap, error / dtc));
        this._driftHzPerSec = FrequencyFll.DRIFT_SMOOTH * this._driftHzPerSec
          + (1.0 - FrequencyFll.DRIFT_SMOOTH) * sMeas;
        this._driftHzPerSec = Math.max(-cap, Math.min(cap, this._driftHzPerSec));
      }
    }
    this._lastDeadbeatAbsPos = absStartSamples;
    // Cancel the (fully observed) error in one step, then wait until the capture
    // provably contains the corrected signal.
    this._correction -= error;
    if (sampleRate > 0) {
      this._correctionVisibleFrom = latestSamplePos
        + Math.ceil(FrequencyFll.DRAIN_GUARD_SEC * sampleRate);
    }
  }

  /** Zeroes the loop state. Call on Record stop and on user-initiated generator-
   *  frequency / FFT-length changes (both invalidate the lock). */
  reset() {
    this._correction = 0.0;
    this._correctionVisibleFrom = -1;
    this._driftHzPerSec = 0.0;
    this._lastUpdateAbsPos = -1;
    this._lastDeadbeatAbsPos = -1;
  }
}
