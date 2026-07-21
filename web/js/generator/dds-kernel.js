/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.generator.SignalGenerator
// (DDS kernels, 64-bit phase accumulator, Taylor sine correction, all
// waveforms, pink-noise Voss–McCartney, dither helper, and the .dpd
// harmonic / intermod compensation: loadHarmonics / loadIntermod /
// applyCompensation). Also mirrors org.edgo.audio.measure.enums.GenSignalForm
// and the DDS constants from org.edgo.audio.measure.common.Constants.
//
// The Java generator runs a signed `long` phase accumulator where the natural
// 2^64 wrap IS the phase wrap. A faithful BigInt port of that integer
// arithmetic would allocate a fresh immutable BigInt on EVERY add/multiply —
// ~1M heap allocations/sec on the realtime AudioWorklet thread, growing the
// worklet realm until the renderer crashes (Aw-Snap STATUS_BREAKPOINT) under
// GC pressure. So the phase is carried instead as a double-precision count of
// *turns* in [0,1): a plain Number, allocation-free in the per-sample hot path.
// This is NOT a precision loss — a turn stored in a double's 53-bit mantissa
// gives ~0.2 pHz frequency granularity, finer than the original 64-bit target.
// Phase wraps with `ph -= Math.floor(ph)` instead of a 2^64 mask. Everything
// floating-point (sine/cos table, Taylor correction, amplitude, noise) stays in
// Float64Array / double, exactly as Java.

// -------------------------------------------------------------------------
// Constants (org.edgo.audio.measure.common.Constants)
// -------------------------------------------------------------------------

/** Radians per full turn (one [0,1) phase cycle). */
const TWO_PI = 2.0 * Math.PI;

// -------------------------------------------------------------------------
// Signal form enum (org.edgo.audio.measure.enums.GenSignalForm)
// -------------------------------------------------------------------------

/**
 * Waveform identifiers — string-valued so they survive structured-clone across
 * the worklet message port. Values match the Java enum names.
 * @enum {string}
 */
export const GenSignalForm = Object.freeze({
  SINE: 'SINE',
  SINE_COMP: 'SINE_COMP',
  DUAL_TONE: 'DUAL_TONE',
  DUAL_TONE_COMP: 'DUAL_TONE_COMP',
  TRIANGLE: 'TRIANGLE',
  RECTANGLE: 'RECTANGLE',
  WHITE_NOISE: 'WHITE_NOISE',
  PINK_NOISE: 'PINK_NOISE',
  PINK_NOISE_LINEAR: 'PINK_NOISE_LINEAR',
  LINEAR_SWEEP: 'LINEAR_SWEEP',
  LOG_SWEEP: 'LOG_SWEEP',
});

/**
 * True for the two-tone waveforms (DUAL_TONE / DUAL_TONE_COMP).
 * @param {string} form
 * @returns {boolean}
 */
export function isDualTone(form) {
  return form === GenSignalForm.DUAL_TONE || form === GenSignalForm.DUAL_TONE_COMP;
}

/**
 * True for every waveform that has a meaningful frequency — i.e. all forms EXCEPT the
 * three noise sources (mirrors GenSignalForm.isPeriodic; the generator's Frequency field
 * is disabled, but still visible, for noise).
 * @param {string} form
 * @returns {boolean}
 */
export function isPeriodic(form) {
  return form !== GenSignalForm.WHITE_NOISE
      && form !== GenSignalForm.PINK_NOISE
      && form !== GenSignalForm.PINK_NOISE_LINEAR;
}

/**
 * Parses a textual signal-form name (CLI aliases included), mirroring
 * GenSignalForm.fromString. Throws on an unknown name.
 * @param {string} s
 * @returns {string} one of {@link GenSignalForm}
 */
export function formFromString(s) {
  switch (String(s).toLowerCase()) {
    case 'sine': return GenSignalForm.SINE;
    case 'sine_compensated': case 'sine_hmc': return GenSignalForm.SINE_COMP;
    case 'triangle': case 'tri': return GenSignalForm.TRIANGLE;
    case 'rectangle': case 'rect': case 'square': case 'pulse': return GenSignalForm.RECTANGLE;
    case 'white': case 'white_noise': return GenSignalForm.WHITE_NOISE;
    case 'pink': case 'pink_noise': return GenSignalForm.PINK_NOISE;
    case 'pink_linear': case 'pink_noise_linear': return GenSignalForm.PINK_NOISE_LINEAR;
    case 'linear_sweep': case 'sweep': case 'chirp': return GenSignalForm.LINEAR_SWEEP;
    case 'log_sweep': case 'farina': return GenSignalForm.LOG_SWEEP;
    case 'dual_tone': case 'dualtone': case 'twotone': case 'two_tone': return GenSignalForm.DUAL_TONE;
    case 'dual_tone_compensated': case 'dualtone_hmc': case 'twotone_hmc': return GenSignalForm.DUAL_TONE_COMP;
    default:
      throw new Error('Unknown signal form: ' + s +
        '. Valid: sine, sine_compensated, triangle, rectangle, white_noise, pink_noise, ' +
        'pink_noise_linear, linear_sweep, log_sweep, dual_tone, dual_tone_compensated');
  }
}

// -------------------------------------------------------------------------
// DDS sine lookup table (full wave, 4096 entries)
// -------------------------------------------------------------------------

