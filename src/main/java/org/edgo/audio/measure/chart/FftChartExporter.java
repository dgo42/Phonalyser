package org.edgo.audio.measure.chart;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.fft.FftResult;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.annotations.XYTextAnnotation;
import org.jfree.chart.axis.AxisLocation;
import org.jfree.chart.axis.AxisState;
import org.jfree.chart.axis.LogAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.NumberTick;
import org.jfree.chart.axis.NumberTickUnit;
import org.jfree.chart.axis.TickType;
import org.jfree.chart.plot.IntervalMarker;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.ui.Layer;
import org.jfree.chart.ui.RectangleEdge;
import org.jfree.chart.ui.TextAnchor;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import lombok.extern.log4j.Log4j2;

/**
 * Renders an {@link FftResult} as a PNG chart -- log-frequency
 * X axis, dBV (or dBFS) Y axis, harmonic peak markers + annotations, a
 * top-right info table with THD / SNR / ENOB / per-harmonic metrics,
 * and a bottom-left corner box with optional caller comment and averaging
 * cycle count.
 *
 * <p>Extracted from {@link FftAnalyzer} so the analyser stays focused
 * on analysis and the chart concern lives in the chart package alongside
 * the other chart exporters.
 */
@Log4j2
public class FftChartExporter {

    private FftChartExporter() {}

    public static String exportChart(FftResult r, int width, int height,
                                     String directory) throws IOException {
        return exportChart(r, width, height, directory, null);
    }

    public static String exportChart(FftResult r, int width, int height,
                                     String directory, String comment) throws IOException {
        return exportChart(r, width, height, directory, comment, false);
    }

    public static String exportChart(FftResult r, int width, int height,
                                     String directory, String comment,
                                     boolean harmonicsSubtracted) throws IOException {
        return exportChart(r, width, height, directory, comment, harmonicsSubtracted, null);
    }

    public static String exportChart(FftResult r, int width, int height,
                                     String directory, String comment,
                                     boolean harmonicsSubtracted, String filePrefix) throws IOException {
        return exportChart(r, width, height, directory, comment, harmonicsSubtracted, filePrefix, null);
    }

    public static String exportChart(FftResult r, int width, int height,
                                     String directory, String comment,
                                     boolean harmonicsSubtracted, String filePrefix,
                                     Double genFreqHz) throws IOException {
        return exportChart(r, width, height, directory, comment, harmonicsSubtracted,
                filePrefix, genFreqHz, null, null, null, null);
    }

    public static String exportChart(FftResult r, int width, int height,
                                     String directory, String comment,
                                     boolean harmonicsSubtracted, String filePrefix,
                                     Double genFreqHz,
                                     double[] overlayFreqs, double[] overlayDbFs) throws IOException {
        return exportChart(r, width, height, directory, comment, harmonicsSubtracted,
                filePrefix, genFreqHz, overlayFreqs, overlayDbFs, null, null);
    }

