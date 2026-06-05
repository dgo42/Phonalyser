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

package org.edgo.audio.measure.gui.freqresp;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.cli.util.StereoFreqRespCalibration;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Single owner of the application's Frequency Response calibration state.
 * Holds an ordered list of loaded calibration entries plus the wizard's
 * direct-loopback buffer:
 *
 * <ul>
 *   <li>{@link #getEntries() entries} — every calibration the user has
 *       loaded via the calibration tab (or the wizard's Apply step).
 *       Each entry pairs a {@link StereoFreqRespCalibration} with the
 *       file path it came from.  The view chains the divides through
 *       all entries in order at draw / save time.</li>
 *   <li>{@link #getDirect() direct} — the loopback DAC→ADC response
 *       measured on page 1 of the calibration wizard.  Used to
 *       bootstrap a calibration so the page-2 measurement of the
 *       device-under-test isn't polluted by the bench's own transfer
 *       function.</li>
 * </ul>
 *
 * <p>Every mutation publishes {@link Events#FREQRESP_CALIBRATION_CHANGED}
 * on the {@link MessageBus} so the pane's calibration tab can refresh
 * the file-path fields and the view can re-derive its displayed copy.
 *
 * <p>The wizard captures a {@link #snapshot()} on open and calls
 * {@link #restore(Snapshot)} on cancel so a cancelled wizard doesn't
 * leave the in-memory state pointing at a half-measured response.
 */
@Log4j2
public final class FreqRespCalibrationStore {

    /** Pairs a loaded calibration with the file path it came from.
     *  Both fields are non-null. */
    @RequiredArgsConstructor
    public static final class Entry {
        @Getter private final StereoFreqRespCalibration calibration;
        @Getter private final String                    path;
    }

    /** Opaque snapshot of every slot — only created by
     *  {@link FreqRespCalibrationStore#snapshot()}. */
    public static final class Snapshot {
        private final List<Entry>               entries;
        private final StereoFreqRespCalibration direct;

        private Snapshot(List<Entry> entries, StereoFreqRespCalibration direct) {
            this.entries = entries;
            this.direct  = direct;
        }
    }

    private static volatile FreqRespCalibrationStore instance;

    public static FreqRespCalibrationStore instance() {
        FreqRespCalibrationStore local = instance;
        if (local != null) return local;
        synchronized (FreqRespCalibrationStore.class) {
            if (instance == null) instance = new FreqRespCalibrationStore();
            return instance;
        }
    }

    private final List<Entry>         entries = new ArrayList<>();
    private StereoFreqRespCalibration direct;

    private FreqRespCalibrationStore() {}

    /** Immutable view of every loaded calibration in the order the user
     *  added them.  Empty when none loaded. */
    public synchronized List<Entry> getEntries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /** True when at least one calibration is loaded. */
    public synchronized boolean isAnyLoaded() {
        return !entries.isEmpty();
    }

    /** Appends a calibration to the end of the entries list and fires
     *  one change event. */
    public synchronized void addEntry(StereoFreqRespCalibration calibration, String path) {
        if (calibration == null || path == null) {
            throw new IllegalArgumentException("calibration and path must be non-null");
        }
        entries.add(new Entry(calibration, path));
        log.info("FreqResp calibration added (row {}, {} points): {}",
                entries.size() - 1, calibration.left().freqs.length, path);
        fire();
    }

    /** Replaces the entry at {@code index}.  Throws if the index is out
     *  of bounds.  Fires one change event. */
    public synchronized void setEntry(int index, StereoFreqRespCalibration calibration, String path) {
        if (index < 0 || index >= entries.size()) {
            throw new IndexOutOfBoundsException("setEntry: index " + index + " of " + entries.size());
        }
        if (calibration == null || path == null) {
            throw new IllegalArgumentException("calibration and path must be non-null");
        }
        entries.set(index, new Entry(calibration, path));
        log.info("FreqResp calibration replaced (row {}, {} points): {}",
                index, calibration.left().freqs.length, path);
        fire();
    }

    /** Removes the entry at {@code index}.  No-op when the index is out
     *  of range; otherwise fires one change event. */
    public synchronized void removeEntry(int index) {
        if (index < 0 || index >= entries.size()) return;
        Entry removed = entries.remove(index);
        log.info("FreqResp calibration removed (row {}): {}", index, removed.getPath());
        fire();
    }

    /** Clears every entry and fires one change event (no-op when already
     *  empty). */
    public synchronized void clearAll() {
        if (entries.isEmpty()) return;
        entries.clear();
        log.info("FreqResp calibrations cleared");
        fire();
    }

    // -------------------------------------------------------------------------
    // Wizard compatibility shims
    //
    // The wizard talks to the store via setCurrent / getCurrent / clearCurrent
    // (operating on a single "current" calibration).  Map those calls onto
    // the multi-row model so the wizard's pre-existing flow stays untouched
    // and a wizard Apply transparently "clear all + load row 0".
    // -------------------------------------------------------------------------

    /** Calibration in row 0, or {@code null} when no entries are loaded.
     *  Kept for compatibility with the wizard's page-2 DUT subtraction
     *  (which uses only the page-1 loopback). */
    public synchronized StereoFreqRespCalibration getCurrent() {
        return entries.isEmpty() ? null : entries.get(0).getCalibration();
    }

    /** Path of row 0, or {@code null} when no entries are loaded
     *  (or row 0 has no associated path — currently impossible since
     *  {@link #addEntry} requires a non-null path). */
    public synchronized String getCurrentPath() {
        return entries.isEmpty() ? null : entries.get(0).getPath();
    }

    /** Clears every loaded entry and seeds row 0 with this calibration.
     *  Also clears any stale wizard transient ({@link #direct}) so the
     *  view doesn't double-correct after the wizard's Apply step.
     *  Both {@code calibration} and {@code path} must be non-null —
     *  use {@link #setDirect(StereoFreqRespCalibration)} for the
     *  wizard's transient page-1 buffer. */
    public synchronized void setCurrent(StereoFreqRespCalibration calibration, String path) {
        if (calibration == null || path == null) {
            throw new IllegalArgumentException("setCurrent requires non-null calibration and path");
        }
        entries.clear();
        entries.add(new Entry(calibration, path));
        this.direct = null;
        log.info("FreqResp current calibration loaded from {} ({} points)",
                path, calibration.left().freqs.length);
        fire();
    }

    /** Alias for {@link #clearAll()} — wizard / older callers. */
    public synchronized void clearCurrent() {
        clearAll();
    }

    /** The wizard's page-1 loopback measurement, or {@code null} when the
     *  wizard hasn't run since the last clear. */
    public synchronized StereoFreqRespCalibration getDirect() {
        return direct;
    }

    /**
     * Replaces the direct (wizard page-1) calibration buffer.  Pass
     * {@code null} to clear.  Fires
     * {@link Events#FREQRESP_CALIBRATION_CHANGED} so the view applies the
     * new transient calibration on top of the entries list — that's how
     * the wizard's page-2 trace gets the page-1 loopback subtracted
     * without polluting the persistent entries list.
     */
    public synchronized void setDirect(StereoFreqRespCalibration directCal) {
        if (this.direct == directCal) return;
        this.direct = directCal;
        fire();
    }

    /** Captures every entry + direct so the wizard can restore them on cancel. */
    public synchronized Snapshot snapshot() {
        return new Snapshot(new ArrayList<>(entries), direct);
    }

    /** Restores from a prior {@link #snapshot()} and publishes one change
     *  event when the entries list actually moved. */
    public synchronized void restore(Snapshot s) {
        boolean changed = !entriesEqual(entries, s.entries);
        entries.clear();
        entries.addAll(s.entries);
        this.direct = s.direct;
        if (changed) fire();
    }

    private boolean entriesEqual(List<Entry> a, List<Entry> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Entry x = a.get(i);
            Entry y = b.get(i);
            if (x.getCalibration() != y.getCalibration()) return false;
            if (!x.getPath().equals(y.getPath())) return false;
        }
        return true;
    }

    private void fire() {
        MessageBus.instance().publish(Events.FREQRESP_CALIBRATION_CHANGED);
    }
}
