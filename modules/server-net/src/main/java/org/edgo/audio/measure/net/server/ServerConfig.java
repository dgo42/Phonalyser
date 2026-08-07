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

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

import org.edgo.audio.measure.common.AppPaths;
import org.edgo.audio.measure.net.proto.NetProto;

import lombok.Getter;

/**
 * Everything the headless server was started with, plus the identity it
 * announces: the parsed command line
 * ({@code [--name <text>] [--port 8377] [--bind <addr>] [-d|--daemon]})
 * together with the {@code serverId}, the display {@code name} and the
 * application version that the {@code hello} response, the beacon and
 * {@code /info} all quote (spec 2.1, 3, 4.1).
 *
 * <p>ONE port: the same listener serves the HTTP endpoints of spec 3 and
 * upgrades the WebSocket of spec 4.
 *
 * <p>Parsing happens in the constructor through instance helpers, so the
 * command line is read exactly once, at start-up, and every consumer gets an
 * immutable value object instead of re-scanning {@code argv}.  Unknown
 * arguments are ignored on purpose: a launch script may carry switches of its
 * own, and whatever a future phase adds must not stop this one from starting.
 *
 * <p>A bad value is a start-up fault, not a runtime condition, so it throws:
 * a server that silently listens on the wrong port is worse than one that
 * refuses to start.
 */
@Getter
public final class ServerConfig {

    private static final String OPT_NAME = "--name";
    private static final String OPT_PORT = "--port";
    private static final String OPT_BIND = "--bind";
    private static final String OPT_DAEMON = "--daemon";
    private static final String OPT_DAEMON_SHORT = "-d";

    /** Filled with the pom version by resource filtering at build time; the
     *  same resource the desktop app reads for its splash and About box. */
    private static final String VERSION_RESOURCE = "/version.properties";
    private static final String VERSION_KEY = "app.version";
    /** An unfiltered (IDE) copy still holds the literal build token. */
    private static final String VERSION_UNFILTERED = "${";
    private static final String VERSION_FALLBACK = "dev";
    private static final String NAME_FALLBACK = "Phonalyser server";

    /** File name of the installation's {@code serverId}. */
    private static final String SERVER_ID_FILE = "server-id";
    /** MACHINE-scope directory name holding {@link #SERVER_ID_FILE} - Windows
     *  {@code %ProgramData%}, macOS {@code /Library/Application Support}. */
    private static final String MACHINE_DIR_NAME = "Phonalyser";
    /** The Linux machine-scope home ({@code /var/lib/<name>}), lower-case per
     *  that convention. */
    private static final String MACHINE_DIR_NAME_LINUX = "phonalyser";

    /** Operator-visible server name - {@code --name}, else the host name. */
    private final String name;
    /** Interface to bind to, or null for every interface ({@code --bind}). */
    private final String bind;
    /** The one port both planes are served on ({@code --port}). */
    private final int port;
    /** Per-INSTALLATION UUID clients key their remembered-server list on
     *  (spec 2.1) - see {@link #installationId(Path)}. */
    private final String serverId;
    /** Application version string announced to clients. */
    private final String app;
    /** Whether the operator asked for daemon mode ({@code -d}/{@code --daemon});
     *  every service definition passes it.  It no longer changes what is
     *  logged: the server writes NO console output in any mode - its logging
     *  config has no console appender at all - and the log file is written the
     *  same way whether the flag is given or not.  Parsed and reported all the
     *  same, because it is what the operator asked for. */
    private final boolean daemon;

    public ServerConfig(String[] args) {
        this(args, machineIdFile(), AppPaths.instance().file(SERVER_ID_FILE));
    }

    /** The same configuration with the identity file named by the caller - the
     *  one seam a test needs to keep the developer's own installation id out of
     *  its way. */
    ServerConfig(String[] args, Path idFile) {
        this(args, idFile, null);
    }

    private ServerConfig(String[] args, Path idFile, Path legacyIdFile) {
        String chosenName = value(args, OPT_NAME);
        this.name = chosenName == null ? hostName() : chosenName;
        this.bind = value(args, OPT_BIND);
        this.port = port(args, OPT_PORT, NetProto.DEFAULT_PORT);
        this.serverId = installationId(idFile, legacyIdFile);
        this.app = resolveApp();
        this.daemon = flag(args, OPT_DAEMON_SHORT) || flag(args, OPT_DAEMON);
    }

