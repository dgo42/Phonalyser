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
 * Drives {@link UsbAudioDescriptors} against a synthesized UAC2 blob
 * ({@link UsbDescriptorFixture}), so the binary parsing is testable with no USB
 * device attached at all.
 *
 * <p>The blob is the topology of a two-pair interface, which is exactly the
 * case the port names exist for: two PCM devices, four streaming interfaces,
 * and four different sockets that ALSA would otherwise list as "USB Audio #0"
 * and "USB Audio #1".
 */
class UsbAudioDescriptorsTest {

    private final UsbAudioDescriptors bench =
            new UsbAudioDescriptors(new UsbDescriptorFixture().benchBlob());

    // ------------------------------------------------------------- the sockets

    @Test
    void aPlaybackStreamIsNamedByTheSocketItDrains() {
        // Interface 1 links the USB INPUT terminal, so the socket is at the far
        // end of the graph: through the feature unit, out of the output terminal.
        assertEquals(UsbDescriptorFixture.HEADPHONE, bench.terminalTypeOf(1));
        assertEquals(UsbDescriptorFixture.LINE, bench.terminalTypeOf(3));
    }

    @Test
    void aCaptureStreamIsNamedByTheSocketThatFeedsIt() {
        // Interface 2 links the USB OUTPUT terminal, so the walk goes the other
        // way - and must not answer the USB terminal it started from.
        assertEquals(UsbDescriptorFixture.MICROPHONE, bench.terminalTypeOf(2));
        assertEquals(UsbDescriptorFixture.LINE, bench.terminalTypeOf(4));
    }

    @Test
    void theTwoPcmDevicesDoNotContaminateEachOther() {
        // The whole point: one PCM device is the microphone and headphone pair,
        // the other the line pair, and a streaming interface must reach only
        // the socket its own graph leads to.
        assertEquals(UsbDescriptorFixture.HEADPHONE, bench.terminalTypeOf(1));
        assertEquals(UsbDescriptorFixture.MICROPHONE, bench.terminalTypeOf(2));
        assertEquals(UsbDescriptorFixture.LINE, bench.terminalTypeOf(3));
        assertEquals(UsbDescriptorFixture.LINE, bench.terminalTypeOf(4));
    }

    // ------------------------------------------------------------- the unknown

    @Test
    void anInterfaceTheDeviceDoesNotStreamOnHasNoSocket() {
        assertEquals(UsbAudioDescriptors.NO_TERMINAL, bench.terminalTypeOf(9));
        assertEquals(UsbAudioDescriptors.NO_TERMINAL, bench.terminalTypeOf(0),
                "the control interface carries no stream");
    }

    @Test
    void aStreamThatEndsNowhereIsUnknownRatherThanTheUsbSideOfItself() {
        // A link into a USB terminal whose graph reaches no socket: the honest
        // answer is "cannot say", never 0x0101, which would be named as a port.
        UsbDescriptorFixture device = new UsbDescriptorFixture();
        device.audioInterface(0, UsbDescriptorFixture.SUBCLASS_CONTROL);
        device.inputTerminal(1, UsbDescriptorFixture.USB_STREAMING);
        device.audioInterface(1, UsbDescriptorFixture.SUBCLASS_STREAMING);
        device.asGeneral(1);
        assertEquals(UsbAudioDescriptors.NO_TERMINAL,
                new UsbAudioDescriptors(device.bytes()).terminalTypeOf(1));
    }

    @Test
    void aDeviceThatWiresItsStreamStraightToASocketIsReadAsIs() {
        // Rare, but legal: no unit in between at all.
        UsbDescriptorFixture device = new UsbDescriptorFixture();
        device.audioInterface(0, UsbDescriptorFixture.SUBCLASS_CONTROL);
        device.inputTerminal(1, UsbDescriptorFixture.SPDIF);
        device.outputTerminal(2, UsbDescriptorFixture.USB_STREAMING, 1);
        device.audioInterface(1, UsbDescriptorFixture.SUBCLASS_STREAMING);
        device.asGeneral(2);
        assertEquals(UsbDescriptorFixture.SPDIF,
                new UsbAudioDescriptors(device.bytes()).terminalTypeOf(1));
    }

    // -------------------------------------------------------------- the broken

    @Test
    void nothingAtAllIsNoTerminalRatherThanAThrow() {
        assertEquals(UsbAudioDescriptors.NO_TERMINAL,
                new UsbAudioDescriptors(null).terminalTypeOf(1));
        assertEquals(UsbAudioDescriptors.NO_TERMINAL,
                new UsbAudioDescriptors(new byte[0]).terminalTypeOf(1));
    }

    @Test
    void aTruncatedBlobIsReadAsFarAsItGoesAndGuessesNothing() {
        byte[] whole = new UsbDescriptorFixture().benchBlob();
        byte[] cut = new byte[whole.length / 2];
        System.arraycopy(whole, 0, cut, 0, cut.length);
        assertEquals(UsbAudioDescriptors.NO_TERMINAL,
                new UsbAudioDescriptors(cut).terminalTypeOf(4),
                "the stream links sit at the end of the blob - cut off, no interface "
                        + "resolves, and a device is listed unnamed rather than mis-named");
    }

    @Test
    void aDescriptorClaimingNoLengthCannotHangTheParse() {
        // A zero-length descriptor advances the cursor by nothing; without a
        // guard this loops until the process is killed.
        assertEquals(UsbAudioDescriptors.NO_TERMINAL,
                new UsbAudioDescriptors(new byte[] { 0, 0x24, 0, 0 }).terminalTypeOf(1));
    }

    @Test
    void aSampleRateTableIsNotATerminal() {
        // Subtype 2 is INPUT_TERMINAL in a control interface and FORMAT_TYPE in
        // a streaming one.  Read without its enclosing interface, this format
        // descriptor's bytes would be published as a terminal of type 0x1804.
        UsbDescriptorFixture device = new UsbDescriptorFixture();
        device.audioInterface(1, UsbDescriptorFixture.SUBCLASS_STREAMING);
        device.asGeneral(2);
        device.formatType();
        assertEquals(UsbAudioDescriptors.NO_TERMINAL,
                new UsbAudioDescriptors(device.bytes()).terminalTypeOf(1));
    }
}
