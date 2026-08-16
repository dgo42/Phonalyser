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

package org.edgo.audio.measure.gui.preferences;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which queued device scan is still worth running, proved without a display.
 *
 * <p>The Preferences dialog scans on ONE serial thread, so an operator walking
 * down the backend combo would otherwise leave the backend they end on waiting
 * behind every backend they passed - each of those a driver enumeration plus a
 * rate x depth probe, i.e. seconds apiece.  The decision that stops it is a plain
 * comparison of generations, and that is what is pinned here; the dialog is built
 * with no shell because its constructor only stores the parent.
 */
class DeviceScanGenerationTest {

    /** Backends switched through before the operator settles on the last one. */
    private static final int SWITCHES = 5;

    private final PreferencesDialog dialog = new PreferencesDialog(null);

    @Test
    void theScanJustStartedIsTheCurrentOne() {
        assertFalse(dialog.scanSuperseded(dialog.startScanGeneration()),
                "nothing has moved on, so this scan is the one whose answer is wanted");
    }

    @Test
    void aNewerSelectionSupersedesTheScanBeforeIt() {
        int first = dialog.startScanGeneration();
        int second = dialog.startScanGeneration();

        assertTrue(dialog.scanSuperseded(first),
                "its answer would describe a backend that is no longer shown");
        assertFalse(dialog.scanSuperseded(second));
    }

    @Test
    void switchingThroughBackendsLeavesExactlyOneScanWorthRunning() {
        List<Integer> queued = new ArrayList<>();
        for (int i = 0; i < SWITCHES; i++) {
            queued.add(dialog.startScanGeneration());
        }

        List<Integer> current = new ArrayList<>();
        for (int generation : queued) {
            if (!dialog.scanSuperseded(generation)) current.add(generation);
        }

        assertEquals(1, current.size(), "five switches, one real scan");
        assertEquals(queued.get(SWITCHES - 1), current.get(0),
                "and it is the LAST one - the selection the operator is looking at");
    }

    @Test
    void aGenerationIsNeverHandedOutTwice() {
        // The check is an equality, so a counter that repeated a value would let a
        // stale scan pass itself off as the current one.
        int first = dialog.startScanGeneration();
        for (int i = 0; i < SWITCHES; i++) {
            assertTrue(dialog.startScanGeneration() > first);
        }
    }
}
