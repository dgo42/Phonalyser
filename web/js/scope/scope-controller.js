/*
 * Phonalyser web — the oscilloscope capture consumer.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of gui/scope/ScopeController. A "latest window" consumer of the shared capture:
 * it acquires/releases its OWN SignalBufferReader cursor over the ring and, per captured batch,
 * copies the newest scope-window samples of both channels and emits onScope. Also serves the long
 * measurement window/gap reads + the 1 s zoomed-overview read. The generator's `snapped`
 * fundamental comes in via an injected getter (it reads the generator without owning it). No DOM.
 */
import { isDualTone } from '../generator/dds-kernel.js';
import { BUFFER_SECONDS } from '../audio/shared-capture.js';
import { MessageBus } from '../bus/message-bus.js';
import { Events } from '../bus/events.js';
import { OscMeasClient } from './osc-meas-client.js';

// Scope window sizing (faithful to Java ScopeView.drawWaveforms line ~2007):
//   wanted = 2·displaySamples + 2·LANCZOS_PADDING + extraLookback
// so the CAPTURED span is ~3× the DISPLAYED window — the trigger-position slider can
// sweep edge-to-edge and the trace never runs out before the window's far edge.
const LANCZOS_PADDING = 80;                // dsp/lanczos LANCZOS_A·MAX_LANCZOS_DOWNSAMPLE
// The scope DISPLAY window's extra lookback (samples) beyond 2·displaySamples so the
// trigger-position slider can sweep edge-to-edge. The long-window Vpp/Vrms/Vmean/Tp/f/Duty
// measurement no longer reads a window from here — it runs in the osc-meas Web Worker off
// its OWN gapless ring reader (the measurement stream, owned by this controller's
// OscMeasClient), integrating oscMeasurementAverageSeconds of samples off the render thread.
const SCOPE_MEAS_MAX_SAMPLES = 8192;
const SCOPE_DIVISIONS_X = 10;              // Java ScopeView.DIVISIONS_X
const SCOPE_MIN_LEN = 1 << 15;            // floor for the scope window (Java leftBuf grow-only)
// Ceiling for the scope window: feedScope runs PER CAPTURE BATCH (far more often than
// the 60 fps Java paint), so an unbounded multi-million-sample copy per batch at a very
// large t/div would stall capture. The cap must still admit the largest displayed window
// (10 s/div × DIVISIONS_X = 10 s span) PLUS the ~2× trigger headroom, else the trace
// flat-lines at large t/div (500 ms–1 s/div) when 2·displaySamples exceeds it. 1<<23 ≈
// 8.4 M samples covers a full 10 s window @ 384 k; the per-batch copy stays bounded and
// the actual read is always re-clamped to the shared ring capacity below.
const SCOPE_MAX_LEN = 1 << 23;

export class ScopeController {
  /**
   * @param capture the SharedCapture instance.
   * @param config  the SHARED engine config object.
   * @param deps    {getSnapped, getSnapped2} — getSnapped: () => the generator's snapped tone-1
   *                fundamental (Hz); getSnapped2: () => its snapped dual-tone second frequency (Hz).
   *                Both must reflect the SAME snap-state the generator emits — Java ScopeView snaps
   *                BOTH tones via FftBinSnap.snapIfEnabled(DUAL_TONE) before reconstructing the beat.
   */
  constructor(capture, config, { getSnapped, getSnapped2 } = {}) {
    this._capture = capture;
    this.config = config;
    this._getSnapped = getSnapped || (() => 0);
    this._getSnapped2 = getSnapped2 || (() => 0);
    this._scopeOn = false;
    // Whether the scope was recording when stopCaptureForPrefs() recorded it, so
    // startCaptureForPrefs() restarts exactly that (Java ScopePane.captureWasRunningForPrefs).
    this._captureWasRunningForPrefs = false;
    this._scopeReader = null;
    this.scopeBufL = null;
    this.scopeBufR = null;
    this._scopeFps = 0; this._scopeCount = 0; this._scopeWinT0 = 0;
    this._zoomBufL = null; this._zoomBufR = null;
    this.onScope = null;   // (buf, info) => void — per capture batch
    // The scope MEASUREMENT stream (gui/scope/ScopeMeasurementWorker): its OWN gapless
    // forward-reader consumer of the shared ring, running the per-channel filter + whole-
    // period pipeline OFF the render thread in a Web Worker. Owned here so its reader lives
    // exactly as long as the scope recording; the pane injects the prefs->params provider +
    // the result sink (setMeasParamsProvider / setMeasResultSink) — the controller has the
    // capture, the pane has the prefs.
    this._measClient = new OscMeasClient(
      this._capture,
      () => (this._measParamsProvider ? this._measParamsProvider() : null),
      (r) => { if (this._measResultSink) this._measResultSink(r); });
    this._measParamsProvider = null;
    this._measResultSink = null;
    // Self-feed off the LIVE capture: Java consumers subscribe to CAPTURE_BATCH_AVAILABLE and read
    // their own cursor — no central dispatcher pumps us. Fires only for the live capture (the
    // measurement capture doesn't publish), and only feeds while this scope is recording.
    MessageBus.instance().subscribe(Events.CAPTURE_BATCH_AVAILABLE, () => {
      if (this._scopeOn) this.feedScope();
    });
  }

