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
import { FftResult } from './fft-result.js';
import { minHopSamples, maxHopSamples, OVERLAP_FRACTION, RAND_HOP_STEPS } from '../enums/fft-overlap.js';
import { isDualTone } from '../enums/gen-signal-form.js';
import { OVERRUN } from '../audio/signal-buffer-reader.js';
import { MainsCombFilter, DEFAULT_NOTCH_BANDWIDTH_HZ } from '../dsp/mains/comb-filter.js';
import { mainsFilterOf } from '../dsp/mains/factory.js';
import { MessageBus } from '../bus/message-bus.js';
import { Events } from '../bus/events.js';
import { GenChangeCause } from '../enums/gen-change-cause.js';
import { FrequencyFll } from '../dsp/fll.js';
import { refinePeak, TONE_SEARCH_BINS } from './imd-analyzer.js';
import { debug, isDebug } from '../util/debug.js';

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
/** Alignment-done threshold for the magnitude-drift watch, in ppm of the tone target (Java
 *  FftController.ALIGN_DONE_PPM): once every locked tone measures within this of its target
 *  the lock counts as settled and the drift comparison fires. Comfortably above the converged
 *  loop's frequency jitter (~0.005 ppm) and below the raw clock offset (~1.5 ppm). */
const ALIGN_DONE_PPM = 0.1;
/** Magnitude-drift threshold in dB (Java FftController.ALIGN_DRIFT_DB): a fundamental whose
 *  level moved by more than this between the first post-reset result and the aligned result
 *  means the running average still carries pre-alignment frames at a depressed level. */
const ALIGN_DRIFT_DB = 0.1;
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

/** How long a display frame may be outstanding before the preparation is presumed dead. The
 *  display worker answers this thread directly, so this timer is the only thing between a worker
 *  that stopped answering and a view that never updates again: on expiry the one-job flag is
 *  released and the preparation moves back onto the analysis thread for the rest of the session.
 *  Generous against the work it covers - a rebuild of a 1M-point display is tens of ms - because
 *  it is a death test, not a pacing one. */
const FINALIZE_WATCHDOG_MS = 5000;
/** Display jobs that may be outstanding at once. The port preserves send order, so the cap only
 *  bounds the in-flight payloads; two, because the analysis answer that resumes the feed races
 *  the ~10 ms display frame on a separate channel and always wins - a cap of one then declined
 *  every second tick over that race and halved the trace rate against the tick rate. */
const FINALIZE_JOBS_MAX = 2;
/** Consecutive slow-floor displays (500 ms apart each) before the feed gives up draining and
 *  re-anchors to live. The hop-bias drain repays at most a fraction of a hop per tick - right
 *  for the sub-second debts of a cold start or a scheduling hiccup - but a multi-second debt (a
 *  backgrounded tab, a stretch of wedged-serial transforms) would hold the display at the floor
 *  for minutes. Six floor shows is ~3 s of demonstrated stuck-behind, measured in wall clock
 *  where a batch catch-up (which legitimately holds a deep backlog) never treads. */
const FLOOR_SHOWS_REANCHOR = 6;
/** JIT warm-up discarded at the head of every Record session, in ms. No UI field: Java
 *  hardwires it (FftAnalyzerWorker), and so does the web. */
const WARMUP_MS = 500;

export class FftController {
  /**
   * @param capture the SharedCapture instance.
   * @param gen     the GeneratorController (read + FLL-steered, not owned).
   * @param prefs   the live Preferences - every analysis setting is read from it at the moment it
   *                is needed, the way Java FftAnalyzerWorker re-reads Preferences.instance() per
   *                tick: the FFT length, window, overlap, averaging, threads, alignment, the THD
   *                settings, the analysed channel, the mains mode, the calibration anchors and
   *                the generator's form and bin snap.
   * @param deps    {status} - status: (text) => void.
   */
  constructor(capture, gen, prefs, { status } = {}) {
    this._capture = capture;
    this._gen = gen;
    this._prefs = prefs;
    this._status = status || (() => {});
    this._fftOn = false;
    /** Load-meter reporter, or null while nothing measures (the tests, the offscreen shell).
     *  Set by the shell through {@link #setBusyReporter}. */
    this._reportBusy = null;
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
    // The random mode's position on its hop lattice (Java hopStep): advanced by every
    // hop, wrapping after RAND_HOP_STEPS; restarts at 0 with every fresh first window.
    this._hopStep = 0;
    // The backlog drain's state (see _nextHop): whether the upper-half bias is engaged, and its
    // own position on the lattice's upper half - separate from _hopStep, so drain draws never
    // consume the main cycle's equal per-value shares.
    this._hopDraining = false;
    this._drainStep = 0;
    // ALL analysis - the threads>1 parallel pool, the cross-tick accumulator (Java
    // FftAnalyzerWorker accumulateIntoForeverBuffer: the worker FFTs only ~1-2 fresh frames per
    // tick and this DEEPENS the average across ticks for the sqrt(N) SNR gain) and the display
    // rebuild finalized off it - runs inside fft-worker.js, which coordinates its own nested
    // fft-pool-worker.js siblings. Java parity: FftAnalyzerWorker.parallelChunks splits chunks ON
    // the background analyzer thread, never the UI thread; the desktop's view then finalizes on
    // its own thread, where the web's view IS the thread the analysis competes with.
    // Epoch bumped on every accumulator reset; in-flight worker results stamped with an
    // older epoch are dropped so a window straddling the reset can't poison the average.
    this._accumEpoch = 0;
    // One-shots -> the worker's accumulator, so it resets / re-anchors at the SAME ticks the
    // recoveries here decide on. Every recovery stays on this side: the capture cursor, the
    // epoch and the drain skip exist only here.
    this._workerResetPending = false;
    this._workerResyncPending = false;
    // THE PIPELINE'S JOBS IN FLIGHT, at most two. The display preparation runs beside the next
    // analysis; the port delivers its answers in send order, so order needs no single-job rule -
    // the cap only bounds what is outstanding to two payloads. Two, not one, because the analysis
    // answer and the display frame race on separate channels and the answer always wins: it
    // resumes the feed while the ~10 ms frame is still in the port, and a depth of one then
    // declined every second tick for a race, halving the trace rate against the tick rate. A tick
    // declined at the cap is simply folded and not displayed, which is what a declined tick
    // already is. The count lives here because this side owns the display gate and is the one
    // participant with a timer that always fires.
    this._finalizeJobs = 0;
    this._finalizeTimer = null;
    // Latched by the watchdog: from then on the analyzer prepares the display itself.
    this._finalizeInPlace = false;
    // The port the display worker answers on, handed over by the analyzer on first use.
    this._displayPort = null;
    // The active .frc rows the de-embed cascades, as the calibration store holds them
    // ({calibration:{left,right}, withNoise}). Kept HERE because the analysed channel is kept
    // here: the rows go to the analyzer resolved to that channel, and a channel switch or a
    // fresh analyzer re-sends them (Java's FftController owns the store for the same reason).
    this._calEntries = [];
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
    // Previous batch's align-on state, for the align OFF->ON transition (Java
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
    // Magnitude-drift watch (Java FftController alignWatch*): armed by every frequency-lock
    // reset (generator change / align-on / Record start + stop), baselined from the first steered
    // result, resolved ONCE when the loop(s) report aligned - see _watchAlignmentMagnitudeDrift.
    this._alignWatchArmed = false;
    this._alignWatchBaselined = false;
    this._alignWatchBaseF1Db = NaN;
    this._alignWatchBaseF2Db = NaN;
    // Last DISPLAY-rebuild timestamp (Java FftAnalyzerWorker.lastShowNanos, in ms here):
    // _showThisTick gates the display rebuild against it at dispatch, so a rebuilt frame is
    // produced at most every DISPLAY_MIN_MS when caught up and at least every DISPLAY_MAX_MS
    // when behind - bounding both the worker's finalize and the emit + draw it triggers here.
    this._lastShowMs = 0;
    this._floorShows = 0;   // consecutive slow-floor displays - see FLOOR_SHOWS_REANCHOR

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
    prefs.fftStrongToneRelDb.addListener(() => this.resetAnalyses());

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
    this._restartWindow();
    this._accumEpoch++;
    this._workerResetPending = true;
    this._drainSkipRemaining = Math.ceil(OUTPUT_DRAIN_SKIP_SEC * this._inRate());
  }

  /** The rate this consumer's samples arrive at: the open ring's granted rate, else the active
   *  backend's configured input rate (the analysis geometry is built before the first acquire,
   *  and the shared capture writes the granted rate into that preference on open). */
  _inRate() {
    const capture = this._capture;
    const live = capture != null && capture.refCount > 0 ? capture.inSampleRate : 0;
    return live > 0 ? live : (this._prefs.current().inputSampleRate || 0);
  }

  /** True while the align mode is FLL (Java FftView's alignGenerator != NONE). */
  _fllOn() { return this._prefs.fftAlignGenerator.get() === 'FLL'; }

