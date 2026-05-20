package org.edgo.audio.measure.adc;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.BiConsumer;

import org.edgo.audio.measure.common.StereoSample;
import org.edgo.audio.measure.common.StereoSampleFloat;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Dense weighted buffer: result[i] = histogram.count[i] / movingAverage[i].
 *
 * For bitDepth ≤ 24: single float[2^bitDepth] array.
 * For bitDepth == 32: 8 chunked float[2^29] arrays (~16 GB total).
 */
@Log4j2
public class WeightedBuffer {

    // 32-bit chunk constants
    private static final int CHUNK_BITS = 29;
    private static final int CHUNK_SIZE = 1 << CHUNK_BITS;
    private static final int CHUNK_MASK = CHUNK_SIZE - 1;

    @Getter private final int  bitDepth;
    @Getter private final long binCount;

    // ≤ 24-bit storage
    @Getter private final float[] bins;

    // 32-bit storage
    @Getter private final float[][] chunks;

    // Code map built by buildCodeMap(): raw code → corrected code
    private float[]   codeMapBins;    // ≤ 24-bit
    private float[][] codeMapChunks;  // 32-bit

    @SuppressWarnings("unused")
    private static double[] MOVING_AVERAGE_12_FIR = new double[] {
        -0.000000000000000003,
        0.007057669832155909,
        0.034611176360050649,
        0.089696532737550377,
        0.159317138508826783,
        0.209317482561416274,
        0.209317482561416301,
        0.159317138508826811,
        0.089696532737550391,
        0.034611176360050677,
        0.007057669832155911,
        -0.000000000000000003
    };

    private static double[] MOVING_AVERAGE_25_FIR = new double[] {
        -0.000000000000000001,
        0.000626896741886996,
        0.002677311320216333,
        0.006591925536381568,
        0.012896825396825394,
        0.021955202891466723,
        0.033730158730158728,
        0.047631695461161243,
        0.062500000000000000,
        0.076741407796951766,
        0.088592529949624937,
        0.096452871572151710,
        0.099206349206349201,
        0.096452871572151724,
        0.088592529949624937,
        0.076741407796951794,
        0.062500000000000028,
        0.047631695461161243,
        0.033730158730158742,
        0.021955202891466741,
        0.012896825396825394,
        0.006591925536381577,
        0.002677311320216343,
        0.000626896741887000,
        -0.000000000000000001
    };

    public WeightedBuffer(int bitDepth) {
        this.bitDepth = bitDepth;
        this.binCount = 1L << bitDepth;

        if (bitDepth < 32) {
            bins   = new float[(int) binCount];
            chunks = null;
        } else {
            bins   = null;
            chunks = new float[8][];
            for (int i = 0; i < 8; i++) {
                chunks[i] = new float[CHUNK_SIZE];
            }
        }
    }

    // -------------------------------------------------------------------------
    // Computation
    // -------------------------------------------------------------------------

