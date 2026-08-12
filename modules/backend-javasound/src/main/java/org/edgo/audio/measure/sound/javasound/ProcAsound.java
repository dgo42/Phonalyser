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

import lombok.extern.log4j.Log4j2;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads {@code /proc/asound} to discover what each detected sound card's
 * hardware actually supports - the sample rates and bit depths of the silicon,
 * not of the mixer in front of it.
 *
 * <p>Two layouts are understood, because the kernel writes two:
 *
 * <ul>
 *   <li><b>USB audio</b> - {@code /proc/asound/card&lt;N&gt;/stream&lt;M&gt;},
 *       with {@code Playback:} / {@code Capture:} sections whose
 *       {@code Interface / Altset} sub-sections declare {@code Format: SXX_LE}
 *       and an exact {@code Rates: ...} list. This is the precise case: the
 *       list is per direction and is what the device enumerated.</li>
 *   <li><b>HD-Audio</b> - {@code /proc/asound/card&lt;N&gt;/codec#&lt;M&gt;},
 *       a series of {@code Node 0x.. [Audio Output|Audio Input]} sections each
 *       with a {@code PCM:} block of {@code rates} and {@code bits}.</li>
 * </ul>
 *
 * <p><b>ANSWERS ARE PER CARD.</b> Reading the whole of {@code /proc/asound} and
 * merging it - which this class used to do - gives every device on the machine
 * the union of every card's capabilities. On a bench with a 48 kHz onboard
 * codec beside a 768 kHz USB interface that answer is wrong for both of them.
 * The ALSA address in the JavaSound mixer name ({@code "CB5 [plughw:1,1]"})
 * names the card, so {@link #cardIndexOf} turns it into the {@code cardN} whose
 * own files are then read.
 *
 * <p><b>"UNKNOWN" IS NOT "NOTHING".</b> A card with neither layout - a legacy
 * PCI codec such as the Ensoniq ES1371 - is reported as
 * {@link CardCaps#known() not known}, which the caller must treat as "this
 * class cannot say", never as "supports no rates". Direction-aware throughout:
 * a codec with 32-bit output and 24-bit input does not get its 32 bits
 * advertised for capture.
 *
 * <p>Results are cached for the JVM's lifetime (hardware capabilities do not
 * change at runtime) and every query answers empty on non-Linux hosts or when
 * {@code /proc/asound} is not readable.
 *
 * <p>It also answers the two questions the ALSA address itself holds -
 * {@link #cardIndexOf} / {@link #deviceIndexOf} - and, for a USB card, which
 * AudioStreaming interface serves a PCM device ({@link #streamInterface}).
 * Those are the same files by the same reader, and they are what
 * {@link AlsaPorts} turns into a port's real name.
 */
@Log4j2
public final class ProcAsound {

    /** Matches {@code "Node 0xNN [Type Words] wcaps 0xMM: Description"}.
     *  Group 1 = bracketed node type ({@code Audio Output} / {@code Audio Input}).
     *  Group 2 = everything after the bracket (used to detect "Digital"
     *  S/PDIF nodes - their rates / bits don't reflect the analog
     *  ADC/DAC capability we actually want). */
    private static final Pattern NODE_HEADER =
            Pattern.compile("^Node\\s+0x[0-9a-fA-F]+\\s+\\[([^\\]]+)\\](.*)");

    /** Matches the USB-Audio format header - e.g. {@code "Format: S24_3LE"}.
     *  We pull the number that follows the leading {@code S}.  Works for
     *  {@code S16_LE}, {@code S24_LE}, {@code S24_3LE}, {@code S32_LE},
     *  {@code U8}, etc. */
    private static final Pattern USB_FORMAT =
            Pattern.compile("(?:Format|Format \\d+)\\s*:\\s*[SU](\\d+)");

    /** Matches the ALSA address JavaSound puts in a mixer name -
     *  {@code "CB5 [plughw:1,1]"} or {@code "[hw:0,0]"}. Group 1 is the CARD
     *  index, which is what {@code /proc/asound/cardN} is keyed on; group 2 is
     *  the DEVICE (PCM) number after the comma, which selects one of the card's
     *  PCM devices and does not change the card's capability files - but does
     *  name which physical port the device is wired to
     *  ({@link #streamInterface}). */
    private static final Pattern ALSA_ADDRESS =
            Pattern.compile("\\[(?:plug)?hw:(\\d+)(?:,(\\d+))?");

    /** Matches a USB stream file's {@code "Interface 2"} sub-section header -
     *  the USB AudioStreaming interface number that serves this direction. */
    private static final Pattern USB_INTERFACE = Pattern.compile("^Interface\\s+(\\d+)");

    /** Matches one card line of {@code /proc/asound/cards}, which the kernel
     *  writes as {@code "%2i [%-15s]: %s - %s"} - index, the 15-char-padded
     *  short id, then the DRIVER and the card's SHORTNAME.  Group 1 is the
     *  index, group 2 is everything after {@code "]: "}, which the caller
     *  splits on its FIRST {@code " - "}: the driver may contain spaces of its
     *  own ({@code "ThinkPad EC"}), so only the first separator is structural.
     *  The line following each of these is an indented longname; it carries no
     *  leading index and therefore does not match. */
    private static final Pattern CARDS_LINE =
            Pattern.compile("^\\s*(\\d+)\\s*\\[[^\\]]*\\]\\s*:\\s*(.+?)\\s*$");

    /** The separator between the driver and the shortname; only the FIRST one
     *  in the line is structural. */
    private static final String DRIVER_SEPARATOR = " - ";

    /** How wide the kernel's own driver field is before it truncates. */
    private static final int DRIVER_FIELD_WIDTH = 15;

    /** The shortest token that can carry meaning when comparing a driver with a
     *  shortname - below this they are initials and bus letters. */
    private static final int SIGNIFICANT_TOKEN_LENGTH = 3;

    /** Tokens that say what KIND of thing a card is rather than which one, so
     *  finding one on both sides means nothing.  Without this, every
     *  {@code *-audio} driver would look like it repeated its card's name. */
    private static final Set<String> GENERIC_TOKENS = Set.of(
            "audio", "sound", "card", "hdmi", "usb", "pcm", "device", "dev");

    /** Drivers that name a BUS or a device class rather than a product.  They
     *  are prefixed to cards they say nothing about, so they are dropped from
     *  the display even when they share no word with the shortname.  Compared
     *  lowercase, and an entry also matches the form the kernel's 15-character
     *  driver field would have truncated it to. */
    private static final Set<String> BUS_DRIVERS = Set.of(
            "hda-intel", "usb-audio", "simple-card", "audio-hdmi");

    /** The {@code --dump-hw-params} lines of interest: {@code FORMAT:} tokens
     *  ({@code S16_LE S24_3LE S32_LE ...} - the digits are the significant
     *  bits) and {@code RATE:}, either discrete values or a
     *  {@code [min max]} range. */
    private static final String  HW_FORMAT_PREFIX = "FORMAT:";
    private static final Pattern HW_FORMAT_TOKEN  = Pattern.compile("[SU](\\d+)_?");
    private static final String  HW_RATE_PREFIX   = "RATE:";
    private static final Pattern HW_RATE_RANGE    = Pattern.compile("\\[(\\d+)\\s+(\\d+)\\]");
    private static final Pattern HW_RATE_VALUE    = Pattern.compile("(\\d+)");
    /** The ladder a continuous {@code RATE: [min max]} range is sampled on -
     *  the same candidates every open-probing backend walks. */
    private static final int[] HW_PROBE_RATES = {8000, 11025, 16000, 22050, 32000, 44100,
            48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000};
    /** How long the dump tool may take before it is killed. */
    private static final int HW_PARAMS_TIMEOUT_SECONDS = 5;

    /** One hw_params answer per {@code card:device}, for this instance's
     *  lifetime - like {@code cardCache}, hardware capabilities do not change
     *  at runtime, and a fresh instance probes fresh. */
    private final Map<String, CardCaps> hwParamsCache = new ConcurrentHashMap<>();

    private enum NodeKind { OUTPUT, INPUT, OTHER }

    /**
     * One card's capabilities, per direction.
     *
     * <p>{@code known} carries the distinction that matters: a card whose
     * layout this class understands answers {@code known = true} even when a
     * direction is empty, because a capture-only card really has no playback
     * rates. A card it does not understand answers {@code known = false},
     * meaning "cannot say" - and the caller must not read the empty arrays as
     * "supports nothing".
     */
    public record CardCaps(int[] inputRates, int[] inputDepths,
                           int[] outputRates, int[] outputDepths, boolean known) {

        /** The answer for a card with neither a USB stream nor an HDA codec file. */
        public static final CardCaps UNKNOWN =
                new CardCaps(new int[0], new int[0], new int[0], new int[0], false);

        public int[] rates(boolean output)  { return (output ? outputRates : inputRates).clone(); }
        public int[] depths(boolean output) { return (output ? outputDepths : inputDepths).clone(); }
    }

    /** The tree the reads come from - {@code /proc/asound} in production, a
     *  fixture directory under test. */
    private final Path root;

    /** Per-card answers, keyed by ALSA card index. */
    private final Map<Integer, CardCaps> cardCache = new ConcurrentHashMap<>();

    /** {@code <root>/cards} parsed into card index -> full card name, read once
     *  on the first ask and kept for this instance's lifetime like every other
     *  answer here. Empty when the file is absent or unreadable, which is the
     *  normal case off Linux; {@code null} until the first read. */
    private volatile Map<Integer, String> cardNames;

    /** Reads the real {@code /proc/asound}. */
    public ProcAsound() {
        this(Paths.get("/proc/asound"));
    }

    /** Reads {@code root} instead - the seam the tests drive a fixture tree through,
     *  which is what makes this parser testable on a machine that has no ALSA. */
    public ProcAsound(Path root) {
        this.root = root;
    }

    /**
     * The ALSA card index named in a JavaSound mixer name, or -1 when the name
     * carries no {@code [plughw:N,M]} address (every non-ALSA mixer, and Java's
     * own "Port" mixers).
     */
    public int cardIndexOf(String mixerName) {
        return addressPart(mixerName, 1);
    }

    /**
     * The ALSA PCM DEVICE number named in a JavaSound mixer name - the {@code 1}
     * of {@code [plughw:1,1]} - or -1 when the name carries no address or names
     * a card without a device ({@code [hw:1]}, Java's own "Port" mixers).
     *
     * <p>It is the card's port that this number selects: a USB interface with a
     * line pair and a microphone pair publishes one PCM device per pair, and
     * {@link #streamInterface} turns the number into the USB interface that
     * serves it.
     */
    public int deviceIndexOf(String mixerName) {
        return addressPart(mixerName, 2);
    }

    /** One capture group of the {@code [plughw:C,D]} address, or -1 when the
     *  name has no address or that part of it is absent. */
    private int addressPart(String mixerName, int group) {
        if (mixerName == null) return -1;
        Matcher m = ALSA_ADDRESS.matcher(mixerName);
        if (!m.find()) return -1;
        String part = m.group(group);
        if (part == null) return -1;
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /**
     * The full product name of card {@code index} as {@code <root>/cards} states
     * it - {@code "SMSL USB AUDIO"} for a line reading
     * {@code " 0 [AUDIO          ]: USB-Audio - SMSL USB AUDIO"} - or an empty
     * string when the file has no such card, cannot be read, or does not exist
     * at all (every non-Linux host).
     *
     * <p>The point of asking is that the name ALSA puts in the JavaSound mixer
     * name is the SHORT id from the brackets, which is an identifier and not a
     * product: two different interfaces can both call themselves {@code AUDIO},
     * and neither tells an operator which box on the bench it is. The cards file
     * is the one place the kernel keeps the string the device reported.
     */
    public String cardName(int index) {
        if (index < 0) return "";
        return cardNames().getOrDefault(index, "");
    }

    /** The parsed cards file, read on first use. */
    private Map<Integer, String> cardNames() {
        Map<Integer, String> names = cardNames;
        if (names == null) {
            synchronized (this) {
                names = cardNames;
                if (names == null) {
                    names = readCards(root.resolve("cards"));
                    cardNames = names;
                }
            }
        }
        return names;
    }

    /**
     * Parses a {@code cards} file. Package-private and taking the file outright
     * so the tests can drive it against a fixture on any OS.
     *
     * <p>Only the card lines are read. Each is followed by an indented long
     * description that repeats the name with the bus address appended, and it is
     * skipped: it has no leading index, so it simply does not match.
     */
    Map<Integer, String> readCards(Path cardsFile) {
        Map<Integer, String> names = new HashMap<>();
        for (String line : readLines(cardsFile)) {
            Matcher m = CARDS_LINE.matcher(line);
            if (!m.matches()) continue;
            try {
                String rest = m.group(2);
                int split = rest.indexOf(DRIVER_SEPARATOR);
                String label = split < 0 ? rest.trim()
                        : cardLabel(rest.substring(0, split),
                                    rest.substring(split + DRIVER_SEPARATOR.length()));
                if (!label.isEmpty()) {
                    names.put(Integer.parseInt(m.group(1)), label);
                }
            } catch (NumberFormatException ignored) { /* not a card line */ }
        }
        if (!names.isEmpty() && log.isInfoEnabled()) {
            log.info("Card names from {}: {}", cardsFile, names);
        }
        return names;
    }

    /**
     * What to SHOW for a card the kernel describes as {@code driver - shortname}.
     *
     * <p>The driver is worth showing only when it adds something. Often it does
     * not: it repeats the shortname, or it names the bus the card hangs on, and
     * printing it then costs width and tells the operator nothing. But dropping
     * it always is wrong too - {@code ICE1712} and {@code AV200} are the chips
     * that distinguish two otherwise similarly named cards, and an operator with
     * several interfaces needs them.
     *
     * <p>So the driver is dropped when, and only when, one of three things
     * holds, and kept otherwise:
     * <ul>
     *   <li>it IS the shortname, once punctuation and case are taken out;</li>
     *   <li>it shares a MEANINGFUL word with the shortname - meaningful being
     *       at least {@value #SIGNIFICANT_TOKEN_LENGTH} characters and not one
     *       of the words that merely say "this is a sound card". A substring
     *       test cannot do this job: {@code CMI8738-MC6} is not a substring of
     *       {@code C-Media CMI8738}, yet the two plainly name the same chip;</li>
     *   <li>it is a known bus or class driver.</li>
     * </ul>
     *
     * <p>Package-private so the rule can be driven with the pairs real machines
     * produce, on a host that has none of the hardware.
     */
    String cardLabel(String driver, String shortname) {
        String name = shortname == null ? "" : shortname.trim();
        String drv  = driver == null ? "" : driver.trim();
        if (drv.isEmpty()) return name;
        if (name.isEmpty()) return drv;
        if (normalized(drv).equals(normalized(name))
                || sharesSignificantToken(drv, name)
                || isBusDriver(drv)) {
            return name;
        }
        return drv + DRIVER_SEPARATOR + name;
    }

    /** Case and punctuation taken out, for the "it IS the shortname" test. */
    private String normalized(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Whether the two strings name the same thing in at least one word that
     *  carries meaning - the test that catches {@code C-Media CMI8738} against
     *  {@code CMI8738-MC6}, which no substring comparison would. */
    private boolean sharesSignificantToken(String driver, String shortname) {
        Set<String> driverTokens = significantTokens(driver);
        for (String token : significantTokens(shortname)) {
            if (driverTokens.contains(token)) return true;
        }
        return false;
    }

    /** The words of {@code s} that can identify a product: lowercase, split on
     *  everything that is not a letter or a digit, short ones and the
     *  {@link #GENERIC_TOKENS generic} ones dropped. */
    private Set<String> significantTokens(String s) {
        Set<String> tokens = new HashSet<>();
        for (String token : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.length() >= SIGNIFICANT_TOKEN_LENGTH && !GENERIC_TOKENS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /** Whether {@code driver} names a bus or a device class rather than a
     *  product - including the form the kernel's 15-character driver field
     *  would have cut it down to. */
    private boolean isBusDriver(String driver) {
        String lower = driver.toLowerCase(Locale.ROOT);
        for (String known : BUS_DRIVERS) {
            if (lower.equals(known)) return true;
            if (known.length() > DRIVER_FIELD_WIDTH
                    && lower.equals(known.substring(0, DRIVER_FIELD_WIDTH))) {
                return true;
            }
        }
        return false;
    }

    /** What {@code <root>/card<card>} says, cached for this instance's lifetime. */
    public CardCaps cardCaps(int card) {
        if (card < 0) return CardCaps.UNKNOWN;
        return cardCache.computeIfAbsent(card, c -> {
            CardCaps caps = readCard(root.resolve("card" + c));
            // Named the way the device listing names it, so a log line and the
            // report cannot call the same card two different things.
            if (caps.known() && log.isInfoEnabled()) {
                String named = cardName(c);
                log.info("Card {} ({}) caps: in.rates={}, in.depths={}, out.rates={}, out.depths={}",
                        c, named.isEmpty() ? "unnamed" : named,
                        caps.inputRates().length, caps.inputDepths().length,
                        caps.outputRates().length, caps.outputDepths().length);
            }
            return caps;
        });
    }

    /** The capabilities of the card named in {@code mixerName} - the whole
     *  lookup in one call, for the caller that only has a mixer. */
    public CardCaps capsForMixer(String mixerName) {
        return cardCaps(cardIndexOf(mixerName));
    }

    /**
     * The capabilities of the PCM device named in {@code mixerName}, read from
     * the {@code hw:} device's OWN hw_params ranges - the fallback for a card
     * {@code /proc/asound} cannot describe (a PCI codec: no USB stream file,
     * no HDA codec file).  {@code aplay}/{@code arecord --dump-hw-params}
     * opens the DIRECT {@code hw:} device, so the plug layer's resample-
     * anything answer never enters; alsa-utils is the same dependency the
     * volume discipline already leans on.  A busy device, a missing tool or a
     * non-Linux host answer {@link CardCaps#UNKNOWN}.
     */
    public CardCaps hwParamsCaps(String mixerName) {
        int card = cardIndexOf(mixerName);
        if (card < 0 || !Files.isDirectory(root)) {
            return CardCaps.UNKNOWN;
        }
        int device = Math.max(deviceIndexOf(mixerName), 0);
        return hwParamsCache.computeIfAbsent(card + ":" + device,
                k -> probeHwParams(card, device));
    }

    private CardCaps probeHwParams(int card, int device) {
        int[][] out = parseHwParams(dumpHwParams(card, device, true));
        int[][] in  = parseHwParams(dumpHwParams(card, device, false));
        boolean known = (out[0].length > 0 && out[1].length > 0)
                || (in[0].length > 0 && in[1].length > 0);
        if (known && log.isInfoEnabled()) {
            log.info("Card {} device {} answered through hw_params (no /proc/asound "
                    + "capability file for it)", card, device);
        }
        return known ? new CardCaps(in[0], in[1], out[0], out[1], true) : CardCaps.UNKNOWN;
    }

    /**
     * One direction's {@code --dump-hw-params} text -> {@code [rates, depths]}.
     * {@code FORMAT:} tokens carry the depths ({@code S24_3LE} and
     * {@code S24_LE} are both 24 significant bits - packed vs in-container is
     * the transport's business, the capability is exact); {@code RATE:} is
     * either a discrete list or a {@code [min max]} range, a range being
     * intersected with the standard rate ladder.
     */
    int[][] parseHwParams(List<String> lines) {
        TreeSet<Integer> depths = new TreeSet<>();
        TreeSet<Integer> rates  = new TreeSet<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith(HW_FORMAT_PREFIX)) {
                Matcher m = HW_FORMAT_TOKEN.matcher(line);
                while (m.find()) {
                    depths.add(Integer.parseInt(m.group(1)));
                }
            } else if (line.startsWith(HW_RATE_PREFIX)) {
                Matcher range = HW_RATE_RANGE.matcher(line);
                if (range.find()) {
                    int min = Integer.parseInt(range.group(1));
                    int max = Integer.parseInt(range.group(2));
                    for (int rate : HW_PROBE_RATES) {
                        if (rate >= min && rate <= max) {
                            rates.add(rate);
                        }
                    }
                } else {
                    Matcher discrete = HW_RATE_VALUE.matcher(line.substring(HW_RATE_PREFIX.length()));
                    while (discrete.find()) {
                        rates.add(Integer.parseInt(discrete.group(1)));
                    }
                }
            }
        }
        return new int[][] {
                rates.stream().mapToInt(Integer::intValue).toArray(),
                depths.stream().mapToInt(Integer::intValue).toArray(),
        };
    }

    /** Runs {@code aplay}/{@code arecord --dump-hw-params} against the direct
     *  {@code hw:card,device} address and returns whatever it printed (the
     *  dump goes to stderr, merged here).  {@code -d 1} bounds the run when a
     *  device actually accepts the probe file's format; the watchdog kills a
     *  hung tool.  Empty on any trouble - the caller treats that as
     *  "cannot say". */
    List<String> dumpHwParams(int card, int device, boolean output) {
        List<String> lines = new ArrayList<>();
        Process process = null;
        try {
            process = new ProcessBuilder(output ? "aplay" : "arecord",
                    "-D", "hw:" + card + "," + device,
                    "--dump-hw-params", "-d", "1",
                    output ? "/dev/zero" : "/dev/null")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null; ) {
                    lines.add(line);
                }
            }
            if (!process.waitFor(HW_PARAMS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (IOException e) {
            if (log.isDebugEnabled()) {
                log.debug("hw_params dump unavailable for hw:{},{}: {}", card, device, e.toString());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
        return lines;
    }

    /**
     * Reads ONE card directory. Package-private and taking the directory
     * outright so the tests can drive it against a fixture on any OS.
     */
    CardCaps readCard(Path cardDir) {
        if (!Files.isDirectory(cardDir)) return CardCaps.UNKNOWN;
        TreeSet<Integer> inR = new TreeSet<>(), inB = new TreeSet<>();
        TreeSet<Integer> outR = new TreeSet<>(), outB = new TreeSet<>();
        boolean known = false;
        try (DirectoryStream<Path> streams = Files.newDirectoryStream(cardDir, "stream*")) {
            for (Path stream : streams) {
                known = true;                   // USB: exact per-direction rate lists
                parseUsbStream(stream, inR, inB, outR, outB);
            }
        } catch (IOException ex) {
            log.debug("Could not list streams under {}: {}", cardDir, ex.getMessage());
        }
        try (DirectoryStream<Path> codecs = Files.newDirectoryStream(cardDir, "codec#*")) {
            for (Path codec : codecs) {
                known = true;                   // HD-Audio
                parseCodec(codec, inR, inB, outR, outB);
            }
        } catch (IOException ex) {
            log.debug("Could not list codecs under {}: {}", cardDir, ex.getMessage());
        }
        if (!known) {
            log.debug("{} has neither stream* nor codec#* - capabilities unknown", cardDir);
            return CardCaps.UNKNOWN;
        }
        CardCaps caps = new CardCaps(toArray(inR), toArray(inB), toArray(outR), toArray(outB), true);
        log.info("{} caps: in.rates={}, in.bits={}, out.rates={}, out.bits={}",
                cardDir.getFileName(), inR, inB, outR, outB);
        return caps;
    }

    /** Parses a USB-Audio {@code /proc/asound/cardN/streamN} file.
     *
     *  <p>Layout walks Playback: / Capture: top-level sections, each with
     *  {@code Interface N / Altset N} sub-sections that declare
     *  {@code Format: SXX_LE} and {@code Rates: r1, r2, ...}.
     */
    private void parseUsbStream(Path stream,
                                TreeSet<Integer> inR, TreeSet<Integer> inB,
                                TreeSet<Integer> outR, TreeSet<Integer> outB) {
        NodeKind kind = NodeKind.OTHER;
        for (String raw : readLines(stream)) {
            String line = raw.trim();
            NodeKind section = sectionHeader(line);
            if (section != null) { kind = section; continue; }
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
                // "Rates: 44100, 48000" - strip the prefix, allow comma separators.
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
     *  is "Audio Input" or "Audio Output" - <em>and</em> the node is
     *  not the digital S/PDIF variant - go to the matching sets.  Pin
     *  Complex, Vendor Defined and digital (S/PDIF) sections are
     *  ignored: their PCM caps describe what the bus can carry, not
     *  what the analog DAC/ADC actually delivers, and a codec like
     *  the ALC262 advertises 32 bit / 192 kHz over S/PDIF while its
     *  analog inputs top out at 20 bit / 96 kHz. */
    private void parseCodec(Path codec,
                            TreeSet<Integer> inR, TreeSet<Integer> inB,
                            TreeSet<Integer> outR, TreeSet<Integer> outB) {
        NodeKind kind = NodeKind.OTHER;
        for (String raw : readLines(codec)) {
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
     * The USB AudioStreaming INTERFACE that serves one PCM device in one
     * direction - {@code /proc/asound/card<card>/stream<device>}'s
     * {@code "Interface 2"} under its {@code Capture:} section - or -1 when the
     * card is not USB, has no such device, or does not work in that direction.
     *
     * <p>The kernel names that file after the PCM device index
     * ({@code stream->pcm_index}), which is exactly the {@code D} of
     * {@code [plughw:C,D]}, so the address in a JavaSound mixer name resolves
     * straight to it. The interface number is what the card's USB descriptors
     * key their terminals on, and the terminal is what carries the port's real
     * name (Line, Mic, Headphone) - so this is the first half of the answer to
     * "which socket is this device?".
     *
     * <p>The first interface in the section wins: a direction's altsets all
     * belong to one AudioStreaming interface, and they are listed one after
     * another under the same header.
     */
    public int streamInterface(int card, int device, boolean input) {
        if (card < 0 || device < 0) return -1;
        Path stream = root.resolve("card" + card).resolve("stream" + device);
        NodeKind wanted = input ? NodeKind.INPUT : NodeKind.OUTPUT;
        NodeKind kind = NodeKind.OTHER;
        for (String raw : readLines(stream)) {
            String line = raw.trim();
            NodeKind section = sectionHeader(line);
            if (section != null) { kind = section; continue; }
            if (kind != wanted) continue;
            Matcher m = USB_INTERFACE.matcher(line);
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (NumberFormatException ex) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /** The direction a {@code "Playback:"} / {@code "Capture:"} header opens,
     *  or null for every other line. The headers are unindented in the file
     *  itself; callers match them on the trimmed line. */
    private NodeKind sectionHeader(String line) {
        if (line.equals("Playback:")) return NodeKind.OUTPUT;
        if (line.equals("Capture:"))  return NodeKind.INPUT;
        return null;
    }

    /** Every line of a {@code /proc} file, or none when it cannot be read -
     *  an absent file is this class's normal answer ("cannot say"), not an
     *  error, on every host that is not the Linux box the card is in. */
    private List<String> readLines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException ex) {
            log.debug("Could not read {}: {}", file, ex.getMessage());
            return List.of();
        }
    }

    /**
     * Codec lines look like {@code rates [0x5e0]: 44100 48000 88200 96000 192000}.
     * Skip the bracketed hex flag, parse every integer that follows.
     */
    private void extractNumbersAfter(String line, TreeSet<Integer> out) {
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

    private int[] toArray(TreeSet<Integer> s) {
        return s.stream().mapToInt(Integer::intValue).toArray();
    }
}
