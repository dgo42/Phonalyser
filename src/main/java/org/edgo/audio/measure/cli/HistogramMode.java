package org.edgo.audio.measure.cli;

import org.edgo.audio.measure.cli.util.*;

import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.adc.AdcHistogram;
import org.edgo.audio.measure.adc.WeightedBuffer;
import org.edgo.audio.measure.chart.ChartExporter;
import org.edgo.audio.measure.chart.HistogramExporter;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.AudioCapture;
import org.edgo.audio.measure.sound.DeviceRef;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@code --histogram} — ADC code-occupancy histogram capture or offline replay.
 *
 * <p>Record mode (no {@code --load}): records {@code --duration} seconds from
 * the selected input device, accumulating a per-code occupancy count.  Replay
 * mode ({@code --load <csv>}): re-processes a previously dumped histogram CSV.
 * Either way the histogram is then weighted (sliding moving-average reference
 * or {@code --sine-reference} arcsine-PDF reference for sine-stimulus
 * captures), and DNL/INL/waveform charts plus the weighted-code-map CSV are
 * written to {@code results/}.
 *
 * <p>The weighted-code-map output feeds {@code --load-weighted} downstream
 * (in {@code --process-wav}, {@code --record-mapped-wav},
 * {@code --fft-analyze}, {@code --gen-fft}) to linearise raw ADC codes
 * through the measured INL curve.
 *
 * <p>Required: {@code --window}, {@code --width}, {@code --height}, plus
 * {@code --duration}/{@code --samplerate} in record mode.
 */
@Log4j2
@UtilityClass
public class HistogramMode {

    public void run(String[] args) throws Exception {
        String loadArg       = ArgParser.getArgValue(args, "--load");
        String durationArg   = ArgParser.getArgValue(args, "--duration");
        String samplerateArg = ArgParser.getArgValue(args, "--samplerate");
        String windowArg     = ArgParser.getArgValue(args, "--window");
        String scaleArg      = ArgParser.getArgValue(args, "--scale");
        String widthArg      = ArgParser.getArgValue(args, "--width");
        String heightArg     = ArgParser.getArgValue(args, "--height");
        String bitsArg       = ArgParser.getArgValue(args, "--bits");
        String adcFsArg      = ArgParser.getArgValue(args, "--adc-fs-vrms");
        if (adcFsArg != null) {
            AudioBackend.setAdcFsVoltageRms(Double.parseDouble(adcFsArg));
        }

        boolean loadMode   = (loadArg != null);
        boolean recordMode = !loadMode;

        if (recordMode && durationArg == null) {
            log.error("Usage (record) : --histogram --duration <s> --samplerate <hz> --window <n> --width <px> --height <px> [--bits 8|16|24] [--scale <V>] [--adc-fs-vrms <vrms>] [--device <i>]");
            log.error("Usage (replay) : --histogram --load <file> --bits 8|16|24 --window <n> --width <px> --height <px> [--scale <V>] [--adc-fs-vrms <vrms>]");
            System.exit(1);
        }
        if (recordMode && samplerateArg == null) {
            log.error("--samplerate is required for recording mode.");
            System.exit(1);
        }
        if (windowArg == null || widthArg == null || heightArg == null) {
            log.error("--window, --width and --height are required.");
            System.exit(1);
        }

        int durationSeconds = recordMode ? Integer.parseInt(durationArg) : 0;
        if (recordMode && durationSeconds <= 0) {
            log.error("--duration must be a positive integer.");
            System.exit(1);
        }
        int sampleRate = recordMode ? Integer.parseInt(samplerateArg) : 0;
        if (recordMode && !SampleRates.isValid(sampleRate)) {
            log.error("--samplerate must be one of: 44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000");
            System.exit(1);
        }
        int windowLength = Integer.parseInt(windowArg);
        if (windowLength < 0) {
            log.error("--window must be a positive integer.");
            System.exit(1);
        }
        double scaleVolts = scaleArg != null
                ? Double.parseDouble(scaleArg)
                : 2.0 * Math.sqrt(2.0) * AudioBackend.getAdcFsVoltageRms();
        if (scaleVolts <= 0) {
            log.error("--scale must be a positive number.");
            System.exit(1);
        }
        int chartWidth = Integer.parseInt(widthArg);
        if (chartWidth <= 0) {
            log.error("--width must be a positive integer.");
            System.exit(1);
        }
        int chartHeight = Integer.parseInt(heightArg);
        if (chartHeight <= 0) {
            log.error("--height must be a positive integer.");
            System.exit(1);
        }
        int bitDepth = (bitsArg != null) ? Integer.parseInt(bitsArg) : 24;
        if (bitDepth != 8 && bitDepth != 16 && bitDepth != 24 && bitDepth != 32) {
            log.error("--bits must be 8, 16, 24 or 32.");
            System.exit(1);
        }

        boolean sineRef = ArgParser.hasArg(args, "--sine-reference");
        String  sineAmpArg = ArgParser.getArgValue(args, "--sine-amplitude");
        double  sineAmpFsRatio = sineAmpArg != null ? Double.parseDouble(sineAmpArg) : Double.NaN;
        String  sineEdgeArg = ArgParser.getArgValue(args, "--sine-edge-bins");
        int     sineEdgeBins = sineEdgeArg != null ? Integer.parseInt(sineEdgeArg) : 2;
        String  sineFitArg = ArgParser.getArgValue(args, "--sine-fit-points");
        int     sineFitPoints = sineFitArg != null ? Integer.parseInt(sineFitArg) : 30;
        boolean rawHistChart = ArgParser.hasArg(args, "--histogram-chart");

        if (loadMode) {
            log.info("Mode    : load histogram from CSV");
            log.info("File    : {}", loadArg);
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
            histogram.loadCsv(loadArg);
            processHistogram(histogram, windowLength, scaleVolts, chartWidth, chartHeight,
                    sineRef, sineAmpFsRatio, sineEdgeBins, sineFitPoints, rawHistChart);
            return;
        }

        DeviceRef selectedMixer = DeviceSelector.selectMixer(args);
        if (selectedMixer == null) {
            log.error("No suitable audio input device found.");
            System.exit(1);
        }

        log.info("Mode      : record");
        log.info("Device    : {}", selectedMixer.name());
        log.info("SampleRate: {} Hz", sampleRate);
        log.info("Bits      : {}", bitDepth);
        log.info("Duration  : {} second(s)", durationSeconds);
        log.info("Window    : {} codes", windowLength);
        log.info("Scale     : {} V", scaleVolts);
        log.info("Chart     : {}x{} px", chartWidth, chartHeight);

        runSession(selectedMixer, sampleRate, durationSeconds, windowLength, scaleVolts,
                chartWidth, chartHeight, bitDepth,
                sineRef, sineAmpFsRatio, sineAmpArg, sineEdgeBins, sineFitPoints, rawHistChart);
    }

