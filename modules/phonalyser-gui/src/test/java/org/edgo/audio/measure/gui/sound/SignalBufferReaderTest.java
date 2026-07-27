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

package org.edgo.audio.measure.gui.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Behaviour of the {@link SignalBufferReader} consuming forward cursor:
 * contiguous reads that advance the cursor, partial reads at the live tip,
 * overrun detection when the writer laps the cursor, re-anchoring, and the
 * frozen-snapshot factory.
 */
class SignalBufferReaderTest {

    private static SignalBuffer bufferOfCapacity(int capacitySamples) {
        return new SignalBuffer(48_000, (double) capacitySamples / 48_000);
    }

    /** Appends {@code n} samples whose left value is the absolute index and
     *  right value its negation, so reads can be checked by position. */
    private static void append(SignalBuffer b, int fromAbs, int n) {
        float[] l = new float[n];
        float[] r = new float[n];
        for (int i = 0; i < n; i++) {
            l[i] = fromAbs + i;
            r[i] = -(fromAbs + i);
        }
        b.appendBatch(l, r, n);
    }

    @Test
    void readConsumesContiguouslyAndAdvances() {
        SignalBuffer b = bufferOfCapacity(100);
        SignalBufferReader rd = new SignalBufferReader(b);
        append(b, 0, 30);
        rd.seekToLatest();                       // anchor at "now" (30): no backlog
        assertEquals(0, rd.available());

        append(b, 30, 20);                       // 30..49 fresh
        assertEquals(20, rd.available());

        float[] l = new float[10];
        float[] r = new float[10];
        int n = rd.read(10, l, r);               // pulls 30..39, advances to 40
        assertEquals(10, n);
        assertEquals(30f, l[0]);
        assertEquals(39f, l[9]);
        assertEquals(-39f, r[9]);
        assertEquals(40L, rd.getReadPos());
        assertEquals(10, rd.available());

        // Asking for more than is available returns only what's there (40..49).
        n = rd.read(100, l, r);
        assertEquals(10, n);
        assertEquals(40f, l[0]);
        assertEquals(49f, l[9]);
        assertEquals(50L, rd.getReadPos());
        assertEquals(0, rd.available());
        assertEquals(0, rd.read(10, l, r));      // nothing new
    }

    @Test
    void readWithSingleChannelIgnoresTheOther() {
        SignalBuffer b = bufferOfCapacity(100);
        SignalBufferReader rd = new SignalBufferReader(b);
        append(b, 0, 10);
        rd.seek(0);
        float[] l = new float[10];
        int n = rd.read(10, l, null);            // right == null
        assertEquals(10, n);
        assertEquals(0f, l[0]);
        assertEquals(9f, l[9]);
    }

    @Test
    void lappedCursorReportsOverrun() {
        SignalBuffer b = bufferOfCapacity(100);
        SignalBufferReader rd = new SignalBufferReader(b);
        append(b, 0, 50);
        rd.seek(10);                             // still resident
        assertEquals(40L, rd.available());

        append(b, 50, 100);                      // writePos=150, oldest=50 > readPos=10
        assertEquals(SignalBufferReader.OVERRUN, rd.available());

        float[] l = new float[10];
        assertEquals(SignalBufferReader.OVERRUN, rd.read(10, l, null));

        rd.seekToLatest();                       // recover: re-anchor at the tip
        assertEquals(0, rd.available());
        assertEquals(150L, rd.getReadPos());
    }

    @Test
    void seekClampsIntoResidentSpan() {
        SignalBuffer b = bufferOfCapacity(100);
        SignalBufferReader rd = new SignalBufferReader(b);
        append(b, 0, 150);                       // writePos=150, resident [50,150)
        rd.seek(0);                              // older than oldest → clamped to 50
        assertEquals(50L, rd.getReadPos());
        rd.seek(999);                            // past the tip → clamped to writePos
        assertEquals(150L, rd.getReadPos());
    }

    @Test
    void frozenSnapshotIsIndependentOfLaterWrites() {
        SignalBuffer b = bufferOfCapacity(100);
        SignalBufferReader rd = new SignalBufferReader(b);
        append(b, 0, 50);
        SignalBufferReader snap = rd.frozenSnapshot();

        append(b, 50, 50);                       // mutate the live buffer afterwards

        float[] l = new float[50];
        int n = snap.readLatest(50, l, null);    // snapshot still holds 0..49
        assertEquals(50, n);
        assertEquals(0f, l[0]);
        assertEquals(49f, l[49]);
    }
}
