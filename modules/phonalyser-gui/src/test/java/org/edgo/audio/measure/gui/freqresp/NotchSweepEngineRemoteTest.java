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

import java.util.List;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.gui.generator.ServerGeneratorStub;
import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.gui.sound.PlaybackStateEnum;
import org.edgo.audio.measure.preferences.BackendPrefs;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.DeviceRef;
import org.edgo.audio.measure.sound.StereoSamples;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Tune-notch wizard's continuous loop when the DAC is on somebody else's
 * bench: the SAME looping Farina sweep, commanded there instead of rendered
 * here, and the SAME ring filled from the stream that comes back.
 *
 * <p>What is asserted is the plumbing and nothing else - the ring, the window and
 * the deconvolution are untouched by this branch, and the engine's own class
 * comment explains why the window needs no alignment (the loop period is a power
 * of two, so the transform is circular and any loop phase is a harmless circular
 * shift).  That is also why the rate guard here is not a nicety: off the asked
 * grid the period is no longer a power of two and every bin corrupts.
 */
class NotchSweepEngineRemoteTest {

    private static final int RATE_HZ = 8_000;
    private static final int BITS = 16;
    /** The DAC configured on ITS own clock, twice the ADC's - the pair a card
     *  whose two sides are set independently really presents. */
    private static final int PLAYBACK_RATE_HZ = 16_000;
    /** And its own word length, deliberately unlike the capture's. */
    private static final int PLAYBACK_BITS = 32;
    /** A rate pair whose ratio does NOT divide one loop period into whole
     *  output samples: 4096 periods at 8 kHz is 22579.2 samples at 44.1 kHz. */
    private static final int INDIVISIBLE_PLAYBACK_RATE_HZ = 44_100;
    private static final int DITHER_BITS = 0;
    /** 16-bit stereo. */
    private static final int FRAME_BYTES = 4;
    /** A power of two, as the wizard makes it - see the engine's class comment. */
    private static final int SWEEP_SAMPLES = 4_096;
    private static final int FADE_SAMPLES = 128;
    private static final double F0 = 40.0;
    private static final double F1 = 400.0;
    private static final double AMPLITUDE_VRMS = 0.3;
    private static final double DAC_FS_VRMS = 1.0;
    /** Signed 16-bit full scale. */
    private static final double FULL_SCALE = 32_768.0;
    private static final int LEFT_STEP = 5;
    private static final int RIGHT_OFFSET = 9;
    private static final double NEW_F0 = 60.0;
    private static final double NEW_F1 = 600.0;

    private AudioBackend audio;
    private BackendKey previousActive;
    private ServerGeneratorStub server;
    private DeviceRef out;
    private DeviceRef in;
    private String previousInName;
    private String previousOutName;
    private int    previousInRate;
    private int    previousInBits;
    private NotchSweepEngine engine;

    @BeforeEach
    void connectTheServer() {
        audio = AudioBackend.instance();
        previousActive = audio.activeKey();
        server = assertInstanceOf(ServerGeneratorStub.class, audio.manager(AudioBackendType.NET),
                "META-INF/services/...AudioDeviceManagerProvider must name the stub, or this "
                        + "test would silently exercise the LOCAL loop");
        server.reset();
        // The full remote key - a bare net carrier is not a selection.
        audio.setActive(BackendKey.of("b7e0-bench-uuid", AudioBackendType.QA40X));
        out = server.listOutputDevices().get(0);
        in = server.listInputDevices().get(0);
        // The engine resolves the ACTIVE devices from the preferences by itself
        // (the one seam), so the stub's devices are named there.  Transient mode
        // keeps this test off the developer's preferences file; the live
        // singleton's names are restored either way.
        Preferences prefs = Preferences.instance();
        prefs.setTransientMode(true);
        BackendPrefs backend = prefs.current();
        previousInName  = backend.getInputDeviceName();
        previousOutName = backend.getOutputDeviceName();
        previousInRate  = backend.getInputSampleRate();
        previousInBits  = backend.getInputBitDepth();
        backend.setInputDeviceName(in.name());
        backend.setOutputDeviceName(out.name());
        // The engine's capture is the SHARED ring, which opens at the
        // preferences' input rate - the one seam rates come from.
        backend.setInputSampleRate(RATE_HZ);
        backend.setInputBitDepth(BITS);
    }

