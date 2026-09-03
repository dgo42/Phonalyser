/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The server's cards and the operator's pick among them (doc/NET-PROTOCOL.md §4.3, faithful port
 * of gui/sound/BenchCards): the chooser's list is filtered per DIRECTION, a pick is STAGED until
 * the Preferences OK (Cancel must mean nothing happened anywhere - on another machine it could not
 * be taken back), the write is LOCKED, and the local mirror is keyed "<serverId>/<name>" and
 * written only after the bench accepted.
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

const { BenchCards } = await import('../js/net/bench-cards.js');
const { CalibrationCopyOffers } = await import('../js/net/calibration-copy-offers.js');
const { DeviceProfileStore, AudioDeviceProfile, DeviceRange } =
  await import('../js/store/device-profiles.js');
const { makeNetDeviceRef } = await import('../js/net/net-device-ref.js');
const { NetFields } = await import('../js/net/net-proto.js');

const NET_QA40X = 'net:QA40X';
const SERVER = 'uuid-bench-1';

function benchSeam({ cards = null, accept = true } = {}) {
  return {
    calls: [], locked: [],
    async call(backend, request, fields) {
      this.calls.push({ backend, request, fields });
      // cards.list answers the scripted card list (null = a bench that will not answer);
      // every other read-only request - cards.put above all - is simply accepted.
      if (request === 'cards.list') return cards == null ? null : { [NetFields.CARDS]: cards };
      return {};
    },
    async callLocked(backend, request, fields) {
      this.locked.push({ backend, request, fields });
      return accept ? {} : null;
    },
  };
}

const device = (name = 'QA403', isInput = true, boundCard = null) => makeNetDeviceRef({
  index: 0, name, description: 'd', vendor: 'QuantAsylum', isInput, remoteBackend: 'QA40X',
  boundCard,
});

test('the chooser is offered only the cards that can serve its DIRECTION', async () => {
  const bench = benchSeam({ cards: [
    { name: 'QA403', input: true, output: true },
    { name: 'Line in only', input: true, output: false },
    { name: 'Amp out', input: false, output: true },
  ] });
  const cards = new BenchCards({ bench, store: new DeviceProfileStore() });

  assert.deepEqual(await cards.list(NET_QA40X, true), ['QA403', 'Line in only']);
  assert.deepEqual(await cards.list(NET_QA40X, false), ['QA403', 'Amp out'],
    'a card with no row for a direction would invite a binding that measures nothing');
});

test('a bench that will not answer shows NOTHING - never another server\'s list', async () => {
  const cards = new BenchCards({ bench: benchSeam(), store: new DeviceProfileStore() });

  assert.deepEqual(await cards.list(NET_QA40X, true), []);
});

test('a pick is STAGED - nothing reaches the bench until the dialog\'s OK', async () => {
  const bench = benchSeam();
  const cards = new BenchCards({ bench, store: new DeviceProfileStore() });

  cards.stage(NET_QA40X, true, 'QA403', 'QA403');
  cards.stage(NET_QA40X, false, 'QA403', 'Amp out');

  assert.deepEqual(bench.locked, [], 'Cancel has to mean nothing happened anywhere');
  assert.equal(cards.stagedCard(NET_QA40X, true, 'QA403'), 'QA403');
  assert.equal(cards.stagedCard(NET_QA40X, false, 'QA403'), 'Amp out',
    'one duplex device, listed under one name, is TWO separate choices');
  assert.deepEqual([...cards.stagedFor(NET_QA40X, true)], [['QA403', 'QA403']]);
  cards.clearStaged();
  assert.equal(cards.stagedCard(NET_QA40X, true, 'QA403'), null);
});

test('the commit is LOCKED, and the mirror is keyed "<serverId>/<name>"', async () => {
  const bench = benchSeam();
  const store = new DeviceProfileStore();
  const cards = new BenchCards({ bench, store });

  assert.equal(await cards.bind(NET_QA40X, SERVER, device(), 'QA403'), true);

  assert.equal(bench.locked.length, 1);
  assert.equal(bench.locked[0].request, 'device.setCard');
  assert.equal(bench.locked[0].fields[NetFields.NAME], 'QA403',
    'the ref travels with the write - the lock is on exactly that device+direction');
  assert.equal(bench.locked[0].fields[NetFields.CARD], 'QA403');
  assert.equal(store.boundCardName(`${SERVER}/QA403`), 'QA403',
    'the mirror tells two benches\' identically-named analyzers apart');
  assert.equal(store.boundCardName('QA403'), null, 'a local device\'s plain key is untouched');
});

