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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import com.sun.net.httpserver.HttpServer;

import org.edgo.audio.measure.net.proto.JsonCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The manual row's liveness probe against a real HTTP endpoint: one
 * {@code GET /info} round trip, vouched only when the answering server IS the
 * server the row is keyed on.  A stub {@code com.sun.net.httpserver} plays the
 * bench - the same JDK server the real {@code HttpFront} is built on.
 */
class ServerProberTest {

    private static final String BENCH_ID = "b7e0c4d2-0000-4000-8000-000000000001";
    private static final String OTHER_ID = "b7e0c4d2-0000-4000-8000-000000000002";
    private static final String LOOPBACK = "127.0.0.1";
    private static final String INFO_PATH = "/info";
    /** The {@code /info} shape the server answers - identity first, which is the
     *  one field the probe reads. */
    private static final String INFO_BODY =
            "{\"serverId\":\"" + BENCH_ID + "\",\"name\":\"Bench\",\"port\":8377}";

    private final ServerProber prober = new ServerProber(new JsonCodec(), id -> { });

    private HttpServer http;
    private int port;

    @BeforeEach
    void serveInfo() throws IOException {
        http = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
        http.createContext(INFO_PATH, exchange -> {
            byte[] body = INFO_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        http.start();
        port = http.getAddress().getPort();
    }

    @AfterEach
    void stopServing() {
        http.stop(0);
    }

    @Test
    void aServerThatAnswersAsItselfIsVouchedFor() {
        assertEquals(BENCH_ID, prober.probe(entry(BENCH_ID)),
                "a manual entry that answers must not read offline - this is the "
                        + "beacon substitute across a router");
    }

    @Test
    void aServerThatAnswersAsSomebodyElseIsNot() {
        assertNull(prober.probe(entry(OTHER_ID)),
                "a reinstalled server answers under a new id; vouching for the old "
                        + "row would mark a ghost live");
    }

    @Test
    void aHostThatDoesNotAnswerIsNot() {
        http.stop(0);
        assertNull(prober.probe(entry(BENCH_ID)));
    }

    private NetServerEntry entry(String serverId) {
        return new NetServerEntry(serverId, "Bench", LOOPBACK, port, true);
    }
}
