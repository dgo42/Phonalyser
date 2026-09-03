/*
 * Phonalyser web - Qa40xDeviceManager session / card / telemetry contract test.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Pins the behaviour of the port of org.edgo.audio.measure.sound.qa40x.Qa40xDeviceManager
 * that a green build cannot: the device card's SURVIVAL rules (an existing card's match
 * aliases and active-range selections outlive a refresh; a fresh card gets the vendor
 * defaults), the I2S live/edit boundary (the depth list and the dialog seed follow the
 * EDIT value, the ENGINE follows the live one), readDeviceInfo reading exactly the
 * registers it decodes and degrading to NONE when a read fails part-way, the four gates
 * of applyActiveRangeChange, and above all shutdown's safe-state write ORDER - parking
 * the ranges after closing the transport, or writing them in the wrong order, leaves a
 * sensitive input range live on an idle analyzer, which is exactly the bug the desktop's
 * 8d8ee8d fixed.
 *
 * Two of these can only be proved through the DEVICE's own traffic, never through a
 * return value: which register each telemetry decoder was fed (every decoder turns any
 * word into a believable string), and which of the two I2S values the engine's supplier
 * reads. Both are asserted against the transport double's ordered op log / register
 * writes, exactly as qa40x-calibration.test.mjs pins the cal-page select.
 *
 * Hardware stands in as the SHARED transport double (test/fake-qa40x-transport.mjs - a
 * private copy drifts from the real one and quietly stops proving the same seam) plus a
 * stub finder, since the web finder speaks WebUSB. The stored ranges are private state, so
 * they are observed the way the DEVICE sees them: through the register codes the engine
 * writes at session start.
 *
 * The last three cases use the REAL DeviceProfileStore to cover the calibrationFromDevice
 * flag restored on DeviceEndpointConfig - persist round trip, refuse-to-calibrate, and the
 * seed merge that lets a device-owned range take the seed values wholesale.
 *
 * preferences.js / device-profiles.js touch localStorage + navigator, so minimal shims go
 * on globalThis BEFORE the modules are imported.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

const backing = new Map();
globalThis.localStorage = {
  getItem: (k) => (backing.has(k) ? backing.get(k) : null),
  setItem: (k, v) => { backing.set(k, String(v)); },
  removeItem: (k) => { backing.delete(k); },
  clear: () => { backing.clear(); },
};
if (!globalThis.navigator) globalThis.navigator = { platform: 'Win32', userAgent: 'node' };

const { Events } = await import('../js/bus/events.js');
const { MessageBus } = await import('../js/bus/message-bus.js');
const { Preferences } = await import('../js/store/preferences.js');
const { DeviceChannelMode } = await import('../js/enums/device-channel-mode.js');
const { AudioDeviceProfile, DeviceProfileStore, DeviceRange } = await import('../js/store/device-profiles.js');
const { CAL_PAGE_BYTES, CAL_READ_COUNT, Qa40xCalibration } = await import('../js/qa40x/qa40x-calibration.js');
const { Qa40xDevice, Qa40xModel } = await import('../js/qa40x/qa40x-device-finder.js');
const { Qa40xDeviceInfo } = await import('../js/qa40x/qa40x-device-info.js');
const { Qa40xDeviceManager, Qa40xDeviceRef } = await import('../js/qa40x/qa40x-device-manager.js');
const { Qa40xPreferences } = await import('../js/qa40x/qa40x-preferences.js');
const {
  CHANNELS: ENGINE_CHANNELS,
} = await import('../js/qa40x/qa40x-duplex-engine.js');
const {
  CAL_PAGE_SELECT_VALUE, I2S_START, I2S_STOP, I2S_WIDTH_OFF,
  REG_CAL_PAGE_SELECT, REG_CAL_READ, REG_CAPABILITY, REG_CAPABILITY2, REG_FIRMWARE_VERSION,
  REG_I2S, REG_I2S_WIDTH, REG_INPUT_FS, REG_OUTPUT_FS, REG_RUN, REG_SERIAL_NUMBER,
  REG_TELEM_ISO_CURRENT, REG_TELEM_TEMPERATURE, REG_TELEM_USB_CURRENT, REG_TELEM_USB_VOLTAGE,
  RUN_STOP, SAFE_INPUT_DBV, SAFE_OUTPUT_DBV,
  i2sWidthCode, inputRangeCode, inputRangeDbvValues, outputRangeCode, outputRangeDbvValues,
  rangeLabel, sampleRatesHz, verboseInputLabel,
} = await import('../js/qa40x/qa40x-protocol.js');
const { inputFullScaleRmsVolts, outputFullScaleRmsVolts } = await import('../js/qa40x/qa40x-levels.js');
const { FakeTransport } = await import('./fake-qa40x-transport.mjs');

const QA40X = 'QA40X';
const WEB_AUDIO = 'WEB_AUDIO';
const RATE_HZ = 48000;
/** The stub Preferences' stored output bit depth - the I2S frame width the engine's
 *  second supplier reads (anything but 16 writes the 32-bit frame code, by design). */
const OUTPUT_BIT_DEPTH = 24;
const TOL = 1e-12;
/** Tolerance for the hand-written full-scale literals below: they carry 10 significant
 *  digits, where TOL compares two runs of the same float expression. */
const HAND_TOL = 1e-9;

/** Cal-page record geometry (doc §6) - private to qa40x-calibration.js, so the page a
 *  test synthesises has to restate it, exactly as qa40x-calibration.test.mjs does. */
const RECORD_BYTES = 6;
const RANGE_STRIDE = 12;
const ADC_BASE = 24;
const DAC_BASE = 120;
const LE = true;

/** Distinct dB corrections per direction AND per channel, so a swapped channel or a
 *  direction using the other one's Qa40xLevels function cannot pass. */
const ADC_LEFT_DB = 1.0;
const ADC_RIGHT_DB = -1.0;
const DAC_LEFT_DB = 2.0;
const DAC_RIGHT_DB = -2.0;
const linear = (db) => Math.pow(10.0, db / 20.0);

/** Telemetry register words the fake device reports (§6 units). */
const FIRMWARE = 60;
const USB_MILLIVOLTS = 4980;
const USB_MILLIAMPS = 250;
const ISO_MILLIAMPS = 120;
const TENTHS_DEGREE = 315;
const CAPABILITY = 0x00000003;
const CAPABILITY2 = 0x0000000A;
const SERIAL = 0x0A1B2C3D;

/**
 * The identity + telemetry registers readDeviceInfo must read, in decode order, as the
 * transport double's op log records them - the ISO-supply register on the QA402 only.
 * WHICH register feeds which decoder is invisible in the returned strings: every decoder
 * turns any word into a plausible value, so two swapped register addresses change nothing
 * a value assertion can see.
 */
