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

import org.edgo.audio.measure.sound.CaptureEndReason;

import lombok.Getter;

/**
 * Fixed-capacity stereo ring buffer holding the most recent {@code N} samples
 * of left and right channel data as normalised doubles in {@code [-1, +1]}.
 * The capture thread calls {@link #append(double, double)}; the SWT UI thread
 * calls {@link #readLatest(int, double[], double[])} during paint events.  All
 * mutating / reading methods are synchronised on this instance, which is
 * adequate for a single writer + single reader scenario.
 *
 * <p>Lives in {@code gui.sound} (next to its producer
 * {@link SharedCapture}) so the scope and FFT views can both depend on
 * the sound package without dragging in scope-specific code.  Was
 * formerly in {@code gui.scope}; that placement created a cycle -
 * {@code gui.sound.SharedCapture} produces the buffer and {@code gui.scope.OscilloscopeController}
 * consumes it.
 */
public final class SignalBuffer {

    private final int     sampleRate;
    private final int     capacity;
    private final double[] left;
    private final double[] right;
    private long          writePos;   // total samples written since construction
    /** Why this stream ended, machine-readably - the GUI localizes it at
     *  display time; whoever held the device already put its own words in the
     *  log.  {@code null} while the stream is still live.  See {@link #finish}. */
    @Getter
    private volatile CaptureEndReason finishedReason;
    /** Whether one consumer already claimed the finished reason for the
     *  operator report - see {@link #takeFinishedReasonForReport}. */
    private boolean finishReasonReported;
    /** Absolute frame position of the most recent sweep-start mark, or -1
     *  while none arrived this stream - see {@link #markSweepStart}.
     *  Per-stream, like every position here: a fresh capture builds a fresh
     *  buffer.  A consumer arming BEFORE it commands the bench compares
     *  against its own armed position, so a stale mark from an earlier sweep
     *  on the same stream is never mistaken for the new one. */
    @Getter
    private volatile long sweepMarkPos = -1;

    public SignalBuffer(int sampleRate, double seconds) {
        if (sampleRate <= 0 || seconds <= 0) {
            throw new IllegalArgumentException("sampleRate and seconds must be positive");
        }
        this.sampleRate = sampleRate;
        this.capacity   = (int) Math.ceil(sampleRate * seconds);
        this.left       = new double[capacity];
        this.right      = new double[capacity];
    }

    public int getSampleRate() {
        return sampleRate;
    }

    /** Total samples ever written.  Used by the UI to detect new data. */
    public synchronized long getWritePos() {
        return writePos;
    }

    /**
     * Declares this stream over: nothing will ever be appended again, because the
     * device feeding it is gone.
     *
     * <p>The state belongs HERE, on the data path, and not with any consumer.  The
     * side that writes into this ring is the side holding the sound card, so it is
     * the only side that can KNOW a stream has ended - a reader can only observe
     * that nothing has arrived lately, which is indistinguishable from a quiet
     * input.  Every reader over this buffer shares the one object, so telling it
     * once tells all of them, and the reference count that hands readers out is
     * left to do only what it is for.
     *
     * <p>Terminal and one-way.  A capture that comes back builds a NEW buffer -
     * one per open - so nothing ever needs to un-finish this one and the flag
     * cannot outlive the lane it describes.  Idempotent, because one dead device
     * reports through more than one path (the backend's device-lost callback and
     * the batch callback's own failure): the first reason wins.
     *
     * @param reason why the stream ended, machine-readably; a device that died
     *               without saying which way falls back to
     *               {@link CaptureEndReason#DEVICE_LOST}, so a finished stream
     *               is never mistaken for a live one merely because the reason
     *               was missing
     */
    public synchronized void finish(CaptureEndReason reason) {
        if (finishedReason != null) return;
        finishedReason = reason != null ? reason : CaptureEndReason.DEVICE_LOST;
        notifyAll();   // a consumer blocked in awaitAvailable reacts to the death NOW
    }

    /** Whether the stream has ended - see {@link #finish}. */
    public boolean isFinished() {
        return finishedReason != null;
    }

    /**
     * Claims the finished reason for the ONE operator report.  The scope and
     * the FFT read the same dead capture through their own readers, and one
     * unplugged device must produce one message, not one per pane - so the
     * claim lives HERE, on the shared object, like the finish state itself.
     * The first consumer to call this gets the reason and shows it; every
     * later caller gets {@code null} and stops silently.  {@code null} also
     * while the stream is still live.
     */
    public synchronized CaptureEndReason takeFinishedReasonForReport() {
        if (finishReasonReported) return null;
        finishReasonReported = true;
        return finishedReason;
    }

