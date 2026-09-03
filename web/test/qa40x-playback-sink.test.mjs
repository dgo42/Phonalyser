/*
 * Phonalyser web - Qa40xPlaybackSink sample-source contract test.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The Java class (Qa40xGenerator) has no unit test - it only exists inside a live USB session -
 * so these assertions pin the four things its sample source decides, all of which are silent
 * failures on real hardware:
 *
 *   1. the PEAK convention: round(clamp(s) · MAXINT) with NO √2. An RMS factor here would be
 *      ~3 dB hot, and 2^31 instead of MAXINT would wrap the wire word;
 *   2. saturation, on the sample AND after the per-lane scale - an unclamped ±2 sample wraps
 *      through Int32Array to a near-zero word, i.e. full-scale garbage instead of a clipped tone.
 *      (The sample clamp and the int32 saturation are deliberately redundant, exactly as in Java:
 *      dropping ONE is masked by the other, dropping both fails these two cases.);
 *   3. the Left/Right/Both gate zeroing the un-driven lane (digital silence, not a copy);
 *   4. TPDF dither at the SELECTED depth, read by value per noise value so a live depth change
 *      can never combine one draw at 8 bits with the other at 0 (the (0 − 1) hazard);
 *   5. resetSweepPosition() BEFORE the attach - warmup already consumed nextSample()s, and a
 *      sweep that starts mid-flight is misaligned against its deconvolution reference.
 *
 * The sink is driven through the REAL DdsKernel (RECTANGLE at fs/2 with dacFsVoltageAmpl = 1 emits
 * exactly ±amplitudeVRms, so the expected wire words are exact integers) and a fake duplex engine
 * that captures the sample source the sink attaches - the same seam the real engine uses, so a
 * broken attach cannot pass.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { DdsKernel } from '../js/generator/dds-kernel.js';
import { GenSignalForm } from '../js/enums/gen-signal-form.js';
import { CHANNELS } from '../js/qa40x/qa40x-duplex-engine.js';
import { MAXINT } from '../js/qa40x/qa40x-levels.js';
import { Qa40xPlaybackSink } from '../js/qa40x/qa40x-playback-sink.js';

const RATE = 48000;
/** fs/2 with the default 0.5 duty ⇒ the rectangle alternates +1, −1, +1, ... exactly. */
const TONE = RATE / 2;

/** Stands in for Qa40xDuplexEngine: records the lane calls and keeps the attached source. */
class FakeEngine {
  constructor(log) {
    this.log = log;
    this.source = null;
  }

  attachGenerator(source) {
    this.source = source;
    this.log.push('attach');
    return Promise.resolve();
  }

  detachGenerator() {
    this.log.push('detach');
    this.source = null;
    return Promise.resolve();
  }
}

/** Stands in for Qa40xDeviceManager: hands out the one duplex engine. */
function makeManager(log) {
  return {
    engine: null,
    acquireEngine(rateHz) {
      log.push('acquire ' + rateHz);
      this.engine = new FakeEngine(log);
      return this.engine;
    },
    /** The manager hands the analyzer back when nothing is using it. close() calls this because an
     *  open() whose start() threw (a malformed .dpd reaches #applyControl) leaves no lane to
     *  detach, so the engine's last-detach release can never fire. */
    releaseIfIdle() {
      log.push('releaseIfIdle');
    },
  };
}

/**
 * Opens + starts a sink on the fake engine and returns everything a case needs: the sink, the
 * captured sample source, and the call log.
 */
async function startSink(spec = {}, deps = {}) {
  const log = [];
  const manager = makeManager(log);
  const sink = new Qa40xPlaybackSink(manager, deps);
  const granted = await sink.open({ sampleRate: RATE });
  await sink.start({
    form: GenSignalForm.RECTANGLE,
    frequency: TONE,
    amplitudeVRms: 1.0,
    dacFsVoltageAmpl: 1.0,   // ⇒ kernel amplitude = amplitudeVRms (rawRms(RECTANGLE) = 1)
    ditherBits: 0,
    outputChannels: 'BOTH',
    rightLaneScale: 1.0,
    ...spec,
  });
  return { sink, manager, log, granted, source: manager.engine.source };
}

