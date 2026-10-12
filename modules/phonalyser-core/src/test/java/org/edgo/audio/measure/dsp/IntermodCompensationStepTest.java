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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.DoubleFunction;

import org.edgo.audio.measure.dsp.IntermodCompensation.GeneratorCorrections;
import org.edgo.audio.measure.dsp.IntermodCompensation.ProductPhasors;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.fft.FftResult.LineState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-product apply of the intermodulation accumulator: one product's
 * measured residual moves that product's correction alone, and the entry it
 * returns for the generator is the one the whole-set conversion lists.  Every
 * product - the F1 harmonics included - is read as its raw bin phasor and
 * de-embedded by the same conversion, and the whole-set step moves only a
 * product that stands the apply gate over its own local band top.
 */
class IntermodCompensationStepTest {

    private static final int    HARMONICS   = 5;
    private static final double F1_HZ       = 1000.0;
    private static final double F2_HZ       = 1100.0;
    /** Every grid product starts from a correction of this level, each at
     *  its own phase, so no two entries coincide. */
    private static final double SEED_LEVEL  = 1.0e-6;
    private static final double SEED_RAD    = 0.7;
    /** The applied product: 2 F1 - F2, a b != 0 product the chain response
     *  de-embeds. */
    private static final int    LINE_A      = 2;
    private static final int    LINE_B      = -1;
    private static final double LINE_RE     = 3.0e-7;
    private static final double LINE_IM     = -2.0e-7;
    /** 2 F1 - F2 read a quarter hertz off its nominal frequency, so the
     *  product's own frequency write is visible beside the others'. */
    private static final double LINE_HZ     = LINE_A * F1_HZ + LINE_B * F2_HZ + 0.25;
    /** The F1 harmonic 3 F1, a b = 0 product, de-embedded like every other
     *  product. */
    private static final int    HARM_A      = 3;
    /** A pair the grid does not hold: order 8, over the grid's cap. */
    private static final int    OFF_GRID    = 4;
    /** A chain response that changes with frequency, and levels away from
     *  unity, so the conversion's de-embed and scaling are exercised. */
    private static final double CAL_MAG_BASE   = 0.5;
    private static final double CAL_MAG_PER_HZ = 1.0e-4;
    private static final double CAL_RAD_PER_HZ = 2.0e-4;
    private static final double ADC_FS_VRMS    = 1.7;
    private static final double DAC_F1_VRMS    = 0.6;
    /** The gate result: F1 at 1 Hz per bin, both tones at phase 0, four
     *  products at PRODUCT_DBFS, index-parallel in GATE_A / GATE_B -
     *  2 F1 - F2 under the gate, F1 + F2 over it, 2 F2 - F1 with no line
     *  state, 2 F1 + F2 with a line state whose band top is not measured. */
    private static final double BIN_HZ        = 1.0;
    private static final int    SPECTRUM_BINS = 8192;
    private static final double PRODUCT_DBFS  = -80.0;
    private static final double UNDER_GATE_DB = 3.0;
    private static final double OVER_GATE_DB  = 8.0;
    private static final int    LOCAL_FRAMES  = 10;
    private static final int[]  GATE_A        = { 2, 1, -1, 2 };
    private static final int[]  GATE_B        = { -1, 1, 2, 1 };
    private static final int    UNDER         = 0;
    private static final int    OVER          = 1;
    private static final int    NO_STATE      = 2;
    private static final int    NAN_TOP       = 3;
    /** The F1-harmonic reading: H2's bin at PRODUCT_DBFS and this phase, the
     *  harmonic table carrying a different level for it. */
    private static final double BIN_PHASE_RAD = 0.9;
    private static final double TABLE_DBFS    = -60.0;

    /** The chain response at {@code hz} as {@code [magLin, phaseRad]}. */
    private double[] chain(double hz) {
        return new double[] { CAL_MAG_BASE + CAL_MAG_PER_HZ * hz, CAL_RAD_PER_HZ * hz };
    }

    /** Grid slot {@code g}'s seed correction, real part. */
    private double seedRe(int g) {
        return SEED_LEVEL * Math.cos(SEED_RAD * (g + 1));
    }

    /** Grid slot {@code g}'s seed correction, imaginary part. */
    private double seedIm(int g) {
        return SEED_LEVEL * Math.sin(SEED_RAD * (g + 1));
    }

