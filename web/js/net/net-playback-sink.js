/*
 * Phonalyser web - the generator that runs on the bench.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of the gen.* half of org.edgo.audio.measure.net.client.NetDeviceManager (spec
 * 4.5) plus the drive order of gui.sound.GeneratorLane.startRemote - on the SAME PlaybackSink
 * seam web-audio-playback-sink.js and qa40x-playback-sink.js implement, so GeneratorController
 * neither knows nor cares that this DDS is on another machine.
 *
 * THE GENERATOR RUNS ON THE SERVER (spec 4.5: "no uplink audio"). What crosses the wire is the
 * desktop GeneratorController's setter surface, with FINAL numbers: the client does its own
 * calibration arithmetic exactly as it does locally, and the bench applies what it is given.
 *
 * THE GRANTED RATE HAS THE LAST WORD. gen.open answers with the rate the bench's DAC took, and
 * everything downstream - the bin snap, every second->sample conversion, every sweep length -
 * must be computed against THAT, not against what was asked for. This sink reports it as
 * `sampleRate`, which is precisely the value the controller re-pins to.
 *
 * PUSHES ARE FIRE-AND-FORGET, commands are waited for. The configuration and the FLL trims are
 * driven by sliders and by a servo, at rates where a blocking round trip would freeze the UI
 * for the length of the bench's worst reply - and there is nothing in the answer to act on,
 * because the generator's state comes back as ev.gen.state either way. gen.start is different:
 * the operator pressed Play, and "did the tone start" is the one answer the pane cannot do
 * without.
 */
import { MessageType, NetFields, NetProto } from './net-proto.js';
import { refInto, refText } from './net-device-ref.js';
import { GenSignalForm, isDualTone, loadHarmonics, loadIntermod } from '../generator/dds-kernel.js';

/** genId while no remote generator is open. */
const NO_GEN = 0;
/** Spec §3's upload endpoint: raw body, no multipart. */
const FILES_PATH = '/files';
/** 413 - the bench's store is full (spec §3 bounds the whole store, not only each upload). */
const HTTP_ENTITY_TOO_LARGE = 413;

/**
 * The file is bigger than the protocol allows - raised BEFORE any socket is opened, and carrying
 * both numbers so the caller can say them in the operator's language (Java
 * FileUpload.TooLargeException -> RemoteGenerator.FileTooLargeException).
 */
export class FileTooLargeError extends Error {
  constructor(bytes, limitBytes) {
    super(`the file is ${bytes} bytes, the upload limit is ${limitBytes} bytes`);
    this.name = 'FileTooLargeError';
    this.bytes = bytes;
    this.limitBytes = limitBytes;
  }
}
/** Radians per turn - the web's .dpd parsers answer in TURNS (what the local DDS kernel wants)
 *  while the wire's phiInits are RADIANS (Java Predistortion: Math.atan2(im, re)). */
const TWO_PI = 2 * Math.PI;

export class NetPlaybackSink {

  /**
   * @param {Object} deps
   * @param {import('./net-connection.js').NetConnection} deps.connection the live session
   * @param {Object} deps.device the remote OUTPUT device ref
   * @param {import('./net-device-manager.js').NetDeviceManager} deps.owner the lock register
   *        and the fault hub - the whole client has to agree on which devices it holds
   * @param {(text: string) => void} [deps.status]
   */
  constructor({ connection, device, owner, status, fetchImpl }) {
    this._connection = connection;
    this._device = device;
    this._owner = owner;
    this._status = status || (() => {});
    /** The upload transport, injected so a test can prove the size gate opened NO socket at all
     *  (Java's MockBench counts upload requests for exactly that assertion). */
    this._fetch = fetchImpl || ((url, init) => fetch(url, init));
    /** The bench's file-playback state, from ev.gen.state's `file` block. */
    this._fileState = { playing: false, finished: false };
    /** A file failure the bench reported, claimed once by the controller's watch. */
    this._fileError = null;
    this._genId = NO_GEN;
    this._grantedRate = 0;
    this._acquired = false;
    /** The last ev.gen.state and NOTHING else: a command is never taken for the state it asked
     *  for - the bench says what it emits, and only the bench (spec 4.5). */
    this._state = { running: false, emitHz: 0, emit2Hz: 0 };
    /** The bench's output lane ended from BELOW - claimed once by the pane's blink tick
     *  (the lostFromBelow contract). */
    this._lost = null;
    this._events = (event) => this._onEvent(event);
    this._sessionEnd = (reason) => this._onSessionEnd(reason);
    /** The live-control channel the controller posts through: a MessagePort-shaped object whose
     *  postMessage translates the DDS worklet's vocabulary into gen.config pushes. */
    this.port = { postMessage: (msg) => this._postControl(msg) };
  }

