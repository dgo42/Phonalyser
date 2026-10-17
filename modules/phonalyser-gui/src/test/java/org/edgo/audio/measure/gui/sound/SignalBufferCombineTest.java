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

import java.util.Arrays;
import java.util.Random;

import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.preferences.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The combined channel on the ring: the four averages the writer feeds - the
 * two inputs' means and their levels {@code mean|x - mean(x)|} - the combined
 * reads built from them, {@code (master + c * (slave - mean(slave)) +
 * mean(master)) / 2} into the master's output and 0 into the slave's with
 * {@code c = level(master) / level(slave)}, the frozen copy that carries the
 * averages, and the reader that routes its reads by its master choice.
 *
 * <p>The signal is the ADC's single-channel mode: one tone on both inputs,
 * the right one 6.02 dB weaker, each input on its own DC offset with its own
 * noise.  The averages are private, so every check reads them back through a
 * combined read: over a span, {@code 2 * combined - master} is the straight
 * line {@code c * slave + offset} with
 * {@code offset = mean(master) - c * mean(slave)}.
 *
 * <p>The averaging time is the live singleton's
 * {@code oscMeasurementAverageSeconds}, set in transient mode and restored
 * after every test.
 */
class SignalBufferCombineTest {

    private static final int    RATE              = 48_000;
    /** The averaging time the tests run at unless they set their own. */
    private static final double AVERAGING_SECONDS = 0.1;
    /** One averaging time in samples: 4800, 100 periods of the tone. */
    private static final int    WINDOW            = (int) Math.round(AVERAGING_SECONDS * RATE);
    /** Holds every sample a test feeds. */
    private static final double RING_SECONDS      = 1.0;
    /** 48 samples per period, so every span read spans whole periods. */
    private static final double TONE_HZ           = 1_000.0;
    private static final double LEFT_AMPLITUDE    = 0.4;
    /** The weaker input: half the left's level, 6.02 dB down. */
    private static final double RIGHT_AMPLITUDE   = LEFT_AMPLITUDE / 2.0;
    /** c with the stronger (left) input as master; its inverse with the right. */
    private static final double LEVEL_RATIO       = LEFT_AMPLITUDE / RIGHT_AMPLITUDE;
    private static final double LEFT_DC           = 0.05;
    private static final double RIGHT_DC          = -0.1;
    /** Each input's own noise, independent of the other's. */
    private static final double NOISE_STD         = 1e-3;
    private static final long   SEED              = 7_919L;
    /** Relative tolerance on a recovered scale c. */
    private static final double SCALE_TOLERANCE   = 0.005;
    private static final double DC_TOLERANCE      = 1e-3;
    /** Relative tolerance on a level. */
    private static final double LEVEL_TOLERANCE   = 0.01;
    /** A float-stored sample against the double one: 24 significant bits. */
    private static final double FLOAT_TOLERANCE   = 1e-6;
    /** Rounding only: the same arithmetic on the same samples. */
    private static final double EXACT_TOLERANCE   = 1e-12;
    /** The no-warm-up span is this fraction of the averaging time: 480
     *  samples, 10 periods. */
    private static final int    EARLY_FRACTION    = 10;
    private static final int    EARLY_SPAN        = WINDOW / EARLY_FRACTION;
    /** Longer than everything the window tests feed: every sample keeps its
     *  1/n weight. */
    private static final double LONG_AVERAGING_SECONDS  = 1.0;
    /** A tenth of what the window tests feed after the switch. */
    private static final double SHORT_AVERAGING_SECONDS = 0.01;
    /** c after the level change when every sample keeps its 1/n weight: the
     *  slave's level is the mean of its two halves. */
    private static final double PLAIN_MEAN_RATIO  = LEFT_AMPLITUDE / ((RIGHT_AMPLITUDE + LEFT_AMPLITUDE) / 2.0);
    /** A ring shorter than what is fed: 2500 samples into 1000 leave the write
     *  index at 500, so the latest 1000 straddle the wrap. */
    private static final int    WRAP_CAPACITY     = 1_000;
    private static final int    WRAP_FED          = 2_500;
    /** Where a gathering read writes into its output. */
    private static final int    OUT_OFFSET        = 7;

