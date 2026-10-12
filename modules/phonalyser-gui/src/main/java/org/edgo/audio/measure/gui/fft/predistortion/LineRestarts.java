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

package org.edgo.audio.measure.gui.fft.predistortion;

import java.nio.file.Path;
import java.util.List;

/**
 * The restarts of single lines' averaging, as the predistortion engine asks
 * them of the coherent average once it has applied their corrections.  A
 * restart of a line's averaging empties the line's lobe and its local band
 * and starts its local count from zero, while the tones, the pilots and every
 * other line keep averaging.  It is carried out once one full frame has been
 * captured after the correction reached the ADC - the output latency and the
 * generator's fade of the line included - so the restarted region holds no
 * frame that straddles the change; until then the line is marked waiting for
 * its reset.
 */
public interface LineRestarts {

    /** A line by its coefficients on the two tones: harmonic h of a single
     *  tone is (h, 0), a dual-tone product a*F1 + b*F2 is (a, b). */
    record Line(int a, int b) {}

    /** Requests the restart of {@code lines}' averaging; the bound the
     *  restart waits for counts from this call.  Callable from any
     *  thread. */
    void requestLineRestart(List<Line> lines);

    /** Writes the coherent average's per-cycle statistics list of the
     *  running collection to {@code file}. */
    void exportStatistics(Path file);
}
