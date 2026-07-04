/*
 * Phonalyser web — cross-tick FFT averaging accumulator.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of the cross-tick running accumulator in
// org.edgo.audio.measure.gui.fft.FftAnalyzerWorker — the part the desktop calls
// accumulateIntoForeverBuffer(...) + overlayAccumulatorOnto(...) + resetAccumulator().
//
// WHY THIS EXISTS — the web port previously had NO cross-tick accumulator: each
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
// PORTED (single-reference, the core that makes a stable tone average correctly):
//   - COHERENT per-lobe constant-phase de-rotation: each bin k snapped to its
//     nearest harmonic h = round(k/k0) and rotated by that lobe's CONSTANT phase
//     h·Φ, Φ = −2π·delta·accumKFractional/fftSize (NOT a per-bin ramp), where
//     delta = samplesAbsStart − accumRefSampleStart. accumRefSampleStart +
//     accumKFractional + accumIntFundBinRounded are PINNED on the first tick.
//   - INCOHERENT power sum: Σ |X[k]|² weighted by frameCount (the analyzer stores
//     RAW magnitude in r.re for incoherent, so we sum mag·mag — no double convert).
//   - targetN scaling: finite avgTarget → exponential ring window; ∞ → cumulative.
//   - overlay: rebuild amplitudeDbFs/phaseDeg/re/im off the running average.
//
// PORTED (the long-run refinements — full parity with the desktop now):
//   - One-shot κ PHASE-SLOPE REFINE (single-reference): the single-frame parabolic
//     peak pins κ to only ~0.01–0.03 bin; over a long coherent run that residual
//     ramps the de-rotated fundamental phase (2π·Δκ·delta/fftSize) and vector-
//     cancels the magnitude (harmonics h× faster). dφ/dΔ over KAPPA_MEAS_FRAMES
//     CLEAN frames yields Δκ ~100× tighter, folded into accumKFractional ONCE.
//   - Continuous PHASE-LOCK LOOP (PLL): once κ is refined, the de-rotated phase is
//     measured against the DEEP accumulated phase and a fraction (PHASE_TRACK_GAIN)
//     of the residual is folded into accumDroppedSamples each tick, killing the
//     residual κ ramp and absorbing disturbances; a re-sync does a one-shot full
//     realign (gain 1).
//   - MULTI-TONE path: detectStrongTones() finds the strong tones from the spectrum
//     itself; accumulateMultiTone() de-rotates each tone's lobe by its OWN frequency
//     (constant phase), runs a per-tone PLL + per-tone κ refine, builds the dual-tone
//     IMD-product grid (a·κ1+b·κ2) and pools the strong tones' clock drift onto the
//     non-tone bins (FORK_NONTONE_DRIFT) — true dual-tone IMD, no grid-lock.
//   - Re-anchor on a re-sync (overrun / discontinuity): onResync() sets the κ /
//     multi-κ skip flags + gapRecoverPending so the jumped frame re-anchors instead
//     of poisoning the slope measurement.
//   - The SpectralDiscontinuityDetector gate (USE_SPECTRAL_DISCONTINUITY): the
//     accumulator OWNS the detector (as the desktop worker does) and resets it in
//     reset(); the controller calls reject() per tick before the fold.
//
// PARALLELISM NOTE — the desktop runs the per-bin de-rotation loops across cores
// (parallelChunks). The math is a disjoint per-bin write, so this port runs the
// SAME loops SERIALLY in one pass: bit-for-bit the same result, just single-threaded
// (the cross-tick fold is already off the main thread on the FFT worker / pool).

import { SpectralDiscontinuityDetector } from '../dsp/spectral-discontinuity-detector.js';

/** Frames of de-rotated-fundamental phase observed before the one-shot κ refine —
 *  ~24 give κ to ~1e-4 bin at high SNR, before the drift becomes visible. */
const KAPPA_MEAS_FRAMES = 24;
/** Phase-tracking-loop gain: fraction of the running fundamental mis-alignment
 *  folded into the de-rotation each tick. Low enough to average out per-tick phase
 *  noise, high enough to track the κ-residual ramp (Java PHASE_TRACK_GAIN). */
const PHASE_TRACK_GAIN = 0.10;
/** A per-tone cross-tick phase residual larger than this is a phase DISCONTINUITY
 *  (DDS jump / reconnect) — realigned in one shot (gain 1) rather than tracked
 *  (Java PHASE_JUMP_RAD = 60°). */
