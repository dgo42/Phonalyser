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

package org.edgo.audio.measure.net.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.edgo.audio.measure.common.Constants;
import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.net.proto.BinaryFrame;
import org.edgo.audio.measure.net.proto.ErrorCode;
import org.edgo.audio.measure.net.proto.FrameType;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetMessage;
import org.edgo.audio.measure.sound.AudioBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The remote generator of spec 4.5, driven without a DAC and without a clock:
 * {@link StubPlayback} says what the DDS actually emitted, the injected clock
 * says where a sweep is, and {@link FakeChannel} keeps the state events.
 *
 * <p>The two properties worth testing are the ones a client cannot check for
 * itself.  First, that {@code gen.config} really is a PARTIAL update - a field
 * the client did not send must come out the far end unchanged, and the only way
 * to see that is the state payload it is built from.  Second, that a snap or a
 * trim reaches the oscillator: the commanded number is trivial to echo back, so
 * the emitted frequency is counted off the rendered waveform instead.
 */
class GeneratorSessionTest {

    private static final int RATE_HZ = StubDeviceManager.RATE_48K;
    private static final int BITS = StubDeviceManager.BITS_24;
    private static final double DITHER_BITS = 1.5;
    private static final int DEVICE_INDEX = 0;
    private static final int FIRST_GEN_ID = 1;
    private static final int UNOPENED_GEN_ID = 7;
    /** A tone far from every default, so an assertion cannot pass by accident. */
    private static final double TONE_HZ = 997.0;
    private static final double AMPLITUDE_VRMS = 0.5;
    private static final double QUIETER_VRMS = 0.25;
    private static final double DAC_FS_VOLTAGE_AMPL = 2.0;
    private static final double SECOND_TONE_HZ = 1_497.0;
    private static final double FIRST_SPLIT_PCT = 70.0;
    private static final double SECOND_SPLIT_PCT = 30.0;
    private static final double RECTANGLE_DUTY = 0.3;
    private static final double TRIANGLE_DUTY = 0.7;
    /** 48 000 / 4 096 = 11.718 75 Hz bins; 997 Hz sits at bin 85.08. */
    private static final int FFT_SIZE = 4_096;
    private static final double SNAPPED_TONE_HZ = 996.09375;
    private static final double TRIMMED_HZ = 1_200.0;
    /** One second of audio - the window the emitted frequency is counted over,
     *  so the zero-crossing estimate lands within a hertz. */
    private static final int MEASURE_SAMPLES = RATE_HZ;
    private static final double HZ_TOLERANCE = 2.0;
    private static final double EXACT = 0.0;
    private static final double EPS = 1e-9;
    /** How close a rendered peak counted over whole cycles comes to the analytic
     *  amplitude: a 997 Hz tone sampled at 48 kHz misses its crest by up to half
     *  a sample step. */
    private static final double AMPLITUDE_EPS = 1e-3;
    /** The card a full-scale test binds to the bench's OUTPUT device.  Its two
     *  channels are unequal on purpose - that is what makes the right lane's
     *  scale a number and not just 1.0. */
    private static final String CARD_NAME = "Bench DAC";
    private static final double CARD_FS_LEFT = 4.0;
    private static final double CARD_FS_RIGHT = 5.0;

    private static final double SWEEP_F0_HZ = 20.0;
    private static final double SWEEP_F1_HZ = 20_000.0;
    private static final int SWEEP_SAMPLES = 96_000;
    /** A two-octave chirp, so WHERE it is can be read straight off the emitted
     *  waveform: 5 kHz at the start, 20 kHz two seconds later. */
    private static final double CHIRP_F0_HZ = 5_000.0;
    private static final double CHIRP_F1_HZ = 20_000.0;
    /** Half of {@link #SWEEP_SAMPLES} - one second in, where the chirp is at
     *  5 kHz · 4^0.5 = 10 kHz. */
    private static final int HALF_CHIRP_SAMPLES = 48_000;
    /** The 0.1 s window the chirp's frequency is counted over, and what it
     *  averages there (10.36 kHz sweeping on).  A chirp that restarted would
     *  read 5.2 kHz - the two cannot be confused. */
    private static final int CHIRP_WINDOW_SAMPLES = 4_800;
    private static final double MID_CHIRP_HZ = 10_360.0;
    private static final double CHIRP_TOLERANCE_HZ = 800.0;
    /** The shortest sweep the generator will build - what a sweep started
     *  without a {@code durationSamples} actually emits. */
    private static final int FLOOR_SWEEP_SAMPLES = 2;
    private static final int LEAD_IN_SAMPLES = 4_800;
    private static final int FADE_SAMPLES = 480;
    /** Half a second on the injected clock - a quarter of the way through the
     *  two-second sweep. */
    private static final int HALF_SECOND_MS = 500;
    private static final long QUARTER_SWEEP_SAMPLES = 24_000L;
    /** Spec 4.5 caps the position push at 10 Hz, which is this period. */
    private static final long MIN_STATE_PERIOD_MS = 100;
    private static final int TICKS = 3;

    /** 24-bit stereo, two frames - one capture batch. */
    private static final int CAPTURE_BATCH_BYTES = 12;
    private static final int FIRST = 0;
    private static final int SECOND = 1;

    /** Sample rate and depth of {@link #silentWav()} - deliberately neither the
     *  rate nor the depth {@code gen.open} granted, so a lane that failed to
     *  reopen at the file's own format is visible. */
    private static final int WAV_RATE_HZ = StubDeviceManager.RATE_96K;
    private static final int WAV_BITS = 16;
    private static final int WAV_FRAMES = 128;
    /** Canonical WAV header length, and what the {@code RIFF} size field counts
     *  besides the audio. */
    private static final int WAV_HEADER_BYTES = 44;
    private static final int WAV_RIFF_OVERHEAD = 36;
    private static final int WAV_FMT_CHUNK_BYTES = 16;
    private static final short WAV_PCM = 1;
    private static final short WAV_MONO = 1;
    private static final int BITS_PER_BYTE = 8;

