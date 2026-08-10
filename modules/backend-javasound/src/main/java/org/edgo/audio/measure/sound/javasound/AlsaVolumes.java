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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import lombok.extern.log4j.Log4j2;

/**
 * Puts the OPENED device's own volume controls at unity, so that nothing
 * between the socket and the converter can quietly scale a measurement.
 *
 * <p>A Linux card's mixer sits INSIDE the calibrated chain. ALSA hands
 * JavaSound the {@code plughw} device, which honours the card's feature-unit
 * volumes, and those are hardware gain: with a USB interface left at -29 dB, a
 * bench measured 1 V as 34 mV while its calibration values were perfect and the
 * same card on a host whose mixer sat at unity measured it right. Nothing in
 * the reading says the mixer did it - which is exactly why the mixer must never
 * be allowed to sit anywhere but 0 dB when a measurement device is opened.
 *
 * <p><b>Only the opened device's own controls, and only the ones this can name
 * with certainty.</b> The card publishes one volume per socket
 * ({@code "Line Capture Volume"}, {@code "Headphone Playback Volume"}) plus,
 * often, one for the PCM stream itself, and {@link AlsaPorts} already knows
 * which socket a {@code plughw:card,device} address is wired to. A control
 * whose name carries that socket's word - or the stream's, which every sample
 * of this device passes through whatever socket it came from - is this
 * device's; every other one belongs to a port nobody asked about and is left
 * exactly as the operator set it. Turning a headphone socket up to full while
 * somebody measures a line output is the harm this rule exists to prevent.
 *
 * <p><b>Unity is 0 dB, not maximum.</b> The value that reads 0 dB comes from
 * the control's own dB scale, so a capture gain whose scale starts AT 0 dB
 * (+19.5 dB at its top, as USB microphone inputs are) is set to its MINIMUM,
 * where a "100 percent" would have added 19.5 dB of gain to a calibrated
 * chain. Only a control that publishes no dB scale at all is set to full
 * scale, which is what {@code alsamixer} would call 100 percent.
 *
 * <p><b>Every failure is loud and none of them stops an open.</b> No
 * alsa-utils, a port that cannot be named, a name the card publishes twice, a
 * write the driver refuses - each says so once, in one line that names what was
 * NOT pinned and what that costs, and the capture or the tone then starts
 * anyway. A control already at unity says nothing at all.
 *
 * <p>Linux only: every other host answers immediately, because there is no
 * {@code amixer} there and the mixer this is about is not in the chain.
 */
@Log4j2
public class AlsaVolumes {

    /** How a control names the direction it sits in - ALSA's own two words, and
     *  the ones {@code amixer} prints. */
    private static final String CAPTURE = "Capture";
    private static final String PLAYBACK = "Playback";

    /** What a volume control's name ends with, after the direction. */
    private static final String VOLUME = "Volume";

    /** The control that belongs to the PCM STREAM rather than to a socket - the
     *  feature unit every sample of the opened device passes through, whichever
     *  socket it came from. Some cards name it {@code "PCM"}, some leave the
     *  base off altogether ({@code "Capture Volume"}); both are this device's. */
    private static final String STREAM_BASE = "PCM";

    /** The host whose mixer this can touch at all. */
    private static final String LINUX = "linux";

    /** A control's header line:
     *  {@code numid=9,iface=MIXER,name='Line Capture Volume'}. Group 1 is the
     *  numid a write is addressed to, group 2 the name the mapping is decided
     *  on. A name the card publishes more than once carries an {@code index=}
     *  after it, which is why the mapping counts names rather than trusting one. */
    private static final Pattern CONTROL_HEADER =
            Pattern.compile("^numid=(\\d+),.*name='([^']*)'(?:,index=(\\d+))?");

    /** The type line of an INTEGER control, which is what a volume is:
     *  {@code ; type=INTEGER,access=rw---R--,values=2,min=0,max=31,step=0}.
     *  Booleans (the jacks, the switches) carry no {@code min}/{@code max} and
     *  are dropped by this pattern alone. */
    private static final Pattern INTEGER_TYPE =
            Pattern.compile("^;\\s*type=INTEGER,.*min=(-?\\d+),max=(-?\\d+)");

