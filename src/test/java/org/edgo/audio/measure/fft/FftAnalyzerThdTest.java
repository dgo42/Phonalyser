package org.edgo.audio.measure.fft;

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
 * harmonic-gather, harmonic-power, and THD-ratio code paths — the
 * stretch where a phase split of the 860-line method is most likely
 * to introduce subtle regressions.
 */
class FftAnalyzerThdTest {

    @Test
    void pureSine_thdIsNearZero() {
        // A clean sine has no harmonic content.  THD must be very low —
        // a "0.0001 %" type number, not the 0 % a textbook would
        // report, because numerical noise leaks into the harmonic bins.
        int sampleRate = 48_000;
        int fftSize    = 8_192;
        double freqHz  = (double) sampleRate / fftSize * 100;   // bin 100

        float[] signal = pureSine(sampleRate, freqHz, 0.5, fftSize * 2);

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

        float[] signal = new float[fftSize * 2];
        for (int n = 0; n < signal.length; n++) {
            signal[n] = (float) (
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

        float[] signal = new float[fftSize * 2];
        for (int n = 0; n < signal.length; n++) {
            double s = 0;
            for (int i = 0; i < amps.length; i++) {
                s += amps[i] * Math.sin(2.0 * Math.PI * mult[i] * freqHz * n / sampleRate);
            }
            signal[n] = (float) s;
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
    void result_carriesInputParameters() {
        // Sanity bookkeeping: the analyser doesn't mangle the parameters
        // it was given on the way through.  Catches a subtle bug where
        // a refactor accidentally reorders constructor args.
        int sampleRate = 96_000;
        int fftSize    = 16_384;
        float[] signal = pureSine(sampleRate, 2_000.0, 0.4, fftSize * 2);

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

    private static float[] pureSine(int sampleRate, double freqHz, double amplitude, int n) {
        float[] sig = new float[n];
        for (int i = 0; i < n; i++) {
            sig[i] = (float) (amplitude * Math.sin(2.0 * Math.PI * freqHz * i / sampleRate));
        }
        return sig;
    }
}
