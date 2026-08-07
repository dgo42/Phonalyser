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
import java.util.Arrays;
import java.util.List;

import org.edgo.audio.measure.enums.DeviceChannelMode;
import org.edgo.audio.measure.preferences.AudioDeviceProfile;
import org.edgo.audio.measure.preferences.DeviceEndpointConfig;
import org.edgo.audio.measure.preferences.DeviceRange;

/**
 * The QA402/QA403 factory calibration page - a 512-byte blob read from the
 * device, parsed into per-range per-channel linear correction factors (see
 * {@code doc/QA40X-PROTOCOL.md} §6).  Immutable; build it with {@link #fromBlob}
 * from an already-assembled page, or {@link #fromTransport} to run the read
 * procedure and parse in one step.
 *
 * <h2>Read procedure (§6)</h2>
 * Write {@link Qa40xProtocol#REG_CAL_PAGE_SELECT} = {@link
 * Qa40xProtocol#CAL_PAGE_SELECT_VALUE}, then read {@link
 * Qa40xProtocol#REG_CAL_READ} exactly {@link #CAL_READ_COUNT} times; each read
 * yields one 32-bit word that is re-packed <b>little-endian</b> into the growing
 * 512-byte page.
 *
 * <h2>Blob layout (§6)</h2>
 * Records are {@code (int16 level, float32 dB)} little-endian, 6 bytes each; the
 * {@code float} is a dB correction giving a linear factor {@code 10^(dB/20)}.
 * Per range the Left record sits at its offset and the Right record at
 * {@code offset + 6}.  ADC (input) left records start at 24 keyed by input dBV
 * {@code 0,6,...,42}; DAC (output) left records start at 120 keyed by output dBV
 * {@code -12,-2,8,18}; consecutive ranges are 12 bytes apart.
 */
public final class Qa40xCalibration {

    /** Bytes in the whole factory cal page (§6). */
    public static final int CAL_PAGE_BYTES = 512;
    /** Register reads that assemble the page: {@code 512 / 4} (§6). */
    public static final int CAL_READ_COUNT = 128;

    /** Bytes per {@code (int16, float32)} record; also the Left->Right stride. */
    private static final int RECORD_BYTES    = 6;
    /** Bytes between consecutive ranges' Left records (Left + Right). */
    private static final int RANGE_STRIDE    = 2 * RECORD_BYTES;
    /** Byte offset of the first (0 dBV) ADC Left record. */
    private static final int ADC_BASE_OFFSET = 24;
    /** Byte offset of the first (-12 dBV) DAC Left record. */
    private static final int DAC_BASE_OFFSET = 120;
    /** Bytes of {@code int16 level} preceding the {@code float32} dB in a record. */
    private static final int LEVEL_BYTES     = 2;
    private static final double DB_DIVISOR   = 20.0;

    /** Input-range linear factors, indexed by reg-{@code 0x05} code, Left channel. */
    private final double[] adcLeft;
    /** Input-range linear factors, indexed by reg-{@code 0x05} code, Right channel. */
    private final double[] adcRight;
    /** Output-range linear factors, indexed by reg-{@code 0x06} code, Left channel. */
    private final double[] dacLeft;
    /** Output-range linear factors, indexed by reg-{@code 0x06} code, Right channel. */
    private final double[] dacRight;

    /** One range's per-channel linear factors - the shape the net protocol's
     *  {@code qa40x.calibration} rows carry (spec 4.6). */
    public record RangeFactor(int dbv, double left, double right) {
    }

    private Qa40xCalibration(double[] adcLeft, double[] adcRight,
            double[] dacLeft, double[] dacRight) {
        this.adcLeft  = adcLeft;
        this.adcRight = adcRight;
        this.dacLeft  = dacLeft;
        this.dacRight = dacRight;
    }

