package org.edgo.audio.measure.gui.freqresp;

import lombok.experimental.UtilityClass;
import org.edgo.audio.measure.gui.fft.FftFormat;

import java.util.Locale;

/**
 * Pure-math formatters for the Frequency Response chart labels and
 * crosshair readout.  Magnitude is always in dB and phase always in
 * degrees, so this class is simpler than {@link FftFormat} — but we still
 * delegate to {@code FftFormat} for shared frequency / SI-prefix helpers so
 * both panes render labels identically.
 */
@UtilityClass
public class FreqRespFormat {

    /** Frequency label suitable for axis ticks (compact). */
    public String formatFrequency(double f) {
        return FftFormat.formatFrequency(f);
    }

    /** Fine-grained frequency formatter for the crosshair readout — four
     *  decimal places so sub-Hz refinements stay visible. */
    public String formatFrequencyFine(double f) {
        return FftFormat.formatFrequencyFine(f);
    }

    /** Integer-only frequency formatter used on linear axis labels. */
    public String formatFrequencyInteger(double f) {
        return FftFormat.formatFrequencyInteger(f);
    }

    /** Magnitude label in dB.  Drops the "+" prefix on positive values to
     *  match the FFT axis convention.  Two decimals so 1 dB rounding
     *  artefacts don't smear the readout. */
    public String formatDb(double db) {
        if (!Double.isFinite(db)) return "—";
        return String.format(Locale.ROOT, "%.2f dB", db);
    }

    /** Magnitude label without the dB suffix — used per-tick. */
    public String formatDbBare(double db) {
        if (!Double.isFinite(db)) return "—";
        return String.format(Locale.ROOT, "%.1f", db);
    }

    /** Phase label in degrees, wrapped to {@code [-180, +180]}. */
    public String formatPhase(double deg) {
        if (!Double.isFinite(deg)) return "—";
        return String.format(Locale.ROOT, "%.1f°", deg);
    }

    /** Linear magnitude → dB.  Returns {@code -300} for non-positive
     *  values so the renderer can clamp cleanly without NaN propagation. */
    public double linToDb(double linear) {
        return (linear > 0.0) ? 20.0 * Math.log10(linear) : -300.0;
    }

    /** Converts a frequency value to its fractional position {@code [0, 1]}
     *  inside a log-scaled axis spanning {@code [freqMin, freqMax]}.  Uses
     *  log10 internally so the math stays well-conditioned for the wide
     *  ranges typical here (~1 Hz to ~100 kHz).  Returns 0 if {@code f}
     *  hits the left edge, 1 at the right. */
    public double freqToXFraction(double f, double freqMin, double freqMax) {
        if (f <= 0 || freqMin <= 0 || freqMax <= 0 || freqMax <= freqMin) return 0.0;
        return (Math.log10(f) - Math.log10(freqMin))
                / (Math.log10(freqMax) - Math.log10(freqMin));
    }

    /** Inverse of {@link #freqToXFraction} — maps a fractional X position
     *  back to a frequency. */
    public double xFractionToFreq(double frac, double freqMin, double freqMax) {
        if (freqMin <= 0 || freqMax <= 0 || freqMax <= freqMin) return freqMin;
        double logMin = Math.log10(freqMin);
        double logMax = Math.log10(freqMax);
        return Math.pow(10, logMin + frac * (logMax - logMin));
    }

    /** Fractional Y position {@code [0, 1]} of {@code db} inside the
     *  linear-dB range {@code [magBot, magTop]}, where 0 = top and 1 = bot. */
    public double dbToYFraction(double db, double magTop, double magBot) {
        if (magTop <= magBot) return 0.0;
        return (magTop - db) / (magTop - magBot);
    }

    /** Fractional Y position {@code [0, 1]} of {@code phaseDeg} inside the
     *  fixed ±180° phase axis.  0 = +180°, 1 = −180°. */
    public double phaseToYFraction(double phaseDeg) {
        double clamped = Math.max(-180.0, Math.min(180.0, phaseDeg));
        return (180.0 - clamped) / 360.0;
    }
}
