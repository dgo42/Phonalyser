package org.edgo.audio.measure.cli;

import java.util.Locale;

import org.edgo.audio.measure.adc.WeightedBuffer;
import org.edgo.audio.measure.chart.FftChartExporter;
import org.edgo.audio.measure.cli.util.ArgParser;
import org.edgo.audio.measure.cli.util.CaptureWithGenerator;
import org.edgo.audio.measure.cli.util.ClockMismatch;
import org.edgo.audio.measure.cli.util.DeviceSelector;
import org.edgo.audio.measure.cli.util.FreqRespCalHelper;
import org.edgo.audio.measure.cli.util.FreqRespCalibration;
import org.edgo.audio.measure.cli.util.SampleRates;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.fft.HarmonicsCsv;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.DeviceRef;

import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;

/**
 * {@code --gen-fft} — single-shot generate → capture → FFT analyze pipeline
 * with no intermediate WAV file.
 *
 * <p>Snaps the requested fundamental to the nearest exact FFT bin (so coherent
 * averaging has no leakage), plays the generator on {@code --out-device} and
 * records on {@code --in-device} for {@code --duration} seconds (defaults to
 * just enough samples for one FFT frame), then runs one FFT pass and writes
 * the spectrum CSV + chart + harmonics CSV.  Optional {@code --cal} applies
 * frequency response de-embedding before stats are computed; {@code --sync-pause} holds
 * the recorder for N seconds after the generator starts so an external
 * phase-locked source can settle.  Logs the DAC↔ADC clock mismatch in ppm.
 *
 * <p>Same capture/analyze pipeline as iteration 0 of {@code
 * --iterative-compensate}, without the iteration loop or accumulator.
 *
 * <p>Required: {@code --samplerate}, {@code --freq}, {@code --amplitude},
 * {@code --fft-size}, {@code --width}, {@code --height}, {@code --out-device},
 * {@code --in-device}.
 */
@Log4j2
@UtilityClass
public class GenFftMode {

