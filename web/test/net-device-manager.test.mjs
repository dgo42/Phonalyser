/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The remote backend's device half: the catalogue of spec 4.3, the lock register, and the two
 * things only this client can notice - a device it HOLDS leaving the bench, and a session that
 * ended under it. The session is a fake NetConnection (no socket, no clock).
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import { NetDeviceManager, isMixedContentBlock } from '../js/net/net-device-manager.js';
import { NetRefusal } from '../js/net/net-connection.js';
import { NetCloseReason } from '../js/net/net-close-reason.js';
import { MessageType, NetFields, ErrorCode } from '../js/net/net-proto.js';
import { NetPreferences, makeServerEntry } from '../js/net/net-preferences.js';
import { DeviceFailureReason } from '../js/enums/device-failure-reason.js';
import { MessageBus } from '../js/bus/message-bus.js';
import { Events } from '../js/bus/events.js';

/** A NetConnection double: it records the requests and answers from a script. */
class FakeConnection {
  constructor() {
    this.sent = [];
    this.events = [];
    this.closeListeners = [];
    this.isOpen = true;
    this.serverName = 'Bench QA403';
    this.serverId = 'uuid-1';
    this.closedWith = null;
    this._nextId = 0;
    /** t -> the answer to give (a function or a literal). */
    this.answers = {};
  }
  newRequest(t) { return { t, id: ++this._nextId }; }
  async request(message) {
    this.sent.push(message);
    const scripted = this.answers[message.t];
    const answer = typeof scripted === 'function' ? scripted(message) : scripted;
    return answer || { t: MessageType.RESP, id: message.id, ok: true, data: {} };
  }
  uiRequest(message) { return this.request(message); }
  refusal(what, answer) {
    const e = answer.error || {};
    return new NetRefusal(`${what}: ${e.code} - ${e.message}`,
      e[NetFields.REASON] ? DeviceFailureReason[e[NetFields.REASON]] : DeviceFailureReason.UNKNOWN);
  }
  addEventListener(l) { this.events.push(l); }
  removeEventListener(l) { this.events = this.events.filter((x) => x !== l); }
  addCloseListener(l) { this.closeListeners.push(l); }
  removeCloseListener(l) { this.closeListeners = this.closeListeners.filter((x) => x !== l); }
  close(reason) { this.closedWith = reason; this.isOpen = false; }
  /** Delivers a server event to whoever subscribed. */
  deliver(event) { for (const l of [...this.events]) l(event); }
  ofType(t) { return this.sent.filter((m) => m.t === t); }
}

/** A Ticker double: it records the period and lets a test run the task by hand, so the 1 s
 *  peer poll is exercised without waiting a second (the seam ticker.js exists for). */
class FakeTicker {
  constructor() { this.periodMs = 0; this.task = null; this.starts = 0; this.stops = 0; }
  start(periodMs, task) { this.periodMs = periodMs; this.task = task; this.starts++; }
  stop() { this.task = null; this.stops++; }
  /** One tick, awaited - the poll is async and the assertions are about what it wrote. */
  async tick() { if (this.task) { this.task(); } await Promise.resolve(); await Promise.resolve(); }
}

/** A prober double answering ONE server's peer table (spec 2.2's GET /servers). */
class FakePeers {
  constructor(peers = []) { this.peers = peers; this.asked = []; }
  async probePeers(host, port) { this.asked.push(`${host}:${port}`); return this.peers; }
  async probeInfo() { return null; }
}

/** The NetRefusal `work` threw - node's assert.throws hands the error to a validator but
 *  answers nothing, and these assertions are about the refusal's own fields. */
function refusalOf(work) {
  try {
    work();
  } catch (e) {
    assert.ok(e instanceof NetRefusal, `expected a NetRefusal, got ${e}`);
    return e;
  }
  assert.fail('nothing was thrown');
  return null;
}

/** One backend entry in devices.list shape (spec 4.3). */
function backendEntry(devices) {
  return { [NetFields.BACKEND]: 'QA40X', [NetFields.DEVICES]: devices };
}