    /** The dB scale a control publishes:
     *  {@code | dBscale-min=-31.00dB,step=1.00dB,mute=0} - the only line that
     *  says which value reads 0 dB. */
    private static final Pattern DB_SCALE =
            Pattern.compile("dBscale-min=(-?[\\d.]+)dB,step=([\\d.]+)dB");

    /** The value line of a control, as opposed to its {@code ;} type line
     *  (which carries a {@code values=} of its own - the channel COUNT). */
    private static final String VALUE_PREFIX = ":";

    private static final String VALUE_MARKER = "values=";

    /** How long a write may take before it is killed - it sets one control the
     *  kernel already holds in memory. */
    private static final int TIMEOUT_SECONDS = 5;

    /**
     * One INTEGER control of a card's mixer, as {@code amixer contents} prints
     * it: what to address a write to, what it is called, the range it moves in,
     * where it stands now, and the dB scale that says what those numbers mean.
     *
     * @param dbMin  dB at {@link #min}, or null for a control that publishes no
     *               scale - which is the whole difference between "0 dB" and
     *               "as far up as it goes"
     * @param dbStep dB per step of the value, or null with {@code dbMin}
     */
    private record Control(int numid, String name, int index, int min, int max,
                           List<Integer> values, Double dbMin, Double dbStep) {

        /** The value that reads 0 dB - and, for a control with no dB scale to
         *  read it off, the maximum, which is the 100 percent an operator sees
         *  in {@code alsamixer}. */
        int unity() {
            if (dbMin == null || dbStep == null || dbStep <= 0) {
                return max;
            }
            long steps = Math.round(-dbMin / dbStep);
            return (int) Math.min(max, Math.max(min, min + steps));
        }

        /** Where {@code value} sits on this control's own scale, or null when it
         *  publishes none. */
        Double db(int value) {
            return dbMin == null ? null : dbMin + (value - min) * dbStep;
        }

        /** True when every channel already reads {@link #unity()} - the case
         *  that must change nothing and say nothing. */
        boolean atUnity() {
            int target = unity();
            return values.stream().allMatch(v -> v == target);
        }
    }

    /** The ALSA address in a mixer name - which card a write goes to. */
    private final ProcAsound proc;

    /** Which socket the opened device is, and therefore the word the card names
     *  that socket's own controls after. */
    private final AlsaPorts ports;

    /** Where the card's control dump comes from. It is read through the type
     *  that already runs {@code amixer contents}, so exactly one place in this
     *  backend starts that command - and a fixture drives both. */
    private final AlsaJacks jacks;

    /** Whether this host is one whose mixer is in the chain at all. */
    private final boolean linux;

    /** Raised the first time a card's controls could not be read, so a machine
     *  without alsa-utils says so once instead of on every open. */
    private final AtomicBoolean warnedUnreadable = new AtomicBoolean();

    /** What each open's pin actually moved, per device and direction, so the
     *  close can put it back: the mixer belongs to the operator between opens,
     *  and a measurement that borrowed it returns it as found. */
    private final Map<String, List<Saved>> restorable = new ConcurrentHashMap<>();

    /** One control as it stood before the pin moved it. */
    private record Saved(int card, int numid, String name, String values) {}

    /** Runs the real {@code amixer}, on the one host that has one. */
    public AlsaVolumes(ProcAsound proc, AlsaPorts ports, AlsaJacks jacks) {
        this(proc, ports, jacks, System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains(LINUX));
    }

    /** Injected whole, so a test can drive both hosts - and a fixture dump - on
     *  a machine that has neither ALSA nor alsa-utils. */
    AlsaVolumes(ProcAsound proc, AlsaPorts ports, AlsaJacks jacks, boolean linux) {
        this.proc = proc;
        this.ports = ports;
        this.jacks = jacks;
        this.linux = linux;
    }