const telemetryReadOps = (hasIso) => [
  REG_FIRMWARE_VERSION, REG_TELEM_USB_VOLTAGE, REG_TELEM_USB_CURRENT,
  ...(hasIso ? [REG_TELEM_ISO_CURRENT] : []),
  REG_TELEM_TEMPERATURE, REG_CAPABILITY, REG_CAPABILITY2, REG_SERIAL_NUMBER,
].map((reg) => `read=${reg}`);

/**
 * Opens the analyzer AND LEAVES IT OPEN, for a test that needs an open device.
 *
 * readDeviceInfo() used to be the convenient way to do this, and no longer is: reading the
 * read-only telemetry panel now hands the device straight back, because holding WebUSB's exclusive
 * claim on interface 0 for the life of the page locked the analyzer out of the vendor software
 * (see qa40x-release.test.mjs). acquireEngine() is the honest stand-in - it opens the device and
 * builds the session engine without starting a stream, so nothing is released underneath the test.
 */
const openDevice = (manager) => manager.acquireEngine(SESSION_RATE_HZ);

/** A rate every model supports, for openDevice(). */
const SESSION_RATE_HZ = 48_000;

/** The ops a session open records: the cal-page select, then its 128 word reads (§6). */
const calPageOps = () => [
  `reg=${REG_CAL_PAGE_SELECT}:${CAL_PAGE_SELECT_VALUE}`,
  ...Array(CAL_READ_COUNT).fill(`read=${REG_CAL_READ}`),
];

const EMPTY_CATALOG = { formatVersion: 1, contentVersion: 0, audioDevices: [] };

/** The 512-byte factory cal page as the 128 little-endian words the read procedure pulls. */
function calPageWords() {
  const blob = new Uint8Array(CAL_PAGE_BYTES);
  const page = new DataView(blob.buffer);
  const put = (offset, level, db) => {
    page.setInt16(offset, level, LE);
    page.setFloat32(offset + 2, db, LE);
  };
  for (const dbv of inputRangeDbvValues()) {
    const at = ADC_BASE + inputRangeCode(dbv) * RANGE_STRIDE;
    put(at, dbv, ADC_LEFT_DB);
    put(at + RECORD_BYTES, dbv, ADC_RIGHT_DB);
  }
  for (const dbv of outputRangeDbvValues()) {
    const at = DAC_BASE + outputRangeCode(dbv) * RANGE_STRIDE;
    put(at, dbv, DAC_LEFT_DB);
    put(at + RECORD_BYTES, dbv, DAC_RIGHT_DB);
  }
  const words = [];
  for (let i = 0; i < CAL_READ_COUNT; i++) words.push(page.getInt32(i * 4, LE));
  return words;
}

/** The same page as a parsed Qa40xCalibration, for driving buildProfile directly. */
function calibration() {
  const blob = new Uint8Array(CAL_PAGE_BYTES);
  const page = new DataView(blob.buffer);
  const words = calPageWords();
  for (let i = 0; i < words.length; i++) page.setInt32(i * 4, words[i], LE);
  return Qa40xCalibration.fromBlob(blob);
}

/** Stands in for Qa40xDeviceFinder: the two calls the manager makes, and a count of the
 *  claims so a re-open after shutdown is provable. */
class StubFinder {
  constructor(transport, devices) {
    this.transport = transport;
    this.devices = devices;
    this.opens = 0;
  }

  async list() {
    return this.devices;
  }

  async open() {
    this.opens++;
    return this.transport;
  }
}

/** Stands in for DeviceProfileStore: the three calls #refreshDeviceCard makes. */
class StubStore {
  constructor(existing = null) {
    this.cards = existing == null ? [] : [existing];
    this.puts = 0;
    this.saves = 0;
  }

  findAudioDeviceProfile(name) {
    for (const card of this.cards) {
      if (card.name === name) return card;
    }
    return null;
  }

  putAudioDeviceProfile(card) {
    this.puts++;
    this.cards = [card];
  }

  saveDevices() {
    this.saves++;
  }
}

/** Records console.warn while `body` runs, so a guarded warning is an assertion rather
 *  than test-log noise. */
async function warnings(body) {
  const original = console.warn;
  const seen = [];
  console.warn = (...args) => seen.push(args.join(' '));
  try {
    await body();
  } finally {
    console.warn = original;
  }
  return seen;
}

/**
 * A manager over the fake device: a stub finder holding one analyzer, the shared
 * transport double primed with the cal page, an instant sleeper and the bus-free seam
 * (Java's package-private test constructors).
 */
function newManager({ model = Qa40xModel.QA403, backend = QA40X, existingCard = null,
  telemetry = [], armBus = false, deviceStore = null, prefs = null, i2sAccepted = true,
  devices = null, bench = null } = {}) {
  const transport = new FakeTransport();
  transport.readReplies.push(...calPageWords(), ...telemetry);
  const finder = new StubFinder(transport, devices == null ? [new Qa40xDevice(model, 'TEST')] : devices);
  const store = deviceStore == null ? new StubStore(existingCard) : deviceStore;
  const registered = [];
  const livePrefs = prefs == null
    ? {
      backend: { get: () => backend },
      current: () => ({ outputBitDepth: OUTPUT_BIT_DEPTH }),
      registerCustomPreferences: (sub) => registered.push(sub),
    }
    : prefs;
  const settings = new Qa40xPreferences();
  // The opener RECORDS its arguments: the seed it is handed is the whole live/edit
  // contract on the dialog side, and a fake that ignores it cannot tell a pending edit
  // from the stale live value.
  const dialogOpens = [];
  const manager = new Qa40xDeviceManager({
    prefs: livePrefs,
    deviceStore: store,
    finder,
    sleeper: () => {},
    settings,
    openSettingsDialog: (parent, info, seed) => {
      dialogOpens.push({ parent, info, seed });
      return i2sAccepted;
    },
    armBus,
    bench,
  });
  return { manager, transport, finder, store, settings, registered, dialogOpens };
}

/** A manager on a bench seam and a REAL store - the shape every overlay case needs. */
function manager1(bench, deviceStore) {
  return newManager({ backend: NET_QA40X, bench, deviceStore }).manager;
}

/** The remote-backend request seam (NetDeviceManager's call/callLocked), scripted and recorded. */
function benchSeam({ answers = {}, locked = {} } = {}) {
  return {
    calls: [], locked: [],
    async call(backend, request, fields) {
      this.calls.push({ backend, request, fields });
      const scripted = answers[request];
      return scripted === undefined ? null : scripted;
    },
    async callLocked(backend, request, fields) {
      this.locked.push({ backend, request, fields });
      const scripted = locked[request];
      return scripted === undefined ? {} : scripted;
    },
  };
}

/** A silent generator lane - enough to make the engine start the stream. */
const SILENCE = (destination, frames) => destination.fill(0, 0, frames * ENGINE_CHANNELS);

/** The value `reg` was last given since the recorded writes were cleared, or null. */
function writtenValue(transport, reg) {
  for (const write of transport.registerWrites) {
    if (write.reg === reg) return write.value;
  }
  return null;
}

