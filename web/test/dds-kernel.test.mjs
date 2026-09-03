/*
 * Phonalyser web - DdsKernel phase-accuracy + allocation-free contract test.
 * Run with `node web/test/dds-kernel.test.mjs`.
 *
 * Guards the BigInt->double-turns phase conversion (web/js/generator/dds-kernel.js):
 *
 *   1. Accuracy - for every deterministic form (SINE, DUAL_TONE, TRIANGLE,
 *      RECTANGLE, SINE_COMP) and several frequencies (incl. 1000.122 Hz and a
 *      high one), the kernel output is reproduced by an INDEPENDENT turns-based
 *      reference (same table + Taylor math, reimplemented here) to ~1e-12, and
 *      the SINE / DUAL_TONE output matches ground-truth Math.sin to ~1e-9 -
 *      confirming the double-turns phase loses no audible precision.
 *   2. Determinism - PINK_NOISE with a seeded RNG reproduces bit-for-bit across
 *      two runs (the Voss-McCartney counter is now a plain Number).
 *   3. Allocation-free hot path - a structural scan of nextSample() and every
 *      per-sample helper it reaches asserts no BigInt, no `n`-suffixed bigint
 *      literal, and no `new` allocation in the per-sample path.
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

import {
  DdsKernel,
  makeCompensation,
} from '../js/generator/dds-kernel.js';
import { GenSignalForm } from '../js/enums/gen-signal-form.js';

const HERE = dirname(fileURLToPath(import.meta.url));
const KERNEL_SRC = join(HERE, '..', 'js', 'generator', 'dds-kernel.js');
// The table + Taylor evaluation lives in the SHARED sine kernel (the scope
// residual synthesizes its reference through the same math), so the per-sample
// hot path now spans two modules and the scan below follows it into both.
const SINE_KERNEL_SRC = join(HERE, '..', 'js', 'dsp', 'sine-kernel.js');

let passed = 0, failed = 0;
function ok(cond, msg) { if (cond) { passed++; } else { failed++; console.error('FAIL:', msg); } }
function near(a, b, eps, msg) { ok(Math.abs(a - b) <= eps, `${msg} (got ${a}, want ${b}, |d|=${Math.abs(a - b)})`); }

const SR = 48000;

// --- independent reference: phase-in-turns sine via table + Taylor ----------
// Mirrors ddsSineOf/ddsCosOf in the kernel, derived independently so a wiring
// bug in the kernel shows up as a mismatch rather than a shared error.
const TBL = 4096;
const SIN = new Float64Array(TBL);
const COS = new Float64Array(TBL);
for (let i = 0; i < TBL; i++) {
  const a = (2 * Math.PI * i) / TBL;
  SIN[i] = Math.sin(a);
  COS[i] = Math.cos(a);
}
const RAD_PER_CELL = (2 * Math.PI) / TBL;
function refSin(turns) {
  const t = turns - Math.floor(turns);
  const scaled = t * TBL;
  const idx = scaled | 0;
  const dx = (scaled - idx) * RAD_PER_CELL;
  const dx2 = dx * dx;
  const tc = 1 + dx2 * (-0.5 + dx2 * (1 / 24));
  const ts = dx * (1 + dx2 * (-1 / 6 + dx2 * (1 / 120)));
  return SIN[idx] * tc + COS[idx] * ts;
}
function refCos(turns) {
  const t = turns - Math.floor(turns);
  const scaled = t * TBL;
  const idx = scaled | 0;
  const dx = (scaled - idx) * RAD_PER_CELL;
  const dx2 = dx * dx;
  const tc = 1 + dx2 * (-0.5 + dx2 * (1 / 24));
  const ts = dx * (1 + dx2 * (-1 / 6 + dx2 * (1 / 120)));
  return COS[idx] * tc - SIN[idx] * ts;
}

const FREQS = [100, 1000, 1000.122, 12345.678, 19999.5];
const N = 4000;

// --- SINE: kernel vs turns-reference (~1e-12) and vs Math.sin (~1e-9) -------
// The reference advances its phase with the SAME incremental recurrence the
// kernel uses (acc += inc; wrap), so the comparison isolates correct formula
// application, not divergent float accumulation over many samples.
function advance(ph, inc) {
  ph += inc;
  if (ph >= 1) ph -= Math.floor(ph);
  return ph;
}
for (const f of FREQS) {
  const k = new DdsKernel({ form: GenSignalForm.SINE, frequency: f, sampleRate: SR, amplitudeVRms: 1, dacFsVoltageAmpl: Math.sqrt(2) });
  const inc = f / SR; // turns per sample
  let ph = 0, maxRef = 0, maxTruth = 0;
  for (let i = 0; i < N; i++) {
    const want = refSin(ph);             // amplitude 1.0 for SINE at 1 Vrms / √2 fs
    const got = k.nextSample();
    maxRef = Math.max(maxRef, Math.abs(got - want));
    maxTruth = Math.max(maxTruth, Math.abs(got - Math.sin(2 * Math.PI * ph)));
    ph = advance(ph, inc);
  }
  near(maxRef, 0, 1e-12, `SINE ${f}Hz vs turns-ref`);
  near(maxTruth, 0, 1e-9, `SINE ${f}Hz vs Math.sin`);
}

// --- DUAL_TONE: f1 and f2, weighted sum --------------------------------------
{
  const f1 = 1000.122, f2 = 1300.7;
  const k = new DdsKernel({ form: GenSignalForm.DUAL_TONE, frequency: f1, sampleRate: SR, amplitudeVRms: 1, dacFsVoltageAmpl: Math.sqrt(2) });
  k.setDualToneFrequency2(f2);
  k.setDualToneAmplitudes(60, 40); // w1=0.6, w2=0.4
  const w1 = 0.6, w2 = 0.4;
  const rawRms = Math.sqrt((w1 * w1 + w2 * w2) / 2);
  const amp = 1 / (Math.sqrt(2) * rawRms);
  const inc1 = f1 / SR, inc2 = f2 / SR;
  let t1 = 0, t2 = 0, maxRef = 0, maxTruth = 0;
  for (let i = 0; i < N; i++) {
    const want = (refSin(t1) * w1 + refSin(t2) * w2) * amp;
    const truth = (Math.sin(2 * Math.PI * t1) * w1 + Math.sin(2 * Math.PI * t2) * w2) * amp;
    const got = k.nextSample();
    maxRef = Math.max(maxRef, Math.abs(got - want));
    maxTruth = Math.max(maxTruth, Math.abs(got - truth));
    t1 = advance(t1, inc1); t2 = advance(t2, inc2);
  }
  near(maxRef, 0, 1e-12, 'DUAL_TONE vs turns-ref');
  near(maxTruth, 0, 1e-9, 'DUAL_TONE vs Math.sin');
}

// --- TRIANGLE: fractional-turn ramp ------------------------------------------
{
  const f = 1000.122, duty = 0.5;
  const k = new DdsKernel({ form: GenSignalForm.TRIANGLE, frequency: f, sampleRate: SR, amplitudeVRms: 1, dacFsVoltageAmpl: Math.sqrt(2) });
  const amp = 1 / (Math.sqrt(2) * (1 / Math.sqrt(3)));
  const inc = f / SR;
  let frac = 0, maxRef = 0;
  for (let i = 0; i < N; i++) {
    const tri = frac < duty ? (frac / duty) * 2 - 1 : 1 - ((frac - duty) / (1 - duty)) * 2;
    const got = k.nextSample();
    maxRef = Math.max(maxRef, Math.abs(got - tri * amp));
    frac = advance(frac, inc);
  }
  near(maxRef, 0, 1e-12, 'TRIANGLE vs turns-ref');
}

// --- RECTANGLE: fractional-turn threshold ------------------------------------
{
  const f = 1000.122, duty = 0.5;
  const k = new DdsKernel({ form: GenSignalForm.RECTANGLE, frequency: f, sampleRate: SR, amplitudeVRms: 1, dacFsVoltageAmpl: Math.sqrt(2) });
  const amp = 1 / (Math.sqrt(2) * 1.0); // rawRms(RECTANGLE) = 1
  const inc = f / SR;
  let frac = 0, maxRef = 0;
  for (let i = 0; i < N; i++) {
    const rect = (frac < duty ? 1 : -1) * amp;
    const got = k.nextSample();
    maxRef = Math.max(maxRef, Math.abs(got - rect));
    frac = advance(frac, inc);
  }
  near(maxRef, 0, 1e-12, 'RECTANGLE vs turns-ref');
}

// --- SINE_COMP: fundamental minus harmonic cos terms with turn offsets -------
{
  const f = 1000.122;
  const ampRatios = [0.01, 0.005, 0.002];     // pct/100
  const hNums = [2, 3, 5];
  const phiInits = [0.3, -1.1, 2.4];          // radians
  const comp = makeCompensation(ampRatios, hNums, phiInits);
  const k = new DdsKernel({ form: GenSignalForm.SINE, frequency: f, sampleRate: SR, amplitudeVRms: 1, dacFsVoltageAmpl: Math.sqrt(2) });
  k.applyCompensation(comp);
  ok(k.form === GenSignalForm.SINE_COMP, 'applyCompensation switches to SINE_COMP');

  const offTurns = phiInits.map((p) => { const t = p / (2 * Math.PI); return t - Math.floor(t); });
  const inc = f / SR;
  let ph = 0, maxRef = 0;
  for (let i = 0; i < N; i++) {
    let want = refSin(ph);
    for (let h = 0; h < hNums.length; h++) {
      const hp = ph * hNums[h] + offTurns[h];
      want -= ampRatios[h] * refCos(hp - Math.floor(hp));
    }
    const got = k.nextSample();
    maxRef = Math.max(maxRef, Math.abs(got - want));
    ph = advance(ph, inc);
  }
  near(maxRef, 0, 1e-12, 'SINE_COMP vs turns-ref');
}

// --- PINK_NOISE: seeded determinism across two kernels -----------------------
function seededRng(seed) {
  // Mulberry32 - deterministic uniform [0,1).
  let a = seed >>> 0;
  return function () {
    a |= 0; a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
{
  const mk = () => new DdsKernel({ form: GenSignalForm.PINK_NOISE, frequency: 1000, sampleRate: SR, rng: seededRng(12345) });
  const a = mk(), b = mk();
  let mismatch = 0, finite = true;
  for (let i = 0; i < N; i++) {
    const x = a.nextSample(), y = b.nextSample();
    if (x !== y) mismatch++;
    if (!Number.isFinite(x)) finite = false;
  }
  ok(mismatch === 0, `PINK_NOISE deterministic across runs (${mismatch} mismatches)`);
  ok(finite, 'PINK_NOISE output finite');
}

// --- allocation-free hot path: structural source scan ------------------------
{
  const src = readFileSync(KERNEL_SRC, 'utf8');
  const sineSrc = readFileSync(SINE_KERNEL_SRC, 'utf8');

  // Collect nextSample() + every method/function it reaches per sample.
  const hotMethods = [
    'nextSample',
    '_ddsTriangle', '_ddsRectangle', '_pinkNoise',
    '_ddsSineCompensated', '_ddsDualToneCompensated',
    '_linearSweepNext', '_logSweepNext', '_sweepEnvelope',
  ];
  // Named with the module each lives in: the table + Taylor pair moved to the
  // shared sine kernel, and sinTurns / cosTurns are what the generator now calls.
  const hotFunctions = [
    ['ddsSineOf', src], ['ddsCosOf', src], ['wrapTurn', src], ['trailingZerosLow', src],
    ['taylorCos', sineSrc], ['taylorSin', sineSrc],
    ['sinTurns', sineSrc], ['cosTurns', sineSrc],
  ];

  function bodyOf(name, kind, from) {
    // Method: `name(...) {` ; function: `function name(...) {`
    const re = kind === 'fn'
      ? new RegExp('function\\s+' + name + '\\s*\\([^)]*\\)\\s*\\{')
      : new RegExp('(?:^|\\n)\\s*' + name + '\\s*\\([^)]*\\)\\s*\\{');
    const src = from;
    const m = re.exec(src);
    if (!m) return null;
    let i = src.indexOf('{', m.index);
    let depth = 0;
    for (let j = i; j < src.length; j++) {
      const ch = src[j];
      if (ch === '{') depth++;
      else if (ch === '}') { depth--; if (depth === 0) return src.slice(i, j + 1); }
    }
    return null;
  }

  let scanned = 0;
  for (const name of hotMethods) {
    const body = bodyOf(name, 'method', src);
    ok(body != null, `found hot method ${name}`);
    if (!body) continue;
    scanned++;
    ok(!/\bBigInt\b/.test(body), `${name}: no BigInt`);
    ok(!/\d+n\b/.test(body), `${name}: no bigint literal`);
    ok(!/\bnew\s+/.test(body), `${name}: no allocation (new)`);
  }
  for (const [name, from] of hotFunctions) {
    const body = bodyOf(name, 'fn', from);
    ok(body != null, `found hot function ${name}`);
    if (!body) continue;
    scanned++;
    ok(!/\bBigInt\b/.test(body), `${name}: no BigInt`);
    ok(!/\d+n\b/.test(body), `${name}: no bigint literal`);
    ok(!/\bnew\s+/.test(body), `${name}: no allocation (new)`);
  }
  ok(scanned === hotMethods.length + hotFunctions.length, 'all hot-path symbols scanned');

  // Sanity: BigInt must be gone from the executable code (comments may still
  // explain WHY it was removed). Strip block + line comments, then scan. BOTH
  // modules the per-sample path runs through are swept, so a stray BigInt
  // outside the four scanned kernel functions cannot slip in either.
  for (const [name, text] of [['dds-kernel.js', src], ['sine-kernel.js', sineSrc]]) {
    const code = text
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/\/\/[^\n]*/g, '');
    ok(!/\bBigInt\b/.test(code), `no BigInt in ${name} code`);
    ok(!/\b\d+n\b/.test(code), `no bigint literals in ${name} code`);
  }
}

console.log(`\nDdsKernel: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
