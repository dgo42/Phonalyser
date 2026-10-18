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

package org.edgo.audio.measure.gui.common;

import lombok.Value;
import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.dsp.StereoFreqRespCalibration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Owner of one pane's loaded frequency-response correction state - the
 * previously-saved {@code .frc} calibrations used to correct what was just
 * measured (a frequency-response sweep or an FFT spectrum).  Holds an ordered
 * list of loaded {@link #getEntries() entries} - every calibration loaded via
 * the pane's calibration tab.  Each entry pairs a
 * {@link StereoFreqRespCalibration} with the file path it came from.  The
 * consumer chains the divides through all entries in order at draw /
 * measurement time, so multiple files compose into a single correction.
 *
 * <p>The FFT pane and the FreqResp pane each construct their <em>own</em>
 * instance so they can be configured independently - you might keep a loopback
 * calibration for the FreqResp pane while loading a DUT-only calibration into
 * the FFT pane.  Each pane's store has one owner that builds it - the FFT
 * controller, the FreqResp pane - and the pane's view and tab control share
 * that one instance.  Nothing needs global access, so this is a plain object,
 * not a singleton.
 *
 * <p>The store announces nothing: its only writer, the pane's calibration tab
 * ({@link AbstractCalibrationTabControl}), hands every {@link Change} its
 * {@link #update} returns to the pane, which re-derives the view and refreshes
 * its calibration tile.
 *
 * <p>Written on the UI thread by the calibration tab and read there by the
 * view.  The FFT predistortion engine also reads it, on the FFT worker thread,
 * while its wizard runs; the wizard is application-modal, so the calibration
 * tab cannot rewrite the store meanwhile, and the store takes no lock.  The
 * per-entry {@link Entry#isWithNoise()} flag is honoured by the FFT pane only.
 */
@Log4j2
public final class CorrectionStore {

    /** Pairs a loaded calibration with the file path it came from and the
     *  row's flags.  Calibration and path are non-null. */
    @Value
    public static class Entry {
        StereoFreqRespCalibration calibration;
        String                    path;
        /** FFT-only: when {@code true}, this entry's correction is subtracted
         *  from every FFT bin (including the noise floor); when {@code false},
         *  only the fundamental + harmonic dot positions get the
         *  per-frequency offset.  Always {@code false} for the FreqResp
         *  instance, which divides the whole trace regardless. */
        boolean                   withNoise;
        /** The row's Active tick: only an active entry is divided by. */
        boolean                   active;
    }

    /** What a {@link Change} reports. */
    public enum Kind {
        /** A loaded calibration entered the store: a file loaded into an empty
         *  row, or a row holding one inserted. */
        ADDED,
        /** A loaded calibration left the store: its row's file cleared, or the
         *  row removed. */
        REMOVED,
        /** The calibration of an entry was exchanged: another file loaded into
         *  its row, or its file written anew. */
        REPLACED,
        /** An entry's Active or With-noise flag flipped; its calibration is the
         *  same. */
        CHANGED;

        private Kind() {
        }
    }

    /** One change of the store: its kind, the entry as it was ({@code null}
     *  for {@link Kind#ADDED}) and as it is ({@code null} for
     *  {@link Kind#REMOVED}). */
    public record Change(Kind kind, Entry before, Entry after) {
    }

    /** Human label distinguishing the instances in log lines (e.g. "FFT"). */
    private final String label;

    /** The entry of every row holding a loaded calibration, active or not, in
     *  the order of the calibration rows. */
    private final List<Entry> entries = new ArrayList<>();

    /** @param label short identifier for this store's log lines */
    public CorrectionStore(String label) {
        this.label = label;
    }

    // -------------------------------------------------------------------------
    // Entries list
    // -------------------------------------------------------------------------

    /** Immutable copy of every active calibration - the ones divided by - in
     *  the order of the calibration rows.  Empty when none is active. */
    public List<Entry> getEntries() {
        List<Entry> active = new ArrayList<>();
        for (Entry e : entries) {
            if (e.isActive()) active.add(e);
        }
        return Collections.unmodifiableList(active);
    }

    /**
     * Brings one row's entry from {@code before} to {@code after} and reports
     * exactly that: {@link Kind#ADDED} when the row held none, {@link Kind#REMOVED}
     * when it holds none now, {@link Kind#REPLACED} when its calibration or path
     * is another, {@link Kind#CHANGED} when only its flags moved.
     *
     * @param position the row's place among the rows holding an entry - where
     *                 an added entry goes
     * @param before   the row's entry as the store holds it, {@code null} for
     *                 none
     * @param after    the row's entry as it is now, {@code null} for none
     * @return the change, or {@code null} when the entry is the same
     */
    public Change update(int position, Entry before, Entry after) {
        if (before == null && after == null) return null;
        Kind kind;
        if (before == null) {
            entries.add(position, after);
            kind = Kind.ADDED;
        } else if (after == null) {
            entries.remove(indexOf(before));
            kind = Kind.REMOVED;
        } else if (before.equals(after)) {
            return null;
        } else {
            entries.set(indexOf(before), after);
            kind = before.getCalibration() == after.getCalibration()
                    && before.getPath().equals(after.getPath()) ? Kind.CHANGED : Kind.REPLACED;
        }
        Entry shown = after != null ? after : before;
        if (log.isInfoEnabled()) {
            log.info("{} calibration {} (active={}, withNoise={}): {}",
                    label, kind, shown.isActive(), shown.isWithNoise(), shown.getPath());
        }
        return new Change(kind, before, after);
    }

    /** The index of {@code entry} itself - two rows may hold equal entries. */
    private int indexOf(Entry entry) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) == entry) return i;
        }
        throw new IllegalArgumentException("entry not held: " + entry.getPath());
    }
}
