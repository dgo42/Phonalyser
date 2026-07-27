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

package org.edgo.audio.measure.bind;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * An observable, mutable value — the unit of two-way binding between a settings
 * control and the {@code Preferences} model.  A parameter that lives in
 * preferences is exposed as a {@code Property}; a control binds to it (via
 * {@code Bindings}) and the view subscribes to it, so neither side ever calls
 * the other — they communicate only through this value.
 *
 * <p>{@link #set} notifies listeners ONLY when the value actually changes
 * (per {@link Objects#equals}).  That guard is what makes a two-way bind safe:
 * a control echoing back the value it was just handed sets the same value, which
 * is a no-op and fires nothing — so there is no control → property → control
 * feedback loop.
 *
 * <p>Threading contract: writes happen on the UI thread (listeners fire
 * synchronously on the mutating thread), but {@link #get()} is safe from ANY
 * thread — {@code value} is {@code volatile}, so worker threads (scope
 * measurement, FFT analysis, audio render) always observe the latest committed
 * value instead of a cached stale one.  Values must stay immutable
 * (boxed primitives, enums, strings) for that hand-off to be sound.  The
 * listener list is a {@link CopyOnWriteArrayList} so a listener may
 * subscribe / unsubscribe during a notification.  Listeners are held strongly;
 * a control binding must {@link #removeListener} on widget dispose (handled by
 * {@code Bindings}) so the property doesn't keep disposed widgets alive.
 */
public final class Property<T> {

    private final List<Consumer<T>> listeners = new CopyOnWriteArrayList<>();
    private volatile T value;

    public Property(T initial) {
        this.value = initial;
    }

    public T get() {
        return value;
    }

    /** Sets the value; on a real change (per {@link Objects#equals}) notifies
     *  every listener with the new value.  A no-op when unchanged. */
    public void set(T newValue) {
        if (Objects.equals(value, newValue)) {
            return;
        }
        value = newValue;
        for (Consumer<T> l : listeners) {
            l.accept(newValue);
        }
    }

    /** Registers a change listener, fired on every real {@link #set}.  The
     *  caller is responsible for {@link #removeListener} (typically from a
     *  widget Dispose listener) so the listener — and what it captures — is
     *  released. */
    public void addListener(Consumer<T> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<T> listener) {
        listeners.remove(listener);
    }
}
