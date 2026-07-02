/*
 * Phonalyser web — the one ref-counted ADC capture, shared across consumers.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of gui/sound/SharedCapture. Owns the single input AudioContext + capture-processor
 * worklet + MediaStream, and the one live SignalBuffer ring the device writes into. The device
 * opens on the first acquire() (refCount 0→1) and closes on the last release(); each consumer
 * (scope / FFT / loopback recording) gets its OWN SignalBufferReader cursor over the shared ring.
 * Everything else — the generator, the consumers' per-batch feeds, the render-time .frc/mains
 * de-embed — lives elsewhere. This is purely the device + ring + refcount.
 */
import { SignalBuffer } from './signal-buffer.js';
import { SignalBufferReader } from './signal-buffer-reader.js';

/** Shared capture ring length, seconds — mirrors SharedCapture.BUFFER_SECONDS. */
export const BUFFER_SECONDS = 22.0;

export class SharedCapture {
  /**
   * @param deps {getConfig, status, onBatch, computeAnalysisFreqs}
   *   - getConfig: () => the live config object. acquire() reads inDeviceId/inRate and RE-PINS
   *     inRate (mutating the config) to the device's actual rate when they differ.
   *   - status: (text) => void
   *   - onBatch: (d) => void — invoked after each captured batch is staged into the ring so the
   *     owner can drive its consumers (rec tap / scope / FFT) off their own cursors.
   *   - computeAnalysisFreqs: () => void — recomputes the analysis freqs once the real input rate
   *     is known (the scope reads `snapped` even with no generator and no FFT).
   */
  constructor({ getConfig, status, onBatch, computeAnalysisFreqs }) {
    this._getConfig = getConfig;
    this._status = status || (() => {});
    this._onBatch = onBatch || (() => {});
    this._computeAnalysisFreqs = computeAnalysisFreqs || (() => {});
    this._refCount = 0;
    this._buffer = null;            // the one shared SignalBuffer ring (null when closed)
    this.inCtx = null;
    this.stream = null;
    this._capNode = null;
    this._silentNode = null;
    this._srcNode = null;
    this.inSampleRate = 0;
    this._lastStartError = '';   // last device-open failure message (for the streaming-save error)
  }

  /** The one shared ring (null when closed). */
  get buffer() { return this._buffer; }

  /** The shared ring capacity in frames; falls back to BUFFER_SECONDS · inRate
   *  when the device isn't open yet (so the streaming dispatch can size against
   *  the ring before any consumer has acquired it). */
  getCapacity() {
    if (this._buffer) return this._buffer.getCapacity();
    return Math.round((this._getConfig().inRate || 384000) * BUFFER_SECONDS);
  }

  /** The last input-device open failure message. */
  getLastStartError() { return this._lastStartError; }
  /** Live consumer reference count. */
  get refCount() { return this._refCount; }
  /** True while the device is open (any consumer holds a reference). */
  get isCapturing() { return this._refCount > 0; }

