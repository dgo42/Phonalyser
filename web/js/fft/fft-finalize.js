/*
 * Phonalyser web - the DISPLAY PREPARATION stage: everything between the folded average and the
 * spectrum the view draws.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of what the desktop's FftView does on its own thread - the cumulative overlay,
 * the raw peak stamp, the loaded .frc de-embed with its held cascade, the plot-time mains comb,
 * ONE statistics recompute, and the blue "before-cal" dots - with the IMD left to the side that
 * owns the emitted-tone request.
 *
 * It is a MODULE, not worker code, and that is load-bearing: fft-finalize-worker.js is a thin
 * shell around it, and fft-worker.js imports the same module for the two cases that must stay on
 * the analysis thread (a tick with no averaging to overlay, and the fallback when a nested worker
 * cannot be built). Whichever path runs, the thread it runs on holds exactly ONE cascade.
 */
import { FftAnalyzer } from './fft-analyzer.js';
import {
  applyInverseInPlace, cascadeInverse, capturePreCorrectionPeaks, gainDbAt,
  toneBins, toneFrequencies, toneLobeSpans,
} from '../dsp/frc.js';
import { MainsCombFilter, DEFAULT_NOTCH_BANDWIDTH_HZ } from '../dsp/mains/comb-filter.js';

/** The statistics engine of this stage - its own instance so its scratch is not re-allocated. */
const finalizeAnalyzer = new FftAnalyzer();

/** The cascade of every loaded .frc row for the analysed channel, held across ticks with the keys
 *  it was built from. Dropped whole on any of its three triggers (rows changed, statistics
 *  reset, audio format changed). */
export const cal = {
  rows: [],            // [{ cal: {freqs, magLin, phaseRad}, withNoise }] for the ANALYSED channel
  inv: null,           // [invRe, invIm] per bin, or null = build on the next finalize
  toneBins: null,      // the fast-path bin list while no with-noise row is loaded
  toneKeys: false,     // true while a tone-only row is loaded
  fundBin: 0,
  f2Bin: -1,
  spans: null,
  scratchRe: null, scratchIm: null, scratchMagSq: null,   // held cascade accumulators
  // The cascade's gain per bin, in dB - what the correction takes off a level, derived from the
  // cascade above and cached beside it. The dual-tone chart draws its blue "before-cal" dots at
  // the intermod PRODUCT frequencies, which only the IMD table on the other side knows, so that
  // side is handed the gain of every bin rather than of the tones this frame located.
  gainDb: null,
};

/** Bin index standing for "this frame carries no second tone" in the cascade's keys. */
const NO_TONE_BIN = -1;
/** How far a lobe EDGE may move before the held response counts as no longer fitting. An edge
 *  is where the lobe meets the noise, so a stale edge inside this corrects grass and moves no
 *  reading; the peak bin and the second fundamental's bin stay exact. */
const SPAN_EDGE_TOLERANCE_BINS = 4;

/** The plot-time mains comb (IIR_COMB mode): the analysis side TRACKS the mains fundamental on
 *  the raw window and sends it with the tick; this instance divides its response out of the shown
 *  spectrum, leaving the accumulator raw - the web's counterpart of the view's own comb. */
let finalizeComb = null;
let finalizeCombRate = 0;

const secondFundamentalBin = (r) => {
  const f2 = r.fundamental2HzRefined;
  if (Number.isNaN(f2) || !(f2 > 0.0) || !(r.fftSize > 0)) return NO_TONE_BIN;
  return Math.round(f2 / (r.sampleRate / r.fftSize));
};

/** Drops the held cascade so the next finalized frame samples the rows afresh - the loaded rows
 *  changed, the audio format was committed, or the statistics restarted (a restart moves the
 *  analysed channel and every tone position the cascade was keyed on). */
export function dropCalCascade() {
  cal.inv = null; cal.toneBins = null; cal.spans = null; cal.gainDb = null;
}

/** Replaces the loaded rows and drops what was built from the old set. */
export function setCalRows(rows) {
  cal.rows = rows || [];
  dropCalCascade();
}

/** The frame geometry a rebuild needs, or null when the held cascade still fits. ONE
 *  toneFrequencies + toneLobeSpans per finalized frame, shared by the check and the rebuild. */
