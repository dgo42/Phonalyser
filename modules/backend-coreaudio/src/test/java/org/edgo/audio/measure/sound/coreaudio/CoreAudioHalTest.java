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

package org.edgo.audio.measure.sound.coreaudio;

import java.util.List;
import java.util.TreeSet;

import com.sun.jna.Memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two raw-buffer parsers of {@link CoreAudioHal}, driven with fabricated
 * property buffers - the HAL wire layout, testable on a host that has no
 * CoreAudio at all.  Layouts under test: {@code AudioValueRange} (two Float64)
 * and {@code AudioStreamRangedDescription} (40-byte basic description + range).
 */
class CoreAudioHalTest {

    private static final int LPCM = 0x6C70636D;
    private static final int AC3  = 0x61632D33;   // 'ac-3' - a non-PCM format id
    /** The device the volume pin is driven on. */
    private static final String INTERFACE = "Cubilux CB5 Line In";
    /** The elements a volume control can live on: the device's own master, then
     *  the two channels of the stereo lane. */
    private static final int MAIN_CONTROL = 0;
    private static final int LEFT_CONTROL = 1;
    private static final int RIGHT_CONTROL = 2;
    /** The scalar ceiling - which on a capture side is the TOP OF THE GAIN
     *  RANGE, not 0 dB: the bench interface's input reads +12 dB here. */
    private static final float FULL_SCALE = 1.0f;
    /** Where this control's own 0 dB sits on its scalar. */
    private static final float ZERO_DB_SCALAR = 0.63f;
    /** A slider somebody moved - silent scaling on every measurement. */
    private static final float HALF_VOLUME = 0.5f;
    private static final float EPS = 1e-6f;

    private final CoreAudioHal hal = new CoreAudioHal();

    // ------------------------------------------------- nominal rates ('nsr#')

    @Test
    void discreteNominalEntriesAreListedOnce() {
        // What a USB interface publishes: one degenerate range per rate.
        Memory ranges = valueRanges(44100, 44100, 48000, 48000, 96000, 96000);
        assertArrayEquals(new int[] {44100, 48000, 96000},
                hal.discreteRates(ranges, (int) ranges.size()));
    }

    @Test
    void aRangedNominalEntryContributesItsTwoEndpointsOnly() {
        // A continuous-clock device declares 8000..192000; the two endpoints
        // are the driver's own numbers - nothing in between is invented.
        Memory ranges = valueRanges(8000, 192000);
        assertArrayEquals(new int[] {8000, 192000},
                hal.discreteRates(ranges, (int) ranges.size()));
    }

    // ------------------------------------------- physical formats ('pft#')

    @Test
    void discreteLinearPcmFormatsAreReportedAsTheirOwnPairs() {
        Memory formats = rangedDescriptions(
                desc(48000, LPCM, 24, 0, 0),
                desc(48000, LPCM, 16, 0, 0),
                desc(96000, LPCM, 24, 0, 0));
        TreeSet<CoreAudioHal.PhysicalFormat> found = new TreeSet<>();
        hal.addFormats(formats, (int) formats.size(), new int[0], found);
        assertEquals(List.of(
                new CoreAudioHal.PhysicalFormat(48000, 16),
                new CoreAudioHal.PhysicalFormat(48000, 24),
                new CoreAudioHal.PhysicalFormat(96000, 24)),
                List.copyOf(found));
    }

    @Test
    void anAnyRateFormatIsResolvedAgainstTheNominalListWithinItsRange() {
        // kAudioStreamAnyRate: rate 0, the trailing range says 44100..96000.
        // The device's nominal list has five rates; only the three inside the
        // range become pairs.
        Memory formats = rangedDescriptions(desc(0, LPCM, 32, 44100, 96000));
        TreeSet<CoreAudioHal.PhysicalFormat> found = new TreeSet<>();
        hal.addFormats(formats, (int) formats.size(),
                new int[] {8000, 44100, 48000, 96000, 192000}, found);
        assertEquals(List.of(
                new CoreAudioHal.PhysicalFormat(44100, 32),
                new CoreAudioHal.PhysicalFormat(48000, 32),
                new CoreAudioHal.PhysicalFormat(96000, 32)),
                List.copyOf(found));
    }

    @Test
    void nonPcmFormatsAndZeroWidthsAreSkipped() {
        Memory formats = rangedDescriptions(
                desc(48000, AC3, 16, 0, 0),     // compressed - not a capture format
                desc(48000, LPCM, 0, 0, 0));    // no width declared
        TreeSet<CoreAudioHal.PhysicalFormat> found = new TreeSet<>();
        hal.addFormats(formats, (int) formats.size(), new int[0], found);
        assertTrue(found.isEmpty());
    }