    private final LockRegistry locks = new LockRegistry();
    private final JsonCodec codec = new JsonCodec();
    /** This bench's device cards - empty unless a test binds one, which is what
     *  makes the 1.0 fallback of {@code gen.open} and a card-backed open two
     *  different tests instead of one that depends on the host. */
    private final StubCardStore cards = new StubCardStore();
    private final DeviceCatalog catalog = new DeviceCatalog(AudioBackend.instance(), locks,
            codec, List.of(AudioBackendType.QA40X), cards.getPrefs());
    private final FakeChannel channel = new FakeChannel();
    private final FakeTicker ticker = new FakeTicker();
    private final FileStore files = new FileStore();
    private final Qa40xGuard qa40x = new Qa40xGuard(AudioBackend.instance(), locks);
    private final CaptureStreamer captures = new CaptureStreamer(AudioBackend.instance(),
            catalog, codec, channel, new FakeWorker(), qa40x);
    /** The injected wall clock, hand-cranked exactly as {@link PeerTable}'s is -
     *  the sweep and file positions are elapsed time, and a sleeping test would
     *  be both slow and flaky. */
    private long nowMs;
    private final GeneratorSession generator = new GeneratorSession(AudioBackend.instance(),
            catalog, captures, files, qa40x, codec, channel, new FakeWorker(), ticker,
            () -> nowMs);

    private final DeviceLock output =
            new DeviceLock(AudioBackendType.QA40X, DEVICE_INDEX, false);
    private final DeviceLock input =
            new DeviceLock(AudioBackendType.QA40X, DEVICE_INDEX, true);

    @AfterEach
    void giveTheBenchBack() {
        generator.closeAll();
        captures.closeAll();
    }

    // -------------------------------------------------------------------------
    // gen.open / gen.close
    // -------------------------------------------------------------------------

    @Test
    void openAnswersAHandleAndTheRateAndLeavesTheLineSilent() {
        JsonNode data = open();

        assertEquals(FIRST_GEN_ID, data.path(NetFields.GEN_ID).asInt());
        assertEquals(RATE_HZ, data.path(NetFields.RATE).asInt());
        assertTrue(playback().isOpened());
        assertEquals(0, playback().getPlayCount(),
                "spec 4.5: gen.open creates the generator SILENT until gen.start");
        assertEquals(OutputChannels.LEFT, playback().getOutputChannels(),
                "the lane gate gen.open asked for reaches the line at the open, not "
                        + "at the first start");
    }

    @Test
    void openOnAnInputRefIsRefused() {
        NetException refused = assertThrows(NetException.class, () -> generator.open(input,
                StubDeviceManager.FIRST_INPUT, RATE_HZ, BITS, DITHER_BITS, null));

        assertEquals(ErrorCode.BAD_REQUEST, refused.getCode());
    }

    @Test
    void aSecondOpenOnTheSameConnectionIsRefused() {
        open();

        NetException refused = assertThrows(NetException.class, this::open);

        assertEquals(ErrorCode.BAD_REQUEST, refused.getCode(),
                "one generator per connection: a second handle would answer a client "
                        + "that then has no way of knowing which one it drives");
    }

    @Test
    void aDeviceNameThatMovedIsRefusedBeforeTheLineIsTouched() {
        NetException refused = assertThrows(NetException.class, () -> generator.open(output,
                "Some other card", RATE_HZ, BITS, DITHER_BITS, null));

        assertEquals(ErrorCode.DEVICE_STALE, refused.getCode());
    }

    @Test
    void everyCommandButOpenNeedsTheHandleThatIsActuallyOpen() {
        open();

        NetException refused = assertThrows(NetException.class,
                () -> generator.start(UNOPENED_GEN_ID));

        assertEquals(ErrorCode.BAD_REQUEST, refused.getCode());
    }

    @Test
    void closeStopsEmissionAndGivesTheLineAndTheClockClaimBack() {
        open();
        configureTone();
        generator.start(FIRST_GEN_ID);
        StubPlayback lane = playback();

        generator.close(FIRST_GEN_ID);

        assertTrue(lane.isClosed(), "spec 4.5: gen.close closes server-side playback");
        assertEquals(FIRST_GEN_ID, captures.open(input, StubDeviceManager.FIRST_INPUT,
                StubDeviceManager.RATE_96K, BITS).path(NetFields.CAPTURE_ID).asInt(),
                "and the QA40x rate claim went with it - a capture at another rate "
                        + "could not be opened at all while the lane still held one");
    }

    @Test
    void aLaneOnADeviceThisBenchHasNoCardForOpensAtTheUnitFullScale() {
        open();
        generator.config(FIRST_GEN_ID, config()
                .put(NetFields.FORM, GenSignalForm.SINE.name())
                .put(NetFields.FREQUENCY, TONE_HZ)
                .put(NetFields.AMPLITUDE_VRMS, AMPLITUDE_VRMS));
        generator.start(FIRST_GEN_ID);

        assertEquals(AMPLITUDE_VRMS * Constants.SQRT2, peakOf(playback()), AMPLITUDE_EPS,
                "with no card the divisor is 1.0 (spec 4.5), so the normalised peak "
                        + "IS the commanded amplitude in its peak form");
        assertEquals(1.0, playback().getRightLaneScale(), EXACT,
                "and both lanes are alike");
    }

    @Test
    void aLaneOnACardedDeviceOpensAtTheBenchsOwnFullScale() {
        cards.card(CARD_NAME, StubDeviceManager.OUTPUT, false, CARD_FS_LEFT, CARD_FS_RIGHT,
                false);

        open();
        generator.config(FIRST_GEN_ID, config()
                .put(NetFields.FORM, GenSignalForm.SINE.name())
                .put(NetFields.FREQUENCY, TONE_HZ)
                .put(NetFields.AMPLITUDE_VRMS, AMPLITUDE_VRMS));
        generator.start(FIRST_GEN_ID);

        // dacFsVoltageAmpl = fsLeft × √2, and the DDS divides the commanded
        // amplitude by it - so the peak the lane really renders says which of
        // the two the generator was built with.
        assertEquals(AMPLITUDE_VRMS / CARD_FS_LEFT, peakOf(playback()), AMPLITUDE_EPS,
                "spec 4.5 v1.1: the lane starts at the full scale the SERVER stores "
                        + "for its own device, not at the 1.0 placeholder");
        assertEquals(CARD_FS_LEFT / CARD_FS_RIGHT, playback().getRightLaneScale(), EPS,
                "and the right lane carries fsLeft/fsRight, so a card whose two DACs "
                        + "differ emits the same physical level on both");
    }