    @AfterEach
    void disconnectTheServer() {
        if (engine != null) {
            engine.close();
            engine = null;
        }
        BackendPrefs backend = Preferences.instance().current();
        backend.setInputDeviceName(previousInName);
        backend.setOutputDeviceName(previousOutName);
        backend.setInputSampleRate(previousInRate);
        backend.setInputBitDepth(previousInBits);
        Preferences.instance().setTransientMode(false);
        audio.setActive(previousActive);
        server.reset();
    }

    /**
     * The whole session on a remote bench: the loop is commanded there, the ring
     * fills from the wire through the same normalisation, the wizard gets its
     * window, and closing gives the lane and the line back.
     */
    @Test
    void theLoopIsCommandedOnTheServerAndTheRingFillsFromTheWire() throws Exception {
        engine = new NotchSweepEngine(RATE_HZ, RATE_HZ, BITS, DITHER_BITS);

        engine.start(F0, F1, AMPLITUDE_VRMS, DAC_FS_VRMS, DAC_FS_VRMS, SWEEP_SAMPLES, FADE_SAMPLES,
                OutputChannels.BOTH);

        assertEquals(GenSignalForm.LOG_SWEEP, server.firstArg("setForm"));
        assertEquals(Boolean.TRUE, server.firstArg("setSweepLoop"),
                "the wizard's sweep runs continuously - that is the whole speed win");
        assertEquals(SWEEP_SAMPLES, server.firstArg("setSweepDurationSamples"));
        assertEquals(0, server.firstArg("setSweepLeadInSamples"),
                "a looping tuning sweep has no lead-in: the ring is read at any phase");
        assertEquals(F0, server.firstArg("setSweepFreqStart"));
        assertEquals(AMPLITUDE_VRMS, server.firstArg("setAmplitudesVrms"),
                "a sweep engine commands both lanes at its one amplitude");
        assertFalse(server.names().contains("setDacFsVoltageAmpls"),
                "the bench's own card owns its full scale");
        assertTrue(server.names().contains("startGenerator"));
        assertNotNull(engine.sweepRef(),
                "the reference X(t) is still built here - it is what the deconvolution "
                        + "divides by, and a unit-amplitude buffer the bench's own full "
                        + "scale cannot change");

        ServerGeneratorStub.ServerCapture capture = server.getLastCapture();
        assertNotNull(capture, "the engine opened no input line on the bench");
        assertNull(engine.latestPeriod(SWEEP_SAMPLES), "nothing has been captured yet");
        capture.feed(period());

        assertEquals(SWEEP_SAMPLES, engine.availableSamples());
        StereoSamples window = engine.latestPeriod(SWEEP_SAMPLES);
        assertNotNull(window, "one whole period is in the ring, so the wizard gets one");
        assertEquals((short) LEFT_STEP / FULL_SCALE, window.left()[1], 0.0,
                "normalised exactly as the local branch normalises - the ring code does "
                        + "not know which of the two played");
        assertEquals((short) (RIGHT_OFFSET - 1) / FULL_SCALE, window.right()[1], 0.0);

        engine.close();
        engine = null;

        assertTrue(server.names().contains("closeGenerator"),
                "a lane left running on the bench is a DAC no other client can take");
        assertTrue(capture.isClosed(), "and the input line goes back with it");
    }

