package org.edgo.audio.measure.chart;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
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
 * Exports a WeightedBuffer as a PNG chart.
 * X axis: voltage (V), Y axis: weighted value (count / moving-average).
 */
@Log4j2
public class ChartExporter {

    /**
     * Generates a PNG chart from the weighted buffer and saves it to disk.
     *
     * @param weighted    source weighted buffer (voltage scale already applied)
     * @param scaleVolts  full ADC voltage range in volts (e.g. 3.4 for ±1.7 V)
     * @param width       image width in pixels
     * @param height      image height in pixels
     * @param directory   output directory
     * @return absolute path of the written PNG file
     */
    public String export(WeightedBuffer weighted, double scaleVolts,
                         int width, int height, String directory) throws IOException {
        log.info("Building chart data...");

        XYSeries series = new XYSeries("Weighted ADC");
        // Shift voltage to bipolar (0 V at mid-range) for chart display.
        final double voltageOffset = scaleVolts / 2.0;
        float globalAverage = weighted.forEachBucket(width, scaleVolts,
                (voltage, value) -> series.add(voltage - voltageOffset, value));

        log.info("Chart series: {} points", series.getItemCount());

        XYSeriesCollection dataset = new XYSeriesCollection(series);
        JFreeChart chart = ChartFactory.createXYLineChart(
                "ADC Calibration",
                "Voltage (V)",
                "Weighted Value",
                dataset,
                PlotOrientation.VERTICAL,
                false,  // legend
                false,  // tooltips
                false   // URLs
        );
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

        // Auto-scale Y to the actual data range — the previous fixed
        // [0.85·avg, 1.01·avg] window was asymmetric and clipped weights
        // above the average, hiding DNL spikes and the noise-smeared rail
        // dip on the upper side.
        NumberAxis range = (NumberAxis) xyPlot.getRangeAxis();
        double minVal = Double.POSITIVE_INFINITY;
        double maxVal = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < series.getItemCount(); i++) {
            double v = series.getY(i).doubleValue();
            if (v < minVal) minVal = v;
            if (v > maxVal) maxVal = v;
        }
        if (!Double.isFinite(minVal) || maxVal <= minVal) {
            // Empty / flat data — fall back to a symmetric ±15 % view
            double avg = globalAverage > 0 ? globalAverage : 1.0;
            minVal = avg * 0.85;
            maxVal = avg * 1.15;
        }
        double pad = Math.max(0.05 * (maxVal - minVal), 1e-9);
        range.setRange(minVal - pad, maxVal + pad);
        range.setAutoTickUnitSelection(true);
        range.setLabelFont(ChartStyle.AXIS_LABEL_FONT);
        range.setTickLabelFont(ChartStyle.AXIS_TICK_FONT);

        String filename = "chart_" +
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".png";
        File outFile = new File(directory, filename);
        ChartUtils.saveChartAsPNG(outFile, chart, width, height);

        log.info("Chart saved: {}  (Y range {} .. {})",
                outFile.getAbsolutePath(),
                String.format(Locale.US, "%.6f", minVal - pad),
                String.format(Locale.US, "%.6f", maxVal + pad));
        return outFile.getAbsolutePath();
    }
}
