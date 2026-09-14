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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the cached {@code √(bin bandwidth)} of the live FFT configuration
 * ({@link Preferences#getBinBwSqrt}) - the V -> V/√Hz divisor of the FFT view's
 * unit conversion, recomputed from the FFT length and the input sample rate
 * whichever way either of them changes.
 *
 * <p>Runs against the live singleton in transient mode (no disk writes); every
 * touched preference is restored afterwards so other tests in the same JVM see
 * the state they started with.
 */
class PreferencesConversionTest {

    private static final int    SAMPLE_RATE  = 384_000;
    private static final int    FFT_LENGTH   = 65_536;
    /** Bin bandwidth of the config above: 384000 / 65536 = 5.859375 Hz. */
    private static final double BIN_BW_HZ    = (double) SAMPLE_RATE / FFT_LENGTH;
    private static final double EPS          = 1e-12;

    private int savedFftLength;
    private int savedSampleRate;

    @BeforeEach
    void configureKnownFormat() {
        Preferences prefs = Preferences.instance();
        prefs.setTransientMode(true);
        savedFftLength  = prefs.getFftLength();
        savedSampleRate = prefs.current().getInputSampleRate();

        // POJO write first, then the property set - the fftLength listener
        // fires recomputeBinBw() and picks the new rate up with it.
        prefs.current().setInputSampleRate(SAMPLE_RATE);
        prefs.setFftLength(FFT_LENGTH == prefs.getFftLength() ? FFT_LENGTH / 2 : FFT_LENGTH);
        prefs.setFftLength(FFT_LENGTH);
    }

    @AfterEach
    void restoreFormat() {
        Preferences prefs = Preferences.instance();
        prefs.current().setInputSampleRate(savedSampleRate);
        prefs.setFftLength(savedFftLength);
    }

    @Test
    void theCachedBinBandwidthFollowsTheLiveConfiguration() {
        assertEquals(Math.sqrt(BIN_BW_HZ), Preferences.instance().getBinBwSqrt(), EPS);
    }

    @Test
    void fftLengthChange_recomputesCachedBinBandwidth() {
        Preferences prefs = Preferences.instance();
        // Bidi-bound GUI edits write the property directly - the listener
        // registered in the constructor must keep the cache in step.
        prefs.fftLengthProperty().set(FFT_LENGTH / 4);
        double binBw = (double) SAMPLE_RATE / (FFT_LENGTH / 4);
        assertEquals(Math.sqrt(binBw), prefs.getBinBwSqrt(), EPS);
    }
}
