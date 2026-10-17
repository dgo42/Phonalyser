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

package org.edgo.audio.measure.fft;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.WindowType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deterministic synthetic harmonic-stability harness.
 *
 * <p>Builds an off-bin tone + H2..H9 at predefined levels + a broadband noise
 * floor (Gaussian) that is then TPDF-dithered to {@link #DITHER_BITS}, runs it
 * through the coherent capture average over {@link #FRAMES} frames, and repeats
 * {@link #COLLECTIONS} times (independent noise seeds) to measure the run-to-run
 * SPREAD of each harmonic - the "drift" we have been chasing by overlaying
 * screenshots.  All results go to an INDEPENDENT log
 * ({@code target/fft-harmonic-stability.log}, see {@code log4j2-test.xml}).
 *
 * <p><b>Scope note:</b> a single {@code analyze()} buffer carries NO inter-frame
 * clock drift (the frames sit at exact sample offsets), so this isolates the
 * MEASUREMENT + intra-capture coherent averaging.  If the harmonics come out
 * stable here, the live drift is the cross-cycle CLOCK, not the analysis math -
 * a useful thing to pin down deterministically.  A cross-cycle (worker) harness
 * with a simulated drifting offset is a separate, larger step.
 *
 * <p>Every generator/FFT knob is a {@code static final} constant below.
 */
@Tag("exploratory")   // slow diagnostic harness - excluded from the normal build (see pom surefire)
class FftHarmonicStabilityTest {

    // ── Generator & FFT settings (tune here) ───────────────────────────────
    private static final int        SAMPLE_RATE    = 384_000;          // Hz
    private static final int        FFT_SIZE       = 65_536;           // 2^16 (real rig: 2_097_152 - slow)
    private static final WindowType WINDOW         = WindowType.BH7;
    private static final FftOverlap OVERLAP        = FftOverlap.PCT_75;
    private static final int        HARMONIC_COUNT = 8;                // H2..H9
    private static final boolean    COHERENT       = true;
    private static final int        FRAMES         = 400;              // "averages" per collection
    private static final int        COLLECTIONS    = 20;

    //private static final double     FUND_HZ        = 1003.0517578125;  // off-bin, like the rig
    private static final double     FUND_HZ        = 1001.953125;

    private static final double     FUND_DBFS      = -79.74;           // fundamental level (dBFS)
    /** H2..H9 levels as ABSOLUTE dBFS (NOT relative to the fundamental) - the
     *  ADC's distortion sits at a fixed level regardless of signal strength. */
    private static final double[]   HARMONIC_DBFS  = {
            -130.0, -135.0, -150.0, -145.0, -158.0, -160.0, -157.0, -162.0
    };
    private static final int        DITHER_BITS    = 27;               // ADC word length (TPDF dither)
    private static final double     NOISE_STD      = 3.0e-6;           // broadband floor std (tune for target floor)
    private static final long       SEED           = 20_260_604L;

    private static final Logger LOG = LogManager.getLogger("FftHarmonicStability");

    // ── Frames × overlap sweep ─────────────────────────────────────────────
    private static final int[]        FRAMES_SWEEP      = {25, 100, 400};
    private static final FftOverlap[] OVERLAP_SWEEP     = {
            FftOverlap.PCT_0, FftOverlap.PCT_50, FftOverlap.PCT_75, FftOverlap.PCT_87_5
    };
    private static final int          SWEEP_COLLECTIONS = 8;

    // ── Plateau check: spread vs frames at FIXED overlap ───────────────────
    private static final int[]      PLATEAU_FRAMES  = {25, 100, 400, 1600};
    private static final FftOverlap PLATEAU_OVERLAP = FftOverlap.PCT_75;   // fixed -> window factor constant
    private static final int        PLATEAU_SEEDS   = 40;

    private CaptureAverage captureAverage() {
        return new CaptureAverage(new FftAnalyzer(), new CoherentAccumulator());
    }

    @Test
    void harmonicStabilityAcrossCollections() {
        CaptureAverage analyzer = captureAverage();
        double[][] hDbFs    = new double[HARMONIC_COUNT][COLLECTIONS];  // existing peak-pick (window search)
        double[][] singleDb = new double[HARMONIC_COUNT][COLLECTIONS];  // single-bin magnitude @ theoretical bin
        double[][] lockDb   = new double[HARMONIC_COUNT][COLLECTIONS];  // phase-sensitive (lock-in) in-phase
        double[]   thd   = new double[COLLECTIONS];
        double[]   snr   = new double[COLLECTIONS];

        LOG.info("=== harmonic stability: {} collections x {} frames | {} pt {} {} | {} Hz ===",
                COLLECTIONS, FRAMES, FFT_SIZE, WINDOW, OVERLAP.label, SAMPLE_RATE);
        LOG.info("fundamental {} dBFS @ {} Hz | harmonics(abs dBFS)={} | noise std={} + {}-bit dither",
                FUND_DBFS, FUND_HZ, Arrays.toString(HARMONIC_DBFS), NOISE_STD, DITHER_BITS);

        for (int c = 0; c < COLLECTIONS; c++) {
            double[] sig = synthesize(SEED + c, FRAMES, OVERLAP);
            FftResult r = analyzer.analyze(sig, SAMPLE_RATE, FFT_SIZE, HARMONIC_COUNT,
                    WINDOW, OVERLAP, 0.0, 0.0, COHERENT, Double.NaN, false, Double.NaN);

            thd[c] = r.thdPct;
            snr[c] = r.snrDb;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < HARMONIC_COUNT; i++) {
                hDbFs[i][c] = (r.harmonicDbFs != null && i < r.harmonicDbFs.length)
                        ? r.harmonicDbFs[i] : Double.NaN;
                sb.append(String.format("  H%d=%.2f", i + 2, hDbFs[i][c]));
            }
            LOG.info(String.format(
                    "collection %2d: fund=%.2f dBFS @ %.4f Hz | floor=%.1f dBFS  SNR=%.2f dB  THD=%.6f%% |%s",
                    c, r.fundamentalDbFs, r.fundamentalHzRefined, r.avgNoiseFloorDbFs, snr[c], thd[c], sb));
            assertTrue(r.fundamentalBin > 0, "fundamental not detected in collection " + c);

            // Phase-sensitive (lock-in) read-out: project each harmonic's averaged
            // complex value onto h× the fundamental's measured phase.  The in-phase
            // component IS the harmonic (the orthogonal-noise half-plane is rejected),
            // so it should hold steadier than the peak-pick magnitude for sub-floor
            // tones.  Single-bin magnitude at the same bin is the middle reference.
            double fundPh = Math.atan2(r.im[r.fundamentalBin], r.re[r.fundamentalBin]);
            for (int i = 0; i < HARMONIC_COUNT; i++) {
                int kH = (int) Math.round((i + 2) * r.fundamentalHzRefined / r.freqResolution);
                if (kH < 1 || kH >= r.re.length) { singleDb[i][c] = lockDb[i][c] = Double.NaN; continue; }
                double re = r.re[kH], im = r.im[kH], mag = Math.hypot(re, im);
                // h·fundPh aligns the time-shift part; the (h-1)·π/2 term is the
                // sin->complex offset (each tone is −π/2), which the harmonics carry
                // h× relative to the fundamental.  In a real rig this fixed offset
                // would come from a phase calibration, not be assumed.
                double ref = (i + 2) * fundPh + (i + 1) * (Math.PI / 2.0);
                double inPhase = re * Math.cos(ref) + im * Math.sin(ref);
                singleDb[i][c] = r.amplitudeDbFs[kH];
                lockDb[i][c]   = r.amplitudeDbFs[kH]
                               + 20.0 * Math.log10(Math.abs(inPhase) / (mag + 1e-300) + 1e-300);
            }
        }

        // Spread (max - min) per harmonic across the collections == the drift.
        LOG.info("── spread across {} collections (max-min) ──", COLLECTIONS);
        for (int i = 0; i < HARMONIC_COUNT; i++) {
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY, sum = 0.0;
            for (int c = 0; c < COLLECTIONS; c++) {
                min  = Math.min(min, hDbFs[i][c]);
                max  = Math.max(max, hDbFs[i][c]);
                sum += hDbFs[i][c];
            }
            LOG.info(String.format("  H%d: mean=%8.2f dBFS  spread=%6.2f dB  [min=%.2f max=%.2f]",
                    i + 2, sum / COLLECTIONS, max - min, min, max));
        }
        LOG.info(String.format("  THD spread=%.6f%%   SNR spread=%.2f dB", spread(thd), spread(snr)));

        // A/B: peak-pick magnitude vs single-bin magnitude vs phase-sensitive lock-in.
        LOG.info("── spread A/B: peak-pick | single-bin | LOCK-IN (max-min, dB) ──");
        for (int i = 0; i < HARMONIC_COUNT; i++) {
            LOG.info(String.format("  H%d:  peak-pick=%6.2f   single-bin=%6.2f   lock-in=%6.2f",
                    i + 2, spread(hDbFs[i]), spread(singleDb[i]), spread(lockDb[i])));
        }

        assertEquals(COLLECTIONS, thd.length);
    }

    /**
     * Depth-vs-stability map: for each (frames × overlap) cell, the peak-pick
     * spread of every harmonic across {@link #SWEEP_COLLECTIONS} independent noise
     * seeds.  {@code eff = frames × (1 − overlap)} is the independent-frame span -
     * the real noise-averaging depth.  If the near-floor spread tracks
     * {@code 1/√eff} (halving per 4× eff), it is purely averaging-limited.
     */
    @Test
    void harmonicSpreadSweep() {
        CaptureAverage analyzer = captureAverage();
        LOG.info("=== SWEEP: peak-pick spread (dB) vs frames x overlap | {} pt {} | {} Hz | {} seeds/cell ===",
                FFT_SIZE, WINDOW, FUND_HZ, SWEEP_COLLECTIONS);
        for (int frames : FRAMES_SWEEP) {
            for (FftOverlap ov : OVERLAP_SWEEP) {
                double[][] h = new double[HARMONIC_COUNT][SWEEP_COLLECTIONS];
                for (int c = 0; c < SWEEP_COLLECTIONS; c++) {
                    double[] sig = synthesize(SEED + c, frames, ov);
                    FftResult r = analyzer.analyze(sig, SAMPLE_RATE, FFT_SIZE, HARMONIC_COUNT,
                            WINDOW, ov, 0.0, 0.0, COHERENT, Double.NaN, false, Double.NaN);
                    for (int i = 0; i < HARMONIC_COUNT; i++) {
                        h[i][c] = (r.harmonicDbFs != null && i < r.harmonicDbFs.length)
                                ? r.harmonicDbFs[i] : Double.NaN;
                    }
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < HARMONIC_COUNT; i++) sb.append(String.format(" H%d=%5.2f", i + 2, spread(h[i])));
                int hop = Math.max(1, (int) Math.round(FFT_SIZE * (1.0 - ov.fraction)));
                double bufMs = 1000.0 * (FFT_SIZE + (frames - 1L) * hop) / SAMPLE_RATE;
                LOG.info(String.format("frames=%4d  overlap=%-6s  buf=%6.0f ms :%s", frames, ov.label, bufMs, sb));
            }
        }
    }

    /**
     * Settles whether the near-floor harmonics keep averaging down or hit a floor.
     * Overlap is held FIXED (so the window/overlap-add gain is a constant) and only
     * the frame count varies - at fixed hop the buffer length, hence the independent
     * signal, is ∝ frames, so this is a clean 1/√N test.  Reports σ (std dev), which
     * tracks √N far more cleanly than max−min: pure averaging => σ halves per 4× frames
     * (−6 dB of σ per decade-ish); a flat σ tail => a systematic floor averaging can't beat.
     */
    @Test
    void harmonicPlateauCheck() {
        CaptureAverage analyzer = captureAverage();
        LOG.info("=== PLATEAU CHECK: spread vs frames @ fixed {} overlap | {} seeds | {} Hz ===",
                PLATEAU_OVERLAP.label, PLATEAU_SEEDS, FUND_HZ);
        LOG.info("  pure 1/√N => σ halves per 4× frames.  Flat σ tail => systematic floor.  (σ = std dev, dB)");
        for (int frames : PLATEAU_FRAMES) {
            double[][] h = new double[HARMONIC_COUNT][PLATEAU_SEEDS];
            for (int c = 0; c < PLATEAU_SEEDS; c++) {
                double[] sig = synthesize(SEED + c, frames, PLATEAU_OVERLAP);
                FftResult r = analyzer.analyze(sig, SAMPLE_RATE, FFT_SIZE, HARMONIC_COUNT,
                        WINDOW, PLATEAU_OVERLAP, 0.0, 0.0, COHERENT, Double.NaN, false, Double.NaN);
                for (int i = 0; i < HARMONIC_COUNT; i++) {
                    h[i][c] = (r.harmonicDbFs != null && i < r.harmonicDbFs.length)
                            ? r.harmonicDbFs[i] : Double.NaN;
                }
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < HARMONIC_COUNT; i++) sb.append(String.format(" H%d=%4.2f", i + 2, std(h[i])));
            LOG.info(String.format("frames=%5d  σ(dB):%s", frames, sb));
        }
    }

    /** One collection's buffer: tone + H2..H9 + Gaussian floor, TPDF-dithered to
     *  {@link #DITHER_BITS}.  Length = exactly {@link #FRAMES} frames at the chosen
     *  overlap, so {@code analyze()} averages that many. */
    private static double[] synthesize(long seed, int frames, FftOverlap overlap) {
        int     hop = Math.max(1, (int) Math.round(FFT_SIZE * (1.0 - overlap.fraction)));
        int     n   = FFT_SIZE + (frames - 1) * hop;
        double[] s   = new double[n];
        Random  rng = new Random(seed);
        double  w0  = 2.0 * Math.PI * FUND_HZ / SAMPLE_RATE;
        double  a0  = Math.pow(10.0, FUND_DBFS / 20.0);
        double  lsb = 2.0 / (double) (1L << DITHER_BITS);
        int     hi  = HARMONIC_COUNT + 1;                          // highest harmonic order present
        double[] ah     = new double[hi + 1];                      // ah[h] = amplitude of harmonic h
        double[] twoCos = new double[hi + 1];                      // 3-term sine recurrence coefficients
        double[] sPrev  = new double[hi + 1];                      // sin(h·w0·(i-1))
        double[] sCur   = new double[hi + 1];                      // sin(h·w0·i)
        ah[1] = a0;
        for (int h = 2; h <= hi; h++) ah[h] = Math.pow(10.0, HARMONIC_DBFS[h - 2] / 20.0);  // absolute
        for (int h = 1; h <= hi; h++) {                            // sin((i+1)θ)=2cosθ·sin(iθ)−sin((i−1)θ)
            twoCos[h] = 2.0 * Math.cos(h * w0);
            sPrev[h]  = -Math.sin(h * w0);                         // sin(−θ)
            sCur[h]   = 0.0;                                       // sin(0)
        }
        final double twoPi = 2.0 * Math.PI;
        final int    reMask = (1 << 16) - 1;                       // re-seed oscillators every 2^16 samples
        for (int i = 0; i < n; i++) {
            if (i > 0 && (i & reMask) == 0) {                      // bound 3-term recurrence drift on long buffers
                for (int h = 1; h <= hi; h++) {
                    double p0 = h * w0 * i, p1 = h * w0 * (i - 1);
                    sCur[h]  = Math.sin(p0 - twoPi * Math.rint(p0 / twoPi));
                    sPrev[h] = Math.sin(p1 - twoPi * Math.rint(p1 / twoPi));
                }
            }
            double v = 0.0;
            for (int h = 1; h <= hi; h++) {
                v += ah[h] * sCur[h];
                double next = twoCos[h] * sCur[h] - sPrev[h];      // advance the oscillator one sample
                sPrev[h] = sCur[h];
                sCur[h]  = next;
            }
            v += NOISE_STD * rng.nextGaussian();                                       // broadband floor
            double dither = (rng.nextDouble() - 0.5 + rng.nextDouble() - 0.5) * lsb;   // TPDF, ±1 LSB
            s[i] = (double) (Math.rint((v + dither) / lsb) * lsb);                      // quantize to N bits
        }
        return s;
    }

    private static double spread(double[] a) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double x : a) { min = Math.min(min, x); max = Math.max(max, x); }
        return max - min;
    }

    private static double std(double[] a) {
        double mean = 0.0;
        for (double x : a) mean += x;
        mean /= a.length;
        double v = 0.0;
        for (double x : a) v += (x - mean) * (x - mean);
        return Math.sqrt(v / a.length);
    }
}
