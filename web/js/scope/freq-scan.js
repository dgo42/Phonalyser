/*
 * Phonalyser web — main-thread client for the off-thread broad-band fundamental search
 * (osc-freq-worker). Coalesces overlapping requests exactly like the Java
 * ScopeMeasurementWorker freqScanBusy flag: a scan already in flight is left to finish, so
 * requests never queue up — the frequency / period readout just refreshes at the scan's own
 * (slower) rate while every other measurement stays live.
 * GNU AGPL v3 or later.
 */
export class FreqScanClient {
  /** @param {(freq:number)=>void} onResult invoked with each scanned fundamental (Hz). */
  constructor(onResult) {
    this._onResult = onResult;
    this._busy = false;
    this._worker = new Worker(new URL('./osc-freq-worker.js', import.meta.url), { type: 'module' });
    this._worker.onmessage = (e) => {
      this._busy = false;
      const f = e.data && e.data.frequency;
      if (Number.isFinite(f)) this._onResult(f);
    };
    this._worker.onerror = (ev) => { this._busy = false; console.error('osc-freq-worker', ev.message); };
  }

  /** Submits a measurement window for the broad-band scan. Dropped (no-op) when a scan is
   *  already in flight, so the worker never builds a backlog. The window is copied into a
   *  fresh transferable buffer because the caller reuses its scratch each pass. */
  submit(data, n, sampleRate, peakVolts) {
    if (this._busy) return;
    this._busy = true;
    const copy = new Float32Array(n);
    copy.set(data.subarray ? data.subarray(0, n) : data.slice(0, n));
    this._worker.postMessage({ data: copy, n, sampleRate, peakVolts }, [copy.buffer]);
  }

  terminate() { try { this._worker.terminate(); } catch (e) { /* ignore */ } this._busy = false; }
}