export function geometryToRebuildFor(r) {
  const measure = () => {
    const tones = toneFrequencies(r);
    return { tones, spans: toneLobeSpans(r, tones) };
  };
  if (cal.inv == null) return measure();
  if (!cal.toneKeys) return null;
  if (r.fundamentalBin !== cal.fundBin) return measure();
  if (secondFundamentalBin(r) !== cal.f2Bin) return measure();
  const g = measure();
  if (cal.spans == null || g.spans.length !== cal.spans.length) return g;
  for (let t = 0; t < g.spans.length; t++) {
    const a = g.spans[t], b = cal.spans[t];
    if (a[0] !== b[0]) return g;
    if (Math.abs(a[1] - b[1]) > SPAN_EDGE_TOLERANCE_BINS) return g;
    if (Math.abs(a[2] - b[2]) > SPAN_EDGE_TOLERANCE_BINS) return g;
  }
  return null;
}

/** Samples the cascade of the loaded rows on this frame's grid and keeps it with its keys. */
export function buildCalInverse(r, tones, spans) {
  const withNoise = [];
  const toneOnly = [];
  for (const row of cal.rows) {
    if (row.withNoise) withNoise.push(row.cal); else toneOnly.push(row.cal);
  }
  const half = r.fftSize / 2;
  if (cal.scratchRe == null || cal.scratchRe.length !== half + 1) {
    cal.scratchRe = new Float64Array(half + 1);
    cal.scratchIm = new Float64Array(half + 1);
    cal.scratchMagSq = new Float64Array(half + 1);
  }
  cal.inv = cascadeInverse(withNoise, toneOnly, tones, spans, r.fftSize, r.sampleRate,
    cal.scratchRe, cal.scratchIm, cal.scratchMagSq);
  cal.toneBins = withNoise.length === 0 ? toneBins(spans, half) : null;
  cal.toneKeys = toneOnly.length > 0;
  cal.fundBin = r.fundamentalBin;
  cal.f2Bin = secondFundamentalBin(r);
  cal.spans = spans;
  cal.gainDb = null;   // derived from the cascade just replaced
}

/** The cascade's gain in dB at every bin, cached until the cascade is rebuilt. Asked for only
 *  while a dual-tone chart with a loaded calibration is drawing its blue "before-cal" dots. */
function calGainDbArray() {
  const n = cal.inv[0].length;
  if (cal.gainDb == null || cal.gainDb.length !== n) {
    const g = new Float64Array(n);
    for (let k = 0; k < n; k++) g[k] = gainDbAt(cal.inv, k);
    cal.gainDb = g;
  }
  return cal.gainDb.slice();
}

/** The dB the cascade takes off the bin nearest hz - what a post-correction level has to gain
 *  back to read as it was measured. 0 with nothing held and for a bin no row reached. */
function calGainDbAt(r, hz) {
  if (cal.inv == null || !(hz > 0.0)) return 0.0;
  return gainDbAt(cal.inv, Math.round(hz / (r.sampleRate / r.fftSize)));
}

/**
 * The bin the magnitude-to-amplitude conversion factor is read at, and the clamp is part of the
 * definition, not an accident of it (FftAccumulator.overlayOnto): the refined fundamental can
 * round past the last bin, and the factor must come from a bin that exists. Both sides of the
 * seam use THIS function, so a job's three carried values and the overlay that consumes them can
 * never be read at two different bins.
 */
export function conversionBin(r) {
  const halfSize = r.fftSize / 2;
  const k = Math.max(1, Math.round(r.fundamentalHzRefined / r.freqResolution));
  return k > halfSize ? halfSize : k;
}

/**
 * The cumulative overlay, from SUMS that were copied across a seam rather than from an
 * accumulator this thread owns - the pipeline's half of FftAccumulator.overlayOnto, arithmetic
 * for arithmetic. The accumulator itself never leaves the analysis thread: it is being written by
 * the next tick's fold while this runs, so a shared view would tear the spectrum.
 *
 * @param r the result whose re / im / amplitudeDbFs / phaseDeg are rewritten in place.
 * @param sums {re, im, pow, frames, coherent, convMag, convAmp} - the accumulator's half+1 sums,
 *   its depth, and the conversion pair read at conversionBin BEFORE the overlay.
 */
