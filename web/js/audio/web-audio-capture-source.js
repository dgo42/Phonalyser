/*
 * Phonalyser web — the Web Audio (getUserMedia) capture source.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The device half of gui/sound/SharedCapture, extracted UNCHANGED: this is the web's
 * AudioCapture implementation for the WEB_AUDIO backend. In Java, SharedCapture holds an
 * AudioCapture that AudioBackend.openCapture(device, rate, depth) built for the active
 * backend — a WasapiRecorder / WdmksRecorder / JavaSoundRecorder / CoreAudioRecorder;
 * here it holds THIS, or the QA40x source (js/qa40x/qa40x-capture-source.js) behind the
 * same contract (the CaptureSource typedef in shared-capture.js). SharedCapture keeps
 * only the refcount, the one ring and the reader cursors — it is not forked per backend.
 *
 * Everything here — the bounded open retry and its self-contention reasoning, the rate
 * fallback that never substitutes the default input, the ACTUAL-rate context match, the
 * crash-safe teardown order, the `closing` flag — is moved code, not new code. The
 * comments say which bench failure each one paid for; read them before changing any of it.
 */
import { MessageBus } from '../bus/message-bus.js';
import { Events } from '../bus/events.js';

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

export class WebAudioCaptureSource {

  /** @type {(text: string) => void} */
  #status;
  /** The input AudioContext — null while closed. */
  #ctx = null;
  /** @type {?MediaStream} */
  #stream = null;
  #capNode = null;
  #silentNode = null;
  #srcNode = null;
  /** True only while WE are tearing the context down, so the context's own
   *  'closed'/'suspended' statechange during teardown is not misread as an
   *  unexpected device failure. */
  #closing = false;

  /**
   * @param deps {status}
   *   - status: (text) => void — surfaces the retry progress of a contended open.
   */
  constructor({ status } = {}) {
    this.#status = status || (() => {});
  }

