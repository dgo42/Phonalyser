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

package org.edgo.audio.measure.sound;

import org.edgo.audio.measure.common.Constants;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.gui.widgets.UnitConversion;
import org.edgo.audio.measure.gui.widgets.UnitFamily;
import org.edgo.audio.measure.gui.widgets.UnitValue;

/**
 * What one volt means on one device and in one direction: the full-scale RMS
 * voltage of each channel, as the device card in force stores it, and every
 * form a consumer derives from it.
 *
 * <p>RMS in BOTH directions, which is what the card holds; the peak form -
 * the ADC's {@code ±full-scale} volts, the generator's amplitude divisor - is
 * {@code fsRms × √2} and the dBV axis offset is {@code 20·log10(fsRms)}.  They
 * are computed HERE, once, so a scope, an FFT view and a generator lane can
 * never derive them three different ways.
 *
 * <p>It exists because calibration follows the device's HOST: a device plugged
 * into a Phonalyser server is calibrated there, and its values travel with the
 * device rather than being looked up again in the client's own card store - a
 * store that knows nothing about the bench across the room and could only match
 * it by a name collision.  {@link AudioDeviceManager#calibration} is how it is
 * read; {@code null} there means "no stored calibration", not "one volt" -
 * {@link #DEFAULT} is what an uncalibrated device measures against until it is
 * calibrated, and it is a consumer's explicit choice to fall back to it.
 *
 * @param fsRmsLeft  the LEFT channel's full-scale, in volts RMS
 * @param fsRmsRight the RIGHT channel's full-scale, in volts RMS - equal to
 *                   {@code fsRmsLeft} for a mono card, where one physical
 *                   channel fills both
 */
public record DeviceCalibration(double fsRmsLeft, double fsRmsRight) {

    /** The full scale an uncalibrated device is measured against: one volt RMS
     *  on both channels, in both directions.  The same figure the web port
     *  seeds. */
    public static final DeviceCalibration DEFAULT = new DeviceCalibration(1.0, 1.0);

    /** {@code ch}'s full scale in volts RMS. */
    public double fsRms(Channel ch) {
        return ch == Channel.R ? fsRmsRight : fsRmsLeft;
    }

    /** {@code ch}'s full scale in PEAK volts ({@code fsRms × √2}): the
     *  {@code ±1.0 -> volts} scale of a captured sample, and the amplitude
     *  divisor of a generated one. */
    public double peakVolts(Channel ch) {
        return fsRms(ch) * Constants.SQRT2;
    }

    /** The dBV↔dBFS offset of {@code ch} ({@code dBV = dBFS + offset}, i.e.
     *  {@code 20·log10(fsRms)}). */
    public double dbvOffsetDb(Channel ch) {
        return 20.0 * Math.log10(fsRms(ch));
    }

    /** An entered amplitude - the number and the unit the operator typed, as
     *  the preferences store it - as canonical V RMS on {@code ch}: a dBFS entry
     *  is defined against this channel's full scale, so a recalibration moves
     *  what it means instead of quietly changing what was entered. */
    public double amplitudeVrms(UnitValue entered, Channel ch) {
        return UnitConversion.convert(UnitFamily.AMPLITUDE, entered.value(), entered.unit(),
                UnitConversion.baseToken(UnitFamily.AMPLITUDE), peakVolts(ch));
    }

    /** {@code vrms} expressed in {@code unit} of the amplitude family on
     *  {@code ch} - the inverse of {@link #amplitudeVrms}, for a clamp that
     *  writes a floor back in the unit the operator chose. */
    public UnitValue amplitudeIn(double vrms, String unit, Channel ch) {
        return new UnitValue(UnitConversion.convert(UnitFamily.AMPLITUDE, vrms,
                UnitConversion.baseToken(UnitFamily.AMPLITUDE), unit, peakVolts(ch)), unit);
    }

    /** An entered dither as a bit depth on {@code ch}: a dBV entry is a TPDF
     *  noise level against this channel's peak full scale, not a depth. */
    public double ditherBits(UnitValue entered, Channel ch) {
        return UnitConversion.convert(UnitFamily.DITHER, entered.value(), entered.unit(),
                UnitConversion.baseToken(UnitFamily.DITHER), peakVolts(ch));
    }
}