function device({ index = 0, name = 'QA403', input = true, lockBy = null, cal = undefined,
  card = undefined, hasBitDepth = true, formats = [{ rate: 48000, bits: 24, channels: 2 }] } = {}) {
  const d = {
    [NetFields.INDEX]: index, [NetFields.NAME]: name, [NetFields.DESCRIPTION]: 'desc',
    [NetFields.VENDOR]: 'QuantAsylum', [NetFields.INPUT]: input,
    [NetFields.OUTPUT]: !input, [NetFields.HAS_BIT_DEPTH]: hasBitDepth,
    [NetFields.FORMATS]: formats.map((f) => ({
      [NetFields.RATE]: f.rate, [NetFields.BITS]: f.bits, [NetFields.CHANNELS]: f.channels,
    })),
    [NetFields.LOCK]: lockBy == null ? null : { [NetFields.BY]: lockBy },
  };
  if (cal !== undefined) d[NetFields.CAL] = cal;
  if (card !== undefined) d[NetFields.CARD] = card;
  return d;
}

/** A manager already connected and pointed at QA40X with `devices`. */
async function routed(devices) {
  const connection = new FakeConnection();
  const preferences = new NetPreferences();
  const manager = new NetDeviceManager({ preferences, openConnection: async () => connection });
  await manager.connect(makeServerEntry('uuid-1', 'Bench QA403', '10.0.0.1', 8377, true));
  connection.answers[MessageType.BACKEND_SELECT] = (m) => ({
    t: MessageType.RESP, id: m.id, ok: true, data: backendEntry(devices),
  });
  await manager.selectBackend('QA40X');
  return { manager, connection, preferences };
}

/** Collects AUDIO_DEVICE_ERROR payloads published while `body` runs. */
async function withDeviceErrors(body) {
  const seen = [];
  const listener = (p) => seen.push(p);
  MessageBus.instance().subscribe(Events.AUDIO_DEVICE_ERROR, listener);
  try { await body(); } finally { MessageBus.instance().unsubscribe(Events.AUDIO_DEVICE_ERROR, listener); }
  return seen;
}

test('backend.select fills the combos in ONE round trip', async () => {
  const { manager, connection } = await routed([
    device({ index: 0, name: 'QA403', input: true }),
    device({ index: 0, name: 'QA403', input: false }),
  ]);

  assert.equal(connection.ofType(MessageType.DEVICES_LIST).length, 0,
    'spec 4.3 shapes the answer as devices.list precisely so nothing has to be asked again');
  assert.equal(manager.listInputDevices().length, 1);
  assert.equal(manager.listOutputDevices().length, 1);
  const input = manager.listInputDevices()[0];
  assert.equal(input.remoteBackend, 'QA40X', 'the wire name travels with the ref');
  assert.equal(input.backend, 'QA40X', 'the TRUE type is what type-specific control keys on');
  assert.equal(input.carrier, 'NET', 'and the carrier is what routes the dispatch at this manager');
  assert.deepEqual(manager.listSupportedFormats(input),
    [{ sampleRate: 48000, bits: 24, channels: 2 }],
    'the field names are the ones every other manager\'s formats use, so the Preferences '
    + 'dialog reads a bench\'s formats through the very code that reads the analyzer\'s');
  assert.equal(manager.hasBitDepth(input), true, 'the SERVER declares it, per device');
  assert.equal(manager.hasBitDepth(), true,
    'and with no device it answers for the BACKEND - the question the depth ROW asks');
});

test('a server backend this build has no constant for is still reachable', async () => {
  const connection = new FakeConnection();
  const manager = new NetDeviceManager({ preferences: new NetPreferences(), openConnection: async () => connection });
  await manager.connect(makeServerEntry('uuid-1', 'B', '10.0.0.1', 8377, true));
  connection.answers[MessageType.BACKEND_SELECT] = (m) => ({ t: MessageType.RESP, id: m.id, ok: true,
    data: { [NetFields.BACKEND]: 'SOMETHING_NEW', [NetFields.DEVICES]: [device({ name: 'x' })] } });

  await manager.selectBackend('SOMETHING_NEW');

  assert.equal(manager.listInputDevices()[0].backend, 'NET',
    'the honest "reachable but not identifiable here"');
});