export function overlayFromSums(r, sums) {
  if (!sums || !(sums.frames > 0)) return;
  const halfSize = r.fftSize / 2;
  const N = halfSize + 1;
  if (!(sums.convMag > 1e-30) || !(sums.convAmp > 1e-30)) return;
  const conv = sums.convAmp / sums.convMag;
  const frames = sums.frames;
  if (sums.coherent) {
    const aRe = sums.re, aIm = sums.im;
    for (let k = 0; k < N; k++) {
      const avgRe = aRe[k] / frames;
      const avgIm = aIm[k] / frames;
      r.re[k] = avgRe;
      r.im[k] = avgIm;
      const mag = Math.sqrt(avgRe * avgRe + avgIm * avgIm);
      const scale = (k === 0 || k === halfSize) ? 0.5 : 1.0;
      const amp = mag * conv * scale;
      r.amplitudeDbFs[k] = amp > 1e-15 ? 20.0 * Math.log10(amp) : -300.0;
      r.phaseDeg[k] = (Math.atan2(avgIm, avgRe)) * (180.0 / Math.PI);
    }
  } else {
    const aPow = sums.pow;
    for (let k = 0; k < N; k++) {
      const avgPow = aPow[k] / frames;
      const mag = Math.sqrt(avgPow);
      const scale = (k === 0 || k === halfSize) ? 0.5 : 1.0;
      const amp = mag * conv * scale;
      r.amplitudeDbFs[k] = amp > 1e-15 ? 20.0 * Math.log10(amp) : -300.0;
      r.re[k] = amp;
      r.im[k] = 0.0;
      r.phaseDeg[k] = 0.0;
    }
  }
}

/** The arrays a job carries in their own slots, or that the display rebuild refills itself - so
 *  the scalar sweep below can take EVERYTHING else without naming it. */
const JOB_ARRAY_FIELDS = [
  'amplitudeDbFs', 'phaseDeg', 're', 'im',
  'harmonicBins', 'harmonicHz', 'harmonicDbFs', 'harmonicPct',
  'imdProductA', 'imdProductB', 'imdProductBin',
  'rawPeakRe', 'rawPeakIm', 'preCorrectionPeaks',
];

/**
 * The job one accumulated tick hands to the display preparation.
 *
 * The scalars are swept GENERICALLY - every own value of the result that is a number, a boolean,
 * a string or null - rather than listed by hand. A hand-written list is a list that goes stale:
 * the first version of this seam omitted fftSize and harmonicCount, and a result assembled without
 * fftSize has a half-spectrum of zero bins, so the overlay wrote one bin, the statistics were
 * recomputed over nothing and the view drew an empty trace while its counter, stamped elsewhere,
 * went on counting. Nothing about that failure was visible in the arrays the seam did carry.
 *
 * The four large spectral arrays are NOT carried: the overlay overwrites all four from the sums.
 * The accumulator's sums ARE copied, because the next tick's fold writes them while this frame is
 * being prepared and a shared view would tear the spectrum.
 *
 * @param r the analysed tick.
 * @param d {mainsF0, wantCalGain} - what the tick asked for.
 * @param ids {id, epoch, reemit}.
 * @param accum the accumulator whose sums are copied (it stays where it is).
 * @returns the job, or null when there is no average behind the tick to send.
 */
export function buildFinalizeJob(r, d, ids, accum) {
  const coherent = accum.isCoherent;
  if ((coherent ? accum.sumRe : accum.sumPow) == null) return null;
  const half = r.fftSize / 2;
  const kFund = conversionBin(r);
  const fields = {};
  for (const key of Object.keys(r)) {
    if (JOB_ARRAY_FIELDS.indexOf(key) >= 0) continue;
    const v = r[key];
    if (v === null || typeof v === 'number' || typeof v === 'boolean' || typeof v === 'string') {
      fields[key] = v;
    }
  }
  return {
    id: ids.id, epoch: ids.epoch, reemit: !!ids.reemit,
    mainsF0: d.mainsF0, wantCalGain: !!d.wantCalGain,
    fields,
    sums: {
      coherent,
      frames: accum.accumFrames,
      re: coherent ? accum.sumRe.slice(0, half + 1) : null,
      im: coherent ? accum.sumIm.slice(0, half + 1) : null,
      pow: coherent ? null : accum.sumPow.slice(0, half + 1),
      convMag: Math.hypot(r.re[kFund], r.im[kFund]),
      convAmp: r.amplitudeDbFs[kFund] > -290.0
        ? Math.pow(10.0, r.amplitudeDbFs[kFund] / 20.0) : 0.0,
    },
    harmonicBins: r.harmonicBins.slice(), harmonicHz: r.harmonicHz.slice(),
    harmonicDbFs: r.harmonicDbFs.slice(), harmonicPct: r.harmonicPct.slice(),
    imdProductA: r.imdProductA ? r.imdProductA.slice() : null,
    imdProductB: r.imdProductB ? r.imdProductB.slice() : null,
    imdProductBin: r.imdProductBin ? r.imdProductBin.slice() : null,
  };
}

