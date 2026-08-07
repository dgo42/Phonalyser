/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of the .fft snapshot format: the saveSpectrum / loadSpectrum
// round-trip in org.edgo.audio.measure.gui.fft.FftController.
//
// The .fft file is a UTF-8 text file: '#'-prefixed provenance header comments
// (capture mode, tone frequencies, FFT/analysis parameters, applied calibration
// paths), one column header, then one row per single-sided bin:
//   frequency_hz;magnitude_dBV;phase_deg
// Magnitudes are dBV (= dBFS + global ADC offset). The writer adds dbvOffsetDb
// on save; the reader subtracts it on load, so the round-trip is exact.
//
// This module ports ONLY the file I/O and the FftResult reconstruction Java does
// up to (not including) the stats recompute. The Java loadSpectrum then calls
// FftController.recomputeStaticResult (and, for IMD files, imdAnalyzer.analyze);
// those live in the already-ported analyzer / imd-analyzer modules and are the
// caller's responsibility - the returned object carries every field they need.
// Preferences-derived inputs (dbvOffsetDb, harmCount, the minimum-fundamental
// floor) come in as explicit arguments rather than via a global, per IoC.

import { FftResult } from '../fft/fft-result.js';

/** .fft format version stamped in the header (FileVersions.FFT_SPECTRUM). */
export const FFT_FORMAT_VERSION = 1;

/** Minimum fundamental frequency the loaded-spectrum peak search starts from
 *  (FftController.LOADED_FUND_MIN_HZ). */
export const LOADED_FUND_MIN_HZ = 10.0;

/**
 * Reconstructed spectrum plus the header hints the caller needs to finish the
 * load (run recomputeStaticResult, and analyze() for IMD files).
 *
 * @typedef {Object} LoadedSpectrum
 * @property {FftResult} result    Bins + fundamental populated; stats NOT yet computed.
 * @property {boolean}   modeImd   True when '# mode=IMD'.
 * @property {number}    tone1Hz   Header tone1_hz, or NaN.
 * @property {number}    tone2Hz   Header tone2_hz, or NaN.
 * @property {number}    dbvOffsetDb  The offset that was applied (echoed back).
 */

/** Parses '# key=value' as a frequency in Hz; NaN when absent/malformed. */
function parseHeaderHz(line) {
  const v = parseFloat(line.substring(line.indexOf('=') + 1).trim());
  return Number.isNaN(v) ? NaN : v;
}

/**
 * Parses a .fft spectrum file. Faithful port of FftController.loadSpectrum: reads
 * the data rows + capture-mode / tone / bin-bandwidth header comments, then
 * reconstructs an {@link FftResult} (freqResolution, fftSize, sampleRate,
 * binBwSqrt, the complex bins from dBV->dBFS->linear, and the fundamental via
 * argmax above LOADED_FUND_MIN_HZ refined to sub-bin) - everything up to the
 * stats recompute, which the caller runs.
 *
 * @param {string} text         Raw file contents.
 * @param {number} dbvOffsetDb  Global ADC offset (prefs.getDbvOffsetDb()); subtracted on load.
 * @param {number} harmCount    Harmonic-array length = max(9, prefs.getFftCalcMaxHarmonic()).
 * @param {(re:Float64Array, im:Float64Array, bin:number, fftSize:number)=>number} parabolicBinInterp
 *        MathUtil.parabolicBinInterp - sub-bin fundamental refinement.
 * @returns {LoadedSpectrum}
 * @throws {Error} When the file holds fewer than 4 rows or a non-positive freq step.
 */
export function loadSpectrum(text, dbvOffsetDb, harmCount, parabolicBinInterp) {
  const rows = [];
  let modeImd = false;
  let tone1Hz = NaN, tone2Hz = NaN, binBwHz = NaN;

  for (const raw of text.split(/\r?\n/)) {
    const s = raw.trim();
    if (s === '') continue;
    if (s.startsWith('# mode=')) {
      modeImd = s.substring(s.indexOf('=') + 1).trim().toUpperCase() === 'IMD';
      continue;
    }
    if (s.startsWith('# tone1_hz=')) { tone1Hz = parseHeaderHz(s); continue; }
    if (s.startsWith('# tone2_hz=')) { tone2Hz = parseHeaderHz(s); continue; }
    if (s.startsWith('# bin_bw_hz=')) { binBwHz = parseHeaderHz(s); continue; }
    const c0 = s.charAt(0);
    if (c0 !== '-' && c0 !== '+' && c0 !== '.' && !(c0 >= '0' && c0 <= '9')) continue;
    const p = s.split(/[;,]/);
    if (p.length < 2) continue;
    const f = parseFloat(p[0].trim());
    const dbv = parseFloat(p[1].trim());
    const ph = p.length >= 3 ? parseFloat(p[2].trim()) : 0.0;
    if (Number.isNaN(f) || Number.isNaN(dbv) || Number.isNaN(ph)) {
      throw new Error('malformed numeric row: ' + s);
    }
    rows.push([f, dbv, ph]);
  }

  const n = rows.length;
  const freqRes = n >= 2 ? (rows[n - 1][0] - rows[0][0]) / (n - 1) : 0.0;
  if (n < 4 || !(freqRes > 0)) throw new Error('not a spectrum file');

  const r = new FftResult();
  r.ensureArrays(n, harmCount);
  r.freqResolution = freqRes;
  // Old files lack bin_bw_hz; the row spacing IS the captured bandwidth there.
  r.binBwSqrt = Math.sqrt(binBwHz > 0 ? binBwHz : freqRes);
  r.fftSize = 2 * (n - 1);
  r.sampleRate = Math.round(freqRes * r.fftSize);
  r.harmonicCount = harmCount;

  for (let k = 0; k < n; k++) {
    const dbv = rows[k][1], ph = rows[k][2];
    const dbFs = dbv - dbvOffsetDb;
    r.amplitudeDbFs[k] = dbFs;
    r.phaseDeg[k] = ph;
    const lin = Math.pow(10, dbFs / 20);
    const rad = ph * Math.PI / 180;
    r.re[k] = lin * Math.cos(rad);
    r.im[k] = lin * Math.sin(rad);
  }

  // Fundamental = strongest bin above ~10 Hz, refined to sub-bin.
  const halfSize = n - 1;
  const minBin = Math.min(halfSize, Math.max(1, Math.ceil(LOADED_FUND_MIN_HZ / freqRes)));
  let fb = minBin;
  for (let k = minBin; k <= halfSize; k++) {
    if (r.amplitudeDbFs[k] > r.amplitudeDbFs[fb]) fb = k;
  }
  r.fundamentalBin = fb;
  r.fundamentalHz = fb * freqRes;
  r.fundamentalHzRefined = parabolicBinInterp(r.re, r.im, fb, r.fftSize) * freqRes;

  // For IMD files the caller pins F1/F2 before measuring products (Java does the
  // same in loadSpectrum); expose the hints so it can.
  if (modeImd && Number.isFinite(tone1Hz) && Number.isFinite(tone2Hz)
      && tone1Hz > 0 && tone2Hz > 0) {
    r.fundamentalHzRefined = Math.min(tone1Hz, tone2Hz);
    r.fundamental2HzRefined = Math.max(tone1Hz, tone2Hz);
  }

  return { result: r, modeImd, tone1Hz, tone2Hz, dbvOffsetDb };
}

