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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.edgo.audio.measure.common.SpectrumStatistics;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.fft.ReadoutSettings;
import org.edgo.audio.measure.fft.SpectrumAccumulator;
import org.edgo.audio.measure.test.AbstractReflectiveTest;
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
 * floor (Gaussian) that is then TPDF-dithered to {@link #DITHER_BITS}, averages
 * it coherently over {@link #FRAMES} frames, and repeats {@link #COLLECTIONS}
 * times (independent noise seeds) to measure the run-to-run SPREAD of each
 * harmonic - the "drift" we have been chasing by overlaying screenshots.  All
 * results go to an INDEPENDENT log ({@code target/fft-harmonic-stability.log},
 * see {@code log4j2-test.xml}).
 *
 * <p>The average is collected the way the live FFT collects it: every frame one
 * analysis cycle, analysed with the worker's own settings and added by the
 * worker's private accumulate step (reached by reflection), then written back
 * through the worker's accumulator and the statistics calculated.
 *
 * <p><b>Scope note:</b> the frames of one buffer carry NO inter-frame clock
 * drift (they sit at exact sample offsets), so this isolates the MEASUREMENT +
 * coherent averaging.  If the harmonics come out stable here, the live drift is
 * the cross-cycle CLOCK, not the analysis math - a useful thing to pin down
 * deterministically.  {@link FftCrossCycleDriftTest} adds a drifting clock.
 *
 * <p>Every generator/FFT knob is a {@code static final} constant below.
 */
@Tag("exploratory")   // slow diagnostic harness - excluded from the normal build (see pom surefire)
class FftHarmonicStabilityTest extends AbstractReflectiveTest {

    // ── Generator & FFT settings (tune here) ───────────────────────────────
    private static final int        SAMPLE_RATE    = 384_000;          // Hz
    private static final int        FFT_SIZE       = 65_536;           // 2^16 (real rig: 2_097_152 - slow)
    private static final WindowType WINDOW         = WindowType.BH7;
    private static final FftOverlap OVERLAP        = FftOverlap.PCT_75;
    private static final int        HARMONIC_COUNT = 8;                // H2..H9
    /** The readout settings: H2..H9, no distortion band, no manual
     *  fundamental. */
    private static final ReadoutSettings SETTINGS  =
            new ReadoutSettings(HARMONIC_COUNT, HARMONIC_COUNT, 0.0, 0.0, Double.NaN);
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

    /** The worker members the collection drives: the private accumulate step
     *  and the private accumulator its average is written back from. */
    private static final String ACCUMULATE  = "accumulateIntoForeverBuffer";
    private static final String ACCUMULATOR = "accumulator";

    /** The coherent average of every frame {@code sig} holds at
     *  {@code overlap}'s spacing, as the live FFT collects it: each frame one
     *  analysis cycle - analysed with the worker's own settings and added by
     *  the worker's accumulate step at its sample offset - then the
     *  accumulator written back onto the last cycle's result and the
     *  statistics calculated.
     *
     * @param sig     the capture
     * @param overlap the frame spacing
     * @return the average */
    private FftResult average(double[] sig, FftOverlap overlap) {
        FftAnalyzer       analyzer = new FftAnalyzer();
        FftAnalyzerWorker worker   = new FftAnalyzerWorker(null);   // headless
        int       hop    = overlap.hopSamples(FFT_SIZE);
        int       frames = (sig.length - FFT_SIZE) / hop + 1;
        double[]  win    = new double[FFT_SIZE];
        FftResult r      = null;
        for (int f = 0; f < frames; f++) {
            long base = (long) f * hop;
            System.arraycopy(sig, (int) base, win, 0, FFT_SIZE);
            analyzer.setMultiTone(false);
            analyzer.setSecondToneHintHz(Double.NaN);
            r = analyzer.analyze(win, SAMPLE_RATE, FFT_SIZE, SETTINGS,
                    WINDOW, overlap, COHERENT, Double.NaN,
                    new FftResult(), hop);
            call(worker, ACCUMULATE, r, base, COHERENT, Integer.MAX_VALUE);
        }
        this.<SpectrumAccumulator>field(worker, ACCUMULATOR).overlayOnto(r);
        r.captureRawPeaks();
        new SpectrumStatistics().calculateStat(r, SETTINGS, null);
        return r;
    }

    /** The level harmonic {@code i + 2} is displayed at.
     *
     * @param r the average
     * @param i the harmonic's index, 0 for H2
     * @return the level, dBFS; NaN where the result carries no such harmonic */
    private double harmonicDbFs(FftResult r, int i) {
        return (r.harmonics != null && i < r.harmonics.length)
                ? r.harmonics[i].deembeddedAmplitudeDbFs() : Double.NaN;
    }

    /** The run-to-run spread of every harmonic over independent noise seeds,
     *  read three ways: the displayed level, the single bin, the lock-in. */
    @Test
    void harmonicStabilityAcrossCollections() {
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
            FftResult r = average(sig, OVERLAP);

            thd[c] = r.thdPct;
            snr[c] = r.snrDb;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < HARMONIC_COUNT; i++) {
                hDbFs[i][c] = harmonicDbFs(r, i);
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
        LOG.info("=== SWEEP: peak-pick spread (dB) vs frames x overlap | {} pt {} | {} Hz | {} seeds/cell ===",
                FFT_SIZE, WINDOW, FUND_HZ, SWEEP_COLLECTIONS);
        for (int frames : FRAMES_SWEEP) {
            for (FftOverlap ov : OVERLAP_SWEEP) {
                double[][] h = new double[HARMONIC_COUNT][SWEEP_COLLECTIONS];
                for (int c = 0; c < SWEEP_COLLECTIONS; c++) {
                    double[] sig = synthesize(SEED + c, frames, ov);
                    FftResult r = average(sig, ov);
                    for (int i = 0; i < HARMONIC_COUNT; i++) {
                        h[i][c] = harmonicDbFs(r, i);
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
        LOG.info("=== PLATEAU CHECK: spread vs frames @ fixed {} overlap | {} seeds | {} Hz ===",
                PLATEAU_OVERLAP.label, PLATEAU_SEEDS, FUND_HZ);
        LOG.info("  pure 1/√N => σ halves per 4× frames.  Flat σ tail => systematic floor.  (σ = std dev, dB)");
        for (int frames : PLATEAU_FRAMES) {
            double[][] h = new double[HARMONIC_COUNT][PLATEAU_SEEDS];
            for (int c = 0; c < PLATEAU_SEEDS; c++) {
                double[] sig = synthesize(SEED + c, frames, PLATEAU_OVERLAP);
                FftResult r = average(sig, PLATEAU_OVERLAP);
                for (int i = 0; i < HARMONIC_COUNT; i++) {
                    h[i][c] = harmonicDbFs(r, i);
                }
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < HARMONIC_COUNT; i++) sb.append(String.format(" H%d=%4.2f", i + 2, std(h[i])));
            LOG.info(String.format("frames=%5d  σ(dB):%s", frames, sb));
        }
    }

    /** One collection's buffer: tone + H2..H9 + Gaussian floor, TPDF-dithered to
     *  {@link #DITHER_BITS}.  Length = exactly {@link #FRAMES} frames at the chosen
     *  overlap, so {@code analyze()} averages that many.
     *
     * @param seed    the noise seed
     * @param frames  the frames the buffer holds
     * @param overlap the frame spacing
     * @return the buffer */
    private double[] synthesize(long seed, int frames, FftOverlap overlap) {
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

    /** The spread of a series: its largest value less its smallest.
     *
     * @param a the series
     * @return the spread */
    private double spread(double[] a) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (double x : a) { min = Math.min(min, x); max = Math.max(max, x); }
        return max - min;
    }

    /** The standard deviation of a series.
     *
     * @param a the series
     * @return the standard deviation */
    private double std(double[] a) {
        double mean = 0.0;
        for (double x : a) mean += x;
        mean /= a.length;
        double v = 0.0;
        for (double x : a) v += (x - mean) * (x - mean);
        return Math.sqrt(v / a.length);
    }
}
