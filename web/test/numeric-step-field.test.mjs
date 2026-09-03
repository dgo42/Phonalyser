/*
 * Phonalyser web - NumericStepModel contract test (ported from
 * NumericStepModelTest.java). Run with `node web/test/numeric-step-field.test.mjs`.
 *
 * The model resolves unit suffixes through i18n's t(); to stay headless we
 * intercept the i18n module with a tiny static unit-key map before importing.
 */
import { Module } from 'node:module';

// --- minimal unit.* table so suffix()/text()/matches() work headless ---
const UNIT = {
  'unit.hz': 'Hz', 'unit.khz': 'kHz',
  'unit.nv': 'nV', 'unit.uv': 'µV', 'unit.mv': 'mV', 'unit.v': 'V', 'unit.dbv': 'dBV', 'unit.bits': 'bits',
  'unit.ms': 'ms', 'unit.s': 's',
  'unit.usdiv': 'µs/div', 'unit.msdiv': 'ms/div', 'unit.sdiv': 's/div',
  'unit.nvdiv': 'nV/div', 'unit.uvdiv': 'µV/div', 'unit.mvdiv': 'mV/div', 'unit.vdiv': 'V/div',
  'unit.percent': '%', 'unit.px': 'px', 'unit.db': 'dB', 'unit.div': 'div',
};

// The model imports t() from i18n; headless that just returns the key, so we
// patch each Unit's suffix() to the static table above for deterministic
// formatting and suffix matching.
const { NumericStepModel, UNIT_FAMILIES } = await import('../js/widgets/numeric-step-field.js');

for (const fam of Object.values(UNIT_FAMILIES)) {
  for (const u of fam.units) {
    const key = u.i18nKey;
    u.suffix = () => (key == null ? '' : (UNIT[key] || key));
  }
}

const EPS = 1e-9;
let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) { passed++; } else { failed++; console.error('FAIL:', msg); } }
function near(a, b, eps, msg) { ok(Math.abs(a - b) <= eps, `${msg} (got ${a}, want ${b})`); }

const F = UNIT_FAMILIES;
const fixed = (family, min, max, w, a, d) => new NumericStepModel({ family, min, max, wheelStep: w, arrowStep: a, decimals: d });
const list = (family, min, max, series, md) => new NumericStepModel({ family, min, max, series, maxDecimals: md });
const pct = (family, min, max, md) => new NumericStepModel({ family, min, max, maxDecimals: md });

