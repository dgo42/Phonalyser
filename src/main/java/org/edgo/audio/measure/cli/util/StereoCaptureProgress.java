package org.edgo.audio.measure.cli.util;

/**
 * Per-block progress callback for the stereo sweep-and-capture pipeline.
 * Producers ({@link CaptureWithGenerator#runStereo}) invoke this once per
 * captured audio block — typically every few ms — so consumers can show a
 * live level meter or progress bar while the sweep is in flight.
 *
 * <p>The callback fires on the audio capture thread; consumers that need
 * to touch UI widgets must marshal to their toolkit's UI thread (e.g.
 * {@code Display.asyncExec}).  Producers MUST tolerate a slow consumer
 * (the callback should never block the audio path); short, allocation-free
 * implementations are best practice.
 */
@FunctionalInterface
public interface StereoCaptureProgress {

    /**
     * Fires once per captured block.
     *
     * @param totalSamples cumulative sample count (per channel) at the end
     *                     of this block — combine with the sample rate to
     *                     get an elapsed-time value
     * @param rmsLin       the larger of the L and R channels' RMS amplitude
     *                     for the just-finished block, in linear scale where
     *                     {@code 1.0} is digital full-scale
     */
    void onBlock(int totalSamples, double rmsLin);
}
