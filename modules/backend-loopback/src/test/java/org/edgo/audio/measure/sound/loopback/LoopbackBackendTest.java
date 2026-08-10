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

package org.edgo.audio.measure.sound.loopback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioFormat;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.sound.AudioCapture;
import org.edgo.audio.measure.sound.AudioDeviceManagerProvider;
import org.edgo.audio.measure.sound.AudioPlayback;
import org.edgo.audio.measure.sound.DeviceRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Behaviour of the digital loopback backend: discovery, the format ladder, that
 * what is played arrives, and that the delivered floor is the arithmetic TPDF
 * floor of the selected depth.
 */
class LoopbackBackendTest {

    private static final int RATE = 48000;
    private static final int LADDER_RATES = 15;
    private static final int DEPTHS = 4;
    /** Delivered TPDF total error: dither (0.408 LSB RMS) plus the rounding it
     *  shapes gives exactly 0.5 LSB RMS on the quantised samples. */
    private static final double EXPECTED_FLOOR_LSB = 0.5;
    private static final double FLOOR_TOLERANCE_LSB = 0.06;
    /** Milliseconds of capture used by the delivery tests. */
    private static final long CAPTURE_MS = 400;
    /** A captured block counts as signal when it swings this far from midpoint. */
    private static final int SIGNAL_THRESHOLD_LSB = 1000;

    /** Collects delivered blocks, decoded to signed sample values in LSB. */
    private List<int[]> capture(AudioCapture capture, long millis, Runnable whileRecording)
            throws Exception {
        List<int[]> blocks = new ArrayList<>();
        capture.setPcmBatchListener((pcm, validBytes) -> {
            int frameSize = capture.getFormat().getFrameSize();
            int sampleBytes = frameSize / 2;
            int frames = validBytes / frameSize;
            int midpoint = 1 << (capture.getFormat().getSampleSizeInBits() - 1);
            int[] left = new int[frames];
            for (int f = 0; f < frames; f++) {
                left[f] = capture.readSample(pcm, f * frameSize) - midpoint;
            }
            if (sampleBytes > 0) {
                blocks.add(left);
            }
        });
        capture.open();
        capture.startRecording();
        if (whileRecording != null) {
            whileRecording.run();
        }
        Thread.sleep(millis);
        capture.stopRecording();
        capture.close();
        return blocks;
    }

    private double rmsLsb(List<int[]> blocks, int skipBlocks) {
        double sum = 0;
        long n = 0;
        for (int i = skipBlocks; i < blocks.size(); i++) {
            for (int v : blocks.get(i)) {
                sum += (double) v * v;
                n++;
            }
        }
        return n == 0 ? Double.NaN : Math.sqrt(sum / n);
    }

    @Test
    @DisplayName("the provider is discoverable through the SPI")
    void spiDiscoversProvider() {
        AudioDeviceManagerProvider found = null;
        for (AudioDeviceManagerProvider p : ServiceLoader.load(AudioDeviceManagerProvider.class)) {
            if (p.backendType() == AudioBackendType.LOOPBACK) {
                found = p;
            }
        }
        assertNotNull(found, "no provider registered for LOOPBACK");
        assertTrue(found.available());
        assertNotNull(found.create());
        assertTrue(AudioBackendType.LOOPBACK.isAvailable(), "LOOPBACK must be available on every OS");
    }

    @Test
    @DisplayName("one device, listed both ways, with the full rate x depth ladder")
    void capabilityLists() {
        LoopbackDeviceManager manager = new LoopbackDeviceManager();
        assertEquals(1, manager.listInputDevices().size());
        assertEquals(1, manager.listOutputDevices().size());
        DeviceRef device = manager.listInputDevices().get(0);

        for (boolean output : new boolean[] { false, true }) {
            List<AudioFormat> formats = manager.listSupportedFormats(device, output);
            assertEquals(LADDER_RATES * DEPTHS, formats.size(),
                    "expected the full ladder for output=" + output);
            assertTrue(formats.stream().anyMatch(f -> f.getSampleRate() == 8000f));
            assertTrue(formats.stream().anyMatch(f -> f.getSampleRate() == 32000f));
            assertTrue(formats.stream().anyMatch(f -> f.getSampleRate() == 768000f));
            for (int depth : new int[] { 16, 20, 24, 32 }) {
                assertTrue(formats.stream().anyMatch(f -> f.getSampleSizeInBits() == depth),
                        "missing depth " + depth);
            }
        }
    }

    @Test
    @DisplayName("quantised silence carries the last-bit dither floor")
    void silenceCarriesDitherFloor() throws Exception {
        LoopbackDeviceManager manager = new LoopbackDeviceManager();
        DeviceRef device = manager.listInputDevices().get(0);
        List<int[]> blocks = capture(manager.openCapture(device, RATE, 24), CAPTURE_MS, null);
        assertTrue(blocks.size() > 1, "no blocks delivered");
        double rms = rmsLsb(blocks, 1);
        assertTrue(Math.abs(rms - EXPECTED_FLOOR_LSB) < FLOOR_TOLERANCE_LSB,
                "delivered silence RMS should be ~" + EXPECTED_FLOOR_LSB + " LSB, was " + rms);
    }

