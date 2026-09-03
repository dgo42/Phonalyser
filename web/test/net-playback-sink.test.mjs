/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The generator that runs on the bench (spec 4.5), on the PlaybackSink seam: the lock, the
 * GRANTED rate, the drive ORDER GeneratorLane.startRemote fixes, the partial gen.config
 * updates the live controls become, and the one rule every hint downstream depends on -
 * emitHz/emit2Hz come from the bench, and 0.0 means "no such tone".
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import { NetPlaybackSink, FileTooLargeError } from '../js/net/net-playback-sink.js';
import { NetRefusal } from '../js/net/net-connection.js';
import { NetCloseReason } from '../js/net/net-close-reason.js';
import { MessageType, NetFields, ErrorCode, NetProto } from '../js/net/net-proto.js';
import { makeNetDeviceRef } from '../js/net/net-device-ref.js';
import { DeviceFailureReason } from '../js/enums/device-failure-reason.js';
import { GenSignalForm } from '../js/enums/gen-signal-form.js';
import { GeneratorController } from '../js/generator/generator-controller.js';
import { testPrefs } from './_test-prefs.mjs';
import { MessageBus } from '../js/bus/message-bus.js';
import { Events } from '../js/bus/events.js';

/** Counts FILE_PLAY_STOPPED - the one event the pane's play-from LED follows - until stop(). */
function filePlayStops() {
  const seen = { n: 0 };
  const sub = () => { seen.n++; };
  MessageBus.instance().subscribe(Events.FILE_PLAY_STOPPED, sub);
  seen.stop = () => MessageBus.instance().unsubscribe(Events.FILE_PLAY_STOPPED, sub);
  return seen;
}

const DEVICE = makeNetDeviceRef({ index: 1, name: 'QA403', description: 'd', vendor: 'v',
  isInput: false, remoteBackend: 'QA40X' });

class FakeConnection {
  constructor() { this.sent = []; this.events = []; this.closeListeners = []; this._id = 0; this.answers = {}; }
  newRequest(t) { return { t, id: ++this._id }; }
  async request(m) {
    this.sent.push(m);
    const s = this.answers[m.t];
    return (typeof s === 'function' ? s(m) : s) || { t: MessageType.RESP, id: m.id, ok: true, data: {} };
  }
  send(m) { this.sent.push(m); return Promise.resolve({ ok: true }); }
  refusal(what, answer) {
    const e = answer.error || {};
    return new NetRefusal(`${what}: ${e.code} - ${e.message}`,
      e[NetFields.REASON] ? DeviceFailureReason[e[NetFields.REASON]] : DeviceFailureReason.UNKNOWN);
  }
  addEventListener(l) { this.events.push(l); }
  removeEventListener(l) { this.events = this.events.filter((x) => x !== l); }
  addCloseListener(l) { this.closeListeners.push(l); }
  removeCloseListener(l) { this.closeListeners = this.closeListeners.filter((x) => x !== l); }
  deliver(e) { for (const l of [...this.events]) l(e); }
  endSession(r) { for (const l of [...this.closeListeners]) l(r); }
  ofType(t) { return this.sent.filter((m) => m.t === t); }
  configs() { return this.ofType(MessageType.GEN_CONFIG); }
}

class FakeOwner {
  constructor() { this.acquired = 0; this.released = 0; this.refuseAcquire = null; }
  async acquireDevice() { this.acquired++; return this.refuseAcquire; }
  async releaseDevice() { this.released++; }
  classifyFailure(e) { return (e instanceof NetRefusal) ? e.reason : DeviceFailureReason.UNKNOWN; }
}

