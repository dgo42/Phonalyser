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

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.core.JsonProcessingException;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.net.proto.ErrorCode;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetError;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetMessage;
import org.edgo.audio.measure.net.proto.NetProto;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import lombok.Getter;
import lombok.Setter;

/**
 * The far end of the socket: a Phonalyser bench with one QA40x behind it, which
 * exists only as the JSON it speaks.
 *
 * <p><b>Why a socket and not a server object.</b>  This module is the CLIENT's
 * bench UI.  What is across the wire is a process that answers JSON - so that is
 * what a test here mocks, exactly as a database client's tests mock the database
 * rather than starting one.  The wire format in {@code server-api} is the entire
 * contract, and nothing in this module compiles against the server.
 *
 * <p><b>Why it is not vacuous.</b>  It does not replay what the client expects;
 * it enforces the rules the client is being tested against, and it keeps state
 * that a write really has to move:
 *
 * <ul>
 *   <li>Spec 4.6's writes ({@code qa40x.settings} with a field,
 *       {@code qa40x.setInputRange}) are refused {@code NOT_LOCKED} unless the
 *       session holds SOME lane of the analyzer; the reads are free.</li>
 *   <li>Spec 4.3's {@code device.setCalibration} is refused unless the session
 *       holds the lane the request NAMES - the weaker any-device rule would
 *       accept a write locked on the wrong direction, which is the silent
 *       failure the client's own lock split exists to prevent.</li>
 *   <li>The front-panel port, the active ranges and the per-lane calibration are
 *       remembered, so a write the client dropped on the way is told apart from
 *       one that changed nothing.</li>
 *   <li>{@code backend.select} is COUNTED, so "the commit is idempotent" and
 *       "a preview commits nothing" become assertions rather than comments.</li>
 * </ul>
 *
 * <p>It answers the keepalive of spec 4.1: a session here is meant to survive
 * for as long as a test watches it, which is the opposite of what a scripted
 * peer proving the client's death detector wants.
 */
final class BenchSocket extends WebSocketServer {

    /** The operator-visible name this bench reports in its {@code hello}. */
    static final String SERVER_NAME = "Bench QA403";
    /** The one device the analyzer offers, in each direction. */
    static final String DEVICE_NAME = "QA403";
    static final String FIRMWARE = "23";
    static final String SERIAL = "QA403-0001";
    static final String TEMPERATURE = "31.5 °C";
    /** The selectable input attenuator positions.  Deliberately unlike the
     *  output list, so a payload that crossed the two fails on its content. */
    static final int[] INPUT_RANGES_DBV = {0, 42};
    static final int[] OUTPUT_RANGES_DBV = {-12, 18};
    static final int DEFAULT_INPUT_DBV = 0;
    static final int DEFAULT_OUTPUT_DBV = 18;
    /** Deliberately unequal, so a round trip that crossed the two channels fails
     *  on the values rather than passing on symmetry. */
    static final double CAL_LEFT = 1.25;
    static final double CAL_RIGHT = 1.5;

    private static final String LOOPBACK = "127.0.0.1";
    private static final String SERVER_APP = "1.2.0";
    private static final String DESCRIPTION = "QuantAsylum analyzer";
    private static final String VENDOR = "QuantAsylum";
    private static final String DISPLAY_NAME = "QA40x";
    private static final String USB_VOLTAGE = "5.01 V";
    private static final String USB_CURRENT = "0.42 A";
    private static final String ISO_CURRENT = "-";
    private static final String CAPABILITY = "0x0003";
    private static final String CAPABILITY2 = "0x0001";
    private static final int RATE_48K = 48_000;
    private static final int BITS_24 = 24;
    private static final int STEREO = 2;
    /** The library's own pong-based detector is off on both ends: liveness is
     *  spec 4.1's 500 ms keepalive, and a second detector would only disagree. */
    private static final int LIBRARY_KEEPALIVE_OFF = 0;

    /** The wire format's mapper - this end's own, like the real server's. */
    private final JsonCodec codec;
    /** Spec 2.1's installation UUID.  Injected because it is what tells one
     *  bench from another: a restart keeps it, another bench does not. */
    @Getter
    private final String serverId;
    /** Guards everything the conversation touches - the library decodes on its
     *  own worker threads, and a test reads the analyzer's state from its own. */
    private final Object bench = new Object();
    private final CountDownLatch listening = new CountDownLatch(1);
    /** Counted down by the first {@code hello} that arrives, so a test can act
     *  at the exact moment a connect attempt is IN FLIGHT. */
    private final CountDownLatch helloSeen = new CountDownLatch(1);
    /** The same for {@code backend.select}: with {@link #setSelectDelayMs} it is
     *  what lets a test act while a COMMIT is provably still on the wire, rather
     *  than guessing at the window with a sleep. */
    private final CountDownLatch selectSeen = new CountDownLatch(1);

