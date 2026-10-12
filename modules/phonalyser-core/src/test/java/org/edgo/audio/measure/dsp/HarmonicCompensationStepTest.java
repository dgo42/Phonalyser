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

import org.edgo.audio.measure.dsp.HarmonicCompensation.GeneratorCorrections;
import org.edgo.audio.measure.dsp.HarmonicCompensation.HarmonicPhasors;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.fft.FftResult.LineState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The correction step of the harmonic accumulator: the plain inversion.  A
 * correction comes back at the analyser inverted, so a clear harmonic's
 * measured residual is added to the correction in force and cancels on the
 * next round; a harmonic under the apply gate moves nothing.
 */
class HarmonicCompensationStepTest {

    private static final int    HARMONICS = 8;          // H2..H9
    private static final double F0_HZ     = 1000.0;
    private static final double RAW_LEVEL = 1.0e-6;
    private static final double CLEAR_DB  = 40.0;       // well over the apply gate
    private static final double FLOOR_DB  = 2.0;        // under the apply gate
    private static final double STEP      = 1.0;
    /** The line one apply steps: H3, at index 1 of the class's H2-first set. */
    private static final int    LINE      = 1;
    private static final double LINE_RE   = 3.0e-7;
    private static final double LINE_IM   = -2.0e-7;
    /** H3 read a quarter hertz off its nominal frequency, so the line's own
     *  frequency write is visible beside the others'. */
    private static final double LINE_HZ   = (LINE + 2) * F0_HZ + 0.25;
    /** A chain response that changes with frequency, and levels away from
     *  unity, so the conversion's de-embed and scaling are exercised. */
    private static final double CAL_MAG_BASE   = 0.5;
    private static final double CAL_MAG_PER_HZ = 1.0e-4;
    private static final double CAL_RAD_PER_HZ = 2.0e-4;
    private static final double ADC_FS_VRMS    = 1.7;
    private static final double DAC_F0_VRMS    = 0.6;
    /** The clearance result: F0 at 1 Hz per bin, H2..H4 at HARMONIC_DBFS on
     *  a flat raw noise grass at GRASS_DBFS, so the noise band beside each
     *  harmonic tops out at the grass. */
    private static final double BIN_HZ         = 1.0;
    private static final double NENBW_BINS     = 1.5;
    private static final int    SPECTRUM_BINS  = 8192;
    private static final int    STAMPED        = 3;
    private static final double GRASS_DBFS     = -120.0;
    private static final double HARMONIC_DBFS  = -90.0;
    /** H2's own local band top stands this far under its level. */
    private static final double STATE_CLEAR_DB = 3.0;
    private static final int    H4_NUMBER      = 4;
    private static final int    LOCAL_FRAMES   = 10;
    /** The worker's result: the harmonic table holds the single frame just
     *  analysed, this far over the averaged peak phasor at the same bin. */
    private static final double FRAME_EXCESS_DB = 10.0;
    private static final double PEAK_RAD        = 0.4;

    private final double[] rawRe = new double[HARMONICS];
    private final double[] rawIm = new double[HARMONICS];

    HarmonicCompensationStepTest() {
        for (int h = 0; h < HARMONICS; h++) {
            double phase = 0.7 * (h + 2);
            rawRe[h] = RAW_LEVEL * Math.cos(phase);
            rawIm[h] = RAW_LEVEL * Math.sin(phase);
        }
    }

    /** The accumulated correction as the generator would run it, read back
     *  through the unit calibration and unit levels. */
    private double[][] applied(HarmonicCompensation comp) {
        double[][] acc = new double[HARMONICS][2];
        GeneratorCorrections gc = comp.toGeneratorCorrections(F0_HZ, f -> new double[] { 1.0, 0.0 }, 1.0, 1.0);
        for (int i = 0; i < gc.harmonicNumbers().length; i++) {
            int h = gc.harmonicNumbers()[i] - 2;
            acc[h][0] = gc.ampRatios()[i] * Math.cos(gc.phiInits()[i]);
            acc[h][1] = gc.ampRatios()[i] * Math.sin(gc.phiInits()[i]);
        }
        return acc;
    }

