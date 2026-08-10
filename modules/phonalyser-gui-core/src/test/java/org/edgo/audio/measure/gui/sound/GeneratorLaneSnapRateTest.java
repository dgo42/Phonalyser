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

package org.edgo.audio.measure.gui.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.preferences.BackendPrefs;
import org.edgo.audio.measure.preferences.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which CLOCK the snap-to-FFT-bin correction is computed on.
 *
 * <p>An FFT bin is a property of the ANALYSIS - its grid is {@code fs_in / N} on
 * the captured signal - so the emitted tone has to be snapped to the INPUT rate.
 * Snapping it to the DAC's rate puts the tone between bins whenever the two
 * differ, which is the whole thing the snap exists to prevent, and it is easy to
 * miss because both rates default to the same value.
 */
class GeneratorLaneSnapRateTest {

    private static final int RATE_48K = 48_000;
    private static final int RATE_96K = 96_000;
    /** Deliberately different from the capture rate, and the default. */
    private static final int OUTPUT_RATE_384K = 384_000;
    private static final int FFT_4096 = 4_096;
    private static final double TONE_HZ = 997.0;
    /** 48 000 / 4 096 = 11.71875 Hz per bin; 997 / 11.71875 = 85.08 -> bin 85. */
    private static final double SNAPPED_AT_48K = 85 * (48_000.0 / 4_096);
    /** 96 000 / 4 096 = 23.4375 Hz per bin; 997 / 23.4375 = 42.53 -> bin 43. */
    private static final double SNAPPED_AT_96K = 43 * (96_000.0 / 4_096);
    /** What the tone becomes when the OUTPUT clock is used by mistake:
     *  384 000 / 4 096 = 93.75 Hz per bin, 997 / 93.75 = 10.6 -> bin 11. */
    private static final double SNAPPED_AT_384K = 11 * (384_000.0 / 4_096);
    private static final double EXACT = 0.0;

    private final GeneratorLane lane = new GeneratorLane();

    private int previousInputRate;
    private int previousOutputRate;
    private int previousFftLength;
    private boolean previousSnap;
    private GenSignalForm previousForm;
    private double previousFrequency;

    @BeforeEach
    void rememberAndConfigure() {
        Preferences prefs = Preferences.instance();
        BackendPrefs backend = prefs.current();
        previousInputRate  = backend.getInputSampleRate();
        previousOutputRate = backend.getOutputSampleRate();
        previousFftLength  = prefs.getFftLength();
        previousSnap       = prefs.isGenSnapToFftBin();
        previousForm       = prefs.getGenSignalForm();
        previousFrequency  = prefs.getGenFrequencyHz();

        backend.setInputSampleRate(RATE_48K);
        backend.setOutputSampleRate(OUTPUT_RATE_384K);
        prefs.setFftLength(FFT_4096);
        prefs.setGenSnapToFftBin(true);
        prefs.setGenSignalForm(GenSignalForm.SINE);
        prefs.setGenFrequencyHz(TONE_HZ);
    }

    @AfterEach
    void restore() {
        Preferences prefs = Preferences.instance();
        BackendPrefs backend = prefs.current();
        backend.setInputSampleRate(previousInputRate);
        backend.setOutputSampleRate(previousOutputRate);
        prefs.setFftLength(previousFftLength);
        prefs.setGenSnapToFftBin(previousSnap);
        prefs.setGenSignalForm(previousForm);
        prefs.setGenFrequencyHz(previousFrequency);
    }

    @Test
    @DisplayName("the snap lands on the CAPTURE grid, not the DAC's")
    void theSnapUsesTheInputRate() {
        assertEquals(RATE_48K, lane.analysisRateHz(),
                "the analysis rate is the input side's");
        assertEquals(SNAPPED_AT_48K, lane.commandedFrequency(), EXACT,
                "the tone must land on a bin of the spectrum it will be measured in");
        assertNotEquals(SNAPPED_AT_384K, lane.commandedFrequency(),
                "snapping on the output clock is the defect this pins: it puts the "
                        + "tone on the DAC's grid, which is a different set of "
                        + "frequencies whenever the two rates differ");
    }

    @Test
    @DisplayName("the snapped tone follows a capture-rate change")
    void theSnapFollowsTheRate() {
        assertEquals(SNAPPED_AT_48K, lane.commandedFrequency(), EXACT);

        Preferences.instance().current().setInputSampleRate(RATE_96K);

        assertEquals(SNAPPED_AT_96K, lane.commandedFrequency(), EXACT,
                "a rate change moves every bin, so the snapped tone has to move with it");
        assertNotEquals(SNAPPED_AT_48K, lane.commandedFrequency(),
                "a value cached from the previous rate would sit off-bin");
    }

    @Test
    @DisplayName("the second tone of a dual tone is snapped on the same grid")
    void theDualToneSnapUsesTheInputRateToo() {
        assertEquals(SNAPPED_AT_48K, lane.snapDualTone(TONE_HZ), EXACT);

        Preferences.instance().current().setInputSampleRate(RATE_96K);

        assertEquals(SNAPPED_AT_96K, lane.snapDualTone(TONE_HZ), EXACT);
    }

    @Test
    @DisplayName("a rectangle still aligns its period to the DAC clock")
    void periodAlignmentStaysOnTheOutputRate() {
        Preferences prefs = Preferences.instance();
        prefs.setGenSignalForm(GenSignalForm.RECTANGLE);
        // 384 000 / round(384 000 / 997) = 384 000 / 385 - the edge has to land on
        // a DAC sample, which has nothing to do with the analysis grid.
        assertEquals(OUTPUT_RATE_384K / (double) Math.round(OUTPUT_RATE_384K / TONE_HZ),
                lane.commandedFrequency(), EXACT,
                "the integer-sample period belongs to the clock that emits the samples");
    }
}
