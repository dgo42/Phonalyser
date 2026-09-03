/*
 * Phonalyser web - DDS generator spectral-purity contract test.
 * Run with `node web/test/dds-generator-purity.test.mjs`.
 *
 * Proves the DdsKernel (web/js/generator/dds-kernel.js) emits spectrally clean
 * tones with no significant spurs - the guarantee the AudioWorklet relies on to
 * feed the FreqResp / THD / IMD measurement chain. The worklet carries its
 * output in a Float32Array, so every sample is narrowed to float32 (Math.fround)
 * before analysis: that float32 quantization noise, not the float64 kernel math,
 * is the real spectral floor the user hears.
 *
 * For each config a long run of samples is generated exactly as the worklet
 * would, windowed with a 4-term Blackman-Harris window (−92 dB sidelobes so it
 * cannot masquerade as a generator spur), FFT'd, and the worst spur in 15-35 kHz
 * - excluding ±60 bins around each tone - is measured relative to the tone peak.
 *
 *   1. DUAL_TONE 19k/20k (snapped to the 32768-bin output grid) @192k and @48k,
 *      float32-narrowed: worst spur below −150 dBc, and with equal per-tone
 *      weights (w1=w2) the two tone bins match within 0.1 dB.
 *   2. SINE 19001.953125 Hz @192k (float32): worst spur below −150 dBc, proving
 *      the single-tone kernel too.
 *
 * Threshold: at N=2^19 the measured float32 worst spur is ≈−174 dBc (DUAL) /
 * ≈−182 dBc (SINE) - dominated by float32 quantization, essentially independent
 * of N (2^21 gives the same figures). −150 dBc leaves ~24 dB margin above the
 * worst measured spur and sits far below the −120 dBc ceiling this test must
 * prove, so N=2^19 is enough to keep it CI-friendly.
 */

import { DdsKernel } from '../js/generator/dds-kernel.js';
import { GenSignalForm } from '../js/enums/gen-signal-form.js';

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) { passed++; } else { failed++; console.error('FAIL:', msg); } }

/** FFT length (samples). 2^19 proves the floor with ~24 dB margin - see header. */
const N = 1 << 19;
/** Output-bin grid the generator snaps requested frequencies to (rate/BINS). */
const BINS = 32768;
/** Worst-spur ceiling (dBc). Below the −120 dBc requirement with ~24 dB margin. */
const SPUR_LIMIT_DBC = -150;
/** Half-width (bins) of the tone-peak exclusion zone around each fundamental. */
const TONE_GUARD_BINS = 60;
/** Half-width (bins) of the tone-peak search window used to find the reference. */
const TONE_PEAK_BINS = 8;
/** Spur search band edges (Hz). */
const BAND_LO_HZ = 15000;
const BAND_HI_HZ = 35000;

