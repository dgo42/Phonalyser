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

package org.edgo.audio.measure.common;

import lombok.Getter;
import lombok.Value;
import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.dsp.StereoFreqRespCalibration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Owner of one pane's loaded frequency-response correction state — the
 * previously-saved {@code .frc} calibrations used to correct what was just
 * measured (a frequency-response sweep or an FFT spectrum).  Holds an ordered
 * list of loaded {@link Entry entries} plus the calibration wizard's
 * direct-loopback buffer:
 *
 * <ul>
 *   <li>{@link #getEntries() entries} — every calibration the user has loaded
 *       via the pane's calibration tab (or the wizard's Apply step).  Each
 *       entry pairs a {@link StereoFreqRespCalibration} with the file path it
 *       came from.  The consumer chains the divides through all entries in
 *       order at draw / measurement time, so multiple files compose into a
 *       single correction.</li>
 *   <li>{@link #getDirect() direct} — the loopback DAC→ADC response measured
 *       on page 1 of the frequency-response calibration wizard.  Used to
 *       bootstrap a calibration so the page-2 measurement of the
 *       device-under-test isn't polluted by the bench's own transfer
 *       function.</li>
 * </ul>
 *
 * <p>The FFT pane and the FreqResp pane each construct their <em>own</em>
 * instance so they can be configured independently — you might keep a loopback
 * calibration for the FreqResp pane while loading a DUT-only calibration into
 * the FFT pane.  The pane is the composition root: it builds the store and
 * injects the same instance into the view and tab control it owns (and the
 * wizard).  Nothing needs global access, so this is a plain object, not a
 * singleton.
 *
 * <p>This class is GUI-agnostic so the CLI can reuse it: change notification
 * is delivered through the constructor-supplied {@code changeNotifier}
 * (no-op when {@code null}) rather than a hard-wired event bus.  The GUI
 * passes a notifier that publishes the matching {@code MessageBus} event so
 * the pane's calibration tab refreshes and the view re-derives its displayed
 * copy; headless / CLI callers pass {@code null}.
 *
 * <p>All access is on the owning view's single (UI) thread, so no internal
 * synchronisation is needed.  The wizard captures a {@link #snapshot()} on
 * open and calls {@link #restore(Snapshot)} on cancel so a cancelled wizard
 * doesn't leave the in-memory state pointing at a half-measured response.  The
 * FFT pane uses only the entries list (with the per-entry
 * {@link Entry#isWithNoise()} flag); the wizard / {@code direct} machinery
 * stays idle there.
 */
@Log4j2
public final class FreqRespCorrectionStore {

    /** Pairs a loaded calibration with the file path it came from.
     *  Both fields are non-null.  {@code @Value} supplies the all-field
     *  {@code equals} the snapshot/restore comparison relies on — including
     *  {@link #isWithNoise()}, so a restore that only flips the flag still
     *  fires a change notification. */
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
    }

    /** Opaque snapshot of every slot — only created by
     *  {@link FreqRespCorrectionStore#snapshot()}. */
    public static final class Snapshot {
        private final List<Entry>               entries;
        private final StereoFreqRespCalibration direct;

        private Snapshot(List<Entry> entries, StereoFreqRespCalibration direct) {
            this.entries = entries;
            this.direct  = direct;
        }
    }

    /** Human label distinguishing the instances in log lines (e.g. "FFT"). */
    private final String label;
    /** Run after every mutation so the owner can react (the GUI publishes its
     *  {@code MessageBus} calibration-changed event).  Never {@code null}. */
    private final Runnable changeNotifier;

    private final List<Entry> entries = new ArrayList<>();

    /** The wizard's page-1 loopback measurement, or {@code null} when the
     *  wizard hasn't run since the last clear. */
    @Getter
    private StereoFreqRespCalibration direct;

    /**
     * @param label          short identifier for this store's log lines
     * @param changeNotifier invoked after every mutation; {@code null} for a
     *                       silent (e.g. CLI) store
     */
    public FreqRespCorrectionStore(String label, Runnable changeNotifier) {
        this.label          = label;
        this.changeNotifier = (changeNotifier == null) ? () -> { } : changeNotifier;
    }

    // -------------------------------------------------------------------------
    // Entries list
    // -------------------------------------------------------------------------

    /** Immutable view of every loaded calibration in the order the user
     *  added them.  Empty when none loaded. */
    public List<Entry> getEntries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /** Appends a calibration with no noise-floor correction (FreqResp, and
     *  FFT rows that only correct harmonic / dot positions).  Fires one
     *  change notification. */
    public void addEntry(StereoFreqRespCalibration calibration, String path) {
        addEntry(calibration, path, false);
    }

    /** Appends a calibration to the end of the entries list and fires one
     *  change notification.  {@code withNoise} is honoured only by the FFT
     *  instance (see {@link Entry#isWithNoise()}). */
    public void addEntry(StereoFreqRespCalibration calibration, String path, boolean withNoise) {
        if (calibration == null || path == null) {
            throw new IllegalArgumentException("calibration and path must be non-null");
        }
        entries.add(new Entry(calibration, path, withNoise));
        if (log.isInfoEnabled()) {
            log.info("{} calibration added (row {}, {} points, withNoise={}): {}",
                    label, entries.size() - 1, calibration.left().freqs.length, withNoise, path);
        }
        fire();
    }

    /** Clears every entry and fires one change notification (no-op when
     *  already empty). */
    public void clearAll() {
        if (entries.isEmpty()) return;
        entries.clear();
        if (log.isInfoEnabled()) {
            log.info("{} calibrations cleared", label);
        }
        fire();
    }

    // -------------------------------------------------------------------------
    // Calibration-wizard compatibility shims
    //
    // The wizard talks to the store via setCurrent / getCurrent / clearCurrent
    // (operating on a single "current" calibration).  Map those calls onto
    // the multi-row model so the wizard's pre-existing flow stays untouched
    // and a wizard Apply transparently "clear all + load row 0".
    // -------------------------------------------------------------------------

    /** Calibration in row 0, or {@code null} when no entries are loaded.
     *  Kept for compatibility with the wizard's page-2 DUT subtraction
     *  (which uses only the page-1 loopback). */
    public StereoFreqRespCalibration getCurrent() {
        return entries.isEmpty() ? null : entries.get(0).getCalibration();
    }

    /** Path of row 0, or {@code null} when no entries are loaded
     *  (or row 0 has no associated path — currently impossible since
     *  {@link #addEntry} requires a non-null path). */
    public String getCurrentPath() {
        return entries.isEmpty() ? null : entries.get(0).getPath();
    }

    /** Clears every loaded entry and seeds row 0 with this calibration.
     *  Also clears any stale wizard transient ({@link #direct}) so the
     *  consumer doesn't double-correct after the wizard's Apply step.
     *  Both {@code calibration} and {@code path} must be non-null —
     *  use {@link #setDirect(StereoFreqRespCalibration)} for the
     *  wizard's transient page-1 buffer. */
    public void setCurrent(StereoFreqRespCalibration calibration, String path) {
        if (calibration == null || path == null) {
            throw new IllegalArgumentException("setCurrent requires non-null calibration and path");
        }
        entries.clear();
        entries.add(new Entry(calibration, path, false));
        this.direct = null;
        if (log.isInfoEnabled()) {
            log.info("{} current calibration loaded from {} ({} points)",
                    label, path, calibration.left().freqs.length);
        }
        fire();
    }

    /** Alias for {@link #clearAll()} — wizard / older callers. */
    public void clearCurrent() {
        clearAll();
    }

    /**
     * Replaces the direct (wizard page-1) calibration buffer.  Pass
     * {@code null} to clear.  Fires a change notification so the consumer
     * applies the new transient calibration on top of the entries list —
     * that's how the wizard's page-2 trace gets the page-1 loopback
     * subtracted without polluting the persistent entries list.
     */
    public void setDirect(StereoFreqRespCalibration directCal) {
        if (this.direct == directCal) return;
        this.direct = directCal;
        fire();
    }

    /** Captures every entry + direct so the wizard can restore them on cancel. */
    public Snapshot snapshot() {
        return new Snapshot(new ArrayList<>(entries), direct);
    }

    /** Restores from a prior {@link #snapshot()} and fires one change
     *  notification when the entries list actually moved.  Element comparison
     *  is {@link Entry}'s Lombok-generated all-field {@code equals}
     *  (calibration record identity + path + withNoise). */
    public void restore(Snapshot s) {
        boolean changed = !entries.equals(s.entries);
        entries.clear();
        entries.addAll(s.entries);
        this.direct = s.direct;
        if (changed) fire();
    }

    private void fire() {
        changeNotifier.run();
    }
}
