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

import lombok.experimental.UtilityClass;

/**
 * QA402/QA403 register wire protocol — the pure byte math behind
 * {@link Qa40xTransport}'s register traffic (see {@code doc/QA40X-PROTOCOL.md}
 * §4).  It is stateless: register addresses and values, the three range/rate
 * code maps, and the frame codec.
 *
 * <p><b>Register frames are big-endian</b> even though audio samples are
 * little-endian (§5) — two independent endiannesses; this class only speaks the
 * register one.  A write is a 5-byte frame {@code [reg][value MSB..LSB]}; a read
 * is the same frame with the address MSB set ({@code 0x80|reg}, value 0), whose
 * reply is a 4-byte big-endian word.
 *
 * <p><b>Code maps.</b> Input full-scale {@code code = dBV / 6} for 0..42 dBV;
 * output full-scale {@code -12/-2/+8/+18 dBV} → codes 0..3; sample rate
 * {@code 48000/96000/192000 Hz} → codes 0..2.  384 kHz is deliberately NOT
 * exposed — it is input-only single-source on QA402/QA403 and garbles the
 * outputs (§9 item 7), so it is rejected here rather than mapped.
 */
@UtilityClass
public class Qa40xProtocol {

    /** Full-scale input range (attenuator) — reg {@code 0x05}, code 0..7 (§4). */
    public static final int REG_INPUT_FS        = 0x05;
    /** Full-scale output range — reg {@code 0x06}, code 0..3 (§4). */
    public static final int REG_OUTPUT_FS       = 0x06;
    /** Stream / run control — reg {@code 0x08}; {@link #RUN_START}/{@link #RUN_STOP} (§4). */
    public static final int REG_RUN             = 0x08;
    /** Sample-rate select — reg {@code 0x09}, code 0..2 (§4). */
    public static final int REG_SAMPLE_RATE     = 0x09;
    /** Calibration-page select — reg {@code 0x0D}; write {@link #CAL_PAGE_SELECT_VALUE} (§6). */
    public static final int REG_CAL_PAGE_SELECT = 0x0D;
    /** Calibration data read port — reg {@code 0x19}, one 32-bit word per read (§6). */
    public static final int REG_CAL_READ        = 0x19;

    /** {@link #REG_RUN} value that starts streaming (§5). */
    public static final int RUN_START            = 0x05;
    /** {@link #REG_RUN} value that stops streaming / recovers an unclean state (§5). */
    public static final int RUN_STOP             = 0x00;
    /** {@link #REG_CAL_PAGE_SELECT} value that selects the factory cal page (§6). */
    public static final int CAL_PAGE_SELECT_VALUE = 0x10;

    /** Bytes in a register write / read-request frame (§4). */
    public static final int REGISTER_FRAME_BYTES = 5;
    /** Bytes in a register read reply (§4). */
    public static final int REGISTER_REPLY_BYTES = 4;

    /** Address-byte MSB that turns a write frame into a read request (§4). */
    private static final int READ_REQUEST_FLAG   = 0x80;

    /** Input full-scale ranges in dBV (code = dBV / 6). */
    private static final int[] INPUT_RANGE_DBV  = {0, 6, 12, 18, 24, 30, 36, 42};
    /** Output full-scale ranges in dBV (code = index). */
    private static final int[] OUTPUT_RANGE_DBV = {-12, -2, 8, 18};
    /** Duplex-capable sample rates in Hz (code = index). */
    private static final int[] SAMPLE_RATE_HZ   = {48_000, 96_000, 192_000};

    private static final int INPUT_RANGE_STEP_DBV = 6;
    private static final int INPUT_RANGE_MAX_DBV  = 42;
    /** Input-only single-source rate that must never be driven as an output/duplex rate (§9 item 7). */
    private static final int UNSUPPORTED_384K_HZ  = 384_000;

    /** Suffix of a device-card range-row label — the one place the {@code "N dBV"}
     *  card-row encoding lives, so {@link Qa40xDeviceManager}'s card refresh and any
     *  caller resolving a label back to a range dBV agree byte-for-byte. */
    private static final String RANGE_LABEL_SUFFIX = " dBV";

