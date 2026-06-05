/*
 * Phonalyser — precision audio measurement workbench.
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
import java.util.function.IntToDoubleFunction;

/**
 * Frequency-domain discontinuity / glitch rejector for the coherently
 * averaged FFT.  Where the time-domain Nth-difference test fails on weak
 * signals (the difference is a high-pass, so low SNR buries the glitch's
 * peak under broadband noise), this works on the spectrum and compares each
 * block to the running statistics of the blocks already collected — robust
 * because a real line references itself while a glitch lifts the floor or
 * the near-carrier pedestal.
 *
 * <h2>Three gates</h2>
 * <ol>
 *   <li><b>Near-carrier pedestal.</b>  The noise "rock" in the bins flanking
 *       each fundamental (main lobe excluded), measured as its excess over
 *       the block's own broadband noise floor — {@code pedestal − floor}.
 *       That excess is amplitude- and window-leakage-independent; its
 *       running {@code median + k·MAD} over the collected blocks is the
 *       threshold, so the clean baseline is learned rather than fixed.</li>
 *   <li><b>Broadband floor.</b>  Reduce the spectrum to {@code B} log-spaced
 *       band levels (dB), keep a per-band running median over the last
 *       {@code L} accepted blocks, and reject when the mean lift over the
 *       floor bands exceeds a self-calibrated {@code median + k·MAD}.
 *       Catches a uniform broadband rise the pedestal excess can't see.</li>
 *   <li><b>Total power.</b>  A generator stall / long ADC dropout where the
 *       lines collapse instead of the floor rising.</li>
 * </ol>
 * Every comparison is a dB ratio self-calibrated against the collected
 * history, so the test is amplitude-independent and learns its own clean
 * baseline.  Only the very first block (which seeds the reference) is
 * accepted unconditionally; from the second block on every gate is live,
 * the thin early history covered by small MAD floors and sharpening as more
 * clean averages accumulate.  An early glitch can't cause false rejects — it
 * only makes a clean block's lift go negative, and the median self-heals.
 *
 * <p>Streaming, single-threaded (worker thread); not synchronized.
 */
public final class SpectralDiscontinuityDetector {

    /** dB floors on the self-calibrated MADs so a momentarily very-stable
     *  run can't collapse a threshold onto its median and false-fire on
     *  ordinary bin noise. */
    private static final double MIN_SCORE_MAD    = 0.5;
    private static final double MIN_POWER_MAD    = 0.5;
    private static final double MIN_PEDESTAL_MAD = 0.5;

    /** The near-carrier pedestal is sampled in the skirt of a strong tone, where
     *  ordinary window leakage — worse the further the tone sits off perfect
     *  coherence — breathes block-to-block over a much heavier tail than the
     *  broadband floor.  It therefore gets its OWN sigma, larger than the
     *  floor/power {@code scoreSigmaK}, so steady leakage doesn't false-fire;
     *  only splatter well above the leakage envelope trips the gate. */
    private static final double PEDESTAL_SIGMA_K = 10.0;

    /** Minimum tone-lobe exclusion half-width, and the pedestal sampling span,
     *  in HZ — converted to bins via {@link #binWidthHz} so they don't drift
     *  with FFT size / sample rate. */
    private static final double PEAK_HALFWIDTH_HZ = 1.1;
    private static final double SKIRT_WIDTH_HZ    = 8.8;

    /** Around each fundamental the floor keeps out only the tone's OWN band plus
     *  any band whose centre lands within this guard — instead of letting the
     *  lobe's leakage drop the neighbour bands too, which opens a wide hole in
     *  the floor at the tone.  The near bands are the most relevant local floor;
     *  bounded to ±2 bands so a tone's leakage can't pull in far-out bands.
     *  Applied around every fundamental. */
    private static final double FLOOR_GUARD_HZ = 100.0;

    /** Data-derived main-lobe finder (the same one the .frc stretch uses): the
     *  pedestal excludes the fundamental's ACTUAL lobe, not a fixed width, so
     *  normal window leakage isn't read as a near-carrier pedestal. */
    private final ToneLobeLift lobe = new ToneLobeLift();

    /** Hz per bin, supplied per block via {@link #reject}; converts the Hz
     *  pedestal constants to bins. */
    private double binWidthHz = 1.0;

    private final int    numBands;
    private final int    historyBlocks;     // L — reference median depth
    private final int    calibBlocks;       // calibration-history depth
    private final double scoreSigmaK;        // reject if score > med + k·MAD
    private final double powerSigmaK;        // reject if |power−med| > k·MAD
    private final double guardDb;            // line band = level > localMed + guardDb
    private final int    minBandBins;        // floor on bins/band (low-freq noise control)

