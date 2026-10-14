/*
 * Phonalyser - precision audio measurement workbench.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.edgo.audio.measure.gui.fft;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.swt.widgets.Display;
import org.edgo.audio.measure.common.AppPaths;
import org.edgo.audio.measure.common.Constants;
import org.edgo.audio.measure.dsp.FftBinSnap;
import org.edgo.audio.measure.dsp.MainsCombFilter;
import org.edgo.audio.measure.dsp.SpectralDiscontinuityDetector;
import org.edgo.audio.measure.dsp.TimeDiscontinuityDetector;
import org.edgo.audio.measure.dsp.ToneLobeLift;
import org.edgo.audio.measure.enums.AlignGenerator;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.FundamentalPhaseAlignment;
import org.edgo.audio.measure.enums.GenChangeCause;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.dsp.MainsFilters;
import org.edgo.audio.measure.dsp.MainsTimeFilter;
import org.edgo.audio.measure.enums.MainsSuppression;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.CoherentAccumulator;
import org.edgo.audio.measure.fft.CoherentAccumulator.FrameTurns;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.fft.FftResultPool;
import org.edgo.audio.measure.fft.PilotToneSelector;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.GeneratorOutput;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.bus.PilotToneRequest;
import org.edgo.audio.measure.gui.common.DebugSwitches;
import org.edgo.audio.measure.gui.fft.predistortion.PredistortionEngine;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.gui.sound.SharedCapture;
import org.edgo.audio.measure.gui.sound.SignalBufferReader;
import org.edgo.audio.measure.sound.CaptureEndReason;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Background analyser worker that owns the FFT compute thread and its
 * {@link FftAnalyzer} instance.  Holds none of the view's state - the
 * worker thread is fully decoupled from {@link FftView} via the bus:
 * each completed analysis is {@linkplain FftResult#deepCopy
 * deep-copied} and published on
 * {@link Events#FFT_RESULT_AVAILABLE}; subscribers (typically
 * {@link FftView}) keep the snapshot and paint from it without
 * touching anything the worker owns.
 *
 * <p>Lifecycle is owner-driven: {@code UIEngines} constructs one worker
 * for the application lifetime (it survives content rebuilds, keeping the
 * averaging accumulator); {@link FftController} drives {@link #start} /
 * {@link #stop}.
 */
@Log4j2
public final class FftAnalyzerWorker {

    /** Idle tick when there's no buffer / not enough fresh samples. */
    private static final int IDLE_TICK_MS = 250;
    /** Maximum sleep between analysis attempts. */
    private static final int MAX_SLEEP_MS = 250;
    /** Per-tick display throttle: the cross-tick accumulator runs every tick, but
     *  the O(N) display rebuild (overlay + recomputeStats + dBV lift + deep-copy
     *  publish) is gated to AT MOST one per {@code DISPLAY_MIN_NANOS} when caught
     *  up, and SKIPPED while the capture backlog is high so the worker catches up
     *  instead of overrunning - but never deferred past {@code DISPLAY_MAX_NANOS}
     *  so the view can't freeze. */
    private static final long DISPLAY_MIN_NANOS =  40_000_000L;   // ≤25 Hz refresh when keeping up
    private static final long DISPLAY_MAX_NANOS = 500_000_000L;   // ≥2 Hz even while behind
    /** Mains-comb re-track cadence in analysis ticks.  The tracker's lock
     *  is an EWMA with ≈33-detection memory, so tracking every Nth tick
     *  changes the smoothed lock negligibly while freeing the ~208
     *  Goertzel sweeps (30-80 ms at large fftSize) from most ticks -
     *  budget that 93.75 % overlap at 1 M needs to stay under its 170 ms
     *  hop. */
    private static final int MAINS_TRACK_TICK_INTERVAL = 5;
    /** Lowest harmonic ORDER the analysis always evaluates, whatever the
     *  preference states: the ladder H2 to H9 the distortion readouts are
     *  built on.  The count handed to the analysis is one less - it counts
     *  from the SECOND harmonic - and the protected ladder of the pilot's
     *  selection is that count plus one again. */
    private static final int MIN_CALC_MAX_HARMONIC = 9;
    private long lastShowNanos;

    private final Display  display;

    private final FftAnalyzer analyzer = new FftAnalyzer();
    /** The cross-cycle average: the sums of the frames since the collection's
     *  first, each turned back to that first frame, and the fundamental's
     *  fractional bin re-pinned from their phase slope.  Restarted by
     *  {@link #accumulateIntoForeverBuffer} on the collection's first frame
     *  and on a change of the transform length or the averaging mode. */
    private final CoherentAccumulator accumulator = new CoherentAccumulator();
    /** The predistortion engine every published result is handed to, on this
     *  worker's thread; null while none is registered.  Stored, not owned: the
     *  predistortion dialog creates the engine, registers and unregisters it. */
    private volatile PredistortionEngine predistortionEngine;

    private volatile SignalBufferReader reader;
    /** True while this worker holds a {@code SharedCapture} reference (acquired
     *  in {@link #start}, released in {@link #stop}).  The worker owns its own
     *  capture lifecycle, so the view / pane never touch the sample buffer. */
    private boolean captureHeld;
    private final    AtomicBoolean      paused = new AtomicBoolean(false);
    /** Armed by a change of the generated signal - the bus event the average
     *  restarts on - and consumed on the worker's thread at the next tick: the
     *  alignment starts afresh, with a new reference baseline and the pilot
     *  elected again.  A tone that changed has a new phase through the notch,
     *  so an excess taken on the old tone would be applied to the new one. */
    private final    AtomicBoolean      alignRestartPending = new AtomicBoolean(false);
    /** Bumped by every {@link #resetStatistics()}.  An analysis tick stamps
     *  the epoch at its start and discards its result when a reset landed
     *  while it was assembling / analysing: such a window straddles the
     *  signal change - accumulating it would poison the freshly cleared
     *  average, and its stale fundamental would feed the FLL one garbage
     *  trim (enough to drag the live generator far off-frequency). */
    private final    AtomicLong         resetEpoch = new AtomicLong();
    /** Set by {@link #resetStatistics()} while the worker runs; consumed at
     *  the top of the next tick, where the worker itself wipes the
     *  accumulator / mains tracking.  Those structures are worker-owned and
     *  not thread-safe - wiping them from the resetting thread mid-tick can
     *  NPE the accumulate loops or leave the detector half-reset. */
    private final    AtomicBoolean      resetPending = new AtomicBoolean();
    /** Companion to {@link #resetPending} for resets caused by a change of
     *  the GENERATED SIGNAL: the worker discards
     *  {@link FrequencyAligner#OUTPUT_DRAIN_SKIP_SEC} of samples after the re-anchor before
     *  building the first window - see
     *  {@link #resetStatisticsAfterSignalChange()}. */
    private final    AtomicBoolean      drainSkipPending = new AtomicBoolean();
    /** Samples still to discard after a signal-change re-anchor
     *  (worker-thread only). */
    private long drainSkipRemaining;
    /** Sample count the NEXT inter-tick wait is for, set by
     *  {@link #doAnalysis}'s data-driven wait sites and consumed by
     *  {@link #workerLoop}: the loop then BLOCKS on the ring
     *  ({@link SignalBufferReader#awaitAvailable}) until that many fresh
     *  samples exist - waking exactly when the next span is ready, and
     *  instantly on capture death - instead of sleeping a rate-derived
     *  estimate.  {@code 0} = the wait is time-driven (idle tick).
     *  Worker-thread only. */
    private int awaitSamplesNext;
    /** Ticks since the mains comb was last re-tracked (worker-thread
     *  only); see {@link #MAINS_TRACK_TICK_INTERVAL}. */
    private int  mainsTrackTick;
    /** Single-slot, coalescing handoff of the latest result to the UI thread.
     *  The worker overwrites it every tick (newest wins) and posts ONE drain
     *  runnable only on the empty->full transition, so the SWT asyncExec queue
     *  never accumulates more than one multi-MB spectrum however far the UI
     *  repaint falls behind the (parallelized, fast) per-tick production. */
    private final AtomicReference<FftResult> latestForUi = new AtomicReference<>();
    /** Recycled {@link FftResult} slots.  A slot is acquired at the top of
     *  a tick and released exactly once - on every discard path, on a
     *  coalesce overwrite in {@link #publishResult}, or after the
     *  synchronous bus dispatch in the drain runnable (subscribers
     *  deep-copy what they keep, so the slot is free once {@code publish}
     *  returns).  In steady state at most 3 slots circulate (one being
     *  filled, one parked in {@link #latestForUi}, one in the UI
     *  dispatch). */
    private final FftResultPool resultPool = new FftResultPool();
    /** Averages PERFORMED since the last reset, unbounded: the collection's
     *  first cycle seeds the accumulator - it starts the average instead of
     *  adding to one - so it does not raise this, and nothing caps it
     *  afterwards.  What a consumer counting its own target against the
     *  average's progress reads (the predistortion rounds). */
    @Getter
    private volatile int                completedAnalyses;
    /** Averages currently INSIDE the average, which is what every "N averages"
     *  readout states: equal to {@link #completedAnalyses} while the averaging
     *  is unbounded, and the configured window length once a moving average has
     *  filled it - a moving average of N may have performed twenty thousand
     *  averages and still hold the last N. */
    @Getter
    private volatile int                averagesInWindow;
    @Getter
    private volatile boolean            running;
    /** WHY the capture ended from below (device lost / delivery stalled) -
     *  CLAIMED from the shared buffer when the reader answers terminally,
     *  consumed by the pane after the auto-stop event.  {@code null} while
     *  the capture is alive, after a plain stop-after-N pause, and when
     *  another consumer of the same dead capture claimed the one report. */
    @Getter
    private volatile CaptureEndReason   captureEndReason;
    /** False until the very first analysis has run since {@link #start}. */
    @Getter
    private volatile boolean firstFrameDone;
    /** Tracks generator on/off transitions so the data collection is
     *  reset whenever the user starts or stops the generator. */
    private volatile Boolean lastGeneratorActive;

    // ─── Contiguous sliding analysis window ─────────────────────────────────
    /** The selected channel's contiguous analysis window, slid forward one
     *  hop per tick by pulling fresh samples from {@link #reader} (a consuming
     *  FIFO cursor).  Retained across ticks - the overlap region - so overlap
     *  works WITHOUT re-reading the ring.  {@link #winValid} marks it as
     *  holding a full {@code needed}-sample contiguous block; a window-size /
     *  channel change or a capture overrun invalidates it and it is rebuilt
     *  from fresh samples.  Worker-thread only (except the volatile flags). */
    private double[] winBuf = new double[0];
    private long    winAbsStart;
    private int     winLen;
    private int     winNeeded     = -1;     // `needed` the window was built for
    private boolean winChannelLeft;         // channel the window currently holds
    /** The overlap mode the window's hops are drawn under.  A mode change
     *  rebuilds the window: the pending hop was drawn under the old mode's
     *  bounds, and a hop gathered into a hop buffer sized for the new ones
     *  would overrun it; the rebuild's first-window path draws it afresh. */
    private FftOverlap winOverlap;
    private volatile boolean winValid;
    /** Samples of the pending window / hop already gathered into
     *  {@link #winBuf} / {@link #hopBuf}.  A span is collected across as many
     *  ticks as it takes - it may be far longer than the capture ring - so
     *  these count the progress through it; both are cleared together with
     *  {@link #winValid} by {@link #invalidateWindow()}, because a rebuild must
     *  never splice samples from either side of a re-anchor.  Worker-thread
     *  only; volatile for the UI's next-frame progress readout. */
    private volatile int winFill;
    private volatile int hopFill;
    /** Set on (re)start / reset; the worker re-anchors {@link #reader} to the
     *  latest sample on its next tick so the window rebuilds from fresh data
     *  (no stale pre-reset samples leak in).  Re-anchoring on the worker thread
     *  keeps the cursor single-owner even though resets arrive from others. */
    private volatile boolean reAnchorPending = true;
    /** Reusable per-tick scratch: {@code analyzeBuf} is the analysis copy (the
     *  comb / FFT window mutate it in place, so it must not be {@link #winBuf}),
     *  {@code hopBuf} stages the fresh hop pulled from the reader each tick.
     *  {@code hopBuf} is sized to the LARGEST possible hop of the current
     *  configuration and only its first {@link #pendingHopSamples} entries are
     *  in use - a random-mode hop change must never resize it mid-gather,
     *  because that would discard the partial fill and tear the contiguous
     *  stream the de-rotation depends on. */
    private double[] analyzeBuf = new double[0];
    private double[] hopBuf     = new double[0];
    /** The OPPOSITE channel's copy of the very same spans as {@link #winBuf} /
     *  {@link #hopBuf}, filled by the SAME reader call (both output arrays of
     *  one {@code read} - a second call would advance the cursor twice and tear
     *  the stream).  Held only while the fundamental phase alignment runs in
     *  mode {@link FundamentalPhaseAlignment#REFERENCE_CHANNEL}, and dropped as
     *  soon as it does not, so the mode coming back on cannot resume a window
     *  whose reference half stopped being filled.  Their fill progress IS
     *  {@link #winFill} / {@link #hopFill} - one call fills both arrays to the
     *  same offset - so {@link #invalidateWindow()} invalidates them with the
     *  analysed window.  No analysis copy is needed: the single-bin phasor
     *  reads the samples without mutating them, and the reference feed is
     *  deliberately not mains-filtered. */
    private double[] refWinBuf;
    private double[] refHopBuf;
    /** The frequency lock's readings: each tone's phase at its bin on the
     *  previous cycle's frame, on the analysed channel and on the reference
     *  channel, so that the next cycle's reading gives the tone's frequency
     *  from the phase step over the advance between the two windows.
     *  Forgotten with the window: a rebuilt window has no previous frame to
     *  step from. */
    private final TonePhaseMemory analysedTones  = new TonePhaseMemory();
    private final TonePhaseMemory referenceTones = new TonePhaseMemory();
    /** True while the reference mirror exists for the trace alone - mode
     *  {@link FundamentalPhaseAlignment#PILOT_TONE} with
     *  {@link DebugSwitches#TRACE_PILOT_PHASE} - and not for the
     *  reference-channel alignment.  The frequency lock's reference
     *  measurement and the reference feed check then stay off, as in every
     *  mode that keeps no mirror: the trace reads the mirror, it steers
     *  nothing. */
    private boolean  refMirrorForTrace;
    /** The hop the window slides by next - drawn per tick in the random
     *  overlap mode, constant otherwise.  FROZEN while its gather is in
     *  flight: it is redrawn only after the slide it sizes has completed.
     *  It is also the spacing between the analysis buffer's two frames, so
     *  the second frame of one tick is exactly the first frame of the next
     *  and the raw-frame cache keeps hitting.  Volatile for the UI's
     *  next-frame progress readout. */
    private volatile int pendingHopSamples;
    /** The random mode's position on its hop lattice, 0 .. RAND_HOP_STEPS-1:
     *  every draw takes the next step and wraps, so the spacings visit the
     *  whole lattice in a fixed cycle (0 -> 1 -> ... -> 15 -> 0) instead of
     *  being drawn at random.  The de-rotation is exact for any hop; the
     *  sequence only decides WHICH spacings occur and in what order - a full
     *  cycle guarantees every lattice value the same share of the average.
     *  Restarts at 0 with every fresh first window. */
    private int hopStep;

    /** Mains-hum comb, lazily built for the current sample rate.  Used only
     *  when {@code fftMainsSuppression == IIR_COMB}: it only TRACKS the mains
     *  frequency here (the rejection is a plot-time spectral correction in
     *  FftView, leaving the accumulator raw - the samples are not filtered). */
    private MainsCombFilter mainsComb;
    private int             mainsCombSampleRate;
    /** Time-domain mains filter (synchronous subtraction / LMS) applied to the
     *  captured window IN PLACE before the FFT, for the SYNC_SUBTRACT / LMS
     *  modes.  The IIR comb stays a plot-time spectral correction instead - see
     *  {@code mainsF0Hz} / FftView. */
    private MainsTimeFilter  mainsTimeFilter;
    private MainsSuppression mainsTimeFilterMode = MainsSuppression.NONE;
    private int              mainsTimeFilterSampleRate;
    private Thread worker;

    // Perf instrumentation for DebugSwitches.SHOW_FFT_ANALYZE_TIME - two
    // nanoTime marks splitting a tick's latency into its two legs.
    /** Set at the top of {@code doAnalysis()}; logged against it at the end of
     *  the analysis ("1. FFT analyze took") - the pure worker-thread compute time. */
    private long startAnalyze;
    /** Set in {@code publishResult()} when the result is handed to the SWT
     *  asyncExec queue; logged against it in {@link #uiGotResult} ("2. FFT result
     *  achieved UI") - the worker -> UI-thread hand-off latency, including any
     *  coalescing wait behind a slow repaint. */
    private long startSendToUi;

    /** Fingerprint of (fftSize, window, channel, sampleRate) the average was
     *  last collected under.  Mismatch => the accumulator restarts. */
    private volatile long accumConfigFingerprint = Long.MIN_VALUE;
    /** {@link Events#GENERATOR_SIGNAL_CHANGED}: a changed tone ends the
     *  alignment's collection, consumed by the worker's own thread at the next
     *  tick (see {@link #alignRestartPending}).  An FLL trim is a sub-Hz
     *  alignment of the generator with the FFT bin grid, under which the
     *  averaging KEEPS running so the lock converges. */
    private final Consumer<GenChangeCause> alignRestartOnGenChange = cause -> {
        if (cause == GenChangeCause.FLL_TRIM) return;
        alignRestartPending.set(true);
    };
    /** A change of the alignment mode ends the collection the same way: the
     *  mode decides what the time base is, so nothing measured under the old
     *  one may be applied under the new.  Entering the pilot mode elects a
     *  pilot and asks the generator for it; leaving it is answered by the
     *  take-down the tick makes itself.  Written on the thread that changes
     *  the setting and consumed by the worker's own at the next tick, like a
     *  changed tone. */
    private final Consumer<FundamentalPhaseAlignment> alignModeChanged =
            mode -> alignRestartPending.set(true);
    /** The pilot lines' level (dBV) and the margin (dB) a line must hold over
     *  its local floor, kept current by subscription.  Neither restarts
     *  anything: the generator rescales its running lines itself, and the
     *  margin applies from the next frame the lines are tested on. */
    private volatile double pilotLevelDbv;
    private volatile double pilotMarginDb;
    private final Consumer<Double> pilotLevelChanged  = v -> pilotLevelDbv = v;
    private final Consumer<Double> pilotMarginChanged = v -> pilotMarginDb = v;
    /** The tones the generator emits, as it answered ONCE at the start of the
     *  collection - index 0 is tone 1, index 1 tone 2 (0.0 when the waveform
     *  has no second tone); {@code null} while no generator states its tones.
     *  A change of the generated signal resets the whole collection, so the
     *  pair is asked for again there and nowhere else; between resets the
     *  frequency lock is the only thing that moves the tones, and it moves
     *  them from here, so every trim it publishes is written back into this
     *  pair.  Worker-thread state. */
    private double[] heldEmittedHz;
    /** Set where a collection starts; the next analysis cycle asks the
     *  generator for its pair and clears it. */
    private boolean  emittedPairPending = true;

    // ─── Cross-tick accumulator (forever-mode only) ────────────────────────
    /** Absolute sample-stream position of the first contributing tick's
     *  frame-0 (= its {@code samplesAbsStart}).  Every subsequent tick's
     *  spectrum is phase-rotated by the time delta from this reference
     *  to keep tones coherent across ticks. */
    private long    accumRefSampleStart;
    /** The tones' reference phases on the reference tick, radians, or
     *  {@link Double#NaN} where that tick read none - {@link #referenceTone1Rad}
     *  and {@link #referenceTone2Rad} as they stood then: a tick's rotation is
     *  its own reading less this one, in either aligning mode. */
    private double  accumRefTone1Rad = Double.NaN;
    private double  accumRefTone2Rad = Double.NaN;
    /** The fundamental's whole bin, {@code round(κ)} pinned at the
     *  collection's first frame and held for the collection: every reading
     *  of the fundamental that is compared across cycles - the alignment's
     *  phase, the reference channel's, the tracking's, the DAC phase stamp,
     *  the traces - reads this one bin, so no two of them ever stand on
     *  different bins; 0 while no collection runs. */
    private int     accumIntFundBinRounded;
    /** Discontinuity-detector toggle.  The frequency-domain running-median
     *  rejector compares each block's spectrum to the accepted history; the
     *  earlier time-domain Δⁿ test was removed entirely once this proved the
     *  better detector ({@code false} disables rejection altogether). */
    private static final boolean USE_SPECTRAL_DISCONTINUITY = true;
    /** Frequency-domain running-median glitch / stall rejector, run per tick
     *  on the freshly computed spectrum before it enters the cross-tick
     *  (vector) average - where one bad block injects a large complex error. */
    private final SpectralDiscontinuityDetector spectralDetector = new SpectralDiscontinuityDetector();
    /** Time-domain discontinuity gate - the scope's glitch detector run on the
     *  tick's raw window.  A splice/dropout breaks the sinusoid recurrence
     *  decades above the noise floor even when its spectral footprint slips
     *  under the frequency-domain gates (and vice versa), so the scope trigger
     *  and the FFT rejection agree on what counts as a damaged block. */
    private static final boolean USE_TIME_DISCONTINUITY = true;
    private final TimeDiscontinuityDetector timeDetector = new TimeDiscontinuityDetector();
    /** Ticks BEFORE a detected discontinuity whose contribution is REMOVED
     *  from the accumulator when a gate fires.  The preceding tick's window
     *  shares nearly all samples with the detecting one, so the event usually
     *  sits at its window-taper EDGE - attenuated below the gate thresholds
     *  but still poisoning the average.  The last this-many accepted ticks
     *  are kept subtractable in {@link #prevTickRe}; the rejected tick itself
     *  never enters the accumulator, so the total loss per event is this
     *  constant plus the detecting tick. */
    private static final int DISCONTINUITY_DROP_TICKS = 1;
    /** Ring of the last {@link #DISCONTINUITY_DROP_TICKS} accepted ticks'
     *  contributions EXACTLY as added (rotation and weight applied), so a
     *  gate firing can subtract them back out.  Coherent: re/im; incoherent:
     *  power.  Sized like the accumulator at restart. */
    private double[][] prevTickRe, prevTickIm, prevTickPow;
    private int[]      prevTickWeight;
    /** Slots of the ring currently valid (cleared by a subtraction, a restart
     *  and a reset), and the next slot to write. */
    private int prevTickCount, prevTickPos;
    /** Debug overlay: the last gate-REJECTED block's pre-average spectrum (dBFs),
     *  held so {@code FftView} can show what tripped a gate (rejected blocks never
     *  otherwise reach the display). */
    private volatile double[] lastRejectBlockDbFs;
    /** Debug overlay: the gate verdict (which gate fired + values) for that same
     *  last rejected block. */
    private volatile SpectralDiscontinuityDetector.Gates lastRejectGates;
    /** Per-tick diagnostic record of the coherent accumulator (a DISABLED
     *  no-op while {@link DebugSwitches#WRITE_FFT_TICK_STATS} is off);
     *  replaced at every accumulator restart so each run gets its own file. */
    private FftTickStatsWriter tickStats = new FftTickStatsWriter();
    /** Tracking of the counted advance.  Once the accumulator has re-pinned κ
     *  from the phase slope, the turned-back fundamental phase should be
     *  constant; a residual of the pin lets it slowly ramp, and a re-sync (the
     *  event that sets {@link #gapRecoverPending}) steps it.  Each cycle the
     *  running mis-alignment vs the deep accumulated phase is measured and a
     *  fraction ({@link #PHASE_TRACK_GAIN}) folded into
     *  {@link #accumDroppedSamples}, so the de-rotation stays in phase -
     *  killing the drift and absorbing disturbances.  On a re-sync the
     *  realign is applied: the whole residual folded once (then the tracking
     *  cleans up).  Only under the counted advance: the aligning modes
     *  measure the advance on every frame. */
    private double  accumDroppedSamples;   // cumulative de-rotation correction (sub-sample), driven by the tracking
    private boolean gapRecoverPending;     // a re-sync fired; realign the next frame
    /** Below this pinned fundamental bin there is no harmonic family to pool
     *  or to trace: the fork stands down. */
    private static final double KAPPA_FAMILY_MIN_BIN = 1.0;
    /** Tracking gain (fraction of the running fundamental mis-alignment
     *  folded into the de-rotation each cycle).  Low enough to average out
     *  per-cycle phase noise (which would random-walk if folded whole), high
     *  enough to track the κ-residual ramp.  Raise it if the fundamental still
     *  drifts; lower it if the harmonics get noisy. */
    private static final double PHASE_TRACK_GAIN = 0.10;
    /** A fork line whose accumulated-vs-fresh phase difference sits within this
     *  of the ±π wrap is dropped for the cycle - folded to the wrong side it
     *  would pull the pooled offset instead of refining it. */
    private static final double FORK_WRAP_GUARD_RAD = Math.PI / 2.0;
    /** Previous tick's averaging mode, for deciding when to reset the cross-tick
     *  accumulator: the averaging mode flip, a ring↔infinite switch, or a
     *  smaller ring (a larger ring keeps the depth). */
    private boolean lastAccumulate;
    private boolean lastForeverMode;
    private int     lastRingN = -1;
    /** Mains-suppression mode the previous ANALYSED tick ran under, for spotting
     *  a mid-run switch; {@code null} only until the first analysed tick of the
     *  process.  Nothing clears it on stop / start, so a mode changed while
     *  stopped is seen as a switch on the first tick of the next session - where
     *  it is a no-op, {@link #start} having already invalidated the window and
     *  cleared the cache.  Worker-thread only. */
    private MainsSuppression lastMainsMode;