// percent up walk
{
  const m = pct(F.FREQUENCY, 1, 192000, 9); m.setValue(1000);
  for (const e of [1100, 1200, 1300, 1400, 1500, 1600, 1700, 1800, 1900, 2000, 2200]) { m.wheel(+1); near(m.getValue(), e, EPS, 'up walk'); }
}
// percent down walk
{
  const m = pct(F.FREQUENCY, 1, 192000, 9); m.setValue(1000);
  for (const e of [900, 800, 700, 600, 500, 400, 300, 200, 100, 90]) { m.wheel(-1); near(m.getValue(), e, EPS, 'down walk'); }
}
// off-grid down
{
  const m = pct(F.FREQUENCY, 1, 192000, 9);
  m.setValue(1300); m.wheel(-1); near(m.getValue(), 1200, EPS, 'off-grid 1300->1200');
  m.setValue(2200); m.wheel(-1); near(m.getValue(), 2100, EPS, '2200->2100'); m.wheel(-1); near(m.getValue(), 2000, EPS, '2100->2000');
}
// clamp
{
  const m = pct(F.FREQUENCY, 1, 192000, 9);
  m.setValue(192000); m.wheel(+1); near(m.getValue(), 192000, EPS, 'max clamp');
  m.setValue(1); m.wheel(-1); near(m.getValue(), 1, EPS, 'min clamp');
}
// arrows displayed unit
{
  const m = pct(F.FREQUENCY, 1, 384000, 9);
  m.setValue(192000); m.arrow(+1); near(m.getValue(), 193000, EPS, '+1kHz');
  m.setValue(500); m.arrow(+1); near(m.getValue(), 501, EPS, '+1Hz');
}
{
  const m = pct(F.AMPLITUDE, 1e-6, 10, 5); m.setValue(0.2); m.arrow(+1); near(m.getValue(), 0.201, EPS, '+1mV');
}
// dbv 10dB grid
{
  const m = pct(F.AMPLITUDE, 1e-6, 10, 5);
  ok(m.commit('0 dBV'), 'commit 0 dBV');
  m.wheel(-1); near(m.getValue(), Math.pow(10, -10 / 20), EPS, '0->-10');
  m.wheel(-1); near(m.getValue(), Math.pow(10, -20 / 20), EPS, '-10->-20');
  m.wheel(+1); near(m.getValue(), Math.pow(10, -10 / 20), EPS, '-20->-10');
  ok(m.commit('-3.5 dBV'), 'commit -3.5'); m.wheel(-1); near(m.getValue(), Math.pow(10, -10 / 20), EPS, '-3.5 down ->-10');
  ok(m.commit('-3.5 dBV'), 'commit -3.5b'); m.wheel(+1); near(m.getValue(), 1.0, EPS, '-3.5 up ->0');
}
// list named first
{
  const m = list(F.NONE, 4096, 10000000, [192000, 8192, 16384], 0); m.setValue(192000);
  m.wheel(+1); near(m.getValue(), 8192, EPS, 'head->first'); m.wheel(-1); near(m.getValue(), 192000, EPS, 'back to head');
  m.wheel(-1); near(m.getValue(), 192000, EPS, 'start saturates'); m.setValue(16384); m.wheel(+1); near(m.getValue(), 16384, EPS, 'end saturates');
}
// log persist/restore
{
  const m = pct(F.AMPLITUDE, 1e-6, 10, 5); m.setValue(0.5); m.setLogDisplay(true);
  ok(m.isLogDisplay(), 'isLogDisplay'); ok(m.text().endsWith('dBV'), 'text dBV: ' + m.text());
  ok(m.commit('0.7'), 'commit 0.7'); ok(!m.isLogDisplay(), 'released');
  ok(m.commit('-6 dBV'), 'commit -6dBV'); ok(m.isLogDisplay(), 'sticky again');
}
// named value
{
  const m = list(F.NONE, 8192, 10000000, [8192, 131072, 262144], 0); m.setNamedValue(192000, 'Nyquist/2'); m.setValue(192000);
  ok(m.text() === 'Nyquist/2', 'named text'); ok(m.commit('nyquist/2'), 'parse label'); near(m.getValue(), 192000, EPS, 'label value');
  ok(m.acceptsPartial('Nyquist/2'), 'partial label'); m.wheel(+1); near(m.getValue(), 262144, EPS, 'step as number');
  ok(m.commit('8192'), 'commit 8192'); ok(m.text() === '8192', 'plain render');
}
// arrow dbv
{
  const m = pct(F.AMPLITUDE, 1e-6, 10, 5); ok(m.commit('-3 dBV'), 'commit -3dBV'); m.arrow(+1); near(m.getValue(), Math.pow(10, -2 / 20), EPS, '+1dB');
}
// list saturate
{
  const m = list(F.VOLTS_PER_DIV, 1e-6, 1e-5, [1e-6, 2e-6, 5e-6, 1e-5], 3); m.setValue(1e-6);
  m.wheel(+1); near(m.getValue(), 2e-6, 1e-15, 'l1'); m.wheel(+1); near(m.getValue(), 5e-6, 1e-15, 'l2');
  m.setValue(1e-5); m.wheel(+1); near(m.getValue(), 1e-5, 1e-15, 'top'); m.wheel(-1); near(m.getValue(), 5e-6, 1e-15, 'down');
}
// off-list nearest
{
  const m = list(F.VOLTS_PER_DIV, 1e-6, 1e-5, [1e-6, 2e-6, 5e-6, 1e-5], 3);
  m.setValue(3e-6); m.wheel(+1); near(m.getValue(), 5e-6, 1e-15, 'off up'); m.setValue(3e-6); m.wheel(-1); near(m.getValue(), 2e-6, 1e-15, 'off down');
}
// infinity
{
  const m = list(F.NONE, 2, Infinity, [2, 4, 8, 16, 32, 64, 128, Infinity], 0); m.setValue(128);
  m.wheel(+1); ok(!Number.isFinite(m.getValue()), 'inf'); ok(m.text() === '∞', 'inf text'); m.wheel(-1); near(m.getValue(), 128, EPS, 'back');
  ok(m.commit('inf'), 'commit inf'); ok(!Number.isFinite(m.getValue()), 'inf again');
}
{
  const m = list(F.NONE, 2, 8, [2, 4, 8], 0); m.setValue(4); ok(!m.commit('∞'), 'reject inf bounded'); near(m.getValue(), 4, EPS, 'unchanged');
}
// fixed
{
  const m = fixed(F.DECIBEL, 10, 140, 10, 1, 1); m.setValue(100); m.wheel(+1); near(m.getValue(), 110, EPS, 'wheel10'); m.arrow(-1); near(m.getValue(), 109, EPS, 'arrow1'); m.setValue(140); m.wheel(+1); near(m.getValue(), 140, EPS, 'max');
}
{
  const m = fixed(F.DIVISIONS, 0, 5, 0.1, 0.1, 1); m.setValue(0); m.wheel(+1); near(m.getValue(), 0.1, EPS, 'hyst'); m.setValue(5); m.arrow(+1); near(m.getValue(), 5, EPS, 'maxclamp');
}
// parsing freq
{
  const m = pct(F.FREQUENCY, 1, 192000, 9);
  ok(m.commit('1.5 kHz'), 'p1'); near(m.getValue(), 1500, EPS, 'v1'); ok(m.commit('200'), 'p2'); near(m.getValue(), 200, EPS, 'v2');
  ok(m.commit('1,5kHz'), 'p3'); near(m.getValue(), 1500, EPS, 'v3'); ok(!m.commit('12 parsec'), 'reject'); near(m.getValue(), 1500, EPS, 'unchanged');
}
{
  const m = pct(F.FREQUENCY, 1, 384000, 9); m.setValue(999); ok(m.text().endsWith('Hz') && !m.text().endsWith('kHz'), 'Hz'); m.setValue(1234.5); ok(m.text().endsWith('kHz'), 'kHz ' + m.text()); ok(m.commit(m.text()), 'roundtrip'); near(m.getValue(), 1234.5, EPS, 'rt val');
}
{
  const m = pct(F.AMPLITUDE, 1e-6, 10, 5);
  ok(m.commit('499 mV'), 'mv'); near(m.getValue(), 0.499, EPS, 'mvv'); ok(m.commit('250 uV'), 'uv'); near(m.getValue(), 2.5e-4, EPS, 'uvv');
  ok(m.commit('-3.5 dBV'), 'dbv'); near(m.getValue(), Math.pow(10, -3.5 / 20), EPS, 'dbvv'); ok(m.text().endsWith('dBV'), 'sticky');
  ok(m.commit('0.7'), 'v'); near(m.getValue(), 0.7, EPS, 'vv'); ok(m.text().endsWith('V') && !m.text().endsWith('dBV'), 'released');
}
{
  const m = pct(F.TIME, 1e-3, 1000000, 3); m.setValue(0.4); ok(m.text().endsWith('ms'), 'ms ' + m.text()); ok(m.commit(m.text()), 'rt'); near(m.getValue(), 0.4, EPS, 'val'); m.setValue(2.5); ok(m.text().endsWith('s') && !m.text().endsWith('ms'), 's');
}
{
  // Suffix-less input is V/div, the BASE unit (UnitFamily.VOLTS_PER_DIV(3)); an explicit suffix still wins.
  const m = list(F.VOLTS_PER_DIV, 1e-6, 500, [1e-6, 1e-3, 1.0], 3); m.setValue(2e-3); ok(m.commit('5'), 'perdiv'); near(m.getValue(), 5, EPS, 'perdivv'); ok(m.commit('5 mv/div'), 'perdivm'); near(m.getValue(), 5e-3, EPS, 'perdivmv');
}
{
  const m = pct(F.FREQUENCY, 1, 192000, 9); ok(m.commit('300 kHz'), 'c'); near(m.getValue(), 192000, EPS, 'nyq'); ok(m.commit('0.2'), 'c2'); near(m.getValue(), 1, EPS, 'min');
}
{
  const m = pct(F.FREQUENCY, 1, 192000, 9); m.setValue(150000); m.setMax(96000); near(m.getValue(), 96000, EPS, 'reclamp');
}
// regressions
{
  const m = pct(F.TIME, 0, 1000000, 3); m.setValue(0); m.wheel(+1); near(m.getValue(), 1e-6, 1e-15, 'zero lsb'); ok(Number.isFinite(m.getValue()), 'finite');
}
{
  const m = pct(F.PERCENT, 0.001, 99.999, 3); m.setValue(0.001); m.wheel(+1); near(m.getValue(), 0.002, EPS, 'duty up'); ok(m.commit(m.text()), 'commit'); near(m.getValue(), 0.002, EPS, 'no snap'); m.wheel(-1); near(m.getValue(), 0.001, EPS, 'down');
}
{
  const m = list(F.NONE, 8192, 10000000, [8192, 16384, 4194304], 0); m.setValue(6000000); m.wheel(+1); near(m.getValue(), 6000000, EPS, 'saturate above'); m.wheel(-1); near(m.getValue(), 4194304, EPS, 'reenter');
}
{
  const m = pct(F.FREQUENCY, 1, 192000, 9); ok(m.commit('200,'), 'dangling,'); near(m.getValue(), 200, EPS, 'v'); ok(m.commit('315.'), 'dangling.'); near(m.getValue(), 315, EPS, 'v2'); ok(!m.commit('.'), 'lone sep reject');
}
{
  const m = pct(F.AMPLITUDE, 1e-6, 10, 5); ok(m.commit('5 μV'), 'greek mu'); near(m.getValue(), 5e-6, 1e-15, 'v');
}
{
  const m = pct(F.AMPLITUDE, 1e-6, 10, 5);
  ok(m.acceptsPartial('-'), 'p-'); ok(m.acceptsPartial('1e-'), 'pe'); ok(m.acceptsPartial('5.'), 'p.'); ok(m.acceptsPartial('1,'), 'p,'); ok(m.acceptsPartial('1.5 dB'), 'pdb'); ok(m.acceptsPartial('∞'), 'pinf');
}
{
  const m = pct(F.TIME, 0, 100, 3); m.setValue(2.5); m.setValue(NaN); near(m.getValue(), 2.5, EPS, 'nan ignored');
}
// unit-in-text regressions - the input now carries the WHOLE composed string
// ("1 kHz"), exactly like Java's single SWT Text.  These pin the bug fix:
//   (a) setValue(1000) in a FREQUENCY field renders "1 kHz" verbatim,
//   (b) an ordinary user edit "1 kHz" -> "1.5 kHz" reparses in-unit -> 1500,
//   (c) a bare "1.5" is the base unit (Hz) -> 1.5,
//   (d) committing the untouched composed text round-trips the value.
{
  const m = pct(F.FREQUENCY, 1, 192000, 9); m.setValue(1000);
  ok(m.text() === '1 kHz', 'compose 1000 -> "1 kHz" (got "' + m.text() + '")');
  ok(m.commit('1.5 kHz'), 'edit "1.5 kHz"'); near(m.getValue(), 1500, EPS, 'edit -> 1500');
  ok(m.commit('1.5'), 'bare "1.5"'); near(m.getValue(), 1.5, EPS, 'bare -> 1.5 Hz');
  m.setValue(1000); ok(m.commit(m.text()), 'untouched commit'); near(m.getValue(), 1000, EPS, 'round-trip 1000');
}
{
  // amplitude auto-ranges the suffix into the same one composed string
  const m = pct(F.AMPLITUDE, 1e-9, 10, 5); m.setValue(1e-4);
  ok(m.text() === '100 µV', 'compose 1e-4 V -> "100 µV" (got "' + m.text() + '")');
  ok(m.commit(m.text()), 'untouched commit µV'); near(m.getValue(), 1e-4, 1e-15, 'round-trip 100 µV');
}
{
  // NONE family has an empty suffix -> just the number, no separator
  const m = list(F.NONE, 2, 64, [2, 4, 8, 16, 32, 64], 0); m.setValue(16);
  ok(m.text() === '16', 'NONE compose -> "16" (got "' + m.text() + '")');
}
// Partial-unit alias matrix (UnitFamily: aliases are family-scoped and matched
// EXACTLY - "not by prefix", so kHz vs Hz can never collide).  FREQUENCY:
// k / kh / khz are kHz; hz is Hz; bare "h" names NO unit and the commit is
// rejected with the value unchanged.  A frequency alias typed into an
// AMPLITUDE field ("5k") is likewise rejected-unchanged.
{
  const m = pct(F.FREQUENCY, 1, 192000, 9);
  ok(m.commit('1.5k'), 'alias k'); near(m.getValue(), 1500, EPS, '1.5k -> 1500');
  ok(m.commit('1.5kh'), 'alias kh'); near(m.getValue(), 1500, EPS, '1.5kh -> 1500');
  ok(m.commit('2khz'), 'alias khz'); near(m.getValue(), 2000, EPS, '2khz -> 2000');
  ok(m.commit('250hz'), 'alias hz'); near(m.getValue(), 250, EPS, '250hz -> 250');
  ok(!m.commit('3h'), 'bare "h" is not a unit (exact match, no prefix)');
  near(m.getValue(), 250, EPS, 'rejected "3h" leaves the value unchanged');
}
{
  const m = pct(F.AMPLITUDE, 1e-9, 10, 5); m.setValue(0.5);
  ok(!m.commit('5k'), 'frequency alias rejected in a voltage field');
  near(m.getValue(), 0.5, EPS, 'rejected "5k" leaves the value unchanged');
}

