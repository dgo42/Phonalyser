/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.fft.FftAnalyzer (the live FFT brain)
// and the math helpers it pulls from org.edgo.audio.measure.fft.MathUtil
// (parabolicBinInterp / chebyshevT / acosh). Produces a fully populated
// FftResult per tick: coherent (complex) OR incoherent (power) cross-tick
// averaging, sub-bin fundamental + second-tone estimation, per-lobe constant-
// phase de-rotation (with the dual-tone IMD-product grid), the κ long-baseline
// refit, the windowed per-frame FFTs, and the THD / SNR / SINAD / THD+N /
// noise-floor metrics.
//
// dB references match the desktop exactly: amplitudes are dBFS via the single-
// sided ×2 (except DC / Nyquist) and the 1/(N·coherentGain) normalisation;
// every ratio (THD / SNR / THD+N / harmonic %) is taken against refLin in those
// same FS-relative units (callers convert dBV ↔ dBFS at the boundary). All DSP
// runs on Float64Array (binary64 == the desktop double).
//
// PORTING NOTE - frame rejection. The desktop hard-codes `frameRejection =
// false` (the only shipping configuration: capture glitches are handled by the
// cross-tick gap recovery / SpectralDiscontinuityDetector in the worker, not by
// per-frame rejection). With it off, rejectionFeasible is always false, the
// R-invariant scan and the phase-coherence subtraction never run, no events are
// ever collected, and the "longest clean segment" is the whole frame range
// (bestStart = 0, bestLen = frameCount). This port reflects that shipping path
// directly rather than porting the dead branches.

import { fft } from '../dsp/fft.js';
import { FftResult } from './fft-result.js';
import { hopSamples, minHopSamples } from '../enums/fft-overlap.js';
import { ieeeRemainder } from '../dsp/mathutil.js';

/** Lower edge (Hz) of the no-hint fundamental search: DC ... this is ignored so
 *  window leakage from a residual DC offset can't win the global max. */
const FUND_SEARCH_MIN_HZ = 5.0;
/** Upper edge of the no-hint fundamental search as a fraction of Nyquist. */
const FUND_SEARCH_MAX_NYQUIST_FRACTION = 0.7;

/** Shared empty grid published for non-dual-tone (or non-de-rotated) ticks. */
const NO_IMD_PRODUCTS = new Int32Array(0);

/** IEC 61672 analog A-weighting; R_A(f)^2 with the +2.00 dB 1 kHz normalization
 *  folded in, so A(1 kHz) = 0 dB. */
const AW_POLE1_HZ = 20.6;
const AW_POLE2_HZ = 107.7;
const AW_POLE3_HZ = 737.9;
const AW_POLE4_HZ = 12194.0;
const AW_NORM_1KHZ_DB = 2.00;
const AW_NORM_POWER = Math.pow(10.0, AW_NORM_1KHZ_DB / 10.0);

/** How many bins the skirt-end walk must look ahead before declaring the floor
 *  reached (Java skirtLookAheadBins). Frames sharing samples = fftSize / step, so
 *  the averaged floor wobbles across that many bins; +2 guards the 0%-overlap
 *  case. 0%->3, 50%->4, 75%->6, 87.5%->10, 93.75%->18, random -> its densest
 *  spacing. Callers pass the mode's SMALLEST possible spacing, not the drawn or
 *  mean one: the walk must bridge the coarsest floor texture the mode can
 *  produce, and random hopping is not credited with decorrelating it. An unknown
 *  spacing (a loaded spectrum file without geometry headers) degrades to the
 *  0%-overlap value. */
function skirtLookAheadBins(fftSize, stepSamples) {
  const framesSharing = stepSamples > 0
    ? Math.max(1, Math.round(fftSize / stepSamples))
    : 1;
  return framesSharing + 2;
}

export class FftAnalyzer {
  /**
   * @param deps {transform} - transform: (re, im) => void, the FFT this analyser runs on every
   *   frame. Defaults to the serial dsp/fft.js transform, which is the reference every result is
   *   measured against; a caller that can DIVIDE a transform across threads - only the analyzer
   *   worker can, and only in a page that allows shared memory - hands its own in here. The
   *   analyser itself knows nothing about threads, and nothing else about it changes.
   */
  constructor({ transform } = {}) {
    this._transform = transform || fft;
    // Cached window-function table - rebuilt only when (fftSize, windowType)
    // changes (analyze runs repeatedly with the same pair on the worker).
    this._cachedWindow = null;
    this._cachedWindowSize = 0;
    this._cachedWindowType = null;
    /** Coherent gain (mean) of the cached window. */
    this._cachedCohGain = 0;
    /** Normalized equivalent noise bandwidth of _cachedWindow in bins,
     *  N·Σw²/(Σw)² (Hann 1.5); cached with the window, divides the noise
     *  integrals (never coherent lines). */
    this._cachedNenbw = 0;
    // Cached per-bin A-weight power table - rebuilt only when (halfSize, freqRes)
    // changes; consulted per noise bin each tick (see _aWeightTable).
    this._cachedAweight = null;
    this._cachedAweightFreqRes = 0.0;

    // Second-tone frequency hint (Hz); NaN disables it.
    this._secondToneHintHz = NaN;
    // Multi-tone flag for the next analyze (skips the single-sine glitch scan -
    // already always skipped here, but kept for the second-tone detect path).
    this._multiTone = false;
    // Spectrum-only fast-path: skip the per-tick THD / SNR / SINAD / noise sweeps.
    this._spectrumOnly = false;

    // The frame spectra the last prelude on THIS analyzer computed, with the identity they
    // belong to - see _preludeFrameFor. The prelude transforms frames 0 and 1 to find the
    // fundamental and its sub-bin position, and those are the very first frames the
    // accumulation then transforms again; keeping them turns the second pass into a copy-free
    // read. Replaced whole at every prelude, so at most two frames are held.
    this._preludeFrames = null;

    /** Where the milliseconds of the analysis running now went, or null while stage timing is
     *  off (see startStageTiming). The stages live here, so their measurement does too: the
     *  windowing and the per-frame transforms in _frameFft, the de-rotation in
     *  accumulatePartial, the statistics in finalize. Off, every stage costs one null test. */
    this.stageMs = null;

    // Reusable scratch (Float64Array) - re-used across calls to kill per-tick alloc.
    this._scratchAmplLinear = null;
    this._scratchSignalMask = null;
    this._scratchNoiseCand = null;
    this._scratchNoiseGlob = null;
  }

  /** Sets (or clears, with NaN) the second-tone frequency hint for the next analyze. */
  setSecondToneHintHz(hz) { this._secondToneHintHz = hz; }
  /** Marks the next analyze as a multi-tone signal (dual tone, etc.). */
  setMultiTone(multiTone) { this._multiTone = multiTone; }
  /** Toggles the spectrum-only fast-path for the next analyze. */
  setSpectrumOnly(on) { this._spectrumOnly = on; }

  /** Arms (or disarms) per-stage timing for the analysis that follows, clearing the counters.
   *  Armed per tick by the caller, because the switch that turns diagnostics on is a setting of
   *  the page and not of this class: the analysis runs in a worker, which has neither that
   *  setting's storage nor a query string of its own to read it from.
   *  @param {boolean} on */
  startStageTiming(on) {
    this.stageMs = on
      ? { windowMs: 0, transformMs: [], frameMs: 0, derotateMs: 0, statsMs: 0 }
      : null;
  }

  /** Closes the statistics stage of a timed analysis; a no-op while timing is off. finalize has
   *  two exits - a spectrum with no usable fundamental leaves early - and both end that stage. */
  _stageStatsDone(startedAt) {
    if (this.stageMs) this.stageMs.statsMs += performance.now() - startedAt;
  }

  /** Returns the cached window table for (N, type), rebuilding via buildWindow
   *  only when the (size, type) pair changes; caches its coherent gain too. */
  _getCachedWindow(N, type) {
    if (this._cachedWindow != null && this._cachedWindowSize === N && this._cachedWindowType === type) {
      return this._cachedWindow;
    }
    this._cachedWindow = this.buildWindow(N, type);
    this._cachedWindowSize = N;
    this._cachedWindowType = type;
    let sum = 0.0;
    let sum2 = 0.0;
    for (let i = 0; i < N; i++) {
      sum += this._cachedWindow[i];
      sum2 += this._cachedWindow[i] * this._cachedWindow[i];
    }
    this._cachedCohGain = sum / N;
    this._cachedNenbw = N * sum2 / (sum * sum);
    return this._cachedWindow;
  }

  /** Returns `current` if length matches, else a fresh Float64Array. */
  _scratchOrAlloc(current, size) {
    return (current != null && current.length === size) ? current : new Float64Array(size);
  }

  /** The spectrum of the frame starting at sample `base`, if the prelude on THIS analyzer
   *  already transformed exactly that frame - same signal, same spacing, same window - else
   *  null. The prelude's two frames ARE accumulation frames 0 and 1 (it windows from sample 0
   *  and from sample `step` with the window `sp` carries), so at the geometry that analyses two
   *  frames per tick the accumulation needs no transform of its own at all.
   *
   *  The identity is checked, not assumed: a pool worker runs its own analyzer that never ran a
   *  prelude and so finds nothing here, and a stale set from an earlier signal or geometry fails
   *  the comparison and is transformed afresh. */
  _preludeFrameFor(samples, base, sp) {
    const p = this._preludeFrames;
    if (p == null || p.samples !== samples || p.step !== sp.step
        || p.fftSize !== sp.fftSize || p.window !== sp.window) return null;
    const i = (base === 0) ? 0 : ((base === p.step) ? 1 : -1);
    if (i < 0 || p.re[i] == null) return null;
    return { re: p.re[i], im: p.im[i] };
  }

