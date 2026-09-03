// A REAL Phonalyser bench, on real sockets, for the browser end-to-end tests.
//
// It is one node http server that does three jobs at one port, exactly as the desktop server
// does (doc/NET-PROTOCOL.md): it serves the web app's files, answers the discovery endpoints
// GET /info and GET /servers, and upgrades `/` to the spec-4 WebSocket control channel. Serving
// the app from the SAME origin is deliberate - the page's own load-time origin probe then finds
// a server and auto-connects to it, which is the served-full-bundle configuration and the one a
// browser test can reach without a click.
//
// THE RULES ARE THE SERVER'S, NOT A CONVENIENCE. Every refusal below is copied from the class
// that raises it in modules/server-net: the lock register (a repeated acquire by the SAME
// session is granted), one capture stream per device, ONE generator per connection, a release
// that also closes what is open on the device, NOT_LOCKED before any stream call. A double that
// grants everything cannot fail a second run, and this round has already been burned once by
// exactly that (B14).
//
// The WebSocket half is implemented here rather than pulled in as a dependency: this needs a
// handshake and unfragmented text frames, the app never sends anything else, and a test double
// is a poor reason to make the build depend on an install succeeding.

import http from 'node:http';
import { createHash } from 'node:crypto';
import { createReadStream, existsSync, statSync } from 'node:fs';
import { join, normalize, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = fileURLToPath(new URL('.', import.meta.url));
const WEB_ROOT = normalize(join(__dirname, '..', '..'));   // web/

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.wasm': 'application/wasm',
  '.yaml': 'text/yaml; charset=utf-8',
  '.properties': 'text/plain; charset=utf-8',
};

/** RFC 6455's fixed GUID - the accept key is sha1(clientKey + GUID), base64. */
const WS_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';

/** Spec 4.1's keepalive, both halves of it: a `ping` every 500 ms, and four CONSECUTIVE
 *  unanswered ones (2 s of silence) means the peer is dead. The real server runs this arithmetic
 *  against every client, so a double that pings not at all cannot fail a client that stops
 *  answering - which is exactly what a measurement deconvolving on the browser's main thread does
 *  (B14). Ids are NEGATIVE: spec 4.0 keeps the server's own requests out of the client's id space. */
const PING_INTERVAL_MS = 500;
const PING_MISSES = 4;

// ---------------------------------------------------------------------------
// The WebSocket frame codec - text out, text in, and nothing this app never uses
// ---------------------------------------------------------------------------

/** One unfragmented text frame (opcode 1), server->client, never masked. */
function textFrame(text) {
  const payload = Buffer.from(text, 'utf8');
  const head = payload.length < 126 ? Buffer.from([0x81, payload.length])
    : payload.length < 65536
      ? Buffer.concat([Buffer.from([0x81, 126]), be16(payload.length)])
      : Buffer.concat([Buffer.from([0x81, 127]), be64(payload.length)]);
  return Buffer.concat([head, payload]);
}

function be16(n) { const b = Buffer.alloc(2); b.writeUInt16BE(n); return b; }
function be64(n) { const b = Buffer.alloc(8); b.writeBigUInt64BE(BigInt(n)); return b; }

/**
 * Pulls whole frames out of `buffer`, unmasking as it goes. Returns the frames it could complete
 * and the bytes left over - a socket hands us arbitrary slices, so a partial frame must wait for
 * the rest rather than be guessed at.
 */
function readFrames(buffer) {
  const frames = [];
  let at = 0;
  for (;;) {
    if (buffer.length - at < 2) break;
    const first = buffer[at], second = buffer[at + 1];
    const opcode = first & 0x0F;
    const masked = (second & 0x80) !== 0;
    let length = second & 0x7F;
    let header = 2;
    if (length === 126) {
      if (buffer.length - at < 4) break;
      length = buffer.readUInt16BE(at + 2); header = 4;
    } else if (length === 127) {
      if (buffer.length - at < 10) break;
      length = Number(buffer.readBigUInt64BE(at + 2)); header = 10;
    }
    const maskLen = masked ? 4 : 0;
    if (buffer.length - at < header + maskLen + length) break;
    const mask = masked ? buffer.subarray(at + header, at + header + 4) : null;
    const start = at + header + maskLen;
    const payload = Buffer.from(buffer.subarray(start, start + length));
    if (mask) for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
    frames.push({ opcode, payload });
    at = start + length;
  }
  return { frames, rest: buffer.subarray(at) };
}