/**
 * Starts the session, having cleared the recorded writes, and hands back the register
 * traffic it produced - the only observable of the manager's stored ranges and of the
 * suppliers the engine was built with.
 */
async function startedWrites(manager, transport) {
  const engine = await manager.acquireEngine(RATE_HZ);
  transport.registerWrites.length = 0;
  await engine.attachGenerator(SILENCE);
  return transport;
}

/**
 * The input/output range codes the DEVICE is given when the session starts - the only
 * observable of the manager's stored ranges, and the one that matters.
 */
async function startedRangeCodes(manager, transport) {
  await startedWrites(manager, transport);
  return {
    input: writtenValue(transport, REG_INPUT_FS),
    output: writtenValue(transport, REG_OUTPUT_FS),
  };
}

// --- enumeration ----------------------------------------------------------

test('one duplex handle per attached analyzer, reported identically on both directions', async () => {
  const { manager } = newManager();

  const inputs = await manager.listInputDevices();
  const outputs = await manager.listOutputDevices();

  assert.equal(inputs.length, 1, 'one handle per attached analyzer');
  assert.deepEqual(outputs.map(String), inputs.map(String), 'both directions list the same device');
  const ref = inputs[0];
  assert.ok(ref instanceof Qa40xDeviceRef);
  assert.ok(ref.isInput() && ref.isOutput(), 'the analyzer is ONE duplex device');
  assert.equal(ref.index, 0);
  assert.equal(ref.name, Qa40xModel.QA403);
  assert.equal(ref.model, Qa40xModel.QA403);
  assert.equal(ref.backend(), QA40X);
  assert.equal(ref.displayName(), '[0] QA403 (QuantAsylum QA40x) - QuantAsylum');
});

test('nothing attached enumerates empty and never throws; the index check quotes the count', async () => {
  const { manager: empty } = newManager({ devices: [] });

  assert.deepEqual(await empty.listInputDevices(), [], 'no device, no throw');
  assert.deepEqual(await empty.listOutputDevices(), []);
  await assert.rejects(() => empty.getDeviceByIndex(0, false),
    /QA40x device index out of range: 0 \(have 0 devices\)/);

  const { manager } = newManager();
  await assert.rejects(() => manager.getDeviceByIndex(1, true),
    /QA40x device index out of range: 1 \(have 1 device\)/,
    'singular for exactly one device');
  await assert.rejects(() => manager.getDeviceByIndex(-1, false),
    /out of range: -1/);
});

// --- formats --------------------------------------------------------------

test('the output depth list follows the I2S EDIT value; the input stays 24-bit', async () => {
  const { manager, settings } = newManager();
  const ref = (await manager.listInputDevices())[0];
  const depths = (formats) => formats.map((f) => f.bits);

  assert.deepEqual(depths(manager.listSupportedFormats(ref, true)), [24, 24, 24, 24],
    'one 24-bit format per rate while the front-panel port is off');
  assert.deepEqual(manager.listSupportedFormats(ref, true).map((f) => f.sampleRate),
    sampleRatesHz(Qa40xModel.QA403), 'rates come from the model (the QA403 adds 384 kHz)');
  assert.deepEqual(manager.listSupportedFormats(ref, true)[0], {
    encoding: 'PCM_SIGNED', sampleRate: 48000, bits: 24, channels: 2,
    frameBytes: 6, frameRate: 48000, bigEndian: false,
  }, 'signed PCM, stereo, little-endian, frame = (bits / 8) · channels');

  // The QA40x settings dialog is OK'd: the choice is PENDING, so it must move the depth
  // list (the combo reacts while the Preferences dialog is still open) but NOT the live
  // value the running session reads.
  await manager.openCustomPreferences(null);
  assert.equal(settings.i2sEnabledEdit(), true, 'the dialog result lands in the EDIT value');
  assert.equal(settings.i2sEnabled(), false, 'and never in the live value before the Preferences OK');

  assert.deepEqual(depths(manager.listSupportedFormats(ref, true)), [16, 32, 16, 32, 16, 32, 16, 32],
    'while the port is on, the output offers ITS frame widths');
  assert.deepEqual(depths(manager.listSupportedFormats(ref, false)), [24, 24, 24, 24],
    'the I2S port is an OUTPUT - it cannot change the capture path');
  assert.deepEqual(manager.listSupportedFormats({ index: 0 }, true), [],
    'a handle from another backend has no QA40x formats');
});

// --- the I2S live / edit boundary -----------------------------------------

test('the engine is built from the LIVE I2S value, never the pending edit', async () => {
  // The depth list above reads the EDIT value on purpose (the combo reacts while the
  // dialog is open). The ENGINE must not: it writes reg 0x0B/0x0A at every session
  // start, so an edit value read here would switch the front-panel port on for a user
  // who toggled it and then pressed CANCEL - hardware moved by a discarded edit.
  const pending = newManager();
  pending.settings.setI2sEnabledEdit(true);           // toggled, no Preferences OK yet

  await startedWrites(pending.manager, pending.transport);

  assert.equal(writtenValue(pending.transport, REG_I2S), I2S_STOP,
    'a pending toggle leaves the front-panel port off');
  assert.equal(writtenValue(pending.transport, REG_I2S_WIDTH), I2S_WIDTH_OFF,
    'and its frame width cleared');

  // Committed (the Preferences OK) - proof the supplier is read at the session
  // boundary at all, rather than the port being wired off.
  const committed = newManager();
  committed.settings.setI2sEnabledEdit(true);
  committed.settings.commitEdit();

  await startedWrites(committed.manager, committed.transport);

  assert.equal(writtenValue(committed.transport, REG_I2S), I2S_START,
    'the committed value does reach the hardware at the next session boundary');
  assert.equal(writtenValue(committed.transport, REG_I2S_WIDTH), i2sWidthCode(OUTPUT_BIT_DEPTH),
    'at the frame width the stored output depth selects');
});

test('reopening the settings dialog seeds it from the PENDING edit, not the live value', async () => {
  const { manager, settings, dialogOpens } = newManager({ i2sAccepted: true });

  await manager.openCustomPreferences(null);          // the user switches the port on...
  await manager.openCustomPreferences(null);          // ...and reopens the dialog

  assert.deepEqual(dialogOpens.map((call) => call.seed), [false, true],
    'the second open shows the edit the first one made; seeding from the live value would'
    + ' silently revert the toggle the user is still editing');
  assert.equal(settings.i2sEnabled(), false,
    'and nothing went live - only the Preferences OK commits');
});

// --- device card ----------------------------------------------------------

