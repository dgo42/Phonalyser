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

package org.edgo.audio.measure.gui.fft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.edgo.audio.measure.common.SpectrumStatistics;
import org.edgo.audio.measure.dsp.FreqRespCalHelper;
import org.edgo.audio.measure.dsp.FreqRespCalibration;
import org.edgo.audio.measure.dsp.InverseCascade;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.fft.ReadoutSettings;
import org.edgo.audio.measure.fft.SpectrumAccumulator;
import org.edgo.audio.measure.test.AbstractReflectiveTest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * One capture averaged both ways, stage by stage, on the notch bench's
 * geometry: the power average the coherent run keeps against the incoherent
 * spectrum before any calibration, N on each without calibration (the noise
 * mask alone), the spectra after the bench's two tone-only rows, and N on
 * each with them (mask and lobe stretch) - for the full band and for the
 * 860 - 9500 Hz band.  The power averages must agree to rounding - the two
 * are collected the same way - and so must N, read from that power average
 * through a mask found on it; the lobe stretch may part the spectra around
 * the tone, inside the mask.  Every stage is logged (logger
 * CoherentNoiseBasis).
 *
 * <p>Both averages are collected the way the live FFT collects them: one
 * frame per analysis cycle, analysed as the worker analyses it and added by
 * the worker's own accumulate step, then written back through the worker's
 * accumulator, the statistics calculated with the correction and the
 * correction applied - the display path.  The accumulate step and the
 * accumulator are private, so they are reached by reflection.
 *
 * <p>Geometry as the bench runs it: 384 kHz, 2 M points, BH7, 93.75 %
 * overlap, the seed frame plus 100 averages.  The calibration is the bench's
 * own pair of rows, read from {@code results/}; the case skips where they are
 * missing.  The signal is modelled on the bench's: the tone at 1004.3336 Hz
 * notched to -88.5 dBFS, a white floor near -169 dBFS per bin, a floor
 * rising towards 100 kHz, the mains lines below 500 Hz, and a slow
 * frequency wander.
 */
@Tag("exploratory")   // 2 M-point transforms and the bench's calibration files
class CoherentNoiseBasisTest extends AbstractReflectiveTest {

    private static final int        SAMPLE_RATE = 384_000;
    private static final int        FFT_SIZE    = 2_097_152;
    private static final WindowType WINDOW      = WindowType.BH7;
    private static final FftOverlap OVERLAP     = FftOverlap.PCT_93_75;
    /** The seed frame plus the 100 averages the bench stops after. */
    private static final int        FRAMES      = 101;
    /** H2 .. H9. */
    private static final int        HARMONICS   = 8;
    private static final double     TONE_HZ     = 1_004.3336;
    /** The notched tone as the ADC reads it. */
    private static final double     TONE_DBFS   = -88.53;
    /** White noise for a floor near -169 dBFS per bin at 2 M points (BH7). */
    private static final double     NOISE_STD   = 1.6e-6;
    /** The rising floor: the second difference of its own white noise,
     *  scaled to stand about 10 dB over the white floor at 100 kHz. */
    private static final double     HF_SCALE    = 1.46 * NOISE_STD;
    /** The mains lines the bench shows, Hz and dBFS. */
    private static final double[]   MAINS_HZ    = { 50.0, 100.0, 150.0, 200.0, 250.0, 400.0 };
    private static final double[]   MAINS_DBFS  = { -129.0, -129.0, -136.0, -140.0, -144.0, -149.0 };
    /** A slow frequency wander: +-0.2 Hz at 0.05 Hz over the 40 s capture. */
    private static final double     WANDER_HZ    = 0.05;
    private static final double     WANDER_INDEX = 4.0;
    private static final long       SEED         = 13L;
    /** The bench's two tone-only rows, active on the left channel. */
    private static final Path[]     ROWS = {
            Path.of("../../results/loopback 384k log before 2.7 ADC.frc"),
            Path.of("../../results/1kHz notch clear 384k log buffer combined 2.7 ADC.frc") };
    /** The two bands the bench was read in: full, and 860 - 9500 Hz. */
    private static final double[][] BANDS = { { 0.0, 0.0 }, { 860.0, 9_500.0 } };
    /** Rounding only: the two averages sum the same powers. */
    private static final double IDENTICAL_DB = 1e-9;
    /** A difference worth listing in the log. */
    private static final double LISTED_DB    = 0.01;
    /** N of the two modes: the same power average under the same mask. */
    private static final double N_AGREE_DB   = 0.01;
    private static final double FLOOR_DB     = -290.0;