    @Test
    void theSessionCarriesTheNamesTheEmissionLinesPrint() {
        cards.card(CARD_NAME, StubDeviceManager.OUTPUT, false, CARD_FS_LEFT, CARD_FS_RIGHT,
                false);

        open();

        assertEquals(StubDeviceManager.OUTPUT, generator.getDeviceName(),
                "the emission lines print the device's human name - the lock only "
                        + "knows backend and index");
        assertEquals(CARD_NAME, generator.getCardName(),
                "the card IN FORCE at open time is what 'emission started/stopped "
                        + "(card ...)' names; unbound stays null and renders as 'none'");
    }

    @Test
    void aLaneOnAnUncalibratedCardOpensAtTheUnitFullScaleToo() {
        cards.card(CARD_NAME, StubDeviceManager.OUTPUT, false, 0.0, 0.0, false);

        open();
        generator.config(FIRST_GEN_ID, config()
                .put(NetFields.FORM, GenSignalForm.SINE.name())
                .put(NetFields.FREQUENCY, TONE_HZ)
                .put(NetFields.AMPLITUDE_VRMS, AMPLITUDE_VRMS));
        generator.start(FIRST_GEN_ID);

        assertEquals(AMPLITUDE_VRMS * Constants.SQRT2, peakOf(playback()), AMPLITUDE_EPS,
                "a card row that was never calibrated carries zeros, and the full "
                        + "scale is the DIVISOR of the amplitude scale - so it is an "
                        + "uncalibrated device and the 1.0 placeholder is what it gets, "
                        + "not a division by zero rendered into the DAC");
        assertEquals(1.0, playback().getRightLaneScale(), EXACT,
                "and the lane ratio stays finite");
    }

    /**
     * A client's own full scale is IGNORED: the card of the machine the DAC is
     * plugged into is the only converter.
     *
     * <p>Input and output full-scale calibration is applied ON THE SERVER.
     * While a client could push its own number over the bench's card, the same
     * amplitude entered on two clients came out of the same output at two
     * different levels, and a server-side card edit changed nothing on a client
     * that pushed.  Both belts are in place: the clients no longer send the
     * field, and this proves the server would ignore it if one did.
     */
    @Test
    void aClientsOwnFullScaleIsIgnoredWhenTheBenchHasACard() {
        cards.card(CARD_NAME, StubDeviceManager.OUTPUT, false, CARD_FS_LEFT, CARD_FS_RIGHT,
                false);

        open();
        configureTone();                          // still sends dacFsVoltageAmpl = 2.0
        generator.start(FIRST_GEN_ID);

        assertEquals(AMPLITUDE_VRMS / CARD_FS_LEFT, peakOf(playback()), AMPLITUDE_EPS,
                "the BENCH's card converts, whatever the client sent");
        assertEquals(CARD_FS_LEFT / CARD_FS_RIGHT, playback().getRightLaneScale(), EPS,
                "and the lane ratio is the card's too");
    }

    @Test
    void theQa40xClockCoversTheGeneratorLaneToo() {
        captures.open(input, StubDeviceManager.FIRST_INPUT, RATE_HZ, BITS);

        NetException refused = assertThrows(NetException.class,
                () -> generator.open(output, StubDeviceManager.OUTPUT,
                        StubDeviceManager.RATE_96K, BITS, DITHER_BITS, null));

        assertEquals(ErrorCode.BAD_REQUEST, refused.getCode(),
                "spec 4.4 names gen.open beside capture.open: one divider clocks both "
                        + "directions, so an output at another rate would re-clock the "
                        + "capture already running");
    }

    // -------------------------------------------------------------------------
    // gen.config - the partial-update matrix
    // -------------------------------------------------------------------------

    @Test
    void configAppliesOnlyTheFieldsThatArePresent() {
        open();
        generator.config(FIRST_GEN_ID, config()
                .put(NetFields.FORM, GenSignalForm.RECTANGLE.name())
                .put(NetFields.FREQUENCY, TONE_HZ)
                .put(NetFields.AMPLITUDE_VRMS, AMPLITUDE_VRMS)
                .put(NetFields.DAC_FS_VOLTAGE_AMPL, DAC_FS_VOLTAGE_AMPL)
                .put(NetFields.RECTANGLE_DUTY, RECTANGLE_DUTY)
                .put(NetFields.TRIANGLE_DUTY, TRIANGLE_DUTY));

        // A second update naming ONE field must leave every other one alone.
        generator.config(FIRST_GEN_ID, config().put(NetFields.AMPLITUDE_VRMS, QUIETER_VRMS));

        JsonNode state = generator.state(FIRST_GEN_ID);
        assertEquals(GenSignalForm.RECTANGLE.name(), state.path(NetFields.FORM).asText(),
                "spec 4.5: only present fields are applied - an absent form is not a "
                        + "request to go back to a sine");
        assertEquals(TONE_HZ, state.path(NetFields.NOMINAL_HZ).asDouble(), EXACT);
        assertEquals(QUIETER_VRMS, state.path(NetFields.AMPLITUDE_VRMS).asDouble(), EXACT,
                "and the one field that WAS present did change");
    }

    @Test
    void aDualToneSubObjectIsAPartialUpdateOfItsOwn() {
        open();
        generator.config(FIRST_GEN_ID, config()
                .put(NetFields.FORM, GenSignalForm.DUAL_TONE.name())
                .put(NetFields.FREQUENCY, TONE_HZ)
                .put(NetFields.DUAL, dual()));

        ObjectNode splitOnly = JsonNodeFactory.instance.objectNode();
        splitOnly.put(NetFields.AMP1_PCT, SECOND_SPLIT_PCT);
        generator.config(FIRST_GEN_ID, config().put(NetFields.DUAL, splitOnly));

        assertEquals(SECOND_TONE_HZ,
                generator.state(FIRST_GEN_ID).path(NetFields.EMIT2_HZ).asDouble(), EXACT,
                "frequency2 was absent from the second update, so the second tone "
                        + "stayed where the first update put it");
    }

    @Test
    void aSweepSubObjectCarriesTheWholeSweepAndSurvivesAnUnrelatedUpdate() {
        open();
        configureSweep(GenSignalForm.LOG_SWEEP);

        generator.config(FIRST_GEN_ID, config().put(NetFields.AMPLITUDE_VRMS, QUIETER_VRMS));

        JsonNode sweep = generator.state(FIRST_GEN_ID).path(NetFields.SWEEP);
        assertEquals(SWEEP_SAMPLES, sweep.path(NetFields.DURATION_SAMPLES).asInt());
        assertFalse(sweep.path(NetFields.LOOP).asBoolean());
    }

