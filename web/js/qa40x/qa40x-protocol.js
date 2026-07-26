/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.sound.qa40x.Qa40xProtocol.
//
// QA402/QA403 register wire protocol — the pure byte math behind the transport's
// register traffic (see doc/QA40X-PROTOCOL.md §4). Stateless: register addresses
// and values, the three range/rate code maps, and the frame codec.
//
// Register frames are BIG-endian even though audio samples are little-endian
// (§5) — two independent endiannesses; this module only speaks the register one.
// A write is a 5-byte frame [reg][value MSB..LSB]; a read is the same frame with
// the address MSB set (0x80|reg, value 0), whose reply is a 4-byte big-endian word.
//
// Code maps. Input full-scale code = dBV / 6 for 0..42 dBV; output full-scale
// -12/-2/+8/+18 dBV → codes 0..3; sample rate 48000/96000/192000/384000 Hz →
// codes 0..3. The 384 kHz code is QA403-ONLY — the QA402 has no code 3 — so the
// rate list is per model (sampleRatesHz), while the code map itself is universal.

/** Full-scale input range (attenuator) — reg 0x05, code 0..7 (§4). */
export const REG_INPUT_FS = 0x05;
/** Full-scale output range — reg 0x06, code 0..3 (§4). */
export const REG_OUTPUT_FS = 0x06;
/** Stream / run control — reg 0x08; RUN_START / RUN_STOP (§4). */
export const REG_RUN = 0x08;
/** Sample-rate select — reg 0x09, code 0..3 (§4). */
export const REG_SAMPLE_RATE = 0x09;
/**
 * Front-panel I2S generator control — reg 0x0A; write I2S_START / I2S_STOP,
 * read = running flag (§4). It drives the expansion port's own EP-3 pair and is
 * independent of the analyzer's DAC/ADC loopback, so it neither disturbs nor
 * depends on a capture session.
 */
export const REG_I2S = 0x0A;
/**
 * Front-panel I2S frame width — reg 0x0B; i2sWidthCode() turns a bit depth into
 * its value (§4).
 */
export const REG_I2S_WIDTH = 0x0B;
/** Calibration-page select — reg 0x0D; write CAL_PAGE_SELECT_VALUE (§6). */
export const REG_CAL_PAGE_SELECT = 0x0D;
/** Firmware build number — reg 0x10; real units report 60 (§4). */
export const REG_FIRMWARE_VERSION = 0x10;
/** USB bus voltage — reg 0x11, millivolts (§6 telemetry). */
export const REG_TELEM_USB_VOLTAGE = 0x11;
/** USB bus current — reg 0x12, milliamps (§6). */
export const REG_TELEM_USB_CURRENT = 0x12;
/** ISO-supply current — reg 0x13, milliamps; QA402 ONLY (§6). */
export const REG_TELEM_ISO_CURRENT = 0x13;
/** Board temperature — reg 0x16, tenths of °C (§6). */
export const REG_TELEM_TEMPERATURE = 0x16;
/** Feature-bit word the app reads before building its rate menu — reg 0x1B (§4). */
export const REG_CAPABILITY = 0x1B;
/** Per-model capability word — reg 0x1C (§4). */
export const REG_CAPABILITY2 = 0x1C;
/** Serial number — reg 0x1D, the 8 hex digits packed as a u32 (§4). */
export const REG_SERIAL_NUMBER = 0x1D;
/** Calibration data read port — reg 0x19, one 32-bit word per read (§6). */
export const REG_CAL_READ = 0x19;

/**
 * Safe-state input range (doc §7 Teardown step 8, ASIO401 parity): maximum
 * attenuation, +42 dBV — the fail-safe relay stays engaged (Atten LED lit,
 * matching the vendor app), so an idle analyzer never sits at a sensitive range.
 * Written whenever the stream parks (last lane detach) and at session close.
 */
export const SAFE_INPUT_DBV = 42;
/** Safe-state output range (doc §7 Teardown step 8): the quietest −12 dBV. */
export const SAFE_OUTPUT_DBV = -12;

/** REG_RUN value that starts streaming (§5). */
export const RUN_START = 0x05;
/** REG_RUN value that stops streaming / recovers an unclean state (§5). */
export const RUN_STOP = 0x00;

/** REG_I2S value that starts the front-panel I2S generator (§4). */
export const I2S_START = 0x01;
/**
 * REG_I2S value that stops it — also what the vendor app writes at connect as a
 * safe init (§6).
 */
export const I2S_STOP = 0x00;

