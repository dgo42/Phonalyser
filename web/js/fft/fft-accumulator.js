/*
 * Phonalyser web - cross-tick FFT averaging accumulator.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of the cross-tick running accumulator in
// org.edgo.audio.measure.gui.fft.FftAnalyzerWorker - the part the desktop calls
// accumulateIntoForeverBuffer(...) + overlayAccumulatorOnto(...) + resetAccumulator().
//
// WHY THIS EXISTS - the web port previously had NO cross-tick accumulator: each
// FFT tick re-FFT'd a window sized to the WHOLE averaging depth
// (bufLen = N + (avgTarget−1)·hop), so the first spectrum only appeared after
// bufLen/inRate seconds (~24 s at 44.1 kHz avg=16; minutes in ∞ mode). The
// desktop sizes each tick to ~1 frame and DEEPENS the average across ticks via
// this running accumulator, so the first spectrum lands in <0.2 s and the
// √N SNR boost grows tick by tick. This module ports that brain.
//
// The per-window analyze() (windowing, FFT, sub-bin fundamental, harmonic table,
// THD/SNR) stays in FftAnalyzer; here we only FOLD each tick's raw half-spectrum
// (re/im for coherent, |X|² for incoherent) into the running sum and OVERLAY the
// cumulative average back onto a result for display, exactly as the desktop does.
//
// PORTED (the core that makes a stable tone average correctly):
//   - COHERENT REGION-CONSTANT de-rotation: a windowed tone's lobe - the peak and
//     its off-centre bins together - advances by ONE common phase per tick, so a
//     per-bin ramp mis-rotates every off-centre lobe bin and an overlapped deep
//     average carves the lobe into a comb. Each member of the COHERENT FAMILY -
//     the harmonic ladder h·κ (h = 0 included, identity phasor) merged
//     position-sorted with the published intermodulation grid a·κ1 + b·κ2 when a
//     dual pair is pinned - carries ONE derived phasor a·Φ1 + b·Φ2, and the
//     regions tile the half spectrum at the MIDPOINTS between neighbouring
//     members. The family is KNOWLEDGE, not a spectral property: no edge is ever
//     measured, so no environmental line can earn a region of its own.
//     Φ1 = −2π·IEEEremainder(delta·accumKFractional, fftSize)/fftSize, where
//     delta = samplesAbsStart − accumRefSampleStart. accumRefSampleStart +
//     accumKFractional + accumIntFundBinRounded + accumKappa2 are PINNED on the
//     first tick.
//   - RECT exception: with the frequency lock on-bin a rectangular window's tone
//     occupies a SINGLE bin - there is no multi-bin lobe to hold together - while
//     its 1/Δf leakage skirt spans the whole spectrum and would cross every region
//     midpoint, which is exactly where a piecewise rotation prints. So RECT keeps
//     the per-bin time-shift ramp, its phasor seeded once and advanced by a
//     recurrence.
//   - INCOHERENT power sum: Σ |X[k]|² weighted by frameCount (the analyzer stores
//     RAW magnitude in r.re for incoherent, so we sum mag·mag - no double convert).
//   - targetN scaling: finite avgTarget -> exponential ring window; ∞ -> cumulative.
//   - overlay: rebuild amplitudeDbFs/phaseDeg/re/im off the running average.
//
// PORTED (the long-run refinements - full parity with the desktop now):
//   - One-shot κ PHASE-SLOPE REFINE: the single-frame parabolic
//     peak pins κ to only ~0.01-0.03 bin; over a long coherent run that residual
//     ramps the de-rotated fundamental phase (2π·Δκ·delta/fftSize) and vector-
//     cancels the magnitude (harmonics h× faster). dφ/dΔ over KAPPA_MEAS_FRAMES
//     CLEAN frames yields Δκ ~100× tighter, folded into accumKFractional ONCE.
//   - Continuous PHASE-LOCK LOOP (PLL): once κ is refined, the de-rotated phase is
//     measured against the DEEP accumulated phase and a fraction (PHASE_TRACK_GAIN)
//     of the residual is folded into accumDroppedSamples each tick, killing the
//     residual κ ramp and absorbing disturbances.
//   - The FORK: that steady per-tick estimate is POOLED over the COHERENT FAMILY
//     ONLY - the pinned fundamental(s), the harmonic ladder h*kappa and the published
//     product grid a*kappa1 + b*kappa2 - never a spectral peak. Membership is
//     knowledge, not level: the threshold only FILTERS family members, and no line
//     outside the family can enter however strong it stands. A common sample offset rotates
//     each line proportionally to its frequency, so each line's residual divided by
//     its own fractional bin estimates the SAME offset; the estimates are averaged
//     with (fractional bin x magnitude)² weights - variance-optimal, since a line's
//     phase noise is floor/|X| while its offset sensitivity grows with frequency.
//     Behind a notch the fundamental is the WEAKEST line, so the pooled reference
//     noise falls with the combined power of the harmonics. A re-sync's one-shot
//     FULL realign stays on the fundamental alone: a large offset wraps the higher
//     lines, where pooling is not valid.
//   - The SUBTRACTABLE RING: the last DISCONTINUITY_DROP_TICKS accepted ticks are
//     held exactly as they entered (rotation and weight applied, and scaled along
//     with any bounded-window scale-down), for the coherent AND the incoherent path
//     alike, so a discontinuity gate can subtract them back out - the window before
//     the detecting one shares nearly all its samples, with the event sitting at its
//     taper edge below the thresholds.
//   - Re-anchor on a re-sync (overrun / discontinuity): onResync() sets the κ skip
//     flag + gapRecoverPending so the jumped frame re-anchors instead of poisoning
//     the slope measurement.
//   - The SpectralDiscontinuityDetector gate (USE_SPECTRAL_DISCONTINUITY): the
//     accumulator OWNS the detector (as the desktop worker does) and resets it in
//     reset(); the controller calls reject() per tick before the fold.
//
// PARALLELISM NOTE - the desktop runs the per-bin de-rotation loops across cores
// (parallelChunks). The math is a disjoint per-bin write, so this port runs the
// SAME loops SERIALLY in one pass: bit-for-bit the same result, just single-threaded
// (the cross-tick fold is already off the main thread on the FFT worker / pool).

import { SpectralDiscontinuityDetector } from '../dsp/spectral-discontinuity-detector.js';
import { ieeeRemainder } from '../dsp/mathutil.js';

/** Frames of de-rotated-fundamental phase observed before the one-shot κ refine -
 *  ~24 give κ to ~1e-4 bin at high SNR, before the drift becomes visible. */
