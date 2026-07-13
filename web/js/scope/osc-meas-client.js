/*
 * Phonalyser web — main-thread client for the scope MEASUREMENT web worker.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The measurement path's OWN consumer of the shared capture ring: it holds a dedicated
 * FORWARD SignalBufferReader (the FFT-consumer pattern, fft-controller.js) and, per
 * CAPTURE_BATCH_AVAILABLE, reads the contiguous gap of BOTH channels exactly once — gapless,
 * every captured sample delivered once, no breaks — into fresh transferable buffers and posts
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

export class OscMeasClient {
  /**
   * @param {object} capture      the SharedCapture — the client acquires its OWN forward reader.
   * @param {() => object|null} getParams  returns the current publish params
   *   { sampleRate, peakVolts, avgSeconds, L:{lpfMode,mainsMode,dual,f1Hz,f2Hz}, R:{...} }
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
    if (!this._worker) return;   // not running → nothing to reattach
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
   *  it to the worker. OVERRUN → re-anchor + worker stream reset (a torn window would smear the
   *  collection). Dropped when measurement is off (getParams returns null). */
  _feed() {
    const reader = this._reader, worker = this._worker;
    if (!reader || !worker) return;
    const params = this._getParams ? this._getParams() : null;
    if (!params) { reader.seekToLatest(); return; }   // measurement off → don't backlog the cursor
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
    worker.postMessage({
      type: 'feed', bufL, bufR, n,
      sampleRate: params.sampleRate, peakVolts: params.peakVolts, avgSeconds: params.avgSeconds,
      L: params.L, R: params.R,
    }, [bufL.buffer, bufR.buffer]);
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
