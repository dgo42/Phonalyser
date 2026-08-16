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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.databind.JsonNode;

import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetMessage;
import org.edgo.audio.measure.net.proto.NetProto;
import org.edgo.audio.measure.sound.DeviceRef;
import org.edgo.audio.measure.sound.RemoteGenerator;
import org.edgo.audio.measure.wav.PcmFileLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Playing a file on a bench (spec §3 {@code PUT /files} + 4.5
 * {@code gen.playFile}): the client uploads once and the far end's own lane
 * renders it - there is no client-&gt;server PCM in this protocol version, so this
 * is upload-and-play and deliberately not streaming.
 *
 * <p>Proved against {@link MockBench}, which speaks the wire and serves the file
 * endpoint for real over HTTP: the bytes asserted here crossed a socket.
 */
class NetFilePlaybackTest {

    private static final String SERVER_NAME = "bench-files";
    private static final String CLIENT_NAME = "client";
    private static final String CLIENT_APP = "Phonalyser/test";
    private static final long AWAIT_MS = 5_000;
    private static final long SHUTDOWN_MS = 2_000;
    private static final int RATE_HZ = 48_000;
    private static final int BITS = 24;
    private static final double DITHER_BITS = 0.0;

    private final JsonCodec codec = new JsonCodec();
    private final List<NetConnection> connections = new ArrayList<>();

    private MockBench bench;
    private URI wsUri;

    @BeforeEach
    void startTheBench() {
        bench = new MockBench(codec, SERVER_NAME);
        wsUri = bench.listen(AWAIT_MS);
    }

    @AfterEach
    void stopTheBench() {
        for (NetConnection connection : connections) {
            connection.connClose(NetCloseReason.BYE);
        }
        bench.shutDown(SHUTDOWN_MS);
        // shutDown() is final and stops only the WebSocket side; without this the
        // file endpoint leaks a bound port and a dispatcher thread per test.
        bench.stopHttp();
    }

    @Test
    @DisplayName("the file is uploaded and gen.playFile names it, the handle and the loop flag")
    void theFileTravelsUpAndTheLaneIsToldToPlayIt() {
        NetDeviceManager manager = connectWithGenerator();
        byte[] content = wavLike(4096);

        manager.playFile(content, PcmFileLoader.MIME_WAV, true);

        // The bytes really crossed the HTTP endpoint.
        assertEquals(1, bench.uploadCount(), "exactly one upload");
        assertEquals(1, bench.uploadRequests(), "and exactly one request to make it");
        assertEquals(content.length, bench.lastDeclaredLength(),
                "the size is declared up front in Content-Length - what lets a real "
                        + "server refuse an over-size body before reading it");
        assertEquals(PcmFileLoader.MIME_WAV, bench.getLastContentType(),
                "and the file's own type, so the bench picks a decoder from what the "
                        + "file IS rather than from what its first bytes resemble");
        String fileId = bench.getLastGenerator().getPlayingFileId();
        assertNotNull(fileId, "the bench recorded which file it was told to play");
        assertArrayEquals(content, bench.uploaded(fileId),
                "the bench holds the bytes the client sent, unchanged");

        // And the command named the lane, that file and the loop flag.
        NetMessage played = bench.awaitNth(MessageType.GEN_PLAY_FILE, 1, AWAIT_MS);
        assertEquals(bench.getLastGenerator().getId(),
                played.optInt(NetFields.GEN_ID), "the open generator handle");
        assertEquals(fileId, played.optString(NetFields.FILE_ID), "the uploaded file's id");
        assertEquals(Boolean.TRUE, played.optBoolean(NetFields.LOOP), "loop travelled");
        assertTrue(bench.getLastGenerator().isFileLoop(), "and the bench read it as looping");

        manager.closeGenerator();
    }

    @Test
    @DisplayName("the bench's pushed file state is what the client reports, and finished clears playing")
    void theFileStateFollowsWhatTheBenchPushes() {
        NetDeviceManager manager = connectWithGenerator();

        manager.playFile(wavLike(1024), PcmFileLoader.MIME_WAV, false);

        // gen.playFile made the bench push playing=true.
        assertTrue(awaitFileState(manager, true, false),
                "the client reports the file playing once the bench says so");

        // The end-of-file edge a non-looping file reaches on its own.  Pushed as
        // playing=TRUE + finished=TRUE so only the `finished` clause can satisfy a
        // reader: a client that dropped it and leaned on "stopped playing" alone
        // would hang here.
        bench.pushFileState(bench.getLastGenerator(), true, true);
        assertTrue(awaitFileState(manager, true, true),
                "the finished edge reaches the client on its own");

        manager.closeGenerator();
        assertEquals(RemoteGenerator.FileState.IDLE, manager.fileState(),
                "closing the lane forgets the file with it");
    }

