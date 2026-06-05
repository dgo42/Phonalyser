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

package org.edgo.audio.measure.adc;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;

import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.fft.FftResult;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.NumberTickUnit;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import lombok.extern.log4j.Log4j2;

/**
 * Regression-based ADC calibration using a sine wave reference.
 *
 * <ol>
 *   <li>Fits an ideal sine (A·sin(ω·n+φ) + DC) to the recorded signal via
 *       least-squares regression: rough frequency from FFT, refined by
 *       golden-section search, final amplitude/phase/DC by linear LS.</li>
 *   <li>Computes the average error (ideal − recorded) per ADC code.</li>
 *   <li>Interpolates missing interior codes linearly from their neighbours.</li>
 *   <li>Exports a {@code weighted_scaled} CSV compatible with
 *       {@link WeightedBuffer#loadCsv(String)} so the result feeds directly
 *       into the existing {@code --process-wav --load-weighted} pipeline.</li>
 * </ol>
 */
@Log4j2
public class RegressionCalibrator {

    // =========================================================================
    // Result
    // =========================================================================

    public class Result {
        /** ADC resolution in bits. */
        public final int    bitDepth;
        /** Total number of ADC codes (= 2^bitDepth). */
        public final int    codeCount;
        /** Refined fundamental frequency of the fitted sine in Hz. */
        public final double fundamentalHz;
        /** Fitted sine amplitude (normalized, −1…+1 scale). */
        public final double amplitude;
        /** Fitted DC offset (normalized). */
        public final double dcOffset;
        /** Fitted initial phase in radians. */
        public final double phase;
        /** RMS of (sample − ideal) after fitting, in normalized units. */
        public final double residualRms;
        /**
         * Average error per ADC code in LSB.
         * Positive = ADC reads too high (ideal is lower than measured).
         * Index is the unsigned ADC code 0…codeCount−1.
         */
        public final double[] avgErrorLsb;
        /** Number of samples that mapped to each ADC code. */
        public final int[]    sampleCount;
        /** True for interior codes that were absent from the signal and interpolated. */
        public final boolean[] interpolated;

        Result(int bitDepth, int codeCount, double fundamentalHz,
               double amplitude, double dcOffset, double phase, double residualRms,
               double[] avgErrorLsb, int[] sampleCount, boolean[] interpolated) {
            this.bitDepth      = bitDepth;
            this.codeCount     = codeCount;
            this.fundamentalHz = fundamentalHz;
            this.amplitude     = amplitude;
            this.dcOffset      = dcOffset;
            this.phase         = phase;
            this.residualRms   = residualRms;
            this.avgErrorLsb   = avgErrorLsb;
            this.sampleCount   = sampleCount;
            this.interpolated  = interpolated;
        }
    }

    // =========================================================================
    // Calibration
    // =========================================================================