  /** The rate the bench GRANTED (0 while closed) - what the controller re-pins to. */
  get sampleRate() { return this._grantedRate; }

  /** The bench's own reading of a refused open, handed on unread (the reason was classified on
   *  the bench by the backend that owns the driver). */
  classifyFailure(err) { return this._owner.classifyFailure(err); }

  /** WHAT the bench said about it, when the refusal carried more than a reason (spec 4.2's code,
   *  message and holder) - handed on unread, exactly like the reason above. */
  refusalText(err) { return this._owner.refusalText(err); }

  /** The confession that this lane died from below - non-null once the bench's output lane
   *  failed or the session ended while the tone was up. A PEEK, not a claim. */
  lostFromBelow() { return this._lost; }

  /** What the bench says it is EMITTING (spec 4.5's ev.gen.state): [tone1Hz, tone2Hz], where
   *  0.0 means "this form emits no such tone" and is NEVER a frequency. The hint plumbing feeds
   *  from this and never from the nominal values - see GENERATOR_EMITTED_HZ. */
  emittedHz() { return [this._state.emitHz, this._state.emit2Hz]; }

  /** True while the bench says it is emitting. */
  get running() { return this._state.running; }

  /** The bench's own file-playback state, from the `file` sub-object of ev.gen.state (spec 4.5).
   *  Absent on a bench that never played one, which reads as idle rather than as a change
   *  (Java RemoteGenerator.fileState). */
  fileState() { return this._fileState; }

  /** Claims a file failure the bench reported, once (Java takeFileErrorForReport). */
  takeFileErrorForReport() {
    const failure = this._fileError;
    this._fileError = null;
    return failure;
  }

  // ---------------------------------------------------------------------------
  // Playing a FILE on the bench (spec §3 upload + 4.5 command)
  // ---------------------------------------------------------------------------

  /**
   * Spec §3 + 4.5: the bytes go up over PUT /files, then gen.playFile tells the lane already open
   * to render them (Java NetDeviceManager.playFile). Two steps, one operator action, so BOTH
   * failures arrive as one refusal.
   *
   * THE SIZE GATE IS THE PROTOCOL'S AND IT FIRES BEFORE ANY SOCKET IS OPENED - the same constant
   * the server enforces, so the two can never disagree. Checking first is a courtesy, not an
   * optimisation: pushing 80 MB up a slow link to be told 413 at the end wastes the operator's
   * time to learn something known before the first byte moved.
   *
   * @param {Uint8Array} content the RAW file, exactly as it came off disk - the BENCH decodes it
   * @param {boolean} loop
   * @throws {FileTooLargeError} over the protocol limit, nothing sent
   */
  async playFile(content, loop) {
    const open = this._genId;
    const session = this._connection;
    if (open === NO_GEN || session == null) {
      throw new Error(`${MessageType.GEN_PLAY_FILE} on a bench with no open generator`);
    }
    if (content.length > NetProto.MAX_UPLOAD_BYTES) {
      throw new FileTooLargeError(content.length, NetProto.MAX_UPLOAD_BYTES);
    }
    this._fileError = null;
    const fileId = await this._upload(session, content);
    const request = session.newRequest(MessageType.GEN_PLAY_FILE);
    request[NetFields.GEN_ID] = open;
    request[NetFields.FILE_ID] = fileId;
    request[NetFields.LOOP] = loop === true;
    const answer = await session.request(request);
    if (answer.ok === false) {
      throw session.refusal(`${MessageType.GEN_PLAY_FILE} failed on ${refText(this._device)}`, answer);
    }
    // NOT a state: the bench's own ev.gen.state is what turns the indicator on, exactly as it is
    // for the tone. Setting it here would report a file playing before the lane had accepted it.
    console.info(`net gen ${open}: playing ${fileId} (${content.length} bytes, loop=${loop === true})`);
  }

