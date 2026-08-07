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

package org.edgo.audio.measure.it;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.stream.Stream;

import org.edgo.audio.measure.it.engine.GuiRun;
import org.edgo.audio.measure.it.engine.ScenarioWorkdir;
import org.edgo.audio.measure.it.engine.ServerProcess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.Timeout;

/**
 * A real desktop client opens a session to a real headless server - two JVMs,
 * one socket.
 *
 * <p><b>This closes a gap nothing else in the repository reaches.</b>
 * {@code server-net} is deliberately absent from every client POM, so no unit
 * test anywhere can wire a live {@code ServerMain} to a live {@code NetBenchUi}
 * - each side can only be tested against a stub of the other. Here neither side
 * is stubbed: the client dials an ephemeral port the OS chose, completes the
 * real handshake, and hangs up.
 *
 * <p>Asserted from BOTH ends, which is the point of doing it cross-process: the
 * client's own checks say what it believed happened, and the server's log says
 * what it saw. A client that reported success while the server never accepted a
 * session would pass the first and fail the second.
 *
 * <p>No {@code -Dlibusb.path} for the server here. This scenario is about the
 * SESSION, not about audio, and pointing the server at the currently broken
 * QA40x mock would only risk noise during its backend probe.
 */
@Tag("user-mode")
@TestInstance(Lifecycle.PER_CLASS)
class NetSessionIT extends ScenarioIT {

    private static final String SCENARIO = "net-session";
    private static final int RUN_TIMEOUT_SECONDS    = 120;
    /** The server has real work to do before it answers - device enumeration
     *  and a backend setup sweep - so it gets its own generous window. */
    private static final int SERVER_READY_SECONDS   = 90;

    /** What ClientSession logs when a session is accepted and when it ends
     *  (ClientSession: "net session: {} ({}) accepted at proto ..." and
     *  "net session {} closed ({}), {} lock(s) freed").  Matched loosely - on
     *  the fragment that carries the meaning, not on the whole sentence, so
     *  rewording the message does not fail an unrelated test. */
    private static final String SERVER_ACCEPTED = "accepted at proto";
    private static final String SERVER_CLOSED   = "lock(s) freed";

    private ScenarioWorkdir workdir;
    private ServerProcess   server;
    private GuiRun          run;

    @BeforeAll
    @Timeout(RUN_TIMEOUT_SECONDS + SERVER_READY_SECONDS + 60)
    void runScenario() throws Exception {
        workdir = stage(SCENARIO);

        // Tracked the moment it is created, so a failure in awaitReady below
        // still takes the server down - the base stops children in reverse
        // order, closing the client before the server it was talking to.
        server = track(new ServerProcess(paths(), workdir, null));
        server.start();
        server.awaitReady(SERVER_READY_SECONDS);

        // The port is only known now, so it reaches the script as a parameter.
        run = runGui(workdir, true, null, Map.of("server", server.address()),
                RUN_TIMEOUT_SECONDS);
    }

    @Test
    void theServerBoundAnEphemeralPort() {
        assertTrue(server.getPort() > 0,
                "the server never reported a bound port; see " + server.getStdoutFile());
    }

    @TestFactory
    Stream<DynamicTest> everyCheckIsGreen() {
        return run.assertAllChecks();
    }

    @Test
    void theServerSawTheSessionOpenAndClose() throws Exception {
        // The other end of the story.  The client's checks above are its own
        // account of the session; this is the server's, and only the two
        // together rule out a client that convinced itself.
        String log = server.readStdout();
        assertTrue(log.contains(SERVER_ACCEPTED),
                "the server never accepted a session - its log has no '"
                        + SERVER_ACCEPTED + "': see " + server.getStdoutFile());
        assertTrue(log.contains(SERVER_CLOSED),
                "the server never saw the session end - its log has no '"
                        + SERVER_CLOSED + "': see " + server.getStdoutFile());
    }
}
