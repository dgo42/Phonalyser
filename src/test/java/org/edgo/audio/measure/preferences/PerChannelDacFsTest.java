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

package org.edgo.audio.measure.preferences;

import org.edgo.audio.measure.common.Constants;
import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.DeviceChannelMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Per-channel DAC full-scale — the OUTPUT mirror of {@link PerChannelAdcFsTest}:
 * the RIGHT scalar {@code dacFsVoltageAmplRight}, the channel-aware
 * {@code applyOutputDeviceProfile} resolution (MONO pushes one value into both
 * scalars; LINKED reads LEFT / RIGHT from the SAME active row; INDEPENDENT resolves
 * RIGHT from {@code activeRangeRight}), the per-channel
 * {@code storeDacCalibration(Channel, …)} write (per-channel for bound stereo cards,
 * both-equal for MONO).
 *
 * <p>The DAC value is a PEAK amplitude in memory but persists as RMS ({@code ÷ √2}
 * on save, {@code × √2} on load), and the device-profile rows store the RMS form —
 * so every profile assertion below carries the {@code / √2} conversion, exactly as
 * {@code applyOutputDeviceProfile} / {@code storeDacCalibration} do.
 *
 * <p>Every instance operates on a DETACHED, transient copy (as in
 * {@link DeviceProfileRoundTripTest}) so nothing here touches the live singleton's
 * on-disk store.
 */
class PerChannelDacFsTest {

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

    /** Builds an OUTPUT-only profile that recognises {@code deviceName} via its
     *  {@code match} list, with the given channel mode + range rows and
     *  active-range labels. */
    private AudioDeviceProfile outputProfile(String name, String deviceName, DeviceChannelMode mode,
                                             String activeLeft, String activeRight, DeviceRange... rows) {
        AudioDeviceProfile p = new AudioDeviceProfile();
        p.setName(name);
        p.getMatch().add(deviceName);
        p.getOutput().setChannels(mode);
        for (DeviceRange r : rows) p.getOutput().getRanges().add(r);
        p.getOutput().setActiveRange(activeLeft);
        p.getOutput().setActiveRangeRight(activeRight);
        return p;
    }

    /** A range row whose fsLeft / fsRight are the RMS form on disk. */
    private DeviceRange row(String label, double fsRmsLeft, double fsRmsRight) {
        DeviceRange r = new DeviceRange();
        r.setLabel(label);
        r.setFsLeft(fsRmsLeft);
        r.setFsRight(fsRmsRight);
        return r;
    }

    // ── Resolution: MONO pushes the single value into BOTH scalars ───────────

    @Test
    void applyOutputDeviceProfile_mono_fillsBothScalarsFromOneValue() {
        Preferences p = detached();
        // MONO: one physical channel; the stray fsRight on the row is ignored.
        p.putAudioDeviceProfile(outputProfile("Mono DAC", "Speakers (Mono DAC)",
                DeviceChannelMode.MONO, "default", null, row("default", 2.0, 9.0)));

        p.setBackend(AudioBackendType.WASAPI);
        p.applyOutputDeviceProfile("Speakers (Mono DAC)");

        assertEquals(2.0 * Constants.SQRT2, p.getDacFsVoltageAmpl(Channel.L), EPS);
        assertEquals(2.0 * Constants.SQRT2, p.getDacFsVoltageAmpl(Channel.R), EPS,
                "MONO pushes the one physical channel's value (× √2) into BOTH scalars");
    }

    // ── Resolution: LINKED reads per-channel from the ONE active row ─────────

    @Test
    void applyOutputDeviceProfile_linked_readsPerChannelFromSharedRow() {
        Preferences p = detached();
        // A LINKED DAC: ONE active row, distinct fsLeft / fsRight (RMS on disk).
        p.putAudioDeviceProfile(outputProfile("Linked DAC", "Speakers (Linked DAC)",
                DeviceChannelMode.LINKED, "default", null, row("default", 2.0, 2.2)));

        p.setBackend(AudioBackendType.WASAPI);
        p.applyOutputDeviceProfile("Speakers (Linked DAC)");

        assertEquals(2.0 * Constants.SQRT2, p.getDacFsVoltageAmpl(Channel.L), EPS,
                "LEFT from the active row's fsLeft (× √2)");
        assertEquals(2.2 * Constants.SQRT2, p.getDacFsVoltageAmpl(Channel.R), EPS,
                "RIGHT from the SAME active row's fsRight (× √2)");
    }

