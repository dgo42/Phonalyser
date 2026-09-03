/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * B14: "only ONE freqresp measurement per load - the second says 'unknown reason'".
 *
 * The measurement's lanes are driven TWICE in the exact order FreqRespController drives them,
 * against a bench that refuses what the real server refuses. That last part is the whole point:
 * the two refusals that can only appear on a SECOND run are server-side rules the node doubles
 * used so far never had -
 *
 *   CaptureStreamer.open  -> BAD_REQUEST "a capture is already open on <device>"
 *   GeneratorSession.open -> BAD_REQUEST "a generator is already open on this connection (id N)"
 *
 * - and both are code-only refusals, which is why they reached the operator as "reason unknown"
 * (the same rendering root as B8/B9/F2). A bench double that grants every open can never fail a
 * second run, so it would have reported this suite green forever.
 *
 * Real: NetConnection over a scripted socket, NetDeviceManager as the lock register, the real
 * NetCaptureSource / NetPlaybackSink, the real SharedCapture and GeneratorController. Doubled:
 * the socket and the bench's bookkeeping.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import { NetConnection } from '../js/net/net-connection.js';
import { NetDeviceManager } from '../js/net/net-device-manager.js';
import { NetCaptureSource } from '../js/net/net-capture-source.js';
import { NetPlaybackSink } from '../js/net/net-playback-sink.js';
import { NetPreferences, makeServerEntry } from '../js/net/net-preferences.js';
import { MessageType, NetFields, ErrorCode, NetProto } from '../js/net/net-proto.js';
import { SharedCapture } from '../js/audio/shared-capture.js';
import { GeneratorController } from '../js/generator/generator-controller.js';
import { testPrefs } from './_test-prefs.mjs';
import { twoPlaneFactory } from './fake-net-data-socket.mjs';

/** Time in the test's hands - nothing pings on its own. */
class FakeTicker {
  start(periodMs, task) { this.periodMs = periodMs; this.task = task; }
  stop() { this.task = null; }
}

const DEVICE_NAME = 'Cubilux USB Audio';

/**
 * A bench that keeps the books the real server keeps: one lock register, one capture stream per
 * device, ONE generator per connection. Every refusal below is copied from the server class that
 * raises it, with its code and its sentence.
 */
class StrictBench {
  constructor() {
    this.sent = [];
    /** lock key ("<backend>/<index>/<input>") -> true while this session holds it. */
    this.locks = new Set();
    /** captureId -> lock key. */
    this.captures = new Map();
    /** The captures whose data connection has attached (spec 4.7) - what makes capture.start
     *  answerable rather than NOT_ATTACHED. */
    this.attached = new Set();
    /** Every data socket this bench has handed out, in dial order. */
    this.dataSockets = [];
    /** The ONE generator of this connection (GeneratorSession.genId), or 0. */
    this.genId = 0;
    this.genLock = null;
    this._nextCapture = 1;
    this._nextGen = 100;
    /** Every refusal this bench sent, for the assertions. */
    this.refusals = [];
    this.onopen = null; this.onmessage = null; this.onclose = null; this.onerror = null;
  }

  /** Spec 4: the first dial is the control connection, every one after it a capture's data
   *  connection - which this bench binds by captureId exactly as the server does. */
  factory() {
    return twoPlaneFactory(this, (attach) => this._attach(attach), this.dataSockets);
  }

  accept() { if (this.onopen) this.onopen(); }

  /** Spec 4.7: the attach names a capture this session opened, or it is refused. */
  _attach(attach) {
    const id = attach[NetFields.CAPTURE_ID];
    if (!this.captures.has(id)) {
      return error(ErrorCode.BAD_REQUEST, `no capture is open with id ${id}`);
    }
    this.attached.add(id);
    return { data: {} };
  }

  send(text) {
    const m = JSON.parse(text);
    this.sent.push(m);
    const answer = this._answer(m);
    if (answer.error) this.refusals.push({ t: m.t, ...answer.error });
    this._deliver({ t: MessageType.RESP, id: m.id, ok: !answer.error, ...answer });
  }

