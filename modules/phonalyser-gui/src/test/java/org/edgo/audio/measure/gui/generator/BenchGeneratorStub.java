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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.sound.sampled.AudioFormat;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.DeviceFailureReason;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.sound.AbstractPcmCapture;
import org.edgo.audio.measure.sound.AudioCapture;
import org.edgo.audio.measure.sound.AudioDeviceManager;
import org.edgo.audio.measure.sound.AudioPlayback;
import org.edgo.audio.measure.sound.DeviceRef;
import org.edgo.audio.measure.sound.MarkedCapture;
import org.edgo.audio.measure.sound.RemoteGenerator;

import lombok.Getter;
import lombok.Setter;

/**
 * A bench on the far end of a backend that RECORDS instead of sending - the
 * seam {@link GeneratorController} routes through when the DDS is not in this
 * process.
 *
 * <p>It records rather than mocks because what these tests ask is "did the
 * setting leave this machine, as that command, in that order": the value, the
 * command and its position relative to {@code gen.start} are all separately
 * wrong-able, and only the recorded sequence has all three.  Every routing
 * defect this stub exists for was a method that silently sent nothing.
 *
 * <p>Reached the ordinary way, through {@code AudioBackend}: the manager is
 * built by {@link BenchGeneratorStubProvider} on the service-loader contract and
 * cached per backend type, so the test drives exactly the lookup production
 * does.  One instance therefore serves the whole JVM - {@link #reset()} is what
 * separates two tests.
 *
 * <p><b>It also streams.</b>  A bench with a generator but no input is only half
 * a bench: the frequency-response sweep and the notch tuning loop capture on the
 * same remote backend they command, and the mark that bounds a sweep record
 * arrives INSIDE that stream ({@link MarkedCapture}).  So the input line here is
 * the same kind of stub as the generator - no device behind it, the test says
 * which bytes arrive and where the mark and the losses fall.
 */
public final class BenchGeneratorStub implements AudioDeviceManager, RemoteGenerator {

    /** The one output device this bench offers; the tests put it into the
     *  backend's preferences so {@code start()} resolves a device at all. */
    public static final String DEVICE_NAME = "Bench DAC (stub)";
    /** The one input device, for the modules that capture on the same bench they
     *  command. */
    public static final String INPUT_NAME = "Bench ADC (stub)";

    /** Ceiling on a held upload gate, so a test that forgets to release it fails
     *  by assertion rather than by hanging the suite. */
    private static final long GATE_TIMEOUT_MS = 5_000;
    private static final String DESCRIPTION = "stub";
    private static final String VENDOR = "stub";
    /** What a refused {@code gen.open} says - the shape of the protocol's own
     *  refusal, so a caller that shows it shows something an operator can act on. */
    public static final String REFUSED = "DEVICE_LOCKED: the bench's DAC is in use by "
            + "another client";

    /** One command as it reached the bench: its name, then its arguments. */
    public record Command(String name, List<Object> args) {
    }

    private final DeviceRef output = new StubRef(0, DEVICE_NAME, false);
    private final DeviceRef input = new StubRef(0, INPUT_NAME, true);
    /** Concurrent because a measurement drives this bench from its own worker
     *  thread while the test watches for the command it is waiting on. */
    private final List<Command> commands = new CopyOnWriteArrayList<>();
    /** What {@code gen.open} answers with, or {@code 0} to answer the rate that
     *  was ASKED for - which is what a bench whose DAC can take it really puts on
     *  the wire.  A bench never answers 0: the protocol has no such value, and a
     *  stub that pretended otherwise would let a client that misread it pass. */
    @Setter
    private int grantedRate;
    /** The same for {@code capture.open}.  The two are separate because a bench
     *  may grant its own rate on each. */
    @Setter
    private int grantedCaptureRate;
    /** When set, {@code gen.open} is REFUSED the way a bench refuses a DAC
     *  somebody else has taken - the case a client only meets in the window its
     *  own close opened. */
    @Setter
    private boolean refuseOpen;
    /** The input line last opened - where a test puts the bytes in.  Volatile:
     *  opened on the measuring thread, read from the test's. */
    @Getter
    private volatile BenchCapture lastCapture;
    /** What the bench last PUSHED about itself - the only thing
     *  {@link GeneratorController#isRunning()} may believe.  Settable so a test
     *  can stage the state an operator-driven disconnect leaves behind. */
    @Setter
    private State state = State.IDLE;
    private double emitHz;
    private double emit2Hz;

