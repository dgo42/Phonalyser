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

import java.util.Random;

import org.edgo.audio.measure.common.SpectrumStatistics;
import org.edgo.audio.measure.dsp.FftDsp;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.WindowType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harmonic-detection and THD computation tests for {@link FftAnalyzer} and
 * {@link SpectrumStatistics}.  Builds synthetic signals with known harmonic
 * content, analyses one frame and calculates its statistics, and verifies
 * the right harmonic bins and a sensible THD percentage.
 *
 * <p>This expands the safety net around the harmonic-gather,
 * harmonic-power, and THD-ratio code paths.
 */
class FftAnalyzerThdTest {

    /** The readout settings most cases run with: H2..H9 evaluated and
     *  summed, no distortion band, no manual fundamental. */
    private static final ReadoutSettings SETTINGS = new ReadoutSettings(8, 8, 0.0, 0.0, Double.NaN);

    /** RMS of the white noise the band-top clamp cases add, full scale 1:
     *  about -111 dBFS per bin at 8192 points with the Hann window. */
    private static final double CLAMP_TEST_NOISE_RMS = 1e-4;
    /** Seed of that noise, so every run reads the same spectrum. */
    private static final long CLAMP_TEST_NOISE_SEED = 20_261_020L;
    /** Amplitude of a line buried in that noise, full scale 1: -160 dBFS,
     *  some 50 dB under the noise bins around it. */
    private static final double CLAMP_TEST_BURIED_AMPLITUDE = 1e-8;
    /** How far (dB) a clear line stands above its band-top at least. */
    private static final double CLAMP_TEST_CLEARANCE_DB = 20.0;

    /** A clean sine has no harmonic content: THD reads below 0.01 % and
     *  -60 dB. */
    @Test
    void pureSine_thdIsNearZero() {
        // A clean sine has no harmonic content.  THD must be very low -
        // a "0.0001 %" type number, not the 0 % a textbook would
        // report, because numerical noise leaks into the harmonic bins.
        int sampleRate = 48_000;
        int fftSize    = 8_192;
        double freqHz  = (double) sampleRate / fftSize * 100;   // bin 100

        double[] signal = pureSine(sampleRate, freqHz, 0.5, fftSize * 2);

        FftResult r = analyzeWithStatistics(new FftAnalyzer(), signal, sampleRate, fftSize,
                SETTINGS, WindowType.HANN, FftOverlap.PCT_0, true, Double.NaN,
                FftOverlap.PCT_0.hopSamples(fftSize));

        assertTrue(r.thdPct < 0.01,
                "pure sine THD should be < 0.01 %, was " + r.thdPct);
        assertTrue(r.thdDb < -60.0,
                "pure sine THD-dB should be < -60 dB, was " + r.thdDb);
    }

    /** A 2nd harmonic at -40 dBc reads 1 % THD and sits at twice the
     *  fundamental's bin. */
    @Test
    void sineWith2ndHarmonic_thdMatchesAddedRatio() {
        // Add a 2nd harmonic at -40 dBc (= 1 % amplitude of the
        // fundamental).  THD should land near 1 % (10 mV / 1 V = 1 %).
        int sampleRate = 48_000;
        int fftSize    = 8_192;
        double freqHz  = (double) sampleRate / fftSize * 100;   // bin 100
        double h1Amp   = 0.5;
        double h2Amp   = h1Amp * 0.01;                          // -40 dBc

        double[] signal = new double[fftSize * 2];
        for (int n = 0; n < signal.length; n++) {
            signal[n] = (double) (
                    h1Amp * Math.sin(2.0 * Math.PI * freqHz * n / sampleRate)
                  + h2Amp * Math.sin(2.0 * Math.PI * 2 * freqHz * n / sampleRate));
        }

        FftResult r = analyzeWithStatistics(new FftAnalyzer(), signal, sampleRate, fftSize,
                SETTINGS, WindowType.HANN, FftOverlap.PCT_0, true, Double.NaN,
                FftOverlap.PCT_0.hopSamples(fftSize));

        // THD percent should be ~1 % (ratio of harmonics power to fundamental).
        assertEquals(1.0, r.thdPct, 0.05,
                "THD% should match the added -40 dBc 2nd-harmonic amplitude");
        // Second harmonic bin should be detected at 2× the fundamental bin.
        int expectedH2Bin = 2 * r.fundamentalBin;
        assertEquals(expectedH2Bin, r.harmonicBins[0],
                "first harmonic slot should hold the 2nd harmonic bin");
    }

