package org.edgo.audio.measure.cli;

import org.edgo.audio.measure.cli.util.*;

import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.adc.AdcHistogram;
import org.edgo.audio.measure.adc.WeightedBuffer;
import org.edgo.audio.measure.chart.DnlInlExporter;
import org.edgo.audio.measure.chart.HistogramExporter;

/**
 * {@code --analyze-histogram <file>} — offline DNL/INL analysis of a previously
 * captured ADC code histogram CSV.
 *
 * <p>Loads the histogram, optionally renders the raw counts-vs-voltage chart,
 * then computes per-code DNL/INL either against a sliding moving average
 * (default) or against a theoretical sine-PDF reference (use {@code
 * --sine-reference} when the capture stimulus was a known sine).  Outputs the
 * DNL/INL chart plus a weighted-code-map CSV — the same CSV that {@code
 * --load-weighted}, {@code --process-wav} and {@code --record-mapped-wav}
 * consume to linearise raw ADC codes through the measured INL curve.
 *
 * <p>Required: {@code --bits}, {@code --window}, {@code --scale}, {@code
 * --width}, {@code --height}.
 */
@Log4j2
@UtilityClass
public class AnalyzeHistogramMode {

    public void run(String[] args) throws Exception {
        String fileArg   = ArgParser.getArgValue(args, "--analyze-histogram");
        String bitsArg   = ArgParser.getArgValue(args, "--bits");
        String windowArg = ArgParser.getArgValue(args, "--window");
        String scaleArg  = ArgParser.getArgValue(args, "--scale");
        String widthArg  = ArgParser.getArgValue(args, "--width");
        String heightArg = ArgParser.getArgValue(args, "--height");
        boolean sineRef  = ArgParser.hasArg(args, "--sine-reference");
        String  sineAmpArg = ArgParser.getArgValue(args, "--sine-amplitude");
        double  sineAmpFsRatio = sineAmpArg != null ? Double.parseDouble(sineAmpArg) : Double.NaN;
        String  sineEdgeArg = ArgParser.getArgValue(args, "--sine-edge-bins");
        int     sineEdgeBins = sineEdgeArg != null ? Integer.parseInt(sineEdgeArg) : 2;
        String  sineFitArg = ArgParser.getArgValue(args, "--sine-fit-points");
        int     sineFitPoints = sineFitArg != null ? Integer.parseInt(sineFitArg) : 30;
        boolean rawHistChart = ArgParser.hasArg(args, "--histogram-chart");

        if (bitsArg == null) {
            log.error("--bits is required for --analyze-histogram.");
            System.exit(1);
        }
        if (windowArg == null) {
            log.error("--window is required for --analyze-histogram.");
            System.exit(1);
        }
        if (scaleArg == null) {
            log.error("--scale is required for --analyze-histogram.");
            System.exit(1);
        }
        if (widthArg == null) {
            log.error("--width is required for --analyze-histogram.");
            System.exit(1);
        }
        if (heightArg == null) {
            log.error("--height is required for --analyze-histogram.");
            System.exit(1);
        }

        int    bitDepth    = Integer.parseInt(bitsArg);
        int    windowLength = Integer.parseInt(windowArg);
        double scaleVolts  = Double.parseDouble(scaleArg);
        int    chartWidth  = Integer.parseInt(widthArg);
        int    chartHeight = Integer.parseInt(heightArg);

        if (bitDepth != 8 && bitDepth != 16 && bitDepth != 24 && bitDepth != 32) {
            log.error("--bits must be 8, 16, 24 or 32.");
            System.exit(1);
        }

        log.info("Mode    : analyze histogram (DNL + INL)");
        log.info("File    : {}", fileArg);
        log.info("Bits    : {}", bitDepth);
        log.info("Window  : {} codes", windowLength);
        log.info("Scale   : {} V", scaleVolts);
        log.info("Chart   : {}x{} px", chartWidth, chartHeight);
        if (sineRef) {
            log.info("Weight  : sine-PDF reference{}, edge mask {} bin(s)/side",
                    sineAmpArg != null ? " (A=" + sineAmpArg + " FS)" : " (auto-estimate A)",
                    sineEdgeBins);
        }

        AdcHistogram histogram = new AdcHistogram(bitDepth);
        histogram.loadCsv(fileArg);
        if (rawHistChart) {
            new HistogramExporter().export(histogram, scaleVolts, chartWidth, chartHeight, "results");
        }
        WeightedBuffer weighted = new WeightedBuffer(bitDepth);
        if (sineRef) {
            weighted.computeSineReference(histogram, sineAmpFsRatio, sineEdgeBins, sineFitPoints);
        } else {
            weighted.compute(histogram, windowLength);
        }
        histogram = null;
        System.gc();
        log.info("Histogram released.");
        new DnlInlExporter().export(weighted, scaleVolts, chartWidth, chartHeight, "results");
    }
}
