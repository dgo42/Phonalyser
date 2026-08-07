/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// FLAC encode/decode via libFLAC (libflacjs, WASM build) - the browser stand-in
// for the desktop FlacWriter (javaFlacEncoder). libFLAC is used for BOTH
// directions on purpose: decodeAudioData would route through the browser's
// resampler and hand back float samples at the AudioContext rate, losing the
// bit-exact PCM the measurement pipeline needs. The WASM codec preserves the
// stored samples and sample rate exactly.
//
// libflac.min.wasm.js is loaded from the CDN in index.html and exposes the global
// `Flac`. It initialises asynchronously, so every entry point awaits readiness.
// If the script never loaded (offline / blocked CDN), the helpers reject with a
// clear message instead of crashing the app.

/** Default FLAC compression level - matches the desktop FlacWriter. */
const DEFAULT_COMPRESSION = 5;

/** Scope saves are stereo (StereoPcmIo.CHANNELS). */
const CHANNELS = 2;

/** Sentinel matching SignalBufferReader.OVERRUN (declared locally so the
 *  streaming save can test reader.read()'s return without importing the reader
 *  module - same convention as io/scope-capture.js). */
const OVERRUN = -1;

/** Rejects bit depths the WASM libFLAC reference encoder cannot stream: at
 *  32-bit init_encoder_stream returns INVALID_BITS_PER_SAMPLE (status 5) and the
 *  stream writes NOTHING, yielding a 0-byte file. Fail loudly here instead of
 *  silently emitting an empty .flac - the callers export FLAC at 24-bit.
 *  (Java's FlacWriter accepts 16/24/32; the browser codec caps at 24.) */
function assertEncodableBits(bitsPerSample) {
  if (bitsPerSample !== 16 && bitsPerSample !== 24) {
    throw new Error('FLAC encode supports 16 or 24 bits/sample in the web codec '
      + '(got ' + bitsPerSample + '). Use WAV or AIFF for 32-bit.');
  }
}

/** Creates + stream-initialises a libFLAC encoder whose output chunks go to
 *  {@code onWrite}. Throws (with the encoder freed) if init fails. */
function openEncoderStream(Flac, sampleRate, channels, bitsPerSample, compression,
                           totalFrames, onWrite) {
  const encoder = Flac.create_libflac_encoder(sampleRate, channels, bitsPerSample,
    compression, totalFrames);
  if (!encoder) throw new Error('FLAC encoder could not be created');
  const status = Flac.init_encoder_stream(encoder, onWrite);
  if (status !== 0) {
    Flac.FLAC__stream_encoder_delete(encoder);
    throw new Error('FLAC encoder init failed (status ' + status + ')');
  }
  return encoder;
}

/** Resolves once the global libFLAC codec reports ready, or rejects if absent. */
function flacReady() {
  return new Promise((resolve, reject) => {
    const Flac = window.Flac;
    if (!Flac) {
      reject(new Error('FLAC codec unavailable: libflac.min.wasm.js failed to load '
        + '(offline or blocked CDN). Use WAV or AIFF instead.'));
      return;
    }
    if (Flac.isReady()) { resolve(Flac); return; }
    Flac.on('ready', () => resolve(Flac));
  });
}

/** Concatenates an array of Uint8Array chunks into one buffer. */
function concatChunks(chunks) {
  let total = 0;
  for (const c of chunks) total += c.byteLength;
  const out = new Uint8Array(total);
  let o = 0;
  for (const c of chunks) { out.set(c, o); o += c.byteLength; }
  return out;
}

/**
 * Builds the interleaved Int32 sample buffer libFLAC expects from per-channel
 * input. Float channels (range [-1,+1]) are scaled to the signed integer range
 * for {@code bitsPerSample}; integer channels are interleaved as-is.
 *
 * @param {(Float32Array|Float64Array|Int32Array)[]} channels
 * @param {number} bitsPerSample
 * @returns {Int32Array} interleaved, length = frames * channels.length
 */
function interleaveToInt32(channels, bitsPerSample) {
  const numCh = channels.length;
  const frames = channels[0].length;
  const out = new Int32Array(frames * numCh);
  // Anything that isn't already quantised Int32 is float [-1,+1] to scale
  // (Float32Array ring dumps AND the Float64Array chunks the streaming reader fills).
  const isFloat = !(channels[0] instanceof Int32Array);
  const maxVal = Math.pow(2, bitsPerSample - 1) - 1;
  for (let f = 0; f < frames; f++) {
    for (let c = 0; c < numCh; c++) {
      const v = channels[c][f];
      out[f * numCh + c] = isFloat ? Math.max(-maxVal - 1, Math.min(maxVal, Math.round(v * maxVal))) : v | 0;
    }
  }
  return out;
}

/**
 * Converts one libFLAC channel byte buffer (little-endian PCM, padded to a wider
 * stride for 24-/8-bit) into signed Int32 samples. Mirrors the byte layout the
 * libflacjs interleave helper consumes.
 *
 * @param {Uint8Array} b
 * @param {number} bitsPerSample  8 / 16 / 24 / 32.
 * @returns {Int32Array}
 */
