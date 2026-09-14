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

package org.edgo.audio.measure.gui.freqresp;

import org.edgo.audio.measure.sound.StereoSamples;
import org.edgo.audio.measure.common.Constants;
import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.generator.SignalGenerator;
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
 * the generator's reference sweep on BOTH channels - the deconvolution
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
    /** Literal calibration values - FIXTURES of this test, not the shipped
     *  defaults, which have since moved.  Both the stub's capture-side scaling and
     *  the analyzer's normalisation use these two, so the test is self-consistent
     *  and needs no Preferences singleton; what they are is immaterial as long as
     *  the two sides agree. */
    private static final double DAC_FS_VRMS    = 2.79351;
    private static final double ADC_FS_VRMS    = 1.7931;
    /** A 16-bit positive rail on the normalised scale - 32767/32768, the
     *  highest level a 16-bit capture can deliver. */
    private static final double RAIL_16_BIT    = 32767.0 / 32768.0;
    /** Hot but unrailed: −0.0087 dBFS, just under the clip threshold. */
    private static final double NEAR_RAIL_LIN  = 0.999;
    /** A DC offset (normalised full scale) that leaves the chirp's peak of
     *  {@code AMP_VRMS·√2 / DAC_FS_VRMS ≈ 0.25} under the rail. */
    private static final double OFFSET_UNRAILED = 0.5;
    /** An offset that pushes that same peak over the rail. */
    private static final double OFFSET_RAILING  = 0.9;

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
        // Delay line on both channels -> unity magnitude in the middle band.
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
                    "progress must be non-decreasing: " + lastFraction.get() + " -> " + frac);
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

    /**
     * The same flat delay line measured with the DAC on ITS OWN, faster clock -
     * 96 kHz out against 48 kHz in, the shape a card whose two sides are set
     * independently really presents (the maintainer bench runs 384 kHz out /
     * 192 kHz in).
     *
     * <p>Three separate things have to be right at once for this to pass, and
     * each of them was wrong when one rate served both converters: the playback
     * leg has to be described on the output clock, the recording and the
     * deconvolution reference have to be counted on the input clock, and the
     * lead-in has to be an index into the RECORD - a lead-in taken from the
     * output clock would sit 0.1 s x (96000 - 48000) = 4800 samples off, which
     * the impulse-response delay correction would quietly absorb into a
     * plausible-looking trace instead of failing.  Unity magnitude across the
     * band is what proves the reference and the recording are the same chirp.
     */
    @Test
    void aFasterDacClockStillDeconvolvesFlatAndReportsTheCaptureRate() throws Exception {
        FreqRespAnalyzerConfig cfg = baseConfig()
                .playbackRateHz(SAMPLE_RATE * 2)
                .playbackBitDepth(32)
                .stereoCaptureProvider(delayLineProvider(DELAY_SAMPLES)).build();
        StereoFreqRespResult stereo = new FreqRespAnalyzer(cfg).run(null, null);

        assertEquals(SAMPLE_RATE, stereo.left().getSampleRate(),
                "the result carries the CAPTURE rate - it is the measurement's own "
                        + "Nyquist and what every consumer draws and saves against");
        assertEquals(SAMPLE_RATE, stereo.right().getSampleRate());
        int lo = (int) (SWEEP_POINTS * 0.10);
        int hi = (int) (SWEEP_POINTS * 0.90);
        for (int i = lo; i < hi; i++) {
            double mL = stereo.left().getMagLin()[i];
            double mR = stereo.right().getMagLin()[i];
            assertTrue(mL > 0.5 && mL < 2.0,
                    "expected unity mag L across two clocks, got " + mL
                            + " at f=" + stereo.left().getFreqs()[i]);
            assertTrue(mR > 0.5 && mR < 2.0,
                    "expected unity mag R across two clocks, got " + mR
                            + " at f=" + stereo.right().getFreqs()[i]);
        }
    }

    /** The capture leg is handed BOTH configured formats, each one whole: the
     *  DAC's rate and word length for the line it opens, the ADC's rate for the
     *  record it assembles.  Pinned because the failure this whole split exists
     *  to remove was a single argument arriving at two converters. */
    @Test
    void theCaptureLegReceivesEachConvertersOwnFormat() throws Exception {
        AtomicInteger seenCaptureRate  = new AtomicInteger();
        AtomicInteger seenPlaybackRate = new AtomicInteger();
        AtomicInteger seenPlaybackBits = new AtomicInteger();
        FreqRespAnalyzerConfig cfg = baseConfig()
                .playbackRateHz(SAMPLE_RATE * 2)
                .playbackBitDepth(32)
                .stereoCaptureProvider((g, o, i, capRate, playRate, playBits, d, oc, dur, c) -> {
                    seenCaptureRate.set(capRate);
                    seenPlaybackRate.set(playRate);
                    seenPlaybackBits.set(playBits);
                    return new StereoSamples(new double[capRate * dur], new double[capRate * dur]);
                })
                .build();
        new FreqRespAnalyzer(cfg).run(null, null);

        assertEquals(SAMPLE_RATE, seenCaptureRate.get(), "the ADC's configured rate");
        assertEquals(SAMPLE_RATE * 2, seenPlaybackRate.get(), "the DAC's configured rate");
        assertEquals(32, seenPlaybackBits.get(), "and the DAC's configured word length");
    }

    @Test
    void cancelTokenAbortsBeforeCapture() {
        AtomicInteger captureCalls = new AtomicInteger();
        FreqRespAnalyzerConfig cfg = baseConfig()
                .stereoCaptureProvider((g, o, i, capRate, playRate, playBits, d, oc, dur, c) -> {
                    captureCalls.incrementAndGet();
                    return new StereoSamples(new double[capRate * dur], new double[capRate * dur]);
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
    void rawPeakReportsTheCaptureLevelAndClearsTheClipFlag() throws Exception {
        FreqRespAnalyzerConfig cfg = baseConfig()
                .stereoCaptureProvider(delayLineProvider(DELAY_SAMPLES)).build();
        StereoFreqRespResult stereo = new FreqRespAnalyzer(cfg).run(null, null);

        // The stub drives the sweep at amplitudeVrms·√2 / dacFs of full scale;
        // the raw peak must report that level, not 0 and not the rail.
        double drivePeak = AMP_VRMS * Constants.SQRT2 / DAC_FS_VRMS;
        assertEquals(drivePeak, stereo.rawPeakLin(), 1e-3,
                "raw peak must track the captured level");
        assertFalse(stereo.clipped(), "a sweep a long way below the rail is not clipped");
    }

    @Test
    void railedSampleOnEitherChannelMarksTheResultClipped() throws Exception {
        // Rail on the RIGHT channel only, and negative - so the peak scan has to
        // cover both channels and take absolute values.
        FreqRespAnalyzerConfig cfg = baseConfig()
                .stereoCaptureProvider((g, o, i, capRate, playRate, playBits, d, oc, dur, c) -> {
                    double[] left  = new double[capRate * dur];
                    double[] right = new double[capRate * dur];
                    right[right.length / 2] = -RAIL_16_BIT;
                    return new StereoSamples(left, right);
                })
                .build();
        StereoFreqRespResult stereo = new FreqRespAnalyzer(cfg).run(null, null);

        assertEquals(RAIL_16_BIT, stereo.rawPeakLin(), 1e-12);
        assertTrue(stereo.clipped(), "a capture that touches the 16-bit rail is clipped");
    }

    /**
     * A DC offset on one channel - an ADC input sitting off centre, a DUT with
     * an output offset - must not reach the trace.  The lead-in before the
     * chirp carries nothing but that offset, so its mean is taken out of the
     * record before the deconvolution; left in, the step at the record's ends
     * ripples the whole low end.
     */
    @Test
    void aDcOffsetOnOneChannelLeavesItsTraceIdenticalToTheOther() throws Exception {
        FreqRespAnalyzerConfig cfg = baseConfig()
                .stereoCaptureProvider(delayLineProvider(DELAY_SAMPLES, OFFSET_UNRAILED)).build();
        StereoFreqRespResult stereo = new FreqRespAnalyzer(cfg).run(null, null);

        double[] mL = stereo.left().getMagLin();
        double[] mR = stereo.right().getMagLin();
        for (int i = 0; i < SWEEP_POINTS; i++) {
            assertEquals(mL[i], mR[i], 1e-9,
                    "the offset channel must deconvolve exactly like the clean one at f="
                            + stereo.right().getFreqs()[i]);
        }
        assertFalse(stereo.clipped(), "an offset that stays under the rail is not clipping");
    }

    /** The rail check reads the RAW record: an offset that pushes the chirp
     *  into the rail is real clipping, information is gone there, and the
     *  result must still say so even though the offset itself is removed. */
    @Test
    void anOffsetThatDrivesTheChirpIntoTheRailIsStillReportedClipped() throws Exception {
        FreqRespAnalyzerConfig cfg = baseConfig()
                .stereoCaptureProvider(delayLineProvider(DELAY_SAMPLES, OFFSET_RAILING)).build();
        StereoFreqRespResult stereo = new FreqRespAnalyzer(cfg).run(null, null);

        assertTrue(stereo.clipped(), "offset plus chirp above the rail is clipped");
    }

    @Test
    void captureJustBelowTheThresholdIsNotClipped() throws Exception {
        FreqRespAnalyzerConfig cfg = baseConfig()
                .stereoCaptureProvider((g, o, i, capRate, playRate, playBits, d, oc, dur, c) -> {
                    double[] left  = new double[capRate * dur];
                    double[] right = new double[capRate * dur];
                    left[left.length / 2] = NEAR_RAIL_LIN;
                    return new StereoSamples(left, right);
                })
                .build();
        StereoFreqRespResult stereo = new FreqRespAnalyzer(cfg).run(null, null);

        assertEquals(NEAR_RAIL_LIN, stereo.rawPeakLin(), 1e-12);
        assertFalse(stereo.clipped(),
                "a hot but unrailed capture must not raise the clipping warning");
    }

    @Test
    void thePeakExactlyAtTheThresholdCountsAsClipped() {
        // Pins the >= against a silent drift to >: 0.9995 is the documented
        // threshold on StereoFreqRespResult, and the boundary itself must trip
        // (the 16-bit rail sits only a hair above it).
        assertTrue(new StereoFreqRespResult(null, null, 0.9995).clipped(),
                "at the threshold IS clipped - >= not >");
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

        FreqRespAnalyzerConfig badBits = baseConfig().playbackBitDepth(20).build();
        assertThrows(IllegalArgumentException.class,
                () -> new FreqRespAnalyzer(badBits).run(null, null));
    }

    /**
     * A band the DAC cannot emit is refused, not measured.
     *
     * <p>With the two clocks independent the sweep has to fit under BOTH Nyquist
     * limits: above the DAC's the chirp folds back on the way out, above the
     * ADC's on the way in, and either way the deconvolution reference - rendered
     * from the asked band, so it never folds - divides a signal that did.  The
     * damage is not confined to the top of the band: the impulse-response peak
     * that sets the delay correction is computed from the same polluted
     * division, so the whole trace comes out wrong and still looks plausible.
     * That is why this is a refusal and not a clamp.
     */
    @Test
    void aBandAboveTheLowerNyquistIsRefusedRatherThanAliased() {
        // Capture 48 kHz, DAC configured at 24 kHz -> ceiling 12 kHz, while the
        // fixture sweeps to 20 kHz.
        FreqRespAnalyzerConfig aliasing = baseConfig()
                .playbackRateHz(SAMPLE_RATE / 2)
                .stereoCaptureProvider(delayLineProvider(DELAY_SAMPLES)).build();

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> new FreqRespAnalyzer(aliasing).run(null, null));

        assertTrue(refused.getMessage().contains(String.valueOf(SAMPLE_RATE / 2))
                        && refused.getMessage().contains(String.valueOf(SAMPLE_RATE)),
                "the refusal names both configured rates - " + refused.getMessage());
    }

    private FreqRespAnalyzerConfig.FreqRespAnalyzerConfigBuilder baseConfig() {
        return FreqRespAnalyzerConfig.builder()
                .outDevice(stubDevice("out", false, true))
                .inDevice (stubDevice("in",  true,  false))
                .captureRateHz(SAMPLE_RATE)
                .playbackRateHz(SAMPLE_RATE)
                .playbackBitDepth(24)
                .ditherBits(0)
                .startHz(START_HZ)
                .stopHz(STOP_HZ)
                .sweepPoints(SWEEP_POINTS)
                .durationSec(DURATION_SEC)
                .leadInSec(LEAD_IN_SEC)
                .amplitudeVrms(AMP_VRMS)
                .dacFsVoltageRmsLeft(DAC_FS_VRMS)
                .dacFsVoltageRmsRight(DAC_FS_VRMS)
                .adcFsVoltageRmsLeft(ADC_FS_VRMS)
                .adcFsVoltageRmsRight(ADC_FS_VRMS)
                .applyCalibration(false);
    }

    /** Returns a StereoCaptureProvider that emits the same delay-line
     *  response on both channels.  Both deconvolutions should recover a
     *  flat unity magnitude across the band.
     *
     *  <p>The recording is modelled ON THE CAPTURE CLOCK, which is what an ADC
     *  really delivers: the chirp is the same stretch of time as the one the
     *  generator was handed, re-rendered at {@code capRate}.  Taking the
     *  playback buffer instead would make the stub agree with the analyzer only
     *  while the two rates happen to be equal, and it is exactly the split case
     *  these tests have to be able to fail on. */
    private StereoCaptureProvider delayLineProvider(int delaySamples) {
        return delayLineProvider(delaySamples, 0.0);
    }

    /** The same delay line with a constant DC offset (normalised full scale)
     *  added to the RIGHT channel over the whole record, lead-in included - an
     *  ADC input sitting off centre, or a DUT with an output offset. */
    private StereoCaptureProvider delayLineProvider(int delaySamples, double rightOffset) {
        return (gen, outDev, inDev, capRate, playRate, playBits, dither, oc, durationSec, cancel) -> {
            assertNotNull(gen.getLogSweepBuffer(),
                    "the generator handed to the capture leg must expose its played sweep");
            double[] sweep = new SignalGenerator(START_HZ, STOP_HZ,
                    (int) Math.round(DURATION_SEC * capRate), 0,
                    capRate, AMP_VRMS, AMP_VRMS, DAC_FS_VRMS, DAC_FS_VRMS).getLogSweepBuffer();
            int leadIn = (int) Math.round(LEAD_IN_SEC * capRate);
            int total  = leadIn + delaySamples + sweep.length + capRate / 2;
            double[] y  = new double[total];
            int offset = leadIn + delaySamples;
            // Capture-side scaling: the real DAC turns a unit-amplitude
            // sweep into a signal whose peak is amplitudeVRms·√2 / FS, which
            // the ADC reads back as that same fraction of full-scale.  The
            // deconvolution then divides this factor back out to recover
            // unity passband, so the test signal must include it.
            double dacDrivePeak = AMP_VRMS * Constants.SQRT2 / DAC_FS_VRMS;
            for (int i = 0; i < sweep.length; i++) {
                if (offset + i < y.length) {
                    y[offset + i] = sweep[i] * dacDrivePeak;
                }
            }
            // Same data on both channels for the test - the analyzer should
            // still deconvolve them independently in parallel.
            double[] right = y.clone();
            for (int i = 0; i < right.length; i++) {
                right[i] += rightOffset;
            }
            return new StereoSamples(y, right);
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
