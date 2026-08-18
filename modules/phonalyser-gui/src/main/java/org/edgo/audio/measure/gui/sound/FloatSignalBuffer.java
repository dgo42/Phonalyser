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

package org.edgo.audio.measure.gui.sound;

/**
 * {@link SignalBuffer} backed by {@code float[]} storage at half the memory of
 * the double ring - the scope's backing: its render and measurement paths read
 * float throughout.  Used for the scope's frozen copies
 * ({@link SignalBuffer#frozenCopy()}) and its loaded signal files; the ONE
 * live capture ring stays {@link DoubleSignalBuffer double} because the FFT
 * reads the same ring.  Precision: a float keeps 24 significant magnitude
 * bits (sign separate) - an int24 sample survives exactly, a 32-bit source is
 * quantised at 2^-24 (≈ -144 dBFS).
 */
public final class FloatSignalBuffer extends SignalBuffer {

    final float[] left;
    final float[] right;

    public FloatSignalBuffer(int sampleRate, double seconds) {
        super(sampleRate, seconds);
        this.left  = new float[getCapacity()];
        this.right = new float[getCapacity()];
    }

    /** Exact-capacity twin for {@link SignalBuffer#frozenCopy()} - see the
     *  base ctor's note on seconds-based capacity rounding. */
    FloatSignalBuffer(int sampleRate, int capacity) {
        super(sampleRate, capacity);
        this.left  = new float[capacity];
        this.right = new float[capacity];
    }

    @Override
    protected void set(int idx, double leftValue, double rightValue) {
        left[idx]  = (float) leftValue;
        right[idx] = (float) rightValue;
    }

    @Override
    protected void copyIn(double[] leftValues, double[] rightValues, int srcPos, int dstIdx, int len) {
        for (int i = 0; i < len; i++) {
            left [dstIdx + i] = (float) leftValues [srcPos + i];
            right[dstIdx + i] = (float) rightValues[srcPos + i];
        }
    }

    @Override
    protected void copyIn(float[] leftValues, float[] rightValues, int srcPos, int dstIdx, int len) {
        System.arraycopy(leftValues,  srcPos, left,  dstIdx, len);
        System.arraycopy(rightValues, srcPos, right, dstIdx, len);
    }

    @Override
    protected void copyOut(int srcIdx, double[] outLeft, double[] outRight, int dstPos, int len) {
        for (int i = 0; i < len; i++) {
            if (outLeft  != null) outLeft [dstPos + i] = left [srcIdx + i];
            if (outRight != null) outRight[dstPos + i] = right[srcIdx + i];
        }
    }

    @Override
    protected void copyOut(int srcIdx, float[] outLeft, float[] outRight, int dstPos, int len) {
        if (outLeft  != null) System.arraycopy(left,  srcIdx, outLeft,  dstPos, len);
        if (outRight != null) System.arraycopy(right, srcIdx, outRight, dstPos, len);
    }

    @Override
    protected void narrowTo(float[] dstLeft, float[] dstRight) {
        System.arraycopy(left,  0, dstLeft,  0, left.length);
        System.arraycopy(right, 0, dstRight, 0, right.length);
    }
}