// --- self-contained iterative radix-2 FFT (in place, decimation-in-time) -----
function fft(re, im) {
  const n = re.length;
  for (let i = 1, j = 0; i < n; i++) {
    let bit = n >> 1;
    for (; j & bit; bit >>= 1) j ^= bit;
    j ^= bit;
    if (i < j) { let t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
  }
  for (let len = 2; len <= n; len <<= 1) {
    const ang = -2 * Math.PI / len;
    const wr = Math.cos(ang), wi = Math.sin(ang);
    for (let i = 0; i < n; i += len) {
      let cr = 1, ci = 0;
      for (let k = 0; k < len / 2; k++) {
        const ur = re[i + k], ui = im[i + k];
        const vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci;
        const vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr;
        re[i + k] = ur + vr; im[i + k] = ui + vi;
        re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi;
        const ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr;
      }
    }
  }
}

// --- 4-term Blackman-Harris window (−92 dB sidelobes) ------------------------
function blackmanHarrisInPlace(re) {
  const a0 = 0.35875, a1 = 0.48829, a2 = 0.14128, a3 = 0.01168;
  const len = re.length;
  for (let i = 0; i < len; i++) {
    re[i] *= a0 - a1 * Math.cos(2 * Math.PI * i / len)
                + a2 * Math.cos(4 * Math.PI * i / len)
                - a3 * Math.cos(6 * Math.PI * i / len);
  }
}

/** Peak magnitude within ±TONE_PEAK_BINS of the bin nearest `freqHz`. */
function tonePeak(mag, freqHz, hzPerBin) {
  const b = Math.round(freqHz / hzPerBin);
  let m = 0;
  for (let i = b - TONE_PEAK_BINS; i <= b + TONE_PEAK_BINS; i++) m = Math.max(m, mag[i]);
  return m;
}

/**
 * Generates one windowed FFT of `N` float32-narrowed samples from `kernel`,
 * then returns the worst spur in [BAND_LO_HZ, BAND_HI_HZ] excluding ±TONE_GUARD_BINS
 * around each tone in `toneFreqs`, plus the per-tone peak magnitudes.
 */
function analyze(kernel, rate, toneFreqs) {
  const re = new Float64Array(N), im = new Float64Array(N);
  for (let i = 0; i < N; i++) re[i] = Math.fround(kernel.nextSample());
  blackmanHarrisInPlace(re);
  fft(re, im);
  const half = N / 2;
  const mag = new Float64Array(half);
  for (let i = 0; i < half; i++) mag[i] = Math.hypot(re[i], im[i]);
  const hzPerBin = rate / N;
  const toneBins = toneFreqs.map((f) => Math.round(f / hzPerBin));
  const tonePeaks = toneFreqs.map((f) => tonePeak(mag, f, hzPerBin));
  const ref = Math.max(...tonePeaks);
  const lo = Math.round(BAND_LO_HZ / hzPerBin);
  const hi = Math.min(half - 1, Math.round(BAND_HI_HZ / hzPerBin));
  let bestMag = 0, bestBin = -1;
  for (let i = lo; i <= hi; i++) {
    if (toneBins.some((b) => Math.abs(i - b) <= TONE_GUARD_BINS)) continue;
    if (mag[i] > bestMag) { bestMag = mag[i]; bestBin = i; }
  }
  return {
    worstDbc: 20 * Math.log10(bestMag / ref),
    worstHz: bestBin * hzPerBin,
    tonePeaks,
  };
}

/** Snaps a requested frequency to the generator's rate/BINS output-bin grid. */
function snap(freqHz, rate) {
  const binHz = rate / BINS;
  return Math.round(freqHz / binHz) * binHz;
}

// --- DUAL_TONE 19k/20k, float32, @192k and @48k ------------------------------
for (const rate of [192000, 48000]) {
  const f1 = snap(19000, rate);
  const f2 = snap(20000, rate);
  const k = new DdsKernel({
    form: GenSignalForm.DUAL_TONE, frequency: f1, sampleRate: rate,
    amplitudeVRms: 0.5, dacFsVoltageAmpl: Math.SQRT2,
  });
  k.setDualToneFrequency2(f2);
  k.setDualToneAmplitudes(50, 50); // equal weights -> tone bins must match
  const r = analyze(k, rate, [f1, f2]);
  const ratioDb = 20 * Math.log10(r.tonePeaks[0] / r.tonePeaks[1]);
  ok(r.worstDbc < SPUR_LIMIT_DBC,
     `DUAL @${rate} worst spur ${r.worstDbc.toFixed(1)} dBc below ${SPUR_LIMIT_DBC} dBc`);
  ok(Math.abs(ratioDb) <= 0.1,
     `DUAL @${rate} equal weights -> tone bins match within 0.1 dB (got ${ratioDb.toFixed(4)} dB)`);
  console.log(`DUAL rate=${rate} f32  f1=${f1.toFixed(3)} f2=${f2.toFixed(3)}  ` +
    `worst=${r.worstDbc.toFixed(1)} dBc @ ${(r.worstHz / 1000).toFixed(2)} kHz  ratio=${ratioDb.toFixed(4)} dB`);
}

// --- SINE 19001.953125 Hz @192k, float32 -------------------------------------
{
  const rate = 192000;
  const f1 = snap(19000, rate); // 19001.953125
  const k = new DdsKernel({
    form: GenSignalForm.SINE, frequency: f1, sampleRate: rate,
    amplitudeVRms: 0.5, dacFsVoltageAmpl: Math.SQRT2,
  });
  const r = analyze(k, rate, [f1]);
  ok(r.worstDbc < SPUR_LIMIT_DBC,
     `SINE @${rate} worst spur ${r.worstDbc.toFixed(1)} dBc below ${SPUR_LIMIT_DBC} dBc`);
  console.log(`SINE rate=${rate} f32  f1=${f1.toFixed(6)}  ` +
    `worst=${r.worstDbc.toFixed(1)} dBc @ ${(r.worstHz / 1000).toFixed(2)} kHz`);
}

console.log(`\ndds-generator-purity: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
