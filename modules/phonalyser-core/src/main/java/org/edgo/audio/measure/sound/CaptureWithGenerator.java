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

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.edgo.audio.measure.adc.WeightedBuffer;
import org.edgo.audio.measure.common.MemUtil;
import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.generator.SignalGenerator;

import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;

@Log4j2
@UtilityClass
public class CaptureWithGenerator {

    /**
     * Simultaneously plays a signal on {@code outDevice} and records from {@code inDevice}
     * for {@code duration} seconds.  Returns the captured mono samples normalised to −1...+1.
     * Channel 0 (left / primary) of the input device is used.
     */
    public double[] run(SignalGenerator gen, DeviceRef outDevice, DeviceRef inDevice,
                       int sampleRate, int bitDepth, int ditherBits,
                       int duration) throws Exception {
        return run(gen, outDevice, inDevice, sampleRate, bitDepth, ditherBits,
                duration, null, 0);
    }

    /**
     * Full overload.  When {@code weights} is non-null, each captured raw ADC code
     * is run through a {@link WeightedBuffer} code-map (offset-binary in ->
     * linearised float code out) before the [-1, +1] normalisation.
     *
     * <p>{@code syncPauseSec} adds a configurable delay between the generator
     * starting (HW buffer pre-filled, DAC actively driving the line) and the
     * recorder starting capture.
     */
    public double[] run(SignalGenerator gen, DeviceRef outDevice, DeviceRef inDevice,
                       int sampleRate, int bitDepth, int ditherBits,
                       int duration, WeightedBuffer weights,
                       int syncPauseSec) throws Exception {
        int   maxSamples = (int) Math.min((long) duration * sampleRate + sampleRate, Integer.MAX_VALUE);
        // ×2: the end-of-capture trim (Arrays.copyOf) transiently holds the
        // buffer and its copy at once.
        ensureHeapFits(2L * maxSamples * Double.BYTES, "The capture buffer");
        double[] samples  = new double[maxSamples];
        long    halfRange = 1L << (bitDepth - 1);
        final AtomicInteger writePos = new AtomicInteger(0);
        final AtomicInteger skipped  = new AtomicInteger(0);
        final int skipFrames = 0;

        AtomicBoolean   genStop  = new AtomicBoolean(false);
        CountDownLatch  genReady = new CountDownLatch(1);

        AudioBackend audio = AudioBackend.instance();
        AudioPlayback audioGen = audio.playbackManager(outDevice)
                .openPlayback(outDevice, sampleRate, bitDepth, ditherBits);
        audioGen.open();

        Thread genThread = new Thread(() -> {
            try {
                audioGen.play(gen, genStop, genReady);
            } catch (Throwable t) {
                // See the sibling run() above: an Error out of the native output
                // path must not leave the sweep with nothing but a JVM stack dump.
                log.error("Generator error", t);
            } finally {
                audioGen.close();
            }
        }, "gen-thread");
        genThread.setPriority(Thread.MAX_PRIORITY);

        try (AudioCapture recorder = audio.manager(inDevice.carrier())
                .openCapture(inDevice, sampleRate, bitDepth)) {
            final WeightedBuffer w = weights;
            recorder.setSampleListener(stereo -> {
                if (skipped.get() < skipFrames) {
                    skipped.addAndGet(stereo.length);
                    return;
                }
                int n = writePos.get();
                for (int i = 0; i < stereo.length && n + i < samples.length; i++) {
                    double code = w != null
                            ? w.correctedCode(stereo[i]).ch1
                            : (double) (stereo[i].ch1 & 0xFFFFFFFFL);
                    samples[n + i] = (code - halfRange) / (double) halfRange;
                }
                writePos.addAndGet(stereo.length);
            });

            recorder.open();
            genThread.start();
            if (!genReady.await(10, TimeUnit.SECONDS)) {
                // The generator never started streaming - recording anyway
                // would deliver a full-duration capture of silence presented
                // as a valid measurement.
                genStop.set(true);
                throw new IllegalStateException(
                        "Generator did not start streaming within 10 s - capture aborted.");
            }
            if (syncPauseSec > 0) {
                log.info("Sync pause: holding capture for {} s while generator runs (analog sync settle)", syncPauseSec);
                Thread.sleep(syncPauseSec * 1000L);
            }
            recorder.startRecording();
            Thread.sleep(duration * 1000L);
            recorder.stopRecording();
        }
        genStop.set(true);
        genThread.join(5000L);
        if (genThread.isAlive()) {
            log.warn("Generator thread still running after 5 s - the playback device may remain busy.");
        }

        int actual = Math.min(writePos.get(), maxSamples);
        return actual < maxSamples ? Arrays.copyOf(samples, actual) : samples;
    }

    /**
     * Stereo variant: same playback/capture orchestration as
     * {@link #run}, but keeps both ADC channels instead of picking
     * one.  Used by the Frequency Response measurement so a single sweep
     * produces both L and R samples - half the wall time of two separate
     * sweeps, and no L/R drift from conditions changing between them.
     */
    /** Refuses up-front - with the real numbers - when {@code needBytes}
     *  cannot fit the current heap: a too-long duration otherwise dies
     *  mid-capture with a bare OutOfMemoryError.  This English guard is the
     *  CLI / log backstop; the GUI pre-checks the SAME {@link MemUtil}
     *  numbers with a localized message. */
    private void ensureHeapFits(long needBytes, String what) {
        long freeBytes = MemUtil.heapShortfall(needBytes);
        if (freeBytes >= 0) {
            throw new IllegalArgumentException(String.format(
                    "%s needs ~%d MB but only %d MB of Java heap are free - "
                    + "reduce the duration / sample rate, or raise -Xmx",
                    what, needBytes >> 20, freeBytes >> 20));
        }
    }

