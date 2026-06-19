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

package org.edgo.audio.measure.cli.util;

/**
 * Both channels of a single ADC capture, already normalised to {@code [-1, +1]}
 * in double precision.
 * Returned by the stereo variant of the capture path so callers can use both
 * channels from one playback instead of running playback twice and throwing
 * away the other channel each time.
 *
 * <p>The two arrays always have the same length — they're filled in lockstep
 * from the same {@code stereo[i]} frames inside the capture listener.
 */
public record StereoSamples(double[] left, double[] right) {}
