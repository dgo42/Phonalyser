/*
 * Phonalyser web - the FFT analysis capture consumer.
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
import { FftAnalyzer } from './fft-analyzer.js';
import { FftResult } from './fft-result.js';
import { FftAccumulator } from './fft-accumulator.js';
import { isDualTone } from '../generator/dds-kernel.js';
import { OVERRUN } from '../audio/signal-buffer-reader.js';
import { MainsCombFilter, DEFAULT_NOTCH_BANDWIDTH_HZ } from '../dsp/mains/comb-filter.js';
import { mainsFilterOf } from '../dsp/mains/factory.js';
import { MessageBus } from '../bus/message-bus.js';
import { Events, GenChangeCause } from '../bus/events.js';
import { FrequencyFll } from '../dsp/fll.js';
import { refinePeak, TONE_SEARCH_BINS } from './imd-analyzer.js';
import { debug } from '../util/debug.js';

/** Output-pipeline drain to skip after a generator/form/frequency change, in seconds
 *  (Java OUTPUT_DRAIN_SKIP_SEC). The DAC's hardware buffer (~480 ms on the render path)
 *  keeps the OLD tone flowing into the ADC after the generator changed; a window built
 *  before it drains straddles both signals - poisoning the fresh accumulator AND feeding
 *  the FLL a smeared, plausible-looking first measurement. */
const OUTPUT_DRAIN_SKIP_SEC = 0.7;
/** Guard skipped after a discontinuity re-sync, ahead of the first fresh window (Java
 *  POST_GLITCH_SKIP_SEC): detection fires on the glitch's START, and the glitch (the
 *  observed USB gaps run 120-160 µs) plus any settling can still be in flight at
 *  "latest" - 5 ms of discarded samples puts the rebuild safely past its end. */
const POST_GLITCH_SKIP_SEC = 0.005;
/** Time-domain discontinuity gate toggle (Java FftAnalyzerWorker.USE_TIME_DISCONTINUITY):
 *  the scope's glitch detector run on the tick's raw window - a splice/dropout breaks the
 *  sinusoid recurrence decades above the noise floor even when its spectral footprint
 *  slips under the frequency-domain gates (and vice versa), so the scope trigger and the
 *  FFT rejection agree on what counts as a damaged block. The O(n) detector pass runs in
 *  fft-worker.js (which holds the raw window); this flag arms it per dispatch and gates
 *  the re-sync off the returned verdict. */
const USE_TIME_DISCONTINUITY = true;
/** FLL measurement plausibility bound, absolute floor in FFT bins (Java
 *  FLL_MAX_ERROR_BINS) - keeps the gate permissive at low target frequencies
 *  where the ppm part collapses below the spectral resolution. */
const FLL_MAX_ERROR_BINS = 5;
/** FLL measurement plausibility bound, relative part in ppm (Java
 *  FLL_MAX_ERROR_PPM) - generator-vs-ADC clock drift is ppm-scale, so 500 ppm is
 *  a generous ceiling for a REAL mistune; beyond it is a mis-measurement (a window
 *  still draining the OLD signal, a harmonic mis-lock, a glitch) that must not steer. */
const FLL_MAX_ERROR_PPM = 500;
/** FLL trace (Java DebugSwitches.TRACE_FLL): when truthy, log every steer / gate /
 *  hold so the live loop is diagnosable - are absCapStart / writePos / measuredHz
 *  finite and advancing, does the transport gate ever open, does genFreq update.
 *  Off by default (no behaviour change); enable from the console with
 *  `window.TRACE_FLL = true`. Mirrors the log.warn lines in FftController.applyFrequencyLock. */
