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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

import org.edgo.audio.measure.net.proto.ErrorCode;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetProto;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * The stateless plane of spec 3: {@code /info}, {@code /devices},
 * {@code /servers}, {@code /health}, the file upload of {@code PUT /files}, and
 * the web bundle at {@code /}.  Everything here is answerable with curl and
 * carries no session - the control plane is {@link WsFront}'s.
 *
 * <p>It is the HTTP twin of that class: transport only.  What the answers MEAN
 * belongs to the types that own the state - {@link DeviceCatalog} knows the
 * bench, {@link PeerTable} knows the LAN, {@link FileStore} holds the uploads -
 * and this one turns their values into responses.
 *
 * <p><b>One port.</b>  This is a plain Jetty handler now, and it does not own a
 * listener: {@link CombinedFront} binds the ONE socket and lets a request with
 * a WebSocket upgrade go to {@link WsFront} while everything else falls through
 * to here.  So a client dials {@code ws://host:port/} and
 * {@code http://host:port/info} on the same number.
 *
 * <p><b>CORS is permissive by design</b> (spec 3: "no authentication in v1 -
 * this is a LAN instrument protocol; {@code --bind} restricts the interface").
 * The web client is served from one server and may talk to another, so a
 * restrictive origin policy would break the supported deployment while
 * protecting nothing that is not already reachable.
 *
 * <p><b>Threading.</b>  Handlers run on Jetty's own pool, never on a WebSocket
 * callback or the keepalive scheduler: a 50 MB upload takes as long as the
 * client's uplink, and {@code /health} must stay answerable meanwhile.
 * {@code /devices} enumerates hardware, which is why it may not run on either of
 * those lanes - the HTTP pool is a lane of its own, and blocking it delays
 * nothing but other HTTP requests.  Blocking is allowed here precisely because
 * {@link Handler.Abstract}'s default invocation type says so.
 */
@Log4j2
@RequiredArgsConstructor
public final class HttpFront extends Handler.Abstract {

    private static final String PATH_INFO = "/info";
    private static final String PATH_DEVICES = "/devices";
    private static final String PATH_SERVERS = "/servers";
    private static final String PATH_HEALTH = "/health";
    private static final String PATH_FILES = "/files";

    private static final String METHOD_GET = "GET";
    private static final String METHOD_PUT = "PUT";
    private static final String METHOD_DELETE = "DELETE";
    private static final String METHOD_OPTIONS = "OPTIONS";

    private static final String HEADER_ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    private static final String HEADER_ALLOW_METHODS = "Access-Control-Allow-Methods";
    private static final String HEADER_ALLOW_HEADERS = "Access-Control-Allow-Headers";
    /** The answer to a browser's private-network question - see {@link #handle}. */
    private static final String HEADER_ALLOW_PRIVATE_NETWORK =
            "Access-Control-Allow-Private-Network";
    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String HEADER_CONTENT_LENGTH = "Content-Length";
    private static final String ANY = "*";
    private static final String ALLOWED_METHODS = "GET, PUT, DELETE, OPTIONS";
    private static final String TYPE_JSON = "application/json; charset=utf-8";
    private static final String TYPE_TEXT = "text/plain; charset=utf-8";
    private static final String TYPE_OCTETS = "application/octet-stream";

    /** The bundle's entry document, for a request that names a directory. */
    private static final String INDEX_FILE = "index.html";
    /** Content types the bundle needs and {@code Files.probeContentType} gets
     *  wrong or not at all on a bare Windows host. */
    private static final Map<String, String> STATIC_TYPES = Map.of(
            ".html", "text/html; charset=utf-8",
            ".js", "text/javascript; charset=utf-8",
            ".css", "text/css; charset=utf-8",
            ".json", TYPE_JSON,
            ".svg", "image/svg+xml",
            ".png", "image/png",
            ".ico", "image/x-icon");

    private static final int COPY_BUFFER_BYTES = 64 * 1024;
    private static final int NANOS_PER_SECOND = 1_000_000_000;

    private final ServerConfig config;
    /** Where this server actually listens - {@code /info} hands a client the
     *  port it is expected to dial, and an ephemeral {@code --port 0} makes the
     *  configured value {@code 0}, which is not an address. */
    private final BoundPorts ports;
    private final DeviceCatalog catalog;
    private final PeerTable peers;
    private final FileStore files;
    private final JsonCodec codec;
    /** Where the web bundle is looked for; served only while it exists, so a
     *  build without one simply answers 404 at {@code /} (spec 3 marks the
     *  bundle "when built with it"). */
    private final Path staticDir;

    private long startedNanos;

    /** The uptime clock of {@code /info} starts when the handler does - which is
     *  when the server it belongs to starts, so there is no second moment to
     *  keep in step with {@link CombinedFront}. */
    @Override
    protected void doStart() throws Exception {
        startedNanos = System.nanoTime();
        super.doStart();
    }

    /**
     * One request, CORS'd and guaranteed to be answered.
     *
     * <p>The headers of spec 3 go on every answer, the preflight a browser sends
     * before a {@code PUT} is answered here, and a handler that throws becomes a
     * 500 rather than taking the front down with it - a fault in one endpoint is
     * a server fault, not a protocol one.
     *
     * <p><b>Private-network access is granted explicitly.</b>  A browser asks
     * one more question before it lets a page reach a LAN address it is not
     * itself on: the preflight carries
     * {@code Access-Control-Request-Private-Network}, and without
     * {@code Access-Control-Allow-Private-Network: true} in the answer the
     * request it was asking about is blocked - whatever the ordinary CORS
     * headers say.  That is precisely the difference between the two web
     * packagings on a bench: the page a server serves IS on the private network
     * and never asks, while a page served from anywhere else asks, is refused,
     * and paints every server on the LAN as offline.  It is granted for the same
     * reason the origin is (spec 3: no authentication, a LAN instrument
     * protocol, {@code --bind} is the knob that limits reach), and it is granted
     * HERE only: the WebSocket upgrade is taken by the handler above this one
     * and is not touched.
     *
     * <p>Always true: this handler is the last in the chain, so a path it does
     * not serve is a 404 it writes itself rather than one Jetty invents.
     */
    @Override
    public boolean handle(Request request, Response response, Callback callback) {
        HttpFields.Mutable headers = response.getHeaders();
        headers.put(HEADER_ALLOW_ORIGIN, ANY);
        headers.put(HEADER_ALLOW_METHODS, ALLOWED_METHODS);
        headers.put(HEADER_ALLOW_HEADERS, ANY);
        headers.put(HEADER_ALLOW_PRIVATE_NETWORK, Boolean.TRUE.toString());
        try {
            if (METHOD_OPTIONS.equals(request.getMethod())) {
                response.setStatus(HttpURLConnection.HTTP_NO_CONTENT);
                callback.succeeded();
                return true;
            }
            route(request, response, callback);
        } catch (IOException | RuntimeException e) {
            if (log.isWarnEnabled()) {
                log.warn("net http: {} failed: {}", request.getHttpURI(), e.toString());
            }
            fail(response, callback);
        }
        return true;
    }

    /** Spec 3's endpoints, and the bundle for everything else.  {@code /files}
     *  matches by prefix because {@code DELETE /files/{id}} names one. */
    private void route(Request request, Response response, Callback callback)
            throws IOException {
        String path = path(request);
        if (PATH_INFO.equals(path)) {
            info(request, response, callback);
        } else if (PATH_DEVICES.equals(path)) {
            devices(request, response, callback);
        } else if (PATH_SERVERS.equals(path)) {
            servers(request, response, callback);
        } else if (PATH_HEALTH.equals(path)) {
            health(request, response, callback);
        } else if (path.equals(PATH_FILES) || path.startsWith(PATH_FILES + "/")) {
            fileCommand(request, response, callback);
        } else {
            bundle(request, response, callback);
        }
    }

    /** Spec 3: who this server is and where it listens. */
    private void info(Request request, Response response, Callback callback) {
        if (!isMethod(request, response, callback, METHOD_GET)) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(NetFields.SERVER_ID, config.getServerId());
        data.put(NetFields.NAME, config.getName());
        data.put(NetFields.APP, config.getApp());
        data.put(NetFields.PROTO, NetProto.PROTO_VERSION);
        data.put(NetFields.OS, System.getProperty("os.name"));
        data.put(NetFields.UPTIME_S, (System.nanoTime() - startedNanos) / NANOS_PER_SECOND);
        data.put(NetFields.PORT, ports.port());
        json(response, callback, HttpURLConnection.HTTP_OK, data);
    }

    /** Spec 3: "same device list as {@code devices.list}, lock state included -
     *  for curl debugging".  A fresh enumeration, on the HTTP pool. */
    private void devices(Request request, Response response, Callback callback) {
        if (!isMethod(request, response, callback, METHOD_GET)) {
            return;
        }
        json(response, callback, HttpURLConnection.HTTP_OK,
                Map.of(NetFields.BACKENDS, catalog.scan()));
    }

    /** Spec 2.2: the peer table, self included. */
    private void servers(Request request, Response response, Callback callback) {
        if (!isMethod(request, response, callback, METHOD_GET)) {
            return;
        }
        json(response, callback, HttpURLConnection.HTTP_OK,
                Map.of(NetFields.SERVERS, peers.servers()));
    }

    /** Spec 3: {@code 200 {"ok":true}} and nothing else - the liveness probe a
     *  monitor polls, so it touches no hardware and takes no lock. */
    private void health(Request request, Response response, Callback callback) {
        if (!isMethod(request, response, callback, METHOD_GET)) {
            return;
        }
        json(response, callback, HttpURLConnection.HTTP_OK, Map.of(NetFields.OK, true));
    }

    /** Spec 3: {@code PUT /files} takes a file for remote playback,
     *  {@code DELETE /files/{id}} gives it back. */
    private void fileCommand(Request request, Response response, Callback callback)
            throws IOException {
        String method = request.getMethod();
        if (METHOD_PUT.equals(method)) {
            upload(request, response, callback);
        } else if (METHOD_DELETE.equals(method)) {
            drop(request, response, callback);
        } else {
            notAllowed(request, response, callback);
        }
    }

    /**
     * Spec 3: raw body, {@code 413} above 50 MB, answered
     * {@code {"fileId":"f-1","bytes":N}}.
     *
     * <p>The cap is checked twice on purpose.  A declared {@code Content-Length}
     * over the limit is refused before a single byte is read - that is the one
     * that matters, because it costs the client's uplink nothing and the server
     * no memory.  The read is capped again for the request that declares no
     * length at all (chunked transfer), where the only way to know is to count.
     */
    private void upload(Request request, Response response, Callback callback)
            throws IOException {
        if (declaredLength(request) > NetProto.MAX_UPLOAD_BYTES) {
            tooLarge(response, callback,
                    "the upload limit is " + NetProto.MAX_UPLOAD_BYTES + " bytes");
            return;
        }
        byte[] content = readCapped(Request.asInputStream(request),
                NetProto.MAX_UPLOAD_BYTES);
        if (content == null) {
            tooLarge(response, callback,
                    "the upload limit is " + NetProto.MAX_UPLOAD_BYTES + " bytes");
            return;
        }
        // Spec 3 (v1.1): the declared type travels with the bytes, so the
        // generator stages the file by what the client says it IS rather than by
        // what its first bytes resemble.  Kept verbatim - this end validates
        // nothing, because an unknown type is the decoder's business at
        // gen.playFile, not a reason to refuse an upload.
        String mimeType = request.getHeaders().get(HEADER_CONTENT_TYPE);
        String fileId = files.put(content, mimeType);
        if (fileId == null) {
            tooLarge(response, callback, "the store already holds "
                    + files.getMaxTotalBytes()
                    + " bytes of uploads - delete one before adding another");
            return;
        }
        if (log.isInfoEnabled()) {
            log.info("net files: {} accepted, {} byte(s), type {}", fileId,
                    content.length, mimeType == null ? "(none)" : mimeType);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(NetFields.FILE_ID, fileId);
        data.put(NetFields.BYTES, content.length);
        json(response, callback, HttpURLConnection.HTTP_OK, data);
    }

    /** Spec 3: {@code DELETE /files/{id}}.  An unknown handle is a 404 - the
     *  client is telling us to drop something we never had, and silently
     *  agreeing would hide an upload that failed. */
    private void drop(Request request, Response response, Callback callback) {
        String fileId = lastPathSegment(request);
        if (!files.remove(fileId)) {
            error(response, callback, HttpURLConnection.HTTP_NOT_FOUND,
                    ErrorCode.NO_SUCH_FILE, "no uploaded file with id " + fileId);
            return;
        }
        json(response, callback, HttpURLConnection.HTTP_OK, Map.of(NetFields.OK, true));
    }

    /**
     * Spec 3: the static web app bundle, when this build ships one.
     *
     * <p>A path that escapes the bundle directory - {@code ../} in any of its
     * encodings - resolves outside it and is refused, so a request cannot read
     * the operator's disk through the one endpoint that maps a URL onto a file
     * name.
     */
    private void bundle(Request request, Response response, Callback callback)
            throws IOException {
        if (!isMethod(request, response, callback, METHOD_GET)) {
            return;
        }
        Path file = staticFile(path(request));
        if (file == null || !Files.isRegularFile(file)) {
            text(response, callback, HttpURLConnection.HTTP_NOT_FOUND,
                    "no web bundle is served by this server");
            return;
        }
        send(response, callback, HttpURLConnection.HTTP_OK, contentType(file),
                Files.readAllBytes(file));
    }

    private Path staticFile(String requestPath) {
        if (staticDir == null || !Files.isDirectory(staticDir)) {
            return null;
        }
        String relative = requestPath.length() <= 1 ? INDEX_FILE : requestPath.substring(1);
        try {
            Path resolved = staticDir.resolve(relative).normalize();
            if (!resolved.startsWith(staticDir)) {
                return null;
            }
            return Files.isDirectory(resolved) ? resolved.resolve(INDEX_FILE) : resolved;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    private String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        for (Map.Entry<String, String> type : STATIC_TYPES.entrySet()) {
            if (name.endsWith(type.getKey())) {
                return type.getValue();
            }
        }
        return TYPE_OCTETS;
    }

    /** True when the request used {@code method}; otherwise the client has
     *  already been answered {@code 405} and the handler must stop. */
    private boolean isMethod(Request request, Response response, Callback callback,
            String method) {
        if (method.equals(request.getMethod())) {
            return true;
        }
        notAllowed(request, response, callback);
        return false;
    }

    private void notAllowed(Request request, Response response, Callback callback) {
        error(response, callback, HttpURLConnection.HTTP_BAD_METHOD, ErrorCode.UNSUPPORTED,
                request.getMethod() + " is not served on " + path(request));
    }

    private void tooLarge(Response response, Callback callback, String detail) {
        error(response, callback, HttpURLConnection.HTTP_ENTITY_TOO_LARGE,
                ErrorCode.FILE_TOO_LARGE, detail);
    }

    /** The answer to a handler that threw.  A response already on its way out
     *  cannot be taken back, so that one is failed instead: the client sees the
     *  connection end, which is the only honest thing left to say. */
    private void fail(Response response, Callback callback) {
        if (response.isCommitted()) {
            callback.failed(new IllegalStateException(
                    "the request could not be served and the answer had already begun"));
            return;
        }
        error(response, callback, HttpURLConnection.HTTP_INTERNAL_ERROR, ErrorCode.INTERNAL,
                "the request could not be served");
    }

    /**
     * A refusal in the shape spec 4.2's vocabulary can be read from:
     * {@code {"ok":false,"error":{"code":...,"message":...}}}.
     *
     * <p>The bare {@code {"ok":false}} this used to send made every refusal look
     * the same on the wire - a client could not tell an over-cap upload from a
     * wrong method or a server fault by its payload, and {@code FILE_TOO_LARGE}
     * was a code the vocabulary declared but nothing ever sent.
     */
    private void error(Response response, Callback callback, int status, ErrorCode code,
            String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(NetFields.OK, false);
        body.put(NetFields.ERROR, Map.of(NetFields.CODE, code.name(),
                NetFields.MESSAGE, detail));
        json(response, callback, status, body);
    }

    private void json(Response response, Callback callback, int status, Object value) {
        try {
            send(response, callback, status, TYPE_JSON,
                    codec.write(value).getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException | RuntimeException e) {
            // Encoding our own answer cannot fail for anything the client did,
            // so there is nothing left to answer WITH: the request ends here.
            callback.failed(e);
        }
    }

    private void text(Response response, Callback callback, int status, String body) {
        send(response, callback, status, TYPE_TEXT, body.getBytes(StandardCharsets.UTF_8));
    }

    /** The one write of every answer - and the one place the request's callback
     *  is completed, which is what tells Jetty the exchange is over. */
    private void send(Response response, Callback callback, int status, String contentType,
            byte[] body) {
        response.setStatus(status);
        response.getHeaders().put(HEADER_CONTENT_TYPE, contentType);
        response.write(true, ByteBuffer.wrap(body), callback);
    }

    /** The body size the request declared, or 0 when it declared none. */
    private long declaredLength(Request request) {
        String declared = request.getHeaders().get(HEADER_CONTENT_LENGTH);
        if (declared == null) {
            return 0;
        }
        try {
            return Long.parseLong(declared.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The whole body, or null when it grew past {@code cap} - the caller
     *  answers 413.  Nothing over the cap is ever kept. */
    private byte[] readCapped(InputStream in, int cap) throws IOException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        int read = in.read(buffer);
        while (read >= 0) {
            if (collected.size() + read > cap) {
                return null;
            }
            collected.write(buffer, 0, read);
            read = in.read(buffer);
        }
        return collected.toByteArray();
    }

    /** The {@code {id}} of {@code /files/{id}}, or null when the path names no
     *  file at all. */
    private String lastPathSegment(Request request) {
        String path = path(request);
        int slash = path.lastIndexOf('/');
        String segment = slash < 0 ? path : path.substring(slash + 1);
        return segment.isEmpty() ? null : segment;
    }

    /** The request's path, never null - a request line that carried no path at
     *  all is a request for the bundle's root. */
    private String path(Request request) {
        String path = request.getHttpURI().getPath();
        return path == null ? "/" : path;
    }
}
