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

import org.edgo.audio.measure.cli.util.CaptureWithGenerator;
import org.edgo.audio.measure.cli.util.StereoCaptureProgress;
import org.edgo.audio.measure.cli.util.StereoSamples;
import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.sound.DeviceRef;

import java.util.function.BooleanSupplier;

/**
 * Strategy for running the sweep-and-capture leg of a Frequency Response
 * measurement.  Plays one sweep on the output device and keeps both ADC
 * channels of the capture so the deconvolution can recover L and R from
 * a single playback.
 *
 * <p>Production code uses {@link #real()} which delegates to
 * {@link CaptureWithGenerator#runStereo}; unit tests inject a stub
 * returning synthetic {@link StereoSamples} without opening any audio
 * device.  Tests only need to implement the SAM
 * {@link #capture(SignalGenerator, DeviceRef, DeviceRef, int, int, int, OutputChannels, int, BooleanSupplier)};
 * the variant with progress falls through to the SAM by default so a
 * lambda-style stub doesn't need to know about progress at all.
 */
@FunctionalInterface
public interface StereoCaptureProvider {

    /**
     * Plays {@code gen}'s output on {@code outDevice} and records both
     * channels of {@code inDevice} for {@code durationSec} seconds,
     * returning the captured samples normalised to {@code [-1, +1]}.
     *
     * @param outputChannels which DAC lane(s) carry the sweep — {@code LEFT} /
     *                       {@code RIGHT} write digital silence to the other
     *                       lane; {@code BOTH} drives both (legacy)
     * @param cancelToken polled during the capture wait; non-null tokens
     *                    allow the call to return early
     */
    StereoSamples capture(SignalGenerator gen, DeviceRef outDevice, DeviceRef inDevice,
                          int sampleRate, int bitDepth, int ditherBits,
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
                                              int sampleRate, int bitDepth, int ditherBits,
                                              OutputChannels outputChannels,
                                              int durationSec,
                                              BooleanSupplier cancelToken,
                                              StereoCaptureProgress progress) throws Exception {
        return capture(gen, outDevice, inDevice, sampleRate, bitDepth, ditherBits,
                       outputChannels, durationSec, cancelToken);
    }

    /** Returns the production capture strategy that drives real audio
     *  hardware via {@link CaptureWithGenerator#runStereo}.  Both the
     *  no-progress SAM and the {@link #captureWithProgress} variant are
     *  overridden — the latter actually forwards live block progress. */
    /** Localized pre-check for the sweep capture: refuses — with the real
     *  numbers — when the two capture lanes (plus their trim copies) cannot
     *  fit the heap, BEFORE the generator starts.  The English guard inside
     *  {@link CaptureWithGenerator#runStereo} stays as the CLI / log
     *  backstop; this one carries the i18n text the measurement-failed
     *  dialog shows. */
    default void ensureCaptureFits(int sampleRate, int durationSec) {
        long needBytes = CaptureWithGenerator.stereoCaptureHeapBytes(sampleRate, durationSec);
        long freeBytes = CaptureWithGenerator.heapShortfall(needBytes);
        if (freeBytes >= 0) {
            throw new IllegalArgumentException(I18n.t("freqResp.capture.tooLarge",
                    needBytes >> 20, freeBytes >> 20));
        }
    }

    static StereoCaptureProvider real() {   // static-ok: interface factory — no instance exists to hang it on
        return new StereoCaptureProvider() {
            @Override
            public StereoSamples capture(SignalGenerator gen, DeviceRef outDevice, DeviceRef inDevice,
                                         int sampleRate, int bitDepth, int ditherBits,
                                         OutputChannels outputChannels,
                                         int durationSec,
                                         BooleanSupplier cancelToken) throws Exception {
                ensureCaptureFits(sampleRate, durationSec);
                return CaptureWithGenerator.runStereo(gen, outDevice, inDevice,
                        sampleRate, bitDepth, ditherBits, outputChannels,
                        durationSec, null, 0, cancelToken, null);
            }
            @Override
            public StereoSamples captureWithProgress(SignalGenerator gen, DeviceRef outDevice, DeviceRef inDevice,
                                                     int sampleRate, int bitDepth, int ditherBits,
                                                     OutputChannels outputChannels,
                                                     int durationSec,
                                                     BooleanSupplier cancelToken,
                                                     StereoCaptureProgress progress) throws Exception {
                ensureCaptureFits(sampleRate, durationSec);
                return CaptureWithGenerator.runStereo(gen, outDevice, inDevice,
                        sampleRate, bitDepth, ditherBits, outputChannels,
                        durationSec, null, 0, cancelToken, progress);
            }
        };
    }
}
