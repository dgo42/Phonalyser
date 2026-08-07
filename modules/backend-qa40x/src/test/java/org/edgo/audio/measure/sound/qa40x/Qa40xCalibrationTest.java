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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import org.edgo.audio.measure.preferences.AudioDeviceProfile;
import org.edgo.audio.measure.preferences.DeviceRange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Qa40xCalibration} parsing against a synthetic 512-byte page built from
 * the {@code doc/QA40X-PROTOCOL.md} §6 offset tables: every ADC range (24...108)
 * and DAC range (120...156), both channels (right = left + 6), plus the
 * {@link Qa40xCalibration#fromTransport} read procedure (select 0x10, 128 reads
 * of reg 0x19, little-endian repack).
 */
class Qa40xCalibrationTest {

    private static final double TOL           = 1e-9;
    private static final int    RECORD_BYTES  = 6;
    private static final int    ADC_BASE      = 24;
    private static final int    DAC_BASE      = 120;
    private static final int    RANGE_STRIDE  = 12;

    private byte[] syntheticBlob() {
        byte[] blob = new byte[Qa40xCalibration.CAL_PAGE_BYTES];
        for (int dbv : Qa40xProtocol.inputRangeDbvValues()) {
            int code = Qa40xProtocol.inputRangeCode(dbv);
            int leftOffset = ADC_BASE + code * RANGE_STRIDE;
            putRecord(blob, leftOffset, (short) dbv, 1.0f + code);
            putRecord(blob, leftOffset + RECORD_BYTES, (short) dbv, -(1.0f + code));
        }
        for (int dbv : Qa40xProtocol.outputRangeDbvValues()) {
            int code = Qa40xProtocol.outputRangeCode(dbv);
            int leftOffset = DAC_BASE + code * RANGE_STRIDE;
            putRecord(blob, leftOffset, (short) dbv, 2.0f + code);
            putRecord(blob, leftOffset + RECORD_BYTES, (short) dbv, -(2.0f + code));
        }
        return blob;
    }

    private void putRecord(byte[] blob, int offset, short level, float db) {
        ByteBuffer record = ByteBuffer.wrap(blob, offset, RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        record.putShort(level);
        record.putFloat(db);
    }

    private double linear(double db) {
        return Math.pow(10.0, db / 20.0);
    }

    @Test
    void fromBlob_parsesEveryAdcRangeAndChannel() {
        Qa40xCalibration cal = Qa40xCalibration.fromBlob(syntheticBlob());
        for (int dbv : Qa40xProtocol.inputRangeDbvValues()) {
            int code = Qa40xProtocol.inputRangeCode(dbv);
            assertEquals(linear(1.0 + code), cal.adcLinearFactor(dbv, false), TOL,
                    "ADC left factor at " + dbv + " dBV");
            assertEquals(linear(-(1.0 + code)), cal.adcLinearFactor(dbv, true), TOL,
                    "ADC right factor at " + dbv + " dBV");
        }
    }

    @Test
    void fromBlob_parsesEveryDacRangeAndChannel() {
        Qa40xCalibration cal = Qa40xCalibration.fromBlob(syntheticBlob());
        for (int dbv : Qa40xProtocol.outputRangeDbvValues()) {
            int code = Qa40xProtocol.outputRangeCode(dbv);
            assertEquals(linear(2.0 + code), cal.dacLinearFactor(dbv, false), TOL,
                    "DAC left factor at " + dbv + " dBV");
            assertEquals(linear(-(2.0 + code)), cal.dacLinearFactor(dbv, true), TOL,
                    "DAC right factor at " + dbv + " dBV");
        }
    }

    @Test
    void fromTransport_runsReadProcedureAndMatchesBlob() {
        byte[] blob = syntheticBlob();
        FakeTransport fake = new FakeTransport();
        // Each register-read reply is the little-endian decode of a 4-byte group;
        // fromTransport re-packs it little-endian, round-tripping to the same page.
        ByteBuffer words = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < Qa40xCalibration.CAL_READ_COUNT; i++) {
            fake.readReplies.add(words.getInt());
        }

        Qa40xCalibration cal = Qa40xCalibration.fromTransport(fake);

        // Cal-page select is written first (reg 0x0D = 0x10).
        assertEquals(new FakeTransport.RegWrite(Qa40xProtocol.REG_CAL_PAGE_SELECT,
                Qa40xProtocol.CAL_PAGE_SELECT_VALUE), fake.registerWrites.get(0));
        // Exactly 128 reads of reg 0x19.
        long reads = fake.ops.stream()
                .filter(op -> op.equals("read=" + Qa40xProtocol.REG_CAL_READ))
                .count();
        assertEquals(Qa40xCalibration.CAL_READ_COUNT, reads);
        // Same factors as parsing the page directly.
        Qa40xCalibration reference = Qa40xCalibration.fromBlob(blob);
        for (int dbv : Qa40xProtocol.inputRangeDbvValues()) {
            assertEquals(reference.adcLinearFactor(dbv, false), cal.adcLinearFactor(dbv, false), TOL);
            assertEquals(reference.adcLinearFactor(dbv, true), cal.adcLinearFactor(dbv, true), TOL);
        }
        for (int dbv : Qa40xProtocol.outputRangeDbvValues()) {
            assertEquals(reference.dacLinearFactor(dbv, false), cal.dacLinearFactor(dbv, false), TOL);
            assertEquals(reference.dacLinearFactor(dbv, true), cal.dacLinearFactor(dbv, true), TOL);
        }
    }