    /** What the analyser reads with the correction {@code acc} applied: the
     *  raw harmonic plus the correction, which the chain returns inverted. */
    private HarmonicPhasors measure(double[][] acc, double clearanceDb) {
        double[] re = new double[HARMONICS], im = new double[HARMONICS];
        double[] hz = new double[HARMONICS], clear = new double[HARMONICS];
        for (int h = 0; h < HARMONICS; h++) {
            re[h] = rawRe[h] - acc[h][0];
            im[h] = rawIm[h] - acc[h][1];
            hz[h] = (h + 2) * F0_HZ;
            clear[h] = clearanceDb;
        }
        return new HarmonicPhasors(re, im, hz, clear);
    }

    private double residual(HarmonicPhasors z, int h) {
        return Math.hypot(z.re()[h], z.im()[h]) / RAW_LEVEL;
    }

    @Test
    void theStepCopiesTheReadingIntoTheCorrection() {
        HarmonicCompensation comp = new HarmonicCompensation(HARMONICS);
        comp.accumulate(measure(new double[HARMONICS][2], CLEAR_DB), STEP);
        double[][] acc = applied(comp);
        for (int h = 0; h < HARMONICS; h++) {
            assertEquals(rawRe[h], acc[h][0], 1e-15, "H" + (h + 2) + " correction, real");
            assertEquals(rawIm[h], acc[h][1], 1e-15, "H" + (h + 2) + " correction, imaginary");
            double[] delta = comp.lastDelta(h);
            assertEquals(rawRe[h], delta[0], 1e-15, "H" + (h + 2) + " delta of the round, real");
            assertEquals(rawIm[h], delta[1], 1e-15, "H" + (h + 2) + " delta of the round, imaginary");
        }
    }

    @Test
    void theCorrectionCancelsTheHarmonicOnTheNextRound() {
        HarmonicCompensation comp = new HarmonicCompensation(HARMONICS);
        comp.accumulate(measure(new double[HARMONICS][2], CLEAR_DB), STEP);
        HarmonicPhasors round1 = measure(applied(comp), CLEAR_DB);
        for (int h = 0; h < HARMONICS; h++) {
            assertEquals(0.0, residual(round1, h), 1e-9, "H" + (h + 2) + " is cancelled");
        }
        // A residual left by the chain is added on top, the sum stays.
        comp.accumulate(round1, STEP);
        double[][] acc = applied(comp);
        for (int h = 0; h < HARMONICS; h++) {
            assertEquals(rawRe[h], acc[h][0], 1e-9, "H" + (h + 2) + " correction holds");
            assertEquals(rawIm[h], acc[h][1], 1e-9);
        }
    }

    @Test
    void aReadingUnderTheGateMovesNothing() {
        HarmonicCompensation comp = new HarmonicCompensation(HARMONICS);
        comp.accumulate(measure(new double[HARMONICS][2], FLOOR_DB), STEP);
        double[][] acc = applied(comp);
        for (int h = 0; h < HARMONICS; h++) {
            assertEquals(0.0, acc[h][0], 0.0, "H" + (h + 2) + " keeps no correction from a floor reading");
            assertEquals(0.0, acc[h][1], 0.0);
            double[] delta = comp.lastDelta(h);
            assertEquals(0.0, delta[0], 0.0);
            assertEquals(0.0, delta[1], 0.0);
        }
    }

    /** The chain response at {@code hz} as {@code [magLin, phaseRad]}. */
    private double[] chain(double hz) {
        return new double[] { CAL_MAG_BASE + CAL_MAG_PER_HZ * hz, CAL_RAD_PER_HZ * hz };
    }

    /** The frequencies the whole-set conversion reads the chain at, one per
     *  listed harmonic in entry order - the harmonics' stored frequencies. */
    private double[] storedFrequencies(HarmonicCompensation comp) {
        List<Double> asked = new ArrayList<>();
        comp.toGeneratorCorrections(F0_HZ, f -> {
            asked.add(f);
            return chain(f);
        }, ADC_FS_VRMS, DAC_F0_VRMS);
        return asked.stream().mapToDouble(Double::doubleValue).toArray();
    }