    /**
     * Master overload -- paints the chart, optionally overlays a calibration
     * trace (dashed green) and pre-correction harmonic peaks (blue dots), and
     * saves the result as PNG.  Pass null for either pair of overlay/preCorr
     * arrays to suppress that layer.  Y values supplied in dBFS are shifted
     * internally onto whichever primary axis is in use (dBV when
     * r.fundRefDbV is set, otherwise dBFS).
     */
    public static String exportChart(FftResult r, int width, int height,
                                     String directory, String comment,
                                     boolean harmonicsSubtracted, String filePrefix,
                                     Double genFreqHz,
                                     double[] overlayFreqs, double[] overlayDbFs,
                                     double[] preCorrFreqs, double[] preCorrDbFs) throws IOException {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        boolean hasDbv = !Double.isNaN(r.fundRefDbV);
        double  dbFsToDbV = hasDbv ? (r.fundRefDbV - r.fundamentalDbFs) : 0.0;
        String  primaryAxisLabel = hasDbv ? "Amplitude (dBV)" : "Amplitude (dBFS)";

        double fundDisplayDbV = !Double.isNaN(r.fundamentalTrueDbV)
                ? r.fundamentalTrueDbV
                : r.fundamentalDbFs + dbFsToDbV;

        XYSeries spectrum = new XYSeries("Spectrum");
        for (int k = 1; k <= r.fftSize / 2; k++) {
            double freq = k * r.freqResolution;
            if (freq >= 1.0) {
                double y = (k == r.fundamentalBin && !Double.isNaN(r.fundamentalTrueDbV))
                        ? fundDisplayDbV
                        : r.amplitudeDbFs[k] + dbFsToDbV;
                spectrum.add(freq, y);
            }
        }

        XYSeries harmPeaks = new XYSeries("Harmonics");
        harmPeaks.add(r.fundamentalHz, fundDisplayDbV);
        for (int h = 0; h < r.harmonicCount; h++) {
            if (r.harmonicBins[h] > 0) {
                harmPeaks.add(r.harmonicHz[h], r.harmonicDbFs[h] + dbFsToDbV);
            }
        }

        XYSeriesCollection dataset = new XYSeriesCollection();
        dataset.addSeries(spectrum);
        dataset.addSeries(harmPeaks);

        boolean hasOverlay = overlayFreqs != null && overlayDbFs != null
                && overlayFreqs.length == overlayDbFs.length
                && overlayFreqs.length >= 2;
        if (hasOverlay) {
            XYSeries overlay = new XYSeries("Cal (inverted)");
            for (int i = 0; i < overlayFreqs.length; i++) {
                if (overlayFreqs[i] > 0.0) {
                    overlay.add(overlayFreqs[i], overlayDbFs[i] + dbFsToDbV);
                }
            }
            dataset.addSeries(overlay);
        }

        boolean hasPrePeaks = preCorrFreqs != null && preCorrDbFs != null
                && preCorrFreqs.length == preCorrDbFs.length
                && preCorrFreqs.length >= 1;
        if (hasPrePeaks) {
            XYSeries prePeaks = new XYSeries("Before cal");
            for (int i = 0; i < preCorrFreqs.length; i++) {
                if (preCorrFreqs[i] > 0.0) {
                    prePeaks.add(preCorrFreqs[i], preCorrDbFs[i] + dbFsToDbV);
                }
            }
            dataset.addSeries(prePeaks);
        }

        JFreeChart chart = ChartFactory.createXYLineChart(
                "FFT Analysis", "Frequency (Hz)", primaryAxisLabel,
                dataset,
                PlotOrientation.VERTICAL,
                true, false, false);
        chart.getTitle().setFont(ChartStyle.CHART_TITLE_FONT);

        XYPlot plot = (XYPlot) chart.getPlot();
        plot.setBackgroundPaint(ChartStyle.PLOT_BACKGROUND);
        plot.setDomainGridlinePaint(ChartStyle.GRID_LINE_COLOR);
        plot.setRangeGridlinePaint(ChartStyle.GRID_LINE_COLOR);

        final double freqMin = Math.max(1.0, r.freqResolution * 0.5);
        final double freqMax = r.sampleRate / 2.0;
        LogAxis freqAxis = new LogAxis("Frequency (Hz)") {
            @Override
            public List<NumberTick> refreshTicks(Graphics2D g2,
                                               AxisState state,
                                               Rectangle2D dataArea,
                                               RectangleEdge edge) {
                List<NumberTick> ticks = new ArrayList<>();
                double[] multipliers = {1, 2, 3, 5, 7};
                double decade = 1.0;
                while (decade <= getUpperBound() * 1.01) {
                    for (double m : multipliers) {
                        double f = decade * m;
                        if (f >= getLowerBound() && f <= getUpperBound()) {
                            String label = f >= 1000.0
                                    ? String.format(Locale.US, "%.0fk", f / 1000.0)
                                    : String.format(Locale.US, "%.0f", f);
                            ticks.add(new NumberTick(TickType.MAJOR, f, label,
                                    TextAnchor.TOP_CENTER, TextAnchor.CENTER, 0.0));
                        }
                    }
                    decade *= 10.0;
                }
                return ticks;
            }
        };
        freqAxis.setBase(10.0);
        freqAxis.setRange(freqMin, freqMax);
        freqAxis.setLabelFont(ChartStyle.AXIS_LABEL_FONT);
        freqAxis.setTickLabelFont(ChartStyle.AXIS_TICK_FONT);
        plot.setDomainAxis(freqAxis);

        if (r.snrFreqMin > 0.0 && r.snrFreqMin > freqMin) {
            IntervalMarker left = new IntervalMarker(freqMin, r.snrFreqMin);
            left.setPaint(ChartStyle.DIM_COLOR);
            left.setAlpha(ChartStyle.DIM_ALPHA);
            plot.addDomainMarker(left, Layer.FOREGROUND);
        }
        double nyquist = r.sampleRate / 2.0;
        if (r.snrFreqMax > 0.0 && r.snrFreqMax < nyquist) {
            IntervalMarker right = new IntervalMarker(r.snrFreqMax, nyquist);
            right.setPaint(ChartStyle.DIM_COLOR);
            right.setAlpha(ChartStyle.DIM_ALPHA);
            plot.addDomainMarker(right, Layer.FOREGROUND);
        }

        double yMaxDbFs = 10.0;
        double yMinDbFs = -220.0;
        NumberAxis yAxis = (NumberAxis) plot.getRangeAxis();
        yAxis.setRange(yMinDbFs + dbFsToDbV, yMaxDbFs + dbFsToDbV);
        yAxis.setTickUnit(new NumberTickUnit(20.0));
        yAxis.setLabelFont(ChartStyle.AXIS_LABEL_FONT);
        yAxis.setTickLabelFont(ChartStyle.AXIS_TICK_FONT);

        if (hasDbv) {
            NumberAxis yAxisDbFs = new NumberAxis("Amplitude (dBFS)");
            yAxisDbFs.setRange(yMinDbFs, yMaxDbFs);
            yAxisDbFs.setTickUnit(new NumberTickUnit(20.0));
            yAxisDbFs.setLabelFont(ChartStyle.AXIS_LABEL_FONT);
            yAxisDbFs.setTickLabelFont(ChartStyle.AXIS_TICK_FONT);
            plot.setRangeAxis(1, yAxisDbFs);
            plot.setRangeAxisLocation(1, AxisLocation.BOTTOM_OR_LEFT);
        }

        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(false, false);
        renderer.setSeriesLinesVisible(0, true);
        renderer.setSeriesShapesVisible(0, false);
        renderer.setSeriesPaint(0, ChartStyle.SPECTRUM_COLOR);
        renderer.setSeriesStroke(0, ChartStyle.SPECTRUM_STROKE);
        renderer.setSeriesLinesVisible(1, false);
        renderer.setSeriesShapesVisible(1, true);
        renderer.setSeriesPaint(1, ChartStyle.PEAK_COLOR);
        renderer.setSeriesShape(1, ChartStyle.PEAK_SHAPE);
        int seriesIdx = 2;
        if (hasOverlay) {
            renderer.setSeriesLinesVisible(seriesIdx, true);
            renderer.setSeriesShapesVisible(seriesIdx, false);
            renderer.setSeriesPaint(seriesIdx, ChartStyle.CAL_OVERLAY_COLOR);
            renderer.setSeriesStroke(seriesIdx, ChartStyle.CAL_OVERLAY_STROKE);
            seriesIdx++;
        }
        if (hasPrePeaks) {
            renderer.setSeriesLinesVisible(seriesIdx, false);
            renderer.setSeriesShapesVisible(seriesIdx, true);
            renderer.setSeriesPaint(seriesIdx, ChartStyle.PRE_PEAK_COLOR);
            renderer.setSeriesShape(seriesIdx, ChartStyle.PRE_PEAK_SHAPE);
        }
        plot.setRenderer(0, renderer);

        Font annFont = ChartStyle.ANNOTATION_FONT;

        XYTextAnnotation fundLabel = new XYTextAnnotation(
                String.format(Locale.US, "F %.2f Hz", r.fundamentalHz),
                r.fundamentalHz,
                r.fundamentalDbFs + dbFsToDbV + ChartStyle.ANNOTATION_Y_OFFSET);
        fundLabel.setFont(annFont);
        fundLabel.setPaint(ChartStyle.FUND_ANNOTATION_COLOR);
        plot.addAnnotation(fundLabel);

        for (int h = 0; h < r.harmonicCount; h++) {
            if (r.harmonicBins[h] > 0
                    && r.harmonicDbFs[h] > yMinDbFs + ChartStyle.ANNOTATION_Y_OFFSET) {
                XYTextAnnotation ann = new XYTextAnnotation(
                        String.format(Locale.US, "H%d", h + 2),
                        r.harmonicHz[h],
                        r.harmonicDbFs[h] + dbFsToDbV + ChartStyle.ANNOTATION_Y_OFFSET);
                ann.setFont(annFont);
                ann.setPaint(ChartStyle.HARM_ANNOTATION_COLOR);
                plot.addAnnotation(ann);
            }
        }

        BufferedImage chartImage = chart.createBufferedImage(width, height);

        double enob = (r.sinadDb - 1.76) / 6.02;

        boolean hasRef = !Double.isNaN(r.fundRefDbV);
        double fundamentalDbV = !Double.isNaN(r.fundamentalTrueDbV)
                ? r.fundamentalTrueDbV
                : (hasRef ? r.fundRefDbV : r.fundamentalDbFs);

        double ndDbVA = r.thdNDb + fundamentalDbV;

        String spanLabel = String.format(Locale.US, "Span: %.0f .. %.0f Hz",
                r.snrFreqMin, r.snrFreqMax);

        double distLo = r.snrFreqMin > 0.0 ? r.snrFreqMin : 0.0;
        double distHi = r.snrFreqMax > 0.0 ? r.snrFreqMax : Double.MAX_VALUE;
        int thdLastHarm = 1;
        for (int h = 0; h < Math.min(r.harmonicCount, 8); h++) {
            if (r.harmonicBins[h] > 0 && r.harmonicHz[h] >= distLo && r.harmonicHz[h] <= distHi) {
                thdLastHarm = h + 2;
            }
        }
        String thdLabel = thdLastHarm >= 2
                ? String.format(Locale.US, "THD H2..%d:", thdLastHarm)
                : "THD:";

        List<String[]> rows = new ArrayList<>();

        double fundHeaderDbFs = !Double.isNaN(r.fundamentalTrueDbV)
                ? fundDisplayDbV - dbFsToDbV
                : r.fundamentalDbFs;
        String fundHeader = hasRef
                ? String.format(Locale.US, "%.2f Hz  %.2f dBFS  %.2f dBV",
                        r.fundamentalHz, fundHeaderDbFs, fundamentalDbV)
                : String.format(Locale.US, "%.2f Hz  %.2f dBFS",
                        r.fundamentalHz, fundHeaderDbFs);
        rows.add(new String[]{ fundHeader, null, null, null });

        rows.add(new String[]{ spanLabel, null, null, null });

        rows.add(new String[]{
            "N+D:", String.format(Locale.US, "%.1f dBV A", ndDbVA),
            thdLabel,
            String.format(Locale.US, "%.8f %%", r.thdPct)
        });
        rows.add(new String[]{
            "N:", String.format(Locale.US, "%.1f dBV", fundamentalDbV - r.snrDb),
            "THD+N:", String.format(Locale.US, "%.8f %%",
                    Math.pow(10.0, r.thdNDb / 20.0) * 100.0)
        });
        rows.add(new String[]{
            "SNR:", String.format(Locale.US, "%.1f dB", r.snrDb),
            "ENOB:", String.format(Locale.US, "%.1f bits", enob)
        });

        if (genFreqHz != null && genFreqHz > 0.0) {
            double delta = r.fundamentalHzRefined - genFreqHz;
            double ppm   = 1e6 * delta / genFreqHz;
            double osc;
            String oscName;
            if (r.sampleRate % 44100 == 0) {
                osc = 22.5792e6; oscName = "22.5792 MHz";
            } else if (r.sampleRate % 48000 == 0) {
                osc = 24.576e6;  oscName = "24.576 MHz";
            } else {
                osc = Double.NaN; oscName = "?";
            }
            String text;
            if (Double.isNaN(osc)) {
                text = String.format(Locale.US, "ΔF: %+.6f Hz (%+.2f ppm)", delta, ppm);
            } else {
                double oscDelta = osc * delta / genFreqHz;
                text = String.format(Locale.US,
                        "ΔF: %+.6f Hz (%+.2f ppm)  Δosc: %+.2f Hz @ %s",
                        delta, ppm, oscDelta, oscName);
            }
            rows.add(new String[]{ text, null, null, null });
        }

        for (int h = 0; h < r.harmonicCount; h += 2) {
            String lLabel = String.format(Locale.US, "H%d:", h + 2);
            String lVal   = hasRef
                    ? String.format(Locale.US, "%.2f dBV  %.8f %%",
                            r.harmonicDbFs[h] + dbFsToDbV, r.harmonicPct[h])
                    : String.format(Locale.US, "%.2f dBFS  %.8f %%",
                            r.harmonicDbFs[h], r.harmonicPct[h]);
            String rLabel = null, rVal = null;
            if (h + 1 < r.harmonicCount) {
                rLabel = String.format(Locale.US, "H%d:", h + 3);
                rVal   = hasRef
                        ? String.format(Locale.US, "%.2f dBV  %.8f %%",
                                r.harmonicDbFs[h + 1] + dbFsToDbV, r.harmonicPct[h + 1])
                        : String.format(Locale.US, "%.2f dBFS  %.8f %%",
                                r.harmonicDbFs[h + 1], r.harmonicPct[h + 1]);
            }
            rows.add(new String[]{ lLabel, lVal, rLabel, rVal });
        }

        drawInfoTable(chartImage, rows, width, height);
        drawCornerInfo(chartImage, comment, harmonicsSubtracted, r.frameCount, width, height);

        File outFile = (filePrefix != null && !filePrefix.isEmpty())
                ? new File(directory, filePrefix + ".png")
                : new File(directory, "fft_chart_" + ts + ".png");
        ImageIO.write(chartImage, "PNG", outFile);
        log.info("FFT chart saved: {}", outFile.getAbsolutePath());
        return outFile.getAbsolutePath();
    }

