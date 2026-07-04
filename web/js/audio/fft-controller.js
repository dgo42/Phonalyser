/*
 * Phonalyser web — the FFT analysis capture consumer.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of gui/fft/FftController. The contiguous-cursor consumer of the shared capture:
 * per batch it pulls the new R-channel (ch1) samples, runs the buffer-and-analyze accumulate +
 * cross-frame coherent-averaging loop through the FFT worker / parallel pool (the faithful brain,
 * off the main thread), steers the generator via the frequency-lock loop, and honours stop-after-N.
 * The worker ACCUMULATOR stays RAW; the render-time .frc de-embed + mains comb correction run on a
 * per-frame COPY before emit (these move to the view layer, fft-view-correction.js, in a later step).
 * Reads + steers the generator's snapped/FLL state through delegating accessors (it does not own it).
 */
import { FftAnalyzer } from '../fft/fft-analyzer.js';
import { FftResult } from '../fft/fft-result.js';
import { FftAccumulator } from '../fft/fft-accumulator.js';
import { isDualTone } from '../generator/dds-kernel.js';
import { OVERRUN } from './signal-buffer-reader.js';
import { MainsCombFilter, DEFAULT_NOTCH_BANDWIDTH_HZ } from '../dsp/mains/comb-filter.js';
import { mainsFilterOf } from '../dsp/mains/factory.js';
import { MessageBus } from '../bus/message-bus.js';
import { Events, GenChangeCause } from '../bus/events.js';
import { FrequencyFll } from '../dsp/fll.js';

/** Output-pipeline drain to skip after a generator/form/frequency change, in seconds
 *  (Java OUTPUT_DRAIN_SKIP_SEC). The DAC's hardware buffer (~480 ms on the render path)
 *  keeps the OLD tone flowing into the ADC after the generator changed; a window built
 *  before it drains straddles both signals — poisoning the fresh accumulator AND feeding
 *  the FLL a smeared, plausible-looking first measurement. */
const OUTPUT_DRAIN_SKIP_SEC = 0.7;
/** Guard skipped after a discontinuity re-sync, ahead of the first fresh window (Java
 *  POST_GLITCH_SKIP_SEC): detection fires on the glitch's START, and the glitch (the
 *  observed USB gaps run 120–160 µs) plus any settling can still be in flight at
 *  "latest" — 5 ms of discarded samples puts the rebuild safely past its end. */
const POST_GLITCH_SKIP_SEC = 0.005;
/** Time-domain discontinuity gate toggle (Java FftAnalyzerWorker.USE_TIME_DISCONTINUITY):
 *  the scope's glitch detector run on the tick's raw window — a splice/dropout breaks the
 *  sinusoid recurrence decades above the noise floor even when its spectral footprint
 *  slips under the frequency-domain gates (and vice versa), so the scope trigger and the
 *  FFT rejection agree on what counts as a damaged block. The O(n) detector pass runs in
 *  fft-worker.js (which holds the raw window); this flag arms it per dispatch and gates
 *  the re-sync off the returned verdict. */
const USE_TIME_DISCONTINUITY = true;
/** FLL measurement plausibility bound, absolute floor in FFT bins (Java
 *  FLL_MAX_ERROR_BINS) — keeps the gate permissive at low target frequencies
 *  where the ppm part collapses below the spectral resolution. */
const FLL_MAX_ERROR_BINS = 5;
/** FLL measurement plausibility bound, relative part in ppm (Java
 *  FLL_MAX_ERROR_PPM) — generator-vs-ADC clock drift is ppm-scale, so 500 ppm is
 *  a generous ceiling for a REAL mistune; beyond it is a mis-measurement (a window
 *  still draining the OLD signal, a harmonic mis-lock, a glitch) that must not steer. */
const FLL_MAX_ERROR_PPM = 500;
/** FLL trace (Java DebugSwitches.TRACE_FLL): when truthy, log every steer / gate /
 *  hold so the live loop is diagnosable — are absCapStart / writePos / measuredHz
 *  finite and advancing, does the transport gate ever open, does genFreq update.
 *  Off by default (no behaviour change); enable from the console with
 *  `window.TRACE_FLL = true`. Mirrors the log.warn lines in FftController.applyFrequencyLock. */
function traceFll() { return typeof window !== 'undefined' && window.TRACE_FLL; }
/** Re-track the mains comb every Nth FFT tick (Java MAINS_TRACK_TICK_INTERVAL). */
const MAINS_TRACK_TICK_INTERVAL = 5;
/** Per-tick DISPLAY throttle (Java FftAnalyzerWorker.DISPLAY_MIN_NANOS / DISPLAY_MAX_NANOS,
 *  expressed in ms here). The cross-tick accumulator runs EVERY tick, but the O(N) display
 *  rebuild — overlayOnto + recomputeStats + FLL + emit — is gated to AT MOST one per
 *  DISPLAY_MIN_MS when caught up, and SKIPPED while the capture backlog is high so the
 *  consumer catches up instead of overrunning — but never deferred past DISPLAY_MAX_MS so
 *  the view can't freeze. This keeps the heavy O(N) finalize OFF the rAF render path so the
 *  FFT can't starve the scope to ~1 fps (#18) and a mid-capture cal load can't hang the UI
 *  (#16): the main thread is freed between display rebuilds for input + the scope render.
 *  Java: DISPLAY_MIN_NANOS = 40_000_000L (≤25 Hz refresh when keeping up),
 *  DISPLAY_MAX_NANOS = 500_000_000L (≥2 Hz even while behind). */
const DISPLAY_MIN_MS = 40;    // ≤25 Hz refresh when keeping up
const DISPLAY_MAX_MS = 500;   // ≥2 Hz even while behind
/** Maps the FftOverlap enum token to its overlap fraction (for the hop math). */
const OVERLAP_FRACTION = {
  PCT_0: 0.0, PCT_50: 0.5, PCT_75: 0.75, PCT_87_5: 0.875, PCT_93_75: 0.9375,
};