    /** Input full-scale ranges in dBV, ascending — a defensive copy. */
    public int[] inputRangeDbvValues() {
        return INPUT_RANGE_DBV.clone();
    }

    /** Output full-scale ranges in dBV, ascending — a defensive copy. */
    public int[] outputRangeDbvValues() {
        return OUTPUT_RANGE_DBV.clone();
    }

    /** Duplex-capable sample rates in Hz, ascending — a defensive copy. */
    public int[] sampleRatesHz() {
        return SAMPLE_RATE_HZ.clone();
    }

    /** The device-card range-row label for a full-scale range in dBV — {@code "N dBV"}. */
    public String rangeLabel(int dbv) {
        return dbv + RANGE_LABEL_SUFFIX;
    }

    /** Resolves a range-row {@link #rangeLabel(int) label} back to its dBV, searching
     *  {@code candidates} (an input/output {@code *RangeDbvValues()} array); returns
     *  {@code fallback} when {@code label} matches none. */
    public int rangeDbv(String label, int[] candidates, int fallback) {
        for (int dbv : candidates) {
            if (rangeLabel(dbv).equals(label)) {
                return dbv;
            }
        }
        return fallback;
    }

    /** Maps an input full-scale range (dBV) to its reg-{@code 0x05} code; {@code dBV / 6}. */
    public int inputRangeCode(int dbv) {
        if (dbv < 0 || dbv > INPUT_RANGE_MAX_DBV || dbv % INPUT_RANGE_STEP_DBV != 0) {
            throw new IllegalArgumentException(
                    "input full-scale range must be one of 0..42 dBV in 6 dB steps: " + dbv);
        }
        return dbv / INPUT_RANGE_STEP_DBV;
    }

    /** Maps an output full-scale range (dBV) to its reg-{@code 0x06} code. */
    public int outputRangeCode(int dbv) {
        for (int i = 0; i < OUTPUT_RANGE_DBV.length; i++) {
            if (OUTPUT_RANGE_DBV[i] == dbv) {
                return i;
            }
        }
        throw new IllegalArgumentException(
                "output full-scale range must be one of -12/-2/+8/+18 dBV: " + dbv);
    }

    /** Maps a sample rate (Hz) to its reg-{@code 0x09} code; rejects 384 kHz (§9 item 7). */
    public int sampleRateCode(int hz) {
        for (int i = 0; i < SAMPLE_RATE_HZ.length; i++) {
            if (SAMPLE_RATE_HZ[i] == hz) {
                return i;
            }
        }
        if (hz == UNSUPPORTED_384K_HZ) {
            throw new IllegalArgumentException(
                    "384 kHz is input-only on QA402/QA403 and is not exposed as a duplex rate (doc §9 item 7): "
                            + hz);
        }
        throw new IllegalArgumentException("sample rate must be one of 48000/96000/192000 Hz: " + hz);
    }

    /** Builds the 5-byte big-endian register write frame {@code [reg][value MSB..LSB]} (§4). */
    public byte[] writeFrame(int reg, int value) {
        byte[] frame = new byte[REGISTER_FRAME_BYTES];
        frame[0] = (byte) reg;
        frame[1] = (byte) (value >> 24);
        frame[2] = (byte) (value >> 16);
        frame[3] = (byte) (value >> 8);
        frame[4] = (byte) value;
        return frame;
    }

    /** Builds the read-request frame — a write of value 0 with the address MSB set ({@code 0x80|reg}, §4). */
    public byte[] readRequestFrame(int reg) {
        return writeFrame(reg | READ_REQUEST_FLAG, 0);
    }

    /** Decodes a 4-byte big-endian register reply into a 32-bit word (§4). */
    public int decodeReply(byte[] reply) {
        if (reply == null || reply.length < REGISTER_REPLY_BYTES) {
            throw new IllegalArgumentException(
                    "register reply must be at least " + REGISTER_REPLY_BYTES + " bytes");
        }
        return ((reply[0] & 0xFF) << 24)
                | ((reply[1] & 0xFF) << 16)
                | ((reply[2] & 0xFF) << 8)
                | (reply[3] & 0xFF);
    }
}
