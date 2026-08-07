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

package org.edgo.audio.measure.sound;

import com.sun.jna.Pointer;

import lombok.extern.log4j.Log4j2;

/**
 * Shared base for the PortAudio CALLBACK capture backends - the WDM-KS recorder
 * on Windows and the CoreAudio recorder on macOS.  The capture-side counterpart
 * of {@link AbstractPortAudioPlayback}, and deliberately shaped like it.
 *
 * <p>Those two recorders are the same program twice: PortAudio invokes their
 * callback on its realtime thread, the callback copies bytes into an SPSC ring
 * and returns, and a consume thread drains the ring and runs the listener
 * dispatch.  They stay separate classes because their device refs, their host
 * APIs and their stop discipline differ (CoreAudio must {@code Pa_AbortStream},
 * Windows must not) - but the part that is identical belongs here rather than
 * in both.
 *
 * <p>What is here is the ONE question a callback backend has to ask that a
 * blocking one does not: <b>has this stream ended underneath me?</b>  A pulled
 * device does not report an error to a callback stream - the callback simply
 * stops being invoked, and the consume thread waits on a ring that will never
 * fill again.  Silence is indistinguishable from a quiet input, so without this
 * the capture falls silent and everything above goes on believing it is
 * measuring.
 *
 * <p>It is asked in two ways, because no single one works on both host APIs.
 * {@code Pa_IsStreamActive} is precise where the host API maintains it, and
 * WDM-KS does not maintain it for a CAPTURE stream; the delivery deadline
 * inherited from the base catches what it misses.  See {@link #ringEmpty()}.
 */
@Log4j2
public abstract class AbstractPortAudioCapture extends AbstractPcmCapture {

    /** Consecutive empty parks before the consume loop asks PortAudio whether
     *  the stream is still alive - 200 × 500 µs ≈ 100 ms of complete silence,
     *  which a healthy capture never produces (a period is 10-20 ms) and which
     *  is far below what an operator notices.  Asking on every park would cross
     *  into the binding a couple of thousand times a second to be told "yes". */
    private static final int SILENT_PARKS_BEFORE_LIVENESS_CHECK = 200;

    /** This backend's name in the log and in the reported detail. */
    private final String backendLabel;

    /** Consume-thread only: empty parks since the last liveness check, and
     *  whether this stream was ever seen running (see {@link #streamEnded}). */
    private int silentParks;
    private boolean streamSeenActive;
    /** Consume-thread only: when the next device-presence question is due. */
    private long nextPresenceCheckNanos;

    protected AbstractPortAudioCapture(String backendLabel, int sampleRate, int bitDepth,
                                       int captureChannels) {
        super(sampleRate, bitDepth, captureChannels);
        this.backendLabel = backendLabel;
    }

    /** The open {@code PaStream*}, or null before {@code open()} / after
     *  {@code close()} - supplied by the subclass, which owns the stream's
     *  whole lifecycle. */
    protected abstract Pointer paStream();

    /** Resets the liveness state for a fresh run.  Call from
     *  {@code startRecording()} BEFORE the consume thread is started, so the
     *  write is safely published to it. */
    protected final void resetLivenessCheck() {
        silentParks = 0;
        streamSeenActive = false;
        nextPresenceCheckNanos = System.nanoTime() + 1_000_000_000L;
        armDeliveryDeadline();
    }

