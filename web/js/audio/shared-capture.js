/*
 * Phonalyser web - the one ref-counted ADC capture, shared across consumers.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of gui/sound/SharedCapture. Owns the ONE live SignalBuffer ring the device
 * writes into, the refcount and the reader cursors - nothing device-specific. The device
 * itself is a CaptureSource (below) the owner injects, exactly as Java's SharedCapture holds
 * an AudioCapture that AudioBackend.openCapture() built for the active backend: Web Audio
 * (./web-audio-capture-source.js) or the QA402/QA403 (../qa40x/qa40x-capture-source.js).
 * There is deliberately ONE SharedCapture, not one per backend.
 *
 * The device opens on the first acquire() (refCount 0->1) and closes on the last release();
 * each consumer (scope / FFT / loopback recording) gets its OWN SignalBufferReader cursor
 * over the shared ring. Everything else - the generator, the consumers' per-batch feeds, the
 * render-time .frc/mains de-embed - lives elsewhere.
 */
import { SignalBuffer } from './signal-buffer.js';
import { SignalBufferReader } from './signal-buffer-reader.js';
import { WebAudioCaptureSource } from './web-audio-capture-source.js';
import { DeviceFailureReason, failureDetailText } from './device-failure-reason.js';
import { MessageBus } from '../bus/message-bus.js';
import { Events } from '../bus/events.js';

/** Spec 5's MARKER kind 1: the next PCM byte is a remote sweep's output sample 0. Named here
 *  rather than imported from js/net so the audio layer keeps no dependency on the net client -
 *  the value is the wire's, and net-proto.js declares the same constant for its own side. */
const MARKER_SWEEP_START = 1;

/** Shared capture ring length, seconds - mirrors SharedCapture.BUFFER_SECONDS. */
export const BUFFER_SECONDS = 22.0;

/**
 * The capture device behind this ring - Java's AudioCapture, one implementation per
 * backend, held (never subclassed) by SharedCapture. open() acquires the device and
 * builds whatever pipeline it needs but dispatches nothing; start() installs the batch
 * sink, which is why the ring always exists before the first batch; stop() silences the
 * sink; close() releases the device. Every call is awaited, so a synchronous
 * implementation may return plain values.
 *
 * @typedef {Object} CaptureSource
 * @property {(deviceId: string, sampleRateHz: number) => Promise<void>} open opens the
 *           device (Java AudioBackend.openCapture(device, rate, depth) + AudioCapture.open())
 * @property {(onBatch: (batch: {l: Float32Array, r: Float32Array, n: number}) => void,
 *           onCaptureEnded: (reason: {name: string, logText: string}) => void)
 *           => Promise<void>} start begins dispatching batches (Java startRecording); `r` is
 *           ch1, the calibrated/attenuated channel every capture path in this app measures.
 *           The two callbacks together are Java's PcmBatchListener: data on one seam, and the
 *           stream's END on the SAME seam (captureEnded) - a source that can tell a dead device
 *           from a quiet one says so here and nowhere else
 * @property {() => Promise<void>} stop stops dispatching (Java stopRecording)
 * @property {() => Promise<void>} close releases the device (Java close)
 * @property {number} sampleRate the ACTUAL captured rate, known only once open; the analysis
 *           is re-pinned to it
 * @property {boolean} isOpen true while the device is still held, INCLUDING a close still
 *           in flight (the measurement idle-wait reads this through contextOpen)
 * @property {?(err: ?Error) => {name: string, i18nKey: string}} classifyFailure OPTIONAL -
 *           WHY this source's open failed, in the shared reason vocabulary (Java
 *           AudioDeviceManager.classifyFailure). Absent = the SPI default, UNKNOWN: a source
 *           that cannot read its own native errors must never make this class parse them.
 * @property {?(err: ?Error) => ?string} refusalText OPTIONAL - WHAT the failure said, when the
 *           source composed a sentence of its own (a bench's spec-4.2 refusal: code, server
 *           message, lock holder). Absent, or null, = there is nothing showable, and the reason
 *           word stands alone. A source whose errors are a DRIVER's does not implement it: raw
 *           native text belongs in the log and nowhere else.
 */