  /** Spec §3's PUT /files, answering the id the bench filed the bytes under. The session carries
   *  commands - small, ordered, latency-sensitive - and a fifty-megabyte body pushed through it
   *  would stall every ping behind it for as long as the transfer took, which the 2 s keepalive
   *  deadline reads as a dead connection. So the bytes take the REST side of the same port. */
  async _upload(session, content) {
    const url = `${session.httpBase()}${FILES_PATH}`;
    let answer;
    try {
      answer = await this._fetch(url, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/octet-stream' },
        body: content,
      });
    } catch (e) {
      // The bench's own words go to the log; the caller says it in the operator's language.
      console.warn(`net gen: uploading the file to ${url} failed: ${e.message}`);
      throw new Error(`the upload to ${url} failed: ${e.message}`);
    }
    if (answer.status === HTTP_ENTITY_TOO_LARGE) {
      // Spec §3 bounds the whole STORE, not only each upload: this is not the operator's file
      // being too big (that was refused above, before the socket) - something has to be dropped
      // at the far end, and a smaller file would not help.
      throw new Error(`the bench refused the upload: its store is full (HTTP ${answer.status})`);
    }
    if (!answer.ok) {
      throw new Error(`the bench refused the upload: HTTP ${answer.status}`);
    }
    const body = await answer.json();
    const fileId = body && body[NetFields.FILE_ID];
    if (!fileId) throw new Error(`the bench answered the upload without a ${NetFields.FILE_ID}`);
    return fileId;
  }

  /** Spec 4.5's gen.stopFile. Nothing open is the idempotent teardown, and the far end pushes the
   *  cleared file state - nothing is assumed here (Java NetDeviceManager.stopFile). */
  async stopFile() {
    const open = this._genId;
    const session = this._connection;
    if (open === NO_GEN || session == null) return;
    const request = session.newRequest(MessageType.GEN_STOP_FILE);
    request[NetFields.GEN_ID] = open;
    const answer = await session.request(request);
    if (answer.ok === false) {
      console.warn(`net gen: the bench refused ${MessageType.GEN_STOP_FILE}:`, answer.error);
    }
  }

  // ---------------------------------------------------------------------------
  // The lane
  // ---------------------------------------------------------------------------

  /**
   * Spec 4.5: takes the output device ("all gen.* require the output-device lock") and opens
   * the server-side playback, silent until start().
   *
   * The lock is given back before any throw: an open that failed must cost nothing, and a lock
   * left behind is a DAC no other client can ever take again.
   *
   * @param {{sampleRate: number, deviceId: ?string, ditherBits: number, outputChannels: string}} spec
   * @returns {Promise<number>} the GRANTED rate
   */
  async open(spec) {
    const open = this._connection;
    const taken = await this._owner.acquireDevice(this._device);
    if (taken != null) throw taken;
    this._acquired = true;
    let opened;
    try {
      const request = refInto(this._device, open.newRequest(MessageType.GEN_OPEN));
      request[NetFields.RATE] = spec.sampleRate;
      request[NetFields.BITS] = spec.bits || 24;
      request[NetFields.DITHER_BITS] = spec.ditherBits || 0;
      request[NetFields.OUTPUT_CHANNELS] = spec.outputChannels || 'BOTH';
      opened = await open.request(request);
    } catch (e) {
      await this._release();
      throw e;
    }
    if (opened.ok === false) {
      await this._release();
      throw open.refusal(`cannot open a generator on ${refText(this._device)}`, opened);
    }
    const data = opened.data || {};
    this._genId = Number(data[NetFields.GEN_ID]) || NO_GEN;
    this._lost = null;                    // a fresh lane owes nothing to the old one's death
    this._state = { running: false, emitHz: 0, emit2Hz: 0 };
    this._grantedRate = Number(data[NetFields.RATE]) || spec.sampleRate;
    open.addEventListener(this._events);
    open.addCloseListener(this._sessionEnd);
    console.info(`net gen ${this._genId}: open on ${refText(this._device)} at `
      + `${this._grantedRate} Hz, dither ${spec.ditherBits} bit, lanes ${spec.outputChannels}`);
    return this._grantedRate;
  }

  /**
   * Configures the lane and puts it on air, in GeneratorLane.startRemote's order: the waveform
   * first, then its parameters, the compensation, and the FFT grid LAST - the bench snaps
   * against the form it currently holds, and a grid pushed before it would be applied to the
   * previous one. Only then gen.start.
   *
   * @param {Object} spec the PlaybackSpec (see generator-controller.js)
   */
  async start(spec) {
    const control = spec.control || {};
    const sweep = control.linearSweep || control.logSweep || null;
    this._config({ [NetFields.FORM]: spec.form });
    // A sweep has no tone frequency - its band travels in the sweep block below.
    if (!sweep) this._config({ [NetFields.FREQUENCY]: spec.frequency });
    // The DAC full scale belongs where the DAC is, and is now the bench's business ALONE -
    // input and output full-scale calibration are applied ON THE SERVER. gen.open makes
    // the server apply its own card, and this client no longer pushes anything over it - a
    // local full scale sent onto another machine's DAC is what made the same entered amplitude
    // come out at a different level on each client. The amplitude itself is absolute volts and
    // travels unchanged.
    this._config({ [NetFields.AMPLITUDE_VRMS]: spec.amplitudeVRms });
    if (control.rectDuty != null) this._config({ [NetFields.RECTANGLE_DUTY]: control.rectDuty });
    if (control.triDuty != null) this._config({ [NetFields.TRIANGLE_DUTY]: control.triDuty });
    if (isDualTone(spec.form)) {
      this._config({ [NetFields.DUAL]: {
        [NetFields.FREQUENCY2]: control.frequency2,
        [NetFields.AMP1_PCT]: control.dualAmp1Pct,
        [NetFields.AMP2_PCT]: control.dualAmp2Pct,
      } });
    }
    if (sweep) this._config({ [NetFields.SWEEP]: sweepBlock(sweep, control.sweepParams) });
    this._pushCompensation(spec, control);
    if (control.fftSize > 0) {
      this._push(MessageType.GEN_FFT_GRID, {
        [NetFields.FFT_SIZE]: control.fftSize,
        [NetFields.SNAP_ENABLED]: control.snapEnabled === true,
      });
    }
    await this._command(MessageType.GEN_START);
  }

  /** Spec 4.5: stops and gives the far end's playback line back, then the device lock with it.
   *  Best effort and idempotent, because it is a teardown step: the alternative is a DAC nobody
   *  can take until the far end's own keepalive notices. */
  async close() {
    const open = this._genId;
    this._genId = NO_GEN;
    this._grantedRate = 0;
    this._state = { running: false, emitHz: 0, emit2Hz: 0 };
    const session = this._connection;
    session.removeEventListener(this._events);
    session.removeCloseListener(this._sessionEnd);
    if (open !== NO_GEN) {
      try {
        const request = session.newRequest(MessageType.GEN_CLOSE);
        request[NetFields.GEN_ID] = open;
        await session.request(request);
      } catch (e) {
        console.warn(`net gen ${open}: close failed: ${e.message}`);
      }
    }
    await this._release();
  }

  // ---------------------------------------------------------------------------
  // The live-control channel
  // ---------------------------------------------------------------------------

  /**
   * One worklet-vocabulary control message, translated into spec 4.5's partial update. Only
   * the fields PRESENT are applied, on both sides of the wire, for the same reason: re-sending
   * a sweep's duration re-renders the chirp and restarts it at sample 0, underneath whoever
   * was recording it.
   */
  _postControl(msg) {
    if (msg == null) return;
    const fields = {};
    if (msg.frequency != null) fields[NetFields.FREQUENCY] = msg.frequency;
    if (msg.amplitudeVRms != null) fields[NetFields.AMPLITUDE_VRMS] = msg.amplitudeVRms;
    // dacFsVoltageAmpl / rightLaneScale are deliberately dropped here: a live calibration
    // change belongs to the bench's card, which the server applies itself (see start()).
    if (msg.rectDuty != null) fields[NetFields.RECTANGLE_DUTY] = msg.rectDuty;
    if (msg.triDuty != null) fields[NetFields.TRIANGLE_DUTY] = msg.triDuty;
    if (msg.form != null) fields[NetFields.FORM] = msg.form;
    if (msg.frequency2 != null || msg.dualAmp1Pct != null || msg.dualAmp2Pct != null) {
      const dual = {};
      if (msg.frequency2 != null) dual[NetFields.FREQUENCY2] = msg.frequency2;
      if (msg.dualAmp1Pct != null) dual[NetFields.AMP1_PCT] = msg.dualAmp1Pct;
      if (msg.dualAmp2Pct != null) dual[NetFields.AMP2_PCT] = msg.dualAmp2Pct;
      fields[NetFields.DUAL] = dual;
    }
    const sweep = msg.linearSweep || msg.logSweep || null;
    if (sweep || msg.sweepParams) fields[NetFields.SWEEP] = sweepBlock(sweep, msg.sweepParams);
    if (Object.keys(fields).length > 0) this._config(fields);
    // The FLL's actuator: the servo stays client-side, the correction is absolute (spec 4.5).
    if (msg.trimHz != null) this._push(MessageType.GEN_TRIM, { [NetFields.HZ]: msg.trimHz });
    if (msg.trim2Hz != null) this._push(MessageType.GEN_TRIM2, { [NetFields.HZ]: msg.trim2Hz });
    if (msg.trimReset === true) this._push(MessageType.GEN_TRIM_RESET, {});
  }

  /** Spec 4.5's compensation blocks. The web's .dpd parsers answer phases in TURNS (what the
   *  local DDS kernel consumes); the wire wants RADIANS, which is what Java's Predistortion
   *  hands its target - so they are converted here, once, at the boundary. A compensated form
   *  with no table is left to the caller's own refusal (the local path refuses it too). */
  _pushCompensation(spec, control) {
    if (spec.form !== GenSignalForm.SINE_COMP && spec.form !== GenSignalForm.DUAL_TONE_COMP) {
      this._config({ [NetFields.CLEAR_COMPENSATION]: true });
      return;
    }
    const text = control.dpdText;
    if (!text) return;
    try {
      if (spec.form === GenSignalForm.DUAL_TONE_COMP) {
        const set = loadIntermod(text);
        this._config({ [NetFields.DUAL_COMPENSATION]: {
          [NetFields.AMP_RATIOS]: [...set.amp],
          [NetFields.A_COEF]: [...set.a],
          [NetFields.B_COEF]: [...set.b],
          [NetFields.PHI_INITS]: [...set.phaseOffTurns].map((turns) => turns * TWO_PI),
        } });
      } else {
        const set = loadHarmonics(text, control.dpdFrequency || spec.frequency);
        this._config({ [NetFields.COMPENSATION]: {
          [NetFields.AMP_RATIOS]: [...set.amp],
          [NetFields.H_NUMS]: [...set.hNum],
          [NetFields.PHI_INITS]: [...set.phaseOffTurns].map((turns) => turns * TWO_PI),
        } });
      }
    } catch (e) {
      // Compensated sine IS its corrections: a bench started on SINE_COMP with a table that
      // would not parse must not emit a plain sine whose THD is then recorded as predistorted.
      console.error('net gen: the predistortion table could not be read', e);
      throw new Error('the predistortion file could not be read for the remote generator');
    }
  }

  // ---------------------------------------------------------------------------
  // What the bench says
  // ---------------------------------------------------------------------------

  /** One ev.gen.state (spec 4.5). Only what the panes read is kept: what is emitting, and at
   *  which frequencies - the sweep and file positions belong to the modules that own those
   *  measurements. A state for a generator this sink does not hold is ignored: taking it would
   *  report a tone nobody here asked for. */
  _onEvent(event) {
    if (event.t === MessageType.EV_DEVICE_ERROR) { this._onDeviceError(event); return; }
    if (event.t !== MessageType.EV_GEN_STATE) return;
    if (Number(event[NetFields.GEN_ID]) !== this._genId) return;
    this._state = {
      running: event[NetFields.RUNNING] === true,
      emitHz: Number(event[NetFields.EMIT_HZ]) || 0,
      emit2Hz: Number(event[NetFields.EMIT2_HZ]) || 0,
    };
    this._cacheFileState(event);
  }

  /**
   * Spec 4.3's ev.device.error for the OUTPUT direction: the bench's render loop died under a
   * tone this lane started. Recorded in the same claim-once cell a dead session uses, and
   * NEVER published from here - the pane's ON-AIR tick claims it, stops the generator and
   * raises the ONE operator report (Java NetDeviceManager.deviceError -> genEndedBelow ->
   * GeneratorLane.takePlaybackEndedFromBelow -> GeneratorPane.onPlaybackEndedFromBelow).
   *
   * It is the ONLY way this can be learnt. gen.start was answered when the lane accepted the
   * tone, and an ENGAGED generator makes no further calls - so a mid-play death has no request
   * left to come back out of, and the play LED sat lit over a lane that had stopped.
   *
   * The INPUT direction is not this lane's business: the capture stream finishes its own ring
   * and the measuring pane reports from there. One failure, one surface.
   */
  _onDeviceError(event) {
    if (this._genId === NO_GEN || this._lost != null) return;
    if (event[NetFields.DIRECTION] !== NetFields.OUTPUT) return;
    const detail = event[NetFields.DETAIL] || '';
    const failure = new Error(`the bench's output lane died - ${detail}`);
    if (this._fileState.playing) {
      // A FILE was on that lane, so the FILE player reports it: one lane dying is one thing
      // that happened and must raise ONE dialog, not the tone's and the file's both. Its state
      // goes finished so the watcher leaves even if nobody claims (Java :427-434).
      this._fileError = failure;
      this._fileState = { playing: false, finished: true };
    } else {
      this._lost = failure;
    }
    this._state = { running: false, emitHz: 0, emit2Hz: 0 };
    console.error(`net gen ${this._genId}: the bench's output lane died - ${detail}`);
  }

  /** The `file` sub-object of ev.gen.state: ABSENT on a bench that never played one, which reads
   *  as idle rather than as a change - so it is only replaced when the bench actually sent it
   *  (Java cacheFileState). */
  _cacheFileState(event) {
    const file = event[NetFields.FILE];
    if (file == null || typeof file !== 'object') return;
    this._fileState = {
      playing: file[NetFields.PLAYING] === true,
      finished: file[NetFields.FINISHED] === true,
    };
  }

  /** The session died while this lane was open: the tone is over whatever the pane still shows,
   *  and the pane learns bottom-up through the lostFromBelow consult: a net-backend disconnect
   *  stops playback from below, never from the pane down. */
  _onSessionEnd(reason) {
    if (this._genId === NO_GEN || this._lost != null) return;
    this._lost = new Error(`the session with the server ended while the tone was playing - ${reason.detail}`);
    this._genId = NO_GEN;
    this._state = { running: false, emitHz: 0, emit2Hz: 0 };
  }

  // ---------------------------------------------------------------------------
  // Talking to the server
  // ---------------------------------------------------------------------------

  /** A gen.config partial update. */
  _config(fields) { this._push(MessageType.GEN_CONFIG, fields); }

  /** Sends a generator message WITHOUT waiting for its answer, logging a refusal when one comes
   *  back. Nothing is sent when no generator is open - the no-op a local setter performs when
   *  nothing is playing. */
  _push(type, fields) {
    const open = this._genId;
    const session = this._connection;
    if (open === NO_GEN || session == null) return;
    const request = session.newRequest(type);
    request[NetFields.GEN_ID] = open;
    Object.assign(request, fields);
    session.send(request).then(
      (answer) => { if (answer && answer.ok === false) console.warn(`net gen: the bench refused ${type}:`, answer.error); },
      (e) => console.warn(`net gen: ${type} was not delivered: ${e.message}`));
  }

  /** gen.start / gen.stop: the same message shape with nothing in it but the handle, and an
   *  answer worth waiting for. */
  async _command(type) {
    const open = this._genId;
    const session = this._connection;
    if (open === NO_GEN || session == null) {
      throw new Error(`${type} on a bench with no open generator`);
    }
    const request = session.newRequest(type);
    request[NetFields.GEN_ID] = open;
    const answer = await session.request(request);
    if (answer.ok === false) {
      throw session.refusal(`${type} failed on ${refText(this._device)}`, answer);
    }
  }

  async _release() {
    if (!this._acquired) return;
    this._acquired = false;
    await this._owner.releaseDevice(this._device);
  }
}

