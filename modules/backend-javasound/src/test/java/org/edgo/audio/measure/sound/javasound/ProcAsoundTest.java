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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/**
 * Drives {@link ProcAsound} against a fixture {@code /proc/asound} tree, so the
 * parsing is testable on a machine that has no ALSA at all (the bench that
 * exposed these faults is a Linux VM; this suite runs on the build host).
 *
 * <p>The fixture mirrors that VM: card0 an HD-Audio codec, card1 the CUBILUX
 * CB5 USB interface, card2 a legacy PCI card with neither layout.
 */
class ProcAsoundTest {

    private static final Path FIXTURES =
            Paths.get("src", "test", "resources", "proc-asound");

    private final ProcAsound proc = new ProcAsound(FIXTURES);

    // ---------------------------------------------------------------- address

    @Test
    void readsTheCardIndexOutOfAnAlsaMixerName() {
        assertEquals(1, proc.cardIndexOf("CB5 [plughw:1,1]"));
        assertEquals(0, proc.cardIndexOf("ALC262 Analog [plughw:0,0]"));
        assertEquals(2, proc.cardIndexOf("[hw:2,0]"), "the direct hw: form too");
    }

    @Test
    void aNameWithNoAlsaAddressHasNoCard() {
        assertEquals(-1, proc.cardIndexOf("Primary Sound Driver"));
        assertEquals(-1, proc.cardIndexOf("Port Speakers"));
        assertEquals(-1, proc.cardIndexOf(null));
    }

    // ------------------------------------------------------------- hw_params

    @Test
    void aHwParamsRangeIsSampledOnTheStandardLadder() {
        // The dump of a PCI card (Xonar STX class): formats as discrete
        // tokens, the rate as a continuous [min max] range that must come
        // back as the standard rates inside it - never as every integer.
        int[][] parsed = proc.parseHwParams(List.of(
                "HW Params of device \"hw:0,0\":",
                "--------------------",
                "ACCESS:  MMAP_INTERLEAVED RW_INTERLEAVED",
                "FORMAT:  S16_LE S32_LE",
                "SUBFORMAT:  STD",
                "SAMPLE_BITS: [16 32]",
                "FRAME_BITS: [32 64]",
                "CHANNELS: [2 2]",
                "RATE: [32000 192000]",
                "PERIOD_TIME: (166 4096000]"));
        assertArrayEquals(new int[] {32000, 44100, 48000, 88200, 96000, 176400, 192000},
                parsed[0], "rates: the ladder within the range");
        assertArrayEquals(new int[] {16, 32}, parsed[1], "depths from the FORMAT tokens");
    }

    @Test
    void aPackedAndAContainer24BothReadAsExactly24() {
        int[][] parsed = proc.parseHwParams(List.of(
                "FORMAT:  S24_3LE S24_LE",
                "RATE: 44100 48000"));
        assertArrayEquals(new int[] {44100, 48000}, parsed[0], "discrete rates verbatim");
        assertArrayEquals(new int[] {24}, parsed[1],
                "both 24-bit spellings are the same exact capability");
    }

    @Test
    void anEmptyDumpAnswersNothing() {
        int[][] parsed = proc.parseHwParams(List.of());
        assertEquals(0, parsed[0].length);
        assertEquals(0, parsed[1].length);
    }

    @Test
    void readsThePcmDeviceOutOfTheSameAddress() {
        // Which PCM device of the card it is - and therefore which of its
        // sockets - is the number after the comma.
        assertEquals(1, proc.deviceIndexOf("CB5 [plughw:1,1]"));
        assertEquals(0, proc.deviceIndexOf("CB5 [plughw:1,0]"));
        assertEquals(0, proc.deviceIndexOf("[hw:2,0]"));
    }

    @Test
    void anAddressWithoutADeviceNamesNoDevice() {
        assertEquals(-1, proc.deviceIndexOf("Port CB5 [hw:1]"),
                "a card, not one of its PCM devices - Java's own Port mixers");
        assertEquals(-1, proc.deviceIndexOf("Primary Sound Driver"));
        assertEquals(-1, proc.deviceIndexOf(null));
    }