test('a fresh card gets the parked defaults, every row calibrated from the device', () => {
  const { manager, registered, settings } = newManager();

  const card = manager.buildProfile(Qa40xModel.QA403, calibration(), null);

  assert.equal(card.name, Qa40xModel.QA403);
  assert.deepEqual(card.match, [Qa40xModel.QA403]);
  assert.equal(card.input.channels, DeviceChannelMode.LINKED);
  assert.equal(card.output.channels, DeviceChannelMode.LINKED);
  assert.equal(card.input.calibrationFromDevice, true, 'the device owns its calibration');
  assert.equal(card.output.calibrationFromDevice, true);
  assert.deepEqual(card.input.ranges.map((r) => r.label), inputRangeDbvValues().map(rangeLabel),
    'one row per attenuator position, keyed by the plain "N dBV" label');
  assert.deepEqual(card.output.ranges.map((r) => r.label), outputRangeDbvValues().map(rangeLabel));
  assert.equal(card.input.activeRange, rangeLabel(SAFE_INPUT_DBV),
    'a range nobody has chosen is the protected one: maximum input attenuation');
  assert.equal(card.output.activeRange, rangeLabel(SAFE_OUTPUT_DBV),
    'and the low output range');
  assert.equal(card.input.activeRangeRight, null, 'nothing to carry over on a fresh card');
  assert.ok(card.input.ranges.every((r) => r.calibrated), 'device-owned values are never seed-refreshed away');
  assert.ok(card.output.ranges.every((r) => r.calibrated));
  // The verbose "really N dBFS" text is a DISPLAY label on the input rows only; the key
  // stays plain so the store and the range routing agree byte-for-byte.  It is a DECLARED
  // field now, so a row without one reads null (not undefined) and displayLabelOrKey
  // falls back to the key - which is what the ranges table shows for the output rows.
  assert.equal(card.input.ranges[0].displayLabel, verboseInputLabel(0));
  assert.equal(card.input.ranges[0].displayLabelOrKey(), verboseInputLabel(0));
  assert.equal(card.output.ranges[0].displayLabel, null);
  assert.equal(card.output.ranges[0].displayLabelOrKey(), card.output.ranges[0].label,
    'an output row shows its plain key');
  // Per-channel full scale: the direction's own Qa40xLevels function, the channel's own
  // cal factor. A swapped channel or direction changes every number here.
  for (const dbv of inputRangeDbvValues()) {
    const row = card.input.ranges[inputRangeCode(dbv)];
    assert.ok(Math.abs(row.fsLeft - inputFullScaleRmsVolts(dbv, linear(ADC_LEFT_DB))) < TOL,
      `input fsLeft at ${dbv} dBV`);
    assert.ok(Math.abs(row.fsRight - inputFullScaleRmsVolts(dbv, linear(ADC_RIGHT_DB))) < TOL,
      `input fsRight at ${dbv} dBV`);
  }
  for (const dbv of outputRangeDbvValues()) {
    const row = card.output.ranges[outputRangeCode(dbv)];
    assert.ok(Math.abs(row.fsLeft - outputFullScaleRmsVolts(dbv, linear(DAC_LEFT_DB))) < TOL,
      `output fsLeft at ${dbv} dBV`);
    assert.ok(Math.abs(row.fsRight - outputFullScaleRmsVolts(dbv, linear(DAC_RIGHT_DB))) < TOL,
      `output fsRight at ${dbv} dBV`);
  }
  assert.deepEqual(registered, [settings],
    'the constructor registers its settings block, which is what replays the saved values into it');
});

test('the output full scale is right in absolute volts, the DAC cal in the divisor', () => {
  const { manager } = newManager();

  const row = manager.buildProfile(Qa40xModel.QA403, calibration(), null)
    .output.ranges[outputRangeCode(18)];

  // Hand-computed, INDEPENDENT of Qa40xLevels - the loop above compares the card against
  // the very function that built it, so with a unity cal (and even with a non-unity one)
  // a cal factor moved from the divisor to the multiplier still passes. It must not:
  // outputFullScaleRmsVolts = 10^((N+3)/20) / (dacCal · √2), the cal DIVIDING because the
  // factor is the one dacInt32 multiplies a requested voltage by - a device that needs
  // more code for a volt reaches full scale at a HIGHER voltage, not a lower one.
  //   10^((18+3)/20) = 11.2201845
  //   left  cal +2 dB -> 1.2589254 · √2 = 1.7804253 -> 11.2201845 / 1.7804253 = 6.3020958 V
  //   right cal −2 dB -> 0.7943282 · √2 = 1.1233240 -> 11.2201845 / 1.1233240 = 9.9881488 V
  // The mutation is exactly channel-swapping here (·cal instead of /cal turns 6.30 into
  // 9.99 and back), so both channels are pinned.
  assert.ok(Math.abs(row.fsLeft - 6.302095821) < HAND_TOL, `+18 dBV output fsLeft: ${row.fsLeft}`);
  assert.ok(Math.abs(row.fsRight - 9.988148765) < HAND_TOL, `+18 dBV output fsRight: ${row.fsRight}`);
});

test("an existing card's aliases and active ranges survive the refresh", () => {
  const { manager } = newManager();
  const existing = new AudioDeviceProfile();
  existing.name = Qa40xModel.QA403;
  existing.match = [Qa40xModel.QA403, 'My bench analyzer'];
  existing.input.activeRange = rangeLabel(12);
  existing.input.activeRangeRight = rangeLabel(24);
  existing.output.activeRange = rangeLabel(-2);

  const card = manager.buildProfile(Qa40xModel.QA403, calibration(), existing);

  assert.deepEqual(card.match, [Qa40xModel.QA403, 'My bench analyzer'],
    'the user\'s match aliases are unioned on, the card name never duplicated');
  assert.equal(card.input.activeRange, rangeLabel(12), 'the selected input range survives');
  assert.equal(card.input.activeRangeRight, rangeLabel(24), 'so does the right channel\'s');
  assert.equal(card.output.activeRange, rangeLabel(-2));
  // The full-scale values are the FRESHLY READ ones, not whatever the old card carried.
  assert.ok(Math.abs(card.input.ranges[0].fsLeft - inputFullScaleRmsVolts(0, linear(ADC_LEFT_DB))) < TOL);
});

test('an active range the device has no row for falls back to the default', () => {
  const { manager } = newManager();
  const existing = new AudioDeviceProfile();
  existing.name = Qa40xModel.QA403;
  existing.input.activeRange = 'Line in 1.0 V';     // another card's label
  existing.input.activeRangeRight = '3 dBV';        // not a QA40x attenuator position
  existing.output.activeRange = rangeLabel(42);     // an INPUT range, not an output one

  const card = manager.buildProfile(Qa40xModel.QA403, calibration(), existing);

  assert.equal(card.input.activeRange, rangeLabel(SAFE_INPUT_DBV),
    'a label with no row cannot stay selected, and what it falls back to is the protected range');
  assert.equal(card.input.activeRangeRight, null, 'a dangling right label is dropped, not kept');
  assert.equal(card.output.activeRange, rangeLabel(SAFE_OUTPUT_DBV));
});