    private void runSession(DeviceRef device, int sampleRate, int durationSeconds, int windowLength,
                            double scaleVolts, int chartWidth, int chartHeight, int bitDepth,
                            boolean sineRef, double sineAmpFsRatio, String sineAmpArg,
                            int sineEdgeBins, int sineFitPoints, boolean rawHistChart) throws Exception {
        AdcHistogram histogram = new AdcHistogram(bitDepth);

        try (AudioCapture recorder = AudioBackend.instance().openCapture(device, sampleRate, bitDepth)) {
            final AdcHistogram histogramRef = histogram;
            final AtomicLong   nextLogAt    = new AtomicLong(System.currentTimeMillis());
            final int          skipFrames   = sampleRate / 100;
            final AtomicInteger skipped     = new AtomicInteger(0);
            recorder.setSampleListener(samples -> {
                if (skipped.get() < skipFrames) {
                    skipped.addAndGet(samples.length);
                    return;
                }
                histogramRef.record(samples);
                long now = System.currentTimeMillis();
                if (now >= nextLogAt.get()) {
                    nextLogAt.set(now + 5_000L);
                    log.info("Samples: {}   Unique codes: {}   Min count: {}   Max count: {}",
                            String.format("%,d", histogramRef.getTotalCount()),
                            String.format("%,d", histogramRef.getUniqueCodes()),
                            String.format("%,d", histogramRef.getMinCount()),
                            String.format("%,d", histogramRef.getMaxCount()));
                }
            });

            recorder.open();
            recorder.startRecording();
            Thread.sleep(durationSeconds * 1000L);
            recorder.stopRecording();
        }

        histogram.exportCsv("results");
        if (sineRef) {
            log.info("Weight    : sine-PDF reference{}, edge mask {} bin(s)/side",
                    sineAmpArg != null ? " (A=" + sineAmpArg + " FS)" : " (auto-estimate A)",
                    sineEdgeBins);
        }
        processHistogram(histogram, windowLength, scaleVolts, chartWidth, chartHeight,
                sineRef, sineAmpFsRatio, sineEdgeBins, sineFitPoints, rawHistChart);
    }

    private void processHistogram(AdcHistogram histogram, int windowLength, double scaleVolts,
                                  int chartWidth, int chartHeight,
                                  boolean sineReference, double sineAmpFsRatio,
                                  int sineEdgeBins, int sineFitPoints,
                                  boolean rawHistogramChart) throws Exception {
        if (rawHistogramChart) {
            new HistogramExporter().export(histogram, scaleVolts, chartWidth, chartHeight, "results");
        }

        WeightedBuffer weighted = new WeightedBuffer(histogram.getBitDepth());
        if (sineReference) {
            weighted.computeSineReference(histogram, sineAmpFsRatio, sineEdgeBins, sineFitPoints);
        } else {
            weighted.compute(histogram, windowLength);
        }
        histogram = null;
        System.gc();
        log.info("Histogram released.");

        weighted.exportCsv("results", "weighted_raw");

        weighted.applyVoltageScale(scaleVolts);
        new ChartExporter().export(weighted, scaleVolts, chartWidth, chartHeight, "results");
        weighted.exportCsv("results", "weighted_scaled");
    }
}
