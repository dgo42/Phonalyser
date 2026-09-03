/*
 * Phonalyser web - Qa40xProtocol frame codec + code maps. Run with
 * `node --test --test-concurrency=1 test/qa40x-protocol.test.mjs`.
 *
 * Mirrors org.edgo.audio.measure.sound.qa40x.Qa40xProtocolTest against
 * doc/QA40X-PROTOCOL.md §4: the 5-byte big-endian write frame, the 0x80|reg read
 * request, the 4-byte big-endian reply decode, and every range / rate code map
 * (384 kHz rejected on the QA402).
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import {
  Qa40xProtocol,
  decodeReply,
  i2sBitDepths,
  i2sWidthCode,
  inputRangeCode,
  inputRangeDbvValues,
  outputRangeCode,
  readRequestFrame,
  sampleRateCode,
  sampleRatesHz,
  writeFrame,
} from '../js/qa40x/qa40x-protocol.js';

test('writeFrame_sampleRateValue2_matchesDoc', () => {
  // doc §4: register 0x09 value 2 -> bytes 09 00 00 00 02
  assert.deepEqual(writeFrame(0x09, 2), new Uint8Array([0x09, 0x00, 0x00, 0x00, 0x02]));
});

test('writeFrame_isBigEndian', () => {
  assert.deepEqual(writeFrame(0x05, 0x01020304),
    new Uint8Array([0x05, 0x01, 0x02, 0x03, 0x04]));
});

test('readRequestFrame_setsAddressMsb', () => {
  // doc §4: read request 0x19 -> first byte 0x99 (0x80 | 0x19)
  const frame = readRequestFrame(0x19);
  assert.equal(frame[0], 0x99);
  assert.deepEqual(frame, new Uint8Array([0x99, 0x00, 0x00, 0x00, 0x00]));
});

test('decodeReply_isBigEndian', () => {
  assert.equal(decodeReply(new Uint8Array([1, 2, 3, 4])), 0x01020304);
  assert.equal(decodeReply(new Uint8Array([0xFF, 0xFF, 0xFF, 0xFF])), -1);
});

test('decodeReply_rejectsShortReply', () => {
  assert.throws(() => decodeReply(new Uint8Array([1, 2, 3])), Error);
  assert.throws(() => decodeReply(null), Error);
});

test('inputRangeCode_isDbvOverSix', () => {
  for (const dbv of inputRangeDbvValues()) {
    assert.equal(inputRangeCode(dbv), dbv / 6);
  }
  assert.equal(inputRangeCode(0), 0);
  assert.equal(inputRangeCode(42), 7);
});

test('inputRangeCode_rejectsOffGrid', () => {
  assert.throws(() => inputRangeCode(3), Error);
  assert.throws(() => inputRangeCode(48), Error);
  assert.throws(() => inputRangeCode(-6), Error);
});

test('outputRangeCode_mapsFourRanges', () => {
  assert.equal(outputRangeCode(-12), 0);
  assert.equal(outputRangeCode(-2), 1);
  assert.equal(outputRangeCode(8), 2);
  assert.equal(outputRangeCode(18), 3);
  assert.throws(() => outputRangeCode(0), Error);
});

test('sampleRateCode_mapsFourRates', () => {
  assert.equal(sampleRateCode(48000), 0);
  assert.equal(sampleRateCode(96000), 1);
  assert.equal(sampleRateCode(192000), 2);
  // doc §4: code 3 is the QA403's 384 kHz - the code map itself is universal,
  // the per-model gate lives in sampleRatesHz(model).
  assert.equal(sampleRateCode(384000), 3);
});

test('sampleRateCode_rejectsRatesWithNoCode', () => {
  assert.throws(() => sampleRateCode(44100), Error);
  assert.throws(() => sampleRateCode(768000), Error);
});

test('sampleRatesHz_offers384kOnQa403Only', () => {
  // The QA402 has no reg-9 code 3; the QA403 does (doc §4).
  assert.deepEqual(sampleRatesHz('QA402'), [48000, 96000, 192000]);
  assert.deepEqual(sampleRatesHz('QA403'), [48000, 96000, 192000, 384000]);
});

test('rangeValueAccessorsAreDefensiveCopies', () => {
  const values = inputRangeDbvValues();
  values[0] = 999;
  assert.equal(inputRangeDbvValues()[0], 0);

  const outputs = Qa40xProtocol.outputRangeDbvValues();
  outputs[0] = 999;
  assert.equal(Qa40xProtocol.outputRangeDbvValues()[0], -12);

  const rates = sampleRatesHz('QA403');
  rates[0] = 999;
  assert.equal(sampleRatesHz('QA403')[0], 48000);
});

test('rangeLabel_isThePersistedKeyForBothDirections', () => {
  assert.equal(Qa40xProtocol.rangeLabel(0), '0 dBV');
  assert.equal(Qa40xProtocol.rangeLabel(42), '42 dBV');
  assert.equal(Qa40xProtocol.rangeLabel(-12), '-12 dBV');
});

test('verboseInputLabel_statesTheNineDbRmsOffset', () => {
  assert.equal(Qa40xProtocol.verboseInputLabel(0), '0 "dBV" real 0 dBFS or -9 dBV');
  assert.equal(Qa40xProtocol.verboseInputLabel(42), '42 "dBV" real 42 dBFS or 33 dBV');
});

test('rangeDbv_resolvesLabelsBackAndFallsBack', () => {
  const inputs = inputRangeDbvValues();
  for (const dbv of inputs) {
    assert.equal(Qa40xProtocol.rangeDbv(Qa40xProtocol.rangeLabel(dbv), inputs, -1), dbv);
  }
  assert.equal(Qa40xProtocol.rangeDbv('7 dBV', inputs, -1), -1);
  assert.equal(Qa40xProtocol.rangeDbv('-12 dBV',
    Qa40xProtocol.outputRangeDbvValues(), 99), -12);
});

test('registerConstantsMatchTheDoc', () => {
  assert.equal(Qa40xProtocol.REG_INPUT_FS, 0x05);
  assert.equal(Qa40xProtocol.REG_OUTPUT_FS, 0x06);
  assert.equal(Qa40xProtocol.REG_RUN, 0x08);
  assert.equal(Qa40xProtocol.REG_SAMPLE_RATE, 0x09);
  assert.equal(Qa40xProtocol.REG_I2S, 0x0A);
  assert.equal(Qa40xProtocol.REG_I2S_WIDTH, 0x0B);
  assert.equal(Qa40xProtocol.REG_CAL_PAGE_SELECT, 0x0D);
  assert.equal(Qa40xProtocol.REG_CAL_READ, 0x19);
  assert.equal(Qa40xProtocol.RUN_START, 0x05);
  assert.equal(Qa40xProtocol.RUN_STOP, 0x00);
  assert.equal(Qa40xProtocol.I2S_START, 0x01);
  assert.equal(Qa40xProtocol.I2S_STOP, 0x00);
  assert.equal(Qa40xProtocol.I2S_WIDTH_OFF, 0x00);
  assert.equal(Qa40xProtocol.CAL_PAGE_SELECT_VALUE, 0x10);
  assert.equal(Qa40xProtocol.REGISTER_FRAME_BYTES, 5);
  assert.equal(Qa40xProtocol.REGISTER_REPLY_BYTES, 4);
  assert.equal(Qa40xProtocol.SAFE_INPUT_DBV, 42);
  assert.equal(Qa40xProtocol.SAFE_OUTPUT_DBV, -12);
});

test('telemetryAndIdentityRegistersMatchTheDoc', () => {
  // Java Qa40xProtocol: firmware build, the four telemetry words, both
  // capability words and the serial number.
  assert.equal(Qa40xProtocol.REG_FIRMWARE_VERSION, 0x10);
  assert.equal(Qa40xProtocol.REG_TELEM_USB_VOLTAGE, 0x11);
  assert.equal(Qa40xProtocol.REG_TELEM_USB_CURRENT, 0x12);
  assert.equal(Qa40xProtocol.REG_TELEM_ISO_CURRENT, 0x13);   // QA402 only
  assert.equal(Qa40xProtocol.REG_TELEM_TEMPERATURE, 0x16);
  assert.equal(Qa40xProtocol.REG_CAPABILITY, 0x1B);
  assert.equal(Qa40xProtocol.REG_CAPABILITY2, 0x1C);
  assert.equal(Qa40xProtocol.REG_SERIAL_NUMBER, 0x1D);
  // No two registers may collide - a copy-paste slip in the block above.
  const regs = [
    Qa40xProtocol.REG_INPUT_FS, Qa40xProtocol.REG_OUTPUT_FS, Qa40xProtocol.REG_RUN,
    Qa40xProtocol.REG_SAMPLE_RATE, Qa40xProtocol.REG_I2S, Qa40xProtocol.REG_I2S_WIDTH,
    Qa40xProtocol.REG_CAL_PAGE_SELECT, Qa40xProtocol.REG_FIRMWARE_VERSION,
    Qa40xProtocol.REG_TELEM_USB_VOLTAGE, Qa40xProtocol.REG_TELEM_USB_CURRENT,
    Qa40xProtocol.REG_TELEM_ISO_CURRENT, Qa40xProtocol.REG_TELEM_TEMPERATURE,
    Qa40xProtocol.REG_CAPABILITY, Qa40xProtocol.REG_CAPABILITY2,
    Qa40xProtocol.REG_SERIAL_NUMBER, Qa40xProtocol.REG_CAL_READ,
  ];
  assert.equal(new Set(regs).size, regs.length);
});

test('bitDepthConstantsMatchTheJava', () => {
  assert.equal(Qa40xProtocol.I2S_BITS_16, 16);
  assert.equal(Qa40xProtocol.I2S_BITS_32, 32);
  // The analyzer's own capture path - NOT one of the I2S widths.
  assert.equal(Qa40xProtocol.ANALYZER_BITS, 24);
});

test('i2sWidthCode_setsBitSixForThirtyTwoBitFramesOnly', () => {
  // doc §4: reg 0x0B bit 6 set = 32-bit frames, clear = 16-bit. 16-bit and
  // "port off" happen to share the value 0x00, so the 32-bit branch is the only
  // one that proves the flag is really emitted - pin it explicitly.
  assert.equal(i2sWidthCode(32), 0x40);
  assert.equal(i2sWidthCode(16), 0x00);
  assert.notEqual(i2sWidthCode(32), i2sWidthCode(16));
  assert.notEqual(i2sWidthCode(32), Qa40xProtocol.I2S_WIDTH_OFF);
  assert.equal(i2sWidthCode(Qa40xProtocol.I2S_BITS_32), 0x40);
  assert.equal(i2sWidthCode(Qa40xProtocol.I2S_BITS_16), Qa40xProtocol.I2S_WIDTH_OFF);
  // Java: "anything but I2S_BITS_16 is 32-bit, the width the port boots at".
  assert.equal(i2sWidthCode(Qa40xProtocol.ANALYZER_BITS), 0x40);
  assert.equal(i2sWidthCode(0), 0x40);
});

test('i2sBitDepths_offersBothWidthsAscendingAsACopy', () => {
  assert.deepEqual(i2sBitDepths(), [16, 32]);
  const depths = i2sBitDepths();
  depths[0] = 999;
  assert.equal(i2sBitDepths()[0], 16);
});

test('telemetryFormattersMatchTheJavaFormatStrings', () => {
  // %.3f V over millivolts; the vendor app warns below 4.6 V.
  assert.equal(Qa40xProtocol.formatUsbVoltage(4980), '4.980 V');
  assert.equal(Qa40xProtocol.formatUsbVoltage(5000), '5.000 V');
  assert.equal(Qa40xProtocol.formatUsbVoltage(4599), '4.599 V');
  // %.3f A over milliamps.
  assert.equal(Qa40xProtocol.formatCurrent(512), '0.512 A');
  assert.equal(Qa40xProtocol.formatCurrent(1234), '1.234 A');
  assert.equal(Qa40xProtocol.formatCurrent(0), '0.000 A');
  // %.1f °C over tenths of a degree.
  assert.equal(Qa40xProtocol.formatTemperature(431), '43.1 °C');
  assert.equal(Qa40xProtocol.formatTemperature(0), '0.0 °C');
  assert.equal(Qa40xProtocol.formatTemperature(-55), '-5.5 °C');
});

test('capabilityAndSerialFormatWordsUnsigned', () => {
  // Java 0x%08X / %08X over an int: 8 upper-case digits, read UNSIGNED.
  assert.equal(Qa40xProtocol.formatCapability(0x1F), '0x0000001F');
  assert.equal(Qa40xProtocol.formatCapability(0xABCDEF12 | 0), '0xABCDEF12');
  assert.equal(Qa40xProtocol.formatCapability(-1), '0xFFFFFFFF');
  assert.equal(Qa40xProtocol.formatSerialNumber(0x1A2B3C4D), '1A2B3C4D');
  assert.equal(Qa40xProtocol.formatSerialNumber(0xFF), '000000FF');
  assert.equal(Qa40xProtocol.formatSerialNumber(-1), 'FFFFFFFF');
  // The decoded reply for an all-ones word is -1 - it must still print as hex.
  assert.equal(Qa40xProtocol.formatSerialNumber(
    decodeReply(new Uint8Array([0xDE, 0xAD, 0xBE, 0xEF]))), 'DEADBEEF');
});

test('sampleRatesHz_rejectsUnknownModelNames', () => {
  // The Java signature takes the Qa40xModel ENUM, so a typo cannot compile
  // there. The port must fail CLOSED, not hand 384 kHz to an unknown model.
  for (const bad of ['QA401', 'qa402', 'QA404', 'QA40x', '', 'QA403 ', null, undefined, 403]) {
    assert.throws(() => sampleRatesHz(bad), Error,
      `expected sampleRatesHz(${JSON.stringify(bad)}) to throw`);
  }
});
