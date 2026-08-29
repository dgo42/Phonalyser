/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.enums.FftOverlap: the overlap fraction
// between successive FFT frames, plus the frame-spacing derivations the enum
// itself owns. Higher overlap speeds up the analyser update rate at the cost of
// CPU.
//
// The web has no Java enum, so a constant is a plain token string (the enum NAME,
// which is what Preferences persists and what the .fft header records) and the
// per-constant state lives in the tables below. The three hop derivations are
// exported as functions taking the token - the JS shape of the Java instance
// methods.

/** The FftOverlap constants, in declaration order (the combo's order). */
export const FFT_OVERLAP_VALUES = ['PCT_0', 'PCT_50', 'PCT_75', 'PCT_87_5', 'PCT_93_75', 'RAND'];

/** Overlap fraction per constant. RAND carries no single fraction - every
 *  consumer resolves the effective spacing through the drawn hop, or through
 *  {@link minHopSamples} / {@link maxHopSamples} where one bounding number is
 *  needed - so its entry is NaN, exactly as the Java constant is. */
export const OVERLAP_FRACTION = {
  PCT_0: 0.0, PCT_50: 0.5, PCT_75: 0.75, PCT_87_5: 0.875, PCT_93_75: 0.9375, RAND: NaN,
};

/** Display label per constant (the Java enum's `label` field): the combo entry,
 *  the settings tile chip and the .dpd header text all read it. */
export const OVERLAP_LABEL = {
  PCT_0: '0%', PCT_50: '50%', PCT_75: '75%', PCT_87_5: '87.5%', PCT_93_75: '93.75%',
  RAND: 'Random',
};

/** The random mode's draw range, in overlap percent. A wider overlap leaves less
 *  fresh signal per hop, so the MAXIMUM percent yields the SMALLEST hop. The
 *  label deliberately carries no numbers - these can change. */
export const RAND_MIN_OVERLAP_PCT = 90.625;
export const RAND_MAX_OVERLAP_PCT = 93.75;

/** The random draw's lattice: the hop range splits into this many equal parts
 *  and every drawn hop lands on a part boundary, so the realized hop sizes take
 *  at most this many distinct values (4096-sample spacing at a 1M frame, 32 at
 *  8k). Discrete sizes keep hop-vs-reading statistics cleanly groupable. */
export const RAND_HOP_STEPS = 16;

/** True for the random mode - the one constant whose fraction is not a usable
 *  number.
 *  @param {string} overlap FftOverlap token.
 *  @returns {boolean} */
export function isRandom(overlap) {
  return overlap === 'RAND';
}

/** Frame spacing in samples at `fftSize` for a fixed overlap. The random mode
 *  has no single spacing - each hop is drawn per tick from its own range - so
 *  asking it for one is a caller error.
 *  @param {string} overlap FftOverlap token.
 *  @param {number} fftSize FFT frame length.
 *  @returns {number} the spacing in samples.
 *  @throws {Error} for the random mode. */
export function hopSamples(overlap, fftSize) {
  if (isRandom(overlap)) {
    throw new Error('Random overlap carries no fixed frame spacing - the hop is drawn per tick');
  }
  const fraction = OVERLAP_FRACTION[overlap];
  return Math.max(1, Math.round(fftSize * (1.0 - (fraction === undefined ? 0.0 : fraction))));
}

/** Smallest spacing this mode can produce at `fftSize`: the fixed hop, or the
 *  random range's densest spacing (its MAXIMUM overlap percent). The noise-skirt
 *  walk sizes its look-ahead from this - the averaged floor's texture is assumed
 *  as coarse as the densest spacing can make it, because random hopping is not
 *  credited with decorrelating it.
 *  @param {string} overlap FftOverlap token.
 *  @param {number} fftSize FFT frame length.
 *  @returns {number} the spacing in samples. */
export function minHopSamples(overlap, fftSize) {
  return isRandom(overlap)
    ? Math.max(1, Math.round(fftSize * (1.0 - RAND_MAX_OVERLAP_PCT / 100.0)))
    : hopSamples(overlap, fftSize);
}

/** Largest spacing this mode can produce at `fftSize`: the fixed hop, or the
 *  random range's sparsest spacing (its MINIMUM overlap percent). Sizes every
 *  buffer that must hold a hop of any drawn size without reallocating
 *  mid-gather.
 *  @param {string} overlap FftOverlap token.
 *  @param {number} fftSize FFT frame length.
 *  @returns {number} the spacing in samples. */
export function maxHopSamples(overlap, fftSize) {
  return isRandom(overlap)
    ? Math.max(1, Math.round(fftSize * (1.0 - RAND_MIN_OVERLAP_PCT / 100.0)))
    : hopSamples(overlap, fftSize);
}
