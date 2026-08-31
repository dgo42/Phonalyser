/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Render-time frequency-response de-embed - a faithful port of
// org.edgo.audio.measure.dsp.FreqRespCalHelper: the per-frequency helpers that
// operate on an already-loaded FreqRespCalibration ({freqs, magLin, phaseRad},
// see io/frc.js loadFrc) - interpolate (log-frequency H(f) lookup), deEmbed
// (divide one complex phasor by H) and calResponseAt (the {magLin, phaseRad}
// accessor the DAC-predistortion path consumes) - and the per-bin CASCADE the
// FFT spectrum is corrected with:
// toneFrequencies, toneLobeSpans, cascadeInverse, applyInverseInPlace,
// gainDbAt, toneBins, plus capturePreCorrectionPeaks, which Java keeps in this
// same class.
//
// The loaded rows are filters in series, so ONE per-bin complex product holds
// them all and correcting a frame is one complex multiply per bin whatever the
// number of rows. The reciprocal is formed once, at build time, and (0, 0) is
// its own "leave this bin alone" mark - a value no finite response can produce.
//
// CRITICAL: this is purely a RENDER-TIME correction of the measured spectrum /
// sweep - it has NOTHING to do with the generator. The accumulator stays RAW;
// the .frc is subtracted on the way to the screen and the measurement table.

import { ToneLobeLift } from './tone-lobe-lift.js';

/** Shared per-tone lobe geometry (robust local floor + data-derived extent),
 *  mirroring the Java static LOBE - the manual-fundamental display uses the
 *  same class, so both agree where a tone ends and the noise begins. */
const LOBE = new ToneLobeLift();

/** Highest intermod order whose dual-tone products a tone-only row corrects,
 *  mirroring FreqRespCalHelper.MAX_IMD_ORDER (= ImdResult.MAX_ORDER = 5). */
const MAX_IMD_ORDER = 5;

/** Index of a tone's peak bin inside one toneLobeSpans row. */
const SPAN_PEAK = 0;
/** Index of the first bin of that tone's main lobe. */
const SPAN_LO = 1;
/** Index of the last bin of that lobe. */
const SPAN_HI = 2;
/** Bin index standing for "the frame does not carry this tone", or "its lobe
 *  could not be located" (peak outside the analysed bins). */
const ABSENT_BIN = -1;

/** Row of cascadeInverse's answer holding Re(1/G) per bin. */
const INV_RE = 0;
/** Row holding Im(1/G) per bin. */
const INV_IM = 1;

/** dB per decade of an AMPLITUDE ratio. */
const AMPLITUDE_DB_FACTOR = 20.0;
/** dB per decade of a POWER ratio - a squared magnitude is read in this domain,
 *  so no square root is taken to state a gain. */
const POWER_DB_FACTOR = 10.0;
/** Below this linear amplitude a bin's level is clamped instead of logarithmed. */
const MIN_LINEAR_AMPLITUDE = 1e-15;
/** The clamp value for a bin below MIN_LINEAR_AMPLITUDE. */
const FLOOR_DB = -300.0;

/**
 * Interpolates the calibration's H(f) at an arbitrary frequency. Uses
 * log-frequency as the interpolation variable, magnitude in dB, and phase via
 * complex unit-phasor interpolation to handle ±180° wraps at notches.
 *
 * @param {{freqs:Float64Array, magLin:Float64Array, phaseRad:Float64Array}} cal
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

/**
 * Divides a single complex phasor (re, im) by the calibration response
 * H = magLin·e^{j·phaseRad}, de-embedding BOTH magnitude and phase
 * (X/H = X·e^{−j·phaseRad}/magLin). A non-positive magLin (no cal at this
 * frequency) returns the phasor unchanged.
 *
 * @returns {[number, number]} [re, im] of X/H
 */
export function deEmbed(re, im, magLin, phaseRad) {
  if (!(magLin > 0.0)) return [re, im];
  const c = Math.cos(phaseRad), s = Math.sin(phaseRad);
  return [(re * c + im * s) / magLin, (im * c - re * s) / magLin];
}

