/*
 * Phonalyser web - .frc de-embedding test (the per-bin cascade).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Run with `node web/test/frc-de-embed.test.mjs`.
 *
 * Mirror of the Java FreqRespCalDeEmbedTest against the web's dsp/frc.js
 * cascade (cascadeInverse / applyInverseInPlace / toneFrequencies /
 * toneLobeSpans / toneBins / gainDbAt), question for question and with the same
 * tolerances, in four groups.
 *
 * EQUIVALENCE. A single with-noise row - one that divides every bin, noise
 * included - must produce exactly the spectrum a per-bin interpolation
 * produces. The reference is that interpolation written out longhand from the
 * public interpolate(), sharing no code with the cascade, and the comparison is
 * BIT FOR BIT: a de-embedded spectrum is a measurement, so the same input must
 * keep producing the same numbers.
 *
 * SERIES. Two rows are two filters in series, so their cascade is the COMPLEX
 * PRODUCT of the two responses. Applying the product once and applying the two
 * responses one after the other are the same operation in exact arithmetic and
 * differ only by where the roundings fall, so that comparison carries a
 * tolerance while the single-row one does not.
 *
 * TONES. A tone-only row corrects the tones alone: one value per tone, written
 * across the tone's whole main lobe, with every other bin - the noise between
 * the tones - left as the analyser wrote it. Mixed with a with-noise row, the
 * lobe bins carry both responses.
 *
 * KEYS. The cascade is sampled on a grid, on a calibration, and - while a
 * tone-only row is active - on the frame's tone positions and lobe extents.
 */

import { FftResult } from '../js/fft/fft-result.js';
import {
  applyInverseInPlace, cascadeInverse, gainDbAt, interpolate,
  toneBins, toneFrequencies, toneLobeSpans,
} from '../js/dsp/frc.js';
import { ToneLobeLift } from '../js/dsp/tone-lobe-lift.js';

// The cascade is HELD by the display-preparation stage, which is a plain module - the worker
// that wraps it is a shell, and the analysis worker imports the same module for the two cases it
// prepares itself. Only the holder and its rebuild rule are asked here (block 7b); the whole tick
// seam is another test's subject.
const { cal: held, geometryToRebuildFor, buildCalInverse } = await import('../js/fft/fft-finalize.js');

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) { passed++; } else { failed++; console.error('FAIL:', msg); } }

const SAMPLE_RATE_HZ = 48000;
// Small on purpose - the equivalence is per bin, so a short spectrum asks it as
// completely as a long one and the test stays quick.
const FFT_SIZE = 1024;
const DOUBLED_RATE_HZ = 96000;
const DOUBLED_FFT_SIZE = 2048;
const HALF = FFT_SIZE / 2;
const BIN_HZ = SAMPLE_RATE_HZ / FFT_SIZE;
const FUNDAMENTAL_BIN = 100;
const HARMONIC_COUNT = 8;
const DB_FLOOR_LIMIT = 1e-15;
const DB_FLOOR = -300.0;
// The product applied once and the two responses applied one after the other
// round at different points, which reaches a few units in the last place.
const SERIES_RELATIVE_TOLERANCE = 1e-12;
const SERIES_DB_TOLERANCE = 1e-9;
const PHASE_DEGREE_TOLERANCE = 1e-9;

const slack = (expected) => Math.abs(expected) * SERIES_RELATIVE_TOLERANCE + DB_FLOOR_LIMIT;
const wrapDegrees = (deg) => {
  let d = deg % 360.0;
  if (d > 180.0) d -= 360.0;
  if (d <= -180.0) d += 360.0;
  return d;
};
const mag = (r, k) => Math.hypot(r.re[k], r.im[k]);

/** A calibration shaped like the benches this feature is used on: a unity
 *  pass-band with a deep, phase-rotating notch in the middle, on log-spaced
 *  points. `scale` multiplies every magnitude, standing for the second channel
 *  of a file or a different loaded file. */