/** The buffers a job owns, for the transfer list - it is built here so a caller cannot forget one. */
export function finalizeJobTransfers(job) {
  const out = [];
  for (const a of [job.sums.re, job.sums.im, job.sums.pow, job.harmonicBins, job.harmonicHz,
    job.harmonicDbFs, job.harmonicPct]) if (a) out.push(a.buffer);
  return out;
}

/** Fills a reused result from a job, sizing its arrays for this grid. The inverse of
 *  buildFinalizeJob, and the two are asked to agree by the pipeline's own test. */
export function loadJobInto(slot, job) {
  const f = job.fields;
  slot.ensureArrays(f.fftSize / 2 + 1, f.harmonicCount);
  Object.assign(slot, f);
  slot.harmonicBins.set(job.harmonicBins);
  slot.harmonicHz.set(job.harmonicHz);
  slot.harmonicDbFs.set(job.harmonicDbFs);
  slot.harmonicPct.set(job.harmonicPct);
  slot.imdProductA = job.imdProductA;
  slot.imdProductB = job.imdProductB;
  slot.imdProductBin = job.imdProductBin;
  // Refilled by the rebuild itself - never carried over from whatever this slot last held.
  slot.preCorrectionPeaks = null;
  slot.rawPeakRe = null;
  slot.rawPeakIm = null;
}

/**
 * The transferable snapshot of the FftResult fields the view, the readout and the predistortion
 * consume - the DISPLAY half of a posted answer, assembled where the display is prepared.
 * Cloning the spectral arrays rather than transferring the caller's keeps a reused result slot
 * reusable next tick.
 *
 * Defined here because this stage is what assembles a display result, and BOTH senders need one
 * shape: the finalize worker for a pipelined frame, and the analysis worker for the two cases
 * that stay on its thread (a tick with no average to overlay, and the fallback).
 *
 * @returns {{out: object, transfer: ArrayBuffer[]}} the message and its transfer list.
 */
export function displaySnapshot(r, extra) {
  const out = Object.assign({
    coherentKappa: r.coherentKappa,
    preCorrectionPeaks: r.preCorrectionPeaks
      ? [r.preCorrectionPeaks[0].slice(), r.preCorrectionPeaks[1].slice()] : null,
    rawFundRe: r.rawFundRe, rawFundIm: r.rawFundIm,
    rawPeakRe: r.rawPeakRe ? r.rawPeakRe.slice() : null,
    rawPeakIm: r.rawPeakIm ? r.rawPeakIm.slice() : null,
    fftSize: r.fftSize, sampleRate: r.sampleRate, frameCount: r.frameCount,
    freqResolution: r.freqResolution, windowType: r.windowType, overlap: r.overlap,
    stepSamples: r.stepSamples,
    amplitudeDbFs: r.amplitudeDbFs.slice(), phaseDeg: r.phaseDeg.slice(),
    re: r.re.slice(), im: r.im.slice(),
    fundamentalBin: r.fundamentalBin, fundamentalHz: r.fundamentalHz,
    fundamentalHzRefined: r.fundamentalHzRefined,
    fundamental2HzRefined: r.fundamental2HzRefined,
    fundamentalDbFs: r.fundamentalDbFs, fundamentalLinear: r.fundamentalLinear,
    fundamentalTrueDbFs: r.fundamentalTrueDbFs,
    fundamentalDynExclusionHz: r.fundamentalDynExclusionHz,
    harmonicCount: r.harmonicCount,
    harmonicBins: r.harmonicBins.slice(), harmonicHz: r.harmonicHz.slice(),
    harmonicDbFs: r.harmonicDbFs.slice(), harmonicPct: r.harmonicPct.slice(),
    thdPct: r.thdPct, thdDb: r.thdDb, thdNDb: r.thdNDb,
    snrDb: r.snrDb, sinadDb: r.sinadDb,
    awThdNDb: r.awThdNDb, enobSinadDb: r.enobSinadDb,
    snrFreqMin: r.snrFreqMin, snrFreqMax: r.snrFreqMax,
    coherentAveraging: r.coherentAveraging,
    noisePower: r.noisePower, windowNenbwBins: r.windowNenbwBins,
    avgNoiseFloorDbFs: r.avgNoiseFloorDbFs,
    imdProductA: r.imdProductA ? r.imdProductA.slice() : null,
    imdProductB: r.imdProductB ? r.imdProductB.slice() : null,
    imdProductBin: r.imdProductBin ? r.imdProductBin.slice() : null,
  }, extra || {});
  const transfer = [
    out.amplitudeDbFs.buffer, out.phaseDeg.buffer, out.re.buffer, out.im.buffer,
    out.harmonicBins.buffer, out.harmonicHz.buffer, out.harmonicDbFs.buffer, out.harmonicPct.buffer,
  ];
  if (out.calGainDb) transfer.push(out.calGainDb.buffer);
  return { out, transfer };
}

