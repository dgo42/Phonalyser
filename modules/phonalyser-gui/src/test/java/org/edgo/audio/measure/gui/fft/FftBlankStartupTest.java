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

package org.edgo.audio.measure.gui.fft;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.commons.lang3.reflect.MethodUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.CoherentAccumulator;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.fft.FftResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Coherent cross-cycle accumulator vs a DEGENERATE FIRST WINDOW.
 *
 * <p>The accumulator pins its coherent reference (κ, the fractional
 * fundamental bin) from the RESTART analysis cycle.  A capture that
 * begins with a line-open transient - leading zeros, a half-filled
 * window, a DC step - hands that cycle a window with no usable
 * fundamental, so the pinned reference comes from noise (or is not
 * finite at all).  Every later clean analysis cycle is then de-rotated
 * against that reference; if the reference never heals, the displayed
 * average stays degenerate for the whole run while the cycle counter
 * keeps climbing - a chart with no trace, no harmonic dots and no THD
 * table, at every scroll position.
 *
 * <p>Same driving pattern as {@link FftCrossCycleDriftTest}: the real
 * {@link FftAnalyzerWorker} accumulator + PLL headlessly, read back
 * through the accumulator's overlay +
 * {@link FftAnalyzer#recomputeStats} - the exact display path.  Both of
 * those worker members are private - a productive class is not widened to
 * let a test in - so this harness reaches them by reflection.  The
 * geometry mirrors the reported bench: 32 kHz, 64k FFT, Hann, 0 %
 * overlap, ring of 4, tone 999.96 Hz at -2.8 dBFS.
 */
@Tag("exploratory")   // parked red: pins the intended post-fix behaviour - excluded from the normal build (see pom surefire)
class FftBlankStartupTest {

    private static final int        SAMPLE_RATE = 32_000;
    private static final int        FFT_SIZE    = 65_536;
    private static final WindowType WINDOW      = WindowType.HANN;
    private static final FftOverlap OVERLAP     = FftOverlap.PCT_0;
    private static final int        HARMONICS   = 8;
    private static final int        TARGET_N    = 4;            // ring 4 -> 4 frames, one per analysis cycle
    private static final double     FUND_HZ     = 999.96;       // fractional bin 2047.918
    private static final double     AMPL        = 0.724;        // -2.8 dBFS
    private static final double     NOISE_STD   = 3.0e-6;
    private static final long       SEED        = 20_260_818L;
    private static final int        CYCLES      = 12;
    /** A displayed fundamental this far above the clamp floor counts as drawn. */
    private static final double     HEALTHY_DBFS = -30.0;

    /** The worker members this harness drives, by name because that is what
     *  reflection takes: the private accumulate step and the private
     *  accumulator whose overlay writes the average back onto a result for
     *  the display path. */
    private static final String ACCUMULATE  = "accumulateIntoForeverBuffer";
    private static final String ACCUMULATOR = "accumulator";

    private static final Logger LOG = LogManager.getLogger("FftBlankStartup");

    /** The one place this harness reaches into the worker: both members it
     *  drives are private, so a renamed member fails here and not at every
     *  call site. */
    private void call(FftAnalyzerWorker worker, String method, Object... args) {
        try {
            MethodUtils.invokeMethod(worker, true, method, args);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("the worker's " + method + " is not reachable", ex);
        }
    }

    /** The worker's accumulator, written back onto {@code r}. */
    private void overlay(FftAnalyzerWorker worker, FftResult r) {
        try {
            ((CoherentAccumulator) FieldUtils.readField(worker, ACCUMULATOR, true)).overlayOnto(r);
        } catch (IllegalAccessException ex) {
            throw new AssertionError("the worker's " + ACCUMULATOR + " is not reachable", ex);
        }
    }

    /** How the capture begins, before the continuous clean sine. */
    private enum Start {
        CLEAN     { @Override void mangle(double[] sig, int winLen) { } },
        /** Whole first window digital zeros - the line delivered nothing yet. */
        ZERO_WIN  { @Override void mangle(double[] sig, int winLen) {
            for (int i = 0; i < winLen; i++) sig[i] = 0.0;
        } },
        /** Line opens mid-window: first half zeros, sine starts abruptly. */
        HALF_WIN  { @Override void mangle(double[] sig, int winLen) {
            for (int i = 0; i < winLen / 2; i++) sig[i] = 0.0;
        } },
        /** Start thump: a DC step across the first half window. */
        DC_STEP   { @Override void mangle(double[] sig, int winLen) {
            for (int i = 0; i < winLen / 2; i++) sig[i] = 0.5;
        } };
        abstract void mangle(double[] sig, int winLen);
    }

    @Test
    void startTransientMustNotBlankTheAverageForever() {
        int hop    = FFT_SIZE;                       // 0 % overlap
        int winLen = FFT_SIZE;                       // one frame per analysis cycle (worker geometry)
        int total  = (CYCLES - 1) * hop + winLen;

        StringBuilder failures = new StringBuilder();
        for (Start start : Start.values()) {
            double lastFund = run(start, hop, winLen, total);
            LOG.info(String.format("=== %s: displayed fundamental after %d analysis cycles = %.2f dBFS ===",
                    start, CYCLES, lastFund));
            if (!(lastFund > HEALTHY_DBFS)) {
                failures.append(String.format("%s ends at %.2f dBFS; ", start, lastFund));
            }
        }
        assertTrue(failures.length() == 0,
                "Displayed average never recovered from the start transient: " + failures);
    }

    private double run(Start start, int hop, int winLen, int total) {
        double[] sig = synthesize(total);
        start.mangle(sig, winLen);

        FftAnalyzer       analyzer = new FftAnalyzer();
        FftAnalyzerWorker worker   = new FftAnalyzerWorker(null);      // headless
        double[] win = new double[winLen];

        double lastFund = Double.NEGATIVE_INFINITY;
        long base = 0;
        for (int t = 0; t < CYCLES; t++) {
            System.arraycopy(sig, (int) base, win, 0, winLen);
            analyzer.setSpectrumOnly(true);
            analyzer.setMultiTone(false);
            analyzer.setSecondToneHintHz(Double.NaN);
            FftResult r = analyzer.analyze(win, SAMPLE_RATE, FFT_SIZE, HARMONICS,
                    WINDOW, OVERLAP, 0.0, 0.0, true, Double.NaN, false, FUND_HZ);
            call(worker, ACCUMULATE, r, base, true, TARGET_N);

            overlay(worker, r);                      // the display path, every analysis cycle
            analyzer.recomputeStats(r);
            int nanBins = 0, liveBins = 0;
            for (int k = 1; k < r.amplitudeDbFs.length; k++) {
                if (Double.isNaN(r.amplitudeDbFs[k])) nanBins++;
                else if (r.amplitudeDbFs[k] > -290.0) liveBins++;
            }
            lastFund = r.fundamentalDbFs;
            LOG.info(String.format(
                    "%s cycle %2d  frames=%3d  fund=%8.2f dBFS @ %10.3f Hz  live=%6d  nan=%6d",
                    start, t + 1, worker.getAccumulatedFrames(), r.fundamentalDbFs,
                    r.fundamentalHzRefined, liveBins, nanBins));
            base += hop;
        }
        return lastFund;
    }

    /** Continuous clean tone + light noise for the whole run. */
    private double[] synthesize(int total) {
        Random rnd = new Random(SEED);
        double[] sig = new double[total];
        double w = 2.0 * Math.PI * FUND_HZ / SAMPLE_RATE;
        for (int i = 0; i < total; i++) {
            sig[i] = AMPL * Math.sin(w * i) + NOISE_STD * rnd.nextGaussian();
        }
        return sig;
    }
}
