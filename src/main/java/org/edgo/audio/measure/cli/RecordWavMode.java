package org.edgo.audio.measure.cli;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import org.edgo.audio.measure.adc.WeightedBuffer;
import org.edgo.audio.measure.chart.WaveformExporter;
import org.edgo.audio.measure.cli.util.AdcCorrection;
import org.edgo.audio.measure.cli.util.AdcCorrectionHelper;
import org.edgo.audio.measure.cli.util.ArgParser;
import org.edgo.audio.measure.cli.util.DeviceSelector;
import org.edgo.audio.measure.cli.util.PcmUtils;
import org.edgo.audio.measure.cli.util.SampleRates;
import org.edgo.audio.measure.common.StereoSampleFloat;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.AudioCapture;
import org.edgo.audio.measure.sound.DeviceRef;
import org.edgo.audio.measure.wav.WavWriter;

import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;

/**
 * {@code --record-wav} / {@code --record-mapped-wav} — raw ADC capture to WAV.
 *
 * <p>{@code --record-wav}: captures the configured input device for
 * {@code --duration} seconds and writes the bytes straight to disk (no
 * sample-level processing).  Optionally renders a waveform PNG, and — when
 * {@code --adc-comp} + {@code --fft-size} are supplied — also emits an
 * {@code <name>_adc_corrected.wav} where per-frame ADC distortion has been
 * subtracted (the fundamental's phase/level extracted via an inline FFT
 * drives the time-domain correction kernel).
 *
 * <p>{@code --record-mapped-wav <csv>}: same capture path but every sample is
 * run through the supplied weighted-code-map CSV (from
 * {@code --analyze-histogram}) before being written; the output WAV is one
 * bit-depth wider to fit linearised codes without quantisation loss.
 *
 * <p>Required: {@code --duration}, {@code --samplerate}.
 */
@Log4j2
@UtilityClass
public class RecordWavMode {

    public void run(String[] args) throws Exception {
        String mappedWeightedArg   = ArgParser.getArgValue(args, "--record-mapped-wav");
        String durationArg         = ArgParser.getArgValue(args, "--duration");
        String samplerateArg       = ArgParser.getArgValue(args, "--samplerate");
        String bitsArg             = ArgParser.getArgValue(args, "--bits");
        String outputArg           = ArgParser.getArgValue(args, "--output");
        String waveformDurationArg = ArgParser.getArgValue(args, "--waveform-duration");
        String scaleArg            = ArgParser.getArgValue(args, "--scale");
        String widthArg            = ArgParser.getArgValue(args, "--width");
        String heightArg           = ArgParser.getArgValue(args, "--height");
        String adcCompArg          = ArgParser.getArgValue(args, "--adc-comp");
        String fftSizeArg          = ArgParser.getArgValue(args, "--fft-size");
        String harmonicsArg        = ArgParser.getArgValue(args, "--harmonics");

        if (durationArg == null) {
            log.error("--duration is required for WAV recording.");
            System.exit(1);
        }
        if (samplerateArg == null) {
            log.error("--samplerate is required for WAV recording.");
            System.exit(1);
        }

        int durationSeconds = Integer.parseInt(durationArg);
        int sampleRate      = Integer.parseInt(samplerateArg);
        if (!SampleRates.isValid(sampleRate)) {
            log.error("--samplerate must be one of: 44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000");
            System.exit(1);
        }
        int bitDepth = (bitsArg != null) ? Integer.parseInt(bitsArg) : 24;
        if (bitDepth != 8 && bitDepth != 16 && bitDepth != 24 && bitDepth != 32) {
            log.error("--bits must be 8, 16, 24 or 32.");
            System.exit(1);
        }
        double waveformDurationMs = 0;
        if (waveformDurationArg != null) {
            waveformDurationMs = Double.parseDouble(waveformDurationArg);
            if (waveformDurationMs <= 0 || waveformDurationMs > 10.0) {
                log.error("--waveform-duration must be > 0 and <= 10 ms.");
                System.exit(1);
            }
        }
        float scaleVolts  = (scaleArg  != null) ? Float.parseFloat(scaleArg)  : 1.0f;
        int   chartWidth  = (widthArg  != null) ? Integer.parseInt(widthArg)  : 1920;
        int   chartHeight = (heightArg != null) ? Integer.parseInt(heightArg) : 400;

        DeviceRef mixer = DeviceSelector.selectMixer(args);
        if (mixer == null) {
            log.error("No suitable audio input device found.");
            System.exit(1);
        }

        if (mappedWeightedArg != null) {
            log.info("Mode       : record + weighted map → WAV");
            log.info("Weights    : {}", mappedWeightedArg);
            log.info("Device     : {}", mixer.name());
            log.info("SampleRate : {} Hz", sampleRate);
            log.info("Bits       : {}", bitDepth);
            log.info("Duration   : {} second(s)", durationSeconds);
            if (waveformDurationMs > 0) {
                log.info("Waveform   : {} ms", waveformDurationMs);
            }
            WeightedBuffer weights = new WeightedBuffer(bitDepth);
            weights.loadCsv(mappedWeightedArg);
            weights.buildCodeMap();
            recordMappedWav(mixer, sampleRate, durationSeconds, bitDepth, scaleVolts, weights, outputArg,
                    waveformDurationMs, chartWidth, chartHeight);
        } else {
            log.info("Mode       : record raw → WAV");
            log.info("Device     : {}", mixer.name());
            log.info("SampleRate : {} Hz", sampleRate);
            log.info("Bits       : {}", bitDepth);
            log.info("Duration   : {} second(s)", durationSeconds);
            if (waveformDurationMs > 0) {
                log.info("Waveform   : {} ms", waveformDurationMs);
            }
            int    fftSize     = fftSizeArg   != null ? Integer.parseInt(fftSizeArg)   : 0;
            int    harmonics   = harmonicsArg != null ? Integer.parseInt(harmonicsArg) : 10;
            if (adcCompArg != null && fftSize == 0) {
                log.error("--adc-comp requires --fft-size to derive fundamental level/phase from the recording.");
                System.exit(1);
            }
            if (adcCompArg != null && Integer.bitCount(fftSize) != 1) {
                log.error("--fft-size must be a power of 2 (e.g. 65536, 131072).");
                System.exit(1);
            }
            recordWav(mixer, sampleRate, durationSeconds, bitDepth, outputArg,
                    waveformDurationMs, scaleVolts, chartWidth, chartHeight,
                    adcCompArg, fftSize, harmonics);
        }
    }