const KAPPA_MEAS_FRAMES = 24;
/** Largest correction the one-shot κ refine may apply, in bins. The refine corrects the
 *  single-frame peak's residual (~0.01-0.03 bin); a phase slope that asks for more than half
 *  a bin is not a refinement of a tone but the random-walk phase of a noise peak pinned as
 *  the fundamental (no generator, a muted DUT), and folding it moved κ toward zero - where
 *  the harmonic ladder h·κ < halfSize runs to millions of lines per tick and the analyser
 *  crawled at the 24th frame. Such a measurement is discarded and re-anchored instead. */
const KAPPA_REFINE_MAX_BIN = 0.5;
/** Below this pinned fundamental bin there is no harmonic family to pool or to rotate by:
 *  the fork and the region family both stand down and the accumulator adds plain. */
const KAPPA_FAMILY_MIN_BIN = 1.0;
/** Phase-tracking-loop gain: fraction of the running fundamental mis-alignment
 *  folded into the de-rotation each tick. Low enough to average out per-tick phase
 *  noise, high enough to track the κ-residual ramp (Java PHASE_TRACK_GAIN). */
const PHASE_TRACK_GAIN = 0.10;
/** Default dB below the fundamental a family line must clear to be admitted to the
 *  pooled tracking estimate.  The desktop reads this LIVE from
 *  Preferences#getFftStrongToneRelDb (default 100.0); the web mirrors that via
 *  setStrongToneRelDb(), seeded here. */
const STRONG_TONE_REL_DB_DEFAULT = 100.0;
/** Spectral-discontinuity gate toggle (Java USE_SPECTRAL_DISCONTINUITY). */
const USE_SPECTRAL_DISCONTINUITY = true;
/** A fork line whose accumulated-vs-fresh phase difference sits within this of the
 *  ±π wrap is dropped for the tick - folded to the wrong side it would pull the
 *  pooled offset instead of refining it (Java FORK_WRAP_GUARD_RAD). */
const FORK_WRAP_GUARD_RAD = Math.PI / 2.0;
/** Ticks BEFORE a detected discontinuity whose contribution is REMOVED from the
 *  accumulator when a gate fires. The preceding tick's window shares nearly all
 *  its samples with the detecting one, so the event usually sits at its
 *  window-taper EDGE - attenuated below the gate thresholds but still poisoning
 *  the average. The last this-many accepted ticks are kept subtractable; the
 *  rejected tick itself never enters the accumulator, so the total loss per event
 *  is this constant plus the detecting tick (Java DISCONTINUITY_DROP_TICKS). */
const DISCONTINUITY_DROP_TICKS = 1;

/** Java Math.floorMod for the ring's backward slot walk: JS % keeps the
 *  dividend's sign, so a negative index would address outside the ring. */
function floorMod(x, m) {
  return ((x % m) + m) % m;
}

