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

import java.util.function.BooleanSupplier;

import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.sound.StereoCaptureProgress;
import org.edgo.audio.measure.sound.StereoSamples;

import lombok.extern.log4j.Log4j2;

/**
 * One sweep-and-capture leg over the SHARED ring buffer, driven through ONE
 * {@link GeneratorLane}: the sweep is described as a {@link GeneratorRun} and
 * the lane runs it - rendered here on a local backend, commanded to the bench
 * on a remote one.  This class never touches a signal source or a remote
 * handle; the only local/remote fact it consults
 * is where its record starts - the bench's in-band mark, or the local lane's
 * ready instant.
 *
 * <p>The reader answers terminally when the capture dies ({@code isFinished})
 * and the lane records a local playback death - both become the thrown failure
 * the measurement dialog shows, instead of a silence capture deconvolved into
 * a plausible-looking "measurement".
 */
@Log4j2
public final class SweepCapture {

    /** Assembly chunk, frames.  Small enough for responsive progress and
     *  cancel polling, large enough that the per-chunk cost is nothing. */
    private static final int CHUNK_FRAMES = 8_192;
    /** Cap on one blocking wait for fresh samples - matches the FFT worker's
     *  cadence cap; a stalled capture re-ticks the loop (and re-checks the
     *  terminal + cancel) at least this often. */
    private static final long AWAIT_CAP_MS = 250;
    /** How long the bench may take to mark its sweep start after being told to
     *  play - a bound on a bench that stopped answering, not a deadline for
     *  the audio. */
    private static final long MARK_TIMEOUT_MS = 10_000L;
    /** Park between checks while waiting for the bench's mark - the same 50 ms
     *  slice every cancel poll in this pipeline uses. */
    private static final long MARK_POLL_MS = 50L;

    /** The one lane this sweep drives; held so a failed run's finally can
     *  stop it whatever happened. */
    private final GeneratorLane lane = new GeneratorLane();

    /**
     * Runs one log sweep and assembles the record from the shared ring.
     * Returns the captured samples normalised to [-1, +1]; a cancel returns
     * what was captured so far (the old contract).  Throws when the capture
     * cannot be opened or runs at another rate, the lane refuses the start,
     * the capture or the playback dies mid-sweep, or the assembly falls a
     * full ring behind.
     */
    public StereoSamples run(double startHz, double stopHz, int sweepSamples,
            int leadInSamples, int fadeSamples, double amplitudeVrms, double dacFsVoltage,
            int sampleRate, int bitDepth, int ditherBits, OutputChannels channels,
            int durationSec, BooleanSupplier cancelToken,
            StereoCaptureProgress progress) throws Exception {
        SharedCapture capture = SharedCapture.instance();
        SignalBufferReader reader = capture.acquire();
        if (reader == null) {
            throw new IllegalStateException(capture.getLastStartError());
        }
        try {
            // Marks recorded after this position are THIS sweep's; a stale one
            // from an earlier sweep on the same stream is never mistaken for it.
            long armedAt = reader.getWritePos();
            // Input side of the rate guard BEFORE anything else opens: the
            // ring carries the GRANTED rate, and refusing here leaves nothing
            // behind but the capture reference the finally releases.
            requireRate(reader.getSampleRate(), sampleRate, "input stream");
            GeneratorRun run = new GeneratorRun(GenSignalForm.LOG_SWEEP,
                    0.0, amplitudeVrms, sampleRate, bitDepth, ditherBits,
                    dacFsVoltage, 1.0, channels,
                    true,    // sample counts on the asked clock - a mismatch refuses
                    false,   // the bench's own device card owns its calibration
                    new GeneratorRun.SweepSpec(startHz, stopHz, sweepSamples,
                            leadInSamples, fadeSamples, fadeSamples, false));
            PlaybackStateEnum started = lane.start(run);
            if (started != PlaybackStateEnum.STARTED) {
                // The lane already gave a refused bench lane back; a granted-
                // rate refusal names the number the bench answered with.
                if (started == PlaybackStateEnum.REMOTE_REFUSED
                        && lane.getLastGrantedRateHz() != sampleRate) {
                    requireRate(lane.getLastGrantedRateHz(), sampleRate, "generator lane");
                }
                throw new IllegalStateException("the sweep playback did not start: " + started);
            }
            try {
                if (lane.isRemoteSession()) {
                    long mark = awaitSweepMark(reader, armedAt, cancelToken);
                    if (mark < 0) {
                        return new StereoSamples(new double[0], new double[0]);   // cancelled
                    }
                    reader.seek(mark);
                } else {
                    // The lane reported STARTED: the hardware buffer is
                    // pre-filled and streaming lead-in silence - the same
                    // anchor instant the private-line path always used.
                    reader.seekToLatest();
                }
                return assemble(reader, sampleRate, durationSec, cancelToken, progress);
            } finally {
                lane.stop();
            }
        } finally {
            capture.release();
        }
    }

