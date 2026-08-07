/*
 * Phonalyser web - main-thread client for the scope MEASUREMENT web worker.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The measurement path's OWN consumer of the shared capture ring: it holds a dedicated
 * FORWARD SignalBufferReader (the FFT-consumer pattern, fft-controller.js) and, per
 * CAPTURE_BATCH_AVAILABLE, reads the contiguous gap of BOTH channels exactly once - gapless,
 * every captured sample delivered once, no breaks - into fresh transferable buffers and posts
 * them to the osc-meas worker. The worker carries the streaming filter state batch to batch and
 * publishes {resultL, resultR, leftMeanNorm, rightMeanNorm} on its own ~100 ms cadence, which
 * this client forwards to the view via onResult. On OVERRUN (the writer lapped the cursor) it
 * seekToLatest()es and tells the worker to reset its stream state so accumulation restarts clean.
 *
 * Lifecycle (start/stop/reattach/reset) is driven by the ScopeController so the measurement
 * stream lives exactly as long as the scope recording (and re-anchors on a device reopen).
 */
import { MessageBus } from '../bus/message-bus.js';
import { Events } from '../bus/events.js';
import { OVERRUN } from '../audio/signal-buffer-reader.js';
import { AmplitudeHistogram } from './amplitude-histogram.js';

/**
 * Micro-bin geometry for the amplitude histograms, fixed and independent of the drawn bar count.
 * The `oscHistogramBins` preference is DISPLAY resolution only - the renderer aggregates these
 * micro-bins down to it - so changing that preference re-draws the collected data instead of
 * discarding it, which rebuilding the accumulator would do.
 */
const HISTOGRAM_ACCUMULATOR_BARS = 200;

export class OscMeasClient {
  /**
   * @param {object} capture      the SharedCapture - the client acquires its OWN forward reader.
   * @param {() => object|null} getParams  returns the current publish params
   *   { sampleRate, peakVoltsL, peakVoltsR, avgSeconds, L:{mainsMode,dual,f1Hz,f2Hz}, R:{...} }
   *   or null to skip this batch (measurement off / no signal).
   * @param {(result:object)=>void} onResult  invoked with {resultL,resultR,leftMeanNorm,rightMeanNorm}.
   */
  constructor(capture, getParams, onResult) {
    this._capture = capture;
    this._getParams = getParams;
    this._onResult = onResult;
    this._reader = null;
    this._readL = null;
    this._readR = null;
    this._worker = null;
    // Self-feed off the LIVE capture, exactly like the FFT consumer: subscribe once, gate
    // on our own reader (null while stopped) so a measurement/FreqResp sweep never triggers us.
    this._onBatch = () => this._feed();
    MessageBus.instance().subscribe(Events.CAPTURE_BATCH_AVAILABLE, this._onBatch);
    // Amplitude histograms, one per channel, binned HERE - in the capture path, on the raw window,
    // BEFORE the worker's DC removal / LPF / mains comb. Binning after any of those smears one
    // distribution into two visible lobes while the estimate is still settling: the same voltage
    // lands in different bins as the filter moves. The DC offset is resolved at PAINT time instead,
    // by centring the plot on the distribution's own mean.
    this._histL = new AmplitudeHistogram(HISTOGRAM_ACCUMULATOR_BARS);
    this._histR = new AmplitudeHistogram(HISTOGRAM_ACCUMULATOR_BARS);
    // Running peak per channel over the measurement-average window, which is what the range is
    // sized from - never a single block's peak. One block's peak is a random draw from the
    // signal's tail; on noise it wanders enough to escape the range nearly every pass, and every
    // escape restarts the distribution. The window TUMBLES: at each boundary the peak restarts
    // from the current block, so a quietened signal is reflected within one window - and since
    // shrinking never re-ranges, that costs no counts.
    this._peakWindow = { L: 0, R: 0, startedAtMs: 0 };
  }

  /**
   * Clears both distributions - the histogram window's own reset button, and everything that
   * resets the scope's running statistics.
   *
   * Applied immediately rather than deferred as a request: JS has one thread, so no pass can be
   * mid-binning, and applying it here means a reset while the scope is STOPPED still lands (a
   * deferred request would wait for a pass that never comes).
   */
  resetHistograms() {
    this._histL.reset();
    this._histR.reset();
    this._peakWindow = { L: 0, R: 0, startedAtMs: 0 };
  }

  /**
   * A detached copy of one channel's distribution for the renderer, or null before anything was
   * collected. The paint runs on the frame cadence and the binning on the measurement cadence, so
   * the snapshot is what stops a distribution changing under a half-drawn frame.
   *
   * @param {string} channel 'L' or 'R'
   * @returns {?AmplitudeHistogram}
   */
  histogramSnapshot(channel) {
    const h = (channel === 'R') ? this._histR : this._histL;
    return h.getTotal() > 0 ? h.snapshot() : null;
  }

