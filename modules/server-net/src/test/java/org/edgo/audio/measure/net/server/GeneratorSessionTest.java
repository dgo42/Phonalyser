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
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.edgo.audio.measure.common.Constants;
import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.net.proto.BinaryFrame;
import org.edgo.audio.measure.net.proto.ErrorCode;
import org.edgo.audio.measure.net.proto.FrameType;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetMessage;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.DeviceRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
    /** A depth far from the open's, so the live push cannot pass by echo. */
    private static final double LIVE_DITHER_BITS = 4.0;
    private static final double LIVE_DITHER_BITS_RIGHT = 5.0;
    private static final int DEVICE_INDEX = 0;
    private static final int FIRST_GEN_ID = 1;
    private static final int UNOPENED_GEN_ID = 7;
    /** A tone far from every default, so an assertion cannot pass by accident. */
    private static final double TONE_HZ = 997.0;
    private static final double AMPLITUDE_VRMS = 0.5;
    private static final double QUIETER_VRMS = 0.25;
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
    /** The same card after a client recalibrated it while the tone was up. */
    private static final double RECALIBRATED_FS_LEFT = 2.0;
    private static final double RECALIBRATED_FS_RIGHT = 2.5;

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
    /** The capture's DATA connection (spec 4.7): where the sweep marker of spec
     *  4.5 goes, since it is a binary frame and the control connection carries
     *  none. */
    private final FakeChannel data = new FakeChannel();
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

    /** The bench's manager reads and writes THIS test's card store - the one
     *  the catalog was built on - so the full scale a lane opens at, and the
     *  one a recalibration moves it to, are the test's. */
    @BeforeEach
    void hookTheCardStore() {
        managerFor().setCards(cards.getPrefs());
    }

    @AfterEach
    void giveTheServerBack() {
        generator.closeAll();
        captures.closeAll();
        managerFor().setCards(null);
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
                StubDeviceManager.FIRST_INPUT, RATE_HZ, BITS, DITHER_BITS, DITHER_BITS, null));

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
                "Some other card", RATE_HZ, BITS, DITHER_BITS, DITHER_BITS, null));

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
    void aLaneOnADeviceThisServerHasNoCardForOpensAtTheUnitFullScale() {
        open();
        generator.config(FIRST_GEN_ID, config()
                .put(NetFields.FORM, GenSignalForm.SINE.name())
                .put(NetFields.FREQUENCY, TONE_HZ)
                .put(NetFields.AMPLITUDE_VRMS_LEFT, AMPLITUDE_VRMS)
                .put(NetFields.AMPLITUDE_VRMS_RIGHT, AMPLITUDE_VRMS));
        generator.start(FIRST_GEN_ID);

        assertEquals(AMPLITUDE_VRMS * Constants.SQRT2, peakOf(playback()), AMPLITUDE_EPS,
                "with no card the divisor is 1.0 (spec 4.5), so the normalised peak "
                        + "IS the commanded amplitude in its peak form");
        assertEquals(AMPLITUDE_VRMS * Constants.SQRT2, peakOfRight(playback()), AMPLITUDE_EPS,
                "and the right lane, at the same amplitude and divisor, is alike");
    }

    @Test
    void aLaneOnACardedDeviceOpensAtTheServersOwnFullScale() {
        cards.card(CARD_NAME, StubDeviceManager.OUTPUT, false, CARD_FS_LEFT, CARD_FS_RIGHT,
                false);

        open();
        generator.config(FIRST_GEN_ID, config()
                .put(NetFields.FORM, GenSignalForm.SINE.name())
                .put(NetFields.FREQUENCY, TONE_HZ)
                .put(NetFields.AMPLITUDE_VRMS_LEFT, AMPLITUDE_VRMS)
                .put(NetFields.AMPLITUDE_VRMS_RIGHT, AMPLITUDE_VRMS));
        generator.start(FIRST_GEN_ID);

        // dacFsVoltageAmpl = fsLeft × √2, and the DDS divides the commanded
        // amplitude by it - so the peak the lane really renders says which of
        // the two the generator was built with.
        assertEquals(AMPLITUDE_VRMS / CARD_FS_LEFT, peakOf(playback()), AMPLITUDE_EPS,
                "spec 4.5 v1.1: the lane starts at the full scale the SERVER stores "
                        + "for its own device, not at the 1.0 placeholder");
        assertEquals(AMPLITUDE_VRMS / CARD_FS_RIGHT, peakOfRight(playback()), AMPLITUDE_EPS,
                "and the right lane renders against the card's RIGHT full scale, so a "
                        + "card whose two DACs differ emits the same physical level on both");
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
                .put(NetFields.AMPLITUDE_VRMS_LEFT, AMPLITUDE_VRMS)
                .put(NetFields.AMPLITUDE_VRMS_RIGHT, AMPLITUDE_VRMS));
        generator.start(FIRST_GEN_ID);

        assertEquals(AMPLITUDE_VRMS * Constants.SQRT2, peakOf(playback()), AMPLITUDE_EPS,
                "a card row that was never calibrated carries zeros, and the full "
                        + "scale is the DIVISOR of the amplitude scale - so it is an "
                        + "uncalibrated device and the 1.0 placeholder is what it gets, "
                        + "not a division by zero rendered into the DAC");
        assertEquals(AMPLITUDE_VRMS * Constants.SQRT2, peakOfRight(playback()), AMPLITUDE_EPS,
                "and the right lane gets the same placeholder, so its level stays finite");
    }

    /**
     * Each lane emits its OWN commanded amplitude against its OWN full scale of
     * the bench's card (spec 4.5, v3): the DDS renders the one waveform at each
     * lane's level, so a right amplitude half the left's on a card whose right
     * full scale is 5/4 of the left's lands at exactly {@code 0.5/5} of digital
     * full scale while the left lane sits at {@code 1/4}.
     */
    @Test
    void eachLaneEmitsItsOwnAmplitudeAgainstItsOwnFullScale() {
        cards.card(CARD_NAME, StubDeviceManager.OUTPUT, false, CARD_FS_LEFT, CARD_FS_RIGHT,
                false);

        open();
        generator.config(FIRST_GEN_ID, config()
                .put(NetFields.FORM, GenSignalForm.SINE.name())
                .put(NetFields.FREQUENCY, TONE_HZ)
                .put(NetFields.AMPLITUDE_VRMS_LEFT, AMPLITUDE_VRMS)
                .put(NetFields.AMPLITUDE_VRMS_RIGHT, QUIETER_VRMS));
        generator.start(FIRST_GEN_ID);

        assertEquals(AMPLITUDE_VRMS / CARD_FS_LEFT, peakOf(playback()), AMPLITUDE_EPS,
                "the left lane renders its own amplitude against the card's left full scale");
        assertEquals(QUIETER_VRMS / CARD_FS_RIGHT, peakOfRight(playback()), AMPLITUDE_EPS,
                "and the right lane its own amplitude against the card's right full scale");

        // One side alone moves one side: the right lane up to the left's amplitude.
        generator.config(FIRST_GEN_ID, config().put(NetFields.AMPLITUDE_VRMS_RIGHT, AMPLITUDE_VRMS));
        assertEquals(AMPLITUDE_VRMS / CARD_FS_RIGHT, peakOfRight(playback()), AMPLITUDE_EPS,
                "equal amplitudes on unequal full scales - the same physical level on "
                        + "both lanes, at different digital levels");
        assertEquals(AMPLITUDE_VRMS / CARD_FS_LEFT, peakOf(playback()), AMPLITUDE_EPS,
                "while the left lane did not move");
    }

    /**
     * A calibration stored while the lane is open (spec 4.3
     * {@code device.setCalibration}: the device's manager writes its card and
     * announces the change on the bus) re-pins the tone that is playing at once,
     * per lane - each lane's rendered level against its own new full scale -
     * exactly as a local recalibration lands on a local generator, and without
     * a restart.
     */
    @Test
    void aCalibrationStoredWhileTheToneIsPlayingRepinsBothLanesAtOnce() {
        cards.card(CARD_NAME, StubDeviceManager.OUTPUT, false, CARD_FS_LEFT, CARD_FS_RIGHT,
                false);
        open();
        generator.config(FIRST_GEN_ID, config()
                .put(NetFields.FORM, GenSignalForm.SINE.name())
                .put(NetFields.FREQUENCY, TONE_HZ)
                .put(NetFields.AMPLITUDE_VRMS_LEFT, AMPLITUDE_VRMS)
                .put(NetFields.AMPLITUDE_VRMS_RIGHT, QUIETER_VRMS));
        generator.start(FIRST_GEN_ID);

        DeviceRef dac = catalog.resolve(output, StubDeviceManager.OUTPUT);
        assertTrue(managerFor().storeCalibration(dac, false, Channel.L, RECALIBRATED_FS_LEFT));
        assertTrue(managerFor().storeCalibration(dac, false, Channel.R, RECALIBRATED_FS_RIGHT));

        assertEquals(AMPLITUDE_VRMS / RECALIBRATED_FS_LEFT, peakOf(playback()), AMPLITUDE_EPS,
                "the playing tone's left lane is rendered against the NEW left full scale");
        assertEquals(QUIETER_VRMS / RECALIBRATED_FS_RIGHT, peakOfRight(playback()),
                AMPLITUDE_EPS,
                "and its right lane against the NEW right full scale");
    }

    @Test
    void theQa40xClockCoversTheGeneratorLaneToo() {
        captures.open(input, StubDeviceManager.FIRST_INPUT, RATE_HZ, BITS);

        NetException refused = assertThrows(NetException.class,
                () -> generator.open(output, StubDeviceManager.OUTPUT,
                        StubDeviceManager.RATE_96K, BITS, DITHER_BITS, DITHER_BITS, null));

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
                .put(NetFields.AMPLITUDE_VRMS_LEFT, AMPLITUDE_VRMS)
                .put(NetFields.RECTANGLE_DUTY, RECTANGLE_DUTY)
                .put(NetFields.TRIANGLE_DUTY, TRIANGLE_DUTY));

        // A second update naming ONE field must leave every other one alone.
        generator.config(FIRST_GEN_ID, config().put(NetFields.AMPLITUDE_VRMS_LEFT, QUIETER_VRMS));

        JsonNode state = generator.state(FIRST_GEN_ID);
        assertEquals(GenSignalForm.RECTANGLE.name(), state.path(NetFields.FORM).asText(),
                "spec 4.5: only present fields are applied - an absent form is not a "
                        + "request to go back to a sine");
        assertEquals(TONE_HZ, state.path(NetFields.NOMINAL_HZ).asDouble(), EXACT);
        assertEquals(QUIETER_VRMS, state.path(NetFields.AMPLITUDE_VRMS_LEFT).asDouble(), EXACT,
                "and the one field that WAS present did change");
    }

    @Test
    void aDitherEditLandsOnTheToneThatIsPlaying() {
        open();
        generator.config(FIRST_GEN_ID, config()
                .put(NetFields.DITHER_BITS_LEFT, LIVE_DITHER_BITS)
                .put(NetFields.DITHER_BITS_RIGHT, LIVE_DITHER_BITS_RIGHT));

        assertEquals(LIVE_DITHER_BITS, playback().getDitherBitsLeft(), EXACT,
                "spec 4.5: gen.config's dither depths are the live half of the depth - "
                        + "they reach the OPEN lane's quantizer, not the next open");
        assertEquals(LIVE_DITHER_BITS_RIGHT, playback().getDitherBitsRight(), EXACT,
                "and each lane its own: a dither entered as a level is a different "
                        + "depth on each lane once the two full scales differ");
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

        generator.config(FIRST_GEN_ID, config().put(NetFields.AMPLITUDE_VRMS_LEFT, QUIETER_VRMS));

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

        generator.config(FIRST_GEN_ID, config().put(NetFields.AMPLITUDE_VRMS_LEFT, QUIETER_VRMS));

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
                .put(NetFields.AMPLITUDE_VRMS_LEFT, AMPLITUDE_VRMS)
                .put(NetFields.AMPLITUDE_VRMS_RIGHT, AMPLITUDE_VRMS));
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

    /**
     * A per-line block on a session in SINE_COMP changes the named harmonic
     * alone, on the running tone and in the stored table - see
     * {@link #assertPerLineBlockFades}.
     */
    @Test
    void aPerLineBlockFadesTheNamedHarmonicAndMergesIntoTheStoredTable() {
        double[] merged = LINE_AMPS.clone();
        merged[LINE_CHANGED] = LINE_NEW_AMP;
        double[] mergedPhis = LINE_PHIS.clone();
        mergedPhis[LINE_CHANGED] = LINE_NEW_PHI;
        NetMessage tone = config()
                .put(NetFields.FORM, GenSignalForm.SINE_COMP.name())
                .put(NetFields.FREQUENCY, TONE_HZ)
                .put(NetFields.AMPLITUDE_VRMS_LEFT, AMPLITUDE_VRMS)
                .put(NetFields.AMPLITUDE_VRMS_RIGHT, AMPLITUDE_VRMS)
                .put(NetFields.COMPENSATION, harmonicTable(LINE_AMPS, LINE_HARMONICS, LINE_PHIS));
        int[] named = { LINE_HARMONICS[LINE_CHANGED] };

        assertPerLineBlockFades(tone, GenSignalForm.SINE_COMP, NetFields.COMPENSATION_LINES,
                harmonicTable(new double[]{ LINE_NEW_AMP }, named, new double[]{ LINE_NEW_PHI }),
                NetFields.DUAL_COMPENSATION,
                productTable(LINE_AMPS, LINE_PRODUCT_A, LINE_PRODUCT_B, LINE_PHIS),
                g -> g.applyCompensation(LINE_AMPS, LINE_HARMONICS, LINE_PHIS),
                g -> g.applyCompensation(merged, LINE_HARMONICS, mergedPhis),
                g -> g.applyCompensationLines(new double[]{ LINE_NEW_AMP }, named,
                        new double[]{ LINE_NEW_PHI }));
    }

    /** The dual-tone twin: a per-line block on a session in DUAL_TONE_COMP
     *  changes the named intermodulation product alone. */
    @Test
    void aPerLineBlockFadesTheNamedProductAndMergesIntoTheStoredTable() {
        double[] merged = LINE_AMPS.clone();
        merged[LINE_CHANGED] = LINE_NEW_AMP;
        double[] mergedPhis = LINE_PHIS.clone();
        mergedPhis[LINE_CHANGED] = LINE_NEW_PHI;
        NetMessage tone = config()
                .put(NetFields.FORM, GenSignalForm.DUAL_TONE_COMP.name())
                .put(NetFields.FREQUENCY, TONE_HZ)
                .put(NetFields.AMPLITUDE_VRMS_LEFT, AMPLITUDE_VRMS)
                .put(NetFields.AMPLITUDE_VRMS_RIGHT, AMPLITUDE_VRMS)
                .put(NetFields.DUAL, dual())
                .put(NetFields.DUAL_COMPENSATION,
                        productTable(LINE_AMPS, LINE_PRODUCT_A, LINE_PRODUCT_B, LINE_PHIS));
        int[] namedA = { LINE_PRODUCT_A[LINE_CHANGED] };
        int[] namedB = { LINE_PRODUCT_B[LINE_CHANGED] };

        assertPerLineBlockFades(tone, GenSignalForm.DUAL_TONE_COMP,
                NetFields.DUAL_COMPENSATION_LINES,
                productTable(new double[]{ LINE_NEW_AMP }, namedA, namedB,
                        new double[]{ LINE_NEW_PHI }),
                NetFields.COMPENSATION, harmonicTable(LINE_AMPS, LINE_HARMONICS, LINE_PHIS),
                g -> g.applyDualToneCompensation(LINE_AMPS, LINE_PRODUCT_A, LINE_PRODUCT_B,
                        LINE_PHIS),
                g -> g.applyDualToneCompensation(merged, LINE_PRODUCT_A, LINE_PRODUCT_B,
                        mergedPhis),
                g -> g.applyDualToneCompensationLines(new double[]{ LINE_NEW_AMP }, namedA,
                        namedB, new double[]{ LINE_NEW_PHI }));
    }

    /** A per-line block on a session emitting the plain sine switches it to
     *  SINE_COMP without a restart, and the tone renders the named line - see
     *  {@link #assertPlainFormBlockSwitches}. */
    @Test
    void aPerLineBlockOnThePlainSineSwitchesToTheCompensatedFormWithoutARestart() {
        NetMessage tone = config()
                .put(NetFields.FORM, GenSignalForm.SINE.name())
                .put(NetFields.FREQUENCY, TONE_HZ)
                .put(NetFields.AMPLITUDE_VRMS_LEFT, AMPLITUDE_VRMS)
                .put(NetFields.AMPLITUDE_VRMS_RIGHT, AMPLITUDE_VRMS);
        int[] named = { LINE_HARMONICS[LINE_CHANGED] };

        assertPlainFormBlockSwitches(tone, GenSignalForm.SINE, GenSignalForm.SINE_COMP,
                NetFields.COMPENSATION_LINES,
                harmonicTable(new double[]{ LINE_NEW_AMP }, named, new double[]{ LINE_NEW_PHI }),
                g -> g.applyCompensationLines(new double[]{ LINE_NEW_AMP }, named,
                        new double[]{ LINE_NEW_PHI }));
    }

    /** The dual-tone twin: a per-line block on a plain DUAL_TONE session
     *  switches it to DUAL_TONE_COMP - no restart, although a form change
     *  between the two would otherwise rebuild the generator. */
    @Test
    void aPerLineBlockOnThePlainDualToneSwitchesToTheCompensatedFormWithoutARestart() {
        NetMessage tone = config()
                .put(NetFields.FORM, GenSignalForm.DUAL_TONE.name())
                .put(NetFields.FREQUENCY, TONE_HZ)
                .put(NetFields.AMPLITUDE_VRMS_LEFT, AMPLITUDE_VRMS)
                .put(NetFields.AMPLITUDE_VRMS_RIGHT, AMPLITUDE_VRMS)
                .put(NetFields.DUAL, dual());
        int[] namedA = { LINE_PRODUCT_A[LINE_CHANGED] };
        int[] namedB = { LINE_PRODUCT_B[LINE_CHANGED] };

        assertPlainFormBlockSwitches(tone, GenSignalForm.DUAL_TONE, GenSignalForm.DUAL_TONE_COMP,
                NetFields.DUAL_COMPENSATION_LINES,
                productTable(new double[]{ LINE_NEW_AMP }, namedA, namedB,
                        new double[]{ LINE_NEW_PHI }),
                g -> g.applyDualToneCompensationLines(new double[]{ LINE_NEW_AMP }, namedA,
                        namedB, new double[]{ LINE_NEW_PHI }));
    }

    /**
     * A plain sine with a whole table stored - sent while the form was plain,
     * so stored only - takes that table whole with the switch, as a form switch
     * brings it, and the named line fades on top of it: bit for bit a local
     * plain sine given the held table whole and then the per-line apply, at
     * the same sample.
     */
    @Test
    void aPerLineBlockOnThePlainSineBringsTheStoredTableAndFadesTheLineOnTop() {
        NetMessage tone = config()
                .put(NetFields.FORM, GenSignalForm.SINE.name())
                .put(NetFields.FREQUENCY, TONE_HZ)
                .put(NetFields.AMPLITUDE_VRMS_LEFT, AMPLITUDE_VRMS)
                .put(NetFields.AMPLITUDE_VRMS_RIGHT, AMPLITUDE_VRMS)
                .put(NetFields.COMPENSATION, harmonicTable(LINE_AMPS, LINE_HARMONICS, LINE_PHIS));
        int[] named = { LINE_HARMONICS[LINE_CHANGED] };

        assertPlainFormBlockSwitches(tone, GenSignalForm.SINE, GenSignalForm.SINE_COMP,
                NetFields.COMPENSATION_LINES,
                harmonicTable(new double[]{ LINE_NEW_AMP }, named, new double[]{ LINE_NEW_PHI }),
                g -> {
                    g.applyCompensation(LINE_AMPS, LINE_HARMONICS, LINE_PHIS);
                    g.applyCompensationLines(new double[]{ LINE_NEW_AMP }, named,
                            new double[]{ LINE_NEW_PHI });
                });
    }

    /** The dual-tone twin: a plain DUAL_TONE session with a stored product
     *  table takes it whole with the switch, the named product fading on top. */
    @Test
    void aPerLineBlockOnThePlainDualToneBringsTheStoredTableAndFadesTheLineOnTop() {
        NetMessage tone = config()
                .put(NetFields.FORM, GenSignalForm.DUAL_TONE.name())
                .put(NetFields.FREQUENCY, TONE_HZ)
                .put(NetFields.AMPLITUDE_VRMS_LEFT, AMPLITUDE_VRMS)
                .put(NetFields.AMPLITUDE_VRMS_RIGHT, AMPLITUDE_VRMS)
                .put(NetFields.DUAL, dual())
                .put(NetFields.DUAL_COMPENSATION,
                        productTable(LINE_AMPS, LINE_PRODUCT_A, LINE_PRODUCT_B, LINE_PHIS));
        int[] namedA = { LINE_PRODUCT_A[LINE_CHANGED] };
        int[] namedB = { LINE_PRODUCT_B[LINE_CHANGED] };

        assertPlainFormBlockSwitches(tone, GenSignalForm.DUAL_TONE, GenSignalForm.DUAL_TONE_COMP,
                NetFields.DUAL_COMPENSATION_LINES,
                productTable(new double[]{ LINE_NEW_AMP }, namedA, namedB,
                        new double[]{ LINE_NEW_PHI }),
                g -> {
                    g.applyDualToneCompensation(LINE_AMPS, LINE_PRODUCT_A, LINE_PRODUCT_B,
                            LINE_PHIS);
                    g.applyDualToneCompensationLines(new double[]{ LINE_NEW_AMP }, namedA,
                            namedB, new double[]{ LINE_NEW_PHI });
                });
    }

    /**
     * A per-line block on a session in the plain form of its family: the
     * session's form becomes the compensated one, as the generator's own
     * per-line apply switches it; the emission that was running goes on - the
     * lane is not started again; and the running tone renders the table - it
     * is no longer the plain tone, and it is bit for bit a local generator of
     * the plain form given, at the same sample, what {@code applyLines} gives
     * it: the per-line apply, after the held table whole when one was stored.
     */
    private void assertPlainFormBlockSwitches(NetMessage tone, GenSignalForm plain,
            GenSignalForm compensated, String linesField, ObjectNode lines,
            Consumer<SignalGenerator> applyLines) {
        open();
        generator.config(FIRST_GEN_ID, tone);
        generator.start(FIRST_GEN_ID);
        int plays = playback().getPlayCount();
        SignalGenerator local     = localTone(plain);
        SignalGenerator plainTone = localTone(plain);
        renderLocal(local, StubPlayback.PREFILL_SAMPLES);
        renderLocal(plainTone, StubPlayback.PREFILL_SAMPLES + LINE_RUN_SAMPLES);
        assertArrayEquals(renderLocal(local, LINE_RUN_SAMPLES), playback().render(LINE_RUN_SAMPLES),
                EXACT, "the local generator renders exactly what the session's renders");

        generator.config(FIRST_GEN_ID, config().put(linesField, lines));
        applyLines.accept(local);
        double[] session = playback().render(LINE_RUN_SAMPLES);

        assertEquals(compensated.name(), generator.state(FIRST_GEN_ID).path(NetFields.FORM).asText(),
                "the block switches the session to the compensated form, as the generator's "
                        + "own per-line apply does");
        assertEquals(plays, playback().getPlayCount(),
                "within one family the switch is no restart: the emission that was running "
                        + "goes on");
        assertFalse(Arrays.equals(renderLocal(plainTone, LINE_RUN_SAMPLES), session),
                "the tone renders the table - it is no longer the plain tone");
        assertArrayEquals(renderLocal(local, LINE_RUN_SAMPLES), session, EXACT,
                "the named line fades in exactly as on a local generator given the same "
                        + "per-line apply");
    }

    /**
     * The per-line contract on the server, against generators built in this
     * test exactly as the session builds its own:
     * <ol>
     * <li>the running tone takes the block through the generator's own fade -
     * bit for bit the output of a local generator given the same table and
     * the same per-line apply at the same sample, which fades the named line
     * alone and leaves every other line bit for bit - and an update half-way
     * through that fade that carries no table of the running form's family,
     * only an unrelated field and a whole table of the OTHER family, does not
     * land the running form's table on it whole;</li>
     * <li>the named line does fade: during the fade the tone is not the merged
     * table's, from its end on it is, bit for bit;</li>
     * <li>the stored table holds the merge: a restart rebuilds, bit for bit,
     * the table the fade ended on.</li>
     * </ol>
     *
     * @param tone        the update that sets the session up before its start
     * @param linesField  the per-line block's field
     * @param lines       the per-line block
     * @param otherField  the whole-table field of the other family
     * @param otherTable  a whole table of the other family, sent mid-fade
     * @param oldTable    the session's first table, applied to a local generator
     * @param mergedTable the table with the block merged, the same way
     * @param applyLines  the block as a local per-line apply
     */
    private void assertPerLineBlockFades(NetMessage tone, GenSignalForm form, String linesField,
            ObjectNode lines, String otherField, ObjectNode otherTable,
            Consumer<SignalGenerator> oldTable, Consumer<SignalGenerator> mergedTable,
            Consumer<SignalGenerator> applyLines) {
        open();
        generator.config(FIRST_GEN_ID, tone);
        generator.start(FIRST_GEN_ID);
        SignalGenerator local   = localTone(form);
        SignalGenerator stepped = localTone(form);
        oldTable.accept(local);
        oldTable.accept(stepped);
        renderLocal(local, StubPlayback.PREFILL_SAMPLES);
        renderLocal(stepped, StubPlayback.PREFILL_SAMPLES + LINE_RUN_SAMPLES);
        assertArrayEquals(renderLocal(local, LINE_RUN_SAMPLES), playback().render(LINE_RUN_SAMPLES),
                EXACT, "the local generator renders exactly what the session's renders");

        generator.config(FIRST_GEN_ID, config().put(linesField, lines));
        applyLines.accept(local);
        mergedTable.accept(stepped);
        int half = LINE_FADE_SAMPLES / 2;
        double[] first = playback().render(half);
        generator.config(FIRST_GEN_ID, config().put(NetFields.RECTANGLE_DUTY, RECTANGLE_DUTY)
                .put(otherField, otherTable));
        double[] rest = playback().render(LINE_RUN_SAMPLES - half);
        double[] session = new double[LINE_RUN_SAMPLES];
        System.arraycopy(first, 0, session, 0, half);
        System.arraycopy(rest, 0, session, half, rest.length);
        double[] step = renderLocal(stepped, LINE_RUN_SAMPLES);

        assertArrayEquals(renderLocal(local, LINE_RUN_SAMPLES), session, EXACT,
                "the running tone takes the block through the generator's own fade, and an "
                        + "update carrying no table of the running form mid-fade does not "
                        + "land that table whole on it");
        assertFalse(Arrays.equals(Arrays.copyOf(step, LINE_FADE_SAMPLES),
                        Arrays.copyOf(session, LINE_FADE_SAMPLES)),
                "the named line fades in - it does not land at once");
        assertArrayEquals(Arrays.copyOfRange(step, LINE_FADE_SAMPLES, LINE_RUN_SAMPLES),
                Arrays.copyOfRange(session, LINE_FADE_SAMPLES, LINE_RUN_SAMPLES), EXACT,
                "after the fade the tone is the merged table's: the named line at its target, "
                        + "every other line bit for bit as it was");

        generator.stop(FIRST_GEN_ID);
        generator.start(FIRST_GEN_ID);
        SignalGenerator rebuilt = localTone(form);
        mergedTable.accept(rebuilt);
        renderLocal(rebuilt, StubPlayback.PREFILL_SAMPLES);
        assertArrayEquals(renderLocal(rebuilt, LINE_RUN_SAMPLES), playback().render(LINE_RUN_SAMPLES),
                EXACT, "the stored table holds the merge: a restart rebuilds the table the "
                        + "fade ended on");
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

        List<BinaryFrame> frames = data.getFrames();
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
                + "\"amplitudeVrmsLeft\":0.5,\"amplitudeVrmsRight\":0.5,"
                + "\"sweep\":{\"active\":false,\"posSamples\":0,"
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
                .put(NetFields.AMPLITUDE_VRMS_LEFT, AMPLITUDE_VRMS)
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
    /** The table the per-line tests start from - three lines at their own
     *  ratios and phases - and the entry a per-line block changes, to twice
     *  its ratio at a phase across the +-pi seam. */
    private static final int[] LINE_HARMONICS = { 2, 3, 4 };
    private static final int[] LINE_PRODUCT_A = { 2, 1, -1 };
    private static final int[] LINE_PRODUCT_B = { -1, 1, 2 };
    private static final double[] LINE_AMPS = { 0.01, 0.01, 0.005 };
    private static final double[] LINE_PHIS = { 0.3, 3.0, -1.0 };
    private static final int LINE_CHANGED = 1;
    private static final double LINE_NEW_AMP = 0.02;
    private static final double LINE_NEW_PHI = -3.0;
    /** The fade of a per-line block on the tests' tone: ten periods of 997 Hz
     *  at 48 kHz are 481.4 samples, rounded up. */
    private static final int LINE_FADE_SAMPLES = 482;
    /** Samples compared before and after a per-line block - over four fades. */
    private static final int LINE_RUN_SAMPLES = 2_000;
    /** The full scale of a device this server has no card for (spec 4.5). */
    private static final double UNIT_FULL_SCALE = 1.0;

    private JsonNode open() {
        return generator.open(output, StubDeviceManager.OUTPUT, RATE_HZ, BITS, DITHER_BITS,
                DITHER_BITS, OutputChannels.LEFT.name());
    }

    /** Spec 4.7's sequence: a capture streams only once its data connection has
     *  attached, and the sweep marker of spec 4.5 travels on THAT connection -
     *  so a capture opened without one would have nowhere to be marked. */
    private int openCapture() {
        int captureId = captures.open(input, StubDeviceManager.FIRST_INPUT, RATE_HZ, BITS)
                .path(NetFields.CAPTURE_ID).asInt();
        captures.attach(captureId, data);
        captures.start(captureId);
        return captureId;
    }

    private void configureTone() {
        generator.config(FIRST_GEN_ID, config()
                .put(NetFields.FORM, GenSignalForm.SINE.name())
                .put(NetFields.FREQUENCY, TONE_HZ)
                .put(NetFields.AMPLITUDE_VRMS_LEFT, AMPLITUDE_VRMS)
                .put(NetFields.AMPLITUDE_VRMS_RIGHT, AMPLITUDE_VRMS));
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
                .put(NetFields.AMPLITUDE_VRMS_LEFT, AMPLITUDE_VRMS)
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

    /** A single-tone table block - whole or per-line, the arrays as given. */
    private ObjectNode harmonicTable(double[] amps, int[] hNums, double[] phis) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        ArrayNode ratios = node.putArray(NetFields.AMP_RATIOS);
        ArrayNode harmonics = node.putArray(NetFields.H_NUMS);
        ArrayNode phases = node.putArray(NetFields.PHI_INITS);
        for (int i = 0; i < amps.length; i++) {
            ratios.add(amps[i]);
            harmonics.add(hNums[i]);
            phases.add(phis[i]);
        }
        return node;
    }

    /** A dual-tone table block - whole or per-line, the arrays as given. */
    private ObjectNode productTable(double[] amps, int[] aCoef, int[] bCoef, double[] phis) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        ArrayNode ratios = node.putArray(NetFields.AMP_RATIOS);
        ArrayNode as = node.putArray(NetFields.A_COEF);
        ArrayNode bs = node.putArray(NetFields.B_COEF);
        ArrayNode phases = node.putArray(NetFields.PHI_INITS);
        for (int i = 0; i < amps.length; i++) {
            ratios.add(amps[i]);
            as.add(aCoef[i]);
            bs.add(bCoef[i]);
            phases.add(phis[i]);
        }
        return node;
    }

    /** A generator built here exactly as the session builds the per-line
     *  tests' tone: the same form, frequencies, split, rate and amplitudes,
     *  at the full scale of a device without a card. */
    private SignalGenerator localTone(GenSignalForm form) {
        SignalGenerator gen = new SignalGenerator(form, TONE_HZ, RATE_HZ, AMPLITUDE_VRMS,
                AMPLITUDE_VRMS, UNIT_FULL_SCALE, UNIT_FULL_SCALE);
        if (form.isDualTone()) {
            gen.setDualToneFrequency2(SECOND_TONE_HZ);
            gen.setDualToneAmplitudes(FIRST_SPLIT_PCT, SECOND_SPLIT_PCT);
        }
        return gen;
    }

    /** {@code count} left-lane samples of a local generator, the way the
     *  stub's render loop pulls them from the session's. */
    private double[] renderLocal(SignalGenerator gen, int count) {
        double[] frame = new double[2];
        double[] rendered = new double[count];
        for (int i = 0; i < count; i++) {
            gen.nextFrame(frame);
            rendered[i] = frame[0];
        }
        return rendered;
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
                .put(NetFields.AMPLITUDE_VRMS_LEFT, AMPLITUDE_VRMS)
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

    /** The MARKER frames written so far - spec 5 puts them on the capture's data
     *  connection beside the PCM (spec 4.7), never on the control one. */
    private List<BinaryFrame> markerFrames() {
        List<BinaryFrame> markers = new ArrayList<>();
        for (BinaryFrame frame : data.getFrames()) {
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
        return peakOf(lane.render(MEASURE_SAMPLES));
    }

    /** The right lane's twin of {@link #peakOf(StubPlayback)}: the level the
     *  DDS renders the RIGHT lane at - its own amplitude over its own full
     *  scale, nothing derived from the left. */
    private double peakOfRight(StubPlayback lane) {
        return peakOf(lane.renderRight(MEASURE_SAMPLES));
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
