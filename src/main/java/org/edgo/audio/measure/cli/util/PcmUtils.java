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

import lombok.experimental.UtilityClass;

@UtilityClass
public class PcmUtils {

    /**
     * Quantises a normalized float[-1,+1] mono signal back to little-endian
     * signed-PCM bytes at {@code bitDepth}, duplicating the value into both
     * channels of a stereo frame.
     */
    public byte[] monoToStereoBytes(double[] samples, int bitDepth) {
        int sampleBytes = bitDepth / 8;
        int frameSize   = sampleBytes * 2;
        byte[] out      = new byte[samples.length * frameSize];
        long maxPos     = (1L << (bitDepth - 1)) - 1;
        long minNeg     = -(1L << (bitDepth - 1));
        for (int i = 0; i < samples.length; i++) {
            long v = Math.round(samples[i] * (1L << (bitDepth - 1)));
            if (v >  maxPos) v = maxPos;
            if (v <  minNeg) v = minNeg;
            int off = i * frameSize;
            for (int ch = 0; ch < 2; ch++) {
                int chOff = off + ch * sampleBytes;
                for (int b = 0; b < sampleBytes; b++) {
                    out[chOff + b] = (byte) (v >> (8 * b));
                }
            }
        }
        return out;
    }

    /**
     * Interleaves two normalised float[-1,+1] mono signals into stereo
     * little-endian signed-PCM bytes at {@code bitDepth}.  Used when the
     * caller already has separate L and R buffers (e.g. a stereo capture)
     * and just needs the interleaved byte layout a WAV writer expects.
     */
    public byte[] stereoToBytes(double[] left, double[] right, int bitDepth) {
        int n = Math.min(left.length, right.length);
        int sampleBytes = bitDepth / 8;
        int frameSize   = sampleBytes * 2;
        byte[] out      = new byte[n * frameSize];
        long maxPos     = (1L << (bitDepth - 1)) - 1;
        long minNeg     = -(1L << (bitDepth - 1));
        for (int i = 0; i < n; i++) {
            long vL = Math.round(left[i]  * (1L << (bitDepth - 1)));
            long vR = Math.round(right[i] * (1L << (bitDepth - 1)));
            if (vL > maxPos) vL = maxPos; else if (vL < minNeg) vL = minNeg;
            if (vR > maxPos) vR = maxPos; else if (vR < minNeg) vR = minNeg;
            int off = i * frameSize;
            for (int b = 0; b < sampleBytes; b++) {
                out[off + b]               = (byte) (vL >> (8 * b));
                out[off + sampleBytes + b] = (byte) (vR >> (8 * b));
            }
        }
        return out;
    }
}
