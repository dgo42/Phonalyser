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

import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server ships TWO logging configs and picks between them: {@code
 * log4j2.xml} is auto-loaded for a foreground run (console + file), and
 * {@code ServerMain.main} reconfigures to {@code log4j2-server.xml} under
 * {@code -d} (the same file, no console).
 *
 * <p>Every way this can break is silent on a bench - a config that vanished
 * from the jar, a console appender in the service flavour writing to a handle
 * nobody reads, a sound layer too quiet to name the device behind a stall, a
 * server writing into the desktop's log file, or the two configs drifting apart
 * so that what a terminal shows is no longer what the service records.  Each of
 * those is a red test here instead.
 */
class ServerLoggingTest {

    /** Auto-loaded by log4j for a foreground run: console AND file. */
    private static final String FOREGROUND_CONFIG = "/log4j2.xml";
    /** Loaded by {@code ServerMain.main} under {@code -d}: file only. */
    private static final String DAEMON_CONFIG = "/log4j2-server.xml";
    /** The one logger the configs must raise to INFO - the server's packages. */
    private static final String NET_LOGGER_NAME = "org.edgo.audio.measure.net";
    /** The sound layer, raised to INFO so an opened line's MIXER is recorded. */
    private static final String SOUND_LOGGER_NAME = "org.edgo.audio.measure.sound";
    /** The server's own log file - never the desktop's phonalyser.log. */
    private static final String SERVER_LOG_FILE = "phonalyser-server.log";

    /** {@code <Logger name="..." level="..."} - the pair the two configs must agree
     *  on, in declaration order. */
    private static final Pattern LOGGER_LEVEL =
            Pattern.compile("<Logger\\s+name=\"([^\"]+)\"\\s+level=\"([^\"]+)\"");
    private static final Pattern ROOT_LEVEL = Pattern.compile("<Root\\s+level=\"([^\"]+)\"");

    @Test
    void bothConfigsShipAndParse() throws Exception {
        for (String resource : List.of(FOREGROUND_CONFIG, DAEMON_CONFIG)) {
            URL url = ServerMain.class.getResource(resource);
            assertNotNull(url, resource + " must ship on the class path - without it "
                    + "the server has no logging config of its own and every "
                    + "operator-facing INFO line vanishes");
            try (InputStream in = url.openStream()) {
                DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
            }
            assertTrue(config(resource).contains("name=\"" + NET_LOGGER_NAME + "\""),
                    resource + " must raise " + NET_LOGGER_NAME + " to INFO - that "
                            + "logger entry is what makes the banner and the session "
                            + "lines visible at all");
        }
    }

    /**
     * The three things the DAEMON config exists to guarantee, each of which
     * fails silently on a bench: a console appender nobody can read, a sound
     * layer too quiet to name the device behind a stall, or a server writing
     * into the desktop's log file.
     */
    @Test
    void theDaemonConfigHasNoConsoleAndKeepsTheSoundLayerAtInfo() throws Exception {
        String xml = config(DAEMON_CONFIG);

        assertFalse(xml.contains("<Console"),
                "a service has no console to print to - every service definition "
                        + "passes -d - so the appender must not exist at all, not "
                        + "merely be thresholded off");
        assertTrue(xml.contains("name=\"" + SOUND_LOGGER_NAME + "\" level=\"INFO\""),
                "the sound layer must log at INFO: the mixer identity of an opened "
                        + "JavaSound line is what names the device behind a render "
                        + "stall, and on a headless bench the log file is the only "
                        + "place it can be read");
        assertTrue(xml.contains(SERVER_LOG_FILE),
                "the server writes its own file - a bench may run the desktop app "
                        + "and a server at once, and two JVMs appending one rolling "
                        + "file fight over the rollover");
        assertFalse(xml.contains("/phonalyser.log"), "and never the desktop's");
    }

    /** The FOREGROUND config is the same thing WITH a console: an operator who
     *  starts the server in a terminal must see it working. */
    @Test
    void theForegroundConfigHasTheConsoleAndTheSameServerFile() throws Exception {
        String xml = config(FOREGROUND_CONFIG);

        assertTrue(xml.contains("<Console"),
                "a foreground run is watched in a terminal - without the console "
                        + "appender the operator sees nothing at all and the server "
                        + "looks dead");
        assertTrue(xml.contains(SERVER_LOG_FILE),
                "and it writes the SAME file as the service flavour, so a bench "
                        + "keeps one server log however it was started");
        assertFalse(xml.contains("/phonalyser.log"), "never the desktop's");
    }

    /**
     * The pair may differ in the console and in NOTHING else.  If the levels
     * drifted, what an operator sees in a terminal would stop being what the
     * same server records as a service - and the difference would only ever be
     * noticed while chasing a bug with the wrong log in hand.
     */
    @Test
    void theTwoConfigsCarryIdenticalLoggerLevels() throws Exception {
        assertEquals(loggerLevels(config(FOREGROUND_CONFIG)),
                loggerLevels(config(DAEMON_CONFIG)),
                "the foreground and daemon configs must declare the same loggers at "
                        + "the same levels - they are one configuration in two "
                        + "flavours, and the console is the only allowed difference");
    }

    /** Every {@code name=level} pair the config declares, plus the Root level,
     *  in declaration order. */
    private List<String> loggerLevels(String xml) {
        List<String> levels = new ArrayList<>();
        Matcher loggers = LOGGER_LEVEL.matcher(xml);
        while (loggers.find()) {
            levels.add(loggers.group(1) + "=" + loggers.group(2));
        }
        Matcher root = ROOT_LEVEL.matcher(xml);
        if (root.find()) {
            levels.add("Root=" + root.group(1));
        }
        return levels;
    }

    private String config(String resource) throws Exception {
        URL url = ServerMain.class.getResource(resource);
        assertNotNull(url, resource + " must ship on the class path");
        try (InputStream in = url.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