  /** How many LEADING frames of `samples` the prelude on this analyzer already transformed -
   *  the frames an accumulation here gets for free, and therefore the frames it is pointless to
   *  hand to another thread. 0 on an analyzer that did not run this prelude. */
  preludeFrameCount(samples, sp) {
    let n = 0;
    while (this._preludeFrameFor(samples, n * sp.step, sp) != null) n++;
    return n;
  }

  /** Windows + FFTs the frame starting at local sample offset `localStart`,
   *  leaving re / im populated with the result. (No frame cache in the browser
   *  port - the desktop FrameFftCache key offset is the worker's concern.) */
  _frameFft(samples, localStart, fftSize, window, re, im) {
    const st = this.stageMs;
    const t0 = st ? performance.now() : 0;
    for (let n = 0; n < fftSize; n++) {
      re[n] = samples[localStart + n] * window[n];
      im[n] = 0.0;
    }
    const t1 = st ? performance.now() : 0;
    this._transform(re, im);
    if (st) {
      const t2 = performance.now();
      st.windowMs += t1 - t0;
      // One entry per transform, in the order the tick ran them - a tick's transforms are a
      // handful, and which of them is slow is the question this log exists to answer.
      st.transformMs.push(t2 - t1);
      st.frameMs += t2 - t0;
    }
  }

  // ==========================================================================
  // Analysis
  // ==========================================================================

  /**
   * Runs coherent-averaged FFT analysis on a normalized mono signal.
   *
   * @param {Float64Array|Float32Array|number[]} samples signal samples (−1 ... +1).
   * @param {number} sampleRate    sample rate in Hz.
   * @param {number} fftSize       FFT frame length - must be a power of 2.
   * @param {number} harmonicCount number of harmonics to evaluate (2nd ... N-th).
   * @param {string|number} windowType WindowType enum token (e.g. "BH4").
   * @param {string} overlap         FftOverlap enum token (e.g. "PCT_0").
   * @param {number} [snrFreqMin=0]  lower bound for SNR noise integration in Hz (0 = no limit).
   * @param {number} [snrFreqMax=0]  upper bound for SNR noise integration in Hz (0 = no limit).
   * @param {boolean} [coherentAveraging=true] true = complex (coherent) averaging; false = power.
   * @param {number} [fundRefDbFs=NaN] known fundamental level in dBFS; NaN = unknown.
   * @param {number} [expectedFundHz=NaN] when set, restrict the fundamental search
   *        to ±10 bins around this frequency (a deeply notched fundamental must
   *        not be lost to a louder spur); NaN = legacy loudest-bin behaviour.
   * @param {FftResult} [outResult] pool slot to write into; a fresh one is
   *        allocated when omitted.
   * @param {?number} [step=null]       stated frame spacing in samples; null derives
   *        it from a FIXED overlap (the random mode has none and throws).
   * @param {?number} [frameCount=null] stated number of frames; null derives it
   *        from the buffer length at the derived spacing.
   * @returns {FftResult} the populated result (`outResult` when supplied).
   */
  analyze(samples, sampleRate, fftSize, harmonicCount,
          windowType = 'HANN', overlap = 'PCT_0',
          snrFreqMin = 0.0, snrFreqMax = 0.0, coherentAveraging = true,
          fundRefDbFs = NaN, expectedFundHz = NaN, outResult = new FftResult(),
          step = null, frameCount = null) {
    // Serial reference path - the single-thread coordinator: run the
    // (single-threaded) prelude, accumulate the WHOLE frame range in one
    // partial, then finalize. The parallel pool runs the same three steps with
    // the accumulation split into contiguous frame ranges across workers (see
    // accumulatePartial / mergePartials / finalize) - summation is associative,
    // so the merged total is bit-for-bit the serial accumulation.
    const sp = this.prelude(samples, sampleRate, fftSize, harmonicCount,
      windowType, overlap, coherentAveraging, expectedFundHz, outResult,
      step, frameCount);
    const partial = this.accumulatePartial(samples, 0, sp.frameCount, sp);
    return this.finalize(partial, sp, snrFreqMin, snrFreqMax, fundRefDbFs, outResult);
  }