// ---------------------------------------------------------------------------
// FULL-PARITY AUDIT additions (mirror Java NumericStepModel exactly).
// ---------------------------------------------------------------------------

// Scope V/div LIST series = Java OscParse.VOLT_PER_DIV (1 nV/div ... 500 V/div).
// The app builds it as ladder125(1e-9, 500); pin the nV decade the web port had
// dropped (it started at 1 µV, missing 1/2/5/10/20/50/100/200/500 nV/div), and
// verify the LIST wheel walks the bottom entries in-order like the field arrows.
{
  const vdiv = [];
  for (const dec of [1e-9, 1e-8, 1e-7, 1e-6, 1e-5, 1e-4, 1e-3, 1e-2, 1e-1, 1, 10, 100]) {
    for (const mant of [1, 2, 5]) { const v = mant * dec; if (v >= 1e-9 - 1e-18 && v <= 500 * (1 + 1e-9)) vdiv.push(v); }
  }
  ok(Math.abs(vdiv[0] - 1e-9) < 1e-18, 'V/div series starts at 1 nV/div (got ' + vdiv[0] + ')');
  ok(Math.abs(vdiv[1] - 2e-9) < 1e-18, 'second entry 2 nV/div');
  ok(Math.abs(vdiv[2] - 5e-9) < 1e-18, 'third entry 5 nV/div');
  const m = list(F.VOLTS_PER_DIV, 1e-9, 500, vdiv, 3); m.setValue(1e-9);
  ok(m.text() === '1 nV/div', '1e-9 renders "1 nV/div" (got "' + m.text() + '")');
  m.wheel(+1); near(m.getValue(), 2e-9, 1e-18, 'nV wheel 1n->2n');
  m.wheel(+1); near(m.getValue(), 5e-9, 1e-18, 'nV wheel 2n->5n');
  m.wheel(-1); near(m.getValue(), 2e-9, 1e-18, 'nV wheel 5n->2n');
  m.setValue(1e-9); m.wheel(-1); near(m.getValue(), 1e-9, 1e-18, 'nV wheel saturates at bottom');
}