    // ─── Fundamental phase alignment ───────────────────────────────────────
    /** The fundamental's phase excess for the NEXT accumulated tick, in
     *  radians: how far the analysed channel's fundamental phase stands from
     *  the drift-free time base the selected alignment mode supplies.  Zero in
     *  mode {@link FundamentalPhaseAlignment#NONE}.  Behind a notch filter the
     *  centre frequency drifts thermally and the phase response at the centre
     *  is steep, so the fundamental - and only it - moves; the tracking loop
     *  has one phase input and would charge that move to the time base, where
     *  it lands on harmonic h multiplied by h.  Set once per tick before
     *  {@link #accumulateIntoForeverBuffer} and consumed there at exactly two
     *  points. */
    private double pendingPhiF;
    /** True while the reference feed failed the start check below, so this
     *  collection accumulates unaligned (phiF held at zero).  Re-evaluated at
     *  every accumulator restart. */
    private boolean phaseAlignRefused;
    /** True for a tick whose alignment has no measurement of its own: the
     *  pilots have not settled a correction yet - the collection's opening
     *  frames, or the frames after lost samples until a new one is decided
     *  and confirmed.  Such a tick is NOT accumulated - its excess is the last
     *  settled one held, which stands still while the notch drifts on - and is
     *  not published either, so the view keeps the last averaged spectrum. */
    private boolean phaseAlignUnsettled;
    /** The advance the pair settled on for THIS tick, samples since the
     *  collection's first read frame, or {@link Double#NaN} where the pair
     *  has nothing settled.  Measured from the two pilot lines, so it carries
     *  neither the capture's count of the samples nor the phase of the
     *  fundamental.  The tones' excess is derived from it; the accumulator's
     *  rotation runs on {@link #referenceTone1Rad}. */
    private double pilotAdvanceSamples = Double.NaN;
    /** Each tone's reference phase on THIS tick's frame, radians at the
     *  window's start - a reading of the tone as the generator emitted it,
     *  clear of the notch - or {@link Double#NaN} where the aligning mode in
     *  force read none.  Mode {@link FundamentalPhaseAlignment#REFERENCE_CHANNEL}:
     *  the reference channel's own phase of F1 and, while a dual pair is
     *  pinned, of F2.  Mode {@link FundamentalPhaseAlignment#PILOT_TONE}: the
     *  first pilot's phase less the second's for F1 - the two lines stand one
     *  fundamental apart, so their difference turns exactly as the fundamental
     *  does - and nothing for F2.  A tone's change between two ticks IS the
     *  turn the advance between them gives that tone, and it is what the
     *  accumulator turns the frame by.  Kept as a phase and not in samples:
     *  every harmonic and every product owes a whole-number combination of
     *  these two turns, so no whole period has to be counted and none can be
     *  miscounted. */
    private double referenceTone1Rad = Double.NaN;
    private double referenceTone2Rad = Double.NaN;
    /** True while the operator has been told that the alignment is deciding.
     *  Raised on the first unsettled tick of a stretch so the notice is posted
     *  once, dropped on the first settled tick; the banner itself goes when a
     *  published frame reaches the view, or on a reset or a stop. */
    private boolean phaseAlignBannerUp;
    /** One refusal notice per collection: cleared by {@link #start()}, set by
     *  the first publish, so a run that restarts its accumulator repeatedly
     *  does not raise the operator's dialog again and again. */
    private boolean phaseAlignRefusalPublished;
    /** Accumulated ticks since {@link #start()}, counting only the ticks that
     *  actually computed a reference phase - the cadence of the live readout
     *  below. */
    private int phaseAlignTicks;
    /** Ticks between two live readouts of the analysed-to-reference ratio.
     *  Frequent enough to show a feed degrading during a long average, rare
     *  enough to cost nothing per tick. */
    private static final int PHASE_ALIGN_READOUT_TICKS = 100;
    /** Half-width, in bins, of the neighbourhood the reference bin's local
     *  floor is taken from, and the half-width of the fundamental's own lobe
     *  excluded from it - a windowed tone spreads over a few bins, which would
     *  otherwise lift the very floor it is being measured against. */
    private static final int PHASE_ALIGN_FLOOR_SPAN_BINS    = 32;
    private static final int PHASE_ALIGN_FLOOR_EXCLUDE_BINS = 2;
    /** Least margin, in dB, the reference bin's power must hold over that
     *  local floor for the feed to count as carrying the fundamental: below it
     *  the phase handed to the loop is the phase of noise. */
    private static final double PHASE_ALIGN_MIN_MARGIN_DB = 20.0;
    /** Least level, in dBFS, the reference bin must reach.  A feed this far
     *  down is at the converter's own noise, whatever its local margin. */
    private static final double PHASE_ALIGN_MIN_LEVEL_DBFS = -100.0;

    /** The notice that stands while the alignment has nothing settled: no
     *  frame is averaged then, and no other capture notice is shown over it. */
    private static final String PHASE_ALIGNING_BANNER = "fft.warning.phaseAligning";

    /** Why the reference-channel alignment refused to run, in the three
     *  numbers the operator's message states: the fundamental's frequency, the
     *  reference bin's level and its margin over the reference channel's local
     *  floor.  Payload of {@link Events#FFT_PHASE_ALIGN_REFUSED}. */
    public record PhaseAlignRefusal(double fundamentalHz, double levelDbFs, double marginDb) {}

    /** Why the pilot-tone alignment ended the recording, in the one number
     *  the operator's message states: the fundamental's frequency.  A pilot
     *  line failed its level test on three frames in a row, which leaves the
     *  coherent average with no time base at all.  Payload of
     *  {@link Events#FFT_PHASE_ALIGN_PILOT_LOST}. */
    public record PilotLost(double fundamentalHz) {}

    /** How long {@link #stop()} waits for the worker thread to leave its
     *  tick, in milliseconds; the teardown goes on once it has elapsed. */
    private static final long STOP_JOIN_MS = 3_000;

    // ─── Pilot tone ─────────────────────────────────────────────────────────
    /** The chain's usable bandwidth, Hz: the highest frequency the amplifier,
     *  the filter under test and the converters still carry.  The pilot is
     *  read for its phase only, so the level loss towards this corner does
     *  not matter, but a line beyond it arrives as noise. */
    private static final double PILOT_CEILING_HZ = 20_000.0;
    /** The highest harmonic order the pilots keep clear of: the ladder the
     *  THD readout sums, H2 to H9.  A fixed number, taken from no setting:
     *  a protected order that followed a preference would move the pilots
     *  whenever the operator changed that number, and moved pilots mean a
     *  new election, a new request to the generator and an average started
     *  again from zero.  The view's "calculate up to" count in particular
     *  only says how many harmonics are displayed. */
    private static final int PILOT_PROTECTED_ORDER = 9;
    /** The two pilot lines' readers, {@code null} while no pilot runs: each
     *  holds the whole bin its line sits on and that line's phase in the
     *  previous frame the line was read on, and gives the turn the line made
     *  between the two.  Re-created at every election - a reader's bin is the
     *  election's own - and dropped when the pilot is taken down. */
    private PilotReader pilotReader1;
    private PilotReader pilotReader2;
    /** The elected pair's turn arithmetic: how far the capture advanced
     *  between two read frames, settled from the two pilots and the
     *  fundamental.  Re-created with the readers. */
    private PairAdvance pilotPair;
    /** Each pilot line's raw phase on this frame's spectrum, and the raw phase
     *  it had on the accumulation's reference frame: their difference is the
     *  turn the line made by itself, which is all its hump is turned back by
     *  in the accumulator ({@link CoherentAccumulator.FrameTurns}).  NaN while
     *  the line is not read. */
    private double pilotRawPhase1 = Double.NaN;
    private double pilotRawPhase2 = Double.NaN;
    private double pilotRawRef1   = Double.NaN;
    private double pilotRawRef2   = Double.NaN;
    /** Set on the second frame in a row without a pilot reading, consumed by
     *  the tick loop: that frame is discarded and the capture re-anchored
     *  through the discontinuity path.  The discard is what makes the warning
     *  banner stand - a frame that still published its own result would reach
     *  the view right after the banner and clear it as stale - and the next
     *  reading starts from the reloaded window. */
    private boolean pilotResyncPending;
    /** Whether the collection's first read frame has been taken: its phases
     *  are the zero every later frame is measured against, and it yields no
     *  change of its own. */
    private boolean pilotFirstTaken;
    /** That first frame's raw phases of F1 and of F2 ({@link Double#NaN} in
     *  single tone) - the tones' half of the zero, the pilots' half living in
     *  their readers. */
    private double toneFirstPhase1;
    private double toneFirstPhase2;
    /** That first frame's position in the stream, samples: this frame's
     *  position less it is the capture's own count of what passed between
     *  them, the anchor that says which repetition of the lines this frame is
     *  on.  What the count cannot know - the samples dropped without an
     *  announcement - is what the lines themselves resolve. */
    private long pilotFirstAbsStart;
    /** Each tone's excess since the collection's first read frame, radians -
     *  F1's and, in dual tone, F2's.  Each frame READS its own value against
     *  that first frame, so an error stays on the frame that made it; a frame
     *  without a pilot line takes its value from the trend instead. */
    private double excessF1Rad;
    private double excessF2Rad;
    /** The SECOND tone's phase excess for the NEXT accumulated tick, radians
     *  - the companion of {@link #pendingPhiF}.  A notch's phase response at
     *  F2 is not the one it has at F1, so the two tones carry their own
     *  numbers: the (0,1) member of the product grid, F2 itself, turns by
     *  minus this, and the fork pool takes it out of F2's fresh phasor.
     *  Zero in single tone and in mode {@link FundamentalPhaseAlignment#NONE}. */
    private double pendingPhiF2;
    /** Frames in a row the pilots gave no measurement on: the first is
     *  extrapolated, the second re-syncs the capture, the third ends the
     *  recording.  Reset by every frame they do measure. */
    private int pilotMissingFrames;
    /** One pilot-lost notice per RUN, on the footing of the refusal's: the
     *  stop travels the auto-stop path and takes a few analysis cycles to
     *  reach the worker, so the condition is still there on the cycles in
     *  between and the operator is told once.  Cleared by {@link #start()}. */
    private boolean pilotLostPublished;
    /** The same for the fundamental against the REFERENCE channel, taken at
     *  the collection's first frame that has the reference mirror - the
     *  excess itself in mode REFERENCE_CHANNEL, the control of the pilot's
     *  reading in mode PILOT_TONE.  The second tone has its own, read at its
     *  own bin on the same mirror. */
    private boolean pilotRefFirstTaken;
    private double  pilotRefFirstRad;
    private boolean pilotRef2FirstTaken;
    private double  pilotRef2FirstRad;
    /** The changes since their first frame, radians, continued unwrapped from
     *  the previous frame: each tone's against the reference channel, which
     *  can pass half a turn through a notch.  A wrapped reading fed to an
     *  average would average a wrap. */
    private double  pilotRefChangeRad;
    private double  pilotRef2ChangeRad;
    /** Those changes averaged over the last
     *  {@link DebugSwitches#PILOT_AVERAGE_FRAMES} accepted frames, one
     *  average each, so both tones are smoothed alike - {@code null} at zero,
     *  where every value is the fresh frame's own.  Allocated here and only
     *  cleared later: a collection start runs on the caller's thread. */
    private final MovingAverage pilotRefAverage  = pilotAverage();
    private final MovingAverage pilotRef2Average = pilotAverage();
    /** Those changes as the excess and the trace take them: the averages'
     *  values where those exist, else the changes themselves. */
    private double  pilotRefValueRad;
    private double  pilotRef2ValueRad;
    /** Frames each tone's excess is fitted over, against the frames' own
     *  times, to predict it on a frame the pilots cannot measure. */
    private static final int PILOT_PREDICT_POINTS = 10;
    /** How far past its newest frame the excess trend is trusted to tell
     *  repetitions of the fundamental's period apart, in transform lengths:
     *  the repetition two periods out stands 0.6 degrees off at the bench
     *  pair's ratio, and the notch's drift moves 0.2-0.3 degrees within
     *  30-60 s on the bench, so two transforms (11 s at 2M points and
     *  384 kHz) keep the prediction well inside the half spacing; beyond
     *  them the pair decides on the pilots alone. */
    private static final double PILOT_PREDICT_HORIZON_TRANSFORMS = 2.0;
    /** Each tone's excess over the last {@link #PILOT_PREDICT_POINTS} frames
     *  the pilots measured, against those frames' times on the capture's
     *  clock.  Allocated here and only cleared later, like the averages. */
    private final PhaseTrend excessTrend1 = new PhaseTrend(PILOT_PREDICT_POINTS);
    private final PhaseTrend excessTrend2 = new PhaseTrend(PILOT_PREDICT_POINTS);
    /** Where the two readings stood against each other at the first frame
     *  both had been read, radians, and whether that frame has come: the
     *  reference's change less the pilot's reading of the drift is zeroed
     *  there, so a pilot whose line was refused on the collection's first
     *  frames compares from the frame it was first read on, without the
     *  drift before it counted in. */
    private boolean pilotRefOffsetTaken;
    private double  pilotRefOffsetRad;
    /** The analysed fundamental's level over the reference channel's at the
     *  fresh frame, dB - the feed's live readout in mode REFERENCE_CHANNEL. */
    private double  referenceFeedDb;
    /** One notice per collection for each of the two conditions that leave
     *  the mode without a measurement: a generator a server drives, and a
     *  pilot bin that carries no line. */
    private boolean pilotRemoteWarned;
    private boolean pilotNoLineWarned;
    /** What the last measured frame's pair arithmetic came to, for the frame
     *  trace alone: the whole turns each pilot has made since the collection's
     *  first frame.  Zero on a frame that measured none, the collection's own
     *  first frame among them.  Written on every measured frame, read only
     *  under {@link DebugSwitches#TRACE_HARMONIC_PHASE}. */
    private long traceTurns1;
    private long traceTurns2;
    /** The rigid residual the pair the frame settled on reached: how far
     *  apart the two pilots' fine readings stood, radians at the
     *  fundamental. */
    private double traceRigidRad;
    /** The two pilot lines' own levels on the frame their phases were read
     *  from, dBFS: what says whether a line was still there to be read. */
    private double tracePilot1DbFs = Double.NaN;
    private double tracePilot2DbFs = Double.NaN;
    /** The margin each pilot line held over the median power of its
     *  neighbourhood on that frame, dB: what the pair's admission is scaled
     *  from.  NaN before a frame was measured. */
    private double traceMargin1Db = Double.NaN;
    private double traceMargin2Db = Double.NaN;
    /** The three lines' plain phases on the analysed channel, radians: one
     *  single frame taken at the window's start - the base the pilot turns are
     *  counted from - with no de-rotation, no summing of the cycle's frames
     *  and no mains comb, so the trace states what the samples themselves hold
     *  before any step of the alignment.  NaN where the line has no bin on
     *  this frame or the window is not filled. */
    private double traceRawFundRad = Double.NaN;
    private double traceRawP1Rad   = Double.NaN;
    private double traceRawP2Rad   = Double.NaN;
    /** The same for the reference channel's fundamental, read on the mirror
     *  window at that same start.  NaN on a frame with no mirror. */
    private double traceRawRefRad = Double.NaN;
    /** The one-shot re-zero of the pilot tracking once the frequency lock is
     *  tracking: the collection's opening frames stand on the untrimmed tone
     *  (the crystals' offset, corrected by the lock's first trim), and with
     *  it the pilots' first phases, the tone's first phase and the pair's
     *  turn counting.  When the lock reports tracking, the next settled
     *  frame's totals become CARRIES and that frame becomes the new zero, so
     *  the whole tracking stands on the on-bin tone while the accumulator -
     *  which is never touched - keeps its de-rotation continuous: every
     *  advance and excess handed on is the new measurement plus its carry.
     *  Latched once per lock acquisition, so lock trouble later in the
     *  recording cannot pile up re-zeros. */
    private boolean pilotRetrackDone;
    private boolean pilotRetrackPending;
    /** What the pilot tracking had measured when it was re-zeroed: the
     *  advance (samples) and each tone's excess (radians) at the frame that
     *  became the new zero.  Added to every reading of the new epoch before
     *  the accumulator sees it; zero for a collection whose zeros needed no
     *  re-take. */
    private double pilotAdvanceCarrySamples;
    private double excessCarry1Rad;
    private double excessCarry2Rad;

    // ─── Generator frequency lock ───────────────────────────────────────────
    /** FLL measurement plausibility bound, relative part: generator-vs-ADC
     *  clock drift is ppm-scale (observed ≤ ~130 ppm), so 500 ppm is a
     *  generous ceiling for a REAL mistune; anything beyond it is a
     *  mis-measurement that must not reach the loop. */
    private static final double FLL_MAX_ERROR_PPM  = 500;
    /** FLL measurement plausibility bound, absolute floor in FFT bins -
     *  keeps the gate permissive at low target frequencies where the
     *  ppm part collapses below the spectral resolution. */
    private static final double FLL_MAX_ERROR_BINS = 5;
    /** Alignment-done threshold for the magnitude-drift watch (ppm of the
     *  tone target): once every locked tone measures within this of its
     *  target, the FLL is considered settled and the drift comparison
     *  fires.  Comfortably above the converged loop's frequency jitter
     *  (~0.005 ppm) and below the raw clock offset (~1.5 ppm). */
    private static final double ALIGN_DONE_PPM  = 0.1;
    /** Magnitude-drift threshold (dB): a fundamental whose level moved by
     *  more than this between the first fed tick after a lock reset and the
     *  aligned tick means the running average still carries pre-alignment
     *  frames at a depressed level - worth suggesting a statistics reset. */
    private static final double ALIGN_DRIFT_DB  = 0.1;
    /** Frequency-lock loops: {@link #fll} tracks the SINE / dual-tone
     *  tone-1 correction; {@link #fll2} tracks dual-tone tone-2.  The pilot
     *  tone of the pilot phase alignment has no loop of its own: the
     *  generator carries it at the exact ratio of its bin to the
     *  fundamental's, so tone 1's correction holds it on its bin - and a loop
     *  of its own would trim away the very frequency shift the alignment
     *  reads.  Created lazily when an alignment mode is active.  Worker-thread
     *  only, like every field of this section but the reset request. */
    private FrequencyAligner fll;
    private FrequencyAligner fll2;
    /** The correction each loop last published as a trim, Hz.  A trim goes
     *  out only when the loop's correction differs from it, so a holding loop
     *  moves nothing on the generator side.  NaN forces the next update to
     *  publish: set at loop creation and reset, where the generator's trim
     *  has been dropped (a statistics reset, a restarted alignment, a new
     *  window geometry - an FFT length change re-snaps the generator). */
    private double fllPublishedCorrection  = Double.NaN;
    private double fll2PublishedCorrection = Double.NaN;
    /** The untrimmed, bin-snapped generator frequency each loop steers to,
     *  Hz, as the last loop step computed it; 0.0 while no step computed one
     *  (tone 2's also in single-tone, which has no second tone).  A reset
     *  drops the generator's trims, so these are the tones it plays after
     *  one and the held pair takes them there. */
    private double fllTargetHz;
    private double fll2TargetHz;
    /** Set by {@link #resetFrequencyLock()} on the caller's thread while the
     *  worker runs, and consumed at the top of the worker's loop iteration,
     *  where the whole reset runs - the loops and the magnitude-drift watch
     *  are worker state, and the reset event is posted behind any trim
     *  already on its way. */
    private final AtomicBoolean frequencyLockResetPending = new AtomicBoolean();
    /** Set by every capture re-sync - {@link #onCaptureOverrun},
     *  {@link #onSignalDiscontinuity} (which the pilot-missing re-sync goes
     *  through as well) and the {@link #reAnchorPending} re-anchor - and
     *  tested and cleared only by {@link #applyFrequencyLock}, after every
     *  other gate of the tick has passed: the first tick that would feed a
     *  loop after a re-sync is skipped.  That is the accumulator's one-shot
     *  realign tick where a realign runs, and the first accumulated tick
     *  where none does.  The loops themselves are not reset - the elapsed
     *  capture time reaches them through the next window's absolute start. */
    private boolean fllResyncHold;
    /** One analysis cycle's reading for the frequency lock, kept for one
     *  cycle: the tones' measured frequencies (tone 2 NaN in single tone),
     *  the frame's start and geometry, the epoch it was read under, and the
     *  tones' levels for the magnitude-drift watch.  Fed on the NEXT cycle,
     *  once that cycle's gates have passed: a discontinuity sitting at a
     *  window's edge is found by the gates only on the cycle after it, and
     *  a reading fed on its own cycle would carry it into the loop. */
    private record FllReading(double measured1, double measured2, long absStart,
                              int sampleRate, int fftSize, long epoch,
                              double fundamentalDbFs, double tone2DbFs) {}
    /** The previous cycle's reading, or {@code null} after a re-sync, a lock
     *  reset or a cycle the lock's gates refused. */
    private FllReading pendingFllReading;
    /** FFT lengths of samples a tracking loop's readings are averaged over:
     *  the newest window and the windows starting up to one FFT length before
     *  it.  A re-sync rebuilds the window from fresh samples, so the average
     *  is whole again one FFT length after the first window that follows it. */
    private static final int FLL_AVERAGE_FRAMES = 2;
    /** How far before the newest window, in FFT lengths, the windows a loop
     *  still starting averages may begin.  A window starting up to half a
     *  length before a trim landed has its centre after it, so the phase
     *  change it reads is the trimmed tone's: a starting loop's large
     *  corrections are never averaged with readings of the untrimmed tone,
     *  and a weak tone is still read over several cycles before the loop
     *  acts on it. */
    private static final double FLL_START_AVERAGE_FRACTION = 0.5;
    /** One reading a loop was fed: its window's absolute start and the tone
     *  frequency it measured, Hz. */
    private record FllSample(long absStart, double hz) {}
    /** Each loop's readings - tone 1's and tone 2's - kept while their
     *  windows start inside the span the loop's state allows: up to one FFT
     *  length before the newest window while it is tracking (so the windows
     *  cover {@link #FLL_AVERAGE_FRAMES} FFT lengths of samples), up to
     *  {@link #FLL_START_AVERAGE_FRACTION} of one while it is still starting;
     *  the loop steers on their mean.  How many readings a span holds follows
     *  from the hops, which a random overlap draws per cycle.  Emptied by
     *  anything that ends the readings' stream: a re-sync, a lock reset, a new
     *  loop. */
    private final ArrayDeque<FllSample> fllAverage1 = new ArrayDeque<>();
    private final ArrayDeque<FllSample> fllAverage2 = new ArrayDeque<>();
    /** Magnitude-drift watch state - armed by a lock reset (generator change /
     *  align-on / record stop), baselined from the first tick that reaches
     *  the watch, resolved one-shot when the loop(s) report aligned.  See
     *  {@link #ALIGN_DRIFT_DB}. */
    private boolean alignWatchArmed;
    private boolean alignWatchBaselined;
    private double  alignWatchBaseF1Db;
    private double  alignWatchBaseF2Db;

    /**
     * @param display SWT display used to marshal {@link Events#FFT_RESULT_AVAILABLE}
     *                publishes back to the UI thread.  Subscribers to that
     *                event (typically the FFT view's {@code redraw()}) are
     *                where the post-analysis paint hook now lives.
     */
    public FftAnalyzerWorker(Display display) {
        this.display = display;
    }

    /** Returns the mains comb, (re)building it when the sample rate
     *  changes.  Worker-thread only. */
    private MainsCombFilter mainsComb(int sampleRate) {
        if (mainsComb == null || mainsCombSampleRate != sampleRate) {
            mainsComb = new MainsCombFilter(sampleRate, MainsCombFilter.DEFAULT_NOTCH_BANDWIDTH_HZ);
            mainsCombSampleRate = sampleRate;
        }
        return mainsComb;
    }

    /** Returns the pre-FFT time-domain mains filter for {@code mode}
     *  (SYNC_SUBTRACT / LMS), (re)building it when the mode or sample rate
     *  changes.  Worker-thread only. */
    private MainsTimeFilter mainsTimeFilter(MainsSuppression mode, int sampleRate) {
        if (mainsTimeFilter == null || mainsTimeFilterMode != mode
                || mainsTimeFilterSampleRate != sampleRate) {
            mainsTimeFilter = MainsFilters.of(mode, sampleRate, MainsCombFilter.DEFAULT_NOTCH_BANDWIDTH_HZ);
            mainsTimeFilterMode = mode;
            mainsTimeFilterSampleRate = sampleRate;
        }
        return mainsTimeFilter;
    }

    /** Drops the cross-tick accumulator.  Safe to call from any thread -
     *  arrays are reassigned, not cleared in place. */
    private void resetAccumulator() {
        accumulator.reset();
        accumIntFundBinRounded = 0;
        spectralDetector.reset();   // the median reference restarts with the average
        lastRejectBlockDbFs = null;
        lastRejectGates     = null;
        prevTickCount = 0;
        tickStats.close();          // the diagnostic record ends with the run it describes
        // The pilot's reading goes with the accumulator: each tone's excess is
        // its drift since the average's first frame, so the zeros, the
        // baselines and the averages start over with the average they serve.
        pilotFirstTaken     = false;
        pilotRefFirstTaken  = false;
        pilotRef2FirstTaken = false;
        pilotRefOffsetTaken = false;
        excessF1Rad         = 0.0;
        excessF2Rad         = 0.0;
        pendingPhiF2        = 0.0;
        pilotMissingFrames  = 0;
        pilotResyncPending  = false;
        resetPilotHistory();
    }

    /** Drops the analysis window AND whatever had been gathered towards the
     *  next one, so the next tick starts collecting from the current cursor
     *  position: a partially filled span kept across a re-anchor would splice
     *  samples from before and after the break into one frame. */
    private void invalidateWindow() {
        winValid = false;
        winFill  = 0;
        hopFill  = 0;
        forgetToneReadings();
    }

    /** Handles a ring overrun: the worker fell a full ring behind, so the
     *  contiguous span it was about to read had already been overwritten.  This
     *  is a COVERAGE gap, not corrupted data - the absolute sample positions are
     *  still correct ({@code writePos} counts every delivered sample), so the
     *  cross-tick de-rotation absorbs the gap (it de-rotates by the true sample
     *  delta) and the running average stays valid.  So just start the next FFT
     *  window fresh from "now"; do NOT restart averaging.  Worker-thread only
     *  (it advances the cursor). */
    private void onCaptureOverrun(SignalBufferReader rdr) {
        invalidateWindow();
        rdr.seekToLatest();
        accumulator.reanchorKappa();   // κ measurement: re-anchor on the jumped frame, don't fold its step
        gapRecoverPending  = true;     // re-sync: one-shot full realign on the next clean frame, then track
        dropFllFeed();
        if (log.isWarnEnabled()) {
            log.warn("FFT ring overrun - analyser fell a full buffer behind; window re-anchored, "
                    + "averaging continues (lower the overlap or FFT length if this repeats)");
        }
        publishCaptureBanner("fft.warning.overrun");
    }

    /** Posts a capture re-sync warning to the view on the UI thread, carrying the
     *  i18n message-key as the payload - so ONE event presents the distinct
     *  "overrun" vs "discontinuity" messages depending on the caller. */
    private void publishCaptureBanner(String messageKey) {
        if (display == null || display.isDisposed()) return;
        // While the alignment has nothing settled, nothing is being averaged,
        // and that is what the operator needs to read.  A re-sync notice put
        // up in that state replaces it with a message about an event that is
        // already over, so it is not shown at all until averaging resumes.
        if (phaseAlignUnsettled && !PHASE_ALIGNING_BANNER.equals(messageKey)) return;
        display.asyncExec(() -> {
            try {
                MessageBus.instance().publish(Events.FFT_CAPTURE_RESYNC, messageKey);
            } catch (Exception e) {
                if (log.isErrorEnabled()) log.error("Can't dispatch {}", Events.FFT_CAPTURE_RESYNC, e);
            }
        });
        display.wake();
    }

    /** Guard skipped after a discontinuity re-sync, ahead of the first fresh
     *  window: detection fires on the glitch's START, and the glitch (the
     *  observed USB gaps run 120-160 µs) plus any settling can still be in
     *  flight at "latest" - 5 ms of discarded samples puts the rebuild safely
     *  past its end. */
    private static final double POST_GLITCH_SKIP_SEC = 0.005;

    /** Recovery for a detected in-window signal discontinuity - the same re-sync
     *  as a ring overrun: discard the glitched window, re-anchor to "now" and
     *  rebuild, KEEP the running average (the de-rotation absorbs the coverage
     *  gap and the cross-tick gap recovery corrects any post-glitch phase step).
     *  Worker-thread only. */
    private void onSignalDiscontinuity(SignalBufferReader reader) {
        invalidateWindow();
        reader.seekToLatest();
        // Reuses the drain-skip: the next tick consumes + discards this span
        // before rebuilding the window (max keeps a larger pending drain).
        drainSkipRemaining = Math.max(drainSkipRemaining,
                (long) Math.ceil(POST_GLITCH_SKIP_SEC * reader.getSampleRate()));
        accumulator.reanchorKappa();   // κ measurement: re-anchor on the jumped frame, don't fold its step
        gapRecoverPending  = true;     // re-sync: one-shot full realign on the next clean frame, then track
        dropFllFeed();
        if (log.isWarnEnabled()) {
            log.warn("Found signal discontinuity - re-synced (glitched window discarded, "
                    + "averaging continues)");
        }
        publishCaptureBanner("fft.warning.discontinuity");
    }

    /** Fundamental bin(s) the spectral detector measures the near-carrier
     *  pedestal around (it then gates that pedestal against the noise floor):
     *  the single-tone fundamental, plus the dual-tone second tone when
     *  present.  Null when no usable fundamental was measured. */
    private int[] fundamentalBins(FftResult r) {
        if (r.freqResolution <= 0) return null;
        int f1 = (Double.isFinite(r.fundamentalHzRefined)  && r.fundamentalHzRefined  > 0)
                ? (int) Math.round(r.fundamentalHzRefined  / r.freqResolution) : -1;
        int f2 = (Double.isFinite(r.fundamental2HzRefined) && r.fundamental2HzRefined > 0)
                ? (int) Math.round(r.fundamental2HzRefined / r.freqResolution) : -1;
        if (f1 > 0 && f2 > 0) return new int[]{ f1, f2 };
        if (f1 > 0)           return new int[]{ f1 };
        if (f2 > 0)           return new int[]{ f2 };
        return null;
    }