    public void compute(AdcHistogram histogram, int windowLength) {
        if (histogram.getBitDepth() != bitDepth) {
            throw new IllegalArgumentException("Histogram bitDepth " + histogram.getBitDepth()
                    + " does not match buffer bitDepth " + bitDepth);
        }

        double[] ring    = new double[windowLength];
        double   sum     = 0d;
        int      ringPos = 0;
        FIR filter = new FIR(MOVING_AVERAGE_25_FIR);

        log.info("Computing weighted buffer (bits={}, bins={}, window={})...",
                bitDepth, String.format("%,d", binCount), windowLength);

        double weightedSum = 0;
        float globalAverage = 0;
        long   nonZeroBins = 0;
        long   averageBins = 0;
        int    ringFiled   = 0;
        if (windowLength > 0) {
            @SuppressWarnings("unused")
            WeightedBuffer firstBuf = new WeightedBuffer(bitDepth);
            for (long pos = 0; pos < binCount; pos++) {
                double count = histogram.getCount(pos);
                count = filter.getOutputSample(count);
                if (count > 0) {
                    nonZeroBins++;
                }
                sum += count;
                sum -= ring[ringPos];
                ring[ringPos] = count;
                if (ringFiled < windowLength) {
                    ringFiled++;
                }
                if (++ringPos == windowLength) {
                    ringPos = 0;
                }

                float maVal    = (float) sum / ringFiled;
                float weighted = maVal > 0f ? (float) count / maVal : 0f;
                set(pos, weighted);
                weightedSum += weighted;
                averageBins++;
            }
            globalAverage = averageBins > 0 ? (float) (weightedSum / averageBins) : 0f;
/*            int smallWindow = 5;
            sum         = 0d;
            ringPos     = 0;
            ringFiled   = 0;
            weightedSum = 0;
            averageBins = 0;
            Arrays.fill(ring, 0);
            for (long pos = 0; pos < binCount; pos++) {
                float count = firstBuf.get(pos);
                sum += count;
                sum -= ring[ringPos];
                ring[ringPos] = count;
                if (ringFiled < smallWindow) {
                    ringFiled++;
                }
                if (++ringPos == smallWindow) {
                    ringPos = 0;
                }

                float maVal    = (float) sum / ringFiled;
                float weighted = maVal > 0f ? (float) count / maVal : 0f;
                set(buf, pos, weighted);
                weightedSum += weighted;
                averageBins++;
            }
            globalAverage = averageBins > 0 ? (float) (weightedSum / averageBins) : 0f;*/
        } else {
            for (long pos = 0; pos < binCount; pos++) {
                double count = histogram.getCount(pos);
                if (count > 0) {
                    sum += (double) count;
                    averageBins++;
                }
            }
            globalAverage = averageBins > 0 ? (float) (sum / averageBins) : 1f;
            for (long pos = 0; pos < binCount; pos++) {
                double count = histogram.getCount(pos);
                if (count > 0) {
                    nonZeroBins++;
                }
                float weighted = count > 0f ? (float) count / globalAverage : 0f;
                set(pos, weighted);
            }
        }

        weightedSum = 0;
        averageBins = 0;
        for (long pos = 0; pos < binCount; pos++) {
            float weighted = get(pos);
            if (weighted < 2 && weighted > 0.5) {
                weightedSum += weighted;
                averageBins++;
            }
        }
        globalAverage = averageBins > 0 ? (float) (weightedSum / averageBins) : 0f;

        log.info("Global average over {} non-edge bins = {}", String.format("%,d", nonZeroBins), globalAverage);

        log.info("Weighted buffer done.");
    }

    /**
     * Computes a per-bin weight buffer using the THEORETICAL arcsine PDF of a
     * sine wave as the reference, instead of the local moving-average baseline
     * used by {@link #compute}.  The arcsine distribution
     * {@code f(x) = 1/(π·√(A²−x²))} is the time-domain density of a uniform
     * sine sweep; integrating over each ADC bin's voltage range gives the
     * expected hit count, and {@code weight = actual / expected} surfaces the
     * code's DNL directly without any spatial smoothing.
     *
     * <p>When the sine amplitude {@code A} exceeds full scale (the user's
     * "slightly bigger than ADC FS" case) the time spent above ±FS is
     * collapsed onto the rail bins by the ADC.  This method models that by
     * extending the rail bins' integration limits to ±A; the rail bins
     * therefore receive both the legitimate fraction of sine time inside their
     * own voltage range AND the clipping fraction
     * {@code (1/π)·arccos(1/A)} per side.
     *
     * <p>Amplitude estimation: when {@code sineAmpFsRatio} is {@code NaN} or
     * non-positive, A is estimated from the observed rail-bin counts assuming
     * clipping dominates them:
     * {@code N_rail/N_total ≈ (2/π)·arccos(1/A) ⇒ A = 1/cos(π·N_rail/2N_total)}.
     * For moderate clipping (rail counts are mostly the clipped portion) this
     * is accurate to within a fraction of an LSB.  Pass an explicit ratio
     * (e.g. 1.05 = 5 % over FS) when you want to bypass the estimator.
     *
     * @param histogram        captured ADC histogram
     * @param sineAmpFsRatio   sine amplitude relative to ADC FS; pass 1.0 for
     *                         "exactly full scale", 1.05 for 5 % over, NaN/≤0
     *                         to auto-estimate
     */
    public void computeSineReference(AdcHistogram histogram, double sineAmpFsRatio) {
        computeSineReference(histogram, sineAmpFsRatio, 2, 30);
    }

