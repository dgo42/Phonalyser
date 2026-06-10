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

package org.edgo.audio.measure.gui.generator;

import lombok.experimental.UtilityClass;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.preferences.Preferences;

/**
 * Pure math for "snap a frequency to the nearest FFT-bin centre" — shared
 * between {@link GeneratorController} (which applies the snap before
 * starting playback) and the FFT view (which uses the snapped value as
 * the reference frequency when computing the clock-drift readout).
 *
 * <p>Bin width = {@code sampleRate / fftLength}.  The snap fires for
 * {@link GenSignalForm#SINE} and {@link GenSignalForm#DUAL_TONE} when
 * the user has enabled it in preferences; every other waveform returns
 * {@code raw} unchanged so the call site can pass through
 * unconditionally.  Dual-tone callers snap each tone independently —
 * pass each tone's raw frequency through this helper in turn.
 */
@UtilityClass
public class FftBinSnap {

    /** Returns {@code raw} rounded to the nearest FFT bin centre when
     *  the user has enabled snap-to-FFT-bin with a SINE or DUAL_TONE
     *  waveform.  Otherwise returns {@code raw} unchanged. */
    public double snapIfEnabled(Preferences prefs, GenSignalForm form,
                                int sampleRate, double raw) {
        if (form != GenSignalForm.SINE && form != GenSignalForm.DUAL_TONE) return raw;
        if (!prefs.isGenSnapToFftBin()) return raw;
        int fftSize = prefs.getFftLength();
        if (fftSize < 8 || sampleRate <= 0) return raw;
        double binHz = (double) sampleRate / fftSize;
        if (binHz <= 0) return raw;
        return Math.round(raw / binHz) * binHz;
    }
}
