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

package org.edgo.audio.measure.sound.qa40x;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.edgo.audio.measure.sound.LibUsb;
import org.edgo.audio.measure.sound.qa40x.Qa40xDeviceFinder.Qa40xDevice;
import org.edgo.audio.measure.sound.qa40x.Qa40xDeviceFinder.Qa40xModel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end integration of the REAL QA40x USB stack — JNA binding
 * ({@link LibUsb}) → {@link Qa40xDeviceFinder} → {@link LibUsbQa40xTransport} →
 * {@link Qa40xDuplexEngine} — against the native mock {@code libusb-1.0.dll} that
 * emulates one permanently-connected QA403 with an ideal loopback cable.  No
 * production code is stubbed: the mock sits below the JNA seam, exactly where a
 * real analyzer would.
 *
 * <p>Every expected value here is DERIVED from the mock's documented formulas
 * (see {@code .claude/plans/qa40x-mock-libusb.md}), not hard-coded: the cal-page
 * dB records ({@code ADC ±(0.10+0.02·c)}, {@code DAC ±(0.05+0.02·c)}, float32),
 * and the per-channel loopback gain
 * {@code G = 10^((outFS − inFS)/20) / (calDac · calAdc)} — the round-trip gain that
 * makes the host ADC read reproduce the DAC peak voltage under the 2026-07-17
 * range-label ruling (the former {@code +9} carried the dropped vendor ADC
 * {@code −6} dB term; {@link Qa40xLevels} now mirrors the DAC {@code +3}, zeroing it).
 *
 * <p><b>Requires the mock DLL, built manually (VS2015):</b> run
 * {@code src\test\lib\libusb\build.cmd}; Maven never builds it.  {@code @BeforeAll}
 * resolves {@code src/test/lib/libusb/x64/Release} (falling back to {@code Debug})
 * and sets the production {@code -Dlibusb.path} flag BEFORE the binding is first
 * touched — exercising the exact mechanism the app uses.  This works only in a
 * <b>fresh JVM</b> where this class runs alone, because {@link LibUsb} loads the
 * library once per process.  It is {@code @Tag}-excluded from the normal build;
 * run it isolated:
 *
 * <pre>{@code
 *   mvn -o test "-Dsurefire.excludedGroups=" -Dtest=Qa40xMockLoopbackIT
 * }</pre>
 */
@Tag("qa40x-mock")
@TestMethodOrder(OrderAnnotation.class)
@TestInstance(Lifecycle.PER_CLASS)
class Qa40xMockLoopbackIT {

    private static final String DLL_FILENAME  = "libusb-1.0.dll";
    private static final String RELEASE_DIR   = "Release";
    private static final String DEBUG_DIR     = "Debug";
    /** The production {@code -Dlibusb.path} flag the app itself uses (see {@link LibUsb}). */
    private static final String LIBUSB_PATH_PROPERTY = "libusb.path";

    /** The mock enumerates one QA403 at this fixed USB bus / address. */
    private static final int EXPECTED_BUS     = 1;
    private static final int EXPECTED_ADDRESS = 5;

    /** Cal-page dB formula (float32): ADC {@code ±(0.10 + 0.02·code)}. */
    private static final float ADC_DB_BASE = 0.10f;
    private static final float ADC_DB_STEP = 0.02f;
    /** Cal-page dB formula (float32): DAC {@code ±(0.05 + 0.02·code)}. */
    private static final float DAC_DB_BASE = 0.05f;
    private static final float DAC_DB_STEP = 0.02f;
    private static final double DB_DIVISOR = 20.0;
    /** Relative tolerance for the cal factors — float32 round-trips bit-exactly. */
    private static final double REL_EPS    = 1e-6;

    /** Loopback session config: input 24 dBV, output 18 dBV, 48 kHz. */
    private static final int INPUT_DBV = 24;
    private static final int OUTPUT_DBV = 18;
    private static final int RATE_HZ = 48_000;

