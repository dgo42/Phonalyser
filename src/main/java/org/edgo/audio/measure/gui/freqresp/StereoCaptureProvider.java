package org.edgo.audio.measure.gui.freqresp;

import org.edgo.audio.measure.cli.util.CaptureWithGenerator;
import org.edgo.audio.measure.cli.util.StereoSamples;
import org.edgo.audio.measure.generator.SignalGenerator;
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
 * device.
 */
@FunctionalInterface
public interface StereoCaptureProvider {

    /**
     * Plays {@code gen}'s output on {@code outDevice} and records both
     * channels of {@code inDevice} for {@code durationSec} seconds,
     * returning the captured samples normalised to {@code [-1, +1]}.
     *
     * @param cancelToken polled during the capture wait; non-null tokens
     *                    allow the call to return early
     */
    StereoSamples capture(SignalGenerator gen, DeviceRef outDevice, DeviceRef inDevice,
                          int sampleRate, int bitDepth, int ditherBits,
                          int durationSec,
                          BooleanSupplier cancelToken) throws Exception;

    /** Returns the production capture strategy that drives real audio
     *  hardware via {@link CaptureWithGenerator#runStereo}. */
    static StereoCaptureProvider real() {
        return (gen, out, in, sr, bd, dither, dur, cancel) ->
                CaptureWithGenerator.runStereo(gen, out, in, sr, bd, dither, dur, null, 0, cancel);
    }
}
