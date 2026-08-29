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

package org.edgo.audio.measure.gui.freqresp;

import org.edgo.audio.measure.preferences.BackendPrefs;
import org.edgo.audio.measure.preferences.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Frequency Response worker takes each converter's format from the half of
 * the preferences that converter owns.
 *
 * <p>An audio card's two sides are configured independently in this app: the
 * ADC has its own sample rate and word length, the DAC has its own.  When they
 * differ - 192000 Hz / 24 bit in against 384000 Hz / 32 bit out is a real bench
 * pairing - a measurement that reads only the input half and opens BOTH lines
 * with it asks the output device for a format it is not configured for, the
 * driver refuses that line, and the measurement cannot run at all.
 *
 * <p>That mistake is one word in a preference getter, it produces no wrong
 * number to notice, and nothing else in the suite catches it: the analyzer's own
 * tests build their configuration by hand, so they never cross the preferences
 * seam.  This test crosses exactly that seam and nothing else - it asks the
 * worker to derive one run's configuration and reads back which rate and which
 * word length each converter was given.  No audio device is opened.
 */
class FreqRespWorkerFormatTest {

    /** Deliberately split, and all four distinct, so a configuration field that
     *  reads the wrong half of the preferences can never coincidentally hold the
     *  right value. */
    private static final int INPUT_RATE_HZ  = 48_000;
    private static final int OUTPUT_RATE_HZ = 384_000;
    private static final int INPUT_BITS     = 16;
    private static final int OUTPUT_BITS    = 32;

    private FreqRespAnalyzerWorker worker;
    private int previousInputRate;
    private int previousOutputRate;
    private int previousInputBits;
    private int previousOutputBits;

    @BeforeEach
    void splitTheTwoConvertersFormats() {
        Preferences prefs = Preferences.instance();
        // Transient mode keeps this test off the developer's preferences file;
        // the live singleton's values are restored either way.
        prefs.setTransientMode(true);
        BackendPrefs backend = prefs.current();
        previousInputRate  = backend.getInputSampleRate();
        previousOutputRate = backend.getOutputSampleRate();
        previousInputBits  = backend.getInputBitDepth();
        previousOutputBits = backend.getOutputBitDepth();
        backend.setInputSampleRate(INPUT_RATE_HZ);
        backend.setOutputSampleRate(OUTPUT_RATE_HZ);
        backend.setInputBitDepth(INPUT_BITS);
        backend.setOutputBitDepth(OUTPUT_BITS);
        // The worker's UI marshaller is not reached by the configuration step;
        // running it inline keeps the test free of an SWT display either way.
        worker = new FreqRespAnalyzerWorker(Runnable::run);
    }

    @AfterEach
    void restore() {
        BackendPrefs backend = Preferences.instance().current();
        backend.setInputSampleRate(previousInputRate);
        backend.setOutputSampleRate(previousOutputRate);
        backend.setInputBitDepth(previousInputBits);
        backend.setOutputBitDepth(previousOutputBits);
        Preferences.instance().setTransientMode(false);
    }

    /**
     * The sweep is played on the DAC's configured rate and word length, and the
     * recording - with the deconvolution, its reference, the bin grid and the
     * result's Nyquist that all follow from it - is counted on the ADC's rate.
     *
     * <p>The two device references are carried through the configuration
     * untouched, so none is passed: what is under test is which half of the
     * preferences each format field came from.
     *
     * <p>There is deliberately no assertion that any two of these numbers are
     * equal.  That they may differ is the whole point.
     */
    @Test
    void eachConverterGetsItsOwnConfiguredFormat() {
        FreqRespAnalyzerConfig cfg = worker.buildConfig(null, null);

        assertEquals(OUTPUT_RATE_HZ, cfg.getPlaybackRateHz(),
                "the sweep is played on the OUTPUT device's configured rate - reading the "
                        + "input rate here is what makes the output line fail to open");
        assertEquals(OUTPUT_BITS, cfg.getPlaybackBitDepth(),
                "and on the OUTPUT device's configured word length");
        assertEquals(INPUT_RATE_HZ, cfg.getCaptureRateHz(),
                "while the recording and everything derived from it stay on the INPUT rate");
    }
}