    /** The worker members the collection drives: the private accumulate step
     *  and the private accumulator its average is written back from. */
    private static final String ACCUMULATE  = "accumulateIntoForeverBuffer";
    private static final String ACCUMULATOR = "accumulator";

    private static final Logger LOG = LogManager.getLogger("CoherentNoiseBasis");

    /** The statistics every stage of each run is read with, one per run: each
     *  keeps what it lifted its run's trace with, and takes that off again
     *  before it calculates the next stage. */
    private final SpectrumStatistics cohStatistics = new SpectrumStatistics();
    private final SpectrumStatistics incStatistics = new SpectrumStatistics();

    /** The power averages of the two runs agree before any calibration, and
     *  N agrees on both with and without the bench's tone-only rows.
     *
     * @throws Exception when a calibration file cannot be read */
    @Test
    void theTwoAveragesOfOneCapturePartOnlyAfterTheirCollection() throws Exception {
        for (Path row : ROWS) {
            Assumptions.assumeTrue(Files.exists(row), "bench calibration missing: " + row);
        }
        List<FreqRespCalibration> rows = List.of(
                FreqRespCalHelper.loadFrc(ROWS[0].toString()).left(),
                FreqRespCalHelper.loadFrc(ROWS[1].toString()).left());
        double[] signal = capture();
        FftResult coh = average(signal, true);
        FftResult inc = average(signal, false);
        int toneBin = coh.fundamentalBin;

        // 1. As collected - before any calibration.
        assertTrue(coh.hasPowerAverage, "the coherent average hands its power average over");
        Diff collected = diff(coh.powerAverageDbFs, inc.amplitudeDbFs);
        LOG.info(String.format(Locale.US, "collected: %d frames each; power average vs incoherent "
                + "spectrum, largest difference %.3e dB at bin %d", coh.frameCount, collected.maxDb,
                collected.bin));
        assertEquals(0.0, collected.maxDb, IDENTICAL_DB,
                "the coherent run's power average is the incoherent average of the same frames");
        logNoise("no calibration (the mask alone)", coh, null, inc, null);

        // 2. With the bench's tone-only rows - the stretch takes its exponents
        //    from each run's own displayed spectrum.  The statistics read the
        //    correction's gains on the uncorrected spectrum and apply the
        //    correction to the trace as they end, as the view's pipeline does.
        InverseCascade cohInv = toneOnlyInverse(coh, rows);
        InverseCascade incInv = toneOnlyInverse(inc, rows);
        logNoise("tone-only rows applied (mask and stretch)", coh, cohInv, inc, incInv);
        Diff restored = diff(coh.powerAverageDbFs, inc.amplitudeDbFs);
        LOG.info(String.format(Locale.US, "tone-only rows applied: largest difference %.3f dB at bin %d "
                        + "(%d bins from the tone), %d bins differ by more than %.2f dB",
                restored.maxDb, restored.bin, restored.bin - toneBin, restored.over, LISTED_DB));
    }

    /** N of both runs in each band, logged with their difference, coherent
     *  minus incoherent; the two must agree - both read the same power
     *  average through a mask found on it.
     *
     * @param stage  the stage's name in the log
     * @param coh    the coherent run's average
     * @param cohInv the correction of the coherent run; null for none
     * @param inc    the incoherent run's average
     * @param incInv the correction of the incoherent run; null for none */
    private void logNoise(String stage, FftResult coh, InverseCascade cohInv,
                          FftResult inc, InverseCascade incInv) {
        for (double[] band : BANDS) {
            double nCoh = bandNoiseDb(cohStatistics, coh, cohInv, band);
            double nInc = bandNoiseDb(incStatistics, inc, incInv, band);
            String bandName = band[1] > 0.0
                    ? String.format(Locale.US, "%.0f-%.0f Hz", band[0], band[1]) : "full";
            LOG.info(String.format(Locale.US, "N, %s, band %s: coherent %.4f dBFS, incoherent %.4f dBFS, "
                    + "difference %+.4f dB", stage, bandName, nCoh, nInc, nCoh - nInc));
            assertEquals(nInc, nCoh, N_AGREE_DB, "N, " + stage + ", band " + bandName);
        }
    }

    /** N of {@code r} calculated in {@code band}, the lifts the run's last
     *  stage put on the trace taken off first.
     *
     * @param statistics the run's statistics
     * @param r          the average
     * @param inv        the correction the statistics de-embed with; null for none
     * @param band       the band's lower and upper edge, Hz; 0 for no edge
     * @return N, dBFS */
    private double bandNoiseDb(SpectrumStatistics statistics, FftResult r, InverseCascade inv,
                               double[] band) {
        statistics.recalculateStat(r,
                new ReadoutSettings(HARMONICS, HARMONICS, band[0], band[1], Double.NaN), inv);
        return 10.0 * Math.log10(r.noisePower);
    }

