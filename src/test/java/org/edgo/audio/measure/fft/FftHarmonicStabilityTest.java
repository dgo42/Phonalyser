package org.edgo.audio.measure.fft;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.WindowType;
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
 * through the coherent-averaging FFT over {@link #FRAMES} frames, and repeats
 * {@link #COLLECTIONS} times (independent noise seeds) to measure the run-to-run
 * SPREAD of each harmonic — the "drift" we have been chasing by overlaying
 * screenshots.  All results go to an INDEPENDENT log
 * ({@code target/fft-harmonic-stability.log}, see {@code log4j2-test.xml}).
 *
 * <p><b>Scope note:</b> a single {@code analyze()} buffer carries NO inter-frame
 * clock drift (the frames sit at exact sample offsets), so this isolates the
 * MEASUREMENT + intra-capture coherent averaging.  If the harmonics come out
 * stable here, the live drift is the cross-tick CLOCK, not the analysis math — a
 * useful thing to pin down deterministically.  A cross-tick (worker) harness
 * with a simulated drifting offset is a separate, larger step.
 *
 * <p>Every generator/FFT knob is a {@code static final} constant below.
 */
class FftHarmonicStabilityTest {

    // ── Generator & FFT settings (tune here) ───────────────────────────────
    private static final int        SAMPLE_RATE    = 384_000;          // Hz
    private static final int        FFT_SIZE       = 65_536;           // 2^16 (real rig: 2_097_152 — slow)
    private static final WindowType WINDOW         = WindowType.BLACKMAN_HARRIS_7;
    private static final FftOverlap OVERLAP        = FftOverlap.PCT_75;
    private static final int        HARMONIC_COUNT = 8;                // H2..H9
    private static final boolean    COHERENT       = true;
    private static final int        FRAMES         = 400;              // "averages" per collection
    private static final int        COLLECTIONS    = 20;

    //private static final double     FUND_HZ        = 1003.0517578125;  // off-bin, like the rig
    private static final double     FUND_HZ        = 1001.953125;

    private static final double     FUND_DBFS      = -79.74;           // fundamental level (dBFS)
    /** H2..H9 levels as ABSOLUTE dBFS (NOT relative to the fundamental) — the
     *  ADC's distortion sits at a fixed level regardless of signal strength. */
    private static final double[]   HARMONIC_DBFS  = {
            -130.0, -135.0, -150.0, -145.0, -158.0, -160.0, -157.0, -162.0
    };
    private static final int        DITHER_BITS    = 27;               // ADC word length (TPDF dither)
    private static final double     NOISE_STD      = 3.0e-6;           // broadband floor std (tune for target floor)
    private static final long       SEED           = 20_260_604L;

    private static final Logger LOG = LogManager.getLogger("FftHarmonicStability");

    @Test
    void harmonicStabilityAcrossCollections() {
        FftAnalyzer analyzer = new FftAnalyzer();
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
            float[] sig = synthesize(SEED + c, FRAMES, OVERLAP);
            FftResult r = analyzer.analyze(sig, SAMPLE_RATE, FFT_SIZE, HARMONIC_COUNT,
                    WINDOW, OVERLAP, 0.0, 0.0, COHERENT, Double.NaN, false);

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
                // sin→complex offset (each tone is −π/2), which the harmonics carry
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

    // ── Frames × overlap sweep ─────────────────────────────────────────────
    private static final int[]        FRAMES_SWEEP      = {25, 100, 400};
    private static final FftOverlap[] OVERLAP_SWEEP     = {
            FftOverlap.PCT_0, FftOverlap.PCT_50, FftOverlap.PCT_75, FftOverlap.PCT_87_5
    };
    private static final int          SWEEP_COLLECTIONS = 8;

    /**
     * Depth-vs-stability map: for each (frames × overlap) cell, the peak-pick
     * spread of every harmonic across {@link #SWEEP_COLLECTIONS} independent noise
     * seeds.  {@code eff = frames × (1 − overlap)} is the independent-frame span —
     * the real noise-averaging depth.  If the near-floor spread tracks
     * {@code 1/√eff} (halving per 4× eff), it is purely averaging-limited.
     */
    @Test
    void harmonicSpreadSweep() {
        FftAnalyzer analyzer = new FftAnalyzer();
        LOG.info("=== SWEEP: peak-pick spread (dB) vs frames x overlap | {} pt {} | {} Hz | {} seeds/cell ===",
                FFT_SIZE, WINDOW, FUND_HZ, SWEEP_COLLECTIONS);
        for (int frames : FRAMES_SWEEP) {
            for (FftOverlap ov : OVERLAP_SWEEP) {
                double[][] h = new double[HARMONIC_COUNT][SWEEP_COLLECTIONS];
                for (int c = 0; c < SWEEP_COLLECTIONS; c++) {
                    float[] sig = synthesize(SEED + c, frames, ov);
                    FftResult r = analyzer.analyze(sig, SAMPLE_RATE, FFT_SIZE, HARMONIC_COUNT,
                            WINDOW, ov, 0.0, 0.0, COHERENT, Double.NaN, false);
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

    // ── Plateau check: spread vs frames at FIXED overlap ───────────────────
    private static final int[]      PLATEAU_FRAMES  = {25, 100, 400, 1600};
    private static final FftOverlap PLATEAU_OVERLAP = FftOverlap.PCT_75;   // fixed → window factor constant
    private static final int        PLATEAU_SEEDS   = 40;

    /**
     * Settles whether the near-floor harmonics keep averaging down or hit a floor.
     * Overlap is held FIXED (so the window/overlap-add gain is a constant) and only
     * the frame count varies — at fixed hop the buffer length, hence the independent
     * signal, is ∝ frames, so this is a clean 1/√N test.  Reports σ (std dev), which
     * tracks √N far more cleanly than max−min: pure averaging ⇒ σ halves per 4× frames
     * (−6 dB of σ per decade-ish); a flat σ tail ⇒ a systematic floor averaging can't beat.
     */
    @Test
    void harmonicPlateauCheck() {
        FftAnalyzer analyzer = new FftAnalyzer();
        LOG.info("=== PLATEAU CHECK: spread vs frames @ fixed {} overlap | {} seeds | {} Hz ===",
                PLATEAU_OVERLAP.label, PLATEAU_SEEDS, FUND_HZ);
        LOG.info("  pure 1/√N ⇒ σ halves per 4× frames.  Flat σ tail ⇒ systematic floor.  (σ = std dev, dB)");
        for (int frames : PLATEAU_FRAMES) {
            double[][] h = new double[HARMONIC_COUNT][PLATEAU_SEEDS];
            for (int c = 0; c < PLATEAU_SEEDS; c++) {
                float[] sig = synthesize(SEED + c, frames, PLATEAU_OVERLAP);
                FftResult r = analyzer.analyze(sig, SAMPLE_RATE, FFT_SIZE, HARMONIC_COUNT,
                        WINDOW, PLATEAU_OVERLAP, 0.0, 0.0, COHERENT, Double.NaN, false);
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

    // ── κ-refit A/B: "rotate the fork" ─────────────────────────────────────
    private static final int[] KAPPA_FRAMES = {400, 1600};   // near-minimum, up-branch
    private static final int   KAPPA_SEEDS  = 24;
    private static final FftOverlap KAPPA_OVERLAP = FftOverlap.PCT_75;

    /**
     * Separates the "rotate the fork" idea into its two levers, against the
     * synthetic ground truth (we know the true κ exactly).  Four de-rotation
     * frequencies are compared on the SAME stored per-frame spectra:
     * <ul>
     *   <li><b>analyze</b> — one-hop phase difference (what {@code analyze()} does today),</li>
     *   <li><b>fund-full</b> — κ refit over ALL frames using only the fundamental,</li>
     *   <li><b>comb-full</b> — κ refit over all frames using the whole comb (h× leverage),</li>
     *   <li><b>oracle</b> — the true κ (no estimation error at all).</li>
     * </ul>
     * Logs per-harmonic σ (does a better κ flatten the drift branch toward oracle?)
     * and mean |κ̂ − κ_true| (does the full-integration / comb refit actually shrink
     * the frequency-reference error?).  oracle σ ≈ the √N floor proves the up-branch
     * is κ-estimate error, not drift.
     */
    @Test
    void kappaRefitAB() {
        FftAnalyzer analyzer = new FftAnalyzer();
        double[] window = analyzer.buildWindow(FFT_SIZE, WINDOW);
        double cohGain = 0.0;
        for (double v : window) cohGain += v;
        cohGain /= FFT_SIZE;
        double normFactor = 1.0 / ((double) FFT_SIZE * cohGain);
        double kOracle = FUND_HZ * FFT_SIZE / (double) SAMPLE_RATE;   // true fractional bin
        int    k0      = (int) Math.round(kOracle);
        int    hi      = HARMONIC_COUNT + 1;
        int[]  bins    = new int[hi + 1];
        for (int h = 1; h <= hi; h++) bins[h] = h * k0;              // on-bin harmonic peak bins

        LOG.info("=== κ-REFIT A/B: harmonic σ & κ-error vs κ source | {} seeds | {} Hz | κ_true={} ===",
                KAPPA_SEEDS, FUND_HZ, kOracle);
        LOG.info("  κ sources: analyze(1-hop) | fund-full(M-frame) | comb-full(M-frame) | oracle(true)");

        for (int frames : KAPPA_FRAMES) {
            int hop = Math.max(1, (int) Math.round(FFT_SIZE * (1.0 - KAPPA_OVERLAP.fraction)));
            double[][] hAna = new double[HARMONIC_COUNT][KAPPA_SEEDS];
            double[][] hFun = new double[HARMONIC_COUNT][KAPPA_SEEDS];
            double[][] hCom = new double[HARMONIC_COUNT][KAPPA_SEEDS];
            double[][] hOra = new double[HARMONIC_COUNT][KAPPA_SEEDS];
            double[]   eAna = new double[KAPPA_SEEDS];
            double[]   eFun = new double[KAPPA_SEEDS];
            double[]   eCom = new double[KAPPA_SEEDS];

            for (int c = 0; c < KAPPA_SEEDS; c++) {
                float[] sig = synthesize(SEED + c, frames, KAPPA_OVERLAP);
                double[][] xr = new double[frames][hi + 1];          // per-frame Re at fund+harmonic bins
                double[][] xi = new double[frames][hi + 1];
                double[] re = new double[FFT_SIZE], im = new double[FFT_SIZE];
                for (int f = 0; f < frames; f++) {
                    int base = f * hop;
                    for (int n = 0; n < FFT_SIZE; n++) { re[n] = sig[base + n] * window[n]; im[n] = 0.0; }
                    Fft.forward(re, im);
                    for (int h = 1; h <= hi; h++) { xr[f][h] = re[bins[h]]; xi[f][h] = im[bins[h]]; }
                }
                double kAna = kappaOneHop(xr, xi, k0, hop, FFT_SIZE);
                double kFun = kappaRefit(xr, xi, hop, frames, FFT_SIZE, k0, 1);   // fundamental only
                double kCom = kappaRefit(xr, xi, hop, frames, FFT_SIZE, k0, hi);  // full comb
                fillHarm(hAna, c, xr, xi, hop, frames, FFT_SIZE, kAna, normFactor);
                fillHarm(hFun, c, xr, xi, hop, frames, FFT_SIZE, kFun, normFactor);
                fillHarm(hCom, c, xr, xi, hop, frames, FFT_SIZE, kCom, normFactor);
                fillHarm(hOra, c, xr, xi, hop, frames, FFT_SIZE, kOracle, normFactor);
                eAna[c] = Math.abs(kAna - kOracle);
                eFun[c] = Math.abs(kFun - kOracle);
                eCom[c] = Math.abs(kCom - kOracle);
            }
            double bufMs = 1000.0 * (FFT_SIZE + (frames - 1L) * hop) / SAMPLE_RATE;
            LOG.info(String.format("--- frames=%d  (buf=%.0f ms) ---", frames, bufMs));
            for (int i = 0; i < HARMONIC_COUNT; i++) {
                LOG.info(String.format("  H%d σ(dB):  analyze=%5.2f  fund-full=%5.2f  comb-full=%5.2f  oracle=%5.2f",
                        i + 2, std(hAna[i]), std(hFun[i]), std(hCom[i]), std(hOra[i])));
            }
            LOG.info(String.format("  κ-error (bins): analyze=%.2e  fund-full=%.2e  comb-full=%.2e",
                    mean(eAna), mean(eFun), mean(eCom)));
        }
    }

    /** One-hop fundamental phase-difference κ — replicates {@code analyze()}'s estimator. */
    private static double kappaOneHop(double[][] xr, double[][] xi, int k0, int hop, int fftSize) {
        double phi0 = Math.atan2(xi[0][1], xr[0][1]);
        double phi1 = Math.atan2(xi[1][1], xr[1][1]);
        double expected = 2.0 * Math.PI * k0 * hop / (double) fftSize;
        double raw      = phi1 - phi0;
        long   m        = Math.round((raw - expected) / (2.0 * Math.PI));
        return (raw - 2.0 * Math.PI * m) * fftSize / (2.0 * Math.PI * hop);
    }

    /** κ that maximizes the coherent comb energy Σ|Σ_f Xₕ·e^{j·h·Φ}| over all frames,
     *  golden-section searched in [k0−0.5, k0+0.5].  hMax=1 → fundamental only. */
    private static double kappaRefit(double[][] xr, double[][] xi, int hop, int frames,
                                     int fftSize, int k0, int hMax) {
        double lo = k0 - 0.5, hi = k0 + 0.5;
        final double R = (Math.sqrt(5.0) - 1.0) / 2.0;   // 0.618
        double a = lo, b = hi;
        double x1 = b - R * (b - a), x2 = a + R * (b - a);
        double f1 = combEnergy(xr, xi, hop, frames, fftSize, hMax, x1);
        double f2 = combEnergy(xr, xi, hop, frames, fftSize, hMax, x2);
        for (int it = 0; it < 40; it++) {
            if (f1 < f2) { a = x1; x1 = x2; f1 = f2; x2 = a + R * (b - a); f2 = combEnergy(xr, xi, hop, frames, fftSize, hMax, x2); }
            else         { b = x2; x2 = x1; f2 = f1; x1 = b - R * (b - a); f1 = combEnergy(xr, xi, hop, frames, fftSize, hMax, x1); }
        }
        return 0.5 * (a + b);
    }

    private static double combEnergy(double[][] xr, double[][] xi, int hop, int frames,
                                     int fftSize, int hMax, double kappa) {
        double total = 0.0;
        for (int h = 1; h <= hMax; h++) {
            double sr = 0.0, si = 0.0;
            double w = -2.0 * Math.PI * kappa * h / (double) fftSize;   // h·Φ per unit sample-offset
            for (int f = 0; f < frames; f++) {
                double ph = w * ((long) f * hop);
                double cs = Math.cos(ph), sn = Math.sin(ph);
                sr += xr[f][h] * cs - xi[f][h] * sn;
                si += xr[f][h] * sn + xi[f][h] * cs;
            }
            total += Math.hypot(sr, si);
        }
        return total;
    }

    /** De-rotate each harmonic's stored bins with the given κ and store dBFS. */
    private static void fillHarm(double[][] out, int c, double[][] xr, double[][] xi,
                                 int hop, int frames, int fftSize, double kappa, double normFactor) {
        for (int i = 0; i < HARMONIC_COUNT; i++) {
            int h = i + 2;
            double sr = 0.0, si = 0.0;
            double w = -2.0 * Math.PI * kappa * h / (double) fftSize;
            for (int f = 0; f < frames; f++) {
                double ph = w * ((long) f * hop);
                double cs = Math.cos(ph), sn = Math.sin(ph);
                sr += xr[f][h] * cs - xi[f][h] * sn;
                si += xr[f][h] * sn + xi[f][h] * cs;
            }
            double amp = Math.hypot(sr, si) / frames;
            out[i][c] = 20.0 * Math.log10(amp * normFactor * 2.0 + 1e-300);
        }
    }

    private static double mean(double[] a) {
        double s = 0.0;
        for (double x : a) s += x;
        return s / a.length;
    }

    /** One collection's buffer: tone + H2..H9 + Gaussian floor, TPDF-dithered to
     *  {@link #DITHER_BITS}.  Length = exactly {@link #FRAMES} frames at the chosen
     *  overlap, so {@code analyze()} averages that many. */
    private static float[] synthesize(long seed, int frames, FftOverlap overlap) {
        int     hop = Math.max(1, (int) Math.round(FFT_SIZE * (1.0 - overlap.fraction)));
        int     n   = FFT_SIZE + (frames - 1) * hop;
        float[] s   = new float[n];
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
            s[i] = (float) (Math.rint((v + dither) / lsb) * lsb);                      // quantize to N bits
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
