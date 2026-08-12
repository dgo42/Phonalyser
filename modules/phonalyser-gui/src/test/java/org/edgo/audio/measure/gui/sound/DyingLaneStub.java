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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioFormat;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.sound.AbstractPcmCapture;
import org.edgo.audio.measure.sound.AudioCapture;
import org.edgo.audio.measure.sound.AudioDeviceManager;
import org.edgo.audio.measure.sound.AudioPlayback;
import org.edgo.audio.measure.sound.DeviceRef;

import lombok.Getter;
import lombok.Setter;

/**
 * A LOCAL backend whose lanes can be made to die the way real ones do.
 *
 * <p>The failure this exists for is not an exception.  The native backends are
 * reached through JNA, and a device pulled out from under an open stream comes
 * back as an {@link Error} - "Invalid memory access" - which a
 * {@code catch (Exception)} does not catch.  It escaped the render thread, the
 * default handler logged it, and nothing else happened: the line was never torn
 * down, the controller went on believing it was generating, and the operator was
 * shown a lit Play button over hardware that no longer existed.  A stub is the
 * only way to ask for that failure on purpose.
 *
 * <p>Both lanes are here because both have the same boundary and the same silence
 * behind it.  The playback dies where the render loop runs; the capture dies
 * inside the batch callback, by handing over a batch that claims more bytes than
 * it carries - which is exactly what a driver reporting a wrong valid-byte count
 * does to the decoder, and what {@code AbstractPcmCapture.dispatch} already
 * swallows with a single log line.
 *
 * <p>It claims {@link AudioBackendType#JAVASOUND} because a LOCAL slot is the
 * whole point: the net slot's manager is a remote generator, and the controller
 * COMMANDS those instead of rendering into them - so {@code BenchGeneratorStub},
 * the sibling this would otherwise extend, refuses to open a playback at all.
 * The claim is contested (the CLI module puts the real JavaSound backend on this
 * test class path), so the tests assert which manager they actually got before
 * they assert anything else.
 */
public final class DyingLaneStub implements AudioDeviceManager {

    public static final String OUTPUT_NAME = "Dying DAC (stub)";
    public static final String INPUT_NAME = "Dying ADC (stub)";
    /** What JNA says when a device is pulled out from under an open stream. */
    public static final String DEATH = "Invalid memory access";

    /** How long the render loop waits to be told the DAC was pulled - a bound so
     *  a failing test ends rather than hanging the build. */
    private static final long KILL_WAIT_MINUTES = 1;

    private final DeviceRef output = new StubRef(0, OUTPUT_NAME, false);
    private final DeviceRef input = new StubRef(0, INPUT_NAME, true);

    /** The capture line last opened - where a test makes the batch callback
     *  fail.  Volatile: opened on the caller's thread, read from the test's. */
    @Getter
    private volatile DyingCapture lastCapture;
    /** Every output line this stub has opened, in order.  Per LANE and not per
     *  stub: one manager serves the whole JVM, so a kill flag on the stub would be
     *  spent after the first test that used it - and a test about an ABANDONED
     *  lane has to reach one that is no longer the newest. */
    private final List<DyingPlayback> playbacks = new CopyOnWriteArrayList<>();

    /** How many times the backend lifecycle brought this stub up and took it
     *  down - what proves an OFFERED backend is swept and not merely the active
     *  one.  Counted rather than flagged so "idempotent" stays askable. */
    @Getter
    private volatile int setupCount;
    @Getter
    private volatile int shutdownCount;
    /** Makes {@link #setup()} throw the way a native driver does.  A backend that
     *  cannot be reached must not stop the application coming up, and an Error is
     *  the shape JNA really raises - a {@code catch (Exception)} would miss it. */
    @Setter
    private volatile boolean setupFails;

    /** Public and no-argument for {@link DyingLaneStubProvider}. */
    public DyingLaneStub() {
    }

    /** The DAC is pulled out from under the lane that is playing: its render
     *  thread throws the native layer's Error. */
    public void killPlayback() {
        if (!playbacks.isEmpty()) {
            killPlayback(playbacks.size() - 1);
        }
    }

