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

import org.apache.commons.lang3.reflect.FieldUtils;
import org.edgo.audio.measure.enums.AlignGenerator;
import org.edgo.audio.measure.enums.FundamentalPhaseAlignment;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.test.AbstractReflectiveTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The frequency the generator lock is fed from the analysed channel: the
 * phase step of the tone's bin between two consecutive cycles' frames, which
 * is exact for a clean tone under any window, in place of the analysis's
 * one-frame estimate, which under a rectangular window reads the noise
 * beside the peak and sent a tone that sat on its bin off it.  The first
 * cycle after a window rebuild has no previous frame and feeds nothing.
 *
 * <p>The measurement and the lock step are the worker's private members,
 * driven by reflection on a worker that holds a synthetic window, the way
 * {@link FftFrequencyLockGatingTest} drives the step.
 */
class FftFrequencyLockReadingTest extends AbstractReflectiveTest {

    private static final int    SAMPLE_RATE = 48_000;
    private static final int    FFT_SIZE    = 4096;
    private static final int    HOP         = FFT_SIZE / 4;
    private static final double BIN_HZ      = SAMPLE_RATE / (double) FFT_SIZE;
    /** The generator's tone sits on this bin of the grid, so the snap leaves
     *  the target where it is. */
    private static final int    TARGET_BIN  = 100;
    private static final double TARGET_HZ   = TARGET_BIN * BIN_HZ;
    /** The tone as captured: a hundred times the loop's lock band off the
     *  target, so a reading that reaches the loop corrects. */
    private static final double MISTUNE_PPM = 1.0;
    private static final double TONE_HZ     = TARGET_HZ * (1.0 + MISTUNE_PPM * 1.0e-6);
    /** What the one-frame estimate of a rectangular window hands over for an
     *  on-bin tone: the noise beside the peak, here a hundredth of a bin. */
    private static final double SEED_HZ     = TARGET_HZ + 0.01 * BIN_HZ;
    /** The step reading's accuracy on a clean tone, in bins. */
    private static final double TOLERANCE_BINS = 1e-6;
    private static final double FUND_DBFS   = -20.0;

    private static final String MEASURE   = "measureAnalysedFrequencies";
    private static final String STEP      = "applyFrequencyLock";
    private static final String LOOP      = "fll";
    private static final String WINDOW    = "winBuf";
    private static final String VALID     = "winValid";
    private static final String ABS_START = "winAbsStart";
    private static final String LAST_UPDATE = "lastUpdateAbsPos";

    private boolean        previousSnap;
    private boolean        previousFundFromGen;
    private AlignGenerator previousAlign;
    private GenSignalForm  previousForm;
    private double         previousFrequencyHz;
    private int            previousFftLength;

    @BeforeEach
    void lockEngaged() {
        Preferences prefs = Preferences.instance();
        prefs.setTransientMode(true);
        previousSnap        = prefs.isGenSnapToFftBin();
        previousFundFromGen = prefs.isFftFundFromGenerator();
        previousAlign       = prefs.getFftAlignGenerator();
        previousForm        = prefs.getGenSignalForm();
        previousFrequencyHz = prefs.getGenFrequencyHz();
        previousFftLength   = prefs.getFftLength();
        prefs.setGenSnapToFftBin(true);
        prefs.setFftFundFromGenerator(true);
        prefs.setFftAlignGenerator(AlignGenerator.FLL);
        prefs.setGenSignalForm(GenSignalForm.SINE);
        prefs.setGenFrequencyHz(TARGET_HZ);
        prefs.setFftLength(FFT_SIZE);
        MessageBus.instance().registerResponder(Events.GENERATOR_RUNNING, () -> Boolean.TRUE);
    }

    @AfterEach
    void settingsRestored() {
        MessageBus.instance().unregisterResponder(Events.GENERATOR_RUNNING);
        Preferences prefs = Preferences.instance();
        prefs.setGenSnapToFftBin(previousSnap);
        prefs.setFftFundFromGenerator(previousFundFromGen);
        prefs.setFftAlignGenerator(previousAlign);
        prefs.setGenSignalForm(previousForm);
        prefs.setGenFrequencyHz(previousFrequencyHz);
        prefs.setFftLength(previousFftLength);
    }

