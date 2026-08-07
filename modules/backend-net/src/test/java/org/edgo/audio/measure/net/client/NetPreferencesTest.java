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

package org.edgo.audio.measure.net.client;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.edgo.audio.measure.net.proto.NetProto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The net settings block: a remembered server reaches the live list (and the
 * file) only when the Preferences dialog is closed with OK, and a saved list
 * survives a round trip through the yaml map.  Pure state - no network, no
 * files, no SWT.
 */
class NetPreferencesTest {

    private static final String BENCH_ID    = "b7e0-bench-a";
    private static final String BENCH_NAME  = "Bench QA403";
    private static final String BENCH_HOST  = "192.168.1.40";
    private static final String ROUTED_ID   = "b7e0-bench-b";
    private static final String ROUTED_NAME = "Lab bench";
    private static final String ROUTED_HOST = "10.4.0.9";
    private static final String KEY_SERVERS = "servers";
    private static final String KEY_SERVER_ID = "serverId";
    private static final String KEY_HOST    = "host";
    private static final String KEY_PORT    = "port";
    private static final String KEY_PROXY_HOST = "proxyHost";
    private static final String KEY_PROXY_PORT = "proxyPort";
    private static final String PROXY_HOST = "proxy.lab.example";
    private static final int PROXY_PORT = 3128;
    /** One past the highest port there is - a number, and not a port. */
    private static final int PORT_ABOVE_RANGE = 65_536;

    @Test
    void addedServerIsPendingUntilCommit() {
        NetPreferences prefs = new NetPreferences();
        assertTrue(prefs.getServers().isEmpty(), "nothing is remembered on a fresh install");

        prefs.beginEdit();
        prefs.putEditServer(discovered());
        prefs.setLastConnectedEdit(BENCH_ID);
        assertTrue(prefs.getServers().isEmpty(),
                "the server list must not touch the live values");
        assertNull(prefs.getLastConnected());

        prefs.commitEdit();
        assertEquals(discovered(), prefs.getServers().get(BENCH_ID),
                "the Preferences dialog's OK commits it");
        assertEquals(BENCH_ID, prefs.getLastConnected());
    }

    @Test
    void beginEditDropsAnAbandonedEdit() {
        // The operator types a server in, then cancels the Preferences dialog:
        // reopening the list must show the live servers again, not the abandoned
        // entry.
        NetPreferences prefs = new NetPreferences();
        prefs.beginEdit();
        prefs.putEditServer(routed());
        // ... Cancel: no commit ...
        prefs.beginEdit();
        assertTrue(prefs.getEditServers().isEmpty(),
                "a cancelled edit must not survive into the next session");
        prefs.commitEdit();
        assertTrue(prefs.getServers().isEmpty());
    }

    @Test
    void aConnectedServerIsRememberedLiveSoCancelCannotHideIt() {
        // Staging is for EDITS, and a session that is already up is not one: the
        // bench currently being measured on has to stay in the list whatever the
        // Preferences dialog is closed with - for a routed server there is no
        // beacon that would put it back.
        NetPreferences prefs = new NetPreferences();
        prefs.beginEdit();
        prefs.putServer(routed());

        assertEquals(routed(), prefs.getServers().get(ROUTED_ID),
                "a live connection is remembered live, not staged");
        assertEquals(routed(), prefs.getEditServers().get(ROUTED_ID),
                "and the open server list shows it at the same time");

        prefs.beginEdit();                      // ... Cancel, and the list reopens ...
        assertEquals(routed(), prefs.getEditServers().get(ROUTED_ID),
                "a Cancel that took the connected bench out of the list would contradict "
                        + "a session that is still up");
    }

    @Test
    void removalIsStagedLikeAnAddition() {
        NetPreferences prefs = new NetPreferences();
        prefs.beginEdit();
        prefs.putEditServer(routed());
        prefs.commitEdit();

        prefs.beginEdit();
        prefs.removeEditServer(ROUTED_ID);
        assertTrue(prefs.getServers().containsKey(ROUTED_ID), "still live until OK");
        prefs.commitEdit();
        assertFalse(prefs.getServers().containsKey(ROUTED_ID));
    }

