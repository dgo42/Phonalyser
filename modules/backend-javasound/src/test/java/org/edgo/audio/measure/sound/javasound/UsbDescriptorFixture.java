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

import java.io.ByteArrayOutputStream;

/**
 * Writes the descriptors a USB audio device would answer with - the same bytes,
 * in the same order, as the kernel's {@code descriptors} file carries. One
 * instance writes one blob.
 *
 * <p>Shared by the parser's own test and by {@link AlsaPortsTest}, which needs
 * the same device on disk to walk the whole chain from a mixer name to a port.
 */
class UsbDescriptorFixture {

    /** Terminal types, as the USB Audio Terminal Types document numbers them. */
    static final int USB_STREAMING = 0x0101;
    static final int MICROPHONE    = 0x0201;
    static final int HEADPHONE     = 0x0302;
    static final int LINE          = 0x0603;
    static final int SPDIF         = 0x0605;

    static final int SUBCLASS_CONTROL = 0x01;
    static final int SUBCLASS_STREAMING = 0x02;

    private static final int TYPE_DEVICE = 0x01;
    private static final int TYPE_CONFIGURATION = 0x02;
    private static final int TYPE_INTERFACE = 0x04;
    private static final int TYPE_CS_INTERFACE = 0x24;
    private static final int CLASS_AUDIO = 0x01;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    /** Everything written so far. */
    byte[] bytes() {
        return out.toByteArray();
    }

    /**
     * The bench topology, whole: PCM device 0 is a microphone in and a
     * headphone out, PCM device 1 a line pair, each stream through a feature
     * unit - so streaming interfaces 1..4 are headphone, microphone, line out
     * and line in, which is what the {@code /proc} stream fixtures name.
     */
    byte[] benchBlob() {
        deviceDescriptor();
        configurationDescriptor();
        audioInterface(0, SUBCLASS_CONTROL);
        controlHeader();
        clockSource(20);
        // Streaming interface 1: host -> headphone.
        inputTerminal(1, USB_STREAMING);
        featureUnit(2, 1);
        outputTerminal(3, HEADPHONE, 2);
        // Streaming interface 2: microphone -> host.
        inputTerminal(4, MICROPHONE);
        featureUnit(5, 4);
        outputTerminal(6, USB_STREAMING, 5);
        // Streaming interface 3: host -> line out.
        inputTerminal(7, USB_STREAMING);
        featureUnit(8, 7);
        outputTerminal(9, LINE, 8);
        // Streaming interface 4: line in -> host.
        inputTerminal(10, LINE);
        featureUnit(11, 10);
        outputTerminal(12, USB_STREAMING, 11);
        audioInterface(1, SUBCLASS_STREAMING);      // altset 0, no stream at all
        audioInterface(1, SUBCLASS_STREAMING);
        asGeneral(1);
        audioInterface(2, SUBCLASS_STREAMING);
        asGeneral(6);
        audioInterface(3, SUBCLASS_STREAMING);
        asGeneral(7);
        audioInterface(4, SUBCLASS_STREAMING);
        asGeneral(12);
        return bytes();
    }

    /** 18 bytes of device descriptor - skipped whole by the parser. */
    void deviceDescriptor() {
        write(18, TYPE_DEVICE, 0x00, 0x02, 0, 0, 0, 64, 0x0d, 0x8c, 0x0c, 0x00,
                0x00, 0x01, 1, 2, 3, 1);
    }

    /** 9 bytes of configuration descriptor - likewise. Every descriptor here
     *  must be exactly as long as its own first byte claims, or the parser
     *  walks off by that much and reads the rest as rubbish. */
    void configurationDescriptor() {
        write(9, TYPE_CONFIGURATION, 0x00, 0x01, 1, 1, 0, 0x80, 50);
    }

    /** An audio interface descriptor: everything after it belongs to it. */
    void audioInterface(int number, int subclass) {
        write(9, TYPE_INTERFACE, number, 0, 1, CLASS_AUDIO, subclass, 0x20, 0);
    }

    /** The control interface's own header - a subtype 1 that is NOT a stream
     *  link, which is why the enclosing subclass has to be tracked. */
    void controlHeader() {
        write(9, TYPE_CS_INTERFACE, 0x01, 0x00, 0x02, 0x01, 0x40, 0x00, 0x00);
    }

    /** A clock source - a unit shape the walk does not follow. */
    void clockSource(int id) {
        write(8, TYPE_CS_INTERFACE, 0x0a, id, 0x03, 0x07, 0, 0);
    }

    void inputTerminal(int id, int terminalType) {
        write(17, TYPE_CS_INTERFACE, 0x02, id,
                terminalType & 0xFF, (terminalType >> 8) & 0xFF,
                0, 20, 2, 3, 0, 0, 0, 0, 0, 0, 0);
    }

    void outputTerminal(int id, int terminalType, int source) {
        write(12, TYPE_CS_INTERFACE, 0x03, id,
                terminalType & 0xFF, (terminalType >> 8) & 0xFF,
                0, source, 20, 0, 0, 0);
    }

    void featureUnit(int id, int source) {
        write(18, TYPE_CS_INTERFACE, 0x06, id, source,
                0x0f, 0, 0, 0, 0x0f, 0, 0, 0, 0x0f, 0, 0, 0, 0);
    }

    /** The stream's link to a terminal. */
    void asGeneral(int terminal) {
        write(16, TYPE_CS_INTERFACE, 0x01, terminal, 0, 1,
                0x01, 0, 0, 0, 2, 0x03, 0, 0, 0, 0);
    }

    /** A streaming format descriptor - subtype 2, the same number an input
     *  terminal carries in the control interface. */
    void formatType() {
        write(6, TYPE_CS_INTERFACE, 0x02, 0x01, 0x04, 0x18);
    }

    private void write(int... descriptor) {
        for (int value : descriptor) {
            out.write(value & 0xFF);
        }
    }
}