test('the lock overview comes from the catalogue, not from a refusal at open time', async () => {
  const { manager } = await routed([device({ name: 'QA403', lockBy: "Developer's laptop" })]);

  assert.equal(manager.lockedBy(manager.listInputDevices()[0]), "Developer's laptop");
});

// ---------------------------------------------------------------------------
// The peer table of the connected server (spec 2.2) - "type one address and the room appears"
// ---------------------------------------------------------------------------

/** A manager connected to one bench, with the peer table and the clock in the test's hands. */
async function connectedWithPeers(peers) {
  const connection = new FakeConnection();
  const preferences = new NetPreferences();
  const prober = new FakePeers(peers);
  const ticker = new FakeTicker();
  const manager = new NetDeviceManager({
    preferences, prober, peerTicker: ticker, openConnection: async () => connection });
  await manager.connect(makeServerEntry('uuid-1', 'Bench A', '10.0.0.1', 8377, true));
  return { manager, preferences, prober, ticker, connection };
}

test('E: a CONNECTED server is asked for its peer table, once a second', async () => {
  // The whole of the web's discovery: the client never hears the LAN beacon, so the room can
  // only arrive from a server that does. It used to arrive on ONE path - the operator typing an
  // address into the dialog - so connecting (or being auto-connected by the page's own server)
  // learned of nobody (maintainer bench: "don't get known server from connected!!!").
  const { preferences, prober, ticker } = await connectedWithPeers([
    makeServerEntry('uuid-2', 'Bench B', '10.0.0.2', 8377, false),
  ]);

  assert.equal(ticker.starts, 1, 'the poll starts with the session');
  assert.equal(ticker.periodMs, 1000, 'at the cadence the maintainer set');
  await ticker.tick();

  assert.deepEqual(prober.asked, ['10.0.0.1:8377'], 'the CONNECTED server is the one asked');
  const known = preferences.getServers();
  assert.equal(known.get('uuid-2').name, 'Bench B', 'and its peer is now known');
  assert.equal(preferences.getEditServers().has('uuid-2'), true,
    'in the copy the Servers dialog paints from, so it shows without reopening anything');
});

test('E: the poll stops with the session - nothing asks a server we no longer talk to',
  async () => {
    const { manager, ticker, prober } = await connectedWithPeers([]);
    await ticker.tick();
    const askedWhileUp = prober.asked.length;

    await manager.disconnect();

    assert.equal(ticker.stops >= 1, true, 'the ticker was stopped');
    await ticker.tick();                       // whatever a stray timer would have done
    assert.equal(prober.asked.length, askedWhileUp, 'and nothing was asked after the goodbye');
  });

test('E: a session that DIES under us stops the poll too', async () => {
  const { ticker, connection, prober } = await connectedWithPeers([]);
  await ticker.tick();
  const askedWhileUp = prober.asked.length;

  for (const listener of connection.closeListeners) listener(NetCloseReason.TRANSPORT_CLOSED);

  await ticker.tick();
  assert.equal(prober.asked.length, askedWhileUp);
});

test('E: peers are deduped by serverId, and a typed-in one stays the operator\'s', async () => {
  const peer = makeServerEntry('uuid-2', 'Bench B', '10.0.0.2', 8377, false);
  const { preferences, ticker } = await connectedWithPeers([peer]);
  // The operator typed this very bench in before it was ever discovered.
  preferences.putServer({ ...peer, manual: true });

  await ticker.tick();
  await ticker.tick();

  const known = preferences.getServers();
  assert.equal(known.size, 2, 'the connected server and the one peer - not one row per round');
  assert.equal(known.get('uuid-2').manual, true,
    'being discovered does not make a typed-in bench disposable');
});