async function opened({ grantedRate = 48000, uploadStatus = 200,
  uploadBody = { [NetFields.FILE_ID]: 'f-1' } } = {}) {
  const connection = new FakeConnection();
  connection.answers[MessageType.GEN_OPEN] = (m) => ({ t: MessageType.RESP, id: m.id, ok: true,
    data: { [NetFields.GEN_ID]: 3, [NetFields.RATE]: grantedRate } });
  // Spec §3's upload side of the same port, counted rather than sent - the size gate's whole
  // point is that an over-size file never reaches here (Java's MockBench counts the same way).
  connection.httpBase = () => 'http://bench.local:8377';
  const uploads = [];
  const fetchImpl = async (url, init) => {
    uploads.push({ url, method: init.method, body: init.body });
    return { ok: uploadStatus >= 200 && uploadStatus < 300, status: uploadStatus,
      json: async () => uploadBody };
  };
  const owner = new FakeOwner();
  const sink = new NetPlaybackSink({ connection, device: DEVICE, owner, fetchImpl });
  const granted = await sink.open({ sampleRate: 48000, bits: 24, ditherBits: 0, outputChannels: 'BOTH' });
  return { sink, connection, owner, granted, uploads };
}

test('open takes the DAC and answers the rate the BENCH granted', async () => {
  const { sink, connection, owner, granted } = await opened({ grantedRate: 44100 });

  assert.equal(owner.acquired, 1, 'spec 4.5: all gen.* require the output-device lock');
  const open = connection.ofType(MessageType.GEN_OPEN)[0];
  assert.equal(open[NetFields.NAME], 'QA403');
  assert.equal(open[NetFields.OUTPUT_CHANNELS], 'BOTH');
  assert.equal(granted, 44100, 'the bench\'s DAC has the last word');
  assert.equal(sink.sampleRate, 44100, 'and every snap and sample count is computed against it');
});

test('an open that fails costs nothing - the lock goes back before the throw', async () => {
  const connection = new FakeConnection();
  connection.answers[MessageType.GEN_OPEN] = (m) => ({ t: MessageType.RESP, id: m.id, ok: false,
    error: { code: ErrorCode.DEVICE_LOCKED, message: 'in use', by: 'Lab PC' } });
  const owner = new FakeOwner();
  const sink = new NetPlaybackSink({ connection, device: DEVICE, owner });

  await assert.rejects(() => sink.open({ sampleRate: 48000, ditherBits: 0, outputChannels: 'BOTH' }),
    /DEVICE_LOCKED/);
  assert.equal(owner.released, 1, 'a lock left behind is a DAC no other client can ever take');
});

test('start drives the bench in the desktop\'s order, and the FFT grid goes LAST', async () => {
  const { sink, connection } = await opened();
  connection.sent.length = 0;

  await sink.start({
    form: GenSignalForm.SINE, frequency: 997, amplitudeVRms: 1.0, dacFsVoltageAmpl: 2.0,
    rightLaneScale: 1.05, ditherBits: 0, outputChannels: 'BOTH',
    control: { rectDuty: 0.5, triDuty: 0.5, fftSize: 32768, snapEnabled: true },
  });

  const order = connection.sent.map((m) => m.t);
  assert.equal(order[0], MessageType.GEN_CONFIG, 'the waveform first');
  assert.equal(connection.configs()[0][NetFields.FORM], GenSignalForm.SINE);
  assert.equal(order[order.length - 2], MessageType.GEN_FFT_GRID,
    'the grid is pushed AFTER the form - the bench snaps against what it currently holds');
  assert.equal(order[order.length - 1], MessageType.GEN_START, 'and only then does it emit');
  const amp = connection.configs().find((m) => m[NetFields.AMPLITUDE_VRMS] != null);
  assert.equal(amp[NetFields.AMPLITUDE_VRMS], 1.0, 'the amplitude is absolute volts, unchanged');
  // The DAC's full scale is the BENCH's, and only the bench's (maintainer ruling: the FS
  // calibration is applied on the server). A client that pushed its own over the bench's card
  // made the same entered amplitude come out at a different level on every client.
  assert.equal(amp[NetFields.DAC_FS_VOLTAGE_AMPL], undefined,
    'the client does not send a full scale onto another machine\'s DAC');
  assert.equal(amp[NetFields.RIGHT_LANE_SCALE], undefined);
});

