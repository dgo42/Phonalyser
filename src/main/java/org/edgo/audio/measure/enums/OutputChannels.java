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

/** Output-lane gate for the signal generator — which physical DAC channel(s)
 *  carry the tone.  {@link #BOTH} drives both lanes (the default, and the only
 *  behaviour before per-channel output existed); {@link #LEFT} / {@link #RIGHT}
 *  drive one lane and write digital silence to the other.  Applied at the sole
 *  stereo-interleave seam ({@code PcmQuantizer} for live playback,
 *  {@code SignalFileExporter} for file export). */
public enum OutputChannels {
    BOTH,
    LEFT,
    RIGHT;

    private OutputChannels() {}
}