    @Test
    void applyingOneLineLeavesEveryOtherHarmonicBitIdentical() {
        HarmonicCompensation comp = new HarmonicCompensation(HARMONICS);
        comp.accumulate(measure(new double[HARMONICS][2], CLEAR_DB), STEP);
        double[][] corrBefore  = new double[HARMONICS][];
        double[][] deltaBefore = new double[HARMONICS][];
        for (int h = 0; h < HARMONICS; h++) {
            corrBefore[h]  = comp.correction(h);
            deltaBefore[h] = comp.lastDelta(h);
        }
        double[] hzBefore = storedFrequencies(comp);
        assertEquals(HARMONICS, hzBefore.length, "every harmonic holds a correction");

        comp.applyLine(LINE, LINE_RE, LINE_IM, LINE_HZ, F0_HZ, this::chain, ADC_FS_VRMS, DAC_F0_VRMS);

        double[] hzAfter = storedFrequencies(comp);
        for (int h = 0; h < HARMONICS; h++) {
            if (h == LINE) continue;
            assertArrayEquals(corrBefore[h], comp.correction(h), "H" + (h + 2) + " correction");
            assertArrayEquals(deltaBefore[h], comp.lastDelta(h), "H" + (h + 2) + " last delta");
            assertEquals(hzBefore[h], hzAfter[h], "H" + (h + 2) + " frequency");
        }
        assertArrayEquals(new double[] { rawRe[LINE] + LINE_RE, rawIm[LINE] + LINE_IM },
                comp.correction(LINE), "the line's correction takes the residual at the full step");
        assertArrayEquals(new double[] { LINE_RE, LINE_IM }, comp.lastDelta(LINE), "the line's last delta");
        assertEquals(LINE_HZ, hzAfter[LINE], "the line's frequency");
    }

    @Test
    void theLineEntryIsTheEntryTheWholeSetLists() {
        HarmonicCompensation comp = new HarmonicCompensation(HARMONICS);
        comp.accumulate(measure(new double[HARMONICS][2], CLEAR_DB), STEP);
        GeneratorCorrections line = comp.applyLine(LINE, LINE_RE, LINE_IM, LINE_HZ, F0_HZ,
                this::chain, ADC_FS_VRMS, DAC_F0_VRMS);
        GeneratorCorrections set = comp.toGeneratorCorrections(F0_HZ, this::chain, ADC_FS_VRMS, DAC_F0_VRMS);

        assertEquals(1, line.harmonicNumbers().length, "one line, one entry");
        assertEquals(LINE + 2, line.harmonicNumbers()[0], "the entry is H3's");
        int i = 0;
        while (set.harmonicNumbers()[i] != LINE + 2) i++;
        assertEquals(set.ampRatios()[i], line.ampRatios()[0], "amplitude ratio");
        assertEquals(set.phiInits()[i],  line.phiInits()[0],  "initial phase");
    }

    /** A single-tone result with H2..H4 stamped, no line states. */
    private FftResult singleTone() {
        FftResult r = new FftResult();
        r.freqResolution       = BIN_HZ;
        r.windowNenbwBins      = NENBW_BINS;
        r.fundamentalBin       = (int) Math.round(F0_HZ / BIN_HZ);
        r.fundamentalHzRefined = F0_HZ;
        r.dacFundPhaseRad      = 0.0;
        r.re                   = new double[SPECTRUM_BINS];
        r.im                   = new double[SPECTRUM_BINS];
        r.amplitudeDbFs        = new double[SPECTRUM_BINS];
        Arrays.fill(r.amplitudeDbFs, GRASS_DBFS);
        r.harmonicCount = STAMPED;
        r.harmonicBins  = new int[STAMPED];
        r.harmonicHz    = new double[STAMPED];
        r.harmonicDbFs  = new double[STAMPED];
        for (int h = 0; h < STAMPED; h++) {
            r.harmonicHz[h]   = (h + 2) * F0_HZ;
            r.harmonicBins[h] = (int) Math.round(r.harmonicHz[h] / BIN_HZ);
            r.harmonicDbFs[h] = HARMONIC_DBFS;
        }
        return r;
    }

