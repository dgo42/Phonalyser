/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.sound.qa40x.Qa40xCalibration.
//
// The QA402/QA403 factory calibration page - a 512-byte blob read from the
// device, parsed into per-range per-channel linear correction factors (see
// doc/QA40X-PROTOCOL.md §6). Immutable; build it with fromBlob() from an
// already-assembled page, or fromTransport() to run the read procedure and parse
// in one step.
//
// READ PROCEDURE (§6)
// Write REG_CAL_PAGE_SELECT = CAL_PAGE_SELECT_VALUE, then read REG_CAL_READ
// exactly CAL_READ_COUNT times; each read yields one 32-bit word that is
// re-packed LITTLE-endian into the growing 512-byte page. (Register frames
// themselves are big-endian - that endianness lives in the transport, not here.)
//
// BLOB LAYOUT (§6)
// Records are (int16 level, float32 dB) little-endian, 6 bytes each; the float is
// a dB correction giving a linear factor 10^(dB/20). Per range the Left record
// sits at its offset and the Right record at offset + 6. ADC (input) left records
// start at 24 keyed by input dBV 0,6,...,42; DAC (output) left records start at 120
// keyed by output dBV -12,-2,8,18; consecutive ranges are 12 bytes apart.
//
// WEB DEVIATION: fromTransport() is async. The Java transport's registerWrite /
// registerRead are blocking bulk transfers; the web transport rides WebUSB, whose
// transfers are promises. Every call is awaited, so a synchronous test double
// works unchanged (awaiting a non-promise yields it directly).

import {
  CAL_PAGE_SELECT_VALUE,
  REG_CAL_PAGE_SELECT,
  REG_CAL_READ,
  inputRangeCode,
  inputRangeDbvValues,
  outputRangeCode,
  outputRangeDbvValues,
} from './qa40x-protocol.js';

/** Bytes in the whole factory cal page (§6). */
export const CAL_PAGE_BYTES = 512;
/** Register reads that assemble the page: 512 / 4 (§6). */
export const CAL_READ_COUNT = 128;

/** Bytes per (int16, float32) record; also the Left->Right stride. */
const RECORD_BYTES = 6;
/** Bytes between consecutive ranges' Left records (Left + Right). */
const RANGE_STRIDE = 2 * RECORD_BYTES;
/** Byte offset of the first (0 dBV) ADC Left record. */
const ADC_BASE_OFFSET = 24;
/** Byte offset of the first (-12 dBV) DAC Left record. */
const DAC_BASE_OFFSET = 120;
/** Bytes of int16 level preceding the float32 dB in a record. */
const LEVEL_BYTES = 2;
/** Bytes per 32-bit word of the assembled page - one register read each. */
const WORD_BYTES = 4;
const DB_DIVISOR = 20.0;

/** Little-endian flag for the DataView accessors - the page's own byte order (§6). */
const LITTLE_ENDIAN = true;

/**
 * Decodes one (int16 level, float32 dB) record to its linear factor 10^(dB/20).
 * The int16 level is skipped; only the dB correction that follows it is read.
 *
 * @param {DataView} page   view over the whole 512-byte cal page
 * @param {number} offset   byte offset of the record's first byte
 * @returns {number} linear correction factor
 */
function decodeLinearFactor(page, offset) {
  const db = page.getFloat32(offset + LEVEL_BYTES, LITTLE_ENDIAN);
  return Math.pow(10.0, db / DB_DIVISOR);
}

export class Qa40xCalibration {

  /**
   * Parses a 512-byte cal page. Java keeps this constructor private - use
   * fromBlob() / fromTransport() rather than calling it directly.
   *
   * @param {Uint8Array} blob the assembled cal page, at least CAL_PAGE_BYTES long
   */
  constructor(blob) {
    if (blob == null || blob.length < CAL_PAGE_BYTES) {
      throw new Error(`cal page must be at least ${CAL_PAGE_BYTES} bytes`);
    }
    const page = new DataView(blob.buffer, blob.byteOffset, blob.byteLength);
    const inputDbv = inputRangeDbvValues();
    const outputDbv = outputRangeDbvValues();
    /** Input-range linear factors, indexed by reg-0x05 code, Left channel. @type {Float64Array} */
    this.adcLeft = new Float64Array(inputDbv.length);
    /** Input-range linear factors, indexed by reg-0x05 code, Right channel. @type {Float64Array} */
    this.adcRight = new Float64Array(inputDbv.length);
    /** Output-range linear factors, indexed by reg-0x06 code, Left channel. @type {Float64Array} */
    this.dacLeft = new Float64Array(outputDbv.length);
    /** Output-range linear factors, indexed by reg-0x06 code, Right channel. @type {Float64Array} */
    this.dacRight = new Float64Array(outputDbv.length);
    for (const dbv of inputDbv) {
      const code = inputRangeCode(dbv);
      const leftOffset = ADC_BASE_OFFSET + code * RANGE_STRIDE;
      this.adcLeft[code] = decodeLinearFactor(page, leftOffset);
      this.adcRight[code] = decodeLinearFactor(page, leftOffset + RECORD_BYTES);
    }
    for (const dbv of outputDbv) {
      const code = outputRangeCode(dbv);
      const leftOffset = DAC_BASE_OFFSET + code * RANGE_STRIDE;
      this.dacLeft[code] = decodeLinearFactor(page, leftOffset);
      this.dacRight[code] = decodeLinearFactor(page, leftOffset + RECORD_BYTES);
    }
  }

