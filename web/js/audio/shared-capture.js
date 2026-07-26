/*
 * Phonalyser web — the one ref-counted ADC capture, shared across consumers.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of gui/sound/SharedCapture. Owns the ONE live SignalBuffer ring the device
 * writes into, the refcount and the reader cursors — nothing device-specific. The device
 * itself is a CaptureSource (below) the owner injects, exactly as Java's SharedCapture holds
 * an AudioCapture that AudioBackend.openCapture() built for the active backend: Web Audio
 * (./web-audio-capture-source.js) or the QA402/QA403 (../qa40x/qa40x-capture-source.js).
 * There is deliberately ONE SharedCapture, not one per backend.
 *
 * The device opens on the first acquire() (refCount 0→1) and closes on the last release();
 * each consumer (scope / FFT / loopback recording) gets its OWN SignalBufferReader cursor
 * over the shared ring. Everything else — the generator, the consumers' per-batch feeds, the
 * render-time .frc/mains de-embed — lives elsewhere.
 */
import { SignalBuffer } from './signal-buffer.js';
import { SignalBufferReader } from './signal-buffer-reader.js';
import { WebAudioCaptureSource } from './web-audio-capture-source.js';
import { MessageBus } from '../bus/message-bus.js';
import { Events } from '../bus/events.js';

/** Shared capture ring length, seconds — mirrors SharedCapture.BUFFER_SECONDS. */
export const BUFFER_SECONDS = 22.0;

/**
 * The capture device behind this ring — Java's AudioCapture, one implementation per
 * backend, held (never subclassed) by SharedCapture. open() acquires the device and
 * builds whatever pipeline it needs but dispatches nothing; start() installs the batch
 * sink, which is why the ring always exists before the first batch; stop() silences the
 * sink; close() releases the device. Every call is awaited, so a synchronous
 * implementation may return plain values.
 *
 * @typedef {Object} CaptureSource
 * @property {(deviceId: string, sampleRateHz: number) => Promise<void>} open opens the
 *           device (Java AudioBackend.openCapture(device, rate, depth) + AudioCapture.open())
 * @property {(onBatch: (batch: {l: Float32Array, r: Float32Array, n: number}) => void)
 *           => Promise<void>} start begins dispatching batches (Java startRecording); `r` is
 *           ch1, the calibrated/attenuated channel every capture path in this app measures
 * @property {() => Promise<void>} stop stops dispatching (Java stopRecording)
 * @property {() => Promise<void>} close releases the device (Java close)
 * @property {number} sampleRate the ACTUAL captured rate, known only once open; the analysis
 *           is re-pinned to it
 * @property {boolean} isOpen true while the device is still held, INCLUDING a close still
 *           in flight (the measurement idle-wait reads this through contextOpen)
 */

export class SharedCapture {
  /**
   * @param deps {getConfig, status, onBatch, computeAnalysisFreqs, publishBatch, captureSource}
   *   - getConfig: () => the live config object. acquire() reads inDeviceId/inRate and RE-PINS
   *     inRate (mutating the config) to the device's actual rate when they differ.
   *   - status: (text) => void
   *   - onBatch: (d) => void — invoked after each captured batch is staged into the ring so the
   *     owner can drive its consumers (rec tap / scope / FFT) off their own cursors.
   *   - computeAnalysisFreqs: () => void — recomputes the analysis freqs once the real input rate
   *     is known (the scope reads `snapped` even with no generator and no FFT).
   *   - captureSource: the CaptureSource this ring is fed by. TRANSITIONAL DEFAULT: the web's
   *     AudioBackend equivalent (AudioEngine, ./backend.js) does not yet dispatch the source on
   *     the active backend, so an un-injected SharedCapture keeps the Web Audio device and the
   *     WEB_AUDIO path behaves exactly as it did before this seam existed.
   */
  constructor({ getConfig, status, onBatch, computeAnalysisFreqs, publishBatch, captureSource } = {}) {
    this._getConfig = getConfig;
    this._status = status || (() => {});
    this._onBatch = onBatch || (() => {});
    this._computeAnalysisFreqs = computeAnalysisFreqs || (() => {});
    // When true, publish CAPTURE_BATCH_AVAILABLE after each staged batch so consumers can
    // self-feed off their own cursors (the LIVE scope/FFT capture). The measurement capture
    // leaves this false so a sweep's batches never trigger the live consumers.
    this._publishBatch = !!publishBatch;
    /** @type {CaptureSource} */
    this._source = captureSource || new WebAudioCaptureSource({ status: (t) => this._status(t) });
    this._refCount = 0;
    this._buffer = null;            // the one shared SignalBuffer ring (null when closed)
    this.inSampleRate = 0;
    this._lastStartError = '';   // last device-open failure message (for the streaming-save error)
  }