const PHASE_JUMP_RAD = 60.0 * Math.PI / 180.0;
/** Half-width (bins) of the lobe a tone's constant phase covers. */
const TONE_LOBE_HALF_BINS = 16;
/** Two peaks closer than this (bins) are merged into one tone. */
const MIN_TONE_SEP_BINS = 48;
/** Cap on independent tone references. */
const MAX_TONES = 8;
/** A peak at an integer multiple (h≥2) of the strongest tone at least this far
 *  BELOW it is a harmonic (distortion), not an independent tone — dropped so a
 *  single tone + harmonics stays on the single-reference refined path. */
const HARMONIC_REJECT_BELOW_DB = 40.0;
/** Ignore peaks below this frequency when detecting tones (residual DC leakage). */
const DC_REJECT_HZ = 10.0;
/** Default dB below the strongest peak a local maximum must clear to count as a
 *  separate TONE.  The desktop reads this LIVE from Preferences#getFftStrongToneRelDb
 *  (default 100.0); the web mirrors that via setStrongToneRelDb(), seeded here. */
const STRONG_TONE_REL_DB_DEFAULT = 100.0;
/** Spectral-discontinuity gate toggle (Java USE_SPECTRAL_DISCONTINUITY). */
const USE_SPECTRAL_DISCONTINUITY = true;
/** "Rotate the fork": pool the strong tones' shared clock-drift estimate onto the
 *  non-tone bins so weak harmonics / IMD products don't sink under drift (Java
 *  FORK_NONTONE_DRIFT — production state on). */
const FORK_NONTONE_DRIFT = true;
/** Per-tone κ refine (multi-tone path) — Java MULTI_KAPPA_REFINE (production on). */
const MULTI_KAPPA_REFINE = true;

/** Java Math.IEEEremainder(x, y): the remainder nearest to zero (round-half-even).
 *  Used by the κ phase-slope unwrap exactly as the desktop does. */
function ieeeRemainder(x, y) {
  const q = x / y;
  if (Math.abs(q - Math.trunc(q)) === 0.5) {
    const n = 2 * Math.round(q / 2);   // tie → nearest even
    return x - y * n;
  }
  return x - y * Math.round(q);
}

export class FftAccumulator {
  constructor() {
    /** @type {?Float64Array} coherent running complex sum, Re (length halfSize+1). */
    this._accumRe = null;
    /** @type {?Float64Array} coherent running complex sum, Im. */
    this._accumIm = null;
    /** @type {?Float64Array} incoherent running power sum (|X|²·weight). */
    this._accumPow = null;
    /** Total frame count accumulated since the last reset (the "N average(s)" depth). */
    this._accumFrames = 0;
    /** True once at least one tick has contributed. */
    this._accumHasData = false;
    /** Absolute sample-stream position of the first contributing tick's frame-0. */
    this._accumRefSampleStart = 0;
    /** Pinned de-rotation frequency (fractional fundamental bin) — held constant. */
    this._accumKFractional = 0;
    /** Pinned round(kFractional) — the stable integer lobe pitch. */
    this._accumIntFundBinRounded = 0;
    /** Config the accumulator was built for; a mismatch restarts it. */
    this._accumFftSize = 0;
    this._accumCoherent = false;
    /** Per-harmonic de-rotation phasor scratch (cos/sin of h·Φ), rebuilt per tick. */
    this._hcos = null;
    this._hsin = null;

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

    // ─── multi-tone path ──────────────────────────────────────────────────────
    /** Fractional bins of the strong tones detected at restart (sorted ascending).
     *  length ≤ 1 ⇒ single-reference rotation; length ≥ 2 ⇒ per-tone de-rotation. */
    this._accumToneKappa = new Float64Array(0);
    /** Per-tone cumulative de-rotation correction (the multi-tone analogue of
     *  accumDroppedSamples) — one entry per accumToneKappa tone. */
    this._toneDroppedSamples = new Float64Array(0);
    /** Per-bin IMD-product assignment (cross-tick dual-tone de-rotation), reused. */
    this._imdGridIdx = null;
    // Per-tone κ refine (mirrors the single-reference one-shot refine).
    this._multiKappaRefined = false;
    this._multiKappaMeasFrames = 0;
    this._multiKappaLastDelta = 0;
    this._multiKappaCleanSpan = 0;
    this._multiKappaSkipNext = false;
    /** @type {?Float64Array} Σ clean per-frame phase steps, per tone. */
    this._multiKappaCumPhase = null;
    /** @type {?Float64Array} de-rotated phase at the reference, per tone. */
    this._multiKappaLastPhase = null;

    // ─── spectral-discontinuity gate (the accumulator OWNS the detector, as the
    //     desktop worker does; reset() restarts it with the average) ────────────
    this._spectralDetector = new SpectralDiscontinuityDetector();

    /** dB below the strongest peak a local maximum must clear to count as a
     *  separate TONE (Java Preferences#getFftStrongToneRelDb, read live). The
     *  controller pushes the live pref value via setStrongToneRelDb(). */
    this._strongToneRelDb = STRONG_TONE_REL_DB_DEFAULT;
  }

