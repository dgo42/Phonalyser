/*
 * Phonalyser web - the shared table-plus-Taylor sine kernel.
 *
 * ONE kernel serves the two precision-critical sinusoids in the app: the DDS
 * generator's waveforms and the oscilloscope residual's fitted reference. The
 * kernel splits a phase given in TURNS (1.0 = one full cycle) into a coarse
 * angle read from a full-wave 4096-entry table and a fractional remainder
 * corrected by a short Taylor series.
 *
 * The residual's reference takes its phase from ONE multiply per sample
 * (turns-per-sample times the absolute sample index) instead of a sin/cos
 * rotation recurrence. What that fixes is the recurrence's SEED: it had to start
 * from sin/cos of omega times the absolute sample offset, and reducing an
 * argument that large loses precision in proportion to it. Rotating forward over
 * the slice contributes almost nothing by comparison - the last case below
 * measures both terms separately so the mechanism is a checked fact rather than
 * a claim.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

import { sinTurns, cosTurns, taylorCos, taylorSin } from '../js/dsp/sine-kernel.js';
import { SineFit } from '../js/dsp/sine-fit.js';

const src = (rel) => readFileSync(new URL(rel, import.meta.url), 'utf8');

// ── One definition, two call sites ──────────────────────────────────────────

test('exactly ONE module builds the sine table', () => {
  // Walk web/js and count the files that declare the table. Java moved it out of
  // the generator into a shared kernel; the web must not have re-grown a copy.
  const root = new URL('../js/', import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1');
  const hits = [];
  const walk = (dir) => {
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const p = join(dir, e.name);
      if (e.isDirectory()) walk(p);
      else if (e.name.endsWith('.js') && readFileSync(p, 'utf8').includes('SINE_TABLE')) hits.push(p);
    }
  };
  walk(root);
  assert.equal(hits.length, 1, `the table is declared in: ${hits.join(', ')}`);
  assert.ok(hits[0].endsWith(join('dsp', 'sine-kernel.js')), `it lives in the shared kernel, not ${hits[0]}`);
});

test('both call sites import the shared kernel rather than carrying the math', () => {
  const dds = src('../js/generator/dds-kernel.js');
  const fit = src('../js/dsp/sine-fit.js');
  assert.ok(dds.includes("from '../dsp/sine-kernel.js'"), 'the generator imports it');
  assert.ok(fit.includes("from './sine-kernel.js'"), 'the scope residual imports it');
  for (const [name, text] of [['dds-kernel', dds], ['sine-fit', fit]]) {
    assert.ok(!/function\s+taylor(Cos|Sin)/.test(text), `${name} has no Taylor copy`);
    assert.ok(!text.includes('SINE_TABLE'), `${name} has no table copy`);
  }
});

test('the audio worklet needs no copy - it reaches the kernel through the generator', () => {
  // AudioWorklet modules are loaded as ES modules and bundled by esbuild as their
  // own entry points, so the import chain resolves in both the dev tree and the
  // production bundle; nothing has to be duplicated into the worklet realm.
  const wk = src('../js/audio/worklets/dds-processor.js');
  assert.ok(wk.includes("from '../../generator/dds-kernel.js'"), 'the worklet imports the generator kernel');
  assert.ok(!wk.includes('SINE_TABLE'), 'and carries no table of its own');
});

// ── The order switch ────────────────────────────────────────────────────────

test('the ACTIVE Taylor order is the 5th, with all three kept as the switch', () => {
  const k = src('../js/dsp/sine-kernel.js');
  // Active: the 5th-order terms are executable, the 7th and 9th are commented.
  assert.ok(/^\s*return 1\.0 \+ dx2 \* \(-0\.5 \+ dx2 \* \(1\.0 \/ 24\.0\)\);/m.test(k),
    'taylorCos runs the 5th order');
  assert.ok(/^\s*return dx \* \(1\.0 \+ dx2 \* \(-1\.0 \/ 6\.0 \+ dx2 \* \(1\.0 \/ 120\.0\)\)\);/m.test(k),
    'taylorSin runs the 5th order');
  for (const higher of ['// return 1.0 + dx2 * (-0.5 + dx2 * (1.0 / 24.0 + dx2 * (-1.0 / 720.0 + dx2 * (1.0 / 40320.0))));',
    '// return dx * (1.0 + dx2 * (-1.0 / 6.0 + dx2 * (1.0 / 120.0 + dx2 * (-1.0 / 5040.0 + dx2 * (1.0 / 362880.0)))));']) {
    assert.ok(k.includes(higher), 'the 9th order is kept as an alternative');
  }
  assert.ok(k.includes('-1.0 / 720.0)));') && k.includes('-1.0 / 5040.0))));'),
    'the 7th order is kept as an alternative');
  // The helpers are the series they claim to be: at the largest fractional angle
  // the table split can produce, both match the true functions to double precision.
  const stepRad = (2 * Math.PI) / 4096;
  assert.ok(Math.abs(taylorCos(stepRad * stepRad) - Math.cos(stepRad)) < 1e-17);
  assert.ok(Math.abs(taylorSin(stepRad, stepRad * stepRad) - Math.sin(stepRad)) < 1e-17);
});

// ── Accuracy ────────────────────────────────────────────────────────────────

test('the kernel tracks Math.sin / Math.cos across arbitrary real turns', () => {
  let worstS = 0, worstC = 0;
  for (let i = 0; i < 200000; i++) {
    const t = i * 0.000173 - 17.0;                     // negatives included
    const folded = 2 * Math.PI * (t - Math.floor(t));
    worstS = Math.max(worstS, Math.abs(sinTurns(t) - Math.sin(folded)));
    worstC = Math.max(worstC, Math.abs(cosTurns(t) - Math.cos(folded)));
  }
  assert.ok(worstS < 2e-15, `worst sine error ${worstS.toExponential(3)}`);
  assert.ok(worstC < 2e-15, `worst cosine error ${worstC.toExponential(3)}`);
});

test('a tiny negative turn whose fold rounds to exactly 1.0 stays finite', () => {
  // turns - floor(turns) rounds to 1.0 here, so the scaled cell is the table
  // length; the mask folds it to cell 0, where the remainder is 0 anyway.
  const tiny = -1e-20;
  assert.equal(tiny - Math.floor(tiny), 1.0, 'the fixture really does hit the degenerate fold');
  assert.equal(sinTurns(tiny), 0.0);
  assert.equal(cosTurns(tiny), 1.0);
});

// ── The residual's phase source ─────────────────────────────────────────────

const SR = 48000;

test('the residual takes its phase from ONE multiply per sample, bit-for-bit', () => {
  // Reproducing the model with an independent per-sample kernel evaluation must
  // match exactly. A rotation recurrence cannot: its value at sample i depends on
  // every step before it.
  const freq = 997.3141592;
  const omega = 2 * Math.PI * freq / SR;
  const fit = new SineFit(0.7, -0.3, 0.05, freq, omega);
  const COUNT = 5000, K0 = 123456789;
  const srcBuf = new Float64Array(COUNT);
  for (let i = 0; i < COUNT; i++) srcBuf[i] = Math.random() - 0.5;
  const dst = new Float64Array(COUNT);
  fit.subtractSineInto(srcBuf, 0, COUNT, K0, dst, 0);
  const tps = omega / (2 * Math.PI);
  for (let i = 0; i < COUNT; i++) {
    const turns = tps * (K0 + i);
    const want = srcBuf[i] - (0.7 * sinTurns(turns) + -0.3 * cosTurns(turns));
    assert.equal(dst[i], want, `sample ${i} is the one-multiply evaluation`);
  }
});

test('subtractFullInto removes the DC term and adds the replacement back', () => {
  const freq = 1000, omega = 2 * Math.PI * freq / SR;
  const fit = new SineFit(0.4, 0.2, 0.11, freq, omega);
  const COUNT = 512, K0 = 7777;
  const srcBuf = new Float64Array(COUNT).fill(0.25);
  const dst = new Float64Array(COUNT);
  fit.subtractFullInto(srcBuf, 0, COUNT, K0, 0.03, dst, 0);
  const tps = omega / (2 * Math.PI);
  for (let i = 0; i < COUNT; i++) {
    const turns = tps * (K0 + i);
    const want = 0.25 - (0.4 * sinTurns(turns) + 0.2 * cosTurns(turns) + 0.11) + 0.03;
    assert.equal(dst[i], want);
  }
});

test('the residual does not degrade with the absolute sample position', () => {
  // The defect the shared kernel removes. At 750 Hz / 48 kHz the turns-per-sample
  // is exactly 1/64, so the phase is exact at any sample index and the reference
  // below is trustworthy. The rotation recurrence has to seed itself from
  // sin/cos of omega * kOffset, and reducing an argument that large is where its
  // error comes from - the case after this one separates the seed term from the
  // rotation term and shows which dominates.
  const freq = 750, omega = 2 * Math.PI * freq / SR;
  assert.equal(freq / SR, 1 / 64, 'the fixture phase is exactly representable');
  const A = 0.7, B = -0.3;
  const fit = new SineFit(A, B, 0, freq, omega);
  const ref = (k) => {
    const t = k / 64 - Math.floor(k / 64);
    return A * Math.sin(2 * Math.PI * t) + B * Math.cos(2 * Math.PI * t);
  };
  const COUNT = 20000;
  const worstAt = (K0) => {
    const s = new Float64Array(COUNT), d = new Float64Array(COUNT);
    for (let i = 0; i < COUNT; i++) s[i] = ref(K0 + i);
    fit.subtractSineInto(s, 0, COUNT, K0, d, 0);
    let w = 0;
    for (let i = 0; i < COUNT; i++) w = Math.max(w, Math.abs(d[i]));
    // The removed algorithm, reproduced over the same span for comparison.
    let cs = Math.sin(omega * K0), cc = Math.cos(omega * K0);
    const co = Math.cos(omega), so = Math.sin(omega);
    let wr = 0;
    for (let i = 0; i < COUNT; i++) {
      wr = Math.max(wr, Math.abs(s[i] - (A * cs + B * cc)));
      const ns = cs * co + cc * so; cc = cc * co - cs * so; cs = ns;
    }
    return { kernel: w, recurrence: wr };
  };
  const near = worstAt(0);
  const far = worstAt(1e9);
  assert.equal(near.kernel, 0, 'the kernel nulls an exactly-phased reference');
  assert.equal(far.kernel, 0, 'and still does a billion samples in');
  assert.ok(far.recurrence > 1e-10,
    `the recurrence degrades far into a capture (${far.recurrence.toExponential(3)})`);
  assert.ok(far.recurrence > near.recurrence * 100,
    `and it degrades WITH the position (${near.recurrence.toExponential(3)} -> `
    + `${far.recurrence.toExponential(3)})`);
});

test('the recurrence error is the SEED, not the rotation across the slice', () => {
  // Separate the two terms. Term A: the seed alone - sin/cos of omega * kOffset
  // compared with the exactly-phased reference, no rotation at all. Term B: the
  // rotation alone - seeded at kOffset 0, where the angle is exact, then stepped
  // forward as many times as the far case sits samples in. If accumulation across
  // the slice were the mechanism, B would be the large one. It is not.
  const freq = 750, omega = 2 * Math.PI * freq / SR;
  const K0 = 1e9, STEPS = 1000000;
  const refSin = (k) => Math.sin(2 * Math.PI * (k / 64 - Math.floor(k / 64)));

  const seedOnly = Math.abs(Math.sin(omega * K0) - refSin(K0));

  const co = Math.cos(omega), so = Math.sin(omega);
  let cs = 0.0, cc = 1.0;                       // the exact seed at k = 0
  let rotationOnly = 0;
  for (let i = 1; i <= STEPS; i++) {
    const ns = cs * co + cc * so; cc = cc * co - cs * so; cs = ns;
    rotationOnly = Math.max(rotationOnly, Math.abs(cs - refSin(i)));
  }
  assert.ok(seedOnly > 1e-10,
    `the seed alone is already large a billion samples in (${seedOnly.toExponential(3)})`);
  assert.ok(rotationOnly < 1e-13,
    `a million rotation steps from an exact seed stay tiny (${rotationOnly.toExponential(3)})`);
  assert.ok(seedOnly > rotationOnly * 1000,
    `so the SEED dominates (${seedOnly.toExponential(3)} vs ${rotationOnly.toExponential(3)})`);
});

test('an off-lattice frequency nulls to the kernel accuracy', () => {
  // Exercises the table-plus-Taylor path proper: no phase lands on a table cell.
  const freq = 1237.77713, omega = 2 * Math.PI * freq / SR;
  const A = 0.55, B = 0.21;
  const fit = new SineFit(A, B, 0, freq, omega);
  const COUNT = 20000, K0 = 4000000;
  // The turns-per-sample is DERIVED FROM OMEGA, as the method derives it. Taking
  // freq/SR instead differs by an ulp, which four million samples in amplifies to
  // a 5e-11 phase difference - a property of the reference, not of the kernel.
  const tps = omega / (2 * Math.PI);
  const s = new Float64Array(COUNT), d = new Float64Array(COUNT);
  for (let i = 0; i < COUNT; i++) {
    const t = tps * (K0 + i);
    const f = 2 * Math.PI * (t - Math.floor(t));
    s[i] = A * Math.sin(f) + B * Math.cos(f);
  }
  fit.subtractSineInto(s, 0, COUNT, K0, d, 0);
  let w = 0;
  for (let i = 0; i < COUNT; i++) w = Math.max(w, Math.abs(d[i]));
  assert.ok(w < 5e-15, `worst residual ${w.toExponential(3)}`);
});

test('the rotation recurrence is gone from the residual, and kept in the FIT', () => {
  const fitSrc = src('../js/dsp/sine-fit.js');
  const bodyOf = (name) => {
    const i = fitSrc.indexOf(name + '(src, srcFrom, count');
    assert.ok(i > 0, `${name} exists`);
    const open = fitSrc.indexOf('{', i);
    let depth = 0;
    for (let j = open; j < fitSrc.length; j++) {
      if (fitSrc[j] === '{') depth++;
      else if (fitSrc[j] === '}' && --depth === 0) return fitSrc.slice(open, j + 1);
    }
    return '';
  };
  for (const name of ['subtractSineInto', 'subtractFullInto']) {
    const body = bodyOf(name);
    for (const gone of ['cosOmega', 'sinOmega', 'curSin', 'curCos']) {
      assert.ok(!body.includes(gone), `${name} no longer uses ${gone}`);
    }
    assert.ok(body.includes('sinTurns') && body.includes('cosTurns'),
      `${name} evaluates through the shared kernel`);
  }
  // The FIT keeps its recurrence: it runs over one bounded window, and Java
  // leaves it there too - only the residual synthesis changed.
  assert.ok(fitSrc.includes('const cosOmega = Math.cos(omega)'),
    'the normal-equation accumulation still uses its recurrence');
});
