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

package org.edgo.audio.measure.sound;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.generator.SignalGenerator;

/**
 * Backend-agnostic stereo PCM playback line. Implementations:
 * {@link JavaSoundGenerator} (default WASAPI playback path) and
 * {@link WdmksGenerator} (PortAudio WDM-KS).
 */
public interface AudioPlayback extends AutoCloseable {

    void open() throws Exception;

    /**
     * The delay tool's signal: silence until the line runs in the steady
     * state the render path keeps it in, then the handshake's edges in turn -
     * {@code -level} until the capture asks for a rise, {@code +level} until
     * it asks for the fall - then {@code -level} for a short fixed time, then
     * silence again, enough to push the last fall out of the line - on each
     * lane at its own level in digital full scale ({@code 0} keeps that lane
     * silent).  The capture side takes its noise baseline on the leading
     * silence.  Each edge is answered on its exchange the moment it is
     * written, and the moment it left the line's queue for the device is
     * stamped into that exchange.  Blocks until the whole signal has been
     * handed to the line, or returns early when the handshake was abandoned;
     * the caller stops the line.
     * The default is the answer of a backend whose output path has no moment
     * it can stamp - a callback or event-driven line, a remote one: it throws
     * {@link UnsupportedOperationException}.
     */
    default void pulse(double levelLeft, double levelRight, PulseHandshake handshake)
            throws InterruptedException {
        throw new UnsupportedOperationException(getClass().getSimpleName()
                + " has no write of its own to time a pulse on");
    }

    /** Plays a fixed duration, then drains and stops. */
    void play(SignalGenerator generator, int durationSeconds);

    /**
     * Plays continuously until {@code stopFlag} is raised. {@code readyLatch}
     * is counted down after the hardware buffer is fully pre-filled.
     */
    void play(SignalGenerator generator, AtomicBoolean stopFlag, CountDownLatch readyLatch);

    /**
     * Live-updates the two lanes' TPDF dither depths - each lane its own, a
     * dither entered as a level resolving differently against each lane's full
     * scale.  No-op when the implementation doesn't support live dither
     * changes (the default).  Honoured by the in-process JavaSoundGenerator /
     * WdmksGenerator wired to the GUI.
     */
    default void setDitherBits(double leftBits, double rightBits) {}

    /**
     * Live-updates the output-lane gate (Left / Right / Both).  No-op by
     * default; honoured by the in-process backends wired to the GUI.
     */
    default void setOutputChannels(OutputChannels channels) {}

    /**
     * The backend's own confession that this lane died from below - for an
     * implementation whose {@code play} CANNOT return on device death: the
     * csjsound provider holds the line monitor across the blocking native
     * write, so a lane on an unplugged device wedges inside it while the
     * backend's watchdog is the only part that knows.  Non-null once the
     * backend detected the loss; the lane above folds it into the same
     * ended-from-below state a returning play thread records.  Default null -
     * a backend whose {@code play} throws or returns on death needs no
     * confession.
     */
    default Throwable lostFromBelow() {
        return null;
    }

    @Override
    void close();
}
