/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.dsp.SineKernel.
//
// Shared table-plus-Taylor sine/cosine kernel - the DDS generator's exact
// evaluation scheme, factored out so every precision-critical sinusoid in the
// code base (the generator's waveforms, the oscilloscope residual's fitted
// reference) is computed by the SAME math.
//
// A full-wave 4096-entry lookup supplies the coarse angle theta0; the remaining
// fractional phase dTheta (at most 2*pi/4096, about 1.5e-3 rad) is corrected by
// a short Taylor series through the angle-addition identities:
//   sin(theta0 + dTheta) = sin(theta0)*cos(dTheta) + cos(theta0)*sin(dTheta)
// At this table size the 5th order already truncates at about 2e-20 of full
// scale (about -390 dBc), far below double precision; the 7th and 9th orders are
// headroom (and room to shrink the table).
//
// ORDER SWITCH - uncomment exactly ONE order in EACH helper, keeping the two
// helpers at the same order.
//
// WEB ADAPTATION - the entry points take TURNS, not a 64-bit phase accumulator.
// JS has no 64-bit integer in the shape the desktop oscillator uses, so the web
// generator carries the accumulator as a hi:lo word pair and converts it to a
// turn (dds-kernel phaseTurn53) before evaluating; Java's sinAtPhaseAcc /
// cosAtPhaseAcc therefore have no separate counterpart here - the turns pair
// below is what BOTH call sites use.

const TABLE_BITS = 12;
const TABLE_SIZE = 1 << TABLE_BITS;   // 4096
/** Radians per table step - the largest dTheta the Taylor helpers ever see. */
const STEP_RAD = (2.0 * Math.PI) / TABLE_SIZE;

const SINE_TABLE = new Float64Array(TABLE_SIZE);
const COS_TABLE = new Float64Array(TABLE_SIZE);
for (let i = 0; i < TABLE_SIZE; i++) {
  const angle = (2.0 * Math.PI * i) / TABLE_SIZE;
  SINE_TABLE[i] = Math.sin(angle);
  COS_TABLE[i] = Math.cos(angle);
}

/** cos(dTheta) Taylor series; dx2 = dTheta squared. Valid for the small
 *  fractional angles of the table split (|dTheta| <= STEP_RAD).
 *  @param {number} dx2
 *  @returns {number} */
export function taylorCos(dx2) {
  // 5th order - cos to dTheta^4:
  return 1.0 + dx2 * (-0.5 + dx2 * (1.0 / 24.0));
  // 7th order - cos to dTheta^6:
  // return 1.0 + dx2 * (-0.5 + dx2 * (1.0 / 24.0 + dx2 * (-1.0 / 720.0)));
  // 9th order - cos to dTheta^8:
  // return 1.0 + dx2 * (-0.5 + dx2 * (1.0 / 24.0 + dx2 * (-1.0 / 720.0 + dx2 * (1.0 / 40320.0))));
}

/** sin(dTheta) Taylor series; dx = dTheta, dx2 = dTheta squared. Valid for the
 *  small fractional angles of the table split.
 *  @param {number} dx
 *  @param {number} dx2
 *  @returns {number} */
export function taylorSin(dx, dx2) {
  // 5th order - sin to dTheta^5:
  return dx * (1.0 + dx2 * (-1.0 / 6.0 + dx2 * (1.0 / 120.0)));
  // 7th order - sin to dTheta^7:
  // return dx * (1.0 + dx2 * (-1.0 / 6.0 + dx2 * (1.0 / 120.0 + dx2 * (-1.0 / 5040.0))));
  // 9th order - sin to dTheta^9:
  // return dx * (1.0 + dx2 * (-1.0 / 6.0 + dx2 * (1.0 / 120.0 + dx2 * (-1.0 / 5040.0 + dx2 * (1.0 / 362880.0)))));
}

/**
 * Sine of an angle given in TURNS (1.0 = one full cycle; any real value,
 * negatives included). For a sinusoid synthesized per sample as
 * sinTurns(k * f/fs) the phase comes from ONE multiply, so there is no
 * recurrence drift over arbitrarily long spans.
 *
 * The cell index is masked to the table width. Java indexes the array directly
 * and would throw on the one degenerate case; here a tiny negative turn count
 * can leave `turns - floor(turns)` rounding to exactly 1.0, whose scaled cell is
 * TABLE_SIZE - the mask folds that to cell 0, where the fractional remainder is
 * 0 anyway, so the value is the correct sin(0). The remainder is taken against
 * the UNMASKED cell for exactly that reason.
 *
 * @param {number} turns phase in turns.
 * @returns {number} sin(2*pi*turns)
 */
export function sinTurns(turns) {
  const t = turns - Math.floor(turns);          // [0, 1)
  const scaled = t * TABLE_SIZE;
  const cell = scaled | 0;
  const idx = cell & (TABLE_SIZE - 1);
  const dx = (scaled - cell) * STEP_RAD;
  const dx2 = dx * dx;
  return SINE_TABLE[idx] * taylorCos(dx2) + COS_TABLE[idx] * taylorSin(dx, dx2);
}

/**
 * Cosine sibling of {@link sinTurns}:
 * cos(theta0 + dTheta) = cos(theta0)*cos(dTheta) - sin(theta0)*sin(dTheta).
 * @param {number} turns phase in turns.
 * @returns {number} cos(2*pi*turns)
 */
export function cosTurns(turns) {
  const t = turns - Math.floor(turns);
  const scaled = t * TABLE_SIZE;
  const cell = scaled | 0;
  const idx = cell & (TABLE_SIZE - 1);
  const dx = (scaled - cell) * STEP_RAD;
  const dx2 = dx * dx;
  return COS_TABLE[idx] * taylorCos(dx2) - SINE_TABLE[idx] * taylorSin(dx, dx2);
}
