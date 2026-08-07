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

package org.edgo.audio.measure.gui.sound;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.edgo.audio.measure.common.Closeables;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.sound.AudioPlayback;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * One local playback lane: the play thread that owns an {@link AudioPlayback}
 * from start to close, and the state everything above consults about it.
 *
 * <p>Extracted from the generator controller so every module that renders a
 * signal (the DDS generator today; frequency-response and notch-tuning sweeps
 * next) drives the SAME lane mechanics instead of growing its own - the
 * common-parent rule applied to the one piece they genuinely share.
 *
 * <p><b>The play thread is the lane's owner.</b>  It is the only thread that
 * touches the line: it renders, and its {@code finally} closes what was opened
 * for it.  {@link #release()} therefore never enters the driver - it sets the
 * lane's stop flag, waits a bounded time, and lets a thread that will not
 * leave the driver go (a wedge costs an abandoned daemon, never the caller).
 *
 * <p><b>Informed from below.</b>  A lane can end without anyone above asking -
 * the device is unplugged, the driver throws an {@code Error} out of a native
 * call - and the play thread (or the backend's loss callback) records that in
 * {@link #endedFromBelow()}: atomic state any consumer may consult, set once
 * per session, never by a lane that was already let go (the session's stop
 * flag doubles as its identity, and the compare-and-set on it is what keeps a
 * stale lane's late death away from the live one).
 */
@Log4j2
public final class PlaybackLane {

    /** How long the play thread is given to leave the driver after being asked
     *  to stop.  Far more than a healthy line needs to drain, far less than an
     *  operator will sit through; past it the thread is abandoned - it is a
     *  daemon, its stop flag is set, and it closes only the line IT opened. */
    private static final long LANE_EXIT_TIMEOUT_MS = 2_000;

    /** The current session's stop flag AND its identity - see the class
     *  comment.  Never null: a fresh lane replaces it, a release sets it. */
    private volatile AtomicBoolean stopFlag = new AtomicBoolean(true);
    /** The lane's owner thread; null when no lane is up (or the last one was
     *  abandoned to the driver). */
    private volatile Thread playThread;
    /** The line the current lane renders into - for the live-apply setters
     *  (dither, routing scale); null between lanes. */
    @Getter
    private volatile AudioPlayback playback;
    /** Why the current session ended from BELOW, or null while it is alive or
     *  ended by {@link #release()}. */
    private final AtomicReference<Throwable> endedBelow = new AtomicReference<>();
    /** Whether the lane is up and emitting - the LOCAL running truth. */
    @Getter
    private volatile boolean running;

    /**
     * Starts a lane: the caller resolved and OPENED {@code playback} and built
     * {@code source}; this owns both from here (the play thread closes the
     * line however the lane ends).  Any previous lane is released first.
     *
     * @return {@link PlaybackStateEnum#STARTED} once the stream signalled
     *         ready; {@link PlaybackStateEnum#NO_STREAM_START} /
     *         {@link PlaybackStateEnum#INTERRUPTED} with the lane already torn
     *         back down
     */
    public synchronized PlaybackStateEnum start(AudioPlayback playback, SignalGenerator source,
            long readyTimeoutSeconds) {
        release();
        final AtomicBoolean sessionStop = new AtomicBoolean(false);
        stopFlag = sessionStop;
        endedBelow.set(null);
        this.playback = playback;

        final CountDownLatch readyLatch = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                playback.play(source, sessionStop, readyLatch);
            } catch (Throwable ex) {
                // THROWABLE, not Exception: the native backends are reached
                // through JNA, whose "Invalid memory access" is an Error.  An
                // escape here would leave the lane claiming to run over a line
                // that has ceased to exist.
                log.warn("Playback thread terminated abnormally", ex);
                laneDied(sessionStop, ex);
            } finally {
                Closeables.closeQuietly(playback);
            }
        }, "generator-play");
        t.setDaemon(true);
        // Exclusive-mode WASAPI / WDM-KS underruns the moment the audio thread
        // misses an event tick; MAX_PRIORITY keeps it ahead of GC helpers and
        // the GUI thread (the canonical fix for the mid-plateau square-wave
        // dip when a buffer of audio is replaced by silence during a stall).
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
        playThread = t;

        try {
            if (!readyLatch.await(readyTimeoutSeconds, TimeUnit.SECONDS)) {
                release();
                return PlaybackStateEnum.NO_STREAM_START;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            release();
            return PlaybackStateEnum.INTERRUPTED;
        }
        running = true;
        return PlaybackStateEnum.STARTED;
    }

    /**
     * Ends the lane: sets its stop flag, gives the play thread
     * {@link #LANE_EXIT_TIMEOUT_MS} to leave the driver, and lets it go either
     * way - a thread still inside a native call after the wait is WEDGED, and
     * keeping its reference would refuse every later start over a lane that
     * can never come back.  Never joins ITSELF: the death path runs on the
     * play thread, and would otherwise wait the whole timeout for the thread
     * standing in this very call.
     *
     * @return {@code true} when the thread really ended, or there was none
     */
    public synchronized boolean release() {
        stopFlag.set(true);
        running  = false;
        playback = null;
        Thread t = playThread;
        if (t == null) return true;
        if (t == Thread.currentThread()) return false;
        try {
            t.join(LANE_EXIT_TIMEOUT_MS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        boolean ended = !t.isAlive();
        if (!ended && log.isWarnEnabled()) {
            log.warn("Playback thread did not exit within {} ms - it is stuck in the driver; "
                    + "abandoning it so it cannot refuse every later start.",
                    LANE_EXIT_TIMEOUT_MS);
        }
        playThread = null;
        return ended;
    }

    /** Why the current session ended from below, or null while it is alive or
     *  was ended by {@link #release()} - the consultable answer to "did the
     *  hardware take this lane away". */
    public Throwable endedFromBelow() {
        sweepLostLane();
        return endedBelow.get();
    }

    /** Claims the from-below end for the ONE operator report: the first
     *  caller gets the failure, every later one gets {@code null} - the
     *  capture side's {@code SignalBuffer} claim, mirrored, so however many
     *  visual sync points poll the lane, one death is reported once. */
    public Throwable takeEndedFromBelowForReport() {
        sweepLostLane();
        return endedBelow.getAndSet(null);
    }

    /**
     * Folds the backend's own confession ({@link AudioPlayback#lostFromBelow})
     * into the same ended-from-below state {@link #laneDied} records - for the
     * lane whose play thread CANNOT return: wedged inside a native write that
     * holds the line monitor (csjsound on an unplugged device), it never
     * reaches the throw that would have travelled up, and only the backend's
     * watchdog knows.  Runs on the consult paths, so the pane's existing
     * 500 ms sync point discovers the death without the play thread's help;
     * the wedged thread is abandoned exactly as {@link #release()} abandons
     * it, and its own late exit (if the driver ever lets go) is ignored by
     * the compare-and-set on the session flag.
     */
    private synchronized void sweepLostLane() {
        AudioPlayback line = playback;
        if (!running || line == null) {
            return;
        }
        Throwable lost = line.lostFromBelow();
        if (lost == null) {
            return;
        }
        if (!stopFlag.compareAndSet(false, true)) {
            return;   // a release or the thread's own death path got here first
        }
        if (log.isWarnEnabled()) {
            log.warn("Playback lane lost from below (backend confession, render "
                    + "thread wedged): {}", lost.toString());
        }
        running    = false;
        playback   = null;
        playThread = null;
        endedBelow.set(lost);
    }

    /**
     * The lane died under its own thread - device unplugged, taken
     * exclusively, or the driver failed hard enough to throw.  Records the
     * why and stops claiming to run; the consumers above CONSULT the lane
     * (the pane polls on its visual sync points) - nothing is published.  A
     * stale session (already released, or a second report of the same death)
     * is dropped by the compare-and-set on ITS OWN stop flag.
     */
    private void laneDied(AtomicBoolean session, Throwable failure) {
        if (stopFlag != session || !session.compareAndSet(false, true)) {
            if (log.isDebugEnabled()) {
                log.debug("Playback lane death ignored - the lane was abandoned or already "
                        + "stopping: {}", failure.toString());
            }
            return;
        }
        running  = false;
        playback = null;
        endedBelow.set(failure);
    }
}