    /** Generator tone: a 1 kHz sine at 0.1·MAXINT peak on the LEFT lane, right silent. */
    private static final double TONE_HZ           = 1000.0;
    private static final double AMPLITUDE_FRACTION = 0.1;
    private static final double AMPLITUDE          = AMPLITUDE_FRACTION * Qa40xLevels.MAXINT;

    private static final int  TARGET_FRAMES_LOOPBACK = 16_384;
    private static final int  TARGET_FRAMES_RESTART  = 4_096;
    /** Frames dropped from the front of a capture before analysis (start-up settle). */
    private static final int  SKIP_FRAMES            = 2_048;
    private static final long AWAIT_SECONDS          = 10L;

    /** Amplitude match window for the recovered loopback level. */
    private static final double AMPLITUDE_TOL_DB = 0.5;
    /** Right lane must be at least this factor below the left level (channel isolation). */
    private static final double ISOLATION_RATIO  = 1_000.0;
    /** The 1 kHz bin must exceed each probed harmonic by this power factor. */
    private static final double DOMINANCE_RATIO  = 100.0;
    private static final double HARMONIC_2ND_HZ  = 2 * TONE_HZ;
    private static final double HARMONIC_3RD_HZ  = 3 * TONE_HZ;

    /** Interleaved stereo int32: 2 channels × 4 bytes. */
    private static final int FRAME_BYTES = 8;
    private static final double SQRT2    = Math.sqrt(2.0);

    private final Qa40xDeviceFinder finder = new Qa40xDeviceFinder();
    private LibUsbQa40xTransport transport;
    private Qa40xDuplexEngine engine;

    @BeforeAll
    void loadMockLibrary() {
        Path x64 = Paths.get(System.getProperty("user.dir"),
                "src", "test", "lib", "libusb", "x64");
        Path releaseDir = x64.resolve(RELEASE_DIR);
        Path debugDir = x64.resolve(DEBUG_DIR);
        Path libDir = null;
        if (Files.isRegularFile(releaseDir.resolve(DLL_FILENAME))) {
            libDir = releaseDir;
        } else if (Files.isRegularFile(debugDir.resolve(DLL_FILENAME))) {
            libDir = debugDir;
        }
        if (libDir == null) {
            fail("mock " + DLL_FILENAME + " not found in " + releaseDir
                    + " or " + debugDir
                    + " — build it with src\\test\\lib\\libusb\\build.cmd (VS2015)");
            return;
        }
        // Must precede any LibUsb touch; only safe in a fresh, single-class fork.
        System.setProperty(LIBUSB_PATH_PROPERTY, libDir.toAbsolutePath().toString());
    }

    @AfterAll
    void closeTransport() {
        if (transport != null) {
            transport.close();
        }
    }

    @Test
    @Order(1)
    void enumeration_findsExactlyOneQa403() {
        assertTrue(LibUsb.available(), "mock libusb-1.0 did not load from jna.library.path");

        List<Qa40xDevice> devices = finder.list();
        assertEquals(1, devices.size(), "mock must expose exactly one QA40x");
        Qa40xDevice device = devices.get(0);
        assertEquals(Qa40xModel.QA403, device.model(), "mock emulates a QA403");
        assertEquals(EXPECTED_BUS, device.busNumber());
        assertEquals(EXPECTED_ADDRESS, device.address());

        // Listing again exercises device-list alloc/free correctness.
        assertEquals(1, finder.list().size(), "second list() must still find the device");
    }

