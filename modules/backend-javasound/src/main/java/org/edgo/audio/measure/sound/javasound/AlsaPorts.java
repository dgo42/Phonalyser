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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.log4j.Log4j2;

/**
 * What SOCKET a Linux audio device is, and whether anything is plugged into it.
 *
 * <p>ALSA names a PCM device after the chip that carries it, so a USB interface
 * with a line pair and a microphone pair is listed as "USB Audio #0" and "USB
 * Audio #1" - two rows that say nothing about which cable goes where. Windows
 * lists the same hardware by its ports ("Line In", "Microphone"), because that
 * is what an operator patches. The card itself knows: its USB descriptors name
 * a terminal per streaming interface, and the kernel's jack controls report
 * whether that terminal has a plug.
 *
 * <p>The chain, all of it read from files this machine already publishes:
 *
 * <ol>
 *   <li>the mixer name carries the ALSA address - {@code "[plughw:1,1]"} is
 *       card 1, PCM device 1 ({@link ProcAsound#cardIndexOf} /
 *       {@link ProcAsound#deviceIndexOf});</li>
 *   <li>{@code /proc/asound/card1/stream1} names the USB AudioStreaming
 *       interface that serves each direction ({@link
 *       ProcAsound#streamInterface});</li>
 *   <li>the card's USB descriptors say what that interface is wired to
 *       ({@link UsbAudioDescriptors});</li>
 *   <li>the terminal type becomes the word ALSA itself uses for it - Line, Mic,
 *       Headphone, Speaker, IEC958 - which is both the label shown and the name
 *       of the jack control {@link AlsaJacks} reads.</li>
 * </ol>
 *
 * <p><b>Nothing is ever hidden on a guess.</b> Any link in that chain that does
 * not answer - a card that is not USB, a kernel without the descriptors file, a
 * terminal type nothing maps, a machine without alsa-utils - ends in a null
 * port, and the caller then lists the device exactly as it did before. A device
 * is dropped only where a jack control explicitly reports {@code off}.
 *
 * <p>The port names are cached (a socket does not move); the jack readings are
 * not - see {@link AlsaJacks#forget()}.
 */
@Log4j2
public final class AlsaPorts {

    /** Where the kernel publishes each card, and from where the USB device that
     *  carries it can be reached. */
    private static final String SYS_CLASS_SOUND = "/sys/class/sound";

    /** The raw USB descriptor blob, beside the device that owns the card. */
    private static final String DESCRIPTORS = "descriptors";

    /** How far up from {@code /sys/class/sound/cardN} the USB device sits: the
     *  card's own directory, its {@code sound} parent, the interface, then the
     *  device. Two more than needed, so an added level does not break it, and
     *  bounded so a symlink loop cannot walk the whole tree. */
    private static final int DESCRIPTOR_SEARCH_DEPTH = 6;

    /** How a jack control names the direction it senses (ALSA's own wording). */
    private static final String INPUT_JACK = " - Input Jack";
    private static final String OUTPUT_JACK = " - Output Jack";

    /** How the label reads when the terminal's own word does not already say
     *  which way round the socket is. */
    private static final String INPUT_LABEL = " In";
    private static final String OUTPUT_LABEL = " Out";

    /**
     * One device's socket: what to call it, and whether a plug is in it.
     *
     * @param label     the port as an operator knows it - "Line In", "Mic In",
     *                  "Headphone Out"
     * @param word      the bare word ALSA gives the terminal - "Line", "Mic",
     *                  "Headphone" - which is what the card names this socket's
     *                  own controls after: the jack control read here
     *                  ({@code "Line - Input Jack"}) and the volume control
     *                  {@link AlsaVolumes} pins ({@code "Line Capture Volume"})
     * @param connected TRUE with a plug, FALSE with an empty socket, and null
     *                  when this machine cannot sense it - which must be read
     *                  as "list it", never as "hide it"
     */
    public record Port(String label, String word, Boolean connected) {

        /** True only where the hardware actually reported an empty socket. */
        public boolean empty() {
            return Boolean.FALSE.equals(connected);
        }
    }

    /**
     * The word ALSA gives a USB terminal type, mirroring the kernel's own table
     * ({@code sound/usb/mixer.c}, {@code get_term_name}) - which is what makes
     * the label and the jack control name the same string. A whole family
     * ({@code family = true}) shares one word: every microphone type, however
     * the descriptor spells it, is "Mic".
     */
    private enum AlsaTerminal {

        MICROPHONE(0x0200, true,  "Mic"),
        HEADSET(0x0400,    true,  "Headset"),
        TELEPHONY(0x0500,  true,  "Phone"),
        OUTPUT(0x0300,     false, "Output"),
        SPEAKER(0x0301,    false, "Speaker"),
        HEADPHONE(0x0302,  false, "Headphone"),
        DESKTOP_SPEAKER(0x0304, false, "Desktop Speaker"),
        ROOM_SPEAKER(0x0305,    false, "Room Speaker"),
        COM_SPEAKER(0x0306,     false, "Com Speaker"),
        LFE(0x0307,        false, "LFE"),
        EXTERNAL_IN(0x0600, false, "External In"),
        ANALOG_IN(0x0601,   false, "Analog In"),
        DIGITAL_IN(0x0602,  false, "Digital In"),
        LINE(0x0603,        false, "Line"),
        LEGACY_IN(0x0604,   false, "Legacy In"),
        SPDIF(0x0605,       false, "IEC958 In");

