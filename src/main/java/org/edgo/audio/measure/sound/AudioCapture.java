/*
 * Phonalyser — precision audio measurement workbench.
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

import java.util.function.Consumer;

import javax.sound.sampled.AudioFormat;

import org.edgo.audio.measure.common.StereoSample;

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
     * <p>Replaces the intermediate {@code StereoSample[]} decode for hot
     * consumers (the scope view) — those convert PCM straight into floats
     * for the ring buffer, so the StereoSample dance is pure overhead.
     */
    @FunctionalInterface
    interface PcmBatchListener {
        void accept(byte[] pcm, int validBytes);
    }

    /** Receives decoded stereo samples on the capture thread. */
    void setSampleListener(Consumer<StereoSample[]> listener);

    /**
     * Direct PCM-bytes path that skips the {@code StereoSample[]} decode.
     * Preferred over {@link #setSampleListener(Consumer)} for consumers that
     * convert to {@code float} themselves — avoids one full pass over the
     * data plus all the per-sample object allocations.
     */
    void setPcmBatchListener(PcmBatchListener listener);

    /** Receives raw little-endian PCM bytes (interleaved L/R) on the capture thread. */
    void setRawBytesListener(Consumer<byte[]> listener);

    void open() throws Exception;

    void startRecording();

    void stopRecording() throws InterruptedException;

    AudioFormat getFormat();

    boolean isRecording();

    /** Decodes one little-endian sample at the given byte offset. */
    int readSample(byte[] pcm, int offset);

    @Override
    void close();
}
