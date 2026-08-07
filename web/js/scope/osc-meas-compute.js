/*
 * Phonalyser web - the scope measurement STREAMING engine, as a pure (DOM-free) module.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of gui/scope/ScopeMeasurementWorker's compute thread as a GAPLESS stream
 * processor. Its consumer (the client, on the main thread) reads every captured sample
 * exactly once from a forward SignalBufferReader and feed()s the contiguous batches here;
 * this engine carries the per-channel HF-LPF / median-despike + mains-comb filter state
 * batch to batch (never resetting the adaptive cancellers - a running absPos gives the
 * phase-locked cancellers their absStart deltas, exactly the resurrected _streamFilterGap
 * mechanics), appends the filtered samples (and a parallel RAW pre-comb copy) into a
 * rolling collection window of length oscMeasurementAverageSeconds·sampleRate, and on
 * publish() measures the whole collection window: frequencies, whole-carrier-/whole-beat-
 * period Vmean/Vrms anchored at the window END, Vpp, and the single-tone time fields.
 *
 * This module is DOM-free so BOTH the real Web Worker (osc-meas-worker.js) and the node
 * tests drive the exact same pipeline synchronously. It owns its per-channel filter-state
 * bags - the adaptive mains cancellers are stateful and their learned state must live
 * where the pipeline runs (the worker thread) - keyed 'L'/'R', exactly like the Java
 * worker keeps measLeft/measRight etc.
 */
import { compute, withoutTimes, withFrequency, withDualTones,
         refineFrequencyAround } from './signal-measurements.js';
import { mainsFilterOf } from '../dsp/mains/factory.js';

// Notch −3 dB width (Hz) - matches the display-side combs (Java MAINS_NOTCH_BW_HZ).
const MAINS_NOTCH_BW_HZ = 2.0;
// Half-width (Hz) of the raw-signal band used to re-pin the comb-located tone's
// frequency (Java ScopeMeasurementWorker.FREQ_REFINE_HALF_HZ). Also the dual-tone
// per-tone refine band around the (snapped) emitted seeds - the LONG raw window gives
// the residual the sub-mHz precision it needs.
const FREQ_REFINE_HALF_HZ = 2.0;
// Retrack the mains canceller at most this often (Java MAINS_TRACK_PERIOD ~200 ms).
const MAINS_TRACK_PERIOD_MS = 200;
// Default measurement-average window (s) when the client hasn't posted the pref yet
// (Java Preferences.oscMeasurementAverageSeconds default).
const DEFAULT_AVG_SECONDS = 5.0;
// Upper bound (samples) on the span the per-tick FREQUENCY work runs over - the compute
// crossing/Goertzel pass, the dual-tone per-tone refines, and the single-tone raw re-pin
// (Java ScopeMeasurementWorker.MEAS_MAX_SAMPLES = 96000: measN = min(count, 96000)). A
// Goertzel refine is O(span) × ~220 probes, so scanning the FULL prefs window (up to 5 s =
// ~1M samples at 192 k) costs ~2 s/tick and stalls the 100 ms publish cadence. The frequency
// is fully determined by the most-recent ≤96000 samples; the FULL prefs window is used ONLY
// for the whole-period Vmean/Vrms integration (the web's long-average enhancement over Java).
const MEAS_MAX_SAMPLES = 96000;

/**
 * The scope measurement pipeline for both channels, owning the per-channel mains-canceller
 * state (a stateful adaptive filter must persist across batches) AND the rolling
 * filtered/raw collection windows. Mirrors ScopeMeasurementWorker's measLeft/measRight, the
 * sample-rate rebuild gates, and the per-channel rolling sample collection the whole-period
 * statistics integrate over.
 *
 * MEASUREMENTS ARE NOT HF-FILTERED (Java has no applyHfLowPass / applyChannelHf in
 * ScopeMeasurementWorker). The display LPF / de-spike is a DISPLAY setting and lives in
 * scope-view alone: running it over the measured window inflated Vpp by ~12 % and rise times
 * by ~25 %, i.e. the instrument reported the filter instead of the signal. The mains comb
 * stays - it removes a known interferer, not the signal's own band.
 */
