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
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.edgo.audio.measure.dsp.FftBinSnap;
import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.net.proto.BinaryFrame;
import org.edgo.audio.measure.net.proto.ErrorCode;
import org.edgo.audio.measure.net.proto.FrameType;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetError;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetMessage;
import org.edgo.audio.measure.net.proto.NetProto;
import org.java_websocket.WebSocket;
import org.java_websocket.framing.CloseFrame;

import lombok.Getter;
import lombok.Setter;

/**
 * A whole bench on the far end of the socket: the catalogue of spec 4.3, its
 * exclusive locks, the capture streams of spec 4.4 with the binary frames of
 * spec 5, and the remote generator of spec 4.5 - all of it spoken as JSON and
 * frames over a real WebSocket, and none of it the server's Java classes.
 *
 * <p><b>Why this is the right far end for a CLIENT test.</b>  This module is the
 * client half of the bridge; its dependency is the wire format and nothing else,
 * exactly as a database client depends on its protocol and not on the database.
 * So a bench that speaks the wire is what the client can be proved against -
 * and the proof is not circular, because nothing here reads the client's code:
 * every answer is built from the spec's own field names, and what the tests
 * assert is what the CLIENT does with them.
 *
 * <p><b>What it can do that a real bench cannot.</b>  A GAP, a jumped packet
 * counter and a marker that lands exactly between two named batches are all
 * frames a healthy server never emits - the first needs a client slow enough to
 * overrun a queue, the second is impossible over intact TCP.  Here they are
 * ordinary methods, and they cross a REAL socket into the client's own demux
 * rather than being handed to it through a back door.
 *
 * <p><b>What it deliberately does NOT do.</b>  It renders no audio and holds no
 * device: the bytes a stream carries are the test's, and the generator is a
 * record of what it was commanded.  Anything that is the SERVER's own decision -
 * when to drop audio, how to render a chirp, which full scale a lane opens at -
 * is not proved here and is not pretended to be.
 */
final class MockBench extends WirePeer {

    /** The names a device ref carries; every request naming a device is
     *  validated against them (spec 4.3), so a client that garbles one is
     *  caught here and not by a mystery further down. */
    static final String FIRST_INPUT = "Bench input";
    /** A second unit, so a test can contend one device and still see the other. */
    static final String SECOND_INPUT = "Bench input B";
    static final String OUTPUT = "Bench output";
    static final int RATE_48K = 48_000;
    static final int RATE_96K = 96_000;
    static final int BITS_24 = 24;
    /** The wire name of the backend this bench serves under - a server's own
     *  enum name, which the client keeps as raw text and never resolves against
     *  its own backends (a bench across the room is not this machine's USB). */
    static final String REMOTE_BACKEND = AudioBackendType.JAVASOUND.name();

    private static final String DISPLAY_NAME = "Java Sound";
    private static final String DESCRIPTION = "Loopback bench";
    private static final String VENDOR = "Phonalyser tests";
    /** The pipeline is stereo downstream, and spec 4.4 writes the channel count
     *  as the constant 2. */
    private static final int CHANNELS = 2;
    /** No card bound, and no full scale stored: 0 is not a calibration, so it is
     *  how "this bench knows nothing about that device" is spelled here. */
    private static final double NO_FULL_SCALE = 0.0;

    private final JsonNodeFactory json = JsonNodeFactory.instance;
    /** The bench's devices, fixed for its lifetime: two inputs and one output,
     *  which is what lets a test contend one device and still measure. */
    private final List<BenchDevice> devices = List.of(
            new BenchDevice(0, FIRST_INPUT, true),
            new BenchDevice(1, SECOND_INPUT, true),
            new BenchDevice(0, OUTPUT, false));
    /** The devices this bench has LOST (see {@link #detach}).  They stay in the
     *  list above so a test can still name them; no catalogue mentions them
     *  again, which is all a client ever sees of a detachment. */
    private final Set<BenchDevice> detached = ConcurrentHashMap.newKeySet();
    private final Map<WebSocket, Session> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger lastCaptureId = new AtomicInteger();
    private final AtomicInteger lastGenId = new AtomicInteger();

    /** The most recently opened capture stream - what a test feeds bytes into.
     *  Volatile: opened on a reader thread, read from the test's. */
    @Getter
    private volatile Capture lastCapture;
    /** The most recently opened generator lane - what a test asks what it was
     *  commanded.  Volatile for the same reason. */
    @Getter
    private volatile Generator lastGenerator;
    /** Whether this bench still answers.  Set so a test can be the bench that
     *  keeps the socket up and goes quiet, which is the only way to reach spec
     *  4.1's four-unanswered-pings death from the client's side. */
    @Setter
    private volatile boolean deaf;
    /** The rate {@code capture.open} GRANTS, or 0 to grant what was asked.  Spec
     *  4.4 lets the bench have the last word ("the granted rate may differ
     *  (device reality), client re-pins"), and a bench that always granted the
     *  request could never show whether the client re-pins at all. */
    @Setter
    private volatile int grantRate;
    /** Whether every {@code capture.attach} is refused (spec 4.7) - the stale
     *  handle and the capture that is already attached reach the client the same
     *  way, and what a test is asking is what the client DOES with a refusal. */
    @Setter
    private volatile boolean refuseAttach;

    /** This bench's own loopback host - {@link WirePeer}'s is private, and the
     *  HTTP side binds the same interface the socket does. */
    private static final String HTTP_HOST = "127.0.0.1";
    /** Spec §3's upload endpoint. */
    private static final String FILES_PATH = "/files";
    /** {@code 413}: no constant for it in {@link HttpURLConnection}. */
    private static final int HTTP_ENTITY_TOO_LARGE = 413;

