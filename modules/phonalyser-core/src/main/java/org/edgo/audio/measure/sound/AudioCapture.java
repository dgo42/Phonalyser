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

import javax.sound.sampled.AudioFormat;

import org.edgo.audio.measure.enums.Channel;

/**
 * Backend-agnostic stereo PCM capture line. Implementations:
 * {@link WasapiRecorder} (WASAPI exclusive mode) and
 * {@link WdmksRecorder} (PortAudio WDM-KS).
 */
public interface AudioCapture extends AutoCloseable {

    /**
     * Receives raw interleaved L/R PCM bytes on the capture / consume thread.
     * Only the first {@code validBytes} bytes contain fresh data.  The byte
     * array may be reused (e.g. returned to an internal pool) immediately
     * after the call returns, so the implementation must finish reading
     * before returning and must not retain the array reference.
     *
     * <p>The bytes arrive undecoded: a consumer (the scope view) converts the
     * PCM straight into the floats of its ring buffer, with no intermediate
     * per-sample objects.
     */
    @FunctionalInterface
    interface PcmBatchListener {
        void accept(byte[] pcm, int validBytes);

        /**
         * The stream is OVER and no {@link #accept} will ever follow - the
         * device was lost, or its stream failed in a way the backend cannot
         * recover from.  {@code reason} is machine-readable; the GUI layer
         * localizes it, and everything the backend wanted to SAY about it is
         * already in the log.
         *
         * <p>Carried on the SAME seam as the data on purpose: a device that
         * fails does not necessarily throw - an unplugged card simply stops
         * delivering, and a listener that is never called again looks exactly
         * like silence, so everything downstream keeps believing it is
         * measuring - on the bench the generator and the scope simply carried
         * on.  The end therefore travels the path the data travelled,
         * layer by layer - capture -> ring buffer -> reader -> worker -> UI -
         * never sideways.  Raised at most once per open, on whatever thread
         * noticed (often a driver callback thread): the listener must not
         * block, exactly as it must not in {@link #accept}.
         *
         * <p>Default no-op: a consumer with its own lifetime bound elsewhere
         * may ignore it; the ring-buffer feeder must not.
         */
        default void captureEnded(CaptureEndReason reason) { }
    }

    /**
     * The capture's data path: the PCM bytes as captured, which the consumer
     * converts to {@code float} itself - one pass over the data and no
     * per-sample object allocations.
     */
    void setPcmBatchListener(PcmBatchListener listener);

    void open() throws Exception;

    void startRecording();

    /**
     * Starts recording and watches {@code input} for the delay tool's pulse,
     * analysing the blocks as they arrive and storing none: a noise baseline
     * on the opening silence, then the pulse's low level - under minus
     * several times that noise, held.  Then, for every edge of
     * {@code handshake} in turn: once the level before it has settled it asks
     * the playback for the edge, waits for the answer, then watches for the
     * edge - the first sample across zero, confirmed by the samples after it
     * staying there, a rise as is, a fall mirrored - and stamps its arrival
     * into the exchange.  Answers whether every edge arrived; {@code false}
     * when a level, an answer or an edge did not come within
     * {@code timeoutMs}, the handshake abandoned and the edges before it
     * keeping their stamps.  The caller stops the recording.
     */
    boolean capturePulse(Channel input, PulseHandshake handshake, long timeoutMs) throws InterruptedException;

    void stopRecording() throws InterruptedException;

    AudioFormat getFormat();

    boolean isRecording();

    /** Decodes one little-endian sample at the given byte offset. */
    int readSample(byte[] pcm, int offset);

    @Override
    void close();
}