  /**
   * PART 1 (single-threaded) of the split analyze path: everything before the
   * frame-accumulation loop. Sizes `outResult`, builds the window, finds the
   * fundamental, computes the SHARED sub-bin fundamental kFractional (incl. the
   * long-baseline κ refit), the second-tone kappa2, and the dual-tone IMD-product
   * grid - and writes fundamental2HzRefined + the IMD arrays into `outResult`.
   *
   * The returned `sharedParams` carries every value the per-frame derotation +
   * the finalize stats path need. It MUST be broadcast unchanged to every pool
   * worker so each derotates with the identical kFractional / kappa2 / IMD grid
   * and the GLOBAL frame index - only then does splitting the frames stay exact.
   *
   * This is the web's ONE geometry authority (Java's single full analyze): the
   * caller states the frame spacing `step` and the number of frames
   * `frameCount`, so an over-sized buffer is never mis-read as extra frames.
   * The analyser consumer states its per-tick DRAWN hop directly - under the
   * random overlap the buffer's two frames are spaced by the hop drawn for the
   * next tick, so the second frame is exactly the next tick's first. Passing
   * null for either derives BOTH from a fixed overlap, which is Java's
   * convenience overload; the random mode has no fixed spacing and refuses.
   *
   * @param {?number} [step=null]       stated frame spacing in samples.
   * @param {?number} [frameCount=null] stated number of frames.
   * @returns {object} sharedParams (see field list at the end).
   */
  prelude(samples, sampleRate, fftSize, harmonicCount,
          windowType, overlap, coherentAveraging, expectedFundHz, outResult,
          step = null, frameCount = null) {
    if (popcount(fftSize) !== 1) {
      throw new Error('fftSize must be a power of 2, got: ' + fftSize);
    }

    // The one place a fixed overlap's spacing is derived: everything below runs
    // on the stated geometry.
    if (step == null || frameCount == null) {
      step = hopSamples(overlap, fftSize);
      frameCount = samples.length >= fftSize
        ? Math.trunc((samples.length - fftSize) / step) + 1
        : 0;
    }
    if (!(step >= 1)) {
      throw new Error('Frame spacing must be positive, got: ' + step);
    }
    if (!(frameCount > 0) || samples.length < fftSize) {
      throw new Error('Signal too short for fftSize=' + fftSize +
        ': need at least ' + fftSize + ' samples, got ' + samples.length);
    }
    if ((frameCount - 1) * step + fftSize > samples.length) {
      throw new Error('Stated geometry exceeds the buffer: ' + frameCount
        + ' frames at step ' + step + ' need ' + ((frameCount - 1) * step + fftSize)
        + ' samples, got ' + samples.length);
    }

    const freqRes = sampleRate / fftSize;
    const halfSize = fftSize / 2;

    // Size the slot's bin / harmonic arrays so the per-frame loops write into them.
    outResult.ensureArrays(halfSize + 1, harmonicCount);

    // --- Window + coherent gain (cached on (fftSize, windowType)) -------------
    const window = this._getCachedWindow(fftSize, windowType);
    const cohGain = this._cachedCohGain;
    const nenbw = this._cachedNenbw;

    // --- Pre-pass peak-bin estimate (frame 0) --------------------------------
    const f0Re = new Float64Array(fftSize);
    const f0Im = new Float64Array(fftSize);
    this._frameFft(samples, 0, fftSize, window, f0Re, f0Im);

    // Restrict the fundamental search to FUND_SEARCH_MIN_HZ ... 0.7·Nyquist so DC
    // leakage and high-frequency spurs can't steal the global max.
    const fundSearchMinBin = Math.max(2, Math.ceil(FUND_SEARCH_MIN_HZ / freqRes));
    const fundSearchMaxBin = Math.min(halfSize,
      Math.floor(FUND_SEARCH_MAX_NYQUIST_FRACTION * halfSize));
    let intFundBin;
    if (!Number.isNaN(expectedFundHz) && expectedFundHz > 0.0) {
      const expectedBin = Math.round(expectedFundHz * fftSize / sampleRate);
      intFundBin = peakBin(f0Re, f0Im, Math.max(1, expectedBin - 10), Math.min(halfSize, expectedBin + 10));
    } else {
      intFundBin = peakBin(f0Re, f0Im, fundSearchMinBin, fundSearchMaxBin);
    }

    // --- Frame range (rejection off: the clean segment is the whole range) ----
    const bestStart = 0;
    const bestLen = frameCount;

    // --- Re-estimate kFractional from the clean segment's first frame --------
    // segBaseSample == 0 here, so the segment's first frame IS the pre-pass
    // frame - alias f0 instead of re-FFT-ing.
    const s0Re = f0Re;
    const s0Im = f0Im;
    // Refine intFundBin within ±2 bins of the pre-pass estimate.
    let refIntBin = peakBin(s0Re, s0Im,
      Math.max(1, intFundBin - 2), Math.min(halfSize, intFundBin + 2));
    let kFractional;
    let s1Re = null;
    let s1Im = null;
    if (bestLen >= 2) {
      const estStep = step;   // the stated spacing - validated positive above
      s1Re = new Float64Array(fftSize);
      s1Im = new Float64Array(fftSize);
      this._frameFft(samples, bestStart * step + estStep, fftSize, window, s1Re, s1Im);
      const phi0 = Math.atan2(s0Im[refIntBin], s0Re[refIntBin]);
      const phi1 = Math.atan2(s1Im[refIntBin], s1Re[refIntBin]);
      const expectedDiff = 2.0 * Math.PI * refIntBin * estStep / fftSize;
      const rawDiff = phi1 - phi0;
      const m = Math.round((rawDiff - expectedDiff) / (2.0 * Math.PI));
      kFractional = (rawDiff - 2.0 * Math.PI * m) * fftSize / (2.0 * Math.PI * estStep);
    } else {
      kFractional = parabolicBinInterp(s0Re, s0Im, refIntBin, fftSize);
    }
    intFundBin = refIntBin;

    // --- Second tone (dual-/multi-tone): same clean-frame sub-bin estimate ----
    // The hint is the COMMANDED second-tone frequency - set only when
    // fund-from-generator is on AND the generator is internal. When it is absent
    // (external generator, or that option off) but the signal IS dual-tone, DETECT
    // the second tone from the CLEAN single frame: the pair is simply THE TWO
    // STRONGEST TONES, so F2 is the strongest local maximum left once the
    // fundamental's own peak is excluded. The exclusion is only the 2 bins either
    // side of it - those ARE the same peak, not a partner. No minimum separation
    // and no minimum level: a bass pair sits 8 bins apart at 41 and 89 Hz with 8k
    // points at 48 kHz (5.86 Hz per bin), and a partner may be tens of dB down
    // after the device under test attenuates it. The lower edge is a fixed 10 Hz
    // guard keeping a residual DC offset's window-main-lobe leakage out of the
    // contest.
    outResult.fundamental2HzRefined = NaN;
    let effHint = this._secondToneHintHz;
    if (Number.isNaN(effHint) && this._multiTone && intFundBin > 0) {
      const loB = Math.max(3, Math.ceil(10.0 / freqRes));
      let bestAmp2 = 0.0;
      let bestK = -1;
      for (let k = loB; k < halfSize; k++) {
        if (Math.abs(k - intFundBin) <= 2) continue;   // F1's own peak, not a partner
        const a2 = s0Re[k] * s0Re[k] + s0Im[k] * s0Im[k];
        if (a2 > bestAmp2
            && a2 >= s0Re[k - 1] * s0Re[k - 1] + s0Im[k - 1] * s0Im[k - 1]
            && a2 > s0Re[k + 1] * s0Re[k + 1] + s0Im[k + 1] * s0Im[k + 1]) {
          bestAmp2 = a2;
          bestK = k;
        }
      }
      if (bestK > 0) effHint = bestK * freqRes;
    }
    if (!Number.isNaN(effHint) && effHint > 0.0) {
      const k0b = Math.round(effHint / freqRes);
      if (k0b >= 3 && k0b <= halfSize - 3) {
        const ri2 = peakBin(s0Re, s0Im, Math.max(1, k0b - 2), Math.min(halfSize, k0b + 2));
        let kf2;
        if (bestLen >= 2) {
          // s1 was computed one frame after the segment base - the phase pair
          // must state that SAME spacing.
          const estStep = step;
          const p0 = Math.atan2(s0Im[ri2], s0Re[ri2]);
          const p1 = Math.atan2(s1Im[ri2], s1Re[ri2]);
          const exp2 = 2.0 * Math.PI * ri2 * estStep / fftSize;
          const m2 = Math.round((p1 - p0 - exp2) / (2.0 * Math.PI));
          kf2 = (p1 - p0 - 2.0 * Math.PI * m2) * fftSize / (2.0 * Math.PI * estStep);
        } else {
          kf2 = parabolicBinInterp(s0Re, s0Im, ri2, fftSize);
        }
        outResult.fundamental2HzRefined = kf2 * freqRes;
      }
    }

    // --- Full-integration κ refit (long-baseline) ----------------------------
    if (coherentAveraging && bestLen >= 4) {
      const kRefined = this._refineKappaOverSegment(samples, window, fftSize, step,
        bestStart, bestLen, refIntBin, kFractional);
      if (Math.abs(kRefined - kFractional) < 0.5) {     // sanity: reject a wild fit
        kFractional = kRefined;
      }
    }

    // --- Dual-tone: publish the intermodulation-product grid -----------------
    // Every product a·κ_F1 + b·κ_F2 (b≠0, |a|+|b| ≤ order) that lands in band is
    // listed with its bin, so the predistortion engine can read each product's
    // phasor straight from re/im[bin]. The list doubles as the product part of
    // pass 2's coherent family below: each entry becomes a de-rotation region of
    // its own, rotated by a·Φ1 + b·Φ2. Default to "no products published":
    // recycled result slots must not leak a previous dual-tone tick's grid into a
    // single-tone tick.
    outResult.imdProductA = NO_IMD_PRODUCTS;
    outResult.imdProductB = NO_IMD_PRODUCTS;
    outResult.imdProductBin = NO_IMD_PRODUCTS;
    if (coherentAveraging && bestLen >= 2 && !Number.isNaN(outResult.fundamental2HzRefined)
        && outResult.fundamental2HzRefined > 0.0 && kFractional > 0.0) {
      const kappa2 = outResult.fundamental2HzRefined / freqRes;
      const k2 = Math.round(kappa2);
      if (k2 >= 1 && k2 <= halfSize) {         // the second tone is present, in band
        const ORDER = Math.max(5, harmonicCount + 1);
        const ta = new Int32Array((2 * ORDER + 1) * (2 * ORDER + 1));
        const tb = new Int32Array(ta.length);
        const tk = new Int32Array(ta.length);
        let cap = 0;
        for (let a = -ORDER; a <= ORDER; a++) {
          for (let b = -ORDER; b <= ORDER; b++) {
            if (b === 0 || Math.abs(a) + Math.abs(b) > ORDER) continue;
            const kp = a * kFractional + b * kappa2;
            if (kp < 1.0 || kp > halfSize) continue;
            tk[cap] = Math.round(kp);
            ta[cap] = a;
            tb[cap] = b;
            cap++;
          }
        }
        outResult.imdProductA = ta.slice(0, cap);
        outResult.imdProductB = tb.slice(0, cap);
        outResult.imdProductBin = tk.slice(0, cap);
      }
    }

    const intFundBinRounded = Math.max(1, Math.round(kFractional));
    // --- The coherent family for the region-constant de-rotation -------------
    // Members carry their (a, b) COEFFICIENTS and their position, built ONCE per
    // call, so every frame (and every pool worker, which receives this unchanged
    // in sharedParams) re-derives the same phasors from its own Φ1/Φ2. Members:
    // the harmonic ladder h·κ (h = 0 covers DC and the deep bass side with the
    // identity phasor) merged position-sorted with the published product grid.
    const f2Kappa = (outResult.imdProductBin.length > 0)
      ? outResult.fundamental2HzRefined / freqRes : NaN;
    let famCount = 0;
    let famA = null, famB = null, famPos = null;
    if (coherentAveraging && kFractional > 0.0) {
      // The divisor is FRACTIONAL: the ladder stops at the last harmonic that
      // still lands inside the half spectrum.
      const hMaxT = Math.trunc(Math.min(halfSize, halfSize / kFractional));
      const pa = outResult.imdProductA;
      const pb = outResult.imdProductB;
      const nGrid = (f2Kappa > 0.0) ? pa.length : 0;
      const spA = new Int32Array(nGrid);
      const spB = new Int32Array(nGrid);
      const spP = new Float64Array(nGrid);
      let nP = 0;
      for (let p = 0; p < nGrid; p++) {
        const posP = pa[p] * kFractional + pb[p] * f2Kappa;
        if (posP < 1.0 || posP > halfSize - 1) continue;
        spA[nP] = pa[p];
        spB[nP] = pb[p];
        spP[nP] = posP;
        nP++;
      }
      for (let i = 1; i < nP; i++) {                   // position sort, tiny n
        const ia = spA[i], ib = spB[i], ip = spP[i];
        let j = i - 1;
        while (j >= 0 && spP[j] > ip) {
          spA[j + 1] = spA[j];
          spB[j + 1] = spB[j];
          spP[j + 1] = spP[j];
          j--;
        }
        spA[j + 1] = ia;
        spB[j + 1] = ib;
        spP[j + 1] = ip;
      }
      famA = new Int32Array(hMaxT + 1 + nP);
      famB = new Int32Array(famA.length);
      famPos = new Float64Array(famA.length);
      let pi2 = 0;
      for (let h = 0; h <= hMaxT; h++) {
        const hp = h * kFractional;
        while (pi2 < nP && spP[pi2] <= hp) {
          famA[famCount] = spA[pi2];
          famB[famCount] = spB[pi2];
          famPos[famCount] = spP[pi2];
          famCount++;
          pi2++;
        }
        famA[famCount] = h;
        famB[famCount] = 0;
        famPos[famCount] = hp;
        famCount++;
      }
      while (pi2 < nP) {
        famA[famCount] = spA[pi2];
        famB[famCount] = spB[pi2];
        famPos[famCount] = spP[pi2];
        famCount++;
        pi2++;
      }
    }

    // The two transforms above ARE accumulation frames 0 and 1: frame 0 is windowed from
    // sample 0 and frame 1 from sample `step`, with this same window - bit for bit what
    // accumulatePartial computes for f = 0 and f = 1. Keep them so the accumulation on THIS
    // analyzer reads them instead of transforming the same samples a second time. They stay
    // OUT of the shared params below, which are broadcast to the pool: these are two full
    // frame spectra, and cloning them per worker would cost more than the transform they save.
    this._preludeFrames = { samples, step, fftSize, window, re: [f0Re, s1Re], im: [f0Im, s1Im] };

    // Everything the per-frame derotation (accumulatePartial) and the finalize
    // stats path need. Plain data only - safe to structured-clone to a worker.
    return {
      samplesLength: samples.length,
      sampleRate, fftSize, harmonicCount, windowType, overlap,
      coherentAveraging, expectedFundHz,
      step, frameCount, freqRes, halfSize, window, cohGain, nenbw,
      kFractional, intFundBinRounded,
      f2Kappa, famCount, famA, famB, famPos,
      fundSearchMinBin, fundSearchMaxBin,
    };
  }

