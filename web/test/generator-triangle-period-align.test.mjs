/*
 * Phonalyser web - TRIANGLE period-alignment contract test.
 *
 * The maintainer's Java fix (GeneratorController.emitFrequency) makes TRIANGLE join
 * RECTANGLE as a period-aligned form: the duty corner is a derivative discontinuity that,
 * off an integer-sample period, drifts against the sample grid cycle to cycle and smears
 * the tone - exactly the rectangle's hard-edge problem. So TRIANGLE is now driven, exported
 * and bracketed at the integer-sample-period frequency fs/N (N = max(2, round(fs/f))), not
 * its raw entered value.
 *
 * Asserts, at 10 kHz @ 48 kHz (N = round(48000/10000) = 5 -> fs/N = 9600 Hz):
 *   1. GeneratorController._genEmitFreq() (the REAL live-emit method) = 9600 Hz for TRIANGLE,
 *      identical to RECTANGLE, and computeAnalysisFreqs() populates gen.snapped = 9600.
 *   2. A raw-emitting form (SINE, snap off) is left on its raw 10000 Hz - the gate is
 *      period-aligned-forms-only.
 *   3. The export-path frequency (the inline fs/N expression at generator-pane.js
 *      #genSaveBtn) = 9600 Hz for TRIANGLE.
 *   4. The pane duty bracket k/n quantisation (the inline formula at
 *      GeneratorPane.updateDutyLabel) for TRIANGLE at 50 % -> 60.000 % (n=5, k=round(2.5)=3,
 *      pct=3*100/5), byte-identical to RECTANGLE - the two forms now share one code path.
 *
 * (1)-(2) exercise the real controller method. (3)-(4) reproduce the pane's inline
 * expressions verbatim - the pane methods need a live DOM/jQuery and can't be invoked
 * headless - so they pin the exact math the ported gate must produce; the DOM-level bracket
 * assertions live in the e2e scope-smoke C33 checks.
 *
 * Run: node web/test/generator-triangle-period-align.test.mjs
 */
globalThis.window = globalThis.window || { addEventListener: () => {} };
globalThis.performance = globalThis.performance || { now: () => Date.now() };
globalThis.AudioContext = globalThis.AudioContext || function () {};   // never instantiated (we never startGenerator)

const { GeneratorController } = await import('../js/generator/generator-controller.js');
const { GenSignalForm } = await import('../js/enums/gen-signal-form.js');
const { testPrefs } = await import('./_test-prefs.mjs');

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) { passed++; } else { failed++; console.error('FAIL:', msg); } }

const FS = 48000, RAW = 10000, EXPECT = 9600;   // 48000 / round(48000/10000) = 48000 / 5

function makePrefs(form) {
  return testPrefs({ genSignalForm: form, genFrequencyHz: RAW, genDualToneFreq1Hz: RAW,
    outputSampleRate: FS, inputSampleRate: FS, fftLength: 32768, genSnapToFftBin: false,
    genDualToneFreq2Hz: 11000, genAmplitudeVrms: 0.5, dacFsVoltageAmpl: Math.SQRT2, genDualToneSplitPct: 50 });
}

// --- (1) TRIANGLE live emit is period-aligned (fs/N), like RECTANGLE ---------
{
  const gen = new GeneratorController(makePrefs(GenSignalForm.TRIANGLE), { status: () => {} });
  const emit = gen._genEmitFreq();   // outSampleRate is 0 (no output ctx) -> falls back to c.outRate = FS
  ok(emit === EXPECT, `TRIANGLE _genEmitFreq() = ${EXPECT} Hz (got ${emit})`);
  gen.computeAnalysisFreqs();
  ok(gen.snapped === EXPECT, `TRIANGLE computeAnalysisFreqs() -> gen.snapped = ${EXPECT} Hz (got ${gen.snapped})`);
}

// --- RECTANGLE emits the SAME value - both are period-aligned now ------------
{
  const gen = new GeneratorController(makePrefs(GenSignalForm.RECTANGLE), { status: () => {} });
  ok(gen._genEmitFreq() === EXPECT, `RECTANGLE _genEmitFreq() = ${EXPECT} Hz (matches TRIANGLE)`);
}

// --- (2) a raw-emitting form (SINE, snap off) is NOT period-aligned ----------
{
  const gen = new GeneratorController(makePrefs(GenSignalForm.SINE), { status: () => {} });
  ok(gen._genEmitFreq() === RAW, `SINE (snap off) _genEmitFreq() stays raw ${RAW} Hz (got ${gen._genEmitFreq()})`);
}

// --- (3) export path emits fs/N for TRIANGLE (generator-pane.js #genSaveBtn) --
{
  const form = GenSignalForm.TRIANGLE, rate = FS, rawHz = RAW;
  const emitHz = (form === GenSignalForm.RECTANGLE || form === GenSignalForm.TRIANGLE)
    ? rate / Math.max(2, Math.round(rate / rawHz)) : rawHz;
  ok(emitHz === EXPECT, `export path emitHz = ${EXPECT} Hz for TRIANGLE (got ${emitHz})`);
}

// --- (4) duty bracket k/n quantisation for TRIANGLE (GeneratorPane.updateDutyLabel) --
function dutyBracketPct(form, rawHz, fs, dutyPct) {
  if (form !== GenSignalForm.RECTANGLE && form !== GenSignalForm.TRIANGLE) return null;
  const n = (rawHz > 0 && fs > 0) ? Math.max(2, Math.round(fs / rawHz)) : 2;
  let k = Math.round((dutyPct / 100) * n);
  if (n > 1) k = Math.max(1, Math.min(n - 1, k));
  return k * 100 / n;
}
{
  const triPct = dutyBracketPct(GenSignalForm.TRIANGLE, RAW, FS, 50);   // n=5, k=round(2.5)=3 -> 60
  const rectPct = dutyBracketPct(GenSignalForm.RECTANGLE, RAW, FS, 50);
  ok(triPct === 60, `TRIANGLE 50 % duty quantises to 60.000 % on the fs/N=5-sample grid (got ${triPct})`);
  ok(triPct === rectPct, `TRIANGLE and RECTANGLE share the duty-quantisation math (${triPct} === ${rectPct})`);
  ok(dutyBracketPct(GenSignalForm.SINE, RAW, FS, 50) === null, 'SINE has no duty bracket (plain label)');
}

console.log(`\ngenerator-triangle-period-align: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
