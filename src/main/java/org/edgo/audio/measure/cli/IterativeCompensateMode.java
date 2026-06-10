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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import org.edgo.audio.measure.chart.FftChartExporter;
import org.edgo.audio.measure.cli.util.ArgParser;
import org.edgo.audio.measure.cli.util.CaptureWithGenerator;
import org.edgo.audio.measure.cli.util.ClockMismatch;
import org.edgo.audio.measure.cli.util.DeviceSelector;
import org.edgo.audio.measure.dsp.FreqRespCalHelper;
import org.edgo.audio.measure.dsp.FreqRespCalibration;
import org.edgo.audio.measure.cli.util.SampleRates;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.fft.HarmonicsCsv;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.DeviceRef;

import lombok.Setter;
import lombok.Value;
import lombok.extern.log4j.Log4j2;

/**
 * {@code --iterative-compensate} — closed-loop DAC pre-distortion to drive
 * THD down toward the noise floor.
 *
 * <p>Iteration 0 captures an uncompensated sine; each subsequent iteration
 * rebuilds the generator with harmonic corrections derived from the previous
 * iteration's measured residuals.  Two modes:
 * <ul>
 *   <li><b>Default:</b> each iteration uses only the latest result as
 *       compensation seed.
 *   <li><b>{@code --accumulate}:</b> tracks an LMS-style accumulator (μ
 *       configurable via {@code --compensation-step}, default 1.0) so
 *       compensation builds up across iterations.
 * </ul>
 *
 * <p>Harmonics within {@code --compensation-snr-margin} dB of the noise floor
 * (default 10 dB) are skipped — they would random-walk the accumulator and
 * eventually diverge.  Stopping conditions: {@code --target-thd} reached, THD
 * grew for {@code --stop-after} consecutive iterations (default 4), or user
 * pressed Enter (manual stop offers a per-iteration pick menu).
 *
 * <p>Best iteration's residual harmonics are written to {@code fft_harmonics_*.csv}
 * (input for {@code --signal sine_compensated} and {@code --deembed}); in
 * accumulate mode the actively-applied compensation is also written to
 * {@code applied_compensation_*.csv}.  Each iteration produces its own
 * spectrum chart.
 *
 * <p>Required: {@code --samplerate}, {@code --amplitude}, {@code --fft-size},
 * {@code --width}, {@code --height}, {@code --out-device}, {@code --in-device}.
 */
@Log4j2
public class IterativeCompensateMode {

    /** The CLI's single Preferences instance (transient mode) — injected by Main. */
    @Setter
    private Preferences prefs;

    /**
     * Per-iteration snapshot used by iterative-compensate so the user can
     * pick which iteration's compensation to save when the loop is stopped
     * manually.
     */
    @Value
    static class IterSnapshot {
        int iter;
        FftResult result;
        double[] appliedRe;
        double[] appliedIm;
        double[] hFreqs;
    }