    // band layout (rebuilt on configure)
    private int   halfSize = -1;
    private int   bands;
    private int[] bandLo, bandHi;

    // per-band level history ring (dB), most-recent L accepted blocks
    private double[][] ref;
    private int     refFill, refHead;

    // calibration histories (own fills — score only exists from block 2)
    private double[] scoreHist;    private int scoreFill, scoreHead;
    private double[] powerHist;    private int powerFill, powerHead;
    private double[] pedestalHist; private int pedFill, pedHead;     // pedestal-over-floor excess (dB)

    // scratch
    private double[] level, mref, rho, scratch;
    private boolean[] lineBand;

    private double lastScore = Double.NaN, lastThreshold = Double.NaN;
    private double lastPowerDb = Double.NaN, lastPedestalDb = Double.NaN;
    private double lastFloorDb = Double.NaN, lastPedestalExcess = Double.NaN;

    private Gates lastGates;

    public SpectralDiscontinuityDetector() {
        this(48, 8, 12, 6.0, 6.0, 10.0, 8);
    }

    public SpectralDiscontinuityDetector(int numBands, int historyBlocks, int calibBlocks,
                                         double scoreSigmaK, double powerSigmaK,
                                         double guardDb, int minBandBins) {
        this.numBands      = Math.max(4, numBands);
        this.historyBlocks = Math.max(3, historyBlocks);
        this.calibBlocks   = Math.max(3, calibBlocks);
        this.scoreSigmaK   = scoreSigmaK;
        this.powerSigmaK   = powerSigmaK;
        this.guardDb       = guardDb;
        this.minBandBins   = Math.max(1, minBandBins);
    }

    /** (Re)builds the log-spaced band layout for a spectrum of {@code halfSize}
     *  bins and clears all history.  Call when the FFT length changes. */
    public void configure(int halfSize) {
        if (halfSize == this.halfSize) return;
        this.halfSize = halfSize;
        ref          = new double[historyBlocks][numBands];
        scoreHist    = new double[calibBlocks];
        powerHist    = new double[calibBlocks];
        pedestalHist = new double[calibBlocks];
        level        = new double[numBands];
        mref         = new double[numBands];
        rho          = new double[numBands];
        lineBand     = new boolean[numBands];
        scratch      = new double[Math.max(historyBlocks, Math.max(numBands, calibBlocks))];
        reset();
    }

    /** Clears the reference and calibration history (e.g. on reset-statistics)
     *  without rebuilding the band layout. */
    public void reset() {
        refFill = refHead = 0;
        scoreFill = scoreHead = 0;
        powerFill = powerHead = 0;
        pedFill = pedHead = 0;
        lastScore = lastThreshold = lastPowerDb = Double.NaN;
        lastPedestalDb = lastFloorDb = lastPedestalExcess = Double.NaN;
        lastGates = null;
    }

