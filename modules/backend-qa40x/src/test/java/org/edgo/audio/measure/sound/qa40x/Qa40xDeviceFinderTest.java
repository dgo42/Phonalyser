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

import java.util.Optional;

import org.edgo.audio.measure.sound.LibUsb;
import org.edgo.audio.measure.sound.qa40x.Qa40xDeviceFinder.Qa40xModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Headless coverage for the finder logic that lives off the JNA boundary: the
 * VID/PID -> model mapping (doc §2), the single-device rule (doc §2/§7), and the
 * graceful behaviour when {@code libusb} is absent.  Real enumeration/open needs
 * hardware and is not exercised.
 */
class Qa40xDeviceFinderTest {

    private final Qa40xDeviceFinder finder = new Qa40xDeviceFinder();

    @Test
    void productId4E37IsQa402() {
        assertEquals(Optional.of(Qa40xModel.QA402), Qa40xModel.fromProductId(0x4E37));
    }

    @Test
    void productId4E39IsQa403() {
        assertEquals(Optional.of(Qa40xModel.QA403), Qa40xModel.fromProductId(0x4E39));
    }

    @Test
    void unknownProductIdIsEmpty() {
        // 0x4E27 is the QA401 - deliberately out of scope, so also unmapped here.
        assertTrue(Qa40xModel.fromProductId(0x4E27).isEmpty());
        assertTrue(Qa40xModel.fromProductId(0x1234).isEmpty());
    }

    @Test
    void requireSingleAcceptsExactlyOne() {
        assertDoesNotThrow(() -> finder.requireSingle(1));
    }

    @Test
    void requireSingleRejectsNone() {
        assertThrows(IllegalStateException.class, () -> finder.requireSingle(0));
    }

    @Test
    void requireSingleRejectsMoreThanOne() {
        assertThrows(IllegalStateException.class, () -> finder.requireSingle(2));
    }

    @Test
    void listIsEmptyWhenLibusbAbsent() {
        assumeFalse(LibUsb.available(), "libusb-1.0 present on host - skipping absence test");
        assertDoesNotThrow(finder::list);
        assertTrue(finder.list().isEmpty(), "with libusb absent, list() must be empty");
    }

    @Test
    void openThrowsWhenLibusbAbsent() {
        assumeFalse(LibUsb.available(), "libusb-1.0 present on host - skipping absence test");
        assertThrows(IllegalStateException.class, finder::open);
    }
}