  /** Sets the multi-tone detection threshold in dB below the strongest peak
   *  (Java Preferences#getFftStrongToneRelDb, read live by detectStrongTones).
   *  The controller calls this at setup and on the pref-change listener so a
   *  change takes effect on the next accumulator restart, as the desktop does. */
  setStrongToneRelDb(db) {
    this._strongToneRelDb = db;
  }

  /** Cumulative frame depth since the last reset — drives the "N/avgTarget"
   *  readout and the stop-after-N threshold (Java getAccumulatedFrames). */
  get accumFrames() { return this._accumFrames; }
  /** True once at least one tick has been folded in. */
  get hasData() { return this._accumHasData; }
  /** Pinned coherent κ (fractional fundamental bin) for the plot-time "before"
   *  dots; 0 before the first contribution. */
  get accumKFractional() { return this._accumKFractional; }
  /** Number of independent strong tones the cross-tick path is de-rotating: ≥2
   *  means the multi-tone (per-tone) path is engaged (Java accumToneKappa.length). */
  get toneCount() { return this._accumToneKappa.length; }

  /** Drops the running accumulator (Java resetAccumulator). Arrays are dropped,
   *  not cleared in place, so a stale in-flight tick can't resurrect them. */
  reset() {
    this._accumRe = null;
    this._accumIm = null;
    this._accumPow = null;
    this._accumFrames = 0;
    this._accumHasData = false;
    this._accumToneKappa = new Float64Array(0);
    this._toneDroppedSamples = new Float64Array(0);
    // The running-median discontinuity rejector restarts with the average (its
    // median reference must be re-learned from the fresh run).
    this._spectralDetector.reset();
  }

  /** Re-anchor after a re-sync (ring overrun / signal discontinuity): the absolute
   *  delta jumps, so the next clean frame must re-anchor the κ slope (not fold its
   *  jumped step) and the PLL does a one-shot full realign. Mirrors the desktop
   *  onCaptureOverrun / onSignalDiscontinuity (kappaSkipNext / multiKappaSkipNext /
   *  gapRecoverPending). Called by the controller when it re-anchors the cursor. */
  onResync() {
    this._kappaSkipNext = true;        // κ measurement: re-anchor on the jumped frame
    this._multiKappaSkipNext = true;   // multi-tone per-tone κ refine: same re-anchor
    this._gapRecoverPending = true;    // one-shot full realign on the next clean frame
  }

  /** Runs the spectral-discontinuity gate on this tick's complex half-spectrum
   *  (Java FftAnalyzerWorker: spectralDetector.reject before the cross-tick fold).
   *  De-rotation does not change magnitude, so the per-window (pre-fold) re/im are
   *  the right thing to gate. @returns {boolean} true ⇒ REJECT this block. */
  reject(re, im, halfSize, binWidthHz, peakBins) {
    if (!USE_SPECTRAL_DISCONTINUITY) return false;
    this._spectralDetector.configure(halfSize);
    return this._spectralDetector.reject(re, im, halfSize, binWidthHz, peakBins);
  }