    @Test
    void anUpdateThatNamesNoSweepFieldDoesNotRestartTheChirp() {
        open();
        configureChirp();
        generator.start(FIRST_GEN_ID);
        playback().render(HALF_CHIRP_SAMPLES);

        generator.config(FIRST_GEN_ID, config().put(NetFields.AMPLITUDE_VRMS, QUIETER_VRMS));

        assertEquals(MID_CHIRP_HZ, playback().measuredHz(CHIRP_WINDOW_SAMPLES),
                CHIRP_TOLERANCE_HZ,
                "spec 4.5: only PRESENT fields are applied.  Re-sending the sweep "
                        + "block re-renders the chirp and rewinds the DDS to sample 0, "
                        + "so a freq-response client trimming its level mid-sweep would "
                        + "assemble its record over a sweep that silently began again - "
                        + "and with no sweepStart marker to notice it by");
    }

    @Test
    void aCompensationTableReachesTheGeneratorThatIsAlreadyRunning() {
        open();
        generator.config(FIRST_GEN_ID, config()
                .put(NetFields.FORM, GenSignalForm.SINE_COMP.name())
                .put(NetFields.FREQUENCY, TONE_HZ)
                .put(NetFields.AMPLITUDE_VRMS, AMPLITUDE_VRMS)
                .put(NetFields.DAC_FS_VOLTAGE_AMPL, DAC_FS_VOLTAGE_AMPL));
        generator.start(FIRST_GEN_ID);
        double uncorrected = peakOf(playback().render(MEASURE_SAMPLES));

        generator.config(FIRST_GEN_ID,
                config().put(NetFields.COMPENSATION, compensation(STRONG_HARMONIC_RATIO)));

        assertTrue(peakOf(playback().render(MEASURE_SAMPLES))
                        > uncorrected * MIN_PEAK_GROWTH,
                "the predistortion loop measures its own correction: a table the "
                        + "server acknowledged but never handed to the running DDS "
                        + "would have it see the same distortion round after round, "
                        + "while SignalGenerator.applyCompensation exists precisely to "
                        + "be hot-swapped with no audio restart");
    }

    @Test
    void anUnknownWaveformIsABadRequestRatherThanASilentFallback() {
        open();

        NetException refused = assertThrows(NetException.class, () -> generator.config(
                FIRST_GEN_ID, config().put(NetFields.FORM, "SAWTOOTH_OF_THESEUS")));

        assertEquals(ErrorCode.BAD_REQUEST, refused.getCode());
    }

    @Test
    void aCompensationTableMustArriveComplete() {
        open();
        ObjectNode partial = JsonNodeFactory.instance.objectNode();
        partial.putArray(NetFields.AMP_RATIOS).add(SECOND_HARMONIC_RATIO);

        NetException refused = assertThrows(NetException.class, () -> generator.config(
                FIRST_GEN_ID, config().put(NetFields.COMPENSATION, partial)));

        assertEquals(ErrorCode.BAD_REQUEST, refused.getCode(),
                "a phasor is an amplitude AND a harmonic AND a phase - half a table "
                        + "would predistort into the wrong bin");
    }

    @Test
    void clearingTheCompensationTakesTheFormBackToItsPlainTone() {
        open();
        generator.config(FIRST_GEN_ID, config()
                .put(NetFields.FORM, GenSignalForm.SINE_COMP.name())
                .put(NetFields.COMPENSATION, compensation()));

        generator.config(FIRST_GEN_ID, config().put(NetFields.CLEAR_COMPENSATION, true));

        assertEquals(GenSignalForm.SINE.name(),
                generator.state(FIRST_GEN_ID).path(NetFields.FORM).asText());
    }

    // -------------------------------------------------------------------------
    // The bin snap and the trims, measured off the emitted waveform
    // -------------------------------------------------------------------------

    @Test
    void theBinSnapMovesTheEmittedToneOntoABinCentre() {
        open();
        configureTone();
        generator.fftGrid(FIRST_GEN_ID, FFT_SIZE, true);
        generator.start(FIRST_GEN_ID);

        assertEquals(SNAPPED_TONE_HZ,
                generator.state(FIRST_GEN_ID).path(NetFields.EMIT_HZ).asDouble(), EXACT,
                "spec 4.5: k·rate/fftSize, the SAME math the client snaps with");
        assertEquals(SNAPPED_TONE_HZ, playback().measuredHz(MEASURE_SAMPLES), HZ_TOLERANCE,
                "and the oscillator really is driven at it - a snap the state reports "
                        + "but the DDS never heard would put the client's whole "
                        + "frequency-lock loop half a bin out");
    }

    @Test
    void aTrimOverridesTheNominalAndReachesTheOscillator() {
        open();
        configureTone();
        generator.fftGrid(FIRST_GEN_ID, FFT_SIZE, true);
        generator.start(FIRST_GEN_ID);

        generator.trim(FIRST_GEN_ID, TRIMMED_HZ);

        JsonNode state = generator.state(FIRST_GEN_ID);
        assertEquals(TONE_HZ, state.path(NetFields.NOMINAL_HZ).asDouble(), EXACT,
                "the nominal is what the operator typed, and a trim never rewrites it");
        assertEquals(TRIMMED_HZ, state.path(NetFields.EMIT_HZ).asDouble(), EXACT);
        assertEquals(TRIMMED_HZ, playback().measuredHz(MEASURE_SAMPLES), HZ_TOLERANCE,
                "spec 4.5: the servo lives client-side, the ACTUATOR is here");
    }

    @Test
    void trimResetSlidesTheToneBackOntoItsSnappedNominal() {
        open();
        configureTone();
        generator.fftGrid(FIRST_GEN_ID, FFT_SIZE, true);
        generator.start(FIRST_GEN_ID);
        generator.trim(FIRST_GEN_ID, TRIMMED_HZ);

        generator.trimReset(FIRST_GEN_ID);

        assertEquals(SNAPPED_TONE_HZ, playback().measuredHz(MEASURE_SAMPLES), HZ_TOLERANCE);
    }

    @Test
    void aNewNominalDropsTheTrimThatBelongedToTheOldTone() {
        open();
        configureTone();
        generator.start(FIRST_GEN_ID);
        generator.trim(FIRST_GEN_ID, TRIMMED_HZ);

        generator.config(FIRST_GEN_ID, config().put(NetFields.FREQUENCY, SECOND_TONE_HZ));

        assertEquals(SECOND_TONE_HZ,
                generator.state(FIRST_GEN_ID).path(NetFields.EMIT_HZ).asDouble(), EXACT,
                "the trim was an ABSOLUTE correction computed for the tone that WAS "
                        + "playing; keeping it across a retune would pin the generator "
                        + "to the old one and the servo would fight its way back");
        assertEquals(SECOND_TONE_HZ, playback().measuredHz(MEASURE_SAMPLES), HZ_TOLERANCE);
    }