export class FftAccumulator {
  constructor() {
    /** @type {?Float64Array} coherent running complex sum, Re (length halfSize+1). */
    this._accumRe = null;
    /** @type {?Float64Array} coherent running complex sum, Im. */
    this._accumIm = null;
    /** @type {?Float64Array} incoherent running power sum (|X|²·weight). */
    this._accumPow = null;
    /** Ring of the last DISCONTINUITY_DROP_TICKS accepted ticks' contributions
     *  EXACTLY as added (rotation and weight applied), so a gate firing can
     *  subtract them back out. Coherent: re/im; incoherent: power. Sized like the
     *  accumulator at restart, so BOTH paths are subtractable, as the desktop's are.
     *  @type {?Array<Float64Array>} */
    this._prevTickRe = null;
    /** @type {?Array<Float64Array>} */ this._prevTickIm = null;
    /** @type {?Array<Float64Array>} */ this._prevTickPow = null;
    /** Frame weight held in each ring slot - what a subtraction gives back to the
     *  depth counter. @type {?Int32Array} */
    this._prevTickWeight = null;
    /** Slots of the ring currently valid (cleared by a subtraction, a restart and a
     *  reset), and the next slot to write. */
    this._prevTickCount = 0;
    this._prevTickPos = 0;
    /** Total frame count accumulated since the last reset (the "N average(s)" depth). */
    this._accumFrames = 0;
    /** True once at least one tick has contributed. */
    this._accumHasData = false;
    /** Absolute sample-stream position of the first contributing tick's frame-0. */
    this._accumRefSampleStart = 0;
    /** Pinned de-rotation frequency (fractional fundamental bin) - held constant. */
    this._accumKFractional = 0;
    /** Pinned round(kFractional) - the stable integer lobe pitch. */
    this._accumIntFundBinRounded = 0;
    /** The second fundamental's pinned fractional bin (dual pair), pinned at the
     *  accumulator restart like accumKFractional; NaN when the run is single-tone. */
    this._accumKappa2 = NaN;
    /** Config the accumulator was built for; a mismatch restarts it. */
    this._accumFftSize = 0;
    this._accumCoherent = false;
    /** Coherent-family scratch: member positions (fractional bins, ascending) and
     *  each member's constant de-rotation phasor for the current tick. Rebuilt per
     *  tick by _buildFamily; grown on demand (Java famPos / famCos / famSin). */
    this._famPos = new Float64Array(0);
    this._famCos = new Float64Array(0);
    this._famSin = new Float64Array(0);

    // ─── κ phase-slope refine (single-reference) ──────────────────────────────
    /** True once the one-shot κ refine has folded Δκ into accumKFractional. */
    this._kappaRefined = false;
    /** CLEAN frames counted toward the slope (the anchor + folded steps). */
    this._kappaMeasFrames = 0;
    /** delta at the running reference frame. */
    this._kappaLastDelta = 0;
    /** de-rotated fundamental phase at the reference frame. */
    this._kappaLastPhase = 0;
    /** Σ clean per-frame phase steps (slope numerator). */
    this._kappaCumPhase = 0;
    /** Σ clean per-frame delta steps (slope denominator). */
    this._kappaCleanSpan = 0;
    /** Next frame is post-re-sync: re-anchor, don't fold its jumped step. */
    this._kappaSkipNext = false;

    // ─── continuous phase-lock loop (PLL) ─────────────────────────────────────
    /** Cumulative de-rotation correction (sub-sample), driven by the PLL. */
    this._accumDroppedSamples = 0;
    /** A re-sync fired; do a one-shot full realign on the next clean frame. */
    this._gapRecoverPending = false;

    // ─── spectral-discontinuity gate (the accumulator OWNS the detector, as the
    //     desktop worker does; reset() restarts it with the average) ────────────
    this._spectralDetector = new SpectralDiscontinuityDetector();

    /** dB below the fundamental a family line must clear to be admitted to the
     *  pooled tracking estimate (Java Preferences#getFftStrongToneRelDb, read
     *  live). The controller pushes the live pref value via setStrongToneRelDb(). */
    this._strongToneRelDb = STRONG_TONE_REL_DB_DEFAULT;
  }

  /** Sets the line-admission threshold in dB below the fundamental (Java
   *  Preferences#getFftStrongToneRelDb, read live). The controller calls this at
   *  setup and on the pref-change listener so a change takes effect on the next
   *  tick, as the desktop does. */
  setStrongToneRelDb(db) {
    this._strongToneRelDb = db;
  }

  /** Cumulative frame depth since the last reset - drives the "N/avgTarget"
   *  readout and the stop-after-N threshold (Java getAccumulatedFrames). */
  get accumFrames() { return this._accumFrames; }
  /** True once at least one tick has been folded in. */
  get hasData() { return this._accumHasData; }
  /** Pinned coherent κ (fractional fundamental bin) for the plot-time "before"
   *  dots; 0 before the first contribution. */
  get accumKFractional() { return this._accumKFractional; }
  /** Whether the running average is the COMPLEX one (coherent) or the power one. */
  get isCoherent() { return this._accumCoherent; }
  /** The running sums, READ ONLY - what the cumulative overlay divides by the depth. Exposed so
   *  the display preparation can be handed a COPY of them and run on another thread; the arrays
   *  themselves stay this accumulator's, because the next tick's fold writes them while that
   *  copy is being drawn. Null before the first contribution, and for the path not in use. */
  get sumRe() { return this._accumRe; }
  get sumIm() { return this._accumIm; }
  get sumPow() { return this._accumPow; }

  /** Drops the running accumulator (Java resetAccumulator). Arrays are dropped,
   *  not cleared in place, so a stale in-flight tick can't resurrect them. */
  reset() {
    this._accumRe = null;
    this._accumIm = null;
    this._accumPow = null;
    this._accumFrames = 0;
    this._accumHasData = false;
    this._prevTickCount = 0;
    // The running-median discontinuity rejector restarts with the average (its
    // median reference must be re-learned from the fresh run).
    this._spectralDetector.reset();
  }

  /** Re-anchor after a re-sync (ring overrun / signal discontinuity): the absolute
   *  delta jumps, so the next clean frame must re-anchor the κ slope (not fold its
   *  jumped step) and the PLL does a one-shot full realign. Mirrors the desktop
   *  onCaptureOverrun / onSignalDiscontinuity (kappaSkipNext / gapRecoverPending).
   *  Called by the controller when it re-anchors the cursor. */
  onResync() {
    this._kappaSkipNext = true;        // κ measurement: re-anchor on the jumped frame
    this._gapRecoverPending = true;    // one-shot full realign on the next clean frame
  }

  /** Runs the spectral-discontinuity gate on this tick's complex half-spectrum
   *  (Java FftAnalyzerWorker: spectralDetector.reject before the cross-tick fold).
   *  De-rotation does not change magnitude, so the per-window (pre-fold) re/im are
   *  the right thing to gate. @returns {boolean} true => REJECT this block. */
  reject(re, im, halfSize, binWidthHz, peakBins) {
    if (!USE_SPECTRAL_DISCONTINUITY) return false;
    this._spectralDetector.configure(halfSize);
    return this._spectralDetector.reject(re, im, halfSize, binWidthHz, peakBins);
  }

  /** The spectral gate's last-decision diagnostics (which gate fired, values vs
   *  thresholds) - read by the controller's debug logging after a reject. */
  get spectralDiagnostics() {
    return this._spectralDetector;
  }

