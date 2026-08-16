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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.edgo.audio.measure.common.Constants;
import org.edgo.audio.measure.dsp.DitherMath;
import org.edgo.audio.measure.gui.widgets.UnitConversion;
import org.edgo.audio.measure.gui.widgets.UnitFamily;
import org.edgo.audio.measure.gui.widgets.UnitValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the entered values: what the operator typed - number AND unit, as one
 * value - is what is stored, and the canonical getters resolve it at USE
 * against the live DAC calibration.
 *
 * <p>The defect these replace: a value entered in a calibration-dependent unit
 * was stored only in its canonical form, so a backend or device change - or a
 * restart against a different DAC calibration - silently redefined what had
 * been entered.
 *
 * <p>Runs against the live singleton in transient mode (no disk writes); every
 * preference these tests touch is restored afterwards.
 */
class EnteredUnitPairTest {

    private static final double EPS = 1e-9;
    /** A DAC peak full scale to load against, and its double, so a value that
     *  depends on the calibration provably moves with it. */
    private static final double FS_AMPL = 2.0;
    private static final double FS_AMPL_DOUBLED = 4.0;
    private static final String VOLT = UnitConversion.baseToken(UnitFamily.AMPLITUDE);
    private static final String BITS = UnitConversion.baseToken(UnitFamily.DITHER);
    private static final String DBV  = UnitConversion.logToken(UnitFamily.AMPLITUDE);
    private static final String DBFS = "dbfs";

    private double savedDacAmpl;
    private UnitValue savedGenAmplitude;
    private UnitValue savedGenDither;
    private UnitValue savedFreqRespAmplitude;
    private UnitValue savedTuneNotchAmplitude;

    @BeforeEach
    void snapshot() {
        Preferences prefs = Preferences.instance();
        prefs.setTransientMode(true);
        savedDacAmpl            = prefs.getDacFsVoltageAmpl();
        savedGenAmplitude       = prefs.getGenAmplitude();
        savedGenDither          = prefs.getGenDither();
        savedFreqRespAmplitude  = prefs.getFreqRespAmplitude();
        savedTuneNotchAmplitude = prefs.getTuneNotchAmplitude();
    }

    @AfterEach
    void restore() {
        Preferences prefs = Preferences.instance();
        prefs.setDacFsVoltageAmpl(savedDacAmpl);
        prefs.setGenAmplitude(savedGenAmplitude);
        prefs.setGenDither(savedGenDither);
        prefs.setFreqRespAmplitude(savedFreqRespAmplitude);
        prefs.setTuneNotchAmplitude(savedTuneNotchAmplitude);
    }

    @Test
    @DisplayName("an entered value survives save and load exactly as entered")
    void enteredValueRoundTrips() throws Exception {
        Preferences prefs = Preferences.instance();
        prefs.setGenAmplitude(new UnitValue(-6.0, DBFS));
        prefs.setGenDither(new UnitValue(-100.0, DBV));
        prefs.setFreqRespAmplitude(new UnitValue(-3.0, DBV));
        prefs.setTuneNotchAmplitude(new UnitValue(0.25, VOLT));

        // On disk it stays two plain keys per field - the number where it
        // always was, the unit token beside it.
        Map<?, ?> written = toMap(prefs);
        assertEquals(-6.0, ((Number) written.get("genAmplitude")).doubleValue(), EPS);
        assertEquals(DBFS, written.get("genAmplitudeUnit"));
        assertEquals(-100.0, ((Number) written.get("genDither")).doubleValue(), EPS);
        assertEquals(DBV, written.get("genDitherUnit"));
        assertFalse(written.containsKey("genAmplitudeVrms"),
                "the canonical-only key is gone - the entered value is the storage");
        assertFalse(written.containsKey("genDitherDbvDisplay"),
                "the display flag is the unit token now");

        prefs.setGenAmplitude(new UnitValue(1.0, VOLT));
        prefs.setGenDither(new UnitValue(0.0, BITS));
        fromMap(prefs, written);
        assertEquals(new UnitValue(-6.0, DBFS), prefs.getGenAmplitude());
        assertEquals(new UnitValue(-100.0, DBV), prefs.getGenDither());
        assertEquals(new UnitValue(-3.0, DBV), prefs.getFreqRespAmplitude());
        assertEquals(new UnitValue(0.25, VOLT), prefs.getTuneNotchAmplitude());
    }