    /** Waits for the bench's sweep-start mark past {@code armedAt} - bounded,
     *  because a bench that stopped answering must fail the measurement, not
     *  hang it.  Returns -1 on cancel. */
    private long awaitSweepMark(SignalBufferReader reader, long armedAt,
            BooleanSupplier cancelToken) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + MARK_TIMEOUT_MS * 1_000_000L;
        while (true) {
            long mark = reader.getSweepMarkPos();
            if (mark >= armedAt) {
                return mark;
            }
            if (cancelToken != null && cancelToken.getAsBoolean()) {
                return -1;
            }
            if (reader.isFinished()) {
                throw new IllegalStateException("the capture ended before the sweep "
                        + "was marked - " + reader.getFinishedReason().logText());
            }
            if (System.nanoTime() > deadlineNanos) {
                throw new IllegalStateException("the bench never marked its sweep "
                        + "start within " + (MARK_TIMEOUT_MS / 1000) + " s - the "
                        + "record has no sample 0 to begin at, so the measurement "
                        + "is refused rather than cut at a guess");
            }
            Thread.sleep(MARK_POLL_MS);
        }
    }

    /** Streams {@code durationSec * sampleRate} contiguous frames from the
     *  cursor into a fresh {@link StereoSamples}, blocking on the ring's own
     *  wakeup between chunks. */
    private StereoSamples assemble(SignalBufferReader reader, int sampleRate,
            int durationSec, BooleanSupplier cancelToken,
            StereoCaptureProgress progress) throws InterruptedException {
        int total = durationSec * sampleRate;
        double[] left   = new double[total];
        double[] right  = new double[total];
        double[] chunkL = new double[CHUNK_FRAMES];
        double[] chunkR = new double[CHUNK_FRAMES];
        int written = 0;
        while (written < total) {
            if (cancelToken != null && cancelToken.getAsBoolean()) {
                break;                       // return what was captured so far
            }
            if (reader.isFinished()) {
                throw new IllegalStateException("the capture ended mid-sweep - "
                        + reader.getFinishedReason().logText());
            }
            Throwable laneDeath = lane.playbackEndedFromBelow();
            if (laneDeath != null) {
                throw new IllegalStateException(
                        "the sweep playback died mid-sweep", laneDeath);
            }
            int want = Math.min(CHUNK_FRAMES, total - written);
            reader.awaitAvailable(want, AWAIT_CAP_MS);
            int got = reader.read(want, chunkL, chunkR);
            if (got == SignalBufferReader.OVERRUN) {
                throw new IllegalStateException("the sweep assembly fell a full "
                        + "ring behind the capture - the record is torn");
            }
            if (got <= 0) {
                continue;                    // timed out awaiting - re-check terminals
            }
            System.arraycopy(chunkL, 0, left,  written, got);
            System.arraycopy(chunkR, 0, right, written, got);
            written += got;
            if (progress != null) {
                progress.onBlock(written, blockRms(chunkL, chunkR, got));
            }
        }
        if (written == total) {
            return new StereoSamples(left, right);
        }
        double[] outL = new double[written];
        double[] outR = new double[written];
        System.arraycopy(left,  0, outL, 0, written);
        System.arraycopy(right, 0, outR, 0, written);
        return new StereoSamples(outL, outR);
    }

    /** The granted-rate guard: every sample count of the sweep is computed
     *  against the asked rate, and a lane or stream clocked at another one
     *  measures the mismatch, not the device. */
    private void requireRate(long granted, int askedHz, String what) {
        if (granted != askedHz) {
            throw new IllegalStateException("the " + what + " runs at " + granted
                    + " Hz but the sweep was built for " + askedHz + " Hz - the "
                    + "measurement is refused rather than shown wrong");
        }
    }

    /** The larger of the two channels' RMS over the chunk - the level the
     *  progress meter shows, same convention as the old private-line path. */
    private double blockRms(double[] l, double[] r, int n) {
        double sumL = 0;
        double sumR = 0;
        for (int i = 0; i < n; i++) {
            sumL += l[i] * l[i];
            sumR += r[i] * r[i];
        }
        return Math.sqrt(Math.max(sumL, sumR) / n);
    }
}
