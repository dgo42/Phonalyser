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
import { SignalBufferReader, OVERRUN } from './signal-buffer-reader.js';
import { isDualTone } from '../generator/dds-kernel.js';
import { BUFFER_SECONDS } from './shared-capture.js';

// Scope window sizing (faithful to Java ScopeView.drawWaveforms line ~2007):
//   wanted = 2·displaySamples + 2·LANCZOS_PADDING + extraLookback
// so the CAPTURED span is ~3× the DISPLAYED window — the trigger-position slider can
// sweep edge-to-edge and the trace never runs out before the window's far edge.
const LANCZOS_PADDING = 80;                // dsp/lanczos LANCZOS_A·MAX_LANCZOS_DOWNSAMPLE
// Java's MEAS_MAX_SAMPLES is 96000, but Java runs the measurement in a BACKGROUND WORKER.
// The web computes inline in the render loop AND this value also sets the scope window's
// extraLookback (so _applyChannelFilters' HF-LPF + mains comb ran over ~97k samples ×2 each
// paint) — together that froze the main thread (~1 s/frame → 0.9 fps + 3 s click lag). 8192
// is still many periods (≈21 of 1 kHz @384k) so stats stay stable, with a cheap per-frame
// cost. (TODO: a measurement worker to restore the full 96000 window off-thread.)
const SCOPE_MEAS_MAX_SAMPLES = 8192;
// AC-warmup exclusion (Java ScopeMeasurementWorker.AC_WARMUP_NANOS): the first stretch of
// captured samples carries the ADC startup transient and would bias the published DC mean
// (Vmean). Drop sampleRate·AC_WARMUP_NANOS/1e9 samples from the write head before clamping.
const AC_WARMUP_NANOS = 500_000;
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
   * @param deps    {getSnapped} — getSnapped: () => the generator's snapped fundamental (Hz).
   */
  constructor(capture, config, { getSnapped } = {}) {
    this._capture = capture;
    this.config = config;
    this._getSnapped = getSnapped || (() => 0);
    this._scopeOn = false;
    this._scopeReader = null;
    this._measPoolReader = null;
    this.scopeBufL = null;
    this.scopeBufR = null;
    this._scopeFps = 0; this._scopeCount = 0; this._scopeWinT0 = 0;
    this._scopeFrozen = null;
    this._measBufL = null; this._measBufR = null;
    this._measGapL = null; this._measGapR = null;
    this._zoomBufL = null; this._zoomBufR = null;
    this.onScope = null;   // (buf, info) => void — per capture batch
  }

  /** True while the scope is recording. */
  get recording() { return this._scopeOn; }

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
      // The measurement pool consumes the SAME ring through its OWN contiguous cursor
      // (one SignalBufferReader per consumer, exactly like the FFT) so every captured
      // sample is folded into Vmean/Vrms/Vpp once — see readMeasurementGap.
      this._measPoolReader = new SignalBufferReader(this._capture.buffer);
      this._measPoolReader.seekToLatest();
      const len = this._windowLen();
      this.scopeBufL = new Float32Array(len);
      this.scopeBufR = new Float32Array(len);
      this._scopeFps = 0; this._scopeCount = 0; this._scopeWinT0 = 0;
      this._scopeOn = true;
    } else {
      this._scopeOn = false;
      // Frozen last frame: a standalone snapshot the view keeps showing while the
      // device may stay open for the FFT consumer (mirror ScopeController.release).
      this._scopeFrozen = (this._scopeReader != null) ? this._scopeReader.frozenSnapshot() : null;
      this._scopeReader = null;
      this._measPoolReader = null;
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
        dualTone: isDualTone(c.form), f1Hz: snapped, f2Hz: c.tone2Hz,
        bufL: this.scopeBufL, bufR: this.scopeBufR,
      });
    }
  }

  /** Reads a FIXED, long latest-window of both channels from the shared ring for
   *  the scope's Vpp/Vrms/Vmean/Tp/f/Duty measurements (faithful to Java
   *  ScopeMeasurementWorker: measN = min(writePos, MEAS_MAX_SAMPLES), read via
   *  readLatest). The span is independent of the main scope's t/div, so at small
   *  t/div (few displayed periods) the stats still see many periods and stay
   *  stable — only Vpp is robust over a short window, the rest are not. Returns
   *  null when the scope isn't recording or too few samples are captured;
   *  otherwise {bufL, bufR, available, inRate}. readLatest is cursor-stateless. */
  readMeasurementWindow() {
    const reader = this._scopeReader;
    if (!reader) return null;
    const c = this.config;
    const writePos = reader.getWritePos();
    // Drop the ADC startup transient (Java: postWarmupCount = writePos − warmupSamples)
    // before clamping, so it doesn't bias Vmean/DC. Keep the 8192 cap (perf divergence).
    const rate = c.inRate > 0 ? c.inRate : 384000;
    const warmupSamples = Math.trunc(rate * AC_WARMUP_NANOS / 1_000_000_000);
    const postWarmupCount = writePos - warmupSamples;
    if (postWarmupCount < 64) return null;
    const measN = Math.min(postWarmupCount, SCOPE_MEAS_MAX_SAMPLES);
    if (measN < 64) return null;
    if (!this._measBufL || this._measBufL.length < measN) {
      this._measBufL = new Float32Array(measN);
      this._measBufR = new Float32Array(measN);
    }
    const available = reader.readLatest(measN, this._measBufL, this._measBufR);
    if (available < 64) return null;
    // absStart = writePos − available (Java ScopeMeasurementWorker) — feeds the
    // measurement pass's phase-locked mains cancellers.
    return { bufL: this._measBufL, bufR: this._measBufR, available, inRate: c.inRate,
             absStart: writePos - available };
  }

  /** Consumes the CONTIGUOUS run of samples captured since the previous call, through the
   *  measurement pool's OWN forward cursor (mirrors the FFT feed) so the scope's Vmean/Vrms/Vpp
   *  pool folds in every captured sample exactly once — the fractional-cycle DC residual
   *  then cancels within the long averaging window instead of swinging per short snapshot.
   *  Returns {bufL, bufR, count, inRate}, null (nothing new / not recording), or
   *  {overrun:true} when the cursor was lapped (a stall longer than the ring) — the caller
   *  drops its pool and the cursor re-anchors at the latest sample. */
  readMeasurementGap() {
    const reader = this._measPoolReader;
    if (!reader) return null;
    const avail = reader.available();
    if (avail === OVERRUN) { reader.seekToLatest(); return { overrun: true }; }
    if (avail <= 0) return null;
    // Cap catch-up per call so a long stall can't iterate the whole ring in one paint.
    const n = Math.min(avail, this.config.inRate);   // ≤ 1 s of samples
    if (!this._measGapL || this._measGapL.length < n) {
      this._measGapL = new Float32Array(n);
      this._measGapR = new Float32Array(n);
    }
    const got = reader.read(n, this._measGapL, this._measGapR);
    if (got === OVERRUN) { reader.seekToLatest(); return { overrun: true }; }
    return { bufL: this._measGapL, bufR: this._measGapR, count: got, inRate: this.config.inRate };
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
    this._measPoolReader = (r && this._capture.buffer) ? new SignalBufferReader(this._capture.buffer) : null;
    if (this._measPoolReader) this._measPoolReader.seekToLatest();
    if (r) { const len = this._windowLen(); this.scopeBufL = new Float32Array(len); this.scopeBufR = new Float32Array(len); }
    else this._scopeOn = false;
    return r != null;
  }
}
