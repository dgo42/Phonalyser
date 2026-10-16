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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour tests for {@link CorrectionStore}: added entries are kept in
 * order with their path and With-noise flag, and clearing empties the store.
 */
class FreqRespCorrectionStoreTest {

    private CorrectionStore store;

    @BeforeEach
    void setup() {
        store = new CorrectionStore("test");
    }

    @Test
    void addedEntriesAreKeptInOrder() {
        StereoFreqRespCalibration first  = sampleStereo();
        StereoFreqRespCalibration second = sampleStereo();
        store.addEntry(first, "/tmp/a.frc", false);
        store.addEntry(second, "/tmp/b.frc", true);

        List<CorrectionStore.Entry> entries = store.getEntries();
        assertEquals(2, entries.size());
        assertSame(first, entries.get(0).getCalibration());
        assertEquals("/tmp/a.frc", entries.get(0).getPath());
        assertFalse(entries.get(0).isWithNoise());
        assertSame(second, entries.get(1).getCalibration());
        assertEquals("/tmp/b.frc", entries.get(1).getPath());
        assertTrue(entries.get(1).isWithNoise());
    }

    @Test
    void clearAllEmptiesTheStore() {
        store.addEntry(sampleStereo(), "/tmp/a.frc", false);

        store.clearAll();
        assertTrue(store.getEntries().isEmpty());

        // A second clear is a no-op.
        store.clearAll();
        assertTrue(store.getEntries().isEmpty());
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
