package org.edgo.audio.measure.sound;

import javax.sound.sampled.*;
import javax.sound.sampled.spi.MixerProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovery for the {@link AudioBackendType#CSJSOUND} backend.  Constructed
 * and owned by {@link AudioBackend} (the singleton factory); do not
 * instantiate directly.  When csjsound-provider is on the classpath, lists
 * only its WASAPI exclusive-mode devices (32-bit capable).  Falls back to the
 * default Java Sound devices otherwise.
 *
 * <p>For the {@link AudioBackendType#WDMKS} backend see {@link WdmksDeviceManager}.
 */
public class CsjsoundDeviceManager {

    /** {@link DeviceRef} backed by a {@code javax.sound.sampled.Mixer.Info}. */
    public record MixerDeviceRef(int index, String name, String description, String vendor,
                                 boolean isInput, boolean isOutput, Mixer.Info mixerInfo)
            implements DeviceRef {
        @Override
        public AudioBackendType backend() {
            return AudioBackendType.CSJSOUND;
        }
        @Override
        public String toString() {
            return displayName();
        }
    }

    /**
     * Cached probe results per mixer name.  Probing a CSJSOUND mixer opens
     * and closes a TargetDataLine / SourceDataLine for every candidate
     * (rate × depth) — at ~50 ms a pop that totals several seconds per
     * device.  Once probed, the device's supported-format list doesn't
     * change unless the user replaces the driver, so caching across
     * Preferences-dialog opens (and across device-list refreshes) is safe
     * and saves the user from the multi-second wait each time the dialog
     * opens.  Keyed by {@link Mixer.Info#getName()} so the entry survives
     * across {@code AudioSystem.getMixerInfo()} re-enumerations as long as
     * the device's reported name stays the same.
     */
    private final Map<String, List<AudioFormat>> inputFormatsCache  = new ConcurrentHashMap<>();
    private final Map<String, List<AudioFormat>> outputFormatsCache = new ConcurrentHashMap<>();

    CsjsoundDeviceManager() {}

    /** Returns the csjsound MixerProvider if loaded, empty otherwise. */
    private Optional<MixerProvider> csjsoundProvider() {
        for (MixerProvider p : ServiceLoader.load(MixerProvider.class)) {
            if (p.getClass().getName().toLowerCase().contains("csj")) {
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    /** Raw Mixer.Info array from csjsound, or from AudioSystem if csjsound is absent. */
    private Mixer.Info[] allMixerInfos() {
        return csjsoundProvider()
                .map(MixerProvider::getMixerInfo)
                .orElseGet(AudioSystem::getMixerInfo);
    }

    /** Returns all input-capable devices from the active provider. */
    public List<DeviceRef> listInputDevices() {
        List<DeviceRef> result = new ArrayList<>();
        java.util.Set<String> seenNames = new java.util.HashSet<>();
        Mixer.Info[] infos = allMixerInfos();

        for (int i = 0; i < infos.length; i++) {
            Mixer.Info info = infos[i];
            try (Mixer mixer = AudioSystem.getMixer(info)) {
                for (Line.Info lineInfo : mixer.getTargetLineInfo()) {
                    if (lineInfo instanceof DataLine.Info) {
                        result.add(new MixerDeviceRef(i, info.getName(), info.getDescription(),
                                info.getVendor(), true, false, info));
                        seenNames.add(info.getName());
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        // Drop any cached input-format entries for mixers that vanished
        // since the last enumeration (unplugged, driver removed, etc.).
        inputFormatsCache.keySet().retainAll(seenNames);
        return result;
    }

    /** Returns the {@link DeviceRef} at the given (csjsound-mixer) index. */
    public DeviceRef getDeviceByIndex(int index, boolean isOutput) {
        Mixer.Info[] all = allMixerInfos();
        if (index < 0 || index >= all.length) {
            throw new IllegalArgumentException("Mixer index out of range: " + index);
        }
        Mixer.Info info = all[index];
        return new MixerDeviceRef(index, info.getName(), info.getDescription(),
                info.getVendor(), !isOutput, isOutput, info);
    }

    /** Returns all output-capable devices from the active provider. */
    public List<DeviceRef> listOutputDevices() {
        List<DeviceRef> result = new ArrayList<>();
        java.util.Set<String> seenNames = new java.util.HashSet<>();
        Mixer.Info[] infos = allMixerInfos();

        for (int i = 0; i < infos.length; i++) {
            Mixer.Info info = infos[i];
            try (Mixer mixer = AudioSystem.getMixer(info)) {
                for (Line.Info lineInfo : mixer.getSourceLineInfo()) {
                    if (lineInfo instanceof DataLine.Info) {
                        result.add(new MixerDeviceRef(i, info.getName(), info.getDescription(),
                                info.getVendor(), false, true, info));
                        seenNames.add(info.getName());
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        outputFormatsCache.keySet().retainAll(seenNames);
        return result;
    }

    /** Candidate sample rates probed against drivers that don't enumerate concrete rates. */
    private static final int[] PROBE_RATES = {
            44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000
    };
    /** Candidate bit depths probed when the declared format has an unspecified size. */
    private static final int[] PROBE_DEPTHS = { 16, 24, 32 };

    /**
     * Returns all AudioFormats supported by the TargetDataLines of the given
     * mixer.  Formats advertised with {@link AudioSystem#NOT_SPECIFIED} for
     * sample rate or bit depth are expanded by probing candidate combinations
     * through actual {@link TargetDataLine#open} calls.  The probe result
     * for each mixer is cached on first access so subsequent Preferences-
     * dialog opens (or device-combo selections that return to a previously
     * seen device) come back instantly instead of rerunning the full scan.
     */
    public List<AudioFormat> listSupportedFormats(Mixer.Info mixerInfo) {
        return inputFormatsCache.computeIfAbsent(
                mixerInfo.getName(), k -> enumerateFormats(mixerInfo, /*output*/ false));
    }

    /**
     * Mirror of {@link #listSupportedFormats} for the SourceDataLines of the
     * given mixer.  Same caching policy.
     */
    public List<AudioFormat> listSupportedOutputFormats(Mixer.Info mixerInfo) {
        return outputFormatsCache.computeIfAbsent(
                mixerInfo.getName(), k -> enumerateFormats(mixerInfo, /*output*/ true));
    }

    private List<AudioFormat> enumerateFormats(Mixer.Info mixerInfo, boolean output) {
        List<AudioFormat> result = new ArrayList<>();
        try (Mixer mixer = AudioSystem.getMixer(mixerInfo)) {
            Line.Info[] lineInfos = output ? mixer.getSourceLineInfo() : mixer.getTargetLineInfo();
            for (Line.Info lineInfo : lineInfos) {
                if (lineInfo instanceof DataLine.Info dataLineInfo) {
                    for (AudioFormat declared : dataLineInfo.getFormats()) {
                        expandFormat(mixer, declared, output, result);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    /**
     * Expands a single declared {@link AudioFormat} into one or more concrete
     * variants the mixer actually accepts.  When the declared format has an
     * unspecified sample rate or bit depth, every candidate combination is
     * probed via {@link Mixer#isLineSupported}; only the supported probes are
     * appended to {@code out}.  Already-concrete formats are passed through
     * after a single confirmation probe so we don't list formats the driver
     * advertises but the OS-level audio stack rejects.
     */
    private void expandFormat(Mixer mixer, AudioFormat declared, boolean output,
                              List<AudioFormat> out) {
        int channels = declared.getChannels() != AudioSystem.NOT_SPECIFIED
                ? declared.getChannels() : 2;
        // Capture path always opens stereo lines, so skip mono-only declarations.
        if (channels != 2) return;

        int[] rates = (declared.getSampleRate() == AudioSystem.NOT_SPECIFIED)
                ? PROBE_RATES : new int[] { Math.round(declared.getSampleRate()) };
        int[] depths = (declared.getSampleSizeInBits() == AudioSystem.NOT_SPECIFIED)
                ? PROBE_DEPTHS : new int[] { declared.getSampleSizeInBits() };
        AudioFormat.Encoding encoding = declared.getEncoding() != null
                ? declared.getEncoding() : AudioFormat.Encoding.PCM_SIGNED;
        // WASAPI / WDM-KS on Windows is universally little-endian; respect the
        // declared bit when the line bothered to specify it, otherwise default
        // to LE so the probe matches the eventual line.open() call.
        boolean bigEndian = declared.isBigEndian();

        for (int rate : rates) {
            for (int depth : depths) {
                int frameSize = (depth / 8) * channels;
                AudioFormat probe = new AudioFormat(
                        encoding, rate, depth, channels, frameSize, rate, bigEndian);
                if (canActuallyOpen(mixer, probe, output)) {
                    out.add(probe);
                }
            }
        }
    }

    /**
     * Tries to actually acquire and open a line with {@code probe} from
     * {@code mixer}, immediately closing it again.  This is the only check
     * Java Sound exposes that catches WASAPI exclusive-mode rate rejections —
     * {@link Mixer#isLineSupported(Line.Info)} happily returns {@code true}
     * for unsupported rates whenever the underlying line was declared with
     * {@link AudioSystem#NOT_SPECIFIED} for sample rate.
     */
    private boolean canActuallyOpen(Mixer mixer, AudioFormat probe, boolean output) {
        DataLine.Info info = output
                ? new DataLine.Info(SourceDataLine.class, probe)
                : new DataLine.Info(TargetDataLine.class, probe);
        if (!mixer.isLineSupported(info)) return false;
        DataLine line = null;
        try {
            line = (DataLine) mixer.getLine(info);
            if (output) ((SourceDataLine) line).open(probe);
            else        ((TargetDataLine) line).open(probe);
            return true;
        } catch (Exception ex) {
            return false;
        } finally {
            if (line != null) {
                try { line.close(); } catch (Exception ignored) {}
            }
        }
    }
}
