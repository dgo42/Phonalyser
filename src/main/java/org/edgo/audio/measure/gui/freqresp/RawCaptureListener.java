package org.edgo.audio.measure.gui.freqresp;

import org.edgo.audio.measure.cli.util.StereoSamples;

/**
 * Optional hook fired by {@link FreqRespAnalyzer} after the stereo sweep
 * capture finishes and before deconvolution starts.  Lets a caller
 * side-effect on the unprocessed samples — the CLI's {@code --sweep-wav}
 * flag uses this to persist a WAV of the raw recording without dragging
 * file-path concerns into the analyzer's signature.
 *
 * <p>Fires on whatever thread runs the analyzer.  Exceptions thrown from the
 * listener propagate out of {@link FreqRespAnalyzer#run} and abort the
 * measurement before deconvolution.
 */
@FunctionalInterface
public interface RawCaptureListener {

    /**
     * @param samples both channels, normalised to {@code [-1, +1]};
     *                do not mutate
     */
    void onRawCapture(StereoSamples samples) throws Exception;
}