test('opening the session persists the card and adopts its active ranges', async () => {
  const existing = new AudioDeviceProfile();
  existing.name = Qa40xModel.QA403;
  existing.input.activeRange = rangeLabel(36);
  existing.output.activeRange = rangeLabel(-12);
  const { manager, transport, store } = newManager({ existingCard: existing });

  const codes = await startedRangeCodes(manager, transport);

  assert.equal(store.puts, 1, 'the card is written once per session open');
  assert.ok(store.saves >= 1, 'and persisted through the store\'s save');
  assert.equal(store.cards[0].input.calibrationFromDevice, true);
  assert.equal(codes.input, inputRangeCode(36), 'the session starts on the card\'s active input range');
  assert.equal(codes.output, outputRangeCode(-12));
});

// --- telemetry ------------------------------------------------------------

test('readDeviceInfo opens the device first and decodes every register', async () => {
  const { manager, finder, transport } = newManager({
    telemetry: [FIRMWARE, USB_MILLIVOLTS, USB_MILLIAMPS, TENTHS_DEGREE, CAPABILITY, CAPABILITY2, SERIAL],
  });

  const info = await manager.readDeviceInfo();

  assert.equal(finder.opens, 1, 'registers are only readable on an OPEN device');
  // WHICH registers, in WHICH order - pinned through the double's one ordered op log, the
  // same way qa40x-calibration.test.mjs pins the page select. The replies are a FIFO, so
  // the decoded values below hold even when a decoder is fed the wrong register's address:
  // formatUsbVoltage on the capability word is a plausible voltage, not a failure.
  assert.deepEqual(transport.ops, [...calPageOps(), ...telemetryReadOps(false), 'close'],
    'the open reads the cal page, then exactly these 7 registers - and hands the analyzer back');
  assert.equal(info.firmwareVersion, '60');
  assert.equal(info.usbVoltage, '4.980 V');
  assert.equal(info.usbCurrent, '0.250 A');
  assert.equal(info.isoCurrent, Qa40xDeviceInfo.UNAVAILABLE, 'the ISO supply is QA402-only');
  assert.equal(info.temperature, '31.5 °C');
  assert.equal(info.capability, '0x00000003');
  assert.equal(info.capability2, '0x0000000A');
  assert.equal(info.serialNumber, '0A1B2C3D');
});

test('the QA402 reports its ISO-supply current', async () => {
  const { manager, transport } = newManager({
    model: Qa40xModel.QA402,
    telemetry: [FIRMWARE, USB_MILLIVOLTS, USB_MILLIAMPS, ISO_MILLIAMPS, TENTHS_DEGREE,
      CAPABILITY, CAPABILITY2, SERIAL],
  });

  const info = await manager.readDeviceInfo();

  assert.equal(info.isoCurrent, '0.120 A');
  assert.equal(info.temperature, '31.5 °C', 'the reads stay in step with the register order');
  assert.deepEqual(transport.ops, [...calPageOps(), ...telemetryReadOps(true), 'close'],
    'the QA402-only ISO register is read, in its place in the sequence');
});

test('a read that fails part-way degrades the whole snapshot to NONE', async () => {
  const { manager, transport } = newManager({
    telemetry: [FIRMWARE, USB_MILLIVOLTS, USB_MILLIAMPS, TENTHS_DEGREE, CAPABILITY, CAPABILITY2, SERIAL],
  });
  await openDevice(manager);                    // opens the device, cal page consumed

  const real = transport.registerRead.bind(transport);
  let healthy = 3;
  transport.registerRead = (reg) => {
    if (healthy-- <= 0) throw new Error('pipe stalled');
    return real(reg);
  };
  let info;
  const logged = await warnings(async () => { info = await manager.readDeviceInfo(); });

  assert.equal(info, Qa40xDeviceInfo.NONE,
    'a panel must show dashes, never three real values and five wrong ones');
  assert.equal(info.temperature, Qa40xDeviceInfo.UNAVAILABLE);
  assert.equal(logged.length, 1, 'and the failure is reported once');
  assert.match(logged[0], /device info read failed/);
});

test('a wedged device leaves the settings dialog with dashes rather than an exception', async () => {
  const { manager, transport } = newManager();
  transport.registerRead = () => { throw new Error('unplugged'); };

  let info;
  await warnings(async () => { info = await manager.readDeviceInfo(); });

  assert.equal(info, Qa40xDeviceInfo.NONE);
  assert.equal(manager.hasCustomPreferences(), true, 'the QA40x always offers its own settings');
});

// --- active-range routing -------------------------------------------------

test('applyActiveRangeChange re-ranges the open device on both directions', async () => {
  const forInput = newManager();
  await openDevice(forInput.manager);                // opens; card default = 0 dBV / +18 dBV
  await forInput.manager.applyActiveRangeChange(QA40X, Qa40xModel.QA403, true, 42);
  assert.equal((await startedRangeCodes(forInput.manager, forInput.transport)).input,
    inputRangeCode(42), 'the committed input range reaches the device');

  const forOutput = newManager();
  await openDevice(forOutput.manager);
  await forOutput.manager.applyActiveRangeChange(QA40X, Qa40xModel.QA403, false, -12);
  assert.equal((await startedRangeCodes(forOutput.manager, forOutput.transport)).output,
    outputRangeCode(-12), 'the committed output range reaches the device');
});

test('applyActiveRangeChange ignores another backend, another card, and a closed device', async () => {
  const other = newManager();
  await other.manager.readDeviceInfo();
  await other.manager.applyActiveRangeChange(WEB_AUDIO, Qa40xModel.QA403, true, 24);
  assert.equal((await startedRangeCodes(other.manager, other.transport)).input,
    inputRangeCode(SAFE_INPUT_DBV), 'only QA40x re-ranges this hardware');

  const foreign = newManager();
  await foreign.manager.readDeviceInfo();
  await foreign.manager.applyActiveRangeChange(QA40X, 'Cosmos ADC', true, 24);
  assert.equal((await startedRangeCodes(foreign.manager, foreign.transport)).input,
    inputRangeCode(SAFE_INPUT_DBV), 'a change for another card is not ours to apply');

  const closed = newManager();
  await closed.manager.applyActiveRangeChange(QA40X, Qa40xModel.QA403, true, 24);
  assert.equal(closed.finder.opens, 0, 'a closed device is not opened just to re-range it');
  assert.equal((await startedRangeCodes(closed.manager, closed.transport)).input,
    inputRangeCode(SAFE_INPUT_DBV), 'the next open reads the range from the store instead');
});

test('setInputRange / setOutputRange validate before they store', async () => {
  const { manager, transport } = newManager();
  await openDevice(manager);

  await assert.rejects(() => manager.setInputRange(3), /0\.\.42 dBV in 6 dB steps/);
  await assert.rejects(() => manager.setOutputRange(0), /-12\/-2\/\+8\/\+18 dBV/);
  await manager.setInputRange(24);
  await manager.setOutputRange(8);

  const codes = await startedRangeCodes(manager, transport);
  assert.equal(codes.input, inputRangeCode(24), 'a rejected range left the stored one untouched');
  assert.equal(codes.output, outputRangeCode(8));
});

