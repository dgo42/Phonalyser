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
import java.util.function.DoubleFunction;

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
     * LMS-style accumulate of the harmonics measured in {@code r}.  The
     * accumulator holds, per harmonic, a COMPLEX phasor whose magnitude is the
     * harmonic's ABSOLUTE measured level (linear, from its de-embedded-chain dBFS
     * — the actual ADC reading, NOT a ratio that bakes in the notched-|F0|
     * division) and whose phase is the RAW measured phase, window-derotated.  The
     * {@code .frc} de-embed and the division by the fundamental are NOT done here;
     * they are applied on the way out, in {@link #toGeneratorCorrections} /
     * {@link #writeCsv}, to the values that actually reach the DDS.
     *
     * <p><b>The fundamental phase is the per-round frame reference.</b>  Each
     * round's FFT window starts at an arbitrary point in the tone, so the
     * fundamental comes out at a RANDOM absolute phase that also carries whatever
     * the round's non-deterministic DAC→ADC delay was; the whole spectrum is
     * de-rotated by it (each harmonic by n·φ₁) so F0 → 0° and every round lands in
     * the same frame.  φ₁ is the measured {@code arg(X₁)} MINUS the {@code .frc}
     * phase at f₁ ({@code calFundPhaseRad}): the twin-T notch sitting on f₁
     * phase-inverts the measured fundamental (its phase there ≈ π), and that π
     * would otherwise enter every harmonic as n·π — flipping the ODD harmonics
     * (n·π ≡ π) while leaving the even ones (n·π ≡ 0), i.e. driving the loop to
     * build a triangle.  Subtracting the cal phase removes it.  This is a pure
     * PHASE subtraction (no division by the singular |H(f₁)|), so the notch null
     * doesn't blow it up; any residual is only the cal-vs-live notch drift.
     */
    public void accumulate(FftResult r, double step, double calFundPhaseRad) {
        double re1 = !Double.isNaN(r.rawFundRe) ? r.rawFundRe : r.re[r.fundamentalBin];
        double im1 = !Double.isNaN(r.rawFundIm) ? r.rawFundIm : r.im[r.fundamentalBin];
        double phi1          = Math.atan2(im1, re1) - calFundPhaseRad;      // window phase, F0 notch phase removed
        double delayRadPerHz = -(phi1 + Math.PI / 2.0) / r.fundamentalHzRefined;

        int    count         = Math.min(r.harmonicCount, maxHarmonics);
        for (int h = 0; h < count; h++) {
            int bin = r.harmonicBins[h];
            if (bin <= 0) continue;

            double magLin = Math.pow(10.0, r.rawHarmonicDbFs(h) / 20.0);    // absolute ADC level, pre-.frc
            double rawRe  = (r.rawPeakRe != null && h < r.rawPeakRe.length) ? r.rawPeakRe[h] : r.re[bin];
            double rawIm  = (r.rawPeakIm != null && h < r.rawPeakIm.length) ? r.rawPeakIm[h] : r.im[bin];
            double phiH   = Math.atan2(rawIm, rawRe);                       // RAW phase; .frc de-embed is downstream
            double phiInit = phiH + r.harmonicHz[h] * delayRadPerHz;
            accRe[h] += step * magLin * Math.cos(phiInit);
            accIm[h] += step * magLin * Math.sin(phiInit);
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

    /** Converts the accumulated ABSOLUTE (ADC-dBFS) phasors to the generator's
     *  compensation triple — applying, HERE on the values that reach the DDS
     *  (not in the accumulator): the {@code .frc} de-embed (magnitude AND phase),
     *  the ADC-dBFS → absolute-volts conversion, and the division by the DAC
     *  fundamental.  {@code calResponseAt} returns the loaded {@code .frc}
     *  response {@code [magLin, phaseRad]} at a frequency; {@code adcFsVoltageRms}
     *  is the ADC full-scale (so {@code dBFS → Vrms}); {@code dacFundamentalVrms}
     *  is the DAC's fundamental output (= {@code genAmplitudeVrms}) the ratio is
     *  taken against — the DAC's own volts↔dBFS then turns the ratio into its
     *  injected harmonic.  Harmonics that never rose above the gate are dropped. */
    public GeneratorCorrections toGeneratorCorrections(double fundamentalHz,
            DoubleFunction<double[]> calResponseAt, double adcFsVoltageRms, double dacFundamentalVrms) {
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
            double[] cal = calResponseAt.apply(hFreqs[h]);                 // [magLin, phaseRad] of H(f_h)
            double magDe = Math.hypot(re, im) / (cal[0] > 0.0 ? cal[0] : 1.0);   // ÷ chain magnitude (de-embed)
            double vH    = magDe * adcFsVoltageRms;                        // ADC dBFS → absolute Vrms at DAC out
            amp[i] = dacFundamentalVrms > 0.0 ? vH / dacFundamentalVrms : 0.0;   // ratio to the DAC fundamental
            phi[i] = Math.atan2(im, re) - cal[1];                          // − chain phase (de-embed)
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
    public void writeDpd(Path file, List<String> extraHeaderLines,
                         double fundamentalHz, double fundamentalDbFs,
                         int sampleRate, int bitDepth, double amplitudeVrms,
                         DoubleFunction<double[]> calResponseAt, double adcFsVoltageRms) throws IOException {
        // amplitudeVrms IS the DAC fundamental (genAmplitudeVrms); the ratio columns
        // (amplitude_pct + re/im, consumed by the SINE_COMPENSATED loader) are taken
        // against it.  The amplitude_dbfs column instead carries the de-embedded
        // ABSOLUTE dBV (V_h via the ADC full-scale) — the honest measured level.
        double fundDbV = amplitudeVrms > 0.0 ? 20.0 * Math.log10(amplitudeVrms) : 0.0;
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            if (extraHeaderLines != null) {
                for (String line : extraHeaderLines) pw.println(line);
            }
            pw.printf(Locale.US, "# sample_rate_hz=%d%n",   sampleRate);
            pw.printf(Locale.US, "# bit_depth=%d%n",        bitDepth);
            pw.printf(Locale.US, "# amplitude_vrms=%.10f%n", amplitudeVrms);
            pw.printf(Locale.US, "# frequency_hz=%.10f%n",   fundamentalHz);
            pw.println("harmonic;frequency_hz;amplitude_dbv;amplitude_pct;phase_deg;re;im");
            pw.printf(Locale.GERMAN, "1;%.6f;%.4f;100.000000;-90.0000;%.10e;%.10e%n",
                    fundamentalHz, fundDbV, 0.0, -1.0);
            for (int h = 0; h < maxHarmonics; h++) {
                if (accRe[h] == 0.0 && accIm[h] == 0.0) continue;
                // Same de-embed as toGeneratorCorrections: ÷ chain H(f_h), ADC dBFS
                // → Vrms, ratio to the DAC fundamental.
                double[] cal = calResponseAt.apply(hFreqs[h]);
                double magDe = Math.hypot(accRe[h], accIm[h]) / (cal[0] > 0.0 ? cal[0] : 1.0);
                double vH    = magDe * adcFsVoltageRms;                    // de-embedded harmonic, abs Vrms
                double amp   = amplitudeVrms > 0.0 ? vH / amplitudeVrms : 0.0;
                double phase = Math.atan2(accIm[h], accRe[h]) - cal[1];
                double re = amp * Math.cos(phase), im = amp * Math.sin(phase);
                double phaseDeg = Math.toDegrees(phase);
                double ampPct   = amp * 100.0;
                double ampDbV   = vH > 0.0 ? 20.0 * Math.log10(vH) : -300.0;
                pw.printf(Locale.GERMAN, "%d;%.6f;%.4f;%.9f;%.4f;%.10e;%.10e%n",
                        h + 2, hFreqs[h], ampDbV, ampPct, phaseDeg, re, im);
            }
        }
    }
}