    public void computeSineReference(AdcHistogram histogram,
                                     double sineAmpFsRatio,
                                     int edgeBins) {
        computeSineReference(histogram, sineAmpFsRatio, edgeBins, 30);
    }

    /**
     * Same as the 3-arg overload, plus {@code fitPoints}: number of quantile
     * points used to estimate the sine amplitude when {@code sineAmpFsRatio}
     * is not supplied (default 30).  See {@link #estimateAmplitudeFromCDF}
     * for the math.
     *
     * <p>{@code edgeBins} masks the first/last N cells per side of the final
     * weight buffer with the mean of the next two inner bins, because the
     * rail bins absorb the clipped overflow AND ADC noise smears the arcsine
     * singularity across a few cells just inside the rails.  Default 2.
     */
    public void computeSineReference(AdcHistogram histogram,
                                     double sineAmpFsRatio,
                                     int edgeBins,
                                     int fitPoints) {
        if (histogram.getBitDepth() != bitDepth) {
            throw new IllegalArgumentException("Histogram bitDepth " + histogram.getBitDepth()
                    + " does not match buffer bitDepth " + bitDepth);
        }
        long total    = histogram.getTotalCount();
        long midCode  = binCount / 2;
        double lsb    = 2.0 / binCount;     // bin width in normalised voltage [-1, +1]

        double amp = sineAmpFsRatio;
        if (Double.isNaN(amp) || amp <= 0.0) {
            if (total == 0) {
                amp = 1.0;
                log.info("Sine-reference: histogram empty — assuming A = 1.0 FS");
            } else {
                amp = estimateAmplitudeFromCDF(histogram, fitPoints);
                double clipPct = amp > 1.0
                        ? 100.0 * (1.0 - 2.0 * Math.asin(1.0 / amp) / Math.PI)
                        : 0.0;
                log.info("Sine-reference: estimated amplitude A = {} FS  (clipping {} % of each period, fit at {} quantile points in F ∈ [0.10, 0.40] ∪ [0.60, 0.90])",
                        String.format(Locale.US, "%.6f", amp),
                        String.format(Locale.US, "%.4f", clipPct),
                        fitPoints);
            }
        } else {
            log.info("Sine-reference: using supplied amplitude A = {} FS",
                    String.format(Locale.US, "%.6f", amp));
        }

        long nonZero = 0;
        double weightedSum = 0.0;
        long activeBins = 0;
        for (long k = 0; k < binCount; k++) {
            double vLow  = ((double) k - midCode) * lsb;
            double vHigh = vLow + lsb;
            // Extend rail bins outward so the clipped portion (sine outside
            // ±FS) is folded into the matching rail's expected count.
            double vLowEff  = (k == 0)            ? Double.NEGATIVE_INFINITY : vLow;
            double vHighEff = (k == binCount - 1) ? Double.POSITIVE_INFINITY : vHigh;
            double pBin = sineFractionInBin(vLowEff, vHighEff, amp);
            double expected = pBin * total;
            double actual   = histogram.getCount(k);
            if (actual > 0) nonZero++;
            double weight = expected > 1e-9 ? actual / expected : 0.0;
            set(k, (float) weight);
            if (weight > 0.5f && weight < 2.0f) {
                weightedSum += weight;
                activeBins++;
            }
        }
        double avg = activeBins > 0 ? weightedSum / activeBins : 0.0;
        log.info("Sine-reference weighting done. Non-zero bins: {}; mean weight (0.5..2.0 range): {}",
                String.format("%,d", nonZero), String.format(Locale.US, "%.4f", avg));

        // Mask edge bins: rail bins (and a few cells just inside) collect both
        // the clipped portion of an over-FS sine AND the noise-smeared
        // arcsine singularity, so the directly computed weight there is
        // unreliable.  Replace each edge bin with the mean of the two
        // next-inward trustworthy bins.
        if (edgeBins > 0 && (long) edgeBins * 2 + 2 < binCount) {
            float leftA  = get(edgeBins);
            float leftB  = get(edgeBins + 1);
            float rightA = get(binCount - 1 - edgeBins);
            float rightB = get(binCount - 2 - edgeBins);
            float leftFill  = (leftA  + leftB)  / 2.0f;
            float rightFill = (rightA + rightB) / 2.0f;
            for (long k = 0; k < edgeBins; k++) set(k, leftFill);
            for (long k = binCount - edgeBins; k < binCount; k++) set(k, rightFill);
            log.info("Sine-reference: masked {} edge bin(s) per side — left fill {} (from bins {}, {}), right fill {} (from bins {}, {})",
                    edgeBins,
                    String.format(Locale.US, "%.4f", leftFill),  edgeBins, edgeBins + 1,
                    String.format(Locale.US, "%.4f", rightFill), binCount - 1 - edgeBins, binCount - 2 - edgeBins);
        }
    }

