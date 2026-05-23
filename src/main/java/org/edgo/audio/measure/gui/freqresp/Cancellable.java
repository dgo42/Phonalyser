package org.edgo.audio.measure.gui.freqresp;

/**
 * Cooperative cancellation token consulted by {@link FreqRespAnalyzer} at
 * each measurement step.  When {@link #isCancelled()} returns {@code true},
 * the analyzer throws {@link InterruptedException} from its next checkpoint
 * so callers can abort an in-flight sweep without waiting for the full
 * duration.  Pass {@code null} when cancellation isn't needed.
 */
@FunctionalInterface
public interface Cancellable {

    /** @return {@code true} when the caller wants the analyzer to stop. */
    boolean isCancelled();
}
