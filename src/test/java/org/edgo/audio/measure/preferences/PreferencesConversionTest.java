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

import org.edgo.audio.measure.enums.MagnitudeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the dBFS→display-unit conversion math in
 * {@link Preferences#convertFromDbFs} — the anchor for every absolute level
 * the FFT view shows.  An uninitialised {@code dbvOffsetDb} once shifted all
 * dBV/V readouts by ~5 dB on fresh installs; these tests would have caught it.
 *
 * <p>Runs against the live singleton in transient mode (no disk writes); every
 * touched preference is restored afterwards so other tests in the same JVM see
 * the state they started with.
 */
class PreferencesConversionTest {

    /** ADC full-scale chosen so the dBV offset is exactly 20·log10(2). */
    private static final double ADC_FS_VRMS  = 2.0;
    private static final double OFFSET_DB    = 20.0 * Math.log10(ADC_FS_VRMS);
    private static final int    SAMPLE_RATE  = 384_000;
    private static final int    FFT_LENGTH   = 65_536;
    /** Bin bandwidth of the config above: 384000 / 65536 = 5.859375 Hz. */
    private static final double BIN_BW_HZ    = (double) SAMPLE_RATE / FFT_LENGTH;
    private static final double EPS          = 1e-12;

    private double savedAdcFs;
    private int    savedFftLength;
    private int    savedSampleRate;

    @BeforeEach
    void configureKnownCalibration() {
        Preferences prefs = Preferences.instance();
        prefs.setTransientMode(true);
        savedAdcFs      = prefs.getAdcFsVoltageRms();
        savedFftLength  = prefs.getFftLength();
        savedSampleRate = prefs.current().getInputSampleRate();

        prefs.setAdcFsVoltageRms(ADC_FS_VRMS);
        // POJO write first, then the property set — the fftLength listener
        // fires recomputeBinBw() and picks the new rate up with it.
        prefs.current().setInputSampleRate(SAMPLE_RATE);
        prefs.setFftLength(FFT_LENGTH == prefs.getFftLength() ? FFT_LENGTH / 2 : FFT_LENGTH);
        prefs.setFftLength(FFT_LENGTH);
    }

    @AfterEach
    void restoreCalibration() {
        Preferences prefs = Preferences.instance();
        prefs.setAdcFsVoltageRms(savedAdcFs);
        prefs.current().setInputSampleRate(savedSampleRate);
        prefs.setFftLength(savedFftLength);
    }

    @Test
    void dbFs_isIdentity() {
        assertEquals(-20.0,
                Preferences.instance().convertFromDbFs(-20.0, MagnitudeUnit.DBFS), EPS);
    }

    @Test
    void dbv_addsCachedOffset() {
        assertEquals(-20.0 + OFFSET_DB,
                Preferences.instance().convertFromDbFs(-20.0, MagnitudeUnit.DBV), EPS);
    }

    @Test
    void volts_readTheDbvValueLinearly() {
        // −20 dBFS at FS = 2 Vrms is exactly 0.2 Vrms: 10^(−20/20) · 2.
        assertEquals(0.2,
                Preferences.instance().convertFromDbFs(-20.0, MagnitudeUnit.V), EPS);
    }

    @Test
    void voltsPerSqrtHz_divideByCachedBinBandwidth() {
        assertEquals(0.2 / Math.sqrt(BIN_BW_HZ),
                Preferences.instance().convertFromDbFs(-20.0, MagnitudeUnit.V_SQRT_HZ), EPS);
    }

    @Test
    void voltsPerSqrtHz_explicitBinBwOverridesCache() {
        // A loaded .fft file carries its own √(bin bandwidth) — the 3-arg
        // overload must use it instead of the live-config cache.
        assertEquals(0.2 / 2.0,
                Preferences.instance().convertFromDbFs(-20.0, MagnitudeUnit.V_SQRT_HZ, 2.0), EPS);
    }

    @Test
    void voltsPerSqrtHz_nullBinBwFallsBackToCache() {
        assertEquals(0.2 / Math.sqrt(BIN_BW_HZ),
                Preferences.instance().convertFromDbFs(-20.0, MagnitudeUnit.V_SQRT_HZ, null), EPS);
    }

    @Test
    void fftLengthChange_recomputesCachedBinBandwidth() {
        Preferences prefs = Preferences.instance();
        // Bidi-bound GUI edits write the property directly — the listener
        // registered in the constructor must keep the cache in step.
        prefs.fftLengthProperty().set(FFT_LENGTH / 4);
        double binBw = (double) SAMPLE_RATE / (FFT_LENGTH / 4);
        assertEquals(0.2 / Math.sqrt(binBw),
                prefs.convertFromDbFs(-20.0, MagnitudeUnit.V_SQRT_HZ), EPS);
    }

    @Test
    void invalidAdcFullScale_keepsOffsetAndValue() {
        Preferences prefs = Preferences.instance();
        double offsetBefore = prefs.getDbvOffsetDb();
        prefs.setAdcFsVoltageRms(0.0);   // rejected by the setter guard
        assertEquals(ADC_FS_VRMS, prefs.getAdcFsVoltageRms(), EPS);
        assertEquals(offsetBefore, prefs.getDbvOffsetDb(), EPS);
    }
}
