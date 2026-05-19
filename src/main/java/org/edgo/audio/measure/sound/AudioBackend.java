package org.edgo.audio.measure.sound;

import lombok.extern.log4j.Log4j2;

import javax.sound.sampled.AudioFormat;
import java.util.List;

/**
 * Process-wide audio backend selection and the dispatch point used by
 * {@code Main}.  Singleton — access via {@link #instance()}.  Set the
 * active backend with {@link #setActive}; the CLI resolves it from
 * {@code --backend wdmks|wasapi} and the default is WASAPI.
 *
 * <p>All discovery and stream-open calls below dispatch to the active
 * backend, so the rest of the code can stay backend-agnostic by
 * working with {@link DeviceRef}, {@link AudioCapture} and
 * {@link AudioPlayback}.
 *
 * <p>Also hosts the {@link #adcFsVoltageRms} global, which used to live
 * on {@code CsjsoundRecorder} when csjsound was the only backend that
 * actually measured the ADC's full scale — now it sits on the
 * backend-agnostic dispatcher.
 */
@Log4j2
public final class AudioBackend {

    /** ADC full-scale voltage in V_RMS.  Read from every render / FFT /
     *  measurement path, so a static-field access is the cheapest option.
     *  The calibration dialog updates this in-process; {@code Preferences}
     *  carries the persisted copy across launches. */
    public static double adcFsVoltageRms = 1.7931;

    private static volatile AudioBackend instance;

    private volatile AudioBackendType active = defaultBackend();
    private volatile WdmksDeviceManager     wdmks;
    private volatile WasapiDeviceManager    wasapi;
    private volatile JavaSoundDeviceManager javaSound;

    private AudioBackend() {}

    /** WASAPI on Windows, JAVASOUND elsewhere — the only backend that exists
     *  on Linux / macOS until a native one is added. */
    private static AudioBackendType defaultBackend() {
        return AudioBackendType.WASAPI.isAvailable()
                ? AudioBackendType.WASAPI
                : AudioBackendType.JAVASOUND;
    }

    /** Lazily constructs the WDM-KS manager on first WDMKS-path access. */
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

    /** Mirror of {@link #wdmks()} for the WASAPI backend. */
    private WasapiDeviceManager wasapi() {
        WasapiDeviceManager local = wasapi;
        if (local == null) {
            synchronized (this) {
                local = wasapi;
                if (local == null) {
                    local = new WasapiDeviceManager();
                    wasapi = local;
                }
            }
        }
        return local;
    }

    /** Mirror of {@link #wdmks()} for the cross-platform JavaSound backend. */
    private JavaSoundDeviceManager javaSound() {
        JavaSoundDeviceManager local = javaSound;
        if (local == null) {
            synchronized (this) {
                local = javaSound;
                if (local == null) {
                    local = new JavaSoundDeviceManager();
                    javaSound = local;
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
        switch (active) {
            case WDMKS:     return wdmks().listInputDevices();
            case JAVASOUND: return javaSound().listInputDevices();
            case WASAPI:
            default:        return wasapi().listInputDevices();
        }
    }

    public List<DeviceRef> listOutputDevices() {
        switch (active) {
            case WDMKS:     return wdmks().listOutputDevices();
            case JAVASOUND: return javaSound().listOutputDevices();
            case WASAPI:
            default:        return wasapi().listOutputDevices();
        }
    }

    public DeviceRef getDeviceByIndex(int index, boolean isOutput) {
        switch (active) {
            case WDMKS:     return wdmks().getDeviceByIndex(index, isOutput);
            case JAVASOUND: return javaSound().getDeviceByIndex(index, isOutput);
            case WASAPI:
            default:        return wasapi().getDeviceByIndex(index, isOutput);
        }
    }

    public List<AudioFormat> listSupportedInputFormats(DeviceRef device) {
        switch (device.backend()) {
            case WDMKS:     return wdmks().listSupportedFormats(device, false);
            case JAVASOUND: return javaSound().listSupportedFormats(device, false);
            case WASAPI:
            default:        return wasapi().listSupportedFormats(device, false);
        }
    }

    public List<AudioFormat> listSupportedOutputFormats(DeviceRef device) {
        switch (device.backend()) {
            case WDMKS:     return wdmks().listSupportedFormats(device, true);
            case JAVASOUND: return javaSound().listSupportedFormats(device, true);
            case WASAPI:
            default:        return wasapi().listSupportedFormats(device, true);
        }
    }

    public AudioCapture openCapture(DeviceRef device, int sampleRate, int bitDepth) {
        switch (device.backend()) {
            case WDMKS:
                return new WdmksRecorder((WdmksDeviceManager.WdmksDeviceRef) device,
                        sampleRate, bitDepth);
            case JAVASOUND:
                return new JavaSoundRecorder(
                        (JavaSoundDeviceManager.JavaSoundDeviceRef) device,
                        sampleRate, bitDepth);
            case WASAPI:
            default:
                return new WasapiRecorder(wasapi(),
                        (WasapiDeviceManager.WasapiDeviceRef) device,
                        sampleRate, bitDepth);
        }
    }

    public AudioPlayback openPlayback(DeviceRef device, int sampleRate, int bitDepth, int ditherBits) {
        switch (device.backend()) {
            case WDMKS:
                return new WdmksGenerator((WdmksDeviceManager.WdmksDeviceRef) device,
                        sampleRate, bitDepth, ditherBits);
            case JAVASOUND:
            case WASAPI:
            default:
                // Our direct WasapiGenerator render loop produces periodic
                // 1-period gaps on some exclusive-mode drivers; the
                // JavaSound SourceDataLine path (same code FilePlay uses)
                // is gap-free.  Routing WASAPI playback through
                // JavaSoundGenerator while honouring the user-selected
                // WASAPI device name; the legacy WasapiGenerator class
                // stays in the tree for reference but is no longer used.
                //
                // The same path serves the explicit JAVASOUND backend on
                // Linux/macOS, with the mixer being matched by name inside
                // {@link JavaSoundGenerator#findMixer}.
                return new JavaSoundGenerator(sampleRate, bitDepth, ditherBits,
                        device.name());
        }
    }
}
