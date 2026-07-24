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

import java.util.Arrays;
import java.util.Locale;

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
 * {@code 48000/96000/192000/384000 Hz} → codes 0..3.  The 384 kHz code is
 * <b>QA403-only</b> — the QA402 has no code 3 — so the rate list is per model
 * ({@link #sampleRatesHz(Qa40xDeviceFinder.Qa40xModel)}) while the code map
 * itself is universal.
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

    /** Safe-state input range (doc §7 Teardown step 8, ASIO401 parity): maximum
     *  attenuation, +42 dBV — the fail-safe relay stays engaged (Atten LED lit,
     *  matching the vendor app), so an idle analyzer never sits at a sensitive
     *  range.  Written whenever the stream parks (last lane detach) and at
     *  session close. */
    public static final int SAFE_INPUT_DBV       = 42;
    /** Safe-state output range (doc §7 Teardown step 8): the quietest −12 dBV. */
    public static final int SAFE_OUTPUT_DBV      = -12;

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
    /** Sample rates in Hz (code = index).  The 384 kHz code exists on the QA403
     *  only — see {@link #sampleRatesHz(Qa40xDeviceFinder.Qa40xModel)}. */
    private static final int[] SAMPLE_RATE_HZ   = {48_000, 96_000, 192_000, 384_000};

    private static final int INPUT_RANGE_STEP_DBV = 6;
    private static final int INPUT_RANGE_MAX_DBV  = 42;
    /** Highest rate the QA402 accepts; the 384 kHz code is QA403-only (§4). */
    private static final int QA402_MAX_RATE_HZ    = 192_000;

    /** Suffix of the plain {@code "N dBV"} range-row label — the persisted KEY,
     *  emitted by {@link #rangeLabel(int)} for BOTH directions, so
     *  {@link Qa40xDeviceManager}'s card refresh and any caller resolving a label
     *  back to a range dBV agree byte-for-byte.  The verbose INPUT display label is
     *  built separately in {@link #verboseInputLabel(int)}. */
    private static final String RANGE_LABEL_SUFFIX = " dBV";

    /** Input full-scale ranges in dBV, ascending — a defensive copy. */
    public int[] inputRangeDbvValues() {
        return INPUT_RANGE_DBV.clone();
    }

    /** Output full-scale ranges in dBV, ascending — a defensive copy. */
    public int[] outputRangeDbvValues() {
        return OUTPUT_RANGE_DBV.clone();
    }

    /** Sample rates in Hz for {@code model}, ascending — a defensive copy.
     *  The QA403 adds 384 kHz (reg-9 code 3) on top of the common 48/96/192;
     *  the QA402 has no code 3 (§4). */
    public int[] sampleRatesHz(Qa40xDeviceFinder.Qa40xModel model) {
        if (model == Qa40xDeviceFinder.Qa40xModel.QA402) {
            return Arrays.stream(SAMPLE_RATE_HZ).filter(hz -> hz <= QA402_MAX_RATE_HZ).toArray();
        }
        return SAMPLE_RATE_HZ.clone();
    }

    /** The device-card range-row label — the persisted KEY, plain {@code "N dBV"}
     *  for both directions.  The verbose input DISPLAY (the "really N dBFS / N−9 dBV"
     *  text) is {@link #verboseInputLabel}, carried on the row's
     *  {@code DeviceRange.displayLabel} and shown only in the ranges table; it is
     *  never persisted or parsed. */
    public String rangeLabel(int dbv) {
        return dbv + RANGE_LABEL_SUFFIX;
    }

    /** Verbose DISPLAY label for an input range: {@code N "dBV" real N dBFS or (N−9) dBV}.
     *  The QA "N dBV" input range is really an N-dBFS (Vpp-differential) reference whose
     *  true RMS full scale is {@code ≈ N − 9} dB ({@link Qa40xLevels} / doc §6
     *  cheat-sheet).  Display only — never a card key, so nothing parses it back. */
    public String verboseInputLabel(int dbv) {
        return String.format(Locale.US, "%d \"dBV\" real %d dBFS or %d dBV", dbv, dbv, dbv - 9);
    }

    /** Resolves a plain range-row {@link #rangeLabel label} back to its dBV, searching
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

    /** Maps a sample rate (Hz) to its reg-{@code 0x09} code, 0..3 (§4).  Code 3
     *  (384 kHz) exists on the QA403 only; the per-model rate list is
     *  {@link #sampleRatesHz(Qa40xDeviceFinder.Qa40xModel)}. */
    public int sampleRateCode(int hz) {
        for (int i = 0; i < SAMPLE_RATE_HZ.length; i++) {
            if (SAMPLE_RATE_HZ[i] == hz) {
                return i;
            }
        }
        throw new IllegalArgumentException(
                "sample rate must be one of 48000/96000/192000/384000 Hz: " + hz);
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