  /** The ACTUAL captured rate: the rate the input context runs at, which open()
   *  matched to the stream's own rate — so the two can never disagree. 0 while
   *  closed. SharedCapture re-pins the analysis to this. */
  get sampleRate() { return this.#ctx ? this.#ctx.sampleRate : 0; }

  /** True while the OS device is still held — INCLUDING the window after stop()
   *  where close() has not yet resolved. SharedCapture.contextOpen (and through it
   *  the measurement idle-wait) reads this: a takeover must block until the device
   *  is genuinely free, not merely until a refcount flag flipped. */
  get isOpen() { return this.#ctx != null || this.#closing; }

  /**
   * Opens the input device and builds the capture graph, WITHOUT yet dispatching
   * batches (start() installs the sink). Rejects with the getUserMedia error —
   * NotReadableError/AbortError for an exclusive hold, NotFoundError when the
   * device vanished, NotAllowed/SecurityError on a permission denial — which
   * SharedCapture classifies and reports.
   *
   * @param {string} deviceId the configured input device id (never substituted)
   * @param {number} requestedRateHz the wanted rate; the device may impose its own
   */
  async open(deviceId, requestedRateHz) {
    // INPUT (ADC): open the stream FIRST, then create the capture context at the stream's ACTUAL
    // rate. A MediaStreamSource whose stream rate ≠ the context rate raises the async "AudioContext
    // encountered an error from the audio device" and silently kills capture. Try the exact rate,
    // then fall back to whatever the device gives if it won't expose it via getUserMedia.
    // Bounded retry on NotReadableError/AbortError (the just-stopped modules may still be
    // releasing the OS device — a self-contention, not an external grab); pause + retry before
    // letting the failure propagate to acquire()'s catch (which raises the visible alert).
    for (let attempt = 1; ; attempt++) {
      try {
        this.#stream = await this.#openStream(deviceId, requestedRateHz);
        break;
      } catch (e) {
        // OverconstrainedError included: a cold device (id not yet resolvable,
        // rate not yet exposed) is a transient constraint miss on takeover, not a
        // permanent hold — retry the bounded loop before the visible alert, the
        // same tolerance Java's translateOpenFailure gives format/in-use opens.
        const retriable = e.name === 'NotReadableError' || e.name === 'AbortError'
          || e.name === 'OverconstrainedError';
        if (!retriable || attempt >= OPEN_MAX_ATTEMPTS) throw e;
        this.#status(`input device busy (attempt ${attempt}/${OPEN_MAX_ATTEMPTS}) — retrying…`);
        await sleep(OPEN_RETRY_PAUSE_MS);
      }
    }
    const track = this.#stream.getAudioTracks()[0];
    // WATCH THE TRACK, not only the context. Unplugging a USB interface does NOT necessarily
    // disturb the AudioContext: Chrome can keep the track alive and hand out SILENCE, so the app
    // went on recording a flat line with the captures-per-second counter ticking — a measurement
    // instrument reading nothing while claiming to read (maintainer, 2026-07-26). The track is where
    // removal shows: 'ended' when the device goes away, 'mute' while it delivers no media.
    track.addEventListener('ended', () => {
      if (!this.#closing) this.#reportDeviceError('input device removed (track ended)');
    });
    track.addEventListener('mute', () => {
      if (!this.#closing) this.#reportDeviceError('input device delivers no audio (track muted)');
    });
    const trackRate = track.getSettings().sampleRate || requestedRateHz;
    this.#ctx = new AudioContext({ sampleRate: trackRate });   // MATCH the stream so it can't rate-mismatch
    // Surface an unexpected device loss to the user: the async 'AudioContext encountered an error
    // from the audio device' fires onerror, and losing the device exclusively (another app grabbed
    // it) drops the context to 'interrupted'/'closed'. Only alert when it wasn't OUR teardown.
    this.#closing = false;
    this.#ctx.onerror = () => { if (!this.#closing) this.#reportDeviceError('AudioContext error'); };
    this.#ctx.addEventListener('statechange', () => {
      if (this.#closing || !this.#ctx) return;
      const st = this.#ctx.state;
      if (st === 'interrupted' || st === 'closed') this.#reportDeviceError('AudioContext state=' + st);
    });
    await this.#ctx.audioWorklet.addModule(new URL('./worklets/capture-processor.js', import.meta.url));
    // Keep references so close() can disconnect the whole capture graph BEFORE closing the
    // context (closing with worklets still wired can crash the renderer).
    this.#capNode = new AudioWorkletNode(this.#ctx, 'capture-processor');
    this.#silentNode = this.#ctx.createGain(); this.#silentNode.gain.value = 0;
    this.#srcNode = this.#ctx.createMediaStreamSource(this.#stream);
    this.#srcNode.connect(this.#capNode).connect(this.#silentNode).connect(this.#ctx.destination);
  }

  /**
   * Starts dispatching captured batches. Called only after the ring exists, so a
   * batch can never land in a half-built consumer.
   *
   * @param {(batch: {l: Float32Array, r: Float32Array, n: number}) => void} onBatch
   *        the capture-worklet hand-off; r is ch1, the calibrated/attenuated channel
   */
  async start(onBatch) {
    if (this.#capNode == null) throw new Error('Call open() before start()');
    this.#capNode.port.onmessage = (e) => onBatch(e.data);
    if (this.#ctx.state === 'suspended') await this.#ctx.resume();
  }

  /** Silences the capture port so no more batches dispatch into a half-torn ring. */
  async stop() {
    this.#silencePort();
  }

  /** Disconnect the graph → release the mic → suspend → close, the crash-safe
   *  teardown order (also used on an open failure, so every step is null-safe and
   *  swallows its own failure — a wedged device must not block the teardown). */
  async close() {
    this.#silencePort();          // idempotent: close() alone must still tear down in order
    for (const n of [this.#srcNode, this.#capNode, this.#silentNode]) {
      try { if (n) n.disconnect(); } catch (_) {}
    }
    this.#srcNode = this.#capNode = this.#silentNode = null;
    try { if (this.#stream) this.#stream.getTracks().forEach(t => t.stop()); } catch (_) {}
    this.#stream = null;
    try { if (this.#ctx) { await this.#ctx.suspend().catch(() => {}); await this.#ctx.close(); } } catch (_) {}
    this.#ctx = null;
  }

  /** One getUserMedia attempt at the requested rate, falling back to the device's
   *  own rate. Returns the MediaStream or throws (NotReadableError/AbortError on
   *  an exclusive-hold contention). Split out so open() can retry it. */
  async #openStream(deviceId, rateHz) {
    // stereo so ch1 (calibrated) exists
    const micBase = { deviceId: { exact: deviceId }, channelCount: { ideal: 2 },
      echoCancellation: false, noiseSuppression: false, autoGainControl: false };
    try {
      return await navigator.mediaDevices.getUserMedia({ audio: { ...micBase, sampleRate: { exact: rateHz } } });
    } catch (e) {
      // A rate the device won't expose is a constraint problem, not a busy device —
      // retry WITHOUT the rate constraint, but ALWAYS keep deviceId:{exact} so we only
      // ever capture from the CONFIGURED input. Any error from this second form
      // (NotReadable/Abort busy, or OverconstrainedError on a cold/unresolvable id)
      // propagates to open()'s retry loop, which retries the SAME device and then
      // surfaces a visible error — it must NEVER silently substitute the default input.
      if (e.name === 'NotReadableError' || e.name === 'AbortError') throw e;
      return await navigator.mediaDevices.getUserMedia({ audio: micBase });
    }
  }

  /** Raises the `closing` guard (so our own teardown is never reported as a device
   *  error) and silences the port. Deliberately NOT cleared here: it stays raised
   *  until the next open(), exactly as the pre-extraction SharedCapture left it, so
   *  isOpen/contextOpen keeps the same value it had before this seam existed. */
  #silencePort() {
    this.#closing = true;
    if (this.#capNode) { try { this.#capNode.port.onmessage = null; } catch (_) {} }
  }

  /** Publishes AUDIO_DEVICE_ERROR (direction = input) for an ASYNC device loss —
   *  the statechange/onerror that fires when a running input device is taken away
   *  (typically another app grabbed it exclusively). The open-time rejection is
   *  reported by SharedCapture instead, which is what classifies it. */
  #reportDeviceError(detail) {
    MessageBus.instance().publish(Events.AUDIO_DEVICE_ERROR, { direction: 'input', detail });
  }
}
