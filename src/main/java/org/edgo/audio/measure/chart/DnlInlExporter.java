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

package org.edgo.audio.measure.chart;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.edgo.audio.measure.adc.WeightedBuffer;
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
 * Computes DNL and INL from a {@link WeightedBuffer} and exports CSV files and PNG charts.
 *
 * Definitions:
 *   DNL[i] = weighted[i] − 1.0                  (deviation of each code width from ideal, in LSB)
 *   INL[i] = Σ DNL[0..i]                        (running integral of DNL, in LSB)
 *
 * A single pass over the buffer produces both metrics simultaneously.
 * Chart X axis: voltage derived from code index and full-scale range.
 * Chart Y axis: metric value in LSB.
 */
@Log4j2
public class DnlInlExporter {

    /**
     * Computes DNL and INL from {@code weighted}, then saves:
     * <ul>
     *   <li>dnl_&lt;ts&gt;.csv      — code_hex;code_unsigned;dnl_lsb</li>
     *   <li>inl_&lt;ts&gt;.csv      — code_hex;code_unsigned;inl_lsb</li>
     *   <li>dnl_chart_&lt;ts&gt;.png</li>
     *   <li>inl_chart_&lt;ts&gt;.png</li>
     * </ul>
     *
     * @param weighted    raw weighted buffer (voltage scale must NOT have been applied)
     * @param scaleVolts  full ADC input voltage range in volts (used for chart X axis only)
     * @param width       chart width in pixels (also controls bucket count for downsampling)
     * @param height      chart height in pixels
     * @param directory   output directory
     */
    public void export(WeightedBuffer weighted, double scaleVolts,
                       int width, int height, String directory) throws IOException {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        File dnlCsvFile = new File(directory, "dnl_" + ts + ".csv");
        File inlCsvFile = new File(directory, "inl_" + ts + ".csv");

        long   binCount     = weighted.getBinCount();
        int    bitDepth     = weighted.getBitDepth();
        int    hexDigits    = bitDepth / 4;
        double voltsPerCode = scaleVolts / binCount;
        double bucketSize   = (double) binCount / width;

        double[] dnlBucketSum = new double[width];
        double[] inlBucketSum = new double[width];
        int[]    bucketCount  = new int[width];

        double inlAccum = 0.0;

        log.info("Computing DNL/INL ({} bins)...", String.format("%,d", binCount));

        try (PrintWriter dnlPw = new PrintWriter(new BufferedWriter(new FileWriter(dnlCsvFile)));
             PrintWriter inlPw = new PrintWriter(new BufferedWriter(new FileWriter(inlCsvFile)))) {

            dnlPw.println("code_hex;code_unsigned;dnl_lsb");
            inlPw.println("code_hex;code_unsigned;inl_lsb");

            float prevWighted = 1;
            for (long code = 1; code < binCount; code++) {
                float  w      = weighted.get(code);
                double dnl    = w - prevWighted;
                prevWighted   = w;
                inlAccum     += dnl;

                dnlPw.printf(Locale.GERMAN, "0x%0" + hexDigits + "X;%d;%.9f%n", code, code, dnl);
                inlPw.printf(Locale.GERMAN, "0x%0" + hexDigits + "X;%d;%.9f%n", code, code, inlAccum);

                int bucket = (int) Math.min(code / bucketSize, width - 1);
                dnlBucketSum[bucket] += dnl;
                inlBucketSum[bucket] += inlAccum;
                bucketCount[bucket]++;
            }
        }

        log.info("DNL CSV saved: {}", dnlCsvFile.getAbsolutePath());
        log.info("INL CSV saved: {}", inlCsvFile.getAbsolutePath());

        XYSeries dnlSeries = new XYSeries("DNL");
        XYSeries inlSeries = new XYSeries("INL");

        double dnlMin = Double.MAX_VALUE,  dnlMax = -Double.MAX_VALUE;
        double inlMin = Double.MAX_VALUE,  inlMax = -Double.MAX_VALUE;

        for (int b = 0; b < width; b++) {
            if (bucketCount[b] > 0) {
                double centerVoltage = (b + 0.5) * bucketSize * voltsPerCode;
                double dnlAvg        = dnlBucketSum[b] / bucketCount[b];
                double inlAvg        = inlBucketSum[b] / bucketCount[b];
                dnlSeries.add(centerVoltage, dnlAvg);
                inlSeries.add(centerVoltage, inlAvg);
                if (dnlAvg < dnlMin) {
                    dnlMin = dnlAvg;
                }
                if (dnlAvg > dnlMax) {
                    dnlMax = dnlAvg;
                }
                if (inlAvg < inlMin) {
                    inlMin = inlAvg;
                }
                if (inlAvg > inlMax) {
                    inlMax = inlAvg;
                }
            }
        }

        saveChart(dnlSeries, "DNL", "DNL (LSB)", scaleVolts, dnlMin, dnlMax,
                width, height, directory, "dnl_chart_" + ts + ".png");
        saveChart(inlSeries, "INL", "INL (LSB)", scaleVolts, inlMin, inlMax,
                width, height, directory, "inl_chart_" + ts + ".png");
    }

    private void saveChart(XYSeries series, String title, String yLabel,
                           double scaleVolts, double yMin, double yMax,
                           int width, int height, String directory, String filename) throws IOException {
        XYSeriesCollection dataset = new XYSeriesCollection(series);
        JFreeChart chart = ChartFactory.createXYLineChart(
                title, "Voltage (V)", yLabel, dataset,
                PlotOrientation.VERTICAL, false, false, false);

        XYPlot plot = (XYPlot) chart.getPlot();

        NumberAxis domain = (NumberAxis) plot.getDomainAxis();
        domain.setRange(0.0, scaleVolts);
        domain.setTickUnit(new NumberTickUnit(scaleVolts / 10.0));
        domain.setVerticalTickLabels(true);

        double margin = Math.max(Math.abs(yMin), Math.abs(yMax)) * 0.1;
        NumberAxis range = (NumberAxis) plot.getRangeAxis();
        range.setRange(yMin - margin, yMax + margin);

        File outFile = new File(directory, filename);
        ChartUtils.saveChartAsPNG(outFile, chart, width, height);
        log.info("{} chart saved: {}", title, outFile.getAbsolutePath());
    }
}