test('E: a peer that MOVED is refreshed, not duplicated', async () => {
  const prober = new FakePeers([makeServerEntry('uuid-2', 'Bench B', '10.0.0.2', 8377, false)]);
  const preferences = new NetPreferences();
  const ticker = new FakeTicker();
  const manager = new NetDeviceManager({
    preferences, prober, peerTicker: ticker, openConnection: async () => new FakeConnection() });
  await manager.connect(makeServerEntry('uuid-1', 'Bench A', '10.0.0.1', 8377, true));
  await ticker.tick();

  prober.peers = [makeServerEntry('uuid-2', 'Bench B', '10.0.0.9', 8377, false)];
  await ticker.tick();

  assert.equal(preferences.getServers().size, 2);
  assert.equal(preferences.getServers().get('uuid-2').host, '10.0.0.9',
    'the same installation at a new address is the same entry with fresher contents');
});

test('a device SOMEBODY ELSE holds is refused here, as in use - not sent as a doomed open',
  async () => {
    // The catalogue already carries the holder, so an open that could only come back
    // DEVICE_LOCKED is not worth a round trip - and the operator must read "in use by another
    // application", never a generic failure (maintainer bench: "reason unknown instead of
    // device is used by other app").
    const { manager, connection } = await routed([device({ name: 'QA403', lockBy: 'Other client' })]);
    const before = connection.sent.length;

    const refusal = refusalOf(() => manager.getDeviceByName('QA403', false));

    assert.equal(refusal.reason, DeviceFailureReason.DEVICE_IN_USE);
    assert.match(refusal.message, /Other client/, 'and it names who has it');
    assert.equal(connection.sent.length, before, 'nothing was sent to the bench');
  });

test('a device WE hold is opened, not refused - our own lock is not somebody else\'s',
  async () => {
    const { manager } = await routed([device({ name: 'QA403', lockBy: 'Phonalyser.web' })]);
    const ref = manager.listInputDevices()[0];
    manager.lockTaken(ref);

    assert.equal(manager.getDeviceByName('QA403', false), ref);
  });

test('NO device selected is refused as such, and never travels as the name "null"', async () => {
  // What a combo whose every row is locked answers (its options are all disabled, so the
  // browser holds no selection at all). It used to reach the wire, where the bench could only
  // say it has no device called "null" - a fault of this screen reported as a fault of the bench.
  const { manager, connection } = await routed([device({ name: 'QA403' })]);
  const before = connection.sent.length;

  for (const empty of [null, undefined, '']) {
    const refusal = refusalOf(() => manager.getDeviceByName(empty, false));
    assert.equal(refusal.reason, DeviceFailureReason.DEVICE_NOT_FOUND);
    assert.match(refusal.message, /no input device is selected/);
    assert.doesNotMatch(refusal.message, /null/, 'and it never quotes the missing name');
  }
  assert.equal(connection.sent.length, before, 'nothing was sent to the bench');
});

test('OUR OWN lock is not a holder - the device we are measuring on stays ours', async () => {
  // The bench reports every lock, this session's included, and the combo turns a holder into
  // "in use by ..." plus an option that cannot be picked. Reported for our own lock, that would
  // tell the operator their own measurement device belongs to somebody else and refuse them the
  // pick of it. The desktop's seam drops it the same way (NetBenchUi.lockedBy).
  const { manager } = await routed([device({ name: 'QA403', lockBy: 'Phonalyser.web' })]);
  const ref = manager.listInputDevices()[0];
  assert.equal(manager.lockedBy(ref), 'Phonalyser.web', 'somebody else, until we take it');

  manager.lockTaken(ref);

  assert.equal(manager.lockedBy(ref), null, 'ours - so no holder, and the row stays pickable');
  assert.equal(manager.holdsLock(ref), true);
});

test('cal is read by SHAPE - null, absent and a zero full scale all mean uncalibrated', async () => {
  const { manager } = await routed([
    device({ index: 0, name: 'good', cal: { fsRmsLeft: 1.5, fsRmsRight: 1.5 } }),
    device({ index: 1, name: 'null cal', cal: null }),
    device({ index: 2, name: 'absent' }),
    device({ index: 3, name: 'zero', cal: { fsRmsLeft: 0, fsRmsRight: 1.5 } }),
    device({ index: 4, name: 'empty', cal: {} }),
  ]);
  const by = (n) => manager.listInputDevices().find((d) => d.name === n);

  assert.deepEqual(manager.calibrationOf(by('good')), { fsRmsLeft: 1.5, fsRmsRight: 1.5 });
  for (const n of ['null cal', 'absent', 'zero', 'empty']) {
    assert.equal(manager.calibrationOf(by(n)), null,
      `${n}: 0 V full scale is the divisor that turns every reading into infinity`);
  }
});