    /**
     * Runs regression calibration on a normalized mono signal.
     *
     * @param samples    signal samples, range −1.0…+1.0 (offset-binary origin)
     * @param sampleRate sample rate in Hz
     * @param bitDepth   ADC resolution in bits
     * @return populated {@link Result}
     */
    public Result calibrate(float[] samples, int sampleRate, int bitDepth) {
        int    codeCount = 1 << bitDepth;
        double halfRange = (double) (1 << (bitDepth - 1));

        // --- Step 1: rough frequency from FFT --------------------------------
        int fftSize = Integer.highestOneBit(Math.min(samples.length, 1 << 20));
        FftResult fftResult = new FftAnalyzer().analyze(samples, sampleRate, fftSize, 1);
        double roughFreqHz = fftResult.fundamentalHz;
        log.info("Rough frequency from FFT: {} Hz", String.format(Locale.US, "%.4f", roughFreqHz));

        // --- Step 2: refine frequency via golden-section search --------------
        // Use at most 2^16 samples for the search to keep it fast; the final
        // LS fit uses all samples.
        int searchLen = Math.min(samples.length, 1 << 16);
        float[] searchBuf = searchLen < samples.length
                ? Arrays.copyOf(samples, searchLen)
                : samples;
        double binWidth = (double) sampleRate / fftSize;
        double freqLo   = Math.max(1.0, roughFreqHz - 2.0 * binWidth);
        double freqHi   = roughFreqHz + 2.0 * binWidth;
        double optFreq  = goldenSectionSearch(searchBuf, sampleRate, freqLo, freqHi, 60);
        log.info("Refined frequency:        {} Hz", String.format(Locale.US, "%.8f", optFreq));

        // --- Step 3: linear LS sine fit on all samples -----------------------
        // Model: y[n] = a·sin(ω·n) + b·cos(ω·n) + c
        double[] fit = fitSine(samples, sampleRate, optFreq);
        double a = fit[0], b = fit[1], c = fit[2];
        double amplitude = Math.sqrt(a * a + b * b);
        double phase     = Math.atan2(b, a);
        double dcOffset  = c;
        double omega     = 2.0 * Math.PI * optFreq / sampleRate;

        log.info("Fitted: A={} ({} LSB)  DC={}  phase={} rad",
                String.format(Locale.US, "%.6f", amplitude),
                String.format(Locale.US, "%.2f", amplitude * halfRange),
                String.format(Locale.US, "%.6f", dcOffset),
                String.format(Locale.US, "%.6f", phase));

        // --- Step 4: collect errors per ADC code -----------------------------
        double[] errorSum = new double[codeCount];
        int[]    count    = new int[codeCount];
        double   resSumSq = 0.0;

        double cosOmega = Math.cos(omega), sinOmega = Math.sin(omega);
        double curSin = 0.0, curCos = 1.0;   // sin(0)=0, cos(0)=1

        for (int n = 0; n < samples.length; n++) {
            double ideal = a * curSin + b * curCos + c;
            double error = samples[n] - ideal;   // normalized; positive = ADC read too high
            resSumSq += error * error;

            // normalized sample → unsigned offset-binary ADC code
            int code = (int) Math.round(samples[n] * halfRange + halfRange);
            code = Math.max(0, Math.min(codeCount - 1, code));
            errorSum[code] += error * halfRange;   // convert to LSB
            count[code]++;

            // advance sin/cos incrementally (avoids per-sample trig calls)
            double nextSin = curSin * cosOmega + curCos * sinOmega;
            curCos = curCos * cosOmega - curSin * sinOmega;
            curSin = nextSin;
        }

        double residualRms = Math.sqrt(resSumSq / samples.length);
        log.info("Residual RMS: {} normalized ({} LSB)",
                String.format(Locale.US, "%.6f", residualRms),
                String.format(Locale.US, "%.4f", residualRms * halfRange));

        // --- Step 5: average errors ------------------------------------------
        double[]  avgErrorLsb  = new double[codeCount];
        boolean[] interpolated = new boolean[codeCount];

        for (int code = 0; code < codeCount; code++) {
            if (count[code] > 0) {
                avgErrorLsb[code] = errorSum[code] / count[code];
            }
        }

        // --- Step 6: interpolate interior missing codes ----------------------
        int firstCode = -1, lastCode = -1;
        for (int code = 0; code < codeCount; code++) {
            if (count[code] > 0) {
                if (firstCode < 0) firstCode = code;
                lastCode = code;
            }
        }

        int interpolatedCount = 0, populatedCount = 0;
        if (firstCode >= 0) {
            for (int code = firstCode + 1; code < lastCode; code++) {
                if (count[code] == 0) {
                    // search nearest populated neighbours
                    int lo = code - 1;
                    while (lo > firstCode && count[lo] == 0) lo--;
                    int hi = code + 1;
                    while (hi < lastCode  && count[hi] == 0) hi++;
                    if (count[lo] > 0 && count[hi] > 0) {
                        double t = (double) (code - lo) / (hi - lo);
                        avgErrorLsb[code]  = avgErrorLsb[lo] + t * (avgErrorLsb[hi] - avgErrorLsb[lo]);
                        interpolated[code] = true;
                        interpolatedCount++;
                    }
                } else {
                    populatedCount++;
                }
            }
        }

        int extremeMissing = codeCount - (lastCode - firstCode + 1);
        log.info("Code coverage: first={}, last={}, populated={}, interpolated={}, extreme-missing={}",
                firstCode, lastCode, populatedCount, interpolatedCount, extremeMissing);

        return new Result(bitDepth, codeCount, optFreq, amplitude, dcOffset, phase, residualRms,
                avgErrorLsb, count, interpolated);
    }

