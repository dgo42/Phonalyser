package org.edgo.audio.measure.cli;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.edgo.audio.measure.adc.WeightedBuffer;
import org.edgo.audio.measure.chart.FftChartExporter;
import org.edgo.audio.measure.cli.util.AdcCorrection;
import org.edgo.audio.measure.cli.util.AdcCorrectionHelper;
import org.edgo.audio.measure.cli.util.ArgParser;
import org.edgo.audio.measure.cli.util.ClockMismatch;
import org.edgo.audio.measure.cli.util.FreqRespCalHelper;
import org.edgo.audio.measure.cli.util.FreqRespCalibration;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.fft.HarmonicsCsv;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.wav.WavReader;

import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;

/**
 * {@code --fft-analyze <wav>} — offline FFT analysis of a captured WAV.
 *
 * <p>Loads the WAV (channel 1), optionally linearises each ADC code through a
 * {@code --load-weighted} INL map and/or subtracts known harmonics via
 * {@code --sub-harmonics}, then runs a single FFT pass to produce THD, THD+N
 * (A-weighted), SNR, ENOB, and the per-harmonic table.  Two optional
 * post-correction passes mutate the result in place: {@code --adc-comp}
 * subtracts the ADC's predicted distortion contribution at each harmonic bin;
 * {@code --cal} divides every FFT bin by the frequancy response's H(f) to
 * undo a downstream frequency response.  Outputs the spectrum chart + CSV
 * and the harmonics CSV (the latter is the input format for
 * {@code --sub-harmonics} and {@code --iterative-compensate}).
 *
 * <p>Required: {@code --fft-size}, {@code --width}, {@code --height}.
 */
@Log4j2
@UtilityClass
public class FftAnalyzeMode {

