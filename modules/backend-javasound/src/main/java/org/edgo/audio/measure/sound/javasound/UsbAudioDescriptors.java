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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One USB device's audio descriptors, read from the raw bytes the kernel
 * publishes beside the device ({@code descriptors}) - the device descriptor
 * followed by its whole configuration, exactly as the device enumerated.
 *
 * <p>It answers ONE question: what socket does the audio of streaming interface
 * {@code N} come from, or go to? The device says so itself, in the standard
 * USB-Audio topology -
 *
 * <ol>
 *   <li>the streaming interface's {@code AS_GENERAL} descriptor names the
 *       terminal it is wired to ({@code bTerminalLink}) - always the USB SIDE
 *       of the link, type 0x01xx, which is no socket at all;</li>
 *   <li>from there the audio flows through the unit graph - feature, selector,
 *       mixer and processing units, each naming the units it draws from - to
 *       the terminal at the other end;</li>
 *   <li>THAT terminal is the socket, and its {@code wTerminalType} says what
 *       kind: microphone, line connector, headphone, speaker, S/PDIF.</li>
 * </ol>
 *
 * <p>Capture and playback are walked in opposite directions. A capture stream
 * ENDS at the USB output terminal, so its socket is upstream; a playback stream
 * STARTS at the USB input terminal, so its socket is the output terminal that
 * draws from it. Which of the two the link is says which way to walk, so no
 * caller has to declare a direction.
 *
 * <p>Both UAC1 and UAC2 are read by the same code because every field this
 * class wants sits at the same offset in both revisions: {@code bTerminalLink}
 * at byte 3 of {@code AS_GENERAL}, {@code bTerminalID} / {@code wTerminalType}
 * at bytes 3 and 4-5 of a terminal, an output terminal's {@code bSourceID} at
 * byte 7, and a unit's id at byte 3. Everything that moved between the
 * revisions sits behind those fields.
 *
 * <p><b>The interface SUBCLASS decides how a class descriptor is read.</b>
 * Subtype 1 is {@code AS_GENERAL} inside a streaming interface and the
 * {@code HEADER} inside a control one; subtypes 2 and 3 are the two terminals
 * in a control interface and the format descriptors in a streaming one. A
 * parser that ignored the enclosing interface would read a sample-rate table as
 * a terminal type.
 *
 * <p>Unparseable, truncated or absent bytes are not an error: every query then
 * answers {@link #NO_TERMINAL}, which a caller must read as "cannot say" rather
 * than as a device with no ports.
 */
public final class UsbAudioDescriptors {

    /** What a query answers when the descriptors do not say - never a type. */
    public static final int NO_TERMINAL = 0;

    /** Standard descriptor type: an interface descriptor. */
    private static final int TYPE_INTERFACE = 0x04;
    /** Class-specific descriptor type: an interface's own audio descriptor. */
    private static final int TYPE_CS_INTERFACE = 0x24;

    /** {@code bInterfaceClass} of every audio interface. */
    private static final int CLASS_AUDIO = 0x01;
    /** {@code bInterfaceSubClass} of the control interface (terminals, units). */
    private static final int SUBCLASS_AUDIOCONTROL = 0x01;
    /** {@code bInterfaceSubClass} of a streaming interface (the endpoints). */
    private static final int SUBCLASS_AUDIOSTREAMING = 0x02;

    /** {@code AS_GENERAL} - the streaming interface's terminal link. */
    private static final int SUBTYPE_AS_GENERAL = 0x01;
    /** {@code INPUT_TERMINAL} of the control interface. */
    private static final int SUBTYPE_INPUT_TERMINAL = 0x02;
    /** {@code OUTPUT_TERMINAL} of the control interface. */
    private static final int SUBTYPE_OUTPUT_TERMINAL = 0x03;
    /** {@code MIXER_UNIT} - source count at byte 4, the ids after it. */
    private static final int SUBTYPE_MIXER_UNIT = 0x04;
    /** {@code SELECTOR_UNIT} - same shape as the mixer. */
    private static final int SUBTYPE_SELECTOR_UNIT = 0x05;
    /** {@code FEATURE_UNIT} - exactly one source, at byte 4. */
    private static final int SUBTYPE_FEATURE_UNIT = 0x06;
    /** {@code PROCESSING_UNIT} - source count at byte 6, the ids after it. */
    private static final int SUBTYPE_PROCESSING_UNIT = 0x07;
    /** {@code EXTENSION_UNIT} - same shape as the processing unit. */
    private static final int SUBTYPE_EXTENSION_UNIT = 0x08;

    /** The terminal family of the USB side of a link - the PCM stream itself,
     *  which nobody ever plugs a cable into. */
    private static final int USB_TERMINAL_FAMILY = 0x0100;
    /** The high byte a terminal family is named by. */
    private static final int FAMILY_MASK = 0xFF00;

    /** A class descriptor must reach byte 3 before its payload can be read. */
    private static final int MIN_LINK_LENGTH = 4;
    /** {@code wTerminalType} ends at byte 5. */
    private static final int MIN_TERMINAL_LENGTH = 6;
    /** An output terminal's {@code bSourceID} sits at byte 7. */
    private static final int MIN_OUTPUT_TERMINAL_LENGTH = 8;
    /** A unit's first source sits at byte 4 at the earliest. */
    private static final int MIN_UNIT_LENGTH = 5;
    /** An interface descriptor declares its class at byte 5. */
    private static final int MIN_INTERFACE_LENGTH = 7;

    /** Where a mixer's and a selector's source count sits. */
    private static final int MIXER_SOURCE_COUNT = 4;
    /** Where a processing unit's source count sits (the process type is in
     *  between). */
    private static final int PROCESSING_SOURCE_COUNT = 6;

    /** How far a walk may follow the unit graph - the visited set already stops
     *  a loop, and this bounds a malformed chain as well, so no descriptor can
     *  hang an enumeration. */
    private static final int MAX_WALK = 32;

    /** One end of a link: what it is, and the unit it draws from. */
    private record Terminal(int type, int source) { }

    /** AudioStreaming interface number -> the terminal id it is wired to. */
    private final Map<Integer, Integer> interfaceTerminal = new HashMap<>();

    /** Input terminal id -> {@code wTerminalType} (an input draws from
     *  nothing inside the device). */
    private final Map<Integer, Integer> inputTerminals = new HashMap<>();

    /** Output terminal id -> its type and the unit it draws from. */
    private final Map<Integer, Terminal> outputTerminals = new HashMap<>();

    /** Unit id -> the units it draws from, in descriptor order. */
    private final Map<Integer, List<Integer>> unitSources = new HashMap<>();

    /**
     * Reads {@code blob} - the whole {@code descriptors} file. A null, short or
     * malformed blob simply leaves the maps empty; this constructor never
     * throws, because a card whose descriptors cannot be read must degrade to
     * "no port names", not to a failed enumeration.
     */
    public UsbAudioDescriptors(byte[] blob) {
        if (blob == null) return;
        int subclass = -1;
        int number = -1;
        int at = 0;
        while (at + 1 < blob.length) {
            int length = blob[at] & 0xFF;
            // A zero-length descriptor would spin this loop forever, and one
            // that runs past the end is a truncated read: stop at either.
            if (length < 2 || at + length > blob.length) break;
            int type = blob[at + 1] & 0xFF;
            if (type == TYPE_INTERFACE && length >= MIN_INTERFACE_LENGTH) {
                number   = blob[at + 2] & 0xFF;
                int cls  = blob[at + 5] & 0xFF;
                subclass = cls == CLASS_AUDIO ? blob[at + 6] & 0xFF : -1;
            } else if (type == TYPE_CS_INTERFACE && length >= 3) {
                readClassDescriptor(blob, at, length, subclass, number);
            }
            at += length;
        }
    }

    /**
     * The type of socket the AudioStreaming interface {@code number} carries, or
     * {@link #NO_TERMINAL} when this device says nothing about it.
     *
     * <p>The link itself is the USB side of the stream, so the answer is at the
     * far end of the unit graph - upstream for a capture stream, downstream for
     * a playback one (see the class comment). A device that wires its stream
     * straight to a socket is answered with that socket.
     */
    public int terminalTypeOf(int number) {
        Integer link = interfaceTerminal.get(number);
        if (link == null) return NO_TERMINAL;
        Terminal output = outputTerminals.get(link);
        if (output != null) {
            // The stream ENDS here: a capture path, and its socket feeds it.
            return isSocket(output.type()) ? output.type() : upstreamSocket(output.source());
        }
        Integer inputType = inputTerminals.get(link);
        if (inputType != null) {
            // The stream STARTS here: a playback path, and its socket drains it.
            return isSocket(inputType) ? inputType : downstreamSocket(link);
        }
        return NO_TERMINAL;
    }

    /** One class-specific descriptor, read as what its ENCLOSING interface
     *  makes it (see the class comment). The first value for a key wins, so a
     *  device with more than one configuration is read as the first one - the
     *  configuration a host actually selects. */
    private void readClassDescriptor(byte[] blob, int at, int length, int subclass, int number) {
        int subtype = blob[at + 2] & 0xFF;
        if (subclass == SUBCLASS_AUDIOSTREAMING) {
            if (subtype == SUBTYPE_AS_GENERAL && length >= MIN_LINK_LENGTH && number >= 0) {
                interfaceTerminal.putIfAbsent(number, blob[at + 3] & 0xFF);
            }
            return;
        }
        if (subclass != SUBCLASS_AUDIOCONTROL) return;
        if (subtype == SUBTYPE_INPUT_TERMINAL && length >= MIN_TERMINAL_LENGTH) {
            inputTerminals.putIfAbsent(blob[at + 3] & 0xFF, word(blob, at + 4));
        } else if (subtype == SUBTYPE_OUTPUT_TERMINAL && length >= MIN_OUTPUT_TERMINAL_LENGTH) {
            outputTerminals.putIfAbsent(blob[at + 3] & 0xFF,
                    new Terminal(word(blob, at + 4), blob[at + 7] & 0xFF));
        } else if (length >= MIN_UNIT_LENGTH) {
            readUnit(blob, at, length, subtype);
        }
    }

    /** A unit's sources, by the shape its subtype declares. Anything else - a
     *  clock source, an effect unit, the control header - draws from nothing
     *  this walk can follow, and is left out. */
    private void readUnit(byte[] blob, int at, int length, int subtype) {
        int id = blob[at + 3] & 0xFF;
        if (subtype == SUBTYPE_FEATURE_UNIT) {
            unitSources.putIfAbsent(id, List.of(blob[at + 4] & 0xFF));
        } else if (subtype == SUBTYPE_MIXER_UNIT || subtype == SUBTYPE_SELECTOR_UNIT) {
            unitSources.putIfAbsent(id, sources(blob, at, length, MIXER_SOURCE_COUNT));
        } else if (subtype == SUBTYPE_PROCESSING_UNIT || subtype == SUBTYPE_EXTENSION_UNIT) {
            unitSources.putIfAbsent(id, sources(blob, at, length, PROCESSING_SOURCE_COUNT));
        }
    }

    /** The {@code baSourceID} array of a multi-input unit: a count at
     *  {@code countAt} bytes into the descriptor, that many ids after it. */
    private List<Integer> sources(byte[] blob, int at, int length, int countAt) {
        List<Integer> found = new ArrayList<>();
        if (countAt >= length) return found;
        int count = blob[at + countAt] & 0xFF;
        for (int i = 1; i <= count && countAt + i < length; i++) {
            found.add(blob[at + countAt + i] & 0xFF);
        }
        return found;
    }

    /** The first socket found walking INTO a unit, following every input a
     *  mixer or a selector offers. */
    private int upstreamSocket(int from) {
        Deque<Integer> pending = new ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        pending.add(from);
        for (int step = 0; step < MAX_WALK && !pending.isEmpty(); step++) {
            int id = pending.remove();
            if (!seen.add(id)) continue;
            Integer type = inputTerminals.get(id);
            if (type != null && isSocket(type)) return type;
            pending.addAll(unitSources.getOrDefault(id, List.of()));
        }
        return NO_TERMINAL;
    }

    /** The socket of the output terminal that draws, however indirectly, from
     *  {@code link} - the far end of a playback stream. */
    private int downstreamSocket(int link) {
        for (Terminal output : outputTerminals.values()) {
            if (isSocket(output.type()) && draws(output.source(), link)) {
                return output.type();
            }
        }
        return NO_TERMINAL;
    }

    /** Whether the chain into {@code from} reaches {@code link}. */
    private boolean draws(int from, int link) {
        Deque<Integer> pending = new ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        pending.add(from);
        for (int step = 0; step < MAX_WALK && !pending.isEmpty(); step++) {
            int id = pending.remove();
            if (id == link) return true;
            if (!seen.add(id)) continue;
            pending.addAll(unitSources.getOrDefault(id, List.of()));
        }
        return false;
    }

    /** Whether a terminal type is something a cable goes into - everything
     *  except the USB side of the stream. */
    private boolean isSocket(int terminalType) {
        return terminalType != NO_TERMINAL
                && (terminalType & FAMILY_MASK) != USB_TERMINAL_FAMILY;
    }

    /** A little-endian 16-bit field. */
    private int word(byte[] blob, int at) {
        return (blob[at] & 0xFF) | ((blob[at + 1] & 0xFF) << 8);
    }
}