    // =========================================================================
    // Frequency optimisation
    // =========================================================================

    /** Golden-section minimisation of the LS residual over frequency. */
    private double goldenSectionSearch(float[] samples, int sampleRate,
                                       double lo, double hi, int iters) {
        final double phi = (Math.sqrt(5.0) - 1.0) / 2.0;
        double c = hi - phi * (hi - lo);
        double d = lo + phi * (hi - lo);
        double fc = rss(samples, sampleRate, c);
        double fd = rss(samples, sampleRate, d);
        for (int i = 0; i < iters; i++) {
            if (fc < fd) {
                hi = d; d = c; fd = fc;
                c = hi - phi * (hi - lo);
                fc = rss(samples, sampleRate, c);
            } else {
                lo = c; c = d; fc = fd;
                d = lo + phi * (hi - lo);
                fd = rss(samples, sampleRate, d);
            }
        }
        return (lo + hi) / 2.0;
    }

    /** Residual sum of squares after fitting a sine at the given frequency. */
    private double rss(float[] samples, int sampleRate, double freqHz) {
        double[] fit = fitSine(samples, sampleRate, freqHz);
        double a = fit[0], b = fit[1], cv = fit[2];
        double omega    = 2.0 * Math.PI * freqHz / sampleRate;
        double cosOmega = Math.cos(omega), sinOmega = Math.sin(omega);
        double curSin = 0.0, curCos = 1.0;
        double rss = 0.0;
        for (int n = 0; n < samples.length; n++) {
            double e = samples[n] - (a * curSin + b * curCos + cv);
            rss += e * e;
            double nextSin = curSin * cosOmega + curCos * sinOmega;
            curCos = curCos * cosOmega - curSin * sinOmega;
            curSin = nextSin;
        }
        return rss;
    }

    // =========================================================================
    // Linear LS sine fitting
    // =========================================================================

    /**
     * Fits y[n] = a·sin(ω·n) + b·cos(ω·n) + c via normal equations.
     *
     * @return [a, b, c]
     */
    double[] fitSine(float[] samples, int sampleRate, double freqHz) {
        double omega    = 2.0 * Math.PI * freqHz / sampleRate;
        double cosOmega = Math.cos(omega), sinOmega = Math.sin(omega);
        double curSin = 0.0, curCos = 1.0;
        int N = samples.length;

        // Accumulate normal-equation matrix entries
        double ss = 0, sc = 0, s1 = 0, cc = 0, c1 = 0;
        double ys = 0, yc = 0, y1 = 0;
        for (int n = 0; n < N; n++) {
            double sn = curSin, cn = curCos, yn = samples[n];
            ss += sn * sn;  sc += sn * cn;  s1 += sn;
            cc += cn * cn;  c1 += cn;
            ys += yn * sn;  yc += yn * cn;  y1 += yn;
            double nextSin = sn * cosOmega + cn * sinOmega;
            curCos = cn * cosOmega - sn * sinOmega;
            curSin = nextSin;
        }
        // [ss sc s1] [a]   [ys]
        // [sc cc c1] [b] = [yc]
        // [s1 c1  N] [c]   [y1]
        return solve3x3(new double[][]{{ss, sc, s1}, {sc, cc, c1}, {s1, c1, N}},
                        new double[]{ys, yc, y1});
    }