  /**
   * PART 2 (parallelisable) of the split analyze path: accumulates the frames in
   * the contiguous range [frameStart, frameEnd) into a partial sum, using the
   * EXACT same per-frame constant-phase derotation as the serial loop and the
   * GLOBAL frame index `f` (so `base = f·step` and every derotation phase match).
   *
   * Returns its OWN freshly allocated accumulators - no shared mutable state - so
   * it can run on a pool worker against a broadcast `sharedParams`. The merge of
   * the per-range partials (mergePartials) is bit-identical to the serial total
   * because complex / power summation is associative.
   *
   * @param {object} sp the sharedParams returned by prelude (broadcast unchanged).
   * @returns {{sumRe:Float64Array, sumIm:Float64Array, acceptedFrames:number}}
   *          For incoherent averaging sumRe carries the power sum and sumIm is 0.
   */
  accumulatePartial(samples, frameStart, frameEnd, sp) {
    const st = this.stageMs;
    const t0 = st ? performance.now() : 0;
    // What the frame transforms had cost before this call: the de-rotation is what is left of
    // this pass once the frames it had to transform itself are taken back out (a frame the
    // prelude already transformed costs nothing here, which is the point of holding them).
    const frameMs0 = st ? st.frameMs : 0;
    const { fftSize, halfSize, step, window, windowType, coherentAveraging,
            kFractional, intFundBinRounded,
            f2Kappa, famCount, famA, famB, famPos } = sp;
    const sumRe = new Float64Array(fftSize);   // complex Re  OR  power (incoherent)
    const sumIm = new Float64Array(fftSize);   // complex Im  OR  unused (incoherent)
    // The transform target for a frame this analyzer has not already transformed; a frame the
    // prelude left behind is read where it lies (_preludeFrameFor). Every branch below only
    // READS the frame pair - it writes the sums - so standing the prelude's own arrays in for
    // this scratch pair cannot disturb them.
    const scratchRe = new Float64Array(fftSize);
    const scratchIm = new Float64Array(fftSize);
    // Region-constant de-rotation. A windowed tone's lobe - the peak and its
    // off-centre bins together - advances by ONE common phase per frame step (the
    // tone's own frequency times the step), so a per-bin ramp mis-rotates every
    // off-centre lobe bin and the overlapped frame sum carves the lobe into a comb;
    // a constant phasor per lobe keeps it intact under every window. The regions
    // tile the spectrum at the MIDPOINTS between the coherent family's members,
    // each member rotated by its derived phase a·Φ1 + b·Φ2 - so no edge is ever
    // measured and no environmental line can earn a region of its own. The upper
    // half of the full-length transform carries the NEGATIVE frequencies: a bin
    // there belongs to the member at |kf| and rotates by the CONJUGATE phasor.
    // RECT exception: on-bin content occupies single bins (no lobe to hold
    // together) while the 1/Δf skirt crosses every region midpoint, which is where
    // a piecewise rotation prints - so RECT keeps the per-bin time-shift ramp with
    // the SIGNED bin index; tapered windows take the region-constant family.
    const nFam = coherentAveraging ? famCount : 0;
    const famC = nFam > 0 ? new Float64Array(nFam) : null;
    const famS = nFam > 0 ? new Float64Array(nFam) : null;
    const rectWin = windowType === 'RECT';
    const rampScale = (kFractional > 0.0 && intFundBinRounded > 0)
      ? kFractional / intFundBinRounded : 1.0;
    let acceptedFrames = 0;
    for (let f = frameStart; f < frameEnd; f++) {
      const base = f * step;
      const held = this._preludeFrameFor(samples, base, sp);
      const frameRe = held ? held.re : scratchRe;
      const frameIm = held ? held.im : scratchIm;
      if (!held) this._frameFft(samples, base, fftSize, window, scratchRe, scratchIm);
      if (coherentAveraging && rectWin) {
        const thetaF = -2.0 * Math.PI * base * rampScale / fftSize;
        const stepRe = Math.cos(thetaF), stepIm = Math.sin(thetaF);
        // Two segments, each seeded on its own signed bin index: [0, halfSize] and
        // [halfSize+1, fftSize) where the signed index is k - fftSize.
        let seg = 0;
        while (seg < fftSize) {
          const segEnd = (seg <= halfSize) ? halfSize + 1 : fftSize;
          const kfSeed = (seg <= halfSize) ? seg : seg - fftSize;
          const ang = ieeeRemainder(thetaF * kfSeed, 2.0 * Math.PI);
          let phRe = Math.cos(ang), phIm = Math.sin(ang);
          for (let k = seg; k < segEnd; k++) {
            sumRe[k] += frameRe[k] * phRe - frameIm[k] * phIm;
            sumIm[k] += frameRe[k] * phIm + frameIm[k] * phRe;
            const nextRe = phRe * stepRe - phIm * stepIm;
            phIm = phRe * stepIm + phIm * stepRe;
            phRe = nextRe;
          }
          seg = segEnd;
        }
      } else if (coherentAveraging && nFam > 0) {
        const phi1 = -2.0 * Math.PI * ieeeRemainder(base * kFractional, fftSize) / fftSize;
        const phi2 = (f2Kappa > 0.0)
          ? -2.0 * Math.PI * ieeeRemainder(base * f2Kappa, fftSize) / fftSize : 0.0;
        for (let i = 0; i < nFam; i++) {
          const ang = ieeeRemainder(famA[i] * phi1 + famB[i] * phi2, 2.0 * Math.PI);
          famC[i] = Math.cos(ang);
          famS[i] = Math.sin(ang);
        }
        // Lower half: |kf| = k ascends, the member cursor advances upward.
        let c = 0;
        for (let k = 0; k <= halfSize; k++) {
          while (c < nFam - 1 && k > 0.5 * (famPos[c] + famPos[c + 1])) c++;
          sumRe[k] += frameRe[k] * famC[c] - frameIm[k] * famS[c];
          sumIm[k] += frameRe[k] * famS[c] + frameIm[k] * famC[c];
        }
        // Upper half: |kf| = fftSize - k DESCENDS as k ascends, so its cursor walks
        // DOWNWARD and the phasor is applied CONJUGATED.
        c = nFam - 1;
        for (let k = halfSize + 1; k < fftSize; k++) {
          while (c > 0 && fftSize - k <= 0.5 * (famPos[c - 1] + famPos[c])) c--;
          sumRe[k] += frameRe[k] * famC[c] + frameIm[k] * famS[c];
          sumIm[k] += -frameRe[k] * famS[c] + frameIm[k] * famC[c];
        }
      } else if (coherentAveraging) {
        // No pinned fundamental: nothing coherent to align - add the frame straight.
        for (let k = 0; k < fftSize; k++) {
          sumRe[k] += frameRe[k];
          sumIm[k] += frameIm[k];
        }
      } else {
        for (let k = 0; k < fftSize; k++) {
          sumRe[k] += frameRe[k] * frameRe[k] + frameIm[k] * frameIm[k];
        }
      }
      acceptedFrames++;
    }
    if (st) st.derotateMs += (performance.now() - t0) - (st.frameMs - frameMs0);
    return { sumRe, sumIm, acceptedFrames };
  }

  /**
   * Merges per-range partials (from accumulatePartial) into a single total, in
   * ascending frame-range order. The Float64Array element-wise sums are added in
   * the SAME left-to-right order the serial loop would visit them, so for one
   * range the result is bit-identical and for many ranges it differs only by the
   * (associative) regrouping of the cross-range sum. Returns a partial-shaped
   * total ({ sumRe, sumIm, acceptedFrames }) for finalize().
   *
   * @param {Array<{sumRe:Float64Array,sumIm:Float64Array,acceptedFrames:number}>}
   *        partials ordered by ascending frameStart.
   * @param {object} sp the sharedParams (for fftSize + coherentAveraging).
   */
  mergePartials(partials, sp) {
    if (partials.length === 1) return partials[0];
    const n = sp.fftSize;
    const coherent = sp.coherentAveraging;
    const sumRe = new Float64Array(n);
    const sumIm = new Float64Array(n);
    let acceptedFrames = 0;
    for (const part of partials) {
      const pRe = part.sumRe, pIm = part.sumIm;
      for (let k = 0; k < n; k++) sumRe[k] += pRe[k];
      if (coherent) for (let k = 0; k < n; k++) sumIm[k] += pIm[k];
      acceptedFrames += part.acceptedFrames;
    }
    return { sumRe, sumIm, acceptedFrames };
  }

