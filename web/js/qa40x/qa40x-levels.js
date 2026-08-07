/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.sound.qa40x.Qa40xLevels.
//
// QA402/QA403 raw <-> volts conversions and per-range full-scale voltages (see
// doc/QA40X-PROTOCOL.md §6). Pure and stateless - the caller supplies the
// selected range dBV and the matching on-device linear cal factor (10^(dB/20),
// from Qa40xCalibration).
//
// RANGE SEMANTICS (doc §6 "Levels cheat-sheet")
// The two directions' "dBV" range labels are NOT the same unit:
//   - Output label = genuine RMS dBV, per single leg. A sine of maxOutputDbv
//     dBV RMS on one leg reaches full scale. The balanced (Out+ − Out−) is
//     +6 dB, but that is the wire, not the sample.
//   - Input label is really a dBFS / peak-to-peak reference. The N "dBV" input
//     range clips (0 dBFS) at 10^(N/20) Vpp DIFFERENTIAL, so the true RMS full
//     scale is N − 9 dB: a constant 9 dB below the label = +3 (peak/RMS, √2)
//     plus +6 (differential ×2 - the vendor "−6 dB differential-ADC" term).
// This supersedes the earlier reading that the label is RMS full scale in both
// directions: a digital mock loopback shows no differential doubling, so it
// could not surface the input's 9 dB offset.
//
// LOAD-BEARING CONVENTIONS
//   - ADC: adcVolts = raw/MAXINT · cal · 10^(N/20) / 2 - the range N is
//     20·log10(Vpp clip), so 10^(N/20) is the peak-to-peak clip and the /2 makes
//     it the peak (raw/MAXINT is the sample normalised to [-1, 1]). A full-scale
//     sine then has RMS = peak/√2 = cal · 10^(N/20)/(2·√2), i.e. ≈ N − 9 dBV.
//   - DAC input is PEAK volts, not RMS (vendor math, unchanged):
//     dacInt32 = peakVolts · cal · 10^(-(maxOutputDbv+3)/20) · MAXINT. The +3
//     closes the dBFS-peak vs dBV-RMS gap ONLY because the incoming amplitude is
//     already peak (RMS·√2 ≈ +3.01 dB). Feeding an RMS-scaled value here lands
//     ~3 dB low (§9 item 12).
//
// PER-RANGE EFFECTIVE FULL-SCALE RMS
// Full scale is the sample reaching ±MAXINT. Output lands at
// ≈ 10^(maxOutputDbv/20) - the per-leg label as an RMS voltage; input lands 9 dB
// lower, at ≈ 10^((maxInputDbv−9)/20), because the input label is a
// Vpp-differential (dBFS) reference, not RMS dBV. Both cal-corrected:
//   - Input:  inputFullScaleRmsVolts  = cal · 10^(N/20) / (2·√2)
//                                     ≈ cal · 10^((maxInputDbv−9)/20)  (Vpp -> peak -> RMS)
//   - Output: outputFullScaleRmsVolts = 10^((maxOutputDbv+3)/20) / (cal · √2)
// These feed the QA40x device card's per-range full-scale voltages; keep the
// on-device cal factor and any Phonalyser .frc correction as separate,
// composable multipliers (§10).

/** Full-scale magnitude of the 32-bit samples: 2^31 - 1 (§6). */
export const MAXINT = 2147483647;

/** Peak-vs-RMS (√2) term: 0 dBFS is a peak limit, dBV is RMS. The output folds
 *  it into the per-leg full scale; the input applies it via SQRT2. */
const PEAK_TO_RMS_DB = 3.0;

/** Peak-to-peak -> peak divisor for the INPUT. The "N dBV" input range is a
 *  Vpp-differential (dBFS) reference - 10^(N/20) is the peak-to-peak clip - so
 *  halving it gives the peak amplitude. An EXACT factor of 2 (6.02 dB from the
 *  balanced In+ − In− = 2×), not a rounded 6 dB (doc §6 cheat-sheet). */
const VPP_TO_PEAK = 2.0;