  /**
   * Folds one tick's RAW per-window spectrum into the running accumulator
   * (Java FftAnalyzerWorker.accumulateIntoForeverBuffer). For coherent mode it
   * applies the single-reference per-lobe constant-phase de-rotation that aligns
   * this tick's frame-0 with the pinned reference; for incoherent it sums power.
   * Resets transparently when fftSize or coherent flag changes between ticks.
   *
   * @param {object} r per-window result: { fftSize, freqResolution,
   *        fundamentalHzRefined, re, im, frameCount } - re/im are length
   *        fftSize/2+1 (half spectrum); for incoherent, re holds raw magnitude.
   * @param {number} samplesAbsStart absolute sample index of this tick's window start.
   * @param {boolean} coherent true = complex de-rotated sum; false = power sum.
   * @param {number} targetN bound on the effective accumulated depth: a finite N
   *        turns the running sum into an exponential window (α = weight/N);
   *        Infinity (∞ / forever mode) is an unbounded cumulative mean.
   * @returns {boolean} true once this tick was folded in.
   */
  accumulate(r, samplesAbsStart, coherent, targetN) {
    const fftSize = r.fftSize;
    const halfSize = fftSize / 2;
    const N = halfSize + 1;
    const kFrac = r.freqResolution > 0 ? r.fundamentalHzRefined / r.freqResolution : 0.0;
    const intFundBin = Math.max(1, Math.round(kFrac));

    const restart = !this._accumHasData
      || this._accumFftSize !== fftSize
      || this._accumCoherent !== coherent;
    if (restart) {
      this._accumRe = coherent ? new Float64Array(N) : null;
      this._accumIm = coherent ? new Float64Array(N) : null;
      this._accumPow = coherent ? null : new Float64Array(N);
      // The subtractable ring is sized like the accumulator, for BOTH paths.
      this._prevTickRe = coherent
        ? Array.from({ length: DISCONTINUITY_DROP_TICKS }, () => new Float64Array(N)) : null;
      this._prevTickIm = coherent
        ? Array.from({ length: DISCONTINUITY_DROP_TICKS }, () => new Float64Array(N)) : null;
      this._prevTickPow = coherent
        ? null : Array.from({ length: DISCONTINUITY_DROP_TICKS }, () => new Float64Array(N));
      this._prevTickWeight = new Int32Array(DISCONTINUITY_DROP_TICKS);
      this._prevTickCount = 0;
      this._prevTickPos = 0;
      this._accumFrames = 0;
      this._accumFftSize = fftSize;
      this._accumCoherent = coherent;
      this._accumRefSampleStart = samplesAbsStart;
      this._accumKFractional = kFrac;
      this._accumIntFundBinRounded = intFundBin;
      // The dual pair's second fundamental, pinned like the first. NaN keeps the
      // family a pure harmonic ladder; a positive value adds the published
      // intermodulation grid a·κ1 + b·κ2 (F2 itself is its (0,1) entry).
      this._accumKappa2 = (coherent && r.fundamental2HzRefined > 0.0 && r.freqResolution > 0.0)
        ? r.fundamental2HzRefined / r.freqResolution
        : NaN;
      this._kappaRefined = false;
      this._kappaMeasFrames = 0;
      this._accumDroppedSamples = 0;
      this._gapRecoverPending = false;
      this._accumHasData = true;
    }

    // Time-shift offset from the pinned reference frame-0. An overrun /
    // discontinuity re-anchor jumps samplesAbsStart, but the COVERAGE gap is counted
    // in the sample positions, so the absolute delta bridges it. What it does NOT
    // count is a dropped-sample xrun - that's recovered into accumDroppedSamples by
    // the PLL below and added here so the pinned-κ rotation stays exact across it.
    let delta = (samplesAbsStart - this._accumRefSampleStart) + this._accumDroppedSamples;

    const weight = Math.max(1, r.frameCount | 0);

    // Bounded (ring) window: scale the running sum down before adding so the
    // effective depth holds at targetN frames (exponential window, α = weight/N).
    // ∞ mode passes targetN = Infinity and never scales (true cumulative mean).
    if (targetN < Infinity && this._accumFrames + weight > targetN) {
      const keep = Math.max(0, targetN - weight);
      const scale = this._accumFrames > 0 ? keep / this._accumFrames : 0.0;
      if (coherent) {
        const aRe = this._accumRe, aIm = this._accumIm;
        for (let k = 0; k < N; k++) { aRe[k] *= scale; aIm[k] *= scale; }
      } else {
        const aPow = this._accumPow;
        for (let k = 0; k < N; k++) aPow[k] *= scale;
      }
      // The subtractable ring holds contributions AS ADDED - once the sum is
      // scaled, they must scale with it or a later subtraction over-removes.
      for (let s = 0; s < this._prevTickCount; s++) {
        const slot = floorMod(this._prevTickPos - 1 - s, DISCONTINUITY_DROP_TICKS);
        if (coherent && this._prevTickRe != null) {
          const pr = this._prevTickRe[slot], pim = this._prevTickIm[slot];
          for (let k = 0; k < N; k++) { pr[k] *= scale; pim[k] *= scale; }
        } else if (this._prevTickPow != null) {
          const pp = this._prevTickPow[slot];
          for (let k = 0; k < N; k++) pp[k] *= scale;
        }
      }
      this._accumFrames = keep;
    }

    // Claim this tick's slot in the subtractable ring; the add loops below fill it
    // with the contribution exactly as accumulated.
    const prevSlot = this._prevTickPos;
    this._prevTickPos = (this._prevTickPos + 1) % DISCONTINUITY_DROP_TICKS;
    if (this._prevTickCount < DISCONTINUITY_DROP_TICKS) this._prevTickCount++;
    this._prevTickWeight[prevSlot] = weight;

    if (coherent) {
      // One-shot κ refine from the de-rotated fundamental's phase slope, then a
      // continuous phase-lock loop. The single-frame parabolic peak pins κ to only
      // ~0.01-0.03 bin; dφ/dΔ over the first KAPPA_MEAS_FRAMES frames IS
      // 2π·Δκ/fftSize, pinning the EXACT frequency ~100× tighter and killing the
      // long-run phase ramp (which would vector-cancel the magnitude, harmonics h×
      // faster). Refined before accumulating so the corrected κ is used from here.
      if (this._accumIntFundBinRounded > 0) {
        const k0 = Math.min(this._accumIntFundBinRounded, halfSize);
        const krot = -2.0 * Math.PI
          * ieeeRemainder(delta * this._accumKFractional, fftSize) / fftSize;
        const cr = Math.cos(krot), sr = Math.sin(krot);
        const obsPhase = Math.atan2(r.re[k0] * sr + r.im[k0] * cr,
                                    r.re[k0] * cr - r.im[k0] * sr);
        if (!this._kappaRefined) {
          // One-shot κ refine from the de-rotated fundamental's phase slope over
          // KAPPA_MEAS_FRAMES CLEAN frames (2π·Δκ/fftSize per unit delta).
          if (this._kappaMeasFrames === 0 || this._kappaSkipNext) {
            // Anchor, or re-anchor after a re-sync (delta jumped): adopt this frame
            // as the reference but DON'T fold its step in - a burst of re-syncs
            // can't poison or starve the measurement.
            if (this._kappaMeasFrames === 0) {
              this._kappaCumPhase = 0.0;
              this._kappaCleanSpan = 0.0;
              this._kappaMeasFrames = 1;
            }
            this._kappaLastPhase = obsPhase;
            this._kappaLastDelta = delta;
            this._kappaSkipNext = false;
          } else {
            this._kappaCumPhase += ieeeRemainder(obsPhase - this._kappaLastPhase, 2.0 * Math.PI);
            this._kappaCleanSpan += delta - this._kappaLastDelta;
            this._kappaLastPhase = obsPhase;
            this._kappaLastDelta = delta;
            if (++this._kappaMeasFrames >= KAPPA_MEAS_FRAMES && this._kappaCleanSpan > 0.0) {
              const dKappa = this._kappaCumPhase * fftSize / (2.0 * Math.PI * this._kappaCleanSpan);
              if (Math.abs(dKappa) <= KAPPA_REFINE_MAX_BIN) {
                this._accumKFractional += dKappa;
                this._kappaRefined = true;
                this._gapRecoverPending = false;
              } else {
                // Not a tone's residual - discard the measurement and start over; a later
                // clean stretch (the generator coming on) can still refine.
                this._kappaMeasFrames = 0;
              }
            }
          }
        } else if (this._accumKFractional !== 0.0) {
          // Continuous phase-tracking loop (a PLL on the cross-tick fundamental):
          // measure the running mis-alignment against the DEEP ACCUMULATED phase
          // (the √N-averaged true reference; the accumulator rotates bin k0 by the
          // same krot as obsPhase, so the two compare directly) and fold a FRACTION
          // (PHASE_TRACK_GAIN) of it back into the de-rotation every tick so it
          // re-locks - killing the residual-κ drift and absorbing disturbances.
          // The steady estimate is the FORK - pooled from every strong family line,
          // not the fundamental alone (see _forkPooledOffset) - so behind a notch
          // the reference noise falls with the combined power of the harmonics
          // instead of being set by the weakest line. On a re-sync the delta
          // jumped, so do a one-shot FULL realign of THIS frame from the
          // fundamental ALONE (a big offset wraps the higher lines' phases, so
          // pooling is not valid there) and let the loop clean up afterwards.
          const accumPhase = Math.atan2(this._accumIm[k0], this._accumRe[k0]);
          const residual = ieeeRemainder(obsPhase - accumPhase, 2.0 * Math.PI);
          const fundOffset = residual * fftSize / (2.0 * Math.PI * this._accumKFractional);
          if (this._gapRecoverPending) {
            this._accumDroppedSamples += fundOffset;
            delta += fundOffset;           // realign THIS frame after the jump
            this._gapRecoverPending = false;
          } else {
            this._accumDroppedSamples += PHASE_TRACK_GAIN
              * this._forkPooledOffset(r, halfSize, delta, k0, fundOffset);
          }
        }
      }
      const aRe = this._accumRe, aIm = this._accumIm;
      const rRe = r.re, rIm = r.im;
      // This tick's ring slot: every add path below writes its contribution here
      // EXACTLY as it entered the accumulator, so a gate firing can subtract it.
      const pvR = this._prevTickRe[prevSlot], pvI = this._prevTickIm[prevSlot];
      // Serial port of the desktop's parallelChunks de-rotation: the per-bin writes
      // are disjoint, so one pass gives bit-for-bit the same result.
      if (r.windowType === 'RECT') {
        // RECT keeps the per-bin time-shift ramp: on-bin content occupies single
        // bins (no lobe to hold together) while its 1/Δf skirt spans the whole
        // spectrum and would cross every region midpoint, which is exactly where a
        // piecewise rotation prints. Seeded once, then advanced by the recurrence.
        const scale = (this._accumKFractional > 0.0 && this._accumIntFundBinRounded > 0)
          ? this._accumKFractional / this._accumIntFundBinRounded
          : 1.0;
        const theta = -2.0 * Math.PI * ieeeRemainder(delta * scale, fftSize) / fftSize;
        const stepRe = Math.cos(theta), stepIm = Math.sin(theta);
        // Java seeds the phasor per parallel chunk at IEEEremainder(theta*kLo, 2*pi);
        // the serial walk is one chunk starting at bin 0, where that is exactly 1+0j.
        let phRe = 1.0, phIm = 0.0;
        for (let k = 0; k < N; k++) {
          const reW = rRe[k] * weight;
          const imW = rIm[k] * weight;
          const cRe = reW * phRe - imW * phIm;
          const cIm = reW * phIm + imW * phRe;
          aRe[k] += cRe;
          aIm[k] += cIm;
          pvR[k] = cRe;
          pvI[k] = cIm;
          const nextRe = phRe * stepRe - phIm * stepIm;
          phIm = phRe * stepIm + phIm * stepRe;
          phRe = nextRe;
        }
      } else {
        const phi1 = (this._accumKFractional > 0.0)
          ? -2.0 * Math.PI * ieeeRemainder(delta * this._accumKFractional, fftSize) / fftSize
          : 0.0;
        const phi2 = (this._accumKappa2 > 0.0)
          ? -2.0 * Math.PI * ieeeRemainder(delta * this._accumKappa2, fftSize) / fftSize
          : 0.0;
        const nFam = this._buildFamily(r, halfSize, phi1, phi2);
        if (nFam <= 0) {
          // No pinned fundamental: nothing coherent needs aligning - add straight;
          // noise and unlocked lines decay on their own.
          for (let k = 0; k < N; k++) {
            const cRe = rRe[k] * weight;
            const cIm = rIm[k] * weight;
            aRe[k] += cRe;
            aIm[k] += cIm;
            pvR[k] = cRe;
            pvI[k] = cIm;
          }
        } else {
          // Region-constant walk: each bin takes the single phasor of the family
          // member whose region it falls in, the regions tiled at the MIDPOINTS
          // between neighbouring members. The accumulator's spectrum is the HALF
          // spectrum [0, halfSize], so every bin is a positive frequency and one
          // ascending cursor covers it - no negative-frequency conjugate branch.
          const pos = this._famPos, pc = this._famCos, ps = this._famSin;
          let c = 0;
          for (let k = 0; k < N; k++) {
            while (c < nFam - 1 && k > 0.5 * (pos[c] + pos[c + 1])) c++;
            const reW = rRe[k] * weight;
            const imW = rIm[k] * weight;
            const cRe = reW * pc[c] - imW * ps[c];
            const cIm = reW * ps[c] + imW * pc[c];
            aRe[k] += cRe;
            aIm[k] += cIm;
            pvR[k] = cRe;
            pvI[k] = cIm;
          }
        }
      }
    } else {
      // Incoherent analyze stores the RAW FFT magnitude in r.re (im = 0). Sum its
      // POWER so the mag->amplitude conversion in overlayOnto applies ONCE.
      const aPow = this._accumPow;
      const pvP = this._prevTickPow[prevSlot];
      const rRe = r.re;
      for (let k = 0; k < N; k++) {
        const mag = rRe[k];
        const cP = mag * mag * weight;
        aPow[k] += cP;
        pvP[k] = cP;
      }
    }
    this._accumFrames += weight;
    return true;
  }