function notchLikeCal(scale) {
  const freqs = new Float64Array([20.0, 50.0, 200.0, 800.0, 950.0, 1000.0, 1050.0,
    2000.0, 5000.0, 12000.0, 20000.0]);
  const magLin = new Float64Array([1.0, 1.0, 0.98, 0.6, 0.05, 1e-4, 0.05, 0.7, 0.95, 1.0, 0.9]);
  const phaseRad = new Float64Array(freqs.length);
  for (let i = 0; i < freqs.length; i++) {
    magLin[i] *= scale;
    // A notch turns the phase through half a circle across its null.
    phaseRad[i] = -Math.PI / 2.0 + Math.PI * i / (freqs.length - 1.0);
  }
  return { freqs, magLin, phaseRad };
}

/** A second, differently shaped calibration - a plain rising response with its
 *  own phase - so a cascade of two rows is not one response with itself. */
function slopedCal() {
  return {
    freqs: new Float64Array([20.0, 200.0, 2000.0, 20000.0]),
    magLin: new Float64Array([0.3, 0.55, 0.8, 1.2]),
    phaseRad: new Float64Array([0.2, 0.35, -0.4, 0.9]),
  };
}

/** A measured spectrum to correct: a tone on a bin centre plus its second
 *  harmonic on a shaped floor, with the harmonic table the analyser fills. The
 *  lobes are seeded explicitly so the lobe walk has a floor to stop at. */
function seededTone(fftSize = FFT_SIZE, sampleRate = SAMPLE_RATE_HZ) {
  const half = fftSize / 2;
  const binHz = sampleRate / fftSize;
  const r = new FftResult();
  r.fftSize = fftSize;
  r.sampleRate = sampleRate;
  r.freqResolution = binHz;
  r.ensureArrays(half + 1, HARMONIC_COUNT);
  r.harmonicCount = HARMONIC_COUNT;
  const noise = 1e-5;
  for (let k = 0; k <= half; k++) {
    r.re[k] = noise * (1.0 + 0.1 * Math.sin(k));
    r.im[k] = noise * 0.5;
  }
  const seed = (bin, peak) => {
    if (bin < 1 || bin > half) return;
    r.re[bin] = peak;
    r.re[bin - 1] = peak * 0.3; r.re[bin + 1] = peak * 0.3;
    r.re[bin - 2] = peak * 0.02; r.re[bin + 2] = peak * 0.02;
  };
  seed(FUNDAMENTAL_BIN, 0.5);
  for (let h = 0; h < HARMONIC_COUNT; h++) {
    const bin = FUNDAMENTAL_BIN * (h + 2);
    r.harmonicBins[h] = bin <= half ? bin : 0;
    r.harmonicHz[h] = bin * binHz;
    r.harmonicDbFs[h] = -60.0;
    if (bin <= half) seed(bin, 0.5 * 0.01);
  }
  for (let k = 0; k <= half; k++) {
    const m = mag(r, k);
    r.amplitudeDbFs[k] = m > 0.0 ? 20.0 * Math.log10(m) : DB_FLOOR;
    r.phaseDeg[k] = Math.atan2(r.im[k], r.re[k]) * (180.0 / Math.PI);
  }
  r.fundamentalBin = FUNDAMENTAL_BIN;
  r.fundamentalHz = FUNDAMENTAL_BIN * binHz;
  r.fundamentalHzRefined = r.fundamentalHz;
  r.fundamental2HzRefined = NaN;
  r.fundamentalDbFs = r.amplitudeDbFs[FUNDAMENTAL_BIN];
  r.fundamentalLinear = 0.5;
  return r;
}

/** A frame of the stated geometry with the same magnitude in every bin - enough
 *  for the questions that ask only which frequency a bin index stands for. */
function flatFrame(fftSize, sampleRate) {
  const half = fftSize / 2;
  const r = new FftResult();
  r.fftSize = fftSize;
  r.sampleRate = sampleRate;
  r.freqResolution = sampleRate / fftSize;
  r.ensureArrays(half + 1, 0);
  r.harmonicCount = 0;
  for (let k = 0; k <= half; k++) { r.re[k] = 1.0; r.im[k] = 0.0; r.amplitudeDbFs[k] = 0.0; }
  r.fundamentalBin = FUNDAMENTAL_BIN;
  r.fundamentalHz = FUNDAMENTAL_BIN * r.freqResolution;
  r.fundamentalHzRefined = r.fundamentalHz;
  r.fundamental2HzRefined = NaN;
  r.fundamentalLinear = 1.0;
  return r;
}