  /** The COUNT of harmonics H2..HN the analyzer computes - Java FftAnalyzerWorker:1661
   *  calcMaxH = max(9, getFftCalcMaxHarmonic()) - 1 (the fundamental is NOT one of them). */
  _harmonicCount() { return Math.max(9, this._prefs.fftCalcMaxHarmonic.get()) - 1; }

  /** The THD band's lower / upper bound in Hz, 0 when that bound is off (Java distMin/distMax). */
  _snrFreqMin() { const p = this._prefs; return p.fftDistMinEnabled.get() ? p.fftDistMinHz.get() : 0; }
  _snrFreqMax() { const p = this._prefs; return p.fftDistMaxEnabled.get() ? p.fftDistMaxHz.get() : 0; }

  /** Restarts the analysis window: re-anchor the cursor at the live tip and drop
   *  whatever had been gathered towards the current one, so the next window is built
   *  from FRESH samples only - the web analog of Java's reAnchorPending +
   *  invalidateWindow(). Without it a restart keeps the buffer full, and the next
   *  window is the OLD one slid forward by a single hop: an average that was meant
   *  to start from zero opens with a frame that is mostly pre-restart signal.
   *  What the ACCUMULATOR does about the restart differs per caller (a reset drops
   *  the collected depth, a re-sync keeps it), so that stays outside. */
  _restartWindow() {
    const reader = this._fftReader;
    if (reader) { reader.seekToLatest(); this._absNextSample = reader.getReadPos(); }
    this.bufFilled = 0; this.bufW = 0; this._refill = this.maxHop;
    this._winAbsStart = 0; this._dispatchedOnce = false;
    this._feedT0 = 0;      // no gathering under way to time (diagnostics only)
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
   *  instead holds a STANDING backlog of about one hop (it is worker-paced, and the drain
   *  keeps that residue near the smallest hop), so the next window is nearly always ready
   *  and counting the whole cursor would pin the bar at full. The sweep Java's bar shows
   *  is progress of the CAPTURE toward the next tick's due time, and the web quantity
   *  with that meaning is what has ARRIVED since this hop was drawn: fresh samples past
   *  the dispatch-time cursor mark, plus what the feed has already read toward the hop,
   *  against the drawn hop. Before the first dispatch the first window needs a full
   *  bufLen (Java winValid==false => avail / winNeeded); _dispatchedOnce is the web
   *  analog of winValid. Overrun stays a negative sentinel, distinct from 0 (no fresh
   *  samples), exactly as Java. */
  nextFrameProgress() {
    if (!this._fftOn || !this._fftReader || this.bufLen == null) return 0;
    const avail = this._fftReader.available();
    if (avail === OVERRUN) return -1.0;   // overrun -> negative, distinct from "no fresh samples"
    let have, want;
    if (this._dispatchedOnce) {              // winValid: each tick needs one fresh hop
      // The hop actually being gathered - the DRAWN pending hop, not a
      // recomputation from the settings (under the random overlap the two differ
      // every tick, and even for a fixed mode the preference can change ahead of
      // the next tick).
      want = Math.max(1, this.pendingHop);
      have = (want - this._refill) + Math.max(0, avail - this._availAtDispatch);
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

  /** The active .frc calibration rows, from the store's single mutation site (the calibration
   *  rows of the FFT settings). The de-embed cascade runs in the analyzer, so the rows are handed
   *  over resolved to the channel being analysed, and the held cascade is dropped with them. */
  setCalibrationEntries(entries) {
    this._calEntries = entries ? entries.slice() : [];
    this._sendCalRows();
  }

  /** Hands the active rows to the analyzer for the channel it reads. Re-sent whenever that
   *  channel changes and whenever a fresh analyzer is built, which starts holding nothing. */
  _sendCalRows() {
    if (!this.worker) return;
    const wantLeft = this._wantLeft;
    this.worker.postMessage({
      calRows: this._calEntries.map((e) => ({
        cal: wantLeft ? e.calibration.left : e.calibration.right, withNoise: !!e.withNoise,
      })),
    });
  }

  /** FFT averages accumulated since the last reset (predistortion host) - the
   *  per-tick analysis count (Java getCompletedAnalyses / completedAnalyses). */
  completedAnalyses() { return this._analysesTicks; }
  /** Cross-tick accumulator frame depth since the last reset (Java
   *  getAccumulatedFrames) - the predistortion frame-depth readout, distinct from
   *  the per-tick completedAnalyses count stop-after-N keys off. */
  accumulatedFrames() { return this._analysesDone; }

  /** The average COUNT to display, read LIVE - the same expression the emitted result
   *  carries (the first tick is the seed, not an average, hence one less, clamped at
   *  0), so the two never disagree. The desktop's ~100 ms indicator timer reads its
   *  count from the analyser the same way (FftView.startFillPercentTimer); the web
   *  label was painted only when a result arrived, so after a stop / start or a reset
   *  it went on showing the PREVIOUS run's depth until the next frame landed - minutes
   *  of it at the large FFT lengths. */
  averagesDisplayed() { return Math.max(0, this.framesDone - 1); }
  /** Resets the accumulated-averages counter, the ANALYSIS WINDOW and the cross-tick
   *  accumulator (the Reset-statistics button, a predistortion round boundary).
   *  Mirrors Java resetStatistics: a fresh round must start the average from zero, so
   *  the window is re-anchored at the live tip and rebuilt from fresh samples, and the
   *  epoch bump drops any in-flight window from before the reset. Restarting the
   *  average WITHOUT restarting the window left the analysis buffer full, so the first
   *  frame of the new average was the last frame of the old one slid forward by one
   *  hop - the reset only appeared to take effect a whole window later.
   *  The frequency lock is deliberately NOT touched: the generator did not change, so
   *  the converged correction stays valid (a user-driven signal change resets it, in
   *  the GENERATOR_SIGNAL_CHANGED subscriber). */
  resetAnalyses() {
    this._analysesTicks = 0;
    this._analysesDone = 0;
    this.framesDone = 0;        // the READOUT too - a restarted average shows 0, not the old depth
    // Java resetStatistics calls paused.set(false): a predistortion-round reset must
    // RESUME the feed after a prior stop-after-N auto-stop, else the next round starves.
    this._fftPausedByStopN = false;
    this._restartWindow();
    this._accumEpoch++;
    this._workerResetPending = true;
    // A frame being prepared belongs to the average that just ended: its carried epoch will no
    // longer match, so it is dropped when it lands - and the flag is released now so the next
    // tick can be displayed at once rather than waiting out a job whose answer is already void.
    this._clearFinalizeInFlight();
    // Every restart-from-zero funnels through here, so this is the one place that announces it.
    // The render-time correction listens and drops its held .frc cascade: the analysed channel
    // and the tone positions it was keyed on both move with a restart. The bus is the seam - the
    // controller must not know the view (Java reaches the same state from the view side, where
    // resetStatistics itself drops the array).
    MessageBus.instance().publish(Events.FFT_STATISTICS_RESET);
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
    this._sendCalRows();                 // the other channel's curve of every loaded row
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
      this._resetFrequencyLock();       // fresh Record session -> converge alignment from zero
      this._fftOn = true;
    } else {
      this._fftOn = false;
      this._fftReader = null;
      // Record stop resets the frequency lock too (Java FftController.resetFrequencyLock is
      // called on Record stop as well as align-ON): the generator returns to its snapped
      // frequency the moment the measurement ends, instead of playing the last trim until the
      // next session happens to start.
      this._resetFrequencyLock();
      // The analyzer is NOT torn down here: it holds the raw cross-tick average, and that
      // average survives a stop - a calibration change made while stopped is answered from it
      // (reemitDisplay). It is replaced when the next session sets up, which is also where a
      // changed FFT length or capture rate would invalidate what it holds.
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
    const p = this._prefs;
    this.N = p.fftLength.get();
    // ∞ (forever) averaging is a true cumulative mean; a finite N is a ring window.
    // The averages preference holds Infinity for the ∞ entry (Java AVERAGES_SERIES ∞).
    const averages = p.fftAverages.get();
    this.foreverMode = averages === Infinity || !Number.isFinite(averages);
    this.ringN = this.foreverMode ? 1 : Math.max(1, averages | 0);
    // The on-screen "N×" depth target: ∞ -> unbounded; a ring -> its N. (The Java
    // worker caps the ring accumulator at 2·N frames since ~2 frames land per
    // coherent tick; here avgTarget is the displayed N and the accumulator's
    // targetN scaling - see _onWorkerResult - bounds the depth to it.)
    this.avgTarget = this.foreverMode ? Infinity : this.ringN;
    this._computeAnalysisFreqs();
    // Hop bounds from the MODE itself: a fixed overlap has one hop (both bounds
    // coincide), the random mode spans its percent range. The window is sized
    // ONCE for the LARGEST possible hop, so it never resizes while a drawn hop's
    // gather is in flight (a mid-gather resize would discard the partial fill and
    // tear the contiguous stream the de-rotation depends on).
    const overlap = p.fftOverlap.get();
    this.minHop = minHopSamples(overlap, this.N);
    this.maxHop = maxHopSamples(overlap, this.N);
    // The hop the window slides by next - drawn per tick in the random overlap
    // mode, constant otherwise. FROZEN while its gather is in flight: it is
    // redrawn only after the slide it sizes has completed. It is also the spacing
    // the analysis states for its frame pair, so the second frame of one tick is
    // exactly the first frame of the next. 0 until the first window is built.
    this.pendingHop = 0;
    this.overlapPct = OVERLAP_FRACTION[overlap] * 100;

    // Per-tick window = N + (perTickFrames−1)·maxHop samples - sized to ~1-2 frames
    // INDEPENDENT of the averaging depth (Java: needed = N + (frames−1)·hop, with
    // frames = coherent ? 2 : 1). The analyzer's cross-tick running accumulator
    // supplies the depth, so the FIRST spectrum lands in <0.2 s at any averaging
    // instead of after bufLen/inRate seconds (the dead-FFT bug this fixes).
    // Size by AVERAGING state, not the coherent flag (Java: averages =
    // (foreverMode || ringN >= 2) ? 2 : 1). Any averaging contributes ~2 frames
    // per tick to the cross-tick accumulator; no averaging needs only 1.
    const accumulate = this.foreverMode || this.ringN >= 2;
    const perTickFrames = accumulate ? 2 : 1;
    this.perTickFrames = perTickFrames;
    this.bufLen = this.N + (perTickFrames - 1) * this.maxHop;
    this.buf = new Float64Array(this.bufLen);
    this.bufW = 0; this.bufFilled = 0;
    // Fresh Record session -> fresh accumulator (Java start() resetStatistics). The fork's
    // line-admission threshold rides every dispatch from the live preference, the way Java reads
    // Preferences#getFftStrongToneRelDb inside forkPooledOffset.
    this._accumEpoch++;
    this._workerResetPending = true;
    // A fresh session re-anchors the window and empties the accumulator without going through
    // resetAnalyses, so it announces the restart itself - an FFT-length change reaches the
    // display this way (stop, start) and the held .frc cascade belongs to the old bin grid.
    MessageBus.instance().publish(Events.FFT_STATISTICS_RESET);
    this._lastAccumulate = false;
    this._lastForeverMode = false;
    this._lastRingN = -1;
    // Absolute sample position of the window currently in `buf` (for the cross-tick
    // de-rotation delta). The first dispatched window starts at 0; each later one
    // advances by the hop that was just consumed - the de-rotation rides each
    // tick's ABSOLUTE sample position, so a per-tick VARIABLE hop stays exact as
    // long as the stream remains gap-free (which is why the pending hop is frozen
    // until its gather completes).
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
    this._wantLeft = (p.fftChannel.get() === 'L');

    this.warmup = Math.round(this._inRate() * WARMUP_MS / 1000);
    this._lastShowMs = 0;   // fresh session -> the first result displays immediately (Java lastShowNanos 0)
    this._floorShows = 0;   // a fresh session owes no floor history
    this._fftFps = 0; this._fftCount = 0; this._fftWinT0 = 0; this._workerMs = 0;
    this._busy = false; this._dispatchId = 0; this.dropped = 0; this._refill = this.maxHop;
    this._availAtDispatch = 0;   // fill readout's zero mark - see nextFrameProgress

    // Worker-pool sizing - see _poolSizeFor. The pool itself lives INSIDE
    // fft-worker.js (nested workers) - the main thread only posts one message per tick,
    // identical to the serial path, so threads>1 can't starve the UI (Java parity:
    // FftAnalyzerWorker.parallelChunks runs on the analyzer thread, never the UI thread).
    this.poolSize = this._poolSizeFor(p.fftThreads.get());
    // Seed the align-transition latch so _syncLiveConfig only resets the FLL on a real
    // OFF->ON flip (the fresh-session reset already runs in setRecording).
    this._lastFllOn = this._fllOn();
    // The previous session's analyzer outlived its stop so the stopped display could be rebuilt
    // from the average it held; a fresh session starts from an empty one, so it goes now.
    this._teardownFftWorkers();
    const workerUrl = new URL('./fft-worker.js', import.meta.url);
    this.worker = new Worker(workerUrl, { type: 'module' });
    this.worker.onmessage = (e) => this._onWorkerResult(e.data);
    this.worker.onerror = (ev) => this._onWorkerError(ev);
    this._sendCalRows();   // a fresh analyzer holds no rows
  }

  /** A TOP-LEVEL worker failure - a module that will not load, an uncaught throw on
   *  the analysis thread. It arrives here instead of as a posted result, so the tick
   *  that was in flight will never reply. Treat it as a completed tick that produced
   *  nothing: the same seam a posted {error} takes, which clears the in-flight flag
   *  and resumes the feed.
   *
   *  Leaving it as a bare log would strand the flag set forever, and since the feed's
   *  busy gate sits BEFORE the overrun check the consumer would then stop consuming
   *  altogether - no window analysed, no overrun ever detected, no further word to
   *  the operator. Java's workerLoop catches the analysis and keeps ticking; this is
   *  that catch. The retry is self-limiting: a further dispatch needs a whole fresh
   *  hop, so a permanently broken worker errors at the capture rate, not in a spin.
   *
   *  The failure is reported on the console, as the desktop reports its own. It is said
   *  per failed tick rather than once, because this handler also fires when the worker
   *  MODULE fails to load: no tick then ever succeeds, the retry keeps the pump moving,
   *  and every window analyses to nothing behind a spectrum that still looks like a
   *  running measurement. */
  _onWorkerError(ev) {
    const msg = (ev && ev.message) ? ev.message : 'worker failed';
    console.warn('FFT analysis worker failed - the tick in flight produced nothing: ' + msg);
    this._onWorkerResult({ error: 'fft-worker: ' + msg });
  }

  /** Kills the FFT analyzer worker (its nested pool sub-workers die with it). */
  _teardownFftWorkers() {
    try { if (this.worker) this.worker.terminate(); } catch (_) {}
    this.worker = null;
    // The display worker dies with its parent, so nothing will answer an outstanding job.
    this._clearFinalizeInFlight();
    this._displayPort = null;
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
    const p = this._prefs;
    this.poolSize = this._poolSizeFor(p.fftThreads.get());
    // Averaging mode/targets (mirrors the _setupFftAnalysis derivation).
    const averages = p.fftAverages.get();
    this.foreverMode = averages === Infinity || !Number.isFinite(averages);
    this.ringN = this.foreverMode ? 1 : Math.max(1, averages | 0);
    this.avgTarget = this.foreverMode ? Infinity : this.ringN;
    // Hop bounds / window geometry off the live overlap + averaging state.
    const overlap = p.fftOverlap.get();
    const minHop = minHopSamples(overlap, this.N);
    const maxHop = maxHopSamples(overlap, this.N);
    const accumulate = this.foreverMode || this.ringN >= 2;
    const perTickFrames = accumulate ? 2 : 1;
    const bufLen = this.N + (perTickFrames - 1) * maxHop;
    this.perTickFrames = perTickFrames;
    if (minHop !== this.minHop || maxHop !== this.maxHop || bufLen !== this.bufLen) {
      this.minHop = minHop;
      this.maxHop = maxHop;
      this.pendingHop = 0;        // redrawn when the rebuilt window completes
      this.overlapPct = OVERLAP_FRACTION[overlap] * 100;
      this.bufLen = bufLen;
      this.buf = new Float64Array(bufLen);
      this.bufW = 0; this.bufFilled = 0; this._refill = maxHop;
      this._winAbsStart = 0; this._dispatchedOnce = false;
      this._accumEpoch++;              // drop ONLY the in-flight old-geometry window
      this._workerResyncPending = true;   // re-anchor κ slope + PLL; the collected DEPTH survives
    }
    const fllOn = this._fllOn();
    if (fllOn && !this._lastFllOn) this._resetFrequencyLock();
    this._lastFllOn = fllOn;
    // MAINS MODE, read live per batch the way the Java worker re-reads it per tick.
    // A transition that touches one of the two TIME-DOMAIN cancellers (SYNC_SUBTRACT, LMS) -
    // entering one, leaving one, or swapping between them - changes what the samples already
    // gathered towards the current window contain: they were filtered under the old mode (or not
    // at all), and the next window would mix the two. So the window is regathered from fresh
    // samples and the canceller's persistent state is dropped with it. IIR_COMB is not a
    // transition of this kind: it leaves the window raw and corrects the plot instead.
    // The ACCUMULATOR is deliberately untouched - not its collected depth, not its counter: the
    // hum is an additive component the canceller removes, not a change of what is measured, so
    // the average goes on (Java keeps the accumulator across a mains-mode change too).
    const mainsMode = p.fftMainsSuppression.get() || 'NONE';
    if (this._lastMainsMode == null) this._lastMainsMode = mainsMode;
    if (mainsMode !== this._lastMainsMode) {
      const timeDomain = (m) => m === 'SYNC_SUBTRACT' || m === 'LMS';
      if (timeDomain(mainsMode) || timeDomain(this._lastMainsMode)) {
        this._restartWindow();
        this._fftTimeFilter = null;      // the canceller's adapted state belongs to the old mode
        this._fftTimeFilterMode = null;
        this._accumEpoch++;                 // drop ONLY the in-flight window of the old mode
        this._workerResyncPending = true;   // re-anchor the slope + PLL; the DEPTH survives
      }
      this._lastMainsMode = mainsMode;
    }
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
    // ANALYSIS IN FLIGHT -> consume NOTHING. Java's worker gathers and analyses on
    // one thread, so a window that comes due is always the next one analysed and the
    // spacing between analysed windows is exactly the hop. The web's analysis is
    // asynchronous, so the only way to keep that invariant is to leave the cursor
    // where it is: the backlog waits in the capture ring instead of being read and
    // thrown away. Reading it and dropping the window would space the analysed
    // windows by hop x (1 + drops) - an irregular multiple set by main-thread load,
    // which randomises the overlap at EVERY setting and hides the periodic floor
    // structure that a fixed high overlap is supposed to show. If the analysis
    // genuinely cannot keep up the ring laps the cursor, which is Java's own outcome
    // and is already handled below as an overrun, with its re-sync banner.
    if (this._busy) return;
    // Live settings re-derive (overlap hop / averages targets / threads pool / align
    // transition) - the Java worker re-reads Preferences every tick; NO reset here.
    // Between ticks, never during one: the busy gate above guarantees no analysis is
    // in flight when the geometry is re-derived.
    this._syncLiveConfig();
    let avail = reader.available();
    if (avail === OVERRUN) { this._onOverrun(); return; }
    if (avail <= 0) return;
    // Output-drain skip (Java FftAnalyzerWorker): after a signal-change re-anchor,
    // consume and DISCARD the span still carrying the old tone (the DAC buffer keeps it
    // flowing into the ADC after the change) so the first window - and the FLL's first
    // measurement - see only the new signal. Discarding reads into null buffers (advances
    // the cursor without copying); return until the drain is exhausted.
    if (this._drainSkipRemaining > 0) {
      const got = reader.read(Math.min(avail, this._drainSkipRemaining), null, null);
      if (got === OVERRUN) { this._onOverrun(); return; }
      if (got > 0) this._drainSkipRemaining -= got;
      return;
    }
    // Diagnostics only: where the window gathering starts, so a dispatch can state what it cost.
    // It is the one stage of a tick that runs on this thread, and the per-stage log has no other
    // way to see it. 0 leaves the dispatch stating nothing.
    this._feedT0 = isDebug() ? performance.now() : 0;
    // Read only as far as the NEXT window needs (Java gathers exactly `needed` for
    // the first window and exactly one hop for each one after, then analyses). Taking
    // the whole backlog instead would bring several windows due inside one
    // _accumulate pass, and only the first of them could be dispatched - the rest
    // would have to be dropped, which is the spacing defect this bound removes.
    const want = Math.min(avail, this._samplesToNextDispatch());
    if (!this._fftReadBuf || this._fftReadBuf.length < want) this._fftReadBuf = new Float64Array(want);
    const tmpR = this._fftReadBuf;
    // Absolute capture position of samples[0] (= the readPos before this consuming
    // read) - the timebase the FLL transport gate compares window starts against.
    const absBase = reader.getReadPos();
    // Read the SELECTED channel (Java FftAnalyzerWorker:1592
    // rdr.read(needed, wantLeft ? winBuf : null, wantLeft ? null : winBuf)):
    // L -> ch0 fills the left arg, R -> ch1 fills the right arg. Consuming forward read.
    const n = this._wantLeft ? reader.read(want, tmpR, null) : reader.read(want, null, tmpR);
    if (n === OVERRUN) { this._onOverrun(); return; }
    this._absNextSample = absBase;
    this._accumulate(tmpR, n);
  }

  /** Samples still owed before the next window comes due - the read bound that keeps
   *  at most ONE window due per feed. Before the first dispatch that is whatever the
   *  buffer still lacks, but never less than the warmup span (warmup samples fill the
   *  ring yet cannot dispatch); afterwards it is the countdown to the drawn hop. */
  _samplesToNextDispatch() {
    if (!this._dispatchedOnce) {
      return Math.max(this.warmup + 1, this.bufLen - this.bufFilled);
    }
    return Math.max(1, this._refill);
  }

  /** Ring overrun: the writer lapped the cursor (the analyser fell a full ring
   *  behind). ONLY the window starts over - re-anchored at "now" and rebuilt from a
   *  fresh contiguous span. The collected average SURVIVES (Java onCaptureOverrun:
   *  a COVERAGE gap, not corrupted data - the absolute sample positions stay true
   *  across it, so the de-rotation absorbs the gap and averaging continues). Losing
   *  a deep average to a momentary stall is the worse failure by far: the depth is
   *  minutes of work, the gap is one window. onResync re-anchors the κ slope / PLL
   *  for the jumped window, and the epoch bump discards only the ONE window that was
   *  in flight across the gap. */
  _onOverrun() {
    this._restartWindow();
    this._accumEpoch++;
    this._workerResyncPending = true;   // KEEP the collected depth; re-anchor κ slope + PLL
    // Say which re-sync this was. The desktop logs it and blinks its own banner
    // (Java onCaptureOverrun -> publishCaptureBanner); the browser re-anchored in
    // silence, so an overrun and a signal discontinuity were indistinguishable on
    // screen even though they mean different things - one is the analysis falling
    // behind, the other damaged samples.
    console.warn('FFT ring overrun - analyser fell a full buffer behind; window re-anchored, '
      + 'averaging continues (lower the overlap or FFT length if this repeats)');
    MessageBus.instance().publish(Events.FFT_CAPTURE_RESYNC, 'fft.warning.overrun');
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
    this._restartWindow();
    this._drainSkipRemaining = Math.max(this._drainSkipRemaining,
      Math.ceil(POST_GLITCH_SKIP_SEC * this._inRate()));
    this._accumEpoch++;                 // drop any in-flight window straddling the re-sync
    this._workerResyncPending = true;   // KEEP the collected depth; re-anchor κ slope + PLL
    MessageBus.instance().publish(Events.FFT_CAPTURE_RESYNC, 'fft.warning.discontinuity');
  }

  /** One hop from the derived bounds (Java FftAnalyzerWorker.nextHop): the single
   *  fixed value when they coincide; for the random overlap the NEXT point of the
   *  mode's hop lattice - the range split into RAND_HOP_STEPS equal parts, the hop
   *  landing on a part boundary (the largest value is the max hop itself; the bare
   *  min hop is never used) - visited in a fixed cycle through _hopStep
   *  (0 -> 1 -> ... -> 15 -> 0). The de-rotation is exact for any hop; the
   *  sequence only decides WHICH spacings occur and in what order - a full cycle
   *  gives every lattice value the same share of the average. */
  _nextHop() {
    const min = this.minHop, max = this.maxHop;
    if (min >= max) return min;
    const quantum = Math.max(1, Math.trunc((max - min) / RAND_HOP_STEPS));
    // DRAIN, with hysteresis: a capture backlog beyond one largest hop is display lag the
    // lattice's mean hop can hold forever but never repay - a debt taken on at session start
    // (cold first ticks) or by a transient stall. While draining, hops come from the UPPER HALF
    // of the same lattice through their own cycle: every spacing stays a legal lattice value,
    // truthfully stated to the de-rotation (no coverage gap, the coherent average untouched),
    // and the spacings stay varied - a run of ONE fixed hop would turn the random mode into a
    // regular overlap and print its periodic ripple onto the averaged noise skirt. The drain
    // engages above a full largest hop of surplus, repays ~10 percent of every window, and
    // releases at one smallest hop, leaving a dead band in which the plain cycle runs - so the
    // bias exists only while a real debt is being repaid. The main cycle position does not
    // advance on a drain draw; the equal per-value share of the lattice resumes where it paused.
    if (this._fftReader) {
      const backlog = this._fftReader.getWritePos() - this._fftReader.getReadPos();
      const engaged = this._hopDraining ? backlog > min + 2 * quantum : backlog > max + quantum;
      this._hopDraining = engaged;
      if (engaged) {
        const half = RAND_HOP_STEPS / 2;
        this._drainStep = (this._drainStep + 1) % half;
        return Math.min(max, min + (1 + half + this._drainStep) * quantum);
      }
    }
    const step = this._hopStep;
    this._hopStep = (this._hopStep + 1) % RAND_HOP_STEPS;
    return Math.min(max, min + (1 + step) * quantum);
  }

  /** Accumulate `n` contiguous samples into the analysis ring and dispatch a
   *  window every drawn hop once the ring has filled. Identical buffer-and-
   *  analyze logic the fused engine ran per chunk, now fed from the FFT cursor. */
  _accumulate(samples, n) {
    const L = this.bufLen;
    for (let i = 0; i < n; i++) {
      this.buf[this.bufW] = samples[i]; this.bufW = (this.bufW + 1) % L;
      if (this.bufFilled < L) this.bufFilled++;
      if (this.warmup > 0) { this.warmup--; continue; }
      // The FIRST window goes out the moment the buffer is full; only the ones after
      // it wait a hop (the desktop dispatches as soon as its span is gathered). The
      // countdown started at a full hop even for the first window, so the fill sat at
      // 100 % for one more hop before anything was analysed - 42 s at 4 M / 50 % /
      // 48 kS/s, and again after every restart.
      if (this.bufFilled >= L && (!this._dispatchedOnce || --this._refill <= 0)) {
        // Absolute window start (the cross-tick de-rotation delta): the first
        // dispatched window starts at 0; each later one advances by the hop the
        // completed slide just consumed - the pending hop, still frozen at the
        // value drawn after the PREVIOUS slide.
        const absStart = this._dispatchedOnce ? (this._winAbsStart + this.pendingHop) : 0;
        this._winAbsStart = absStart;
        // A fresh first window starts the lattice cycle over (Java: hopStep = 0
        // at the first window build).
        if (!this._dispatchedOnce) this._hopStep = 0;
        this._dispatchedOnce = true;
        // The slide is complete - take the NEXT hop. It is the spacing this
        // tick's analysis states for its frame pair, so the second frame lands
        // exactly where the next tick's first frame will.
        this.pendingHop = this._nextHop();
        this._refill = this.pendingHop;
        // The fill readout's zero mark: what already waits in the cursor belongs to the standing
        // backlog, not to this hop's progress - the bar sweeps on what ARRIVES from here on.
        this._availAtDispatch = this._fftReader ? Math.max(0, this._fftReader.available()) : 0;
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
    const mode = this._prefs.fftMainsSuppression.get() || 'NONE';
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
    const p = this._prefs;
    if (!p.fftManualFundEnabled.get()) return NaN;
    const v = p.fftManualFundVrms.get();
    const dbv = (v > 0) ? 20.0 * Math.log10(v) : NaN;
    // The ANALYZED channel's ADC dBV offset (Java FftAnalyzerWorker:2005
    // getDbvOffsetDb(getFftChannel())), so the anchor resolves against the right full scale.
    return dbv - (p.getDbvOffsetDb(p.fftChannel.get()) || 0);   // NaN propagates
  }

  /** Hands the current window to the analyzer. Routes to the worker POOL when
   *  poolSize > 1, else the classic single-worker path.
   *
   *  There is NO busy check here: feedFft consumes nothing while an analysis is in
   *  flight and reads only as far as the next window, so exactly one window can be
   *  due per feed and it is always dispatched. A window that came due and was
   *  discarded would space the analysed windows by a multiple of the hop, which is
   *  the one thing the fixed overlaps must not do. The only remaining drop is the
   *  no-worker case below - the analyser is torn down, so there is nothing to run
   *  the tick on, which is Java's stopped-worker-thread state. */
  _dispatch(absStart, absCapStart) {
    const dbg = isDebug();
    const L = this.bufLen, snap = new Float64Array(L);
    snap.set(this.buf.subarray(this.bufW));
    snap.set(this.buf.subarray(0, this.bufW), L - this.bufW);
    const rate = this._inRate();
    this._applyFftMains(snap, rate, absCapStart);   // mains suppression on the window (+ plot-time f0)
    // Stamp this tick's absolute window start + accumulator epoch so _onWorkerResult
    // folds the result in with the right de-rotation delta and drops it if a reset
    // (epoch bump) landed while it was analyzing.
    this._tickAbsStart = absStart || 0;
    this._tickEpoch = this._accumEpoch;
    if (!this.worker) { this.dropped++; return; }
    this._busy = true;
    const p = this._prefs;
    const dual = isDualTone(p.genSignalForm.get());
    // Fold control (main->worker): the values the cross-tick fold needs. resetAccum / resyncAccum
    // are one-shots fired wherever a recovery here resets / re-anchors the average, so the
    // worker's accumulator moves with the cursor this side owns.
    const forever = this.foreverMode, ringN = this.ringN;
    const resetAccum = this._workerResetPending; this._workerResetPending = false;
    const resyncAccum = this._workerResyncPending; this._workerResyncPending = false;
    // What the generator says it EMITS, at THIS analyzer's rate - asked, not re-derived (Java
    // FftAnalyzerWorker requests GENERATOR_EMITTED_HZ under the same genActive + fund-from-gen
    // gate). null (or a 0 for "no such tone") withholds the hint entirely, which is what makes
    // the analyzer auto-detect an external tone instead of pinning its search to a nominal.
    // The fund-from-generator gate reads the LIVE preference, exactly as Java does
    // (FftAnalyzerWorker:1697 genActive && prefs.isFftFundFromGenerator()): the predistortion
    // wizard forces this pref on Start and the hint must reach an already-running record -
    // without it the accumulator's global peak search once pinned the coherent reference onto
    // the wizard's H9 test partial (the probe partials arrive stronger than the notched
    // fundamental).
    const fundFromGen = p.fftFundFromGenerator.get();
    const emitted = (this._genOn && fundFromGen)
      ? MessageBus.instance().request(Events.GENERATOR_EMITTED_HZ, rate) : null;
    const hintHz = (hz) => (hz > 0 ? hz : NaN);
    this.worker.postMessage({
      id: this._dispatchId++, samples: snap, sampleRate: rate, fftSize: this.N,
      harmonicCount: this._harmonicCount(), windowType: p.fftWindow.get(), overlap: p.fftOverlap.get(),
      // Stated frame geometry (Java analyze's step + frameCount): the spacing is
      // this tick's DRAWN pending hop - so the second frame is exactly the next
      // tick's first - and the count is stated because the window is sized for
      // the LARGEST hop, which would over-count under the random overlap.
      step: this.pendingHop, frameCount: this.perTickFrames,
      snrFreqMin: this._snrFreqMin(), snrFreqMax: this._snrFreqMax(),
      coherentAveraging: p.fftCoherentAveraging.get(),
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
      // prefs.isFftDetectTimeDiscontinuity()). Read live so an unchecked box takes effect
      // mid-record without a restart.
      timeGate: USE_TIME_DISCONTINUITY && (this.foreverMode || this.ringN >= 2)
        && p.fftDetectTimeDiscontinuity.get() !== false,
      winAbsStart: this._tickAbsStart,
      // FLL transport timebase (Java FftResult.samplesAbsStart / writePos): the window's
      // absolute capture start and the live write head, stamped ONTO THE RESULT by the worker so
      // a display frame prepared beside the next analysis still carries the window it was
      // measured from - the steer reads the frame, never a slot the next dispatch overwrites.
      absCapStart, writePos: this._fftReader ? this._fftReader.getWritePos() : 0,
      accumulate: forever || ringN >= 2, targetN: forever ? Infinity : 2 * ringN,
      spectralGate: true, strongToneRelDb: p.fftStrongToneRelDb.get(),
      resetAccum, resyncAccum,
      // Whether this tick is also to be REBUILT for display (the overlay, the .frc de-embed, the
      // plot-time mains comb, the statistics recompute, the blue dots) - see _showThisTick.
      // Sent with the window because the rebuild runs there; the mains fundamental the comb
      // needs was tracked on the raw window just above.
      finalize: this._showThisTick(), mainsF0: this._fftMainsF0,
      // The accumulator epoch this tick belongs to. A display frame is prepared beside the NEXT
      // analysis and answers out of band, so it carries this back and the carried value is what
      // decides whether it still belongs to the average on screen.
      epoch: this._accumEpoch,
      // Set once the watchdog has given up on the display worker: prepare it on the analysis
      // thread instead, which is what the code did before the pipeline existed.
      finalizeInPlace: this._finalizeInPlace,
      // The blue "before-cal" dots of the single-tone chart ride the result (preCorrectionPeaks),
      // but a DUAL-tone chart draws them at the intermod PRODUCT frequencies, which only the IMD
      // table - computed on this side, off the de-embedded spectrum - knows. So a dual-tone frame
      // with rows loaded also carries the cascade's gain per bin, and the chart looks its
      // products up in it. Nothing else needs it, and a single-tone frame never carries it.
      wantCalGain: dual && this._calEntries.length > 0,
      // DIAGNOSTICS, and nothing the analysis reads. The per-stage timing switch is a setting of
      // the page: a worker can read neither the storage it is kept in nor a query string of its
      // own, so it is stated per tick. With it, the one stage of a tick that does NOT happen in
      // the worker - reading the capture and assembling this window, which happens here - so that
      // the worker's line can account for a whole tick rather than the part it can see.
      debug: dbg,
      readMs: dbg && this._feedT0 > 0 ? performance.now() - this._feedT0 : 0,
    }, [snap.buffer]);
  }

  /** Whether the tick being dispatched is to be rebuilt for display (Java FftAnalyzerWorker
   *  doAnalysis tail, the showNow gate). Skip the rebuild while the capture backlog is high -
   *  catch up rather than overrun - throttle it to DISPLAY_MIN_MS when caught up, and force it
   *  by DISPLAY_MAX_MS so the view never freezes. Java:
   *    long backlog   = rdr.getWritePos() - rdr.getReadPos();
   *    long sinceShow = System.nanoTime() - lastShowNanos;
   *    boolean showNow = paused.get() || sinceShow >= DISPLAY_MAX_NANOS
   *                    || (sinceShow >= DISPLAY_MIN_NANOS && backlog <= 2L * needed);
   *  The desktop asks this AFTER its analysis, on the same thread that would do the rebuild.
   *  Here the rebuild happens in the worker, so the question is asked one tick earlier, when the
   *  window is handed over - the same backlog and elapsed-time facts, read at dispatch. A tick
   *  the gate declines is folded and nothing more, so it costs a frame, never depth. */
  _showThisTick() {
    const backlog = this._fftReader ? (this._fftReader.getWritePos() - this._fftReader.getReadPos()) : 0;
    const now = performance.now();
    const sinceShow = now - this._lastShowMs;
    const caughtUp = backlog <= 2 * this.bufLen;
    // Never past the in-flight cap: the port keeps display frames in send order, so the cap only
    // bounds the outstanding payloads, and a tick declined for it is folded exactly like one the
    // throttle declined.
    const showNow = this._finalizeJobs < FINALIZE_JOBS_MAX
      && (this._fftPausedByStopN
        || sinceShow >= DISPLAY_MAX_MS
        || (sinceShow >= DISPLAY_MIN_MS && caughtUp));
    if (showNow) {
      this._lastShowMs = now;
      // The escalation past the drain: a display that keeps showing from the slow floor is
      // wall-clock proof of a debt the hop bias cannot repay. Count consecutive floor shows and
      // re-anchor once at the threshold - the average survives, the coverage gap is declared on
      // the banner, and the next window starts at live. A caught-up show resets the count.
      if (caughtUp) {
        this._floorShows = 0;
      } else if (++this._floorShows >= FLOOR_SHOWS_REANCHOR) {
        this._floorShows = 0;
        this._onOverrun();
      }
      // HOW FAR BEHIND LIVE this analysis is running, in seconds of capture - the quantity that
      // decides the gate above and the one thing about a slow FFT that cannot be seen on screen.
      // A backlog that only grows says the analysis is not keeping up with the capture: the feed
      // consumes exactly one hop per tick and never more, so a deficit once taken on is carried
      // until the window is re-anchored (an overrun or a discontinuity re-sync). While it exceeds
      // twice the window the caught-up branch cannot fire and the view falls back to the
      // DISPLAY_MAX_MS floor, which is the visible symptom.
      // Built only when debug logging is on: this runs on every displayed frame, and a
      // diagnostic must cost nothing when it is not being read - nor be able to throw.
      const rate = this._inRate();
      if (isDebug() && rate > 0) {
        debug(`[fft] frame shown - behind live ${(backlog / rate).toFixed(2)} s`
          + ` (${caughtUp ? 'caught up' : 'BEHIND, only the ' + DISPLAY_MAX_MS + ' ms floor shows frames'})`
          + ` | tick ${sinceShow.toFixed(0)} ms, analyse ${(this._workerMs || 0).toFixed(0)} ms,`
          + ` hop ${(Math.max(1, this.pendingHop) / rate * 1000).toFixed(0)} ms`);
      }
    }
    return showNow;
  }

  /** Reports main-thread milliseconds to the load meter (the shell hands the render loop's
   *  reporter in; nothing here knows what is on the other end). The fold below runs BETWEEN
   *  animation frames, so without this the meter would read the busiest part of a displayed
   *  frame as idle time and the scope's pacing would never engage. */
  setBusyReporter(reportBusy) {
    this._reportBusy = typeof reportBusy === 'function' ? reportBusy : null;
  }

  /** Worker reply seam: clears the in-flight flag, folds the tick in, then resumes
   *  the feed at once - Java's loop gathers the next hop the instant the analysis
   *  returns, and waiting for the next capture batch instead would cap the analysis
   *  rate at one window per batch event and let a backlog build for no reason. The
   *  fold runs through _handleWorkerResult; the resume happens whichever way it
   *  leaves (a gate firing or an epoch drop still owes the next window).
   *
   *  The whole seam is timed and reported: the fold, the display rebuild it may trigger
   *  (overlay + recompute + the view's own correction and draw, all synchronous under the
   *  emit) and the gather that follows are one uninterrupted stretch of main-thread work. */
  _onWorkerResult(r) {
    const t0 = this._reportBusy ? performance.now() : 0;
    // The display worker's port hand-over: the analyzer builds that worker on first use and
    // passes the port it will answer this thread on directly, so a finished frame does not wait
    // behind the analysis of the next window.
    if (r && r.kind === 'displayPort') {
      this._displayPort = r.port;
      this._displayPort.onmessage = (ev) => this._onDisplayResult(ev.data);
      return;
    }
    // The display worker could not be loaded. The frame it was asked for will never arrive, so
    // the slot is released NOW rather than at the end of a watchdog the operator would watch as
    // seconds of blank trace, and every later tick has its display prepared on the analysis
    // thread. A missing file therefore costs one frame, not five seconds.
    if (r && r.kind === 'finalizeFailed') {
      this._clearFinalizeInFlight();
      this._finalizeInPlace = true;
      return;
    }
    // The answer to a stopped-state re-emit request folds nothing and belongs to no tick: it
    // must not clear the in-flight flag of an analysis that is still running, and it must not
    // resume the feed. Straight to the display.
    if (r && r.reemit) {
      if (r.finalizePending) { this._armFinalizeWatchdog(); return; }
      if (!r.empty) this._emit(FftResult.adopt(r));
      if (this._reportBusy) this._reportBusy(performance.now() - t0);
      return;
    }
    this._busy = false;
    try {
      this._handleWorkerResult(r);
    } finally {
      this.feedFft();
      if (this._reportBusy) this._reportBusy(performance.now() - t0);
    }
  }

  /** A display frame prepared beside the next analysis. It arrives out of band, so it carries the
   *  accumulator epoch of the tick it was built from and that CARRIED value is what is compared:
   *  the single-slot epoch of a dispatch is only sound while exactly one analysis is in flight,
   *  which a display job is not. A frame from before a statistics restart is dropped whole.
   *  It never touches the depth readout - that follows the analysis answers, which arrive in tick
   *  order, so a late frame cannot walk the count backwards. */
  _onDisplayResult(r) {
    this._finalizeDelivered();
    if (!r || r.error) { if (r && r.error) console.error('display', r.error); return; }
    if (r.epoch !== this._accumEpoch) return;
    const adopted = FftResult.adopt(r);
    this._emit(adopted);
    // The displayed frame steers the frequency lock, exactly as an in-place frame does - it
    // carries its own transport timebase (samplesAbsStart / writePos, stamped in the worker), so
    // arriving one analysis later costs the loop nothing its transport gate does not already
    // span. A stopped-state re-emit is display-only: stale data must not trim a live generator.
    if (!adopted.reemit) this._steerFrom(adopted);
  }

  /** Counts a job into the in-flight cap and (re)arms the watchdog that must be able to release
   *  it. The display worker answers the main thread directly, so nothing but this timer stands
   *  between a worker that stopped answering and a frozen display: on expiry every outstanding
   *  job is written off AND the pipeline is latched off, so every later tick asks the analyzer to
   *  prepare the display itself - one watchdog interval of cost, then exactly the behaviour of
   *  the code before the split. */
  _armFinalizeWatchdog() {
    this._finalizeJobs = Math.min(FINALIZE_JOBS_MAX, this._finalizeJobs + 1);
    if (this._finalizeTimer) clearTimeout(this._finalizeTimer);
    this._finalizeTimer = setTimeout(() => {
      this._finalizeTimer = null;
      this._finalizeJobs = 0;
      this._finalizeInPlace = true;
      console.warn('FFT display preparation stopped answering - it runs on the analysis thread '
        + 'from here on');
    }, FINALIZE_WATCHDOG_MS);
  }

  /** Counts a delivered (or errored) frame out of the cap; the watchdog stays armed while a
   *  job is still out and disarms with the last one. */
  _finalizeDelivered() {
    this._finalizeJobs = Math.max(0, this._finalizeJobs - 1);
    if (this._finalizeJobs === 0 && this._finalizeTimer) {
      clearTimeout(this._finalizeTimer);
      this._finalizeTimer = null;
    }
  }

  /** Writes every outstanding job off - a worker failure, a teardown, a statistics restart:
   *  no reply is expected (or welcome) after any of these. */
  _clearFinalizeInFlight() {
    this._finalizeJobs = 0;
    if (this._finalizeTimer) { clearTimeout(this._finalizeTimer); this._finalizeTimer = null; }
  }

  _handleWorkerResult(r) {
    if (!this._fftOn) return;
    if (r.error) { console.error('analyze', r.error); this._clearFinalizeInFlight(); return; }

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
    const forever = this.foreverMode;
    const ringN = this.ringN;
    const accumulate = forever || ringN >= 2;

    // Mode-transition reset (Java lastAccumulate / lastForeverMode / lastRingN):
    // only when the new bound discards collected depth - a flip, a ring↔∞ switch,
    // or a SMALLER ring. (A larger ring just widens the exponential window.)
    if (accumulate !== this._lastAccumulate
        || forever !== this._lastForeverMode
        || (!forever && ringN < this._lastRingN)) {
      this._workerResetPending = true;   // the fold lives in the worker; the reset rides the next tick
      this._analysesTicks = 0;   // Java discard branch zeroes completedAnalyses too
      this._analysesDone = 0;
    }
    this._lastAccumulate = accumulate;
    this._lastForeverMode = forever;
    this._lastRingN = ringN;

    // The FOLD ran in the worker, beside the analysis (the display rebuild with it). What
    // comes back is its verdict: which gate fired, and the depth it reached. Every
    // RECOVERY stays here, because it is the capture cursor, the epoch and the drain skip that
    // have to move - none of which exist in the worker. Gate order is the desktop's: the cheap
    // time-domain pass on the raw window, then the spectral one; each has already taken the
    // previous tick back out of the average (its window shared nearly all these samples), and
    // the shared recovery re-anchors the cursor, arms the post-glitch drain, re-anchors the
    // accumulator's slope and bumps the epoch so the discarded window cannot fold in.
    if (accumulate) {
      if (USE_TIME_DISCONTINUITY && r.gate === 'time') {
        console.info('Time-domain discontinuity in the tick window - re-sync');
        this._onSignalDiscontinuity();
        return;
      }
      if (r.gate === 'spectral') {
        const d = r.gateDiag || {};
        debug('[fft] spectral gate reject - gates ' + JSON.stringify(d.gates)
          + ` score ${d.score != null ? d.score.toFixed(2) : '?'} thr ${d.scoreThresh != null ? d.scoreThresh.toFixed(2) : '?'}`
          + ` | power ${d.powerDb != null ? d.powerDb.toFixed(1) : '?'} med ${d.powerMed != null ? d.powerMed.toFixed(1) : '?'} thr ${d.powerThresh != null ? d.powerThresh.toFixed(1) : '?'}`
          + ` | pedestal ${Number.isFinite(d.pedestal) ? d.pedestal.toFixed(1) : '-'} thr ${Number.isFinite(d.pedestalThresh) ? d.pedestalThresh.toFixed(1) : '-'}`);
        this._onSignalDiscontinuity();
        return;
      }
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
    const accumFrames = r.accumFrames != null ? r.accumFrames : 0;
    if (accumulate) {
      // The depth in ticks is taken as the analyzer states it, never re-derived here: it owns the
      // accumulator and the tick's frames-per-tick, and deriving it a second time from fields
      // that may or may not have travelled is how one average came to be reported as two numbers,
      // one twice the other.
      const depthTicks = r.accumDepthTicks != null ? r.accumDepthTicks : 0;
      this.framesDone = forever ? depthTicks : Math.min(this.avgTarget + 1, this._analysesTicks);
      // Frame depth (Java getAccumulatedFrames) - the predistortion frame-depth readout,
      // NOT the stop-after-N / completedAnalyses key (that's the per-tick count above).
      this._analysesDone = accumFrames;
    } else {
      // No averaging (Averages Off or 1): the depth IS the tick's own frame count, and it is
      // taken from the geometry THIS side stated for the tick rather than read back off the
      // answer. A tick the display gate declined carries counters only - the frame count travels
      // with a DISPLAYED result and with nothing else - so reading it there produced undefined,
      // Math.min made that NaN, and both the averages label and the predistortion frame depth
      // read NaN from the first declined tick onward, which at any hop shorter than the display
      // throttle is nearly every tick.
      const tickFrames = r.frameCount != null ? r.frameCount : this.perTickFrames;
      this.framesDone = Math.min(this.avgTarget, tickFrames);
      this._analysesDone += tickFrames;
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
    if (forever && this._prefs.fftStopAfterNEnabled.get() && !this._fftPausedByStopN
        && displayedAverages >= this._prefs.fftStopAfterN.get()) {
      this._fftPausedByStopN = true;
      // The tick that reaches the cap is the LAST one, and what the operator is left looking at
      // must be the average it completed. The display gate decided about this tick before the
      // count was known (at dispatch, see _showThisTick), so a declined one would freeze the
      // view one average short with no further tick coming to correct it. Ask for the rebuild
      // that was skipped - the same request a calibration change makes on a stopped FFT.
      if (!r.finalized && !r.finalizePending) this.reemitDisplay(null);
      // ONE seam for every analyser self-stop (Java FftAnalyzerWorker publishes
      // FFT_RECORDING_AUTO_STOPPED for stop-after-N and capture death alike): the pane's
      // subscriber tears the consumer down and un-lights the Record LED.
      MessageBus.instance().publish(Events.FFT_RECORDING_AUTO_STOPPED);
    }

    // The display of THIS tick is being prepared beside the next analysis and will arrive on its
    // own; there is nothing to show yet. The FLL is steered from the frame that arrives, so the
    // steer waits with it - one tick of extra transport, on a loop whose own gate already spans
    // a full transport round trip.
    if (r.finalizePending) { this._armFinalizeWatchdog(); return; }

    // A tick the display gate declined at dispatch (_showThisTick) was folded and nothing more:
    // it carries no rebuilt spectrum, so there is nothing to steer the FLL from and nothing to
    // show. The depth it added is already counted above.
    if (!r.finalized) return;

    // The FFT pane + the predistortion read the EMITTED result. The worker->main transfer is a
    // plain, lossy snapshot, so re-adopt the FftResult prototype (methods rawHarmonicDbFs /
    // deepCopy / noisePeakFloorDbFs). The raw pre-de-embed phasors were stamped in the worker,
    // before its own de-embed, and travel as plain fields.
    r = FftResult.adopt(r);

    this._emit(r);
    // Steer AFTER the emit - the desktop's order (the view finalizes the displayed frame, then
    // calls the controller's frequency lock), and the frame carries its own transport timebase
    // so the same call serves a frame arriving from the display worker.
    this._steerFrom(r);
  }

  /** FLL - steer the generator so each captured tone returns to its exact target bin
   *  (nulls the DAC-ADC clock offset). Driven through the Java FrequencyFll deadbeat loop:
   *  ONE bounded correction per fully-observed transport round trip, NOT a per-frame
   *  proportional integrator. The published generator frequency is snapped + correction.
   *  Runs once per DISPLAYED frame, whichever path prepared it - in place beside the analysis,
   *  or in the display worker with the frame arriving out of band - exactly as the desktop
   *  steers from its result-display pipeline. Java FftController.applyFrequencyLock branches on
   *  the form: single-tone runs one loop off slot.fundamentalHzRefined; DUAL_TONE runs TWO
   *  independent loops (fll / fll2) off the per-tone detected frequencies and publishes
   *  GENERATOR_FREQ_TRIM / _2. This controller mirrors that with _fll / _fll2, refining the two
   *  tones itself off r.amplitudeDbFs (the CONTROLLER holds no per-tone estimate - the IMD
   *  table runs in the view). */
  _steerFrom(r) {
    if (this.fllArmed()) {
      if (isDualTone(this._prefs.genSignalForm.get())) {
        this._steerDualTone(r);
      } else if (r.fundamentalHzRefined > 0) {
        this._steerSingleTone(r);
      } else if (traceFll()) {
        console.warn(`FLL SKIP: armed but no fundamental - fundRefined=${r.fundamentalHzRefined}`);
      }
    } else if (traceFll()) {
      // Steering skipped entirely: report which precondition failed - the classic "inert" path.
      console.warn(`FLL SKIP: ${this._fllGateState()}`);
    }
  }

  /** Resets the frequency-lock loop(s) so alignment converges fresh from zero, AND tells the
   *  generator to drop any residual trim (Java FftController.resetFrequencyLock, publishing
   *  GENERATOR_FREQ_TRIM_RESET): clearing only the loop state would leave the generator at the
   *  last published trim, and the fresh loop - which assumes generator = target + correction -
   *  would overshoot by exactly that stale amount on its first correction. */
  _resetFrequencyLock() {
    // Re-arm the magnitude-drift watch: the next steered result baselines the fundamental
    // level(s); the comparison fires once alignment settles.
    this._alignWatchArmed = true;
    this._alignWatchBaselined = false;
    this._fll.reset();
    this._fll2.reset();
    MessageBus.instance().publish(Events.GENERATOR_FREQ_TRIM_RESET, null);
  }

  /** Magnitude-drift watch (Java FftController.watchAlignmentMagnitudeDrift): baselines the
   *  fundamental level(s) on the first steered result after a lock reset, then - once the
   *  loop(s) report aligned - compares the current level(s) against that baseline ONE time. A
   *  drift beyond ALIGN_DRIFT_DB means the running average still contains pre-alignment frames
   *  at a depressed level, so FFT_ALIGN_MAG_DRIFT carries the largest per-tone delta (dB) to the
   *  pane's blinking reset-statistics hint. `f2Db` is NaN in single-tone mode. */
  _watchAlignmentMagnitudeDrift(f1Db, f2Db, aligned) {
    if (!this._alignWatchArmed || !Number.isFinite(f1Db)) return;
    if (!this._alignWatchBaselined) {
      this._alignWatchBaseF1Db = f1Db;
      this._alignWatchBaseF2Db = f2Db;
      this._alignWatchBaselined = true;
      return;
    }
    if (!aligned) return;                       // loop still pulling - keep waiting
    this._alignWatchArmed = false;              // one-shot per re-arm
    let delta = Math.abs(f1Db - this._alignWatchBaseF1Db);
    if (Number.isFinite(f2Db) && Number.isFinite(this._alignWatchBaseF2Db)) {
      delta = Math.max(delta, Math.abs(f2Db - this._alignWatchBaseF2Db));
    }
    if (delta > ALIGN_DRIFT_DB) MessageBus.instance().publish(Events.FFT_ALIGN_MAG_DRIFT, delta);
  }

  /** Whether the frequency lock may steer at all - Java FftController.applyFrequencyLock's
   *  prerequisite gate, all four conditions: the generator is snapped to the FFT bin grid (an
   *  off-grid tone gives the loop nothing to lock to - its scalloped estimates would be chased
   *  as drift), the fundamental comes from the generator, an align mode is selected, and the
   *  generator is actually emitting. Inert means inert: no steer, no correction accumulated. */
  fllArmed() {
    const p = this._prefs;
    if (!p.genSnapToFftBin.get()) return false;
    if (!p.fftFundFromGenerator.get()) return false;
    if (!this._fllOn()) return false;
    return !!this._genOn;
  }

  /** Names the first failed frequency-lock precondition, for the trace log only. */
  _fllGateState() {
    const p = this._prefs;
    if (!p.genSnapToFftBin.get()) return 'snap-to-bin OFF';
    if (!p.fftFundFromGenerator.get()) return 'fundamental not from generator';
    if (!this._fllOn()) return 'align mode NONE';
    return this._genOn ? 'armed' : 'generator not emitting';
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
        r.samplesAbsStart, r.writePos, this._inRate(), this.N);
      this.genFreq = fllTarget + this._fll.correction;
      // Publish the trim; GeneratorController applies it to its own worklet (was a direct post).
      MessageBus.instance().publish(Events.GENERATOR_FREQ_TRIM, this.genFreq);
      if (traceFll()) {
        // Java log.warn("FLL t1: target=... meas=... corr=... pub=..."), plus the transport timebase +
        // gate so an inert loop is diagnosable (gate held forever => winStart never reaches
        // visibleFrom; corr stuck at 0 => lock-band/plausibility hold).
        const held = this._fll.correctionVisibleFrom >= 0
          && r.samplesAbsStart < this._fll.correctionVisibleFrom;
        console.warn(`FLL t1: target=${fllTarget.toFixed(6)} meas=${r.fundamentalHzRefined.toFixed(6)}`
          + ` corr=${this._fll.correction >= 0 ? '+' : ''}${this._fll.correction.toFixed(6)}`
          + ` pub=${this.genFreq.toFixed(6)} winStart=${r.samplesAbsStart} writePos=${r.writePos}`
          + ` gate[${gateBefore}->${this._fll.correctionVisibleFrom}] ${held ? 'HELD' : 'open'}`);
      }
      // Status-display lock latch ONLY (Java ALIGN_DONE_PPM aligned flag): lights the "locked"
      // readout once the measured error sits within tolerance. It does NOT gate the steering -
      // the deadbeat loop's own transport gate provides the damping, and the loop keeps
      // correcting ongoing DAC↔ADC clock drift.
      this._updateLockLatch(Math.abs(this.fllErrHz / this.binW) < 5e-4);
      // Java FftController.applyFrequencyLock :361-363 - aligned within ALIGN_DONE_PPM of the
      // target; the drift watch compares the fundamental level once that holds.
      const aligned = Math.abs(r.fundamentalHzRefined - fllTarget) <= fllTarget * ALIGN_DONE_PPM * 1e-6;
      this._watchAlignmentMagnitudeDrift(r.fundamentalDbFs, NaN, aligned);
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

    const ok1 = this._steerOneDualLoop(this._fll, Events.GENERATOR_FREQ_TRIM, t1, f1Hz, 't1', r);
    const ok2 = this._steerOneDualLoop(this._fll2, Events.GENERATOR_FREQ_TRIM_2, t2, f2Hz, 't2', r);

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
    // Java FftController.applyFrequencyLock :344-347 - both tones within ALIGN_DONE_PPM of
    // their targets; the drift watch reads the per-tone levels off the refined peaks (Java
    // reads imd.f1DbV / f2DbV - a level DIFFERENCE, so the dBFS/dBV offset cancels).
    const aligned = t1 > 0 && t2 > 0
      && Math.abs(f1Hz - t1) <= t1 * ALIGN_DONE_PPM * 1e-6
      && Math.abs(f2Hz - t2) <= t2 * ALIGN_DONE_PPM * 1e-6;
    const lvlLow = p1 ? p1.levelDbFs : NaN, lvlHigh = p2 ? p2.levelDbFs : NaN;
    this._watchAlignmentMagnitudeDrift(t1 <= t2 ? lvlLow : lvlHigh, t1 <= t2 ? lvlHigh : lvlLow, aligned);
  }

  /** Runs ONE dual-tone deadbeat loop for a single tone: plausibility-gate the measurement,
   *  update the loop, publish `target + correction` on `trimEvent`. Returns true when the
   *  measurement passed the gate and steered (so the caller can fold it into the locked latch).
   *  Mirrors one half of Java's dual branch (:275-308) including the GATED trace line. */
  _steerOneDualLoop(fll, trimEvent, target, measuredHz, tag, r) {
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
      r.samplesAbsStart, r.writePos, this._inRate(), this.N);
    const pub = target + fll.correction;
    MessageBus.instance().publish(trimEvent, pub);
    if (traceFll()) {
      const held = fll.correctionVisibleFrom >= 0 && r.samplesAbsStart < fll.correctionVisibleFrom;
      console.warn(`FLL ${tag}: target=${target.toFixed(6)} meas=${measuredHz.toFixed(6)}`
        + ` corr=${fll.correction >= 0 ? '+' : ''}${fll.correction.toFixed(6)}`
        + ` pub=${pub.toFixed(6)} winStart=${r.samplesAbsStart} writePos=${r.writePos}`
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

  /** Stamps the display-side fields on a finished result and publishes it as FFT_RESULT_AVAILABLE
   *  (Java FftAnalyzerWorker publishes the slot after each analysis; the FFT pane subscribes,
   *  as Java's FftView does). */
  _emit(r) {
    const rate = this._inRate();
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
      on: this._fllOn(), locked: this.fllLocked, errBins: this.fllErrHz / this.binW,
      ppm: this.snapped > 0 ? this.fllErrHz / this.snapped * 1e6 : 0,
      genFreq: this.genFreq, rejected: this.rejectedCount,
    };
    // The tick rate rides the hop actually gathered (the drawn pending hop), which
    // under the random overlap changes every tick.
    const liveHop = Math.max(1, this.pendingHop);
    r.perf = {
      fftFps: this._fftFps, targetFps: rate / liveHop, workerMs: this._workerMs,
      dropped: this.dropped, threads: this.poolSize, overlapPct: this.overlapPct, hopMs: liveHop / rate * 1000,
    };
    r.inRate = rate; r.snapped = this.snapped;
    // The IMD table is computed off the DE-EMBEDDED spectrum, so it runs in the view correction
    // (fft/fft-view-correction.js) alongside the .frc de-embed, not here.

    // Remembered for reemitDisplay (the stopped-state cal re-apply) - and as the identity
    // guard so a LOADED .fft file in the pane is never overwritten by a stale re-emit.
    this._lastEmitted = r;
    MessageBus.instance().publish(Events.FFT_RESULT_AVAILABLE, r);
  }

  /** Asks the analyzer to rebuild the DISPLAYED result from the RAW accumulator (which survives
   *  a stop) so a calibration Active/With-noise/load/remove change applies to a STOPPED FFT too.
   *  Java's .frc de-embed is a plot-time transform over the raw lastResult, so a toggle there
   *  just repaints; here the raw average and the correction both live in the worker, so the
   *  rebuild is REQUESTED and its answer arrives asynchronously as FFT_RESULT_AVAILABLE - the
   *  pane painting a result that lands while not recording. No FLL steer, no stop-after -
   *  display only.
   *  `base` must be the pane's CURRENT result: when it isn't the controller's own last emit (a
   *  loaded .fft file), nothing is requested. Returns whether a rebuild was asked for; the worker
   *  answers empty - and the display keeps what it shows - when nothing is accumulated yet.
   *  Limitation: a non-averaged run leaves the accumulator empty, and a torn-down analyzer (the
   *  FFT was never started this session) has nothing to ask. */
  reemitDisplay(base) {
    const r = this._lastEmitted;
    if (!r || (base && base !== r) || !this.worker) return false;
    this.worker.postMessage({
      reemit: true, mainsF0: this._fftMainsF0,
      wantCalGain: isDualTone(this._prefs.genSignalForm.get()) && this._calEntries.length > 0,
      epoch: this._accumEpoch,
      finalizeInPlace: this._finalizeInPlace,
      // Refused while a display frame is already being prepared: its answer would arrive after
      // this one and overwrite it. The display keeps what it shows and the next request succeeds.
      jobOutstanding: this._finalizeJobs > 0,
    });
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
