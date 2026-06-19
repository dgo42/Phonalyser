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

    /**
     * Runs {@code action}; logs (debug) on failure and returns.  Use for
     * non-AutoCloseable cleanup paths — native handle releases, {@code stop()}
     * / {@code stopRecording()} on a line, JNA {@code Pa_CloseStream}, etc.
     */
    public static void tryQuietly(String label, ThrowingRunnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            log.debug("{} failed: {}", label, t.toString());
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Throwable;
    }
}
