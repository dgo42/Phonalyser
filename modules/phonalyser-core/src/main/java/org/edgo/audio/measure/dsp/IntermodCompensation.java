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
import java.util.ArrayList;
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
 * fixed grid of such products (per-tone harmonics + the low-order intermod
 * comb) and accumulates an LMS-style correction phasor per product:
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
 * Products within {@code snrMargin} dB of the noise floor are skipped (they
 * would random-walk the accumulator).  The result converts to the generator's
 * dual-tone compensation API ({@link #toGeneratorCorrections}) and serialises
 * to a dual-tone {@code applied_compensation} CSV ({@link #writeCsv}).
 *
 * <p>Both conversions divide by the per-product PLANT transfer - what the
 * chain answers to a commanded correction at that product, measured once by
 * {@link #measurePlant} and installed by {@link #setPlant}.  It is the
 * identity until a probe installs one, so an accumulator that never sees a
 * probe converts exactly as it always did.  Without it the loop assumes a
 * perfect chain, and an excess transport delay turns every command by an angle
 * that grows with the product's frequency until the unit-step iteration stops
 * contracting.
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
    /** Per-product PLANT transfer G - what the DAC-to-ADC chain answers to a
     *  commanded correction at that product, measured once by the probe
     *  ({@link #measurePlant}).  Identity {@code (1, 0)} until one is
     *  installed, which leaves every outgoing correction bit-for-bit what it
     *  was before a plant existed. */
    private final double[] plantRe;
    private final double[] plantIm;

    public IntermodCompensation(int maxHarmonics) {
        List<int[]> grid = buildGrid(maxHarmonics);
        int n = grid.size();
        this.coefA  = new int[n];
        this.coefB  = new int[n];
        for (int i = 0; i < n; i++) {
            coefA[i] = grid.get(i)[0];
            coefB[i] = grid.get(i)[1];
        }
        this.accRe   = new double[n];
        this.accIm   = new double[n];
        this.freqHz  = new double[n];
        this.plantRe = new double[n];
        this.plantIm = new double[n];
        Arrays.fill(plantRe, 1.0);                       // identity plant
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
     * {@code r} - the dual-tone twin of {@link HarmonicCompensation#accumulate},
     * sharing its design: the accumulator holds each product's ABSOLUTE measured
     * level (dBFS-linear, off the conv-scaled phasors - the actual ADC reading,
     * not a notched-fundamental ratio) as a complex phasor, window-derotated; the
     * {@code .frc} de-embed, the dBV conversion and the division by the DAC
     * fundamental are applied on the way OUT ({@link #toGeneratorCorrections} /
     * {@link #writeDpd}).  Every detected product is corrected - no noise gate.
     *
     * <p><b>Two fundamentals frame the products.</b>  Each product {@code a·f₁+b·f₂}
     * is de-rotated by {@code a·(φ₁+π/2) + b·(φ₂+π/2)} so it lands in the same
     * per-round frame, where {@code φ₁ = arg(X_F1) − argH(f₁)} and
     * {@code φ₂ = arg(X_F2) − argH(f₂)} are the measured fundamental phases with
     * the twin-T notch phase removed (a pure phase subtraction; without it the
     * notch's ≈π at each tone would flip products and build the dual-tone
     * triangle - see HarmonicCompensation).  F2 is the {@code (0,1)} grid product.
     * The b=0 F1 harmonics read the display-de-embedded {@code re}/{@code im}
     * phase + {@link FftResult#rawHarmonicDbFs} magnitude (and take unit response
     * downstream); every b≠0 product reads the raw conv-scaled phasor and is
     * de-embedded downstream.
     *
     * @param calF1PhaseRad {@code argH(f₁)} - the {@code .frc} phase at F1
     * @param calF2PhaseRad {@code argH(f₂)} - the {@code .frc} phase at F2
     */
    public void accumulate(FftResult r, double f1Hz, double f2Hz, double step,
                           double calF1PhaseRad, double calF2PhaseRad) {
        ProductPhasors z = measurePhasors(r, f1Hz, f2Hz, calF1PhaseRad, calF2PhaseRad);
        for (int g = 0; g < accRe.length; g++) {
            if (!(z.hz()[g] > 0.0)) continue;            // product not measured in this frame
            accRe[g] += step * z.re()[g];
            accIm[g] += step * z.im()[g];
            freqHz[g] = z.hz()[g];
        }
    }

    /** Per-product complex phasors with the grid coordinates and the frequency
     *  each belongs to.  Carries a frame's measurement
     *  ({@link #measurePhasors}) and the measured plant
     *  ({@link #measurePlant}), whose unmeasured entries are
     *  {@link Double#NaN}. */
    public record ProductPhasors(int[] coefA, int[] coefB, double[] re, double[] im, double[] hz) {}

    /**
     * The distortion products of one frame as complex phasors in the
     * accumulator's frame - the construction {@link #accumulate} adds in, and
     * the same one the probe differences to measure the plant, so both read a
     * frame identically.  A product the analyzer did not publish this tick
     * carries {@code (0, 0)} at frequency 0.
     *
     * @param calF1PhaseRad {@code argH(f₁)} - the {@code .frc} phase at F1
     * @param calF2PhaseRad {@code argH(f₂)} - the {@code .frc} phase at F2
     */
    public ProductPhasors measurePhasors(FftResult r, double f1Hz, double f2Hz,
                                         double calF1PhaseRad, double calF2PhaseRad) {
        double[] re = new double[coefA.length];
        double[] im = new double[coefA.length];
        double[] hz = new double[coefA.length];
        ProductPhasors out = new ProductPhasors(coefA.clone(), coefB.clone(), re, im, hz);
        if (r.re == null || r.im == null) return out;
        int fundBin = r.fundamentalBin;
        if (fundBin <= 0 || fundBin >= r.re.length || !(f1Hz > 0.0) || !(f2Hz > 0.0)) return out;

        double f1Re = !Double.isNaN(r.rawFundRe) ? r.rawFundRe : r.re[fundBin];
        double f1Im = !Double.isNaN(r.rawFundIm) ? r.rawFundIm : r.im[fundBin];
        double phi1 = Math.atan2(f1Im, f1Re) - calF1PhaseRad;        // F1 frame, notch phase removed
        // F2 is the (0,1) product in the analyzer's de-rotated grid.
        int f2Idx = productIndex(r, 0, 1);
        double phi2 = (f2Idx >= 0 && r.rawPeakRe != null && f2Idx < r.rawPeakRe.length)
                ? Math.atan2(r.rawPeakIm[f2Idx], r.rawPeakRe[f2Idx]) - calF2PhaseRad
                : phi1;

        for (int g = 0; g < coefA.length; g++) {
            double[] slot = rawSlot(r, g, f1Hz, f2Hz);
            if (slot == null) continue;
            double refPhase = coefA[g] * (phi1 + Math.PI / 2.0) + coefB[g] * (phi2 + Math.PI / 2.0);
            double phiInit  = slot[1] - refPhase;
            re[g] = slot[0] * Math.cos(phiInit);
            im[g] = slot[0] * Math.sin(phiInit);
            hz[g] = slot[2];
        }
        return out;
    }

    /**
     * The same per-product reading as {@link #measurePhasors}, but RAW - the
     * magnitude and the phase as measured, before the two-tone de-rotation.
     * The diagnostic record keeps the raw observation and states the two tone
     * references beside it, so the de-rotation can be reproduced from the file
     * instead of being baked into it.  A product the frame does not carry
     * stays {@code (0, 0)} at frequency 0.
     */
    public ProductPhasors rawPhasors(FftResult r, double f1Hz, double f2Hz) {
        double[] re = new double[coefA.length];
        double[] im = new double[coefA.length];
        double[] hz = new double[coefA.length];
        ProductPhasors out = new ProductPhasors(coefA.clone(), coefB.clone(), re, im, hz);
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
     *  ({@link Double#NaN} phasors).  Lets a caller state the chain response at
     *  every product without knowing how the grid is built. */
    public ProductPhasors grid(double f1Hz, double f2Hz) {
        double[] re = new double[coefA.length];
        double[] im = new double[coefA.length];
        double[] hz = new double[coefA.length];
        Arrays.fill(re, Double.NaN);
        Arrays.fill(im, Double.NaN);
        for (int g = 0; g < coefA.length; g++) {
            hz[g] = coefA[g] * f1Hz + coefB[g] * f2Hz;
        }
        return new ProductPhasors(coefA.clone(), coefB.clone(), re, im, hz);
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
     * what a frame says: a b=0 F1 harmonic takes the display-de-embedded
     * {@code re}/{@code im} phase with the raw harmonic level (and unit
     * response downstream), every b!=0 product takes the raw conv-scaled
     * phasor.
     */
    private double[] rawSlot(FftResult r, int g, double f1Hz, double f2Hz) {
        int a = coefA[g];
        int b = coefB[g];
        double fp = a * f1Hz + b * f2Hz;
        if (!(fp > 0.0)) return null;
        double magLin;
        double phiRaw;
        if (b == 0) {
            int idx = a - 2;                              // harmonicBins[0] = 2nd harmonic
            if (r.harmonicBins == null || idx < 0 || idx >= r.harmonicCount) return null;
            int bin = r.harmonicBins[idx];
            if (bin <= 0 || bin >= r.re.length) return null;
            magLin = Math.pow(10.0, r.rawHarmonicDbFs(idx) / 20.0);   // absolute (lobe dBFS)
            phiRaw = Math.atan2(r.im[bin], r.re[bin]);               // display-de-embedded phase
        } else {
            int i = productIndex(r, a, b);
            if (i < 0 || r.rawPeakRe == null || i >= r.rawPeakRe.length) return null;
            magLin = Math.hypot(r.rawPeakRe[i], r.rawPeakIm[i]);     // absolute (conv-scaled dBFS-linear)
            phiRaw = Math.atan2(r.rawPeakIm[i], r.rawPeakRe[i]);     // raw phase (de-embed downstream)
        }
        return new double[]{ magLin, phiRaw, fp };
    }

    /**
     * True when {@code r} carries the analyzer's de-rotated phasor for at
     * least one MIXED ({@code b != 0}) product of this grid - read through the
     * very {@link #rawSlot} the measurement uses, so the answer cannot differ
     * from what a measurement would find.
     *
     * <p>The per-product de-rotation is a per-tick decision: a frame taken
     * right after a statistics re-anchor or a discontinuity re-sync can carry
     * no de-rotated products at all ({@link FftResult#imdProductA} empty).  A
     * plant measured from such a frame is not wrong, it is HALF measured - the
     * b=0 harmonics still read out of the harmonic table while every mixed
     * product looks "not measured this tick" and silently keeps the identity,
     * so nothing in the result marks the loss.  A caller that gets to choose
     * its frame - the probe - must therefore ask first.
     */
    public boolean carriesProducts(FftResult r) {
        if (r == null || r.re == null || r.im == null) return false;
        double f1Hz = r.fundamentalHzRefined;
        double f2Hz = r.fundamental2HzRefined;
        if (!(f1Hz > 0.0) || !(f2Hz > 0.0)) return false;
        for (int g = 0; g < coefA.length; g++) {
            if (coefB[g] != 0 && rawSlot(r, g, f1Hz, f2Hz) != null) return true;
        }
        return false;
    }

    /**
     * The probe command for the WHOLE grid in the generator's dual-tone shape:
     * every product commanded at {@code ampRatio} (ratio to the DAC F1 tone)
     * with initial phases stepped {@code phaseStepRad} apart.  The caller hands
     * the same object to the generator and to {@link #measurePlant}, so what
     * was commanded and what the measurement divides by cannot drift apart.
     *
     * <p>Stepped phases are not required to measure the plant - any known phase
     * would do - but they make a product delivered to the wrong DDS partial
     * show up as a plant phase that does not fit the measured pattern.
     */
    public GeneratorCorrections probeCommand(double ampRatio, double phaseStepRad) {
        int n = coefA.length;
        double[] amp = new double[n];
        double[] phi = new double[n];
        for (int g = 0; g < n; g++) {
            amp[g] = ampRatio;
            phi[g] = g * phaseStepRad;
        }
        return new GeneratorCorrections(amp, coefA.clone(), coefB.clone(), phi);
    }

    /**
     * Measures the per-product plant transfer G - what the chain actually does
     * with a correction it is commanded to inject - from two frames: one
     * collected with no probe, one with the {@link #probeCommand} partials
     * applied through the same generator path the corrections use.  The DUT's
     * own products and the noise are common to both frames, so their difference
     * is the probe's arrival alone; dividing it by the arrival the command
     * should have produced leaves the chain's own magnitude and phase answer.
     *
     * <p>The expected arrival is the inverse of what
     * {@link #toGeneratorCorrections} commands, so the plant absorbs every
     * modelling step that conversion takes: the generator SUBTRACTS the
     * commanded phasor, the partial reaches the ADC through the response this
     * class de-embeds against ({@code H(f_p)} for a b!=0 product, unit for the
     * b=0 harmonics the display already de-embedded), and the products are
     * measured against a fundamental that carries {@code |H(f1)|}:
     * {@code expected = -c * H_p * |F1_raw| / |H(f1)|}.
     *
     * <p>A product whose arrival does not stand {@code clearanceDb} above the
     * frame's noise floor gets {@link Double#NaN} - a noise reading must never
     * become a plant to divide by.
     *
     * @param baseline      frame collected with no probe applied
     * @param probe         frame collected with the probe partials applied
     * @param command       what {@link #probeCommand} handed the generator
     * @param calResponseAt the loaded {@code .frc} response {@code [magLin,
     *                      phaseRad]} at a frequency
     * @param clearanceDb   dB the arrival must clear the noise floor by
     */
    public ProductPhasors measurePlant(FftResult baseline, FftResult probe,
            GeneratorCorrections command, DoubleFunction<double[]> calResponseAt,
            double clearanceDb) {
        int n = coefA.length;
        double[] gRe = new double[n];
        double[] gIm = new double[n];
        double[] hz  = new double[n];
        Arrays.fill(gRe, Double.NaN);
        Arrays.fill(gIm, Double.NaN);
        ProductPhasors out = new ProductPhasors(coefA.clone(), coefB.clone(), gRe, gIm, hz);

        double f1 = probe.fundamentalHzRefined;
        double f2 = probe.fundamental2HzRefined;
        if (!(f1 > 0.0) || !(f2 > 0.0)) return out;
        double[] calF1 = calResponseAt.apply(f1);
        double   calF2Phase = calResponseAt.apply(f2)[1];
        double   fundLin = Math.hypot(probe.rawFundRe, probe.rawFundIm);
        if (!(fundLin > 0.0) || !(calF1[0] > 0.0)) return out;

        ProductPhasors base   = measurePhasors(baseline, baseline.fundamentalHzRefined,
                baseline.fundamental2HzRefined,
                calResponseAt.apply(baseline.fundamentalHzRefined)[1],
                calResponseAt.apply(baseline.fundamental2HzRefined)[1]);
        ProductPhasors probed = measurePhasors(probe, f1, f2, calF1[1], calF2Phase);

        for (int g = 0; g < n; g++) {
            if (g >= command.ampRatios().length || !(command.ampRatios()[g] > 0.0)) continue;
            if (!(base.hz()[g] > 0.0) || !(probed.hz()[g] > 0.0)) continue;
            double dRe = probed.re()[g] - base.re()[g];
            double dIm = probed.im()[g] - base.im()[g];
            double arrivalDb = 20.0 * Math.log10(Math.hypot(dRe, dIm));
            if (!(arrivalDb >= probe.avgNoiseFloorDbFs + clearanceDb)) continue;

            double[] cal = deEmbedResponse(g, probed.hz()[g], calResponseAt);
            double hRe = cal[0] * Math.cos(cal[1]);
            double hIm = cal[0] * Math.sin(cal[1]);
            double cRe = command.ampRatios()[g] * Math.cos(command.phiInits()[g]);
            double cIm = command.ampRatios()[g] * Math.sin(command.phiInits()[g]);
            double scale = -fundLin / calF1[0];           // the generator subtracts the command
            double eRe = scale * (cRe * hRe - cIm * hIm);
            double eIm = scale * (cRe * hIm + cIm * hRe);
            double den = eRe * eRe + eIm * eIm;
            if (!(den > 0.0)) continue;
            gRe[g] = (dRe * eRe + dIm * eIm) / den;       // measured / expected
            gIm[g] = (dIm * eRe - dRe * eIm) / den;
            hz[g]  = probed.hz()[g];
        }
        return out;
    }

    /** Installs a measured plant.  An entry that is not finite (no trustworthy
     *  probe for that product) or exactly zero keeps the identity, so such a
     *  product is corrected exactly as it was before any probe ran. */
    public void setPlant(ProductPhasors plant) {
        for (int g = 0; g < plantRe.length && g < plant.re().length; g++) {
            double re = plant.re()[g];
            double im = plant.im()[g];
            if (!Double.isFinite(re) || !Double.isFinite(im)) continue;
            if (re == 0.0 && im == 0.0) continue;
            plantRe[g] = re;
            plantIm[g] = im;
        }
    }

    /** The accumulated phasor as it LEAVES the accumulator: divided by the
     *  measured plant, so the command answers the chain's own magnitude and
     *  phase instead of assuming a perfect one.  With the identity plant the
     *  division is by {@code 1 + 0j} - the accumulator passes through
     *  unchanged, bit for bit. */
    private double[] correctedPhasor(int g) {
        double gRe = plantRe[g];
        double gIm = plantIm[g];
        double den = gRe * gRe + gIm * gIm;
        if (!(den > 0.0)) return new double[]{ accRe[g], accIm[g] };
        return new double[]{ (accRe[g] * gRe + accIm[g] * gIm) / den,
                             (accIm[g] * gRe - accRe[g] * gIm) / den };
    }

    /** Unit calibration response {@code [magLin=1, phaseRad=0]} - a no-op divide
     *  for the b=0 harmonics the display already de-embedded. */
    private static final double[] UNIT_RESPONSE = { 1.0, 0.0 };

    /** The chain response product {@code g} is de-embedded against on the way
     *  out: the loaded {@code .frc} at its frequency, or unity for a b=0
     *  harmonic the display already de-embedded.  The single definition of that
     *  convention - the conversions, the plant measurement and any record of
     *  the response all read it here, so none of them can drift from the
     *  others. */
    public double[] deEmbedResponse(int g, double freqHz, DoubleFunction<double[]> calResponseAt) {
        return coefB[g] == 0 ? UNIT_RESPONSE : calResponseAt.apply(freqHz);
    }

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
     *  dual-tone compensation quad - applying HERE (on the values reaching the
     *  DDS): the {@code .frc} de-embed (÷H, −argH) for every b≠0 product (b=0
     *  harmonics took the display de-embed already -> unit response), the
     *  ADC-dBFS -> Vrms conversion, and the division by the DAC F1 fundamental.
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
            double[] z   = correctedPhasor(g);                            // ÷ measured plant G(f_p)
            double[] cal = deEmbedResponse(g, freqHz[g], calResponseAt);
            double magDe = Math.hypot(z[0], z[1]) / (cal[0] > 0.0 ? cal[0] : 1.0);
            double vP    = magDe * adcFsVoltageRms;                       // ADC dBFS -> absolute Vrms
            amp[i] = dacFundamentalVrms > 0.0 ? vP / dacFundamentalVrms : 0.0;   // ratio to DAC F1
            phi[i] = Math.atan2(z[1], z[0]) - cal[1];
            a[i]   = coefA[g];
            b[i]   = coefB[g];
            i++;
        }
        return new GeneratorCorrections(amp, a, b, phi);
    }

    /** Deep copy - lets the wizard snapshot the best-THD round's accumulator
     *  while the loop keeps updating the live one.  The plant travels with the
     *  copy: the retained snapshot is what gets saved, and it must convert to
     *  the generator exactly as the live accumulator did. */
    public IntermodCompensation copy() {
        return new IntermodCompensation(coefA, coefB, accRe, accIm, freqHz, plantRe, plantIm);
    }

    /** Full-array constructor used only by {@link #copy()}. */
    private IntermodCompensation(int[] coefA, int[] coefB,
                                 double[] accRe, double[] accIm, double[] freqHz,
                                 double[] plantRe, double[] plantIm) {
        this.coefA   = coefA.clone();
        this.coefB   = coefB.clone();
        this.accRe   = accRe.clone();
        this.accIm   = accIm.clone();
        this.freqHz  = freqHz.clone();
        this.plantRe = plantRe.clone();
        this.plantIm = plantIm.clone();
    }

    /**
     * Writes a dual-tone {@code applied_compensation} CSV.  Mirrors the
     * single-tone format ({@link HarmonicCompensation#writeCsv}) but the
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
            // amplitude_dbv column carries the de-embedded ABSOLUTE dBV.  Same
            // de-embed as toGeneratorCorrections.
            pw.println("a;b;frequency_hz;amplitude_dbv;amplitude_pct;phase_deg;re;im");
            for (int g = 0; g < accRe.length; g++) {
                if (accRe[g] == 0.0 && accIm[g] == 0.0) continue;
                // Same de-embed as toGeneratorCorrections, and the same plant
                // division - a saved file must reproduce what the generator
                // was actually running.
                double[] z   = correctedPhasor(g);
                double[] cal = deEmbedResponse(g, freqHz[g], calResponseAt);
                double magDe = Math.hypot(z[0], z[1]) / (cal[0] > 0.0 ? cal[0] : 1.0);
                double vP    = magDe * adcFsVoltageRms;
                double amp   = dacFundamentalVrms > 0.0 ? vP / dacFundamentalVrms : 0.0;
                double phase = Math.atan2(z[1], z[0]) - cal[1];
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