    // ------------------------------------------------- the 0 dB volume pin

    @Test
    void aDeviceWithNoVolumeControlIsLeftAloneAndSaysNothing() {
        FakeCoreAudioLib machine = new FakeCoreAudioLib(INTERFACE);

        new CoreAudioHal(machine).pinVolumeToUnity(INTERFACE, false);

        assertEquals(0, machine.volumeWrites(),
                "a professional interface publishes no volume property at all - its "
                        + "gain is fixed in hardware, which is the wanted state, and "
                        + "there is nothing to write");
    }

    @Test
    void aCaptureGainAtTheScalarCeilingIsPinnedDownToTheDevicesZeroDb() {
        // The bench case this spec comes from: the input's scalar 1.0 is the top
        // of its GAIN range (+12 dB), and 0 dB sits lower on the scalar.  A pin
        // that assumed "maximum = unity" would ADD 12 dB to a calibrated chain.
        FakeCoreAudioLib machine = new FakeCoreAudioLib(INTERFACE);
        machine.volumeControl(INTERFACE, false, MAIN_CONTROL, FULL_SCALE);
        machine.zeroDbAt(INTERFACE, false, MAIN_CONTROL, ZERO_DB_SCALAR);

        new CoreAudioHal(machine).pinVolumeToUnity(INTERFACE, false);

        assertEquals(ZERO_DB_SCALAR, machine.volume(INTERFACE, false, MAIN_CONTROL), EPS,
                "unity is the device's own 0 dB from its dB-to-scalar translation, "
                        + "never the scalar ceiling");
        assertEquals(1, machine.volumeWrites());
    }

    @Test
    void aControlWithNoDbTranslationIsPinnedToFullScale() {
        FakeCoreAudioLib machine = new FakeCoreAudioLib(INTERFACE);
        machine.volumeControl(INTERFACE, true, MAIN_CONTROL, HALF_VOLUME);

        new CoreAudioHal(machine).pinVolumeToUnity(INTERFACE, true);

        assertEquals(FULL_SCALE, machine.volume(INTERFACE, true, MAIN_CONTROL), EPS,
                "with no dB scale published there is nothing better to aim for than "
                        + "full scale - where an output's 0 dB conventionally sits");
    }

    @Test
    void aVolumeAlreadyAtTheTargetIsNotWrittenAgain() {
        FakeCoreAudioLib machine = new FakeCoreAudioLib(INTERFACE);
        machine.volumeControl(INTERFACE, false, MAIN_CONTROL, ZERO_DB_SCALAR);
        machine.zeroDbAt(INTERFACE, false, MAIN_CONTROL, ZERO_DB_SCALAR);

        new CoreAudioHal(machine).pinVolumeToUnity(INTERFACE, false);

        assertEquals(0, machine.volumeWrites(),
                "nothing moved, so nothing is written and nothing is logged - the "
                        + "INFO line means the machine was changed");
    }

    @Test
    void aDeviceWithPerChannelControlsHasEveryChannelPinned() {
        FakeCoreAudioLib machine = new FakeCoreAudioLib(INTERFACE);
        machine.volumeControl(INTERFACE, false, LEFT_CONTROL, FULL_SCALE);
        machine.volumeControl(INTERFACE, false, RIGHT_CONTROL, FULL_SCALE);
        machine.zeroDbAt(INTERFACE, false, LEFT_CONTROL, ZERO_DB_SCALAR);
        machine.zeroDbAt(INTERFACE, false, RIGHT_CONTROL, ZERO_DB_SCALAR);

        new CoreAudioHal(machine).pinVolumeToUnity(INTERFACE, false);

        assertEquals(ZERO_DB_SCALAR, machine.volume(INTERFACE, false, LEFT_CONTROL), EPS);
        assertEquals(ZERO_DB_SCALAR, machine.volume(INTERFACE, false, RIGHT_CONTROL), EPS,
                "many devices publish no master control and only per-channel ones - "
                        + "a pin that only asked for the master would leave the "
                        + "measurement scaled");
    }