    /**
     * The two converters run the formats they are each CONFIGURED with: the DAC
     * is opened on the output rate and word length, while the captured window,
     * the deconvolution and its reference all stay on the ADC's clock.
     *
     * <p>The loop is therefore ONE DURATION counted twice - the period is the
     * power of two in capture samples, and the DAC is told the same stretch of
     * time in its own, faster samples.  Opening the DAC in the ADC's format
     * instead is refused outright by any card whose two sides are set
     * differently, which is what this covers.
     */
    @Test
    void eachConverterIsOpenedInItsOwnConfiguredFormat() throws Exception {
        engine = new NotchSweepEngine(RATE_HZ, PLAYBACK_RATE_HZ, PLAYBACK_BITS, DITHER_BITS);

        engine.start(F0, F1, AMPLITUDE_VRMS, DAC_FS_VRMS, DAC_FS_VRMS, SWEEP_SAMPLES, FADE_SAMPLES,
                OutputChannels.BOTH);

        assertEquals(PLAYBACK_RATE_HZ, server.firstArg("openGenerator"),
                "the DAC is opened on the OUTPUT rate - the ADC's is none of its business");
        assertEquals(PLAYBACK_BITS, openedArg(1),
                "and on the output word length");
        assertEquals(SWEEP_SAMPLES * PLAYBACK_RATE_HZ / RATE_HZ,
                server.firstArg("setSweepDurationSamples"),
                "one loop period is one DURATION: the same stretch of time is more "
                        + "samples on the faster clock");
        assertEquals(SWEEP_SAMPLES, engine.sweepRef().length,
                "while the reference stays as long as the window it is divided into - "
                        + "a longer one would zero-pad the capture inside the transform "
                        + "and the deconvolution would stop being circular");
    }

    /**
     * A rate pair whose ratio leaves one loop period a fractional number of
     * output samples is refused, both settings named.
     *
     * <p>Rounding it instead would emit a loop of a slightly different duration
     * from the window the deconvolution treats as exactly one period, and the
     * two would walk apart cycle by cycle - a trace that looks plausible and
     * drifts, which is worse than no trace.  The refusal costs nothing: it
     * happens before the ring or the lane is acquired.
     */
    @Test
    void aRatePairThatDoesNotDivideTheLoopPeriodRefusesTheSession() {
        try (NotchSweepEngine refused = new NotchSweepEngine(RATE_HZ,
                INDIVISIBLE_PLAYBACK_RATE_HZ, PLAYBACK_BITS, DITHER_BITS)) {
            IllegalStateException failed = assertThrows(IllegalStateException.class,
                    () -> refused.start(F0, F1, AMPLITUDE_VRMS, DAC_FS_VRMS, DAC_FS_VRMS, SWEEP_SAMPLES,
                            FADE_SAMPLES, OutputChannels.BOTH));

            assertTrue(failed.getMessage().contains(String.valueOf(RATE_HZ))
                            && failed.getMessage().contains(
                                    String.valueOf(INDIVISIBLE_PLAYBACK_RATE_HZ)),
                    "the refusal names both configured rates - " + failed.getMessage());
        }

        assertFalse(server.names().contains("openGenerator"),
                "and it took nothing from the bench: no lane was ever asked for");
        assertNull(server.getLastCapture(), "and no input line either");
    }

    /** A live band change reaches the bench too, or it would go on emitting the
     *  old band while the wizard deconvolved against the new reference. */
    @Test
    void aBandChangeReachesTheServerAndTheReference() throws Exception {
        engine = new NotchSweepEngine(RATE_HZ, RATE_HZ, BITS, DITHER_BITS);
        engine.start(F0, F1, AMPLITUDE_VRMS, DAC_FS_VRMS, DAC_FS_VRMS, SWEEP_SAMPLES, FADE_SAMPLES,
                OutputChannels.BOTH);
        server.reset();

        engine.setBand(NEW_F0, NEW_F1);

        assertEquals(NEW_F0, server.firstArg("setSweepFreqStart"));
        assertEquals(NEW_F1, server.firstArg("setSweepFreqEnd"));
    }

