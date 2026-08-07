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

package org.edgo.audio.measure.preferences;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.edgo.audio.measure.sound.qa40x.Qa40xPreferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The component-owned preference block contract: edits reach the live value
 * only on commit, and a saved block survives a round trip.  Pure state - no
 * files, no SWT, no hardware.
 */
class SubPreferencesTest {

    @Test
    void editIsPendingUntilCommit() {
        Qa40xPreferences p = new Qa40xPreferences();
        assertFalse(p.isI2sEnabled(), "default is off");

        p.beginEdit();
        p.setI2sEnabledEdit(true);
        assertFalse(p.isI2sEnabled(), "the settings dialog must not touch the live value");

        p.commitEdit();
        assertTrue(p.isI2sEnabled(), "the Preferences dialog's OK commits it");
    }

    @Test
    void beginEditDropsAnAbandonedEdit() {
        // The user toggles it, then cancels the Preferences dialog: reopening
        // must show the live value again, not the abandoned edit.
        Qa40xPreferences p = new Qa40xPreferences();
        p.beginEdit();
        p.setI2sEnabledEdit(true);
        // ... Cancel: no commit ...
        p.beginEdit();
        assertFalse(p.isI2sEnabledEdit(), "a cancelled edit must not survive into the next session");
        p.commitEdit();
        assertFalse(p.isI2sEnabled());
    }

    @Test
    void roundTripsThroughAMap() {
        Qa40xPreferences saved = new Qa40xPreferences();
        saved.beginEdit();
        saved.setI2sEnabledEdit(true);
        saved.commitEdit();
        Map<String, Object> block = saved.toMap();

        Qa40xPreferences loaded = new Qa40xPreferences();
        loaded.fromMap(block);
        assertTrue(loaded.isI2sEnabled(), "the live value comes back off the file");
        assertTrue(loaded.isI2sEnabledEdit(), "and the edit value starts from it");
        assertEquals("qa40x", loaded.key(), "the per-backend prefix in preferences.yaml");
    }

    @Test
    void unknownOrAbsentEntriesKeepTheCurrentValue() {
        // A file from an older or newer release must not throw or reset values.
        Qa40xPreferences p = new Qa40xPreferences();
        p.beginEdit();
        p.setI2sEnabledEdit(true);
        p.commitEdit();

        p.fromMap(new LinkedHashMap<>());
        assertTrue(p.isI2sEnabled(), "an empty block leaves the value alone");

        Map<String, Object> alien = new LinkedHashMap<>();
        alien.put("somethingElse", 42);
        p.fromMap(alien);
        assertTrue(p.isI2sEnabled(), "an unrecognised entry leaves the value alone");
    }
}