    @Test
    void aTrimWithoutAFiniteFrequencyIsRefused() {
        open();

        NetException refused = assertThrows(NetException.class,
                () -> generator.trim(FIRST_GEN_ID, null));

        assertEquals(ErrorCode.BAD_REQUEST, refused.getCode());
    }

    // -------------------------------------------------------------------------
    // The sweepStart marker of spec 4.5
    // -------------------------------------------------------------------------

    @Test
    void startingASweepInjectsExactlyOneMarkerIntoEveryOpenCaptureStream() {
        int captureId = openCapture();
        open();
        configureSweep(GenSignalForm.LOG_SWEEP);

        generator.start(FIRST_GEN_ID);

        List<BinaryFrame> markers = markerFrames();
        assertEquals(1, markers.size(), "spec 4.5: one marker per sweep start");
        assertEquals(captureId, markers.get(FIRST).streamId());
        assertEquals(BinaryFrame.MARKER_SWEEP_START, markers.get(FIRST).n(),
                "spec 5: MARKER n is the marker KIND, and kind 1 is sweepStart");
        assertEquals(0, markers.get(FIRST).packetCounter(),
                "it takes a packet number of its own - spec 5 counts every frame "
                        + "type, and a hole would read as transport loss");
    }

    @Test
    void aSteadyToneInjectsNoMarkerAtAll() {
        openCapture();
        open();
        configureTone();

        generator.start(FIRST_GEN_ID);

        assertEquals(0, markerFrames().size(),
                "spec 4.5 ties the marker to STARTING A SWEEP; a marker on every "
                        + "start would have a freq-response client assembling a record "
                        + "around a tone that never swept");
    }

    @Test
    void theMarkerLandsBehindTheAudioCapturedBeforeTheSweepStarted() {
        openCapture();
        managerFor().getLastCapture().feed(new byte[CAPTURE_BATCH_BYTES]);
        open();
        configureSweep(GenSignalForm.LOG_SWEEP);

        generator.start(FIRST_GEN_ID);

        List<BinaryFrame> frames = channel.getFrames();
        assertEquals(FrameType.PCM, frames.get(FIRST).type());
        assertEquals(FrameType.MARKER, frames.get(SECOND).type(),
                "spec 5: the first PCM byte AFTER the marker is the one aligned with "
                        + "sweep sample 0, so audio captured before the sweep started "
                        + "may not be overtaken by it");
    }

    @Test
    void aSweepThatBeginsBecauseTheFormChangedIsAnnouncedToo() {
        openCapture();
        open();
        configureTone();
        generator.start(FIRST_GEN_ID);
        assertEquals(0, markerFrames().size(), "a steady tone announces nothing");

        configureSweep(GenSignalForm.LOG_SWEEP);

        assertEquals(1, markerFrames().size(),
                "the chirp starts at sample 0 whether gen.start or gen.config asked "
                        + "for it, and spec 6's freq-response client is waiting for the "
                        + "marker that bounds its record either way");
    }

    // -------------------------------------------------------------------------
    // gen.state and the ev.gen.state push
    // -------------------------------------------------------------------------

    @Test
    void theStatePayloadIsTheOneSpecFourFiveWrites() {
        open();
        configureTone();
        generator.start(FIRST_GEN_ID);

        JsonNode state = generator.state(FIRST_GEN_ID);

        assertEquals("{\"genId\":1,\"running\":true,\"form\":\"SINE\","
                + "\"nominalHz\":997.0,\"emitHz\":997.0,\"emit2Hz\":0.0,"
                + "\"amplitudeVrms\":0.5,\"sweep\":{\"active\":false,\"posSamples\":0,"
                + "\"durationSamples\":0,\"loop\":true},\"file\":{\"playing\":false,"
                + "\"posSamples\":0,\"finished\":false,\"rate\":0,\"bits\":0}}",
                state.toString(),
                "golden payload: the field NAMES and the nesting are the contract two "
                        + "independently written clients bind against, and a "
                        + "one-character drift is a silent no-op rather than an error");
        assertEquals(0.0, state.path(NetFields.EMIT2_HZ).asDouble(), EXACT,
                "spec 4.5: emitHz/emit2Hz are the frequencies ACTUALLY EMITTED, and a "
                        + "single tone emits no second one - a hint consumer told 1 kHz "
                        + "would draw a phantom line the DAC never carried");
    }

    @Test
    void aPullAndAPushDescribeTheGeneratorIdentically() {
        open();
        configureTone();
        generator.start(FIRST_GEN_ID);

        List<NetMessage> pushed = channel.events(MessageType.EV_GEN_STATE);
        assertFalse(pushed.isEmpty(), "spec 4.5: pushed on every state change");
        NetMessage last = pushed.get(pushed.size() - 1);
        JsonNode pull = generator.state(FIRST_GEN_ID);
        for (Iterator<String> fields = pull.fieldNames(); fields.hasNext();) {
            String field = fields.next();
            assertEquals(pull.get(field).toString(), last.getNode(field).toString(),
                    "spec 4.5: gen.state answers the SAME payload the event carries - "
                            + field);
        }
    }

    @Test
    void theSweepPositionFollowsTheClockAndTheTickerStaysUnderTenHertz() {
        open();
        configureSweep(GenSignalForm.LINEAR_SWEEP);
        generator.start(FIRST_GEN_ID);

        nowMs += HALF_SECOND_MS;
        JsonNode sweep = generator.state(FIRST_GEN_ID).path(NetFields.SWEEP);

        assertTrue(sweep.path(NetFields.ACTIVE).asBoolean());
        assertEquals(QUARTER_SWEEP_SAMPLES, sweep.path(NetFields.POS_SAMPLES).asLong(),
                "half a second at 48 kHz is 24 000 samples of a 96 000-sample sweep");
        assertTrue(ticker.getPeriodMs() >= MIN_STATE_PERIOD_MS,
                "spec 4.5: at most 10 Hz while a sweep or a file is running");
    }