  /**
   * Removes the subtractable ring's held contributions - the last accepted tick(s) -
   * from the accumulator (Java subtractPreviousTicks). The retroactive half of
   * discontinuity rejection: the window(s) BEFORE the detecting one shared nearly
   * all of its samples, with the event attenuated at their taper edge below the gate
   * thresholds, so their contribution is poisoned although no gate fired on them.
   * Called by the controller at BOTH gates, before the re-sync; never on a ring
   * overrun, which is a coverage gap rather than damaged data.
   */
  subtractPreviousTicks() {
    if (this._prevTickCount <= 0 || !this._accumHasData) return;
    let removedFrames = 0;
    for (let s = 0; s < this._prevTickCount; s++) {
      const slot = floorMod(this._prevTickPos - 1 - s, DISCONTINUITY_DROP_TICKS);
      if (this._accumCoherent && this._accumRe != null && this._prevTickRe != null) {
        const pr = this._prevTickRe[slot], pim = this._prevTickIm[slot];
        const aRe = this._accumRe, aIm = this._accumIm;
        for (let k = 0; k < aRe.length; k++) { aRe[k] -= pr[k]; aIm[k] -= pim[k]; }
      } else if (!this._accumCoherent && this._accumPow != null && this._prevTickPow != null) {
        const pp = this._prevTickPow[slot];
        const aPow = this._accumPow;
        for (let k = 0; k < aPow.length; k++) aPow[k] -= pp[k];
      }
      removedFrames += this._prevTickWeight[slot];
    }
    this._accumFrames = Math.max(0, this._accumFrames - removedFrames);
    this._prevTickCount = 0;
  }