/** Linear amplitude per unit of raw bin magnitude, read off the fundamental of
 *  the UNCORRECTED frame - the scale every restated level is expressed on. */
function levelScaleOf(r) {
  const fundMag = mag(r, r.fundamentalBin);
  return fundMag > 0.0 ? r.fundamentalLinear / fundMag : 0.0;
}

/** The per-bin interpolation and divide written out longhand from the public
 *  interpolate(): an independent oracle, inverting H per bin first and then
 *  multiplying - the operation order the cascade performs. The level scale is
 *  supplied so a second pass keeps the scale of the uncorrected frame. */
function deEmbedByInterpolatingEveryBin(r, cal, linPerMag) {
  const half = r.fftSize / 2;
  const binWidth = r.sampleRate / r.fftSize;
  const fLo = cal.freqs[0];
  const fHi = cal.freqs[cal.freqs.length - 1];
  for (let k = 1; k <= half; k++) {
    const f = k * binWidth;
    if (f < fLo || f > fHi) continue;
    const h = interpolate(cal, f);
    const hMag = h[0];
    if (hMag <= 0.0) continue;
    const hRe = hMag * Math.cos(h[1]);
    const hIm = hMag * Math.sin(h[1]);
    const hMagSq = hMag * hMag;
    const vRe = hRe / hMagSq;
    const vIm = -hIm / hMagSq;
    const xRe = r.re[k], xIm = r.im[k];
    const zRe = xRe * vRe - xIm * vIm;
    const zIm = xRe * vIm + xIm * vRe;
    r.re[k] = zRe;
    r.im[k] = zIm;
    const newAmpLin = Math.hypot(zRe, zIm) * linPerMag;
    r.amplitudeDbFs[k] = newAmpLin > DB_FLOOR_LIMIT ? 20.0 * Math.log10(newAmpLin) : DB_FLOOR;
    r.phaseDeg[k] = Math.atan2(zIm, zRe) * (180.0 / Math.PI);
  }
}

const withNoiseInverse = (r, rows) => {
  const tones = toneFrequencies(r);
  return cascadeInverse(rows, [], tones, toneLobeSpans(r, tones), r.fftSize, r.sampleRate);
};
const toneOnlyInverse = (r, rows) => {
  const tones = toneFrequencies(r);
  return cascadeInverse([], rows, tones, toneLobeSpans(r, tones), r.fftSize, r.sampleRate);
};
const toneBinsOf = (r) => toneBins(toneLobeSpans(r, toneFrequencies(r)), r.fftSize / 2);

/** Inclusive lobe span of the tone at toneHz, found the way the correction
 *  finds it: peak bin, robust local floor, walk out to the floor. */
function lobeSpanAt(r, toneHz) {
  const half = r.fftSize / 2;
  const lift = new ToneLobeLift();
  const m = (k) => Math.hypot(r.re[k], r.im[k]);
  const peak = Math.round(toneHz / (r.sampleRate / r.fftSize));
  const floor = lift.localFloor(m, peak, half);
  return lift.lobeBins(m, peak, half, floor);
}

/** Every bin a tone-only row of `cal` reaches on `r`: the lobe spans of the
 *  fundamental and of each located harmonic inside the calibration's range. */
function toneLobeMap(r, cal) {
  const fLo = cal.freqs[0];
  const fHi = cal.freqs[cal.freqs.length - 1];
  const marked = new Uint8Array(r.fftSize / 2 + 1);
  const tones = [r.fundamentalHzRefined];
  for (let i = 0; i < r.harmonicCount; i++) {
    tones.push(r.harmonicBins[i] > 0 ? r.harmonicHz[i] : 0.0);
  }
  for (const hz of tones) {
    if (!(hz > 0.0) || hz < fLo || hz > fHi) continue;
    const span = lobeSpanAt(r, hz);
    for (let k = span[0]; k <= span[1]; k++) if (k >= 1 && k < marked.length) marked[k] = 1;
  }
  return marked;
}