    /**
     * The MACHINE-scope home of the server.  The Windows service and the
     * standalone {@code java -jar} run as DIFFERENT accounts, so the per-user
     * data directory gave each form its own state - one host, two "servers" in
     * every client's list.  Spec 2.1 mints the UUID "once per server
     * INSTALLATION", and the installation is the machine: Windows
     * {@code %ProgramData%}, macOS {@code /Library/Application Support}, Linux
     * {@code /var/lib}.
     *
     * <p>That same scope holds the server's WHOLE data directory -
     * {@code devices.yaml} incl. - not just the id: the per-user
     * {@code %APPDATA%\Phonalyser} belongs to the GUI, and a console
     * server sharing it would edit the desktop's own card store.
     * {@link ServerMain#main} defaults {@code app.data.dir} here.
     */
    static Path machineDataDir() {   // static-ok: resolved before the instance exists (main / constructor delegation)
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String programData = System.getenv("ProgramData");
            return Paths.get(programData != null ? programData : "C:\\ProgramData")
                    .resolve(MACHINE_DIR_NAME);
        }
        if (os.contains("mac")) {
            return Paths.get("/Library/Application Support").resolve(MACHINE_DIR_NAME);
        }
        return Paths.get("/var/lib").resolve(MACHINE_DIR_NAME_LINUX);
    }

    /** The data directory a server DEFAULTS to when no {@code -Dapp.data.dir}
     *  says otherwise - {@link #machineDataDir()} on WINDOWS (every launch form
     *  can write {@code %ProgramData%}), {@code null} elsewhere: a root-less
     *  console run cannot create {@code /var/lib/phonalyser} or
     *  {@code /Library/Application Support/Phonalyser}, and the unix service
     *  scripts already pin the machine directories explicitly. */
    static Path defaultDataDir() {   // static-ok: resolved in main, before any instance exists
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win") ? machineDataDir() : null;
    }

    /** The id file inside {@link #machineDataDir()} - see that method's scope
     *  rationale. */
    private static Path machineIdFile() {   // static-ok: resolved before the instance exists (constructor delegation)
        return machineDataDir().resolve(SERVER_ID_FILE);
    }

    /**
     * The {@code serverId} of spec 2.1: "a UUID generated once per server
     * INSTALLATION - clients key their remembered-server list on it, never on
     * the IP".  So it is READ from {@code idFile} and minted only when that file
     * holds nothing yet.
     *
     * <p>A fresh id on every start would make each restart of the bench look
     * like a different server: every client's remembered entry would point at a
     * server that no longer exists, the same box would join the list again under
     * a new id, and the per-server settings keyed on the old one would be lost.
     *
     * <p>A data directory that cannot be read or written costs exactly that
     * persistence and nothing else - the server still runs, with an id that
     * lasts as long as the process, which is what the old behaviour was.
     *
     * <p>{@code legacyIdFile} (the per-user location every build before the
     * machine-scope move wrote) is consulted when {@code idFile} holds nothing:
     * an existing installation keeps the id its clients already remember, and a
     * best-effort copy promotes it to the machine scope so the OTHER form
     * (service vs standalone) converges on the same id at its next start.
     */
    private String installationId(Path idFile, Path legacyIdFile) {
        String stored = readId(idFile);
        if (stored != null) {
            return stored;
        }
        if (legacyIdFile != null) {
            String legacy = readId(legacyIdFile);
            if (legacy != null) {
                writeId(idFile, legacy);
                return legacy;
            }
        }
        String minted = UUID.randomUUID().toString();
        if (!writeId(idFile, minted) && legacyIdFile != null) {
            // Machine scope not writable (no rights): fall back to the per-user
            // home so at least THIS form keeps a stable id across restarts.
            writeId(legacyIdFile, minted);
        }
        return minted;
    }

    private String readId(Path idFile) {
        try {
            if (Files.isRegularFile(idFile)) {
                String stored = Files.readString(idFile, StandardCharsets.UTF_8).trim();
                if (!stored.isEmpty()) {
                    return stored;
                }
            }
        } catch (IOException | RuntimeException e) {
            // Unreadable counts as absent.
        }
        return null;
    }

    private boolean writeId(Path idFile, String id) {
        try {
            Path parent = idFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(idFile, id, StandardCharsets.UTF_8);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Where the front listens: every interface unless {@code --bind} named
     *  one. */
    public InetSocketAddress address() {
        return bind == null
                ? new InetSocketAddress(port) : new InetSocketAddress(bind, port);
    }

    /** The value following {@code flag}, or null when the flag is absent.
     *  Case-insensitive, like the CLI argument parser the app already uses.
     *
     *  @throws IllegalArgumentException when the flag is the LAST argument and
     *          its value is missing - silently falling back to the default
     *          would leave the operator's {@code --port} unheard, which is
     *          the start-up fault this class refuses to make. */
    private String value(String[] args, String flag) {
        for (int i = 0; i < args.length; i++) {
            if (args[i].equalsIgnoreCase(flag)) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(flag + " expects a value");
                }
                return args[i + 1];
            }
        }
        return null;
    }

    /** Whether {@code flag} appears at all - a value-less switch.  The CLI
     *  module's {@code ArgParser.hasArg} does the same scan, but this module
     *  may not depend on the CLI, so the three lines live here beside
     *  {@link #value(String[], String)} rather than pull a module edge in. */
    private boolean flag(String[] args, String flag) {
        for (String arg : args) {
            if (arg.equalsIgnoreCase(flag)) {
                return true;
            }
        }
        return false;
    }

    private int port(String[] args, String flag, int fallback) {
        String text = value(args, flag);
        if (text == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    flag + " expects a port number, got: " + text, e);
        }
    }

    private String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return NAME_FALLBACK;
        }
    }

    /** The running application's version: the filtered build resource first,
     *  then the jar manifest, then a marker for an unfiltered dev run.  The
     *  desktop app resolves it the same way from its own module - the two
     *  cannot share a helper today because the server may not depend on any
     *  GUI module. */
    private String resolveApp() {
        try (InputStream in = getClass().getResourceAsStream(VERSION_RESOURCE)) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String version = props.getProperty(VERSION_KEY, "").trim();
                if (!version.isEmpty() && !version.startsWith(VERSION_UNFILTERED)) {
                    return version;
                }
            }
        } catch (IOException e) {
            // Fall through to the manifest, then to the dev marker.
        }
        String version = getClass().getPackage().getImplementationVersion();
        return version == null || version.isEmpty() ? VERSION_FALLBACK : version;
    }
}