test('a refused bind leaves no local record of a choice that is in force nowhere', async () => {
  const bench = benchSeam({ accept: false });
  const store = new DeviceProfileStore();
  const cards = new BenchCards({ bench, store });

  assert.equal(await cards.bind(NET_QA40X, SERVER, device('QA402'), 'QA402'), false);
  assert.equal(store.boundCardName(`${SERVER}/QA402`), null);
});

test('an empty card name unbinds - back to the bench\'s own name-match resolution', async () => {
  const bench = benchSeam();
  const store = new DeviceProfileStore();
  const cards = new BenchCards({ bench, store });
  await cards.bind(NET_QA40X, SERVER, device('QA405'), 'QA405');

  await cards.bind(NET_QA40X, SERVER, device('QA405'), '');

  assert.equal(bench.locked[1].fields[NetFields.CARD], null, 'null is the wire\'s "unbind"');
  assert.equal(store.boundCardName(`${SERVER}/QA405`), null);
});

test('the BENCH\'s answer wins over the mirror - another operator may have re-bound it', async () => {
  const store = new DeviceProfileStore();
  const cards = new BenchCards({ bench: benchSeam(), store });
  store.bindDeviceToCard(`${SERVER}/QA403`, 'my old pick');

  assert.equal(cards.boundCard(SERVER, device('QA403', true, 'what the bench says'), 'QA403'),
    'what the bench says');
  assert.equal(cards.boundCard(SERVER, device('QA403'), 'QA403'), 'my old pick',
    'the mirror covers the moment before the catalogue has been read');
});

test('a calibration write carries the ref and the two full scales, under that device\'s lock', async () => {
  const bench = benchSeam();
  const cards = new BenchCards({ bench, store: new DeviceProfileStore() });

  assert.equal(await cards.calibrate(NET_QA40X, device('Line in', true), 1.5, 1.52), true);

  const write = bench.locked[0];
  assert.equal(write.request, 'device.setCalibration');
  assert.equal(write.fields[NetFields.INPUT], true);
  assert.equal(write.fields[NetFields.FS_RMS_LEFT], 1.5);
  assert.equal(write.fields[NetFields.FS_RMS_RIGHT], 1.52);
});

test('ruling D4: the copy sends THIS machine\'s card values, unconverted, and only if it has any',
  async () => {
    const bench = benchSeam();
    const store = new DeviceProfileStore(null, { storageKey: 'phonalyser.devices.copy.1' });
    const card = new AudioDeviceProfile();
    card.name = 'Focusrite';
    card.match = ['Bench line in'];
    for (const [ep, fs] of [[card.input, 1.25], [card.output, 0.5]]) {
      const row = new DeviceRange();
      row.label = 'default';
      row.fsLeft = fs; row.fsRight = fs * 1.01;
      ep.ranges.push(row);
      ep.activeRange = 'default';
    }
    store.putAudioDeviceProfile(card);
    const cards = new BenchCards({ bench, store });

    assert.equal(await cards.copyCardCalibrationToBench(NET_QA40X, device('Line in'), 'Bench line in', true), true);
    const write = bench.locked[0];
    assert.equal(write.request, 'device.setCalibration');
    // The card holds RMS in BOTH directions, and spec 4.3 carries RMS - no √2 on the way out.
    assert.equal(write.fields[NetFields.FS_RMS_LEFT], 1.25);
    assert.equal(write.fields[NetFields.FS_RMS_RIGHT], 1.25 * 1.01);

    // A name this machine has no calibrated card for is not a copy - and sends nothing.
    assert.equal(await cards.copyCardCalibrationToBench(NET_QA40X, device('Line in'), 'Unknown box', true), false);
    assert.equal(bench.locked.length, 1);
  });

test('the copy offer is spent once per bench, DIRECTION and device - and only when SETTLED', () => {
  const offers = new CalibrationCopyOffers();

  assert.equal(offers.mayAsk(NET_QA40X, true, 'QA403'), true);
  assert.equal(offers.mayAsk(NET_QA40X, true, 'QA403'), true,
    'asking is not answering: a wire glitch must not cost the operator the offer (S4)');

  offers.settle(NET_QA40X, true, 'QA403');

  assert.equal(offers.mayAsk(NET_QA40X, true, 'QA403'), false, 'asked and answered, either way');
  offers.settle(NET_QA40X, true, 'QA403');   // idempotent
  assert.equal(offers.mayAsk(NET_QA40X, true, 'QA403'), false);
  // A duplex device is two separate uncalibrated endpoints; without the direction in the key the
  // input's offer would silently spend the one the output was owed.
  assert.equal(offers.mayAsk(NET_QA40X, false, 'QA403'), true);
  assert.equal(offers.mayAsk('net:WASAPI', true, 'QA403'), true, 'another bench is another device');
});