export class SharedCapture {
  /**
   * @param deps {getConfig, status, onBatch, computeAnalysisFreqs, publishBatch, captureSource}
   *   - getConfig: () => the live config object. acquire() reads inDeviceId/inRate and RE-PINS
   *     inRate (mutating the config) to the device's actual rate when they differ.
   *   - status: (text) => void
   *   - onBatch: (d) => void - invoked after each captured batch is staged into the ring so the
   *     owner can drive its consumers (rec tap / scope / FFT) off their own cursors.
   *   - computeAnalysisFreqs: () => void - recomputes the analysis freqs once the real input rate
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
   *  Used for the acquire()-time device-open rejection, which is the failure THIS class turns
   *  into operator language; a running device that is later LOST is not an event at all - it
   *  finishes the ring, and each consumer localizes the end reason off its own reader
   *  (Java SharedCapture.laneFailed: "No event.").
   *
   *  The payload carries the machine-readable reason and the sentence it renders to. The raw
   *  browser text is deliberately NOT in it: a DOMException name says the operator nothing and
   *  belongs in the log alone. */
  _reportDeviceError(reason, failure) {
    MessageBus.instance().publish(Events.AUDIO_DEVICE_ERROR,
      { direction: 'input', reason: reason.name, message: this._openFailureText(reason, failure) });
  }

  /** The sentence a failed capture open leaves on screen. The reason word when the source could
   *  classify the failure; the failure's OWN sentence when it could not and the failure carried
   *  one - a bench refusal names the spec-4.2 code and, for a locked device, its holder, where
   *  the reason has only "reason unknown" to offer, so a code-only refusal would otherwise
   *  reach the operator as that one useless line. */
  _openFailureText(reason, failure) {
    return failureDetailText(reason, this._refusalText(failure));
  }

  /** WHAT the failure said, asked of the source that OWNS it - the twin of {@link
   *  #_classifyFailure}, and the reason no driver text can leak here: a source that composes no
   *  sentence of its own (the Web Audio lane) has no such method, and gets the SPI default. */
  _refusalText(failure) {
    const src = this._source;
    return (src && typeof src.refusalText === 'function') ? src.refusalText(failure) : null;
  }