// --- 1. One with-noise row de-embeds bit-identically to per-bin interpolation.
{
  const cal = notchLikeCal(1.0);
  const reference = seededTone();
  const cascaded = reference.deepCopy();
  const uncorrected = reference.amplitudeDbFs[FUNDAMENTAL_BIN];
  deEmbedByInterpolatingEveryBin(reference, cal, levelScaleOf(reference));
  applyInverseInPlace(cascaded, withNoiseInverse(cascaded, [cal]), null);

  ok(cascaded.amplitudeDbFs[FUNDAMENTAL_BIN] !== uncorrected,
    'the de-embedding changed nothing, so the comparison below would hold even if neither path worked');
  let bitEqual = true;
  for (let k = 0; k <= HALF; k++) {
    if (reference.re[k] !== cascaded.re[k] || reference.im[k] !== cascaded.im[k]
      || reference.amplitudeDbFs[k] !== cascaded.amplitudeDbFs[k]
      || reference.phaseDeg[k] !== cascaded.phaseDeg[k]) { bitEqual = false; break; }
  }
  ok(bitEqual, 'one with-noise row equals the per-bin interpolation bit for bit');
}

// --- 2. Two with-noise rows in series equal their complex product applied once.
{
  const first = notchLikeCal(1.0);
  const second = slopedCal();
  const sequential = seededTone();
  const cascaded = sequential.deepCopy();
  const uncorrected = sequential.amplitudeDbFs[FUNDAMENTAL_BIN];
  const linPerMag = levelScaleOf(sequential);
  deEmbedByInterpolatingEveryBin(sequential, first, linPerMag);
  deEmbedByInterpolatingEveryBin(sequential, second, linPerMag);
  applyInverseInPlace(cascaded, withNoiseInverse(cascaded, [first, second]), null);

  ok(cascaded.amplitudeDbFs[FUNDAMENTAL_BIN] !== uncorrected,
    'neither path corrected anything, so the series comparison is empty');
  let within = true;
  for (let k = 0; k <= HALF; k++) {
    if (Math.abs(sequential.re[k] - cascaded.re[k]) > slack(sequential.re[k])
      || Math.abs(sequential.im[k] - cascaded.im[k]) > slack(sequential.im[k])
      || Math.abs(sequential.amplitudeDbFs[k] - cascaded.amplitudeDbFs[k]) > SERIES_DB_TOLERANCE) {
      within = false; break;
    }
  }
  ok(within, 'two rows in series equal the product applied once, within the stated rounding tolerance');
}

// --- 3. A tone-only row corrects the tone lobes and nothing else.
{
  const cal = notchLikeCal(1.0);
  const corrected = seededTone();
  const raw = corrected.deepCopy();
  const inLobe = toneLobeMap(raw, cal);
  const span = lobeSpanAt(raw, raw.fundamentalHzRefined);
  const h = interpolate(cal, raw.fundamentalHzRefined);
  const reported = applyInverseInPlace(corrected, toneOnlyInverse(corrected, [cal]), toneBinsOf(corrected));

  ok(reported > 0, 'nothing was corrected, so the tone-only checks are empty');
  let lobeOk = true;
  for (let k = span[0]; k <= span[1]; k++) {
    const expected = mag(raw, k) / h[0];
    if (Math.abs(mag(corrected, k) - expected) > slack(expected)) { lobeOk = false; break; }
    const turned = wrapDegrees(corrected.phaseDeg[k] - raw.phaseDeg[k] + h[1] * (180.0 / Math.PI));
    if (Math.abs(turned) > PHASE_DEGREE_TOLERANCE) { lobeOk = false; break; }
  }
  ok(lobeOk, 'each fundamental-lobe bin is divided by the response magnitude and rotated by its phase');
  let othersUntouched = true;
  for (let k = 0; k <= HALF; k++) {
    if (inLobe[k]) continue;
    if (raw.re[k] !== corrected.re[k] || raw.im[k] !== corrected.im[k]
      || raw.amplitudeDbFs[k] !== corrected.amplitudeDbFs[k]
      || raw.phaseDeg[k] !== corrected.phaseDeg[k]) { othersUntouched = false; break; }
  }
  ok(othersUntouched, 'every bin outside a tone lobe keeps all four of its outputs');
}

// --- 4. The tone-only pass reports the lobe bins as its corrected count.
{
  const cal = notchLikeCal(1.0);
  const frame = seededTone();
  const inLobe = toneLobeMap(frame.deepCopy(), cal);
  let expected = 0;
  for (const marked of inLobe) if (marked) expected++;
  const reported = applyInverseInPlace(frame, toneOnlyInverse(frame, [cal]), toneBinsOf(frame));

  ok(expected > 0, 'the tone set must reach the spectrum, or nothing is asked here');
  ok(reported === expected, `every lobe bin and no other bin is written (${reported} vs ${expected})`);
  ok(reported < HALF, 'the tone-only pass does not walk the whole spectrum');
}