    /** An accumulator whose every grid product holds a correction, each
     *  product at its nominal frequency. */
    private IntermodCompensation seeded() {
        IntermodCompensation comp = new IntermodCompensation(HARMONICS);
        ProductPhasors grid = comp.grid(F1_HZ, F2_HZ);
        for (int g = 0; g < grid.hz().length; g++) {
            comp.applyLine(grid.coefA()[g], grid.coefB()[g], seedRe(g), seedIm(g), grid.hz()[g],
                    this::chain, ADC_FS_VRMS, DAC_F1_VRMS);
        }
        return comp;
    }

    /** The conversion of a correction {@code (re, im)} stored at {@code hz},
     *  written out in the order the class computes it, as
     *  {@code [amplitudeRatio, initialPhase]}. */
    private double[] converted(double re, double im, double hz) {
        double[] cal = chain(hz);
        return new double[] { Math.hypot(re, im) / cal[0] * ADC_FS_VRMS / DAC_F1_VRMS,
                              Math.atan2(im, re) - cal[1] };
    }

    /** Entry index of product {@code (a, b)} in {@code gc}. */
    private int entryOf(GeneratorCorrections gc, int a, int b) {
        int i = 0;
        while (gc.coefA()[i] != a || gc.coefB()[i] != b) i++;
        return i;
    }

    @Test
    void applyingOneProductLeavesEveryOtherProductBitIdentical() {
        IntermodCompensation comp = seeded();
        List<Double> hzBefore = new ArrayList<>();
        GeneratorCorrections before = comp.toGeneratorCorrections(asking(hzBefore), ADC_FS_VRMS, DAC_F1_VRMS);

        comp.applyLine(LINE_A, LINE_B, LINE_RE, LINE_IM, LINE_HZ, this::chain, ADC_FS_VRMS, DAC_F1_VRMS);

        List<Double> hzAfter = new ArrayList<>();
        GeneratorCorrections after = comp.toGeneratorCorrections(asking(hzAfter), ADC_FS_VRMS, DAC_F1_VRMS);
        int products = comp.grid(F1_HZ, F2_HZ).hz().length;
        assertEquals(products, before.coefA().length, "the set lists every grid product");
        assertEquals(products, after.coefA().length, "the set lists every grid product");
        // The chain is read once per entry, in entry order, at the product's
        // stored frequency - the F1 harmonics included.
        assertEquals(products, hzBefore.size(), "one chain read per product");
        assertEquals(products, hzAfter.size(), "one chain read per product");
        int line = entryOf(after, LINE_A, LINE_B);
        for (int i = 0; i < products; i++) {
            String name = "(" + after.coefA()[i] + ", " + after.coefB()[i] + ")";
            assertEquals(before.coefA()[i], after.coefA()[i], name + " a");
            assertEquals(before.coefB()[i], after.coefB()[i], name + " b");
            if (i == line) continue;
            assertEquals(before.ampRatios()[i], after.ampRatios()[i], name + " amplitude ratio");
            assertEquals(before.phiInits()[i],  after.phiInits()[i],  name + " initial phase");
            assertEquals(hzBefore.get(i), hzAfter.get(i), name + " frequency");
        }
        // The applied product holds its seed plus the residual at the full
        // step, stored at its new frequency.  Every grid product is listed, so
        // its entry index is its grid slot.
        double[] expected = converted(seedRe(line) + LINE_RE, seedIm(line) + LINE_IM, LINE_HZ);
        assertEquals(LINE_HZ, hzAfter.get(line), "the applied product's frequency");
        assertEquals(expected[0], after.ampRatios()[line], "the applied product's amplitude ratio");
        assertEquals(expected[1], after.phiInits()[line],  "the applied product's initial phase");
        assertNotEquals(before.ampRatios()[line], after.ampRatios()[line], "the applied product moved");
    }