        /** High byte of a terminal type - what a whole family shares. */
        private static final int FAMILY_MASK = 0xFF00;

        private final int type;
        private final boolean family;
        private final String word;

        private AlsaTerminal(int type, boolean family, String word) {
            this.type = type;
            this.family = family;
            this.word = word;
        }

        /** The terminal for a {@code wTerminalType}, or null for one this table
         *  does not name - the USB streaming terminal (0x01xx) among them,
         *  which is the PCM side of the link and no socket at all. */
        static AlsaTerminal fromType(int terminalType) {
            for (AlsaTerminal candidate : values()) {
                boolean hit = candidate.family
                        ? (terminalType & FAMILY_MASK) == candidate.type
                        : terminalType == candidate.type;
                if (hit) return candidate;
            }
            return null;
        }
    }

    /** The ALSA address in a mixer name, and the stream files behind it. */
    private final ProcAsound proc;

    /** {@code /sys/class/sound} in production, a fixture tree under test. */
    private final Path sysRoot;

    /** Live jack state - deliberately not cached across enumerations. */
    private final AlsaJacks jacks;

    /** One parsed descriptor blob per card. A card with none caches an empty
     *  parse, so its sysfs tree is walked once per process, not once per
     *  device of every enumeration. */
    private final Map<Integer, UsbAudioDescriptors> descriptors = new ConcurrentHashMap<>();

    /** Reads the real {@code /sys/class/sound} and runs the real
     *  {@code amixer}. */
    public AlsaPorts(ProcAsound proc) {
        this(proc, new AlsaJacks());
    }

    /** Reads the real {@code /sys/class/sound} through a jack reader the caller
     *  already holds - which is how {@link AlsaVolumes} comes to read the same
     *  card's controls through the same one. */
    public AlsaPorts(ProcAsound proc, AlsaJacks jacks) {
        this(proc, Paths.get(SYS_CLASS_SOUND), jacks);
    }

    /** Injected whole, so a test can drive fixture trees and a jack reader that
     *  needs neither a sound card nor alsa-utils. */
    public AlsaPorts(ProcAsound proc, Path sysRoot, AlsaJacks jacks) {
        this.proc = proc;
        this.sysRoot = sysRoot;
        this.jacks = jacks;
    }

    /**
     * The socket behind a JavaSound mixer name in one direction, or null when
     * this machine cannot say (see the class comment).
     *
     * @param mixerName the JavaSound mixer name, card and PCM device included
     *                  ({@code "<card> [plughw:1,1]"})
     * @param input     true for the capture side of that PCM device
     */
    public Port port(String mixerName, boolean input) {
        int card = proc.cardIndexOf(mixerName);
        int device = proc.deviceIndexOf(mixerName);
        if (card < 0 || device < 0) return null;      // not an ALSA PCM mixer
        int number = proc.streamInterface(card, device, input);
        if (number < 0) return null;                  // not USB, or silent here
        AlsaTerminal terminal =
                AlsaTerminal.fromType(cardDescriptors(card).terminalTypeOf(number));
        if (terminal == null) return null;
        Port port = new Port(label(terminal.word, input), terminal.word,
                jacks.connected(card, terminal.word + (input ? INPUT_JACK : OUTPUT_JACK)));
        if (log.isDebugEnabled()) {
            log.debug("{} {} is {}", mixerName, input ? "capture" : "playback", port);
        }
        return port;
    }

    /** Drops the jack readings so the next {@link #port} query senses the
     *  hardware again - called once per device enumeration, which is what lets
     *  a cable pulled between two scans be noticed. */
    public void refresh() {
        jacks.forget();
    }

    /** The terminal's word plus the direction, unless the word already carries
     *  one: "Line" reads "Line In" or "Line Out", while "Analog In" and
     *  "IEC958 In" are left as ALSA spells them. */
    private String label(String word, boolean input) {
        if (word.endsWith(INPUT_LABEL) || word.endsWith(OUTPUT_LABEL)) return word;
        return word + (input ? INPUT_LABEL : OUTPUT_LABEL);
    }

    /** One card's USB descriptors, parsed once. Cards that are not USB cache an
     *  empty parse, which answers "no terminal" to everything. */
    private UsbAudioDescriptors cardDescriptors(int card) {
        return descriptors.computeIfAbsent(card,
                c -> new UsbAudioDescriptors(readDescriptors(c)));
    }

    /**
     * The raw descriptor blob of the USB device that carries {@code card}, or
     * null when there is none.
     *
     * <p>{@code /sys/class/sound/cardN} is a symlink INTO the device tree, and
     * the file sits at the USB device - above the interface the sound card
     * hangs off ({@code .../1-2/1-2:1.0/sound/card1}). Resolving the link and
     * walking up until a {@code descriptors} file appears finds it without
     * hard-coding that depth, and finds nothing at all for a PCI card, which is
     * the correct answer for one.
     */
    private byte[] readDescriptors(int card) {
        try {
            Path at = sysRoot.resolve("card" + card).toRealPath();
            for (int up = 0; up < DESCRIPTOR_SEARCH_DEPTH && at != null; up++) {
                Path file = at.resolve(DESCRIPTORS);
                if (Files.isReadable(file)) {
                    return Files.readAllBytes(file);
                }
                at = at.getParent();
            }
        } catch (IOException | RuntimeException ex) {
            if (log.isDebugEnabled()) {
                log.debug("No USB descriptors for card {}: {}", card, ex.toString());
            }
        }
        return null;
    }
}
