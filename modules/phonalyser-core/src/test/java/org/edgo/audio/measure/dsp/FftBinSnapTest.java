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

package org.edgo.audio.measure.dsp;

import org.edgo.audio.measure.enums.GenSignalForm;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The snap-to-bin rounding, against bin centres worked out by hand.
 *
 * <p>It matters more than a rounding rule usually would: the net protocol
 * promises (spec 4.5, {@code gen.fftGrid}) that the server "snaps the emitted
 * frequency ... same math as the client-side snap, so both compute identical
 * values".  Two sides that disagree by one ULP put the client's frequency-lock
 * loop half a bin from where it thinks it is, and the loop then spends its life
 * correcting a difference nobody can see.  One implementation, tested against
 * arithmetic rather than against itself.
 */
class FftBinSnapTest {

    private static final int RATE_48K = 48_000;
    private static final int FFT_4096 = 4_096;
    /** 48 000 / 4 096 - the bin width every expectation below is built from. */
    private static final double BIN_HZ = 11.71875;
    private static final double TONE_HZ = 997.0;
    /** 997 / 11.718 75 = 85.077..., so bin 85. */
    private static final double SNAPPED_HZ = 85 * BIN_HZ;
    /** Exactly half a bin above bin 85 - where the rounding has to pick a side. */
    private static final double MIDPOINT_HZ = 85.5 * BIN_HZ;
    private static final double SNAPPED_MIDPOINT_HZ = 86 * BIN_HZ;
    /** Shorter than the helper's floor, where a "snapped" value would move the
     *  tone somewhere the operator never asked for. */
    private static final int TOO_SHORT_FFT = 4;
    private static final double EXACT = 0.0;

    @Test
    void aSineIsRoundedToTheNearestBinCentre() {
        assertEquals(SNAPPED_HZ, FftBinSnap.snapIfEnabled(GenSignalForm.SINE, RATE_48K,
                FFT_4096, true, TONE_HZ), EXACT);
    }

    @Test
    void aBinCentreSnapsToItself() {
        assertEquals(SNAPPED_HZ, FftBinSnap.snapIfEnabled(GenSignalForm.SINE, RATE_48K,
                FFT_4096, true, SNAPPED_HZ), EXACT,
                "an already-snapped tone must not creep bin by bin as the client "
                        + "re-applies the snap on every FFT-length change");
    }

    @Test
    void theMidpointRoundsUp() {
        assertEquals(SNAPPED_MIDPOINT_HZ, FftBinSnap.snapIfEnabled(GenSignalForm.SINE,
                RATE_48K, FFT_4096, true, MIDPOINT_HZ), EXACT,
                "half-up, so the server and the client land on the same bin for the "
                        + "one input where the two could differ");
    }

    @Test
    void aCompensatedSineAndATwoToneSnapLikeAPlainSine() {
        assertEquals(SNAPPED_HZ, FftBinSnap.snapIfEnabled(GenSignalForm.SINE_COMP,
                RATE_48K, FFT_4096, true, TONE_HZ), EXACT);
        assertEquals(SNAPPED_HZ, FftBinSnap.snapIfEnabled(GenSignalForm.DUAL_TONE,
                RATE_48K, FFT_4096, true, TONE_HZ), EXACT,
                "each tone of a two-tone signal is snapped through this helper in "
                        + "turn, so both land on a bin centre");
    }

    @Test
    void everyOtherWaveformPassesThroughUntouched() {
        assertEquals(TONE_HZ, FftBinSnap.snapIfEnabled(GenSignalForm.RECTANGLE, RATE_48K,
                FFT_4096, true, TONE_HZ), EXACT,
                "a rectangle can only place its edge ON a sample, so it is driven at "
                        + "an integer-sample period instead - the call site passes "
                        + "through this helper unconditionally and relies on it");
        assertEquals(TONE_HZ, FftBinSnap.snapIfEnabled(GenSignalForm.LOG_SWEEP, RATE_48K,
                FFT_4096, true, TONE_HZ), EXACT);
        assertEquals(TONE_HZ, FftBinSnap.snapIfEnabled(GenSignalForm.WHITE_NOISE, RATE_48K,
                FFT_4096, true, TONE_HZ), EXACT);
    }

    @Test
    void aDisabledSnapChangesNothing() {
        assertEquals(TONE_HZ, FftBinSnap.snapIfEnabled(GenSignalForm.SINE, RATE_48K,
                FFT_4096, false, TONE_HZ), EXACT);
    }

    @Test
    void anImpossibleGridChangesNothing() {
        assertEquals(TONE_HZ, FftBinSnap.snapIfEnabled(GenSignalForm.SINE, RATE_48K,
                TOO_SHORT_FFT, true, TONE_HZ), EXACT,
                "below the floor the bin grid is coarser than the frequency entry "
                        + "itself");
        assertEquals(TONE_HZ, FftBinSnap.snapIfEnabled(GenSignalForm.SINE, 0, FFT_4096,
                true, TONE_HZ), EXACT,
                "and a rate of zero is a server that has not been told one yet, not "
                        + "a licence to divide by it");
    }
}