// ---------------------------------------------------------------------------
// S4: the WHOLE card goes up (cards.put + device.setCard), values-only as the fallback -
// the web half of the desktop's BenchCards.propagateLocalCard.
// ---------------------------------------------------------------------------

/** A local card that recognises `deviceName`, with a two-row range table so a values-only
 *  copy is visibly less than the card. */
function localCard(name, deviceName, { fromDevice = false } = {}) {
  const card = new AudioDeviceProfile();
  card.name = name;
  card.match = [deviceName];
  for (const [ep, fs] of [[card.input, 1.25], [card.output, 0.5]]) {
    for (const [label, mul] of [['default', 1], ['pad 20 dB', 10]]) {
      const row = new DeviceRange();
      row.label = label;
      row.fsLeft = fs * mul; row.fsRight = fs * mul * 1.01;
      row.calibrated = true;
      ep.ranges.push(row);
    }
    ep.activeRange = 'default';
    ep.calibrationFromDevice = fromDevice;
  }
  return card;
}

function storeWith(card, key) {
  const store = new DeviceProfileStore(null, { storageKey: key, catalog: null });
  if (card) store.putAudioDeviceProfile(card);
  return store;
}

test('S4: the whole card goes up - cards.put UNLOCKED, then the binding under the lock', async () => {
  const bench = benchSeam();
  const store = storeWith(localCard('Focusrite', 'Bench line in'), 'phonalyser.devices.s4.1');
  const cards = new BenchCards({ bench, store });

  const outcome = await cards.propagateLocalCard(NET_QA40X, SERVER, device('Bench line in'),
    'Bench line in', true);

  assert.equal(outcome, BenchCards.Copied.CARD);
  assert.equal(bench.calls[0].request, 'cards.put', 'the card first - nothing is bound to it yet');
  const content = bench.calls[0].fields[NetFields.CONTENT];
  assert.equal(content.name, 'Focusrite');
  assert.equal(content.input.ranges.length, 2, 'the whole range table, not one pair of numbers');
  assert.equal(content.input.ranges[0].fsVrms.left, 1.25);
  assert.ok(content.match.includes('Bench line in'), 'the device name the bench recognises it by');
  assert.equal(bench.locked[0].request, 'device.setCard', 'and THEN the binding, which is a write');
  assert.equal(bench.locked[0].fields[NetFields.CARD], 'Focusrite');
});

test('S4: the content carries no displayLabel - the standing ban, on the wire too', async () => {
  const bench = benchSeam();
  const card = localCard('Focusrite', 'Bench line in');
  card.input.ranges[0].displayLabel = 'nobody asked for this';
  const store = storeWith(card, 'phonalyser.devices.s4.2');
  const cards = new BenchCards({ bench, store });

  await cards.propagateLocalCard(NET_QA40X, SERVER, device('Bench line in'), 'Bench line in', true);

  const row = bench.calls[0].fields[NetFields.CONTENT].input.ranges[0];
  assert.equal('displayLabel' in row, false, 'display text is the dialog\'s, never the wire\'s');
  assert.equal(row.label, 'default');
});

test('S4: a bench that will not take the CARD still gets the values', async () => {
  // cards.put is refused (a name it already has); device.setCalibration is not.
  const bench = benchSeam();
  bench.call = async function (backend, request, fields) {
    this.calls.push({ backend, request, fields });
    return request === 'cards.put' ? null : {};
  };
  const store = storeWith(localCard('Focusrite', 'Bench line in'), 'phonalyser.devices.s4.3');
  const cards = new BenchCards({ bench, store });

  const outcome = await cards.propagateLocalCard(NET_QA40X, SERVER, device('Bench line in'),
    'Bench line in', true);

  assert.equal(outcome, BenchCards.Copied.VALUES_ONLY);
  assert.equal(bench.locked[0].request, 'device.setCalibration');
  assert.equal(bench.locked[0].fields[NetFields.FS_RMS_LEFT], 1.25);
});

