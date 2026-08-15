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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

import org.edgo.audio.measure.common.Closeables;
import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.DeviceFailureReason;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.AudioCapture;
import org.edgo.audio.measure.sound.AudioDeviceManager;
import org.edgo.audio.measure.sound.AudioPlayback;
import org.edgo.audio.measure.sound.DeviceRef;

import lombok.extern.log4j.Log4j2;

/**
 * Discovery for the {@link AudioBackendType#JAVASOUND} backend.  Lists the
 * mixers reported by {@link AudioSystem#getMixerInfo()} that can supply at
 * least one {@link TargetDataLine} (capture) or {@link SourceDataLine}
 * (playback) - the same {@code javax.sound.sampled} surface
 * {@link JavaSoundGenerator} already uses for output.
 *
 * <p>Used as the cross-platform fallback on Linux and macOS where
 * WASAPI / WDM-KS don't exist, and as a portable option on Windows.
 *
 * <p>Constructed and owned by {@link AudioBackend}; do not instantiate
 * directly.
 */
@Log4j2
public final class JavaSoundDeviceManager implements AudioDeviceManager {

    /** The ALSA address of the system's default device, which names no card of
     *  its own - the entry that follows whatever the system is set to. */
    private static final String DEFAULT_ADDRESS = "[default]";
    /** What that entry is called in the list, since "PCH [default]" named a
     *  card the selection does not actually pin. */
    private static final String SYSTEM_DEFAULT_LABEL = "System default";
    /** The JavaSound provider's own prefix on every ALSA description; it is
     *  followed by the card's name and then the PCM device's designation. */
    private static final String DIRECT_AUDIO_PREFIX = "Direct Audio Device:";
    /** The placeholder the provider stamps on a device whose maker it cannot
     *  name.  It is not a vendor, and it read as one at the end of every
     *  device line. */
    private static final String UNKNOWN_VENDOR = "Unknown Vendor";

    /**
     * A listed JavaSound device - a value container over the mixer behind it.
     *
     * <p>{@code name} arrives FINISHED from the manager and is both what the
     * operator reads and the device's identity: on ALSA the card's real product
     * name with its port ({@code "CUBILUX CB5 - Line In"}), elsewhere the
     * mixer's own name exactly as the host API states it. {@code mixerInfo} is
     * the backing mixer - the raw host name lives there, which is what a line
     * open and the ALSA volume pinning key on.
     */
    public record JavaSoundDeviceRef(int index, String name, String description, String vendor,
                                     boolean isInput, boolean isOutput,
                                     Mixer.Info mixerInfo)
            implements DeviceRef {
        @Override
        public AudioBackendType backend() {
            return AudioBackendType.JAVASOUND;
        }

        /** The shared line, minus a vendor there is none of: the provider
         *  stamps a placeholder on every device whose maker it cannot name
         *  ({@link #plainVendor} drops it), and the separator in front of it
         *  would then trail every device line with nothing after it. */
        @Override
        public String displayName() {
            if (vendor != null && !vendor.isBlank()) {
                return DeviceRef.super.displayName();
            }
            return description == null || description.isBlank()
                    ? String.format("[%d] %s", index, name)
                    : String.format("[%d] %s (%s)", index, name, description);
        }

        @Override
        public String toString() {
            return displayName();
        }
    }

    /** Playback buffer scales with the sample rate - {@value #BASE_BUFFER_FRAMES}
     *  frames per {@value #BASE_SAMPLE_RATE} Hz (so 8192 @ 768 kHz, 16384 @
     *  1536 kHz) - keeping the underrun margin constant in TIME (a fixed frame
     *  count would halve it each time the rate doubles: the ~108 µs dropouts seen
     *  on a scope at 768 kHz).  It is also the floor for rates at/below the base.
     *  Derived from the line format's sample rate, recomputed on each open so it
     *  tracks the bidi-bound output-rate changes. */
    /*private static final int BASE_BUFFER_FRAMES = 8192;
    private static final int BASE_SAMPLE_RATE   = 384000;*/