    /**
     * Called from the consume loop when the ring came up empty: counts the
     * silence and, once there has been enough of it, asks whether the stream is
     * still there.
     *
     * <p>TWO questions are asked, because one of them does not work on every
     * host API.  {@link #streamEnded()} is the precise one and stays first; the
     * delivery deadline is the one that answers when the host API is not
     * telling.  WDM-KS is exactly that case: a USB card pulled on a running
     * capture leaves the stream nominally ACTIVE every time, so
     * {@code Pa_IsStreamActive} - which correctly stopped the PLAYBACK on the
     * same host API and the same unplug - simply never fires on the capture
     * side (the generator stops and reports an error while the scope keeps
     * running).  A stream that PortAudio still calls active but that has
     * delivered nothing for two seconds has ended whatever PortAudio says.
     *
     * @return true when the stream has ended and the loss has been reported -
     *         the consume loop must return
     */
    protected final boolean ringEmpty() {
        if (++silentParks < SILENT_PARKS_BEFORE_LIVENESS_CHECK) {
            return false;
        }
        silentParks = 0;
        return streamEnded() || deliveryStalled();
    }

    /** Called from the consume loop when a buffer did arrive: the stream is
     *  plainly alive, so the silence count starts again. */
    protected final void ringDelivered() {
        silentParks = 0;
    }

    /**
     * Whether this stream's DEVICE is still on the machine - the capture twin
     * of the playback side's question, and asked on the DELIVERED path too:
     * silence detection cannot see a device whose carcass keeps clocking out
     * zeroes (a macOS aggregate whose members all left does exactly that), so
     * a capture of nothing would run for ever while its open stream blocks
     * every device-list rebuild.  The base cannot answer - PortAudio's device
     * snapshot never changes - so the default is {@code true} and a backend
     * with a live device source overrides.
     */
    protected boolean deviceStillPresent() {
        return true;
    }

    /**
     * Asks {@link #deviceStillPresent()} at most once a second, from the
     * consume loop - on delivery, where silence-based checks never run - and
     * reports the loss if the device is gone.
     *
     * @return true when the loss has been reported and the caller's loop must end
     */
    protected final boolean devicePresenceLost() {
        long now = System.nanoTime();
        if (now < nextPresenceCheckNanos) {
            return false;
        }
        nextPresenceCheckNanos = now + 1_000_000_000L;
        if (deviceStillPresent() || !recording.get()) {
            return false;
        }
        recording.set(false);
        log.error("{} capture device left the machine - the stream kept running "
                + "without it", backendLabel);
        endCapture(CaptureEndReason.DEVICE_LOST,
                backendLabel + " capture device left the machine");
        return true;
    }

    /**
     * Whether the capture stream has ENDED underneath us, and telling everything
     * above if it has.  {@code Pa_IsStreamActive} answers 1 only while the
     * stream is really running, which is what separates a quiet input from a
     * device that is gone.
     *
     * <p><b>It can only be wrong in the safe direction.</b>  Three conditions
     * have to hold before a loss is reported: {@code recording} is still set (a
     * stop of OUR own clears it first, so it is never mistaken for a loss), the
     * stream was seen ACTIVE at least once (the consume thread starts before
     * {@code Pa_StartStream}, and "not started yet" must not read as "ended"),
     * and PortAudio itself now says it is not active.  A stream PortAudio calls
     * inactive really has ended, so a false alarm - which would stop a healthy
     * measurement - cannot come from here.  The converse it does not promise -
     * a host API that leaves the stream nominally active after the device is
     * pulled, which is what WDM-KS does on the capture side - is why
     * {@link #ringEmpty()} also asks the delivery deadline.
     */
    private boolean streamEnded() {
        Pointer stream = paStream();
        if (!recording.get() || stream == null) {
            return false;                    // our own stop is not a loss
        }
        int active = PortAudio.lib().Pa_IsStreamActive(stream);
        if (active == 1) {
            streamSeenActive = true;
            return false;
        }
        if (!streamSeenActive) {
            return false;                    // Pa_StartStream has not run yet
        }
        recording.set(false);
        log.error("{} capture stream ended by itself (Pa_IsStreamActive={}) - the device "
                + "was unplugged or the host API aborted it", backendLabel, active);
        endCapture(CaptureEndReason.DEVICE_LOST,
                backendLabel + " capture stream ended (Pa_IsStreamActive=" + active + ")");
        return true;
    }
}