    @Test
    void roundTripsThroughAMap() {
        NetPreferences saved = new NetPreferences();
        saved.beginEdit();
        saved.putEditServer(discovered());
        saved.putEditServer(routed());
        saved.setLastConnectedEdit(ROUTED_ID);
        saved.commitEdit();

        NetPreferences loaded = new NetPreferences();
        loaded.fromMap(saved.toMap());
        assertEquals(discovered(), loaded.getServers().get(BENCH_ID),
                "the live values come back off the file");
        assertEquals(routed(), loaded.getServers().get(ROUTED_ID),
                "the manually typed one too, flagged as such");
        assertEquals(ROUTED_ID, loaded.getLastConnected());
        assertEquals(loaded.getServers(), loaded.getEditServers(),
                "and the edit values start from them");
        assertEquals("net", loaded.key(), "the per-owner prefix in preferences.yaml");
    }

    @Test
    void unknownOrAbsentEntriesKeepTheCurrentValue() {
        // A file from an older or newer release must not throw or reset values.
        NetPreferences prefs = new NetPreferences();
        prefs.beginEdit();
        prefs.putEditServer(discovered());
        prefs.setLastConnectedEdit(BENCH_ID);
        prefs.commitEdit();

        prefs.fromMap(new LinkedHashMap<>());
        assertEquals(discovered(), prefs.getServers().get(BENCH_ID),
                "an empty block leaves the list alone");
        assertEquals(BENCH_ID, prefs.getLastConnected());

        Map<String, Object> alien = new LinkedHashMap<>();
        alien.put("somethingElse", 42);
        prefs.fromMap(alien);
        assertEquals(discovered(), prefs.getServers().get(BENCH_ID),
                "an unrecognised entry leaves the list alone");
    }

    @Test
    void aServerBlockWithoutAPortFallsBackToTheProtocolDefault() {
        NetPreferences prefs = new NetPreferences();
        prefs.fromMap(stored(block(BENCH_ID)));

        NetServerEntry read = prefs.getServers().get(BENCH_ID);
        assertEquals(NetProto.DEFAULT_PORT, read.port());
        assertEquals(BENCH_ID, read.name(), "an unnamed server is shown by its id");
    }

    @Test
    void aSavedServerIsWrittenWithItsOnePort() {
        NetPreferences saved = new NetPreferences();
        saved.beginEdit();
        saved.putEditServer(routed());
        saved.commitEdit();

        Map<String, Object> written = saved.toMap();
        Object block = ((List<?>) written.get(KEY_SERVERS)).get(0);

        assertEquals(NetProto.DEFAULT_PORT, ((Map<?, ?>) block).get(KEY_PORT),
                "one port is what a server has, on the wire and in the file");
    }

    @Test
    void aServerBlockWithoutAnIdIsDropped() {
        // The id is what a server is keyed by - an entry without one could not be
        // matched to the bench that announced itself.
        NetPreferences prefs = new NetPreferences();
        prefs.fromMap(stored(block(null)));

        assertTrue(prefs.getServers().isEmpty());
    }

    // --- the proxy -----------------------------------------------------------

    @Test
    void theProxyIsStagedLikeEverythingElseInThisBlock() {
        NetPreferences prefs = new NetPreferences();
        prefs.beginEdit();
        prefs.setProxyHostEdit(PROXY_HOST);
        prefs.setProxyPortEdit(PROXY_PORT);

        assertNull(prefs.getProxyHost(), "typing is not committing");
        assertNull(prefs.proxy(), "and the live accessor still says direct");
        assertNotNull(prefs.proxyEdit(), "while the screen already has one");

        prefs.commitEdit();
        assertEquals(PROXY_HOST, prefs.getProxyHost(), "the Preferences dialog's OK");
        assertEquals(PROXY_PORT, prefs.getProxyPort());
        assertEquals(proxy(), prefs.proxy());
    }

    @Test
    void aCancelledProxyNeverReachesTheLiveValues() {
        // Cancel does not commit - and the dialog re-seeds the edit copy the
        // next time it opens, which is what makes the abandoned value vanish.
        NetPreferences prefs = new NetPreferences();
        prefs.beginEdit();
        prefs.setProxyHostEdit(PROXY_HOST);
        prefs.setProxyPortEdit(PROXY_PORT);
        // ... Cancel: no commit ...
        prefs.beginEdit();

        assertNull(prefs.getProxyHostEdit(), "a cancelled proxy must not survive");
        assertNull(prefs.proxyEdit());
        assertNull(prefs.proxy(),
                "and the auto-reconnect, which reads the live copy, never saw it");
    }

