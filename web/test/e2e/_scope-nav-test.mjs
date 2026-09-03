// Headless pure-math test for the ported scope NAVIGATION foundation (temp, _-prefixed):
//   scope-nav.js, scope-format.js, scope-trigger.js, measurement-stats.js, scope-enums.js.
// Verifies the viewport transform (incl. virtual offsets), zoom-around-mouse,
// vertical move sign, coupled 1-2-5 V/div, the rung helpers, the Schmitt+Lanczos
// trigger (incl. sub-sample refine + dual-tone holdoff), and the Welford stats.

import { ScopeNav, DIVISIONS_X, DIVISIONS_Y } from '../../js/scope/scope-nav.js';
import {
  ceilToStep, nextVoltsPerDivRung, coupleVoltsPerDivZoom, onVoltsPerDivRule,
  displaySamplesFor, anchorOffsetAfterZoom, preserveCanvasMiddle, clampOffsetDelta,
  clamp01,
} from '../../js/scope/scope-format.js';
import { find, refine, linear } from '../../js/scope/scope-trigger.js';
import { lanczos } from '../../js/dsp/lanczos.js';
import { MeasurementStats } from '../../js/scope/measurement-stats.js';
import { OscSliderId } from '../../js/enums/osc-slider-id.js';
import { TriggerEdge } from '../../js/enums/trigger-edge.js';
import { TriggerMode } from '../../js/enums/trigger-mode.js';

let pass = 0, fail = 0;
const ok = (cond, msg) => { if (cond) { pass++; } else { fail++; console.log('FAIL: ' + msg); } };
const near = (a, b, eps, msg) => ok(Math.abs(a - b) <= eps, `${msg} (got ${a}, want ${b}, eps ${eps})`);

/** Converged sinc-zero ground truth: refine()'s 10-iter bisection taken to 40 iters. */
function refineConverged(data, n, a, b, rising) {
  for (let i = 0; i < 40; i++) {
    const m = 0.5 * (a + b);
    const v = lanczos(data, n, m, 1.0);
    if (rising ? v >= 0 : v <= 0) b = m; else a = m;
  }
  return 0.5 * (a + b);
}

// Ascending 1-2-5 V/div ladder (subset is enough for the rung math).
const LADDER = [
  0.0005, 0.001, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.5,
  1, 2, 5, 10, 20, 50, 100, 200, 500,
];
const nav = new ScopeNav(DIVISIONS_X, DIVISIONS_Y, LADDER);

// ─── constants ──────────────────────────────────────────────────────────────
ok(DIVISIONS_X === 10 && DIVISIONS_Y === 10, 'DIVISIONS_X = DIVISIONS_Y = 10');

// ─── ScopeNav: viewport mapping, offsetFrac inside AND outside [0,1] ─────────
{
  const anchorAbs = 1000, disp = 200, bufStart = 700;
  // offsetFrac = 0.3 (inside): viewLeft = 1000 − 200·0.3 = 940; local = 940−700 = 240.
  const vIn = nav.viewport(anchorAbs, 0.3, disp, bufStart);
  near(vIn.viewLeftAbs, 940, 1e-9, 'viewLeftAbs inside [0,1]');
  ok(vIn.dispStart === 240 && Math.abs(vIn.subSampleOffset) < 1e-9, 'dispStart/subSample inside');
  ok(vIn.windowSamples === disp, 'windowSamples is the full (exact) displaySamples');
  // offsetFrac = 1.7 (VIRTUAL, outside [0,1]): viewLeft = 1000 − 200·1.7 = 660 (off the left).
  const vOut = nav.viewport(anchorAbs, 1.7, disp, bufStart);
  near(vOut.viewLeftAbs, 660, 1e-9, 'viewLeftAbs VIRTUAL (offsetFrac > 1)');
  ok(vOut.dispStart === -40, 'dispStart negative for virtual offset (window hangs off left)');
  // fractional offset -> fractional subSampleOffset carried, not clamped.
  const vFrac = nav.viewport(1000.0, 0.301, disp, bufStart);
  near(vFrac.viewLeftAbs, 939.8, 1e-9, 'viewLeftAbs fractional');
  near(vFrac.subSampleOffset, 0.8, 1e-9, 'subSampleOffset carries fraction');
  // raw formula matches viewLeftAbs() directly, inside and virtual.
  near(nav.viewLeftAbs(1000, -0.5, 200), 1100, 1e-9, 'viewLeftAbs negative (virtual) formula');
}