    @Test
    void theGeneratorGoingAwayIsAStateChangeLikeAnyOther() {
        open();
        configureTone();
        generator.start(FIRST_GEN_ID);

        generator.close(FIRST_GEN_ID);

        List<NetMessage> pushed = channel.events(MessageType.EV_GEN_STATE);
        NetMessage last = pushed.get(pushed.size() - 1);
        assertEquals(FIRST_GEN_ID, last.getNode(NetFields.GEN_ID).asInt());
        assertFalse(last.getNode(NetFields.RUNNING).asBoolean(),
                "spec 4.5 pushes on EVERY state change, and a generator that stopped "
                        + "and gave its line back is the biggest one: on a "
                        + "device.release the connection stays alive, so without this "
                        + "the client is never told the tone it thinks is playing "
                        + "has gone");
    }

    @Test
    void aSweepStartedWithoutADurationReportsTheOneItIsEmitting() {
        open();
        ObjectNode oneShot = JsonNodeFactory.instance.objectNode();
        oneShot.put(NetFields.LOOP, false);
        generator.config(FIRST_GEN_ID, config()
                .put(NetFields.FORM, GenSignalForm.LINEAR_SWEEP.name())
                .put(NetFields.AMPLITUDE_VRMS, AMPLITUDE_VRMS)
                .put(NetFields.SWEEP, oneShot));
        generator.start(FIRST_GEN_ID);

        nowMs += HALF_SECOND_MS;

        JsonNode sweep = generator.state(FIRST_GEN_ID).path(NetFields.SWEEP);
        assertEquals(FLOOR_SWEEP_SAMPLES, sweep.path(NetFields.DURATION_SAMPLES).asInt(),
                "the generator was built at the two-sample floor, so that is the "
                        + "duration that is emitting - a client told 0 would size its "
                        + "record off a length nothing is playing");
        assertEquals(FLOOR_SWEEP_SAMPLES, sweep.path(NetFields.POS_SAMPLES).asLong(),
                "and the position is measured against that same cycle instead of "
                        + "standing at zero for ever");
    }

    @Test
    void aSteadyToneIsNotPushedTenTimesASecond() {
        open();
        configureTone();
        generator.start(FIRST_GEN_ID);
        int pushedByTheStart = channel.events(MessageType.EV_GEN_STATE).size();

        ticker.advance(TICKS);

        assertEquals(pushedByTheStart, channel.events(MessageType.EV_GEN_STATE).size(),
                "spec 4.5 pushes on every CHANGE plus a position while something is "
                        + "moving; a tone holding still has neither");
    }

    // -------------------------------------------------------------------------
    // gen.playFile and the file ownership of spec 3
    // -------------------------------------------------------------------------

    @Test
    void anUnknownFileIsRefused() {
        open();

        NetException refused = assertThrows(NetException.class,
                () -> generator.playFile(FIRST_GEN_ID, "f-404", false));

        assertEquals(ErrorCode.NO_SUCH_FILE, refused.getCode());
    }

    @Test
    void aFileThisConnectionPlayedIsDroppedWithTheConnection() {
        open();
        String fileId = files.put(silentWav());
        generator.playFile(FIRST_GEN_ID, fileId, false);
        assertNotNull(files.get(fileId));

        generator.closeAll();

        assertNull(files.get(fileId),
                "spec 3: a file is owned by nobody until gen.playFile references it, "
                        + "and is dropped when the referencing connection closes - "
                        + "otherwise a client that vanished leaves the operator's "
                        + "audio in the server's heap with no handle left to delete "
                        + "it by");
    }

    @Test
    void aFilePlaysAtItsOwnRateAndTheStateSaysWhichOne() {
        open();
        StubPlayback toneLane = playback();
        String fileId = files.put(silentWav());

        generator.playFile(FIRST_GEN_ID, fileId, false);

        assertTrue(toneLane.isClosed(),
                "one DAC, one source: the file takes the lane from the tone");
        JsonNode file = generator.state(FIRST_GEN_ID).path(NetFields.FILE);
        assertTrue(file.path(NetFields.PLAYING).asBoolean());
        assertEquals(WAV_RATE_HZ, file.path(NetFields.RATE).asInt(),
                "nothing resamples on this path, so the lane is reopened at the "
                        + "file's own rate - a lane clocked elsewhere would play it at "
                        + "the wrong pitch");
        assertEquals(WAV_BITS, file.path(NetFields.BITS).asInt());
        assertEquals(WAV_RATE_HZ, playback().getSampleRate());
    }

    @Test
    void stoppingTheFileGivesTheLaneBackAtTheFormatOpenGranted() {
        open();
        String fileId = files.put(silentWav());
        generator.playFile(FIRST_GEN_ID, fileId, false);

        generator.stopFile(FIRST_GEN_ID);

        assertEquals(RATE_HZ, playback().getSampleRate());
        assertEquals(BITS, playback().getBitDepth());
        assertFalse(generator.state(FIRST_GEN_ID).path(NetFields.FILE)
                .path(NetFields.PLAYING).asBoolean());
    }

    @Test
    void theDeclaredTypePicksTheStagingSuffixAndTheSniffIsOnlyTheFallback() {
        open();

        // The declared type decides, whatever the bytes look like.
        assertEquals(".flac", generator.suffixOf(silentWav(), "audio/flac"),
                "a client that says FLAC gets the FLAC decoder, even though these "
                        + "bytes begin RIFF");
        assertEquals(".aiff", generator.suffixOf(silentWav(), "audio/aiff"),
                "and AIFF gets .aiff - the case no sniff of the first bytes chose, "
                        + "because the old fallback sent everything non-FLAC to .wav");
        assertEquals(".wav", generator.suffixOf(silentWav(), "audio/wav"));
        assertEquals(".wav", generator.suffixOf(silentWav(), "audio/wav; charset=binary"),
                "a Content-Type may carry parameters; only the type decides");

        // The ID3v2-prefixed FLAC: the very case the content sniff cannot settle,
        // since the file does not begin with the fLaC marker.  Asserting the
        // staging suffix only - building a genuinely decodable ID3+FLAC fixture
        // here would be disproportionate to the routing rule under test.
        byte[] id3Flac = {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 10, 'f', 'L', 'a', 'C'};
        assertEquals(".flac", generator.suffixOf(id3Flac, "audio/flac"),
                "the declared type resolves a FLAC behind an ID3 tag");
        assertEquals(".wav", generator.suffixOf(id3Flac, null),
                "and without a type the old sniff still misses it - unchanged "
                        + "behaviour, deliberately not widened");

        // Absent or unknown: today's sniff, exactly as before.
        assertEquals(".flac", generator.suffixOf(flacMagic(), null),
                "no type declared falls back to the fLaC marker");
        assertEquals(".flac", generator.suffixOf(flacMagic(), "application/octet-stream"),
                "and so does a type this server does not know");
        assertEquals(".wav", generator.suffixOf(silentWav(), null));
    }