/**
 * The two frame widths the front-panel I2S port runs at (§4); the analyzer's own
 * loopback is unaffected and stays at ANALYZER_BITS.
 */
export const I2S_BITS_16 = 16;
export const I2S_BITS_32 = 32;
/**
 * Delivered depth of the analyzer's own capture path — what the depth combo
 * offers while the I2S port is off.
 */
export const ANALYZER_BITS = 24;

/**
 * REG_I2S_WIDTH value for a frame width: bit 6 set = 32-bit frames, clear =
 * 16-bit (§4). Anything but I2S_BITS_16 is 32-bit, the width the port boots at.
 */
const I2S_WIDTH_32_FLAG = 0x40;
const I2S_WIDTH_16_FLAG = 0x00;
/**
 * REG_I2S_WIDTH value written whenever the port is off — the same cleared state
 * a stopped session leaves behind.
 */
export const I2S_WIDTH_OFF = 0x00;
/** REG_CAL_PAGE_SELECT value that selects the factory cal page (§6). */
export const CAL_PAGE_SELECT_VALUE = 0x10;

/** Bytes in a register write / read-request frame (§4). */
export const REGISTER_FRAME_BYTES = 5;
/** Bytes in a register read reply (§4). */
export const REGISTER_REPLY_BYTES = 4;

/** Address-byte MSB that turns a write frame into a read request (§4). */
const READ_REQUEST_FLAG = 0x80;

/** Input full-scale ranges in dBV (code = dBV / 6). */
const INPUT_RANGE_DBV = [0, 6, 12, 18, 24, 30, 36, 42];
/** Output full-scale ranges in dBV (code = index). */
const OUTPUT_RANGE_DBV = [-12, -2, 8, 18];
/**
 * Sample rates in Hz (code = index). The 384 kHz code exists on the QA403 only
 * — see sampleRatesHz().
 */
const SAMPLE_RATE_HZ = [48000, 96000, 192000, 384000];

const INPUT_RANGE_STEP_DBV = 6;
const INPUT_RANGE_MAX_DBV = 42;
/** Highest rate the QA402 accepts; the 384 kHz code is QA403-only (§4). */
const QA402_MAX_RATE_HZ = 192000;

/**
 * Names of the QA40x models — the Java gate is
 * `model == Qa40xDeviceFinder.Qa40xModel.QA402` over an ENUM, which cannot be
 * mistyped; here the model is carried by its enum NAME, so the gate must be
 * fail-CLOSED: only these two names are accepted and anything else throws.
 * A fail-open `model !== 'QA402'` would hand a typo the QA403-only 384 kHz rate.
 */
const MODEL_QA402 = 'QA402';
const MODEL_QA403 = 'QA403';
const MODEL_NAMES = Object.freeze([MODEL_QA402, MODEL_QA403]);

/**
 * Suffix of the plain "N dBV" range-row label — the persisted KEY, emitted by
 * rangeLabel() for BOTH directions, so the device manager's card refresh and any
 * caller resolving a label back to a range dBV agree byte-for-byte. The verbose
 * INPUT display label is built separately in verboseInputLabel().
 */
const RANGE_LABEL_SUFFIX = ' dBV';

/** dB by which the true RMS full scale sits below the nominal "N dBV" input range. */
const INPUT_LABEL_RMS_OFFSET_DB = 9;

/** Telemetry scaling / digits — Java's `%.3f V`, `%.3f A`, `%.1f °C`, `%08X`. */
const MILLI_PER_UNIT = 1000;
const TENTHS_PER_DEGREE = 10;
const VOLT_AMP_DECIMALS = 3;
const TEMPERATURE_DECIMALS = 1;
const HEX_RADIX = 16;
const WORD_HEX_DIGITS = 8;

/**
 * Input full-scale ranges in dBV, ascending — a defensive copy.
 * @returns {number[]}
 */
export function inputRangeDbvValues() {
  return INPUT_RANGE_DBV.slice();
}

/**
 * Output full-scale ranges in dBV, ascending — a defensive copy.
 * @returns {number[]}
 */
export function outputRangeDbvValues() {
  return OUTPUT_RANGE_DBV.slice();
}

/**
 * Sample rates in Hz for `model`, ascending — a defensive copy. The QA403 adds
 * 384 kHz (reg-9 code 3) on top of the common 48/96/192; the QA402 has no code 3 (§4).
 * Unknown names THROW — the Java signature takes the Qa40xModel enum, so a
 * misspelled model is not representable there and must not be here either.
 * @param {string} model model name, 'QA402' or 'QA403'
 * @returns {number[]}
 */