    /** Gaussian elimination with partial pivoting for a 3×3 system. */
    private double[] solve3x3(double[][] A, double[] rhs) {
        double[][] aug = new double[3][4];
        for (int i = 0; i < 3; i++) {
            System.arraycopy(A[i], 0, aug[i], 0, 3);
            aug[i][3] = rhs[i];
        }
        for (int col = 0; col < 3; col++) {
            int maxRow = col;
            for (int row = col + 1; row < 3; row++) {
                if (Math.abs(aug[row][col]) > Math.abs(aug[maxRow][col])) maxRow = row;
            }
            double[] tmp = aug[col]; aug[col] = aug[maxRow]; aug[maxRow] = tmp;
            double diag = aug[col][col];
            if (Math.abs(diag) < 1e-15) continue;
            for (int row = col + 1; row < 3; row++) {
                double f = aug[row][col] / diag;
                for (int j = col; j <= 3; j++) aug[row][j] -= f * aug[col][j];
            }
        }
        double[] x = new double[3];
        for (int i = 2; i >= 0; i--) {
            x[i] = aug[i][3];
            for (int j = i + 1; j < 3; j++) x[i] -= aug[i][j] * x[j];
            x[i] /= aug[i][i];
        }
        return x;
    }

    // =========================================================================
    // Export
    // =========================================================================