    /** Adds the result's one frame to the cross-cycle average: coherent, the
     *  frame turned back to the collection's first frame by the accumulator
     *  (every bin in proportion to its frequency, the humps of the coherent
     *  family by their lines' angles); incoherent, its power.  Restarts the
     *  accumulator transparently when fftSize or the averaging mode changes
     *  between cycles.
     *
     *  @param targetN bound on the effective accumulated frame depth.  Use
     *         {@link Integer#MAX_VALUE} for an unbounded cumulative mean
     *         (forever mode); a finite N turns the running sum into an
     *         exponential window of N frames (α = weight/N).
     *  @return {@code true} if this tick was accumulated; {@code false} while
     *         the phase lock is still converging (the caller should then show
     *         the live single-tick spectrum, not the accumulator). */
    private boolean accumulateIntoForeverBuffer(FftResult r,
                                                long samplesAbsStart,
                                                boolean coherent,
                                                int targetN) {
        int fftSize  = r.fftSize;
        int halfSize = fftSize / 2;
        int N        = halfSize + 1;
        double kFrac = r.freqResolution > 0
                ? r.fundamentalHzRefined / r.freqResolution
                : 0.0;

        boolean restart = !accumulator.isStarted()
                || accumulator.getFftSize() != fftSize
                || accumulator.isCoherent() != coherent;
        if (restart) {
            // The dual pair's second tone, pinned with the fundamental for the
            // whole collection; NaN when the run is single-tone.
            double kappa2 = (coherent && r.fundamental2HzRefined > 0.0 && r.freqResolution > 0.0)
                    ? r.fundamental2HzRefined / r.freqResolution
                    : Double.NaN;
            accumulator.restart(fftSize, coherent, kFrac, kappa2, r.imdProductA, r.imdProductB,
                    analyzer.mainLobeBins(r.windowType), r.pilot1Bin, r.pilot2Bin);
            // The band tops of the collection's statistics, on the bin width
            // and the noise bandwidth the collection is pinned to.
            accumulator.bandGeometry(r.freqResolution, r.windowNenbwBins);
            accumIntFundBinRounded = Math.max(1, (int) Math.round(kFrac));
            prevTickRe     = coherent ? new double[DISCONTINUITY_DROP_TICKS][N] : null;
            prevTickIm     = coherent ? new double[DISCONTINUITY_DROP_TICKS][N] : null;
            prevTickPow    = coherent ? null : new double[DISCONTINUITY_DROP_TICKS][N];
            prevTickWeight = new int[DISCONTINUITY_DROP_TICKS];
            prevTickCount  = 0;
            prevTickPos    = 0;
            accumRefSampleStart    = samplesAbsStart;
            // The aligning mode's reading on the tick that becomes the
            // reference - the tones' reference phases: every later tick's
            // rotation is its own reading less this one, so the accumulator
            // turns by a turn that was MEASURED rather than by the count the
            // capture kept.
            accumRefTone1Rad       = referenceTone1Rad;
            accumRefTone2Rad       = referenceTone2Rad;
            // The pilot lines' raw phases on this frame: the reference each
            // line's own turn is read against.
            pilotRawRef1           = pilotRawPhase1;
            pilotRawRef2           = pilotRawPhase2;
            accumDroppedSamples    = 0.0;
            gapRecoverPending      = false;
            tickStats.close();
            tickStats = (coherent && DebugSwitches.WRITE_FFT_TICK_STATS)
                    ? new FftTickStatsWriter(AppPaths.instance().getLogsDir(),
                            fftSize, r.sampleRate, r.freqResolution)
                    : new FftTickStatsWriter();
            if (log.isInfoEnabled()) {
                log.info("FFT cross-tick de-rotation: every bin in proportion to its frequency, "
                        + "the humps of the coherent family (harmonic ladder{}) by their lines' "
                        + "angles - κ re-pinned from the phase slope + phase-lock loop",
                        kappa2 > 0.0 ? " + dual pair/product grid" : "");
            }
            // The reference feed is re-qualified whenever the average starts
            // anew - at the first tick of a collection and after any change
            // that drops the accumulator - so a feed that is missing now can
            // still carry the next run.
            checkPhaseAlignStart(r, halfSize);
        }
        // The tones' phase excesses for THIS tick.  Zero in mode NONE and for
        // as long as the reference feed stands refused; F1's enters at exactly
        // two points - the tracking loop's fundamental phase below, and the
        // fundamental's hump in the accumulator - and F2's at the two that
        // answer them for the second tone: its own hump, and the fork pool's
        // reading of its bin.  Nowhere else.
        final double phiF  = phaseAlignRefused ? 0.0 : pendingPhiF;
        final double phiF2 = phaseAlignRefused ? 0.0 : pendingPhiF2;

        // Time-shift offset from the pinned reference frame-0.  An overrun /
        // discontinuity re-anchor jumps samplesAbsStart, but the COVERAGE gap is
        // correctly counted in writePos, so the absolute delta bridges it.  What
        // it does NOT count is a dropped-sample xrun (the ADC silently drops N) -
        // that's recovered into accumDroppedSamples by the gap-correction below
        // and added here so the pinned-κ rotation stays exact across the drop.
        double delta = (samplesAbsStart - accumRefSampleStart) + accumDroppedSamples;
        // With an aligning mode in force the advance is MEASURED, not counted,
        // and on lines the notch does not touch: the reading carries neither
        // the fundamental's own drift nor anything the capture's counter got
        // wrong across a re-anchor.  The notched fundamental's phase then
        // steers nothing - it is a line to be corrected, not a clock - which
        // is what keeps a re-sync from leaving a rotation behind on every
        // harmonic.  Either mode states the fundamental's TURN over the
        // advance - the reference channel's own tone, or the pilot pair's
        // phase difference - which places the advance within one period of
        // the fundamental, all that a ladder of its whole multiples can tell
        // apart, so the count is moved to the nearest advance that turn fits.
        final boolean measuredGeometry = !phaseAlignRefused && accumulator.getKappa() > 0.0
                && !Double.isNaN(referenceTone1Rad) && !Double.isNaN(accumRefTone1Rad);
        if (measuredGeometry) {
            delta = advanceForTurn(delta, referenceTone1Rad - accumRefTone1Rad, fftSize);
        }

        int weight = Math.max(1, r.frameCount);
        // A coherent "dropout hold" used to sit here: when the pinned fundamental's
        // amplitude collapsed it returned WITHOUT accumulating, to avoid diluting
        // the coherent average with silence.  But that froze the averages count and
        // the live view whenever the signal no longer had a fundamental (coherent
        // only - incoherent has no such guard).  A missing fundamental must NEVER
        // stall averaging or the view (it only hides the THD/IMD table), so every
        // tick is now accumulated and published; the phase-track loop below
        // re-aligns when a tone returns.
        // Bounded (ring) window: before adding this tick, scale the running sum
        // down so the effective depth holds at targetN frames.  This makes the
        // running sum an exponential window (α = weight/targetN); forever mode
        // passes targetN = MAX_VALUE and never scales (true cumulative mean).
        double held = accumulator.getFrames();
        if (targetN < Integer.MAX_VALUE && held + weight > targetN) {
            int    keep  = Math.max(0, targetN - weight);
            double scale = held > 0.0 ? keep / held : 0.0;
            accumulator.scale(scale);
            // The subtractable ring holds contributions AS ADDED - once the
            // sum is scaled, they must scale with it or a later subtraction
            // over-removes.
            for (int s = 0; s < prevTickCount; s++) {
                int slot = Math.floorMod(prevTickPos - 1 - s, DISCONTINUITY_DROP_TICKS);
                if (coherent && prevTickRe != null) {
                    double[] pr = prevTickRe[slot], pi = prevTickIm[slot];
                    for (int k = 0; k < N; k++) { pr[k] *= scale; pi[k] *= scale; }
                } else if (prevTickPow != null) {
                    double[] pp = prevTickPow[slot];
                    for (int k = 0; k < N; k++) pp[k] *= scale;
                }
            }
        }
        // Claim this tick's slot in the subtractable ring; the accumulator
        // fills it with the contribution exactly as accumulated.
        final int prevSlot = prevTickPos;
        prevTickPos = (prevTickPos + 1) % DISCONTINUITY_DROP_TICKS;
        if (prevTickCount < DISCONTINUITY_DROP_TICKS) prevTickCount++;
        prevTickWeight[prevSlot] = weight;
        if (coherent) {
            int k0 = pinnedFundBin(halfSize);
            if (k0 > 0 && accumulator.isKappaRefined() && !measuredGeometry) {
                // Skipped entirely while the advance is measured - by the
                // pair or by the reference channel: this loop derives the
                // geometry from the FUNDAMENTAL's phase against the
                // accumulated reference, which forces f0 into agreement by
                // construction and therefore cannot see its own error - the
                // error lands on harmonic h multiplied by h instead.  And
                // not before the accumulator has closed its κ measurement:
                // until then the slope of this very phase is re-pinning κ.
                // Continuous phase-tracking loop on the cross-tick spectrum.
                // Instead of trusting a frozen pinned κ - whose residual
                // error ramps the de-rotated phase and slowly
                // vector-cancels the fundamental - measure the running
                // mis-alignment against the DEEP ACCUMULATED phase (the
                // √N-averaged true reference; the accumulator turns bin k0
                // by the same angle as obsPhase, so the two compare directly)
                // and fold a FRACTION of it back into the de-rotation every
                // tick.  PHASE_TRACK_GAIN < 1 is the loop filter: it
                // averages out the per-tick phase noise that would otherwise
                // random-walk if folded whole.  The steady estimate is the
                // FORK - pooled from every strong family line, not the
                // fundamental alone (see forkPooledOffset) - so behind a
                // notch the reference noise falls with the combined power of
                // the harmonics instead of being set by the weakest line.
                // On a re-sync the delta jumped, so do a one-shot FULL
                // realign of THIS frame from the fundamental alone (a big
                // offset wraps the higher lines' phases, so pooling is not
                // valid there) and let the loop clean up afterwards.
                // Minus the fundamental's phase excess: what the loop sees is
                // the fundamental with the notch's own drift taken out.
                double krot = accumulator.fundamentalAngle(delta);
                double cr   = Math.cos(krot), sr = Math.sin(krot);
                double obsPhase = Math.atan2(r.re[k0] * sr + r.im[k0] * cr,
                                             r.re[k0] * cr - r.im[k0] * sr) - phiF;
                double accumPhase = Math.atan2(accumulator.getSumIm()[k0],
                                               accumulator.getSumRe()[k0]);
                double residual   = Math.IEEEremainder(obsPhase - accumPhase, 2.0 * Math.PI);
                double fundOffset = residual * fftSize
                                  / (2.0 * Math.PI * accumulator.getKappa());
                if (gapRecoverPending) {
                    accumDroppedSamples += fundOffset;
                    delta += fundOffset;           // realign THIS frame after the jump
                    gapRecoverPending = false;
                    if (log.isInfoEnabled()) {
                        log.info("FFT re-sync realigned: {} sample one-shot (Δφ={} rad vs accumulated ref); phase tracking continues",
                                Math.round(fundOffset), String.format("%.4f", residual));
                    }
                } else {
                    accumDroppedSamples += PHASE_TRACK_GAIN
                            * forkPooledOffset(r, halfSize, delta, k0, fundOffset, phiF2);
                }
            }
            // In the reference mode the second tone's turn is read on the
            // reference channel as the fundamental's is, and taken as read:
            // the advance above is placed by the fundamental alone, within
            // one of ITS periods, and a whole period of the fundamental is
            // no whole turn of the second tone.  Otherwise the accumulator
            // counts it from the advance.
            final boolean referenceTone2 = measuredGeometry
                    && !Double.isNaN(referenceTone2Rad) && !Double.isNaN(accumRefTone2Rad);
            final double phi2 = referenceTone2
                    ? -Math.IEEEremainder(referenceTone2Rad - accumRefTone2Rad, 2.0 * Math.PI)
                    : Double.NaN;
            final boolean refinedBefore = accumulator.isKappaRefined();
            accumulator.add(r.re, r.im, weight,
                    new FrameTurns(delta, phi2, phiF, phiF2,
                            pilotTurn(pilotRawPhase1, pilotRawRef1),
                            pilotTurn(pilotRawPhase2, pilotRawRef2)),
                    prevTickRe[prevSlot], prevTickIm[prevSlot]);
            // A re-sync during the κ measurement is absorbed by the
            // measurement's own re-anchor; the one-shot realign the loop would
            // otherwise open with is dropped when the measurement closes, so
            // the loop starts tracking instead of folding a whole residual
            // read against an average the re-sync already crossed.
            if (!refinedBefore && accumulator.isKappaRefined()) gapRecoverPending = false;
            tickStats.tick(r, samplesAbsStart, delta, accumDroppedSamples,
                    accumulator.getKappa(), k0, accumulator.fundamentalAngle(delta),
                    accumulator.getSumRe(), accumulator.getSumIm(),
                    (int) Math.round(accumulator.getFrames()));
            if (DebugSwitches.TRACE_HARMONIC_PHASE && log.isInfoEnabled()) {
                // The first frame of a collection is the reference the rest is
                // read against, so it is traced as the spectrum stands; every
                // later frame is traced with the phase excess taken out, which
                // is what keeps the relations still if the alignment works.
                traceHarmonicPhases(r.re, r.im, halfSize, restart ? 0.0 : phiF, Double.NaN,
                        "frame " + completedAnalyses + (restart
                                ? " (first accumulated frame, spectrum as it stands)"
                                : " (phase excess taken out)"));
                // The accumulator with this frame already in it, read the same
                // way but with NO excess taken out: the accumulated fundamental
                // carries -phiF from its hump's rotation, so taking it out again
                // here would count it twice.  This is the line that says whether
                // the harmonics of a LONG average hold together, which no
                // single-tick reading can show.  It also carries the chain's
                // phase at the fundamental, which turns the harmonics' relations
                // into the generator's correction phases: the emitted fundamental
                // the accumulator holds is the pilot pair's difference on the
                // reference frame - the phases every added frame lands the pilot
                // humps back on - less the quarter turn a sine's bin reads
                // behind its accumulator phase.
                double emittedFundRad = Double.isNaN(pilotRawRef1) || Double.isNaN(pilotRawRef2)
                        ? Double.NaN : wrapTurn(pilotRawRef1 - pilotRawRef2 - Math.PI / 2.0);
                traceHarmonicPhases(accumulator.getSumRe(), accumulator.getSumIm(), halfSize, 0.0,
                        emittedFundRad, "accumulator after frame " + completedAnalyses);
                // The levels beside the phases: the same bins, the fresh
                // frame as one frame and the accumulator over the frames it
                // holds with this one in, so a residual that grows or shrinks
                // across a long collection shows in the log as well.
                double conv = r.amplitudePerMagnitude();
                traceHarmonicLevels(r.re, r.im, halfSize, conv, 1.0,
                        r.freqResolution, r.windowNenbwBins, "frame " + completedAnalyses);
                traceHarmonicLevels(accumulator.getSumRe(), accumulator.getSumIm(), halfSize, conv,
                        accumulator.getFrames(), r.freqResolution, r.windowNenbwBins,
                        "accumulator after frame " + completedAnalyses);
            }
        } else {
            // The incoherent result holds the frame's magnitude in re and 0
            // in im; the accumulator sums its power either way.
            accumulator.addPower(r.re, r.im, weight, prevTickPow[prevSlot]);
        }
        return true;
    }

    /** The fundamental's bin pinned for this collection, clamped to the
     *  spectrum; 0 while no collection runs. */
    private int pinnedFundBin(int halfSize) {
        return accumulator.isStarted() && accumIntFundBinRounded > 0
                ? Math.min(halfSize, accumIntFundBinRounded) : 0;
    }

    /** A pilot line's turn since the collection's reference frame, radians,
     *  as the accumulator turns its hump back by: the line's raw phase on
     *  this frame less the raw phase it had on the reference frame.
     *  {@link Double#NaN} where either reading is missing, which leaves the
     *  hump to its bin's counted turn. */
    private double pilotTurn(double rawPhase, double rawRef) {
        if (Double.isNaN(rawPhase) || Double.isNaN(rawRef)) return Double.NaN;
        return -Math.IEEEremainder(rawPhase - rawRef, 2.0 * Math.PI);
    }

    /** The counted advance moved to the nearest one over which the pinned
     *  fundamental makes the turn {@code turnRad}, samples.  The reference
     *  channel's reading fixes the advance within one period of the
     *  fundamental, and the count says which period.  A count that is off by
     *  whole periods leaves the result off by the same periods, which no
     *  whole multiple of the fundamental can see. */
    private double advanceForTurn(double counted, double turnRad, int fftSize) {
        double kappa = accumulator.getKappa();
        double countedTurn = 2.0 * Math.PI
                * Math.IEEEremainder(counted * kappa, fftSize) / fftSize;
        double missingTurn = Math.IEEEremainder(turnRad - countedTurn, 2.0 * Math.PI);
        return counted + missingTurn * fftSize / (2.0 * Math.PI * kappa);
    }

    /** Removes the subtractable ring's held contributions - the last accepted
     *  tick(s) - from the accumulator.  The retroactive half of discontinuity
     *  rejection: the window(s) BEFORE the detecting one shared nearly all of
     *  its samples, with the event attenuated at their taper edge below the
     *  gate thresholds, so their contribution is poisoned although no gate
     *  fired on them. */
    private void subtractPreviousTicks() {
        if (prevTickCount <= 0 || !accumulator.isStarted()) return;
        int removedFrames = 0;
        for (int s = 0; s < prevTickCount; s++) {
            int slot = Math.floorMod(prevTickPos - 1 - s, DISCONTINUITY_DROP_TICKS);
            if (accumulator.isCoherent() && prevTickRe != null) {
                accumulator.remove(prevTickRe[slot], prevTickIm[slot], prevTickWeight[slot]);
            } else if (!accumulator.isCoherent() && prevTickPow != null) {
                accumulator.removePower(prevTickPow[slot], prevTickWeight[slot]);
            }
            removedFrames += prevTickWeight[slot];
        }
        if (log.isInfoEnabled()) {
            log.info("Discontinuity: the previous {} tick(s) ({} frames) removed from the "
                    + "accumulator - the event sat at their window edge, under the gates",
                    prevTickCount, removedFrames);
        }
        prevTickCount = 0;
    }

    /** Highest harmonic the phase trace reports.  The ladder H2 to H9 is the
     *  one the distortion readouts are built on, so the trace speaks the same
     *  set. */
    private static final int TRACE_MAX_HARMONIC = 9;
    /** Reads each harmonic's local noise floor for the floor trace - the
     *  upper border of the noise band beside the line, the same floor the
     *  displayed harmonics are held at from below. */
    private final ToneLobeLift lobe = new ToneLobeLift();

    /**
     * One line of the harmonic phase trace (see
     * {@link DebugSwitches#TRACE_HARMONIC_PHASE}): for h = 2 to
     * {@link #TRACE_MAX_HARMONIC}, the phase of harmonic h measured against h
     * times the fundamental's own phase, in degrees, read from THIS tick's
     * spectrum at the harmonics' bins.
     *
     * <p>Why that relation.  A pure time shift of the whole capture turns
     * harmonic h by h times what it turns the fundamental by, so the relation
     * below is blind to it - it is blind to everything the accumulator's time
     * base does.  What it is NOT blind to is the fundamental moving ALONE,
     * which is what a notch's thermal drift does to it.  Traced against the
     * first frame of a collection the numbers therefore walk away by h times
     * the drift; traced with the measured excess taken out of the
     * fundamental's phase they stand still, and that difference is the whole
     * claim of the phase alignment, readable in a log.
     *
     * <p>The same reading serves two spectra, which is why the arrays arrive as
     * parameters: this tick's own, where the excess is taken out to show the
     * relations standing still, and the ACCUMULATOR as it stands after this
     * tick was added, where no excess is taken out because the accumulated
     * fundamental already carries it through the family rotation.  The
     * accumulator's line is the one that says whether a long average keeps its
     * harmonics, which a per-tick reading cannot tell.
     *
     * @param re       real parts to read, indexed by bin
     * @param im       imaginary parts to read, indexed by bin
     * @param halfSize highest bin index either array is read at
     * @param phiF           the phase excess to take out of the fundamental's
     *                       phase; zero reads the spectrum as it stands
     * @param emittedFundRad the fundamental's phase as the DAC emitted it, in
     *                       this spectrum's frame; the fundamental's phase
     *                       less it leads the line as {@code H1} in degrees -
     *                       the chain's phase at the fundamental, the notch's
     *                       as it stands, which harmonic h's relation takes
     *                       h times to become the harmonic's phase against
     *                       the emitted fundamental; NaN writes no {@code H1}
     * @param label          what this line reads - the frame and how, or the
     *                       accumulator after that frame
     */
    private void traceHarmonicPhases(double[] re, double[] im, int halfSize, double phiF,
            double emittedFundRad, String label) {
        double kappa = accumulator.getKappa();
        int    k0    = pinnedFundBin(halfSize);
        if (kappa < KAPPA_FAMILY_MIN_BIN || k0 <= 0) return;
        double fundPhase = Math.atan2(im[k0], re[k0]) - phiF;
        StringBuilder rel = new StringBuilder();
        if (!Double.isNaN(emittedFundRad)) {
            rel.append(String.format(" H1=%.2f", Math.toDegrees(wrapTurn(fundPhase - emittedFundRad))));
        }
        for (int h = 2; h <= TRACE_MAX_HARMONIC; h++) {
            int kh = (int) Math.round(h * kappa);
            if (kh > halfSize) break;
            double d = Math.IEEEremainder(Math.atan2(im[kh], re[kh]) - h * fundPhase,
                    2.0 * Math.PI);
            rel.append(String.format(" H%d=%.2f", h, Math.toDegrees(d)));
        }
        log.info("FFT harmonic phase trace, {}:{}", label, rel);
    }

    /**
     * One line of the harmonic level trace, the level half of the
     * {@link DebugSwitches#TRACE_HARMONIC_PHASE} record: the fundamental and
     * every harmonic h up to {@link #TRACE_MAX_HARMONIC} in dBFS, read from
     * the bin nearest h times the pinned fractional fundamental bin.  One bin,
     * no lobe integration: a tone off the grid reads low by its scalloping,
     * so the numbers are a trend over a collection, not the THD table's
     * values.  What the phase trace cannot show is whether a residual
     * harmonic grows or shrinks across a long average - a DAC correction
     * ageing, or a floor averaging down; this line is that.
     *
     * <p>A second line, the floor trace, states each harmonic's local noise
     * floor from H2 on, read from the same arrays scaled the same way by
     * {@link ToneLobeLift#lineFloor} - the upper border of the noise band
     * beside the line, the floor the displayed harmonics are held at from
     * below.  On the accumulator it is the coherent average's own floor,
     * which falls with the frame count, so the level line beside it says how
     * far a harmonic stands clear of what the average can resolve.
     *
     * @param re        real parts to read, indexed by bin
     * @param im        imaginary parts to read, indexed by bin
     * @param halfSize  highest bin index either array is read at
     * @param conv      the magnitude-to-amplitude factor of this spectrum's
     *                  window and length, {@link FftResult#amplitudePerMagnitude}
     * @param norm      what the arrays are a sum of: one for a single frame,
     *                  the accumulated frame count for the accumulator
     * @param binHz     the spectrum's bin width in Hz
     * @param nenbwBins the window's equivalent noise bandwidth in bins
     * @param label     what this line reads - the frame, or the accumulator
     *                  after that frame
     */
    private void traceHarmonicLevels(double[] re, double[] im, int halfSize, double conv,
            double norm, double binHz, double nenbwBins, String label) {
        double kappa = accumulator.getKappa();
        if (kappa < KAPPA_FAMILY_MIN_BIN || pinnedFundBin(halfSize) <= 0
                || conv <= 0.0 || norm <= 0.0) {
            return;
        }
        StringBuilder levels = new StringBuilder();
        StringBuilder floors = new StringBuilder();
        for (int h = 1; h <= TRACE_MAX_HARMONIC; h++) {
            int kh = (int) Math.round(h * kappa);
            if (kh > halfSize) break;
            double amp = Math.hypot(re[kh], im[kh]) * conv / norm;
            levels.append(String.format(" H%d=%.2f", h, amp > 1e-15 ? 20.0 * Math.log10(amp) : -300.0));
            if (h >= 2) {
                double floorLin = lobe.lineFloor(
                        k -> Math.hypot(re[k], im[k]) * conv / norm, kh, halfSize, binHz, nenbwBins);
                floors.append(String.format(" H%d=%.2f", h,
                        floorLin > 0.0 ? 20.0 * Math.log10(floorLin) : Double.NaN));
            }
        }
        if (log.isInfoEnabled()) {
            log.info("FFT harmonic level trace, {} (dBFS):{}", label, levels);
            log.info("FFT harmonic floor trace, {} (dBFS):{}", label, floors);
        }
    }

    /** This tick's FUNDAMENTAL phase excess for the selected alignment mode,
     *  in radians - the notch's phase against the collection's own start in
     *  both aligning modes: measured against the reference channel per tick
     *  and taken against the first tick's value, or measured from the tone's
     *  own turn less the turn the advance the pilot pair resolved explains;
     *  zero when no alignment runs or the mode's own reference is not
     *  available this tick.  The second tone's excess is written to
     *  {@link #pendingPhiF2} on the same pass, there being one reading of the
     *  frame for both. */
    private double tickPhiF(FundamentalPhaseAlignment mode, FftResult r, boolean coherent) {
        switch (mode) {
            case REFERENCE_CHANNEL:
                return referencePhiF(r, coherent);
            case PILOT_TONE:
                return pilotPhiF(r, coherent);
            default:
                pendingPhiF2 = 0.0;
                return 0.0;
        }
    }

    /**
     * Mode PILOT_TONE's excess for this frame: what each tone's phase has
     * gained beyond the turn the capture's own advance explains, counted from
     * the collection's first read frame.
     *
     * <p>Two pilot lines are generated on whole bins of the transform, clear
     * of the tones and of everything they produce, and read for their phases
     * alone.  A line at bin p turns by {@code 2 pi p D / N} over an advance of
     * D samples, so its own reading fixes D only modulo its period {@code N/p}
     * - a score of samples near the top of the band - while the hop counter's
     * figure misses whatever the converter dropped unannounced.  The two lines
     * together fix it: over the range the pair was elected for, only one rung
     * of the first line's ladder of admissible advances stands close to a rung
     * of the second's.  That search is {@link PairAdvance}.
     *
     * <p>With the advance known, a tone at bin k owes {@code 2 pi k D / N} of
     * turn to it; whatever it gained beyond that is the notch's own drift at
     * that tone, and it is summed frame by frame into the tone's excess.  Each
     * tone keeps its own sum: a notch's phase response at F2 is not the one it
     * has at F1, so one number cannot serve both.
     *
     * <p>The frame is read by {@link #readAlignmentFrame}: every phase from
     * ONE raw frame - the cycle's fresh frame, the one its spectrum will share
     * with the next cycle's - by the analyser's single-frame bin read at the
     * frame's own start, no de-rotation and no mean of frames.  The cycle's
     * spectrum, the coherent mean of its frame pair, serves the line checks
     * alone.
     *
     * <p>A frame whose pilot bin holds no line is not read - its phase is the
     * phase of noise - and neither is one whose two ladders fail to agree.
     * Such a frame takes each tone's excess from that tone's own trend over
     * the frames before it; the frames after it are counted in
     * {@link #pilotsMissing}.
     */
    private double pilotPhiF(FftResult r, boolean coherent) {
        readAlignmentFrame(FundamentalPhaseAlignment.PILOT_TONE, r, coherent);
        // A second tone the pair cannot carry - its bin is no whole multiple of
        // the spacing, which is the ordinary dual pair - has no excess of its
        // own.  It is then left ALONE: zero turns its lobe by nothing, where
        // the unknown would turn it by NaN and erase it from the average.
        pendingPhiF2 = Double.isNaN(excessF2Rad) ? 0.0 : wrapTurn(excessF2Rad);
        double excess = pilotDriftRad();
        if (pilotFirstTaken) {
            traceExcess(excess);
            traceExcessF2();
        }
        return excess;
    }