    /**
     * Cycle one has no previous frame: the result keeps the one-frame
     * estimate and the lock, fed that cycle's reading on cycle two, does not
     * move.  Cycle two reads the tone from the phase step over the hop, to a
     * millionth of a bin, and the lock, fed it on cycle three, corrects the
     * mistune.
     */
    @ParameterizedTest
    @EnumSource(value = WindowType.class, names = {"RECT", "BH7"})
    void theLockIsFedThePhaseStepReadingAndNotTheOneFrameEstimate(WindowType window) throws Exception {
        FftAnalyzerWorker worker = new FftAnalyzerWorker(null);   // headless
        double[] tone = tone(TONE_HZ, FFT_SIZE + 2 * HOP);

        FftResult first = measured(worker, window, tone, 0);
        assertEquals(SEED_HZ, first.fundamentalHzRefined, 0.0,
                "cycle one has nothing to step from: the one-frame estimate stands");
        lock(worker, first, 0);

        FftResult second = measured(worker, window, tone, HOP);
        assertEquals(TONE_HZ, second.fundamentalHzRefined, TOLERANCE_BINS * BIN_HZ,
                "cycle two reads the tone from the phase step (" + window + ")");
        lock(worker, second, HOP);
        FrequencyAligner fll = field(worker, LOOP);
        assertEquals(-1L, lastUpdate(fll),
                "cycle one's reading, the one-frame estimate, never reaches the loop");
        assertEquals(0.0, fll.getCorrection(), "and the loop holds");

        FftResult third = measured(worker, window, tone, 2 * HOP);
        lock(worker, third, 2 * HOP);
        assertEquals(HOP, lastUpdate(fll),
                "cycle two's step reading, under its window's start, is fed on cycle three");
        assertNotEquals(0.0, fll.getCorrection(), "and the mistune it carries is corrected");
        assertTrue(Math.abs(fll.getCorrection() + (TONE_HZ - TARGET_HZ)) < TOLERANCE_BINS * BIN_HZ,
                "by the mistune itself: " + fll.getCorrection());
    }

    // ── Drivers ────────────────────────────────────────────────────────────

    /** One cycle's measurement on the window of {@code tone} that starts at
     *  {@code absStart}, seeded with the one-frame estimate; returns the
     *  cycle's result. */
    private FftResult measured(FftAnalyzerWorker worker, WindowType window, double[] tone, int absStart)
            throws IllegalAccessException {
        double[] win = new double[FFT_SIZE];
        System.arraycopy(tone, absStart, win, 0, FFT_SIZE);
        FieldUtils.writeField(worker, WINDOW, win, true);
        FieldUtils.writeField(worker, VALID, true, true);
        FieldUtils.writeField(worker, ABS_START, (long) absStart, true);
        FftResult r = new FftResult();
        r.sampleRate           = SAMPLE_RATE;
        r.fftSize              = FFT_SIZE;
        r.freqResolution       = BIN_HZ;
        r.windowType           = window;
        r.fundamentalBin       = TARGET_BIN;
        r.fundamentalHzRefined = SEED_HZ;
        r.fundamentalDbFs      = FUND_DBFS;
        call(worker, MEASURE, r);
        return r;
    }

    /** One lock step with the analysis mode NONE and the write position one
     *  transform past the window's start. */
    private void lock(FftAnalyzerWorker worker, FftResult r, int absStart) {
        call(worker, STEP, r, FundamentalPhaseAlignment.NONE, (long) absStart, (long) absStart + FFT_SIZE,
                worker.currentResetEpoch());
    }

    /** The capture position of the loop's last update, -1 before the first. */
    private long lastUpdate(FrequencyAligner fll) {
        return field(fll, LAST_UPDATE);
    }

    /** {@code length} samples of a unit sine at {@code hz}. */
    private double[] tone(double hz, int length) {
        double[] s = new double[length];
        double w = 2.0 * Math.PI * hz / SAMPLE_RATE;
        for (int n = 0; n < length; n++) {
            s[n] = Math.sin(w * n);
        }
        return s;
    }
}
