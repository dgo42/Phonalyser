/*
 * Phonalyser - precision audio measurement workbench.
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

import java.util.List;

import org.edgo.audio.measure.dsp.FreqRespCalibration;
import org.edgo.audio.measure.dsp.StereoFreqRespCalibration;
import org.edgo.audio.measure.gui.common.CorrectionStore;
import org.edgo.audio.measure.gui.common.CorrectionStore.Change;
import org.edgo.audio.measure.gui.common.CorrectionStore.Entry;
import org.edgo.audio.measure.gui.common.CorrectionStore.Kind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour tests for {@link CorrectionStore}: every update of one row's
 * entry is reported as exactly the change it is - added, removed, replaced
 * (another calibration or path), changed (flags only) - the entries keep the
 * rows' order, and only the active ones are divided by.
 */
class FreqRespCorrectionStoreTest {

    private static final String PATH_A = "/tmp/a.frc";
    private static final String PATH_B = "/tmp/b.frc";

    private CorrectionStore store;

    @BeforeEach
    void setup() {
        store = new CorrectionStore("test");
    }

    @Test
    void addedEntriesKeepTheRowsOrder() {
        StereoFreqRespCalibration first  = sampleStereo();
        StereoFreqRespCalibration second = sampleStereo();
        Entry b = new Entry(second, PATH_B, true, true);
        Entry a = new Entry(first, PATH_A, false, true);
        store.update(0, null, b);
        Change added = store.update(0, null, a);

        assertEquals(Kind.ADDED, added.kind());
        assertNull(added.before());
        assertSame(a, added.after());
        List<Entry> entries = store.getEntries();
        assertEquals(2, entries.size());
        assertSame(first, entries.get(0).getCalibration(), "inserted at its row's place");
        assertFalse(entries.get(0).isWithNoise());
        assertSame(second, entries.get(1).getCalibration());
        assertTrue(entries.get(1).isWithNoise());
    }

    @Test
    void onlyActiveEntriesAreDividedBy() {
        Entry parked = new Entry(sampleStereo(), PATH_A, false, false);
        store.update(0, null, parked);
        assertTrue(store.getEntries().isEmpty(), "a parked row is held but not divided by");

        Entry ticked = new Entry(parked.getCalibration(), PATH_A, false, true);
        Change change = store.update(0, parked, ticked);

        assertEquals(Kind.CHANGED, change.kind(), "an Active tick is a change, not an add");
        assertSame(parked, change.before());
        assertSame(ticked, change.after());
        assertEquals(List.of(ticked), store.getEntries());
    }

    @Test
    void aWithNoiseFlipIsAChange() {
        StereoFreqRespCalibration cal = sampleStereo();
        Entry toneOnly = new Entry(cal, PATH_A, false, true);
        store.update(0, null, toneOnly);

        Change change = store.update(0, toneOnly, new Entry(cal, PATH_A, true, true));

        assertEquals(Kind.CHANGED, change.kind());
    }

    @Test
    void anotherCalibrationOrPathIsAReplacement() {
        Entry a = new Entry(sampleStereo(), PATH_A, false, true);
        store.update(0, null, a);
        Entry reread = new Entry(sampleStereo(), PATH_A, false, true);
        assertEquals(Kind.REPLACED, store.update(0, a, reread).kind(), "the file read again");

        Entry otherFile = new Entry(sampleStereo(), PATH_B, false, true);
        assertEquals(Kind.REPLACED, store.update(0, reread, otherFile).kind(), "another file");
        assertEquals(List.of(otherFile), store.getEntries());
    }

    @Test
    void aRemovedEntryLeavesTheStore() {
        Entry a = new Entry(sampleStereo(), PATH_A, false, true);
        store.update(0, null, a);

        Change change = store.update(0, a, null);

        assertEquals(Kind.REMOVED, change.kind());
        assertSame(a, change.before());
        assertNull(change.after());
        assertTrue(store.getEntries().isEmpty());
    }

    @Test
    void anUnchangedEntryIsNoChange() {
        StereoFreqRespCalibration cal = sampleStereo();
        Entry a = new Entry(cal, PATH_A, false, true);
        store.update(0, null, a);

        assertNull(store.update(0, a, new Entry(cal, PATH_A, false, true)));
        assertNull(store.update(0, null, null), "a row without a file has nothing to report");
    }

    private StereoFreqRespCalibration sampleStereo() {
        double[] f = { 20, 200, 2000, 20000 };
        double[] m = { 1.0, 1.0, 1.0, 1.0 };
        double[] p = { 0.0, 0.0, 0.0, 0.0 };
        FreqRespCalibration left  = new FreqRespCalibration(f, m, p);
        FreqRespCalibration right = new FreqRespCalibration(f, m, p);
        return new StereoFreqRespCalibration(left, right);
    }
}