const TABLE_BITS = 12;
const TABLE_SIZE = 1 << TABLE_BITS; // 4096
/** Radix (2^32) of the two-word 64-bit fixed-point phase accumulator. */
const PHASE_2P32 = 4294967296;
/** 1/2^53 — scales the top 53 bits of the accumulator to a turn in [0,1) (Constants.ONE_OVER_2_53). */
const ONE_OVER_2_53 = 2 ** -53;
/** 2^21 — lifts the high word into the top-53-bit turn (the hi word carries bits 33..53 of it). */
const HI_TO_TOP53 = 1 << 21;
/**
 * Splits a turns-per-sample increment (double in [0,1)) into the high + low 32-bit words of a
 * 64-bit fixed-point increment, so the phase can advance by EXACT integer addition each sample
 * with no floating-point drift — faithful to SignalGenerator's 64-bit `long` accumulator, where
 * `long` overflow IS the turn wrap.
 * @param {number} turns turns-per-sample in [0,1)
 * @returns {{hi:number, lo:number}}
 */
function incWords(turns) {
  const scaled = turns * PHASE_2P32;            // integer part → hi word; fraction → lo word
  let hi = Math.floor(scaled);
  let lo = Math.round((scaled - hi) * PHASE_2P32);   // round to nearest, like Math.round(freq/sr·2^64)
  if (lo >= PHASE_2P32) { lo -= PHASE_2P32; hi += 1; }   // carry the rounded lo word into hi
  return { hi: hi >>> 0, lo: lo >>> 0 };
}
const SINE_TABLE = new Float64Array(TABLE_SIZE);
const COS_TABLE = new Float64Array(TABLE_SIZE);
for (let i = 0; i < TABLE_SIZE; i++) {
  const angle = (TWO_PI * i) / TABLE_SIZE;
  SINE_TABLE[i] = Math.sin(angle);
  COS_TABLE[i] = Math.cos(angle);
}

/** Radians per table cell — sub-cell turn fraction → Taylor-correction angle. */
const RAD_PER_CELL = TWO_PI / TABLE_SIZE;

const PINK_OCTAVES = 16;

// -------------------------------------------------------------------------
// Taylor correction (5th order — matches the active branch in Java)
// -------------------------------------------------------------------------

/** cos(Δθ) Taylor series; dx2 = Δθ². */
function taylorCos(dx2) {
  return 1.0 + dx2 * (-0.5 + dx2 * (1.0 / 24.0));
}

/** sin(Δθ) Taylor series; dx = Δθ, dx2 = Δθ². */
function taylorSin(dx, dx2) {
  return dx * (1.0 + dx2 * (-1.0 / 6.0 + dx2 * (1.0 / 120.0)));
}

/** Wraps a turn count to [0,1). */
function wrapTurn(ph) {
  return ph - Math.floor(ph);
}

/**
 * Turn fraction [0,1) from the top 53 bits of the hi:lo accumulator — the web
 * mirror of SignalGenerator's `(phaseAcc >>> 11) * ONE_OVER_2_53`. Reconstructing
 * only the top 53 bits keeps the result STRICTLY below 1.0 (max = 1 − 2^-53), so
 * `ph * TABLE_SIZE` can never round up to TABLE_SIZE and index off the table —
 * unlike the full `(hi + lo/2^32)/2^32`, which rounds to exactly 1.0 when the
 * accumulator sits just under 2^64 (the wrap point hit at integer-ratio
 * frequencies such as 100 Hz / 1000 Hz).
 */
function phaseTurn53(hi, lo) {
  return (hi * HI_TO_TOP53 + (lo >>> 11)) * ONE_OVER_2_53;
}

/** sin(θ) for a phase in turns [0,1) (table + Taylor); allocation-free.
 *  `cell & (TABLE_SIZE-1)` mirrors Java's top-12-bit index extraction
 *  (`phaseAcc >>> (64-TABLE_BITS)`, always 0..4095): when a phase whose period
 *  evenly divides the accumulator (e.g. an integer frequency) rounds up to
 *  exactly 1.0, `ph*TABLE_SIZE` is 4096 and the mask folds it back to 0 (whose
 *  Δθ is 0), avoiding an out-of-range read that would return NaN. */
function ddsSineOf(ph) {
  const scaled = ph * TABLE_SIZE;
  const cell = scaled | 0;
  const idx = cell & (TABLE_SIZE - 1);
  const dx = (scaled - cell) * RAD_PER_CELL;
  const dx2 = dx * dx;
  return SINE_TABLE[idx] * taylorCos(dx2) + COS_TABLE[idx] * taylorSin(dx, dx2);
}

/** cos(θ) for a phase in turns [0,1) (table + Taylor); allocation-free.
 *  Index masked like {@link ddsSineOf} so a phase rounding to exactly 1.0 wraps
 *  to table cell 0 rather than reading past the end (NaN). */
function ddsCosOf(ph) {
  const scaled = ph * TABLE_SIZE;
  const cell = scaled | 0;
  const idx = cell & (TABLE_SIZE - 1);
  const dx = (scaled - cell) * RAD_PER_CELL;
  const dx2 = dx * dx;
  return COS_TABLE[idx] * taylorCos(dx2) - SINE_TABLE[idx] * taylorSin(dx, dx2);
}

/** Converts an initial phase (radians) to a turn offset in [0,1). */
function phaseRadToTurns(phiInit) {
  return wrapTurn(phiInit / TWO_PI);
}

// -------------------------------------------------------------------------
// Gaussian source — Marsaglia polar (Java's Random.nextGaussian algorithm)
// -------------------------------------------------------------------------

/**
 * Random source matching the API the kernel needs: a uniform [0,1) `next()`
 * and a `nextGaussian()`. Defaults to Math.random; the polar Box–Muller form
 * reproduces java.util.Random.nextGaussian's caching behaviour.
 */
