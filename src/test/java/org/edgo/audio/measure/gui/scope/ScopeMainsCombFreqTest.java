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

package org.edgo.audio.measure.gui.scope;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.edgo.audio.measure.dsp.MainsCombFilter;
import org.junit.jupiter.api.Test;

/**
 * Field regression: a weak tone buried under stronger mains, measured with the
 * IIR mains comb ON.  Reproduces the report where the scope read ~1003.33 Hz
 * (comb-biased) for a 1003.601 Hz tone the FFT measured at 1003.60.
 *
 * <p>Strategy under test: the comb suppresses the dominant mains so the tone
 * becomes the spectral peak (a good SEED), but its 999.7 Hz notch (50·20) sits
 * ~4 Hz from the tone and, at high sample rates, the comb never settles inside
 * the window — both pull the combed frequency low.  The worker therefore
 * re-pins the seed on the RAW signal in a narrow band: mains energy lives in
 * the low harmonics (tens of Hz away), so the band isolates the tone, un-biased.
 */
class ScopeMainsCombFreqTest {

    private static final double TONE_HZ = 1003.601;
    private static final double MAINS_HZ = 49.986;     // a real mains lock value

    /** Weak tone + stronger mains.  Mains energy is in the low harmonics (as
     *  real hum is), so total mains dominates the tone yet nothing competes
     *  with the tone in its own band. */
    private static float[] toneUnderMains(double fs, int n, double toneAmp, double mainsAmp) {
        float[] d = new float[n];
        for (int i = 0; i < n; i++) {
            double t = i / fs;
            double v = toneAmp * Math.sin(2 * Math.PI * TONE_HZ * t);
            for (int k = 1; k <= 9; k++) {
                v += (mainsAmp / k) * Math.sin(2 * Math.PI * MAINS_HZ * k * t + 0.3 * k);
            }
            d[i] = (float) v;
        }
        return d;
    }

    /** The load-bearing fix: given the comb's (downward-biased) seed, the raw
     *  narrow-band refine returns the true tone frequency at every rate the
     *  scope uses — not the comb-biased value, and not the stronger mains. */
    @Test
    void rawRefineRecoversToneFromBiasedSeed() {
        for (double fs : new double[]{96_000, 192_000, 384_000}) {
            int n = 96_000;
            float[] raw = toneUnderMains(fs, n, 1e-4, 4e-4);     // mains 4× the tone
            double seed = TONE_HZ - 0.3;                          // simulate comb pull
            double f = SignalMeasurements.refineFrequencyAround(raw, n, fs, seed, 2.0);
            assertTrue(Math.abs(f - TONE_HZ) < 0.01,
                    String.format("fs=%.0f: got %.5f, err %+.5f", fs, f, f - TONE_HZ));
        }
    }

    /** Sanity: measuring the RAW signal directly (the naive "drop the comb"
     *  fix) locks onto the stronger mains, NOT the tone — which is exactly why
     *  the comb seed is needed to locate the tone first. */
    @Test
    void rawDirectLocksOntoMains() {
        double fs = 384_000;
        int n = 96_000;
        float[] raw = toneUnderMains(fs, n, 1e-4, 4e-4);
        double fRaw = SignalMeasurements.from(raw, n, fs, 1.0, true).getFrequency();
        assertTrue(Math.abs(fRaw - TONE_HZ) > 1.0,
                "expected raw-direct to lock onto mains, not the tone; got " + fRaw);
    }

    /** End-to-end through the worker's comb path at 96 kHz, where the comb
     *  settles within the window so it cleanly locates the tone: levels off the
     *  combed tail, frequency re-pinned on the raw signal. */
    @Test
    void endToEndCombSeedThenRawRefine() {
        double fs = 96_000;
        int n = 96_000;
        float[] raw = toneUnderMains(fs, n, 1e-4, 4e-4);
        float[] combed = raw.clone();
        MainsCombFilter c = new MainsCombFilter((int) fs, 2.0);
        c.track(combed, n);
        c.reset();
        c.processPreservingDc(combed, n, 0L);
        int settle = (int) (3.0 * fs / (Math.PI * 2.0));
        int from = Math.min(settle, n / 2);
        int measLen = n - from;
        float[] tail = new float[measLen];
        System.arraycopy(combed, from, tail, 0, measLen);
        double seed = SignalMeasurements.from(tail, measLen, fs, 1.0, true).getFrequency();
        double f = SignalMeasurements.refineFrequencyAround(raw, n, fs, seed, 2.0);
        assertTrue(Math.abs(f - TONE_HZ) < 0.01,
                String.format("seed=%.4f -> %.5f, err %+.5f", seed, f, f - TONE_HZ));
    }
}
