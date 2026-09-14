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

import org.edgo.audio.measure.common.MemUtil;
import org.edgo.audio.measure.dsp.FreqRespCalHelper;
import org.edgo.audio.measure.sound.StereoCaptureProgress;
import org.edgo.audio.measure.sound.StereoSamples;
import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.sound.SweepCapture;
import org.edgo.audio.measure.sound.DeviceRef;

import java.util.function.BooleanSupplier;

/**
 * Strategy for running the sweep-and-capture leg of a Frequency Response
 * measurement.  Plays one sweep on the output device and keeps both ADC
 * channels of the capture so the deconvolution can recover L and R from
 * a single playback.
 *
 * <p>Production code uses {@link #forSweep}, whose one strategy drives the
 * shared ring through a {@code GeneratorLane}; unit tests inject a stub
 * returning synthetic {@link StereoSamples} without opening any audio
 * device.  Tests only need to implement the SAM
 * {@link #capture(SignalGenerator, DeviceRef, DeviceRef, int, int, int, int, OutputChannels, int, BooleanSupplier)};
 * the variant with progress falls through to the SAM by default so a
 * lambda-style stub doesn't need to know about progress at all.
 *
 * <p><b>Two clocks, not one.</b>  The DAC and the ADC carry their own
 * configured formats and need not agree, so the playback format and the
 * capture rate travel separately: the output line is opened on the DAC's
 * rate and word length, while the recording's length and the ring guard are
 * counted on the ADC's.
 */
@FunctionalInterface
public interface StereoCaptureProvider {

    /**
     * Plays {@code gen}'s output on {@code outDevice} and records both
     * channels of {@code inDevice} for {@code durationSec} seconds,
     * returning the captured samples normalised to {@code [-1, +1]}.
     *
     * @param captureRateHz    the ADC's configured rate - what the returned
     *                         samples are clocked at, so the record is
     *                         {@code durationSec * captureRateHz} frames long
     * @param playbackRateHz   the DAC's configured rate - the output line is
     *                         opened with it and the chirp is rendered on it
     * @param playbackBitDepth the DAC's configured word length
     * @param outputChannels which DAC lane(s) carry the sweep - {@code LEFT} /
     *                       {@code RIGHT} write digital silence to the other
     *                       lane; {@code BOTH} drives both (legacy)
     * @param cancelToken polled during the capture wait; non-null tokens
     *                    allow the call to return early
     */
    StereoSamples capture(SignalGenerator gen, DeviceRef outDevice, DeviceRef inDevice,
                          int captureRateHz, int playbackRateHz, int playbackBitDepth,
                          int ditherBits,
                          OutputChannels outputChannels,
                          int durationSec,
                          BooleanSupplier cancelToken) throws Exception;

    /**
     * Same as {@link #capture} but also forwards per-block progress
     * (cumulative sample count + block RMS) to {@code progress} as the
     * capture fills.  Default implementation delegates to {@link #capture}
     * ignoring the progress argument so test stubs that only override the
     * SAM keep working with no changes.
     */
    default StereoSamples captureWithProgress(SignalGenerator gen, DeviceRef outDevice, DeviceRef inDevice,
                                              int captureRateHz, int playbackRateHz,
                                              int playbackBitDepth, int ditherBits,
                                              OutputChannels outputChannels,
                                              int durationSec,
                                              BooleanSupplier cancelToken,
                                              StereoCaptureProgress progress) throws Exception {
        return capture(gen, outDevice, inDevice, captureRateHz, playbackRateHz,
                       playbackBitDepth, ditherBits, outputChannels, durationSec, cancelToken);
    }

    /** Localized pre-check for the sweep capture: refuses - with the real
     *  numbers - when the two capture lanes (plus their trim copies) cannot
     *  fit the heap, BEFORE the generator starts.  Sized on the CAPTURE rate,
     *  which is what the record is allocated at.  The CLI capture leg guards
     *  the same {@link MemUtil} numbers with its English backstop; this one
     *  carries the i18n text the measurement-failed dialog shows. */
    default void ensureCaptureFits(int captureRateHz, int durationSec) {
        long needBytes = MemUtil.stereoCaptureHeapBytes(captureRateHz, durationSec);
        long freeBytes = MemUtil.heapShortfall(needBytes);
        if (freeBytes >= 0) {
            throw new IllegalArgumentException(I18n.t("freqResp.capture.tooLarge",
                    needBytes >> 20, freeBytes >> 20));
        }
    }

    /**
     * The production strategy: ONE {@link SweepCapture} over ONE
     * {@link org.edgo.audio.measure.gui.sound.GeneratorLane} - the lane, not
     * this seam, decides whether the chirp is rendered here or commanded to a
     * bench, so no caller differentiates the two.  The {@link SignalGenerator}
     * the analyzer passes in stays its own deconvolution reference; the lane
     * builds the identical chirp from the same numbers.
     *
     * <p>The sweep is derived in PLAYBACK SAMPLES here, by the same formulas
     * {@link FreqRespAnalyzer#run} uses on the same seconds; they must stay in
     * step, because whichever end renders the chirp builds it from these and
     * the analyzer deconvolves against a reference built from the same seconds
     * on the capture clock.
     *
     * @param playbackRateHz the DAC's configured rate - every sample count
     *                      below is against it, which is why a lane that grants
     *                      another one fails the measurement instead of running it
     * @param durationSec   the chirp's own length, excluding the lead-in
     * @param dacFsVoltageLeft  the left lane's DAC full scale the amplitude is scaled against
     * @param dacFsVoltageRight the same for the right lane
     */
    static StereoCaptureProvider forSweep(int playbackRateHz, double startHz, double stopHz,   // static-ok: interface factory - no instance exists to hang it on
            double durationSec, double leadInSec, double amplitudeVrms,
            double dacFsVoltageLeft, double dacFsVoltageRight) {
        int sweepSamples  = (int) Math.round(durationSec * playbackRateHz);
        int leadInSamples = (int) Math.round(leadInSec * playbackRateHz);
        int fadeSamples   = FreqRespCalHelper.sweepFadeSamples(sweepSamples);
        return new StereoCaptureProvider() {
            @Override
            public StereoSamples capture(SignalGenerator gen, DeviceRef outDevice, DeviceRef inDevice,
                                         int captureRate, int playRate, int playBits, int ditherBits,
                                         OutputChannels outputChannels,
                                         int captureSec,
                                         BooleanSupplier cancelToken) throws Exception {
                return captureWithProgress(gen, outDevice, inDevice, captureRate, playRate,
                        playBits, ditherBits, outputChannels, captureSec, cancelToken, null);
            }
            @Override
            public StereoSamples captureWithProgress(SignalGenerator gen, DeviceRef outDevice, DeviceRef inDevice,
                                                     int captureRate, int playRate, int playBits,
                                                     int ditherBits,
                                                     OutputChannels outputChannels,
                                                     int captureSec,
                                                     BooleanSupplier cancelToken,
                                                     StereoCaptureProgress progress) throws Exception {
                ensureCaptureFits(captureRate, captureSec);
                return new SweepCapture().run(startHz, stopHz, sweepSamples,
                        leadInSamples, fadeSamples, amplitudeVrms, dacFsVoltageLeft, dacFsVoltageRight,
                        captureRate, playRate, playBits, ditherBits, outputChannels,
                        captureSec, cancelToken, progress);
            }
        };
    }
}
