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

package org.edgo.audio.measure.cli;

import org.edgo.audio.measure.cli.util.*;

import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.chart.ChartStyle;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.gui.preferences.Preferences;
import org.edgo.audio.measure.sound.DeviceRef;
import org.edgo.audio.measure.wav.WavWriter;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.AxisState;
import org.jfree.chart.axis.LogAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.NumberTick;
import org.jfree.chart.axis.NumberTickUnit;
import org.jfree.chart.axis.TickType;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.ui.RectangleEdge;
import org.jfree.chart.ui.TextAnchor;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * {@code --freq-response} — measures a filter's transfer function H(f) via
 * Farina log-sweep frequency-domain deconvolution.
 *
 * <p>Plays a single Farina exponential sweep {@code x(t)=sin(K(eᵗ/ᴸ−1))} from
 * {@code --sweep-start} to {@code --sweep-end} after a {@code --lead-in}
 * silence, captures the response, then computes {@code H = Y/X} per bin with
 * the DAC↔ADC transport delay removed (located via the impulse-response peak
 * and rotated out in the frequency domain so notches keep their physical
 * ±180° phase steps).  Output rows are linearly interpolated to
 * {@code --sweep-points} log-spaced frequencies and written to a CSV that
 * {@code --cal} consumes in {@code --fft-analyze}, {@code --gen-fft} and
 * {@code --iterative-compensate}.  A matching PNG chart (magnitude in dB and
 * unwrapped phase) is rendered alongside.
 *
 * <p>Required: {@code --samplerate}, {@code --amplitude}, {@code --out-device},
 * {@code --in-device}.
 */
@Log4j2
@UtilityClass
public class FreqRespMode {

