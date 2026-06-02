package org.edgo.audio.measure.dsp;

import java.util.Arrays;

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

    /** Bins on each side of a fundamental excluded as its main lobe, and the
     *  width of the pedestal window measured just beyond it. */
    private static final int PEAK_HALFWIDTH = 6;
    private static final int SKIRT_WIDTH    = 48;

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
        buildBands(halfSize);
        ref          = new double[historyBlocks][bands];
        scoreHist    = new double[calibBlocks];
        powerHist    = new double[calibBlocks];
        pedestalHist = new double[calibBlocks];
        level        = new double[bands];
        mref         = new double[bands];
        rho          = new double[bands];
        lineBand     = new boolean[bands];
        scratch      = new double[Math.max(historyBlocks, Math.max(bands, calibBlocks))];
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
    }

    /** Ingests one block's complex spectrum (bins {@code 0..halfSize-1}) and
     *  returns {@code true} if the block is an outlier to be REJECTED.
     *  {@code peakBins} are the known fundamental bin(s) whose near-carrier
     *  pedestal excess is gated (may be null/empty to skip that gate). */
    public boolean reject(double[] re, double[] im, int halfSize, int[] peakBins) {
        if (halfSize != this.halfSize) configure(halfSize);

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
        lastPedestalExcess = Double.isNaN(lastPedestalDb) ? Double.NaN : lastPedestalDb - lastFloorDb;

        if (refFill == 0) {                    // first block: seed the history, accept
            pushPower(lastPowerDb);
            pushPedestal(lastPedestalExcess);
            pushRef(level);
            return false;
        }

        // --- gate 1: near-carrier pedestal excess vs its collected median ---
        boolean pedestalOut = false;
        if (!Double.isNaN(lastPedestalExcess) && pedFill > 0) {
            double pedMed = median(copy(pedestalHist, pedFill), pedFill);
            double pedMad = mad(pedestalHist, pedFill, pedMed);
            pedestalOut = lastPedestalExcess > pedMed + scoreSigmaK * Math.max(pedMad, MIN_PEDESTAL_MAD);
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
        double sMed = scoreFill > 0 ? median(copy(scoreHist, scoreFill), scoreFill) : 0.0;
        double sMad = scoreFill > 0 ? mad(scoreHist, scoreFill, sMed) : 0.0;
        lastThreshold = sMed + scoreSigmaK * Math.max(sMad, MIN_SCORE_MAD);
        boolean scoreOut = score > lastThreshold;

        // --- gate 3: total power (stall / dropout) ---
        double pMed = median(copy(powerHist, powerFill), powerFill);
        double pMad = mad(powerHist, powerFill, pMed);
        boolean powerOut = Math.abs(lastPowerDb - pMed) > powerSigmaK * Math.max(pMad, MIN_POWER_MAD);

        boolean rejected = pedestalOut || scoreOut || powerOut;

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

    // ---------------------------------------------------------------- internals

    /** Median power (→ dB) of the bins flanking each fundamental, main lobe
     *  excluded.  NaN when no peaks are supplied. */
    private double pedestalDb(double[] re, double[] im, int[] peakBins) {
        if (peakBins == null || peakBins.length == 0) return Double.NaN;
        double[] buf = new double[peakBins.length * 2 * SKIRT_WIDTH];
        int n = 0;
        for (int f : peakBins) {
            n = collectSkirt(re, im, f - PEAK_HALFWIDTH - SKIRT_WIDTH, f - PEAK_HALFWIDTH, buf, n);
            n = collectSkirt(re, im, f + PEAK_HALFWIDTH + 1, f + PEAK_HALFWIDTH + 1 + SKIRT_WIDTH, buf, n);
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

    /** A band is a line / skirt when its level towers over the local median of
     *  nearby bands — derived from the current block, so it needs no history. */
    private boolean isLocalLine(double[] lvl, int b) {
        int lo = Math.max(0, b - 3), hi = Math.min(bands, b + 4);
        double[] loc = new double[hi - lo];
        int n = 0;
        for (int j = lo; j < hi; j++) loc[n++] = lvl[j];
        return lvl[b] > median(loc, n) + guardDb;
    }

    private void buildBands(int halfSize) {
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

    private double[] copy(double[] a, int n) {
        return Arrays.copyOf(a, n);
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
}
