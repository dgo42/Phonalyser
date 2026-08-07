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

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hot-plug question this backend exists to answer for a headless server:
 * <b>has the machine's audio hardware moved since PortAudio enumerated it?</b>
 *
 * <p>PortAudio enumerates ONCE, inside {@code Pa_Initialize}, and never rescans -
 * so a device pulled out stays in its list, a device plugged back in never enters
 * it, and every open on that device fails until the library is re-initialised.
 * Nothing in PortAudio will say so; the CoreAudio HAL, read fresh on every call,
 * will.  These tests drive that comparison with fabricated property buffers, on
 * any OS: no framework, no PortAudio, no bench.
 */
class CoreAudioDeviceManagerTest {

    private static final String LINE_IN = "Cubilux CB5 Line In";
    private static final String LINE_OUT = "Cubilux CB5 Line Out";
    private static final String BUILT_IN = "MacBook Pro Speakers";
    private static final String ARRIVED = "USB Audio CODEC";

    /** The machine as it stood when the snapshot was taken - the manager reads it
     *  at construction, which is where a real one is built: lazily, immediately
     *  before the first enumeration. */
    private final FakeCoreAudioLib machine = new FakeCoreAudioLib(BUILT_IN, LINE_IN, LINE_OUT);
    private final CoreAudioDeviceManager manager =
            new CoreAudioDeviceManager(new CoreAudioHal(machine));

    @Test
    void theHalNamesEveryDeviceTheMachineCurrentlyHasWithItsObjectId() {
        assertEquals(Set.of("1 " + BUILT_IN, "2 " + LINE_IN, "3 " + LINE_OUT),
                new CoreAudioHal(machine).deviceIdentities(),
                "the id travels with the name because a replugged device keeps the "
                        + "name and gets a new audio object");
    }

    @Test
    void aMachineThatHasNotMovedIsNotStale() {
        assertFalse(manager.deviceListStale(),
                "a rebuild re-initialises the native library and refuses while any "
                        + "stream is open - a poll that asked for one every tick on "
                        + "a bench where nothing moved would be a hazard, not a "
                        + "hot-plug look");
        assertFalse(manager.deviceListStale(), "and asking again changes nothing");
    }

    @Test
    void aDeviceThatLeftTheMachineMakesTheListStale() {
        machine.unplug(LINE_IN);
        machine.unplug(LINE_OUT);

        assertTrue(manager.deviceListStale(),
                "the interface is gone and PortAudio's list still offers it - which "
                        + "is exactly what left the bench server telling its clients "
                        + "nothing at all across an unplug");
    }

    @Test
    void aDeviceThatArrivedMakesTheListStale() {
        machine.plug(ARRIVED);

        assertTrue(manager.deviceListStale(),
                "a device plugged in after start-up can never enter a snapshot "
                        + "enumeration, so the arrival has to be noticed from the "
                        + "HAL side or the device is unreachable for the process's "
                        + "whole life");
    }

    @Test
    void aDeviceUnpluggedAndPluggedBackInBetweenTwoLooksIsStaleToo() {
        machine.unplug(LINE_IN);
        machine.plug(LINE_IN);   // the same name, a NEW audio object

        assertTrue(manager.deviceListStale(),
                "the machine's device NAMES are exactly what they were, and "
                        + "PortAudio's entry is dead all the same - which is why "
                        + "the object id is compared and not the name");
    }

    @Test
    void aHalThatAnswersNothingIsNotReadAsAMachineWithoutDevices() {
        machine.unplug(BUILT_IN);
        machine.unplug(LINE_IN);
        machine.unplug(LINE_OUT);

        assertFalse(manager.deviceListStale(),
                "empty is 'cannot answer' - a host without the framework and a "
                        + "failed property read look the same, and terminating "
                        + "PortAudio because a read failed would take every open "
                        + "stream with it");
    }

}
