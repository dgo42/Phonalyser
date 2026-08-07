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

package org.edgo.audio.measure.gui.backend.net;

import java.util.List;

import org.edgo.audio.measure.net.client.NetPreferences;
import org.edgo.audio.measure.net.client.NetServerEntry;
import org.edgo.audio.measure.net.proto.Beacon;
import org.edgo.audio.measure.net.proto.NetProto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server list as a MODEL: what the dialog would draw, decided without a
 * display and without a network - beacons in, rows out, and every change staged
 * in the settings block until the Preferences dialog commits it.
 *
 * <p>Time is an argument here for the same reason it is in the class under test:
 * "offline" is a comparison against a moment, and a test that had to sleep
 * through {@link NetProto#BEACON_EXPIRY_MS} to see it would prove the same thing
 * two seconds later.
 */
class NetServerListTest {

    private static final String BENCH_ID = "b7e0c4d2-0000-4000-8000-000000000001";
    /** A SECOND installation - switching between two benches is a supported case,
     *  and an id is what tells them apart. */
    private static final String OTHER_ID = "b7e0c4d2-0000-4000-8000-000000000002";
    private static final String BENCH_NAME = "Bench QA403";
    private static final String HOST = "192.168.1.40";
    private static final String OTHER_HOST = "192.168.1.41";
    private static final String ROUTED_HOST = "10.4.0.9";
    private static final String APP = "1.2.0";
    private static final long T0 = 1_000_000;

    private final NetPreferences preferences = new NetPreferences();
    private final NetServerList list = new NetServerList(preferences);

    @Test
    void aHeardServerIsLiveAndGoesOfflineWhenItsBeaconsStop() {
        preferences.beginEdit();
        list.heard(beacon(BENCH_NAME), HOST, T0);

        List<NetServerList.Row> live = list.rows(T0);
        assertEquals(1, live.size());
        assertEquals(BENCH_NAME, live.get(0).server().name());
        assertEquals(HOST, live.get(0).server().host(),
                "spec 2.1: the address is the datagram's source, not the payload's");
        assertTrue(live.get(0).online());
        assertFalse(live.get(0).server().manual(), "discovery found it, nobody typed it in");

        assertTrue(list.rows(T0 + NetProto.BEACON_EXPIRY_MS - 1).get(0).online(),
                "still inside the four-beacon window");
        assertFalse(list.rows(T0 + NetProto.BEACON_EXPIRY_MS).get(0).online(),
                "four missed beacons (2 s) and the bench is shown offline, not dropped - "
                        + "a server that is switched off is still a server the operator knows");
    }

    @Test
    void aServerThatMovedKeepsItsEntryBecauseTheIdIsTheKey() {
        preferences.beginEdit();
        list.heard(beacon(BENCH_NAME), HOST, T0);
        list.heard(beacon("Bench QA403 (moved)"), OTHER_HOST, T0 + 1);

        List<NetServerList.Row> rows = list.rows(T0 + 1);
        assertEquals(1, rows.size(),
                "spec 2.1: a bench is keyed by its installation id, and a DHCP lease is "
                        + "not a new bench");
        assertEquals(OTHER_HOST, rows.get(0).server().host(), "at its fresher address");
        assertEquals("Bench QA403 (moved)", rows.get(0).server().name());
    }

    @Test
    void discoveryDoesNotTurnATypedInServerIntoADisposableOne() {
        preferences.beginEdit();
        list.remember(new NetServerEntry(BENCH_ID, BENCH_NAME, ROUTED_HOST,
                NetProto.DEFAULT_PORT, true));

        list.heard(beacon(BENCH_NAME), HOST, T0);

        NetServerEntry entry = list.rows(T0).get(0).server();
        assertTrue(entry.manual(),
                "the operator typed this one in; hearing it must not take away their "
                        + "right to remove it again");
        assertEquals(HOST, entry.host(), "but the address it actually answers from wins");
    }

    @Test
    void addingAndForgettingAreStagedLikeEverySetting() {
        preferences.beginEdit();
        list.remember(new NetServerEntry(BENCH_ID, BENCH_NAME, ROUTED_HOST,
                NetProto.DEFAULT_PORT, true));
        assertTrue(preferences.getServers().isEmpty(),
                "nothing reaches the live settings until the Preferences dialog's OK");

        preferences.commitEdit();
        assertEquals(1, preferences.getServers().size());

        preferences.beginEdit();
        list.forget(BENCH_ID);
        assertEquals(1, preferences.getServers().size(), "still live until OK");
        assertTrue(list.rows(T0).isEmpty(), "but gone from what the dialog shows");
        preferences.commitEdit();
        assertTrue(preferences.getServers().isEmpty());
    }

    @Test
    void aUnicastAnswerCountsExactlyLikeABeacon() {
        preferences.beginEdit();
        list.remember(new NetServerEntry(BENCH_ID, BENCH_NAME, ROUTED_HOST,
                NetProto.DEFAULT_PORT, true));
        assertFalse(list.rows(T0).get(0).online(),
                "a routed bench's beacons never arrive, so beacons alone leave it offline");

        list.alive(BENCH_ID, T0);

        assertTrue(list.rows(T0).get(0).online(),
                "a row that lets the operator connect must not read offline - "
                        + "GET /info vouches where multicast cannot");
        assertFalse(list.rows(T0 + NetProto.BEACON_EXPIRY_MS).get(0).online(),
                "and the vouching expires on the same clock a beacon does");
    }

    @Test
    void aBeaconWithoutAnIdIsNotAServerToRemember() {
        preferences.beginEdit();
        list.heard(new Beacon(NetProto.BEACON_MAGIC, NetProto.PROTO_VERSION, null,
                BENCH_NAME, APP, NetProto.DEFAULT_PORT), HOST, T0);
        assertTrue(list.rows(T0).isEmpty(),
                "the id is the key of the remembered list; there is nothing to file it under");
    }

    @Test
    void theConnectedServerIsNotSomethingToConnectToAgain() {
        preferences.beginEdit();
        list.heard(beacon(BENCH_NAME), HOST, T0);
        NetServerEntry bench = list.rows(T0).get(0).server();
        NetServerEntry other = new NetServerEntry(OTHER_ID, "Bench two", OTHER_HOST,
                NetProto.DEFAULT_PORT, true);

        assertFalse(list.connectable(null, null),
                "with no row selected there is nothing to connect to");
        assertTrue(list.connectable(bench, null),
                "a selected bench and no session - this is the ordinary Connect");
        assertFalse(list.connectable(bench, bench),
                "the row that IS the session offers nothing to connect to, so "
                        + "Connect is disabled on it");
        assertTrue(list.connectable(other, bench),
                "another bench while one is connected is a switch, and switching is "
                        + "exactly what the button is for");
    }

    @Test
    void aRowIsMatchedByItsIdAndNotByItsAddress() {
        preferences.beginEdit();
        list.heard(beacon(BENCH_NAME), HOST, T0);
        NetServerEntry shown = list.rows(T0).get(0).server();
        // The same installation, seen at the address it had when the session was
        // opened - a DHCP lease later, the row is redrawn at the new one.
        NetServerEntry connected = new NetServerEntry(BENCH_ID, BENCH_NAME, ROUTED_HOST,
                NetProto.DEFAULT_PORT, false);

        assertFalse(list.connectable(shown, connected),
                "spec 2.1 keys a bench on its installation id: the address moving does "
                        + "not make it a second server to connect to");
    }

    private Beacon beacon(String name) {
        return Beacon.of(BENCH_ID, name, APP, NetProto.DEFAULT_PORT);
    }
}
