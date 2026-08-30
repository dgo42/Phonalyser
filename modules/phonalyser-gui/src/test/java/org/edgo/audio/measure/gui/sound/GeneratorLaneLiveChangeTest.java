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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.preferences.BackendPrefs;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How a predistortion file ({@code .dpd} - the saved harmonic correction table
 * the DAC-predistortion wizard writes) reaches a generator that is already
 * emitting.  Asked of the tone actually rendered, through the ordinary start
 * path against {@link DyingLaneStub}'s output line.
 *
 * <p>Two properties, both of them things the wizard's Apply depends on.  First:
 * a correction file arriving for the form that is playing is a hot-swap the DDS
 * supports (the tables are published into it atomically), so it must not stop
 * and start the device at all - a stop+start replays {@code lastRun}, the
 * parameters the lane was last STARTED with, and thereby discards every live
 * amplitude and frequency edit made since.  Second: switching a running
 * generator to a compensated form loads the file the path preference names AT
 * THAT MOMENT, which is what fixes the order the wizard has to commit in.
 */
class GeneratorLaneLiveChangeTest {

    private static final int RATE_HZ = 48_000;
    private static final int BIT_DEPTH = 24;
    private static final double TONE_HZ = 1_000.0;
    private static final double AMPLITUDE_VRMS = 0.1;
    /** DAC full scale of the run: 1.0 V, so an emitted peak IS the amplitude scale. */
    private static final double DAC_FS_VOLTS = 1.0;
    private static final double DITHER_BITS = 0.0;
    private static final double RIGHT_LANE_SCALE = 1.0;
    /** 4800 samples at 48 kHz = 100 whole cycles of a 1 kHz tone (and 200 of its
     *  second harmonic), so both magnitudes are read on a coherent window. */
    private static final int COHERENT_SAMPLES = 4_800;
    /** Comfortably above a plain sine's harmonic content (which is zero to
     *  rounding) and comfortably below the 20 % the test files ask for. */
    private static final double H2_PRESENT = 0.1;
    private static final double H2_ABSENT = 0.01;
    private static final int SECOND_HARMONIC = 2;
    private static final int THIRD_HARMONIC = 3;
    /** The correction amplitude every test file carries, in percent of the fundamental. */
    private static final double HARMONIC_PCT = 20.0;
    private static final double TWO = 2.0;

    @TempDir
    private Path tempDir;

    private final GeneratorLane lane = new GeneratorLane();

    private DyingLaneStub stub;
    private BackendKey previousActive;
    private BackendKey previousSelection;
    private GenSignalForm previousForm;
    private String previousDpd;

    @BeforeEach
    void armTheStubBackend() {
        Preferences prefs = Preferences.instance();
        prefs.setTransientMode(true);   // the lane writes through the live singleton
        previousSelection = prefs.getSelectedBackend();
        previousForm = prefs.getGenSignalForm();
        previousDpd = prefs.getGenDpd(GenSignalForm.SINE_COMP);

        AudioBackend audio = AudioBackend.instance();
        previousActive = audio.activeKey();
        stub = assertInstanceOf(DyingLaneStub.class,
                audio.manager(AudioBackendType.JAVASOUND),
                "the JAVASOUND slot went to the real backend - this test would then open "
                        + "a sound card instead of the stub line it reads the tone from");
        audio.setActive(AudioBackendType.JAVASOUND);
        prefs.setSelectedBackend(BackendKey.of(AudioBackendType.JAVASOUND));

        BackendPrefs backend = prefs.current();
        backend.setOutputDeviceName(DyingLaneStub.OUTPUT_NAME);
        backend.setOutputSampleRate(RATE_HZ);
        backend.setOutputBitDepth(BIT_DEPTH);
        prefs.setGenSignalForm(GenSignalForm.SINE);
    }

    @AfterEach
    void disarm() {
        lane.stop();
        AudioBackend.instance().setActive(previousActive);
        Preferences prefs = Preferences.instance();
        if (previousSelection != null) {
            prefs.setSelectedBackend(previousSelection);
        }
        prefs.setGenSignalForm(previousForm);
        prefs.setGenDpd(GenSignalForm.SINE_COMP, previousDpd);
    }