    private void recordWav(DeviceRef device, int sampleRate, int durationSeconds, int bitDepth,
                           String outputPath, double waveformDurationMs,
                           double scaleVolts, int chartWidth, int chartHeight,
                           String adcCompPath, int fftSize, int harmonics) throws Exception {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File outFile = outputPath != null
                ? new File(outputPath)
                : new File("recording_" + ts + ".wav");

        final int   wfMax = waveformDurationMs > 0 ? (int) Math.ceil(waveformDurationMs * sampleRate / 1000.0) : 0;
        final int[] wfBuf = wfMax > 0 ? new int[wfMax] : null;
        final AtomicInteger wfCollected = new AtomicInteger(0);

        final int sampleBytes  = bitDepth / 8;
        final int frameSize    = sampleBytes * 2;
        final int skipFrames   = sampleRate / 100;
        final AtomicInteger skipped = new AtomicInteger(0);

        final int     totalFramesAlloc = adcCompPath != null
                ? (int) Math.min((long) sampleRate * (durationSeconds + 1), Integer.MAX_VALUE)
                : 0;
        final float[] capturedCh1 = totalFramesAlloc > 0 ? new float[totalFramesAlloc] : null;
        final AtomicInteger capPos = new AtomicInteger(0);
        final long halfRange = 1L << (bitDepth - 1);

        try (WavWriter wav = new WavWriter(outFile, sampleRate, 2, bitDepth, false);
             AudioCapture recorder = AudioBackend.instance().openCapture(device, sampleRate, bitDepth)) {
            recorder.setRawBytesListener(bytes -> {
                if (skipped.get() < skipFrames) {
                    skipped.addAndGet(bytes.length / frameSize);
                    return;
                }
                try {
                    wav.writeRaw(bytes, bytes.length);
                } catch (IOException e) {
                    log.error("WAV write error", e);
                }
                if (wfBuf != null) {
                    int n = wfCollected.get();
                    if (n < wfMax) {
                        int frames = bytes.length / frameSize;
                        int toCopy = Math.min(frames, wfMax - n);
                        for (int f = 0; f < toCopy; f++) {
                            wfBuf[n + f] = recorder.readSample(bytes, f * frameSize);
                        }
                        wfCollected.addAndGet(toCopy);
                    }
                }
                if (capturedCh1 != null) {
                    int frames = bytes.length / frameSize;
                    int n      = capPos.get();
                    int toCopy = Math.min(frames, capturedCh1.length - n);
                    for (int f = 0; f < toCopy; f++) {
                        long code = recorder.readSample(bytes, f * frameSize + sampleBytes) & 0xFFFFFFFFL;
                        capturedCh1[n + f] = (float) ((code - halfRange) / (double) halfRange);
                    }
                    capPos.addAndGet(toCopy);
                }
            });
            recorder.open();
            recorder.startRecording();
            Thread.sleep(durationSeconds * 1000L);
            recorder.stopRecording();
        }

        if (wfBuf != null) {
            new WaveformExporter().export(wfBuf, sampleRate, bitDepth, scaleVolts, waveformDurationMs, chartWidth, chartHeight, ".");
        }

        if (adcCompPath != null) {
            int actual = capPos.get();
            if (actual < fftSize) {
                log.error("Captured {} samples — fewer than --fft-size {}; cannot apply ADC correction.",
                        actual, fftSize);
                return;
            }
            float[] samples = actual < capturedCh1.length
                    ? Arrays.copyOf(capturedCh1, actual)
                    : capturedCh1;

            log.info("ADC correction: loading coefficients from {}", adcCompPath);
            AdcCorrection adc = AdcCorrectionHelper.loadCsv(adcCompPath);
            log.info("ADC correction: running FFT (size={}, harmonics={}) to extract fundamental phase/level",
                    fftSize, harmonics);
            FftAnalyzer fftAnalyzer = new FftAnalyzer();
            FftResult r = fftAnalyzer.analyze(samples, sampleRate, fftSize, harmonics,
                    WindowType.HANN, FftOverlap.PCT_0,
                    0.0, 0.0, true, Double.NaN);
            AdcCorrectionHelper.logCorrectedHarmonics(r, adc);
            AdcCorrectionHelper.subtractDistortionPerFrameInPlace(samples, sampleRate, fftSize, r, adc);

            String   baseName = outFile.getName().replaceFirst("\\.wav$", "");
            File corrFile = new File(outFile.getParentFile(), baseName + "_adc_corrected.wav");
            byte[] pcm = PcmUtils.floatMonoToStereoBytes(samples, bitDepth);
            try (WavWriter cw = new WavWriter(corrFile, sampleRate, 2, bitDepth, false)) {
                cw.writeRaw(pcm, pcm.length);
            }
            log.info("ADC-corrected WAV written: {}", corrFile.getAbsolutePath());
        }
    }