  /**
   * The FORK: the per-tick tracking correction (in samples) pooled from every strong
   * line of the coherent family instead of the fundamental alone (Java
   * forkPooledOffset). A common sample offset rotates each line proportionally to
   * its frequency, so each line's accumulated-vs-fresh phase difference divided by
   * its fractional bin is an independent estimate of the SAME offset; the estimates
   * are averaged with weights (fractional bin x magnitude)^2 - variance-optimal,
   * since a line's phase noise is floor/|X| and its offset sensitivity grows with
   * frequency. Admission: a line joins when its fresh level is within the
   * multi-tone threshold (setStrongToneRelDb) below the fundamental's - a smaller
   * threshold pools fewer lines - and the dual pair's F2 (the (0,1) grid product)
   * always joins. Behind a notch the fundamental is the WEAKEST line, and the pooled
   * reference noise falls with the combined power of the harmonics. With nothing
   * else admitted, returns the fundamental's own estimate unchanged.
   *
   * @param {object} r the per-window result.
   * @param {number} halfSize fftSize/2.
   * @param {number} delta samples from the pinned reference to this tick.
   * @param {number} k0 the fundamental's bin.
   * @param {number} fundOffset the fundamental's own offset estimate, in samples.
   * @returns {number} the pooled offset estimate, in samples.
   */
  _forkPooledOffset(r, halfSize, delta, k0, fundOffset) {
    const kappa = this._accumKFractional;
    // The ladder below runs h while h·κ < halfSize: bounded by the family, not by the
    // spectrum, so a sub-bin κ has no ladder to pool - the fundamental's own estimate stands.
    if (!(kappa >= KAPPA_FAMILY_MIN_BIN)) return fundOffset;
    const fundPow = r.re[k0] * r.re[k0] + r.im[k0] * r.im[k0];
    const minPow = fundPow * Math.pow(10.0, -this._strongToneRelDb / 10.0);   // a POWER ratio
    let sumW = kappa * kappa * fundPow;                     // the fundamental seeds the pool
    let sumWOff = sumW * fundOffset;
    // The harmonic ladder h·κ, h = 2 and up.
    for (let h = 2; h * kappa < halfSize - 1.0; h++) {
      const est = this._forkLineOffset(r, halfSize, delta, h * kappa, minPow, false);
      if (est != null) {
        sumW += est[0];
        sumWOff += est[0] * est[1];
      }
    }
    // The published product grid a·κ1 + b·κ2 when a dual pair is pinned; F2 - the
    // (0,1) entry - is a fundamental and joins unconditionally.
    const pa = r.imdProductA;
    const pb = r.imdProductB;
    if (this._accumKappa2 > 0.0 && pa != null && pb != null) {
      for (let p = 0; p < pa.length; p++) {
        const kappaP = pa[p] * kappa + pb[p] * this._accumKappa2;
        if (kappaP < 1.0 || kappaP > halfSize - 1.0) continue;
        const exempt = pa[p] === 0 && pb[p] === 1;
        const est = this._forkLineOffset(r, halfSize, delta, kappaP, minPow, exempt);
        if (est != null) {
          sumW += est[0];
          sumWOff += est[0] * est[1];
        }
      }
    }
    return sumWOff / sumW;
  }