/**
 * Convenience accessor returning H(freq) as {magLin, phaseRad} - the
 * `calResponseAt` callback shape the DAC-predistortion and FFT-compensation
 * paths inject. Returns unity (no correction) when `cal` is null.
 *
 * @param {?{freqs:Float64Array, magLin:Float64Array, phaseRad:Float64Array}} cal
 * @param {number} freq
 * @returns {{magLin:number, phaseRad:number}}
 */
export function calResponseAt(cal, freq) {
  if (cal == null) return { magLin: 1.0, phaseRad: 0.0 };
  const [magLin, phaseRad] = interpolate(cal, freq);
  return { magLin, phaseRad };
}

/**
 * Every tone a tone-only calibration row corrects on `r`, in the order the
 * shared lobe bins are claimed: the fundamental (sub-bin refined where the
 * refinement is finite and positive, else the fundamental bin's centre), each
 * harmonic the frame located, the second fundamental of a dual tone and its
 * intermod products of orders 2 to MAX_IMD_ORDER.
 *
 * A position the frame does not carry keeps its SLOT and holds 0 - the list is
 * never compacted, so a later frame's tone set and lobe spans line up with an
 * earlier one's slot for slot and can be compared.
 *
 * The dual-tone products are the CCIF/DIN set the IMD analyser measures:
 *   d2L = f2 - f1,               d2H = f2 + f1
 *   dnL = (n-1)*f1 - (n-2)*f2,   dnH = (n-1)*f2 - (n-2)*f1   (n >= 3)
 *
 * @param {import('../fft/fft-result.js').FftResult} r
 * @returns {Float64Array} one frequency per slot, 0 for an absent tone
 */
export function toneFrequencies(r) {
  const harmonics = Math.max(0, r.harmonicCount);
  const tones = new Float64Array(2 + harmonics + 2 * (MAX_IMD_ORDER - 1));
  const binWidth = r.sampleRate / r.fftSize;
  tones[0] = (Number.isFinite(r.fundamentalHzRefined) && r.fundamentalHzRefined > 0.0)
    ? r.fundamentalHzRefined : r.fundamentalBin * binWidth;
  for (let h = 0; h < harmonics; h++) {
    if (r.harmonicBins != null && r.harmonicBins[h] > 0) {
      tones[1 + h] = r.harmonicHz[h];
    }
  }
  const second = r.fundamental2HzRefined;
  if (!Number.isNaN(second) && second > 0.0) {
    // Sort the refined pair so f1 = lower / f2 = higher. INTENDED WEB
    // DIVERGENCE from the Java tone set, which uses the refined values in slot
    // order: the web analyzer can deliver the HIGHER tone in slot 1 (tones
    // entered high-first), and an unsorted pair flips dnL/dnH so the real
    // f2 - f1 difference product goes negative and is skipped - the difference
    // products would then never be de-embedded.
    // The sort feeds the PRODUCT formulas only. The F2 slot below keeps the
    // unsorted second refined value, as it always has: that slot names the
    // second tone the frame reported, not the higher of the two, and its own
    // lobe is corrected wherever it sits. The asymmetry is deliberate.
    const f1 = Math.min(r.fundamentalHzRefined, second);
    const f2 = Math.max(r.fundamentalHzRefined, second);
    tones[1 + harmonics] = second;
    let idx = 2 + harmonics;
    for (let n = 2; n <= MAX_IMD_ORDER; n++) {
      if (n === 2) {
        tones[idx++] = f2 - f1;                        // difference
        tones[idx++] = f2 + f1;                        // sum
      } else {
        tones[idx++] = (n - 1) * f1 - (n - 2) * f2;
        tones[idx++] = (n - 1) * f2 - (n - 2) * f1;
      }
    }
  }
  return tones;
}