    @Test
    @DisplayName("the floor is one LSB of the SELECTED depth, so it falls as depth rises")
    void floorFollowsBitDepth() throws Exception {
        LoopbackDeviceManager manager = new LoopbackDeviceManager();
        DeviceRef device = manager.listInputDevices().get(0);
        // Same LSB count at both depths, but an LSB at 24 bits is 2^8 times
        // smaller than at 16 - so relative to full scale the floor drops.
        double rms16 = rmsLsb(capture(manager.openCapture(device, RATE, 16), CAPTURE_MS, null), 1);
        double rms24 = rmsLsb(capture(manager.openCapture(device, RATE, 24), CAPTURE_MS, null), 1);
        assertTrue(Math.abs(rms16 - EXPECTED_FLOOR_LSB) < FLOOR_TOLERANCE_LSB, "16 bit: " + rms16);
        assertTrue(Math.abs(rms24 - EXPECTED_FLOOR_LSB) < FLOOR_TOLERANCE_LSB, "24 bit: " + rms24);
        double full16 = rms16 / Math.pow(2, 15);
        double full24 = rms24 / Math.pow(2, 23);
        assertTrue(full24 < full16 / 100, "floor relative to full scale must fall with depth");
    }

    @Test
    @DisplayName("what the playback lane emits arrives at the capture lane")
    void playedSignalArrives() throws Exception {
        LoopbackDeviceManager manager = new LoopbackDeviceManager();
        DeviceRef device = manager.listInputDevices().get(0);
        int depth = 24;
        AudioPlayback playback = manager.openPlayback(device, RATE, depth, 0.0);
        playback.open();
        AtomicBoolean stop = new AtomicBoolean(false);
        SignalGenerator gen = new SignalGenerator(GenSignalForm.SINE, 1000.0, RATE, 0.5, 1.0);
        Thread player = new Thread(() -> playback.play(gen, stop, null), "test-player");

        List<int[]> blocks = capture(manager.openCapture(device, RATE, depth), CAPTURE_MS, player::start);
        stop.set(true);
        player.join(2000);
        playback.close();

        int[] signal = null;
        for (int[] block : blocks) {
            int peak = 0;
            for (int v : block) {
                peak = Math.max(peak, Math.abs(v));
            }
            if (peak > SIGNAL_THRESHOLD_LSB) {
                signal = block;
                break;
            }
        }
        assertNotNull(signal, "the played signal never reached the capture lane");

        // The first delivered signal block is the generator's first block, so an
        // identically seeded generator quantised here is the reference; only the
        // TPDF dither differs, which is bounded by one LSB.
        SignalGenerator reference = new SignalGenerator(GenSignalForm.SINE, 1000.0, RATE, 0.5, 1.0);
        long maxVal = (1L << (depth - 1)) - 1;
        for (int i = 0; i < signal.length; i++) {
            long expected = Math.round(reference.nextSample() * maxVal);
            assertTrue(Math.abs(expected - signal[i]) <= 1,
                    "sample " + i + ": expected ~" + expected + " but captured " + signal[i]);
        }
    }

    @Test
    @DisplayName("delivery is paced by the wall clock, not by the CPU")
    void deliveryIsWallClockPaced() throws Exception {
        LoopbackDeviceManager manager = new LoopbackDeviceManager();
        DeviceRef device = manager.listInputDevices().get(0);
        long started = System.nanoTime();
        List<int[]> blocks = capture(manager.openCapture(device, RATE, 24), CAPTURE_MS, null);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        long frames = 0;
        for (int[] block : blocks) {
            frames += block.length;
        }
        long expectedMs = frames * 1000L / RATE;
        assertTrue(frames > 0, "nothing delivered");
        assertTrue(Math.abs(elapsedMs - expectedMs) < CAPTURE_MS / 2,
                "delivered " + frames + " frames (" + expectedMs + " ms of audio) in " + elapsedMs + " ms");
    }

    @Test
    @DisplayName("a second capture on the same device delivers just like the first")
    void consecutiveCapturesBothDeliver() throws Exception {
        LoopbackDeviceManager manager = new LoopbackDeviceManager();
        DeviceRef device = manager.listInputDevices().get(0);
        double first = rmsLsb(capture(manager.openCapture(device, RATE, 24), CAPTURE_MS, null), 1);
        double second = rmsLsb(capture(manager.openCapture(device, RATE, 24), CAPTURE_MS, null), 1);
        assertTrue(Math.abs(first - EXPECTED_FLOOR_LSB) < FLOOR_TOLERANCE_LSB, "first: " + first);
        assertTrue(Math.abs(second - EXPECTED_FLOOR_LSB) < FLOOR_TOLERANCE_LSB, "second: " + second);
    }
}
