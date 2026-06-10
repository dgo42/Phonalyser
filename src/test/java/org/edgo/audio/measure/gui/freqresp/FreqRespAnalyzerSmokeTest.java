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

package org.edgo.audio.measure.gui.freqresp;

import org.edgo.audio.measure.cli.util.StereoSamples;
import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.sound.DeviceRef;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke tests for {@link FreqRespAnalyzer}.  All tests inject a mock
 * {@link StereoCaptureProvider} that synthesises a delay-line response from
 * the generator's reference sweep on BOTH channels — the deconvolution
 * should recover a flat unity magnitude across the swept band on each.
 * Validates the orchestration (validate / progress / cancel / raw-capture
 * listener) without touching any real audio hardware.
 */
class FreqRespAnalyzerSmokeTest {

    private static final int    SAMPLE_RATE    = 48000;
    private static final int    DELAY_SAMPLES  = 192;        // 4 ms
    private static final double START_HZ       = 20.0;
    private static final double STOP_HZ        = 20000.0;
    private static final int    SWEEP_POINTS   = 64;
    private static final double DURATION_SEC   = 1.0;
    private static final double LEAD_IN_SEC    = 0.1;
    private static final double AMP_VRMS       = 0.5;
    /** Literal calibration values (the factory defaults) — both the stub's
     *  capture-side scaling and the analyzer's normalisation use these, so the
     *  test is self-consistent and needs no Preferences singleton. */
    private static final double DAC_FS_VRMS    = 2.79351;
    private static final double ADC_FS_VRMS    = 1.7931;

    @Test
    void deconvolutionRecoversFlatResponseFromDelayLineOnBothChannels() throws Exception {
        FreqRespAnalyzerConfig cfg = baseConfig()
                .stereoCaptureProvider(delayLineProvider(DELAY_SAMPLES)).build();
        StereoFreqRespResult stereo = new FreqRespAnalyzer(cfg).run(null, null);

        assertNotNull(stereo, "analyzer must return a stereo result");
        assertNotNull(stereo.left(),  "left channel populated");
        assertNotNull(stereo.right(), "right channel populated");
        assertEquals(Channel.L, stereo.left().getChannel());
        assertEquals(Channel.R, stereo.right().getChannel());
        assertEquals(SWEEP_POINTS, stereo.left().getFreqs().length);
        assertEquals(SAMPLE_RATE, stereo.left().getSampleRate());
        assertFalse(stereo.left().isCalibrationApplied());
        assertNull(stereo.left().getSourceFilePath());
        // Delay line on both channels → unity magnitude in the middle band.
        int lo = (int) (SWEEP_POINTS * 0.10);
        int hi = (int) (SWEEP_POINTS * 0.90);
        for (int i = lo; i < hi; i++) {
            double mL = stereo.left().getMagLin()[i];
            double mR = stereo.right().getMagLin()[i];
            assertTrue(mL > 0.5 && mL < 2.0,
                    "expected unity mag L, got " + mL + " at f=" + stereo.left().getFreqs()[i]);
            assertTrue(mR > 0.5 && mR < 2.0,
                    "expected unity mag R, got " + mR + " at f=" + stereo.right().getFreqs()[i]);
        }
    }

    @Test
    void progressCallbackFiresInAscendingOrder() throws Exception {
        AtomicReference<Double> lastFraction = new AtomicReference<>(-1.0);
        AtomicInteger fires = new AtomicInteger();
        ProgressCallback progress = (frac, msg) -> {
            assertNotNull(msg, "progress message must be non-null");
            assertTrue(frac >= 0.0 && frac <= 1.0, "fraction in [0,1]: " + frac);
            assertTrue(frac >= lastFraction.get(),
                    "progress must be non-decreasing: " + lastFraction.get() + " → " + frac);
            lastFraction.set(frac);
            fires.incrementAndGet();
        };

        FreqRespAnalyzerConfig cfg = baseConfig()
                .stereoCaptureProvider(delayLineProvider(DELAY_SAMPLES))
                .build();
        new FreqRespAnalyzer(cfg).run(progress, null);

        assertTrue(fires.get() >= 3,
                "expected at least 3 progress events, got " + fires.get());
        assertEquals(1.0, lastFraction.get(), 1e-9, "last fraction must reach 1.0");
    }

    @Test
    void cancelTokenAbortsBeforeCapture() {
        AtomicInteger captureCalls = new AtomicInteger();
        FreqRespAnalyzerConfig cfg = baseConfig()
                .stereoCaptureProvider((g, o, i, sr, bd, d, dur, c) -> {
                    captureCalls.incrementAndGet();
                    return new StereoSamples(new float[sr * dur], new float[sr * dur]);
                })
                .build();
        Cancellable alwaysCancelled = () -> true;
        assertThrows(InterruptedException.class,
                () -> new FreqRespAnalyzer(cfg).run(null, alwaysCancelled));
        assertEquals(0, captureCalls.get(),
                "capture must not run when token is cancelled before sweep starts");
    }