    @Test
    void onlyTheDirectionBeingOpenedIsPinned() {
        FakeCoreAudioLib machine = new FakeCoreAudioLib(INTERFACE);
        machine.volumeControl(INTERFACE, false, MAIN_CONTROL, HALF_VOLUME);
        machine.volumeControl(INTERFACE, true, MAIN_CONTROL, HALF_VOLUME);
        machine.zeroDbAt(INTERFACE, false, MAIN_CONTROL, ZERO_DB_SCALAR);
        machine.zeroDbAt(INTERFACE, true, MAIN_CONTROL, ZERO_DB_SCALAR);

        new CoreAudioHal(machine).pinVolumeToUnity(INTERFACE, false);

        assertEquals(ZERO_DB_SCALAR, machine.volume(INTERFACE, false, MAIN_CONTROL), EPS);
        assertEquals(HALF_VOLUME, machine.volume(INTERFACE, true, MAIN_CONTROL), EPS,
                "the open claimed one direction of one device, and the state of "
                        + "anything else on the machine is somebody else's");
    }

    @Test
    void aRefusedVolumeWriteIsSurvivedRatherThanThrown() {
        FakeCoreAudioLib machine = new FakeCoreAudioLib(INTERFACE);
        machine.volumeControl(INTERFACE, false, MAIN_CONTROL, HALF_VOLUME);
        machine.zeroDbAt(INTERFACE, false, MAIN_CONTROL, ZERO_DB_SCALAR);
        machine.refuseVolumeWrites();

        new CoreAudioHal(machine).pinVolumeToUnity(INTERFACE, false);

        assertEquals(HALF_VOLUME, machine.volume(INTERFACE, false, MAIN_CONTROL), EPS,
                "the device would not be told - which is a WARN naming the level it "
                        + "stayed at, and an open that goes ahead: a measurement at "
                        + "the wrong level beats no measurement at all");
    }

    @Test
    void aClosedStreamRestoresTheVolumeThePinMoved() {
        FakeCoreAudioLib machine = new FakeCoreAudioLib(INTERFACE);
        machine.volumeControl(INTERFACE, false, MAIN_CONTROL, FULL_SCALE);
        machine.zeroDbAt(INTERFACE, false, MAIN_CONTROL, ZERO_DB_SCALAR);
        CoreAudioHal hal = new CoreAudioHal(machine);

        hal.pinVolumeToUnity(INTERFACE, false);
        hal.restoreVolume(INTERFACE, false);

        assertEquals(FULL_SCALE, machine.volume(INTERFACE, false, MAIN_CONTROL), EPS,
                "the volume belongs to the operator between opens - what the pin moved "
                        + "comes back as the open found it");
        assertEquals(2, machine.volumeWrites());
    }

    @Test
    void aRestoreWithoutAPinWritesNothing() {
        FakeCoreAudioLib machine = new FakeCoreAudioLib(INTERFACE);
        machine.volumeControl(INTERFACE, false, MAIN_CONTROL, ZERO_DB_SCALAR);
        machine.zeroDbAt(INTERFACE, false, MAIN_CONTROL, ZERO_DB_SCALAR);
        CoreAudioHal hal = new CoreAudioHal(machine);

        hal.pinVolumeToUnity(INTERFACE, false);   // already at 0 dB: moved nothing
        hal.restoreVolume(INTERFACE, false);

        assertEquals(0, machine.volumeWrites(),
                "a pin that moved nothing leaves nothing to put back");
    }

    @Test
    void aDeviceGoneAtRestoreIsLeftInPeace() {
        FakeCoreAudioLib machine = new FakeCoreAudioLib(INTERFACE);
        machine.volumeControl(INTERFACE, false, MAIN_CONTROL, FULL_SCALE);
        machine.zeroDbAt(INTERFACE, false, MAIN_CONTROL, ZERO_DB_SCALAR);
        CoreAudioHal hal = new CoreAudioHal(machine);

        hal.pinVolumeToUnity(INTERFACE, false);
        machine.unplug(INTERFACE);
        hal.restoreVolume(INTERFACE, false);

        assertEquals(1, machine.volumeWrites(),
                "an unplugged device has nothing to restore on - the remembered values "
                        + "are dropped without a fault");
    }

    // ------------------------------------------------- playback device presence

    @Test
    void aPluggedDeviceIsStillPresent() {
        FakeCoreAudioLib machine = new FakeCoreAudioLib(INTERFACE);
        assertTrue(new CoreAudioHal(machine).devicePresent(INTERFACE, true));
    }

    @Test
    void anUnpluggedDeviceIsGone() {
        // A real machine keeps its built-in devices, so an unplug leaves the
        // list non-empty - only then is "not found" a verdict rather than a
        // failed read.
        FakeCoreAudioLib machine = new FakeCoreAudioLib(INTERFACE, "Built-in Output");
        machine.unplug(INTERFACE);
        assertFalse(new CoreAudioHal(machine).devicePresent(INTERFACE, true),
                "the live HAL sees the unplug the nominally-active stream never reports - "
                        + "this answer is what ends a tone nobody hears");
    }

