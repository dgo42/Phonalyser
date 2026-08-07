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

package org.edgo.audio.measure.sound.javasound;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link AlsaPorts} over the whole chain - a JavaSound mixer name, the
 * {@code /proc} stream files, the card's USB descriptors and the kernel's jack
 * controls - on a host that has none of them.
 *
 * <p>The three fixtures describe ONE machine, the bench this work was built
 * for: card 0 an HD-Audio codec, card 1 a USB interface whose PCM device 0 is
 * the microphone and headphone pair and whose PCM device 1 is the line pair,
 * card 2 a legacy PCI card. Only the line pair has plugs in it.
 */
class AlsaPortsTest {

    private static final Path PROC = Paths.get("src", "test", "resources", "proc-asound");

    /** The mixer names the JDK's ALSA provider builds for that machine. */
    private static final String LINE_DEVICE = "CB5 [plughw:1,1]";
    private static final String MIC_DEVICE = "CB5 [plughw:1,0]";
    private static final String HDA_DEVICE = "ALC262 Analog [plughw:0,0]";
    private static final String LEGACY_DEVICE = "ES1371 [plughw:2,0]";

    /** The device tree this machine's sysfs is built in. */
    @TempDir
    Path sysfs;

    private final ProcAsound proc = new ProcAsound(PROC);

    /** Where the card's directory sits - {@code /sys/class/sound} on the bench. */
    private Path sysRoot;

    private AlsaPorts ports;

    /**
     * Builds the sysfs shape the kernel publishes: the card's directory hangs
     * several levels BELOW the USB device that carries it, and the descriptor
     * blob sits at the device. Finding it is a walk up, which is exactly what a
     * card that is not on USB must fail at.
     */
    @BeforeEach
    void bench() throws IOException {
        Path device = Files.createDirectories(sysfs.resolve("usb1").resolve("1-2"));
        Files.write(device.resolve("descriptors"), new UsbDescriptorFixture().benchBlob());
        // The interface directory below it - named "1-2:1.0" on the bench, which
        // is not a name this host can create, and the walk cares about the DEPTH.
        sysRoot = device.resolve("interface-0").resolve("sound");
        Files.createDirectories(sysRoot.resolve("card1"));
        ports = new AlsaPorts(proc, sysRoot, new FixtureJacks(1, AlsaJacksTest.CARD1_CONTENTS));
    }

    // ---------------------------------------------------------------- the port

    @Test
    void namesEachPcmDeviceAfterTheSocketItIsWiredTo() {
        // What the operator sees instead of "USB Audio #0" and "USB Audio #1".
        assertEquals("Line In", ports.port(LINE_DEVICE, true).label());
        assertEquals("Line Out", ports.port(LINE_DEVICE, false).label());
        assertEquals("Mic In", ports.port(MIC_DEVICE, true).label());
        assertEquals("Headphone Out", ports.port(MIC_DEVICE, false).label());
    }

    @Test
    void aDirectionIsNamedByItsOwnStream() {
        // One PCM device, two different sockets: the capture side is wired to
        // the microphone and the playback side to the headphone socket, and the
        // stream file names a different USB interface for each.
        assertEquals("Mic In", ports.port(MIC_DEVICE, true).label());
        assertEquals("Headphone Out", ports.port(MIC_DEVICE, false).label());
    }

    // --------------------------------------------------------------- the plugs

    @Test
    void reportsWhichSocketsHaveAPlugInThem() {
        assertTrue(ports.port(LINE_DEVICE, true).connected());
        assertTrue(ports.port(LINE_DEVICE, false).connected());
        assertFalse(ports.port(MIC_DEVICE, true).connected());
        assertFalse(ports.port(MIC_DEVICE, false).connected());
    }

    @Test
    void onlyAnEmptySocketIsEmpty() {
        assertFalse(ports.port(LINE_DEVICE, true).empty(), "a plug is in it");
        assertTrue(ports.port(MIC_DEVICE, true).empty());
    }

    @Test
    void aCardThatCannotSenseItsSocketsHidesNothing() {
        // No alsa-utils, or a card with no connector controls at all: the port
        // is still named, and it must NOT read as empty - that is the whole
        // difference between "cannot say" and "nothing plugged in".
        AlsaPorts blind = new AlsaPorts(proc, sysRoot, new FixtureJacks(9,
                AlsaJacksTest.CARD1_CONTENTS));
        AlsaPorts.Port port = blind.port(MIC_DEVICE, true);
        assertEquals("Mic In", port.label());
        assertNull(port.connected());
        assertFalse(port.empty(), "a device must never be hidden on a reading nobody took");
    }

    // ------------------------------------------------------------- the unknown

    @Test
    void aCardThatIsNotUsbHasNoPortToName() {
        assertNull(ports.port(HDA_DEVICE, true), "an HD-Audio codec publishes no stream file");
        assertNull(ports.port(LEGACY_DEVICE, true), "and a legacy PCI card neither");
    }

    @Test
    void aMixerWithNoAlsaAddressIsNotAskedAboutAtAll() {
        assertNull(ports.port("Primary Sound Driver", true));
        assertNull(ports.port("Port CB5 [hw:1]", true), "a card, but no PCM device");
        assertNull(ports.port(null, true));
    }

    @Test
    void aPcmDeviceTheCardDoesNotHaveHasNoPort() {
        assertNull(ports.port("CB5 [plughw:1,7]", true));
    }

    @Test
    void aCardWithoutDescriptorsIsNamedByNothing() throws IOException {
        // Same /proc tree, but the USB device's blob is not readable - a
        // container, or a kernel that does not publish it.
        Path bare = Files.createDirectories(sysfs.resolve("bare"));
        Files.createDirectories(bare.resolve("card1"));
        assertNull(new AlsaPorts(proc, bare, new FixtureJacks(1, AlsaJacksTest.CARD1_CONTENTS))
                .port(LINE_DEVICE, true));
    }

    @Test
    void refreshingIsWhatMakesAPulledCableShowUp() {
        FixtureJacks jacks = new FixtureJacks(1, AlsaJacksTest.CARD1_CONTENTS);
        AlsaPorts fresh = new AlsaPorts(proc, sysRoot, jacks);
        fresh.port(LINE_DEVICE, true);
        fresh.port(LINE_DEVICE, false);
        assertEquals(1, jacks.reads(), "one reading serves the whole enumeration");
        fresh.refresh();
        fresh.port(LINE_DEVICE, true);
        assertEquals(2, jacks.reads(), "and the next enumeration takes a new one");
    }
}
