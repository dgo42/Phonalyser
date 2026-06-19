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

/**
 * Callback invoked by {@link FreqRespAnalyzer} as it progresses through a
 * sweep + deconvolution.  Implementations are typically the worker thread's
 * progress-bar updater; pass {@code null} when no progress reporting is
 * needed.
 *
 * <p>Fired from whatever thread runs the analyzer (a background daemon for
 * the GUI worker, the main thread for the CLI).  Implementations that touch
 * SWT widgets are responsible for marshalling to the UI thread.
 */
@FunctionalInterface
public interface ProgressCallback {

    /**
     * @param fraction current progress in {@code [0.0, 1.0]}; not strictly
     *                 monotonic but always inside the range
     * @param message  human-readable description of the current step (e.g.
     *                 "Running sweep", "Computing transfer function")
     */
    void onProgress(double fraction, String message);
}