    private final Random noise = new Random(SEED);
    private double previousAveragingSeconds;

    @BeforeEach
    void setAveragingTime() {
        Preferences prefs = Preferences.instance();
        prefs.setTransientMode(true);
        previousAveragingSeconds = prefs.getOscMeasurementAverageSeconds();
        prefs.setOscMeasurementAverageSeconds(AVERAGING_SECONDS);
    }

    @AfterEach
    void restoreAveragingTime() {
        Preferences.instance().setOscMeasurementAverageSeconds(previousAveragingSeconds);
    }

    // ── Averages ─────────────────────────────────────────────────────────────

    @Test
    void theScaleIsTheLevelRatioWithEitherInputAsMaster() {
        SignalBuffer buffer = new DoubleSignalBuffer(RATE, RING_SECONDS);
        feed(WINDOW, RIGHT_AMPLITUDE, buffer);

        Combination leftMaster = combinationOf(buffer, Channel.L, WINDOW);
        assertEquals(LEVEL_RATIO, leftMaster.scale(), LEVEL_RATIO * SCALE_TOLERANCE,
                "the weaker slave is scaled up to the master's level");
        assertEquals(LEFT_DC - leftMaster.scale() * RIGHT_DC, leftMaster.offset(), DC_TOLERANCE,
                "the offset is mean(master) - c * mean(slave): both means stand at their DC");

        Combination rightMaster = combinationOf(buffer, Channel.R, WINDOW);
        assertEquals(1.0 / LEVEL_RATIO, rightMaster.scale(), SCALE_TOLERANCE / LEVEL_RATIO,
                "the stronger slave is scaled down to the master's level");
        assertEquals(RIGHT_DC - rightMaster.scale() * LEFT_DC, rightMaster.offset(), DC_TOLERANCE,
                "the same two means serve the other master");
    }

    @Test
    void theCombinationStandsWithoutWarmUp() {
        SignalBuffer buffer = new DoubleSignalBuffer(RATE, RING_SECONDS);
        feed(EARLY_SPAN, RIGHT_AMPLITUDE, buffer);

        assertEquals(LEVEL_RATIO, combinationOf(buffer, Channel.L, EARLY_SPAN).scale(),
                LEVEL_RATIO * SCALE_TOLERANCE,
                "a tenth of the averaging time in, the scale stands: below the window every sample weighs 1/n");
        double[] masterIn  = rawLatest(buffer, EARLY_SPAN).left();
        double[] masterOut = combinedLatest(buffer, Channel.L, EARLY_SPAN).left();
        assertEquals(mean(masterIn), mean(masterOut), DC_TOLERANCE,
                "the master's DC offset stands as well - an exponential average started at 0 "
                        + "would hold about a tenth of each offset here");
    }

    @Test
    void aSlaveWithoutLevelKeepsTheCombinationFinite() {
        SignalBuffer buffer = new DoubleSignalBuffer(RATE, RING_SECONDS);
        double[] left  = new double[WINDOW];
        double[] right = new double[WINDOW];   // a dead input: exactly 0, level 0
        for (int i = 0; i < WINDOW; i++) {
            left[i] = LEFT_AMPLITUDE * tone(i) + LEFT_DC;
        }
        buffer.appendBatch(left, right, WINDOW);

        double[] masterOut = combinedLatest(buffer, Channel.L, WINDOW).left();
        double added = 2.0 * masterOut[0] - left[0];
        for (int i = 0; i < WINDOW; i++) {
            assertTrue(Double.isFinite(masterOut[i]), "the scale is never divided by the slave's zero level");
            assertEquals(added, 2.0 * masterOut[i] - left[i], EXACT_TOLERANCE,
                    "the zero slave adds the constant offset only");
        }
    }

    @Test
    void readsLeaveTheAveragesUnchanged() {
        SignalBuffer buffer = new DoubleSignalBuffer(RATE, RING_SECONDS);
        SignalBufferReader reader = new SignalBufferReader(buffer);
        feed(WINDOW, RIGHT_AMPLITUDE, buffer);
        double[] before = combinedLatest(buffer, Channel.L, WINDOW).left();

        readEveryWay(buffer, reader);

        assertArrayEquals(before, combinedLatest(buffer, Channel.L, WINDOW).left(),
                "only the writer feeds the averages - a read of any kind leaves them as they were");
        reader.release();
    }

