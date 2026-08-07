/*
 * Phonalyser web - the FreqResp DECONVOLUTION web worker.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The compute thread of Java's FreqRespAnalyzerWorker, which the web port had collapsed into the
 * controller. At the shipped FFT size (2^22) the two deconvolutions take SECONDS, and a second
 * spent on the browser's main thread is a second in which the socket's message handler does not
 * run - so a bench's 500 ms keepalive pings go unanswered and the server closes the session as
 * dead after four of them (spec 4.1). That is the whole reason this worker exists.
 *
 * A THIN shim, deliberately: the math is deconvolve.js's, which is DOM-free and therefore the
 * exact same code the node tests drive synchronously. One job in, both channels out - the module
 * worker over a DOM-free compute module is the pattern osc-meas-worker.js already uses.
 */
import { computeFromLogSweep } from './deconvolve.js';

self.onmessage = (e) => {
  const job = e.data;
  const calL = computeFromLogSweep(job.recLeft, job.sweepRef, job.leadInSamples, job.sampleRate,
    job.freqs, job.amplitudeVRms, job.adcFsVoltageRms, job.fade, job.applySavGol);
  const calR = computeFromLogSweep(job.recRight, job.sweepRef, job.leadInSamples, job.sampleRate,
    job.freqs, job.amplitudeVRms, job.adcFsVoltageRms, job.fade, job.applySavGol);
  // Both calibrations carry the SAME freqs array (computeFromLogSweep hands its grid back
  // untouched), so its buffer is listed once - a transfer list may not name one buffer twice,
  // and the structured clone keeps the two references pointing at the one array.
  self.postMessage({ calL, calR }, [calL.freqs.buffer,
    calL.magLin.buffer, calL.phaseRad.buffer, calR.magLin.buffer, calR.phaseRad.buffer]);
};