    /** The capture: the notched tone with its wander, the mains lines, the
     *  white and the rising floor.
     *
     * @return the signal */
    private double[] capture() {
        int hop = OVERLAP.hopSamples(FFT_SIZE);
        double[] s = new double[FFT_SIZE + (FRAMES - 1) * hop];
        Random noise = new Random(SEED);
        double toneAmplitude = Math.pow(10.0, TONE_DBFS / 20.0);
        double[] mainsAmplitude = new double[MAINS_HZ.length];
        for (int m = 0; m < MAINS_HZ.length; m++) {
            mainsAmplitude[m] = Math.pow(10.0, MAINS_DBFS[m] / 20.0);
        }
        double hf1 = 0.0;
        double hf2 = 0.0;
        for (int n = 0; n < s.length; n++) {
            double t = n / (double) SAMPLE_RATE;
            double phase = 2.0 * Math.PI * TONE_HZ * t + WANDER_INDEX * Math.sin(2.0 * Math.PI * WANDER_HZ * t);
            double v = toneAmplitude * Math.sin(phase) + NOISE_STD * noise.nextGaussian();
            for (int m = 0; m < MAINS_HZ.length; m++) {
                v += mainsAmplitude[m] * Math.sin(2.0 * Math.PI * MAINS_HZ[m] * t);
            }
            double hf0 = noise.nextGaussian();
            v += HF_SCALE * (hf0 - 2.0 * hf1 + hf2);
            hf2 = hf1;
            hf1 = hf0;
            s[n] = v;
        }
        return s;
    }

    /** The average of {@code signal} as the live FFT collects it: every frame
     *  one analysis cycle - analysed with the worker's own settings and added
     *  by the worker's accumulate step at its sample offset - then the
     *  accumulator written back onto the last cycle's result.
     *
     * @param signal   the capture
     * @param coherent whether the frames are averaged coherently
     * @return the average */
    private FftResult average(double[] signal, boolean coherent) {
        FftAnalyzer       analyzer = new FftAnalyzer();
        FftAnalyzerWorker worker   = new FftAnalyzerWorker(null);   // headless
        ReadoutSettings   settings = new ReadoutSettings(HARMONICS, HARMONICS, 0.0, 0.0, Double.NaN);
        int       hop = OVERLAP.hopSamples(FFT_SIZE);
        double[]  win = new double[FFT_SIZE];
        FftResult r   = null;
        for (int f = 0; f < FRAMES; f++) {
            long base = (long) f * hop;
            System.arraycopy(signal, (int) base, win, 0, FFT_SIZE);
            analyzer.setMultiTone(false);
            analyzer.setSecondToneHintHz(Double.NaN);
            r = analyzer.analyze(win, SAMPLE_RATE, FFT_SIZE, settings, WINDOW, OVERLAP,
                    coherent, TONE_HZ, new FftResult(), hop);
            call(worker, ACCUMULATE, r, base, coherent, Integer.MAX_VALUE);
        }
        this.<SpectrumAccumulator>field(worker, ACCUMULATOR).overlayOnto(r);
        r.captureRawPeaks();
        return r;
    }

    /** The view's correction of a frame by tone-only rows: one value per tone
     *  of the frame.
     *
     * @param r    the frame
     * @param rows the tone-only calibrations
     * @return the cascade */
    private InverseCascade toneOnlyInverse(FftResult r, List<FreqRespCalibration> rows) {
        return FreqRespCalHelper.cascadeInverse(List.of(), rows,
                FreqRespCalHelper.toneFrequencies(r), r.fftSize, r.sampleRate);
    }

    /** The largest per-bin difference of two dBFS spectra, where it stands,
     *  and how many bins differ by more than {@link #LISTED_DB}; bins both
     *  spectra hold at the floor are skipped.
     *
     * @param a the first spectrum, dBFS
     * @param b the second spectrum, dBFS
     * @return the comparison's outcome */
    private Diff diff(double[] a, double[] b) {
        Diff d = new Diff();
        for (int k = 1; k < a.length - 1; k++) {
            if (a[k] <= FLOOR_DB && b[k] <= FLOOR_DB) continue;
            double delta = Math.abs(a[k] - b[k]);
            if (delta > LISTED_DB) d.over++;
            if (delta > d.maxDb) {
                d.maxDb = delta;
                d.bin   = k;
            }
        }
        return d;
    }

    /** A per-bin comparison's outcome. */
    private static final class Diff {
        double maxDb;
        int    bin;
        int    over;
    }
}
