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

package org.edgo.audio.measure.preferences;

import org.edgo.audio.measure.common.Constants;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.DeviceChannelMode;
import org.edgo.audio.measure.sound.DeviceCalibration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Per-channel DAC full-scale - the OUTPUT mirror of {@link PerChannelAdcFsTest}:
 * the same {@link DeviceEndpointConfig#calibration()} resolution on a card's
 * output endpoint (MONO fills both channels; LINKED reads LEFT / RIGHT from the
 * SAME active row; INDEPENDENT resolves RIGHT from {@code activeRangeRight}),
 * the same per-channel {@link DeviceEndpointConfig#storeFullScale} write.
 *
 * <p>The card holds the DAC full scale as RMS, exactly as the ADC's; the PEAK
 * form a generator lane divides its amplitude by is {@code fsRms × √2}, and it
 * is derived by {@link DeviceCalibration#peakVolts} - nowhere else - so every
 * assertion on a row here is in RMS and the one on the peak form carries the
 * {@code × √2}.
 *
 * <p>Pure model tests: no store, no singleton.
 */
class PerChannelDacFsTest {

    private static final double EPS = 1e-9;

    /** An output endpoint with the given channel mode, range rows and
     *  active-range labels. */
    private DeviceEndpointConfig output(DeviceChannelMode mode, String activeLeft,
                                        String activeRight, DeviceRange... rows) {
        DeviceEndpointConfig ep = new DeviceEndpointConfig();
        ep.setChannels(mode);
        for (DeviceRange r : rows) ep.getRanges().add(r);
        ep.setActiveRange(activeLeft);
        ep.setActiveRangeRight(activeRight);
        return ep;
    }

    /** A range row whose fsLeft / fsRight are the RMS form on disk. */
    private DeviceRange row(String label, double fsRmsLeft, double fsRmsRight) {
        DeviceRange r = new DeviceRange();
        r.setLabel(label);
        r.setFsLeft(fsRmsLeft);
        r.setFsRight(fsRmsRight);
        return r;
    }

    // ── Resolution: MONO fills BOTH channels from the one value ──────────────

    @Test
    void calibration_mono_fillsBothChannelsFromOneValue() {
        // MONO: one physical channel; the stray fsRight on the row is ignored.
        DeviceCalibration cal = output(DeviceChannelMode.MONO, "default", null,
                row("default", 2.0, 9.0)).calibration();

        assertEquals(2.0, cal.fsRms(Channel.L), EPS);
        assertEquals(2.0, cal.fsRms(Channel.R), EPS,
                "MONO fills BOTH channels from the one physical channel's value");
    }

    // ── Resolution: LINKED reads per-channel from the ONE active row ─────────

    @Test
    void calibration_linked_readsPerChannelFromSharedRow() {
        // A LINKED DAC: ONE active row, distinct fsLeft / fsRight (RMS on disk).
        DeviceCalibration cal = output(DeviceChannelMode.LINKED, "default", null,
                row("default", 2.0, 2.2)).calibration();

        assertEquals(2.0, cal.fsRms(Channel.L), EPS, "LEFT from the active row's fsLeft");
        assertEquals(2.2, cal.fsRms(Channel.R), EPS, "RIGHT from the SAME active row's fsRight");
    }

    // ── Resolution: INDEPENDENT fills the pair from distinct rows ────────────

    @Test
    void calibration_independent_fillsPairFromDistinctRows() {
        DeviceCalibration cal = output(DeviceChannelMode.INDEPENDENT, "lo", "hi",
                row("lo", 1.0, 1.1), row("hi", 3.0, 3.3)).calibration();

        assertEquals(1.0, cal.fsRms(Channel.L), EPS, "LEFT from activeRange row's fsLeft");
        assertEquals(3.3, cal.fsRms(Channel.R), EPS, "RIGHT from activeRangeRight row's fsRight");
    }

    // ── Resolution: dangling activeRangeRight falls back to the active row ────

    @Test
    void calibration_independent_danglingRight_fallsBackToActiveRow() {
        DeviceCalibration cal = output(DeviceChannelMode.INDEPENDENT, "lo", "NOPE",
                row("lo", 1.0, 1.1), row("hi", 3.0, 3.3)).calibration();

        assertEquals(1.0, cal.fsRms(Channel.L), EPS);
        assertEquals(1.1, cal.fsRms(Channel.R), EPS,
                "dangling right label falls back to the activeRange row's fsRight");
    }

    // ── storeFullScale(Channel.R, ...) on INDEPENDENT writes only right ───────

    @Test
    void storeFullScale_right_onIndependentEndpoint_writesOnlyRight() {
        DeviceEndpointConfig ep = output(DeviceChannelMode.INDEPENDENT, "lo", "hi",
                row("lo", 1.0, 1.1), row("hi", 3.0, 3.3));

        ep.storeFullScale(Channel.R, 4.0);

        DeviceRange hi = ep.getRanges().get(1);   // "hi" = activeRangeRight row
        assertEquals(4.0, hi.getFsRight(), EPS, "RIGHT calibrate wrote fsRight (RMS)");
        assertEquals(3.0, hi.getFsLeft(),  EPS, "RIGHT calibrate must NOT touch fsLeft");
        DeviceRange lo = ep.getRanges().get(0);   // "lo" = activeRange row, untouched
        assertEquals(1.0, lo.getFsLeft(),  EPS);
        assertEquals(1.1, lo.getFsRight(), EPS);
        assertEquals(1.0, ep.calibration().fsRms(Channel.L), EPS, "LEFT untouched by a RIGHT DAC calibrate");
        assertEquals(4.0, ep.calibration().fsRms(Channel.R), EPS, "RIGHT took the value");
    }

    // ── storeFullScale(Channel.R, ...) on a LINKED endpoint writes only right ─

    @Test
    void storeFullScale_right_onLinkedEndpoint_writesOnlyRightOfSharedRow() {
        DeviceEndpointConfig ep = output(DeviceChannelMode.LINKED, "default", null,
                row("default", 2.0, 2.2));

        ep.storeFullScale(Channel.R, 3.5);

        DeviceRange shared = ep.getRanges().get(0);   // the ONE shared active row
        assertEquals(2.0, shared.getFsLeft(),  EPS, "RIGHT calibrate must NOT touch fsLeft");
        assertEquals(3.5, shared.getFsRight(), EPS, "RIGHT calibrate wrote fsRight of the shared active row (RMS)");
    }

    // ── storeFullScale(Channel, ...) on a MONO endpoint keeps both equal ──────

    @Test
    void storeFullScale_onMonoEndpoint_keepsBothChannelsEqual() {
        DeviceEndpointConfig ep = output(DeviceChannelMode.MONO, "default", null,
                row("default", 2.0, 2.0));

        ep.storeFullScale(Channel.R, 2.5);

        DeviceRange active = ep.getRanges().get(0);
        assertEquals(2.5, active.getFsLeft(),  EPS, "MONO calibrate writes both row fields equal (RMS)");
        assertEquals(2.5, active.getFsRight(), EPS, "MONO calibrate writes both row fields equal (RMS)");
        assertEquals(2.5, ep.calibration().fsRms(Channel.L), EPS);
        assertEquals(2.5, ep.calibration().fsRms(Channel.R), EPS);
    }

    // ── The peak form a generator lane divides by ────────────────────────────

    @Test
    void peakVolts_isTheRmsFullScaleTimesSqrt2_perChannel() {
        DeviceCalibration cal = output(DeviceChannelMode.LINKED, "default", null,
                row("default", 2.0, 2.2)).calibration();

        assertEquals(2.0 * Constants.SQRT2, cal.peakVolts(Channel.L), EPS,
                "the card holds RMS; the lane's amplitude divisor is the peak form");
        assertEquals(2.2 * Constants.SQRT2, cal.peakVolts(Channel.R), EPS,
                "each lane against its own full scale");
    }

    @Test
    void anUncalibratedDeviceIsDrivenAgainstOneVoltRms() {
        assertEquals(Constants.SQRT2, DeviceCalibration.DEFAULT.peakVolts(Channel.L), EPS);
        assertEquals(Constants.SQRT2, DeviceCalibration.DEFAULT.peakVolts(Channel.R), EPS);
    }
}