    /**
     * Mode REFERENCE_CHANNEL's excess for this frame: the fundamental's
     * change against the reference channel since the collection's first
     * frame, read by {@link #readAlignmentFrame} on the footing of the pilot
     * mode's readings - one raw frame, continued unwrapped, smoothed by the
     * same setting - and traced on the same line.  The reference channel
     * carries the fundamental clear of the notch, so its change is the
     * notch's drift; a pilot elected in this mode is read on the same frame
     * as the control, the roles of the pilot mode reversed.
     *
     * <p>In dual tone the second tone is read the same way at its own bin on
     * the same mirror, and its change becomes {@link #pendingPhiF2}: the
     * mirror carries both tones un-notched, so no advance has to be resolved
     * for either of them - the difference of two phases read in ONE frame
     * holds nothing of the frame's position.
     *
     * <p>What the accumulator is fed is the CHANGE since the first frame,
     * not the phase difference itself: the accumulated fundamental then
     * keeps the phase the notch had at the collection's start - the phase
     * the loaded correction de-embeds and the predistortion frame reference
     * subtracts - instead of losing the notch's phase altogether, which
     * that reference would then take out a second time, turning harmonic h
     * by h times the notch's phase.
     *
     * <p>The same read hands the accumulator the frame's advance, as the
     * pilot pair does in its mode: the reference channel's own tone phases
     * ({@link #referenceTone1Rad}), whose change is the turn that advance
     * gives each tone.  The two modes differ in how they find the advance
     * and the excess, and in nothing the accumulator does with them.
     *
     * <p>Also carries the live readout of the feed: the analysed-to-reference
     * ratio and the raw phase difference, every
     * {@link #PHASE_ALIGN_READOUT_TICKS} accumulated ticks.  Zero while the
     * reference window is not available.
     */
    private double referencePhiF(FftResult r, boolean coherent) {
        int k0 = readAlignmentFrame(FundamentalPhaseAlignment.REFERENCE_CHANNEL, r, coherent);
        if (k0 <= 0 || !pilotRefFirstTaken) return 0.0;
        pendingPhiF2 = pilotRef2FirstTaken ? wrapTurn(pilotRef2ValueRad) : 0.0;
        double excess = wrapTurn(pilotRefValueRad);
        traceExcess(excess);
        phaseAlignTicks++;
        if (phaseAlignTicks % PHASE_ALIGN_READOUT_TICKS == 0 && log.isInfoEnabled()) {
            log.info("FFT phase alignment, tick {}: analysed/reference at bin {} = {} dB, {} deg",
                    phaseAlignTicks, k0,
                    String.format("%.2f", referenceFeedDb),
                    String.format("%.2f", Math.toDegrees(Math.IEEEremainder(
                            pilotRefFirstRad + pilotRefChangeRad, 2.0 * Math.PI))));
        }
        return excess;
    }

    /**
     * Reads the cycle's fresh frame for the two modes that measure a phase:
     * the tones' raw phases at their bins - F1 always, F2 while a dual pair
     * is pinned - then their changes against the reference channel and the
     * two pilots' readings, each against the frame before it.  The fresh
     * frame starts one drawn hop into the window; the cycle's frame pair is
     * spaced by that hop, its first frame being the previous cycle's second.
     * At the first frame both paths have read, the reference's change less
     * the pilot's reading of the drift is zeroed, so the two compare from
     * that frame on.
     *
     * @return the fundamental's bin, or 0 when the frame gives none
     */
    private int readAlignmentFrame(FundamentalPhaseAlignment mode, FftResult r, boolean coherent) {
        // This frame's reference phases are its own: a frame that reads none
        // must not be turned by the frame before it.
        referenceTone1Rad = Double.NaN;
        referenceTone2Rad = Double.NaN;
        int halfSize = r.fftSize / 2;
        int k0 = phaseAlignBin(r, halfSize, coherent);
        if (k0 <= 0) return 0;
        // The analysed channel's phases come from this cycle's own spectrum:
        // its frames are de-rotated to the window's start and summed there, so
        // every bin of it carries that line's phase at that one origin - the
        // same origin for every line, cycle after cycle.
        double phiF  = Math.atan2(r.im[k0], r.re[k0]);
        takeRawPhases(r, k0);
        int    k1    = secondToneBin(r, halfSize);
        double phiF2 = (k1 > 0) ? Math.atan2(r.im[k1], r.re[k1]) : Double.NaN;
        readReferenceChange(mode, r, k0, k1, Math.hypot(r.re[k0], r.im[k0]), phiF, phiF2);
        readPilots(mode, r, halfSize, k0, k1, phiF, phiF2);
        if (!pilotRefOffsetTaken && pilotFirstTaken && pilotRefFirstTaken) {
            pilotRefOffsetTaken = true;
            pilotRefOffsetRad   = wrapTurn(pilotRefValueRad - pilotDriftRad());
        }
        return k0;
    }

    /** The second tone's bin for this frame, or -1 in single tone: its
     *  refined frequency over the frequency resolution, the derivation the
     *  accumulator's second tone is pinned by, rounded to the bin the line
     *  is read at. */
    private int secondToneBin(FftResult r, int halfSize) {
        if (!(r.fundamental2HzRefined > 0.0) || !(r.freqResolution > 0.0)) return -1;
        int k1 = (int) Math.round(r.fundamental2HzRefined / r.freqResolution);
        return (k1 > 0 && k1 <= halfSize) ? k1 : -1;
    }

    /** One bin's raw phase in the frame that starts {@code base} samples into
     *  {@code buf}, radians. */
    private double framePhase(double[] buf, FftResult r, int base, int bin) {
        FftAnalyzer.BinPhasor p = analyzer.frameBinPhasor(buf, base, r.fftSize, r.windowType, bin);
        return Math.atan2(p.im(), p.re());
    }

    /**
     * Takes the three lines' plain phases on the analysed channel for the
     * frame trace: the fundamental and both pilots, each one single frame at
     * the window's start.  That start is the base the whole alignment counts
     * from - the advance handed to {@link PairAdvance} is measured between
     * window starts - so these phases stand at the same origin as everything
     * derived from them, with the cycle's de-rotation and summing, and the
     * mains comb, left out.  Costs one single-bin transform per line, paid
     * only while {@link DebugSwitches#TRACE_PILOT_PHASE} is on.
     */
    private void takeRawPhases(FftResult r, int k0) {
        traceRawFundRad = Double.NaN;
        traceRawP1Rad   = Double.NaN;
        traceRawP2Rad   = Double.NaN;
        if (!DebugSwitches.TRACE_PILOT_PHASE || !winValid || winBuf.length < r.fftSize) return;
        traceRawFundRad = framePhase(winBuf, r, 0, k0);
        if (pilotReader1 == null) return;
        traceRawP1Rad = framePhase(winBuf, r, 0, pilotReader1.getBin());
        if (pilotReader2 == null) return;
        traceRawP2Rad = framePhase(winBuf, r, 0, pilotReader2.getBin());
    }

    /** A phase reduced to the nearest branch of one turn, radians. */
    private double wrapTurn(double rad) {
        return Math.IEEEremainder(rad, 2.0 * Math.PI);
    }

    /**
     * The two pilots on the fresh frame, and what they make of each tone's
     * excess.
     *
     * <p>Both lines are read first.  A line that fails its level test carries
     * the phase of noise, so the frame measures nothing and is handed to
     * {@link #pilotsMissing}; the phases held from the frame before it stay,
     * and the next frame's difference reaches across the hole.  With both
     * lines held, each reader gives the turn its line made since the frame it
     * was last accepted on, modulo one turn, and {@link PairAdvance} settles
     * the advance from those two readings and the fundamental's own change:
     * the pilots' rigid relation lists the pairs of whole turns that fit, the
     * fundamental's continuity picks the one whose excess change stays within
     * the drift bound for the elapsed time, over half the transform on either
     * side of the advance the hop counter implies.
     *
     * <p>A frame the three lines refuse is discarded: its readings contradict
     * each other, so nothing of it becomes a baseline and nothing is guessed
     * in its place; the next frame is measured across the whole span since
     * the last accepted one.
     *
     * <p>What a settled frame leaves is each tone's own drift - its measured
     * change less the turn the advance explains at its bin - summed into that
     * tone's excess and fed to that tone's trend against the frame's time;
     * the pilots' own disagreement on it feeds the tolerance the next frame's
     * search applies.
     *
     * <p>Where the election found ONE admissible pilot the same path runs on
     * that line alone, with the whole turns taken from the hop counter instead
     * of from the pair - see {@link #resolveAdvance}.
     */
    private void readPilots(FundamentalPhaseAlignment mode, FftResult r, int halfSize,
                            int k0, int k1, double phiF, double phiF2) {
        if (pilotReader1 == null) return;
        double timeSec = frameTimeSec(r);
        // Measured FIRST, on this frame's own bins: a frame whose line fails
        // the margin test is exactly the frame whose level has to be read, and
        // measuring after that test returned would print the level of the last
        // frame that passed it - a number that reads as a measurement and is
        // not one.
        tracePilotLevels(r);
        // A bin carries its line when its power stands the pilot-margin
        // preference over the median power of its neighbourhood, the lobe
        // left out of that median - the floor recipe of the reference feed
        // check, here on the analysed frame's own raw bins and per tick.  The
        // same margins give the pair its reading noise for this frame.
        double  margin1 = analysedPilotMarginDb(r, pilotReader1.getBin(), halfSize);
        double  margin2 = pilotReader2 == null ? Double.POSITIVE_INFINITY
                : analysedPilotMarginDb(r, pilotReader2.getBin(), halfSize);
        traceMargin1Db = margin1;   // on record for the failing frame as well
        traceMargin2Db = margin2;
        boolean held1   = margin1 >= pilotMarginDb;
        boolean held2   = margin2 >= pilotMarginDb;
        pilotReader1.setLineHeld(held1);
        if (pilotReader2 != null) pilotReader2.setLineHeld(held2);
        if (!held1 || !held2) {
            pilotRawPhase1 = Double.NaN;    // no line, no phase of its own to turn by
            pilotRawPhase2 = Double.NaN;
            warnNoPilotLine(held1 ? pilotReader2.getBin() : pilotReader1.getBin());
            // In the pilot mode the pair is the frame's clock: a frame it
            // cannot be read on has no measured turn, and turning it by the
            // count would hand the time base to the notched fundamental, so
            // the frame is not averaged.
            if (steersByPair(mode)) phaseAlignUnsettled = true;
            pilotsMissing(mode, r, timeSec);
            return;
        }
        double raw1 = Math.atan2(r.im[pilotReader1.getBin()], r.re[pilotReader1.getBin()]);
        double raw2 = pilotReader2 != null
                ? Math.atan2(r.im[pilotReader2.getBin()], r.re[pilotReader2.getBin()])
                : Double.NaN;
        pilotRawPhase1 = raw1;      // the lobes' own de-rotation reads them, accepted or not
        pilotRawPhase2 = raw2;
        // The two lines stand one fundamental apart, so the first pilot's
        // phase less the second's turns between two frames exactly as the
        // emitted fundamental does: the frame's reference phase, the reading
        // the reference channel gives in its own mode.  Stated on the
        // collection's first read frame as on every later one, so the tick
        // that becomes the accumulator's reference holds it.
        if (steersByPair(mode)) referenceTone1Rad = wrapTurn(raw1 - raw2);
        if (!pilotFirstTaken) {
            // The collection's first read frame is the zero everything after
            // it is measured from: it holds the phases and states no change.
            pilotFirstTaken = true;
            takeFirstPilotFrame(raw1, raw2, phiF, phiF2, winAbsStart);
            excessF1Rad        = 0.0;
            excessF2Rad        = 0.0;
            pilotMissingFrames = 0;
            excessTrend1.reset();
            excessTrend2.reset();
            return;
        }
        // Every line's change since that first frame, each known modulo its own
        // turn: the pilots' two and the tones', which is what the relations are
        // written in.
        double d1 = pilotReader1.changeSince(raw1);
        double d2 = pilotReader2 != null ? pilotReader2.changeSince(raw2) : 0.0;
        double c1 = wrapTurn(phiF - toneFirstPhase1);
        double c2 = (k1 > 0 && !Double.isNaN(phiF2) && !Double.isNaN(toneFirstPhase2))
                ? wrapTurn(phiF2 - toneFirstPhase2) : Double.NaN;
        PairAdvance.Result advance = resolveAdvance(d1, d2, (double) (winAbsStart - pilotFirstAbsStart),
                c1, k0, c2, k1, r.fftSize, timeSec,
                PILOT_PREDICT_HORIZON_TRANSFORMS * r.fftSize / (double) r.sampleRate,
                Math.hypot(pilotPhaseNoiseRad(margin1), pilotPhaseNoiseRad(margin2)));
        pilotMissingFrames = 0;
        // The excess is READ, not accumulated: each frame's own distance from
        // the first frame, so a frame the search settles wrongly is wrong on
        // that frame alone.
        // A frame the pair has not settled a correction for carries the last
        // settled drift, which stands still while the notch does not.  It is
        // good enough to show and not good enough to average on, so the tick
        // is kept out of the accumulator altogether.
        phaseAlignUnsettled = !advance.settled();
        // The advance the pair settled on, since the collection's first read
        // frame: what the tones' excess is counted against.  The accumulator's
        // rotation does not read it - that runs on the pair's phase difference
        // stated above.
        // MODE PILOT_TONE ONLY: the reference-channel mode reads the pair as
        // the control of its own reading, under a trace switch, and a switch
        // that is there to observe must not change what is measured.
        pilotAdvanceSamples = (mode == FundamentalPhaseAlignment.PILOT_TONE)
                ? advance.advanceSamples() + pilotAdvanceCarrySamples : Double.NaN;
        excessF1Rad = advance.excessChangeRad() + excessCarry1Rad;
        excessTrend1.add(timeSec, excessF1Rad);
        if (!Double.isNaN(c2)) {
            excessF2Rad = advance.excessChange2Rad() + excessCarry2Rad;
            // A tone the pair cannot carry states NaN, and a trend fed NaN
            // answers NaN for every frame after it - including the bridged
            // ones, which read from the trend alone.
            if (!Double.isNaN(excessF2Rad)) excessTrend2.add(timeSec, excessF2Rad);
        }
        traceAdvance(advance);
        if (pilotRetrackPending && mode == FundamentalPhaseAlignment.PILOT_TONE
                && advance.settled()) {
            // The frequency lock is tracking: this settled frame's totals
            // become the carries and its readings the new zero, so the turn
            // counting restarts on the trimmed tone while everything handed
            // to the accumulator stays continuous.  The frame itself was
            // measured and averaged on the old basis above.
            pilotRetrackPending      = false;
            pilotAdvanceCarrySamples = pilotAdvanceSamples;
            excessCarry1Rad          = excessF1Rad;
            if (!Double.isNaN(excessF2Rad)) excessCarry2Rad = excessF2Rad;
            if (pilotPair != null) pilotPair.reset();
            takeFirstPilotFrame(raw1, raw2, phiF, phiF2, winAbsStart);
            // The control comparison's zero is re-taken with the tracking:
            // the reference reading's baseline keeps the untrimmed first
            // frame's term, and only a fresh offset taken on the trimmed
            // tone cancels it out of the pilot-versus-reference readout.
            pilotRefOffsetTaken = false;
            if (log.isInfoEnabled()) {
                log.info("FFT phase alignment: pilot tracking re-zeroed on the trimmed tone "
                        + "(frequency lock tracking); advance {} samples and excess {} deg carried",
                        String.format("%.1f", pilotAdvanceCarrySamples),
                        String.format("%.3f", Math.toDegrees(excessCarry1Rad)));
            }
        }
    }

    /** Whether the pilot pair is the frame's clock: mode PILOT_TONE with both
     *  lines elected.  In the reference-channel mode the pair is read as the
     *  control of that mode's own reading and steers nothing; one elected
     *  line has no difference to state. */
    private boolean steersByPair(FundamentalPhaseAlignment mode) {
        return mode == FundamentalPhaseAlignment.PILOT_TONE && pilotReader2 != null;
    }

    /** Makes this frame's readings the zero every later frame of the
     *  collection is measured from - the readers' raw phases and the tones'
     *  phases.  Set once per collection, on its first read frame. */
    private void takeFirstPilotFrame(double raw1, double raw2, double phiF, double phiF2,
                                     long absStart) {
        pilotReader1.takeFirst(raw1);
        if (pilotReader2 != null) pilotReader2.takeFirst(raw2);
        toneFirstPhase1    = phiF;
        toneFirstPhase2    = phiF2;
        pilotFirstAbsStart = absStart;
    }

    /**
     * The advance since the previous read frame, from the lines the election
     * left.
     *
     * <p>With the pair it is the choice of {@link PairAdvance}: the
     * repetitions of the fundamental's period within reach of the capture's
     * count are scored by the pilots' two residuals and, within
     * {@code horizonSec} of the trend's newest frame, by how far each
     * repetition's excess stands from what the trend over the earlier frames
     * predicts for this one - NaN past that horizon or before the trend
     * holds a frame, which leaves the pilots to decide.
     *
     * <p>With ONE pilot there is no vernier and no search: the whole turns it
     * has made are the count the capture's own figure implies, and the tones
     * are measured against that line's phase at their own frequency.  Every
     * frame resolves, because nothing can disagree - which is exactly what
     * the second line would have been for: samples lost without an
     * announcement move the true turn count off the capture's, and a single
     * line cannot tell that from its own next whole turn.  The election
     * states the condition, once per collection.
     */
    private PairAdvance.Result resolveAdvance(double d1, double d2, double nominal, double c1,
                                              int k0, double c2, int k1, int fftSize,
                                              double timeSec, double horizonSec,
                                              double readingSigmaRad) {
        // The trends hold the CARRIED totals; the pair measures from its own
        // zero, so its prediction is the total less the carry.
        double expected1 = excessTrend1.predict(timeSec, horizonSec) - excessCarry1Rad;
        double expected2 = Double.isNaN(c2) ? Double.NaN
                : excessTrend2.predict(timeSec, horizonSec) - excessCarry2Rad;
        if (pilotPair != null) {
            return pilotPair.resolve(d1, d2, nominal, c1, k0, c2, k1, expected1, expected2,
                    readingSigmaRad);
        }
        // One line: its whole turns are the count the capture's own figure
        // implies, and its phase brought to the fundamental's frequency is
        // what the tones are measured against, exactly as the pair's is.
        int    p1      = pilotReader1.getBin();
        double turns   = d1 / (2.0 * Math.PI);
        long   m1      = Math.round(nominal * p1 / fftSize - turns);
        double atTone  = 2.0 * Math.PI * k0 * (turns + m1) / p1;
        return new PairAdvance.Result(m1, 0, 0.0, 0.0,
                wrapTurn(c1 - atTone),
                Double.isNaN(c2) ? Double.NaN : wrapTurn(c2 - atTone * k1 / (double) k0),
                true, (turns + m1) * fftSize / (double) p1);
    }

    /** One notice per collection that a pilot bin carries no line. */
    private void warnNoPilotLine(int bin) {
        if (pilotNoLineWarned) return;
        pilotNoLineWarned = true;
        if (log.isWarnEnabled()) {
            log.warn("FFT phase alignment: the pilot's bin {} holds no line - less than {} dB "
                    + "over its local floor; the frame measures nothing and its excess is "
                    + "predicted from the last {} measured frames", bin,
                    String.format("%.0f", pilotMarginDb), PILOT_PREDICT_POINTS);
        }
    }

    /**
     * A frame the pilots gave no measurement on, and what the ones after it
     * cost.  What arrives here is a frame with a pilot line absent; a frame
     * whose lines were read always settles.
     *
     * <p>The first of a run is bridged: each tone's excess is what its own
     * trend over the measured frames before it predicts at this frame's time,
     * and the frame is accumulated with that value - a drift that bends
     * within the window is followed by the fit where a held value would lag
     * it.
     *
     * <p>The second re-syncs the capture through the path a detected
     * discontinuity takes: two frames in a row without a time base is what a
     * gap in the stream looks like from here.  The re-sync itself belongs to
     * the tick loop, which discards this frame with it - see
     * {@link #pilotResyncPending} - so the window is re-anchored and reloaded
     * before the next reading.
     *
     * <p>The third means the re-sync did not bring the lines back, so the
     * coherent average has no time base at all: the operator is told and the
     * recording ends through the auto-stop path the stop-after-N count uses.
     * That last step belongs to the pilot mode alone - in the
     * reference-channel mode the pilots are only the trace's control, and
     * losing them costs a trace line.
     */
    private void pilotsMissing(FundamentalPhaseAlignment mode, FftResult r, double timeSec) {
        // Before the collection's first measured frame there is nothing to
        // bridge and nothing to lose: a generator that has not brought its
        // lines up yet leaves the average unaligned, as it does with no pilot
        // at all, and the count starts when the pair has measured once.
        if (!pilotFirstTaken) return;
        pilotMissingFrames++;
        // No line to read: the frame is bridged by each tone's own trend at
        // the frame's time and accumulated with that value.
        if (excessTrend1.getCount() > 0) excessF1Rad = excessTrend1.predict(timeSec);
        if (excessTrend2.getCount() > 0) excessF2Rad = excessTrend2.predict(timeSec);
        traceTurns1     = 0;                // this frame resolved none
        traceTurns2     = 0;
        traceRigidRad   = 0.0;
        if (pilotMissingFrames == 2) {
            // The tick loop does the re-sync: this frame has to be DISCARDED
            // with it, and a frame that still published its own result would
            // reach the view right after the warning banner and clear it.
            pilotResyncPending = true;
            return;
        }
        if (pilotMissingFrames < 3 || mode != FundamentalPhaseAlignment.PILOT_TONE) return;
        if (!pilotLostPublished) {
            pilotLostPublished = true;
            if (log.isWarnEnabled()) {
                log.warn("FFT phase alignment: no pilot line on {} frames in a row - the coherent "
                        + "average has no time base and the recording is ending", pilotMissingFrames);
            }
            publishOnUiThread(Events.FFT_PHASE_ALIGN_PILOT_LOST,
                    new PilotLost(r.fundamentalHzRefined));
        }
        paused.set(true);
        if (display != null && !display.isDisposed()) {
            display.asyncExec(() ->
                    MessageBus.instance().publish(Events.FFT_RECORDING_AUTO_STOPPED));
        }
    }

    /** This cycle's time on the capture's own clock, seconds: the window's
     *  absolute start - the origin the cycle's phases are read at - over the
     *  sample rate.  What the trend fits against: a re-sync gap enters as the
     *  samples that arrived, so a value read after it is read at the right
     *  distance. */
    private double frameTimeSec(FftResult r) {
        return winAbsStart / (double) r.sampleRate;
    }

    /** Each tone against the REFERENCE channel in the SAME raw frame - the
     *  same bin on the two paths, read the same way, so nothing of the
     *  frame's position enters - taken against the first frame that had the
     *  mirror and continued unwrapped.  F1 always; F2 at its own bin while a
     *  dual pair is pinned, on its own baseline, because a notch turns the
     *  two tones by different amounts.  Without the mirror nothing is read
     *  and the reference values stay unset.
     *
     *  <p>In mode REFERENCE_CHANNEL the mirror's own tone phases are handed
     *  on as well: their change from tick to tick is the turn the advance
     *  gives each tone, which the accumulator turns the frame by.  In the
     *  pilot mode the mirror is the control of the pair's reading, and a
     *  reading taken to observe must not change what is measured, so nothing
     *  is handed on there. */
    private void readReferenceChange(FundamentalPhaseAlignment mode, FftResult r, int k0, int k1,
            double fundMag, double phiF, double phiF2) {
        traceRawRefRad    = Double.NaN;     // no mirror read yet on this frame
        boolean steers = mode == FundamentalPhaseAlignment.REFERENCE_CHANNEL;
        if (refWinBuf == null || !winValid) return;
        // Read at the window's start, the origin the analysed channel's own
        // phases carry, so nothing of the drawn hop enters the comparison.
        FftAnalyzer.BinPhasor ref = analyzer.frameBinPhasor(refWinBuf, 0, r.fftSize, r.windowType, k0);
        double refMag = Math.hypot(ref.re(), ref.im());
        if (refMag == 0.0) return;
        double refPhase = Math.atan2(ref.im(), ref.re());
        traceRawRefRad  = refPhase;
        if (steers) referenceTone1Rad = refPhase;
        double against  = wrapTurn(phiF - refPhase);
        referenceFeedDb = 20.0 * Math.log10(fundMag / refMag);
        if (!pilotRefFirstTaken) {
            pilotRefFirstTaken = true;
            pilotRefFirstRad   = against;
            pilotRefChangeRad  = 0.0;
            if (pilotRefAverage != null) pilotRefAverage.reset();
        } else {
            pilotRefChangeRad = continuedChangeRad(pilotRefChangeRad, against - pilotRefFirstRad);
        }
        pilotRefValueRad = smoothed(pilotRefAverage, pilotRefChangeRad);
        if (k1 <= 0 || Double.isNaN(phiF2)) return;
        double ref2Phase = framePhase(refWinBuf, r, 0, k1);
        if (steers) referenceTone2Rad = ref2Phase;
        double against2 = wrapTurn(phiF2 - ref2Phase);
        if (!pilotRef2FirstTaken) {
            pilotRef2FirstTaken = true;
            pilotRef2FirstRad   = against2;
            pilotRef2ChangeRad  = 0.0;
            if (pilotRef2Average != null) pilotRef2Average.reset();
        } else {
            pilotRef2ChangeRad = continuedChangeRad(pilotRef2ChangeRad, against2 - pilotRef2FirstRad);
        }
        pilotRef2ValueRad = smoothed(pilotRef2Average, pilotRef2ChangeRad);
    }

    /** The pilots' reading of the FUNDAMENTAL's drift, radians, wrapped to
     *  plus or minus pi: F1's excess since the collection's first read frame
     *  - mode PILOT_TONE's own excess, and the control of the reading in mode
     *  REFERENCE_CHANNEL.  Zero until that first frame, and the trend's
     *  prediction on a frame the pilots could not measure. */
    private double pilotDriftRad() {
        if (!pilotFirstTaken) return 0.0;
        return wrapTurn(excessF1Rad);
    }

    /** One line of the per-frame trace under
     *  {@link DebugSwitches#TRACE_HARMONIC_PHASE}: the mode's excess, what
     *  each pilot alone made of the fundamental's change on this frame, and
     *  the reading against the reference channel. */
    private void traceExcess(double excessRad) {
        if (!DebugSwitches.TRACE_HARMONIC_PHASE || !log.isInfoEnabled()) return;
        log.info("FFT phase excess, frame {}: {} deg{}{}{}",
                completedAnalyses,
                String.format("%.3f", Math.toDegrees(excessRad)),
                pairTrace(),
                pilotReferenceTrace(),
                rawPhaseTrace());
    }