    /** Ingests one block's complex spectrum (bins {@code 0..halfSize-1}) and
     *  returns {@code true} if the block is an outlier to be REJECTED.
     *  {@code peakBins} are the known fundamental bin(s) whose near-carrier
     *  pedestal excess is gated (may be null/empty to skip that gate). */
    public boolean reject(double[] re, double[] im, int halfSize, double binWidthHz, int[] peakBins) {
        this.binWidthHz = binWidthHz > 0.0 ? binWidthHz : 1.0;
        configure(halfSize);
        buildBands(halfSize, peakBins);   // re-apply the ±FLOOR_GUARD_HZ tone-band narrowing

        // --- band levels (dB) + total power ---
        double totalPow = 0.0;
        for (int b = 0; b < bands; b++) {
            double sum = 0.0;
            for (int k = bandLo[b]; k < bandHi[b]; k++) sum += re[k] * re[k] + im[k] * im[k];
            int n = bandHi[b] - bandLo[b];
            level[b] = 10.0 * Math.log10((n > 0 ? sum / n : 0.0) + 1e-300);
            totalPow += sum;
        }
        lastPowerDb = 10.0 * Math.log10(totalPow + 1e-300);

        // Per-block line detection (no history needed) → noise floor + pedestal excess.
        for (int b = 0; b < bands; b++) lineBand[b] = isLocalLine(level, b);
        lastFloorDb        = floorMedian(level);
        lastPedestalDb     = pedestalDb(re, im, peakBins);
        double pedFloorDb  = pedestalFloorDb(peakBins);    // two bands flanking the tone
        if (Double.isNaN(pedFloorDb)) pedFloorDb = lastFloorDb;
        lastPedestalExcess = Double.isNaN(lastPedestalDb) ? Double.NaN
                : lastPedestalDb - pedFloorDb;

        if (refFill == 0) {                    // first block: seed the history, accept
            pushPower(lastPowerDb);
            pushPedestal(lastPedestalExcess);
            pushRef(level);
            lastGates = new Gates(bandLo, bandHi, level.clone(), lastFloorDb, 0.0, 0.0,
                    lastPedestalExcess, Double.NaN, lastPowerDb, lastPowerDb, 0.0, halfSize,
                    false, false, false, peakBins == null ? null : peakBins.clone());
            return false;
        }

        // --- gate 1: near-carrier pedestal excess vs its collected median ---
        boolean pedestalOut = false;
        double pedestalThresh = Double.NaN;
        if (!Double.isNaN(lastPedestalExcess) && pedFill > 0) {
            double pedMed = median(pedestalHist, pedFill);
            double pedMad = mad(pedestalHist, pedFill, pedMed);
            pedestalThresh = pedMed + PEDESTAL_SIGMA_K * Math.max(pedMad, MIN_PEDESTAL_MAD);
            pedestalOut = lastPedestalExcess > pedestalThresh;
        }

        // --- gate 2: broadband floor lift vs the running-median reference ---
        for (int b = 0; b < bands; b++) {
            for (int i = 0; i < refFill; i++) scratch[i] = ref[i][b];
            mref[b] = median(scratch, refFill);
        }
        int m = 0;
        for (int b = 0; b < bands; b++) {
            if (lineBand[b]) continue;         // a real line / skirt — self-references, skip
            rho[m++] = level[b] - mref[b];
        }
        double score = m > 0 ? mean(rho, m) : 0.0;
        lastScore = score;
        double sMed = scoreFill > 0 ? median(scoreHist, scoreFill) : 0.0;
        double sMad = scoreFill > 0 ? mad(scoreHist, scoreFill, sMed) : 0.0;
        lastThreshold = sMed + scoreSigmaK * Math.max(sMad, MIN_SCORE_MAD);
        boolean scoreOut = score > lastThreshold;

        // --- gate 3: total power (stall / dropout) ---
        double pMed = median(powerHist, powerFill);
        double pMad = mad(powerHist, powerFill, pMed);
        double powerThresh = powerSigmaK * Math.max(pMad, MIN_POWER_MAD);
        boolean powerOut = Math.abs(lastPowerDb - pMed) > powerThresh;

        boolean rejected = pedestalOut || scoreOut || powerOut;
        lastGates = new Gates(bandLo, bandHi, mref.clone(), lastFloorDb, lastThreshold, score,
                lastPedestalExcess, pedestalThresh, lastPowerDb, pMed, powerThresh, halfSize,
                pedestalOut, scoreOut, powerOut, peakBins == null ? null : peakBins.clone());

        pushScore(score);                      // baselines track all blocks (robust to outliers)
        pushPower(lastPowerDb);
        pushPedestal(lastPedestalExcess);
        if (!rejected) pushRef(level);         // reference only from accepted blocks
        return rejected;
    }

    public double  lastScore()         { return lastScore; }
    public double  lastThreshold()     { return lastThreshold; }
    public double  lastPowerDb()       { return lastPowerDb; }
    public double  lastPedestalDb()    { return lastPedestalDb; }
    public double  lastFloorDb()       { return lastFloorDb; }
    public double  lastPedestalExcess(){ return lastPedestalExcess; }
    public boolean isWarmingUp()       { return refFill < 1; }
    public int     getBandCount()      { return bands; }
    /** Read-only snapshot of the last block's gate state for the debug overlay. */
    public Gates   gates()             { return lastGates; }

    // ---------------------------------------------------------------- internals

    /** Median power (→ dB) of the bins flanking each fundamental, main lobe
     *  excluded.  NaN when no peaks are supplied. */
    private double pedestalDb(double[] re, double[] im, int[] peakBins) {
        if (peakBins == null || peakBins.length == 0) return Double.NaN;
        IntToDoubleFunction mag = k -> Math.hypot(re[k], im[k]);
        int peakHalf = Math.max(1, (int) Math.round(PEAK_HALFWIDTH_HZ / binWidthHz));
        int skirt    = Math.max(8, (int) Math.round(SKIRT_WIDTH_HZ  / binWidthHz));
        double[] buf = new double[peakBins.length * 2 * skirt];
        int n = 0;
        for (int f : peakBins) {
            // Exclude the tone's DATA-DERIVED main lobe (not a fixed width) so
            // normal window leakage isn't counted; sample the skirt just beyond.
            int mb = halfSize - 1;   // bins here run 1..halfSize-1 (as buildBands/collectSkirt assume)
            int[] e = lobe.lobeBins(mag, f, mb, lobe.localFloor(mag, f, mb));
            int lo = Math.min(e[0], f - peakHalf);
            int hi = Math.max(e[1], f + peakHalf);
            n = collectSkirt(re, im, lo - skirt, lo, buf, n);
            n = collectSkirt(re, im, hi + 1, hi + 1 + skirt, buf, n);
        }
        if (n == 0) return Double.NaN;
        return 10.0 * Math.log10(median(buf, n) + 1e-300);
    }

