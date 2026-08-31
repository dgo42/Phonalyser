/*
 * Phonalyser web - the display-preparation worker: the web's counterpart of the desktop's second
 * thread, the one that prepares what the view draws while the analyser is already working on the
 * next window.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU AGPL v3 or later.
 *
 * A thin shell. Everything it does lives in fft-finalize.js, which the analysis worker imports
 * too - so the two cases that must stay on the analysis thread (a tick with no average to
 * overlay, and the fallback when this worker cannot be built) run the SAME code against the same
 * kind of holder, and there is never a second cascade on one thread.
 *
 * It answers the MAIN THREAD directly, over a port handed to it at construction, rather than
 * through its parent: the parent is busy analysing the next window, and a finished display frame
 * that waited for the parent's event loop would arrive a whole analysis late - which is the delay
 * this whole split exists to remove.
 */
import { FftResult } from './fft-result.js';
import {
  displaySnapshot, dropCalCascade, finalizeForDisplay, loadJobInto, overlayFromSums, setCalRows,
} from './fft-finalize.js';

/** The result the display is rebuilt into, reused across frames: its four spectral arrays are the
 *  overlay's target and are re-allocated only when the transform size changes. */
const slot = new FftResult();

/** The port to the main thread. Null until the parent hands it over. */
let displayPort = null;

self.onmessage = (e) => {
  const d = e.data;
  if (d.port) { displayPort = d.port; return; }
  if (d.calRows !== undefined) { setCalRows(d.calRows); return; }
  // A restart keeps the loaded rows and throws away only what was built from them: the analysed
  // channel and every tone position the cascade was keyed on move with a restart.
  if (d.dropCal) { dropCalCascade(); return; }
  if (!d.job) return;
  const job = d.job;
  const t0 = performance.now();
  loadJobInto(slot, job);
  const calGainDb = finalizeForDisplay(slot, job, (r) => overlayFromSums(r, job.sums));
  const { out, transfer } = displaySnapshot(slot, {
    kind: 'display', id: job.id, epoch: job.epoch, ms: performance.now() - t0,
    reemit: !!job.reemit, calGainDb: calGainDb || null,
  });
  if (displayPort) displayPort.postMessage(out, transfer);
  else self.postMessage(out, transfer);
};