  /**
   * Parses an already-assembled 512-byte cal page (§6).
   *
   * @param {Uint8Array} blob the cal page
   * @returns {Qa40xCalibration}
   */
  static fromBlob(blob) {
    return new Qa40xCalibration(blob);
  }

  /**
   * Runs the §6 read procedure over `transport` - selects the cal page, reads
   * CAL_READ_COUNT words re-packed little-endian - then parses.
   *
   * @param {{registerWrite: function(number, number): *, registerRead: function(number): *}} transport
   *        the Qa40xTransport seam; both calls are awaited (see the WEB DEVIATION note)
   * @returns {Promise<Qa40xCalibration>}
   */
  static async fromTransport(transport) {
    await transport.registerWrite(REG_CAL_PAGE_SELECT, CAL_PAGE_SELECT_VALUE);
    const blob = new Uint8Array(CAL_PAGE_BYTES);
    const page = new DataView(blob.buffer);
    for (let i = 0; i < CAL_READ_COUNT; i++) {
      page.setInt32(i * WORD_BYTES, await transport.registerRead(REG_CAL_READ), LITTLE_ENDIAN);
    }
    return Qa40xCalibration.fromBlob(blob);
  }

  /**
   * Linear input (ADC) cal factor for an input range, per channel.
   *
   * @param {number} inputDbv       input full-scale range label, in "dBV"
   * @param {boolean} rightChannel  true for Right, false for Left
   * @returns {number} linear correction factor
   */
  /**
   * A calibration assembled from the LINEAR FACTORS a Phonalyser server sends - spec 4.6's
   * qa40x.calibration ({adc:[{dbv,left,right}...], dac:[...]}) - instead of read off a cal page here:
   * the analyzer is on the bench and its EEPROM is not this machine's to read. The factors are
   * exactly what {@link #adcLinearFactor} / {@link #dacLinearFactor} answer, so every consumer
   * (the card builder above all) works unchanged - which is why the server sends linear factors
   * rather than a decoded card.
   *
   * A range the bench did not send keeps the NEUTRAL factor 1.0: a zero would silently collapse
   * that range's full scale to nothing, and an absent row means "not said", never "no signal".
   *
   * @param {Array<{dbv: number, left: number, right: number}>} adcRows input-range factors
   * @param {Array<{dbv: number, left: number, right: number}>} dacRows output-range factors
   * @returns {Qa40xCalibration}
   */
  static fromFactors(adcRows, dacRows) {
    const cal = Object.create(Qa40xCalibration.prototype);
    cal.adcLeft = neutralFactors(inputRangeDbvValues().length);
    cal.adcRight = neutralFactors(inputRangeDbvValues().length);
    cal.dacLeft = neutralFactors(outputRangeDbvValues().length);
    cal.dacRight = neutralFactors(outputRangeDbvValues().length);
    fillFactors(adcRows, inputRangeCode, cal.adcLeft, cal.adcRight);
    fillFactors(dacRows, outputRangeCode, cal.dacLeft, cal.dacRight);
    return cal;
  }

  adcLinearFactor(inputDbv, rightChannel) {
    const code = inputRangeCode(inputDbv);
    return rightChannel ? this.adcRight[code] : this.adcLeft[code];
  }

  /**
   * Linear output (DAC) cal factor for an output range, per channel.
   *
   * @param {number} outputDbv      output full-scale range label, in dBV
   * @param {boolean} rightChannel  true for Right, false for Left
   * @returns {number} linear correction factor
   */
  dacLinearFactor(outputDbv, rightChannel) {
    const code = outputRangeCode(outputDbv);
    return rightChannel ? this.dacRight[code] : this.dacLeft[code];
  }
}

/** `n` factors that scale nothing - the state of a range nobody has said anything about. */
function neutralFactors(n) {
  return new Float64Array(n).fill(1.0);
}

/** Files each wire row under its range CODE, which is how the lookups are indexed. A row for a
 *  range this build does not know (a newer analyzer) is skipped rather than mis-filed. */
function fillFactors(rows, codeOf, left, right) {
  for (const row of (Array.isArray(rows) ? rows : [])) {
    if (row == null || typeof row.dbv !== 'number') continue;
    const code = codeOf(row.dbv);
    if (!(code >= 0 && code < left.length)) continue;
    if (typeof row.left === 'number') left[code] = row.left;
    if (typeof row.right === 'number') right[code] = row.right;
  }
}
