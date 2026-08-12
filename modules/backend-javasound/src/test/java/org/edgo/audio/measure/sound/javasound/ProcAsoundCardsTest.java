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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading card PRODUCT names out of {@code /proc/asound/cards}.
 *
 * <p>The name ALSA puts in a JavaSound mixer name is the bracketed SHORT id -
 * an identifier, padded to 15 characters, that says nothing about which box is
 * on the bench: two unrelated interfaces can both be called {@code AUDIO}. The
 * cards file is where the kernel keeps the string the device actually reported,
 * and this is the parser for it.
 *
 * <p>Driven against two real machines' files. The drivers matter as much as the
 * names: {@code HDA-Intel} carries a hyphen of its own, so a parser that took
 * "everything up to the first dash" as the driver would read those lines wrong.
 */
class ProcAsoundCardsTest {

    /** A USB interface beside a PCI card - the reported case. */
    private static final Path SMSL =
            Paths.get("src", "test", "resources", "proc-asound-smsl");
    /** Onboard HD-Audio, its HDMI sibling, and a USB interface. */
    private static final Path PCH =
            Paths.get("src", "test", "resources", "proc-asound-pch");
    /** A tree with no cards file at all - every non-Linux host. */
    private static final Path NO_CARDS =
            Paths.get("src", "test", "resources", "proc-asound");

    @Test
    @DisplayName("a USB interface answers its product name, not its short id")
    void usbCardNames() {
        ProcAsound proc = new ProcAsound(SMSL);
        assertEquals("SMSL USB AUDIO", proc.cardName(0),
                "the mixer name only carries the short id AUDIO, which is what "
                        + "sent the operator looking for the product name");
        assertEquals("AV200 - Xonar STX", proc.cardName(1));
    }

    @Test
    @DisplayName("every card line of both machines answers its required name")
    void everyRequiredName() {
        ProcAsound pch = new ProcAsound(PCH);
        assertEquals("HDA Intel PCH", pch.cardName(0));
        assertEquals("HDA NVidia",    pch.cardName(1));
        assertEquals("CUBILUX CB5",   pch.cardName(2));
        ProcAsound smsl = new ProcAsound(SMSL);
        assertEquals("SMSL USB AUDIO",  smsl.cardName(0));
        assertEquals("AV200 - Xonar STX", smsl.cardName(1),
                "the chip is what tells two otherwise similar cards apart, so a "
                        + "driver that shares nothing with the shortname is kept");
    }

    // ------------------------------------------------------- the display rule

    private final ProcAsound rule = new ProcAsound(NO_CARDS);

    @Test
    @DisplayName("a driver that repeats the shortname is dropped")
    void driverThatIsTheShortname() {
        assertEquals("Loopback", rule.cardLabel("Loopback", "Loopback"));
        assertEquals("bcm2835 ALSA", rule.cardLabel("bcm2835", "bcm2835 ALSA"));
    }

    @Test
    @DisplayName("a driver sharing a meaningful word with the shortname is dropped")
    void driverSharingAWord() {
        assertEquals("HDA NVidia", rule.cardLabel("HDA-Intel", "HDA NVidia"));
        assertEquals("HDA Intel PCH", rule.cardLabel("HDA-Intel", "HDA Intel PCH"));
        // Neither string contains the other, so only a token comparison sees it.
        assertEquals("C-Media CMI8738", rule.cardLabel("CMI8738-MC6", "C-Media CMI8738"));
        assertEquals("vc4-hdmi-0", rule.cardLabel("vc4-hdmi", "vc4-hdmi-0"));
        // A driver with a space of its own, which the first " - " split keeps whole.
        assertEquals("ThinkPad Console Audio Control",
                rule.cardLabel("ThinkPad EC", "ThinkPad Console Audio Control"));
        // The kernel cut the driver at 15 characters; the stem still matches.
        assertEquals("wm8960-soundcard",
                rule.cardLabel("wm8960-soundcar", "wm8960-soundcard"));
    }

    @Test
    @DisplayName("a bus or class driver is dropped even when it shares no word")
    void busDrivers() {
        assertEquals("CUBILUX CB5",  rule.cardLabel("USB-Audio", "CUBILUX CB5"));
        assertEquals("Benchmark 1.0", rule.cardLabel("USB-Audio", "Benchmark 1.0"));
        assertEquals("bt-sco-audio", rule.cardLabel("simple-card", "bt-sco-audio"));
        assertEquals("Some Card",    rule.cardLabel("audio-hdmi", "Some Card"));
        assertEquals("Other Card",   rule.cardLabel("usb-audio", "Other Card"),
                "the allowlist is case-insensitive");
    }

    @Test
    @DisplayName("a driver that names the chip is kept in front of the shortname")
    void chipDriversAreKept() {
        assertEquals("AV200 - Xonar STX", rule.cardLabel("AV200", "Xonar STX"));
        assertEquals("ICE1712 - M Audio Delta 1010",
                rule.cardLabel("ICE1712", "M Audio Delta 1010"),
                "'audio' is a word every card shares, so it must not count as a match");
    }

    @Test
    @DisplayName("a half-written line still answers something usable")
    void degenerateLines() {
        assertEquals("Xonar STX", rule.cardLabel("", "Xonar STX"));
        assertEquals("AV200", rule.cardLabel("AV200", ""));
        assertEquals("AV200", rule.cardLabel("AV200", null));
        assertEquals("Xonar STX", rule.cardLabel(null, "Xonar STX"));
    }

    @Test
    @DisplayName("a hyphenated driver does not eat the card name")
    void hdaCardNames() {
        ProcAsound proc = new ProcAsound(PCH);
        assertEquals("HDA Intel PCH", proc.cardName(0),
                "the driver is HDA-Intel: its own hyphen must not be taken for "
                        + "the driver/name separator");
        assertEquals("HDA NVidia", proc.cardName(1));
        assertEquals("CUBILUX CB5", proc.cardName(2));
    }

    @Test
    @DisplayName("the indented long line under each card is not a card")
    void followOnLinesAreSkipped() {
        ProcAsound proc = new ProcAsound(SMSL);
        // Two cards in that file, and nothing else - the long descriptions
        // beneath them repeat the name with the bus address appended.
        assertEquals(2, proc.readCards(SMSL.resolve("cards")).size());
        assertEquals(3, proc.readCards(PCH.resolve("cards")).size());
    }

    @Test
    @DisplayName("an absent file, an absent card and a negative index answer empty")
    void absentAnswersAreEmpty() {
        assertEquals("", new ProcAsound(NO_CARDS).cardName(0),
                "a tree without a cards file is the normal case off Linux");
        ProcAsound proc = new ProcAsound(SMSL);
        assertEquals("", proc.cardName(7), "no such card in the file");
        assertEquals("", proc.cardName(-1), "no ALSA address in the mixer name");
        assertTrue(new ProcAsound(Paths.get("no", "such", "tree")).cardName(0).isEmpty());
    }

    @Test
    @DisplayName("the card index comes from the mixer name's ALSA address")
    void cardNameForAMixerAddress() {
        ProcAsound proc = new ProcAsound(SMSL);
        // The whole point of the lookup: the address in the mixer name selects
        // the card whose product name replaces the short id in the display.
        assertEquals("SMSL USB AUDIO",
                proc.cardName(proc.cardIndexOf("AUDIO [plughw:0,0]")));
        assertEquals("AV200 - Xonar STX",
                proc.cardName(proc.cardIndexOf("STX [plughw:1,0]")));
    }
}
