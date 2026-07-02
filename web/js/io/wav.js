/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.wav.WavReader and WavWriter, plus
// the AIFF writer (org.edgo.audio.measure.wav.AiffWriter) and the FLAC writer
// stub (org.edgo.audio.measure.wav.FlacWriter).
//
// Java mapped RandomAccessFile/ByteBuffer onto a growable ArrayBuffer here: the
// writers buffer into a JS array of Uint8Array chunks, then assemble the final
// blob with the header finalised exactly as the Java writeHeader() does. The
// byte layouts (RIFF/WAVE little-endian PCM or IEEE-float, FORM/AIFF big-endian
// PCM with an 80-bit extended sample rate) are preserved bit-for-bit.

// ─────────────────────────────────────────────────────────────────────────────
// WAV reader
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Decoded WAV header + bulk samples.
 *
 * Samples are delivered as SIGNED integers (two's-complement, native PCM range)
 * — matching StereoPcmIo.decodeStereo's readSample (the scope load path divides
 * by 2^(bits-1) to get float [-1,+1]). For mono files ch1 mirrors ch0.
 *
 * @typedef {Object} WavReadResult
 * @property {number}       sampleRate     Hz.
 * @property {number}       channels       Channel count from the fmt chunk.
 * @property {number}       bitsPerSample  8 / 16 / 24 / 32.
 * @property {number}       frameCount     dataBytes / (channels * bytesPerSample).
 * @property {Int32Array}   ch0            Signed samples, channel 0.
 * @property {Int32Array}   ch1            Signed samples, channel 1 (= ch0 if mono).
 */

const tag4 = (dv, off) =>
  String.fromCharCode(dv.getUint8(off), dv.getUint8(off + 1), dv.getUint8(off + 2), dv.getUint8(off + 3));

/**
 * Reads one signed little-endian PCM sample, matching
 * StereoPcmIo.readSample (signed branch): WAV 8-bit is unsigned offset-128, all
 * wider depths are signed two's-complement.
 * @param {DataView} dv
 * @param {number} off  byte offset
 * @param {number} bytes  1 / 2 / 3 / 4
 * @returns {number}  signed sample value
 */
function readSample(dv, off, bytes) {
  switch (bytes) {
    case 1:
      // WAV 8-bit is unsigned (midpoint 128); to signed: (u & 0xFF) - 128.
      return (dv.getUint8(off) & 0xff) - 128;
    case 2:
      return dv.getInt16(off, true);
    case 3: {
      const b0 = dv.getUint8(off), b1 = dv.getUint8(off + 1), b2 = dv.getInt8(off + 2);
      return (b2 << 16) | (b1 << 8) | b0;   // b2 signed → sign-extended 24-bit
    }
    case 4:
      return dv.getInt32(off, true);
    default:
      throw new Error('Unsupported bytes per sample: ' + bytes);
  }
}

/**
 * Parses a PCM WAV file (format code 1) from raw bytes. Supports 8/16/24/32-bit
 * little-endian signed PCM. Faithful port of WavReader (header scan + decode).
 *
 * @param {ArrayBuffer|Uint8Array} input  Raw file bytes.
 * @returns {WavReadResult}
 * @throws {Error} On a missing/invalid RIFF/WAVE/fmt/data chunk or non-PCM format.
 */
