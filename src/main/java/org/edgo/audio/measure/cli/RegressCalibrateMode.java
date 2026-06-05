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
import org.edgo.audio.measure.adc.RegressionCalibrator;
import org.edgo.audio.measure.wav.WavReader;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code --regress-calibrate <wav>} — sine-wave least-squares INL fit.
 *
 * <p>Alternative to the histogram-based INL workflow: takes a clean recorded
 * sine, runs a per-code regression-calibration solver ({@link
 * RegressionCalibrator}), and writes both a regression-calibration CSV and
 * a regression chart PNG.  Useful when the stimulus is known to be a clean
 * sine of arbitrary amplitude — the regression jointly recovers
 * amplitude/phase and the per-code INL curve.
 *
 * <p>Required: {@code --scale} (full-scale voltage range).
 */
@Log4j2
@UtilityClass
public class RegressCalibrateMode {

    public void run(String[] args) throws Exception {
        String fileArg   = ArgParser.getArgValue(args, "--regress-calibrate");
        String scaleArg  = ArgParser.getArgValue(args, "--scale");
        String widthArg  = ArgParser.getArgValue(args, "--width");
        String heightArg = ArgParser.getArgValue(args, "--height");

        if (scaleArg == null) {
            log.error("--scale is required for --regress-calibrate (full-scale voltage range, e.g. 2.0 for ±1 V).");
            System.exit(1);
        }
        double scaleVolts = Double.parseDouble(scaleArg);
        int chartWidth    = widthArg  != null ? Integer.parseInt(widthArg)  : 1920;
        int chartHeight   = heightArg != null ? Integer.parseInt(heightArg) : 600;

        WavReader reader      = new WavReader(fileArg);
        int       sampleRate  = reader.getSampleRate();
        int       bitDepth    = reader.getBitsPerSample();
        long      totalFrames = reader.getFrameCount();

        log.info("Mode      : Regression calibration");
        log.info("File      : {}", fileArg);
        log.info("SampleRate: {} Hz", sampleRate);
        log.info("Bits      : {}", bitDepth);
        log.info("Samples   : {}", totalFrames);
        log.info("Scale     : {} V", scaleVolts);
        log.info("Chart     : {}x{} px", chartWidth, chartHeight);

        float[] samples   = new float[(int) Math.min(totalFrames, Integer.MAX_VALUE)];
        long    halfRange = 1L << (bitDepth - 1);
        AtomicInteger pos = new AtomicInteger(0);

        reader.process(block -> {
            int n = pos.get();
            for (int i = 0; i < block.length && n + i < samples.length; i++) {
                int signed = block[i].ch1 - (int) halfRange;
                samples[n + i] = (float) (signed / (double) halfRange);
            }
            pos.addAndGet(block.length);
        });

        int actualSamples = pos.get();
        float[] trimmed = actualSamples < samples.length
                ? Arrays.copyOf(samples, actualSamples)
                : samples;

        RegressionCalibrator calibrator = new RegressionCalibrator();
        RegressionCalibrator.Result result = calibrator.calibrate(trimmed, sampleRate, bitDepth);
        calibrator.exportCsv(result, scaleVolts, "results");
        calibrator.exportChart(result, scaleVolts, chartWidth, chartHeight, "results");
    }
}