    /** Spec 4.3's exclusive locks, by the lane they are keyed on.  This bench
     *  serves one session at a time, so a lane in here is one THIS session
     *  holds. */
    private final Set<Lane> locked = new HashSet<>();
    /** Spec 4.5's open playback lanes, by the handle this bench handed out.
     *  Deliberately NOT cleared by {@link #onClose} - a lane the client gave
     *  back on the wire is what a test has to tell apart from one the server's
     *  own teardown swept up afterwards. */
    private final Set<Integer> generators = new HashSet<>();
    /** Every request this bench answered, in the order it answered them - the
     *  only way to see that a teardown step really went out while the session
     *  was still alive, since the state it changed is torn down either way. */
    private final List<MessageType> conversation = new ArrayList<>();
    /** What {@code device.setCalibration} stored, by the lane it named. */
    private final Map<Lane, Cal> calibrations = new HashMap<>();
    private final AtomicInteger selectCount = new AtomicInteger();
    private final AtomicInteger closedCount = new AtomicInteger();

    /** The analyzer's front-panel I2S port, which really does keep what was
     *  written to it - a bench that forgot a write would let a client that
     *  dropped it pass. */
    @Getter
    private volatile boolean i2sEnabled;
    @Getter
    private volatile int activeInputRangeDbv = DEFAULT_INPUT_DBV;
    @Getter
    private volatile int activeOutputRangeDbv = DEFAULT_OUTPUT_DBV;
    /** Spec 4.1's client name, as the connecting session gave it - what a lock
     *  is reported as being held BY. */
    private volatile String clientName = "";
    /** The last handle {@code gen.open} handed out.  Guarded by {@link #bench},
     *  like everything else the conversation moves. */
    private int lastGenId;
    /** How long the {@code hello} answer is held back.  A bench that completes
     *  the socket handshake and then takes its time is what puts a connect
     *  attempt in flight for a known window. */
    @Setter
    private volatile long helloDelayMs;
    /** How long {@code backend.select} is held before it is answered - a server
     *  that has just restarted really is slow here, because it enumerates its
     *  devices to build the answer. */
    @Setter
    private volatile long selectDelayMs;

