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
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
 *
 * <p>The same fixture asks what a WAVEFORM change does to the pilot lines - the
 * two low-level extra tones the FFT's fundamental phase alignment reads as its
 * time base.  They belong to the analysis, which elects them and asks for them
 * again whenever it restarts its alignment, so a change that rebuilds the DDS
 * drops both together with the frequency-lock correction of the tone that
 * ended, and a change the DDS takes live leaves them where they are.
 */
class GeneratorLaneLiveChangeTest {

    private static final int RATE_HZ = 48_000;
    private static final int BIT_DEPTH = 24;
    private static final double TONE_HZ = 1_000.0;
    /** The second tone of the two-tone forms, Hz.  It comes from the settings,
     *  not from the run, so it is pinned in the fixture: what a rebuilt
     *  two-tone generator emits has to be the same in every run of this class.
     *  130 whole cycles of the {@link #COHERENT_SAMPLES} span, and far from the
     *  pilot line the tests read. */
    private static final double DUAL_TONE_2_HZ = 1_300.0;
    private static final double AMPLITUDE_VRMS = 0.1;
    /** DAC full scale of the run: 1.0 V, so an emitted peak IS the amplitude scale. */
    private static final double DAC_FS_VOLTS = 1.0;
    private static final double DITHER_BITS = 0.0;
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
    /** The first pilot line the tests ask the lane for - the low-level extra
     *  tones the FFT's fundamental phase alignment reads as its time base.
     *  15370 Hz is bin 1537 of the {@link #COHERENT_SAMPLES} window at 48 kHz,
     *  and it sits thousands of bins from every tone this test emits. */
    private static final double PILOT_HZ = 15_370.0;
    /** The second line of the pair, bin 1653 of the same window: 116 bins from
     *  the first and on no harmonic of the tones this test emits, so each line
     *  is read without the other. */
    private static final double PILOT_2_HZ = 16_530.0;
    /** The level the alignment elects its pilots at, dBV - one value for both
     *  lines. */
    private static final double PILOT_LEVEL_DBV = -100.0;
    /** What that level is worth in the emitted samples: the pilot's V RMS
     *  against the run's DAC full scale, at a sine's peak / RMS ratio - the
     *  pilot is added outside the lane's amplitude scaling, so its own level is
     *  all that decides it. */
    private static final double PILOT_AMPLITUDE =
            Math.pow(10.0, PILOT_LEVEL_DBV / 20.0) * Math.sqrt(TWO) / DAC_FS_VOLTS;
    /** A twentieth of the pilot's amplitude: far under what a dropped or
     *  re-levelled line would show, far over the windowed read's own error. */
    private static final double PILOT_TOLERANCE = PILOT_AMPLITUDE / 20.0;
    /** Samples emitted after the pilots are switched on before they are read:
     *  the generator fades a pilot's level in with a 50 ms time constant, and
     *  half a second at 48 kHz is ten of them - the remainder, e^-10, is far
     *  under {@link #PILOT_TOLERANCE}. */
    private static final int PILOT_SETTLE_SAMPLES = 24_000;
    /** The frequency-lock correction the tests put on tone 1 - one percent,
     *  orders above any real converter-pair mismatch, so a pilot carried on it
     *  lands far from its own frequency and the two are told apart. */
    private static final double TRIM_RATIO = 1.01;

    @TempDir
    private Path tempDir;

    private final GeneratorLane lane = new GeneratorLane();

    private DyingLaneStub stub;
    private BackendKey previousActive;
    private BackendKey previousSelection;
    private GenSignalForm previousForm;
    private String previousDpd;
    private double previousDualToneFreq2Hz;
    private double previousFrequencyHz;
    private boolean previousSnapToFftBin;