export function sampleRatesHz(model) {
  if (!MODEL_NAMES.includes(model)) {
    throw new Error(`unknown QA40x model: ${model} (expected one of ${MODEL_NAMES.join('/')})`);
  }
  if (model === MODEL_QA402) {
    return SAMPLE_RATE_HZ.filter((hz) => hz <= QA402_MAX_RATE_HZ);
  }
  return SAMPLE_RATE_HZ.slice();
}

/**
 * The device-card range-row label — the persisted KEY, plain "N dBV" for both
 * directions. The verbose input DISPLAY (the "really N dBFS / N−9 dBV" text) is
 * verboseInputLabel(), carried on the row's displayLabel and shown only in the
 * ranges table; it is never persisted or parsed.
 * @param {number} dbv
 * @returns {string}
 */
export function rangeLabel(dbv) {
  return dbv + RANGE_LABEL_SUFFIX;
}

/**
 * Verbose DISPLAY label for an input range: `N "dBV" real N dBFS or (N−9) dBV`.
 * The QA "N dBV" input range is really an N-dBFS (Vpp-differential) reference
 * whose true RMS full scale is ≈ N − 9 dB (Qa40xLevels / doc §6 cheat-sheet).
 * Display only — never a card key, so nothing parses it back.
 * @param {number} dbv
 * @returns {string}
 */
export function verboseInputLabel(dbv) {
  return `${dbv} "dBV" real ${dbv} dBFS or ${dbv - INPUT_LABEL_RMS_OFFSET_DB} dBV`;
}

/**
 * Resolves a plain rangeLabel() back to its dBV, searching `candidates` (an
 * input/output *RangeDbvValues() array); returns `fallback` when `label` matches none.
 * @param {string} label
 * @param {number[]} candidates
 * @param {number} fallback
 * @returns {number}
 */
export function rangeDbv(label, candidates, fallback) {
  for (const dbv of candidates) {
    if (rangeLabel(dbv) === label) {
      return dbv;
    }
  }
  return fallback;
}

/**
 * Maps an input full-scale range (dBV) to its reg-0x05 code; dBV / 6.
 * @param {number} dbv
 * @returns {number}
 */
export function inputRangeCode(dbv) {
  if (dbv < 0 || dbv > INPUT_RANGE_MAX_DBV || dbv % INPUT_RANGE_STEP_DBV !== 0) {
    throw new Error(`input full-scale range must be one of 0..42 dBV in 6 dB steps: ${dbv}`);
  }
  return dbv / INPUT_RANGE_STEP_DBV;
}

/**
 * Maps an output full-scale range (dBV) to its reg-0x06 code.
 * @param {number} dbv
 * @returns {number}
 */
export function outputRangeCode(dbv) {
  for (let i = 0; i < OUTPUT_RANGE_DBV.length; i++) {
    if (OUTPUT_RANGE_DBV[i] === dbv) {
      return i;
    }
  }
  throw new Error(`output full-scale range must be one of -12/-2/+8/+18 dBV: ${dbv}`);
}

/**
 * REG_TELEM_USB_VOLTAGE (mV) as volts, `x.xxx V` (§6). The vendor app warns
 * below 4.6 V.
 * @param {number} raw
 * @returns {string}
 */
export function formatUsbVoltage(raw) {
  return `${(raw / MILLI_PER_UNIT).toFixed(VOLT_AMP_DECIMALS)} V`;
}

/**
 * REG_TELEM_USB_CURRENT / REG_TELEM_ISO_CURRENT (mA) as amps, `x.xxx A` (§6).
 * @param {number} raw
 * @returns {string}
 */
export function formatCurrent(raw) {
  return `${(raw / MILLI_PER_UNIT).toFixed(VOLT_AMP_DECIMALS)} A`;
}

/**
 * REG_TELEM_TEMPERATURE (tenths of °C) as `xx.x °C` (§6).
 * @param {number} raw
 * @returns {string}
 */
export function formatTemperature(raw) {
  return `${(raw / TENTHS_PER_DEGREE).toFixed(TEMPERATURE_DECIMALS)} °C`;
}

/**
 * A capability word as the `0x…` hex the protocol notes quote (§4) — Java's
 * `0x%08X` over an int, i.e. the word read UNSIGNED.
 * @param {number} raw
 * @returns {string}
 */
export function formatCapability(raw) {
  return `0x${formatSerialNumber(raw)}`;
}