    // ── Averaging window and release ─────────────────────────────────────────

    @Test
    void theAveragingWindowFollowsTheAveragingTime() {
        SignalBuffer buffer = longAveragingBuffer();
        SignalBufferReader reader = new SignalBufferReader(buffer);

        feedLevelChange(buffer);

        assertEquals(1.0, combinationOf(buffer, Channel.L, WINDOW).scale(), SCALE_TOLERANCE,
                "the shortened averaging time reached the window: ten short windows after the "
                        + "level change the scale stands at the new ratio 1");
        reader.release();
    }

    @Test
    void aReleasedBufferNoLongerFollowsTheAveragingTime() {
        SignalBuffer buffer = longAveragingBuffer();
        new SignalBufferReader(buffer).release();

        feedLevelChange(buffer);

        assertEquals(PLAIN_MEAN_RATIO, combinationOf(buffer, Channel.L, WINDOW).scale(),
                PLAIN_MEAN_RATIO * SCALE_TOLERANCE,
                "released, the buffer kept the long window: every sample still weighs 1/n");
    }

    @Test
    void theBufferFollowsTheAveragingTimeUntilItsLastReaderIsReleased() {
        SignalBuffer buffer = longAveragingBuffer();
        SignalBufferReader first  = new SignalBufferReader(buffer);
        SignalBufferReader second = new SignalBufferReader(buffer);
        first.release();

        feedLevelChange(buffer);

        assertEquals(1.0, combinationOf(buffer, Channel.L, WINDOW).scale(), SCALE_TOLERANCE,
                "one reader is still on the buffer, so the buffer still follows the averaging time");
        second.release();
    }

    // ── Combined reads ───────────────────────────────────────────────────────

    @Test
    void theCombinedOutputStandsAtTheMastersLevelAndDcOffset() {
        SignalBuffer buffer = new DoubleSignalBuffer(RATE, RING_SECONDS);
        feed(WINDOW, RIGHT_AMPLITUDE, buffer);
        Stereo raw = rawLatest(buffer, WINDOW);

        for (Channel master : Channel.values()) {
            Stereo combined = combinedLatest(buffer, master, WINDOW);
            double[] masterIn  = raw.channel(master);
            double[] masterOut = combined.channel(master);
            double masterLevel = level(masterIn);
            assertEquals(mean(masterIn), mean(masterOut), DC_TOLERANCE,
                    master + " as master: the combination keeps the master's DC offset");
            assertEquals(masterLevel, level(masterOut), LEVEL_TOLERANCE * masterLevel,
                    master + " as master: the combination keeps the master's level");
            assertSilent(combined.channel(other(master)), master + " as master: the slave is delivered as 0");
        }
    }

    @Test
    void everyCombinedReadFormReturnsTheSameSamplesAcrossTheWrap() {
        SignalBuffer buffer = new DoubleSignalBuffer(RATE, (double) WRAP_CAPACITY / RATE);
        feed(WRAP_FED, RIGHT_AMPLITUDE, buffer);
        long end = buffer.getWritePos();
        double[] reference = new double[WRAP_CAPACITY];
        assertEquals(WRAP_CAPACITY, buffer.readLatest(Channel.L, WRAP_CAPACITY, reference, null),
                "a combined read takes null for the slave's output");

        Stereo endingAt = nanStereo(WRAP_CAPACITY);
        buffer.readEndingAt(Channel.L, end, WRAP_CAPACITY, endingAt.left(), endingAt.right());
        assertArrayEquals(reference, endingAt.left(), "readEndingAt combines like readLatest");
        assertSilent(endingAt.right(), "readEndingAt delivers the slave as 0");

        float[] latestLeft  = new float[WRAP_CAPACITY];
        float[] latestRight = nanFloats(WRAP_CAPACITY);
        buffer.readLatest(Channel.L, WRAP_CAPACITY, latestLeft, latestRight);
        assertNarrowed(reference, latestLeft, 0.0, "the float readLatest is the double one narrowed");
        assertSilent(latestRight, "the float readLatest delivers the slave as 0");

        float[] endingLeft  = new float[WRAP_CAPACITY];
        float[] endingRight = nanFloats(WRAP_CAPACITY);
        buffer.readEndingAt(Channel.L, end, WRAP_CAPACITY, endingLeft, endingRight);
        assertNarrowed(reference, endingLeft, 0.0, "the float readEndingAt is the double one narrowed");
        assertSilent(endingRight, "the float readEndingAt delivers the slave as 0");
    }