    @Test
    void anAiffUploadDeclaredAsAiffPlays() throws Exception {
        open();
        String fileId = files.put(silentAiff(), "audio/aiff");

        generator.playFile(FIRST_GEN_ID, fileId, false);

        assertTrue(generator.state(FIRST_GEN_ID).path(NetFields.FILE)
                        .path(NetFields.PLAYING).asBoolean(),
                "an AIFF staged as .aiff decodes and plays - before the type "
                        + "travelled it was staged .wav and left to the JDK to cope");
        assertEquals(WAV_RATE_HZ, generator.state(FIRST_GEN_ID).path(NetFields.FILE)
                .path(NetFields.RATE).asInt());
    }

    @Test
    void loopCanBeFlippedBothWaysWhileTheFileIsPlaying() {
        open();
        String fileId = files.put(silentWav());
        generator.playFile(FIRST_GEN_ID, fileId, false);

        // Ticking loop mid-play: the player re-reads the flag at each end of
        // stream, so the lap that is ending repeats.
        generator.config(FIRST_GEN_ID, new NetMessage(MessageType.GEN_CONFIG)
                .put(NetFields.GEN_ID, FIRST_GEN_ID)
                .put(NetFields.FILE_LOOP, true));
        assertTrue(generator.playingFile().isLoop(),
                "gen.config's fileLoop reaches the running player");

        // And unticking it again: the lap FINISHES rather than being cut off -
        // nothing here stops playback, only the end-of-stream decision changes.
        generator.config(FIRST_GEN_ID, new NetMessage(MessageType.GEN_CONFIG)
                .put(NetFields.GEN_ID, FIRST_GEN_ID)
                .put(NetFields.FILE_LOOP, false));
        assertFalse(generator.playingFile().isLoop());
        assertTrue(generator.state(FIRST_GEN_ID).path(NetFields.FILE)
                        .path(NetFields.PLAYING).asBoolean(),
                "the file is still playing - unticking decides the END, it does "
                        + "not stop anything now");
    }

    @Test
    void aFileThatRunsOutStopsTheLaneInsteadOfWritingSilenceForEver() {
        open();
        StubPlayback fileLane;
        String fileId = files.put(silentWav());
        generator.playFile(FIRST_GEN_ID, fileId, false);
        fileLane = playback();
        // Pull the file past its end.  Until this was fixed nothing here was
        // watching: the generator answers silence for ever once finished, so the
        // play loop wrote digital silence into the DAC indefinitely and the lane
        // never came back to the tone's format.
        fileLane.render(WAV_FRAMES * 4);
        assertTrue(generator.playingFile().isFinished(), "the file really ran out");
        int sentBeforeTick = channel.getSent().size();

        ticker.advance(1);

        // (a) the client learns the file finished, and learns it BEFORE the lane
        // is handed back - a handback first would blank the file block and the
        // end-of-file would never reach the operator's screen.
        List<NetMessage> after = channel.getSent().subList(sentBeforeTick,
                channel.getSent().size());
        int finishedAt = -1;
        for (int i = 0; i < after.size(); i++) {
            if (after.get(i).getNode(NetFields.FILE).path(NetFields.FINISHED).asBoolean()) {
                finishedAt = i;
                break;
            }
        }
        assertTrue(finishedAt >= 0, "file:{finished:true} was pushed: " + after);
        assertTrue(finishedAt < after.size() - 1,
                "and a further state followed it - the handback, which must come "
                        + "after the news, not instead of it");

        // (b) the lane that played the file is given back exactly once.
        assertEquals(1, fileLane.getCloseCount(),
                "one file, one handback - a lane released twice is a double free");

        // (c) nothing can write to it any more: the source is released and the
        // lane in force is a fresh one at the TONE's format.
        assertNull(generator.playingFile(), "the file source is let go");
        assertNotSame(fileLane, playback(), "and the lane in force is a new one");
        assertEquals(RATE_HZ, playback().getSampleRate(),
                "reopened at the tone's rate, ready for gen.start");
    }