export class OscMeasCompute {
  constructor() {
    // Per-channel streaming-state bags, keyed 'L'/'R'. Each holds one persistent mains
    // canceller (never reset - the gaps are contiguous), a running absPos for the
    // phase-locked cancellers, the rolling filtered collection buffer + the parallel RAW
    // (pre-comb) collection buffer used for the frequency refinement (Java refines on raw:
    // src = raw ?: buf), and the derived window sizing.
    this._st = {
      L: this._newState(),
      R: this._newState(),
    };
    // Latest off-thread broad-band frequency (Java asyncFreqLeft/Right); folded in when
    // the cheap crossing search returns NaN. The client supplies fresh values via setAsyncFreq.
    this._asyncFreq = { L: NaN, R: NaN };
    // Scratch reused by measureWindow (one-shot fallback) so the sync path allocates nothing
    // per call once warm.
    this._oneShot = {
      L: { scratch: null, raw: null, tail: null, mains: null, mainsMode: null, mainsRate: 0, trackT0: 0 },
      R: { scratch: null, raw: null, tail: null, mains: null, mainsMode: null, mainsRate: 0, trackT0: 0 },
    };
    this._nowMs = () => (typeof performance !== 'undefined' ? performance.now() : Date.now());
  }

  _newState() {
    return {
      mains: null, mainsMode: null, mainsRate: 0, trackT0: 0,
      absPos: 0,
      // Rolling collection: filtered (post-comb) + raw (pre-comb) parallel copies, a
      // circular fill head + a resident count, and the current window capacity in samples.
      collect: null, collectRaw: null, head: 0, size: 0, cap: 0,
      sampleRate: 0, avgSamples: 0,
      // Per-feed filtered/raw scratch (grows to the largest batch seen).
      scratch: null,
    };
  }

  /** Publishes the latest off-thread broad-band scan result for a channel (Java
   *  asyncFreqLeft/Right assignment from the osc-freq-scan thread). */
  setAsyncFreq(ch, hz) { this._asyncFreq[ch] = hz; }

  /** Drops ALL per-channel streaming state (filters, running absPos, collection
   *  windows) so the stream restarts clean after an OVERRUN / channel switch / reset
   *  (Java onCaptureOverrun + clearHistory). */
  resetStream() {
    this._st.L = this._newState();
    this._st.R = this._newState();
    this._asyncFreq = { L: NaN, R: NaN };
  }

  /** (Re)sizes a channel's rolling collection window for the live sample rate + the
   *  measurement-average-seconds pref. Growing preserves the resident samples (copied
   *  head-aligned); a sample-rate change or shrink restarts the collection empty. */
  _ensureCollection(st, sampleRate, avgSeconds) {
    const avgSamples = Math.max(1, Math.round(Math.max(0.001, avgSeconds) * sampleRate));
    if (st.collect && st.sampleRate === sampleRate && st.avgSamples === avgSamples) return;
    const rateChanged = st.sampleRate !== sampleRate;
    // Linearise the current contents newest-last before resizing.
    let prevFilt = null, prevRaw = null, prevN = 0;
    if (st.collect && !rateChanged) {
      prevN = st.size;
      prevFilt = new Float32Array(prevN);
      prevRaw = new Float32Array(prevN);
      for (let i = 0; i < prevN; i++) {
        const idx = (st.head - prevN + i + st.cap) % st.cap;
        prevFilt[i] = st.collect[idx];
        prevRaw[i] = st.collectRaw[idx];
      }
    }
    st.collect = new Float32Array(avgSamples);
    st.collectRaw = new Float32Array(avgSamples);
    st.head = 0; st.size = 0; st.cap = avgSamples;
    st.sampleRate = sampleRate; st.avgSamples = avgSamples;
    if (prevFilt) {
      const keep = Math.min(prevN, avgSamples);
      for (let i = prevN - keep; i < prevN; i++) {
        st.collect[st.head] = prevFilt[i];
        st.collectRaw[st.head] = prevRaw[i];
        st.head = (st.head + 1) % st.cap;
        if (st.size < st.cap) st.size++;
      }
    }
  }

