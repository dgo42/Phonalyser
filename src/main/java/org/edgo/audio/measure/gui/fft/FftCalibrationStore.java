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

package org.edgo.audio.measure.gui.fft;

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
 * Owner of the FFT pane's loaded calibration list.  An ordered list of
 * {@link Entry} pairs (each = one {@link StereoFreqRespCalibration} +
 * the file path it came from); the view chains the divides through all
 * entries in order at draw / measurement time, so multiple files
 * compose into a single correction.
 *
 * <p>Separate from {@code FreqRespCalibrationStore} so the FFT pane and
 * the FreqResp pane can be configured independently (you might keep a
 * loopback calibration for the FreqResp pane while loading a DUT-only
 * calibration into the FFT pane).
 *
 * <p>Every mutation publishes {@link Events#FFT_CALIBRATION_CHANGED} on
 * the {@link MessageBus} — the FFT pane's row UI and view both
 * subscribe.
 */
@Log4j2
public final class FftCalibrationStore {

    @RequiredArgsConstructor
    public static final class Entry {
        @Getter private final StereoFreqRespCalibration calibration;
        @Getter private final String                    path;
        /** When {@code true}, this entry's correction is subtracted
         *  from every FFT bin (including noise floor); when
         *  {@code false}, only the fundamental + harmonic dot
         *  positions get the per-frequency offset.  Used to per-file
         *  what was previously a single global "Calibrate with noise"
         *  toggle.  Defaults to {@code false}. */
        @Getter private final boolean                   withNoise;
    }

    private static volatile FftCalibrationStore instance;

    private final List<Entry> entries = new ArrayList<>();

    private FftCalibrationStore() {}

    public static FftCalibrationStore instance() {
        FftCalibrationStore local = instance;
        if (local != null) return local;
        synchronized (FftCalibrationStore.class) {
            if (instance == null) instance = new FftCalibrationStore();
            return instance;
        }
    }

    public synchronized List<Entry> getEntries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public synchronized boolean isAnyLoaded() {
        return !entries.isEmpty();
    }

    public synchronized void addEntry(StereoFreqRespCalibration calibration, String path,
                                      boolean withNoise) {
        if (calibration == null || path == null) {
            throw new IllegalArgumentException("calibration and path must be non-null");
        }
        entries.add(new Entry(calibration, path, withNoise));
        log.info("FFT calibration added (row {}, {} points, withNoise={}): {}",
                entries.size() - 1, calibration.left().freqs.length, withNoise, path);
        fire();
    }

    public synchronized void clearAll() {
        if (entries.isEmpty()) return;
        entries.clear();
        log.info("FFT calibrations cleared");
        fire();
    }

    private void fire() {
        MessageBus.instance().publish(Events.FFT_CALIBRATION_CHANGED);
    }
}