    /** With H2, H3 and H5 present the ladder holds each at its multiple of
     *  the fundamental's bin. */
    @Test
    void sineWithMultipleHarmonics_correctBinsDetected() {
        // Fundamental + H2 + H3 + H5 (no H4) at varying levels.  The
        // analyser fills harmonicBins[0..7] with the bins of H2..H9.
        int sampleRate = 48_000;
        int fftSize    = 8_192;
        double freqHz  = (double) sampleRate / fftSize * 50;    // bin 50
        // amplitudes  H1=0.5  H2=0.05 (10 %)  H3=0.025 (5 %)  H5=0.01 (2 %)
        double[] amps = { 0.5, 0.05, 0.025, 0.0, 0.01 };
        int[]    mult = { 1,   2,    3,     4,   5    };

        double[] signal = new double[fftSize * 2];
        for (int n = 0; n < signal.length; n++) {
            double s = 0;
            for (int i = 0; i < amps.length; i++) {
                s += amps[i] * Math.sin(2.0 * Math.PI * mult[i] * freqHz * n / sampleRate);
            }
            signal[n] = (double) s;
        }

        FftResult r = new FftAnalyzer().analyze(
                signal, sampleRate, fftSize, SETTINGS,
                WindowType.HANN, FftOverlap.PCT_0, true, Double.NaN,
                new FftResult(), FftOverlap.PCT_0.hopSamples(fftSize));

        // harmonicBins[0]=H2, [1]=H3, [2]=H4, [3]=H5
        int fundBin = r.fundamentalBin;
        assertEquals(2 * fundBin, r.harmonicBins[0], "H2 bin");
        assertEquals(3 * fundBin, r.harmonicBins[1], "H3 bin");
        assertEquals(5 * fundBin, r.harmonicBins[3], "H5 bin");
    }

    /** THD sums only the harmonics up to the stated limit; the ladder keeps
     *  all of them and the result names the limit it summed. */
    @Test
    void thdSumsOnlyTheHarmonicsUpToTheStatedLimit() {
        // H2, H3 and H5 at 1 % each.  Summing all eight gives sqrt(3) %, up to
        // H3 gives sqrt(2) %, H2 alone gives 1 %; the harmonic table keeps all
        // of them whatever the limit, and the result says what it summed.
        int sampleRate = 48_000;
        int fftSize    = 8_192;
        double freqHz  = (double) sampleRate / fftSize * 50;    // bin 50
        double[] signal = new double[fftSize * 2];
        for (int n = 0; n < signal.length; n++) {
            double t = 2.0 * Math.PI * freqHz * n / sampleRate;
            signal[n] = 0.5 * Math.sin(t) + 0.005 * (Math.sin(2 * t) + Math.sin(3 * t) + Math.sin(5 * t));
        }
        int step = FftOverlap.PCT_0.hopSamples(fftSize);

        double[][] cases = { { 8, Math.sqrt(3.0) }, { 2, Math.sqrt(2.0) }, { 1, 1.0 } };
        for (double[] c : cases) {
            int limit = (int) c[0];
            FftResult r = analyzeWithStatistics(new FftAnalyzer(), signal, sampleRate, fftSize,
                    new ReadoutSettings(8, limit, 0.0, 0.0, Double.NaN),
                    WindowType.HANN, FftOverlap.PCT_0, true, Double.NaN, step);
            assertEquals(c[1], r.thdPct, 0.05, "THD over H2..H" + (limit + 1));
            assertEquals(limit, r.thdHarmonics, "the result names the harmonics it summed");
            assertEquals(5 * r.fundamentalBin, r.harmonicBins[3], "H5 stays in the table at limit " + limit);
        }
    }

