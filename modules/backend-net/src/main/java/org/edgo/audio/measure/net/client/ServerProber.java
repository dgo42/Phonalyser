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

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;

import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetProto;

import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

/**
 * Unicast liveness for the servers discovery cannot vouch for: a MANUAL entry
 * sits on a routed subnet - that is why it was typed in - so its beacons never
 * arrive and the list showed it offline forever, however connectable it was.
 * While the server list is open, this asks each such entry's
 * {@code GET /info} - the spec's lock-free identity endpoint
 * - and reports every server that answers AS the server the row is keyed on,
 * which the list then stamps exactly as it stamps a heard beacon.
 *
 * <p>Alive only while the dialog is, like {@link BeaconListener}: liveness of
 * a list nobody is looking at is not worth the traffic.  One daemon thread,
 * short timeouts - a row that will not answer costs its timeout, not the
 * window.
 */
@Log4j2
@RequiredArgsConstructor
public final class ServerProber {

    private static final String PROBER_THREAD = "net-manual-liveness";
    /** {@code GET /info}: identity and port, no hardware, no lock - the
     *  cheapest thing a Phonalyser server answers. */
    private static final String INFO_PATH = "/info";
    /** One poll round per half {@link NetProto#BEACON_EXPIRY_MS}: a row that
     *  answers stays continuously inside the expiry window, and one that stops
     *  answering goes grey on the same clock a silent beacon does. */
    private static final long ROUND_MS = NetProto.BEACON_EXPIRY_MS / 2;
    /** Per-request budget, connect and read each.  Short on purpose: this is a
     *  liveness poll, and the worst case runs once per row per round. */
    private static final int TIMEOUT_MS = 600;
    /** An {@code /info} body is a small JSON object; more than this is not one. */
    private static final int MAX_BODY_BYTES = 4096;
    private static final int HTTP_OK = 200;

    private final JsonCodec codec;
    /** Told the serverId of every row that answered as itself.  Called on the
     *  prober thread - the subscriber marshals. */
    private final Consumer<String> onAlive;

    /** What to poll, replaced whole by the owner on every refresh.  Volatile:
     *  written on the UI thread, read on the prober thread. */
    private volatile List<NetServerEntry> targets = List.of();
    /** The proxy to reach the rows through, or null for direct - replaced whole
     *  by the owner exactly as {@link #targets} is, because the operator can
     *  type a proxy while this is already polling.  Volatile for the same
     *  reason: written on the UI thread, read on the prober thread. */
    @Setter
    private volatile Proxy proxy;
    private volatile boolean running;
    private Thread thread;

    /** The rows to keep vouching for - replaced whole, never mutated. */
    public void setTargets(List<NetServerEntry> rows) {
        targets = List.copyOf(rows);
    }

    /** Starts polling.  Idempotent. */
    public void start() {
        if (thread != null) {
            return;
        }
        running = true;
        thread = new Thread(this::pollLoop, PROBER_THREAD);
        thread.setDaemon(true);
        thread.start();
    }

    /** Stops polling.  Idempotent; a request in flight runs into its timeout. */
    public void stop() {
        running = false;
        Thread poller = thread;
        thread = null;
        if (poller != null) {
            poller.interrupt();
        }
    }

    private void pollLoop() {
        while (running) {
            for (NetServerEntry row : targets) {
                if (!running) {
                    return;
                }
                String alive = probe(row);
                if (alive != null) {
                    onAlive.accept(alive);
                }
            }
            try {
                Thread.sleep(ROUND_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /**
     * One {@code GET /info} round trip to {@code row} - the confirmed serverId,
     * or null when the host does not answer, answers something that is not a
     * Phonalyser server, or answers as a DIFFERENT server than the row is keyed
     * on (a reinstallation; vouching for the old id would mark a ghost live).
     *
     * <p>Package-private so a test can prove the parse against a local stub
     * server without the polling thread.
     */
    String probe(NetServerEntry row) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("http", row.host(), row.port(), INFO_PATH);
            // The manual rows are exactly the routed ones a proxy exists for -
            // read once per request, so a proxy typed while this is polling
            // takes effect on the next round rather than at the next restart.
            Proxy through = proxy;
            connection = (HttpURLConnection) (through == null
                    ? url.openConnection() : url.openConnection(through));
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            if (connection.getResponseCode() != HTTP_OK) {
                return null;
            }
            byte[] body;
            try (InputStream in = connection.getInputStream()) {
                body = in.readNBytes(MAX_BODY_BYTES);
            }
            JsonNode info = codec.read(new String(body, StandardCharsets.UTF_8),
                    JsonNode.class);
            String reported = info.path(NetFields.SERVER_ID).asText(null);
            return row.serverId().equals(reported) ? reported : null;
        } catch (IOException | RuntimeException e) {
            if (log.isDebugEnabled()) {
                log.debug("net liveness: {}:{} did not answer {}: {}", row.host(),
                        row.port(), INFO_PATH, e.toString());
            }
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
