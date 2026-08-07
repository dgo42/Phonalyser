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

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.edgo.audio.measure.net.proto.ErrorCode;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetMessage;
import org.edgo.audio.measure.net.proto.NetProto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Both halves of the keepalive contract of spec 4.1, from the client's side: it
 * pings every 500 ms and gives up after four unanswered ones, and it answers the
 * server's pings so the server does not give up on IT.  The two rules of spec 1
 * that need the same kind of peer live here too - the chosen version binds the
 * session, and a request this build does not know is still ANSWERED.
 *
 * <p><b>Why the peer here is scripted and not the real server.</b>  A correct
 * server always answers, so it cannot produce the one condition this test is
 * about - a socket that is still up while the far end has gone quiet.  Stopping
 * the real server instead closes the transport, which is a different ending with
 * a different reason, and a client that only ever saw THAT ending would pass this
 * test while being blind to the silent bench spec 4.1 exists for.  Everything
 * else about the client is the shipping code; see {@link NetLoopbackTest} for the
 * runs against the real server.
 *
 * <p><b>No test sleeps.</b>  Time reaches the connection through an injected
 * {@link Ticker}, so the 2 s death of spec 4.1 is five method calls.
 */
class NetKeepaliveTest {

    private static final String CLIENT_NAME = "Developer's laptop";
    private static final String CLIENT_APP = "Phonalyser desktop test";
    /** How long any wait on the loopback may take before it counts as a hang. */
    private static final long AWAIT_MS = 10_000;
    /** The tick that DECLARES the death: the count is checked before the next
     *  ping goes out, so the four unanswered ones are noticed on the fifth. */
    private static final int TICKS_TO_DEATH = NetProto.MAX_MISSED_PINGS + 1;
    /** Spec 4.0: a server request id is negative so it cannot collide with a
     *  client's.  A number with no other meaning - that is the whole point. */
    private static final int SERVER_PING_ID = -7;
    /** The id of the request from the future, likewise negative. */
    private static final int SERVER_UNKNOWN_ID = -11;
    /** A request type no version of this protocol has ever had - what a v2 server
     *  asking for something new looks like to this build. */
    private static final String FUTURE_REQUEST = "server.rollcall";
    /** The envelope keys of spec 4.0; only this test builds a message by hand, so
     *  they are not worth a constant in the wire format itself. */
    private static final String FIELD_TYPE = "t";
    private static final String FIELD_ID = "id";
    /** A connect budget short enough that a peer which never answers cannot hide
     *  behind the request timeout - what the Preferences dialog spends. */
    private static final long CONNECT_BUDGET_MS = 300;
    /** What "it honoured the budget" means: comfortably under the 10 s request
     *  timeout, which is the wait this test exists to rule out. */
    private static final long CONNECT_CEILING_MS = 5_000;
    private static final long NANOS_PER_MS = 1_000_000;

    private final JsonCodec codec = new JsonCodec();
    private final FakeTicker keepalive = new FakeTicker();

    private ScriptedServer peer;
    private NetConnection connection;

    @BeforeEach
    void connectToThePeer() {
        peer = new ScriptedServer(codec);
        URI uri = peer.listen(AWAIT_MS);
        connection = new NetConnection(uri, CLIENT_NAME, CLIENT_APP, codec, keepalive);
        connection.open(AWAIT_MS);
        assertTrue(connection.isOpen());
        assertEquals(NetProto.PROTO_VERSION, connection.getProto(),
                "spec 1: the hello response carries the CHOSEN session version");
        assertTrue(connection.getCaps().isEmpty(),
                "a cap is a promise, and its ABSENCE is not a refusal - a client that "
                        + "hard-required one could not talk to this peer at all");
    }

    @AfterEach
    void disconnect() {
        connection.close(NetCloseReason.BYE);
        peer.shutDown(AWAIT_MS);
    }

