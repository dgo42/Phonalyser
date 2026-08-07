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

import org.edgo.audio.measure.sound.AbstractPcmCapture;

import lombok.Getter;

/**
 * A capture line with no device behind it: the test says when a batch arrives
 * and what is in it.
 *
 * <p>It extends the SAME base every real recorder does, so a batch reaches the
 * streamer through the production {@code dispatch} - including the buffer-reuse
 * contract ("the byte array may be reused immediately after the call returns"),
 * which is precisely the rule a streamer that forgot to copy would break.
 */
final class StubCapture extends AbstractPcmCapture {

    @Getter
    private boolean opened;
    /** Volatile because the loopback run's teardown closes the line on the
     *  session's own thread while the test thread waits to see it. */
    @Getter
    private volatile boolean closed;
    @Getter
    private int startCount;
    @Getter
    private int stopCount;

    StubCapture(int sampleRate, int bitDepth) {
        super(sampleRate, bitDepth);
    }

    @Override
    public void open() {
        opened = true;
    }

    @Override
    public void startRecording() {
        recording.set(true);
        startCount++;
    }

    @Override
    public void stopRecording() {
        recording.set(false);
        stopCount++;
    }

    @Override
    public void close() {
        closed = true;
    }

    /** Delivers one capture batch, exactly as a backend's consume thread would.
     *  The array goes on as the backend's own, so a test that overwrites it
     *  afterwards catches a consumer which queued the reference rather than a
     *  copy. */
    void feed(byte[] pcm) {
        dispatch(pcm, pcm.length);
    }
}
