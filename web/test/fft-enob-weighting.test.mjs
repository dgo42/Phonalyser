/*
 * Phonalyser web - the ENOB basis and the weighting ruling (MT-003 definitions).
 *
 * SINAD and its reciprocal THD+N divide by the PLAIN in-band noise-plus-distortion
 * power - no weighting curve. ENOB refers that same denominator to full scale,
 * where the signal level cancels, so the figure carries no fundamental term at all
 * and neither a de-embedding cascade nor a manually entered fundamental can skew
 * it. The IEC 61672 A-curve survives in ONE readout: N+D, the audibility figure,
 * through its own field.
 *
 * Vocabulary: refLin is the fundamental amplitude every ratio divides by (the
 * manually entered level when the operator sets one). "In-band" is the distortion
 * measurement band the SNR bounds define; the noise integral is the summed noise
 * bins over that band, divided by the window's NENBW.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

import { FftAnalyzer } from '../js/fft/fft-analyzer.js';
import { FftResult } from '../js/fft/fft-result.js';

const SR = 48000;
const FFT = 4096;
const BIN_W = SR / FFT;

/** A single off-bin tone plus broadband noise - deterministic, no harmonics. */
function toneWithNoise(len, toneHz, amp, noiseAmp) {
  const x = new Float64Array(len);
  let s = 0x2545f491;
  const rnd = () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 0x100000000 - 0.5; };
  for (let n = 0; n < len; n++) x[n] = amp * Math.sin(2 * Math.PI * toneHz * n / SR) + noiseAmp * rnd();
  return x;
}

const SIGNAL = toneWithNoise(FFT * 3, 1000 + 0.37 * BIN_W, 0.5, 2e-3);

function analyze(fundRefDbFs = NaN) {
  return new FftAnalyzer().analyze(SIGNAL, SR, FFT, 8, 'BH7', 'PCT_75',
    0, 0, true, fundRefDbFs, NaN, new FftResult());
}

test('SINAD is UNWEIGHTED - with no in-band distortion it collapses onto SNR', () => {
  const r = analyze();
  // Both divide refLin^2 by an in-band noise integral; SINAD adds the harmonic
  // power, which on a pure tone in noise is only the floor under the harmonic bins.
  assert.ok(Math.abs(r.sinadDb - r.snrDb) < 0.1,
    `SINAD ${r.sinadDb.toFixed(3)} must sit on SNR ${r.snrDb.toFixed(3)} dB (unweighted)`);
  // ...and the A-weighted figure is genuinely different on this noise, so the
  // assertion above is not vacuous: an A-weighted SINAD would have moved by this.
  const awGap = Math.abs(-r.awThdNDb - r.snrDb);
  assert.ok(awGap > 1.0,
    `the A-curve moves the same noise by ${awGap.toFixed(2)} dB, so the two are distinguishable`);
});

test('THD+N is the reciprocal of the unweighted SINAD, exactly', () => {
  const r = analyze();
  assert.equal(r.thdNDb, -r.sinadDb);
});

test('N+D is the ONLY weighted figure, and it is negative dB against the signal', () => {
  const r = analyze();
  assert.ok(Number.isFinite(r.awThdNDb));
  assert.ok(r.awThdNDb < 0, `N+D reads ${r.awThdNDb.toFixed(2)} dB, a level below the signal`);
  // It is NOT the unweighted THD+N: the curve has to be in there.
  assert.ok(Math.abs(r.awThdNDb - r.thdNDb) > 1.0,
    `N+D ${r.awThdNDb.toFixed(2)} differs from THD+N ${r.thdNDb.toFixed(2)} dB by the A-curve`);
});

test('ENOB carries NO fundamental term - a manual fundamental cannot skew it', () => {
  // The denominator is already full-scale-relative power, so the signal level
  // cancels exactly. Entering a fundamental 20 dB away moves every ratio that
  // divides by refLin, and must leave the ENOB basis bit-identical.
  const auto = analyze();
  const manual = analyze(auto.fundamentalDbFs - 20.0);
  assert.ok(Math.abs(manual.sinadDb - auto.sinadDb) > 15.0,
    'the manual fundamental really did move the ratios');
  assert.equal(manual.enobSinadDb, auto.enobSinadDb,
    'the ENOB basis is bit-identical under a manual fundamental');
  assert.equal(manual.snrDb - auto.snrDb, manual.sinadDb - auto.sinadDb,
    'SNR and SINAD moved together, by the same refLin change');
});

