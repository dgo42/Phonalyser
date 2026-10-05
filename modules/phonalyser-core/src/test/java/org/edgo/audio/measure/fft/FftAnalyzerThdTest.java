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

import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.WindowType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harmonic-detection and THD computation tests for {@link FftAnalyzer}.
 * Builds synthetic signals with known harmonic content, runs analysis,
 * verifies the analyser picks the right harmonic bins and computes a
 * sensible THD percentage.
 *
 * <p>This expands the safety net around {@link FftAnalyzer#analyze}'s
 * harmonic-gather, harmonic-power, and THD-ratio code paths - the
 * stretch where a phase split of the 860-line method is most likely
 * to introduce subtle regressions.
 */
class FftAnalyzerThdTest {

    @Test
    void pureSine_thdIsNearZero() {
        // A clean sine has no harmonic content.  THD must be very low -
        // a "0.0001 %" type number, not the 0 % a textbook would
        // report, because numerical noise leaks into the harmonic bins.
        int sampleRate = 48_000;
        int fftSize    = 8_192;
        double freqHz  = (double) sampleRate / fftSize * 100;   // bin 100

        double[] signal = pureSine(sampleRate, freqHz, 0.5, fftSize * 2);

        FftResult r = new FftAnalyzer().analyze(
                signal, sampleRate, fftSize, 8,
                WindowType.HANN, FftOverlap.PCT_0,
                0.0, 0.0, true, Double.NaN, false);

        assertTrue(r.thdPct < 0.01,
                "pure sine THD should be < 0.01 %, was " + r.thdPct);
        assertTrue(r.thdDb < -60.0,
                "pure sine THD-dB should be < -60 dB, was " + r.thdDb);
    }

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

        FftResult r = new FftAnalyzer().analyze(
                signal, sampleRate, fftSize, 8,
                WindowType.HANN, FftOverlap.PCT_0,
                0.0, 0.0, true, Double.NaN, false);

        // THD percent should be ~1 % (ratio of harmonics power to fundamental).
        assertEquals(1.0, r.thdPct, 0.05,
                "THD% should match the added -40 dBc 2nd-harmonic amplitude");
        // Second harmonic bin should be detected at 2× the fundamental bin.
        int expectedH2Bin = 2 * r.fundamentalBin;
        assertEquals(expectedH2Bin, r.harmonicBins[0],
                "first harmonic slot should hold the 2nd harmonic bin");
    }

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
                signal, sampleRate, fftSize, 8,
                WindowType.HANN, FftOverlap.PCT_0,
                0.0, 0.0, true, Double.NaN, false);

        // harmonicBins[0]=H2, [1]=H3, [2]=H4, [3]=H5
        int fundBin = r.fundamentalBin;
        assertEquals(2 * fundBin, r.harmonicBins[0], "H2 bin");
        assertEquals(3 * fundBin, r.harmonicBins[1], "H3 bin");
        assertEquals(5 * fundBin, r.harmonicBins[3], "H5 bin");
    }

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
        int frames = (signal.length - fftSize) / step + 1;

        double[][] cases = { { 8, Math.sqrt(3.0) }, { 2, Math.sqrt(2.0) }, { 1, 1.0 } };
        for (double[] c : cases) {
            int limit = (int) c[0];
            FftResult r = new FftAnalyzer().analyze(
                    signal, sampleRate, fftSize, 8, limit,
                    WindowType.HANN, FftOverlap.PCT_0,
                    0.0, 0.0, true, Double.NaN, false, Double.NaN,
                    new FftResult(), step, frames);
            assertEquals(c[1], r.thdPct, 0.05, "THD over H2..H" + (limit + 1));
            assertEquals(limit, r.thdHarmonics, "the result names the harmonics it summed");
            assertEquals(5 * r.fundamentalBin, r.harmonicBins[3], "H5 stays in the table at limit " + limit);
        }
    }

    @Test
    void result_carriesInputParameters() {
        // Sanity bookkeeping: the analyser doesn't mangle the parameters
        // it was given on the way through.  Catches a subtle bug where
        // a refactor accidentally reorders constructor args.
        int sampleRate = 96_000;
        int fftSize    = 16_384;
        double[] signal = pureSine(sampleRate, 2_000.0, 0.4, fftSize * 2);

        FftResult r = new FftAnalyzer().analyze(
                signal, sampleRate, fftSize, 6,
                WindowType.HANN, FftOverlap.PCT_50,
                10.0, 40_000.0, false, -3.0, false);

        assertEquals(sampleRate, r.sampleRate);
        assertEquals(fftSize,    r.fftSize);
        assertEquals(WindowType.HANN,    r.windowType);
        assertEquals(FftOverlap.PCT_50,  r.overlap);
        assertEquals(10.0,    r.snrFreqMin);
        assertEquals(40_000.0, r.snrFreqMax);
        assertEquals(false,   r.coherentAveraging);
    }

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
        FftResult noPilot = analyzer.analyze(signal, sampleRate, fftSize, 8,
                WindowType.BH7, FftOverlap.PCT_0, 0.0, 0.0, true, Double.NaN, false);
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
        FftResult withPilots = analyzer.analyze(signal, sampleRate, fftSize, 8,
                WindowType.BH7, FftOverlap.PCT_0, 0.0, 0.0, true, Double.NaN, false);

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

    @Test
    void pilotSpans_coverEachPilotBinPlusTheWindowsMainLobe() {
        // The analyser is the one place the excluded width is decided, and each
        // pilot's span is stamped on the result so that the readouts outside it
        // - the dual-tone TD+N residual - leave exactly the same bins out as
        // its own noise, SNR and ENOB sums.  One span per pilot: the bins its
        // position straddles, which for a whole bin is that bin alone, widened
        // by the window's own main-lobe half-width on either side.
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
        FftResult r = analyzer.analyze(signal, sampleRate, fftSize, 8,
                WindowType.BH7, FftOverlap.PCT_0, 0.0, 0.0, true, Double.NaN, false);

        int lobe = analyzer.mainLobeBins(WindowType.BH7);
        assertEquals(pilot1Bin - lobe, r.pilot1MaskLoBin,
                "the first pilot's span starts a main lobe below its bin");
        assertEquals(pilot1Bin + lobe, r.pilot1MaskHiBin,
                "the first pilot's span ends a main lobe above its bin");
        assertEquals(pilot2Bin - lobe, r.pilot2MaskLoBin,
                "the second pilot's span starts a main lobe below its bin");
        assertEquals(pilot2Bin + lobe, r.pilot2MaskHiBin,
                "the second pilot's span ends a main lobe above its bin");
    }

    private static double[] pureSine(int sampleRate, double freqHz, double amplitude, int n) {
        double[] sig = new double[n];
        for (int i = 0; i < n; i++) {
            sig[i] = (double) (amplitude * Math.sin(2.0 * Math.PI * freqHz * i / sampleRate));
        }
        return sig;
    }
}