    /**
     * The four phases the samples themselves hold, degrees: the fundamental
     * and the two pilots on the analysed channel and the fundamental on the
     * reference channel, each the plain {@code atan2} of one single frame's
     * bin taken at the window's start - the base the advance is counted
     * between.  Nothing of the alignment has touched them: no de-rotation, no
     * summing of the cycle's frames, no mains comb, no first-frame zero, no
     * turn resolution, no average.  Everything else on the line is derived
     * from these four, so a collection can be re-read from the trace alone.
     * NaN where a line had nothing to read on this frame: no window, no bin,
     * or no mirror.
     */
    private String rawPhaseTrace() {
        if (!DebugSwitches.TRACE_PILOT_PHASE) return "";
        return String.format("; raw f0 %.4f deg; raw P1 %.4f deg; raw P2 %.4f deg; raw ref %.4f deg",
                Math.toDegrees(traceRawFundRad), Math.toDegrees(traceRawP1Rad),
                Math.toDegrees(traceRawP2Rad), Math.toDegrees(traceRawRefRad));
    }

    /** The second tone's own line of the same trace, in dual tone only: F2
     *  carries its own excess through its own member of the family, so the
     *  two lines together say whether the pair holds BOTH tones. */
    private void traceExcessF2() {
        if (!DebugSwitches.TRACE_HARMONIC_PHASE || !log.isInfoEnabled()) return;
        if (!(accumulator.getKappa2() > 0.0)) return;
        log.info("FFT phase excess F2, frame {}: {} deg{}",
                completedAnalyses,
                String.format("%.3f", Math.toDegrees(wrapTurn(excessF2Rad))),
                pairTrace());
    }

    /** The pair's part of a trace line: the two pilot lines' own levels and
     *  margins over their floor, the admission those margins gave the frame,
     *  the whole turns each line has made since the collection's first
     *  frame, and how far apart their figures stood.  Empty while no frame
     *  has been measured. */
    private String pairTrace() {
        if (!pilotFirstTaken) return "";
        String readings = "";
        PairAdvance pair = pilotPair;
        double admitRad = pair != null ? pair.getTraceAdmitRad() : Double.NaN;
        if (pair != null && !Double.isNaN(pair.getTraceCoarseRad())) {
            // The frame's own readings at the fundamental, settled or not: the
            // pilots' difference, each pilot's fine reading under the
            // correction in force, and the miss of their mean against the
            // difference - the figure a frame that settles nothing leaves
            // behind, so its size is on record.
            double coarse = pair.getTraceCoarseRad();
            double fine1  = pair.getTraceFine1Rad();
            double fine2  = pair.getTraceFine2Rad();
            double miss   = Math.IEEEremainder(0.5 * (fine1 + fine2) - coarse, 2.0 * Math.PI);
            readings = String.format("; coarse %.3f deg, fine1 %.3f deg, fine2 %.3f deg, miss %.3f deg",
                    Math.toDegrees(coarse), Math.toDegrees(fine1), Math.toDegrees(fine2),
                    Math.toDegrees(miss));
        }
        return String.format("; P1&2 %.1f&%.1f dBFS; margin1&2 %.1f&%.1f dB; admit %.3f deg; "
                        + "turns %d/%d; res. rigid %.4f deg%s",
                tracePilot1DbFs, tracePilot2DbFs, traceMargin1Db, traceMargin2Db,
                Math.toDegrees(admitRad), traceTurns1, traceTurns2,
                Math.toDegrees(traceRigidRad), readings);
    }

    /** Keeps what the frame trace states about a measured frame: the whole
     *  turns the pair settled on since the collection's first frame and how
     *  far apart the two pilots' figures stood on them. */
    private void traceAdvance(PairAdvance.Result advance) {
        if (!DebugSwitches.TRACE_HARMONIC_PHASE) return;
        traceTurns1         = advance.m1();
        traceTurns2         = advance.m2();
        traceRigidRad       = advance.rigidRad();
    }

    /** Each pilot line's level on this frame, dBFS, for the trace: the bin's
     *  magnitude on the result's own amplitude scale, NaN where no reader
     *  stands or the result carries no scale. */
    private void tracePilotLevels(FftResult r) {
        if (!DebugSwitches.TRACE_HARMONIC_PHASE || r.amplitudeDbFs == null) {
            tracePilot1DbFs = Double.NaN;
            tracePilot2DbFs = Double.NaN;
            return;
        }
        double conv = r.amplitudePerMagnitude();
        tracePilot1DbFs = pilotLevelDbFs(r, pilotReader1, conv);
        tracePilot2DbFs = pilotLevelDbFs(r, pilotReader2, conv);
    }

    private double pilotLevelDbFs(FftResult r, PilotReader reader, double conv) {
        if (reader == null || conv <= 0.0) return Double.NaN;
        double ampl = Math.hypot(r.re[reader.getBin()], r.im[reader.getBin()]) * conv;
        return ampl > 1e-15 ? 20.0 * Math.log10(ampl) : -300.0;
    }

    /** One of the pilot path's two averages: over
     *  {@link DebugSwitches#PILOT_AVERAGE_FRAMES} accepted frames, or none
     *  at zero. */
    private MovingAverage pilotAverage() {
        int frames = DebugSwitches.PILOT_AVERAGE_FRAMES;
        return frames > 0 ? new MovingAverage(frames) : null;
    }

    /** A change as the excess and the trace take it: fed to {@code average}
     *  and read back from it where one exists, else the change itself. */
    private double smoothed(MovingAverage average, double changeRad) {
        if (average == null) return changeRad;
        average.feed(changeRad);
        return average.value();
    }

    /** Forgets everything the alignment has read: the collection's zero - the
     *  first frame's tone phases and stream position, and the readers' own -
     *  the residual statistic behind the reading spread, both tones' trends
     *  and, where they exist, the reference channel's averages.  Every reading
     *  is relative to that zero, so none of it may cross into a new
     *  collection. */
    private void resetPilotHistory() {
        toneFirstPhase1    = Double.NaN;
        toneFirstPhase2    = Double.NaN;
        pilotFirstAbsStart = 0L;
        // A reset or a restart clears the banner in the view, so the next
        // undecided stretch has to post its notice afresh.
        phaseAlignUnsettled = false;
        phaseAlignBannerUp  = false;
        pilotAdvanceSamples = Double.NaN;   // nothing measured for the new collection
        referenceTone1Rad   = Double.NaN;
        referenceTone2Rad   = Double.NaN;
        // A new collection's zeros are its own: nothing to carry, and a
        // re-zero armed for the old collection is void.
        pilotRetrackPending      = false;
        pilotAdvanceCarrySamples = 0.0;
        excessCarry1Rad          = 0.0;
        excessCarry2Rad          = 0.0;
        // The trace states what THIS collection has measured: its first frame
        // measures nothing, and the previous collection's turns are not its.
        traceTurns1         = 0;
        traceTurns2         = 0;
        traceRigidRad       = 0.0;
        tracePilot1DbFs     = Double.NaN;
        tracePilot2DbFs     = Double.NaN;
        traceMargin1Db      = Double.NaN;
        traceMargin2Db      = Double.NaN;
        traceRawFundRad     = Double.NaN;
        traceRawP1Rad       = Double.NaN;
        traceRawP2Rad       = Double.NaN;
        traceRawRefRad      = Double.NaN;
        if (pilotPair != null) pilotPair.reset();
        if (pilotReader1 != null) pilotReader1.reset();
        if (pilotReader2 != null) pilotReader2.reset();
        excessTrend1.reset();
        excessTrend2.reset();
        if (pilotRefAverage == null) return;
        pilotRefAverage.reset();
        pilotRef2Average.reset();
    }

    /** A line's change continued from the previous accepted frame's: the
     *  fresh reading, known modulo a turn, is placed on the turn the
     *  previous value had reached, so the change stays continuous where the
     *  reading alone would wrap. */
    private double continuedChangeRad(double previousRad, double readingRad) {
        return previousRad + Math.IEEEremainder(readingRad - previousRad, 2.0 * Math.PI);
    }

    /** The phase noise of a line standing {@code marginDb} over the median
     *  power of its neighbourhood, radians rms: the noise's quadrature
     *  component over the line's amplitude, the median of an exponential
     *  being ln 2 of its mean power.  What the pair measures its admission
     *  in; zero for a margin without bound. */
    private double pilotPhaseNoiseRad(double marginDb) {
        return Math.pow(10.0, -marginDb / 20.0) / Math.sqrt(2.0 * Math.log(2.0));
    }

    /** The pilot's level over its own local floor on the ANALYSED channel,
     *  dB, read from the fresh frame's raw bins. */
    private double analysedPilotMarginDb(FftResult r, int k2, int halfSize) {
        int lo = Math.max(0, k2 - PHASE_ALIGN_FLOOR_SPAN_BINS);
        int hi = Math.min(halfSize, k2 + PHASE_ALIGN_FLOOR_SPAN_BINS);
        double[] powers = new double[hi - lo + 1];
        for (int k = lo; k <= hi; k++) {
            powers[k - lo] = r.re[k] * r.re[k] + r.im[k] * r.im[k];
        }
        return marginOverFloorDb(powers, k2 - lo);
    }

    /** A line's power over the median power of its neighbourhood, dB, with
     *  its own lobe left out of that median - a windowed tone spreads over a
     *  few bins and would lift the very floor it is measured against.
     *  {@code powers} holds the neighbourhood in bin order and
     *  {@code powers[centre]} is the line's own bin. */
    private double marginOverFloorDb(double[] powers, int centre) {
        double[] floor = new double[powers.length];
        int nFloor = 0;
        for (int i = 0; i < powers.length; i++) {
            if (Math.abs(i - centre) > PHASE_ALIGN_FLOOR_EXCLUDE_BINS) {
                floor[nFloor++] = powers[i];
            }
        }
        Arrays.sort(floor, 0, nFloor);
        double median   = nFloor > 0 ? floor[nFloor / 2] : 0.0;
        double linePow  = powers[centre];
        return (median > 0.0 && linePow > 0.0) ? 10.0 * Math.log10(linePow / median) : -300.0;
    }

    /**
     * The tail of the pilot frame trace under
     * {@link DebugSwitches#TRACE_PILOT_PHASE}.  The control is the
     * fundamental on the REFERENCE channel - the un-notched tone - and never
     * a pilot read on that channel: the pilot's phase there would be its own
     * second converter path, which says nothing about the notch.  One value
     * comes from it, at the fundamental and from the averaged changes: the
     * difference between that channel's reading of the fundamental's drift
     * and the pilot's - zero for a pilot that is the clean time base the
     * method takes it for.
     *
     * <p>Read here and applied nowhere.  Empty when the switch is off or no
     * frame has been read against the mirror with the pilot read as well.
     */
    private String pilotReferenceTrace() {
        if (!DebugSwitches.TRACE_PILOT_PHASE || !pilotRefOffsetTaken) return "";
        double againstReference = fundamentalAgainstReferenceRad();
        if (Double.isNaN(againstReference)) return "";
        return String.format("; pilot vs ref %.3f deg",
                Math.toDegrees(pilotAgainstReferenceRad(againstReference)));
    }

    /** The fundamental's change against the reference channel, smoothed as
     *  the excess is and wrapped to plus or minus pi: the fundamental's
     *  drift as the reference channel sees it.  NaN while no frame of the
     *  collection has been read against the mirror. */
    private double fundamentalAgainstReferenceRad() {
        if (!pilotRefFirstTaken) return Double.NaN;
        return Math.IEEEremainder(pilotRefValueRad, 2.0 * Math.PI);
    }

    /** The reference channel's reading of the fundamental's drift less the
     *  pilot's, both at the fundamental, zeroed at the first frame both were
     *  read and wrapped to plus or minus pi: zero for a pilot that is the
     *  clean time base the method takes it for, and the pilot method's own
     *  error where it is not. */
    private double pilotAgainstReferenceRad(double fundamentalAgainstReferenceRad) {
        return Math.IEEEremainder(
                fundamentalAgainstReferenceRad - pilotDriftRad() - pilotRefOffsetRad, 2.0 * Math.PI);
    }

    /** The bin the accumulator reads the fundamental at for THIS tick: its
     *  pinned fundamental's whole bin while the accumulator is live for this
     *  geometry, and otherwise the bin this tick is about to pin - the same
     *  rule the restart block applies, so the reference phasor is taken at the
     *  loop's bin even on the tick that pins it.  A bin that wandered with
     *  the per-tick peak would compare two different frequencies' phases. */
    private int phaseAlignBin(FftResult r, int halfSize, boolean coherent) {
        boolean live = accumulator.isStarted() && accumulator.getFftSize() == r.fftSize
                && accumulator.isCoherent() == coherent && pinnedFundBin(halfSize) > 0;
        int k0 = live ? pinnedFundBin(halfSize)
                      : Math.max(1, (int) Math.round(r.fundamentalHzRefined / r.freqResolution));
        return Math.min(k0, halfSize);
    }

    /**
     * Measures, on the reference window this cycle holds, the tone frequencies
     * the frequency lock steers on, and stamps them on the result: the
     * fundamental always, the second tone whenever the analysis found one (its
     * bin comes from the analysed channel's own refined estimate - the same
     * tone is at the same place on both channels).  Both stay
     * {@link Double#NaN} when no reference window exists, which is every mode
     * but the reference-channel alignment.
     *
     * <p>Called for EVERY analysed cycle, not only for the ones that reach the
     * accumulator: the frequency lock runs with the averaging off or set to one
     * as well, and a measurement tied to the accumulate flag would leave it
     * steering on the notched channel in exactly those settings.  It therefore
     * derives its own geometry - the alignment bin - by the same rule the
     * phase excess uses, and reads each tone the way the coherent average
     * re-pins the fundamental: the tone's phase at its bin on this cycle's
     * frame ({@link FftAnalyzer#frameBinPhasor}, at the window's start)
     * against the phase it read on the previous cycle's frame, over the
     * advance between the two windows ({@link MathUtil#kappaFromPhaseStep}).
     * The first cycle after a window rebuild has no previous reading and
     * stamps nothing.
     *
     * <p>Why measure them at all: behind a notch the analysed fundamental sits
     * tens of dB into the noise, where a sub-bin estimate wanders, while the
     * reference channel carries the same tone clear of it.  Only the loop's
     * input changes - the accumulator, the coherent bin and the plots keep the
     * analysed channel's numbers.
     *
     * <p>A feed the start check refused carries no tone at all - the bin holds
     * noise, and the frequency read off noise is as meaningless as the phase
     * the accumulator already discards for it.  Nothing is stamped then, and
     * the lock keeps steering on the analysed channel.
     */
    private void measureReferenceFrequencies(FftResult r, boolean coherent) {
        if (refWinBuf == null || refMirrorForTrace || !winValid || r.freqResolution <= 0.0
                || phaseAlignRefused) {
            referenceTones.forget();
            return;
        }
        int halfSize = r.fftSize / 2;
        int k0 = phaseAlignBin(r, halfSize, coherent);
        if (k0 <= 0) {
            referenceTones.forget();
            return;
        }
        referenceTones.read(k0, secondToneBin(r, halfSize), winAbsStart, r.fftSize, r.freqResolution,
                bin -> framePhase(refWinBuf, r, 0, bin));
        if (!Double.isNaN(referenceTones.frequencyHz1())) {
            r.referenceFundamentalHzRefined = referenceTones.frequencyHz1();
        }
        if (!Double.isNaN(referenceTones.frequencyHz2())) {
            r.referenceFundamental2HzRefined = referenceTones.frequencyHz2();
        }
    }

    /**
     * Measures, on the analysed window this cycle holds, the tone frequencies
     * the same way as the reference channel's above - the phase step between
     * this cycle's frame and the previous one at each tone's bin - and writes
     * them on the result as its refined frequencies, in place of the
     * analysis's one-frame estimate, whenever a previous frame exists to step
     * from.  Called for every analysed cycle, with the reference measurement.
     *
     * <p>Read on the raw retained window, not on the transform the analysis
     * delivered: the incoherent analysis hands over magnitudes in place of
     * the phasors, and a reading that holds under either averaging mode is
     * what the lock needs.  The one-frame estimate stays for the first cycle
     * after a window rebuild, which has no previous frame; the lock does not
     * act on it ({@link #applyFrequencyLock}).
     */
    private void measureAnalysedFrequencies(FftResult r) {
        if (!winValid || r.freqResolution <= 0.0) {
            analysedTones.forget();
            return;
        }
        int halfSize = r.fftSize / 2;
        int k0 = Math.min(halfSize, Math.max(1, r.fundamentalBin));
        analysedTones.read(k0, secondToneBin(r, halfSize), winAbsStart, r.fftSize, r.freqResolution,
                bin -> framePhase(winBuf, r, 0, bin));
        if (!Double.isNaN(analysedTones.frequencyHz1())) {
            r.fundamentalHzRefined = analysedTones.frequencyHz1();
        }
        if (!Double.isNaN(analysedTones.frequencyHz2())) {
            r.fundamental2HzRefined = analysedTones.frequencyHz2();
        }
    }

    /** Drops the previous cycle's readings on both channels: a cycle that
     *  reads nothing leaves nothing for the next one to pair with. */
    private void forgetToneReadings() {
        analysedTones.forget();
        referenceTones.forget();
    }

    /** Resets the frequency-lock loop(s) so alignment converges fresh from
     *  zero - called when the user turns alignment ON, on a change of the
     *  generated signal and on Record stop.  Kept separate from
     *  {@link #resetStatistics()} so a manual statistics reset keeps the
     *  converged alignment intact.  Any thread: while the worker runs, the
     *  whole reset runs on its thread at the top of its next loop iteration
     *  (paused or not); with no worker thread it runs here, the way
     *  {@link #resetStatistics()} wipes worker state when stopped. */
    public void resetFrequencyLock() {
        if (running) {
            frequencyLockResetPending.set(true);
        } else {
            resetFrequencyLockState();      // no worker thread to race with
        }
    }

    /** The reset itself: re-arms the magnitude-drift watch, zeroes the loops
     *  and tells the generator to drop any residual trim
     *  ({@link Events#GENERATOR_FREQ_TRIM_RESET}).  Clearing only the loop
     *  state would leave the generator at the last published trim, and the
     *  fresh loop - which assumes "generator = target + correction" - would
     *  overshoot by exactly that stale amount on its first correction.  The
     *  event goes through the UI thread's queue behind every trim already
     *  posted, so the reset is what the generator ends on. */
    private void resetFrequencyLockState() {
        // Re-arm the magnitude-drift watch: the next fed tick baselines the
        // fundamental level(s); the comparison fires once alignment settles.
        alignWatchArmed     = true;
        alignWatchBaselined = false;
        // The lock re-acquires from scratch, so the pilot tracking's one-shot
        // re-zero is armed again for the moment it is tracking once more.
        pilotRetrackDone    = false;
        pilotRetrackPending = false;
        if (fll == null && fll2 == null) return;   // alignment never engaged - no trims published
        // The generator returns to its untrimmed tones with the event below, so
        // the held pair takes the untrimmed targets the loops steer to.  They
        // come from this worker, not from the pair minus a correction: a pair
        // answered by the local generator is untrimmed already.
        if (heldEmittedHz != null) {
            if (fllTargetHz > 0) heldEmittedHz[0] = fllTargetHz;
            if (fll2TargetHz > 0 && heldEmittedHz.length > 1) heldEmittedHz[1] = fll2TargetHz;
        }
        if (fll  != null) fll.reset();
        if (fll2 != null) fll2.reset();
        pendingFllReading = null;       // read before the reset: not the fresh loops' to act on
        clearFllAverages();
        republishTrims();
        publishOnUiThread(Events.GENERATOR_FREQ_TRIM_RESET, null);
    }

    /** Makes the next update of each loop publish its trim whatever its
     *  correction: the generator's standing trim has been dropped. */
    private void republishTrims() {
        fllPublishedCorrection  = Double.NaN;
        fll2PublishedCorrection = Double.NaN;
    }

    /**
     * The generator frequency lock, fed from the PREVIOUS tick's reading on
     * the worker thread, and the trims it publishes.  Called once per
     * analysed tick, settled or not, after the accumulate step and before
     * the unsettled return and the display throttle - so every tick that
     * gets here passed both discontinuity gates on a full post-seek window
     * with the drain finished.  This tick's
     * own reading is kept for the next call ({@link #pendingFllReading}): a
     * discontinuity sitting at a window's edge is found by the gates only on
     * the tick after it, so a tick passing them is what clears the reading
     * before it.
     *
     * <p>Gated, in this order: a reset that landed while this window was
     * analysed (the tick's epoch); in mode PILOT_TONE with a pilot elected, a
     * tick whose pilot reading lost a line - the frame is disturbed; the
     * three prerequisite prefs (snap-to-bin, fund-from-gen, align mode) and
     * the generator actively emitting - a tick refused by any of these drops
     * the pending reading as well; then, on the pending reading, per tone
     * the target, a finite measurement and the plausibility bound.  Last,
     * the first tick after a re-sync that passed all of these is skipped
     * ({@link #fllResyncHold}); the re-sync itself dropped the pending
     * reading ({@link #dropFllFeed}).
     *
     * <p>A trim is published only when the loop's correction moved since the
     * last published one, so a holding loop leaves the generator alone.
     *
     * @param r               this tick's result, before the accumulator overlay
     * @param alignMode       the tick's fundamental phase alignment mode
     * @param samplesAbsStart absolute capture position of the window's start
     * @param writePos        live capture head - where a correction issued now lands
     * @param epochAtStart    the reset epoch the tick started under
     */
    private void applyFrequencyLock(FftResult r, FundamentalPhaseAlignment alignMode,
                                    long samplesAbsStart, long writePos, long epochAtStart) {
        FllReading reading = pendingFllReading;
        pendingFllReading  = null;
        if (epochAtStart != resetEpoch.get()) return;
        if (alignMode == FundamentalPhaseAlignment.PILOT_TONE && pilotReader1 != null
                && pilotMissingFrames > 0) {
            return;
        }
        Preferences prefs = Preferences.instance();
        if (!prefs.isGenSnapToFftBin())       return;
        if (!prefs.isFftFundFromGenerator())  return;
        AlignGenerator mode = prefs.getFftAlignGenerator();
        if (mode == AlignGenerator.NONE)      return;
        if (!isGeneratorActive())             return;
        // Swap the loop type to match the selected mode (a change resets both loops).
        if (fll == null || fll.getMode() != mode) {
            FrequencyAlignerFactory factory = FrequencyAlignerFactory.instance();
            fll  = factory.create(mode);
            fll2 = factory.create(mode);
            republishTrims();
            clearFllAverages();
        }
        // Branch on generator form: single-tone runs one loop, dual-tone two
        // independent loops driven by the per-tone refined frequencies.
        boolean dualTone = prefs.getGenSignalForm().isDualTone();
        // Each tone's measurement: the reference channel's when this cycle
        // measured it (the phase-alignment mode reads that channel, where
        // the tones stand clear of the notch), else the analysed channel's
        // phase-step reading - NaN on a cycle without a previous frame to
        // step from, so the loop is never fed the analysis's one-frame
        // estimate, which under a rectangular window reads the noise beside
        // the peak.  Kept for the next cycle; what is fed below is the
        // reading the previous cycle kept.
        int k2 = dualTone ? secondToneBin(r, r.fftSize / 2) : 0;
        pendingFllReading = new FllReading(
                loopMeasurement(r.referenceFundamentalHzRefined, analysedTones.frequencyHz1()),
                dualTone ? loopMeasurement(r.referenceFundamental2HzRefined, analysedTones.frequencyHz2())
                         : Double.NaN,
                samplesAbsStart, r.sampleRate, r.fftSize, epochAtStart,
                r.fundamentalDbFs, k2 > 0 ? r.amplitudeDbFs[k2] : Double.NaN);
        if (reading == null || reading.epoch() != epochAtStart) return;
        if (dualTone) {
            double t1 = FftBinSnap.snapIfEnabled(GenSignalForm.DUAL_TONE,
                    reading.sampleRate(), prefs.getFftLength(), prefs.isGenSnapToFftBin(),
                    prefs.getGenDualToneFreq1Hz());
            double t2 = FftBinSnap.snapIfEnabled(GenSignalForm.DUAL_TONE,
                    reading.sampleRate(), prefs.getFftLength(), prefs.isGenSnapToFftBin(),
                    prefs.getGenDualToneFreq2Hz());
            fllTargetHz  = t1;
            fll2TargetHz = t2;
            // A tone without a finite estimate is gated.
            double m1 = reading.measured1();
            double m2 = reading.measured2();
            boolean feed1 = t1 > 0 && Double.isFinite(m1)
                    && plausibleFllMeasurement(t1, m1, reading.sampleRate(), reading.fftSize());
            boolean feed2 = t2 > 0 && Double.isFinite(m2)
                    && plausibleFllMeasurement(t2, m2, reading.sampleRate(), reading.fftSize());
            if ((feed1 || feed2) && skipAfterResync()) return;
            if (feed1) m1 = fllFeed(fll,  fllAverage1, m1, reading.absStart(), reading.fftSize());
            if (feed2) m2 = fllFeed(fll2, fllAverage2, m2, reading.absStart(), reading.fftSize());
            if (feed1) {
                fll.update(t1, m1, reading.absStart(), writePos, reading.sampleRate(), reading.fftSize());
                double pub1 = t1 + fll.getCorrection();
                boolean moved1 = fll.getCorrection() != fllPublishedCorrection;
                if (DebugSwitches.TRACE_FLL && log.isWarnEnabled()) {
                    log.warn("FLL t1: target={} meas={} corr={} {}",
                            String.format(Locale.US, "%.6f", t1),
                            String.format(Locale.US, "%.6f", m1),
                            String.format(Locale.US, "%+.6f", fll.getCorrection()),
                            moved1 ? String.format(Locale.US, "pub=%.6f", pub1) : "held");
                }
                if (moved1) {
                    fllPublishedCorrection = fll.getCorrection();
                    if (heldEmittedHz != null) heldEmittedHz[0] = pub1;
                    publishOnUiThread(Events.GENERATOR_FREQ_TRIM, pub1);
                }
            } else if (DebugSwitches.TRACE_FLL && log.isWarnEnabled()) {
                log.warn("FLL t1 GATED: target={} meas={}",
                        String.format(Locale.US, "%.6f", t1),
                        String.format(Locale.US, "%.6f", m1));
            }
            if (feed2) {
                fll2.update(t2, m2, reading.absStart(), writePos, reading.sampleRate(), reading.fftSize());
                double pub2 = t2 + fll2.getCorrection();
                boolean moved2 = fll2.getCorrection() != fll2PublishedCorrection;
                if (DebugSwitches.TRACE_FLL && log.isWarnEnabled()) {
                    log.warn("FLL t2: target={} meas={} corr={} {}",
                            String.format(Locale.US, "%.6f", t2),
                            String.format(Locale.US, "%.6f", m2),
                            String.format(Locale.US, "%+.6f", fll2.getCorrection()),
                            moved2 ? String.format(Locale.US, "pub=%.6f", pub2) : "held");
                }
                if (moved2) {
                    fll2PublishedCorrection = fll2.getCorrection();
                    if (heldEmittedHz != null && heldEmittedHz.length > 1) heldEmittedHz[1] = pub2;
                    publishOnUiThread(Events.GENERATOR_FREQ_TRIM_2, pub2);
                }
            } else if (DebugSwitches.TRACE_FLL && log.isWarnEnabled()) {
                log.warn("FLL t2 GATED: target={} meas={}",
                        String.format(Locale.US, "%.6f", t2),
                        String.format(Locale.US, "%.6f", m2));
            }
            boolean aligned = t1 > 0 && t2 > 0
                    && Math.abs(m1 - t1) <= t1 * ALIGN_DONE_PPM * Constants.PPM
                    && Math.abs(m2 - t2) <= t2 * ALIGN_DONE_PPM * Constants.PPM;
            // The reading's own levels, before the accumulator overlay: tone
            // 2's is its bin's, read where the second tone's line is read.
            watchAlignmentMagnitudeDrift(reading.fundamentalDbFs(), reading.tone2DbFs(), aligned);
        } else {
            double measured = reading.measured1();
            if (!Double.isFinite(measured)) return;
            double target = FftBinSnap.snapIfEnabled(GenSignalForm.SINE,
                    reading.sampleRate(), prefs.getFftLength(), prefs.isGenSnapToFftBin(),
                    prefs.getGenFrequencyHz());
            fllTargetHz  = target;
            fll2TargetHz = 0.0;
            if (!(target > 0)) return;
            if (!plausibleFllMeasurement(target, measured,
                    reading.sampleRate(), reading.fftSize())) {
                return;
            }
            if (skipAfterResync()) return;
            measured = fllFeed(fll, fllAverage1, measured, reading.absStart(), reading.fftSize());
            fll.update(target, measured, reading.absStart(), writePos,
                    reading.sampleRate(), reading.fftSize());
            if (fll.getCorrection() != fllPublishedCorrection) {
                fllPublishedCorrection = fll.getCorrection();
                if (heldEmittedHz != null) heldEmittedHz[0] = target + fllPublishedCorrection;
                publishOnUiThread(Events.GENERATOR_FREQ_TRIM, target + fllPublishedCorrection);
            }
            boolean aligned = Math.abs(measured - target)
                    <= target * ALIGN_DONE_PPM * Constants.PPM;
            watchAlignmentMagnitudeDrift(reading.fundamentalDbFs(), Double.NaN, aligned);
        }
    }