    BenchSocket(JsonCodec codec, String serverId, int port) {
        super(new InetSocketAddress(LOOPBACK, port));
        this.codec = codec;
        this.serverId = serverId;
        setConnectionLostTimeout(LIBRARY_KEEPALIVE_OFF);
        setReuseAddr(true);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /** Starts listening.  {@link #start()} only spawns a thread, so a client
     *  that dialled straight after it would find nothing bound yet. */
    void listen(long timeoutMs) {
        start();
        await(listening, timeoutMs, "the bench did not bind within " + timeoutMs + " ms");
    }

    /** Waits until a connect attempt has reached this bench - the {@code hello}
     *  is in, and (with {@link #setHelloDelayMs}) not yet answered. */
    void awaitHello(long timeoutMs) {
        await(helloSeen, timeoutMs, "no client dialled this bench within "
                + timeoutMs + " ms");
    }

    /** Waits until a {@code backend.select} has reached this bench - and, with
     *  {@link #setSelectDelayMs}, is being HELD unanswered.  What lets a test say
     *  "while the commit is on the wire" and mean it. */
    void awaitSelect(long timeoutMs) {
        await(selectSeen, timeoutMs, "no client committed a backend on this bench "
                + "within " + timeoutMs + " ms");
    }

    /** Stops listening and drops the session; the test's teardown calls it
     *  whatever happened. */
    void shutDown(long timeoutMs) {
        try {
            stop((int) timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** How many {@code backend.select} round trips this bench has answered -
     *  the seam that tells a re-affirmed selection from a second commit. */
    int selects() {
        return selectCount.get();
    }

    /** How many sessions ended on this bench: a client that dialled and then
     *  gave the session straight back shows up here as one. */
    int closedSessions() {
        return closedCount.get();
    }

    /** How many playback lanes of spec 4.5 are still open here.  Survives the
     *  socket closing on purpose: a lane that is still counted after a
     *  disconnect is one the client abandoned rather than gave back. */
    int openGenerators() {
        synchronized (bench) {
            return generators.size();
        }
    }

    /** Every request this bench answered, in order - what a teardown that has
     *  to happen BEFORE the farewell is asserted against. */
    List<MessageType> conversation() {
        synchronized (bench) {
            return List.copyOf(conversation);
        }
    }

    @Override
    public void onStart() {
        listening.countDown();
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        // Nothing to do: the protocol's own handshake is `hello`.
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        synchronized (bench) {
            // Spec 4.1: the locks a session held go back with it.
            locked.clear();
        }
        closedCount.incrementAndGet();
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        // A test that hangs says more than one that fails on a socket closing
        // under it during teardown, so nothing here is fatal.
    }

    // -------------------------------------------------------------------------
    // The conversation
    // -------------------------------------------------------------------------

    @Override
    public void onMessage(WebSocket conn, String text) {
        NetMessage request;
        try {
            request = codec.read(text);
        } catch (JsonProcessingException | RuntimeException e) {
            throw new IllegalStateException("the client sent undecodable text: " + text, e);
        }
        Integer id = request.getId();
        if (id == null) {
            return;                 // an event: spec 4.0 never answers one
        }
        if (request.getType() == MessageType.HELLO) {
            helloSeen.countDown();
            pause(helloDelayMs);
        }
        // Announced BEFORE the pause, so a test waiting on the latch is released
        // while the answer is still being held back - which is the whole point.
        if (request.getType() == MessageType.BACKEND_SELECT) {
            selectSeen.countDown();
            pause(selectDelayMs);
        }
        synchronized (bench) {
            answer(conn, id, request);
        }
    }

    /** One request, answered the way the protocol says - the same switch the
     *  server's own session runs, minus everything this module cannot reach
     *  (capture streaming, the generator lane, the card store). */
    private void answer(WebSocket conn, int id, NetMessage request) {
        conversation.add(request.getType());
        switch (request.getType()) {
            case HELLO:
                clientName = request.optString(NetFields.NAME);
                send(conn, new NetMessage(id, codec.toNode(hello())));
                break;
            case PING:
            case BYE:
                send(conn, new NetMessage(id));
                break;
            case BACKEND_LIST:
                send(conn, new NetMessage(id,
                        codec.toNode(Map.of(NetFields.BACKENDS, backends()))));
                break;
            case BACKEND_SELECT:
                backendSelect(conn, id, request);
                break;
            case DEVICES_LIST:
                send(conn, new NetMessage(id, codec.toNode(catalogue())));
                break;
            case DEVICE_ACQUIRE:
                acquire(conn, id, request);
                break;
            case DEVICE_RELEASE:
                release(conn, id, request);
                break;
            case DEVICE_SET_CALIBRATION:
                setCalibration(conn, id, request);
                break;
            case GEN_OPEN:
                genOpen(conn, id, request);
                break;
            case GEN_CLOSE:
                genClose(conn, id, request);
                break;
            case QA40X_INFO:
                send(conn, new NetMessage(id, codec.toNode(info())));
                break;
            case QA40X_RANGES:
                send(conn, new NetMessage(id, codec.toNode(ranges())));
                break;
            case QA40X_CALIBRATION:
                send(conn, new NetMessage(id, codec.toNode(calibrationPage())));
                break;
            case QA40X_SETTINGS:
                settings(conn, id, request);
                break;
            case QA40X_SET_INPUT_RANGE:
                setRange(conn, id, request, true);
                break;
            case QA40X_SET_OUTPUT_RANGE:
                setRange(conn, id, request, false);
                break;
            default:
                fail(conn, id, ErrorCode.UNSUPPORTED,
                        "this bench does not serve '" + request.getT() + "'");
                break;
        }
    }

    /** Spec 4.1's {@code hello} response, with the capability tokens a bench
     *  that really has an analyzer behind it advertises. */
    private Map<String, Object> hello() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(NetFields.PROTO, NetProto.PROTO_VERSION);
        data.put(NetFields.SERVER_ID, serverId);
        data.put(NetFields.NAME, SERVER_NAME);
        data.put(NetFields.APP, SERVER_APP);
        data.put(NetFields.CAPS, List.of(NetFields.CAP_QA40X, NetFields.CAP_GEN));
        return data;
    }

    /** Spec 4.3's {@code backend.list}: what this bench can be measured with. */
    private List<Map<String, Object>> backends() {
        Map<String, Object> analyzer = new LinkedHashMap<>();
        analyzer.put(NetFields.BACKEND, AudioBackendType.QA40X.name());
        analyzer.put(NetFields.DISPLAY_NAME, DISPLAY_NAME);
        analyzer.put(NetFields.AVAILABLE, true);
        analyzer.put(NetFields.OPERATIONAL, true);
        analyzer.put(NetFields.HAS_BIT_DEPTH, false);
        return List.of(analyzer);
    }

    /**
     * Spec 4.3's {@code backend.select}: the session commits to one backend and
     * is answered with that backend's whole {@code devices.list} entry, so one
     * round trip fills the client's device combos.
     */
    private void backendSelect(WebSocket conn, int id, NetMessage request) {
        if (!AudioBackendType.QA40X.name().equals(request.optString(NetFields.BACKEND))) {
            fail(conn, id, ErrorCode.BAD_REQUEST,
                    "this bench serves only " + AudioBackendType.QA40X);
            return;
        }
        selectCount.incrementAndGet();
        send(conn, new NetMessage(id, codec.toNode(backendEntry())));
    }

    /** Spec 4.3's {@code devices.list}: every backend this bench serves. */
    private Map<String, Object> catalogue() {
        return Map.of(NetFields.BACKENDS, List.of(backendEntry()));
    }

    /** One backend's entry - the shape {@code backend.select} answers with and
     *  the shape each element of a {@code devices.list} array has, so a preview
     *  and a commit can never disagree about what a device is. */
    private Map<String, Object> backendEntry() {
        List<Map<String, Object>> devices = new ArrayList<>();
        devices.add(device(new Lane(0, true)));
        devices.add(device(new Lane(0, false)));
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(NetFields.BACKEND, AudioBackendType.QA40X.name());
        entry.put(NetFields.DEVICES, devices);
        return entry;
    }

    /** One device object of spec 4.3, whole: the ref fields, the inlined
     *  formats, the lock overlay and the stored calibration.  An ordered map
     *  rather than {@code Map.of}, which forbids the JSON nulls the protocol
     *  uses as values - "free" and "no calibration" are answers, not absences. */
    private Map<String, Object> device(Lane lane) {
        Map<String, Object> format = new LinkedHashMap<>();
        format.put(NetFields.RATE, RATE_48K);
        format.put(NetFields.BITS, BITS_24);
        format.put(NetFields.CHANNELS, STEREO);
        Cal cal = calibrations.get(lane);
        Map<String, Object> device = new LinkedHashMap<>();
        device.put(NetFields.INDEX, lane.index());
        device.put(NetFields.NAME, DEVICE_NAME);
        device.put(NetFields.DESCRIPTION, DESCRIPTION);
        device.put(NetFields.VENDOR, VENDOR);
        device.put(NetFields.INPUT, lane.input());
        device.put(NetFields.OUTPUT, !lane.input());
        device.put(NetFields.FORMATS, List.of(format));
        device.put(NetFields.HAS_BIT_DEPTH, false);
        device.put(NetFields.LOCK, locked.contains(lane)
                ? Map.of(NetFields.BY, clientName) : null);
        device.put(NetFields.CAL, cal == null ? null : cal.toMap());
        device.put(NetFields.CARD, null);
        return device;
    }

    /** Spec 4.3: an exclusive lock on one lane.  A repeat acquire by the session
     *  that already holds it succeeds - there is nothing on the wire to tell the
     *  two apart, which is exactly why the client has to remember. */
    private void acquire(WebSocket conn, int id, NetMessage request) {
        Lane lane = laneOf(request);
        if (lane == null) {
            fail(conn, id, ErrorCode.DEVICE_STALE, "no such device on this bench");
            return;
        }
        locked.add(lane);
        send(conn, new NetMessage(id));
    }

    /** Spec 4.3: gives the lane back; {@code NOT_LOCKED} when this session never
     *  held it. */
    private void release(WebSocket conn, int id, NetMessage request) {
        Lane lane = laneOf(request);
        if (lane == null || !locked.remove(lane)) {
            fail(conn, id, ErrorCode.NOT_LOCKED, "that device is not locked here");
            return;
        }
        send(conn, new NetMessage(id));
    }

    /**
     * Spec 4.5: the far end's playback lane opens, silent until {@code gen.start}
     * - and only for a client that holds the DAC, because "all {@code gen.*}
     * require the output-device lock".
     *
     * <p>The GRANTED rate comes back with the handle: the bench's own DAC has the
     * last word, and a client that went on counting sweep samples against the rate
     * it ASKED for would command one grid while this end emitted another.
     */
    private void genOpen(WebSocket conn, int id, NetMessage request) {
        Lane lane = laneOf(request);
        if (lane == null || lane.input()) {
            fail(conn, id, ErrorCode.BAD_REQUEST, "a generator needs an output device");
            return;
        }
        if (!locked.contains(lane)) {
            fail(conn, id, ErrorCode.NOT_LOCKED,
                    "the output device must be acquired before a generator opens on it");
            return;
        }
        generators.add(++lastGenId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(NetFields.GEN_ID, lastGenId);
        data.put(NetFields.RATE, RATE_48K);
        send(conn, new NetMessage(id, codec.toNode(data)));
    }

    /** Spec 4.5: the lane goes back.  A handle this bench never opened is
     *  refused, so a close aimed at nothing cannot pass for a real one. */
    private void genClose(WebSocket conn, int id, NetMessage request) {
        Integer handle = request.optInt(NetFields.GEN_ID);
        if (handle == null || !generators.remove(handle)) {
            fail(conn, id, ErrorCode.BAD_REQUEST, "no such generator on this bench");
            return;
        }
        send(conn, new NetMessage(id));
    }

    /**
     * Spec 4.3's {@code device.setCalibration}: locked on the lane the request
     * NAMES, not on whichever lane of the backend happened to be free.
     *
     * <p>That distinction is the whole point of this handler: a client that took
     * the weaker any-device lock of spec 4.6 would send a DAC calibration under
     * an ADC lock, and be answered exactly this refusal.
     */
    private void setCalibration(WebSocket conn, int id, NetMessage request) {
        Lane lane = laneOf(request);
        if (lane == null) {
            fail(conn, id, ErrorCode.DEVICE_STALE, "no such device on this bench");
            return;
        }
        if (!locked.contains(lane)) {
            fail(conn, id, ErrorCode.NOT_LOCKED,
                    "the device must be acquired before its calibration can be written");
            return;
        }
        Double left = request.optDouble(NetFields.FS_RMS_LEFT);
        Double right = request.optDouble(NetFields.FS_RMS_RIGHT);
        if (left == null || right == null) {
            fail(conn, id, ErrorCode.BAD_REQUEST,
                    "a calibration needs both full scales");
            return;
        }
        calibrations.put(lane, new Cal(left, right));
        // Before the response, exactly as the server orders it: the catalogue a
        // client reads afterwards already carries the new value.
        broadcastDevicesChanged(conn);
        send(conn, new NetMessage(id));
    }

    /** Spec 4.6 {@code qa40x.info} (read-only): the telemetry registers, as the
     *  strings this end formatted them into - nothing is re-interpreted on the
     *  way, which is what lets one panel show a local analyzer and a remote one. */
    private Map<String, Object> info() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(NetFields.FIRMWARE_VERSION, FIRMWARE);
        data.put(NetFields.USB_VOLTAGE, USB_VOLTAGE);
        data.put(NetFields.USB_CURRENT, USB_CURRENT);
        data.put(NetFields.ISO_CURRENT, ISO_CURRENT);
        data.put(NetFields.TEMPERATURE, TEMPERATURE);
        data.put(NetFields.CAPABILITY, CAPABILITY);
        data.put(NetFields.CAPABILITY2, CAPABILITY2);
        data.put(NetFields.SERIAL_NUMBER, SERIAL);
        return data;
    }

    /** Spec 4.6 {@code qa40x.ranges} (read-only). */
    private Map<String, Object> ranges() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(NetFields.INPUT_DBV, INPUT_RANGES_DBV);
        data.put(NetFields.OUTPUT_DBV, OUTPUT_RANGES_DBV);
        data.put(NetFields.ACTIVE_INPUT_DBV, activeInputRangeDbv);
        data.put(NetFields.ACTIVE_OUTPUT_DBV, activeOutputRangeDbv);
        return data;
    }

    /** Spec 4.6 {@code qa40x.calibration} (read-only): the device's own linear
     *  factors, one row per attenuator position. */
    private Map<String, Object> calibrationPage() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(NetFields.ADC, rows(INPUT_RANGES_DBV));
        data.put(NetFields.DAC, rows(OUTPUT_RANGES_DBV));
        return data;
    }

    private List<Map<String, Object>> rows(int[] positions) {
        List<Map<String, Object>> page = new ArrayList<>();
        for (int dbv : positions) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(NetFields.DBV, dbv);
            row.put(NetFields.LEFT, CAL_LEFT);
            row.put(NetFields.RIGHT, CAL_RIGHT);
            page.add(row);
        }
        return page;
    }