test('the card in force is carried; a blank one is no binding', async () => {
  const { manager } = await routed([
    device({ index: 0, name: 'bound', card: 'QA403' }),
    device({ index: 1, name: 'blank', card: '' }),
    device({ index: 2, name: 'none', card: null }),
  ]);
  const by = (n) => manager.listInputDevices().find((d) => d.name === n);

  assert.equal(manager.boundCardOf(by('bound')), 'QA403');
  assert.equal(manager.boundCardOf(by('blank')), null);
  assert.equal(manager.boundCardOf(by('none')), null);
});

test('ev.devices.changed replaces the catalogue whole - an empty one included', async () => {
  const { manager, connection } = await routed([device({ name: 'QA403' })]);

  connection.deliver({ t: MessageType.EV_DEVICES_CHANGED,
    [NetFields.BACKENDS]: [backendEntry([device({ name: 'QA403' }), device({ index: 1, name: 'new one' })])] });
  assert.equal(manager.listInputDevices().length, 2);

  connection.deliver({ t: MessageType.EV_DEVICES_CHANGED, [NetFields.BACKENDS]: [backendEntry([])] });
  assert.equal(manager.listInputDevices().length, 0,
    'a server with nothing left to offer is not a message to be second-guessed');
});

test('a device this session HOLDS leaving the bench is reported, and its lock forgotten', async () => {
  const { manager, connection } = await routed([device({ name: 'QA403' })]);
  const ref = manager.listInputDevices()[0];
  assert.equal(await manager.acquireDevice(ref), null);
  assert.equal(manager.holdsLock(ref), true);

  const seen = await withDeviceErrors(async () => {
    connection.deliver({ t: MessageType.EV_DEVICES_CHANGED, [NetFields.BACKENDS]: [backendEntry([])] });
  });

  assert.equal(seen.length, 1, 'only this client knows what it holds, so only it can ask');
  assert.equal(seen[0].reason, DeviceFailureReason.DEVICE_DISCONNECTED.name);
  assert.equal(manager.holdsLock(ref), false,
    'the lock is forgotten - a release could only be answered DEVICE_STALE - but withDeviceLock '
    + 'must not go on believing it holds one');
  assert.equal(connection.ofType(MessageType.DEVICE_RELEASE).length, 0, 'and nothing is sent');
});

test('ev.device.error is LOG-ONLY here - the failing path reports it once, itself', async () => {
  const { connection } = await routed([device()]);

  const seen = await withDeviceErrors(async () => {
    connection.deliver({ t: MessageType.EV_DEVICE_ERROR, [NetFields.DIRECTION]: 'input',
      [NetFields.DETAIL]: 'PaErrorCode -9996', [NetFields.REASON]: 'DEVICE_NOT_ANSWERING' });
  });

  assert.deepEqual(seen, [],
    'an input-lane failure finishes the capture stream\'s own ring and the pane reports from '
    + 'there; telling the operator here as well would report one failure twice');
});

test('a GAP is confessed on the analyzers\' own re-sync seam, not as a device error', async () => {
  const { manager } = await routed([device()]);
  const banners = [];
  const listener = (key) => banners.push(key);
  MessageBus.instance().subscribe(Events.FFT_CAPTURE_RESYNC, listener);
  const errors = await withDeviceErrors(async () => {
    try { manager.gap(128); } finally { MessageBus.instance().unsubscribe(Events.FFT_CAPTURE_RESYNC, listener); }
  });

  assert.deepEqual(banners, ['fft.warning.netGap'], 'the same blinking confession an overrun raises');
  assert.deepEqual(errors, [], 'the stream carries on - a gap is not a device error');
});