/**
 * Provenance fields written into the .fft header. Mirror the values Java pulls
 * from the result + Preferences at save time; all optional.
 *
 * @typedef {Object} FftSaveMeta
 * @property {boolean}  [imd=false]          true -> '# mode=IMD' (else THD).
 * @property {number}   [tone1Hz=0]          Written only when imd.
 * @property {number}   [tone2Hz=0]          Written only when imd.
 * @property {number}   [dbvOffsetDb=0]      Added to every dBFS to make dBV.
 * @property {number}   [binBwHz]            '# bin_bw_hz='; defaults to binBwSqrt² or row spacing².
 * @property {string[]} [calibrationPaths=[]] '# calibration=' lines (append ' (withNoise)' yourself).
 */

/**
 * Serialises an {@link FftResult} as .fft text. Faithful port of
 * FftController.saveSpectrum: the '#'-prefixed provenance header then
 * 'frequency_hz;magnitude_dBV;phase_deg' rows (Locale.US: %.6f, dot decimal).
 * Each bin's dBV = amplitudeDbFs + dbvOffsetDb.
 *
 * @param {FftResult} r
 * @param {FftSaveMeta} [meta={}]
 * @returns {string} The complete file contents (LF line endings).
 */
export function saveSpectrum(r, meta = {}) {
  const {
    imd = false, tone1Hz = 0, tone2Hz = 0, dbvOffsetDb = 0,
    calibrationPaths = [],
  } = meta;

  const lines = [];
  lines.push('# Phonalyser FFT spectrum');
  lines.push('# format_version=' + FFT_FORMAT_VERSION);
  lines.push('# mode=' + (imd ? 'IMD' : 'THD'));
  lines.push('# fft_size=' + r.fftSize);
  lines.push('# sample_rate_hz=' + r.sampleRate);
  lines.push('# window=' + (r.windowType != null ? r.windowType : 'n/a'));
  lines.push('# averaging=' + (r.coherentAveraging ? 'coherent' : 'incoherent'));
  lines.push('# averages=' + r.frameCount);
  if (r.snrFreqMin > 0 || r.snrFreqMax > 0) {
    lines.push('# snr_freq_min_hz=' + r.snrFreqMin.toFixed(3));
    lines.push('# snr_freq_max_hz=' + r.snrFreqMax.toFixed(3));
  }
  if (Number.isFinite(r.fundamentalTrueDbFs)) {
    lines.push('# manual_fund_dbv=' + (r.fundamentalTrueDbFs + dbvOffsetDb).toFixed(4));
  }
  lines.push('# thd_max_harmonic=' + r.harmonicCount);
  for (const path of calibrationPaths) lines.push('# calibration=' + path);

  // bin_bw_hz: from the result's captured √bw (re-opened files) or the supplied
  // live-config value. Matches Java: bwSqrt² with %.9f.
  const bwSqrt = r.binBwSqrt != null
    ? r.binBwSqrt
    : (meta.binBwHz != null ? Math.sqrt(meta.binBwHz) : 0);
  lines.push('# bin_bw_hz=' + (bwSqrt * bwSqrt).toFixed(9));

  if (imd) {
    lines.push('# tone1_hz=' + tone1Hz.toFixed(6));
    lines.push('# tone2_hz=' + tone2Hz.toFixed(6));
  }
  lines.push('frequency_hz;magnitude_dBV;phase_deg');

  const len = r.amplitudeDbFs.length;
  for (let k = 0; k < len; k++) {
    const f = k * r.freqResolution;
    const dbv = r.amplitudeDbFs[k] + dbvOffsetDb;
    const ph = (r.phaseDeg != null && k < r.phaseDeg.length) ? r.phaseDeg[k] : 0;
    lines.push(f.toFixed(6) + ';' + dbv.toFixed(6) + ';' + ph.toFixed(6));
  }
  return lines.join('\n') + '\n';
}
