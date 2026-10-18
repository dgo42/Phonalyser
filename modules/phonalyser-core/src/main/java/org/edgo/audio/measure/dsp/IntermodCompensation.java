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

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleFunction;

import org.edgo.audio.measure.fft.FftResult;

/**
 * Complex accumulator for closed-loop DAC <em>intermodulation</em>
 * pre-distortion - the two-tone sibling of {@link HarmonicCompensation},
 * shared by the GUI predistortion wizard's dual-tone path.
 *
 * <p>Every distortion product of a two-tone signal - each tone's harmonics
 * <em>and</em> their intermodulation products alike - sits at
 * {@code a·f₁ + b·f₂} for integer {@code (a, b)} and has instantaneous phase
 * {@code a·θ₁ + b·θ₂} from the generator's two DDS accumulators, so a single
 * {@code (a, b)}-indexed correction set cancels them all.  This class holds a
 * fixed grid of such products - the eight products the IMD readout lists
 * ({@code ImdAnalyzer}): {@code f2 - f1} and {@code f1 + f2}, then
 * {@code (n-1)*f1 - (n-2)*f2} and {@code (n-1)*f2 - (n-2)*f1} for n = 3 .. 5 -
 * and accumulates an LMS-style correction phasor per product:
 *
 * <ul>
 *   <li>the product's <b>phase-stable</b> averaged value comes straight from
 *       the FFT's {@code re}/{@code im} - the analyzer already de-rotates each
 *       product bin by {@code a·Φ(F1)+b·Φ(F2)}
 *       ({@link FftResult#imdProductA}), so the products enjoy the same
 *       trustworthy phase the per-tone harmonics get from the single-reference
 *       de-rotation;</li>
 *   <li>the transport delay {@code ωD = −(φ₁+π/2)} is read from F1 and applied
 *       per product by its frequency - identical to the single-tone path.</li>
 * </ul>
 *
 * Only a product standing {@link HarmonicCompensation#APPLY_CLEARANCE_DB} or
 * more over its own local band top moves its correction (a floor-level reading
 * would random-walk the accumulator).  The result serialises to a dual-tone
 * predistortion file ({@code .dpd}, {@link #writeDpd}).
 *
 * <p>The conversion de-embeds through the loaded {@code .frc} response - the
 * chain's measured complex transfer at each product, which carries every
 * rotation a correction needs (a phase is only defined modulo one turn, so a
 * transport delay is indistinguishable from its per-frequency rotation, and
 * the rotation is what the calibration measures).
 */
public final class IntermodCompensation {

    /** The correction grid as {@code (a, b)} of {@code a*f1 + b*f2}, in the
     *  IMD readout's order and naming (d2L, d2H, d3L .. d5H): {@code f2 - f1}
     *  and {@code f1 + f2}, then per n = 3 .. 5 the product
     *  {@code (n-1)*f1 - (n-2)*f2} below F1 and {@code (n-1)*f2 - (n-2)*f1}
     *  above F2.  The n-th pair is of order {@code 2n - 3}, and the analyzer
     *  de-rotates products only up to order {@code max(5, harmonicCount + 1)}
     *  ({@link FftResult#imdProductA}): the d5 pair, of order 7, carries a
     *  phasor only from 6 harmonics on; below that it has no frequency on the
     *  result and never moves its correction. */
    private static final int[][] IMD_PRODUCTS = {
        { -1,  1 }, {  1,  1 },
        {  2, -1 }, { -1,  2 },
        {  3, -2 }, { -2,  3 },
        {  4, -3 }, { -3,  4 },
    };

    /** Per-product θ₁ / θ₂ integer multipliers (the grid), the accumulated
     *  correction phasor, and the product's last-measured frequency. */
    private final int[]    coefA;
    private final int[]    coefB;
    private final double[] accRe;
    private final double[] accIm;
    private final double[] freqHz;
    /** Reads each product's local noise floor for the apply gate. */
    private final ToneLobeLift lobe = new ToneLobeLift();

