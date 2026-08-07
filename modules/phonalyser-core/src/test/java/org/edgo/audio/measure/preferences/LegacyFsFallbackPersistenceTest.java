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

import org.edgo.audio.measure.common.Constants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Pins the DEPRECATED shared full-scale fallback keys ({@code adcFsVoltageRms},
 * {@code dacFsVoltageRms}) to their 1.2 contract: LOAD
 * ONLY.  They are the calibration fallback for a device whose card carries none,
 * and a calibrated card pushes its own values into the very same runtime
 * scalars - so writing them back would save the last selected card's
 * calibration as the machine-wide default and hand it to the next uncalibrated
 * device as if it had been measured there.  The reader stays: a pre-1.2
 * {@code preferences.yaml} seeds the runtime fallback once, then the entry
 * disappears with the first save.
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
    void legacyFsKeysAreNeverWritten() throws Exception {
        Preferences prefs = Preferences.instance();
        prefs.setAdcFsVoltageRms(ADC_FS_VRMS);
        prefs.setDacFsVoltageAmpl(DAC_FS_VRMS * Constants.SQRT2);

        Map<?, ?> written = invokeToMap(prefs);
        assertFalse(written.containsKey("adcFsVoltageRms"),
                "adcFsVoltageRms is runtime-only since 1.2 - it must not reach the yaml");
        assertFalse(written.containsKey("dacFsVoltageRms"),
                "dacFsVoltageRms is runtime-only since 1.2 - it must not reach the yaml");
    }

    @Test
    void legacyFsKeysStillSeedTheRuntimeFallbackOnLoad() throws Exception {
        // A pre-1.2 preferences.yaml carrying the two legacy keys must land in the
        // fallback scalars on load - the one-way migration the 1.2 contract keeps.
        Preferences prefs = Preferences.instance();
        prefs.setAdcFsVoltageRms(1.0);
        prefs.setDacFsVoltageAmpl(1.0);
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("adcFsVoltageRms", ADC_FS_VRMS);
        legacy.put("dacFsVoltageRms", DAC_FS_VRMS);   // on disk RMS, in memory amplitude
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
