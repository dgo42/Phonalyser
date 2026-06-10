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

import lombok.Builder;
import lombok.Getter;
import org.edgo.audio.measure.cli.util.StereoCaptureProgress;
import org.edgo.audio.measure.cli.util.StereoSamples;
import org.edgo.audio.measure.sound.DeviceRef;

/**
 * Typed bundle of parameters for one {@link FreqRespAnalyzer} run.  Build via
 * the Lombok-generated {@code builder()}.  All numeric fields are validated by
 * the analyzer before any audio device is opened; invalid combinations throw
 * {@link IllegalArgumentException} at the start of {@code run()}.
 *
 * <p>The analyzer always captures both ADC channels in one playback and
 * deconvolves them in parallel — there is no per-channel mode any more.
 */
@Getter
@Builder
public final class FreqRespAnalyzerConfig {

    /** Output (DAC) device the sweep is played on. */
    private final DeviceRef outDevice;
    /** Input (ADC) device both channels are captured from. */
    private final DeviceRef inDevice;

    /** Sample rate in Hz for both playback and capture. */
    private final int sampleRate;
    /** Bit depth (8 / 16 / 24 / 32). */
    private final int bitDepth;
    /** TPDF dither bits applied to the output before quantisation; 0 disables. */
    private final int ditherBits;

    /** Sweep start frequency in Hz; must be {@code > 0}. */
    private final double startHz;
    /** Sweep stop frequency in Hz; must be {@code > startHz}. */
    private final double stopHz;
    /** Number of linearly-spaced output points emitted by the deconvolution.
     *  Step size = {@code (stopHz - startHz) / (sweepPoints - 1)}. */
    private final int sweepPoints;
    /** Sweep duration in seconds, excluding lead-in. */
    private final double durationSec;
    /** Silent lead-in prepended to the sweep, in seconds. */
    private final double leadInSec;

    /** Generator drive amplitude in V RMS at the DAC. */
    private final double amplitudeVrms;

    /** When {@code true} and {@code FreqRespCorrectionStore.getCurrent()}
     *  is non-null, the analyzer divides each measured channel by the
     *  matching channel of the loaded calibration before returning;
     *  the resulting {@link FreqRespResult#isCalibrationApplied()} reads
     *  {@code true} for both channels. */
    private final boolean applyCalibration;

    /** Stereo capture strategy.  Production callers use
     *  {@link StereoCaptureProvider#real()}; tests inject a stub that
     *  returns synthetic {@link StereoSamples}. */
    @Builder.Default
    private final StereoCaptureProvider stereoCaptureProvider = StereoCaptureProvider.real();

    /** Optional hook fired with the raw captured stereo samples between
     *  the capture and the deconvolution steps.  Used by the CLI's
     *  {@code --sweep-wav} flag to persist the unprocessed recording
     *  without making the analyzer aware of file paths.  {@code null}
     *  means no listener. */
    @Builder.Default
    private final RawCaptureListener rawCaptureListener = null;

    /** Optional per-block progress hook for the capture leg — fires on
     *  the audio capture thread with the cumulative sample count and
     *  the block's max-channel RMS.  Used by the GUI's busy-shell live
     *  meter; CLI / unit-test callers pass {@code null}. */
    @Builder.Default
    private final StereoCaptureProgress captureProgress = null;
}
