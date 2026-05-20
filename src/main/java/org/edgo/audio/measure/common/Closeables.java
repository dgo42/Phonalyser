package org.edgo.audio.measure.common;

import lombok.extern.log4j.Log4j2;

/**
 * Generic best-effort {@link AutoCloseable#close} helper.  Use on
 * clean-up paths (error handlers, dispose listeners, shutdown hooks)
 * where the close failure can be safely swallowed but should still be
 * visible in the debug log for post-mortem analysis.
 *
 * <p>Replaces the inline {@code try { x.close(); } catch (Throwable t) {}}
 * pattern with a one-line call, and ensures the close failure isn't
 * silently invisible — it lands at {@code debug} level on the calling
 * class so a developer chasing a leak can see it.
 */
@Log4j2
public final class Closeables {

    private Closeables() {}

    /** Closes {@code c} if non-null; logs (debug) on failure and returns. */
    public static void closeQuietly(AutoCloseable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable t) {
            log.debug("close() failed on {}: {}", c.getClass().getSimpleName(), t.toString());
        }
    }
}