  /**
   * One fork line's [weight, offset-estimate] - or null when the line is below the
   * admission level (unless `exempt`), out of range, or too close to the phase wrap
   * this tick (Java forkLineOffset). The fresh phasor is de-rotated by the line's own
   * family angle, exactly as the accumulator rotated its bin, so the two phases
   * compare directly.
   *
   * @param {number} kappaL the line's fractional bin.
   * @param {number} minPow the admission POWER floor.
   * @param {boolean} exempt true for a line that joins regardless of level.
   * @returns {?Array<number>} [weight, offset in samples], or null.
   */
  _forkLineOffset(r, halfSize, delta, kappaL, minPow, exempt) {
    const k = Math.round(kappaL);
    if (k <= 1 || k > halfSize) return null;
    const pow = r.re[k] * r.re[k] + r.im[k] * r.im[k];
    if (!exempt && pow < minPow) return null;
    const size = this._accumFftSize;
    const ang = -2.0 * Math.PI * ieeeRemainder(delta * kappaL, size) / size;
    const c = Math.cos(ang), s = Math.sin(ang);
    const obs = Math.atan2(r.re[k] * s + r.im[k] * c, r.re[k] * c - r.im[k] * s);
    const accumPhase = Math.atan2(this._accumIm[k], this._accumRe[k]);
    const res = ieeeRemainder(obs - accumPhase, 2.0 * Math.PI);
    if (Math.abs(res) > FORK_WRAP_GUARD_RAD) return null;
    return [kappaL * kappaL * pow, res * size / (2.0 * Math.PI * kappaL)];
  }

