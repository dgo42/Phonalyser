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

package org.edgo.audio.measure.net.proto;

import lombok.experimental.UtilityClass;

/**
 * The net protocol's fixed numbers, in one place - every value here is a
 * literal quotation of the wire spec (doc/NET-PROTOCOL.md), so the server, the
 * client and the tests can never disagree about a port, an interval or a limit.
 *
 * <p>Constants only, no behaviour: the code that USES a number lives with the
 * state it belongs to (the beacon sender, the session keepalive, the upload
 * handler) - this type just refuses to let those numbers be re-typed by hand.
 * The wire FIELD NAMES live next door in {@link NetFields}.
 */
@UtilityClass
public class NetProto {

    /** Highest wire-protocol version this build speaks - the value carried by
     *  {@code hello}, the beacon and {@code /info} (spec 1). */
    public static final int PROTO_VERSION = 2;

    /**
     * Lowest wire-protocol version this build still speaks.  Spec 1 negotiates
     * RANGES: each side offers {@code [protoMin..proto]} and the server picks
     * the highest version inside BOTH ranges.  Only a truly empty intersection
     * is {@code PROTO_MISMATCH}.
     *
     * <p>Equal to {@link #PROTO_VERSION}, so this build's range is {@code 2..2}
     * and a v1 peer is refused at {@code hello}.  Spec 1 calls that a hard cut
     * and gives the reason: v2 carries the audio on a SECOND connection a v1
     * peer never dials (spec 4.7) and refuses {@code capture.start} until that
     * connection has attached (spec 4.4), so there is no subset of v2 a v1 peer
     * could be served with.  The negotiation itself is untouched - it is what a
     * v3 will be agreed through; only the range moved.
     */
    public static final int PROTO_MIN_VERSION = PROTO_VERSION;

    /** Default port, ONE for both planes: the HTTP endpoints of spec 3 and the
     *  WebSocket upgrade of spec 4 are served by the same listener. */
    public static final int DEFAULT_PORT = 8377;

    /** Value of the beacon's {@code phonalyser} marker field (spec 2.1). */
    public static final int BEACON_MAGIC = 1;

    /** Discovery multicast group (spec 2.1). */
    public static final String BEACON_GROUP = "239.255.83.77";

    /**
     * Discovery multicast port (spec 2.1) - the SAME NUMBER as
     * {@link #DEFAULT_PORT}, and defined as it so the two can never drift.
     * UDP and TCP are independent port spaces, so one number serves both: the
     * datagram goes to 8377/udp and the session to 8377/tcp.
     *
     * <p>FIXED, even when {@code --port} names something else.  Discovery only
     * works if every client binds the group on the port every server announces
     * on, so this one is not the operator's to move; a server on a custom TCP
     * port still beacons here and carries its real port in the payload.
     */
    public static final int BEACON_PORT = DEFAULT_PORT;

    /** Multicast TTL - link-local only; the bridge is a LAN instrument. */
    public static final int BEACON_TTL = 1;

    /** Beacon repeat interval; also sent immediately on any change (spec 2.1). */
    public static final int BEACON_INTERVAL_MS = 500;

    /** Peer-table entry lifetime - four missed intervals, the same threshold
     *  philosophy as the keepalive (spec 2.2). */
    public static final int BEACON_EXPIRY_MS = 2_000;

    /** Single-byte discovery probe {@code '?'}; answered with one beacon. */
    public static final byte BEACON_PROBE = (byte) '?';

    /** Keepalive period, both directions (spec 4.1). */
    public static final int PING_INTERVAL_MS = 500;

    /** Unanswered pings that declare the connection dead - 2 s (spec 4.1). */
    public static final int MAX_MISSED_PINGS = 4;

    /**
     * How long a freshly upgraded connection may stay silent before the server
     * closes it (spec 4).
     *
     * <p>A connection's PLANE is decided by its first text message - {@code
     * hello} makes it the control connection, {@code capture.attach} a data one
     * - and until that message arrives the server holds no session state for it
     * at all.  This is what stops a socket that upgrades and then says nothing
     * from being held for ever: it has no keepalive to declare it dead, because
     * a keepalive belongs to a session and this is not one yet.  Long enough
     * that a client dialling its data connection over a slow link still gets to
     * send its attach.
     */
    public static final int PLANE_DECLARATION_TIMEOUT_MS = 10_000;

    /** Upload cap for {@code PUT /files}; above it the answer is 413 (spec 3). */
    public static final int MAX_UPLOAD_BYTES = 50 * 1024 * 1024;
}