  close() { }

  _answer(m) {
    switch (m.t) {
      case MessageType.HELLO:
        return { data: { [NetFields.PROTO]: NetProto.PROTO_VERSION,
          [NetFields.SERVER_ID]: 'server-uuid-1', [NetFields.NAME]: 'Bench',
          [NetFields.APP]: '1.1.1', [NetFields.CLIENT_ID]: 'client-uuid-1',
          [NetFields.CAPS]: ['qa40x', 'gen', 'files'] } };
      case MessageType.BACKEND_LIST:
        return { data: { [NetFields.BACKENDS]: [{ [NetFields.BACKEND]: 'JAVASOUND',
          [NetFields.DISPLAY_NAME]: 'Java Sound', [NetFields.AVAILABLE]: true,
          [NetFields.OPERATIONAL]: true, [NetFields.HAS_BIT_DEPTH]: true }] } };
      case MessageType.BACKEND_SELECT:
        return { data: { [NetFields.BACKEND]: 'JAVASOUND',
          [NetFields.DEVICES]: [this._device(true), this._device(false)] } };
      case MessageType.DEVICE_ACQUIRE:
        // LockRegistry.acquire: a REPEATED acquire by the same session is granted.
        this.locks.add(lockKey(m));
        return { data: {} };
      case MessageType.DEVICE_RELEASE:
        // Spec 4.3: "Also closes any open stream/generator on it".
        this._closeEverythingOn(lockKey(m));
        this.locks.delete(lockKey(m));
        return { data: {} };
      case MessageType.CAPTURE_OPEN:
        return this._captureOpen(m);
      case MessageType.CAPTURE_START:
      case MessageType.CAPTURE_STOP:
        return this._captureCommand(m);
      case MessageType.CAPTURE_CLOSE: {
        const bad = this._captureCommand(m);
        if (bad.error) return bad;
        this.captures.delete(m[NetFields.CAPTURE_ID]);
        this.attached.delete(m[NetFields.CAPTURE_ID]);
        return { data: {} };
      }
      case MessageType.GEN_OPEN:
        return this._genOpen(m);
      case MessageType.GEN_CLOSE: {
        const bad = this._genCommand(m);
        if (bad.error) return bad;
        this.genId = 0; this.genLock = null;
        return { data: {} };
      }
      default:
        // gen.config / gen.start / gen.stop / gen.fftGrid - all id-checked the same way.
        if (String(m.t).startsWith('gen.')) return this._genCommand(m);
        return { data: {} };
    }
  }

  /** ClientSession.captureOpen + CaptureStreamer.open, refusal for refusal. */
  _captureOpen(m) {
    const key = lockKey(m);
    if (!this.locks.has(key)) {
      return error(ErrorCode.NOT_LOCKED, `${key} must be acquired before it can stream`);
    }
    for (const held of this.captures.values()) {
      if (held === key) return error(ErrorCode.BAD_REQUEST, `a capture is already open on ${key}`);
    }
    const id = this._nextCapture++;
    this.captures.set(id, key);
    return { data: { [NetFields.CAPTURE_ID]: id, [NetFields.RATE]: 48000,
      [NetFields.BITS]: 24, [NetFields.CHANNELS]: 2 } };
  }

  _captureCommand(m) {
    const id = m[NetFields.CAPTURE_ID];
    if (!this.captures.has(id)) {
      return error(ErrorCode.BAD_REQUEST, `no capture is open with id ${id}`);
    }
    if (m.t === MessageType.CAPTURE_START && !this.attached.has(id)) {
      return error(ErrorCode.NOT_ATTACHED,
        `capture ${id} has no data connection yet - attach one before starting it`);
    }
    return { data: {} };
  }