export class FftController {
  /**
   * @param capture the SharedCapture instance.
   * @param gen     the GeneratorController (read + FLL-steered, not owned).
   * @param config  the SHARED engine config object.
   * @param deps    {status} — status: (text) => void.
   */
  constructor(capture, gen, config, { status } = {}) {
    this._capture = capture;
    this._gen = gen;
    this.config = config;
    this._status = status || (() => {});
    this._fftOn = false;
    this._fftReader = null;
    this._fftReadBuf = null;
    this._fftPausedByStopN = false;
    // Samples still to discard after a signal-change re-anchor (Java drainSkipRemaining):
    // armed by the GENERATOR_SIGNAL_CHANGED subscription below, consumed in feedFft before
    // the first dispatch so the first window holds none of the old tone still draining out
    // of the DAC buffer.
    this._drainSkipRemaining = 0;
    // Main-thread analyzer: ONLY for recomputeStats (the render-time .frc de-embed re-derive).
    // All analysis — including the threads>1 parallel pool — runs inside fft-worker.js, which
    // coordinates its own nested fft-pool-worker.js siblings (Java parity: FftAnalyzerWorker
    // .parallelChunks splits chunks ON the background analyzer thread, never the UI thread).
    this._coordAnalyzer = new FftAnalyzer();
    // Cross-tick running accumulator (Java FftAnalyzerWorker accumulateIntoForeverBuffer):
    // the worker FFTs only ~1-2 fresh frames per tick; this DEEPENS the average across
    // ticks (√N SNR boost), and the displayed spectrum + stats are finalized OFF it.
    this._accum = new FftAccumulator();
    // Epoch bumped on every accumulator reset; in-flight worker results stamped with an
    // older epoch are dropped so a window straddling the reset can't poison the average.
    this._accumEpoch = 0;
    // Previous tick's averaging mode, for the mode-transition reset (Java lastAccumulate /
    // lastForeverMode / lastRingN): flip, ring↔∞ switch, or a SMALLER ring drops the depth.
    this._lastAccumulate = false;
    this._lastForeverMode = false;
    this._lastRingN = -1;
    this.worker = null;
    this.poolSize = 0;
    // Generator frequency-lock loop (Java FrequencyFll): a one-step deadbeat with an
    // exact transport gate (correctionVisibleFrom), NOT a per-frame proportional
    // integrator. The published generator frequency is snapped + correction. Reset on
    // Record start and on a user signal change (both invalidate the lock).
    this._fll = new FrequencyFll();
    // FLL display + steer state — OWNED here now (moved off GeneratorController): the corrected
    // generator frequency + lock status this loop computes and _emit reports. The steer publishes
    // GENERATOR_FREQ_TRIM; the generator applies it to its worklet.
    this.genFreq = 0;
    this.fllErrHz = 0;
    this.fllLocked = false;
    this.fllStable = 0;
    this.rejectedCount = 0;
    // Previous batch's config.fllOn, for the align OFF→ON transition (Java
    // FftView.java:465-466: "Selecting an active alignment mode (PID / FLL) resets its
    // loop so each session converges fresh; NONE deliberately resets nothing").
    this._lastFllOn = false;
    // Absolute capture position (in samples) of the next sample to enter the analysis
    // ring — the timebase the FLL transport gate compares window starts against. Set
    // from the reader's readPos on every (re)anchor; advanced as samples are buffered.
    this._absNextSample = 0;
    // Analysis TICKS since the last reset (Java completedAnalyses — incremented once
    // per accepted tick). This is what stop-after-N and the predistortion host key
    // off; it is NOT the accumulator frame depth (kept separately in _analysesDone
    // for the predistortion frame-depth readout / the on-screen "N×" count).
    this._analysesTicks = 0;
    // Cross-tick accumulator frame depth since the last reset (Java getAccumulatedFrames):
    // the predistortion frame-depth readout + the displayed "N×" depth, NOT stop-after-N.
    this._analysesDone = 0;
    // Mains suppression: IIR_COMB tracks the live mains fundamental and divides the comb response
    // out of the spectrum at PLOT time (the worker accumulator stays raw).
    this._fftMains = null;
    this._fftMainsRate = 0;
    this._fftMainsF0 = 0;         // last tracked mains fundamental (Hz); 0 = unlocked
    this._fftMainsTick = 0;       // re-track cadence counter (shared by both branches)
    // SYNC_SUBTRACT / LMS pre-FFT time-domain filter (Java FftAnalyzerWorker
    // mainsTimeFilter / mainsTimeFilterMode / mainsTimeFilterSampleRate): built by
    // mainsFilterOf, recreated when the mode or sample rate changes, state PERSISTENT
    // across windows (the absStart delta keeps the phase-locked template aligned).
    this._fftTimeFilter = null;
    this._fftTimeFilterMode = 'NONE';
    this._fftTimeFilterRate = 0;
    this.onResult = null;          // (result) => void — RAW per-window result; the view applies .frc/mains/IMD
    this.onFftAutoStopped = null;  // () => void — stop-after-N tripped
    // Last DISPLAY-rebuild timestamp (Java FftAnalyzerWorker.lastShowNanos, in ms here):
    // the per-tick display throttle gates overlayOnto + recomputeStats + FLL + emit against
    // it so the O(N) finalize runs ≤25 Hz (DISPLAY_MIN_MS) when caught up and ≥2 Hz
    // (DISPLAY_MAX_MS) when behind — keeping the heavy work off the rAF render path.
    this._lastShowMs = 0;

    // Generated-signal change (Java FftAnalyzerWorker invalidateOnGenChange /
    // resetStatisticsAfterSignalChange): on a USER_INPUT change arm the output-drain
    // skip so the first window after the change holds none of the old tone still flowing
    // out of the DAC buffer. An FLL_TRIM is a sub-Hz alignment that must KEEP averaging,
    // so it does NOT arm the drain (it would needlessly blank the view on every trim).
    MessageBus.instance().subscribe(Events.GENERATOR_SIGNAL_CHANGED, (cause) => {
      if (cause === GenChangeCause.FLL_TRIM) return;
      // A user-initiated generator-frequency / form / FFT-length change invalidates
      // the lock — reset the deadbeat loop so it converges fresh from zero (Java
      // FftController.resetFrequencyLock), else its stale correction overshoots the
      // first measurement of the new signal.
      this._fll.reset();
      this.fllErrHz = 0; this.fllLocked = false; this.fllStable = 0; this.genFreq = this.snapped;
      if (this._fftOn) this._armOutputDrainSkip();
    });
  }

  /** Arms the one-shot output-drain skip (Java resetStatisticsAfterSignalChange →
   *  drainSkipPending → drainSkipRemaining): discard ~OUTPUT_DRAIN_SKIP_SEC of samples
   *  before the next window so it sees only the new signal. Re-anchors the cursor + the
   *  accumulator like an overrun so the discarded span can't leak into the average. */
  _armOutputDrainSkip() {
    const reader = this._fftReader;
    if (reader) { reader.seekToLatest(); this._absNextSample = reader.getReadPos(); }
    this.bufFilled = 0; this.bufW = 0; this._refill = this.hop;
    this._winAbsStart = 0; this._dispatchedOnce = false;
    this._accum.reset();
    this._accumEpoch++;
    this._drainSkipRemaining = Math.ceil(OUTPUT_DRAIN_SKIP_SEC * (this.config.inRate || 0));
  }

  // ---- Generator state read + FLL-steered through delegating accessors (this._gen) ----
  get snapped() { return this._gen.snapped; }
  get binW() { return this._gen.binW; }
  get genNode() { return this._gen.genNode; }
  get _genOn() { return this._gen.running; }
  _computeAnalysisFreqs() { this._gen.computeAnalysisFreqs(); }

  /** True while the FFT is recording. */
  get recording() { return this._fftOn; }
  /** True once stop-after-N has paused the feed. */
  get pausedByStopN() { return this._fftPausedByStopN; }

  /** Fraction (0..1) of the data needed for the *current* FFT frame already buffered
   *  — drives the fill-% indicator (Java FftAnalyzerWorker.getNextFrameProgress).
   *  Building the first window needs a full bufLen; once dispatching, each tick needs
   *  one fresh hop, so the bar sweeps 0→1 per hop. 0 when not recording.
   *
   *  Java reads {@code avail / want} off the worker's consuming cursor because the
   *  worker pulls exactly `needed` (first) or `hop` (subsequent) per tick and SLEEPS
   *  between, so the cursor's unread backlog genuinely sweeps 0→want. The web consumer
   *  drains the WHOLE cursor backlog into `buf` every capture batch (feedFft → _accumulate),
   *  so `reader.available()` is ~0 right after each batch and never sweeps — the live fill
   *  lives in the web consumer's own buffer state instead. So mirror Java's `avail / want`
   *  faithfully against THAT state: bufFilled / bufLen before the first dispatch
   *  (Java winValid==false ⇒ avail / winNeeded), then (hop − _refill) / hop per hop after
   *  (Java winValid==true ⇒ avail / hop). _dispatchedOnce is the web analog of winValid;
   *  _refill is the samples still owed before the next dispatch. Overrun stays a negative
   *  sentinel, distinct from 0 (no fresh samples), exactly as Java. */
  nextFrameProgress() {
    if (!this._fftOn || !this._fftReader || this.bufLen == null) return 0;
    if (this._fftReader.available() === OVERRUN) return -1.0;   // overrun → negative, distinct from "no fresh samples"
    let have, want;
    if (this._dispatchedOnce) {              // winValid: each tick needs one fresh hop
      want = this.hop;
      have = this.hop - this._refill;        // samples buffered toward the next hop dispatch
    } else {                                 // building the first window: needs a full bufLen
      want = this.bufLen;
      have = this.bufFilled;
    }
    const f = have / want;
    return f < 0 ? 0 : (f > 1 ? 1 : f);
  }