    @Test
    @Order(2)
    void openAndCalibration_matchMockFormula() {
        transport = finder.open();               // reset + claim under the hood
        assertNotNull(transport, "open() must return a claimed transport");

        Qa40xCalibration cal = Qa40xCalibration.fromTransport(transport);

        for (int dbv : Qa40xProtocol.inputRangeDbvValues()) {
            int code = Qa40xProtocol.inputRangeCode(dbv);
            float db = ADC_DB_BASE + ADC_DB_STEP * code;
            assertFactor(linear(db), cal.adcLinearFactor(dbv, false),
                    "ADC left factor at " + dbv + " dBV");
            assertFactor(linear(-db), cal.adcLinearFactor(dbv, true),
                    "ADC right factor at " + dbv + " dBV");
        }
        for (int dbv : Qa40xProtocol.outputRangeDbvValues()) {
            int code = Qa40xProtocol.outputRangeCode(dbv);
            float db = DAC_DB_BASE + DAC_DB_STEP * code;
            assertFactor(linear(db), cal.dacLinearFactor(dbv, false),
                    "DAC left factor at " + dbv + " dBV");
            assertFactor(linear(-db), cal.dacLinearFactor(dbv, true),
                    "DAC right factor at " + dbv + " dBV");
        }
    }

    @Test
    @Order(3)
    void duplexLoopback_reproducesHostGain() throws InterruptedException {
        engine = new Qa40xDuplexEngine(transport, millis -> { },
                INPUT_DBV, OUTPUT_DBV, RATE_HZ);
        engine.attachGenerator(new SineSource(AMPLITUDE, TONE_HZ, RATE_HZ));
        CapturingConsumer consumer = new CapturingConsumer(TARGET_FRAMES_LOOPBACK);
        engine.attachCapture(consumer);

        assertTrue(consumer.await(AWAIT_SECONDS),
                "loopback did not deliver " + TARGET_FRAMES_LOOPBACK + " frames in time");
        engine.detachCapture();
        engine.detachGenerator();

        int[][] channels = deinterleave(consumer.copyBytes(), SKIP_FRAMES);
        int[] left = channels[0];
        int[] right = channels[1];

        // Expected loopback gain from the mock's cal formula + gain identity.
        double calAdcLeft = linear(ADC_DB_BASE + ADC_DB_STEP * Qa40xProtocol.inputRangeCode(INPUT_DBV));
        double calDacLeft = linear(DAC_DB_BASE + DAC_DB_STEP * Qa40xProtocol.outputRangeCode(OUTPUT_DBV));
        double gainLeft = linear(OUTPUT_DBV - INPUT_DBV) / (calDacLeft * calAdcLeft);
        double expectedAmplitude = AMPLITUDE * gainLeft;

        // (a) Recovered left amplitude (RMS·√2) matches A·G_L within 0.5 dB.
        double measuredAmplitude = rms(left) * SQRT2;
        double amplitudeErrorDb = 20.0 * Math.log10(measuredAmplitude / expectedAmplitude);
        assertTrue(Math.abs(amplitudeErrorDb) <= AMPLITUDE_TOL_DB,
                "left amplitude " + measuredAmplitude + " vs expected " + expectedAmplitude
                        + " (" + amplitudeErrorDb + " dB)");

        // (b) Right lane is silent — proves the DAC L/R swap / ADC no-swap wiring.
        double rightAmplitude = rms(right) * SQRT2;
        assertTrue(rightAmplitude <= expectedAmplitude / ISOLATION_RATIO,
                "right lane not isolated: " + rightAmplitude);

        // (c) The 1 kHz bin dominates its harmonics — the tone is where we put it.
        double powerTone = goertzelPower(left, TONE_HZ);
        double power2nd = goertzelPower(left, HARMONIC_2ND_HZ);
        double power3rd = goertzelPower(left, HARMONIC_3RD_HZ);
        assertTrue(powerTone > DOMINANCE_RATIO * power2nd && powerTone > DOMINANCE_RATIO * power3rd,
                "1 kHz did not dominate: p1k=" + powerTone + " p2k=" + power2nd + " p3k=" + power3rd);
    }

