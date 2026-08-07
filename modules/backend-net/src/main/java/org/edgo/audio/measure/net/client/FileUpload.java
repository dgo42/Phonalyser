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
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;

import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetProto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * The client half of spec §3's {@code PUT /files}: hands a bench the bytes of a
 * file so its own generator lane can play them.
 *
 * <p><b>Why HTTP and not the session.</b>  The session carries commands and
 * state - small, ordered, latency-sensitive messages - and a fifty-megabyte body
 * pushed through it would stall every ping behind it for as long as the transfer
 * took, which the 2 s keepalive deadline reads as a dead connection.  The server
 * therefore serves uploads on its REST side, on the very same port, and this
 * speaks to that.
 *
 * <p><b>The size gate is the protocol's, not ours.</b>  {@link #put} refuses
 * anything over {@link NetProto#MAX_UPLOAD_BYTES} <em>before</em> opening a
 * socket - the same number the server enforces, read from the same constant, so
 * the two can never disagree.  Checking first is not an optimisation but a
 * courtesy: pushing 80 MB up a slow link to be told 413 at the end wastes the
 * operator's time to learn something known before the first byte moved.
 *
 * <p>Deliberately not a singleton and deliberately without state of its own: one
 * of these is built per connection, holding only what that connection is dialled
 * through.
 */
@Log4j2
@RequiredArgsConstructor
public final class FileUpload {

    /** Spec §3: the upload endpoint, raw body, no multipart. */
    private static final String FILES_PATH = "/files";
    /** Long enough for a large body over a slow link; the session's own 10 s
     *  budget is for commands, not for megabytes. */
    private static final int READ_TIMEOUT_MS = 120_000;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    /** Streams the body instead of buffering a second copy in the JDK. */
    private static final int CHUNK_BYTES = 64 * 1024;
    /** An answer is a small JSON object either way; more than this is not one. */
    private static final int MAX_BODY_BYTES = 4096;
    /** {@code 413}: {@link HttpURLConnection} has no constant for it. */
    private static final int HTTP_ENTITY_TOO_LARGE = 413;
    /** No status could be read at all - the link broke rather than refused. */
    private static final int NO_STATUS = -1;
    /** What a body whose type the caller could not name is declared as. */
    private static final String TYPE_OCTETS = "application/octet-stream";

    private final JsonCodec codec;
    /** The proxy this bench is reached through, or null for direct - the
     *  session's own, so the upload takes the route the commands take. */
    private final Proxy proxy;

    /**
     * Uploads {@code content} and answers the {@code fileId} the bench filed it
     * under.
     *
     * @param base     the bench's HTTP base, {@code http://host:port}
     * @param mimeType the file's audio MIME type, or null when the caller cannot
     *                 tell - then the body is declared opaque and the bench falls
     *                 back to sniffing it, exactly as it did before this was sent
     * @throws TooLargeException when {@code content} exceeds the protocol's
     *         upload limit, thrown WITHOUT contacting the server
     * @throws IOException on any transport failure, or when the bench refuses -
     *         the message carries the server's own words for the log
     */
    public String put(URI base, byte[] content, String mimeType) throws IOException {
        if (content.length > NetProto.MAX_UPLOAD_BYTES) {
            throw new TooLargeException(content.length, NetProto.MAX_UPLOAD_BYTES);
        }
        HttpURLConnection http = null;
        try {
            URL url = URI.create(base + FILES_PATH).toURL();
            http = (HttpURLConnection) (proxy == null
                    ? url.openConnection() : url.openConnection(proxy));
            http.setRequestMethod("PUT");
            http.setDoOutput(true);
            http.setConnectTimeout(CONNECT_TIMEOUT_MS);
            http.setReadTimeout(READ_TIMEOUT_MS);
            // Declared up front: the server checks Content-Length before it reads
            // a byte, which is how an over-size upload is refused cheaply at both
            // ends rather than after the whole body has crossed the wire.
            http.setFixedLengthStreamingMode(content.length);
            // What the file IS, so the bench picks its decoder from the type
            // rather than from what the first bytes resemble.  Octet-stream only
            // when the caller could not tell.
            http.setRequestProperty("Content-Type",
                    mimeType == null || mimeType.isBlank() ? TYPE_OCTETS : mimeType);
            try (OutputStream out = http.getOutputStream()) {
                for (int off = 0; off < content.length; off += CHUNK_BYTES) {
                    out.write(content, off, Math.min(CHUNK_BYTES, content.length - off));
                }
            } catch (IOException writeFailed) {
                // A server that refuses on Content-Length answers before it has
                // read the body - which breaks this pipe mid-write.  The REFUSAL
                // is still sitting there to be read, and it is the thing worth
                // telling the operator; the broken pipe is only how we learned of
                // it.  Falling through to the status below is therefore not
                // swallowing an error, it is reading the real one.
                int refused = statusOrNone(http);
                if (refused == HTTP_ENTITY_TOO_LARGE) {
                    throw storeFull(http);
                }
                throw writeFailed;
            }
            int status = http.getResponseCode();
            if (status == HTTP_ENTITY_TOO_LARGE) {
                throw storeFull(http);
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("the bench refused the upload: HTTP " + status
                        + " " + errorBody(http));
            }
            byte[] body;
            try (InputStream in = http.getInputStream()) {
                body = in.readNBytes(MAX_BODY_BYTES);
            }
            JsonNode answer = codec.read(new String(body, StandardCharsets.UTF_8),
                    JsonNode.class);
            String fileId = answer.path(NetFields.FILE_ID).asText(null);
            if (fileId == null || fileId.isEmpty()) {
                throw new IOException("the bench answered the upload without a "
                        + NetFields.FILE_ID);
            }
            if (log.isInfoEnabled()) {
                log.info("Uploaded {} bytes to {} as {}", content.length, base, fileId);
            }
            return fileId;
        } finally {
            if (http != null) {
                http.disconnect();
            }
        }
    }

    /** The status of a request whose body could not be written, or
     *  {@link #NO_STATUS} when the connection cannot answer that either - a
     *  genuinely broken link rather than a refusal. */
    private int statusOrNone(HttpURLConnection http) {
        try {
            return http.getResponseCode();
        } catch (IOException noAnswer) {
            return NO_STATUS;
        }
    }

    /** The bench's store is full (spec §3: it bounds the whole store, not only
     *  each upload).  A distinct exception because it is a distinct thing to tell
     *  the operator: nothing about THEIR file is wrong, and a smaller one will not
     *  help - something has to be dropped at the far end. */
    private StoreFullException storeFull(HttpURLConnection http) {
        return new StoreFullException("the bench refused the upload: its store is "
                + "full (HTTP " + HTTP_ENTITY_TOO_LARGE + " " + errorBody(http) + ")");
    }

    /** The refusal body, for the LOG line only - the operator is told in their
     *  own language by the caller, never in the server's. */
    private String errorBody(HttpURLConnection http) {
        try (InputStream err = http.getErrorStream()) {
            if (err == null) {
                return "";
            }
            return new String(err.readNBytes(MAX_BODY_BYTES), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * The bench accepted the file's size but has no room for it - spec §3's
     * whole-store bound.  Deliberately NOT {@link TooLargeException}: the two
     * refusals need different advice, and telling an operator their 3 MB file is
     * too big because somebody else filled the store would be a lie.
     */
    public static final class StoreFullException extends IOException {

        private static final long serialVersionUID = 1L;

        StoreFullException(String message) {
            super(message);
        }
    }

    /**
     * The file is bigger than the protocol allows - raised before any socket is
     * opened, and carrying both numbers so the caller can say them in the
     * operator's language.
     */
    public static final class TooLargeException extends IOException {

        private static final long serialVersionUID = 1L;

        @Getter
        private final long bytes;
        @Getter
        private final long limitBytes;

        TooLargeException(long bytes, long limitBytes) {
            super("the file is " + bytes + " bytes, the upload limit is "
                    + limitBytes + " bytes");
            this.bytes = bytes;
            this.limitBytes = limitBytes;
        }
    }
}
