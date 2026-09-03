/*
 * Phonalyser web - QA40x USB discovery.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Mirrors org.edgo.audio.measure.sound.qa40x.Qa40xDeviceFinderTest - the VID/PID ->
 * model mapping (doc §2), the single-device rule (doc §2/§7) and the graceful
 * behaviour with no USB at all - over a fake navigator.usb, which the desktop test
 * cannot have (it assumeFalse's its way around a present libusb instead).
 *
 * The web port adds coverage the Java has no counterpart for, because the mechanics
 * it replaces are the risky part: that requestDevice() - which needs a USER GESTURE
 * - is entered ONLY from scan(), never from list() or the lazily-called open(), and
 * that a failed claim leaves the device closed.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import {
  QA_VID, Qa40xDevice, Qa40xDeviceFinder, Qa40xModel, fromProductId,
} from '../js/qa40x/qa40x-device-finder.js';
import { INTERFACE_0, WebUsbQa40xTransport } from '../js/qa40x/webusb-qa40x-transport.js';

const PID_QA402 = 0x4E37;
const PID_QA403 = 0x4E39;
/** The QA401 - a real QuantAsylum analyzer on the same VID, deliberately out of scope. */
const PID_QA401 = 0x4E27;
const FOREIGN_VID = 0x1234;

/** USBDevice double: records the open/configure/claim/close sequence in order. */
class FakeUsbDevice {
  constructor(vendorId, productId, serialNumber, configurationValue = null) {
    this.vendorId = vendorId;
    this.productId = productId;
    this.serialNumber = serialNumber;
    this.configuration = configurationValue == null ? null : { configurationValue };
    this.ops = [];
    /** Set to an Error to make claimInterface reject (a busy / vanished interface). */
    this.claimError = null;
  }

  async open() {
    this.ops.push('open');
  }

  async selectConfiguration(value) {
    this.ops.push(`selectConfiguration(${value})`);
    this.configuration = { configurationValue: value };
  }

  async claimInterface(index) {
    this.ops.push(`claimInterface(${index})`);
    if (this.claimError) {
      throw this.claimError;
    }
  }

  async releaseInterface(index) {
    this.ops.push(`releaseInterface(${index})`);
  }

  async close() {
    this.ops.push('close');
  }
}

/** navigator.usb double: a granted-device list plus a scriptable chooser. */
class FakeUsb {
  constructor(devices = []) {
    this.devices = devices;
    /** Chooser calls - the gesture-only path must show 0 for everything but scan(). */
    this.requestCalls = 0;
    /** Filters of the last requestDevice() call. */
    this.lastFilters = null;
    /** Device the chooser returns; null = the user dismissed it (WebUSB rejects). */
    this.picks = null;
    /** Set to an Error to make getDevices() reject. */
    this.enumerateError = null;
  }

  async getDevices() {
    if (this.enumerateError) {
      throw this.enumerateError;
    }
    return this.devices;
  }

  async requestDevice(options) {
    this.requestCalls++;
    this.lastFilters = options.filters;
    if (this.picks == null) {
      throw new Error('NotFoundError: No device selected.');
    }
    this.devices = [...this.devices, this.picks];
    return this.picks;
  }

