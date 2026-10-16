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

package org.edgo.audio.measure.preferences;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * An observable list of Load-calibration rows ({@link CalibrationEntry}) - the
 * model a pane's calibration tab renders.  Every change reaches the listeners
 * as one {@link Change} naming what changed, where and on which item: an item
 * added, removed or replaced, the list cleared, a held item's path, Active or
 * With-noise changed - and a calibration file written to disk that an item
 * references ({@link Preferences#broadcastSavedFile}), since the curve behind
 * that item is new although its path is not.  Nothing else notifies: clearing
 * an empty list, removing an absent item, setting the item already held at an
 * index and a property set to its current value are no changes.
 *
 * <p>Every mutation of the {@link List} contract reaches one of the primitives
 * overridden here: {@link AbstractList} routes add / addAll / listIterator add
 * to {@link #add(int, CalibrationEntry)}, set / replaceAll / sort to
 * {@link #set}, subList clear and listIterator remove to
 * {@link #remove(int)}, and the iterator's remove - which removeIf, removeAll
 * and retainAll use - goes through {@link #remove(Object)}.  A bulk operation
 * therefore sends one change per item it touches.
 *
 * <p>The list subscribes to an item's three properties when the item enters
 * and no other slot holds it yet, and unsubscribes when the item leaves and no
 * other slot still holds it - so an item that was removed, replaced or cleared
 * away no longer reaches the listeners.  A property change of an item held in
 * several slots sends one change per slot.
 *
 * <p>Threading: mutations and notifications run on the mutating thread (the
 * UI thread) and take no lock.  The items sit in a
 * {@link CopyOnWriteArrayList} and {@link #iterator()} walks a snapshot, so
 * the preferences save thread iterates a consistent list while the UI thread
 * changes it.  The listener list is copy-on-write too: a listener may
 * subscribe, unsubscribe or change the list during a notification.
 */
public final class CalibrationList extends AbstractList<CalibrationEntry> {

    /** {@link Change#index()} of a change that names no single slot
     *  ({@link Kind#CLEARED}). */
    public static final int NO_INDEX = -1;

    /** What a {@link Change} reports. */
    public enum Kind {
        /** The entry entered the list at the index. */
        ADDED,
        /** The entry left the list from the index; the items behind it moved
         *  up by one. */
        REMOVED,
        /** The entry took the place of {@link Change#previous()} at the
         *  index. */
        REPLACED,
        /** Every item left the list; no index, no entry. */
        CLEARED,
        /** The path of the entry at the index changed. */
        PATH,
        /** The Active toggle of the entry at the index changed. */
        ACTIVE,
        /** The With-noise toggle of the entry at the index changed. */
        WITH_NOISE,
        /** The calibration file the entry at the index references was written
         *  to disk: the path is unchanged, the curve behind it is new. */
        FILE_SAVED
    }

    /** One change of the list: its kind, the slot it happened at
     *  ({@link #NO_INDEX} for {@link Kind#CLEARED}), the entry it concerns
     *  ({@code null} for {@link Kind#CLEARED}), and for
     *  {@link Kind#REPLACED} the entry that held the slot before
     *  ({@code null} otherwise). */
    public record Change(Kind kind, int index, CalibrationEntry entry, CalibrationEntry previous) {
    }

    /** The property listeners of one held item, kept so that
     *  {@link #unsubscribe} removes exactly what {@link #subscribe} added. */
    private record Subscription(Consumer<String> path, Consumer<Boolean> active,
                                Consumer<Boolean> withNoise) {
    }

    private final List<CalibrationEntry>   items     = new CopyOnWriteArrayList<>();
    private final List<Consumer<Change>>   listeners = new CopyOnWriteArrayList<>();
    /** The subscription of every item the list holds, by item identity. */
    private final Map<CalibrationEntry, Subscription> subscriptions = new IdentityHashMap<>();

    /** Registers a change listener.  The caller removes it again
     *  ({@link #removeListener}) when its view is disposed. */
    public void addListener(Consumer<Change> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<Change> listener) {
        listeners.remove(listener);
    }

    @Override
    public CalibrationEntry get(int index) {
        return items.get(index);
    }

    @Override
    public int size() {
        return items.size();
    }

    @Override
    public void add(int index, CalibrationEntry entry) {
        boolean held = items.contains(entry);
        items.add(index, entry);
        modCount++;
        if (!held) {
            subscribe(entry);
        }
        fire(new Change(Kind.ADDED, index, entry, null));
    }

    /** Replaces the item at {@code index}: the new item is subscribed, the old
     *  one unsubscribed unless another slot still holds it. */
    @Override
    public CalibrationEntry set(int index, CalibrationEntry entry) {
        CalibrationEntry old = items.get(index);
        if (old == entry) {
            return old;
        }
        boolean held = items.contains(entry);
        items.set(index, entry);
        if (!held) {
            subscribe(entry);
        }
        releaseIfGone(old);
        fire(new Change(Kind.REPLACED, index, entry, old));
        return old;
    }

    @Override
    public CalibrationEntry remove(int index) {
        CalibrationEntry old = items.remove(index);
        modCount++;
        releaseIfGone(old);
        fire(new Change(Kind.REMOVED, index, old, null));
        return old;
    }

    /** Overridden because the inherited form removes through the iterator,
     *  whose own remove comes back here. */
    @Override
    public boolean remove(Object o) {
        int index = items.indexOf(o);
        if (index < 0) {
            return false;
        }
        remove(index);
        return true;
    }

    /** Removes every item and unsubscribes from each; one change, none for a
     *  list that is already empty. */
    @Override
    public void clear() {
        if (items.isEmpty()) {
            return;
        }
        for (CalibrationEntry e : items) {
            unsubscribe(e);
        }
        items.clear();
        modCount++;
        fire(new Change(Kind.CLEARED, NO_INDEX, null, null));
    }

    @Override
    public Iterator<CalibrationEntry> iterator() {
        return new SnapshotIterator();
    }

    /** Walks the items as they stood when the walk began, so a thread that
     *  only reads (the preferences save thread) never meets a list the UI
     *  thread is changing.  {@code remove} takes the last returned item out of
     *  the live list through {@link CalibrationList#remove(Object)}, which
     *  keeps removeIf, removeAll and retainAll notifying and unsubscribing
     *  like every other change. */
    private final class SnapshotIterator implements Iterator<CalibrationEntry> {

        private final Iterator<CalibrationEntry> snapshot = items.iterator();
        private CalibrationEntry last;

        @Override
        public boolean hasNext() {
            return snapshot.hasNext();
        }

        @Override
        public CalibrationEntry next() {
            last = snapshot.next();
            return last;
        }

        @Override
        public void remove() {
            if (last == null) {
                throw new IllegalStateException("next() has not returned an item to remove");
            }
            CalibrationList.this.remove(last);
            last = null;
        }
    }

    /** The saved-file broadcast reaching this list: one {@link Kind#FILE_SAVED}
     *  change for every slot whose item references the file just written to
     *  {@code path} ({@link CalibrationEntry#matchesPath}), none otherwise. */
    void onFileSaved(String path) {
        List<CalibrationEntry> held = new ArrayList<>(items);
        for (int i = 0; i < held.size(); i++) {
            CalibrationEntry e = held.get(i);
            if (e.matchesPath(path)) {
                fire(new Change(Kind.FILE_SAVED, i, e, null));
            }
        }
    }

    private void subscribe(CalibrationEntry entry) {
        Subscription s = new Subscription(
                v -> fireForSlots(Kind.PATH, entry),
                v -> fireForSlots(Kind.ACTIVE, entry),
                v -> fireForSlots(Kind.WITH_NOISE, entry));
        entry.path().addListener(s.path());
        entry.active().addListener(s.active());
        entry.withNoise().addListener(s.withNoise());
        subscriptions.put(entry, s);
    }

    private void unsubscribe(CalibrationEntry entry) {
        Subscription s = subscriptions.remove(entry);
        if (s == null) {
            return;
        }
        entry.path().removeListener(s.path());
        entry.active().removeListener(s.active());
        entry.withNoise().removeListener(s.withNoise());
    }

    /** Unsubscribes from an item that just left one slot, unless another slot
     *  still holds it. */
    private void releaseIfGone(CalibrationEntry entry) {
        if (!items.contains(entry)) {
            unsubscribe(entry);
        }
    }

    /** Sends a property change of {@code entry} once for every slot holding
     *  it, at the slot's index as the list stood when the property changed. */
    private void fireForSlots(Kind kind, CalibrationEntry entry) {
        List<CalibrationEntry> held = new ArrayList<>(items);
        for (int i = 0; i < held.size(); i++) {
            if (held.get(i) == entry) {
                fire(new Change(kind, i, entry, null));
            }
        }
    }

    private void fire(Change change) {
        for (Consumer<Change> l : listeners) {
            l.accept(change);
        }
    }
}