    public void run(String[] args) throws Exception {
        String samplerateArg = ArgParser.getArgValue(args, "--samplerate");
        String bitsArg       = ArgParser.getArgValue(args, "--bits");
        String freqArg       = ArgParser.getArgValue(args, "--freq");
        String amplitudeArg  = ArgParser.getArgValue(args, "--amplitude");
        String ditherArg     = ArgParser.getArgValue(args, "--dither");
        String durationArg   = ArgParser.getArgValue(args, "--duration");
        String fftSizeArg    = ArgParser.getArgValue(args, "--fft-size");
        String harmonicsArg  = ArgParser.getArgValue(args, "--harmonics");
        String windowArg     = ArgParser.getArgValue(args, "--window-fn");
        String overlapArg    = ArgParser.getArgValue(args, "--overlap");
        String distMinArg    = ArgParser.getArgValue(args, "--dist-min");
        String distMaxArg    = ArgParser.getArgValue(args, "--dist-max");
        String fundVArg      = ArgParser.getArgValue(args, "--fund-v");
        String fundDbVArg    = ArgParser.getArgValue(args, "--fund-dbv");
        boolean coherent     = !ArgParser.hasArg(args, "--no-coherent");
        boolean accumulate   = ArgParser.hasArg(args, "--accumulate");
        String stopAfterArg  = ArgParser.getArgValue(args, "--stop-after");
        String targetThdArg  = ArgParser.getArgValue(args, "--target-thd");
        String widthArg      = ArgParser.getArgValue(args, "--width");
        String heightArg     = ArgParser.getArgValue(args, "--height");
        String freqRespCalArg  = ArgParser.getArgValue(args, "--cal");
        boolean calNoise     = ArgParser.hasArg(args, "--cal-noise");
        String snrMarginArg  = ArgParser.getArgValue(args, "--compensation-snr-margin");
        String compStepArg   = ArgParser.getArgValue(args, "--compensation-step");
        double compSnrMargin = snrMarginArg != null ? Double.parseDouble(snrMarginArg) : 10.0;
        double compStep      = compStepArg  != null ? Double.parseDouble(compStepArg)  : 1.0;
        String adcFsArg      = ArgParser.getArgValue(args, "--adc-fs-vrms");
        if (adcFsArg != null) {
            prefs.setAdcFsVoltageRms(Double.parseDouble(adcFsArg));
        }

        if (samplerateArg == null) { log.error("--samplerate required"); System.exit(1); }
        if (fftSizeArg    == null) { log.error("--fft-size required");   System.exit(1); }
        if (widthArg      == null) { log.error("--width required");      System.exit(1); }
        if (heightArg     == null) { log.error("--height required");     System.exit(1); }
        if (amplitudeArg  == null) { log.error("--amplitude required");  System.exit(1); }

        int    sampleRate  = Integer.parseInt(samplerateArg);
        int    bitDepth    = bitsArg      != null ? Integer.parseInt(bitsArg)        : 32;
        double frequency   = freqArg      != null ? Double.parseDouble(freqArg)      : 1000.0;
        double amplitude   = Double.parseDouble(amplitudeArg);
        int    ditherBits  = ditherArg    != null ? Integer.parseInt(ditherArg)      : 0;
        int    duration    = durationArg  != null ? Integer.parseInt(durationArg)    : 60;
        int    fftSize     = Integer.parseInt(fftSizeArg);
        int    harmonics   = harmonicsArg != null ? Integer.parseInt(harmonicsArg)   : 10;
        int    stopAfter  = stopAfterArg != null ? Integer.parseInt(stopAfterArg)  : 4;
        double targetThd  = targetThdArg != null ? Double.parseDouble(targetThdArg) : 0.0;
        int    chartWidth  = Integer.parseInt(widthArg);
        int    chartHeight = Integer.parseInt(heightArg);

        WindowType windowType = windowArg  != null
                ? WindowType.valueOf(windowArg)  : WindowType.HANN;
        FftOverlap overlap = overlapArg != null
                ? FftOverlap.fromString(overlapArg)    : FftOverlap.PCT_0;

        double snrFreqMin = distMinArg != null ? Double.parseDouble(distMinArg) : 0.0;
        double snrFreqMax = distMaxArg != null ? Double.parseDouble(distMaxArg) : sampleRate / 2.0;

        double fundRefDbV;
        if (fundVArg != null) {
            fundRefDbV = 20.0 * Math.log10(Double.parseDouble(fundVArg));
        } else if (fundDbVArg != null) {
            fundRefDbV = Double.parseDouble(fundDbVArg);
        } else {
            fundRefDbV = Double.NaN;
        }
        // The analyzer speaks dBFS only — convert the user-supplied dBV anchor at the boundary.
        double fundRefDbFs = fundRefDbV - prefs.getDbvOffsetDb();

        if (!SampleRates.isValid(sampleRate)) {
            log.error("--samplerate must be one of the supported rates");
            System.exit(1);
        }
        if (Integer.bitCount(fftSize) != 1) {
            log.error("--fft-size must be a power of 2");
            System.exit(1);
        }
        if (bitDepth != 8 && bitDepth != 16 && bitDepth != 24 && bitDepth != 32) {
            log.error("--bits must be 8, 16, 24 or 32");
            System.exit(1);
        }

        double freqRequested = frequency;
        long   alignedBin    = Math.round(frequency * fftSize / (double) sampleRate);
        if (alignedBin < 1) alignedBin = 1;
        frequency = alignedBin * sampleRate / (double) fftSize;
        log.info("Frequency snap: requested {} Hz → bin {} → aligned {} Hz (Δ={} Hz, bin width={} Hz)",
                String.format(Locale.US, "%.6f", freqRequested),
                alignedBin,
                String.format(Locale.US, "%.10f", frequency),
                String.format(Locale.US, "%+.6f", frequency - freqRequested),
                String.format(Locale.US, "%.6f", sampleRate / (double) fftSize));

        DeviceSelector.logProviders();
        DeviceRef outDevice = DeviceSelector.selectMixerByFlag(args, "--out-device", true);
        DeviceRef inDevice  = DeviceSelector.selectMixerByFlag(args, "--in-device",  false);
        if (outDevice == null) { log.error("No output device. Use --out-device <index>."); System.exit(1); }
        if (inDevice  == null) { log.error("No input device. Use --in-device <index>.");  System.exit(1); }

        log.info("=== Iterative Harmonic Compensation Workflow ===");
        log.info("Out device : {}", outDevice.name());
        log.info("In device  : {}", inDevice.name());
        log.info("Sample rate: {} Hz",    sampleRate);
        log.info("Bits       : {}",        bitDepth);
        log.info("Frequency  : {} Hz",    frequency);
        log.info("Amplitude  : {} V RMS", amplitude);
        log.info("Duration   : {} s/iter", duration);
        log.info("FFT size   : {}",        fftSize);
        log.info("Harmonics  : {}",        harmonics);
        log.info("Window     : {}",        windowType);
        log.info("Overlap    : {}",        overlap.label);
        log.info("Dist range : {}-{} Hz",  snrFreqMin, snrFreqMax);
        log.info("Coherent   : {}",        coherent);
        log.info("Accumulate : {}",        accumulate);
        log.info("Stop after : {} consecutive THD increases", stopAfter);
        if (targetThd > 0) log.info("Target THD : {} %", String.format(Locale.US, "%.8f", targetThd));
        log.info("Comp. gate : harmonics quieter than noise floor + {} dB are skipped",
                String.format(Locale.US, "%.1f", compSnrMargin));
        log.info("Comp. step : {} (LMS μ; 1.0 = full residual per iteration, <1 damps oscillations)",
                String.format(Locale.US, "%.3f", compStep));
        if (freqRespCalArg != null) log.info("Frequency response cal : {}", freqRespCalArg);

        FreqRespCalibration freqRespCal = freqRespCalArg != null
                ? FreqRespCalHelper.loadCsv(freqRespCalArg).left()
                : null;

        String wfTs = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        log.info("Workflow timestamp: {}", wfTs);

        final double MAX_FUND_EXCLUSION_HZ = 500.0;
        final double MAX_FREQ_DRIFT_PPM = 3.0;

        double[] accCorrRe = accumulate ? new double[harmonics] : null;
        double[] accCorrIm = accumulate ? new double[harmonics] : null;
        double[] hFreqs    = accumulate ? new double[harmonics] : null;

        List<Double> thdHistory = new ArrayList<>();
        FftResult lastResult;
        FftResult bestResult = null;
        double bestThd = Double.MAX_VALUE;
        double[] bestAccRe = accumulate ? new double[harmonics] : null;
        double[] bestAccIm = accumulate ? new double[harmonics] : null;
        double[] bestHFreqs = accumulate ? new double[harmonics] : null;
        double[] bestAppliedRe = accumulate ? new double[harmonics] : null;
        double[] bestAppliedIm = accumulate ? new double[harmonics] : null;

        FftAnalyzer fftAnalyzer = new FftAnalyzer();

        log.info("--- Iteration 0: uncompensated sine ---");
        FftResult result0;
        double[] overlayFreqs0 = null;
        double[] overlayDbFs0  = null;
        double[] preCorrFreqs0 = null;
        double[] preCorrDbFs0  = null;
        for (int retry = 0; ; retry++) {
            float[] s0 = CaptureWithGenerator.run(
                    new SignalGenerator(GenSignalForm.SINE, frequency, sampleRate, amplitude, prefs.getDacFsVoltageRms()),
                    outDevice, inDevice, sampleRate, bitDepth, ditherBits, duration);
            try {
                result0 = fftAnalyzer.analyze(
                        s0, sampleRate, fftSize, harmonics,
                        windowType, overlap, snrFreqMin, snrFreqMax, coherent, fundRefDbFs,
                        freqRespCal == null, frequency);
            } catch (IllegalStateException e) {
                log.warn("Iteration 0 retry {}: {} — signal interrupted, retrying",
                        retry + 1, e.getMessage());
                continue;
            }
            s0 = null;
            if (freqRespCal != null) {
                double[][] ov = FreqRespCalHelper.computeOverlay(freqRespCal, result0);
                if (ov != null) {
                    overlayFreqs0 = ov[0];
                    overlayDbFs0  = ov[1];
                }
                double[][] pp = FreqRespCalHelper.capturePreCorrectionPeaks(result0);
                preCorrFreqs0 = pp[0];
                preCorrDbFs0  = pp[1];
                FreqRespCalHelper.applyCompensationInPlace(result0, freqRespCal, calNoise);
            }
            if (result0.fundamentalDynExclusionHz > MAX_FUND_EXCLUSION_HZ) {
                log.warn("Iteration 0 retry {}: fundamental exclusion {} Hz > {} Hz — signal interrupted, retrying",
                        retry + 1,
                        String.format(Locale.US, "%.1f", result0.fundamentalDynExclusionHz),
                        String.format(Locale.US, "%.1f", MAX_FUND_EXCLUSION_HZ));
                continue;
            }
            double ppm0 = 1e6 * (result0.fundamentalHzRefined - frequency) / frequency;
            if (Math.abs(ppm0) > MAX_FREQ_DRIFT_PPM) {
                log.warn("Iteration 0 retry {}: frequency drift {} ppm > ±{} ppm (gen={} Hz, meas={} Hz) — clock glitch, retrying",
                        retry + 1,
                        String.format(Locale.US, "%+.2f", ppm0),
                        String.format(Locale.US, "%.2f", MAX_FREQ_DRIFT_PPM),
                        String.format(Locale.US, "%.6f", frequency),
                        String.format(Locale.US, "%.6f", result0.fundamentalHzRefined));
                continue;
            }
            break;
        }

        double signalRatio = Math.pow(10.0, result0.fundamentalDbFs / 20.0);
        double adcFsVrms   = amplitude / signalRatio;
        log.info("ADC FS derived: {} V RMS  ({} V peak)  — fundamental at {} dBFS, generator {} V RMS",
                String.format(Locale.US, "%.4f", adcFsVrms),
                String.format(Locale.US, "%.4f", adcFsVrms * Math.sqrt(2.0)),
                String.format(Locale.US, "%.2f", result0.fundamentalDbFs),
                String.format(Locale.US, "%.4f", amplitude));

        if (accumulate) {
            accumulateHarmonics(result0, accCorrRe, accCorrIm, hFreqs,
                    compSnrMargin, compStep);
        }

        FftChartExporter exporter = new FftChartExporter();
        exporter.setAdcFsVoltageRms(prefs.getAdcFsVoltageRms());
        exporter.exportChart(result0, chartWidth, chartHeight, "results",
                "Iteration 0 (uncompensated)", false,
                "fft_chart_iter0_" + wfTs, frequency,
                overlayFreqs0, overlayDbFs0, preCorrFreqs0, preCorrDbFs0);
        ClockMismatch.log(0, frequency, result0.fundamentalHzRefined, sampleRate);

        thdHistory.add(result0.thdPct);
        lastResult = result0;
        bestResult = result0;
        bestThd = result0.thdPct;
        int bestIter = 0;
        if (accumulate) {
            System.arraycopy(accCorrRe, 0, bestAccRe, 0, harmonics);
            System.arraycopy(accCorrIm, 0, bestAccIm, 0, harmonics);
            System.arraycopy(hFreqs, 0, bestHFreqs, 0, harmonics);
        }
        log.info("Iteration 0 THD: {} %", String.format(Locale.US, "%.8f", result0.thdPct));

        List<IterSnapshot> snapshots = new ArrayList<>();
        snapshots.add(new IterSnapshot(0, result0,
                accumulate ? new double[harmonics] : null,
                accumulate ? new double[harmonics] : null,
                accumulate ? Arrays.copyOf(hFreqs, harmonics) : null));

        final AtomicBoolean stopRequested =
                new AtomicBoolean(false);
        Thread keyboardThread = new Thread(() -> {
            try {
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(System.in));
                while (!stopRequested.get()) {
                    if (System.in.available() > 0) {
                        r.readLine();
                        stopRequested.set(true);
                        log.info("Keyboard stop requested — finishing current iteration then saving best result.");
                        return;
                    }
                    Thread.sleep(200);
                }
            } catch (Exception e) {
                log.debug("Keyboard-stop watcher exited: {}", e.toString());
            }
        }, "iterative-compensate-keyboard-stop");
        keyboardThread.setDaemon(true);
        keyboardThread.start();
        log.info(">>> Press Enter at any time to stop the iterative loop and save the best result. <<<");

