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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.edgo.audio.measure.fft.FftResult;

/**
 * Complex accumulator for closed-loop DAC <em>intermodulation</em>
 * pre-distortion — the two-tone sibling of {@link HarmonicCompensation},
 * shared by the GUI predistortion wizard's dual-tone path.
 *
 * <p>Every distortion product of a two-tone signal — each tone's harmonics
 * <em>and</em> their intermodulation products alike — sits at
 * {@code a·f₁ + b·f₂} for integer {@code (a, b)} and has instantaneous phase
 * {@code a·θ₁ + b·θ₂} from the generator's two DDS accumulators, so a single
 * {@code (a, b)}-indexed correction set cancels them all.  This class holds a
 * fixed grid of such products (per-tone harmonics + the low-order intermod
 * comb) and accumulates an LMS-style correction phasor per product:
 *
 * <ul>
 *   <li>the product's <b>phase-stable</b> averaged value comes straight from
 *       the FFT's {@code re}/{@code im} — the analyzer already de-rotates each
 *       product bin by {@code a·Φ(F1)+b·Φ(F2)}
 *       ({@link FftResult#imdProductA}), so the products enjoy the same
 *       trustworthy phase the per-tone harmonics get from the single-reference
 *       de-rotation;</li>
 *   <li>the transport delay {@code ωD = −(φ₁+π/2)} is read from F1 and applied
 *       per product by its frequency — identical to the single-tone path.</li>
 * </ul>
 *
 * Products within {@code snrMargin} dB of the noise floor are skipped (they
 * would random-walk the accumulator).  The result converts to the generator's
 * dual-tone compensation API ({@link #toGeneratorCorrections}) and serialises
 * to a dual-tone {@code applied_compensation} CSV ({@link #writeCsv}).
 */
public final class IntermodCompensation {

    /** Highest |a|+|b| the FFT analyzer is guaranteed to de-rotate (its grid
     *  order is {@code max(5, harmonicCount+1)}), so every b≠0 product we list
     *  stays at or below it and is therefore one the analyzer published. */
    private static final int INTERMOD_ORDER_CAP = 5;

    /** Per-product θ₁ / θ₂ integer multipliers (the grid), the accumulated
     *  correction phasor, and the product's last-measured frequency. */
    private final int[]    coefA;
    private final int[]    coefB;
    private final double[] accRe;
    private final double[] accIm;
    private final double[] freqHz;

    public IntermodCompensation(int maxHarmonics) {
        List<int[]> grid = buildGrid(maxHarmonics);
        int n = grid.size();
        this.coefA  = new int[n];
        this.coefB  = new int[n];
        for (int i = 0; i < n; i++) {
            coefA[i] = grid.get(i)[0];
            coefB[i] = grid.get(i)[1];
        }
        this.accRe  = new double[n];
        this.accIm  = new double[n];
        this.freqHz = new double[n];
    }

    /** Builds the fixed correction grid: F1 harmonics {@code (h,0)} and F2
     *  harmonics {@code (0,h)} up to the harmonic cap, plus the low-order
     *  intermodulation comb.  Order kept ≤ {@link #INTERMOD_ORDER_CAP} for the
     *  b≠0 products so each is one the analyzer de-rotated. */
    private List<int[]> buildGrid(int maxHarmonics) {
        List<int[]> g = new ArrayList<>();
        int hMax = Math.max(2, maxHarmonics);
        for (int h = 2; h <= hMax; h++)                      g.add(new int[]{ h, 0 });   // F1 harmonics
        for (int h = 2; h <= Math.min(hMax, INTERMOD_ORDER_CAP); h++) g.add(new int[]{ 0, h });   // F2 harmonics
        // Intermod comb (|a|+|b| ≤ INTERMOD_ORDER_CAP): 2nd, 3rd and 5th order.
        int[][] intermod = {
            { 1,  1}, {-1,  1},                 // f1+f2, f2−f1            (2nd order)
            { 2, -1}, {-1,  2}, { 2, 1}, {1, 2},// 2f1−f2, 2f2−f1, 2f1+f2, f1+2f2 (3rd)
            { 3, -2}, {-2,  3},                 // 3f1−2f2, 3f2−2f1        (5th order)
        };
        for (int[] ab : intermod) g.add(ab);
        return g;
    }

    /** Generator-shaped snapshot: only the non-zero correction phasors, as the
     *  {@code (amplitudeRatio, aCoef, bCoef, initialPhase)} quad the dual-tone
     *  compensation API consumes. */
    public record GeneratorCorrections(double[] ampRatios, int[] coefA, int[] coefB, double[] phiInits) {}

    /**
     * LMS-style accumulate of the two-tone distortion products measured in
     * {@code r}.  The transport delay is taken from F1 ({@code fundamentalBin}),
     * each grid product's phase-stable phasor is read from {@code re}/{@code im}
     * at its bin (F1 harmonics from {@link FftResult#harmonicBins}, every b≠0
     * product from the de-rotated {@link FftResult#imdProductBin} grid), and the
     * de-delayed phasor is added in scaled by {@code step}.  Every detected
     * product is corrected — there is no noise-floor gate.
     */
    public void accumulate(FftResult r, double f1Hz, double f2Hz, double step) {
        if (r.re == null || r.im == null || r.amplitudeDbFs == null) return;
        int fundBin = r.fundamentalBin;
        if (fundBin <= 0 || fundBin >= r.re.length) return;
        double refMag = Math.hypot(r.re[fundBin], r.im[fundBin]);
        if (refMag <= 0.0 || !(f1Hz > 0.0)) return;

        double phi1          = Math.atan2(r.im[fundBin], r.re[fundBin]);
        double omegaD        = -(phi1 + Math.PI / 2.0);
        double delayRadPerHz = omegaD / f1Hz;

        for (int g = 0; g < coefA.length; g++) {
            int a = coefA[g], b = coefB[g];
            int bin = findBin(r, a, b);
            if (bin <= 0 || bin >= r.re.length) continue;

            double fp = a * f1Hz + b * f2Hz;
            if (!(fp > 0.0)) continue;
            double mag      = Math.hypot(r.re[bin], r.im[bin]);
            double ampRatio = mag / refMag;
            double phiP     = Math.atan2(r.im[bin], r.re[bin]);
            double phiInit  = phiP + fp * delayRadPerHz;
            accRe[g]  += step * ampRatio * Math.cos(phiInit);
            accIm[g]  += step * ampRatio * Math.sin(phiInit);
            freqHz[g]  = fp;
        }
    }