/**
 * The main-lobe geometry of every tone of toneFrequencies, measured on the
 * frame's RAW bin magnitudes: a tone's energy fills the analysis window's whole
 * main lobe, so a tone-only row's single value H(f_tone) is written across
 * exactly these bins. All spans come from the frame as it arrives, in one pass,
 * so no span is measured on bins an earlier tone or row has already rewritten.
 *
 * The calibration's own frequency range is NOT applied here: each loaded row
 * has its own range while this geometry is a property of the frame, shared by
 * every row and compared frame to frame to decide whether the held response
 * still fits. The range filter sits in cascadeInverse, where the row is known.
 *
 * @param {import('../fft/fft-result.js').FftResult} r
 * @param {Float64Array} tones
 * @returns {Int32Array[]} one [peak, lo, hi] per tone slot, all ABSENT_BIN for
 *   a tone the frame does not carry or whose peak falls outside 1..fftSize/2
 */
export function toneLobeSpans(r, tones) {
  const half = r.fftSize / 2;
  const binWidth = r.sampleRate / r.fftSize;
  const mag = (k) => Math.hypot(r.re[k], r.im[k]);
  const spans = [];
  for (let t = 0; t < tones.length; t++) {
    const span = new Int32Array([ABSENT_BIN, ABSENT_BIN, ABSENT_BIN]);
    spans.push(span);
    const hz = tones[t];
    if (!(hz > 0.0)) continue;
    const peak = Math.round(hz / binWidth);
    if (peak < 1 || peak > half) continue;
    const floor = LOBE.localFloor(mag, peak, half);
    const edges = LOBE.lobeBins(mag, peak, half, floor);
    span[SPAN_PEAK] = peak;
    span[SPAN_LO] = edges[0];
    span[SPAN_HI] = edges[1];
  }
  return spans;
}

/**
 * The de-embedding response of every loaded calibration row, cascaded into ONE
 * per-bin array and inverted, ready for applyInverseInPlace.
 *
 * The rows are filters in series, so their responses form a COMPLEX PRODUCT per
 * bin - magnitudes multiply, phases add. The product is commutative, which is
 * why the two groups arrive as two lists: a with-noise row contributes H(f_k)
 * at every bin of its swept range (the whole spectrum, noise included), a
 * tone-only row contributes H(f_tone) across each tone's lobe span and nothing
 * between the tones. Within one tone-only row the first tone of the slot order
 * owns a shared bin; a further row multiplies its own value into the same bins,
 * which is what puts the rows in series.
 *
 * A bin's squared magnitude is accumulated as the product of the rows' own
 * |H|^2 and the pair is inverted as conj(G)/|G|^2. A bin no row reached, and
 * one whose cascade cancelled to zero magnitude, keeps (0, 0).
 *
 * @param {Array<{freqs:Float64Array, magLin:Float64Array, phaseRad:Float64Array}>} withNoiseRows
 * @param {Array<{freqs:Float64Array, magLin:Float64Array, phaseRad:Float64Array}>} toneOnlyRows
 * @param {Float64Array} tones
 * @param {Int32Array[]} toneSpans
 * @param {number} fftSize
 * @param {number} sampleRate
 * @param {Float64Array} [scratchRe]    caller-held accumulator, length fftSize/2+1
 * @param {Float64Array} [scratchIm]
 * @param {Float64Array} [scratchMagSq]
 * @returns {Float64Array[]} [invRe, invIm], each indexed by bin 0..fftSize/2
 */