        for (int iter = 1; ; iter++) {
            if (stopRequested.get()) {
                log.info("Stopping on keyboard request after {} iteration(s).", iter - 1);
                break;
            }
            log.info("--- Iteration {}: compensated sine{} ---", iter,
                    accumulate ? " (accumulated corrections)" : "");

            FftResult result;
            double[] overlayFreqs = null;
            double[] overlayDbFs  = null;
            double[] preCorrFreqs = null;
            double[] preCorrDbFs  = null;
            for (int retry = 0; ; retry++) {
                SignalGenerator gen = accumulate
                        ? buildAccumulatedGenerator(frequency, sampleRate, amplitude,
                                accCorrRe, accCorrIm, hFreqs)
                        : new SignalGenerator(frequency, sampleRate, amplitude, prefs.getDacFsVoltageRms(), lastResult);
                float[] s = CaptureWithGenerator.run(gen,
                        outDevice, inDevice, sampleRate, bitDepth, ditherBits, duration);
                try {
                    result = fftAnalyzer.analyze(
                            s, sampleRate, fftSize, harmonics,
                            windowType, overlap, snrFreqMin, snrFreqMax, coherent, fundRefDbFs,
                            freqRespCal == null, frequency);
                } catch (IllegalStateException e) {
                    log.warn("Iteration {} retry {}: {} — signal interrupted, retrying",
                            iter, retry + 1, e.getMessage());
                    continue;
                }
                s = null;
                if (freqRespCal != null) {
                    double[][] ov = FreqRespCalHelper.computeOverlay(freqRespCal, result);
                    if (ov != null) {
                        overlayFreqs = ov[0];
                        overlayDbFs  = ov[1];
                    }
                    double[][] pp = FreqRespCalHelper.capturePreCorrectionPeaks(result);
                    preCorrFreqs = pp[0];
                    preCorrDbFs  = pp[1];
                    FreqRespCalHelper.applyCompensationInPlace(result, freqRespCal, calNoise);
                }
                if (result.fundamentalDynExclusionHz > MAX_FUND_EXCLUSION_HZ) {
                    log.warn("Iteration {} retry {}: fundamental exclusion {} Hz > {} Hz — signal interrupted, retrying",
                            iter, retry + 1,
                            String.format(Locale.US, "%.1f", result.fundamentalDynExclusionHz),
                            String.format(Locale.US, "%.1f", MAX_FUND_EXCLUSION_HZ));
                    continue;
                }
                double ppm = 1e6 * (result.fundamentalHzRefined - frequency) / frequency;
                if (Math.abs(ppm) > MAX_FREQ_DRIFT_PPM) {
                    log.warn("Iteration {} retry {}: frequency drift {} ppm > ±{} ppm (gen={} Hz, meas={} Hz) — clock glitch, retrying",
                            iter, retry + 1,
                            String.format(Locale.US, "%+.2f", ppm),
                            String.format(Locale.US, "%.2f", MAX_FREQ_DRIFT_PPM),
                            String.format(Locale.US, "%.6f", frequency),
                            String.format(Locale.US, "%.6f", result.fundamentalHzRefined));
                    continue;
                }
                break;
            }

            double[] appliedRe = null, appliedIm = null;
            if (accumulate) {
                appliedRe = Arrays.copyOf(accCorrRe, harmonics);
                appliedIm = Arrays.copyOf(accCorrIm, harmonics);
            }

            if (accumulate) {
                accumulateHarmonics(result, accCorrRe, accCorrIm, hFreqs,
                        compSnrMargin, compStep);
            }

            // Reuses the exporter configured for iteration 0 — same ADC full-scale.
            exporter.exportChart(result, chartWidth, chartHeight, "results",
                    String.format("Iteration %d", iter), false,
                    String.format("fft_chart_iter%d_", iter) + wfTs, frequency,
                    overlayFreqs, overlayDbFs, preCorrFreqs, preCorrDbFs);
            ClockMismatch.log(iter, frequency, result.fundamentalHzRefined, sampleRate);

            thdHistory.add(result.thdPct);
            lastResult = result;
            if (result.thdPct < bestThd) {
                bestThd = result.thdPct;
                bestResult = result;
                bestIter = iter;
                if (accumulate) {
                    System.arraycopy(accCorrRe, 0, bestAccRe, 0, harmonics);
                    System.arraycopy(accCorrIm, 0, bestAccIm, 0, harmonics);
                    System.arraycopy(hFreqs, 0, bestHFreqs, 0, harmonics);
                    System.arraycopy(appliedRe, 0, bestAppliedRe, 0, harmonics);
                    System.arraycopy(appliedIm, 0, bestAppliedIm, 0, harmonics);
                }
            }
            log.info("Iteration {} THD: {} %",
                    iter, String.format(Locale.US, "%.8f", result.thdPct));

            snapshots.add(new IterSnapshot(iter, result,
                    appliedRe == null ? null : Arrays.copyOf(appliedRe, harmonics),
                    appliedIm == null ? null : Arrays.copyOf(appliedIm, harmonics),
                    accumulate ? Arrays.copyOf(hFreqs, harmonics) : null));

            if (targetThd > 0 && result.thdPct <= targetThd) {
                log.info("THD {} % reached target {} % — stopping.",
                        String.format(Locale.US, "%.8f", result.thdPct),
                        String.format(Locale.US, "%.8f", targetThd));
                break;
            }

            int n = thdHistory.size();
            if (n >= stopAfter + 1) {
                boolean growing = true;
                for (int g = 0; g < stopAfter && growing; g++) {
                    growing = thdHistory.get(n - 1 - g) > thdHistory.get(n - 2 - g);
                }
                if (growing) {
                    StringBuilder sb = new StringBuilder("THD growing: ");
                    for (int g = stopAfter; g >= 0; g--) {
                        if (g < stopAfter) sb.append(" → ");
                        sb.append(String.format(Locale.US, "%.8f", thdHistory.get(n - 1 - g)));
                    }
                    sb.append(" — stopping.");
                    log.info(sb.toString());
                    break;
                }
            }
        }

