/*
 * Phonalyser web — off-thread broad-band fundamental search.
 *
 * Faithful port of the Java ScopeMeasurementWorker "osc-freq-scan" thread: a weak or
 * noisy signal needs the costly per-bin Goertzel sweep to find its fundamental, and
 * running that on the live measurement path drops the 10 Hz cadence and throttles the
 * whole readout table. So the sweep runs HERE, off the main thread, and the main thread
 * folds the result (frequency only) into the next measurement — only f / period lag, every
 * other readout stays real-time.
 * GNU AGPL v3 or later.
 */
import { compute } from './signal-measurements.js';

self.onmessage = (e) => {
  const { data, n, sampleRate, peakVolts } = e.data;
  // Full measurement WITH the broad-band scan enabled; only the frequency is wanted.
  const m = compute(data, n, sampleRate, peakVolts, true);
  self.postMessage({ frequency: m.frequency });
};
