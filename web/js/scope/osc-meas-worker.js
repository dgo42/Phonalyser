/*
 * Phonalyser web — the scope MEASUREMENT web worker.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * A real Web Worker mirroring the Java ScopeMeasurementWorker's compute thread. The main
 * side (osc-meas-client) owns a FORWARD SignalBufferReader over the shared ring and, per
 * CAPTURE_BATCH_AVAILABLE, reads the contiguous gap of BOTH channels exactly once (gapless,
 * FFT-consumer pattern) and posts it here as a 'feed'. This worker carries the per-channel
 * HF-LPF/despike + mains-comb STREAMING state batch to batch (never reset — a running
 * absPos gives the phase-locked cancellers their absStart deltas) and appends the filtered
 * samples (+ a parallel raw copy) into a rolling collection window = oscMeasurementAverage
 * Seconds·sampleRate. On its OWN fixed ~100 ms cadence (with catch-up re-anchor, Java
 * measurementLoop) it measures the whole collection window per channel and posts back
 * {resultL, resultR, leftMeanNorm, rightMeanNorm}. An OVERRUN on the client side sends a
 * 'reset' so the stream restarts clean.
 *
 * The measurement math lives in the shared, DOM-free compute module (osc-meas-compute.js)
 * so the node tests drive the exact same pipeline synchronously.
 */
import { OscMeasCompute } from './osc-meas-compute.js';

const PUBLISH_INTERVAL_MS = 100;   // Java measurementLoop ~100 ms cadence

const engine = new OscMeasCompute();
// Latest publish parameters, refreshed with every feed / params message (Java worker
// re-reads Preferences each pass): the sample rate, ±1.0→volts scale, and per-channel
// mains mode + dual-tone form + the generator's (snap-aware) tone seeds.
let params = null;
let publishTimer = null;

// Nested broad-band weak-signal frequency scan (Java ScopeMeasurementWorker "osc-freq-scan"
// thread): when the cheap crossing search returns NaN for a single tone, the costly per-bin
// Goertzel sweep runs in a NESTED worker (module workers may spawn workers — the same
// fft-worker→fft-pool-worker pattern) so it never stalls this publish loop. Coalesced per
// channel (one scan in flight at a time); the result folds into the next publish via
// engine.setAsyncFreq. Lazily created so it costs nothing until a weak tone appears.
const freqScan = { L: { worker: null, busy: false }, R: { worker: null, busy: false } };

/** Kicks a coalesced broad-band scan of a channel's raw window (single-tone weak signal). */
function requestFreqScan(ch, sampleRate, peakVolts) {
  const fs = freqScan[ch];
  if (fs.busy) return;
  const raw = engine.rawWindow(ch);
  if (!raw) return;
  if (!fs.worker) {
    fs.worker = new Worker(new URL('./osc-freq-worker.js', import.meta.url), { type: 'module' });
    fs.worker.onmessage = (e) => {
      fs.busy = false;
      const f = e.data && e.data.frequency;
      if (Number.isFinite(f)) engine.setAsyncFreq(ch, f);
    };
    fs.worker.onerror = () => { fs.busy = false; };
  }
  fs.busy = true;
  const copy = new Float32Array(raw.length);
  copy.set(raw);
  fs.worker.postMessage({ data: copy, n: copy.length, sampleRate, peakVolts }, [copy.buffer]);
}

/** Measures both channels over their current collection windows and posts the result. */
function publishTick() {
  if (!params) return;
  const { sampleRate, peakVolts, L, R } = params;
  const out = { resultL: null, resultR: null, leftMeanNorm: NaN, rightMeanNorm: NaN };
  if (engine.hasData('L')) {
    out.resultL = engine.publish('L', sampleRate, peakVolts, L);
    if (out.resultL) out.leftMeanNorm = out.resultL.vmean / peakVolts;
    if (out.resultL && !L.dual && Number.isNaN(out.resultL.frequency)) requestFreqScan('L', sampleRate, peakVolts);
  }
  if (engine.hasData('R')) {
    out.resultR = engine.publish('R', sampleRate, peakVolts, R);
    if (out.resultR) out.rightMeanNorm = out.resultR.vmean / peakVolts;
    if (out.resultR && !R.dual && Number.isNaN(out.resultR.frequency)) requestFreqScan('R', sampleRate, peakVolts);
  }
  if (out.resultL || out.resultR) self.postMessage(out);
}

function startTimer() {
  if (publishTimer == null) publishTimer = setInterval(publishTick, PUBLISH_INTERVAL_MS);
}

self.onmessage = (e) => {
  const d = e.data;
  if (!d) return;
  if (d.type === 'reset') { engine.resetStream(); return; }
  if (d.type === 'feed') {
    // Persist the publish parameters (per-channel), then append this batch to BOTH
    // channels' streaming pipelines. The buffers arrived as fresh transferables.
    params = {
      sampleRate: d.sampleRate, peakVolts: d.peakVolts,
      L: d.L, R: d.R,
    };
    const feedL = { lpfMode: d.L.lpfMode, mainsMode: d.L.mainsMode, avgSeconds: d.avgSeconds };
    const feedR = { lpfMode: d.R.lpfMode, mainsMode: d.R.mainsMode, avgSeconds: d.avgSeconds };
    if (d.bufL) engine.feed('L', d.bufL, d.n, d.sampleRate, feedL);
    if (d.bufR) engine.feed('R', d.bufR, d.n, d.sampleRate, feedR);
    startTimer();
    return;
  }
};