    /**
     * Probability that a sine of amplitude {@code amp} (positive) lies in
     * {@code [vLow, vHigh]} on a uniform-time integral.  Returns 0 when the
     * window is fully outside [-amp, +amp]; the inputs may be ±∞ (used for
     * rail bins that absorb the clipped fraction).
     */
    private double sineFractionInBin(double vLow, double vHigh, double amp) {
        if (amp <= 0.0) return 0.0;
        double clo = Math.max(vLow, -amp);
        double chi = Math.min(vHigh,  amp);
        if (chi <= clo) return 0.0;
        return (Math.asin(chi / amp) - Math.asin(clo / amp)) / Math.PI;
    }

    /**
     * Estimates the sine amplitude from the histogram's empirical CDF by
     * inverting the analytical CDF
     *   F(V) = 1/2 + (1/π)·arcsin(V/A)         for V ∈ [-A, +A]
     * at multiple interior quantile points.  At each chosen quantile F_i the
     * empirical histogram CDF gives the voltage V_i where the cumulative
     * count crosses F_i·N_total; the analytic relation then yields
     *   A_i = V_i / sin(π·(F_i − 1/2))
     * The median of {A_i} is returned — robust against drift / non-stable
     * signal poisoning the histogram tails (which would dominate any rail-bin
     * estimator).
     *
     * <p>Quantile points are sampled from {@code F ∈ [0.10, 0.40] ∪
     * [0.60, 0.90]}, deliberately skipping:
     * <ul>
     *   <li>the rails (F &lt; 0.10, F &gt; 0.90) — poisoned by drift, glitches,
     *       and clipping;
     *   <li>the median (F ≈ 0.5) — where {@code sin(π·(F−1/2)) → 0} makes
     *       {@code V/sin(...)} numerically singular.
     * </ul>
     *
     * @param histogram captured ADC histogram
     * @param nPoints   total number of quantile points (split equally
     *                  between the two F bands).  Typical 20–40.
     */
    public double estimateAmplitudeFromCDF(AdcHistogram histogram, int nPoints) {
        long binCount = histogram.getBinCount();
        long total    = histogram.getTotalCount();
        if (total == 0) return 1.0;
        long midCode = binCount / 2;
        double lsb   = 2.0 / binCount;

        final double F_LO_LEFT  = 0.10, F_HI_LEFT  = 0.40;
        final double F_LO_RIGHT = 0.60, F_HI_RIGHT = 0.90;
        int nPerSide = Math.max(1, nPoints / 2);
        int n        = nPerSide * 2;
        double[] targets = new double[n];
        double[] Fvals   = new double[n];
        for (int i = 0; i < nPerSide; i++) {
            double F = F_LO_LEFT + (F_HI_LEFT - F_LO_LEFT) * (i + 0.5) / nPerSide;
            targets[i] = F * total;
            Fvals[i]   = F;
        }
        for (int i = 0; i < nPerSide; i++) {
            double F = F_LO_RIGHT + (F_HI_RIGHT - F_LO_RIGHT) * (i + 0.5) / nPerSide;
            targets[nPerSide + i] = F * total;
            Fvals[nPerSide + i]   = F;
        }

        // One sweep through the histogram, recording the (code, F) pair for
        // each target as soon as the cumulative count crosses it.
        double[] amps = new double[n];
        int valid = 0;
        long cumCount = 0;
        int  targetIdx = 0;
        for (long k = 0; k < binCount && targetIdx < n; k++) {
            cumCount += histogram.getCount(k);
            while (targetIdx < n && cumCount >= targets[targetIdx]) {
                double V = ((double) k + 0.5 - midCode) * lsb;
                double sinArg = Math.sin(Math.PI * (Fvals[targetIdx] - 0.5));
                if (Math.abs(sinArg) > 1e-9) {
                    amps[valid++] = V / sinArg;
                }
                targetIdx++;
            }
        }

        if (valid == 0) return 1.0;
        Arrays.sort(amps, 0, valid);
        return amps[valid / 2];   // median for robustness
    }