    @Test
    void anF1HarmonicAndAnIntermodProductTakeTheSameConversion() {
        IntermodCompensation comp = new IntermodCompensation(HARMONICS);
        double harmHz = HARM_A * F1_HZ;
        GeneratorCorrections harmonic = comp.applyLine(HARM_A, 0, LINE_RE, LINE_IM, harmHz,
                this::chain, ADC_FS_VRMS, DAC_F1_VRMS);
        GeneratorCorrections product = comp.applyLine(LINE_A, LINE_B, LINE_RE, LINE_IM, LINE_HZ,
                this::chain, ADC_FS_VRMS, DAC_F1_VRMS);

        double[] harmExpected    = converted(LINE_RE, LINE_IM, harmHz);
        double[] productExpected = converted(LINE_RE, LINE_IM, LINE_HZ);
        assertEquals(harmExpected[0],    harmonic.ampRatios()[0], "3 F1 de-embedded by the chain, amplitude");
        assertEquals(harmExpected[1],    harmonic.phiInits()[0],  "3 F1 de-embedded by the chain, phase");
        assertEquals(productExpected[0], product.ampRatios()[0],  "2 F1 - F2 de-embedded by the chain, amplitude");
        assertEquals(productExpected[1], product.phiInits()[0],   "2 F1 - F2 de-embedded by the chain, phase");
    }

    @Test
    void aPairOffTheGridIsRefused() {
        IntermodCompensation comp = new IntermodCompensation(HARMONICS);
        assertThrows(IllegalArgumentException.class, () -> comp.applyLine(OFF_GRID, OFF_GRID,
                LINE_RE, LINE_IM, LINE_HZ, this::chain, ADC_FS_VRMS, DAC_F1_VRMS));
        assertEquals(0, comp.toGeneratorCorrections(this::chain, ADC_FS_VRMS, DAC_F1_VRMS).coefA().length,
                "a refused pair moves nothing");
    }

    @Test
    void theLineEntryIsTheEntryTheWholeSetLists() {
        IntermodCompensation comp = seeded();
        GeneratorCorrections product = comp.applyLine(LINE_A, LINE_B, LINE_RE, LINE_IM, LINE_HZ,
                this::chain, ADC_FS_VRMS, DAC_F1_VRMS);
        GeneratorCorrections harmonic = comp.applyLine(HARM_A, 0, LINE_RE, LINE_IM, HARM_A * F1_HZ,
                this::chain, ADC_FS_VRMS, DAC_F1_VRMS);
        GeneratorCorrections set = comp.toGeneratorCorrections(this::chain, ADC_FS_VRMS, DAC_F1_VRMS);

        assertSameEntry(set, product, LINE_A, LINE_B);
        assertSameEntry(set, harmonic, HARM_A, 0);
    }

    /** The one entry of {@code line} equals product {@code (a, b)}'s entry in
     *  {@code set}, bit for bit. */
    private void assertSameEntry(GeneratorCorrections set, GeneratorCorrections line, int a, int b) {
        String name = "(" + a + ", " + b + ")";
        assertEquals(1, line.coefA().length, name + ": one line, one entry");
        assertEquals(a, line.coefA()[0], name + " a");
        assertEquals(b, line.coefB()[0], name + " b");
        int i = entryOf(set, a, b);
        assertEquals(set.ampRatios()[i], line.ampRatios()[0], name + " amplitude ratio");
        assertEquals(set.phiInits()[i],  line.phiInits()[0],  name + " initial phase");
    }

    /** The chain response, each frequency it is read at recorded in
     *  {@code asked}. */
    private DoubleFunction<double[]> asking(List<Double> asked) {
        return f -> {
            asked.add(f);
            return chain(f);
        };
    }

    /** The dual-tone result of the gate tests, its line states stamped for
     *  every gate product except NO_STATE. */
    private FftResult gateResult() {
        FftResult r = new FftResult();
        r.freqResolution   = BIN_HZ;
        r.fundamentalBin   = (int) Math.round(F1_HZ / BIN_HZ);
        r.dacFundPhaseRad  = 0.0;
        r.dacFund2PhaseRad = 0.0;
        r.re               = new double[SPECTRUM_BINS];
        r.im               = new double[SPECTRUM_BINS];
        r.imdProductA      = GATE_A.clone();
        r.imdProductB      = GATE_B.clone();
        r.rawPeakRe        = new double[GATE_A.length];
        r.rawPeakIm        = new double[GATE_A.length];
        Arrays.fill(r.rawPeakRe, Math.pow(10.0, PRODUCT_DBFS / 20.0));
        r.lineStates = List.of(
                state(UNDER, PRODUCT_DBFS - UNDER_GATE_DB),
                state(OVER, PRODUCT_DBFS - OVER_GATE_DB),
                state(NAN_TOP, Double.NaN));
        return r;
    }