  /**
   * Fills the family scratch (_famPos / _famCos / _famSin) with the COHERENT FAMILY
   * sorted by position (Java FftAnalyzerWorker.buildFamily): the harmonic ladder
   * h·κ - h = 0 IS a member, carrying the identity phasor and covering DC and the
   * deep bass side - merged with the published intermodulation grid a·κ1 + b·κ2
   * when a dual pair is pinned. Phasors: the ladder by incremental recurrence from
   * Φ1, the grid members directly from a·Φ1 + b·Φ2.
   *
   * @param {object} r the per-window result (its imdProductA / imdProductB carry
   *        the published product grid).
   * @param {number} halfSize fftSize/2 - the top bin of the half spectrum.
   * @param {number} phi1 the pinned fundamental's phase advance for this tick.
   * @param {number} phi2 the second fundamental's; 0 when the run is single-tone.
   * @returns {number} the member count; 0 when no fundamental is pinned (the caller
   *          then adds without rotation).
   */
  _buildFamily(r, halfSize, phi1, phi2) {
    const kappa = this._accumKFractional;
    // No family below one bin: the ladder would be the whole half spectrum, member for bin.
    if (!(kappa >= KAPPA_FAMILY_MIN_BIN)) return 0;
    // The divisor is FRACTIONAL: the ladder must stop at the last harmonic that
    // still lands inside the half spectrum, not at a rounded multiple of it.
    const hMax = Math.trunc(Math.min(halfSize, halfSize / kappa));
    const pa = r.imdProductA;
    const pb = r.imdProductB;
    const products = this._accumKappa2 > 0.0 && pa != null && pb != null && pa.length > 0;
    const cap = hMax + 1 + (products ? pa.length : 0);
    if (this._famPos.length < cap) {
      this._famPos = new Float64Array(cap);
      this._famCos = new Float64Array(cap);
      this._famSin = new Float64Array(cap);
    }
    const famPos = this._famPos, famCos = this._famCos, famSin = this._famSin;
    // The grid members, position-sorted (a handful of entries).
    let nP = 0;
    let pPos = null;
    let pAng = null;
    if (products) {
      pPos = new Float64Array(pa.length);
      pAng = new Float64Array(pa.length);
      for (let p = 0; p < pa.length; p++) {
        const posP = pa[p] * kappa + pb[p] * this._accumKappa2;
        if (posP < 1.0 || posP > halfSize - 1) continue;
        pPos[nP] = posP;
        pAng[nP] = ieeeRemainder(pa[p] * phi1 + pb[p] * phi2, 2.0 * Math.PI);
        nP++;
      }
      for (let i = 1; i < nP; i++) {
        const vp = pPos[i];
        const va = pAng[i];
        let j = i - 1;
        while (j >= 0 && pPos[j] > vp) {
          pPos[j + 1] = pPos[j];
          pAng[j + 1] = pAng[j];
          j--;
        }
        pPos[j + 1] = vp;
        pAng[j + 1] = va;
      }
    }
    // Merge the (already ascending) ladder with the sorted grid members; the ladder
    // phasor h·Φ1 advances by an incremental complex multiply.
    const c1 = Math.cos(phi1), s1 = Math.sin(phi1);
    let ch = 1.0, sh = 0.0;
    let n = 0;
    let pi = 0;
    for (let h = 0; h <= hMax; h++) {
      const hp = h * kappa;
      while (pi < nP && pPos[pi] <= hp) {
        famPos[n] = pPos[pi];
        famCos[n] = Math.cos(pAng[pi]);
        famSin[n] = Math.sin(pAng[pi]);
        n++;
        pi++;
      }
      famPos[n] = hp;
      famCos[n] = ch;
      famSin[n] = sh;
      n++;
      const next = ch * c1 - sh * s1;
      sh = ch * s1 + sh * c1;
      ch = next;
    }
    while (pi < nP) {
      famPos[n] = pPos[pi];
      famCos[n] = Math.cos(pAng[pi]);
      famSin[n] = Math.sin(pAng[pi]);
      n++;
      pi++;
    }
    return n;
  }

  /**
   * Rewrites r's re/im/amplitudeDbFs/phaseDeg in place to reflect the cumulative
   * accumulator average (Java overlayAccumulatorOnto). After this the caller runs
   * FftAnalyzer.recomputeStats(r) so the fundamental / harmonic table / THD / SNR
   * are re-derived from the cumulative spectrum (the per-tick stats were just one
   * tick's contribution). No-op until at least one tick has been folded in.
   *
   * @param {object} r the per-window result to overlay (its re/im/amplitudeDbFs/
   *        phaseDeg arrays are length fftSize/2+1).
   */
  overlayOnto(r) {
    if (!this._accumHasData || this._accumFrames <= 0) return;
    const halfSize = r.fftSize / 2;
    const N = halfSize + 1;

    // Derive the (mag -> amplitude) conversion factor from the pre-overlay r:
    // amplLin = hypot(re,im)·normFactor·2 (non-DC/Nyquist). Picking the
    // fundamental bin guarantees a high-SNR sample; the factor depends only on
    // the window's coherent gain (constant for a given fftSize/window).
    let kFund = Math.max(1, Math.round(r.fundamentalHzRefined / r.freqResolution));
    if (kFund > halfSize) kFund = halfSize;
    const magFund = Math.hypot(r.re[kFund], r.im[kFund]);
    const ampFund = r.amplitudeDbFs[kFund] > -290.0
      ? Math.pow(10.0, r.amplitudeDbFs[kFund] / 20.0)
      : 0.0;
    if (magFund < 1e-30 || ampFund < 1e-30) return;
    const conv = ampFund / magFund;   // normFactor · 2 (for non-DC/Nyquist)

    const frames = this._accumFrames;
    if (this._accumCoherent) {
      const aRe = this._accumRe, aIm = this._accumIm;
      for (let k = 0; k < N; k++) {
        const avgRe = aRe[k] / frames;
        const avgIm = aIm[k] / frames;
        r.re[k] = avgRe;
        r.im[k] = avgIm;
        const mag = Math.sqrt(avgRe * avgRe + avgIm * avgIm);
        const scale = (k === 0 || k === halfSize) ? 0.5 : 1.0;
        const amp = mag * conv * scale;
        r.amplitudeDbFs[k] = amp > 1e-15 ? 20.0 * Math.log10(amp) : -300.0;
        r.phaseDeg[k] = (Math.atan2(avgIm, avgRe)) * (180.0 / Math.PI);
      }
    } else {
      const aPow = this._accumPow;
      for (let k = 0; k < N; k++) {
        const avgPow = aPow[k] / frames;
        const mag = Math.sqrt(avgPow);
        const scale = (k === 0 || k === halfSize) ? 0.5 : 1.0;
        const amp = mag * conv * scale;
        r.amplitudeDbFs[k] = amp > 1e-15 ? 20.0 * Math.log10(amp) : -300.0;
        // Match analyze()'s incoherent convention: re holds magnitude, im = 0.
        r.re[k] = amp;
        r.im[k] = 0.0;
        r.phaseDeg[k] = 0.0;
      }
    }
  }
}
