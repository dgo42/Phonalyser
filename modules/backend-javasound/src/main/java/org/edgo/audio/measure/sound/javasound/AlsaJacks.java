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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.log4j.Log4j2;

/**
 * Which of a card's sockets have a plug in them, read from ALSA's own jack
 * controls - {@code amixer -c <card> contents}:
 *
 * <pre>
 * numid=11,iface=CARD,name='Line - Input Jack'
 *   ; type=BOOLEAN,access=r-------,values=1
 *   : values=on
 * </pre>
 *
 * <p>The kernel builds one such read-only boolean per connector it can sense,
 * and names it after the terminal behind it - {@code "<port> - Input Jack"} /
 * {@code "<port> - Output Jack"}, the same words {@link AlsaPorts} composes
 * from the card's USB descriptors. That shared name is the join: the
 * descriptors say which socket a PCM device is wired to, this says whether
 * anything is plugged into it.
 *
 * <p><b>Silence is not "unplugged".</b> {@code amixer} is a separate package
 * (alsa-utils) and plenty of cards sense nothing at all, so an absent command,
 * an absent control and an unreadable value all answer {@code null} - "cannot
 * say" - and a caller must then list the device rather than hide it. Only an
 * explicit {@code off} means the socket is empty.
 *
 * <p><b>Nothing is cached across enumerations.</b> The card's ports are
 * hardware and do not move, but a plug is exactly the thing that changes while
 * the program runs: {@link #forget()} drops the readings so the next
 * enumeration re-runs the command, which is what makes the server's 2 s rescan
 * see a cable being pulled.
 */
@Log4j2
public class AlsaJacks {

    /** The control name in an {@code amixer contents} header line. */
    private static final Pattern CONTROL_NAME = Pattern.compile("name='([^']*)'");

    /** How long the command may take before it is killed - it reads one card's
     *  control list, which is memory the kernel already holds. */
    private static final int TIMEOUT_SECONDS = 5;

    /** Only connector controls are of interest, and they all end this way. */
    private static final String JACK_SUFFIX = " Jack";

    /** The value line of a control, as opposed to its {@code ;} type line. */
    private static final String VALUE_PREFIX = ":";

    private static final String VALUE_MARKER = "values=";

    private static final String VALUE_ON = "on";

    private static final String VALUE_OFF = "off";

    /** One reading per card, dropped wholesale by {@link #forget()}. A card
     *  whose command failed caches an empty map - the command is not retried
     *  for every device of the same enumeration. */
    private final Map<Integer, Map<String, Boolean>> byCard = new ConcurrentHashMap<>();

    /**
     * Whether {@code control} - a full jack control name such as
     * {@code "Line - Input Jack"} - reports a plug on {@code card}, or null
     * when this machine cannot say (see the class comment).
     */
    public Boolean connected(int card, String control) {
        if (card < 0 || control == null) return null;
        return byCard.computeIfAbsent(card, c -> parse(readContents(c))).get(control);
    }

    /** Drops every reading, so the next {@link #connected} query asks the
     *  hardware again. Called once per device enumeration. */
    public void forget() {
        byCard.clear();
    }

    /**
     * The jack controls in one {@code amixer contents} dump, by name.
     *
     * <p>Duplicate names are ORed: a card with two microphone sockets
     * publishes {@code 'Mic - Input Jack'} twice, the second one carrying
     * {@code index=1}, and nothing in the dump says which socket serves which
     * PCM device. "One of them has a plug" is the only claim the text supports,
     * and it errs towards listing a device rather than hiding one.
     */
    Map<String, Boolean> parse(List<String> lines) {
        Map<String, Boolean> jacks = new HashMap<>();
        String name = null;
        for (String raw : lines) {
            String line = raw.trim();
            Matcher m = CONTROL_NAME.matcher(line);
            if (m.find()) {
                String found = m.group(1);
                name = found.endsWith(JACK_SUFFIX) ? found : null;
                continue;
            }
            if (name == null || !line.startsWith(VALUE_PREFIX)) continue;
            int at = line.indexOf(VALUE_MARKER);
            if (at < 0) continue;
            String value = line.substring(at + VALUE_MARKER.length()).trim();
            if (value.equals(VALUE_ON)) {
                jacks.merge(name, Boolean.TRUE, (a, b) -> a || b);
            } else if (value.equals(VALUE_OFF)) {
                jacks.merge(name, Boolean.FALSE, (a, b) -> a || b);
            }
            name = null;
        }
        if (log.isDebugEnabled()) {
            log.debug("ALSA jacks: {}", jacks);
        }
        return jacks;
    }

    /**
     * Runs {@code amixer -c <card> contents} and returns its output, or no
     * lines when the command is missing, fails or hangs. Package-private and
     * separate from {@link #parse} so a test can drive the parsing without a
     * sound card - and without alsa-utils.
     *
     * <p>It is also where {@link AlsaVolumes} gets the dump it reads the card's
     * volume controls out of, so this backend starts that command in exactly
     * one place - and one fixture answers both readers.
     */
    List<String> readContents(int card) {
        List<String> lines = new ArrayList<>();
        Process process = null;
        try {
            process = new ProcessBuilder("amixer", "-c", String.valueOf(card), "contents")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null; ) {
                    lines.add(line);
                }
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return List.of();
            }
            if (process.exitValue() != 0) {
                return List.of();
            }
            return lines;
        } catch (Exception ex) {
            // Every host without alsa-utils lands here, which is why it is a
            // debug line and an empty answer: no jack detection, no hiding.
            if (log.isDebugEnabled()) {
                log.debug("amixer -c {} contents failed: {}", card, ex.toString());
            }
            if (process != null) process.destroyForcibly();
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            return List.of();
        }
    }
}
