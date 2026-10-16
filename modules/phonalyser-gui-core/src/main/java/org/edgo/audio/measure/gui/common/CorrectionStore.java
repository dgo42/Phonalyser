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
 * ({@link AbstractCalibrationTabControl}), re-derives the view and refreshes
 * its calibration tile itself after every sync.
 *
 * <p>Written on the UI thread by the calibration tab and read there by the
 * view.  The FFT predistortion engine also reads it, on the FFT worker thread,
 * while its wizard runs; the wizard is application-modal, so the calibration
 * tab cannot rewrite the store meanwhile, and the store takes no lock.  The
 * per-entry {@link Entry#isWithNoise()} flag is honoured by the FFT pane only.
 */
@Log4j2
public final class CorrectionStore {

    /** Pairs a loaded calibration with the file path it came from.
     *  Both fields are non-null. */
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

    /** Human label distinguishing the instances in log lines (e.g. "FFT"). */
    private final String label;

    private final List<Entry> entries = new ArrayList<>();

    /** @param label short identifier for this store's log lines */
    public CorrectionStore(String label) {
        this.label = label;
    }

    // -------------------------------------------------------------------------
    // Entries list
    // -------------------------------------------------------------------------

    /** Immutable copy of every loaded calibration, in the order of the
     *  calibration rows.  Empty when none loaded. */
    public List<Entry> getEntries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    /** Appends a calibration to the end of the entries list.
     *  {@code withNoise} is honoured only by the FFT instance (see
     *  {@link Entry#isWithNoise()}). */
    public void addEntry(StereoFreqRespCalibration calibration, String path, boolean withNoise) {
        if (calibration == null || path == null) {
            throw new IllegalArgumentException("calibration and path must be non-null");
        }
        entries.add(new Entry(calibration, path, withNoise));
        if (log.isInfoEnabled()) {
            log.info("{} calibration added (row {}, {} points, withNoise={}): {}",
                    label, entries.size() - 1, calibration.left().freqs.length, withNoise, path);
        }
    }

    /** Clears every entry (no-op when already empty). */
    public void clearAll() {
        if (entries.isEmpty()) return;
        entries.clear();
        if (log.isInfoEnabled()) {
            log.info("{} calibrations cleared", label);
        }
    }
}