// ─── ScopeNav: zoomTriggerOffset preserves the sample under the cursor ───────
{
  const offOld = 0.3, dispOld = 200, dispNew = 100, mouse = 0.7;
  // aroundMouse=false -> offset unchanged (resolution control anchors the trigger).
  near(nav.zoomTriggerOffset(offOld, dispOld, dispNew, mouse, false), offOld, 1e-12,
    'zoomTriggerOffset !aroundMouse leaves offset');
  // aroundMouse=true -> the absolute sample under `mouse` is invariant across the zoom.
  const offNew = nav.zoomTriggerOffset(offOld, dispOld, dispNew, mouse, true);
  const anchor = 5000;
  const sampleBefore = nav.viewLeftAbs(anchor, offOld, dispOld) + mouse * dispOld;
  const sampleAfter = nav.viewLeftAbs(anchor, offNew, dispNew) + mouse * dispNew;
  near(sampleAfter, sampleBefore, 1e-7, 'zoomTriggerOffset keeps the sample under the cursor');
}

// ─── ScopeNav: moveVertical dir>0 decreases the offset ───────────────────────
{
  const peak = 2.53;
  // both channels active, mid-screen, plenty of head-room so the clamp doesn't bite.
  const r = nav.moveVertical(0.5, 0.5, true, 0.5, 0.5, true, +1, peak);
  ok(r[0] < 0.5 && r[1] < 0.5, 'moveVertical dir>0 (wheel up) DECREASES the offset');
  near(r[0], 0.5 - 0.05, 1e-9, 'moveVertical step is ½ div = 0.05 in offsetFrac');
  const rDown = nav.moveVertical(0.5, 0.5, true, 0.5, 0.5, true, -1, peak);
  ok(rDown[0] > 0.5 && rDown[1] > 0.5, 'moveVertical dir<0 INCREASES the offset');
  // both channels locked together: identical V/div -> identical result.
  ok(r[0] === r[1], 'moveVertical locks both channels together');
  // inactive channel returned unchanged.
  const rOff = nav.moveVertical(0.5, 0.5, true, 0.42, 0.5, false, +1, peak);
  ok(rOff[1] === 0.42, 'moveVertical leaves an inactive channel untouched');
}

// ─── ScopeFormat: coupleVoltsPerDivZoom keeps the 1-2-5 ratio over both ch ───
{
  // both on rule, equal V/div -> both step to the same next rung, ratio 1:1 preserved.
  const z = coupleVoltsPerDivZoom(0.01, 0.01, -1, LADDER, 0);   // zoom in
  near(z[0], 0.005, 1e-12, 'coupleVoltsPerDivZoom both-on-rule steps left down a rung');
  near(z[1], 0.005, 1e-12, 'coupleVoltsPerDivZoom both-on-rule steps right down a rung');
  near(z[0] / z[1], 1.0, 1e-12, 'coupleVoltsPerDivZoom holds the 1:1 ratio');
  // unequal but both-on-rule (2× apart) -> ratio stays 2× after coupling.
  const z2 = coupleVoltsPerDivZoom(0.01, 0.02, +1, LADDER, 0); // zoom out
  near(z2[1] / z2[0], 2.0, 1e-9, 'coupleVoltsPerDivZoom holds the 2:1 ratio across a step');
  // off-rule follower scales by the base's ratio (proportional coupling).
  const z3 = coupleVoltsPerDivZoom(0.01, 0.013, -1, LADDER, 0);
  near(z3[1] / z3[0], 0.013 / 0.01, 1e-9, 'coupleVoltsPerDivZoom holds an off-rule ratio');
  // single active channel: inactive (<=0) one returned unchanged.
  const z4 = coupleVoltsPerDivZoom(0.01, -1, -1, LADDER, 0);
  near(z4[0], 0.005, 1e-12, 'coupleVoltsPerDivZoom single-channel steps the active one');
  ok(z4[1] === -1, 'coupleVoltsPerDivZoom leaves the inactive channel sentinel');
}