    private static void drawInfoTable(BufferedImage img,
                                      List<String[]> rows,
                                      int imgWidth, int imgHeight) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Font headerFont = ChartStyle.TABLE_HEADER_FONT;
        Font cellFont   = ChartStyle.TABLE_CELL_FONT;

        FontMetrics hmFm = g.getFontMetrics(headerFont);
        FontMetrics cmFm = g.getFontMetrics(cellFont);
        int lineH   = cmFm.getHeight() + 2;
        int pad     = ChartStyle.TABLE_PAD;

        int spanMaxW = 0;
        int colW0 = 0, colW1 = 0, colW2 = 0, colW3 = 0;
        for (String[] row : rows) {
            if (row[1] == null) {
                spanMaxW = Math.max(spanMaxW, hmFm.stringWidth(row[0]));
            } else {
                colW0 = Math.max(colW0, cmFm.stringWidth(row[0]));
                colW1 = Math.max(colW1, cmFm.stringWidth(row[1]));
                if (row[2] != null) {
                    colW2 = Math.max(colW2, cmFm.stringWidth(row[2]));
                    colW3 = Math.max(colW3, cmFm.stringWidth(row[3]));
                }
            }
        }

        int colSep  = pad;
        int halfW   = colW0 + colSep + colW1;
        int dataW   = halfW + colSep + pad + colW2 + colSep + colW3;
        int tableW  = Math.max(dataW, spanMaxW) + pad * 2;
        int tableH  = rows.size() * lineH + pad * 2;

