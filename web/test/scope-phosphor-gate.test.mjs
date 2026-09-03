/*
 * Phonalyser web - PhosphorGate kind-decision + decay-arithmetic contract test.
 * Run with `node web/test/scope-phosphor-gate.test.mjs`.
 *
 * Pins the pure (no-GL) surface of scope-phosphor.js against Java
 * org.edgo.audio.measure.gui.scope.gl.ScopePhosphor:
 *
 *   PhosphorGate  - the per-frame kind-decision state machine lifted out of Java
 *                   render() (ScopePhosphor.java:151-187): the first observation
 *                   never overrides; a trigger-source change (mode/type/edge/
 *                   channel) or an external clear -> CLEAR (extClear taking
 *                   priority over geometry); a geometry change (timePerDiv /
 *                   per-channel volts-per-div / offsets / trigger position) ->
 *                   RESET; anything else passes the caller's kind through.
 *
 *   phosphorFade  - the decay fraction Java computes in accumulate() (:260-263):
 *                   fade = 1 - exp(-dt/tau), dt clamped to MAX_DECAY_SECONDS (0.5),
 *                   and infinite persistence (tau <= 0) never decays.
 *
 * No GL is touched - the WebGL2 engine (ScopePhosphor's render path) can't run
 * under node and is exercised in the browser e2e instead. This file locks the
 * decision + arithmetic that must never regress.
 */

import {
  ScopePhosphor,
  PhosphorGate,
  phosphorFade,
  MAX_DECAY_SECONDS,
} from '../js/scope/scope-phosphor.js';

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) { passed++; } else { failed++; console.error('FAIL:', msg); } }

const K = ScopePhosphor.Kind;
const TAU = 0.5;   // an arbitrary finite persistence for the pass-through frame

// A baseline settings snapshot. Each mutation test copies this and changes one
// field, so exactly one of trigger/geometry differs from the recorded frame.
function baseInputs() {
  return {
    triggerMode: 'AUTO',
    triggerType: 'EDGE',
    triggerEdge: 'RISE',
    triggerChannel: 'L',
    timePerDiv: 1e-4,
    leftVdiv: 0.1,
    rightVdiv: 0.1,
    leftOff: 0.5,
    rightOff: 0.5,
    triggerPos: 0.5,
    extClearRequested: false,
  };
}

// Seeds a gate's baseline: the FIRST decide() records the snapshot without any
// override (prefsSeen was false). Returns the gate, primed for a second frame.
function primedGate(seedKind = K.REALTIME) {
  const gate = new PhosphorGate();
  const first = gate.decide(seedKind, baseInputs(), TAU);
  ok(first.kind === seedKind,
     'first observation passes the caller kind through (no override): got ' + first.kind);
  return gate;
}

// ===== Test 1: first frame never overrides (prefsSeen gate) =====
{
  // Even with extClear set on the very first frame, Java compares extClear before
  // prefsSeen - extClear is NOT gated on prefsSeen, so it still forces CLEAR.
  // But a trigger/geometry "change" cannot be detected with no prior baseline.
  const gate = new PhosphorGate();
  const r = gate.decide(K.REALTIME, baseInputs(), TAU);
  ok(r.kind === K.REALTIME, 'first frame with no extClear -> caller kind (no phantom RESET/CLEAR)');

  const gate2 = new PhosphorGate();
  const r2 = gate2.decide(K.REALTIME, { ...baseInputs(), extClearRequested: true }, TAU);
  ok(r2.kind === K.CLEAR, 'first frame WITH extClear -> CLEAR (extClear is not prefsSeen-gated)');
}

// ===== Test 2: each trigger-source field change -> CLEAR =====
for (const [field, value] of [
  ['triggerMode', 'NORMAL'],
  ['triggerType', 'GLITCH'],
  ['triggerEdge', 'FALL'],
  ['triggerChannel', 'R'],
]) {
  const gate = primedGate();
  const inp = baseInputs();
  inp[field] = value;
  const r = gate.decide(K.REALTIME, inp, TAU);
  ok(r.kind === K.CLEAR, `trigger-source change (${field}) -> CLEAR: got ${r.kind}`);
}

// ===== Test 3: each geometry field change -> RESET =====
for (const [field, value] of [
  ['timePerDiv', 2e-4],
  ['leftVdiv', 0.2],
  ['rightVdiv', 0.2],
  ['leftOff', 0.6],
  ['rightOff', 0.4],
  ['triggerPos', 0.25],
]) {
  const gate = primedGate();
  const inp = baseInputs();
  inp[field] = value;
  const r = gate.decide(K.REALTIME, inp, TAU);
  ok(r.kind === K.RESET, `geometry change (${field}) -> RESET: got ${r.kind}`);
}