test('the constructor arms the range bridge, so a bus-published change reaches the device', async () => {
  const { manager, transport } = newManager({ armBus: true });
  await openDevice(manager);                    // open, and activeBackend() is QA40X

  assert.equal(manager.activeBackend(), QA40X);
  assert.equal(manager.cardName(), Qa40xModel.QA403);
  MessageBus.instance().publish(Events.DEVICE_ACTIVE_RANGE_CHANGED,
    { input: true, activeRangeLabel: rangeLabel(42) });

  // The routed change is queued on the manager's serialization chain, so the session
  // opened next necessarily sees it - no polling, no sleep.
  assert.equal((await startedRangeCodes(manager, transport)).input, inputRangeCode(42));
});

// --- shutdown -------------------------------------------------------------

test('shutdown parks the safe state in order, THEN closes the transport', async () => {
  const { manager, transport } = newManager();
  await openDevice(manager);
  transport.registerWrites.length = 0;
  transport.ops.length = 0;

  await manager.shutdown();

  assert.deepEqual(transport.registerWrites, [
    { reg: REG_RUN, value: RUN_STOP },
    { reg: REG_INPUT_FS, value: inputRangeCode(SAFE_INPUT_DBV) },
    { reg: REG_OUTPUT_FS, value: outputRangeCode(SAFE_OUTPUT_DBV) },
  ], 'stream stopped BEFORE the ranges move, input parked before output');
  assert.deepEqual(transport.ops, [
    `reg=${REG_RUN}:${RUN_STOP}`,
    `reg=${REG_INPUT_FS}:${inputRangeCode(SAFE_INPUT_DBV)}`,
    `reg=${REG_OUTPUT_FS}:${outputRangeCode(SAFE_OUTPUT_DBV)}`,
    'close',
  ], 'the transport closes LAST - a closed transport cannot park anything');
  assert.equal(inputRangeCode(SAFE_INPUT_DBV), 7, 'the parked input is maximum attenuation');
});

test('shutdown nulls the session, is idempotent, and lets the next use re-open', async () => {
  const { manager, transport, finder } = newManager();
  await openDevice(manager);
  await manager.shutdown();

  assert.equal(manager.cardName(), null, 'no open device any more');
  transport.ops.length = 0;
  await manager.shutdown();
  assert.deepEqual(transport.ops, [], 'a second shutdown touches nothing');

  transport.readReplies.push(...calPageWords());
  await manager.acquireEngine(RATE_HZ);
  assert.equal(finder.opens, 2, 'the next use claims the device again');
  assert.equal(manager.cardName(), Qa40xModel.QA403);
});

test('shutdown never throws, even with the device gone mid-park', async () => {
  const { manager, transport, finder } = newManager();
  await openDevice(manager);
  transport.registerWrite = () => { throw new Error('unplugged'); };
  transport.close = () => { throw new Error('handle gone'); };

  const logged = await warnings(() => manager.shutdown());

  assert.equal(logged.length, 2, 'both the park and the close are reported');
  assert.match(logged[0], /safe-state write failed/);
  assert.match(logged[1], /transport close failed/);
  assert.equal(manager.cardName(), null, 'the session is dropped regardless - the exit path must finish');
  assert.equal(finder.opens, 1);
});

// --- calibrationFromDevice on the real store ------------------------------

test("the manager's card round-trips calibrationFromDevice through the store", async () => {
  const storageKey = 'phonalyser.devices.test.qa40x.roundtrip';
  localStorage.removeItem(storageKey);
  const prefs = new Preferences(true);
  const store = new DeviceProfileStore(prefs, { storageKey, catalog: EMPTY_CATALOG });
  const { manager } = newManager({ deviceStore: store, prefs });

  await openDevice(manager);                    // opens -> card built + persisted

  const reopened = new DeviceProfileStore(prefs, { storageKey, catalog: EMPTY_CATALOG });
  const card = reopened.findAudioDeviceProfile(Qa40xModel.QA403);
  assert.ok(card != null, 'the card was persisted');
  assert.equal(card.input.calibrationFromDevice, true, 'the flag survives write + read');
  assert.equal(card.output.calibrationFromDevice, true);
  assert.equal(card.input.ranges.length, inputRangeDbvValues().length);
  assert.equal(JSON.parse(localStorage.getItem(storageKey)).audioDevices[0].input.calibrationFromDevice,
    true, 'and is in the document itself, not just the parsed object');
  assert.equal(reopened.getAudioDeviceProfiles()[0].input.calibrationFromDevice, true,
    'the defensive deep copy carries it too');
});

test('a calibrate write against a device-provided endpoint is a warned no-op', async () => {
  const storageKey = 'phonalyser.devices.test.qa40x.refuse';
  localStorage.removeItem(storageKey);
  const prefs = new Preferences(true);
  const store = new DeviceProfileStore(prefs, { storageKey, catalog: EMPTY_CATALOG });
  const { manager } = newManager({ deviceStore: store, prefs });
  await openDevice(manager);
  const written = store.findAudioDeviceProfile(Qa40xModel.QA403);
  const deviceInputFs = { left: written.input.ranges[0].fsLeft, right: written.input.ranges[0].fsRight };
  const deviceOutputFs = { left: written.output.ranges[0].fsLeft, right: written.output.ranges[0].fsRight };

  const logged = await warnings(() => {
    store.storeAdcCalibration(1.234, Qa40xModel.QA403);
    store.storeAdcCalibrationChannel('R', 1.234, Qa40xModel.QA403);
    store.storeDacCalibration(1.234, Qa40xModel.QA403);
    store.storeDacCalibrationChannel('R', 1.234, Qa40xModel.QA403);
  });

  assert.equal(logged.length, 4, 'each refusal is warned about, not silent');
  assert.match(logged[0], /calibration is device-provided for QA403/);
  const card = store.findAudioDeviceProfile(Qa40xModel.QA403);
  assert.deepEqual({ left: card.input.ranges[0].fsLeft, right: card.input.ranges[0].fsRight },
    deviceInputFs, 'the device\'s own input values are untouched');
  assert.deepEqual({ left: card.output.ranges[0].fsLeft, right: card.output.ranges[0].fsRight },
    deviceOutputFs, 'and its output values too');
});