test('a sweep carries its band in the sweep block and no tone frequency', async () => {
  const { sink, connection } = await opened();
  connection.sent.length = 0;

  await sink.start({
    form: GenSignalForm.LOG_SWEEP, frequency: 997, amplitudeVRms: 1.0, dacFsVoltageAmpl: 2.0,
    control: { logSweep: { f0: 20, f1: 20000, sweepSamples: 480000, leadInSamples: 2400 },
      sweepParams: { loop: true, fadeInSamples: 128, fadeOutSamples: 256 } },
  });

  assert.equal(connection.configs().some((m) => m[NetFields.FREQUENCY] != null), false,
    'a sweep has no tone frequency - its band travels in the sweep block');
  const sweep = connection.configs().find((m) => m[NetFields.SWEEP])[NetFields.SWEEP];
  assert.equal(sweep[NetFields.F0], 20);
  assert.equal(sweep[NetFields.F1], 20000);
  assert.equal(sweep[NetFields.DURATION_SAMPLES], 480000);
  assert.equal(sweep[NetFields.LEAD_IN_SAMPLES], 2400);
  assert.equal(sweep[NetFields.LOOP], true);
  assert.equal(sweep[NetFields.FADE_OUT_SAMPLES], 256);
});

test('a sweep engine leaves the BENCH\'s device card in force (pushCalibration)', async () => {
  const { sink, connection } = await opened();
  connection.sent.length = 0;

  await sink.start({
    form: GenSignalForm.LOG_SWEEP, amplitudeVRms: 0.5, dacFsVoltageAmpl: 2.79,
    rightLaneScale: 1.05, pushCalibration: false, outputChannels: 'BOTH',
    control: { logSweep: { f0: 20, f1: 20000, sweepSamples: 480000, leadInSamples: 2400 },
      sweepParams: { loop: false, fadeInSamples: 12000, fadeOutSamples: 12000 } },
  });

  const amp = connection.configs().find((m) => m[NetFields.AMPLITUDE_VRMS] != null);
  assert.equal(amp[NetFields.AMPLITUDE_VRMS], 0.5, 'the commanded level still crosses');
  assert.equal(amp[NetFields.DAC_FS_VOLTAGE_AMPL], undefined,
    'the DAC full scale belongs where the DAC is - gen.open took it from the bench\'s own card');
  assert.equal(amp[NetFields.RIGHT_LANE_SCALE], undefined);
});

test('a live control message becomes a PARTIAL update - only what changed', async () => {
  const { sink, connection } = await opened();
  await sink.start({ form: GenSignalForm.SINE, frequency: 997, amplitudeVRms: 1, dacFsVoltageAmpl: 2, control: {} });
  connection.sent.length = 0;

  sink.port.postMessage({ frequency: 1000.5 });

  assert.equal(connection.configs().length, 1);
  const update = connection.configs()[0];
  assert.equal(update[NetFields.FREQUENCY], 1000.5);
  assert.equal(update[NetFields.SWEEP], undefined,
    're-sending a sweep would re-render the chirp and restart it under whoever was recording it');
  assert.equal(update[NetFields.GEN_ID], 3, 'every gen.* carries the handle');
});

test('the FLL trims are the actuator: absolute, fire-and-forget, servo stays here', async () => {
  const { sink, connection } = await opened();
  await sink.start({ form: GenSignalForm.SINE, frequency: 997, amplitudeVRms: 1, dacFsVoltageAmpl: 2, control: {} });
  connection.sent.length = 0;

  sink.port.postMessage({ trimHz: 997.013 });
  sink.port.postMessage({ trim2Hz: 1996.7 });
  sink.port.postMessage({ trimReset: true });

  assert.equal(connection.ofType(MessageType.GEN_TRIM)[0][NetFields.HZ], 997.013);
  assert.equal(connection.ofType(MessageType.GEN_TRIM2)[0][NetFields.HZ], 1996.7);
  assert.equal(connection.ofType(MessageType.GEN_TRIM_RESET).length, 1);
});

