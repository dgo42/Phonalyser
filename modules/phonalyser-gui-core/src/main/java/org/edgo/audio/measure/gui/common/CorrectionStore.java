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

import lombok.Getter;
import lombok.Value;
import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.dsp.FreqRespCalHelper;
import org.edgo.audio.measure.dsp.FreqRespCalibration;
import org.edgo.audio.measure.dsp.StereoFreqRespCalibration;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.gui.bus.MessageBus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Owner of one pane's loaded frequency-response correction state - the
 * previously-saved {@code .frc} calibrations used to correct what was just
 * measured (a frequency-response sweep or an FFT spectrum).  Holds an ordered
 * list of loaded {@link Entry entries} plus the calibration wizard's
 * direct-loopback buffer:
 *
 * <ul>
 *   <li>{@link #getEntries() entries} - every calibration the user has loaded
 *       via the pane's calibration tab (or the wizard's Apply step).  Each
 *       entry pairs a {@link StereoFreqRespCalibration} with the file path it
 *       came from.  The consumer chains the divides through all entries in
 *       order at draw / measurement time, so multiple files compose into a
 *       single correction.</li>
 *   <li>{@link #getDirect() direct} - the loopback DAC->ADC response measured
 *       on page 1 of the frequency-response calibration wizard.  Used to
 *       bootstrap a calibration so the page-2 measurement of the
 *       device-under-test isn't polluted by the bench's own transfer
 *       function.</li>
 * </ul>
 *
 * <p>The FFT pane and the FreqResp pane each construct their <em>own</em>
 * instance so they can be configured independently - you might keep a loopback
 * calibration for the FreqResp pane while loading a DUT-only calibration into
 * the FFT pane.  The pane is the composition root: it builds the store and
 * injects the same instance into the view and tab control it owns (and the
 * wizard).  Nothing needs global access, so this is a plain object, not a
 * singleton.
 *
 * <p>Change notification is config, not a callback: the constructor takes the
 * name of the {@code MessageBus} event to publish after every mutation
 * ({@code null} = silent), so the owning pane's calibration tab refreshes and
 * the view re-derives its displayed copy.  The offscreen screenshot store
 * passes {@code null}.
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
public final class CorrectionStore {

    /** Pairs a loaded calibration with the file path it came from.
     *  Both fields are non-null.  {@code @Value} supplies the all-field
     *  {@code equals} the snapshot/restore comparison relies on - including
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

    /** Opaque snapshot of every slot - only created by
     *  {@link CorrectionStore#snapshot()}. */
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
    /** Name of the {@code MessageBus} event published after every mutation, or
     *  {@code null} for a silent store (e.g. the offscreen screenshot clone). */
    private final String changedEvent;

    private final List<Entry> entries = new ArrayList<>();

    /** The wizard's page-1 loopback measurement, or {@code null} when the
     *  wizard hasn't run since the last clear. */
    @Getter
    private StereoFreqRespCalibration direct;

    /**
     * @param label        short identifier for this store's log lines
     * @param changedEvent {@code MessageBus} event published after every
     *                     mutation; {@code null} for a silent store
     */
    public CorrectionStore(String label, String changedEvent) {
        this.label        = label;
        this.changedEvent = changedEvent;
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

    /**
     * Replaces one band of the topmost with-noise entry's response, on one
     * channel only, with a freshly measured one - the de-embedding refresh a
     * notch-phase probe makes after it has measured the filter again.
     *
     * <p>The measured response arrives on its own frequency grid and is
     * interpolated onto the entry's grid ({@link FreqRespCalHelper#interpolate},
     * the same log-frequency reading the plot-time cascade uses), so the entry
     * keeps its own points and only their magnitude and phase move.  Points
     * outside {@code [bandLoHz, bandHiHz]} keep the numbers they had, and the
     * band is first cut down to what {@code measured} actually covers:
     * beyond its ends the interpolation returns its end point, which would
     * flatten the entry's own response there.
     *
     * <p>The entry is edited in place - no entry is added or removed, so a
     * cascade that already holds this row cannot end up correcting twice - and
     * nothing is written to disk: the file the row was loaded from keeps the
     * response it was saved with.  The change notification is what carries the
     * edit to the consumer, whose derived correction is cached and is not keyed
     * on the arrays' content.
     *
     * <p>Store membership IS activity for this store: only rows the operator
     * has switched on are pushed into it, so "the topmost with-noise entry" is
     * simply the first {@link Entry#isWithNoise()} entry in
     * {@link #getEntries()} order.
     *
     * @param side      the channel whose side of the entry is refreshed; the
     *                  other side is left untouched
     * @param bandLoHz  low edge of the refreshed band, inclusive
     * @param bandHiHz  high edge of the refreshed band, inclusive
     * @param measured  the freshly measured response (its own freqs / magLin /
     *                  phaseRad grid)
     * @return {@code true} when an entry was refreshed and the change
     *         published; {@code false} when no with-noise entry is loaded, in
     *         which case nothing is touched
     */
    public boolean refreshBand(Channel side, double bandLoHz, double bandHiHz,
                               FreqRespCalibration measured) {
        FreqRespCalibration target = null;
        for (Entry entry : entries) {
            if (entry.isWithNoise()) {
                target = side == Channel.L
                        ? entry.getCalibration().left()
                        : entry.getCalibration().right();
                break;
            }
        }
        if (target == null) {
            if (log.isInfoEnabled()) {
                log.info("{} band refresh skipped: no with-noise calibration row is loaded",
                        label);
            }
            return false;
        }
        double lo = Math.max(bandLoHz, measured.freqs[0]);
        double hi = Math.min(bandHiHz, measured.freqs[measured.freqs.length - 1]);
        int replaced = 0;
        for (int i = 0; i < target.freqs.length; i++) {
            double f = target.freqs[i];
            if (f < lo || f > hi) continue;
            double[] h = FreqRespCalHelper.interpolate(measured, f);
            target.magLin[i]   = h[0];
            target.phaseRad[i] = h[1];
            replaced++;
        }
        if (log.isInfoEnabled()) {
            log.info("{} band refresh on {}: {} of {} points replaced between {} and {} Hz",
                    label, side, replaced, target.freqs.length, lo, hi);
        }
        fire();
        return true;
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
     *  (or row 0 has no associated path - currently impossible since
     *  {@link #addEntry} requires a non-null path). */
    public String getCurrentPath() {
        return entries.isEmpty() ? null : entries.get(0).getPath();
    }

    /** Clears every loaded entry and seeds row 0 with this calibration.
     *  Also clears any stale wizard transient ({@link #direct}) so the
     *  consumer doesn't double-correct after the wizard's Apply step.
     *  Both {@code calibration} and {@code path} must be non-null -
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

    /** Alias for {@link #clearAll()} - wizard / older callers. */
    public void clearCurrent() {
        clearAll();
    }

    /**
     * Replaces the direct (wizard page-1) calibration buffer.  Pass
     * {@code null} to clear.  Fires a change notification so the consumer
     * applies the new transient calibration on top of the entries list -
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
        if (changedEvent != null) MessageBus.instance().publish(changedEvent);
    }
}
