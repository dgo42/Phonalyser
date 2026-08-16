/*
 * Phonalyser web - the net protocol's fixed vocabulary.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of the shared wire module org.edgo.audio.measure.net.proto
 * (NetProto + MessageType + NetFields + ErrorCode + FrameType + BinaryFrame) - every value
 * here is a literal quotation of doc/NET-PROTOCOL.md, so the server, the desktop client and
 * this one can never disagree about a port, an interval, a field name or a frame layout.
 *
 * Constants and one parse function, no session behaviour: what the messages MEAN lives with
 * the state that owns it (net-connection.js, net-device-manager.js, net-capture-source.js),
 * exactly as the Java splits it. Java keeps these in their own module precisely so neither
 * side can re-type a field name by hand - a mistyped 'emmitHz' is a silent runtime no-op.
 */

/** The protocol's fixed numbers (Java NetProto). */
export const NetProto = Object.freeze({
  /** Highest wire-protocol version this build speaks (spec 1). */
  PROTO_VERSION: 2,
  /** Lowest version this build still speaks - spec 1 negotiates the RANGE. Equal to
   *  PROTO_VERSION, so this build's range is 2..2 and a v1 peer is refused at hello: spec 1
   *  calls v2 a hard cut, because v2 carries the audio on a SECOND connection a v1 peer never
   *  dials (spec 4.7) and refuses capture.start until it has attached (spec 4.4) - there is no
   *  subset of v2 a v1 peer could be served with. The negotiation itself is untouched. */
  PROTO_MIN_VERSION: 2,
  /** Default port, ONE for both planes: the HTTP endpoints of spec 3 and the WebSocket
   *  upgrade of spec 4 are served by the same listener. */
  DEFAULT_PORT: 8377,
  /** Keepalive period, both directions (spec 4.1). */
  PING_INTERVAL_MS: 500,
  /** Discovery beacon period (spec 2.1): how often a server announces itself, and therefore how
   *  often a client that cannot HEAR the beacon has to ask instead. The same 500 ms as the
   *  keepalive today and a different concept entirely - a keepalive is a session's heartbeat,
   *  this is the cadence of the room. Named apart so tuning one can never silently move the
   *  other. */
  BEACON_INTERVAL_MS: 500,
  /** Unanswered pings that declare the connection dead - 2 s (spec 4.1). */
  MAX_MISSED_PINGS: 4,
  /** Upload cap for PUT /files (spec 3): 50 MB. */
  MAX_UPLOAD_BYTES: 50 * 1024 * 1024,
});

/**
 * Every `t` discriminator the control channel knows (spec 4.1-4.6), with the exact wire text.
 * The key is the wire name in upper snake case; the mapping is explicit rather than derived so
 * a rename here can never silently change the protocol (Java MessageType).
 * @enum {string}
 */
export const MessageType = Object.freeze({
  // Session - spec 4.1.
  HELLO: 'hello',
  PING: 'ping',
  BYE: 'bye',
  // Response envelope - spec 4.0.
  RESP: 'resp',
  // Devices and locks - spec 4.3.
  BACKEND_LIST: 'backend.list',
  BACKEND_SELECT: 'backend.select',
  DEVICES_LIST: 'devices.list',
  DEVICE_ACQUIRE: 'device.acquire',
  DEVICE_RELEASE: 'device.release',
  DEVICE_SET_CALIBRATION: 'device.setCalibration',
  DEVICE_SET_CARD: 'device.setCard',
  CARDS_LIST: 'cards.list',
  /** Stores a NEW card on the server (v1.1) - create-only, no lock, no broadcast: a card
   *  nothing is bound to is in force nowhere. The binding that follows is the write. */
  CARDS_PUT: 'cards.put',
  EV_DEVICES_CHANGED: 'ev.devices.changed',
  EV_DEVICE_ERROR: 'ev.device.error',
  // Capture streaming - spec 4.4.
  CAPTURE_OPEN: 'capture.open',
  CAPTURE_START: 'capture.start',
  CAPTURE_STOP: 'capture.stop',
  CAPTURE_CLOSE: 'capture.close',
  /** The only message a DATA connection ever sends (spec 4.7): its first, which is what MAKES
   *  it a data connection, and its last - everything after it is audio going the other way. */
  CAPTURE_ATTACH: 'capture.attach',
  // Remote generator - spec 4.5.
  GEN_OPEN: 'gen.open',
  GEN_CONFIG: 'gen.config',
  GEN_START: 'gen.start',
  GEN_STOP: 'gen.stop',
  GEN_FFT_GRID: 'gen.fftGrid',
  GEN_TRIM: 'gen.trim',
  GEN_TRIM2: 'gen.trim2',
  GEN_TRIM_RESET: 'gen.trimReset',
  GEN_STATE: 'gen.state',
  GEN_PLAY_FILE: 'gen.playFile',
  GEN_STOP_FILE: 'gen.stopFile',
  GEN_CLOSE: 'gen.close',
  EV_GEN_STATE: 'ev.gen.state',
  // QA40x extension - spec 4.6.
  QA40X_INFO: 'qa40x.info',
  QA40X_RANGES: 'qa40x.ranges',
  QA40X_SET_INPUT_RANGE: 'qa40x.setInputRange',
  QA40X_SET_OUTPUT_RANGE: 'qa40x.setOutputRange',
  QA40X_CALIBRATION: 'qa40x.calibration',
  QA40X_SETTINGS: 'qa40x.settings',
});

