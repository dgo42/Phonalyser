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

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import javax.sound.sampled.AudioFormat;

import org.edgo.audio.measure.enums.AudioBackendType;

import lombok.extern.log4j.Log4j2;

/**
 * Process-wide audio backend selection and the dispatch point used by
 * {@code Main}.  Singleton — access via {@link #instance()}.  Set the
 * active backend with {@link #setActive}; the CLI resolves it from
 * {@code --backend wdmks|wasapi} and the default comes from the host OS.
 *
 * <p>All discovery and stream-open calls below dispatch to the active
 * backend, so the rest of the code can stay backend-agnostic by
 * working with {@link DeviceRef}, {@link AudioCapture} and
 * {@link AudioPlayback}.
 *
 * <h2>Backend discovery</h2>
 * This class names NO concrete backend.  Each backend module registers an
 * {@link AudioDeviceManagerProvider} through the service loader, and the
 * providers are indexed by {@link AudioBackendType} on first use.  That is what
 * lets the core module sit at the bottom of the dependency graph: a headless
 * build ships only the backends it needs, and a backend that is absent simply
 * never appears in the registry rather than failing to link.
 *
 * <p>Manager construction stays lazy exactly as before — discovering a provider
 * is free, and {@link AudioDeviceManagerProvider#create()} runs at most once per
 * backend, on first use.
 */
@Log4j2
public final class AudioBackend {

    private static volatile AudioBackend instance;

    private volatile AudioBackendType active = AudioBackendType.fromOs();

    /** Registered providers, indexed on first access.  Never null once
     *  {@link #providers()} has run. */
    private volatile Map<AudioBackendType, AudioDeviceManagerProvider> providers;
    /** Managers built so far.  Guarded by {@code this}; read without the lock
     *  only through {@link #managerIfCreated}, which tolerates a stale miss. */
    private final Map<AudioBackendType, AudioDeviceManager> created =
            new EnumMap<>(AudioBackendType.class);

    private AudioBackend() {}

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

    /** The provider index, built once from the service loader.  A duplicate
     *  claim on a backend type is logged and ignored — first registration wins,
     *  which keeps the outcome deterministic if two modules ever collide. */
    private Map<AudioBackendType, AudioDeviceManagerProvider> providers() {
        Map<AudioBackendType, AudioDeviceManagerProvider> local = providers;
        if (local != null) return local;
        synchronized (this) {
            if (providers != null) return providers;
            Map<AudioBackendType, AudioDeviceManagerProvider> found =
                    new EnumMap<>(AudioBackendType.class);
            for (AudioDeviceManagerProvider p : ServiceLoader.load(AudioDeviceManagerProvider.class)) {
                AudioDeviceManagerProvider previous = found.putIfAbsent(p.backendType(), p);
                if (previous != null) {
                    log.warn("Audio backend {}: duplicate provider {} ignored (kept {})",
                            p.backendType(), p.getClass().getName(), previous.getClass().getName());
                }
            }
            log.info("Audio backends registered: {}", found.keySet());
            providers = found;
            return found;
        }
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
        synchronized (this) {
            return created.get(type);
        }
    }

    /**
     * The manager for {@code type}, created on demand.  Unlike
     * {@link #managerIfCreated}, this DOES construct: the Preferences dialog
     * asks a backend the user has merely selected in the combo whether it
     * offers custom settings, which cannot wait for that backend to go active.
     * Construction opens no device — every manager reaches its hardware lazily.
     *
     * @throws IllegalStateException when no module on the class path provides
     *         {@code type}.  That is a packaging fault, not a runtime
     *         condition, so it fails loudly rather than silently substituting
     *         another backend.
     */
    public AudioDeviceManager manager(AudioBackendType type) {
        synchronized (this) {
            AudioDeviceManager existing = created.get(type);
            if (existing != null) return existing;
            AudioDeviceManagerProvider provider = providers().get(type);
            if (provider == null) {
                throw new IllegalStateException(
                        "No audio backend registered for " + type
                                + " — the corresponding backend module is not on the class path");
            }
            AudioDeviceManager built = provider.create();
            created.put(type, built);
            return built;
        }
    }

