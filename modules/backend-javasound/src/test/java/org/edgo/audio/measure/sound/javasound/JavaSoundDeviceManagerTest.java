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

    // ------------------------------------------------------------ the built name

    @Test
    void theBuiltNameIsTheCardAndThePort() {
        // The address is consumed to build the name and is not in it: C named
        // the card, D named the port, and neither the short id nor
        // [plughw:...] tells an operator anything they can act on.
        assertEquals("SMSL USB AUDIO - Headphone Out",
                manager.deviceName("AUDIO [plughw:0,0]", "Headphone Out", "SMSL USB AUDIO"));
        // A port ALSA could name outright needs no cleaning.
        assertEquals("CUBILUX CB5 - Line In",
                manager.deviceName("CB5 [plughw:2,1]", "Line In", "CUBILUX CB5"));
    }

    @Test
    void theProvidersBoilerplateAndTheRepeatedCardNameAreTakenOff() {
        // "Direct Audio Device: HDA Intel PCH, ALC262 Analog" is the card's own
        // name in front of the one part that says which device this is.
        assertEquals("HDA Intel PCH - ALC262 Analog",
                manager.deviceName("PCH [plughw:0,0]",
                        "Direct Audio Device: HDA Intel PCH, ALC262 Analog", "HDA Intel PCH"));
        assertEquals("HDA Intel PCH - ALC262 Alt Analog",
                manager.deviceName("PCH [plughw:0,2]",
                        "Direct Audio Device: HDA Intel PCH, ALC262 Alt Analog", "HDA Intel PCH"));
        assertEquals("HDA NVidia - HDMI 0",
                manager.deviceName("NVidia [plughw:1,3]",
                        "Direct Audio Device: HDA NVidia, HDMI 0", "HDA NVidia"));
    }

    @Test
    void theSystemDefaultEntryNamesNoCard() {
        // "PCH [default]" named a card the selection does not actually pin: the
        // entry follows whatever the system is set to.
        assertEquals("System default",
                manager.deviceName("PCH [default]",
                        "Direct Audio Device: HDA Intel PCH, ALC262 Analog", "HDA Intel PCH"));
    }

    @Test
    void theBuiltNameIsAlsoTheIdentity() {
        // One source of truth: the ref receives the FINISHED name and carries
        // it as name and identity alike; the raw mixer name lives only in the
        // Mixer.Info reference beside it.
        JavaSoundDeviceManager.JavaSoundDeviceRef d =
                new JavaSoundDeviceManager.JavaSoundDeviceRef(
                        0, "SMSL USB AUDIO - Headphone Out", "", "ALSA", true, false, null);
        assertEquals("SMSL USB AUDIO - Headphone Out", d.name());
        assertEquals("SMSL USB AUDIO - Headphone Out", d.identity());
        assertEquals("[0] SMSL USB AUDIO - Headphone Out - ALSA", d.displayName(),
                "a name that already says everything is not echoed in brackets");
    }

    @Test
    void withoutACardNameTheNameIsExactlyWhatItWasBefore() {
        // Every non-ALSA host: no cards file, so no card name, and the listing
        // keeps the rendering Windows and macOS have always shown.
        assertEquals("AUDIO [plughw:0,0]",
                manager.deviceName("AUDIO [plughw:0,0]", "Headphone Out", ""));
        assertEquals("AUDIO [plughw:0,0]",
                manager.deviceName("AUDIO [plughw:0,0]", "Headphone Out", null));
        JavaSoundDeviceManager.JavaSoundDeviceRef d =
                new JavaSoundDeviceManager.JavaSoundDeviceRef(
                        0, "Speakers (Realtek)", "Headphone Out", "ALSA", true, false, null);
        assertEquals("[0] Speakers (Realtek) (Headphone Out) - ALSA", d.displayName());
    }
}
