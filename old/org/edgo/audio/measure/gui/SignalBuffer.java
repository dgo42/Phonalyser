package org.edgo.audio.measure.gui;

/**
 * Fixed-capacity stereo ring buffer holding the most recent {@code N} samples
 * of left and right channel data as normalised floats in {@code [-1, +1]}.
 * The capture thread calls {@link #append(float, float)}; the SWT UI thread
 * calls {@link #readLatest(int, float[], float[])} during paint events.  All
 * mutating / reading methods are synchronised on this instance, which is
 * adequate for a single writer + single reader scenario.
 */
public final class SignalBuffer {

    private final int     sampleRate;
    private final int     capacity;
    private final float[] left;
    private final float[] right;
    private long          writePos;   // total samples written since construction

    public SignalBuffer(int sampleRate, double seconds) {
        if (sampleRate <= 0 || seconds <= 0) {
            throw new IllegalArgumentException("sampleRate and seconds must be positive");
        }
        this.sampleRate = sampleRate;
        this.capacity   = (int) Math.ceil(sampleRate * seconds);
        this.left       = new float[capacity];
        this.right      = new float[capacity];
    }

    public int getSampleRate() {
        return sampleRate;
    }

    /** Total samples ever written.  Used by the UI to detect new data. */
    public synchronized long getWritePos() {
        return writePos;
    }

    /** Capacity in samples (= sampleRate × seconds, rounded up). */
    public int getCapacity() {
        return capacity;
    }

    public synchronized void append(float leftValue, float rightValue) {
        int idx = (int) (writePos % capacity);
        left[idx]  = leftValue;
        right[idx] = rightValue;
        writePos++;
    }

    /**
     * Copies up to {@code count} of the most-recently-written samples into
     * {@code outLeft} / {@code outRight} (either may be {@code null} to skip
     * that channel).  Returns the number of samples actually copied, which
     * may be less than {@code count} early on when fewer samples have been
     * captured.
     *
     * <p>Implementation: the requested span either lies entirely past the
     * ring-buffer wrap (one contiguous slice) or straddles it (two slices,
     * one to the end of the backing array, one from index 0).  Both cases
     * are handled with up to two {@link System#arraycopy} calls — a JVM
     * intrinsic that compiles down to a fast memmove — instead of a
     * per-sample modulo loop.  At 384 kHz this drops the synchronised hold
     * time from several milliseconds per paint to tens of microseconds.
     */
    public synchronized int readLatest(int count, float[] outLeft, float[] outRight) {
        long latest = writePos;
        long start  = Math.max(0, latest - count);
        int available = (int) Math.min(count, latest - start);
        if (available <= 0) return 0;
        int srcStart = (int) (start % capacity);
        int firstChunk = Math.min(available, capacity - srcStart);
        if (outLeft  != null) System.arraycopy(left,  srcStart, outLeft,  0, firstChunk);
        if (outRight != null) System.arraycopy(right, srcStart, outRight, 0, firstChunk);
        int remaining = available - firstChunk;
        if (remaining > 0) {
            if (outLeft  != null) System.arraycopy(left,  0, outLeft,  firstChunk, remaining);
            if (outRight != null) System.arraycopy(right, 0, outRight, firstChunk, remaining);
        }
        return available;
    }
}