function traceFll() { return typeof window !== 'undefined' && window.TRACE_FLL; }
/** Re-track the mains comb every Nth FFT tick (Java MAINS_TRACK_TICK_INTERVAL). */
const MAINS_TRACK_TICK_INTERVAL = 5;
/** Per-tick DISPLAY throttle (Java FftAnalyzerWorker.DISPLAY_MIN_NANOS / DISPLAY_MAX_NANOS,
 *  expressed in ms here). The cross-tick accumulator runs EVERY tick, but the O(N) display
 *  rebuild - overlayOnto + recomputeStats + FLL + emit - is gated to AT MOST one per
 *  DISPLAY_MIN_MS when caught up, and SKIPPED while the capture backlog is high so the
 *  consumer catches up instead of overrunning - but never deferred past DISPLAY_MAX_MS so
 *  the view can't freeze. This keeps the heavy O(N) finalize OFF the rAF render path so the
 *  FFT can't starve the scope to ~1 fps and a mid-capture cal load can't hang the UI:
 *  the main thread is freed between display rebuilds for input + the scope render.
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
   * @param deps    {status, prefs} - status: (text) => void; prefs: the live Preferences,
   *                read for the settings whose change must reset this controller's own
   *                accumulator (absent in bare test constructions - the reaction stays unwired).
   */
  constructor(capture, gen, config, { status, prefs } = {}) {
    this._capture = capture;
    this._gen = gen;
    this.config = config;
    this._status = status || (() => {});
    this._fftOn = false;
    // Whether the analyser was recording when stopCaptureForPrefs() recorded it, so
    // startCaptureForPrefs() restarts exactly that (Java FftPane.recordWasRunningForPrefs).
    this._recordWasRunningForPrefs = false;
    this._fftReader = null;
    this._fftReadBuf = null;
    this._fftPausedByStopN = false;
    // Why the capture ended under this consumer, once it CLAIMED that report (Java
    // FftAnalyzerWorker.captureEndReason). null for a stop-after-N pause, a user stop, or when
    // the scope claimed the one report first - the pane shows a dialog only for a non-null one.
    this._captureEndReason = null;
    // Samples still to discard after a signal-change re-anchor (Java drainSkipRemaining):
    // armed by the GENERATOR_SIGNAL_CHANGED subscription below, consumed in feedFft before
    // the first dispatch so the first window holds none of the old tone still draining out
    // of the DAC buffer.
    this._drainSkipRemaining = 0;
    // Main-thread analyzer: ONLY for recomputeStats (the render-time .frc de-embed re-derive).
    // All analysis - including the threads>1 parallel pool - runs inside fft-worker.js, which
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
    // Step 5b dual-fold parity harness (default OFF -> the fold runs only here on main, zero
    // overhead). Flip via localStorage 'foldInWorker'='1' + reload: the worker then runs a
    // SHADOW copy of the cross-tick fold and this controller logs main-vs-worker spectrum
    // parity. Steady-state clean runs are bit-identical; a mid-run reset/glitch desyncs the
    // two depths (an async worker can't drop the same straddling window) - reported, not failed.
    this._foldInWorker = (() => {
      try { return !!(globalThis.localStorage && localStorage.getItem('foldInWorker') === '1'); }
      catch (_) { return false; }
    })();
    this._parityDesynced = false;
    // One-shots -> the worker's shadow accumulator, so it resets / re-anchors at the SAME ticks
    // this controller's own accumulator does (set at every _accum.reset() / _accum.onResync()).
    this._workerResetPending = false;
    this._workerResyncPending = false;
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
    // Second, INDEPENDENT deadbeat loop for the DUAL_TONE second tone (Java FftController's
    // fll2). Constructed + reset in lockstep with _fll; only exercised on a dual-tone form
    // where the second-tone steer publishes GENERATOR_FREQ_TRIM_2. Single-tone forms leave it idle.
    this._fll2 = new FrequencyFll();
    // FLL display + steer state - OWNED here now (moved off GeneratorController): the corrected
    // generator frequency + lock status this loop computes and _emit reports. The steer publishes
    // GENERATOR_FREQ_TRIM; the generator applies it to its worklet.
    this.genFreq = 0;
    this.fllErrHz = 0;
    this.fllLocked = false;
    this.fllStable = 0;
    this.rejectedCount = 0;
    // Previous batch's config.fllOn, for the align OFF->ON transition (Java
    // FftView.java:465-466: "Selecting an active alignment mode (PID / FLL) resets its
    // loop so each session converges fresh; NONE deliberately resets nothing").
    this._lastFllOn = false;
    // Absolute capture position (in samples) of the next sample to enter the analysis
    // ring - the timebase the FLL transport gate compares window starts against. Set
    // from the reader's readPos on every (re)anchor; advanced as samples are buffered.
    this._absNextSample = 0;
    // Analysis TICKS since the last reset (Java completedAnalyses - incremented once
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
    this.onResult = null;          // (result) => void - RAW per-window result; the view applies .frc/mains/IMD
    this.onFftAutoStopped = null;  // () => void - stop-after-N tripped
    // Last DISPLAY-rebuild timestamp (Java FftAnalyzerWorker.lastShowNanos, in ms here):
    // the per-tick display throttle gates overlayOnto + recomputeStats + FLL + emit against
    // it so the O(N) finalize runs ≤25 Hz (DISPLAY_MIN_MS) when caught up and ≥2 Hz
    // (DISPLAY_MAX_MS) when behind - keeping the heavy work off the rAF render path.
    this._lastShowMs = 0;

    // Generated-signal change (Java FftAnalyzerWorker invalidateOnGenChange /
    // resetStatisticsAfterSignalChange): on a USER_INPUT change arm the output-drain
    // skip so the first window after the change holds none of the old tone still flowing
    // out of the DAC buffer. An FLL_TRIM is a sub-Hz alignment that must KEEP averaging,
    // so it does NOT arm the drain (it would needlessly blank the view on every trim).
    MessageBus.instance().subscribe(Events.GENERATOR_SIGNAL_CHANGED, (cause) => {
      if (cause === GenChangeCause.FLL_TRIM) return;
      // A user-initiated generator-frequency / form / FFT-length change invalidates
      // the lock - reset the deadbeat loop so it converges fresh from zero (Java
      // FftController.resetFrequencyLock), else its stale correction overshoots the
      // first measurement of the new signal.
      this._fll.reset();
      this._fll2.reset();               // dual-tone second loop resets in lockstep
      this.fllErrHz = 0; this.fllLocked = false; this.fllStable = 0; this.genFreq = this.snapped;
      // And the STATISTICS with them (Java FftView's GENERATOR_SIGNAL_CHANGED subscriber
      // calls resetStatisticsAfterSignalChange, which is resetStatistics plus the drain
      // skip): the averaged spectrum and the analyses count were measured for the OLD
      // signal. Arming the drain alone reset the accumulator but left the counter running,
      // so the readout went on claiming an average depth built from a tone that is gone.
      // Unconditional, like the Java subscriber - the drain skip below is what needs a
      // live reader, not the counters.
      this.resetAnalyses();
      if (this._fftOn) this._armOutputDrainSkip();
    });

    // The multi-tone detect threshold reshapes which peaks count as TONES, and with them how
    // the coherent average is de-rotated - a change invalidates the running accumulator, so
    // the reaction lives HERE with the state it resets, beside the signal-change reset above;
    // the preferences dialog only writes the value (Java FftView:498 wires the same property
    // to resetStatistics, and the field's own tooltip promises "Changing it restarts FFT
    // averaging"). The Property notifies on a real change only, so an OK that re-writes the
    // same threshold stays silent.
    // Guarded on the PROPERTY, not just the object: the engine tests hand in partial
    // preference stubs (backend only), and a stub without this field simply leaves the
    // reaction unwired, like the bare constructions do.
    if (prefs && prefs.fftStrongToneRelDb) prefs.fftStrongToneRelDb.addListener(() => this.resetAnalyses());

    // Self-feed off the LIVE capture: Java consumers subscribe to CAPTURE_BATCH_AVAILABLE and read
    // their own cursor - no central dispatcher pumps us. feedFft self-gates on pausedByStopN and a
    // null reader; only the live capture publishes, so a measurement sweep never triggers it.
    MessageBus.instance().subscribe(Events.CAPTURE_BATCH_AVAILABLE, () => {
      if (this._fftOn) this.feedFft();
    });
  }

  /** Arms the one-shot output-drain skip (Java resetStatisticsAfterSignalChange ->
   *  drainSkipPending -> drainSkipRemaining): discard ~OUTPUT_DRAIN_SKIP_SEC of samples
   *  before the next window so it sees only the new signal. Re-anchors the cursor + the
   *  accumulator like an overrun so the discarded span can't leak into the average. */
  _armOutputDrainSkip() {
    const reader = this._fftReader;
    if (reader) { reader.seekToLatest(); this._absNextSample = reader.getReadPos(); }
    this.bufFilled = 0; this.bufW = 0; this._refill = this.hop;
    this._winAbsStart = 0; this._dispatchedOnce = false;
    this._accum.reset();
    this._accumEpoch++;
    this._workerResetPending = true;
    this._drainSkipRemaining = Math.ceil(OUTPUT_DRAIN_SKIP_SEC * (this.config.inRate || 0));
  }

  // ---- Generator state read + FLL-steered through delegating accessors (this._gen) ----
  get snapped() { return this._gen.snapped; }
  /** Tone 2's emit frequency for a dual-tone form - the same bin-snap state {@link #snapped}
   *  carries for tone 1, so the min / max that pick the dual-tone hints compare like with like
   *  (Java reads the two GenDualToneFreq prefs side by side at FftAnalyzerWorker:1712 / :1754).
   *  Read LIVE off the generator like ScopeController.snapped2 does: retuneGenerator refreshes
   *  the cached gen.snapped on every live tone edit, so both values track the running DDS. */
  get snapped2() { return this._gen._genEmitFreq2(); }
  get binW() { return this._gen.binW; }
  get genNode() { return this._gen.genNode; }
  get _genOn() { return this._gen.running; }
  _computeAnalysisFreqs() { this._gen.computeAnalysisFreqs(); }

  /** True while the FFT is recording. */
  get recording() { return this._fftOn; }
  /** Why the capture ended from below, if THIS consumer claimed the one operator report
   *  (Java FftController.captureEndReason). null otherwise - the pane stays silent. */
  captureEndReason() { return this._captureEndReason; }
  /** True once stop-after-N has paused the feed. */
  get pausedByStopN() { return this._fftPausedByStopN; }

  /** Fraction (0..1) of the data needed for the *current* FFT frame already buffered
   *  - drives the fill-% indicator (Java FftAnalyzerWorker.getNextFrameProgress).
   *  Building the first window needs a full bufLen; once dispatching, each tick needs
   *  one fresh hop, so the bar sweeps 0->1 per hop. 0 when not recording.
   *
   *  Java reads {@code avail / want} off the worker's consuming cursor because the
   *  worker pulls exactly `needed` (first) or `hop` (subsequent) per tick and SLEEPS
   *  between, so the cursor's unread backlog genuinely sweeps 0->want. The web consumer
   *  drains the WHOLE cursor backlog into `buf` every capture batch (feedFft -> _accumulate),
   *  so `reader.available()` is ~0 right after each batch and never sweeps - the live fill
   *  lives in the web consumer's own buffer state instead. So mirror Java's `avail / want`
   *  faithfully against THAT state: bufFilled / bufLen before the first dispatch
   *  (Java winValid==false => avail / winNeeded), then (hop − _refill) / hop per hop after
   *  (Java winValid==true => avail / hop). _dispatchedOnce is the web analog of winValid;
   *  _refill is the samples still owed before the next dispatch. Overrun stays a negative
   *  sentinel, distinct from 0 (no fresh samples), exactly as Java. */
  nextFrameProgress() {
    if (!this._fftOn || !this._fftReader || this.bufLen == null) return 0;
    if (this._fftReader.available() === OVERRUN) return -1.0;   // overrun -> negative, distinct from "no fresh samples"
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

  /** Step 5b parity harness (FOLD_IN_WORKER): assert the worker's SHADOW cross-tick fold
   *  produced the same cumulative spectrum this controller's own fold did. Compared only when
   *  the two depths agree - an async worker can't drop the exact window a mid-run reset drops,
   *  so a reset/glitch transiently offsets the depths (reported once, then re-syncs on the next
   *  clean record). A steady-state |Δ| above 1e-9 dB is a real fold-port bug. */
  _checkFoldParity(r) {
    const mainFrames = this._accum.accumFrames;
    if (r.wAccumFrames !== mainFrames) {
      if (!this._parityDesynced) {
        this._parityDesynced = true;
        console.warn(`[FOLD_IN_WORKER] fold depths desynced (main ${mainFrames} vs worker ${r.wAccumFrames}) - expected after a reset/glitch; restart the FFT to re-test parity`);
      }
      return;
    }
    if (this._parityDesynced) { this._parityDesynced = false; console.info('[FOLD_IN_WORKER] fold depths re-synced'); }
    const a = r.amplitudeDbFs, w = r.wAmp;
    let maxAbs = 0, at = -1;
    for (let k = 0; k < a.length; k++) {
      const dd = Math.abs(a[k] - w[k]);
      if (dd > maxAbs) { maxAbs = dd; at = k; }
    }
    if (maxAbs > 1e-9) console.warn(`[FOLD_IN_WORKER] spectrum parity FAIL: max |Δ|=${maxAbs.toExponential(3)} dB at bin ${at} (depth ${mainFrames})`);
    else if ((this._analysesTicks & 127) === 0) console.info(`[FOLD_IN_WORKER] parity OK: max |Δ|=${maxAbs.toExponential(3)} dB (depth ${mainFrames})`);
  }

  /** FFT averages accumulated since the last reset (predistortion host) - the
   *  per-tick analysis count (Java getCompletedAnalyses / completedAnalyses). */
  completedAnalyses() { return this._analysesTicks; }
  /** Cross-tick accumulator frame depth since the last reset (Java
   *  getAccumulatedFrames) - the predistortion frame-depth readout, distinct from
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
    if (this._accum) { this._accum.reset(); this._accumEpoch++; this._workerResetPending = true; }
  }

  /** Selects which ADC channel the FFT ANALYZES (Java FftView button ->
   *  viewPrefs.setFftChannel -> the fftChannelProperty subscription ->
   *  resetStatistics). L -> ch0 (left), R -> ch1 (right). A real change resets
   *  the statistics + accumulator so collection restarts from 0 (Java
   *  FftView:439; the accumulator/count/epoch reset is the web analog of the
   *  worker's window rebuild on a channel change, Java FftAnalyzerWorker:1567).
   *  No-op when the channel is unchanged. */
  setFftChannel(ch) {
    const wl = ch === 'L';
    if (wl === this._wantLeft) return;   // no change -> keep averaging
    this._wantLeft = wl;
    this.config.channel = ch;            // keep the config the read/emit consult in sync
    // Re-pick the analyzed channel's ADC dBV offset so the manual-fundamental anchor
    // (Java FftAnalyzerWorker:2005 getDbvOffsetDb(getFftChannel())) stays per-channel-correct
    // on a live switch (both offsets were snapshotted at readConfig).
    if (this.config.dbvOffsetDbRight != null) {
      this.config.dbvOffsetDb = (ch === 'R') ? this.config.dbvOffsetDbRight : this.config.dbvOffsetDbLeft;
    }
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
      this._captureEndReason = null;   // a fresh session invalidates the old terminal
      const reader = await this._capture.acquire();
      if (!reader) { this._teardownFftWorkers(); return false; }
      this._fftReader = reader;
      this._fftReader.seekToLatest();   // contiguous stream anchors at "now"
      this._absNextSample = this._fftReader.getReadPos();
      this._fll.reset();                // fresh Record session -> converge alignment from zero
      this._fll2.reset();               // ...and its dual-tone second loop
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
   *  per-dispatch as `threads`: W = 1 -> the classic single "buffer-and-analyze"
   *  analyze() inside the worker; W > 1 -> the WORKER coordinates its own nested
   *  fft-pool-worker.js pool (prelude -> split W contiguous frame ranges -> gather
   *  -> merge -> finalize, all in-worker). The main thread posts ONE transferred
   *  message either way - Java parity: FftAnalyzerWorker.parallelChunks runs on
   *  the analyzer thread, never the UI thread. */
  _setupFftAnalysis() {
    const c = this.config;
    this.N = c.fftSize;
    // ∞ (forever) averaging is a true cumulative mean; a finite N is a ring window.
    // app.js passes averages = Infinity for the ∞ toggle (Java AVERAGES_SERIES ∞).
    this.foreverMode = c.averages === Infinity || !Number.isFinite(c.averages);
    this.ringN = this.foreverMode ? 1 : Math.max(1, c.averages | 0);
    // The on-screen "N×" depth target: ∞ -> unbounded; a ring -> its N. (The Java
    // worker caps the ring accumulator at 2·N frames since ~2 frames land per
    // coherent tick; here avgTarget is the displayed N and the accumulator's
    // targetN scaling - see _onWorkerResult - bounds the depth to it.)
    this.avgTarget = this.foreverMode ? Infinity : this.ringN;
    this._computeAnalysisFreqs();
    const overlapFrac = OVERLAP_FRACTION[c.overlap] || 0.0;
    this.hop = Math.max(1, Math.round(this.N * (1 - overlapFrac)));
    this.overlapPct = overlapFrac * 100;

    // Per-tick window = N + (perTickFrames−1)·hop samples - sized to ~1-2 frames
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
    // Fresh Record session -> fresh accumulator (Java start() resetStatistics).
    // Seed the multi-tone detect threshold live from the pref (Java reads
    // Preferences#getFftStrongToneRelDb in detectStrongTones); a running change
    // re-pushes via setStrongToneRelDb (app.js pref-change listener).
    this._accum.setStrongToneRelDb(this.config.fftStrongToneRelDb ?? 100.0);
    this._accum.reset();
    this._accumEpoch++;
    this._workerResetPending = true;
    this._lastAccumulate = false;
    this._lastForeverMode = false;
    this._lastRingN = -1;
    // Absolute sample position of the window currently in `buf` (for the cross-tick
    // de-rotation delta). The first dispatched window starts at 0; each later one
    // advances exactly one hop - a uniform delta keeps the per-lobe rotation exact.
    this._winAbsStart = 0;
    this._dispatchedOnce = false;
    this._drainSkipRemaining = 0;   // fresh session anchors clean - nothing to drain

    // Generator-locked FLL bookkeeping shared with the result handler - a fresh Record session
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
    // channel = prefs.getFftChannel(); wantLeft = channel == L). L -> ch0 (left),
    // R -> ch1 (right). feedFft reads THIS channel; _emit stamps it into
    // r.channelLeft so the .frc de-embed picks the matching curve.
    this._wantLeft = (this.config.channel === 'L');

    this.warmup = Math.round(c.inRate * Math.max(0, c.warmupMs) / 1000);
    this._lastShowMs = 0;   // fresh session -> the first result displays immediately (Java lastShowNanos 0)
    this._fftFps = 0; this._fftCount = 0; this._fftWinT0 = 0; this._workerMs = 0;
    this._busy = false; this._dispatchId = 0; this.dropped = 0; this._refill = this.hop;

    // Worker-pool sizing - see _poolSizeFor. The pool itself lives INSIDE
    // fft-worker.js (nested workers) - the main thread only posts one message per tick,
    // identical to the serial path, so threads>1 can't starve the UI (Java parity:
    // FftAnalyzerWorker.parallelChunks runs on the analyzer thread, never the UI thread).
    this.poolSize = this._poolSizeFor(c.threads);
    // Seed the align-transition latch so _syncLiveConfig only resets the FLL on a real
    // OFF->ON flip (the fresh-session reset already runs in setRecording).
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
   *  chunks = max(2, availableProcessors−1)). Java NEVER uses every logical core -
   *  it always leaves one for the rest of the app, so the UI/audio threads keep
   *  running; the web mirrors that: cap at hardwareConcurrency−1 (floor 1). */
  _poolSizeFor(threads) {
    const hw = navigator.hardwareConcurrency || 4;
    const coreCap = Math.max(1, hw - 1);
    return Math.max(1, Math.min(16, coreCap, threads | 0));
  }

  /** Live re-read of the running config, called once per capture batch (feedFft).
   *  The Java worker re-reads Preferences every tick, so a NON-STRUCTURAL settings
   *  change applies on the next tick with NO statistics reset - Java
   *  FftTabControl.java:377-380: "Overlap only changes the hop, not the
   *  spectrum/accumulator - refresh the tab tile but DON'T reset the average; the
   *  worker adapts next tick", and :391-397 (averages): "No reset here: the worker
   *  resets the average only on a ring↔∞ switch or a smaller ring (a larger ring
   *  keeps the depth)". The web consumer sized its hop/buffer/pool at setup only,
   *  so those changes used to need a full restartFft - which RESET the accumulator.
   *  This re-derives them live instead:
   *   - POOL SIZE (web-only #threads): the worker reads `threads` per dispatch
   *     message (fft-worker.js:151), so a live resize needs no worker rebuild.
   *   - AVERAGING targets (∞/ring): the depth transition itself stays in
   *     _onWorkerResult (lastAccumulate/lastForeverMode/lastRingN - the exact Java
   *     discard rules), so a larger ring KEEPS the collected depth.
   *   - HOP / window geometry (overlap): the analysis ring is rebuilt and re-fills
   *     (≲ bufLen/inRate s gap) but the ACCUMULATOR IS KEPT - the epoch bump only
   *     drops the in-flight old-geometry window, and onResync() re-anchors the κ
   *     slope + PLL across the timebase jump (the same keep-depth re-anchor the
   *     spectral-discontinuity rejector uses).
   *   - ALIGN OFF->ON: reset the deadbeat loop so alignment converges fresh (Java
   *     FftView.java:465-466 -> FftController.resetFrequencyLock,
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
      this._workerResyncPending = true;
    }
    if (c.fllOn && !this._lastFllOn) { this._fll.reset(); this._fll2.reset(); }
    this._lastFllOn = !!c.fllOn;
  }

  /** Per-batch: pull the NEW contiguous R-channel (ch1) samples from the cursor and
   *  feed them through the buffer-and-analyze accumulate+dispatch loop. On OVERRUN
   *  (the writer lapped the cursor - the worker fell a full ring behind) re-anchor
   *  at the latest sample and reset the fill state, exactly as the cross-tick
   *  accumulator must (a torn window would smear the fundamental). */
  feedFft() {
    // Stop-after-N pause (Java FftAnalyzerWorker.workerLoop: `if (!paused.get())`
    // gates doAnalysis() - once stop-after-N trips paused, NO further tick runs, so
    // no more windows are buffered/dispatched). Mirror that here: gate the whole
    // per-batch feed while paused so the unthrottled heavy-rebuild burst that starves
    // the scope rAF can't happen (the tripping tick already showed its final frame via
    // showNow). Cleared on a fresh setRecording(true) / resetStatistics restart.
    if (this._fftPausedByStopN) return;
    const reader = this._fftReader;
    if (!reader) return;
    // The capture DIED under us (Java FftAnalyzerWorker.doAnalysis, first thing after the reader
    // snapshot): a reader cannot tell a dead lane from a quiet one, so the writer says so and
    // this consults it. Claim the reason for the ONE operator report - the first consumer across
    // scope and FFT gets it, the other stops silently - then stop analysing and let the pane
    // tear the consumer down (FFT_RECORDING_AUTO_STOPPED -> disengageRecord), which is what
    // releases the shared capture.
    if (reader.isFinished()) {
      this._captureEndReason = reader.takeFinishedReasonForReport();
      MessageBus.instance().publish(Events.FFT_RECORDING_AUTO_STOPPED);
      return;
    }
    // Live settings re-derive (overlap hop / averages targets / threads pool / align
    // transition) - the Java worker re-reads Preferences every tick; NO reset here.
    this._syncLiveConfig();
    let avail = reader.available();
    if (avail === OVERRUN) { this._onOverrun(reader); return; }
    if (avail <= 0) return;
    // Output-drain skip (Java FftAnalyzerWorker): after a signal-change re-anchor,
    // consume and DISCARD the span still carrying the old tone (the DAC buffer keeps it
    // flowing into the ADC after the change) so the first window - and the FLL's first
    // measurement - see only the new signal. Discarding reads into null buffers (advances
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
    // read) - the timebase the FLL transport gate compares window starts against.
    const absBase = reader.getReadPos();
    // Read the SELECTED channel (Java FftAnalyzerWorker:1592
    // rdr.read(needed, wantLeft ? winBuf : null, wantLeft ? null : winBuf)):
    // L -> ch0 fills the left arg, R -> ch1 fills the right arg. Consuming forward read.
    const n = this._wantLeft ? reader.read(avail, tmpR, null) : reader.read(avail, null, tmpR);
    if (n === OVERRUN) { this._onOverrun(reader); return; }
    this._absNextSample = absBase;
    this._accumulate(tmpR, n);
  }

  /** Ring overrun: the writer lapped the cursor (the worker fell a full ring
   *  behind). Re-anchor at "now" and rebuild the window from a fresh contiguous
   *  span. Like the Java onCaptureOverrun, this RE-ANCHORS the accumulator's
   *  reference (a torn window would inject a phase jump) - bump the epoch so the
   *  worker result for the discarded window is dropped, and reset the accumulator
   *  so deep averaging restarts from the fresh unbroken span. */
  _onOverrun(reader) {
    reader.seekToLatest();
    this._absNextSample = reader.getReadPos();
    this.bufFilled = 0; this.bufW = 0; this._refill = this.hop;
    this._winAbsStart = 0; this._dispatchedOnce = false;
    this._accum.reset();
    this._accumEpoch++;
    this._workerResetPending = true;
  }

  /** Recovery for a detected in-window signal discontinuity (Java FftAnalyzerWorker
   *  .onSignalDiscontinuity) - the same re-sync as a ring overrun: discard the glitched
   *  window, re-anchor to "now" and rebuild, but KEEP the running average (onResync
   *  re-anchors the κ slope / PLL - the web analog of Java's kappaSkipNext /
   *  multiKappaSkipNext / gapRecoverPending; the de-rotation absorbs the coverage gap).
   *  Reuses the drain-skip: the next feed consumes + discards POST_GLITCH_SKIP_SEC of
   *  samples before rebuilding the window, so the rebuild starts past the glitch's END,
   *  not at its detected start (max keeps a larger pending drain). Shared by the
   *  time-domain and the spectral gates, exactly as Java. Publishes the re-sync banner
   *  message-key (Java publishCaptureBanner - same event + i18n key). */
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
    this._workerResyncPending = true;
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
        // First dispatched window starts at 0; each later one advances one hop -
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
   *  tracks the live mains fundamental, then - per the configured mode - either
   *  filters the window in the time domain (SYNC_SUBTRACT / LMS) or records the
   *  tracked f0 for the PLOT-time spectral comb correction (IIR_COMB; window left
   *  raw so the coherent average is undisturbed).  Returns the dBFS spectrum the
   *  IIR comb correction must be applied to at render time, via this._fftMainsF0.
   *
   *  SYNC_SUBTRACT / LMS remove the hum from the window IN PLACE before the FFT
   *  (Java mainsTimeFilter path): both subtract only the additive hum, leaving the
   *  test tone's amplitude and phase intact, so the coherent de-rotation / average
   *  is undisturbed.  The filter instance is PERSISTENT (its template / taps adapt
   *  tick to tick) and `absCapStart` - the window's absolute capture position (Java
   *  samplesAbsStart) - keeps the period-locked state phase-aligned across the
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
      // Plot-time spectral correction - leave the window (and the worker
      // accumulator) raw; remember the locked fundamental for _onWorkerResult.
      this._fftMainsF0 = comb.isTuned() ? comb.getMainsHz() : 0;
      return;
    }
    // SYNC_SUBTRACT / LMS -> true time-domain canceller pre-FFT (Java
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
   *  fundamental" mode - the canonical Vrms the user typed, resolved to its dBV
   *  anchor (20·log10 Vrms) then to dBFS at this boundary (minus dbvOffsetDb).
   *  NaN (no anchor - auto-detect the fundamental) unless manual-fundamental
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
    // Step 5 fold-control (main->worker): the values the cross-tick fold needs so the worker can
    // run a lockstep SHADOW copy for the FOLD_IN_WORKER parity harness (§5b). resetAccum /
    // resyncAccum are one-shots fired wherever this controller resets / re-anchors its OWN
    // accumulator, so the shadow tracks it. Ignored by the worker unless foldInWorker.
    const forever = this.foreverMode, ringN = this.ringN;
    const resetAccum = this._workerResetPending; this._workerResetPending = false;
    const resyncAccum = this._workerResyncPending; this._workerResyncPending = false;
    // What the generator says it EMITS, at THIS analyzer's rate - asked, not re-derived (Java
    // FftAnalyzerWorker requests GENERATOR_EMITTED_HZ under the same genActive + fund-from-gen
    // gate). null (or a 0 for "no such tone") withholds the hint entirely, which is what makes
    // the analyzer auto-detect an external tone instead of pinning its search to a nominal.
    const emitted = (this._genOn && c.fftFundFromGenerator)
      ? MessageBus.instance().request(Events.GENERATOR_EMITTED_HZ, c.inRate) : null;
    const hintHz = (hz) => (hz > 0 ? hz : NaN);
    this.worker.postMessage({
      id: this._dispatchId++, samples: snap, sampleRate: c.inRate, fftSize: this.N,
      harmonicCount: c.harmonicCount, windowType: c.window, overlap: c.overlap,
      snrFreqMin: c.snrFreqMin || 0, snrFreqMax: c.snrFreqMax || 0, coherentAveraging: this.config.coherent,
      // Generator FREQUENCY hint - gated on the generator ACTUALLY RUNNING as well as the
      // pref, exactly as Java (FftAnalyzerWorker:1697-1714: `genActive && prefs
      // .isFftFundFromGenerator()`, whose comment reads "genActive still gates the generator
      // FREQUENCY hints below, which do need it"). With the generator idle the tone is
      // EXTERNAL and its frequency is unknown, so hinting the commanded value pins the
      // analyzer's ±10-bin fundamental search (fft-analyzer.js:241-246 / :572-583) to a
      // nominal the captured tone need not be near - the fundamental LEVEL and the THD
      // denominator are then read off the main lobe's skirt while the harmonic grid (which
      // rides the measured kFractional) stays correct.
      // ...and in DUAL tone the anchor is the LOWER tone whichever box the user typed it in
      // (Java :1709-1714 `dualTone ? Math.min(genDualToneFreq1Hz, genDualToneFreq2Hz) :
      // genFrequencyHz`). Hinting tone 1 makes the whole measurement ENTRY-ORDER dependent:
      // with F1 = 7 kHz / F2 = 1.1 kHz the ±10-bin search latches the UPPER tone, so
      // fundamentalHzRefined / fundamentalDbFs report the 7 kHz tone and the harmonic grid
      // walks 14 kHz, 21 kHz ... instead of 2.2 kHz, 3.3 kHz ... - a different THD off the same
      // signal purely because the two numbers were swapped between the fields.
      expectedFundHz: emitted == null ? NaN
        : hintHz(dual ? Math.min(emitted[0], emitted[1]) : emitted[0]),
      fundRefDbFs: this._fundRefDbFs(),   // THD manual-fundamental anchor (NaN = auto-detect)
      // Second-tone hint - the UPPER tone, gated on exactly the same generator-running + pref
      // pair as the fundamental hint (Java FftAnalyzerWorker:1752-1755 `genActive && prefs
      // .isFftFundFromGenerator() && dualTone`). Its search window is only ±2 bins
      // (fft-analyzer.js:303-306), 5× tighter than the fundamental's, so an EXTERNAL dual tone
      // whose upper tone sits off the commanded nominal resolves fundamental2HzRefined onto a
      // noise bin: every IMD product frequency derives from that pair and readBinVrms reads the
      // EXACT nearest bin, so the whole product grid then reads the noise floor. Withheld, the
      // analyzer auto-detects the real second tone from the clean frame (fft-analyzer.js:285-301).
      multiTone: dual,
      secondToneHintHz: (emitted != null && dual)
        ? hintHz(Math.max(emitted[0], emitted[1])) : NaN,
      threads: this.poolSize,   // >1 -> the worker fans out to its nested pool
      // Time-domain discontinuity gate (Java: if (USE_TIME_DISCONTINUITY && accumulate)):
      // the raw window lives in the worker after this transfer, so the worker runs the
      // detector pass - but only when this tick will accumulate, exactly as Java.
      // ...and only when the user leaves the gate ON (Java FftAnalyzerWorker:1818 also requires
      // prefs.isFftDetectTimeDiscontinuity()). Read from the live config so an unchecked box takes
      // effect mid-record without a restart. undefined (pre-readConfig) defaults to ON.
      timeGate: USE_TIME_DISCONTINUITY && (this.foreverMode || this.ringN >= 2)
        && this.config.fftDetectTimeDiscontinuity !== false,
      // Step 5b shadow-fold control (ignored unless foldInWorker).
      foldInWorker: this._foldInWorker,
      winAbsStart: this._tickAbsStart,
      accumulate: forever || ringN >= 2, targetN: forever ? Infinity : 2 * ringN,
      spectralGate: true, strongToneRelDb: this.config.fftStrongToneRelDb ?? 100.0,
      resetAccum, resyncAccum,
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
    // only when the new bound discards collected depth - a flip, a ring↔∞ switch,
    // or a SMALLER ring. (A larger ring just widens the exponential window.)
    if (accumulate !== this._lastAccumulate
        || forever !== this._lastForeverMode
        || (!forever && ringN < this._lastRingN)) {
      this._accum.reset();
      this._workerResetPending = true;
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
      // itself ran in fft-worker.js - the raw window lives there after the transfer,
      // and the tick's own refined fundamental pins the recurrence prediction exactly,
      // so the reject threshold rides on the noise floor at any signal frequency; NaN
      // (no tone) self-estimates. On detect -> the same re-sync + banner as the
      // spectral gate below, and the block is dropped before it can fold in.
      if (USE_TIME_DISCONTINUITY && r.timeDiscontinuity) {
        console.info('Time-domain discontinuity in the tick window - re-sync');
        this._onSignalDiscontinuity();
        return;
      }
      // Frequency-domain glitch / stall rejection (Java FftAnalyzerWorker): compare
      // this tick's spectrum to the running-median reference and re-sync (re-anchor
      // past the glitched overlap) BEFORE it can poison the cross-tick vector average.
      // Java runs this for ANY accumulate tick (coherent OR incoherent) - not gated on
      // the coherent flag. In the incoherent path the analyzer stores the per-window
      // magnitude in r.re (im = 0), so the same magnitude-domain gate applies. On a
      // reject the shared recovery (Java onSignalDiscontinuity) re-anchors the cursor
      // to "now", arms the 5 ms post-glitch drain skip, re-anchors the accumulator's
      // κ slope / PLL (onResync - the collected depth survives) and bumps the epoch so
      // the discarded window can't fold in.
      {
        const binW = r.freqResolution;
        const peakBins = this._fundamentalBins(r);
        if (this._accum.reject(r.re, r.im, r.fftSize / 2, binW, peakBins)) {
          const d = this._accum.spectralDiagnostics;
          debug('[fft] spectral gate reject - gates ' + JSON.stringify(d.lastGates)
            + ` score ${d.lastScore != null ? d.lastScore.toFixed(2) : '?'} thr ${d.lastScoreThresh != null ? d.lastScoreThresh.toFixed(2) : '?'}`
            + ` | power ${d.lastPowerDb != null ? d.lastPowerDb.toFixed(1) : '?'} med ${d.lastPowerMed != null ? d.lastPowerMed.toFixed(1) : '?'} thr ${d.lastPowerThresh != null ? d.lastPowerThresh.toFixed(1) : '?'}`
            + ` | pedestal ${Number.isFinite(d.lastPedestalExcess) ? d.lastPedestalExcess.toFixed(1) : '-'} thr ${Number.isFinite(d.lastPedestalThresh) ? d.lastPedestalThresh.toFixed(1) : '-'}`);
          this._onSignalDiscontinuity();
          return;
        }
      }
      accumulated = this._accum.accumulate(r, this._tickAbsStart, coherent, targetN);
    }

    // Per-tick bookkeeping runs EVERY accepted tick (Java completedAnalyses++ /
    // firstFrameDone = true): the analysis-TICK count is what stop-after-N and the
    // predistortion host key off. Reached only past the epoch + reject gates above,
    // so rejected / straddling ticks don't count - exactly as the desktop.
    this._analysesTicks++;

    // Per-tick depth readout. In ∞ mode the displayed "N×" is the accumulator depth
    // in tick-equivalents (accumFrames / framesPerTick) - unbounded, it climbs with
    // every fold. A finite ring CANNOT read its depth for this: the exponential
    // window saturates at exactly the target depth, and the emitted readout
    // subtracts the seed tick (framesDone - 1 below) - depth-derived, the moving
    // average was stuck one short of N forever. The ring therefore counts PROCESSED
    // ticks, as the desktop's fill readout does (completedAnalyses - 1, capped at
    // N). With no averaging (ringN = 1) there is no accumulator; show the single
    // window's own frame count (1/1).
    const accumFrames = this._accum.accumFrames;
    if (accumulate) {
      const depthTicks = Math.round(accumFrames / perTickFrames);
      this.framesDone = forever ? depthTicks : Math.min(this.avgTarget + 1, this._analysesTicks);
      // Frame depth (Java getAccumulatedFrames) - the predistortion frame-depth readout,
      // NOT the stop-after-N / completedAnalyses key (that's the per-tick count above).
      this._analysesDone = accumFrames;
    } else {
      this.framesDone = Math.min(this.avgTarget, r.frameCount);
      this._analysesDone += r.frameCount;
    }

    // Stop-after-N (Java FftAnalyzerWorker): ONLY in ∞ (forever) mode, when the cap is enabled
    // and the count THE USER SEES reaches it - pause the consumer and notify the UI so it can
    // un-light the Record LED (FFT_RECORDING_AUTO_STOPPED -> disengageRecord).
    //
    // Gate on the DISPLAYED count, not the raw tick counter: the label shows
    // Math.max(0, framesDone − 1) (emitted below; the first tick is the seed, not an average),
    // so keying off _analysesTicks stopped one displayed average early - entering 50 stopped
    // with "49" on screen. Java fixed the same off-by-one as (completedAnalyses − 1) >= N, where
    // its two counters coincide; here they need not: framesDone is the accumulator depth in
    // tick-equivalents (round(accumFrames / perTickFrames)) while _analysesTicks is a raw count,
    // so comparing the displayed expression is correct under both.
    const displayedAverages = Math.max(0, this.framesDone - 1);
    if (forever && this.config.stopAfterNEnabled && !this._fftPausedByStopN
        && displayedAverages >= this.config.stopAfterN) {
      this._fftPausedByStopN = true;
      if (this.onFftAutoStopped) { try { this.onFftAutoStopped(); } catch (_) {} }
    }

    // Per-tick DISPLAY TRIM (Java FftAnalyzerWorker doAnalysis tail, the showNow gate):
    // the accumulator above ran THIS tick, but the O(N) display rebuild below - overlayOnto
    // + recomputeStats + FLL + emit - does NOT need to. Skip it while the capture backlog is
    // high (catch up rather than overrun), throttle it to DISPLAY_MIN_MS when caught up, but
    // force it by DISPLAY_MAX_MS so the view never freezes. This is the fix for the FFT
    // starving the scope to ~1 fps and a mid-capture cal load hanging the UI:
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
    // overlayAccumulatorOnto + recomputeStats), not off the single window - so the
    // shown spectrum is the deep cumulative average, with THD/SNR re-derived from it.
    if (accumulated) {
      this._accum.overlayOnto(r);            // r.amplitudeDbFs/re/im <- cumulative average
      // Step 5b: compare THIS main fold's cumulative spectrum to the worker's shadow fold
      // (both pure overlayOnto outputs, pre-recompute) - the correctness gate before flipping
      // the fold into the worker. Only when the depths agree (a mid-run reset/glitch transiently
      // desyncs the async worker; that is reported, not a failure).
      if (this._foldInWorker && r.wAmp) this._checkFoldParity(r);
      this._coordAnalyzer.recomputeStats(r); // re-derive fundamental/harmonics/THD/SNR
      r.frameCount = this._accum.accumFrames;
      // Pinned coherent κ for the plot-time "before" dots (NaN => single tick).
      r.coherentKappa = this._accum.hasData ? this._accum.accumKFractional : NaN;
    }

    // The FFT pane + the predistortion read the EMITTED result. The worker->main transfer is a
    // plain, lossy snapshot, so (1) re-adopt the FftResult prototype (methods rawHarmonicDbFs /
    // deepCopy / noisePeakFloorDbFs) and (2) re-stamp the RAW (pre-.frc-de-embed) fundamental +
    // peak phasors off the deep-averaged spectrum HERE - before fftViewCorrection.apply (run in
    // engine.onResult AFTER _emit) de-embeds re/im in place. Mirrors Java FftAnalyzer, which
    // captures the raw peaks after averaging and before the .frc de-embed.
    r = FftResult.adopt(r);
    r.captureRawPeaks();

    // FLL - steer the generator so each captured tone returns to its exact target bin
    // (nulls the DAC↔ADC clock offset). Driven through the Java FrequencyFll deadbeat loop:
    // ONE bounded correction per fully-observed transport round trip, NOT a per-frame
    // proportional integrator. The published generator frequency is snapped + correction.
    // Only steers when the generator is actually running. Java FftController.applyFrequencyLock
    // branches on the form: single-tone runs one loop off slot.fundamentalHzRefined; DUAL_TONE
    // runs TWO independent loops (fll / fll2) off the per-tone detected frequencies imd.f1Hz /
    // imd.f2Hz and publishes GENERATOR_FREQ_TRIM / _2. This controller mirrors that with
    // _fll / _fll2, refining the two tones here (the CONTROLLER holds no per-tone estimate - the
    // IMD table runs in the view - so the steer refines them itself off r.amplitudeDbFs).
    if (this.config.fllOn && this._genOn && this.genNode) {
      if (isDualTone(this.config.form)) {
        this._steerDualTone(r);
      } else if (r.fundamentalHzRefined > 0) {
        this._steerSingleTone(r);
      } else if (traceFll()) {
        console.warn(`FLL SKIP: fllOn=${!!this.config.fllOn} genOn=${this._genOn}`
          + ` genNode=${!!this.genNode} fundRefined=${r.fundamentalHzRefined}`);
      }
    } else if (traceFll()) {
      // Steering skipped entirely: report which precondition failed (fllOn / generator
      // running / generator node) - the classic "inert" path.
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

  /** Single-tone FLL steer (Java FftController.applyFrequencyLock else-branch, :313-327):
   *  drives the ONE loop off the refined fundamental. FLL TARGET = the entered tone snapped to
   *  the CAPTURE-rate FFT bin grid (r.sampleRate). Java snaps to slot.sampleRate (the FFT/capture
   *  rate); its DAC and ADC share one exclusive-mode clock so that equals the output rate and the
   *  loop lands on the FFT bin. The web's generator (outCtx) and capture (inCtx) are two INDEPENDENT
   *  AudioContext clocks (~80 ppm apart), so targeting this.snapped (the OUTPUT-rate bin) locks the
   *  tone to the wrong grid and Δosc stays ≈80 ppm. Targeting the capture-rate bin steers the
   *  CAPTURED tone onto the actual FFT bin -> Δosc nulls. The DAC still plays this.snapped;
   *  fll.correction bridges the gap. */
  _steerSingleTone(r) {
    // Plausibility gate (Java plausibleFllMeasurement): clock drift is ppm-scale, so a
    // measurement farther than max(5 bins, 500 ppm) from the target is a mis-measurement
    // (a window still draining the OLD signal after a form/freq switch, a harmonic mis-lock,
    // a capture glitch) - skip steering so it can't trim the live generator off.
    const fllTarget = this._gen.snapToRate(r.sampleRate);
    const maxErrHz = Math.max(FLL_MAX_ERROR_BINS * this.binW, fllTarget * FLL_MAX_ERROR_PPM * 1e-6);
    if (Math.abs(r.fundamentalHzRefined - fllTarget) <= maxErrHz) {
      this.fllErrHz = r.fundamentalHzRefined - fllTarget;
      // Java FftController.applyFrequencyLock (:322-324): fll.update folds this measurement into
      // the deadbeat + transport gate, then publishes `target + correction` where target is the
      // CAPTURE-rate snap (slot.sampleRate) - NOT the output-rate base.
      const gateBefore = this._fll.correctionVisibleFrom;
      this._fll.update(fllTarget, r.fundamentalHzRefined,
        this._tickAbsCapStart, this._tickWritePos, this.config.inRate, this.N);
      this.genFreq = fllTarget + this._fll.correction;
      // Publish the trim; GeneratorController applies it to its own worklet (was a direct post).
      MessageBus.instance().publish(Events.GENERATOR_FREQ_TRIM, this.genFreq);
      if (traceFll()) {
        // Java log.warn("FLL t1: target=... meas=... corr=... pub=..."), plus the transport timebase +
        // gate so an inert loop is diagnosable (gate held forever => winStart never reaches
        // visibleFrom; corr stuck at 0 => lock-band/plausibility hold).
        const held = this._fll.correctionVisibleFrom >= 0
          && this._tickAbsCapStart < this._fll.correctionVisibleFrom;
        console.warn(`FLL t1: target=${fllTarget.toFixed(6)} meas=${r.fundamentalHzRefined.toFixed(6)}`
          + ` corr=${this._fll.correction >= 0 ? '+' : ''}${this._fll.correction.toFixed(6)}`
          + ` pub=${this.genFreq.toFixed(6)} winStart=${this._tickAbsCapStart} writePos=${this._tickWritePos}`
          + ` gate[${gateBefore}->${this._fll.correctionVisibleFrom}] ${held ? 'HELD' : 'open'}`);
      }
      // Status-display lock latch ONLY (Java ALIGN_DONE_PPM aligned flag): lights the "locked"
      // readout once the measured error sits within tolerance. It does NOT gate the steering -
      // the deadbeat loop's own transport gate provides the damping, and the loop keeps
      // correcting ongoing DAC↔ADC clock drift.
      this._updateLockLatch(Math.abs(this.fllErrHz / this.binW) < 5e-4);
    } else if (traceFll()) {
      // Java log.warn("FLL t1 GATED: target=... meas=...") - the plausibility gate rejected this
      // measurement (|meas − target| beyond max(5 bins, 500 ppm)); no steer this frame.
      console.warn(`FLL t1 GATED: target=${fllTarget.toFixed(6)} meas=${r.fundamentalHzRefined.toFixed(6)}`
        + ` errHz=${(r.fundamentalHzRefined - fllTarget).toFixed(6)} maxErrHz=${maxErrHz.toFixed(6)}`);
    }
  }

  /** Dual-tone FLL steer (Java FftController.applyFrequencyLock dual branch, :263-312): TWO
   *  independent deadbeat loops (_fll / _fll2) steer tone 1 and tone 2 to their own capture-rate
   *  bin targets and publish GENERATOR_FREQ_TRIM / GENERATOR_FREQ_TRIM_2. Java reads the per-tone
   *  frequencies from imd.f1Hz / imd.f2Hz; the web controller holds no IMD result (the table runs
   *  in the view), so it refines the two tones HERE off the current spectrum r.amplitudeDbFs with
   *  the same quadratic peak refinement ImdAnalyzer uses (refinePeak over ±TONE_SEARCH_BINS around
   *  each capture-rate-snapped target). Refining only the two tones - NOT the full IMD product
   *  table - is deliberate: the FLL only needs the fundamentals, and tone frequencies are
   *  calibration-independent, so the RAW controller-side (pre-.frc-de-embed) spectrum is the
   *  correct input here (the .frc de-embed alters magnitudes, not peak positions). The tone-1 loop
   *  keeps feeding the shared fllErrHz / fllLocked / genFreq readout; the locked latch requires
   *  BOTH tones in tolerance (Java aligned :309-311). */
  _steerDualTone(r) {
    const amp = r.amplitudeDbFs;
    const binBw = r.freqResolution;
    if (amp == null || !(binBw > 0)) {
      if (traceFll()) console.warn('FLL dual SKIP: no spectrum (amplitudeDbFs/freqResolution)');
      return;
    }
    // Targets: each entered tone snapped to the CAPTURE-rate bin grid (Java t1 / t2,
    // FftBinSnap.snapIfEnabled(DUAL_TONE, slot.sampleRate, ...)).
    const t1 = this._gen.snapToRate(r.sampleRate);
    const t2 = this._gen.snapToRate2(r.sampleRate);
    // Per-tone measured frequency: the analyzer's COHERENTLY-REFINED clean-frame sub-bin
    // estimates (r.fundamentalHzRefined for fLow, r.fundamental2HzRefined for fHigh - the same
    // honest per-tone values ImdAnalyzer.f1Hz/f2Hz carry), with the quadratic refinePeak of the
    // collapsed spectrum ONLY as the fallback when the refined estimate is unavailable. This is a
    // 1:1 mirror of Java ImdAnalyzer (:99-102): steering from the argmax+quadratic estimate left F2
    // with a static ppm error and made the loop wobble/diverge; the refined value nulls it.
    // p1/p2 are keyed to fLow/fHigh (ImdAnalyzer's ordering) so they remain the matching fallback.
    const p1 = refinePeak(amp, binBw, Math.min(t1, t2), TONE_SEARCH_BINS);
    const p2 = refinePeak(amp, binBw, Math.max(t1, t2), TONE_SEARCH_BINS);
    // The refined pair arrives in ANALYZER-SLOT order (slot 1 = the detector's primary,
    // which is the HIGHER tone when the user enters tones high-first) - sort it onto the
    // fLow/fHigh roles before pairing with the targets, or each loop steers against the
    // OTHER tone's measurement (verified with F1=7 kHz / F2=1.3 kHz; same re-pairing as
    // imd-analyzer.js analyzeImd).
    const ref1 = (r.fundamentalHzRefined > 0.0) ? r.fundamentalHzRefined : NaN;
    const ref2 = (Number.isFinite(r.fundamental2HzRefined) && r.fundamental2HzRefined > 0.0)
      ? r.fundamental2HzRefined : NaN;
    const pLow = p1 != null ? p1.freqHz : NaN;
    const pHigh = p2 != null ? p2.freqHz : NaN;
    let fLowHz, fHighHz;
    if (Number.isFinite(ref1) && Number.isFinite(ref2)) {
      fLowHz = Math.min(ref1, ref2);
      fHighHz = Math.max(ref1, ref2);
    } else {
      const ref = Number.isFinite(ref1) ? ref1 : ref2;   // at most one refined estimate
      if (!Number.isFinite(ref)) { fLowHz = pLow; fHighHz = pHigh; }
      else if (Math.abs(ref - pLow) <= Math.abs(ref - pHigh)) { fLowHz = ref; fHighHz = pHigh; }
      else { fLowHz = pLow; fHighHz = ref; }
    }
    const f1Hz = (t1 <= t2 ? fLowHz : fHighHz);
    const f2Hz = (t1 <= t2 ? fHighHz : fLowHz);

    const ok1 = this._steerOneDualLoop(this._fll, Events.GENERATOR_FREQ_TRIM, t1, f1Hz, 't1');
    const ok2 = this._steerOneDualLoop(this._fll2, Events.GENERATOR_FREQ_TRIM_2, t2, f2Hz, 't2');

    // Tone-1-based readout stays the shared display state (genFreq / fllErrHz), so the existing
    // Δosc / ppm columns keep reading tone 1; only the locked LATCH couples both loops.
    if (ok1) {
      this.fllErrHz = f1Hz - t1;
      this.genFreq = t1 + this._fll.correction;
    }
    // Locked latch: BOTH tones within tolerance (Java aligned :309-311, ALIGN_DONE_PPM ≈ the
    // 5e-4-bin band the single-tone latch uses). A steer that was plausibility-gated this frame
    // counts as not-in-tolerance so the latch can't light on a stale reading.
    const tol1 = ok1 && Math.abs((f1Hz - t1) / this.binW) < 5e-4;
    const tol2 = ok2 && Math.abs((f2Hz - t2) / this.binW) < 5e-4;
    this._updateLockLatch(tol1 && tol2);
  }

  /** Runs ONE dual-tone deadbeat loop for a single tone: plausibility-gate the measurement,
   *  update the loop, publish `target + correction` on `trimEvent`. Returns true when the
   *  measurement passed the gate and steered (so the caller can fold it into the locked latch).
   *  Mirrors one half of Java's dual branch (:275-308) including the GATED trace line. */
  _steerOneDualLoop(fll, trimEvent, target, measuredHz, tag) {
    if (!(target > 0) || !Number.isFinite(measuredHz)) {
      if (traceFll()) console.warn(`FLL ${tag} GATED: target=${target} meas=${measuredHz}`);
      return false;
    }
    const maxErrHz = Math.max(FLL_MAX_ERROR_BINS * this.binW, target * FLL_MAX_ERROR_PPM * 1e-6);
    if (Math.abs(measuredHz - target) > maxErrHz) {
      if (traceFll()) {
        console.warn(`FLL ${tag} GATED: target=${target.toFixed(6)} meas=${measuredHz.toFixed(6)}`
          + ` errHz=${(measuredHz - target).toFixed(6)} maxErrHz=${maxErrHz.toFixed(6)}`);
      }
      return false;
    }
    const gateBefore = fll.correctionVisibleFrom;
    fll.update(target, measuredHz,
      this._tickAbsCapStart, this._tickWritePos, this.config.inRate, this.N);
    const pub = target + fll.correction;
    MessageBus.instance().publish(trimEvent, pub);
    if (traceFll()) {
      const held = fll.correctionVisibleFrom >= 0 && this._tickAbsCapStart < fll.correctionVisibleFrom;
      console.warn(`FLL ${tag}: target=${target.toFixed(6)} meas=${measuredHz.toFixed(6)}`
        + ` corr=${fll.correction >= 0 ? '+' : ''}${fll.correction.toFixed(6)}`
        + ` pub=${pub.toFixed(6)} winStart=${this._tickAbsCapStart} writePos=${this._tickWritePos}`
        + ` gate[${gateBefore}->${fll.correctionVisibleFrom}] ${held ? 'HELD' : 'open'}`);
    }
    return true;
  }

  /** Advances the display-only lock latch (Java ALIGN_DONE_PPM aligned flag): two consecutive
   *  in-tolerance frames light `fllLocked`, any out-of-tolerance frame drops it. Shared by the
   *  single- and dual-tone steer (dual requires BOTH loops in tolerance before it calls this true). */
  _updateLockLatch(inTolerance) {
    if (inTolerance) {
      if (++this.fllStable >= 2) this.fllLocked = true;
    } else {
      this.fllStable = 0;
      this.fllLocked = false;
    }
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
    // ∞ (forever) mode has no finite target - show the ∞ glyph (Java AVERAGES_SERIES ∞);
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

    // Remembered for reemitDisplay (the stopped-state cal re-apply) - and as the identity
    // guard so a LOADED .fft file in the pane is never overwritten by a stale re-emit.
    this._lastEmitted = r;
    this.onResult(r);
  }

  /** Re-derives + re-emits the DISPLAYED result from the RAW accumulator
   *  (which survives a stop) so a calibration Active/With-noise/load/remove change applies
   *  to a STOPPED FFT too. Java's .frc de-embed is a plot-time transform over the raw
   *  lastResult, so a toggle there just repaints; the web's emit-time copy needs this
   *  re-emit through engine.onResult so the CURRENT correction cascade re-applies to raw
   *  data. overlayOnto REPLACES the result's spectral arrays from the accumulator (its
   *  mag->amplitude anchor at the fundamental bin survives the .frc de-embed, which scales
   *  amplitude and re/im by the same factor). No FLL steer, no stop-after - display only.
   *  `base` must be the pane's CURRENT result: when it isn't the controller's own last
   *  emit (a loaded .fft file), this is a no-op. Returns whether a result was re-emitted.
   *  Limitation: a non-averaged run leaves the accumulator empty - nothing to rebuild. */
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

  /** Preferences-dialog audio-config bracket (Java FftPane.stopCaptureForPrefs /
   *  startCaptureForPrefs): the analyser OWNS whether it was recording. stopCaptureForPrefs
   *  REALLY stops - setRecording(false) tears down the worker(s) AND releases this
   *  consumer's shared-capture ref, so the one device closes on the LAST release
   *  (refcount), not a central hard teardown. startCaptureForPrefs REALLY restarts -
   *  setRecording(true) rebuilds the rate-dependent analysis geometry + worker(s) and
   *  re-acquires the ref (reopening the device at the committed config on first re-acquire). */
  async stopCaptureForPrefs() {
    this._recordWasRunningForPrefs = this._fftOn;
    if (this._fftOn) await this.setRecording(false);
  }
  async startCaptureForPrefs() {
    if (!this._recordWasRunningForPrefs) return;
    if (await this.setRecording(true)) return;
    // The restart FAILED - the committed device could not be opened. setRecording(true) left the
    // analyser off and holding nothing, but the PANE still shows Record engaged, because it owns
    // that LED. Reuse the auto-stop notification: its subscriber already flips Record back off and
    // reconciles the pane, which is exactly what is needed here (the same gap the scope had, where
    // the trace froze behind a lit LED).
    MessageBus.instance().publish(Events.FFT_RECORDING_AUTO_STOPPED);
  }
}
