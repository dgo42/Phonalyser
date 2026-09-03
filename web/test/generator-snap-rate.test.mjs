/*
 * Phonalyser web - the snap grid is the ANALYSIS rate's, not the DAC's.
 *
 * Port of the Java GeneratorLaneSnapRateTest. An FFT bin is a property of the ANALYSIS,
 * whose grid is fs_in / N on the CAPTURED signal, so a tone snapped on the output clock
 * lands between bins whenever the two rates differ - the leakage the snap exists to avoid,
 * and for a dual tone the smearing manufactures the very intermodulation products the IMD
 * table then reports. The whole-sample-period correction of RECTANGLE / TRIANGLE answers to
 * the OTHER clock: the edge has to land on a sample the DAC emits.
 *
 * Asserts, with capture at 48 kHz against a DAC at 384 kHz, 4096-point FFT, snap on:
 *   1. analysisSampleRate() is the capture rate, and a 997 Hz sine emits on the 48 kHz grid
 *      (11 * 48000/4096 = 128.90625 Hz bins) - never on the 384 kHz one.
 *   2. A capture-rate change moves every bin, so the emitted tone moves with it.
 *   3. Tone 2 of a dual tone is snapped on that same grid.
 *   4. An analyzer asking GENERATOR_EMITTED_HZ at ITS rate is answered on ITS grid, while
 *      the period alignment stays on the DAC's clock whoever asks.
 *   5. The bin grid is the rate over the FFT LENGTH, so the snapped tone follows a length
 *      change exactly as it follows a rate change - on the RUNNING generator, not only at the
 *      next start. With the round that carries the length change to it (FFT_LENGTH_CHANGED,
 *      published by the FFT strip once the new length is in the preferences; the generator
 *      controller's subscriber re-snaps), which consults no analyser state at all.
 *
 * Run: node web/test/generator-snap-rate.test.mjs
 */
globalThis.window = globalThis.window || { addEventListener: () => {} };
globalThis.performance = globalThis.performance || { now: () => Date.now() };
globalThis.AudioContext = globalThis.AudioContext || function () {};   // never instantiated

const { GeneratorController } = await import('../js/generator/generator-controller.js');
const { GenSignalForm } = await import('../js/enums/gen-signal-form.js');
const { testPrefs } = await import('./_test-prefs.mjs');

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) { passed++; } else { failed++; console.error('FAIL:', msg); } }

const RATE_48K = 48000, RATE_96K = 96000, DAC_384K = 384000;
const FFT = 4096, TONE = 997;

/** The preferences a generator reads, with the tone entered in BOTH single- and dual-tone slots. */
function makePrefs(form, tone = TONE, inRate = RATE_48K) {
  return testPrefs({ genSignalForm: form, genFrequencyHz: tone, genDualToneFreq1Hz: tone,
    genDualToneFreq2Hz: tone, outputSampleRate: DAC_384K, inputSampleRate: inRate, fftLength: FFT,
    genSnapToFftBin: true, genAmplitudeVrms: 0.5, dacFsVoltageAmpl: Math.SQRT2, genDualToneSplitPct: 50 });
}

/** The bin grid of a rate, as FftBinSnap computes it - the expectation, derived not typed. */
const snapOn = (rate, raw) => Math.round(raw / (rate / FFT)) * (rate / FFT);

// --- (1) the snap lands on the CAPTURE grid, not the DAC's -------------------
{
  const gen = new GeneratorController(makePrefs(GenSignalForm.SINE), { status: () => {} });
  ok(gen.analysisSampleRate() === RATE_48K, 'the analysis rate is the input side\'s');
  const emit = gen._genEmitFreq();
  ok(emit === snapOn(RATE_48K, TONE),
    `the tone lands on a bin of the spectrum it will be measured in (got ${emit})`);
  ok(emit !== snapOn(DAC_384K, TONE),
    'snapping on the output clock is the defect this pins: a different set of frequencies');
  gen.computeAnalysisFreqs();
  ok(gen.snapped === snapOn(RATE_48K, TONE), 'computeAnalysisFreqs drives the DDS at that same bin');
}