  /**
   * PART 3 (single-threaded) of the split analyze path: from the merged partial
   * sum + the shared params, recomputes the single-sided spectrum, fundamental,
   * harmonics, THD / SNR / SINAD / THD+N / noise-floor and writes the fully
   * populated FftResult. Identical to the serial analyze tail.
   *
   * @param {{sumRe:Float64Array,sumIm:Float64Array,acceptedFrames:number}} merged
   * @param {object} sp the sharedParams from prelude.
   * @returns {FftResult} `outResult`, populated.
   */
  finalize(merged, sp, snrFreqMin, snrFreqMax, fundRefDbFs, outResult) {
    const statsT0 = this.stageMs ? performance.now() : 0;
    const { sampleRate, fftSize, harmonicCount, windowType, overlap, step,
            coherentAveraging, expectedFundHz,
            freqRes, halfSize, cohGain, nenbw, kFractional,
            fundSearchMinBin, fundSearchMaxBin } = sp;
    const sumRe = merged.sumRe;
    const sumIm = merged.sumIm;
    const fc = merged.acceptedFrames;
    outResult.frameCount = fc;

    // --- Single-sided amplitude & phase spectrum -----------------------------
    const normFactor = 1.0 / (fftSize * cohGain);
    const avgRe = outResult.re;
    const avgIm = outResult.im;
    this._scratchAmplLinear = this._scratchOrAlloc(this._scratchAmplLinear, halfSize + 1);
    const amplLinear = this._scratchAmplLinear;
    const amplDbFs = outResult.amplitudeDbFs;
    const phaseDeg = outResult.phaseDeg;
    for (let k = 0; k <= halfSize; k++) {
      let mag;
      if (coherentAveraging) {
        avgRe[k] = sumRe[k] / fc;
        avgIm[k] = sumIm[k] / fc;
        mag = Math.sqrt(avgRe[k] * avgRe[k] + avgIm[k] * avgIm[k]);
        phaseDeg[k] = radToDeg(Math.atan2(avgIm[k], avgRe[k]));
      } else {
        mag = Math.sqrt(sumRe[k] / fc);
        avgRe[k] = mag;   // store magnitude in re for export
        avgIm[k] = 0.0;
        phaseDeg[k] = 0.0;
      }
      amplLinear[k] = (k === 0 || k === halfSize)
        ? mag * normFactor
        : mag * normFactor * 2.0;
      amplDbFs[k] = amplLinear[k] > 1e-15 ? 20.0 * Math.log10(amplLinear[k]) : -300.0;
    }

    // --- Fundamental (max bin, skip DC + ULF) --------------------------------
    let fundBin;
    if (!Number.isNaN(expectedFundHz) && expectedFundHz > 0.0) {
      const expectedBin = Math.round(expectedFundHz * fftSize / sampleRate);
      const kLo = Math.max(1, expectedBin - 10);
      const kHi = Math.min(halfSize, expectedBin + 10);
      fundBin = kLo;
      for (let k = kLo + 1; k <= kHi; k++) if (amplLinear[k] > amplLinear[fundBin]) fundBin = k;
    } else {
      fundBin = fundSearchMinBin;
      for (let k = fundSearchMinBin + 1; k <= fundSearchMaxBin; k++) {
        if (amplLinear[k] > amplLinear[fundBin]) fundBin = k;
      }
    }
    const fundHz = fundBin * freqRes;
    const fundHzRefined = kFractional * freqRes;
    const fundDbFs = amplDbFs[fundBin];
    const fundLinear = amplLinear[fundBin];

    // refLin: the fundamental amplitude used as the denominator for every ratio
    // (THD / SNR / THD+N / harmonic %). When fundRefDbFs is set, use the user's
    // true fundamental (already dBFS) so an external H1 notch can't poison ratios.
    let fundTrueDbFs = NaN;
    let refLin;
    if (Number.isNaN(fundRefDbFs)) {
      refLin = fundLinear;
    } else {
      fundTrueDbFs = fundRefDbFs;
      refLin = Math.pow(10.0, fundTrueDbFs / 20.0);
    }

    // --- Harmonics -----------------------------------------------------------
    const hBins = outResult.harmonicBins;
    const hHz = outResult.harmonicHz;
    const hDbFs = outResult.harmonicDbFs;
    const hLinear = new Float64Array(harmonicCount);
    const hPct = outResult.harmonicPct;
    detectHarmonics(kFractional, fundHzRefined, halfSize, amplLinear,
      refLin, harmonicCount, hBins, hHz, hDbFs, hLinear, hPct);

    // --- Spectrum-only fast-path ---------------------------------------------
    if (this._spectrumOnly) {
      outResult.fftSize = fftSize;
      outResult.sampleRate = sampleRate;
      outResult.frameCount = fc;
      outResult.freqResolution = freqRes;
      outResult.windowType = windowType;
      outResult.overlap = overlap;
      outResult.stepSamples = step;
      outResult.fundamentalBin = fundBin;
      outResult.fundamentalHz = fundHz;
      outResult.fundamentalHzRefined = fundHzRefined;
      outResult.fundamentalDbFs = fundDbFs;
      outResult.fundamentalLinear = fundLinear;
      outResult.harmonicCount = harmonicCount;
      outResult.snrFreqMin = snrFreqMin;
      outResult.snrFreqMax = snrFreqMax;
      outResult.coherentAveraging = coherentAveraging;
      outResult.fundamentalTrueDbFs = fundTrueDbFs;
      outResult.thdPct = 0.0;
      outResult.thdDb = -300.0;
      outResult.thdNDb = 300.0;
      outResult.snrDb = 300.0;
      outResult.sinadDb = 300.0;
      outResult.awThdNDb = 300.0;
      outResult.enobSinadDb = 300.0;
      outResult.noisePower = 0.0;
      outResult.windowNenbwBins = nenbw;
      outResult.awNoisePower = 0.0;
      outResult.avgNoiseFloorDbFs = fundDbFs - 300.0;
      outResult.fundamentalDynExclusionHz = 0.0;
      outResult.preCorrectionPeaks = null;
      outResult.captureRawPeaks();
      this._stageStatsDone(statsT0);
      return outResult;
    }

    const snrLo = snrFreqMin > 0.0 ? snrFreqMin : 0.0;
    const snrHi = snrFreqMax > 0.0 ? snrFreqMax : Number.MAX_VALUE;

    // --- THD - H2..H9 (max 8 harmonics) within dist range --------------------
    let awHarmPowerSum = 0.0;
    let harmPowerSum = 0.0;
    for (let h = 0; h < Math.min(harmonicCount, 8); h++) {
      if (hBins[h] > 0) {
        const harmFreq = hHz[h];
        if (harmFreq >= snrLo && harmFreq <= snrHi) {
          const a = hLinear[h];
          harmPowerSum += a * a;
          awHarmPowerSum += a * a * this._aWeightPower(harmFreq);
        }
      }
    }
    const thdPct = refLin > 0 ? Math.sqrt(harmPowerSum) / refLin * 100.0 : 0.0;
    const thdDb = thdPct > 0 ? 20.0 * Math.log10(thdPct / 100.0) : -300.0;

    // --- Signal-bin mask -----------------------------------------------------
    const EXCL_BINS = 4;
    const isSignalBin = this._buildSignalBinMask(halfSize, fundBin, hBins, harmonicCount, EXCL_BINS);

    // --- Noise floor + dynamic mask extension --------------------------------
    const nf = this._computeNoiseFloorAndExtendSignalMask(
      amplLinear, isSignalBin, halfSize, freqRes, snrLo, snrHi, fundBin, EXCL_BINS,
      skirtLookAheadBins(fftSize, minHopSamples(overlap, fftSize)));
    const medianNoisePow = nf.medianNoisePow;
    const dynWidthBins = nf.dynWidthBins;

    // --- SNR - integrated noise over the measurement band --------------------
    const awTable = this._aWeightTable(halfSize, freqRes);
    let noisePower = 0.0;
    let awNoisePower = 0.0;
    let bandBins = 0, countedBins = 0;
    for (let k = 1; k <= halfSize; k++) {
      const freq = k * freqRes;
      if (freq >= snrLo && freq <= snrHi) {
        bandBins++;
        if (!isSignalBin[k]) { const p = amplLinear[k] * amplLinear[k]; noisePower += p; awNoisePower += p * awTable[k]; countedBins++; }
      }
    }
    // Excluded zones (fundamental skirt, harmonics) held noise too - estimate it as the surrounding floor by rescaling the
    // counted sum to the full band width, instead of dropping those slots (flattered SNR by the excluded fraction; ~0.8 dB in QA40x app).
    if (countedBins > 0) { const rescale = bandBins / countedBins; noisePower *= rescale; awNoisePower *= rescale; }
    // A windowed periodogram bin holds NENBW bin-widths of broadband power (Hann 1.5); summing noise bins as-is overstates
    // the band noise by that factor - divide it back out. Coherent lines stay untouched (already cohGain-normalized).
    noisePower /= nenbw;
    awNoisePower /= nenbw;
    const snrDb = noisePower <= 0 ? 300.0 : 10.0 * Math.log10((refLin * refLin) / noisePower);

    // SINAD: signal RMS² / (noise + distortion) - the integrated measurement-band
    // noise plus the in-band harmonic power, UNWEIGHTED (the MT-003 definition;
    // THD+N below is its reciprocal and ENOB derives from it - none of them
    // carries a weighting curve).
    const sinadDenom = noisePower + harmPowerSum;
    const sinadDb = sinadDenom > 0 ? 10.0 * Math.log10((refLin * refLin) / sinadDenom) : 300.0;
    // A-weighted noise+distortion for the N+D readout ONLY - the one audibility
    // figure (IEC 61672 supplies the curve; the weighting is this readout's own
    // choice). Negative dB relative to the signal.
    const awDenom = awNoisePower + awHarmPowerSum;
    const awThdNDb = (awDenom > 0 && refLin > 0)
      ? 10.0 * Math.log10(awDenom / (refLin * refLin)) : 300.0;
    // ENOB basis: the SAME unweighted in-band denominator referred to FULL SCALE -
    // where the signal level cancels exactly, because the denominator is already
    // full-scale-relative power. No fundamental term appears, so neither the
    // notched bin level nor the manual-fundamental reference can skew the figure
    // under any de-embedding combination. Not the full Nyquist band: an
    // oversampled sigma-delta parks its shaped noise above the audio band by
    // design, and a full-band integral reads that as bits lost.
    const enobSinadDb = sinadDenom > 0 ? -10.0 * Math.log10(sinadDenom) : 300.0;
    const avgNoiseFloorDbFs = medianNoisePow > 0
      ? 20.0 * Math.log10(Math.sqrt(medianNoisePow))
      : fundDbFs - 300.0;
    // THD+N is the reciprocal of SINAD: (noise + distortion) / signal in dB, so
    // −sinadDb. Same band, same numerator definition, unweighted like SINAD.
    const thdNDb = -sinadDb;

    // --- Write scalars (arrays already alias outResult) ----------------------
    outResult.fftSize = fftSize;
    outResult.sampleRate = sampleRate;
    outResult.frameCount = fc;
    outResult.freqResolution = freqRes;
    outResult.windowType = windowType;
    outResult.overlap = overlap;
    outResult.stepSamples = step;
    outResult.fundamentalBin = fundBin;
    outResult.fundamentalHz = fundHz;
    outResult.fundamentalHzRefined = fundHzRefined;
    outResult.fundamentalDbFs = fundDbFs;
    outResult.fundamentalLinear = fundLinear;
    outResult.harmonicCount = harmonicCount;
    outResult.thdPct = thdPct;
    outResult.thdDb = thdDb;
    outResult.thdNDb = thdNDb;
    outResult.snrDb = snrDb;
    outResult.sinadDb = sinadDb;
    outResult.awThdNDb = awThdNDb;
    outResult.enobSinadDb = enobSinadDb;
    outResult.snrFreqMin = snrFreqMin;
    outResult.snrFreqMax = snrFreqMax;
    outResult.coherentAveraging = coherentAveraging;
    outResult.noisePower = noisePower;
    outResult.windowNenbwBins = nenbw;
    outResult.awNoisePower = awNoisePower;
    outResult.avgNoiseFloorDbFs = avgNoiseFloorDbFs;
    outResult.fundamentalTrueDbFs = fundTrueDbFs;
    outResult.fundamentalDynExclusionHz = dynWidthBins * freqRes;
    outResult.preCorrectionPeaks = null;
    outResult.captureRawPeaks();
    this._stageStatsDone(statsT0);
    return outResult;
  }

