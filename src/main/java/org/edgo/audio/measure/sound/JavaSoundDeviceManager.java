package org.edgo.audio.measure.sound;

import lombok.extern.log4j.Log4j2;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.edgo.audio.measure.enums.AudioBackendType;

/**
 * Discovery for the {@link AudioBackendType#JAVASOUND} backend.  Lists the
 * mixers reported by {@link AudioSystem#getMixerInfo()} that can supply at
 * least one {@link TargetDataLine} (capture) or {@link SourceDataLine}
 * (playback) — the same {@code javax.sound.sampled} surface
 * {@link JavaSoundGenerator} already uses for output.
 *
 * <p>Used as the cross-platform fallback on Linux and macOS where
 * WASAPI / WDM-KS don't exist, and as a portable option on Windows.
 *
 * <p>Constructed and owned by {@link AudioBackend}; do not instantiate
 * directly.
 */
@Log4j2
public final class JavaSoundDeviceManager {

    /** {@link DeviceRef} backed by a {@link Mixer.Info}. */
    public record JavaSoundDeviceRef(int index, String name, String description, String vendor,
                                     boolean isInput, boolean isOutput,
                                     Mixer.Info mixerInfo)
            implements DeviceRef {
        @Override
        public AudioBackendType backend() {
            return AudioBackendType.JAVASOUND;
        }
        @Override
        public String toString() {
            return displayName();
        }
    }

    /**
     * Cached probe results per mixer name.  Probing actually opens lines
     * (the only reliable way past the {@code isLineSupported} false
     * positives — see {@link #probeFormats}), so caching avoids the
     * latency hit on every {@code listSupportedFormats} call.
     */
    private final Map<String, List<AudioFormat>> inputFormatsCache  = new ConcurrentHashMap<>();
    private final Map<String, List<AudioFormat>> outputFormatsCache = new ConcurrentHashMap<>();

    JavaSoundDeviceManager() {}

    public List<DeviceRef> listInputDevices()  { return list(true);  }
    public List<DeviceRef> listOutputDevices() { return list(false); }

    private List<DeviceRef> list(boolean input) {
        List<DeviceRef> out = new ArrayList<>();
        Class<? extends Line> probe = input ? TargetDataLine.class : SourceDataLine.class;
        int slot = 0;
        for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
            Mixer m;
            try {
                m = AudioSystem.getMixer(mi);
            } catch (Throwable t) {
                log.warn("Could not open mixer {}: {}", mi.getName(), t.getMessage());
                continue;
            }
            if (!m.isLineSupported(new DataLine.Info(probe, null))) continue;
            out.add(new JavaSoundDeviceRef(
                    slot++,
                    mi.getName(),
                    mi.getDescription(),
                    mi.getVendor(),
                    input, !input,
                    mi));
        }
        return out;
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
     * Probes a small set of rates × bit depths against the device's mixer
     * and returns the {@link AudioFormat}s the line will actually open at.
     * Cached per mixer name.
     */
    public List<AudioFormat> listSupportedFormats(DeviceRef device, boolean output) {
        if (!(device instanceof JavaSoundDeviceRef d)) return new ArrayList<>();
        Map<String, List<AudioFormat>> cache = output ? outputFormatsCache : inputFormatsCache;
        return cache.computeIfAbsent(d.name(), k -> probeFormats(d, output));
    }

    /**
     * Two probe paths, chosen by host OS:
     * <ul>
     *   <li>On <strong>Linux</strong> we trust hardware-reported caps,
     *       not a hard-coded candidate list.  {@link ProcAsound} parses
     *       {@code /proc/asound/card*\/codec#*} and {@code /stream*} to
     *       discover the rates and bit depths the codec actually
     *       supports — analog Audio Input / Output nodes only — and we
     *       cross those into the AudioFormat list directly.  This is
     *       why 20-bit ADCs (Realtek ALC262 and friends) are now
     *       selectable: nothing filters them out.</li>
     *   <li>On <strong>Windows / macOS</strong> we open and immediately
     *       close a line for each (rate, bits) pair from a standard
     *       pro-audio candidate set — the OS audio engine refuses
     *       formats the hardware can't handle, so open-and-test is the
     *       reliable signal.</li>
     * </ul>
     */
    private List<AudioFormat> probeFormats(JavaSoundDeviceRef d, boolean output) {
        Class<? extends DataLine> cls = output ? SourceDataLine.class : TargetDataLine.class;
        Mixer m = AudioSystem.getMixer(d.mixerInfo());

        boolean linux = System.getProperty("os.name", "").toLowerCase().contains("linux");
        if (linux) {
            return probeFormatsLinux(m, output);
        }

        int[] rates  = {44100, 48000, 88200, 96000, 176400, 192000,
                        352800, 384000, 705600, 768000};
        int[] depths = {16, 24, 32};
        List<AudioFormat> result = new ArrayList<>();
        for (int rate : rates) {
            for (int bits : depths) {
                AudioFormat fmt = buildFormat(rate, bits);
                if (canOpen(m, cls, fmt)) {
                    result.add(fmt);
                }
            }
        }
        return result;
    }

    /**
     * Linux probe — hardware caps are taken straight from the kernel
     * (analog ADC/DAC nodes in {@code /proc/asound}) or from the
     * mixer's own {@link DataLine.Info#getFormats()} when {@code /proc}
     * isn't readable.  No fixed candidate list, so the user sees every
     * rate and every bit depth the silicon really supports — including
     * unusual ones like 20 bit and 22.05 kHz.
     */
    private List<AudioFormat> probeFormatsLinux(Mixer m, boolean output) {
        // 1. /proc/asound is the source of truth when present.
        int[] hwRates  = ProcAsound.hardwareRates(output);
        int[] hwDepths = ProcAsound.hardwareBitDepths(output);
        if (hwRates.length > 0 && hwDepths.length > 0) {
            List<AudioFormat> result = new ArrayList<>();
            for (int rate : hwRates) {
                for (int bits : hwDepths) {
                    result.add(buildFormat(rate, bits));
                }
            }
            return result;
        }

        // 2. /proc/asound unavailable (containers, non-Linux kernels) —
        //    walk the mixer's own getFormats() for whatever explicit
        //    (rate, bits) pairs it advertises.  NOT_SPECIFIED entries
        //    carry no hardware information so they're skipped; an empty
        //    result triggers PreferencesDialog's default-rate fallback.
        Line.Info[] lineInfos = output ? m.getSourceLineInfo() : m.getTargetLineInfo();
        java.util.TreeSet<Integer> rates  = new java.util.TreeSet<>();
        java.util.TreeSet<Integer> depths = new java.util.TreeSet<>();
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
     *  so the frame size is correct: 20-bit stereo → 6 bytes/frame. */
    private static AudioFormat buildFormat(int rate, int bits) {
        int bytesPerSample = (bits + 7) / 8;
        int frameSize      = bytesPerSample * 2;
        return new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                rate, bits, 2, frameSize, rate, false);
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
            if (line != null) {
                try { line.close(); } catch (Throwable ignored) {}
            }
        }
    }
}
