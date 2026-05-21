package org.edgo.audio.measure.gui.freqresp;

/**
 * Callback invoked by {@link FreqRespAnalyzer} as it progresses through a
 * sweep + deconvolution.  Implementations are typically the worker thread's
 * progress-bar updater; pass {@code null} when no progress reporting is
 * needed.
 *
 * <p>Fired from whatever thread runs the analyzer (a background daemon for
 * the GUI worker, the main thread for the CLI).  Implementations that touch
 * SWT widgets are responsible for marshalling to the UI thread.
 */
@FunctionalInterface
public interface ProgressCallback {

    /**
     * @param fraction current progress in {@code [0.0, 1.0]}; not strictly
     *                 monotonic but always inside the range
     * @param message  human-readable description of the current step (e.g.
     *                 "Running sweep", "Computing transfer function")
     */
    void onProgress(double fraction, String message);
}
