/*
 * Phonalyser web - the bus rounds that replaced stored callbacks.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Run with `node web/test/bus-rounds.test.mjs`.
 *
 * Two seams that carried a stored callback are asked here to carry their Java event instead:
 * the correction store (Java CorrectionStore publishes the event name it was built with on every
 * mutation) and the FFT controller's alignment magnitude-drift watch (Java
 * FftController.watchAlignmentMagnitudeDrift publishes FFT_ALIGN_MAG_DRIFT once per re-arm).
 * The DOM-bound rounds - the screenshot request, the single-shot disarm, auto-setup, the file
 * player's end - are wired through the same bus and are proven on the bench.
 */
import { CorrectionStore } from '../js/common/correction-store.js';
import { FftController } from '../js/fft/fft-controller.js';
import { MessageBus } from '../js/bus/message-bus.js';
import { Events } from '../js/bus/events.js';

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) { passed++; } else { failed++; console.error('FAIL:', msg); } }

/** Counts publishes of `event` until stop(). */
function count(event) {
  const seen = { n: 0, last: undefined };
  const sub = (p) => { seen.n++; seen.last = p; };
  MessageBus.instance().subscribe(event, sub);
  seen.stop = () => MessageBus.instance().unsubscribe(event, sub);
  return seen;
}

// --- 1. THE STORE PUBLISHES ITS EVENT. One publish per mutation that changed something; a
// restore that lands on the same entries is silent; a store built without an event is silent.
{
  const cal = { left: {}, right: {} };
  const store = new CorrectionStore('FFT', Events.FFT_CALIBRATION_CHANGED);
  const seen = count(Events.FFT_CALIBRATION_CHANGED);
  store.addEntry(cal, 'a.frc');
  ok(seen.n === 1, 'adding an entry publishes the calibration-changed event once');
  store.clearAll();
  ok(seen.n === 2, 'clearing publishes once');
  store.clearAll();
  ok(seen.n === 2, 'clearing an empty store publishes nothing');
  store.setDirect({ direct: true });
  ok(seen.n === 3, 'the wizard transient publishes once');
  const snap = store.snapshot();
  store.restore(snap);
  ok(seen.n === 3, 'restoring the same entries is silent');
  store.addEntry(cal, 'b.frc');
  store.restore(snap);
  ok(seen.n === 5, 'restoring different entries publishes (after the add that changed them)');
  seen.stop();

  const silent = new CorrectionStore('TuneNotch', null);
  const fr = count(Events.FREQRESP_CALIBRATION_CHANGED);
  const fft = count(Events.FFT_CALIBRATION_CHANGED);
  silent.addEntry(cal, 'c.frc');
  silent.clearAll();
  ok(fr.n === 0 && fft.n === 0, 'a store without an event name publishes nothing at all');
  fr.stop(); fft.stop();
}

// --- 2. THE DRIFT WATCH FIRES ONCE PER RE-ARM. The first steered result after a lock reset
// baselines the level; nothing fires while the loop is still pulling; the first ALIGNED result
// compares once - beyond 0.1 dB the delta is published, within it nothing is - and the watch is
// spent until the next reset. Single-tone (tone 2 NaN) and dual-tone (the larger delta wins).
{
  const c = Object.create(FftController.prototype);
  c._fll = { reset() {} };
  c._fll2 = { reset() {} };
  c._alignWatchArmed = false;
  c._alignWatchBaselined = false;
  const drift = count(Events.FFT_ALIGN_MAG_DRIFT);
  const reset = count(Events.GENERATOR_FREQ_TRIM_RESET);

  c._watchAlignmentMagnitudeDrift(-20, NaN, true);
  ok(drift.n === 0, 'an un-armed watch (no lock reset yet) never fires');

  c._resetFrequencyLock();
  ok(reset.n === 1, 'the lock reset still publishes the trim-reset round');
  c._watchAlignmentMagnitudeDrift(-20.0, NaN, true);
  ok(drift.n === 0, 'the first result only baselines, aligned or not');
  c._watchAlignmentMagnitudeDrift(-19.0, NaN, false);
  ok(drift.n === 0, 'a level shift while the loop is still pulling is not judged yet');
  c._watchAlignmentMagnitudeDrift(-20.05, NaN, true);
  ok(drift.n === 0, 'aligned within 0.1 dB of the baseline: no hint');
  c._watchAlignmentMagnitudeDrift(-15.0, NaN, true);
  ok(drift.n === 0, 'and the watch is spent - a later shift does not fire without a new reset');

  c._resetFrequencyLock();
  c._watchAlignmentMagnitudeDrift(-20.0, NaN, true);
  c._watchAlignmentMagnitudeDrift(-19.7, NaN, true);
  ok(drift.n === 1 && Math.abs(drift.last - 0.3) < 1e-9,
    'aligned 0.3 dB off the baseline publishes the delta once');
  c._watchAlignmentMagnitudeDrift(-15.0, NaN, true);
  ok(drift.n === 1, 'one hint per re-arm');

  c._resetFrequencyLock();
  c._watchAlignmentMagnitudeDrift(-20.0, -26.0, true);
  c._watchAlignmentMagnitudeDrift(-20.02, -26.5, true);
  ok(drift.n === 2 && Math.abs(drift.last - 0.5) < 1e-9,
    'dual-tone: the larger of the two per-tone deltas is what travels');

  c._resetFrequencyLock();
  c._watchAlignmentMagnitudeDrift(NaN, NaN, true);
  c._watchAlignmentMagnitudeDrift(-20.0, NaN, true);
  c._watchAlignmentMagnitudeDrift(-19.0, NaN, true);
  ok(drift.n === 3, 'a result without a fundamental level neither baselines nor resolves');
  drift.stop(); reset.stop();
}

console.log(`bus-rounds.test: ${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);