    /**
     * Cached probe results per mixer name.  Probing actually opens lines
     * (the only reliable way past the {@code isLineSupported} false
     * positives - see {@link #probeFormats}), so caching avoids the
     * latency hit on every {@code listSupportedFormats} call.
     */
    private final Map<String, List<AudioFormat>> inputFormatsCache  = new ConcurrentHashMap<>();
    private final Map<String, List<AudioFormat>> outputFormatsCache = new ConcurrentHashMap<>();

    /** Whether this host is the one the ALSA cleanup was written for.  The
     *  phantom filter in {@link #list} runs only here - see it for why its
     *  evidence is ambiguous anywhere else. */
    private final boolean linux = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("linux");

    /** The kernel's view of what each card can do (Linux only; every query
     *  answers "unknown" elsewhere, which the probe treats as "ask the mixer"). */
    private final ProcAsound procAsound;

    /** Which SOCKET each Linux device is, and whether it has a plug (Linux and
     *  USB only; every query answers null elsewhere, which {@link #list} treats
     *  as "report the mixer exactly as it names itself"). */
    private final AlsaPorts alsaPorts;

    /** Puts an opened device's own volume controls at 0 dB, so the card's mixer
     *  cannot sit inside a calibrated chain and scale it (Linux only; every
     *  other host answers immediately). */
    private final AlsaVolumes alsaVolumes;

    /** Which mixer each requested output name's pin landed on, so
     *  {@link #restoreOutputVolume} can put that mixer back at close. */
    private final Map<String, String> pinnedOutputMixers = new ConcurrentHashMap<>();

    public JavaSoundDeviceManager() {
        ProcAsound proc = new ProcAsound();
        AlsaJacks jacks = new AlsaJacks();
        AlsaPorts ports = new AlsaPorts(proc, jacks);
        this.procAsound = proc;
        this.alsaPorts = ports;
        this.alsaVolumes = new AlsaVolumes(proc, ports, jacks);
    }

    /** Injected so a test can point the capability reader, the port reader and
     *  the volume writer at fixture trees. */
    public JavaSoundDeviceManager(ProcAsound procAsound, AlsaPorts alsaPorts,
                                  AlsaVolumes alsaVolumes) {
        this.procAsound = procAsound;
        this.alsaPorts = alsaPorts;
        this.alsaVolumes = alsaVolumes;
    }

    public List<DeviceRef> listInputDevices()  { return list(true);  }
    public List<DeviceRef> listOutputDevices() { return list(false); }

    /**
     * Drops every cached probe, so the operator's scan re-asks the hardware.
     *
     * <p>The enumeration itself is live - {@link AudioSystem#getMixerInfo()} is
     * read on every list - but the format probe behind each mixer is cached,
     * and that cache is what a scan is really aimed at: a device that was held
     * when it was first probed answers from the stale entry for ever otherwise,
     * and a device whose formats changed with it keeps reporting the old set.
     * The ALSA collaborators forget with it: their card-index-keyed answers
     * (capabilities, card names, USB descriptors) survive a replug otherwise,
     * and a card moved to another USB port re-enumerates at a NEW index while
     * the old index may now belong to different hardware.
     * Answers {@code true} because the next list can now differ from the last.
     */
    @Override
    public boolean refreshDeviceList() {
        inputFormatsCache.clear();
        outputFormatsCache.clear();
        procAsound.forget();
        alsaPorts.forget();
        return true;
    }

