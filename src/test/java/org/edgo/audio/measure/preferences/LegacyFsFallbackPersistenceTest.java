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

package org.edgo.audio.measure.preferences;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import org.edgo.audio.measure.common.Constants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the DEPRECATED shared full-scale fallback keys ({@code adcFsVoltageRms},
 * {@code dacFsVoltageRms}) to the preferences yaml round-trip: they are the
 * calibration FALLBACK for devices with no card in {@code devices.yaml}, are
 * documented in the help's Preferences chapter as kept for backwards
 * compatibility, and are scheduled for removal only in the release AFTER the
 * next one.  A cleanup once dropped them from the writer — a released user's
 * calibration silently reset to defaults; this test makes that regression loud.
 *
 * <p>Runs against the live singleton in transient mode (no disk writes); every
 * touched preference is restored afterwards.
 */
class LegacyFsFallbackPersistenceTest {

    private static final double ADC_FS_VRMS = 1.7895371493262566;
    private static final double DAC_FS_VRMS = 1.9122365402608692;
    private static final double EPS         = 1e-12;

    private double savedAdcFs;
    private double savedDacAmpl;

    @BeforeEach
    void snapshot() {
        Preferences prefs = Preferences.instance();
        prefs.setTransientMode(true);
        savedAdcFs   = prefs.getAdcFsVoltageRms();
        savedDacAmpl = prefs.getDacFsVoltageAmpl();
    }

    @AfterEach
    void restore() {
        Preferences prefs = Preferences.instance();
        prefs.setAdcFsVoltageRms(savedAdcFs);
        prefs.setDacFsVoltageAmpl(savedDacAmpl);
    }

    @Test
    void legacyFsKeysAreWrittenAndReadBack() throws Exception {
        Preferences prefs = Preferences.instance();
        prefs.setAdcFsVoltageRms(ADC_FS_VRMS);
        prefs.setDacFsVoltageAmpl(DAC_FS_VRMS * Constants.SQRT2);

        Map<?, ?> written = invokeToMap(prefs);
        assertTrue(written.containsKey("adcFsVoltageRms"), "adcFsVoltageRms must stay in the yaml");
        assertTrue(written.containsKey("dacFsVoltageRms"), "dacFsVoltageRms must stay in the yaml");
        assertEquals(ADC_FS_VRMS, ((Number) written.get("adcFsVoltageRms")).doubleValue(), EPS);
        // The DAC value is persisted as RMS (the on-disk convention), amplitude in memory.
        assertEquals(DAC_FS_VRMS, ((Number) written.get("dacFsVoltageRms")).doubleValue(), EPS);

        // A pre-card preferences.yaml carrying only the two legacy keys must land
        // in the fallback scalars on load.
        prefs.setAdcFsVoltageRms(1.0);
        prefs.setDacFsVoltageAmpl(1.0);
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("adcFsVoltageRms", ADC_FS_VRMS);
        legacy.put("dacFsVoltageRms", DAC_FS_VRMS);
        invokeFromMap(prefs, legacy);
        assertEquals(ADC_FS_VRMS, prefs.getAdcFsVoltageRms(), EPS);
        assertEquals(DAC_FS_VRMS * Constants.SQRT2, prefs.getDacFsVoltageAmpl(), EPS);
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
