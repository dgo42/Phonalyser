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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The two host-API strings this backend cleans up before it publishes a device:
 * the vendor and the description. Both go straight into the label an operator
 * reads in the device combo, and both are written by the provider, not by the
 * device.
 */
class JavaSoundDeviceManagerTest {

    private final JavaSoundDeviceManager manager = new JavaSoundDeviceManager();

    // -------------------------------------------------------------- the vendor

    @Test
    void aVendorKeepsItsNameAndLosesItsAddress() {
        // The two shapes the providers emit: a bare name, and a name with the
        // project URL after it - the same URL on every device of the machine.
        assertEquals("ALSA", manager.plainVendor("ALSA (http://www.alsa-project.org)"));
        assertEquals("Unknown Vendor", manager.plainVendor("Unknown Vendor"));
    }

    @Test
    void aVendorIsNeverCutDownToNothing() {
        assertEquals("(http://example.org)", manager.plainVendor("(http://example.org)"),
                "a bracketed address is still more than an empty field");
        assertEquals("", manager.plainVendor(null));
    }

    // --------------------------------------------------------- the description

    @Test
    void whatTheNameAlreadySaysIsNotSaidAgain() {
        // The provider builds the description from the card's long name, the
        // PCM id and the PCM name - and the mixer is listed under the last of
        // those already.
        assertEquals("Direct Audio Device: HDA Intel PCH at 0xf7f10000 irq 33",
                manager.distinct("Direct Audio Device: HDA Intel PCH at 0xf7f10000 irq 33, "
                        + "ALC262 Analog, ALC262 Analog", "ALC262 Analog [plughw:0,0]"));
    }

    @Test
    void wherePartsOverlapTheOneThatSaysMoreStays() {
        assertEquals("Direct Audio Device: CUBILUX, USB Audio #1",
                manager.distinct("Direct Audio Device: CUBILUX, USB Audio, USB Audio #1",
                        "CB5 [plughw:1,1]"),
                "the part with the device number in it is the one that tells two "
                        + "PCM devices of one card apart");
    }

    @Test
    void aDescriptionWithNothingToDropIsUntouched() {
        // The Windows listing, which shares one description across devices and
        // must keep reading exactly as it did.
        assertEquals("Direct Audio Device: DirectSound Capture",
                manager.distinct("Direct Audio Device: DirectSound Capture",
                        "Line (2- USB Audio CODEC)"));
    }

    @Test
    void aDescriptionThatOnlyRepeatsTheNameIsKeptWhole() {
        // Emptying it would print an empty pair of brackets in the label, which
        // reads worse than the repetition does.
        assertEquals("CB5", manager.distinct("CB5", "CB5 [plughw:1,1]"));
        assertEquals("", manager.distinct(null, "CB5 [plughw:1,1]"));
    }
}