  /**
   * Streaming feed: applies the channel's PERSISTENT mains comb (state carried batch to
   * batch, running absPos for the phase-locked cancellers - NO reset, the resurrected
   * _streamFilterGap mechanics) to a contiguous batch of raw samples, then appends both the
   * combed and the RAW (pre-comb) samples into the channel's rolling collection window
   * (oldest samples drop off). NO HF filtering: the display LPF / de-spike is a display
   * setting and never touches a measured value (see the class comment).
   *
   * @param {'L'|'R'} ch
   * @param {Float32Array} samples  the channel's raw batch (contiguous, gapless)
   * @param {number} n              valid length of `samples`
   * @param {number} sampleRate
   * @param {object} opts           { mainsMode, avgSeconds }
   */
  feed(ch, samples, n, sampleRate, opts) {
    if (!(n > 0) || !(sampleRate > 0)) return;
    const st = this._st[ch];
    const mainsMode = opts.mainsMode || 'NONE';
    const avgSeconds = opts.avgSeconds > 0 ? opts.avgSeconds : DEFAULT_AVG_SECONDS;
    this._ensureCollection(st, sampleRate, avgSeconds);

    // Combed working copy (mains applied in place); the RAW batch stays in `samples` so we
    // can append the pre-comb signal in parallel.
    let out = st.scratch;
    if (!out || out.length < n) { out = new Float32Array(n); st.scratch = out; }
    out.set(samples.subarray(0, n));

    // --- mains-hum suppression, PERSISTENT canceller over the contiguous stream ---
    if (mainsMode !== 'NONE') {
      if (!st.mains || st.mainsRate !== sampleRate || st.mainsMode !== mainsMode) {
        st.mains = mainsFilterOf(mainsMode, sampleRate, MAINS_NOTCH_BW_HZ);
        st.mainsRate = sampleRate; st.mainsMode = mainsMode; st.trackT0 = 0;
      }
      const now = this._nowMs();
      if ((now - st.trackT0) >= MAINS_TRACK_PERIOD_MS || !st.mains.isTuned()) {
        st.mains.track(out, n); st.trackT0 = now;
      }
      // NO reset -> continuous canceller; the running absPos gives the phase-locked
      // cancellers their absStart deltas across the contiguous gaps.
      st.mains.processPreservingDc(out, n, st.absPos);
    } else {
      st.mains = null; st.mainsMode = null;
    }
    st.absPos += n;

    // --- append filtered + raw into the rolling collection window ---
    for (let i = 0; i < n; i++) {
      st.collect[st.head] = out[i];
      st.collectRaw[st.head] = samples[i];
      st.head = (st.head + 1) % st.cap;
      if (st.size < st.cap) st.size++;
    }
  }

  /** True once a channel has enough resident samples to measure. */
  hasData(ch) { return this._st[ch].size >= 64; }

  /** The channel's RAW (pre-comb) collection window, linearised oldest-first, for the
   *  off-thread broad-band weak-signal frequency scan (the scan runs on the raw signal,
   *  free of the comb's notch bias). Returns null when the channel has too little data. */
  rawWindow(ch) {
    const st = this._st[ch];
    if (st.size < 64) return null;
    return this._linearise(st, st.collectRaw, st.size, true);
  }

  /**
   * Publishes the current measurement for ONE channel over its rolling collection
   * window (Java ScopeMeasurementWorker.measureChannel + the whole-period statistics):
   * whole-carrier-/whole-beat-period Vmean/Vrms anchored at the window END, Vpp over the
   * whole window, single-tone frequency (crossing + weak-signal async fallback + raw-band
   * re-pin) OR the dual-tone tone pair refined on the LONG raw window, and the single-tone
   * time fields (cleared for dual). Returns the SignalMeasurements, or null when the
   * channel has too little data yet.
   *
   * @param {'L'|'R'} ch
   * @param {number} sampleRate
   * @param {number} peakVolts
   * @param {object} opts   { mainsMode, dual, f1Hz, f2Hz }
   * @returns {object|null}
   */
  publish(ch, sampleRate, peakVolts, opts) {
    const st = this._st[ch];
    const n = st.size;
    if (n < 64) return null;
    // Linearise the rolling filtered + raw windows oldest-first so the statistics
    // integrate whole periods anchored at the window END (newest sample last).
    const filt = this._linearise(st, st.collect, n);
    const raw = this._linearise(st, st.collectRaw, n, true);
    return this._extract(ch, filt, raw, n, sampleRate, peakVolts, opts.mainsMode || 'NONE',
                         !!opts.dual, opts.f1Hz, opts.f2Hz);
  }