  /**
   * Full-integration refinement of the fundamental's fractional bin κ via a
   * single-bin Goertzel phase-slope fit over every clean-segment frame (a
   * (bestLen−1)·step baseline vs the coarse one-hop). Residuals are re-centred
   * on their circular mean so an arbitrary fundamental phase near ±π can't wrap
   * mid-segment. Returns the refined κ.
   */
  _refineKappaOverSegment(samples, window, fftSize, step, bestStart, bestLen, refIntBin, kCoarse) {
    const w = 2.0 * Math.PI * refIntBin / fftSize;
    const cw = Math.cos(w);
    const sw = Math.sin(w);
    const coeff = 2.0 * cw;
    const resid = new Float64Array(bestLen);
    let sumCos = 0.0, sumSin = 0.0;
    for (let f = bestStart; f < bestStart + bestLen; f++) {
      const base = f * step;
      let sp = 0.0, sp2 = 0.0;                          // Goertzel state at refIntBin
      for (let n = 0; n < fftSize; n++) {
        const s = samples[base + n] * window[n] + coeff * sp - sp2;
        sp2 = sp;
        sp = s;
      }
      const ph = Math.atan2(sp2 * sw, sp - sp2 * cw);
      const expected = 2.0 * Math.PI * kCoarse * base / fftSize;
      const r = ieeeRemainder(ph - expected, 2.0 * Math.PI);
      resid[f - bestStart] = r;
      sumCos += Math.cos(r);
      sumSin += Math.sin(r);
    }
    const meanAngle = Math.atan2(sumSin, sumCos);        // circular mean -> wrap-safe centre
    const meanBase = (bestStart + (bestStart + bestLen - 1)) * 0.5 * step;
    let sDbR = 0.0, sDb2 = 0.0;
    for (let f = bestStart; f < bestStart + bestLen; f++) {
      const rc = ieeeRemainder(resid[f - bestStart] - meanAngle, 2.0 * Math.PI);
      const db = f * step - meanBase;
      sDbR += db * rc;
      sDb2 += db * db;
    }
    if (!(sDb2 > 0.0)) return kCoarse;
    return kCoarse + (sDbR / sDb2) * fftSize / (2.0 * Math.PI);
  }

  /**
   * Recomputes the fundamental level, harmonic table, THD, THD+N, SNR and noise
   * statistics from the (possibly mutated) amplitudeDbFs / re / im in `r`. Use
   * after any post-processing that mutates the spectrum (frequency-response
   * de-embedding, ADC correction). fundamentalBin / harmonicBins stay unchanged
   * (smooth per-bin scaling does not move peaks). Mirrors analyze()'s stats path.
   */
  recomputeStats(r) {
    const halfSize = r.fftSize / 2;
    const freqRes = r.freqResolution;
    const snrLo = r.snrFreqMin > 0.0 ? r.snrFreqMin : 0.0;
    const snrHi = r.snrFreqMax > 0.0 ? r.snrFreqMax : Number.MAX_VALUE;

    // Derive properly-scaled linear amplitudes from amplitudeDbFs.
    this._scratchAmplLinear = this._scratchOrAlloc(this._scratchAmplLinear, halfSize + 1);
    const amplLinear = this._scratchAmplLinear;
    for (let k = 0; k <= halfSize; k++) {
      amplLinear[k] = r.amplitudeDbFs[k] > -290.0 ? Math.pow(10.0, r.amplitudeDbFs[k] / 20.0) : 0.0;
    }

    // --- Fundamental ---------------------------------------------------------
    const fundBin = r.fundamentalBin;
    const fundLinear = amplLinear[fundBin];
    const fundDbFs = r.amplitudeDbFs[fundBin];
    r.fundamentalLinear = fundLinear;
    r.fundamentalDbFs = fundDbFs;
    const refLin = Number.isNaN(r.fundamentalTrueDbFs)
      ? fundLinear
      : Math.pow(10.0, r.fundamentalTrueDbFs / 20.0);

    // --- Harmonic table (proportional-bin combination) -----------------------
    const harmonicCount = r.harmonicCount;
    const hLinear = new Float64Array(harmonicCount);
    for (let h = 0; h < harmonicCount; h++) {
      if (r.harmonicBins[h] <= 0) continue;
      const kH = freqRes > 0 ? r.harmonicHz[h] / freqRes : 0.0;
      const binMain = Math.round(kH);
      if (binMain < 1 || binMain > halfSize) {
        r.harmonicBins[h] = -1;
        r.harmonicDbFs[h] = -300.0;
        r.harmonicPct[h] = 0.0;
        hLinear[h] = 0.0;
        continue;
      }
      const offset = kH - binMain;
      const binAdj = (offset >= 0.0) ? Math.min(halfSize, binMain + 1) : Math.max(1, binMain - 1);
      const wgt = Math.abs(offset);
      const pMain = amplLinear[binMain] * amplLinear[binMain];
      const pAdj = amplLinear[binAdj] * amplLinear[binAdj];
      const aCombined = Math.sqrt((1.0 - wgt) * pMain + wgt * pAdj);
      r.harmonicBins[h] = binMain;
      r.harmonicDbFs[h] = aCombined > 1e-15 ? 20.0 * Math.log10(aCombined) : -300.0;
      r.harmonicPct[h] = refLin > 0 ? aCombined / refLin * 100.0 : 0.0;
      hLinear[h] = aCombined;
    }

    // --- THD (H2..H9 within SNR range) ---------------------------------------
    let awHarmPowerSum = 0.0;
    let harmPowerSum = 0.0;
    for (let h = 0; h < Math.min(harmonicCount, 8); h++) {
      const hb = r.harmonicBins[h];
      if (hb > 0) {
        const freq = r.harmonicHz[h];
        if (freq >= snrLo && freq <= snrHi) {
          const a = hLinear[h];
          harmPowerSum += a * a;
          awHarmPowerSum += a * a * this._aWeightPower(freq);
        }
      }
    }
    r.thdPct = refLin > 0 ? Math.sqrt(harmPowerSum) / refLin * 100.0 : 0.0;
    r.thdDb = r.thdPct > 0 ? 20.0 * Math.log10(r.thdPct / 100.0) : -300.0;

    // --- Signal-bin mask + noise floor ---------------------------------------
    const EXCL_BINS = 4;
    const isSignalBin = this._buildSignalBinMask(halfSize, fundBin, r.harmonicBins, harmonicCount, EXCL_BINS);
    const medianNoisePow = this._computeNoiseFloorAndExtendSignalMask(
      amplLinear, isSignalBin, halfSize, freqRes, snrLo, snrHi, fundBin, EXCL_BINS,
      skirtLookAheadBins(r.fftSize, r.overlap != null
        ? minHopSamples(r.overlap, r.fftSize) : r.stepSamples)).medianNoisePow;

    // --- SNR -----------------------------------------------------------------
    const awTable = this._aWeightTable(halfSize, freqRes);
    let noisePower = 0.0;
    let awNoisePower = 0.0;
    let bandBins = 0, countedBins = 0;
    for (let k = 1; k <= halfSize; k++) {
      const freq = k * freqRes;
      if (freq >= snrLo && freq <= snrHi) {
        bandBins++;
        if (!isSignalBin[k]) { const p = amplLinear[k] * amplLinear[k]; noisePower += p; awNoisePower += p * awTable[k]; countedBins++; }
      }
    }
    // Excluded-zone rescale + NENBW - same corrections as analyze(). NENBW read from the RESULT (stamped at analysis time) -
    // a recompute must match the spectrum it recomputes from, not the window selected by now.
    if (countedBins > 0) { const rescale = bandBins / countedBins; noisePower *= rescale; awNoisePower *= rescale; }
    if (r.windowNenbwBins > 0) { noisePower /= r.windowNenbwBins; awNoisePower /= r.windowNenbwBins; }
    r.noisePower = noisePower;
    r.snrDb = noisePower <= 0 ? 300.0 : 10.0 * Math.log10((refLin * refLin) / noisePower);
    // SINAD, UNWEIGHTED (MT-003) - see the analyze() pass; the A-curve exists only
    // in the N+D figure below.
    const sinadDenom = noisePower + harmPowerSum;
    r.sinadDb = sinadDenom > 0 ? 10.0 * Math.log10((refLin * refLin) / sinadDenom) : 300.0;
    const awDenom = awNoisePower + awHarmPowerSum;
    r.awThdNDb = (awDenom > 0 && refLin > 0)
      ? 10.0 * Math.log10(awDenom / (refLin * refLin)) : 300.0;
    // ENOB basis - the same unweighted in-band denominator referred to FULL SCALE,
    // where the signal level cancels exactly; see the analyze() pass. No
    // fundamental term, so no de-embedding combination can skew it.
    r.enobSinadDb = sinadDenom > 0 ? -10.0 * Math.log10(sinadDenom) : 300.0;
    r.avgNoiseFloorDbFs = medianNoisePow > 0
      ? 20.0 * Math.log10(Math.sqrt(medianNoisePow))
      : fundDbFs - 300.0;
    // THD+N is the reciprocal of SINAD: −sinadDb in dB.
    r.awNoisePower = awNoisePower;
    r.thdNDb = -r.sinadDb;
  }

