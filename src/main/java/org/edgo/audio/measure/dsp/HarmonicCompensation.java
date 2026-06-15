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

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.edgo.audio.measure.fft.FftResult;

/**
 * Complex accumulator for closed-loop DAC harmonic pre-distortion — the
 * single home of the correction math shared by the CLI
 * {@code IterativeCompensateMode} and the GUI predistortion wizard.
 *
 * <p>Each measured {@link FftResult} contributes an LMS-style update of the
 * per-harmonic correction phasor {@code (re, im)}: the harmonic's amplitude
 * ratio and its phase (corrected for the measured DAC↔ADC transport delay)
 * are added in, scaled by the step μ.  Harmonics within {@code snrMargin} dB
 * of the noise floor are skipped — they would random-walk the accumulator.
 * The accumulated phasors convert to the generator's compensation API
 * ({@link #toGeneratorCorrections}) and serialise to the CLI-compatible
 * {@code applied_compensation_*.csv} ({@link #writeCsv}).
 */
public final class HarmonicCompensation {

    private final int      maxHarmonics;
    /** Per-harmonic accumulated correction phasor (real / imaginary parts)
     *  and the harmonic's measured frequency.  Index h is harmonic H(h+2). */
    private final double[] accRe;
    private final double[] accIm;
    private final double[] hFreqs;

    public HarmonicCompensation(int maxHarmonics) {
        this.maxHarmonics = maxHarmonics;
        this.accRe   = new double[maxHarmonics];
        this.accIm   = new double[maxHarmonics];
        this.hFreqs  = new double[maxHarmonics];
    }

    /** Generator-shaped snapshot: only the non-zero correction phasors, as
     *  the {@code (amplitudeRatio, harmonicNumber, initialPhase)} triple the
     *  generator's compensation API consumes. */
    public record GeneratorCorrections(double[] ampRatios, int[] harmonicNumbers, double[] phiInits) {}

    /**
     * LMS-style accumulate of the harmonics measured in {@code r}.  Mirrors
     * the CLI's per-iteration update: the fundamental phase gives the
     * transport delay {@code ωD = −(φ₁+π/2)}, each harmonic is de-delayed to
     * its DAC-emit phase, and the phasor is added in scaled by {@code step}.
     * Harmonics quieter than {@code noiseFloor + snrMarginDb} are left
     * untouched.
     */
    public void accumulate(FftResult r, double snrMarginDb, double step) {
        double phi1          = Math.atan2(r.im[r.fundamentalBin], r.re[r.fundamentalBin]);
        double omegaD        = -(phi1 + Math.PI / 2.0);
        double delayRadPerHz = omegaD / r.fundamentalHzRefined;

        int    count         = Math.min(r.harmonicCount, maxHarmonics);
        double skipBelowDbFs = r.avgNoiseFloorDbFs + snrMarginDb;
        for (int h = 0; h < count; h++) {
            int bin = r.harmonicBins[h];
            if (bin <= 0) continue;
            if (r.harmonicDbFs[h] < skipBelowDbFs) continue;   // near noise — would random-walk

            double ampRatio = r.harmonicPct[h] / 100.0;
            double phiH     = Math.atan2(r.im[bin], r.re[bin]);
            double phiInit  = phiH + r.harmonicHz[h] * delayRadPerHz;
            accRe[h] += step * ampRatio * Math.cos(phiInit);
            accIm[h] += step * ampRatio * Math.sin(phiInit);
            hFreqs[h] = r.harmonicHz[h];
        }
    }

    /** True once at least one harmonic has accumulated a non-zero correction. */
    public boolean hasCorrections() {
        for (int h = 0; h < maxHarmonics; h++) {
            if (accRe[h] != 0.0 || accIm[h] != 0.0) return true;
        }
        return false;
    }

    /** Converts the accumulated phasors to the generator's compensation
     *  triple, dropping harmonics that never rose above the noise gate. */
    public GeneratorCorrections toGeneratorCorrections(double fundamentalHz) {
        int valid = 0;
        for (int h = 0; h < maxHarmonics; h++) {
            if (accRe[h] != 0.0 || accIm[h] != 0.0) valid++;
        }
        double[] amp = new double[valid];
        int[]    num = new int[valid];
        double[] phi = new double[valid];
        int i = 0;
        for (int h = 0; h < maxHarmonics; h++) {
            double re = accRe[h], im = accIm[h];
            if (re == 0.0 && im == 0.0) continue;
            amp[i] = Math.hypot(re, im);
            phi[i] = Math.atan2(im, re);
            num[i] = (int) Math.round(hFreqs[h] / fundamentalHz);
            i++;
        }
        return new GeneratorCorrections(amp, num, phi);
    }

    /** Deep copy — lets the wizard snapshot the best-THD iteration's
     *  accumulator while the loop keeps updating the live one. */
    public HarmonicCompensation copy() {
        HarmonicCompensation c = new HarmonicCompensation(maxHarmonics);
        System.arraycopy(accRe,  0, c.accRe,  0, maxHarmonics);
        System.arraycopy(accIm,  0, c.accIm,  0, maxHarmonics);
        System.arraycopy(hFreqs, 0, c.hFreqs, 0, maxHarmonics);
        return c;
    }

    /**
     * Writes the {@code applied_compensation} CSV — byte-compatible with the
     * CLI iterative-compensate output (German-locale data rows: comma
     * decimals, semicolon fields) so the generator's existing
     * {@code SINE_COMPENSATED} loader reads it unchanged.  Any
     * {@code extraHeaderLines} (the GUI's {@code # format_version=} + gen /
     * FFT / THD provenance) are written first; they are {@code #}-comments
     * the loader skips.
     */
    public void writeCsv(Path file, List<String> extraHeaderLines,
                         double fundamentalHz, double fundamentalDbFs,
                         int sampleRate, int bitDepth, double amplitudeVrms) throws IOException {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            if (extraHeaderLines != null) {
                for (String line : extraHeaderLines) pw.println(line);
            }
            pw.printf(Locale.US, "# sample_rate_hz=%d%n",   sampleRate);
            pw.printf(Locale.US, "# bit_depth=%d%n",        bitDepth);
            pw.printf(Locale.US, "# amplitude_vrms=%.10f%n", amplitudeVrms);
            pw.printf(Locale.US, "# frequency_hz=%.10f%n",   fundamentalHz);
            pw.println("harmonic;frequency_hz;amplitude_dbfs;amplitude_pct;phase_deg;re;im");
            pw.printf(Locale.GERMAN, "1;%.6f;%.4f;100.000000;-90.0000;%.10e;%.10e%n",
                    fundamentalHz, fundamentalDbFs, 0.0, -1.0);
            for (int h = 0; h < maxHarmonics; h++) {
                double re = accRe[h], im = accIm[h];
                double amp = Math.hypot(re, im);
                if (amp == 0.0) continue;
                double phaseDeg = Math.toDegrees(Math.atan2(im, re));
                double ampPct   = amp * 100.0;
                double ampDbFs  = fundamentalDbFs + 20.0 * Math.log10(amp);
                pw.printf(Locale.GERMAN, "%d;%.6f;%.4f;%.9f;%.4f;%.10e;%.10e%n",
                        h + 2, hFreqs[h], ampDbFs, ampPct, phaseDeg, re, im);
            }
        }
    }
}