  /** WHY the open failed, asked of the source that OWNS the native error (Java
   *  AudioDeviceManager.classifyFailure, hoisted out of the try for exactly this). A source
   *  without the optional method gets the SPI default, UNKNOWN - this class never parses a
   *  driver's text itself, which is the classifier living as far from the error as possible. */
  _classifyFailure(err) {
    // An error that already KNOWS what it is keeps its answer. The open can fail before the
    // source exists at all - resolving the configured device against a bench's catalogue is a
    // step of building it - and asking a source that is not there yet turned a refusal which
    // named itself perfectly well into UNKNOWN - the operator read "reason unknown" where the
    // refusal had already said the device was in use by another application.
    if (err && err.reason && typeof err.reason.name === 'string') return err.reason;
    const src = this._source;
    return (src && typeof src.classifyFailure === 'function')
      ? src.classifyFailure(err) : DeviceFailureReason.UNKNOWN;
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
  /** True while the device is open (any consumer holds a reference) AND its stream is still
   *  live. A reference held over a FINISHED stream is not a capture: between a device dying and
   *  the last pane running its stop there is a window where the count is still above zero and
   *  nothing is arriving, and answering "recording" there is how a Record button stays lit over
   *  hardware that is gone (Java SharedCapture.isCapturing). */
  get isCapturing() {
    return this._refCount > 0 && this._buffer != null && !this._buffer.isFinished();
  }
  /** True while the input device is still open - including the brief window
   *  AFTER release() dropped refCount to 0 but teardown()'s close() has not
   *  yet resolved (the OS device is still held). The idle-wait polls this so a
   *  measurement takeover blocks until the device is genuinely free, not merely
   *  until the refcount flag flipped. */
  get contextOpen() { return this._source.isOpen; }

  /** Opens the input device on the first acquire (refCount 0->1) and creates the one shared
   *  SignalBuffer; otherwise just bumps the refcount. Returns a fresh SignalBufferReader cursor
   *  over the live ring (each consumer gets its own), or null on failure. Mirrors
   *  SharedCapture.acquire(). */
  async acquire() {
    if (this._refCount > 0 && this._buffer != null && !this._buffer.isFinished()) {
      this._refCount++;
      return new SignalBufferReader(this._buffer);
    }
    // A lane that has ENDED is not a lane to join. The count can still be above zero here - a
    // holder whose stop has not run yet - and taking the fast path on it would hand out a cursor
    // over a ring nothing writes to any more: a flat trace and a spectrum of silence, presented
    // as a measurement. Whether the stream ended is the BUFFER's answer; nothing here keeps a
    // second copy of it (Java SharedCapture.acquire -> discardFinishedLane).
    await this._discardFinishedLane();
    this._lastStartError = '';   // this attempt owns the field (Java lastStartError = null)
    const c = this._getConfig();
    this._status('opening input context + device...');
    try {
      // The device: its own open() owns the retry / rate / graph mechanics (Java
      // AudioBackend.openCapture(device, rate, depth) + AudioCapture.open()). It reports the
      // ACTUAL rate only once open, which is why the re-pin below happens here and not earlier.
      await this._source.open(c.inDeviceId, c.inRate);
      const deviceRate = this._source.sampleRate || c.inRate;
      if (deviceRate !== c.inRate) c.inRate = deviceRate;   // device gave a different rate -> re-pin the analysis to it
      // ALWAYS derive binW / snapped / fundBin here: the scope consumer reads `snapped` (its period
      // + dual-tone freqs) even with NO generator and NO FFT, so computing it only on a rate-mismatch
      // left snapped=undefined -> period=NaN -> a broken scope trace whenever Scope Record was used alone.
      this._computeAnalysisFreqs();

      // The one shared ring (22 s) the device writes into; each consumer reads it through its own
      // cursor. Created BEFORE start(), which is what installs the batch sink - so no batch can
      // ever land in a half-built ring.
      this._buffer = new SignalBuffer(c.inRate, BUFFER_SECONDS);
      const lane = this._buffer;                  // THIS lane and its OWN once-flag, captured in
      const laneReported = { done: false };       // the closure (Java's per-lane AtomicBoolean):
      await this._source.start((d) => this._onCaptureBatch(d),   // a later lane's flag can neither
        (reason) => this._laneFailed(reason, lane, laneReported));   // swallow nor be swallowed
      // A stream that carries IN-BAND marks (a bench rendering the sweep itself, spec 5):
      // translate the sweep-start mark into a position ON THE RING. The mark is dispatched
      // between two batches by the same source that appends them, so the ring's write position
      // at this callback IS the mark's frame position - the remote sweep consumer seeks its
      // cursor there (kind 1 = generator sweep sample 0).
      if (typeof this._source.setMarkerListener === 'function') {
        this._source.setMarkerListener({
          marker: (kind) => { if (kind === MARKER_SWEEP_START) lane.markSweepStart(); },
          gap: () => { /* the source already confessed it to the fault hub */ },
        });
      }

      // ++ and not = 1: the count is a running balance of acquires against releases, and a lane
      // discarded above may still owe releases from holders that have not stopped yet. Each of
      // those decrements the reference it really took, so nobody is stranded (Java acquire()).
      this._refCount++;
      this.inSampleRate = deviceRate;
      return new SignalBufferReader(this._buffer);
    } catch (e) {
      // The BACKEND that owns the error says what it meant; this class only turns the answer
      // into a sentence (Java openFailureText). The raw browser text goes to the log and
      // nowhere else - "NotReadableError" is not a reason the operator can act on, "the device
      // is in use by another application" is.
      const reason = this._classifyFailure(e);
      console.error(`Capture: failed to start (${reason.name}) - ${e.name} - ${e.message}`, e);
      this._lastStartError = this._openFailureText(reason, e);
      this._status('capture start failed: ' + this._lastStartError);
      // Raise a visible alert for a genuine device-open failure (device busy / unplugged / driver
      // gone). getUserMedia rejects with NotReadableError/AbortError when the input is held by
      // another app in exclusive mode; NotFoundError when it vanished. NotAllowed/SecurityError is
      // a permission denial (not a "device in use" case) - status only, no modal.
      if (e.name !== 'NotAllowedError' && e.name !== 'SecurityError') this._reportDeviceError(reason, e);
      await this._cleanupAfterFailure();
      return null;
    }
  }

  /** Undoes a half-built lane. The reference count is NOT touched: this open never incremented
   *  it, and it may already carry holders of a lane {@link #_discardFinishedLane} just dropped -
   *  zeroing it would strand their releases, and the NEXT lane would then be closed one
   *  reference early (Java SharedCapture.cleanupAfterFailure). Each step is swallowed on its own
   *  so a failing stop() can never skip the close() that actually frees the device. */
  async _cleanupAfterFailure() {
    try { await this._source.stop(); } catch (_) {}
    try { await this._source.close(); } catch (_) {}
    this._buffer = null;
  }

  /** Releases one capture reference; tears the capture graph down when the last reference goes
   *  away. Mirrors SharedCapture.release(). */
  async release() {
    if (this._refCount <= 0) return;
    this._refCount--;
    if (this._refCount > 0) return;
    await this.teardown();
  }

  /**
   * A live capture lane died on its own. The STREAM is ended - and that is the whole report:
   * every consumer reads its reader's terminal state on the next tick, stops itself, and the
   * first to CLAIM the reason shows it to the operator. No event carries the failure.
   *
   * The wake-up is the one web adaptation: Java's consumers sit in awaitAvailable() and
   * finish()'s notifyAll starts their tick immediately, while the web's consumers self-feed off
   * CAPTURE_BATCH_AVAILABLE - which stops arriving the moment the device dies. So the death
   * publishes it ONCE, purely as the wake-up Java's notifyAll is: each consumer then re-checks
   * its OWN cursor and finds the terminal state there, not in the payload.
   *
   * Once per lane: a device that has started failing usually fails on every path at once, and
   * the death must be logged once, not a hundred times. `lane` is THIS lane's buffer, never the
   * current one - a callback that outlives its lane must not end the stream that replaced it,
   * and it must still be able to end its OWN, even after teardown() dropped the reference:
   * a consumer still holding a reader over that ring would otherwise never learn it is dead
   * (Java laneFailed finishes `stream` unconditionally once the per-lane flag flips).
   *
   * @param {{name: string, logText: string}} reason
   * @param {SignalBuffer} lane the buffer this source was writing into
   * @param {{done: boolean}} reported THIS lane's once-flag
   */
  _laneFailed(reason, lane, reported) {
    if (reported.done || lane == null) return;
    reported.done = true;
    console.error('Capture: the input lane failed - ' + (reason ? reason.logText : 'unknown'));
    lane.finish(reason);
    if (this._publishBatch) MessageBus.instance().publish(Events.CAPTURE_BATCH_AVAILABLE);
  }

  /**
   * Lets go of a lane whose stream has ENDED, so a fresh open starts clean. The reference count
   * is deliberately left alone: holders that have not yet run their stop still owe one each, and
   * zeroing it here would strand them. The device goes back BEFORE the new one is opened - a
   * line held across captures is what makes the next one deliver silence (Java
   * discardFinishedLane).
   */
  async _discardFinishedLane() {
    const dead = this._buffer;
    if (dead == null || !dead.isFinished()) return;
    console.warn('Capture: re-opening after the input stream ended - '
      + (dead.getFinishedReason() ? dead.getFinishedReason().logText : 'unknown'));
    try { await this._source.stop(); } catch (_) {}
    try { await this._source.close(); } catch (_) {}
    this._buffer = null;
  }

  /** Silence the batch sink, then release the device - the crash-safe teardown, also used on an
   *  acquire failure (which can leave a half-open device). Each step is swallowed on its own so a
   *  failing stop() can never skip the close() that actually frees the device. */
  async teardown() {
    this._refCount = 0;
    try { await this._source.stop(); } catch (_) {}
    try { await this._source.close(); } catch (_) {}
    this._buffer = null;
  }

  /** Capture-thread -> main-thread hand-off: stage L/R into the shared ring, then notify the owner
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