// ===== Test 4: extClear takes priority over a simultaneous geometry change =====
{
  const gate = primedGate();
  const inp = baseInputs();
  inp.timePerDiv = 5e-4;          // geometry changed -> would be RESET on its own
  inp.extClearRequested = true;   // but extClear wins
  const r = gate.decide(K.REALTIME, inp, TAU);
  ok(r.kind === K.CLEAR, 'extClear + geometry change -> CLEAR (extClear priority): got ' + r.kind);
}

// ===== Test 4b: a trigger change also beats a simultaneous geometry change =====
{
  const gate = primedGate();
  const inp = baseInputs();
  inp.timePerDiv = 5e-4;      // geometry changed
  inp.triggerEdge = 'FALL';   // AND trigger changed -> CLEAR wins over RESET
  const r = gate.decide(K.REALTIME, inp, TAU);
  ok(r.kind === K.CLEAR, 'trigger + geometry change -> CLEAR (CLEAR beats RESET): got ' + r.kind);
}

// ===== Test 5: nothing changed -> caller's kind passes through =====
{
  const gate = primedGate();
  for (const kind of [K.REALTIME, K.COMPOSITE, K.RESET, K.CLEAR]) {
    const r = gate.decide(kind, baseInputs(), TAU);
    ok(r.kind === kind, `unchanged settings pass the caller kind through (${kind}): got ${r.kind}`);
  }
}

// ===== Test 6: persistSeconds passes through untouched =====
{
  const gate = primedGate();
  for (const s of [0, -1, 0.25, 3.0]) {
    const r = gate.decide(K.REALTIME, baseInputs(), s);
    ok(r.persistSeconds === s, `persistSeconds passthrough (${s}): got ${r.persistSeconds}`);
  }
}

// ===== Test 7: the baseline is re-recorded each frame (no stale comparison) =====
{
  // Change timePerDiv once -> RESET; then keep the NEW value -> passthrough (the
  // gate must have recorded the new value, not kept comparing to the original).
  const gate = primedGate();
  const inp = baseInputs();
  inp.timePerDiv = 2e-4;
  ok(gate.decide(K.REALTIME, inp, TAU).kind === K.RESET, 'first geometry change -> RESET');
  ok(gate.decide(K.REALTIME, inp, TAU).kind === K.REALTIME,
     'same (already-recorded) geometry next frame -> passthrough, not a repeat RESET');
}

// ===== Test 8: phosphorFade arithmetic - fade = 1 - exp(-dt/tau) =====
{
  const approx = (a, b, tol = 1e-12) => Math.abs(a - b) <= tol;

  // dt below the cap: exact formula.
  const dt = 0.1, tau = 0.5;
  ok(approx(phosphorFade(dt, tau), 1 - Math.exp(-dt / tau)),
     'fade = 1 - exp(-dt/tau) below the cap');

  // dt = 0 -> no fade.
  ok(phosphorFade(0, tau) === 0, 'dt = 0 -> fade 0 (nothing decays)');

  // dt cap: anything past MAX_DECAY_SECONDS decays as if dt = 0.5.
  ok(MAX_DECAY_SECONDS === 0.5, 'MAX_DECAY_SECONDS is 0.5 (Java :63-66)');
  const capped = 1 - Math.exp(-MAX_DECAY_SECONDS / tau);
  ok(approx(phosphorFade(10.0, tau), capped),
     'dt past the cap clamps to MAX_DECAY_SECONDS (gentle fade, not a wipe)');
  ok(approx(phosphorFade(0.5, tau), phosphorFade(100.0, tau)),
     'dt = cap and dt >> cap give the same fade (clamp is at exactly 0.5)');

  // A larger tau fades LESS for the same dt (monotone in tau).
  ok(phosphorFade(0.1, 0.2) > phosphorFade(0.1, 2.0),
     'shorter tau -> more fade per step');

  // Fade is always in [0, 1]. A tiny tau against the capped dt saturates to a
  // full fade (1 - exp(-500) rounds to 1.0) - a complete wipe, which is correct.
  const tiny = phosphorFade(0.5, 0.001);
  ok(tiny > 0 && tiny <= 1, 'fade stays within (0, 1] even for a tiny tau');
  ok(tiny === 1, 'a tiny tau against the capped dt saturates to a full fade (wipe)');
}

// ===== Test 9: infinite persistence never decays (tau <= 0) =====
{
  ok(phosphorFade(0.1, -1) === 0, 'infinite persistence (tau < 0) -> fade 0 (never decay)');
  ok(phosphorFade(0.1, 0) === 0, 'tau = 0 -> fade 0 (off is handled by the caller, not here)');
}

console.log(`\nPhosphorGate + phosphorFade: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
