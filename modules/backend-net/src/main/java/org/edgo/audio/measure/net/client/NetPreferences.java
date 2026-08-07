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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.edgo.audio.measure.net.proto.NetProto;
import org.edgo.audio.measure.preferences.SubPreferences;

import lombok.Getter;
import lombok.Setter;

/**
 * The net bridge's settings block - the {@code custom.net} section of
 * preferences.yaml: which Phonalyser servers this installation knows, which one
 * it was last connected to, and the HTTP proxy to reach them through.
 *
 * <p>Holds each value twice, per the {@link SubPreferences} contract: the LIVE
 * value the application runs on and gets saved, and the EDIT value the server
 * list is changing until the Preferences dialog is closed with OK.  A server
 * added, renamed or removed in that list therefore reaches the file only on OK,
 * exactly like the QA40x block - Cancel drops it with the dialog.
 *
 * <p><b>Keyed by server id, not by address.</b>  The id is the installation UUID
 * of spec 2.1: a bench that moved to another address is still the same bench,
 * and its remembered port and name follow it.
 */
public final class NetPreferences implements SubPreferences {

    private static final String KEY = "net";

    private static final String KEY_SERVERS        = "servers";
    private static final String KEY_LAST_CONNECTED = "lastConnected";
    private static final String KEY_SERVER_ID      = "serverId";
    private static final String KEY_NAME           = "name";
    private static final String KEY_HOST           = "host";
    private static final String KEY_PORT           = "port";
    private static final String KEY_MANUAL         = "manual";
    private static final String KEY_PROXY_HOST     = "proxyHost";
    private static final String KEY_PROXY_PORT     = "proxyPort";

    /** {@link #proxyPort} when no proxy port is set - 0 is not a port anything
     *  can be reached on, so it doubles as "unset" without a second flag. */
    private static final int PORT_UNSET = 0;
    private static final int PORT_MIN = 1;
    private static final int PORT_MAX = 65_535;

    /** The remembered servers the application runs on, keyed by server id and
     *  kept in the order they were added.  Display thread only - see the
     *  threading note on {@code NetBenchUi.attemptReconnect}, which is why a
     *  reconnect that runs on a timer marshals before it touches this. */
    private final Map<String, NetServerEntry> servers = new LinkedHashMap<>();
    /** What the server list is editing; reaches {@link #servers} only through
     *  {@link #commitEdit()}.  Display thread only, for the same reason. */
    private final Map<String, NetServerEntry> serversEdit = new LinkedHashMap<>();

    /** The server this installation last connected to, or {@code null} - what a
     *  reconnect starts from, resolved against {@link #getServers()}. */
    @Getter
    private String lastConnected;
    /** The value the server list is editing; reaches {@link #lastConnected} only
     *  through {@link #commitEdit()}. */
    @Getter
    @Setter
    private String lastConnectedEdit;

    /** The HTTP proxy this installation reaches routed servers through, or
     *  null/empty for none - the operator's own field, never discovered. */
    @Getter
    private String proxyHost;
    /** {@link #proxyHost}'s port, or {@link #PORT_UNSET} when the operator left
     *  it empty. */
    @Getter
    private int proxyPort;
    /** What the servers dialog is editing; reaches {@link #proxyHost} only
     *  through {@link #commitEdit()}. */
    @Getter
    @Setter
    private String proxyHostEdit;
    /** What the servers dialog is editing; reaches {@link #proxyPort} only
     *  through {@link #commitEdit()}. */
    @Getter
    @Setter
    private int proxyPortEdit;

    // -------------------------------------------------------------------------
    // The proxy
    // -------------------------------------------------------------------------

    /**
     * The proxy the COMMITTED settings say to use, or null for a direct
     * connection - what the auto-reconnect dials through, because a retry that
     * fires minutes after the Preferences dialog was cancelled must use what the
     * operator kept, not what they typed and abandoned.
     */
    public Proxy proxy() {
        return resolve(proxyHost, proxyPort);
    }

    /**
     * The proxy the settings ON SCREEN say to use, or null - what the servers
     * dialog's own Connect, Probe and Add dial through, since the operator
     * typing a proxy and immediately pressing Connect means THAT proxy.
     */
    public Proxy proxyEdit() {
        return resolve(proxyHostEdit, proxyPortEdit);
    }

    /**
     * The one place the ignore-rule lives: an empty address or an empty port
     * means the proxy settings are ignored.  A host nobody typed, or a port
     * that is not a port, means NO proxy and a silent direct connection - not
     * an error, because half-filled settings are how a field looks while it is
     * being typed, so they are ignored rather than refused.
     *
     * <p><b>UNRESOLVED on purpose.</b>  {@code new InetSocketAddress(host, port)}
     * performs a DNS lookup in the constructor, and this method is called from
     * the servers dialog's repaint - twice a second, on the display thread,
     * against a name that may be half-typed and will not resolve.  The transport
     * resolves the proxy when it connects, which is where a name lookup belongs
     * and where its failure is a connection error rather than a frozen window.
     */
    private Proxy resolve(String host, int port) {
        if (host == null || host.isBlank() || port < PORT_MIN || port > PORT_MAX) {
            return null;
        }
        return new Proxy(Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved(host.trim(), port));
    }