// Comma decimal separator - Java commit() does text.replace(',', '.') so the
// whole locale (uk/de/fr...) can type "1,5". Accepted in BOTH the partial filter
// and the final commit, with or without a unit suffix.
{
  const m = pct(F.FREQUENCY, 1, 192000, 9);
  ok(m.commit('1,5 kHz'), 'comma commit "1,5 kHz"'); near(m.getValue(), 1500, EPS, '-> 1500');
  ok(m.commit('250,5'), 'comma commit bare "250,5"'); near(m.getValue(), 250.5, EPS, '-> 250.5 Hz');
  ok(m.acceptsPartial('1,'), 'comma partial "1,"'); ok(m.acceptsPartial('1,5'), 'comma partial "1,5"');
  ok(m.acceptsPartial('1,5 k'), 'comma+unit partial "1,5 k"');
}
{
  const m = pct(F.AMPLITUDE, 1e-6, 10, 5);
  ok(m.commit('0,499 V'), 'comma commit "0,499 V"'); near(m.getValue(), 0.499, EPS, '-> 0.499 V');
  ok(m.commit('250,5 uV'), 'comma commit "250,5 uV"'); near(m.getValue(), 250.5e-6, 1e-15, '-> 250.5 µV');
}

// PERCENT policy: wheel and arrow must DIFFER. Wheel = "careful 10 %" that floors
// onto the decade's 10 %-grid; arrow = ±1 in the DISPLAYED unit (±1 kHz once the
// field shows kHz). At 19001.953125 Hz (displayed 19.001953125 kHz):
//   wheel +1 -> 20000 (10 %-grid floor of 19001.95×1.1=20902 onto step 1000),
//   arrow +1 -> 20001.953125 (+1 kHz).
{
  const w = pct(F.FREQUENCY, 0.01, 384000, 9); w.setValue(19001.953125);
  ok(w.text() === '19.001953125 kHz', 'display kHz (got "' + w.text() + '")');
  w.wheel(+1); near(w.getValue(), 20000, EPS, 'PERCENT wheel +1 -> 20000 (10 %-grid)');
  const a = pct(F.FREQUENCY, 0.01, 384000, 9); a.setValue(19001.953125);
  a.arrow(+1); near(a.getValue(), 20001.953125, EPS, 'PERCENT arrow +1 -> +1 kHz');
  ok(w.getValue() !== a.getValue(), 'PERCENT wheel result differs from arrow result');
}