  /** Fundamental bin(s) the spectral-discontinuity gate measures the near-carrier
   *  pedestal around (Java FftAnalyzerWorker.fundamentalBins): the single-tone
   *  fundamental, plus the dual-tone second tone when present. null when none. */
  _fundamentalBins(r) {
    if (!(r.freqResolution > 0)) return null;
    const f1 = (Number.isFinite(r.fundamentalHzRefined) && r.fundamentalHzRefined > 0)
      ? Math.round(r.fundamentalHzRefined / r.freqResolution) : -1;
    const f2 = (Number.isFinite(r.fundamental2HzRefined) && r.fundamental2HzRefined > 0)
      ? Math.round(r.fundamental2HzRefined / r.freqResolution) : -1;
    if (f1 > 0 && f2 > 0) return Int32Array.of(f1, f2);
    if (f1 > 0) return Int32Array.of(f1);
    if (f2 > 0) return Int32Array.of(f2);
    return null;
  }

  /** Pushes a live multi-tone-detect-threshold pref change into the running
   *  accumulator (Java reads Preferences#getFftStrongToneRelDb live in
   *  detectStrongTones). Takes effect on the next accumulator restart. */
  setStrongToneRelDb(db) { if (this._accum) this._accum.setStrongToneRelDb(db); }

  /** FFT averages accumulated since the last reset (predistortion host) — the
   *  per-tick analysis count (Java getCompletedAnalyses / completedAnalyses). */
  completedAnalyses() { return this._analysesTicks; }
  /** Cross-tick accumulator frame depth since the last reset (Java
   *  getAccumulatedFrames) — the predistortion frame-depth readout, distinct from
   *  the per-tick completedAnalyses count stop-after-N keys off. */
  accumulatedFrames() { return this._analysesDone; }
  /** Resets the accumulated-averages counter AND the cross-tick accumulator
   *  (predistortion round boundary). Mirrors Java resetStatistics: a fresh round
   *  must start the average from zero, so bump the epoch to drop any in-flight
   *  window from before the reset. */
  resetAnalyses() {
    this._analysesTicks = 0;
    this._analysesDone = 0;
    // Java resetStatistics calls paused.set(false): a predistortion-round reset must
    // RESUME the feed after a prior stop-after-N auto-stop, else the next round starves.
    this._fftPausedByStopN = false;
    if (this._accum) { this._accum.reset(); this._accumEpoch++; }
  }

  /** Selects which ADC channel the FFT ANALYZES (Java FftView button →
   *  viewPrefs.setFftChannel → the fftChannelProperty subscription →
   *  resetStatistics). L → ch0 (left), R → ch1 (right). A real change resets
   *  the statistics + accumulator so collection restarts from 0 (Java
   *  FftView:439; the accumulator/count/epoch reset is the web analog of the
   *  worker's window rebuild on a channel change, Java FftAnalyzerWorker:1567).
   *  No-op when the channel is unchanged. */
  setFftChannel(ch) {
    const wl = ch === 'L';
    if (wl === this._wantLeft) return;   // no change → keep averaging
    this._wantLeft = wl;
    this.config.channel = ch;            // keep the config the read/emit consult in sync
    this.resetAnalyses();                // fresh average from 0 (accumulator + counts + epoch bump)
  }

  /** Turns the FFT's Record state on/off: acquires/releases the shared capture,
   *  sets up the analysis window + worker(s) + the contiguous cursor while on, and
   *  tears the worker(s) down on stop. Returns the resulting on-state (false if the
   *  acquire failed) so the caller can reconcile its own flag / LED. */
  async setRecording(on) {
    if (on === this._fftOn) return this._fftOn;
    if (on) {
      this._setupFftAnalysis();
      const reader = await this._capture.acquire();
      if (!reader) { this._teardownFftWorkers(); return false; }
      this._fftReader = reader;
      this._fftReader.seekToLatest();   // contiguous stream anchors at "now"
      this._absNextSample = this._fftReader.getReadPos();
      this._fll.reset();                // fresh Record session → converge alignment from zero
      this._fftOn = true;
    } else {
      this._fftOn = false;
      this._fftReader = null;
      this._teardownFftWorkers();
      await this._capture.release();
    }
    return this._fftOn;
  }

  /** Builds the analysis window geometry + the FFT analyzer worker (the faithful
   *  brain, off the main thread). poolSize = W from the #threads select, passed
   *  per-dispatch as `threads`: W = 1 → the classic single "buffer-and-analyze"
   *  analyze() inside the worker; W > 1 → the WORKER coordinates its own nested
   *  fft-pool-worker.js pool (prelude → split W contiguous frame ranges → gather
   *  → merge → finalize, all in-worker). The main thread posts ONE transferred
   *  message either way — Java parity: FftAnalyzerWorker.parallelChunks runs on
   *  the analyzer thread, never the UI thread. */
  _setupFftAnalysis() {
    const c = this.config;
    this.N = c.fftSize;
    // ∞ (forever) averaging is a true cumulative mean; a finite N is a ring window.
    // app.js passes averages = Infinity for the ∞ toggle (Java AVERAGES_SERIES ∞).
    this.foreverMode = c.averages === Infinity || !Number.isFinite(c.averages);
    this.ringN = this.foreverMode ? 1 : Math.max(1, c.averages | 0);
    // The on-screen "N×" depth target: ∞ → unbounded; a ring → its N. (The Java
    // worker caps the ring accumulator at 2·N frames since ~2 frames land per
    // coherent tick; here avgTarget is the displayed N and the accumulator's
    // targetN scaling — see _onWorkerResult — bounds the depth to it.)
    this.avgTarget = this.foreverMode ? Infinity : this.ringN;
    this._computeAnalysisFreqs();
    const overlapFrac = OVERLAP_FRACTION[c.overlap] || 0.0;
    this.hop = Math.max(1, Math.round(this.N * (1 - overlapFrac)));
    this.overlapPct = overlapFrac * 100;

    // Per-tick window = N + (perTickFrames−1)·hop samples — sized to ~1-2 frames
    // INDEPENDENT of the averaging depth (Java: needed = N + (frames−1)·hop, with
    // frames = coherent ? 2 : 1). The cross-tick running accumulator (this._accum)
    // supplies the depth, so the FIRST spectrum lands in <0.2 s at any averaging
    // instead of after bufLen/inRate seconds (the dead-FFT bug this fixes).
    // Size by AVERAGING state, not the coherent flag (Java: averages =
    // (foreverMode || ringN >= 2) ? 2 : 1). Any averaging contributes ~2 frames
    // per tick to the cross-tick accumulator; no averaging needs only 1.
    const accumulate = this.foreverMode || this.ringN >= 2;
    const perTickFrames = accumulate ? 2 : 1;
    this.bufLen = this.N + (perTickFrames - 1) * this.hop;
    this.buf = new Float64Array(this.bufLen);
    this.bufW = 0; this.bufFilled = 0;
    // Fresh Record session → fresh accumulator (Java start() resetStatistics).
    // Seed the multi-tone detect threshold live from the pref (Java reads
    // Preferences#getFftStrongToneRelDb in detectStrongTones); a running change
    // re-pushes via setStrongToneRelDb (app.js pref-change listener).
    this._accum.setStrongToneRelDb(this.config.fftStrongToneRelDb ?? 100.0);
    this._accum.reset();
    this._accumEpoch++;
    this._lastAccumulate = false;
    this._lastForeverMode = false;
    this._lastRingN = -1;
    // Absolute sample position of the window currently in `buf` (for the cross-tick
    // de-rotation delta). The first dispatched window starts at 0; each later one
    // advances exactly one hop — a uniform delta keeps the per-lobe rotation exact.
    this._winAbsStart = 0;
    this._dispatchedOnce = false;
    this._drainSkipRemaining = 0;   // fresh session anchors clean — nothing to drain

    // Generator-locked FLL bookkeeping shared with the result handler — a fresh Record session
    // resets the loop (this._fll.reset in setRecording), so the steer state starts from the base
    // emit frequency rather than a stale converged value.
    this.genFreq = this.snapped;
    this.fllErrHz = 0; this.fllLocked = false; this.fllStable = 0;
    this.framesDone = 0;
    // Stop-after-N restarts its count each Record session (Java zeroes
    // completedAnalyses on start); clear the pause so a fresh start re-feeds.
    this._analysesTicks = 0;
    this._analysesDone = 0;
    this._fftPausedByStopN = false;
    // Which ADC channel the FFT ANALYZES (Java FftAnalyzerWorker:1535-6
    // channel = prefs.getFftChannel(); wantLeft = channel == L). L → ch0 (left),
    // R → ch1 (right). feedFft reads THIS channel; _emit stamps it into
    // r.channelLeft so the .frc de-embed picks the matching curve.
    this._wantLeft = (this.config.channel === 'L');

    this.warmup = Math.round(c.inRate * Math.max(0, c.warmupMs) / 1000);
    this._lastShowMs = 0;   // fresh session → the first result displays immediately (Java lastShowNanos 0)
    this._fftFps = 0; this._fftCount = 0; this._fftWinT0 = 0; this._workerMs = 0;
    this._busy = false; this._dispatchId = 0; this.dropped = 0; this._refill = this.hop;

    // Worker-pool sizing — see _poolSizeFor. The pool itself lives INSIDE
    // fft-worker.js (nested workers) — the main thread only posts one message per tick,
    // identical to the serial path, so threads>1 can't starve the UI (Java parity:
    // FftAnalyzerWorker.parallelChunks runs on the analyzer thread, never the UI thread).
    this.poolSize = this._poolSizeFor(c.threads);
    // Seed the align-transition latch so _syncLiveConfig only resets the FLL on a real
    // OFF→ON flip (the fresh-session reset already runs in setRecording).
    this._lastFllOn = !!c.fllOn;
    const workerUrl = new URL('./fft-worker.js', import.meta.url);
    this.worker = new Worker(workerUrl, { type: 'module' });
    this.worker.onmessage = (e) => this._onWorkerResult(e.data);
    this.worker.onerror = (ev) => console.error('fft-worker', ev.message);
  }