export function readWav(input) {
  const u8 = input instanceof Uint8Array ? input : new Uint8Array(input);
  const dv = new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
  const len = u8.byteLength;

  if (tag4(dv, 0) !== 'RIFF') throw new Error("Expected 'RIFF' but got '" + tag4(dv, 0) + "'");
  // bytes 4..7 = file size - 8 (ignored)
  if (tag4(dv, 8) !== 'WAVE') throw new Error("Expected 'WAVE' but got '" + tag4(dv, 8) + "'");

  let sampleRate = 0, channels = 0, bits = 0;
  let dataOffset = 0, dataSize = 0;

  let p = 12;
  while (p < len - 8) {
    const t = tag4(dv, p);
    const chunkSize = dv.getUint32(p + 4, true) >>> 0;
    p += 8;
    if (t === 'fmt ') {
      const audioFormat = dv.getUint16(p, true);
      if (audioFormat !== 1) {
        throw new Error('Only PCM (format 1) WAV files are supported, got: ' + audioFormat);
      }
      channels = dv.getUint16(p + 2, true);
      sampleRate = dv.getUint32(p + 4, true);
      // p+8 byteRate, p+12 blockAlign
      bits = dv.getUint16(p + 14, true);
      p += chunkSize;
    } else if (t === 'data') {
      dataOffset = p;
      dataSize = chunkSize;
      break;
    } else {
      p += chunkSize;
    }
  }

  if (bits === 0) throw new Error('fmt  chunk not found');
  if (dataOffset === 0) throw new Error('data chunk not found');

  const sampleBytes = bits / 8;
  const frameBytes = sampleBytes * channels;
  const frameCount = Math.floor(dataSize / frameBytes);
  const ch0 = new Int32Array(frameCount);
  const ch1 = new Int32Array(frameCount);
  for (let f = 0; f < frameCount; f++) {
    const base = dataOffset + f * frameBytes;
    const a = readSample(dv, base, sampleBytes);
    ch0[f] = a;
    ch1[f] = channels >= 2 ? readSample(dv, base + sampleBytes, sampleBytes) : a;
  }

  return { sampleRate, channels, bitsPerSample: bits, frameCount, ch0, ch1 };
}

// ─────────────────────────────────────────────────────────────────────────────
// AIFF / AIFF-C reader
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Decodes a 10-byte IEEE 754 80-bit extended-precision value (the AIFF COMM
 * sample rate). Inverse of {@link toExtended80}; only the non-negative range
 * AIFF uses is handled.
 * @param {DataView} dv
 * @param {number} off
 * @returns {number}
 */
function fromExtended80(dv, off) {
  const hi = dv.getUint16(off, false);
  const sign = hi & 0x8000 ? -1 : 1;
  const expon = hi & 0x7fff;
  // 64-bit mantissa (integer bit included) assembled with BigInt for exactness.
  let mant = 0n;
  for (let i = 0; i < 8; i++) mant = (mant << 8n) | BigInt(dv.getUint8(off + 2 + i));
  if (expon === 0 && mant === 0n) return 0;
  // value = mantissa * 2^(expon - 16383 - 63)
  const mantNum = Number(mant) / Math.pow(2, 63);
  return sign * mantNum * Math.pow(2, expon - 16383);
}

/**
 * Decoded AIFF/AIFF-C header + bulk samples. Samples are delivered as SIGNED
 * integers (two's-complement, native PCM range) — matching what the scope/load
 * callers expect (they normalise by 2^(bits-1)).
 *
 * @typedef {Object} AiffReadResult
 * @property {number}     sampleRate
 * @property {number}     channels
 * @property {number}     bitsPerSample
 * @property {number}     frameCount
 * @property {Int32Array} ch0
 * @property {Int32Array} ch1            (= ch0 if mono)
 */

/**
 * Reads a PCM AIFF or AIFF-C file. Parses FORM/AIFF|AIFC, the COMM chunk (channel
 * count, frame count, bit depth, 80-bit extended sample rate) and SSND (offset +
 * block-size header, then big-endian PCM). AIFF-C compression NONE is big-endian;
 * sowt is little-endian — both decoded to signed samples.
 *
 * @param {ArrayBuffer|Uint8Array} input  Raw file bytes.
 * @returns {AiffReadResult}
 * @throws {Error} On a missing/invalid FORM/AIFF/COMM/SSND chunk or non-PCM data.
 */