  /** True while the scope is recording. */
  get recording() { return this._scopeOn; }

  /** The generator's snapped tone-1 / dual-tone tone-2 fundamentals (Hz) — the same
   *  snap-state feedScope stamps into info.f1Hz/f2Hz, exposed so the measurement params
   *  provider (owned by the pane, which has the prefs) can seed the dual-tone refine. */
  get snapped() { return this._getSnapped(); }
  get snapped2() { return this._getSnapped2(); }

  /** Turns the scope's Record state on/off: acquires/releases the shared capture
   *  and, while on, drives onScope off its own latest-window cursor. Returns the
   *  resulting on-state (false if the acquire failed) so the caller can reconcile
   *  its own flag / LED. */
  async setRecording(on) {
    if (on === this._scopeOn) return this._scopeOn;
    if (on) {
      const reader = await this._capture.acquire();
      if (!reader) return false;
      this._scopeReader = reader;
      const len = this._windowLen();
      this.scopeBufL = new Float32Array(len);
      this.scopeBufR = new Float32Array(len);
      this._scopeFps = 0; this._scopeCount = 0; this._scopeWinT0 = 0;
      this._scopeOn = true;
      // Start the measurement stream alongside the scope (its own forward reader + worker).
      await this._measClient.start();
    } else {
      this._scopeOn = false;
      // Stop the measurement stream first (releases its own capture reference).
      await this._measClient.stop();
      this._scopeReader = null;
      await this._capture.release();
    }
    return this._scopeOn;
  }

  /** Captured scope-window length in samples (faithful to Java drawWaveforms):
   *  2·displaySamples + 2·LANCZOS_PADDING + extraLookback, where displaySamples =
   *  t/div·DIVISIONS_X·inRate and extraLookback = min(inRate, MEAS_MAX_SAMPLES). The
   *  captured span is therefore ~3× the displayed window so the trigger-position
   *  slider can sweep edge-to-edge without the trace running out. Floored at
   *  SCOPE_MIN_LEN and capped at the shared ring capacity. */
  _windowLen() {
    const c = this.config;
    const rate = c.inRate > 0 ? c.inRate : 384000;
    const displaySamples = Math.round((c.scopeTimePerDiv || 0.001) * SCOPE_DIVISIONS_X * rate);
    const extraLookback = Math.min(rate, SCOPE_MEAS_MAX_SAMPLES);
    let wanted = 2 * displaySamples + 2 * LANCZOS_PADDING + extraLookback;
    if (wanted < SCOPE_MIN_LEN) wanted = SCOPE_MIN_LEN;
    if (wanted > SCOPE_MAX_LEN) wanted = SCOPE_MAX_LEN;
    const cap = this._capture.buffer ? this._capture.buffer.getCapacity() : Math.round(rate * BUFFER_SECONDS);
    if (wanted > cap) wanted = cap;
    return wanted;
  }