    // ------------------------------------------------------- the USB interface

    @Test
    void namesTheUsbInterfaceServingEachDirectionOfAPcmDevice() {
        // The link from an ALSA address to the card's USB descriptors: the
        // stream file is named after the PCM device, and its sections name one
        // AudioStreaming interface each.
        assertEquals(1, proc.streamInterface(1, 0, false), "playback of device 0");
        assertEquals(2, proc.streamInterface(1, 0, true),  "capture of device 0");
        assertEquals(3, proc.streamInterface(1, 1, false), "playback of device 1");
        assertEquals(4, proc.streamInterface(1, 1, true),  "capture of device 1");
    }

    @Test
    void aCardWithNoStreamFileNamesNoInterface() {
        assertEquals(-1, proc.streamInterface(0, 0, true), "the HD-Audio codec");
        assertEquals(-1, proc.streamInterface(2, 0, true), "the legacy PCI card");
        assertEquals(-1, proc.streamInterface(1, 7, true), "a PCM device the card has not");
        assertEquals(-1, proc.streamInterface(-1, -1, true));
    }

    // ------------------------------------------------------------- per device

    @Test
    void aUsbCardReportsItsOwnExactRatesPerDirection() {
        ProcAsound.CardCaps cb5 = proc.cardCaps(1);
        assertTrue(cb5.known());
        assertArrayEquals(
                new int[] { 44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000 },
                cb5.rates(true), "playback lists both altsets");
        assertArrayEquals(new int[] { 24, 32 }, cb5.depths(true));
        // Capture on this device offers ONE altset - fewer rates and one depth.
        assertArrayEquals(
                new int[] { 44100, 48000, 88200, 96000, 176400, 192000 }, cb5.rates(false));
        assertArrayEquals(new int[] { 24 }, cb5.depths(false));
    }

    @Test
    void anHdaCardReportsItsAnalogNodesOnly() {
        ProcAsound.CardCaps hda = proc.cardCaps(0);
        assertTrue(hda.known());
        // 192000 belongs to the DIGITAL output node and 384000 to a Pin Complex;
        // neither describes the analog converter, so neither is offered.
        assertArrayEquals(new int[] { 44100, 48000, 96000 }, hda.rates(true));
        assertArrayEquals(new int[] { 16, 24 }, hda.depths(true));
        assertArrayEquals(new int[] { 44100, 48000, 96000 }, hda.rates(false));
    }

    @Test
    void theTwoCardsDoNotContaminateEachOther() {
        // The whole point of the per-card lookup: before it, every device on the
        // machine got the union of both cards.
        assertFalse(IntStream.of(proc.cardCaps(0).rates(true)).anyMatch(r -> r == 384000),
                "the onboard codec must not inherit the USB interface's 384 kHz");
        assertTrue(IntStream.of(proc.cardCaps(1).rates(true)).anyMatch(r -> r == 384000));
    }

    // ------------------------------------------------------------ the unknown

    @Test
    void aCardWithNeitherLayoutIsUnknownRatherThanEmpty() {
        ProcAsound.CardCaps legacy = proc.cardCaps(2);
        assertFalse(legacy.known(),
                "a legacy PCI card must read as 'cannot say', never as 'supports nothing' - "
                        + "the caller decides what to do, and must not publish an empty list as fact");
        assertEquals(0, legacy.rates(true).length);
    }

    @Test
    void anAbsentCardIsUnknown() {
        assertFalse(proc.cardCaps(9).known());
        assertFalse(proc.cardCaps(-1).known(), "a mixer with no ALSA address");
    }

    @Test
    void capsForMixerResolvesTheWholeLookup() {
        assertTrue(proc.capsForMixer("CB5 [plughw:1,1]").known());
        assertFalse(proc.capsForMixer("Primary Sound Driver").known());
    }

    @Test
    void aMissingProcTreeIsUnknownRatherThanAThrow() {
        ProcAsound none = new ProcAsound(Paths.get("no", "such", "tree"));
        assertFalse(none.cardCaps(0).known());
    }
}
