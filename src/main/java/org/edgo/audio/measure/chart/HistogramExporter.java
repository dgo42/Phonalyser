package org.edgo.audio.measure.chart;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.edgo.audio.measure.adc.AdcHistogram;
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
 * Exports the RAW {@link AdcHistogram} as a PNG chart — counts per code-bucket
 * vs voltage, before any DNL weighting.  Useful as a sanity check: you can
 * eyeball the actual ADC code distribution (e.g. arcsine PDF for a sine input,
 * with rail-bin spikes when the signal over-drives FS) and confirm it matches
 * what the analytic reference assumes.
 *
 * <p>Voltage convention matches {@link WeightedBuffer#forEachBucket}:
 * {@code voltage = (code + 0.5) · scaleVolts / binCount}, i.e. an offset-binary
 * sweep from 0 V (lowest code) to {@code scaleVolts} (highest code).
 */
@Log4j2
public class HistogramExporter {

    /**
     * Renders a histogram chart and saves it to disk.
     *
     * @param histogram   raw ADC histogram
     * @param scaleVolts  full ADC voltage range (e.g. 5.0 for ±2.5 V_p)
     * @param width       image width in pixels (also the bucket count)
     * @param height      image height in pixels
     * @param directory   output directory
     * @return absolute path of the written PNG file
     */
    public String export(AdcHistogram histogram, double scaleVolts,
                         int width, int height, String directory) throws IOException {
        final long binCount    = histogram.getBinCount();
        final int  buckets     = Math.min(width, (int) Math.min(binCount, Integer.MAX_VALUE));
        final double bucketSize = (double) binCount / buckets;
        final double voltsPerLSB = scaleVolts / (double) binCount;

        log.info("Building raw-histogram chart ({} buckets over {} bins, {} V full scale)...",
                buckets, String.format("%,d", binCount),
                String.format(Locale.US, "%.4f", scaleVolts));

        // Sum counts per bucket.
        long[] sums = new long[buckets];
        for (long code = 0; code < binCount; code++) {
            int idx = (int) Math.min(code / bucketSize, buckets - 1);
            sums[idx] += histogram.getCount(code);
        }

        // Detect rail-bin clipping spikes: if the first/last bucket's total
        // significantly exceeds the peak of the inner buckets, the rail bin
        // is accumulating clipped overflow that would compress the chart's
        // Y range.  Drop only those outlier buckets; otherwise keep them so
        // the naturally rising arcsine tail stays visible.
        final double SPIKE_RATIO = 3.0;
        long peakInner = 0;
        for (int b = 1; b < buckets - 1; b++) {
            if (sums[b] > peakInner) peakInner = sums[b];
        }
        boolean skipFirst = peakInner > 0 && sums[0]            > peakInner * SPIKE_RATIO;
        boolean skipLast  = peakInner > 0 && sums[buckets - 1]  > peakInner * SPIKE_RATIO;

        int firstBucket = skipFirst ? 1            : 0;
        int lastBucket  = skipLast  ? buckets - 2  : buckets - 1;

        XYSeries series = new XYSeries("Raw histogram");
        long minSum = Long.MAX_VALUE;
        long maxSum = Long.MIN_VALUE;
        for (int b = firstBucket; b <= lastBucket; b++) {
            // Voltage at the bucket centre — bipolar, 0 V at mid-range.
            double centerVoltage = ((b + 0.5) * bucketSize) * voltsPerLSB - scaleVolts / 2.0;
            series.add(centerVoltage, (double) sums[b]);
            if (sums[b] < minSum) minSum = sums[b];
            if (sums[b] > maxSum) maxSum = sums[b];
        }
        if (minSum > maxSum) { minSum = 0; maxSum = 1; }
        if (skipFirst || skipLast) {
            String which = skipFirst && skipLast ? "first and last"
                         : skipFirst             ? "first"
                                                 : "last";
            log.info("Histogram chart: dropped {} bucket as rail-bin clipping spike (first={}, last={}, inner peak={}, ratio threshold {}×)",
                    which,
                    String.format("%,d", sums[0]),
                    String.format("%,d", sums[buckets - 1]),
                    String.format("%,d", peakInner),
                    String.format(Locale.US, "%.1f", SPIKE_RATIO));
        }

        XYSeriesCollection dataset = new XYSeriesCollection(series);
        JFreeChart chart = ChartFactory.createXYLineChart(
                "ADC Raw Histogram",
                "Voltage (V)",
                "Count",
                dataset,
                PlotOrientation.VERTICAL,
                false, false, false);

        XYPlot xyPlot = (XYPlot) chart.getPlot();
        xyPlot.setDomainGridlinesVisible(true);
        xyPlot.setDomainGridlinePaint(ChartStyle.GRID_LINE_COLOR);
        xyPlot.setRangeGridlinesVisible(true);
        xyPlot.setRangeGridlinePaint(ChartStyle.GRID_LINE_COLOR);

        NumberAxis domain = (NumberAxis) xyPlot.getDomainAxis();
        domain.setRange(-scaleVolts / 2.0, scaleVolts / 2.0);
        // Voltage-axis ticks: tick unit chosen so ticks land every ~40..100 px
        // (40 px @ 1920 wide, 100 px @ 5000 wide, linear in between).  Format
        // string passed explicitly — leaving NumberTickUnit's default DecimalFormat
        // produces unlabeled ticks under some locales.
        double step = ChartStyle.chooseVoltageTickStep(scaleVolts, width);
        DecimalFormat fmt = (DecimalFormat) NumberFormat.getNumberInstance(Locale.US);
        int decimals = step >= 1.0 ? 0 : (int) Math.ceil(-Math.log10(step));
        StringBuilder pat = new StringBuilder("0");
        if (decimals > 0) { pat.append('.'); for (int i = 0; i < decimals; i++) pat.append('0'); }
        fmt.applyPattern(pat.toString());
        domain.setTickUnit(new NumberTickUnit(step, fmt));
        domain.setLabelFont(ChartStyle.AXIS_LABEL_FONT);
        domain.setTickLabelFont(ChartStyle.AXIS_TICK_FONT);

        NumberAxis range = (NumberAxis) xyPlot.getRangeAxis();
        // Auto-scale Y to actual data with a small pad.  Counts are integers ≥ 0,
        // so let the lower bound start at 0 (more natural for a raw histogram)
        // unless minSum is itself well above 0 and showing a meaningful floor.
        long span = Math.max(1, maxSum - minSum);
        long pad  = Math.max(1, span / 20);
        long yLow = Math.max(0, minSum - pad);
        range.setRange(yLow, maxSum + pad);
        range.setAutoTickUnitSelection(true);
        range.setLabelFont(ChartStyle.AXIS_LABEL_FONT);
        range.setTickLabelFont(ChartStyle.AXIS_TICK_FONT);

        String filename = "histogram_chart_" +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".png";
        File outFile = new File(directory, filename);
        ChartUtils.saveChartAsPNG(outFile, chart, width, height);

        log.info("Histogram chart saved: {}  (Y range {} .. {}, total samples {})",
                outFile.getAbsolutePath(),
                String.format("%,d", yLow),
                String.format("%,d", maxSum + pad),
                String.format("%,d", histogram.getTotalCount()));
        return outFile.getAbsolutePath();
    }
}