    // ── Resolution: INDEPENDENT fills the pair from distinct rows ────────────

    @Test
    void applyOutputDeviceProfile_independent_fillsPairFromDistinctRows() {
        Preferences p = detached();
        p.putAudioDeviceProfile(outputProfile("Dual DAC", "Speakers (Dual DAC)",
                DeviceChannelMode.INDEPENDENT, "lo", "hi",
                row("lo", 1.0, 1.1), row("hi", 3.0, 3.3)));

        p.setBackend(AudioBackendType.WASAPI);
        p.applyOutputDeviceProfile("Speakers (Dual DAC)");

        assertEquals(1.0 * Constants.SQRT2, p.getDacFsVoltageAmpl(Channel.L), EPS,
                "LEFT from activeRange row's fsLeft (× √2)");
        assertEquals(3.3 * Constants.SQRT2, p.getDacFsVoltageAmpl(Channel.R), EPS,
                "RIGHT from activeRangeRight row's fsRight (× √2)");
    }

    // ── Resolution: dangling activeRangeRight falls back to the active row ────

    @Test
    void applyOutputDeviceProfile_independent_danglingRight_fallsBackToActiveRow() {
        Preferences p = detached();
        p.putAudioDeviceProfile(outputProfile("Dangling DAC", "Speakers (Dangling DAC)",
                DeviceChannelMode.INDEPENDENT, "lo", "NOPE",
                row("lo", 1.0, 1.1), row("hi", 3.0, 3.3)));

        p.setBackend(AudioBackendType.WASAPI);
        p.applyOutputDeviceProfile("Speakers (Dangling DAC)");

        assertEquals(1.0 * Constants.SQRT2, p.getDacFsVoltageAmpl(Channel.L), EPS);
        assertEquals(1.1 * Constants.SQRT2, p.getDacFsVoltageAmpl(Channel.R), EPS,
                "dangling right label falls back to the activeRange row's fsRight");
    }

    // ── storeDacCalibration(Channel.R, …) on INDEPENDENT writes only right ───

    @Test
    void storeDacCalibration_right_onIndependentCard_writesOnlyRight() {
        Preferences p = detached();
        p.putAudioDeviceProfile(outputProfile("Dual DAC", "Speakers (Dual DAC)",
                DeviceChannelMode.INDEPENDENT, "lo", "hi",
                row("lo", 1.0, 1.1), row("hi", 3.0, 3.3)));
        p.setBackend(AudioBackendType.WASAPI);
        p.current().setOutputDeviceName("Speakers (Dual DAC)");
        p.setDacFsVoltageAmpl(1.0 * Constants.SQRT2);
        p.setDacFsVoltageAmplRight(3.3 * Constants.SQRT2);

        double newAmpl = 4.0 * Constants.SQRT2;
        p.storeDacCalibration(Channel.R, newAmpl);

        assertEquals(1.0 * Constants.SQRT2, p.getDacFsVoltageAmpl(), EPS,
                "LEFT scalar untouched by a RIGHT DAC calibrate");
        assertEquals(newAmpl, p.getDacFsVoltageAmplRight(), EPS, "RIGHT scalar took the value");

        AudioDeviceProfile bound = p.resolveDeviceProfile("Speakers (Dual DAC)");
        DeviceRange hi = bound.getOutput().getRanges().get(1);   // "hi" = activeRangeRight row
        assertEquals(4.0, hi.getFsRight(), EPS, "RIGHT calibrate wrote fsRight (RMS = ampl / √2)");
        assertEquals(3.0, hi.getFsLeft(),  EPS, "RIGHT calibrate must NOT touch fsLeft");
        DeviceRange lo = bound.getOutput().getRanges().get(0);   // "lo" = activeRange row, untouched
        assertEquals(1.0, lo.getFsLeft(),  EPS);
        assertEquals(1.1, lo.getFsRight(), EPS);
    }

    // ── storeDacCalibration(Channel.R, …) on a LINKED card writes only right ─

