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
package org.edgo.audio.measure.gui.bus;

/**
 * Payload of {@link Events#GENERATOR_PILOT_ON}: the pilot tones the phase
 * alignment wants beside the main waveform.  Each frequency is one the
 * selection elected - the centre of a bin of the capture grid, derived from
 * that bin index every time rather than carried as a rounded value - and the
 * level, one value for both lines, is far enough below the main tone to leave
 * every readout untouched.  Two lines are asked for because each pins the
 * elapsed sample count only modulo its own period: the whole periods a gap in
 * the capture may have added are settled by the two readings together.
 *
 * @param hz1      the first pilot's frequency in Hz
 * @param hz2      the second pilot's frequency in Hz, NaN when the selection
 *                 found room for one line only
 * @param levelDbv the pilots' level at the converter, dBV
 */
public record PilotToneRequest(double hz1, double hz2, double levelDbv) {
}
