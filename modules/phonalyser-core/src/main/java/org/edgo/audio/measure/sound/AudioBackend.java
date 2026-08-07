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

package org.edgo.audio.measure.sound;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sound.sampled.AudioFormat;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.preferences.BackendPrefs;
import org.edgo.audio.measure.preferences.Preferences;

import lombok.extern.log4j.Log4j2;

/**
 * Process-wide audio backend selection and the dispatch point used by
 * {@code Main}.  Singleton - access via {@link #instance()}.  Set the
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
 * <p>Manager construction stays lazy exactly as before - discovering a provider
 * is free, and {@link AudioDeviceManagerProvider#create()} runs at most once per
 * backend, on first use.
 */
@Log4j2
public final class AudioBackend {

    private static volatile AudioBackend instance;

    /** The ACTIVE selection, whole: the true type AND the server it lives on
     *  (null server = this machine).  Kept as a {@link BackendKey} so both
     *  levels stay askable - {@link #activeType()} answers what the backend IS,
     *  {@link #active()} what it is dispatched through. */
    private volatile BackendKey activeKey = BackendKey.of(AudioBackendType.fromOs());

    /** Registered providers, indexed on first access.  Never null once
     *  {@link #providers()} has run. */
    private volatile Map<AudioBackendType, AudioDeviceManagerProvider> providers;
    /** Managers built so far.  Guarded by {@code this}; read without the lock
     *  only through {@link #managerIfCreated}, which tolerates a stale miss. */
    private final Map<AudioBackendType, AudioDeviceManager> created =
            new EnumMap<>(AudioBackendType.class);

    private static final String SETUP_THREAD = "backend-setup";
    /** How long {@link #teardown()} waits for a start-up sweep still in flight.
     *  Not a correctness barrier - every implementation is idempotent - but
     *  letting a park and an unpark cross on the same USB device buys nothing,
     *  and the application is quitting, so the wait is short and bounded. */
    private static final long SETUP_JOIN_MS = 2_000;

    /** The background start-up sweep while it runs, else null - what
     *  {@link #teardown()} waits on.  Volatile: written by the thread that
     *  starts it, read by the one that tears down. */
    private volatile Thread setupSweep;
    /** Guards against a second background sweep.  {@code setup()} itself is
     *  idempotent; this stops the application paying for the enumeration twice. */
    private final AtomicBoolean setupStarted = new AtomicBoolean();

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
     *  claim on a backend type is logged and ignored - first registration wins,
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

    /** The whole active selection - which backend, and on which server. */
    public BackendKey activeKey() {
        return activeKey;
    }

    /** What the active backend IS - the TRUE type, local or remote alike (a
     *  remote QA403 answers {@link AudioBackendType#QA40X}).  Type-specific
     *  behaviour keys on this. */
    public AudioBackendType activeType() {
        return activeKey.type();
    }

    /** What the active selection is DISPATCHED through - the carrier: the type
     *  itself when local, {@link AudioBackendType#NET} for any remote bench.
     *  Routing only; for identity ask {@link #activeType()}. */
    public AudioBackendType active() {
        return activeKey.carrier();
    }

    /** Kernel settle after a carrier's teardown, before anything may open the
     *  next one.  A live switch closes one host API's streams and immediately
     *  opens another's on the SAME hardware, and the drivers underneath share
     *  state we cannot see: the hot WASAPI->WDM-KS switch bluescreened the bench
     *  twice with 0x10D WDF_VIOLATION - a KMDF fault inside the USB audio class
     *  driver, unreachable from this process.  The pause gives the departing
     *  driver its teardown window: the previous carrier must be disconnected and
     *  confirmed gone, and only then, after this settle, may the next one be
     *  activated.  Uniform for every carrier switch - no backend is
     *  special-cased, per the identical-behaviour rule. */
    private static final long CARRIER_SWITCH_SETTLE_MS = 500L;

