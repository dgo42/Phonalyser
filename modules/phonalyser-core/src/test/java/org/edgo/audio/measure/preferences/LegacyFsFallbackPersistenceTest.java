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

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A {@code preferences.yaml} that still carries the machine-wide full-scale
 * keys of the pre-card releases ({@code adcFsVoltageRms}, {@code dacFsVoltageRms})
 * loads without complaint and is written back WITHOUT them: a full scale is a
 * property of a device card now, never of the installation, and a stray key
 * must neither fail the load nor be carried forward as if it still meant
 * something.
 *
 * <p>Runs against the live singleton in transient mode (no disk writes).
 */
class LegacyFsFallbackPersistenceTest {

    private static final double ADC_FS_VRMS = 1.7895371493262566;
    private static final double DAC_FS_VRMS = 1.9122365402608692;

    @Test
    void theMachineWideFullScaleKeysAreNeitherReadNorWrittenBack() throws Exception {
        Preferences prefs = Preferences.instance();
        prefs.setTransientMode(true);
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("adcFsVoltageRms", ADC_FS_VRMS);
        legacy.put("dacFsVoltageRms", DAC_FS_VRMS);

        invokeFromMap(prefs, legacy);

        Map<?, ?> written = invokeToMap(prefs);
        assertFalse(written.containsKey("adcFsVoltageRms"),
                "a full scale lives in a device card - the installation has none");
        assertFalse(written.containsKey("dacFsVoltageRms"),
                "a full scale lives in a device card - the installation has none");
    }

    private Map<?, ?> invokeToMap(Preferences prefs) throws Exception {
        Method m = Preferences.class.getDeclaredMethod("toMap");
        m.setAccessible(true);
        return (Map<?, ?>) m.invoke(prefs);
    }

    private void invokeFromMap(Preferences prefs, Map<String, Object> root) throws Exception {
        Method m = Preferences.class.getDeclaredMethod("fromMap", Map.class);
        m.setAccessible(true);
        m.invoke(prefs, root);
    }
}
