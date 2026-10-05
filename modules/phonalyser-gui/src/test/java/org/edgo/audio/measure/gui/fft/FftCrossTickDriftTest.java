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

import org.apache.commons.lang3.reflect.MethodUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.fft.FftResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-tick harmonic-stability harness under a DRIFTING DAC↔ADC clock.
 *
 * <p>Unlike {@code FftHarmonicStabilityTest} (a single long {@code analyze()} call),
 * this drives the <b>real</b> {@link FftAnalyzerWorker} cross-tick path - the one the
 * live FFT view actually uses: 2 frames per tick (averages = 2), accumulated across
 * ticks by {@code accumulateIntoForeverBuffer} with its type-1
 * phase-lock loop, then read back via {@code overlayAccumulatorOnto}
 * + {@link FftAnalyzer#recomputeStats}.  Only the Thread/Display/MessageBus plumbing
 * is bypassed; the accumulator + PLL are the production code, reached through
 * reflection because both worker members are private.
 *
 * <p>The capture is a continuous tone+harmonics whose frequency <b>chirps</b> at a
 * configurable rate (the DAC↔ADC relative drift).  The worker de-rotates each tick by
 * the whole-spectrum time-shift ramp (phase proportional to bin index, anchored on the
 * refined κ); the PLL tracks the FUNDAMENTAL phase and its sample-domain correction
 * reaches every harmonic ×h through the ramp - so under a common-clock drift the
 * whole coherent set must hold flat over depth.  ({@code 10 ppb/min = 0.01 ppm/min}
 * is the real NZ2520SDA rig; higher rates exaggerate any residual to see a breakdown.)
 */
@Tag("exploratory")   // slow diagnostic harness - excluded from the normal build (see pom surefire)
class FftCrossTickDriftTest {

    private static final int        SAMPLE_RATE    = 384_000;
    private static final int        FFT_SIZE       = 65_536;
    private static final WindowType WINDOW         = WindowType.BH7;
    private static final FftOverlap OVERLAP        = FftOverlap.PCT_75;
    private static final int        HARMONIC_COUNT = 8;                 // H2..H9

    private static final double     FUND_HZ        = 1001.953125;       // on-bin @ 64k/384k (bin 171)
    private static final double     FUND_DBFS      = -79.74;
    private static final double[]   HARMONIC_DBFS  = {
            -130.0, -135.0, -150.0, -145.0, -158.0, -160.0, -157.0, -162.0
    };
    private static final int        DITHER_BITS    = 27;
    private static final double     NOISE_STD      = 3.0e-6;
    private static final long       SEED           = 20_260_604L;

    private static final int        TICKS          = 1000;              // cross-tick captures
    private static final int        READOUT_EVERY  = 100;               // read accumulated harmonics every N ticks
    /** Relative DAC↔ADC drift rate, ppm/min.  0.01 ppm/min = 10 ppb/min (the rig). */
    private static final double[]   DRIFT_PPM_PER_MIN = {0.0, 0.01, 1.0, 10.0};

    /** The worker members this harness drives, by name because that is what
     *  reflection takes: the private accumulate step and the private overlay
     *  that writes the accumulator back onto a result for the display path.
     *  A productive class is not widened to let a test in. */
    private static final String ACCUMULATE = "accumulateIntoForeverBuffer";
    private static final String OVERLAY    = "overlayAccumulatorOnto";

    private static final Logger LOG = LogManager.getLogger("FftCrossTickDrift");

    /** The one place this harness reaches into the worker, so a renamed
     *  member fails here and not at a dozen call sites. */
    private void call(FftAnalyzerWorker worker, String method, Object... args) {
        try {
            MethodUtils.invokeMethod(worker, true, method, args);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("the worker's " + method + " is not reachable", ex);
        }
    }

    @Test
    void crossTickHarmonicDriftSweep() {
        int hop    = Math.max(1, (int) Math.round(FFT_SIZE * (1.0 - OVERLAP.fraction)));
        int winLen = FFT_SIZE + hop;                                    // averages = 2 (worker geometry)
        int total  = (TICKS - 1) * hop + winLen;
        LOG.info("=== CROSS-TICK DRIFT: real worker PLL | {} ticks x 2-frame | {} pt {} {} | {} Hz ===",
                TICKS, FFT_SIZE, WINDOW, OVERLAP.label, FUND_HZ);

        for (double driftPpmMin : DRIFT_PPM_PER_MIN) {
            double driftPerSample = driftPpmMin * 1e-6 / 60.0 / SAMPLE_RATE;   // relative Δf per sample
            double[] sig = synthesizeContinuous(SEED, total, driftPerSample);
            double ppbOverRun = driftPpmMin * 1000.0 * (total / (double) SAMPLE_RATE) / 60.0;

            FftAnalyzer       analyzer = new FftAnalyzer();
            FftAnalyzerWorker worker   = new FftAnalyzerWorker(null);      // headless
            double[] win = new double[winLen];

            LOG.info(String.format("--- drift = %.3f ppm/min  (%.1f ppb over %.1f s) ---",
                    driftPpmMin, ppbOverRun, total / (double) SAMPLE_RATE));

            // Final readout vs the SYNTHESIZED levels: the whole-spectrum ramp
            // propagates the fundamental loop's clock correction ×h, so at full
            // depth every coherent component must read its true level at every
            // common-clock drift rate.  (An early-vs-late comparison would be
            // biased low at the start for harmonics near the per-bin floor.)
            long base = 0;
            FftResult r;
            for (int t = 0; t < TICKS; t++) {
                System.arraycopy(sig, (int) base, win, 0, winLen);
                analyzer.setSamplesAbsStart(base);
                analyzer.setSpectrumOnly(true);
                analyzer.setMultiTone(false);
                analyzer.setSecondToneHintHz(Double.NaN);
                r = analyzer.analyze(win, SAMPLE_RATE, FFT_SIZE, HARMONIC_COUNT,
                        WINDOW, OVERLAP, 0.0, 0.0, true, Double.NaN, false, Double.NaN);
                call(worker, ACCUMULATE, r, base, true, Integer.MAX_VALUE);

                if ((t + 1) % READOUT_EVERY == 0) {
                    call(worker, OVERLAY, r);
                    analyzer.recomputeStats(r);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < HARMONIC_COUNT; i++) {
                        double h = (r.harmonicDbFs != null && i < r.harmonicDbFs.length)
                                ? r.harmonicDbFs[i] : Double.NaN;
                        sb.append(String.format(" H%d=%.2f", i + 2, h));
                    }
                    LOG.info(String.format("  tick %4d  frames=%6d  fund=%.2f |%s",
                            t + 1, worker.getAccumulatedFrames(), r.fundamentalDbFs, sb));
                    if (t + 1 == TICKS) {
                        assertEquals(FUND_DBFS, r.fundamentalDbFs, 1.0,
                                "fundamental off its set level at " + driftPpmMin + " ppm/min");
                        // 3 dB: the weakest harmonics sit ~12 dB above the
                        // deep-averaged floor, where residual noise perturbs a
                        // reading by up to ~2.2 dB; a rotation defect sinks
                        // harmonics by tens of dB and still trips this.
                        for (int i = 0; i < HARMONIC_COUNT; i++) {
                            assertEquals(HARMONIC_DBFS[i], r.harmonicDbFs[i], 3.0,
                                    "H" + (i + 2) + " off its set level at " + driftPpmMin + " ppm/min");
                        }
                    }
                }
                base += hop;
            }
        }
    }

    // ── Random overlap: variable hop through the same worker path ──────────
    /** The fixed-hop sweep's geometry with each tick's hop drawn uniformly
     *  from the random mode's own overlap range.  The de-rotation and the
     *  accumulator PLL ride absolute sample positions, so the readouts must
     *  hold the same levels as the fixed-hop sweep at the same depth - any
     *  hop-dependent term shows here as a fundamental or harmonic decline. */
    @Test
    void crossTickRandomHopSweep() {
        int minHop = FftOverlap.RAND.minHopSamples(FFT_SIZE);
        int maxHop = FftOverlap.RAND.maxHopSamples(FFT_SIZE);
        int winLen = FFT_SIZE + maxHop;                     // worker capacity: the largest hop
        int total  = (TICKS - 1) * maxHop + winLen;
        LOG.info("=== CROSS-TICK RANDOM HOP: {} ticks x 2-frame | hop in [{}..{}] | {} pt {} | {} Hz ===",
                TICKS, minHop, maxHop, FFT_SIZE, WINDOW, FUND_HZ);

        for (double driftPpmMin : DRIFT_PPM_PER_MIN) {
            double driftPerSample = driftPpmMin * 1e-6 / 60.0 / SAMPLE_RATE;
            double[] sig = synthesizeContinuous(SEED, total, driftPerSample);
            Random hopDraw = new Random(SEED);              // reproducible hop sequence
            FftAnalyzer       analyzer = new FftAnalyzer();
            FftAnalyzerWorker worker   = new FftAnalyzerWorker(null);   // headless
            double[] win     = new double[winLen];
            LOG.info(String.format("--- drift = %.3f ppm/min  (random hop) ---", driftPpmMin));

            long base = 0;
            FftResult r;
            for (int t = 0; t < TICKS; t++) {
                // The worker's lattice draw: part boundaries of the mode's range.
                int hop = minHop + (1 + hopDraw.nextInt(FftOverlap.RAND_HOP_STEPS))
                        * ((maxHop - minHop) / FftOverlap.RAND_HOP_STEPS);
                System.arraycopy(sig, (int) base, win, 0, winLen);
                analyzer.setSamplesAbsStart(base);
                analyzer.setSpectrumOnly(true);
                analyzer.setMultiTone(false);
                analyzer.setSecondToneHintHz(Double.NaN);
                r = analyzer.analyze(win, SAMPLE_RATE, FFT_SIZE, HARMONIC_COUNT, HARMONIC_COUNT,
                        WINDOW, FftOverlap.RAND, 0.0, 0.0, true, Double.NaN,
                        false, Double.NaN, new FftResult(), hop, 2);
                call(worker, ACCUMULATE, r, base, true, Integer.MAX_VALUE);

                if ((t + 1) % READOUT_EVERY == 0) {
                    call(worker, OVERLAY, r);
                    analyzer.recomputeStats(r);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < HARMONIC_COUNT; i++) {
                        double h = (r.harmonicDbFs != null && i < r.harmonicDbFs.length)
                                ? r.harmonicDbFs[i] : Double.NaN;
                        sb.append(String.format(" H%d=%.2f", i + 2, h));
                    }
                    LOG.info(String.format("  tick %4d  frames=%6d  fund=%.2f |%s",
                            t + 1, worker.getAccumulatedFrames(), r.fundamentalDbFs, sb));
                }
                base += hop;
            }
        }
    }

    // ── True dual-tone (IMD) under drift ───────────────────────────────────
    /** Two EQUAL tones F1,F2 plus their 2nd/3rd-order IMD products at known bins,
     *  all on one synthetic clock.  Under the whole-spectrum ramp every on-bin
     *  component - pair and products alike - must hold flat over depth at every
     *  common-clock drift rate.  Product levels are read straight from the
     *  accumulated spectrum (they aren't harmonics of one fundamental). */
    @Test
    void crossTickImdDriftSweep() {
        int hop    = Math.max(1, (int) Math.round(FFT_SIZE * (1.0 - OVERLAP.fraction)));
        int winLen = FFT_SIZE + hop;
        int total  = (TICKS - 1) * hop + winLen;
        double binHz = (double) SAMPLE_RATE / FFT_SIZE;

        // F1, F2 on-bin and well-separated (outside BH7's ±190-bin lobes); products clean.
        int      f1Bin = 1000, f2Bin = 1700;                 // 5859 Hz, 9961 Hz, both −30 dBFS
        int[]    bins  = { f1Bin, f2Bin,  300,   700,  2000,  2400,  2700,  3400 };
        double[] dbfs  = { -30.0, -30.0, -120.0,-115.0,-110.0,-120.0,-115.0,-110.0 };
        String[] lbl   = { "F1", "F2", "2F1-F2", "F2-F1", "2F1", "2F2-F1", "F1+F2", "2F2" };
        int      pFrom = 2;                                   // indices [2..] are the products to monitor
        double[] freqs = new double[bins.length];
        for (int i = 0; i < bins.length; i++) freqs[i] = bins[i] * binHz;

        LOG.info("=== CROSS-TICK IMD DRIFT: F1={} Hz F2={} Hz (both −30 dBFS) | products read direct ===",
                String.format("%.1f", freqs[0]), String.format("%.1f", freqs[1]));

        for (double driftPpmMin : new double[]{0.0, 10.0}) {
            double driftPerSample = driftPpmMin * 1e-6 / 60.0 / SAMPLE_RATE;
            double[] sig = synthesizeTones(SEED, total, driftPerSample, freqs, dbfs);

            FftAnalyzer       analyzer = new FftAnalyzer();
            FftAnalyzerWorker worker   = new FftAnalyzerWorker(null);
            double[] win = new double[winLen];
            LOG.info(String.format("--- drift = %.3f ppm/min ---", driftPpmMin));

            // Mid-run vs final: the pair and every on-bin product ride the one
            // ramp and the shared clock, so they must hold flat over depth.
            double[] early = new double[bins.length];
            long base = 0;
            FftResult r;
            for (int t = 0; t < TICKS; t++) {
                System.arraycopy(sig, (int) base, win, 0, winLen);
                analyzer.setSamplesAbsStart(base);
                analyzer.setSpectrumOnly(true);
                analyzer.setMultiTone(true);
                analyzer.setSecondToneHintHz(freqs[1]);
                r = analyzer.analyze(win, SAMPLE_RATE, FFT_SIZE, HARMONIC_COUNT,
                        WINDOW, OVERLAP, 0.0, 0.0, true, Double.NaN, false, Double.NaN);
                call(worker, ACCUMULATE, r, base, true, Integer.MAX_VALUE);

                if ((t + 1) % (READOUT_EVERY * 5) == 0) {
                    call(worker, OVERLAY, r);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < bins.length; i++) {
                        double lv = (r.amplitudeDbFs != null && bins[i] < r.amplitudeDbFs.length)
                                ? r.amplitudeDbFs[bins[i]] : Double.NaN;
                        if (t + 1 == READOUT_EVERY * 5) early[i] = lv;
                        if (i >= pFrom) sb.append(String.format("  %s=%.1f", lbl[i], lv));
                    }
                    LOG.info(String.format("  tick %4d  frames=%6d |%s",
                            t + 1, worker.getAccumulatedFrames(), sb));
                    if (t + 1 == TICKS) {
                        // The pair pools nothing by design, so under a strongly
                        // exaggerated common-clock drift the difference products
                        // compound the fundamental loop's lag with the pinned
                        // second-tone residual (sum products cancel the pair) -
                        // a few dB at 1000x the real rig's drift, a millionth of
                        // that in phase at the rig's own.  The wide tolerance at
                        // the exaggerated rate still catches the historical
                        // half-turn cancellation class (tens of dB).
                        double tol = driftPpmMin > 1.0 ? 10.0 : 2.0;
                        for (int i = 0; i < bins.length; i++) {
                            assertEquals(early[i], r.amplitudeDbFs[bins[i]], tol,
                                    lbl[i] + " drifted at " + driftPpmMin + " ppm/min");
                        }
                    }
                }
                base += hop;
            }
        }
    }

    /** Reproduces the live F2-sink: two EQUAL tones with F2/F1 ≈ 1.05 so the per-tick
     *  single-tone analyze (seeded fundamental = F1, "get fundamental from generator")
     *  snaps F2 to {@code h = round(F2/F1) = 1} and de-rotates it as F1.  F2/F1 = 1.7 in
     *  the other test rounds to an integer-cycle error and stays clean - this one
     *  used to bite.  Reads F1 & F2 levels vs averaging depth. */
    @Test
    void crossTickDualToneSinkRepro() {
        int hop    = Math.max(1, (int) Math.round(FFT_SIZE * (1.0 - OVERLAP.fraction)));
        int winLen = FFT_SIZE + hop;
        int total  = (TICKS - 1) * hop + winLen;
        double binHz = (double) SAMPLE_RATE / FFT_SIZE;
        int    f1Bin = 8533, f2Bin = 9047;                   // ratio->h=1; Δ=514 -> 0.25·514=128.5 -> 180° cancel
        double[] freqs = { f1Bin * binHz, f2Bin * binHz };
        double[] dbfs  = { -10.0, -10.0 };

        LOG.info("=== DUAL-TONE F2-SINK REPRO: F1={} Hz F2={} Hz (equal, seed fund=F1) ===",
                String.format("%.1f", freqs[0]), String.format("%.1f", freqs[1]));

        for (double driftPpmMin : new double[]{0.0, 1.0}) {
            double driftPerSample = driftPpmMin * 1e-6 / 60.0 / SAMPLE_RATE;
            double[] sig = synthesizeTones(SEED, total, driftPerSample, freqs, dbfs);
            FftAnalyzer       analyzer = new FftAnalyzer();
            FftAnalyzerWorker worker   = new FftAnalyzerWorker(null);
            double[] win = new double[winLen];
            LOG.info(String.format("--- drift = %.3f ppm/min ---", driftPpmMin));
            long base = 0;
            FftResult r;
            for (int t = 0; t < TICKS; t++) {
                System.arraycopy(sig, (int) base, win, 0, winLen);
                analyzer.setSamplesAbsStart(base);
                analyzer.setSpectrumOnly(true);
                analyzer.setMultiTone(true);
                analyzer.setSecondToneHintHz(freqs[1]);
                r = analyzer.analyze(win, SAMPLE_RATE, FFT_SIZE, HARMONIC_COUNT,
                        WINDOW, OVERLAP, 0.0, 0.0, true, Double.NaN, false, freqs[0]);  // seed fund = F1
                if (t == 0) {   // SINGLE-capture levels - before any cross-tick accumulation
                    LOG.info(String.format("    single capture (1 avg):  F1=%.2f  F2=%.2f  (F2-F1=%.2f dB)",
                            r.amplitudeDbFs[f1Bin], r.amplitudeDbFs[f2Bin],
                            r.amplitudeDbFs[f2Bin] - r.amplitudeDbFs[f1Bin]));
                }
                call(worker, ACCUMULATE, r, base, true, Integer.MAX_VALUE);
                if ((t + 1) % (READOUT_EVERY * 2) == 0) {
                    call(worker, OVERLAY, r);
                    double f1 = r.amplitudeDbFs[f1Bin], f2 = r.amplitudeDbFs[f2Bin];
                    LOG.info(String.format("  tick %4d  frames=%6d |  F1=%.2f  F2=%.2f  (F2-F1=%.2f dB)",
                            t + 1, worker.getAccumulatedFrames(), f1, f2, f2 - f1));
                    if (t + 1 == TICKS) {
                        // Equal close-spaced tones must both survive the deep
                        // average - the historical defect cancelled F2 here.
                        assertTrue(Math.abs(f2 - f1) <= 2.0,
                                "F2-F1 = " + String.format("%.2f", f2 - f1)
                                + " dB at drift " + driftPpmMin + " ppm/min");
                    }
                }
                base += hop;
            }
        }
    }

    /** Reproduces the EXACT live case: 19 & 20 kHz at 2 M FFT.  The bug is
     *  intra-capture, so a single {@code analyze()} (2-frame) shows it directly -
     *  no cross-tick needed.  Δ=5462 bins -> 0.25·5462 = 1365.5 -> 180° cancel under
     *  the single-tone snap.  Logs F1/F2 and the second-tone refine so we can see
     *  whether the dual-tone fix even engaged. */
    @Test
    void crossTickImd2M() {
        final int FFT = 2_097_152;
        int hop    = Math.max(1, (int) Math.round(FFT * (1.0 - OVERLAP.fraction)));
        int winLen = FFT + hop;                              // averages = 2 (single capture = the bug)
        double binHz = (double) SAMPLE_RATE / FFT;
        int    f1Bin = 103765;                               // ≈19.0 kHz
        int[]  dFbins = { 500, 2000, 5462, 10000, 30000 };   // F2−F1 spacing sweep (5462 = the live 1 kHz)
        LOG.info("=== IMD 2M (NO HINT - auto-detect, live config): F1 bin {} (~19 kHz), F2 swept; "
                + "multiTone, no commanded fundamental ===", f1Bin);

        FftAnalyzer analyzer = new FftAnalyzer();
        for (int dF : dFbins) {
            int f2Bin = f1Bin + dF;
            double[] freqs = { f1Bin * binHz, f2Bin * binHz };
            double[] dbfs  = { -10.0, -10.0 };
            double[] sig = synthesizeTones(SEED, winLen, 0.0, freqs, dbfs);
            analyzer.setSamplesAbsStart(0);
            analyzer.setSpectrumOnly(true);
            analyzer.setMultiTone(true);
            analyzer.setSecondToneHintHz(Double.NaN);        // NO hint - get-fund-from-gen OFF
            FftResult r = analyzer.analyze(sig, SAMPLE_RATE, FFT, HARMONIC_COUNT,
                    WINDOW, OVERLAP, 0.0, 0.0, true, Double.NaN, false, Double.NaN);   // no expectedFundHz
            long resid = f2Bin - Math.round((double) f2Bin / f1Bin) * (long) f1Bin;    // off-harmonic distance
            LOG.info(String.format("  Δf=%6d bins:  F1=%.2f  F2=%.2f  (F2-F1=%6.2f dB)  fund2refHz=%.3f  residual=%d bins",
                    dF, r.amplitudeDbFs[f1Bin], r.amplitudeDbFs[f2Bin],
                    r.amplitudeDbFs[f2Bin] - r.amplitudeDbFs[f1Bin], r.fundamental2HzRefined, resid));
        }
    }

    /** Confirms the IMD PRODUCTS (not just F2) are mis-de-rotated: F1/F2 + the
     *  2nd/3rd-order products at known levels, single 2 M capture, live config.
     *  Products at a·F1+b·F2 get snapped to the nearest F1-harmonic, so they cancel
     *  asymmetrically by their residual - set vs measured shows which sink. */
    @Test
    void crossTickImdProducts2M() {
        final int FFT = 2_097_152;
        int hop    = Math.max(1, (int) Math.round(FFT * (1.0 - OVERLAP.fraction)));
        int winLen = FFT + hop;
        double binHz = (double) SAMPLE_RATE / FFT;
        int f1 = 103765, f2 = 109227;                        // 19 kHz, 20 kHz
        int[]    bins = { f1, f2, (f2 - f1), (2 * f1 - f2), (2 * f2 - f1), (f1 + f2) };
        double[] dbfs = { -10.0, -10.0, -90.0, -85.0, -85.0, -90.0 };
        String[] lbl  = { "F1", "F2", "F2-F1", "2F1-F2", "2F2-F1", "F1+F2" };
        double[] freqs = new double[bins.length];
        for (int i = 0; i < bins.length; i++) freqs[i] = bins[i] * binHz;

        double[] sig = synthesizeTones(SEED, winLen, 0.0, freqs, dbfs);
        FftAnalyzer analyzer = new FftAnalyzer();
        analyzer.setSamplesAbsStart(0);
        analyzer.setSpectrumOnly(true);
        analyzer.setMultiTone(true);
        analyzer.setSecondToneHintHz(freqs[1]);              // get-fund-from-gen ON
        FftResult r = analyzer.analyze(sig, SAMPLE_RATE, FFT, HARMONIC_COUNT,
                WINDOW, OVERLAP, 0.0, 0.0, true, Double.NaN, false, freqs[0]);
        LOG.info("=== IMD PRODUCTS 2M (single capture): set vs measured ===");
        for (int i = 0; i < bins.length; i++) {
            long resid = bins[i] - Math.round((double) bins[i] / f1) * (long) f1;
            LOG.info(String.format("  %-7s bin %7d set %6.1f -> measured %8.2f dBFS   (resid %6d -> %3.0f°)",
                    lbl[i], bins[i], dbfs[i], r.amplitudeDbFs[bins[i]],
                    resid, 360.0 * ((0.25 * resid) % 1.0)));
        }
    }

    /** Off-bin twin tones carrying a shared relative-frequency WOBBLE
     *  (δ(k)=A·sin, ~8-tick period - the residual an alignment loop leaves), products
     *  synthesized as tones at a·F1+b·F2 (each inherits the same relative wobble).
     *  Accumulated over many ticks; reads the COHERENT level's drift (tick 50 -> last),
     *  which is the de-coherence the wobble induces as the type-1 PLL lags the moving
     *  frequency.  The one-shot κ refine pins the tick-0 κ but cannot FOLLOW a moving
     *  frequency, so a residual wobble stays visible here.  wobble=0 is the
     *  constant-offset baseline (the PLL holds it flat). */
    @Test
    void crossTickImdWobbleLock() {
        int hop    = Math.max(1, (int) Math.round(FFT_SIZE * (1.0 - OVERLAP.fraction)));
        int winLen = FFT_SIZE + hop;
        int ticks  = 400;
        int total  = (ticks - 1) * hop + winLen;
        double binHz = (double) SAMPLE_RATE / FFT_SIZE;
        double tau = 4.0 * hop;   // ~4-tick relative-frequency wobble correlation time

        // F1, F2 OFF-bin by a fractional bin; products at a·F1+b·F2 (also off-bin).
        double f1 = (1000 + 0.37) * binHz, f2 = (1700 + 0.29) * binHz;
        double[] freqs = { f1, f2, f2 - f1, 2 * f1 - f2, 2 * f1, 2 * f2 - f1, f1 + f2, 2 * f2 };
        double[] dbfs  = { -30.0, -30.0, -100.0, -105.0, -110.0, -108.0, -112.0, -115.0 };
        String[] lbl   = { "F1", "F2", "F2-F1", "2F1-F2", "2F1", "2F2-F1", "F1+F2", "2F2" };
        int[] bins = new int[freqs.length];
        for (int i = 0; i < freqs.length; i++) bins[i] = (int) Math.round(freqs[i] / binHz);

        LOG.info("=== CROSS-TICK IMD WOBBLE LOCK: F1={} F2={} off-bin, wobble τ~{} ticks, {} ticks ===",
                String.format("%.3f", f1), String.format("%.3f", f2),
                String.format("%.0f", tau / hop), ticks);

        for (double wobblePpm : new double[]{0.0, 0.05, 0.5}) {
            double[] sig = synthesizeWobble(SEED, total, wobblePpm * 1e-6, tau, freqs, dbfs);
            FftAnalyzer       analyzer = new FftAnalyzer();
            FftAnalyzerWorker worker   = new FftAnalyzerWorker(null);
            double[] win = new double[winLen];
            double[] early = new double[freqs.length], late = new double[freqs.length];
            long base = 0;
            for (int t = 0; t < ticks; t++) {
                System.arraycopy(sig, (int) base, win, 0, winLen);
                analyzer.setSamplesAbsStart(base);
                analyzer.setSpectrumOnly(true);
                analyzer.setMultiTone(true);
                analyzer.setSecondToneHintHz(f2);
                FftResult r = analyzer.analyze(win, SAMPLE_RATE, FFT_SIZE, HARMONIC_COUNT,
                        WINDOW, OVERLAP, 0.0, 0.0, true, Double.NaN, false, Double.NaN);
                call(worker, ACCUMULATE, r, base, true, Integer.MAX_VALUE);
                if (t == 50 || t == ticks - 1) {
                    call(worker, OVERLAY, r);
                    double[] dst = (t == 50) ? early : late;
                    for (int i = 0; i < freqs.length; i++) {
                        dst[i] = (r.amplitudeDbFs != null && bins[i] < r.amplitudeDbFs.length)
                                ? r.amplitudeDbFs[bins[i]] : Double.NaN;
                    }
                }
                base += hop;
            }
            LOG.info(String.format("--- wobble = %.2f ppm  (tick 50 -> %d) ---", wobblePpm, ticks));
            for (int i = 0; i < freqs.length; i++) {
                LOG.info(String.format("  %-7s %8.2f -> %8.2f dBFS   (drift %+.2f)",
                        lbl[i], early[i], late[i], late[i] - early[i]));
            }
        }
    }

    /** An off-bin, unlocked line (a mains stand-in) among on-bin content: the
     *  whole-spectrum ramp rotates its bins by the BIN frequency, not the line's
     *  own, so its tick phasors never align and the line must average DOWN while
     *  the on-bin fundamental holds.  This is the seam-free replacement for the
     *  region schemes whose boundaries printed a pedestal around such a line. */
    @Test
    void crossTickOffBinLineDecays() {
        int hop    = Math.max(1, (int) Math.round(FFT_SIZE * (1.0 - OVERLAP.fraction)));
        int winLen = FFT_SIZE + hop;
        int ticks  = 400;
        int total  = (ticks - 1) * hop + winLen;
        double binHz = (double) SAMPLE_RATE / FFT_SIZE;
        int    fundBin = 1000, lineBin = 500;
        double[] freqs = { fundBin * binHz, (lineBin + 0.41) * binHz };   // line off-bin by 0.41
        double[] dbfs  = { -30.0, -60.0 };
        double[] sig = synthesizeTones(SEED, total, 0.0, freqs, dbfs);

        FftAnalyzer       analyzer = new FftAnalyzer();
        FftAnalyzerWorker worker   = new FftAnalyzerWorker(null);
        double[] win = new double[winLen];
        double fund0 = Double.NaN, line0 = Double.NaN, fundLate = Double.NaN, lineLate = Double.NaN;
        long base = 0;
        for (int t = 0; t < ticks; t++) {
            System.arraycopy(sig, (int) base, win, 0, winLen);
            analyzer.setSamplesAbsStart(base);
            analyzer.setSpectrumOnly(true);
            analyzer.setMultiTone(false);
            analyzer.setSecondToneHintHz(Double.NaN);
            FftResult r = analyzer.analyze(win, SAMPLE_RATE, FFT_SIZE, HARMONIC_COUNT,
                    WINDOW, OVERLAP, 0.0, 0.0, true, Double.NaN, false, Double.NaN);
            if (t == 0) {   // single-capture reference, before any accumulation
                fund0 = r.amplitudeDbFs[fundBin];
                line0 = Math.max(r.amplitudeDbFs[lineBin], r.amplitudeDbFs[lineBin + 1]);
            }
            call(worker, ACCUMULATE, r, base, true, Integer.MAX_VALUE);
            if (t == ticks - 1) {
                call(worker, OVERLAY, r);
                fundLate = r.amplitudeDbFs[fundBin];
                lineLate = Math.max(r.amplitudeDbFs[lineBin], r.amplitudeDbFs[lineBin + 1]);
            }
            base += hop;
        }
        LOG.info(String.format("=== OFF-BIN LINE DECAY: fund %.2f -> %.2f dBFS, line %.2f -> %.2f dBFS over %d ticks ===",
                fund0, fundLate, line0, lineLate, ticks));
        assertEquals(fund0, fundLate, 1.0, "on-bin fundamental must hold through the deep average");
        assertTrue(lineLate <= line0 - 10.0,
                "off-bin line must average down; was " + String.format("%.2f -> %.2f", line0, lineLate));
    }

    /** Continuous sum of arbitrary tones {@code freqHz[i]} at {@code dbfs[i]}, all
     *  chirping at the SAME {@code driftPerSample} (one shared clock), plus noise +
     *  TPDF dither.  Re-seeded every 2^16 samples to the exact chirp phase. */
    private static double[] synthesizeTones(long seed, int n, double driftPerSample,
                                           double[] freqHz, double[] dbfs) {
        double[]  s   = new double[n];
        Random   rng = new Random(seed);
        int      nt  = freqHz.length;
        double   lsb = 2.0 / (double) (1L << DITHER_BITS);
        double[] w0     = new double[nt];
        double[] a      = new double[nt];
        double[] twoCos = new double[nt];
        double[] sPrev  = new double[nt];
        double[] sCur   = new double[nt];
        for (int i = 0; i < nt; i++) {
            w0[i] = 2.0 * Math.PI * freqHz[i] / SAMPLE_RATE;
            a[i]  = Math.pow(10.0, dbfs[i] / 20.0);
        }
        final double twoPi  = 2.0 * Math.PI;
        final int    reMask = (1 << 16) - 1;
        for (int k = 0; k < n; k++) {
            if (k == 0 || (k & reMask) == 0) {
                double driftK = 1.0 + driftPerSample * k;
                for (int i = 0; i < nt; i++) {
                    double pCur  = w0[i] * (k       + 0.5 * driftPerSample * (double) k * k);
                    double pPrev = w0[i] * ((k - 1) + 0.5 * driftPerSample * (double) (k - 1) * (k - 1));
                    sCur[i]   = Math.sin(pCur  - twoPi * Math.rint(pCur  / twoPi));
                    sPrev[i]  = Math.sin(pPrev - twoPi * Math.rint(pPrev / twoPi));
                    twoCos[i] = 2.0 * Math.cos(w0[i] * driftK);
                }
            }
            double v = 0.0;
            for (int i = 0; i < nt; i++) {
                v += a[i] * sCur[i];
                double next = twoCos[i] * sCur[i] - sPrev[i];
                sPrev[i] = sCur[i];
                sCur[i]  = next;
            }
            v += NOISE_STD * rng.nextGaussian();
            double dither = (rng.nextDouble() - 0.5 + rng.nextDouble() - 0.5) * lsb;
            s[k] = (double) (Math.rint((v + dither) / lsb) * lsb);
        }
        return s;
    }

    /** Sum of tones {@code freqHz[i]} at {@code dbfs[i]}, all sharing ONE relative
     *  frequency RANDOM-WALK δ(k) - a mean-reverting (Ornstein-Uhlenbeck) wobble with
     *  stationary std {@code wobbleAmp} and correlation time {@code tauSamples}, the
     *  bounded residual a NOISY alignment loop leaves around its target.  Unlike a
     *  clean sinusoid (whose lag averages out), a random walk de-correlates the phase,
     *  so it actually de-coheres the cross-tick sum when the loop can't follow it.
     *  Phase integrated per sample: φ_i(k)=w_i·(k+Σδ) - every tone (and the product
     *  tones) wobbles by the SAME relative amount, as a shared DAC↔ADC clock would. */
    private static double[] synthesizeWobble(long seed, int n, double wobbleAmp, double tauSamples,
                                            double[] freqHz, double[] dbfs) {
        double[]  s    = new double[n];
        Random   rng  = new Random(seed);
        int      nt   = freqHz.length;
        double   lsb  = 2.0 / (double) (1L << DITHER_BITS);
        double   aOu  = 1.0 / Math.max(1.0, tauSamples);
        double   sOu  = wobbleAmp * Math.sqrt(2.0 * aOu);   // OU stationary std = wobbleAmp
        double[] w    = new double[nt];
        double[] a    = new double[nt];
        for (int i = 0; i < nt; i++) {
            w[i] = 2.0 * Math.PI * freqHz[i] / SAMPLE_RATE;
            a[i] = Math.pow(10.0, dbfs[i] / 20.0);
        }
        double delta = 0.0, cum = 0.0;
        for (int k = 0; k < n; k++) {
            delta += -aOu * delta + sOu * rng.nextGaussian();   // OU relative-freq wobble
            cum   += delta;                                     // accumulated phase offset (samples)
            double v = 0.0;
            for (int i = 0; i < nt; i++) {
                v += a[i] * Math.sin(w[i] * (k + cum));
            }
            v += NOISE_STD * rng.nextGaussian();
            double dither = (rng.nextDouble() - 0.5 + rng.nextDouble() - 0.5) * lsb;
            s[k] = (double) (Math.rint((v + dither) / lsb) * lsb);
        }
        return s;
    }

    /** Continuous tone + H2..H9 chirping at {@code driftPerSample} (relative Δf/sample),
     *  plus broadband Gaussian noise, TPDF-dithered to {@link #DITHER_BITS}.  Generated
     *  with a 3-term sine recurrence re-seeded every 2^16 samples to the EXACT chirp
     *  phase (and local frequency), bounding recurrence drift over the long buffer. */
    private static double[] synthesizeContinuous(long seed, int n, double driftPerSample) {
        double[]  s   = new double[n];
        Random   rng = new Random(seed);
        double   w0  = 2.0 * Math.PI * FUND_HZ / SAMPLE_RATE;          // fundamental rad/sample @ t=0
        double   a0  = Math.pow(10.0, FUND_DBFS / 20.0);
        double   lsb = 2.0 / (double) (1L << DITHER_BITS);
        int      hi  = HARMONIC_COUNT + 1;
        double[] ah     = new double[hi + 1];
        double[] twoCos = new double[hi + 1];
        double[] sPrev  = new double[hi + 1];
        double[] sCur   = new double[hi + 1];
        ah[1] = a0;
        for (int h = 2; h <= hi; h++) ah[h] = Math.pow(10.0, HARMONIC_DBFS[h - 2] / 20.0);

        final double twoPi  = 2.0 * Math.PI;
        final int    reMask = (1 << 16) - 1;
        for (int i = 0; i < n; i++) {
            if (i == 0 || (i & reMask) == 0) {                        // re-seed phase + local frequency
                double driftI = 1.0 + driftPerSample * i;             // instantaneous relative frequency
                for (int h = 1; h <= hi; h++) {
                    double pCur  = h * w0 * (i      + 0.5 * driftPerSample * (double) i * i);
                    double pPrev = h * w0 * ((i - 1) + 0.5 * driftPerSample * (double) (i - 1) * (i - 1));
                    sCur[h]   = Math.sin(pCur  - twoPi * Math.rint(pCur  / twoPi));
                    sPrev[h]  = Math.sin(pPrev - twoPi * Math.rint(pPrev / twoPi));
                    twoCos[h] = 2.0 * Math.cos(h * w0 * driftI);
                }
            }
            double v = 0.0;
            for (int h = 1; h <= hi; h++) {
                v += ah[h] * sCur[h];
                double next = twoCos[h] * sCur[h] - sPrev[h];
                sPrev[h] = sCur[h];
                sCur[h]  = next;
            }
            v += NOISE_STD * rng.nextGaussian();
            double dither = (rng.nextDouble() - 0.5 + rng.nextDouble() - 0.5) * lsb;
            s[i] = (double) (Math.rint((v + dither) / lsb) * lsb);
        }
        return s;
    }
}
