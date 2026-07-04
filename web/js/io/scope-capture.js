/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of the scope "capture" file format:
// org.edgo.audio.measure.gui.scope.StereoPcmIo (packStereo / decodeStereo /
// formatForName / openSink) and ScopeFileSaver (findFullPeriodWindow + save).
//
// There is no bespoke binary ".osc" container — a scope capture is just a stereo
// PCM audio file (.wav / .flac / .aiff / .aif). The scope holds a ring of
// normalised float samples in [-1, +1]; this module quantises them to signed
// little-endian PCM at the chosen bit depth, optionally snapping the saved span
// to whole signal periods (rising zero crossings) so a looped player has no
// click at the seam, then wraps the bytes in the format-appropriate writer.
//
// The decode side turns a decoder's PCM bytes back into float [-1, +1] L/R,
// tolerant of 8/16/24/32-bit, signed/unsigned, big/little-endian — the inverse
// of packStereo for the common (signed little-endian) case.

import { WavWriter, AiffWriter, createFlacWriter,
         StreamingWavWriter, StreamingAiffWriter } from './wav.js';

/** Sentinel matching SignalBufferReader.OVERRUN (re-exported so saveStreaming can
 *  test reader.read()'s return without importing the reader module). */
const OVERRUN = -1;

/** Stereo: both writers + the packer assume 2 channels (StereoPcmIo.CHANNELS). */
export const CHANNELS = 2;

/**
 * Picks the container format from the file name's extension. Faithful port of
 * StereoPcmIo.formatForName (default WAV).
 * @param {string} name
 * @returns {'WAV'|'FLAC'|'AIFF'}
 */
export function formatForName(name) {
  const lower = name.toLowerCase();
  if (lower.endsWith('.flac')) return 'FLAC';
  if (lower.endsWith('.aiff') || lower.endsWith('.aif')) return 'AIFF';
  return 'WAV';
}

function clamp(v) {
  if (v > 1) return 1;
  if (v < -1) return -1;
  return v;
}

/**
 * Quantises {@code count} stereo float samples (range [-1,+1]) starting at
 * {@code offset} into interleaved little-endian signed PCM bytes at the requested
 * bit depth. Faithful port of StereoPcmIo.packStereo (8-bit is unsigned offset
 * binary; 16/24/32-bit are signed two's-complement, max = 2^(bits-1)-1).
 *
 * @param {Float32Array|number[]} left
 * @param {Float32Array|number[]} right
 * @param {number} offset
 * @param {number} count
 * @param {Uint8Array} buf      Output, ≥ count * (bitDepth/8) * CHANNELS bytes.
 * @param {number} bitDepth     8 / 16 / 24 / 32.
 */
export function packStereo(left, right, offset, count, buf, bitDepth) {
  const bytesPerSample = bitDepth / 8;
  const bytesPerFrame = bytesPerSample * CHANNELS;
  if (bitDepth === 8) {
    for (let i = 0; i < count; i++) {
      const o = i * bytesPerFrame;
      buf[o] = (Math.round(clamp(left[offset + i]) * 127) + 128) & 0xff;
      buf[o + 1] = (Math.round(clamp(right[offset + i]) * 127) + 128) & 0xff;
    }
    return;
  }
  // maxVal = 2^(bitDepth-1) - 1. Use the byte loop (>> 8*b) exactly like Java;
  // values fit in 32-bit for ≤24-bit, and 32-bit uses Math.round on a double.
  const maxVal = Math.pow(2, bitDepth - 1) - 1;
  for (let i = 0; i < count; i++) {
    const pcmL = Math.round(clamp(left[offset + i]) * maxVal);
    const pcmR = Math.round(clamp(right[offset + i]) * maxVal);
    const o = i * bytesPerFrame;
    for (let b = 0; b < bytesPerSample; b++) {
      // Math.floor(x / 2^(8b)) & 0xFF reproduces the signed `>> 8b` byte extract
      // for two's-complement values without 32-bit overflow at bitDepth 32.
      buf[o + b] = Math.floor(pcmL / Math.pow(2, 8 * b)) & 0xff;
      buf[o + bytesPerSample + b] = Math.floor(pcmR / Math.pow(2, 8 * b)) & 0xff;
    }
  }
}