    /** A re-sync's effect on the frequency lock: the reading the previous
     *  cycle kept is dropped - its window met the discontinuity - and the
     *  first tick that would feed the lock afterwards is skipped
     *  ({@link #fllResyncHold}). */
    private void dropFllFeed() {
        pendingFllReading = null;
        fllResyncHold     = true;
        clearFllAverages();
    }

    /** The reading one loop is fed: {@code measured} joins {@code average},
     *  the readings whose windows started before the span the loop's state
     *  allows drop out - one FFT length before this window while
     *  {@code loop} is tracking, {@link #FLL_START_AVERAGE_FRACTION} of one
     *  while it is starting - and the mean of the rest is fed.
     *
     *  @param absStart the reading's window start, absolute capture position */
    private double fllFeed(FrequencyAligner loop, ArrayDeque<FllSample> average, double measured,
                           long absStart, int fftSize) {
        average.addLast(new FllSample(absStart, measured));
        long span = loop.isTracking()
                ? (long) (FLL_AVERAGE_FRAMES - 1) * fftSize
                : Math.round(FLL_START_AVERAGE_FRACTION * fftSize);
        long oldestStart = absStart - span;
        while (average.peekFirst().absStart() < oldestStart) {
            average.removeFirst();
        }
        return meanHz(average);
    }

    /** The mean of {@code average}'s readings, Hz; NaN while it holds none. */
    private double meanHz(ArrayDeque<FllSample> average) {
        if (average.isEmpty()) return Double.NaN;
        double sum = 0.0;
        for (FllSample s : average) sum += s.hz();
        return sum / average.size();
    }

    /** Ends the tracking averages: the next tracking reading starts them
     *  afresh. */
    private void clearFllAverages() {
        fllAverage1.clear();
        fllAverage2.clear();
    }

    /** Stamps on {@code r} the frequencies the loops steer on while they
     *  track - each tracking loop's average - for the view's ΔF readout;
     *  NaN for a loop that is not tracking, where the readout shows the
     *  cycle's own measurement. */
    private void stampFllAverages(FftResult r) {
        r.fllAveragedHz  = (fll  != null && fll.isTracking())  ? meanHz(fllAverage1) : Double.NaN;
        r.fllAveraged2Hz = (fll2 != null && fll2.isTracking()) ? meanHz(fllAverage2) : Double.NaN;
    }

    /** Tests and clears {@link #fllResyncHold}: {@code true} on the one tick
     *  after a re-sync that it skips.  Asked only once every other gate of
     *  the tick has passed, so a tick that would not have fed a loop anyway
     *  does not use it up. */
    private boolean skipAfterResync() {
        if (!fllResyncHold) return false;
        fllResyncHold = false;
        return true;
    }

    /** One tone's frequency for the lock: the reference channel's measurement
     *  when this cycle produced one, else the analysed channel's.  Behind a
     *  notch the analysed tone is buried and its sub-bin estimate wanders,
     *  while the reference channel carries the same tone clear of it - so where
     *  a reference number exists it is the one the loop steers on.  Everything
     *  else in the feed - the plausibility gate, the trim publish, the aligned
     *  test - works on whichever number comes back. */
    private double loopMeasurement(double referenceHz, double analysedHz) {
        return Double.isFinite(referenceHz) ? referenceHz : analysedHz;
    }

    /** Magnitude-drift watch: baselines the fundamental level(s) on the
     *  first tick after a lock reset re-armed it, then - once the loop(s)
     *  report aligned - compares the current level(s) against that baseline
     *  ONE time.  A drift beyond {@link #ALIGN_DRIFT_DB} means the running
     *  average still contains pre-alignment frames at a depressed level; the
     *  view shows a blinking hint to reset statistics.  Levels in dBFS - the
     *  dBV offset cancels in the delta; {@code f2Db} is {@code NaN} in
     *  single-tone mode. */
    private void watchAlignmentMagnitudeDrift(double f1Db, double f2Db, boolean aligned) {
        if (!alignWatchArmed || Double.isNaN(f1Db)) return;
        if (!alignWatchBaselined) {
            alignWatchBaseF1Db  = f1Db;
            alignWatchBaseF2Db  = f2Db;
            alignWatchBaselined = true;
            return;
        }
        if (!aligned) return;                       // loop still pulling - keep waiting
        alignWatchArmed = false;                    // one-shot per re-arm
        double delta = Math.abs(f1Db - alignWatchBaseF1Db);
        if (!Double.isNaN(f2Db) && !Double.isNaN(alignWatchBaseF2Db)) {
            delta = Math.max(delta, Math.abs(f2Db - alignWatchBaseF2Db));
        }
        if (delta > ALIGN_DRIFT_DB) {
            publishOnUiThread(Events.FFT_ALIGN_MAG_DRIFT, delta);
        }
    }

    /** True when the measured frequency is close enough to the target to
     *  plausibly be the generator's own tone - clock drift is ppm-scale.
     *  A larger mismatch is a mis-measurement (a window still containing
     *  the OLD signal draining through the output buffer after a form /
     *  frequency switch, a harmonic mis-lock, a capture glitch); feeding
     *  it to the FLL published corrections like "19 kHz target − 1 kHz
     *  measured = +18 kHz" that trimmed the live generator to 37 kHz. */
    private boolean plausibleFllMeasurement(double targetHz, double measuredHz,
                                            int sampleRate, int fftSize) {
        double binHz = sampleRate / (double) fftSize;
        double maxErrHz = Math.max(FLL_MAX_ERROR_BINS * binHz,
                targetHz * FLL_MAX_ERROR_PPM * Constants.PPM);
        return Math.abs(measuredHz - targetHz) <= maxErrHz;
    }

    /** Qualifies the reference feed at an accumulator restart: the reference
     *  bin must stand {@link #PHASE_ALIGN_MIN_MARGIN_DB} over the median power
     *  of its neighbourhood (the fundamental's own lobe left out of that floor)
     *  and reach {@link #PHASE_ALIGN_MIN_LEVEL_DBFS}.  A feed that fails either
     *  test carries no fundamental to compare against - its phase is the phase
     *  of noise, and aligning to it would inject that noise into the average -
     *  so the collection runs unaligned, with the two numbers logged and, once
     *  per collection, handed to the pane for the operator.  The sweep costs
     *  one single-bin transform per neighbourhood bin, paid once per restart,
     *  never per tick.  A no-op in every other alignment mode, which keep no
     *  reference window - or keep it for the trace alone. */
    private void checkPhaseAlignStart(FftResult r, int halfSize) {
        phaseAlignRefused = false;
        if (refWinBuf == null || refMirrorForTrace || !winValid || r.freqResolution <= 0.0) return;
        int k0 = pinnedFundBin(halfSize);
        if (k0 <= 0) return;
        int lo = Math.max(0, k0 - PHASE_ALIGN_FLOOR_SPAN_BINS);
        int hi = Math.min(halfSize, k0 + PHASE_ALIGN_FLOOR_SPAN_BINS);
        double[] floor = new double[hi - lo + 1];
        int nFloor = 0;
        double refPow = 0.0;
        for (int k = lo; k <= hi; k++) {
            FftAnalyzer.BinPhasor p = analyzer.frameBinPhasor(refWinBuf, 0, r.fftSize, r.windowType, k);
            double pow = p.re() * p.re() + p.im() * p.im();
            if (k == k0) refPow = pow;
            if (Math.abs(k - k0) > PHASE_ALIGN_FLOOR_EXCLUDE_BINS) floor[nFloor++] = pow;
        }
        Arrays.sort(floor, 0, nFloor);
        double median   = nFloor > 0 ? floor[nFloor / 2] : 0.0;
        double marginDb = (refPow > 0.0 && median > 0.0)
                ? 10.0 * Math.log10(refPow / median) : -300.0;
        double conv     = r.amplitudePerMagnitude();
        double levelDbFs = (refPow > 0.0 && conv > 0.0)
                ? 20.0 * Math.log10(Math.sqrt(refPow) * conv) : -300.0;
        if (marginDb >= PHASE_ALIGN_MIN_MARGIN_DB && levelDbFs >= PHASE_ALIGN_MIN_LEVEL_DBFS) {
            return;
        }
        phaseAlignRefused = true;
        if (log.isWarnEnabled()) {
            log.warn("FFT phase alignment refused: the reference channel's bin {} is at {} dBFS, "
                    + "{} dB over its local floor (needs {} dBFS and {} dB) - averaging unaligned",
                    k0, String.format("%.1f", levelDbFs), String.format("%.1f", marginDb),
                    String.format("%.0f", PHASE_ALIGN_MIN_LEVEL_DBFS),
                    String.format("%.0f", PHASE_ALIGN_MIN_MARGIN_DB));
        }
        publishPhaseAlignRefusal(new PhaseAlignRefusal(r.fundamentalHzRefined, levelDbFs, marginDb));
    }

    /** Hands one refusal to the UI thread - the bus dispatches synchronously on
     *  the publishing thread, and the subscriber opens a dialog - and only the
     *  first one of a collection, so a run that restarts its accumulator does
     *  not raise the same dialog again. */
    private void publishPhaseAlignRefusal(PhaseAlignRefusal refusal) {
        if (phaseAlignRefusalPublished) return;
        phaseAlignRefusalPublished = true;
        publishOnUiThread(Events.FFT_PHASE_ALIGN_REFUSED, refusal);
    }

    /** Hands one message of the alignment to the UI thread: the bus
     *  dispatches synchronously on the publishing thread, and the
     *  subscribers of these messages open a dialog or - for the pilot the
     *  generator is asked for and released - announce a changed signal,
     *  whose own subscribers touch widgets.  Neither may happen on the
     *  worker thread.  Messages keep the order they were handed over in, so
     *  two pilot messages of one analysis cycle reach the generator in the
     *  order that cycle produced them.  Nothing is sent while no display is
     *  wired (the offscreen controller). */
    private void publishOnUiThread(String event, Object payload) {
        if (display == null || display.isDisposed()) return;
        display.asyncExec(() -> {
            try {
                MessageBus.instance().publish(event, payload);
            } catch (Exception e) {
                if (log.isErrorEnabled()) log.error("Can't dispatch {}", event, e);
            }
        });
        display.wake();
    }

    /** The alignment afresh, in every aligning mode: the reference channel's
     *  baseline is taken anew at the next tick and the pilot is elected again
     *  for the geometry in force.  What a collection start does, and what a
     *  change of the generated signal does in the middle of one - the tone
     *  that changed has a new phase through the notch, so nothing measured on
     *  the old tone may be applied to it.  Runs on the worker thread and, at a
     *  collection start, on the caller's. */
    private void restartAlignment() {
        restartPilot();
    }

    /** The pilot afresh for the collection that starts here: the first
     *  frame's reading, the excess and the collection's notices go, and the
     *  pilot is elected again for the geometry in force - the capture rate,
     *  the transform size, the fundamental's bin or the dual pair's bins, the
     *  window and the generator's playback rate can all have changed since
     *  the last collection, and the elected bin is relative to every one of
     *  them.  The elected pilot is asked of the generator every time, the same
     *  one included: the generator remembers no pilot of its own and answers
     *  the request without announcing a changed signal, so a repeated request
     *  starts nothing over. */
    private void restartPilot() {
        pilotFirstTaken     = false;
        pilotRefFirstTaken  = false;
        pilotRef2FirstTaken = false;
        pilotRefOffsetTaken = false;
        pilotRemoteWarned   = false;
        pilotNoLineWarned   = false;
        excessF1Rad         = 0.0;
        excessF2Rad         = 0.0;
        pendingPhiF2        = 0.0;
        pilotMissingFrames  = 0;
        pilotResyncPending  = false;
        resetPilotHistory();
        Preferences prefs = Preferences.instance();
        if (!pilotWanted(prefs.getFftFundamentalPhaseAlignment())) {
            clearPilot();
            return;
        }
        selectPilot(prefs);
    }

    /** Whether a mode asks the generator for the pilot: the pilot mode
     *  always; the reference-channel mode under the pilot trace switch,
     *  where the pilot is read as the control of the reference's reading. */
    private boolean pilotWanted(FundamentalPhaseAlignment mode) {
        return mode == FundamentalPhaseAlignment.PILOT_TONE
                || (mode == FundamentalPhaseAlignment.REFERENCE_CHANNEL
                        && DebugSwitches.TRACE_PILOT_PHASE);
    }

    /**
     * Elects the PAIR of pilots for the geometry in force and asks the
     * generator for both.  The band they are elected from ends at
     * {@link #PILOT_CEILING_HZ} and starts above the protected ladder, whose
     * highest ORDER is {@link #PILOT_PROTECTED_ORDER}, the ladder the THD
     * readout sums; no setting moves it.  The election also states the
     * clearance the pair
     * was admitted at, and the reader searches with those numbers and no
     * others.
     *
     * <p>The fundamental is taken from what the generator EMITS, never from a
     * peak search: the pilot passes no notch and arrives far above the
     * notched fundamental, so a search would take the pilot itself for the
     * fundamental.  A generator a server drives gets no pilot at all - the
     * pilot's controls exist on the local DDS alone - and the mode then
     * measures no excess.
     */
    private void selectPilot(Preferences prefs) {
        // Every exit below takes the pilot down: it runs only while one can
        // be elected for the configuration in force right now, and the next
        // restart asks again.
        SignalBufferReader rdr = reader;
        int sampleRate = rdr != null ? rdr.getSampleRate() : 0;
        int fftSize    = prefs.getFftLength();
        if (sampleRate <= 0 || fftSize < 8 || !isGeneratorActive()) {
            clearPilot();
            return;
        }
        MessageBus bus = MessageBus.instance();
        GeneratorOutput output = bus.request(Events.GENERATOR_OUTPUT);
        if (output != null && output.remote()) {
            if (!pilotRemoteWarned) {
                pilotRemoteWarned = true;
                if (log.isWarnEnabled()) {
                    log.warn("FFT phase alignment: pilot tone needs the local generator");
                }
            }
            clearPilot();
            return;
        }
        // A responder that answers no rate leaves the capture grid as the only
        // one known; the whole-period knock-out is then evaluated on it alone.
        double playbackRateHz = (output != null && output.rateHz() > 0.0)
                ? output.rateHz() : sampleRate;
        int[] fundamentalBins = emittedBins(prefs, sampleRate, fftSize);
        if (fundamentalBins == null) {
            clearPilot();
            return;
        }
        PilotToneSelector.PilotPair elected = new PilotToneSelector(analyzer, sampleRate,
                playbackRateHz, fftSize, fundamentalBins, PILOT_PROTECTED_ORDER,
                prefs.getFftWindow(), PILOT_CEILING_HZ).selectPair();
        if (elected.bin1() <= 0) {
            if (log.isWarnEnabled()) {
                log.warn("FFT phase alignment: no pilot fits this configuration - {}",
                        elected.reason());
            }
            clearPilot();
            return;
        }
        // One admissible pilot still aligns: the hop counter's own count of
        // whole turns stands in for the vernier the second line would give.
        boolean pair          = elected.bin2() > 0;
        pilotReader1          = new PilotReader(elected.bin1());
        pilotReader2          = pair ? new PilotReader(elected.bin2()) : null;
        pilotPair             = pair
                ? new PairAdvance(elected.bin1(), elected.bin2(), fftSize, sampleRate)
                : null;
        analyzer.setPilotBins(elected.bin1(), pair ? elected.bin2() : -1);
        double levelDbv = pilotLevelDbv;
        PilotToneRequest request = new PilotToneRequest(elected.hz1(),
                pair ? elected.hz2() : Double.NaN, levelDbv);
        if (pair && log.isInfoEnabled()) {
            log.info("FFT phase alignment: pilot tones at bins {} ({} Hz) and {} ({} Hz), {} dBV",
                    elected.bin1(), String.format("%.6f", elected.hz1()),
                    elected.bin2(), String.format("%.6f", elected.hz2()),
                    String.format("%.1f", levelDbv));
        } else if (!pair && log.isWarnEnabled()) {
            log.warn("FFT phase alignment: one pilot only, at bin {} ({} Hz), {} dBV - {}. "
                    + "A single line knows the advance between two frames modulo its own "
                    + "period alone, so the whole turns are taken from the hop counter and "
                    + "samples lost between two frames pass unnoticed",
                    elected.bin1(), String.format("%.6f", elected.hz1()),
                    String.format("%.1f", levelDbv), elected.reason());
        }
        publishOnUiThread(Events.GENERATOR_PILOT_ON, request);
    }

    /** The generator's emitted pair for this collection - {@link #heldEmittedHz},
     *  asked of the generator on the first call after a collection started and
     *  held from then on, so the pilot election and the analysis hints share
     *  ONE request whichever of them comes first.  Worker thread only. */
    private double[] emittedPair(int sampleRate) {
        if (emittedPairPending) {
            heldEmittedHz      = MessageBus.instance().request(Events.GENERATOR_EMITTED_HZ, sampleRate);
            emittedPairPending = false;
        }
        return heldEmittedHz;
    }

    /** The bins the protected ladder is built on: the tone the generator
     *  emits, and the upper one as well while a dual pair runs.  {@code null}
     *  while the generator states no tone to protect - a sweep, the noise
     *  forms, or no responder at all. */
    private int[] emittedBins(Preferences prefs, int sampleRate, int fftSize) {
        double[] emitted = emittedPair(sampleRate);
        if (emitted == null || !(emitted[0] > 0.0)) return null;
        double binHz = sampleRate / (double) fftSize;
        int    k1    = (int) Math.round(emitted[0] / binHz);
        if (k1 < 1) return null;
        if (!prefs.getGenSignalForm().isDualTone() || !(emitted[1] > 0.0)) {
            return new int[]{ k1 };
        }
        int k2 = (int) Math.round(emitted[1] / binHz);
        return k2 > 0 ? new int[]{ k1, k2 } : new int[]{ k1 };
    }

    /** Takes both pilots down: the generator is told once, the analyser stops
     *  holding bins that are no longer there, and the readers go with them, so
     *  a pilot coming back is read against a frame of its own instead of one
     *  read before the gap. */
    private void clearPilot() {
        pilotFirstTaken = false;
        pilotRawPhase1  = Double.NaN;
        pilotRawPhase2  = Double.NaN;
        pilotRawRef1    = Double.NaN;
        pilotRawRef2    = Double.NaN;
        resetPilotHistory();
        if (pilotReader1 == null) return;
        pilotReader1 = null;
        pilotReader2 = null;
        pilotPair    = null;
        analyzer.setPilotBins(-1, -1);
        publishOnUiThread(Events.GENERATOR_PILOT_OFF, null);
    }

    /** The FORK: the per-tick tracking correction (in samples) pooled from
     *  every strong line of the coherent family instead of the fundamental
     *  alone.  A common sample offset rotates each line proportionally to its
     *  frequency, so each line's accumulated-vs-fresh phase difference divided
     *  by its fractional bin is an independent estimate of the SAME offset;
     *  the estimates are averaged with weights {@code (fractional bin x
     *  magnitude)^2} - variance-optimal, since a line's phase noise is
     *  floor/|X| and its offset sensitivity grows with frequency.  Admission:
     *  a line joins when its fresh level is within the multi-tone threshold
     *  (Preferences, {@code fftStrongToneRelDb}) below the fundamental's -
     *  smaller threshold pools fewer lines - and the dual pair's F2 (the
     *  {@code (0,1)} grid product) always joins; a line within
     *  {@link #FORK_WRAP_GUARD_RAD} of the ±π wrap is dropped for the tick.
     *  Behind a notch the fundamental is the WEAKEST line, and the pooled
     *  reference noise falls with the combined power of the harmonics - the
     *  theory chapter's fork.  With nothing else admitted, returns the
     *  fundamental's own estimate unchanged. */
    private double forkPooledOffset(FftResult r, int halfSize, double delta, int k0,
                                    double fundOffset, double phiF2) {
        double kappa  = accumulator.getKappa();
        double kappa2 = accumulator.getKappa2();
        // The ladder below runs h while h·κ < halfSize: bounded by the family,
        // not by the spectrum, so a sub-bin κ has no ladder to pool - the
        // fundamental's own estimate stands.
        if (kappa < KAPPA_FAMILY_MIN_BIN) return fundOffset;
        double fundPow = r.re[k0] * r.re[k0] + r.im[k0] * r.im[k0];
        double relDb   = Preferences.instance().getFftStrongToneRelDb();
        double minPow  = fundPow * Math.pow(10.0, -relDb / 10.0);
        double sumW    = kappa * kappa * fundPow;
        double sumWOff = sumW * fundOffset;
        // The harmonic ladder h·κ, h = 2 and up.  No excess is taken out of
        // these: the accumulator turns the h = 1 rung alone, so the harmonics'
        // accumulated bins carry none either and the two sides agree.
        for (int h = 2; h * kappa < halfSize - 1.0; h++) {
            double[] est = forkLineOffset(r, halfSize, delta, h * kappa, minPow, false, 0.0);
            if (est != null) {
                sumW    += est[0];
                sumWOff += est[0] * est[1];
            }
        }
        // The published product grid a·κ1 + b·κ2 when a dual pair is pinned;
        // F2 - the (0,1) entry - is a fundamental and joins unconditionally.
        int[] pa = r.imdProductA;
        int[] pb = r.imdProductB;
        if (kappa2 > 0.0 && pa != null && pb != null) {
            for (int p = 0; p < pa.length; p++) {
                double kappaP = pa[p] * kappa + pb[p] * kappa2;
                if (kappaP < 1.0 || kappaP > halfSize - 1.0) continue;
                boolean exempt = pa[p] == 0 && pb[p] == 1;
                // F2's accumulated region carries -phiF2, so its fresh phasor
                // has to be read with the same excess taken out; without that
                // the whole excess would come back as a systematic residual
                // and be folded into the dropped-sample estimate.
                double[] est = forkLineOffset(r, halfSize, delta, kappaP, minPow, exempt,
                        exempt ? phiF2 : 0.0);
                if (est != null) {
                    sumW    += est[0];
                    sumWOff += est[0] * est[1];
                }
            }
        }
        return sumWOff / sumW;
    }

    /** One fork line's {@code [weight, offset-estimate]} - or {@code null}
     *  when the line is below the admission level (unless {@code exempt}),
     *  out of range, or too close to the phase wrap this tick.  The fresh
     *  phasor is de-rotated by the line's own family angle and by
     *  {@code excessRad}, the phase excess the accumulator turned that line's
     *  region by, exactly as the accumulator rotated its bin - so the two
     *  phases compare directly and the residual holds the sample offset
     *  alone. */
    private double[] forkLineOffset(FftResult r, int halfSize, double delta,
                                    double kappaL, double minPow, boolean exempt,
                                    double excessRad) {
        int k = (int) Math.round(kappaL);
        if (k <= 1 || k > halfSize) return null;
        double pow = r.re[k] * r.re[k] + r.im[k] * r.im[k];
        if (!exempt && pow < minPow) return null;
        int    fftSize = accumulator.getFftSize();
        double ang = -2.0 * Math.PI
                * Math.IEEEremainder(delta * kappaL, fftSize) / fftSize;
        double c   = Math.cos(ang), s = Math.sin(ang);
        double obs = Math.atan2(r.re[k] * s + r.im[k] * c,
                                r.re[k] * c - r.im[k] * s) - excessRad;
        double accumPhase = Math.atan2(accumulator.getSumIm()[k], accumulator.getSumRe()[k]);
        double res = Math.IEEEremainder(obs - accumPhase, 2.0 * Math.PI);
        if (Math.abs(res) > FORK_WRAP_GUARD_RAD) return null;
        return new double[]{ kappaL * kappaL * pow,
                             res * fftSize / (2.0 * Math.PI * kappaL) };
    }

    /**
     * States on the result the fundamental's phase AS THE DAC EMITTED IT, at
     * the accumulator's own time origin, for the predistortion loop - see
     * {@link FftResult#dacFundPhaseRad}.  Read off the spectrum the
     * accumulator was just written into, so it shares the origin of the
     * harmonic phasors captured right after it; a value taken on the fresh
     * frame would sit at that frame's origin instead, and the two differ by
     * the rotation at the fundamental over the whole advance since the
     * reference frame.
     *
     * <p>Mode PILOT_TONE: the two pilot lobes are held in the accumulator at
     * their reference-frame phases, they are spaced the fundamental's bin
     * apart, and the generator starts them phase-locked to the main tone -
     * so their difference IS the fundamental's phase at that origin, and it
     * never passed the notch.  A second tone gets the same reading scaled by
     * its bin over the fundamental's where that is a whole multiple; a dual
     * pair's second tone is left unset.
     *
     * <p>Mode REFERENCE_CHANNEL: the accumulated fundamental holds the
     * notch's phase as it stood on the reference frame, and the difference
     * between the analysed and the reference channel on that very frame is
     * that phase, measured - taking it out leaves the tone as emitted.
     *
     * <p>Stated in the convention a measured bin has, so it drops into the
     * loop's reference where the notched reading used to stand.
     */
    private void stampDacFundamentalPhase(FftResult r, FundamentalPhaseAlignment mode) {
        r.dacFundPhaseRad  = Double.NaN;
        r.dacFund2PhaseRad = Double.NaN;
        int halfSize = r.fftSize / 2;
        int k0 = pinnedFundBin(halfSize);
        double kappa2 = accumulator.getKappa2();
        int k1 = (kappa2 > 0.0) ? (int) Math.round(kappa2) : -1;
        if (k0 <= 0) return;
        if (mode == FundamentalPhaseAlignment.PILOT_TONE) {
            if (pilotPair == null || pilotReader1 == null || pilotReader2 == null) return;
            int p1 = pilotReader1.getBin();
            int p2 = pilotReader2.getBin();
            if (p1 <= 0 || p2 <= 0 || p1 > halfSize || p2 > halfSize) return;
            double atFund = Math.atan2(r.im[p1], r.re[p1]) - Math.atan2(r.im[p2], r.re[p2]);
            // A sine's bin reads a quarter turn behind its accumulator phase;
            // the pilots' difference has that quarter turn cancelled out.
            r.dacFundPhaseRad = wrapTurn(atFund - Math.PI / 2.0);
            if (k1 > 0 && k1 <= halfSize && k1 % k0 == 0) {
                r.dacFund2PhaseRad = wrapTurn(atFund * (k1 / k0) - Math.PI / 2.0);
            }
            return;
        }
        if (mode == FundamentalPhaseAlignment.REFERENCE_CHANNEL && pilotRefFirstTaken) {
            r.dacFundPhaseRad = wrapTurn(Math.atan2(r.im[k0], r.re[k0]) - pilotRefFirstRad);
            if (k1 > 0 && k1 <= halfSize && pilotRef2FirstTaken) {
                r.dacFund2PhaseRad = wrapTurn(Math.atan2(r.im[k1], r.re[k1]) - pilotRef2FirstRad);
            }
        }
    }

    // ─── External wiring ────────────────────────────────────────────────────