    @Test
    void theFloatBackingCombinesLikeTheDoubleBacking() {
        SignalBuffer doubles = new DoubleSignalBuffer(RATE, RING_SECONDS);
        SignalBuffer floats  = new FloatSignalBuffer(RATE, RING_SECONDS);
        feed(WINDOW, RIGHT_AMPLITUDE, doubles, floats);

        for (Channel master : Channel.values()) {
            double[] expected = combinedLatest(doubles, master, WINDOW).channel(master);
            Stereo fromFloats = combinedLatest(floats, master, WINDOW);
            assertArrayEquals(expected, fromFloats.channel(master), FLOAT_TOLERANCE,
                    master + " as master: the float ring combines like the double one");
            assertSilent(fromFloats.channel(other(master)), master + " as master: the slave is delivered as 0");

            float[] narrow = new float[WINDOW];
            boolean leftMaster = master == Channel.L;
            floats.readLatest(master, WINDOW, leftMaster ? narrow : null, leftMaster ? null : narrow);
            assertNarrowed(expected, narrow, FLOAT_TOLERANCE,
                    master + " as master: the float ring's float output combines too");
        }
    }

    // ── Reader ───────────────────────────────────────────────────────────────

    @Test
    void aReaderRoutesItsReadsByItsMasterChoice() {
        SignalBuffer buffer = new DoubleSignalBuffer(RATE, (double) WRAP_CAPACITY / RATE);
        SignalBufferReader reader = new SignalBufferReader(buffer);
        feed(WRAP_FED, RIGHT_AMPLITUDE, buffer);
        long end   = buffer.getWritePos();
        long start = end - WRAP_CAPACITY;
        Stereo raw = rawLatest(buffer, WRAP_CAPACITY);
        double[] combined = combinedLatest(buffer, Channel.R, WRAP_CAPACITY).right();

        Stereo separate = readerLatest(reader, WRAP_CAPACITY);
        assertArrayEquals(raw.left(),  separate.left(),  "without combine the reader returns the ring unchanged");
        assertArrayEquals(raw.right(), separate.right(), "without combine the reader returns the ring unchanged");

        reader.combine(Channel.R);
        Stereo latest = readerLatest(reader, WRAP_CAPACITY);
        assertArrayEquals(combined, latest.right(), "combined, the reader's latest window is the combined read");
        assertSilent(latest.left(), "combined, the reader delivers the slave as 0");

        double[] gathered = new double[OUT_OFFSET + WRAP_CAPACITY];
        reader.seek(start);
        assertEquals(WRAP_CAPACITY, reader.read(WRAP_CAPACITY, null, gathered, OUT_OFFSET));
        assertArrayEquals(combined, Arrays.copyOfRange(gathered, OUT_OFFSET, gathered.length),
                "the consuming cursor combines too, written from the output offset");

        float[] cursor = new float[WRAP_CAPACITY];
        reader.seek(start);
        assertEquals(WRAP_CAPACITY, reader.read(WRAP_CAPACITY, null, cursor));
        assertNarrowed(combined, cursor, 0.0, "the float cursor combines like the double one");

        float[] endingAt = new float[WRAP_CAPACITY];
        reader.readEndingAt(end, WRAP_CAPACITY, null, endingAt);
        assertNarrowed(combined, endingAt, 0.0, "the reader's float readEndingAt combines");

        reader.separate();
        assertArrayEquals(raw.right(), readerLatest(reader, WRAP_CAPACITY).right(),
                "separated, the reader returns the ring unchanged again");

        assertThrows(IllegalArgumentException.class, () -> reader.combine(null));
        reader.release();
    }