// ─── ScopeFormat: ceilToStep / nextVoltsPerDivRung land on real rungs ────────
{
  near(ceilToStep(0.506, LADDER), 1, 1e-12, 'ceilToStep rounds 0.506 up to the 1 rung');
  near(ceilToStep(0.005, LADDER), 0.005, 1e-12, 'ceilToStep returns an exact rung');
  ok(ceilToStep(99999, LADDER) === LADDER[LADDER.length - 1], 'ceilToStep clamps to the top rung');
  near(nextVoltsPerDivRung(0.01, +1, LADDER), 0.02, 1e-12, 'nextVoltsPerDivRung up');
  near(nextVoltsPerDivRung(0.01, -1, LADDER), 0.005, 1e-12, 'nextVoltsPerDivRung down');
  ok(onVoltsPerDivRule(0.02, LADDER), 'onVoltsPerDivRule true on a rung');
  ok(!onVoltsPerDivRule(0.013, LADDER), 'onVoltsPerDivRule false off a rung');
  // ceilToStep is the FS-fills-height ceiling used by zoomOutVoltsPerDivCeiling.
  near(nav.zoomOutVoltsPerDivCeiling(2.53), 1, 1e-12, 'zoomOutVoltsPerDivCeiling = ceil(2·2.53/10) -> 1');
}

// ─── ScopeFormat: misc nav helpers ───────────────────────────────────────────
{
  // EXACT real-valued width: 20 us/div at 44.1 kHz is 8.82 samples, kept unrounded.
  near(displaySamplesFor(0.001, 48000), 480, 1e-12, 'displaySamplesFor = timePerDiv·10·sr');
  near(displaySamplesFor(20e-6, 44100), 8.82, 1e-12, 'displaySamplesFor keeps a fractional width');
  ok(displaySamplesFor(1e-9, 48000) === 2, 'displaySamplesFor floors at 2');
  // anchorOffsetAfterZoom(0.5,...) is preserveCanvasMiddle and is a fixed point at 0.5.
  near(anchorOffsetAfterZoom(0.5, 0.01, 0.005, 0.5), 0.5, 1e-12, 'anchor at middle is a fixed point');
  near(preserveCanvasMiddle(0.7, 0.01, 0.005), 0.5 + (0.7 - 0.5) * 2, 1e-12, 'preserveCanvasMiddle doubles offset on 2× zoom-in');
  near(clamp01(-0.2), 0, 1e-12, 'clamp01 low');
  near(clamp01(1.4), 1, 1e-12, 'clamp01 high');
  // clampOffsetDelta limits a move so ±FS/2 stops at the canvas middle.
  const d = clampOffsetDelta(-0.05, 0.5, 1.0, 5.0, 10);   // half = max(0.5, 5/(10·1)) = 0.5
  near(d, -0.05, 1e-12, 'clampOffsetDelta passes a within-band move');
}

// ─── scope-trigger: rising zero-cross near the right edge, sub-sample refine ──
{
  // A frequency whose period is NOT an integer number of samples, so the zero
  // crossings fall BETWEEN samples and the linear estimate carries real error for
  // the sinc refine to improve on. (f=1000 at 48 kHz crosses on exact integers.)
  const sr = 48000, f = 997;
  const n = 4096;
  const sig = new Float32Array(n);
  for (let i = 0; i < n; i++) sig[i] = Math.sin(2 * Math.PI * f * i / sr);
  // Keep the search end clear of the right-edge truncation band (LANCZOS_A = 16)
  // so the committed winner's sinc reconstruction has full kernel context.
  const from = 32, to = n - 32;
  // linear estimate (no sinc) - rightmost rising crossing of level 0.
  const tLin = find(sig, n, from, to, 0.0, true, false, 0.0);
  ok(tLin > 0, 'find returns a rising crossing');
  // it IS a rising zero crossing: sample just left < 0, just right > 0.
  const iL = Math.floor(tLin);
  ok(sig[iL] < 0 && sig[iL + 1] > 0, 'find lands on a RISING zero-crossing');
  // near the right edge: within one period of the search end.
  ok(tLin > to - (sr / f) - 2, 'find returns the RIGHTMOST qualified crossing');
  // sinc refine improves on the linear estimate. Ground truth = the SAME band-limited
  // (Lanczos scale-1) reconstruction the refine targets, but bisected to convergence
  // (40 iters ≫ refine's 10). The 10-iter refine should land far closer to this true
  // sinc zero than the linear chord estimate does.
  const tRef = find(sig, n, from, to, 0.0, true, true, 0.0);
  const trueZero = refineConverged(sig, n, iL, iL + 1, true);
  const errLin = Math.abs(tLin - trueZero);
  const errRef = Math.abs(tRef - trueZero);
  ok(errRef < errLin, `Lanczos refine improves on linear (errRef ${errRef.toExponential(2)} < errLin ${errLin.toExponential(2)})`);
  ok(errRef < 1e-3, 'refined crossing is sub-millisample accurate');
  // direct refine()/linear() sanity.
  near(linear(-1, 1, 10, 0), 10.5, 1e-12, 'linear midpoint crossing');
  ok(find(sig, n, from, to, 5.0, true, false, 0.0) === -1, 'find returns -1 when level never crossed');
}