// ---------------------------------------------------------------------------
// The bench
// ---------------------------------------------------------------------------

/**
 * One session's state and the whole spec-4 answer table. A session is a connection: its locks,
 * its capture streams and its ONE generator all die with it.
 */
class Session {
  constructor(bench, socket) {
    this.bench = bench;
    this.socket = socket;
    this.clientName = 'unnamed';
    this.locks = new Set();
    this.captures = new Map();       // captureId -> lock key
    this.genId = 0;
    this.genLock = null;
    this._nextCapture = 1;
    this._nextGen = 100;
    /** The keepalive of spec 4.1: the ping clock, the next (negative) request id, and how many
     *  pings this client has left unanswered in a row. */
    this._pingTimer = null;
    this._nextPing = -1;
    this._unanswered = 0;
  }

  send(message) { this.socket.write(textFrame(JSON.stringify(message))); }

  /** Starts pinging this client, and stops trusting it after PING_MISSES in a row. */
  startKeepalive() {
    if (this._pingTimer != null) return;
    this._pingTimer = setInterval(() => this.pingTick(), PING_INTERVAL_MS);
    if (this._pingTimer && typeof this._pingTimer.unref === 'function') this._pingTimer.unref();
  }

  pingTick() {
    if (this._unanswered >= PING_MISSES) {
      const missed = this._unanswered;
      this.bench.kills.push({ client: this.clientName, missed });
      console.log(`bench: ${missed} pings unanswered (${missed * PING_INTERVAL_MS} ms) - `
        + `'${this.clientName}' is declared dead, closing the session (spec 4.1)`);
      this.stopKeepalive();
      this.socket.destroy();
      return;
    }
    this._unanswered++;
    this.send({ t: 'ping', id: this._nextPing-- });
  }

  stopKeepalive() {
    if (this._pingTimer != null) { clearInterval(this._pingTimer); this._pingTimer = null; }
  }

  handle(message) {
    const id = message.id;
    if (message.t === 'resp') {
      // The client's answer to one of OUR pings - the only response a client ever sends. Spec 4.1
      // counts CONSECUTIVE misses, so any answer clears the count.
      this._unanswered = 0;
      return;
    }
    const answer = this.answer(message);
    if (answer == null) return;                       // an event or a ping answer, already sent
    if (answer.error) {
      this.bench.refusals.push({ t: message.t, ...answer.error });
      this.send({ t: 'resp', id, ok: false, error: answer.error });
      return;
    }
    this.send({ t: 'resp', id, ok: true, data: answer.data || {} });
  }