// --- (2) the snapped tone follows a capture-rate change ----------------------
{
  const prefs = makePrefs(GenSignalForm.SINE);
  const gen = new GeneratorController(prefs, { status: () => {} });
  ok(gen._genEmitFreq() === snapOn(RATE_48K, TONE), 'starts on the 48 kHz grid');
  prefs.current().inputSampleRate = RATE_96K;   // what a committed rate edit writes
  ok(gen._genEmitFreq() === snapOn(RATE_96K, TONE),
    'a rate change moves every bin, so the snapped tone has to move with it');
  ok(gen._genEmitFreq() !== snapOn(RATE_48K, TONE), 'a value cached from the previous rate would sit off-bin');
}

// --- (3) the second tone of a dual tone is snapped on the same grid ----------
{
  const prefs = makePrefs(GenSignalForm.DUAL_TONE);
  const gen = new GeneratorController(prefs, { status: () => {} });
  ok(gen._genEmitFreq2() === snapOn(RATE_48K, TONE), 'tone 2 snaps on the analysis grid too');
  prefs.current().inputSampleRate = RATE_96K;
  ok(gen._genEmitFreq2() === snapOn(RATE_96K, TONE), 'and follows the capture rate with tone 1');
}

// --- (4) an asker is answered on ITS grid; the period alignment is the DAC's --
{
  const gen = new GeneratorController(makePrefs(GenSignalForm.SINE), { status: () => {} });
  const [tone1] = gen.emittedHz(RATE_96K);
  ok(tone1 === snapOn(RATE_96K, TONE),
    `an analyzer asking at its own rate is answered on its own grid (got ${tone1})`);

  const rect = new GeneratorController(makePrefs(GenSignalForm.RECTANGLE), { status: () => {} });
  const aligned = DAC_384K / Math.max(2, Math.round(DAC_384K / TONE));
  ok(rect._genEmitFreq() === aligned, 'a rectangle aligns its period to the DAC clock');
  ok(rect.emittedHz(RATE_96K)[0] === aligned,
    'and keeps that clock even for an asker on another rate - the edge lands on an EMITTED sample');
}

// --- (5) the snapped tone follows the FFT LENGTH, on the generator already playing ----------
// A bin is the analysis rate over the FFT length, so the two divisors are equal partners: a
// length change moves every bin just as a rate change does, and the tone the DDS is emitting
// has to move with it rather than wait for the next start.
{
  const LONGER_FFT = 2 * FFT;
  // Doubling the length halves the bin width, so every bin centre of the coarse grid is still a
  // bin centre of the fine one: a tone that happened to sit on one would not move, and would
  // prove nothing. 1000 Hz is one the two grids resolve differently (996.094 vs 1001.953 Hz at
  // 48 kHz), which is what makes the question answerable.
  const LENGTH_TONE = 1000;
  const snapAt = (fftSize, raw) => Math.round(raw / (RATE_48K / fftSize)) * (RATE_48K / fftSize);
  ok(snapAt(FFT, LENGTH_TONE) !== snapAt(LONGER_FFT, LENGTH_TONE),
    'the chosen tone lands on a different frequency under each length');

  const prefs = makePrefs(GenSignalForm.SINE, LENGTH_TONE);
  const gen = new GeneratorController(prefs, { status: () => {} });
  ok(gen._genEmitFreq() === snapAt(FFT, LENGTH_TONE), 'starts on the 4096-point grid');
  prefs.fftLength.set(LONGER_FFT);   // what the FFT-length control writes
  ok(gen._genEmitFreq() === snapAt(LONGER_FFT, LENGTH_TONE),
    'a length change halves the bin width, so the snapped tone has to move with it');
  ok(gen._genEmitFreq() !== snapAt(FFT, LENGTH_TONE),
    'a tone left on the previous length s grid is the reported defect: it sits off-bin');
  gen.computeAnalysisFreqs();
  ok(gen.snapped === snapAt(LONGER_FFT, LENGTH_TONE), 'and the retune drives the DDS at the new bin');

  const dualPrefs = makePrefs(GenSignalForm.DUAL_TONE, LENGTH_TONE);
  const genDual = new GeneratorController(dualPrefs, { status: () => {} });
  dualPrefs.fftLength.set(LONGER_FFT);
  ok(genDual._genEmitFreq2() === snapAt(LONGER_FFT, LENGTH_TONE), 'tone 2 follows the length as well');
}