    @Test
    @DisplayName("the entered figures are unmoved by a full-scale change, and the getters re-solve")
    void enteredNumbersHoldWhileTheResolutionFollowsTheCalibration() {
        Preferences prefs = Preferences.instance();
        prefs.setDacFsVoltageAmpl(FS_AMPL);
        prefs.setGenAmplitude(new UnitValue(-6.0, DBFS));
        prefs.setGenDither(new UnitValue(-100.0, DBV));
        double vrmsBefore = prefs.getGenAmplitudeVrms();
        double bitsBefore = prefs.getGenDitherBits();
        assertEquals(FS_AMPL / Constants.SQRT2 * Math.pow(10.0, -6.0 / 20.0), vrmsBefore, EPS);
        assertEquals(DitherMath.bitsForDbv(-100.0, FS_AMPL), bitsBefore, EPS);

        prefs.setDacFsVoltageAmpl(FS_AMPL_DOUBLED);
        assertEquals(new UnitValue(-6.0, DBFS), prefs.getGenAmplitude(),
                "the entered value is untouched");
        assertEquals(new UnitValue(-100.0, DBV), prefs.getGenDither());
        assertEquals(2 * vrmsBefore, prefs.getGenAmplitudeVrms(), EPS,
                "twice the full scale is twice the volts for the same dBFS");
        assertEquals(DitherMath.bitsForDbv(-100.0, FS_AMPL_DOUBLED), prefs.getGenDitherBits(), EPS,
                "the same level is one more bit under twice the full scale");
        assertTrue(prefs.getGenDitherBits() > bitsBefore);
    }

    @Test
    @DisplayName("a unit-only edit fires once")
    void unitOnlyChangeNotifies() {
        Preferences prefs = Preferences.instance();
        prefs.setGenAmplitude(new UnitValue(1.0, VOLT));
        List<UnitValue> seen = new ArrayList<>();
        Consumer<UnitValue> probe = seen::add;
        prefs.genAmplitudeProperty().addListener(probe);
        try {
            // Same figure, another unit: 1 dBV is not 1 V.  A consumer watching
            // a number alone would never learn that the drive changed.
            prefs.setGenAmplitude(new UnitValue(1.0, DBV));
            assertEquals(1, seen.size(), "exactly one notification");
            assertEquals(new UnitValue(1.0, DBV), seen.get(0));
            assertEquals(Math.pow(10.0, 1.0 / 20.0), prefs.getGenAmplitudeVrms(), EPS);
        } finally {
            prefs.genAmplitudeProperty().removeListener(probe);
        }
    }

    @Test
    @DisplayName("a combined number-and-unit edit notifies ONCE, and never with a mixed value")
    void combinedEditIsOneAtomicNotification() {
        // Regression guard: while the number and the unit were two properties,
        // an edit that moved both was two writes - and the first notification
        // carried the NEW unit against the OLD number.  A dither going from Off
        // to -100 dBV momentarily resolved as a real depth of about one bit,
        // which reached the running lane.  One value, one write, one event.
        Preferences prefs = Preferences.instance();
        prefs.setDacFsVoltageAmpl(FS_AMPL);
        prefs.setGenDither(new UnitValue(0.0, BITS));
        List<UnitValue> seen = new ArrayList<>();
        List<Double> resolved = new ArrayList<>();
        Consumer<UnitValue> probe = v -> {
            seen.add(v);
            // Read the way every consumer does - through the converting getter.
            resolved.add(prefs.getGenDitherBits());
        };
        prefs.genDitherProperty().addListener(probe);
        try {
            prefs.setGenDither(new UnitValue(-100.0, DBV));
            assertEquals(1, seen.size(), "one edit, one notification");
            assertEquals(new UnitValue(-100.0, DBV), seen.get(0));
            assertEquals(DitherMath.bitsForDbv(-100.0, FS_AMPL), resolved.get(0), EPS,
                    "the value seen by a consumer is the one that was entered, whole");
        } finally {
            prefs.genDitherProperty().removeListener(probe);
        }
    }