/** Pulls `frames` interleaved L,R int32 frames out of the attached source. */
function pull(source, frames) {
  const dest = new Int32Array(CHANNELS * frames);
  source(dest, frames);
  return dest;
}

/** A deterministic uniform source: 0.75, 0.25, 0.75, ... ⇒ tpdf draw difference = +0.5 exactly. */
function cycleRng(values) {
  let i = 0;
  return () => values[i++ % values.length];
}

test('the peak convention scales a ±1 sample to ±MAXINT - no √2, no 2^31', async () => {
  const { source } = await startSink();

  const d = pull(source, 2);

  // RECTANGLE at fs/2: +1 then −1, both lanes (BOTH, right scale 1.0).
  assert.equal(d[0], MAXINT, 'left lane of a +1 sample is exactly MAXINT');
  assert.equal(d[1], MAXINT, 'right lane too - the scale is 1.0');
  assert.equal(d[2], -MAXINT, 'the −1 sample lands on −MAXINT');
  assert.equal(d[3], -MAXINT);
  // An RMS (÷√2) factor would land here instead - the ~3 dB-hot mistake the Java warns about.
  assert.notEqual(d[0], Math.round(MAXINT / Math.SQRT2));
});

test('a sample beyond ±1 saturates instead of wrapping', async () => {
  const { source } = await startSink({ amplitudeVRms: 2.0 });   // kernel emits ±2

  const d = pull(source, 2);

  assert.equal(d[0], MAXINT, '+2 clamps to +MAXINT');
  assert.equal(d[1], MAXINT);
  assert.equal(d[2], -MAXINT, '−2 clamps to −MAXINT');
  // Unclamped, 2·MAXINT stored into an Int32Array wraps to −2: full-scale garbage, not a clip.
  assert.notEqual(d[0], -2);
});

test('the per-lane scale is saturated too, so a right-lane scale > 1 cannot wrap', async () => {
  const { source } = await startSink({ rightLaneScale: 2.0 });

  const d = pull(source, 1);

  assert.equal(d[0], MAXINT, 'left keeps the mono amplitude (scale 1.0)');
  assert.equal(d[1], MAXINT, 'right is clamped after the ×2, not wrapped');
});

test('a right-lane scale BELOW 1 lands on the right lane only, never on the left', async () => {
  // The scale is fsLeft/fsRight, so it belongs to the right lane alone - the left lane IS the
  // mono amplitude reference (Java pushOutputRoutingToPlayback: setChannelScale(1.0, ...)). The
  // saturating case above cannot see the pair swapped: at 2.0 BOTH lanes clip to MAXINT either
  // way. Below 1 nothing saturates, so a swap is visible - and on a LINKED card with distinct
  // DAC full-scales it would emit two different PHYSICAL levels, which no assertion downstream
  // of the wire can catch.
  const { source } = await startSink({ rightLaneScale: 0.5 });

  const d = pull(source, 2);

  assert.equal(d[0], MAXINT, 'the left lane keeps the unscaled mono amplitude');
  assert.equal(d[1], Math.round(0.5 * MAXINT), 'and only the right lane is scaled');
  assert.equal(d[2], -MAXINT, 'the negative half-cycle does not move the scale across either');
  assert.equal(d[3], Math.round(-0.5 * MAXINT));
});

test('the L/R/Both gate writes digital silence into the un-driven lane', async () => {
  const both = pull((await startSink({ outputChannels: 'BOTH' })).source, 1);
  assert.deepEqual([both[0], both[1]], [MAXINT, MAXINT], 'BOTH drives both lanes');

  const left = pull((await startSink({ outputChannels: 'LEFT' })).source, 2);
  assert.deepEqual([left[0], left[1]], [MAXINT, 0], 'LEFT: right lane silent');
  assert.deepEqual([left[2], left[3]], [-MAXINT, 0], 'and stays silent on the next frame');

  const right = pull((await startSink({ outputChannels: 'RIGHT' })).source, 2);
  assert.deepEqual([right[0], right[1]], [0, MAXINT], 'RIGHT: left lane silent');
  assert.deepEqual([right[2], right[3]], [0, -MAXINT]);
});

