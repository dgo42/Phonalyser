// Smoke test for the scope stream-forward record (StereoPcmIo.saveStreaming port).
// Temp (_-prefixed). Headless, no browser: drives the REAL SignalBuffer +
// SignalBufferReader + io/scope-capture.saveStreaming against a mock
// FileSystemWritableFileStream that captures the written bytes, then asserts the
// result is a VALID WAV (RIFF/WAVE/fmt/data, correct frame count) that round-trips
// through the app's readWav decoder.
//
// This is the gate that must PASS before the forward-record path is re-enabled in
// scope-tab-control: it previously wrote corrupt/short files. It exercises the
// 44-byte header, the streamed PCM chunks, and the close() RIFF/data size patches.
//
// A "live" capture is simulated by a feeder timer that appends a 1 kHz sine to the
// ring on an interval, exactly as the device capture handler would - so the forward
// reader.read() consumes fresh samples and saveStreaming's "await fresh samples"
// (n<=0 -> sleep 20 ms) branch is genuinely exercised.

import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { existsSync, mkdirSync, copyFileSync } from 'node:fs';
import { SignalBuffer } from '../../js/audio/signal-buffer.js';
import { SignalBufferReader } from '../../js/audio/signal-buffer-reader.js';
import { saveStreaming } from '../../js/io/scope-capture.js';
import { readWav, readAiff } from '../../js/io/wav.js';

let fails = 0;
const ok = (cond, msg) => { if (!cond) { console.log('FAIL: ' + msg); fails++; } else { console.log('ok: ' + msg); } };

// ── Mock FileSystemWritableFileStream ────────────────────────────────────────
// Models the two write forms the streaming writers use:
//   write(BufferSource)                         -> append at the current cursor
//   write({type:'write', position, data})       -> positioned write (size patches)
// and close(). Backed by a growable byte array; tracks the cursor like the real
// stream so a sequential write after the header lands right after it, and the
// close() positioned patches overwrite the placeholder size fields in place.
class MockWritable {
  constructor() { this.bytes = []; this.cursor = 0; this.closed = false; }
  _ensure(end) { while (this.bytes.length < end) this.bytes.push(0); }
  _put(at, data) {
    const u8 = data instanceof Uint8Array ? data
      : data instanceof ArrayBuffer ? new Uint8Array(data)
      : new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
    this._ensure(at + u8.length);
    for (let i = 0; i < u8.length; i++) this.bytes[at + i] = u8[i];
    return at + u8.length;
  }
  async write(arg) {
    if (this.closed) throw new Error('write after close');
    if (arg && typeof arg === 'object' && arg.type === 'write') {
      // positioned write - does NOT move the append cursor.
      this._put(arg.position, arg.data);
      return;
    }
    this.cursor = this._put(this.cursor, arg);
  }
  async close() { this.closed = true; }
  toUint8Array() { return Uint8Array.from(this.bytes); }
}

// ── Build a live-ish ring fed with a 1 kHz sine ──────────────────────────────
const RATE = 48000;
const RING_SECONDS = 1;                 // small ring so the forward read outruns it
const FREQ = 1000;
const buffer = new SignalBuffer(RATE, RING_SECONDS);
const reader = new SignalBufferReader(buffer);

let phase = 0;                          // total samples appended so far (for the sine)
const FEED_CHUNK = 2048;                // ~42 ms per feed
function feed() {
  const l = new Float64Array(FEED_CHUNK), r = new Float64Array(FEED_CHUNK);
  for (let i = 0; i < FEED_CHUNK; i++) {
    const s = Math.sin(2 * Math.PI * FREQ * (phase + i) / RATE) * 0.5;
    l[i] = s; r[i] = s;
  }
  phase += FEED_CHUNK;
  buffer.appendBatch(l, r, FEED_CHUNK);
}
// Prime a little so the first read has data, then feed on a timer.
feed();
const feeder = setInterval(feed, 10);

// ── Drive the real saveStreaming for ~2 s, longer than the ring ──────────────
const RECORD_SECONDS = 2;
const totalFrames = RECORD_SECONDS * RATE;   // 96000 > ring capacity (48000)
const BIT_DEPTH = 32;

const writable = new MockWritable();
let progressSeen = 0, lastProgress = 0;

const written = await saveStreaming(
  reader, writable, RATE, BIT_DEPTH, totalFrames, 'smoke.wav',
  () => false,                                  // never cancel
  (n) => { progressSeen++; lastProgress = n; });

clearInterval(feeder);

// ── Assertions ───────────────────────────────────────────────────────────────
ok(written === totalFrames, `wrote all ${totalFrames} frames (got ${written})`);
ok(writable.closed, 'writable was closed (size patches flushed)');
ok(progressSeen > 0 && lastProgress === written, 'progress callback fired and matched written');

const fileBytes = writable.toUint8Array();
const bytesPerFrame = (BIT_DEPTH / 8) * 2;     // 32-bit, stereo
const expectedBytes = 44 + totalFrames * bytesPerFrame;
ok(fileBytes.length === expectedBytes,
  `file is header(44) + data: expected ${expectedBytes}, got ${fileBytes.length}`);