  /** Kills the FFT analyzer worker (its nested pool sub-workers die with it). */
  _teardownFftWorkers() {
    try { if (this.worker) this.worker.terminate(); } catch (_) {}
    this.worker = null;
  }

  /** Effective worker-pool size for a requested thread count (Java parallelChunks:
   *  chunks = max(2, availableProcessors−1)). Java NEVER uses every logical core —
   *  it always leaves one for the rest of the app, so the UI/audio threads keep
   *  running; the web mirrors that: cap at hardwareConcurrency−1 (floor 1). */
  _poolSizeFor(threads) {
    const hw = navigator.hardwareConcurrency || 4;
    const coreCap = Math.max(1, hw - 1);
    return Math.max(1, Math.min(16, coreCap, threads | 0));
  }

  /** Live re-read of the running config, called once per capture batch (feedFft).
   *  The Java worker re-reads Preferences every tick, so a NON-STRUCTURAL settings
   *  change applies on the next tick with NO statistics reset — Java
   *  FftTabControl.java:377-380: "Overlap only changes the hop, not the
   *  spectrum/accumulator — refresh the tab tile but DON'T reset the average; the
   *  worker adapts next tick", and :391-397 (averages): "No reset here: the worker
   *  resets the average only on a ring↔∞ switch or a smaller ring (a larger ring
   *  keeps the depth)". The web consumer sized its hop/buffer/pool at setup only,
   *  so those changes used to need a full restartFft — which RESET the accumulator
   *  (retest #7/#26). This re-derives them live instead:
   *   - POOL SIZE (web-only #threads): the worker reads `threads` per dispatch
   *     message (fft-worker.js:151), so a live resize needs no worker rebuild.
   *   - AVERAGING targets (∞/ring): the depth transition itself stays in
   *     _onWorkerResult (lastAccumulate/lastForeverMode/lastRingN — the exact Java
   *     discard rules), so a larger ring KEEPS the collected depth.
   *   - HOP / window geometry (overlap): the analysis ring is rebuilt and re-fills
   *     (≲ bufLen/inRate s gap) but the ACCUMULATOR IS KEPT — the epoch bump only
   *     drops the in-flight old-geometry window, and onResync() re-anchors the κ
   *     slope + PLL across the timebase jump (the same keep-depth re-anchor the
   *     spectral-discontinuity rejector uses).
   *   - ALIGN OFF→ON: reset the deadbeat loop so alignment converges fresh (Java
   *     FftView.java:465-466 → FftController.resetFrequencyLock,
   *     FftController.java:210-219); switching OFF holds the converged correction. */
  _syncLiveConfig() {
    if (this.bufLen == null || !(this.N > 0)) return;   // no analysis geometry yet (not set up)
    const c = this.config;
    this.poolSize = this._poolSizeFor(c.threads);
    // Averaging mode/targets (mirrors the _setupFftAnalysis derivation).
    this.foreverMode = c.averages === Infinity || !Number.isFinite(c.averages);
    this.ringN = this.foreverMode ? 1 : Math.max(1, c.averages | 0);
    this.avgTarget = this.foreverMode ? Infinity : this.ringN;
    // Hop / window geometry off the live overlap + averaging state.
    const overlapFrac = OVERLAP_FRACTION[c.overlap] || 0.0;
    const hop = Math.max(1, Math.round(this.N * (1 - overlapFrac)));
    const accumulate = this.foreverMode || this.ringN >= 2;
    const bufLen = this.N + ((accumulate ? 2 : 1) - 1) * hop;
    if (hop !== this.hop || bufLen !== this.bufLen) {
      this.hop = hop;
      this.overlapPct = overlapFrac * 100;
      this.bufLen = bufLen;
      this.buf = new Float64Array(bufLen);
      this.bufW = 0; this.bufFilled = 0; this._refill = hop;
      this._winAbsStart = 0; this._dispatchedOnce = false;
      this._accumEpoch++;       // drop ONLY the in-flight old-geometry window
      this._accum.onResync();   // re-anchor κ slope + PLL; the collected DEPTH survives
    }
    if (c.fllOn && !this._lastFllOn) this._fll.reset();
    this._lastFllOn = !!c.fllOn;
  }

