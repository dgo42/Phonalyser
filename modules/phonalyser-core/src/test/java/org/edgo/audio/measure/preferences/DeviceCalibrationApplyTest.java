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

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.edgo.audio.measure.common.Constants;
import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.DeviceChannelMode;
import org.edgo.audio.measure.sound.DeviceCalibration;
import org.edgo.audio.measure.sound.DeviceRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which machine's calibration wins for a device: a calibration belongs to, and
 * is stored on, the machine the device is connected to.
 *
 * <p>The case that has to be pinned is the COLLISION.  A device on a Phonalyser
 * server arrives carrying the full scales that server stores for it (net spec
 * 4.3's {@code cal}), and this machine's own card store may well hold a card
 * whose {@code match} list recognises the same name - a second QA403, or simply
 * the card left behind by the analyzer that used to be plugged in here.  Resolved
 * by name, that local card would silently mis-scale every measurement taken on a
 * bench across the room, and nothing on screen would say so.
 */
class DeviceCalibrationApplyTest {

    private static final double EPS = 1e-9;
    private static final String DEVICE_NAME = "QA403";
    private static final String LOCAL_CARD = "Local QA403";
    private static final String RANGE_LABEL = "default";
    /** What THIS machine's card says the device's full scales are. */
    private static final double LOCAL_FS_LEFT = 1.0;
    private static final double LOCAL_FS_RIGHT = 1.1;
    /** What the bench that actually owns the device says they are - nowhere near
     *  the local card's, so a resolution that took the wrong one is unmistakable. */
    private static final double WIRE_FS_LEFT = 7.5;
    private static final double WIRE_FS_RIGHT = 9.5;
    /** What the global scalar holds before an uncalibrated card fails to move
     *  it - the legacy value an unbound device leaves standing. */
    private static final double LEGACY_FS = 3.25;
    /** How long the torn-read hunt keeps reading against a writer.  Long enough
     *  that an unsynchronized read is caught in the act (it tears within a few
     *  thousand iterations), short enough to stay a unit test. */
    private static final long TORN_READ_BUDGET_NANOS = 400_000_000L;

    @Test
    void aDeviceThatCarriesItsOwnCalibrationBeatsANameMatchingLocalCard() {
        Preferences prefs = detached();
        prefs.putAudioDeviceProfile(card(true));

        prefs.applyDeviceProfile(
                onABench(true, new DeviceCalibration(WIRE_FS_LEFT, WIRE_FS_RIGHT)), true);

        assertEquals(WIRE_FS_LEFT, prefs.getAdcFsVoltageRms(Channel.L), EPS,
                "calibration lives where the device is connected: the values the "
                        + "device carried must win over a local card that merely "
                        + "recognises its name");
        assertEquals(WIRE_FS_RIGHT, prefs.getAdcFsVoltageRms(Channel.R), EPS);
    }

    @Test
    void aLocalDeviceCarriesNoneAndFallsBackToTheCardStore() {
        Preferences prefs = detached();
        prefs.putAudioDeviceProfile(card(true));

        prefs.applyDeviceProfile(local(true), true);

        assertEquals(LOCAL_FS_LEFT, prefs.getAdcFsVoltageRms(Channel.L), EPS,
                "a device on this machine answers null, and the name lookup is what "
                        + "it always was");
        assertEquals(LOCAL_FS_RIGHT, prefs.getAdcFsVoltageRms(Channel.R), EPS);
    }

    @Test
    void theOutputDirectionAppliesTheSamePairInItsPeakForm() {
        Preferences prefs = detached();
        prefs.putAudioDeviceProfile(card(false));

        prefs.applyDeviceProfile(
                onABench(false, new DeviceCalibration(WIRE_FS_LEFT, WIRE_FS_RIGHT)), false);

        assertEquals(WIRE_FS_LEFT * Constants.SQRT2, prefs.getDacFsVoltageAmpl(Channel.L),
                EPS, "the wire carries RMS, the generator is driven at the peak form");
        assertEquals(WIRE_FS_RIGHT * Constants.SQRT2,
                prefs.getDacFsVoltageAmpl(Channel.R), EPS);
        assertEquals(WIRE_FS_LEFT / WIRE_FS_RIGHT, prefs.dacRightLaneScale(), EPS,
                "and the per-lane scale a remote generator is sent follows from the "
                        + "same pair, so both lanes emit the same physical level");
    }

    @Test
    void aLocalOutputDeviceStillResolvesItsCard() {
        Preferences prefs = detached();
        prefs.putAudioDeviceProfile(card(false));

        prefs.applyDeviceProfile(local(false), false);

        assertEquals(LOCAL_FS_LEFT * Constants.SQRT2, prefs.getDacFsVoltageAmpl(Channel.L),
                EPS);
        assertEquals(LOCAL_FS_RIGHT * Constants.SQRT2,
                prefs.getDacFsVoltageAmpl(Channel.R), EPS);
    }

    /**
     * A range row that was never calibrated carries zeros, and a full scale is
     * the divisor every level is computed against - so the ONE resolution answers
     * "no calibration", the same {@code null} an unbound name gets.
     *
     * <p>It matters at the single point rather than at each caller because they
     * all read it: the apply methods here, the {@code cal} a Phonalyser server
     * publishes per device (net spec 4.3), and the full scale a remote generator
     * lane opens at.  A zero reaching any of them is an infinite reading or a
     * division by zero, and nothing on screen would say which.
     */
    @Test
    void anUncalibratedRowIsNoCalibrationAtAll() {
        Preferences prefs = detached();
        prefs.putAudioDeviceProfile(uncalibratedCard(0.0, 0.0));

        assertNull(prefs.deviceCalibration(DEVICE_NAME, true));
        assertNull(prefs.deviceCalibration(DEVICE_NAME, false),
                "and in either direction - the row is the same one");
    }

    @Test
    void oneUncalibratedChannelIsEnoughToRefuseThePair() {
        Preferences prefs = detached();
        prefs.putAudioDeviceProfile(uncalibratedCard(LOCAL_FS_LEFT, 0.0));

        assertNull(prefs.deviceCalibration(DEVICE_NAME, true),
                "half a calibration would scale one channel and leave the other "
                        + "reading infinity");
    }

    @Test
    void anUncalibratedCardLeavesTheGlobalScalarsWhereTheyWere() {
        Preferences prefs = detached();
        prefs.setAdcFsVoltageRms(LEGACY_FS);
        prefs.setAdcFsVoltageRmsRight(LEGACY_FS);
        prefs.putAudioDeviceProfile(uncalibratedCard(0.0, 0.0));

        prefs.applyDeviceProfile(local(true), true);

        assertEquals(LEGACY_FS, prefs.getAdcFsVoltageRms(Channel.L), EPS,
                "the legacy scalars stand, exactly as they do for an unbound device");
        assertEquals(LEGACY_FS, prefs.getAdcFsVoltageRms(Channel.R), EPS);
    }

    // -------------------------------------------------------------------------
    // "Is this selection calibrated at all?" - the trigger of the Preferences
    // dialog's warning.  Same precedence as the applies above, so
    // the two can never disagree about the selection on screen.
    // -------------------------------------------------------------------------

    @Test
    void aDeviceCarryingItsOwnCalibrationIsCalibrated() {
        Preferences prefs = detached();

        assertFalse(prefs.isUncalibrated(
                onABench(true, new DeviceCalibration(WIRE_FS_LEFT, WIRE_FS_RIGHT)), true),
                "the bench that owns the device sent its full scales - there is "
                        + "nothing to warn about");
    }

    @Test
    void aDeviceWithNeitherWireCalibrationNorCardIsUncalibrated() {
        Preferences prefs = detached();

        assertTrue(prefs.isUncalibrated(local(true), true));
        assertTrue(prefs.isUncalibrated(DEVICE_NAME, true),
                "and the name-only form answers the same for a device this process "
                        + "holds no ref for");
    }

    @Test
    void aLocalCardWithValuesCoversADeviceOfTHISMachine() {
        Preferences prefs = detached();
        prefs.putAudioDeviceProfile(card(true));

        assertFalse(prefs.isUncalibrated(local(true), true));
        assertTrue(prefs.isUncalibrated(local(false), false),
                "the card was calibrated for the INPUT only - the output half of "
                        + "the same selection is still uncalibrated");
    }

    /**
     * A device on a BENCH is calibrated by that bench and by
     * nothing else.  A local card that merely recognises its name is the SOURCE
     * for propagating a calibration up to the server (net spec 4.3
     * {@code cards.put} / {@code device.setCalibration}) - never a silent
     * calibrator down here.
     *
     * <p>The two halves have to move together, and this is why they live in one
     * test: a warning that stayed quiet because of the local card would leave the
     * operator measuring against a full scale nobody on the bench ever measured,
     * and an apply that took the local card would produce exactly those numbers.
     */
    @Test
    void aLocalCardNeverCoversADeviceOnABench() {
        Preferences prefs = detached();
        prefs.setAdcFsVoltageRms(LEGACY_FS);
        prefs.setAdcFsVoltageRmsRight(LEGACY_FS);
        prefs.putAudioDeviceProfile(card(true));

        assertTrue(prefs.isUncalibrated(onABench(true, null), true),
                "the bench has no calibration for it, so it IS uncalibrated - this "
                        + "machine's card of the same name is not an answer for a "
                        + "device across the room");

        prefs.applyDeviceProfile(onABench(true, null), true);

        assertEquals(LEGACY_FS, prefs.getAdcFsVoltageRms(Channel.L), EPS,
                "and nothing of that card reaches the scalars - the runtime "
                        + "fallback stands until the bench itself is calibrated");
        assertEquals(LEGACY_FS, prefs.getAdcFsVoltageRms(Channel.R), EPS);
    }

    @Test
    void aCardWhoseRowWasNeverCalibratedIsUncalibrated() {
        Preferences prefs = detached();
        prefs.putAudioDeviceProfile(uncalibratedCard(0.0, 0.0));

        assertTrue(prefs.isUncalibrated(local(true), true),
                "a bound card with zeros in its row is an uncalibrated device "
                        + "however it got there");
    }

    /** The one exception: a device with builtin calibration, such as a QA40x -
     *  the analyzer's own values are not the operator's to supply, so warning
     *  them about it would be noise they cannot act on. */
    @Test
    void aDeviceProvidedCalibrationIsExemptEvenWithAnEmptyRow() {
        Preferences prefs = detached();
        AudioDeviceProfile analyzer = uncalibratedCard(0.0, 0.0);
        analyzer.getInput().setCalibrationFromDevice(true);
        prefs.putAudioDeviceProfile(analyzer);

        assertFalse(prefs.isUncalibrated(local(true), true));
        assertTrue(prefs.isUncalibrated(local(false), false),
                "the flag is per endpoint - the output half of the same card is "
                        + "not exempt");
    }

    /**
     * The pair is READ under the monitor the calibrate writes hold, so no reader
     * can catch the row between its {@code fsLeft} and {@code fsRight} writes.
     *
     * <p>A half-written pair is the worst kind of wrong value: a Phonalyser server
     * broadcasts this resolution as the {@code cal} of every device it offers, and
     * a new left against an old right is a plausible-looking channel imbalance
     * that no client could detect and no measurement would flag.
     */
    @Test
    void theCalibrationPairIsNeverReadHalfWritten() throws InterruptedException {
        Preferences prefs = detached();
        prefs.putAudioDeviceProfile(card(true));
        AtomicReference<String> torn = new AtomicReference<>();
        AtomicBoolean stop = new AtomicBoolean();
        Thread writer = new Thread(() -> {
            while (!stop.get()) {
                prefs.storeDeviceCalibration(DEVICE_NAME, true, LOCAL_FS_LEFT, LOCAL_FS_RIGHT);
                prefs.storeDeviceCalibration(DEVICE_NAME, true, WIRE_FS_LEFT, WIRE_FS_RIGHT);
            }
        }, "torn-read-writer");
        writer.start();
        long deadline = System.nanoTime() + TORN_READ_BUDGET_NANOS;
        while (torn.get() == null && System.nanoTime() < deadline) {
            DeviceCalibration cal = prefs.deviceCalibration(DEVICE_NAME, true);
            if (cal == null) continue;
            boolean coherent =
                    (cal.fsRmsLeft() == LOCAL_FS_LEFT && cal.fsRmsRight() == LOCAL_FS_RIGHT)
                    || (cal.fsRmsLeft() == WIRE_FS_LEFT && cal.fsRmsRight() == WIRE_FS_RIGHT);
            if (!coherent) {
                torn.set(cal.fsRmsLeft() + " / " + cal.fsRmsRight());
            }
        }
        stop.set(true);
        writer.join();

        assertNull(torn.get(), "the broadcast carried a half-written pair");
    }

    /** A card bound to the device whose ONE row carries the given full scales -
     *  both endpoints, so either direction resolves to it. */
    private AudioDeviceProfile uncalibratedCard(double fsLeft, double fsRight) {
        AudioDeviceProfile profile = new AudioDeviceProfile();
        profile.setName(LOCAL_CARD);
        profile.getMatch().add(DEVICE_NAME);
        for (DeviceEndpointConfig endpoint
                : List.of(profile.getInput(), profile.getOutput())) {
            DeviceRange row = new DeviceRange();
            row.setLabel(RANGE_LABEL);
            row.setFsLeft(fsLeft);
            row.setFsRight(fsRight);
            endpoint.setChannels(DeviceChannelMode.LINKED);
            endpoint.getRanges().add(row);
            endpoint.setActiveRange(RANGE_LABEL);
        }
        return profile;
    }

    /** A detached, transient Preferences with the inherited profile list cleared,
     *  so nothing here touches the live singleton's on-disk store. */
    private Preferences detached() {
        Preferences prefs = Preferences.instance().copyForDialog();
        prefs.setTransientMode(true);
        for (AudioDeviceProfile inherited : prefs.getAudioDeviceProfiles()) {
            prefs.removeAudioDeviceProfile(inherited.getName());
        }
        prefs.setBackend(AudioBackendType.WASAPI);
        return prefs;
    }

    /** THIS machine's card for a device of that name - one LINKED active row. */
    private AudioDeviceProfile card(boolean input) {
        AudioDeviceProfile profile = new AudioDeviceProfile();
        profile.setName(LOCAL_CARD);
        profile.getMatch().add(DEVICE_NAME);
        DeviceRange row = new DeviceRange();
        row.setLabel(RANGE_LABEL);
        row.setFsLeft(LOCAL_FS_LEFT);
        row.setFsRight(LOCAL_FS_RIGHT);
        DeviceEndpointConfig endpoint = input ? profile.getInput() : profile.getOutput();
        endpoint.setChannels(DeviceChannelMode.LINKED);
        endpoint.getRanges().add(row);
        endpoint.setActiveRange(RANGE_LABEL);
        return profile;
    }

    /** A device of THIS machine, carrying no calibration of its own - the case the
     *  card store has always answered. */
    private DeviceRef local(boolean input) {
        return new StubRef(DEVICE_NAME, input, null, false);
    }

    /** The same device on a Phonalyser server, with whatever calibration that
     *  server holds for it (null = none). */
    private DeviceRef onABench(boolean input, DeviceCalibration cal) {
        return new StubRef(DEVICE_NAME, input, cal, true);
    }

    /** A device handle with nothing on it but what the apply seam reads: its name,
     *  its direction, the calibration it did or did not come with, and whether it
     *  is plugged into THIS machine or into a bench. */
    private record StubRef(String name, boolean isInput, DeviceCalibration calibration,
            boolean remote) implements DeviceRef {

        @Override
        public int index() {
            return 0;
        }

        /** A bench device is reached through the dual-level carrier, which is what
         *  {@code DeviceRef.remote()} reads; a local one names its own backend. */
        @Override
        public AudioBackendType carrier() {
            return remote ? AudioBackendType.NET : AudioBackendType.WASAPI;
        }

        @Override
        public String description() {
            return "stub";
        }

        @Override
        public String vendor() {
            return "stub";
        }

        /** What the device IS - a QA403 answers the same either way; only the
         *  CARRIER says where it is plugged in. */
        @Override
        public AudioBackendType backend() {
            return AudioBackendType.QA40X;
        }

        @Override
        public boolean isOutput() {
            return !isInput;
        }
    }
}