    public IntermodCompensation() {
        int products = IMD_PRODUCTS.length;
        this.coefA  = new int[products];
        this.coefB  = new int[products];
        for (int g = 0; g < products; g++) {
            coefA[g] = IMD_PRODUCTS[g][0];
            coefB[g] = IMD_PRODUCTS[g][1];
        }
        this.accRe   = new double[products];
        this.accIm   = new double[products];
        this.freqHz  = new double[products];
    }

    /**
     * LMS-style accumulate of the two-tone distortion products measured in
     * {@code r} - the dual-tone twin of {@link HarmonicCompensation#accumulate},
     * sharing its design: the accumulator holds each product's ABSOLUTE measured
     * level (dBFS-linear, off the conv-scaled phasors - the actual ADC reading,
     * not a notched-fundamental ratio) as a complex phasor, window-derotated; the
     * {@code .frc} de-embed, the dBV conversion and the division by the DAC
     * fundamental are applied on the way OUT ({@link #writeDpd}).  The
     * products are gated as the overload over the
     * measured {@link ProductPhasors} states.
     *
     * <p><b>Two fundamentals frame the products.</b>  Each product {@code a·f₁+b·f₂}
     * is de-rotated by {@code a·(φ₁+π/2) + b·(φ₂+π/2)} so it lands in the same
     * per-round frame, where {@code φ₁ = arg(X_F1) − argH(f₁)} and
     * {@code φ₂ = arg(X_F2) − argH(f₂)} are the measured fundamental phases with
     * the twin-T notch phase removed (a pure phase subtraction; without it the
     * notch's ≈π at each tone would flip products and build the dual-tone
     * triangle - see HarmonicCompensation).  F2 is the {@code (0,1)} grid product.
     * Every product reads the raw conv-scaled phasor and is de-embedded
     * downstream.
     *
     * @param calF1PhaseRad {@code argH(f₁)} - the {@code .frc} phase at F1
     * @param calF2PhaseRad {@code argH(f₂)} - the {@code .frc} phase at F2
     */
    public void accumulate(FftResult r, double f1Hz, double f2Hz, double step,
                           double calF1PhaseRad, double calF2PhaseRad) {
        accumulate(measurePhasors(r, f1Hz, f2Hz, calF1PhaseRad, calF2PhaseRad), step);
    }

    /**
     * The update from one frame's measured products: a clear product's
     * measured residual added at {@code step} to the correction in force.
     * Only a product standing {@link HarmonicCompensation#APPLY_CLEARANCE_DB}
     * or more over its own local band top moves its correction, as in
     * {@link HarmonicCompensation#accumulate}: a floor-level reading is a
     * noise vector, and a step on it writes that noise into the generator.
     * A gated-out product, and one without a measured clearance, keeps its
     * correction unchanged; a measured product's frequency is stored either
     * way.
     */
    public void accumulate(ProductPhasors z, double step) {
        for (int g = 0; g < accRe.length; g++) {
            if (!(z.hz()[g] > 0.0)) continue;            // product not measured in this frame
            double clearance = z.clearanceDb() != null ? z.clearanceDb()[g] : Double.NaN;
            if (Double.isFinite(clearance) && clearance >= HarmonicCompensation.APPLY_CLEARANCE_DB) {
                accRe[g] += step * z.re()[g];
                accIm[g] += step * z.im()[g];
            }
            freqHz[g] = z.hz()[g];
        }
    }

    /** Per-product complex phasors with the grid coordinates - each product's
     *  multiple of the first tone and of the second - the frequency each
     *  belongs to and how far each stood above its own local band top (dB,
     *  {@link Double#NaN} when unknown).  Carries a frame's measurement
     *  ({@link #measurePhasors}) and the measured plant
     *  ({@link #measurePlant}), whose unmeasured entries are
     *  {@link Double#NaN}. */
    public record ProductPhasors(int[] toneOneMultiples, int[] toneTwoMultiples, double[] re, double[] im,
                                 double[] hz, double[] clearanceDb) {}