class GaussianRng {
  constructor(nextDouble) {
    this._next = nextDouble || Math.random;
    this._haveNext = false;
    this._nextG = 0.0;
  }
  /** Uniform double in [0,1). */
  next() {
    return this._next();
  }
  /** Standard-normal (mean 0, σ 1) via Marsaglia polar method. */
  nextGaussian() {
    if (this._haveNext) {
      this._haveNext = false;
      return this._nextG;
    }
    let v1, v2, s;
    do {
      v1 = 2.0 * this._next() - 1.0;
      v2 = 2.0 * this._next() - 1.0;
      s = v1 * v1 + v2 * v2;
    } while (s >= 1.0 || s === 0.0);
    const multiplier = Math.sqrt((-2.0 * Math.log(s)) / s);
    this._nextG = v2 * multiplier;
    this._haveNext = true;
    return v1 * multiplier;
  }
}

// -------------------------------------------------------------------------
// Compensation sets (immutable, atomically swapped — see Java Compensation /
// DualToneComp)
// -------------------------------------------------------------------------

/**
 * Builds a single-tone harmonic compensation set.
 * @param {ArrayLike<number>} ampRatios  per-harmonic amplitude ratio (pct/100)
 * @param {ArrayLike<number>} hNums       harmonic numbers (2,3,4,…)
 * @param {ArrayLike<number>} phiInits    per-harmonic initial phase (radians)
 * @returns {{amp:Float64Array, hNum:Int32Array, phaseOffTurns:Float64Array}}
 */
export function makeCompensation(ampRatios, hNums, phiInits) {
  const n = ampRatios.length;
  const amp = Float64Array.from(ampRatios);
  const hNum = Int32Array.from(hNums);
  const phaseOffTurns = new Float64Array(n);
  for (let i = 0; i < n; i++) phaseOffTurns[i] = phaseRadToTurns(phiInits[i]);
  return { amp, hNum, phaseOffTurns };
}

/**
 * Builds a dual-tone intermod compensation set. Each product sits at
 * a·f₁ + b·f₂ with phase a·θ₁ + b·θ₂.
 * @param {ArrayLike<number>} ampRatios per-product amplitude ratio
 * @param {ArrayLike<number>} aCoef     θ₁ integer multiples (may be negative)
 * @param {ArrayLike<number>} bCoef     θ₂ integer multiples (may be negative)
 * @param {ArrayLike<number>} phiInits  per-product initial phase (radians)
 * @returns {{amp:Float64Array, a:Int32Array, b:Int32Array, phaseOffTurns:Float64Array}}
 */
export function makeDualToneComp(ampRatios, aCoef, bCoef, phiInits) {
  const n = ampRatios.length;
  const amp = Float64Array.from(ampRatios);
  const a = Int32Array.from(aCoef);
  const b = Int32Array.from(bCoef);
  const phaseOffTurns = new Float64Array(n);
  for (let i = 0; i < n; i++) phaseOffTurns[i] = phaseRadToTurns(phiInits[i]);
  return { amp, a, b, phaseOffTurns };
}

// -------------------------------------------------------------------------
// .dpd / CSV compensation parsing (loadHarmonics / loadIntermod)
// -------------------------------------------------------------------------

/**
 * Parses an fft_harmonics_*.csv / applied_compensation_*.csv text body into a
 * single-tone harmonic compensation set, applying the same system-delay phase
 * correction as SignalGenerator.loadHarmonics. Skips H1 (fundamental) but uses
 * its phase for the delay estimate.
 *
 * @param {string} text       full CSV file contents
 * @param {number} frequency  fundamental frequency (Hz)
 * @returns {{amp:Float64Array, hNum:Int32Array, phaseOffTurns:Float64Array}}
 */
export function loadHarmonics(text, frequency) {
  const rows = []; // [ampRatio, phi(rad), freqHz, hIndex]
  let phi1 = NaN;
  for (let raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (line.length === 0 || !isDigit(line.charAt(0))) continue;
    const cols = line.split(';');
    if (cols.length < 5) continue;
    const hIndex = parseInt(cols[0].trim(), 10);
    let phi;
    if (cols.length >= 7) {
      const re = parseFloat(cols[5].trim().replace(',', '.'));
      const im = parseFloat(cols[6].trim().replace(',', '.'));
      phi = Math.atan2(im, re);
    } else {
      phi = degToRad(parseFloat(cols[4].trim().replace(',', '.')));
    }
    if (hIndex === 1) {
      phi1 = phi;
      continue;
    }
    const ampPct = parseFloat(cols[3].trim().replace(',', '.'));
    const freqHz = parseFloat(cols[1].trim().replace(',', '.'));
    rows.push([ampPct / 100.0, phi, freqHz, hIndex]);
  }
  if (Number.isNaN(phi1)) phi1 = -Math.PI / 2.0; // H1 absent → no delay correction
  const omegaD = -(phi1 + Math.PI / 2.0);
  const delayRadPerHz = omegaD / frequency;
  const n = rows.length;
  const amp = new Float64Array(n);
  const hNums = new Int32Array(n);
  const phis = new Float64Array(n);
  for (let i = 0; i < n; i++) {
    const [ampRatio, phiH, freqHz, hNumber] = rows[i];
    amp[i] = ampRatio;
    hNums[i] = hNumber;
    phis[i] = phiH + freqHz * delayRadPerHz;
  }
  return makeCompensation(amp, hNums, phis);
}

/**
 * True when a .dpd/CSV body's first non-comment line marks a dual-tone intermod
 * file (header begins "a;b"). Mirrors isDualToneCorrectionFile.
 * @param {string} text
 * @returns {boolean}
 */