// FIXED policy: wheel and arrow are the two explicit increments (may differ) and
// stay linear (no grid snapping). Mirrors Java fftStopAfterN - NONE family,
// wheelStep 100, arrowStep 1, 0 decimals, clamped [2 .. 1_000_000].
{
  const m = fixed(F.NONE, 2, 1000000, 100, 1, 0); m.setValue(10);
  m.wheel(+1); near(m.getValue(), 110, EPS, 'FIXED wheel +100');
  m.arrow(+1); near(m.getValue(), 111, EPS, 'FIXED arrow +1');
  m.arrow(-1); near(m.getValue(), 110, EPS, 'FIXED arrow -1');
  m.setValue(50); m.wheel(-1); near(m.getValue(), 2, EPS, 'FIXED wheel -100 clamps at min 2');
  m.setValue(999950); m.wheel(+1); near(m.getValue(), 1000000, EPS, 'FIXED wheel clamps at max');
  ok(m.text() === '1000000', 'FIXED 0-dec render "1000000" (got "' + m.text() + '")');
}

// Step-while-edited: Java NumericStepField.stepArrow/stepWheel do
// `model.commit(field.getText()); model.arrow/wheel(dir)` - the step proceeds
// from the JUST-ENTERED text, not the last committed value. The web _wire mirrors
// this (commit(this._committed()) before every wheel/arrow/button step). Model
// level: commit the typed text, THEN step.
{
  // The step proceeds from the just-ENTERED value (1500), not the seeded 500.
  // 1500 shows "1.5 kHz", so arrow +1 adds 1 kHz (+1 displayed unit) -> 2500.
  const m = pct(F.FREQUENCY, 0.01, 384000, 9); m.setValue(500);
  ok(m.commit('1.5 kHz'), 'edit to "1.5 kHz"'); near(m.getValue(), 1500, EPS, 'entered 1500');
  m.arrow(+1); near(m.getValue(), 2500, EPS, 'arrow steps from ENTERED 1500: +1 kHz -> 2500');
}
{
  // Invalid mid-edit text on step: Java commit() returns false and leaves the
  // value unchanged, so the subsequent step proceeds from the LAST GOOD value.
  const m = pct(F.FREQUENCY, 0.01, 384000, 9); m.setValue(2000);
  ok(!m.commit('12 parsec'), 'invalid edit rejected'); near(m.getValue(), 2000, EPS, 'value held');
  m.arrow(+1); near(m.getValue(), 3000, EPS, 'arrow from held 2000 (+1 kHz -> 3000)');
}