    private void set(long code, float value) {
        if (bitDepth < 32) {
            bins[(int) (code & (long)(binCount - 1))] = value;
        } else {
            chunks[(int)(code >>> CHUNK_BITS)][(int)(code & CHUNK_MASK)] = value;
        }
    }

    public float get(long code) {
        if (bitDepth < 32) {
            return bins[(int) (code & (long)(binCount - 1))];
        } else {
            return chunks[(int)(code >>> CHUNK_BITS)][(int)(code & CHUNK_MASK)];
        }
    }

    /**
     * Builds a linearization map: raw code → corrected code.
     *
     * Algorithm:
     *   1. Treat the weight of each bin as a measure of how many output codes it deserves.
     *   2. Compute cumulative sum (CDF) of the weights.
     *   3. Normalize: corrected = round(cdf * (n-1) / total) for full precision.
     *
     * After this call, use {@link #correctedCode(StereoSample)} to remap each sample.
     */
    public void buildCodeMap() {
        log.info("Building code linearization map ({} bins)...", String.format("%,d", binCount));

        if (bitDepth < 32) {
            int    n       = (int) binCount;
            int    maxCode = n - 1;
            codeMapBins    = new float[n];
            double total   = 0;
            for (int i = 0; i < n; i++) {
                total += bins[i];
            }
            if (total == 0) {
                log.warn("All weights are zero – map will be identity.");
                return;
            }
            double cdf = 0;
            for (int i = 0; i < n; i++) {
                cdf += bins[i];
                codeMapBins[i] = (float) /*Math.round*/((cdf * maxCode / total));
            }
        } else {
            codeMapChunks = new float[8][];
            for (int c = 0; c < 8; c++) {
                codeMapChunks[c] = new float[CHUNK_SIZE];
            }
            double total = 0;
            for (int c = 0; c < 8; c++) {
                for (int i = 0; i < CHUNK_SIZE; i++) {
                    total += chunks[c][i];
                }
            }
            if (total == 0) {
                log.warn("All weights are zero – map will be identity.");
                return;
            }
            double cdf    = 0;
            long   maxCode = binCount - 1;
            for (int c = 0; c < 8; c++) {
                float[] chunk = chunks[c];
                float[] map   = codeMapChunks[c];
                for (int i = 0; i < CHUNK_SIZE; i++) {
                    cdf    += chunk[i];
                    map[i]  = (float) (cdf * maxCode / total) + 1;
                }
            }
        }
        log.info("Code map built.");
    }

    /** Maps a raw ADC code to a linearized output code using the built code map. */
    public StereoSampleFloat correctedCode(StereoSample rawCode) {
        StereoSampleFloat res = new StereoSampleFloat();
        /*res.ch0 = rawCode.ch0;
        res.ch1 = rawCode.ch1;*/
        if (bitDepth < 32) {
            res.ch0 = codeMapBins[rawCode.ch0 & (int)(binCount - 1)];
            res.ch1 = codeMapBins[rawCode.ch1 & (int)(binCount - 1)];
        } else {
            res.ch0 = codeMapChunks[rawCode.ch0 >>> CHUNK_BITS][rawCode.ch0 & CHUNK_MASK];
            res.ch1 = codeMapChunks[rawCode.ch1 >>> CHUNK_BITS][rawCode.ch1 & CHUNK_MASK];
        }
        return res;
    }