export function readAiff(input) {
  const u8 = input instanceof Uint8Array ? input : new Uint8Array(input);
  const dv = new DataView(u8.buffer, u8.byteOffset, u8.byteLength);
  const len = u8.byteLength;

  if (tag4(dv, 0) !== 'FORM') throw new Error("Expected 'FORM' but got '" + tag4(dv, 0) + "'");
  const formType = tag4(dv, 8);
  if (formType !== 'AIFF' && formType !== 'AIFC') {
    throw new Error("Expected 'AIFF' or 'AIFC' but got '" + formType + "'");
  }

  let channels = 0, frameCount = 0, bits = 0, sampleRate = 0;
  let littleEndian = false;
  let ssndOffset = 0, ssndDataBytes = 0;

  let p = 12;
  while (p + 8 <= len) {
    const t = tag4(dv, p);
    const chunkSize = dv.getInt32(p + 4, false) >>> 0;
    const body = p + 8;
    if (t === 'COMM') {
      channels = dv.getInt16(body, false);
      frameCount = dv.getUint32(body + 2, false) >>> 0;
      bits = dv.getInt16(body + 6, false);
      sampleRate = Math.round(fromExtended80(dv, body + 8));
      // AIFF-C: a 4-byte compression type follows the extended rate (offset 18).
      if (formType === 'AIFC' && chunkSize >= 22) {
        const comp = tag4(dv, body + 18);
        if (comp === 'sowt' || comp === 'SOWT') littleEndian = true;
        else if (comp !== 'NONE') throw new Error('Unsupported AIFF-C compression: ' + comp);
      }
    } else if (t === 'SSND') {
      const dataOffset = dv.getUint32(body, false) >>> 0;     // skip bytes before frames
      // body+4 = blockSize (alignment, ignored)
      ssndOffset = body + 8 + dataOffset;
      ssndDataBytes = chunkSize - 8 - dataOffset;
    }
    // Chunks are word-aligned: pad to an even byte count.
    p = body + chunkSize + (chunkSize & 1);
  }

  if (bits === 0) throw new Error('COMM chunk not found');
  if (ssndOffset === 0) throw new Error('SSND chunk not found');

  const sampleBytes = bits / 8;
  const frameBytes = sampleBytes * channels;
  const available = Math.floor(ssndDataBytes / frameBytes);
  const n = Math.min(frameCount, available);
  const ch0 = new Int32Array(n);
  const ch1 = new Int32Array(n);
  for (let f = 0; f < n; f++) {
    const base = ssndOffset + f * frameBytes;
    const a = readAiffSample(dv, base, sampleBytes, littleEndian);
    ch0[f] = a;
    ch1[f] = channels >= 2 ? readAiffSample(dv, base + sampleBytes, sampleBytes, littleEndian) : a;
  }

  return { sampleRate, channels, bitsPerSample: bits, frameCount: n, ch0, ch1 };
}