// --- 5. A with-noise row mixed with a tone-only row multiplies on the lobes.
{
  const wide = notchLikeCal(1.0);
  const tonesCal = slopedCal();
  const mixed = seededTone();
  const raw = mixed.deepCopy();
  const inLobe = toneLobeMap(raw, tonesCal);
  const tones = toneFrequencies(mixed);
  const spans = toneLobeSpans(mixed, tones);
  const inv = cascadeInverse([wide], [tonesCal], tones, spans, mixed.fftSize, mixed.sampleRate);
  const reported = applyInverseInPlace(mixed, inv, null);

  ok(reported > 0, 'nothing was corrected, so the mixed-rows checks are empty');
  const bothMag = interpolate(wide, raw.fundamentalHzRefined)[0]
    * interpolate(tonesCal, raw.fundamentalHzRefined)[0];
  const expectedLobe = mag(raw, FUNDAMENTAL_BIN) / bothMag;
  ok(Math.abs(mag(mixed, FUNDAMENTAL_BIN) - expectedLobe) <= slack(expectedLobe),
    'a lobe bin is divided by BOTH responses');
  let noiseBin = 0;
  for (let k = FUNDAMENTAL_BIN + 1; k <= HALF; k++) {
    if (!inLobe[k] && k * BIN_HZ <= wide.freqs[wide.freqs.length - 1]) { noiseBin = k; break; }
  }
  ok(noiseBin > 0, 'the frame must have a bin between the tones inside the with-noise range');
  const wideOnly = interpolate(wide, noiseBin * BIN_HZ)[0];
  const expectedNoise = mag(raw, noiseBin) / wideOnly;
  ok(Math.abs(mag(mixed, noiseBin) - expectedNoise) <= slack(expectedNoise),
    'a bin between the tones is divided by the with-noise response alone');
}

// --- 6. The dual-tone product set, and the web's min-max sort of the pair.
{
  const dual = seededTone();
  dual.fundamentalHzRefined = 2500.0;      // slot 1 carries the HIGHER tone
  dual.fundamental2HzRefined = 2000.0;     // slot 2 the lower one
  const tones = toneFrequencies(dual);
  const products = Array.from(tones.slice(2 + dual.harmonicCount));
  ok(products.every((hz) => hz > 0.0),
    'the sorted pair keeps every intermod product positive (an unsorted pair skips the differences)');
  ok(products.includes(500.0), 'the second-order difference product f2 - f1 is in the set');
  ok(products.includes(4500.0), 'the second-order sum product f2 + f1 is in the set');
  const single = seededTone();
  const singleTones = toneFrequencies(single);
  ok(Array.from(singleTones.slice(2 + single.harmonicCount)).every((hz) => hz === 0.0),
    'a single-tone frame carries no products, and the absent slots stay in place');
}

// --- 7. The key material moves with the frame.
{
  const frame = seededTone();
  const tones = toneFrequencies(frame);
  const spans = toneLobeSpans(frame, tones);

  const same = frame.deepCopy();
  const sameSpans = toneLobeSpans(same, toneFrequencies(same));
  let identical = spans.length === sameSpans.length;
  for (let t = 0; identical && t < spans.length; t++) {
    identical = spans[t][0] === sameSpans[t][0] && spans[t][1] === sameSpans[t][1]
      && spans[t][2] === sameSpans[t][2];
  }
  ok(identical, 'an identical later frame keeps the same tone set and spans - no rebuild');

  const moved = frame.deepCopy();
  moved.fundamentalHzRefined = frame.fundamentalHzRefined + frame.freqResolution;
  ok(toneFrequencies(moved)[0] !== tones[0], 'a moved fundamental moves the tone set');

  const widened = frame.deepCopy();
  const span = lobeSpanAt(frame, frame.fundamentalHzRefined);
  widened.re[span[1] + 1] = frame.re[FUNDAMENTAL_BIN] / 2.0;
  widened.im[span[1] + 1] = 0.0;
  const widenedSpans = toneLobeSpans(widened, toneFrequencies(widened));
  ok(widenedSpans[0][2] !== spans[0][2], 'a widened lobe shows up in the spans');
}