test('emitHz comes from the BENCH - and 0.0 is "no such tone", never a frequency', async () => {
  const { sink, connection } = await opened();

  assert.deepEqual(sink.emittedHz(), [0, 0], 'nothing has been said yet');

  connection.deliver({ t: MessageType.EV_GEN_STATE, [NetFields.GEN_ID]: 3,
    [NetFields.RUNNING]: true, [NetFields.EMIT_HZ]: 997.0134, [NetFields.EMIT2_HZ]: 0 });
  assert.deepEqual(sink.emittedHz(), [997.0134, 0], 'post-snap, post-trim - what is REALLY emitted');
  assert.equal(sink.running, true);

  // A state for another generator is not this lane's: taking it would report a tone nobody
  // here asked for.
  connection.deliver({ t: MessageType.EV_GEN_STATE, [NetFields.GEN_ID]: 9,
    [NetFields.RUNNING]: false, [NetFields.EMIT_HZ]: 1, [NetFields.EMIT2_HZ]: 2 });
  assert.deepEqual(sink.emittedHz(), [997.0134, 0]);

  // A sweep reports emitHz 0 - the hint plumbing must draw nothing, not a marker at DC.
  connection.deliver({ t: MessageType.EV_GEN_STATE, [NetFields.GEN_ID]: 3,
    [NetFields.RUNNING]: true, [NetFields.EMIT_HZ]: 0, [NetFields.EMIT2_HZ]: 0 });
  assert.deepEqual(sink.emittedHz(), [0, 0]);
});

test('a session that dies while the tone is up is confessed bottom-up, once', async () => {
  const { sink, connection } = await opened();
  assert.equal(sink.lostFromBelow(), null, 'a healthy lane confesses nothing');

  connection.endSession(NetCloseReason.KEEPALIVE_TIMEOUT);

  assert.match(sink.lostFromBelow().message, /ended while the tone was playing/);
  assert.equal(sink.running, false);
  assert.deepEqual(sink.emittedHz(), [0, 0], 'and it stops claiming to emit anything');
});

/**
 * The maintainer's bug: "no error message in web app if server output channel stop playback -
 * no error message, generator don't stopped - LED play is on".
 *
 * The bench's render loop dies mid-tone and pushes ev.device.error{output} (spec 4.3). After
 * gen.start returned there is no request left for that failure to come back out of, so this
 * event is the ONLY way the client can learn the tone it thinks is playing has stopped. The
 * sink records it in the same claim-once cell a dead session uses, which the pane's ON-AIR
 * tick already consults (Java NetDeviceManager.deviceError -> genEndedBelow ->
 * GeneratorPane.scheduleOnAirBlink).
 */
test('an OUTPUT ev.device.error while the tone is up is confessed bottom-up, once', async () => {
  const { sink, connection } = await opened();
  assert.equal(sink.lostFromBelow(), null, 'a healthy lane confesses nothing');

  connection.deliver({ t: MessageType.EV_DEVICE_ERROR, [NetFields.DIRECTION]: 'output',
    [NetFields.DETAIL]: 'Speakers (USB DAC): the render loop died',
    [NetFields.REASON]: 'DEVICE_DISCONNECTED' });

  assert.notEqual(sink.lostFromBelow(), null, 'the pane\'s consult has something to claim');
  assert.match(sink.lostFromBelow().message, /render loop died/, 'the bench\'s own detail');
  assert.equal(sink.running, false, 'and the lane stops claiming to be playing');
  assert.deepEqual(sink.emittedHz(), [0, 0]);
});

