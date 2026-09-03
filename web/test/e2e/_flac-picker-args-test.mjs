// Static + unit check for the scope .flac save path (user retest #3: "flac
// impossible to save - dialog closed immediately, doesn't create any file").
// Temp (_-prefixed). Headless - native pickers can't be driven, so this test:
//
//  1. extracts the EXACT types descriptor the scope "Choose target" button hands
//     to showSaveFilePicker (app.js WAV_TYPE through file-picker.js
//     pickSaveTarget's mapping) and asserts it is a spec-valid
//     showSaveFilePicker input for a .flac target: well-formed MIME with a
//     leading type/subtype, extensions starting with '.' (no extra dots, ≤16
//     chars - Chromium throws TypeError past those), '.flac' present in the
//     accepted extensions, and a suggestedName that keeps its .flac suffix
//     (scopeSaveSuggestedName must not append '.wav' to it);
//  2. asserts the FLAC encode entries (encodeFlac + saveStreamingFlac) reject
//     NOTHING at the bit depth the scope save actually uses (24) while still
//     refusing 32-bit loudly, and that scope-tab-control routes FLAC saves at
//     24-bit in BOTH paths (ring dump + forward record);
//  3. drives the REAL forward-record FLAC path (saveStreamingFlac with the real
//     libFLAC WASM codec, live-fed ring, duration > ring) and round-trips the
//     result through decodeFlac - the exact scenario that used to kill the save
//     (openStreamingSink's FLAC throw closed the progress window instantly).

import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { existsSync, mkdirSync, copyFileSync, readFileSync } from 'node:fs';
import { SignalBuffer } from '../../js/audio/signal-buffer.js';
import { SignalBufferReader } from '../../js/audio/signal-buffer-reader.js';

let fails = 0;
const ok = (cond, msg) => { if (!cond) { console.log('FAIL: ' + msg); fails++; } else { console.log('ok: ' + msg); } };

const __dirname = dirname(fileURLToPath(import.meta.url));
const WEB = join(__dirname, '..', '..');

// flac.js reads window.Flac lazily per call - an empty window yields the
// deterministic "codec unavailable" rejection for the guard-order checks below;
// section 3 installs the real codec into it.
globalThis.window = {};
const { encodeFlac, saveStreamingFlac, decodeFlac } = await import('../../js/io/flac.js');

// ── 1. The exact showSaveFilePicker argument for a .flac target ──────────────
const appSrc = readFileSync(join(WEB, 'js', 'shell', 'app.js'), 'utf8');
const wavTypeMatch = appSrc.match(/const WAV_TYPE = (\[[\s\S]*?\]);/);
ok(!!wavTypeMatch, 'app.js defines WAV_TYPE (the shared save-picker accept descriptor)');
const WAV_TYPE = wavTypeMatch ? new Function('return ' + wavTypeMatch[1] + ';')() : [];

// suggestedName: scopeSaveSuggestedName only appends '.wav' when the name has no
// known audio extension - extract its regex and prove 'scope.flac' passes intact.
const tabSrc = readFileSync(join(WEB, 'js', 'scope', 'scope-tab-control.js'), 'utf8');
const sugMatch = tabSrc.match(/if \(!(\/.+?\/i)\.test\(name\)\) name \+= '\.wav';/);
ok(!!sugMatch, 'scope-tab-control has the suggested-name extension check');
if (sugMatch) {
  const extRe = new Function('return ' + sugMatch[1] + ';')();
  ok(extRe.test('scope.flac'), "scopeSaveSuggestedName keeps '.flac' (no '.wav' appended)");
}
const suggestedName = 'scope.flac';

// Reproduce file-picker.js pickSaveTarget's exact mapping (lines building the
// showSaveFilePicker call): {suggestedName, types: [{description, accept: {mime: exts}}]}.
const pickerArg = {
  suggestedName,
  types: WAV_TYPE.map((t) => ({
    description: t.description,
    accept: { [t.accept]: t.extensions },
  })),
};