    /** Resolves a grid product to its FFT bin: F1 harmonics {@code (h,0)} from
     *  the single-reference harmonic table, every b≠0 product from the
     *  per-product de-rotated grid.  Returns {@code -1} when the analyzer
     *  didn't de-rotate this product this tick (so its phase isn't trustworthy). */
    private int findBin(FftResult r, int a, int b) {
        if (b == 0) {
            int idx = a - 2;                          // harmonicBins[0] = 2nd harmonic
            if (r.harmonicBins != null && idx >= 0 && idx < r.harmonicCount) {
                return r.harmonicBins[idx];
            }
            return -1;
        }
        int[] pa = r.imdProductA, pb = r.imdProductB, pbin = r.imdProductBin;
        if (pa == null) return -1;
        for (int i = 0; i < pa.length; i++) {
            if (pa[i] == a && pb[i] == b) return pbin[i];
        }
        return -1;
    }

    /** True once at least one product has accumulated a non-zero correction. */
    public boolean hasCorrections() {
        for (int g = 0; g < accRe.length; g++) {
            if (accRe[g] != 0.0 || accIm[g] != 0.0) return true;
        }
        return false;
    }

    /** Converts the accumulated phasors to the generator's dual-tone
     *  compensation quad, dropping products that never rose above the noise gate. */
    public GeneratorCorrections toGeneratorCorrections() {
        int valid = 0;
        for (int g = 0; g < accRe.length; g++) {
            if (accRe[g] != 0.0 || accIm[g] != 0.0) valid++;
        }
        double[] amp = new double[valid];
        int[]    a   = new int[valid];
        int[]    b   = new int[valid];
        double[] phi = new double[valid];
        int i = 0;
        for (int g = 0; g < accRe.length; g++) {
            double re = accRe[g], im = accIm[g];
            if (re == 0.0 && im == 0.0) continue;
            amp[i] = Math.hypot(re, im);
            phi[i] = Math.atan2(im, re);
            a[i]   = coefA[g];
            b[i]   = coefB[g];
            i++;
        }
        return new GeneratorCorrections(amp, a, b, phi);
    }

    /** Deep copy — lets the wizard snapshot the best-THD round's accumulator
     *  while the loop keeps updating the live one. */
    public IntermodCompensation copy() {
        return new IntermodCompensation(coefA, coefB, accRe, accIm, freqHz);
    }

    /** Full-array constructor used only by {@link #copy()}. */
    private IntermodCompensation(int[] coefA, int[] coefB,
                                 double[] accRe, double[] accIm, double[] freqHz) {
        this.coefA  = coefA.clone();
        this.coefB  = coefB.clone();
        this.accRe  = accRe.clone();
        this.accIm  = accIm.clone();
        this.freqHz = freqHz.clone();
    }

    /**
     * Writes a dual-tone {@code applied_compensation} CSV.  Mirrors the
     * single-tone format ({@link HarmonicCompensation#writeCsv}) but the
     * per-row key is the {@code (a, b)} coefficient pair instead of a single
     * harmonic number — German-locale data rows (comma decimals, semicolon
     * fields).  Any {@code extraHeaderLines} are written first as
     * {@code #}-comments.
     */
    public void writeCsv(Path file, List<String> extraHeaderLines,
                         double f1Hz, double f2Hz, double fundamentalDbFs,
                         int sampleRate, int bitDepth, double amplitudeVrms) throws IOException {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            if (extraHeaderLines != null) {
                for (String line : extraHeaderLines) pw.println(line);
            }
            pw.printf(Locale.US, "# sample_rate_hz=%d%n",    sampleRate);
            pw.printf(Locale.US, "# bit_depth=%d%n",         bitDepth);
            pw.printf(Locale.US, "# amplitude_vrms=%.10f%n",  amplitudeVrms);
            pw.printf(Locale.US, "# frequency1_hz=%.10f%n",   f1Hz);
            pw.printf(Locale.US, "# frequency2_hz=%.10f%n",   f2Hz);
            pw.println("a;b;frequency_hz;amplitude_dbfs;amplitude_pct;phase_deg;re;im");
            for (int g = 0; g < accRe.length; g++) {
                double re = accRe[g], im = accIm[g];
                double amp = Math.hypot(re, im);
                if (amp == 0.0) continue;
                double phaseDeg = Math.toDegrees(Math.atan2(im, re));
                double ampPct   = amp * 100.0;
                double ampDbFs  = fundamentalDbFs + 20.0 * Math.log10(amp);
                pw.printf(Locale.GERMAN, "%d;%d;%.6f;%.4f;%.9f;%.4f;%.10e;%.10e%n",
                        coefA[g], coefB[g], freqHz[g], ampDbFs, ampPct, phaseDeg, re, im);
            }
        }
    }
}
