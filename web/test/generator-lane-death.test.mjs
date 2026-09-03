/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * A generator lane that dies from BELOW, end to end on the CONSULT model - the web port of the
 * desktop's LaneDeathTest shape.
 *
 * The maintainer's bench report: "no error message in web app if server output channel stop
 * playback - no error message, generator don't stopped - LED play is on". Nothing above the lane
 * asks the bench anything once a tone is running (gen.start was answered when the lane accepted
 * it), so the bench's ev.device.error is the only word that ever arrives - and it used to be
 * logged and dropped.
 *
 * The chain this pins, one link per test:
 *   the sink records it            (net-playback-sink._onDeviceError)
 *   -> the controller claims it ONCE and stops the generator (takePlaybackEndedFromBelow)
 *   -> the pane clears the LED and raises the ONE report        (onPlaybackEndedFromBelow)
 * which is exactly the desktop's NetDeviceManager.deviceError -> genEndedBelow ->
 * GeneratorLane.takePlaybackEndedFromBelow -> GeneratorPane.scheduleOnAirBlink consult.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

// The pane touches jQuery and the DOM; only the two selectors below are ever asked about.
const nodes = new Map();
function fakeNode(sel) {
  return {
    sel, classes: new Set(), attrs: {},
    toggleClass(name, on) { if (on) this.classes.add(name); else this.classes.delete(name); return this; },
    removeClass(name) { this.classes.delete(name); return this; },
    addClass(name) { this.classes.add(name); return this; },
    attr(k, v) { if (v === undefined) return this.attrs[k]; this.attrs[k] = v; return this; },
    text() { return this; },
    prop() { return this; },
  };
}
globalThis.$ = (sel) => {
  if (!nodes.has(sel)) nodes.set(sel, fakeNode(sel));
  return nodes.get(sel);
};

const { MessageBus } = await import('../js/bus/message-bus.js');
const { Events } = await import('../js/bus/events.js');
const { MessageType, NetFields } = await import('../js/net/net-proto.js');
const { NetPlaybackSink } = await import('../js/net/net-playback-sink.js');
const { makeNetDeviceRef } = await import('../js/net/net-device-ref.js');
const { DeviceFailureReason } = await import('../js/enums/device-failure-reason.js');
const { GeneratorController } = await import('../js/generator/generator-controller.js');
const { GeneratorPane } = await import('../js/generator/generator-pane.js');

const DEVICE = makeNetDeviceRef({ index: 1, name: 'QA403', description: 'd', vendor: 'v',
  isInput: false, remoteBackend: 'QA40X' });

/** The minimum of a live session: it answers gen.open and hands events to its listeners. */
class FakeConnection {
  constructor() { this.events = []; this.closeListeners = []; this._id = 0; }
  newRequest(t) { return { t, id: ++this._id }; }
  async request(m) {
    return { t: MessageType.RESP, id: m.id, ok: true,
      data: { [NetFields.GEN_ID]: 3, [NetFields.RATE]: 48000 } };
  }
  send() { return Promise.resolve({ ok: true }); }
  addEventListener(l) { this.events.push(l); }
  removeEventListener(l) { this.events = this.events.filter((x) => x !== l); }
  addCloseListener(l) { this.closeListeners.push(l); }
  removeCloseListener(l) { this.closeListeners = this.closeListeners.filter((x) => x !== l); }
  deliver(e) { for (const l of [...this.events]) l(e); }
}

/** An OPEN remote lane with a tone running on it, as the bench reports it. */
async function playingLane() {
  const connection = new FakeConnection();
  const owner = { async acquireDevice() { return null; }, async releaseDevice() {},
    classifyFailure: () => DeviceFailureReason.UNKNOWN };
  const sink = new NetPlaybackSink({ connection, device: DEVICE, owner });
  await sink.open({ sampleRate: 48000, bits: 24, ditherBits: 0, outputChannels: 'BOTH' });
  connection.deliver({ t: MessageType.EV_GEN_STATE, [NetFields.GEN_ID]: 3,
    [NetFields.RUNNING]: true, [NetFields.EMIT_HZ]: 997, [NetFields.EMIT2_HZ]: 0 });
  return { sink, connection };
}