    public void run(String[] args) throws Exception {
        String fileArg      = ArgParser.getArgValue(args, "--fft-analyze");
        String fftSizeArg   = ArgParser.getArgValue(args, "--fft-size");
        String harmonicsArg = ArgParser.getArgValue(args, "--harmonics");
        String widthArg     = ArgParser.getArgValue(args, "--width");
        String heightArg    = ArgParser.getArgValue(args, "--height");
        String windowArg    = ArgParser.getArgValue(args, "--window-fn");
        String overlapArg   = ArgParser.getArgValue(args, "--overlap");
        String snrMinArg    = ArgParser.getArgValue(args, "--dist-min");
        String snrMaxArg    = ArgParser.getArgValue(args, "--dist-max");
        String fundVArg     = ArgParser.getArgValue(args, "--fund-v");
        String fundDbVArg   = ArgParser.getArgValue(args, "--fund-dbv");
        String  subHarmArg  = ArgParser.getArgValue(args, "--sub-harmonics");
        boolean subHarmReIm = ArgParser.hasArg(args, "--sub-harmonics-reim");
        boolean coherent    = !ArgParser.hasArg(args, "--no-coherent");
        String  commentArg  = ArgParser.getArgValue(args, "--comment");
        String  freqArg     = ArgParser.getArgValue(args, "--freq");
        String  adcCompArg  = ArgParser.getArgValue(args, "--adc-comp");
        String  freqRespCalArg = ArgParser.getArgValue(args, "--cal");
        boolean calNoise     = ArgParser.hasArg(args, "--cal-noise");
        String  adcFsArg     = ArgParser.getArgValue(args, "--adc-fs-vrms");
        String  loadWeightedArg = ArgParser.getArgValue(args, "--load-weighted");
        if (adcFsArg != null) {
            AudioBackend.setAdcFsVoltageRms(Double.parseDouble(adcFsArg));
        }

        if (fftSizeArg == null) {
            log.error("--fft-size is required for --fft-analyze.");
            System.exit(1);
        }
        if (widthArg == null) {
            log.error("--width is required for --fft-analyze.");
            System.exit(1);
        }
        if (heightArg == null) {
            log.error("--height is required for --fft-analyze.");
            System.exit(1);
        }

        int fftSize      = Integer.parseInt(fftSizeArg);
        int harmonics    = harmonicsArg != null ? Integer.parseInt(harmonicsArg) : 10;
        int chartWidth   = Integer.parseInt(widthArg);
        int chartHeight  = Integer.parseInt(heightArg);
        WindowType windowType = windowArg  != null
                ? WindowType.valueOf(windowArg)
                : WindowType.HANN;
        FftOverlap overlap = overlapArg != null
                ? FftOverlap.fromString(overlapArg)
                : FftOverlap.PCT_0;

        if (Integer.bitCount(fftSize) != 1) {
            log.error("--fft-size must be a power of 2 (e.g. 65536, 131072).");
            System.exit(1);
        }

        WavReader reader = new WavReader(fileArg);
        int sampleRate   = reader.getSampleRate();
        int bitDepth     = reader.getBitsPerSample();
        double snrFreqMin = snrMinArg != null ? Double.parseDouble(snrMinArg) : 0.0;
        double snrFreqMax = snrMaxArg != null ? Double.parseDouble(snrMaxArg) : sampleRate / 2.0;
        double fundRefDbV;
        if (fundVArg != null) {
            double v = Double.parseDouble(fundVArg);
            fundRefDbV = 20.0 * Math.log10(v);
        } else if (fundDbVArg != null) {
            fundRefDbV = Double.parseDouble(fundDbVArg);
        } else {
            fundRefDbV = Double.NaN;
        }

        Double genFreqHz = freqArg != null ? Double.parseDouble(freqArg) : null;

        log.info("Mode      : FFT analysis");
        log.info("File      : {}", fileArg);
        log.info("SampleRate: {} Hz", sampleRate);
        log.info("Bits      : {}", bitDepth);
        log.info("FFT size  : {}", fftSize);
        log.info("Harmonics : {}", harmonics);
        log.info("Window    : {}", windowType);
        log.info("Overlap   : {}", overlap.label);
        log.info("SNR range : {}-{} Hz", snrFreqMin, snrFreqMax);
        log.info("Averaging : {}", coherent ? "coherent" : "incoherent");
        log.info("Chart     : {}x{} px", chartWidth, chartHeight);
        if (genFreqHz != null) log.info("Gen freq  : {} Hz", String.format(Locale.US, "%.6f", genFreqHz));

        WeightedBuffer weights = null;
        if (loadWeightedArg != null) {
            weights = new WeightedBuffer(bitDepth);
            weights.loadCsv(loadWeightedArg);
            weights.buildCodeMap();
            log.info("Weighted  : {}", loadWeightedArg);
        }

        long totalFrames = reader.getFrameCount();
        float[] samples  = new float[(int) Math.min(totalFrames, Integer.MAX_VALUE)];
        long   halfRange = 1L << (bitDepth - 1);
        AtomicInteger pos = new AtomicInteger(0);

        final WeightedBuffer w = weights;
        reader.process(block -> {
            int n = pos.get();
            for (int i = 0; i < block.length && n + i < samples.length; i++) {
                double code = w != null
                        ? w.correctedCode(block[i]).ch1
                        : (double) block[i].ch1;
                samples[n + i] = (float) ((code - halfRange) / (double) halfRange);
            }
            pos.addAndGet(block.length);
        });

        int actualSamples = pos.get();
        float[] trimmed   = actualSamples < samples.length
                ? Arrays.copyOf(samples, actualSamples)
                : samples;

        FftAnalyzer fftAnalyzer = new FftAnalyzer();
        if (subHarmArg != null) {
            log.info("Sub-harm  : {}  (mode: {})", subHarmArg, subHarmReIm ? "re/im" : "amp+phase");
            HarmonicsCsv.subtract(trimmed, sampleRate, subHarmArg, subHarmReIm);
        }

        boolean willPostCorrect = adcCompArg != null || freqRespCalArg != null;
        double expectedFundHz = genFreqHz != null ? genFreqHz : Double.NaN;
        FftResult result = fftAnalyzer.analyze(trimmed, sampleRate, fftSize, harmonics,
                windowType, overlap, snrFreqMin, snrFreqMax, coherent, fundRefDbV,
                !willPostCorrect, expectedFundHz);

        if (adcCompArg != null) {
            log.info("ADC comp  : {}", adcCompArg);
            AdcCorrection adc = AdcCorrectionHelper.loadCsv(adcCompArg);
            AdcCorrectionHelper.logCorrectedHarmonics(result, adc);
            AdcCorrectionHelper.applyToResultInPlace(result, adc);
            log.info("THD   : {}%  ({} dB)  [after ADC correction]",
                    String.format(Locale.US, "%.8f", result.thdPct),
                    String.format(Locale.US, "%.2f", result.thdDb));
        }

        double[] overlayFreqs  = null;
        double[] overlayDbFs   = null;
        double[] preCorrFreqs  = null;
        double[] preCorrDbFs   = null;
        if (freqRespCalArg != null) {
            log.info("Frequency response cal: {}{}", freqRespCalArg, calNoise ? "  (--cal-noise: correct all bins)" : "");
            // FreqResp files are stereo since v6; the FFT compensation
            // path is single-channel, so apply the left side (matches the
            // primary capture channel both modes assume).
            FreqRespCalibration cal = FreqRespCalHelper.loadCsv(freqRespCalArg).left();
            double[][] overlay  = FreqRespCalHelper.computeOverlay(cal, result);
            double[][] prePeaks = FreqRespCalHelper.capturePreCorrectionPeaks(result);
            if (overlay != null) {
                overlayFreqs = overlay[0];
                overlayDbFs  = overlay[1];
            }
            preCorrFreqs = prePeaks[0];
            preCorrDbFs  = prePeaks[1];
            FreqRespCalHelper.applyCompensationInPlace(result, cal, calNoise);
            log.info("THD   : {}%  ({} dB)  [after frequency response de-attenuation]",
                    String.format(Locale.US, "%.8f", result.thdPct),
                    String.format(Locale.US, "%.2f", result.thdDb));
        }

        FreqRespCalHelper.applyDefaultDbvScaling(result);

        fftAnalyzer.exportFftCsv(result, "results");
        HarmonicsCsv.export(result, "results");
        FftChartExporter.exportChart(result, chartWidth, chartHeight, "results",
                commentArg, subHarmArg != null, null, genFreqHz,
                overlayFreqs, overlayDbFs, preCorrFreqs, preCorrDbFs);
        if (genFreqHz != null) {
            ClockMismatch.log(0, genFreqHz, result.fundamentalHzRefined, sampleRate);
        }
    }
}
