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

package org.edgo.audio.measure.net.server;

import java.net.InetAddress;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;
import org.edgo.audio.measure.common.AppPaths;
import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.NetInterfaces;
import org.edgo.audio.measure.net.proto.NetProto;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.javasound.CsjsoundNativePath;

/**
 * The headless server's entry point and composition root: it reads the command
 * line into a {@link ServerConfig}, builds the single {@link LockRegistry} and
 * the one front both planes are served by, starts the hot-plug rescan, and
 * arranges the teardown.
 *
 * <p>No display is ever created here, and nothing in this module compiles
 * against the GUI - the server is a different application in a jar of its own,
 * not a mode of the workbench.
 *
 * <p>Shutdown runs from a JVM hook, which is what a headless process wants:
 * {@code SIGTERM} from a service manager or {@code Ctrl-C} at a console must
 * still close the connections, and each of them frees its locks and parks what
 * it held on the way out.  The desktop app deliberately avoids shutdown hooks
 * because it has an orderly exit path through its window; a server has no
 * window.
 */
public final class ServerMain {

    private static final String KEEPALIVE_THREAD = "net-keepalive";
    private static final String SHUTDOWN_THREAD = "net-shutdown";
    private static final String RESCAN_THREAD = "net-rescan";
    /** One thread carries every session's keepalive: a ping is a short JSON
     *  write, the sends never block, and a session's own requests run on the
     *  session's thread, never on this one. */
    private static final int KEEPALIVE_THREADS = 1;
    /** How long a session's teardown may take before the shutdown gives up on
     *  it.  Generous on purpose: the teardown joins a render thread (two
     *  seconds) and closes a device line after it, and cutting it short is what
     *  leaves a QA40x unparked. */
    private static final int TEARDOWN_TIMEOUT_MS = 3_000;
    /** What the clients are told the connection ended for. */
    private static final String SHUTDOWN_REASON = "server shutdown";
    /** How often the bench is re-enumerated to notice a device that was plugged
     *  in or pulled out (spec 4.3: {@code ev.devices.changed} is "sent on
     *  hot-plug and on any lock change").  Slow on purpose - enumeration talks
     *  to drivers and USB, and nothing on a bench appears so urgently that two
     *  seconds of delay costs a measurement. */
    private static final int HOTPLUG_INTERVAL_MS = 2_000;
    /** Directory name of the web bundle, when a build ships one (spec 3). */
    private static final String STATIC_DIR = "web";
    /** System property both logging configs derive the log directory from;
     *  {@link #main} fills it so the log lands in the server's data directory
     *  rather than in a {@code logs/} beside a jar nobody may write to. */
    private static final String LOG_DIR_PROPERTY = "app.log.dir";
    /** The data-directory override {@code AppPaths} honours.  {@link #main}
     *  defaults it to {@link ServerConfig#machineDataDir()} - the per-user
     *  {@code %APPDATA%\Phonalyser} belongs to the GUI, and a server, console
     *  or service, is a MACHINE installation; an explicit
     *  {@code -Dapp.data.dir} (tests, launchers) still wins. */
    private static final String DATA_DIR_PROPERTY = "app.data.dir";
    /** The DAEMON logging config - this module's {@code log4j2.xml} without the
     *  console appender.  Loaded only under {@code -d}; a foreground start uses
     *  the auto-loaded {@code log4j2.xml} as it is. */
    private static final String LOG_CONFIG_RESOURCE = "/log4j2-server.xml";

    /**
     * This server's logger - an INSTANCE field, and deliberately NOT the
     * house's {@code @Log4j2} static one.
     *
     * <p>A static logger is created at CLASS LOAD, and for the class that holds
     * {@code main} that is before {@code main} can set {@code app.log.dir}.
     * log4j would then start this module's auto-loaded {@code log4j2.xml}
     * against the fallback directory and leave a stray empty
     * {@code logs/phonalyser-server.log} beside the jar - which is exactly the
     * file that appeared before this was understood.  Every instance of this
     * class is built after {@code main} has set the property, and {@code main}
     * itself uses a local logger created at that same safe moment.  Same
     * reasoning as {@code AppPaths}' lazy {@code log()}.
     */
    private final Logger log = LogManager.getLogger(ServerMain.class);

