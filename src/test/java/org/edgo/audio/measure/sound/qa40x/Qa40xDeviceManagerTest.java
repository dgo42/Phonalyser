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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioFormat;

import org.junit.jupiter.api.Test;

import org.edgo.audio.measure.enums.DeviceChannelMode;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.preferences.AudioDeviceProfile;
import org.edgo.audio.measure.preferences.DeviceEndpointConfig;
import org.edgo.audio.measure.preferences.DeviceRange;
import org.edgo.audio.measure.sound.AudioCapture;
import org.edgo.audio.measure.sound.DeviceRef;
import org.edgo.audio.measure.sound.qa40x.Qa40xDeviceFinder.Qa40xModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Qa40xDeviceManager} integration: the fixed format set, graceful empty
 * enumeration, the device-card builder (per-channel full-scale derived from
 * {@link Qa40xLevels} + cal-page factors, active-range survival across refresh),
 * and the one-duplex-engine session model against {@link FakeTransport} — the
 * engine survives a capture close/reopen while the generator stays attached and
 * stops only when the last client detaches (doc §10).
 */
class Qa40xDeviceManagerTest {

    private static final int RATE_HZ      = 48_000;
    private static final double TOL       = 1e-9;
    private static final int RECORD_BYTES = 6;
    private static final int ADC_BASE     = 24;
    private static final int DAC_BASE     = 120;
    private static final int RANGE_STRIDE = 12;

    private static final Qa40xDuplexEngine.Sleeper INSTANT = millis -> { };

    // --- formats + enumeration -----------------------------------------------

    @Test
    void supportedFormats_areTheThreeDuplexRatesStereo24BitLittleEndian() {
        Qa40xDeviceManager mgr = new Qa40xDeviceManager(new FakeTransport(), INSTANT);
        DeviceRef ref = new Qa40xDeviceManager.Qa40xDeviceRef(0, "QA403", Qa40xModel.QA403);
        for (boolean output : new boolean[] {false, true}) {
            List<AudioFormat> formats = mgr.listSupportedFormats(ref, output);
            assertEquals(3, formats.size());
            int[] expectedRates = {48_000, 96_000, 192_000};
            for (int i = 0; i < expectedRates.length; i++) {
                AudioFormat f = formats.get(i);
                assertEquals(expectedRates[i], (int) f.getSampleRate());
                // Advertised depth is the EFFECTIVE 24 bits the recorder delivers
                // (it drops the int32 pad byte — doc §5, maintainer bench
                // 2026-07-17), so the frame is 24-bit stereo = 6 bytes.
                assertEquals(24, f.getSampleSizeInBits());
                assertEquals(6, f.getFrameSize());
                assertEquals(2, f.getChannels());
                assertFalse(f.isBigEndian(), "QA402/QA403 samples are little-endian (doc §5)");
            }
        }
    }

    @Test
    void enumeration_isEmptyWithoutADevice() {
        // No QA40x is attached in the test environment (and libusb is likely
        // absent) → both lists degrade to empty, never throwing.
        Qa40xDeviceManager mgr = new Qa40xDeviceManager();
        assertTrue(mgr.listInputDevices().isEmpty());
        assertTrue(mgr.listOutputDevices().isEmpty());
    }

    // --- device card ---------------------------------------------------------

