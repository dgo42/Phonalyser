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

package org.edgo.audio.measure.sound.qa40x;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link Qa40xProtocol} frame codec + code maps against {@code doc/QA40X-PROTOCOL.md}
 * §4: the 5-byte big-endian write frame, the {@code 0x80|reg} read request, the
 * 4-byte big-endian reply decode, and every range / rate code map (384 kHz
 * rejected).
 */
class Qa40xProtocolTest {

    @Test
    void writeFrame_sampleRateValue2_matchesDoc() {
        // doc §4: register 0x09 value 2 -> bytes 09 00 00 00 02
        assertArrayEquals(new byte[]{0x09, 0x00, 0x00, 0x00, 0x02},
                Qa40xProtocol.writeFrame(0x09, 2));
    }

    @Test
    void writeFrame_isBigEndian() {
        assertArrayEquals(new byte[]{0x05, 0x01, 0x02, 0x03, 0x04},
                Qa40xProtocol.writeFrame(0x05, 0x01020304));
    }

    @Test
    void readRequestFrame_setsAddressMsb() {
        // doc §4: read request 0x19 -> first byte 0x99 (0x80 | 0x19)
        byte[] frame = Qa40xProtocol.readRequestFrame(0x19);
        assertEquals((byte) 0x99, frame[0]);
        assertArrayEquals(new byte[]{(byte) 0x99, 0x00, 0x00, 0x00, 0x00}, frame);
    }

    @Test
    void decodeReply_isBigEndian() {
        assertEquals(0x01020304, Qa40xProtocol.decodeReply(new byte[]{1, 2, 3, 4}));
        assertEquals(-1, Qa40xProtocol.decodeReply(
                new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}));
    }

    @Test
    void inputRangeCode_isDbvOverSix() {
        for (int dbv : Qa40xProtocol.inputRangeDbvValues()) {
            assertEquals(dbv / 6, Qa40xProtocol.inputRangeCode(dbv));
        }
        assertEquals(0, Qa40xProtocol.inputRangeCode(0));
        assertEquals(7, Qa40xProtocol.inputRangeCode(42));
    }

    @Test
    void inputRangeCode_rejectsOffGrid() {
        assertThrows(IllegalArgumentException.class, () -> Qa40xProtocol.inputRangeCode(3));
        assertThrows(IllegalArgumentException.class, () -> Qa40xProtocol.inputRangeCode(48));
        assertThrows(IllegalArgumentException.class, () -> Qa40xProtocol.inputRangeCode(-6));
    }

    @Test
    void outputRangeCode_mapsFourRanges() {
        assertEquals(0, Qa40xProtocol.outputRangeCode(-12));
        assertEquals(1, Qa40xProtocol.outputRangeCode(-2));
        assertEquals(2, Qa40xProtocol.outputRangeCode(8));
        assertEquals(3, Qa40xProtocol.outputRangeCode(18));
        assertThrows(IllegalArgumentException.class, () -> Qa40xProtocol.outputRangeCode(0));
    }

    @Test
    void sampleRateCode_mapsFourRates() {
        assertEquals(0, Qa40xProtocol.sampleRateCode(48_000));
        assertEquals(1, Qa40xProtocol.sampleRateCode(96_000));
        assertEquals(2, Qa40xProtocol.sampleRateCode(192_000));
        // doc §4: code 3 is the QA403's 384 kHz - the code map itself is universal,
        // the per-model gate lives in sampleRatesHz(model).
        assertEquals(3, Qa40xProtocol.sampleRateCode(384_000));
    }

    @Test
    void sampleRateCode_rejectsRatesWithNoCode() {
        assertThrows(IllegalArgumentException.class, () -> Qa40xProtocol.sampleRateCode(44_100));
        assertThrows(IllegalArgumentException.class, () -> Qa40xProtocol.sampleRateCode(768_000));
    }

    @Test
    void sampleRatesHz_offers384kOnQa403Only() {
        // The QA402 has no reg-9 code 3; the QA403 does (doc §4).
        assertArrayEquals(new int[] {48_000, 96_000, 192_000},
                Qa40xProtocol.sampleRatesHz(Qa40xDeviceFinder.Qa40xModel.QA402));
        assertArrayEquals(new int[] {48_000, 96_000, 192_000, 384_000},
                Qa40xProtocol.sampleRatesHz(Qa40xDeviceFinder.Qa40xModel.QA403));
    }

    @Test
    void rangeValueAccessorsAreDefensiveCopies() {
        int[] values = Qa40xProtocol.inputRangeDbvValues();
        values[0] = 999;
        assertEquals(0, Qa40xProtocol.inputRangeDbvValues()[0]);
    }
}