/**
 * Reads one PCM sample. Faithful port of StereoPcmIo.readSample.
 * @returns {number} signed integer sample value
 */
function readSample(pcm, off, bytesPerSample, signed, bigEndian) {
  switch (bytesPerSample) {
    case 1: {
      const v = pcm[off] & 0xff;
      return signed ? (v << 24 >> 24) : v - 128;
    }
    case 2: {
      const v = bigEndian
        ? ((pcm[off] & 0xff) << 8) | (pcm[off + 1] & 0xff)
        : ((pcm[off + 1] & 0xff) << 8) | (pcm[off] & 0xff);
      return signed ? (v << 16 >> 16) : v - 0x8000;
    }
    case 3: {
      let v = bigEndian
        ? ((pcm[off] << 16) | ((pcm[off + 1] & 0xff) << 8) | (pcm[off + 2] & 0xff))
        : ((pcm[off + 2] << 16) | ((pcm[off + 1] & 0xff) << 8) | (pcm[off] & 0xff));
      v = (v << 8) >> 8; // sign-extend 24-bit
      return signed ? v : v - 0x800000;
    }
    case 4: {
      const v = bigEndian
        ? ((pcm[off] << 24) | ((pcm[off + 1] & 0xff) << 16)
            | ((pcm[off + 2] & 0xff) << 8) | (pcm[off + 3] & 0xff))
        : ((pcm[off + 3] << 24) | ((pcm[off + 2] & 0xff) << 16)
            | ((pcm[off + 1] & 0xff) << 8) | (pcm[off] & 0xff));
      // `v` is already a signed int32 from the bit ops; unsigned shifts the bias.
      return signed ? v : (v - 0x80000000) | 0;
    }
    default:
      throw new Error('Unsupported bytesPerSample: ' + bytesPerSample);
  }
}

/**
 * Decodes {@code frames} of interleaved PCM bytes into normalised float [-1,+1]
 * L/R. Faithful port of StereoPcmIo.decodeStereo: a mono source (channels==1) is
 * mirrored to both outputs; midpoint = 2^(bits-1).
 *
 * @param {Uint8Array} pcm
 * @param {number} frames
 * @param {number} channels
 * @param {number} bytesPerSample
 * @param {boolean} signed
 * @param {boolean} bigEndian
 * @param {Float32Array} left   Output, length ≥ frames.
 * @param {Float32Array} right  Output, length ≥ frames.
 */
export function decodeStereo(pcm, frames, channels, bytesPerSample, signed, bigEndian, left, right) {
  const frameSize = bytesPerSample * channels;
  const midpoint = Math.pow(2, bytesPerSample * 8 - 1);
  for (let f = 0; f < frames; f++) {
    const off = f * frameSize;
    const sL = readSample(pcm, off, bytesPerSample, signed, bigEndian);
    const sR = channels >= 2
      ? readSample(pcm, off + bytesPerSample, bytesPerSample, signed, bigEndian)
      : sL;
    left[f] = sL / midpoint;
    right[f] = sR / midpoint;
  }
}

// ─── Full-period window snapping (ScopeFileSaver.findFullPeriodWindow) ────────

/** First index in [lo,hi) where arr[i-1]<0 && arr[i]>=0; -1 if none. */
function firstRisingZeroCross(arr, lo, hi) {
  for (let i = lo; i < hi; i++) if (arr[i - 1] < 0 && arr[i] >= 0) return i;
  return -1;
}