  // ==========================================================================
  // Signal mask + noise floor
  // ==========================================================================

  /** IEC 61672 analog A-weighting as a POWER weight: R_A(f)^2 with the +2.00 dB 1 kHz normalization folded in (A(1 kHz)=0 dB). */
  _aWeightPower(freqHz) {
    const f2 = freqHz * freqHz;
    const ra = (AW_POLE4_HZ * AW_POLE4_HZ * f2 * f2)
      / ((f2 + AW_POLE1_HZ * AW_POLE1_HZ)
        * Math.sqrt((f2 + AW_POLE2_HZ * AW_POLE2_HZ) * (f2 + AW_POLE3_HZ * AW_POLE3_HZ))
        * (f2 + AW_POLE4_HZ * AW_POLE4_HZ));
    return ra * ra * AW_NORM_POWER;
  }

  /** Cached per-bin A-weight table (entry k = _aWeightPower(k*freqRes)); rebuilt only when the
   *  spectrum geometry (halfSize / freqRes) changes - the noise integrals consult it per bin each tick. */
  _aWeightTable(halfSize, freqRes) {
    if (this._cachedAweight == null || this._cachedAweight.length !== halfSize + 1
        || this._cachedAweightFreqRes !== freqRes) {
      const table = new Float64Array(halfSize + 1);
      for (let k = 0; k <= halfSize; k++) table[k] = this._aWeightPower(k * freqRes);
      this._cachedAweight = table;
      this._cachedAweightFreqRes = freqRes;
    }
    return this._cachedAweight;
  }

  /** Builds the "signal bin" mask: fundamental bin + all harmonic bins, each
   *  smeared by exclBins on either side. Reused scratch (re-zeroed each call). */
  _buildSignalBinMask(halfSize, fundBin, hBins, harmonicCount, exclBins) {
    let isSignalBin = (this._scratchSignalMask != null && this._scratchSignalMask.length === halfSize + 1)
      ? this._scratchSignalMask : new Uint8Array(halfSize + 1);
    this._scratchSignalMask = isSignalBin;
    isSignalBin.fill(0);
    for (let d = -exclBins; d <= exclBins; d++) {
      const bin = fundBin + d;
      if (bin >= 0 && bin <= halfSize) isSignalBin[bin] = 1;
    }
    for (let h = 0; h < harmonicCount; h++) {
      if (hBins[h] > 0) {
        for (let d = -exclBins; d <= exclBins; d++) {
          const bin = hBins[h] + d;
          if (bin >= 0 && bin <= halfSize) isSignalBin[bin] = 1;
        }
      }
    }
    return isSignalBin;
  }

  /** Computes the median non-signal-bin power inside the SNR band, runs the
   *  dynamic fundamental-exclusion walk against the global 10th-percentile floor,
   *  and applies the phase-noise exclusion zone within ±fundBin/2. Mutates
   *  `isSignalBin` in place. Returns { medianNoisePow, dynWidthBins }. */
  _computeNoiseFloorAndExtendSignalMask(amplLinear, isSignalBin, halfSize, freqRes, snrLo, snrHi, fundBin, exclBins, lookAheadBins) {
    if (this._scratchNoiseCand == null || this._scratchNoiseCand.length < halfSize) {
      this._scratchNoiseCand = new Float64Array(halfSize);
      this._scratchNoiseGlob = new Float64Array(halfSize);
    }
    const candidatePow = this._scratchNoiseCand;
    const globalPow = this._scratchNoiseGlob;
    let candidateCount = 0;
    let globalCount = 0;
    for (let k = 1; k <= halfSize; k++) {
      const freq = k * freqRes;
      if (!isSignalBin[k]) {
        const pow = amplLinear[k] * amplLinear[k];
        globalPow[globalCount++] = pow;
        if (freq >= snrLo && freq <= snrHi) candidatePow[candidateCount++] = pow;
      }
    }
    let medianNoisePow = candidateCount > 0
      ? selectKth(candidatePow, candidateCount, Math.trunc(candidateCount / 2)) : 0.0;
    // Walk threshold = the GLOBAL pool's MEDIAN (band-independent). NOT the 10th percentile: only ~10% of noise bins fall
    // below that, so L consecutive sub-threshold bins occur with ~0.1^L probability - at 87.5%+ overlap (L = 10/18) the
    // look-ahead walk never stopped and flagged the whole band as signal (N read "-", SNR pinned at the 300 dB sentinel).
    // Half of all noise bins sit below the median, so the walk ends within a few correlation lengths of true noise while
    // genuine skirt bins (well above the floor) keep it going exactly as before.
    const walkStopPow = globalCount > 0
      ? selectKth(globalPow, globalCount, Math.trunc(globalCount / 2)) : 0.0;

    let dynWidthBins = exclBins;
    if (walkStopPow > 0) {
      // Hard cap at ±fundBin/2 (the same "can never reach H2" bound the phase-noise pass uses) so no floor statistics can
      // ever extend the exclusion across the whole band (the rescaled noise sum needs surviving bins to estimate the zone).
      const walkLo = Math.max(1, fundBin - Math.trunc(fundBin / 2));
      const walkHi = Math.min(halfSize - 1, fundBin + Math.trunc(fundBin / 2));
      let dynLo = fundBin, dynHi = fundBin;
      // Ends only when lookAheadBins consecutive bins sit at/below the floor - a shorter sub-floor dip is the wobble of an
      // overlap-averaged floor (correlated frames), not the skirt's end, and is stepped over.
      while (dynLo > walkLo) {
        let stepTo = -1;
        const limit = Math.max(walkLo, dynLo - lookAheadBins);
        for (let k = dynLo - 1; k >= limit; k--) { if (amplLinear[k] * amplLinear[k] > walkStopPow) { stepTo = k; break; } }
        if (stepTo < 0) break;
        dynLo = stepTo;
      }
      while (dynHi < walkHi) {
        let stepTo = -1;
        const limit = Math.min(walkHi, dynHi + lookAheadBins);
        for (let k = dynHi + 1; k <= limit; k++) { if (amplLinear[k] * amplLinear[k] > walkStopPow) { stepTo = k; break; } }
        if (stepTo < 0) break;
        dynHi = stepTo;
      }
      dynWidthBins = Math.max(fundBin - dynLo, dynHi - fundBin);
      if (dynWidthBins > exclBins) {
        for (let k = dynLo; k <= dynHi; k++) isSignalBin[k] = 1;
        // Pass 2 - refined range-restricted median with the updated exclusion.
        candidateCount = 0;
        for (let k = 1; k <= halfSize; k++) {
          const freq = k * freqRes;
          if (!isSignalBin[k] && freq >= snrLo && freq <= snrHi) {
            candidatePow[candidateCount++] = amplLinear[k] * amplLinear[k];
          }
        }
        medianNoisePow = candidateCount > 0
          ? selectKth(candidatePow, candidateCount, Math.trunc(candidateCount / 2))
          : medianNoisePow;
      }
    }

    // Phase-noise exclusion: ±fundBin/2 around the fundamental, any bin above
    // the refined floor marked signal (capped so it can't reach H2 at 2·fundBin).
    if (medianNoisePow > 0) {
      const phaseLo = Math.max(1, fundBin - Math.trunc(fundBin / 2));
      const phaseHi = Math.min(halfSize, fundBin + Math.trunc(fundBin / 2));
      for (let k = phaseLo; k <= phaseHi; k++) {
        if (!isSignalBin[k] && amplLinear[k] * amplLinear[k] > medianNoisePow) isSignalBin[k] = 1;
      }
    }
    return { medianNoisePow, dynWidthBins };
  }

  // ==========================================================================
  // Window functions
  // ==========================================================================

  /** Builds a normalized analysis window of length N for the given WindowType
   *  token. All windows use (N−1) symmetric normalization except Flat-top
   *  (periodic N) and the HFT / Chebyshev families (peak-1). */
  buildWindow(N, type) {
    const w = new Float64Array(N);
    switch (type) {
      case 'RECT':
        w.fill(1.0);
        return w;

      case 'HANN':
        for (let n = 0; n < N; n++) w[n] = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * n / (N - 1)));
        return w;

      case 'BH4': {
        const a0 = 0.35875, a1 = 0.48829, a2 = 0.14128, a3 = 0.01168;
        for (let n = 0; n < N; n++) {
          const t = 2.0 * Math.PI * n / (N - 1);
          w[n] = a0 - a1 * Math.cos(t) + a2 * Math.cos(2 * t) - a3 * Math.cos(3 * t);
        }
        return w;
      }

