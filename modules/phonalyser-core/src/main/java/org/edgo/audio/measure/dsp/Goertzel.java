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

import lombok.experimental.UtilityClass;

/**
 * The single-bin DFT by Goertzel's recurrence - ONE evaluation of the
 * transform at ONE frequency, for the places where a full transform of that
 * block does not exist or the frequency does not sit on its grid.
 *
 * <p>A block that HAS been transformed is read from its spectrum instead: at
 * a bin of that transform the two are the same number, and the recurrence
 * would only pay O(N) a second time for what the spectrum already holds.
 *
 * <p>The recurrence's terminal carries the constant factor e^{-jω}; it is
 * undone here, so the value handed back is the DFT bin itself and every
 * caller works from one definition.  A caller that needs power or magnitude
 * takes them from the components, which cost nothing next to the O(N) sweep.
 */
@UtilityClass
public final class Goertzel {

    /** One frequency's complex DFT value: the same units the transform's own
     *  real and imaginary parts carry. */
    public record SingleBin(double re, double im) {}

    /**
     * The DFT of {@code samples[offset .. offset+fftSize)} at the angular
     * frequency {@code omega} radians per sample - {@code 2π·k/N} for bin
     * {@code k} of an {@code N}-point transform, or {@code 2π·f/fs} for an
     * arbitrary frequency, which is what the recurrence is for.
     *
     * @param window the analysis window applied as the sweep runs, or
     *               {@code null} for none - a block the caller has already
     *               windowed, or one measured unwindowed
     * @param mean   subtracted from every sample as the sweep runs, so a block
     *               carrying DC is measured without a copy of its own; 0 when
     *               the block is already centred
     */
    public static SingleBin goertzelBin(double[] samples, int offset, int fftSize, double[] window,
                                        double omega, double mean) {
        if (offset < 0 || fftSize < 0 || (long) offset + fftSize > samples.length) {
            throw new IllegalArgumentException("Block at " + offset + " exceeds the buffer: "
                    + fftSize + " samples needed, " + samples.length + " held");
        }
        final double cw    = Math.cos(omega);
        final double sw    = Math.sin(omega);
        final double coeff = 2.0 * cw;
        double s1 = 0.0;
        double s2 = 0.0;
        if (window != null) {
            for (int n = 0; n < fftSize; n++) {
                double x = (samples[n + offset] - mean) * window[n];
                double s = x + coeff * s1 - s2;
                s2 = s1;
                s1 = s;
            }
        } else {
            for (int n = 0; n < fftSize; n++) {
                double s = (samples[n + offset] - mean) + coeff * s1 - s2;
                s2 = s1;
                s1 = s;
            }
        }
        // Terminal s1 - s2·e^{-jω}, times e^{jω} - the factor the recurrence
        // leaves undone - gives the transform's own value at that frequency.
        double re = s1 - s2 * cw;
        double im = s2 * sw;
        return new SingleBin(re * cw - im * sw, re * sw + im * cw);
    }

    /**
     * The DFT of {@code samples[offset .. offset+fftSize)} at the angular
     * frequency {@code omega} radians per sample - {@code 2π·k/N} for bin
     * {@code k} of an {@code N}-point transform, or {@code 2π·f/fs} for an
     * arbitrary frequency, which is what the recurrence is for.
     *
     * @param window the analysis window applied as the sweep runs, or
     *               {@code null} for none - a block the caller has already
     *               windowed, or one measured unwindowed
     * @param mean   subtracted from every sample as the sweep runs, so a block
     *               carrying DC is measured without a copy of its own; 0 when
     *               the block is already centred
     */
    public static SingleBin goertzelBin(float[] samples, int offset, int fftSize, double[] window,
                                        double omega, double mean) {
        if (offset < 0 || fftSize < 0 || (long) offset + fftSize > samples.length) {
            throw new IllegalArgumentException("Block at " + offset + " exceeds the buffer: "
                    + fftSize + " samples needed, " + samples.length + " held");
        }
        final double cw    = Math.cos(omega);
        final double sw    = Math.sin(omega);
        final double coeff = 2.0 * cw;
        double s1 = 0.0;
        double s2 = 0.0;
        if (window != null) {
            for (int n = 0; n < fftSize; n++) {
                double x = (samples[n + offset] - mean) * window[n];
                double s = x + coeff * s1 - s2;
                s2 = s1;
                s1 = s;
            }
        } else {
            for (int n = 0; n < fftSize; n++) {
                double s = (samples[n + offset] - mean) + coeff * s1 - s2;
                s2 = s1;
                s1 = s;
            }
        }
        // Terminal s1 - s2·e^{-jω}, times e^{jω} - the factor the recurrence
        // leaves undone - gives the transform's own value at that frequency.
        double re = s1 - s2 * cw;
        double im = s2 * sw;
        return new SingleBin(re * cw - im * sw, re * sw + im * cw);
    }
}
