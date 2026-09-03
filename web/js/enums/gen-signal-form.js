/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.enums.GenSignalForm - the waveform identifiers plus
// the enum's own predicates and parser as functions taking the value.

/**
 * Waveform identifiers - string-valued so they survive structured-clone across
 * the worklet message port. Values match the Java enum names.
 * @enum {string}
 */
export const GenSignalForm = Object.freeze({
  SINE: 'SINE',
  SINE_COMP: 'SINE_COMP',
  DUAL_TONE: 'DUAL_TONE',
  DUAL_TONE_COMP: 'DUAL_TONE_COMP',
  TRIANGLE: 'TRIANGLE',
  RECTANGLE: 'RECTANGLE',
  WHITE_NOISE: 'WHITE_NOISE',
  PINK_NOISE: 'PINK_NOISE',
  PINK_NOISE_LINEAR: 'PINK_NOISE_LINEAR',
  LINEAR_SWEEP: 'LINEAR_SWEEP',
  LOG_SWEEP: 'LOG_SWEEP',
});

/**
 * True for the two-tone waveforms (DUAL_TONE / DUAL_TONE_COMP) - GenSignalForm.isDualTone.
 * @param {string} form
 * @returns {boolean}
 */
export function isDualTone(form) {
  return form === GenSignalForm.DUAL_TONE || form === GenSignalForm.DUAL_TONE_COMP;
}

/**
 * True for the two PREDISTORTED waveforms (SINE_COMP / DUAL_TONE_COMP) - GenSignalForm
 * .isCompensated. Each is the compensated sibling of a plain form that renders the same tones
 * from the same DDS state, differing only in that a correction table is subtracted; the pair
 * therefore shares an RMS and switches without a rebuild. Drives every "does this form need the
 * correction tables loaded?" branch.
 * @param {string} form
 * @returns {boolean}
 */
export function isCompensated(form) {
  return form === GenSignalForm.SINE_COMP || form === GenSignalForm.DUAL_TONE_COMP;
}

/**
 * True for every waveform that has a meaningful frequency - i.e. all forms EXCEPT the
 * three noise sources (the generator's Frequency field is disabled, but still visible, for
 * noise).
 * @param {string} form
 * @returns {boolean}
 */
export function isPeriodic(form) {
  return form !== GenSignalForm.WHITE_NOISE
      && form !== GenSignalForm.PINK_NOISE
      && form !== GenSignalForm.PINK_NOISE_LINEAR;
}

/**
 * Parses a textual signal-form name (CLI aliases included), mirroring
 * GenSignalForm.fromString. Throws on an unknown name.
 * @param {string} s
 * @returns {string} one of {@link GenSignalForm}
 */
export function formFromString(s) {
  switch (String(s).toLowerCase()) {
    case 'sine': return GenSignalForm.SINE;
    case 'sine_compensated': case 'sine_hmc': return GenSignalForm.SINE_COMP;
    case 'triangle': case 'tri': return GenSignalForm.TRIANGLE;
    case 'rectangle': case 'rect': case 'square': case 'pulse': return GenSignalForm.RECTANGLE;
    case 'white': case 'white_noise': return GenSignalForm.WHITE_NOISE;
    case 'pink': case 'pink_noise': return GenSignalForm.PINK_NOISE;
    case 'pink_linear': case 'pink_noise_linear': return GenSignalForm.PINK_NOISE_LINEAR;
    case 'linear_sweep': case 'sweep': case 'chirp': return GenSignalForm.LINEAR_SWEEP;
    case 'log_sweep': case 'farina': return GenSignalForm.LOG_SWEEP;
    case 'dual_tone': case 'dualtone': case 'twotone': case 'two_tone': return GenSignalForm.DUAL_TONE;
    case 'dual_tone_compensated': case 'dualtone_hmc': case 'twotone_hmc': return GenSignalForm.DUAL_TONE_COMP;
    default:
      throw new Error('Unknown signal form: ' + s +
        '. Valid: sine, sine_compensated, triangle, rectangle, white_noise, pink_noise, ' +
        'pink_noise_linear, linear_sweep, log_sweep, dual_tone, dual_tone_compensated');
  }
}