  answer(m) {
    const bench = this.bench;
    switch (m.t) {
      case 'hello':
        this.clientName = m.name || 'unnamed';
        // The session is live from here, and so is its keepalive - the client starts its own on
        // the same message.
        this.startKeepalive();
        return { data: { proto: 1, serverId: bench.serverId, name: bench.name,
          app: 'Phonalyser/1.1.1', caps: ['qa40x', 'gen', 'files'] } };
      case 'ping': return { data: {} };
      case 'bye': return { data: {} };
      case 'backend.list':
        return { data: { backends: bench.backends.map((b) => ({ backend: b.backend,
          displayName: b.displayName, available: true, operational: true, hasBitDepth: true })) } };
      case 'backend.select': {
        bench.selects.push(m.backend);
        if (bench.refuseSelect) return fail('BAD_REQUEST', `${m.backend} is not operational here`);
        const entry = bench.backends.find((b) => b.backend === m.backend);
        if (!entry) return fail('BAD_REQUEST', `unknown backend: ${m.backend}`);
        this.selected = entry.backend;
        return { data: { backend: entry.backend, devices: bench.devicesOf(entry) } };
      }
      case 'devices.list':
        return { data: { backends: bench.backends.map((b) => ({ backend: b.backend,
          devices: bench.devicesOf(b) })) } };
      case 'cards.list': return { data: { cards: bench.cards } };
      // Spec 4.6, the two lock-free reads a client makes the moment a bench analyzer becomes its
      // selection: the factory calibration and the ranges IN FORCE. Together they are what the
      // client renders into the device-provided card its Ranges table reads (B27), so a double
      // that answers neither cannot show that table at all.
      case 'qa40x.calibration': return { data: bench.qa40xCalibration };
      case 'qa40x.ranges': return { data: bench.qa40xRanges };
      case 'device.acquire': {
        const key = lockKey(m);
        const holder = bench.owners.get(key);
        if (holder != null && holder !== this) {
          return fail('DEVICE_LOCKED', 'device already in use', { by: holder.clientName });
        }
        bench.owners.set(key, this);       // a repeated acquire by the same session is granted
        this.locks.add(key);
        bench.broadcastDevicesChanged();
        return { data: {} };
      }
      case 'device.release': {
        const key = lockKey(m);
        this.closeEverythingOn(key);
        this.locks.delete(key);
        if (bench.owners.get(key) === this) bench.owners.delete(key);
        bench.broadcastDevicesChanged();
        return { data: {} };
      }
      case 'capture.open': {
        const key = lockKey(m);
        if (bench.owners.get(key) !== this) {
          return fail('NOT_LOCKED', `${key} must be acquired before it can stream`);
        }
        for (const held of this.captures.values()) {
          if (held === key) return fail('BAD_REQUEST', `a capture is already open on ${key}`);
        }
        const captureId = this._nextCapture++;
        this.captures.set(captureId, key);
        const device = bench.deviceOf(key);
        return { data: { captureId, rate: bench.grantedRate(device, m.rate),
          bits: m.bits || 24, channels: 2 } };
      }
      case 'capture.start':
      case 'capture.stop':
        return this.captureCommand(m);
      case 'capture.close': {
        const bad = this.captureCommand(m);
        if (bad.error) return bad;
        this.captures.delete(m.captureId);
        return { data: {} };
      }
      case 'gen.open': {
        const key = lockKey(m);
        if (bench.owners.get(key) !== this) {
          return fail('NOT_LOCKED', `${key} must be acquired before the generator can drive it`);
        }
        if (this.genId !== 0) {
          return fail('BAD_REQUEST',
            `a generator is already open on this connection (id ${this.genId})`);
        }
        this.genId = this._nextGen++;
        this.genLock = key;
        return { data: { genId: this.genId, rate: bench.grantedRate(bench.deviceOf(key), m.rate) } };
      }
      case 'gen.close': {
        const bad = this.genCommand(m);
        if (bad.error) return bad;
        this.genId = 0; this.genLock = null;
        return { data: {} };
      }
      default:
        if (String(m.t).startsWith('gen.')) return this.genCommand(m);
        return fail('UNSUPPORTED', `this bench does not answer ${m.t}`);
    }
  }

  captureCommand(m) {
    if (!this.captures.has(m.captureId)) {
      return fail('BAD_REQUEST', `no capture is open with id ${m.captureId}`);
    }
    return { data: {} };
  }

  genCommand(m) {
    if (this.genId === 0 || m.genId !== this.genId) {
      return fail('BAD_REQUEST', `no generator is open with id ${m.genId}`);
    }
    return { data: {} };
  }

  closeEverythingOn(key) {
    for (const [id, held] of [...this.captures]) if (held === key) this.captures.delete(id);
    if (this.genLock === key) { this.genId = 0; this.genLock = null; }
  }