  /** Acquires the dedicated forward reader + spins up the worker. The reader anchors at
   *  "now" so the stream starts contiguous with the live write head. Returns true on success. */
  async start() {
    if (this._reader) return true;
    const reader = await this._capture.acquire();
    if (!reader) return false;
    this._reader = reader;
    this._reader.seekToLatest();   // contiguous forward stream anchors at "now"
    this._worker = new Worker(new URL('./osc-meas-worker.js', import.meta.url), { type: 'module' });
    this._worker.onmessage = (e) => { if (this._onResult) this._onResult(e.data); };
    this._worker.onerror = (ev) => console.error('osc-meas-worker', ev.message);
    return true;
  }

  /** Tears down the worker + releases the forward reader (scope record stop). */
  async stop() {
    if (this._worker) { try { this._worker.terminate(); } catch (_) { /* ignore */ } this._worker = null; }
    if (this._reader) { this._reader = null; await this._capture.release(); }
  }

  /** Re-acquires the forward reader after a device reopen (mirror ScopeController.reattach). */
  async reattach() {
    if (!this._worker) return;   // not running -> nothing to reattach
    const r = await this._capture.acquire();
    this._reader = r;
    if (r) { r.seekToLatest(); this._resetWorker(); }
  }

  /** Drops the worker's stream state so the collection window + adaptive filters restart
   *  clean (a measurement-channel switch / stats reset), and re-anchors the reader at "now". */
  reset() {
    if (this._reader) this._reader.seekToLatest();
    this._resetWorker();
  }

  _resetWorker() { if (this._worker) this._worker.postMessage({ type: 'reset' }); }

  /** Per capture batch: read the NEW contiguous gap of BOTH channels off the cursor and post
   *  it to the worker. OVERRUN -> re-anchor + worker stream reset (a torn window would smear the
   *  collection). Dropped when measurement is off (getParams returns null). */
  _feed() {
    const reader = this._reader, worker = this._worker;
    if (!reader || !worker) return;
    const params = this._getParams ? this._getParams() : null;
    if (!params) { reader.seekToLatest(); return; }   // measurement off -> don't backlog the cursor
    let avail = reader.available();
    if (avail === OVERRUN) { reader.seekToLatest(); this._resetWorker(); return; }
    if (avail <= 0) return;
    // Fresh transferables per post (the worker takes ownership; a recycled buffer can't be
    // reused across an in-flight transfer). Read the contiguous gap of both channels at once.
    const bufL = new Float32Array(avail);
    const bufR = new Float32Array(avail);
    const n = reader.read(avail, bufL, bufR);
    if (n === OVERRUN) { reader.seekToLatest(); this._resetWorker(); return; }
    if (n <= 0) return;
    // BIN FIRST, while the samples are still exactly as captured, and before the buffers are
    // transferred to the worker (a transferred ArrayBuffer is detached - unreadable from here).
    // The reader is a FORWARD cursor handing back only the contiguous NEW gap, so each sample is
    // binned exactly once; a re-read window would count the overlap ~20× at this cadence and bias
    // the distribution toward whatever the overlap covered.
    this._binHistograms(bufL, bufR, n, params);
    worker.postMessage({
      type: 'feed', bufL, bufR, n,
      sampleRate: params.sampleRate,
      peakVoltsL: params.peakVoltsL, peakVoltsR: params.peakVoltsR,
      avgSeconds: params.avgSeconds,
      L: params.L, R: params.R,
    }, [bufL.buffer, bufR.buffer]);
  }

  /**
   * One binning pass over the raw gap: peak -> windowed peak -> range -> count.
   *
   * Missing passes under load is fine - what is collected stays an unbiased sub-sample of the
   * signal. It does mean the total is NOT a census of captured samples, so it must never be
   * presented as one.
   *
   * @param {Float32Array} bufL left channel, normalised
   * @param {Float32Array} bufR right channel, normalised
   * @param {number} n samples valid in each buffer
   * @param {object} params the publish params, for the averaging window length
   */
  _binHistograms(bufL, bufR, n, params) {
    let peakL = 0, peakR = 0;
    for (let i = 0; i < n; i++) {
      const l = bufL[i] < 0 ? -bufL[i] : bufL[i];
      const r = bufR[i] < 0 ? -bufR[i] : bufR[i];
      if (l > peakL) peakL = l;
      if (r > peakR) peakR = r;
    }
    const windowMs = Math.max(0.1, params.avgSeconds || 5.0) * 1000;
    const now = Date.now();
    const w = this._peakWindow;
    if (w.startedAtMs === 0 || now - w.startedAtMs >= windowMs) {
      w.startedAtMs = now;                 // tumble: restart the peak from THIS block
      w.L = peakL;
      w.R = peakR;
    } else {
      if (peakL > w.L) w.L = peakL;
      if (peakR > w.R) w.R = peakR;
    }
    this._histL.fit(-w.L, w.L);            // false => the counts were cleared, which is intended:
    this._histR.fit(-w.R, w.R);            // counts from a different level are a different signal
    for (let i = 0; i < n; i++) {
      this._histL.add(bufL[i]);
      this._histR.add(bufR[i]);
    }
  }

  /** Full teardown (page/engine dispose): drop the subscription + worker + reader. */
  async terminate() {
    if (this._onBatch) {
      MessageBus.instance().unsubscribe(Events.CAPTURE_BATCH_AVAILABLE, this._onBatch);
      this._onBatch = null;
    }
    await this.stop();
  }
}
