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

package org.edgo.audio.measure.gui.generator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.preferences.BackendPrefs;
import org.edgo.audio.measure.preferences.Preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether a file may be played at all: its own rate and depth against the ones
 * the output is CONFIGURED for.
 *
 * <p>A file is played at its own format - nothing is resampled and nothing is
 * re-quantised - so the line is opened at the FILE's rate and depth.  On a backend
 * whose two directions share one clock that drags the whole session onto the
 * file's rate, and on any backend it silently replaces the format the operator
 * configured.  So the mismatch is refused before the line is opened, and the
 * refusal names both formats: "set the output to the file's format" is the
 * operator's next move and they cannot make it until they are told which two
 * numbers disagree.
 *
 * <p>The comparison is the whole decision, and it needs neither a device nor a
 * display - so it is driven directly, with no SWT anywhere in this file.
 */
class GeneratorControllerFileFormatTest {

    /** This test's own backend entry, so nothing here touches a real backend's
     *  saved device, rate or depth. */
    private static final String SERVER_ID = "b7e0-bench-file-format";
    private static final int CONFIGURED_RATE = 48000;
    private static final int CONFIGURED_BITS = 24;
    private static final String FILE_NAME = "sweep-clip.wav";

    private GeneratorController controller;
    private BackendKey previousSelection;

    @BeforeEach
    void configureTheOutput() {
        Preferences prefs = Preferences.instance();
        // The controller writes through the live singleton; transient mode makes
        // sure this test cannot reach the user's preferences file.
        prefs.setTransientMode(true);
        previousSelection = prefs.getSelectedBackend();
        prefs.setSelectedBackend(BackendKey.of(SERVER_ID, AudioBackendType.QA40X));
        BackendPrefs output = prefs.current();
        output.setOutputSampleRate(CONFIGURED_RATE);
        output.setOutputBitDepth(CONFIGURED_BITS);
        controller = new GeneratorController();
    }

    @AfterEach
    void restoreTheSelection() {
        if (controller != null) {
            controller.shutdown();
            controller = null;
        }
        if (previousSelection != null) {
            Preferences.instance().setSelectedBackend(previousSelection);
        }
    }

    /**
     * A file lane is opened with the CONFIGURED dither, and a change mid-file
     * reaches it.
     *
     * <p>It used to be opened at a hard zero, on the reasoning that a file's
     * samples are already quantised.  They are not, by the time they leave: the
     * calibration scale multiplies every sample and the line re-quantises the
     * product at the DAC's depth - an undithered rounding, which is what dither
     * exists to linearise.  The live half matters for the same reason the tone's
     * does: the operator moves the field while listening.
     */
    @Test
    void theLiveDitherPushSurvivesAnIdleFileLine() {
        // The push now reaches TWO lanes from one setter. With no file playing the
        // second is null, and that is the ordinary case every dither edit takes -
        // it must not fault, and it must still reach the tone lane.
        controller.setDitherBits(4.0);
        controller.setDitherBits(0.0);
    }

    @Test
    void aFileInTheConfiguredFormatIsPlayed() {
        assertNull(controller.filePlayFormatMismatch(FILE_NAME, CONFIGURED_RATE, CONFIGURED_BITS),
                "a file that matches the configured output is exactly what this backend plays");
    }

    @Test
    void aDifferentRateIsRefusedAndBothFormatsAreNamed() {
        String refusal = controller.filePlayFormatMismatch(FILE_NAME, 44100, CONFIGURED_BITS);

        assertNotNull(refusal, "a 44.1 kHz file on a 48 kHz output cannot be played as it is");
        assertEquals(I18n.t("generator.error.playFile.formatMismatch", FILE_NAME,
                "44100", "24", "48000", "24"), refusal);
        assertTrue(refusal.contains(FILE_NAME), "the operator is told WHICH file: " + refusal);
        // Ungrouped, and that is the assertion: handed to MessageFormat as an
        // Integer a rate comes back as "44,100 Hz", which no combo in this
        // application writes - so the message would name a rate the operator
        // cannot find in the list it sends them to.
        assertTrue(refusal.contains("44100") && refusal.contains("48000"),
                "and both rates, or there is nothing to compare: " + refusal);
        assertFalse(refusal.contains("{"),
                "every placeholder was substituted - a lone apostrophe in the pattern "
                        + "silently stops MessageFormat doing that: " + refusal);
    }

    @Test
    void aDifferentDepthAloneIsRefusedToo() {
        String refusal = controller.filePlayFormatMismatch(FILE_NAME, CONFIGURED_RATE, 16);

        assertNotNull(refusal, "the depth decides the line's format just as the rate does");
        assertEquals(I18n.t("generator.error.playFile.formatMismatch", FILE_NAME,
                "48000", "16", "48000", "24"), refusal);
        assertTrue(refusal.contains("16") && refusal.contains("24"),
                "both depths reach the operator: " + refusal);
    }

    @Test
    void theRefusalFollowsTheCONFIGUREDFormat_notAFixedOne() {
        // The operator sets the output to the file's format, which is the move the
        // message asks for - and the very same file is then played.
        BackendPrefs output = Preferences.instance().current();
        output.setOutputSampleRate(96000);
        output.setOutputBitDepth(32);

        assertNotNull(controller.filePlayFormatMismatch(FILE_NAME, CONFIGURED_RATE, CONFIGURED_BITS),
                "the file that fitted the old configuration no longer fits this one");
        assertNull(controller.filePlayFormatMismatch(FILE_NAME, 96000, 32),
                "and the one that fits the new configuration is played");
    }
}