  /** The session ended - every lock it held goes back, which is what spec 4.1 requires of a
   *  server when a connection dies. */
  dispose() {
    this.stopKeepalive();
    for (const key of this.locks) if (this.bench.owners.get(key) === this) this.bench.owners.delete(key);
    this.locks.clear();
    this.captures.clear();
    this.genId = 0;
  }
}

function fail(code, message, extra = {}) { return { error: { code, message, ...extra } }; }

function lockKey(m) { return `${m.backend}/${m.index}/${m.input ? 'in' : 'out'}`; }

/** One device row of spec 4.3, in the wire's own shape. */
function device({ index, name, input, formats }) {
  return { index, name, description: `${name} (bench)`, vendor: 'Bench Instruments',
    input, output: !input, hasBitDepth: true, formats, lock: null, cal: null, card: null };
}

const DEFAULT_FORMATS = [
  { rate: 44100, bits: 16, channels: 2 },
  { rate: 48000, bits: 24, channels: 2 },
  { rate: 96000, bits: 24, channels: 2 },
];

/**
 * The bench. `backends` is the offer; each carries its own device list and formats, so a test can
 * make two backends differ (and can REPLACE them between connections, which is what a reconnect
 * that must re-read the catalogue is about).
 */
export class BenchServer {
  constructor({ serverId = 'e2e-bench-1', name = 'E2E Bench', backends = null } = {}) {
    this.serverId = serverId;
    this.name = name;
    this.backends = backends || [{
      backend: 'JAVASOUND', displayName: 'Java Sound',
      devices: [
        device({ index: 0, name: 'Bench ADC', input: true, formats: DEFAULT_FORMATS }),
        device({ index: 0, name: 'Bench DAC', input: false, formats: DEFAULT_FORMATS }),
      ],
    }];
    /** The cards this bench's store offers (spec 4.3's cards.list) - the choices a remote device's
     *  card chooser is filled from. */
    this.cards = [{ name: 'Bench card', input: true, output: true }];
    /** The analyzer's factory calibration and the ranges it is running under (spec 4.6) - a
     *  QA403's five input positions, the middle one in force. */
    this.qa40xCalibration = { adc: [{ dbv: 0, left: 1.02, right: 0.98 }],
      dac: [{ dbv: -12, left: 1.01, right: 1.03 }] };
    this.qa40xRanges = { activeInputDbv: 12, activeOutputDbv: -12 };
    /** lock key -> the Session holding it (the server-wide register). */
    this.owners = new Map();
    this.sessions = new Set();
    /** Every refusal this bench sent, for the assertions. */
    this.refusals = [];
    /** Every backend.select this bench was asked for, in order - where a session is ROUTED is
     *  what decides whether it answers a client's reads at all (B16). */
    this.selects = [];
    /** Set to refuse every backend.select - a backend the server has but cannot run (B18). */
    this.refuseSelect = false;
    /** Every session this bench declared dead by the keepalive rule (B14). */
    this.kills = [];
    /** How often GET /info was answered - what proves a liveness round reached THIS bench, as
     *  opposed to being dropped before it was ever sent. */
    this.infoHits = 0;
    this.port = 0;
    this._server = null;
  }

  /** Starts listening on an ephemeral port; resolves once the port is known. */
  async start() {
    this._server = http.createServer((req, res) => this._http(req, res));
    this._server.on('upgrade', (req, socket) => this._upgrade(req, socket));
    await new Promise((resolve) => this._server.listen(0, '127.0.0.1', resolve));
    this.port = this._server.address().port;
    return this;
  }

  async stop() {
    for (const session of [...this.sessions]) { try { session.socket.destroy(); } catch { /* gone */ } }
    this.sessions.clear();
    await new Promise((resolve) => this._server.close(resolve));
  }

  get origin() { return `http://127.0.0.1:${this.port}`; }

  /** Replaces the offer - a bench whose hardware changed while nobody was connected. */
  setBackends(backends) { this.backends = backends; }

  devicesOf(entry) {
    return entry.devices.map((d) => {
      const holder = this.owners.get(`${entry.backend}/${d.index}/${d.input ? 'in' : 'out'}`);
      return { ...d, lock: holder == null ? null : { by: holder.clientName } };
    });
  }

