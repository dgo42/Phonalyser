/*
 * Phonalyser web - generator output-lane feature: pref persistence + gate math.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Run with `node web/test/output-lane.test.mjs`.
 *
 * Two contracts, both ported from the Java baseline:
 *
 *   1. genOutputChannels persistence - mirrors OutputChannelsPrefsTest: the pref
 *      defaults to 'BOTH', persists by enum NAME through the localStorage-backed
 *      toMap/fromMap round-trip, and an absent key leaves the default (old files
 *      stay on BOTH).
 *   2. Output-lane gate math - the interleave-seam mapping (Java PcmQuantizer.encode /
 *      SignalFileExporter.fillBuffer): BOTH -> [s, s*scale], LEFT -> [s, 0],
 *      RIGHT -> [0, s*scale], and BOTH + scale 1.0 -> identical lanes.
 *
 * preferences.js persists to localStorage and reads navigator in its constructor,
 * so this installs minimal shims on globalThis BEFORE importing the module.
 */

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) { passed++; } else { failed++; console.error('FAIL:', msg); } }

// --- minimal shims: an in-memory localStorage + a navigator for defaultUiFont ---
function installShims() {
  const store = new Map();
  globalThis.localStorage = {
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => { store.set(k, String(v)); },
    removeItem: (k) => { store.delete(k); },
    clear: () => { store.clear(); },
  };
  if (!globalThis.navigator) globalThis.navigator = { platform: 'Win32', userAgent: 'node' };
}
installShims();

const { Preferences, PREFS_KEY } = await import('../js/store/preferences.js');
const { outputLaneGate, outputLaneSamples } = await import('../js/generator/dds-kernel.js');

// ── 1. persistence: default is BOTH ──────────────────────────────────────────
localStorage.clear();
const fresh = new Preferences();
ok(fresh.genOutputChannels.get() === 'BOTH', `default is BOTH (got ${fresh.genOutputChannels.get()})`);

// ── 1a. round-trip LEFT/RIGHT through localStorage, persisted by enum name ────
localStorage.clear();
const src = new Preferences();
src.genOutputChannels.set('LEFT');
src.flush();   // force the debounced write
const doc = JSON.parse(localStorage.getItem(PREFS_KEY) || '{}');
ok(doc.genOutputChannels === 'LEFT', `persisted by enum name (got ${doc.genOutputChannels})`);

const reloaded = new Preferences();   // load() runs in ctor
ok(reloaded.genOutputChannels.get() === 'LEFT', `round-trip LEFT (got ${reloaded.genOutputChannels.get()})`);

localStorage.clear();
const srcR = new Preferences();
srcR.genOutputChannels.set('RIGHT');
srcR.flush();
ok(new Preferences().genOutputChannels.get() === 'RIGHT', 'round-trip RIGHT');

// ── 1b. absent key keeps the default BOTH (old files stay on BOTH) ────────────
localStorage.clear();
const withKey = new Preferences();
withKey.genOutputChannels.set('RIGHT');
withKey.flush();
const stripped = JSON.parse(localStorage.getItem(PREFS_KEY) || '{}');
delete stripped.genOutputChannels;
localStorage.setItem(PREFS_KEY, JSON.stringify(stripped));
ok(new Preferences().genOutputChannels.get() === 'BOTH',
  'an absent key leaves the default BOTH');

// ── 1c. an invalid enum name is rejected, keeping the current value ───────────
localStorage.clear();
const bad = new Preferences();
bad.genOutputChannels.set('LEFT');
bad.flush();
const badDoc = JSON.parse(localStorage.getItem(PREFS_KEY) || '{}');
badDoc.genOutputChannels = 'SIDEWAYS';
localStorage.setItem(PREFS_KEY, JSON.stringify(badDoc));
ok(new Preferences().genOutputChannels.get() === 'BOTH',
  'invalid enum name keeps the current value (default BOTH on a fresh load)');

// ── 2. gate math (Java PcmQuantizer.encode wantL/wantR semantics) ─────────────
const s = 0.5, scale = 0.8;

const both = outputLaneSamples(s, 'BOTH', scale);
ok(both[0] === s && both[1] === s * scale, `BOTH -> [s, s*scale] (got [${both}])`);

const left = outputLaneSamples(s, 'LEFT', scale);
ok(left[0] === s && left[1] === 0, `LEFT -> [s, 0] (got [${left}])`);

const right = outputLaneSamples(s, 'RIGHT', scale);
ok(right[0] === 0 && right[1] === s * scale, `RIGHT -> [0, s*scale] (got [${right}])`);

// BOTH + unity scale = both lanes identical (byte-identical to the pre-feature encoder).
const unity = outputLaneSamples(s, 'BOTH', 1.0);
ok(unity[0] === s && unity[1] === s, `BOTH + scale 1.0 -> identical lanes (got [${unity}])`);

// The hoisted gate the worklet uses agrees with the per-sample composer.
const gB = outputLaneGate('BOTH'), gL = outputLaneGate('LEFT'), gR = outputLaneGate('RIGHT');
ok(gB.wantL && gB.wantR, 'gate BOTH drives both lanes');
ok(gL.wantL && !gL.wantR, 'gate LEFT silences the right lane');
ok(!gR.wantL && gR.wantR, 'gate RIGHT silences the left lane');

console.log(`\noutput-lane: ${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);