    @Test
    void aFrozenSnapshotKeepsTheAveragesOfItsMoment() {
        SignalBuffer buffer = new DoubleSignalBuffer(RATE, RING_SECONDS);
        SignalBufferReader reader = new SignalBufferReader(buffer);
        feed(WINDOW, RIGHT_AMPLITUDE, buffer);
        double[] leftAtCopy  = combinedLatest(buffer, Channel.L, WINDOW).left();
        double[] rightAtCopy = combinedLatest(buffer, Channel.R, WINDOW).right();
        reader.combine(Channel.L);
        SignalBufferReader frozen = reader.frozenSnapshot();

        feed(WINDOW, LEFT_AMPLITUDE, buffer);   // the live averages move on

        double[] first = readerLatest(frozen, WINDOW).left();
        assertArrayEquals(leftAtCopy, first, FLOAT_TOLERANCE,
                "the copy keeps the reader's master and combines with the averages of the moment it was taken");
        assertArrayEquals(first, readerLatest(frozen, WINDOW).left(),
                "every read of the copy returns the same samples - a stopped trace never changes");
        frozen.combine(Channel.R);
        assertArrayEquals(rightAtCopy, readerLatest(frozen, WINDOW).right(), FLOAT_TOLERANCE,
                "the copied averages serve the other master as well");
        frozen.release();
        reader.release();
    }

    // ── Feeding ──────────────────────────────────────────────────────────────

    /** Appends {@code count} sample pairs to each buffer, the tone continuing
     *  from the first buffer's write position: the left input at
     *  {@link #LEFT_AMPLITUDE}, the right at {@code rightAmplitude}, each on its
     *  own DC offset with its own noise.  Every buffer gets the same samples,
     *  in batches no longer than its ring - one batch wraps at most once. */
    private void feed(int count, double rightAmplitude, SignalBuffer... buffers) {
        long from = buffers[0].getWritePos();
        double[] left  = new double[count];
        double[] right = new double[count];
        for (int i = 0; i < count; i++) {
            double tone = tone(from + i);
            left[i]  = LEFT_AMPLITUDE * tone + LEFT_DC  + NOISE_STD * noise.nextGaussian();
            right[i] = rightAmplitude * tone + RIGHT_DC + NOISE_STD * noise.nextGaussian();
        }
        for (SignalBuffer buffer : buffers) {
            for (int done = 0; done < count; done += buffer.getCapacity()) {
                int batch = Math.min(buffer.getCapacity(), count - done);
                buffer.appendBatch(Arrays.copyOfRange(left,  done, done + batch),
                                   Arrays.copyOfRange(right, done, done + batch), batch);
            }
        }
    }

    private double tone(long sample) {
        return Math.sin(2.0 * Math.PI * TONE_HZ * sample / RATE);
    }

    /** A ring built while the averaging time is {@link #LONG_AVERAGING_SECONDS}. */
    private SignalBuffer longAveragingBuffer() {
        Preferences.instance().setOscMeasurementAverageSeconds(LONG_AVERAGING_SECONDS);
        return new DoubleSignalBuffer(RATE, RING_SECONDS);
    }

    /** One averaging time with the right input at half the left's level, then
     *  the averaging time shortened to {@link #SHORT_AVERAGING_SECONDS}, then one
     *  more with both inputs at the same level.  A buffer that follows the
     *  change weighs the second half exponentially over ten short windows; one
     *  that does not keeps weighing every sample 1/n. */
    private void feedLevelChange(SignalBuffer buffer) {
        feed(WINDOW, RIGHT_AMPLITUDE, buffer);
        Preferences.instance().setOscMeasurementAverageSeconds(SHORT_AVERAGING_SECONDS);
        feed(WINDOW, LEFT_AMPLITUDE, buffer);
    }

    /** Every read the ring and a reader offer: plain and combined,
     *  latest-window, ending-at and the consuming cursor, double and float,
     *  and a frozen copy. */
    private void readEveryWay(SignalBuffer buffer, SignalBufferReader reader) {
        long end = buffer.getWritePos();
        double[] left   = new double[WINDOW];
        double[] right  = new double[WINDOW];
        float[]  leftF  = new float[WINDOW];
        float[]  rightF = new float[WINDOW];
        buffer.readLatest(WINDOW, left, right);
        buffer.readLatest(WINDOW, leftF, rightF);
        buffer.readEndingAt(end, WINDOW, left, right);
        buffer.readEndingAt(end, WINDOW, leftF, rightF);
        for (Channel master : Channel.values()) {
            buffer.readLatest(master, WINDOW, left, right);
            buffer.readLatest(master, WINDOW, leftF, rightF);
            buffer.readEndingAt(master, end, WINDOW, left, right);
            buffer.readEndingAt(master, end, WINDOW, leftF, rightF);
            reader.combine(master);
            reader.seek(0);
            reader.read(WINDOW, left, right);
            reader.seek(0);
            reader.read(WINDOW, leftF, rightF);
        }
        reader.separate();
        reader.seek(0);
        reader.read(WINDOW, left, right);
        reader.frozenSnapshot().release();
    }