/** The bench's render loop dies under the tone (spec 4.3 ev.device.error, direction output). */
function killOutputLane(connection) {
  connection.deliver({ t: MessageType.EV_DEVICE_ERROR, [NetFields.DIRECTION]: 'output',
    [NetFields.DETAIL]: 'Speakers (USB DAC): PortAudio stream died',
    [NetFields.REASON]: DeviceFailureReason.DEVICE_DISCONNECTED.name });
}

/** A controller holding that sink, with the stop recorded rather than driven - the stop's own
 *  plumbing is another test's; what matters here is that it HAPPENS, once. */
function controllerOver(sink) {
  const controller = Object.create(GeneratorController.prototype);
  controller._sink = sink;
  controller._playbackEndClaimed = false;
  controller.stops = 0;
  controller.stopGenerator = async () => { controller.stops++; };
  return controller;
}

test('the bench killing the output lane reaches the controller, which stops the generator', async () => {
  const { sink, connection } = await playingLane();
  const controller = controllerOver(sink);
  assert.equal(await controller.takePlaybackEndedFromBelow(), null, 'a healthy lane claims nothing');
  assert.equal(controller.stops, 0);

  killOutputLane(connection);
  const died = await controller.takePlaybackEndedFromBelow();

  assert.notEqual(died, null, 'the tone the operator thinks is playing has stopped');
  assert.match(died.message, /output lane died/);
  assert.equal(controller.stops, 1, 'the generator state is reconciled, so a re-press starts clean');
});

test('the death is claimed ONCE - one lane, one operator report', async () => {
  const { sink, connection } = await playingLane();
  const controller = controllerOver(sink);
  killOutputLane(connection);

  assert.notEqual(await controller.takePlaybackEndedFromBelow(), null);
  assert.equal(await controller.takePlaybackEndedFromBelow(), null, 'the second tick says nothing');
  assert.equal(controller.stops, 1, 'and does not stop a generator that is already stopped');
});

test('the pane clears the play LED and raises exactly one report', async () => {
  const alerts = [];
  const sub = (p) => alerts.push(p);
  MessageBus.instance().subscribe(Events.AUDIO_DEVICE_ERROR, sub);
  const pane = Object.create(GeneratorPane.prototype);
  pane.engine = { activeBackend: () => 'net:QA40X' };
  pane.setGenFileBtn = () => {};
  pane._stopOnAirTick = () => {};
  $('#onAir').toggleClass('live', true);                 // the LED as the operator sees it
  $('#genPlay').addClass('playing');

  pane.onPlaybackEndedFromBelow();

  assert.equal($('#onAir').classes.has('live'), false, 'the LED is off - the lane is down');
  assert.equal($('#genPlay').classes.has('playing'), false, 'and Play is no longer lit');
  assert.equal(alerts.length, 1, 'ONE message: a failure reported twice is one nobody trusts');
  assert.equal(alerts[0].direction, 'output');
  assert.ok(alerts[0].message, 'with a sentence, not a raw driver string');
  MessageBus.instance().unsubscribe(Events.AUDIO_DEVICE_ERROR, sub);
});

/** The pane's ON-AIR tick is the poll point - the same seam the desktop uses (its blink tick).
 *  Driving it is what proves the three links are actually connected to each other. */
test('the ON-AIR tick is what carries the death from the wire to the LED', async () => {
  const alerts = [];
  const sub = (p) => alerts.push(p);
  MessageBus.instance().subscribe(Events.AUDIO_DEVICE_ERROR, sub);
  const { sink, connection } = await playingLane();
  const controller = controllerOver(sink);
  const pane = Object.create(GeneratorPane.prototype);
  pane.engine = controller;
  controller.activeBackend = () => 'net:QA40X';
  pane.setGenFileBtn = () => {};
  pane._stopOnAirTick = () => {};
  $('#onAir').toggleClass('live', true);

  await pane._onAirTick();
  assert.equal($('#onAir').classes.has('live'), true, 'nothing died yet - the tone plays on');
  assert.equal(alerts.length, 0);

  killOutputLane(connection);
  await pane._onAirTick();

  assert.equal($('#onAir').classes.has('live'), false, 'LED off');
  assert.equal(controller.stops, 1, 'generator stopped');
  assert.equal(alerts.length, 1, 'operator told, once');
  MessageBus.instance().unsubscribe(Events.AUDIO_DEVICE_ERROR, sub);
});