    /**
     * The distortion products of one frame as complex phasors in the
     * accumulator's frame - the construction {@link #accumulate} adds in, and
     * the same one the probe differences to measure the plant, so both read a
     * frame identically.  A product the analyzer did not publish this
     * analysis cycle carries {@code (0, 0)} at frequency 0.
     * The clearance is the product's raw level over the upper border of the
     * noise band beside its bin ({@link ToneLobeLift#lineFloor}), as
     * {@link HarmonicCompensation#measurePhasors} reads it for a harmonic;
     * {@link Double#NaN} where either is unknown.
     *
     * @param calF1PhaseRad {@code argH(f₁)} - the {@code .frc} phase at F1
     * @param calF2PhaseRad {@code argH(f₂)} - the {@code .frc} phase at F2
     */
    public ProductPhasors measurePhasors(FftResult r, double f1Hz, double f2Hz,
                                         double calF1PhaseRad, double calF2PhaseRad) {
        double[] re = new double[coefA.length];
        double[] im = new double[coefA.length];
        double[] hz = new double[coefA.length];
        double[] clearance = new double[coefA.length];
        Arrays.fill(clearance, Double.NaN);
        ProductPhasors out = new ProductPhasors(coefA.clone(), coefB.clone(), re, im, hz, clearance);
        if (r.re == null || r.im == null) return out;
        int fundBin = r.fundamentalBin;
        if (fundBin <= 0 || fundBin >= r.re.length || !(f1Hz > 0.0) || !(f2Hz > 0.0)) return out;

        double f1Re = !Double.isNaN(r.rawFundRe) ? r.rawFundRe : r.re[fundBin];
        double f1Im = !Double.isNaN(r.rawFundIm) ? r.rawFundIm : r.im[fundBin];
        // Each tone as the DAC emitted it, where the alignment has it - from
        // the pilots or the opposite channel, at the same origin as the peaks
        // below - and otherwise the notched reading less the calibration's
        // static phase.  See FftResult.dacFundPhaseRad.
        double phi1 = !Double.isNaN(r.dacFundPhaseRad)
                ? r.dacFundPhaseRad
                : Math.atan2(f1Im, f1Re) - calF1PhaseRad;        // F1 frame, notch phase removed
        // F2 is the (0,1) product in the analyzer's de-rotated grid.
        int f2Idx = productIndex(r, 0, 1);
        double phi2 = !Double.isNaN(r.dacFund2PhaseRad)
                ? r.dacFund2PhaseRad
                : ((f2Idx >= 0 && r.rawPeakRe != null && f2Idx < r.rawPeakRe.length)
                        ? Math.atan2(r.rawPeakIm[f2Idx], r.rawPeakRe[f2Idx]) - calF2PhaseRad
                        : phi1);

        for (int g = 0; g < coefA.length; g++) {
            double[] slot = rawSlot(r, g, f1Hz, f2Hz);
            if (slot == null) continue;
            double refPhase = coefA[g] * (phi1 + Math.PI / 2.0) + coefB[g] * (phi2 + Math.PI / 2.0);
            double phiInit  = slot[1] - refPhase;
            re[g] = slot[0] * Math.cos(phiInit);
            im[g] = slot[0] * Math.sin(phiInit);
            hz[g] = slot[2];
            clearance[g] = clearanceDb(r, g, 20.0 * Math.log10(slot[0]));
        }
        return out;
    }