export function isDualToneCorrectionFile(text) {
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (line.length === 0 || line.startsWith('#')) continue;
    return line.toLowerCase().startsWith('a;b');
  }
  return false;
}

/**
 * Parses a dual-tone intermod-correction file body (a;b;…;re;im, German-locale
 * decimals) into a dual-tone compensation set. Mirrors loadIntermod: amplitude
 * = hypot(re,im), phase = atan2(im,re), no delay re-derivation.
 * @param {string} text
 * @returns {{amp:Float64Array, a:Int32Array, b:Int32Array, phaseOffTurns:Float64Array}}
 */
export function loadIntermod(text) {
  const aL = [];
  const bL = [];
  const reL = [];
  const imL = [];
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (line.length === 0 || line.startsWith('#')) continue;
    const cols = line.split(';');
    if (cols.length < 8) continue;
    const c0 = cols[0].trim().charAt(0);
    if (!isDigit(c0) && c0 !== '-') continue; // header "a;b;…"
    aL.push(parseInt(cols[0].trim(), 10));
    bL.push(parseInt(cols[1].trim(), 10));
    reL.push(parseFloat(cols[6].trim().replace(',', '.')));
    imL.push(parseFloat(cols[7].trim().replace(',', '.')));
  }
  const n = aL.length;
  const amp = new Float64Array(n);
  const phi = new Float64Array(n);
  for (let i = 0; i < n; i++) {
    amp[i] = Math.hypot(reL[i], imL[i]);
    phi[i] = Math.atan2(imL[i], reL[i]);
  }
  return makeDualToneComp(amp, aL, bL, phi);
}

function isDigit(ch) {
  return ch >= '0' && ch <= '9';
}
function degToRad(d) {
  return (d * Math.PI) / 180.0;
}

// -------------------------------------------------------------------------
// Farina log-sweep render (renderLogSweep)
// -------------------------------------------------------------------------

/**
 * Renders a unit-amplitude (peak 1.0) Farina exponential sine sweep:
 * x(t) = sin(K·(exp(t/L) − 1)), L = T/ln(f1/f0), K = 2π·f0·L. Sample 0 is
 * exactly 0 so leading silence concatenates without a discontinuity.
 * @param {number} f0           start frequency (Hz)
 * @param {number} f1           end frequency (Hz)
 * @param {number} sweepSamples sweep length (samples)
 * @param {number} sampleRate   sample rate (Hz)
 * @returns {Float64Array}
 */
export function renderLogSweep(f0, f1, sweepSamples, sampleRate) {
  const T = sweepSamples / sampleRate;
  const L = T / Math.log(f1 / f0);
  const K = 2.0 * Math.PI * f0 * L;
  const buf = new Float64Array(sweepSamples);
  for (let n = 0; n < sweepSamples; n++) {
    const t = n / sampleRate;
    buf[n] = Math.sin(K * (Math.exp(t / L) - 1.0));
  }
  return buf;
}

// -------------------------------------------------------------------------
// rawRms — theoretical RMS of the unit-amplitude waveform (rawRms)
// -------------------------------------------------------------------------

/**
 * Theoretical RMS of the raw (unit-amplitude) waveform — used to convert a
 * target V RMS into a linear peak amplitude scale. For DUAL_TONE forms the
 * value depends on the per-tone weights (w1, w2).
 * @param {string} form
 * @param {number} [w1=0.5] dual-tone weight 1
 * @param {number} [w2=0.5] dual-tone weight 2
 * @returns {number}
 */
export function rawRms(form, w1 = 0.5, w2 = 0.5) {
  switch (form) {
    case GenSignalForm.SINE:
    case GenSignalForm.SINE_COMP:
    case GenSignalForm.LINEAR_SWEEP:
    case GenSignalForm.LOG_SWEEP:
      return 1.0 / Math.sqrt(2.0);
    case GenSignalForm.TRIANGLE:
      return 1.0 / Math.sqrt(3.0);
    case GenSignalForm.RECTANGLE:
      return 1.0;
    case GenSignalForm.WHITE_NOISE:
      return 1.0;
    case GenSignalForm.PINK_NOISE:
      return 1.0 / Math.sqrt(PINK_OCTAVES + 1.0);
    case GenSignalForm.PINK_NOISE_LINEAR:
      return 1.0 / Math.sqrt(3.0 * (PINK_OCTAVES + 1.0));
    case GenSignalForm.DUAL_TONE:
    case GenSignalForm.DUAL_TONE_COMP:
      return Math.max(1e-12, Math.sqrt((w1 * w1 + w2 * w2) / 2.0));
    default:
      throw new Error('Unknown form: ' + form);
  }
}

// -------------------------------------------------------------------------
// DDS kernel — per-sample, usable in an AudioWorklet AND headless
// -------------------------------------------------------------------------

/**
 * Stateful per-sample DDS kernel — a faithful, headless-capable port of the
 * sample-generating core of SignalGenerator. One instance owns the phase
 * accumulators, noise state and live parameters; {@link DdsKernel#nextSample}
 * returns one amplitude-scaled sample and advances all accumulators, exactly
 * like SignalGenerator.nextSample. Sweep playback (linear / log) is included.
 */
