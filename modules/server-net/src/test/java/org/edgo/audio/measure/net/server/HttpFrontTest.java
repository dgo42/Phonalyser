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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.net.proto.Beacon;
import org.edgo.audio.measure.net.proto.ErrorCode;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetProto;
import org.edgo.audio.measure.sound.AudioBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stateless plane of spec 3, over a real socket on an ephemeral port: the
 * four {@code GET}s, the upload with its 50 MB cap, the delete, the bundle and
 * the CORS header every browser answer needs.
 *
 * <p>An ephemeral port ({@code --port 0}) rather than 8377, because a test that
 * fails when the developer happens to be running a server is a test nobody
 * trusts.
 *
 * <p>It drives the REAL chain - the whole {@link CombinedFront}, upgrade
 * handler included - rather than the HTTP handler alone: the merge put a
 * WebSocket front in front of these endpoints, and a plain {@code GET} falling
 * through it is now part of what every one of them depends on.
 *
 * <p>The over-cap upload is driven through a raw socket on purpose.  What the
 * server promises is that a declared {@code Content-Length} above the cap is
 * refused BEFORE a byte is read - proving that with a real 50 MB body would test
 * the loopback interface instead, and would test it for a second every run.
 */
class HttpFrontTest {

    private static final String SERVER_NAME = "Bench QA403";
    private static final String LOOPBACK = "127.0.0.1";
    private static final String EPHEMERAL = "0";
    private static final String CORS_HEADER = "access-control-allow-origin";
    /** What a browser sends and expects for a request into a private network. */
    private static final String ORIGIN_HEADER = "Origin";
    private static final String REQUEST_METHOD_HEADER = "Access-Control-Request-Method";
    private static final String REQUEST_PRIVATE_NETWORK_HEADER =
            "Access-Control-Request-Private-Network";
    private static final String ALLOW_PRIVATE_NETWORK_HEADER =
            "access-control-allow-private-network";
    /** A page this server did not serve - the standalone web app's case. */
    private static final String FOREIGN_ORIGIN = "http://elsewhere.example";
    private static final String METHOD_OPTIONS = "OPTIONS";
    private static final String PATH_INFO = "/info";
    private static final String PATH_SERVERS = "/servers";
    private static final String CONTENT_TYPE_HEADER = "content-type";
    private static final String INDEX_FILE = "index.html";
    private static final String SECRET_FILE = "secret.txt";
    private static final byte[] UPLOAD = "RIFF....WAVEfmt ".getBytes(StandardCharsets.UTF_8);
    private static final String PEER_ID = "b7e0-uuid";
    private static final String PEER_NAME = "Bench sound card";
    private static final String PEER_HOST = "192.168.1.40";
    private static final int STATUS_LINE_CODE_START = 9;
    private static final int STATUS_LINE_CODE_END = 12;
    /** Fake wall clock for the peer table; these tests never advance it. */
    private static final long FIXED_NOW_MS = 1_000L;

    /** Stands in for the web bundle a packaged build ships (spec 3).  Not
     *  private: JUnit refuses to inject a temporary directory into a private
     *  field. */
    @TempDir
    Path bundle;

    private final ServerConfig config = new ServerConfig(
            new String[] {"--name", SERVER_NAME, "--port", EPHEMERAL});
    private final LockRegistry locks = new LockRegistry();
    private final JsonCodec codec = new JsonCodec();
    private final DeviceCatalog catalog = new DeviceCatalog(AudioBackend.instance(), locks,
            codec, List.of(AudioBackendType.QA40X), new StubCardStore().getPrefs());
    /** The port actually bound, read off the front itself - which is the whole
     *  point: this run asks for an ephemeral one, so the configured value is 0
     *  and no client could dial what it announced. */
    private final BoundPorts ports = new BoundPorts(() -> this.front.getPort());
    private final PeerTable peers = new PeerTable(config, ports, () -> FIXED_NOW_MS);
    private final FileStore files = new FileStore();
    private final HttpClient client = HttpClient.newHttpClient();

    private CombinedFront front;

    @BeforeEach
    void startTheFront() {
        AudioBackend audio = AudioBackend.instance();
        WsFront ws = new WsFront(config, locks, new Qa40xGuard(audio, locks),
                new Qa40xSession(audio, codec, List.of(AudioBackendType.QA40X)), catalog,
                audio, files, codec, FakeTicker::new);
        front = new CombinedFront(config,
                new HttpFront(config, ports, catalog, peers, files, codec, bundle), ws);
        front.start();
    }

    @AfterEach
    void stopTheFront() {
        front.stop();
    }

    @Test
    void healthIsTheCheapestAnswerTheServerHasAndCarriesTheCorsHeader() throws Exception {
        HttpResponse<String> response = get("/health");

        assertEquals(HttpURLConnection.HTTP_OK, response.statusCode());
        assertTrue(json(response).path(NetFields.OK).asBoolean());
        assertEquals("*", response.headers().firstValue(CORS_HEADER).orElse(null),
                "spec 3: permissive CORS - the web app is served by one server and "
                        + "may talk to another");
    }

