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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.net.proto.ErrorCode;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetError;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetMessage;
import org.edgo.audio.measure.net.proto.NetProto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * One WebSocket connection, for its whole life: the {@code hello} handshake of
 * spec 1, the request dispatch of spec 4, the keepalive of spec 4.1 and the
 * teardown that gives the hardware back.
 *
 * <p><b>Keepalive.</b>  The server pings every 500 ms with a NEGATIVE id (spec
 * 4.0 - server request ids cannot collide with the client's) and counts the
 * unanswered ones; four in a row and the connection is dead.  Time arrives
 * through an injected {@link Ticker}, never from a clock read or a sleep, so
 * the whole death sequence is a handful of method calls in a test and the
 * production path is one shared scheduler.
 *
 * <p><b>Ownership.</b>  The session owns nothing it can forget to give back:
 * the locks it holds are recorded in the {@link LockRegistry} alone, so
 * {@link #close(String)} - from {@code bye}, from a dead keepalive, or from the
 * transport vanishing - frees them by identity.  Its capture streams are the
 * {@link CaptureStreamer}'s in the same way, and its generator, its output line
 * and the uploaded files it referenced are the {@link GeneratorSession}'s.
 *
 * <p><b>Threading.</b>  Requests run on the session's own {@link SessionWorker}
 * thread, one at a time and in the order the client sent them, so a call that
 * waits on hardware delays nothing but the connection that asked for it - not
 * the transport's reader thread (which serves several connections), not the
 * other sessions, and not this session's own teardown.  Keepalive traffic
 * deliberately bypasses that queue: the client's {@code ping} and its answers to
 * ours are served on the thread that delivered them, over atomic counters,
 * because liveness must never be reported from behind a device call - a server
 * that declares a healthy client dead over its own latency is worse than no
 * keepalive at all.  {@link #close(String)} is a lock-free flag flip for the
 * same reason: every thread must be able to end the session immediately.
 */
@Log4j2
@RequiredArgsConstructor
public final class ClientSession {

    /** Value of {@link #proto} before a successful {@code hello}. */
    private static final int NO_PROTO = 0;
    /** Stands in for the client's {@code name} until it introduces itself. */
    private static final String UNNAMED_CLIENT = "unknown client";
    /** What the {@code hello} response advertises (spec 4.1) whatever the host
     *  is: the remote generator of spec 4.5 and the uploads of spec 3, both
     *  served by every server.  A token is a PROMISE that the commands behind it
     *  work - spec 4.6 lets a client send {@code qa40x.*} the moment it reads
     *  {@code "qa40x"} - which is why {@link NetFields#CAP_QA40X} is NOT here: it
     *  is added per server, and only when this one really has an analyzer to
     *  serve (see {@link #caps()}). */
    private static final List<String> CAPS_SERVED =
            List.of(NetFields.CAP_GEN, NetFields.CAP_FILES);
    /**
     * How long a DYING session's teardown may take before its locks are taken
     * back without it - see {@link #close(String)}.
     *
     * <p>Longer than any healthy teardown: that one joins the render thread (two
     * seconds) and closes a device line after it.  Short enough that a bench is
     * not stranded - past this point the teardown is not slow, it is stuck in a
     * driver call that may never return, and a lock nobody can ever release is a
     * device no other connection acquires again short of restarting the server.
     */
    private static final long TEARDOWN_WATCHDOG_MS = 5_000;

    private final ServerConfig config;
    private final LockRegistry locks;
    /** The shared analyzer's safe state - the last step of the teardown below,
     *  and the reason it has to be the LAST one. */
    private final Qa40xGuard qa40x;
    private final DeviceCatalog catalog;
    private final CaptureStreamer captures;
    private final GeneratorSession generator;
    /** The analyzer's own commands (spec 4.6).  Server-wide like the guard beside
     *  it - one analyzer, whoever is asking - and stateless, so what makes a
     *  {@code qa40x.*} call THIS connection's is the lock check below. */
    private final Qa40xSession qa40xSession;
    private final JsonCodec codec;
    private final SessionChannel channel;
    private final Ticker ticker;
    private final SessionWorker worker;

    /** The negotiated session version (spec 1); {@link #NO_PROTO} until the
     *  handshake succeeds, which is also the "hello was first" flag.  Written on
     *  the worker thread, read wherever the session is inspected. */
    @Getter
    private volatile int proto;
    /** The user-visible client name - what a {@code DEVICE_LOCKED} error and a
     *  device's {@code lock} object name as the owner (spec 4.3).  Written on
     *  the worker thread and read by OTHER connections' threads, hence
     *  volatile: a refusal that says "unknown client" instead of naming the
     *  operator who has the device is a lie the bench cannot debug. */
    @Getter
    private volatile String clientName = UNNAMED_CLIENT;
    /** The backend this connection committed to with {@code backend.select}
     *  (spec 4.3), or null while it has committed to none.  SESSION state, not
     *  server state: the server serves all its backends at once and two clients
     *  using different backends of the same bench is a supported case, so this
     *  must never reach {@code AudioBackend.setActive}.  Written and read only on
     *  the session's own worker thread, which is why it needs no volatile. */
    private AudioBackendType selectedBackend;
    /** Sequence number of the last server ping sent; its id on the wire is the
     *  negative of this.  WRITTEN by the keepalive task alone, which the scheduler
     *  never runs twice at once - volatile because {@link #heard()} reads it from
     *  the transport thread, and a torn or cached read there would level the
     *  liveness mark against a count that never existed. */
    private volatile int pingCounter;
    /** Sequence number of the NEWEST ping the client answered.  Spec 4.1 counts
     *  CONSECUTIVE unanswered pings, which is {@link #pingCounter} minus this -
     *  an id match, so a stale answer cannot pass for a fresh one. */
    private final AtomicInteger lastAnsweredPing = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

    /** Starts the keepalive.  Called once, when the connection opens. */
    public void start() {
        ticker.start(NetProto.PING_INTERVAL_MS, this::keepaliveTick);
    }

    /** True once the session has ended and given everything back. */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Takes one decoded control message off the transport thread.  Keepalive
     * traffic - the client's {@code ping} and its answer to ours - is served
     * right here; everything else goes to the session's own thread (see the
     * class comment on why the two must not share a queue).
     */
    public void onMessage(NetMessage message) {
        if (closed.get()) {
            return;
        }
        heard();
        MessageType type = message.getType();
        if (type == MessageType.RESP) {
            onResponse(message);
            return;
        }
        Integer id = message.getId();
        if (id == null) {
            if (log.isWarnEnabled()) {
                log.warn("net session {}: request without id ignored: {}",
                        clientName, message.getT());
            }
            return;
        }
        if (type == MessageType.PING) {
            // Spec 4.1 lists ping as "both, every 500 ms" and marks only hello
            // as MUST-be-first: the server pings from the moment the socket
            // opens, so it answers from then too.
            channel.send(new NetMessage(id));
            return;
        }
        int requestId = id;
        worker.submit(() -> dispatch(requestId, type, message));
    }

    /**
     * One request, on the session's own thread.
     *
     * <p>The two {@code catch} clauses draw the line spec 4.2 draws: a
     * {@link NetException} is a refusal the protocol has a word for - a stale
     * device, a rate the hardware forbids - and the client is told which.
     * {@code INTERNAL} is for everything else: a handler that failed for a
     * reason the protocol does not name.  Either way the client is told about
     * ITS request and keeps its locks and its streams - losing the whole session
     * over one bad call would take the running measurements of every module on
     * that connection down with it.
     */
    private void dispatch(int id, MessageType type, NetMessage message) {
        if (closed.get()) {
            return;
        }
        try {
            if (proto == NO_PROTO && type != MessageType.HELLO) {
                fail(id, ErrorCode.BAD_REQUEST, "hello must be the first message");
                return;
            }
            switch (type) {
                case HELLO:           hello(id, message); break;
                case BYE:             bye(id); break;
                case BACKEND_LIST:    backendList(id); break;
                case BACKEND_SELECT:  backendSelect(id, message); break;
                case DEVICES_LIST:    devicesList(id); break;
                case DEVICE_ACQUIRE:  deviceAcquire(id, message); break;
                case DEVICE_RELEASE:  deviceRelease(id, message); break;
                case DEVICE_SET_CALIBRATION: deviceSetCalibration(id, message); break;
                case DEVICE_SET_CARD: deviceSetCard(id, message); break;
                case DEVICE_SET_ACTIVE_RANGE: deviceSetActiveRange(id, message); break;
                case CARDS_LIST:      cardsList(id); break;
                case CARDS_PUT:       cardsPut(id, message); break;
                case CAPTURE_OPEN:    captureOpen(id, message); break;
                case CAPTURE_START:   captureCommand(id, message, captures::start); break;
                case CAPTURE_STOP:    captureCommand(id, message, captures::stop); break;
                case CAPTURE_CLOSE:   captureCommand(id, message, captures::close); break;
                case GEN_OPEN:        genOpen(id, message); break;
                case GEN_CONFIG:      genConfig(id, message); break;
                case GEN_START:       genCommand(id, message, generator::start); break;
                case GEN_STOP:        genCommand(id, message, generator::stop); break;
                case GEN_FFT_GRID:    genFftGrid(id, message); break;
                case GEN_TRIM:        genTrim(id, message, generator::trim); break;
                case GEN_TRIM2:       genTrim(id, message, generator::trim2); break;
                case GEN_TRIM_RESET:  genCommand(id, message, generator::trimReset); break;
                case GEN_STATE:       genState(id, message); break;
                case GEN_PLAY_FILE:   genPlayFile(id, message); break;
                case GEN_STOP_FILE:   genCommand(id, message, generator::stopFile); break;
                case GEN_CLOSE:       genCommand(id, message, generator::close); break;
                case QA40X_INFO:      answer(id, qa40xSession.info()); break;
                case QA40X_RANGES:    answer(id, qa40xSession.ranges()); break;
                case QA40X_CALIBRATION: answer(id, qa40xSession.calibration()); break;
                case QA40X_SET_INPUT_RANGE:  qa40xRange(id, message, true); break;
                case QA40X_SET_OUTPUT_RANGE: qa40xRange(id, message, false); break;
                case QA40X_SETTINGS:  qa40xSettings(id, message); break;
                default:
                    fail(id, ErrorCode.UNSUPPORTED,
                            "unsupported message type: " + message.getT());
                    break;
            }
        } catch (NetException e) {
            fail(id, e);
        } catch (Throwable t) {
            // Throwable: this is where an answer LEAVES the server, so a fault has
            // to become an error response rather than a dead session.  A handler
            // that reaches hardware reaches JNA, and JNA raises an Error out of a
            // native call on a device that has gone - which used to walk past this
            // guard, leave the request unanswered for ever, and take the session's
            // request thread with it.
            if (log.isErrorEnabled()) {
                log.error("net session {}: {} failed", clientName, type, t);
            }
            fail(id, ErrorCode.INTERNAL, "server error: " + t);
        }
    }

    /**
     * The version negotiation of spec 1: the client offers the range
     * {@code [protoMin..proto]} (an absent {@code protoMin} means the range is
     * just {@code proto}), the server intersects it with its own and answers
     * with the HIGHEST version in both - which then governs the session.  An
     * empty intersection is {@code PROTO_MISMATCH}, names both ranges, and
     * closes: there is no version in which the two could go on talking.
     */
    private void hello(int id, NetMessage message) {
        if (proto != NO_PROTO) {
            fail(id, ErrorCode.BAD_REQUEST, "hello was already answered");
            return;
        }
        Integer clientMax = message.optInt(NetFields.PROTO);
        if (clientMax == null) {
            fail(id, ErrorCode.BAD_REQUEST, "hello without proto");
            return;
        }
        Integer offeredMin = message.optInt(NetFields.PROTO_MIN);
        int clientMin = offeredMin == null ? clientMax : offeredMin;
        int chosen = Math.min(clientMax, NetProto.PROTO_VERSION);
        if (chosen < Math.max(clientMin, NetProto.PROTO_MIN_VERSION)) {
            channel.send(new NetMessage(id, new NetError(ErrorCode.PROTO_MISMATCH,
                    "client speaks proto " + clientMin + ".." + clientMax
                            + ", server speaks " + NetProto.PROTO_MIN_VERSION + ".."
                            + NetProto.PROTO_VERSION)));
            close("protocol version mismatch");
            return;
        }
        String offeredName = message.optString(NetFields.NAME);
        clientName = offeredName == null || offeredName.isBlank()
                ? UNNAMED_CLIENT : offeredName;
        proto = chosen;
        if (log.isInfoEnabled()) {
            log.info("net session: {} ({}) accepted at proto {} of {}..{}",
                    clientName, message.optString(NetFields.CLIENT), chosen,
                    clientMin, clientMax);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(NetFields.PROTO, chosen);
        data.put(NetFields.SERVER_ID, config.getServerId());
        data.put(NetFields.NAME, config.getName());
        data.put(NetFields.APP, config.getApp());
        data.put(NetFields.CAPS, caps());
        channel.send(new NetMessage(id, codec.toNode(data)));
    }

    /** Spec 4.1: answer, then release everything and close. */
    private void bye(int id) {
        channel.send(new NetMessage(id));
        close("bye");
    }

    /** Spec 4.3: the server's backends, named and flagged, with no device detail
     *  - what a client's backend combo is built from before it has picked one. */
    private void backendList(int id) {
        channel.send(new NetMessage(id,
                codec.toNode(Map.of(NetFields.BACKENDS, catalog.backends()))));
    }

    /**
     * Spec 4.3: this connection commits to one backend, and is answered with
     * that backend's whole {@code devices.list} entry so a single round-trip
     * fills its device combos.
     *
     * <p>The choice is the SESSION's.  Another connection measuring on another
     * backend of the same bench is unaffected - there is no server-wide active
     * backend to flip, and flipping one would tear that other client's stream
     * down.  Re-selecting simply switches; the locks this connection already
     * holds stay valid, because a lock is on a device (spec 4.3), not on a
     * selection, and dropping them here would hand a running measurement's input
     * to whoever asked next.
     */
    private void backendSelect(int id, NetMessage message) {
        String name = message.optString(NetFields.BACKEND);
        if (name == null) {
            fail(id, ErrorCode.BAD_REQUEST, "backend.select needs a backend");
            return;
        }
        AudioBackendType chosen;
        try {
            chosen = AudioBackendType.valueOf(name);
        } catch (IllegalArgumentException e) {
            fail(id, ErrorCode.BAD_REQUEST, "unknown backend: " + name);
            return;
        }
        JsonNode entry = catalog.backendEntry(chosen);
        selectedBackend = chosen;
        if (log.isInfoEnabled()) {
            log.info("net session {}: backend {} selected", clientName, chosen);
        }
        channel.send(new NetMessage(id, entry));
    }

    /** Spec 4.3: everything this server has, with each device's formats inlined
     *  and the current lock state overlaid.  A fresh enumeration, on this
     *  connection's own thread - the client asked, so the client waits.  The
     *  ask is the operator's scan gesture, so the snapshot backends rebuild
     *  first (see {@link DeviceCatalog#refreshSnapshotBackends()}). */
    private void devicesList(int id) {
        catalog.refreshSnapshotBackends();
        channel.send(new NetMessage(id,
                codec.toNode(Map.of(NetFields.BACKENDS, catalog.scan()))));
    }

    /** Spec 4.3: an exclusive lock on the device and direction, or
     *  {@code DEVICE_LOCKED} naming the client that holds it.  The ref's name is
     *  validated first - a lock on an index that now belongs to another device
     *  would be granted happily and go wrong only later, at the open. */
    private void deviceAcquire(int id, NetMessage message) {
        DeviceLock lock = deviceOf(message);
        catalog.resolve(lock, message.optString(NetFields.NAME));
        ClientSession holder = locks.acquire(lock, this);
        if (holder != null) {
            channel.send(new NetMessage(id, new NetError(ErrorCode.DEVICE_LOCKED,
                    lock + " is in use", holder.getClientName())));
            return;
        }
        channel.send(new NetMessage(id));
    }

    /**
     * Spec 4.3: gives the device back and "also closes any open stream/generator
     * on it"; {@code NOT_LOCKED} when this connection never held it.  The stream
     * and the generator go first, so the device line is already back in the OS
     * when the lock frees and the next client is told the device is available.
     *
     * <p>The ref's name is deliberately NOT validated here.  The lock is keyed
     * on the index the client sent, so releasing it always does the right thing,
     * and refusing a release after a hot-plug would strand the device with a
     * client that has no way left to let go of it.
     */
    private void deviceRelease(int id, NetMessage message) {
        DeviceLock lock = deviceOf(message);
        if (locks.owner(lock) != this) {
            fail(id, ErrorCode.NOT_LOCKED, lock + " is not locked by this connection");
            return;
        }
        captures.closeDevice(lock);
        generator.closeDevice(lock);
        locks.release(lock, this);
        channel.send(new NetMessage(id));
    }

    /**
     * Spec 4.3's {@code device.setCalibration}: the two full-scale RMS volts go
     * into THIS server's card for that device and direction, and every client's
     * {@code cal} view refreshes.
     *
     * <p>Calibration lives where the device is connected, so a client measuring
     * across the room writes the bench's card rather than its own - which is why
     * the ref's name is validated like an acquire's ({@code DEVICE_STALE} on an
     * index that moved): a calibration written onto whatever device now sits at
     * that index would mis-scale every later measurement on it, silently.
     *
     * <p>The device lock is required for the same reason the analyzer's range
     * writes are (spec 4.6): it changes what every measurement on that device
     * MEANS, and doing that underneath the client that is measuring is the one
     * failure the lock exists to prevent.  A card whose calibration comes from
     * the device itself refuses outright - a QA40x's full-scales are read from
     * its EEPROM and are not a client's to write.
     *
     * <p>The broadcast goes through the one {@code ev.devices.changed} fan-out a
     * lock change uses, so a client's cached catalogue carries the new values
     * with no request of its own.
     */
    private void deviceSetCalibration(int id, NetMessage message) {
        DeviceLock lock = deviceOf(message);
        String name = message.optString(NetFields.NAME);
        catalog.resolve(lock, name);
        if (locks.owner(lock) != this) {
            fail(id, ErrorCode.NOT_LOCKED,
                    lock + " must be acquired before its calibration can be written");
            return;
        }
        Double left = message.optDouble(NetFields.FS_RMS_LEFT);
        Double right = message.optDouble(NetFields.FS_RMS_RIGHT);
        if (left == null || right == null) {
            fail(id, ErrorCode.BAD_REQUEST,
                    "device.setCalibration needs both fsRmsLeft and fsRmsRight");
            return;
        }
        if (!catalog.storeCalibration(lock, name, left, right)) {
            fail(id, ErrorCode.BAD_REQUEST, lock + " reads its calibration from the "
                    + "device itself, so it is not a client's to write");
            return;
        }
        if (log.isInfoEnabled()) {
            log.info("net session {}: {} calibrated at {} / {} Vrms", clientName, lock,
                    left, right);
        }
        locks.notifyDevicesChanged();
        channel.send(new NetMessage(id));
    }

    /**
     * Spec 4.3's {@code device.setCard}: records which of THIS server's cards the
     * operator chose for that device, and every client's {@code card} - and with
     * it the {@code cal} in force - refreshes.
     *
     * <p>The name validation, the lock and the broadcast are
     * {@link #deviceSetCalibration}'s, for the same reasons: a binding accepted
     * for whatever now sits at a moved index would mis-scale every later
     * measurement on it, and changing which card a device resolves to changes what
     * every measurement on it MEANS.
     *
     * <p>Where it deliberately differs is the {@code calibrationFromDevice} card,
     * which this ACCEPTS.  Binding chooses a card; it writes no values into one.
     * The QA402-vs-QA403 pick is the case the message exists for, and both of
     * those cards are device-calibrated - refusing them would leave the one
     * choice nobody but the operator can make unmakeable.
     */
    private void deviceSetCard(int id, NetMessage message) {
        DeviceLock lock = deviceOf(message);
        String name = message.optString(NetFields.NAME);
        catalog.resolve(lock, name);
        if (locks.owner(lock) != this) {
            fail(id, ErrorCode.NOT_LOCKED,
                    lock + " must be acquired before its card binding can be written");
            return;
        }
        String card = message.optString(NetFields.CARD);
        if (!catalog.storeCard(lock, name, card)) {
            fail(id, ErrorCode.BAD_REQUEST, "no card named '" + card + "' on this server - "
                    + "a binding may only name a card that exists");
            return;
        }
        if (log.isInfoEnabled()) {
            log.info("net session {}: {} bound to card {}", clientName, lock,
                    card == null || card.isEmpty() ? "<none>" : "'" + card + "'");
        }
        locks.notifyDevicesChanged();
        channel.send(new NetMessage(id));
    }

    /**
     * Spec 4.3's {@code device.setActiveRange}: the operator moved a bench card's
     * active range - the row whose full scale is IN FORCE - from a client.
     *
     * <p>Gated exactly like {@link #deviceSetCalibration}: the ref's name is
     * validated ({@code DEVICE_STALE}) and the device lock is required, because
     * moving the active row changes what every measurement on that device means
     * just as surely as writing new values into it does.  It broadcasts for the
     * same reason: the {@code cal} every client holds for the device is the active
     * row's pair, so it has just changed everywhere.
     *
     * <p>The QA40x is deliberately NOT re-ranged through here - its attenuator is
     * the DEVICE's own state and spec 4.6 owns it ({@code qa40x.setInputRange}).
     * This is for an ordinary card whose rows describe a switch nobody but the
     * operator can throw.
     */
    private void deviceSetActiveRange(int id, NetMessage message) {
        DeviceLock lock = deviceOf(message);
        String name = message.optString(NetFields.NAME);
        catalog.resolve(lock, name);
        if (locks.owner(lock) != this) {
            fail(id, ErrorCode.NOT_LOCKED,
                    lock + " must be acquired before its active range can be moved");
            return;
        }
        String label = message.optString(NetFields.RANGE);
        if (label == null || label.isEmpty()) {
            fail(id, ErrorCode.BAD_REQUEST, "device.setActiveRange needs a range");
            return;
        }
        String scope = message.optString(NetFields.CHANNEL);
        Channel side = channelOf(scope);
        if (side == null && scope != null && !NetFields.BOTH.equals(scope)) {
            fail(id, ErrorCode.BAD_REQUEST, "unknown channel '" + scope + "' - a range "
                    + "moves for 'both', 'left' or 'right'");
            return;
        }
        if (!catalog.storeActiveRange(lock, name, label, side)) {
            fail(id, ErrorCode.BAD_REQUEST, "no card in force for " + lock
                    + " has a range named '" + label + "'");
            return;
        }
        if (log.isInfoEnabled()) {
            log.info("net session {}: {} active range is now '{}' ({})", clientName, lock,
                    label, scope == null ? NetFields.BOTH : scope);
        }
        locks.notifyDevicesChanged();
        channel.send(new NetMessage(id));
    }

    /** The {@code channel} scope of {@code device.setActiveRange} as the store's
     *  own enum: null for {@code both} (and for an absent field), which is what a
     *  LINKED or MONO endpoint's single marker means. */
    private Channel channelOf(String scope) {
        if (NetFields.LEFT.equals(scope)) {
            return Channel.L;
        }
        return NetFields.RIGHT.equals(scope) ? Channel.R : null;
    }

    /**
     * Spec 4.3's {@code cards.put}: a client hands this server a whole card for a
     * device plugged in here - the propagation half of "calibration lives where
     * the device is connected".
     *
     * <p>No lock: the card is not bound to anything yet, so nothing it says is in
     * force and no measurement can change under anyone.  No broadcast either, for
     * the same reason - the {@code device.setCard} a client sends next is what puts
     * the card in force, and that one does broadcast.
     */
    private void cardsPut(int id, NetMessage message) {
        catalog.createCard(codec.toMap(message.getNode(NetFields.CONTENT)));
        if (log.isInfoEnabled()) {
            log.info("net session {}: card '{}' stored on this bench", clientName,
                    message.getNode(NetFields.CONTENT).path(NetFields.NAME).asText());
        }
        channel.send(new NetMessage(id));
    }

    /** Spec 4.3's {@code cards.list}: the server's cards by logical name, which is
     *  what a client's binding chooser offers.  A plain read - no lock, no device:
     *  choosing is a decision the operator makes BEFORE taking anything, and
     *  needing the device to see the choices would be backwards. */
    private void cardsList(int id) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(NetFields.CARDS, catalog.cards());
        channel.send(new NetMessage(id, codec.toNode(data)));
    }

    /** Spec 4.4: opens the stream and answers the format actually granted.  The
     *  lock check lives here, not in the streamer: who owns what is between this
     *  session and the registry. */
    private void captureOpen(int id, NetMessage message) {
        DeviceLock lock = deviceOf(message);
        requireSelectedBackend(lock);
        if (locks.owner(lock) != this) {
            fail(id, ErrorCode.NOT_LOCKED,
                    lock + " must be acquired before it can stream");
            return;
        }
        channel.send(new NetMessage(id, captures.open(lock,
                message.optString(NetFields.NAME),
                message.optInt(NetFields.RATE), message.optInt(NetFields.BITS))));
    }

    /** The three commands of spec 4.4 that name nothing but a {@code captureId}
     *  and answer an empty payload: start, stop and close. */
    private void captureCommand(int id, NetMessage message, Consumer<Integer> command) {
        command.accept(message.optInt(NetFields.CAPTURE_ID));
        channel.send(new NetMessage(id));
    }

    /** Spec 4.5: opens the server-side playback and creates the generator.  It
     *  is gated exactly like {@code capture.open} - spec 4.3 names both as the
     *  calls whose device ref must match the selected backend, and spec 4.5
     *  requires the output lock for every {@code gen.*}. */
    private void genOpen(int id, NetMessage message) {
        DeviceLock lock = deviceOf(message);
        requireSelectedBackend(lock);
        if (locks.owner(lock) != this) {
            fail(id, ErrorCode.NOT_LOCKED,
                    lock + " must be acquired before the generator can drive it");
            return;
        }
        channel.send(new NetMessage(id, generator.open(lock,
                message.optString(NetFields.NAME), message.optInt(NetFields.RATE),
                message.optInt(NetFields.BITS), message.optDouble(NetFields.DITHER_BITS),
                message.optString(NetFields.OUTPUT_CHANNELS))));
    }

    /** Spec 4.5: the partial update.  The whole message goes down - which fields
     *  are present IS the payload, so nothing may be unpacked into defaults on
     *  the way. */
    private void genConfig(int id, NetMessage message) {
        generator.config(message.optInt(NetFields.GEN_ID), message);
        channel.send(new NetMessage(id));
    }

    /** The commands of spec 4.5 that name nothing but a {@code genId} and answer
     *  an empty payload: start, stop, trimReset, stopFile and close. */
    private void genCommand(int id, NetMessage message, Consumer<Integer> command) {
        command.accept(message.optInt(NetFields.GEN_ID));
        channel.send(new NetMessage(id));
    }

    private void genFftGrid(int id, NetMessage message) {
        generator.fftGrid(message.optInt(NetFields.GEN_ID),
                message.optInt(NetFields.FFT_SIZE),
                message.optBoolean(NetFields.SNAP_ENABLED));
        channel.send(new NetMessage(id));
    }

    /** {@code gen.trim} and {@code gen.trim2} - the same message shape aimed at
     *  a different tone. */
    private void genTrim(int id, NetMessage message, BiConsumer<Integer, Double> command) {
        command.accept(message.optInt(NetFields.GEN_ID), message.optDouble(NetFields.HZ));
        channel.send(new NetMessage(id));
    }

    /** Spec 4.5: "pull the full state (same payload as {@code ev.gen.state})". */
    private void genState(int id, NetMessage message) {
        channel.send(new NetMessage(id,
                generator.state(message.optInt(NetFields.GEN_ID))));
    }

    private void genPlayFile(int id, NetMessage message) {
        generator.playFile(message.optInt(NetFields.GEN_ID),
                message.optString(NetFields.FILE_ID),
                message.optBoolean(NetFields.LOOP));
        channel.send(new NetMessage(id));
    }

    // ------------------------------ QA40x - 4.6 ------------------------------

    /** A response that is nothing but its payload - the read-only calls of spec
     *  4.6, whose whole answer is what the analyzer said. */
    private void answer(int id, JsonNode data) {
        channel.send(new NetMessage(id, data));
    }

    /**
     * {@code qa40x.setInputRange} / {@code qa40x.setOutputRange}: the analyzer's
     * attenuator moves, so the lock is required - spec 4.6 marks only the reads
     * read-only, and a client that moved the range under another client's running
     * measurement would silently change what that one is measuring.
     *
     * <p>And every client is told, through the one {@code ev.devices.changed}
     * fan-out a lock change uses.  The range in force IS the device's full scale:
     * the analyzer's card carries one calibrated row per attenuator position, and
     * the {@code cal} of spec 4.3 reports whichever row is active.  Without the
     * broadcast a client would go on scaling its measurements by the range the
     * analyzer has LEFT until something unrelated - a lock taken, a device
     * unplugged - happened to refresh its catalogue, and nothing on its screen
     * would say the numbers had stopped meaning volts.
     */
    private void qa40xRange(int id, NetMessage message, boolean input) {
        requireQa40xLock();
        qa40xSession.setRange(input, message.optInt(NetFields.DBV));
        locks.notifyDevicesChanged();
        channel.send(new NetMessage(id));
    }

    /** {@code qa40x.settings}: a get (no field) is read-only and free; a set
     *  moves the front-panel I2S port, which changes the supported sample widths
     *  of a device somebody may be streaming - so it needs the lock. */
    private void qa40xSettings(int id, NetMessage message) {
        Boolean i2sEnabled = message.optBoolean(NetFields.I2S_ENABLED);
        if (i2sEnabled != null) {
            requireQa40xLock();
        }
        answer(id, qa40xSession.settings(i2sEnabled));
    }

    /** Spec 4.6: "requires the QA40x lock (either direction) unless marked
     *  read-only".  Either direction, because the analyzer is ONE device with one
     *  attenuator pair - holding its input is enough to be the client whose
     *  measurement a range change belongs to. */
    private void requireQa40xLock() {
        if (!locks.heldBy(AudioBackendType.QA40X, this)) {
            throw new NetException(ErrorCode.NOT_LOCKED,
                    "the QA40x must be acquired before its settings can be changed");
        }
    }

    /**
     * Spec 4.1's capability list for THIS server: the two every server serves,
     * plus {@code qa40x} when it really has an analyzer backend to serve it with.
     *
     * <p>Advertised per host rather than per build because that is what the token
     * means to a client - spec 4.6 lets it send {@code qa40x.*} the moment it
     * reads the token, and a build that ships the QA40x module on a machine where
     * {@code libusb} never loaded has nothing behind it.
     */
    private List<String> caps() {
        if (!qa40xSession.isServed()) {
            return CAPS_SERVED;
        }
        List<String> caps = new ArrayList<>(CAPS_SERVED);
        caps.add(NetFields.CAP_QA40X);
        return caps;
    }

    /**
     * Spec 4.3: "after selection, device refs in {@code capture.open}/
     * {@code gen.open} MUST name the selected backend, else error
     * {@code BACKEND_MISMATCH}".
     *
     * <p>Only AFTER a selection: a connection that never sent
     * {@code backend.select} works straight off {@code devices.list}, which is
     * the walkthrough spec 6 opens with and what the older clients do.  The check
     * is here, beside the lock check, because both answer the same question -
     * may THIS connection open THIS device - and neither is the streamer's
     * business.
     */
    private void requireSelectedBackend(DeviceLock device) {
        if (selectedBackend != null && device.backend() != selectedBackend) {
            throw new NetException(ErrorCode.BACKEND_MISMATCH,
                    device + " is not on the selected backend " + selectedBackend);
        }
    }

    /** The device ref a request names, as the lock it identifies.  An
     *  incomplete ref, or a backend name this build does not know, is the
     *  client's mistake - {@code BAD_REQUEST}, spec 4.2. */
    private DeviceLock deviceOf(NetMessage message) {
        try {
            return DeviceLock.fromMessage(message);
        } catch (IllegalArgumentException e) {
            throw new NetException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * One keepalive period, on the scheduler shared by every session - so it
     * takes no lock and waits for nothing: a session that blocked here would
     * stop the pings of all the others and kill them too.
     *
     * <p>The count is checked BEFORE the next ping goes out, so four unanswered
     * pings (2 s of silence) are declared dead half a period later - the
     * "≤ 2.5 s" the spec's walkthrough promises other clients waiting for the
     * devices.
     */
    private void keepaliveTick() {
        if (closed.get()) {
            return;
        }
        try {
            int unanswered = pingCounter - lastAnsweredPing.get();
            if (unanswered >= NetProto.MAX_MISSED_PINGS) {
                if (log.isWarnEnabled()) {
                    log.warn("net session {}: {} pings unanswered - connection dead",
                            clientName, unanswered);
                }
                close("keepalive timeout");
                return;
            }
            pingCounter++;
            channel.send(new NetMessage(MessageType.PING, -pingCounter));
        } catch (Throwable t) {
            // Throwable, because of where this runs: the scheduler repeats it at a
            // fixed rate, and ANY escaping throwable cancels a repeating task for
            // good - silently.  This session would then never ping again and never
            // be declared dead, so the devices it holds would stay held while the
            // client that holds them is already gone.  The ticker guards the same
            // boundary from its own side; this one keeps the failure attributable
            // to the session it happened in.
            if (log.isWarnEnabled()) {
                log.warn("net session {}: keepalive failed: {}", clientName, t.toString());
            }
            close("keepalive failed");
        }
    }

    /**
     * The client was heard from - ANY frame it sent, not only an answer to a ping.
     *
     * <p><b>Why any frame counts.</b>  A web client was killed repeatedly while
     * it was demonstrably alive and talking.
     * The server log says what happened: {@code net capture 1: 47784 stereo
     * frame(s) dropped - the client is not keeping up}, then 36924 more, then
     * {@code 4 pings unanswered - connection dead}.  A browser delivers WebSocket
     * messages IN ORDER, so a client that has fallen behind on the AUDIO lane has
     * the server's ping sitting behind thousands of binary frames in its own
     * receive queue - and answers it seconds late however promptly it handles it.
     * Counting only ping answers therefore measures the client's INTAKE RATE, not
     * whether it is alive, and it kills the exact client that is working hardest.
     *
     * <p>Its own pings are the honest signal: they are produced by a timer of the
     * client's, not by its receive backlog, so they keep arriving on time while it
     * is digesting a flood - and they stop the moment it really goes.  A client
     * that sends nothing at all is still dead at the same 2 s (spec 4.1's promise
     * to the other clients waiting for its devices is unchanged).
     *
     * <p>Written as "nothing is outstanding" rather than a counter of its own: the
     * death test is {@code pingCounter - lastAnsweredPing}, and the keepalive tick
     * is the only writer of {@code pingCounter}, so levelling the two here says
     * exactly what was learned - this client is current - without a second piece
     * of state that could disagree with the first.
     */
    private void heard() {
        lastAnsweredPing.accumulateAndGet(pingCounter, Math::max);
    }

    /** The client's answer to a server request - only {@code ping} exists in
     *  v1, and its id says WHICH ping it answers (spec 4.0: negative).  Only the
     *  newest one moves the mark, so an answer that arrived after four later
     *  pings went unanswered cannot resurrect a connection that is 2 s behind. */
    private void onResponse(NetMessage message) {
        Integer id = message.getId();
        if (id == null || id >= 0) {
            return;
        }
        lastAnsweredPing.accumulateAndGet(-id, Math::max);
    }

    /**
     * Ends the session and gives everything back: the keepalive stops, the
     * transport closes, and the request thread runs the {@link #teardown} that
     * hands the streams, the generator and the hardware back and frees the locks
     * (which grays the devices out for every other client) - in that order and
     * on that thread, for the reasons the teardown gives.
     *
     * <p>Callable from ANY thread and idempotent - {@code bye}
     * on the session's own thread, a dead keepalive on the scheduler and the
     * transport's close callback all land here, in any order, and none of them
     * waits for the others.
     */
    public void close(String reason) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ticker.stop();
        // The keepalive's clock becomes the teardown's watchdog - the same one
        // ticker, because this session has exactly one thing left to be timed.
        // Armed BEFORE the teardown is queued, so a worker thread that is already
        // wedged cannot get between the two.
        ticker.start(TEARDOWN_WATCHDOG_MS, this::teardownOverdue);
        // The WHOLE teardown is ENQUEUED, not run here.  It stops a render
        // thread, closes device lines and writes the analyzer's safe state, and
        // the threads that reach this method are shared by every connection -
        // the keepalive scheduler that declared this client dead, and the
        // transport's close callback.  A session's own thread is where its
        // device work belongs, and an ordinary shutdown lets what is already
        // queued finish, so the task really does run.
        worker.submit(() -> teardown(reason));
        worker.shutdown();
        channel.close(reason);
    }

    /**
     * The teardown did not finish in {@link #TEARDOWN_WATCHDOG_MS}: the locks
     * come back anyway.
     *
     * <p>There is one thing a session's own thread cannot do, and that is give
     * anything back while it is stuck inside a device call that never returns -
     * a libusb transfer on an analyzer that was unplugged is exactly such a call,
     * and the worker is a single thread, so the teardown is queued BEHIND it for
     * ever.  The bench then shows a lock held by a client that has been gone for
     * minutes ("DEVICE_LOCKED - held by ..." surviving a client restart), and no
     * connection can acquire that device again short of restarting the server.
     *
     * <p>So the lock is taken back here, off the wedged thread, and nothing else
     * is: the line is NOT closed and the analyzer is NOT parked from this
     * thread - both of those are device calls, and the device is what is stuck.
     * The teardown still owns them and still runs them if it ever comes back;
     * {@link Qa40xGuard#parkIfIdle()} asks the registry first, so a park arriving
     * that late can no longer touch an analyzer another client has since taken.
     *
     * <p>It stops its own ticker first, which is what makes this repeating task a
     * one-shot: the locks are freed once, and a teardown that stays stuck does
     * not go on logging about it every five seconds.
     */
    private void teardownOverdue() {
        ticker.stop();
        int freed = locks.releaseAll(this);
        if (freed > 0 && log.isErrorEnabled()) {
            log.error("net session {}: the teardown was still running after {} ms - "
                    + "{} lock(s) taken back without it, so the device is usable "
                    + "again; its close is still queued on a thread that is not "
                    + "answering", clientName, TEARDOWN_WATCHDOG_MS, freed);
        }
    }

    /**
     * The teardown of spec 4.1, in the ONE order it may run in, on this
     * session's own thread: the capture streams stop, THEN the generator gives
     * its render thread and its DAC line back, THEN the locks are freed (which
     * broadcasts {@code ev.devices.changed} and lets the next client take the
     * device), and only then is the analyzer parked.
     *
     * <p>Every step of that order is a hardware fact rather than a preference.
     * A lock freed while this connection's play thread is still inside the
     * render loop hands another client a device whose line is still open - an
     * exclusive-mode open fails outright, and a shared-mode one silently
     * delivers zeros.  And the park ("park hardware (QA40x attenuator safe)")
     * writes registers and releases the transport, so it may only happen once
     * nothing of this session's still drives the analyzer AND the registry says
     * nobody else holds it.
     *
     * <p>Which is why each step is guarded SEPARATELY.  One try around the whole
     * sequence would let a capture that refused to close skip the generator
     * entirely and then park the analyzer and free the locks anyway - handing
     * the next client a device whose play thread is still rendering into an open
     * line, the exact sequence the order above exists to prevent.
     */
    private void teardown(String reason) {
        try {
            step(captures::closeAll, "closing the capture streams");
            step(generator::closeAll, "closing the generator");
        } finally {
            // The locks come back even from a close that blew up.  A device whose
            // line refused to shut is a bench problem; a lock left behind by the
            // client that died holding it is a device no other connection can ever
            // acquire again short of restarting the server.
            //
            // In a finally, and not merely after the two steps, because the code
            // has to mean that whatever the guards above learn to let through:
            // they did let one through - an Error out of a native close walked
            // past a catch on RuntimeException, and with it went the release AND
            // the park, which is the bench's stuck lock and its unparked analyzer
            // in one line of the log that was never written.
            ticker.stop();                       // the watchdog has nothing left to do
            int freed = locks.releaseAll(this);
            step(qa40x::parkIfIdle, "parking the QA40x");
            if (log.isInfoEnabled()) {
                log.info("net session {} closed ({}), {} lock(s) freed",
                        clientName, reason, freed);
            }
        }
    }

    /** One teardown step, guarded: what it hands back is given back whether or
     *  not the step before it succeeded, and the ORDER survives a failure.
     *
     *  <p>Throwable rather than RuntimeException: the steps end in native device
     *  closes, and a JNA invocation on a device that has been unplugged raises an
     *  Error - the one failure a teardown must survive, and the one this guard
     *  used to let past. */
    private void step(Runnable action, String what) {
        try {
            action.run();
        } catch (Throwable t) {
            if (log.isErrorEnabled()) {
                log.error("net session {}: {} failed - the teardown goes on",
                        clientName, what, t);
            }
        }
    }

    /**
     * Waits up to {@code timeoutMs} for the teardown {@link #close(String)}
     * started to finish.
     *
     * <p>The server's own shutdown is the caller: spec 4.1 names it as one of
     * the ends the teardown is reached from, and a JVM that exited while the
     * task was still queued would kill the session's daemon thread in the
     * middle of it - the DAC line left open and the analyzer never parked,
     * which is exactly what a {@code SIGTERM} on a bench must not do.
     */
    public void awaitClosed(long timeoutMs) {
        worker.awaitShutdown(timeoutMs);
    }

    /** Pushes a server-initiated event (spec 4.0: no id, never answered) to this
     *  client - the broadcast path {@link WsFront} fans out.  A closed session
     *  drops it: the connection is already gone. */
    public void send(NetMessage event) {
        if (!closed.get()) {
            channel.send(event);
        }
    }

    private void fail(int id, ErrorCode code, String message) {
        if (log.isDebugEnabled()) {
            log.debug("net session {}: {} - {}", clientName, code, message);
        }
        channel.send(new NetMessage(id, new NetError(code, message)));
    }

    /** The same refusal, keeping the failure REASON a device fault carried: the
     *  client's operator is told why in the client's own language, not in the
     *  server's (spec 4.2 - an UNKNOWN reason stays off the wire). */
    private void fail(int id, NetException refusal) {
        if (log.isDebugEnabled()) {
            log.debug("net session {}: {} - {}", clientName, refusal.getCode(), refusal.getMessage());
        }
        channel.send(new NetMessage(id,
                new NetError(refusal.getCode(), refusal.getMessage(), refusal.getReason())));
    }
}