    @Test
    @DisplayName("a pre-pair configuration converts once, per field")
    void prePairConfigurationSeedsTheEnteredValues() throws Exception {
        Preferences prefs = Preferences.instance();
        prefs.setDacFsVoltageAmpl(FS_AMPL);
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("genAmplitudeVrms", 0.5);
        legacy.put("genAmplitudeDbvDisplay", true);
        legacy.put("genDitherBits", 12.0);
        legacy.put("genDitherDbvDisplay", true);
        legacy.put("freqRespAmplitudeVrms", 1.5);
        legacy.put("freqRespAmplitudeDbvDisplay", false);
        legacy.put("tuneNotchAmplitudeVrms", 0.75);
        fromMap(prefs, legacy);

        // No device is selected in a transient test run, so the conversion full
        // scale is unity - which is the store's own fallback.
        assertEquals(new UnitValue(20 * Math.log10(0.5), DBV), prefs.getGenAmplitude());
        assertEquals(0.5, prefs.getGenAmplitudeVrms(), EPS, "the canonical value is unchanged");
        assertEquals(new UnitValue(DitherMath.dbvForBits(12.0, 1.0), DBV), prefs.getGenDither());
        assertEquals(new UnitValue(1.5, VOLT), prefs.getFreqRespAmplitude(),
                "no flag set - volts as entered");
        assertEquals(new UnitValue(0.75, VOLT), prefs.getTuneNotchAmplitude(),
                "that field never had a flag");
    }

    @Test
    @DisplayName("a dither of Off converts to the base unit whatever the old flag said")
    void prePairOffDitherStaysInBits() throws Exception {
        Preferences prefs = Preferences.instance();
        Map<String, Object> legacy = new LinkedHashMap<>();
        legacy.put("genDitherBits", 0.0);
        legacy.put("genDitherDbvDisplay", true);
        fromMap(prefs, legacy);
        assertEquals(new UnitValue(0.0, BITS), prefs.getGenDither());
        assertEquals(0.0, prefs.getGenDitherBits(), EPS);
    }

    @Test
    @DisplayName("a stored entered value wins over the pre-pair keys of the same field")
    void storedValueBeatsTheLegacyKeys() throws Exception {
        Preferences prefs = Preferences.instance();
        Map<String, Object> mixed = new LinkedHashMap<>();
        mixed.put("genAmplitude", -12.0);
        mixed.put("genAmplitudeUnit", DBV);
        mixed.put("genAmplitudeVrms", 0.5);            // stale, from an older release
        mixed.put("genAmplitudeDbvDisplay", false);
        fromMap(prefs, mixed);
        assertEquals(new UnitValue(-12.0, DBV), prefs.getGenAmplitude());
    }

    @Test
    @DisplayName("a programmatic canonical write stores the base unit")
    void canonicalSetterWritesTheBaseUnit() {
        Preferences prefs = Preferences.instance();
        prefs.setGenAmplitude(new UnitValue(-6.0, DBFS));
        prefs.setGenAmplitudeVrms(0.25);
        assertEquals(new UnitValue(0.25, VOLT), prefs.getGenAmplitude());
        assertEquals(0.25, prefs.getGenAmplitudeVrms(), EPS);

        prefs.setGenDither(new UnitValue(-100.0, DBV));
        prefs.setGenDitherBits(8.0);
        assertEquals(new UnitValue(8.0, BITS), prefs.getGenDither());
        assertEquals(8.0, prefs.getGenDitherBits(), EPS);
    }

    @Test
    @DisplayName("the freqresp floor is expressed in the unit that is stored")
    void freqRespFloorKeepsTheStoredUnit() {
        Preferences prefs = Preferences.instance();
        prefs.setDacFsVoltageAmpl(FS_AMPL);
        prefs.setFreqRespAmplitude(new UnitValue(-60.0, DBV));
        UnitValue floor = prefs.freqRespAmplitudeIn(1e-4);
        assertEquals(DBV, floor.unit(), "clamping must not move the operator back to volts");
        assertEquals(20 * Math.log10(1e-4), floor.value(), EPS);
    }

    private Map<?, ?> toMap(Preferences prefs) throws Exception {
        Method m = Preferences.class.getDeclaredMethod("toMap");
        m.setAccessible(true);
        return (Map<?, ?>) m.invoke(prefs);
    }

    private void fromMap(Preferences prefs, Map<?, ?> root) throws Exception {
        Method m = Preferences.class.getDeclaredMethod("fromMap", Map.class);
        m.setAccessible(true);
        m.invoke(prefs, root);
    }
}
