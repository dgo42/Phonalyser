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
import java.util.function.DoubleFunction;

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
     * {@code r} — the dual-tone twin of {@link HarmonicCompensation#accumulate},
     * sharing its design: the accumulator holds each product's ABSOLUTE measured
     * level (dBFS-linear, off the conv-scaled phasors — the actual ADC reading,
     * not a notched-fundamental ratio) as a complex phasor, window-derotated; the
     * {@code .frc} de-embed, the dBV conversion and the division by the DAC
     * fundamental are applied on the way OUT ({@link #toGeneratorCorrections} /
     * {@link #writeDpd}).  Every detected product is corrected — no noise gate.
     *
     * <p><b>Two fundamentals frame the products.</b>  Each product {@code a·f₁+b·f₂}
     * is de-rotated by {@code a·(φ₁+π/2) + b·(φ₂+π/2)} so it lands in the same
     * per-round frame, where {@code φ₁ = arg(X_F1) − argH(f₁)} and
     * {@code φ₂ = arg(X_F2) − argH(f₂)} are the measured fundamental phases with
     * the twin-T notch phase removed (a pure phase subtraction; without it the
     * notch's ≈π at each tone would flip products and build the dual-tone
     * triangle — see HarmonicCompensation).  F2 is the {@code (0,1)} grid product.
     * The b=0 F1 harmonics read the display-de-embedded {@code re}/{@code im}
     * phase + {@link FftResult#rawHarmonicDbFs} magnitude (and take unit response
     * downstream); every b≠0 product reads the raw conv-scaled phasor and is
     * de-embedded downstream.
     *
     * @param calF1PhaseRad {@code argH(f₁)} — the {@code .frc} phase at F1
     * @param calF2PhaseRad {@code argH(f₂)} — the {@code .frc} phase at F2
     */
    public void accumulate(FftResult r, double f1Hz, double f2Hz, double step,
                           double calF1PhaseRad, double calF2PhaseRad) {
        if (r.re == null || r.im == null) return;
        int fundBin = r.fundamentalBin;
        if (fundBin <= 0 || fundBin >= r.re.length || !(f1Hz > 0.0) || !(f2Hz > 0.0)) return;

        double f1Re = !Double.isNaN(r.rawFundRe) ? r.rawFundRe : r.re[fundBin];
        double f1Im = !Double.isNaN(r.rawFundIm) ? r.rawFundIm : r.im[fundBin];
        double phi1 = Math.atan2(f1Im, f1Re) - calF1PhaseRad;        // F1 frame, notch phase removed
        // F2 is the (0,1) product in the analyzer's de-rotated grid.
        int f2Idx = productIndex(r, 0, 1);
        double phi2 = (f2Idx >= 0 && r.rawPeakRe != null && f2Idx < r.rawPeakRe.length)
                ? Math.atan2(r.rawPeakIm[f2Idx], r.rawPeakRe[f2Idx]) - calF2PhaseRad
                : phi1;

        for (int g = 0; g < coefA.length; g++) {
            int a = coefA[g], b = coefB[g];
            double fp = a * f1Hz + b * f2Hz;
            if (!(fp > 0.0)) continue;

            double magLin, phiRaw;
            if (b == 0) {
                int idx = a - 2;                          // harmonicBins[0] = 2nd harmonic
                if (r.harmonicBins == null || idx < 0 || idx >= r.harmonicCount) continue;
                int bin = r.harmonicBins[idx];
                if (bin <= 0 || bin >= r.re.length) continue;
                magLin = Math.pow(10.0, r.rawHarmonicDbFs(idx) / 20.0);   // absolute (lobe dBFS)
                phiRaw = Math.atan2(r.im[bin], r.re[bin]);               // display-de-embedded phase
            } else {
                int i = productIndex(r, a, b);
                if (i < 0 || r.rawPeakRe == null || i >= r.rawPeakRe.length) continue;
                magLin = Math.hypot(r.rawPeakRe[i], r.rawPeakIm[i]);     // absolute (conv-scaled dBFS-linear)
                phiRaw = Math.atan2(r.rawPeakIm[i], r.rawPeakRe[i]);     // raw phase (de-embed downstream)
            }
            double refPhase = a * (phi1 + Math.PI / 2.0) + b * (phi2 + Math.PI / 2.0);
            double phiInit  = phiRaw - refPhase;
            accRe[g] += step * magLin * Math.cos(phiInit);
            accIm[g] += step * magLin * Math.sin(phiInit);
            freqHz[g] = fp;
        }
    }

    /** Unit calibration response {@code [magLin=1, phaseRad=0]} — a no-op divide
     *  for the b=0 harmonics the display already de-embedded. */
    private static final double[] UNIT_RESPONSE = { 1.0, 0.0 };

    /** Index of a b≠0 product in the analyzer's de-rotated grid (so the raw
     *  phasor at {@code rawPeak[i]} / bin at {@code imdProductBin[i]} can be
     *  read), or {@code -1} when the analyzer didn't de-rotate it this tick. */
    private int productIndex(FftResult r, int a, int b) {
        int[] pa = r.imdProductA, pb = r.imdProductB;
        if (pa == null) return -1;
        for (int i = 0; i < pa.length; i++) {
            if (pa[i] == a && pb[i] == b) return i;
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

    /** Converts the accumulated ABSOLUTE (ADC-dBFS) phasors to the generator's
     *  dual-tone compensation quad — applying HERE (on the values reaching the
     *  DDS): the {@code .frc} de-embed (÷H, −argH) for every b≠0 product (b=0
     *  harmonics took the display de-embed already → unit response), the
     *  ADC-dBFS → Vrms conversion, and the division by the DAC F1 fundamental.
     *  Products below the gate are dropped. */
    public GeneratorCorrections toGeneratorCorrections(DoubleFunction<double[]> calResponseAt,
            double adcFsVoltageRms, double dacFundamentalVrms) {
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
            if (accRe[g] == 0.0 && accIm[g] == 0.0) continue;
            double[] cal = coefB[g] == 0 ? UNIT_RESPONSE : calResponseAt.apply(freqHz[g]);
            double magDe = Math.hypot(accRe[g], accIm[g]) / (cal[0] > 0.0 ? cal[0] : 1.0);
            double vP    = magDe * adcFsVoltageRms;                       // ADC dBFS → absolute Vrms
            amp[i] = dacFundamentalVrms > 0.0 ? vP / dacFundamentalVrms : 0.0;   // ratio to DAC F1
            phi[i] = Math.atan2(accIm[g], accRe[g]) - cal[1];
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
    public void writeDpd(Path file, List<String> extraHeaderLines,
                         double f1Hz, double f2Hz, double fundamentalDbFs,
                         int sampleRate, int bitDepth, double amplitudeVrms,
                         DoubleFunction<double[]> calResponseAt, double adcFsVoltageRms,
                         double dacFundamentalVrms) throws IOException {
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(file, StandardCharsets.UTF_8))) {
            if (extraHeaderLines != null) {
                for (String line : extraHeaderLines) pw.println(line);
            }
            pw.printf(Locale.US, "# sample_rate_hz=%d%n",    sampleRate);
            pw.printf(Locale.US, "# bit_depth=%d%n",         bitDepth);
            pw.printf(Locale.US, "# amplitude_vrms=%.10f%n",  amplitudeVrms);
            pw.printf(Locale.US, "# frequency1_hz=%.10f%n",   f1Hz);
            pw.printf(Locale.US, "# frequency2_hz=%.10f%n",   f2Hz);
            // re/im carry the ratio phasor the dual-tone loader consumes; the
            // amplitude_dbv column carries the de-embedded ABSOLUTE dBV.  Same
            // de-embed as toGeneratorCorrections.
            pw.println("a;b;frequency_hz;amplitude_dbv;amplitude_pct;phase_deg;re;im");
            for (int g = 0; g < accRe.length; g++) {
                if (accRe[g] == 0.0 && accIm[g] == 0.0) continue;
                double[] cal = coefB[g] == 0 ? UNIT_RESPONSE : calResponseAt.apply(freqHz[g]);
                double magDe = Math.hypot(accRe[g], accIm[g]) / (cal[0] > 0.0 ? cal[0] : 1.0);
                double vP    = magDe * adcFsVoltageRms;
                double amp   = dacFundamentalVrms > 0.0 ? vP / dacFundamentalVrms : 0.0;
                double phase = Math.atan2(accIm[g], accRe[g]) - cal[1];
                double re = amp * Math.cos(phase), im = amp * Math.sin(phase);
                double phaseDeg = Math.toDegrees(phase);
                double ampPct   = amp * 100.0;
                double ampDbV   = vP > 0.0 ? 20.0 * Math.log10(vP) : -300.0;
                pw.printf(Locale.GERMAN, "%d;%d;%.6f;%.4f;%.9f;%.4f;%.10e;%.10e%n",
                        coefA[g], coefB[g], freqHz[g], ampDbV, ampPct, phaseDeg, re, im);
            }
        }
    }
}
