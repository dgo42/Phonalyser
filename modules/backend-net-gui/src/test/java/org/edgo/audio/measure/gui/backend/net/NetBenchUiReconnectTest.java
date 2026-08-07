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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.RemoteBackendRegistry;
import org.edgo.audio.measure.net.client.NetDeviceManager;
import org.edgo.audio.measure.net.client.NetServerEntry;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Restart the server and the client comes back by itself.
 *
 * <p><b>What went wrong.</b>  A server restart left the client dead until the
 * operator opened the servers dialog and pressed Connect - and then everything
 * worked at once.  Nothing was broken; nothing was trying.
 * The session ends, the modules stop themselves bottom-up (each
 * open stream fails on its own path), and from then on the bench is simply a
 * bench nobody is talking to.
 *
 * <p><b>What is asserted, and what deliberately is not.</b>  That the session
 * comes back, that the backend combo's entries are the new session's, and that
 * the selection the operator had committed is committed again - because without
 * that last one the modules find a connected bench with no backend selected and
 * still cannot open a device, which would make the reconnect useless in exactly
 * the case it exists for.  No measurement is restarted: pressing play stays the
 * operator's move.
 *
 * <p><b>The far end is a socket</b> ({@link BenchSocket}), which is what the
 * three awkward cases need: a bench that comes back with the SAME installation
 * id after a restart, a bench that answers slowly enough for an attempt to be
 * provably in flight, and - hand-built here because it is not a Phonalyser bench
 * at all - one that accepts the socket and then says nothing.
 */
class NetBenchUiReconnectTest {

    private static final String LOOPBACK = "127.0.0.1";
    /** The bench that goes away and comes back: spec 2.1 keys a server on its
     *  installation UUID, and a restart is the SAME installation - which is what
     *  lets the committed selection be committed again on the new session. */
    private static final String BENCH_ID = "b7e0c4d2-0000-4000-8000-0000000000a1";
    /** A second installation, for the bench the operator switches to. */
    private static final String OTHER_ID = "b7e0c4d2-0000-4000-8000-0000000000a2";
    /** Long enough for several of the client's 5 s retries, so a slow CI agent
     *  fails on the behaviour and not on its own scheduling. */
    private static final long RECONNECT_WINDOW_MS = 30_000;
    /** Longer than one retry delay: what a NEGATIVE has to outlast to mean
     *  anything.  The wait ends early the moment the thing it forbids happens,
     *  so it only costs this when the code is right. */
    private static final long NO_RECONNECT_MS = 8_000;
    private static final long POLL_MS = 50;
    /** Long enough to cover a retry delay plus a whole 3 s connect timeout, so at
     *  least one attempt is provably dialled INTO the black hole and out again. */
    private static final long BLACK_HOLE_WINDOW_MS = 12_000;
    /** What a caller may wait on this UI while a dial is in flight.  Generous by
     *  two orders of magnitude against the 3 s freeze it is there to catch - a
     *  loaded agent may hiccup, a blocking connect cannot hide under this. */
    private static final long CALLER_STALL_LIMIT_MS = 250;
    /** How long the slow bench sits on its {@code hello}.  Bounded on both sides:
     *  it must stay under the client's 3 s connect timeout (or the attempt fails
     *  instead of succeeding late), and it must outlast one loopback connect -
     *  which is all the test does inside the window. */
    private static final long HELLO_HOLD_MS = 2_000;
    /** How long the bench holds its {@code backend.select}.  Long enough that the
     *  answerability check below happens well inside the window, short enough that
     *  a test which fails still fails quickly. */
    private static final long SELECT_HOLD_MS = 2_000;
    private static final long BIND_TIMEOUT_MS = 5_000;
    private static final long STOP_TIMEOUT_MS = 1_000;

    /** The name the stand-in display thread carries, so an assertion can say
     *  WHERE something ran and not merely that it ran. */
    private static final String DISPLAY_THREAD = "test-stand-in-display";

    private int benchPort;
    private BenchSocket socket;
    private BenchSocket elsewhere;
    private NetBenchUi bench;
    private ExecutorService display;
    private BackendKey previousSelection;