// Raw header field checks (independent of readWav) - the 44-byte WAV header.
const dv = new DataView(fileBytes.buffer, fileBytes.byteOffset, fileBytes.byteLength);
const tag = (o) => String.fromCharCode(fileBytes[o], fileBytes[o + 1], fileBytes[o + 2], fileBytes[o + 3]);
ok(tag(0) === 'RIFF', "tag@0 == 'RIFF'");
ok(dv.getUint32(4, true) === 36 + totalFrames * bytesPerFrame, 'RIFF size patched = 36 + dataBytes');
ok(tag(8) === 'WAVE', "tag@8 == 'WAVE'");
ok(tag(12) === 'fmt ', "tag@12 == 'fmt '");
ok(dv.getUint32(16, true) === 16, 'fmt chunk size == 16');
ok(dv.getUint16(20, true) === 1, 'audioFormat == 1 (PCM)');
ok(dv.getUint16(22, true) === 2, 'channels == 2');
ok(dv.getUint32(24, true) === RATE, `sampleRate == ${RATE}`);
ok(dv.getUint32(28, true) === RATE * bytesPerFrame, 'byteRate == rate * blockAlign');
ok(dv.getUint16(32, true) === bytesPerFrame, 'blockAlign == bytesPerFrame');
ok(dv.getUint16(34, true) === BIT_DEPTH, `bitsPerSample == ${BIT_DEPTH}`);
ok(tag(36) === 'data', "tag@36 == 'data'");
ok(dv.getUint32(40, true) === totalFrames * bytesPerFrame, 'data size patched = dataBytes');

// Decode through the app's own readWav and verify it round-trips.
let dec = null;
try { dec = readWav(fileBytes); } catch (e) { ok(false, 'readWav threw: ' + e.message); }
if (dec) {
  ok(dec.sampleRate === RATE, `readWav sampleRate == ${RATE}`);
  ok(dec.channels === 2, 'readWav channels == 2');
  ok(dec.bitsPerSample === BIT_DEPTH, `readWav bitsPerSample == ${BIT_DEPTH}`);
  ok(dec.frameCount === totalFrames, `readWav frameCount == ${totalFrames} (got ${dec.frameCount})`);
  // The decoded signal is a 1 kHz sine at amplitude 0.5: peak |sample| / 2^31 ≈ 0.5,
  // and it must not be all-zero (corrupt) nor clipped.
  const FS = Math.pow(2, BIT_DEPTH - 1);
  let peak = 0, nonZero = 0;
  for (let i = 0; i < dec.frameCount; i++) {
    const a = Math.abs(dec.ch0[i]);
    if (a > peak) peak = a;
    if (a > 0) nonZero++;
  }
  const peakNorm = peak / FS;
  ok(nonZero > dec.frameCount * 0.9, `decoded signal is non-zero (${nonZero}/${dec.frameCount})`);
  ok(peakNorm > 0.45 && peakNorm <= 0.51, `decoded peak ≈ 0.5 (got ${peakNorm.toFixed(4)})`);
}

// ── AIFF variant - exercises StreamingAiffWriter (FORM/COMM/SSND patches) ─────
// Same forward-record path, but the '.aiff' extension routes to the streaming AIFF
// writer (big-endian PCM, 80-bit extended sample rate, three close() size patches).
{
  const buf2 = new SignalBuffer(RATE, RING_SECONDS);
  const rdr2 = new SignalBufferReader(buf2);
  let ph2 = 0;
  const feed2 = () => {
    const l = new Float64Array(FEED_CHUNK), r = new Float64Array(FEED_CHUNK);
    for (let i = 0; i < FEED_CHUNK; i++) {
      const s = Math.sin(2 * Math.PI * FREQ * (ph2 + i) / RATE) * 0.5;
      l[i] = s; r[i] = s;
    }
    ph2 += FEED_CHUNK;
    buf2.appendBatch(l, r, FEED_CHUNK);
  };
  feed2();
  const feeder2 = setInterval(feed2, 10);
  const w2 = new MockWritable();
  const written2 = await saveStreaming(
    rdr2, w2, RATE, BIT_DEPTH, totalFrames, 'smoke.aiff', () => false, () => {});
  clearInterval(feeder2);

  ok(written2 === totalFrames, `AIFF wrote all ${totalFrames} frames`);
  const ab = w2.toUint8Array();
  const adv = new DataView(ab.buffer, ab.byteOffset, ab.byteLength);
  const atag = (o) => String.fromCharCode(ab[o], ab[o + 1], ab[o + 2], ab[o + 3]);
  const dataBytes = totalFrames * bytesPerFrame;
  ok(atag(0) === 'FORM', "AIFF tag@0 == 'FORM'");
  ok(adv.getInt32(4, false) === (4 + 8 + 18 + 8 + 8 + dataBytes), 'AIFF FORM size patched');
  ok(atag(8) === 'AIFF', "AIFF tag@8 == 'AIFF'");
  ok(atag(12) === 'COMM', "AIFF tag@12 == 'COMM'");
  ok(adv.getInt32(22, false) === totalFrames, 'AIFF COMM numFrames patched');
  ok(atag(38) === 'SSND', "AIFF tag@38 == 'SSND'");
  ok(adv.getInt32(42, false) === (8 + dataBytes), 'AIFF SSND size patched');

  let adec = null;
  try { adec = readAiff(ab); } catch (e) { ok(false, 'readAiff threw: ' + e.message); }
  if (adec) {
    ok(adec.sampleRate === RATE, `readAiff sampleRate == ${RATE}`);
    ok(adec.frameCount === totalFrames, `readAiff frameCount == ${totalFrames}`);
    const FS = Math.pow(2, BIT_DEPTH - 1);
    let peak = 0;
    for (let i = 0; i < adec.frameCount; i++) { const a = Math.abs(adec.ch0[i]); if (a > peak) peak = a; }
    ok(peak / FS > 0.45 && peak / FS <= 0.51, `AIFF decoded peak ≈ 0.5 (got ${(peak / FS).toFixed(4)})`);
  }
}