    @Test
    void buildProfile_derivesPerChannelFullScaleFromLevelsAndCal() {
        Qa40xCalibration cal = Qa40xCalibration.fromBlob(syntheticBlob());
        Qa40xDeviceManager mgr = new Qa40xDeviceManager(new FakeTransport(), INSTANT);

        AudioDeviceProfile card = mgr.buildProfile("QA403", cal, null);

        assertTrue(card.getMatch().contains("QA403"), "match list carries the model name");

        DeviceEndpointConfig in = card.getInput();
        assertEquals(DeviceChannelMode.LINKED, in.getChannels());
        assertTrue(in.isCalibrationFromDevice());
        assertEquals(8, in.getRanges().size(), "8 input ranges (0..42 dBV)");
        for (int dbv : Qa40xProtocol.inputRangeDbvValues()) {
            DeviceRange row = rowByLabel(in, dbv + " dBV");
            assertEquals(Qa40xLevels.inputFullScaleRmsVolts(dbv, cal.adcLinearFactor(dbv, false)),
                    row.getFsLeft(), TOL, "input fsLeft at " + dbv + " dBV");
            assertEquals(Qa40xLevels.inputFullScaleRmsVolts(dbv, cal.adcLinearFactor(dbv, true)),
                    row.getFsRight(), TOL, "input fsRight at " + dbv + " dBV");
            assertNotEquals(row.getFsLeft(), row.getFsRight(),
                    "per-channel cal makes L/R full-scale asymmetric");
            assertTrue(row.isCalibrated(), "device-owned rows are calibrated (survive seed merge)");
        }

        DeviceEndpointConfig out = card.getOutput();
        assertTrue(out.isCalibrationFromDevice());
        assertEquals(4, out.getRanges().size(), "4 output ranges (-12/-2/+8/+18 dBV)");
        for (int dbv : Qa40xProtocol.outputRangeDbvValues()) {
            DeviceRange row = rowByLabel(out, dbv + " dBV");
            assertEquals(Qa40xLevels.outputFullScaleRmsVolts(dbv, cal.dacLinearFactor(dbv, false)),
                    row.getFsLeft(), TOL, "output fsLeft at " + dbv + " dBV");
            assertEquals(Qa40xLevels.outputFullScaleRmsVolts(dbv, cal.dacLinearFactor(dbv, true)),
                    row.getFsRight(), TOL, "output fsRight at " + dbv + " dBV");
        }

        // Default active ranges when the card is first created (PyQa40x defaults).
        assertEquals("0 dBV", in.getActiveRange());
        assertEquals("18 dBV", out.getActiveRange());
    }

    @Test
    void buildProfile_preservesUserActiveRangeAcrossRefresh() {
        Qa40xCalibration cal = Qa40xCalibration.fromBlob(syntheticBlob());
        Qa40xDeviceManager mgr = new Qa40xDeviceManager(new FakeTransport(), INSTANT);

        AudioDeviceProfile existing = new AudioDeviceProfile();
        existing.setName("QA403");
        existing.getInput().setActiveRange("24 dBV");    // a valid, non-default input range
        existing.getOutput().setActiveRange("-12 dBV");  // a valid, non-default output range

        AudioDeviceProfile refreshed = mgr.buildProfile("QA403", cal, existing);

        assertEquals("24 dBV", refreshed.getInput().getActiveRange(),
                "the user's input active-range selection survives a device refresh");
        assertEquals("-12 dBV", refreshed.getOutput().getActiveRange(),
                "the user's output active-range selection survives a device refresh");
    }

    // --- session lifecycle ---------------------------------------------------

    @Test
    void engineSurvivesCaptureReopenWhileGeneratorAttached_stopsOnLastDetach() throws Exception {
        FakeTransport fake = new FakeTransport();
        Qa40xDeviceManager mgr = new Qa40xDeviceManager(fake, INSTANT);

        Qa40xGenerator gen = (Qa40xGenerator) mgr.openPlayback(
                new Qa40xDeviceManager.Qa40xDeviceRef(0, "QA403", Qa40xModel.QA403), RATE_HZ, 0);
        gen.open();
        SignalGenerator sig = new SignalGenerator(GenSignalForm.SINE, 1_000.0, RATE_HZ, 0.1, 1.0);
        AtomicBoolean stop = new AtomicBoolean(false);
        CountDownLatch ready = new CountDownLatch(1);
        Thread player = new Thread(() -> gen.play(sig, stop, ready), "qa40x-test-play");
        player.setDaemon(true);
        player.start();

        assertTrue(ready.await(2, TimeUnit.SECONDS), "generator primes and signals ready");
        assertEquals(0, fake.cancelAllCount, "stream is up (no teardown yet)");

        // Capture attaches to the RUNNING stream, then closes — the generator is
        // still attached, so the engine must NOT tear down.
        AudioCapture rec = mgr.openCapture(null, RATE_HZ);
        rec.open();
        rec.startRecording();
        rec.stopRecording();
        rec.close();
        assertEquals(0, fake.cancelAllCount, "capture close leaves the generator's stream running");

        // Re-open capture on the same still-running engine — still no restart.
        AudioCapture rec2 = mgr.openCapture(null, RATE_HZ);
        rec2.open();
        rec2.startRecording();
        assertEquals(0, fake.cancelAllCount, "reopen attaches live, no restart");
        rec2.stopRecording();
        rec2.close();
        assertEquals(0, fake.cancelAllCount);

        // The generator is the last client — detaching it stops the stream.
        stop.set(true);
        player.join(2_000);
        assertEquals(1, fake.cancelAllCount, "last client detach tears the session down");
    }