    public void run(String[] args) throws Exception {
        String samplerateArg = ArgParser.getArgValue(args, "--samplerate");
        String bitsArg       = ArgParser.getArgValue(args, "--bits");
        String freqArg       = ArgParser.getArgValue(args, "--freq");
        String amplitudeArg  = ArgParser.getArgValue(args, "--amplitude");
        String ditherArg     = ArgParser.getArgValue(args, "--dither");
        String durationArg   = ArgParser.getArgValue(args, "--duration");
        String signalArg     = ArgParser.getArgValue(args, "--signal");
        String fftSizeArg    = ArgParser.getArgValue(args, "--fft-size");
        String harmonicsArg  = ArgParser.getArgValue(args, "--harmonics");
        String windowArg     = ArgParser.getArgValue(args, "--window-fn");
        String overlapArg    = ArgParser.getArgValue(args, "--overlap");
        String distMinArg    = ArgParser.getArgValue(args, "--dist-min");
        String distMaxArg    = ArgParser.getArgValue(args, "--dist-max");
        String fundVArg      = ArgParser.getArgValue(args, "--fund-v");
        String fundDbVArg    = ArgParser.getArgValue(args, "--fund-dbv");
        boolean coherent     = !ArgParser.hasArg(args, "--no-coherent");
        String widthArg      = ArgParser.getArgValue(args, "--width");
        String heightArg     = ArgParser.getArgValue(args, "--height");
        String freqRespCalArg  = ArgParser.getArgValue(args, "--cal");
        boolean calNoise     = ArgParser.hasArg(args, "--cal-noise");
        String adcFsArg      = ArgParser.getArgValue(args, "--adc-fs-vrms");
        String commentArg    = ArgParser.getArgValue(args, "--comment");
        String outputDirArg  = ArgParser.getArgValue(args, "--output");
        String harmonicsCsv  = ArgParser.getArgValue(args, "--harmonics-csv");
        String loadWeightedArg = ArgParser.getArgValue(args, "--load-weighted");
        String syncPauseArg  = ArgParser.getArgValue(args, "--sync-pause");
        if (adcFsArg != null) {
            AudioBackend.setAdcFsVoltageRms(Double.parseDouble(adcFsArg));
        }

        if (samplerateArg == null) { log.error("--samplerate required"); System.exit(1); }
        if (freqArg       == null) { log.error("--freq required");       System.exit(1); }
        if (amplitudeArg  == null) { log.error("--amplitude required");  System.exit(1); }
        if (fftSizeArg    == null) { log.error("--fft-size required");   System.exit(1); }
        if (widthArg      == null) { log.error("--width required");      System.exit(1); }
        if (heightArg     == null) { log.error("--height required");     System.exit(1); }

        int    sampleRate = Integer.parseInt(samplerateArg);
        int    bitDepth   = bitsArg    != null ? Integer.parseInt(bitsArg)    : 32;
        double frequency  = Double.parseDouble(freqArg);
        double amplitude  = Double.parseDouble(amplitudeArg);
        int    ditherBits = ditherArg  != null ? Integer.parseInt(ditherArg)  : 0;
        int    fftSize    = Integer.parseInt(fftSizeArg);
        int    harmonics  = harmonicsArg != null ? Integer.parseInt(harmonicsArg) : 10;
        int    chartWidth  = Integer.parseInt(widthArg);
        int    chartHeight = Integer.parseInt(heightArg);
        String outputDir   = outputDirArg != null ? outputDirArg : "results";
        GenSignalForm form = signalArg != null
                ? GenSignalForm.fromString(signalArg)
                : GenSignalForm.SINE;
        WindowType windowType = windowArg != null
                ? WindowType.fromString(windowArg) : WindowType.HANN;
        FftOverlap overlap = overlapArg != null
                ? FftOverlap.fromString(overlapArg)   : FftOverlap.PCT_0;

        if (!SampleRates.isValid(sampleRate)) {
            log.error("--samplerate must be one of the supported rates"); System.exit(1);
        }
        if (Integer.bitCount(fftSize) != 1) {
            log.error("--fft-size must be a power of 2"); System.exit(1);
        }
        if (bitDepth != 8 && bitDepth != 16 && bitDepth != 24 && bitDepth != 32) {
            log.error("--bits must be 8, 16, 24 or 32"); System.exit(1);
        }

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

        double freqRequested = frequency;
        long   alignedBin    = Math.round(frequency * fftSize / (double) sampleRate);
        if (alignedBin < 1) alignedBin = 1;
        frequency = alignedBin * sampleRate / (double) fftSize;

        int duration = durationArg != null
                ? Integer.parseInt(durationArg)
                : (int) Math.ceil(fftSize / (double) sampleRate) + 1;

        DeviceSelector.logProviders();
        DeviceRef outDevice = DeviceSelector.selectMixerByFlag(args, "--out-device", true);
        DeviceRef inDevice  = DeviceSelector.selectMixerByFlag(args, "--in-device",  false);
        if (outDevice == null) { log.error("No output device. Use --out-device <index>."); System.exit(1); }
        if (inDevice  == null) { log.error("No input device. Use --in-device <index>.");  System.exit(1); }

        FreqRespCalibration freqRespCal = freqRespCalArg != null
                ? FreqRespCalHelper.loadCsv(freqRespCalArg).left() : null;

        log.info("=== Gen + FFT (single shot) ===");
        log.info("Out device : {}", outDevice.name());
        log.info("In device  : {}", inDevice.name());
        log.info("Sample rate: {} Hz", sampleRate);
        log.info("Bits       : {}",    bitDepth);
        log.info("Signal     : {}",    form);
        log.info("Frequency  : {} Hz (requested {} Hz, snapped to bin {})",
                String.format(Locale.US, "%.6f", frequency),
                String.format(Locale.US, "%.6f", freqRequested),
                alignedBin);
        log.info("Amplitude  : {} V RMS", amplitude);
        log.info("Duration   : {} s",     duration);
        log.info("FFT size   : {}",       fftSize);
        log.info("Harmonics  : {}",       harmonics);
        log.info("Window     : {}",       windowType);
        log.info("Overlap    : {}",       overlap.label);
        log.info("SNR range  : {}-{} Hz", snrFreqMin, snrFreqMax);
        log.info("Averaging  : {}",       coherent ? "coherent" : "incoherent");
        if (freqRespCalArg != null) {
            log.info("Frequency response cal : {}{}", freqRespCalArg, calNoise ? "  (--cal-noise: correct all bins)" : "");
        }
        if (harmonicsCsv != null) {
            log.info("Harmonics  : {}",   harmonicsCsv);
        }

        int syncPauseSec = 0;
        if (syncPauseArg != null) {
            syncPauseSec = Integer.parseInt(syncPauseArg);
            if (syncPauseSec < 0 || syncPauseSec > 60) {
                log.error("--sync-pause must be in [0, 60] seconds");
                System.exit(1);
            }
            if (syncPauseSec > 0) {
                log.info("Sync pause : {} s before capture (generator runs during this window)",
                        syncPauseSec);
            }
        }

        WeightedBuffer weights = null;
        if (loadWeightedArg != null) {
            weights = new WeightedBuffer(bitDepth);
            weights.loadCsv(loadWeightedArg);
            weights.buildCodeMap();
            log.info("Weighted   : {}", loadWeightedArg);
        }

        SignalGenerator gen;
        if (form == GenSignalForm.SINE_COMPENSATED) {
            if (harmonicsCsv == null) {
                log.error("--harmonics-csv <file> is required for --signal sine_compensated");
                System.exit(1);
            }
            gen = new SignalGenerator(frequency, sampleRate, amplitude, harmonicsCsv);
        } else {
            if (harmonicsCsv != null) {
                log.warn("--harmonics-csv is only used with --signal sine_compensated; ignoring");
            }
            gen = new SignalGenerator(form, frequency, sampleRate, amplitude);
        }
        float[] samples = CaptureWithGenerator.run(gen, outDevice, inDevice,
                sampleRate, bitDepth, ditherBits, duration, weights, syncPauseSec);

        FftAnalyzer fftAnalyzer = new FftAnalyzer();
        FftResult result = fftAnalyzer.analyze(
                samples, sampleRate, fftSize, harmonics,
                windowType, overlap, snrFreqMin, snrFreqMax, coherent, fundRefDbV,
                freqRespCal == null, frequency);

        double[] overlayFreqs = null;
        double[] overlayDbFs  = null;
        double[] preCorrFreqs = null;
        double[] preCorrDbFs  = null;
        if (freqRespCal != null) {
            double[][] ov = FreqRespCalHelper.computeOverlay(freqRespCal, result);
            if (ov != null) { overlayFreqs = ov[0]; overlayDbFs = ov[1]; }
            double[][] pp = FreqRespCalHelper.capturePreCorrectionPeaks(result);
            preCorrFreqs = pp[0];
            preCorrDbFs  = pp[1];
            FreqRespCalHelper.applyCompensationInPlace(result, freqRespCal, calNoise);
            log.info("THD   : {}%  ({} dB)  [after frequency response de-attenuation]",
                    String.format(Locale.US, "%.8f", result.thdPct),
                    String.format(Locale.US, "%.2f", result.thdDb));
        }
        FreqRespCalHelper.applyDefaultDbvScaling(result);

        fftAnalyzer.exportFftCsv(result, outputDir);
        HarmonicsCsv.export(result, outputDir);
        FftChartExporter.exportChart(result, chartWidth, chartHeight, outputDir,
                commentArg, false, null, frequency,
                overlayFreqs, overlayDbFs, preCorrFreqs, preCorrDbFs);
        ClockMismatch.log(0, frequency, result.fundamentalHzRefined, sampleRate);
    }
}
