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
import org.edgo.audio.measure.adc.WeightedBuffer;
import org.edgo.audio.measure.chart.WaveformExporter;
import org.edgo.audio.measure.common.StereoSampleFloat;
import org.edgo.audio.measure.wav.WavReader;
import org.edgo.audio.measure.wav.WavWriter;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code --process-wav <file>} — applies a code-weight INL map to an existing
 * WAV without recapturing.
 *
 * <p>Reads the input WAV, runs every sample through the {@code --load-weighted}
 * weighted-code-map CSV (produced by {@code --analyze-histogram}), and writes
 * a linearised WAV out one bit-depth wider (so the linearised codes fit
 * without quantisation loss).  Equivalent to {@code --record-mapped-wav}
 * applied offline.  Optionally renders a waveform PNG of the leading
 * {@code --waveform-duration} milliseconds.
 *
 * <p>Required: {@code --load-weighted}.
 */
@Log4j2
@UtilityClass
public class ProcessWavMode {

    public void run(String[] args) throws Exception {
        String processWavArg       = ArgParser.getArgValue(args, "--process-wav");
        String loadWeightedArg     = ArgParser.getArgValue(args, "--load-weighted");
        String outputArg           = ArgParser.getArgValue(args, "--output");
        String waveformDurationArg = ArgParser.getArgValue(args, "--waveform-duration");
        String scaleArg            = ArgParser.getArgValue(args, "--scale");
        String widthArg            = ArgParser.getArgValue(args, "--width");
        String heightArg           = ArgParser.getArgValue(args, "--height");

        if (loadWeightedArg == null) {
            log.error("--load-weighted <csv> is required with --process-wav.");
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

        WavReader reader = new WavReader(processWavArg);
        log.info("Mode      : process WAV → mapped WAV");
        log.info("Input     : {}", processWavArg);
        log.info("Weights   : {}", loadWeightedArg);
        if (waveformDurationMs > 0) {
            log.info("Waveform  : {} ms", waveformDurationMs);
        }

        WeightedBuffer weights = new WeightedBuffer(reader.getBitsPerSample());
        weights.loadCsv(loadWeightedArg);
        weights.buildCodeMap();
        processWav(reader, scaleVolts, weights, outputArg, waveformDurationMs, chartWidth, chartHeight);
    }

    private void processWav(WavReader reader, float scaleVolts, WeightedBuffer weights,
                            String outputPath, double waveformDurationMs,
                            int chartWidth, int chartHeight) throws Exception {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File outFile = outputPath != null
                ? new File(outputPath)
                : new File("processed_" + ts + ".wav");

        int   bitDepth = reader.getBitsPerSample();
        int   wfMax    = waveformDurationMs > 0 ? (int) Math.ceil(waveformDurationMs * reader.getSampleRate() / 1000.0) : 0;
        int[] wfBuf    = wfMax > 0 ? new int[wfMax] : null;
        final AtomicInteger wfCollected = new AtomicInteger(0);

        try (WavWriter wav = new WavWriter(outFile, reader.getSampleRate(), reader.getChannels(), bitDepth + 8, false)) {
            reader.process(samples -> {
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
                        int toCopy = Math.min(mapped.length, wfMax - n);
                        for (int i = 0; i < toCopy; i++) {
                            wfBuf[n + i] = (int) mapped[i].ch0;
                        }
                        wfCollected.addAndGet(toCopy);
                    }
                }
            });
        }

        if (wfBuf != null) {
            new WaveformExporter().export(wfBuf, reader.getSampleRate(), bitDepth, scaleVolts, waveformDurationMs, chartWidth, chartHeight, ".");
        }
    }
}