export class DdsKernel {
  /**
   * @param {Object} opts
   * @param {string}  opts.form            initial waveform (default SINE)
   * @param {number}  opts.frequency       output frequency in Hz (default 1000)
   * @param {number}  opts.sampleRate      sample rate in Hz (required)
   * @param {number}  opts.amplitudeVRms   target output amplitude (V RMS, default 1)
   * @param {number}  opts.dacFsVoltageAmpl DAC full-scale PEAK voltage (= FS-sine RMS × √2, default √2)
   * @param {function():number} [opts.rng] uniform [0,1) source for noise (default Math.random)
   */
  constructor(opts) {
    const o = opts || {};
    /** Sample rate (Hz). */
    this.sampleRate = o.sampleRate;
    /** @type {string} current waveform. */
    this.form = o.form || GenSignalForm.SINE;

    // Phase accumulators: EXACT 64-bit fixed-point (two 32-bit words, hi:lo) advanced by integer
    // addition with no float drift — faithful to SignalGenerator's 64-bit `long` accumulator
    // (overflow = the turn wrap). `_phaseAcc` / `_phaseAcc2` hold the derived turn fraction [0,1)
    // recomputed each sample for the waveform readers.
    this._phaseAcc = 0.0; this._phaseHi = 0; this._phaseLo = 0;
    this._phaseAcc2 = 0.0; this._phase2Hi = 0; this._phase2Lo = 0;
    const inc0 = incWords(this._toPhaseInc(o.frequency != null ? o.frequency : 1000));
    this._phaseIncHi = inc0.hi; this._phaseIncLo = inc0.lo;
    this._phaseInc2Hi = 0; this._phaseInc2Lo = 0;

    // Amplitude scaling.
    this._dacFsVoltageAmpl = o.dacFsVoltageAmpl != null ? o.dacFsVoltageAmpl : Math.sqrt(2.0);
    this._currentVrms = o.amplitudeVRms != null ? o.amplitudeVRms : 1.0;
    this._dualW1 = 0.5;
    this._dualW2 = 0.5;
    this._amplitude = this._currentVrms / (this._dacFsVoltageAmpl * rawRms(this.form));

    // Duty cycles.
    this._rectDuty = 0.5;
    this._triDuty = 0.5;

    // Pink-noise state.
    this._pinkRows = new Float64Array(PINK_OCTAVES);
    this._pinkRunningSum = 0.0;
    this._pinkCounter = 0;
    this._rng = new GaussianRng(o.rng);

    // Compensation (null = uncorrected).
    this._comp = null;
    this._dtComp = null;

    // Linear-sweep state.
    this._sweepPeriodSamples = 0;
    this._sweepFreqStart = 0.0;
    this._sweepFreqEnd = 0.0;
    this._sweepIdx = 0;
    this._sweepPhase = 0.0;
    this._sweepLoop = true;
    this._fadeInSamples = 0;
    this._fadeOutSamples = 0;

    // Log-sweep state.
    this._logSweepBuffer = null;
    this._logSweepLeadIn = 0;
    this._logSweepIdx = 0; // sample index (Number — sweeps are far short of 2^53)
    this._logSweepRestart = false;
  }

  /** Frequency (Hz) → phase increment in turns-per-sample (a plain Number). */
  _toPhaseInc(frequencyHz) {
    return frequencyHz / this.sampleRate;
  }

  // ---- live parameter setters (match SignalGenerator's public API) -------

  /** Live-swaps the waveform, re-scaling amplitude to keep V RMS. */
  setForm(newForm) {
    if (newForm == null || newForm === this.form) return;
    this.form = newForm;
    this._amplitude = this._currentVrms / (this._dacFsVoltageAmpl * rawRms(newForm, this._dualW1, this._dualW2));
  }

  /** Live-updates the output frequency (Hz), phase-continuous. */
  setFrequency(frequencyHz) {
    const p = incWords(this._toPhaseInc(frequencyHz));
    this._phaseIncHi = p.hi; this._phaseIncLo = p.lo;
  }

  /** Live-updates the second tone's frequency (Hz) for DUAL_TONE forms. */
  setDualToneFrequency2(frequencyHz) {
    const p = incWords(this._toPhaseInc(frequencyHz));
    this._phaseInc2Hi = p.hi; this._phaseInc2Lo = p.lo;
  }

  /** Live-updates the dual-tone amplitude split (per-tone percentages). */
  setDualToneAmplitudes(amp1Pct, amp2Pct) {
    this._dualW1 = Math.max(0.0, Math.min(100.0, amp1Pct)) / 100.0;
    this._dualW2 = Math.max(0.0, Math.min(100.0, amp2Pct)) / 100.0;
    this._amplitude = this._currentVrms / (this._dacFsVoltageAmpl * rawRms(this.form, this._dualW1, this._dualW2));
  }

  /** Live-updates the output amplitude (V RMS). */
  setAmplitudeVrms(amplitudeVRms) {
    this._currentVrms = amplitudeVRms;
    this._amplitude = amplitudeVRms / (this._dacFsVoltageAmpl * rawRms(this.form, this._dualW1, this._dualW2));
  }

  /** Pushes a new DAC full-scale PEAK voltage and recomputes amplitude. */
  setDacFsVoltageAmpl(v) {
    this._dacFsVoltageAmpl = v;
    this._amplitude = this._currentVrms / (v * rawRms(this.form, this._dualW1, this._dualW2));
  }

  /** Live-updates the rectangle duty cycle, clamped to [0.001, 0.999]. */
  setRectangleDuty(duty) {
    if (!Number.isFinite(duty)) return;
    if (duty < 0.001) duty = 0.001;
    if (duty > 0.999) duty = 0.999;
    this._rectDuty = duty;
  }

  /** Live-updates the triangle duty (rise fraction), clamped to [0.001, 0.999]. */
  setTriangleDuty(duty) {
    if (!Number.isFinite(duty)) return;
    if (duty < 0.001) duty = 0.001;
    if (duty > 0.999) duty = 0.999;
    this._triDuty = duty;
  }

