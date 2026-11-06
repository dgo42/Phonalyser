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
import java.util.function.IntToDoubleFunction;

import org.edgo.audio.measure.enums.WindowType;

/**
 * Frequency-domain discontinuity / glitch rejector for the coherently
 * averaged FFT.  Where the time-domain Nth-difference test fails on weak
 * signals (the difference is a high-pass, so low SNR buries the glitch's
 * peak under broadband noise), this works on the spectrum and compares each
 * block to the running statistics of the blocks already collected - robust
 * because a real line references itself while a glitch lifts the floor or
 * the near-carrier pedestal.
 *
 * <h2>Three gates</h2>
 * <ol>
 *   <li><b>Near-carrier pedestal.</b>  The noise "rock" in the bins flanking
 *       each fundamental (main lobe excluded), measured as its excess over
 *       the block's own broadband noise floor - {@code pedestal − floor}.
 *       That excess is amplitude- and window-leakage-independent; its
 *       running {@code median + k·MAD} over the collected blocks is the
 *       threshold, so the clean baseline is learned rather than fixed.</li>
 *   <li><b>Broadband floor.</b>  Reduce the spectrum to {@code B} log-spaced
 *       bands, each read as its band-top - the upper edge of its noise,
 *       {@link FftDsp#bandTop} of its bins' magnitudes, in dB - keep a
 *       per-band running median over the last {@code L} accepted blocks, and
 *       reject when the mean lift over the floor bands exceeds a
 *       self-calibrated {@code median + k·MAD}.
 *       Catches a uniform broadband rise the pedestal excess can't see.</li>
 *   <li><b>Total power.</b>  A generator stall / long ADC dropout where the
 *       lines collapse instead of the floor rising.</li>
 * </ol>
 * Every comparison is a dB ratio self-calibrated against the collected
 * history, so the test is amplitude-independent and learns its own clean
 * baseline.  Only the very first block (which seeds the reference) is
 * accepted unconditionally; from the second block on every gate is live,
 * the thin early history covered by small MAD floors and sharpening as more
 * clean averages accumulate.  An early glitch can't cause false rejects - it
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
     *  ordinary window leakage - worse the further the tone sits off perfect
     *  coherence - breathes block-to-block over a much heavier tail than the
     *  broadband floor.  It therefore gets its OWN sigma, larger than the
     *  floor/power {@code scoreSigmaK}, so steady leakage doesn't false-fire;
     *  only splatter well above the leakage envelope trips the gate. */
    private static final double PEDESTAL_SIGMA_K = 10.0;

    /** The pedestal sampling span, in HZ - converted to bins via
     *  {@link #binWidthHz} so it doesn't drift with FFT size / sample rate. */
    private static final double SKIRT_WIDTH_HZ    = 8.8;

    /** Around each fundamental the floor keeps out only the tone's OWN band plus
     *  any band whose centre lands within this guard - instead of letting the
     *  lobe's leakage drop the neighbour bands too, which opens a wide hole in
     *  the floor at the tone.  The near bands are the most relevant local floor;
     *  bounded to ±2 bands so a tone's leakage can't pull in far-out bands.
     *  Applied around every fundamental. */
    private static final double FLOOR_GUARD_HZ = 100.0;

    /** Hz per bin, supplied per block via {@link #reject}; converts the Hz
     *  pedestal constants to bins. */
    private double binWidthHz = 1.0;

    private final int    numBands;
    private final int    historyBlocks;     // L - reference median depth
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

    // calibration histories (own fills - score only exists from block 2)
    private double[] scoreHist;    private int scoreFill, scoreHead;
    private double[] powerHist;    private int powerFill, powerHead;
    private double[] pedestalHist; private int pedFill, pedHead;     // pedestal-over-floor excess (dB)

    // working arrays: per-band band-top (dB), the reference, the lifts, the
    // reused input of every median, a band's magnitudes for its band-top
    private double[] topLevel, mref, rho, medianInput;
    private double[] bandMagnitudes = new double[0];
    private boolean[] lineBand;

    private double lastScore = Double.NaN, lastThreshold = Double.NaN;
    private double lastPowerDb = Double.NaN, lastPedestalDb = Double.NaN;
    private double lastFloorDb = Double.NaN, lastPedestalExcess = Double.NaN;
    // gate 1 reference: median of the fundamentals' flank floors (dB), NaN when none
    private double lastPedestalFloorDb = Double.NaN;

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
     *  bins and clears all history.  Call when the FFT length changes.
     *
     * @param halfSize the spectrum's bin count */
    public void configure(int halfSize) {
        if (halfSize == this.halfSize) return;
        this.halfSize = halfSize;
        ref          = new double[historyBlocks][numBands];
        scoreHist    = new double[calibBlocks];
        powerHist    = new double[calibBlocks];
        pedestalHist = new double[calibBlocks];
        topLevel     = new double[numBands];
        mref         = new double[numBands];
        rho          = new double[numBands];
        lineBand     = new boolean[numBands];
        medianInput  = new double[Math.max(historyBlocks, Math.max(numBands, calibBlocks))];
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
        lastPedestalDb = lastFloorDb = lastPedestalExcess = lastPedestalFloorDb = Double.NaN;
        lastGates = null;
    }

    /** Ingests one block's complex spectrum (bins {@code 0..halfSize-1}) and
     *  returns {@code true} if the block is an outlier to be REJECTED.
     *  {@code peakBins} are the known fundamental bin(s) whose near-carrier
     *  pedestal excess is gated (may be null/empty to skip that gate);
     *  {@code window} is the block's window function: the local floor the
     *  pedestal's lobe walk stops at is read past its main lobe.
     *
     * @param re         the block's real parts, by bin
     * @param im         the block's imaginary parts, by bin
     * @param halfSize   the spectrum's bin count
     * @param binWidthHz the bin width, Hz
     * @param window     the block's window function
     * @param peakBins   the fundamentals' bins; {@code null} or empty to skip gate 1
     * @return {@code true} when the block is rejected */
    public boolean reject(double[] re, double[] im, int halfSize, double binWidthHz,
                          WindowType window, int[] peakBins) {
        this.binWidthHz = binWidthHz > 0.0 ? binWidthHz : 1.0;
        configure(halfSize);
        buildBands(halfSize, peakBins);   // re-apply the ±FLOOR_GUARD_HZ tone-band narrowing

        // --- band levels (dB): band-top, + total power ---
        double totalPow = 0.0;
        for (int b = 0; b < bands; b++) {
            int n = bandHi[b] - bandLo[b];
            if (bandMagnitudes.length < n) bandMagnitudes = new double[n];
            double sum = 0.0;
            for (int k = bandLo[b]; k < bandHi[b]; k++) {
                double pow = re[k] * re[k] + im[k] * im[k];
                sum += pow;
                bandMagnitudes[k - bandLo[b]] = Math.sqrt(pow);
            }
            topLevel[b] = 20.0 * Math.log10((n > 0 ? FftDsp.bandTop(bandMagnitudes, n) : 0.0) + 1e-300);
            totalPow += sum;
        }
        lastPowerDb = 10.0 * Math.log10(totalPow + 1e-300);

        // Per-block line detection (no history needed) -> noise floor + pedestal excess.
        for (int b = 0; b < bands; b++) lineBand[b] = isLocalLine(topLevel, b);
        // Noise floor = band-top of the non-line bands' band-tops, all bands
        // when every band is a line.
        lastFloorDb        = FftDsp.bandTopOfLevelsDb(topLevel, lineBand, bands);
        // The pedestal is sampled past at least the window's main lobe and the
        // leakage guard, over SKIRT_WIDTH_HZ - one geometry per block.
        int pedGap  = FftDsp.mainLobeBins(window) + FftDsp.LEAKAGE_GUARD_BINS;
        int pedSpan = Math.max(8, (int) Math.round(SKIRT_WIDTH_HZ / this.binWidthHz));
        lastPedestalDb     = pedestalDb(re, im, peakBins, window, pedGap, pedSpan);
        double pedFloorDb  = lastPedestalFloorDb;          // the tones' flank floors
        if (Double.isNaN(pedFloorDb)) pedFloorDb = lastFloorDb;
        lastPedestalExcess = Double.isNaN(lastPedestalDb) ? Double.NaN
                : lastPedestalDb - pedFloorDb;

        if (refFill == 0) {                    // first block: seed the history, accept
            pushPower(lastPowerDb);
            pushPedestal(lastPedestalExcess);
            pushRef(topLevel);
            double[] tops = topLevel.clone();  // the reference is the block itself
            lastGates = new Gates(bandLo, bandHi, tops, tops, lineBand.clone(), lastFloorDb, 0.0, 0.0,
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

        // --- gate 2: broadband floor lift - each band's band-top vs its
        //     running-median reference ---
        for (int b = 0; b < bands; b++) {
            for (int i = 0; i < refFill; i++) medianInput[i] = ref[i][b];
            mref[b] = median(medianInput, refFill);
        }
        int m = 0;
        for (int b = 0; b < bands; b++) {
            if (lineBand[b]) continue;         // a real line / skirt - self-references, skip
            rho[m++] = topLevel[b] - mref[b];
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
        lastGates = new Gates(bandLo, bandHi, mref.clone(), topLevel.clone(), lineBand.clone(),
                lastFloorDb, lastThreshold, score,
                lastPedestalExcess, pedestalThresh, lastPowerDb, pMed, powerThresh, halfSize,
                pedestalOut, scoreOut, powerOut, peakBins == null ? null : peakBins.clone());

        pushScore(score);                     // baselines track all blocks (robust to outliers)
        pushPower(lastPowerDb);
        pushPedestal(lastPedestalExcess);
        if (!rejected) pushRef(topLevel);      // reference only from accepted blocks
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

    /** Band-top ({@link FftDsp#bandTop}, -> dB) of the magnitudes of the
     *  {@code skirt} bins flanking each fundamental past its lobe - at least
     *  {@code peakHalf} bins out.  Each fundamental's flank floor
     *  ({@link FftDsp#pedestalFloor}), the stop of its lobe walk, is kept as
     *  well: their median in dB becomes {@link #lastPedestalFloorDb}, the
     *  reference gate 1 measures the pedestal against; NaN when no
     *  fundamental has a flank bin in range.
     *
     * @param re       the frame's real parts, by bin
     * @param im       the frame's imaginary parts, by bin
     * @param peakBins the fundamentals' bins; {@code null} or empty for none
     * @param window   the analysis window, for the lobe walk
     * @param peakHalf least distance, in bins, of the sampled bins from a fundamental
     * @param skirt    bins sampled on each side
     * @return the pedestal, dB; NaN when no peaks are supplied or no bin is sampled */
    private double pedestalDb(double[] re, double[] im, int[] peakBins, WindowType window,
                              int peakHalf, int skirt) {
        lastPedestalFloorDb = Double.NaN;
        if (peakBins == null || peakBins.length == 0) return Double.NaN;
        IntToDoubleFunction mag = k -> Math.hypot(re[k], im[k]);
        double[] buf = new double[peakBins.length * 2 * skirt];
        double[] floorsDb = new double[peakBins.length];
        int n = 0;
        int nFloors = 0;
        for (int f : peakBins) {
            // Exclude the tone's DATA-DERIVED main lobe (not a fixed width) so
            // normal window leakage isn't counted; sample the skirt just beyond.
            int mb = halfSize - 1;   // bins here run 1..halfSize-1 (as buildBands/collectSkirt assume)
            double floor = FftDsp.pedestalFloor(mag, f, mb, binWidthHz, window);
            if (floor > 0.0) floorsDb[nFloors++] = 20.0 * Math.log10(floor);
            int[] e = FftDsp.lobeBins(mag, f, mb, floor, binWidthHz, window);
            int lo = Math.min(e[0], f - peakHalf);
            int hi = Math.max(e[1], f + peakHalf);
            n = collectSkirt(re, im, lo - skirt, lo, buf, n);
            n = collectSkirt(re, im, hi + 1, hi + 1 + skirt, buf, n);
        }
        if (nFloors > 0) lastPedestalFloorDb = median(floorsDb, nFloors);
        if (n == 0) return Double.NaN;
        return 20.0 * Math.log10(FftDsp.bandTop(buf, n) + 1e-300);
    }

    /** Appends the magnitudes of bins {@code [from, to)}, clipped to the
     *  spectrum, to {@code buf} from index {@code n} on.
     *
     * @param re   the frame's real parts, by bin
     * @param im   the frame's imaginary parts, by bin
     * @param from the first bin
     * @param to   one past the last bin
     * @param buf  the buffer the magnitudes go into
     * @param n    how many values {@code buf} already holds
     * @return how many values {@code buf} holds afterwards */
    private int collectSkirt(double[] re, double[] im, int from, int to, double[] buf, int n) {
        from = Math.max(1, from);
        to   = Math.min(halfSize, to);
        for (int k = from; k < to && n < buf.length; k++) buf[n++] = Math.hypot(re[k], im[k]);
        return n;
    }

    /** A band is a line / skirt when its level towers over the local median of
     *  nearby bands - derived from the current block, so it needs no history. */
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
        // is the guard width - not the lobe's full leakage extent.  It's a band
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

    /** Median of the first {@code n} values of {@code a}; the mean of the two
     *  middle values for an even count.  After the upper middle is selected,
     *  every entry below its index is no larger, so the lower middle is the
     *  largest of those.
     *
     * @param a the values; left as given
     * @param n how many leading values take part
     * @return the median; 0 when {@code n} is not positive */
    private double median(double[] a, int n) {
        if (n <= 0) return 0.0;
        double[] c = Arrays.copyOf(a, n);
        double upper = FftDsp.selectKth(c, n, n / 2);
        if (n % 2 == 1) return upper;
        return 0.5 * (FftDsp.selectKth(c, n / 2, n / 2 - 1) + upper);
    }

    /** Median absolute deviation of the first {@code n} values of {@code a}
     *  from {@code med}, scaled to the standard deviation of a normal
     *  distribution.
     *
     * @param a   the values; left as given
     * @param n   how many leading values take part
     * @param med their median
     * @return the scaled MAD; 0 when {@code n} is not positive */
    private double mad(double[] a, int n, double med) {
        if (n <= 0) return 0.0;
        double[] d = new double[n];
        for (int i = 0; i < n; i++) d[i] = Math.abs(a[i] - med);
        return 1.4826 * median(d, n);          // scaled to ≈σ for a normal distribution
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
        public final double[] mref;             // gate 2: per-band median band-top reference (dB)
        public final double[] bandTopDb;        // gate 2: this block's band-top per band (dB)
        public final boolean[] lineBand;        // gate 2: bands the line test left out of the score
        public final double   floorDb;          // current noise floor (dB) - the excess anchor
        public final double   scoreThreshDb;    // gate 2: lift over mref that rejects (dB)
        public final double   scoreDb;          // gate 2: this block's mean lift (dB)
        public final double   pedestalExcessDb; // gate 1: current pedestal over floor (dB)
        public final double   pedestalThreshDb; // gate 1: reject excess (dB; NaN = not armed)
        public final double   powerDb, powerMedDb, powerThreshDb;  // gate 3 (dB)
        public final int      bins;             // bins summed for total power
        public final boolean  pedestalOut, scoreOut, powerOut;
        public final int[]    peakBins;         // gate 1 placement (near-carrier)

        /** One block's gate state.
         *
         * @param bandLo           each band's first bin
         * @param bandHi           each band's end bin, exclusive
         * @param mref             gate 2: per-band median band-top reference, dB
         * @param bandTopDb        gate 2: this block's band-top per band, dB
         * @param lineBand        gate 2: bands the line test left out of the score
         * @param floorDb          the detector's noise floor, dB
         * @param scoreThreshDb    gate 2: lift over the reference that rejects, dB
         * @param scoreDb          gate 2: this block's mean lift, dB
         * @param pedestalExcessDb gate 1: the pedestal over its reference, dB
         * @param pedestalThreshDb gate 1: the excess that rejects, dB; NaN when not armed
         * @param powerDb          gate 3: this block's total power, dB
         * @param powerMedDb       gate 3: median of the recent total powers, dB
         * @param powerThreshDb    gate 3: the deviation that rejects, dB
         * @param bins             bins summed for the total power
         * @param pedestalOut      whether gate 1 rejected
         * @param scoreOut         whether gate 2 rejected
         * @param powerOut         whether gate 3 rejected
         * @param peakBins         the fundamentals' bins gate 1 is placed at */
        Gates(int[] bandLo, int[] bandHi, double[] mref, double[] bandTopDb, boolean[] lineBand, double floorDb,
              double scoreThreshDb, double scoreDb, double pedestalExcessDb, double pedestalThreshDb,
              double powerDb, double powerMedDb, double powerThreshDb, int bins,
              boolean pedestalOut, boolean scoreOut, boolean powerOut, int[] peakBins) {
            this.bandLo = bandLo; this.bandHi = bandHi; this.mref = mref; this.bandTopDb = bandTopDb;
            this.lineBand = lineBand;
            this.floorDb = floorDb; this.scoreThreshDb = scoreThreshDb; this.scoreDb = scoreDb;
            this.pedestalExcessDb = pedestalExcessDb; this.pedestalThreshDb = pedestalThreshDb;
            this.powerDb = powerDb; this.powerMedDb = powerMedDb; this.powerThreshDb = powerThreshDb;
            this.bins = bins;
            this.pedestalOut = pedestalOut; this.scoreOut = scoreOut; this.powerOut = powerOut;
            this.peakBins = peakBins;
        }
    }
}