    /** Activates a selection - both levels of it.  A deactivated CARRIER gets
     *  its teardown at the switch, so its device never sits in a live state
     *  while another backend measures (the QA40x parks at maximum attenuation
     *  and releases its USB session; it reopens on the next activation).
     *  Compared at the carrier level on purpose: switching between two servers
     *  keeps the one NET manager and its session alive.  After the teardown the
     *  switch waits {@link #CARRIER_SWITCH_SETTLE_MS} before returning, so the
     *  first open on the new carrier cannot race the old driver's teardown -
     *  activation itself is lazy, which makes this return the last gate. */
    public void setActive(BackendKey key) {
        AudioBackendType previous = activeKey.carrier();
        activeKey = key;
        log.info("Audio backend: {}", key.key());
        if (previous != key.carrier()) {
            AudioDeviceManager old = managerIfCreated(previous);
            if (old != null) {
                old.shutdown();
                try {
                    Thread.sleep(CARRIER_SWITCH_SETTLE_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** A LOCAL selection by bare type - the CLI's and the startup restore's
     *  form.  A dual-level carrier is refused by {@link BackendKey}: a remote
     *  bench is activated by its full key, never by the carrier alone. */
    public void setActive(AudioBackendType type) {
        setActive(BackendKey.of(type));
    }

    /** The already-constructed manager for {@code type}, or {@code null} when
     *  that backend was never touched this session.  NEVER constructs - the
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
     * Construction opens no device - every manager reaches its hardware lazily.
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
                                + " - the corresponding backend module is not on the class path");
            }
            AudioDeviceManager built = provider.create();
            created.put(type, built);
            return built;
        }
    }

    /** {@code true} when a module supplying {@code type} is on the class path
     *  AND that module says it can run here ({@link
     *  AudioDeviceManagerProvider#available()} - the QA40x provider probes its
     *  {@code libusb-1.0} binding).  Lets the UI offer only the backends this
     *  build actually ships and this process can actually open. */
    public boolean isAvailable(AudioBackendType type) {
        AudioDeviceManagerProvider provider = providers().get(type);
        return provider != null && provider.available();
    }

    private AudioDeviceManager activeManager() {
        return manager(activeKey.carrier());
    }

    public List<DeviceRef> listInputDevices() {
        return activeManager().listInputDevices();
    }

    public List<DeviceRef> listOutputDevices() {
        return activeManager().listOutputDevices();
    }

    /**
     * The ACTIVE input device - the one the operator's preferences name on the
     * active backend - resolved against the backend's live device list.  This
     * is THE resolution seam: a module never matches a device name against a
     * list itself, it takes this handle and opens it
     * ({@link #openCapture(DeviceRef)}).  Applying the device profile (the
     * per-card ADC full scale on the runtime scalars) is the CALLER's step
     * after resolution - a preference write belongs on the UI thread, which
     * this backend has no access to.
     *
     * @return the ready-to-open handle, or {@code null} when no input device is
     *         configured or the configured one is not present right now - the
     *         caller owns the user-facing wording of that difference
     */
    public DeviceRef getActiveInputDevice() {
        return activeDevice(true);
    }

    /** The ACTIVE output device; the exact mirror of
     *  {@link #getActiveInputDevice()} for the playback direction (the DAC
     *  full scale is what the caller's profile application primes). */
    public DeviceRef getActiveOutputDevice() {
        return activeDevice(false);
    }

    private DeviceRef activeDevice(boolean input) {
        Preferences prefs = Preferences.instance();
        BackendPrefs bp = prefs.current();
        String name = input ? bp.getInputDeviceName() : bp.getOutputDeviceName();
        if (name == null || name.isEmpty()) {
            return null;
        }
        DeviceRef device = findByName(name, input);
        if (device == null && activeManager().refreshDeviceList()) {
            // A saved name that no longer resolves is the one signal a snapshot
            // enumeration gives that it has gone stale (a replugged card is a NEW
            // entry a stale list cannot contain, and open() is never reached to
            // fail).  One rebuild, one re-look - a second miss means the device
            // is genuinely not there.
            device = findByName(name, input);
        }
        return device;
    }

    private DeviceRef findByName(String name, boolean input) {
        for (DeviceRef device : input ? listInputDevices() : listOutputDevices()) {
            if (name.equals(device.name())) {
                return device;
            }
        }
        return null;
    }

    public DeviceRef getDeviceByIndex(int index, boolean isOutput) {
        return activeManager().getDeviceByIndex(index, isOutput);
    }

    public List<AudioFormat> listSupportedInputFormats(DeviceRef device) {
        return manager(device.carrier()).listSupportedFormats(device, false);
    }

    public List<AudioFormat> listSupportedOutputFormats(DeviceRef device) {
        return manager(device.carrier()).listSupportedFormats(device, true);
    }

    // -------------------------------------------------------------------------
    // Non-mutating, type-parameterised enumeration.  The active-backend
    // overloads above dispatch on the live {@code activeKey}; these dispatch
    // on the caller-supplied CARRIER type, so the Preferences dialog can browse
    // any backend's devices and formats without flipping the live active backend
    // via {@link #setActive}.  They touch no mutable field - only the lazily
    // built per-type managers, which enumerate independently of any open stream.
    // -------------------------------------------------------------------------

    /** Rebuilds the {@code type} backend's device enumeration on the operator's
     *  explicit scan - see {@link AudioDeviceManager#refreshDeviceList()}.  The
     *  same interface for every backend: one whose list is always live (or is
     *  kept fresh elsewhere, as a server keeps its own) answers false and the
     *  following re-list simply returns the current truth. */
    public boolean refreshDeviceLists(AudioBackendType type) {
        return manager(type).refreshDeviceList();
    }

    /** Whether the {@code type} backend's device list has gone stale under it -
     *  what a POLL asks before it spends a rebuild (the net server's hot-plug
     *  tick), where the operator's scan above simply rebuilds because they asked.
     *  See {@link AudioDeviceManager#deviceListStale()}. */
    public boolean deviceListStale(AudioBackendType type) {
        return manager(type).deviceListStale();
    }

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

    /** Opens {@code device} at the rate and depth the active backend's
     *  preferences carry - the device's format IS the preferences, so there is
     *  nothing for a caller to pass beside the handle.  A path that must open
     *  at an EXPLICIT rate (the net server honouring a wire request, the CLI
     *  honouring an argument, file playback at the file's own rate) goes to
     *  the {@link AudioDeviceManager} SPI: {@code manager(device.backend())}
     *  for capture, {@link #playbackManager} for playback. */
    public AudioCapture openCapture(DeviceRef device) {
        BackendPrefs bp = Preferences.instance().current();
        return manager(device.carrier())
                .openCapture(device, bp.getInputSampleRate(), bp.getInputBitDepth());
    }

    /** Playback twin of {@link #openCapture(DeviceRef)}: rate and depth from
     *  the preferences; only the dither depth - a generator setting, not a
     *  device format - is the caller's. */
    public AudioPlayback openPlayback(DeviceRef device, double ditherBits) {
        BackendPrefs bp = Preferences.instance().current();
        return playbackManager(device).openPlayback(device,
                bp.getOutputSampleRate(), bp.getOutputBitDepth(), ditherBits);
    }

    /**
     * The manager PLAYBACK on {@code device} must go through - the one place
     * the render-path policy lives.  Our direct WASAPI render loop produces
     * periodic 1-period gaps on some exclusive-mode drivers; the JavaSound
     * SourceDataLine path (the same code File-play uses) is gap-free.  WASAPI
     * playback is therefore routed through the JavaSound manager while still
     * honouring the user-selected WASAPI device name - the mixer is matched by
     * name inside the JavaSound manager's output-line opener.  That same path
     * serves the explicit JAVASOUND backend on Linux/macOS.
     *
     * <p>Public because the explicit-rate playback paths (the net server, file
     * playback) open through the SPI and must not lose this reroute by asking
     * {@code manager(device.backend())} themselves.
     */
    public AudioDeviceManager playbackManager(DeviceRef device) {
        // Route on the CARRIER: a remote WASAPI device renders on ITS bench -
        // the reroute below is local render-path policy only.
        AudioBackendType route = device.carrier();
        if (route == AudioBackendType.WASAPI) {
            route = AudioBackendType.JAVASOUND;
        }
        return manager(route);
    }

    /** The JavaSound mixer authority - exposed so file playback can open its
     *  output line on the SAME selected device the DDS tone uses (and reach
     *  high formats, e.g. 384&nbsp;kHz / 24-bit, the default mixer refuses).
     *  Returned as the SPI type: the concrete manager lives in a module core
     *  must not name, so the caller (which already depends on that module)
     *  narrows it. */
    public AudioDeviceManager javaSoundManager() {
        return manager(AudioBackendType.JAVASOUND);
    }

    /** The QA40x session manager - exposed so a Preferences-committed active-range
     *  change can be routed to the open device. */
    public AudioDeviceManager qa40xManager() {
        return manager(AudioBackendType.QA40X);
    }

    /**
     * The ACTIVE backend's remote-generator capability, or {@code null} when the
     * generator runs in this process - which is every local backend, so the null
     * is the ordinary answer and not a fault.
     *
     * <p>Asked here rather than injected once into whoever generates: the
     * operator switches backends while the application runs, and the capability
     * belongs to whichever backend is active NOW.  A manager that does not
     * implement it is a manager whose DAC is reached by rendering samples into
     * {@link #openPlayback}, which is the path that has always existed.
     */
    public RemoteGenerator remoteGenerator() {
        AudioBackendType carrier = activeKey.carrier();
        if (!isAvailable(carrier)) return null;
        return manager(carrier) instanceof RemoteGenerator remote ? remote : null;
    }

    /** App-exit teardown of the ACTIVE backend's manager - the only one that
     *  can still hold live device state, because {@link #setActive} already
     *  shuts a backend down when it is deactivated.  See
     *  {@link AudioDeviceManager#shutdown()} (a no-op for most backends; a
     *  backend whose device carries state across process death overrides it).
     *  Uses {@link #managerIfCreated}, never {@link #manager}: the exit path
     *  must not construct anything.  Called explicitly from the exit code -
     *  this app skips JVM shutdown hooks (see {@code GuiMain}). */
    public void shutdown() {
        AudioDeviceManager m = managerIfCreated(activeKey.carrier());
        if (m != null) {
            m.shutdown();
        }
    }

    /**
     * Brings every backend this host can open to a known, safe, idle state,
     * whether or not anything is about to measure with it - the application
     * taking the hardware up.  One of the two lifecycle calls a host makes: the
     * headless server from its start, the desktop from its start-up.
     *
     * <p><b>Every backend, asked the same question.</b>  Nothing here knows or
     * cares which backend is an analyzer, which is a sound card, or which
     * reaches its hardware over a wire - {@link AudioDeviceManager#setup()} is
     * the contract and each implementation answers it in its own terms.  A
     * backend takes itself out of the sweep by reporting itself unavailable,
     * which is its own answer, not a judgement made here.
     *
     * <p><b>Why "whether or not anything is about to measure".</b>  A device can
     * arrive already live and in an unknown state: an analyzer moved between two
     * hosts without losing its USB power keeps the input sensitivity the PREVIOUS
     * session left it at, because that session never released it - it simply
     * stopped being the host.  Nothing else in the application would correct
     * that until something happened to acquire and release the device, which on a
     * bench may be after the first measurement has already been taken at the
     * wrong range.
     *
     * <p>A backend that fails is logged and skipped: the host must still come up.
     */
    public void setup() {
        setupSweep = null;
        setupAll();
    }

    /**
     * The same sweep, on a thread of its own, for a host that must not wait for
     * it - the desktop, where {@link #setup()} in front of the first window
     * would be an unbounded delay before the application appears, paid on every
     * launch and made worst by exactly the hardware this exists for: a wedged
     * device is both the slowest to answer and the one most likely to be left
     * live.  The headless server has no window and calls {@link #setup()}
     * straight.
     *
     * <p>What it trades, honestly: the sweep is not finished when the window is
     * usable, so an operator quick enough can reach a device it has not got to.
     * That is self-correcting - acquiring and releasing a device is itself what
     * leaves it safe - and the sweep is for the device nobody touches.
     *
     * <p>{@link #teardown()} waits for it, so a park and an unpark cannot cross
     * on the same device as the application quits.
     */
    public void setupInBackground() {
        if (!setupStarted.compareAndSet(false, true)) {
            return;                       // one sweep per process is enough
        }
        Thread worker = new Thread(this::setupAll, SETUP_THREAD);
        worker.setDaemon(true);
        setupSweep = worker;
        worker.start();
    }

    private void setupAll() {
        for (AudioBackendType type : AudioBackendType.values()) {
            if (!isAvailable(type)) {
                continue;
            }
            try {
                if (manager(type).setup() && log.isInfoEnabled()) {
                    log.info("Audio backend {}: set up", type);
                }
            } catch (Throwable t) {
                // The host's start-up path: one backend that cannot be reached
                // must not stop the application coming up.
                log.warn("Audio backend {}: setup failed: {}", type, t.toString());
            }
        }
    }

    /**
     * The closing half of {@link #setup()}: every backend this process actually
     * built is left in a known, safe, idle state as the application lets go.
     * The server calls it from its stop path and the desktop from its exit code.
     *
     * <p><b>Every backend, again asked the same question.</b>  What "safe and
     * idle" costs differs enormously - an analyzer writes its attenuator to the
     * protected range, a bench on another machine gives back its generator lane
     * and its device locks over the wire, most backends do nothing at all - and
     * none of that is visible here.  The one backend that is ACTIVE is not a
     * special case either: it is simply one of the built ones.
     *
     * <p>{@link #managerIfCreated} rather than {@link #manager}: an exit path
     * must not construct a backend, and a backend that was never built has
     * nothing of ours to leave behind.  Every manager's own shutdown is
     * idempotent, so overlapping with the teardown {@link #setActive} already
     * ran when a backend was switched away from is harmless.
     */
    public void teardown() {
        awaitSetupSweep();
        for (AudioBackendType type : AudioBackendType.values()) {
            AudioDeviceManager m = managerIfCreated(type);
            if (m == null) {
                continue;
            }
            try {
                m.shutdown();
            } catch (Throwable t) {
                // The exit path: what is left to tear down still gets its turn.
                log.warn("Audio backend {}: teardown failed: {}", type, t.toString());
            }
        }
    }

    /** Lets a background start-up sweep finish before the teardown walks the
     *  same devices - bounded, because the application is already quitting and a
     *  backend that is not answering must not hold the process open. */
    private void awaitSetupSweep() {
        Thread worker = setupSweep;
        if (worker == null) {
            return;
        }
        try {
            worker.join(SETUP_JOIN_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