test('the ENOB basis is the unweighted denominator referred to full scale', () => {
  const r = analyze();
  // enobSinadDb = -10*log10(sinadDenom) and sinadDb = 10*log10(refLin^2/sinadDenom),
  // so the two differ by exactly the signal level in dB - the term that cancels.
  const signalDb = 20.0 * Math.log10(Math.pow(10.0, r.fundamentalDbFs / 20.0));
  assert.ok(Math.abs((r.enobSinadDb - r.sinadDb) + signalDb) < 1e-9,
    `enobSinadDb ${r.enobSinadDb.toFixed(4)} = sinadDb ${r.sinadDb.toFixed(4)} `
    + `minus the signal level ${signalDb.toFixed(4)} dB`);
  const bits = (r.enobSinadDb - 1.76) / 6.02;
  assert.ok(bits > 0 && bits < 32, `ENOB reads ${bits.toFixed(1)} bits`);
});

test('the second stats pass stamps the same two fields', () => {
  // The web has TWO stats passes: analyze's finalize, and recomputeStats, which the
  // display path runs on the ACCUMULATED spectrum and on a de-embedded copy.
  const a = new FftAnalyzer();
  const r = analyze();
  const before = { enob: r.enobSinadDb, aw: r.awThdNDb, sinad: r.sinadDb };
  r.enobSinadDb = 0; r.awThdNDb = 0; r.sinadDb = 0;
  a.recomputeStats(r);
  assert.ok(Math.abs(r.sinadDb - before.sinad) < 0.5, 'recomputeStats re-derives SINAD');
  assert.ok(Math.abs(r.enobSinadDb - before.enob) < 0.5, 'and the ENOB basis');
  assert.ok(Math.abs(r.awThdNDb - before.aw) < 0.5, 'and the A-weighted N+D');
  assert.equal(r.thdNDb, -r.sinadDb, 'THD+N stays the reciprocal in this pass too');
});

test('the spectrum-only fast path neutralises both new scalars', () => {
  // A recycled result slot must not leak a previous tick's numbers if anything
  // reads them before the display rebuild.
  const a = new FftAnalyzer();
  a.setSpectrumOnly(true);
  const r = a.analyze(SIGNAL, SR, FFT, 8, 'BH7', 'PCT_75', 0, 0, true, NaN, NaN, new FftResult());
  assert.equal(r.awThdNDb, 300.0);
  assert.equal(r.enobSinadDb, 300.0);
  assert.equal(r.sinadDb, 300.0);
});

test('deepCopy carries both new fields', () => {
  const r = analyze();
  const c = r.deepCopy();
  assert.equal(c.awThdNDb, r.awThdNDb);
  assert.equal(c.enobSinadDb, r.enobSinadDb);
});

// ── The readouts that consume them ──────────────────────────────────────────

const src = (rel) => readFileSync(new URL(rel, import.meta.url), 'utf8');

test('the distortion table wires N+D to the weighted field and ENOB to the unweighted basis', () => {
  const view = src('../js/fft/fft-view.js');
  assert.ok(/'N\+D:', `\$\{this\._fmtDb\(r\.awThdNDb\)\} A`/.test(view),
    'the N+D row reads awThdNDb and keeps its A marker');
  assert.ok(view.includes('(r.enobSinadDb - 1.76) / 6.02'),
    'the ENOB row reads the full-scale-referred basis');
  assert.ok(!view.includes('(r.sinadDb - 1.76)'),
    'no readout derives ENOB from the ratio SINAD any more');
});

test('the worker carries both fields across the boundary', () => {
  // Without this the view would render NaN bits and an empty N+D cell. The snapshot that crosses
  // the boundary is assembled by the display-preparation stage, which is where both senders of a
  // display result - the display worker and the analysis worker's own two cases - take it from.
  assert.ok(/awThdNDb: r\.awThdNDb, enobSinadDb: r\.enobSinadDb/.test(src('../js/fft/fft-finalize.js')));
});

test('the predistortion wizard mirrors follow the shared unweighted fields', () => {
  const wiz = src('../js/shell/predistortion-wizard.js');
  assert.ok(wiz.includes('r.sinadDb.toFixed(2)'), 'the SINAD row reads the shared field');
  assert.ok(wiz.includes('r.thdNDb.toFixed(2)'), 'the THD+N row reads its reciprocal');
  assert.ok(wiz.includes('# thd_plus_n_db=${this._fmt(-r.sinadDb)}'),
    'the .dpd header follows the same field');
});

test('the withdrawn full-band ENOB basis is nowhere in the analyzer', () => {
  // An intermediate Java commit added it and the next one removed it again; it must
  // never have existed on the web.
  const ana = src('../js/fft/fft-analyzer.js');
  for (const gone of ['fullNoisePower', 'fullCounted', 'enobHarmPow']) {
    assert.ok(!ana.includes(gone), `${gone} must not exist`);
  }
});
