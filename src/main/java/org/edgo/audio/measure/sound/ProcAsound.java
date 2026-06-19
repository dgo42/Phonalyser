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

import lombok.extern.log4j.Log4j2;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses {@code /proc/asound/card&lt;N&gt;/codec#&lt;M&gt;} on Linux to
 * discover the sample rates and bit depths each detected sound card's
 * codec actually supports.  The kernel's HD-Audio driver writes these
 * files; the format is a series of {@code Node 0x... [...]} sections,
 * each with its own {@code PCM:} block:
 *
 * <pre>{@code
 *   Node 0x02 [Audio Output] wcaps 0x40d: Stereo Digital
 *     ...
 *     PCM:
 *       rates [0x...]: 44100 48000 88200 96000 192000
 *       bits [0x...]: 16 20 24 32
 *       formats [0x...]: PCM
 *
 *   Node 0x09 [Audio Input] wcaps 0x10051b: Stereo Amp-In
 *     ...
 *     PCM:
 *       rates [0x...]: 44100 48000 96000 192000
 *       bits [0x...]: 16 20 24
 *       formats [0x...]: PCM
 * }</pre>
 *
 * <p>Used by {@link JavaSoundDeviceManager} as a hardware-truth cap on
 * the rates / bit depths shown for the JAVASOUND backend on Linux — a
 * Pulse / PipeWire mixer happily advertises 768 kHz / 32-bit even on a
 * 192 kHz card, but {@code /proc/asound} reports what the silicon
 * actually does.  Direction-aware: capture lookups read only
 * "Audio Input" sections, playback lookups read only "Audio Output"
 * sections, so a codec that supports 32-bit output but 24-bit input
 * doesn't have its 32-bit advertised for capture (and vice versa).
 *
 * <p>Results are queried lazily and cached for the JVM's lifetime
 * (codec capabilities don't change at runtime).  Returns empty arrays
 * on non-Linux hosts or when {@code /proc/asound} isn't readable.
 */
@Log4j2
public final class ProcAsound {

    private static volatile boolean scanned;
    private static volatile int[] inputRates   = new int[0];
    private static volatile int[] inputDepths  = new int[0];
    private static volatile int[] outputRates  = new int[0];
    private static volatile int[] outputDepths = new int[0];

    /** Matches {@code "Node 0xNN [Type Words] wcaps 0xMM: Description"}.
     *  Group 1 = bracketed node type ({@code Audio Output} / {@code Audio Input}).
     *  Group 2 = everything after the bracket (used to detect "Digital"
     *  S/PDIF nodes — their rates / bits don't reflect the analog
     *  ADC/DAC capability we actually want). */
    private static final Pattern NODE_HEADER =
            Pattern.compile("^Node\\s+0x[0-9a-fA-F]+\\s+\\[([^\\]]+)\\](.*)");

    /** Matches the USB-Audio format header — e.g. {@code "Format: S24_3LE"}.
     *  We pull the number that follows the leading {@code S}.  Works for
     *  {@code S16_LE}, {@code S24_LE}, {@code S24_3LE}, {@code S32_LE},
     *  {@code U8}, etc. */
    private static final Pattern USB_FORMAT =
            Pattern.compile("(?:Format|Format \\d+)\\s*:\\s*[SU](\\d+)");

    private ProcAsound() {}

    /** Sample rates (Hz) the hardware supports for the requested
     *  direction, or an empty array on non-Linux / unreadable
     *  {@code /proc/asound}.
     *
     *  @param output {@code true} for playback (DAC / Audio Output),
     *                {@code false} for capture (ADC / Audio Input). */
    public static synchronized int[] hardwareRates(boolean output) {
        if (!scanned) scan();
        return (output ? outputRates : inputRates).clone();
    }

    /** Bit depths supported by the hardware for the requested
     *  direction, or an empty array on non-Linux / unreadable
     *  {@code /proc/asound}. */
    public static synchronized int[] hardwareBitDepths(boolean output) {
        if (!scanned) scan();
        return (output ? outputDepths : inputDepths).clone();
    }

    private enum NodeKind { OUTPUT, INPUT, OTHER }

    private static void scan() {
        scanned = true;
        Path procAsound = Paths.get("/proc/asound");
        if (!Files.isDirectory(procAsound)) return;
        TreeSet<Integer> inR = new TreeSet<>(), inB  = new TreeSet<>();
        TreeSet<Integer> outR = new TreeSet<>(), outB = new TreeSet<>();
        try (DirectoryStream<Path> cards = Files.newDirectoryStream(procAsound, "card*")) {
            for (Path cardDir : cards) {
                if (!Files.isDirectory(cardDir)) continue;
                // HDA codecs: rates/bits under Audio Input/Output Node sections.
                try (DirectoryStream<Path> codecs = Files.newDirectoryStream(cardDir, "codec#*")) {
                    for (Path codec : codecs) {
                        parseCodec(codec, inR, inB, outR, outB);
                    }
                } catch (IOException ex) {
                    log.debug("Could not list codecs under {}: {}", cardDir, ex.getMessage());
                }
                // USB-Audio: rates/bits under Playback/Capture sections.
                try (DirectoryStream<Path> streams = Files.newDirectoryStream(cardDir, "stream*")) {
                    for (Path stream : streams) {
                        parseUsbStream(stream, inR, inB, outR, outB);
                    }
                } catch (IOException ex) {
                    log.debug("Could not list streams under {}: {}", cardDir, ex.getMessage());
                }
            }
        } catch (IOException ex) {
            log.debug("Could not scan /proc/asound: {}", ex.getMessage());
        }
        inputRates   = toArray(inR);
        inputDepths  = toArray(inB);
        outputRates  = toArray(outR);
        outputDepths = toArray(outB);
        log.info("/proc/asound hardware caps: in.rates={}, in.bits={}, out.rates={}, out.bits={}",
                inR, inB, outR, outB);
    }

    /** Parses a USB-Audio {@code /proc/asound/cardN/streamN} file.
     *
     *  <p>Layout walks Playback: / Capture: top-level sections, each
     *  with {@code Interface N / Altset N} sub-sections that declare
     *  {@code Format: SXX_LE} and {@code Rates: r1, r2, ...}.
     */
    private static void parseUsbStream(Path stream,
                                       TreeSet<Integer> inR, TreeSet<Integer> inB,
                                       TreeSet<Integer> outR, TreeSet<Integer> outB) {
        List<String> lines;
        try {
            lines = Files.readAllLines(stream);
        } catch (IOException ex) {
            log.debug("Could not read {}: {}", stream, ex.getMessage());
            return;
        }
        NodeKind kind = NodeKind.OTHER;
        for (String raw : lines) {
            String line = raw.trim();
            // Top-level section headers are unindented in the source file
            // but we trimmed — match by suffix colon and exact label.
            if (line.equals("Playback:"))      { kind = NodeKind.OUTPUT; continue; }
            else if (line.equals("Capture:")) { kind = NodeKind.INPUT;  continue; }
            if (kind == NodeKind.OTHER) continue;

            Matcher fm = USB_FORMAT.matcher(line);
            if (fm.find()) {
                try {
                    int bits = Integer.parseInt(fm.group(1));
                    if (bits > 0) (kind == NodeKind.OUTPUT ? outB : inB).add(bits);
                } catch (NumberFormatException ignored) { /* skip */ }
                continue;
            }
            if (line.startsWith("Rates")) {
                // "Rates: 44100, 48000" — strip the prefix, allow comma separators.
                int colon = line.indexOf(':');
                if (colon < 0 || colon + 1 >= line.length()) continue;
                for (String tok : line.substring(colon + 1).split("[,\\s]+")) {
                    if (tok.isEmpty()) continue;
                    try {
                        int v = Integer.parseInt(tok);
                        if (v > 0) (kind == NodeKind.OUTPUT ? outR : inR).add(v);
                    } catch (NumberFormatException ignored) { /* skip */ }
                }
            }
        }
    }

    /** Walk the file once, tracking which {@code Node} section we are
     *  currently inside.  PCM rates / bits found while the section type
     *  is "Audio Input" or "Audio Output" — <em>and</em> the node is
     *  not the digital S/PDIF variant — go to the matching sets.  Pin
     *  Complex, Vendor Defined and digital (S/PDIF) sections are
     *  ignored: their PCM caps describe what the bus can carry, not
     *  what the analog DAC/ADC actually delivers, and a codec like
     *  the ALC262 advertises 32 bit / 192 kHz over S/PDIF while its
     *  analog inputs top out at 20 bit / 96 kHz. */
    private static void parseCodec(Path codec,
                                   TreeSet<Integer> inR, TreeSet<Integer> inB,
                                   TreeSet<Integer> outR, TreeSet<Integer> outB) {
        List<String> lines;
        try {
            lines = Files.readAllLines(codec);
        } catch (IOException ex) {
            log.debug("Could not read {}: {}", codec, ex.getMessage());
            return;
        }
        NodeKind kind = NodeKind.OTHER;
        for (String raw : lines) {
            String line = raw.trim();
            Matcher m = NODE_HEADER.matcher(line);
            if (m.find()) {
                String t    = m.group(1).toLowerCase();
                String desc = m.group(2).toLowerCase();
                boolean digital = desc.contains("digital");
                if (digital) {
                    kind = NodeKind.OTHER;
                } else if (t.contains("audio output")) {
                    kind = NodeKind.OUTPUT;
                } else if (t.contains("audio input")) {
                    kind = NodeKind.INPUT;
                } else {
                    kind = NodeKind.OTHER;
                }
                continue;
            }
            if (kind == NodeKind.OTHER) continue;
            if (line.startsWith("rates")) {
                extractNumbersAfter(line, kind == NodeKind.OUTPUT ? outR : inR);
            } else if (line.startsWith("bits")) {
                extractNumbersAfter(line, kind == NodeKind.OUTPUT ? outB : inB);
            }
        }
    }

    /**
     * Codec lines look like {@code rates [0x5e0]: 44100 48000 88200 96000 192000}.
     * Skip the bracketed hex flag, parse every integer that follows.
     */
    private static void extractNumbersAfter(String line, TreeSet<Integer> out) {
        int colon  = line.indexOf(':');
        int rbrack = line.indexOf(']');
        int start  = Math.max(colon, rbrack);
        if (start < 0 || start + 1 >= line.length()) return;
        for (String tok : line.substring(start + 1).trim().split("\\s+")) {
            try {
                int v = Integer.parseInt(tok);
                if (v > 0) out.add(v);
            } catch (NumberFormatException ignored) { /* skip non-numeric tokens */ }
        }
    }

    private static int[] toArray(TreeSet<Integer> s) {
        return s.stream().mapToInt(Integer::intValue).toArray();
    }
}