    public void run(String[] args) throws Exception {
        String samplerateArg    = ArgParser.getArgValue(args, "--samplerate");
        String bitsArg          = ArgParser.getArgValue(args, "--bits");
        String amplitudeArg     = ArgParser.getArgValue(args, "--amplitude");
        String sweepStartArg    = ArgParser.getArgValue(args, "--sweep-start");
        String sweepEndArg      = ArgParser.getArgValue(args, "--sweep-end");
        String sweepPointsArg   = ArgParser.getArgValue(args, "--sweep-points");
        String sweepDurationArg = ArgParser.getArgValue(args, "--sweep-duration");
        String leadInArg        = ArgParser.getArgValue(args, "--lead-in");
        String ditherArg        = ArgParser.getArgValue(args, "--dither");
        String outputArg        = ArgParser.getArgValue(args, "--output");
        String wavOutArg        = ArgParser.getArgValue(args, "--sweep-wav");
        String widthArg         = ArgParser.getArgValue(args, "--width");
        String heightArg        = ArgParser.getArgValue(args, "--height");
        String adcFsArg         = ArgParser.getArgValue(args, "--adc-fs-vrms");

        if (samplerateArg == null) { log.error("--samplerate required"); System.exit(1); }
        if (amplitudeArg  == null) { log.error("--amplitude required");  System.exit(1); }
        if (adcFsArg != null) {
            // Inject for this run only — Main marked Preferences transient, so not persisted.
            Preferences.instance().setAdcFsVoltageRms(Double.parseDouble(adcFsArg));
        }

        int sampleRate = Integer.parseInt(samplerateArg);
        int bitDepth   = bitsArg != null ? Integer.parseInt(bitsArg) : 24;
        double amp     = Double.parseDouble(amplitudeArg);
        int    ditherBits = ditherArg != null ? Integer.parseInt(ditherArg) : 0;
        int    chartWidth  = widthArg  != null ? Integer.parseInt(widthArg)  : 1920;
        int    chartHeight = heightArg != null ? Integer.parseInt(heightArg) : 600;

        if (!SampleRates.isValid(sampleRate)) {
            log.error("--samplerate must be one of the supported rates"); System.exit(1);
        }
        if (bitDepth != 8 && bitDepth != 16 && bitDepth != 24 && bitDepth != 32) {
            log.error("--bits must be 8, 16, 24 or 32"); System.exit(1);
        }

        double fStart   = sweepStartArg != null ? Double.parseDouble(sweepStartArg) : 2.0;
        double fEnd     = sweepEndArg   != null ? Double.parseDouble(sweepEndArg)   : sampleRate / 2.0;
        int    nPoints  = sweepPointsArg != null ? Integer.parseInt(sweepPointsArg) : 200;
        double sweepDurationSec = sweepDurationArg != null ? Double.parseDouble(sweepDurationArg) : 5.5;
        double leadInSec = leadInArg != null ? Double.parseDouble(leadInArg) : 0.2;

        if (fStart <= 0.0 || fEnd <= fStart) {
            log.error("--sweep-start must be > 0 and < --sweep-end"); System.exit(1);
        }
        if (nPoints < 4) {
            log.error("--sweep-points must be at least 4"); System.exit(1);
        }
        if (sweepDurationSec < 0.5) {
            log.error("--sweep-duration must be at least 0.5 s"); System.exit(1);
        }
        if (leadInSec < 0.05) {
            log.error("--lead-in must be at least 0.05 s"); System.exit(1);
        }

        double[] freqs = new double[nPoints];
        double logStart = Math.log(fStart);
        double logEnd   = Math.log(fEnd);
        for (int i = 0; i < nPoints; i++) {
            double t = i / (double) (nPoints - 1);
            freqs[i] = Math.exp(logStart + (logEnd - logStart) * t);
        }

        int  sweepSamples    = (int) Math.round(sweepDurationSec * sampleRate);
        int  leadInSamples   = (int) Math.round(leadInSec * sampleRate);
        int  tailSamples     = sampleRate / 2;
        long totalGenSamples = (long) leadInSamples + sweepSamples + tailSamples;
        int  durationSec     = (int) Math.ceil(totalGenSamples / (double) sampleRate);

        DeviceSelector.logProviders();
        DeviceRef outDevice = DeviceSelector.selectMixerByFlag(args, "--out-device", true);
        DeviceRef inDevice  = DeviceSelector.selectMixerByFlag(args, "--in-device",  false);
        if (outDevice == null) { log.error("No output device. Use --out-device <index>."); System.exit(1); }
        if (inDevice  == null) { log.error("No input device. Use --in-device <index>.");  System.exit(1); }

        log.info("=== Frequency Response Measurement: Farina log-sweep deconvolution ===");
        log.info("Out device  : {}", outDevice.name());
        log.info("In device   : {}", inDevice.name());
        log.info("Sample rate : {} Hz",   sampleRate);
        log.info("Bits        : {}",      bitDepth);
        log.info("Sweep range : {} → {} Hz",
                String.format(Locale.US, "%.3f", fStart),
                String.format(Locale.US, "%.3f", fEnd));
        log.info("Sweep length: {} s ({} samples)",
                String.format(Locale.US, "%.3f", sweepDurationSec), sweepSamples);
        log.info("Output rows : {} log-spaced points", nPoints);
        log.info("Lead-in     : {} s ({} samples)",
                String.format(Locale.US, "%.2f", leadInSec), leadInSamples);
        log.info("Duration    : {} s total", durationSec);
        log.info("Amplitude   : {} V RMS", amp);
        log.info("Chart       : {}x{} px", chartWidth, chartHeight);

        SignalGenerator gen = new SignalGenerator(fStart, fEnd, sweepSamples, leadInSamples, sampleRate, amp);
        // One-shot sweep for the measurement: emit silence after the buffer
        // ends instead of looping a second cycle into the capture window,
        // which would alias into the deconvolution.  Hann fade-in/fade-out
        // suppresses sweep-boundary spectral leakage (same value also
        // applied to the X reference inside computeFromLogSweep).
        int fadeSamples = FreqRespCalHelper.sweepFadeSamples(sweepSamples);
        gen.setSweepParams(false, fadeSamples, fadeSamples);
        // Stereo capture: one playback, both ADC channels retained — the
        // CLI mirrors the GUI's behaviour so a saved measurement carries
        // L and R deconvolved from the same sweep.
        StereoSamples rec = CaptureWithGenerator.runStereo(
                gen, outDevice, inDevice, sampleRate, bitDepth, ditherBits,
                durationSec, null, 0, null, null);
        log.info("Captured    : {} samples per channel ({} s)", rec.left().length,
                String.format(Locale.US, "%.4f", rec.left().length / (double) sampleRate));

        if (wavOutArg != null) {
            byte[] pcm = PcmUtils.floatStereoToBytes(rec.left(), rec.right(), bitDepth);
            try (WavWriter w = new WavWriter(new File(wavOutArg), sampleRate, 2, bitDepth, false)) {
                w.writeRaw(pcm, pcm.length);
            }
            log.info("Sweep WAV   : {}", wavOutArg);
        }

        // Parallel deconvolution: L and R share the same reference sweep
        // but compute independently, so they run on separate futures off
        // the common ForkJoin pool.
        float[] sweepRef = gen.getLogSweepBuffer();
        CompletableFuture<FreqRespCalibration> calLFut = CompletableFuture.supplyAsync(
                () -> FreqRespCalHelper.computeFromLogSweep(
                        rec.left(), sweepRef, leadInSamples, sampleRate, freqs, amp,
                        fadeSamples, "L"));
        CompletableFuture<FreqRespCalibration> calRFut = CompletableFuture.supplyAsync(
                () -> FreqRespCalHelper.computeFromLogSweep(
                        rec.right(), sweepRef, leadInSamples, sampleRate, freqs, amp,
                        fadeSamples, "R"));
        FreqRespCalibration calL = calLFut.join();
        FreqRespCalibration calR = calRFut.join();
        StereoFreqRespCalibration stereo = new StereoFreqRespCalibration(calL, calR);

        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String csvPath = outputArg != null
                ? outputArg
                : new File("results", "filter_cal_" + ts + ".csv").getPath();
        FreqRespCalHelper.saveCsv(stereo, csvPath, sampleRate, fStart, fEnd, nPoints, amp);
        log.info("Filter cal CSV saved: {}", csvPath);

        String chartPath = csvPath.replaceFirst("\\.csv$", "") + ".png";
        exportChart(stereo, chartWidth, chartHeight, chartPath);
        log.info("Filter cal chart saved: {}", chartPath);
    }