  /** Linearises a channel's circular collection buffer into a reused scratch, oldest
   *  sample first. `wantRaw` selects the second scratch slot so filt + raw don't clash. */
  _linearise(st, ring, n, wantRaw = false) {
    const key = wantRaw ? '_linRaw' : '_linFilt';
    let out = st[key];
    if (!out || out.length < n) { out = new Float32Array(n); st[key] = out; }
    for (let i = 0; i < n; i++) out[i] = ring[(st.head - n + i + st.cap) % st.cap];
    return out;
  }

  /**
   * One-shot fallback: runs the SAME measurement pipeline over ONE already-captured raw
   * window (the view's synchronous displayed-window measurement, used when no live worker
   * stream is publishing - e.g. the node tests / a directly-injected measurement window).
   * Combs the window with a per-call-reset mains canceller (a single standalone window, not a
   * stream), then measures it exactly as publish() does - and, exactly as publish() does, with
   * no HF filtering at all.
   *
   * @param {'L'|'R'} ch
   * @param {Float32Array} rawWin  the channel's raw (unfiltered) window samples
   * @param {number} n             valid length of rawWin
   * @param {number} sampleRate
   * @param {number} peakVolts
   * @param {number} absStart      absolute index of rawWin[0] (mains phase)
   * @param {object} opts          { mainsMode, dual, f1Hz, f2Hz }
   * @returns {object|null}
   */
  measureWindow(ch, rawWin, n, sampleRate, peakVolts, absStart, opts) {
    if (n < 64) return null;
    const { mainsMode, dual, f1Hz, f2Hz } = opts;
    const os = this._oneShot[ch];
    let filt = os.scratch;
    if (!filt || filt.length < n) { filt = new Float32Array(n); os.scratch = filt; }
    filt.set(rawWin.subarray(0, n));

    if (mainsMode && mainsMode !== 'NONE') {
      if (!os.mains || os.mainsRate !== sampleRate || os.mainsMode !== mainsMode) {
        os.mains = mainsFilterOf(mainsMode, sampleRate, MAINS_NOTCH_BW_HZ);
        os.mainsRate = sampleRate; os.mainsMode = mainsMode; os.trackT0 = 0;
      }
      const now = this._nowMs();
      if ((now - os.trackT0) >= MAINS_TRACK_PERIOD_MS || !os.mains.isTuned()) {
        os.mains.track(filt, n); os.trackT0 = now;
      }
      if (mainsMode === 'IIR_COMB') os.mains.reset();
      os.mains.processPreservingDc(filt, n, absStart);
    } else {
      os.mains = null; os.mainsMode = null;
    }
    // measRaw IS the raw window (untouched by the filters) in every configuration
    // (Java src = raw != null ? raw : buf).
    return this._extract(ch, filt, rawWin, n, sampleRate, peakVolts, mainsMode || 'NONE',
                         !!dual, f1Hz, f2Hz);
  }