/** Whether a wire name is a message THIS build knows (Java MessageType.fromWire ≠ UNKNOWN).
 *  The generic backend-request seam asks before sending: a panel from a future build naming a
 *  message this one cannot form must be refused here, not answered UNSUPPORTED by the server. */
export function isKnownMessage(wire) {
  return Object.values(MessageType).includes(wire);
}

/** The refusal codes of spec 4.2 (Java ErrorCode). */
export const ErrorCode = Object.freeze({
  PROTO_MISMATCH: 'PROTO_MISMATCH',
  BAD_REQUEST: 'BAD_REQUEST',
  UNSUPPORTED: 'UNSUPPORTED',
  NOT_LOCKED: 'NOT_LOCKED',
  DEVICE_LOCKED: 'DEVICE_LOCKED',
  DEVICE_STALE: 'DEVICE_STALE',
  DEVICE_ERROR: 'DEVICE_ERROR',
  BACKEND_MISMATCH: 'BACKEND_MISMATCH',
  /** capture.start on a capture whose DATA connection has not attached yet (spec 4.4, 4.7).
   *  Its own code and not BAD_REQUEST: nothing is malformed, the client is one step early, and
   *  the frames the start would produce would have nowhere to go. */
  NOT_ATTACHED: 'NOT_ATTACHED',
  NO_SUCH_FILE: 'NO_SUCH_FILE',
  FILE_TOO_LARGE: 'FILE_TOO_LARGE',
  INTERNAL: 'INTERNAL',
});

/**
 * Wire FIELD NAMES (Java NetFields). Every producer and consumer names its fields from here:
 * these strings are the only contract a partial update like gen.config has.
 */
/**
 * The VALUE vocabulary of spec 4.3's `direction` field ("input" | "output") - a value, not a field
 * name. It reads identically to NetFields.INPUT / .OUTPUT today, which is exactly why it has its
 * own home: comparing a wire value against a field-name constant works only by that coincidence,
 * and renaming the field would silently break the direction test.
 */
export const NetDirection = Object.freeze({ INPUT: 'input', OUTPUT: 'output' });