        int x0 = imgWidth  - tableW - ChartStyle.TABLE_MARGIN;
        int y0 = ChartStyle.TABLE_MARGIN;

        g.setColor(ChartStyle.TABLE_BG_COLOR);
        g.fillRoundRect(x0, y0, tableW, tableH, ChartStyle.TABLE_ARC, ChartStyle.TABLE_ARC);
        g.setColor(ChartStyle.TABLE_BORDER_COLOR);
        g.drawRoundRect(x0, y0, tableW, tableH, ChartStyle.TABLE_ARC, ChartStyle.TABLE_ARC);

        int textX = x0 + pad;
        int textY = y0 + pad + cmFm.getAscent();

        for (String[] row : rows) {
            if (row[1] == null) {
                g.setFont(headerFont);
                g.setColor(ChartStyle.TABLE_HEADER_COLOR);
                int sw = hmFm.stringWidth(row[0]);
                g.drawString(row[0], x0 + (tableW - sw) / 2, textY);
            } else {
                g.setFont(cellFont);
                g.setColor(ChartStyle.TABLE_LABEL_COLOR);
                g.drawString(row[0], textX + colW0 - cmFm.stringWidth(row[0]), textY);
                g.setColor(ChartStyle.TABLE_VALUE_COLOR);
                g.drawString(row[1], textX + colW0 + colSep, textY);
                if (row[2] != null) {
                    int rx = textX + halfW + pad;
                    g.setColor(ChartStyle.TABLE_LABEL_COLOR);
                    g.drawString(row[2], rx + colW2 - cmFm.stringWidth(row[2]), textY);
                    g.setColor(ChartStyle.TABLE_VALUE_COLOR);
                    g.drawString(row[3], rx + colW2 + colSep, textY);
                }
            }
            textY += lineH;
        }
        g.dispose();
    }

    private static void drawCornerInfo(BufferedImage img,
                                       String comment, boolean harmonicsSubtracted,
                                       int frameCount, int imgWidth, int imgHeight) {
        List<String> lines = new ArrayList<>();
        if (comment != null && !comment.isBlank()) {
            lines.add(comment);
        }
        if (harmonicsSubtracted) {
            lines.add("Harmonics compensated");
        }
        lines.add(String.format(Locale.US, "Avg cycles: %d", frameCount));

        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,  RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Font font = ChartStyle.TABLE_CELL_FONT;
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();

        int pad   = ChartStyle.CORNER_PAD;
        int lineH = fm.getHeight() + 2;
        int boxH  = lineH * lines.size() + pad * 2;
        int maxW  = lines.stream().mapToInt(fm::stringWidth).max().orElse(0);
        int boxW  = maxW + pad * 2;
        int boxX  = pad;
        int boxY  = imgHeight - boxH - pad;

        g.setColor(ChartStyle.CORNER_BG_COLOR);
        g.fillRoundRect(boxX, boxY, boxW, boxH, ChartStyle.CORNER_ARC, ChartStyle.CORNER_ARC);
        g.setColor(ChartStyle.CORNER_BORDER_COLOR);
        g.drawRoundRect(boxX, boxY, boxW, boxH, ChartStyle.CORNER_ARC, ChartStyle.CORNER_ARC);

        g.setColor(ChartStyle.CORNER_TEXT_COLOR);
        int textY = boxY + pad + fm.getAscent();
        for (String line : lines) {
            g.drawString(line, boxX + pad, textY);
            textY += lineH;
        }
        g.dispose();
    }
}