test('an INPUT ev.device.error is not the generator\'s to report', async () => {
  const { sink, connection } = await opened();
  connection.deliver({ t: MessageType.EV_GEN_STATE, [NetFields.GEN_ID]: 3,
    [NetFields.RUNNING]: true, [NetFields.EMIT_HZ]: 997, [NetFields.EMIT2_HZ]: 0 });

  connection.deliver({ t: MessageType.EV_DEVICE_ERROR, [NetFields.DIRECTION]: 'input',
    [NetFields.DETAIL]: 'the capture device went away', [NetFields.REASON]: 'DEVICE_DISCONNECTED' });

  assert.equal(sink.lostFromBelow(), null,
    'the capture stream finishes its ring and its pane reports - one failure, one surface');
  assert.equal(sink.running, true, 'and the tone is still playing, because it is');
});

/** A FILE on that lane is the file player's to report - one lane dying is ONE thing that
 *  happened and must raise ONE dialog, not the tone's and the file's both (Java :427-434). */
test('an OUTPUT error with a FILE playing goes to the file\'s claim, not the tone\'s', async () => {
  const { sink, connection } = await opened();
  connection.deliver({ t: MessageType.EV_GEN_STATE, [NetFields.GEN_ID]: 3,
    [NetFields.RUNNING]: true, [NetFields.FILE]: { [NetFields.PLAYING]: true } });

  connection.deliver({ t: MessageType.EV_DEVICE_ERROR, [NetFields.DIRECTION]: 'output',
    [NetFields.DETAIL]: 'the render loop died', [NetFields.REASON]: 'DEVICE_DISCONNECTED' });

  assert.equal(sink.lostFromBelow(), null, 'the tone does not claim it');
  assert.notEqual(sink.takeFileErrorForReport(), null, 'the file player does');
  assert.equal(sink.fileState().finished, true, 'and its watcher leaves even if nobody claims');
});

test('close gives the lane and the DAC back, and unsubscribes', async () => {
  const { sink, connection, owner } = await opened();

  await sink.close();

  assert.equal(connection.ofType(MessageType.GEN_CLOSE).length, 1);
  assert.equal(owner.released, 1);
  assert.equal(connection.events.length, 0);
  assert.equal(connection.closeListeners.length, 0);
  assert.equal(sink.sampleRate, 0);
});

test('a non-compensated form clears the bench\'s tables; a compensated one sends radians', async () => {
  const { sink, connection } = await opened();
  connection.sent.length = 0;
  await sink.start({ form: GenSignalForm.SINE, frequency: 997, amplitudeVRms: 1, dacFsVoltageAmpl: 2, control: {} });
  assert.equal(connection.configs().some((m) => m[NetFields.CLEAR_COMPENSATION] === true), true,
    'a plain sine on a bench that still held a table would be predistorted for nothing');

  connection.sent.length = 0;
  // One harmonic row of an fft_harmonics CSV: h;freq;amp_pct;phase_deg;...;re;im
  const dpd = 'harmonic;freq;amplitude_pct;phase_deg;x;re;im\n'
    + '1;997;100;0;0;1;0\n2;1994;1,5;0;0;0,015;0\n';
  await sink.start({ form: GenSignalForm.SINE_COMP, frequency: 997, amplitudeVRms: 1,
    dacFsVoltageAmpl: 2, control: { dpdText: dpd, dpdFrequency: 997 } });

  const comp = connection.configs().find((m) => m[NetFields.COMPENSATION]);
  assert.ok(comp, 'the parsed table is pushed, not the file');
  assert.deepEqual([...comp[NetFields.COMPENSATION][NetFields.H_NUMS]], [2]);
  const phi = comp[NetFields.COMPENSATION][NetFields.PHI_INITS];
  assert.ok(phi.every((v) => Math.abs(v) <= Math.PI * 2 + 1e-9),
    'the wire wants RADIANS; the web parser answers turns, so the boundary converts');
});

// --- playing a FILE on the bench (spec §3 upload + 4.5 command), mirroring NetFilePlaybackTest ---