    /** The frame carries the parameters it was analysed with, unmangled. */
    @Test
    void result_carriesInputParameters() {
        // Sanity bookkeeping: the analyser doesn't mangle the parameters
        // it was given on the way through.  Catches a subtle bug where
        // a refactor accidentally reorders constructor args.
        int sampleRate = 96_000;
        int fftSize    = 16_384;
        double[] signal = pureSine(sampleRate, 2_000.0, 0.4, fftSize * 2);

        FftResult r = new FftAnalyzer().analyze(
                signal, sampleRate, fftSize, new ReadoutSettings(6, 6, 10.0, 40_000.0, -3.0),
                WindowType.HANN, FftOverlap.PCT_50, false, Double.NaN,
                new FftResult(), FftOverlap.PCT_50.hopSamples(fftSize));

        assertEquals(sampleRate, r.sampleRate);
        assertEquals(fftSize,    r.fftSize);
        assertEquals(WindowType.HANN,    r.windowType);
        assertEquals(FftOverlap.PCT_50,  r.overlap);
        assertEquals(10.0,    r.snrFreqMin);
        assertEquals(40_000.0, r.snrFreqMax);
        assertEquals(-3.0,    r.fundamentalTrueDbFs);
        assertEquals(false,   r.coherentAveraging);
    }

