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

import lombok.extern.log4j.Log4j2;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * In-process publish / subscribe hub keyed by event name.  Names are
 * symbolic constants declared in {@link Events} — never write a string
 * literal at the call site; the constant gives compile-time safety and
 * a single place to rename / retire events.
 *
 * <p>Two flavours of event are supported:
 * <ul>
 *   <li><b>Pub / sub</b> — every subscriber is a {@link Consumer}.  For
 *       notifications without a payload, use {@code Consumer<Void>} and
 *       publish with {@link #publish(String)} — subscribers receive
 *       {@code null}.  For payload-carrying events, declare
 *       {@code Consumer<MyPayload>} and publish with
 *       {@link #publish(String, Object)}.  One payload type per event
 *       name — mixing types is a programmer error.</li>
 *   <li><b>Request / response</b> — caller invokes
 *       {@link #request(String)} / {@link #request(String, Object)} and
 *       gets a value back from a single registered responder
 *       ({@link #registerResponder(String, Supplier)} /
 *       {@link #registerResponder(String, Function)}).  Exactly one
 *       responder per event name — a second {@code registerResponder}
 *       replaces the first.  Returns {@code null} when no responder is
 *       registered.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * <p>{@code publish} dispatches synchronously on the calling thread.
 * The subscriber list is a {@link CopyOnWriteArrayList}, so a handler
 * may subscribe / unsubscribe during dispatch.  SWT discipline still
 * applies — if a publisher fires from a worker thread but the
 * subscriber touches widgets, the subscriber is responsible for
 * marshalling to the UI thread (typically with
 * {@code Display.asyncExec}).
 *
 * <h2>Lifecycle</h2>
 * <p>The bus retains a strong reference to every registered handler;
 * subscribers MUST call {@code unsubscribe} (typically from a
 * {@code Composite.addDisposeListener}) or the bus will keep them — and
 * the widgets they capture — alive forever.
 */
@Log4j2
public final class MessageBus {

    private static volatile MessageBus instance;

    /** Handler list per event name.  Each entry is a {@link Consumer}
     *  — uniform shape lets {@link #dispatch} run a single typed call
     *  per subscriber, no instanceof branching. */
    private final Map<String, List<Consumer<?>>> subscribers = new ConcurrentHashMap<>();

    /** Single responder per event name — a {@link Supplier} (no payload
     *  request) or a {@link Function} (payload-carrying request). */
    private final Map<String, Object> responders = new ConcurrentHashMap<>();

    private MessageBus() {}

    public static MessageBus instance() {
        MessageBus local = instance;
        if (local != null) return local;
        synchronized (MessageBus.class) {
            if (instance == null) instance = new MessageBus();
            return instance;
        }
    }

    // ---- Pub / sub ----------------------------------------------------------

    public <T> void subscribe(String eventName, Consumer<T> handler) {
        listFor(eventName).add(handler);
    }

    public <T> void unsubscribe(String eventName, Consumer<T> handler) {
        List<Consumer<?>> list = subscribers.get(eventName);
        if (list != null) list.remove(handler);
    }

    /** Notification — dispatches with a {@code null} payload to every
     *  subscriber.  {@code Consumer<Void>} handlers must tolerate the
     *  null. */
    public void publish(String eventName) {
        dispatch(eventName, null);
    }

    public <T> void publish(String eventName, T payload) {
        dispatch(eventName, payload);
    }

    // ---- Request / response -------------------------------------------------

    /** Registers the (single) responder that will produce a value for
     *  every future {@link #request(String)} on {@code eventName}.
     *  Replaces any previously registered responder for the same name
     *  (with a warning), so registration is exclusive. */
    public <R> void registerResponder(String eventName, Supplier<R> responder) {
        installResponder(eventName, responder);
    }

    public <T, R> void registerResponder(String eventName, Function<T, R> responder) {
        installResponder(eventName, responder);
    }

    /** Removes the responder for {@code eventName}.  After this,
     *  {@link #request(String)} on the same name returns {@code null}. */
    public void unregisterResponder(String eventName) {
        responders.remove(eventName);
    }

    /** Invokes the registered responder for {@code eventName} (if any)
     *  and returns its result.  Returns {@code null} when no responder
     *  is registered or the responder threw — exceptions are logged. */
    public <R> R request(String eventName) {
        return invokeResponder(eventName, null);
    }

    public <T, R> R request(String eventName, T payload) {
        return invokeResponder(eventName, payload);
    }

    // ---- Internal -----------------------------------------------------------

    private void installResponder(String eventName, Object responder) {
        Object prev = responders.put(eventName, responder);
        if (prev != null) {
            log.warn("Responder for {} was replaced; previous registration overridden", eventName);
        }
    }

    @SuppressWarnings("unchecked")
    private <T, R> R invokeResponder(String eventName, T payload) {
        Object r = responders.get(eventName);
        if (r == null) return null;
        try {
            if (r instanceof Supplier<?> s)      return (R) s.get();
            if (r instanceof Function<?, ?> f)   return (R) ((Function<T, R>) f).apply(payload);
        } catch (Exception ex) {
            log.warn("Responder for {} threw: {}", eventName, ex.getMessage(), ex);
        }
        return null;
    }

    private List<Consumer<?>> listFor(String eventName) {
        return subscribers.computeIfAbsent(eventName, k -> new CopyOnWriteArrayList<>());
    }

    @SuppressWarnings("unchecked")
    private <T> void dispatch(String eventName, T payload) {
        List<Consumer<?>> list = subscribers.get(eventName);
        if (list == null || list.isEmpty()) return;
        for (Consumer<?> raw : list) {
            try {
                ((Consumer<T>) raw).accept(payload);
            } catch (Exception ex) {
                log.warn("Subscriber threw on {}: {}", eventName, ex.getMessage(), ex);
            }
        }
    }
}