        if (stopRequested.get() && snapshots.size() > 1) {
            log.info("=== Pick iteration to save ===");
            log.info("idx |  THD %       | applied?");
            for (IterSnapshot snap : snapshots) {
                String mark = snap.getIter() == bestIter ? "  <-- BEST" : "";
                log.info("{} | {} | {}{}",
                        String.format("%3d", snap.getIter()),
                        String.format(Locale.US, "%12.8f", snap.getResult().thdPct),
                        snap.getAppliedRe() != null ? "yes" : "no",
                        mark);
            }
            log.info("Enter iteration number to save (blank = keep BEST iter {}): ", bestIter);
            try {
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(System.in));
                String line = r.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    int pick = Integer.parseInt(line.trim());
                    IterSnapshot chosen = null;
                    for (IterSnapshot snap : snapshots) {
                        if (snap.getIter() == pick) { chosen = snap; break; }
                    }
                    if (chosen == null) {
                        log.warn("Iteration {} not found in snapshots — keeping BEST iter {}.",
                                pick, bestIter);
                    } else {
                        bestIter   = chosen.getIter();
                        bestResult = chosen.getResult();
                        bestThd    = chosen.getResult().thdPct;
                        if (accumulate) {
                            System.arraycopy(chosen.getAppliedRe(), 0, bestAppliedRe, 0, harmonics);
                            System.arraycopy(chosen.getAppliedIm(), 0, bestAppliedIm, 0, harmonics);
                            System.arraycopy(chosen.getHFreqs(),    0, bestHFreqs,    0, harmonics);
                        }
                        log.info("User picked iteration {} (THD {} %).",
                                bestIter, String.format(Locale.US, "%.8f", bestThd));
                    }
                } else {
                    log.info("Keeping BEST iteration {} (THD {} %).",
                            bestIter, String.format(Locale.US, "%.8f", bestThd));
                }
            } catch (Exception e) {
                log.warn("Could not read iteration choice ({}). Keeping BEST iter {}.",
                        e.getMessage(), bestIter);
            }
        }

        String csvPath = HarmonicsCsv.export(bestResult, "results",
                "fft_harmonics_" + wfTs);
        log.info("Best-iteration residual harmonics CSV (iter {}): {}", bestIter, csvPath);

        if (accumulate) {
            String appliedCsv = exportAppliedCompensationCsv(
                    bestAppliedRe, bestAppliedIm, bestHFreqs,
                    bestResult.fundamentalHzRefined, bestResult.fundamentalDbFs,
                    sampleRate, bitDepth, amplitude,
                    "results", "applied_compensation_" + wfTs);
            log.info("Applied compensation for best iter {}: {}", bestIter, appliedCsv);
        }

        log.info("=== THD history ===");
        for (int i = 0; i < thdHistory.size(); i++) {
            double thd = thdHistory.get(i);
            log.info("  Iter {}: {} %", i, String.format(Locale.US, "%.8f", thd));
        }
        log.info("Best: iteration {} with THD {} %",
                bestIter, String.format(Locale.US, "%.8f", bestThd));
    }

    private void accumulateHarmonics(FftResult r,
            double[] accRe, double[] accIm, double[] hFreqs,
            double snrMarginDb, double step) {
        double phi1   = Math.atan2(r.im[r.fundamentalBin], r.re[r.fundamentalBin]);
        double omegaD = -(phi1 + Math.PI / 2.0);
        double delayRadPerHz = omegaD / r.fundamentalHzRefined;

        int count = Math.min(r.harmonicCount, accRe.length);
        double skipBelowDbFs = r.avgNoiseFloorDbFs + snrMarginDb;

        for (int h = 0; h < count; h++) {
            int bin = r.harmonicBins[h];
            if (bin <= 0) continue;
            if (r.harmonicDbFs[h] < skipBelowDbFs) {
                log.info("H{} skipped ({} dBFS < noise floor {} dBFS + {} dB margin) — near noise",
                        h + 2,
                        String.format(Locale.US, "%.2f", r.harmonicDbFs[h]),
                        String.format(Locale.US, "%.2f", r.avgNoiseFloorDbFs),
                        String.format(Locale.US, "%.1f", snrMarginDb));
                continue;
            }
            double ampRatio = r.harmonicPct[h] / 100.0;
            double phiH     = Math.atan2(r.im[bin], r.re[bin]);
            double freqHz   = r.harmonicHz[h];
            double phiInit  = phiH + freqHz * delayRadPerHz;
            accRe[h]  += step * ampRatio * Math.cos(phiInit);
            accIm[h]  += step * ampRatio * Math.sin(phiInit);
            hFreqs[h]  = r.harmonicHz[h];
        }
    }

    private SignalGenerator buildAccumulatedGenerator(
            double frequency, int sampleRate, double amplitude,
            double[] accRe, double[] accIm, double[] hFreqs) {
        int valid = 0;
        for (int h = 0; h < accRe.length; h++) {
            if (accRe[h] != 0.0 || accIm[h] != 0.0) valid++;
        }
        double[] ampRatios = new double[valid];
        double[] phiInits  = new double[valid];
        double[] freqs     = new double[valid];
        int i = 0;
        for (int h = 0; h < accRe.length; h++) {
            double re = accRe[h], im = accIm[h];
            if (re == 0.0 && im == 0.0) continue;
            ampRatios[i] = Math.sqrt(re * re + im * im);
            phiInits[i]  = Math.atan2(im, re);
            freqs[i]     = hFreqs[h];
            i++;
        }
        return new SignalGenerator(frequency, sampleRate, amplitude, prefs.getDacFsVoltageRms(),
                ampRatios, phiInits, freqs);
    }

    private String exportAppliedCompensationCsv(
            double[] accRe, double[] accIm, double[] hFreqs,
            double fundamentalHz, double fundamentalDbFs,
            int sampleRate, int bitDepth, double amplitudeVRms,
            String directory, String filePrefix) throws IOException {
        File outFile = new File(directory, filePrefix + ".csv");
        try (PrintWriter pw = new PrintWriter(
                new BufferedWriter(new FileWriter(outFile)))) {
            pw.printf(Locale.US, "# sample_rate_hz=%d%n", sampleRate);
            pw.printf(Locale.US, "# bit_depth=%d%n",      bitDepth);
            pw.printf(Locale.US, "# amplitude_vrms=%.10f%n", amplitudeVRms);
            pw.printf(Locale.US, "# frequency_hz=%.10f%n",   fundamentalHz);
            pw.println("harmonic;frequency_hz;amplitude_dbfs;amplitude_pct;phase_deg;re;im");
            pw.printf(Locale.GERMAN, "1;%.6f;%.4f;100.000000;-90.0000;%.10e;%.10e%n",
                    fundamentalHz, fundamentalDbFs, 0.0, -1.0);
            for (int h = 0; h < accRe.length; h++) {
                double re = accRe[h], im = accIm[h];
                double amp = Math.sqrt(re * re + im * im);
                if (amp == 0.0) continue;
                double phaseDeg = Math.toDegrees(Math.atan2(im, re));
                double ampPct   = amp * 100.0;
                double ampDbFs  = fundamentalDbFs + 20.0 * Math.log10(amp);
                pw.printf(Locale.GERMAN, "%d;%.6f;%.4f;%.9f;%.4f;%.10e;%.10e%n",
                        h + 2, hFreqs[h], ampDbFs, ampPct, phaseDeg, re, im);
            }
        }
        return outFile.getAbsolutePath();
    }
}