    /** The files this bench has been handed over {@code PUT /files}, keyed by the
     *  {@code fileId} it answered with - what a test asserts the upload really
     *  carried. */
    private final Map<String, byte[]> uploads = new ConcurrentHashMap<>();
    private final AtomicInteger lastFileId = new AtomicInteger();
    /** Every upload REQUEST that arrived, accepted or refused. */
    private final AtomicInteger uploadRequests = new AtomicInteger();
    /** The declared body size of the last request, or -1 when none was sent. */
    private volatile long lastDeclaredLength = -1;
    /** The {@code Content-Type} of the last upload request, or null. */
    @Getter
    private volatile String lastContentType;
    /** Milliseconds to stall an upload before reading it - a slow link. */
    @Setter
    private volatile long uploadDelayMs;
    /** The HTTP side of spec §3, on its own port.  A real server multiplexes it
     *  onto the session's port; this bench cannot (its WebSocket library owns
     *  that socket), so a test points the client at {@link #httpBase()}. */
    private HttpServer http;
    /** When set, {@code PUT /files} answers 413 instead of accepting - the
     *  "store already full" refusal of spec §3, which no client-side size check
     *  can predict. */
    @Setter
    private volatile boolean refuseUploads;

    MockBench(JsonCodec codec, String serverName) {
        super(codec, serverName, List.of(NetFields.CAP_GEN, NetFields.CAP_FILES));
    }

    // -------------------------------------------------------------------------
    // The HTTP side: PUT /files (spec §3)
    // -------------------------------------------------------------------------

