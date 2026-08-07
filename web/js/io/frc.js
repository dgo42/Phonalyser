/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of the .frc store: org.edgo.audio.measure.dsp.FreqRespCalHelper
// saveFrc / loadFrc (the on-disk filter-calibration format) plus the in-memory
// shapes from FreqRespCalibration / StereoFreqRespCalibration.
//
// The .frc file is a comma-separated, dot-decimal text file: header comment
// lines prefixed with '#' carrying measurement provenance, one data header
// line, then one row per sweep point with five columns:
//   frequency_hz, mag_left_dB, mag_right_dB, phase_left_deg, phase_right_deg
// Magnitudes are RELATIVE dB (ADC/DAC ratio, flat passband at 0 dB). The loader
// converts dB -> linear (10^(dB/20)) and degrees -> radians; the writer does the
// inverse, clamping non-positive magnitudes to -300 dB.

/** .frc format version stamped in the header (FileVersions.FRC_CALIBRATION). */
export const FRC_FORMAT_VERSION = 1;

/** Header key for the capture sample rate, written by {@link saveFrc} and read
 *  back by {@link readSampleRateHz} (FreqRespCalHelper.SAMPLE_RATE_HEADER_KEY). */
const SAMPLE_RATE_HEADER_KEY = 'sample_rate_hz';

/**
 * One channel's filter calibration on a common frequency grid. Mirrors
 * org.edgo.audio.measure.dsp.FreqRespCalibration: all three arrays share length
 * N, freqs strictly ascending, magLin linear (passband ≈ 1.0), phaseRad radians.
 *
 * @typedef {Object} FreqRespCalibration
 * @property {Float64Array} freqs
 * @property {Float64Array} magLin
 * @property {Float64Array} phaseRad
 */

/**
 * Stereo pair of calibrations (one per ADC channel). Mirrors
 * StereoFreqRespCalibration; both sides share the same freqs array length.
 *
 * @typedef {Object} StereoFreqRespCalibration
 * @property {FreqRespCalibration} left
 * @property {FreqRespCalibration} right
 */

/**
 * Optional provenance written into the .frc header comments. All fields default
 * to 0 when omitted (matching how the CLI/GUI populate them).
 *
 * @typedef {Object} FrcSaveMeta
 * @property {number} [sampleRate=0]
 * @property {number} [sweepStart=0]
 * @property {number} [sweepEnd=0]
 * @property {number} [sweepPoints=0]
 * @property {number} [amplitudeVRms=0]
 */

/**
 * Serialises a stereo filter calibration to .frc text. Faithful port of
 * FreqRespCalHelper.saveFrc: writes the '#'-prefixed header block then the
 * 5-column data rows (Locale.US formatting: dot decimal, %.6f freq/mag, %.4f
 * phase). Magnitudes ≤ 0 are written as -300 dB.
 *
 * @param {StereoFreqRespCalibration} stereo
 * @param {FrcSaveMeta} [meta={}]
 * @returns {string} The complete file contents (LF line endings).
 * @throws {Error} When either channel is missing.
 */