export const NetFields = Object.freeze({
  // shared
  NAME: 'name', PROTO: 'proto', APP: 'app', SERVER_ID: 'serverId', PORT: 'port',
  RATE: 'rate', BITS: 'bits', CHANNELS: 'channels', LOOP: 'loop', FILE_ID: 'fileId',
  // session - 4.1
  // CLIENT_ID is the session's own handle, answered by hello and spent by capture.attach
  // (spec 4.7). A SECRET: never logged, never shown, never in a beacon or an error - what a
  // client is KNOWN by is NAME, which is what a DEVICE_LOCKED refusal quotes.
  PROTO_MIN: 'protoMin', CLIENT: 'client', CLIENT_ID: 'clientId', CAPS: 'caps',
  CAP_QA40X: 'qa40x', CAP_GEN: 'gen', CAP_FILES: 'files',
  // HTTP - 2.2 and 3
  SERVERS: 'servers', HOST: 'host', SELF: 'self', OS: 'os', UPTIME_S: 'uptimeS',
  BYTES: 'bytes', OK: 'ok', ERROR: 'error', CODE: 'code', MESSAGE: 'message',
  // devices and locks - 4.3
  BACKENDS: 'backends', BACKEND: 'backend', DISPLAY_NAME: 'displayName',
  AVAILABLE: 'available', OPERATIONAL: 'operational', DEVICES: 'devices', INDEX: 'index',
  DESCRIPTION: 'description', VENDOR: 'vendor', INPUT: 'input', OUTPUT: 'output',
  FORMATS: 'formats', HAS_BIT_DEPTH: 'hasBitDepth', LOCK: 'lock', CAL: 'cal',
  FS_RMS_LEFT: 'fsRmsLeft', FS_RMS_RIGHT: 'fsRmsRight', CARD: 'card', CARDS: 'cards',
  // cards.put's body: the whole card in the same vocabulary its devices.yaml entry uses -
  // { name, match, input/output: { channels, ranges: [{ label, fsVrms, calibrated }], activeRange } }.
  CONTENT: 'content',
  BY: 'by', DIRECTION: 'direction', DETAIL: 'detail', REASON: 'reason',
  // capture streaming - 4.4
  CAPTURE_ID: 'captureId', FRAME_BYTES: 'frameBytes',
  // remote generator - 4.5
  GEN_ID: 'genId', DITHER_BITS: 'ditherBits', OUTPUT_CHANNELS: 'outputChannels',
  FORM: 'form', FREQUENCY: 'frequency', AMPLITUDE_VRMS: 'amplitudeVrms',
  DAC_FS_VOLTAGE_AMPL: 'dacFsVoltageAmpl', RIGHT_LANE_SCALE: 'rightLaneScale',
  RECTANGLE_DUTY: 'rectangleDuty', TRIANGLE_DUTY: 'triangleDuty',
  DUAL: 'dual', FREQUENCY2: 'frequency2', AMP1_PCT: 'amp1Pct', AMP2_PCT: 'amp2Pct',
  SWEEP: 'sweep', F0: 'f0', F1: 'f1', DURATION_SAMPLES: 'durationSamples',
  LEAD_IN_SAMPLES: 'leadInSamples', FADE_IN_SAMPLES: 'fadeInSamples',
  FADE_OUT_SAMPLES: 'fadeOutSamples', COMPENSATION: 'compensation', AMP_RATIOS: 'ampRatios',
  H_NUMS: 'hNums', PHI_INITS: 'phiInits', DUAL_COMPENSATION: 'dualCompensation',
  A_COEF: 'aCoef', B_COEF: 'bCoef', CLEAR_COMPENSATION: 'clearCompensation',
  FFT_SIZE: 'fftSize', SNAP_ENABLED: 'snapEnabled', HZ: 'hz', RUNNING: 'running',
  NOMINAL_HZ: 'nominalHz', EMIT_HZ: 'emitHz', EMIT2_HZ: 'emit2Hz', ACTIVE: 'active',
  POS_SAMPLES: 'posSamples', FILE: 'file', PLAYING: 'playing', FINISHED: 'finished',
  // QA40x extension - 4.6
  FIRMWARE_VERSION: 'firmwareVersion', USB_VOLTAGE: 'usbVoltage', USB_CURRENT: 'usbCurrent',
  ISO_CURRENT: 'isoCurrent', TEMPERATURE: 'temperature', CAPABILITY: 'capability',
  CAPABILITY2: 'capability2', SERIAL_NUMBER: 'serialNumber',
  INPUT_DBV: 'inputDbv', OUTPUT_DBV: 'outputDbv',
  ACTIVE_INPUT_DBV: 'activeInputDbv', ACTIVE_OUTPUT_DBV: 'activeOutputDbv',
  DBV: 'dbv', ADC: 'adc', DAC: 'dac', LEFT: 'left', RIGHT: 'right',
  I2S_ENABLED: 'i2sEnabled',
});

/** Binary frame types (spec 5, Java FrameType). 4 is reserved for uplink PCM, not in v1. */
export const FrameType = Object.freeze({
  PCM: 1,
  MARKER: 2,
  GAP: 3,
  UPLINK_PCM: 4,
});

/** Fixed binary header size - the payload starts here (spec 5). */
export const HEADER_BYTES = 16;

/** MARKER kind 1: the first PCM byte AFTER this frame is aligned with sweep output sample 0,
 *  to within one capture batch (spec 5). */
export const MARKER_SWEEP_START = 1;

/**
 * Decodes one received binary message (spec 5). Everything after the 16-byte header is the
 * payload - the WebSocket message boundary carries the total length, which is what lets a
 * receiver skip a frame type it does not know (spec 1). Little-endian throughout, which a
 * DataView reads without byte shuffling.
 *
 * @param {ArrayBuffer} message one whole WebSocket binary message
 * @returns {{typeCode: number, streamId: number, packetCounter: number, n: number,
 *            payload: DataView}} the parsed frame; `payload` is a VIEW over the received
 *          buffer, never a copy - the decoder reads it once, per batch, on the socket's turn
 * @throws {Error} when the message is shorter than its header, or a PCM frame's declared
 *         length disagrees with what it carries - a peer whose framing is broken is not one
 *         to keep measuring with
 */
export function parseBinaryFrame(message) {
  if (message.byteLength < HEADER_BYTES) {
    throw new Error(`net binary frame shorter than its header: ${message.byteLength} < ${HEADER_BYTES} bytes`);
  }
  const head = new DataView(message, 0, HEADER_BYTES);
  const typeCode = head.getUint8(0);
  // offset 1: reserved, 0
  const streamId = head.getUint16(2, true);
  // packetCounter is u64; Number is exact to 2^53 - at one frame per capture batch that is
  // longer than the universe, and a BigInt here would infect every comparison.
  const packetCounter = Number(head.getBigUint64(4, true));
  const n = head.getUint32(12, true);
  const payload = new DataView(message, HEADER_BYTES, message.byteLength - HEADER_BYTES);
  if (typeCode === FrameType.PCM && n !== payload.byteLength) {
    throw new Error(`PCM frame declares n=${n} but carries ${payload.byteLength} payload bytes`);
  }
  return { typeCode, streamId, packetCounter, n, payload };
}
