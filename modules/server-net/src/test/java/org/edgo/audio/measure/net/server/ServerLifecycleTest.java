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

import java.util.List;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.sound.AudioBackend;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The server brackets every backend it serves: each one is brought to its known
 * idle state before a client can ask for anything on it, and left in one when
 * the server stops.
 *
 * <p><b>Why the stop half needs a test of its own.</b>  Each session's teardown
 * hands back what THAT session held, and until now that was the only thing on
 * the server that ever left the hardware safe - so a server nobody ever
 * connected to, or one whose clients never took the device, went down without
 * touching it at all.  On an analyzer whose input sensitivity survives the host
 * that set it, that is a bench left at an unknown range by an orderly service
 * stop.  These two tests are what say the calls happen with NO client in the
 * picture at all.
 *
 * <p>What a backend DOES in those two calls is its own business and is pinned in
 * its own module; the stub here only records that it was asked.  Counts are read
 * as deltas because the stub bench is the process-wide manager every test class
 * in this module shares.
 */
class ServerLifecycleTest {

    private static final String SERVER_NAME = "Bench lifecycle";
    /** The front is not what this drives, and a fixed port would fail the
     *  moment the developer has a server of their own running. */
    private static final String EPHEMERAL = "0";

    private final StubCardStore cards = new StubCardStore();

    @Test
    void everyServedBackendIsBroughtUpAtStartAndLeftSafeAtStop() {
        StubDeviceManager bench = bench();
        int setupsBefore = bench.getSetupCount();
        int shutdownsBefore = bench.getShutdownCount();
        ServerMain server = serverServing(AudioBackendType.QA40X);

        server.start();

        assertEquals(setupsBefore + 1, bench.getSetupCount(),
                "before a client can connect: what the server enumerated may have "
                        + "been left live by whatever host had it last");

        server.stop();

        assertEquals(shutdownsBefore + 1, bench.getShutdownCount(),
                "and an orderly stop leaves it safe even though no session ever "
                        + "existed to hand anything back - which is the whole reason "
                        + "the service wrapper asks for a graceful stop");
    }

    /**
     * The lifecycle is the HOST's, not the served list's: every local backend on
     * this machine is brought up and left safe, including one this server does
     * not offer to clients.
     *
     * <p>That is deliberate: stopping the server shuts every backend down.
     * Serving a backend is about what a CLIENT may ask for; leaving hardware in
     * a known state is about what this process did to the machine it runs on.
     * A server that opened nothing on a device still shares the host with it,
     * and the device that most needs the safe state is exactly the one nobody
     * was measuring with.
     */
    @Test
    void aBackendThisServerDoesNotServeIsStillBroughtUpAndLeftSafe() {
        StubDeviceManager bench = bench();
        int setupsBefore = bench.getSetupCount();
        int shutdownsBefore = bench.getShutdownCount();
        ServerMain server = serverServing(AudioBackendType.JAVASOUND);

        try {
            server.start();
        } finally {
            server.stop();
        }

        assertEquals(setupsBefore + 1, bench.getSetupCount(),
                "the host brackets every LOCAL backend, not just the served ones");
        assertEquals(shutdownsBefore + 1, bench.getShutdownCount());
    }

    /** A server on a free port, serving exactly {@code served}, calibrating into
     *  this test's own store rather than the developer's. */
    private ServerMain serverServing(AudioBackendType served) {
        ServerConfig config = new ServerConfig(new String[] {"--name", SERVER_NAME,
                "--port", EPHEMERAL});
        return new ServerMain(config, List.of(served), cards.getPrefs());
    }

    private StubDeviceManager bench() {
        return (StubDeviceManager) AudioBackend.instance().manager(AudioBackendType.QA40X);
    }
}
