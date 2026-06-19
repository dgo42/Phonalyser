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

import lombok.extern.log4j.Log4j2;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.NumberTickUnit;

import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Exports a waveform chart (amplitude vs. time) as PNG.
 *
 * Samples are expected in offset-binary format as produced by CsjsoundRecorder
 * (unsigned range 0..2^bitDepth-1). They are converted to voltage using:
 *   voltage = (unsigned_sample - 2^(bitDepth-1)) * scaleVolts / 2^(bitDepth-1)
 *
 * For mapped samples (float), use the overload that accepts float[].
 */
@Log4j2
public class WaveformExporter {

    /**
     * Exports a waveform from raw integer samples (offset-binary, as from CsjsoundRecorder).
     *
     * @param samples      offset-binary samples (all or as many as needed)
     * @param sampleRate   sample rate in Hz
     * @param bitDepth     bit depth (8, 16 or 24)
     * @param scaleVolts   full-scale voltage range (peak-to-peak)
     * @param durationMs   waveform window to display in milliseconds (max 10 ms)
     * @param width        chart width in pixels
     * @param height       chart height in pixels
     * @param directory    output directory
     */
    public String export(int[] samples, int sampleRate, int bitDepth, double scaleVolts,
                         double durationMs, int width, int height, String directory) throws IOException {
        if (bitDepth > 24) {
            throw new IllegalArgumentException("Max supported bit depth for waveform is 24");
        }
        int    sampleCount = Math.min(samples.length, (int) Math.ceil(durationMs * sampleRate / 1000.0));
        long   halfRange   = 1L << (bitDepth - 1);
        double voltPerLSB  = scaleVolts / halfRange;

        XYSeries series = new XYSeries("Waveform");
        for (int i = 0; i < sampleCount; i++) {
            double timeMs   = i * 1000.0 / sampleRate;
            long   unsigned = samples[i] & ((1L << bitDepth) - 1);
            double voltage  = (unsigned - halfRange) * voltPerLSB;
            series.add(timeMs, voltage);
        }

        return writeChart(series, "Amplitude (V)", durationMs, width, height, directory);
    }

    // -------------------------------------------------------------------------

    private String writeChart(XYSeries series, String yLabel, double durationMs,
                              int width, int height, String directory) throws IOException {
        XYSeriesCollection dataset = new XYSeriesCollection(series);
        JFreeChart chart = ChartFactory.createXYLineChart(
                "Waveform",
                "Time (ms)",
                yLabel,
                dataset,
                PlotOrientation.VERTICAL,
                false, false, false
        );

        XYPlot plot = (XYPlot) chart.getPlot();
        NumberAxis domainAxis = (NumberAxis) plot.getDomainAxis();
        domainAxis.setRange(0.0, durationMs);
        domainAxis.setTickUnit(new NumberTickUnit(durationMs / 10.0));
        domainAxis.setVerticalTickLabels(true);

        String filename = "waveform_" +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".png";
        File outFile = new File(directory, filename);
        ChartUtils.saveChartAsPNG(outFile, chart, width, height);

        log.info("Waveform chart saved: {}", outFile.getAbsolutePath());
        return outFile.getAbsolutePath();
    }
}
