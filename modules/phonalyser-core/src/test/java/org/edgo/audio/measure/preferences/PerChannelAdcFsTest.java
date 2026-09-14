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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-channel ADC full-scale, read out of and written into a card's INPUT
 * endpoint.  {@link DeviceEndpointConfig#calibration()} resolves the pair in
 * force: MONO fills both channels from the one physical channel's value; LINKED
 * reads LEFT / RIGHT from the SAME active row's {@code fsLeft} / {@code fsRight};
 * INDEPENDENT resolves LEFT from the {@code activeRange} row and RIGHT from the
 * {@code activeRangeRight} row, a dangling right label falling back to the
 * {@code activeRange} row.  {@link DeviceEndpointConfig#storeFullScale} writes
 * one channel - both, equal, on a MONO endpoint - and seeds a fresh table.  The
 * derived forms (the dBV offset, the peak volts) come from the
 * {@link DeviceCalibration} the endpoint answers, per channel.
 *
 * <p>Pure model tests: no store, no singleton.
 */
class PerChannelAdcFsTest {

    private static final double EPS = 1e-9;

    /** An input endpoint with the given channel mode, range rows and
     *  active-range labels. */
    private DeviceEndpointConfig input(DeviceChannelMode mode, String activeLeft,
                                       String activeRight, DeviceRange... rows) {
        DeviceEndpointConfig ep = new DeviceEndpointConfig();
        ep.setChannels(mode);
        for (DeviceRange r : rows) ep.getRanges().add(r);
        ep.setActiveRangeLeft(activeLeft);
        ep.setActiveRangeRight(activeRight);
        return ep;
    }

    private DeviceRange row(String label, double fsLeft, double fsRight) {
        DeviceRange r = new DeviceRange();
        r.setLabel(label);
        r.setFsLeft(fsLeft);
        r.setFsRight(fsRight);
        return r;
    }

    // ── Resolution: LINKED reads per-channel from the ONE active row ──────────

    @Test
    void calibration_linked_readsPerChannelFromSharedRow() {
        // A LINKED card: ONE active row, but distinct fsLeft / fsRight per channel.
        DeviceCalibration cal = input(DeviceChannelMode.LINKED, "gain", null,
                row("gain", 2.5, 2.7)).calibration();

        assertEquals(2.5, cal.fsRms(Channel.L), EPS, "LEFT from the active row's fsLeft");
        assertEquals(2.7, cal.fsRms(Channel.R), EPS, "RIGHT from the SAME active row's fsRight");
    }

    // ── Resolution: MONO fills BOTH channels from the one value ──────────────

    @Test
    void calibration_mono_fillsBothChannelsFromOneValue() {
        // A MONO card: one physical channel.  Even a stray fsRight on the row is
        // ignored - the single fsLeft fills both channels.
        DeviceCalibration cal = input(DeviceChannelMode.MONO, "gain", null,
                row("gain", 1.9, 9.9)).calibration();

        assertEquals(1.9, cal.fsRms(Channel.L), EPS);
        assertEquals(1.9, cal.fsRms(Channel.R), EPS,
                "MONO fills BOTH channels from the one physical channel's value");
    }

    // ── Resolution: INDEPENDENT fills the pair from distinct rows ────────────

    @Test
    void calibration_independent_fillsPairFromDistinctRows() {
        // Left active = "1.7V" (fsLeft 1.70), right active = "3.4V" (fsRight 3.42).
        DeviceCalibration cal = input(DeviceChannelMode.INDEPENDENT, "1.7V", "3.4V",
                row("1.7V", 1.70, 1.71), row("3.4V", 3.40, 3.42)).calibration();

        assertEquals(1.70, cal.fsRms(Channel.L), EPS, "LEFT from activeRange row's fsLeft");
        assertEquals(3.42, cal.fsRms(Channel.R), EPS, "RIGHT from activeRangeRight row's fsRight");
    }

    // ── Resolution: dangling activeRangeRight falls back to the active row ────

    @Test
    void calibration_independent_danglingRight_fallsBackToActiveRow() {
        // activeRangeRight names a row that does not exist -> fall back to the
        // activeRange ("1.7V") row's fsRight (1.71).
        DeviceCalibration cal = input(DeviceChannelMode.INDEPENDENT, "1.7V", "NOPE",
                row("1.7V", 1.70, 1.71), row("3.4V", 3.40, 3.42)).calibration();

        assertEquals(1.70, cal.fsRms(Channel.L), EPS);
        assertEquals(1.71, cal.fsRms(Channel.R), EPS,
                "dangling right label falls back to the activeRange row's fsRight");
    }

    // ── Resolution: a zero on either channel is no calibration at all ────────

    @Test
    void calibration_uncalibratedRow_isNoCalibration() {
        assertNull(input(DeviceChannelMode.LINKED, "gain", null, row("gain", 0.0, 0.0))
                .calibration(), "a never-calibrated row carries zeros - a divisor of zero");
        assertNull(input(DeviceChannelMode.LINKED, "gain", null, row("gain", 1.7, 0.0))
                .calibration(), "half a calibration would leave one channel reading infinity");
        assertNull(input(DeviceChannelMode.LINKED, null, null).calibration(),
                "and an empty table has no row to read");
    }

    // ── storeFullScale(Channel.R, ...) writes only the right side ─────────────

    @Test
    void storeFullScale_right_onIndependentEndpoint_writesOnlyRightRowField() {
        DeviceEndpointConfig ep = input(DeviceChannelMode.INDEPENDENT, "1.7V", "3.4V",
                row("1.7V", 1.70, 1.71), row("3.4V", 3.40, 3.42));

        ep.storeFullScale(Channel.R, 3.99);

        DeviceRange rightRow = ep.getRanges().get(1);  // "3.4V"
        assertEquals("3.4V", rightRow.getLabel());
        assertEquals(3.99, rightRow.getFsRight(), EPS, "RIGHT calibrate wrote fsRight of the activeRangeRight row");
        assertEquals(3.40, rightRow.getFsLeft(),  EPS, "RIGHT calibrate must NOT touch fsLeft");
        assertTrue(rightRow.isCalibrated(), "a real calibration marks its row");
        // The LEFT active row is entirely untouched.
        DeviceRange leftRow = ep.getRanges().get(0);   // "1.7V"
        assertEquals(1.70, leftRow.getFsLeft(),  EPS);
        assertEquals(1.71, leftRow.getFsRight(), EPS);
        assertEquals(1.70, ep.calibration().fsRms(Channel.L), EPS, "LEFT reads as before");
        assertEquals(3.99, ep.calibration().fsRms(Channel.R), EPS, "RIGHT reads the value");
    }

    @Test
    void storeFullScale_left_onIndependentEndpoint_writesOnlyLeft() {
        DeviceEndpointConfig ep = input(DeviceChannelMode.INDEPENDENT, "1.7V", "3.4V",
                row("1.7V", 1.70, 1.71), row("3.4V", 3.40, 3.42));

        ep.storeFullScale(Channel.L, 1.99);

        DeviceRange leftRow = ep.getRanges().get(0);   // "1.7V"
        assertEquals(1.99, leftRow.getFsLeft(),  EPS, "LEFT calibrate wrote fsLeft of the activeRange row");
        assertEquals(1.71, leftRow.getFsRight(), EPS, "LEFT calibrate must NOT touch fsRight");
        assertEquals(3.42, ep.calibration().fsRms(Channel.R), EPS, "RIGHT untouched by a LEFT calibrate");
    }

    // ── storeFullScale(Channel.R, ...) on a LINKED endpoint writes only right ──

    @Test
    void storeFullScale_right_onLinkedEndpoint_writesOnlyRightOfSharedRow() {
        // A LINKED card keeps per-channel VALUES on ONE shared active row.
        DeviceEndpointConfig ep = input(DeviceChannelMode.LINKED, "gain", null,
                row("gain", 2.5, 2.7));

        ep.storeFullScale(Channel.R, 3.99);

        DeviceRange shared = ep.getRanges().get(0);   // the ONE shared active row
        assertEquals(2.5,  shared.getFsLeft(),  EPS, "RIGHT calibrate must NOT touch fsLeft");
        assertEquals(3.99, shared.getFsRight(), EPS, "RIGHT calibrate wrote fsRight of the shared active row");
    }

    // ── storeFullScale(Channel, ...) on a MONO endpoint keeps both equal ───────

    @Test
    void storeFullScale_onMonoEndpoint_keepsBothChannelsEqual() {
        DeviceEndpointConfig ep = input(DeviceChannelMode.MONO, "gain", null,
                row("gain", 1.9, 1.9));

        ep.storeFullScale(Channel.R, 2.22);

        DeviceRange active = ep.getRanges().get(0);
        assertEquals(2.22, active.getFsLeft(),  EPS, "MONO calibrate writes both row fields equal");
        assertEquals(2.22, active.getFsRight(), EPS, "MONO calibrate writes both row fields equal");
        assertEquals(2.22, ep.calibration().fsRms(Channel.L), EPS);
        assertEquals(2.22, ep.calibration().fsRms(Channel.R), EPS);
    }

    // ── A fresh table: the first calibrate seeds the row and the other channel ─

    @Test
    void storeFullScale_onEmptyTable_seedsTheDefaultRowAndBothChannels() {
        DeviceEndpointConfig ep = input(DeviceChannelMode.LINKED, null, null);

        ep.storeFullScale(Channel.L, 2.22);

        assertEquals(1, ep.getRanges().size(), "one row was created");
        DeviceRange seeded = ep.getRanges().get(0);
        assertEquals("default", seeded.getLabel());
        assertEquals("default", ep.getActiveRangeLeft(),
                "and selected as active");
        assertEquals(2.22, seeded.getFsLeft(),  EPS);
        assertEquals(2.22, seeded.getFsRight(), EPS,
                "the never-calibrated other channel takes the same value - one write "
                        + "calibrates the device instead of leaving it half done");
        assertEquals(2.22, ep.calibration().fsRms(Channel.R), EPS);
    }

    @Test
    void storeFullScale_seedsOnlyANeverCalibratedOtherChannel() {
        DeviceEndpointConfig ep = input(DeviceChannelMode.LINKED, "gain", null,
                row("gain", 2.5, 0.0));

        ep.storeFullScale(Channel.L, 1.99);

        assertEquals(1.99, ep.getRanges().get(0).getFsRight(), EPS,
                "a right channel still at zero takes the measured left");
        assertEquals(1.99, ep.calibration().fsRms(Channel.R), EPS);

        ep.storeFullScale(Channel.R, 3.3);
        ep.storeFullScale(Channel.L, 1.0);
        assertEquals(3.3, ep.getRanges().get(0).getFsRight(), EPS,
                "a right channel once measured is never overwritten by a LEFT write");
    }

    // ── The derived forms are per channel ────────────────────────────────────

    @Test
    void dbvOffset_isPerChannel() {
        DeviceCalibration cal = new DeviceCalibration(1.7931, 3.5862);

        assertEquals(20.0 * Math.log10(1.7931), cal.dbvOffsetDb(Channel.L), EPS);
        assertEquals(20.0 * Math.log10(3.5862), cal.dbvOffsetDb(Channel.R), EPS);
    }

    @Test
    void peakVolts_isPerChannel() {
        DeviceCalibration cal = new DeviceCalibration(2.0, 3.0);

        assertEquals(2.0 * Constants.SQRT2, cal.peakVolts(Channel.L), EPS);
        assertEquals(3.0 * Constants.SQRT2, cal.peakVolts(Channel.R), EPS);
    }
}