    /**
     * A correction file for the form that is playing reaches the tone through the
     * DDS's atomic hot-swap.  Stopping and starting the device for it is both
     * unnecessary and destructive: the restart replays the run the lane was last
     * started with, so the amplitude and the frequency revert to the values the
     * last Play was handed - which is what silently undoes every edit made while
     * the wizard was training, the frequency-lock trim onto the analysis bin
     * included.
     */
    @Test
    @DisplayName("a .dpd for the playing form is hot-loaded - no stop, no start")
    void aCorrectionFileReachesTheRunningToneWithoutARestart() throws IOException {
        Preferences prefs = Preferences.instance();
        assertEquals(PlaybackStateEnum.STARTED, lane.start(sineRun()));
        SignalGenerator running = lane();
        int openedBefore = stub.openedPlaybacks();
        assertTrue(harmonicRatio(emit(running, COHERENT_SAMPLES)) < H2_ABSENT,
                "the plain sine carries no second harmonic before the file is loaded");

        prefs.setGenDpd(GenSignalForm.SINE_COMP,
                writeDpd("just-measured.dpd", SECOND_HARMONIC).toString());
        prefs.setGenSignalForm(GenSignalForm.SINE_COMP);
        lane.dpdChanged(GenSignalForm.SINE_COMP);

        assertEquals(openedBefore, stub.openedPlaybacks(),
                "the correction file reopened the output device - a hot-swappable change "
                        + "was answered with a stop and a start");
        assertSame(running, lane(),
                "and it rebuilt the DDS, so everything set live on the old one is gone");
        assertTrue(harmonicRatio(emit(running, COHERENT_SAMPLES)) > H2_PRESENT,
                "the corrections never reached the tone that is playing");
    }

    /**
     * Switching a running generator to a compensated form loads the file the path
     * preference names AT THAT MOMENT - the property the predistortion wizard's
     * commit order rests on.  Its Apply writes the corrections path and then the
     * form, because the form switch is what loads the file for a generator that
     * was on the plain waveform; written the other way round, the compensated
     * tone comes up on whatever .dpd was configured before the session, and the
     * freshly measured one is never heard.
     *
     * <p>Asked with two files: the one configured before (a THIRD-harmonic
     * correction) and the one just saved (a SECOND-harmonic correction).  Only
     * the second may be audible afterwards.
     */
    @Test
    @DisplayName("a live form switch loads the .dpd the path preference names NOW")
    void switchingToTheCompensatedFormLoadsTheCurrentlyConfiguredFile() throws IOException {
        Preferences prefs = Preferences.instance();
        prefs.setGenDpd(GenSignalForm.SINE_COMP, writeDpd("previous.dpd", THIRD_HARMONIC).toString());
        assertEquals(PlaybackStateEnum.STARTED, lane.start(sineRun()));
        SignalGenerator running = lane();

        prefs.setGenDpd(GenSignalForm.SINE_COMP, writeDpd("just-saved.dpd", SECOND_HARMONIC).toString());
        lane.setForm(GenSignalForm.SINE_COMP);

        double[] emitted = emit(running, COHERENT_SAMPLES);
        assertTrue(ratioAt(emitted, SECOND_HARMONIC * TONE_HZ) > H2_PRESENT,
                "the just-saved corrections are not in the tone");
        assertTrue(ratioAt(emitted, THIRD_HARMONIC * TONE_HZ) < H2_ABSENT,
                "the tone carries the corrections configured BEFORE this session - the "
                        + "form was switched while the path preference still named the old file");
    }