    @Test
    void theProxyRoundTripsThroughAMapAndIsAbsentUntilItIsSet() {
        NetPreferences unset = new NetPreferences();
        assertNull(unset.toMap().get(KEY_PROXY_HOST),
                "an installation that never typed a proxy writes no proxy keys");
        assertNull(unset.toMap().get(KEY_PROXY_PORT));

        NetPreferences saved = new NetPreferences();
        saved.beginEdit();
        saved.setProxyHostEdit(PROXY_HOST);
        saved.setProxyPortEdit(PROXY_PORT);
        saved.commitEdit();

        NetPreferences loaded = new NetPreferences();
        loaded.fromMap(saved.toMap());

        assertEquals(PROXY_HOST, loaded.getProxyHost());
        assertEquals(PROXY_PORT, loaded.getProxyPort());
        assertEquals(proxy(), loaded.proxy());
        assertEquals(proxy(), loaded.proxyEdit(),
                "fromMap re-seeds the edit copy, so an open list shows the file");
    }

    @Test
    void aFileWithoutAProxyBlockReadsAsNoProxy() {
        NetPreferences prefs = new NetPreferences();
        prefs.fromMap(stored(block(BENCH_ID)));

        assertNull(prefs.getProxyHost());
        assertEquals(0, prefs.getProxyPort());
        assertNull(prefs.proxy(), "absent means direct, not a proxy at port 0");
    }

    /**
     * The ignore-rule in the four shapes it can arrive in: an empty address or
     * an empty port means the proxy settings are ignored.  Half-filled settings
     * are what a pair of fields looks like while they are being typed, so they
     * are IGNORED and the connection goes direct - never refused, and never
     * half-applied.
     */
    @Test
    void aProxyCountsOnlyWhenBothHalvesAreThereAndThePortIsOne() {
        NetPreferences prefs = new NetPreferences();
        prefs.beginEdit();

        prefs.setProxyHostEdit(PROXY_HOST);
        prefs.setProxyPortEdit(0);
        assertNull(prefs.proxyEdit(), "a host with no port is not a proxy");

        prefs.setProxyHostEdit("");
        prefs.setProxyPortEdit(PROXY_PORT);
        assertNull(prefs.proxyEdit(), "a port with no host is not one either");

        prefs.setProxyHostEdit(PROXY_HOST);
        prefs.setProxyPortEdit(PROXY_PORT);
        assertEquals(proxy(), prefs.proxyEdit(), "both, and it is an HTTP proxy");

        prefs.setProxyPortEdit(PORT_ABOVE_RANGE);
        assertNull(prefs.proxyEdit(), "a number that is not a port is not a port");
    }

    // --- helpers -------------------------------------------------------------

    private NetServerEntry discovered() {
        return new NetServerEntry(BENCH_ID, BENCH_NAME, BENCH_HOST,
                NetProto.DEFAULT_PORT, false);
    }

    /** A bench on a routed subnet - no beacon reaches it, so the operator typed
     *  its address in and it is remembered as manual. */
    private NetServerEntry routed() {
        return new NetServerEntry(ROUTED_ID, ROUTED_NAME, ROUTED_HOST,
                NetProto.DEFAULT_PORT, true);
    }

    /** A saved server block carrying only an address, and an id when given. */
    private Map<String, Object> block(String serverId) {
        Map<String, Object> block = new LinkedHashMap<>();
        if (serverId != null) {
            block.put(KEY_SERVER_ID, serverId);
        }
        block.put(KEY_HOST, BENCH_HOST);
        return block;
    }

    /** The proxy those constants describe, as {@code NetPreferences} resolves
     *  it - an HTTP proxy, because that is the one type that carries both a
     *  {@code ws://} tunnel and a plain {@code GET}, and UNRESOLVED, because a
     *  settings block must not do a DNS lookup to answer what it holds. */
    private Proxy proxy() {
        return new Proxy(Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved(PROXY_HOST, PROXY_PORT));
    }

    private Map<String, Object> stored(Map<String, Object> block) {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put(KEY_SERVERS, List.of(block));
        return stored;
    }
}
