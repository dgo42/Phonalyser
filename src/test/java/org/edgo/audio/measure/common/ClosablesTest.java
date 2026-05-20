package org.edgo.audio.measure.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link Closeables}: the close-quietly helper that replaces
 * the inline {@code try { x.close(); } catch (Throwable ignored) {}}
 * pattern on cleanup paths.
 */
class ClosablesTest {

    @Test
    void closeQuietly_callsClose_onWellBehavedResource() {
        int[] closeCount = { 0 };
        AutoCloseable c = () -> closeCount[0]++;

        Closeables.closeQuietly(c);

        assertEquals(1, closeCount[0]);
    }

    @Test
    void closeQuietly_swallowsExceptions() {
        AutoCloseable throwing = () -> {
            throw new RuntimeException("intentional");
        };

        // No assertion needed past "must not throw" — closeQuietly is
        // expected to swallow.  An exception propagating out would fail
        // the test by way of the JUnit runner.
        Closeables.closeQuietly(throwing);
    }

    @Test
    void closeQuietly_acceptsNull() {
        // Null is the common "never opened" case; must be a no-op.
        Closeables.closeQuietly(null);
    }

    @Test
    void closeQuietly_callerStateUnaffectedByCloseFailure() {
        // Even after a close failure on the first resource, the caller
        // should be able to continue and close subsequent resources.
        AutoCloseable bad  = () -> { throw new RuntimeException("bad"); };
        int[] goodCloses = { 0 };
        AutoCloseable good = () -> goodCloses[0]++;

        Closeables.closeQuietly(bad);
        Closeables.closeQuietly(good);

        assertEquals(1, goodCloses[0],
                "close of subsequent resource must not be skipped by an earlier failure");
        assertTrue(true);
    }
}
