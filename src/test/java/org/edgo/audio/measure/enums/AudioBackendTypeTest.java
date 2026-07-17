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

package org.edgo.audio.measure.enums;

import org.junit.jupiter.api.Test;

import org.edgo.audio.measure.sound.LibUsb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The QA40x backend enum: its availability is gated purely on the
 * {@code libusb-1.0} binding loading (not the host OS), and {@code fromString}
 * accepts the {@code qa40x|qa402|qa403} aliases.  The parse assertions are
 * written to be deterministic whether or not the native library is present on
 * the test machine.
 */
class AudioBackendTypeTest {

    @Test
    void qa40xAvailabilityTracksLibUsb() {
        // Availability is the binding probe, independent of the OS gate the
        // sound-card backends use.
        assertEquals(LibUsb.available(), AudioBackendType.QA40X.isAvailable());
    }

    @Test
    void displayNameIsQa40x() {
        assertEquals("QA40x", AudioBackendType.QA40X.getDisplayName());
    }

    @Test
    void fromStringAcceptsQa40xAliases() {
        // The token must be RECOGNISED (parsed to QA40X) regardless of the
        // environment; the availability gate then decides success vs. a clear
        // "not available" error — never the "Unknown --backend" path.
        for (String alias : new String[] {"qa40x", "qa402", "qa403", "QA403"}) {
            if (LibUsb.available()) {
                assertEquals(AudioBackendType.QA40X, AudioBackendType.fromString(alias));
            } else {
                IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                        () -> AudioBackendType.fromString(alias));
                assertTrue(ex.getMessage().contains("not available"),
                        "recognised alias gated by availability, not rejected as unknown: " + ex.getMessage());
            }
        }
    }

    @Test
    void fromStringStillRejectsUnknownTokens() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AudioBackendType.fromString("nonsense"));
        assertTrue(ex.getMessage().contains("Unknown --backend"));
        assertTrue(ex.getMessage().contains("qa40x"), "usage line advertises the new backend");
    }
}
