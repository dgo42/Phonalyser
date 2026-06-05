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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for the {@link SignalBuffer} ring buffer that bridges audio
 * backends (writer) to the FFT analyser and oscilloscope (readers).
 * Covers wrap-around, partial reads, batch writes, and the abs-position
 * accessor used by the FFT worker's "fresh samples since start"
 * accounting.  Any regression here corrupts every live spectrum and
 * trace, so the wrap-around math is worth pinning down.
 */
class SignalBufferTest {

    @Test
    void constructor_rejectsNonPositiveParams() {
        assertThrows(IllegalArgumentException.class,
                () -> new SignalBuffer(0, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> new SignalBuffer(48_000, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SignalBuffer(-1, 1.0));
    }

    @Test
    void getters_returnConstructorArgs() {
        SignalBuffer b = new SignalBuffer(48_000, 0.5);
        assertEquals(48_000, b.getSampleRate());
        assertEquals(24_000, b.getCapacity(), "ceil(48000 * 0.5)");
        assertEquals(0L,     b.getWritePos());
    }

    @Test
    void append_incrementsWritePosAndStoresSample() {
        SignalBuffer b = new SignalBuffer(48_000, 1.0);
        b.append(0.5f, -0.5f);
        b.append(0.25f, -0.25f);

        assertEquals(2L, b.getWritePos());

        float[] left  = new float[2];
        float[] right = new float[2];
        int got = b.readLatest(2, left, right);
        assertEquals(2, got);
        assertEquals(0.5f,   left[0],  1e-6);
        assertEquals(0.25f,  left[1],  1e-6);
        assertEquals(-0.5f,  right[0], 1e-6);
        assertEquals(-0.25f, right[1], 1e-6);
    }

    @Test
    void appendBatch_storesContiguousChunkWithoutWrap() {
        SignalBuffer b = new SignalBuffer(48_000, 1.0);
        float[] L = { 1f, 2f, 3f, 4f, 5f };
        float[] R = { -1f, -2f, -3f, -4f, -5f };
        b.appendBatch(L, R, 5);
        assertEquals(5L, b.getWritePos());

        float[] outL = new float[5];
        float[] outR = new float[5];
        int got = b.readLatest(5, outL, outR);
        assertEquals(5, got);
        for (int i = 0; i < 5; i++) {
            assertEquals(L[i], outL[i], 1e-6);
            assertEquals(R[i], outR[i], 1e-6);
        }
    }

    @Test
    void appendBatch_wrapsCorrectly() {
        // Capacity 4.  Write 7 samples → buffer holds last 4.
        SignalBuffer b = new SignalBuffer(48_000, 4.0 / 48_000);   // ⌈4⌉ = 4
        assertEquals(4, b.getCapacity());

        float[] L = { 1f, 2f, 3f, 4f, 5f, 6f, 7f };
        float[] R = { 1f, 2f, 3f, 4f, 5f, 6f, 7f };
        b.appendBatch(L, R, 7);
        assertEquals(7L, b.getWritePos());

        float[] outL = new float[4];
        float[] outR = new float[4];
        int got = b.readLatest(4, outL, outR);
        assertEquals(4, got);
        // Last 4 samples written: 4, 5, 6, 7
        assertEquals(4f, outL[0], 1e-6);
        assertEquals(5f, outL[1], 1e-6);
        assertEquals(6f, outL[2], 1e-6);
        assertEquals(7f, outL[3], 1e-6);
    }

    @Test
    void readLatest_returnsPartial_whenFewerSamplesAvailable() {
        SignalBuffer b = new SignalBuffer(48_000, 1.0);
        b.append(1f, 1f);
        b.append(2f, 2f);

        float[] out = new float[5];
        int got = b.readLatest(5, out, null);
        assertEquals(2, got, "only 2 samples available; readLatest reports the actual count");
        assertEquals(1f, out[0], 1e-6);
        assertEquals(2f, out[1], 1e-6);
    }

    @Test
    void readLatest_nullChannel_skipsCopy() {
        // Passing null for outRight skips the right-channel copy.
        SignalBuffer b = new SignalBuffer(48_000, 1.0);
        b.append(1f, 11f);
        b.append(2f, 22f);

        float[] outL = new float[2];
        int got = b.readLatest(2, outL, null);
        assertEquals(2, got);
        assertEquals(1f, outL[0], 1e-6);
        assertEquals(2f, outL[1], 1e-6);
    }

    @Test
    void readEndingAt_clipsToOldestAvailable() {
        // Capacity 4.  Write 6 samples (0..5).  The oldest still-resident
        // sample is index (6 - 4) = 2.  Asking for the last 10 ending at
        // 6 should return 4 samples: indices 2, 3, 4, 5 = values 3, 4, 5, 6.
        SignalBuffer b = new SignalBuffer(48_000, 4.0 / 48_000);
        for (int i = 1; i <= 6; i++) b.append(i, -i);

        float[] outL = new float[10];
        int got = b.readEndingAt(6L, 10, outL, null);
        assertEquals(4, got, "older samples are clipped to the ring's oldest");
        assertEquals(3f, outL[0], 1e-6);
        assertEquals(4f, outL[1], 1e-6);
        assertEquals(5f, outL[2], 1e-6);
        assertEquals(6f, outL[3], 1e-6);
    }

    @Test
    void readEndingAt_zeroCount_returnsZero() {
        SignalBuffer b = new SignalBuffer(48_000, 1.0);
        b.append(1f, 1f);
        assertEquals(0, b.readEndingAt(1L, 0, new float[5], null));
    }
}
