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

package org.edgo.audio.measure.net.client;

import java.util.Set;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.sound.DeviceCalibration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What makes two remote device refs the same device.
 *
 * <p>The one property worth a test of its own: a ref carries the server's stored
 * calibration (spec 4.3's {@code cal}), which the bench re-sends with every
 * {@code ev.devices.changed} and which really does change under a running
 * session - a client on the other side of the room calibrating that device is
 * exactly the case this rule exists for.  Held locks and open streams are
 * tracked BY REF across those refreshes, so a value change that moved the
 * identity would orphan the lock on a device this session is measuring on.
 */
class NetDeviceRefTest {

    private static final int INDEX = 1;
    private static final String NAME = "QA403";
    private static final String DESCRIPTION = "QuantAsylum analyzer";
    private static final String VENDOR = "QuantAsylum";
    private static final String REMOTE_BACKEND = "QA40X";

    @Test
    void twoRefsForTheSameDeviceAreEqualHoweverItIsCalibrated() {
        NetDeviceRef uncalibrated = ref(null);
        NetDeviceRef calibrated = ref(new DeviceCalibration(1.234, 2.345));
        NetDeviceRef recalibrated = ref(new DeviceCalibration(9.0, 9.0));

        assertEquals(uncalibrated, calibrated);
        assertEquals(calibrated, recalibrated);
        assertEquals(uncalibrated.hashCode(), calibrated.hashCode(),
                "equal refs must hash alike, or the lock register loses them");
        assertEquals(calibrated.hashCode(), recalibrated.hashCode());
        assertTrue(Set.of(uncalibrated).contains(recalibrated),
                "which is the whole point: the register is a hash set of refs, and a "
                        + "release after a calibration write has to find its entry");
    }

    @Test
    void aRefIsStillTheDeviceItNames() {
        assertNotEquals(ref(null), new NetDeviceRef(INDEX + 1, NAME, DESCRIPTION, VENDOR,
                true, REMOTE_BACKEND, null, null, false), "another index is another device");
        assertNotEquals(ref(null), new NetDeviceRef(INDEX, NAME, DESCRIPTION, VENDOR,
                false, REMOTE_BACKEND, null, null, false), "and so is the other direction");
        assertNotEquals(ref(null), new NetDeviceRef(INDEX, "QA402", DESCRIPTION, VENDOR,
                true, REMOTE_BACKEND, null, null, false));
        assertNotEquals(ref(null), new NetDeviceRef(INDEX, NAME, DESCRIPTION, VENDOR,
                true, "JAVASOUND", null, null, false));
    }

    @Test
    void aRemoteDeviceKnowsBothItsLevels() {
        NetDeviceRef ref = ref(null);

        // The dual-level rule: what it IS is the TRUE type - a QA403 on a bench
        // is a QA40X wherever it hangs, and that is what type-specific control
        // keys on...
        assertEquals(AudioBackendType.QA40X, ref.backend());
        // ...while how it is REACHED is the carrier - the answer that routes
        // every manager dispatch at the net manager, never at the LOCAL driver
        // of the same name (a bench across the room is not this machine's USB).
        assertEquals(AudioBackendType.NET, ref.carrier());
        assertEquals(REMOTE_BACKEND, ref.remoteBackend());
        assertNull(ref.calibration(),
                "a bench with no card for the device answers null, and the client "
                        + "falls back to its own defaults");
    }

    @Test
    void aBackendThisBuildCannotNameIsStillReachableJustNotIdentifiable() {
        NetDeviceRef foreign = new NetDeviceRef(INDEX, NAME, DESCRIPTION, VENDOR,
                true, "HAL9000", null, null, false);

        assertEquals(AudioBackendType.NET, foreign.backend(),
                "a server may serve a backend this build has no constant for - "
                        + "the honest identity is then the carrier itself");
        assertEquals(AudioBackendType.NET, foreign.carrier(),
                "and it still routes at the net manager like every remote device");
    }

    /** The card binding travels with the ref exactly as the calibration does, and
     *  is just as absent from its identity - a {@code device.setCard} re-sends the
     *  whole catalogue, and a lock held across that refresh must survive it. */
    @Test
    void twoRefsForTheSameDeviceAreEqualWhateverCardItIsBoundTo() {
        NetDeviceRef unbound = ref(null);
        NetDeviceRef bound = new NetDeviceRef(INDEX, NAME, DESCRIPTION, VENDOR, true,
                REMOTE_BACKEND, null, "QA403", false);
        NetDeviceRef rebound = new NetDeviceRef(INDEX, NAME, DESCRIPTION, VENDOR, true,
                REMOTE_BACKEND, null, "QA402", false);

        assertEquals(unbound, bound);
        assertEquals(bound, rebound);
        assertEquals(unbound.hashCode(), rebound.hashCode());
        assertEquals("QA402", rebound.boundCard());
        assertNull(unbound.boundCard(), "nothing chosen on the bench");
    }

    /** {@code calFromDevice} is the third thing a refresh may flip and the third
     *  that is not identity: an analyzer that starts answering "these values are
     *  mine" is the same analyzer, and the lock this session holds on it must
     *  survive the payload that says so. */
    @Test
    void twoRefsForTheSameDeviceAreEqualWhoeverOwnsItsFullScales() {
        NetDeviceRef ordinary = ref(null);
        NetDeviceRef deviceOwned = new NetDeviceRef(INDEX, NAME, DESCRIPTION, VENDOR, true,
                REMOTE_BACKEND, null, null, true);

        assertEquals(ordinary, deviceOwned);
        assertEquals(ordinary.hashCode(), deviceOwned.hashCode());
        assertTrue(deviceOwned.calFromDevice(),
                "and the flag is still readable - it is what makes the client show "
                        + "the analyzer's own numbers read-only");
        assertFalse(ordinary.calFromDevice(),
                "a device the bench calibrates the ordinary way stays editable");
    }

    private NetDeviceRef ref(DeviceCalibration calibration) {
        return new NetDeviceRef(INDEX, NAME, DESCRIPTION, VENDOR, true, REMOTE_BACKEND,
                calibration, null, false);
    }
}