  /**
   * Folds one tick's RAW per-window spectrum into the running accumulator
   * (Java FftAnalyzerWorker.accumulateIntoForeverBuffer). For coherent mode it
   * applies the single-reference per-lobe constant-phase de-rotation that aligns
   * this tick's frame-0 with the pinned reference; for incoherent it sums power.
   * Resets transparently when fftSize or coherent flag changes between ticks.
   *
   * @param {object} r per-window result: { fftSize, freqResolution,
   *        fundamentalHzRefined, re, im, frameCount } — re/im are length
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
      this._accumFrames = 0;
      this._accumFftSize = fftSize;
      this._accumCoherent = coherent;
      this._accumRefSampleStart = samplesAbsStart;
      this._accumKFractional = kFrac;
      this._accumIntFundBinRounded = intFundBin;
      // Choose the cross-tick path from the SPECTRAL peak count (detectStrongTones),
      // NOT the THD/IMD display mode: a single-tone setup with strong harmonics or
      // residual mains can still land on the multi-tone path (each tone phase-locked,
      // no shared κ phase-slope refine), while a clean single tone + harmonics stays
      // single-reference. The tones are found from the spectrum, so it works for
      // unknown / external multi-tone signals.
      this._accumToneKappa = coherent ? this._detectStrongTones(r) : new Float64Array(0);
      this._toneDroppedSamples = new Float64Array(this._accumToneKappa.length);
      this._kappaRefined = false;
      this._kappaMeasFrames = 0;
      this._multiKappaRefined = false;
      this._multiKappaMeasFrames = 0;
      this._multiKappaCleanSpan = 0;
      this._multiKappaSkipNext = false;
      this._multiKappaCumPhase = null;
      this._multiKappaLastPhase = null;
      this._accumDroppedSamples = 0;
      this._gapRecoverPending = false;
      this._accumHasData = true;
    }

    // Time-shift offset from the pinned reference frame-0. An overrun /
    // discontinuity re-anchor jumps samplesAbsStart, but the COVERAGE gap is counted
    // in the sample positions, so the absolute delta bridges it. What it does NOT
    // count is a dropped-sample xrun — that's recovered into accumDroppedSamples by
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
      this._accumFrames = keep;
    }

    if (coherent) {
      if (this._accumToneKappa.length >= 2) {
        // Multi-tone: de-rotate each detected tone's lobe by its OWN frequency
        // (constant phase), so every fundamental keeps its true position — no single
        // reference, hence no grid-lock.
        this._accumulateMultiTone(r, N, weight, delta);
      } else {
        // One-shot κ refine from the de-rotated fundamental's phase slope, then a
        // continuous phase-lock loop. The single-frame parabolic peak pins κ to only
        // ~0.01–0.03 bin; dφ/dΔ over the first KAPPA_MEAS_FRAMES frames IS
        // 2π·Δκ/fftSize, pinning the EXACT frequency ~100× tighter and killing the
        // long-run phase ramp (which would vector-cancel the magnitude, harmonics h×
        // faster). Refined before accumulating so the corrected κ is used from here.
        if (this._accumIntFundBinRounded > 0) {
          const k0 = Math.min(this._accumIntFundBinRounded, halfSize);
          const krot = -2.0 * Math.PI * delta * this._accumKFractional / fftSize;
          const cr = Math.cos(krot), sr = Math.sin(krot);
          const obsPhase = Math.atan2(r.re[k0] * sr + r.im[k0] * cr,
                                      r.re[k0] * cr - r.im[k0] * sr);
          if (!this._kappaRefined) {
            // One-shot κ refine from the de-rotated fundamental's phase slope over
            // KAPPA_MEAS_FRAMES CLEAN frames (2π·Δκ/fftSize per unit delta).
            if (this._kappaMeasFrames === 0 || this._kappaSkipNext) {
              // Anchor, or re-anchor after a re-sync (delta jumped): adopt this frame
              // as the reference but DON'T fold its step in — a burst of re-syncs
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
                this._accumKFractional += dKappa;
                this._kappaRefined = true;
                this._gapRecoverPending = false;
              }
            }
          } else if (this._accumKFractional !== 0.0) {
            // Continuous phase-tracking loop (a PLL on the cross-tick fundamental):
            // measure the running mis-alignment against the DEEP ACCUMULATED phase
            // (the √N-averaged true reference; the accumulator rotates bin k0 by the
            // same krot as obsPhase, so the two compare directly) and fold a FRACTION
            // (PHASE_TRACK_GAIN) of it back into the de-rotation every tick so it
            // re-locks — killing the residual-κ drift and absorbing disturbances. On
            // a re-sync, do a one-shot FULL realign of THIS frame too (gain 1).
            const accumPhase = Math.atan2(this._accumIm[k0], this._accumRe[k0]);
            const residual = ieeeRemainder(obsPhase - accumPhase, 2.0 * Math.PI);
            const gain = this._gapRecoverPending ? 1.0 : PHASE_TRACK_GAIN;
            const corr = gain * residual * fftSize / (2.0 * Math.PI * this._accumKFractional);
            this._accumDroppedSamples += corr;
            if (this._gapRecoverPending) {
              delta += corr;                 // realign THIS frame after the jump
              this._gapRecoverPending = false;
            }
          }
        }
        // Single reference: PER-LOBE constant-phase de-rotation over the HALF
        // spectrum [0, Nyquist]. Each bin is snapped to its nearest harmonic
        // h = round(bin / k0) and rotated by that lobe's CONSTANT phase h·Φ,
        // Φ = −2π·delta·accumKFractional/fftSize — NOT a per-bin ramp (which would
        // comb the leakage skirt). All bins are positive frequencies, so the
        // harmonic index is round(k / k0) directly (no signed-bin wrap).
        const k0x = Math.max(1, this._accumIntFundBinRounded);
        const phiX = -2.0 * Math.PI * delta * this._accumKFractional / fftSize;
        const hMaxX = Math.max(1, Math.trunc(halfSize / k0x));
        if (this._hcos == null || this._hcos.length !== 2 * hMaxX + 1) {
          this._hcos = new Float64Array(2 * hMaxX + 1);
          this._hsin = new Float64Array(2 * hMaxX + 1);
        }
        const e1cx = Math.cos(phiX), e1sx = Math.sin(phiX);
        const hcx = this._hcos, hsx = this._hsin;
        hcx[hMaxX] = 1.0; hsx[hMaxX] = 0.0;
        for (let h = 1; h <= hMaxX; h++) {
          hcx[hMaxX + h] = hcx[hMaxX + h - 1] * e1cx - hsx[hMaxX + h - 1] * e1sx;
          hsx[hMaxX + h] = hcx[hMaxX + h - 1] * e1sx + hsx[hMaxX + h - 1] * e1cx;
          hcx[hMaxX - h] = hcx[hMaxX + h];            // exp(−j·h·Φ) = conjugate
          hsx[hMaxX - h] = -hsx[hMaxX + h];
        }
        const aRe = this._accumRe, aIm = this._accumIm;
        const rRe = r.re, rIm = r.im;
        // Serial port of the desktop's parallelChunks de-rotation (disjoint per-bin
        // writes → identical result single-threaded).
        for (let k = 0; k < N; k++) {
          let h = Math.round(k / k0x);                // nearest harmonic lobe (k ≥ 0)
          if (h > hMaxX) h = hMaxX;
          const cr2 = hcx[h + hMaxX], ci2 = hsx[h + hMaxX];
          const reW = rRe[k] * weight;
          const imW = rIm[k] * weight;
          aRe[k] += reW * cr2 - imW * ci2;
          aIm[k] += reW * ci2 + imW * cr2;
        }
      }
    } else {
      // Incoherent analyze stores the RAW FFT magnitude in r.re (im = 0). Sum its
      // POWER so the mag→amplitude conversion in overlayOnto applies ONCE.
      const aPow = this._accumPow;
      const rRe = r.re;
      for (let k = 0; k < N; k++) {
        const mag = rRe[k];
        aPow[k] += mag * mag * weight;
      }
    }
    this._accumFrames += weight;
    return true;
  }

  /**
   * Multi-tone cross-tick rotation (Java FftAnalyzerWorker.accumulateMultiTone):
   * each detected strong tone's lobe is de-rotated by a CONSTANT phase equal to that
   * tone's own inter-tick advance, so the lobe is preserved intact and the
   * accumulated peak stays at the tone's true (sub-bin) frequency — no grid-lock.
   * Each tone carries its OWN phase-lock loop (toneDroppedSamples) so a frozen-κ
   * residual can't ramp its lobe out of phase over a long average — the per-tone
   * analogue of the single-reference loop, so IMD tone pairs hold. The dual-tone IMD
   * grid (a·F1+b·F2) rides a·ang1+b·ang2 (the SAME tracked tone phases), and the
   * non-tone bins get a plain time-shift drift-corrected by the strong tones' pooled
   * clock estimate. This is the honest "measure what was sampled" path for unknown /
   * external multi-tone signals: the tones are found from the spectrum, not assumed.
   * Run SERIALLY here; the desktop parallelizes the bin loop (parallelChunks) — the
   * per-bin writes are disjoint, so the math is identical.
   */
  _accumulateMultiTone(r, N, weight, delta) {
    const fftSize = r.fftSize;
    const nT = this._accumToneKappa.length;
    const aRe = this._accumRe, aIm = this._accumIm;
    const accumFrames = this._accumFrames;
    const cRe = new Float64Array(nT);
    const cIm = new Float64Array(nT);
    const lo = new Int32Array(nT);
    const hi = new Int32Array(nT);
    const angT = new Float64Array(nT);   // each tone's tracked de-rotation phase (for the IMD grid)
    // The strong tones share ONE clock: each tone's per-tone loop correction measures
    // the same relative drift δ ≈ correction/delta. Pool them (strength-weighted) and
    // de-rotate the NON-tone bins by delta·(1+δ).
    let dsum = 0.0, wsum = 0.0;
    // Per-tone κ refine (mirrors the single-reference one-shot refine): while
    // unrefined, leave the PLL OFF and measure each tone's de-rotated phase slope vs
    // the PINNED κ; fold Δκ into accumToneKappa once after KAPPA_MEAS_FRAMES.
    const kRefining = MULTI_KAPPA_REFINE && aRe != null && accumFrames > 0 && !this._multiKappaRefined;
    const kAnchor = kRefining && (this._multiKappaMeasFrames === 0 || this._multiKappaSkipNext);
    if (kRefining && this._multiKappaMeasFrames === 0) {   // first refine frame: (re)allocate per-tone phase state
      this._multiKappaCumPhase = new Float64Array(nT);
      this._multiKappaLastPhase = new Float64Array(nT);
      this._multiKappaCleanSpan = 0.0;
    }
    for (let t = 0; t < nT; t++) {
      const kappa = this._accumToneKappa[t];
      const k0 = Math.min(Math.max(0, Math.round(kappa)), N - 1);
      // De-rotate this tone's lobe by its OWN frequency, advanced by the tone's
      // accumulated loop correction. A frozen per-tone κ would ramp the lobe phase
      // and vector-cancel it — the multi-tone analogue of the single-reference drift.
      const effDelta = delta + this._toneDroppedSamples[t];
      let ang = -2.0 * Math.PI * effDelta * kappa / fftSize;
      let cr = Math.cos(ang), sr = Math.sin(ang);
      // Per-tone phase-lock loop: compare this tone's de-rotated phase to the DEEP
      // accumulated phase at its bin (rotated by the same ang so the two compare
      // directly) and fold a fraction of the residual into the tone's correction.
      // Skipped on the first contributing frame (no reference yet). On a re-sync
      // (gapRecoverPending) do a one-shot FULL realign of THIS frame too.
      if (aRe != null && accumFrames > 0 && kappa > 0.0) {
        const obsPhase = Math.atan2(r.re[k0] * sr + r.im[k0] * cr,
                                    r.re[k0] * cr - r.im[k0] * sr);
        if (kRefining) {
          // κ refine: accumulate this tone's clean phase step (pinned-κ de-rotation;
          // the anchor / re-anchor frame only sets the reference).
          if (kAnchor) {
            this._multiKappaLastPhase[t] = obsPhase;
          } else {
            this._multiKappaCumPhase[t] += ieeeRemainder(obsPhase - this._multiKappaLastPhase[t], 2.0 * Math.PI);
            this._multiKappaLastPhase[t] = obsPhase;
          }
        } else {
          // Per-tone phase-lock loop: fold a fraction of the running mis-alignment vs
          // the DEEP accumulated phase into the tone's correction. A residual far
          // beyond the per-tick noise is a phase DISCONTINUITY (DDS jump / reconnect)
          // the window gate missed — snap (gain 1). On a re-sync do a full realign too.
          const accumPhase = Math.atan2(aIm[k0], aRe[k0]);
          const residual = ieeeRemainder(obsPhase - accumPhase, 2.0 * Math.PI);
          const jump = Math.abs(residual) > PHASE_JUMP_RAD;
          const gain = (this._gapRecoverPending || jump) ? 1.0 : PHASE_TRACK_GAIN;
          const corr = gain * residual * fftSize / (2.0 * Math.PI * kappa);
          this._toneDroppedSamples[t] += corr;
          if (this._gapRecoverPending || jump) {
            ang = -2.0 * Math.PI * (effDelta + corr) * kappa / fftSize;
            cr = Math.cos(ang);
            sr = Math.sin(ang);
          }
        }
      }
      angT[t] = ang;
      cRe[t] = cr;
      cIm[t] = sr;
      lo[t] = Math.max(0, k0 - TONE_LOBE_HALF_BINS);
      hi[t] = Math.min(N - 1, k0 + TONE_LOBE_HALF_BINS);
      if (FORK_NONTONE_DRIFT && aRe != null && accumFrames > 0 && Math.abs(delta) > 1.0) {
        const w = Math.hypot(aRe[k0], aIm[k0]);   // strength weight
        dsum += w * this._toneDroppedSamples[t] / delta;
        wsum += w;
      }
    }
    // One re-sync realign serves every tone; clear after they've all used it.
    this._gapRecoverPending = false;
    // IMD-product grid (true dual-tone): each product a·F1+b·F2 rides a·ang1+b·ang2 —
    // the SAME tracked tone phases — so its EXACT sub-bin frequency (clock offset /
    // wobble included) is de-rotated, not the integer bin-centre.
    let prodIdx = null;
    let prodCos = null, prodSin = null;
    if (nT === 2 && this._accumToneKappa[0] > 0.0 && this._accumToneKappa[1] > 0.0) {
      const ORDER = 9;
      const pa = new Int32Array((2 * ORDER + 1) * (2 * ORDER + 1));
      const pb = new Int32Array(pa.length);
      const pk = new Int32Array(pa.length);
      let cap = 0;
      for (let a = -ORDER; a <= ORDER; a++) {
        for (let b = -ORDER; b <= ORDER; b++) {
          const ord = Math.abs(a) + Math.abs(b);
          if (ord < 2 || ord > ORDER) continue;   // tones (ord 1) handled per-tone above
          const kp = a * this._accumToneKappa[0] + b * this._accumToneKappa[1];
          if (kp < 1.0 || kp > N - 2) continue;
          pk[cap] = Math.round(kp);
          pa[cap] = a;
          pb[cap] = b;
          cap++;
        }
      }
      prodCos = new Float64Array(cap);
      prodSin = new Float64Array(cap);
      for (let p = 0; p < cap; p++) {
        const ph = pa[p] * angT[0] + pb[p] * angT[1];
        prodCos[p] = Math.cos(ph);
        prodSin[p] = Math.sin(ph);
      }
      if (this._imdGridIdx == null || this._imdGridIdx.length < N) this._imdGridIdx = new Int32Array(N);
      prodIdx = this._imdGridIdx;
      prodIdx.fill(-1, 0, N);
      for (let p = 0; p < cap; p++) {
        const plo = Math.max(0, pk[p] - TONE_LOBE_HALF_BINS);
        const phi = Math.min(N - 1, pk[p] + TONE_LOBE_HALF_BINS);
        for (let k = plo; k <= phi; k++) prodIdx[k] = p;
      }
    }
    // Non-tone, non-product bins: plain time-shift, drift-corrected by the pooled δ so
    // the weak harmonics track the same clock as the strong teeth.
    const deltaEff = wsum > 0.0 ? delta * (1.0 + dsum / wsum) : delta;
    const rampSlope = -2.0 * Math.PI * deltaEff / fftSize;
    const stepRe = Math.cos(rampSlope), stepIm = Math.sin(rampSlope);
    // Serial port of the desktop parallelChunks fork loop: seed the fork phasor
    // exp(j·k·rampSlope) at k=0 and a tone-lobe cursor, then walk every bin once.
    let phRe = 1.0;   // cos(rampSlope·0)
    let phIm = 0.0;   // sin(rampSlope·0)
    let curT = 0;
    for (let k = 0; k < N; k++) {
      while (curT < nT && k > hi[curT]) curT++;
      let rotRe, rotIm;
      const p = (prodIdx != null) ? prodIdx[k] : -1;
      if (curT < nT && k >= lo[curT]) { rotRe = cRe[curT]; rotIm = cIm[curT]; }   // tone lobe
      else if (p >= 0) { rotRe = prodCos[p]; rotIm = prodSin[p]; }                // IMD product
      else { rotRe = phRe; rotIm = phIm; }                                        // fork
      const rRe = r.re[k] * weight;
      const rIm = r.im[k] * weight;
      aRe[k] += rRe * rotRe - rIm * rotIm;
      aIm[k] += rRe * rotIm + rIm * rotRe;
      const nextRe = phRe * stepRe - phIm * stepIm;
      phIm = phRe * stepIm + phIm * stepRe;
      phRe = nextRe;
    }
    // Per-tone κ refine: advance the shared frame count / clean span; once the window
    // is full fold each tone's Δκ (its phase slope) into accumToneKappa, so the tones
    // AND the IMD grid (a·κ1+b·κ2) lock from the next frame on.
    if (kRefining) {
      if (kAnchor) {
        if (this._multiKappaMeasFrames === 0) this._multiKappaMeasFrames = 1;
        this._multiKappaLastDelta = delta;
        this._multiKappaSkipNext = false;
      } else {
        this._multiKappaCleanSpan += delta - this._multiKappaLastDelta;
        this._multiKappaLastDelta = delta;
        if (++this._multiKappaMeasFrames >= KAPPA_MEAS_FRAMES && this._multiKappaCleanSpan > 0.0) {
          for (let t = 0; t < nT; t++) {
            this._accumToneKappa[t] += this._multiKappaCumPhase[t] * fftSize
              / (2.0 * Math.PI * this._multiKappaCleanSpan);
          }
          this._multiKappaRefined = true;
        }
      }
    }
  }