  /**
   * Hot-swaps the single-tone harmonic compensation and switches to SINE_COMP.
   * Accepts either a prebuilt set ({@link makeCompensation}/{@link loadHarmonics})
   * or raw (ampRatios, hNums, phiInits) arrays.
   * @param {Object|ArrayLike<number>} compOrAmp
   * @param {ArrayLike<number>} [hNums]
   * @param {ArrayLike<number>} [phiInits]
   */
  applyCompensation(compOrAmp, hNums, phiInits) {
    this._comp = hNums === undefined ? compOrAmp : makeCompensation(compOrAmp, hNums, phiInits);
    this.form = GenSignalForm.SINE_COMP;
  }

  /**
   * Hot-swaps the dual-tone intermod compensation and switches to DUAL_TONE_COMP.
   * Accepts a prebuilt set ({@link makeDualToneComp}/{@link loadIntermod}) or raw
   * (ampRatios, aCoef, bCoef, phiInits) arrays.
   * @param {Object|ArrayLike<number>} compOrAmp
   * @param {ArrayLike<number>} [aCoef]
   * @param {ArrayLike<number>} [bCoef]
   * @param {ArrayLike<number>} [phiInits]
   */
  applyDualToneCompensation(compOrAmp, aCoef, bCoef, phiInits) {
    this._dtComp = aCoef === undefined ? compOrAmp : makeDualToneComp(compOrAmp, aCoef, bCoef, phiInits);
    this.form = GenSignalForm.DUAL_TONE_COMP;
  }

  /** Drops any compensation, returning a compensated form to its plain tone. */
  clearCompensation() {
    this._comp = null;
    this._dtComp = null;
    if (this.form === GenSignalForm.SINE_COMP) this.form = GenSignalForm.SINE;
    if (this.form === GenSignalForm.DUAL_TONE_COMP) this.form = GenSignalForm.DUAL_TONE;
  }

  // ---- sweep configuration ----------------------------------------------

  /**
   * Configures the LINEAR_SWEEP state (call before generating sweep samples).
   * @param {number} freqStart start frequency (Hz)
   * @param {number} freqEnd   end frequency (Hz)
   * @param {number} periodSamples sweep period (samples)
   */
  configureLinearSweep(freqStart, freqEnd, periodSamples) {
    this._sweepFreqStart = freqStart;
    this._sweepFreqEnd = freqEnd;
    this._sweepPeriodSamples = periodSamples;
    this._sweepIdx = 0;
    this._sweepPhase = 0.0;
  }

  /**
   * Configures the LOG_SWEEP state by rendering its reference buffer.
   * @param {number} f0 start frequency (Hz)
   * @param {number} f1 end frequency (Hz)
   * @param {number} sweepSamples sweep length (samples)
   * @param {number} leadInSamples silent lead-in samples
   */
  configureLogSweep(f0, f1, sweepSamples, leadInSamples) {
    this._logSweepBuffer = renderLogSweep(f0, f1, sweepSamples, this.sampleRate);
    this._logSweepLeadIn = leadInSamples;
    this._logSweepIdx = 0;
    this._logSweepRestart = false;
  }

  /** The pre-rendered log-sweep reference buffer (for deconvolution). */
  getLogSweepBuffer() {
    return this._logSweepBuffer;
  }

  /** Live-applies sweep loop + Hann fade lengths (clamped to half a cycle). */
  setSweepParams(loop, fadeInSamples, fadeOutSamples) {
    this._sweepLoop = loop;
    const half = Math.max(0, Math.floor(this._sweepCycleLength() / 2));
    this._fadeInSamples = Math.max(0, Math.min(fadeInSamples, half));
    this._fadeOutSamples = Math.max(0, Math.min(fadeOutSamples, half));
  }

  /** Resets all sweep / phase playback position state. */
  resetSweepPosition() {
    this._logSweepRestart = false;
    this._logSweepIdx = 0;
    this._sweepIdx = 0;
    this._sweepPhase = 0.0;
    this._phaseAcc = 0.0; this._phaseHi = 0; this._phaseLo = 0;
    this._phaseAcc2 = 0.0; this._phase2Hi = 0; this._phase2Lo = 0;
  }

  _sweepCycleLength() {
    if (this.form === GenSignalForm.LINEAR_SWEEP) return this._sweepPeriodSamples;
    if (this.form === GenSignalForm.LOG_SWEEP) return this._logSweepBuffer ? this._logSweepBuffer.length : 0;
    return 0;
  }

  // ---- per-sample kernel -------------------------------------------------