    /**
     * Renders a stereo frequency-response chart:
     *   Series 0 / 1 = magnitude L / R in dB (left Y axis)
     *   Series 0 / 1 = phase L / R in degrees (right Y axis), unwrapped
     */
    private void exportChart(StereoFreqRespCalibration stereo,
            int width, int height, String pngPath) throws IOException {
        FreqRespCalibration calL = stereo.left();
        FreqRespCalibration calR = stereo.right();
        XYSeries magL   = new XYSeries("Magnitude L (dB)");
        XYSeries magR   = new XYSeries("Magnitude R (dB)");
        XYSeries phaseL = new XYSeries("Phase L (deg)");
        XYSeries phaseR = new XYSeries("Phase R (deg)");
        for (int i = 0; i < calL.freqs.length; i++) {
            double freq = calL.freqs[i];
            if (freq <= 0.0) continue;
            double mL = calL.magLin[i];
            double mR = calR.magLin[i];
            magL.add(freq,   mL > 0.0 ? 20.0 * Math.log10(mL) : -300.0);
            magR.add(freq,   mR > 0.0 ? 20.0 * Math.log10(mR) : -300.0);
            phaseL.add(freq, Math.toDegrees(calL.phaseRad[i]));
            phaseR.add(freq, Math.toDegrees(calR.phaseRad[i]));
        }

        XYSeriesCollection magData = new XYSeriesCollection();
        magData.addSeries(magL);
        magData.addSeries(magR);
        XYSeriesCollection phaseData = new XYSeriesCollection();
        phaseData.addSeries(phaseL);
        phaseData.addSeries(phaseR);

        JFreeChart chart = ChartFactory.createXYLineChart(
                "Filter Frequency Response (L + R)", "Frequency (Hz)", "Magnitude (dB)",
                magData, PlotOrientation.VERTICAL,
                true, false, false);
        chart.getTitle().setFont(ChartStyle.CHART_TITLE_FONT);

        XYPlot plot = (XYPlot) chart.getPlot();
        plot.setBackgroundPaint(ChartStyle.PLOT_BACKGROUND);
        plot.setDomainGridlinePaint(ChartStyle.GRID_LINE_COLOR);
        plot.setRangeGridlinePaint(ChartStyle.GRID_LINE_COLOR);

        final double freqMin = calL.freqs[0];
        final double freqMax = calL.freqs[calL.freqs.length - 1];
        LogAxis freqAxis = new LogAxis("Frequency (Hz)") {
            @Override
            public List<NumberTick> refreshTicks(
                    Graphics2D g2,
                    AxisState state,
                    Rectangle2D dataArea,
                    RectangleEdge edge) {
                List<NumberTick> ticks = new ArrayList<>();
                double[] mults = {1, 2, 3, 5, 7};
                double decade = 1.0;
                while (decade <= getUpperBound() * 1.01) {
                    for (double m : mults) {
                        double f = decade * m;
                        if (f >= getLowerBound() && f <= getUpperBound()) {
                            String label = f >= 1000.0
                                    ? String.format(Locale.US, "%.0fk", f / 1000.0)
                                    : String.format(Locale.US, "%.0f", f);
                            ticks.add(new NumberTick(
                                    TickType.MAJOR, f, label,
                                    TextAnchor.TOP_CENTER,
                                    TextAnchor.CENTER, 0.0));
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

        double minDb =  Double.POSITIVE_INFINITY;
        double maxDb = -Double.POSITIVE_INFINITY;
        for (int i = 0; i < calL.freqs.length; i++) {
            for (double mag : new double[]{ calL.magLin[i], calR.magLin[i] }) {
                if (mag <= 0.0) continue;
                double db = 20.0 * Math.log10(mag);
                if (db < minDb) minDb = db;
                if (db > maxDb) maxDb = db;
            }
        }
        if (!Double.isFinite(minDb)) { minDb = -100.0; maxDb = 10.0; }
        double yMin = Math.floor((minDb - 5.0) / 10.0) * 10.0;
        double yMax = Math.ceil ((maxDb + 5.0) / 10.0) * 10.0;
        if (yMax - yMin < 20.0) yMax = yMin + 20.0;
        NumberAxis yAxis = (NumberAxis) plot.getRangeAxis();
        yAxis.setRange(yMin, yMax);
        yAxis.setTickUnit(new NumberTickUnit(10.0));
        yAxis.setLabelFont(ChartStyle.AXIS_LABEL_FONT);
        yAxis.setTickLabelFont(ChartStyle.AXIS_TICK_FONT);

        double minPhase =  Double.POSITIVE_INFINITY;
        double maxPhase = -Double.POSITIVE_INFINITY;
        for (int i = 0; i < calL.freqs.length; i++) {
            for (double pd : new double[]{ Math.toDegrees(calL.phaseRad[i]),
                                           Math.toDegrees(calR.phaseRad[i]) }) {
                if (pd < minPhase) minPhase = pd;
                if (pd > maxPhase) maxPhase = pd;
            }
        }
        if (!Double.isFinite(minPhase)) { minPhase = -180.0; maxPhase = 180.0; }
        double pSpan = Math.max(90.0, maxPhase - minPhase);
        double pad   = pSpan * 0.05;
        NumberAxis phaseAxis = new NumberAxis("Phase (deg)");
        phaseAxis.setRange(minPhase - pad, maxPhase + pad);
        phaseAxis.setLabelFont(ChartStyle.AXIS_LABEL_FONT);
        phaseAxis.setTickLabelFont(ChartStyle.AXIS_TICK_FONT);
        plot.setRangeAxis(1, phaseAxis);
        plot.setDataset(1, phaseData);
        plot.mapDatasetToRangeAxis(1, 1);

        XYLineAndShapeRenderer magRend =
                new XYLineAndShapeRenderer(true, false);
        // Match the GUI's channel colour convention: L red, R blue.
        magRend.setSeriesPaint(0, new Color(0xCC, 0x33, 0x33));
        magRend.setSeriesStroke(0, ChartStyle.SPECTRUM_STROKE);
        magRend.setSeriesPaint(1, new Color(0x33, 0x66, 0xCC));
        magRend.setSeriesStroke(1, ChartStyle.SPECTRUM_STROKE);
        plot.setRenderer(0, magRend);

        XYLineAndShapeRenderer phRend =
                new XYLineAndShapeRenderer(true, false);
        BasicStroke dashed = new BasicStroke(0.6f,
                BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                10.0f, new float[]{4f, 3f}, 0f);
        phRend.setSeriesPaint(0, new Color(0xCC, 0x33, 0x33));
        phRend.setSeriesStroke(0, dashed);
        phRend.setSeriesPaint(1, new Color(0x33, 0x66, 0xCC));
        phRend.setSeriesStroke(1, dashed);
        plot.setRenderer(1, phRend);

        File out = new File(pngPath);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        ChartUtils.saveChartAsPNG(out, chart, width, height);
    }
}