  /** Finds the fractional bins of the "strong" tones in r's spectrum (Java
   *  detectStrongTones) — local maxima within STRONG_TONE_REL_DB of the strongest
   *  peak, merged within MIN_TONE_SEP_BINS, harmonics rejected, refined to sub-bin by
   *  parabolic interpolation, sorted ascending. Detection from the spectrum (not
   *  commanded frequencies) is deliberate: the generator may be external, so the FFT
   *  is the only thing that knows what was actually received.
   *  @returns {Float64Array} sub-bin tone positions (fractional bins). */
  _detectStrongTones(r) {
    const db = r.amplitudeDbFs;
    if (db == null) return new Float64Array(0);
    const halfSize = r.fftSize / 2;
    if (halfSize < 4) return new Float64Array(0);
    // Skip the DC/ULF zone: a residual DC offset leaks through the window main lobe
    // into the first bins and would be taken as a spurious second tone.
    const freqRes = r.sampleRate / r.fftSize;
    const minBin = Math.max(2, Math.ceil(DC_REJECT_HZ / freqRes));
    let strongest = -Number.MAX_VALUE;
    for (let k = minBin; k < halfSize; k++) {
      if (db[k] > db[k - 1] && db[k] >= db[k + 1] && db[k] > strongest) strongest = db[k];
    }
    const thresh = strongest - this._strongToneRelDb;
    // Keep the strongest MAX_TONES local maxima above the threshold, merging peaks
    // closer than MIN_TONE_SEP_BINS. Selecting by strength (not scan order) keeps the
    // real tones even when 1/f or HF-rise noise also clears the threshold.
    const bins = new Int32Array(MAX_TONES);
    const lvls = new Float64Array(MAX_TONES);
    let count = 0;
    for (let k = minBin; k < halfSize; k++) {
      if (db[k] < thresh) continue;
      if (!(db[k] > db[k - 1] && db[k] >= db[k + 1])) continue;   // local max
      const lvl = db[k];
      let near = -1;
      for (let t = 0; t < count; t++) {
        if (Math.abs(k - bins[t]) < MIN_TONE_SEP_BINS) { near = t; break; }
      }
      if (near >= 0) {
        if (lvl > lvls[near]) { bins[near] = k; lvls[near] = lvl; }
      } else if (count < MAX_TONES) {
        bins[count] = k; lvls[count] = lvl; count++;
      } else {
        let weakest = 0;
        for (let t = 1; t < count; t++) if (lvls[t] < lvls[weakest]) weakest = t;
        if (lvl > lvls[weakest]) { bins[weakest] = k; lvls[weakest] = lvl; }
      }
    }
    // Harmonic rejection: a single tone's harmonics clear the floor and would force
    // the per-tone path (frozen parabolic κ, NO phase-slope refine — the long-run
    // drift). A harmonic sits at an integer multiple h≥2 of the strongest tone AND
    // well below it; the single-reference time-shift already aligns it via h×, so drop
    // it. Genuine inharmonic IMD partners aren't integer multiples and aren't far
    // below, so they survive.
    if (count >= 2) {
      let fund = 0;
      for (let i = 1; i < count; i++) if (lvls[i] > lvls[fund]) fund = i;
      const f0bin = bins[fund];
      let kept = 0;
      for (let i = 0; i < count; i++) {
        let harmonic = false;
        if (i !== fund && f0bin > 0.0) {
          const ratio = bins[i] / f0bin;
          const h = Math.round(ratio);
          harmonic = h >= 2
            && Math.abs(ratio - h) * f0bin < MIN_TONE_SEP_BINS
            && lvls[i] < lvls[fund] - HARMONIC_REJECT_BELOW_DB;
        }
        if (!harmonic) { bins[kept] = bins[i]; lvls[kept] = lvls[i]; kept++; }
      }
      count = kept;
    }
    // Sort the kept tones by bin ascending (needed by the region walk).
    for (let i = 1; i < count; i++) {
      const b = bins[i], l = lvls[i];
      let j = i - 1;
      while (j >= 0 && bins[j] > b) { bins[j + 1] = bins[j]; lvls[j + 1] = lvls[j]; j--; }
      bins[j + 1] = b; lvls[j + 1] = l;
    }
    const out = new Float64Array(count);
    for (let i = 0; i < count; i++) out[i] = this._refineBin(db, bins[i], halfSize);
    return out;
  }

  /** 3-point parabolic peak interpolation (on dB) around bin k (Java refineBin). */
  _refineBin(db, k, halfSize) {
    if (k <= 1 || k >= halfSize) return k;
    const yl = db[k - 1], yc = db[k], yr = db[k + 1];
    const denom = yl - 2.0 * yc + yr;
    let d = (Math.abs(denom) < 1e-12) ? 0.0 : 0.5 * (yl - yr) / denom;
    if (d < -0.5) d = -0.5;
    if (d > 0.5) d = 0.5;
    return k + d;
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

    // Derive the (mag → amplitude) conversion factor from the pre-overlay r:
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