  /** Per-batch: copy the latest scope-window samples of BOTH channels and emit onScope.
   *  Publishes the R channel (ch1 = the measured/primary channel) as `buf` per the
   *  scope DATA CONTRACT, with both channels carried on info.bufL / info.bufR so the
   *  view can draw the two traces independently. */
  feedScope() {
    const reader = this._scopeReader;
    if (!reader) return;
    // Re-size the scope window when t/div changed (grow-only, mirroring Java's
    // leftBuf/rightBuf grow-only reallocation) so the captured span stays ~3× the
    // displayed window at the current t/div — the trace never runs out early.
    const len = this._windowLen();
    if (!this.scopeBufL || this.scopeBufL.length < len) {
      this.scopeBufL = new Float32Array(len);
      this.scopeBufR = new Float32Array(len);
    }
    // Read exactly `len` (the current t/div's wanted span), not the grow-only buffer
    // length — so zooming back IN shrinks the per-batch copy again (Java sizes the read
    // to `wanted` each paint). `available` is the ACTUAL filled count (< len until the
    // ring fills); the view uses it, not the buffer length, so it never draws the
    // unfilled tail (stale / zero) as signal.
    // Java reads readEndingAt(viewEndAbs, wanted); for the pure-live tip viewEndAbs is the
    // write head, so readLatest(len) is equivalent (both end at the newest sample).
    const available = reader.readLatest(len, this.scopeBufL, this.scopeBufR);
    // Absolute index of the window's first sample in the capture stream (Java
    // bufStartAbs) — the scope's phase-locked mains cancellers advance their
    // mains phase by its delta across paints.
    const absStart = reader.getWritePos() - available;
    const t = performance.now();
    this._scopeCount++; if (!this._scopeWinT0) this._scopeWinT0 = t;
    if (t - this._scopeWinT0 >= 1000) { this._scopeFps = this._scopeCount * 1000 / (t - this._scopeWinT0); this._scopeCount = 0; this._scopeWinT0 = t; }
    if (this.onScope) {
      const c = this.config;
      const snapped = this._getSnapped();
      // peakVolts maps a normalised ±1.0 sample to the ADC full-scale swing
      // (adcFsVoltageRms·√2) so the scope measurements read in volts.
      this.onScope(this.scopeBufR, {
        scopeFps: this._scopeFps, period: c.inRate / snapped,
        inRate: c.inRate, snapped, available, absStart,
        peakVolts: c.adcFsVoltageRms * Math.SQRT2,
        dualTone: isDualTone(c.form), f1Hz: snapped, f2Hz: this._getSnapped2(),
        bufL: this.scopeBufL, bufR: this.scopeBufR,
      });
    }
  }

  /** Reads exactly the latest ONE SECOND of both channels from the shared ring
   *  for the condensed overview strip (faithful to Java ZoomedView.drawWaveforms:
   *  displaySamples = sampleRate, read straight from the ring via readEndingAt).
   *  The span is FIXED at 1 s regardless of the main scope's t/div, so the zoomed
   *  view's horizontal scale never changes with the main t/div. Returns null when
   *  the scope isn't recording; otherwise {bufL, bufR, available, displaySamples,
   *  inRate}. readEndingAt is cursor-stateless, so reusing the scope reader here
   *  doesn't disturb feedScope's latest-window read. */
  readZoomedWindow() {
    const reader = this._scopeReader;
    if (!reader) return null;
    const c = this.config;
    const rate = c.inRate > 0 ? c.inRate : 384000;
    const displaySamples = rate;                 // exactly 1 second (Java ZoomedView)
    const wanted = displaySamples + 2 * LANCZOS_PADDING;
    if (!this._zoomBufL || this._zoomBufL.length < wanted) {
      this._zoomBufL = new Float32Array(wanted);
      this._zoomBufR = new Float32Array(wanted);
    }
    const viewEndAbs = reader.getWritePos();     // latest sample (no back-offset live)
    const available = reader.readEndingAt(viewEndAbs, wanted, this._zoomBufL, this._zoomBufR);
    if (available < 2) return null;
    return { bufL: this._zoomBufL, bufR: this._zoomBufR, available, displaySamples, inRate: rate };
  }