    private final ServerConfig config;
    /** What this server offers its clients - decided once in the constructor
     *  (see the comment there); kept because the start-up banner names it. */
    private final List<AudioBackendType> serving;
    private final LockRegistry locks = new LockRegistry();
    private final ScheduledExecutorService scheduler;
    /** The hot-plug rescan's own lane: an enumeration runs at the hardware's
     *  speed and may not sit on the thread that pings the sessions - see
     *  {@link #rescanDevices()}.  The request handlers have Jetty's pool and no
     *  longer need one from here. */
    private final ExecutorService rescanExecutor;
    private final DeviceCatalog catalog;
    /** The backend registry, kept because the server's OWN lifecycle brackets
     *  every LOCAL backend - {@link AudioBackend#setup()} on the way up and
     *  {@link AudioBackend#teardown()} on the way down. */
    private final AudioBackend audio;
    /** The sessions' owner, kept because the shutdown closes them and the
     *  hot-plug rescan broadcasts through them. */
    private final WsFront wsFront;
    /** The ONE listener: HTTP and the WebSocket upgrade on the same port. */
    private final CombinedFront front;
    private final BeaconSender beacon;
    /** The hot-plug clock.  A {@link Ticker} like every other periodic task in
     *  this module, on the same shared scheduler. */
    private final Ticker hotplug;
    /** The server stops once.  {@link #stop()} is both the shutdown hook and a
     *  public call, so an orderly stop followed by JVM exit would otherwise
     *  close the front and park the hardware twice. */
    private final AtomicBoolean stopped = new AtomicBoolean();

    public ServerMain(ServerConfig config) {
        this(config, ScheduledTicker::new, null, Preferences.instance());
    }

    /**
     * The same server serving exactly {@code served}, whatever this host could
     * open by itself - the seam a loopback test needs to serve a backend it
     * STUBBED, since a stub is not a driver the host policy below can see (no
     * {@code libusb} loads for a QA40x that is a test double).
     *
     * <p>Production goes through {@link #ServerMain(ServerConfig)}, which decides
     * the list from this host and this build.
     */
    public ServerMain(ServerConfig config, List<AudioBackendType> served) {
        this(config, ScheduledTicker::new, served, Preferences.instance());
    }

    /**
     * The same server calibrating into {@code cards} instead of into this
     * installation's own device store - the seam an end-to-end test needs.
     *
     * <p>Spec 4.3 lets a client WRITE the calibration of a device the server owns
     * ({@code device.setCalibration}), and that write persists: a test driving it
     * against the singleton would rewrite the developer's {@code devices.yaml}.
     * Production goes through the constructors above, where the store is this
     * installation's.
     */
    public ServerMain(ServerConfig config, List<AudioBackendType> served,
            Preferences cards) {
        this(config, ScheduledTicker::new, served, cards);
    }

    /**
     * The same server with its connections' clock chosen by the caller - the one
     * seam that lets a loopback test drive the keepalive of spec 4.1 (four
     * unanswered pings, 2 s) in no time at all instead of waiting for it.
     *
     * <p>Only the SESSION tickers come from here.  The beacon and the hot-plug
     * rescan keep the real scheduler: they are the server's own heartbeat, they
     * touch the network and the hardware, and a test that silently stopped them
     * would be testing a server nobody runs.  A factory of a scheduler rather
     * than a plain supplier because the scheduler is this constructor's own -
     * there is nothing to hand a supplier before it exists.
     */
    ServerMain(ServerConfig config,
            Function<ScheduledExecutorService, Ticker> sessionTickers) {
        this(config, sessionTickers, null, Preferences.instance());
    }

    /** The three seams at once - the connections' clock, the backends served
     *  (null: decide them from this host, as production does) and the device
     *  store this bench's calibration lives in. */
    ServerMain(ServerConfig config,
            Function<ScheduledExecutorService, Ticker> sessionTickers,
            List<AudioBackendType> served, Preferences cards) {
        this.config = config;
        this.scheduler = Executors.newScheduledThreadPool(KEEPALIVE_THREADS,
                this::keepaliveThread);
        this.rescanExecutor = Executors.newSingleThreadExecutor(this::rescanThread);
        AudioBackend audio = AudioBackend.instance();
        this.audio = audio;
        JsonCodec codec = new JsonCodec();
        // ONE list, read by everything that answers "does this server have that
        // backend?": the catalog's enumeration and its available/operational
        // flags, and the QA40x extension's capability token.  Two predicates for
        // the same question is how a hello came to promise "qa40x" on a host
        // where backend.select QA40X answers BAD_REQUEST.
        this.serving = served == null
                ? servedBackends(audio) : List.copyOf(served);
        // This machine's device cards: the calibration of everything plugged into
        // the bench is the SERVER's to keep (spec 4.3), so the catalog publishes
        // it with every device and a client's device.setCalibration writes it
        // here.  Named once, in the composition root.
        this.catalog = new DeviceCatalog(audio, locks, codec, serving, cards);
        // Read through this server rather than captured: a bound port does not
        // exist until the front below is listening.
        BoundPorts ports = new BoundPorts(this::getPort);
        PeerTable peers = new PeerTable(config, ports, System::currentTimeMillis);
        // One store for the whole server: the HTTP front puts uploads in, and a
        // connection's generator takes them out and owns them from then on
        // (spec 3).
        FileStore files = new FileStore();
        this.wsFront = new WsFront(config, locks, new Qa40xGuard(audio, locks),
                new Qa40xSession(audio, codec, serving), catalog, audio, files, codec,
                () -> sessionTickers.apply(scheduler));
        // ONE listener for both planes: the upgrade endpoint is the ws front's,
        // everything else falls through to the http one (spec 3, 4).
        this.front = new CombinedFront(config,
                new HttpFront(config, ports, catalog, peers, files, codec, staticDir()),
                wsFront);
        this.beacon = new BeaconSender(config, ports, codec, peers,
                new ScheduledTicker(scheduler));
        this.hotplug = new ScheduledTicker(scheduler);
    }

