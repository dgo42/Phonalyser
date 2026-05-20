package org.edgo.audio.measure.sound;

import lombok.extern.log4j.Log4j2;

import javax.sound.sampled.AudioFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.edgo.audio.measure.enums.AudioBackendType;

/**
 * Discovery for the {@link AudioBackendType#WDMKS} backend.  Constructed and
 * owned by {@link AudioBackend} (the singleton factory); do not instantiate
 * directly.
 *
 * <p>Enumerates devices belonging to PortAudio's {@code paWDMKS} host API.
 * The {@code index()} of the returned {@link DeviceRef} is the slot inside the
 * WDM-KS device list (so {@code --device 0} is the first WDM-KS device, not
 * the global PortAudio index).
 */
@Log4j2
public class WdmksDeviceManager {

    /** {@link DeviceRef} backed by a PortAudio WDM-KS device index. */
    public record WdmksDeviceRef(int index, String name, String description, String vendor,
                                 boolean isInput, boolean isOutput,
                                 int paDeviceIndex, double defaultSampleRate)
            implements DeviceRef {
        @Override
        public AudioBackendType backend() {
            return AudioBackendType.WDMKS;
        }
        @Override
        public String toString() {
            return displayName();
        }
    }

    /**
     * Cached probe results per WDM-KS device name.  Pa_IsFormatSupported is
     * already cheap, but caching keeps the Preferences dialog consistent
     * with the CSJSOUND backend and avoids the (small) JNA overhead on
     * repeated dialog opens.  Eviction happens inside {@link #list} when a
     * device disappears between enumerations.
     */
    private final Map<String, List<AudioFormat>> inputFormatsCache  = new ConcurrentHashMap<>();
    private final Map<String, List<AudioFormat>> outputFormatsCache = new ConcurrentHashMap<>();

    WdmksDeviceManager() {}

    private int wdmksHostApiIndex() {
        int idx = PortAudio.lib().Pa_HostApiTypeIdToHostApiIndex(PortAudio.paWDMKS);
        if (idx < 0) {
            throw new IllegalStateException(
                    "WDM-KS host API not available in this PortAudio build (rc=" + idx + ")");
        }
        return idx;
    }

    public List<DeviceRef> listInputDevices() {
        return list(true);
    }

    public List<DeviceRef> listOutputDevices() {
        return list(false);
    }

    private List<DeviceRef> list(boolean input) {
        List<DeviceRef> out = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();
        PortAudio.Lib lib = PortAudio.lib();
        int hostApi = wdmksHostApiIndex();
        PortAudio.PaHostApiInfo apiInfo = lib.Pa_GetHostApiInfo(hostApi);
        if (apiInfo == null) return out;

        int slot = 0;
        for (int i = 0; i < apiInfo.deviceCount; i++) {
            int paDev = lib.Pa_HostApiDeviceIndexToDeviceIndex(hostApi, i);
            if (paDev < 0) continue;
            PortAudio.PaDeviceInfo info = lib.Pa_GetDeviceInfo(paDev);
            if (info == null) continue;
            boolean canIn  = info.maxInputChannels  >= 2;
            boolean canOut = info.maxOutputChannels >= 2;
            if (input ? !canIn : !canOut) continue;
            out.add(new WdmksDeviceRef(slot++, info.name,
                    "WDM-KS", apiInfo.name,
                    canIn, canOut, paDev, info.defaultSampleRate));
            seenNames.add(info.name);
        }
        // Drop cached format lists for devices that vanished since the last
        // enumeration.  Surviving entries stay so the next listSupportedFormats
        // call on a known device returns instantly.
        (input ? inputFormatsCache : outputFormatsCache).keySet().retainAll(seenNames);
        return out;
    }

    public DeviceRef getDeviceByIndex(int index, boolean isOutput) {
        List<DeviceRef> all = isOutput ? listOutputDevices() : listInputDevices();
        if (index < 0 || index >= all.size()) {
            throw new IllegalArgumentException("WDM-KS device index out of range: " + index
                    + " (have " + all.size() + " " + (isOutput ? "output" : "input") + " devices)");
        }
        return all.get(index);
    }

    /**
     * Probes a small set of standard rates × bit depths and returns the
     * AudioFormats that PortAudio reports as supported on the given device.
     * The result is cached per device name on first access, so repeat calls
     * (subsequent Preferences-dialog opens, combo selections returning to a
     * previously seen device) come back from memory.
     */
    public List<AudioFormat> listSupportedFormats(DeviceRef device, boolean output) {
        if (!(device instanceof WdmksDeviceRef d)) return new ArrayList<>();
        Map<String, List<AudioFormat>> cache = output ? outputFormatsCache : inputFormatsCache;
        return cache.computeIfAbsent(d.name(), k -> probeFormats(d, output));
    }

    private List<AudioFormat> probeFormats(WdmksDeviceRef d, boolean output) {
        List<AudioFormat> result = new ArrayList<>();
        int[] rates  = {44100, 48000, 88200, 96000, 176400, 192000,
                        352800, 384000, 705600, 768000};
        int[] depths = {16, 24, 32};

        PortAudio.PaStreamParameters params = new PortAudio.PaStreamParameters();
        params.device                    = d.paDeviceIndex();
        params.channelCount              = 2;
        params.suggestedLatency          = 0.0;
        params.hostApiSpecificStreamInfo = null;

        for (int rate : rates) {
            for (int bits : depths) {
                params.sampleFormat = PortAudio.paSampleFormatFor(bits);
                params.write();
                int rc = output
                        ? PortAudio.lib().Pa_IsFormatSupported(null,   params, rate)
                        : PortAudio.lib().Pa_IsFormatSupported(params, null,   rate);
                if (rc == PortAudio.paFormatIsSupported) {
                    result.add(new AudioFormat(
                            AudioFormat.Encoding.PCM_SIGNED,
                            rate, bits, 2, (bits / 8) * 2, rate, false));
                }
            }
        }
        return result;
    }

}