    @Test
    @DisplayName("the declared audio type travels with the upload, and its absence is octet-stream")
    void theFilesMimeTypeIsSentWithTheUpload() {
        NetDeviceManager manager = connectWithGenerator();

        manager.playFile(wavLike(512), PcmFileLoader.MIME_FLAC, false);
        assertEquals(PcmFileLoader.MIME_FLAC, bench.getLastContentType(),
                "a FLAC is declared a FLAC - the one format the old content sniff "
                        + "could miss behind an ID3 tag");

        manager.playFile(wavLike(512), PcmFileLoader.MIME_AIFF, false);
        assertEquals(PcmFileLoader.MIME_AIFF, bench.getLastContentType(),
                "and an AIFF an AIFF, which no sniff of the first bytes chose");

        manager.playFile(wavLike(512), null, false);
        assertEquals("application/octet-stream", bench.getLastContentType(),
                "a caller that cannot tell declares opaque bytes rather than "
                        + "guessing, which leaves the bench on its own sniff");

        manager.closeGenerator();
    }

    @Test
    @DisplayName("a session that dies mid-file finishes the file state and arms the error")
    void aBenchThatDiesWhileAFileIsPlayingIsReportedAndNotLeftPlaying() {
        NetDeviceManager manager = connectWithGenerator();
        manager.playFile(wavLike(1024), PcmFileLoader.MIME_WAV, false);
        assertTrue(awaitFileState(manager, true, false), "playing first");

        // The AP drops: the SESSION dies underneath a file that was playing.
        // Deliberately not a commanded teardown - that one closes the generator
        // first and owes no error, because the operator asked for it.  Here the
        // socket is simply gone and the lane is still nominally up.
        manager.getConnection().connClose(NetCloseReason.KEEPALIVE_TIMEOUT);
        manager.disconnect();

        assertFalse(manager.fileState().playing(),
                "nothing is playing anywhere, so nothing may be reported playing");
        assertTrue(manager.fileState().finished(),
                "and the state reads as ENDED, so a watcher polling it leaves");
        Throwable died = manager.takeFileErrorForReport();
        assertNotNull(died, "the death is claimable once, so the operator is told");
        assertNull(manager.takeFileErrorForReport(),
                "and only once - one death is one dialog");
    }

    @Test
    @DisplayName("a live loop toggle travels as gen.config{fileLoop}")
    void theLiveLoopToggleReachesTheBenchOverTheWire() {
        NetDeviceManager manager = connectWithGenerator();
        manager.playFile(wavLike(1024), PcmFileLoader.MIME_WAV, false);
        assertTrue(awaitFileState(manager, true, false), "playing first");
        assertFalse(bench.getLastGenerator().isFileLoop(), "started not looping");

        manager.setFileLoop(true);

        // The one line that builds the message, over a real socket - until now
        // the client half and the server half of the live toggle had never met.
        NetMessage config = bench.awaitNth(MessageType.GEN_CONFIG, 1, AWAIT_MS);
        assertEquals(bench.getLastGenerator().getId(), config.optInt(NetFields.GEN_ID),
                "the config names the lane it belongs to");
        assertEquals(Boolean.TRUE, config.optBoolean(NetFields.FILE_LOOP),
                "and carries the new flag");
        assertTrue(awaitTrue(() -> bench.getLastGenerator().isFileLoop()),
                "which the bench applies to the file that is playing");

        manager.setFileLoop(false);
        assertTrue(awaitTrue(() -> !bench.getLastGenerator().isFileLoop()),
                "and the other way too - unticking travels the same path");

        manager.closeGenerator();
    }

    @Test
    @DisplayName("stop sends gen.stopFile for the open lane")
    void stoppingCommandsTheBench() {
        NetDeviceManager manager = connectWithGenerator();
        manager.playFile(wavLike(1024), PcmFileLoader.MIME_WAV, false);
        assertTrue(awaitFileState(manager, true, false), "playing first");

        manager.stopFile();

        NetMessage stopped = bench.awaitNth(MessageType.GEN_STOP_FILE, 1, AWAIT_MS);
        assertEquals(bench.getLastGenerator().getId(), stopped.optInt(NetFields.GEN_ID),
                "the stop names the lane it belongs to");
        assertNull(bench.getLastGenerator().getPlayingFileId(),
                "and the bench dropped the file");

        manager.closeGenerator();
    }