test('a file over the protocol limit is refused client-side, with NOTHING uploaded', async () => {
  const { sink, connection, uploads } = await opened();
  const tooBig = new Uint8Array(NetProto.MAX_UPLOAD_BYTES + 1);

  const refused = await sink.playFile(tooBig, false).then(() => null, (e) => e);

  assert.equal(refused.name, 'FileTooLargeError');
  assert.equal(refused.bytes, tooBig.length, 'the refusal carries the file\'s size');
  assert.equal(refused.limitBytes, NetProto.MAX_UPLOAD_BYTES, 'and the protocol\'s own limit');
  // The load-bearing one: the gate fired BEFORE a socket was opened, not after 50 MB had crossed
  // the wire and been turned away.
  assert.equal(uploads.length, 0, 'no socket was opened');
  assert.equal(connection.ofType(MessageType.GEN_PLAY_FILE).length, 0, 'and nothing was commanded');
});

test('the bytes go up over PUT /files, then gen.playFile names that file and the loop flag',
  async () => {
    const { sink, connection, uploads } = await opened();
    const content = new Uint8Array([1, 2, 3, 4]);

    await sink.playFile(content, true);

    assert.equal(uploads.length, 1);
    assert.equal(uploads[0].method, 'PUT');
    assert.equal(uploads[0].url, 'http://bench.local:8377/files', 'the session\'s own host and port');
    assert.deepEqual([...uploads[0].body], [1, 2, 3, 4], 'the bench holds the bytes unchanged');
    const played = connection.ofType(MessageType.GEN_PLAY_FILE)[0];
    assert.equal(played[NetFields.FILE_ID], 'f-1', 'the uploaded file\'s id');
    assert.equal(played[NetFields.LOOP], true);
    assert.equal(sink.fileState().playing, false, 'and NOT a state - the bench\'s push turns it on');
  });

test('a full store is not an over-size file, and nothing is commanded after it', async () => {
  const { sink, connection } = await opened({ uploadStatus: 413 });

  const refused = await sink.playFile(new Uint8Array(8), false).then(() => null, (e) => e);

  assert.notEqual(refused.name, 'FileTooLargeError', 'a full store is the BENCH\'s problem, not the file\'s');
  assert.match(refused.message, /store is full/);
  assert.equal(connection.ofType(MessageType.GEN_PLAY_FILE).length, 0);
});

test('the file block of ev.gen.state is the only thing that moves the file state', async () => {
  const { sink, connection } = await opened();
  const state = (file) => connection.deliver(Object.assign(
    { t: MessageType.EV_GEN_STATE, [NetFields.GEN_ID]: 3, [NetFields.RUNNING]: false },
    file === undefined ? {} : { [NetFields.FILE]: file }));

  state({ [NetFields.PLAYING]: true, [NetFields.FINISHED]: false });
  assert.deepEqual(sink.fileState(), { playing: true, finished: false });
  state(undefined);   // a bench that never played one sends no block at all
  assert.deepEqual(sink.fileState(), { playing: true, finished: false },
    'an ABSENT block reads as idle, never as a change');
  state({ [NetFields.PLAYING]: false, [NetFields.FINISHED]: true });
  assert.deepEqual(sink.fileState(), { playing: false, finished: true });
});

// --- the CONTROLLER's remote file session (Java startRemoteFilePlayback + remoteFilePlayLoop) ---

/** A bench lane whose playFile the test resolves by hand, so a stop can land mid-flight. */
function laneWithHeldPlay() {
  let release;
  const held = new Promise((resolve) => { release = resolve; });
  const calls = [];
  return {
    release,
    calls,
    file: { playing: false, finished: false },
    emittedHz: () => [0, 0],
    async playFile(content, loop) { calls.push(['playFile', loop]); await held; },
    async stopFile() { calls.push(['stopFile']); },
    async close() { calls.push(['close']); },
    fileState() { return this.file; },
    takeFileErrorForReport() { return null; },
  };
}

/** Waits for the DETACHED watch half to finish (F21: the play promise resolves at the command,
 *  so the session's end is no longer something a caller can await). */
async function settled(ctrl) {
  for (let i = 0; i < 60 && ctrl.filePlaying; i++) await new Promise((r) => setTimeout(r, 10));
}