    private Qa40xCalibration(byte[] blob) {
        if (blob == null || blob.length < CAL_PAGE_BYTES) {
            throw new IllegalArgumentException("cal page must be at least " + CAL_PAGE_BYTES + " bytes");
        }
        int[] inputDbv = Qa40xProtocol.inputRangeDbvValues();
        int[] outputDbv = Qa40xProtocol.outputRangeDbvValues();
        this.adcLeft  = new double[inputDbv.length];
        this.adcRight = new double[inputDbv.length];
        this.dacLeft  = new double[outputDbv.length];
        this.dacRight = new double[outputDbv.length];
        for (int dbv : inputDbv) {
            int code = Qa40xProtocol.inputRangeCode(dbv);
            int leftOffset = ADC_BASE_OFFSET + code * RANGE_STRIDE;
            adcLeft[code]  = decodeLinearFactor(blob, leftOffset);
            adcRight[code] = decodeLinearFactor(blob, leftOffset + RECORD_BYTES);
        }
        for (int dbv : outputDbv) {
            int code = Qa40xProtocol.outputRangeCode(dbv);
            int leftOffset = DAC_BASE_OFFSET + code * RANGE_STRIDE;
            dacLeft[code]  = decodeLinearFactor(blob, leftOffset);
            dacRight[code] = decodeLinearFactor(blob, leftOffset + RECORD_BYTES);
        }
    }

    /** Parses an already-assembled 512-byte cal page (§6). */
    public static Qa40xCalibration fromBlob(byte[] blob) {
        return new Qa40xCalibration(blob);
    }

    /**
     * Builds the table from already-decoded linear factors - the rows the net
     * protocol's {@code qa40x.calibration} answers with (spec 4.6), which is how
     * a REMOTE analyzer's table is rebuilt without the USB read.  A range the
     * rows do not name keeps factor {@code 1.0}: no correction, the same stance
     * the levels math takes on a device whose page could not be read.
     */
    public static Qa40xCalibration fromFactors(List<RangeFactor> adc, List<RangeFactor> dac) {
        double[] adcLeft  = ones(Qa40xProtocol.inputRangeDbvValues().length);
        double[] adcRight = ones(adcLeft.length);
        double[] dacLeft  = ones(Qa40xProtocol.outputRangeDbvValues().length);
        double[] dacRight = ones(dacLeft.length);
        for (RangeFactor row : adc) {
            int code = Qa40xProtocol.inputRangeCode(row.dbv());
            adcLeft[code]  = row.left();
            adcRight[code] = row.right();
        }
        for (RangeFactor row : dac) {
            int code = Qa40xProtocol.outputRangeCode(row.dbv());
            dacLeft[code]  = row.left();
            dacRight[code] = row.right();
        }
        return new Qa40xCalibration(adcLeft, adcRight, dacLeft, dacRight);
    }

    private static double[] ones(int length) {
        double[] factors = new double[length];
        Arrays.fill(factors, 1.0);
        return factors;
    }

