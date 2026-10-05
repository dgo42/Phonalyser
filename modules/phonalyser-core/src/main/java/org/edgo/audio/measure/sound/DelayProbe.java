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

package org.edgo.audio.measure.sound;

import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.OutputChannels;

/**
 * Everything one delay measurement ({@link AudioDeviceManager#measureDelay})
 * needs besides the two devices: the pulse's level and lanes, where it is
 * read, and the formats the two lines open in.  Built by {@link AudioBackend}
 * from the preferences and carried whole to the backend that measures - over
 * the wire, when that backend is a bench across the network.
 *
 * @param amplitudeVrms the pulse's level - the peak the rectangle reaches
 * @param lanes         which output lane(s) carry it
 * @param edgeChannel   which capture channel the edges are read on
 * @param outputRate    the playback line's sample rate, Hz
 * @param outputBits    the playback line's sample depth
 * @param inputRate     the capture line's sample rate, Hz
 * @param inputBits     the capture line's sample depth
 */
public record DelayProbe(double amplitudeVrms, OutputChannels lanes, Channel edgeChannel,
        int outputRate, int outputBits, int inputRate, int inputBits) {
}