export function cascadeInverse(withNoiseRows, toneOnlyRows, tones, toneSpans, fftSize, sampleRate,
  scratchRe, scratchIm, scratchMagSq) {
  const half = fftSize / 2;
  const binWidth = sampleRate / fftSize;
  // The three per-bin accumulators are the caller's when it holds them across rebuilds - at a
  // 2^18-point FFT they are 3 MB, and a frame whose lobes moved rebuilds every time. Zeroed here
  // rather than freshly allocated, so a held set behaves exactly like a new one.
  const fits = (a) => a != null && a.length === half + 1;
  const gRe = fits(scratchRe) ? scratchRe.fill(0) : new Float64Array(half + 1);
  const gIm = fits(scratchIm) ? scratchIm.fill(0) : new Float64Array(half + 1);
  const magSq = fits(scratchMagSq) ? scratchMagSq.fill(0) : new Float64Array(half + 1);
  for (const cal of withNoiseRows) {
    const fLo = cal.freqs[0];
    const fHi = cal.freqs[cal.freqs.length - 1];
    for (let k = 1; k <= half; k++) {
      const f = k * binWidth;
      if (f < fLo || f > fHi) continue;
      const h = interpolate(cal, f);
      if (h[0] <= 0.0) continue;
      multiplyIn(gRe, gIm, magSq, k, h[0], h[1]);
    }
  }
  for (const cal of toneOnlyRows) {
    const fLo = cal.freqs[0];
    const fHi = cal.freqs[cal.freqs.length - 1];
    const done = new Uint8Array(half + 1);
    for (let t = 0; t < tones.length; t++) {
      const hz = tones[t];
      const lo = toneSpans[t][SPAN_LO];
      if (lo === ABSENT_BIN || !(hz > 0.0) || hz < fLo || hz > fHi) continue;
      const h = interpolate(cal, hz);
      if (h[0] <= 0.0) continue;
      for (let k = lo; k <= toneSpans[t][SPAN_HI]; k++) {
        if (k < 1 || k > half || done[k]) continue;
        multiplyIn(gRe, gIm, magSq, k, h[0], h[1]);
        done[k] = 1;
      }
    }
  }
  for (let k = 1; k <= half; k++) {
    const m = magSq[k];
    if (!(m > 0.0)) {
      gRe[k] = 0.0;
      gIm[k] = 0.0;
      continue;
    }
    const vRe = gRe[k] / m;
    const vIm = -gIm[k] / m;
    gRe[k] = vRe;
    gIm[k] = vIm;
  }
  return [gRe, gIm];
}

/** Multiplies one row's response at one bin into the cascade. The first row to
 *  reach a bin writes its response unchanged, so a lone row leaves no product
 *  rounding behind; the squared magnitude is carried as the product of the
 *  rows' own values rather than re-derived from the accumulated parts, which is
 *  what keeps a single row's reciprocal identical to the one formed straight
 *  from its interpolated magnitude. */
function multiplyIn(gRe, gIm, magSq, k, hMag, phaseRad) {
  const hRe = hMag * Math.cos(phaseRad);
  const hIm = hMag * Math.sin(phaseRad);
  if (magSq[k] > 0.0) {
    const aRe = gRe[k];
    const aIm = gIm[k];
    gRe[k] = aRe * hRe - aIm * hIm;
    gIm[k] = aRe * hIm + aIm * hRe;
    magSq[k] = magSq[k] * (hMag * hMag);
  } else {
    gRe[k] = hRe;
    gIm[k] = hIm;
    magSq[k] = hMag * hMag;
  }
}

/**
 * Divides every bin the cascade reached by its response, in place, and returns
 * how many bins were written. Each such bin gets one complex multiply by the
 * stored reciprocal and has its level and phase restated; every other bin keeps
 * exactly the numbers the analyser wrote, since a recompute would move them by
 * a rounding step.
 *
 * `bins` is the marked-bin list from toneBins when every loaded row corrects
 * only the tones - the correction then covers a few lobes, and walking the list
 * costs the lobes instead of the whole spectrum. It is null once a with-noise
 * row is active, since that row marks most of the spectrum.
 *
 * The level scale is read off the fundamental BEFORE any bin is changed, so the
 * whole spectrum is restated on one consistent scale.
 *
 * @param {import('../fft/fft-result.js').FftResult} r
 * @param {Float64Array[]} inv
 * @param {?Int32Array} bins
 * @returns {number} corrected bin count
 */
export function applyInverseInPlace(r, inv, bins) {
  const invRe = inv[INV_RE];
  const invIm = inv[INV_IM];
  const oldFundMag = Math.hypot(r.re[r.fundamentalBin], r.im[r.fundamentalBin]);
  const linPerMag = oldFundMag > 0.0 ? r.fundamentalLinear / oldFundMag : 0.0;
  let corrected = 0;
  if (bins != null) {
    for (let i = 0; i < bins.length; i++) {
      if (correctBin(r, invRe, invIm, bins[i], linPerMag)) corrected++;
    }
  } else {
    const half = r.fftSize / 2;
    for (let k = 1; k <= half; k++) {
      if (correctBin(r, invRe, invIm, k, linPerMag)) corrected++;
    }
  }
  return corrected;
}