    /** Cumulative frame count accumulated across all forever-mode ticks
     *  since the last reset.  Returns 0 outside forever mode or before
     *  any contribution.  This is the meaningful "N average(s)" depth
     *  for forever mode - equal to the SNR-improvement factor √N². */
    public int  getAccumulatedFrames()          { return (int) Math.round(accumulator.getFrames()); }
    public boolean isPaused()                   { return paused.get(); }

    // ─── Lifecycle ──────────────────────────────────────────────────────────

    /** Combines the capture's two inputs into {@code master} - the other one
     *  folded into it as its slave - or separates them for {@code null}, on
     *  the reader held now; {@link #start()} combines the reader it acquires
     *  from the preference. */
    public void setCombinedMaster(Channel master) {
        SignalBufferReader rdr = reader;
        if (rdr == null) return;
        if (master != null) rdr.combine(master);
        else                rdr.separate();
    }

    /** Starts the analyser on a daemon worker thread.  Idempotent. */
    public synchronized void start() {
        if (running) return;
        // The worker owns its capture: acquire a SharedCapture reader DIRECTLY
        // (the bus is UI-only and carries no device traffic) so neither the
        // view nor the pane has to handle the sample
        // buffer.  A null result means the input device is unavailable - stay
        // stopped so the caller (which checks isRunning) can revert its Record
        // button.
        SignalBufferReader r = SharedCapture.instance().acquire();
        if (r == null) return;
        Channel combined = Preferences.instance().getFftCombinedMaster();
        if (combined != null) r.combine(combined);   // a fresh reader combines like the last one
        this.reader      = r;
        this.captureHeld = true;
        running = true;
        captureEndReason = null;   // fresh session - no terminal recorded yet
        // Re-arm the first-frame regime + drop any stale spectrum so the chart
        // starts blank.  reanchorPending makes the worker seek the reader to
        // "now" on its next tick, so the first analysis window is built from a
        // FULL needed-sample span of FRESH samples captured AFTER this point.
        firstFrameDone        = false;
        invalidateWindow();
        reAnchorPending       = true;
        completedAnalyses     = 0;
        averagesInWindow      = 0;
        resetStatistics();
        paused.set(false);
        // One refusal notice per collection, one pilot-lost notice per run,
        // and a readout cadence that starts with the collection.
        phaseAlignRefusalPublished = false;
        pilotLostPublished         = false;
        phaseAlignTicks            = 0;
        // A new collection needs its own baseline - a phase excess from a
        // collection that has ended describes a filter at a temperature this
        // one knows nothing about - and it gets one from the statistics reset
        // above, which the first tick consumes.  A restart armed before this
        // press belonged to the collection that ended and is dropped here.
        alignRestartPending.set(false);
        MessageBus bus = MessageBus.instance();
        bus.subscribe(Events.GENERATOR_SIGNAL_CHANGED, alignRestartOnGenChange);
        Preferences prefs0 = Preferences.instance();
        prefs0.fftFundamentalPhaseAlignmentProperty().addListener(alignModeChanged);
        pilotLevelDbv = prefs0.getFftPilotLevelDbv();
        pilotMarginDb = prefs0.getFftPilotMarginDb();
        prefs0.fftPilotLevelDbvProperty().addListener(pilotLevelChanged);
        prefs0.fftPilotMarginDbProperty().addListener(pilotMarginChanged);
        worker = new Thread(this::workerLoop, "fft-analyzer");
        worker.setDaemon(true);
        worker.start();
    }