    /** Spec 4.6 {@code qa40x.settings}: a get (no field) is read-only and free;
     *  a set moves the front-panel port and needs the analyzer's lock.  Either
     *  way the answer is the state now in force, so a client never has to guess
     *  whether its write took. */
    private void settings(WebSocket conn, int id, NetMessage request) {
        Boolean wanted = request.optBoolean(NetFields.I2S_ENABLED);
        if (wanted != null) {
            if (!heldSomewhere()) {
                fail(conn, id, ErrorCode.NOT_LOCKED,
                        "the QA40x must be acquired before its settings can be changed");
                return;
            }
            i2sEnabled = wanted;
        }
        send(conn, new NetMessage(id,
                codec.toNode(Map.of(NetFields.I2S_ENABLED, i2sEnabled))));
    }

    /** Spec 4.6's range setters: the attenuator moves, and every client is told
     *  - the range in force IS the device's full scale. */
    private void setRange(WebSocket conn, int id, NetMessage request, boolean input) {
        if (!heldSomewhere()) {
            fail(conn, id, ErrorCode.NOT_LOCKED,
                    "the QA40x must be acquired before its ranges can be changed");
            return;
        }
        Integer dbv = request.optInt(NetFields.DBV);
        if (dbv == null || !has(input ? INPUT_RANGES_DBV : OUTPUT_RANGES_DBV, dbv)) {
            fail(conn, id, ErrorCode.BAD_REQUEST,
                    "this analyzer has no such range: " + dbv);
            return;
        }
        if (input) {
            activeInputRangeDbv = dbv;
        } else {
            activeOutputRangeDbv = dbv;
        }
        broadcastDevicesChanged(conn);
        send(conn, new NetMessage(id));
    }

