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

import lombok.Builder;
import lombok.Getter;
import org.edgo.audio.measure.dsp.FreqRespCalHelper;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.sound.StereoCaptureProgress;
import org.edgo.audio.measure.sound.StereoSamples;
import org.edgo.audio.measure.sound.DeviceRef;

/**
 * Typed bundle of parameters for one {@link FreqRespAnalyzer} run.  Build via
 * the Lombok-generated {@code builder()}.  All numeric fields are validated by
 * the analyzer before any audio device is opened; invalid combinations throw
 * {@link IllegalArgumentException} at the start of {@code run()}.
 *
 * <p>The analyzer always captures both ADC channels in one playback and
 * deconvolves them in parallel - there is no per-channel mode any more.
 */
@Getter
@Builder
public final class FreqRespAnalyzerConfig {

    /** Output (DAC) device the sweep is played on. */
    private final DeviceRef outDevice;
    /** Input (ADC) device both channels are captured from. */
    private final DeviceRef inDevice;

    /** The ADC's configured rate in Hz: the clock the recording arrives on, and
     *  therefore the clock the deconvolution, its reference, its bin grid and
     *  the result's Nyquist are all counted in. */
    private final int captureRateHz;
    /** The DAC's configured rate in Hz - the format the output line is opened
     *  with and the clock the played chirp is rendered on.  Independent of
     *  {@link #captureRateHz}: a card whose two sides are set differently is
     *  measured on both of its clocks, not on one of them twice. */
    private final int playbackRateHz;
    /** The DAC's configured word length (8 / 16 / 24 / 32). */
    private final int playbackBitDepth;
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
    /** Silence before the chirp, in seconds: the fixed
     *  {@link FreqRespCalHelper#OFFSET_LEAD_IN_SEC} window the deconvolution
     *  measures each channel's DC offset on, plus the lead-in the operator
     *  configured on top of it.  Never less than that window. */
    private final double leadInSec;
    /** The ratio the swept band is widened by at each end of the read band
     *  ({@link FreqRespConstants#sweptBandHz}): the measurement's
     *  {@link FreqRespConstants#SWEEP_BAND_MARGIN} by default, so the read
     *  band's ends lie clear of the chirp's fades; 1.0 makes the chirp the
     *  read band itself, for a probe that needs every second of the sweep on
     *  that band.  The provider that plays the chirp has to be built with the
     *  same value - the analyzer's reference is the chirp this says. */
    @Builder.Default
    private final double sweepBandMargin = FreqRespConstants.SWEEP_BAND_MARGIN;

    /** Generator drive amplitude in V RMS at the DAC. */
    private final double amplitudeVrms;
    /** DAC full-scale RMS voltage of each lane - what that lane's rendering of
     *  the one drive amplitude divides by. */
    private final double dacFsVoltageRmsLeft;
    private final double dacFsVoltageRmsRight;
    /** ADC full-scale RMS voltage of each channel, normalising its deconvolved
     *  magnitude to a physical transfer function (1.0 = 0 dB at unity loopback). */
    private final double adcFsVoltageRmsLeft;
    private final double adcFsVoltageRmsRight;

    /** Which output lane(s) the sweep drives - the encoder gate applied to the
     *  played sweep ({@code BOTH} by default, which is the only behaviour before
     *  per-channel output existed).  {@code LEFT} / {@code RIGHT} write digital
     *  silence to the un-driven lane; both channels are still deconvolved (the
     *  user hides the un-driven trace with the view's L/R toolbar buttons). */
    @Builder.Default
    private final OutputChannels outputChannels = OutputChannels.BOTH;

    /** The channel the capture combines both inputs into, the other one folded
     *  in as its slave, or {@code null} while they are separate.  Combined, the
     *  slave's record is zeros: the master alone is deconvolved and its
     *  response stands for both channels.  The capture provider has to be
     *  built with the same value. */
    private final Channel combinedMaster;

    /** The apply-calibration preference as the sweep started.  The analyzer
     *  does not read it: the view divides the shown curves by the loaded
     *  calibrations itself ({@code FreqRespView}). */
    private final boolean applyCalibration;

    /** Stereo capture strategy.  Production callers use
     *  {@link StereoCaptureProvider#real()}; tests inject a stub that
     *  returns synthetic {@link StereoSamples}. */
    @Builder.Default
    // No default: production builders pass StereoCaptureProvider.forSweep
    // (the ONE lane-driven strategy), tests inject stubs; validate() refuses
    // a config that set neither.
    private final StereoCaptureProvider stereoCaptureProvider = null;

    /** Optional hook fired with the raw captured stereo samples between
     *  the capture and the deconvolution steps.  Used by the CLI's
     *  {@code --sweep-wav} flag to persist the unprocessed recording
     *  without making the analyzer aware of file paths.  {@code null}
     *  means no listener. */
    @Builder.Default
    private final RawCaptureListener rawCaptureListener = null;

    /** Optional per-block progress hook for the capture leg - fires on
     *  the audio capture thread with the cumulative sample count and
     *  the block's max-channel RMS.  Used by the GUI's progress-dialog live
     *  meter; CLI / unit-test callers pass {@code null}. */
    @Builder.Default
    private final StereoCaptureProgress captureProgress = null;
}