  deviceOf(key) {
    const [backend, index, dir] = key.split('/');
    const entry = this.backends.find((b) => b.backend === backend);
    if (!entry) return null;
    return entry.devices.find((d) => String(d.index) === index && d.input === (dir === 'in')) || null;
  }

  /** Spec 4.4: "the granted rate may differ (device reality), client re-pins" - this bench grants
   *  what it has a format for, else its highest. */
  grantedRate(device, asked) {
    if (device == null) return asked || 48000;
    if (device.formats.some((f) => f.rate === asked)) return asked;
    return device.formats.reduce((best, f) => Math.max(best, f.rate), 0) || asked;
  }

  /** Spec 4.3: the lock overview moved, so every session is told. */
  broadcastDevicesChanged() {
    const payload = { t: 'ev.devices.changed',
      backends: this.backends.map((b) => ({ backend: b.backend, devices: this.devicesOf(b) })) };
    for (const session of this.sessions) session.send(payload);
  }

  // ---- HTTP: the app's files + the two discovery endpoints -----------------

  _http(req, res) {
    const urlPath = decodeURIComponent((req.url || '/').split('?')[0]);
    if (urlPath === '/info') {
      this.infoHits++;
      return json(res, { serverId: this.serverId, name: this.name, app: 'Phonalyser/1.1.1',
        proto: 1, port: this.port });
    }
    if (urlPath === '/servers') {
      return json(res, { servers: [{ serverId: this.serverId, name: this.name,
        host: '127.0.0.1', port: this.port, self: true }] });
    }
    const filePath = normalize(join(WEB_ROOT, urlPath === '/' ? '/index.html' : urlPath));
    if (!filePath.startsWith(WEB_ROOT)) { res.writeHead(403); res.end('forbidden'); return; }
    if (!existsSync(filePath) || !statSync(filePath).isFile()) {
      res.writeHead(404); res.end(`not found: ${urlPath}`); return;
    }
    res.writeHead(200, { 'Content-Type': MIME[extname(filePath).toLowerCase()] || 'application/octet-stream',
      'Cache-Control': 'no-store' });
    createReadStream(filePath).pipe(res);
  }

  // ---- The spec-4 control channel -----------------------------------------

  _upgrade(req, socket) {
    const key = req.headers['sec-websocket-key'];
    if (!key) { socket.destroy(); return; }
    socket.write('HTTP/1.1 101 Switching Protocols\r\n'
      + 'Upgrade: websocket\r\nConnection: Upgrade\r\n'
      + `Sec-WebSocket-Accept: ${createHash('sha1').update(key + WS_GUID).digest('base64')}\r\n\r\n`);
    socket.setNoDelay(true);
    const session = new Session(this, socket);
    this.sessions.add(session);
    let pending = Buffer.alloc(0);
    socket.on('data', (chunk) => {
      pending = Buffer.concat([pending, chunk]);
      const { frames, rest } = readFrames(pending);
      pending = rest;
      for (const frame of frames) {
        if (frame.opcode === 0x8) { socket.end(); return; }          // close
        if (frame.opcode !== 0x1) continue;                          // this app sends only text
        let message;
        try { message = JSON.parse(frame.payload.toString('utf8')); } catch { continue; }
        session.handle(message);
      }
    });
    const done = () => { session.dispose(); this.sessions.delete(session); };
    socket.on('close', done);
    socket.on('error', done);
  }
}

/**
 * A JSON answer with the CORS header the real server puts on EVERY http answer
 * (HttpFront.handle: Access-Control-Allow-Origin is set before anything is routed, spec 3's
 * "permissive by design" - the app is served by one server and may talk to another).
 *
 * Without it this double is not the server: a browser would refuse every cross-origin probe,
 * and a rig built on that would blame CORS for whatever it was actually looking at.
 */
function json(res, body) {
  const text = JSON.stringify(body);
  res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Cache-Control': 'no-store' });
  res.end(text);
}