    /**
     * Puts every volume control of {@code mixerName}'s own port - and of the
     * stream behind it - at 0 dB, once, as the device is opened.
     *
     * @param mixerName the JavaSound mixer name, card and PCM device included
     *                  ({@code "CB5 [plughw:1,1]"})
     * @param input     true for the capture side of that PCM device
     */
    public void pinToUnity(String mixerName, boolean input) {
        if (!linux) {
            return;
        }
        int card = proc.cardIndexOf(mixerName);
        if (card < 0) {
            return;                         // not an ALSA PCM mixer: nothing addressable
        }
        List<String> dump = jacks.readContents(card);
        if (dump.isEmpty()) {
            warnUnreadable(card, mixerName);
            return;
        }
        String direction = input ? CAPTURE : PLAYBACK;
        List<Control> volumes = volumesOf(dump, direction);
        if (volumes.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("Card {} publishes no {} volume control - nothing there can "
                        + "attenuate {}", card, direction, mixerName);
            }
            return;
        }
        List<Saved> moved = new ArrayList<>();
        AlsaPorts.Port port = ports.port(mixerName, input);
        if (port == null) {
            pinAll(card, mixerName, null, volumes, moved);
        } else {
            int pcmDevice = proc.deviceIndexOf(mixerName);
            if (pin(card, mixerName, port.word(), direction, pcmDevice, volumes, moved) == 0) {
                pinAll(card, mixerName, port.word(), volumes, moved);
            }
        }
        if (!moved.isEmpty()) {
            restorable.put(key(mixerName, input), moved);
        }
    }

    /**
     * The fallback when precise addressing claims nothing: every one of these
     * volume controls belongs to the SELECTED card and direction, so all of
     * them are pinned rather than none - the close restores each one moved.
     * The precise port/stream matching stays first, so a multi-port card
     * still gets exact addressing, and a PORT-named control published twice
     * is still refused there (it was claimed, just not resolved) rather than
     * swept up here.
     */
    private void pinAll(int card, String mixerName, String word, List<Control> volumes,
                        List<Saved> moved) {
        if (log.isInfoEnabled()) {
            log.info("Card {}'s controls carry {} - pinning all {} volume control(s) of the "
                    + "selected device: {}", card,
                    word == null ? "no port this machine can name"
                            : "neither its port word '" + word + "' nor a stream name",
                    volumes.size(), names(volumes));
        }
        for (Control control : volumes) {
            if (!control.atUnity()) {
                apply(card, mixerName, control, moved);
            }
        }
    }

    /**
     * Puts back what this device and direction's pin moved, as the stream that
     * borrowed the mixer stops - however it stops.  The mixer belongs to the
     * operator between opens; only what the pin itself changed is written, so a
     * knob the operator turned while the stream ran keeps the operator's value.
     * Nothing was moved (or the pin never ran): nothing is written and nothing
     * is said.
     */
    public void restore(String mixerName, boolean input) {
        if (!linux) {
            return;
        }
        List<Saved> moved = restorable.remove(key(mixerName, input));
        if (moved == null) {
            return;
        }
        for (Saved control : moved) {
            if (!set(control.card(), control.numid(), control.values())) {
                if (log.isWarnEnabled()) {
                    log.warn("Could not restore card {}'s '{}' to {} for {} - the device may "
                            + "already be gone", control.card(), control.name(),
                            control.values(), mixerName);
                }
                continue;
            }
            if (log.isInfoEnabled()) {
                log.info("Restored card {}'s '{}' for {}: back to {}",
                        control.card(), control.name(), mixerName, control.values());
            }
        }
    }

    private String key(String mixerName, boolean input) {
        return mixerName + (input ? "|capture" : "|playback");
    }

    /**
     * Sets the controls that belong to this port, and says how many of them
     * this could name at all - a zero is what tells the caller that a card's
     * volumes were all left alone.
     *
     * <p>A PORT-named control the card publishes more than once is NOT one of
     * them: two sockets of one word publish the same control name (the second
     * carrying an {@code index=}) and nothing in the dump says which of them
     * serves the PCM device being opened. Naming it and leaving it is the
     * honest answer; a guess would move a stranger's socket.
     *
     * <p>A STREAM-side control published more than once is different: a card
     * with one feature unit per PCM pair names them all {@code "PCM Playback
     * Volume"}, distinguished only by {@code index=}, and the kernel assigns
     * those indexes in the same interface order as the PCM device numbers - so
     * the control whose index equals the opened {@code plughw:C,D} device
     * number is this device's own, and picking it is reading the layout, not
     * guessing. A card whose indexes do not include the device number falls
     * back to the honest refusal.
     */
    private int pin(int card, String mixerName, String word, String direction,
                    int pcmDevice, List<Control> volumes, List<Saved> moved) {
        int claimed = 0;
        for (Map.Entry<String, List<Control>> published : byName(volumes).entrySet()) {
            String base = baseOf(published.getKey(), direction);
            boolean streamSide = base.equals(STREAM_BASE) || base.isEmpty();
            if (!base.equals(word) && !streamSide) {
                if (log.isDebugEnabled()) {
                    log.debug("'{}' belongs to card {}'s '{}' port, not to {} - left alone",
                            published.getKey(), card, base, mixerName);
                }
                continue;
            }
            claimed++;
            List<Control> same = published.getValue();
            Control control;
            if (same.size() == 1) {
                control = same.get(0);
            } else if (streamSide && pcmDevice >= 0) {
                control = same.stream()
                        .filter(candidate -> candidate.index() == pcmDevice)
                        .findFirst().orElse(null);
                if (control == null) {
                    warnAmbiguous(card, mixerName, published.getKey(), same.size());
                    continue;
                }
                if (log.isInfoEnabled()) {
                    log.info("Card {} publishes '{}' {} times - index {} is PCM device {}'s "
                            + "own ({})", card, published.getKey(), same.size(),
                            control.index(), pcmDevice, mixerName);
                }
            } else {
                warnAmbiguous(card, mixerName, published.getKey(), same.size());
                continue;
            }
            if (!control.atUnity()) {
                apply(card, mixerName, control, moved);
            }
        }
        return claimed;
    }

    /** Writes one control's unity into it, and says either what moved or why
     *  nothing did.  A write that lands is remembered in {@code moved}, with
     *  the value the control stood at, so the close can put it back. */
    private void apply(int card, String mixerName, Control control, List<Saved> moved) {
        int target = control.unity();
        String setting = String.join(",",
                Collections.nCopies(control.values().size(), String.valueOf(target)));
        String from = joined(control.values());
        if (!set(card, control.numid(), setting)) {
            if (log.isWarnEnabled()) {
                log.warn("Could not set card {}'s '{}' to {} for {} - it stays at {} and "
                        + "attenuates the calibrated chain silently",
                        card, control.name(), setting, mixerName, from);
            }
            return;
        }
        moved.add(new Saved(card, control.numid(), control.name(), from));
        if (log.isInfoEnabled()) {
            log.info("Pinned card {}'s '{}' to unity for {}: {} -> {}{}",
                    card, control.name(), mixerName, from, setting, change(control, target));
        }
    }

    /** What the change was worth in dB, for the log - or a plain word when the
     *  control publishes no scale to read it off. */
    private String change(Control control, int target) {
        Double from = control.db(control.values().get(0));
        Double to = control.db(target);
        if (from == null || to == null) {
            return " (no dB scale published - set to full scale)";
        }
        return String.format(Locale.ROOT, " (%.1f dB -> %.1f dB)", from, to);
    }

    /** The base a control's name carries in front of its direction: "Line" of
     *  {@code "Line Capture Volume"}, and the empty string of a card that names
     *  its only stream control {@code "Capture Volume"}. */
    private String baseOf(String name, String direction) {
        return name.substring(0, name.length() - (direction + " " + VOLUME).length()).trim();
    }

    /** The controls of one dump grouped by name, in the order the card
     *  publishes them - the grouping is what makes a name published twice
     *  visible as the ambiguity it is. */
    private Map<String, List<Control>> byName(List<Control> volumes) {
        return volumes.stream().collect(Collectors.groupingBy(
                Control::name, LinkedHashMap::new, Collectors.toList()));
    }

    /**
     * Every INTEGER volume control of one direction in an {@code amixer
     * contents} dump, in the order the card publishes them.
     *
     * <p>A control is one block of lines opened by its header, so the dump is
     * cut at the headers and each block read on its own.
     */
    private List<Control> volumesOf(List<String> lines, String direction) {
        String suffix = direction + " " + VOLUME;
        List<Control> found = new ArrayList<>();
        List<String> block = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (CONTROL_HEADER.matcher(line).find()) {
                addVolume(block, suffix, found);
                block.clear();
            }
            block.add(line);
        }
        addVolume(block, suffix, found);
        return found;
    }

    /** Reads one control's block, and adds it only if it is a volume of the
     *  wanted direction - the jacks, the switches and the channel maps of the
     *  same dump all fall out here. */
    private void addVolume(List<String> block, String suffix, List<Control> found) {
        int numid = -1;
        String name = null;
        int index = 0;
        int min = 0;
        int max = 0;
        boolean integer = false;
        List<Integer> values = new ArrayList<>();
        Double dbMin = null;
        Double dbStep = null;
        for (String line : block) {
            Matcher header = CONTROL_HEADER.matcher(line);
            if (header.find()) {
                numid = Integer.parseInt(header.group(1));
                name = header.group(2);
                index = header.group(3) != null ? Integer.parseInt(header.group(3)) : 0;
                continue;
            }
            Matcher type = INTEGER_TYPE.matcher(line);
            if (type.find()) {
                integer = true;
                min = Integer.parseInt(type.group(1));
                max = Integer.parseInt(type.group(2));
                continue;
            }
            Matcher scale = DB_SCALE.matcher(line);
            if (scale.find()) {
                dbMin = Double.parseDouble(scale.group(1));
                dbStep = Double.parseDouble(scale.group(2));
                continue;
            }
            if (line.startsWith(VALUE_PREFIX) && !readValues(line, values)) {
                return;                     // a value nobody can read is not a volume
            }
        }
        if (name == null || !integer || values.isEmpty() || !name.endsWith(suffix)) {
            return;
        }
        found.add(new Control(numid, name, index, min, max, List.copyOf(values), dbMin, dbStep));
    }

    /** One control's current value per channel, or false for a line that does
     *  not read as numbers at all. */
    private boolean readValues(String line, List<Integer> values) {
        int at = line.indexOf(VALUE_MARKER);
        if (at < 0) {
            return true;
        }
        for (String token : line.substring(at + VALUE_MARKER.length()).trim().split(",")) {
            try {
                values.add(Integer.valueOf(token.trim()));
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        return true;
    }

    /**
     * Runs {@code amixer -c <card> cset numid=<numid> <value>} and says whether
     * the control took it.
     *
     * <p>The value is written once per channel ({@code "127,127"}) rather than
     * once for the control, so a stereo pair cannot end up with one side set
     * and the other where it was.
     *
     * <p>Package-private, and the only thing in this class that changes the
     * machine, so a test can record what WOULD have been written - on a host
     * with neither a sound card nor alsa-utils.
     */
    boolean set(int card, int numid, String value) {
        Process process = null;
        try {
            process = new ProcessBuilder("amixer", "-c", String.valueOf(card),
                    "cset", "numid=" + numid, value)
                    .redirectErrorStream(true)
                    .start();
            List<String> said = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null; ) {
                    said.add(line);
                }
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            if (process.exitValue() != 0 && log.isDebugEnabled()) {
                log.debug("amixer -c {} cset numid={} {} said: {}", card, numid, value, said);
            }
            return process.exitValue() == 0;
        } catch (Exception ex) {
            if (log.isDebugEnabled()) {
                log.debug("amixer -c {} cset numid={} {} failed: {}",
                        card, numid, value, ex.toString());
            }
            if (process != null) {
                process.destroyForcibly();
            }
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /** The one confession a host without alsa-utils gets - it would otherwise
     *  be repeated on every open of every device for the life of the process. */
    private void warnUnreadable(int card, String mixerName) {
        if (!warnedUnreadable.compareAndSet(false, true)) {
            return;
        }
        if (log.isWarnEnabled()) {
            log.warn("Could not read card {}'s mixer controls, so the volumes in front of "
                    + "{} were NOT pinned to 0 dB - install alsa-utils (amixer) if a level "
                    + "measures low: a control left below 0 dB attenuates the calibrated "
                    + "chain silently", card, mixerName);
        }
    }

    /** Says which control was named twice, and that a guess was refused. */
    private void warnAmbiguous(int card, String mixerName, String name, int published) {
        if (log.isWarnEnabled()) {
            log.warn("Card {} publishes '{}' {} times and nothing says which of them serves "
                    + "{} - left untouched rather than set at a guess",
                    card, name, published, mixerName);
        }
    }

    private String names(List<Control> volumes) {
        return volumes.stream().map(Control::name).distinct().collect(Collectors.joining(", "));
    }

    private String joined(List<Integer> values) {
        return values.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
