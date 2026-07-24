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

import java.util.List;

import javax.sound.sampled.AudioFormat;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.sound.coreaudio.CoreAudioDeviceManager;
import org.edgo.audio.measure.sound.javasound.JavaSoundDeviceManager;
import org.edgo.audio.measure.sound.qa40x.Qa40xDeviceManager;
import org.edgo.audio.measure.sound.wasapi.WasapiDeviceManager;
import org.edgo.audio.measure.sound.wasapi.WasapiRecorder;
import org.edgo.audio.measure.sound.wdmks.WdmksDeviceManager;

import lombok.extern.log4j.Log4j2;

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
 */
@Log4j2
public final class AudioBackend {

    private static volatile AudioBackend instance;

    private volatile AudioBackendType active = AudioBackendType.fromOs();
    private volatile WdmksDeviceManager     wdmks;
    private volatile WasapiDeviceManager    wasapi;
    private volatile JavaSoundDeviceManager javaSound;
    private volatile CoreAudioDeviceManager coreAudio;
    private volatile Qa40xDeviceManager     qa40x;

    private AudioBackend() {}

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

    /** Mirror of {@link #wdmks()} for the macOS CoreAudio (PortAudio) backend. */
    private CoreAudioDeviceManager coreAudio() {
        CoreAudioDeviceManager local = coreAudio;
        if (local == null) {
            synchronized (this) {
                local = coreAudio;
                if (local == null) {
                    local = new CoreAudioDeviceManager();
                    coreAudio = local;
                }
            }
        }
        return local;
    }