    @Test
    void theClearanceReadsTheLineStateAndTheLineFloorOnlyWithoutLineStates() {
        HarmonicCompensation comp = new HarmonicCompensation(HARMONICS);
        double besideDb = HARMONIC_DBFS - GRASS_DBFS;

        // A stamped result: H2 carries a measured local band top, H3 no line
        // state, H4 a line state whose band top is not measured yet.  The
        // grass beside H3 and H4 would read 30 dB clear; a stamped result
        // never reads it.
        FftResult averaged = singleTone();
        averaged.lineStates = List.of(
                new LineState(2, 0, averaged.harmonicBins[0], LOCAL_FRAMES, false,
                        HARMONIC_DBFS - STATE_CLEAR_DB),
                new LineState(H4_NUMBER, 0, averaged.harmonicBins[2], LOCAL_FRAMES, false, Double.NaN));
        HarmonicPhasors stamped = comp.measurePhasors(averaged, 0.0);
        double[] clear = stamped.clearanceDb();
        assertEquals(STATE_CLEAR_DB, clear[0], 1e-9, "H2 reads its own local band top");
        assertTrue(Double.isNaN(clear[1]), "H3 without a line state on a stamped result reads NaN");
        assertTrue(Double.isNaN(clear[2]), "H4 without a measured band top reads NaN");

        // A result no coherent average stamped carries no line states at all:
        // the noise band beside each line.
        HarmonicPhasors unstamped = comp.measurePhasors(singleTone(), 0.0);
        double tableLin = Math.pow(10.0, HARMONIC_DBFS / 20.0);
        for (int h = 0; h < STAMPED; h++) {
            String name = "H" + (h + 2);
            assertEquals(besideDb, unstamped.clearanceDb()[h], 1e-9, name + " on a result without line states");
            // Neither result captured peak phasors: the level falls back to
            // the harmonic table.
            assertEquals(tableLin, Math.hypot(stamped.re()[h], stamped.im()[h]), 1e-18,
                    name + " level without captured peaks, stamped result");
            assertEquals(tableLin, Math.hypot(unstamped.re()[h], unstamped.im()[h]), 1e-18,
                    name + " level without captured peaks, result without line states");
        }
    }

    /** {@code rad} wrapped to (-pi, pi], the range atan2 reports in. */
    private double wrapped(double rad) {
        return Math.atan2(Math.sin(rad), Math.cos(rad));
    }

    /** A result built the way the analyser worker hands it on: the harmonic
     *  table holds the single frame just analysed (HARMONIC_DBFS), the
     *  averaged peak phasor captured at each harmonic's bin stands
     *  FRAME_EXCESS_DB lower, no line states. */
    private FftResult workerResult() {
        FftResult r = singleTone();
        double averagedLin = Math.pow(10.0, (HARMONIC_DBFS - FRAME_EXCESS_DB) / 20.0);
        r.rawPeakRe = new double[STAMPED];
        r.rawPeakIm = new double[STAMPED];
        for (int h = 0; h < STAMPED; h++) {
            r.rawPeakRe[h] = averagedLin * Math.cos(PEAK_RAD);
            r.rawPeakIm[h] = averagedLin * Math.sin(PEAK_RAD);
        }
        return r;
    }

    @Test
    void theLevelAndTheClearanceFollowTheAveragedPeak() {
        HarmonicCompensation comp = new HarmonicCompensation(HARMONICS);
        double averagedDbFs = HARMONIC_DBFS - FRAME_EXCESS_DB;

        // Each harmonic's local band top 3 dB under the averaged peak: the
        // single frame's level would stand 13 dB over it and pass the gate.
        FftResult stamped = workerResult();
        List<LineState> states = new ArrayList<>();
        for (int h = 0; h < STAMPED; h++) {
            states.add(new LineState(h + 2, 0, stamped.harmonicBins[h], LOCAL_FRAMES, false,
                    averagedDbFs - STATE_CLEAR_DB));
        }
        stamped.lineStates = states;
        HarmonicPhasors z = comp.measurePhasors(stamped, 0.0);
        for (int h = 0; h < STAMPED; h++) {
            String name = "H" + (h + 2);
            assertEquals(Math.pow(10.0, averagedDbFs / 20.0), Math.hypot(z.re()[h], z.im()[h]), 1e-18,
                    name + " level is the averaged peak's, not the single frame's");
            // The phase is the same averaged peak's, de-rotated by the
            // harmonic number times (phi1 + pi/2) with phi1 = 0; the
            // spectrum's own bin is zero here and would read 0.
            assertEquals(wrapped(PEAK_RAD - (h + 2) * Math.PI / 2.0), Math.atan2(z.im()[h], z.re()[h]), 1e-12,
                    name + " phase is the averaged peak's");
            assertEquals(STATE_CLEAR_DB, z.clearanceDb()[h], 1e-9,
                    name + " clearance takes the averaged peak's level");
        }

        // Without line states the clearance over the noise band beside the
        // line takes the averaged peak's level too.
        double[] unstamped = comp.measurePhasors(workerResult(), 0.0).clearanceDb();
        for (int h = 0; h < STAMPED; h++) {
            assertEquals(averagedDbFs - GRASS_DBFS, unstamped[h], 1e-9,
                    "H" + (h + 2) + " clearance beside the line takes the averaged peak's level");
        }
    }
}