  /** Per-batch: pull the NEW contiguous R-channel (ch1) samples from the cursor and
   *  feed them through the buffer-and-analyze accumulate+dispatch loop. On OVERRUN
   *  (the writer lapped the cursor — the worker fell a full ring behind) re-anchor
   *  at the latest sample and reset the fill state, exactly as the cross-tick
   *  accumulator must (a torn window would smear the fundamental). */
  feedFft() {
    // Stop-after-N pause (Java FftAnalyzerWorker.workerLoop: `if (!paused.get())`
    // gates doAnalysis() — once stop-after-N trips paused, NO further tick runs, so
    // no more windows are buffered/dispatched). Mirror that here: gate the whole
    // per-batch feed while paused so the unthrottled heavy-rebuild burst that starves
    // the scope rAF can't happen (the tripping tick already showed its final frame via
    // showNow). Cleared on a fresh setRecording(true) / resetStatistics restart.
    if (this._fftPausedByStopN) return;
    const reader = this._fftReader;
    if (!reader) return;
    // Live settings re-derive (overlap hop / averages targets / threads pool / align
    // transition) — the Java worker re-reads Preferences every tick; NO reset here.
    this._syncLiveConfig();
    let avail = reader.available();
    if (avail === OVERRUN) { this._onOverrun(reader); return; }
    if (avail <= 0) return;
    // Output-drain skip (Java FftAnalyzerWorker): after a signal-change re-anchor,
    // consume and DISCARD the span still carrying the old tone (the DAC buffer keeps it
    // flowing into the ADC after the change) so the first window — and the FLL's first
    // measurement — see only the new signal. Discarding reads into null buffers (advances
    // the cursor without copying); return until the drain is exhausted.
    if (this._drainSkipRemaining > 0) {
      const got = reader.read(Math.min(avail, this._drainSkipRemaining), null, null);
      if (got === OVERRUN) { this._onOverrun(reader); return; }
      if (got > 0) this._drainSkipRemaining -= got;
      return;
    }
    if (!this._fftReadBuf || this._fftReadBuf.length < avail) this._fftReadBuf = new Float64Array(avail);
    const tmpR = this._fftReadBuf;
    // Absolute capture position of samples[0] (= the readPos before this consuming
    // read) — the timebase the FLL transport gate compares window starts against.
    const absBase = reader.getReadPos();
    // Read the SELECTED channel (Java FftAnalyzerWorker:1592
    // rdr.read(needed, wantLeft ? winBuf : null, wantLeft ? null : winBuf)):
    // L → ch0 fills the left arg, R → ch1 fills the right arg. Consuming forward read.
    const n = this._wantLeft ? reader.read(avail, tmpR, null) : reader.read(avail, null, tmpR);
    if (n === OVERRUN) { this._onOverrun(reader); return; }
    this._absNextSample = absBase;
    this._accumulate(tmpR, n);
  }

  /** Ring overrun: the writer lapped the cursor (the worker fell a full ring
   *  behind). Re-anchor at "now" and rebuild the window from a fresh contiguous
   *  span. Like the Java onCaptureOverrun, this RE-ANCHORS the accumulator's
   *  reference (a torn window would inject a phase jump) — bump the epoch so the
   *  worker result for the discarded window is dropped, and reset the accumulator
   *  so deep averaging restarts from the fresh unbroken span. */
  _onOverrun(reader) {
    reader.seekToLatest();
    this._absNextSample = reader.getReadPos();
    this.bufFilled = 0; this.bufW = 0; this._refill = this.hop;
    this._winAbsStart = 0; this._dispatchedOnce = false;
    this._accum.reset();
    this._accumEpoch++;
  }

  /** Recovery for a detected in-window signal discontinuity (Java FftAnalyzerWorker
   *  .onSignalDiscontinuity) — the same re-sync as a ring overrun: discard the glitched
   *  window, re-anchor to "now" and rebuild, but KEEP the running average (onResync
   *  re-anchors the κ slope / PLL — the web analog of Java's kappaSkipNext /
   *  multiKappaSkipNext / gapRecoverPending; the de-rotation absorbs the coverage gap).
   *  Reuses the drain-skip: the next feed consumes + discards POST_GLITCH_SKIP_SEC of
   *  samples before rebuilding the window, so the rebuild starts past the glitch's END,
   *  not at its detected start (max keeps a larger pending drain). Shared by the
   *  time-domain and the spectral gates, exactly as Java. Publishes the re-sync banner
   *  message-key (Java publishCaptureBanner — same event + i18n key). */
  _onSignalDiscontinuity() {
    const reader = this._fftReader;
    if (reader) {
      reader.seekToLatest();
      this._absNextSample = reader.getReadPos();
    }
    this._drainSkipRemaining = Math.max(this._drainSkipRemaining,
      Math.ceil(POST_GLITCH_SKIP_SEC * (this.config.inRate || 0)));
    this.bufFilled = 0; this.bufW = 0; this._refill = this.hop;
    this._winAbsStart = 0; this._dispatchedOnce = false;
    this._accum.onResync();   // KEEP the collected depth; re-anchor κ slope + PLL
    this._accumEpoch++;       // drop any in-flight window straddling the re-sync
    MessageBus.instance().publish(Events.FFT_CAPTURE_RESYNC, 'fft.warning.discontinuity');
  }

  /** Accumulate `n` contiguous samples into the analysis ring and dispatch a
   *  window every `hop` samples once the ring has filled. Identical buffer-and-
   *  analyze logic the fused engine ran per chunk, now fed from the FFT cursor. */
  _accumulate(samples, n) {
    const L = this.bufLen;
    for (let i = 0; i < n; i++) {
      this.buf[this.bufW] = samples[i]; this.bufW = (this.bufW + 1) % L;
      if (this.bufFilled < L) this.bufFilled++;
      if (this.warmup > 0) { this.warmup--; continue; }
      if (this.bufFilled >= L && --this._refill <= 0) {
        this._refill = this.hop;
        // Uniform-hop absolute window start (the cross-tick de-rotation delta).
        // First dispatched window starts at 0; each later one advances one hop —
        // a uniform delta is what keeps the accumulator's per-lobe rotation exact.
        const absStart = this._dispatchedOnce ? (this._winAbsStart + this.hop) : 0;
        this._winAbsStart = absStart;
        this._dispatchedOnce = true;
        // Absolute capture position of this window's first sample (the FLL transport
        // timebase): samples[i] sits at _absNextSample + i, and this window ends at it.
        const absCapStart = (this._absNextSample + i) - (this.bufLen - 1);
        this._dispatch(absStart, absCapStart);
      }
    }
  }

  /** Mains suppression on the about-to-be-analyzed window (Java FftAnalyzerWorker):
   *  tracks the live mains fundamental, then — per the configured mode — either
   *  filters the window in the time domain (SYNC_SUBTRACT / LMS) or records the
   *  tracked f0 for the PLOT-time spectral comb correction (IIR_COMB; window left
   *  raw so the coherent average is undisturbed).  Returns the dBFS spectrum the
   *  IIR comb correction must be applied to at render time, via this._fftMainsF0.
   *
   *  SYNC_SUBTRACT / LMS remove the hum from the window IN PLACE before the FFT
   *  (Java mainsTimeFilter path): both subtract only the additive hum, leaving the
   *  test tone's amplitude and phase intact, so the coherent de-rotation / average
   *  is undisturbed.  The filter instance is PERSISTENT (its template / taps adapt
   *  tick to tick) and `absCapStart` — the window's absolute capture position (Java
   *  samplesAbsStart) — keeps the period-locked state phase-aligned across the
   *  overlapping hop-spaced windows. */
  _applyFftMains(snap, sampleRate, absCapStart) {
    const mode = this.config.mainsSuppression || 'NONE';
    if (mode === 'NONE') { this._fftMainsF0 = 0; return; }
    if (mode === 'IIR_COMB') {
      if (!this._fftMains || this._fftMainsRate !== sampleRate) {
        this._fftMains = new MainsCombFilter(sampleRate, DEFAULT_NOTCH_BANDWIDTH_HZ);
        this._fftMainsRate = sampleRate;
        this._fftMainsTick = 0;
      }
      const comb = this._fftMains;
      // Re-track every Nth tick only (a second of samples gives a mHz estimate).
      if (this._fftMainsTick++ % MAINS_TRACK_TICK_INTERVAL === 0) {
        comb.track(snap, Math.min(snap.length, sampleRate));
      }
      // Plot-time spectral correction — leave the window (and the worker
      // accumulator) raw; remember the locked fundamental for _onWorkerResult.
      this._fftMainsF0 = comb.isTuned() ? comb.getMainsHz() : 0;
      return;
    }
    // SYNC_SUBTRACT / LMS → true time-domain canceller pre-FFT (Java
    // FftAnalyzerWorker.mainsTimeFilter(mode, sampleRate) via MainsFilters.of).
    this._fftMainsF0 = 0;
    if (!this._fftTimeFilter || this._fftTimeFilterMode !== mode
        || this._fftTimeFilterRate !== sampleRate) {
      this._fftTimeFilter = mainsFilterOf(mode, sampleRate, DEFAULT_NOTCH_BANDWIDTH_HZ);
      this._fftTimeFilterMode = mode;
      this._fftTimeFilterRate = sampleRate;
      this._fftMainsTick = 0;
    }
    const mf = this._fftTimeFilter;
    if (this._fftMainsTick++ % MAINS_TRACK_TICK_INTERVAL === 0) {
      mf.track(snap, Math.min(snap.length, sampleRate));
    }
    mf.processPreservingDc(snap, snap.length, absCapStart);
  }