test('S4: a card that landed but could not be BOUND is reported as itself', async () => {
  const bench = benchSeam({ accept: false });   // every LOCKED write refused -> the bind fails
  const store = storeWith(localCard('Focusrite', 'Bench line in'), 'phonalyser.devices.s4.4');
  const cards = new BenchCards({ bench, store });

  const outcome = await cards.propagateLocalCard(NET_QA40X, SERVER, device('Bench line in'),
    'Bench line in', true);

  assert.equal(outcome, BenchCards.Copied.CARD_UNBOUND,
    'a real card is on the bench, unbound - saying "nothing happened" would send the '
    + 'operator round again into the name it just created');
  assert.equal(bench.locked.filter((w) => w.request === 'device.setCalibration').length, 0,
    'the card IS there with its values in it - nothing left for the fallback to add');
});

test('S4: nothing at all reaches the bench when this machine has no calibrated card', async () => {
  const bench = benchSeam();
  const cards = new BenchCards({ bench, store: storeWith(null, 'phonalyser.devices.s4.5') });

  assert.equal(await cards.propagateLocalCard(NET_QA40X, SERVER, device('Bench line in'),
    'Bench line in', true), BenchCards.Copied.NOTHING_TO_COPY);
  assert.deepEqual(bench.calls, []);
  assert.deepEqual(bench.locked, []);
});

test('S4/R3: propagating changes no CARD in this machine\'s storage', async () => {
  const bench = benchSeam();
  const key = 'phonalyser.devices.s4.6';
  // The card recognises the device by SUBSTRING, so binding the full device name into the copy
  // is a real change - and the store's own card must not see it.
  const store = storeWith(localCard('Focusrite', 'Focusrite'), key);
  const dev = 'Bench Focusrite 2i2';
  const before = JSON.parse(backing.get(key));
  const cards = new BenchCards({ bench, store });

  await cards.propagateLocalCard(NET_QA40X, SERVER, device(dev), dev, true);

  const after = JSON.parse(backing.get(key));
  // Structural equality of the cards, not of the whole document: the binding mirror below is a
  // legitimate write, so the document itself is NOT byte-identical and must not be asserted so.
  assert.deepEqual(after.audioDevices, before.audioDevices,
    'every stored card is unchanged - it was the SOURCE; the values now live on the server');
  assert.deepEqual(store.findAudioDeviceProfile('Focusrite').match, ['Focusrite'],
    'the device name was bound into the COPY that was sent, never into the store\'s card');
  // The copy's match list is what the bench recognises the device by. Binding the name into it
  // is IDEMPOTENT (Java AudioDeviceProfile.bindDeviceName): this card already matches the device
  // by substring, so nothing is appended - and the assertion above is what proves the copy is
  // detached either way.
  assert.deepEqual(bench.calls[0].fields[NetFields.CONTENT].match, ['Focusrite']);
  // The ONE thing that is written is the binding MIRROR, keyed under the server's id - a card
  // NAME, not a calibration, and the maintainer's explicit order (it is what lets the chooser
  // show the pick before the bench has answered).
  assert.deepEqual(after.bindings, { [`${SERVER}/${dev}`]: 'Focusrite' });
  assert.equal(before.bindings, undefined);
});

/** S5: "QA40x has generated card/calibration read from device, therefore - not necessary to
 *  copy from client to server". A local card whose values are the DEVICE's own is never
 *  pushed: they are that analyzer's, and the bench would refuse them anyway. */
test('S5: a device-generated local card is never copied up', async () => {
  const bench = benchSeam();
  const store = storeWith(localCard('QA403', 'QA403', { fromDevice: true }),
    'phonalyser.devices.s5.1');
  const cards = new BenchCards({ bench, store });

  const outcome = await cards.propagateLocalCard(NET_QA40X, SERVER, device('QA403'), 'QA403', true);

  assert.equal(outcome, BenchCards.Copied.NOTHING_TO_COPY);
  assert.deepEqual(bench.calls, [], 'no cards.put');
  assert.deepEqual(bench.locked, [], 'and no values either');
});

test('a refused calibration (a calibrationFromDevice card) is a plain false', async () => {
  // The bench answers BAD_REQUEST for a card whose values come from the analyzer itself - not a
  // client's to write. It arrives as null through the seam, and there is nothing the operator can
  // do about a write the UI should not have offered.
  const bench = benchSeam({ accept: false });
  const cards = new BenchCards({ bench, store: new DeviceProfileStore() });

  assert.equal(await cards.calibrate(NET_QA40X, device('QA403'), 2.0, 2.0), false);
});
