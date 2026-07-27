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
 * Cooperative cancellation token consulted by {@link FreqRespAnalyzer} at
 * each measurement step.  When {@link #isCancelled()} returns {@code true},
 * the analyzer throws {@link InterruptedException} from its next checkpoint
 * so callers can abort an in-flight sweep without waiting for the full
 * duration.  Pass {@code null} when cancellation isn't needed.
 */
@FunctionalInterface
public interface Cancellable {

    /** @return {@code true} when the caller wants the analyzer to stop. */
    boolean isCancelled();
}
