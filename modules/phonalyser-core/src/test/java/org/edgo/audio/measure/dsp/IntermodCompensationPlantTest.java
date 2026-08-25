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

import java.util.Arrays;
import java.util.function.DoubleFunction;

import org.edgo.audio.measure.dsp.IntermodCompensation.GeneratorCorrections;
import org.edgo.audio.measure.dsp.IntermodCompensation.ProductPhasors;
import org.edgo.audio.measure.fft.FftResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dual-tone plant probe: a pure delay planted across the whole
 * {@code a*f1 + b*f2} product grid must come back out of
 * {@link IntermodCompensation#measurePlant} product by product, an accumulator
 * without a plant must convert exactly as it did before one existed, and a
 * product arriving at the noise floor must leave the identity behind.
 */
class IntermodCompensationPlantTest {

    private static final int    MAX_HARMONICS = 4;         // a small but complete grid
    private static final double F1_HZ         = 1000.0;
    private static final double F2_HZ         = 1100.0;
    /** Probe amplitude ratio to the DAC F1 tone (-60 dB). */
    private static final double PROBE_AMP     = 1.0e-3;
    private static final double PHASE_STEP    = Math.PI / 4.0;
    private static final double CLEARANCE_DB  = 20.0;
    /** The excess transport delay planted in the synthetic chain: every
     *  product's transfer is turned by 2*pi*f*D at its OWN frequency. */
    private static final double DELAY_S       = 34.6e-6;
    /** Bin width of the synthetic spectrum - 1 Hz keeps bin index = frequency. */
    private static final double BIN_HZ        = 1.0;
    private static final int    SPECTRUM_BINS = 8192;
    /** Order of the de-rotated product grid the analyzer publishes,
     *  {@code max(5, harmonicCount + 1)} as it computes it - the comb is far
     *  wider than the accumulator's own grid and in its own order, so a slot
     *  can only be found by its {@code (a, b)} coefficients. */
    private static final int    PRODUCT_ORDER = Math.max(5, MAX_HARMONICS + 1);

    /** A flat, transparent chain - the probe math must not need a real one. */
    private final DoubleFunction<double[]> unitChain = f -> new double[]{ 1.0, 0.0 };

    @Test
    void plantedDelayIsRecoveredAtEveryProductFrequency() {
        IntermodCompensation comp = new IntermodCompensation(MAX_HARMONICS);
        GeneratorCorrections command = comp.probeCommand(PROBE_AMP, PHASE_STEP);
        int n = command.ampRatios().length;

        double[] baseRe  = new double[n];
        double[] baseIm  = new double[n];
        double[] probeRe = new double[n];
        double[] probeIm = new double[n];
        for (int g = 0; g < n; g++) {
            double fp = command.coefA()[g] * F1_HZ + command.coefB()[g] * F2_HZ;
            baseRe[g] = 2.0e-5;                            // the DUT's own product
            baseIm[g] = -1.0e-5;
            // The chain answers the commanded partial with the planted delay.
            // With a unit chain and a unit fundamental level the expected
            // arrival is -c, so the measured arrival is -c turned by 2*pi*f*D.
            double turn = 2.0 * Math.PI * fp * DELAY_S;
            double cRe  = PROBE_AMP * Math.cos(command.phiInits()[g]);
            double cIm  = PROBE_AMP * Math.sin(command.phiInits()[g]);
            probeRe[g] = baseRe[g] - (cRe * Math.cos(turn) - cIm * Math.sin(turn));
            probeIm[g] = baseIm[g] - (cRe * Math.sin(turn) + cIm * Math.cos(turn));
        }

        ProductPhasors plant = comp.measurePlant(frame(baseRe, baseIm, -200.0),
                frame(probeRe, probeIm, -200.0), command, unitChain, CLEARANCE_DB);

        for (int g = 0; g < n; g++) {
            double fp   = plant.coefA()[g] * F1_HZ + plant.coefB()[g] * F2_HZ;
            double turn = 2.0 * Math.PI * fp * DELAY_S;
            // Compared as a complex difference, so the wrap of the higher
            // products cannot make an exact answer look wrong.
            double wantRe = Math.cos(turn);
            double wantIm = Math.sin(turn);
            assertEquals(0.0, Math.hypot(plant.re()[g] - wantRe, plant.im()[g] - wantIm), 1e-9,
                    "product (" + plant.coefA()[g] + "," + plant.coefB()[g] + ") at " + fp + " Hz");
        }
    }

    /**
     * The same planted delay, but read out of frames shaped the way the
     * ANALYZER shapes them - the full de-rotated product comb, in the
     * analyzer's own order, with the raw phasors stamped by
     * {@link FftResult#captureRawPeaks()} itself.  The probe must find every
     * MIXED ({@code b != 0}) product there, exactly as the accumulator finds it
     * on a live round; a mixed product that silently drops out keeps the
     * identity plant and its amplitude error is never corrected.
     */
    @Test
    void mixedProductsAreMeasuredOnAnAnalyzerShapedFrame() {
        IntermodCompensation comp = new IntermodCompensation(MAX_HARMONICS);
        GeneratorCorrections command = comp.probeCommand(PROBE_AMP, PHASE_STEP);
        int n = command.ampRatios().length;

        double[] baseRe  = new double[n];
        double[] baseIm  = new double[n];
        double[] probeRe = new double[n];
        double[] probeIm = new double[n];
        for (int g = 0; g < n; g++) {
            double fp   = command.coefA()[g] * F1_HZ + command.coefB()[g] * F2_HZ;
            double turn = 2.0 * Math.PI * fp * DELAY_S;
            double cRe  = PROBE_AMP * Math.cos(command.phiInits()[g]);
            double cIm  = PROBE_AMP * Math.sin(command.phiInits()[g]);
            baseRe[g]  = 2.0e-5;
            baseIm[g]  = -1.0e-5;
            probeRe[g] = baseRe[g] - (cRe * Math.cos(turn) - cIm * Math.sin(turn));
            probeIm[g] = baseIm[g] - (cRe * Math.sin(turn) + cIm * Math.cos(turn));
        }

        ProductPhasors plant = comp.measurePlant(analyzerFrame(baseRe, baseIm, -200.0),
                analyzerFrame(probeRe, probeIm, -200.0), command, unitChain, CLEARANCE_DB);

        int mixed = 0;
        for (int g = 0; g < n; g++) {
            int a = plant.coefA()[g];
            int b = plant.coefB()[g];
            String at = "product (" + a + "," + b + ")";
            double fp = a * F1_HZ + b * F2_HZ;
            assertTrue(plant.hz()[g] > 0.0, at + " was read out of the frame");
            double turn = 2.0 * Math.PI * fp * DELAY_S;
            assertEquals(0.0, Math.hypot(plant.re()[g] - Math.cos(turn),
                    plant.im()[g] - Math.sin(turn)), 1e-9, at + " at " + fp + " Hz");
            if (b != 0) mixed++;
        }
        assertTrue(mixed >= 11, "the grid carries its mixed products: " + mixed);
    }

    /**
     * The frame a probe must not measure from: one the analyzer published
     * without engaging its per-product de-rotation, so it carries no product
     * grid at all.  Read out of such a frame the plant comes back HALF
     * measured - every {@code b = 0} harmonic present (they are read from the
     * harmonic table, which is always there), every MIXED product left on the
     * identity - and nothing in the result marks the loss, so the whole
     * {@code a*f1 + b*f2} comb stays uncorrected while the probe looks like it
     * succeeded.  The accumulator must therefore be able to name such a frame
     * before it is used.
     */
    @Test
    void aFrameWithoutTheDeRotatedGridIsNotAPlantSource() {
        IntermodCompensation comp = new IntermodCompensation(MAX_HARMONICS);
        GeneratorCorrections command = comp.probeCommand(PROBE_AMP, PHASE_STEP);
        int n = command.ampRatios().length;

        double[] baseRe  = new double[n];
        double[] baseIm  = new double[n];
        double[] probeRe = new double[n];
        double[] probeIm = new double[n];
        for (int g = 0; g < n; g++) {
            baseRe[g]  = 2.0e-5;
            baseIm[g]  = -1.0e-5;
            probeRe[g] = baseRe[g] - PROBE_AMP * Math.cos(command.phiInits()[g]);
            probeIm[g] = baseIm[g] - PROBE_AMP * Math.sin(command.phiInits()[g]);
        }

        assertTrue(comp.carriesProducts(analyzerFrame(baseRe, baseIm, -200.0)),
                "a de-rotated frame carries its mixed products");
        assertFalse(comp.carriesProducts(withoutProductGrid(analyzerFrame(baseRe, baseIm, -200.0))),
                "a frame the analyzer did not de-rotate carries none");

        // The fingerprint the bench read: b = 0 measured, every mixed product
        // silently unmeasured.
        ProductPhasors half = comp.measurePlant(
                withoutProductGrid(analyzerFrame(baseRe, baseIm, -200.0)),
                withoutProductGrid(analyzerFrame(probeRe, probeIm, -200.0)),
                command, unitChain, CLEARANCE_DB);
        for (int g = 0; g < n; g++) {
            String at = "product (" + half.coefA()[g] + "," + half.coefB()[g] + ")";
            if (half.coefB()[g] == 0) {
                assertTrue(Double.isFinite(half.re()[g]), at + " still measured");
            } else {
                assertTrue(Double.isNaN(half.re()[g]), at + " unmeasured without the grid");
            }
        }
    }

    @Test
    void unsetPlantLeavesTheCorrectionUntouched() {
        IntermodCompensation plain   = new IntermodCompensation(MAX_HARMONICS);
        IntermodCompensation withNaN = new IntermodCompensation(MAX_HARMONICS);
        int n = plain.probeCommand(PROBE_AMP, PHASE_STEP).ampRatios().length;
        double[] re = new double[n];
        double[] im = new double[n];
        for (int g = 0; g < n; g++) {
            re[g] =  3.0e-5 + g * 1.0e-6;
            im[g] = -2.0e-5 + g * 5.0e-7;
        }
        plain.accumulate(frame(re, im, -200.0), F1_HZ, F2_HZ, 1.0, 0.0, 0.0);
        withNaN.accumulate(frame(re, im, -200.0), F1_HZ, F2_HZ, 1.0, 0.0, 0.0);

        double[] nan = new double[n];
        Arrays.fill(nan, Double.NaN);
        withNaN.setPlant(new ProductPhasors(new int[n], new int[n], nan, nan, new double[n]));

        GeneratorCorrections a = plain.toGeneratorCorrections(unitChain, 1.0, 1.0);
        GeneratorCorrections b = withNaN.toGeneratorCorrections(unitChain, 1.0, 1.0);
        assertEquals(a.ampRatios().length, b.ampRatios().length);
        assertTrue(a.ampRatios().length > 0, "the synthetic frame fed every product");
        for (int i = 0; i < a.ampRatios().length; i++) {
            // Bit-exact: an unmeasured plant divides by 1 + 0j.
            assertEquals(a.ampRatios()[i], b.ampRatios()[i]);
            assertEquals(a.phiInits()[i],  b.phiInits()[i]);
            assertEquals(a.coefA()[i],     b.coefA()[i]);
            assertEquals(a.coefB()[i],     b.coefB()[i]);
        }
    }

    @Test
    void productAtTheNoiseFloorKeepsTheIdentityPlant() {
        IntermodCompensation comp = new IntermodCompensation(MAX_HARMONICS);
        GeneratorCorrections command = comp.probeCommand(PROBE_AMP, PHASE_STEP);
        int n = command.ampRatios().length;
        double[] baseRe  = new double[n];
        double[] baseIm  = new double[n];
        double[] probeRe = new double[n];
        double[] probeIm = new double[n];
        for (int g = 0; g < n; g++) {
            baseRe[g]  = 2.0e-5;
            probeRe[g] = baseRe[g] - PROBE_AMP * Math.cos(command.phiInits()[g]);
            probeIm[g] = baseIm[g] - PROBE_AMP * Math.sin(command.phiInits()[g]);
        }

        // Floor at -70 dBFS: the -60 dBFS arrival stands only 10 dB above it,
        // short of the 20 dB clearance, so every product is refused.
        ProductPhasors refused = comp.measurePlant(frame(baseRe, baseIm, -70.0),
                frame(probeRe, probeIm, -70.0), command, unitChain, CLEARANCE_DB);
        for (int g = 0; g < n; g++) {
            assertTrue(Double.isNaN(refused.re()[g]), "product " + g + " refused below the clearance");
        }
        comp.setPlant(refused);                            // identity survives

        // The same arrival against a floor at -100 dBFS clears it.
        ProductPhasors accepted = comp.measurePlant(frame(baseRe, baseIm, -100.0),
                frame(probeRe, probeIm, -100.0), command, unitChain, CLEARANCE_DB);
        for (int g = 0; g < n; g++) {
            assertTrue(Double.isFinite(accepted.re()[g]), "product " + g + " accepted above the clearance");
        }
    }

    /**
     * A frame whose products carry exactly the given phasors in the
     * accumulator's frame.  Both tones sit at -90 degrees with unit magnitude,
     * so every de-rotation term {@code a*(phi1+pi/2)+b*(phi2+pi/2)} vanishes
     * and each product's raw phase IS its accumulator-frame phase; the F1
     * harmonics (b = 0) are written into the harmonic table the way the
     * accumulator reads them, every other product into the analyzer's
     * de-rotated product grid - here a MINIMAL grid holding exactly the
     * accumulator's own slots.
     */
    private FftResult frame(double[] re, double[] im, double noiseFloorDbFs) {
        GeneratorCorrections slots = new IntermodCompensation(MAX_HARMONICS)
                .probeCommand(1.0, 0.0);
        int n = slots.ampRatios().length;

        FftResult r = toneFrame(noiseFloorDbFs);
        // Every b != 0 slot reads the analyzer's de-rotated product grid, and
        // F2 itself - the (0,1) product - supplies the second reference phase.
        int products = 1;                                  // entry 0 holds (0,1) = F2
        for (int g = 0; g < n; g++) {
            if (slots.coefB()[g] != 0) products++;
        }
        r.imdProductA   = new int[products];
        r.imdProductB   = new int[products];
        r.imdProductBin = new int[products];
        r.rawPeakRe     = new double[products];
        r.rawPeakIm     = new double[products];
        r.imdProductA[0]   = 0;
        r.imdProductB[0]   = 1;
        r.imdProductBin[0] = (int) (F2_HZ / BIN_HZ);
        r.rawPeakRe[0]     = 0.0;
        r.rawPeakIm[0]     = -1.0;                         // F2 at -90 degrees, unit magnitude
        int p = 1;
        for (int g = 0; g < n; g++) {
            int a = slots.coefA()[g];
            int b = slots.coefB()[g];
            if (b == 0) {
                plantHarmonic(r, a, re[g], im[g]);
            } else {
                r.imdProductA[p]   = a;
                r.imdProductB[p]   = b;
                r.imdProductBin[p] = (int) Math.round((a * F1_HZ + b * F2_HZ) / BIN_HZ);
                r.rawPeakRe[p]     = re[g];
                r.rawPeakIm[p]     = im[g];
                p++;
            }
        }
        return r;
    }

    /**
     * The same frame built the way the ANALYZER builds one: the published grid
     * is the FULL de-rotated comb - every {@code b != 0} pair within
     * {@link #PRODUCT_ORDER} that lands in band, in the analyzer's own emission
     * order, so it is both wider than the accumulator's grid and differently
     * ordered - the products are planted into the spectrum at their bins, and
     * the raw peak phasors are stamped by {@link FftResult#captureRawPeaks()}
     * itself.  A probe reading this frame therefore takes exactly the lookup a
     * live round takes.
     */
    private FftResult analyzerFrame(double[] re, double[] im, double noiseFloorDbFs) {
        GeneratorCorrections slots = new IntermodCompensation(MAX_HARMONICS)
                .probeCommand(1.0, 0.0);
        int n = slots.ampRatios().length;

        FftResult r = toneFrame(noiseFloorDbFs);
        int   cap = (2 * PRODUCT_ORDER + 1) * (2 * PRODUCT_ORDER + 1);
        int[] pa  = new int[cap];
        int[] pb  = new int[cap];
        int[] pk  = new int[cap];
        int   cnt = 0;
        for (int a = -PRODUCT_ORDER; a <= PRODUCT_ORDER; a++) {
            for (int b = -PRODUCT_ORDER; b <= PRODUCT_ORDER; b++) {
                if (b == 0 || Math.abs(a) + Math.abs(b) > PRODUCT_ORDER) continue;
                double kp = (a * F1_HZ + b * F2_HZ) / BIN_HZ;
                if (kp < 1.0 || kp >= SPECTRUM_BINS) continue;
                pa[cnt] = a;
                pb[cnt] = b;
                pk[cnt] = (int) Math.round(kp);
                cnt++;
            }
        }
        r.imdProductA   = Arrays.copyOf(pa, cnt);
        r.imdProductB   = Arrays.copyOf(pb, cnt);
        r.imdProductBin = Arrays.copyOf(pk, cnt);

        // Both tones at -90 degrees with unit magnitude, in the spectrum this
        // time: captureRawPeaks reads its scale factor off the fundamental bin.
        r.re[r.fundamentalBin] = 0.0;
        r.im[r.fundamentalBin] = -1.0;
        r.re[(int) (F2_HZ / BIN_HZ)] = 0.0;
        r.im[(int) (F2_HZ / BIN_HZ)] = -1.0;
        for (int g = 0; g < n; g++) {
            int a = slots.coefA()[g];
            int b = slots.coefB()[g];
            if (b == 0) {
                plantHarmonic(r, a, re[g], im[g]);
            } else {
                int bin = (int) Math.round((a * F1_HZ + b * F2_HZ) / BIN_HZ);
                r.re[bin] = re[g];
                r.im[bin] = im[g];
            }
        }
        r.captureRawPeaks();                 // the analyzer's own peak stamping
        return r;
    }

    /** The same frame as the analyzer publishes on a tick where it did NOT
     *  engage the per-product de-rotation: no product grid at all, and the raw
     *  peak phasors consequently stamped over the HARMONIC bins instead. */
    private FftResult withoutProductGrid(FftResult r) {
        r.imdProductA   = new int[0];
        r.imdProductB   = new int[0];
        r.imdProductBin = new int[0];
        r.captureRawPeaks();
        return r;
    }

    /** The two-tone skeleton both frame builders start from: the tones, the
     *  spectrum arrays and an empty harmonic table.  Both tones sit at -90
     *  degrees with unit magnitude. */
    private FftResult toneFrame(double noiseFloorDbFs) {
        FftResult r = new FftResult();
        r.freqResolution        = BIN_HZ;
        r.fundamentalBin        = (int) (F1_HZ / BIN_HZ);
        r.fundamentalHzRefined  = F1_HZ;
        r.fundamental2HzRefined = F2_HZ;
        r.fundamentalDbFs       = 0.0;
        r.avgNoiseFloorDbFs     = noiseFloorDbFs;
        r.re = new double[SPECTRUM_BINS];
        r.im = new double[SPECTRUM_BINS];
        r.rawFundRe = 0.0;
        r.rawFundIm = -1.0;

        // The b = 0 slots read the harmonic table: magnitude from the raw
        // harmonic level, phase from the spectrum bin.
        r.harmonicCount = MAX_HARMONICS;
        r.harmonicBins  = new int[MAX_HARMONICS];
        r.harmonicHz    = new double[MAX_HARMONICS];
        r.harmonicDbFs  = new double[MAX_HARMONICS];
        for (int h = 0; h < MAX_HARMONICS; h++) {
            r.harmonicBins[h] = (int) ((h + 2) * F1_HZ / BIN_HZ);
            r.harmonicHz[h]   = (h + 2) * F1_HZ;
            r.harmonicDbFs[h] = -300.0;
        }
        return r;
    }

    /** Plants one F1 harmonic the way the accumulator reads it back: the level
     *  in the harmonic table, the phase in the spectrum bin. */
    private void plantHarmonic(FftResult r, int harmonic, double re, double im) {
        int    idx = harmonic - 2;
        int    bin = r.harmonicBins[idx];
        double mag = Math.hypot(re, im);
        double ang = Math.atan2(im, re);
        r.harmonicDbFs[idx] = mag > 0.0 ? 20.0 * Math.log10(mag) : -300.0;
        r.re[bin] = Math.cos(ang);
        r.im[bin] = Math.sin(ang);
    }
}