    // ── Reading back ─────────────────────────────────────────────────────────

    private Stereo rawLatest(SignalBuffer buffer, int count) {
        Stereo out = nanStereo(count);
        buffer.readLatest(count, out.left(), out.right());
        return out;
    }

    private Stereo combinedLatest(SignalBuffer buffer, Channel master, int count) {
        Stereo out = nanStereo(count);
        buffer.readLatest(master, count, out.left(), out.right());
        return out;
    }

    private Stereo readerLatest(SignalBufferReader reader, int count) {
        Stereo out = nanStereo(count);
        reader.readLatest(count, out.left(), out.right());
        return out;
    }

    /** Outputs filled with NaN beforehand, so one a read leaves alone shows. */
    private Stereo nanStereo(int count) {
        double[] left  = new double[count];
        double[] right = new double[count];
        Arrays.fill(left,  Double.NaN);
        Arrays.fill(right, Double.NaN);
        return new Stereo(left, right);
    }

    private float[] nanFloats(int count) {
        float[] out = new float[count];
        Arrays.fill(out, Float.NaN);
        return out;
    }

    /** The scale and offset a combined read applied over the latest
     *  {@code count} samples: {@code 2 * combined - master} is
     *  {@code scale * slave + offset} sample by sample, fitted here as the
     *  least-squares line against the slave. */
    private Combination combinationOf(SignalBuffer buffer, Channel master, int count) {
        Stereo raw = rawLatest(buffer, count);
        double[] masterIn  = raw.channel(master);
        double[] slaveIn   = raw.channel(other(master));
        double[] masterOut = combinedLatest(buffer, master, count).channel(master);
        double[] added = new double[count];
        for (int i = 0; i < count; i++) {
            added[i] = 2.0 * masterOut[i] - masterIn[i];
        }
        double slaveMean = mean(slaveIn);
        double addedMean = mean(added);
        double covariance = 0.0;
        double variance   = 0.0;
        for (int i = 0; i < count; i++) {
            double deviation = slaveIn[i] - slaveMean;
            covariance += deviation * (added[i] - addedMean);
            variance   += deviation * deviation;
        }
        double scale = covariance / variance;
        return new Combination(scale, addedMean - scale * slaveMean);
    }

    private Channel other(Channel channel) {
        return channel == Channel.L ? Channel.R : Channel.L;
    }

    private double mean(double[] values) {
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    /** Mean absolute deviation from the mean - the level measure the buffer
     *  averages. */
    private double level(double[] values) {
        double mean = mean(values);
        double sum = 0.0;
        for (double v : values) {
            sum += Math.abs(v - mean);
        }
        return sum / values.length;
    }

    private void assertSilent(double[] out, String message) {
        for (double v : out) {
            assertEquals(0.0, v, message);
        }
    }

    private void assertSilent(float[] out, String message) {
        for (float v : out) {
            assertEquals(0f, v, message);
        }
    }

    /** Each float sample is the double one narrowed, within {@code tolerance}. */
    private void assertNarrowed(double[] expected, float[] actual, double tolerance, String message) {
        assertEquals(expected.length, actual.length, message);
        for (int i = 0; i < expected.length; i++) {
            assertEquals((float) expected[i], actual[i], (float) tolerance, message);
        }
    }

    /** One combined read's scale c and offset {@code mean(master) - c * mean(slave)}. */
    private record Combination(double scale, double offset) { }

    /** Both outputs of one read. */
    private record Stereo(double[] left, double[] right) {
        double[] channel(Channel channel) {
            return channel == Channel.L ? left : right;
        }
    }
}