/** Largest index in [lo,hi) where arr[i-1]<0 && arr[i]>=0; -1 if none. */
function lastRisingZeroCross(arr, lo, hi) {
  for (let i = hi - 1; i >= lo && i >= 1; i--) if (arr[i - 1] < 0 && arr[i] >= 0) return i;
  return -1;
}

/**
 * Finds the largest (start,length) sub-range of {@code [0,actual)} whose
 * endpoints land on rising zero crossings of {@code left} one period apart, so
 * the saved span is an integer number of signal periods and loops cleanly.
 * Faithful port of ScopeFileSaver.findFullPeriodWindow. Returns the trivial
 * (0, actual) when frequency detection isn't usable.
 *
 * @param {Float32Array} left
 * @param {number} actual
 * @param {number} sampleRate
 * @param {number} freqHz   Pass 0 / NaN / Infinity to disable snapping.
 * @returns {{start:number, length:number}}
 */
export function findFullPeriodWindow(left, actual, sampleRate, freqHz) {
  if (!(freqHz > 0) || !Number.isFinite(freqHz) || sampleRate <= 0 || actual < 2) {
    return { start: 0, length: actual };
  }
  const periodSamples = Math.max(2, Math.round(sampleRate / freqHz));
  if (actual < 2 * periodSamples) return { start: 0, length: actual };

  const startSnap = firstRisingZeroCross(left, 1, Math.min(actual, periodSamples + 1));
  if (startSnap < 0) return { start: 0, length: actual };
  const endSnap = lastRisingZeroCross(
    left, Math.max(startSnap + periodSamples, actual - periodSamples), actual);
  if (endSnap <= startSnap) return { start: 0, length: actual };

  const length = endSnap - startSnap;
  if (length < periodSamples) return { start: 0, length: actual };
  return { start: startSnap, length };
}

// ─── Save ────────────────────────────────────────────────────────────────────

/** Opens the format-appropriate writer (StereoPcmIo.openSink). FLAC throws
 *  until the WASM encoder is wired in (see wav.js). */
function openWriter(fmt, sampleRate, bitDepth) {
  switch (fmt) {
    case 'FLAC': return createFlacWriter(sampleRate, CHANNELS, bitDepth);
    case 'AIFF': return new AiffWriter(sampleRate, CHANNELS, bitDepth);
    default: return new WavWriter(sampleRate, CHANNELS, bitDepth, false);
  }
}

/**
 * Builds a stereo capture file from normalised float L/R. Faithful port of
 * ScopeFileSaver.save: optionally snaps the span to whole signal periods, then
 * quantises and encodes in 4096-frame chunks.
 *
 * @param {Float32Array} left   Captured left channel, length ≥ {@code actual}.
 * @param {Float32Array} right  Captured right channel.
 * @param {number} actual       Number of valid frames in left/right.
 * @param {string} fileName     Used only to pick the format from its extension.
 * @param {number} sampleRate
 * @param {number} bitDepth     8 / 16 / 24 / 32.
 * @param {number} [signalFrequencyHz=0]  >0 to snap to whole periods; 0/NaN to skip.
 * @returns {Uint8Array} The complete encoded file bytes.
 */
export function saveScopeCapture(left, right, actual, fileName, sampleRate, bitDepth,
                                 signalFrequencyHz = 0) {
  if (actual <= 0) {
    throw new Error("Scope buffer is empty — capture hasn't produced any samples yet.");
  }
  const w = findFullPeriodWindow(left, actual, sampleRate, signalFrequencyHz);
  const saveStart = w.start;
  const saveLength = w.length;

  const bytesPerFrame = (bitDepth / 8) * CHANNELS;
  const fmt = formatForName(fileName);
  const sink = openWriter(fmt, sampleRate, bitDepth);

  const chunkFrames = 4096;
  const buf = new Uint8Array(chunkFrames * bytesPerFrame);
  let written = 0;
  while (written < saveLength) {
    const n = Math.min(chunkFrames, saveLength - written);
    packStereo(left, right, saveStart + written, n, buf, bitDepth);
    sink.writeRaw(buf, n * bytesPerFrame);
    written += n;
  }
  return sink.finish();
}