  /** The dBFS reference anchor passed into FftAnalyzer for the THD "manual
   *  fundamental" mode — the canonical Vrms the user typed, resolved to its dBV
   *  anchor (20·log10 Vrms) then to dBFS at this boundary (minus dbvOffsetDb).
   *  NaN (no anchor — auto-detect the fundamental) unless manual-fundamental
   *  mode is enabled. Mirrors FftAnalyzerWorker.resolveFundRefDbFs. */
  _fundRefDbFs() {
    const c = this.config;
    if (!c.manualFundEnabled) return NaN;
    const v = c.manualFundVrms;
    const dbv = (v > 0) ? 20.0 * Math.log10(v) : NaN;
    return dbv - (c.dbvOffsetDb || 0);   // NaN propagates
  }

  /** Hands the current window to the analyzer (one analysis in flight at a time;
   *  drop the tick while busy so capture can't back up). Routes to the worker
   *  POOL when poolSize > 1, else the classic single-worker path. */
  _dispatch(absStart, absCapStart) {
    if (this._busy) { this.dropped++; return; }
    const L = this.bufLen, snap = new Float64Array(L);
    snap.set(this.buf.subarray(this.bufW));
    snap.set(this.buf.subarray(0, this.bufW), L - this.bufW);
    this._applyFftMains(snap, this.config.inRate, absCapStart);   // mains suppression on the window (+ plot-time f0)
    // Stamp this tick's absolute window start + accumulator epoch so _onWorkerResult
    // folds the result in with the right de-rotation delta and drops it if a reset
    // (epoch bump) landed while it was analyzing.
    this._tickAbsStart = absStart || 0;
    this._tickEpoch = this._accumEpoch;
    // FLL transport timebase (Java FftResult.samplesAbsStart / writePos): the window's
    // absolute capture start, and the live capture write head at publish time.
    this._tickAbsCapStart = absCapStart;
    this._tickWritePos = this._fftReader ? this._fftReader.getWritePos() : 0;
    if (!this.worker) { this.dropped++; return; }
    this._busy = true;
    const c = this.config;
    const dual = isDualTone(c.form);
    this.worker.postMessage({
      id: this._dispatchId++, samples: snap, sampleRate: c.inRate, fftSize: this.N,
      harmonicCount: c.harmonicCount, windowType: c.window, overlap: c.overlap,
      snrFreqMin: c.snrFreqMin || 0, snrFreqMax: c.snrFreqMax || 0, coherentAveraging: this.config.coherent,
      expectedFundHz: c.fftFundFromGenerator ? this.snapped : NaN,   // hint vs auto-detect
      fundRefDbFs: this._fundRefDbFs(),   // THD manual-fundamental anchor (NaN = auto-detect)
      multiTone: dual, secondToneHintHz: dual ? c.tone2Hz : NaN,
      threads: this.poolSize,   // >1 → the worker fans out to its nested pool
      // Time-domain discontinuity gate (Java: if (USE_TIME_DISCONTINUITY && accumulate)):
      // the raw window lives in the worker after this transfer, so the worker runs the
      // detector pass — but only when this tick will accumulate, exactly as Java.
      timeGate: USE_TIME_DISCONTINUITY && (this.foreverMode || this.ringN >= 2),
    }, [snap.buffer]);
  }