    /**
     * Runs the §6 read procedure over {@code transport} - selects the cal page,
     * reads {@link #CAL_READ_COUNT} words re-packed little-endian - then parses.
     */
    public static Qa40xCalibration fromTransport(Qa40xTransport transport) {
        transport.registerWrite(Qa40xProtocol.REG_CAL_PAGE_SELECT, Qa40xProtocol.CAL_PAGE_SELECT_VALUE);
        byte[] blob = new byte[CAL_PAGE_BYTES];
        ByteBuffer page = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < CAL_READ_COUNT; i++) {
            page.putInt(transport.registerRead(Qa40xProtocol.REG_CAL_READ));
        }
        return fromBlob(blob);
    }

    /** Linear input (ADC) cal factor for an input range, per channel. */
    public double adcLinearFactor(int inputDbv, boolean rightChannel) {
        int code = Qa40xProtocol.inputRangeCode(inputDbv);
        return rightChannel ? adcRight[code] : adcLeft[code];
    }

    /** Linear output (DAC) cal factor for an output range, per channel. */
    public double dacLinearFactor(int outputDbv, boolean rightChannel) {
        int code = Qa40xProtocol.outputRangeCode(outputDbv);
        return rightChannel ? dacRight[code] : dacLeft[code];
    }

    /**
     * Renders this table as the analyzer's device card: a LINKED stereo endpoint
     * per direction, one range row per attenuator/gain position with per-channel
     * full-scale RMS volts derived from {@link Qa40xLevels} and these factors,
     * both endpoints {@code calibrationFromDevice}.  When an {@code existing}
     * card is present its match aliases and active-range selections survive the
     * refresh; otherwise the actives fall back to the given dBV defaults.
     *
     * <p>It lives on the calibration rather than in the device manager because
     * the factors ARE the card's substance - and so the one implementation
     * serves both the analyzer wired to this machine and the same analyzer
     * reached over the net bridge, whose factors arrive as {@link RangeFactor}
     * rows instead of a USB read.
     */
    public AudioDeviceProfile toProfile(String cardName, AudioDeviceProfile existing,
            int defaultInputDbv, int defaultOutputDbv) {
        AudioDeviceProfile card = new AudioDeviceProfile();
        card.setName(cardName);
        card.getMatch().add(cardName);
        if (existing != null) {
            for (String entry : existing.getMatch()) {
                if (!card.getMatch().contains(entry)) {
                    card.getMatch().add(entry);
                }
            }
        }
        card.setInput(buildEndpoint(true,
                existing != null ? existing.getInput() : null, defaultInputDbv));
        card.setOutput(buildEndpoint(false,
                existing != null ? existing.getOutput() : null, defaultOutputDbv));
        return card;
    }

    private DeviceEndpointConfig buildEndpoint(boolean input, DeviceEndpointConfig existing,
            int defaultDbv) {
        DeviceEndpointConfig ep = new DeviceEndpointConfig();
        ep.setChannels(DeviceChannelMode.LINKED);
        ep.setCalibrationFromDevice(true);
        int[] dbvValues = input ? Qa40xProtocol.inputRangeDbvValues() : Qa40xProtocol.outputRangeDbvValues();
        for (int dbv : dbvValues) {
            DeviceRange row = new DeviceRange();
            row.setLabel(Qa40xProtocol.rangeLabel(dbv));   // plain "N dBV" - the persisted key
            if (input) {
                // The input "N dBV" range is really an N-dBFS (Vpp-differential)
                // reference; show its real levels in the ranges table while the key
                // stays plain (doc §6 cheat-sheet).
                row.setDisplayLabel(Qa40xProtocol.verboseInputLabel(dbv));
                row.setFsLeft(Qa40xLevels.inputFullScaleRmsVolts(dbv, adcLinearFactor(dbv, false)));
                row.setFsRight(Qa40xLevels.inputFullScaleRmsVolts(dbv, adcLinearFactor(dbv, true)));
            } else {
                row.setFsLeft(Qa40xLevels.outputFullScaleRmsVolts(dbv, dacLinearFactor(dbv, false)));
                row.setFsRight(Qa40xLevels.outputFullScaleRmsVolts(dbv, dacLinearFactor(dbv, true)));
            }
            row.setCalibrated(true);         // device-owned values - never seed-refreshed away
            ep.getRanges().add(row);
        }
        String defaultActive = Qa40xProtocol.rangeLabel(defaultDbv);
        String active = (existing != null && hasRow(ep, existing.getActiveRange()))
                ? existing.getActiveRange() : defaultActive;
        ep.setActiveRange(active);
        if (existing != null && hasRow(ep, existing.getActiveRangeRight())) {
            ep.setActiveRangeRight(existing.getActiveRangeRight());
        }
        return ep;
    }

    private boolean hasRow(DeviceEndpointConfig ep, String label) {
        if (label == null) {
            return false;
        }
        for (DeviceRange row : ep.getRanges()) {
            if (label.equals(row.getLabel())) {
                return true;
            }
        }
        return false;
    }

    /** Decodes one {@code (int16 level, float32 dB)} record to its linear factor {@code 10^(dB/20)}. */
    private double decodeLinearFactor(byte[] blob, int offset) {
        ByteBuffer record = ByteBuffer.wrap(blob, offset, RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        record.position(offset + LEVEL_BYTES);   // skip the int16 level; the dB correction follows
        float db = record.getFloat();
        return Math.pow(10.0, db / DB_DIVISOR);
    }
}