    /**
     * The slot EMPTIED while its compensated form is the one playing: the tone
     * ends.  Compensated sine is DEFINED by its correction table - with none it
     * renders a plain sine under the compensated label, and a distortion figure
     * measured on that gets recorded as predistorted when it is not.  A start
     * with an empty slot refuses for exactly that reason (it answers
     * NEED_PREDISTORTION rather than opening a line), so a slot emptied while
     * the tone is up must not go on emitting either.
     *
     * <p>Ending it and RESTARTING it are told apart by the stub's open count: a
     * restart would open another output line, a stop opens none.
     */
    @Test
    @DisplayName("emptying the .dpd slot of the playing compensated form stops the lane")
    void emptyingTheSlotOfThePlayingCompensatedFormStopsTheTone() throws IOException {
        Preferences prefs = Preferences.instance();
        prefs.setGenDpd(GenSignalForm.SINE_COMP,
                writeDpd("loaded.dpd", SECOND_HARMONIC).toString());
        assertEquals(PlaybackStateEnum.STARTED, lane.start(sineRun()));
        prefs.setGenSignalForm(GenSignalForm.SINE_COMP);
        lane.setForm(GenSignalForm.SINE_COMP);   // the operator is on the compensated tone
        int openedBefore = stub.openedPlaybacks();

        prefs.setGenDpd(GenSignalForm.SINE_COMP, null);
        lane.dpdChanged(GenSignalForm.SINE_COMP);

        assertFalse(lane.isRunning(),
                "the compensated tone kept playing with no correction table - what it emits "
                        + "is now a plain sine, and every distortion figure read off it is "
                        + "recorded as if it had been predistorted");
        assertEquals(openedBefore, stub.openedPlaybacks(),
                "the lane was restarted rather than stopped");
    }

    /** The run the pane's Play would derive: a plain sine at the test's tone. */
    private GeneratorRun sineRun() {
        return new GeneratorRun(GenSignalForm.SINE, TONE_HZ, AMPLITUDE_VRMS, RATE_HZ,
                BIT_DEPTH, DITHER_BITS, DAC_FS_VOLTS, RIGHT_LANE_SCALE,
                OutputChannels.BOTH, false, true, null);
    }

    /** The DDS the stub's output line is playing right now. */
    private SignalGenerator lane() {
        SignalGenerator gen = stub.lastPlayedGenerator();
        assertTrue(gen != null, "the stub line never received a generator");
        return gen;
    }

    /** A single-tone correction file carrying ONE correction, at {@code harmonic}
     *  and {@link #HARMONIC_PCT} of the fundamental.  Its H1 row sits at the DDS's
     *  own start phase, so the delay estimate is zero and the written phases reach
     *  the generator unchanged. */
    private Path writeDpd(String name, int harmonic) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, """
                # Phonalyser DAC predistortion
                harmonic;frequency_hz;level_dbfs;amplitude_pct;phase_deg
                1;%s;-20.0;100.0;-90.0
                %d;%s;-40.0;%s;-90.0
                """.formatted(TONE_HZ, harmonic, harmonic * TONE_HZ, HARMONIC_PCT));
        return file;
    }

    /** {@code n} samples straight off the DDS - what the render thread would emit. */
    private double[] emit(SignalGenerator gen, int n) {
        double[] samples = new double[n];
        for (int i = 0; i < n; i++) {
            samples[i] = gen.nextSample();
        }
        return samples;
    }

    /** The amplitude of the {@code hz} component of {@code samples} (peak units) -
     *  a one-bin discrete transform, exact on the coherent window this test uses. */
    private double magnitudeAt(double[] samples, double hz) {
        double re = 0.0;
        double im = 0.0;
        double w = TWO * Math.PI * hz / RATE_HZ;
        for (int i = 0; i < samples.length; i++) {
            re += samples[i] * Math.cos(w * i);
            im += samples[i] * Math.sin(w * i);
        }
        return TWO * Math.hypot(re, im) / samples.length;
    }

    /** The {@code hz} component as a fraction of the fundamental - what a loaded
     *  single-tone correction set puts into the emitted waveform. */
    private double ratioAt(double[] samples, double hz) {
        return magnitudeAt(samples, hz) / magnitudeAt(samples, TONE_HZ);
    }

    /** The second harmonic's share of the fundamental. */
    private double harmonicRatio(double[] samples) {
        return ratioAt(samples, SECOND_HARMONIC * TONE_HZ);
    }
}
