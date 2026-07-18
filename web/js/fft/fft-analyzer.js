/*
 * Phonalyser web — precision audio measurement workbench (browser port).
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
// PORTING NOTE — frame rejection. The desktop hard-codes `frameRejection =
// false` (the only shipping configuration: capture glitches are handled by the
// cross-tick gap recovery / SpectralDiscontinuityDetector in the worker, not by
// per-frame rejection). With it off, rejectionFeasible is always false, the
// R-invariant scan and the phase-coherence subtraction never run, no events are
// ever collected, and the "longest clean segment" is the whole frame range
// (bestStart = 0, bestLen = frameCount). This port reflects that shipping path
// directly rather than porting the dead branches.

import { fft } from '../dsp/fft.js';
import { FftResult } from './fft-result.js';

/** Lower edge (Hz) of the no-hint fundamental search: DC … this is ignored so
 *  window leakage from a residual DC offset can't win the global max. */
const FUND_SEARCH_MIN_HZ = 5.0;
/** Upper edge of the no-hint fundamental search as a fraction of Nyquist. */
const FUND_SEARCH_MAX_NYQUIST_FRACTION = 0.7;

/** Shared empty grid published for non-dual-tone (or non-de-rotated) ticks. */
const NO_IMD_PRODUCTS = new Int32Array(0);

/** Maps a FftOverlap enum token (or a numeric fraction) to its overlap fraction. */
const OVERLAP_FRACTION = {
  PCT_0: 0.0, PCT_50: 0.5, PCT_75: 0.75, PCT_87_5: 0.875, PCT_93_75: 0.9375,
};

/** @returns {number} overlap fraction for `overlap` (enum token or number). */
function overlapFraction(overlap) {
  if (typeof overlap === 'number') return overlap;
  const f = OVERLAP_FRACTION[overlap];
  return f === undefined ? 0.0 : f;
}

/** Equivalent noise bandwidth (bins) per WindowType token — verbatim from Java
 *  enums/WindowType.enbw(). Broadband noise measured through the FFT reads
 *  10·log10(ENBW) dB above its true level (the tone reads dead-on), so the
 *  generator's dither dBV readout adds that term to stay checkable against the
 *  FFT noise floor. */
const WINDOW_ENBW = {
  RECT: 1.0, HANN: 1.5, BH4: 2.0044, BH7: 2.6303, FT: 3.7702,
  HFT144D: 4.5386, HFT248D: 5.6512, KB24: 2.8013, KB38: 3.5072,
  DC150: 2.3660, DC200: 2.7259, DC250: 3.0435, DC300: 3.3310,
};

/** Equivalent noise bandwidth (bins) of `windowToken`, defaulting to 1.0 for an
 *  unknown token (WindowType.enbw()). */
export function enbwOf(windowToken) {
  const e = WINDOW_ENBW[windowToken];
  return e === undefined ? 1.0 : e;
}

export class FftAnalyzer {
  constructor() {
    // Cached window-function table — rebuilt only when (fftSize, windowType)
    // changes (analyze runs repeatedly with the same pair on the worker).
    this._cachedWindow = null;
    this._cachedWindowSize = 0;
    this._cachedWindowType = null;
    /** Coherent gain (mean) of the cached window. */
    this._cachedCohGain = 0;

    // Second-tone frequency hint (Hz); NaN disables it.
    this._secondToneHintHz = NaN;
    // Multi-tone flag for the next analyze (skips the single-sine glitch scan —
    // already always skipped here, but kept for the second-tone detect path).
    this._multiTone = false;
    // Spectrum-only fast-path: skip the per-tick THD / SNR / SINAD / noise sweeps.
    this._spectrumOnly = false;

    // Reusable scratch (Float64Array) — re-used across calls to kill per-tick alloc.
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
    for (let i = 0; i < N; i++) sum += this._cachedWindow[i];
    this._cachedCohGain = sum / N;
    return this._cachedWindow;
  }