    /**
     * The liveness probe as a BROWSER makes it: a page on another origin asking
     * this server who it is.  It is the one request the web client's server list
     * is built on, and a missing header here shows up as every remembered bench
     * reading "offline" while it is running perfectly.
     */
    @Test
    void theTwoEndpointsAWebClientProbesAnswerAForeignOrigin() throws Exception {
        for (String path : new String[] {PATH_INFO, PATH_SERVERS}) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(uri(path)).header(ORIGIN_HEADER, FOREIGN_ORIGIN)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(HttpURLConnection.HTTP_OK, response.statusCode(), path);
            assertEquals("*", response.headers().firstValue(CORS_HEADER).orElse(null),
                    path + " must be readable by a page this server did not serve");
        }
    }

    /**
     * The PRIVATE NETWORK preflight (Chrome's Private Network Access): a page
     * that is NOT itself on the private network asks, before the real request,
     * whether it may reach one - {@code OPTIONS} carrying
     * {@code Access-Control-Request-Private-Network: true}.  Answer without
     * {@code Access-Control-Allow-Private-Network: true} and the browser blocks
     * the request it was asking about, whatever the ordinary CORS headers say.
     *
     * <p>That is the difference between the two packagings on a bench: the page a
     * server serves is ON the private network and never asks, so the embedded app
     * works; a page served from anywhere else asks, is refused, and paints every
     * bench offline.  Same server, same endpoint, same CORS headers.
     */
    @Test
    void thePrivateNetworkPreflightIsAnswered() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(uri(PATH_INFO))
                        .method(METHOD_OPTIONS, HttpRequest.BodyPublishers.noBody())
                        .header(ORIGIN_HEADER, FOREIGN_ORIGIN)
                        .header(REQUEST_METHOD_HEADER, "GET")
                        .header(REQUEST_PRIVATE_NETWORK_HEADER, "true")
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(HttpURLConnection.HTTP_NO_CONTENT, response.statusCode());
        assertEquals("*", response.headers().firstValue(CORS_HEADER).orElse(null));
        assertEquals("true",
                response.headers().firstValue(ALLOW_PRIVATE_NETWORK_HEADER).orElse(null),
                "without this the browser refuses the probe it was asking permission for, "
                        + "and every bench on the LAN reads as offline");
    }

    @Test
    void infoNamesTheServerAndTheOnePortBothPlanesAreOn() throws Exception {
        JsonNode data = json(get("/info"));

        assertEquals(config.getServerId(), data.path(NetFields.SERVER_ID).asText());
        assertEquals(SERVER_NAME, data.path(NetFields.NAME).asText());
        assertEquals(config.getApp(), data.path(NetFields.APP).asText());
        assertEquals(NetProto.PROTO_VERSION, data.path(NetFields.PROTO).asInt());
        assertTrue(data.path(NetFields.OS).isTextual());
        assertTrue(data.path(NetFields.UPTIME_S).asLong() >= 0);
        assertEquals(front.getPort(), data.path(NetFields.PORT).asInt(),
                "a client that found /info must know where to open the WebSocket - and "
                        + "it is the port this very answer arrived on, not a second one");
    }

    @Test
    void devicesAnswersTheSameListTheControlChannelDoes() throws Exception {
        JsonNode backends = json(get("/devices")).path(NetFields.BACKENDS);

        assertEquals(1, backends.size());
        assertEquals(AudioBackendType.QA40X.name(),
                backends.get(0).path(NetFields.BACKEND).asText());
        assertTrue(backends.get(0).path(NetFields.DEVICES).size() > 0);
    }

    @Test
    void theSelfRowCarriesThePortThisServerActuallyBound() throws Exception {
        JsonNode self = json(get("/servers")).path(NetFields.SERVERS).get(0);

        assertEquals(front.getPort(), self.path(NetFields.PORT).asInt(),
                "spec 2.2 rows are addresses a client dials, and --port 0 leaves the "
                        + "CONFIGURED value at 0 - announcing that would put a running "
                        + "server on the LAN nobody can reach");
    }

    @Test
    void aRefusalCarriesTheErrorCodeItsVocabularyHasAWordFor() throws Exception {
        HttpResponse<String> refused = get("/files");

        assertEquals(HttpURLConnection.HTTP_BAD_METHOD, refused.statusCode());
        JsonNode error = json(refused).path(NetFields.ERROR);
        assertEquals(ErrorCode.UNSUPPORTED.name(), error.path(NetFields.CODE).asText(),
                "a bare {\"ok\":false} makes every refusal look the same on the wire, "
                        + "and spec 4.2 declares the codes precisely so a client can "
                        + "tell them apart");
        assertFalse(error.path(NetFields.MESSAGE).asText().isBlank());
    }

    @Test
    void serversCarriesThisServerAndEveryPeerItHeard() throws Exception {
        peers.seen(new Beacon(NetProto.BEACON_MAGIC, NetProto.PROTO_VERSION, PEER_ID,
                PEER_NAME, config.getApp(), NetProto.DEFAULT_PORT), PEER_HOST);

        JsonNode servers = json(get("/servers")).path(NetFields.SERVERS);

        assertEquals(2, servers.size());
        assertEquals(config.getServerId(), servers.get(0).path(NetFields.SERVER_ID).asText());
        assertTrue(servers.get(0).path(NetFields.SELF).asBoolean(),
                "spec 2.2: the table includes this server");
        assertEquals(PEER_HOST, servers.get(1).path(NetFields.HOST).asText(),
                "and a peer's address comes from its datagram, never from what it "
                        + "said about itself");
        assertFalse(servers.get(1).path(NetFields.SELF).asBoolean());
    }

    @Test
    void anUploadIsKeptInRamAndAnsweredWithItsHandle() throws Exception {
        HttpResponse<String> response = put("/files", UPLOAD);

        assertEquals(HttpURLConnection.HTTP_OK, response.statusCode());
        JsonNode data = json(response);
        String fileId = data.path(NetFields.FILE_ID).asText();
        assertEquals(UPLOAD.length, data.path(NetFields.BYTES).asInt());
        assertArrayEquals(UPLOAD, files.get(fileId),
                "spec 3: files live in server RAM until something references them");
    }

    @Test
    void anUploadOverTheCapIsRefusedBeforeAByteOfItIsRead() throws Exception {
        String status = rawStatus("PUT /files HTTP/1.1\r\nHost: " + LOOPBACK
                + "\r\nContent-Length: " + (NetProto.MAX_UPLOAD_BYTES + 1L)
                + "\r\nConnection: close\r\n\r\n");

        assertEquals(HttpURLConnection.HTTP_ENTITY_TOO_LARGE, status(status),
                "spec 3: the limit is 50 MB and above it the answer is 413");
    }

    @Test
    void deletingDropsTheFileAndAnUnknownHandleIsNotFound() throws Exception {
        String fileId = json(put("/files", UPLOAD)).path(NetFields.FILE_ID).asText();

        HttpResponse<String> dropped = delete("/files/" + fileId);
        HttpResponse<String> again = delete("/files/" + fileId);

        assertEquals(HttpURLConnection.HTTP_OK, dropped.statusCode());
        assertEquals(HttpURLConnection.HTTP_NOT_FOUND, again.statusCode(),
                "agreeing to drop something we never had would hide a failed upload");
    }

    @Test
    void theBundleIsServedWhenTheBuildShipsOneAndIsOtherwiseNotFound() throws Exception {
        assertEquals(HttpURLConnection.HTTP_NOT_FOUND, get("/").statusCode(),
                "spec 3 marks the bundle 'when built with it'");

        Files.writeString(bundle.resolve(INDEX_FILE), "<html>bench</html>");
        HttpResponse<String> served = get("/");

        assertEquals(HttpURLConnection.HTTP_OK, served.statusCode());
        assertEquals("<html>bench</html>", served.body());
        assertTrue(served.headers().firstValue(CONTENT_TYPE_HEADER).orElse("")
                .startsWith("text/html"));
    }

    @Test
    void aPathThatClimbsOutOfTheBundleIsRefused() throws Exception {
        Files.writeString(bundle.getParent().resolve(SECRET_FILE), "not yours");

        String status = rawStatus("GET /../" + SECRET_FILE + " HTTP/1.1\r\nHost: "
                + LOOPBACK + "\r\nConnection: close\r\n\r\n");

        assertEquals(HttpURLConnection.HTTP_BAD_REQUEST, status(status),
                "the one endpoint that maps a URL onto a file name must not read "
                        + "the operator's disk - a path that climbs above the root "
                        + "cannot be canonicalised, and the transport refuses it before "
                        + "the handler is reached at all (the handler's own "
                        + "normalize-and-startsWith guard is what catches the encoded "
                        + "forms that DO canonicalise)");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String path, byte[] body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://" + LOOPBACK + ":" + front.getPort() + path);
    }

    private JsonNode json(HttpResponse<String> response) throws JsonProcessingException {
        return codec.read(response.body()).getRoot();
    }

    /** Sends a request exactly as typed - the only way to make a claim the HTTP
     *  client would refuse to make (a body length it will not send, a path it
     *  would normalise away) - and answers the status line. */
    private String rawStatus(String request) throws IOException {
        try (Socket socket = new Socket(LOOPBACK, front.getPort())) {
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.US_ASCII));
            return in.readLine();
        }
    }

    /** The code out of {@code "HTTP/1.1 413 Request Entity Too Large"}. */
    private int status(String statusLine) {
        return Integer.parseInt(
                statusLine.substring(STATUS_LINE_CODE_START, STATUS_LINE_CODE_END));
    }
}