    /** Two pilot lines, each on a whole bin, leave THD, SNR and ENOB where
     *  they were and sit on their own bins at their own level. */
    @Test
    void pilotTones_stayOutOfTheReadoutsAndSitOnTheirOwnBins() {
        // A pilot tone is a generated line the fundamental's phase alignment
        // reads as its time base; two of them run at once, each on a WHOLE bin
        // of the analysis grid.  They must leave the readouts where they were:
        // pinned on the analyser by their bins, each bin plus the window's main
        // lobe is kept out of the noise, SNR and ENOB sums, and standing clear
        // of the harmonic ladder THD never sees either of them.
        // Geometry: 48 kHz, 1 M points, the fundamental on bin 21911 (1003 Hz
        // snapped to the grid), the pilots on bins 339620 and 361531 - halfway
        // between the 15th and 16th harmonic of that fundamental, and between
        // the 16th and the 17th, so 10955 bins clear of every one of them.
        int sampleRate  = 48_000;
        int fftSize     = 1 << 20;
        int fundBin     = 21_911;
        int pilot1Bin   = 339_620;
        int pilot2Bin   = 361_531;
        double fundHz   = fundBin * (double) sampleRate / fftSize;
        double fundAmp  = 0.5;
        double pilotAmp = 1.0e-5;     // -100 dBV against a 1 V full scale
        // A deterministic noise floor ~70 dB below a pilot.  Being the same
        // draw in both runs it holds every noise bin at the same value, so the
        // comparison below sees the pilot lines alone and not the round-off of
        // two different sums.
        double noiseAmp = 1.0e-6;
        Random noise    = new Random(4242L);

        double[] signal = new double[fftSize * 2];
        for (int n = 0; n < signal.length; n++) {
            signal[n] = fundAmp * Math.sin(2.0 * Math.PI * fundHz * n / sampleRate)
                      + noiseAmp * noise.nextGaussian();
        }

        FftAnalyzer analyzer = new FftAnalyzer();
        analyzer.setPilotBins(-1, -1);             // no pilot on the reference run
        FftResult noPilot = analyzeWithStatistics(analyzer, signal, sampleRate, fftSize,
                SETTINGS, WindowType.BH7, FftOverlap.PCT_0, true, Double.NaN,
                FftOverlap.PCT_0.hopSamples(fftSize));
        double thdDb  = noPilot.thdDb;
        double snrDb  = noPilot.snrDb;
        double enobDb = noPilot.enobSinadDb;

        // The same signal plus both pilot lines, each on its own bin, as the
        // generator carries them.
        for (int n = 0; n < signal.length; n++) {
            signal[n] += pilotAmp * Math.sin(2.0 * Math.PI * pilot1Bin * n / fftSize)
                       + pilotAmp * Math.sin(2.0 * Math.PI * pilot2Bin * n / fftSize);
        }
        analyzer.setPilotBins(pilot1Bin, pilot2Bin);
        FftResult withPilots = analyzeWithStatistics(analyzer, signal, sampleRate, fftSize,
                SETTINGS, WindowType.BH7, FftOverlap.PCT_0, true, Double.NaN,
                FftOverlap.PCT_0.hopSamples(fftSize));

        assertEquals(thdDb, withPilots.thdDb, 0.01,
                "THD must not move when the two pilots are added");
        assertEquals(snrDb, withPilots.snrDb, 0.01,
                "SNR must not move when the two pilots are added");
        assertEquals(enobDb, withPilots.enobSinadDb, 0.01,
                "the ENOB basis must not move when the two pilots are added");

        assertEquals(pilot1Bin, withPilots.pilot1Bin,
                "the result carries the first pilot's bin as pinned");
        assertEquals(pilot2Bin, withPilots.pilot2Bin,
                "the result carries the second pilot's bin as pinned");
        assertEquals(pilot1Bin, withPilots.pilot1HzRefined / withPilots.freqResolution, 1e-6,
                "a pilot on a whole bin has that bin times the bin width for its frequency");
        assertEquals(pilot2Bin, withPilots.pilot2HzRefined / withPilots.freqResolution, 1e-6,
                "the second pilot's frequency is its own bin times the bin width");

        // Both lines have to come through the coherent sum at their own level,
        // or the three readings above would hold for the trivial reason that
        // nothing was there to disturb them.  A line on a whole bin is periodic
        // in the frame, so no window loss and no de-rotation residual is
        // expected at either of them.
        assertEquals(20.0 * Math.log10(pilotAmp),
                withPilots.amplitudeDbFs[pilot1Bin], 0.1,
                "the first pilot must survive the coherent sum at its own level");
        assertEquals(20.0 * Math.log10(pilotAmp),
                withPilots.amplitudeDbFs[pilot2Bin], 0.1,
                "the second pilot must survive the coherent sum at its own level");
    }

    /** Each pilot's stamped span is its bin widened by the window's main
     *  lobe and the leakage guard on either side. */
    @Test
    void pilotSpans_coverEachPilotBinPlusTheWindowsMainLobeAndGuard() {
        // The analyser is the one place the excluded width is decided, and each
        // pilot's span is stamped on the result so that every readout that
        // integrates bins - the noise, SNR and ENOB sums and the dual-tone TD+N
        // residual - leaves exactly the same bins out.  One span per pilot: the
        // bins its position straddles, which for a whole bin is that bin alone,
        // widened by the window's own main-lobe half-width plus the leakage
        // guard on either side.
        int sampleRate = 48_000;
        int fftSize    = 8_192;
        int pilot1Bin  = 1_500;
        int pilot2Bin  = 2_600;
        double fundHz  = (double) sampleRate / fftSize * 100;   // bin 100
        double fundAmp  = 0.5;
        double pilotAmp = 1.0e-5;

        double[] signal = new double[fftSize * 2];
        for (int n = 0; n < signal.length; n++) {
            signal[n] = fundAmp * Math.sin(2.0 * Math.PI * fundHz * n / sampleRate)
                      + pilotAmp * Math.sin(2.0 * Math.PI * pilot1Bin * n / fftSize)
                      + pilotAmp * Math.sin(2.0 * Math.PI * pilot2Bin * n / fftSize);
        }

        FftAnalyzer analyzer = new FftAnalyzer();
        analyzer.setPilotBins(pilot1Bin, pilot2Bin);
        FftResult r = analyzer.analyze(signal, sampleRate, fftSize, SETTINGS,
                WindowType.BH7, FftOverlap.PCT_0, true, Double.NaN,
                new FftResult(), FftOverlap.PCT_0.hopSamples(fftSize));

        int half = FftDsp.mainLobeBins(WindowType.BH7) + FftDsp.LEAKAGE_GUARD_BINS;
        assertEquals(pilot1Bin - half, r.pilot1MaskLoBin,
                "the first pilot's span starts a main lobe and the guard below its bin");
        assertEquals(pilot1Bin + half, r.pilot1MaskHiBin,
                "the first pilot's span ends a main lobe and the guard above its bin");
        assertEquals(pilot2Bin - half, r.pilot2MaskLoBin,
                "the second pilot's span starts a main lobe and the guard below its bin");
        assertEquals(pilot2Bin + half, r.pilot2MaskHiBin,
                "the second pilot's span ends a main lobe and the guard above its bin");
    }

