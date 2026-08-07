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

package org.edgo.audio.measure.common;

import lombok.experimental.UtilityClass;

/**
 * Heap arithmetic shared by every consumer that allocates capture-scale
 * buffers: the CLI capture legs guard with an English message, the GUI
 * pre-checks with a localized one - both from the SAME numbers, so the two
 * can never disagree about what fits.
 */
@UtilityClass
public class MemUtil {

    /** Heap bytes the stereo capture leg allocates for {@code duration}
     *  seconds at {@code sampleRate}: two double lanes plus their
     *  end-of-capture trim copies. */
    public long stereoCaptureHeapBytes(int sampleRate, int duration) {
        long maxSamples = Math.min((long) duration * sampleRate + sampleRate, Integer.MAX_VALUE);
        return 4L * maxSamples * Double.BYTES;
    }

    /** The current free-heap bytes when {@code needBytes} plus a 25 %
     *  headroom (for the analysis that follows the capture) does NOT fit,
     *  or {@code -1} when it fits. */
    public long heapShortfall(long needBytes) {
        Runtime rt = Runtime.getRuntime();
        long freeBytes = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
        return needBytes > freeBytes - freeBytes / 4 ? freeBytes : -1;
    }
}