    /** Public and no-argument for {@link BenchGeneratorStubProvider}. */
    public BenchGeneratorStub() {
    }

    // -------------------------------------------------------------------------
    // What the tests read back
    // -------------------------------------------------------------------------

    /** Every command since the last {@link #reset()}, in the order it was sent. */
    public List<Command> commands() {
        return List.copyOf(commands);
    }

    /** Just the command names, which is what an ordering assertion compares. */
    public List<String> names() {
        List<String> names = new ArrayList<>(commands.size());
        for (Command c : commands) {
            names.add(c.name());
        }
        return names;
    }

    /** The first argument of the first {@code name} command, or {@code null}
     *  when that command never left this machine - which is the failure mode
     *  every routing defect had. */
    public Object firstArg(String name) {
        for (Command c : commands) {
            if (c.name().equals(name)) {
                return c.args().isEmpty() ? null : c.args().get(0);
            }
        }
        return null;
    }

    /** Drops the log and the pushed state - one instance serves the whole JVM. */
    public void reset() {
        commands.clear();
        grantedRate = 0;
        grantedCaptureRate = 0;
        refuseOpen = false;
        lastCapture = null;
        state = State.IDLE;
        emitHz = 0;
        emit2Hz = 0;
        // The manager is a singleton across this class's tests, so the file
        // fields have to be cleared here too or one test's staged refusal
        // becomes the next one's mysterious failure.
        playedFile = null;
        playedFileLoop = false;
        stopFileCalls = 0;
        fileState = FileState.IDLE;
        refuseFile = null;
        playFileDelayMs = 0;
        playedFileMimeType = null;
        generatorOpen = false;
        pushedFileLoop = null;
        ignoredFileLoopPush = null;
        fileSession = false;
        uploadGate = null;
        uploadEntered = new CountDownLatch(1);
        openGate = null;
        openEntered = new CountDownLatch(1);
    }