    /**
     * Every mixer that can supply a line in this direction - and, on Linux,
     * every such mixer whose SOCKET is not reported empty.
     *
     * <p>A card's PCM devices are named after the chip ("USB Audio #1"), which
     * says nothing about which cable an operator plugged where, and a card with
     * a line pair and a microphone pair lists both whether or not anything is
     * attached to either. {@link AlsaPorts} answers both questions from the
     * card's own descriptors and the kernel's jack controls, so a Linux listing
     * reads the way the Windows one does: the ports by name, and only the ones
     * that are connected.
     *
     * <p>The name the ref carries is FINISHED here and is the device's
     * identity everywhere - the card binding, the saved preference, a remote
     * client's ref.  On ALSA it is the card's real product name with its port;
     * on every other host it is the mixer name exactly as the host API states
     * it.  The mixer behind a name stays in {@link #mixerByName}, this
     * manager's own lookup.
     */
    private List<DeviceRef> list(boolean input) {
        List<DeviceRef> out = new ArrayList<>();
        Class<? extends Line> probe = input ? TargetDataLine.class : SourceDataLine.class;
        // One enumeration, one look at the jacks: a plug pulled since the last
        // scan must show up, and re-reading it per device would not.
        alsaPorts.refresh();
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        int slot = 0;
        for (Mixer.Info mi : mixers) {
            Mixer m;
            try {
                m = AudioSystem.getMixer(mi);
            } catch (Throwable t) {
                log.warn("Could not open mixer {}: {}", mi.getName(), t.getMessage());
                continue;
            }
            if (!m.isLineSupported(new DataLine.Info(probe, null))) {
                continue;
            }
            JavaSoundDeviceRef ref = null;
            if (linux) {
                AlsaPorts.Port port = alsaPorts.port(mi.getName(), input);
                // The slot is only consumed by a device that is actually listed.
                String designation = port == null ? distinct(mi.getDescription(), mi.getName()) : port.label();
                String name = deviceName(mi.getName(), designation);
                ref = new JavaSoundDeviceRef(
                        slot,
                        name,
                        // A name that already carries the port needs no echo of it.
                        name.equals(mi.getName()) ? designation : "",
                        plainVendor(mi.getVendor()),
                        input, !input,
                        mi);
                if (port != null && port.empty()) {
                    continue;
                }
                // A device that reports no formats is a phantom - a PCM with nothing
                // behind it (an HDMI codec without a sink) that cannot open at any
                // rate.  Dropped HERE so every consumer - the GUI combos, a server's
                // device list, the scanner - sees the same set.  The system default
                // stays listed: it is a role, not a PCM, and reports no formats by
                // construction.  The probe rides the formats cache.
                //
                // LINUX ONLY, because that is the host whose subdevice cleanup this
                // was written for and the only one where an empty answer means what
                // it says.  Elsewhere the probe's silence is ambiguous: a mixer that
                // advertises its lines with NOT_SPECIFIED rate or sample size
                // enumerates no concrete pair, and a device that is already held
                // refuses the read - both answer empty while being perfectly real
                // hardware, and both were then dropped from the list.  Not probing
                // also takes the probe's cost off enumeration, which every backend
                // switch pays on the UI thread.
                if (linux && !name.equals(SYSTEM_DEFAULT_LABEL)
                        && listSupportedFormats(ref, !input).isEmpty()) {
                    continue;
                }
            } else {
                ref = new JavaSoundDeviceRef(
                        slot,
                        mi.getName(),
                        mi.getName(),
                        mi.getVendor(),
                        input, !input,
                        mi);
            }
            slot++;
            out.add(ref);
        }
        return out;
    }

    /**
     * The finished device name: on ALSA {@code "<card> - <port>"} built from
     * the {@code [plughw:C,D]} address - C selects the card whose real name
     * comes from {@code /proc/asound/cards}, D the port - because the address,
     * the bracketed short id and the provider's {@code "Direct Audio Device:"}
     * boilerplate carry nothing an operator can act on.  The system-default
     * entry is named by its role; a mixer with no card name behind it - every
     * non-ALSA host - keeps the mixer name exactly as the host API states it.
     */
    private String deviceName(String mixerName, String designation) {
        return deviceName(mixerName, designation,
                procAsound.cardName(procAsound.cardIndexOf(mixerName)));
    }