  /** ClientSession.genOpen + GeneratorSession.open - including the ONE-per-connection rule. */
  _genOpen(m) {
    const key = lockKey(m);
    if (!this.locks.has(key)) {
      return error(ErrorCode.NOT_LOCKED, `${key} must be acquired before the generator can drive it`);
    }
    if (this.genId !== 0) {
      return error(ErrorCode.BAD_REQUEST,
        `a generator is already open on this connection (id ${this.genId})`);
    }
    this.genId = this._nextGen++;
    this.genLock = key;
    return { data: { [NetFields.GEN_ID]: this.genId, [NetFields.RATE]: 48000 } };
  }

  _genCommand(m) {
    if (this.genId === 0 || m[NetFields.GEN_ID] !== this.genId) {
      return error(ErrorCode.BAD_REQUEST, `no generator is open with id ${m[NetFields.GEN_ID]}`);
    }
    return { data: {} };
  }

  _closeEverythingOn(key) {
    for (const [id, held] of [...this.captures]) {
      if (held === key) { this.captures.delete(id); this.attached.delete(id); }
    }
    if (this.genLock === key) { this.genId = 0; this.genLock = null; }
  }

  _device(input) {
    return { [NetFields.INDEX]: 0, [NetFields.NAME]: DEVICE_NAME,
      [NetFields.DESCRIPTION]: 'desc', [NetFields.VENDOR]: 'Cubilux',
      [NetFields.INPUT]: input, [NetFields.OUTPUT]: !input,
      [NetFields.HAS_BIT_DEPTH]: true, [NetFields.LOCK]: null,
      [NetFields.FORMATS]: [{ [NetFields.RATE]: 48000, [NetFields.BITS]: 24,
        [NetFields.CHANNELS]: 2 }] };
  }

  _deliver(message) { if (this.onmessage) this.onmessage({ data: JSON.stringify(message) }); }
}

function lockKey(m) {
  return `${m[NetFields.BACKEND]}/${m[NetFields.INDEX]}/${m[NetFields.INPUT] ? 'in' : 'out'}`;
}

function error(code, message) { return { error: { code, message } }; }

/** A connected manager routed at the bench's one backend. */
async function bench() {
  const socket = new StrictBench();
  const connection = new NetConnection({
    url: 'ws://bench.local:8377/', clientName: 'Web client', clientApp: 'Phonalyser.web/1.1.1',
    ticker: new FakeTicker(), socketFactory: socket.factory(),
  });
  const manager = new NetDeviceManager({
    preferences: new NetPreferences(),
    openConnection: async () => {
      const opening = connection.open(5000); socket.accept(); await opening; return connection;
    },
  });
  await manager.connect(makeServerEntry('server-uuid-1', 'Bench', '10.0.0.1', 8377, true));
  await manager.selectBackend('JAVASOUND');
  return { socket, manager };
}

const SWEEP = { f0: 20, f1: 20000, sweepSamples: 4800, leadInSamples: 480, fadeSamples: 48,
  amplitudeVRms: 0.5 };

/**
 * ONE freqresp measurement, in FreqRespController's own order: open the capture first (the sweep
 * is authored at the rate the ADC granted), then the output lane, play, and tear both down in the
 * finally's order - stopFile, then the capture's release.
 */
async function measurement(capture, gen) {
  const reader = await capture.acquire();
  try {
    const granted = await gen.openSweepContext(48000, { outputChannels: 'BOTH' });
    await gen.playSweepBuffer(undefined, 48000, { outputChannels: 'BOTH', sweep: SWEEP });
    return { reader, granted };
  } finally {
    await gen.stopFile();
    await capture.release();
  }
}

/** The two lanes a measurement drives, wired exactly as AudioEngine wires them. */
function lanes(manager, prefs) {
  // ONE capture source for every run, exactly as the AudioBackend's dispatch wrapper reuses the
  // source of the active backend across acquires - so anything one run leaves on it is still
  // there for the next.
  const source = new NetCaptureSource({ connection: manager.connection,
    device: manager.listInputDevices()[0], owner: manager });
  return {
    capture: new SharedCapture({ prefs, captureSource: source }),
    gen: new GeneratorController(prefs, {
      openPlayback: () => new NetPlaybackSink({ connection: manager.connection,
        device: manager.listOutputDevices()[0], owner: manager }),
    }),
  };
}