/** Reads one signed AIFF PCM sample (big-endian by default; sowt → little-endian). */
function readAiffSample(dv, off, bytes, le) {
  switch (bytes) {
    case 1:
      return dv.getInt8(off);
    case 2:
      return dv.getInt16(off, le);
    case 3: {
      const b0 = dv.getUint8(off), b1 = dv.getUint8(off + 1), b2 = dv.getUint8(off + 2);
      const v = le ? (b2 << 16) | (b1 << 8) | b0 : (b0 << 16) | (b1 << 8) | b2;
      return (v << 8) >> 8; // sign-extend 24-bit
    }
    case 4:
      return dv.getInt32(off, le);
    default:
      throw new Error('Unsupported bytes per sample: ' + bytes);
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Byte sink — mirrors RandomAccessFile growth without a fixed length.
// ─────────────────────────────────────────────────────────────────────────────

class ByteSink {
  constructor() {
    this.chunks = [];
    this.length = 0;
  }
  push(u8) {
    this.chunks.push(u8);
    this.length += u8.byteLength;
  }
  /** Concatenates everything written into one Uint8Array. */
  toUint8Array() {
    const out = new Uint8Array(this.length);
    let o = 0;
    for (const c of this.chunks) { out.set(c, o); o += c.byteLength; }
    return out;
  }
}

function asciiBytes(s) {
  const b = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) b[i] = s.charCodeAt(i) & 0xff;
  return b;
}

// ─────────────────────────────────────────────────────────────────────────────
// WAV writer — port of WavWriter. Header placeholder then finalised on finish().
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Builds a PCM or IEEE-float WAV file in memory. Faithful port of WavWriter:
 * accumulates data and emits the 44-byte header with final sizes on {@link #finish}.
 */
export class WavWriter {
  /**
   * @param {number}  sampleRate
   * @param {number}  channels
   * @param {number}  bitsPerSample
   * @param {boolean} [floatFormat=false]  true → 32-bit IEEE float (fmt code 3);
   *                                        false → integer PCM (fmt code 1).
   */
  constructor(sampleRate, channels, bitsPerSample, floatFormat = false) {
    this.sampleRate = sampleRate;
    this.channels = channels;
    this.bitsPerSample = bitsPerSample;
    this.floatFormat = floatFormat;
    this.data = new ByteSink();
  }

  /**
   * Appends raw little-endian PCM bytes (integer PCM mode).
   * @param {Uint8Array} buf
   * @param {number} [length=buf.byteLength]
   */
  writeRaw(buf, length = buf.byteLength) {
    this.data.push(buf.subarray(0, length));
  }

  /**
   * Appends 32-bit IEEE float samples (float WAV mode), little-endian.
   * @param {Float32Array|number[]} samples
   */
  writeFloats(samples) {
    const out = new Uint8Array(samples.length * 4);
    const dv = new DataView(out.buffer);
    for (let i = 0; i < samples.length; i++) dv.setFloat32(i * 4, samples[i], true);
    this.data.push(out);
  }

  /** Header bytes for a given PCM data size. */
  #header(dataSizeBytes) {
    const blockAlign = this.channels * (this.bitsPerSample / 8);
    const byteRate = this.sampleRate * blockAlign;
    const audioFormat = this.floatFormat ? 3 : 1;
    const h = new Uint8Array(44);
    const dv = new DataView(h.buffer);
    h.set(asciiBytes('RIFF'), 0);
    dv.setUint32(4, (36 + dataSizeBytes) >>> 0, true);
    h.set(asciiBytes('WAVE'), 8);
    h.set(asciiBytes('fmt '), 12);
    dv.setUint32(16, 16, true);
    dv.setUint16(20, audioFormat, true);
    dv.setUint16(22, this.channels, true);
    dv.setUint32(24, this.sampleRate, true);
    dv.setUint32(28, byteRate >>> 0, true);
    dv.setUint16(32, blockAlign, true);
    dv.setUint16(34, this.bitsPerSample, true);
    h.set(asciiBytes('data'), 36);
    dv.setUint32(40, dataSizeBytes >>> 0, true);
    return h;
  }

  /**
   * Finalises and returns the full file bytes (header + data).
   * @returns {Uint8Array}
   */
  finish() {
    const out = new Uint8Array(44 + this.data.length);
    out.set(this.#header(this.data.length), 0);
    out.set(this.data.toUint8Array(), 44);
    return out;
  }
}

/**
 * Streaming WAV writer for the scope stream-forward record (StereoPcmIo
 * .saveStreaming + openSink → WavWriter): writes a 44-byte header with PLACEHOLDER
 * RIFF/data sizes to a FileSystemWritableFileStream up front, appends PCM chunks
 * as they arrive (sequential writes advance the stream cursor), then patches the
 * two size fields with positioned writes on {@link #close} — the streaming analogue
 * of {@link WavWriter}, whose in-memory header is finalised only on finish().
 */
export class StreamingWavWriter {
  /**
   * @param {FileSystemWritableFileStream} writable
   * @param {number} sampleRate
   * @param {number} channels
   * @param {number} bitsPerSample
   */
  constructor(writable, sampleRate, channels, bitsPerSample) {
    this.writable = writable;
    this.sampleRate = sampleRate;
    this.channels = channels;
    this.bitsPerSample = bitsPerSample;
    this.dataBytes = 0;
    this._started = false;
  }

  /** Writes the 44-byte header with a zero (placeholder) data size, advancing the
   *  stream cursor to the start of the PCM data. */
  async start() {
    const blockAlign = this.channels * (this.bitsPerSample / 8);
    const byteRate = this.sampleRate * blockAlign;
    const h = new Uint8Array(44);
    const dv = new DataView(h.buffer);
    h.set(asciiBytes('RIFF'), 0);
    dv.setUint32(4, 36, true);                 // placeholder: 36 + 0 data bytes
    h.set(asciiBytes('WAVE'), 8);
    h.set(asciiBytes('fmt '), 12);
    dv.setUint32(16, 16, true);
    dv.setUint16(20, 1, true);                 // PCM
    dv.setUint16(22, this.channels, true);
    dv.setUint32(24, this.sampleRate, true);
    dv.setUint32(28, byteRate >>> 0, true);
    dv.setUint16(32, blockAlign, true);
    dv.setUint16(34, this.bitsPerSample, true);
    h.set(asciiBytes('data'), 36);
    dv.setUint32(40, 0, true);                 // placeholder data size
    await this.writable.write(h);
    this._started = true;
  }

  /** Appends raw little-endian PCM bytes (already packed by packStereo). */
  async writeRaw(buf, length = buf.byteLength) {
    if (!this._started) await this.start();
    await this.writable.write(buf.subarray(0, length));
    this.dataBytes += length;
  }

  /** Patches the RIFF + data size fields (positioned writes) and closes the file. */
  async close() {
    if (!this._started) await this.start();
    const riff = new Uint8Array(4), dv1 = new DataView(riff.buffer);
    dv1.setUint32(0, (36 + this.dataBytes) >>> 0, true);
    await this.writable.write({ type: 'write', position: 4, data: riff });
    const dsz = new Uint8Array(4), dv2 = new DataView(dsz.buffer);
    dv2.setUint32(0, this.dataBytes >>> 0, true);
    await this.writable.write({ type: 'write', position: 40, data: dsz });
    await this.writable.close();
  }
}

/**
 * Streaming AIFF writer — same incremental scheme as {@link StreamingWavWriter}:
 * a 54-byte header with placeholder FORM/COMM/SSND sizes is written up front, each
 * sample byte-swapped to big-endian as it streams, then the FORM size, COMM frame
 * count and SSND size are patched with positioned writes on {@link #close} (port of
 * AiffWriter through StereoPcmIo.openSink's AIFF branch).
 */
export class StreamingAiffWriter {
  /**
   * @param {FileSystemWritableFileStream} writable
   * @param {number} sampleRate
   * @param {number} channels
   * @param {number} bitsPerSample
   */
  constructor(writable, sampleRate, channels, bitsPerSample) {
    this.writable = writable;
    this.sampleRate = sampleRate;
    this.channels = channels;
    this.bitsPerSample = bitsPerSample;
    this.dataBytes = 0;
    this._started = false;
  }

  async start() {
    const h = new Uint8Array(54);
    const dv = new DataView(h.buffer);
    h.set(asciiBytes('FORM'), 0);
    dv.setInt32(4, 4 + 8 + 18 + 8 + 8, false);   // placeholder: form size with 0 data
    h.set(asciiBytes('AIFF'), 8);
    h.set(asciiBytes('COMM'), 12);
    dv.setInt32(16, 18, false);
    dv.setInt16(20, this.channels, false);
    dv.setInt32(22, 0, false);                   // placeholder numFrames
    dv.setInt16(26, this.bitsPerSample, false);
    h.set(toExtended80(this.sampleRate), 28);    // 10 bytes, ends at 38
    h.set(asciiBytes('SSND'), 38);
    dv.setInt32(42, 8, false);                   // placeholder ssnd size (8 + 0 data)
    dv.setInt32(46, 0, false);                   // offset
    dv.setInt32(50, 0, false);                   // blockSize
    await this.writable.write(h);
    this._started = true;
  }

  /** Appends raw little-endian PCM bytes, byte-swapping each sample to big-endian. */
  async writeRaw(buf, length = buf.byteLength) {
    if (!this._started) await this.start();
    const bps = this.bitsPerSample / 8;
    const swapped = new Uint8Array(length);
    for (let i = 0; i < length; i += bps) {
      for (let b = 0; b < bps; b++) swapped[i + b] = buf[i + bps - 1 - b];
    }
    await this.writable.write(swapped);
    this.dataBytes += length;
  }

  async close() {
    if (!this._started) await this.start();
    const bytesPerFrame = this.channels * (this.bitsPerSample / 8);
    const numFrames = Math.floor(this.dataBytes / bytesPerFrame);
    const form = new Uint8Array(4), dvf = new DataView(form.buffer);
    dvf.setInt32(0, (4 + 8 + 18 + 8 + 8 + this.dataBytes) | 0, false);
    await this.writable.write({ type: 'write', position: 4, data: form });
    const nf = new Uint8Array(4), dvn = new DataView(nf.buffer);
    dvn.setInt32(0, numFrames | 0, false);
    await this.writable.write({ type: 'write', position: 22, data: nf });
    const ssnd = new Uint8Array(4), dvs = new DataView(ssnd.buffer);
    dvs.setInt32(0, (8 + this.dataBytes) | 0, false);
    await this.writable.write({ type: 'write', position: 42, data: ssnd });
    await this.writable.close();
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// AIFF writer — port of AiffWriter. Big-endian PCM; LE input byte-swapped.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Converts a non-negative double to a 10-byte IEEE 754 80-bit extended-precision
 * value (sign + 15-bit biased exponent, bias 16383, + 64-bit mantissa with the
 * explicit integer bit). Faithful port of AiffWriter.toExtended80.
 * @param {number} v
 * @returns {Uint8Array} length 10
 */
function toExtended80(v) {
  const out = new Uint8Array(10);
  if (v === 0) return out;
  const sign = v < 0 ? 0x8000 : 0;
  const abs = Math.abs(v);
  // Math.getExponent(abs): floor(log2(abs)).
  const expon = Math.floor(Math.log2(abs));
  const mantissa = abs / Math.pow(2, expon); // in [1, 2)
  const biasedExp = expon + 16383;
  // fractionBits = (mantissa - 1) * 2^63 ; integer bit set separately. Build the
  // 64-bit mantissa with BigInt to avoid the float53 precision loss.
  const fractionBits = BigInt(Math.floor((mantissa - 1.0) * Math.pow(2, 63)));
  const mantInt = (1n << 63n) | fractionBits;
  out[0] = ((sign | biasedExp) >> 8) & 0xff;
  out[1] = (sign | biasedExp) & 0xff;
  for (let i = 0; i < 8; i++) {
    out[2 + i] = Number((mantInt >> BigInt((7 - i) * 8)) & 0xffn);
  }
  return out;
}

/**
 * Builds a PCM AIFF file in memory. Faithful port of AiffWriter: samples are
 * stored big-endian — callers still hand little-endian PCM and this writer
 * byte-swaps each sample on the fly.
 */
export class AiffWriter {
  /**
   * @param {number} sampleRate
   * @param {number} channels
   * @param {number} bitsPerSample
   */
  constructor(sampleRate, channels, bitsPerSample) {
    this.sampleRate = sampleRate;
    this.channels = channels;
    this.bitsPerSample = bitsPerSample;
    this.data = new ByteSink();
  }

  /**
   * Appends raw little-endian PCM bytes, byte-swapping each sample to big-endian.
   * @param {Uint8Array} buf
   * @param {number} [length=buf.byteLength]
   */
  writeRaw(buf, length = buf.byteLength) {
    const bps = this.bitsPerSample / 8;
    const swapped = new Uint8Array(length);
    for (let i = 0; i < length; i += bps) {
      for (let b = 0; b < bps; b++) swapped[i + b] = buf[i + bps - 1 - b];
    }
    this.data.push(swapped);
  }

  #header(dataSizeBytes) {
    const bytesPerFrame = this.channels * (this.bitsPerSample / 8);
    const numFrames = Math.floor(dataSizeBytes / bytesPerFrame);
    const formChunkSize = 4 + 8 + 18 + 8 + 8 + dataSizeBytes;
    const ssndChunkSize = 8 + dataSizeBytes;

    const h = new Uint8Array(54);
    const dv = new DataView(h.buffer);
    h.set(asciiBytes('FORM'), 0);
    dv.setInt32(4, formChunkSize | 0, false);
    h.set(asciiBytes('AIFF'), 8);

    h.set(asciiBytes('COMM'), 12);
    dv.setInt32(16, 18, false);
    dv.setInt16(20, this.channels, false);
    dv.setInt32(22, numFrames | 0, false);
    dv.setInt16(26, this.bitsPerSample, false);
    h.set(toExtended80(this.sampleRate), 28); // 10 bytes, ends at 38

    h.set(asciiBytes('SSND'), 38);
    dv.setInt32(42, ssndChunkSize | 0, false);
    dv.setInt32(46, 0, false); // offset
    dv.setInt32(50, 0, false); // blockSize
    return h;
  }

  /**
   * Finalises and returns the full file bytes.
   * @returns {Uint8Array}
   */
  finish() {
    const out = new Uint8Array(54 + this.data.length);
    out.set(this.#header(this.data.length), 0);
    out.set(this.data.toUint8Array(), 54);
    return out;
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// FLAC writer — STUB. Java uses javaFlacEncoder; the browser port needs a WASM
// encoder (e.g. libFLAC compiled to WASM). Until that is wired in, this throws.
// ─────────────────────────────────────────────────────────────────────────────

/** Mirrors FlacWriter.FLAC_MAX_SAMPLE_RATE (20-bit STREAMINFO sample-rate cap). */
export const FLAC_MAX_SAMPLE_RATE = 655350;

/**
 * Placeholder for the FLAC encoder. FLAC encode requires a WASM codec (libFLAC
 * or equivalent) that is not yet bundled. Validates arguments the way FlacWriter
 * does, then throws so callers fail fast and fall back to WAV/AIFF.
 *
 * Note: the Java FlacWriter NEGATES samples before encoding (a javaFlacEncoder
 * polarity workaround) and forces INDEPENDENT channel coding. Any WASM
 * implementation must reproduce whatever polarity the chosen codec needs to
 * round-trip bit-equivalent to the WAV writer — verify empirically.
 *
 * @param {number} sampleRate
 * @param {number} channels
 * @param {number} bitsPerSample  16 / 24 / 32 only (FLAC spec range).
 * @throws {Error} Always (not yet implemented), or on out-of-range arguments.
 */
export function createFlacWriter(sampleRate, channels, bitsPerSample) {
  if (bitsPerSample !== 16 && bitsPerSample !== 24 && bitsPerSample !== 32) {
    throw new Error('FLAC requires 16, 24, or 32 bit samples (got ' + bitsPerSample + ')');
  }
  if (sampleRate > FLAC_MAX_SAMPLE_RATE) {
    throw new Error('FLAC sample rate is capped at ' + FLAC_MAX_SAMPLE_RATE
      + ' Hz by the format spec (got ' + sampleRate + ' Hz). Use WAV or AIFF for higher rates.');
  }
  throw new Error('FLAC encoding is not yet implemented in the web port (needs a WASM codec). '
    + 'Use WAV or AIFF.');
}
