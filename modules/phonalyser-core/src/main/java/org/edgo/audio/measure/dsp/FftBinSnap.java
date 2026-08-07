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

import lombok.experimental.UtilityClass;

/**
 * Pure math for "snap a frequency to the nearest FFT-bin centre" - shared by the
 * generator controller (which applies the snap before starting playback), the FFT
 * view (which uses the snapped value as the reference frequency when computing
 * the clock-drift readout), and the net server's remote generator, whose
 * {@code gen.fftGrid} promises the client "the same math as the client-side snap,
 * so both compute identical values".
 *
 * <p>Bin width = {@code sampleRate / fftLength}.  The snap fires for
 * {@link GenSignalForm#SINE}, {@link GenSignalForm#SINE_COMP} and
 * {@link GenSignalForm#DUAL_TONE} when it is enabled; every other waveform
 * returns {@code raw} unchanged so the call site can pass through
 * unconditionally.  Dual-tone callers snap each tone independently - pass each
 * tone's raw frequency through this helper in turn.
 *
 * <p>The grid is always given explicitly: the desktop callers unpack it from
 * their preferences, while a headless server is TOLD the grid by the client
 * that owns the analyzer - a second copy of the rounding is exactly the drift
 * the {@code gen.fftGrid} promise forbids.
 */
@UtilityClass
public class FftBinSnap {

    /** Shortest FFT the snap accepts.  Below it the bin grid is coarser than the
     *  frequency entry itself, and a "snapped" value would move the tone
     *  somewhere the operator never asked for. */
    private static final int MIN_FFT_SIZE = 8;

    /** Returns {@code raw} rounded to the nearest FFT bin centre when
     *  {@code genSnapToFftBin} is set with a SINE, SINE_COMP or DUAL_TONE
     *  waveform.  Otherwise returns {@code raw} unchanged. */
    public double snapIfEnabled(GenSignalForm form, int sampleRate, int fftSize,
                                boolean genSnapToFftBin, double raw) {
        if (form != GenSignalForm.SINE && form != GenSignalForm.DUAL_TONE
                && form != GenSignalForm.SINE_COMP) return raw;
        if (!genSnapToFftBin) return raw;
        if (fftSize < MIN_FFT_SIZE || sampleRate <= 0) return raw;
        double binHz = (double) sampleRate / fftSize;
        if (binHz <= 0) return raw;
        return Math.round(raw / binHz) * binHz;
    }
}