    /** Spec 4.6's rule: "the QA40x lock (either direction)" - the analyzer is
     *  ONE device with one attenuator pair, so holding any lane of it makes the
     *  caller the client whose measurement the change belongs to. */
    private boolean heldSomewhere() {
        return !locked.isEmpty();
    }

    /** Spec 4.3's fan-out: the event carries the full {@code devices.list}
     *  payload, so a client's cached catalogue refreshes with no request. */
    private void broadcastDevicesChanged(WebSocket conn) {
        send(conn, new NetMessage(MessageType.EV_DEVICES_CHANGED)
                .put(NetFields.BACKENDS, codec.toNode(List.of(backendEntry()))));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** The lane a request names through the four ref fields of spec 4.3, or null
     *  when this bench has no such device - the {@code DEVICE_STALE} case. */
    private Lane laneOf(NetMessage request) {
        Integer index = request.optInt(NetFields.INDEX);
        Boolean input = request.optBoolean(NetFields.INPUT);
        if (index == null || input == null || index != 0
                || !AudioBackendType.QA40X.name().equals(request.optString(NetFields.BACKEND))
                || !DEVICE_NAME.equals(request.optString(NetFields.NAME))) {
            return null;
        }
        return new Lane(index, input);
    }

    private boolean has(int[] positions, int dbv) {
        for (int position : positions) {
            if (position == dbv) {
                return true;
            }
        }
        return false;
    }

    private void fail(WebSocket conn, int id, ErrorCode code, String message) {
        send(conn, new NetMessage(id, new NetError(code, message)));
    }

    /** Writes one message, tolerating a socket that has already gone: a test's
     *  teardown races the answers still in flight, and that race is ordinary. */
    private void send(WebSocket conn, NetMessage message) {
        try {
            conn.send(codec.write(message));
        } catch (RuntimeException e) {
            // The session ended under this answer; nobody is left to tell.
        }
    }

    private void await(CountDownLatch latch, long timeoutMs, String what) {
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(what);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(what, e);
        }
    }

    private void pause(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** One device and one direction - what spec 4.3 keys a lock on, and what a
     *  calibration belongs to.  The two are the same pair on purpose: that is
     *  the identity a write has to be locked on. */
    private record Lane(int index, boolean input) {
    }

    /** The {@code cal} object of spec 4.3: the full-scale RMS volts this bench
     *  stores for one lane. */
    private record Cal(double left, double right) {

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put(NetFields.FS_RMS_LEFT, left);
            map.put(NetFields.FS_RMS_RIGHT, right);
            return map;
        }
    }
}