    @Test
    void storeDacCalibration_right_onLinkedCard_writesOnlyRightOfSharedRow() {
        Preferences p = detached();
        p.putAudioDeviceProfile(outputProfile("Linked DAC", "Speakers (Linked DAC)",
                DeviceChannelMode.LINKED, "default", null, row("default", 2.0, 2.2)));
        p.setBackend(AudioBackendType.WASAPI);
        p.current().setOutputDeviceName("Speakers (Linked DAC)");
        p.setDacFsVoltageAmpl(2.0 * Constants.SQRT2);
        p.setDacFsVoltageAmplRight(2.2 * Constants.SQRT2);

        double newAmpl = 3.5 * Constants.SQRT2;
        p.storeDacCalibration(Channel.R, newAmpl);

        assertEquals(2.0 * Constants.SQRT2, p.getDacFsVoltageAmpl(), EPS,
                "LEFT scalar untouched by a RIGHT calibrate on a LINKED card");
        assertEquals(newAmpl, p.getDacFsVoltageAmplRight(), EPS, "RIGHT scalar took the value");

        AudioDeviceProfile bound = p.resolveDeviceProfile("Speakers (Linked DAC)");
        DeviceRange shared = bound.getOutput().getRanges().get(0);   // the ONE shared active row
        assertEquals(2.0, shared.getFsLeft(),  EPS, "RIGHT calibrate must NOT touch fsLeft");
        assertEquals(3.5, shared.getFsRight(), EPS, "RIGHT calibrate wrote fsRight of the shared active row (RMS)");
    }

    // ── storeDacCalibration(Channel, …) on a MONO card keeps both equal ──────

    @Test
    void storeDacCalibration_onMonoCard_keepsBothChannelsEqual() {
        Preferences p = detached();
        p.putAudioDeviceProfile(outputProfile("Mono DAC", "Speakers (Mono DAC)",
                DeviceChannelMode.MONO, "default", null, row("default", 2.0, 2.0)));
        p.setBackend(AudioBackendType.WASAPI);
        p.current().setOutputDeviceName("Speakers (Mono DAC)");

        double newAmpl = 2.5 * Constants.SQRT2;
        p.storeDacCalibration(Channel.R, newAmpl);

        assertEquals(newAmpl, p.getDacFsVoltageAmpl(), EPS, "MONO calibrate moves both scalars together");
        assertEquals(newAmpl, p.getDacFsVoltageAmplRight(), EPS);

        AudioDeviceProfile bound = p.resolveDeviceProfile("Speakers (Mono DAC)");
        DeviceRange active = bound.getOutput().getRanges().get(0);
        assertEquals(2.5, active.getFsLeft(),  EPS, "MONO calibrate writes both row fields equal (RMS)");
        assertEquals(2.5, active.getFsRight(), EPS, "MONO calibrate writes both row fields equal (RMS)");
    }

    // ── Legacy single-arg store keeps both channels equal ────────────────────

    @Test
    void storeDacCalibration_legacy_keepsBothChannelsEqual() {
        Preferences p = detached();
        // Even on a LINKED card, the legacy single-arg write is a both-equal write.
        p.putAudioDeviceProfile(outputProfile("Linked DAC", "Speakers (Linked DAC)",
                DeviceChannelMode.LINKED, "default", null, row("default", 2.0, 2.2)));
        p.setBackend(AudioBackendType.WASAPI);
        p.current().setOutputDeviceName("Speakers (Linked DAC)");

        double newAmpl = 2.79351;
        p.storeDacCalibration(newAmpl);

        assertEquals(newAmpl, p.getDacFsVoltageAmpl(), EPS);
        assertEquals(newAmpl, p.getDacFsVoltageAmplRight(), EPS, "legacy store keeps both scalars equal");

        AudioDeviceProfile bound = p.resolveDeviceProfile("Speakers (Linked DAC)");
        DeviceRange active = bound.getOutput().getRanges().get(0);
        assertEquals(newAmpl / Constants.SQRT2, active.getFsLeft(),  EPS, "legacy store writes both row fields equal (RMS)");
        assertEquals(newAmpl / Constants.SQRT2, active.getFsRight(), EPS, "legacy store writes both row fields equal (RMS)");
    }

    @Test
    void dacFsVoltageAmpl_isChannelAware() {
        Preferences p = detached();
        p.setDacFsVoltageAmpl(2.0);
        p.setDacFsVoltageAmplRight(3.0);
        assertEquals(2.0, p.getDacFsVoltageAmpl(Channel.L), EPS);
        assertEquals(3.0, p.getDacFsVoltageAmpl(Channel.R), EPS);
        assertEquals(2.0, p.getDacFsVoltageAmpl(), EPS, "channel-less getter is the LEFT / legacy value");
    }
}