test('the gate is honoured LIVE - a setOutputChannels between fills moves the silence', async () => {
  const { sink, source } = await startSink();

  assert.deepEqual(Array.from(pull(source, 1)), [MAXINT, MAXINT]);
  sink.setOutputChannels('LEFT');
  const gated = pull(source, 1);
  assert.equal(gated[1], 0, 'the next fill already honours the new gate');
});

test('dither bits 0 is OFF - the wire word carries the sample and nothing else', async () => {
  // Silent tone (amplitude 0) so ANY dither would be the only thing on the wire.
  const { source } = await startSink({ amplitudeVRms: 0.0, ditherBits: 0 },
    { rng: cycleRng([0.75, 0.25]) });

  const d = pull(source, 4);

  assert.deepEqual(Array.from(d), [0, 0, 0, 0, 0, 0, 0, 0], '0 bits ⇒ no noise at all');
});

test('the dither amplitude follows the SELECTED depth, not the 32-bit container', async () => {
  // (0.75 − 0.25) / 2^(bits−1) - the ±1 LSB TPDF amplitude at the target depth.
  const at8 = pull((await startSink({ amplitudeVRms: 0.0, ditherBits: 8 },
    { rng: cycleRng([0.75, 0.25]) })).source, 2);
  const at24 = pull((await startSink({ amplitudeVRms: 0.0, ditherBits: 24 },
    { rng: cycleRng([0.75, 0.25]) })).source, 2);

  assert.equal(at8[0], Math.round(MAXINT * 0.5 / Math.pow(2, 7)), '8-bit dither: 0.5/2^7 FS');
  assert.equal(at8[0], 8388608, '≈ −48 dBFS - the audible 8-bit floor');
  assert.equal(at24[0], Math.round(MAXINT * 0.5 / Math.pow(2, 23)), '24-bit dither: 0.5/2^23 FS');
  assert.equal(at24[0], 128, 'lands in the DAC-dropped low byte');
  // 65536× apart: a dither scaled by the 32-bit wire container instead would collapse them.
  assert.equal(at8[0] / at24[0], 65536);
  assert.equal(at8[1], at8[0], 'both lanes carry the same physical dither');
});

test('the dither depth is read BY VALUE per noise value - a live change cannot mix (0 − 1)', async () => {
  // The rng flips the depth to OFF between the two draws of the FIRST noise value. A correct
  // implementation passes the depth into the noise computation once, so that value stays an
  // 8-bit ±1 LSB; an implementation that re-read the field for the divisor would produce
  // 0.5 / 2^(0−1) = 1.0 - full scale, i.e. a full-scale click from a dither combo-box change.
  let flipped = false;
  const sinkRef = {};
  const rng = () => {
    if (!flipped) {
      flipped = true;
      sinkRef.sink.setDitherBits(0);
      return 0.75;
    }
    return 0.25;
  };
  const started = await startSink({ amplitudeVRms: 0.0, ditherBits: 8 }, { rng });
  sinkRef.sink = started.sink;

  const d = pull(started.source, 2);

  assert.equal(d[0], 8388608, 'the in-flight noise value stayed at its 8-bit amplitude');
  assert.notEqual(d[0], MAXINT, 'never a full-scale (0 − 1) hybrid');
  assert.equal(d[2], 0, 'the NEXT sample already sees the new depth (off)');
});