    /** Mirror of {@link #wdmks()} for the QuantAsylum QA402/QA403 (libusb) backend. */
    private AudioDeviceManager qa40x() {
        Qa40xDeviceManager local = qa40x;
        if (local == null) {
            synchronized (this) {
                local = qa40x;
                if (local == null) {
                    local = new Qa40xDeviceManager();
                    qa40x = local;
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
        AudioBackendType previous = active;
        active = type;
        log.info("Audio backend: {}", type);
        // A deactivated backend gets its teardown at the switch, so its device
        // never sits in a live state while another backend measures (the QA40x
        // parks at maximum attenuation and releases its USB session; it
        // reopens on the next activation).  No-op for a first-time set or a
        // re-set of the same type.
        if (previous != type) {
            AudioDeviceManager old = managerIfCreated(previous);
            if (old != null) {
                old.shutdown();
            }
        }
    }

    /** The already-constructed manager for {@code type}, or {@code null} when
     *  that backend was never touched this session.  NEVER constructs — the
     *  teardown paths ({@link #setActive}, {@link #shutdown}) must not open
     *  anything. */
    private AudioDeviceManager managerIfCreated(AudioBackendType type) {
        switch (type) {
            case WDMKS:     return wdmks;
            case COREAUDIO: return coreAudio;
            case JAVASOUND: return javaSound;
            case QA40X:     return qa40x;
            case WASAPI:
            default:        return wasapi;
        }
    }

    public List<DeviceRef> listInputDevices() {
        switch (active) {
            case WDMKS:     return wdmks().listInputDevices();
            case COREAUDIO: return coreAudio().listInputDevices();
            case JAVASOUND: return javaSound().listInputDevices();
            case QA40X:     return qa40x().listInputDevices();
            case WASAPI:
            default:        return wasapi().listInputDevices();
        }
    }

    public List<DeviceRef> listOutputDevices() {
        switch (active) {
            case WDMKS:     return wdmks().listOutputDevices();
            case COREAUDIO: return coreAudio().listOutputDevices();
            case JAVASOUND: return javaSound().listOutputDevices();
            case QA40X:     return qa40x().listOutputDevices();
            case WASAPI:
            default:        return wasapi().listOutputDevices();
        }
    }

    public DeviceRef getDeviceByIndex(int index, boolean isOutput) {
        switch (active) {
            case WDMKS:     return wdmks().getDeviceByIndex(index, isOutput);
            case COREAUDIO: return coreAudio().getDeviceByIndex(index, isOutput);
            case JAVASOUND: return javaSound().getDeviceByIndex(index, isOutput);
            case QA40X:     return qa40x().getDeviceByIndex(index, isOutput);
            case WASAPI:
            default:        return wasapi().getDeviceByIndex(index, isOutput);
        }
    }

    public List<AudioFormat> listSupportedInputFormats(DeviceRef device) {
        switch (device.backend()) {
            case WDMKS:     return wdmks().listSupportedFormats(device, false);
            case COREAUDIO: return coreAudio().listSupportedFormats(device, false);
            case JAVASOUND: return javaSound().listSupportedFormats(device, false);
            case QA40X:     return qa40x().listSupportedFormats(device, false);
            case WASAPI:
            default:        return wasapi().listSupportedFormats(device, false);
        }
    }

    public List<AudioFormat> listSupportedOutputFormats(DeviceRef device) {
        switch (device.backend()) {
            case WDMKS:     return wdmks().listSupportedFormats(device, true);
            case COREAUDIO: return coreAudio().listSupportedFormats(device, true);
            case JAVASOUND: return javaSound().listSupportedFormats(device, true);
            case QA40X:     return qa40x().listSupportedFormats(device, true);
            case WASAPI:
            default:        return wasapi().listSupportedFormats(device, true);
        }
    }

    // -------------------------------------------------------------------------
    // Non-mutating, type-parameterised enumeration.  The active-backend
    // overloads above dispatch on the live {@code active} field; these dispatch
    // on the caller-supplied {@code type}, so the Preferences dialog can browse
    // any backend's devices and formats without flipping the live active backend
    // via {@link #setActive}.  They touch no mutable field — only the lazily
    // built per-type managers, which enumerate independently of any open stream.
    // -------------------------------------------------------------------------

    public List<DeviceRef> listInputDevices(AudioBackendType type) {
        switch (type) {
            case WDMKS:     return wdmks().listInputDevices();
            case COREAUDIO: return coreAudio().listInputDevices();
            case JAVASOUND: return javaSound().listInputDevices();
            case QA40X:     return qa40x().listInputDevices();
            case WASAPI:
            default:        return wasapi().listInputDevices();
        }
    }

    public List<DeviceRef> listOutputDevices(AudioBackendType type) {
        switch (type) {
            case WDMKS:     return wdmks().listOutputDevices();
            case COREAUDIO: return coreAudio().listOutputDevices();
            case JAVASOUND: return javaSound().listOutputDevices();
            case QA40X:     return qa40x().listOutputDevices();
            case WASAPI:
            default:        return wasapi().listOutputDevices();
        }
    }

    public List<AudioFormat> listSupportedInputFormats(AudioBackendType type, DeviceRef device) {
        switch (type) {
            case WDMKS:     return wdmks().listSupportedFormats(device, false);
            case COREAUDIO: return coreAudio().listSupportedFormats(device, false);
            case JAVASOUND: return javaSound().listSupportedFormats(device, false);
            case QA40X:     return qa40x().listSupportedFormats(device, false);
            case WASAPI:
            default:        return wasapi().listSupportedFormats(device, false);
        }
    }

    public List<AudioFormat> listSupportedOutputFormats(AudioBackendType type, DeviceRef device) {
        switch (type) {
            case WDMKS:     return wdmks().listSupportedFormats(device, true);
            case COREAUDIO: return coreAudio().listSupportedFormats(device, true);
            case JAVASOUND: return javaSound().listSupportedFormats(device, true);
            case QA40X:     return qa40x().listSupportedFormats(device, true);
            case WASAPI:
            default:        return wasapi().listSupportedFormats(device, true);
        }
    }

    public AudioCapture openCapture(DeviceRef device, int sampleRate, int bitDepth) {
        switch (device.backend()) {
            case WDMKS:
                return wdmks().openCapture(device, sampleRate, bitDepth);
            case COREAUDIO:
                return coreAudio().openCapture(device, sampleRate, bitDepth);
            case JAVASOUND:
                return javaSound().openCapture(device, sampleRate, bitDepth);
            case QA40X:
                return qa40x().openCapture(device, sampleRate, bitDepth);
            case WASAPI:
            default:
                return new WasapiRecorder(wasapi(),
                        (WasapiDeviceManager.WasapiDeviceRef) device,
                        sampleRate, bitDepth);
        }
    }

    public AudioPlayback openPlayback(DeviceRef device, int sampleRate, int bitDepth, double ditherBits) {
        switch (device.backend()) {
            case WDMKS:
                return wdmks().openPlayback(device, sampleRate, bitDepth, ditherBits);
            case COREAUDIO:
                return coreAudio().openPlayback(device, sampleRate, bitDepth, ditherBits);
            case QA40X:
                return qa40x().openPlayback(device, sampleRate, bitDepth, ditherBits);
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
                // {@link JavaSoundDeviceManager#openOutputLine}.
                return javaSound().openPlayback(device, sampleRate, bitDepth, ditherBits);
        }
    }

    /** The JavaSound mixer authority — exposed so file playback can open its
     *  output line on the SAME selected device the DDS tone uses (and reach
     *  high formats, e.g. 384&nbsp;kHz / 24-bit, the default mixer refuses). */
    public JavaSoundDeviceManager javaSoundManager() {
        return javaSound();
    }

    /** App-exit teardown of the ACTIVE backend's manager — the only one that
     *  can still hold live device state, because {@link #setActive} already
     *  shuts a backend down when it is deactivated.  See
     *  {@link AudioDeviceManager#shutdown()} (a no-op for most backends; a
     *  backend whose device carries state across process death overrides it).
     *  Uses {@link #managerIfCreated}, never the lazy accessors: the exit path
     *  must not construct anything.  Called explicitly from the exit code —
     *  this app skips JVM shutdown hooks (see {@code GuiMain}). */
    public void shutdown() {
        AudioDeviceManager m = managerIfCreated(active);
        if (m != null) {
            m.shutdown();
        }
    }

    /** The QA40x session manager — exposed so a Preferences-committed active-range
     *  change can be routed to the open device (see {@code Qa40xRangeController}). */
    public AudioDeviceManager qa40xManager() {
        return qa40x();
    }
}