      case 'BH7': {
        const a = [0.2712203606, 0.4334446123, 0.2180041184, 0.0657853433,
          0.0107618673, 0.0007700127, 0.0000136547];
        for (let n = 0; n < N; n++) {
          const t = 2.0 * Math.PI * n / (N - 1);
          let val = 0.0;
          for (let k = 0; k < a.length; k++) val += (k % 2 === 0 ? 1.0 : -1.0) * a[k] * Math.cos(k * t);
          w[n] = val;
        }
        return w;
      }

      case 'FT': {
        const a0 = 0.21557895, a1 = 0.41663158, a2 = 0.27726316, a3 = 0.08357895, a4 = 0.00694737;
        for (let n = 0; n < N; n++) {
          const t = 2.0 * Math.PI * n / N;
          w[n] = a0 - a1 * Math.cos(t) + a2 * Math.cos(2 * t) - a3 * Math.cos(3 * t) + a4 * Math.cos(4 * t);
        }
        return w;
      }

      case 'HFT144D': return buildHftWindow(N, HFT144D_COEFFS);
      case 'HFT248D': return buildHftWindow(N, HFT248D_COEFFS);
      case 'KB24': return buildKaiserWindow(N, 24.0);
      case 'KB38': return buildKaiserWindow(N, 38.0);
      case 'DC150': return buildChebyshevWindow(N, 150.0);
      case 'DC200': return buildChebyshevWindow(N, 200.0);
      case 'DC250': return buildChebyshevWindow(N, 250.0);
      case 'DC300': return buildChebyshevWindow(N, 300.0);

      default:
        throw new Error('Unknown window type: ' + type);
    }
  }
}

// HFT144D coefficients (Heinzel/Rüdiger/Schilling 2002) - flat-top, −144.1 dB.
const HFT144D_COEFFS = [1.0, -1.96760033, 1.57983607, -0.81123644,
  0.22583558, -0.02773848, 0.00090360];

// HFT248D coefficients (same paper) - flat-top, −248.4 dB.
const HFT248D_COEFFS = [1.0, -1.985844164102, 1.791176438506, -1.282075284005,
  0.667777530266, -0.240160796576, 0.056656381764,
  -0.008134974479, 0.000624544650, -0.000019808998, 0.000000132974];

/** Builds an HFT-family flat-top window: a PERIODIC cosine sum (signs in the
 *  coefficients), z = 2πn/N, normalized to peak 1. */
function buildHftWindow(N, coeffs) {
  const w = new Float64Array(N);
  let max = 0.0;
  for (let n = 0; n < N; n++) {
    const z = 2.0 * Math.PI * n / N;
    let val = 0.0;
    for (let k = 0; k < coeffs.length; k++) val += coeffs[k] * Math.cos(k * z);
    w[n] = val;
    if (val > max) max = val;
  }
  for (let n = 0; n < N; n++) w[n] /= max;
  return w;
}

/** Builds a symmetric Kaiser-Bessel window, w[n] = I₀(β·√(1−x²)) / I₀(β),
 *  x = 2n/(N−1) − 1. */
function buildKaiserWindow(N, beta) {
  const w = new Float64Array(N);
  const denom = besselI0(beta);
  for (let n = 0; n < N; n++) {
    const x = 2.0 * n / (N - 1) - 1.0;
    w[n] = besselI0(beta * Math.sqrt(Math.max(0.0, 1.0 - x * x))) / denom;
  }
  return w;
}

/** Modified Bessel function of the first kind, order 0 - power series Σ((x/2)ᵏ/k!)². */
function besselI0(x) {
  let sum = 1.0;
  let term = 1.0;
  const half = x / 2.0;
  for (let k = 1; k < 200; k++) {
    const f = half / k;
    term *= f * f;
    sum += term;
    if (term < sum * 1e-17) break;
  }
  return sum;
}

/** Builds a Dolph-Chebyshev window via FFT-based IDFT, then fftshift + peak-1. */
function buildChebyshevWindow(N, attenDb) {
  const beta = Math.pow(10.0, attenDb / 20.0);
  const x0 = Math.cosh(acosh(beta) / (N - 1));

  const wRe = new Float64Array(N);
  const wIm = new Float64Array(N);
  for (let k = 0; k <= N / 2; k++) {
    const val = chebyshevT(N - 1, x0 * Math.cos(Math.PI * k / N));
    wRe[k] = val;
    if (k > 0 && k < N - k) wRe[N - k] = val;
  }
  // IFFT(W) = FFT(W)/N for real W.
  fft(wRe, wIm);
  for (let i = 0; i < N; i++) wRe[i] /= N;

  // fftshift by N/2.
  const window = new Float64Array(N);
  const half = N / 2;
  for (let i = 0; i < N; i++) window[(i + half) % N] = wRe[i];

  let max = 0.0;
  for (let i = 0; i < N; i++) if (window[i] > max) max = window[i];
  if (max > 0.0) for (let i = 0; i < N; i++) window[i] /= max;
  return window;
}

// ============================================================================
// Pure-math helpers (port of MathUtil + private FftAnalyzer static helpers)
// ============================================================================

/** Locates each harmonic (H2..H{harmonicCount+1}) in the averaged spectrum: the
 *  Nth harmonic sits at the fractional bin N·kFractional, with energy power-
 *  weighted across the two straddling bins (window main-lobe leakage). Fills the
 *  pre-allocated out-arrays in place. */
function detectHarmonics(kFractional, fundHzRefined, halfSize, amplLinear,
                         refLin, harmonicCount, hBins, hHz, hDbFs, hLinear, hPct) {
  for (let h = 0; h < harmonicCount; h++) {
    const hNum = h + 2;
    const kH = kFractional * hNum;
    const binMain = Math.round(kH);
    if (binMain < 1 || binMain > halfSize) {
      hBins[h] = -1;
      hHz[h] = fundHzRefined * hNum;
      hDbFs[h] = -300.0;
      hLinear[h] = 0.0;
      hPct[h] = 0.0;
      continue;
    }
    const offset = kH - binMain;
    const binAdj = (offset >= 0.0) ? Math.min(halfSize, binMain + 1) : Math.max(1, binMain - 1);
    const w = Math.abs(offset);
    const pMain = amplLinear[binMain] * amplLinear[binMain];
    const pAdj = amplLinear[binAdj] * amplLinear[binAdj];
    const aCombined = Math.sqrt((1.0 - w) * pMain + w * pAdj);
    hBins[h] = binMain;
    hHz[h] = fundHzRefined * hNum;
    hLinear[h] = aCombined;
    hDbFs[h] = aCombined > 1e-15 ? 20.0 * Math.log10(aCombined) : -300.0;
    hPct[h] = refLin > 0 ? aCombined / refLin * 100.0 : 0.0;
  }
}

/** Returns the bin index in [kLo, kHi] with the largest squared-magnitude. */
function peakBin(re, im, kLo, kHi) {
  let best = kLo;
  let peakPow = re[kLo] * re[kLo] + im[kLo] * im[kLo];
  for (let k = kLo + 1; k <= kHi; k++) {
    const p = re[k] * re[k] + im[k] * im[k];
    if (p > peakPow) { peakPow = p; best = k; }
  }
  return best;
}

/** Parabolic interpolation on the log-power spectrum to refine a peak bin to a
 *  fractional bin index. δ = 0.5·(α − γ)/(α − 2β + γ). */
function parabolicBinInterp(re, im, peakBinIdx, fftSize) {
  const halfSize = fftSize / 2;
  const lo = Math.max(1, peakBinIdx - 1);
  const hi = Math.min(halfSize, peakBinIdx + 1);
  const pLo = Math.log(re[lo] * re[lo] + im[lo] * im[lo] + 1e-30);
  const pMid = Math.log(re[peakBinIdx] * re[peakBinIdx] + im[peakBinIdx] * im[peakBinIdx] + 1e-30);
  const pHi = Math.log(re[hi] * re[hi] + im[hi] * im[hi] + 1e-30);
  const denom = pLo - 2.0 * pMid + pHi;
  if (Math.abs(denom) < 1e-15) return peakBinIdx;
  return peakBinIdx + 0.5 * (pLo - pHi) / denom;
}

/** Chebyshev polynomial of the first kind T_n(x), finite for any real x. */
function chebyshevT(n, x) {
  if (x > 1.0) return Math.cosh(n * acosh(x));
  if (x < -1.0) return (n % 2 === 0 ? 1.0 : -1.0) * Math.cosh(n * acosh(-x));
  return Math.cos(n * Math.acos(x));
}

/** Inverse hyperbolic cosine: acosh(x) = ln(x + √(x²−1)), x ≥ 1. */
function acosh(x) {
  return Math.log(x + Math.sqrt(x * x - 1.0));
}

/** In-place Hoare quickselect: the k-th smallest of a[0..len), median-of-3 pivot. */
function selectKth(a, len, k) {
  let lo = 0, hi = len - 1;
  while (lo < hi) {
    const p1 = a[lo], p2 = a[(lo + hi) >>> 1], p3 = a[hi];
    const pivot = Math.max(Math.min(p1, p2), Math.min(Math.max(p1, p2), p3));
    let i = lo, j = hi;
    while (i <= j) {
      while (a[i] < pivot) i++;
      while (a[j] > pivot) j--;
      if (i <= j) {
        const t = a[i]; a[i] = a[j]; a[j] = t;
        i++; j--;
      }
    }
    if (k <= j) hi = j;
    else if (k >= i) lo = i;
    else return a[k];
  }
  return a[k];
}

/** Math.toDegrees. */
function radToDeg(r) { return r * (180.0 / Math.PI); }

/** Population count (number of set bits) - fftSize power-of-2 check. */
function popcount(x) {
  let c = 0;
  while (x) { x &= x - 1; c++; }
  return c;
}
