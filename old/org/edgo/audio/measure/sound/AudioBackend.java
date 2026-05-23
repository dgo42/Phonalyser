package org.edgo.audio.measure.sound;

import lombok.extern.log4j.Log4j2;

import javax.sound.sampled.AudioFormat;
import java.util.List;

/**
 * Process-wide audio backend selection and the dispatch point used by
 * {@code Main}.  Singleton — access via {@link #instance()}.  Set the active
 * backend with {@link #setActive} (the CLI resolves it from
 * {@code --backend csjsound|wdmks}; default is csjsound).
 *
 * <p>All discovery and stream-open calls below dispatch to the active
 * backend, so the rest of the code can stay backend-agnostic by
 * working with {@link DeviceRef}, {@link AudioCapture} and
 * {@link AudioPlayback}.
 */
@Log4j2
public final class AudioBackend {

    private static volatile AudioBackend instance;

    private volatile AudioBackendType active = AudioBackendType.CSJSOUND;
    private volatile CsjsoundDeviceManager csjsound;
    private volatile WdmksDeviceManager    wdmks;

    private AudioBackend() {}

    /**
     * Lazily constructs the csjsound manager on first CSJSOUND-path access.
     * Synchronized so concurrent callers can't build duplicates, and only
     * paid when the user actually selects this backend.
     */
    private CsjsoundDeviceManager csjsound() {
        CsjsoundDeviceManager local = csjsound;
        if (local == null) {
            synchronized (this) {
                local = csjsound;
                if (local == null) {
                    local = new CsjsoundDeviceManager();
                    csjsound = local;
                }
            }
        }
        return local;
    }

    /** Mirror of {@link #csjsound()} for the WDM-KS backend. */
    private WdmksDeviceManager wdmks() {
        WdmksDeviceManager local = wdmks;
        if (local == null) {
            synchronized (this) {
                local = wdmks;
                if (local == null) {
                    local = new WdmksDeviceManager();
                    wdmks = local;
                }
            }
        }
        return local;
    }

    /**
     * Returns the singleton instance, lazily creating it inside a synchronized
     * block on first access so concurrent callers cannot construct duplicates.
     */
    public static AudioBackend instance() {
        AudioBackend local = instance;
        if (local == null) {
            synchronized (AudioBackend.class) {
                local = instance;
                if (local == null) {
                    local = new AudioBackend();
                    instance = local;
                }
            }
        }
        return local;
    }

    public AudioBackendType active() {
        return active;
    }

    public void setActive(AudioBackendType type) {
        active = type;
        log.info("Audio backend: {}", type);
    }

    public List<DeviceRef> listInputDevices() {
        return active == AudioBackendType.WDMKS
                ? wdmks().listInputDevices()
                : csjsound().listInputDevices();
    }

    public List<DeviceRef> listOutputDevices() {
        return active == AudioBackendType.WDMKS
                ? wdmks().listOutputDevices()
                : csjsound().listOutputDevices();
    }

    public DeviceRef getDeviceByIndex(int index, boolean isOutput) {
        return active == AudioBackendType.WDMKS
                ? wdmks().getDeviceByIndex(index, isOutput)
                : csjsound().getDeviceByIndex(index, isOutput);
    }

    public List<AudioFormat> listSupportedInputFormats(DeviceRef device) {
        if (device.backend() == AudioBackendType.WDMKS) {
            return wdmks().listSupportedFormats(device, false);
        }
        return csjsound().listSupportedFormats(
                ((CsjsoundDeviceManager.MixerDeviceRef) device).mixerInfo());
    }

    public List<AudioFormat> listSupportedOutputFormats(DeviceRef device) {
        if (device.backend() == AudioBackendType.WDMKS) {
            return wdmks().listSupportedFormats(device, true);
        }
        return csjsound().listSupportedOutputFormats(
                ((CsjsoundDeviceManager.MixerDeviceRef) device).mixerInfo());
    }

    public AudioCapture openCapture(DeviceRef device, int sampleRate, int bitDepth) {
        if (device.backend() == AudioBackendType.WDMKS) {
            return new WdmksRecorder((WdmksDeviceManager.WdmksDeviceRef) device, sampleRate, bitDepth);
        }
        return new CsjsoundRecorder(
                ((CsjsoundDeviceManager.MixerDeviceRef) device).mixerInfo(),
                sampleRate, bitDepth);
    }

    public AudioPlayback openPlayback(DeviceRef device, int sampleRate, int bitDepth, int ditherBits) {
        if (device.backend() == AudioBackendType.WDMKS) {
            return new WdmksGenerator((WdmksDeviceManager.WdmksDeviceRef) device,
                    sampleRate, bitDepth, ditherBits);
        }
        return new CsjsoundGenerator(
                ((CsjsoundDeviceManager.MixerDeviceRef) device).mixerInfo(),
                sampleRate, bitDepth, ditherBits);
    }
}