// ── FLAC one-shot encode/decode (io/flac.js via the REAL libFLAC WASM codec) ──
// The scope FLAC save routes through encodeFlac; the bug was a 0-byte .flac. This
// loads the actual WASM codec under Node (the dev CommonJS build), encodes a stereo
// sine at 24-bit - the max the WASM reference encoder accepts (32-bit -> 0 bytes) -
// asserts the file is non-empty with the 'fLaC' magic + correct header, and round-
// trips it back through decodeFlac.
{
  const require = createRequire(import.meta.url);
  const __dirname = dirname(fileURLToPath(import.meta.url));
  const WEB = join(__dirname, '..', '..');
  // package.json is type:module, so require() of a .js file fails as ESM - stage a
  // .cjs copy of the WASM build (+ its .wasm binary) beside this test and load that.
  const tmp = join(__dirname, '_flactmp');
  if (!existsSync(tmp)) mkdirSync(tmp, { recursive: true });
  const srcJs = join(WEB, 'vendor', 'libflac', 'libflac.dev.wasm.js');
  const srcWasm = join(WEB, 'vendor', 'libflac', 'libflac.dev.wasm.wasm');
  copyFileSync(srcJs, join(tmp, 'libflac.cjs'));
  copyFileSync(srcWasm, join(tmp, 'libflac.dev.wasm.wasm'));
  process.env.FLAC_SCRIPT_LOCATION = tmp + '/';
  // Node 24 defines a global fetch the emscripten loader would try (and fail) on a
  // bare file path - remove it so the loader takes its Node binary-read path.
  delete globalThis.fetch;
  const Flac = require(join(tmp, 'libflac.cjs'));
  await new Promise((resolve) => {
    if (Flac.isReady()) resolve(); else Flac.on('ready', () => resolve());
  });
  globalThis.window = { Flac };
  const { encodeFlac, decodeFlac } = await import('../../js/io/flac.js');

  const FLAC_BITS = 24;   // 32-bit is rejected by the WASM encoder -> 0-byte file
  const N = RATE / 10;    // 0.1 s
  const l = new Float32Array(N), r = new Float32Array(N);
  for (let i = 0; i < N; i++) { const s = Math.sin(2 * Math.PI * FREQ * i / RATE) * 0.5; l[i] = s; r[i] = s; }

  const flacBytes = await encodeFlac([l, r], RATE, FLAC_BITS);
  ok(flacBytes && flacBytes.length > 0, `FLAC encode is NON-EMPTY (got ${flacBytes ? flacBytes.length : 0} bytes)`);
  const magic = flacBytes && flacBytes.length >= 4
    ? String.fromCharCode(flacBytes[0], flacBytes[1], flacBytes[2], flacBytes[3]) : '';
  ok(magic === 'fLaC', `FLAC file starts with the 'fLaC' magic (got '${magic}')`);

  const fdec = await decodeFlac(flacBytes);
  ok(fdec.sampleRate === RATE, `FLAC decoded sampleRate == ${RATE} (got ${fdec.sampleRate})`);
  ok(fdec.channels === 2, `FLAC decoded channels == 2 (got ${fdec.channels})`);
  ok(fdec.bitsPerSample === FLAC_BITS, `FLAC decoded bitsPerSample == ${FLAC_BITS} (got ${fdec.bitsPerSample})`);
  ok(fdec.ch0.length === N, `FLAC decoded frame count == ${N} (got ${fdec.ch0.length})`);
  const FFS = Math.pow(2, FLAC_BITS - 1);
  let fpeak = 0;
  for (let i = 0; i < fdec.ch0.length; i++) { const a = Math.abs(fdec.ch0[i]); if (a > fpeak) fpeak = a; }
  ok(fpeak / FFS > 0.45 && fpeak / FFS <= 0.51, `FLAC decoded peak ≈ 0.5 (got ${(fpeak / FFS).toFixed(4)})`);
}

console.log(fails === 0 ? 'scope-save-smoke: ALL PASSED' : `scope-save-smoke: ${fails} FAILED`);
process.exit(fails === 0 ? 0 : 1);