    /**
     * Where the web bundle lives when this build ships one: a {@code web}
     * directory beside the running jar, else the same name in the working
     * directory.  Resolved here, in the composition root, because it is a
     * deployment fact - {@link HttpFront} is handed the answer and serves it only
     * while it exists.
     */
    private Path staticDir() {
        try {
            CodeSource source = getClass().getProtectionDomain().getCodeSource();
            if (source != null) {
                Path code = Path.of(source.getLocation().toURI());
                Path beside = (Files.isDirectory(code) ? code : code.getParent())
                        .resolve(STATIC_DIR);
                if (Files.isDirectory(beside)) {
                    return beside.toAbsolutePath().normalize();
                }
            }
        } catch (URISyntaxException | RuntimeException e) {
            if (log.isDebugEnabled()) {
                log.debug("net server: cannot locate the jar, looking for {} in the "
                        + "working directory: {}", STATIC_DIR, e.toString());
            }
        }
        return Path.of(STATIC_DIR).toAbsolutePath().normalize();
    }

    /**
     * Which backends this server offers its clients: the module is on the class
     * path AND the backend can be opened on this host.  Both halves are needed -
     * a build ships every backend module regardless of platform, so the class
     * path alone would offer a macOS client the Windows kernel-streaming path
     * and fail at the first enumeration.
     *
     * <p>Deciding it once, here in the composition root, is what keeps the
     * catalog free of host policy - and what lets a test serve exactly the
     * backends it stubs.
     */
    private List<AudioBackendType> servedBackends(AudioBackend audio) {
        List<AudioBackendType> served = new ArrayList<>();
        for (AudioBackendType type : AudioBackendType.values()) {
            if (type.isAvailable() && audio.isAvailable(type)) {
                served.add(type);
            }
        }
        if (log.isInfoEnabled()) {
            log.info("net server: serving backends {}", served);
        }
        return served;
    }

