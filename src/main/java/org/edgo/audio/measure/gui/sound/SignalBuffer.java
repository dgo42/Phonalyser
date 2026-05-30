package org.edgo.audio.measure.gui.sound;

/**
 * Fixed-capacity stereo ring buffer holding the most recent {@code N} samples
 * of left and right channel data as normalised floats in {@code [-1, +1]}.
 * The capture thread calls {@link #append(float, float)}; the SWT UI thread
 * calls {@link #readLatest(int, float[], float[])} during paint events.  All
 * mutating / reading methods are synchronised on this instance, which is
 * adequate for a single writer + single reader scenario.
 *
 * <p>Lives in {@code gui.sound} (next to its producer
 * {@link SharedCapture}) so the scope and FFT views can both depend on
 * the sound package without dragging in scope-specific code.  Was
 * formerly in {@code gui.scope}; that placement created a cycle —
 * {@code gui.sound.SharedCapture} produces the buffer and {@code gui.scope.OscilloscopeController}
 * consumes it.
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
     * Writes {@code count} samples from {@code leftValues} / {@code rightValues}
     * to the ring buffer in a single synchronised section.  Replaces a per-
     * sample {@link #append(float, float)} loop so the capture thread holds
     * the monitor once per chunk (~30/s) instead of once per sample (~384 k/s
     * at 384 kHz) — dramatically reducing lock contention with the UI
     * thread's {@link #readLatest(int, float[], float[])} during paint.
     *
     * <p>Both writes use {@link System#arraycopy} so the synchronised hold
     * time stays in the tens of microseconds even for 8 k-sample chunks.
     */
    public synchronized void appendBatch(float[] leftValues, float[] rightValues, int count) {
        if (count <= 0) return;
        int writeIdx   = (int) (writePos % capacity);
        int firstChunk = Math.min(count, capacity - writeIdx);
        System.arraycopy(leftValues,  0, left,  writeIdx, firstChunk);
        System.arraycopy(rightValues, 0, right, writeIdx, firstChunk);
        int remaining = count - firstChunk;
        if (remaining > 0) {
            System.arraycopy(leftValues,  firstChunk, left,  0, remaining);
            System.arraycopy(rightValues, firstChunk, right, 0, remaining);
        }
        writePos += count;
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
    public int readLatest(int count, float[] outLeft, float[] outRight) {
        // Snapshot the write position under the lock — only this
        // critical section blocks the audio thread's appendBatch.
        // The arraycopy then runs OUTSIDE the lock: the writer can
        // race with us but only overwrites the read region after
        // (capacity − count) more samples have arrived, which at
        // typical capture rates is seconds away — far longer than
        // the few ms an arraycopy needs.  This drops the lock-hold
        // from arraycopy-of-2M-floats (~ms) to a single field read
        // (~µs), eliminating the WASAPI underruns the FFT reads were
        // causing whenever they crossed a capture-thread tick.
        long latest;
        synchronized (this) {
            latest = writePos;
        }
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

    /**
     * Reads up to {@code count} samples ending at {@code absoluteEnd}
     * (last sample copied has absolute index {@code absoluteEnd - 1}).
     * Drop-in compatible with {@link #readLatest}: data is head-aligned
     * to {@code outLeft[0]} and the return value is the number of
     * samples actually copied (≤ {@code count}).  Bounds-checked: any
     * portion of the request older than the ring's oldest still-
     * resident sample, or beyond {@code writePos}, is silently
     * clipped.  Passing {@code absoluteEnd == writePos} yields the same
     * data {@link #readLatest} would.
     */
    public int readEndingAt(long absoluteEnd, int count,
                            float[] outLeft, float[] outRight) {
        // Same decoupling as readLatest: snapshot writePos under the
        // lock, do the arraycopy outside so the audio thread's
        // appendBatch never waits on a multi-megabyte read.
        long currentWrite;
        synchronized (this) {
            currentWrite = writePos;
        }
        long oldest    = Math.max(0L, currentWrite - capacity);
        long endExcl   = Math.min(absoluteEnd, currentWrite);
        long startReq  = absoluteEnd - count;
        long start     = Math.max(startReq, oldest);
        int  available = (int) Math.max(0L, endExcl - start);
        if (available <= 0) return 0;
        int srcStart   = (int) (start % capacity);
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

    /**
     * Forward counterpart of {@link #readEndingAt}: copies up to {@code count}
     * samples STARTING at absolute index {@code absoluteStart} into
     * {@code outLeft} / {@code outRight} (either may be {@code null}),
     * head-aligned to index 0.  The natural primitive for a forward cursor
     * ({@link SignalBufferReader}) that walks the ring as a FIFO.
     *
     * <p>Bounds-checked the same way as {@link #readEndingAt}: any part of the
     * request older than the ring's oldest resident sample, or beyond
     * {@code writePos}, is silently clipped, and the return value is the number
     * of samples actually copied.  Package-private: callers walk the stream via
     * {@link SignalBufferReader}, which owns the read position and the overrun
     * policy; this method just does the wrap-aware copy.
     */
    int readStartingAt(long absoluteStart, int count,
                       float[] outLeft, float[] outRight) {
        long currentWrite;
        synchronized (this) {
            currentWrite = writePos;
        }
        long oldest    = Math.max(0L, currentWrite - capacity);
        long start     = Math.max(absoluteStart, oldest);
        long endExcl   = Math.min(absoluteStart + (long) count, currentWrite);
        int  available = (int) Math.max(0L, endExcl - start);
        if (available <= 0) return 0;
        int srcStart   = (int) (start % capacity);
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
