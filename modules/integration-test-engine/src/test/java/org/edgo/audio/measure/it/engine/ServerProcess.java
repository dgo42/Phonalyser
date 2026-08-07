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

package org.edgo.audio.measure.it.engine;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * The headless Phonalyser server as a child JVM - a REAL server, on a real
 * socket, which is the coverage nothing else in this repository has: no test
 * anywhere else wires a live server to a live client, because server-net is
 * deliberately absent from every client POM.
 *
 * <p>Bound to {@code 127.0.0.1} and to port 0, so the OS picks a free port and
 * two scenarios can never collide over a fixed one.  The port is then read back
 * from the server's own startup banner.
 *
 * <p><b>Foreground, never {@code -d}.</b>  The daemon switch swaps in a
 * console-less logging configuration, and the banner this class parses would
 * never reach stdout.
 */
@Log4j2
public final class ServerProcess extends JvmProcess {

    private static final String MAIN_CLASS = "org.edgo.audio.measure.net.server.ServerMain";
    private static final String LOG_BASE   = "server";
    private static final String SERVER_NAME = "it-server";
    private static final String BIND_ADDRESS = "127.0.0.1";
    /** This module's own log4j2 configuration for the child (see the file). */
    private static final String LOGGING_CONFIG_RESOURCE = "/it-server-log4j2.xml";

    /** The banner line ServerMain emits once the front is up, per bound
     *  address: {@code net server: listening on ws://HOST:PORT  http://...}.
     *  The port is read from it rather than guessed, and it is emitted AFTER
     *  the bind, so seeing it means the socket is real. */
    private static final Pattern LISTENING = Pattern.compile("listening on ws://[^:]+:(\\d+)");

    private static final long POLL_MS = 200;
    /** How long the readiness probe gives one HTTP attempt. */
    private static final int PROBE_TIMEOUT_SECONDS = 2;

    private final ScenarioWorkdir workdir;
    /** The QA40x mock directory, or null to serve only the host's own
     *  backends. */
    private final Path libusbDir;

    /** The port the server actually bound, once {@link #awaitReady} has read it
     *  from the banner; 0 before that. */
    @Getter
    private int port;

    public ServerProcess(EnginePaths paths, ScenarioWorkdir workdir, Path libusbDir) {
        super(paths.getClasspathFile(), workdir.getDir(), LOG_BASE);
        this.workdir   = workdir;
        this.libusbDir = libusbDir;
    }

    @Override
    protected String mainClass() {
        return MAIN_CLASS;
    }

    @Override
    protected List<String> jvmArgs() {
        List<String> args = new ArrayList<>();
        args.add("-Dapp.data.dir=" + workdir.getServerDataDir());
        // Pin the logging configuration - see it-server-log4j2.xml.  Both
        // children share one class path, so which of the two bundled log4j2.xml
        // resources wins is an accident; without this the server's banner never
        // reaches stdout and the port below cannot be read.
        args.add("-Dlog4j2.configurationFile=" + loggingConfig());
        if (libusbDir != null) {
            args.add("-Dlibusb.path=" + libusbDir);
        }
        return args;
    }

    /** The engine's own logging configuration for the child, as an absolute
     *  path - the child's class path holds this module's dependencies, not its
     *  test resources, so it has to be named by location. */
    private Path loggingConfig() {
        URL url = getClass().getResource(LOGGING_CONFIG_RESOURCE);
        if (url == null) {
            throw new IllegalStateException("Missing engine resource " + LOGGING_CONFIG_RESOURCE);
        }
        try {
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Logging config is not a file: " + url, e);
        }
    }

    @Override
    protected List<String> programArgs() {
        // Port 0: the OS picks a free one.  No -d / --daemon, ever - see the
        // class comment.
        return List.of("--name", SERVER_NAME, "--port", "0", "--bind", BIND_ADDRESS);
    }

    /** {@code host:port} for a client to connect to - valid only after
     *  {@link #awaitReady}. */
    public String address() {
        return BIND_ADDRESS + ":" + port;
    }

    /**
     * Waits until the server is genuinely usable: its banner has named the
     * bound port AND an HTTP request to it has been answered.
     *
     * <p>Both halves are needed.  The banner alone says the front started; only
     * a completed request proves the server is answering, and a client that
     * connected in between would be racing the server's own startup.
     */
    public void awaitReady(int timeoutSeconds) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        port = 0;
        while (System.currentTimeMillis() < deadline) {
            if (port == 0) {
                Matcher matcher = LISTENING.matcher(readStdout());
                if (matcher.find()) {
                    port = Integer.parseInt(matcher.group(1));
                    if (log.isInfoEnabled()) {
                        log.info("IT: net server bound to port {}", port);
                    }
                }
            }
            if (port != 0 && answersInfo()) {
                return;
            }
            if (!isAlive()) {
                throw new IllegalStateException("the net server exited during startup - see "
                        + getStdoutFile() + " and " + getStderrFile());
            }
            Thread.sleep(POLL_MS);
        }
        throw new IllegalStateException("net server was not ready within " + timeoutSeconds
                + " s (port so far: " + port + ") - see " + getStdoutFile()
                + " and " + getStderrFile());
    }

    /** One readiness probe.  Any answer at all means the front is serving; the
     *  STATUS is not judged here, only that a real HTTP exchange completed. */
    private boolean answersInfo() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(PROBE_TIMEOUT_SECONDS))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + address() + "/info"))
                .timeout(Duration.ofSeconds(PROBE_TIMEOUT_SECONDS))
                .GET()
                .build();
        try {
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() > 0;
        } catch (IOException | InterruptedException notYet) {
            if (notYet instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
}