  /** Returns `current` if length matches, else a fresh Float64Array. */
  _scratchOrAlloc(current, size) {
    return (current != null && current.length === size) ? current : new Float64Array(size);
  }

  /** Windows + FFTs the frame starting at local sample offset `localStart`,
   *  leaving re / im populated with the result. (No frame cache in the browser
   *  port — the desktop FrameFftCache key offset is the worker's concern.) */
  _frameFft(samples, localStart, fftSize, window, re, im) {
    for (let n = 0; n < fftSize; n++) {
      re[n] = samples[localStart + n] * window[n];
      im[n] = 0.0;
    }
    fft(re, im);
  }

  // ==========================================================================
  // Analysis
  // ==========================================================================

  /**
   * Runs coherent-averaged FFT analysis on a normalized mono signal.
   *
   * @param {Float64Array|Float32Array|number[]} samples signal samples (−1 … +1).
   * @param {number} sampleRate    sample rate in Hz.
   * @param {number} fftSize       FFT frame length — must be a power of 2.
   * @param {number} harmonicCount number of harmonics to evaluate (2nd … N-th).
   * @param {string|number} windowType WindowType enum token (e.g. "BH4").
   * @param {string|number} overlap    FftOverlap enum token (e.g. "PCT_0") or fraction.
   * @param {number} [snrFreqMin=0]  lower bound for SNR noise integration in Hz (0 = no limit).
   * @param {number} [snrFreqMax=0]  upper bound for SNR noise integration in Hz (0 = no limit).
   * @param {boolean} [coherentAveraging=true] true = complex (coherent) averaging; false = power.
   * @param {number} [fundRefDbFs=NaN] known fundamental level in dBFS; NaN = unknown.
   * @param {number} [expectedFundHz=NaN] when set, restrict the fundamental search
   *        to ±10 bins around this frequency (a deeply notched fundamental must
   *        not be lost to a louder spur); NaN = legacy loudest-bin behaviour.
   * @param {FftResult} [outResult] pool slot to write into; a fresh one is
   *        allocated when omitted.
   * @returns {FftResult} the populated result (`outResult` when supplied).
   */
  analyze(samples, sampleRate, fftSize, harmonicCount,
          windowType = 'HANN', overlap = 'PCT_0',
          snrFreqMin = 0.0, snrFreqMax = 0.0, coherentAveraging = true,
          fundRefDbFs = NaN, expectedFundHz = NaN, outResult = new FftResult()) {
    // Serial reference path — the single-thread coordinator: run the
    // (single-threaded) prelude, accumulate the WHOLE frame range in one
    // partial, then finalize. The parallel pool runs the same three steps with
    // the accumulation split into contiguous frame ranges across workers (see
    // accumulatePartial / mergePartials / finalize) — summation is associative,
    // so the merged total is bit-for-bit the serial accumulation.
    const sp = this.prelude(samples, sampleRate, fftSize, harmonicCount,
      windowType, overlap, coherentAveraging, expectedFundHz, outResult);
    const partial = this.accumulatePartial(samples, 0, sp.frameCount, sp);
    return this.finalize(partial, sp, snrFreqMin, snrFreqMax, fundRefDbFs, outResult);
  }