    @BeforeEach
    void startTheBenchAndConnect() throws IOException {
        Preferences prefs = Preferences.instance();
        prefs.setTransientMode(true);
        previousSelection = prefs.getSelectedBackend();
        benchPort = freePort();
        socket = start(BENCH_ID, benchPort);
        bench = (NetBenchUi) RemoteBackendRegistry.instance().getUi();
        // The display this run cannot create, stood in for by one thread - which
        // is what makes the split assertable at all: without it the marshal runs
        // inline and a blocking dial on "the display thread" looks exactly like a
        // blocking dial anywhere else.
        display = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, DISPLAY_THREAD);
            thread.setDaemon(true);
            return thread;
        });
        bench.setUiExecutor(display);
        assertNull(bench.connect(entry(benchPort)), "the loopback bench refused the session");
    }

    @AfterEach
    void disconnectAndStop() {
        bench.disconnect();
        bench.setUiExecutor(null);
        display.shutdownNow();
        socket.shutDown(STOP_TIMEOUT_MS);
        if (elsewhere != null) {
            elsewhere.shutDown(STOP_TIMEOUT_MS);
        }
        if (previousSelection != null) {
            Preferences.instance().setSelectedBackend(previousSelection);
        }
    }

    /**
     * The reported case: the operator restarts the server and does nothing else.
     */
    @Test
    void aServerThatComesBackIsPickedUpWithoutTheOperator() {
        BackendKey analyzer = analyzer();
        assertTrue(bench.select(analyzer), "the bench refused backend.select");
        Preferences.instance().setSelectedBackend(analyzer);

        socket.shutDown(STOP_TIMEOUT_MS);
        awaitTrue(() -> !bench.isConnected(), RECONNECT_WINDOW_MS,
                "the client never noticed the bench go away");

        // Where the state half lands: the publish is the last thing the commit
        // does, so the thread that carries it is the thread the commit ran on.
        AtomicReference<String> committedOn = new AtomicReference<>();
        Consumer<Object> watcher = ignored -> committedOn.set(Thread.currentThread().getName());
        MessageBus.instance().subscribe(Events.REMOTE_BACKENDS_CHANGED, watcher);
        socket = start(BENCH_ID, benchPort);   // the operator's restart, and nothing more

        awaitTrue(bench::isConnected, RECONNECT_WINDOW_MS,
                "the client never came back by itself - which is the whole defect: it "
                        + "worked the moment somebody pressed Connect");
        assertFalse(bench.entries().isEmpty(),
                "the combo's entries are the NEW session's, so anything showing them "
                        + "has something to show");
        MessageBus.instance().unsubscribe(Events.REMOTE_BACKENDS_CHANGED, watcher);
        assertEquals(DISPLAY_THREAD, committedOn.get(),
                "the state half ran somewhere other than the display thread - the maps "
                        + "it writes are the ones the servers dialog reads");
        awaitTrue(() -> !netManager().listInputDevices().isEmpty(), RECONNECT_WINDOW_MS,
                "the committed selection was not re-committed: the session is up, but "
                        + "the manager is routed at nothing and the modules would find "
                        + "no device to open when the operator presses play");
    }

    /**
     * The operator's own Disconnect is a decision, not a fault - and decisions
     * stay made.  A client that reconnected here would take a bench back over
     * seconds after somebody deliberately let it go.
     */
    @Test
    void theOperatorsOwnDisconnectStaysDisconnected() {
        bench.disconnect();
        assertFalse(bench.isConnected());

        assertFalse(waitedUntil(bench::isConnected, NO_RECONNECT_MS),
                "the client reconnected to a bench the operator had disconnected from");
    }

    /**
     * A manual connect during the retry window wins, and keeps winning - even
     * against an attempt that is ALREADY DIALLING.
     *
     * <p>That is the case the generation counter exists for and the one a
     * switched-off bench cannot produce: a future's {@code cancel} cannot reach a
     * task that has begun, so an attempt which succeeds AFTER the operator has
     * chosen another bench would hand back a second live session - whose close
     * listener would later tear down the one that won.  The bench on the old
     * port therefore answers its {@code hello} slowly, and the operator's connect
     * lands inside that window.
     */
    @Test
    void aManualConnectDuringTheRetryWindowCancelsIt() throws IOException {
        socket.shutDown(STOP_TIMEOUT_MS);
        awaitTrue(() -> !bench.isConnected(), RECONNECT_WINDOW_MS,
                "the client never noticed the bench go away");

        // The old bench is back on its port - but slow to answer, so the retry
        // that finds it is still in flight when the operator gives up on it.
        socket = new BenchSocket(new JsonCodec(), BENCH_ID, benchPort);
        socket.setHelloDelayMs(HELLO_HOLD_MS);
        socket.listen(BIND_TIMEOUT_MS);
        int otherPort = freePort();
        elsewhere = start(OTHER_ID, otherPort);

        socket.awaitHello(RECONNECT_WINDOW_MS);
        // The operator does not wait: they connect somewhere else, and this is
        // the newer intention.
        assertNull(bench.connect(entry(otherPort)), "the second bench refused the session");

        awaitTrue(() -> socket.closedSessions() > 0, RECONNECT_WINDOW_MS,
                "the overtaken attempt kept the session it had dialled - a second live "
                        + "connection whose close listener would later tear down the one "
                        + "the operator actually chose");
        assertFalse(waitedUntil(() -> !onPort(otherPort), NO_RECONNECT_MS),
                "the pending retry took the session back off the server the operator "
                        + "had just chosen");
        assertTrue(bench.isConnected());
        assertEquals(otherPort, bench.getConnectedServer().port());
    }

    /**
     * The worst window in practice: a bench that ANSWERS NOTHING.
     *
     * <p>A switched-off server refuses in microseconds, which is why the first
     * version of this feature looked harmless.  A black-holed one - cable out,
     * firewall dropping, VM paused - accepts the socket and never completes the
     * handshake, so every attempt rides the full {@code CONNECT_TIMEOUT_MS}.  Run
     * on the thread that owns this UI's state, that is three seconds of freeze
     * out of every five, indefinitely; run where it belongs, the caller never
     * feels it.
     *
     * <p>So this asserts both halves of that: the retries really do happen and
     * really do ride their timeout (the black hole counts the connections it
     * accepted), and the thread that armed them stays answerable throughout.
     */
    @Test
    void aBenchThatAnswersNothingIsDialledWithoutHoldingItsCaller() throws Exception {
        socket.shutDown(STOP_TIMEOUT_MS);
        awaitTrue(() -> !bench.isConnected(), RECONNECT_WINDOW_MS,
                "the client never noticed the bench go away");

        try (BlackHole hole = new BlackHole(benchPort)) {
            long slowest = 0;
            long deadline = System.currentTimeMillis() + BLACK_HOLE_WINDOW_MS;
            while (System.currentTimeMillis() < deadline) {
                // What the display thread is asked to do while a retry is in
                // flight: anything at all.  Dial the bench there and this waits
                // out the whole connect timeout behind it.
                long before = System.nanoTime();
                display.submit(() -> { }).get(CALLER_STALL_LIMIT_MS, TimeUnit.MILLISECONDS);
                slowest = Math.max(slowest, (System.nanoTime() - before) / 1_000_000L);
                Thread.sleep(POLL_MS);
            }

            assertTrue(hole.accepted() > 0,
                    "the client never retried at all against a bench that was there but "
                            + "silent - the dial has to RIDE the timeout, not skip it");
            assertTrue(slowest < CALLER_STALL_LIMIT_MS,
                    "the display thread was held for " + slowest + " ms: the blocking "
                            + "dial is running where the state lives, which is the freeze "
                            + "this split exists to remove");
            assertFalse(bench.isConnected(), "nothing ever answered, so nothing connected");
        }
    }

    /**
     * The re-commit that follows a reconnect is a round trip too - and it must not
     * be made on the thread that owns this UI's state.
     *
     * <p>{@code commitReconnect} is deliberately marshalled onto the display
     * thread, because the maps it writes are the ones the servers dialog reads.
     * Committing the operator's backend again from inside it put a
     * {@code backend.select} - up to the full request timeout - on that same
     * thread.  Worse than the connect it replaced: nobody pressed anything, so the
     * freeze arrives unannounced seconds after a bench the operator may not have
     * noticed going away, and a server that has JUST restarted is exactly the one
     * slow to answer, because it enumerates its devices to build that answer.
     *
     * <p>Deterministic, not timed: the bench holds its {@code backend.select}
     * unanswered and says so through a latch, so the check below provably happens
     * while the commit is on the wire rather than hoping it lands in a window.
     */
    @Test
    void theReCommitAfterAReconnectIsNotMadeOnTheDisplayThread() throws Exception {
        BackendKey analyzer = analyzer();
        assertTrue(bench.select(analyzer), "the bench refused backend.select");
        Preferences.instance().setSelectedBackend(analyzer);

        socket.shutDown(STOP_TIMEOUT_MS);
        awaitTrue(() -> !bench.isConnected(), RECONNECT_WINDOW_MS,
                "the client never noticed the bench go away");

        // Back on its port, and slow to answer the commit that follows.
        socket = new BenchSocket(new JsonCodec(), BENCH_ID, benchPort);
        socket.setSelectDelayMs(SELECT_HOLD_MS);
        socket.listen(BIND_TIMEOUT_MS);

        socket.awaitSelect(RECONNECT_WINDOW_MS);
        // The commit is on the wire and being held.  The display thread must still
        // answer - run inline, it would be sitting inside that round trip.
        long before = System.nanoTime();
        display.submit(() -> { }).get(CALLER_STALL_LIMIT_MS, TimeUnit.MILLISECONDS);
        long stalledMs = (System.nanoTime() - before) / 1_000_000L;

        assertTrue(stalledMs < CALLER_STALL_LIMIT_MS,
                "the display thread was held for " + stalledMs + " ms while a "
                        + "backend.select was in flight - the re-commit is running "
                        + "where this UI's state lives instead of off it");
    }

    // -------------------------------------------------------------------------
    // Harness
    // -------------------------------------------------------------------------

    /**
     * A server that is THERE and says nothing: it accepts every connection and
     * then holds it, so a client's handshake waits out its whole timeout.  The
     * sockets are kept open on purpose - closing them would turn the black hole
     * into an ordinary refusal, which is the case that never hurt.
     *
     * <p>Not a {@link BenchSocket}: what makes this case the discriminating one
     * is that nothing on the far end speaks the protocol at all.
     */
    private static final class BlackHole implements AutoCloseable {

        private final ServerSocket socket;
        private final List<Socket> held = new CopyOnWriteArrayList<>();
        private final AtomicInteger accepted = new AtomicInteger();
        private final Thread acceptor;

        private BlackHole(int port) throws IOException {
            // Bound exactly as BenchSocket binds - loopback, and with the address
            // reusable.  It used to take the WILDCARD without setReuseAddress,
            // which conflicts with the 127.0.0.1 bind the bench on this same port
            // has only just given up: the kernel holds that address briefly, and
            // this constructor then threw "Address already in use" for reasons
            // that had nothing to do with what the test was proving.
            socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(LOOPBACK, port));
            acceptor = new Thread(this::acceptForever, "test-black-hole");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        private void acceptForever() {
            while (!socket.isClosed()) {
                try {
                    held.add(socket.accept());
                    accepted.incrementAndGet();
                } catch (IOException e) {
                    return;                  // closed: the test is done with it
                }
            }
        }

        private int accepted() {
            return accepted.get();
        }

        @Override
        public void close() throws IOException {
            socket.close();
            for (Socket open : held) {
                open.close();
            }
        }
    }

    /** The bench, as the servers dialog's manual entry starts: an address, with
     *  the id left for the server's own {@code hello} to supply. */
    private NetServerEntry entry(int port) {
        return new NetServerEntry(LOOPBACK, BenchSocket.SERVER_NAME, LOOPBACK, port, true);
    }

    /** A bench of installation {@code id} listening on {@code port} - the same
     *  shape the rest of this module's loopback runs use. */
    private BenchSocket start(String id, int port) {
        BenchSocket fresh = new BenchSocket(new JsonCodec(), id, port);
        fresh.listen(BIND_TIMEOUT_MS);
        return fresh;
    }

    private BackendKey analyzer() {
        return BackendKey.of(bench.getConnectedServer().serverId(), AudioBackendType.QA40X);
    }

    private boolean onPort(int port) {
        NetServerEntry connected = bench.getConnectedServer();
        return connected != null && connected.port() == port;
    }

    private NetDeviceManager netManager() {
        return (NetDeviceManager) AudioBackend.instance().manager(AudioBackendType.NET);
    }

    /** Waits for {@code condition}, failing the test when it never holds. */
    private void awaitTrue(BooleanSupplier condition, long timeoutMs, String what) {
        if (!waitedUntil(condition, timeoutMs)) {
            throw new AssertionError(what);
        }
    }

    /** Whether {@code condition} became true inside {@code timeoutMs}.  Used for
     *  the negatives too, where ending EARLY is the failure - so a test that
     *  forbids something only pays the full wait when nothing went wrong. */
    private boolean waitedUntil(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    private int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }
}