    @Test
    void fourUnansweredPingsEndTheSession() {
        AtomicReference<NetCloseReason> ended = new AtomicReference<>();
        connection.addCloseListener(ended::set);

        keepalive.advance(TICKS_TO_DEATH);

        assertEquals(NetCloseReason.KEEPALIVE_TIMEOUT, ended.get(),
                "spec 4.1: four consecutive unanswered pings - 2 s of silence - and the "
                        + "connection is dead, and the modules must be told WHY so they "
                        + "can stop while the measurement can still be stopped");
        assertFalse(connection.isOpen());
        assertTrue(keepalive.isStopped(), "a dead session stops pinging");

        assertThrows(IllegalStateException.class,
                () -> connection.request(connection.newRequest(MessageType.DEVICES_LIST)),
                "a request on an ended session fails at once rather than waiting for a "
                        + "timeout the bench will never answer");
    }

    @Test
    void theClientAnswersTheServersNegativeIdPings() {
        peer.push(new NetMessage(MessageType.PING, SERVER_PING_ID));

        NetMessage answer = peer.awaitReceived(MessageType.RESP, SERVER_PING_ID, AWAIT_MS);
        assertTrue(answer.isOk(),
                "spec 4.0: the server also sends REQUESTS, and the client answers them "
                        + "with the same resp envelope a client request gets - without "
                        + "which the server counts this client dead 2 s from now");
        assertTrue(connection.isOpen());
    }

    @Test
    void aServerRequestThisBuildDoesNotKnowIsAnsweredUnsupported() {
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put(FIELD_TYPE, FUTURE_REQUEST);
        request.put(FIELD_ID, SERVER_UNKNOWN_ID);

        peer.push(new NetMessage(request));

        NetMessage answer = peer.awaitReceived(MessageType.RESP, SERVER_UNKNOWN_ID, AWAIT_MS);
        assertFalse(answer.isOk());
        assertTrue(answer.getError().is(ErrorCode.UNSUPPORTED),
                "spec 1: \"Unknown message types answer error UNSUPPORTED\" - the rule is "
                        + "for BOTH sides, and a v2 server that asked this build for "
                        + "something new would otherwise wait forever for an answer: "
                        + answer);
        assertTrue(connection.isOpen(),
                "refusing one request is not a reason to end a session that works");
    }

    @Test
    void aPeerThatNeverAnswersHelloGivesUpWithinTheConnectBudget() {
        // The host answers the WebSocket handshake and then says nothing - a
        // firewall's transparent proxy, a half-started server.  The whole attempt
        // runs on the UI thread from a button, so the budget its caller asked for
        // must cover the socket AND the hello, not each of them.
        ScriptedServer mute = new ScriptedServer(codec);
        mute.setAnswerHello(false);
        NetConnection session = new NetConnection(mute.listen(AWAIT_MS), CLIENT_NAME,
                CLIENT_APP, codec, new FakeTicker());
        try {
            long startedAt = System.nanoTime();
            assertThrows(IllegalStateException.class, () -> session.open(CONNECT_BUDGET_MS));
            long spentMs = (System.nanoTime() - startedAt) / NANOS_PER_MS;

            assertTrue(spentMs < CONNECT_CEILING_MS,
                    "a connect attempt must not wait out the request timeout: a bench that "
                            + "answers nothing would freeze the Preferences dialog for it, "
                            + "and this one took " + spentMs + " ms");
            // The socket is left for the CALLER to close (both call sites do,
            // see NetBenchUi.connect / probe): only a REFUSED session closes here.
        } finally {
            session.close(NetCloseReason.BYE);
            mute.shutDown(AWAIT_MS);
        }
    }

    @Test
    void aChosenVersionThisClientNeverOfferedEndsTheSession() {
        ScriptedServer tooNew = new ScriptedServer(codec);
        tooNew.setHelloProto(NetProto.PROTO_VERSION + 1);
        NetConnection session = new NetConnection(tooNew.listen(AWAIT_MS), CLIENT_NAME,
                CLIENT_APP, codec, new FakeTicker());
        try {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> session.open(AWAIT_MS));
            assertTrue(refused.getMessage().contains(ErrorCode.PROTO_MISMATCH.name()),
                    "spec 1: the CHOSEN version governs the session and binds both sides, "
                            + "so one outside the range this build offered is a mismatch "
                            + "and not a session - " + refused.getMessage());
            assertFalse(session.isOpen(),
                    "and the socket goes with it: a session left running in a dialect "
                            + "this build cannot speak would fail later, on data");
        } finally {
            session.close(NetCloseReason.BYE);
            tooNew.shutDown(AWAIT_MS);
        }
    }
}