    @Test
    @Order(4)
    void stopStartCycle_streamsAgain() throws InterruptedException {
        // A second RUN_START against the mock after the §7 cancelAll → reg8=0 stop.
        CapturingConsumer consumer = new CapturingConsumer(TARGET_FRAMES_RESTART);
        engine.attachCapture(consumer);

        assertTrue(consumer.await(AWAIT_SECONDS),
                "restarted stream did not deliver " + TARGET_FRAMES_RESTART + " frames in time");
        engine.detachCapture();
    }

    // --- expected-value math (mirrors the mock's documented formulas) ---------

    private double linear(double db) {
        return Math.pow(10.0, db / DB_DIVISOR);
    }

    private void assertFactor(double expected, double actual, String message) {
        assertEquals(expected, actual, Math.abs(expected) * REL_EPS, message);
    }

    // --- capture analysis -----------------------------------------------------

    private int[][] deinterleave(byte[] bytes, int skipFrames) {
        int frames = bytes.length / FRAME_BYTES - skipFrames;
        int[] left = new int[frames];
        int[] right = new int[frames];
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        buffer.position(skipFrames * FRAME_BYTES);
        for (int i = 0; i < frames; i++) {
            left[i] = buffer.getInt();
            right[i] = buffer.getInt();
        }
        return new int[][] { left, right };
    }

    private double rms(int[] samples) {
        double sumSquares = 0.0;
        for (int sample : samples) {
            sumSquares += (double) sample * sample;
        }
        return Math.sqrt(sumSquares / samples.length);
    }

    /** Goertzel single-bin power at {@code freqHz} (samples taken at {@link #RATE_HZ}). */
    private double goertzelPower(int[] samples, double freqHz) {
        double coeff = 2.0 * Math.cos(2.0 * Math.PI * freqHz / RATE_HZ);
        double s1 = 0.0;
        double s2 = 0.0;
        for (int sample : samples) {
            double s0 = sample + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2;
    }

    /** Continuous-phase 1 kHz sine on the LOGICAL left lane; right lane silent. */
    private static final class SineSource implements Qa40xDuplexEngine.SampleSource {
        private final double amplitude;
        private final double frequencyHz;
        private final double sampleRateHz;
        private long phase;

        SineSource(double amplitude, double frequencyHz, double sampleRateHz) {
            this.amplitude = amplitude;
            this.frequencyHz = frequencyHz;
            this.sampleRateHz = sampleRateHz;
        }

        @Override
        public void nextFrames(int[] destination, int frames) {
            for (int f = 0; f < frames; f++) {
                double angle = 2.0 * Math.PI * frequencyHz * phase / sampleRateHz;
                destination[2 * f] = (int) Math.round(amplitude * Math.sin(angle));
                destination[2 * f + 1] = 0;
                phase++;
            }
        }
    }

    /** Accumulates capture buffers and latches once {@code targetFrames} have arrived. */
    private static final class CapturingConsumer implements Qa40xDuplexEngine.CaptureConsumer {
        private final int targetFrames;
        private final CountDownLatch latch = new CountDownLatch(1);
        private final List<byte[]> chunks = new ArrayList<>();
        private int frameCount;

        CapturingConsumer(int targetFrames) {
            this.targetFrames = targetFrames;
        }

        @Override
        public synchronized void onAudio(byte[] buffer, int length) {
            chunks.add(Arrays.copyOf(buffer, length));    // buffer is recycled after return
            frameCount += length / FRAME_BYTES;
            if (frameCount >= targetFrames) {
                latch.countDown();
            }
        }

        boolean await(long seconds) throws InterruptedException {
            return latch.await(seconds, TimeUnit.SECONDS);
        }

        synchronized byte[] copyBytes() {
            int total = 0;
            for (byte[] chunk : chunks) {
                total += chunk.length;
            }
            byte[] all = new byte[total];
            int pos = 0;
            for (byte[] chunk : chunks) {
                System.arraycopy(chunk, 0, all, pos, chunk.length);
                pos += chunk.length;
            }
            return all;
        }
    }
}