/**
 * REG_SERIAL_NUMBER — the packed u32 back as its 8 hex digits (§4).
 * @param {number} raw
 * @returns {string}
 */
export function formatSerialNumber(raw) {
  return (raw >>> 0).toString(HEX_RADIX).toUpperCase().padStart(WORD_HEX_DIGITS, '0');
}

/**
 * Maps an I2S frame width in bits to its reg-0x0B value (§4).
 * @param {number} bits
 * @returns {number}
 */
export function i2sWidthCode(bits) {
  return bits === I2S_BITS_16 ? I2S_WIDTH_16_FLAG : I2S_WIDTH_32_FLAG;
}

/**
 * The frame widths the front-panel I2S port offers, ascending — what the depth
 * combo shows in place of ANALYZER_BITS while it is on.
 * @returns {number[]}
 */
export function i2sBitDepths() {
  return [I2S_BITS_16, I2S_BITS_32];
}

/**
 * Maps a sample rate (Hz) to its reg-0x09 code, 0..3 (§4). Code 3 (384 kHz)
 * exists on the QA403 only; the per-model rate list is sampleRatesHz().
 * @param {number} hz
 * @returns {number}
 */
export function sampleRateCode(hz) {
  for (let i = 0; i < SAMPLE_RATE_HZ.length; i++) {
    if (SAMPLE_RATE_HZ[i] === hz) {
      return i;
    }
  }
  throw new Error(`sample rate must be one of 48000/96000/192000/384000 Hz: ${hz}`);
}

/**
 * Builds the 5-byte big-endian register write frame [reg][value MSB..LSB] (§4).
 * @param {number} reg
 * @param {number} value
 * @returns {Uint8Array}
 */
export function writeFrame(reg, value) {
  const frame = new Uint8Array(REGISTER_FRAME_BYTES);
  frame[0] = reg;
  frame[1] = value >> 24;
  frame[2] = value >> 16;
  frame[3] = value >> 8;
  frame[4] = value;
  return frame;
}

/**
 * Builds the read-request frame — a write of value 0 with the address MSB set
 * (0x80|reg, §4).
 * @param {number} reg
 * @returns {Uint8Array}
 */
export function readRequestFrame(reg) {
  return writeFrame(reg | READ_REQUEST_FLAG, 0);
}

/**
 * Decodes a 4-byte big-endian register reply into a signed 32-bit word (§4).
 * @param {Uint8Array|number[]} reply
 * @returns {number}
 */
export function decodeReply(reply) {
  if (reply == null || reply.length < REGISTER_REPLY_BYTES) {
    throw new Error(`register reply must be at least ${REGISTER_REPLY_BYTES} bytes`);
  }
  return ((reply[0] & 0xFF) << 24)
      | ((reply[1] & 0xFF) << 16)
      | ((reply[2] & 0xFF) << 8)
      | (reply[3] & 0xFF);
}

/** Java call-site parity: the whole utility class as one frozen namespace. */
export const Qa40xProtocol = Object.freeze({
  REG_INPUT_FS,
  REG_OUTPUT_FS,
  REG_RUN,
  REG_SAMPLE_RATE,
  REG_I2S,
  REG_I2S_WIDTH,
  REG_CAL_PAGE_SELECT,
  REG_FIRMWARE_VERSION,
  REG_TELEM_USB_VOLTAGE,
  REG_TELEM_USB_CURRENT,
  REG_TELEM_ISO_CURRENT,
  REG_TELEM_TEMPERATURE,
  REG_CAPABILITY,
  REG_CAPABILITY2,
  REG_SERIAL_NUMBER,
  REG_CAL_READ,
  SAFE_INPUT_DBV,
  SAFE_OUTPUT_DBV,
  RUN_START,
  RUN_STOP,
  I2S_START,
  I2S_STOP,
  I2S_BITS_16,
  I2S_BITS_32,
  ANALYZER_BITS,
  I2S_WIDTH_OFF,
  i2sWidthCode,
  i2sBitDepths,
  CAL_PAGE_SELECT_VALUE,
  REGISTER_FRAME_BYTES,
  REGISTER_REPLY_BYTES,
  inputRangeDbvValues,
  outputRangeDbvValues,
  sampleRatesHz,
  rangeLabel,
  verboseInputLabel,
  rangeDbv,
  inputRangeCode,
  outputRangeCode,
  formatUsbVoltage,
  formatCurrent,
  formatTemperature,
  formatCapability,
  formatSerialNumber,
  sampleRateCode,
  writeFrame,
  readRequestFrame,
  decodeReply,
});