/** Volts-to-dB divisor (20·log10). */
const DB_DIVISOR = 20.0;

const SQRT2 = Math.sqrt(2.0);

function dbToLinear(db) {
  return Math.pow(10.0, db / DB_DIVISOR);
}

/**
 * The peak instantaneous voltage a full-scale sample reaches on an input range,
 * the single source the ADC read and the RMS full scale both build on. The range
 * N is 20·log10(Vpp clip differential), so 10^(N/20) is that peak-to-peak clip
 * and halving it (VPP_TO_PEAK) gives the peak: adcCal · 10^(N/20) / 2.
 *
 * @param {number} maxInputDbv selected input range label, in "dBV"
 * @param {number} adcCal on-device linear ADC cal factor
 * @returns {number} peak volts at full scale
 */
function inputFullScalePeakVolts(maxInputDbv, adcCal) {
  return adcCal * dbToLinear(maxInputDbv) / VPP_TO_PEAK;
}

/**
 * Converts a raw ADC sample to instantaneous (differential) volts:
 * raw/MAXINT · inputFullScalePeakVolts. raw/MAXINT is the sample normalised to
 * [-1, 1], so full scale (±1) is the peak of the range's Vpp-differential clip;
 * a full-scale sine then reads ≈ (N − 9) dBV RMS (doc §6; see the notes above).
 *
 * @param {number} raw raw 32-bit sample
 * @param {number} maxInputDbv selected input range label, in "dBV"
 * @param {number} adcCal on-device linear ADC cal factor
 * @returns {number} instantaneous volts
 */
export function adcVolts(raw, maxInputDbv, adcCal) {
  return (raw / MAXINT) * inputFullScalePeakVolts(maxInputDbv, adcCal);
}

/**
 * Converts a PEAK output voltage to a 32-bit DAC sample:
 * round(peakVolts · dacCal · 10^(-(maxOutputDbv+3)/20) · MAXINT), saturated to
 * ±MAXINT (§6). peakVolts MUST be peak amplitude (RMS·√2) - see the notes above.
 *
 * @param {number} peakVolts PEAK output volts (not RMS)
 * @param {number} maxOutputDbv selected output range label, in dBV
 * @param {number} dacCal on-device linear DAC cal factor
 * @returns {number} the 32-bit sample, saturated to ±MAXINT
 */
export function dacInt32(peakVolts, maxOutputDbv, dacCal) {
  const scaled = peakVolts * dacCal * dbToLinear(-(maxOutputDbv + PEAK_TO_RMS_DB)) * MAXINT;
  const rounded = Math.round(scaled);
  if (rounded > MAXINT) {
    return MAXINT;
  }
  if (rounded < -MAXINT) {
    return -MAXINT;
  }
  return rounded;
}

/**
 * Effective full-scale RMS input voltage for a range: the peak clip
 * (inputFullScalePeakVolts) brought to RMS, peak / √2. Lands at
 * adcCal · 10^(N/20) / (2·√2) ≈ adcCal · 10^((maxInputDbv−9)/20), ≈ 9 dB below
 * the "N dBV" label (see the notes above).
 *
 * @param {number} maxInputDbv selected input range label, in "dBV"
 * @param {number} adcCal on-device linear ADC cal factor
 * @returns {number} full-scale RMS volts
 */
export function inputFullScaleRmsVolts(maxInputDbv, adcCal) {
  return inputFullScalePeakVolts(maxInputDbv, adcCal) / SQRT2;
}

/**
 * Effective full-scale RMS output voltage for a range:
 * 10^((maxOutputDbv+3)/20) / (dacCal · √2) ≈ the label as an RMS voltage,
 * cal-corrected (see the notes above).
 *
 * @param {number} maxOutputDbv selected output range label, in dBV
 * @param {number} dacCal on-device linear DAC cal factor
 * @returns {number} full-scale RMS volts
 */
export function outputFullScaleRmsVolts(maxOutputDbv, dacCal) {
  return dbToLinear(maxOutputDbv + PEAK_TO_RMS_DB) / (dacCal * SQRT2);
}