  /**
   * Returns the next sample in [-1,+1] scaled by amplitude, advancing the phase
   * accumulator(s). Faithful to SignalGenerator.nextSample.
   * @returns {number}
   */
  nextSample() {
    // Derive the turn fraction(s) [0,1) from the EXACT 64-bit accumulator(s) for this sample.
    this._phaseAcc = phaseTurn53(this._phaseHi, this._phaseLo);
    const dual = this.form === GenSignalForm.DUAL_TONE || this.form === GenSignalForm.DUAL_TONE_COMP;
    if (dual) this._phaseAcc2 = phaseTurn53(this._phase2Hi, this._phase2Lo);
    let raw;
    switch (this.form) {
      case GenSignalForm.SINE: raw = ddsSineOf(this._phaseAcc); break;
      case GenSignalForm.TRIANGLE: raw = this._ddsTriangle(); break;
      case GenSignalForm.RECTANGLE: raw = this._ddsRectangle(); break;
      case GenSignalForm.WHITE_NOISE: raw = this._rng.nextGaussian(); break;
      case GenSignalForm.PINK_NOISE: raw = this._pinkNoise(false); break;
      case GenSignalForm.PINK_NOISE_LINEAR: raw = this._pinkNoise(true); break;
      case GenSignalForm.SINE_COMP: raw = this._ddsSineCompensated(); break;
      case GenSignalForm.LINEAR_SWEEP: raw = this._linearSweepNext(); break;
      case GenSignalForm.LOG_SWEEP: raw = this._logSweepNext(); break;
      case GenSignalForm.DUAL_TONE:
        raw = ddsSineOf(this._phaseAcc) * this._dualW1 + ddsSineOf(this._phaseAcc2) * this._dualW2;
        break;
      case GenSignalForm.DUAL_TONE_COMP: raw = this._ddsDualToneCompensated(); break;
      default: raw = 0.0; break;
    }
    // Advance by exact 64-bit integer addition (carry-propagated; the high word wrapping mod
    // 2^32 IS the 2^64 turn wrap — no float accumulation error / drift).
    let lo = this._phaseLo + this._phaseIncLo, carry = 0;
    if (lo >= PHASE_2P32) { lo -= PHASE_2P32; carry = 1; }
    this._phaseLo = lo;
    this._phaseHi = (this._phaseHi + this._phaseIncHi + carry) % PHASE_2P32;
    if (dual) {
      let lo2 = this._phase2Lo + this._phaseInc2Lo, carry2 = 0;
      if (lo2 >= PHASE_2P32) { lo2 -= PHASE_2P32; carry2 = 1; }
      this._phase2Lo = lo2;
      this._phase2Hi = (this._phase2Hi + this._phaseInc2Hi + carry2) % PHASE_2P32;
    }
    return raw * this._amplitude;
  }

  /**
   * Fills `out` (length `n`) with `n` samples (or out.length if n omitted).
   * @param {Float32Array|Float64Array|number[]} out
   * @param {number} [n=out.length]
   */
  fill(out, n) {
    const count = n != null ? n : out.length;
    for (let i = 0; i < count; i++) out[i] = this.nextSample();
  }

  // ---- waveform kernels --------------------------------------------------

  _ddsRectangle() {
    const frac = this._phaseAcc; // turns in [0,1)
    return frac < this._rectDuty ? 1.0 : -1.0;
  }

  _ddsTriangle() {
    const frac = this._phaseAcc; // turns in [0,1)
    const d = this._triDuty;
    if (frac < d) return (frac / d) * 2.0 - 1.0;
    return 1.0 - ((frac - d) / (1.0 - d)) * 2.0;
  }

  _pinkNoise(linear) {
    this._pinkCounter += 1;
    const trailing = trailingZerosLow(this._pinkCounter);
    if (trailing < PINK_OCTAVES) {
      const newVal = linear ? this._rng.next() * 2.0 - 1.0 : this._rng.nextGaussian();
      this._pinkRunningSum += newVal - this._pinkRows[trailing];
      this._pinkRows[trailing] = newVal;
    }
    const white = linear ? this._rng.next() * 2.0 - 1.0 : this._rng.nextGaussian();
    return (this._pinkRunningSum + white) / (PINK_OCTAVES + 1.0);
  }

  _ddsSineCompensated() {
    let s = ddsSineOf(this._phaseAcc);
    const c = this._comp;
    if (c != null) {
      for (let i = 0; i < c.amp.length; i++) {
        const hPhase = wrapTurn(this._phaseAcc * c.hNum[i] + c.phaseOffTurns[i]);
        s -= c.amp[i] * ddsCosOf(hPhase);
      }
    }
    return s;
  }

  _ddsDualToneCompensated() {
    let s = ddsSineOf(this._phaseAcc) * this._dualW1 + ddsSineOf(this._phaseAcc2) * this._dualW2;
    const c = this._dtComp;
    if (c != null) {
      for (let i = 0; i < c.amp.length; i++) {
        const pPhase = wrapTurn(this._phaseAcc * c.a[i] + this._phaseAcc2 * c.b[i] + c.phaseOffTurns[i]);
        s -= c.amp[i] * ddsCosOf(pPhase);
      }
    }
    return s;
  }

  // ---- sweep kernels -----------------------------------------------------

  _linearSweepNext() {
    if (this._sweepIdx >= this._sweepPeriodSamples) {
      if (!this._sweepLoop) return 0.0;
      this._sweepIdx = 0;
      this._sweepPhase = 0.0;
    }
    const frac = this._sweepIdx / this._sweepPeriodSamples;
    const instF = this._sweepFreqStart + (this._sweepFreqEnd - this._sweepFreqStart) * frac;
    const s = Math.sin(this._sweepPhase);
    this._sweepPhase += (2.0 * Math.PI * instF) / this.sampleRate;
    if (this._sweepPhase >= 2.0 * Math.PI) {
      this._sweepPhase -= 2.0 * Math.PI * Math.floor(this._sweepPhase / (2.0 * Math.PI));
    }
    const env = this._sweepEnvelope(this._sweepIdx, this._sweepPeriodSamples);
    this._sweepIdx++;
    return s * env;
  }

  _logSweepNext() {
    const buf = this._logSweepBuffer;
    if (this._logSweepRestart) {
      this._logSweepRestart = false;
      this._logSweepIdx = this._logSweepLeadIn;
    }
    const idx = this._logSweepIdx++;
    if (idx < this._logSweepLeadIn) return 0.0;
    let sIdx = idx - this._logSweepLeadIn;
    const bufLen = buf.length;
    if (sIdx >= bufLen) {
      if (!this._sweepLoop) return 0.0;
      const completed = Math.floor((sIdx - 1) / bufLen) + 1;
      this._logSweepIdx = this._logSweepLeadIn + (sIdx - completed * bufLen) + 1;
      sIdx = sIdx % bufLen;
    }
    const bufIdx = sIdx;
    const env = this._sweepEnvelope(bufIdx, bufLen);
    return buf[bufIdx] * env;
  }

