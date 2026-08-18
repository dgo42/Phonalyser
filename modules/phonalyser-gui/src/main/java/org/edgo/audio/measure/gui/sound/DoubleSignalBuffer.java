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
 * {@link SignalBuffer} backed by {@code double[]} storage - the precision the
 * FFT pipeline needs (coherent averaging accumulates far below a float
 * mantissa).  The ONE live capture ring is a double buffer, because the scope
 * and the FFT read the same ring; float storage exists only on scope-private
 * copies ({@link FloatSignalBuffer}).
 */
public final class DoubleSignalBuffer extends SignalBuffer {

    private final double[] left;
    private final double[] right;

    public DoubleSignalBuffer(int sampleRate, double seconds) {
        super(sampleRate, seconds);
        this.left  = new double[getCapacity()];
        this.right = new double[getCapacity()];
    }

    @Override
    protected void set(int idx, double leftValue, double rightValue) {
        left[idx]  = leftValue;
        right[idx] = rightValue;
    }

    @Override
    protected void copyIn(double[] leftValues, double[] rightValues, int srcPos, int dstIdx, int len) {
        System.arraycopy(leftValues,  srcPos, left,  dstIdx, len);
        System.arraycopy(rightValues, srcPos, right, dstIdx, len);
    }

    @Override
    protected void copyIn(float[] leftValues, float[] rightValues, int srcPos, int dstIdx, int len) {
        for (int i = 0; i < len; i++) {
            left [dstIdx + i] = leftValues [srcPos + i];
            right[dstIdx + i] = rightValues[srcPos + i];
        }
    }

    @Override
    protected void copyOut(int srcIdx, double[] outLeft, double[] outRight, int dstPos, int len) {
        if (outLeft  != null) System.arraycopy(left,  srcIdx, outLeft,  dstPos, len);
        if (outRight != null) System.arraycopy(right, srcIdx, outRight, dstPos, len);
    }

    @Override
    protected void copyOut(int srcIdx, float[] outLeft, float[] outRight, int dstPos, int len) {
        for (int i = 0; i < len; i++) {
            if (outLeft  != null) outLeft [dstPos + i] = (float) left [srcIdx + i];
            if (outRight != null) outRight[dstPos + i] = (float) right[srcIdx + i];
        }
    }

    @Override
    protected void narrowTo(float[] dstLeft, float[] dstRight) {
        for (int i = 0; i < left.length; i++) {
            dstLeft [i] = (float) left [i];
            dstRight[i] = (float) right[i];
        }
    }
}
