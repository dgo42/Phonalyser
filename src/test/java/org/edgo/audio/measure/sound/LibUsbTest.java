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

package org.edgo.audio.measure.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Headless coverage for the parts of the {@link LibUsb} binding that live off the
 * JNA boundary: the per-OS library-name resolution, and the graceful-absence
 * probe.  The native calls themselves need real hardware and are not exercised.
 */
class LibUsbTest {

    @Test
    void windows64TriesArchSuffixThenPlainName() {
        // The portaudio_x64/portaudio_x86 convention: both arch DLLs coexist in
        // lib/windows/, the plain upstream name stays as a fall-back.
        assertArrayEquals(new String[] { "libusb-1.0_x64", "libusb-1.0" },
                LibUsb.candidateLibraryNames("Windows 11", "amd64"));
    }

    @Test
    void windows32TriesX86SuffixThenPlainName() {
        // The legacy 32-bit fat jar (Azul Zulu win_i686 reports "x86").
        assertArrayEquals(new String[] { "libusb-1.0_x86", "libusb-1.0" },
                LibUsb.candidateLibraryNames("Windows 10", "x86"));
        assertArrayEquals(new String[] { "libusb-1.0_x86", "libusb-1.0" },
                LibUsb.candidateLibraryNames("Windows 10", "i686"));
    }

    @Test
    void macResolvesToUnixCoreName() {
        // JNA maps "usb-1.0" → libusb-1.0.dylib on macOS; arch is irrelevant
        // (the per-arch lib/macos-* directory picks the file).
        assertArrayEquals(new String[] { "usb-1.0" },
                LibUsb.candidateLibraryNames("Mac OS X", "aarch64"));
    }

    @Test
    void linuxTriesCoreNameThenVersionedSoname() {
        // "usb-1.0" → libusb-1.0.so (dev symlink), then the versioned SONAME.
        assertArrayEquals(new String[] { "usb-1.0", "libusb-1.0.so.0" },
                LibUsb.candidateLibraryNames("Linux", "amd64"));
    }

    @Test
    void unknownOrNullOsFallsBackToUnixNames() {
        assertArrayEquals(new String[] { "usb-1.0", "libusb-1.0.so.0" },
                LibUsb.candidateLibraryNames(null, null));
        assertArrayEquals(new String[] { "usb-1.0", "libusb-1.0.so.0" },
                LibUsb.candidateLibraryNames("SomeFutureOS", "riscv64"));
    }

    @Test
    void availableProbeNeverThrows() {
        // Whether or not libusb-1.0 is installed on the test host, probing for it
        // must return a boolean rather than erupt with an UnsatisfiedLinkError.
        assertDoesNotThrow(LibUsb::available);
    }
}