// PARTIAL_INPUT is now a pure legal-character alphabet, NOT a positional
// prefix-grammar. The old grammar silently swallowed any letter typed with the
// caret inside/before the number (field "19.002 kHz", caret after "19", typing
// 'm' -> composed "19m.002 kHz" - digits/dot AFTER the letter - failed the
// filter, so units were untypeable during an in-place edit). acceptsPartial()
// must now ACCEPT these mid-edit states (letters / duplicate separators
// ANYWHERE); commit() stays the strict gate and REJECTS the garbage while still
// ACCEPTING proper entries. Covers FREQUENCY and AMPLITUDE.
{
  const midEdits = ['19k.002 kHz', '1m9.002 kHz', 'k19.002 kHz', '19..002 kHz', '19.002 mkHz'];
  // FREQUENCY family
  {
    const m = pct(F.FREQUENCY, 1, 192000, 9);
    for (const s of midEdits) ok(m.acceptsPartial(s), 'FREQ acceptsPartial mid-edit "' + s + '"');
    for (const bad of ['19k.002 kHz', '19..002']) {
      m.setValue(5000); const before = m.getValue();
      ok(!m.commit(bad), 'FREQ commit rejects garbage "' + bad + '"');
      near(m.getValue(), before, EPS, 'FREQ value unchanged after rejected "' + bad + '"');
    }
    ok(m.commit('19.002 kHz'), 'FREQ commit accepts "19.002 kHz"'); near(m.getValue(), 19002, EPS, '-> 19002');
    ok(m.commit('2k'), 'FREQ commit accepts "2k"'); near(m.getValue(), 2000, EPS, '-> 2000');
    ok(m.commit('1,5 kHz'), 'FREQ commit accepts "1,5 kHz"'); near(m.getValue(), 1500, EPS, '-> 1500');
  }
  // AMPLITUDE family
  {
    const m = pct(F.AMPLITUDE, 1e-6, 10, 5);
    for (const s of midEdits) ok(m.acceptsPartial(s), 'AMP acceptsPartial mid-edit "' + s + '"');
    for (const bad of ['19k.002 kHz', '19..002']) {
      m.setValue(0.5); const before = m.getValue();
      ok(!m.commit(bad), 'AMP commit rejects garbage "' + bad + '"');
      near(m.getValue(), before, EPS, 'AMP value unchanged after rejected "' + bad + '"');
    }
    ok(m.commit('20 mV'), 'AMP commit accepts "20 mV"'); near(m.getValue(), 0.02, EPS, '-> 0.02 V');
  }
}

// Blank state (mirror Java NumericStepModelTest blank cases): setBlank renders empty
// and holds no value (the disabled, never-measured calibration channel row); the next
// setValue / successful commit leaves it.
{
  const m = pct(F.VOLTAGE, 1e-9, 10, 5); m.setValue(0.5);
  ok(!m.isBlank(), 'fresh value is not blank');
  m.setBlank();
  ok(m.isBlank(), 'setBlank -> isBlank true');
  ok(m.text() === '', 'blank renders empty text (got "' + m.text() + '")');
  m.setValue(1e-3);
  ok(!m.isBlank(), 'setValue clears blank'); near(m.getValue(), 1e-3, 1e-15, 'value set after blank');
  m.setBlank(); ok(m.isBlank(), 're-blanked');
  ok(m.commit('20 mV'), 'commit leaves blank'); ok(!m.isBlank(), 'commit cleared blank');
  near(m.getValue(), 0.02, EPS, 'committed value after blank -> 0.02 V');
}

// VOLTAGE family: nV/µV/mV/V, AMPLITUDE's linear switching WITHOUT the log dBV unit
// (calibration-value entry, where a dB reference makes no sense).
{
  ok(F.VOLTAGE.logUnit() == null, 'VOLTAGE has no logarithmic (dBV) unit');
  const m = pct(F.VOLTAGE, 1e-9, 10, 5);
  m.setValue(1e-4); ok(m.text() === '100 µV', 'VOLTAGE 1e-4 -> "100 µV" (got "' + m.text() + '")');
  m.setValue(0.4); ok(m.text() === '400 mV', 'VOLTAGE 0.4 -> "400 mV" (got "' + m.text() + '")');
  m.setValue(2); ok(m.text() === '2 V', 'VOLTAGE 2 -> "2 V" (got "' + m.text() + '")');
  ok(!m.commit('3 dBV'), 'VOLTAGE rejects a dBV suffix (no log unit)');
}

// ---------------------------------------------------------------------------
// DITHER policy - full-scale-aware bits⇄dBV, ±1-bit / ±10-dBV, Off at top
// (mirror NumericStepModelTest.java DITHER cases).
// ---------------------------------------------------------------------------
const DITHER_DB_PER_BIT = 6.0206;
const DITHER_OFFSET_DB = 7.782;
// Peak full-scale = 1 Vpeak -> 20·log10(1) = 0 dBV -> clean anchor.
const dither = (maxBits) => new NumericStepModel({ family: F.DITHER, maxBits, fsAmplSupplier: () => 1.0 });
const ditherDbv = (bits) => -(bits - 1) * DITHER_DB_PER_BIT - DITHER_OFFSET_DB;   // fsDbv = 0
const ditherBits = (dbv) => 1 + (-DITHER_OFFSET_DB - dbv) / DITHER_DB_PER_BIT;    // inverse
const fmt1 = (x) => x.toFixed(1);