    private int collectSkirt(double[] re, double[] im, int from, int to, double[] buf, int n) {
        from = Math.max(1, from);
        to   = Math.min(halfSize, to);
        for (int k = from; k < to && n < buf.length; k++) buf[n++] = re[k] * re[k] + im[k] * im[k];
        return n;
    }

    /** Noise floor = median of the non-line band levels (dB). */
    private double floorMedian(double[] lvl) {
        int n = 0;
        for (int b = 0; b < bands; b++) if (!lineBand[b]) scratch[n++] = lvl[b];
        if (n == 0) for (int b = 0; b < bands; b++) scratch[n++] = lvl[b];   // all lines: fall back
        return median(scratch, n);
    }

    /** Pedestal-gate noise floor: median of the two ALREADY-computed bands
     *  flanking each fundamental (the left and right neighbours of its now-narrow
     *  ±FLOOR_GUARD_HZ band) — the local floor right beside the tone, free of the
     *  jittery far-out bands.  NaN when no flanking band exists. */
    private double pedestalFloorDb(int[] peakBins) {
        if (peakBins == null || peakBins.length == 0) return Double.NaN;
        double[] buf = new double[peakBins.length * 2];
        int n = 0;
        for (int f : peakBins) {
            int bf = bandOf(f);
            if (bf < 0) continue;
            if (bf - 1 >= 0)    buf[n++] = level[bf - 1];
            if (bf + 1 < bands) buf[n++] = level[bf + 1];
        }
        return n == 0 ? Double.NaN : median(buf, n);
    }

    /** Index of the band containing {@code bin}, or −1. */
    private int bandOf(int bin) {
        for (int b = 0; b < bands; b++) if (bin >= bandLo[b] && bin < bandHi[b]) return b;
        return -1;
    }

    /** A band is a line / skirt when its level towers over the local median of
     *  nearby bands — derived from the current block, so it needs no history. */
    private boolean isLocalLine(double[] lvl, int b) {
        int lo = Math.max(0, b - 3), hi = Math.min(bands, b + 4);
        double[] loc = new double[hi - lo];
        int n = 0;
        for (int j = lo; j < hi; j++) loc[n++] = lvl[j];
        return lvl[b] > median(loc, n) + guardDb;
    }

    private void buildBands(int halfSize, int[] peakBins) {
        int firstBin = 1;                      // skip DC
        int lastBin  = Math.max(firstBin + 1, halfSize);
        double lnLo = Math.log(firstBin), lnHi = Math.log(lastBin);
        int[] lo = new int[numBands];
        int[] hi = new int[numBands];
        int count = 0, prev = firstBin;
        for (int b = 0; b < numBands; b++) {
            double f = (b + 1) / (double) numBands;
            int edge = (int) Math.round(Math.exp(lnLo + (lnHi - lnLo) * f));
            edge = Math.max(prev + minBandBins, Math.min(lastBin, edge)); // ≥ minBandBins bins
            if (prev >= lastBin) break;
            lo[count] = prev;
            hi[count] = edge;
            count++;
            prev = edge;
            if (edge >= lastBin) break;
        }
        bands  = Math.max(1, count);
        bandLo = Arrays.copyOf(lo, bands);
        bandHi = Arrays.copyOf(hi, bands);

        // Shrink the band holding each fundamental to ±FLOOR_GUARD_HZ, handing its
        // freed bins to the immediate neighbours, so the floor's gap at the tone
        // is the guard width — not the lobe's full leakage extent.  It's a band
        // geometry change, so the floor AND the debug overlay (which read these
        // very edges) both show the narrow gap, with no extra code.
        if (peakBins != null) {
            int guard = Math.max(1, (int) Math.round(FLOOR_GUARD_HZ / binWidthHz));
            for (int pf : peakBins) {
                int bf = -1;
                for (int b = 0; b < bands; b++) if (pf >= bandLo[b] && pf < bandHi[b]) { bf = b; break; }
                if (bf < 0) continue;
                int nlo = Math.max(bf > 0         ? bandLo[bf - 1] + 1 : firstBin, pf - guard);
                int nhi = Math.min(bf < bands - 1 ? bandHi[bf + 1] - 1 : lastBin,  pf + guard);
                if (nhi <= nlo) continue;
                if (bf > 0)         bandHi[bf - 1] = nlo;
                if (bf < bands - 1) bandLo[bf + 1] = nhi;
                bandLo[bf] = nlo;
                bandHi[bf] = nhi;
            }
        }
    }

