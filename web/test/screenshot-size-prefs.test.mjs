/*
 * Phonalyser web - per-pane screenshot-size persistence contract test.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Run with `node web/test/screenshot-size-prefs.test.mjs`.
 *
 * The screenshot dialog (js/ui/screenshot.js) remembers the chosen output size
 * PER PANE: scope -> oscScreenshotWidth/Height, FFT -> fftScreenshotWidth/Height,
 * FreqResp -> freqRespScreenshotWidth/Height. This test pins that the three pairs
 * are INDEPENDENT and each round-trips through the localStorage-backed toMap/fromMap
 * (a regression would be FFT + FreqResp sharing the single legacy screenshotWidth
 * pair, which they did before this change).
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

const { Preferences } = await import('../js/store/preferences.js');

// Fresh instance (bypass the process-wide singleton so each run starts clean).
const prefs = new Preferences();

// Distinct sizes per pane so a shared pair would collide detectably.
prefs.scopeScreenshotWidth.set(1024);  prefs.scopeScreenshotHeight.set(768);
prefs.fftScreenshotWidth.set(1920);  prefs.fftScreenshotHeight.set(1080);
prefs.freqRespScreenshotWidth.set(1366); prefs.freqRespScreenshotHeight.set(768);
prefs.flush();   // force the debounced write to localStorage

// A second instance re-reads the same localStorage document (load() runs in ctor).
const reloaded = new Preferences();

ok(reloaded.scopeScreenshotWidth.get() === 1024 && reloaded.scopeScreenshotHeight.get() === 768,
  `scope size round-trip: ${reloaded.scopeScreenshotWidth.get()}x${reloaded.scopeScreenshotHeight.get()}`);
ok(reloaded.fftScreenshotWidth.get() === 1920 && reloaded.fftScreenshotHeight.get() === 1080,
  `fft size round-trip: ${reloaded.fftScreenshotWidth.get()}x${reloaded.fftScreenshotHeight.get()}`);
ok(reloaded.freqRespScreenshotWidth.get() === 1366 && reloaded.freqRespScreenshotHeight.get() === 768,
  `freqResp size round-trip: ${reloaded.freqRespScreenshotWidth.get()}x${reloaded.freqRespScreenshotHeight.get()}`);

// Independence: the three pairs are distinct, so no two panes share storage.
ok(reloaded.scopeScreenshotWidth.get() !== reloaded.fftScreenshotWidth.get()
  && reloaded.fftScreenshotWidth.get() !== reloaded.freqRespScreenshotWidth.get()
  && reloaded.scopeScreenshotWidth.get() !== reloaded.freqRespScreenshotWidth.get(),
  'each pane persists an independent width');

// Default 0 ("use native size") must NOT be emitted (emit-when->0 parity with the
// scope's scopeScreenshot* pair).
localStorage.clear();
const dflt = new Preferences();
dflt.flush();
const raw = localStorage.getItem('phonalyser.preferences') || '{}';
const doc = JSON.parse(raw);
ok(!('fftScreenshotWidth' in doc) && !('fftScreenshotHeight' in doc)
  && !('freqRespScreenshotWidth' in doc) && !('freqRespScreenshotHeight' in doc),
  'zero (native) sizes are omitted from the serialised document');

console.log(`\nscreenshot-size-prefs: ${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);