    @Test
    void fileLoopIsIgnoredWhenNoFileIsPlaying() {
        open();

        // A tone-only generator: the field is inapplicable and must be ignored
        // like every other inapplicable gen.config field, not refused.
        generator.config(FIRST_GEN_ID, new NetMessage(MessageType.GEN_CONFIG)
                .put(NetFields.GEN_ID, FIRST_GEN_ID)
                .put(NetFields.FILE_LOOP, true));

        assertNull(generator.playingFile(), "still no file, and no exception");
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    /** Four bytes that are nothing but the FLAC stream marker - enough for the
     *  content sniff, which reads exactly those. */
    private byte[] flacMagic() {
        return new byte[] {'f', 'L', 'a', 'C', 0, 0, 0, 0};
    }

    /** {@link #silentWav()} re-wrapped as a real AIFF by the JDK's own writer, so
     *  the decode under test is a genuine one. */
    private byte[] silentAiff() throws Exception {
        try (AudioInputStream wav = AudioSystem.getAudioInputStream(
                new ByteArrayInputStream(silentWav()))) {
            ByteArrayOutputStream aiff = new ByteArrayOutputStream();
            AudioSystem.write(wav, AudioFileFormat.Type.AIFF, aiff);
            return aiff.toByteArray();
        }
    }

    /** A second-harmonic correction that is only ever asked to be well formed. */
    private static final double SECOND_HARMONIC_RATIO = 0.01;
    /** One that is asked to be AUDIBLE: {@code sin θ − 0.5·cos 2θ} peaks at 1.5,
     *  so a correction that reached the DDS is visible in the waveform and one
     *  that did not is not. */
    private static final double STRONG_HARMONIC_RATIO = 0.5;
    private static final double MIN_PEAK_GROWTH = 1.3;
    private static final int SECOND_HARMONIC = 2;

    private JsonNode open() {
        return generator.open(output, StubDeviceManager.OUTPUT, RATE_HZ, BITS, DITHER_BITS,
                OutputChannels.LEFT.name());
    }

    private int openCapture() {
        int captureId = captures.open(input, StubDeviceManager.FIRST_INPUT, RATE_HZ, BITS)
                .path(NetFields.CAPTURE_ID).asInt();
        captures.start(captureId);
        return captureId;
    }

    private void configureTone() {
        generator.config(FIRST_GEN_ID, config()
                .put(NetFields.FORM, GenSignalForm.SINE.name())
                .put(NetFields.FREQUENCY, TONE_HZ)
                .put(NetFields.AMPLITUDE_VRMS, AMPLITUDE_VRMS)
                .put(NetFields.DAC_FS_VOLTAGE_AMPL, DAC_FS_VOLTAGE_AMPL));
    }

    private void configureSweep(GenSignalForm form) {
        ObjectNode sweep = JsonNodeFactory.instance.objectNode();
        sweep.put(NetFields.F0, SWEEP_F0_HZ);
        sweep.put(NetFields.F1, SWEEP_F1_HZ);
        sweep.put(NetFields.DURATION_SAMPLES, SWEEP_SAMPLES);
        sweep.put(NetFields.LEAD_IN_SAMPLES, LEAD_IN_SAMPLES);
        sweep.put(NetFields.FADE_IN_SAMPLES, FADE_SAMPLES);
        sweep.put(NetFields.FADE_OUT_SAMPLES, FADE_SAMPLES);
        sweep.put(NetFields.LOOP, false);
        generator.config(FIRST_GEN_ID, config()
                .put(NetFields.FORM, form.name())
                .put(NetFields.AMPLITUDE_VRMS, AMPLITUDE_VRMS)
                .put(NetFields.DAC_FS_VOLTAGE_AMPL, DAC_FS_VOLTAGE_AMPL)
                .put(NetFields.SWEEP, sweep));
    }

    private ObjectNode dual() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put(NetFields.FREQUENCY2, SECOND_TONE_HZ);
        node.put(NetFields.AMP1_PCT, FIRST_SPLIT_PCT);
        node.put(NetFields.AMP2_PCT, SECOND_SPLIT_PCT);
        return node;
    }

    private ObjectNode compensation() {
        return compensation(SECOND_HARMONIC_RATIO);
    }

    /** One second-harmonic phasor at {@code ratio}, in phase. */
    private ObjectNode compensation(double ratio) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.putArray(NetFields.AMP_RATIOS).add(ratio);
        node.putArray(NetFields.H_NUMS).add(SECOND_HARMONIC);
        node.putArray(NetFields.PHI_INITS).add(EXACT);
        return node;
    }

    /** A one-shot log sweep with no lead-in and no fades, so a sample pulled
     *  from the lane is a sample of the chirp itself and its frequency says
     *  exactly how far in the sweep has got. */
    private void configureChirp() {
        ObjectNode sweep = JsonNodeFactory.instance.objectNode();
        sweep.put(NetFields.F0, CHIRP_F0_HZ);
        sweep.put(NetFields.F1, CHIRP_F1_HZ);
        sweep.put(NetFields.DURATION_SAMPLES, SWEEP_SAMPLES);
        sweep.put(NetFields.LEAD_IN_SAMPLES, 0);
        sweep.put(NetFields.LOOP, false);
        generator.config(FIRST_GEN_ID, config()
                .put(NetFields.FORM, GenSignalForm.LOG_SWEEP.name())
                .put(NetFields.AMPLITUDE_VRMS, AMPLITUDE_VRMS)
                .put(NetFields.DAC_FS_VOLTAGE_AMPL, DAC_FS_VOLTAGE_AMPL)
                .put(NetFields.SWEEP, sweep));
    }

    /** The largest excursion in a rendered block - what tells a corrected tone
     *  from an uncorrected one without an FFT. */
    private double peakOf(double[] rendered) {
        double peak = 0.0;
        for (double sample : rendered) {
            peak = Math.max(peak, Math.abs(sample));
        }
        return peak;
    }

    /** A {@code gen.config} body.  The handle rides in the message like every
     *  other field, so the test builds exactly what the wire carries. */
    private NetMessage config() {
        return new NetMessage(MessageType.GEN_CONFIG, FIRST_GEN_ID)
                .put(NetFields.GEN_ID, FIRST_GEN_ID);
    }

    /** The MARKER frames written so far - spec 5 puts them on the audio lane
     *  beside the PCM. */
    private List<BinaryFrame> markerFrames() {
        List<BinaryFrame> markers = new ArrayList<>();
        for (BinaryFrame frame : channel.getFrames()) {
            if (frame.type() == FrameType.MARKER) {
                markers.add(frame);
            }
        }
        return markers;
    }

    private StubPlayback playback() {
        return managerFor().getLastPlayback();
    }

    /** The largest normalised sample one cycle of the rendered tone reaches -
     *  {@code amplitudeVrms · √2 / dacFsVoltageAmpl}, which is the only place the
     *  full scale the generator was BUILT with becomes visible: nothing reads it
     *  back, and the state payload does not carry it. */
    private double peakOf(StubPlayback lane) {
        double peak = 0.0;
        for (double sample : lane.render(MEASURE_SAMPLES)) {
            peak = Math.max(peak, Math.abs(sample));
        }
        return peak;
    }

    private StubDeviceManager managerFor() {
        return (StubDeviceManager) AudioBackend.instance().manager(AudioBackendType.QA40X);
    }

    /**
     * A minimal 16-bit mono WAV of silence - enough for the shared decoder to
     * report a rate and a depth, which is all this suite asks of it.  It is
     * written by hand rather than shipped as a resource so the rate and the
     * depth the assertions quote are visible right here.
     */
    private byte[] silentWav() {
        int dataBytes = WAV_FRAMES * (WAV_BITS / BITS_PER_BYTE);
        ByteBuffer wav = ByteBuffer.allocate(WAV_HEADER_BYTES + dataBytes)
                .order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(WAV_RIFF_OVERHEAD + dataBytes);
        wav.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(WAV_FMT_CHUNK_BYTES);
        wav.putShort(WAV_PCM);
        wav.putShort(WAV_MONO);
        wav.putInt(WAV_RATE_HZ);
        wav.putInt(WAV_RATE_HZ * (WAV_BITS / BITS_PER_BYTE));
        wav.putShort((short) (WAV_BITS / BITS_PER_BYTE));
        wav.putShort((short) WAV_BITS);
        wav.put("data".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(dataBytes);
        return wav.array();
    }
}