    private void pushRef(double[] lvl) {
        System.arraycopy(lvl, 0, ref[refHead], 0, bands);
        refHead = (refHead + 1) % historyBlocks;
        if (refFill < historyBlocks) refFill++;
    }

    private void pushScore(double v) {
        scoreHist[scoreHead] = v;
        scoreHead = (scoreHead + 1) % calibBlocks;
        if (scoreFill < calibBlocks) scoreFill++;
    }

    private void pushPower(double v) {
        powerHist[powerHead] = v;
        powerHead = (powerHead + 1) % calibBlocks;
        if (powerFill < calibBlocks) powerFill++;
    }

    private void pushPedestal(double v) {
        if (Double.isNaN(v)) return;
        pedestalHist[pedHead] = v;
        pedHead = (pedHead + 1) % calibBlocks;
        if (pedFill < calibBlocks) pedFill++;
    }

    private static double median(double[] a, int n) {
        if (n <= 0) return 0.0;
        double[] c = Arrays.copyOf(a, n);
        Arrays.sort(c);
        return (n % 2 == 1) ? c[n / 2] : 0.5 * (c[n / 2 - 1] + c[n / 2]);
    }

    private static double mad(double[] a, int n, double med) {
        if (n <= 0) return 0.0;
        double[] d = new double[n];
        for (int i = 0; i < n; i++) d[i] = Math.abs(a[i] - med);
        Arrays.sort(d);
        double m = (n % 2 == 1) ? d[n / 2] : 0.5 * (d[n / 2 - 1] + d[n / 2]);
        return 1.4826 * m;                     // scaled to ≈σ for a normal distribution
    }

    private static double mean(double[] a, int n) {
        if (n <= 0) return 0.0;
        double s = 0.0;
        for (int i = 0; i < n; i++) s += a[i];
        return s / n;
    }

    /** Immutable snapshot of the last block's gate state for the debug overlay.
     *  All dB values are the detector's frame (10·log10 raw bin power); a viewer
     *  positions each by its EXCESS over {@link #floorDb} added to the displayed
     *  (calibrated) noise floor, so it lands at the right vertical position
     *  whatever calibration the chart applies. */
    public static final class Gates {
        public final int[]    bandLo, bandHi;   // band bin ranges (shared, immutable)
        public final double[] mref;             // gate 2: per-band median floor reference (dB)
        public final double   floorDb;          // current noise floor (dB) — the excess anchor
        public final double   scoreThreshDb;    // gate 2: lift over mref that rejects (dB)
        public final double   scoreDb;          // gate 2: this block's mean lift (dB)
        public final double   pedestalExcessDb; // gate 1: current pedestal over floor (dB)
        public final double   pedestalThreshDb; // gate 1: reject excess (dB; NaN = not armed)
        public final double   powerDb, powerMedDb, powerThreshDb;  // gate 3 (dB)
        public final int      bins;             // bins summed for total power
        public final boolean  pedestalOut, scoreOut, powerOut;
        public final int[]    peakBins;         // gate 1 placement (near-carrier)

        Gates(int[] bandLo, int[] bandHi, double[] mref, double floorDb, double scoreThreshDb,
              double scoreDb, double pedestalExcessDb, double pedestalThreshDb,
              double powerDb, double powerMedDb, double powerThreshDb, int bins,
              boolean pedestalOut, boolean scoreOut, boolean powerOut, int[] peakBins) {
            this.bandLo = bandLo; this.bandHi = bandHi; this.mref = mref;
            this.floorDb = floorDb; this.scoreThreshDb = scoreThreshDb; this.scoreDb = scoreDb;
            this.pedestalExcessDb = pedestalExcessDb; this.pedestalThreshDb = pedestalThreshDb;
            this.powerDb = powerDb; this.powerMedDb = powerMedDb; this.powerThreshDb = powerThreshDb;
            this.bins = bins;
            this.pedestalOut = pedestalOut; this.scoreOut = scoreOut; this.powerOut = powerOut;
            this.peakBins = peakBins;
        }
    }
}
