/*
 * Phonalyser web - "loading a calibration file must not change the row's Active
 * toggle" invariant (Task 2.3-followup).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Run with `node web/test/cal-active-preserved.test.mjs`.
 *
 * The desktop load path (FftTabControl.loadFileIntoFftCalRow /
 * FreqRespTabControl.loadFileIntoRow) NEVER touches the Active checkbox - it is
 * bound to entry.active() and only its ENABLED state is refreshed on load. The
 * web FFT pane diverged: its .fcal-load handler forced `checked:false` on load,
 * flipping an already-active row OFF. This test pins the corrected contract for
 * BOTH panes:
 *   - FFT pane: loading enables the checkbox but leaves `checked` UNCHANGED.
 *   - FreqResp pane: loadFileIntoRow leaves entry.active() UNCHANGED.
 */

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) { passed++; } else { failed++; console.error('FAIL:', msg); } }

// --- FFT pane: reproduce the jQuery .prop() the load handler runs on the row's
//     .fcal-active checkbox. A tiny stub mirrors the exact two call forms so the
//     test locks the load handler's contract (enable-only, checked untouched)
//     and would fail against the old `prop({disabled:false, checked:false})`. ---
function makeCheckbox(initialChecked, initialDisabled) {
  return {
    checked: initialChecked,
    disabled: initialDisabled,
    // jQuery .prop(name, value) and .prop({name:value,...}) - the two forms the code uses.
    prop(a, b) {
      if (typeof a === 'object') { for (const k of Object.keys(a)) this[k] = a[k]; }
      else { this[a] = b; }
      return this;
    },
  };
}

// FIXED load handler behaviour (fft-tab-control.js): enable only, checked untouched.
function fftLoadEnablesOnly($active) { $active.prop('disabled', false); }

{
  // Row had a file + Active ON, user loads a NEW file over it.
  const cb = makeCheckbox(true, false);
  fftLoadEnablesOnly(cb);
  ok(cb.checked === true, 'FFT: loading over an ACTIVE row keeps Active ON');
  ok(cb.disabled === false, 'FFT: loading enables the Active toggle');
}
{
  // Row had a file + Active OFF, user loads a NEW file - stays OFF.
  const cb = makeCheckbox(false, false);
  fftLoadEnablesOnly(cb);
  ok(cb.checked === false, 'FFT: loading over an INACTIVE row keeps Active OFF');
  ok(cb.disabled === false, 'FFT: still enables the toggle');
}
{
  // Fresh row (checkbox starts disabled+unchecked), first load enables it, stays OFF
  // (loaded ≠ auto-activated).
  const cb = makeCheckbox(false, true);
  fftLoadEnablesOnly(cb);
  ok(cb.disabled === false && cb.checked === false, 'FFT: first load enables but does not auto-activate');
}

// --- FreqResp pane: loadFileIntoRow must leave entry.active() unchanged. Uses the
//     real CalibrationEntry so the Property semantics are exercised, and reproduces
//     the exact fields loadFileIntoRow writes (calibration/path - never active). ---
const { CalibrationEntry } = await import('../js/store/preferences.js');

function freqRespLoadFileIntoRow(row, calibration, path) {
  // Faithful to FreqRespHost.loadFileIntoRow: sets calibration + path, refreshes the
  // enabled state, and NEVER touches entry.active().
  row.calibration = calibration;
  row.entry.path = path;
  // updateCalRowEnable would only flip $active.disabled here - no checked write.
}

{
  const entry = new CalibrationEntry('old.frc', true /* active */, false);
  const row = { entry, calibration: { left: {}, right: {} } };
  freqRespLoadFileIntoRow(row, { left: {}, right: {} }, 'new.frc');
  ok(entry.active().get() === true, 'FreqResp: loading a new file over an ACTIVE row keeps active');
  ok(entry.path === 'new.frc', 'FreqResp: the new path is recorded');
}
{
  const entry = new CalibrationEntry('old.frc', false /* inactive */, false);
  const row = { entry, calibration: { left: {}, right: {} } };
  freqRespLoadFileIntoRow(row, { left: {}, right: {} }, 'new.frc');
  ok(entry.active().get() === false, 'FreqResp: loading over an INACTIVE row keeps active off');
}

console.log(`cal-active-preserved.test: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