    @BeforeEach
    void armTheStubBackend() {
        Preferences prefs = Preferences.instance();
        prefs.setTransientMode(true);   // the lane writes through the live singleton
        previousSelection = prefs.getSelectedBackend();
        previousForm = prefs.getGenSignalForm();
        previousDpd = prefs.getGenDpd(GenSignalForm.SINE_COMP);
        previousDualToneFreq2Hz = prefs.getGenDualToneFreq2Hz();
        previousFrequencyHz = prefs.getGenFrequencyHz();
        previousSnapToFftBin = prefs.isGenSnapToFftBin();

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
        // The two-tone form reads its second frequency from the settings and
        // snaps it onto the analysis grid when the snap is on, so both are
        // pinned: what a two-tone generator emits here must not depend on what
        // another test left in the live settings.  The single tone's own
        // setting is pinned with them, because a frequency-lock trim is read
        // against it: the lane divides the corrected frequency by this one to
        // get the ratio it carries the pilot on.
        prefs.setGenDualToneFreq2Hz(DUAL_TONE_2_HZ);
        prefs.setGenFrequencyHz(TONE_HZ);
        prefs.setGenSnapToFftBin(false);
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
        prefs.setGenDualToneFreq2Hz(previousDualToneFreq2Hz);
        prefs.setGenFrequencyHz(previousFrequencyHz);
        prefs.setGenSnapToFftBin(previousSnapToFftBin);
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

    /**
     * The same rule for the DUAL-TONE compensated form: its slot is no more
     * optional than the sine's.  A compensated form IS its corrections, so a
     * two-tone pair emitted with none under the compensated label has its
     * INTERMODULATION measured and recorded as if it had been predistorted.
     * Both halves of the rule are asked here: a start with an empty slot is
     * refused (NEED_PREDISTORTION, no line opened), and a slot emptied while
     * that form plays ends the tone rather than dropping the corrections and
     * playing on.
     */
    @Test
    @DisplayName("the dual-tone compensated form refuses an empty slot and stops when it is emptied")
    void theDualToneCompensatedFormTreatsAnEmptySlotAsTheSineDoes() throws IOException {
        Preferences prefs = Preferences.instance();
        prefs.setGenDpd(GenSignalForm.DUAL_TONE_COMP, null);
        int openedBeforeRefusal = stub.openedPlaybacks();

        assertEquals(PlaybackStateEnum.NEED_PREDISTORTION, lane.start(dualCompRun()),
                "a compensated dual tone started with an empty slot would emit the plain "
                        + "two tones, and their intermodulation would be recorded as "
                        + "predistorted");
        assertEquals(openedBeforeRefusal, stub.openedPlaybacks(),
                "the refusal still opened an output line");

        prefs.setGenDpd(GenSignalForm.DUAL_TONE_COMP,
                writeDpd("dual-loaded.dpd", SECOND_HARMONIC).toString());
        assertEquals(PlaybackStateEnum.STARTED, lane.start(dualCompRun()));
        prefs.setGenSignalForm(GenSignalForm.DUAL_TONE_COMP);
        lane.setForm(GenSignalForm.DUAL_TONE_COMP);
        int openedBefore = stub.openedPlaybacks();

        prefs.setGenDpd(GenSignalForm.DUAL_TONE_COMP, null);
        lane.dpdChanged(GenSignalForm.DUAL_TONE_COMP);

        assertFalse(lane.isRunning(),
                "the compensated dual tone kept playing with no correction table");
        assertEquals(openedBefore, stub.openedPlaybacks(),
                "the lane was restarted rather than stopped");
    }

    /**
     * A waveform change the DDS cannot take live rebuilds the generator, and the
     * rebuilt tone comes up bare: no pilots, and no frequency-lock correction.
     * Entering or leaving the dual-tone family is such a change (a second tone
     * needs its own accumulator).  The pilots are the analysis's own lines - the
     * analysis elects them, and asks for them again as soon as it has restarted
     * its alignment on the changed signal - so re-issuing them from this side
     * would put lines on the new tone that nobody had asked for, and they would
     * carry the trim measured on the tone that has just ended while the tone
     * itself came up on its set frequency.
     */
    @Test
    @DisplayName("a waveform change that rebuilds the generator drops both pilots and the trim")
    void aRebuildingFormChangeDropsThePilots() {
        Preferences prefs = Preferences.instance();
        // The lane remembers the form it was last told about; pin it here, so
        // the change below is a rebuild whatever the settings held before.
        lane.formChanged(GenSignalForm.SINE, sineRun());
        assertEquals(PlaybackStateEnum.STARTED, lane.start(sineRun()));
        SignalGenerator before = lane();
        lane.setPilotTones(PILOT_HZ, PILOT_2_HZ, PILOT_LEVEL_DBV);
        lane.trimFrequency(TONE_HZ * TRIM_RATIO);   // a lock correction now stands on tone 1
        emit(before, PILOT_SETTLE_SAMPLES);
        double[] playing = emit(before, COHERENT_SAMPLES);
        assertEquals(PILOT_AMPLITUDE, pilotAmplitude(playing, PILOT_HZ * TRIM_RATIO),
                PILOT_TOLERANCE,
                "the first pilot never reached the tone that was playing, carried on tone 1's trim");
        assertEquals(PILOT_AMPLITUDE, pilotAmplitude(playing, PILOT_2_HZ * TRIM_RATIO),
                PILOT_TOLERANCE,
                "the second pilot never reached it on the same trim");

        prefs.setGenSignalForm(GenSignalForm.DUAL_TONE);
        lane.formChanged(GenSignalForm.DUAL_TONE, dualRun());

        SignalGenerator after = lane();
        assertNotSame(before, after,
                "entering the dual-tone family did not rebuild the DDS at all - the case this "
                        + "test is about never happened");
        double[] rebuilt = emit(after, COHERENT_SAMPLES);
        assertEquals(0.0, pilotAmplitude(rebuilt, PILOT_HZ), PILOT_TOLERANCE,
                "the rebuilt generator carries the first pilot, which nobody asked it for");
        assertEquals(0.0, pilotAmplitude(rebuilt, PILOT_2_HZ), PILOT_TOLERANCE,
                "the rebuilt generator carries the second pilot, which nobody asked it for");
        assertEquals(0.0, pilotAmplitude(rebuilt, PILOT_HZ * TRIM_RATIO), PILOT_TOLERANCE,
                "the rebuilt generator carries the first pilot at its trimmed position");
        assertEquals(0.0, pilotAmplitude(rebuilt, PILOT_2_HZ * TRIM_RATIO), PILOT_TOLERANCE,
                "the rebuilt generator carries the second pilot at its trimmed position");

        // The trim went with it: pilots asked for now are issued at their own
        // frequencies, not at the ratio the ended tone had been corrected by.
        lane.setPilotTones(PILOT_HZ, PILOT_2_HZ, PILOT_LEVEL_DBV);
        emit(after, PILOT_SETTLE_SAMPLES);
        double[] asked = emit(after, COHERENT_SAMPLES);
        assertEquals(PILOT_AMPLITUDE, pilotAmplitude(asked, PILOT_HZ), PILOT_TOLERANCE,
                "a first pilot asked of the rebuilt tone is not at the frequency it was asked for");
        assertEquals(PILOT_AMPLITUDE, pilotAmplitude(asked, PILOT_2_HZ), PILOT_TOLERANCE,
                "a second pilot asked of the rebuilt tone is not at the frequency it was asked for");
        assertEquals(0.0, pilotAmplitude(asked, PILOT_HZ * TRIM_RATIO), PILOT_TOLERANCE,
                "the rebuilt tone still carried the previous trim: the pilots rode a correction "
                        + "measured on a tone that had ended, while the tone itself came up on "
                        + "its set frequency");
        assertEquals(0.0, pilotAmplitude(asked, PILOT_2_HZ * TRIM_RATIO), PILOT_TOLERANCE,
                "the second pilot too was issued on the trim of the tone that had ended");
    }

    /**
     * A waveform change the DDS takes live leaves the pilots alone: nothing is
     * rebuilt, so there is nothing to put back, and both lines go on at their
     * elected frequencies and level.  Plain sine to its compensated sibling is
     * such a change - both render the same tone from the same accumulator - and
     * the pilots ride outside every predistortion kernel, so the corrections
     * that arrive with the form do not touch them either.
     */
    @Test
    @DisplayName("a live waveform change leaves both pilots where they are")
    void aLiveFormChangeLeavesThePilotsAlone() throws IOException {
        Preferences prefs = Preferences.instance();
        prefs.setGenDpd(GenSignalForm.SINE_COMP,
                writeDpd("live-swap.dpd", SECOND_HARMONIC).toString());
        lane.formChanged(GenSignalForm.SINE, sineRun());
        assertEquals(PlaybackStateEnum.STARTED, lane.start(sineRun()));
        SignalGenerator running = lane();
        lane.setPilotTones(PILOT_HZ, PILOT_2_HZ, PILOT_LEVEL_DBV);
        emit(running, PILOT_SETTLE_SAMPLES);
        int openedBefore = stub.openedPlaybacks();

        prefs.setGenSignalForm(GenSignalForm.SINE_COMP);
        lane.formChanged(GenSignalForm.SINE_COMP, sineCompRun());

        assertEquals(openedBefore, stub.openedPlaybacks(),
                "the compensated sibling was answered with a stop and a start - a live swap "
                        + "was expected, and this test then says nothing about one");
        assertSame(running, lane(), "and it rebuilt the DDS");
        double[] swapped = emit(running, COHERENT_SAMPLES);
        assertEquals(PILOT_AMPLITUDE, pilotAmplitude(swapped, PILOT_HZ),
                PILOT_TOLERANCE, "the live form swap moved the first pilot");
        assertEquals(PILOT_AMPLITUDE, pilotAmplitude(swapped, PILOT_2_HZ),
                PILOT_TOLERANCE, "the live form swap moved the second pilot");
    }

    /** The run the pane's Play would derive for the compensated dual tone. */
    private GeneratorRun dualCompRun() {
        return new GeneratorRun(GenSignalForm.DUAL_TONE_COMP, TONE_HZ, AMPLITUDE_VRMS, AMPLITUDE_VRMS, RATE_HZ,
                BIT_DEPTH, DITHER_BITS, DITHER_BITS, DAC_FS_VOLTS, DAC_FS_VOLTS,
                OutputChannels.BOTH, false, null);
    }

    /** The run the pane's Play would derive: a plain sine at the test's tone. */
    private GeneratorRun sineRun() {
        return new GeneratorRun(GenSignalForm.SINE, TONE_HZ, AMPLITUDE_VRMS, AMPLITUDE_VRMS, RATE_HZ,
                BIT_DEPTH, DITHER_BITS, DITHER_BITS, DAC_FS_VOLTS, DAC_FS_VOLTS,
                OutputChannels.BOTH, false, null);
    }

    /** The same for the compensated sibling - the run a live form swap is
     *  handed, and which it does not use because it rebuilds nothing. */
    private GeneratorRun sineCompRun() {
        return new GeneratorRun(GenSignalForm.SINE_COMP, TONE_HZ, AMPLITUDE_VRMS, AMPLITUDE_VRMS,
                RATE_HZ, BIT_DEPTH, DITHER_BITS, DITHER_BITS, DAC_FS_VOLTS, DAC_FS_VOLTS,
                OutputChannels.BOTH, false, null);
    }

    /** The run for the plain two-tone form: tone 1 is the test's tone, tone 2
     *  comes from the settings the way the pane's Play leaves it. */
    private GeneratorRun dualRun() {
        return new GeneratorRun(GenSignalForm.DUAL_TONE, TONE_HZ, AMPLITUDE_VRMS, AMPLITUDE_VRMS,
                RATE_HZ, BIT_DEPTH, DITHER_BITS, DITHER_BITS, DAC_FS_VOLTS, DAC_FS_VOLTS,
                OutputChannels.BOTH, false, null);
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
        double[] frame = new double[2];
        double[] samples = new double[n];
        for (int i = 0; i < n; i++) {
            gen.nextFrame(frame);
            samples[i] = frame[0];
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

    /** The amplitude of the {@code hz} component of {@code samples} (peak
     *  units), read through a Hann window - the pilot's own frequency, or the
     *  position a trim would have carried it to.
     *
     *  <p>The window is what makes the reading the pilot's own.  The pilot sits
     *  some 80 dB under the main tone, and the second tone of a dual pair comes
     *  from the settings at whatever frequency they hold - a frequency that
     *  need not complete a whole number of periods in this span.  The leakage
     *  of such a tone into a rectangular one-bin read a thousand bins away
     *  falls only as 1/distance and still exceeds the pilot; under the Hann
     *  window it falls three orders faster and is gone.  The pilot itself sits
     *  on a whole bin of the span, where the windowed read returns its
     *  amplitude exactly; a trimmed position lands between bins, where the read
     *  is up to a sixth low - far inside what tells a line from none. */
    private double pilotAmplitude(double[] samples, double hz) {
        int n = samples.length;
        double re = 0.0;
        double im = 0.0;
        double sumWin = 0.0;
        double w = TWO * Math.PI * hz / RATE_HZ;
        for (int i = 0; i < n; i++) {
            double win = 0.5 - 0.5 * Math.cos(TWO * Math.PI * i / n);
            sumWin += win;
            re += win * samples[i] * Math.cos(w * i);
            im += win * samples[i] * Math.sin(w * i);
        }
        return TWO * Math.hypot(re, im) / sumWin;
    }
}