    public StereoSamples runStereo(SignalGenerator gen, DeviceRef outDevice, DeviceRef inDevice,
                                   int sampleRate, int bitDepth, int ditherBits,
                                   OutputChannels outputChannels,
                                   int duration, WeightedBuffer weights,
                                   int syncPauseSec,
                                   BooleanSupplier cancelToken,
                                   StereoCaptureProgress progress) throws Exception {
        int   maxSamples = (int) Math.min((long) duration * sampleRate + sampleRate, Integer.MAX_VALUE);
        ensureHeapFits(MemUtil.stereoCaptureHeapBytes(sampleRate, duration), "The stereo capture buffers");
        final double[] leftBuf  = new double[maxSamples];
        final double[] rightBuf = new double[maxSamples];
        final long    halfRange = 1L << (bitDepth - 1);
        final AtomicInteger writePos = new AtomicInteger(0);
        final AtomicInteger skipped  = new AtomicInteger(0);
        final int skipFrames = 0;

        AtomicBoolean   genStop  = new AtomicBoolean(false);
        CountDownLatch  genReady = new CountDownLatch(1);

        AudioBackend audio = AudioBackend.instance();
        AudioPlayback audioGen = audio.playbackManager(outDevice)
                .openPlayback(outDevice, sampleRate, bitDepth, ditherBits);
        audioGen.open();
        // Gate the played sweep to the selected DAC lane(s) - LEFT / RIGHT write
        // digital silence to the un-driven lane at the quantizer's
        // stereo-interleave seam; BOTH drives both (legacy).  The sweep never
        // scales lanes - calibration enters only in the deconvolution math.
        audioGen.setOutputChannels(outputChannels);

        Thread genThread = new Thread(() -> {
            try {
                audioGen.play(gen, genStop, genReady);
            } catch (Throwable t) {
                // Throwable, not Exception: a native output path raises an Error
                // out of JNA when the device is pulled mid-play, and a thread body
                // that let that through would report nothing at all beyond the
                // JVM's own uncaught-exception line.
                log.error("Generator error", t);
            } finally {
                audioGen.close();
            }
        }, "gen-thread");
        genThread.setPriority(Thread.MAX_PRIORITY);

        try (AudioCapture recorder = audio.manager(inDevice.carrier())
                .openCapture(inDevice, sampleRate, bitDepth)) {
            final WeightedBuffer w = weights;
            recorder.setSampleListener(stereo -> {
                if (skipped.get() < skipFrames) {
                    skipped.addAndGet(stereo.length);
                    return;
                }
                int n = writePos.get();
                double sumSqL = 0.0;
                double sumSqR = 0.0;
                int    cnt    = 0;
                for (int i = 0; i < stereo.length && n + i < leftBuf.length; i++) {
                    double code0;
                    double code1;
                    if (w != null) {
                        code0 = w.correctedCode(stereo[i]).ch0;
                        code1 = w.correctedCode(stereo[i]).ch1;
                    } else {
                        code0 = (double) (stereo[i].ch0 & 0xFFFFFFFFL);
                        code1 = (double) (stereo[i].ch1 & 0xFFFFFFFFL);
                    }
                    double l = (code0 - halfRange) / (double) halfRange;
                    double r = (code1 - halfRange) / (double) halfRange;
                    leftBuf [n + i] = l;
                    rightBuf[n + i] = r;
                    sumSqL += l * l;
                    sumSqR += r * r;
                    cnt++;
                }
                writePos.addAndGet(stereo.length);
                if (progress != null && cnt > 0) {
                    double rmsL = Math.sqrt(sumSqL / cnt);
                    double rmsR = Math.sqrt(sumSqR / cnt);
                    try { progress.onBlock(writePos.get(), Math.max(rmsL, rmsR)); }
                    catch (Exception ex) { log.warn("Capture progress listener threw", ex); }
                }
            });

            recorder.open();
            genThread.start();
            if (!genReady.await(10, TimeUnit.SECONDS)) {
                genStop.set(true);
                throw new IllegalStateException(
                        "Generator did not start streaming within 10 s - capture aborted.");
            }
            if (syncPauseSec > 0) {
                log.info("Sync pause: holding capture for {} s while generator runs (analog sync settle)", syncPauseSec);
                Thread.sleep(syncPauseSec * 1000L);
            }
            recorder.startRecording();
            sleepInterruptibly(duration * 1000L, cancelToken);
            recorder.stopRecording();
        }
        genStop.set(true);
        genThread.join(5000L);
        if (genThread.isAlive()) {
            log.warn("Generator thread still running after 5 s - the playback device may remain busy.");
        }

        int actual = Math.min(writePos.get(), maxSamples);
        double[] outLeft  = actual < maxSamples ? Arrays.copyOf(leftBuf,  actual) : leftBuf;
        double[] outRight = actual < maxSamples ? Arrays.copyOf(rightBuf, actual) : rightBuf;
        return new StereoSamples(outLeft, outRight);
    }

    /** Sleeps for {@code totalMs}, but if {@code cancelToken} is non-null
     *  the wait is split into 50 ms slices that re-check the token between
     *  slices so a cancel request takes effect within ~50 ms instead of
     *  blocking for the full duration. */
    private void sleepInterruptibly(long totalMs, BooleanSupplier cancelToken) throws InterruptedException {
        if (cancelToken == null) {
            Thread.sleep(totalMs);
            return;
        }
        final long slice = 50L;
        long remaining = totalMs;
        while (remaining > 0) {
            if (cancelToken.getAsBoolean()) return;
            long step = Math.min(slice, remaining);
            Thread.sleep(step);
            remaining -= step;
        }
    }
}