  /**
   * Shared measurement extraction over an already-filtered window `filt` (length `n`)
   * plus its RAW (pre-comb) counterpart `raw`. Runs the comb-settle tail trim, the
   * whole-period compute (for Vpp + single-tone frequency/times), the weak-signal
   * async-frequency fallback + raw-band re-pin (single tone) or the dual-tone tone-pair
   * refine (dual), then re-bounds Vmean/Vrms over the LARGEST WHOLE NUMBER OF PERIODS that
   * fits the window ANCHORED AT ITS END - single: whole carrier periods (1/f); dual: whole
   * reconstructed-beat periods (1/|f1−f2|).
   */
  _extract(ch, filt, raw, n, sampleRate, peakVolts, mainsMode, dual, f1Hz, f2Hz) {
    // Comb-settle tail trim (Java measureChannel IIR_COMB branch): the comb's delay
    // lines start zeroed each pass, so its head is an un-suppressed pass-through that
    // would skew Vpp/Vrms - measure the settled TAIL (≈3 time-constants in, capped so at
    // least half remains). This trims the (oldest) head of the collection window.
    let data = filt, mN = n;
    if (mainsMode === 'IIR_COMB') {
      const settle = Math.trunc(3.0 * sampleRate / (Math.PI * MAINS_NOTCH_BW_HZ));
      const from = Math.min(settle, Math.trunc(n / 2));
      if (from > 0) { data = filt.subarray(from); mN = n - from; }
    }

    // Bound the FREQUENCY work to the most-recent ≤MEAS_MAX_SAMPLES samples (Java measN =
    // min(count, 96000)). The Goertzel refines are O(span); scanning the whole prefs window
    // stalls the publish cadence. Vpp + the single-tone frequency come off this bounded tail;
    // the whole-period Vmean/Vrms below still integrates the FULL window (data/mN).
    const measN = Math.min(mN, MEAS_MAX_SAMPLES);
    const measData = measN < mN ? data.subarray(mN - measN) : data;
    const rawN = Math.min(n, MEAS_MAX_SAMPLES);
    const rawTail = rawN < n ? raw.subarray(n - rawN) : raw;

    // Vpp over the bounded tail + the single-tone frequency/times (fast: the broad-band scan
    // runs off-thread). compute()'s own Vmean/Vrms are superseded below by the end-anchored
    // whole-period integration over the FULL window.
    let meas = compute(measData, measN, sampleRate, peakVolts, false);
    let wp = null;   // { mean, rms } over the largest whole-period, end-anchored window

    if (dual) {
      // Two simultaneous fundamentals - clear the single-value time fields (Java
      // withoutTimes), then refine BOTH tones over the bounded RAW tail from the emitted
      // (snap-aware) seeds ±FREQ_REFINE_HALF_HZ. The raw window is free of the comb's notch
      // bias; ≤96000 samples (≥0.5 s) already resolves the seeds to sub-mHz.
      meas = withoutTimes(meas);
      const src = rawTail;              // Java src = raw != null ? raw : buf (bounded, measN)
      let r1 = NaN, r2 = NaN;
      if (f1Hz > 0) {
        const refined = refineFrequencyAround(src, rawN, sampleRate, f1Hz, FREQ_REFINE_HALF_HZ);
        if (Number.isFinite(refined)) r1 = refined;
      }
      if (f2Hz > 0) {
        const refined = refineFrequencyAround(src, rawN, sampleRate, f2Hz, FREQ_REFINE_HALF_HZ);
        if (Number.isFinite(refined)) r2 = refined;
      }
      meas = withDualTones(meas, r1, r2);
      // Whole-period Vmean/Vrms over the RECONSTRUCTED BEAT (spec §4b: 1/|f1−f2| from the
      // refined captured pair). DEVIATION (correctness, preserving §4b's whole-period,
      // end-anchored intent): a window that is a whole number of |f1−f2| beat periods only
      // nulls the two carriers when |f1−f2| divides BOTH tone frequencies - which it does
      // NOT for a general bin-snapped pair (e.g. the spec's 9999.023/11000.977: |f1−f2| =
      // 342·bin but f1 = 3413·bin, so whole-beat integration leaves a ~3 µV per-tone
      // residual, far over the "<1 µV" target). The correct whole-period null is the
      // window that is SIMULTANEOUSLY a whole number of BOTH tones' periods - the true
      // signal fundamental. So take the largest end-anchored window that is whole f1
      // periods AND lands within a small fraction of a whole f2 period (which recurs on the
      // beat, so the search is short). Falls back to whole f1 periods alone otherwise.
      if (Number.isFinite(r1) && Number.isFinite(r2) && r1 > 0 && r2 > 0) {
        wp = this._dualWholePeriodMeanRms(data, mN, sampleRate, r1, r2);
      }
    } else {
      if (Number.isNaN(meas.frequency)) {
        // Weak / noisy tone: fold in the latest off-thread broad-band scan (Java worker).
        const async = this._asyncFreq[ch];
        if (Number.isFinite(async)) meas = withFrequency(meas, async);
      }
      if (mainsMode !== 'NONE' && Number.isFinite(meas.frequency)) {
        // Re-pin the comb-located tone on the RAW signal (bounded tail), free of the comb's
        // notch bias (Java two-step: the canceller finds WHICH peak is the fundamental; the
        // precise frequency comes from the raw window).
        const precise = refineFrequencyAround(rawTail, rawN, sampleRate, meas.frequency, FREQ_REFINE_HALF_HZ);
        if (Number.isFinite(precise)) meas = withFrequency(meas, precise);
      }
      // Whole CARRIER periods (1/f) - exact for a single tone (the sub-sample rounding
      // residual is second-order), end-anchored at the window's newest sample (§4b).
      if (Number.isFinite(meas.frequency) && meas.frequency > 0) {
        wp = this._wholePeriodMeanRms(data, mN, sampleRate, meas.frequency);
      }
    }

    if (wp) { meas.vmean = wp.mean * peakVolts; meas.vrms = wp.rms * peakVolts; }
    return meas;
  }