    /** The same for an EARLIER lane - the one a controller gave up waiting for
     *  and started a new tone over.  Its death arrives while another lane is
     *  playing, which is the whole point of asking for it. */
    public void killPlayback(int index) {
        playbacks.get(index).kill.countDown();
    }

    /** How many output lines have been opened so far - the index of the lane
     *  about to be abandoned is this, minus one. */
    public int openedPlaybacks() {
        return playbacks.size();
    }

    /** Whether that lane's render thread has run its teardown.  The sync point a
     *  test needs before asserting what the lane's death did NOT do: close()
     *  happens in the play thread's finally, after the failure handler. */
    public boolean playbackClosed(int index) {
        return playbacks.get(index).closed;
    }

    /** The DDS the most recently opened line was handed, or null before its
     *  render thread reached {@code play} - for a test that asks what the lane
     *  actually configured rather than what it was asked for. */
    public SignalGenerator lastPlayedGenerator() {
        return playbacks.isEmpty() ? null : playbacks.get(playbacks.size() - 1).getPlayed();
    }

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

    @Override
    public AudioCapture openCapture(DeviceRef device, int sampleRate, int bitDepth) {
        lastCapture = new DyingCapture(sampleRate, bitDepth);
        return lastCapture;
    }

    @Override
    public AudioPlayback openPlayback(DeviceRef device, int sampleRate, int bitDepth,
            double ditherBits) {
        DyingPlayback lane = new DyingPlayback();
        playbacks.add(lane);
        return lane;
    }

    /** The output line: it primes, reports ready exactly as a real one does, and
     *  then dies where a real one dies - inside the render loop, with the Error
     *  the native layer throws. */
    private static final class DyingPlayback implements AudioPlayback {

        /** Raised by {@link DyingLaneStub#killPlayback()}; the render loop is
         *  waiting on it. */
        private final CountDownLatch kill = new CountDownLatch(1);
        /** Set by the render thread's own teardown - see
         *  {@link DyingLaneStub#playbackClosed(int)}. */
        private volatile boolean closed;
        /** The generator handed to {@link #play}; null until the render thread
         *  reaches it.  Volatile: written there, read from the test's thread. */
        @Getter
        private volatile SignalGenerator played;

        @Override
        public void open() {
            // Nothing to open - the failure under test is not an open failure.
        }

        @Override
        public void play(SignalGenerator generator, int durationSeconds) {
            throw new UnsupportedOperationException("the GUI never plays a fixed duration");
        }

        @Override
        public void play(SignalGenerator generator, AtomicBoolean stopFlag,
                CountDownLatch readyLatch) {
            // The very DDS the lane built and configured, kept so a test can ask
            // what it was actually set to rather than what the caller meant.
            played = generator;
            readyLatch.countDown();          // the buffer is primed: the tone is up
            try {
                kill.await(KILL_WAIT_MINUTES, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            throw new Error(DEATH);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** The input line: the test says when a batch arrives and whether its header
     *  tells the truth about how long it is. */
    public final class DyingCapture extends AbstractPcmCapture {

        /** Frames the lying batch below claims to carry. */
        private static final int CLAIMED_FRAMES = 4;

        private DyingCapture(int sampleRate, int bitDepth) {
            super(sampleRate, bitDepth);
        }

        @Override
        public void open() {
            // Nothing to open.
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
        }

        /** One batch whose header LIES: it claims four frames and carries one.
         *  A consumer decoding it reads past the end of the buffer - the shape a
         *  driver's wrong valid-byte count really has, and one that is fatal to
         *  the callback thread rather than to the caller. */
        public void feedALyingBatch() {
            dispatch(new byte[frameSize], frameSize * CLAIMED_FRAMES);
        }
    }

    /** The stub's own device handle - the fields the resolution seam reads. */
    private record StubRef(int index, String name, boolean isInput) implements DeviceRef {

        private static final String DESCRIPTION = "stub";
        private static final String VENDOR = "stub";

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
            return AudioBackendType.JAVASOUND;
        }

        @Override
        public boolean isOutput() {
            return !isInput;
        }
    }
}