  /**
   * PART 1 (single-threaded) of the split analyze path: everything before the
   * frame-accumulation loop. Sizes `outResult`, builds the window, finds the
   * fundamental, computes the SHARED sub-bin fundamental kFractional (incl. the
   * long-baseline κ refit), the second-tone kappa2, and the dual-tone IMD-product
   * grid — and writes fundamental2HzRefined + the IMD arrays into `outResult`.
   *
   * The returned `sharedParams` carries every value the per-frame derotation +
   * the finalize stats path need. It MUST be broadcast unchanged to every pool
   * worker so each derotates with the identical kFractional / kappa2 / IMD grid
   * and the GLOBAL frame index — only then does splitting the frames stay exact.
   *
   * @returns {object} sharedParams (see field list at the end).
   */
  prelude(samples, sampleRate, fftSize, harmonicCount,
          windowType, overlap, coherentAveraging, expectedFundHz, outResult) {
    if (popcount(fftSize) !== 1) {
      throw new Error('fftSize must be a power of 2, got: ' + fftSize);
    }

    const step = Math.max(1, Math.round(fftSize * (1.0 - overlapFraction(overlap))));
    const frameCount = samples.length >= fftSize
      ? Math.trunc((samples.length - fftSize) / step) + 1
      : 0;
    if (frameCount === 0) {
      throw new Error('Signal too short for fftSize=' + fftSize +
        ': need at least ' + fftSize + ' samples, got ' + samples.length);
    }

    const freqRes = sampleRate / fftSize;
    const halfSize = fftSize / 2;

    // Size the slot's bin / harmonic arrays so the per-frame loops write into them.
    outResult.ensureArrays(halfSize + 1, harmonicCount);

    // --- Window + coherent gain (cached on (fftSize, windowType)) -------------
    const window = this._getCachedWindow(fftSize, windowType);
    const cohGain = this._cachedCohGain;

    // --- Pre-pass peak-bin estimate (frame 0) --------------------------------
    const f0Re = new Float64Array(fftSize);
    const f0Im = new Float64Array(fftSize);
    this._frameFft(samples, 0, fftSize, window, f0Re, f0Im);

    // Restrict the fundamental search to FUND_SEARCH_MIN_HZ … 0.7·Nyquist so DC
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
    const frameRe = new Float64Array(fftSize);
    const frameIm = new Float64Array(fftSize);
    const bestStart = 0;
    const bestLen = frameCount;

    // --- Re-estimate kFractional from the clean segment's first frame --------
    // segBaseSample == 0 here, so the segment's first frame IS the pre-pass
    // frame — alias f0 instead of re-FFT-ing.
    const s0Re = f0Re;
    const s0Im = f0Im;
    // Refine intFundBin within ±2 bins of the pre-pass estimate.
    let refIntBin = peakBin(s0Re, s0Im,
      Math.max(1, intFundBin - 2), Math.min(halfSize, intFundBin + 2));
    let kFractional;
    let s1Re = null;
    let s1Im = null;
    if (bestLen >= 2) {
      const estStep = step > 0 ? step : fftSize;
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
    outResult.fundamental2HzRefined = NaN;
    const SECOND_TONE_GUARD_BINS = 100;
    let effHint = this._secondToneHintHz;
    if (Number.isNaN(effHint) && this._multiTone && intFundBin > 0) {
      const fundAmp2 = s0Re[intFundBin] * s0Re[intFundBin] + s0Im[intFundBin] * s0Im[intFundBin];
      const thresh2 = fundAmp2 * 1e-4;                  // ≥ −40 dB of F1 → a real tone
      const loB = Math.max(3, Math.ceil(10.0 / freqRes));
      let bestAmp2 = 0.0;
      let bestK = -1;
      for (let k = loB; k < halfSize; k++) {
        if (Math.abs(k - intFundBin) <= SECOND_TONE_GUARD_BINS) continue;   // skip F1's lobe
        const a2 = s0Re[k] * s0Re[k] + s0Im[k] * s0Im[k];
        if (a2 > bestAmp2 && a2 >= thresh2
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
          const estStep = step > 0 ? step : fftSize;
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
    if (coherentAveraging && bestLen >= 4 && step > 0) {
      const kRefined = this._refineKappaOverSegment(samples, window, fftSize, step,
        bestStart, bestLen, refIntBin, kFractional);
      if (Math.abs(kRefined - kFractional) < 0.5) {     // sanity: reject a wild fit
        kFractional = kRefined;
      }
    }

    // --- Dual-tone: choose F2's lobe de-rotation (individual κ vs single-ref) -
    const F2_LOBE_HALF = 24;
    let f2Individual = false;
    let k2 = -1;
    let kappa2 = 0.0;
    if (coherentAveraging && bestLen >= 2 && !Number.isNaN(outResult.fundamental2HzRefined)
        && outResult.fundamental2HzRefined > 0.0 && kFractional > 0.0) {
      kappa2 = outResult.fundamental2HzRefined / freqRes;
      k2 = Math.round(kappa2);
      if (k2 >= 1 && k2 <= halfSize) {
        const h2 = Math.max(1, Math.round(kappa2 / kFractional));
        let saRe = 0.0, saIm = 0.0, sbRe = 0.0, sbIm = 0.0;
        for (let f = bestStart; f < bestStart + bestLen; f++) {
          const base = f * step;
          this._frameFft(samples, base, fftSize, window, frameRe, frameIm);
          const re = frameRe[k2], im = frameIm[k2];
          const phiA = -2.0 * Math.PI * base * kFractional * h2 / fftSize;  // h·Φ(F1)
          const phiB = -2.0 * Math.PI * base * kappa2 / fftSize;            // Φ(κ_F2)
          const ca = Math.cos(phiA), sa = Math.sin(phiA);
          const cb = Math.cos(phiB), sb = Math.sin(phiB);
          saRe += re * ca - im * sa; saIm += re * sa + im * ca;
          sbRe += re * cb - im * sb; sbIm += re * sb + im * cb;
        }
        f2Individual = Math.hypot(sbRe, sbIm) > Math.hypot(saRe, saIm);
      }
    }

    // --- Build the IMD-product grid (only when F2 rides its own κ) ------------
    const f2Kappa = kappa2;
    let nProd = 0;
    let prodA = null, prodB = null, imdIdx = null;
    outResult.imdProductA = NO_IMD_PRODUCTS;
    outResult.imdProductB = NO_IMD_PRODUCTS;
    outResult.imdProductBin = NO_IMD_PRODUCTS;
    if (f2Individual) {
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
      prodA = ta.slice(0, cap);
      prodB = tb.slice(0, cap);
      nProd = cap;
      outResult.imdProductA = prodA;
      outResult.imdProductB = prodB;
      outResult.imdProductBin = tk.slice(0, cap);
      imdIdx = new Int32Array(fftSize).fill(-1);
      for (let p = 0; p < cap; p++) {
        const lo2 = Math.max(1, tk[p] - F2_LOBE_HALF);
        const hi2 = Math.min(halfSize, tk[p] + F2_LOBE_HALF);
        for (let k = lo2; k <= hi2; k++) imdIdx[k] = p;
      }
    }

    const intFundBinRounded = Math.max(1, Math.round(kFractional));
    const hMax = coherentAveraging ? Math.max(1, Math.trunc(halfSize / intFundBinRounded)) : 0;

    // Everything the per-frame derotation (accumulatePartial) and the finalize
    // stats path need. Plain data only — safe to structured-clone to a worker.
    return {
      samplesLength: samples.length,
      sampleRate, fftSize, harmonicCount, windowType, overlap,
      coherentAveraging, expectedFundHz,
      step, frameCount, freqRes, halfSize, window, cohGain,
      kFractional, intFundBinRounded, hMax,
      f2Kappa, nProd, prodA, prodB, imdIdx,
      fundSearchMinBin, fundSearchMaxBin,
    };
  }

  /**
   * PART 2 (parallelisable) of the split analyze path: accumulates the frames in
   * the contiguous range [frameStart, frameEnd) into a partial sum, using the
   * EXACT same per-frame constant-phase derotation as the serial loop and the
   * GLOBAL frame index `f` (so `base = f·step` and every derotation phase match).
   *
   * Returns its OWN freshly allocated accumulators — no shared mutable state — so
   * it can run on a pool worker against a broadcast `sharedParams`. The merge of
   * the per-range partials (mergePartials) is bit-identical to the serial total
   * because complex / power summation is associative.
   *
   * @param {object} sp the sharedParams returned by prelude (broadcast unchanged).
   * @returns {{sumRe:Float64Array, sumIm:Float64Array, acceptedFrames:number}}
   *          For incoherent averaging sumRe carries the power sum and sumIm is 0.
   */
  accumulatePartial(samples, frameStart, frameEnd, sp) {
    const { fftSize, halfSize, step, window, coherentAveraging,
            kFractional, intFundBinRounded, hMax,
            f2Kappa, nProd, prodA, prodB, imdIdx } = sp;
    const sumRe = new Float64Array(fftSize);   // complex Re  OR  power (incoherent)
    const sumIm = new Float64Array(fftSize);   // complex Im  OR  unused (incoherent)
    const frameRe = new Float64Array(fftSize);
    const frameIm = new Float64Array(fftSize);
    // Per-lobe constant-phase de-rotation: each bin is snapped to its nearest
    // harmonic h = round(signed-freq-bin / k₀) and de-rotated by that lobe's
    // CONSTANT phase h·Φ — NOT a per-bin frequency ramp (which would comb the
    // leakage skirt).
    const hc = coherentAveraging ? new Float64Array(2 * hMax + 1) : null;
    const hs = coherentAveraging ? new Float64Array(2 * hMax + 1) : null;
    let acceptedFrames = 0;
    for (let f = frameStart; f < frameEnd; f++) {
      const base = f * step;
      this._frameFft(samples, base, fftSize, window, frameRe, frameIm);
      if (coherentAveraging) {
        // Φ = −2π·k_f·f·step/N — the fundamental's inter-frame phase advance;
        // harmonic lobe h gets h·Φ. Cache exp(j·h·Φ) for h = −hMax..hMax (negative
        // h is the conjugate: the real-spectrum image of harmonic h).
        const phi = -2.0 * Math.PI * (f * step) * kFractional / fftSize;
        const e1c = Math.cos(phi), e1s = Math.sin(phi);
        hc[hMax] = 1.0; hs[hMax] = 0.0;
        for (let h = 1; h <= hMax; h++) {
          hc[hMax + h] = hc[hMax + h - 1] * e1c - hs[hMax + h - 1] * e1s;
          hs[hMax + h] = hc[hMax + h - 1] * e1s + hs[hMax + h - 1] * e1c;
          hc[hMax - h] = hc[hMax + h];           // exp(−j·h·Φ) = conjugate
          hs[hMax - h] = -hs[hMax + h];
        }
        // IMD-grid de-rotation: each product lobe rides a·Φ(F1)+b·Φ(F2).
        const phi2 = (imdIdx != null) ? -2.0 * Math.PI * (f * step) * f2Kappa / fftSize : 0.0;
        const pcos = new Float64Array(nProd);
        const psin = new Float64Array(nProd);
        for (let p = 0; p < nProd; p++) {
          const ph = prodA[p] * phi + prodB[p] * phi2;   // a·Φ(F1) + b·Φ(F2)
          pcos[p] = Math.cos(ph);
          psin[p] = Math.sin(ph);
        }
        const k0 = intFundBinRounded;
        const half = halfSize;
        for (let k = 0; k < fftSize; k++) {
          let cr, ci;
          const p = (imdIdx != null) ? imdIdx[k] : -1;
          if (p >= 0) {
            cr = pcos[p]; ci = psin[p];                  // IMD product: a·Φ1+b·Φ2
          } else {
            const kf = (k <= half) ? k : k - fftSize;    // signed frequency bin
            let h = Math.round(kf / k0);                 // nearest harmonic lobe
            if (h > hMax) h = hMax; else if (h < -hMax) h = -hMax;
            cr = hc[h + hMax]; ci = hs[h + hMax];
          }
          const cRe = frameRe[k] * cr - frameIm[k] * ci;
          const cIm = frameRe[k] * ci + frameIm[k] * cr;
          sumRe[k] += cRe;
          sumIm[k] += cIm;
        }
      } else {
        for (let k = 0; k < fftSize; k++) {
          sumRe[k] += frameRe[k] * frameRe[k] + frameIm[k] * frameIm[k];
        }
      }
      acceptedFrames++;
    }
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
    const { sampleRate, fftSize, harmonicCount, windowType, overlap,
            coherentAveraging, expectedFundHz,
            freqRes, halfSize, cohGain, kFractional,
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
      outResult.noisePower = 0.0;
      outResult.awNoisePower = 0.0;
      outResult.avgNoiseFloorDbFs = fundDbFs - 300.0;
      outResult.fundamentalDynExclusionHz = 0.0;
      outResult.preCorrectionPeaks = null;
      outResult.captureRawPeaks();
      return outResult;
    }

    const snrLo = snrFreqMin > 0.0 ? snrFreqMin : 0.0;
    const snrHi = snrFreqMax > 0.0 ? snrFreqMax : Number.MAX_VALUE;

    // --- THD — H2..H9 (max 8 harmonics) within dist range --------------------
    let harmPowerSum = 0.0;
    for (let h = 0; h < Math.min(harmonicCount, 8); h++) {
      if (hBins[h] > 0) {
        const harmFreq = hHz[h];
        if (harmFreq >= snrLo && harmFreq <= snrHi) {
          const a = hLinear[h];
          harmPowerSum += a * a;
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
      amplLinear, isSignalBin, halfSize, freqRes, snrLo, snrHi, fundBin, EXCL_BINS);
    const medianNoisePow = nf.medianNoisePow;
    const dynWidthBins = nf.dynWidthBins;

    // --- SNR — integrated noise over the measurement band --------------------
    let noisePower = 0.0;
    for (let k = 1; k <= halfSize; k++) {
      const freq = k * freqRes;
      if (!isSignalBin[k] && freq >= snrLo && freq <= snrHi) {
        const pow = amplLinear[k] * amplLinear[k];
        noisePower += pow;
      }
    }
    const snrDb = noisePower <= 0 ? 300.0 : 10.0 * Math.log10((refLin * refLin) / noisePower);

    // SINAD: signal RMS² / (noise + in-band harmonic power) — basis for ENOB.
    const sinadDenom = noisePower + harmPowerSum;
    const sinadDb = sinadDenom > 0 ? 10.0 * Math.log10((refLin * refLin) / sinadDenom) : 300.0;
    const avgNoiseFloorDbFs = medianNoisePow > 0
      ? 20.0 * Math.log10(Math.sqrt(medianNoisePow))
      : fundDbFs - 300.0;
    // THD+N is the reciprocal of SINAD: −sinadDb.
    const thdNDb = -sinadDb;

    // --- Write scalars (arrays already alias outResult) ----------------------
    outResult.fftSize = fftSize;
    outResult.sampleRate = sampleRate;
    outResult.frameCount = fc;
    outResult.freqResolution = freqRes;
    outResult.windowType = windowType;
    outResult.overlap = overlap;
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
    outResult.snrFreqMin = snrFreqMin;
    outResult.snrFreqMax = snrFreqMax;
    outResult.coherentAveraging = coherentAveraging;
    outResult.noisePower = noisePower;
    outResult.awNoisePower = noisePower;
    outResult.avgNoiseFloorDbFs = avgNoiseFloorDbFs;
    outResult.fundamentalTrueDbFs = fundTrueDbFs;
    outResult.fundamentalDynExclusionHz = dynWidthBins * freqRes;
    outResult.preCorrectionPeaks = null;
    outResult.captureRawPeaks();
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
    const meanAngle = Math.atan2(sumSin, sumCos);        // circular mean → wrap-safe centre
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
    let harmPowerSum = 0.0;
    for (let h = 0; h < Math.min(harmonicCount, 8); h++) {
      const hb = r.harmonicBins[h];
      if (hb > 0) {
        const freq = r.harmonicHz[h];
        if (freq >= snrLo && freq <= snrHi) {
          const a = hLinear[h];
          harmPowerSum += a * a;
        }
      }
    }
    r.thdPct = refLin > 0 ? Math.sqrt(harmPowerSum) / refLin * 100.0 : 0.0;
    r.thdDb = r.thdPct > 0 ? 20.0 * Math.log10(r.thdPct / 100.0) : -300.0;

    // --- Signal-bin mask + noise floor ---------------------------------------
    const EXCL_BINS = 4;
    const isSignalBin = this._buildSignalBinMask(halfSize, fundBin, r.harmonicBins, harmonicCount, EXCL_BINS);
    const medianNoisePow = this._computeNoiseFloorAndExtendSignalMask(
      amplLinear, isSignalBin, halfSize, freqRes, snrLo, snrHi, fundBin, EXCL_BINS).medianNoisePow;

    // --- SNR -----------------------------------------------------------------
    let noisePower = 0.0;
    for (let k = 1; k <= halfSize; k++) {
      const freq = k * freqRes;
      if (!isSignalBin[k] && freq >= snrLo && freq <= snrHi) {
        const pow = amplLinear[k] * amplLinear[k];
        noisePower += pow;
      }
    }
    r.noisePower = noisePower;
    r.snrDb = noisePower <= 0 ? 300.0 : 10.0 * Math.log10((refLin * refLin) / noisePower);
    const sinadDenom = noisePower + harmPowerSum;
    r.sinadDb = sinadDenom > 0 ? 10.0 * Math.log10((refLin * refLin) / sinadDenom) : 300.0;
    r.avgNoiseFloorDbFs = medianNoisePow > 0
      ? 20.0 * Math.log10(Math.sqrt(medianNoisePow))
      : fundDbFs - 300.0;
    r.awNoisePower = noisePower;
    r.thdNDb = -r.sinadDb;
  }

  // ==========================================================================
  // Signal mask + noise floor
  // ==========================================================================

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
  _computeNoiseFloorAndExtendSignalMask(amplLinear, isSignalBin, halfSize, freqRes, snrLo, snrHi, fundBin, exclBins) {
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
    // 10th-percentile of the full spectrum — closer to true quantization floor.
    const globalMedianNoisePow = globalCount > 0
      ? selectKth(globalPow, globalCount, Math.trunc(globalCount / 10)) : 0.0;

    let dynWidthBins = exclBins;
    if (globalMedianNoisePow > 0) {
      let dynLo = fundBin, dynHi = fundBin;
      while (dynLo > 1 && amplLinear[dynLo - 1] * amplLinear[dynLo - 1] > globalMedianNoisePow) dynLo--;
      while (dynHi < halfSize - 1 && amplLinear[dynHi + 1] * amplLinear[dynHi + 1] > globalMedianNoisePow) dynHi++;
      dynWidthBins = Math.max(fundBin - dynLo, dynHi - fundBin);
      if (dynWidthBins > exclBins) {
        for (let k = dynLo; k <= dynHi; k++) isSignalBin[k] = 1;
        // Pass 2 — refined range-restricted median with the updated exclusion.
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

// HFT144D coefficients (Heinzel/Rüdiger/Schilling 2002) — flat-top, −144.1 dB.
const HFT144D_COEFFS = [1.0, -1.96760033, 1.57983607, -0.81123644,
  0.22583558, -0.02773848, 0.00090360];

// HFT248D coefficients (same paper) — flat-top, −248.4 dB.
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

/** Modified Bessel function of the first kind, order 0 — power series Σ((x/2)ᵏ/k!)². */
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

/** Java Math.IEEEremainder(x, y): the remainder nearest to zero (round-half-even). */
function ieeeRemainder(x, y) {
  const r = x - y * Math.round(x / y);
  // Math.round rounds .5 up; IEEEremainder rounds half to even. Correct the tie.
  const q = x / y;
  if (Math.abs(q - Math.trunc(q)) === 0.5) {
    const n = 2 * Math.round(q / 2);   // nearest even
    return x - y * n;
  }
  return r;
}

/** Math.toDegrees. */
function radToDeg(r) { return r * (180.0 / Math.PI); }

/** Population count (number of set bits) — fftSize power-of-2 check. */
function popcount(x) {
  let c = 0;
  while (x) { x &= x - 1; c++; }
  return c;
}