    /**
     * The wizard's lane gate is a live control, but spec 4.5 fixes the output
     * channels at {@code gen.open} - so a change REOPENS the bench's lane with
     * the new gate and tells it the same loop again.
     *
     * <p>Pushing it as a command instead would be silent: the operator flips the
     * channel, the panel shows it, and the bench goes on driving the lane it was
     * opened with.  The capture keeps running across the reopen, which is why the
     * wizard still gets its window afterwards.
     */
    @Test
    void aLaneGateChangeReopensTheServersLaneAndKeepsTheRingRunning() throws Exception {
        engine = new NotchSweepEngine(RATE_HZ, RATE_HZ, BITS, DITHER_BITS);
        engine.start(F0, F1, AMPLITUDE_VRMS, DAC_FS_VRMS, DAC_FS_VRMS, SWEEP_SAMPLES, FADE_SAMPLES,
                OutputChannels.BOTH);
        ServerGeneratorStub.ServerCapture capture = server.getLastCapture();
        server.reset();

        engine.setOutputChannels(OutputChannels.LEFT);

        // The ONE GeneratorLane replays its stored run whole, so the reopen
        // carries the lane's full command set - the sweep block among it, in
        // the same order every generator start commands the bench in.
        assertEquals(List.of("closeGenerator", "openGenerator", "setForm",
                        "setAmplitudesVrms", "setRectangleDuty", "setTriangleDuty",
                        "setSweepFreqStart", "setSweepFreqEnd", "setSweepDurationSamples",
                        "setSweepLeadInSamples", "setSweepFadeInSamples",
                        "setSweepFadeOutSamples", "setSweepLoop", "fftGrid",
                        "startGenerator"),
                server.names(),
                "the gate belongs to the LINE, so the line is opened again - and a new "
                        + "line knows nothing about the sweep until it is told");
        assertEquals(OutputChannels.LEFT, openedGate(),
                "and it is opened with the gate the operator picked");

        capture.feed(period());
        assertEquals(SWEEP_SAMPLES, engine.availableSamples(),
                "the capture and the ring never stopped - only the stimulus paused, "
                        + "which is a real gap in the signal and heals within one period");
        assertNotNull(engine.latestPeriod(SWEEP_SAMPLES));
    }

    /**
     * The reopen's own failure case: the close gives the DAC back, so another
     * client can take it before the open asks for it again.
     *
     * <p>What the engine owes its caller then is the gate that is still the
     * session's - the control the operator flipped has to go back to something
     * true, and the gate they asked for never took effect.  The failure is raised
     * rather than swallowed (a silently un-re-gated lane is the defect the reopen
     * exists to remove), which is why the wizard applies this on its sweep worker
     * and not from the combo's listener.
     */
    @Test
    void aRefusedReopenSaysSoAndLeavesTheGateThatIsStillInForce() throws Exception {
        engine = new NotchSweepEngine(RATE_HZ, RATE_HZ, BITS, DITHER_BITS);
        engine.start(F0, F1, AMPLITUDE_VRMS, DAC_FS_VRMS, DAC_FS_VRMS, SWEEP_SAMPLES, FADE_SAMPLES,
                OutputChannels.BOTH);
        server.reset();
        server.setRefuseOpen(true);

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> engine.setOutputChannels(OutputChannels.LEFT));