  _onWorkerResult(r) {
    this._busy = false;
    if (!this._fftOn) return;
    if (r.error) { console.error('analyze', r.error); return; }

    // Drop a window that straddles a reset / overrun (Java epoch gate): the
    // accumulator was re-anchored while this window was analyzing, so folding it
    // would poison the freshly cleared average and its stale fundamental would
    // step the FLL.
    if (this._tickEpoch !== this._accumEpoch) return;

    const t0 = performance.now();
    this._fftCount++; if (!this._fftWinT0) this._fftWinT0 = t0;
    if (t0 - this._fftWinT0 >= 2000) { this._fftFps = this._fftCount * 1000 / (t0 - this._fftWinT0); this._fftCount = 0; this._fftWinT0 = t0; }
    this._workerMs = this._workerMs ? this._workerMs * 0.85 + r.ms * 0.15 : r.ms;

    // ── Cross-tick accumulate (Java FftAnalyzerWorker doAnalysis tail) ──────────
    // Accumulate whenever any averaging is requested. Each tick FFTs only ~1-2
    // fresh frames; the running accumulator supplies the depth. A finite ring is
    // bounded to 2·ringN frames (≈2 coherent frames land per tick, so the displayed
    // "N×" climbs to ringN over 2·ringN folded frames); ∞ mode is a cumulative mean.
    const coherent = !!r.coherentAveraging;
    const forever = this.foreverMode;
    const ringN = this.ringN;
    const accumulate = forever || ringN >= 2;
    const targetN = forever ? Infinity : 2 * ringN;
    const perTickFrames = coherent ? 2 : 1;

    // Mode-transition reset (Java lastAccumulate / lastForeverMode / lastRingN):
    // only when the new bound discards collected depth — a flip, a ring↔∞ switch,
    // or a SMALLER ring. (A larger ring just widens the exponential window.)
    if (accumulate !== this._lastAccumulate
        || forever !== this._lastForeverMode
        || (!forever && ringN < this._lastRingN)) {
      this._accum.reset();
      this._analysesTicks = 0;   // Java discard branch zeroes completedAnalyses too
      this._analysesDone = 0;
    }
    this._lastAccumulate = accumulate;
    this._lastForeverMode = forever;
    this._lastRingN = ringN;

    let accumulated = false;
    if (accumulate) {
      // Time-domain discontinuity gate FIRST (Java FftAnalyzerWorker doAnalysis: the
      // cheap O(n) pass on the raw window runs before the spectral gate). The detector
      // itself ran in fft-worker.js — the raw window lives there after the transfer,
      // and the tick's own refined fundamental pins the recurrence prediction exactly,
      // so the reject threshold rides on the noise floor at any signal frequency; NaN
      // (no tone) self-estimates. On detect → the same re-sync + banner as the
      // spectral gate below, and the block is dropped before it can fold in.
      if (USE_TIME_DISCONTINUITY && r.timeDiscontinuity) {
        console.info('Time-domain discontinuity in the tick window — re-sync');
        this._onSignalDiscontinuity();
        return;
      }
      // Frequency-domain glitch / stall rejection (Java FftAnalyzerWorker): compare
      // this tick's spectrum to the running-median reference and re-sync (re-anchor
      // past the glitched overlap) BEFORE it can poison the cross-tick vector average.
      // Java runs this for ANY accumulate tick (coherent OR incoherent) — not gated on
      // the coherent flag. In the incoherent path the analyzer stores the per-window
      // magnitude in r.re (im = 0), so the same magnitude-domain gate applies. On a
      // reject the shared recovery (Java onSignalDiscontinuity) re-anchors the cursor
      // to "now", arms the 5 ms post-glitch drain skip, re-anchors the accumulator's
      // κ slope / PLL (onResync — the collected depth survives) and bumps the epoch so
      // the discarded window can't fold in.
      {
        const binW = r.freqResolution;
        const peakBins = this._fundamentalBins(r);
        if (this._accum.reject(r.re, r.im, r.fftSize / 2, binW, peakBins)) {
          this._onSignalDiscontinuity();
          return;
        }
      }
      accumulated = this._accum.accumulate(r, this._tickAbsStart, coherent, targetN);
    }

    // Per-tick bookkeeping runs EVERY accepted tick (Java completedAnalyses++ /
    // firstFrameDone = true): the analysis-TICK count is what stop-after-N and the
    // predistortion host key off. Reached only past the epoch + reject gates above,
    // so rejected / straddling ticks don't count — exactly as the desktop.
    this._analysesTicks++;

    // Per-tick depth readout. When averaging, the displayed "N×" is the accumulator
    // depth in tick-equivalents (accumFrames / framesPerTick), capped at the target
    // N — so it climbs 1/N, 2/N, … as the cross-tick average deepens. With no
    // averaging (ringN = 1) there is no accumulator; show the single window's own
    // frame count (1/1).
    const accumFrames = this._accum.accumFrames;
    if (accumulate) {
      const depthTicks = Math.round(accumFrames / perTickFrames);
      this.framesDone = forever ? depthTicks : Math.min(this.avgTarget, depthTicks);
      // Frame depth (Java getAccumulatedFrames) — the predistortion frame-depth readout,
      // NOT the stop-after-N / completedAnalyses key (that's the per-tick count above).
      this._analysesDone = accumFrames;
    } else {
      this.framesDone = Math.min(this.avgTarget, r.frameCount);
      this._analysesDone += r.frameCount;
    }

    // Stop-after-N (Java FftAnalyzerWorker): ONLY in ∞ (forever) mode, when the cap is
    // enabled and the analysis-TICK count reaches it — pause the consumer and notify the
    // UI so it can un-light the Record LED (FFT_RECORDING_AUTO_STOPPED → disengageRecord).
    if (forever && this.config.stopAfterNEnabled && !this._fftPausedByStopN
        && this._analysesTicks >= this.config.stopAfterN) {
      this._fftPausedByStopN = true;
      if (this.onFftAutoStopped) { try { this.onFftAutoStopped(); } catch (_) {} }
    }

    // Per-tick DISPLAY TRIM (Java FftAnalyzerWorker doAnalysis tail, the showNow gate):
    // the accumulator above ran THIS tick, but the O(N) display rebuild below — overlayOnto
    // + recomputeStats + FLL + emit — does NOT need to. Skip it while the capture backlog is
    // high (catch up rather than overrun), throttle it to DISPLAY_MIN_MS when caught up, but
    // force it by DISPLAY_MAX_MS so the view never freezes. This is the fix for the FFT
    // starving the scope to ~1 fps (#18) and a mid-capture cal load hanging the UI (#16):
    // the heavy finalize is bounded to ≤25 Hz on the main thread, leaving the rAF loop +
    // input free between rebuilds. Java:
    //   long backlog   = rdr.getWritePos() - rdr.getReadPos();
    //   long sinceShow = System.nanoTime() - lastShowNanos;
    //   boolean showNow = paused.get() || sinceShow >= DISPLAY_MAX_NANOS
    //                   || (sinceShow >= DISPLAY_MIN_NANOS && backlog <= 2L * needed);
    //   if (!showNow) { resultPool.release(r); return msForSamples(hopSamples, sampleRate); }
    //   lastShowNanos = System.nanoTime();
    const backlog = this._fftReader ? (this._fftReader.getWritePos() - this._fftReader.getReadPos()) : 0;
    const sinceShow = performance.now() - this._lastShowMs;
    const showNow = this._fftPausedByStopN
      || sinceShow >= DISPLAY_MAX_MS
      || (sinceShow >= DISPLAY_MIN_MS && backlog <= 2 * this.bufLen);
    if (!showNow) return;   // accumulated already; defer the O(N) rebuild + FLL + emit
    this._lastShowMs = performance.now();

    // Finalize the DISPLAYED spectrum + stats OFF the accumulator (Java
    // overlayAccumulatorOnto + recomputeStats), not off the single window — so the
    // shown spectrum is the deep cumulative average, with THD/SNR re-derived from it.
    if (accumulated) {
      this._accum.overlayOnto(r);            // r.amplitudeDbFs/re/im ← cumulative average
      this._coordAnalyzer.recomputeStats(r); // re-derive fundamental/harmonics/THD/SNR
      r.frameCount = this._accum.accumFrames;
      // Pinned coherent κ for the plot-time "before" dots (NaN ⇒ single tick).
      r.coherentKappa = this._accum.hasData ? this._accum.accumKFractional : NaN;
    }

    // The FFT pane + the predistortion read the EMITTED result. The worker→main transfer is a
    // plain, lossy snapshot, so (1) re-adopt the FftResult prototype (methods rawHarmonicDbFs /
    // deepCopy / noisePeakFloorDbFs) and (2) re-stamp the RAW (pre-.frc-de-embed) fundamental +
    // peak phasors off the deep-averaged spectrum HERE — before fftViewCorrection.apply (run in
    // engine.onResult AFTER _emit) de-embeds re/im in place. Mirrors Java FftAnalyzer, which
    // captures the raw peaks after averaging and before the .frc de-embed.
    r = FftResult.adopt(r);
    r.captureRawPeaks();

    // FLL — steer the generator off the sub-bin refined fundamental so the captured
    // tone returns to the exact target bin (nulls the DAC↔ADC clock offset). Driven
    // from fundamentalHzRefined through the Java FrequencyFll deadbeat loop: ONE
    // bounded correction per fully-observed transport round trip, NOT a per-frame
    // proportional integrator. The published generator frequency is snapped +
    // correction. Only steers when the generator is actually running.
    if (this.config.fllOn && this._genOn && this.genNode && r.fundamentalHzRefined > 0) {
      // Plausibility gate (Java plausibleFllMeasurement): clock drift is ppm-scale, so a
      // measurement farther than max(5 bins, 500 ppm) from the target is a mis-measurement
      // (a window still draining the OLD signal after a form/freq switch, a harmonic
      // mis-lock, a capture glitch) — skip steering so it can't trim the live generator off.
      // Gate only the STEER (not the display emit below — that still publishes the spectrum).
      // FLL TARGET = the entered tone snapped to the CAPTURE-rate FFT bin grid (r.sampleRate),
      // identical to what the Δosc readout (fft-view.js expected) and the coherent FFT use.
      // Java FftController.applyFrequencyLock:315 snaps to slot.sampleRate (the FFT/capture rate);
      // in Java the DAC and ADC share one exclusive-mode clock so that equals the output rate and
      // the loop naturally lands on the FFT bin. The web's generator (outCtx) and capture (inCtx)
      // are two INDEPENDENT AudioContext clocks (~80 ppm apart), so targeting this.snapped (the
      // OUTPUT-rate bin) locks the tone to the wrong grid and Δosc stays ≈80 ppm. Targeting the
      // capture-rate bin steers the CAPTURED tone onto the actual FFT bin → Δosc nulls. The
      // published base stays this.snapped (what the DAC plays); fll.correction bridges the gap.
      const fllTarget = this._gen.snapToRate(r.sampleRate);
      const maxErrHz = Math.max(FLL_MAX_ERROR_BINS * this.binW, fllTarget * FLL_MAX_ERROR_PPM * 1e-6);
      if (Math.abs(r.fundamentalHzRefined - fllTarget) <= maxErrHz) {
        this.fllErrHz = r.fundamentalHzRefined - fllTarget;
        // Java FftController.applyFrequencyLock (FftController.java:322-324): fll.update folds this
        // measurement into the deadbeat + transport gate, then it publishes `target + correction`
        // where target is the CAPTURE-rate snap (slot.sampleRate) — NOT the output-rate base. We had
        // wrongly published this.snapped (output grid) + correction, so the DAC was still commanded
        // on the output grid. Publish fllTarget + correction, exactly like Java.
        const gateBefore = this._fll.correctionVisibleFrom;
        this._fll.update(fllTarget, r.fundamentalHzRefined,
          this._tickAbsCapStart, this._tickWritePos, this.config.inRate, this.N);
        this.genFreq = fllTarget + this._fll.correction;
        // Publish the trim; GeneratorController applies it to its own worklet (was a direct post).
        MessageBus.instance().publish(Events.GENERATOR_FREQ_TRIM, this.genFreq);
        if (traceFll()) {
          // Java log.warn("FLL t1: target=… meas=… corr=… pub=…"), plus the transport
          // timebase + gate so an inert loop is diagnosable (gate held forever ⇒ winStart
          // never reaches visibleFrom; corr stuck at 0 ⇒ lock-band/plausibility hold).
          const held = this._fll.correctionVisibleFrom >= 0
            && this._tickAbsCapStart < this._fll.correctionVisibleFrom;
          console.warn(`FLL: target=${fllTarget.toFixed(6)} meas=${r.fundamentalHzRefined.toFixed(6)}`
            + ` corr=${this._fll.correction >= 0 ? '+' : ''}${this._fll.correction.toFixed(6)}`
            + ` pub=${this.genFreq.toFixed(6)} winStart=${this._tickAbsCapStart} writePos=${this._tickWritePos}`
            + ` gate[${gateBefore}→${this._fll.correctionVisibleFrom}] ${held ? 'HELD' : 'open'}`);
        }
        // Status-display lock latch ONLY (Java ALIGN_DONE_PPM aligned flag): lights the
        // "locked" readout once the measured error sits within tolerance. It does NOT
        // gate the steering — the deadbeat loop's own transport gate provides the
        // damping, and the loop keeps correcting ongoing DAC↔ADC clock drift.
        const errBins = this.fllErrHz / this.binW;
        if (Math.abs(errBins) < 5e-4) {
          if (++this.fllStable >= 2) this.fllLocked = true;
        } else {
          this.fllStable = 0;
          this.fllLocked = false;
        }
        // Web limitation (Java runs a second FrequencyAligner, fll2, off imd.f2):
        // in dual-tone mode only tone 1 is steered here. This controller
        // models a single FLL state (genFreq / fllErrHz / fllLocked), not Java's twin
        // independent FrequencyAligner loops, so a faithful second-tone trim would need
        // its own loop + lock state. Accepted as a web limitation.
      } else if (traceFll()) {
        // Java log.warn("FLL t1 GATED: target=… meas=…") — the plausibility gate rejected
        // this measurement (|meas − target| beyond max(5 bins, 500 ppm)); no steer this frame.
        console.warn(`FLL GATED: target=${fllTarget.toFixed(6)} meas=${r.fundamentalHzRefined.toFixed(6)}`
          + ` errHz=${(r.fundamentalHzRefined - fllTarget).toFixed(6)} maxErrHz=${maxErrHz.toFixed(6)}`);
      }
    } else if (traceFll()) {
      // Steering skipped entirely: report which precondition failed (fllOn / generator
      // running / generator node / a usable refined fundamental) — the classic "inert" path.
      console.warn(`FLL SKIP: fllOn=${!!this.config.fllOn} genOn=${this._genOn}`
        + ` genNode=${!!this.genNode} fundRefined=${r.fundamentalHzRefined}`);
    }

    // The worker accumulator stays RAW: the render-time .frc de-embed + IIR-comb mains correction
    // + IMD run in the VIEW (fft/fft-view-correction.js), not here. Attach the tracked mains comb +
    // locked fundamental so the view can divide the comb response out of the displayed spectrum.
    r._mainsComb = this._fftMains;
    r._mainsF0 = this._fftMainsF0;

    this._emit(r);
  }

