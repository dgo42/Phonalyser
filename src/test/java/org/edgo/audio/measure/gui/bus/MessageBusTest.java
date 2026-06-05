/*
 * Phonalyser — precision audio measurement workbench.
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

package org.edgo.audio.measure.gui.bus;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Behaviour tests for the in-process {@link MessageBus}.  Verifies all
 * three event flavours (notification / payload / request-response) and
 * the lifecycle expectations (unsubscribe stops delivery; responder
 * replacement is exclusive).
 *
 * <p>{@code MessageBus} is a process-wide singleton, so each test uses
 * a unique event-name prefix to avoid cross-test interference.
 */
class MessageBusTest {

    private final MessageBus bus = MessageBus.instance();

    @Test
    void notification_subscribePublishUnsubscribe() {
        AtomicInteger calls = new AtomicInteger();
        Consumer<Void> handler = ignored -> calls.incrementAndGet();
        String ev = "test.notification.basic";

        bus.subscribe(ev, handler);
        bus.publish(ev);
        bus.publish(ev);
        assertEquals(2, calls.get(), "handler should fire once per publish");

        bus.unsubscribe(ev, handler);
        bus.publish(ev);
        assertEquals(2, calls.get(), "unsubscribed handler must not fire");
    }

    @Test
    void payload_subscribeReceivesPublishedValue() {
        AtomicReference<String> seen = new AtomicReference<>();
        Consumer<String> handler = seen::set;
        String ev = "test.payload.basic";

        bus.subscribe(ev, handler);
        bus.publish(ev, "hello");
        assertEquals("hello", seen.get());

        bus.unsubscribe(ev, handler);
        bus.publish(ev, "ignored");
        assertEquals("hello", seen.get(), "unsubscribed payload handler must not fire");
    }

@Test
    void responder_replacementIsExclusive() {
        String ev = "test.responder.replace";

        bus.registerResponder(ev, () -> 1);
        Integer first = bus.request(ev);
        assertEquals(1, first);

        // Re-registering replaces the previous responder.
        bus.registerResponder(ev, () -> 42);
        Integer second = bus.request(ev);
        assertEquals(42, second);

        bus.unregisterResponder(ev);
        Object after = bus.request(ev);
        assertNull(after, "request on unregistered responder returns null");
    }

    @Test
    void responder_withPayloadEchoes() {
        String ev = "test.responder.payload";
        Function<Integer, Integer> echo = x -> x == null ? 0 : x * 2;
        bus.registerResponder(ev, echo);

        Integer doubled = bus.request(ev, 21);
        assertEquals(42, doubled);

        bus.unregisterResponder(ev);
    }

    @Test
    void subscriberException_doesNotBreakOtherSubscribers() {
        // If one handler throws, the remaining handlers under the same
        // event name MUST still receive the publish.
        String ev = "test.exception.isolation";
        AtomicInteger goodCalls = new AtomicInteger();
        Consumer<Void> bad  = ignored -> { throw new RuntimeException("intentional"); };
        Consumer<Void> good = ignored -> goodCalls.incrementAndGet();

        bus.subscribe(ev, bad);
        bus.subscribe(ev, good);
        bus.publish(ev);
        assertEquals(1, goodCalls.get(), "well-behaved subscriber must still fire");

        bus.unsubscribe(ev, bad);
        bus.unsubscribe(ev, good);
    }

    @Test
    void request_noResponder_returnsNull() {
        Object result = bus.request("test.responder.absent.never-registered");
        assertNull(result);
    }
}
