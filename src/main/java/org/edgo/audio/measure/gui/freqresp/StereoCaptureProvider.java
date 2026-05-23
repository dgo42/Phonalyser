package org.edgo.audio.measure.gui.freqresp;

import org.edgo.audio.measure.cli.util.CaptureWithGenerator;
import org.edgo.audio.measure.cli.util.StereoCaptureProgress;
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
 * device.  Tests only need to implement the SAM
 * {@link #capture(SignalGenerator, DeviceRef, DeviceRef, int, int, int, int, BooleanSupplier)};
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
     * @param cancelToken polled during the capture wait; non-null tokens
     *                    allow the call to return early
     */
    StereoSamples capture(SignalGenerator gen, DeviceRef outDevice, DeviceRef inDevice,
                          int sampleRate, int bitDepth, int ditherBits,
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
                                              int durationSec,
                                              BooleanSupplier cancelToken,
                                              StereoCaptureProgress progress) throws Exception {
        return capture(gen, outDevice, inDevice, sampleRate, bitDepth, ditherBits,
                       durationSec, cancelToken);
    }

    /** Returns the production capture strategy that drives real audio
     *  hardware via {@link CaptureWithGenerator#runStereo}.  Both the
     *  no-progress SAM and the {@link #captureWithProgress} variant are
     *  overridden — the latter actually forwards live block progress. */
    static StereoCaptureProvider real() {
        return new StereoCaptureProvider() {
            @Override
            public StereoSamples capture(SignalGenerator gen, DeviceRef outDevice, DeviceRef inDevice,
                                         int sampleRate, int bitDepth, int ditherBits,
                                         int durationSec,
                                         BooleanSupplier cancelToken) throws Exception {
                return CaptureWithGenerator.runStereo(gen, outDevice, inDevice,
                        sampleRate, bitDepth, ditherBits,
                        durationSec, null, 0, cancelToken, null);
            }
            @Override
            public StereoSamples captureWithProgress(SignalGenerator gen, DeviceRef outDevice, DeviceRef inDevice,
                                                     int sampleRate, int bitDepth, int ditherBits,
                                                     int durationSec,
                                                     BooleanSupplier cancelToken,
                                                     StereoCaptureProgress progress) throws Exception {
                return CaptureWithGenerator.runStereo(gen, outDevice, inDevice,
                        sampleRate, bitDepth, ditherBits,
                        durationSec, null, 0, cancelToken, progress);
            }
        };
    }
}
