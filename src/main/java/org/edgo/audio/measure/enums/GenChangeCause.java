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

package org.edgo.audio.measure.enums;

import org.edgo.audio.measure.gui.bus.Events;

/**
 * Tag carried as the payload of {@link Events#GENERATOR_SIGNAL_CHANGED}
 * so subscribers can distinguish a user-initiated generator change from
 * a closed-loop trim issued by the FFT-side frequency-lock loop.
 *
 * <p>The distinction matters because user-initiated changes invalidate
 * the FFT worker's raw-FFT cache + averaging (the signal really is
 * different), while a FLL trim is a sub-millihertz tweak meant to align
 * the running tone with the nearest FFT bin centre and MUST keep
 * averaging alive — otherwise every closed-loop step would throw away
 * the accumulated noise reduction the user is trying to build up.
 */
public enum GenChangeCause {
    /** The user changed a generator parameter (frequency, amplitude,
     *  waveform, sweep params, etc.).  Downstream subscribers should
     *  treat this as "drop everything; restart". */
    USER_INPUT,

    /** The FFT-side {@code FrequencyLockLoop} applied a small trim to
     *  the generator frequency.  Downstream subscribers that cache
     *  results derived from the generated signal MUST keep their cache
     *  + averaging — a trim of micro-hertz scale doesn't change the
     *  signal's structure, only its alignment with the FFT bin grid. */
    FLL_TRIM,
}