// --- (5b) THE PUBLISHER: the FFT-length control writes the preference before it announces ------
// Everything above holds only if the new length actually REACHES the preferences before the
// generator reads them. The control's first handler writes prefs.fftLength unconditionally - not
// behind a "while the FFT records" guard, which is the defect this pins: with the FFT stopped and
// a tone playing, the generator once re-snapped against the previous length while the label
// already showed the new grid. Read the handlers and pin the order: the write, then the restart,
// then the announcement - and nothing of the generator's own reaction in it (the FFT strip does
// not decide for the generator what a new grid means; Java FftTabControl publishes
// FFT_LENGTH_CHANGED and the generator side subscribes).
{
  const src = await (await import('node:fs/promises')).readFile(
    new URL('../js/fft/fft-tab-control.js', import.meta.url), 'utf8');
  const iWrite = src.indexOf("$('#fftSize').on('change', () => prefs.fftLength.set(");
  ok(iWrite > 0, 'the length control writes the preference, unconditionally, in its own handler');
  const start = src.indexOf("$('#fftSize').on('change', async ()");
  ok(start > iWrite, 'the restart handler is bound AFTER the write, so the restart reads the new length');
  const handler = src.slice(start, src.indexOf('\n    });', start));
  const iRestart = handler.indexOf('restartFft()');
  const iPublish = handler.indexOf('Events.FFT_LENGTH_CHANGED');
  ok(iRestart > 0 && iPublish > 0 && iRestart < iPublish,
    'the restart comes first, and the new length is announced only AFTER it');
  ok(!handler.includes('retuneGenerator') && !handler.includes("$('#snap')"),
    'the handler carries none of the generator\'s reaction - that is the subscriber\'s');
}

// --- (5c) THE SUBSCRIBER, WITH THE FFT NOT RUNNING. The rule is about the generator, not the
// analyser: a tone playing while the FFT is stopped must follow the length change too. The
// generator controller's own FFT_LENGTH_CHANGED subscriber consults no analyser state at all -
// only whether snap-to-bin is on and whether IT is emitting - so the announcement alone moves a
// playing tone onto the new grid (Java GeneratorController: FFT_LENGTH_CHANGED -> reapplySnap
// when isGenSnapToFftBin).
{
  const { MessageBus } = await import('../js/bus/message-bus.js');
  const { Events } = await import('../js/bus/events.js');
  const LONGER_FFT = 2 * FFT;
  const LENGTH_TONE = 1000;
  const snapAt = (fftSize, raw) => Math.round(raw / (RATE_48K / fftSize)) * (RATE_48K / fftSize);
  const playing = (prefs) => {
    const gen = new GeneratorController(prefs, { status: () => {} });
    gen.computeAnalysisFreqs();
    const posted = [];
    gen._genOn = true;                                    // emitting, no analyser anywhere
    gen._sink = { port: { postMessage: (m) => posted.push(m) } };
    return { gen, posted };
  };

  const prefs = makePrefs(GenSignalForm.SINE, LENGTH_TONE);
  const { gen, posted } = playing(prefs);
  ok(gen.snapped === snapAt(FFT, LENGTH_TONE), 'the DDS is on the 4096-point grid');
  prefs.fftLength.set(LONGER_FFT);                        // the strip wrote the length...
  MessageBus.instance().publish(Events.FFT_LENGTH_CHANGED);   // ...and then announced it
  // (reapplySnap posts the tone-2 retune right behind tone 1, as it does on a trim reset.)
  ok(posted.length >= 1 && posted[0].frequency === snapAt(LONGER_FFT, LENGTH_TONE),
    `the announcement alone re-snaps the playing tone onto the new grid (posted ${JSON.stringify(posted)})`);
  ok(gen.snapped === snapAt(LONGER_FFT, LENGTH_TONE), 'and the controller\'s own emit frequency moved with it');

  const off = makePrefs(GenSignalForm.SINE, LENGTH_TONE);
  off.genSnapToFftBin.set(false);
  const idle = playing(off);
  off.fftLength.set(LONGER_FFT);
  MessageBus.instance().publish(Events.FFT_LENGTH_CHANGED);
  ok(idle.posted.length === 0, 'with snap-to-bin off no grid applies - nothing is retuned');

  const silent = new GeneratorController(makePrefs(GenSignalForm.SINE), { status: () => {} });
  const stray = [];
  silent._sink = { port: { postMessage: (m) => stray.push(m) } };   // a sink, but not emitting
  MessageBus.instance().publish(Events.FFT_LENGTH_CHANGED);
  ok(stray.length === 0, 'and a generator that is not emitting has no tone to move');
}

console.log(`\ngenerator-snap-rate: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
