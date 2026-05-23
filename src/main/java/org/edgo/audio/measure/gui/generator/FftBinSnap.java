package org.edgo.audio.measure.gui.generator;

import lombok.experimental.UtilityClass;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.gui.preferences.Preferences;

/**
 * Pure math for "snap a frequency to the nearest FFT-bin centre" — shared
 * between {@link GeneratorController} (which applies the snap before
 * starting playback) and the FFT view (which uses the snapped value as
 * the reference frequency when computing the clock-drift readout).
 *
 * <p>Bin width = {@code sampleRate / fftLength}.  The snap only fires for
 * {@link GenSignalForm#SINE} and only when the user has enabled it in
 * preferences — every other case returns {@code raw} unchanged so the
 * call site can pass through unconditionally.
 */
@UtilityClass
public class FftBinSnap {

    /** Returns {@code raw} rounded to the nearest FFT bin centre when
     *  the user has enabled snap-to-FFT-bin with a SINE waveform.
     *  Otherwise returns {@code raw} unchanged. */
    public double snapIfEnabled(Preferences prefs, GenSignalForm form,
                                int sampleRate, double raw) {
        if (form != GenSignalForm.SINE) return raw;
        if (!prefs.isGenSnapToFftBin()) return raw;
        int fftSize = prefs.getFftLength();
        if (fftSize < 8 || sampleRate <= 0) return raw;
        double binHz = (double) sampleRate / fftSize;
        if (binHz <= 0) return raw;
        return Math.round(raw / binHz) * binHz;
    }
}