    public static void main(String[] args) {
        // The data directory FIRST OF ALL: AppPaths locks the choice in at its
        // first touch, and both the config below (the legacy id path) and the
        // log-dir default reach it.  A server is a machine installation - the
        // per-user %APPDATA%\Phonalyser is the GUI's store, and a console
        // server sharing it would edit the desktop's own devices.yaml.
        // Windows-only by design - see ServerConfig.defaultDataDir; an
        // explicit -Dapp.data.dir wins.
        Path defaultData = ServerConfig.defaultDataDir();
        if (defaultData != null && System.getProperty(DATA_DIR_PROPERTY) == null) {
            System.setProperty(DATA_DIR_PROPERTY, defaultData.toString());
        }
        ServerConfig config = new ServerConfig(args);
        // The log directory next, and before anything in this process has
        // logged a single line - see the lazy-logger note on this class.  Both
        // server configs resolve ${sys:app.log.dir} when their file appender
        // starts, and a logger touched earlier would resolve it to the
        // fallback and leave a stray empty logs/ directory beside the jar.
        if (System.getProperty(LOG_DIR_PROPERTY) == null) {
            System.setProperty(LOG_DIR_PROPERTY,
                    AppPaths.instance().getLogsDir().toString());
        }
        // NOW the first logger of this process may exist: local, because a
        // static field would have been initialised at class load, above.
        Logger log = LogManager.getLogger(ServerMain.class);
        // Foreground needs no reconfigure at all: this module's own log4j2.xml
        // carries the default name and log4j has just auto-loaded it, console
        // and all.  A daemon has no console to print to, so -d - which every
        // service definition passes - swaps in the console-less twin.
        if (config.isDaemon()) {
            // The failure branches WARN rather than stay silent: a service that
            // quietly kept the console config would write to a handle nobody
            // reads and look like a server that logs nothing.  A warn still
            // reaches the file appender of the config already in force.
            URL daemonLogging = ServerMain.class.getResource(LOG_CONFIG_RESOURCE);
            if (daemonLogging == null) {
                if (log.isWarnEnabled()) {
                    log.warn("net server: {} is missing from the class path - the "
                            + "foreground logging config stays in force, so this "
                            + "service also writes to a console nobody reads",
                            LOG_CONFIG_RESOURCE);
                }
            } else {
                try {
                    Configurator.reconfigure(daemonLogging.toURI());
                } catch (URISyntaxException e) {
                    if (log.isWarnEnabled()) {
                        log.warn("net server: cannot re-point logging to {}: {} - the "
                                + "foreground config stays in force", daemonLogging,
                                e.toString());
                    }
                }
            }
        }
        // Route any worker-thread death through log4j.  Set AFTER the
        // reconfigure so the stack lands in the server's own log file, and set
        // at all because every thread that matters here is a background one:
        // the session workers, the capture streamer, the hot-plug rescan.
        // Without this an uncaught exception on one of them only prints to
        // stderr - which a daemon run has silenced - so the session freezes
        // with no trace anywhere.
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            if (log.isErrorEnabled()) {
                log.error("Uncaught exception on thread '{}': {}",
                        thread.getName(), error.toString(), error);
            }
        });
        // Make the bundled csjsound WASAPI-exclusive JavaSound provider loadable
        // BEFORE anything triggers the JVM's first System.loadLibrary, and before
        // the server below enumerates the bench.  The provider resolves its
        // native through System.loadLibrary, which reads java.library.path and
        // nothing else - and that path is snapshotted at VM init, so the only
        // stageable entry is the CURRENT DIRECTORY (see CsjsoundNativePath's
        // class doc).  Without it a Windows server offers shared-mode JavaSound
        // only: the silent-48 kHz shared-mixer fallback, where the same
        // interface driven locally runs EXCL: at its real rate.  A no-op on
        // non-Windows, in the dev tree, and on any build without the resource.
        CsjsoundNativePath.installForFatJar();
        new ServerMain(config).start();
    }

    /** Brings the server up.  Returns as soon as it is listening - the
     *  transport's own threads keep the process alive.
     *
     *  @throws IllegalStateException when the port cannot be bound: an
     *          operator who named a port and silently got none would have a
     *          server no client can find */
    public void start() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, SHUTDOWN_THREAD));
        // The bench is enumerated ONCE here, before a client can connect.
        // ev.devices.changed carries the full devices.list payload (spec 4.3)
        // but is built from the last enumeration, because it fires on threads
        // that must not touch hardware - so without a first one, the very first
        // lock change would broadcast an empty backends array and every client
        // would reset its device combos to nothing.
        catalog.scan();
        // And with the bench known, bring every backend up to its known idle
        // state BEFORE a client can ask for anything on it.  What that means is
        // each backend's own business (see AudioDeviceManager#setup) - the server
        // does not ask which of them is an analyzer.
        audio.setup();
        front.start();
        beacon.start();
        hotplug.start(HOTPLUG_INTERVAL_MS, () -> rescanExecutor.execute(this::rescanDevices));
        // The banner: emitted AFTER the front is up so the port is the BOUND
        // one, through the logger so the same lines reach the log file in every
        // mode and the console unless -d silenced it.
        if (log.isInfoEnabled()) {
            log.info("net server '{}' (id {}, app {}, proto {})", config.getName(),
                    config.getServerId(), config.getApp(), NetProto.PROTO_VERSION);
            log.info("net server: mode {}, backends {}, web bundle {}",
                    config.isDaemon() ? "daemon" : "foreground",
                    serving, webBundle());
            for (String line : listeningOn()) {
                log.info("net server: listening on {}", line);
            }
        }
    }

    /** The banner's web-bundle fact, resolved the same way the HTTP front was
     *  handed it at construction. */
    private String webBundle() {
        Path dir = staticDir();
        return Files.isDirectory(dir) ? "serving " + dir : "none";
    }

    /** One banner line per reachable address, ws and http side by side - the
     *  SAME port in both, which is the whole point of the merge, and the
     *  operator can paste either.  {@code --bind} named the one interface;
     *  otherwise every IPv4 this host answers on - and when even enumeration
     *  fails, just the bound port. */
    private List<String> listeningOn() {
        int port = front.getPort();
        if (config.getBind() != null) {
            return List.of(urls(config.getBind(), port));
        }
        List<String> lines = new ArrayList<>();
        for (InetAddress address : NetInterfaces.listenAddresses()) {
            lines.add(urls(address.getHostAddress(), port));
        }
        if (lines.isEmpty()) {
            lines.add("port " + port + " (all interfaces)");
        }
        return lines;
    }

    private String urls(String host, int port) {
        return "ws://" + host + ":" + port + "  http://" + host + ":" + port;
    }

    /** The port actually bound - what {@link BoundPorts} reads through, and
     *  what a caller that started this server on an ephemeral one has to ask
     *  for to reach it. */
    public int getPort() {
        return front.getPort();
    }

    /**
     * Closes every connection, WAITS for the teardowns, and stops the timers.
     * Each session frees its locks and parks what nobody else holds on its way
     * out, which is the whole of the hardware teardown: there is no server-wide
     * backend left to shut down here, and calling the global one would park a
     * device another process may legitimately still be using.
     *
     * <p>Idempotent: the shutdown hook runs it too, after an explicit stop.
     */
    public void stop() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        // Stop being findable and reachable first, then close the connections:
        // a client that connects between the two would get a session the very
        // next line tears down.
        beacon.stop();
        hotplug.stop();
        front.stopAccepting();
        // The sessions come down before the transport does, and each teardown is
        // WAITED for.  It is the last thing the hardware gets: the DAC lines are
        // closed and the analyzer is parked (spec 4.1 - "park hardware, QA40x
        // attenuator safe") on the sessions' own threads, which are daemons.
        // Returning while those tasks were still queued would let this hook
        // finish, the JVM exit, and a SIGTERM leave the input at full
        // sensitivity with the output line still open.
        wsFront.closeSessions(SHUTDOWN_REASON, TEARDOWN_TIMEOUT_MS);
        // Each teardown above hands back what THAT session held, which covers
        // nothing at all when no client ever connected - and an orderly service
        // stop has to leave the bench safe either way.  The locks are back by
        // now, so this is the last thing that can touch the hardware and every
        // backend's own closing state is reachable.  It is also what makes a
        // graceful stop worth having: a hard kill skips this entirely, which is
        // why the service wrapper asks for one.
        audio.teardown();
        // And the listener last: the sessions above said their goodbyes and
        // freed their locks ON those sockets, which only exist until this line.
        front.stop();
        rescanExecutor.shutdownNow();
        scheduler.shutdownNow();
        if (log.isInfoEnabled()) {
            log.info("net server '{}' stopped", config.getName());
        }
    }

    /**
     * One hot-plug look at the bench: re-enumerate, and tell every connected
     * client only when the device set actually moved (spec 4.3 -
     * {@code ev.devices.changed} "sent on hot-plug and on any lock change").
     *
     * <p>It runs on a lane of its own, never on the ticker's thread.  That
     * thread carries every session's keepalive, and an enumeration talks to
     * drivers and USB: a bench with a wedged device would otherwise stall the
     * pings and have the server declare all of its healthy clients dead.
     */
    // The backend lifecycle itself lives on AudioBackend - one fan-out over the
    // LOCAL backends, shared with the desktop, so neither host decides which of
    // them is an analyzer.  The server just brackets its own life with the two
    // calls: audio.setup() once the bench is enumerated and before a client can
    // ask for anything on it, audio.teardown() once the sessions are down and
    // their locks are back.  Those two moments are also why no "is anything
    // holding this device" test is needed here: at start no lock can exist, and
    // at stop they have all been returned.

    private void rescanDevices() {
        try {
            if (catalog.rescan()) {
                if (log.isInfoEnabled()) {
                    log.info("net server: the device list changed - telling every client");
                }
                wsFront.broadcastDevicesChanged();
            }
        } catch (Throwable t) {
            // A backend that fails to enumerate must not end the rescan for
            // good: the next tick tries again, and the clients keep the list
            // they have.  Throwable, because enumerating is a native call on
            // every backend that has one - an analyzer pulled out mid-scan
            // faults the invocation itself, and letting that out would kill the
            // rescan thread and take the hot-plug detection down with it.
            if (log.isWarnEnabled()) {
                log.warn("net server: hot-plug rescan failed: {}", t.toString());
            }
        }
    }

    private Thread keepaliveThread(Runnable task) {
        Thread thread = new Thread(task, KEEPALIVE_THREAD);
        thread.setDaemon(true);
        return thread;
    }

    private Thread rescanThread(Runnable task) {
        Thread thread = new Thread(task, RESCAN_THREAD);
        thread.setDaemon(true);
        return thread;
    }
}