  _emit(r) {
    if (!this.onResult) return;
    const c = this.config;
    r.binW = this.binW;
    // Stamp the channel the FFT ACTUALLY analyzed (Java FftAnalyzerWorker:1872
    // r.channelLeft = wantLeft): the render-time .frc de-embed picks left()/right()
    // off this (fft-view-correction.js) and the predistortion cal reads it.
    r.channelLeft = this._wantLeft;
    // One-tick display lag (Java FftView startFillPercentTimer: shows
    // completedAnalyses − 1, min 0): the depth shown is the number of ticks
    // already FOLDED before this one, so the very first result reads 0/N rather
    // than jumping straight to 1/N. Clamp at 0.
    r.framesDone = Math.max(0, this.framesDone - 1);
    // ∞ (forever) mode has no finite target — show the ∞ glyph (Java AVERAGES_SERIES ∞);
    // a finite ring shows its N. (framesDone >= avgTarget readouts stay false for ∞.)
    r.avgTarget = this.foreverMode ? '∞' : this.avgTarget;
    r.coherent = r.coherentAveraging;
    r.fll = {
      on: c.fllOn, locked: this.fllLocked, errBins: this.fllErrHz / this.binW,
      ppm: this.snapped > 0 ? this.fllErrHz / this.snapped * 1e6 : 0,
      genFreq: this.genFreq, rejected: this.rejectedCount,
    };
    r.perf = {
      fftFps: this._fftFps, targetFps: c.inRate / this.hop, workerMs: this._workerMs,
      dropped: this.dropped, threads: this.poolSize, overlapPct: this.overlapPct, hopMs: this.hop / c.inRate * 1000,
    };
    r.inRate = c.inRate; r.snapped = this.snapped;
    // The IMD table is computed off the DE-EMBEDDED spectrum, so it runs in the view correction
    // (fft/fft-view-correction.js) alongside the .frc de-embed, not here.

    // Remembered for reemitDisplay (the stopped-state cal re-apply) — and as the identity
    // guard so a LOADED .fft file in the pane is never overwritten by a stale re-emit.
    this._lastEmitted = r;
    this.onResult(r);
  }

  /** #24 follow-up: re-derives + re-emits the DISPLAYED result from the RAW accumulator
   *  (which survives a stop) so a calibration Active/With-noise/load/remove change applies
   *  to a STOPPED FFT too. Java's .frc de-embed is a plot-time transform over the raw
   *  lastResult, so a toggle there just repaints; the web's emit-time copy needs this
   *  re-emit through engine.onResult so the CURRENT correction cascade re-applies to raw
   *  data. overlayOnto REPLACES the result's spectral arrays from the accumulator (its
   *  mag→amplitude anchor at the fundamental bin survives the .frc de-embed, which scales
   *  amplitude and re/im by the same factor). No FLL steer, no stop-after — display only.
   *  `base` must be the pane's CURRENT result: when it isn't the controller's own last
   *  emit (a loaded .fft file), this is a no-op. Returns whether a result was re-emitted.
   *  Limitation: a non-averaged run leaves the accumulator empty — nothing to rebuild. */
  reemitDisplay(base) {
    const r = this._lastEmitted;
    if (!r || (base && base !== r) || !this._accum.hasData) return false;
    this._accum.overlayOnto(r);
    this._coordAnalyzer.recomputeStats(r);
    r.frameCount = this._accum.accumFrames;
    r.coherentKappa = this._accum.accumKFractional;
    r.captureRawPeaks();
    this._emit(r);
    return true;
  }

  /** Re-acquires the shared capture + re-anchors the FFT cursor after a device reopen
   *  (mirror the Java SignalBufferReader re-attach). Returns true when the re-acquire succeeded. */
  async reattach() {
    const r = await this._capture.acquire();
    this._fftReader = r;
    if (r) r.seekToLatest(); else this._fftOn = false;
    return r != null;
  }
}