    @Test
    void rangeChangeWhileStreaming_restartsSession() throws Exception {
        // The card's active-range radios drive the engine range via the manager;
        // a range change on a running session is a full stop + start (doc §10).
        FakeTransport fake = new FakeTransport();
        Qa40xDeviceManager mgr = new Qa40xDeviceManager(fake, INSTANT);
        AudioCapture rec = mgr.openCapture(null, RATE_HZ);
        rec.open();
        rec.startRecording();                 // engine streaming

        mgr.setInputRange(24);
        assertEquals(1, fake.cancelAllCount, "input range change restarts the running session");
        mgr.setOutputRange(-12);
        assertEquals(2, fake.cancelAllCount, "output range change restarts the running session");

        rec.stopRecording();
        rec.close();
    }

    @Test
    void generatorUsesPeakConvention_noExtraSqrt2Factor() {
        // A full-scale-peak sine (Vrms = dacFs/√2 → peak amplitude 1.0) must reach
        // ~±MAXINT on the wire.  A spurious RMS/√2 factor would cap it near
        // 0.707·MAXINT — this pins the peak convention (doc §6 / Qa40xLevels).
        FakeTransport fake = new FakeTransport();
        Qa40xDeviceManager mgr = new Qa40xDeviceManager(fake, INSTANT);
        Qa40xGenerator gen = (Qa40xGenerator) mgr.openPlayback(null, RATE_HZ, 0);
        gen.open();

        SignalGenerator sig = new SignalGenerator(GenSignalForm.SINE, 1_000.0, RATE_HZ, 1.0, Math.sqrt(2.0));
        AtomicBoolean alreadyStopped = new AtomicBoolean(true);
        gen.play(sig, alreadyStopped, new CountDownLatch(1));   // attaches, primes, exits immediately

        assertFalse(fake.submittedWrites.isEmpty(), "priming submitted at least one DAC chunk");
        int peak = 0;
        byte[] chunk = fake.submittedWrites.get(0);
        for (int f = 0; f * 8 + 8 <= chunk.length; f++) {
            int left = leInt(chunk, f * 8 + 4);   // frame = [R LE][L LE]; left is the second word
            peak = Math.max(peak, Math.abs(left));
        }
        assertTrue(peak > (int) (0.95 * Qa40xLevels.MAXINT),
                "full-scale peak sine must reach ~MAXINT, not ~0.707·MAXINT (peak != √2·RMS): " + peak);
    }

    // --- helpers -------------------------------------------------------------

    private int leInt(byte[] b, int o) {
        return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8 | (b[o + 2] & 0xFF) << 16 | b[o + 3] << 24;
    }

    private DeviceRange rowByLabel(DeviceEndpointConfig ep, String label) {
        for (DeviceRange r : ep.getRanges()) {
            if (label.equals(r.getLabel())) {
                return r;
            }
        }
        throw new IllegalArgumentException("no range row labelled " + label);
    }

    /** A 512-byte cal page with a distinct, non-zero dB per range/channel (right =
     *  -left), so parsed L/R factors differ — mirrors {@code Qa40xCalibrationTest}. */
    private byte[] syntheticBlob() {
        byte[] blob = new byte[Qa40xCalibration.CAL_PAGE_BYTES];
        for (int dbv : Qa40xProtocol.inputRangeDbvValues()) {
            int code = Qa40xProtocol.inputRangeCode(dbv);
            int leftOffset = ADC_BASE + code * RANGE_STRIDE;
            putRecord(blob, leftOffset, (short) dbv, 1.0f + code);
            putRecord(blob, leftOffset + RECORD_BYTES, (short) dbv, -(1.0f + code));
        }
        for (int dbv : Qa40xProtocol.outputRangeDbvValues()) {
            int code = Qa40xProtocol.outputRangeCode(dbv);
            int leftOffset = DAC_BASE + code * RANGE_STRIDE;
            putRecord(blob, leftOffset, (short) dbv, 2.0f + code);
            putRecord(blob, leftOffset + RECORD_BYTES, (short) dbv, -(2.0f + code));
        }
        return blob;
    }

    private void putRecord(byte[] blob, int offset, short level, float db) {
        ByteBuffer record = ByteBuffer.wrap(blob, offset, RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        record.putShort(level);
        record.putFloat(db);
    }
}
