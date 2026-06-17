/*
 * Phonalyser — precision audio measurement workbench.
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import org.eclipse.swt.widgets.Display;
import org.edgo.audio.measure.dsp.MainsCombFilter;
import org.edgo.audio.measure.dsp.SpectralDiscontinuityDetector;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.GenChangeCause;
import org.edgo.audio.measure.dsp.MainsFilters;
import org.edgo.audio.measure.dsp.MainsTimeFilter;
import org.edgo.audio.measure.enums.MainsSuppression;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.fft.FftAnalyzer.FrameFftCache;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.fft.FftResultPool;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.DebugSwitches;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.gui.sound.SignalBufferReader;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Background analyser worker that owns the FFT compute thread and its
 * {@link FftAnalyzer} instance.  Holds none of the view's state — the
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
     *  instead of overrunning — but never deferred past {@code DISPLAY_MAX_NANOS}
     *  so the view can't freeze. */
    private static final long DISPLAY_MIN_NANOS =  40_000_000L;   // ≤25 Hz refresh when keeping up
    private static final long DISPLAY_MAX_NANOS = 500_000_000L;   // ≥2 Hz even while behind
    /** Output-pipeline drain to skip after a signal change, in seconds.
     *  The DAC's hardware buffer (≈480 ms on the JavaSound render path)
     *  keeps the OLD tone flowing into the ADC after the generator
     *  changed; a window built before it drains straddles both signals —
     *  poisoning the fresh accumulator AND feeding the FLL a smeared,
     *  plausible-looking first measurement. */
    private static final double OUTPUT_DRAIN_SKIP_SEC = 0.7;
    /** Mains-comb re-track cadence in analysis ticks.  The tracker's lock
     *  is an EWMA with ≈33-detection memory, so tracking every Nth tick
     *  changes the smoothed lock negligibly while freeing the ~208
     *  Goertzel sweeps (30–80 ms at large fftSize) from most ticks —
     *  budget that 93.75 % overlap at 1 M needs to stay under its 170 ms
     *  hop. */
    private static final int MAINS_TRACK_TICK_INTERVAL = 5;
    private long lastShowNanos;

    private final Display  display;

    private final FftAnalyzer analyzer = new FftAnalyzer();

    private volatile SignalBufferReader reader;
    /** True while this worker holds a {@code SharedCapture} reference (acquired
     *  in {@link #start}, released in {@link #stop}).  The worker owns its own
     *  capture lifecycle, so the view / pane never touch the sample buffer. */
    private boolean captureHeld;
    private final    AtomicBoolean      paused = new AtomicBoolean(false);
    /** Bumped by every {@link #resetStatistics()}.  An analysis tick stamps
     *  the epoch at its start and discards its result when a reset landed
     *  while it was assembling / analyzing: such a window straddles the
     *  signal change — accumulating it would poison the freshly cleared
     *  average, and its stale fundamental would feed the FLL one garbage
     *  trim (enough to drag the live generator far off-frequency). */
    private final    AtomicLong         resetEpoch = new AtomicLong();
    /** Set by {@link #resetStatistics()} while the worker runs; consumed at
     *  the top of the next tick, where the worker itself wipes the
     *  accumulator / mains tracking.  Those structures are worker-owned and
     *  not thread-safe — wiping them from the resetting thread mid-tick can
     *  NPE the accumulate loops or leave the detector half-reset. */
    private final    AtomicBoolean      resetPending = new AtomicBoolean();
    /** Companion to {@link #resetPending} for resets caused by a change of
     *  the GENERATED SIGNAL: the worker discards
     *  {@link #OUTPUT_DRAIN_SKIP_SEC} of samples after the re-anchor before
     *  building the first window — see
     *  {@link #resetStatisticsAfterSignalChange()}. */
    private final    AtomicBoolean      drainSkipPending = new AtomicBoolean();
    /** Samples still to discard after a signal-change re-anchor
     *  (worker-thread only). */
    private long drainSkipRemaining;
    /** Ticks since the mains comb was last re-tracked (worker-thread
     *  only); see {@link #MAINS_TRACK_TICK_INTERVAL}. */
    private int  mainsTrackTick;
    /** Single-slot, coalescing handoff of the latest result to the UI thread.
     *  The worker overwrites it every tick (newest wins) and posts ONE drain
     *  runnable only on the empty→full transition, so the SWT asyncExec queue
     *  never accumulates more than one multi-MB spectrum however far the UI
     *  repaint falls behind the (parallelized, fast) per-tick production. */
    private final AtomicReference<FftResult> latestForUi = new AtomicReference<>();
    /** Recycled {@link FftResult} slots.  A slot is acquired at the top of
     *  a tick and released exactly once — on every discard path, on a
     *  coalesce overwrite in {@link #publishResult}, or after the
     *  synchronous bus dispatch in the drain runnable (subscribers
     *  deep-copy what they keep, so the slot is free once {@code publish}
     *  returns).  In steady state at most 3 slots circulate (one being
     *  filled, one parked in {@link #latestForUi}, one in the UI
     *  dispatch). */
    private final FftResultPool resultPool = new FftResultPool();
    @Getter
    private volatile int                completedAnalyses;
    @Getter
    private volatile boolean            running;
    /** False until the very first analysis has run since {@link #start}. */
    @Getter
    private volatile boolean firstFrameDone;
    /** Tracks generator on/off transitions so the data collection is
     *  reset whenever the user starts or stops the generator. */
    private volatile Boolean lastGeneratorActive;

    // ─── Contiguous sliding analysis window ─────────────────────────────────
    /** The selected channel's contiguous analysis window, slid forward one
     *  hop per tick by pulling fresh samples from {@link #reader} (a consuming
     *  FIFO cursor).  Retained across ticks — the overlap region — so overlap
     *  works WITHOUT re-reading the ring.  {@link #winValid} marks it as
     *  holding a full {@code needed}-sample contiguous block; a window-size /
     *  channel change or a capture overrun invalidates it and it is rebuilt
     *  from fresh samples.  Worker-thread only (except the volatile flags). */
    private double[] winBuf = new double[0];
    private long    winAbsStart;
    private int     winLen;
    private int     winNeeded     = -1;     // `needed` the window was built for
    private boolean winChannelLeft;         // channel the window currently holds
    private volatile boolean winValid;
    /** Set on (re)start / reset; the worker re-anchors {@link #reader} to the
     *  latest sample on its next tick so the window rebuilds from fresh data
     *  (no stale pre-reset samples leak in).  Re-anchoring on the worker thread
     *  keeps the cursor single-owner even though resets arrive from others. */
    private volatile boolean reanchorPending = true;
    /** Reusable per-tick scratch: {@code analyzeBuf} is the analysis copy (the
     *  comb / FFT window mutate it in place, so it must not be {@link #winBuf}),
     *  {@code hopBuf} stages the fresh hop pulled from the reader each tick. */
    private double[] analyzeBuf = new double[0];
    private double[] hopBuf     = new double[0];

    /** Mains-hum comb, lazily built for the current sample rate.  Used only
     *  when {@code fftMainsSuppression == IIR_COMB}: it only TRACKS the mains
     *  frequency here (the rejection is a plot-time spectral correction in
     *  FftView, leaving the accumulator raw — the samples are not filtered). */
    private MainsCombFilter mainsComb;
    private int             mainsCombSampleRate;
    /** Time-domain mains filter (synchronous subtraction / LMS) applied to the
     *  captured window IN PLACE before the FFT, for the SYNC_SUBTRACT / LMS
     *  modes.  The IIR comb stays a plot-time spectral correction instead — see
     *  {@code mainsF0Hz} / FftView. */
    private MainsTimeFilter  mainsTimeFilter;
    private MainsSuppression mainsTimeFilterMode = MainsSuppression.NONE;
    private int              mainsTimeFilterSampleRate;
    private Thread worker;

    // Perf instrumentation for DebugSwitches.SHOW_FFT_ANALYZE_TIME — two
    // nanoTime marks splitting a tick's latency into its two legs.
    /** Set at the top of {@code doAnalysis()}; logged against it at the end of
     *  the analysis ("1. FFT analyze took") — the pure worker-thread compute time. */
    private long startAnalyze;
    /** Set in {@code publishResult()} when the result is handed to the SWT
     *  asyncExec queue; logged against it in {@link #uiGotResult} ("2. FFT result
     *  achieved UI") — the worker → UI-thread hand-off latency, including any
     *  coalescing wait behind a slow repaint. */
    private long startSendToUi;

    // ─── Per-frame FFT cache (perf) ─────────────────────────────────────────
    /** Soft budget for total cache memory (bytes).  Cap on frame count is
     *  derived from this so a large fftSize automatically shrinks the
     *  number of cached frames instead of inflating the heap to GB. */
    private static final long FRAME_CACHE_BYTE_BUDGET = 128L * 1024 * 1024;
    /** Shared monitor for all {@link #frameCacheMap} and {@link #arrayPool}
     *  access — both are written/read on the worker thread but cleared
     *  from arbitrary threads (event subscriptions, {@link #resetStatistics}). */
    private final Object frameCacheLock = new Object();
    /** Position-keyed cache of raw windowed FFT results (re[], im[]) for
     *  individual frames.  Lets each tick FFT only the genuinely-new
     *  frame instead of re-FFT-ing all N frames in the sliding window. */
    private final LinkedHashMap<Long, double[][]> frameCacheMap =
            new LinkedHashMap<Long, double[][]>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, double[][]> eldest) {
            if (size() <= frameCacheCap) return false;
            // Salvage the evicted entry's arrays to the pool so the next
            // put can reuse them via arraycopy instead of allocating
            // fresh doubles[fftSize] — eliminates the GC churn that
            // makes large-fftSize FFT panes drift toward heap exhaustion.
            double[][] v = eldest.getValue();
            if (v != null && v[0] != null) {
                arrayPool.addLast(v[0]);
                arrayPool.addLast(v[1]);
            }
            capPoolLocked();
            return true;
        }
    };
    /** Reusable double[] buffers for the cache.  Arrays evicted from
     *  {@link #frameCacheMap} land here; the next {@link FrameFftCache#put}
     *  pops one instead of allocating.  Capped at {@code 2 × cap} to
     *  bound retained memory on rapid cap shrinks. */
    private final ArrayDeque<double[]> arrayPool = new ArrayDeque<>();
    /** Current cap on cached frames — derived per-tick from
     *  {@link #FRAME_CACHE_BYTE_BUDGET} and the configured averaging count,
     *  so the cache stays bounded regardless of fftSize. */
    private volatile int frameCacheCap = 16;
    /** Fingerprint of (fftSize, window, channel, sampleRate) at the time
     *  the cache was last known to be coherent.  Mismatch ⇒ clear. */
    private volatile long frameCacheFingerprint = Long.MIN_VALUE;
    /** Event-bus callback that drops the per-frame raw-FFT cache.  Does
     *  NOT call {@link #resetStatistics}: the cache contents are
     *  mathematically independent of cal changes (cal is applied after
     *  {@code analyze}) and of generator parameter changes (the cached
     *  FFTs are still correct for the samples that are physically in
     *  the buffer).  Calling resetStatistics here would force the next
     *  analysis to wait for a full fftLength of fresh samples — a
     *  multi-second blank period the user perceives as a glitch every
     *  time they nudge an amplitude / frequency / cal row. */
    private final Consumer<Void> invalidateOnEvent = ignored -> recycleAndClearCache();
    /** Variant of {@link #invalidateOnEvent} for
     *  {@link Events#GENERATOR_SIGNAL_CHANGED}: inspects the payload's
     *  {@link GenChangeCause} and skips the cache flush when the cause
     *  is {@link GenChangeCause#FLL_TRIM}.  An FLL trim is a sub-Hz
     *  alignment of the generator with the FFT bin grid; the cached
     *  FFTs are still correct (the underlying samples haven't changed)
     *  and we want to KEEP averaging so the lock converges. */
    private final Consumer<GenChangeCause> invalidateOnGenChange = cause -> {
        if (cause == GenChangeCause.FLL_TRIM) return;
        recycleAndClearCache();
    };
    /** Cache adapter passed to {@link FftAnalyzer#setFrameCache}; backed
     *  by {@link #frameCacheMap} under {@link #frameCacheLock}. */
    private final FftAnalyzer.FrameFftCache frameFftCacheImpl =
            new FftAnalyzer.FrameFftCache() {
        @Override
        public boolean tryFill(long absStart, int fftSize,
                               double[] outRe, double[] outIm) {
            double[][] hit;
            synchronized (frameCacheLock) {
                hit = frameCacheMap.get(absStart);
            }
            if (hit == null || hit[0].length != fftSize) return false;
            System.arraycopy(hit[0], 0, outRe, 0, fftSize);
            System.arraycopy(hit[1], 0, outIm, 0, fftSize);
            return true;
        }
        @Override
        public void put(long absStart, int fftSize, double[] re, double[] im) {
            double[] reCopy, imCopy;
            synchronized (frameCacheLock) {
                reCopy = obtainArrayLocked(fftSize);
                imCopy = obtainArrayLocked(fftSize);
            }
            // Copy outside the lock — System.arraycopy is the hot path
            // (megabytes per put at large fftSize); holding the lock
            // would needlessly block cache invalidate from other threads.
            System.arraycopy(re, 0, reCopy, 0, fftSize);
            System.arraycopy(im, 0, imCopy, 0, fftSize);
            synchronized (frameCacheLock) {
                frameCacheMap.put(absStart, new double[][] { reCopy, imCopy });
            }
        }
    };

    /** Pop a pooled {@code double[size]} if one is available; otherwise
     *  allocate.  Wrong-sized arrays in the pool are discarded.  Caller
     *  MUST hold {@link #frameCacheLock}. */
    private double[] obtainArrayLocked(int size) {
        while (!arrayPool.isEmpty()) {
            double[] a = arrayPool.pollLast();
            if (a.length == size) return a;
            // wrong size (config change leftover) → let GC reclaim
        }
        return new double[size];
    }

    /** Bounds the pool so it never holds more spare arrays than the
     *  cache cap can re-consume.  Caller MUST hold the lock. */
    private void capPoolLocked() {
        int budget = Math.max(4, frameCacheCap * 2);
        while (arrayPool.size() > budget) arrayPool.pollFirst();
    }

    /** Clears the cache, recycling every entry's arrays into the pool
     *  so the next {@link FrameFftCache#put} reuses them instead of
     *  allocating.  Safe to call from any thread. */
    private void recycleAndClearCache() {
        synchronized (frameCacheLock) {
            for (double[][] v : frameCacheMap.values()) {
                if (v != null && v[0] != null) {
                    arrayPool.addLast(v[0]);
                    arrayPool.addLast(v[1]);
                }
            }
            frameCacheMap.clear();
            capPoolLocked();
        }
    }

    /** Drops both the cache AND the pool (e.g., after a config change
     *  where every pooled array has the wrong size). */
    private void discardCacheAndPool() {
        synchronized (frameCacheLock) {
            frameCacheMap.clear();
            arrayPool.clear();
        }
    }

    // ─── Cross-tick accumulator (forever-mode only) ────────────────────────
    /** Coherent accumulator: running complex sum (re/im) across every
     *  forever-mode tick since the last reset.  Each tick contributes its
     *  per-bin r.re/r.im rotated to align with {@link #accumRefSampleStart}'s
     *  time origin and weighted by that tick's {@code frameCount}, so the
     *  cumulative SNR boost is √(total frames) instead of √(frames per
     *  tick).  Null when not in forever-coherent mode or before the first
     *  contribution. */
    private double[] accumRe;
    private double[] accumIm;
    private int[]    imdGridIdx;   // per-bin IMD-product assignment (cross-tick dual-tone de-rotation)
    /** Incoherent (power) accumulator counterpart.  Holds {@code |X[k]|²}
     *  weighted by frame count, no phase alignment needed. */
    private double[] accumPow;
    /** Total frame count accumulated since the last reset.  Drives the
     *  "N average(s)" label in forever mode and the stop-after-N
     *  threshold. */
    private volatile int accumFrames;
    /** Absolute sample-stream position of the first contributing tick's
     *  frame-0 (= its {@code samplesAbsStart}).  Every subsequent tick's
     *  spectrum is phase-rotated by the time delta from this reference
     *  to keep tones coherent across ticks. */
    private long    accumRefSampleStart;
    /** Cross-tick de-rotation frequency (fractional fundamental bin), PINNED at
     *  the first contributing tick from the refined fundamental and held
     *  constant for the run so the rotation is well-defined.  A source whose
     *  frequency drifts more than a few ppm during a long average loses phase
     *  coherence — a hardware-stability concern, not corrected here. */
    private double  accumKFractional;
    /** Discontinuity-detector toggle.  The frequency-domain running-median
     *  rejector compares each block's spectrum to the accepted history; the
     *  earlier time-domain Δⁿ test was removed entirely once this proved the
     *  better detector ({@code false} disables rejection altogether). */
    private static final boolean USE_SPECTRAL_DISCONTINUITY = true;
    /** Frequency-domain running-median glitch / stall rejector, run per tick
     *  on the freshly computed spectrum before it enters the cross-tick
     *  (vector) average — where one bad block injects a large complex error. */
    private final SpectralDiscontinuityDetector spectralDetector = new SpectralDiscontinuityDetector();
    /** Debug overlay: the last gate-REJECTED block's pre-average spectrum (dBFs),
     *  held so {@code FftView} can show what tripped a gate (rejected blocks never
     *  otherwise reach the display). */
    private volatile double[] lastRejectBlockDbFs;
    /** Debug overlay: the gate verdict (which gate fired + values) for that same
     *  last rejected block. */
    private volatile SpectralDiscontinuityDetector.Gates lastRejectGates;
    /** Per-harmonic de-rotation phasor cache for the cross-tick single-reference
     *  accumulation (cos/sin of {@code h·Φ}); rebuilt per tick. */
    private double[] hcosScratch, hsinScratch;
    /** Pinned {@code round(kFractional)} for the rotation formula — the
     *  integer bin is stable, so only the fractional part needs converging. */
    private int     accumIntFundBinRounded;
    /** Phase-slope κ refinement.  The single-frame parabolic peak pins κ only to
     *  ~0.01–0.03 bin; over a long coherent run that residual ramps the de-
     *  rotated fundamental phase (2π·Δκ·delta/fftSize) and vector-cancels the
     *  magnitude — harmonics h× faster.  Measuring the de-rotated fundamental's
     *  phase SLOPE over {@link #KAPPA_MEAS_FRAMES} CLEAN frames yields Δκ directly
     *  (~100× tighter than the peak), folded into κ ONCE so the ramp vanishes.
     *  Re-syncs (overrun / discontinuity) jump delta; the frame after one is
     *  flagged {@link #kappaSkipNext} and used only to re-anchor the running
     *  reference — its jumped step is NOT folded in — so a burst of re-syncs
     *  can't poison or starve the measurement; only the clean frames between
     *  them count.  Single-reference path only; harmonics ride the time-shift. */
    private boolean kappaRefined;
    private int     kappaMeasFrames;   // CLEAN frames counted (the anchor + folded steps)
    private double  kappaLastDelta;    // delta at the running reference frame
    private double  kappaLastPhase;    // de-rotated fundamental phase at the reference
    private double  kappaCumPhase;     // Σ clean per-frame phase steps
    private double  kappaCleanSpan;    // Σ clean per-frame delta steps (slope denominator)
    private boolean kappaSkipNext;     // next frame is post-re-sync: re-anchor, don't fold
    /** Per-tone analogue of the κ refine above, for the multi-tone path.  Each
     *  detected tone's de-rotated phase slope over {@link #KAPPA_MEAS_FRAMES} clean
     *  frames yields its own Δκ — folded into {@link #accumToneKappa} ONCE — so the
     *  tones AND the IMD products built from them (a·κ1+b·κ2) lock instead of riding
     *  the tick-0 parabolic κ's residual.  Per-tone phase arrays; the frame count /
     *  clean span / skip flag are shared (one delta per frame).  PLL stays off until
     *  refined, so the slope is measured against the pinned κ (not the tracked one). */
    private boolean  multiKappaRefined;
    private int      multiKappaMeasFrames;
    private double   multiKappaLastDelta;
    private double   multiKappaCleanSpan;
    private boolean  multiKappaSkipNext;
    private double[] multiKappaCumPhase;    // Σ clean per-frame phase steps, per tone
    private double[] multiKappaLastPhase;   // de-rotated phase at the reference, per tone
    /** Phase-tracking loop (a PLL on the cross-tick fundamental).  Once κ is
     *  refined the de-rotated fundamental phase should be constant; a frozen
     *  pinned-κ residual lets it slowly ramp, and a re-sync (the event that sets
     *  {@link #gapRecoverPending}) steps it.  Each tick the running mis-alignment
     *  vs the deep accumulated phase is measured and a fraction
     *  ({@link #PHASE_TRACK_GAIN}) folded into {@link #accumDroppedSamples} so the
     *  de-rotation re-locks — killing the drift and absorbing disturbances.  On a
     *  re-sync a one-shot FULL realign is applied (then the loop cleans up). */
    private double  accumDroppedSamples;   // cumulative de-rotation correction (sub-sample), driven by the loop
    private boolean gapRecoverPending;     // a re-sync fired; do a one-shot full realign on the next frame
    /** Frames of de-rotated-fundamental phase observed before the one-shot κ
     *  refine — ~24 give κ to ~1e-4 bin at high SNR, landing before the drift
     *  becomes visible (~30 frames). */
    private static final int KAPPA_MEAS_FRAMES = 24;
    /** Phase-tracking loop gain (fraction of the running fundamental mis-alignment
     *  folded into the de-rotation each tick).  Low enough to average out per-tick
     *  phase noise (which would random-walk if folded whole), high enough to track
     *  the κ-residual ramp.  Raise it if the fundamental still drifts; lower it if
     *  the harmonics get noisy. */
    private static final double PHASE_TRACK_GAIN = 0.10;
    /** Dropout threshold: when the per-tick fundamental falls below this fraction
     *  of the accumulated average, the signal is treated as dropped (silence /
     *  disconnect) and the tick is held out of the average instead of diluting it. */
    private static final double DROPOUT_FRACTION = 0.75;
    /** A per-tone cross-tick phase residual larger than this is treated as a
     *  phase DISCONTINUITY (a DDS phase jump / reconnect that the window
     *  glitch-gate didn't catch) rather than tracking noise: the tone is
     *  realigned in one shot (gain 1) so the frame adds coherently at its new
     *  phase instead of smearing the average.  Well above the per-tick noise +
     *  κ-ramp residual (sub-degree), so it only fires on a genuine jump.  This
     *  is the cross-tick safety net for the multi-tone path, where the
     *  analyzer's per-sample R-invariant glitch test is disabled. */
    private static final double PHASE_JUMP_RAD = Math.toRadians(60.0);
    /** Fractional bins of the strong tones detected at restart (sorted
     *  ascending).  Length ≤ 1 ⇒ single-reference rotation (legacy path);
     *  length ≥ 2 ⇒ each tone's lobe is de-rotated by its OWN frequency so
     *  the accumulated spectrum keeps every fundamental at its true sub-bin
     *  position instead of locking it to the FFT grid.  Found from the
     *  spectrum itself, so it works for unknown / external multi-tone
     *  signals where the app can't know the frequencies up front. */
    private double[] accumToneKappa = new double[0];
    /** Per-tone cumulative de-rotation correction (sub-sample), one entry per
     *  {@link #accumToneKappa} tone — the multi-tone analogue of
     *  {@link #accumDroppedSamples}.  Each tone runs its OWN phase-lock loop
     *  (residual vs the deep accumulated phase at the tone's bin) and folds the
     *  correction here, so an IMD tone pair holds instead of each lobe ramping
     *  on a frozen per-tone κ.  Length tracks {@link #accumToneKappa}. */
    private double[] toneDroppedSamples = new double[0];
    /** Half-width (bins) of the lobe a tone's constant phase covers. */
    private static final int    TONE_LOBE_HALF_BINS = 16;
    /** Two peaks closer than this (bins) are merged into one tone. */
    private static final int    MIN_TONE_SEP_BINS   = 48;
    /** Cap on independent tone references. */
    private static final int    MAX_TONES           = 8;
    /** A peak at an integer multiple (h≥2) of the strongest tone that is at
     *  least this far BELOW it is a harmonic (distortion), not an independent
     *  tone — drop it from the tone list so a single tone + harmonics stays on
     *  the single-reference (phase-slope-refined) path instead of the per-tone
     *  multi-tone path.  Far below ⇒ it can't be a real IMD partner (which is
     *  comparable in level) and the single-ref time-shift already aligns it. */
    private static final double HARMONIC_REJECT_BELOW_DB = 40.0;
    /** Ignore peaks below this frequency when detecting tones — a residual DC
     *  offset leaks through the window main lobe into the first bins and would
     *  be taken as a spurious second tone.  Well under any real tone. */
    private static final double DC_REJECT_HZ = 10.0;
    /** Config the accumulator was built for; mismatch ⇒ reset. */
    private int     accumFftSize;
    private boolean accumCoherent;
    /** True once at least one tick has contributed. */
    private boolean accumHasData;
    /** Previous tick's averaging mode, for deciding when to reset the cross-tick
     *  accumulator: the averaging mode flip, a ring↔infinite switch, or a
     *  smaller ring (a larger ring keeps the depth). */
    private boolean lastAccumulate;
    private boolean lastForeverMode;
    private int     lastRingN = -1;

    /**
     * @param display SWT display used to marshal {@link Events#FFT_RESULT_AVAILABLE}
     *                publishes back to the UI thread.  Subscribers to that
     *                event (typically the FFT view's {@code redraw()}) are
     *                where the post-analysis paint hook now lives.
     */
    public FftAnalyzerWorker(Display display) {
        this.display = display;
        analyzer.setFrameCache(frameFftCacheImpl);
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

    /** Drops the cross-tick accumulator.  Safe to call from any thread —
     *  arrays are reassigned, not cleared in place. */
    private void resetAccumulator() {
        accumRe = null;
        accumIm = null;
        accumPow = null;
        accumFrames = 0;
        accumHasData = false;
        accumToneKappa = new double[0];
        toneDroppedSamples = new double[0];
        spectralDetector.reset();   // the median reference restarts with the average
        lastRejectBlockDbFs = null;
        lastRejectGates     = null;
    }

    /** Handles a ring overrun: the worker fell a full ring behind, so the
     *  contiguous span it was about to read had already been overwritten.  This
     *  is a COVERAGE gap, not corrupted data — the absolute sample positions are
     *  still correct ({@code writePos} counts every delivered sample), so the
     *  cross-tick de-rotation absorbs the gap (it de-rotates by the true sample
     *  delta) and the running average stays valid.  So just start the next FFT
     *  window fresh from "now"; do NOT restart averaging.  Worker-thread only
     *  (it advances the cursor). */
    private void onCaptureOverrun(SignalBufferReader rdr) {
        winValid = false;
        rdr.seekToLatest();
        kappaSkipNext      = true;   // κ measurement: re-anchor on the jumped frame, don't fold its step
        multiKappaSkipNext = true;   // multi-tone per-tone κ refine: same re-anchor
        gapRecoverPending  = true;   // re-sync: one-shot full realign on the next clean frame, then track
        if (log.isWarnEnabled()) {
            log.warn("FFT ring overrun — analyser fell a full buffer behind; window re-anchored, "
                    + "averaging continues (lower the overlap or FFT length if this repeats)");
        }
        publishCaptureBanner("fft.warning.overrun");
    }

    /** Posts a capture re-sync warning to the view on the UI thread, carrying the
     *  i18n message-key as the payload — so ONE event presents the distinct
     *  "overrun" vs "discontinuity" messages depending on the caller. */
    private void publishCaptureBanner(String messageKey) {
        if (display == null || display.isDisposed()) return;
        display.asyncExec(() -> {
            try {
                MessageBus.instance().publish(Events.FFT_CAPTURE_RESYNC, messageKey);
            } catch (Exception e) {
                if (log.isErrorEnabled()) log.error("Can't dispatch {}", Events.FFT_CAPTURE_RESYNC, e);
            }
        });
        display.wake();
    }

    /** Recovery for a detected in-window signal discontinuity — the same re-sync
     *  as a ring overrun: discard the glitched window, re-anchor to "now" and
     *  rebuild, KEEP the running average (the de-rotation absorbs the coverage
     *  gap and the cross-tick gap recovery corrects any post-glitch phase step).
     *  Worker-thread only. */
    private void onSignalDiscontinuity(SignalBufferReader reader) {
        winValid = false;
        reader.seekToLatest();
        kappaSkipNext      = true;   // κ measurement: re-anchor on the jumped frame, don't fold its step
        multiKappaSkipNext = true;   // multi-tone per-tone κ refine: same re-anchor
        gapRecoverPending  = true;   // re-sync: one-shot full realign on the next clean frame, then track
        if (log.isWarnEnabled()) {
            log.warn("Found signal discontinuity — re-synced (glitched window discarded, "
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

    /** Below this bin count the de-rotation runs serially — the fork/dispatch
     *  overhead would dominate the small loop. */
    private static final int DEROT_PARALLEL_THRESHOLD = 1 << 16;   // 64k bins

    /** A contiguous half-open [lo, hi) bin range to process. */
    @FunctionalInterface
    private interface RangeTask { void run(int lo, int hi); }

    /** Runs {@code task} over [0, n) split into one contiguous chunk per core on
     *  the common pool; below {@link #DEROT_PARALLEL_THRESHOLD} it runs the whole
     *  range on the caller's thread.  The chunks are disjoint, so a task that
     *  writes only its own [lo, hi) slice needs no synchronisation. */
    private void parallelChunks(int n, RangeTask task) {
        if (n < DEROT_PARALLEL_THRESHOLD) { task.run(0, n); return; }
        int chunks    = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
        int chunkSize = (n + chunks - 1) / chunks;
        IntStream.range(0, chunks).parallel().forEach(c -> {
            int lo = c * chunkSize;
            int hi = Math.min(n, lo + chunkSize);
            if (lo < hi) task.run(lo, hi);
        });
    }


    /** Adds the post-cal Result's spectrum to the cross-tick accumulator.
     *  For coherent mode, applies the time-shift phase rotation that aligns
     *  this tick's frame-0 with the accumulator's reference (so tones
     *  add coherently across ticks).  For incoherent mode, just sums
     *  power per bin.  Resets the accumulator transparently when fftSize
     *  or coherent flag changes between ticks.
     *
     *  @param targetN bound on the effective accumulated frame depth.  Use
     *         {@link Integer#MAX_VALUE} for an unbounded cumulative mean
     *         (forever mode); a finite N turns the running sum into an
     *         exponential window of N frames (α = weight/N).
     *  @return {@code true} if this tick was accumulated; {@code false} while
     *         the phase lock is still converging (the caller should then show
     *         the live single-tick spectrum, not the accumulator). */
    // Package-private (not private) so the cross-tick drift harness
    // (FftCrossTickDriftTest) can drive the real accumulator + PLL headlessly.
    boolean accumulateIntoForeverBuffer(FftResult r,
                                        long samplesAbsStart,
                                        boolean coherent,
                                        int targetN) {
        int fftSize  = r.fftSize;
        int halfSize = fftSize / 2;
        int N        = halfSize + 1;
        double kFrac = r.freqResolution > 0
                ? r.fundamentalHzRefined / r.freqResolution
                : 0.0;
        int intFundBin = Math.max(1, (int) Math.round(kFrac));

        boolean restart = !accumHasData
                || accumFftSize != fftSize
                || accumCoherent != coherent;
        if (restart) {
            accumRe  = coherent ? new double[N] : null;
            accumIm  = coherent ? new double[N] : null;
            accumPow = coherent ? null          : new double[N];
            accumFrames = 0;
            accumFftSize = fftSize;
            accumCoherent = coherent;
            accumRefSampleStart    = samplesAbsStart;
            accumKFractional       = kFrac;
            accumIntFundBinRounded = intFundBin;
            accumToneKappa         = coherent ? detectStrongTones(r) : new double[0];
            toneDroppedSamples     = new double[accumToneKappa.length];
            kappaRefined           = false;
            kappaMeasFrames        = 0;
            multiKappaRefined      = false;
            multiKappaMeasFrames   = 0;
            multiKappaCleanSpan    = 0.0;
            multiKappaSkipNext     = false;
            multiKappaCumPhase     = null;
            multiKappaLastPhase    = null;
            accumDroppedSamples    = 0.0;
            gapRecoverPending      = false;
            accumHasData = true;
            // State the cross-tick path outright — it is chosen by the SPECTRAL
            // peak count (detectStrongTones), NOT by the THD/IMD display mode, so
            // a single-tone THD setup with strong harmonics or residual mains can
            // still land on the multi-tone path (each tone independently
            // phase-locked, but no shared κ phase-slope refine).
            if (log.isInfoEnabled()) {
                log.info("FFT cross-tick path: {}",
                        accumToneKappa.length >= 2
                                ? "MULTI-TONE (" + accumToneKappa.length + " tones) — per-tone phase-lock loop "
                                  + "(strong harmonics or residual mains added peaks)"
                                : "single-reference — κ phase-slope refine + phase-lock loop");
            }
        }

        // Time-shift offset from the pinned reference frame-0.  An overrun /
        // discontinuity re-anchor jumps samplesAbsStart, but the COVERAGE gap is
        // correctly counted in writePos, so the absolute delta bridges it.  What
        // it does NOT count is a dropped-sample xrun (the ADC silently drops N) —
        // that's recovered into accumDroppedSamples by the gap-correction below
        // and added here so the pinned-κ rotation stays exact across the drop.
        double delta = (samplesAbsStart - accumRefSampleStart) + accumDroppedSamples;

        int weight = Math.max(1, r.frameCount);
        // Dropout guard: if the fundamental has collapsed (DAC/ADC disconnected,
        // signal muted), DON'T fold the silence into the average — it would just
        // dilute everything coherently built.  A dropout is smooth, so the Δ³s
        // glitch gate never fires; catch it here by amplitude and HOLD the
        // accumulator (return without adding) until the tone returns.
        if (coherent && accumRe != null && accumFrames > 0 && accumIntFundBinRounded > 0) {
            int    k0d      = Math.min(accumIntFundBinRounded, halfSize);
            double tickFund = Math.hypot(r.re[k0d], r.im[k0d]);
            double avgFund  = Math.hypot(accumRe[k0d], accumIm[k0d]) / accumFrames;
            if (avgFund > 0.0 && tickFund < DROPOUT_FRACTION * avgFund) {
                gapRecoverPending = true;   // tone returns at a new phase → one-shot realign then
                return true;                // hold the accumulator; don't dilute with a dropout
            }
        }
        // Bounded (ring) window: before adding this tick, scale the running sum
        // down so the effective depth holds at targetN frames.  This makes the
        // running sum an exponential window (α = weight/targetN); forever mode
        // passes targetN = MAX_VALUE and never scales (true cumulative mean).
        if (targetN < Integer.MAX_VALUE && accumFrames + weight > targetN) {
            int    keep  = Math.max(0, targetN - weight);
            double scale = accumFrames > 0 ? (double) keep / accumFrames : 0.0;
            if (coherent) {
                if (accumRe != null) {
                    for (int k = 0; k < N; k++) { accumRe[k] *= scale; accumIm[k] *= scale; }
                }
            } else if (accumPow != null) {
                for (int k = 0; k < N; k++) accumPow[k] *= scale;
            }
            accumFrames = keep;
        }
        if (coherent) {
            if (accumToneKappa.length >= 2) {
                // Multi-tone: de-rotate each detected tone's lobe by its OWN
                // frequency (constant phase), so every fundamental keeps its
                // true position — no single reference, hence no grid-lock.
                accumulateMultiTone(r, N, weight, delta);
            } else {
                // One-shot κ refine from the de-rotated fundamental's phase slope.
                // The single-frame peak pins κ to only ~0.01–0.03 bin; dφ/dΔ over
                // the first KAPPA_MEAS_FRAMES frames IS 2π·Δκ/fftSize, pinning the
                // EXACT frequency ~100× tighter and killing the long-run phase
                // ramp (which would vector-cancel the magnitude, harmonics h×
                // faster).  Refined before accumulating so the corrected κ is used
                // from here; the few pre-refine frames carry a negligible offset.
                if (accumIntFundBinRounded > 0) {
                    int    k0   = Math.min(accumIntFundBinRounded, halfSize);
                    double krot = -2.0 * Math.PI * delta * accumKFractional / fftSize;
                    double cr   = Math.cos(krot), sr = Math.sin(krot);
                    double obsPhase = Math.atan2(r.re[k0] * sr + r.im[k0] * cr,
                                                 r.re[k0] * cr - r.im[k0] * sr);
                    if (!kappaRefined) {
                        // One-shot κ refine from the de-rotated fundamental's phase
                        // slope: dφ/dΔ over KAPPA_MEAS_FRAMES CLEAN frames IS
                        // 2π·Δκ/fftSize, pinning the EXACT frequency ~100× tighter
                        // than the single-frame peak (whose ~0.01-bin bias would
                        // ramp the phase and sink the magnitude, harmonics h× faster).
                        if (kappaMeasFrames == 0 || kappaSkipNext) {
                            // Anchor, or re-anchor after a re-sync (delta jumped):
                            // adopt this frame as the reference but DON'T fold its
                            // step in — a burst can't poison or starve the measure.
                            if (kappaMeasFrames == 0) {
                                kappaCumPhase   = 0.0;
                                kappaCleanSpan  = 0.0;
                                kappaMeasFrames = 1;
                            }
                            kappaLastPhase = obsPhase;
                            kappaLastDelta = delta;
                            kappaSkipNext  = false;
                        } else {
                            kappaCumPhase  += Math.IEEEremainder(obsPhase - kappaLastPhase, 2.0 * Math.PI);
                            kappaCleanSpan += delta - kappaLastDelta;
                            kappaLastPhase  = obsPhase;
                            kappaLastDelta  = delta;
                            if (++kappaMeasFrames >= KAPPA_MEAS_FRAMES && kappaCleanSpan > 0.0) {
                                double dKappa = kappaCumPhase * fftSize / (2.0 * Math.PI * kappaCleanSpan);
                                accumKFractional += dKappa;
                                kappaRefined = true;
                                gapRecoverPending = false;
                                if (log.isInfoEnabled()) {
                                    log.info("FFT κ refined from phase slope over {} clean frames: Δκ={} bin → κ={}",
                                            kappaMeasFrames, String.format("%.5f", dKappa),
                                            String.format("%.5f", accumKFractional));
                                }
                            }
                        }
                    } else if (accumKFractional != 0.0) {
                        // Continuous phase-tracking loop (a PLL on the cross-tick
                        // fundamental).  Instead of trusting a frozen pinned κ — whose
                        // residual error ramps the de-rotated phase and slowly
                        // vector-cancels the fundamental — measure the running
                        // mis-alignment against the DEEP ACCUMULATED phase (the
                        // √N-averaged true reference; the accumulator rotates bin k0 by
                        // the same krot as obsPhase, so the two compare directly) and
                        // fold a FRACTION of it back into the de-rotation every tick.
                        // PHASE_TRACK_GAIN < 1 is the loop filter: it averages out the
                        // per-tick phase noise that would otherwise random-walk if
                        // folded whole.  This holds the fundamental flat with no
                        // pinned-κ drift, and a glitch is just a large residual the
                        // loop tracks out — replacing the fragile 8-frame gap-
                        // correction.  On a re-sync the delta jumped, so do a one-shot
                        // FULL realign of THIS frame too (gain 1) and let the loop
                        // clean up any single-frame residual afterwards.
                        double accumPhase = Math.atan2(accumIm[k0], accumRe[k0]);
                        double residual   = Math.IEEEremainder(obsPhase - accumPhase, 2.0 * Math.PI);
                        double gain       = gapRecoverPending ? 1.0 : PHASE_TRACK_GAIN;
                        double corr       = gain * residual * fftSize
                                          / (2.0 * Math.PI * accumKFractional);
                        accumDroppedSamples += corr;
                        if (gapRecoverPending) {
                            delta += corr;                 // realign THIS frame after the jump
                            gapRecoverPending = false;
                            if (log.isInfoEnabled()) {
                                log.info("FFT re-sync realigned: {} sample one-shot (Δφ={} rad vs accumulated ref); phase-lock loop continues",
                                        Math.round(corr), String.format("%.4f", residual));
                            }
                        }
                    }
                }
                // Single reference: PER-LOBE constant-phase de-rotation (the same
                // math as FftAnalyzer Pass 2, but over the HALF spectrum
                // [0, Nyquist]: every accumulator bin is a POSITIVE frequency, so —
                // unlike Pass 2's full fftSize array — there is NO negative-bin wrap
                // and the harmonics run all the way to Nyquist).  Each bin is snapped
                // to its nearest harmonic h = round(bin / k0) and rotated by that
                // lobe's CONSTANT phase h·Φ, Φ = −2π·delta·accumKFractional/N — NOT a
                // per-bin ramp.  The ramp is exact only at the harmonic bins and
                // over-rotates the leakage skirt between them ∝ (bin-offset × tick),
                // which the cross-tick sum turns into a comb on the skirt; constant
                // phase per lobe aligns the whole lobe so the skirt stays clean.
                final int    k0x   = Math.max(1, accumIntFundBinRounded);
                final double phiX  = -2.0 * Math.PI * delta * accumKFractional / (double) fftSize;
                final int    hMaxX = Math.max(1, halfSize / k0x);
                if (hcosScratch == null || hcosScratch.length != 2 * hMaxX + 1) {
                    hcosScratch = new double[2 * hMaxX + 1];
                    hsinScratch = new double[2 * hMaxX + 1];
                }
                final double e1cx = Math.cos(phiX), e1sx = Math.sin(phiX);
                final double[] hcx = hcosScratch, hsx = hsinScratch;
                hcx[hMaxX] = 1.0; hsx[hMaxX] = 0.0;
                for (int h = 1; h <= hMaxX; h++) {
                    hcx[hMaxX + h] = hcx[hMaxX + h - 1] * e1cx - hsx[hMaxX + h - 1] * e1sx;
                    hsx[hMaxX + h] = hcx[hMaxX + h - 1] * e1sx + hsx[hMaxX + h - 1] * e1cx;
                    hcx[hMaxX - h] =  hcx[hMaxX + h];           // exp(−j·h·Φ) = conjugate
                    hsx[hMaxX - h] = -hsx[hMaxX + h];
                }
                final int hmx   = hMaxX;
                // Parallelized across cores: the accumRe/accumIm writes are disjoint
                // per chunk; each bin looks up its lobe's cached phasor.  All bins
                // are positive frequencies (half spectrum), so the harmonic index is
                // round(k / k0x) directly — no signed-bin wrap.
                parallelChunks(N, (lo, hi) -> {
                    for (int k = lo; k < hi; k++) {
                        int h  = Math.round(k / (float) k0x);        // nearest harmonic lobe (k ≥ 0)
                        if (h > hmx) h = hmx;
                        double cr = hcx[h + hmx], ci = hsx[h + hmx];
                        double rRe = r.re[k] * weight;
                        double rIm = r.im[k] * weight;
                        accumRe[k] += rRe * cr - rIm * ci;
                        accumIm[k] += rRe * ci + rIm * cr;
                    }
                });
            }
        } else {
            // Incoherent analyze stores the RAW FFT magnitude in r.re (im = 0).
            // Sum its POWER — NOT the already-amplitude r.amplitudeDbFs — so the
            // mag→amplitude conv in overlayAccumulatorOnto (shared with the
            // coherent path) applies ONCE, not twice (the double conversion
            // buried the whole spectrum ~120 dB).
            for (int k = 0; k < N; k++) {
                double mag = r.re[k];
                accumPow[k] += mag * mag * weight;
            }
        }
        accumFrames += weight;
        return true;
    }

    /** Multi-tone cross-tick rotation: each detected strong tone's lobe is
     *  de-rotated by a CONSTANT phase equal to that tone's own inter-tick
     *  advance, so the lobe is preserved intact and the accumulated peak
     *  stays at the tone's true (sub-bin) frequency — no locking to the FFT
     *  grid.  Each tone carries its OWN phase-lock loop ({@link
     *  #toneDroppedSamples}) so a frozen-κ residual can't ramp its lobe out of
     *  phase over a long average — the per-tone analogue of the single-reference
     *  loop, so IMD tone pairs hold.  Bins outside every tone lobe get the plain
     *  time-shift ramp, which leaves broadband noise to average down.  This is
     *  the honest "measure what was sampled" path for unknown / external
     *  multi-tone signals: the tones are found from the spectrum, not assumed. */
    /** "Rotate the fork": pool the strong tones' shared clock-drift estimate and
     *  apply it to the non-tone bins too, so the weak harmonics / IMD products don't
     *  sink under drift.  Package-private + non-final so the cross-tick drift harness
     *  can A/B it; the intended production state is on. */
    static boolean FORK_NONTONE_DRIFT = true;
    /** Per-tone κ refine (multi-tone path).  Package-private + non-final so the
     *  cross-tick drift harness can A/B it; the intended production state is on. */
    static boolean MULTI_KAPPA_REFINE = true;

    private void accumulateMultiTone(FftResult r, int N, int weight, double delta) {
        int fftSize = r.fftSize;
        int nT = accumToneKappa.length;
        double[] cRe = new double[nT];
        double[] cIm = new double[nT];
        int[]    lo  = new int[nT];
        int[]    hi  = new int[nT];
        double[] angT = new double[nT];   // each tone's tracked de-rotation phase (for the IMD grid)
        // The strong tones share ONE clock: each tone's per-tone loop correction
        // measures the same relative drift δ ≈ correction/delta.  Pool them
        // (strength-weighted) below and de-rotate the NON-tone bins by delta·(1+δ).
        double dsum = 0.0, wsum = 0.0;
        // Per-tone κ refine (mirrors the single-reference one-shot refine): while
        // unrefined, leave the PLL OFF and measure each tone's de-rotated phase slope
        // vs the PINNED κ; fold Δκ into accumToneKappa once after KAPPA_MEAS_FRAMES.
        boolean kRefining = MULTI_KAPPA_REFINE && accumRe != null && accumFrames > 0 && !multiKappaRefined;
        boolean kAnchor   = kRefining && (multiKappaMeasFrames == 0 || multiKappaSkipNext);
        if (kRefining && multiKappaMeasFrames == 0) {   // first refine frame: (re)allocate per-tone phase state
            multiKappaCumPhase  = new double[nT];
            multiKappaLastPhase = new double[nT];
            multiKappaCleanSpan = 0.0;
        }
        for (int t = 0; t < nT; t++) {
            double kappa = accumToneKappa[t];
            int    k0    = Math.min(Math.max(0, (int) Math.round(kappa)), N - 1);
            // De-rotate this tone's lobe by its OWN frequency, advanced by the
            // tone's accumulated loop correction.  A frozen per-tone κ (the
            // single-frame parabolic estimate, ~0.01–0.05 bin off) would ramp the
            // lobe phase and vector-cancel it — the multi-tone analogue of the
            // single-reference drift.
            double effDelta = delta + toneDroppedSamples[t];
            double ang = -2.0 * Math.PI * effDelta * kappa / (double) fftSize;
            double cr = Math.cos(ang), sr = Math.sin(ang);
            // Per-tone phase-lock loop: compare this tone's de-rotated phase to
            // the DEEP accumulated phase at its bin (the √N-averaged reference,
            // rotated by the same ang so the two compare directly) and fold a
            // fraction of the residual into the tone's correction.  The same loop
            // that holds the single-tone fundamental, run once per tone.  Skipped
            // on the first contributing frame (no reference yet).  On a re-sync
            // (gapRecoverPending) do a one-shot FULL realign of THIS frame too.
            if (accumRe != null && accumFrames > 0 && kappa > 0.0) {
                double obsPhase = Math.atan2(r.re[k0] * sr + r.im[k0] * cr,
                                             r.re[k0] * cr - r.im[k0] * sr);
                if (kRefining) {
                    // κ refine: accumulate this tone's clean phase step (pinned-κ
                    // de-rotation; the anchor / re-anchor frame only sets the reference).
                    if (kAnchor) {
                        multiKappaLastPhase[t] = obsPhase;
                    } else {
                        multiKappaCumPhase[t] += Math.IEEEremainder(obsPhase - multiKappaLastPhase[t], 2.0 * Math.PI);
                        multiKappaLastPhase[t] = obsPhase;
                    }
                } else {
                    // Per-tone phase-lock loop: fold a fraction of the running
                    // mis-alignment vs the DEEP accumulated phase into the tone's
                    // correction.  A residual far beyond the per-tick noise is a phase
                    // DISCONTINUITY (DDS jump / reconnect) the window gate missed — snap
                    // (gain 1) so the frame adds at its new phase instead of smearing.
                    // On a re-sync (gapRecoverPending) do a one-shot FULL realign too.
                    double accumPhase = Math.atan2(accumIm[k0], accumRe[k0]);
                    double residual   = Math.IEEEremainder(obsPhase - accumPhase, 2.0 * Math.PI);
                    boolean jump = Math.abs(residual) > PHASE_JUMP_RAD;
                    double gain  = (gapRecoverPending || jump) ? 1.0 : PHASE_TRACK_GAIN;
                    double corr  = gain * residual * fftSize / (2.0 * Math.PI * kappa);
                    toneDroppedSamples[t] += corr;
                    if (gapRecoverPending || jump) {
                        ang = -2.0 * Math.PI * (effDelta + corr) * kappa / (double) fftSize;
                        cr  = Math.cos(ang);
                        sr  = Math.sin(ang);
                        if (jump && !gapRecoverPending && log.isInfoEnabled()) {
                            log.info("FFT dual-tone discontinuity: tone {} (~{} Hz) phase jump {} rad — realigned",
                                    t, String.format("%.1f", kappa * r.sampleRate / (double) fftSize),
                                    String.format("%.3f", residual));
                        }
                    }
                }
            }
            angT[t] = ang;
            cRe[t] = cr;
            cIm[t] = sr;
            lo[t] = Math.max(0,     k0 - TONE_LOBE_HALF_BINS);
            hi[t] = Math.min(N - 1, k0 + TONE_LOBE_HALF_BINS);
            if (FORK_NONTONE_DRIFT && accumRe != null && accumFrames > 0 && Math.abs(delta) > 1.0) {
                double w = Math.hypot(accumRe[k0], accumIm[k0]);   // strength weight
                dsum += w * toneDroppedSamples[t] / delta;
                wsum += w;
            }
        }
        // One re-sync realign serves every tone; clear after they've all used it.
        gapRecoverPending = false;
        // IMD-product grid (true dual-tone): each product a·F1+b·F2 rides
        // a·ang1 + b·ang2 — the SAME tracked tone phases — so its EXACT sub-bin
        // frequency (clock offset / wobble included) is de-rotated, not the integer
        // bin-centre.  Without this the off-bin products carry a (bin − κ_p)·delta
        // ramp that drifts (slow when aligned, wild at a raw ppm offset).
        int[] prodIdx = null;
        double[] prodCos = null, prodSin = null;
        if (nT == 2 && accumToneKappa[0] > 0.0 && accumToneKappa[1] > 0.0) {
            final int ORDER = 9;
            int[] pa = new int[(2 * ORDER + 1) * (2 * ORDER + 1)];
            int[] pb = new int[pa.length];
            int[] pk = new int[pa.length];
            int cap = 0;
            for (int a = -ORDER; a <= ORDER; a++) {
                for (int b = -ORDER; b <= ORDER; b++) {
                    int ord = Math.abs(a) + Math.abs(b);
                    if (ord < 2 || ord > ORDER) continue;   // tones (ord 1) handled per-tone above
                    double kp = a * accumToneKappa[0] + b * accumToneKappa[1];
                    if (kp < 1.0 || kp > N - 2) continue;
                    pk[cap] = (int) Math.round(kp);
                    pa[cap] = a;
                    pb[cap] = b;
                    cap++;
                }
            }
            prodCos = new double[cap];
            prodSin = new double[cap];
            for (int p = 0; p < cap; p++) {
                double ph = pa[p] * angT[0] + pb[p] * angT[1];
                prodCos[p] = Math.cos(ph);
                prodSin[p] = Math.sin(ph);
            }
            if (imdGridIdx == null || imdGridIdx.length < N) imdGridIdx = new int[N];
            prodIdx = imdGridIdx;
            Arrays.fill(prodIdx, 0, N, -1);
            for (int p = 0; p < cap; p++) {
                int plo = Math.max(0,     pk[p] - TONE_LOBE_HALF_BINS);
                int phi = Math.min(N - 1, pk[p] + TONE_LOBE_HALF_BINS);
                for (int k = plo; k <= phi; k++) prodIdx[k] = p;
            }
        }
        // Non-tone, non-product bins: plain time-shift, drift-corrected by the
        // pooled δ so the weak harmonics track the same clock as the strong teeth.
        double deltaEff  = wsum > 0.0 ? delta * (1.0 + dsum / wsum) : delta;
        double rampSlope = -2.0 * Math.PI * deltaEff / (double) fftSize;
        double stepRe = Math.cos(rampSlope), stepIm = Math.sin(rampSlope);
        final int[]    fProdIdx = prodIdx;
        final double[] fProdCos = prodCos;
        final double[] fProdSin = prodSin;
        // Parallel like the single-reference accumulate: chunks write
        // disjoint bins; each seeds its own fork phasor exp(j·kLo·rampSlope)
        // (one cos/sin — also a SHORTER recurrence chain than the old full-
        // length serial one) and its own tone-lobe cursor.
        parallelChunks(N, (kLo, kHi) -> {
            double phRe = Math.cos(rampSlope * kLo);
            double phIm = Math.sin(rampSlope * kLo);
            int curT = 0;
            while (curT < nT && kLo > hi[curT]) curT++;
            for (int k = kLo; k < kHi; k++) {
                while (curT < nT && k > hi[curT]) curT++;
                double rotRe, rotIm;
                int p = (fProdIdx != null) ? fProdIdx[k] : -1;
                if (curT < nT && k >= lo[curT]) { rotRe = cRe[curT];    rotIm = cIm[curT];    }   // tone lobe
                else if (p >= 0)                { rotRe = fProdCos[p];  rotIm = fProdSin[p];  }   // IMD product
                else                            { rotRe = phRe;         rotIm = phIm;         }   // fork
                double rRe = r.re[k] * weight;
                double rIm = r.im[k] * weight;
                accumRe[k] += rRe * rotRe - rIm * rotIm;
                accumIm[k] += rRe * rotIm + rIm * rotRe;
                double nextRe = phRe * stepRe - phIm * stepIm;
                phIm = phRe * stepIm + phIm * stepRe;
                phRe = nextRe;
            }
        });
        // Per-tone κ refine: advance the shared frame count / clean span; once the
        // window is full fold each tone's Δκ (its phase slope) into accumToneKappa,
        // so the tones AND the IMD grid (a·κ1+b·κ2) lock from the next frame on.
        if (kRefining) {
            if (kAnchor) {
                if (multiKappaMeasFrames == 0) {
                    multiKappaMeasFrames = 1;
                }
                multiKappaLastDelta = delta;
                multiKappaSkipNext  = false;
            } else {
                multiKappaCleanSpan += delta - multiKappaLastDelta;
                multiKappaLastDelta  = delta;
                if (++multiKappaMeasFrames >= KAPPA_MEAS_FRAMES && multiKappaCleanSpan > 0.0) {
                    for (int t = 0; t < nT; t++) {
                        accumToneKappa[t] += multiKappaCumPhase[t] * fftSize
                                / (2.0 * Math.PI * multiKappaCleanSpan);
                    }
                    multiKappaRefined = true;
                    if (log.isInfoEnabled()) {
                        log.info("FFT multi-tone κ refined from phase slope over {} clean frames ({} tones)",
                                multiKappaMeasFrames, nT);
                    }
                }
            }
        }
    }

    /** Finds the fractional bins of the "strong" tones in {@code r}'s
     *  spectrum — local maxima within {@link #STRONG_TONE_REL_DB} dB of the
     *  strongest peak, merged within {@link #MIN_TONE_SEP_BINS} and refined
     *  to sub-bin by parabolic interpolation, sorted ascending.  Detection
     *  from the spectrum (not commanded frequencies) is deliberate: the
     *  generator may be external, so the FFT is the only thing that knows
     *  what was actually received. */
    private double[] detectStrongTones(FftResult r) {
        double[] db = r.amplitudeDbFs;
        if (db == null) return new double[0];
        int halfSize = r.fftSize / 2;
        if (halfSize < 4) return new double[0];
        // Skip the DC/ULF zone: a residual DC offset leaks through the window's
        // main lobe into the first bins (bin 3 ≈ 0.55 Hz at 2 M / 384 kHz) and
        // would be picked up as a spurious "second tone".  Ignore everything
        // below 10 Hz — well under any real tone, and the same guard the
        // analyzer's fundamental search uses.
        double freqRes = (double) r.sampleRate / r.fftSize;
        int    minBin  = Math.max(2, (int) Math.ceil(DC_REJECT_HZ / freqRes));
        // A peak counts as a separate TONE only if it is within
        // fftStrongToneRelDb of the STRONGEST peak.  A clean tone's harmonics
        // sit far below it (often >100 dB) so they're excluded and the signal
        // stays on the single-reference phase-lock path; genuine dual-tone / IMD
        // partners are comparable in level and survive.
        double strongest = -Double.MAX_VALUE;
        for (int k = minBin; k < halfSize; k++) {
            if (db[k] > db[k - 1] && db[k] >= db[k + 1] && db[k] > strongest) strongest = db[k];
        }
        double thresh = strongest - Preferences.instance().getFftStrongToneRelDb();
        // Keep the strongest MAX_TONES local maxima above the threshold, merging
        // peaks closer than MIN_TONE_SEP_BINS (one tone's lobe).  Selecting by
        // strength (not scan order) keeps the real tones even when 1/f or the
        // HF-rise noise also clears the threshold.
        int[]    bins = new int[MAX_TONES];
        double[] lvls = new double[MAX_TONES];
        int count = 0;
        for (int k = minBin; k < halfSize; k++) {
            if (db[k] < thresh) continue;
            if (!(db[k] > db[k - 1] && db[k] >= db[k + 1])) continue;   // local max
            double lvl = db[k];
            int near = -1;
            for (int t = 0; t < count; t++) {
                if (Math.abs(k - bins[t]) < MIN_TONE_SEP_BINS) { near = t; break; }
            }
            if (near >= 0) {
                if (lvl > lvls[near]) { bins[near] = k; lvls[near] = lvl; }
            } else if (count < MAX_TONES) {
                bins[count] = k; lvls[count] = lvl; count++;
            } else {
                int weakest = 0;
                for (int t = 1; t < count; t++) if (lvls[t] < lvls[weakest]) weakest = t;
                if (lvl > lvls[weakest]) { bins[weakest] = k; lvls[weakest] = lvl; }
            }
        }
        // Harmonic rejection: a single tone's harmonics (2f, 3f, …) clear the
        // floor and would otherwise be taken as independent tones, forcing the
        // per-tone multi-tone path (frozen parabolic κ, NO phase-slope refine —
        // the long-run drift).  A harmonic sits at an integer multiple h≥2 of the
        // strongest tone AND well below it (distortion); the single-reference
        // time-shift already aligns it via h×, so drop it — leaving a single tone
        // + harmonics on the refined single-ref path.  Genuine inharmonic IMD
        // partners aren't integer multiples and aren't far below, so they survive.
        if (count >= 2) {
            int fund = 0;
            for (int i = 1; i < count; i++) if (lvls[i] > lvls[fund]) fund = i;
            double f0bin = bins[fund];
            int kept = 0;
            for (int i = 0; i < count; i++) {
                boolean harmonic = false;
                if (i != fund && f0bin > 0.0) {
                    double ratio = bins[i] / f0bin;
                    long   h     = Math.round(ratio);
                    harmonic = h >= 2
                            && Math.abs(ratio - h) * f0bin < MIN_TONE_SEP_BINS
                            && lvls[i] < lvls[fund] - HARMONIC_REJECT_BELOW_DB;
                }
                if (!harmonic) { bins[kept] = bins[i]; lvls[kept] = lvls[i]; kept++; }
            }
            count = kept;
        }
        // Sort the kept tones by bin ascending (needed by the region walk).
        for (int i = 1; i < count; i++) {
            int b = bins[i]; double l = lvls[i]; int j = i - 1;
            while (j >= 0 && bins[j] > b) { bins[j + 1] = bins[j]; lvls[j + 1] = lvls[j]; j--; }
            bins[j + 1] = b; lvls[j + 1] = l;
        }
        double[] out = new double[count];
        for (int i = 0; i < count; i++) out[i] = refineBin(db, bins[i], halfSize);
        return out;
    }

    /** 3-point parabolic peak interpolation (on dB) around bin {@code k}. */
    private double refineBin(double[] db, int k, int halfSize) {
        if (k <= 1 || k >= halfSize) return k;
        double yl = db[k - 1], yc = db[k], yr = db[k + 1];
        double denom = yl - 2.0 * yc + yr;
        double d = (Math.abs(denom) < 1e-12) ? 0.0 : 0.5 * (yl - yr) / denom;
        if (d < -0.5) d = -0.5;
        if (d >  0.5) d =  0.5;
        return k + d;
    }

    /** Mutates {@code r}'s re/im/amplitudeDbFs/phaseDeg arrays in place to
     *  reflect the cumulative accumulator average.  After this, the caller
     *  should run {@link FftAnalyzer#recomputeStats} so the fundamental,
     *  harmonic table, THD/SNR are re-derived from the cumulative spectrum
     *  (the per-tick stats were just one tick's contribution). */
    void overlayAccumulatorOnto(FftResult r) {   // package-private for the cross-tick drift harness
        if (!accumHasData || accumFrames <= 0) return;
        int halfSize = r.fftSize / 2;
        int N        = halfSize + 1;

        // Derive the (mag → amplitude) conversion factor used by analyze()
        // from the pre-overlay r itself: amplLin = hypot(re,im) · normFactor·2
        // for non-DC/Nyquist bins.  Picking the fundamental bin guarantees
        // a high-SNR sample for the ratio; the factor depends only on the
        // window's coherent gain (= constant for a given fftSize/window).
        int kFund = Math.max(1, (int) Math.round(
                r.fundamentalHzRefined / r.freqResolution));
        if (kFund > halfSize) kFund = halfSize;
        double magFund = Math.hypot(r.re[kFund], r.im[kFund]);
        double ampFund = r.amplitudeDbFs[kFund] > -290.0
                ? Math.pow(10.0, r.amplitudeDbFs[kFund] / 20.0)
                : 0.0;
        if (magFund < 1e-30 || ampFund < 1e-30) return;
        double conv = ampFund / magFund;   // normFactor · 2 (for non-DC/Nyquist)

        // Per-bin and independent → parallelize the dB rebuild (1M× log10/atan2)
        // across cores; disjoint k-writes, no recursion.
        if (accumCoherent) {
            parallelChunks(N, (lo, hi) -> {
                for (int k = lo; k < hi; k++) {
                    double avgRe = accumRe[k] / accumFrames;
                    double avgIm = accumIm[k] / accumFrames;
                    r.re[k] = avgRe;
                    r.im[k] = avgIm;
                    // sqrt form, not Math.hypot: hypot's over/underflow
                    // armour costs 5-10× and FFT magnitudes live mid-range;
                    // same form the analyzer's own magnitude pass uses.
                    double mag = Math.sqrt(avgRe * avgRe + avgIm * avgIm);
                    double scale = (k == 0 || k == halfSize) ? 0.5 : 1.0;
                    double amp = mag * conv * scale;
                    r.amplitudeDbFs[k] = amp > 1e-15 ? 20.0 * Math.log10(amp) : -300.0;
                    r.phaseDeg[k] = Math.toDegrees(Math.atan2(avgIm, avgRe));
                }
            });
        } else {
            parallelChunks(N, (lo, hi) -> {
                for (int k = lo; k < hi; k++) {
                    double avgPow = accumPow[k] / accumFrames;
                    double mag = Math.sqrt(avgPow);
                    double scale = (k == 0 || k == halfSize) ? 0.5 : 1.0;
                    double amp = mag * conv * scale;
                    r.amplitudeDbFs[k] = amp > 1e-15 ? 20.0 * Math.log10(amp) : -300.0;
                    // Match analyze()'s incoherent convention: re holds magnitude,
                    // im = 0 (phase is undefined for power averaging).
                    r.re[k] = amp;
                    r.im[k] = 0.0;
                    r.phaseDeg[k] = 0.0;
                }
            });
        }
    }

    // ─── External wiring ────────────────────────────────────────────────────

    /** Cumulative frame count accumulated across all forever-mode ticks
     *  since the last reset.  Returns 0 outside forever mode or before
     *  any contribution.  This is the meaningful "N average(s)" depth
     *  for forever mode — equal to the SNR-improvement factor √N². */
    public int  getAccumulatedFrames()          { return accumFrames; }
    public boolean isPaused()                   { return paused.get(); }

    // ─── Lifecycle ──────────────────────────────────────────────────────────

    /** Starts the analyser on a daemon worker thread.  Idempotent. */
    public synchronized void start() {
        if (running) return;
        // The worker owns its capture: acquire a SharedCapture reader here so
        // neither the view nor the pane has to handle the sample buffer.  A
        // null result means the input device is unavailable — stay stopped so
        // the caller (which checks isRunning) can revert its Record button.
        SignalBufferReader r = MessageBus.instance().request(Events.CAPTURE_ACQUIRE);
        if (r == null) return;
        this.reader      = r;
        this.captureHeld = true;
        running = true;
        // Re-arm the first-frame regime + drop any stale spectrum so the chart
        // starts blank.  reanchorPending makes the worker seek the reader to
        // "now" on its next tick, so the first analysis window is built from a
        // FULL needed-sample span of FRESH samples captured AFTER this point.
        firstFrameDone        = false;
        winValid              = false;
        reanchorPending       = true;
        completedAnalyses     = 0;
        resetStatistics();
        paused.set(false);
        MessageBus bus = MessageBus.instance();
        bus.subscribe(Events.GENERATOR_SIGNAL_CHANGED, invalidateOnGenChange);
        bus.subscribe(Events.FFT_CALIBRATION_CHANGED, invalidateOnEvent);
        worker = new Thread(this::workerLoop, "fft-analyzer");
        worker.setDaemon(true);
        worker.start();
    }

    /** Stops the analyser and interrupts the worker.  Idempotent.  Joins the
     *  worker (it exits within one tick — the interrupt breaks any sleep) so
     *  the teardown below can't race a tick still in flight. */
    public synchronized void stop() {
        running = false;
        Thread w = worker;
        worker = null;
        if (w != null) {
            w.interrupt();
            try {
                w.join(3_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        MessageBus bus = MessageBus.instance();
        bus.unsubscribe(Events.GENERATOR_SIGNAL_CHANGED, invalidateOnGenChange);
        bus.unsubscribe(Events.FFT_CALIBRATION_CHANGED, invalidateOnEvent);
        if (w == null || !w.isAlive()) {
            discardCacheAndPool();
            resetAccumulator();
            // Reclaim an undrained slot, then drop the pool — at fftSize
            // 4 M the slots idle on ~100 MB of spectrum arrays while
            // nothing is recording.  (No race with the drain runnable:
            // both run on the UI thread and both use getAndSet(null).)
            resultPool.release(latestForUi.getAndSet(null));
            resultPool.clear();
        } else {
            // A monster-FFT tick outlived the join: leave its state alone and
            // let the next start's pending reset wipe it on the worker side.
            log.warn("FFT worker did not exit within 3 s — cache/accumulator cleanup deferred to the next start.");
            resetPending.set(true);
        }
        reader = null;
        if (captureHeld) {
            bus.publish(Events.CAPTURE_RELEASE);
            captureHeld = false;
        }
    }

    /** Clears the completed-analyses counter, drops the retained
     *  spectrum, and resumes the loop if it was paused by stop-after-N.
     *  Safe to call from any thread.  Does NOT post the redraw — the
     *  caller does that. */
    public void resetStatistics() {
        resetEpoch.incrementAndGet();   // first: a mid-tick analysis must see it
        paused.set(false);
        completedAnalyses     = 0;
        firstFrameDone        = false;
        winValid              = false;
        reanchorPending       = true;
        recycleAndClearCache();         // lock-guarded — safe from any thread
        if (running) {
            // Worker-owned state (accumulator, mains tracking) is wiped by the
            // worker itself at the next tick boundary — see resetPending.
            resetPending.set(true);
        } else {
            resetWorkerOwnedState();    // no worker thread to race with
        }
    }

    /** {@link #resetStatistics()} variant for resets caused by a change of
     *  the GENERATED SIGNAL (user input on the generator pane, generator
     *  start/stop): additionally arms the post-re-anchor drain skip so the
     *  first window holds none of the old tone still flowing out of the
     *  DAC's hardware buffer — see {@link #OUTPUT_DRAIN_SKIP_SEC}.
     *  Setting-only resets (window / channel / fftLength) use plain
     *  {@link #resetStatistics()}: the signal didn't change, so there is
     *  nothing to drain. */
    public void resetStatisticsAfterSignalChange() {
        drainSkipPending.set(true);     // before resetStatistics: its reanchorPending publishes this too
        resetStatistics();
    }

    /** Current reset epoch — compare against {@link FftResult#epoch} to
     *  detect a result produced before the latest reset (e.g. one that sat
     *  parked in the coalescing UI hand-off across a signal change). */
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
        mainsTrackTick = 0;             // re-lock mains on the very next tick
        if (mainsComb != null) mainsComb.resetTracking();   // re-lock mains from scratch
    }

    // ─── Status queries ─────────────────────────────────────────────────────

    /** True when the audio generator is currently producing a signal.
     *  Resolved via {@link MessageBus} — the generator pane registers a
     *  responder for {@link Events#GENERATOR_RUNNING}.  Defaults to
     *  {@code true} when no responder is registered. */
    public boolean isGeneratorActive() {
        Boolean answer = MessageBus.instance().request(Events.GENERATOR_RUNNING);
        return answer == null || answer;
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
        FftOverlap overlap = prefs.getFftOverlap();
        double hop = Math.max(1, fftLength * (1.0 - overlap.fraction));
        // Building the first window needs a full `needed`; once it's valid each
        // tick just needs one fresh hop, so the bar sweeps 0→1 per hop.
        double want = winValid ? hop : (winNeeded > 0 ? winNeeded : fftLength);
        double f = avail / want;
        return (f < 0) ? 0 : (f > 1 ? 1 : f);
    }

    // ─── Worker loop ────────────────────────────────────────────────────────

    /** Worker-thread body.  Runs analysis on a background thread, posts
     *  the redraw via {@link Display#asyncExec} so the SWT thread isn't
     *  blocked by the FFT compute. */
    private void workerLoop() {
        while (running) {
            int sleepMs = IDLE_TICK_MS;
            if (!paused.get()) {
                try {
                    sleepMs = doAnalysis();
                } catch (Throwable t) {
                    if (log.isWarnEnabled()) {
                        log.warn("FFT analysis tick failed", t);
                    }
                    sleepMs = IDLE_TICK_MS;
                }
            }
            if (sleepMs <= 0) sleepMs = IDLE_TICK_MS;
            if (sleepMs > MAX_SLEEP_MS) sleepMs = MAX_SLEEP_MS;
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** Performs at most one analysis pass.  Returns the suggested sleep
     *  in milliseconds before the next attempt — derived from the
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
        }
        // Stamp AFTER the transition reset above so this tick doesn't
        // discard itself; see the epoch check below the analyze call.
        final long epochAtStart = resetEpoch.get();

        // Single per-tick snapshot: stop() nulls the field (and a quick
        // stop→start swaps in a NEW cursor) while a tick is in flight —
        // re-reading the field mid-tick would let an outgoing worker consume
        // from the new worker's cursor.
        final SignalBufferReader rdr = reader;
        if (rdr == null) return IDLE_TICK_MS;
        int sampleRate = rdr.getSampleRate();
        if (sampleRate <= 0) return IDLE_TICK_MS;

        Preferences prefs = Preferences.instance();
        int fftLength = prefs.getFftLength();
        if (fftLength < 8 || (fftLength & (fftLength - 1)) != 0) return IDLE_TICK_MS;

        double avgRaw = prefs.getFftAverages();
        boolean foreverMode = Double.isInfinite(avgRaw);
        int     ringN       = Math.max(1, (int) avgRaw);
        // Cross-tick accumulate whenever any averaging is requested.  Each tick
        // then FFTs only a small fixed number of fresh frames (not the whole
        // ring); the running cross-tick accumulator supplies the depth.  This
        // makes a "200×" ring reach its full depth (instead of being capped by
        // what fits in the capture buffer) and stops it re-FFTing ~40 frames
        // every tick.
        boolean accumulate = foreverMode || ringN >= 2;
        int averages = accumulate ? 2 : 1;
        // Frame-depth cap for the bounded (ring) accumulator.  ≈2 frames are
        // contributed per tick, and the on-screen "N×" count is tick-based, so
        // cap at 2·N frames: the floor keeps deepening for the whole time the
        // count climbs to N (no "stuck at half" artefact), matching the
        // forever-mode "≈2× displayed N frames" convention.
        int targetFrames = foreverMode ? Integer.MAX_VALUE : 2 * ringN;

        WindowType window = prefs.getFftWindow();

        FftOverlap overlap = prefs.getFftOverlap();

        double hop = fftLength * (1.0 - overlap.fraction);
        if (hop < 1) hop = 1;
        int hopSamples = (int) Math.max(1, Math.round(hop));
        int needed = (int) Math.ceil(fftLength + (averages - 1) * hop);
        needed = Math.min(needed, rdr.getCapacity());

        Channel channel = prefs.getFftChannel();
        boolean wantLeft = (channel == Channel.L);

        // Re-anchor on (re)start / reset so the sliding window is rebuilt only
        // from FRESH samples captured after this point (no ghosting after the
        // generator stops).  Done on the worker thread to keep the cursor's
        // read position single-owner even though resets arrive from others.
        if (reanchorPending) {
            rdr.seekToLatest();
            reanchorPending = false;
            winValid = false;
            if (drainSkipPending.getAndSet(false)) {
                drainSkipRemaining = (long) Math.ceil(OUTPUT_DRAIN_SKIP_SEC * sampleRate);
            }
        }
        // Output-drain skip: after a signal-change re-anchor, consume and
        // discard the span still carrying the old tone (the DAC buffer
        // keeps it flowing into the ADC after the change) so the first
        // window — and the FLL's first measurement — see only the new
        // signal.
        if (drainSkipRemaining > 0) {
            long skipAvail = rdr.available();
            if (skipAvail > 0) {
                int got = rdr.read((int) Math.min(skipAvail, drainSkipRemaining), (double[]) null, (double[]) null);
                if (got > 0) drainSkipRemaining -= got;
            }
            if (drainSkipRemaining > 0) {
                return msForSamples((int) Math.min(drainSkipRemaining, Integer.MAX_VALUE), sampleRate);
            }
        }
        // A change in window size (fftLength / overlap / averages) or channel
        // forces a rebuild from a fresh contiguous span.
        if (winNeeded != needed || winChannelLeft != wantLeft) {
            winValid       = false;
            winNeeded      = needed;
            winChannelLeft = wantLeft;
            if (winBuf.length != needed) winBuf = new double[needed];
        }

        long avail = rdr.available();
        if (avail == SignalBufferReader.OVERRUN) {
            // The writer lapped the cursor — the contiguous stream tore (the
            // worker fell a full ring behind).  Drop the cross-tick accumulator
            // + counters, invalidate the window, and re-anchor: deep averaging
            // restarts from a fresh unbroken span (overlap can only resume once
            // there are no breaks).  A reset the user can SEE — not a silent
            // torn-read glitch.
            onCaptureOverrun(rdr);
            return IDLE_TICK_MS;
        }

        long samplesAbsStart;
        if (!winValid) {
            // Build the first window from one complete `needed`-sample span.
            if (avail < needed) {
                return Math.max(20, msForSamples((int) (needed - avail), sampleRate));
            }
            int got = rdr.read(needed, wantLeft ? winBuf : null, wantLeft ? null : winBuf);
            if (got == SignalBufferReader.OVERRUN) { onCaptureOverrun(rdr); return IDLE_TICK_MS; }
            if (got < needed) return IDLE_TICK_MS;        // gated by avail≥needed; defensive
            winLen      = got;
            winAbsStart = rdr.getReadPos() - got;
            winValid    = true;
        } else {
            // Slide forward exactly one hop: pull the fresh hop, drop the oldest
            // hop, append it.  Uniform hop ⇒ uniform cross-tick de-rotation
            // delta, gap-free across ticks (the contiguous-stream fix).
            if (avail < hopSamples) {
                return Math.max(20, msForSamples(hopSamples - (int) avail, sampleRate));
            }
            if (hopBuf.length != hopSamples) hopBuf = new double[hopSamples];
            int got = rdr.read(hopSamples, wantLeft ? hopBuf : null, wantLeft ? null : hopBuf);
            if (got == SignalBufferReader.OVERRUN) { onCaptureOverrun(rdr); return IDLE_TICK_MS; }
            if (got < hopSamples) return IDLE_TICK_MS;    // gated by avail≥hop; defensive
            System.arraycopy(winBuf, hopSamples, winBuf, 0, winLen - hopSamples);
            System.arraycopy(hopBuf, 0, winBuf, winLen - hopSamples, hopSamples);
            winAbsStart += hopSamples;
        }
        samplesAbsStart = winAbsStart;

        // Per-tick analysis copy: the comb / FFT window mutate the samples in
        // place, so the retained sliding window must NOT be handed to analyze.
        if (analyzeBuf.length != winLen) analyzeBuf = new double[winLen];
        System.arraycopy(winBuf, 0, analyzeBuf, 0, winLen);

        // Cache invalidation: any change to fftLength / window / channel /
        // sampleRate makes previously cached raw FFTs stale.  Drop both
        // the cache AND the pool (pooled arrays would also be the wrong
        // size after an fftLength change).
        // The IIR comb is a plot-time correction (doesn't touch samples), so it
        // stays OUT of the fingerprint.  The SYNC_SUBTRACT / LMS modes filter the
        // samples in place before the FFT, so THEIR mode IS in the fingerprint:
        // toggling one invalidates the raw-frame cache and resets the accumulator.
        MainsSuppression mainsMode = prefs.getFftMainsSuppression();
        boolean mainsSuppress   = mainsMode == MainsSuppression.IIR_COMB;          // plot-time comb
        boolean mainsTimeDomain = mainsMode == MainsSuppression.SYNC_SUBTRACT
                               || mainsMode == MainsSuppression.LMS;                // pre-FFT filter
        long cfgFingerprint = fftLength;
        cfgFingerprint = 31 * cfgFingerprint + sampleRate;
        cfgFingerprint = 31 * cfgFingerprint + window.ordinal();
        cfgFingerprint = 31 * cfgFingerprint + (wantLeft ? 1 : 0);
        cfgFingerprint = 31 * cfgFingerprint + (mainsTimeDomain ? mainsMode.ordinal() : 0);
        if (cfgFingerprint != frameCacheFingerprint) {
            discardCacheAndPool();
            // Same config change invalidates the cross-tick accumulator
            // (different fftSize/window/channel would make the cumulative
            // spectrum meaningless).
            resetAccumulator();
            frameCacheFingerprint = cfgFingerprint;
        }
        // Memory-aware cap.  A single cached entry is 2 × fftSize × 8 B
        // (re[] + im[]), so at fftSize = 2 M each entry is 32 MB and at
        // 4 M it's 64 MB.  Capping the count alone (e.g. 2 × averages)
        // means a high averages-count + large fftSize trivially burns
        // gigabytes.  Instead, divide a fixed byte budget by the per-entry
        // size, and never exceed averages + 2 (which is all the ring
        // ever actually needs).
        long perEntryBytes = 2L * fftLength * Double.BYTES;
        int budgetCap = (int) Math.max(2L,
                FRAME_CACHE_BYTE_BUDGET / Math.max(1L, perEntryBytes));
        frameCacheCap = Math.max(2, Math.min(averages + 2, budgetCap));

        // The pristine sliding window was copied into analyzeBuf above; the
        // comb / FFT window mutate this copy in place, never winBuf.
        double[] samples = analyzeBuf;

        int    calcMaxH = Math.max(9, prefs.getFftCalcMaxHarmonic()) - 1;
        double distMin  = prefs.isFftDistMinEnabled() ? prefs.getFftDistMinHz() : 0;
        double distMax  = prefs.isFftDistMaxEnabled() ? prefs.getFftDistMaxHz() : 0;
        boolean coherent = prefs.isFftCoherentAveraging();
        boolean genActive = isGeneratorActive();
        // Manual fundamental is a user-DECLARED true level — it applies to ANY source
        // (external amplifier as much as our own generator), so it must NOT be gated on
        // genActive; resolveFundRefDbFs already returns NaN unless the user enabled it.
        // (genActive still gates the generator FREQUENCY hints below, which do need it.)
        double fundRefDbFs = resolveFundRefDbFs(prefs);
        // The coherent de-rotation locks onto the detected fundamental, so
        // in dual-tone the hint must point at a real tone (the lower one).
        // Hinting the single-tone frequency — usually nowhere near F1/F2 —
        // pegs the reference to a noise bin that jitters tick-to-tick, which
        // smears BOTH tones and makes the measured frequencies (and Δf
        // readout) swing wildly even though the generator never moves.
        boolean dualTone = prefs.getGenSignalForm().isDualTone();
        double expectedFundHz = (genActive && prefs.isFftFundFromGenerator())
                ? (dualTone
                    ? Math.min(prefs.getGenDualToneFreq1Hz(), prefs.getGenDualToneFreq2Hz())
                    : prefs.getGenFrequencyHz())
                : Double.NaN;

        // Mains suppression: only TRACK the live mains fundamental here (a
        // second of samples gives a mHz-accurate estimate) so the comb stays
        // locked to 50/60 Hz.  The signal is deliberately NOT filtered in the
        // time domain — combing each frame convolves the comb's transient/phase
        // into it, which the coherent average can't undo (drift, worst when a
        // mains harmonic sits on a measured tone).  Instead the comb's
        // normalized frequency response is divided out of the AVERAGED spectrum
        // at plot time (applyMainsCorrection), leaving the accumulator raw —
        // like the .frc cal, but independent of it.  Input stays pristine, so
        // the per-frame cache works normally.
        if (mainsSuppress) {
            // Re-track every Nth tick only — see MAINS_TRACK_TICK_INTERVAL.
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
        // A pre-FFT mains filter makes each frame non-deterministic (its state
        // adapts tick to tick), so the raw-frame cache is bypassed in that mode.
        analyzer.setFrameCache(mainsTimeDomain ? null : frameFftCacheImpl);

        analyzer.setSamplesAbsStart(samplesAbsStart);
        // Dual-tone: hint the upper tone too, so the analyzer produces an
        // honest sub-bin frequency estimate for it from a clean frame — the
        // dual-tone readout then reports true frequencies instead of reading
        // peaks off the coherently-collapsed average (see ImdAnalyzer).
        analyzer.setSecondToneHintHz(
                (genActive && prefs.isFftFundFromGenerator() && dualTone)
                        ? Math.max(prefs.getGenDualToneFreq1Hz(), prefs.getGenDualToneFreq2Hz())
                        : Double.NaN);
        // Dual tone breaks the single-sine R-invariant glitch test, so skip
        // it (it would otherwise flag nearly every sample and self-invalidate).
        analyzer.setMultiTone(dualTone);
        // While cross-tick averaging, the single-tick THD / SNR / noise-floor
        // stats are discarded and re-derived by recomputeStats() on the
        // accumulated spectrum below — so tell analyze to skip those sweeps
        // (incl. the two O(N log N) noise sorts) and only produce the spectrum
        // + fundamental/harmonic bins.  Single-tick mode keeps the full stats.
        analyzer.setSpectrumOnly(accumulate);
        FftResult slot = resultPool.acquire();
        FftResult r;
        try {
            r = analyzer.analyze(samples, sampleRate, fftLength, calcMaxH,
                    window, overlap, distMin, distMax, coherent, fundRefDbFs,
                    false, expectedFundHz, slot);
        } catch (RuntimeException ex) {
            resultPool.release(slot);
            return IDLE_TICK_MS;
        }
        // Pooled slot: clear the conditionally-written debug fields so a tick
        // that doesn't reach their assignment below can't republish a
        // previous tick's snapshots.
        r.gates         = null;
        r.gateBlockDbFs = null;
        // A signal change (resetStatistics) landed while this window was
        // being assembled / analyzed — it straddles the old and the new
        // signal.  Discard it: neither accumulate (it would poison the
        // freshly cleared average) nor publish (its stale fundamental would
        // step the FLL and trim the live generator to a garbage frequency).
        // The reset's pending re-anchor rebuilds from post-change samples.
        if (epochAtStart != resetEpoch.get()) {
            if (log.isInfoEnabled()) {
                log.info("Signal changed mid-analysis — straddling window discarded");
            }
            resultPool.release(r);
            return IDLE_TICK_MS;
        }
        // The dBFS→dBV offset is the global ADC calibration constant
        // (Preferences#getDbvOffsetDb), applied at display time — nothing to
        // anchor per result here; dBFS is the result's only base scale.
        // Mode-transition detection — reset the cross-tick average only when the
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
        // overrun — re-anchor past the glitched overlap) before it can poison
        // the cross-tick vector average.  Only while accumulating; the
        // detector reconfigures itself on an fftLength change and reuses the
        // existing discontinuity recovery + banner.
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
                onSignalDiscontinuity(rdr);
                resultPool.release(r);
                return IDLE_TICK_MS;
            }
            r.gates         = spectralDetector.gates();   // debug overlay snapshot
            r.gateBlockDbFs = dbgBlock;                    // current accepted block (pre-average)
        }
        // Second epoch gate: the detector pass above takes tens of ms at large
        // fftSize — a reset landing there must still keep this straddling
        // window OUT of the freshly cleared accumulator.
        if (epochAtStart != resetEpoch.get()) {
            if (log.isInfoEnabled()) {
                log.info("Signal changed mid-analysis — straddling window discarded before accumulation");
            }
            resultPool.release(r);
            return IDLE_TICK_MS;
        }
        boolean accumulated = accumulate
                && accumulateIntoForeverBuffer(r, samplesAbsStart, coherent, targetFrames);

        // Per-tick bookkeeping runs EVERY tick (the averaging depth is the tick
        // count), even on ticks whose display we skip below.
        completedAnalyses++;
        firstFrameDone = true;
        // Stop-after-N: forever mode + cap enabled; counts ticks (= the "N
        // average(s)" label).  The cross-tick accumulator deepens past 2 frames.
        if (foreverMode
                && prefs.isFftStopAfterNEnabled()
                && completedAnalyses >= prefs.getFftStopAfterN()) {
            paused.set(true);
            if (display != null && !display.isDisposed()) {
                display.asyncExec(() ->
                        MessageBus.instance().publish(Events.FFT_RECORDING_AUTO_STOPPED));
            }
        }

        // Per-tick TRIM: the accumulator above ran this tick, but the O(N) display
        // rebuild below (overlay + recomputeStats + dBV lift + deep-copy publish)
        // does NOT need to.  Skip it while the capture backlog is high — catch up
        // rather than overrun — throttle it to DISPLAY_MIN_NANOS when caught up,
        // but force it by DISPLAY_MAX_NANOS so the view never freezes.
        long backlog   = rdr.getWritePos() - rdr.getReadPos();
        long sinceShow = System.nanoTime() - lastShowNanos;
        boolean showNow = paused.get()
                || sinceShow >= DISPLAY_MAX_NANOS
                || (sinceShow >= DISPLAY_MIN_NANOS && backlog <= 2L * needed);
        if (!showNow) {
            resultPool.release(r);                         // accumulated; defer the rebuild
            return msForSamples(hopSamples, sampleRate);
        }
        lastShowNanos = System.nanoTime();

        if (accumulated) {
            overlayAccumulatorOnto(r);          // r.amplitudeDbFs ← RAW cumulative average
        }
        // The whole post-average pipeline — mains rejection → recomputeStats →
        // .frc calibration → dBV lift — now runs in the UI (FftView), ONCE per
        // DISPLAYED frame (after the display throttle + the coalescing handoff),
        // so it's not redone on coalesced frames and mains is applied where the
        // user wants it.  Here we only hand over the RAW averaged spectrum plus
        // the state that pipeline needs: tracked mains f0 (NaN ⇒ off), the pinned
        // coherent κ for the cal "before" dots (NaN ⇒ single tick), the channel.
        r.mainsF0Hz     = mainsSuppress ? mainsComb(sampleRate).getMainsHz() : Double.NaN;
        r.coherentKappa = accumulated
                ? (accumHasData ? accumKFractional
                                : (r.freqResolution > 0 ? r.fundamentalHzRefined / r.freqResolution : 0.0))
                : Double.NaN;
        r.channelLeft      = wantLeft;
        r.samplesAbsStart  = samplesAbsStart;   // for the FLL's real-time dt
        r.writePos         = rdr.getWritePos();   // live capture head — where a correction issued now lands
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
        // Final epoch gate before the hand-off: a stale fundamental published
        // after a reset would feed the FLL one garbage trim.
        if (epochAtStart != resetEpoch.get()) {
            if (log.isInfoEnabled()) {
                log.info("Signal changed mid-analysis — straddling window discarded before publish");
            }
            resultPool.release(r);
            return IDLE_TICK_MS;
        }
        publishResult(r);
        return msForSamples(hopSamples, sampleRate);
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
        // per-tick), the newer result simply overwrites it — the SWT asyncExec
        // queue then holds at most one spectrum instead of growing without
        // bound until recording stops.  Averaging keeps running every tick; only
        // the DISPLAY is throttled to the UI's paint rate, which is lossless for
        // a coherent average (the next frame carries the deeper accumulation).
        FftResult coalesced = latestForUi.getAndSet(newSlot);
        if (coalesced != null) {
            // The UI never saw the overwritten result — recycle it.
            resultPool.release(coalesced);
            return;
        }
        display.asyncExec(() -> {
            FftResult slot = latestForUi.getAndSet(null);
            if (slot == null) return;
            try {
                MessageBus.instance().publish(Events.FFT_RESULT_AVAILABLE, slot);
            } catch (Exception e) {
                log.error("Can't dispatch Events.FFT_RESULT_AVAILABLE", e);
            } finally {
                // Subscribers ran synchronously in publish() above and
                // deep-copied what they keep — the slot is free again.
                resultPool.release(slot);
            }
        });
        // Force the SWT main loop to wake from Display.sleep() if it was
        // dozing.  asyncExec already posts a wake message but on Windows
        // the message queue can drop wakes under load — wake() is the
        // belt-and-suspenders nudge.
        display.wake();
    }

    public void uiGotResult() {
        if (log.isWarnEnabled() && DebugSwitches.SHOW_FFT_ANALYZE_TIME) {
            log.warn("2. FFT result achieved UI in {} ms", (double)(System.nanoTime() - startSendToUi) / 1_000_000);
        }
    }

    private int msForSamples(int samples, int sampleRate) {
        if (sampleRate <= 0 || samples <= 0) return 0;
        return (int) Math.ceil(1000.0 * samples / sampleRate);
    }


    /** Computes the dBFS reference anchor passed into {@link FftAnalyzer}:
     *  the manual fundamental (canonical Vrms — the field stores nothing
     *  else, whatever unit the user typed in) is resolved to its dBV anchor,
     *  then converted to dBFS here at the boundary — the analyzer speaks
     *  dBFS only.  Returns {@code NaN} (no anchor) unless manual-fundamental
     *  mode is enabled. */
    private double resolveFundRefDbFs(Preferences prefs) {
        if (prefs.isFftManualFundEnabled()) {
            double v = prefs.getFftManualFundVrms();
            double dbv = (v > 0) ? 20.0 * Math.log10(v) : Double.NaN;
            return dbv - prefs.getDbvOffsetDb();   // NaN propagates
        }
        return Double.NaN;
    }
}