    @Test
    void rawCaptureListenerFiresWithCapturedStereoSamples() throws Exception {
        AtomicInteger listenerHits = new AtomicInteger();
        AtomicReference<Integer> leftCount  = new AtomicReference<>();
        AtomicReference<Integer> rightCount = new AtomicReference<>();
        RawCaptureListener listener = samples -> {
            listenerHits.incrementAndGet();
            leftCount.set(samples.left().length);
            rightCount.set(samples.right().length);
        };
        FreqRespAnalyzerConfig cfg = baseConfig()
                .stereoCaptureProvider(delayLineProvider(DELAY_SAMPLES))
                .rawCaptureListener(listener)
                .build();
        new FreqRespAnalyzer(cfg).run(null, null);
        assertEquals(1, listenerHits.get(), "raw-capture listener fires exactly once");
        assertNotNull(leftCount.get());
        assertNotNull(rightCount.get());
        assertTrue(leftCount.get() > 0);
        assertEquals(leftCount.get(), rightCount.get(), "L and R same length");
    }

    @Test
    void validateRejectsBadConfig() {
        FreqRespAnalyzerConfig noDevices = baseConfig()
                .outDevice(null).inDevice(null).build();
        assertThrows(IllegalArgumentException.class,
                () -> new FreqRespAnalyzer(noDevices).run(null, null));

        FreqRespAnalyzerConfig inverted = baseConfig().startHz(100).stopHz(50).build();
        assertThrows(IllegalArgumentException.class,
                () -> new FreqRespAnalyzer(inverted).run(null, null));

        FreqRespAnalyzerConfig badBits = baseConfig().bitDepth(20).build();
        assertThrows(IllegalArgumentException.class,
                () -> new FreqRespAnalyzer(badBits).run(null, null));
    }

    private FreqRespAnalyzerConfig.FreqRespAnalyzerConfigBuilder baseConfig() {
        return FreqRespAnalyzerConfig.builder()
                .outDevice(stubDevice("out", false, true))
                .inDevice (stubDevice("in",  true,  false))
                .sampleRate(SAMPLE_RATE)
                .bitDepth(24)
                .ditherBits(0)
                .startHz(START_HZ)
                .stopHz(STOP_HZ)
                .sweepPoints(SWEEP_POINTS)
                .durationSec(DURATION_SEC)
                .leadInSec(LEAD_IN_SEC)
                .amplitudeVrms(AMP_VRMS)
                .dacFsVoltageRms(DAC_FS_VRMS)
                .adcFsVoltageRms(ADC_FS_VRMS)
                .applyCalibration(false);
    }

    /** Returns a StereoCaptureProvider that emits the same delay-line
     *  response on both channels.  Both deconvolutions should recover a
     *  flat unity magnitude across the band. */
    private StereoCaptureProvider delayLineProvider(int delaySamples) {
        return (gen, outDev, inDev, sr, bd, dither, durationSec, cancel) -> {
            float[] sweep = gen.getLogSweepBuffer();
            assertNotNull(sweep, "generator must expose log-sweep buffer");
            int leadIn = (int) Math.round(LEAD_IN_SEC * sr);
            int total  = leadIn + delaySamples + sweep.length + sr / 2;
            float[] y  = new float[total];
            int offset = leadIn + delaySamples;
            // Capture-side scaling: the real DAC turns a unit-amplitude
            // sweep into a signal whose peak is amplitudeVRms·√2 / FS, which
            // the ADC reads back as that same fraction of full-scale.  The
            // deconvolution then divides this factor back out to recover
            // unity passband, so the test signal must include it.
            double dacDrivePeak = AMP_VRMS * Math.sqrt(2.0) / DAC_FS_VRMS;
            for (int i = 0; i < sweep.length; i++) {
                if (offset + i < y.length) {
                    y[offset + i] = (float) (sweep[i] * dacDrivePeak);
                }
            }
            // Same data on both channels for the test — the analyzer should
            // still deconvolve them independently in parallel.
            return new StereoSamples(y, y.clone());
        };
    }

    private DeviceRef stubDevice(String name, boolean input, boolean output) {
        return new DeviceRef() {
            @Override public int index() { return 0; }
            @Override public String name() { return name; }
            @Override public String description() { return name + " (stub)"; }
            @Override public String vendor() { return "test"; }
            @Override public AudioBackendType backend() { return AudioBackendType.JAVASOUND; }
            @Override public boolean isInput()  { return input; }
            @Override public boolean isOutput() { return output; }
        };
    }
}