    /** {@code true} when a module supplying {@code type} is on the class path.
     *  Lets the UI offer only the backends this build actually ships. */
    public boolean isAvailable(AudioBackendType type) {
        return providers().containsKey(type);
    }

    private AudioDeviceManager activeManager() {
        return manager(active);
    }

    public List<DeviceRef> listInputDevices() {
        return activeManager().listInputDevices();
    }

    public List<DeviceRef> listOutputDevices() {
        return activeManager().listOutputDevices();
    }

    public DeviceRef getDeviceByIndex(int index, boolean isOutput) {
        return activeManager().getDeviceByIndex(index, isOutput);
    }

    public List<AudioFormat> listSupportedInputFormats(DeviceRef device) {
        return manager(device.backend()).listSupportedFormats(device, false);
    }

    public List<AudioFormat> listSupportedOutputFormats(DeviceRef device) {
        return manager(device.backend()).listSupportedFormats(device, true);
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
        return manager(type).listInputDevices();
    }

    public List<DeviceRef> listOutputDevices(AudioBackendType type) {
        return manager(type).listOutputDevices();
    }

    public List<AudioFormat> listSupportedInputFormats(AudioBackendType type, DeviceRef device) {
        return manager(type).listSupportedFormats(device, false);
    }

    public List<AudioFormat> listSupportedOutputFormats(AudioBackendType type, DeviceRef device) {
        return manager(type).listSupportedFormats(device, true);
    }

    public AudioCapture openCapture(DeviceRef device, int sampleRate, int bitDepth) {
        return manager(device.backend()).openCapture(device, sampleRate, bitDepth);
    }

    public AudioPlayback openPlayback(DeviceRef device, int sampleRate, int bitDepth, double ditherBits) {
        AudioBackendType type = device.backend();
        // Our direct WASAPI render loop produces periodic 1-period gaps on some
        // exclusive-mode drivers; the JavaSound SourceDataLine path (the same
        // code File-play uses) is gap-free.  WASAPI playback is therefore routed
        // through the JavaSound manager while still honouring the user-selected
        // WASAPI device name — the mixer is matched by name inside the JavaSound
        // manager's output-line opener.  That same path serves the explicit
        // JAVASOUND backend on Linux/macOS.
        if (type == AudioBackendType.WASAPI) {
            type = AudioBackendType.JAVASOUND;
        }
        return manager(type).openPlayback(device, sampleRate, bitDepth, ditherBits);
    }

    /** The JavaSound mixer authority — exposed so file playback can open its
     *  output line on the SAME selected device the DDS tone uses (and reach
     *  high formats, e.g. 384&nbsp;kHz / 24-bit, the default mixer refuses).
     *  Returned as the SPI type: the concrete manager lives in a module core
     *  must not name, so the caller (which already depends on that module)
     *  narrows it. */
    public AudioDeviceManager javaSoundManager() {
        return manager(AudioBackendType.JAVASOUND);
    }

    /** The QA40x session manager — exposed so a Preferences-committed active-range
     *  change can be routed to the open device. */
    public AudioDeviceManager qa40xManager() {
        return manager(AudioBackendType.QA40X);
    }

    /** App-exit teardown of the ACTIVE backend's manager — the only one that
     *  can still hold live device state, because {@link #setActive} already
     *  shuts a backend down when it is deactivated.  See
     *  {@link AudioDeviceManager#shutdown()} (a no-op for most backends; a
     *  backend whose device carries state across process death overrides it).
     *  Uses {@link #managerIfCreated}, never {@link #manager}: the exit path
     *  must not construct anything.  Called explicitly from the exit code —
     *  this app skips JVM shutdown hooks (see {@code GuiMain}). */
    public void shutdown() {
        AudioDeviceManager m = managerIfCreated(active);
        if (m != null) {
            m.shutdown();
        }
    }
}