// bits view + companion dBV, and dBV view + companion bits are full-scale-aware inverses.
{
  const m = dither(16); m.setValue(16);
  ok(!m.isLogDisplay(), 'DITHER default bits view');
  ok(m.text() === '16 bits', 'DITHER 16 -> "16 bits" (got "' + m.text() + '")');
  ok(m.companionText() === fmt1(ditherDbv(16)) + ' dBV', 'DITHER bits-view companion is dBV (got "' + m.companionText() + '")');
  m.setLogDisplay(true);
  ok(m.isLogDisplay(), 'DITHER dBV view');
  ok(m.text() === fmt1(ditherDbv(16)) + ' dBV', 'DITHER dBV text (got "' + m.text() + '")');
  ok(m.companionText() === '16 bits', 'DITHER dBV-view companion is bits (got "' + m.companionText() + '")');
}
// dBV entry stores fractional bits and sticks the dBV view (short aliases too).
{
  const m = dither(24);
  ok(m.commit('-95 dBV'), 'DITHER commit "-95 dBV"'); near(m.getValue(), ditherBits(-95), 1e-9, 'DITHER dBV -> fractional bits');
  ok(m.isLogDisplay(), 'DITHER dBV entry sticks the dBV view');
  ok(m.commit('-95 db'), 'DITHER short dBV alias "db"'); near(m.getValue(), ditherBits(-95), 1e-9, 'DITHER db alias bits');
  ok(m.commit('-95 d'), 'DITHER short dBV alias "d"'); near(m.getValue(), ditherBits(-95), 1e-9, 'DITHER d alias bits');
}
// dBV anchors to the PEAK full-scale (20·log10(fsAmpl)), NOT the RMS full-scale (/√2).
{
  const fsAmpl = 2.79351;
  const m = new NumericStepModel({ family: F.DITHER, maxBits: 24, fsAmplSupplier: () => fsAmpl });
  ok(m.commit('-100 dBV'), 'DITHER commit "-100 dBV" @ real FS');
  const fsDbv = 20 * Math.log10(fsAmpl);
  const expBits = 1 + (fsDbv - DITHER_OFFSET_DB - (-100)) / DITHER_DB_PER_BIT;
  near(m.getValue(), expBits, 1e-9, 'DITHER peak-FS anchor bits');
  const rmsBits = 1 + (fsDbv - 20 * Math.log10(Math.SQRT2) - DITHER_OFFSET_DB - (-100)) / DITHER_DB_PER_BIT;
  ok(Math.abs(m.getValue() - rmsBits) > 0.4, 'DITHER must NOT use the RMS anchor (/√2)');
}
// The dBV is the PHYSICAL TPDF level: window-INVARIANT, with no FFT-window ENBW term. (That term
// once existed to cancel the analyser's pre-NENBW over-read; since the NENBW correction the noise
// metrics are honest, so the dither mapping must not carry it - and an analyser display setting
// must never move the generated output.)
{
  const a = new NumericStepModel({ family: F.DITHER, maxBits: 24, fsAmplSupplier: () => 1.0 });
  const b = new NumericStepModel({ family: F.DITHER, maxBits: 24, fsAmplSupplier: () => 1.0 });
  ok(a.commit('-100 dBV'), 'DITHER commit a'); ok(b.commit('-100 dBV'), 'DITHER commit b');
  near(a.getValue(), b.getValue(), 1e-12, 'DITHER dBV->bits is window-invariant');
  near(a.getValue(), ditherBits(-100), 1e-9, 'DITHER dBV->bits is the plain physical mapping');
}
// The ENTERED value is what a dBV dither stores - the LEVEL that was typed. Replaying it under a
// new calibration therefore shows the same figure and re-solves the bits beneath it (a DAC
// recalibration - the only config the mapping depends on).
{
  let fsAmpl = 1.0;
  const m = new NumericStepModel({ family: F.DITHER, maxBits: 30, fsAmplSupplier: () => fsAmpl });
  ok(m.commit('-100 dBV'), 'DITHER entered seed'); const bits0 = m.getValue();
  ok(m.enteredValue().unit === 'dbv', 'DITHER entered unit is the level view');
  near(m.enteredValue().value, -100, 1e-9, 'DITHER entered number is the level');
  // Captured under the OLD calibration, which is the point: the store holds the pair, and it is
  // not re-derived from the canonical value afterwards.
  const stored = m.enteredValue();
  fsAmpl = 2.0;                                  // +6.0206 dBV of full scale -> +1 bit
  ok(m.seedPair(stored), 'DITHER replay of the entered value');
  near(m.enteredValue().value, -100, 1e-9, 'DITHER the entered level is what holds');
  near(m.getValue(), bits0 + 20 * Math.log10(2) / DITHER_DB_PER_BIT, 1e-9, 'DITHER bits move by the full-scale delta');
}
// A bits entry holds the physical depth across the same change.
{
  let fsAmpl = 1.0;
  const m = new NumericStepModel({ family: F.DITHER, maxBits: 30, fsAmplSupplier: () => fsAmpl });
  ok(m.commit('16 bits'), 'DITHER bits seed');
  ok(m.enteredValue().unit === 'bits', 'DITHER entered unit is bits');
  fsAmpl = 2.0;
  ok(m.seedPair(m.enteredValue()), 'DITHER replay in bits');
  near(m.getValue(), 16, EPS, 'DITHER bits view holds the physical dither');
}
// Off has no level, so it is stored in the base unit whatever view is sticky.
{
  const m = dither(24);
  ok(m.commit('-100 dBV'), 'DITHER Off seed'); ok(m.commit('off'), 'DITHER off keeps the dBV view');
  ok(m.enteredValue().unit === 'bits', 'DITHER Off is a depth of none, not a level');
  near(m.enteredValue().value, 0, EPS, 'DITHER Off number');
}
// A scaled linear unit never sticks, so what a millivolt entry means is its volts figure.
{
  const m = new NumericStepModel({ family: F.AMPLITUDE, min: 1e-9, max: 100, maxDecimals: 9 });
  ok(m.commit('300 mV'), 'AMPLITUDE mV entry');
  ok(m.enteredValue().unit === 'v', 'AMPLITUDE mV stores the base unit');
  near(m.enteredValue().value, 0.3, EPS, 'AMPLITUDE mV number in volts');
}
// A dBFS entry is only meaningful against the calibration: the same entered value is a different
// voltage once the full scale moves, which is why the pair - not the voltage - is what is stored.
{
  let fsAmpl = 2.0;
  const m = new NumericStepModel({ family: F.AMPLITUDE, min: 1e-4, max: 100, maxDecimals: 5,
    fsAmplSupplier: () => fsAmpl });
  ok(m.commit('-6 dBFS'), 'AMPLITUDE dBFS entry');
  ok(m.enteredValue().unit === 'dbfs', 'AMPLITUDE dBFS sticks');
  near(m.enteredValue().value, -6, 1e-9, 'AMPLITUDE dBFS number');
  const vrms = m.getValue(), stored = m.enteredValue();
  fsAmpl = 4.0;
  ok(m.seedPair(stored), 'AMPLITUDE replay of the entered dBFS');
  near(m.enteredValue().value, -6, 1e-9, 'AMPLITUDE the entered figure is unmoved');
  near(m.getValue(), 2 * vrms, 1e-9, 'AMPLITUDE twice the full scale is twice the volts');
}
// bits entry clamps, 0/off -> Off, fractional bits + short alias.
{
  const m = dither(16);
  ok(m.commit('12 bits'), 'DITHER commit "12 bits"'); near(m.getValue(), 12, EPS, 'DITHER 12'); ok(!m.isLogDisplay(), 'DITHER bits view');
  ok(m.commit('20'), 'DITHER commit "20"'); near(m.getValue(), 16, EPS, 'DITHER clamp to max 16');
  ok(m.commit('15.5 b'), 'DITHER "15.5 b"'); near(m.getValue(), 15.5, EPS, 'DITHER fractional bits');
  ok(m.commit('0'), 'DITHER commit "0"'); near(m.getValue(), 0, EPS, 'DITHER 0 -> Off'); ok(m.text() === 'Off', 'DITHER Off text');
  ok(m.commit('off'), 'DITHER commit "off"'); near(m.getValue(), 0, EPS, 'DITHER off word');
  ok(!m.commit(''), 'DITHER empty entry rejected (value unchanged)'); near(m.getValue(), 0, EPS, 'DITHER still Off');
}
// bits stepping: whole ±1-bit steps with Off at the TOP.
{
  const m = dither(16); m.setValue(2);
  m.arrow(+1); near(m.getValue(), 1, EPS, 'DITHER up 2->1');
  m.arrow(+1); near(m.getValue(), 0, EPS, 'DITHER up 1->Off (top)');
  m.arrow(+1); near(m.getValue(), 0, EPS, 'DITHER up Off stays Off');
  m.arrow(-1); near(m.getValue(), 1, EPS, 'DITHER down Off->1');
  m.setValue(16); m.arrow(-1); near(m.getValue(), 16, EPS, 'DITHER down at max saturates');
}
// bits stepping preserves a fraction.
{
  const m = dither(24); ok(m.commit('15.5 bits'), 'DITHER frac seed');
  m.arrow(-1); near(m.getValue(), 16.5, EPS, 'DITHER down keeps fraction'); m.arrow(+1); near(m.getValue(), 15.5, EPS, 'DITHER up keeps fraction');
}
// dBV stepping walks exactly 10 dBV (fractional bits, no snap); up from 1 bit -> Off.
{
  const m = dither(16); m.setLogDisplay(true); m.setValue(16);
  m.wheel(+1); near(m.getValue(), ditherBits(ditherDbv(16) + 10), EPS, 'DITHER +10 dBV');
  m.setValue(16); m.wheel(-1); near(m.getValue(), 16, EPS, 'DITHER -10 dBV clamps at max');
  m.setValue(1); m.wheel(+1); near(m.getValue(), 0, EPS, 'DITHER up from loudest bit -> Off');
}
// Off has no companion view.
{
  const m = dither(16); m.setValue(0);
  ok(m.text() === 'Off', 'DITHER Off text'); ok(m.companionText() === '', 'DITHER Off has no companion');
}

console.log(`\nNumericStepModel: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