    /**
     * The same per-product reading as {@link #measurePhasors}, but RAW - the
     * magnitude and the phase as measured, before the two-tone de-rotation.
     * The diagnostic record keeps the raw observation and states the two tone
     * references beside it, so the de-rotation can be reproduced from the file
     * instead of being baked into it.  A product the frame does not carry
     * stays {@code (0, 0)} at frequency 0.  No clearance is read here
     * ({@link Double#NaN} throughout).
     */
    public ProductPhasors rawPhasors(FftResult r, double f1Hz, double f2Hz) {
        double[] re = new double[coefA.length];
        double[] im = new double[coefA.length];
        double[] hz = new double[coefA.length];
        double[] clearance = new double[coefA.length];
        Arrays.fill(clearance, Double.NaN);
        ProductPhasors out = new ProductPhasors(coefA.clone(), coefB.clone(), re, im, hz, clearance);
        if (r.re == null || r.im == null) return out;
        if (r.fundamentalBin <= 0 || !(f1Hz > 0.0) || !(f2Hz > 0.0)) return out;
        for (int g = 0; g < coefA.length; g++) {
            double[] slot = rawSlot(r, g, f1Hz, f2Hz);
            if (slot == null) continue;
            re[g] = slot[0] * Math.cos(slot[1]);
            im[g] = slot[0] * Math.sin(slot[1]);
            hz[g] = slot[2];
        }
        return out;
    }

    /** The grid this accumulator corrects at the given tones - the {@code (a,
     *  b)} coordinates and each product's frequency, with no measurement
     *  ({@link Double#NaN} phasors and clearances).  Lets a caller state the
     *  chain response at every product without knowing how the grid is built. */
    public ProductPhasors grid(double f1Hz, double f2Hz) {
        double[] re = new double[coefA.length];
        double[] im = new double[coefA.length];
        double[] hz = new double[coefA.length];
        double[] clearance = new double[coefA.length];
        Arrays.fill(re, Double.NaN);
        Arrays.fill(im, Double.NaN);
        Arrays.fill(clearance, Double.NaN);
        for (int g = 0; g < coefA.length; g++) {
            hz[g] = coefA[g] * f1Hz + coefB[g] * f2Hz;
        }
        return new ProductPhasors(coefA.clone(), coefB.clone(), re, im, hz, clearance);
    }

    /** The SECOND tone's raw phasor {@code [re, im]} - the {@code (0,1)} entry
     *  of the analyzer's de-rotated grid.  The dual de-rotation needs
     *  {@code arg(F2)} beside {@code arg(F1)}, so a diagnostic record of a
     *  two-tone round has to state it.  {@code null} when the frame does not
     *  carry the second tone. */
    public double[] secondTonePhasor(FftResult r) {
        int i = productIndex(r, 0, 1);
        if (i < 0 || r.rawPeakRe == null || i >= r.rawPeakRe.length) return null;
        return new double[]{ r.rawPeakRe[i], r.rawPeakIm[i] };
    }

    /**
     * The raw reading of grid slot {@code g} in {@code r}:
     * {@code [magLin, phaseRad, freqHz]}, or {@code null} when the frame does
     * not carry that product.  The single definition both the accumulator
     * frame and the raw view read through, so the two can never disagree about
     * what a frame says: every product takes the raw conv-scaled phasor the
     * analyzer's product grid captured ({@link FftResult#rawPeakRe}), the
     * chain de-embedded downstream.
     */
    private double[] rawSlot(FftResult r, int g, double f1Hz, double f2Hz) {
        int a = coefA[g];
        int b = coefB[g];
        double fp = a * f1Hz + b * f2Hz;
        if (!(fp > 0.0)) return null;
        int i = productIndex(r, a, b);
        if (i < 0 || r.rawPeakRe == null || i >= r.rawPeakRe.length) return null;
        double magLin = Math.hypot(r.rawPeakRe[i], r.rawPeakIm[i]);     // absolute (conv-scaled dBFS-linear)
        double phiRaw = Math.atan2(r.rawPeakIm[i], r.rawPeakRe[i]);     // raw phase (de-embed downstream)
        return new double[]{ magLin, phiRaw, fp };
    }