// Spec/Chromium validity of the picker argument (Chromium throws TypeError on a
// malformed MIME or extension BEFORE/AS the dialog opens - "closed immediately").
ok(pickerArg.suggestedName.toLowerCase().endsWith('.flac'), 'suggestedName ends with .flac');
ok(Array.isArray(pickerArg.types) && pickerArg.types.length > 0, 'types is a non-empty array');
const MIME_RE = /^[a-zA-Z0-9!#$&^_.+-]+\/[a-zA-Z0-9!#$&^_.+-]+$/;   // type "/" subtype, no params
const EXT_RE = /^\.[a-zA-Z0-9]+$/;                                   // '.' + alnum, no 2nd dot
const allExts = [];
for (const ty of pickerArg.types) {
  ok(typeof ty.description === 'string', `description is a string ('${ty.description}')`);
  const mimes = Object.keys(ty.accept);
  ok(mimes.length > 0, 'accept has at least one MIME key');
  for (const mime of mimes) {
    ok(MIME_RE.test(mime), `MIME '${mime}' is well-formed type/subtype`);
    ok(!mime.includes('*'), `MIME '${mime}' has no wildcard (Chromium save picker rejects them)`);
    const exts = ty.accept[mime];
    ok(Array.isArray(exts) && exts.length > 0, `extensions for '${mime}' is a non-empty array`);
    for (const ext of exts) {
      ok(EXT_RE.test(ext), `extension '${ext}' starts with '.' and has no extra dot/separator`);
      ok(ext.length <= 16, `extension '${ext}' is within Chromium's 16-char limit`);
      allExts.push(ext.toLowerCase());
    }
  }
}
ok(allExts.includes('.flac'),
  "'.flac' is in the accepted extensions (picker filter neither hides nor renames the target)");

// ── 2. FLAC encode entries vs the scope-save bit depth ───────────────────────
// The scope must route FLAC at 24-bit in BOTH paths (ring dump + forward record).
ok(/const FLAC_BIT_DEPTH = 24;/.test(tabSrc), 'scope-tab-control: FLAC_BIT_DEPTH === 24');
ok(/const flacBits = FLAC_BIT_DEPTH;/.test(tabSrc),
  'ring-dump path (encodeScopeSave) encodes FLAC at FLAC_BIT_DEPTH');
ok(/=== 'FLAC'\s*\n?\s*\? FLAC_BIT_DEPTH : PCM_BIT_DEPTH/.test(tabSrc),
  'Save handler derives the bit depth from the target format (FLAC -> 24)');

// encodeFlac guard order: bit-depth check fires BEFORE the codec loads, so with
// no codec the rejection reason tells us which gate tripped.
const rejectionOf = async (p) => { try { await p; return null; } catch (e) { return e.message; } };
const tiny = [new Float32Array(8), new Float32Array(8)];
const m32 = await rejectionOf(encodeFlac(tiny, 48000, 32));
ok(m32 != null && m32.includes('16 or 24 bits'), `encodeFlac REJECTS 32-bit loudly (got: ${m32})`);
const m24 = await rejectionOf(encodeFlac(tiny, 48000, 24));
ok(m24 == null || !m24.includes('bits'),
  `encodeFlac accepts 24-bit - only the (absent) codec rejects (got: ${m24})`);

const dummyReader = { seekToLatest() {}, read() { return 0; } };
const s32 = await rejectionOf(saveStreamingFlac(dummyReader, null, 48000, 32, 1, () => true, null));
ok(s32 != null && s32.includes('16 or 24 bits'), `saveStreamingFlac REJECTS 32-bit loudly (got: ${s32})`);
const s24 = await rejectionOf(saveStreamingFlac(dummyReader, null, 48000, 24, 1, () => true, null));
ok(s24 == null || !s24.includes('bits'),
  `saveStreamingFlac accepts 24-bit - only the (absent) codec rejects (got: ${s24})`);

// ── 3. Forward-record FLAC round-trip with the REAL WASM codec ───────────────
// Stage the CommonJS dev build of libFLAC (same scheme as _scope-save-smoke.mjs).
{
  const require = createRequire(import.meta.url);
  const tmp = join(__dirname, '_flactmp');
  if (!existsSync(tmp)) mkdirSync(tmp, { recursive: true });
  copyFileSync(join(WEB, 'vendor', 'libflac', 'libflac.dev.wasm.js'), join(tmp, 'libflac.cjs'));
  copyFileSync(join(WEB, 'vendor', 'libflac', 'libflac.dev.wasm.wasm'), join(tmp, 'libflac.dev.wasm.wasm'));
  process.env.FLAC_SCRIPT_LOCATION = tmp + '/';
  delete globalThis.fetch;   // force the emscripten loader onto its Node file path
  const Flac = require(join(tmp, 'libflac.cjs'));
  await new Promise((resolve) => { if (Flac.isReady()) resolve(); else Flac.on('ready', resolve); });
  globalThis.window.Flac = Flac;

  const RATE = 48000, FREQ = 1000, FLAC_BITS = 24;
  const RING_SECONDS = 1, RECORD_SECONDS = 2;             // forward record outruns the ring
  const totalFrames = RECORD_SECONDS * RATE;
  const buffer = new SignalBuffer(RATE, RING_SECONDS);
  const reader = new SignalBufferReader(buffer);
  let phase = 0;
  const FEED_CHUNK = 2048;
  const feed = () => {
    const l = new Float64Array(FEED_CHUNK), r = new Float64Array(FEED_CHUNK);
    for (let i = 0; i < FEED_CHUNK; i++) {
      const s = Math.sin(2 * Math.PI * FREQ * (phase + i) / RATE) * 0.5;
      l[i] = s; r[i] = s;
    }
    phase += FEED_CHUNK;
    buffer.appendBatch(l, r, FEED_CHUNK);
  };
  feed();
  const feeder = setInterval(feed, 10);

  // Append-only mock FileSystemWritableFileStream (the FLAC encoder frames its
  // own container - no positioned header patches).
  const chunks = [];
  let closed = false;
  const writable = {
    async write(u8) { if (closed) throw new Error('write after close'); chunks.push(Uint8Array.from(u8)); },
    async close() { closed = true; },
  };

  let progressSeen = 0, lastProgress = 0;
  const written = await saveStreamingFlac(reader, writable, RATE, FLAC_BITS, totalFrames,
    () => false, (n) => { progressSeen++; lastProgress = n; });
  clearInterval(feeder);

  ok(written === totalFrames, `streaming FLAC wrote all ${totalFrames} frames (got ${written})`);
  ok(closed, 'streaming FLAC closed the writable');
  ok(progressSeen > 0 && lastProgress === written, 'progress callback fired and matched written');

  let total = 0;
  for (const c of chunks) total += c.length;
  const bytes = new Uint8Array(total);
  let o = 0;
  for (const c of chunks) { bytes.set(c, o); o += c.length; }
  ok(bytes.length > 0, `streaming FLAC file is NON-EMPTY (${bytes.length} bytes)`);
  const magic = bytes.length >= 4
    ? String.fromCharCode(bytes[0], bytes[1], bytes[2], bytes[3]) : '';
  ok(magic === 'fLaC', `streaming FLAC starts with the 'fLaC' magic (got '${magic}')`);

  const dec = await decodeFlac(bytes);
  ok(dec.sampleRate === RATE, `decoded sampleRate == ${RATE} (got ${dec.sampleRate})`);
  ok(dec.channels === 2, `decoded channels == 2 (got ${dec.channels})`);
  ok(dec.bitsPerSample === FLAC_BITS, `decoded bitsPerSample == ${FLAC_BITS} (got ${dec.bitsPerSample})`);
  ok(dec.ch0.length === totalFrames, `decoded frame count == ${totalFrames} (got ${dec.ch0.length})`);
  const FS = Math.pow(2, FLAC_BITS - 1);
  let peak = 0, nonZero = 0;
  for (let i = 0; i < dec.ch0.length; i++) {
    const a = Math.abs(dec.ch0[i]);
    if (a > peak) peak = a;
    if (a > 0) nonZero++;
  }
  ok(nonZero > dec.ch0.length * 0.9, `decoded signal is non-zero (${nonZero}/${dec.ch0.length})`);
  ok(peak / FS > 0.45 && peak / FS <= 0.51, `decoded peak ≈ 0.5 (got ${(peak / FS).toFixed(4)})`);
}

console.log(fails === 0 ? 'flac-picker-args: ALL PASSED' : `flac-picker-args: ${fails} FAILED`);
process.exit(fails === 0 ? 0 : 1);