    /** Gate product {@code p}'s line state with the given local band top. */
    private LineState state(int p, double bandTopDbFs) {
        int bin = (int) Math.round((GATE_A[p] * F1_HZ + GATE_B[p] * F2_HZ) / BIN_HZ);
        return new LineState(GATE_A[p], GATE_B[p], bin, LOCAL_FRAMES, false, bandTopDbFs);
    }

    /** Grid slot of gate product {@code p} in {@code z}. */
    private int slotOf(ProductPhasors z, int p) {
        int g = 0;
        while (z.coefA()[g] != GATE_A[p] || z.coefB()[g] != GATE_B[p]) g++;
        return g;
    }

    @Test
    void theClearanceIsTheRawLevelOverTheLinesLocalBandTop() {
        ProductPhasors z = new IntermodCompensation(HARMONICS)
                .measurePhasors(gateResult(), F1_HZ, F2_HZ, 0.0, 0.0);
        for (int p = UNDER; p <= NAN_TOP; p++) {
            assertTrue(z.hz()[slotOf(z, p)] > 0.0, "gate product " + p + " is measured");
        }
        assertEquals(UNDER_GATE_DB, z.clearanceDb()[slotOf(z, UNDER)], 1e-9, "2 F1 - F2");
        assertEquals(OVER_GATE_DB,  z.clearanceDb()[slotOf(z, OVER)],  1e-9, "F1 + F2");
        assertTrue(Double.isNaN(z.clearanceDb()[slotOf(z, NO_STATE)]), "no line state reads NaN");
        assertTrue(Double.isNaN(z.clearanceDb()[slotOf(z, NAN_TOP)]), "no measured band top reads NaN");
    }

    @Test
    void onlyAProductOverTheGateMovesItsCorrection() {
        IntermodCompensation comp = new IntermodCompensation(HARMONICS);
        comp.accumulate(gateResult(), F1_HZ, F2_HZ, 1.0, 0.0, 0.0);
        GeneratorCorrections gc = comp.toGeneratorCorrections(this::chain, ADC_FS_VRMS, DAC_F1_VRMS);
        assertEquals(1, gc.coefA().length, "under the gate and without a clearance, a product stays");
        assertEquals(GATE_A[OVER], gc.coefA()[0], "F1 + F2 moved, a");
        assertEquals(GATE_B[OVER], gc.coefB()[0], "F1 + F2 moved, b");
    }

    @Test
    void anF1HarmonicReadsItsRawBinPhasor() {
        FftResult r = new FftResult();
        r.freqResolution = BIN_HZ;
        r.fundamentalBin = (int) Math.round(F1_HZ / BIN_HZ);
        r.re             = new double[SPECTRUM_BINS];
        r.im             = new double[SPECTRUM_BINS];
        r.amplitudeDbFs  = new double[SPECTRUM_BINS];
        int bin = (int) Math.round(2 * F1_HZ / BIN_HZ);
        r.re[bin]            = Math.cos(BIN_PHASE_RAD);
        r.im[bin]            = Math.sin(BIN_PHASE_RAD);
        r.amplitudeDbFs[bin] = PRODUCT_DBFS;
        r.harmonicCount = 1;
        r.harmonicBins  = new int[] { bin };
        r.harmonicHz    = new double[] { 2 * F1_HZ };
        r.harmonicDbFs  = new double[] { TABLE_DBFS };

        IntermodCompensation comp = new IntermodCompensation(HARMONICS);
        ProductPhasors raw = comp.rawPhasors(r, F1_HZ, F2_HZ);
        int g = 0;
        while (raw.coefA()[g] != 2 || raw.coefB()[g] != 0) g++;
        assertEquals(Math.pow(10.0, PRODUCT_DBFS / 20.0), Math.hypot(raw.re()[g], raw.im()[g]), 1e-18,
                "2 F1 reads its bin's own level, not the harmonic table's");
        assertEquals(BIN_PHASE_RAD, Math.atan2(raw.im()[g], raw.re()[g]), 1e-12, "2 F1 reads its bin's raw phase");
    }
}