test('classifyFailure hands on what the bench said - and only that', async () => {
  const { manager } = await routed([device()]);

  assert.equal(manager.classifyFailure(new NetRefusal('x', DeviceFailureReason.DEVICE_IN_USE)),
    DeviceFailureReason.DEVICE_IN_USE, 'the classification was done on the bench');
  assert.equal(manager.classifyFailure(new Error('socket died mid-open')),
    DeviceFailureReason.UNKNOWN, 'anything that is not a refusal was never the far device\'s failure');
});

test('withDeviceLock takes, works, and gives back - even when the work throws', async () => {
  const { manager, connection } = await routed([device({ name: 'QA403' })]);
  const ref = manager.listInputDevices()[0];

  const value = await manager.withDeviceLock(ref, async () => 'written');
  assert.equal(value, 'written');
  assert.equal(connection.ofType(MessageType.DEVICE_ACQUIRE).length, 1);
  assert.equal(connection.ofType(MessageType.DEVICE_RELEASE).length, 1);
  assert.equal(manager.holdsLock(ref), false);

  await assert.rejects(() => manager.withDeviceLock(ref, async () => { throw new Error('write failed'); }));
  assert.equal(connection.ofType(MessageType.DEVICE_RELEASE).length, 2,
    'a lock left behind is a device no other client can ever take again');
});

test('withDeviceLock under a lock this session ALREADY holds takes and returns nothing', async () => {
  const { manager, connection } = await routed([device({ name: 'QA403' })]);
  const ref = manager.listInputDevices()[0];
  await manager.acquireDevice(ref);
  connection.sent.length = 0;

  await manager.withDeviceLock(ref, async () => 'written');

  assert.deepEqual(connection.sent, [], 'nothing is taken and nothing is given back');
  assert.equal(manager.holdsLock(ref), true, 'and the lock this session took is still its own');
});

test('a device that is not free is not written, and says who has it', async () => {
  const { manager, connection } = await routed([device({ name: 'QA403' })]);
  connection.answers[MessageType.DEVICE_ACQUIRE] = (m) => ({ t: MessageType.RESP, id: m.id, ok: false,
    error: { code: ErrorCode.DEVICE_LOCKED, message: 'in use', by: 'Lab PC' } });
  const ref = manager.listInputDevices()[0];

  const refusal = await manager.acquireDevice(ref);
  assert.match(refusal.message, /DEVICE_LOCKED/);
  assert.equal(manager.holdsLock(ref), false);

  let ran = false;
  assert.equal(await manager.withDeviceLock(ref, async () => { ran = true; }), null);
  assert.equal(ran, false, 'the work never runs without the lock');
});

test('disconnect gives every lock back ON THE WIRE, then says goodbye', async () => {
  const { manager, connection } = await routed([
    device({ index: 0, name: 'in' }), device({ index: 1, name: 'out', input: false }),
  ]);
  await manager.acquireDevice(manager.listInputDevices()[0]);
  await manager.acquireDevice(manager.listOutputDevices()[0]);

  await manager.disconnect();

  assert.equal(connection.ofType(MessageType.DEVICE_RELEASE).length, 2,
    'spec 4.3 keeps a lock once taken - dropping the register would strand both devices');
  assert.equal(connection.closedWith, NetCloseReason.BYE);
  assert.equal(manager.listInputDevices().length, 0, 'nothing offers a device that cannot be opened');
  assert.equal(manager.getConnectedServer(), null);
});

test('a session that ends UNDER us stops the modules and empties the catalogue', async () => {
  const { manager, connection } = await routed([device()]);

  const seen = await withDeviceErrors(async () => {
    for (const l of connection.closeListeners) l(NetCloseReason.KEEPALIVE_TIMEOUT);
  });

  assert.equal(seen.length, 1, 'spec 4.1: stop all modules, show the connection error');
  assert.equal(manager.listInputDevices().length, 0);
  assert.equal(manager.getConnectedServer(), null);
});

test('our own goodbye is not a fault', async () => {
  const { manager, connection } = await routed([device()]);

  const seen = await withDeviceErrors(async () => {
    for (const l of connection.closeListeners) l(NetCloseReason.BYE);
  });

  assert.deepEqual(seen, []);
});