export function saveFrc(stereo, meta = {}) {
  if (!stereo || !stereo.left || !stereo.right) {
    throw new Error('stereo calibration with both channels is required');
  }
  const {
    sampleRate = 0, sweepStart = 0, sweepEnd = 0,
    sweepPoints = 0, amplitudeVRms = 0,
  } = meta;
  const left = stereo.left, right = stereo.right;
  const n = Math.min(left.freqs.length, right.freqs.length);

  const lines = [];
  lines.push('# kind=filter_calibration');
  lines.push('# format_version=' + FRC_FORMAT_VERSION);
  lines.push('# ' + SAMPLE_RATE_HEADER_KEY + '=' + Math.trunc(sampleRate));
  lines.push('# sweep_start_hz=' + sweepStart.toFixed(6));
  lines.push('# sweep_end_hz=' + sweepEnd.toFixed(6));
  lines.push('# sweep_points=' + Math.trunc(sweepPoints));
  lines.push('# dac_drive_v_rms=' + amplitudeVRms.toFixed(6));
  lines.push('frequency_hz,mag_left_dB,mag_right_dB,phase_left_deg,phase_right_deg');
  for (let i = 0; i < n; i++) {
    const magL = left.magLin[i], magR = right.magLin[i];
    const dbL = magL > 0 ? 20 * Math.log10(magL) : -300;
    const dbR = magR > 0 ? 20 * Math.log10(magR) : -300;
    const phaseL = left.phaseRad[i] * 180 / Math.PI;
    const phaseR = right.phaseRad[i] * 180 / Math.PI;
    lines.push(
      left.freqs[i].toFixed(6) + ',' + dbL.toFixed(6) + ',' + dbR.toFixed(6)
      + ',' + phaseL.toFixed(4) + ',' + phaseR.toFixed(4));
  }
  return lines.join('\n') + '\n';
}

/**
 * Parses a .frc file written by {@link saveFrc}. Faithful port of
 * FreqRespCalHelper.loadFrc: skips '#' comments and the column header, accepts
 * comma- or legacy semicolon-separated rows, tolerates a comma decimal mark per
 * field, and converts dB -> linear and degrees -> radians.
 *
 * @param {string} text  Raw file contents.
 * @returns {StereoFreqRespCalibration}
 * @throws {Error} When no data rows are present.
 */
export function loadFrc(text) {
  const rows = [];
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (line === '' || line.startsWith('#')) continue;
    const c0 = line.charAt(0);
    if (!(c0 >= '0' && c0 <= '9') && c0 !== '-') continue;
    const cols = line.includes(',') ? line.split(',') : line.split(';');
    if (cols.length < 5) continue;
    const num = (s) => parseFloat(s.trim().replace(',', '.'));
    const freq = num(cols[0]);
    const dbL = num(cols[1]);
    const dbR = num(cols[2]);
    const phaseL = num(cols[3]) * Math.PI / 180;
    const phaseR = num(cols[4]) * Math.PI / 180;
    rows.push([freq, Math.pow(10, dbL / 20), phaseL, Math.pow(10, dbR / 20), phaseR]);
  }
  if (rows.length === 0) throw new Error('Filter calibration file has no data rows');

  const n = rows.length;
  const freqs = new Float64Array(n);
  const magL = new Float64Array(n), phaseL = new Float64Array(n);
  const magR = new Float64Array(n), phaseR = new Float64Array(n);
  for (let i = 0; i < n; i++) {
    const r = rows[i];
    freqs[i] = r[0];
    magL[i] = r[1]; phaseL[i] = r[2];
    magR[i] = r[3]; phaseR[i] = r[4];
  }
  return {
    left: { freqs, magLin: magL, phaseRad: phaseL },
    right: { freqs, magLin: magR, phaseRad: phaseR },
  };
}

/**
 * Reads the capture sample rate recorded in a .frc file's leading
 * '# sample_rate_hz=' header comment (written by {@link saveFrc}). Only the
 * header block is scanned - the scan stops at the first non-comment line.
 * Returns 0 when the header is absent or unparseable (legacy files), so callers
 * can fall back. Faithful port of FreqRespCalHelper.readSampleRateHz - the web
 * takes the already-decoded file TEXT (no OS paths in the browser).
 *
 * @param {string} text  Raw file contents.
 * @returns {number} The header sample rate, or 0 when absent / garbled.
 */
export function readSampleRateHz(text) {
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (line === '') continue;
    if (!line.startsWith('#')) break;   // header block ended
    const eq = line.indexOf('=');
    if (eq > 0 && line.substring(1, eq).trim() === SAMPLE_RATE_HEADER_KEY) {
      const v = parseInt(line.substring(eq + 1).trim(), 10);
      return Number.isNaN(v) ? 0 : v;   // garbled header - treat as absent
    }
  }
  return 0;
}