    /**
     * Records that the NEXT frame appended is a remote generator's sweep sample
     * 0 - a bench that renders the sweep itself marks that position in its
     * capture stream (in-band, spec 5), and the writer calls this at the mark's
     * dispatch, which the stream orders exactly between two batches.  Like the
     * finish state, the position belongs HERE, on the shared data, so every
     * reader answers the same one: a sweep consumer seeks its cursor to it and
     * assembles the record the mark bounds.  Notifies, so a consumer blocked in
     * {@link #awaitAvailable} re-checks its mark without waiting out the cap.
     */
    public synchronized void markSweepStart() {
        sweepMarkPos = writePos;
        notifyAll();
    }

    /** Capacity in samples (= sampleRate × seconds, rounded up). */
    public int getCapacity() {
        return capacity;
    }

    public synchronized void append(double leftValue, double rightValue) {
        int idx = (int) (writePos % capacity);
        left[idx]  = leftValue;
        right[idx] = rightValue;
        writePos++;
        notifyAll();   // wake consumers blocked in awaitAvailable
    }

    /**
     * Writes {@code count} samples from {@code leftValues} / {@code rightValues}
     * to the ring buffer in a single synchronised section.  Replaces a per-
     * sample {@link #append(double, double)} loop so the capture thread holds
     * the monitor once per chunk (~30/s) instead of once per sample (~384 k/s
     * at 384 kHz) - dramatically reducing lock contention with the UI
     * thread's {@link #readLatest(int, double[], double[])} during paint.
     *
     * <p>Both writes use {@link System#arraycopy} so the synchronised hold
     * time stays in the tens of microseconds even for 8 k-sample chunks.
     */
    public synchronized void appendBatch(double[] leftValues, double[] rightValues, int count) {
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
        notifyAll();   // wake consumers blocked in awaitAvailable
    }

