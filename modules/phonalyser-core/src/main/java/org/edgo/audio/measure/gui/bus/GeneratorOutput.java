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
 * Response of {@link Events#GENERATOR_OUTPUT}: the two facts about the
 * generator's output side that no other request carries.  The playback rate
 * may differ from the capture rate, and a tone whose period holds a whole
 * number of PLAYBACK samples renders one short pattern for ever - which is
 * why the pilot selection needs the rate and not only the capture grid.
 * {@code remote} is true while a server drives the DDS: the pilot exists
 * only for the local one.
 *
 * @param rateHz the playback sample rate in Hz: the rate a server granted
 *               while its generator lane is open, else the configured output
 *               rate, which is also the answer while no lane is open
 * @param remote whether a server, not the local lane, drives the generator
 */
public record GeneratorOutput(double rateHz, boolean remote) {
}