test('the seed merge takes a device-owned range wholesale', () => {
  const storageKey = 'phonalyser.devices.test.qa40x.merge';
  const endpoint = (fs, calibrated) => ({
    channels: 'LINKED',
    calibrationFromDevice: true,
    ranges: [{ label: rangeLabel(0), fsVrms: { left: fs, right: fs }, calibrated }],
    activeRange: rangeLabel(0),
  });
  // A store written at contentVersion 1 whose row is flagged calibrated (a device card
  // marks its rows so the ordinary merge cannot refresh them away)...
  localStorage.setItem(storageKey, JSON.stringify({
    formatVersion: 1,
    contentVersion: 1,
    audioDevices: [{ name: Qa40xModel.QA403, match: [Qa40xModel.QA403], input: endpoint(9.0, true) }],
  }));
  // ...and a newer catalog that still declares the endpoint device-owned.
  const catalog = {
    formatVersion: 1,
    contentVersion: 2,
    audioDevices: [{ name: Qa40xModel.QA403, match: [Qa40xModel.QA403], input: endpoint(0.5, false) }],
  };

  const store = new DeviceProfileStore(new Preferences(true), { storageKey, catalog });

  const row = store.findAudioDeviceProfile(Qa40xModel.QA403).input.ranges[0];
  assert.equal(row.fsLeft, 0.5, 'the device owns the values, so the seed nominal wins');
  assert.equal(row.fsRight, 0.5);
  assert.equal(row.calibrated, false, 'and the stale calibrated flag is not carried over');
});

// ===========================================================================
// The analyzer on a SERVER - spec 4.6 (Java Qa40xSettingsUi.openRemote/commitEdit)
// ===========================================================================

const NET_QA40X = 'net:QA40X';

test('a remote selection fills the SAME dialog from the bench, and opens no local device', async () => {
  const bench = benchSeam({ answers: {
    'qa40x.settings': { i2sEnabled: true },
    'qa40x.info': { firmwareVersion: '2.3', usbVoltage: '5.01 V', serialNumber: 'QA403-42' },
  } });
  const { manager, transport, dialogOpens } = newManager({ backend: NET_QA40X, bench });

  await manager.openCustomPreferences(null);

  assert.deepEqual(bench.calls.map((c) => c.request), ['qa40x.settings', 'qa40x.info'],
    'both are lock-free reads');
  assert.equal(dialogOpens.length, 1);
  assert.equal(dialogOpens[0].seed, true, 'the port state the BENCH reports seeds the toggle');
  assert.equal(dialogOpens[0].info.firmwareVersion, '2.3');
  assert.equal(dialogOpens[0].info.serialNumber, 'QA403-42');
  assert.equal(dialogOpens[0].info.temperature, Qa40xDeviceInfo.UNAVAILABLE,
    'a field the bench did not send reads like an unread register, not a blank row');
  assert.equal(transport.ops.length, 0, 'the analyzer on THIS machine was never touched');
});

test('a bench that will not answer costs the read, not the panel', async () => {
  const bench = benchSeam();   // every call answers null
  const { manager, dialogOpens } = newManager({ backend: NET_QA40X, bench });

  await manager.openCustomPreferences(null);

  assert.equal(dialogOpens.length, 1, 'the dialog still opens');
  assert.equal(dialogOpens[0].seed, false);
  assert.equal(dialogOpens[0].info.firmwareVersion, Qa40xDeviceInfo.UNAVAILABLE);
});

test('an accepted CHANGE is written on the Preferences OK, under the analyzer\'s lock', async () => {
  const bench = benchSeam({ answers: { 'qa40x.settings': { i2sEnabled: false } } });
  const { manager, settings } = newManager({ backend: NET_QA40X, bench, i2sAccepted: true });

  await manager.openCustomPreferences(null);
  assert.deepEqual(bench.locked, [], 'nothing is sent while the Preferences dialog is still open');
  assert.equal(settings.i2sEnabledEdit(), false,
    'the port belongs to the BENCH - it is a pending write, never this build\'s preference block');

  await manager.commitCustomPreferencesEdit();

  assert.equal(bench.locked.length, 1);
  assert.equal(bench.locked[0].request, 'qa40x.settings');
  assert.equal(bench.locked[0].fields.i2sEnabled, true,
    'spec 4.6 needs the lock for a write - an ordinary call comes back NOT_LOCKED');
  assert.equal(bench.locked[0].backend, NET_QA40X);
});

test('a panel opened and dismissed writes nothing - and a cancelled session leaves nothing behind', async () => {
  const bench = benchSeam({ answers: { 'qa40x.settings': { i2sEnabled: true } } });
  const { manager } = newManager({ backend: NET_QA40X, bench, i2sAccepted: true });

  await manager.openCustomPreferences(null);      // accepted === seed: not a change
  await manager.commitCustomPreferencesEdit();
  assert.deepEqual(bench.locked, [], 'a write of the value the bench already has takes a lock for nothing');

  bench.answers = {};
  const { manager: second, } = newManager({ backend: NET_QA40X, bench, i2sAccepted: true });
  await second.openCustomPreferences(null);       // false -> true: a real change, now pending
  second.beginCustomPreferencesEdit();            // ...and the operator pressed Cancel
  await second.commitCustomPreferencesEdit();
  assert.deepEqual(bench.locked, [], 'what a cancelled session left pending must not reach the bench');
});

test('selecting a bench\'s analyzer syncs its card - the bench\'s factors and its ranges IN FORCE', async () => {
  const bench = benchSeam({ answers: {
    'qa40x.calibration': { adc: [{ dbv: 0, left: 1.02, right: 0.98 }], dac: [{ dbv: -12, left: 1.01, right: 1.03 }] },
    'qa40x.ranges': { activeInputDbv: 6, activeOutputDbv: -12 },
  } });
  bench.deviceName = () => 'QA403';
  const store = new DeviceProfileStore();
  const { manager } = newManager({ backend: NET_QA40X, bench, deviceStore: store });

  const card = await manager.syncRemoteCard(NET_QA40X);

  assert.equal(card.name, 'QA403', 'keyed on the remote device NAME - how everything resolves it');
  assert.equal(card.input.calibrationFromDevice, true,
    'the same card the local backend builds from the cal page - no remote special case downstream');
  assert.equal(card.input.activeRange, '6 dBV', 'the analyzer is already running under it');
  assert.equal(card.output.activeRange, '-12 dBV');
  const stored = store.findAudioDeviceProfile('QA403');
  assert.equal(stored.input.activeRange, '6 dBV',
    'written LIVE (recorded web divergence: the card section renders from the live store)');
  // The 0 dBV input row carries the bench's own factors, per channel - a range the bench said
  // nothing about keeps the neutral 1.0 rather than collapsing to zero full scale.
  const zeroRow = stored.input.ranges.find((r) => r.label === '0 dBV');
  assert.ok(zeroRow.fsLeft > 0 && zeroRow.fsRight > 0);
  assert.ok(Math.abs(zeroRow.fsLeft / zeroRow.fsRight - 1.02 / 0.98) < 1e-9,
    'the per-channel factors are the BENCH\'s, not this machine\'s');
});

/**
 * S1 (maintainer): "remote-QA40x shouldn't save card in devices storage!"
 *
 * The bench analyzer's card is a TRANSIENT OVERLAY - the dialog renders from it, and it is
 * gone with the tab. Persistence would be wrong twice over: it is another machine's device,
 * and it is unnecessary - the settle/scan tail re-syncs it on every arrival (B27).
 */