  /** Opens the input device on the first acquire (refCount 0→1) and creates the one shared
   *  SignalBuffer; otherwise just bumps the refcount. Returns a fresh SignalBufferReader cursor
   *  over the live ring (each consumer gets its own), or null on failure. Mirrors
   *  SharedCapture.acquire(). */
  async acquire() {
    if (this._refCount > 0) {
      this._refCount++;
      return new SignalBufferReader(this._buffer);
    }
    const c = this._getConfig();
    this._status('opening input context + device…');
    try {
      // INPUT (ADC): open the stream FIRST, then create the capture context at the stream's ACTUAL
      // rate. A MediaStreamSource whose stream rate ≠ the context rate raises the async "AudioContext
      // encountered an error from the audio device" and silently kills capture. Try the exact rate,
      // then fall back to whatever the device gives if it won't expose it via getUserMedia.
      const micBase = { deviceId: { exact: c.inDeviceId }, channelCount: { ideal: 2 },   // stereo so ch1 (calibrated) exists
        echoCancellation: false, noiseSuppression: false, autoGainControl: false };
      try {
        this.stream = await navigator.mediaDevices.getUserMedia({ audio: { ...micBase, sampleRate: { exact: c.inRate } } });
      } catch (_) {
        this.stream = await navigator.mediaDevices.getUserMedia({ audio: micBase });
      }
      const trackRate = this.stream.getAudioTracks()[0].getSettings().sampleRate || c.inRate;
      if (trackRate !== c.inRate) c.inRate = trackRate;   // device gave a different rate → re-pin the analysis to it
      // ALWAYS derive binW / snapped / fundBin here: the scope consumer reads `snapped` (its period
      // + dual-tone freqs) even with NO generator and NO FFT, so computing it only on a rate-mismatch
      // left snapped=undefined → period=NaN → a broken scope trace whenever Scope Record was used alone.
      this._computeAnalysisFreqs();
      this.inCtx = new AudioContext({ sampleRate: trackRate });   // MATCH the stream so it can't rate-mismatch
      await this.inCtx.audioWorklet.addModule(new URL('./worklets/capture-processor.js', import.meta.url));
      // Keep references so the release path can disconnect the whole capture graph BEFORE closing the
      // context (closing with worklets still wired can crash the renderer).
      this._capNode = new AudioWorkletNode(this.inCtx, 'capture-processor');
      this._silentNode = this.inCtx.createGain(); this._silentNode.gain.value = 0;
      this._srcNode = this.inCtx.createMediaStreamSource(this.stream);
      this._srcNode.connect(this._capNode).connect(this._silentNode).connect(this.inCtx.destination);

      // The one shared ring (22 s) the device writes into; each consumer reads it through its own cursor.
      this._buffer = new SignalBuffer(c.inRate, BUFFER_SECONDS);
      this._capNode.port.onmessage = (e) => this._onCaptureBatch(e.data);

      if (this.inCtx.state === 'suspended') await this.inCtx.resume();
      this._refCount = 1;
      this.inSampleRate = this.inCtx.sampleRate;
      return new SignalBufferReader(this._buffer);
    } catch (e) {
      this._lastStartError = e.name + ' — ' + e.message;
      this._status('capture start failed: ' + this._lastStartError);
      await this.teardown();
      return null;
    }
  }

  /** Releases one capture reference; tears the capture graph down when the last reference goes
   *  away. Mirrors SharedCapture.release(). */
  async release() {
    if (this._refCount <= 0) return;
    this._refCount--;
    if (this._refCount > 0) return;
    await this.teardown();
  }

  /** Disconnect → release the mic → suspend → close, mirroring the existing crash-safe capture
   *  teardown (also used on an acquire failure). */
  async teardown() {
    this._refCount = 0;
    // Silence the capture port so no more batches dispatch into a half-torn ring.
    if (this._capNode) { try { this._capNode.port.onmessage = null; } catch (_) {} }
    for (const n of [this._srcNode, this._capNode, this._silentNode]) {
      try { if (n) n.disconnect(); } catch (_) {}
    }
    this._srcNode = this._capNode = this._silentNode = null;
    try { if (this.stream) this.stream.getTracks().forEach(t => t.stop()); } catch (_) {}
    this.stream = null;
    try { if (this.inCtx) { await this.inCtx.suspend().catch(() => {}); await this.inCtx.close(); } } catch (_) {}
    this.inCtx = null;
    this._buffer = null;
  }

  /** Capture-thread → main-thread hand-off: stage L/R into the shared ring, then notify the owner
   *  so each active consumer can read off its OWN cursor (mirror SharedCapture's PCM batch listener
   *  publishing CAPTURE_BATCH_AVAILABLE). */
  _onCaptureBatch(d) {
    const buf = this._buffer;
    if (!buf) return;
    buf.appendBatch(d.l, d.r, d.n);
    this._onBatch(d);
  }
}