  // The real navigator.usb is an EventTarget; the finder's watchDisconnect listens for
  // 'disconnect', which is the ONE signal an unplugged analyzer gives (no transfer ever fails -
  // WebUSB has no per-transfer timeout, they just stop completing).
  #listeners = [];
  addEventListener(type, fn) { if (type === 'disconnect') this.#listeners.push(fn); }
  /** Test hook: unplug `device`, as the browser would report it. */
  emitDisconnect(device) { for (const fn of this.#listeners) fn({ device }); }
}

const qa402 = (serial = 'AA01AA01') => new FakeUsbDevice(QA_VID, PID_QA402, serial);
const qa403 = (serial = 'BB02BB02') => new FakeUsbDevice(QA_VID, PID_QA403, serial);

// --- model mapping (Java: productId4E37IsQa402 / 4E39IsQa403 / unknownProductIdIsEmpty) ---

test('fromProductId: 0x4E37 is the QA402, 0x4E39 the QA403', () => {
  assert.equal(fromProductId(PID_QA402), Qa40xModel.QA402);
  assert.equal(fromProductId(PID_QA403), Qa40xModel.QA403);
});

test('fromProductId: an unknown product ID maps to no model', () => {
  // 0x4E27 is the QA401 - deliberately out of scope, so also unmapped here.
  assert.equal(fromProductId(PID_QA401), null);
  assert.equal(fromProductId(0x1234), null);
});

test('Qa40xModel names are the ones qa40x-protocol.js gates on', () => {
  // sampleRatesHz(model) fails CLOSED on an unknown name, so a renamed member here
  // would cost the QA403 its 384 kHz rate - and throw instead of degrading.
  assert.deepEqual(Qa40xModel, { QA402: 'QA402', QA403: 'QA403' });
});

// --- enumeration ---------------------------------------------------------------

test('list: maps each granted device VID/PID to its model, with a readable identity', async () => {
  const finder = new Qa40xDeviceFinder(new FakeUsb([qa402('AA01AA01'), qa403('BB02BB02')]));

  const found = await finder.list();

  assert.deepEqual(found.map((d) => d.model), [Qa40xModel.QA402, Qa40xModel.QA403]);
  assert.deepEqual(found.map((d) => d.serialNumber), ['AA01AA01', 'BB02BB02']);
  assert.equal(String(found[1]), 'QA403 @ serial BB02BB02');
});

test('list: a device that reports no serial still renders', async () => {
  const finder = new Qa40xDeviceFinder(new FakeUsb([new FakeUsbDevice(QA_VID, PID_QA402, undefined)]));

  const [found] = await finder.list();

  assert.equal(found.serialNumber, null);
  assert.equal(String(found), 'QA402 @ serial (no serial)');
});

test('list: skips foreign devices', async () => {
  // getDevices() returns EVERY device this origin was ever granted - a keyboard, a
  // QA401, another vendor's V-USB gadget sharing 0x16C0 - so both halves of the
  // VID/PID test have to hold, not just one.
  const usb = new FakeUsb([
    new FakeUsbDevice(QA_VID, PID_QA401, 'QA401'),          // right vendor, out-of-scope model
    new FakeUsbDevice(FOREIGN_VID, PID_QA402, 'IMPOSTOR'),  // QA402's PID under another vendor
    qa403('BB02BB02'),
  ]);
  const finder = new Qa40xDeviceFinder(usb);

  const found = await finder.list();

  assert.deepEqual(found.map((d) => d.model), [Qa40xModel.QA403]);
  assert.deepEqual(found.map((d) => d.serialNumber), ['BB02BB02']);
});

test('list: no WebUSB yields an empty list and does not throw', async () => {
  const finder = new Qa40xDeviceFinder(null);

  assert.equal(finder.available(), false);
  assert.deepEqual(await finder.list(), []);
});

test('list: an enumeration failure yields an empty list and does not throw', async () => {
  // A getDevices() rejection (a permissions-policy-blocked context) must degrade
  // exactly like an absent WebUSB: the audio path asks on every open.
  const usb = new FakeUsb([qa403()]);
  usb.enumerateError = new Error('SecurityError: Access denied.');
  const finder = new Qa40xDeviceFinder(usb);

  assert.deepEqual(await finder.list(), []);
});

test('list: never opens the device chooser, even with nothing granted', async () => {
  // requestDevice() needs user activation. list() is reachable from the audio path,
  // where there is none - a chooser there would throw AND nag.
  const usb = new FakeUsb([]);
  const finder = new Qa40xDeviceFinder(usb);

  assert.deepEqual(await finder.list(), []);
  assert.equal(usb.requestCalls, 0);
});

// --- the single-device rule (Java: requireSingle* ) ----------------------------

test('requireSingle: accepts exactly one', () => {
  const finder = new Qa40xDeviceFinder(new FakeUsb());

  assert.doesNotThrow(() => finder.requireSingle(1));
});

test('requireSingle: rejects none, with the desktop message', () => {
  const finder = new Qa40xDeviceFinder(new FakeUsb());

  assert.throws(() => finder.requireSingle(0), { message: 'No QA402/QA403 found on USB' });
});

test('requireSingle: rejects more than one, naming the count', () => {
  const finder = new Qa40xDeviceFinder(new FakeUsb());

  assert.throws(() => finder.requireSingle(3), {
    message: '3 QA40x devices attached - connect exactly one '
      + '(the QA40x backend, like ASIO401, drives a single device)',
  });
});

// --- scan(): the one gesture-only path -----------------------------------------

test('scan: prompts with the QA40x VID/PID filters when nothing is granted yet', async () => {
  const picked = qa403('BB02BB02');
  const usb = new FakeUsb([]);
  usb.picks = picked;
  const finder = new Qa40xDeviceFinder(usb);

  const found = await finder.scan();

  assert.equal(usb.requestCalls, 1);
  assert.deepEqual(usb.lastFilters, [
    { vendorId: QA_VID, productId: PID_QA402 },
    { vendorId: QA_VID, productId: PID_QA403 },
  ]);
  assert.deepEqual(found, [new Qa40xDevice(Qa40xModel.QA403, 'BB02BB02')]);
});

test('scan: does not prompt when a device is already granted and connected', async () => {
  const usb = new FakeUsb([qa402('AA01AA01')]);
  const finder = new Qa40xDeviceFinder(usb);

  const found = await finder.scan();

  assert.equal(usb.requestCalls, 0);
  assert.deepEqual(found.map((d) => d.model), [Qa40xModel.QA402]);
});

test('scan: a dismissed chooser yields an empty list and does not throw', async () => {
  const usb = new FakeUsb([]);   // picks stays null -> requestDevice rejects (NotFoundError)
  const finder = new Qa40xDeviceFinder(usb);

  assert.deepEqual(await finder.scan(), []);
  assert.equal(usb.requestCalls, 1);
});

test('scan: no WebUSB yields an empty list and does not throw', async () => {
  assert.deepEqual(await new Qa40xDeviceFinder(null).scan(), []);
});

// --- open() --------------------------------------------------------------------

test('open: opens, selects configuration 1, claims interface 0, and never prompts', async () => {
  const device = qa403();
  const usb = new FakeUsb([device]);
  const finder = new Qa40xDeviceFinder(usb);

  const transport = await finder.open();

  assert.ok(transport instanceof WebUsbQa40xTransport);
  assert.equal(transport.device, device);
  // The ORDER is the contract: claiming before the configuration is selected fails
  // outright on macOS, where the OS leaves the device unconfigured.
  assert.deepEqual(device.ops, ['open', 'selectConfiguration(1)', `claimInterface(${INTERFACE_0})`]);
  assert.equal(usb.requestCalls, 0, 'open() runs off the audio path - no user gesture to spend');
});

test('open: skips a redundant SET_CONFIGURATION when the OS already configured the device', async () => {
  const device = new FakeUsbDevice(QA_VID, PID_QA403, 'BB02BB02', 1);
  const finder = new Qa40xDeviceFinder(new FakeUsb([device]));

  await finder.open();

  assert.deepEqual(device.ops, ['open', `claimInterface(${INTERFACE_0})`]);
});

test('open: closes the device when the claim fails, and propagates', async () => {
  // An opened-but-unclaimed device left dangling poisons the next open attempt.
  const device = qa403();
  device.claimError = new Error('NetworkError: Unable to claim interface.');
  const finder = new Qa40xDeviceFinder(new FakeUsb([device]));

  await assert.rejects(() => finder.open(), /Unable to claim interface/);
  assert.deepEqual(device.ops, ['open', 'selectConfiguration(1)', `claimInterface(${INTERFACE_0})`, 'close']);
});

test('open: enforces the single-device rule', async () => {
  const none = new Qa40xDeviceFinder(new FakeUsb([]));
  await assert.rejects(() => none.open(), { message: 'No QA402/QA403 found on USB' });

  const two = new Qa40xDeviceFinder(new FakeUsb([qa402(), qa403()]));
  await assert.rejects(() => two.open(), {
    message: '2 QA40x devices attached - connect exactly one '
      + '(the QA40x backend, like ASIO401, drives a single device)',
  });
});

test('open: a foreign device alone is not a QA40x', async () => {
  const finder = new Qa40xDeviceFinder(new FakeUsb([new FakeUsbDevice(FOREIGN_VID, PID_QA403, 'IMPOSTOR')]));

  await assert.rejects(() => finder.open(), { message: 'No QA402/QA403 found on USB' });
});

test('open: throws when the browser has no WebUSB', async () => {
  await assert.rejects(() => new Qa40xDeviceFinder(null).open(), /not available/);
});

test('open: an enumeration failure propagates instead of masquerading as "no device"', async () => {
  // list() swallows it by contract; open() must not - the caller has to be able to
  // tell "no analyzer attached" from "the browser refused to enumerate".
  const usb = new FakeUsb([qa403()]);
  usb.enumerateError = new Error('SecurityError: Access denied.');
  const finder = new Qa40xDeviceFinder(usb);

  await assert.rejects(() => finder.open(), /SecurityError/);
});

test('Qa40xDevice is immutable', () => {
  const device = new Qa40xDevice(Qa40xModel.QA402, 'AA01AA01');

  assert.throws(() => { device.model = Qa40xModel.QA403; }, TypeError);
});

// --- watchDisconnect: the unplug signal ---------------------------------------

test('watchDisconnect fires for a QA40x and hands over its record', () => {
  const usb = new FakeUsb([qa403()]);
  const finder = new Qa40xDeviceFinder(usb);
  const seen = [];
  finder.watchDisconnect((device) => seen.push(device));

  usb.emitDisconnect(qa403('BB02BB02'));

  assert.equal(seen.length, 1);
  assert.equal(seen[0].model, Qa40xModel.QA403, 'the handler gets the matched model record');
});

test('watchDisconnect ignores foreign devices - any USB unplug fires the same event', () => {
  const usb = new FakeUsb([qa403()]);
  const finder = new Qa40xDeviceFinder(usb);
  const seen = [];
  finder.watchDisconnect((device) => seen.push(device));

  usb.emitDisconnect(new FakeUsbDevice(FOREIGN_VID, 0x0001, 'X'));   // someone's mouse dongle
  usb.emitDisconnect(new FakeUsbDevice(QA_VID, PID_QA401, 'Y'));     // a QA401 - out of scope

  assert.deepEqual(seen, [], 'neither may kill a running QA402/QA403 session');
});

test('watchDisconnect is a no-op without WebUSB', () => {
  const finder = new Qa40xDeviceFinder(null);
  finder.watchDisconnect(() => { throw new Error('must never fire'); });   // must not throw either
});
