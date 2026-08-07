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

import java.nio.file.Path;

import org.edgo.audio.measure.net.proto.NetProto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server's command line:
 * {@code [--name <text>] [--port 8377] [--bind <addr>]}.  ONE port serves both
 * planes, and its default is the spec's number, so a client that was told
 * nothing but an address still finds the server.
 *
 * <p>The {@code serverId} is the other half of what this class decides, and spec
 * 2.1 makes it an INSTALLATION property rather than a process one - so every
 * test here names its own identity file and the developer's real one is never
 * touched.
 */
class ServerConfigTest {

    private static final String SERVER_NAME = "Bench QA403";
    private static final String LOOPBACK = "127.0.0.1";
    private static final int PORT = 9377;
    /** A flag this build does not know, standing in for whatever a future phase
     *  puts on the same command line. */
    private static final String UNKNOWN_FLAG = "--frobnicate";
    private static final String UNKNOWN_VALUE = "on";

    /** Where these runs keep their {@code serverId}.  Not private: JUnit refuses
     *  to inject a temporary directory into a private field. */
    @TempDir
    Path dataDir;

    @Test
    void anEmptyCommandLineTakesTheSpecDefaults() {
        ServerConfig config = config(new String[0]);

        assertEquals(NetProto.DEFAULT_PORT, config.getPort(),
                "one port serves the HTTP endpoints of spec 3 and the WebSocket "
                        + "upgrade of spec 4");
        assertNull(config.getBind(), "no --bind means every interface");
        assertFalse(config.getName().isBlank(), "the host name stands in for --name");
        assertFalse(config.getServerId().isBlank());
        assertFalse(config.getApp().isBlank());
        assertTrue(config.address().getAddress().isAnyLocalAddress());
    }

    @Test
    void everyFlagIsRead() {
        ServerConfig config = config(new String[] {
            "--name", SERVER_NAME, "--port", String.valueOf(PORT),
            "--bind", LOOPBACK});

        assertEquals(SERVER_NAME, config.getName());
        assertEquals(PORT, config.getPort());
        assertEquals(LOOPBACK, config.getBind());
        assertEquals(LOOPBACK, config.address().getHostString(),
                "--bind restricts the interface the front listens on");
        assertEquals(PORT, config.address().getPort());
    }

    @Test
    void anUnknownFlagIsIgnoredRatherThanRefused() {
        ServerConfig config = config(new String[] {
            UNKNOWN_FLAG, UNKNOWN_VALUE, "--name", SERVER_NAME});

        assertEquals(SERVER_NAME, config.getName(),
                "the same argv may carry a launch script's own switches and whatever "
                        + "a future phase adds, so an unrecognised flag is skipped");
        assertEquals(NetProto.DEFAULT_PORT, config.getPort());
    }

    @Test
    void theDaemonSwitchIsReadInBothSpellingsAndDefaultsOff() {
        assertFalse(config(new String[0]).isDaemon(),
                "a server started without -d keeps its console");
        assertTrue(config(new String[] {"-d"}).isDaemon());
        assertTrue(config(new String[] {"--daemon"}).isDaemon());
        assertTrue(config(new String[] {"-D"}).isDaemon(),
                "flags are case-insensitive, like every other option here");
    }

    @Test
    void theServerIdIsMintedOnceAndReadBackOnEveryLaterStart() {
        String minted = config(new String[0]).getServerId();

        assertEquals(minted, config(new String[0]).getServerId(),
                "spec 2.1: the id is generated once per INSTALLATION, and clients "
                        + "key their remembered-server list on it - a restart that "
                        + "handed out a new one would point every client at a server "
                        + "that no longer exists and lose its per-server settings");
    }

    @Test
    void aSecondInstallationGetsAnIdOfItsOwn() {
        String here = config(new String[0]).getServerId();

        assertFalse(here.equals(new ServerConfig(new String[0],
                        dataDir.resolve("elsewhere").resolve("server-id")).getServerId()),
                "two benches on the LAN must never answer with the same id");
    }

    @Test
    void aFlagWithoutItsValueStopsTheServerStartingUp() {
        assertThrows(IllegalArgumentException.class,
                () -> config(new String[] {"--port"}),
                "a trailing flag whose value was forgotten must not fall back to the "
                        + "default port behind the operator's back");
    }

    @Test
    void aPortThatIsNotANumberStopsTheServerStartingUp() {
        assertThrows(IllegalArgumentException.class,
                () -> config(new String[] {"--port", "eight-three-seven-seven"}),
                "a server listening on the wrong port is worse than one that refuses");
    }

    /** The server's default data home is MACHINE scope on Windows - the
     *  per-user store belongs to the GUI - and deliberately absent elsewhere,
     *  where the machine directories need root and the service scripts pin
     *  them explicitly. */
    @Test
    void theDefaultDataDirIsMachineScopeOnWindowsAndAbsentElsewhere() {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("win");
        if (windows) {
            assertEquals(ServerConfig.machineDataDir(), ServerConfig.defaultDataDir(),
                    "every Windows launch form must share the machine-scope home");
            assertTrue(ServerConfig.defaultDataDir().endsWith("Phonalyser"),
                    "the machine home is the Phonalyser directory under ProgramData");
        } else {
            assertNull(ServerConfig.defaultDataDir(),
                    "a root-less console run must keep the per-user directory");
        }
    }

    /** A configuration whose installation identity lives in this test's own
     *  directory rather than in the developer's data directory. */
    private ServerConfig config(String[] args) {
        return new ServerConfig(args, dataDir.resolve("server-id"));
    }
}
