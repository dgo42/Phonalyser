package org.edgo.audio.measure.cli.util;

import lombok.experimental.UtilityClass;

@UtilityClass
public class PcmUtils {

    /**
     * Quantises a normalized float[-1,+1] mono signal back to little-endian
     * signed-PCM bytes at {@code bitDepth}, duplicating the value into both
     * channels of a stereo frame.
     */
    public byte[] floatMonoToStereoBytes(float[] samples, int bitDepth) {
        int sampleBytes = bitDepth / 8;
        int frameSize   = sampleBytes * 2;
        byte[] out      = new byte[samples.length * frameSize];
        long maxPos     = (1L << (bitDepth - 1)) - 1;
        long minNeg     = -(1L << (bitDepth - 1));
        for (int i = 0; i < samples.length; i++) {
            long v = Math.round((double) samples[i] * (1L << (bitDepth - 1)));
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
}
