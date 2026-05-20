package org.edgo.audio.measure.gui.preferences;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * Tests for {@link BackendPrefs}: default values, the {@link
 * BackendPrefs#copyFrom} sync, and {@link BackendPrefs#snapshot}.  These
 * methods support the preferences-dialog undo flow — cancel restores
 * the snapshot, OK commits.  A regression here silently corrupts the
 * undo path.
 */
class BackendPrefsTest {

    @Test
    void defaults_matchHistoricalValues() {
        BackendPrefs p = new BackendPrefs();
        assertEquals(null,    p.getInputDeviceName());
        assertEquals(null,    p.getOutputDeviceName());
        assertEquals(384_000, p.getInputSampleRate());
        assertEquals(24,      p.getInputBitDepth());
        assertEquals(384_000, p.getOutputSampleRate());
        assertEquals(24,      p.getOutputBitDepth());
    }

    @Test
    void copyFrom_copiesEveryField() {
        BackendPrefs src = new BackendPrefs();
        src.setInputDeviceName("Mic A");
        src.setOutputDeviceName("Speakers B");
        src.setInputSampleRate(96_000);
        src.setInputBitDepth(16);
        src.setOutputSampleRate(48_000);
        src.setOutputBitDepth(32);

        BackendPrefs dst = new BackendPrefs();
        dst.copyFrom(src);

        assertEquals("Mic A",      dst.getInputDeviceName());
        assertEquals("Speakers B", dst.getOutputDeviceName());
        assertEquals(96_000,       dst.getInputSampleRate());
        assertEquals(16,           dst.getInputBitDepth());
        assertEquals(48_000,       dst.getOutputSampleRate());
        assertEquals(32,           dst.getOutputBitDepth());
    }

    @Test
    void snapshot_returnsIndependentCopy() {
        BackendPrefs orig = new BackendPrefs();
        orig.setInputDeviceName("Mic");
        orig.setInputSampleRate(192_000);

        BackendPrefs snap = orig.snapshot();
        assertNotSame(orig, snap, "snapshot must be a new instance");
        assertEquals("Mic",  snap.getInputDeviceName());
        assertEquals(192_000, snap.getInputSampleRate());

        // Mutating the original must not affect the snapshot.
        orig.setInputDeviceName("Other");
        orig.setInputSampleRate(44_100);
        assertEquals("Mic",   snap.getInputDeviceName());
        assertEquals(192_000, snap.getInputSampleRate());
    }

    @Test
    void copyFrom_afterSnapshot_restoresOriginalState() {
        // Models the "user changes settings, clicks Cancel" flow.
        BackendPrefs working = new BackendPrefs();
        working.setInputDeviceName("Original");
        working.setInputSampleRate(48_000);

        BackendPrefs snap = working.snapshot();

        // User edits something …
        working.setInputDeviceName("Edited");
        working.setInputSampleRate(192_000);

        // … and cancels.
        working.copyFrom(snap);

        assertEquals("Original", working.getInputDeviceName());
        assertEquals(48_000,     working.getInputSampleRate());
    }
}