    private void recordMappedWav(DeviceRef device, int sampleRate, int durationSeconds, int bitDepth,
                                 float scaleVolts, WeightedBuffer weights, String outputPath,
                                 double waveformDurationMs, int chartWidth, int chartHeight) throws Exception {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File outFile = outputPath != null
                ? new File(outputPath)
                : new File("recording_mapped_" + ts + ".wav");

        final int   wfMax      = waveformDurationMs > 0 ? (int) Math.ceil(waveformDurationMs * sampleRate / 1000.0) : 0;
        final int[] wfBuf      = wfMax > 0 ? new int[wfMax] : null;
        final AtomicInteger wfCollected = new AtomicInteger(0);
        final int           skipFrames  = sampleRate / 100;
        final AtomicInteger skipped     = new AtomicInteger(0);

        try (WavWriter wav = new WavWriter(outFile, sampleRate, 2, bitDepth + 8, false);
             AudioCapture recorder = AudioBackend.instance().openCapture(device, sampleRate, bitDepth)) {
            recorder.setSampleListener(samples -> {
                if (skipped.get() < skipFrames) {
                    skipped.addAndGet(samples.length);
                    return;
                }
                StereoSampleFloat[] mapped = new StereoSampleFloat[samples.length];
                for (int i = 0; i < samples.length; i++) {
                    mapped[i] = weights.correctedCode(samples[i]);
                }
                try {
                    wav.writeSamples(mapped, bitDepth);
                } catch (IOException e) {
                    log.error("WAV write error", e);
                }
                if (wfBuf != null) {
                    int n = wfCollected.get();
                    if (n < wfMax) {
                        int   toCopy = Math.min(mapped.length, wfMax - n);
                        int[] ch1Buf = new int[mapped.length];
                        for (int ch1BufPos = 0; ch1BufPos < mapped.length; ch1BufPos++) {
                            ch1Buf[ch1BufPos] = (int) Math.round(mapped[ch1BufPos].ch1);
                        }
                        System.arraycopy(ch1Buf, 0, wfBuf, n, toCopy);
                        wfCollected.addAndGet(toCopy);
                    }
                }
            });
            recorder.open();
            recorder.startRecording();
            Thread.sleep(durationSeconds * 1000L);
            recorder.stopRecording();
        }

        if (wfBuf != null) {
            new WaveformExporter().export(wfBuf, sampleRate, bitDepth, scaleVolts, waveformDurationMs, chartWidth, chartHeight, ".");
        }
    }
}
