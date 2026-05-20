package org.edgo.audio.measure.cli.util;

import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.adc.WeightedBuffer;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.AudioCapture;
import org.edgo.audio.measure.sound.AudioPlayback;
import org.edgo.audio.measure.sound.DeviceRef;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Log4j2
@UtilityClass
public class CaptureWithGenerator {

    /**
     * Simultaneously plays a signal on {@code outDevice} and records from {@code inDevice}
     * for {@code duration} seconds.  Returns the captured mono samples normalised to −1…+1.
     * Channel 0 (left / primary) of the input device is used.
     */
    public float[] run(SignalGenerator gen, DeviceRef outDevice, DeviceRef inDevice,
                       int sampleRate, int bitDepth, int ditherBits,
                       int duration) throws Exception {
        return run(gen, outDevice, inDevice, sampleRate, bitDepth, ditherBits,
                duration, null, 0);
    }

    /**
     * Full overload.  When {@code weights} is non-null, each captured raw ADC code
     * is run through a {@link WeightedBuffer} code-map (offset-binary in →
     * linearised float code out) before the [-1, +1] normalisation.
     *
     * <p>{@code syncPauseSec} adds a configurable delay between the generator
     * starting (HW buffer pre-filled, DAC actively driving the line) and the
     * recorder starting capture.
     */
    public float[] run(SignalGenerator gen, DeviceRef outDevice, DeviceRef inDevice,
                       int sampleRate, int bitDepth, int ditherBits,
                       int duration, WeightedBuffer weights,
                       int syncPauseSec) throws Exception {
        int   maxSamples = (int) Math.min((long) duration * sampleRate + sampleRate, Integer.MAX_VALUE);
        float[] samples  = new float[maxSamples];
        long    halfRange = 1L << (bitDepth - 1);
        final AtomicInteger writePos = new AtomicInteger(0);
        final AtomicInteger skipped  = new AtomicInteger(0);
        final int skipFrames = 0;

        AtomicBoolean   genStop  = new AtomicBoolean(false);
        CountDownLatch  genReady = new CountDownLatch(1);

        AudioPlayback audioGen = AudioBackend.instance().openPlayback(outDevice, sampleRate, bitDepth, ditherBits);
        audioGen.open();

        Thread genThread = new Thread(() -> {
            try {
                audioGen.play(gen, genStop, genReady);
            } catch (Exception e) {
                log.error("Generator error", e);
            } finally {
                audioGen.close();
            }
        }, "gen-thread");
        genThread.setPriority(Thread.MAX_PRIORITY);

        try (AudioCapture recorder = AudioBackend.instance().openCapture(inDevice, sampleRate, bitDepth)) {
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
                    samples[n + i] = (float) ((code - halfRange) / (double) halfRange);
                }
                writePos.addAndGet(stereo.length);
            });

            recorder.open();
            genThread.start();
            genReady.await(10, TimeUnit.SECONDS);
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

        int actual = Math.min(writePos.get(), maxSamples);
        return actual < maxSamples ? Arrays.copyOf(samples, actual) : samples;
    }
}