// --- 7b. The REBUILD RULE the holder applies to that key material: the peak bin exactly, the
// lobe edges with a few bins of slack. An edge is where the lobe meets the noise, so a stale
// edge inside the tolerance corrects grass and moves no reading - while a deepening average
// walks every edge outward one bin at a time, which under an exact compare rebuilt the whole
// cascade on every displayed frame. The held spans are nudged directly, so each question asks
// exactly one edge distance.
{
  const rowCal = notchLikeCal(1.0);
  held.rows = [{ cal: rowCal, withNoise: false }];
  held.inv = null; held.toneBins = null; held.spans = null; held.gainDb = null;
  const frame = seededTone();

  const g = geometryToRebuildFor(frame);
  ok(g != null, 'with nothing held the first frame builds');
  buildCalInverse(frame, g.tones, g.spans);
  ok(geometryToRebuildFor(frame) === null, 'an identical frame does not rebuild');

  held.spans[0][2] -= 1;
  ok(geometryToRebuildFor(frame) === null,
    'a 1-bin edge wobble is inside the tolerance - no rebuild');
  held.spans[0][2] -= 3;                       // now 4 bins: still the tolerance
  ok(geometryToRebuildFor(frame) === null, 'a 4-bin edge shift is still inside it');
  held.spans[0][2] -= 1;                       // 5 bins: beyond it
  ok(geometryToRebuildFor(frame) !== null, 'a 5-bin edge shift rebuilds');

  held.spans[0][2] += 5;                       // restore the edge
  held.spans[0][0] += 1;                       // and move the PEAK by one bin
  ok(geometryToRebuildFor(frame) !== null,
    'a moved peak rebuilds however small the move - the peak carries the tone s identity');
}

// --- 8. What the cascade is specific to, and the untouched mark.
{
  const cal = notchLikeCal(1.0);
  const frame = flatFrame(FFT_SIZE, SAMPLE_RATE_HZ);
  const raw = frame.deepCopy();
  const inv = withNoiseInverse(frame, [cal]);
  applyInverseInPlace(frame, inv, null);
  const nyquistBin = HALF;

  ok(nyquistBin * BIN_HZ > cal.freqs[cal.freqs.length - 1],
    'the test grid must reach past the calibration top point');
  ok(gainDbAt(inv, nyquistBin) === 0.0, 'a bin above the calibrated range carries no correction');
  ok(frame.re[nyquistBin] === raw.re[nyquistBin] && frame.im[nyquistBin] === raw.im[nyquistBin],
    'and that bin keeps its numbers');
  ok(gainDbAt(inv, FUNDAMENTAL_BIN) !== 0.0, 'while an in-range bin is corrected');

  const gainAt = (c, fftSize, sampleRate) =>
    gainDbAt(withNoiseInverse(flatFrame(fftSize, sampleRate), [c]), FUNDAMENTAL_BIN);
  ok(gainAt(cal, FFT_SIZE, SAMPLE_RATE_HZ) !== gainAt(cal, DOUBLED_FFT_SIZE, SAMPLE_RATE_HZ),
    'the correction is specific to the FFT length');
  ok(gainAt(cal, FFT_SIZE, SAMPLE_RATE_HZ) !== gainAt(cal, FFT_SIZE, DOUBLED_RATE_HZ),
    'the correction is specific to the capture rate');
  ok(gainAt(notchLikeCal(1.0), FFT_SIZE, SAMPLE_RATE_HZ) !== gainAt(notchLikeCal(0.5), FFT_SIZE, SAMPLE_RATE_HZ),
    'the correction is specific to the channel the calibration came from');
  ok(gainAt(notchLikeCal(1.0), FFT_SIZE, SAMPLE_RATE_HZ) !== gainAt(notchLikeCal(2.0), FFT_SIZE, SAMPLE_RATE_HZ),
    'a different loaded row is a different response');
  ok(gainDbAt(inv, DOUBLED_FFT_SIZE / 2) === 0.0,
    'a bin index past the sampled grid carries no correction');
}

console.log(`frc-de-embed.test: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