/** One bin: Z = X * (1/G), then the level and phase the display and the
 *  statistics read. Returns false for an untouched bin, which the (0, 0)
 *  reciprocal marks. */
function correctBin(r, invRe, invIm, k, linPerMag) {
  const vRe = invRe[k];
  const vIm = invIm[k];
  if (vRe === 0.0 && vIm === 0.0) return false;
  const xRe = r.re[k];
  const xIm = r.im[k];
  const zRe = xRe * vRe - xIm * vIm;
  const zIm = xRe * vIm + xIm * vRe;
  r.re[k] = zRe;
  r.im[k] = zIm;
  const newAmpLin = Math.hypot(zRe, zIm) * linPerMag;
  r.amplitudeDbFs[k] = newAmpLin > MIN_LINEAR_AMPLITUDE
    ? AMPLITUDE_DB_FACTOR * Math.log10(newAmpLin) : FLOOR_DB;
  r.phaseDeg[k] = radToDeg(Math.atan2(zIm, zRe));
  return true;
}

/**
 * The cascade's gain at one bin, 20*log10|G| - how many dB the correction takes
 * off that bin, so a level measured after the correction plus this value is the
 * level before it. 0 for a bin the cascade never reached and for one outside
 * the sampled grid.
 *
 * @param {?Float64Array[]} inv
 * @param {number} bin
 * @returns {number}
 */
export function gainDbAt(inv, bin) {
  if (inv == null || bin < 1 || bin >= inv[INV_RE].length) return 0.0;
  const vRe = inv[INV_RE][bin];
  const vIm = inv[INV_IM][bin];
  const vMagSq = vRe * vRe + vIm * vIm;
  return vMagSq > 0.0 ? -POWER_DB_FACTOR * Math.log10(vMagSq) : 0.0;
}

/**
 * The bins of every located tone lobe, ascending and without repeats - the walk
 * applyInverseInPlace makes when no with-noise row is loaded.
 *
 * @param {Int32Array[]} toneSpans
 * @param {number} half
 * @returns {Int32Array}
 */
export function toneBins(toneSpans, half) {
  const marked = new Uint8Array(half + 1);
  let n = 0;
  for (const span of toneSpans) {
    if (span[SPAN_LO] === ABSENT_BIN) continue;
    for (let k = span[SPAN_LO]; k <= span[SPAN_HI]; k++) {
      if (k >= 1 && k <= half && !marked[k]) {
        marked[k] = 1;
        n++;
      }
    }
  }
  const bins = new Int32Array(n);
  let i = 0;
  for (let k = 1; k <= half; k++) {
    if (marked[k]) bins[i++] = k;
  }
  return bins;
}

/**
 * Snapshots the pre-correction fundamental + harmonic peak levels so the chart
 * can draw blue "before-cal" dots alongside the red "after-cal" dots. Returns
 * [freqs, dbFs] with index 0 = fundamental, 1..harmonicCount = H2..HN.
 *
 * @param {import('../fft/fft-result.js').FftResult} result
 * @returns {Float64Array[]}
 */
export function capturePreCorrectionPeaks(result) {
  const count = 1 + result.harmonicCount;
  const freqs = new Float64Array(count);
  const dbFs = new Float64Array(count);
  freqs[0] = result.fundamentalHz;
  dbFs[0] = result.fundamentalDbFs;
  for (let h = 0; h < result.harmonicCount; h++) {
    if (result.harmonicBins[h] > 0) {
      freqs[1 + h] = result.harmonicHz[h];
      dbFs[1 + h] = result.harmonicDbFs[h];
    }
  }
  return [freqs, dbFs];
}

/** Math.toDegrees. */
function radToDeg(rad) { return rad * (180.0 / Math.PI); }
