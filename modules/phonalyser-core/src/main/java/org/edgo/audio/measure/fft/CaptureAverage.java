/*
 * Phonalyser - precision audio measurement workbench.
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

package org.edgo.audio.measure.fft;

import java.util.Locale;

import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.WindowType;

import lombok.extern.log4j.Log4j2;

/**
 * The average over the frames of one capture held whole - a recording read
 * from file, a generated signal: the frames at the overlap's spacing, the
 * first one analysed ({@link FftAnalyzer#analyze}), every one accumulated
 * ({@link CoherentAccumulator}), and the result carrying the average with
 * the statistics of the average.  The offline counterpart of the live
 * analyser worker, which feeds the same accumulator one frame per analysis
 * cycle.
 *
 * <p>The analysed frame fixes what the accumulator is pinned to - the
 * fundamental's fractional bin, the second tone's, the product grid - and
 * the accumulator re-pins the fundamental from the phase slope over the
 * frames it is fed, so the result's refined fundamental and its harmonic
 * ladder are re-placed on the re-pinned value before the statistics.
 */
@Log4j2
public final class CaptureAverage {

    private final FftAnalyzer         analyzer;
    private final CoherentAccumulator accumulator;
    /** The transform of each frame beyond the analysed one, full length. */
    private double[] frameRe = new double[0];
    private double[] frameIm = new double[0];

    public CaptureAverage(FftAnalyzer analyzer, CoherentAccumulator accumulator) {
        this.analyzer    = analyzer;
        this.accumulator = accumulator;
    }

    /**
     * The averaged analysis of {@code samples}, the arguments those of
     * {@link FftAnalyzer#analyze(double[], int, int, int, WindowType,
     * FftOverlap, double, double, boolean, double, boolean, double)}.  A
     * capture holding one frame is that frame's analysis as it stands.
     */
    public FftResult analyze(double[] samples, int sampleRate,
                             int fftSize, int harmonicCount,
                             WindowType windowType, FftOverlap overlap,
                             double snrFreqMin, double snrFreqMax,
                             boolean coherent, double fundRefDbFs,
                             boolean logSummary, double expectedFundHz) {
        int step       = overlap.hopSamples(fftSize);
        int frameCount = samples.length >= fftSize ? (samples.length - fftSize) / step + 1 : 0;
        FftResult r = analyzer.analyze(samples, sampleRate, fftSize, harmonicCount,
                windowType, overlap, snrFreqMin, snrFreqMax, coherent, fundRefDbFs,
                logSummary && frameCount <= 1, expectedFundHz);
        if (frameCount <= 1) return r;

        double kappa  = r.freqResolution > 0.0 ? r.fundamentalHzRefined / r.freqResolution : 0.0;
        double kappa2 = r.fundamental2HzRefined > 0.0 && r.freqResolution > 0.0
                ? r.fundamental2HzRefined / r.freqResolution : Double.NaN;
        accumulator.restart(fftSize, coherent, kappa, kappa2, r.imdProductA, r.imdProductB,
                analyzer.mainLobeBins(windowType), r.pilot1Bin, r.pilot2Bin);
        // Each line's band top - the level a correction must clear - is read
        // from the line's local band; a capture resets no line, so it is the
        // band top over the averaged spectrum.
        accumulator.lineBands(r.freqResolution, r.windowNenbwBins);
        // The analysed frame is the reference the rest is turned back to; the
        // result holds its transform (the magnitude alone when incoherent).
        addFrame(r.re, r.im, 0.0, coherent);
        if (frameRe.length < fftSize) {
            frameRe = new double[fftSize];
            frameIm = new double[fftSize];
        }
        for (int f = 1; f < frameCount; f++) {
            int base = f * step;
            analyzer.frameSpectrum(samples, base, fftSize, windowType, frameRe, frameIm);
            addFrame(frameRe, frameIm, base, coherent);
        }
        if (coherent && accumulator.getKappa() > 0.0 && accumulator.getKappa() != kappa) {
            r.fundamentalHzRefined = accumulator.getKappa() * r.freqResolution;
            r.placeHarmonics();
        }
        accumulator.overlayOnto(r);
        r.captureRawPeaks();
        analyzer.recomputeStats(r);
        if (log.isInfoEnabled()) {
            log.info("Capture average: {} frames at step {} samples, fundamental {} Hz",
                    frameCount, step, String.format(Locale.US, "%.6f", r.fundamentalHzRefined));
        }
        if (logSummary) analyzer.logSummary(r);
        return r;
    }

    private void addFrame(double[] re, double[] im, double delta, boolean coherent) {
        if (coherent) {
            accumulator.add(re, im, 1.0, CoherentAccumulator.FrameTurns.of(delta), null, null);
        } else {
            accumulator.addPower(re, im, 1.0, null);
        }
    }
}