    public void loadCsv(String filePath) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            br.readLine(); // skip header
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] cols         = line.split(";");
                long     codeUnsigned = Long.parseUnsignedLong(cols[0].trim());
                float    value        = Float.parseFloat(cols[1].trim().replace(',', '.'));
                if (value == 0f) {
                    continue;
                }
                if (bitDepth < 32) {
                    bins[(int) codeUnsigned] = value;
                } else {
                    chunks[(int)(codeUnsigned >>> CHUNK_BITS)][(int)(codeUnsigned & CHUNK_MASK)] = value;
                }
            }
        }
        log.info("Weighted buffer loaded from: {}", filePath);
    }

    // -------------------------------------------------------------------------
    // Voltage scaling
    // -------------------------------------------------------------------------

    public void applyVoltageScale(double scaleVolts) {
        final float voltsPerLSB = (float) (scaleVolts / binCount);
        log.info("Applying voltage scale ({} V, {} V/LSB)...", scaleVolts, voltsPerLSB);

        if (bitDepth < 32) {
            for (int i = 0; i < (int) binCount; i++) {
                bins[i] *= voltsPerLSB;
            }
        } else {
            for (int c = 0; c < 8; c++) {
                float[] chunk = chunks[c];
                for (int i = 0; i < CHUNK_SIZE; i++) {
                    chunk[i] *= voltsPerLSB;
                }
            }
        }

        log.info("Voltage scale applied.");
    }

    // -------------------------------------------------------------------------
    // Iteration
    // -------------------------------------------------------------------------

    public float forEachBucket(int buckets, double scaleVolts,
                              BiConsumer<Double, Float> action) {
        final double voltsPerLSB = scaleVolts / binCount;
        final double bucketSize  = (double) binCount / buckets;

        double[] sums   = new double[buckets];
        int[]    counts = new int[buckets];

        float globalAverage = 0;
        int   globalCount   = 0;

        if (bitDepth < 32) {
            for (int i = 0; i < (int) binCount; i++) {
                if (bins[i] != 0f) {
                    globalAverage += bins[i];
                    globalCount++;
                }
                int idx = (int) Math.min(i / bucketSize, buckets - 1);
                sums[idx]   += bins[i];
                counts[idx]++;
            }
        } else {
            for (int c = 0; c < 8; c++) {
                float[] chunk   = chunks[c];
                long    basePos = (long) c * CHUNK_SIZE;
                for (int i = 0; i < CHUNK_SIZE; i++) {
                    if (bins[i] != 0f) {
                        globalAverage += bins[i];
                        globalCount++;
                    }
                    int idx = (int) Math.min((basePos + i) / bucketSize, buckets - 1);
                    sums[idx]   += chunk[i];
                    counts[idx]++;
                }
            }
        }

        globalAverage /= globalCount;

        for (int b = 0; b < buckets; b++) {
            if (counts[b] > 0) {
                long   centerCode    = (long) ((b + 0.5) * bucketSize);
                double centerVoltage = centerCode * voltsPerLSB;
                float  point         = (float) (sums[b] / (double)counts[b]);
                //if (point < globalAverage * 1.02 && point > globalAverage * 0.98) {
                    action.accept(centerVoltage, point);
                //}
            }
        }
        return globalAverage;
    }

    // -------------------------------------------------------------------------
    // Export
    // -------------------------------------------------------------------------

    public String exportCsv(String directory) throws IOException {
        return exportCsv(directory, "weighted");
    }

    public String exportCsv(String directory, String label) throws IOException {
        String filename = label + "_" +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".csv";
        File outFile = new File(directory, filename);

        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(outFile)))) {
            pw.println("code_unsigned;weighted_value");

            if (bitDepth < 32) {
                for (int i = 0; i < (int) binCount; i++) {
                    if (bins[i] != 0f) {
                        pw.printf(Locale.GERMAN, "%d;%.12f%n", (long) i, bins[i]);
                    }
                }
            } else {
                for (int c = 0; c < 8; c++) {
                    float[] chunk = chunks[c];
                    for (int i = 0; i < CHUNK_SIZE; i++) {
                        if (chunk[i] != 0f) {
                            long unsignedCode = (long) c * CHUNK_SIZE + i;
                            pw.printf(Locale.GERMAN, "%d;%.12f%n", unsignedCode, chunk[i]);
                        }
                    }
                }
            }
        }

        log.info("Weighted buffer exported: {}", outFile.getAbsolutePath());
        return outFile.getAbsolutePath();
    }
}