/**
 * The display rebuild, in the web's transform order: the cumulative overlay, the raw peak stamp,
 * the .frc de-embed, the plot-time mains comb, ONE statistics recompute gated as the desktop
 * gates it, then the accumulated frame's blue dots off the cascade's own gain.
 *
 * @param r the result to rebuild in place.
 * @param d {mainsF0, wantCalGain} - what the tick asked for.
 * @param overlay a function that performs the cumulative overlay onto r, or null for a tick with
 *   no average behind it (averaging off), whose own spectrum is what is displayed. Passing it in
 *   is what lets the same stage run beside the accumulator and across a seam from it.
 * @returns the per-bin gain the dual-tone chart asked for, or null.
 */
export function finalizeForDisplay(r, d, overlay) {
  const accumulated = typeof overlay === 'function';
  if (accumulated) overlay(r);
  // The RAW (pre-de-embed) phasors the DAC-predistortion reads, stamped before any bin moves.
  r.captureRawPeaks();
  const calibrated = cal.rows.length > 0;
  let correctedBins = 0;
  if (calibrated) {
    r.preCorrectionPeaks = capturePreCorrectionPeaks(r);
    const g = geometryToRebuildFor(r);
    if (g != null) buildCalInverse(r, g.tones, g.spans);
    correctedBins = applyInverseInPlace(r, cal.inv, cal.toneBins);
  }
  let mainsApplied = false;
  if (d.mainsF0 > 0 && r.freqResolution > 0) {
    if (!finalizeComb || finalizeCombRate !== r.sampleRate) {
      finalizeComb = new MainsCombFilter(r.sampleRate, DEFAULT_NOTCH_BANDWIDTH_HZ);
      finalizeCombRate = r.sampleRate;
    }
    finalizeComb.applySpectrumCorrection(r.amplitudeDbFs, null, r.freqResolution, d.mainsF0);
    mainsApplied = true;
  }
  if (accumulated || mainsApplied || correctedBins > 0) finalizeAnalyzer.recomputeStats(r);
  if (calibrated && accumulated && Number.isFinite(r.coherentKappa)) {
    const hc = r.harmonicCount;
    const preFreqs = new Float64Array(1 + hc);
    const preDbFs = new Float64Array(1 + hc);
    const stableFundHz = r.coherentKappa * r.freqResolution;
    preFreqs[0] = r.fundamentalHz;
    preDbFs[0] = r.fundamentalDbFs + calGainDbAt(r, stableFundHz);
    for (let h = 0; h < hc; h++) {
      if (r.harmonicBins[h] > 0) {
        preFreqs[1 + h] = r.harmonicHz[h];
        preDbFs[1 + h] = r.harmonicDbFs[h] + calGainDbAt(r, stableFundHz * (h + 2));
      }
    }
    r.preCorrectionPeaks = [preFreqs, preDbFs];
  }
  return (d.wantCalGain && cal.inv != null) ? calGainDbArray() : null;
}