    /** A harmonic buried in the noise reads its band-top - the top of the
     *  local noise band the statistics measure beside it - one far above
     *  its band-top reads as measured, and THD sums the clamped levels of
     *  every harmonic up to the THD order, the absent ones at their
     *  band-tops included. */
    @Test
    void lineFloorClamp_harmonicUnderItsFloorReadsTheFloorAndThdSumsIt() {
        int sampleRate = 48_000;
        int fftSize    = 8_192;
        double binHz   = (double) sampleRate / fftSize;
        int n = fftSize * 2;
        double[] signal = noisySine(sampleRate, 100 * binHz, 0.5, n);
        double[] h2 = pureSine(sampleRate, 200 * binHz, CLAMP_TEST_BURIED_AMPLITUDE, n);
        double[] h3 = pureSine(sampleRate, 300 * binHz, 1e-3, n);   // far above the noise
        for (int i = 0; i < n; i++) signal[i] += h2[i] + h3[i];

        FftResult r = analyzeWithStatistics(new FftAnalyzer(), signal, sampleRate, fftSize,
                SETTINGS, WindowType.HANN, FftOverlap.PCT_0, true, Double.NaN,
                FftOverlap.PCT_0.hopSamples(fftSize));

        SpectralLine buried = r.harmonics[0];
        SpectralLine clear  = r.harmonics[1];
        assertTrue(Double.isFinite(buried.bandTopDbFs()), "H2's band-top is measured");
        assertEquals(buried.bandTopDbFs(), buried.rawAmplitudeDbFs(), 1e-9, "H2 is clamped at its band-top");
        assertTrue(clear.rawAmplitudeDbFs() > clear.bandTopDbFs() + CLAMP_TEST_CLEARANCE_DB,
                "H3 reads as measured, far above its band-top");
        double fundLin = Math.pow(10.0, r.fundamentalDbFs / 20.0);
        double powerSum = 0.0;
        for (int h = 0; h < Math.min(r.harmonics.length, r.thdHarmonics); h++) {
            if (r.harmonicBins[h] <= 0) continue;
            double a = Math.pow(10.0, r.harmonics[h].rawAmplitudeDbFs() / 20.0);
            powerSum += a * a;
        }
        double expected = Math.sqrt(powerSum) / fundLin * 100.0;
        assertEquals(expected, r.thdPct, expected * 1e-6,
                "THD sums the clamped H2, the measured H3 and every other harmonic's clamped level");
    }