// ─── Streaming save (record-to-disk) ─────────────────────────────────────────

/** Opens the streaming sink for {@code fmt}. WAV / AIFF size-patch their headers
 *  with positioned writes at close; FLAC has no streaming web encoder, so the
 *  caller keeps the buffer-dump fallback for .flac only (handled in the tab
 *  control — saveStreaming throws here so the failure is explicit). */
function openStreamingSink(fmt, writable, sampleRate, bitDepth) {
  switch (fmt) {
    case 'FLAC':
      throw new Error('FLAC streaming record is not supported in the web port (no streaming '
        + 'WASM encoder). Use WAV or AIFF, or a duration within the ring for a one-shot FLAC.');
    case 'AIFF': return new StreamingAiffWriter(writable, sampleRate, CHANNELS, bitDepth);
    default: return new StreamingWavWriter(writable, sampleRate, CHANNELS, bitDepth);
  }
}

/**
 * Streams up to {@code totalFrames} of LIVE capture from {@code reader}'s
 * contiguous cursor straight to {@code writable} in real time — for captures
 * longer than the ring buffer. Faithful async port of StereoPcmIo.saveStreaming:
 * unlike {@link saveScopeCapture} (which dumps the already-captured ring), this
 * records FORWARD, reading the cursor as fresh samples arrive, so it takes about
 * {@code totalFrames / sampleRate} seconds of wall-clock to complete.
 *
 * <p>{@code isCancelled} is polled to stop early (the partial file is still
 * finalised); {@code onProgress} is notified with the running frame count for a UI
 * bar. Returns the number of frames actually written. The caller owns the capture
 * reference (acquire before, release after). If the writer ever falls a full ring
 * behind (disk stall), the cursor is re-anchored rather than tearing.
 *
 * @param {import('../audio/signal-buffer-reader.js').SignalBufferReader} reader
 * @param {FileSystemWritableFileStream} writable
 * @param {number} sampleRate
 * @param {number} bitDepth     8 / 16 / 24 / 32.
 * @param {number} totalFrames
 * @param {string} fileName      Picks the container format from its extension.
 * @param {() => boolean} isCancelled
 * @param {(written:number) => void} onProgress
 * @returns {Promise<number>} frames written.
 */
export async function saveStreaming(reader, writable, sampleRate, bitDepth, totalFrames,
                                    fileName, isCancelled, onProgress) {
  if (!reader) {
    throw new Error('No live capture to record (start the scope recording first).');
  }
  const fmt = formatForName(fileName);
  const bytesPerFrame = (bitDepth / 8) * CHANNELS;
  const chunkFrames = Math.max(4096, Math.floor(sampleRate / 10));   // ~100 ms per write
  const left = new Float64Array(chunkFrames);
  const right = new Float64Array(chunkFrames);
  const buf = new Uint8Array(chunkFrames * bytesPerFrame);

  reader.seekToLatest();
  let written = 0;
  const sink = openStreamingSink(fmt, writable, sampleRate, bitDepth);
  try {
    while (written < totalFrames && (isCancelled == null || !isCancelled())) {
      const want = Math.min(chunkFrames, totalFrames - written);
      const n = reader.read(want, left, right);
      if (n === OVERRUN) {
        reader.seekToLatest();   // writer stalled a full ring behind — re-anchor
        continue;
      }
      if (n <= 0) {
        await new Promise((r) => setTimeout(r, 20));   // caught up to the tip — await fresh samples
        continue;
      }
      packStereo(left, right, 0, n, buf, bitDepth);
      await sink.writeRaw(buf, n * bytesPerFrame);
      written += n;
      if (onProgress) onProgress(written);
    }
  } finally {
    await sink.close();
  }
  return written;
}