    /** Waits until {@code name} has reached this bench, so a test that drives a
     *  measurement from another thread synchronises on what the measurement DID
     *  rather than on a sleep.
     *
     *  @return false when it never arrived inside {@code timeoutMs} */
    public boolean awaitCommand(String name, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (names().contains(name)) {
                return true;
            }
            Thread.onSpinWait();
        }
        return names().contains(name);
    }

    /** The same for the input line: it exists only once the measurement has
     *  opened it. */
    public BenchCapture awaitCapture(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            BenchCapture open = lastCapture;
            if (open != null && open.isRecording()) {
                return open;
            }
            Thread.onSpinWait();
        }
        return lastCapture;
    }

    private void record(String name, Object... args) {
        commands.add(new Command(name, List.of(args)));
    }

    // -------------------------------------------------------------------------
    // The remote generator
    // -------------------------------------------------------------------------

    @Override
    public int openGenerator(DeviceRef device, int sampleRate, int bitDepth, double ditherBits,
            OutputChannels channels) {
        record("openGenerator", sampleRate, bitDepth, ditherBits, channels);
        openEntered.countDown();
        CountDownLatch gate = openGate;
        if (gate != null) {
            // Opening an exclusive device on a real bench takes seconds - the
            // wait the upload notice has to cover.  A latch, not a sleep.
            try {
                gate.await(GATE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (refuseOpen) {
            throw new StubRefusal(REFUSED, DeviceFailureReason.DEVICE_IN_USE);
        }
        generatorOpen = true;
        return grantedRate > 0 ? grantedRate : sampleRate;
    }

    @Override
    public void startGenerator() {
        record("startGenerator");
        state = new State(true, emitHz, emit2Hz);
    }

    @Override
    public void stopGenerator() {
        record("stopGenerator");
        state = State.IDLE;
    }

    @Override
    public void closeGenerator() {
        record("closeGenerator");
        state = State.IDLE;
        generatorOpen = false;
    }

    @Override
    public void setRightLaneScale(double scale) {
        record("setRightLaneScale", scale);
    }

    @Override
    public void setDitherBits(double bits) {
        record("setDitherBits", bits);
    }

    @Override
    public void fftGrid(int fftSize, boolean snapEnabled) {
        record("fftGrid", fftSize, snapEnabled);
    }

    @Override
    public void trim(double hz) {
        record("trim", hz);
        emitHz = hz;
    }

    @Override
    public void trim2(double hz) {
        record("trim2", hz);
        emit2Hz = hz;
    }

    @Override
    public void trimReset() {
        record("trimReset");
    }

    @Override
    public State state() {
        return state;
    }

    @Override
    public Throwable takeEndedFromBelowForReport() {
        return null;   // the stubbed bench never dies from below
    }

    /**
     * This bench's reading of its own refusals - the same one the net client
     * makes: the reason travelled WITH the refusal (the far end classified it
     * where the driver is), and all this does is hand it on.  Anything that is
     * not a refusal of this bench's was never its device's failure.
     */
    @Override
    public DeviceFailureReason classifyFailure(Throwable failure) {
        return (failure instanceof StubRefusal refused) ? refused.reason
                : DeviceFailureReason.UNKNOWN;
    }

    /** A refusal that carries WHY, the shape {@code NetConnection.Refusal} has on
     *  the wire - an {@link IllegalStateException}, so every catch upstream reads
     *  it exactly as it reads a real bench's. */
    private static final class StubRefusal extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        private final DeviceFailureReason reason;

        private StubRefusal(String message, DeviceFailureReason reason) {
            super(message);
            this.reason = reason;
        }
    }

    /** Whether {@code openGenerator} has run without a matching close - the
     *  stub's own bookkeeping, which is exactly what the real client keeps. */
    @Getter
    private volatile boolean generatorOpen;

    // -------------------------------------------------------------------------
    // Playing a file on the bench
    // -------------------------------------------------------------------------

    /** The bytes the client last uploaded, or null - what a test asserts the
     *  upload actually carried. */
    @Getter
    private volatile byte[] playedFile;
    /** The loop flag of the last {@code gen.playFile}. */
    @Getter
    private volatile boolean playedFileLoop;
    /** How many times {@code gen.stopFile} was commanded. */
    @Getter
    private volatile int stopFileCalls;
    /** What the bench PUSHES about its file lane - settable so a test can stage
     *  the playing / finished edges the controller's watcher reads. */
    @Setter
    private volatile FileState fileState = FileState.IDLE;
    /** When set, {@code playFile} throws it - the refusal a client meets when the
     *  upload or the command fails. */
    @Setter
    private RuntimeException refuseFile;
    /** Milliseconds {@code playFile} stalls before it answers - a slow link, so a
     *  test can press Stop while the bytes are still in flight. */
    @Setter
    private volatile long playFileDelayMs;

    /** The MIME type the client declared for the last file. */
    @Getter
    private volatile String playedFileMimeType;

    /** Held closed by a test that has to act WHILE the lane is being opened -
     *  the seconds-long wait on a real exclusive device. */
    @Setter
    private volatile CountDownLatch openGate;
    /** Counted down the moment {@code openGenerator} is entered. */
    @Getter
    private volatile CountDownLatch openEntered = new CountDownLatch(1);
    /** Held closed by a test that has to act WHILE the upload is in flight - a
     *  latch, not a sleep, so the ordering is deterministic. */
    @Setter
    private volatile CountDownLatch uploadGate;
    /** Counted down the moment {@code playFile} is entered, so a test can wait
     *  for the transfer to have really started before it acts. */
    @Getter
    private volatile CountDownLatch uploadEntered = new CountDownLatch(1);

    @Override
    public void playFile(byte[] content, String mimeType, boolean loop) {
        record("playFile", content.length);
        playedFileMimeType = mimeType;
        uploadEntered.countDown();
        CountDownLatch gate = uploadGate;
        if (gate != null) {
            try {
                gate.await(GATE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (playFileDelayMs > 0) {
            try {
                Thread.sleep(playFileDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (refuseFile != null) {
            throw refuseFile;
        }
        playedFile = content.clone();
        playedFileLoop = loop;
        // LAST, after the gate: the real bench only has a file session once the
        // upload has landed and gen.playFile has been accepted.  A stub that had
        // one from the first instruction would accept a live loop push that the
        // real one ignores, and every test of that window would pass vacuously.
        fileSession = true;
    }

    @Override
    public void stopFile() {
        record("stopFile");
        stopFileCalls++;
        fileSession = false;
    }

    /** Whether this bench currently holds a file session - what decides whether
     *  a live {@code gen.config} carrying {@code fileLoop} means anything. */
    @Getter
    private volatile boolean fileSession;
    /** The live loop flag last pushed for a file session that EXISTED, or null. */
    @Getter
    private volatile Boolean pushedFileLoop;
    /** The last live push that arrived with NO file session, or null - the real
     *  bench drops these on the floor, and a test that wants to see one asks
     *  here rather than being told the push took effect. */
    @Getter
    private volatile Boolean ignoredFileLoopPush;

    @Override
    public void setFileLoop(boolean loop) {
        record("setFileLoop", loop);
        if (fileSession) {
            pushedFileLoop = loop;
        } else {
            // Spec 4.5: applied only when a file session exists on that
            // generator; ignored like any other inapplicable field otherwise.
            ignoredFileLoopPush = loop;
        }
    }

    @Override
    public FileState fileState() {
        return fileState;
    }

    @Override
    public Throwable takeFileErrorForReport() {
        return null;   // the stubbed bench never loses a file from below
    }

    // -------------------------------------------------------------------------
    // The live parameters
    // -------------------------------------------------------------------------

    @Override
    public void setForm(GenSignalForm form) {
        record("setForm", form);
    }

    @Override
    public void setFrequency(double hz) {
        record("setFrequency", hz);
        emitHz = hz;
    }

    @Override
    public void setDualToneFrequency2(double hz) {
        record("setDualToneFrequency2", hz);
        emit2Hz = hz;
    }

    @Override
    public void setDualToneAmplitudes(double amp1Pct, double amp2Pct) {
        record("setDualToneAmplitudes", amp1Pct, amp2Pct);
    }

    @Override
    public void setAmplitudeVrms(double vrms) {
        record("setAmplitudeVrms", vrms);
    }

    @Override
    public void setDacFsVoltageAmpl(double volts) {
        record("setDacFsVoltageAmpl", volts);
    }

    @Override
    public void setRectangleDuty(double dutyFrac) {
        record("setRectangleDuty", dutyFrac);
    }

    @Override
    public void setTriangleDuty(double dutyFrac) {
        record("setTriangleDuty", dutyFrac);
    }

    @Override
    public void setSweepFreqStart(double hz) {
        record("setSweepFreqStart", hz);
    }

    @Override
    public void setSweepFreqEnd(double hz) {
        record("setSweepFreqEnd", hz);
    }

    @Override
    public void setSweepDurationSamples(int samples) {
        record("setSweepDurationSamples", samples);
    }

    @Override
    public void setSweepLeadInSamples(int samples) {
        record("setSweepLeadInSamples", samples);
    }

    @Override
    public void setSweepFadeInSamples(int samples) {
        record("setSweepFadeInSamples", samples);
    }

    @Override
    public void setSweepFadeOutSamples(int samples) {
        record("setSweepFadeOutSamples", samples);
    }

    @Override
    public void setSweepLoop(boolean loop) {
        record("setSweepLoop", loop);
    }

    @Override
    public void applyCompensation(double[] ampRatios, int[] hNums, double[] phiInits) {
        record("applyCompensation", ampRatios, hNums, phiInits);
    }

    @Override
    public void applyDualToneCompensation(double[] ampRatios, int[] aCoef, int[] bCoef,
            double[] phiInits) {
        record("applyDualToneCompensation", ampRatios, aCoef, bCoef, phiInits);
    }

    @Override
    public void clearCompensation() {
        record("clearCompensation");
    }

    // -------------------------------------------------------------------------
    // The device manager - enumeration only; nothing here opens a line
    // -------------------------------------------------------------------------

    @Override
    public List<DeviceRef> listInputDevices() {
        return List.of(input);
    }

    @Override
    public List<DeviceRef> listOutputDevices() {
        return List.of(output);
    }

    @Override
    public DeviceRef getDeviceByIndex(int index, boolean isOutput) {
        if (index != 0) {
            throw new IllegalArgumentException("no such device: " + index);
        }
        return isOutput ? output : input;
    }

    @Override
    public List<AudioFormat> listSupportedFormats(DeviceRef device, boolean output) {
        return List.of();
    }

    /** The bench's input line, at the rate it GRANTS - which need not be the one
     *  asked for (the protocol says so, and a client that ignored the difference
     *  would count samples against a clock nothing runs at). */
    @Override
    public AudioCapture openCapture(DeviceRef device, int sampleRate, int bitDepth) {
        lastCapture = new BenchCapture(grantedCaptureRate > 0 ? grantedCaptureRate
                : sampleRate, bitDepth);
        return lastCapture;
    }

    @Override
    public AudioPlayback openPlayback(DeviceRef device, int sampleRate, int bitDepth,
            double ditherBits) {
        throw new UnsupportedOperationException(
                "a remote generator is COMMANDED, never rendered into - a controller that "
                        + "reaches this line took the local path against a remote bench");
    }

    /**
     * The bench's input line with no ADC behind it: the test says when a batch
     * arrives, what is in it, where the sweep mark falls between two of them and
     * where the bench lost audio.
     *
     * <p>It extends the SAME base every real recorder does, so the bytes reach a
     * measurement through the production decode and normalisation - including the
     * buffer-reuse contract a consumer that kept the array would break - and it
     * carries the in-band marks, because a stream that could not be marked is not
     * the stream a sweep is cut out of.
     */
    public static final class BenchCapture extends AbstractPcmCapture
            implements MarkedCapture {

        /** How many PCM bytes have been handed to the pipeline - the position the
         *  mark and the losses are reported at, exactly as the real stream
         *  reports them. */
        private long pcmBytesDelivered;
        @Setter
        private volatile MarkedCapture.Listener markerListener;
        @Getter
        private volatile boolean closed;

        private BenchCapture(int sampleRate, int bitDepth) {
            super(sampleRate, bitDepth);
        }

        @Override
        public void open() {
            // Nothing to open: the bytes come from the test.
        }

        @Override
        public void startRecording() {
            recording.set(true);
        }

        @Override
        public void stopRecording() {
            recording.set(false);
        }

        @Override
        public void close() {
            recording.set(false);
            closed = true;
        }

        /** One capture batch, exactly as a backend's consume thread delivers it. */
        public void feed(byte[] pcm) {
            dispatch(pcm, pcm.length);
            pcmBytesDelivered += pcm.length;
        }

        /** The bench's generator started here: the record begins at the next
         *  byte. */
        public void markSweepStart() {
            MarkedCapture.Listener listener = markerListener;
            if (listener != null) {
                listener.marker(1, pcmBytesDelivered);
            }
        }

        /** The bench confesses it dropped {@code lostFrames} stereo frames. */
        public void gap(long lostFrames) {
            MarkedCapture.Listener listener = markerListener;
            if (listener != null) {
                listener.gap(lostFrames, pcmBytesDelivered);
            }
        }
    }

    private record StubRef(int index, String name, boolean isInput) implements DeviceRef {

        @Override
        public String description() {
            return DESCRIPTION;
        }

        @Override
        public String vendor() {
            return VENDOR;
        }

        @Override
        public AudioBackendType backend() {
            return AudioBackendType.NET;
        }

        @Override
        public boolean isOutput() {
            return !isInput;
        }
    }
}