    /**
     * Exports regression calibration as a {@code weighted_scaled}-compatible CSV.
     *
     * <p>The {@code weighted_value} column contains the effective bin width in volts
     * for each ADC code — values close to {@code scale/2^bitDepth}, exactly like
     * the {@code weighted_scaled} files produced by histogram-based calibration.
     *
     * <p>Per-code error averages are noisy (few samples per code), so
     * {@code avgErrorLsb} is smoothed with a Gaussian-weighted moving average
     * before the gradient is computed.  Extreme codes (outside the sine's
     * amplitude) receive exactly {@code voltPerLsb}.
     *
     * @param r          calibration result
     * @param scaleVolts full-scale voltage range (e.g. 2.0 for ±1 V)
     * @param directory  output directory
     * @return absolute path of the written file
     */
    public String exportCsv(Result r, double scaleVolts, String directory) throws IOException {
        String ts         = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File   outFile    = new File(directory, "regression_calibration_" + ts + ".csv");
        double voltPerLsb = scaleVolts / r.codeCount;

        // Smooth avgErrorLsb to suppress per-code noise before taking the gradient.
        // Window ≈ sqrt(codeCount) gives a good balance between noise suppression
        // and preserving real DNL structure.
        double[] smoothedError = gaussianSmooth(r.avgErrorLsb, r.sampleCount,
                                                r.interpolated, r.codeCount);

        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(outFile)))) {
            pw.println("code_hex;code_unsigned;weighted_value");

            int hexDigits = r.bitDepth / 4;
            for (int code = 0; code < r.codeCount; code++) {
                double width;
                if (code < r.codeCount - 1
                        && (r.sampleCount[code] > 0 || r.interpolated[code])
                        && (r.sampleCount[code + 1] > 0 || r.interpolated[code + 1])) {
                    // width[k] = voltPerLsb − (smoothedError[k+1] − smoothedError[k]) × voltPerLsb
                    width = voltPerLsb * (1.0 - (smoothedError[code + 1] - smoothedError[code]));
                } else {
                    width = voltPerLsb;
                }
                pw.printf(Locale.GERMAN, "0x%0" + hexDigits + "X;%d;%.12f%n",
                        code, (long) code, width);
            }
        }
        log.info("Regression calibration CSV saved: {}", outFile.getAbsolutePath());
        return outFile.getAbsolutePath();
    }

    /**
     * Gaussian-weighted moving average of {@code error}, operating only over
     * covered codes (sampleCount > 0 or interpolated).  Uncovered codes keep 0.
     * Sigma = sqrt(codeCount) / 2, truncated at 3σ.
     */
    private double[] gaussianSmooth(double[] error, int[] sampleCount,
                                    boolean[] interpolated, int n) {
        double sigma  = Math.max(2.0, Math.sqrt(n) / 2.0);
        int    radius = (int) Math.ceil(3.0 * sigma);

        // Precompute Gaussian kernel
        double[] kernel = new double[radius + 1];
        for (int i = 0; i <= radius; i++) {
            kernel[i] = Math.exp(-0.5 * (i / sigma) * (i / sigma));
        }

        double[] result = new double[n];
        for (int k = 0; k < n; k++) {
            if (sampleCount[k] == 0 && !interpolated[k]) {
                continue;   // extreme code — leave 0
            }
            double weightSum = 0.0, valueSum = 0.0;
            for (int d = -radius; d <= radius; d++) {
                int j = k + d;
                if (j < 0 || j >= n) continue;
                if (sampleCount[j] == 0 && !interpolated[j]) continue;
                double w = kernel[Math.abs(d)];
                valueSum  += error[j] * w;
                weightSum += w;
            }
            result[k] = weightSum > 0 ? valueSum / weightSum : error[k];
        }
        log.debug("Gaussian smoothing done: sigma={:.1f}, radius={}", sigma, radius);
        return result;
    }

    /**
     * Exports a PNG chart of delta_voltage vs ADC input voltage.
     * X axis: ideal input voltage derived from code index and scaleVolts.
     * Y axis: correction delta in volts (negative = ADC reads too high).
     * Absent/extreme codes (delta=0, sampleCount=0) are excluded from the chart.
     *
     * @param r          calibration result
     * @param scaleVolts full-scale voltage range
     * @param width      chart width in pixels (also controls bucket resolution)
     * @param height     chart height in pixels
     * @param directory  output directory
     * @return absolute path of the written PNG file
     */
    public String exportChart(Result r, double scaleVolts,
                              int width, int height, String directory) throws IOException {
        String ts         = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        double voltPerLsb = scaleVolts / r.codeCount;
        double bucketSize = (double) r.codeCount / width;

        double[] bucketSum   = new double[width];
        int[]    bucketCount = new int[width];
        double   deltaMin    = Double.MAX_VALUE;
        double   deltaMax    = -Double.MAX_VALUE;

        for (int code = 0; code < r.codeCount; code++) {
            if (r.sampleCount[code] == 0 && !r.interpolated[code]) {
                continue;   // extreme / absent code — no data
            }
            double deltaVoltage = -r.avgErrorLsb[code] * voltPerLsb;
            int bucket = (int) Math.min(code / bucketSize, width - 1);
            bucketSum[bucket]   += deltaVoltage;
            bucketCount[bucket]++;
            if (deltaVoltage < deltaMin) deltaMin = deltaVoltage;
            if (deltaVoltage > deltaMax) deltaMax = deltaVoltage;
        }

        XYSeries series = new XYSeries("Delta Voltage");
        for (int b = 0; b < width; b++) {
            if (bucketCount[b] > 0) {
                double centerVoltage = (b + 0.5) * bucketSize * voltPerLsb;
                series.add(centerVoltage, bucketSum[b] / bucketCount[b]);
            }
        }

        XYSeriesCollection dataset = new XYSeriesCollection(series);
        JFreeChart chart = ChartFactory.createXYLineChart(
                "Regression Calibration — Delta Voltage",
                "Voltage (V)", "Delta (V)",
                dataset, PlotOrientation.VERTICAL, false, false, false);

        XYPlot plot = (XYPlot) chart.getPlot();

        NumberAxis domain = (NumberAxis) plot.getDomainAxis();
        domain.setRange(0.0, scaleVolts);
        domain.setTickUnit(new NumberTickUnit(scaleVolts / 10.0));
        domain.setVerticalTickLabels(true);

        double margin = Math.max(Math.abs(deltaMin), Math.abs(deltaMax)) * 0.1;
        NumberAxis range = (NumberAxis) plot.getRangeAxis();
        range.setRange(deltaMin - margin, deltaMax + margin);

        File outFile = new File(directory, "regression_chart_" + ts + ".png");
        ChartUtils.saveChartAsPNG(outFile, chart, width, height);
        log.info("Regression chart saved: {}", outFile.getAbsolutePath());
        return outFile.getAbsolutePath();
    }
}