  _sweepEnvelope(idx, cycleLength) {
    const fIn = this._fadeInSamples;
    const fOut = this._fadeOutSamples;
    if (fIn > 0 && idx < fIn) {
      return 0.5 * (1.0 - Math.cos((Math.PI * idx) / fIn));
    }
    if (fOut > 0 && idx >= cycleLength - fOut) {
      let back = cycleLength - 1 - idx;
      if (back < 0) back = 0;
      return 0.5 * (1.0 - Math.cos((Math.PI * back) / fOut));
    }
    return 1.0;
  }
}

/**
 * Trailing-zero count of the low bits of a positive integer counter
 * (Voss–McCartney octave select). The counter is only inspected modulo
 * 2^PINK_OCTAVES, so its low bits — well within 2^53 — are exact in a Number,
 * and `& -low` isolates the lowest set bit via 32-bit bitwise ops.
 */
function trailingZerosLow(x) {
  const low = x & 0xffffffff; // low 32 bits (PINK_OCTAVES=16 ≤ 32)
  if (low === 0) return 32;
  return 31 - Math.clz32(low & -low);
}

// -------------------------------------------------------------------------
// TPDF dither / PCM quantization
// (faithful port of org.edgo.audio.measure.sound.PcmQuantizer)
// -------------------------------------------------------------------------

/**
 * TPDF (triangular-PDF) dither noise in the normalized −1…+1 sample domain, as
 * PcmQuantizer.tpdfNoise: (u1 − u2) / 2^(ditherBits−1) for two independent
 * uniform [0,1) draws, or exactly 0 when ditherBits ≤ 0. The ±1 LSB amplitude
 * is set by the dither bit count, not the target bit depth. ditherBits may be
 * fractional — Math.pow(2, bits−1) equals the old 1<<(bits−1) for whole bits and
 * interpolates the ±1 LSB amplitude continuously in between.
 * @param {number} ditherBits TPDF dither depth in bits, may be fractional (0 = off)
 * @param {function():number} [rng=Math.random] uniform [0,1) source
 * @returns {number} dither value in the −1…+1 domain
 */
export function tpdfNoise(ditherBits, rng = Math.random) {
  const bits = ditherBits;
  if (bits <= 0) return 0.0;
  return (rng() - rng()) / Math.pow(2, bits - 1);
}

/** Clamp to [-1, 1] — PcmQuantizer.clamp. */
function clamp1(v) {
  return v > 1.0 ? 1.0 : v < -1.0 ? -1.0 : v;
}

/**
 * Quantises one continuous-domain sample (−1…+1) to a signed integer of
 * `bitDepth` resolution, applying TPDF dither immediately before rounding —
 * a faithful per-sample port of PcmQuantizer.encode. The 8-bit path scales by
 * 127; every other depth scales by 2^(bitDepth−1) − 1.
 * @param {number} sample     normalized amplitude (−1…+1)
 * @param {number} bitDepth   target bit depth (8, 16, 24, 32)
 * @param {number} [ditherBits=0] TPDF dither depth in bits (0 = off)
 * @param {function():number} [rng=Math.random] uniform [0,1) source
 * @returns {number} signed integer PCM sample
 */
export function quantizePcm(sample, bitDepth, ditherBits = 0, rng = Math.random) {
  const v = clamp1(sample + tpdfNoise(ditherBits, rng));
  if (bitDepth === 8) return Math.round(v * 127.0);
  const maxVal = Math.pow(2, bitDepth - 1) - 1;
  return Math.round(v * maxVal);
}

/**
 * The output-lane gate — which physical DAC lane(s) carry the tone. Faithful to
 * PcmQuantizer.encode / SignalFileExporter.fillBuffer: the left lane is driven
 * unless the gate is 'RIGHT', the right lane unless the gate is 'LEFT'; the
 * un-selected lane is written as digital silence. Returns the two booleans so a
 * hot caller (the DDS worklet) can hoist them once per block, allocation-free.
 * @param {string} outputChannels 'BOTH' | 'LEFT' | 'RIGHT'
 * @returns {{wantL: boolean, wantR: boolean}}
 */
export function outputLaneGate(outputChannels) {
  return { wantL: outputChannels !== 'RIGHT', wantR: outputChannels !== 'LEFT' };
}

/**
 * One continuous-domain sample → the two interleaved lanes [left, right] through
 * the gate and the per-lane right-scale, matching PcmQuantizer.encode: the left
 * lane scales by 1.0 (the mono/left full-scale is the amplitude reference), the
 * right lane by {@code rightLaneScale} (= fsLeft/fsRight, so a LINKED card with
 * distinct DAC full-scales emits the same physical level on both lanes); a
 * gated-off lane is digital zero. With gate 'BOTH' and rightLaneScale 1.0 both
 * lanes carry the identical sample (pre-feature behaviour). Non-hot-path
 * convenience (file export, tests) — the worklet inlines the hoisted gate.
 * @param {number} sample
 * @param {string} outputChannels 'BOTH' | 'LEFT' | 'RIGHT'
 * @param {number} rightLaneScale
 * @returns {[number, number]}
 */
export function outputLaneSamples(sample, outputChannels, rightLaneScale) {
  const { wantL, wantR } = outputLaneGate(outputChannels);
  return [wantL ? sample : 0, wantR ? sample * rightLaneScale : 0];
}
