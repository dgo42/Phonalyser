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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.edgo.audio.measure.common.Closeables;
import org.edgo.audio.measure.common.Constants;
import org.edgo.audio.measure.dsp.FftBinSnap;
import org.edgo.audio.measure.enums.DeviceFailureReason;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.generator.FilePlaybackGenerator;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.wav.PcmFileLoader;
import org.edgo.audio.measure.net.proto.ErrorCode;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetMessage;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.AudioDeviceManager;
import org.edgo.audio.measure.sound.AudioPlayback;
import org.edgo.audio.measure.sound.DeviceCalibration;
import org.edgo.audio.measure.sound.DeviceRef;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * One connection's remote generator - the {@code gen.*} vocabulary of spec 4.5:
 * the DDS runs HERE, next to the DAC, and only commands travel.
 *
 * <p><b>What it owns.</b>  The {@link SignalGenerator}, the {@link AudioPlayback}
 * opened on the output device this connection locked, the thread that renders
 * between them - and, because {@code SignalGenerator} has no state getters at
 * all, the COMMANDED state itself: form, frequencies, amplitude, duties, the
 * sweep parameters, the FFT grid, the trims and the compensation tables.  That
 * is what {@code ev.gen.state} is built from (spec 4.5), and it is the reason
 * this is a type rather than a handful of fields on the session: nothing else on
 * the server knows what the client asked the generator to emit.
 *
 * <p><b>Commanded, not measured.</b>  {@code nominalHz} is what the client
 * typed; {@code emitHz} is what the DDS is actually driven at - the nominal
 * value after the FFT-bin snap of {@code gen.fftGrid} and then, if one arrived,
 * after the absolute {@code gen.trim}.  Spec 4.5 is explicit that clients feed
 * their analyzers from {@code emitHz} and never from the nominal, so the two are
 * kept apart here rather than one overwriting the other: a trim that overwrote
 * the nominal could never be reset, and a snap that overwrote it would drift a
 * little further every time the FFT length changed.
 *
 * <p><b>Threading.</b>  Every method here runs on the session's own
 * {@link SessionWorker} - the same thread that dispatches the requests, so the
 * commanded state has exactly one writer and needs no locking.  Two threads
 * touch it from outside and both are deliberately narrow: the play thread, which
 * is handed its generator and its stop flag as locals and only ever reports a
 * failure back; and the {@link Ticker}, which does nothing but ENQUEUE a state
 * push onto the worker (spec 4.5 asks for ≤ 10 Hz while a sweep or a file is
 * running, and a ticker that touched the device would stall every other
 * session's keepalive).
 *
 * <p><b>Teardown.</b>  {@link #closeAll()} is the generator's half of the one
 * teardown path of spec 4.1, reached from {@code bye}, from a dead keepalive and
 * from the transport vanishing: emission stops, the decoder and its staged copy
 * go, the output line goes back, the QA40x rate claim is released, and the
 * uploaded files this connection referenced are dropped (spec 3).
 */
@Log4j2
@RequiredArgsConstructor
public final class GeneratorSession {

    /** Highest handle, for the same reason {@code captureId} has one: wire ids
     *  are allocated inside a bounded range and never from a counter that can
     *  run away. */
    private static final int MAX_GEN_ID = 0xFFFF;
    /** Value of {@link #genId} while no generator is open. */
    private static final int NO_GEN = 0;
    private static final String PLAY_THREAD = "net-gen-play-";
    /** State-push period.  Spec 4.5: "≤ 10 Hz while a sweep or file is
     *  running". */
    private static final int STATE_INTERVAL_MS = 100;
    /** How long a start waits for the hardware buffer to pre-fill.  The same
     *  five seconds the desktop controller allows - an exclusive-mode driver can
     *  take seconds to hand out a render stream. */
    private static final long READY_TIMEOUT_MS = 5_000;
    /** How long a stop waits for the play thread to leave the render loop. */
    private static final long STOP_JOIN_MS = 2_000;
    private static final int MS_PER_S = 1_000;

    private static final double DEFAULT_FREQUENCY_HZ = 1_000.0;
    /** Amplitude before the client sets one: silence.  A bench that came up
     *  emitting full scale into whatever is wired to it would be a hardware
     *  fault waiting to happen. */
    private static final double DEFAULT_AMPLITUDE_VRMS = 0.0;
    /** DAC full-scale before the client sends its calibration.  Never 0 - it is
     *  the divisor of the amplitude scale. */
    private static final double DEFAULT_DAC_FS_VOLTAGE_AMPL = 1.0;
    /** Per-lane output scale before the client sends one: both lanes alike,
     *  which is what a card with one DAC full-scale means and what a client too
     *  old to send the field expects. */
    private static final double DEFAULT_RIGHT_LANE_SCALE = 1.0;
    /** The LEFT lane's scale.  Always one: a mono amplitude is computed against
     *  the left full-scale, so the right lane carries the whole ratio. */
    private static final double LEFT_LANE_SCALE = 1.0;
    /** Symmetric duty, and an even two-tone split - the generator's own
     *  defaults. */
    private static final double DEFAULT_DUTY = 0.5;
    private static final double DEFAULT_SPLIT_PCT = 50.0;
    private static final double DEFAULT_SWEEP_F0_HZ = 20.0;
    private static final double DEFAULT_SWEEP_F1_HZ = 20_000.0;
    /** What {@code emitHz}/{@code emit2Hz} report when the form emits no such
     *  tone - see {@link #reportedEmitHz()}. */
    private static final double NO_EMITTED_HZ = 0.0;
    /** Shortest sweep the generator will build. */
    private static final int MIN_SWEEP_SAMPLES = 2;

    /** Where an uploaded file is staged for the decoder.  See
     *  {@link #writeTemp(byte[])} for why RAM alone will not do. */
    private static final String TEMP_PREFIX = "phonalyser-net-";
    private static final String SUFFIX_FLAC = ".flac";
    private static final String SUFFIX_WAV = ".wav";
    private static final String SUFFIX_AIFF = ".aiff";
    /** Bytes of the FLAC stream marker, the one format whose decoder is picked
     *  by file name rather than by content. */
    private static final int FLAC_MAGIC_BYTES = 4;

    private final AudioBackend audio;
    private final DeviceCatalog catalog;
    /** This connection's capture streams - where the {@code sweepStart} marker
     *  of spec 4.5 goes.  Injected rather than called back into, so the
     *  dependency runs one way: the generator asks the streams' owner to mark
     *  them, and nothing asks the generator anything. */
    private final CaptureStreamer captures;
    private final FileStore files;
    private final Qa40xGuard qa40x;
    private final JsonCodec codec;
    private final SessionChannel channel;
    private final SessionWorker worker;
    /** The ≤ 10 Hz position clock of spec 4.5. */
    private final Ticker ticker;
    /** Wall clock in milliseconds, injected like {@link PeerTable}'s: the sweep
     *  and file positions are derived from elapsed time, and a test must be able
     *  to place them exactly instead of sleeping for them. */
    private final LongSupplier clockMs;

    /* --------------------------- what gen.open fixed --------------------------- */

    /** The open generator's handle, or {@link #NO_GEN}. */
    private int genId;
    private DeviceLock device;
    /** Package-visible for one caller each: the test pinning that the names
     *  the emission lines print really are the open's device and its card. */
    @Getter(AccessLevel.PACKAGE)
    private String deviceName;
    /** The card in force for {@link #deviceName} at open time - what the
     *  operator-facing emission lines print beside the device. */
    @Getter(AccessLevel.PACKAGE)
    private String cardName;
    private int rateHz;
    private int bits;
    /** The rate currently CLAIMED from {@link Qa40xGuard} for {@link #device} -
     *  {@link #rateHz} while the tone owns the lane, the file's own rate while a
     *  file plays.  Kept because a refused swap has to put back the claim that
     *  described the lane, not the one {@code gen.open} was granted. */
    private int claimedRateHz;
    private double ditherBits;
    private OutputChannels outputChannels = OutputChannels.BOTH;
    /** Where the next handle comes from.  One generator is open per connection
     *  at a time, so there is nothing to skip - only the range to stay inside. */
    private int nextGenId = 1;

    /* ------------------------ commanded state - spec 4.5 ------------------------ */

    private GenSignalForm form = GenSignalForm.SINE;
    private double nominalHz = DEFAULT_FREQUENCY_HZ;
    private double nominal2Hz = DEFAULT_FREQUENCY_HZ;
    private double amplitudeVrms = DEFAULT_AMPLITUDE_VRMS;
    private double dacFsVoltageAmpl = DEFAULT_DAC_FS_VOLTAGE_AMPL;
    /** {@code fsLeft/fsRight} - the client's calibration for a card whose two
     *  DAC full-scales differ.  It belongs to the LANE, not to the DDS, so it is
     *  pushed to the playback line rather than to the generator. */
    private double rightLaneScale = DEFAULT_RIGHT_LANE_SCALE;
    private double rectangleDuty = DEFAULT_DUTY;
    private double triangleDuty = DEFAULT_DUTY;
    private double amp1Pct = DEFAULT_SPLIT_PCT;
    private double amp2Pct = DEFAULT_SPLIT_PCT;
    private double sweepF0 = DEFAULT_SWEEP_F0_HZ;
    private double sweepF1 = DEFAULT_SWEEP_F1_HZ;
    private int sweepDurationSamples;
    private int leadInSamples;
    private int fadeInSamples;
    private int fadeOutSamples;
    private boolean sweepLoop = true;
    private int fftSize;
    private boolean snapEnabled;
    /** The absolute corrected frequencies of {@code gen.trim}/{@code gen.trim2},
     *  or null while the tone sits on its snapped nominal.  Null is not zero:
     *  {@code gen.trimReset} has to be able to say "no correction at all". */
    private Double trimHz;
    private Double trim2Hz;
    private double[] compAmpRatios;
    private int[] compHNums;
    private double[] compPhiInits;
    private double[] dualCompAmpRatios;
    private int[] dualCompA;
    private int[] dualCompB;
    private double[] dualCompPhiInits;

    /* -------------------------------- runtime -------------------------------- */

    private AudioPlayback playback;
    /** The backend {@link #playback} was opened through - the one type that can
     *  say what a fault on this lane MEANT, asked again when the lane dies. */
    private AudioDeviceManager playbackBackend;
    private SignalGenerator generator;
    private FilePlaybackGenerator filePlayback;
    private Path fileTemp;
    private Thread playThread;
    private AtomicBoolean stopFlag;
    private boolean running;
    /** When the current emission started, on the injected clock - the origin
     *  every reported position is measured from. */
    private long startedAtMs;

    // -------------------------------------------------------------------------
    // gen.open / gen.close
    // -------------------------------------------------------------------------

    /**
     * Spec 4.5: opens the server-side playback on the locked output device and
     * creates the generator, silent until {@code gen.start}.
     *
     * <p>The lock check is the session's, exactly as it is for
     * {@code capture.open} - who owns what is between the session and the
     * registry.  What is decided here is everything about the lane itself,
     * including the QA40x's one register-9 clock (spec 4.4: "{@code capture.open}
     * / {@code gen.open} on QA40x with mismatched rates -> {@code BAD_REQUEST}"),
     * which is asked of the server-wide {@link Qa40xGuard} because the other
     * stream at the other rate may belong to another connection entirely.
     *
     * @throws NetException with the spec 4.2 code the refusal deserves
     */
    public JsonNode open(DeviceLock lock, String name, Integer rate, Integer bitDepth,
            Double dither, String channels) {
        if (rate == null || bitDepth == null) {
            throw new NetException(ErrorCode.BAD_REQUEST,
                    "gen.open needs a rate and a bit depth");
        }
        if (lock.input()) {
            throw new NetException(ErrorCode.BAD_REQUEST,
                    "gen.open needs an output device ref, got " + lock);
        }
        if (genId != NO_GEN) {
            throw new NetException(ErrorCode.BAD_REQUEST,
                    "a generator is already open on this connection (id " + genId + ")");
        }
        DeviceRef ref = catalog.resolve(lock, name);
        this.device = lock;
        this.deviceName = name;
        this.cardName = catalog.boundCard(ref.name());
        this.rateHz = rate;
        this.bits = bitDepth;
        this.ditherBits = dither == null ? 0.0 : dither;
        this.outputChannels = outputChannelsOf(channels);
        applyCardCalibration(lock, name);
        qa40x.claimRate(lock, rate);
        this.claimedRateHz = rate;
        try {
            this.playback = openLane(ref, rate, bitDepth);
        } catch (NetException e) {
            qa40x.releaseRate(lock);
            this.device = null;
            throw e;
        }
        this.genId = freeGenId();
        if (log.isInfoEnabled()) {
            log.info("net gen {}: open on {} at {} Hz / {} bit, dither {} bit, lanes {}",
                    genId, lock, rate, bitDepth, ditherBits, outputChannels);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(NetFields.GEN_ID, genId);
        data.put(NetFields.RATE, rate);
        return codec.toNode(data);
    }

    /**
     * Spec 4.5 (v1.1): the lane opens at the full scale THIS server stores for
     * the output device - {@code dacFsVoltageAmpl} is the card's left
     * full-scale in peak-amplitude form ({@code × √2}, the same conversion the
     * desktop's own output profile makes), and {@code rightLaneScale} is
     * {@code fsLeft/fsRight} for a card whose two DACs differ.
     *
     * <p>Calibration lives where the device is connected, so the bench's own
     * numbers are not merely the starting point - they are the ONLY ones.  A
     * client used to be able to push its own {@code dacFsVoltageAmpl} over these,
     * and two clients with different local cards then drove the same output to
     * different levels from the same entered amplitude.  The bench's card is the
     * single converter, so the setter no longer reads that field and a client no
     * longer sends it.
     *
     * <p>Both fields are written on EVERY open, defaults included: one connection
     * opens one generator at a time but may open several in a row, and a lane
     * reopened on an uncalibrated device must not inherit the previous device's
     * full scale.
     *
     * <p><b>A card must offer BOTH full scales, and both above zero, or neither
     * is used.</b>  {@link #DEFAULT_DAC_FS_VOLTAGE_AMPL} says why: the full scale
     * is the DIVISOR of the amplitude scale, so a zero there is not a quiet lane
     * but a division by zero rendered into the DAC - and a zero on the right
     * would make the lane ratio infinite and drive that side into the clipper.
     * A card row that was never calibrated carries exactly those zeros, and it is
     * an uncalibrated device however it got there: the 1.0 placeholder is the
     * honest answer for it.  The resolution itself refuses such a row (an
     * uncalibrated card answers no calibration at all), so this test is the belt
     * that keeps a divisor of zero out of the DDS whatever the store learns to
     * hand back.
     */
    private void applyCardCalibration(DeviceLock lock, String name) {
        DeviceCalibration cal = catalog.calibration(lock, name);
        if (cal == null || cal.fsRmsLeft() <= 0.0 || cal.fsRmsRight() <= 0.0) {
            this.dacFsVoltageAmpl = DEFAULT_DAC_FS_VOLTAGE_AMPL;
            this.rightLaneScale = DEFAULT_RIGHT_LANE_SCALE;
            return;
        }
        this.dacFsVoltageAmpl = cal.fsRmsLeft() * Constants.SQRT2;
        this.rightLaneScale = cal.fsRmsLeft() / cal.fsRmsRight();
    }

    /** Spec 4.5: "stops, closes server-side playback". */
    public void close(Integer id) {
        require(id);
        closeAll();
    }

    /** Spec 4.3: {@code device.release} "also closes any open stream/generator
     *  on it".  Silent for any other device - a connection releasing its input
     *  must not stop the tone it is driving the device under test with. */
    public void closeDevice(DeviceLock lock) {
        if (lock.equals(device)) {
            closeAll();
        }
    }

    /**
     * The generator's half of the teardown of spec 4.1 - reached from
     * {@code gen.close}, from {@code bye}, from a dead keepalive and from the
     * transport vanishing, so it is idempotent and assumes nothing about what is
     * still open.
     *
     * <p>The uploaded files go last and unconditionally: spec 3 keeps them
     * "until the referencing connection closes", and a client that vanished
     * mid-measurement would otherwise leave the operator's audio in the server's
     * heap with no handle left anywhere to delete it by.
     *
     * <p>Every step is guarded on its own.  Written as a bare sequence, a native
     * {@code close()} that threw would skip the two steps after it: the QA40x
     * rate claim would stay keyed on this device FOREVER - refusing every later
     * {@code capture.open}/{@code gen.open} at another rate for the life of the
     * process - and the uploaded bytes would never be dropped.  A throw earlier
     * still would skip the close and leave the DAC line open with no owner left
     * to close it.
     */
    public void closeAll() {
        ticker.stop();
        step(this::stopEmission, "stopping the emission");
        step(() -> stopFileSource(), "stopping the file source");
        step(this::closeLane, "closing the output line");
        step(this::releaseRateClaim, "releasing the QA40x rate claim");
        int dropped = files.releaseAll(this);
        // Spec 4.5 pushes ev.gen.state on EVERY state change, and a generator
        // that has stopped and given its line back is the largest one there is.
        // It goes out BEFORE the handle is dropped, because a state push has
        // nothing left to describe once genId is NO_GEN - and without it a
        // device.release on the output device would leave a live connection
        // still believing its tone is playing.
        pushState();
        if (genId != NO_GEN && log.isInfoEnabled()) {
            log.info("net gen {}: closed, {} uploaded file(s) dropped", genId, dropped);
        }
        genId = NO_GEN;
    }

    /** One teardown step, guarded: what it hands back is given back whether or
     *  not the step before it succeeded - see {@link #closeAll()}.  Throwable,
     *  because the steps end in a native line close and JNA answers a device that
     *  has gone with an Error, not an exception. */
    private void step(Runnable action, String what) {
        try {
            action.run();
        } catch (Throwable t) {
            if (log.isErrorEnabled()) {
                log.error("net gen {}: {} failed - the teardown goes on", genId, what, t);
            }
        }
    }

    /** Gives the output line back.  Cleared first, so a close that throws cannot
     *  leave a handle to a line nobody may touch again.  BOUNDED: a native
     *  close on a dead line can block for minutes (csjsound holds the line
     *  monitor across the blocked native write), and this runs on the
     *  session's ONE command thread - an unbounded close here wedged that
     *  thread and starved every later request of the connection
     *  (devices.list, cards.list, gen.*) while the beacon kept answering. */
    private void closeLane() {
        AudioPlayback open = playback;
        playback = null;
        if (open != null) {
            Closeables.tryBounded("net gen " + genId + ": output line close",
                    STOP_JOIN_MS, open::close);
        }
    }

    /** Drops this lane's share of the QA40x's one clock - AFTER the line is
     *  actually back, so no other session can re-clock the analyzer in between. */
    private void releaseRateClaim() {
        if (device != null) {
            qa40x.releaseRate(device);
            device = null;
            claimedRateHz = 0;
        }
    }

    // -------------------------------------------------------------------------
    // gen.config - the partial update of spec 4.5
    // -------------------------------------------------------------------------

    /**
     * Spec 4.5: "partial update - only present fields are applied".  Which is
     * why every value is read as a nullable and an absent one changes nothing:
     * a client that sends {@code {frequency}} alone must not have its amplitude
     * silently reset to a default it never asked for.
     *
     * <p>A new nominal frequency drops the trim that went with it.  The trim is
     * an ABSOLUTE corrected frequency (spec 4.5) computed by the client's FLL
     * for the tone that WAS playing; keeping it across a retune would pin the
     * generator to the old tone and the servo would have to fight its way back.
     */
    public void config(Integer id, NetMessage message) {
        require(id);
        GenSignalForm previous = form;
        String requested = message.optString(NetFields.FORM);
        if (requested != null) {
            form = formOf(requested);
        }
        Double frequency = message.optDouble(NetFields.FREQUENCY);
        if (frequency != null) {
            nominalHz = frequency;
            trimHz = null;
        }
        Double amplitude = message.optDouble(NetFields.AMPLITUDE_VRMS);
        if (amplitude != null) {
            amplitudeVrms = amplitude;
        }
        // dacFsVoltageAmpl is deliberately NOT read from the client any more.  The
        // conversion from volts to the DAC's full scale belongs to the machine the
        // DAC is plugged into, which is this one, and it is made from THIS server's
        // card (applyCardCalibration).  A client that pushed its own number
        // overwrote the bench's card, so the same amplitude entered on two clients
        // came out of the same output at two different levels.  The bench's card
        // alone converts.  This is the server half of the belt; the clients no
        // longer send the field at all.
        Boolean fileLoop = message.optBoolean(NetFields.FILE_LOOP);
        if (fileLoop != null) {
            // Applied to the RUNNING file if there is one, and ignored otherwise
            // exactly as every other inapplicable field is.  The player re-reads
            // the flag at each end of stream, so ticking it mid-play makes the
            // current lap loop and unticking it lets that lap FINISH rather than
            // cutting it off - the local behaviour, now reachable over the wire.
            FilePlaybackGenerator playing = filePlayback;
            if (playing != null) {
                playing.setLoop(fileLoop);
            }
        }
        Double laneScale = message.optDouble(NetFields.RIGHT_LANE_SCALE);
        if (laneScale != null) {
            rightLaneScale = laneScale;
            // Straight to the line that is open: the quantizer reads the scales
            // per block, so a recalibrated card takes effect on the tone that is
            // playing instead of at the next open.
            applyLaneScale();
        }
        Double rectDuty = message.optDouble(NetFields.RECTANGLE_DUTY);
        if (rectDuty != null) {
            rectangleDuty = rectDuty;
        }
        Double triDuty = message.optDouble(NetFields.TRIANGLE_DUTY);
        if (triDuty != null) {
            triangleDuty = triDuty;
        }
        configureDual(sub(message, NetFields.DUAL));
        NetMessage sweep = sub(message, NetFields.SWEEP);
        configureSweep(sweep);
        configureCompensation(sub(message, NetFields.COMPENSATION));
        configureDualCompensation(sub(message, NetFields.DUAL_COMPENSATION));
        if (Boolean.TRUE.equals(message.optBoolean(NetFields.CLEAR_COMPENSATION))) {
            clearCompensation();
        }
        applyLive(previous, sweep);
        pushState();
    }

    private void configureDual(NetMessage dual) {
        if (dual == null) {
            return;
        }
        Double frequency2 = dual.optDouble(NetFields.FREQUENCY2);
        if (frequency2 != null) {
            nominal2Hz = frequency2;
            trim2Hz = null;
        }
        Double first = dual.optDouble(NetFields.AMP1_PCT);
        if (first != null) {
            amp1Pct = first;
        }
        Double second = dual.optDouble(NetFields.AMP2_PCT);
        if (second != null) {
            amp2Pct = second;
        }
    }

    private void configureSweep(NetMessage sweep) {
        if (sweep == null) {
            return;
        }
        Double f0 = sweep.optDouble(NetFields.F0);
        if (f0 != null) {
            sweepF0 = f0;
        }
        Double f1 = sweep.optDouble(NetFields.F1);
        if (f1 != null) {
            sweepF1 = f1;
        }
        Integer duration = sweep.optInt(NetFields.DURATION_SAMPLES);
        if (duration != null) {
            sweepDurationSamples = duration;
        }
        Integer leadIn = sweep.optInt(NetFields.LEAD_IN_SAMPLES);
        if (leadIn != null) {
            leadInSamples = leadIn;
        }
        Integer fadeIn = sweep.optInt(NetFields.FADE_IN_SAMPLES);
        if (fadeIn != null) {
            fadeInSamples = fadeIn;
        }
        Integer fadeOut = sweep.optInt(NetFields.FADE_OUT_SAMPLES);
        if (fadeOut != null) {
            fadeOutSamples = fadeOut;
        }
        Boolean loop = sweep.optBoolean(NetFields.LOOP);
        if (loop != null) {
            sweepLoop = loop;
        }
    }

    private void configureCompensation(NetMessage comp) {
        if (comp == null) {
            return;
        }
        double[] ratios = doubles(comp, NetFields.AMP_RATIOS);
        int[] harmonics = ints(comp, NetFields.H_NUMS);
        double[] phases = doubles(comp, NetFields.PHI_INITS);
        if (ratios == null || harmonics == null || phases == null) {
            throw new NetException(ErrorCode.BAD_REQUEST,
                    "compensation needs ampRatios, hNums and phiInits together");
        }
        if (ratios.length != harmonics.length || ratios.length != phases.length) {
            throw new NetException(ErrorCode.BAD_REQUEST,
                    "compensation arrays must be the same length");
        }
        compAmpRatios = ratios;
        compHNums = harmonics;
        compPhiInits = phases;
    }

    private void configureDualCompensation(NetMessage comp) {
        if (comp == null) {
            return;
        }
        double[] ratios = doubles(comp, NetFields.AMP_RATIOS);
        int[] aCoef = ints(comp, NetFields.A_COEF);
        int[] bCoef = ints(comp, NetFields.B_COEF);
        double[] phases = doubles(comp, NetFields.PHI_INITS);
        if (ratios == null || aCoef == null || bCoef == null || phases == null) {
            throw new NetException(ErrorCode.BAD_REQUEST,
                    "dualCompensation needs ampRatios, aCoef, bCoef and phiInits together");
        }
        if (ratios.length != aCoef.length || ratios.length != bCoef.length
                || ratios.length != phases.length) {
            throw new NetException(ErrorCode.BAD_REQUEST,
                    "dualCompensation arrays must be the same length");
        }
        dualCompAmpRatios = ratios;
        dualCompA = aCoef;
        dualCompB = bCoef;
        dualCompPhiInits = phases;
    }

    /** Spec 4.5's {@code clearCompensation}: the tables go, and a compensated
     *  form falls back to its plain tone - the same pair the generator's own
     *  {@code clearCompensation} makes. */
    private void clearCompensation() {
        compAmpRatios = null;
        compHNums = null;
        compPhiInits = null;
        dualCompAmpRatios = null;
        dualCompA = null;
        dualCompB = null;
        dualCompPhiInits = null;
        if (form == GenSignalForm.SINE_COMP) {
            form = GenSignalForm.SINE;
        } else if (form == GenSignalForm.DUAL_TONE_COMP) {
            form = GenSignalForm.DUAL_TONE;
        }
        SignalGenerator gen = generator;
        if (gen != null) {
            gen.clearCompensation();
        }
    }

    // -------------------------------------------------------------------------
    // gen.start / gen.stop and the play thread
    // -------------------------------------------------------------------------

    /**
     * Spec 4.5: emission begins.  "Starting a sweep resets the sweep position
     * and injects a {@code sweepStart} marker into this connection's open
     * capture streams" - the reset is the playback path's own (it rewinds after
     * its JIT warmup, which would otherwise have eaten the first samples), and
     * the marker goes out once the render buffer is pre-filled, so the first PCM
     * byte after it really is the one aligned with sweep sample 0.
     *
     * <p>A second start restarts from sample 0 rather than being ignored: a
     * freq-response client asks for exactly that between sweeps, and a
     * silently-ignored start would leave it waiting for a marker that never
     * comes.
     */
    public void start(Integer id) {
        require(id);
        endFilePlayback();
        stopEmission();
        beginEmission(buildGenerator(), true);
        pushState();
        // The emission lines live on the wire-command path, not inside
        // beginEmission/stopEmission - every start quietly restarts via
        // stopEmission, and a teardown must not read as an operator stop.
        if (log.isInfoEnabled()) {
            log.info("net gen {}: emission started on {} (card {})", genId,
                    deviceName, cardOrNone());
        }
    }

    /** Spec 4.5: emission stops.  The line stays open - only {@code gen.close}
     *  gives it back. */
    public void stop(Integer id) {
        require(id);
        stopEmission();
        pushState();
        if (log.isInfoEnabled()) {
            log.info("net gen {}: emission stopped on {} (card {})", genId,
                    deviceName, cardOrNone());
        }
    }

    /** The emission lines' card slot for a device no card is in force for -
     *  the same "none" the capture lines print, never a null. */
    private String cardOrNone() {
        return cardName == null ? "none" : cardName;
    }

    /**
     * Hands {@code gen} to the output line on a thread of its own and waits for
     * the hardware buffer to fill, which is what {@code readyLatch} means - the
     * shape the desktop controller has proven: a per-session stop flag (so a
     * stop aimed at a previous, possibly wedged, thread can never be revoked by
     * the next one) and a maximum-priority daemon (an exclusive-mode driver
     * underruns the moment the render thread misses an event tick).
     */
    private void beginEmission(SignalGenerator gen, boolean announceSweep) {
        AudioPlayback lane = playback;
        if (lane == null) {
            throw new NetException(ErrorCode.DEVICE_ERROR,
                    "the output line is not open");
        }
        AtomicBoolean sessionStop = new AtomicBoolean();
        CountDownLatch ready = new CountDownLatch(1);
        // Captured as locals: the play thread must read nothing this session's
        // own thread may be rewriting while it runs.
        DeviceLock lockedDevice = device;
        int openGenId = genId;
        Thread thread = new Thread(() -> {
            try {
                lane.play(gen, sessionStop, ready);
            } catch (Throwable t) {
                // Throwable, and the finally below is why it matters so much: an
                // Error out of the native render loop (JNA, on a device pulled
                // mid-tone) used to skip BOTH the report and the close while the
                // latch still counted down - so the request was answered "the tone
                // started", the client believed it for as long as it stayed
                // connected, and the lane was never given back.
                reportDeviceError(openGenId, lockedDevice, t);
            } finally {
                // A failure before the pre-fill finished would otherwise leave
                // the starting request waiting out the whole timeout.
                ready.countDown();
            }
        }, PLAY_THREAD + genId);
        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
        this.generator = gen;
        this.stopFlag = sessionStop;
        this.playThread = thread;
        thread.start();
        awaitReady(ready);
        this.running = true;
        this.startedAtMs = clockMs.getAsLong();
        if (announceSweep && isSweep(form)) {
            captures.markSweepStart();
        }
        ticker.start(STATE_INTERVAL_MS, () -> worker.submit(this::tick));
    }

    private void awaitReady(CountDownLatch ready) {
        try {
            if (ready.await(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopEmission();
            throw new NetException(ErrorCode.DEVICE_ERROR,
                    "interrupted while waiting for the output stream to start");
        }
        stopEmission();
        throw new NetException(ErrorCode.DEVICE_ERROR,
                "the output stream did not start within " + READY_TIMEOUT_MS + " ms");
    }

    /** Stops the render loop and joins it.  Idempotent, and safe when nothing
     *  ever started - every teardown path calls it blindly. */
    private void stopEmission() {
        ticker.stop();
        AtomicBoolean flag = stopFlag;
        if (flag != null) {
            flag.set(true);
        }
        Thread thread = playThread;
        if (thread != null) {
            try {
                thread.join(STOP_JOIN_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive() && log.isWarnEnabled()) {
                log.warn("net gen {}: the play thread did not exit within {} ms - its "
                        + "stop flag stays set, so it can never resume", genId, STOP_JOIN_MS);
            }
        }
        playThread = null;
        stopFlag = null;
        generator = null;
        running = false;
    }

    /**
     * Spec 4.3's {@code ev.device.error} for the OUTPUT direction: a render loop
     * that died has no request left to be answered in - {@code gen.start} was
     * answered when the buffer filled - so this is the only way the client can
     * learn that the tone it thinks is playing stopped.
     *
     * <p>And the lane goes with it - the honest-loss rule is the error AND a
     * close.  Reporting alone would leave the line open with no render thread
     * behind it while every {@code ev.gen.state} kept claiming {@code running},
     * so the client would go on trusting a tone that stopped.  The close is
     * ENQUEUED onto the session's own thread: this runs on the play thread,
     * which is the very thread the close has to join.
     */
    private void reportDeviceError(int failedGenId, DeviceLock failedDevice,
            Throwable fault) {
        if (log.isErrorEnabled()) {
            log.error("net gen {}: the output lane on {} failed - telling the client",
                    failedGenId, failedDevice, fault);
        }
        // This event is ALWAYS about a device, so it always carries a reason -
        // the backend's own reading of its own fault, UNKNOWN included.  The
        // detail keeps the raw text for the log on the far side; the reason is
        // what the far side's operator gets to read.
        DeviceFailureReason reason = (playbackBackend == null)
                ? DeviceFailureReason.UNKNOWN : playbackBackend.classifyFailure(fault);
        channel.send(new NetMessage(MessageType.EV_DEVICE_ERROR)
                .put(NetFields.DIRECTION, NetFields.OUTPUT)
                .put(NetFields.DETAIL, failedDevice + ": " + fault)
                .put(NetFields.REASON, reason.name()));
        worker.submit(() -> closeFailedLane(failedGenId));
    }

    /** Closes the generator whose render loop died - unless it has already gone
     *  and the handle now belongs to a generator this failure says nothing
     *  about. */
    private void closeFailedLane(int failedGenId) {
        if (genId != NO_GEN && genId == failedGenId) {
            closeAll();
        }
    }

    /**
     * Builds the generator the current commanded state describes.  It is rebuilt
     * at every start rather than kept and mutated because the sweep forms and
     * the two-tone forms stand up dedicated DDS state (a pre-rendered chirp
     * buffer, a second phase accumulator) that no setter can hot-swap - the same
     * reason the desktop controller restarts instead of live-switching them.
     */
    private SignalGenerator buildGenerator() {
        SignalGenerator gen;
        if (form == GenSignalForm.LINEAR_SWEEP) {
            gen = new SignalGenerator(sweepF0, sweepF1, rateHz, sweepSamples(),
                    amplitudeVrms, dacFsVoltageAmpl);
        } else if (form == GenSignalForm.LOG_SWEEP) {
            requireLogSweepBand();
            gen = new SignalGenerator(sweepF0, sweepF1, sweepSamples(),
                    Math.max(0, leadInSamples), rateHz, amplitudeVrms, dacFsVoltageAmpl);
        } else {
            gen = new SignalGenerator(form, emitHz(), rateHz, amplitudeVrms,
                    dacFsVoltageAmpl);
        }
        gen.setRectangleDuty(rectangleDuty);
        gen.setTriangleDuty(triangleDuty);
        if (isSweep(form)) {
            gen.setSweepParams(sweepLoop, fadeInSamples, fadeOutSamples);
        }
        if (form.isDualTone()) {
            gen.setDualToneFrequency2(emit2Hz());
            gen.setDualToneAmplitudes(amp1Pct, amp2Pct);
        }
        applyCompensationTo(gen);
        return gen;
    }

    /**
     * Loads the commanded compensation tables into {@code gen} - at a start,
     * where they decide what is built, and on the RUNNING generator, where they
     * are the whole point.
     *
     * <p>{@code SignalGenerator.applyCompensation} publishes the phasors as one
     * immutable set before the form flips, precisely so the predistortion loop
     * can hot-swap a round's corrections with no audio restart.  A table that
     * only ever reached the next {@code gen.start} would have that loop measure
     * the same distortion round after round while the server answered every
     * {@code gen.config} with {@code ok}.
     */
    private void applyCompensationTo(SignalGenerator gen) {
        if (form == GenSignalForm.SINE_COMP && compAmpRatios != null) {
            gen.applyCompensation(compAmpRatios, compHNums, compPhiInits);
        } else if (form == GenSignalForm.DUAL_TONE_COMP && dualCompAmpRatios != null) {
            gen.applyDualToneCompensation(dualCompAmpRatios, dualCompA, dualCompB,
                    dualCompPhiInits);
        }
    }

    /** The Farina chirp is {@code sin(K·(exp(t/L) − 1))} with
     *  {@code L = T/ln(f1/f0)}: a zero or equal band divides by zero or by a
     *  logarithm of one, and the generator would render a buffer of NaN into the
     *  DAC rather than fail. */
    private void requireLogSweepBand() {
        if (sweepF0 <= 0 || sweepF1 <= 0 || sweepF0 == sweepF1) {
            throw new NetException(ErrorCode.BAD_REQUEST,
                    "a log sweep needs two different positive frequencies, got f0="
                            + sweepF0 + " f1=" + sweepF1);
        }
    }

    private int sweepSamples() {
        return Math.max(MIN_SWEEP_SAMPLES, sweepDurationSamples);
    }

    /**
     * Pushes what CAN be live-applied onto a running generator, and restarts it
     * for what cannot.  Only a form change across the rebuild boundary forces
     * the restart - re-sending the same form with new numbers stays live, which
     * is what a client sliding a frequency or an amplitude expects.
     *
     * <p>{@code sweep} is the sub-object the client sent, or null when it sent
     * none, because for the sweep block "only present fields are applied" (spec
     * 4.5) is not a nicety: every one of those setters re-renders the chirp and
     * asks the DDS to restart it at sample 0.  A freq-response client trimming
     * its level mid-sweep with {@code gen.config{amplitudeVrms}} would otherwise
     * have the sweep silently begin again underneath the record it is
     * assembling - and with no {@code sweepStart} marker to notice it by.
     */
    private void applyLive(GenSignalForm previous, NetMessage sweep) {
        if (generator == null) {
            return;                     // nothing runs; the next start builds it
        }
        if (previous != form && (needsRebuild(previous) || needsRebuild(form))) {
            stopEmission();
            // A sweep that begins here begins at sample 0 like any other, so it
            // is announced like any other (spec 4.5): the client that asked for
            // the form is waiting for exactly that marker to bound its record.
            beginEmission(buildGenerator(), true);
            return;
        }
        SignalGenerator gen = generator;
        gen.setForm(form);
        gen.setFrequency(emitHz());
        gen.setAmplitudeVrms(amplitudeVrms);
        gen.setDacFsVoltageAmpl(dacFsVoltageAmpl);
        gen.setRectangleDuty(rectangleDuty);
        gen.setTriangleDuty(triangleDuty);
        if (form.isDualTone()) {
            gen.setDualToneFrequency2(emit2Hz());
            gen.setDualToneAmplitudes(amp1Pct, amp2Pct);
        }
        applyCompensationTo(gen);
        if (isSweep(form) && sweep != null) {
            applySweepLive(gen, sweep);
        }
    }

    /** The sweep fields THIS {@code gen.config} carried, and only those - see
     *  {@link #applyLive} for what re-applying the rest would cost.  The lead-in
     *  is absent on purpose: the log sweep takes it at construction, so it
     *  reaches the generator at the next start. */
    private void applySweepLive(SignalGenerator gen, NetMessage sweep) {
        if (sweep.optDouble(NetFields.F0) != null) {
            gen.setSweepFreqStart(sweepF0);
        }
        if (sweep.optDouble(NetFields.F1) != null) {
            gen.setSweepFreqEnd(sweepF1);
        }
        if (sweep.optInt(NetFields.DURATION_SAMPLES) != null) {
            gen.setSweepDurationSamples(sweepSamples());
        }
        if (sweep.optBoolean(NetFields.LOOP) != null
                || sweep.optInt(NetFields.FADE_IN_SAMPLES) != null
                || sweep.optInt(NetFields.FADE_OUT_SAMPLES) != null) {
            gen.setSweepParams(sweepLoop, fadeInSamples, fadeOutSamples);
        }
    }

    /** True for the forms whose DDS state a live form swap cannot build. */
    private boolean needsRebuild(GenSignalForm candidate) {
        return isSweep(candidate) || candidate.isDualTone();
    }

    private boolean isSweep(GenSignalForm candidate) {
        return candidate == GenSignalForm.LINEAR_SWEEP
                || candidate == GenSignalForm.LOG_SWEEP;
    }

    // -------------------------------------------------------------------------
    // gen.fftGrid / gen.trim / gen.trim2 / gen.trimReset
    // -------------------------------------------------------------------------

    /**
     * Spec 4.5: "the server snaps the emitted frequency to the FFT bin grid
     * {@code k·rate/fftSize} when enabled - SAME math as the client-side snap, so
     * both compute identical values".  Which is the whole reason the snap lives
     * in the core as {@link FftBinSnap} rather than being re-derived here: two
     * implementations of one rounding rule would disagree in the last bin sooner
     * or later, and the client would spend the difference chasing its own tail
     * through the frequency-lock loop.
     */
    public void fftGrid(Integer id, Integer size, Boolean snap) {
        require(id);
        if (size != null) {
            fftSize = size;
        }
        if (snap != null) {
            snapEnabled = snap;
        }
        applyEmitFrequencies();
        pushState();
    }

    /** Spec 4.5: the FLL's actuator - "absolute corrected frequency for tone 1".
     *  The servo itself lives in the client's FFT; this only obeys. */
    public void trim(Integer id, Double hz) {
        require(id);
        trimHz = finiteHz(hz);
        applyEmitFrequencies();
        pushState();
    }

    /** The same for the second tone of a two-tone signal. */
    public void trim2(Integer id, Double hz) {
        require(id);
        trim2Hz = finiteHz(hz);
        applyEmitFrequencies();
        pushState();
    }

    /** Spec 4.5: "back to nominal" - both tones slide onto their snapped
     *  configured frequencies. */
    public void trimReset(Integer id) {
        require(id);
        trimHz = null;
        trim2Hz = null;
        applyEmitFrequencies();
        pushState();
    }

    private Double finiteHz(Double hz) {
        if (hz == null || !Double.isFinite(hz)) {
            throw new NetException(ErrorCode.BAD_REQUEST,
                    "a trim needs a finite frequency in hz, got " + hz);
        }
        return hz;
    }

    private void applyEmitFrequencies() {
        SignalGenerator gen = generator;
        if (gen == null) {
            return;
        }
        gen.setFrequency(emitHz());
        if (form.isDualTone()) {
            gen.setDualToneFrequency2(emit2Hz());
        }
    }

    /** What the DDS is actually driven at: the trim when the client sent one,
     *  else the nominal on the FFT bin grid. */
    private double emitHz() {
        Double trim = trimHz;
        return trim != null ? trim
                : FftBinSnap.snapIfEnabled(form, rateHz, fftSize, snapEnabled, nominalHz);
    }

    /** The second tone's emitted frequency.  It snaps as a two-tone value
     *  whatever the form says, because that is the only form in which it is
     *  emitted at all, and each tone lands on its own bin centre. */
    private double emit2Hz() {
        Double trim = trim2Hz;
        return trim != null ? trim
                : FftBinSnap.snapIfEnabled(GenSignalForm.DUAL_TONE, rateHz, fftSize,
                        snapEnabled, nominal2Hz);
    }

    /**
     * What spec 4.5 promises {@code emitHz} is: "the post-snap, post-trim
     * frequency ACTUALLY EMITTED - clients feed their FFT/scope hint plumbing
     * from these, never from the nominal values".
     *
     * <p>Which is why a sweep and the noise forms report none.  They emit no
     * single frequency at all, and answering with the tone the client last
     * typed would have every consumer that believed the field - as the spec
     * tells it to - draw a marker on a line that is not on the wire.
     */
    private double reportedEmitHz() {
        return emitsSingleTone() ? emitHz() : NO_EMITTED_HZ;
    }

    /** The same for the second tone, which only the two-tone forms emit at
     *  all: a single-tone form reporting one would put a phantom second line
     *  in the client's spectrum. */
    private double reportedEmit2Hz() {
        return form.isDualTone() ? emit2Hz() : NO_EMITTED_HZ;
    }

    /** True for the forms driven at ONE frequency a client can point an
     *  analyzer at.  The noise forms have no period at all
     *  ({@link GenSignalForm#isPeriodic()}), and a sweep is a different
     *  frequency every sample. */
    private boolean emitsSingleTone() {
        return form.isPeriodic() && !isSweep(form);
    }

    // -------------------------------------------------------------------------
    // gen.playFile / gen.stopFile
    // -------------------------------------------------------------------------

    /**
     * Spec 4.5: "plays an uploaded file (spec 3) through the generator lane".
     *
     * <p>The lane is REOPENED at the file's own rate and depth, exactly as the
     * desktop file player opens its line on the file's format: nothing resamples
     * on this path, so a file played through a lane clocked at another rate
     * would simply come out at the wrong pitch - and that is also what makes the
     * {@code rate}/{@code bits} of the state event's {@code file} object mean
     * something.  The QA40x's one clock is re-asked of the guard on the way, so
     * a file whose rate would re-clock somebody else's running capture is
     * refused instead of silently ruining their measurement.
     *
     * <p>The file also takes the lane away from the tone, the same exclusivity
     * the desktop has between its two engines: one DAC, one source.
     */
    public void playFile(Integer id, String fileId, Boolean loop) {
        require(id);
        byte[] content = files.get(fileId);
        if (content == null || !files.claim(fileId, this)) {
            throw new NetException(ErrorCode.NO_SUCH_FILE,
                    "no uploaded file with id " + fileId);
        }
        endFilePlayback();
        stopEmission();
        Path staged = writeTemp(content, files.typeOf(fileId));
        FilePlaybackGenerator player;
        try {
            player = new FilePlaybackGenerator(staged.toFile(), Boolean.TRUE.equals(loop));
        } catch (Exception e) {
            deleteTemp(staged);
            throw new NetException(ErrorCode.BAD_REQUEST,
                    "cannot decode uploaded file " + fileId + ": " + e);
        }
        this.fileTemp = staged;
        this.filePlayback = player;
        try {
            reopenLane(player.getFileSampleRate(), player.getFileBitDepth());
            beginEmission(player, false);
        } catch (NetException e) {
            // The lane goes back to the tone's format as well as the file going:
            // leaving it clocked for a file that never played would have the next
            // gen.start emit the tone at the wrong pitch.
            endFilePlayback();
            throw e;
        }
        if (log.isInfoEnabled()) {
            log.info("net gen {}: playing {} at {} Hz / {} bit, loop {}", genId, fileId,
                    player.getFileSampleRate(), player.getFileBitDepth(), loop);
        }
        pushState();
    }

    /** Spec 4.5: the file stops and the lane goes back to the format
     *  {@code gen.open} granted, ready for the tone again. */
    public void stopFile(Integer id) {
        require(id);
        endFilePlayback();
        pushState();
    }

    /** The file this session is playing, or null - the one seam a test needs to
     *  see that a live {@code gen.config} reached the player itself. */
    FilePlaybackGenerator playingFile() {
        return filePlayback;
    }

    /** The file goes and the tone's lane comes back - {@code gen.stopFile}, and
     *  a {@code gen.start} taking the DAC back for the DDS. */
    private void endFilePlayback() {
        if (stopFileSource()) {
            reopenLane(rateHz, bits);
        }
    }

    /** Stops the file source and gives its decoder and staged copy back, without
     *  touching the output line - the teardown wants the line closed, not
     *  reopened, so the two callers ask for different things.
     *
     *  @return whether a file was playing at all */
    private boolean stopFileSource() {
        if (filePlayback == null) {
            return false;
        }
        stopEmission();
        filePlayback.close();
        filePlayback = null;
        deleteTemp(fileTemp);
        fileTemp = null;
        return true;
    }

    /**
     * Reopens the output line at another format, keeping the QA40x rate claim
     * honest across the swap.
     *
     * <p>The claim is moved BEFORE the line is touched: {@link Qa40xGuard} keys
     * the claim on the device, so the old entry has to go before the new rate
     * can be asked for - and if the answer is no, the claim THE LANE WAS RUNNING
     * UNDER goes back and the line that is playing is left exactly as it was.
     * Not the {@code gen.open} rate: coming back from a file at another rate,
     * that is not the rate the lane is clocked at, and the guard would then hold
     * a claim describing a lane nobody has.
     */
    private void reopenLane(int rate, int bitDepth) {
        int running = claimedRateHz;
        qa40x.releaseRate(device);
        try {
            qa40x.claimRate(device, rate);
        } catch (NetException e) {
            restoreClaim(running);
            throw e;
        }
        claimedRateHz = rate;
        closeLane();
        playback = openLane(catalog.resolve(device, deviceName), rate, bitDepth);
    }

    /** Puts back the claim the lane is still running under after a refused swap.
     *  It cannot be allowed to throw: a second refusal here would replace the
     *  refusal the client actually asked about, and the lane that is still
     *  playing would be described by no claim at all. */
    private void restoreClaim(int rate) {
        try {
            qa40x.claimRate(device, rate);
            claimedRateHz = rate;
        } catch (NetException e) {
            if (log.isWarnEnabled()) {
                log.warn("net gen {}: cannot re-claim {} Hz on {} after a refused lane "
                        + "swap: {}", genId, rate, device, e.toString());
            }
        }
    }

    /** Pushes the per-lane scale to the line that is open, if one is.  The LEFT
     *  lane is always 1.0 - see {@link #LEFT_LANE_SCALE}. */
    private void applyLaneScale() {
        AudioPlayback lane = playback;
        if (lane != null) {
            lane.setChannelScale(LEFT_LANE_SCALE, rightLaneScale);
        }
    }

    /**
     * The output lane, opened - with the ONE further attempt a stale device
     * snapshot earns.
     *
     * <p>A backend whose device list is a start-up snapshot fails every open on a
     * card that was unplugged and plugged back in, because it is still describing
     * where the card USED to be - the macOS bench symptom, where the interface is
     * sitting right there and every {@code gen.open} answers an internal
     * PortAudio error until the server is restarted.  The catalog answers whether
     * that is what happened, rebuilds the list if it is, and hands back the ref as
     * it stands NOW; a null from it means a retry could not help, and the first
     * refusal is the answer.  Exactly one retry, never a loop.
     */
    private AudioPlayback openLane(DeviceRef ref, int rate, int bitDepth) {
        // Hoisted out of the try so the refusal can ask the SAME backend what
        // its own failure meant: the text below is this server's language and
        // its driver codes are nobody's, so the client is sent the machine
        // readable reason beside it and says it in the operator's language.
        AudioDeviceManager playbackManager = audio.playbackManager(ref);
        try {
            return openedLane(playbackManager, ref, rate, bitDepth);
        } catch (Exception first) {
            DeviceRef fresh = catalog.refreshedForRetry(device, deviceName);
            if (fresh == null) {
                throw refusal(playbackManager, first, rate, bitDepth);
            }
            if (log.isInfoEnabled()) {
                log.info("net gen: {} failed to open and the device list was stale - "
                        + "rebuilt it, trying once more: {}", device, first.toString());
            }
            try {
                return openedLane(playbackManager, fresh, rate, bitDepth);
            } catch (Exception retried) {
                throw refusal(playbackManager, retried, rate, bitDepth);
            }
        }
    }

    /** The one refusal wording every attempt ends in. */
    private NetException refusal(AudioDeviceManager playbackManager, Exception failure, int rate,
            int bitDepth) {
        return new NetException(ErrorCode.DEVICE_ERROR, "cannot open " + device
                + " at " + rate + " Hz / " + bitDepth + " bit: " + failure,
                playbackManager.classifyFailure(failure));
    }

    /** ONE attempt at the lane, leaving nothing half-open behind a failure. */
    private AudioPlayback openedLane(AudioDeviceManager playbackManager, DeviceRef ref, int rate,
            int bitDepth) throws Exception {
        AudioPlayback opened = null;
        try {
            // The SPI form via playbackManager: the WIRE-requested rate (spec
            // 4.5), with the WASAPI -> JavaSound render reroute kept.
            opened = playbackManager.openPlayback(ref, rate, bitDepth, ditherBits);
            opened.open();
            opened.setOutputChannels(outputChannels);
            opened.setChannelScale(LEFT_LANE_SCALE, rightLaneScale);
            // Kept for the lane's LATER death: the render loop can fail long
            // after this call returns, and the reading of that fault belongs to
            // the same backend that opened the line.
            this.playbackBackend = playbackManager;
            return opened;
        } catch (Exception e) {
            if (opened != null) {
                opened.close();
            }
            throw e;
        }
    }

    /**
     * Stages an uploaded file where the decoder can reach it.
     *
     * <p>Spec 3 keeps uploads in RAM and this one does too - {@link FileStore}
     * is still the only owner of the bytes.  The copy exists because the shared
     * decoder ({@code PcmFileLoader}, and Nayuki's FLAC reader underneath it)
     * takes a {@code File} and nothing else, and duplicating a WAV/AIFF/FLAC
     * decoder in the server to avoid one temporary file would be a far worse
     * trade.  It is deleted the moment playback ends, on every path including
     * the teardown.
     */
    private Path writeTemp(byte[] content, String mimeType) {
        try {
            Path staged = Files.createTempFile(TEMP_PREFIX, suffixOf(content, mimeType));
            Files.write(staged, content);
            return staged;
        } catch (IOException | RuntimeException e) {
            throw new NetException(ErrorCode.INTERNAL,
                    "cannot stage the uploaded file for playback: " + e);
        }
    }

    /**
     * What to name the staged copy, so the shared decoder routes on it.
     *
     * <p><b>The declared type wins</b> (spec 3, v1.1): the client knows what it
     * picked, and the bytes do not always say.  A FLAC behind an ID3 tag does not
     * start with {@code fLaC}, and nothing in an AIFF's first four bytes made the
     * old sniff choose {@code .aiff} - it fell through to {@code .wav} and left the
     * JDK to cope.  A type that arrived settles both.
     *
     * <p>The content sniff stays as the FALLBACK, unchanged and unwidened, for an
     * upload that declared no type or one this server does not know - which is
     * exactly what every upload did before the type travelled.
     *
     * <p>Package-private so the routing rule can be pinned directly: it is a pure
     * function of the bytes and the declared type, and the staged file it names is
     * deleted the moment playback ends, so there is nothing left to observe it by
     * afterwards.
     */
    String suffixOf(byte[] content, String mimeType) {
        String declared = mimeType == null ? "" : mimeType.trim().toLowerCase(Locale.ROOT);
        // A Content-Type may carry parameters ("audio/wav; charset=binary");
        // only the type itself decides.
        int parameters = declared.indexOf(';');
        if (parameters >= 0) {
            declared = declared.substring(0, parameters).trim();
        }
        switch (declared) {
            case PcmFileLoader.MIME_FLAC: return SUFFIX_FLAC;
            case PcmFileLoader.MIME_AIFF: return SUFFIX_AIFF;
            case PcmFileLoader.MIME_WAV:  return SUFFIX_WAV;
            default: break;              // absent or unknown: sniff, as before
        }
        if (content.length >= FLAC_MAGIC_BYTES && content[0] == 'f' && content[1] == 'L'
                && content[2] == 'a' && content[3] == 'C') {
            return SUFFIX_FLAC;
        }
        return SUFFIX_WAV;
    }

    private void deleteTemp(Path staged) {
        if (staged == null) {
            return;
        }
        try {
            Files.deleteIfExists(staged);
        } catch (IOException e) {
            if (log.isWarnEnabled()) {
                log.warn("net gen {}: cannot delete the staged file {}: {}", genId,
                        staged, e.toString());
            }
        }
    }

    // -------------------------------------------------------------------------
    // gen.state and the ev.gen.state push
    // -------------------------------------------------------------------------

    /** Spec 4.5's {@code gen.state}: "pull the full state (same payload as
     *  {@code ev.gen.state})" - literally the same builder, so a pull and a push
     *  can never describe the generator differently. */
    public JsonNode state(Integer id) {
        require(id);
        return stateNode();
    }

    /**
     * One position tick, on the session's own thread.  Spec 4.5 asks for the
     * push "on every state change, PLUS ≤ 10 Hz while a sweep or file is
     * running" - so a generator holding a steady tone is silent here: its state
     * has not changed, and a client extrapolating nothing needs no reminder.
     *
     * <p>The ticker also stops itself once a one-shot sweep or a file has run
     * out, after one last push carrying the final position.
     */
    private void tick() {
        if (!running) {
            return;
        }
        FilePlaybackGenerator player = filePlayback;
        boolean sweeping = isSweep(form);
        if (!sweeping && player == null) {
            return;
        }
        pushState();
        if (player != null ? player.isFinished()
                : !sweepLoop && sweepPosition() >= sweepSamples()) {
            ticker.stop();
        }
        if (player != null && player.isFinished()) {
            // A file that ran out must END the session, not merely stop the
            // position pushes.  Nothing else was watching: the generator answers
            // silence for ever once it is finished, so the play loop went on
            // writing digital silence into the DAC indefinitely - the lane never
            // came back to the tone's format, the staged file was never released,
            // and an exclusive-mode device left draining silence for minutes
            // ends up not draining at all.
            //
            // The state carrying finished:true has already gone out above, so the
            // client has its end-of-file before the lane is handed back.
            if (log.isInfoEnabled()) {
                log.info("net gen {}: the file ran out - stopping the lane", genId);
            }
            try {
                endFilePlayback();
                pushState();
            } catch (Throwable handbackFailed) {
                // The handback can be refused - the QA40x guard turning down the
                // tone's rate because another session now holds a different one,
                // or the device refusing to reopen at all.  Swallowed, it would
                // leave the lane clocked for the FILE while rateHz claims the
                // tone's, and the next gen.start would emit at the wrong pitch
                // while the state event reported the commanded frequency.  This
                // class's rule everywhere else is the error AND a close; the EOF
                // path is not allowed to be the exception.  Throwable, like the
                // sibling failure sites: a device pulled mid-reopen answers
                // through JNA with an Error, not an exception, and an Error that
                // escaped here would skip the report and the close both.
                reportDeviceError(genId, device, handbackFailed);
            }
        }
    }

    /** Spec 4.5: {@code ev.gen.state} is pushed on every state change. */
    private void pushState() {
        if (genId == NO_GEN) {
            return;
        }
        NetMessage event = new NetMessage(MessageType.EV_GEN_STATE);
        JsonNode state = stateNode();
        for (Iterator<String> names = state.fieldNames(); names.hasNext();) {
            String field = names.next();
            event.put(field, state.get(field));
        }
        channel.send(event);
    }

    /** The state payload of spec 4.5, in the field order the spec writes it. */
    private JsonNode stateNode() {
        FilePlaybackGenerator player = filePlayback;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(NetFields.GEN_ID, genId);
        data.put(NetFields.RUNNING, running);
        data.put(NetFields.FORM, form.name());
        data.put(NetFields.NOMINAL_HZ, nominalHz);
        data.put(NetFields.EMIT_HZ, reportedEmitHz());
        data.put(NetFields.EMIT2_HZ, reportedEmit2Hz());
        data.put(NetFields.AMPLITUDE_VRMS, amplitudeVrms);
        Map<String, Object> sweep = new LinkedHashMap<>();
        sweep.put(NetFields.ACTIVE, running && isSweep(form));
        sweep.put(NetFields.POS_SAMPLES, sweepPosition());
        // What the sweep is actually BUILT from, not the raw command: a sweep
        // started without a durationSamples runs at the two-sample floor (see
        // sweepSamples), and a client told "0" would size its record off a
        // length nothing is emitting and watch the position never move.  A form
        // that is not a sweep reports the commanded value as it stands - there
        // is no sweep to describe.
        sweep.put(NetFields.DURATION_SAMPLES,
                isSweep(form) ? sweepSamples() : sweepDurationSamples);
        sweep.put(NetFields.LOOP, sweepLoop);
        data.put(NetFields.SWEEP, sweep);
        Map<String, Object> file = new LinkedHashMap<>();
        file.put(NetFields.PLAYING, running && player != null && !player.isFinished());
        file.put(NetFields.POS_SAMPLES, filePosition());
        file.put(NetFields.FINISHED, player != null && player.isFinished());
        file.put(NetFields.RATE, player == null ? 0 : player.getFileSampleRate());
        file.put(NetFields.BITS, player == null ? 0 : player.getFileBitDepth());
        data.put(NetFields.FILE, file);
        return codec.toNode(data);
    }

    /**
     * Where the file is now, in samples - spec 4.5 defines the {@code file}
     * object as the generator's LIVE position.
     *
     * <p>Guarded on {@code running} exactly as {@link #sweepPosition()} is, and
     * for the same reason: {@code gen.stop} leaves the decoder in place so the
     * lane can be resumed, and {@code startedAtMs} is never re-based, so an
     * unguarded elapsed-time position would keep advancing on the wall clock
     * after playback stopped - ten seconds later it would report ten seconds of
     * audio the DAC never carried, and for a looping file it would run past the
     * file length without limit.
     */
    private long filePosition() {
        FilePlaybackGenerator player = filePlayback;
        if (!running || player == null || player.isFinished()) {
            return 0L;
        }
        return elapsedSamples(player.getFileSampleRate());
    }

    /**
     * Where the sweep is now, in samples.
     *
     * <p>Derived from elapsed time rather than counted: the DDS is pulled by the
     * backend's render loop and reports nothing back, and inserting a counter
     * between the two would put a call on the per-sample audio path for a number
     * the client only reads ten times a second.  Elapsed time times the sample
     * rate IS the sample count of a stream that is running - the hardware clock
     * and the wall clock differ by the card's ppm error, which is orders below
     * the batch granularity this position is honest to anyway.
     */
    private long sweepPosition() {
        if (!running || !isSweep(form)) {
            return 0L;
        }
        long emitted = elapsedSamples(rateHz);
        if (form == GenSignalForm.LOG_SWEEP) {
            emitted = Math.max(0L, emitted - leadInSamples);
        }
        // Measured against the cycle the generator was BUILT with, the same one
        // the state reports as durationSamples - a commanded 0 is a two-sample
        // sweep that really is running, not a position frozen at zero.
        int cycle = sweepSamples();
        return sweepLoop ? emitted % cycle : Math.min(emitted, cycle);
    }

    private long elapsedSamples(int rate) {
        long elapsed = clockMs.getAsLong() - startedAtMs;
        return elapsed <= 0 ? 0L : elapsed * rate / MS_PER_S;
    }

    // -------------------------------------------------------------------------
    // Wire helpers
    // -------------------------------------------------------------------------

    /** Every {@code gen.*} but {@code gen.open} names the handle it acts on, and
     *  a wrong or stale one must not silently act on the generator that IS
     *  open. */
    private void require(Integer id) {
        if (genId == NO_GEN || id == null || id != genId) {
            throw new NetException(ErrorCode.BAD_REQUEST,
                    "no generator is open with id " + id);
        }
    }

    /** The next handle, wrapping inside the range rather than counting on
     *  forever. */
    private int freeGenId() {
        int id = nextGenId;
        nextGenId = id == MAX_GEN_ID ? 1 : id + 1;
        return id;
    }

    private GenSignalForm formOf(String name) {
        try {
            return GenSignalForm.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new NetException(ErrorCode.BAD_REQUEST, "unknown waveform: " + name);
        }
    }

    /** Spec 4.5's {@code outputChannels}: which physical DAC lane carries the
     *  signal, named as the server spells it ({@code BOTH}, {@code LEFT},
     *  {@code RIGHT}).  Absent means both, which is what a client that never
     *  heard of per-lane output expects. */
    private OutputChannels outputChannelsOf(String name) {
        if (name == null) {
            return OutputChannels.BOTH;
        }
        try {
            return OutputChannels.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new NetException(ErrorCode.BAD_REQUEST,
                    "unknown output channels: " + name);
        }
    }

    /** A nested {@code gen.config} object as a message in its own right, so its
     *  fields are read through the same "absent is not zero" accessors as the
     *  top-level ones instead of a second set written out by hand. */
    private NetMessage sub(NetMessage message, String field) {
        JsonNode node = message.getNode(field);
        return node.isObject() ? new NetMessage((ObjectNode) node) : null;
    }

    private double[] doubles(NetMessage owner, String field) {
        JsonNode node = owner.getNode(field);
        if (!node.isArray()) {
            return null;
        }
        double[] values = new double[node.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = node.get(i).asDouble();
        }
        return values;
    }

    private int[] ints(NetMessage owner, String field) {
        JsonNode node = owner.getNode(field);
        if (!node.isArray()) {
            return null;
        }
        int[] values = new int[node.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = node.get(i).asInt();
        }
        return values;
    }
}