    @Test
    @DisplayName("a file over the protocol limit is refused client-side, with nothing uploaded")
    void anOversizeFileIsRefusedBeforeAnythingIsSent() {
        NetDeviceManager manager = connectWithGenerator();
        byte[] tooBig = new byte[NetProto.MAX_UPLOAD_BYTES + 1];

        RemoteGenerator.FileTooLargeException refused = assertThrows(
                RemoteGenerator.FileTooLargeException.class,
                () -> manager.playFile(tooBig, PcmFileLoader.MIME_WAV, false));

        assertEquals(tooBig.length, refused.getBytes(), "the refusal carries the file's size");
        assertEquals(NetProto.MAX_UPLOAD_BYTES, refused.getLimitBytes(),
                "and the protocol's own limit, so the caller can say both");
        // The load-bearing one: no REQUEST reached the bench at all, so the gate
        // really fired before a socket was opened rather than after 50 MB had
        // crossed the wire and been turned away.
        assertEquals(0, bench.uploadRequests(), "no socket was opened");
        assertTrue(bench.receivedOf(MessageType.GEN_PLAY_FILE).isEmpty(),
                "and no play command was sent either");
        assertFalse(manager.fileState().playing(),
                "the client is left reporting no file");

        manager.closeGenerator();
    }

    @Test
    @DisplayName("a bench that refuses the upload leaves the client with no file playing")
    void anUploadTheBenchRefusesSurfacesAndLeavesNothingPlaying() {
        NetDeviceManager manager = connectWithGenerator();
        bench.setRefuseUploads(true);          // the "store is full" 413 of spec §3

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> manager.playFile(wavLike(2048), PcmFileLoader.MIME_WAV, false));

        // NOT the too-large type: this file's size was fine, the bench simply had
        // no room.  Mapping a store-full 413 onto "your file is too big" would
        // send the operator off shortening a file that was never the problem.
        assertFalse(refused instanceof RemoteGenerator.FileTooLargeException,
                "a full store is not an over-size file: " + refused);
        assertEquals(1, bench.uploadRequests(),
                "the body really was offered - this refusal comes from the bench");
        assertEquals(0, bench.uploadCount(), "and the bench filed nothing");
        assertTrue(bench.receivedOf(MessageType.GEN_PLAY_FILE).isEmpty(),
                "a failed upload is never followed by a play command");
        assertFalse(manager.fileState().playing(), "and nothing is reported playing");

        manager.closeGenerator();
    }

    // -------------------------------------------------------------------------
    // Harness
    // -------------------------------------------------------------------------

    /** A connected manager with a generator lane already open - every file
     *  command needs one, exactly as the protocol does. */
    private NetDeviceManager connectWithGenerator() {
        NetConnection connection = new NetConnection(wsUri, CLIENT_NAME, CLIENT_APP,
                codec, new FakeTicker());
        connections.add(connection);
        connection.open(AWAIT_MS);
        NetDeviceManager manager = new NetDeviceManager();
        manager.connect(connection, select(connection, servedBackend(connection)));
        // The bench cannot serve HTTP on its WebSocket port; production does, so
        // only the test says where the file endpoint really is.
        manager.setUploadBase(bench.httpBase());
        DeviceRef output = manager.listOutputDevices().get(0);
        manager.openGenerator(output, RATE_HZ, BITS, DITHER_BITS, OutputChannels.BOTH);
        return manager;
    }

    /** Spec 4.3's {@code backend.select}: the session commits to one backend and
     *  is answered with that backend's whole entry. */
    private JsonNode select(NetConnection connection, String backend) {
        NetMessage answer = connection.request(
                connection.newRequest(MessageType.BACKEND_SELECT)
                        .put(NetFields.BACKEND, backend));
        assertTrue(answer.isOk(), "the bench refused backend.select: " + answer);
        return answer.getData();
    }

    /** The first backend of {@code devices.list} that has any device at all. */
    private String servedBackend(NetConnection connection) {
        NetMessage list = connection.request(
                connection.newRequest(MessageType.DEVICES_LIST));
        assertTrue(list.isOk());
        for (JsonNode backend : list.getData().path(NetFields.BACKENDS)) {
            if (!backend.path(NetFields.DEVICES).isEmpty()) {
                return backend.path(NetFields.BACKEND).asText();
            }
        }
        throw new IllegalStateException("this bench serves no device at all: "
                + list.getData());
    }

    /** Waits for {@code condition}, which a reader-thread push has to satisfy. */
    private boolean awaitTrue(BooleanSupplier condition) {
        long deadline = System.nanoTime() + AWAIT_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** Waits for the pushed file state to reach {@code playing}/{@code finished} -
     *  the state arrives on the reader thread, so it is never instant. */
    private boolean awaitFileState(NetDeviceManager manager, boolean playing, boolean finished) {
        long deadline = System.nanoTime() + AWAIT_MS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            RemoteGenerator.FileState state = manager.fileState();
            if (state.playing() == playing && state.finished() == finished) {
                return true;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** Bytes that stand in for an encoded file.  The client never decodes them -
     *  it uploads them and the bench decodes - so their only job is to be a
     *  recognisable pattern that must arrive unchanged. */
    private byte[] wavLike(int length) {
        byte[] content = new byte[length];
        for (int i = 0; i < length; i++) {
            content[i] = (byte) (i * 31 + 7);
        }
        return content;
    }
}