    /**
     * Blocks until at least {@code count} samples have been written past
     * {@code fromPos}, the stream {@linkplain #finish finished}, or
     * {@code maxWaitMs} elapsed - whichever comes first.  Returns the number
     * of samples now available past {@code fromPos} (less than {@code count}
     * on timeout or a finished stream; the caller re-checks its own state
     * either way).  A negative {@code fromPos} (an unanchored cursor) waits
     * relative to the current write position.
     *
     * <p>The wakeup half of the ring: the writer {@code notifyAll}s on every
     * append AND on {@link #finish}, so a blocked consumer starts its tick the
     * moment its data really exists - and reacts to device death immediately -
     * instead of sleeping a rate-derived estimate.  The predicate re-check per
     * batch costs nanoseconds; consumers still run their expensive tick once
     * per requested span.
     */
    public synchronized long awaitAvailable(long fromPos, int count, long maxWaitMs)
            throws InterruptedException {
        if (fromPos < 0) fromPos = writePos;
        long deadlineNanos = System.nanoTime() + maxWaitMs * 1_000_000L;
        while (writePos - fromPos < count && finishedReason == null) {
            long leftNanos = deadlineNanos - System.nanoTime();
            if (leftNanos <= 0) break;
            wait(Math.max(1L, leftNanos / 1_000_000L));
        }
        return writePos - fromPos;
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
     * are handled with up to two {@link System#arraycopy} calls - a JVM
     * intrinsic that compiles down to a fast memmove - instead of a
     * per-sample modulo loop.  At 384 kHz this drops the synchronised hold
     * time from several milliseconds per paint to tens of microseconds.
     */
    public int readLatest(int count, double[] outLeft, double[] outRight) {
        // Snapshot the write position under the lock - only this
        // critical section blocks the audio thread's appendBatch.
        // The arraycopy then runs OUTSIDE the lock: the writer can
        // race with us but only overwrites the read region after
        // (capacity − count) more samples have arrived, which at
        // typical capture rates is seconds away - far longer than
        // the few ms an arraycopy needs.  This drops the lock-hold
        // from arraycopy-of-2M-doubles (~ms) to a single field read
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
                            double[] outLeft, double[] outRight) {
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
                       double[] outLeft, double[] outRight) {
        return readStartingAt(absoluteStart, count, outLeft, outRight, 0);
    }

    /** {@link #readStartingAt(long, int, double[], double[])} writing at
     *  {@code outOffset} instead of index 0, so a consumer can gather one frame
     *  from SEVERAL forward reads - the only way to assemble a frame longer than
     *  the ring itself. */
    int readStartingAt(long absoluteStart, int count,
                       double[] outLeft, double[] outRight, int outOffset) {
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
        if (outLeft  != null) System.arraycopy(left,  srcStart, outLeft,  outOffset, firstChunk);
        if (outRight != null) System.arraycopy(right, srcStart, outRight, outOffset, firstChunk);
        int remaining = available - firstChunk;
        if (remaining > 0) {
            if (outLeft  != null) System.arraycopy(left,  0, outLeft,  outOffset + firstChunk, remaining);
            if (outRight != null) System.arraycopy(right, 0, outRight, outOffset + firstChunk, remaining);
        }
        return available;
    }

    // ── Single-precision views for the display / WAV paths ───────────────────
    // The oscilloscope and WAV save/load don't need double precision, so they
    // read / write a float view of the (double) ring instead of dragging their
    // DSP to double.  Narrowing / widening is per-element - System.arraycopy
    // can't convert between float and double.

    /** {@link #readLatest(int, double[], double[])} into single-precision out
     *  buffers (the caller's DSP stays {@code float}). */
    public int readLatest(int count, float[] outLeft, float[] outRight) {
        long latest;
        synchronized (this) { latest = writePos; }
        long start  = Math.max(0, latest - count);
        int available = (int) Math.min(count, latest - start);
        if (available <= 0) return 0;
        int srcStart = (int) (start % capacity);
        int firstChunk = Math.min(available, capacity - srcStart);
        if (outLeft  != null) narrow(left,  srcStart, outLeft,  0, firstChunk);
        if (outRight != null) narrow(right, srcStart, outRight, 0, firstChunk);
        int remaining = available - firstChunk;
        if (remaining > 0) {
            if (outLeft  != null) narrow(left,  0, outLeft,  firstChunk, remaining);
            if (outRight != null) narrow(right, 0, outRight, firstChunk, remaining);
        }
        return available;
    }

    /** {@link #readEndingAt(long, int, double[], double[])} into float out buffers. */
    public int readEndingAt(long absoluteEnd, int count, float[] outLeft, float[] outRight) {
        long currentWrite;
        synchronized (this) { currentWrite = writePos; }
        long oldest    = Math.max(0L, currentWrite - capacity);
        long endExcl   = Math.min(absoluteEnd, currentWrite);
        long start     = Math.max(absoluteEnd - count, oldest);
        int  available = (int) Math.max(0L, endExcl - start);
        if (available <= 0) return 0;
        int srcStart   = (int) (start % capacity);
        int firstChunk = Math.min(available, capacity - srcStart);
        if (outLeft  != null) narrow(left,  srcStart, outLeft,  0, firstChunk);
        if (outRight != null) narrow(right, srcStart, outRight, 0, firstChunk);
        int remaining = available - firstChunk;
        if (remaining > 0) {
            if (outLeft  != null) narrow(left,  0, outLeft,  firstChunk, remaining);
            if (outRight != null) narrow(right, 0, outRight, firstChunk, remaining);
        }
        return available;
    }

    /** {@link #readStartingAt(long, int, double[], double[])} into float out buffers. */
    int readStartingAt(long absoluteStart, int count, float[] outLeft, float[] outRight) {
        long currentWrite;
        synchronized (this) { currentWrite = writePos; }
        long oldest    = Math.max(0L, currentWrite - capacity);
        long start     = Math.max(absoluteStart, oldest);
        long endExcl   = Math.min(absoluteStart + (long) count, currentWrite);
        int  available = (int) Math.max(0L, endExcl - start);
        if (available <= 0) return 0;
        int srcStart   = (int) (start % capacity);
        int firstChunk = Math.min(available, capacity - srcStart);
        if (outLeft  != null) narrow(left,  srcStart, outLeft,  0, firstChunk);
        if (outRight != null) narrow(right, srcStart, outRight, 0, firstChunk);
        int remaining = available - firstChunk;
        if (remaining > 0) {
            if (outLeft  != null) narrow(left,  0, outLeft,  firstChunk, remaining);
            if (outRight != null) narrow(right, 0, outRight, firstChunk, remaining);
        }
        return available;
    }

    /** {@link #appendBatch(double[], double[], int)} from single-precision sources (WAV load). */
    public synchronized void appendBatch(float[] leftValues, float[] rightValues, int count) {
        if (count <= 0) return;
        int writeIdx   = (int) (writePos % capacity);
        int firstChunk = Math.min(count, capacity - writeIdx);
        widen(leftValues,  0, left,  writeIdx, firstChunk);
        widen(rightValues, 0, right, writeIdx, firstChunk);
        int remaining = count - firstChunk;
        if (remaining > 0) {
            widen(leftValues,  firstChunk, left,  0, remaining);
            widen(rightValues, firstChunk, right, 0, remaining);
        }
        writePos += count;
    }

    private void narrow(double[] src, int srcPos, float[] dst, int dstPos, int len) {
        for (int i = 0; i < len; i++) dst[dstPos + i] = (float) src[srcPos + i];
    }

    private void widen(float[] src, int srcPos, double[] dst, int dstPos, int len) {
        for (int i = 0; i < len; i++) dst[dstPos + i] = src[srcPos + i];
    }
}