    /** The naming decision itself, separated from the cards-file lookup so the
     *  shape is testable without a /proc behind it. */
    String deviceName(String mixerName, String designation, String cardName) {
        if (mixerName != null && mixerName.contains(DEFAULT_ADDRESS)) {
            return SYSTEM_DEFAULT_LABEL;
        }
        if (cardName == null || cardName.isBlank()) {
            return mixerName;
        }
        String port = stripDirectAudio(designation);
        return port.isEmpty() ? cardName : cardName + " - " + port;
    }

    /**
     * The port half of a name: the real socket name when ALSA could be asked
     * ({@code "Line In"}), else the PCM device's designation with the
     * provider's boilerplate taken off - {@code "Direct Audio Device: HDA
     * Intel PCH, ALC262 Analog"} repeats the card's name in front of the only
     * part that says which device this is, so what remains is
     * {@code "ALC262 Analog"}.
     */
    private String stripDirectAudio(String designation) {
        String text = designation == null ? "" : designation.trim();
        if (!text.startsWith(DIRECT_AUDIO_PREFIX)) {
            return text;
        }
        String rest = text.substring(DIRECT_AUDIO_PREFIX.length()).trim();
        int comma = rest.lastIndexOf(',');
        return comma >= 0 && comma + 1 < rest.length()
                ? rest.substring(comma + 1).trim() : rest;
    }

    /**
     * The host API's description with everything the mixer NAME already says
     * taken out of it, part by comma-separated part.
     *
     * <p>The provider builds it from the card's long name, the PCM id and the
     * PCM name, and those repeat both each other and the name the same mixer is
     * listed under - so the line an operator reads carried the same words three
     * times over and the one word that told two devices apart was at the end of
     * it. Where two parts overlap the LONGER one stays: it is the one carrying
     * the device number.
     *
     * <p>A description that says nothing the name does not is kept whole rather
     * than emptied: the field is printed in brackets whatever is in it, and an
     * empty pair of brackets reads worse than the repetition did.
     *
     * <p>Package-private so a test can drive it with the strings the providers
     * emit, on a host that has none of their hardware.
     */
    String distinct(String description, String name) {
        if (description == null || description.isBlank()) return "";
        String known = name == null ? "" : name;
        List<String> kept = new ArrayList<>();
        for (String part : description.split(",")) {
            String segment = part.trim();
            if (segment.isEmpty() || repeats(known, segment)) continue;
            kept.removeIf(earlier -> repeats(segment, earlier));
            if (kept.stream().noneMatch(earlier -> repeats(earlier, segment))) {
                kept.add(segment);
            }
        }
        return kept.isEmpty() ? description : String.join(", ", kept);
    }

    /** Whether {@code text} already carries {@code part}, however either is
     *  capitalised - the one test the de-duplication is built on. */
    private boolean repeats(String text, String part) {
        return text.toLowerCase(Locale.ROOT).contains(part.toLowerCase(Locale.ROOT));
    }

    /**
     * The vendor as far as its NAME goes.
     *
     * <p>The provider stamps every mixer it lists with its own name and, on
     * this platform, its project URL in brackets after it. The address is the
     * same on every device of the machine, so it is repeated on every row of a
     * device combo and tells an operator nothing; the plain word still says
     * which driver path the device came through, which is worth keeping.
     *
     * <p>Cut only where there is something to cut, and never down to nothing:
     * a provider that puts its whole vendor in brackets keeps it, because an
     * empty vendor would say less than the bracketed one did.
     *
     * <p>The one vendor that is dropped entirely is the provider's own
     * placeholder for "I could not find out": it names no maker, and it stood
     * at the end of every device line saying so.  A real vendor stays.
     *
     * <p>Package-private for the same reason {@link #distinct} is.
     */
    String plainVendor(String vendor) {
        if (vendor == null) return "";
        int at = vendor.indexOf('(');
        String name = at < 0 ? vendor : vendor.substring(0, at).trim();
        if (name.isEmpty()) return vendor;
        return name.equalsIgnoreCase(UNKNOWN_VENDOR) ? "" : name;
    }