  /** Re-acquires the shared capture + rebuilds this consumer's readers after a device reopen
   *  (mirror the Java SignalBufferReader re-attach). Returns true when the re-acquire succeeded. */
  async reattach() {
    const r = await this._capture.acquire();
    this._scopeReader = r;
    if (r == null) {
      // The device did not come back. Run the REAL stop — the same teardown setRecording(false)
      // does — because _measClient.reattach() re-acquires unconditionally and would RE-OPEN the
      // device this consumer just disowned, after which nothing could release it (setRecording(false)
      // early-returns on !_scopeOn and never reaches the client's stop). On a QA40x that left
      // interface 0 claimed for the life of the page.
      //
      // Clearing the flag ALONE is not enough, and was the first attempt at this fix: the trace
      // froze but the scope never stopped — the Record LED stayed lit and the measurement worker
      // kept running, which is what made the app crawl after a while (maintainer, 2026-07-26).
      this._scopeOn = false;
      await this._measClient.stop();     // stops the worker and releases its own capture reference
      this._scopeReader = null;
      await this._capture.release();     // guarded at zero, so this is safe with no ref held
      // Tell the pane, which owns the Record LED and the control gating — reattach() is driven by
      // the ENGINE (reopenCaptureDevice), so nothing else reconciles the UI with the engine's state.
      MessageBus.instance().publish(Events.SCOPE_RECORDING_STOPPED);
      return false;
    }
    const len = this._windowLen();
    this.scopeBufL = new Float32Array(len);
    this.scopeBufR = new Float32Array(len);
    // Re-anchor the measurement stream onto the fresh ring too (mirror the SignalBufferReader
    // re-attach; the client re-acquires its own reference + resets the worker's stream state).
    await this._measClient.reattach();
    return true;
  }

  /** Preferences-dialog audio-config bracket (Java ScopePane.stopCaptureForPrefs /
   *  startCaptureForPrefs): the scope OWNS whether it was live, so the engine's
   *  two-phase bounce restarts exactly what was running. stopCaptureForPrefs REALLY
   *  stops — setRecording(false) stops the measurement client AND releases this
   *  consumer's shared-capture ref, so the one device closes on the LAST release
   *  (refcount), not a central hard teardown. startCaptureForPrefs REALLY restarts —
   *  setRecording(true) re-acquires the ref (reopening the device at the committed
   *  config on the first re-acquire) and rebuilds the rate-dependent buffers. */
  async stopCaptureForPrefs() {
    this._captureWasRunningForPrefs = this._scopeOn;
    if (this._scopeOn) await this.setRecording(false);
  }
  async startCaptureForPrefs() {
    if (!this._captureWasRunningForPrefs) return;
    if (await this.setRecording(true)) return;
    // The restart FAILED — the committed device could not be opened (unplugged, held by another
    // app, a rate it will not grant). setRecording(true) already left this consumer off and holding
    // nothing, so the state is correct; what is missing is that the PANE still shows the scope as
    // running, because it owns the Record LED and nothing here had told it. That is what "the trace
    // doesn't redraw, but the scope doesn't stop" was (maintainer, 2026-07-26): a stopped controller
    // behind a lit LED. The same publish the reattach failure uses, so both routes reconcile the UI.
    MessageBus.instance().publish(Events.SCOPE_RECORDING_STOPPED);
  }

  /** Injects the prefs->publish-params provider the measurement client polls each batch
   *  (the pane owns the prefs; the controller owns the client). Returns null to skip a batch. */
  setMeasParamsProvider(fn) { this._measParamsProvider = fn; }

  /** Injects the sink for each worker publish {resultL,resultR,leftMeanNorm,rightMeanNorm}
   *  (the pane wires it to the view's publishMeasurement). */
  setMeasResultSink(fn) { this._measResultSink = fn; }

  /** Re-anchors the measurement stream + drops the worker's collection / filter state
   *  (measurement-channel switch, stats reset). */
  resetMeasurement() { this._measClient.reset(); }
}