function measurementConfig() {
  return testPrefs({ inputDeviceName: DEVICE_NAME, outputDeviceName: DEVICE_NAME,
    inputSampleRate: 48000, outputSampleRate: 48000, fftLength: 65536, genSignalForm: 'SINE',
    genFrequencyHz: 1000, genAmplitudeVrms: 0.5, dacFsVoltageAmpl: 1.0 });
}

test('B14: a SECOND measurement opens exactly like the first - nothing survives the teardown',
  async () => {
    const { socket, manager } = await bench();
    const config = measurementConfig();
    const { capture, gen } = lanes(manager, config);

    const first = await measurement(capture, gen);
    assert.notEqual(first.reader, null, 'the first run is the one that worked on his bench');
    assert.equal(socket.refusals.length, 0, 'and it is refused nothing');

    const second = await measurement(capture, gen);

    assert.deepEqual(socket.refusals, [], 'the bench refuses the second run nothing - a stream or '
      + 'a generator left open by the first is exactly what BAD_REQUEST answers');
    assert.notEqual(second.reader, null, 'the second measurement records too');
    assert.equal(capture.getLastStartError(), '', 'no failure to report');
    assert.equal(socket.captures.size, 0, 'no capture stream is left open on the bench');
    assert.equal(socket.genId, 0, 'no generator is left open on the connection');
    assert.equal(socket.locks.size, 0, 'and both device locks are back');
  });

test('B14 root: ONE refused gen.start must cost ONE measurement, not every later one', async () => {
  // The shape of "only ONE measurement per page load": the lane was handed to the bench command
  // BEFORE anything owned it, so a refusal there orphaned an OPEN generator - and a server keeps
  // one generator per connection, so every later gen.open was refused for the life of the
  // session. The refusal itself is legitimate (a bench may say no to a sweep it cannot render);
  // what must not survive it is the lane.
  const { socket, manager } = await bench();
  const config = measurementConfig();
  const { capture, gen } = lanes(manager, config);
  const realAnswer = socket._answer.bind(socket);
  let refuseOnce = true;
  socket._answer = (m) => {
    if (m.t === MessageType.GEN_START && refuseOnce) {
      refuseOnce = false;
      return error(ErrorCode.INTERNAL, 'the sweep renderer failed');
    }
    return realAnswer(m);
  };

  await assert.rejects(() => measurement(capture, gen), /gen\.start failed/);

  assert.equal(socket.genId, 0, 'the refused run gave its generator back');
  assert.equal(socket.locks.size, 0, 'and the output lock with it');

  const second = await measurement(capture, gen);

  assert.notEqual(second.reader, null, 'so the NEXT measurement runs');
  assert.equal(socket.refusals.length, 1, 'the only refusal is the scripted one');
});

test('B14: a bench lane still OPEN is not an idle DAC - the takeover wait can see it', async () => {
  // deviceIdle / the GENERATOR_RUNNING responder are what a measurement waits on before it opens
  // the output device. They read outputContextOpen, which knew only about the LOCAL contexts: a
  // bench's lane sink was invisible, so the wait returned "free" over a DAC whose lock this
  // session still held and whose generator the server still had open.
  const { manager } = await bench();
  const config = measurementConfig();
  const { gen } = lanes(manager, config);

  assert.equal(gen.outputContextOpen, false, 'nothing open yet');
  await gen.openSweepContext(48000, { outputChannels: 'BOTH' });
  assert.equal(gen.outputContextOpen, true,
    'a sweep lane opened on the bench IS an output device this session holds');

  await gen.playSweepBuffer(undefined, 48000, { outputChannels: 'BOTH', sweep: SWEEP });
  assert.equal(gen.outputContextOpen, true, 'and it stays held while the sweep plays');

  await gen.stopFile();
  assert.equal(gen.outputContextOpen, false, 'only the teardown frees it');
});