function unpackChannel(b, bitsPerSample) {
  const dataBytes = bitsPerSample / 8;
  // libFLAC pads 24- and 8-bit samples by one byte (3->4, 1->2); 16/32 are exact.
  const stride = (bitsPerSample === 24 || bitsPerSample === 8) ? dataBytes + 1 : dataBytes;
  const dv = new DataView(b.buffer, b.byteOffset, b.byteLength);
  const n = Math.floor(b.byteLength / stride);
  const out = new Int32Array(n);
  for (let i = 0; i < n; i++) {
    const off = i * stride;
    switch (dataBytes) {
      case 1: out[i] = dv.getInt8(off); break;
      case 2: out[i] = dv.getInt16(off, true); break;
      case 3: {
        const lo = dv.getUint8(off), mid = dv.getUint8(off + 1), hi = dv.getUint8(off + 2);
        out[i] = ((hi << 24) | (mid << 16) | (lo << 8)) >> 8; // sign-extend 24-bit LE
        break;
      }
      default: out[i] = dv.getInt32(off, true); break;
    }
  }
  return out;
}

/**
 * Encodes PCM to a FLAC byte stream with libFLAC.
 *
 * @param {(Float32Array|Int32Array)[]} channels  One array per channel (mono or stereo).
 *        Float32 values in [-1,+1] are scaled to the integer range; Int32 values
 *        are taken as already-quantised signed PCM.
 * @param {number} sampleRate
 * @param {number} bitsPerSample  16 / 24 (FLAC range).
 * @param {number} [compression=5]  0..8.
 * @returns {Promise<Uint8Array>} the complete .flac file bytes.
 */
export async function encodeFlac(channels, sampleRate, bitsPerSample, compression = DEFAULT_COMPRESSION) {
  assertEncodableBits(bitsPerSample);
  const Flac = await flacReady();
  const numCh = channels.length;
  const frames = channels[0].length;
  const interleaved = interleaveToInt32(channels, bitsPerSample);

  const chunks = [];
  const onWrite = (data) => { chunks.push(data.slice()); };
  const encoder = openEncoderStream(Flac, sampleRate, numCh, bitsPerSample, compression,
    frames, onWrite);

  const ok = Flac.FLAC__stream_encoder_process_interleaved(encoder, interleaved, frames);
  Flac.FLAC__stream_encoder_finish(encoder);
  Flac.FLAC__stream_encoder_delete(encoder);
  if (!ok) throw new Error('FLAC encoding failed');

  return concatChunks(chunks);
}

/**
 * Streams up to {@code totalFrames} of LIVE capture from {@code reader}'s
 * contiguous cursor straight to {@code writable} as FLAC, in real time - the
 * FLAC branch of the scope stream-forward record. Faithful port of the FLAC case
 * of StereoPcmIo.saveStreaming (~line 209) + openSink (~line 71): Java streams
 * FLAC through FlacWriter exactly like WAV/AIFF. It lives here (not in
 * scope-capture's openStreamingSink) because libFLAC owns the container framing:
 * the encoder emits its own byte chunks via the onWrite callback - there is no
 * header-placeholder-then-patch scheme like the streaming WAV/AIFF writers, so
 * chunks are simply appended to the writable in order. STREAMINFO's
 * total_samples stays 0 ("unknown"): a stream encoder without a seek callback
 * cannot patch the header, the spec allows it, and decodeFlac stitches frames
 * without it.
 *
 * <p>Loop semantics mirror StereoPcmIo.saveStreaming (~lines 227-244): OVERRUN ->
 * re-anchor with seekToLatest; caught up to the live tip -> await fresh samples
 * (20 ms); cancel -> stop early, the partial file is still finalised (Java's
 * try-with-resources close). Returns the number of frames actually written; the
 * caller owns the capture reference (acquire before, release after).
 *
 * @param {import('../audio/signal-buffer-reader.js').SignalBufferReader} reader
 * @param {FileSystemWritableFileStream} writable
 * @param {number} sampleRate
 * @param {number} bitsPerSample  16 / 24 (web codec cap - 32-bit WAV/AIFF only).
 * @param {number} totalFrames
 * @param {() => boolean} isCancelled
 * @param {(written:number) => void} onProgress
 * @param {number} [compression=5]  0..8.
 * @returns {Promise<number>} frames written.
 */