test('connecting remembers the bench LIVE - a Cancel must not make it vanish', async () => {
  const { manager, preferences } = await routed([device()]);

  assert.equal(preferences.getServers().has('uuid-1'), true, 'live, not staged');
  assert.equal(preferences.getLastConnected(), 'uuid-1');
  assert.equal(manager.getConnectedServer().serverId, 'uuid-1');
});

// ===========================================================================
// The selected backend's own requests - spec 4.6's QA40x extension
// ===========================================================================

const NET_QA40X = 'net:QA40X';

test('a backend request reaches the bench and answers its data', async () => {
  const { manager, connection } = await routed([device()]);
  connection.answers[MessageType.QA40X_RANGES] = (m) => ({ t: MessageType.RESP, id: m.id, ok: true,
    data: { [NetFields.ACTIVE_INPUT_DBV]: 6, [NetFields.ACTIVE_OUTPUT_DBV]: -12 } });

  const answer = await manager.call(NET_QA40X, MessageType.QA40X_RANGES);

  assert.equal(answer[NetFields.ACTIVE_INPUT_DBV], 6);
  assert.equal(connection.ofType(MessageType.QA40X_RANGES).length, 1);
});

test('a request for another backend, an unknown name, or a refusal is a null - never a throw', async () => {
  const { manager, connection } = await routed([device()]);
  connection.sent.length = 0;

  // A panel left open on another of the server's backends must not have its reads answered by
  // whichever backend this session happens to be routed at now. A VALID net: value, spelled the
  // way the combo spells it - an unparseable one would only prove that garbage is refused.
  assert.equal(await manager.call('net:JAVASOUND', MessageType.QA40X_INFO), null);
  assert.equal(await manager.call('NET:JAVASOUND', MessageType.QA40X_INFO), null,
    'and the old uppercase spelling names no backend at all any more');
  assert.equal(await manager.call('WEB_AUDIO', MessageType.QA40X_INFO), null);
  assert.equal(await manager.call(NET_QA40X, 'qa40x.somethingNewer'), null,
    'a message this build cannot form is refused here, not sent to be answered UNSUPPORTED');
  assert.equal(connection.sent.length, 0, 'and nothing went out for any of them');

  connection.answers[MessageType.QA40X_INFO] = (m) => ({ t: MessageType.RESP, id: m.id, ok: false,
    error: { code: ErrorCode.UNSUPPORTED, message: 'no analyzer here' } });
  assert.equal(await manager.call(NET_QA40X, MessageType.QA40X_INFO), null,
    'an unreachable bench is a value the panel cannot show, not an error it can fix');
});

test('a backend-wide write takes ANY device of the backend, and gives it back', async () => {
  const { manager, connection } = await routed([device({ index: 0, name: 'QA403' }),
    device({ index: 0, name: 'QA403', input: false })]);
  connection.sent.length = 0;

  const answer = await manager.callLocked(NET_QA40X, MessageType.QA40X_SET_INPUT_RANGE,
    { [NetFields.DBV]: 6 });

  assert.notEqual(answer, null);
  const order = connection.sent.map((m) => m.t);
  assert.deepEqual(order, [MessageType.DEVICE_ACQUIRE, MessageType.QA40X_SET_INPUT_RANGE,
    MessageType.DEVICE_RELEASE], 'spec 4.6: the analyzer\'s lock, either direction');
  assert.equal(connection.ofType(MessageType.QA40X_SET_INPUT_RANGE)[0][NetFields.DBV], 6);
});

test('a busy first device is not the answer - the next one is tried', async () => {
  const { manager, connection } = await routed([device({ index: 0, name: 'QA403' }),
    device({ index: 0, name: 'QA403', input: false })]);
  let acquires = 0;
  connection.answers[MessageType.DEVICE_ACQUIRE] = (m) => (++acquires === 1
    ? { t: MessageType.RESP, id: m.id, ok: false,
      error: { code: ErrorCode.DEVICE_LOCKED, message: 'in use', by: 'Lab PC' } }
    : { t: MessageType.RESP, id: m.id, ok: true, data: {} });

  const answer = await manager.callLocked(NET_QA40X, MessageType.QA40X_SETTINGS,
    { [NetFields.I2S_ENABLED]: true });

  assert.notEqual(answer, null, 'a bench with several devices has no reason to refuse the write');
  assert.equal(acquires, 2);
});

