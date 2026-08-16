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

package org.edgo.audio.measure.enums;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The backend enum as a pure MODEL type: {@code isAvailable()} answers OS
 * policy only.  Whether the QA40x's {@code libusb-1.0} binding actually loads
 * is the driver module's answer, given through the provider SPI - this module
 * cannot even reach the binding, which is the layering under test.
 * {@code fromString} accepts the {@code qa40x|qa402|qa403} aliases.
 */
class AudioBackendTypeTest {

    @Test
    void qa40xIsNotOsGated() {
        // The model answers "fits any OS"; the hardware probe (does libusb
        // load?) belongs to the driver's provider, not to this enum.
        assertTrue(AudioBackendType.QA40X.isAvailable());
    }

    @Test
    void netIsNeverALocalChoice() {
        assertFalse(AudioBackendType.NET.isAvailable(),
                "a remote bench has no local hardware to be available");
    }

    @Test
    void displayNameIsQa40x() {
        assertEquals("QA40x", AudioBackendType.QA40X.getDisplayName());
    }

    @Test
    void fromStringAcceptsQa40xAliases() {
        // Deterministic on every machine now that the enum's gate is OS policy
        // alone: the aliases parse, and whether the analyzer can actually be
        // driven is discovered where the hardware is, not at argument parsing.
        for (String alias : new String[] {"qa40x", "qa402", "qa403", "QA403"}) {
            assertEquals(AudioBackendType.QA40X, AudioBackendType.fromString(alias));
        }
    }

    @Test
    void fromStringDoesNotPretendNetIsALocalBackend() {
        // AudioBackendType.NET is the carrier for a bench reached over the network: it owns no
        // local hardware, so --backend can never select it.  A token that parsed
        // and then failed the availability gate would answer "not available on
        // Windows", which is not what is wrong with it.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AudioBackendType.fromString("net"));
        assertTrue(ex.getMessage().contains("Unknown --backend"), ex.getMessage());
    }

    @Test
    void fromStringStillRejectsUnknownTokens() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AudioBackendType.fromString("nonsense"));
        assertTrue(ex.getMessage().contains("Unknown --backend"));
        assertTrue(ex.getMessage().contains("qa40x"), "usage line advertises the new backend");
    }
}
