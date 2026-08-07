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

package org.edgo.audio.measure.net.client;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.sound.AudioPlayback;

/**
 * The playback line the net backend deliberately does NOT have.
 *
 * <p>Spec 7 puts client-&gt;server audio out of scope for v1, and the reason is
 * physical rather than technical: the generator runs on the server, next to the
 * DAC (spec 4.5), so there is no uplink audio to send - streaming samples across
 * the network to be played would add the network's jitter to a signal whose
 * whole purpose is to be clean.  Frame type 4 is reserved for the day that
 * changes.
 *
 * <p>Every method therefore throws instead of pretending: a generator that
 * silently emitted nothing would look like a dead device under test, and an
 * operator would go looking for the fault in the wiring.
 *
 * <p><b>Except {@link #close()}, which is a no-op.</b>  A teardown that throws
 * breaks every {@code finally} and every try-with-resources that touches it - the
 * cleanup path of a caller that failed to open this in the first place would
 * itself blow up, and the exception the operator needs to see would be replaced
 * by this one.  Closing something that was never opened is exactly nothing.
 */
public final class NetPcmPlayback implements AudioPlayback {

    private static final String NOT_IMPLEMENTED =
            "Network playback not implemented - the generator runs server-side";

    @Override
    public void open() {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    @Override
    public void play(SignalGenerator generator, int durationSeconds) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    @Override
    public void play(SignalGenerator generator, AtomicBoolean stopFlag,
            CountDownLatch readyLatch) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    @Override
    public void setDitherBits(double bits) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    @Override
    public void setChannelScale(double left, double right) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    @Override
    public void setOutputChannels(OutputChannels channels) {
        throw new UnsupportedOperationException(NOT_IMPLEMENTED);
    }

    /** Nothing was opened, so nothing is closed - see the class comment for why
     *  this one may not throw. */
    @Override
    public void close() {
        // Deliberately empty.
    }
}