    /** Stops the analyser and interrupts the worker.  Idempotent.  Joins the
     *  worker (it exits within one tick - the interrupt breaks any sleep) so
     *  the teardown below can't race a tick still in flight. */
    public synchronized void stop() {
        running = false;
        Thread w = worker;
        worker = null;
        if (w != null) {
            w.interrupt();
            try {
                w.join(STOP_JOIN_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        MessageBus bus = MessageBus.instance();
        bus.unsubscribe(Events.GENERATOR_SIGNAL_CHANGED, alignRestartOnGenChange);
        Preferences prefs0 = Preferences.instance();
        prefs0.fftFundamentalPhaseAlignmentProperty().removeListener(alignModeChanged);
        prefs0.fftPilotLevelDbvProperty().removeListener(pilotLevelChanged);
        prefs0.fftPilotMarginDbProperty().removeListener(pilotMarginChanged);
        // Nothing reads the pilot once the analysis is over, and the generator
        // keeps what it was asked for until it is told otherwise - so the
        // request is withdrawn here, after the join, where no tick can ask
        // for it again.
        clearPilot();
        // The lock goes with the capture: its estimate of the two converters'
        // drift describes a capture that is over, and a generator left at the
        // last trim would be measured against a fresh loop's first correction
        // on top of it.  After the join, and with the loops the worker's own,
        // this runs here rather than being asked for from outside.
        resetFrequencyLock();
        if (w == null || !w.isAlive()) {
            resetAccumulator();
            // Reclaim an undrained slot, then drop the pool - at fftSize
            // 4 M the slots idle on ~100 MB of spectrum arrays while
            // nothing is recording.  (No race with the drain runnable:
            // both run on the UI thread and both use getAndSet(null).)
            resultPool.release(latestForUi.getAndSet(null));
            resultPool.clear();
            // Big-buffer teardown: the sliding window, its per-tick copy, the
            // hop stage and the analyzer's scratch idle on several hundred MB
            // at fftSize 4 M while nothing records.  All rebuild on demand at
            // the next start (the join above guarantees no tick is in flight).
            winBuf     = new double[0];
            analyzeBuf = new double[0];
            hopBuf     = new double[0];
            refWinBuf  = null;      // the reference mirror is the same size again
            refHopBuf  = null;
            invalidateWindow();
            winNeeded  = -1;
            analyzer.releaseScratch();
        } else {
            // A monster-FFT tick outlived the join: leave its state alone and
            // let the next start's pending reset wipe it on the worker side.
            log.warn("FFT worker did not exit within 3 s - accumulator cleanup deferred to the next start.");
            resetPending.set(true);
        }
        reader = null;
        if (captureHeld) {
            // DIRECT, same rule as the acquire in start().
            SharedCapture.instance().release();
            captureHeld = false;
        }
    }

    /** Clears the completed-analyses counter, drops the retained
     *  spectrum, and resumes the loop if it was paused by stop-after-N.
     *  Safe to call from any thread.  Does NOT post the redraw - the
     *  caller does that. */
    public void resetStatistics() {
        resetEpoch.incrementAndGet();   // first: a mid-tick analysis must see it
        paused.set(false);
        completedAnalyses     = 0;
        averagesInWindow      = 0;
        firstFrameDone        = false;
        invalidateWindow();
        reAnchorPending       = true;
        if (running) {
            // Worker-owned state (accumulator, mains tracking) is wiped by the
            // worker itself at the next tick boundary - see resetPending.
            resetPending.set(true);
        } else {
            resetWorkerOwnedState();    // no worker thread to race with
        }
    }

    /** {@link #resetStatistics()} variant for resets caused by a change of
     *  the GENERATED SIGNAL (user input on the generator pane, generator
     *  start/stop): additionally arms the post-re-anchor drain skip so the
     *  first window holds none of the old tone still flowing out of the
     *  DAC's hardware buffer - see {@link FrequencyAligner#OUTPUT_DRAIN_SKIP_SEC}.
     *  Setting-only resets (window / channel / fftLength) use plain
     *  {@link #resetStatistics()}: the signal didn't change, so there is
     *  nothing to drain. */
    public void resetStatisticsAfterSignalChange() {
        drainSkipPending.set(true);     // before resetStatistics: its reanchorPending publishes this too
        resetStatistics();
    }

    /** The current collection's epoch, stamped on every result it produces:
     *  a result whose {@link FftResult#epoch} differs was measured before the
     *  latest reset.  The hand-off drops such a result itself - see
     *  {@link #drainPendingResult()}. */
    public long currentResetEpoch() {
        return resetEpoch.get();
    }

    /** Wipes the worker-owned, non-thread-safe analysis state.  Called only
     *  on the worker thread (via {@link #resetPending}) or while no worker
     *  is alive ({@link #resetStatistics} when stopped, {@link #stop} after
     *  the join). */
    private void resetWorkerOwnedState() {
        resetAccumulator();
        completedAnalyses = 0;          // re-zero: a racing worker-side ++ may have resurrected a stale count
        averagesInWindow  = 0;
        mainsTrackTick = 0;             // re-lock mains on the very next tick
        if (mainsComb != null) mainsComb.resetTracking();   // re-lock mains from scratch
    }

    // ─── Status queries ─────────────────────────────────────────────────────

    /** True when the audio generator is currently producing a signal.
     *  Resolved via {@link MessageBus} - the generator pane registers a
     *  responder for {@link Events#GENERATOR_RUNNING}.  Defaults to
     *  {@code true} when no responder is registered. */
    public boolean isGeneratorActive() {
        Boolean answer = MessageBus.instance().request(Events.GENERATOR_RUNNING);
        return answer == null || answer;
    }

    /** One emitted frequency as an analyzer HINT: {@code 0.0} is the emitted
     *  state's "this waveform has no such tone" (a sweep, the noise forms, the
     *  second tone of a single-tone signal), and pointing the search at DC is
     *  worse than not pointing it anywhere - so it becomes "no hint". */
    private double hintHz(double emittedHz) {
        return emittedHz > 0 ? emittedHz : Double.NaN;
    }

    /** Fraction (0..1) of the data needed for the *current* FFT frame
     *  that has already been captured.  Drives the "NN%" indicator next
     *  to the magnitude-unit combo. */
    public double getNextFrameProgress() {
        SignalBufferReader rdr = reader;
        if (rdr == null || !rdr.isAnchored()) return 0;
        Preferences prefs = Preferences.instance();
        int fftLength = prefs.getFftLength();
        if (fftLength < 8) return 0;
        long avail = rdr.available();
        // Overrun is reported as a NEGATIVE progress so the UI can tell it
        // apart from 0 (= simply no fresh samples captured yet).
        if (avail == SignalBufferReader.OVERRUN) return -1.0;
        // The hop actually being gathered - the worker's drawn pending hop, not
        // a recomputation from preferences (under the random overlap the two
        // differ every tick, and even for fixed modes the preference can change
        // ahead of the worker's next tick).
        double hop = Math.max(1, pendingHopSamples);
        // Building the first window needs a full `needed`; once it's valid each
        // tick just needs one fresh hop, so the bar sweeps 0->1 per hop.  A span
        // is gathered across ticks, so what already sits in the analyser's own
        // buffer counts as captured - otherwise a span longer than the ring
        // would read 0 % for its whole (minutes-long) collection.
        double want = winValid ? hop : (winNeeded > 0 ? winNeeded : fftLength);
        double f = (avail + (winValid ? hopFill : winFill)) / want;
        return (f < 0) ? 0 : (f > 1 ? 1 : f);
    }

    // ─── Worker loop ────────────────────────────────────────────────────────

    /** Worker-thread body.  Runs analysis on a background thread, posts
     *  the redraw via {@link Display#asyncExec} so the SWT thread isn't
     *  blocked by the FFT compute. */
    private void workerLoop() {
        while (running) {
            int sleepMs = IDLE_TICK_MS;
            awaitSamplesNext = 0;
            // A frequency-lock reset asked for since the last iteration, taken
            // before the paused test so a paused worker still performs it.
            if (frequencyLockResetPending.getAndSet(false)) {
                resetFrequencyLockState();
            }
            if (!paused.get()) {
                try {
                    sleepMs = doAnalysis();
                } catch (Throwable t) {
                    if (log.isWarnEnabled()) {
                        if (!(t.getCause() instanceof InterruptedException)) {
                            log.warn("FFT analysis tick failed", t);
                        }
                    }
                    sleepMs = IDLE_TICK_MS;
                    awaitSamplesNext = 0;
                }
            }
            if (sleepMs <= 0) sleepMs = IDLE_TICK_MS;
            if (sleepMs > MAX_SLEEP_MS) sleepMs = MAX_SLEEP_MS;
            // Data-driven wait: block on the ring for the samples the next
            // tick needs (the writer notifies per batch and on finish); the
            // ms value only CAPS the wait, so a stalled capture still ticks
            // the loop.  Time-driven wait (no count): plain sleep as before.
            int wantSamples = awaitSamplesNext;
            SignalBufferReader rdr = reader;
            try {
                if (wantSamples > 0 && rdr != null) {
                    rdr.awaitAvailable(wantSamples, sleepMs);
                } else {
                    Thread.sleep(sleepMs);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** Performs at most one analysis pass.  Returns the suggested sleep
     *  in milliseconds before the next attempt - derived from the
     *  configured overlap so the FFT update rate scales with the hop
     *  size. */
	private int doAnalysis() {
        startAnalyze = System.nanoTime();
        // Generator-state transition: wipe accumulated stats so the next
        // analysis starts with a clean slate.
        boolean genActiveNow = isGeneratorActive();
        if (lastGeneratorActive != null && lastGeneratorActive != genActiveNow) {
            resetStatisticsAfterSignalChange();
        }
        lastGeneratorActive = genActiveNow;
        // Consume a pending foreign reset (or the transition reset just
        // above): the accumulator / mains-tracking wipe runs HERE, on the
        // worker thread, so it can never race the accumulate loops.
        if (resetPending.getAndSet(false)) {
            resetWorkerOwnedState();
            // The average starts from zero here, so the alignment starts from
            // its own first frame again: the pilot is elected afresh for the
            // geometry in force and asked of the generator once more.
            restartAlignment();
            republishTrims();       // the loops keep their state; their next update re-sends the trim
            emittedPairPending = true;  // a collection starts: the generator states its tones once
            fllTargetHz  = 0.0;         // the loops' targets belong to the collection they steered
            fll2TargetHz = 0.0;
        }
        // Stamp AFTER the transition reset above so this tick doesn't
        // discard itself; see the epoch check below the analyze call.
        final long epochAtStart = resetEpoch.get();

        // Single per-tick snapshot: stop() nulls the field (and a quick
        // stop->start swaps in a NEW cursor) while a tick is in flight -
        // re-reading the field mid-tick would let an outgoing worker consume
        // from the new worker's cursor.
        final SignalBufferReader rdr = reader;
        if (rdr == null) return IDLE_TICK_MS;
        // Reader-terminal consult: the capture writer finished the ring buffer
        // from below (device lost / delivery stalled).  CLAIM the reason (the
        // buffer hands it to exactly one consumer - one dead device, one
        // message, however many panes were reading it), stop the loop, and
        // tell the pane through the same auto-stop seam that stop-after-N
        // uses - the pane pops Record (its stop() runs the full teardown) and
        // localizes the claimed reason, or stays silent when another pane won.
        if (rdr.isFinished()) {
            captureEndReason = rdr.takeFinishedReasonForReport();
            running = false;
            if (display != null && !display.isDisposed()) {
                display.asyncExec(() ->
                        MessageBus.instance().publish(Events.FFT_RECORDING_AUTO_STOPPED));
            }
            return IDLE_TICK_MS;
        }
        int sampleRate = rdr.getSampleRate();
        if (sampleRate <= 0) return IDLE_TICK_MS;

        Preferences prefs = Preferences.instance();
        int fftLength = prefs.getFftLength();
        if (fftLength < 8 || (fftLength & (fftLength - 1)) != 0) return IDLE_TICK_MS;

        double avgRaw = prefs.getFftAverages();
        boolean foreverMode = Double.isInfinite(avgRaw);
        int     ringN       = Math.max(1, (int) avgRaw);
        // Cross-tick accumulate whenever any averaging is requested.  Each tick
        // then FFTs ONE fresh frame; the running cross-tick accumulator
        // supplies the depth.  This makes a "200×" ring reach its full depth
        // (instead of being capped by what fits in the capture buffer) and
        // stops it re-FFTing ~40 frames every tick.
        boolean accumulate = foreverMode || ringN >= 2;
        // Frame-depth cap for the bounded (ring) accumulator: one frame per
        // tick, and the on-screen "N×" count is tick-based, so N frames.
        int targetFrames = foreverMode ? Integer.MAX_VALUE : ringN;

        WindowType window = prefs.getFftWindow();

        FftOverlap overlap = prefs.getFftOverlap();

        // Hop bounds from the mode itself: a fixed overlap has one hop (both
        // bounds coincide), the random mode spans its percent range.  The
        // window and the hop buffer are sized ONCE for the LARGEST possible
        // hop, so neither resizes while a drawn hop's gather is in flight (a
        // mid-gather resize would discard the partial fill and tear the
        // contiguous stream).
        int minHopSamples = overlap.minHopSamples(fftLength);
        int maxHopSamples = overlap.maxHopSamples(fftLength);
        // NOT bounded by the capture ring: the analysis span is the analyser's
        // own buffer, gathered from the ring across as many ticks as it takes.
        // Clamping it to the ring made the largest FFT lengths impossible - a
        // 4 M-point frame at 48 kS/s is 87 s of audio against a 22 s ring, so
        // the span demanded was the whole ring, and waiting for it guaranteed
        // the writer lapped the cursor (the permanent overrun at 4 M).
        int needed = fftLength;

        Channel channel = prefs.getFftChannel();
        boolean wantLeft = (channel == Channel.L);

        FundamentalPhaseAlignment alignMode = prefs.getFftFundamentalPhaseAlignment();
        // A generated signal that changed since the last tick starts the
        // alignment afresh, before this tick reads its first sample.
        if (alignRestartPending.getAndSet(false)) {
            restartAlignment();
            republishTrims();       // the loops keep their state; their next update re-sends the trim
            emittedPairPending = true;  // the signal changed: the generator states its tones once more
        }
        // The pilot lives only while the mode asks for it and the generator
        // plays.  A mode left behind and a tone taken down are both silent
        // events for the alignment - nothing restarts it - so the tick
        // itself takes the pilot back off the generator.
        if (!pilotWanted(alignMode) || !genActiveNow) {
            clearPilot();
        }

        // Re-anchor on (re)start / reset so the sliding window is rebuilt only
        // from FRESH samples captured after this point (no ghosting after the
        // generator stops).  Done on the worker thread to keep the cursor's
        // read position single-owner even though resets arrive from others.
        if (reAnchorPending) {
            rdr.seekToLatest();
            reAnchorPending = false;
            invalidateWindow();
            dropFllFeed();
            if (drainSkipPending.getAndSet(false)) {
                drainSkipRemaining = (long) Math.ceil(FrequencyAligner.OUTPUT_DRAIN_SKIP_SEC * sampleRate);
            }
        }
        // Output-drain skip: after a signal-change re-anchor, consume and
        // discard the span still carrying the old tone (the DAC buffer
        // keeps it flowing into the ADC after the change) so the first
        // window sees only the new signal - as does every window the
        // frequency lock is fed, which skips that first one besides (see
        // fllResyncHold).  Also armed (5 ms) by a discontinuity re-sync, so
        // the rebuild starts past the glitch's END, not at its detected start.
        if (drainSkipRemaining > 0) {
            long skipAvail = rdr.available();
            if (skipAvail > 0) {
                int got = rdr.read((int) Math.min(skipAvail, drainSkipRemaining), (double[]) null, (double[]) null);
                if (got > 0) drainSkipRemaining -= got;
            }
            if (drainSkipRemaining > 0) {
                return awaitFor((int) Math.min(drainSkipRemaining, Integer.MAX_VALUE), sampleRate);
            }
        }
        // A change in window size (fftLength) or channel forces a rebuild from
        // a fresh contiguous span, and so does a change of the overlap mode
        // (see winOverlap).  The accumulator keeps running: the de-rotation
        // rides absolute positions, the rebuild costs one window of coverage.
        boolean sizeOrChannelChanged = winNeeded != needed || winChannelLeft != wantLeft;
        if (sizeOrChannelChanged || winOverlap != overlap) {
            invalidateWindow();     // incl. the partial fill: it is the wrong size / channel / hop now
            winNeeded      = needed;
            winChannelLeft = wantLeft;
            winOverlap     = overlap;
            if (winBuf.length != needed) winBuf = new double[needed];
            if (sizeOrChannelChanged) {
                republishTrims();   // an FFT length change re-snaps the generator, dropping its trim
            }
        }
        // The reference channel's mirror of the same span exists only while the
        // mode asks for it - the reference-channel alignment, or the pilot's
        // trace of both its bins against it; dropping it when none
        // asks is what makes a mode coming back on rebuild the window instead
        // of resuming one whose reference half was not being filled.  In the
        // pilot mode the mirror serves the trace alone - the excess is read
        // on the analysed channel there.
        boolean refForAlignment = alignMode == FundamentalPhaseAlignment.REFERENCE_CHANNEL;
        boolean refForTrace     = alignMode == FundamentalPhaseAlignment.PILOT_TONE
                && DebugSwitches.TRACE_PILOT_PHASE;
        refMirrorForTrace = refForTrace && !refForAlignment;
        boolean refWanted = refForAlignment || refForTrace;
        if (!refWanted) {
            refWinBuf = null;
            refHopBuf = null;
        } else if (refWinBuf == null || refWinBuf.length != needed) {
            invalidateWindow();
            refWinBuf = new double[needed];
        }

        long avail = rdr.available();
        if (avail == SignalBufferReader.OVERRUN) {
            // The writer lapped the cursor - the contiguous stream tore (the
            // worker fell a full ring behind).  Drop the cross-tick accumulator
            // + counters, invalidate the window, and re-anchor: deep averaging
            // restarts from a fresh unbroken span (overlap can only resume once
            // there are no breaks).  A reset the user can SEE - not a silent
            // torn-read glitch.
            onCaptureOverrun(rdr);
            return IDLE_TICK_MS;
        }

        long samplesAbsStart;
        if (!winValid) {
            // Build the first window by GATHERING the `needed`-sample span into
            // winBuf over as many ticks as it takes - the span can be longer
            // than the ring, and every tick's share is consumed as it arrives,
            // so the cursor stays right behind the writer instead of standing
            // still and being lapped.
            winFill = gather(rdr, winBuf, refWinBuf, winFill, needed, wantLeft, avail);
            if (winFill == SignalBufferReader.OVERRUN) { onCaptureOverrun(rdr); return IDLE_TICK_MS; }
            if (winFill < needed) return awaitFor(needed - winFill, sampleRate);
            winLen      = needed;
            winAbsStart = rdr.getReadPos() - winLen;
            winValid    = true;
            winFill     = 0;
            // First hop of a fresh window: the lattice cycle starts over, and
            // the value is the spacing this tick's frame pair is analysed at
            // and the amount the next slide consumes.
            hopStep = 0;
            pendingHopSamples = nextHop(minHopSamples, maxHopSamples);
        } else {
            // Slide forward exactly one hop: pull the fresh hop, drop the oldest
            // hop, append it.  The de-rotation rides each tick's ABSOLUTE
            // sample position, so a per-tick VARIABLE hop stays exact - the
            // stream just has to remain gap-free, which is why the pending hop
            // is frozen until its gather completes.  The hop is gathered the
            // same way as the first window: at zero overlap it is as long as
            // the frame itself, hence longer than the ring at the large FFT
            // lengths.
            int slideHop = pendingHopSamples;
            if (hopBuf.length != maxHopSamples) {
                // Capacity follows the CONFIGURATION (size / mode / range), never
                // the drawn hop - reallocation cannot strike mid-gather.
                hopBuf  = new double[maxHopSamples];
                hopFill = 0;
            }
            if (refWanted && (refHopBuf == null || refHopBuf.length != maxHopSamples)) {
                // Only ever reached with nothing gathered yet: the mode turning
                // on drops the reference window above, which restarts the whole
                // span, and a changed hop capacity changes `needed` with it.
                refHopBuf = new double[maxHopSamples];
            }
            hopFill = gather(rdr, hopBuf, refHopBuf, hopFill, slideHop, wantLeft, avail);
            if (hopFill == SignalBufferReader.OVERRUN) { onCaptureOverrun(rdr); return IDLE_TICK_MS; }
            if (hopFill < slideHop) return awaitFor(slideHop - hopFill, sampleRate);
            System.arraycopy(winBuf, slideHop, winBuf, 0, winLen - slideHop);
            System.arraycopy(hopBuf, 0, winBuf, winLen - slideHop, slideHop);
            if (refWinBuf != null && refHopBuf != null && refWinBuf.length == winLen) {
                System.arraycopy(refWinBuf, slideHop, refWinBuf, 0, winLen - slideHop);
                System.arraycopy(refHopBuf, 0, refWinBuf, winLen - slideHop, slideHop);
            }
            winAbsStart += slideHop;
            hopFill = 0;
            // The slide is complete - take the NEXT hop.  It is the spacing
            // this tick's analysis states for its frame pair, so the second
            // frame lands exactly where the next tick's first frame will.
            pendingHopSamples = nextHop(minHopSamples, maxHopSamples);
        }
        samplesAbsStart = winAbsStart;

        // Per-tick analysis copy: the comb / FFT window mutate the samples in
        // place, so the retained sliding window must NOT be handed to analyze.
        if (analyzeBuf.length != winLen) analyzeBuf = new double[winLen];
        System.arraycopy(winBuf, 0, analyzeBuf, 0, winLen);

        // Any change to fftLength / window / channel / sampleRate makes the
        // cross-tick accumulator meaningless (a different geometry or signal
        // under the cumulative spectrum), so it restarts.
        // The mains-suppression mode is NOT one of them - a change of mode is a
        // change of what the samples are, not of the spectrum's geometry, so it
        // is handled on its own below and keeps the collected depth.
        MainsSuppression mainsMode = prefs.getFftMainsSuppression();
        boolean mainsSuppress   = mainsMode == MainsSuppression.IIR_COMB;          // plot-time comb
        boolean mainsTimeDomain = mainsMode == MainsSuppression.SYNC_SUBTRACT
                               || mainsMode == MainsSuppression.LMS;                // pre-FFT filter
        long cfgFingerprint = fftLength;
        cfgFingerprint = 31 * cfgFingerprint + sampleRate;
        cfgFingerprint = 31 * cfgFingerprint + window.ordinal();
        cfgFingerprint = 31 * cfgFingerprint + (wantLeft ? 1 : 0);
        if (cfgFingerprint != accumConfigFingerprint) {
            resetAccumulator();
            accumConfigFingerprint = cfgFingerprint;
        }
        // A change of the pre-FFT filter TYPE restarts the window gather.  None
        // and IIR comb share no pre-FFT filter (the comb is a plot-time
        // correction), so a switch between those two changes nothing here; any
        // transition into, out of or between Sync subtract and LMS does: the
        // retained window is dropped and rebuilt from the reader's current
        // position.
        //
        // What that buys is one window of coverage discarded, and no more.  It
        // does NOT prevent a mixed frame: every tick copies the pristine retained
        // window into analyzeBuf and filters the WHOLE copy in one
        // processPreservingDc call, so no FFT frame can straddle the switch with
        // or without the restart.  Nor does it train the new filter, which
        // mainsTimeFilter rebuilds untrained on a mode change: the gather ticks
        // return at the incomplete-window wait above, before this block runs, so
        // the first analysed tick after the switch is the first one to train it.
        //
        // The accumulator is deliberately left untouched.  Pre-switch and
        // post-switch frames go on averaging together, and the averages counter
        // stays consistent with them because nothing resets.  The rebuild needs
        // no phase re-anchor: it takes the first-window path, which derives
        // winAbsStart from the reader's own position, and the de-rotation rides
        // those absolute positions, so the discarded span costs coverage but
        // never displaces a frame.  No operator-facing re-sync banner belongs
        // here either - a setting was changed, nothing went wrong.
        boolean lastTimeDomain = lastMainsMode == MainsSuppression.SYNC_SUBTRACT
                              || lastMainsMode == MainsSuppression.LMS;
        if (lastMainsMode != null && lastMainsMode != mainsMode && (mainsTimeDomain || lastTimeDomain)) {
            invalidateWindow();
        }
        lastMainsMode = mainsMode;

        // The pristine sliding window was copied into analyzeBuf above; the
        // comb / FFT window mutate this copy in place, never winBuf.
        double[] samples = analyzeBuf;

        int    calcMaxH = Math.max(MIN_CALC_MAX_HARMONIC, prefs.getFftCalcMaxHarmonic()) - 1;
        // "Max harmonic for THD" names the highest harmonic the sum takes; the
        // analyser counts harmonics from the 2nd, so H(N) is N - 1 of them.
        int    thdMaxH  = Math.max(1, prefs.getFftThdMaxHarmonic() - 1);
        double distMin  = prefs.isFftDistMinEnabled() ? prefs.getFftDistMinHz() : 0;
        double distMax  = prefs.isFftDistMaxEnabled() ? prefs.getFftDistMaxHz() : 0;
        boolean coherent = prefs.isFftCoherentAveraging();
        boolean genActive = isGeneratorActive();
        // Manual fundamental is a user-DECLARED true level - it applies to ANY source
        // (external amplifier as much as our own generator), so it must NOT be gated on
        // genActive; resolveFundRefDbFs already returns NaN unless the user enabled it.
        // (genActive still gates the generator FREQUENCY hints below, which do need it.)
        double fundRefDbFs = resolveFundRefDbFs(prefs);
        // The coherent de-rotation locks onto the detected fundamental, so
        // in dual-tone the hint must point at a real tone (the lower one).
        // Hinting the single-tone frequency - usually nowhere near F1/F2 -
        // pegs the reference to a noise bin that jitters tick-to-tick, which
        // smears BOTH tones and makes the measured frequencies (and Δf
        // readout) swing wildly even though the generator never moves.
        boolean dualTone = prefs.getGenSignalForm().isDualTone();
        // Which tones the generator is EMITTING - asked of the engine that owns
        // it (Events.GENERATOR_EMITTED_HZ) rather than re-derived from these
        // preferences: a bench a network away snapped against ITS lane's rate
        // and holds the frequency-lock trims, so a locally recomputed hint
        // points between bins and the de-rotation locks onto the wrong one.
        // Asked ONCE per collection (see emittedPair): a changed signal resets
        // the collection, and the lock's own trims are written back into the
        // held pair as they are published.  The fund-from-generator preference
        // is tested every cycle, where the hints are formed: it can change
        // within a collection, and the hints follow it off and on again on the
        // pair already held.  No generator, no pair: the transition to a
        // running one resets the collection and asks again.
        if (!genActive) heldEmittedHz = null;
        double[] emitted = (genActive && prefs.isFftFundFromGenerator())
                ? emittedPair(sampleRate) : null;
        double expectedFundHz = emitted == null ? Double.NaN
                : hintHz(dualTone ? Math.min(emitted[0], emitted[1]) : emitted[0]);

        // Mains suppression: only TRACK the live mains fundamental here (a
        // second of samples gives a mHz-accurate estimate) so the comb stays
        // locked to 50/60 Hz.  The signal is deliberately NOT filtered in the
        // time domain - combing each frame convolves the comb's transient/phase
        // into it, which the coherent average can't undo (drift, worst when a
        // mains harmonic sits on a measured tone).  Instead the comb's
        // normalized frequency response is divided out of the AVERAGED spectrum
        // at plot time (applyMainsCorrection), leaving the accumulator raw -
        // like the .frc cal, but independent of it.  Input stays pristine, so
        // the per-frame cache works normally.
        if (mainsSuppress) {
            // Re-track every Nth tick only - see MAINS_TRACK_TICK_INTERVAL.
            if (mainsTrackTick++ % MAINS_TRACK_TICK_INTERVAL == 0) {
                mainsComb(sampleRate).track(samples, Math.min(samples.length, sampleRate));
            }
        } else if (mainsTimeDomain) {
            // Synchronous-subtraction / LMS: remove the hum from the captured
            // window IN PLACE before the FFT.  Both leave the test tone's
            // amplitude and phase intact (they subtract only the additive hum),
            // so the coherent de-rotation / average is undisturbed.  The window
            // is filtered in double precision to preserve the measurement floor.
            MainsTimeFilter mf = mainsTimeFilter(mainsMode, sampleRate);
            if (mainsTrackTick++ % MAINS_TRACK_TICK_INTERVAL == 0) {
                mf.track(samples, Math.min(samples.length, sampleRate));
            }
            mf.processPreservingDc(samples, samples.length, samplesAbsStart);
        }
        // Dual-tone: hint the upper tone too, so the analyzer produces an
        // honest sub-bin frequency estimate for it from the frame - the
        // dual-tone readout then reports true frequencies instead of reading
        // peaks off the coherently-collapsed average (see ImdAnalyzer).
        analyzer.setSecondToneHintHz(
                (emitted != null && dualTone) ? hintHz(Math.max(emitted[0], emitted[1]))
                        : Double.NaN);
        // Dual tone: the second tone is searched for when no hint names it.
        analyzer.setMultiTone(dualTone);
        // While cross-tick averaging, the single-tick THD / SNR / noise-floor
        // stats are discarded and re-derived by recomputeStats() on the
        // accumulated spectrum below - so tell analyze to skip those sweeps
        // (incl. the two O(N log N) noise sorts) and only produce the spectrum
        // + fundamental/harmonic bins.  Single-tick mode keeps the full stats.
        analyzer.setSpectrumOnly(accumulate);
        FftResult slot = resultPool.acquire();
        FftResult r;
        try {
            // The one frame at the window's start; the stated spacing is the
            // drawn pending hop, which the statistics read the overlap from.
            r = analyzer.analyze(samples, sampleRate, fftLength, calcMaxH, thdMaxH,
                    window, overlap, distMin, distMax, coherent, fundRefDbFs,
                    false, expectedFundHz, slot, pendingHopSamples);
        } catch (RuntimeException ex) {
            // Pool hygiene only - the failure itself must surface: the worker
            // loop's Throwable catch logs it, so a persistently throwing
            // analysis is visible in a WARN-level log instead of ticking
            // forever in silence.
            resultPool.release(slot);
            throw ex;
        }
        // Pooled slot: clear the conditionally-written debug fields so a tick
        // that doesn't reach their assignment below can't republish a
        // previous tick's snapshots.
        r.gates         = null;
        r.gateBlockDbFs = null;
        // A signal change (resetStatistics) landed while this window was
        // being assembled / analyzed - it straddles the old and the new
        // signal.  Discard it: neither accumulate (it would poison the
        // freshly cleared average) nor publish (its spectrum is the old
        // signal's, stamped with the epoch the reset just ended).  Returning
        // here also keeps it away from the frequency-lock step further down.
        // The reset's pending re-anchor rebuilds from post-change samples.
        if (epochAtStart != resetEpoch.get()) {
            if (log.isInfoEnabled()) {
                log.info("Signal changed mid-analysis - straddling window discarded");
            }
            resultPool.release(r);
            return IDLE_TICK_MS;
        }
        // The dBFS->dBV offset is the analysed channel's ADC calibration, applied
        // at display time - nothing to anchor per result here; dBFS is the
        // result's only base scale.
        // Mode-transition detection - reset the cross-tick average only when the
        // new bound discards collected depth: the averaging mode flips
        // (idle ↔ averaging), a ring ↔ infinite switch, or a SMALLER ring.  A
        // LARGER ring keeps the depth (the exponential window just widens).
        // Forever is the explicit flag, not a MAX_VALUE sentinel.
        boolean discard = accumulate != lastAccumulate
                || foreverMode != lastForeverMode               // ring ↔ infinite
                || (!foreverMode && ringN < lastRingN);          // smaller ring
        if (discard) {
            resetAccumulator();
            completedAnalyses = 0;
            averagesInWindow  = 0;
            firstFrameDone    = false;
        }
        lastAccumulate  = accumulate;
        lastForeverMode = foreverMode;
        lastRingN       = ringN;
        // Cross-tick accumulation: each tick contributes its (small) per-tick
        // frames to a running accumulator (coherently rotated to align with
        // the accumulator's time reference), then the displayed Result is
        // rebuilt from the running average and the harmonic / THD / SNR stats
        // are re-derived via recomputeStats.  Forever mode is a true cumulative
        // mean (SNR boost √(total frames)); a finite ring N is the same
        // accumulator bounded to N frames (an exponential window), so it also
        // reaches √N depth regardless of the capture-buffer size.
        // Frequency-domain glitch / stall rejection: compare this tick's
        // spectrum to the running-median reference and re-sync (like an
        // overrun - re-anchor past the glitched overlap) before it can poison
        // the cross-tick vector average.  Only while accumulating; the
        // detector reconfigures itself on an fftLength change and reuses the
        // existing discontinuity recovery + banner.
        // Time-domain discontinuity gate first (cheap O(n) pass on the raw
        // window): the tick's own refined fundamental pins the recurrence
        // prediction exactly, so the reject threshold rides on the noise
        // floor at any signal frequency; NaN (no tone) self-estimates.
        if (USE_TIME_DISCONTINUITY && accumulate && prefs.isFftDetectTimeDiscontinuity()) {
            double f0 = r.fundamentalHzRefined;
            double omega = (f0 > 0 && f0 < sampleRate / 2.0)
                    ? 2.0 * Math.PI * f0 / sampleRate : Double.NaN;
            if (timeDetector.detect(samples, samples.length, omega)) {
                if (log.isInfoEnabled()) {
                    log.info("Time-domain discontinuity in the tick window - re-sync");
                }
                subtractPreviousTicks();
                onSignalDiscontinuity(rdr);
                resultPool.release(r);
                return IDLE_TICK_MS;
            }
        }
        if (USE_SPECTRAL_DISCONTINUITY && accumulate) {
            spectralDetector.configure(fftLength / 2);
            // Debug: snapshot this block's pre-average spectrum BEFORE the
            // accumulator overlay (below) overwrites r.amplitudeDbFs.
            double[] dbgBlock = DebugSwitches.SHOW_DISCONTINUITY_GATES ? r.amplitudeDbFs.clone() : null;
            if (spectralDetector.reject(r.re, r.im, fftLength / 2, sampleRate / (double) fftLength, fundamentalBins(r))) {
                if (dbgBlock != null) {                                  // hold the rejected block + verdict
                    lastRejectBlockDbFs = dbgBlock;
                    lastRejectGates     = spectralDetector.gates();
                }
                subtractPreviousTicks();
                onSignalDiscontinuity(rdr);
                resultPool.release(r);
                return IDLE_TICK_MS;
            }
            r.gates         = spectralDetector.gates();   // debug overlay snapshot
            r.gateBlockDbFs = dbgBlock;                    // current accepted block (pre-average)
        }
        // Second epoch gate: the detector pass above takes tens of ms at large
        // fftSize - a reset landing there must still keep this straddling
        // window OUT of the freshly cleared accumulator.
        if (epochAtStart != resetEpoch.get()) {
            if (log.isInfoEnabled()) {
                log.info("Signal changed mid-analysis - straddling window discarded before accumulation");
            }
            resultPool.release(r);
            return IDLE_TICK_MS;
        }
        // The tone frequencies for the frequency lock, read from the phase
        // step between this cycle's frame and the previous one on both
        // channels - every analysed cycle, whether or not this one is
        // averaged: the lock runs with the averaging off or at one too, and
        // those are settings in which it would otherwise keep steering on the
        // notched channel.
        measureAnalysedFrequencies(r);
        measureReferenceFrequencies(r, coherent);
        // Fundamental phase alignment: this tick's phase excess, measured after
        // the gates (a tick that never reaches the accumulator needs none) and
        // before the accumulator, which consumes it at its two points.  Mode
        // REFERENCE_CHANNEL measures it per tick against the opposite channel.
        phaseAlignUnsettled = false;
        if (accumulate) {
            pendingPhiF = tickPhiF(alignMode, r, coherent);
        } else {
            pendingPhiF  = 0.0;
            pendingPhiF2 = 0.0;
        }
        // Two frames in a row without a pilot reading are a gap in the stream
        // as far as this path can tell, so the frame goes the way a detected
        // discontinuity's does: discarded whole, the window re-anchored, no
        // result published - which is also what leaves the warning banner
        // standing until the reloaded window produces one.  The frames before
        // it were sound or bridged, so nothing is taken back out of the
        // accumulator.
        if (pilotResyncPending) {
            pilotResyncPending = false;
            if (log.isWarnEnabled()) {
                log.warn("FFT phase alignment: two frames in a row without a pilot line - "
                        + "window discarded and the capture re-synced through the discontinuity path");
            }
            onSignalDiscontinuity(rdr);
            resultPool.release(r);
            return IDLE_TICK_MS;
        }
        // A tick whose alignment has settled nothing is read and published but
        // never averaged: the drift it carries is the last settled one, held
        // while the notch moves on, so accumulating it would fold a stale
        // rotation into every line.
        boolean accumulated = accumulate && !phaseAlignUnsettled
                && accumulateIntoForeverBuffer(r, samplesAbsStart, coherent, targetFrames);

        // The generator frequency lock, on every tick that got here - settled
        // or not: each passed both discontinuity gates on a full post-seek
        // window, and the lock's reading is the reference channel's tone,
        // which the pair's settling does not touch.  A lock that stood
        // still while the pair was unsettled left the generator wherever a
        // false reading had put it, with nothing to bring it back.  Ahead of
        // the unsettled return and the display throttle, so neither an
        // unsettled tick nor a tick the display skips starves the loop.
        applyFrequencyLock(r, alignMode, samplesAbsStart, rdr.getWritePos(), epochAtStart);
        stampFllAverages(r);

        // The pilot tracking's one-shot re-zero: armed the first time the
        // lock reports tracking, executed by the next settled pilot frame.
        if (!pilotRetrackDone && fll != null && fll.isTracking()) {
            pilotRetrackDone    = true;
            pilotRetrackPending = true;
        }

        // A tick the alignment has not settled is analysed but not averaged, so
        // it must not reach the view either: drawn among averaged frames it
        // shows one frame's noise floor, which reads as the average collapsing.
        // The tick goes the way a re-sync tick goes - nothing published - and
        // the banner raised here stands until a published frame clears it,
        // which is the first frame that was averaged again.
        if (phaseAlignUnsettled) {
            if (!phaseAlignBannerUp) {
                phaseAlignBannerUp = true;
                publishCaptureBanner(PHASE_ALIGNING_BANNER);
            }
            resultPool.release(r);
            return awaitFor(pendingHopSamples, sampleRate);
        }
        phaseAlignBannerUp = false;

        // Per-tick bookkeeping runs EVERY tick, even on ticks whose display we
        // skip below.  Neither counter counts the collection's first cycle:
        // that one seeds the accumulator instead of averaging into it.
        if (firstFrameDone) {
            completedAnalyses++;
            if (foreverMode || averagesInWindow < ringN) averagesInWindow++;
        }
        firstFrameDone = true;
        // Stop-after-N: forever mode + cap enabled.  The bound is that same
        // depth.  The cross-tick accumulator deepens past 2 frames.
        if (foreverMode
                && prefs.isFftStopAfterNEnabled()
                && completedAnalyses >= prefs.getFftStopAfterN()) {
            pauseAndAnnounceAutoStop();
        }

        // Per-tick TRIM: the accumulator above ran this tick, but the O(N) display
        // rebuild below (overlay + recomputeStats + dBV lift + deep-copy publish)
        // does NOT need to.  Skip it while the capture backlog is high - catch up
        // rather than overrun - throttle it to DISPLAY_MIN_NANOS when caught up,
        // but force it by DISPLAY_MAX_NANOS so the view never freezes.
        long backlog   = rdr.getWritePos() - rdr.getReadPos();
        long sinceShow = System.nanoTime() - lastShowNanos;
        boolean showNow = paused.get()
                || sinceShow >= DISPLAY_MAX_NANOS
                || (sinceShow >= DISPLAY_MIN_NANOS && backlog <= 2L * needed);
        if (!showNow) {
            resultPool.release(r);                         // accumulated; defer the rebuild
            return awaitFor(pendingHopSamples, sampleRate);
        }
        lastShowNanos = System.nanoTime();

        if (accumulated) {
            accumulator.overlayOnto(r);         // r.amplitudeDbFs <- RAW cumulative average
            stampDacFundamentalPhase(r, alignMode);   // on the SAME spectrum the peaks come from
            r.captureRawPeaks();                // refresh raw F + peak phasors off the averaged re/im
        }
        // The whole post-average pipeline - mains rejection -> recomputeStats ->
        // .frc calibration -> dBV lift - now runs in the UI (FftView), ONCE per
        // DISPLAYED frame (after the display throttle + the coalescing handoff),
        // so it's not redone on coalesced frames and mains is applied at plot
        // time.  Here we only hand over the RAW averaged spectrum plus
        // the state that pipeline needs: tracked mains f0 (NaN => off), the pinned
        // coherent κ for the cal "before" dots (NaN => single tick), the channel.
        r.mainsF0Hz     = mainsSuppress ? mainsComb(sampleRate).getMainsHz() : Double.NaN;
        r.coherentKappa = accumulated
                ? (accumulator.isStarted() ? accumulator.getKappa()
                                : (r.freqResolution > 0 ? r.fundamentalHzRefined / r.freqResolution : 0.0))
                : Double.NaN;
        r.channelLeft      = wantLeft;
        r.samplesAbsStart  = samplesAbsStart;   // the window's absolute start
        r.epoch            = epochAtStart;      // consumers drop frames from a pre-reset epoch
        r.gateRejectDbFs   = lastRejectBlockDbFs;   // debug: last gate-rejected block + verdict
        r.gateRejectGates  = lastRejectGates;

        // Hand off to the UI.  No copy needed: analyze() returns a FRESH
        // FftResult each tick (the worker never mutates a handed-off {@code r}),
        // and the view deep-copies on receipt.  publishResult coalesces, so a
        // slow repaint can't pile spectra up on the asyncExec queue.
        if (log.isWarnEnabled() && DebugSwitches.SHOW_FFT_ANALYZE_TIME) {
            log.warn("1. FFT analyze took {} ms",
                    String.format("%.2f", (System.nanoTime() - startAnalyze) / 1_000_000.0));
        }
        // Final epoch gate before the hand-off: a reset that landed during the
        // display rebuild leaves this a spectrum of the old signal.
        if (epochAtStart != resetEpoch.get()) {
            if (log.isInfoEnabled()) {
                log.info("Signal changed mid-analysis - straddling window discarded before publish");
            }
            resultPool.release(r);
            return IDLE_TICK_MS;
        }
        publishResult(r);
        handToPredistortionEngine(r);
        return awaitFor(pendingHopSamples, sampleRate);
    }

    /** Pauses the analysis after this cycle - the worker loop runs no further
     *  analysis while the pause flag is set - and announces the auto-stop on
     *  {@link Events#FFT_RECORDING_AUTO_STOPPED} on the display thread, on
     *  which the FFT pane flips Record off. */
    private void pauseAndAnnounceAutoStop() {
        paused.set(true);
        if (display != null && !display.isDisposed()) {
            display.asyncExec(() ->
                    MessageBus.instance().publish(Events.FFT_RECORDING_AUTO_STOPPED));
        }
    }

    /** Deep-copies {@code newSlot} on the worker thread and publishes
     *  the snapshot on {@link Events#FFT_RESULT_AVAILABLE} via
     *  {@link Display#asyncExec} so subscribers receive the payload on
     *  the UI thread.  The copy decouples the worker (which may keep
     *  mutating its working {@code r}) from {@link FftView} (which
     *  paints from the published snapshot for many frames). */
    private void publishResult(FftResult newSlot) {
        if (display == null || display.isDisposed()) {
            resultPool.release(newSlot);
            return;
        }
        startSendToUi = System.nanoTime();
        // Coalescing handoff: stash the newest result and post a drain runnable
        // ONLY when the slot was empty.  If the UI hasn't drained the previous
        // one yet (a large-FFT repaint is far slower than the parallelized
        // per-tick), the newer result simply overwrites it - the SWT asyncExec
        // queue then holds at most one spectrum instead of growing without
        // bound until recording stops.  Averaging keeps running every tick; only
        // the DISPLAY is throttled to the UI's paint rate, which is lossless for
        // a coherent average (the next frame carries the deeper accumulation).
        FftResult coalesced = latestForUi.getAndSet(newSlot);
        if (coalesced != null) {
            // The UI never saw the overwritten result - recycle it.
            resultPool.release(coalesced);
            return;
        }
        display.asyncExec(this::drainPendingResult);
        // Force the SWT main loop to wake from Display.sleep() if it was
        // dozing.  asyncExec already posts a wake message but on Windows
        // the message queue can drop wakes under load - wake() is the
        // belt-and-suspenders nudge.
        display.wake();
    }

    /** Publishes a parked result to the bus NOW - UI thread only.  The
     *  asyncExec posted by {@link #publishResult} is the wake-from-idle path,
     *  but SWT dispatches asyncExec work only when the native event queue
     *  drains empty - and a GL scope's swap traffic can keep that queue busy
     *  for as long as it renders, leaving the spectrum to update once in
     *  dozens of averages.  The render heartbeat therefore PULLS through this
     *  method as well: it rides a timer, which is dispatched regardless of
     *  the queue.  Idempotent between the two callers - both run on the UI
     *  thread and both take the slot with {@code getAndSet(null)}. */
    public void drainPendingResult() {
        FftResult slot = latestForUi.getAndSet(null);
        if (slot == null) return;
        // A slot parked here across a reset or a change of the generated signal
        // carries the spectrum of what was measured BEFORE it: shown, it flashes
        // as the new signal's reading, and measured on, it reports the old
        // signal's products.  It dies where it waited instead of being handed
        // over for its consumers to recognise.
        if (slot.epoch != resetEpoch.get()) {
            resultPool.release(slot);
            return;
        }
        try {
            MessageBus.instance().publish(Events.FFT_RESULT_AVAILABLE, slot);
        } catch (Exception e) {
            log.error("Can't dispatch Events.FFT_RESULT_AVAILABLE", e);
        } finally {
            // Subscribers ran synchronously in publish() above and
            // deep-copied what they keep - the slot is free again.
            resultPool.release(slot);
        }
    }

    public void uiGotResult() {
        if (log.isWarnEnabled() && DebugSwitches.SHOW_FFT_ANALYZE_TIME) {
            log.warn("2. FFT result achieved UI in {} ms", (double)(System.nanoTime() - startSendToUi) / 1_000_000);
        }
    }

    /** Hands every published result to {@code engine} from now on, in place
     *  of any engine registered before. */
    public void registerPredistortionEngine(PredistortionEngine engine) {
        predistortionEngine = engine;
    }

    /** Stops handing results to the registered engine. */
    public void unregisterPredistortionEngine() {
        predistortionEngine = null;
    }

    /**
     * Hands {@code r}, the same object {@link #publishResult} has just put
     * into the hand-off slot, to the registered engine on this worker's
     * thread; the engine judges it and returns.  A result whose epoch a reset
     * has moved past since the last gate belongs to a collection that has
     * ended and is not handed.  The result the engine's run settles on stops
     * the analysis the way stop-after-N stops it
     * ({@link #pauseAndAnnounceAutoStop}).
     *
     * <p>The view finalizes the same object in place on the display's thread.
     * The engine reads, directly and through the correction sets'
     * measurements: the frame count, the channel, the bin
     * width, the refined tones, the captured fundamental and peak phasors
     * ({@link FftResult#rawFundRe} / {@link FftResult#rawFundIm},
     * {@link FftResult#rawPeakRe} / {@link FftResult#rawPeakIm}), the tones'
     * DAC phases ({@link FftResult#dacFundPhaseRad},
     * {@link FftResult#dacFund2PhaseRad}), the harmonic count, bins and
     * frequencies, the harmonics' levels ({@link FftResult#rawHarmonicDbFs}:
     * {@link FftResult#preCorrectionPeaks}, otherwise
     * {@link FftResult#harmonicDbFs}), the product grid
     * ({@link FftResult#imdProductA} / {@link FftResult#imdProductB}), and
     * {@link FftResult#amplitudeDbFs} with {@link FftResult#freqResolution}
     * and {@link FftResult#windowNenbwBins} for the lines' clearances.  The
     * view rewrites four of them in place: the harmonic bins, which its
     * statistics recompute rewrites with the value they hold (the nearest bin
     * to the same harmonic frequency); {@link FftResult#harmonicDbFs}, which
     * the same recompute rewrites; {@link FftResult#preCorrectionPeaks},
     * which its calibration sets; and {@link FftResult#amplitudeDbFs}, which
     * its mains correction and calibration rewrite.  Until the view has run,
     * the pre-correction peaks are null and the harmonic levels hold the
     * analyser's detection on the last single frame, not the average.
     */
    private void handToPredistortionEngine(FftResult r) {
        PredistortionEngine engine = predistortionEngine;
        if (engine == null) return;
        if (r.epoch != resetEpoch.get()) return;
        boolean settled = engine.onResult(r);
        if (settled) pauseAndAnnounceAutoStop();
    }

    private int msForSamples(int samples, int sampleRate) {
        if (sampleRate <= 0 || samples <= 0) return 0;
        return (int) Math.ceil(1000.0 * samples / sampleRate);
    }

    /** Marks the coming inter-tick wait as DATA-driven: the loop blocks on
     *  the ring until {@code samples} fresh samples exist (see
     *  {@link #awaitSamplesNext}).  Returns the rate-derived estimate, which
     *  only CAPS that wait. */
    private int awaitFor(int samples, int sampleRate) {
        awaitSamplesNext = samples;
        return msForSamples(samples, sampleRate);
    }

    /** One hop from the derived bounds: the single fixed value when they
     *  coincide; for the random overlap the next point of the mode's hop
     *  lattice - the range split into {@link FftOverlap#RAND_HOP_STEPS} equal
     *  parts, the hop landing on a part boundary (the largest value is the
     *  max hop itself; the bare min hop is never used) - visited in a fixed
     *  cycle through {@link #hopStep}. */
    private int nextHop(int minHopSamples, int maxHopSamples) {
        if (minHopSamples >= maxHopSamples) return minHopSamples;
        int quantum = Math.max(1, (maxHopSamples - minHopSamples) / FftOverlap.RAND_HOP_STEPS);
        int step = hopStep;
        hopStep = (hopStep + 1) % FftOverlap.RAND_HOP_STEPS;
        return Math.min(maxHopSamples, minHopSamples + (1 + step) * quantum);
    }

    /** Consumes this tick's share of a span being collected into {@code dst}:
     *  copies what the ring holds right now (at most the {@code target - have}
     *  still missing) behind the {@code have} samples already there, and
     *  returns the new fill count - {@code target} once the span is complete,
     *  or {@link SignalBufferReader#OVERRUN} if the cursor was lapped.  Reading
     *  every tick is what keeps the cursor close behind the writer while a span
     *  longer than the ring is being assembled.  Worker-thread only (it
     *  advances the cursor). */
    private int gather(SignalBufferReader rdr, double[] dst, double[] refDst, int have, int target,
                       boolean wantLeft, long avail) {
        int chunk = (int) Math.min(avail, target - have);
        if (chunk <= 0) return have;
        int got = rdr.read(chunk, wantLeft ? dst : refDst, wantLeft ? refDst : dst, have);
        return got == SignalBufferReader.OVERRUN ? SignalBufferReader.OVERRUN : have + got;
    }


    /** Computes the dBFS reference anchor passed into {@link FftAnalyzer}:
     *  the manual fundamental (canonical Vrms - the field stores nothing
     *  else, whatever unit the user typed in) is resolved to its dBV anchor,
     *  then converted to dBFS here at the boundary - the analyzer speaks
     *  dBFS only.  Returns {@code NaN} (no anchor) unless manual-fundamental
     *  mode is enabled. */
    private double resolveFundRefDbFs(Preferences prefs) {
        if (prefs.isFftManualFundEnabled()) {
            double v = prefs.getFftManualFundVrms();
            double dbv = (v > 0) ? 20.0 * Math.log10(v) : Double.NaN;
            return dbv - AudioBackend.instance().analysedChannelDbvOffsetDb();   // NaN propagates
        }
        return Double.NaN;
    }
}
