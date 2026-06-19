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

package org.edgo.audio.measure.dsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LowPassFilter}: passband preservation, high-frequency
 * rejection, and the inactive (cutoff ≥ Nyquist) pass-through.
 */
class LowPassFilterTest {

    private static double power(float[] x, int from, int len, double freqHz, int fs) {
        double omega = 2.0 * Math.PI * freqHz / fs;
        double coeff = 2.0 * Math.cos(omega);
        double s1 = 0.0, s2 = 0.0;
        for (int i = from; i < from + len; i++) {
            double s = x[i] + coeff * s1 - s2;
            s2 = s1;
            s1 = s;
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2;
    }

    private static float[] tone(int n, double hz, int fs) {
        float[] s = new float[n];
        for (int i = 0; i < n; i++) s[i] = (float) Math.sin(2 * Math.PI * hz * i / fs);
        return s;
    }

    @Test
    void rejectsHfPassesAudio() {
        int fs = 384_000;
        LowPassFilter f = new LowPassFilter(fs, 80_000, 4);
        assertTrue(f.isActive());

        // 1 kHz passband tone: essentially untouched.
        float[] lo = tone(fs / 4, 1000, fs);
        float[] loF = lo.clone();
        f.reset();
        f.process(loF, loF.length);
        double passDb = 10 * Math.log10(power(loF, fs / 8, fs / 8, 1000, fs)
                / power(lo, fs / 8, fs / 8, 1000, fs));
        assertTrue(Math.abs(passDb) < 1.0, "1 kHz altered by " + passDb + " dB");

        // 150 kHz spike (well above the 80 kHz corner): strongly attenuated.
        float[] hi = tone(fs / 4, 150_000, fs);
        float[] hiF = hi.clone();
        f.reset();
        f.process(hiF, hiF.length);
        double rejDb = 10 * Math.log10(power(hi, fs / 8, fs / 8, 150_000, fs)
                / power(hiF, fs / 8, fs / 8, 150_000, fs));
        assertTrue(rejDb > 24.0, "150 kHz only attenuated " + rejDb + " dB");
    }

    @Test
    void inactiveAboveNyquist() {
        // At 48 kHz, an 80 kHz cutoff is above Nyquist → pass-through.
        LowPassFilter f = new LowPassFilter(48_000, 80_000, 4);
        assertFalse(f.isActive());
        float[] s = tone(4800, 1000, 48_000);
        float[] copy = s.clone();
        f.process(copy, copy.length);
        for (int i = 0; i < s.length; i++) assertEquals(s[i], copy[i], 0.0f);
    }
}
