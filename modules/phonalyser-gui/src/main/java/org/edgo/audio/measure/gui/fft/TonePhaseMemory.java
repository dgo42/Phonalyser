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

package org.edgo.audio.measure.gui.fft;

import java.util.function.IntToDoubleFunction;

import org.edgo.audio.measure.fft.MathUtil;

/**
 * The phase one channel's frame carried at each of two tones' bins on the
 * previous analysis cycle, remembered so that the next cycle's reading gives
 * each tone's frequency from the phase step over the advance between the two
 * windows ({@link MathUtil#kappaFromPhaseStep}) - the reading a frequency
 * lock is steered with, exact for a clean tone under any window where a
 * one-frame fit of the peak's neighbours is not.
 *
 * <p>The pair is formed at the REMEMBERED bin: the phase this frame carries
 * there against the phase the previous frame carried there.  A bin that
 * moved between the two cycles costs one more reading of a bin, never the
 * frequency.  A cycle with nothing remembered - the first after a window
 * rebuild - reads nothing, and a window rebuilt from a fresh span has no
 * previous frame to step from, so it is forgotten with the window.
 */
final class TonePhaseMemory {

    private int    bin1   = -1;
    private int    bin2   = -1;
    private double phase1 = Double.NaN;
    private double phase2 = Double.NaN;
    /** The absolute capture position of the window the phases were read on. */
    private long   absStart;
    /** The last {@link #read}'s frequencies, Hz; NaN where it paired nothing. */
    private double hz1 = Double.NaN;
    private double hz2 = Double.NaN;

    /**
     * Reads this cycle's phases, pairs them with the remembered ones and
     * remembers them for the next cycle.
     *
     * @param k1       the first tone's bin on this frame, 0 or less for none
     * @param k2       the second tone's bin on this frame, 0 or less for none
     * @param absStart the absolute capture position of this cycle's window
     * @param fftSize  the frame length in samples
     * @param binHz    the frequency resolution, Hz per bin
     * @param phaseAt  the phase this cycle's frame carries at a bin, radians
     */
    void read(int k1, int k2, long absStart, int fftSize, double binHz, IntToDoubleFunction phaseAt) {
        double p1 = k1 > 0 ? phaseAt.applyAsDouble(k1) : Double.NaN;
        double p2 = k2 > 0 ? phaseAt.applyAsDouble(k2) : Double.NaN;
        long advance = absStart - this.absStart;
        hz1 = Double.NaN;
        hz2 = Double.NaN;
        if (advance > 0 && bin1 > 0 && !Double.isNaN(phase1)) {
            double atPrevious = bin1 == k1 ? p1 : phaseAt.applyAsDouble(bin1);
            hz1 = MathUtil.kappaFromPhaseStep(phase1, atPrevious, bin1, advance, fftSize) * binHz;
        }
        if (advance > 0 && k2 > 0 && bin2 > 0 && !Double.isNaN(phase2)) {
            double atPrevious = bin2 == k2 ? p2 : phaseAt.applyAsDouble(bin2);
            hz2 = MathUtil.kappaFromPhaseStep(phase2, atPrevious, bin2, advance, fftSize) * binHz;
        }
        this.absStart = absStart;
        bin1   = k1;
        phase1 = p1;
        bin2   = k2;
        phase2 = p2;
    }

    /** Drops the previous cycle's phases and the last readings: a cycle
     *  that reads nothing leaves nothing for the next one to pair with. */
    void forget() {
        phase1 = Double.NaN;
        phase2 = Double.NaN;
        hz1    = Double.NaN;
        hz2    = Double.NaN;
    }

    /** The first tone's frequency from the last {@link #read}, Hz, or NaN
     *  when that read had no previous phase to pair with. */
    double frequencyHz1() {
        return hz1;
    }

    /** The second tone's frequency from the last {@link #read}, Hz, or NaN
     *  under the same conditions and when there is no second tone. */
    double frequencyHz2() {
        return hz2;
    }
}
