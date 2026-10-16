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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import org.edgo.audio.measure.preferences.CalibrationList.Change;
import org.edgo.audio.measure.preferences.CalibrationList.Kind;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link CalibrationList} sends one {@link Change} per change, naming its
 * kind, the slot and the entry - an item added, removed, replaced, the list
 * cleared, a held item's path, Active or With-noise changed, a saved-file
 * broadcast for a file an item references - and nothing for anything else: a
 * broadcast for an unreferenced file, a change on an item that has left the
 * list, a value set to what it already is.
 *
 * <p>The broadcast runs through a DETACHED preferences copy
 * ({@link Preferences#copyForDialog()}), so the live singleton's lists are
 * never touched; the copy's lists carry no save listener.  It reaches every
 * list the preferences hold - FreqResp and FFT alike.
 */
class CalibrationListTest {

    private static final String REFERENCED_FILE   = "cal/loopback.frc";
    /** The same file as {@link #REFERENCED_FILE}, spelled with a {@code ./}
     *  segment - matched by the normalised path compare. */
    private static final String REFERENCED_FILE_OTHER_SPELLING = "cal/./loopback.frc";
    private static final String UNREFERENCED_FILE = "cal/filter.frc";

    /** Every calibration list a preferences instance holds - each one hears
     *  the saved-file broadcast. */
    static Stream<Named<Function<Preferences, CalibrationList>>> calibrationLists() {
        return Stream.of(
                Named.of("freqResp", Preferences::getFreqRespCalibrations),
                Named.of("fft",      Preferences::getFftCalibrations));
    }

    /** Records the changes {@code list} sends from now on. */
    private List<Change> recordChanges(CalibrationList list) {
        List<Change> changes = new ArrayList<>();
        list.addListener(changes::add);
        return changes;
    }

    @Test
    void add_sendsAddedAtItsIndex() {
        CalibrationList list = new CalibrationList();
        list.add(new CalibrationEntry());
        List<Change> changes = recordChanges(list);
        CalibrationEntry entry = new CalibrationEntry();
        list.add(0, entry);
        assertEquals(List.of(new Change(Kind.ADDED, 0, entry, null)), changes);
    }

    @Test
    void remove_sendsRemovedFromItsIndex() {
        CalibrationList list = new CalibrationList();
        CalibrationEntry entry = new CalibrationEntry();
        list.add(new CalibrationEntry());
        list.add(entry);
        List<Change> changes = recordChanges(list);
        list.remove(entry);
        assertEquals(List.of(new Change(Kind.REMOVED, 1, entry, null)), changes);
    }

    @Test
    void replace_sendsReplacedWithThePreviousEntry() {
        CalibrationList list = new CalibrationList();
        CalibrationEntry previous = new CalibrationEntry();
        list.add(previous);
        List<Change> changes = recordChanges(list);
        CalibrationEntry entry = new CalibrationEntry();
        list.set(0, entry);
        assertEquals(List.of(new Change(Kind.REPLACED, 0, entry, previous)), changes);
    }

    @Test
    void clear_sendsOneCleared() {
        CalibrationList list = new CalibrationList();
        list.add(new CalibrationEntry());
        list.add(new CalibrationEntry());
        List<Change> changes = recordChanges(list);
        list.clear();
        assertEquals(List.of(new Change(Kind.CLEARED, CalibrationList.NO_INDEX, null, null)), changes,
                "one change, not one per item");
    }

    @Test
    void propertyChanges_sendTheirKindAtTheEntrysIndex() {
        CalibrationList list = new CalibrationList();
        CalibrationEntry entry = new CalibrationEntry();
        list.add(new CalibrationEntry());
        list.add(entry);
        List<Change> changes = recordChanges(list);
        entry.setPath(REFERENCED_FILE);
        entry.active().set(true);
        entry.withNoise().set(true);
        assertEquals(List.of(
                new Change(Kind.PATH,       1, entry, null),
                new Change(Kind.ACTIVE,     1, entry, null),
                new Change(Kind.WITH_NOISE, 1, entry, null)), changes);
    }

    @ParameterizedTest
    @MethodSource("calibrationLists")
    void savedFileBroadcast_sendsFileSavedForEveryReferencingEntry(Function<Preferences, CalibrationList> listOf) {
        Preferences prefs = Preferences.instance().copyForDialog();
        CalibrationList list = listOf.apply(prefs);
        CalibrationEntry referencing = new CalibrationEntry(REFERENCED_FILE, true, false);
        CalibrationEntry otherSpelling = new CalibrationEntry(REFERENCED_FILE_OTHER_SPELLING, false, false);
        list.add(referencing);
        list.add(new CalibrationEntry());
        list.add(otherSpelling);
        List<Change> changes = recordChanges(list);
        prefs.broadcastSavedFile(REFERENCED_FILE);
        assertEquals(List.of(
                new Change(Kind.FILE_SAVED, 0, referencing, null),
                new Change(Kind.FILE_SAVED, 2, otherSpelling, null)), changes);
    }

    @ParameterizedTest
    @MethodSource("calibrationLists")
    void savedFileBroadcast_unreferencedFile_sendsNothing(Function<Preferences, CalibrationList> listOf) {
        Preferences prefs = Preferences.instance().copyForDialog();
        CalibrationList list = listOf.apply(prefs);
        list.add(new CalibrationEntry(REFERENCED_FILE, true, false));
        list.add(new CalibrationEntry());
        List<Change> changes = recordChanges(list);
        prefs.broadcastSavedFile(UNREFERENCED_FILE);
        assertEquals(List.of(), changes);
    }

    @Test
    void itemsThatLeftTheList_sendNothing() {
        CalibrationList list = new CalibrationList();
        CalibrationEntry removed  = new CalibrationEntry();
        CalibrationEntry replaced = new CalibrationEntry();
        CalibrationEntry cleared  = new CalibrationEntry();
        list.add(removed);
        list.add(replaced);
        list.remove(removed);
        list.set(0, new CalibrationEntry());
        CalibrationList other = new CalibrationList();
        other.add(cleared);
        other.clear();
        List<Change> changes = recordChanges(list);
        List<Change> otherChanges = recordChanges(other);
        for (CalibrationEntry gone : new CalibrationEntry[] { removed, replaced, cleared }) {
            gone.setPath(REFERENCED_FILE);
            gone.active().set(true);
            gone.withNoise().set(true);
        }
        assertEquals(List.of(), changes, "removed and replaced items are unsubscribed");
        assertEquals(List.of(), otherChanges, "cleared items are unsubscribed");
    }

    @Test
    void noChange_sendsNothing() {
        CalibrationList list = new CalibrationList();
        CalibrationEntry entry = new CalibrationEntry(REFERENCED_FILE, true, false);
        list.add(entry);
        CalibrationList empty = new CalibrationList();
        List<Change> changes = recordChanges(list);
        List<Change> emptyChanges = recordChanges(empty);
        entry.setPath(REFERENCED_FILE);
        entry.active().set(true);
        entry.withNoise().set(false);
        list.remove(new CalibrationEntry());
        list.set(0, entry);
        empty.clear();
        assertEquals(List.of(), changes, "same-value sets, an absent item, the held item re-set");
        assertEquals(List.of(), emptyChanges, "clearing an empty list");
    }

    @Test
    void entryHeldTwice_sendsOneChangePerSlot() {
        CalibrationList list = new CalibrationList();
        CalibrationEntry entry = new CalibrationEntry();
        list.add(entry);
        list.add(entry);
        List<Change> changes = recordChanges(list);
        entry.active().set(true);
        assertEquals(List.of(
                new Change(Kind.ACTIVE, 0, entry, null),
                new Change(Kind.ACTIVE, 1, entry, null)), changes, "one change per slot holding it");
        list.remove(0);
        changes.clear();
        entry.active().set(false);
        assertEquals(List.of(new Change(Kind.ACTIVE, 0, entry, null)), changes,
                "still subscribed while one slot holds it");
        list.remove(entry);
        changes.clear();
        entry.active().set(true);
        assertEquals(List.of(), changes, "unsubscribed once no slot holds it");
    }

    @Test
    void removeIf_sendsRemovedAndUnsubscribes() {
        CalibrationList list = new CalibrationList();
        CalibrationEntry entry = new CalibrationEntry(REFERENCED_FILE, false, false);
        list.add(entry);
        list.add(new CalibrationEntry());
        List<Change> changes = recordChanges(list);
        list.removeIf(e -> e == entry);
        assertEquals(List.of(new Change(Kind.REMOVED, 0, entry, null)), changes);
        assertEquals(1, list.size());
        entry.active().set(true);
        assertEquals(1, changes.size(), "the removed item is unsubscribed");
    }
}
