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
import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.DeviceChannelMode;
import org.edgo.audio.measure.enums.MagnitudeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Per-channel ADC full-scale: the RIGHT scalar {@code adcFsVoltageRmsRight} plus
 * its cached dBV offset, the channel-aware {@code applyInputDeviceProfile}
 * resolution (MONO pushes one value into both scalars; LINKED reads LEFT / RIGHT
 * from the SAME active row's {@code fsLeft} / {@code fsRight}; INDEPENDENT resolves
 * LEFT from {@code activeRange}'s {@code fsLeft} and RIGHT from
 * {@code activeRangeRight}'s {@code fsRight}, with a dangling right label falling
 * back to the {@code activeRange} row), and the per-channel {@code storeAdcCalibration(Channel,
 * ...)} write (per-channel for bound stereo cards, both-equal for MONO).
 *
 * <p>Every instance operates on a DETACHED, transient copy (as in
 * {@link DeviceProfileRoundTripTest}) so nothing here touches the live singleton's
 * on-disk store.
 */
class PerChannelAdcFsTest {

    private static final double EPS = 1e-9;

    /** A detached, transient Preferences with the inherited profile list cleared. */
    private Preferences detached() {
        Preferences p = Preferences.instance().copyForDialog();
        p.setTransientMode(true);
        for (AudioDeviceProfile inherited : p.getAudioDeviceProfiles()) {
            p.removeAudioDeviceProfile(inherited.getName());
        }
        return p;
    }

