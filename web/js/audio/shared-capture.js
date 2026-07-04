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
import { MessageBus } from '../bus/message-bus.js';
import { Events } from '../bus/events.js';

/** Shared capture ring length, seconds — mirrors SharedCapture.BUFFER_SECONDS. */
export const BUFFER_SECONDS = 22.0;

/** Bounded device-open retry (mirror GeneratorController.startGenerator's
 *  MAX_ATTEMPTS / RETRY_PAUSE_MS): a measurement takeover (FreqResp / Tune-notch)
 *  stops the other modules then opens the device itself, but the just-stopped
 *  AudioContexts release the OS device tens of ms AFTER their close() resolves —
 *  so the first getUserMedia can lose the race and reject NotReadableError /
 *  AbortError even though no OTHER app holds it (a SELF-contention). Retry a few
 *  times with a short pause before reporting a genuine external hold. */
const OPEN_MAX_ATTEMPTS = 3;
const OPEN_RETRY_PAUSE_MS = 250;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

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
  constructor({ getConfig, status, onBatch, computeAnalysisFreqs, publishBatch } = {}) {
    this._getConfig = getConfig;
    this._status = status || (() => {});
    this._onBatch = onBatch || (() => {});
    this._computeAnalysisFreqs = computeAnalysisFreqs || (() => {});
    // When true, publish CAPTURE_BATCH_AVAILABLE after each staged batch so consumers can
    // self-feed off their own cursors (the LIVE scope/FFT capture). The measurement capture
    // leaves this false so a sweep's batches never trigger the live consumers.
    this._publishBatch = !!publishBatch;
    this._refCount = 0;
    this._buffer = null;            // the one shared SignalBuffer ring (null when closed)
    this.inCtx = null;
    this.stream = null;
    this._capNode = null;
    this._silentNode = null;
    this._srcNode = null;
    this.inSampleRate = 0;
    this._lastStartError = '';   // last device-open failure message (for the streaming-save error)
    // True only while WE are tearing the context down, so the context's own 'closed'/'suspended'
    // statechange during teardown is not misread as an unexpected device failure.
    this._closing = false;
  }

  /** Publishes AUDIO_DEVICE_ERROR (direction = input) so the shell can raise a visible alert.
   *  Used for BOTH the acquire()-time device-open rejection and the async statechange/onerror that
   *  fires when a running input device is lost (typically another app grabbed it exclusively). */
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
  /** True while the input AudioContext is still open — including the brief window
   *  AFTER release() dropped refCount to 0 but teardown()'s inCtx.close() has not
   *  yet resolved (the OS device is still held). The idle-wait polls this so a
   *  measurement takeover blocks until the device is genuinely free, not merely
   *  until the refcount flag flipped. */
  get contextOpen() { return this.inCtx != null || this._closing; }

  /** One getUserMedia attempt at the requested rate, falling back to the device's
   *  own rate. Returns the MediaStream or throws (NotReadableError/AbortError on
   *  an exclusive-hold contention). Split out so acquire() can retry it. */
  async _openStream(c) {
    // stereo so ch1 (calibrated) exists
    const micBase = { deviceId: { exact: c.inDeviceId }, channelCount: { ideal: 2 },
      echoCancellation: false, noiseSuppression: false, autoGainControl: false };
    try {
      return await navigator.mediaDevices.getUserMedia({ audio: { ...micBase, sampleRate: { exact: c.inRate } } });
    } catch (e) {
      // A rate the device won't expose is a constraint problem, not a busy device —
      // retry WITHOUT the rate constraint, but ALWAYS keep deviceId:{exact} so we only
      // ever capture from the CONFIGURED input. Any error from this second form
      // (NotReadable/Abort busy, or OverconstrainedError on a cold/unresolvable id)
      // propagates to acquire()'s retry loop, which retries the SAME device and then
      // surfaces a visible error — it must NEVER silently substitute the default input.
      if (e.name === 'NotReadableError' || e.name === 'AbortError') throw e;
      return await navigator.mediaDevices.getUserMedia({ audio: micBase });
    }
  }

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
      // Bounded retry on NotReadableError/AbortError (the just-stopped modules may still be
      // releasing the OS device — a self-contention, not an external grab); pause + retry before
      // letting the failure fall through to the catch (which raises the visible alert).
      for (let attempt = 1; ; attempt++) {
        try {
          this.stream = await this._openStream(c);
          break;
        } catch (e) {
          // OverconstrainedError included: a cold device (id not yet resolvable,
          // rate not yet exposed) is a transient constraint miss on takeover, not a
          // permanent hold — retry the bounded loop before the visible alert, the
          // same tolerance Java's translateOpenFailure gives format/in-use opens.
          const retriable = e.name === 'NotReadableError' || e.name === 'AbortError'
            || e.name === 'OverconstrainedError';
          if (!retriable || attempt >= OPEN_MAX_ATTEMPTS) throw e;
          this._status(`input device busy (attempt ${attempt}/${OPEN_MAX_ATTEMPTS}) — retrying…`);
          await sleep(OPEN_RETRY_PAUSE_MS);
        }
      }
      const trackRate = this.stream.getAudioTracks()[0].getSettings().sampleRate || c.inRate;
      if (trackRate !== c.inRate) c.inRate = trackRate;   // device gave a different rate → re-pin the analysis to it
      // ALWAYS derive binW / snapped / fundBin here: the scope consumer reads `snapped` (its period
      // + dual-tone freqs) even with NO generator and NO FFT, so computing it only on a rate-mismatch
      // left snapped=undefined → period=NaN → a broken scope trace whenever Scope Record was used alone.
      this._computeAnalysisFreqs();
      this.inCtx = new AudioContext({ sampleRate: trackRate });   // MATCH the stream so it can't rate-mismatch
      // Surface an unexpected device loss to the user: the async 'AudioContext encountered an error
      // from the audio device' fires onerror, and losing the device exclusively (another app grabbed
      // it) drops the context to 'interrupted'/'closed'. Only alert when it wasn't OUR teardown.
      this._closing = false;
      this.inCtx.onerror = () => { if (!this._closing) this._reportDeviceError('AudioContext error'); };
      this.inCtx.addEventListener('statechange', () => {
        if (this._closing || !this.inCtx) return;
        const st = this.inCtx.state;
        if (st === 'interrupted' || st === 'closed') this._reportDeviceError('AudioContext state=' + st);
      });
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

  /** Disconnect → release the mic → suspend → close, mirroring the existing crash-safe capture
   *  teardown (also used on an acquire failure). */
  async teardown() {
    this._refCount = 0;
    this._closing = true;   // suppress the statechange/onerror our own close() will fire
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
    // Java SharedCapture publishes CAPTURE_BATCH_AVAILABLE; the scope + FFT consumers subscribe
    // and read their own cursors. Only the live capture opts in (publishBatch).
    if (this._publishBatch) MessageBus.instance().publish(Events.CAPTURE_BATCH_AVAILABLE);
  }
}