    /**
     * Grid slot {@code g}'s clearance in {@code r}, dB: the product's raw
     * level {@code rawDbFs} over the upper border of the noise band beside
     * its bin ({@link ToneLobeLift#lineFloor} on
     * {@link FftResult#amplitudeDbFs}), read the way
     * {@link HarmonicCompensation#measurePhasors} reads a harmonic's.
     * {@link Double#NaN} when the result carries no spectrum or no bin for
     * the product, or when either level is not finite.
     */
    private double clearanceDb(FftResult r, int g, double rawDbFs) {
        int i = productIndex(r, coefA[g], coefB[g]);
        if (i < 0 || r.imdProductBin == null || i >= r.imdProductBin.length
                || r.amplitudeDbFs == null) return Double.NaN;
        int bin = r.imdProductBin[i];
        double[] ampDb = r.amplitudeDbFs;
        if (bin <= 0 || bin >= ampDb.length) return Double.NaN;
        double floorLin = lobe.lineFloor(k -> Math.pow(10.0, ampDb[k] / 20.0), bin, ampDb.length - 1,
                r.freqResolution, r.windowNenbwBins);
        double floorDb  = floorLin > 0.0 ? 20.0 * Math.log10(floorLin) : Double.NaN;
        return Double.isFinite(rawDbFs) && Double.isFinite(floorDb) ? rawDbFs - floorDb : Double.NaN;
    }

    /** Index of a b≠0 product in the analyzer's de-rotated grid (so the raw
     *  phasor at {@code rawPeak[i]} / bin at {@code imdProductBin[i]} can be
     *  read), or {@code -1} when the analyzer didn't de-rotate it this
     *  analysis cycle. */
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

    /** Deep copy - lets the wizard snapshot the best-THD round's accumulator
     *  while the loop keeps updating the live one. */
    public IntermodCompensation copy() {
        return new IntermodCompensation(coefA, coefB, accRe, accIm, freqHz);
    }

    /** Full-array constructor used only by {@link #copy()}. */
    private IntermodCompensation(int[] coefA, int[] coefB,
                                 double[] accRe, double[] accIm, double[] freqHz) {
        this.coefA   = coefA.clone();
        this.coefB   = coefB.clone();
        this.accRe   = accRe.clone();
        this.accIm   = accIm.clone();
        this.freqHz  = freqHz.clone();
    }

    /**
     * Writes a dual-tone predistortion file ({@code .dpd}).  Mirrors the
     * single-tone format ({@link HarmonicCompensation#writeDpd}) but the
     * per-row key is the {@code (a, b)} coefficient pair instead of a single
     * harmonic number - German-locale data rows (comma decimals, semicolon
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
            // amplitude_dbv column carries the de-embedded ABSOLUTE dBV.
            pw.println("a;b;frequency_hz;amplitude_dbv;amplitude_pct;phase_deg;re;im");
            for (int g = 0; g < accRe.length; g++) {
                if (accRe[g] == 0.0 && accIm[g] == 0.0) continue;
                // De-embed: divided by the chain's H(f_p), ADC dBFS -> Vrms,
                // ratio to the DAC fundamental.
                double[] cal = calResponseAt.apply(freqHz[g]);
                double magDe = Math.hypot(accRe[g], accIm[g]) / (cal[0] > 0.0 ? cal[0] : 1.0);
                double vP    = magDe * adcFsVoltageRms;
                double amp   = dacFundamentalVrms > 0.0 ? vP / dacFundamentalVrms : 0.0;
                double phase = Math.atan2(accIm[g], accRe[g]) - cal[1];
                double re = amp * Math.cos(phase), im = amp * Math.sin(phase);
                double phaseDeg = Math.toDegrees(phase);
                double ampPct   = amp * 100.0;
                double ampDbV   = vP > 0.0 ? 20.0 * Math.log10(vP) : -300.0;
                pw.printf(Locale.GERMAN, "%d;%d;%.6f;%.4f;%.15f;%.4f;%.10e;%.10e%n",
                        coefA[g], coefB[g], freqHz[g], ampDbV, ampPct, phaseDeg, re, im);
            }
        }
    }
}