    /** Builds an INPUT-only profile that recognises {@code deviceName} via its
     *  {@code match} list, with the given channel mode + range rows and
     *  active-range labels. */
    private AudioDeviceProfile inputProfile(String name, String deviceName, DeviceChannelMode mode,
                                            String activeLeft, String activeRight, DeviceRange... rows) {
        AudioDeviceProfile p = new AudioDeviceProfile();
        p.setName(name);
        p.getMatch().add(deviceName);
        p.getInput().setChannels(mode);
        for (DeviceRange r : rows) p.getInput().getRanges().add(r);
        p.getInput().setActiveRange(activeLeft);
        p.getInput().setActiveRangeRight(activeRight);
        return p;
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
    void applyInputDeviceProfile_linked_readsPerChannelFromSharedRow() {
        Preferences p = detached();
        // A LINKED card: ONE active row, but distinct fsLeft / fsRight per channel.
        p.putAudioDeviceProfile(inputProfile("Linked Card", "Line (Linked Card)",
                DeviceChannelMode.LINKED, "gain", null, row("gain", 2.5, 2.7)));

        p.setBackend(AudioBackendType.WASAPI);
        p.applyInputDeviceProfile("Line (Linked Card)");

        assertEquals(2.5, p.getAdcFsVoltageRms(Channel.L), EPS, "LEFT from the active row's fsLeft");
        assertEquals(2.7, p.getAdcFsVoltageRms(Channel.R), EPS, "RIGHT from the SAME active row's fsRight");
        assertEquals(2.7, p.getAdcFsVoltageRmsRight(), EPS);
    }

    // ── Resolution: MONO pushes the single value into BOTH scalars ───────────

    @Test
    void applyInputDeviceProfile_mono_fillsBothScalarsFromOneValue() {
        Preferences p = detached();
        // A MONO card: one physical channel.  Even a stray fsRight on the row is
        // ignored - the single fsLeft fills both scalars.
        p.putAudioDeviceProfile(inputProfile("Mono Card", "Line (Mono Card)",
                DeviceChannelMode.MONO, "gain", null, row("gain", 1.9, 9.9)));

        p.setBackend(AudioBackendType.WASAPI);
        p.applyInputDeviceProfile("Line (Mono Card)");

        assertEquals(1.9, p.getAdcFsVoltageRms(Channel.L), EPS);
        assertEquals(1.9, p.getAdcFsVoltageRms(Channel.R), EPS,
                "MONO pushes the one physical channel's value into BOTH scalars");
    }

    // ── Resolution: INDEPENDENT fills the pair from distinct rows ────────────

    @Test
    void applyInputDeviceProfile_independent_fillsPairFromDistinctRows() {
        Preferences p = detached();
        // Left active = "1.7V" (fsLeft 1.70), right active = "3.4V" (fsRight 3.42).
        p.putAudioDeviceProfile(inputProfile("Dual Card", "Line (Dual Card)",
                DeviceChannelMode.INDEPENDENT, "1.7V", "3.4V",
                row("1.7V", 1.70, 1.71), row("3.4V", 3.40, 3.42)));

        p.setBackend(AudioBackendType.WASAPI);
        p.applyInputDeviceProfile("Line (Dual Card)");

        assertEquals(1.70, p.getAdcFsVoltageRms(Channel.L), EPS, "LEFT from activeRange row's fsLeft");
        assertEquals(3.42, p.getAdcFsVoltageRms(Channel.R), EPS, "RIGHT from activeRangeRight row's fsRight");
    }

    // ── Resolution: dangling activeRangeRight falls back to the active row ────

    @Test
    void applyInputDeviceProfile_independent_danglingRight_fallsBackToActiveRow() {
        Preferences p = detached();
        // activeRangeRight names a row that does not exist -> fall back to the
        // activeRange ("1.7V") row's fsRight (1.71).
        p.putAudioDeviceProfile(inputProfile("Dangling Card", "Line (Dangling Card)",
                DeviceChannelMode.INDEPENDENT, "1.7V", "NOPE",
                row("1.7V", 1.70, 1.71), row("3.4V", 3.40, 3.42)));

        p.setBackend(AudioBackendType.WASAPI);
        p.applyInputDeviceProfile("Line (Dangling Card)");

        assertEquals(1.70, p.getAdcFsVoltageRms(Channel.L), EPS);
        assertEquals(1.71, p.getAdcFsVoltageRms(Channel.R), EPS,
                "dangling right label falls back to the activeRange row's fsRight");
    }

    // ── storeAdcCalibration(Channel.R, ...) writes only the right side ─────────

    @Test
    void storeAdcCalibration_right_writesOnlyRightRowFieldAndScalar() {
        Preferences p = detached();
        p.putAudioDeviceProfile(inputProfile("Dual Card", "Line (Dual Card)",
                DeviceChannelMode.INDEPENDENT, "1.7V", "3.4V",
                row("1.7V", 1.70, 1.71), row("3.4V", 3.40, 3.42)));
        p.setBackend(AudioBackendType.WASAPI);
        p.current().setInputDeviceName("Line (Dual Card)");
        p.setAdcFsVoltageRms(1.70);
        p.setAdcFsVoltageRmsRight(3.42);

        p.storeAdcCalibration(Channel.R, 3.99);

        // Only the RIGHT scalar changed.
        assertEquals(1.70, p.getAdcFsVoltageRms(), EPS, "LEFT scalar untouched by a RIGHT calibrate");
        assertEquals(3.99, p.getAdcFsVoltageRmsRight(), EPS, "RIGHT scalar took the value");

        AudioDeviceProfile bound = p.resolveDeviceProfile("Line (Dual Card)");
        DeviceRange rightRow = bound.getInput().getRanges().get(1);  // "3.4V"
        assertEquals("3.4V", rightRow.getLabel());
        assertEquals(3.99, rightRow.getFsRight(), EPS, "RIGHT calibrate wrote fsRight of the activeRangeRight row");
        assertEquals(3.40, rightRow.getFsLeft(),  EPS, "RIGHT calibrate must NOT touch fsLeft");
        // The LEFT active row is entirely untouched.
        DeviceRange leftRow = bound.getInput().getRanges().get(0);   // "1.7V"
        assertEquals(1.70, leftRow.getFsLeft(),  EPS);
        assertEquals(1.71, leftRow.getFsRight(), EPS);
    }

    @Test
    void storeAdcCalibration_left_onIndependentCard_writesOnlyLeft() {
        Preferences p = detached();
        p.putAudioDeviceProfile(inputProfile("Dual Card", "Line (Dual Card)",
                DeviceChannelMode.INDEPENDENT, "1.7V", "3.4V",
                row("1.7V", 1.70, 1.71), row("3.4V", 3.40, 3.42)));
        p.setBackend(AudioBackendType.WASAPI);
        p.current().setInputDeviceName("Line (Dual Card)");
        p.setAdcFsVoltageRms(1.70);
        p.setAdcFsVoltageRmsRight(3.42);

        p.storeAdcCalibration(Channel.L, 1.99);

        assertEquals(1.99, p.getAdcFsVoltageRms(), EPS, "LEFT scalar took the value");
        assertEquals(3.42, p.getAdcFsVoltageRmsRight(), EPS, "RIGHT scalar untouched by a LEFT calibrate");

        AudioDeviceProfile bound = p.resolveDeviceProfile("Line (Dual Card)");
        DeviceRange leftRow = bound.getInput().getRanges().get(0);   // "1.7V"
        assertEquals(1.99, leftRow.getFsLeft(),  EPS, "LEFT calibrate wrote fsLeft of the activeRange row");
        assertEquals(1.71, leftRow.getFsRight(), EPS, "LEFT calibrate must NOT touch fsRight");
    }

    // ── storeAdcCalibration(Channel.R, ...) on a LINKED card writes only right ──

    @Test
    void storeAdcCalibration_right_onLinkedCard_writesOnlyRightOfSharedRow() {
        Preferences p = detached();
        // A LINKED card keeps per-channel VALUES on ONE shared active row.
        p.putAudioDeviceProfile(inputProfile("Linked Card", "Line (Linked Card)",
                DeviceChannelMode.LINKED, "gain", null, row("gain", 2.5, 2.7)));
        p.setBackend(AudioBackendType.WASAPI);
        p.current().setInputDeviceName("Line (Linked Card)");
        p.setAdcFsVoltageRms(2.5);
        p.setAdcFsVoltageRmsRight(2.7);

        p.storeAdcCalibration(Channel.R, 3.99);

        assertEquals(2.5, p.getAdcFsVoltageRms(), EPS, "LEFT scalar untouched by a RIGHT calibrate on a LINKED card");
        assertEquals(3.99, p.getAdcFsVoltageRmsRight(), EPS, "RIGHT scalar took the value");

        AudioDeviceProfile bound = p.resolveDeviceProfile("Line (Linked Card)");
        DeviceRange shared = bound.getInput().getRanges().get(0);   // the ONE shared active row
        assertEquals(2.5,  shared.getFsLeft(),  EPS, "RIGHT calibrate must NOT touch fsLeft");
        assertEquals(3.99, shared.getFsRight(), EPS, "RIGHT calibrate wrote fsRight of the shared active row");
    }

    // ── storeAdcCalibration(Channel, ...) on a MONO card keeps both equal ──────

    @Test
    void storeAdcCalibration_onMonoCard_keepsBothChannelsEqual() {
        Preferences p = detached();
        p.putAudioDeviceProfile(inputProfile("Mono Card", "Line (Mono Card)",
                DeviceChannelMode.MONO, "gain", null, row("gain", 1.9, 1.9)));
        p.setBackend(AudioBackendType.WASAPI);
        p.current().setInputDeviceName("Line (Mono Card)");

        p.storeAdcCalibration(Channel.R, 2.22);

        assertEquals(2.22, p.getAdcFsVoltageRms(), EPS, "MONO calibrate moves both scalars together");
        assertEquals(2.22, p.getAdcFsVoltageRmsRight(), EPS);

        AudioDeviceProfile bound = p.resolveDeviceProfile("Line (Mono Card)");
        DeviceRange active = bound.getInput().getRanges().get(0);
        assertEquals(2.22, active.getFsLeft(),  EPS, "MONO calibrate writes both row fields equal");
        assertEquals(2.22, active.getFsRight(), EPS, "MONO calibrate writes both row fields equal");
    }

    // ── Legacy single-arg store keeps both channels equal ────────────────────

    @Test
    void storeAdcCalibration_legacy_keepsBothChannelsEqual() {
        Preferences p = detached();
        // Even on an INDEPENDENT card, the legacy single-arg write is a LINKED write.
        p.putAudioDeviceProfile(inputProfile("Dual Card", "Line (Dual Card)",
                DeviceChannelMode.INDEPENDENT, "1.7V", "3.4V",
                row("1.7V", 1.70, 1.71), row("3.4V", 3.40, 3.42)));
        p.setBackend(AudioBackendType.WASAPI);
        p.current().setInputDeviceName("Line (Dual Card)");

        p.storeAdcCalibration(2.22);

        assertEquals(2.22, p.getAdcFsVoltageRms(), EPS);
        assertEquals(2.22, p.getAdcFsVoltageRmsRight(), EPS, "legacy store keeps both scalars equal");

        AudioDeviceProfile bound = p.resolveDeviceProfile("Line (Dual Card)");
        DeviceRange active = bound.getInput().getRanges().get(0);    // "1.7V" (activeRange row)
        assertEquals(2.22, active.getFsLeft(),  EPS, "legacy store writes both row fields equal");
        assertEquals(2.22, active.getFsRight(), EPS, "legacy store writes both row fields equal");
    }

    // ── getDbvOffsetDb(Channel) consistency after setters ────────────────────

    @Test
    void dbvOffset_isConsistentPerChannelAfterSetters() {
        Preferences p = detached();
        p.setAdcFsVoltageRms(1.7931);
        p.setAdcFsVoltageRmsRight(3.5862);

        assertEquals(20.0 * Math.log10(1.7931), p.getDbvOffsetDb(Channel.L), EPS);
        assertEquals(20.0 * Math.log10(1.7931), p.getDbvOffsetDb(), EPS,
                "channel-less getter is the LEFT / legacy offset");
        assertEquals(20.0 * Math.log10(3.5862), p.getDbvOffsetDb(Channel.R), EPS);

        // convertFromDbFs(dBV) uses the channel's own offset.
        double dbFs = -6.0;
        assertEquals(dbFs + p.getDbvOffsetDb(Channel.L),
                p.convertFromDbFs(dbFs, MagnitudeUnit.DBV, Channel.L), EPS);
        assertEquals(dbFs + p.getDbvOffsetDb(Channel.R),
                p.convertFromDbFs(dbFs, MagnitudeUnit.DBV, Channel.R), EPS);
    }

    @Test
    void peakVolts_isChannelAware() {
        Preferences p = detached();
        p.setAdcFsVoltageRms(2.0);
        p.setAdcFsVoltageRmsRight(3.0);
        assertEquals(2.0 * Constants.SQRT2, p.getAdcPeakVolts(Channel.L), EPS);
        assertEquals(3.0 * Constants.SQRT2, p.getAdcPeakVolts(Channel.R), EPS);
    }
}