    /** A dual tone's 2f1 - f2 buried in the noise reads its band-top. */
    @Test
    void lineFloorClamp_intermodProductUnderItsFloorReadsTheFloor() {
        int sampleRate = 48_000;
        int fftSize    = 8_192;
        double binHz   = (double) sampleRate / fftSize;
        int n = fftSize * 2;
        double[] signal = noisySine(sampleRate, 100 * binHz, 0.25, n);
        double[] f2     = pureSine(sampleRate, 130 * binHz, 0.25, n);
        double[] d3     = pureSine(sampleRate,  70 * binHz, CLAMP_TEST_BURIED_AMPLITUDE, n);   // 2f1 - f2
        for (int i = 0; i < n; i++) signal[i] += f2[i] + d3[i];

        // Equal tones: the fundamental is seeded with F1, so the second-tone
        // hint finds F2 and not the fundamental itself.
        FftAnalyzer analyzer = new FftAnalyzer();
        analyzer.setMultiTone(true);
        analyzer.setSecondToneHintHz(130 * binHz);
        FftResult r = analyzeWithStatistics(analyzer, signal, sampleRate, fftSize,
                SETTINGS, WindowType.HANN, FftOverlap.PCT_0, true, 100 * binHz,
                FftOverlap.PCT_0.hopSamples(fftSize));

        assertTrue(r.imd != null, "the pair is analysed as a dual tone");
        SpectralLine buried = r.imd.dnL[3];
        assertTrue(Double.isFinite(buried.bandTopDbFs()), "2f1 - f2's band-top is measured");
        assertEquals(buried.bandTopDbFs(), buried.rawAmplitudeDbFs(), 1e-9, "2f1 - f2 is clamped at its band-top");
    }

    /** One frame analysed and its statistics calculated, with no
     *  {@code .frc} correction.
     *
     * @param analyzer       the analyser, with any pilots pinned
     * @param signal         the samples, the frame at their start
     * @param sampleRate     the sample rate, Hz
     * @param fftSize        the transform length
     * @param settings       the readout settings
     * @param window         the analysis window
     * @param overlap        the frame overlap stamped on the result
     * @param coherent       whether the frame keeps its phasors
     * @param expectedFundHz the fundamental hint, {@link Double#NaN} for none
     * @param step           the frame spacing stamped on the result, samples
     * @return the frame with its statistics */
    private FftResult analyzeWithStatistics(FftAnalyzer analyzer, double[] signal, int sampleRate,
                                            int fftSize, ReadoutSettings settings,
                                            WindowType window, FftOverlap overlap,
                                            boolean coherent, double expectedFundHz, int step) {
        FftResult r = analyzer.analyze(signal, sampleRate, fftSize, settings, window, overlap,
                coherent, expectedFundHz, new FftResult(), step);
        new SpectrumStatistics().calculateStat(r, settings, null);
        return r;
    }

    /** A sine.
     *
     * @param sampleRate the sample rate, Hz
     * @param freqHz     its frequency, Hz
     * @param amplitude  its peak amplitude, full scale 1
     * @param n          the samples wanted
     * @return the sine */
    private double[] pureSine(int sampleRate, double freqHz, double amplitude, int n) {
        double[] sig = new double[n];
        for (int i = 0; i < n; i++) {
            sig[i] = (double) (amplitude * Math.sin(2.0 * Math.PI * freqHz * i / sampleRate));
        }
        return sig;
    }

    /** A sine on white Gaussian noise of {@link #CLAMP_TEST_NOISE_RMS}, the
     *  same noise every run.
     *
     * @param sampleRate the sample rate, Hz
     * @param freqHz     the sine's frequency, Hz
     * @param amplitude  its peak amplitude, full scale 1
     * @param n          the samples wanted
     * @return the samples */
    private double[] noisySine(int sampleRate, double freqHz, double amplitude, int n) {
        double[] sig = pureSine(sampleRate, freqHz, amplitude, n);
        Random noise = new Random(CLAMP_TEST_NOISE_SEED);
        for (int i = 0; i < n; i++) {
            sig[i] += CLAMP_TEST_NOISE_RMS * noise.nextGaussian();
        }
        return sig;
    }
}