/** The controller's file session on a minimal `this` - no engine, no browser globals. */
function fileController() {
  return Object.assign(Object.create(GeneratorController.prototype), {
    filePlayError: null, _remoteFile: null, _remoteFilePlaying: false,
    _fileLaneSink: null, _fileSrc: null, _sink: null,
  });
}

test('a stop in flight reaches the bench ONLY after the play command returned', async () => {
  const lane = laneWithHeldPlay();
  const ctrl = fileController();

  const playing = ctrl._startRemoteFilePlayback(lane, { bytes: new Uint8Array(4), name: 'a.wav' }, false);
  await ctrl.stopFile();          // the operator stops while the bytes are still moving
  lane.release();                 // ...and only now does the bench have the file
  await playing;
  await settled(ctrl);

  assert.deepEqual(lane.calls.map((c) => c[0]), ['playFile', 'stopFile'],
    'a stop that overtook the upload would leave the bench playing a file this client forgot');
  assert.equal(ctrl.filePlaying, false);
});

test('a natural end clears the play state and publishes FILE_PLAY_STOPPED, sending no stop', async () => {
  const lane = laneWithHeldPlay();
  const ctrl = fileController();
  const ended = filePlayStops();
  ctrl._fileLaneSink = lane;        // playFileBuffer opened this lane FOR the file
  lane.file = { playing: true, finished: false };

  const playing = ctrl._startRemoteFilePlayback(lane, { bytes: new Uint8Array(4), name: 'a.wav' }, false);
  lane.release();
  await playing;
  lane.file = { playing: false, finished: true };   // the bench says it ran out
  await settled(ctrl);

  ended.stop();
  assert.deepEqual(lane.calls.map((c) => c[0]), ['playFile', 'close'],
    'nothing to stop - it ended by itself; the lane opened for the file is given back');
  assert.equal(ended.n, 1, 'the pane\'s LED follows the same event the local path publishes');
  assert.equal(ctrl.filePlaying, false);
});

test('the loop flag is carried, and an over-size file is a localized refusal, not a throw',
  async () => {
    const lane = laneWithHeldPlay();
    lane.release();
    const ctrl = fileController();
    const looping = ctrl._startRemoteFilePlayback(lane, { bytes: new Uint8Array(4), name: 'a.wav' }, true);
    lane.file = { playing: false, finished: true };
    await looping;
    assert.deepEqual(lane.calls[0], ['playFile', true], 'loop travels to the bench');

    const refusing = fileController();
    const tooBig = {
      emittedHz: () => [0, 0],
      async playFile() { throw new FileTooLargeError(60 * 1024 * 1024, 50 * 1024 * 1024); },
      async stopFile() {}, fileState() { return { playing: false, finished: false }; },
      takeFileErrorForReport() { return null; },
    };
    await refusing._startRemoteFilePlayback(tooBig, { bytes: new Uint8Array(1), name: 'big.wav' }, false);

    assert.equal(refusing.filePlayError, 'generator.error.playFile.tooLargeForBench');
    assert.equal(refusing.filePlaying, false, 'and nothing is left showing as playing');
  });

test('the STORED dither reaches the bench at the START, not only when it is changed', async () => {
  // The bench fixes the depth at gen.open and the live gen.config half only moves it afterwards
  // (spec 4.5). Opened without it, a reloaded session ran undithered until the operator touched
  // the control - the value was on the wire for a CHANGE and never for a start.
  const opened = [];
  const prefs = testPrefs({ outputSampleRate: 48000, outputDeviceName: null, genDitherBits: 12,
    genOutputChannels: 'BOTH', genSignalForm: GenSignalForm.SINE, genFrequencyHz: 1000, genAmplitudeVrms: 0.5 });
  const lane = {
    sampleRate: 48000,
    emittedHz: () => [1000, 0],
    async open(spec) { opened.push(spec); return 48000; },
    async start() {},
    async close() {},
    port: { postMessage: () => {} },
  };
  const ctrl = new GeneratorController(prefs, { openPlayback: () => lane, status: () => {} });

  await ctrl.startGenerator();

  assert.equal(opened.length, 1, 'the lane was opened');
  assert.equal(opened[0].ditherBits, 12,
    'the stored depth is what gen.open fixes the session at');
  await ctrl.stopGenerator();
});