test('resetSweepPosition() runs BEFORE the lane is attached', async () => {
  const log = [];
  const original = DdsKernel.prototype.resetSweepPosition;
  DdsKernel.prototype.resetSweepPosition = function patched(...args) {
    log.push('reset');
    return original.apply(this, args);
  };
  try {
    const manager = makeManager(log);
    const sink = new Qa40xPlaybackSink(manager);
    await sink.open({ sampleRate: RATE });
    await sink.start({
      form: GenSignalForm.LOG_SWEEP, frequency: 1000, amplitudeVRms: 1.0,
      dacFsVoltageAmpl: 1.0, ditherBits: 0, outputChannels: 'BOTH', rightLaneScale: 1.0,
    });

    // The engine primes past the start threshold inside attachGenerator, so a rewind that came
    // after the attach would already have shipped mis-aligned sweep samples to the DAC.
    assert.deepEqual(log, ['acquire ' + RATE, 'reset', 'attach']);
  } finally {
    DdsKernel.prototype.resetSweepPosition = original;
  }
});

test('close() detaches the lane exactly once, and is a no-op when idle', async () => {
  const { sink, log } = await startSink();

  await sink.close();
  await sink.close();

  // Exactly ONE detach - the point of the test. Both closes ask the manager to release, which is
  // deliberate: the request is idle-only and idempotent, and it is the ONLY thing that hands the
  // analyzer back when open() succeeded but start() never did (no lane exists to detach then).
  assert.deepEqual(log, ['acquire ' + RATE, 'attach', 'detach', 'releaseIfIdle', 'releaseIfIdle'],
    'the second close must not detach a lane this sink no longer owns');
  assert.equal(sink.port, null, 'no live-control channel once the generator is gone');
});

test('the control port applies the live-control protocol to the in-process kernel', async () => {
  const { sink, source } = await startSink();

  // The same message vocabulary the dds-processor worklet speaks - here it must reach the kernel
  // the sink drives (a QA40x session has no worklet), so a freqresp sweep / FLL trim / lane-gate
  // push works identically on both backends.
  sink.port.postMessage({ rectDuty: 0.5, outputChannels: 'RIGHT', rightLaneScale: 0.5 });
  const gated = pull(source, 1);
  assert.deepEqual([gated[0], gated[1]], [0, Math.round(MAXINT * 0.5)],
    'the gate AND the right-lane scale arrived');

  sink.port.postMessage({ amplitudeVRms: 0.0, outputChannels: 'BOTH' });
  assert.deepEqual(Array.from(pull(source, 1)), [0, 0], 'the kernel amplitude arrived too');
});

test('a BUFFER lane carries the dither its OPEN was given - the only route it has', async () => {
  // A buffer lane (file play, a pre-rendered sweep) never goes through start() and exposes no
  // live-control port, so the open is its one and only chance to learn the depth. A silent buffer
  // makes any noise on the wire the dither's alone.
  const log = [];
  const manager = makeManager(log);
  const sink = new Qa40xPlaybackSink(manager, { rng: cycleRng([0.75, 0.25]) });
  await sink.open({ sampleRate: RATE, ditherBits: 8 });
  await sink.playBuffer(new Float32Array(4));

  const d = pull(manager.engine.source, 1);

  assert.equal(d[0], Math.round(MAXINT * 0.5 / Math.pow(2, 7)), '8-bit TPDF on the left lane');
  assert.equal(d[1], d[0], 'and the same physical dither on the right');
  assert.equal(sink.port, null, 'a buffer lane has no live channel - the open was the only route');
});

test('a BUFFER lane opened with no dither stays bit-exact', async () => {
  // The other half of the rule: nothing is added when nothing was configured, so a sweep that
  // opens without a dither still plays its reference samples untouched.
  const log = [];
  const manager = makeManager(log);
  const sink = new Qa40xPlaybackSink(manager, { rng: cycleRng([0.75, 0.25]) });
  await sink.open({ sampleRate: RATE });
  await sink.playBuffer(Float32Array.from([1.0, -1.0]));

  const d = pull(manager.engine.source, 2);

  assert.deepEqual(Array.from(d), [MAXINT, MAXINT, -MAXINT, -MAXINT], 'no noise, no rounding drift');
});