    public DeviceRef getDeviceByIndex(int index, boolean isOutput) {
        List<DeviceRef> all = isOutput ? listOutputDevices() : listInputDevices();
        if (index < 0 || index >= all.size()) {
            throw new IllegalArgumentException("JavaSound device index out of range: " + index
                    + " (have " + all.size() + " " + (isOutput ? "output" : "input") + " devices)");
        }
        return all.get(index);
    }

    /**
     * Opens a {@link SourceDataLine} for {@code fmt} on the first mixer whose
     * name contains {@code deviceName} (and supports the format), falling back
     * to the platform-default line when {@code deviceName} is blank or no mixer
     * matches.  This is the device-name -> mixer selection both the DDS tone
     * ({@link JavaSoundGenerator}) and file playback share, so they reach the
     * SAME selected output device - which on Windows is the csjsound
     * exclusive-mode mixer that can open high formats (e.g. 384&nbsp;kHz /
     * 24-bit) the default mixer refuses.
     *
     * <p>It is also the ONE place every playback open goes through - the DDS
     * tone, file playback, the server's generator lane - which is why the
     * card's own volume controls are pinned to 0 dB here
     * ({@link AlsaVolumes}), once per open and never per buffer.
     */
    public SourceDataLine openOutputLine(String deviceName, AudioFormat fmt) throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
        Mixer.Info chosen = findMixer(deviceName, info);
        SourceDataLine line = (SourceDataLine) (chosen != null
                ? AudioSystem.getMixer(chosen).getLine(info)
                : AudioSystem.getLine(info));
        // Time-based buffer (see OUTPUT_BUFFER_SEC): a fixed frame count would
        // halve the underrun margin each time the rate doubles.  fmt carries the
        // selected output rate, so the buffer tracks it; floored at 4096 frames.
        line.open(fmt);
        /*int bufferFrames = Math.max(BASE_BUFFER_FRAMES,
                (int) Math.round(fmt.getSampleRate() * (double) BASE_BUFFER_FRAMES / BASE_SAMPLE_RATE));
        line.open(fmt, bufferFrames * fmt.getFrameSize());*/
        if (log.isInfoEnabled()) {
            log.info("JavaSound output line opened: format={}, mixer={}",
                    fmt, chosen != null ? chosen.getName() : "<JavaSound default>");
            /*log.info("JavaSound output line opened: format={}, mixer={}, buffer={} frames ({} ms)",
                    fmt, chosen != null ? chosen.getName() : "<JavaSound default>",
                    bufferFrames, bufferFrames * 1000L / (long) fmt.getSampleRate());*/
        }
        if (chosen != null) {
            // The card's own volumes are hardware gain INSIDE the calibrated
            // chain (plughw honours them), and a mixer left turned down scales
            // the tone with nothing in the reading to say so.  The chosen
            // mixer's name is the one asked about: the requested name is only a
            // substring of it, and a fallback to the default mixer opened a
            // device this cannot address at all.
            alsaVolumes.pinToUnity(chosen.getName(), false);
            // Remembered under the REQUESTED name, which is all the generator
            // has when it closes and asks for the mixer back.
            pinnedOutputMixers.put(deviceName == null ? "" : deviceName, chosen.getName());
        }
        return line;
    }

    /** Puts back what {@link #openOutputLine}'s pin moved, as the playback that
     *  borrowed the mixer closes - resolved through the requested-name mapping
     *  the pin recorded, because the caller never sees the chosen mixer. */
    public void restoreOutputVolume(String deviceName) {
        String resolved = pinnedOutputMixers.remove(deviceName == null ? "" : deviceName);
        if (resolved != null) {
            alsaVolumes.restore(resolved, false);
        }
    }

    /**
     * First mixer whose name contains {@code deviceName} and can supply a
     * {@link SourceDataLine} matching {@code info}; {@code null} when no name
     * is requested or none matches (the caller then uses
     * {@link AudioSystem#getLine}).
     */
    private Mixer.Info findMixer(String deviceName, DataLine.Info info) {
        if (deviceName == null || deviceName.isEmpty()) return null;
        // The stored name is the FINISHED one this manager built, so it is
        // found the same way it was made: derive each mixer's name and compare.
        boolean input = TargetDataLine.class.equals(info.getLineClass());
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            AlsaPorts.Port port = alsaPorts.port(mi.getName(), input);
            String designation = port == null ? distinct(mi.getDescription(), mi.getName()) : port.label();
            if (!deviceName.equals(deviceName(mi.getName(), designation))) continue;
            if (AudioSystem.getMixer(mi).isLineSupported(info)) return mi;
        }
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            if (!mi.getName().contains(deviceName)) continue;
            if (AudioSystem.getMixer(mi).isLineSupported(info)) return mi;
        }
        if (log.isWarnEnabled()) {
            log.warn("JavaSound: no mixer matches '{}', falling back to default", deviceName);
        }
        return null;
    }

    /**
     * Probes a small set of rates × bit depths against the device's mixer
     * and returns the {@link AudioFormat}s the line will actually open at.
     * Cached per mixer name.
     */
    public List<AudioFormat> listSupportedFormats(DeviceRef device, boolean output) {
        if (!(device instanceof JavaSoundDeviceRef d)) return new ArrayList<>();
        Map<String, List<AudioFormat>> cache = output ? outputFormatsCache : inputFormatsCache;
        // Keyed on the RAW mixer name: it is unique per PCM device, where the
        // finished name is not (four HDMI outputs of one card share theirs).
        return cache.computeIfAbsent(d.mixerInfo().getName(), k -> probeFormats(d, output));
    }

    public AudioCapture openCapture(DeviceRef device, int sampleRate, int bitDepth) {
        return new JavaSoundRecorder((JavaSoundDeviceManager.JavaSoundDeviceRef) device,
                sampleRate, bitDepth, alsaVolumes);
    }

    public AudioPlayback openPlayback(DeviceRef device, int sampleRate, int bitDepth, double ditherBits) {
        return new JavaSoundGenerator(sampleRate, bitDepth, ditherBits, device.name(), this);
    }

    /**
     * This backend's own reading of its own failures - JavaSound gives no error
     * code at all, so the EXCEPTION TYPE is the primary signal and the message
     * only refines it.
     *
     * <p>It classifies more than JavaSound's own devices: {@code
     * AudioBackend.playbackManager} routes WASAPI playback here too, so a
     * WASAPI card's open failure is read by this method.
     *
     * <p>{@link LineUnavailableException} is the JDK's one word for "the line
     * exists but you cannot have it" - another application holds it, or the
     * driver is in exclusive mode.  {@link IllegalArgumentException} is what
     * {@code AudioSystem.getLine} throws when no line matches the asked format
     * at all.  The stall texts are this module's own (see
     * {@code JavaSoundGenerator}'s lost-lane details): a line that stops
     * draining has not disappeared - it has stopped answering, which is exactly
     * the csjsound render stall a bench sees.
     */
    @Override
    public DeviceFailureReason classifyFailure(Throwable failure) {
        if (failure == null) {
            return DeviceFailureReason.UNKNOWN;
        }
        String text = failure.getMessage() == null
                ? "" : failure.getMessage().toLowerCase(Locale.ROOT);
        if (text.contains("no longer taking audio")
                || text.contains("stopped draining")
                || text.contains("accepted nothing")) {
            return DeviceFailureReason.DEVICE_NOT_ANSWERING;
        }
        if (failure instanceof LineUnavailableException) {
            return DeviceFailureReason.DEVICE_IN_USE;
        }
        if (failure instanceof IllegalArgumentException) {
            return DeviceFailureReason.FORMAT_UNSUPPORTED;
        }
        return DeviceFailureReason.UNKNOWN;
    }

    /**
     * Two probe paths, chosen by host OS:
     * <ul>
     *   <li>On <strong>Linux</strong> we trust hardware-reported caps,
     *       not a hard-coded candidate list.  {@link ProcAsound} parses
     *       {@code /proc/asound/card*\/codec#*} and {@code /stream*} to
     *       discover the rates and bit depths the codec actually
     *       supports - analog Audio Input / Output nodes only - and we
     *       cross those into the AudioFormat list directly.  This is
     *       why 20-bit ADCs (Realtek ALC262 and friends) are now
     *       selectable: nothing filters them out.</li>
     *   <li>On <strong>Windows / macOS</strong> we open and immediately
     *       close a line for each (rate, bits) pair from a standard
     *       pro-audio candidate set - the OS audio engine refuses
     *       formats the hardware can't handle, so open-and-test is the
     *       reliable signal.</li>
     * </ul>
     *
     */
    private List<AudioFormat> probeFormats(JavaSoundDeviceRef d, boolean output) {
        Class<? extends DataLine> cls = output ? SourceDataLine.class : TargetDataLine.class;
        Mixer m = AudioSystem.getMixer(d.mixerInfo());

        boolean linux = System.getProperty("os.name", "").toLowerCase().contains("linux");
        if (linux) {
            return probeFormatsLinux(d, m, output);
        }

        int[] rates  = {8000, 11025, 16000, 22050, 44100, 48000, 88200,
                        96000, 176400, 192000, 352800, 384000, 705600, 768000};
        int[] depths = {16, 24, 32};
        List<AudioFormat> result = new ArrayList<>();
        for (int rate : rates) {
            for (int bits : depths) {
                AudioFormat fmt = buildFormat(rate, bits);
                boolean ok = canOpen(m, cls, fmt);
                if (!ok && !output) {
                    // A mono-only capture device (1-channel mic) won't open
                    // stereo; JavaSoundRecorder captures it mono and upmixes,
                    // so still offer this rate/bit-depth.
                    ok = canOpen(m, cls, buildMonoFormat(rate, bits));
                }
                if (ok) {
                    result.add(fmt);   // report stereo; recorder upmixes if the device is mono
                }
            }
        }
        return result;
    }

    /**
     * Linux probe - hardware caps come straight from the kernel, for THIS
     * DEVICE'S OWN CARD.
     *
     * <p>The mixer name carries the ALSA address ({@code "CB5 [plughw:1,1]"}),
     * so {@link ProcAsound} is asked about that card alone. Asking
     * {@code /proc/asound} as a whole - which this method used to do - merges
     * every card into one answer, so a machine with an onboard codec beside a
     * USB interface reported the union for both; and on a machine whose cards
     * are neither HD-Audio nor USB it reported nothing at all, which is how a
     * bench came to deliver an empty format list.
     *
     * <p><b>ON THE LEGACY-CARD CASE, THE DIRECT {@code hw:} DEVICE.</b> A card
     * with neither layout (a PCI codec such as the Xonar STX or the Ensoniq
     * ES1371) is answered {@link ProcAsound.CardCaps#known() not known}, and
     * its truth is then read from the {@code hw:} device's own hw_params
     * ranges ({@link ProcAsound#hwParamsCaps}).  What this path still never
     * does is open-and-test through the PLUG layer ({@code plughw}), which
     * exists precisely to CONVERT - it accepts rates the hardware cannot
     * produce and resamples silently, so a probe through it would publish a
     * capability list the silicon cannot honour.
     */
    private List<AudioFormat> probeFormatsLinux(JavaSoundDeviceRef d, Mixer m, boolean output) {
        // 1. This card's own /proc/asound entry is the source of truth when present.
        // The ALSA address lives in the RAW mixer name behind the ref - the
        // finished name deliberately carries no [plughw:C,D] to parse.
        ProcAsound.CardCaps caps = procAsound.capsForMixer(d.mixerInfo().getName());
        if (!caps.known()) {
            // 1b. No USB stream file, no HDA codec file - ask the direct hw:
            //     device for its own hw_params ranges.
            caps = procAsound.hwParamsCaps(d.mixerInfo().getName());
        }
        int[] hwRates  = caps.rates(output);
        int[] hwDepths = caps.depths(output);
        if (hwRates.length > 0 && hwDepths.length > 0) {
            List<AudioFormat> result = new ArrayList<>();
            for (int rate : hwRates) {
                for (int bits : hwDepths) {
                    result.add(buildFormat(rate, bits));
                }
            }
            return result;
        }

        // 2. /proc/asound unavailable (containers, non-Linux kernels) -
        //    walk the mixer's own getFormats() for whatever explicit
        //    (rate, bits) pairs it advertises.  NOT_SPECIFIED entries
        //    carry no hardware information so they're skipped; an empty
        //    result triggers PreferencesDialog's default-rate fallback.
        Line.Info[] lineInfos = output ? m.getSourceLineInfo() : m.getTargetLineInfo();
        TreeSet<Integer> rates  = new TreeSet<>();
        TreeSet<Integer> depths = new TreeSet<>();
        for (Line.Info li : lineInfos) {
            if (!(li instanceof DataLine.Info dli)) continue;
            for (AudioFormat sf : dli.getFormats()) {
                if (sf.getEncoding() != AudioFormat.Encoding.PCM_SIGNED) continue;
                int sBits = sf.getSampleSizeInBits();
                float sRate = sf.getSampleRate();
                if (sBits != AudioSystem.NOT_SPECIFIED) depths.add(sBits);
                if ((int) sRate != AudioSystem.NOT_SPECIFIED) rates.add(Math.round(sRate));
            }
        }
        List<AudioFormat> result = new ArrayList<>();
        for (int rate : rates) {
            for (int bits : depths) {
                result.add(buildFormat(rate, bits));
            }
        }
        return result;
    }

    /** Builds a stereo PCM_SIGNED little-endian AudioFormat for the
     *  given rate and bit depth.  Rounds non-byte-aligned bit widths
     *  (e.g. 20-bit packed in 24-bit containers) up to the next byte
     *  so the frame size is correct: 20-bit stereo -> 6 bytes/frame. */
    private AudioFormat buildFormat(int rate, int bits) {
        int bytesPerSample = (bits + 7) / 8;
        int frameSize      = bytesPerSample * 2;
        return new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                rate, bits, 2, frameSize, rate, false);
    }


    /** Mono (1-channel) variant of {@link #buildFormat} - the capture candidate
     *  a 1-channel device opens at, and the format {@link JavaSoundRecorder}
     *  then falls back to. */
    private AudioFormat buildMonoFormat(int rate, int bits) {
        int bytesPerSample = (bits + 7) / 8;
        return new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                rate, bits, 1, bytesPerSample, rate, false);
    }

    private static boolean canOpen(Mixer m, Class<? extends DataLine> cls, AudioFormat fmt) {
        DataLine.Info info = new DataLine.Info(cls, fmt);
        if (!m.isLineSupported(info)) return false;
        DataLine line = null;
        try {
            line = (DataLine) m.getLine(info);
            // DataLine itself only declares the no-arg open(); the
            // format-taking overload lives on the SourceDataLine /
            // TargetDataLine subinterfaces.
            if (line instanceof SourceDataLine sdl) {
                sdl.open(fmt);
            } else if (line instanceof TargetDataLine tdl) {
                tdl.open(fmt);
            } else {
                return false;
            }
            return true;
        } catch (LineUnavailableException | IllegalArgumentException ex) {
            return false;
        } finally {
            Closeables.closeQuietly(line);
        }
    }
}