// --- F21: the caller's promise ends at the COMMAND, not at the end of the file ---

/** A REAL GeneratorController over a bench lane, driven through the real playFileBuffer. */
function realController(lane) {
  const prefs = testPrefs({ outputSampleRate: 48000, outputDeviceName: null, genDitherBits: 0, genOutputChannels: 'BOTH' });
  return new GeneratorController(prefs, { openPlayback: () => lane, status: () => {} });
}

test('F21: playFileBuffer resolves once the BENCH HAS the file, not when the file ends',
  async () => {
    const lane = laneWithHeldPlay();
    lane.open = async () => 48000;
    lane.file = { playing: true, finished: false };   // still playing when the command returns
    const ctrl = realController(lane);
    const led = [];
    const off = () => led.push('off');
    MessageBus.instance().subscribe(Events.FILE_PLAY_STOPPED, off);

    lane.release();
    await ctrl.playFileBuffer([new Float32Array(4)], 48000, false, { bytes: new Uint8Array(4), name: 'a.wav' });
    led.push('on');   // what the pane does the instant its await resolves

    assert.equal(ctrl.filePlaying, true, 'the file is still playing on the bench');
    assert.deepEqual(led, ['on'], 'the LED lights DURING playback - it used to light after it');

    // ...and the detached watch still ends the session when the bench says the file ran out.
    lane.file = { playing: false, finished: true };
    for (let i = 0; i < 40 && ctrl.filePlaying; i++) await new Promise((r) => setTimeout(r, 10));
    MessageBus.instance().unsubscribe(Events.FILE_PLAY_STOPPED, off);
    assert.deepEqual(led, ['on', 'off'], 'LED on during, off after - in that order');
    assert.equal(ctrl.filePlaying, false);
  });

test('F21: a failure inside the DETACHED watch is still reported, never unhandled', async () => {
  const lane = laneWithHeldPlay();
  lane.open = async () => 48000;
  lane.file = { playing: true, finished: false };
  lane.fileState = () => { throw new Error('the watch blew up'); };
  const ctrl = realController(lane);

  lane.release();
  await ctrl.playFileBuffer([new Float32Array(4)], 48000, false, { bytes: new Uint8Array(4), name: 'a.wav' });

  for (let i = 0; i < 40 && ctrl.filePlaying; i++) await new Promise((r) => setTimeout(r, 10));
  assert.equal(ctrl.filePlayError, 'generator.error.playFile',
    'the rejection lands in the localized surface a caller would have caught');
  assert.equal(ctrl.filePlaying, false, 'and the session is closed out either way');
});

test('a bench REFUSAL and a local open failure are two different sentences', () => {
  // Java keeps them apart deliberately (PlaybackStateEnum.REMOTE_REFUSED): "the server refused"
  // and "could not open the output device" leave the operator with different next moves, and the
  // remote twin has its OWN key. The reason inside is the same one the bench classified and sent.
  const reason = DeviceFailureReason.DEVICE_IN_USE;
  // A minimal `this` on the real prototype - no controller construction, no browser globals.
  const withSink = (sink) => Object.assign(Object.create(GeneratorController.prototype), { _sink: sink });
  const bench = withSink({ emittedHz: () => 0 });        // a lane that reports the FAR END
  const local = withSink({ start() {}, close() {} });    // one that cannot

  assert.equal(bench._openFailureText(reason), 'generator.error.remoteRefused');
  assert.equal(local._openFailureText(reason), 'generator.error.openDeviceFailed');
  assert.equal(withSink(null)._openFailureText(reason), 'generator.error.openDeviceFailed',
    'a failure before any lane exists is a local one');
});
