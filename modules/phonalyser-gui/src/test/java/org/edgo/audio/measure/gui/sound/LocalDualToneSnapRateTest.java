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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.gui.generator.GeneratorController;
import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.preferences.BackendPrefs;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a LOCAL dual-tone start actually drives the second tone at.
 *
 * <p>Both tones must land on bin centres of the SAME grid - the analysis one,
 * {@code fs_in / N}.  Tone 1 is snapped where the run is derived; tone 2 is
 * pushed into the DDS separately after it is built, and that second push is the
 * one a rate split can leave behind: with tone 1 on the analysis grid and tone 2
 * on the DAC's, the second tone smears across bins and manufactures exactly the
 * intermodulation products the IMD table then reports as the device's.
 *
 * <p>Asserted on the generator the lane HANDED THE OUTPUT LINE, not on the
 * helper that computes the value - a helper can be right while the start path
 * never calls it.
 */
class LocalDualToneSnapRateTest {

    private static final int INPUT_RATE_48K = 48_000;
    /** Different from the capture rate, and the value both rates default to. */
    private static final int OUTPUT_RATE_384K = 384_000;
    private static final int BIT_DEPTH = 24;
    private static final int FFT_4096 = 4_096;
    private static final double TONE1_HZ = 997.0;
    private static final double TONE2_HZ = 1_997.0;
    private static final double AMPLITUDE_VRMS = 0.1;
    /** 1997 / (48 000 / 4 096) = 170.4 -> bin 170. */
    private static final double TONE2_ON_ANALYSIS_GRID = 170 * (48_000.0 / 4_096);
    /** 1997 / (384 000 / 4 096) = 21.3 -> bin 21: the DAC grid, the defect. */
    private static final double TONE2_ON_OUTPUT_GRID = 21 * (384_000.0 / 4_096);
    /** The phase increment is a 64-bit integer, so a read-back is exact to
     *  {@code fs/2^64} - far below anything this asserts. */
    private static final double TOLERANCE_HZ = 1e-6;
    private static final long AWAIT_MS = 10_000;
    private static final long POLL_MS = 20;

    private DyingLaneStub stub;
    private GeneratorController controller;
    private BackendKey previousActive;
    private BackendKey previousSelection;
    private GenSignalForm previousForm;
    private double previousFrequencyHz;
    private double previousFreq1;
    private double previousFreq2;
    private int previousInputRate;
    private int previousOutputRate;
    private int previousFftLength;
    private boolean previousSnap;

    @BeforeEach
    void armTheStubBackend() {
        Preferences prefs = Preferences.instance();
        prefs.setTransientMode(true);
        previousSelection   = prefs.getSelectedBackend();
        previousForm        = prefs.getGenSignalForm();
        previousFrequencyHz = prefs.getGenFrequencyHz();
        previousFreq1       = prefs.getGenDualToneFreq1Hz();
        previousFreq2       = prefs.getGenDualToneFreq2Hz();
        previousFftLength   = prefs.getFftLength();
        previousSnap        = prefs.isGenSnapToFftBin();

        AudioBackend audio = AudioBackend.instance();
        previousActive = audio.activeKey();
        stub = assertInstanceOf(DyingLaneStub.class,
                audio.manager(AudioBackendType.JAVASOUND),
                "the JAVASOUND slot went to the real backend - this test would then "
                        + "open a sound card instead of the stub lane it reads back");
        audio.setActive(AudioBackendType.JAVASOUND);
        prefs.setSelectedBackend(BackendKey.of(AudioBackendType.JAVASOUND));

        BackendPrefs backend = prefs.current();
        previousInputRate  = backend.getInputSampleRate();
        previousOutputRate = backend.getOutputSampleRate();
        backend.setOutputDeviceName(DyingLaneStub.OUTPUT_NAME);
        backend.setInputDeviceName(DyingLaneStub.INPUT_NAME);
        backend.setOutputBitDepth(BIT_DEPTH);
        backend.setInputBitDepth(BIT_DEPTH);
        // The whole point: the two clocks differ, so a snap on the wrong one
        // lands on a visibly different frequency.
        backend.setInputSampleRate(INPUT_RATE_48K);
        backend.setOutputSampleRate(OUTPUT_RATE_384K);

        prefs.setFftLength(FFT_4096);
        prefs.setGenSnapToFftBin(true);
        prefs.setGenSignalForm(GenSignalForm.DUAL_TONE);
        prefs.setGenDualToneFreq1Hz(TONE1_HZ);
        prefs.setGenDualToneFreq2Hz(TONE2_HZ);
        prefs.setGenAmplitudeVrms(AMPLITUDE_VRMS);
    }

    @AfterEach
    void disarm() {
        if (stub != null && stub.openedPlaybacks() > 0) {
            stub.killPlayback();   // let the parked render thread leave play()
        }
        if (controller != null) {
            controller.shutdown();
            controller = null;
        }
        AudioBackend.instance().setActive(previousActive);
        Preferences prefs = Preferences.instance();
        if (previousSelection != null) {
            prefs.setSelectedBackend(previousSelection);
        }
        BackendPrefs backend = prefs.current();
        backend.setInputSampleRate(previousInputRate);
        backend.setOutputSampleRate(previousOutputRate);
        prefs.setFftLength(previousFftLength);
        prefs.setGenSnapToFftBin(previousSnap);
        prefs.setGenSignalForm(previousForm);
        prefs.setGenFrequencyHz(previousFrequencyHz);
        prefs.setGenDualToneFreq1Hz(previousFreq1);
        prefs.setGenDualToneFreq2Hz(previousFreq2);
    }

    /** The DDS the lane handed the line, once its render thread has it. */
    private SignalGenerator awaitPlayedGenerator() throws InterruptedException {
        long deadline = System.currentTimeMillis() + AWAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            SignalGenerator played = stub.lastPlayedGenerator();
            if (played != null) return played;
            Thread.sleep(POLL_MS);
        }
        return null;
    }

    @Test
    @DisplayName("a local dual-tone start drives tone 2 on the analysis grid")
    void toneTwoIsSnappedOnTheCaptureRate() throws InterruptedException {
        controller = new GeneratorController();
        controller.start();

        SignalGenerator played = awaitPlayedGenerator();
        assertNotNull(played, "the lane never handed a generator to the output line");
        double tone2 = played.getDualToneFrequency2Hz();

        assertEquals(TONE2_ON_ANALYSIS_GRID, tone2, TOLERANCE_HZ,
                "tone 2 has to sit on a bin of the spectrum it will be measured in, "
                        + "the same grid tone 1 was snapped to");
        assertNotEquals(TONE2_ON_OUTPUT_GRID, tone2,
                "snapping tone 2 against the run's rate puts it on the DAC grid while "
                        + "tone 1 sits on the analysis grid - two different grids, and "
                        + "the smeared tone feeds the IMD products the table reports");
    }
}