    @Test
    void fromBlob_rejectsShortPage() {
        assertThrows(IllegalArgumentException.class, () -> Qa40xCalibration.fromBlob(new byte[256]));
    }

    @Test
    void fromFactors_rebuildsTheTableTheWireRowsDescribe() {
        // The rows the net protocol's qa40x.calibration answers with (spec 4.6) -
        // the same factors the synthetic page encodes, so both constructions must
        // agree on every range and channel.
        List<Qa40xCalibration.RangeFactor> adc = new ArrayList<>();
        for (int dbv : Qa40xProtocol.inputRangeDbvValues()) {
            int code = Qa40xProtocol.inputRangeCode(dbv);
            adc.add(new Qa40xCalibration.RangeFactor(dbv, linear(1.0 + code), linear(-(1.0 + code))));
        }
        List<Qa40xCalibration.RangeFactor> dac = new ArrayList<>();
        for (int dbv : Qa40xProtocol.outputRangeDbvValues()) {
            int code = Qa40xProtocol.outputRangeCode(dbv);
            dac.add(new Qa40xCalibration.RangeFactor(dbv, linear(2.0 + code), linear(-(2.0 + code))));
        }

        Qa40xCalibration cal = Qa40xCalibration.fromFactors(adc, dac);

        Qa40xCalibration reference = Qa40xCalibration.fromBlob(syntheticBlob());
        for (int dbv : Qa40xProtocol.inputRangeDbvValues()) {
            assertEquals(reference.adcLinearFactor(dbv, false), cal.adcLinearFactor(dbv, false), TOL);
            assertEquals(reference.adcLinearFactor(dbv, true), cal.adcLinearFactor(dbv, true), TOL);
        }
        for (int dbv : Qa40xProtocol.outputRangeDbvValues()) {
            assertEquals(reference.dacLinearFactor(dbv, false), cal.dacLinearFactor(dbv, false), TOL);
            assertEquals(reference.dacLinearFactor(dbv, true), cal.dacLinearFactor(dbv, true), TOL);
        }
    }

    @Test
    void fromFactors_missingRowsMeanNoCorrection() {
        Qa40xCalibration cal = Qa40xCalibration.fromFactors(List.of(), List.of());
        assertEquals(1.0, cal.adcLinearFactor(0, false), TOL,
                "an unnamed range gets factor 1.0 - the uncalibrated-device stance");
        assertEquals(1.0, cal.dacLinearFactor(18, true), TOL);
    }

    @Test
    void toProfile_rendersTheCardWithTheBenchActivesAsDefaults() {
        Qa40xCalibration cal = Qa40xCalibration.fromBlob(syntheticBlob());

        AudioDeviceProfile card = cal.toProfile("QA403 remote", null, 6, -2);

        assertTrue(card.getInput().isCalibrationFromDevice(),
                "device-read factors make a calibrationFromDevice card");
        assertEquals(Qa40xProtocol.rangeLabel(6), card.getInput().getActiveRange(),
                "no existing card: the given default (the bench's actual range) is active");
        assertEquals(Qa40xProtocol.rangeLabel(-2), card.getOutput().getActiveRange());
        assertEquals(Qa40xProtocol.inputRangeDbvValues().length,
                card.getInput().getRanges().size(), "one row per attenuator position");
        int dbv = Qa40xProtocol.inputRangeDbvValues()[0];
        DeviceRange row = card.getInput().getRanges().get(0);
        assertEquals(Qa40xLevels.inputFullScaleRmsVolts(dbv, cal.adcLinearFactor(dbv, false)),
                row.getFsLeft(), TOL, "fs volts from the levels math and this page's factor");
        assertEquals(Qa40xLevels.inputFullScaleRmsVolts(dbv, cal.adcLinearFactor(dbv, true)),
                row.getFsRight(), TOL);
    }
}