test('a lock this session ALREADY holds is used and NOT given back', async () => {
  const { manager, connection } = await routed([device()]);
  await manager.acquireDevice(manager.listInputDevices()[0]);   // the capture's lock
  connection.sent.length = 0;

  await manager.callLocked(NET_QA40X, MessageType.QA40X_SETTINGS, { [NetFields.I2S_ENABLED]: true });

  assert.deepEqual(connection.sent.map((m) => m.t), [MessageType.QA40X_SETTINGS],
    'releasing would hit spec 4.3\'s "also closes any open stream" and kill the live capture');
});

test('a request that NAMES a device is locked on exactly that device', async () => {
  const { manager, connection } = await routed([device({ index: 0, name: 'QA403' }),
    device({ index: 3, name: 'QA403', input: false })]);
  connection.sent.length = 0;

  await manager.callLocked(NET_QA40X, MessageType.DEVICE_SET_CALIBRATION,
    { [NetFields.INDEX]: 3, [NetFields.INPUT]: false, [NetFields.NAME]: 'QA403',
      [NetFields.FS_RMS_LEFT]: 2.0, [NetFields.FS_RMS_RIGHT]: 2.0 });

  const acquired = connection.ofType(MessageType.DEVICE_ACQUIRE)[0];
  assert.equal(acquired[NetFields.INDEX], 3);
  assert.equal(acquired[NetFields.INPUT], false,
    'a DAC calibration taken out under an INPUT lock comes back NOT_LOCKED');
});

test('a named device the bench no longer offers is not sent under somebody else\'s lock', async () => {
  const { manager, connection } = await routed([device({ index: 0, name: 'QA403' })]);
  connection.sent.length = 0;

  const answer = await manager.callLocked(NET_QA40X, MessageType.DEVICE_SET_CALIBRATION,
    { [NetFields.INDEX]: 7, [NetFields.INPUT]: true, [NetFields.NAME]: 'Gone',
      [NetFields.FS_RMS_LEFT]: 1.0, [NetFields.FS_RMS_RIGHT]: 1.0 });

  assert.equal(answer, null);
  assert.equal(connection.sent.length, 0, 'the any-device fallback would move a stranger\'s device');
});

// --- spec §3: the one connect failure with an explanation worth giving (R2.2-9) ---

test('an https-served page may not dial ws:// to a LAN host, and the failure SAYS so', async () => {
  const lan = makeServerEntry('uuid-9', 'Bench QA403', '10.0.0.1', 8377, true);
  const here = makeServerEntry('uuid-8', 'This machine', 'localhost', 8377, true);
  const manager = new NetDeviceManager({
    preferences: new NetPreferences(),
    // The browser blocks the dial WITHOUT detail - that is exactly why the client has to
    // recognise the case itself instead of showing the raw failure.
    openConnection: async () => { throw new Error('') },
  });
  const saved = globalThis.location;
  try {
    globalThis.location = { protocol: 'https:' };
    assert.equal(await manager.connect(lan), 'web.net.error.mixedContent');
    assert.equal(await manager.connect(here), 'net.error.connect',
      'a loopback host is exempt from the mixed-content rule, so its failure is the ORDINARY one - '
      + 'and that sentence is Java\'s own key, not a web-invented twin');

    assert.equal(isMixedContentBlock(lan), true);
    for (const host of ['localhost', '127.0.0.1', '::1']) {
      assert.equal(isMixedContentBlock({ host, port: 8377 }), false, `${host} is dialable`);
    }
    globalThis.location = { protocol: 'http:' };
    assert.equal(isMixedContentBlock(lan), false, 'an http page dials ws:// freely');
  } finally {
    if (saved === undefined) delete globalThis.location; else globalThis.location = saved;
  }
});