export async function saveStreamingFlac(reader, writable, sampleRate, bitsPerSample,
                                        totalFrames, isCancelled, onProgress,
                                        compression = DEFAULT_COMPRESSION) {
  if (!reader) {
    throw new Error('No live capture to record (start the scope recording first).');
  }
  assertEncodableBits(bitsPerSample);
  const Flac = await flacReady();

  const chunkFrames = Math.max(4096, Math.floor(sampleRate / 10));   // ~100 ms per read
  const left = new Float64Array(chunkFrames);
  const right = new Float64Array(chunkFrames);

  // Encoder output chunks are buffered synchronously by onWrite during each
  // process call, then drained to the writable with awaited sequential writes.
  const pending = [];
  const onWrite = (data) => { pending.push(data.slice()); };
  const encoder = openEncoderStream(Flac, sampleRate, CHANNELS, bitsPerSample,
    compression, 0 /* total unknown up-front */, onWrite);
  const drain = async () => { while (pending.length) await writable.write(pending.shift()); };

  reader.seekToLatest();
  let written = 0;
  let err = null;
  try {
    while (written < totalFrames && (isCancelled == null || !isCancelled())) {
      const want = Math.min(chunkFrames, totalFrames - written);
      const n = reader.read(want, left, right);
      if (n === OVERRUN) {
        reader.seekToLatest();   // writer stalled a full ring behind - re-anchor
        continue;
      }
      if (n <= 0) {
        await new Promise((r) => setTimeout(r, 20));   // caught up to the tip - await fresh samples
        continue;
      }
      const interleaved = interleaveToInt32(
        [left.subarray(0, n), right.subarray(0, n)], bitsPerSample);
      if (!Flac.FLAC__stream_encoder_process_interleaved(encoder, interleaved, n)) {
        throw new Error('FLAC encoding failed');
      }
      await drain();
      written += n;
      if (onProgress) onProgress(written);
    }
  } catch (e) {
    err = e;
  }
  // Finalise even on cancel / error - flush the encoder's tail frames, then close
  // the file (a loop error wins over a finalisation error, like try-with-resources).
  try {
    Flac.FLAC__stream_encoder_finish(encoder);   // may emit final chunks via onWrite
    Flac.FLAC__stream_encoder_delete(encoder);
    await drain();
    await writable.close();
  } catch (e) {
    if (!err) err = e;
  }
  if (err) throw err;
  return written;
}

/**
 * Decoded FLAC payload.
 * @typedef {Object} FlacDecodeResult
 * @property {number}     sampleRate
 * @property {number}     channels
 * @property {number}     bitsPerSample
 * @property {Int32Array} ch0           Signed integer PCM, channel 0.
 * @property {Int32Array} ch1           Channel 1 (= ch0 for mono).
 */

/**
 * Decodes a FLAC byte stream to signed integer PCM channels with libFLAC
 * (bit-exact - no resampling).
 *
 * @param {Uint8Array|ArrayBuffer} bytes  Raw .flac file bytes.
 * @returns {Promise<FlacDecodeResult>}
 */
export async function decodeFlac(bytes) {
  const Flac = await flacReady();
  const data = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);

  const decoder = Flac.create_libflac_decoder();
  if (!decoder) throw new Error('FLAC decoder could not be created');

  let offset = 0;
  const read = (bufferSize) => {
    const end = Math.min(offset + bufferSize, data.length);
    const slice = data.subarray(offset, end);
    offset = end;
    return { buffer: slice, readDataLength: slice.length, error: false };
  };

  let sampleRate = 0, channels = 0, bitsPerSample = 0;
  const chPieces = []; // array of [Int32Array per channel] per decoded frame
  const write = (channelBuffers, frameInfo) => {
    sampleRate = frameInfo.sampleRate;
    channels = frameInfo.channels;
    bitsPerSample = frameInfo.bitsPerSample;
    // channelBuffers: Uint8Array[], one per channel, holding little-endian PCM
    // bytes. libFLAC pads 24- and 8-bit samples to a 4-/2-byte stride, so the
    // in-memory stride is wider than the significant byte count.
    const frameChannels = channelBuffers.map((b) => unpackChannel(b, bitsPerSample));
    chPieces.push(frameChannels);
  };

  let decodeError = null;
  const error = (errCode, errMsg) => { decodeError = errMsg || ('FLAC decode error ' + errCode); };

  const status = Flac.init_decoder_stream(decoder, read, write, error);
  if (status !== 0) {
    Flac.FLAC__stream_decoder_delete(decoder);
    throw new Error('FLAC decoder init failed (status ' + status + ')');
  }

  Flac.FLAC__stream_decoder_process_until_end_of_stream(decoder);
  Flac.FLAC__stream_decoder_finish(decoder);
  Flac.FLAC__stream_decoder_delete(decoder);

  if (decodeError) throw new Error(decodeError);
  if (!chPieces.length) throw new Error('FLAC stream produced no samples');

  // Stitch the per-frame channel pieces into one contiguous buffer per channel.
  let total = 0;
  for (const piece of chPieces) total += piece[0].length;
  const ch0 = new Int32Array(total);
  const ch1 = new Int32Array(total);
  let o = 0;
  for (const piece of chPieces) {
    const n = piece[0].length;
    ch0.set(piece[0], o);
    ch1.set(channels >= 2 ? piece[1] : piece[0], o);
    o += n;
  }

  return { sampleRate, channels, bitsPerSample, ch0, ch1 };
}