// ─── scope-trigger: dual-tone holdoff respects minSpacing ────────────────────
{
  const sr = 48000, f1 = 1000, f2 = 1100;   // |F1−F2| = 100 Hz beat
  const n = 8192;
  const sig = new Float32Array(n);
  for (let i = 0; i < n; i++) sig[i] = Math.sin(2 * Math.PI * f1 * i / sr) + Math.sin(2 * Math.PI * f2 * i / sr);
  const minSpacing = sr / Math.abs(f1 - f2);   // = 480 samples per beat
  const from = 16, to = n;
  // Collect accepted triggers by progressively shrinking the search window from the right;
  // simpler: assert holdoff produces FEWER triggers than no-holdoff over the same window.
  // Count rising level-0 crossings WITHOUT holdoff via repeated right-trimmed searches.
  const tHold = find(sig, n, from, to, 0.0, true, false, 0.0, minSpacing);
  const tNo = find(sig, n, from, to, 0.0, true, false, 0.0, 0.0);
  ok(tHold > 0 && tNo > 0, 'both holdoff and no-holdoff find a trigger');
  // With holdoff the accepted winner is still a valid rightmost crossing; verify the
  // gate by walking accepted triggers: search a window ending at tHold and ensure the
  // previous accepted trigger is ≥ minSpacing earlier.
  const tPrev = find(sig, n, from, Math.floor(tHold), 0.0, true, false, 0.0, minSpacing);
  ok(tPrev < 0 || (tHold - tPrev) >= minSpacing - 1, 'holdoff keeps accepted triggers ≥ minSpacing apart');
  // sanity: the carrier crosses 0 far more often than once per beat, so a no-holdoff
  // search ending just before tHold finds a crossing CLOSER than minSpacing.
  const tNear = find(sig, n, from, Math.floor(tHold), 0.0, true, false, 0.0, 0.0);
  ok(tNear > 0 && (tHold - tNear) < minSpacing, 'without holdoff a closer crossing exists (proves the gate fired)');
}

// ─── measurement-stats: Welford avg/min/max/sigma, NaN skipped ───────────────
{
  const s = new MeasurementStats();
  // sequence 2,4,4,4,5,5,7,9 (the classic Welford example) with a NaN spliced in.
  const seq = [2, 4, 4, NaN, 4, 5, 5, 7, 9];
  for (const v of seq) s.add(v);
  near(s.getMean(), 5.0, 1e-12, 'stats mean = 5 (NaN skipped)');
  near(s.getMin(), 2.0, 1e-12, 'stats min = 2');
  near(s.getMax(), 9.0, 1e-12, 'stats max = 9');
  // sample sigma over the 8 real values: variance = 32/7, sigma = sqrt(32/7).
  near(s.getSigma(), Math.sqrt(32 / 7), 1e-9, 'stats sample sigma = sqrt(32/7)');
  // empty / single-sample guards return NaN.
  const e = new MeasurementStats();
  ok(Number.isNaN(e.getMean()) && Number.isNaN(e.getSigma()), 'empty stats are NaN');
  e.add(3);
  ok(Number.isNaN(e.getSigma()), 'single-sample sigma is NaN');
  near(e.getMean(), 3, 1e-12, 'single-sample mean is the value');
}

// ─── enums: frozen constant objects with the serialised values ───────────────
{
  ok(OscSliderId.OFFSET === 'OFFSET' && OscSliderId.TRIGGER_LEVEL === 'TRIGGER_LEVEL'
    && OscSliderId.TRIGGER_POSITION === 'TRIGGER_POSITION', 'OscSliderId values');
  ok(TriggerEdge.RISE === 'RISE' && TriggerEdge.FALL === 'FALL', 'TriggerEdge values');
  ok(TriggerMode.AUTO === 'AUTO' && TriggerMode.NORMAL === 'NORMAL' && TriggerMode.SINGLE === 'SINGLE',
    'TriggerMode values');
  ok(Object.isFrozen(OscSliderId) && Object.isFrozen(TriggerEdge) && Object.isFrozen(TriggerMode),
    'enums are frozen');
}

console.log(`\nscope-nav: ${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