  /** Publishes AUDIO_DEVICE_ERROR (direction = input) so the shell can raise a visible alert.
   *  Used for the acquire()-time device-open rejection, which is the failure THIS class
   *  classifies; a running device that is later lost is detected by the source and published
   *  from there (the two are the only AUDIO_DEVICE_ERROR sites on the input direction). */
  _reportDeviceError(detail) {
    MessageBus.instance().publish(Events.AUDIO_DEVICE_ERROR, { direction: 'input', detail });
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
  /** True while the input device is still open — including the brief window
   *  AFTER release() dropped refCount to 0 but teardown()'s close() has not
   *  yet resolved (the OS device is still held). The idle-wait polls this so a
   *  measurement takeover blocks until the device is genuinely free, not merely
   *  until the refcount flag flipped. */
  get contextOpen() { return this._source.isOpen; }

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
      // The device: its own open() owns the retry / rate / graph mechanics (Java
      // AudioBackend.openCapture(device, rate, depth) + AudioCapture.open()). It reports the
      // ACTUAL rate only once open, which is why the re-pin below happens here and not earlier.
      await this._source.open(c.inDeviceId, c.inRate);
      const deviceRate = this._source.sampleRate || c.inRate;
      if (deviceRate !== c.inRate) c.inRate = deviceRate;   // device gave a different rate → re-pin the analysis to it
      // ALWAYS derive binW / snapped / fundBin here: the scope consumer reads `snapped` (its period
      // + dual-tone freqs) even with NO generator and NO FFT, so computing it only on a rate-mismatch
      // left snapped=undefined → period=NaN → a broken scope trace whenever Scope Record was used alone.
      this._computeAnalysisFreqs();

      // The one shared ring (22 s) the device writes into; each consumer reads it through its own
      // cursor. Created BEFORE start(), which is what installs the batch sink — so no batch can
      // ever land in a half-built ring.
      this._buffer = new SignalBuffer(c.inRate, BUFFER_SECONDS);
      await this._source.start((d) => this._onCaptureBatch(d));

      this._refCount = 1;
      this.inSampleRate = deviceRate;
      return new SignalBufferReader(this._buffer);
    } catch (e) {
      this._lastStartError = e.name + ' — ' + e.message;
      this._status('capture start failed: ' + this._lastStartError);
      // Raise a visible alert for a genuine device-open failure (device busy / unplugged / driver
      // gone). getUserMedia rejects with NotReadableError/AbortError when the input is held by
      // another app in exclusive mode; NotFoundError when it vanished. NotAllowed/SecurityError is
      // a permission denial (not a "device in use" case) — status only, no modal.
      if (e.name !== 'NotAllowedError' && e.name !== 'SecurityError') this._reportDeviceError(this._lastStartError);
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

  /** Silence the batch sink, then release the device — the crash-safe teardown, also used on an
   *  acquire failure (which can leave a half-open device). Each step is swallowed on its own so a
   *  failing stop() can never skip the close() that actually frees the device. */
  async teardown() {
    this._refCount = 0;
    try { await this._source.stop(); } catch (_) {}
    try { await this._source.close(); } catch (_) {}
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
    // Java SharedCapture publishes CAPTURE_BATCH_AVAILABLE; the scope + FFT consumers subscribe
    // and read their own cursors. Only the live capture opts in (publishBatch).
    if (this._publishBatch) MessageBus.instance().publish(Events.CAPTURE_BATCH_AVAILABLE);
  }
}
