/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of the FreqResp result shapes:
//   - org.edgo.audio.measure.gui.freqresp.FreqRespResult - one channel's measured
//     magnitude/phase curve on a common freq grid, plus the channel id, sample rate,
//     the sweep params it came from, an optional source file path, and the
//     "calibration already baked in" flag (true for a loaded .frc, false for a live
//     sweep - so the view's render-time applyCurrentCalibration knows not to
//     double-correct a loaded file).
//   - org.edgo.audio.measure.gui.freqresp.StereoFreqRespResult - the bundle of both
//     channels from a single stereo capture + deconvolution pass.
//
// These are plain object factories (the desktop records carry no behaviour beyond
// their accessors); the view reads .freqs / .magLin / .phaseRad / .channel /
// .sampleRate / .sweepParams / .calibrationApplied directly.

/**
 * One channel's frequency-response result. Mirrors FreqRespResult.
 *
 * @param {'L'|'R'} channel        which ADC channel produced this curve
 * @param {number}  sampleRate     capture sample rate (Hz)
 * @param {Float64Array} freqs     strictly-ascending log-spaced output grid (Hz)
 * @param {Float64Array} magLin    |H| linear, passband normalised to ~1.0
 * @param {Float64Array} phaseRad  phase (radians)
 * @param {object|null}  sweepParams  the sweep params (start/stop/points/durationSec/leadInSec/...) or null
 * @param {string|null}  sourceFilePath  loaded-file path, or null for a live sweep
 * @param {boolean}      calibrationApplied  true when the calibration division is already baked in
 * @returns {object} the FreqRespResult-shaped record
 */
export function makeFreqRespResult(
  channel, sampleRate, freqs, magLin, phaseRad,
  sweepParams = null, sourceFilePath = null, calibrationApplied = false,
) {
  return { channel, sampleRate, freqs, magLin, phaseRad, sweepParams, sourceFilePath, calibrationApplied };
}

/**
 * Normalised sample magnitude at which the capture counts as CLIPPED (Java
 * StereoFreqRespResult.CLIP_THRESHOLD_LIN). Not 1.0: a converter at full scale reports its
 * top code, and the deconvolution's smooth curve hides the flat tops completely - so the
 * measurement looks perfect and is not.
 */
export const CLIP_THRESHOLD_LIN = 0.9995;

/**
 * Bundle of both channels' results from one stereo pass. Mirrors StereoFreqRespResult - both
 * sides non-null for production runs.
 *
 * @param {object} left  the L-channel FreqRespResult
 * @param {object} right the R-channel FreqRespResult
 * @param {number} rawPeakLin the largest |sample| seen in the RAW capture, either channel,
 *        normalised - measured before any deconvolution, because that is the only place the
 *        clipping is still visible
 * @returns {{left: object, right: object, rawPeakLin: number, clipped: () => boolean}}
 */
export function makeStereoResult(left, right, rawPeakLin = 0) {
  return {
    left,
    right,
    rawPeakLin,
    /** True when the capture reached full scale - the recorded signal is clipped, so this
     *  response is distorted however smooth the curve looks. */
    clipped() { return this.rawPeakLin >= CLIP_THRESHOLD_LIN; },
  };
}
