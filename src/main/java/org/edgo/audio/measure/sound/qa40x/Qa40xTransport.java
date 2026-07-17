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

package org.edgo.audio.measure.sound.qa40x;

/**
 * Transport seam for the QA402/QA403 USB protocol — the byte pipe between the
 * measurement engine and the analyzer, with no protocol knowledge above the
 * wire level.  It moves four kinds of traffic over interface 0's four bulk
 * endpoints (see {@code doc/QA40X-PROTOCOL.md} §3/§4/§5):
 *
 * <ul>
 *   <li>register <b>writes</b> — a 5-byte big-endian frame on EP {@code 0x01} OUT;</li>
 *   <li>register <b>reads</b> — a {@code 0x80|reg} request write followed by a
 *       4-byte big-endian reply on EP {@code 0x81} IN;</li>
 *   <li>audio <b>playback</b> — async writes on EP {@code 0x02} OUT;</li>
 *   <li>audio <b>capture</b> — async reads on EP {@code 0x82} IN.</li>
 * </ul>
 *
 * <p>Register traffic is synchronous (blocking bulk transfers); audio traffic is
 * asynchronous — submitted here and completed later on the transport's event
 * thread, which reports back through the {@link TransferListener}.  The
 * full-duplex discipline (≥2 transfers in flight per direction, read-completion
 * clock) lives in the engine that drives this seam, not here.
 *
 * <p><b>Stop discipline.</b> {@link #cancelAll()} aborts in-flight transfers and
 * {@link #close()} tears the session down; neither ever pipe-resets or
 * clear-halts an endpoint — doing so hangs the next session's first read
 * (doc §3).
 *
 * <p>The default full-scale value convention is big-endian for register values
 * even though the audio samples are little-endian — two independent
 * endiannesses that this seam neither imposes nor conflates (doc §4/§5).
 */
public interface Qa40xTransport extends AutoCloseable {

    /** Writes {@code value} to register {@code reg} — a 5-byte big-endian frame on EP {@code 0x01} OUT. */
    void registerWrite(int reg, int value);

    /** Reads register {@code reg} — sends {@code 0x80|reg}, then decodes the 4-byte big-endian reply from EP {@code 0x81} IN. */
    int  registerRead(int reg);

    /** Submits an async playback transfer of {@code length} bytes from {@code data} on EP {@code 0x02} OUT. */
    void submitAudioWrite(byte[] data, int length);

    /** Submits an async capture transfer filling up to {@code buffer.length} bytes from EP {@code 0x82} IN. */
    void submitAudioRead(byte[] buffer);

    /** Installs the listener that receives audio-transfer completions and failures. */
    void setListener(TransferListener listener);

    /** Cancels in-flight transfers; never pipe-resets or clear-halts an endpoint (doc §3). */
    void cancelAll();

    /** Releases interface 0 and closes the device handle. */
    @Override void close();

    /** Callback sink for async audio transfers, invoked on the transport's event thread. */
    interface TransferListener {

        /** A playback transfer finished; {@code transferred} bytes of {@code buffer} were sent. */
        void writeCompleted(byte[] buffer, int transferred);

        /** A capture transfer finished; {@code transferred} bytes of {@code buffer} were filled. */
        void readCompleted(byte[] buffer, int transferred);

        /** A transfer failed (or was cancelled); {@code read} tells the direction, {@code detail} the reason. */
        void transferFailed(boolean read, String detail);
    }
}