    @Test
    void aHalThatCannotAnswerReportsPresent() {
        FakeCoreAudioLib machine = new FakeCoreAudioLib();
        assertTrue(new CoreAudioHal(machine).devicePresent(INTERFACE, true),
                "an empty device list is do-not-know, and do-not-know must never kill "
                        + "a healthy stream");
    }

    @Test
    void anAggregateWithActiveSubDevicesIsPresent() {
        FakeCoreAudioLib machine = new FakeCoreAudioLib("CB5 Aggregate");
        machine.activeSubDevices("CB5 Aggregate", 2);
        assertTrue(new CoreAudioHal(machine).devicePresent("CB5 Aggregate", true));
    }

    @Test
    void anAggregateWhoseMembersAllLeftIsGone() {
        // The aggregate survives its sub-devices at OS level: pull the one card
        // behind it and the NAME stays on the machine while nothing is left to
        // move samples - the empty active-sub-device list is the real verdict.
        FakeCoreAudioLib machine = new FakeCoreAudioLib("CB5 Aggregate");
        machine.activeSubDevices("CB5 Aggregate", 0);
        assertFalse(new CoreAudioHal(machine).devicePresent("CB5 Aggregate", true),
                "an aggregate with an empty active-sub-device list plays a tone "
                        + "nobody hears - it must read as gone");
    }

    @Test
    void anAggregatesVolumesArePinnedThroughItsMembers() {
        // An aggregate publishes no volume controls of its own - the real
        // controls live on the member cards, and a chain measured THROUGH the
        // aggregate is scaled by exactly those.
        FakeCoreAudioLib machine = new FakeCoreAudioLib("CB5 Aggregate", INTERFACE);
        machine.aggregateOf("CB5 Aggregate", INTERFACE);
        machine.volumeControl(INTERFACE, false, MAIN_CONTROL, FULL_SCALE);
        machine.zeroDbAt(INTERFACE, false, MAIN_CONTROL, ZERO_DB_SCALAR);

        new CoreAudioHal(machine).pinVolumeToUnity("CB5 Aggregate", false);

        assertEquals(ZERO_DB_SCALAR, machine.volume(INTERFACE, false, MAIN_CONTROL), EPS,
                "the member's own gain is what sits in the calibrated chain");
    }

    @Test
    void anAggregatesMembersAreRestoredOnClose() {
        FakeCoreAudioLib machine = new FakeCoreAudioLib("CB5 Aggregate", INTERFACE);
        machine.aggregateOf("CB5 Aggregate", INTERFACE);
        machine.volumeControl(INTERFACE, false, MAIN_CONTROL, FULL_SCALE);
        machine.zeroDbAt(INTERFACE, false, MAIN_CONTROL, ZERO_DB_SCALAR);
        CoreAudioHal hal = new CoreAudioHal(machine);

        hal.pinVolumeToUnity("CB5 Aggregate", false);
        hal.restoreVolume("CB5 Aggregate", false);

        assertEquals(FULL_SCALE, machine.volume(INTERFACE, false, MAIN_CONTROL), EPS,
                "what the pin moved on a member comes back as the open found it");
    }

    // --------------------------------------------------------------- fixtures

    /** An {@code AudioValueRange[]} buffer: (min, max) pairs of Float64. */
    private Memory valueRanges(double... minMax) {
        Memory m = new Memory(minMax.length * 8L);
        for (int i = 0; i < minMax.length; i++) {
            m.setDouble(i * 8L, minMax[i]);
        }
        return m;
    }

    /** One 56-byte {@code AudioStreamRangedDescription} as raw fields. */
    private record Desc(double rate, int formatId, int bits, double min, double max) {}

    private Desc desc(double rate, int formatId, int bits, double min, double max) {
        return new Desc(rate, formatId, bits, min, max);
    }

    private Memory rangedDescriptions(Desc... descs) {
        Memory m = new Memory(descs.length * 56L);
        for (int i = 0; i < descs.length; i++) {
            long off = i * 56L;
            m.setDouble(off, descs[i].rate());
            m.setInt(off + 8, descs[i].formatId());
            m.setInt(off + 32, descs[i].bits());
            m.setDouble(off + 40, descs[i].min());
            m.setDouble(off + 48, descs[i].max());
        }
        return m;
    }
}