    /** Starts the file endpoint and answers its base URL.  Separate from
     *  {@link #listen} because only the file tests need it. */
    URI httpBase() {
        if (http == null) {
            try {
                http = HttpServer.create(new InetSocketAddress(HTTP_HOST, 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException("the mock bench could not open its HTTP side", e);
            }
            http.createContext(FILES_PATH, this::serveFiles);
            http.start();
        }
        return URI.create("http://" + HTTP_HOST + ":" + http.getAddress().getPort());
    }

    /** The bytes filed under {@code fileId}, or null - what a test reads back to
     *  prove the upload arrived intact. */
    byte[] uploaded(String fileId) {
        return uploads.get(fileId);
    }

    /** How many uploads this bench has accepted. */
    int uploadCount() {
        return uploads.size();
    }

    /** How many upload REQUESTS reached this bench - accepted or refused.  The
     *  only number that can tell "no socket was opened" from "a body crossed the
     *  wire and was turned away". */
    int uploadRequests() {
        return uploadRequests.get();
    }

    /** The {@code Content-Length} of the last upload request, or -1 - proves the
     *  client declares the size up front, which is what lets a real server refuse
     *  an over-size body before reading it. */
    long lastDeclaredLength() {
        return lastDeclaredLength;
    }

    /** Stops the file endpoint.  {@link WirePeer#shutDown} is final and stops only
     *  the WebSocket side, so a test that used {@link #httpBase()} must call this
     *  or leak a bound port and a dispatcher thread per test. */
    void stopHttp() {
        HttpServer running = http;
        http = null;
        if (running != null) {
            running.stop(0);
        }
    }

    private void serveFiles(HttpExchange exchange) throws IOException {
        try (exchange) {
            // Counted BEFORE anything else: a test that claims nothing was
            // uploaded has to be able to see a socket that was opened and a body
            // that was pushed and then refused - which the accepted-file map
            // cannot show, because a refusal files nothing.
            uploadRequests.incrementAndGet();
            if (!"PUT".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(HttpURLConnection.HTTP_BAD_METHOD, -1);
                return;
            }
            String declared = exchange.getRequestHeaders().getFirst("Content-Length");
            if (declared != null) {
                lastDeclaredLength = Long.parseLong(declared);
            }
            lastContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (uploadDelayMs > 0) {
                // A slow link, so a test can press Stop while the bytes are in
                // flight - the only way to prove the upload is not on the UI
                // thread and that a cancelled transfer never starts playing.
                try {
                    Thread.sleep(uploadDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (refuseUploads || body.length > NetProto.MAX_UPLOAD_BYTES) {
                byte[] refusal = ("{\"ok\":false,\"error\":{\"code\":\"FILE_TOO_LARGE\"}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(HTTP_ENTITY_TOO_LARGE, refusal.length);
                exchange.getResponseBody().write(refusal);
                return;
            }
            String fileId = "f-" + lastFileId.incrementAndGet();
            uploads.put(fileId, body);
            byte[] answer = ("{\"" + NetFields.FILE_ID + "\":\"" + fileId + "\",\""
                    + NetFields.BYTES + "\":" + body.length + "}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, answer.length);
            exchange.getResponseBody().write(answer);
        }
    }

    // -------------------------------------------------------------------------
    // What a test sets up and reads back
    // -------------------------------------------------------------------------

    /** Seeds what this bench's own card store holds for one of its devices -
     *  spec 4.3 v1.1: the calibration lives where the device is plugged in, so
     *  it is there before any client connects. */
    void storeCalibration(String name, boolean input, double fsLeft, double fsRight) {
        BenchDevice device = byName(name, input);
        device.fsLeft = fsLeft;
        device.fsRight = fsRight;
    }

    /** What this bench stores for a device now - how a test asks whether a
     *  {@code device.setCalibration} really reached the far end. */
    double storedLeft(String name, boolean input) {
        return byName(name, input).fsLeft;
    }

    /** This bench's card for the device says the full scales are the DEVICE's own
     *  - what an analyzer that reads its own EEPROM looks like from here. */
    void deviceOwnedCalibration(String name, boolean input) {
        byName(name, input).calFromDevice = true;
    }

    /** The bench's calibration for a device MOVES and it says so the way spec 4.3
     *  says everything else - the full {@code devices.list} payload.  This is what
     *  ANOTHER client's calibrate, card binding or range change looks like from
     *  here, and what a connected client has to react to rather than merely
     *  cache. */
    synchronized void recalibrate(String name, boolean input, double fsLeft, double fsRight) {
        storeCalibration(name, input, fsLeft, fsRight);
        broadcastDevices();
    }

    /**
     * The bench LOSES a device - the analyzer was detached, the server discarded
     * its session - and says so the way spec 4.3 says everything else: the full
     * {@code devices.list} payload, one device shorter.
     *
     * <p>Deliberately not a hand-built payload in the test: every other field of
     * every remaining device stays exactly as it was, so what the client is being
     * asked is only whether it noticed the one that left.
     */
    synchronized void detach(String name, boolean input) {
        detached.add(byName(name, input));
        broadcastDevices();
    }

    /** Spec 4.3's {@code ev.device.error}: a lane on the bench died. */
    void pushDeviceError(String direction, String detail) {
        push(new NetMessage(MessageType.EV_DEVICE_ERROR)
                .put(NetFields.DIRECTION, direction)
                .put(NetFields.DETAIL, detail));
    }

    /** Pushes one {@code ev.devices.changed} carrying an arbitrary payload - how
     *  a test asks what the client does with a catalogue that no longer names
     *  its backend, or names none at all. */
    void pushDevicesChanged(JsonNode backends) {
        push(new NetMessage(MessageType.EV_DEVICES_CHANGED)
                .put(NetFields.BACKENDS, backends));
    }

    /** How many sessions this bench is serving - the client count a test needs
     *  before it can talk about "the other client". */
    int sessions() {
        return sessions.size();
    }

    /** Whether {@code name} is locked by anybody at all. */
    boolean isLocked(String name, boolean input) {
        return byName(name, input).holder != null;
    }

    // -------------------------------------------------------------------------
    // The conversation
    // -------------------------------------------------------------------------

    /**
     * Spec 4.1: the client's own name arrives with the handshake, and it is what
     * a {@code DEVICE_LOCKED} refusal has to quote - "in use by Developer's
     * laptop" is actionable, "cannot open device" is not.
     *
     * <p>And this is where the session BEGINS, not at the upgrade: spec 4 leaves
     * a fresh connection's plane to its first message, so a socket that has not
     * said {@code hello} is not a session and must not be counted as one - a
     * bench that opened a session per socket would report a client's data
     * connections (spec 4.7) as extra clients.
     */
    @Override
    protected void greeted(WebSocket conn, NetMessage hello) {
        Session session = new Session(conn);
        session.clientName = hello.optString(NetFields.NAME);
        sessions.put(conn, session);
    }

    /** The session died with the socket: spec 4.1 tears down everything it held,
     *  which is what makes "the locks really are free afterwards" askable.
     *
     *  <p>A DATA connection closing is not that (spec 4.7): it is one capture's
     *  socket going away, and this bench simply forgets it - the capture and the
     *  session it belongs to are the client's to close. */
    @Override
    protected void closed(WebSocket conn) {
        for (Session open : sessions.values()) {
            open.detach(conn);
        }
        Session session = sessions.remove(conn);
        if (session != null) {
            session.tearDown();
            broadcastDevices();
        }
    }

    /**
     * Spec 4.7's {@code capture.attach}: the connection this arrived on becomes
     * the data connection of the named capture, and every frame of that stream
     * goes out on it from now on.
     *
     * <p>The handle is resolved through the {@code clientId} the {@code hello}
     * answered, so an attach that names a session this bench never opened - or
     * one that has since gone - is refused exactly as the spec says, and the
     * client has to surface it as the capture's own failure.
     */
    @Override
    protected synchronized NetMessage attached(WebSocket conn, NetMessage attach) {
        Integer id = attach.getId();
        if (id == null) {
            return null;
        }
        if (refuseAttach) {
            return new NetMessage(id, new NetError(ErrorCode.BAD_REQUEST,
                    "this bench refuses the attach"));
        }
        Session session = sessions.get(controlOf(attach.optString(NetFields.CLIENT_ID)));
        if (session == null) {
            return new NetMessage(id, new NetError(ErrorCode.BAD_REQUEST,
                    "capture.attach names no session on this bench"));
        }
        Capture capture = session.captures.get(attach.optInt(NetFields.CAPTURE_ID));
        if (capture == null) {
            return new NetMessage(id, new NetError(ErrorCode.BAD_REQUEST,
                    "no such capture on this session"));
        }
        if (capture.data != null) {
            return new NetMessage(id, new NetError(ErrorCode.BAD_REQUEST,
                    "capture " + capture.id + " already has a data connection"));
        }
        capture.data = conn;
        return new NetMessage(id);
    }

    @Override
    protected NetMessage answer(WebSocket conn, NetMessage request) {
        Integer id = request.getId();
        if (id == null) {
            return null;
        }
        Session session = sessions.get(conn);
        if (session == null) {
            return null;
        }
        switch (request.getType()) {
            case PING:
                // Deaf on purpose: see the setter.
                return deaf ? null : new NetMessage(id);
            case BYE:
                return new NetMessage(id);
            case BACKEND_LIST:
                return new NetMessage(id, backendList());
            case BACKEND_SELECT:
                return new NetMessage(id, backendEntry());
            case DEVICES_LIST:
                return new NetMessage(id, devicesList());
            case DEVICE_ACQUIRE:
                return acquire(session, id, request);
            case DEVICE_RELEASE:
                return releaseDevice(session, id, request);
            case DEVICE_SET_CALIBRATION:
                return setCalibration(session, id, request);
            case CAPTURE_OPEN:
                return captureOpen(session, id, request);
            case CAPTURE_START:
                return captureCommand(session, id, request, true);
            case CAPTURE_STOP:
                return captureCommand(session, id, request, false);
            case CAPTURE_CLOSE:
                return captureClose(session, id, request);
            case GEN_OPEN:
                return genOpen(session, id, request);
            case GEN_CONFIG:
                return genConfig(session, id, request);
            case GEN_FFT_GRID:
                return genGrid(session, id, request);
            case GEN_TRIM:
                return genTrim(session, id, request);
            case GEN_START:
                return genRun(session, id, request, true);
            case GEN_STOP:
                return genRun(session, id, request, false);
            case GEN_CLOSE:
                return genClose(session, id, request);
            case GEN_PLAY_FILE:
                return genPlayFile(session, id, request);
            case GEN_STOP_FILE:
                return genStopFile(session, id, request);
            default:
                return new NetMessage(id, new NetError(ErrorCode.UNSUPPORTED,
                        "this bench does not serve '" + request.getT() + "'"));
        }
    }

    // -------------------------------------------------------------------------
    // Devices and locks - spec 4.3
    // -------------------------------------------------------------------------

    private synchronized NetMessage acquire(Session session, int id, NetMessage request) {
        BenchDevice device = named(request);
        if (device == null) {
            return stale(id, request);
        }
        if (device.holder != null && device.holder != session) {
            return new NetMessage(id, new NetError(ErrorCode.DEVICE_LOCKED,
                    "the device is in use", device.holder.clientName));
        }
        // A repeat acquire by the SAME session succeeds and changes nothing -
        // there is nothing on the wire that could tell the two apart, which is
        // exactly why the client has to remember what it holds.
        boolean fresh = device.holder == null;
        device.holder = session;
        if (fresh) {
            broadcastDevices();
        }
        return new NetMessage(id);
    }

    private synchronized NetMessage releaseDevice(Session session, int id,
            NetMessage request) {
        BenchDevice device = named(request);
        if (device == null) {
            return stale(id, request);
        }
        if (device.holder == session) {
            device.holder = null;
            // Spec 4.3: a release "also closes any open stream/generator on it".
            // Kept faithfully because it is the whole reason the client must not
            // release a lock it was only borrowing.
            session.closeEverythingOn(device);
            broadcastDevices();
        }
        return new NetMessage(id);
    }

    private synchronized NetMessage setCalibration(Session session, int id,
            NetMessage request) {
        BenchDevice device = named(request);
        if (device == null) {
            return stale(id, request);
        }
        if (device.holder != session) {
            return new NetMessage(id, new NetError(ErrorCode.NOT_LOCKED,
                    "a calibration may only be written by the client holding the device"));
        }
        device.fsLeft = request.optDouble(NetFields.FS_RMS_LEFT);
        device.fsRight = request.optDouble(NetFields.FS_RMS_RIGHT);
        broadcastDevices();
        return new NetMessage(id);
    }

    /** The device a request names, or null when the four ref fields of spec 4.3
     *  do not describe one - which is how a client that garbles a ref is caught
     *  on the wire instead of somewhere downstream. */
    private BenchDevice named(NetMessage request) {
        Integer index = request.optInt(NetFields.INDEX);
        Boolean input = request.optBoolean(NetFields.INPUT);
        String name = request.optString(NetFields.NAME);
        String backend = request.optString(NetFields.BACKEND);
        if (index == null || input == null || !REMOTE_BACKEND.equals(backend)) {
            return null;
        }
        for (BenchDevice device : devices) {
            if (device.index == index && device.input == input) {
                return device.name.equals(name) ? device : null;
            }
        }
        return null;
    }

    private NetMessage stale(int id, NetMessage request) {
        return new NetMessage(id, new NetError(ErrorCode.DEVICE_STALE,
                "no such device on this bench: " + request));
    }

    private BenchDevice byName(String name, boolean input) {
        for (BenchDevice device : devices) {
            if (device.name.equals(name) && device.input == input) {
                return device;
            }
        }
        throw new IllegalArgumentException("this bench has no " + (input ? "input" : "output")
                + " called '" + name + "'");
    }

    // -------------------------------------------------------------------------
    // Capture streaming - spec 4.4
    // -------------------------------------------------------------------------

    private synchronized NetMessage captureOpen(Session session, int id,
            NetMessage request) {
        BenchDevice device = named(request);
        if (device == null) {
            return stale(id, request);
        }
        if (device.holder != session) {
            return new NetMessage(id, new NetError(ErrorCode.NOT_LOCKED,
                    "spec 4.4: a capture requires the input lock"));
        }
        int asked = request.optInt(NetFields.RATE);
        int bits = request.optInt(NetFields.BITS);
        int granted = grantRate == 0 ? asked : grantRate;
        Capture capture = new Capture(lastCaptureId.incrementAndGet(), device);
        session.captures.put(capture.id, capture);
        lastCapture = capture;
        ObjectNode data = json.objectNode();
        data.put(NetFields.CAPTURE_ID, capture.id);
        data.put(NetFields.RATE, granted);
        data.put(NetFields.BITS, bits);
        data.put(NetFields.CHANNELS, CHANNELS);
        return new NetMessage(id, data);
    }

    private NetMessage captureCommand(Session session, int id, NetMessage request,
            boolean start) {
        Capture capture = session.captures.get(request.optInt(NetFields.CAPTURE_ID));
        if (capture == null) {
            return new NetMessage(id, new NetError(ErrorCode.BAD_REQUEST,
                    "no such capture stream on this session"));
        }
        if (start && capture.data == null) {
            return new NetMessage(id, new NetError(ErrorCode.NOT_ATTACHED,
                    "spec 4.4: capture " + capture.id + " has no data connection yet"));
        }
        capture.started = start;
        return new NetMessage(id);
    }

    /** Spec 4.4 and 4.7: the capture ends and its data connection with it -
     *  closed from HERE, which is the order a client has to survive (the socket
     *  can die before the answer to the close arrives on the other one). */
    private NetMessage captureClose(Session session, int id, NetMessage request) {
        Capture capture = session.captures.remove(request.optInt(NetFields.CAPTURE_ID));
        if (capture != null) {
            capture.closed = true;
            WebSocket stream = capture.data;
            capture.data = null;
            if (stream != null) {
                stream.close();
            }
        }
        return new NetMessage(id);
    }

    // -------------------------------------------------------------------------
    // The remote generator - spec 4.5
    // -------------------------------------------------------------------------

    private synchronized NetMessage genOpen(Session session, int id, NetMessage request) {
        BenchDevice device = named(request);
        if (device == null) {
            return stale(id, request);
        }
        if (device.holder != session) {
            return new NetMessage(id, new NetError(ErrorCode.NOT_LOCKED,
                    "spec 4.5: all gen.* require the output-device lock"));
        }
        Generator generator = new Generator(lastGenId.incrementAndGet(), session, device,
                request.optInt(NetFields.RATE), request.optInt(NetFields.BITS),
                request.optDouble(NetFields.DITHER_BITS),
                request.optString(NetFields.OUTPUT_CHANNELS));
        session.generators.put(generator.id, generator);
        lastGenerator = generator;
        ObjectNode data = json.objectNode();
        data.put(NetFields.GEN_ID, generator.id);
        data.put(NetFields.RATE, generator.rate);
        return new NetMessage(id, data);
    }

    private NetMessage genConfig(Session session, int id, NetMessage request) {
        Generator generator = generatorOf(session, request);
        if (generator == null) {
            return new NetMessage(id, new NetError(ErrorCode.BAD_REQUEST,
                    "no such generator on this session"));
        }
        generator.apply(request);
        // Spec 4.5 v1.1: the live repeat flag of the file that is playing.  The
        // generic apply() above records it in the config block like every other
        // field; routing it to the lane's own flag is what lets a test see the
        // value the client actually put on the wire.
        Boolean fileLoop = request.optBoolean(NetFields.FILE_LOOP);
        if (fileLoop != null) {
            generator.fileLoop = fileLoop;
        }
        return new NetMessage(id);
    }

    private NetMessage genGrid(Session session, int id, NetMessage request) {
        Generator generator = generatorOf(session, request);
        if (generator == null) {
            return new NetMessage(id, new NetError(ErrorCode.BAD_REQUEST,
                    "no such generator on this session"));
        }
        generator.fftSize = request.optInt(NetFields.FFT_SIZE);
        generator.snapEnabled = Boolean.TRUE.equals(
                request.optBoolean(NetFields.SNAP_ENABLED));
        pushGenState(generator);
        return new NetMessage(id);
    }

    private NetMessage genTrim(Session session, int id, NetMessage request) {
        Generator generator = generatorOf(session, request);
        if (generator == null) {
            return new NetMessage(id, new NetError(ErrorCode.BAD_REQUEST,
                    "no such generator on this session"));
        }
        // Spec 4.5: the trim is an ABSOLUTE corrected frequency, so what comes
        // back as emitHz is exactly the value sent.
        generator.trimHz = request.optDouble(NetFields.HZ);
        pushGenState(generator);
        return new NetMessage(id);
    }

    private NetMessage genRun(Session session, int id, NetMessage request, boolean start) {
        Generator generator = generatorOf(session, request);
        if (generator == null) {
            return new NetMessage(id, new NetError(ErrorCode.BAD_REQUEST,
                    "no such generator on this session"));
        }
        generator.running = start;
        if (start) {
            generator.configAtStart = generator.config.deepCopy();
            markSweepStart(session, generator);
        }
        pushGenState(generator);
        return new NetMessage(id);
    }

    private NetMessage genClose(Session session, int id, NetMessage request) {
        Generator generator = session.generators.remove(request.optInt(NetFields.GEN_ID));
        if (generator != null) {
            generator.running = false;
            generator.closed = true;
        }
        return new NetMessage(id);
    }

    /**
     * Spec 4.5's {@code gen.playFile}: the lane starts rendering a file already
     * uploaded over {@code PUT /files}, and the state push is what tells the
     * client so - nothing about it comes back in the answer.
     */
    private NetMessage genPlayFile(Session session, int id, NetMessage request) {
        Generator generator = generatorOf(session, request);
        if (generator == null) {
            return new NetMessage(id, new NetError(ErrorCode.BAD_REQUEST,
                    "no such generator on this session"));
        }
        String fileId = request.optString(NetFields.FILE_ID);
        if (fileId == null || !uploads.containsKey(fileId)) {
            return new NetMessage(id, new NetError(ErrorCode.NO_SUCH_FILE,
                    "this bench holds no file " + fileId));
        }
        generator.playingFileId = fileId;
        generator.fileLoop = Boolean.TRUE.equals(request.optBoolean(NetFields.LOOP));
        pushFileState(generator, true, false);
        return new NetMessage(id);
    }

    private NetMessage genStopFile(Session session, int id, NetMessage request) {
        Generator generator = generatorOf(session, request);
        if (generator == null) {
            return new NetMessage(id, new NetError(ErrorCode.BAD_REQUEST,
                    "no such generator on this session"));
        }
        generator.playingFileId = null;
        pushFileState(generator, false, false);
        return new NetMessage(id);
    }

    /** Pushes the {@code file} block of spec 4.5's {@code ev.gen.state} - the one
     *  way a client learns a file is playing, or has run out.  Package-private so
     *  a test can drive the end-of-file edge a real bench reaches on its own. */
    void pushFileState(Generator generator, boolean playing, boolean finished) {
        ObjectNode file = JsonNodeFactory.instance.objectNode();
        file.put(NetFields.PLAYING, playing);
        file.put(NetFields.FINISHED, finished);
        file.put(NetFields.POS_SAMPLES, 0);
        NetMessage event = new NetMessage(MessageType.EV_GEN_STATE)
                .put(NetFields.GEN_ID, generator.id)
                .put(NetFields.RUNNING, generator.running)
                .put(NetFields.EMIT_HZ, 0.0)
                .put(NetFields.EMIT2_HZ, 0.0)
                .put(NetFields.FILE, file);
        send(generator.session.conn, event);
    }

    private Generator generatorOf(Session session, NetMessage request) {
        Integer handle = request.optInt(NetFields.GEN_ID);
        return handle == null ? null : session.generators.get(handle);
    }

    /**
     * Spec 4.5: starting a SWEEP injects a {@code sweepStart} marker into this
     * connection's open capture streams.
     *
     * <p>It is sent after everything already handed to that stream, on the one
     * socket the audio itself travels - which is what makes the boundary the
     * client reports checkable at all.  Whether the SERVER manages to drain its
     * own queue first is the server's promise and is not proved here.
     */
    private void markSweepStart(Session session, Generator generator) {
        GenSignalForm form = generator.form();
        if (form != GenSignalForm.LOG_SWEEP && form != GenSignalForm.LINEAR_SWEEP) {
            return;
        }
        for (Capture capture : session.captures.values()) {
            capture.mark(BinaryFrame.MARKER_SWEEP_START);
        }
    }

    /** Spec 4.5's {@code ev.gen.state}: what the bench says it is emitting, which
     *  is the only thing a client may believe. */
    private void pushGenState(Generator generator) {
        NetMessage event = new NetMessage(MessageType.EV_GEN_STATE)
                .put(NetFields.GEN_ID, generator.id)
                .put(NetFields.RUNNING, generator.running)
                .put(NetFields.NOMINAL_HZ, generator.nominalHz())
                .put(NetFields.EMIT_HZ, generator.running ? generator.emitHz() : 0.0)
                .put(NetFields.EMIT2_HZ, 0.0);
        send(generator.session.conn, event);
    }

    // -------------------------------------------------------------------------
    // The catalogue payloads of spec 4.3
    // -------------------------------------------------------------------------

    /** {@code backend.list}: what a client offers as selectable entries. */
    private ObjectNode backendList() {
        ObjectNode entry = json.objectNode();
        entry.put(NetFields.BACKEND, REMOTE_BACKEND);
        entry.put(NetFields.DISPLAY_NAME, DISPLAY_NAME);
        entry.put(NetFields.AVAILABLE, true);
        entry.put(NetFields.OPERATIONAL, true);
        ObjectNode data = json.objectNode();
        data.putArray(NetFields.BACKENDS).add(entry);
        return data;
    }

    /** {@code devices.list}: the per-backend array. */
    private ObjectNode devicesList() {
        ObjectNode data = json.objectNode();
        data.putArray(NetFields.BACKENDS).add(backendEntry());
        return data;
    }

    /** ONE backend entry - the shape {@code backend.select} answers with and the
     *  shape each element of {@code devices.list} has, so both fill the combos
     *  from the same payload. */
    private synchronized ObjectNode backendEntry() {
        ObjectNode entry = json.objectNode();
        entry.put(NetFields.BACKEND, REMOTE_BACKEND);
        ArrayNode array = entry.putArray(NetFields.DEVICES);
        for (BenchDevice device : devices) {
            if (!detached.contains(device)) {
                array.add(deviceNode(device));
            }
        }
        return entry;
    }

    private ObjectNode deviceNode(BenchDevice device) {
        ObjectNode node = json.objectNode();
        node.put(NetFields.INDEX, device.index);
        node.put(NetFields.NAME, device.name);
        node.put(NetFields.DESCRIPTION, DESCRIPTION);
        node.put(NetFields.VENDOR, VENDOR);
        node.put(NetFields.INPUT, device.input);
        node.put(NetFields.HAS_BIT_DEPTH, device.hasBitDepth);
        ArrayNode formats = node.putArray(NetFields.FORMATS);
        for (int rate : new int[] {RATE_48K, RATE_96K}) {
            ObjectNode format = formats.addObject();
            format.put(NetFields.RATE, rate);
            format.put(NetFields.BITS, BITS_24);
            format.put(NetFields.CHANNELS, CHANNELS);
        }
        if (device.holder == null) {
            node.putNull(NetFields.LOCK);
        } else {
            node.putObject(NetFields.LOCK).put(NetFields.BY, device.holder.clientName);
        }
        if (device.fsLeft > NO_FULL_SCALE && device.fsRight > NO_FULL_SCALE) {
            ObjectNode cal = node.putObject(NetFields.CAL);
            cal.put(NetFields.FS_RMS_LEFT, device.fsLeft);
            cal.put(NetFields.FS_RMS_RIGHT, device.fsRight);
        } else {
            node.putNull(NetFields.CAL);
        }
        if (device.card == null) {
            node.putNull(NetFields.CARD);
        } else {
            node.put(NetFields.CARD, device.card);
        }
        // Plain false unless this bench says the values are the analyzer's own -
        // the flag a client reads before it OFFERS a calibration edit (v1.1).
        node.put(NetFields.CAL_FROM_DEVICE, device.calFromDevice);
        return node;
    }

    /** Spec 4.3: every hot-plug and every LOCK change is broadcast to all
     *  sessions as the full {@code devices.list} payload. */
    private void broadcastDevices() {
        ObjectNode entry = backendEntry();
        for (Session session : sessions.values()) {
            NetMessage event = new NetMessage(MessageType.EV_DEVICES_CHANGED);
            event.getRoot().putArray(NetFields.BACKENDS).add(entry.deepCopy());
            send(session.conn, event);
        }
    }

    // -------------------------------------------------------------------------
    // The bench's own state
    // -------------------------------------------------------------------------

    /** One device of this bench, in ONE direction - which is what spec 4.3 calls
     *  a device, and therefore what a lock is taken on. */
    private static final class BenchDevice {

        private final int index;
        private final String name;
        private final boolean input;
        /** This bench offers one sample width, so offering a depth selector for
         *  it would be a lie the client has no other way of knowing. */
        private final boolean hasBitDepth;

        /** The session holding the exclusive lock, or null when free. */
        private Session holder;
        /** The full scale this bench's card store holds, or 0 for "no card". */
        private double fsLeft;
        private double fsRight;
        private String card;
        /** Whether this bench's card says the values are the DEVICE's own (spec
         *  4.3 {@code calFromDevice}) - an analyzer reading its own EEPROM. */
        private boolean calFromDevice;

        private BenchDevice(int index, String name, boolean input) {
            this.index = index;
            this.name = name;
            this.input = input;
            this.hasBitDepth = false;
        }
    }

    /** One connected client, with everything it holds - which is what makes the
     *  difference between "this session already has the lock" and "somebody else
     *  does" a thing the far end really decides. */
    private final class Session {

        private final WebSocket conn;
        private final Map<Integer, Capture> captures = new ConcurrentHashMap<>();
        private final Map<Integer, Generator> generators = new ConcurrentHashMap<>();

        /** The client's own name (spec 4.1) - what a {@code DEVICE_LOCKED} error
         *  names as the holder on somebody else's screen. */
        private volatile String clientName;

        private Session(WebSocket conn) {
            this.conn = conn;
        }

        /** One of this session's data connections went away (spec 4.7): the
         *  capture it carried keeps its handle and simply has no socket again,
         *  which is what an orderly {@code capture.close} leaves behind. */
        private void detach(WebSocket conn) {
            for (Capture capture : captures.values()) {
                if (capture.data == conn) {
                    capture.data = null;
                }
            }
        }

        /** Spec 4.3: releasing a device closes anything open on it. */
        private void closeEverythingOn(BenchDevice device) {
            for (Capture capture : List.copyOf(captures.values())) {
                if (capture.device == device) {
                    capture.closed = true;
                    captures.remove(capture.id);
                }
            }
            for (Generator generator : List.copyOf(generators.values())) {
                if (generator.device == device) {
                    generator.running = false;
                    generator.closed = true;
                    generators.remove(generator.id);
                }
            }
        }

        /** Spec 4.1: a dead session's streams stop and its locks go back. */
        private void tearDown() {
            for (Capture capture : captures.values()) {
                capture.closed = true;
            }
            captures.clear();
            for (Generator generator : generators.values()) {
                generator.running = false;
                generator.closed = true;
            }
            generators.clear();
            for (BenchDevice device : devices) {
                if (device.holder == this) {
                    device.holder = null;
                }
            }
        }
    }

    /**
     * One capture stream on the bench: the test says when a frame goes out and
     * what is in it, and this stamps the header of spec 5 - the stream id and
     * the packet counter that runs over frames of EVERY type.
     */
    final class Capture {

        @Getter
        private final int id;

        private final BenchDevice device;
        /** Spec 5: per stream, starts at 0, +1 per frame of any type. */
        private final AtomicLong packet = new AtomicLong();

        /** This capture's data connection (spec 4.7), or null until one attaches
         *  - and the socket every frame below goes out on, which is what makes
         *  "the audio never touches the control connection" a thing a client
         *  test can be wrong about. */
        private volatile WebSocket data;
        /** Volatile: set on a reader thread, read from the test's. */
        @Getter
        private volatile boolean closed;
        @Getter
        private volatile boolean started;

        private Capture(int id, BenchDevice device) {
            this.id = id;
            this.device = device;
        }

        /** One capture batch, as the bench's own audio worker would send it. */
        void feed(byte[] pcm) {
            send(data, new BinaryFrame(FrameType.PCM, id,
                    packet.getAndIncrement(), pcm).toBytes());
        }

        /**
         * One PCM frame stamped with a packet counter of the test's choosing,
         * and the stream's counter left where that frame put it.
         *
         * <p>Intact TCP neither loses nor reorders, so no server can be made to
         * produce this frame - and a client that did not check the counter would
         * splice across real loss with nothing to show for it.
         */
        void feedAt(long packetCounter, byte[] pcm) {
            packet.set(packetCounter + 1);
            send(data, new BinaryFrame(FrameType.PCM, id, packetCounter,
                    pcm).toBytes());
        }

        /** Spec 5's GAP: the bench's honest confession that it dropped
         *  {@code lostFrames} stereo frames. */
        void gap(long lostFrames) {
            send(data, new BinaryFrame(FrameType.GAP, id,
                    packet.getAndIncrement(), lostFrames).toBytes());
        }

        /** Spec 5's MARKER: an in-band position mark - the first PCM byte after
         *  it is aligned with sweep output sample 0. */
        void mark(long markerKind) {
            send(data, new BinaryFrame(FrameType.MARKER, id,
                    packet.getAndIncrement(), markerKind).toBytes());
        }

        /** This capture's data connection DROPS - the socket pulled, with no
         *  close frame and nothing on the control connection to say so (spec
         *  4.1's death rule against spec 4.7's discriminator).  It is the one
         *  thing only a bench can do to a client, and the client has to read it
         *  as the session dying rather than as a stream that ended. */
        void dropDataConnection() {
            WebSocket stream = data;
            data = null;
            if (stream != null) {
                // closeConnection, not close: no close handshake, so the far end
                // sees the ABNORMAL code a pulled socket really produces rather
                // than the NORMAL one an orderly close carries.
                stream.closeConnection(CloseFrame.ABNORMAL_CLOSE, "the socket was pulled");
            }
        }

        /** The bench closes this data connection the ORDERLY way (spec 4.7):
         *  a close frame carrying NORMAL, which is what a {@code capture.close}
         *  produces - and what a client must not read as the session dying even
         *  when nothing local marked the socket first. */
        void closeDataConnectionNormally() {
            WebSocket stream = data;
            data = null;
            if (stream != null) {
                stream.close(CloseFrame.NORMAL, "capture " + id + " closed");
            }
        }

        /** A control-plane message pushed down the DATA connection - the one
         *  thing spec 4.7 forbids a bench outright, and therefore the only way
         *  to ask what a client does when its peer mixes the planes. */
        void pushTextOnDataConnection(NetMessage message) {
            send(data, message);
        }

        /** A binary frame of a type this build has no meaning for (spec 1: an
         *  unknown frame is skipped whole, counter included). */
        void unknownFrame() {
            send(data, new BinaryFrame(FrameType.UPLINK_PCM, id,
                    packet.getAndIncrement(), 0L).toBytes());
        }
    }

    /**
     * One generator lane on the bench: a record of what it was commanded, and
     * the {@code ev.gen.state} it pushes back.
     *
     * <p>It renders nothing.  What the bench would DO with these numbers is the
     * bench's business; what a client test can prove is that the numbers reached
     * it, whole and in time to matter.
     */
    final class Generator {

        @Getter
        private final int id;
        @Getter
        private final int rate;
        @Getter
        private final int bits;
        @Getter
        private final double ditherBits;
        /** Which physical lane spec 4.5 gated the signal onto, as the wire text
         *  ({@code "BOTH"}, {@code "LEFT"}, {@code "RIGHT"}). */
        @Getter
        private final String outputChannels;

        private final Session session;
        private final BenchDevice device;
        /** Everything {@code gen.config} has applied so far - a PARTIAL update,
         *  so the fields accumulate exactly as they do on a real bench. */
        private final ObjectNode config = json.objectNode();

        @Getter
        private volatile boolean running;
        @Getter
        private volatile boolean closed;
        /** The file this lane was told to play, or null - what a test asserts
         *  {@code gen.playFile} actually named. */
        @Getter
        private volatile String playingFileId;
        /** The {@code loop} flag of that command. */
        @Getter
        private volatile boolean fileLoop;
        /** The configuration as it stood when {@code gen.start} arrived - what a
         *  bench would have built its waveform from, and therefore the only
         *  honest answer to "had the lead-in reached it in time". */
        @Getter
        private volatile ObjectNode configAtStart = json.objectNode();
        /** Spec 4.5's {@code gen.fftGrid}, or null while the client has sent
         *  none - then the snap below is a no-op, exactly as it is locally. */
        @Getter
        private volatile Integer fftSize;
        @Getter
        private volatile boolean snapEnabled;
        private volatile Double trimHz;

        private Generator(int id, Session session, BenchDevice device, int rate, int bits,
                double ditherBits, String outputChannels) {
            this.id = id;
            this.session = session;
            this.device = device;
            this.rate = rate;
            this.bits = bits;
            this.ditherBits = ditherBits;
            this.outputChannels = outputChannels;
        }

        /** Merges one {@code gen.config} into what this lane knows: the top-level
         *  fields it carries and, one level down, the fields of the sub-blocks -
         *  because spec 4.5 applies "only the fields present", and re-sending a
         *  sweep's duration would re-render the chirp under whoever is
         *  recording it. */
        private void apply(NetMessage request) {
            for (Map.Entry<String, JsonNode> field : request.getRoot().properties()) {
                String name = field.getKey();
                JsonNode value = field.getValue();
                if (value.isObject()) {
                    ObjectNode block = config.has(name) && config.path(name).isObject()
                            ? (ObjectNode) config.path(name) : config.putObject(name);
                    block.setAll((ObjectNode) value);
                } else {
                    config.set(name, value);
                }
            }
        }

        /** The waveform this lane was told to emit; SINE until it is told. */
        private GenSignalForm form() {
            String name = config.path(NetFields.FORM).asText(null);
            return name == null ? GenSignalForm.SINE : GenSignalForm.valueOf(name);
        }

        private double nominalHz() {
            return config.path(NetFields.FREQUENCY).asDouble();
        }

        /**
         * What this lane reports as EMITTED: the trim if one was sent (spec 4.5
         * makes it an absolute corrected frequency), else the nominal snapped
         * onto the grid the client sent with {@code gen.fftGrid}.
         *
         * <p>The snap uses the grid THIS BENCH WAS TOLD, so a client that never
         * put the analyzer's FFT size on the wire gets a different number back
         * than the one its own snap computes - which is the whole point of the
         * field.
         */
        private double emitHz() {
            Double trimmed = trimHz;
            if (trimmed != null) {
                return trimmed;
            }
            Integer grid = fftSize;
            return FftBinSnap.snapIfEnabled(form(), rate, grid == null ? 0 : grid,
                    snapEnabled, nominalHz());
        }
    }
}
