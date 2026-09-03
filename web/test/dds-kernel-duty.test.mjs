/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Rectangle / triangle duty is whole samples of the period-aligned grid (the Java
 * SignalGeneratorTest duty cases): the pulse is round(duty · n) samples of the n-sample period -
 * the count the generator pane's duty bracket shows - never fewer than one and never the whole
 * period. A duty below one sample (0.25 % of 384 samples is 0.96 samples) once left the pulse to
 * the phase accumulator's rounding and vanished; it is the one-sample pulse the bracket promises.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

import { DdsKernel, rawRms } from '../js/generator/dds-kernel.js';
import { GenSignalForm } from '../js/enums/gen-signal-form.js';

/** 384 kHz at 1 kHz is 384 samples per period - the grid the duty bracket divides by. */
const RATE = 384000;
const FREQ = 1000;
const PERIOD = 384;
const PERIODS = 10;
const DAC_FS_AMPL = Math.SQRT2;

function render(kernel, samples) {
  const block = new Float64Array(samples);
  kernel.fill(block, samples);
  return block;
}

/** High samples per period over PERIODS periods; every period must carry the same count, or the
 *  pulse is at the mercy of rounding. */
function highSamplesPerPeriod(kernel) {
  const block = render(kernel, PERIOD * PERIODS);
  let first = -1;
  for (let p = 0; p < PERIODS; p++) {
    let high = 0;
    for (let i = 0; i < PERIOD; i++) if (block[p * PERIOD + i] > 0) high++;
    if (first < 0) first = high;
    assert.equal(high, first, `period ${p} carries a different pulse width`);
  }
  return first;
}

test('the rectangle duty is whole samples of the period - the count the bracket shows', () => {
  const kernel = new DdsKernel({ form: GenSignalForm.RECTANGLE, frequency: FREQ, sampleRate: RATE,
    amplitudeVRms: 1.0, dacFsVoltageAmpl: DAC_FS_AMPL });

  kernel.setRectangleDuty(0.0025);
  assert.equal(highSamplesPerPeriod(kernel), 1,
    'a sub-sample duty still emits the ONE high sample per period the bracket shows');
  kernel.setRectangleDuty(0.5);
  assert.equal(highSamplesPerPeriod(kernel), PERIOD / 2, '50 % of 384 samples is 192 high samples per period');
  kernel.setRectangleDuty(0.999);
  assert.equal(highSamplesPerPeriod(kernel), PERIOD - 1,
    'at most n - 1 high samples - the train never collapses to a DC level');
});

test('the triangle corner lands ON a sample of the period', () => {
  const kernel = new DdsKernel({ form: GenSignalForm.TRIANGLE, frequency: FREQ, sampleRate: RATE,
    amplitudeVRms: 1.0, dacFsVoltageAmpl: DAC_FS_AMPL });
  kernel.setTriangleDuty(0.0025);   // 0.96 samples of rise -> one sample
  const peak = 1.0 / (DAC_FS_AMPL * rawRms(GenSignalForm.TRIANGLE));

  const block = render(kernel, PERIOD * PERIODS);
  for (let p = 0; p < PERIODS; p++) {
    assert.ok(Math.abs(block[p * PERIOD] + peak) < peak * 1e-9, `period ${p} starts at the trough`);
    assert.ok(Math.abs(block[p * PERIOD + 1] - peak) < peak * 1e-9,
      `period ${p}: the corner is the second sample, exactly at the peak`);
  }
});