/** Spec 4.5's sweep sub-object, carrying only what the caller set - re-sending a duration
 *  re-renders the chirp and restarts it at sample 0. */
function sweepBlock(sweep, params) {
  const block = {};
  if (sweep) {
    if (sweep.freqStart != null) block[NetFields.F0] = sweep.freqStart;
    if (sweep.freqEnd != null) block[NetFields.F1] = sweep.freqEnd;
    if (sweep.periodSamples != null) block[NetFields.DURATION_SAMPLES] = sweep.periodSamples;
    if (sweep.f0 != null) block[NetFields.F0] = sweep.f0;
    if (sweep.f1 != null) block[NetFields.F1] = sweep.f1;
    if (sweep.sweepSamples != null) block[NetFields.DURATION_SAMPLES] = sweep.sweepSamples;
    if (sweep.leadInSamples != null) block[NetFields.LEAD_IN_SAMPLES] = sweep.leadInSamples;
  }
  if (params) {
    if (params.loop != null) block[NetFields.LOOP] = params.loop;
    if (params.fadeInSamples != null) block[NetFields.FADE_IN_SAMPLES] = params.fadeInSamples;
    if (params.fadeOutSamples != null) block[NetFields.FADE_OUT_SAMPLES] = params.fadeOutSamples;
  }
  return block;
}