        // The refusal reaches the caller machine-readably (the enum's contract:
        // the bench's own words go to the LOG, never up as operator text).
        assertTrue(refused.getMessage().contains(PlaybackStateEnum.REMOTE_REFUSED.name()),
                "the failure names its machine-readable state - " + refused.getMessage());
        assertEquals(OutputChannels.BOTH, engine.getLoopChannels(),
                "the gate the operator asked for never took effect, so what a control "
                        + "has to show is the one this session ran with");
        assertEquals(List.of("closeGenerator", "openGenerator"), server.names(),
                "the old lane really was given back before the new one was asked for - "
                        + "and nothing was started on a lane that never opened");
    }

    /**
     * A bench that grants another rate fails the session: the loop period would
     * no longer be a power of two at the rate the audio actually runs at, the
     * deconvolution would stop being circular over one period, and every bin
     * would corrupt into a spike the operator would tune against.
     *
     * <p>And the failed start GIVES THE LANE BACK BY ITSELF - the LANE refuses
     * the grant before anything is configured or emitted (the run says
     * requireGrantedRate), and the lane the open took goes back inside that
     * refusal; the wizard that calls this only logs the failure.
     */
    @Test
    void aServerThatGrantsAnotherRateRefusesTheSessionAndGivesTheLaneBack() {
        server.setGrantedRate(RATE_HZ * 2);
        try (NotchSweepEngine refused = new NotchSweepEngine(RATE_HZ, RATE_HZ, BITS, DITHER_BITS)) {
                IllegalStateException failed = assertThrows(IllegalStateException.class,
                        () -> refused.start(F0, F1, AMPLITUDE_VRMS, DAC_FS_VRMS, DAC_FS_VRMS, SWEEP_SAMPLES,
                                FADE_SAMPLES, OutputChannels.BOTH));

                assertTrue(failed.getMessage().contains(String.valueOf(RATE_HZ * 2)),
                        "the refusal names what the bench granted - " + failed.getMessage());
        }

        assertFalse(server.names().contains("startGenerator"),
                "and nothing was emitted on a grid nothing could be read on");
        assertTrue(server.names().contains("closeGenerator"),
                "the lane the open TOOK is already back - nobody calls close() on an "
                        + "engine whose start threw");
    }

    /** And the same when it is the input stream that comes back on another
     *  clock.  The shared ring carries the GRANTED rate, so the refusal fires
     *  BEFORE any generator lane opens - a refused session takes nothing from
     *  the bench at all, and the capture reference goes back with the throw. */
    @Test
    void anInputStreamAtAnotherRateRefusesTheSessionAndGivesBothBack() {
        server.setGrantedCaptureRate(RATE_HZ * 2);
        try (NotchSweepEngine refused = new NotchSweepEngine(RATE_HZ, RATE_HZ, BITS, DITHER_BITS)) {
                IllegalStateException failed = assertThrows(IllegalStateException.class,
                        () -> refused.start(F0, F1, AMPLITUDE_VRMS, DAC_FS_VRMS, DAC_FS_VRMS, SWEEP_SAMPLES,
                                FADE_SAMPLES, OutputChannels.BOTH));

                assertTrue(failed.getMessage().contains(String.valueOf(RATE_HZ * 2)),
                        failed.getMessage());
        }

        assertFalse(server.names().contains("openGenerator"),
                "a session refused on its input stream never took the bench's DAC");
        assertFalse(server.names().contains("startGenerator"));
        assertTrue(server.getLastCapture().isClosed(),
                "the input line is back, which is what releases its device lock");
    }

    /** The lane gate the bench's line was last OPENED with - argument 4 of
     *  {@code openGenerator} (after the rate, the depth and the two lanes'
     *  dither), which is where spec 4.5 puts it. */
    private OutputChannels openedGate() {
        return (OutputChannels) openedArg(4);
    }

    /** Argument {@code index} of the bench's {@code openGenerator} - the line's
     *  format is fixed there, so this is where the rate, the word length and
     *  the gate are read back from. */
    private Object openedArg(int index) {
        for (ServerGeneratorStub.Command command : server.commands()) {
            if ("openGenerator".equals(command.name())) {
                return command.args().get(index);
            }
        }
        return null;
    }

    /** One whole loop period of recognisable PCM, signed little-endian as the
     *  wire carries it. */
    private byte[] period() {
        byte[] pcm = new byte[SWEEP_SAMPLES * FRAME_BYTES];
        for (int f = 0; f < SWEEP_SAMPLES; f++) {
            write(pcm, f * FRAME_BYTES, (short) (f * LEFT_STEP));
            write(pcm, f * FRAME_BYTES + BITS / 8, (short) (RIGHT_OFFSET - f));
        }
        return pcm;
    }

    private void write(byte[] pcm, int at, short sample) {
        pcm[at] = (byte) (sample & 0xFF);
        pcm[at + 1] = (byte) ((sample >> 8) & 0xFF);
    }
}