test('a bench card is never written to devices storage', async () => {
  const bench = benchSeam({ answers: {
    'qa40x.calibration': { adc: [{ dbv: 0, left: 1.0, right: 1.0 }], dac: [{ dbv: -12, left: 1.0, right: 1.0 }] },
    'qa40x.ranges': { activeInputDbv: 6, activeOutputDbv: -12 },
  } });
  bench.deviceName = () => 'QA403-overlay-only';
  const key = 'phonalyser.devices.overlay.' + Math.random();
  const store = new DeviceProfileStore(new Preferences(true), { storageKey: key, catalog: null });

  const card = await manager1(bench, store).syncRemoteCard(NET_QA40X);

  assert.equal(card.name, 'QA403-overlay-only');
  assert.equal(store.findAudioDeviceProfile('QA403-overlay-only'), card,
    'the dialog still resolves it - an overlay is consulted BEFORE the store');
  const doc = JSON.parse(globalThis.localStorage.getItem(key) || '{"audioDevices":[]}');
  assert.equal(doc.audioDevices.filter((c) => c.name === 'QA403-overlay-only').length, 0,
    'and nothing of the bench reached the persisted document');
});

/**
 * The collision the desktop hit: this machine has its OWN card named QA403 and the bench's
 * analyzer is called QA403 too. The overlay must not displace the stored card - not in the
 * document, and not after the overlay is dropped.
 */
test('a bench card never displaces a LOCAL card of the same name', async () => {
  const bench = benchSeam({ answers: {
    'qa40x.calibration': { adc: [{ dbv: 0, left: 2.0, right: 2.0 }], dac: [{ dbv: -12, left: 2.0, right: 2.0 }] },
    'qa40x.ranges': { activeInputDbv: 6, activeOutputDbv: -12 },
  } });
  bench.deviceName = () => 'QA403';
  const key = 'phonalyser.devices.collision.' + Math.random();
  const store = new DeviceProfileStore(new Preferences(true), { storageKey: key, catalog: null });
  const mine = new AudioDeviceProfile();
  mine.name = 'QA403';
  mine.match.push('QA403');
  mine.input.ranges.push(Object.assign(new DeviceRange(), { label: 'mine', fsLeft: 9.5, fsRight: 9.5 }));
  mine.input.activeRange = 'mine';
  store.putAudioDeviceProfile(mine);
  const before = globalThis.localStorage.getItem(key);

  await manager1(bench, store).syncRemoteCard(NET_QA40X);

  assert.equal(globalThis.localStorage.getItem(key), before,
    'the persisted document is byte-identical while the overlay is shown');
  assert.equal(store.findAudioDeviceProfile('QA403').input.activeRange, '6 dBV',
    'the dialog sees the BENCH card while it is selected');

  store.clearTransientProfiles();          // leaving the bench selection

  assert.equal(store.findAudioDeviceProfile('QA403').input.ranges[0].label, 'mine',
    'and this machine\'s own card is back, untouched');
  assert.equal(globalThis.localStorage.getItem(key), before);
});

/** Every persisting route goes through putAudioDeviceProfile, so the gate lives there: a
 *  card-section commit on the bench card (its active-range radio) must sweep nothing in. */
test('committing the bench card persists nothing', async () => {
  const bench = benchSeam({ answers: {
    'qa40x.calibration': { adc: [{ dbv: 0, left: 1.0, right: 1.0 }], dac: [{ dbv: -12, left: 1.0, right: 1.0 }] },
    'qa40x.ranges': { activeInputDbv: 6, activeOutputDbv: -12 },
  } });
  bench.deviceName = () => 'QA403-commit';
  const key = 'phonalyser.devices.commit.' + Math.random();
  const store = new DeviceProfileStore(new Preferences(true), { storageKey: key, catalog: null });
  const card = await manager1(bench, store).syncRemoteCard(NET_QA40X);
  const before = globalThis.localStorage.getItem(key);

  card.input.activeRange = '0 dBV';        // the operator moved the radio
  store.putAudioDeviceProfile(card);       // what CardSection._commitSelected does

  assert.equal(globalThis.localStorage.getItem(key), before, 'the document did not move');
  assert.equal(store.findAudioDeviceProfile('QA403-commit').input.activeRange, '0 dBV',
    'while the overlay itself carries the pick');
});

/** S2: the LOCAL analyzer's card is built at SELECTION, not only at the first capture -
 *  with an empty store the dialog used to show no ranges at all until something opened the
 *  device. It IS persisted: this installation's own device, desktop parity. */
test('selecting the LOCAL analyzer builds and PERSISTS its card', async () => {
  const key = 'phonalyser.devices.local-sync.' + Math.random();
  const store = new DeviceProfileStore(new Preferences(true), { storageKey: key, catalog: null });
  const { manager } = newManager({ backend: QA40X, deviceStore: store });

  const card = await manager.syncLocalCard();

  assert.equal(card.name, Qa40xModel.QA403);
  assert.equal(card.input.calibrationFromDevice, true);
  assert.ok(card.input.ranges.length > 1, 'the ranges the dialog had nothing to draw');
  const doc = JSON.parse(globalThis.localStorage.getItem(key));
  assert.equal(doc.audioDevices.filter((c) => c.name === Qa40xModel.QA403).length, 1,
    'this machine\'s own device: persisted, unlike a bench one');
});

/** WebUSB: no device, or no permission - the sync costs itself, never the dialog. */
test('a local analyzer that cannot be opened fails soft', async () => {
  const key = 'phonalyser.devices.local-fail.' + Math.random();
  const store = new DeviceProfileStore(new Preferences(true), { storageKey: key, catalog: null });
  const { manager } = newManager({ backend: QA40X, deviceStore: store, devices: [] });

  assert.equal(await manager.syncLocalCard(), null, 'no throw - the caller has a dialog to draw');
  const doc = JSON.parse(globalThis.localStorage.getItem(key));
  assert.equal(doc.audioDevices.length, 0, 'and nothing invented');
});

test('a bench that answers nothing leaves the stored card exactly as it was', async () => {
  const bench = benchSeam();
  // A name no earlier case wrote: the store is backed by the shared localStorage stub, so a
  // "nothing was written" assertion has to be about a card nothing else could have written.
  bench.deviceName = () => 'QA402-never-synced';
  const store = new DeviceProfileStore();
  const { manager } = newManager({ backend: NET_QA40X, bench, deviceStore: store });

  assert.equal(await manager.syncRemoteCard(NET_QA40X), null);
  assert.equal(store.findAudioDeviceProfile('QA402-never-synced'), null,
    'the full-scale math stays as it was');
});

test('the LOCAL analyzer still reads its own registers - the remote branch is not a takeover', async () => {
  const bench = benchSeam();
  const { manager, dialogOpens } = newManager({ backend: QA40X, bench, telemetry: [0, 0, 0, 0, 0, 0] });

  await manager.openCustomPreferences(null);

  assert.deepEqual(bench.calls, [], 'nothing crossed a wire for hardware on this machine');
  assert.equal(dialogOpens.length, 1);
});