  /**
   * Mean + AC-RMS of `data[0..n)` over the LARGEST whole number of periods of frequency
   * `freqHz` that fits, ANCHORED AT THE END (the newest samples). Returns null when less
   * than one whole period fits (nothing better to integrate over than the full window,
   * which compute() already gave). The whole-period bounding removes the fractional-cycle
   * residual (amplitude-proportional, capture-phase-random) that otherwise jitters Vmean.
   */
  _wholePeriodMeanRms(data, n, sampleRate, freqHz) {
    const periodSamples = sampleRate / freqHz;
    if (!(periodSamples >= 2)) return null;
    const periods = Math.floor(n / periodSamples);
    if (periods < 1) return null;
    const win = Math.min(n, Math.round(periods * periodSamples));
    return this._meanRmsTail(data, n, win);
  }

  /**
   * Mean + AC-RMS over the largest END-ANCHORED window that is a whole number of `f1`
   * periods AND (within a small tolerance) a whole number of `f2` periods - i.e. the true
   * common fundamental of the two tones, so BOTH carriers integrate to ~0 and only the DC
   * remains. Scans whole-f1-period windows from the largest down; the f2-aligned one
   * recurs on the beat period, so the scan terminates quickly. Falls back to the largest
   * whole-f1-period window when no dual-aligned window is found.
   */
  _dualWholePeriodMeanRms(data, n, sampleRate, f1, f2) {
    const p1 = sampleRate / f1, p2 = sampleRate / f2;
    if (!(p1 >= 2) || !(p2 >= 2)) return null;
    const kMax = Math.floor(n / p1);
    if (kMax < 1) return null;
    const TOL = 0.02;   // ≤2% of an f2 period of residual phase -> sub-µV per-tone leakage
    let win = Math.min(n, Math.round(kMax * p1));   // fallback: largest whole-f1 window
    for (let k = kMax; k >= 1; k--) {
      const w = Math.round(k * p1);
      if (w < 2 || w > n) continue;
      const c2 = w / p2;
      if (Math.abs(c2 - Math.round(c2)) < TOL) { win = w; break; }
    }
    return this._meanRmsTail(data, n, win);
  }

  /** Mean + AC-RMS of the last `win` samples of `data[0..n)` (end-anchored). */
  _meanRmsTail(data, n, win) {
    if (!(win >= 2)) return null;
    const from = n - win;
    let sum = 0, sumSq = 0;
    for (let i = from; i < n; i++) { const v = data[i]; sum += v; sumSq += v * v; }
    const mean = sum / win;
    const variance = sumSq / win - mean * mean;
    return { mean, rms: Math.sqrt(Math.max(0.0, variance)) };
  }
}