    // -------------------------------------------------------------------------
    // The remembered servers
    // -------------------------------------------------------------------------

    /** The LIVE servers, keyed by server id - read-only: the list is changed
     *  through the edit copy, which is what makes Cancel possible. */
    public Map<String, NetServerEntry> getServers() {
        return Collections.unmodifiableMap(servers);
    }

    /** The servers as the open server list has them, keyed by server id. */
    public Map<String, NetServerEntry> getEditServers() {
        return Collections.unmodifiableMap(serversEdit);
    }

    /** Adds {@code server} to the edit copy, or replaces what was remembered
     *  under its id - a bench that answered from a new address, or under a new
     *  name, is the same entry with fresher contents. */
    public void putEditServer(NetServerEntry server) {
        serversEdit.put(server.serverId(), server);
    }

    /** Forgets one server in the edit copy. */
    public void removeEditServer(String serverId) {
        serversEdit.remove(serverId);
    }

    /** Remembers {@code server} in BOTH copies at once - what a CONNECTION does.
     *  Staging exists so a list edit can be dropped by Cancel, and a session that
     *  is already live is not an edit: a Cancel that made the bench currently
     *  being measured on vanish from the list would be a lie about the state of
     *  the machine, and for a routed server there is no beacon to put it back. */
    public void putServer(NetServerEntry server) {
        servers.put(server.serverId(), server);
        serversEdit.put(server.serverId(), server);
    }

    // -------------------------------------------------------------------------
    // The SubPreferences contract
    // -------------------------------------------------------------------------

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        List<Object> written = new ArrayList<>();
        for (NetServerEntry server : servers.values()) {
            Map<String, Object> block = new LinkedHashMap<>();
            block.put(KEY_SERVER_ID, server.serverId());
            block.put(KEY_NAME,      server.name());
            block.put(KEY_HOST,      server.host());
            block.put(KEY_PORT,      server.port());
            block.put(KEY_MANUAL,    server.manual());
            written.add(block);
        }
        map.put(KEY_SERVERS, written);
        if (lastConnected != null) {
            map.put(KEY_LAST_CONNECTED, lastConnected);
        }
        // Written only when set: an installation that never typed a proxy keeps
        // an empty block rather than two keys saying "nothing", and a file the
        // operator reads is shorter for it.
        if (proxyHost != null && !proxyHost.isBlank()) {
            map.put(KEY_PROXY_HOST, proxyHost);
        }
        if (proxyPort != PORT_UNSET) {
            map.put(KEY_PROXY_PORT, proxyPort);
        }
        return map;
    }

    @Override
    public void fromMap(Map<?, ?> map) {
        if (map.get(KEY_SERVERS) instanceof List<?> stored) {
            servers.clear();
            for (Object element : stored) {
                if (element instanceof Map<?, ?> block) {
                    read(block);
                }
            }
        }
        if (map.get(KEY_LAST_CONNECTED) instanceof String s) {
            lastConnected = s;
        }
        if (map.get(KEY_PROXY_HOST) instanceof String s) {
            proxyHost = s;
        }
        if (map.get(KEY_PROXY_PORT) instanceof Number n) {
            proxyPort = n.intValue();
        }
        // Keep a list opened before this arrives consistent with the file.
        beginEdit();
    }

    @Override
    public void beginEdit() {
        serversEdit.clear();
        serversEdit.putAll(servers);
        lastConnectedEdit = lastConnected;
        proxyHostEdit = proxyHost;
        proxyPortEdit = proxyPort;
    }

    @Override
    public void commitEdit() {
        servers.clear();
        servers.putAll(serversEdit);
        lastConnected = lastConnectedEdit;
        proxyHost = proxyHostEdit;
        proxyPort = proxyPortEdit;
    }

    /** One saved server block.  An entry without an id cannot be keyed and is
     *  dropped; a missing port falls back to the protocol's default, which is
     *  what the operator was offered when they typed the server in. */
    private void read(Map<?, ?> block) {
        if (!(block.get(KEY_SERVER_ID) instanceof String serverId) || serverId.isEmpty()) {
            return;
        }
        String name = block.get(KEY_NAME) instanceof String s ? s : serverId;
        String host = block.get(KEY_HOST) instanceof String s ? s : null;
        int port = block.get(KEY_PORT) instanceof Number n
                ? n.intValue() : NetProto.DEFAULT_PORT;
        boolean manual = block.get(KEY_MANUAL) instanceof Boolean b && b;
        servers.put(serverId, new NetServerEntry(serverId, name, host, port, manual));
    }
}
