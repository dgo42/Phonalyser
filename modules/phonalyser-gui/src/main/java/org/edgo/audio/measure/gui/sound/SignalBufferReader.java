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
 * Per-consumer read cursor over a shared {@link SignalBuffer}.
 *
 * <p>{@link SignalBuffer} is a single-writer ring with only an absolute
 * <em>write</em> position; it has no notion of "where a given consumer last
 * read".  That's fine for a consumer that always wants the most recent window
 * (the scope: "show me now") - it reads relative to {@code writePos} and can
 * never fall behind.  It is wrong for a consumer that needs a <b>gap-free,
 * contiguous</b> stream - the FFT cross-tick coherent accumulator, where every
 * frame's absolute sample offset must advance by an exact, uniform hop or the
 * de-rotation smears the fundamental into a sinc.  Reading "the latest N ending
 * at writePos" each tick can't give that: between ticks the window jumps by
 * however long the worker happened to sleep, and if the worker ever falls a
 * full ring behind, the oldest unread samples are silently overwritten and the
 * next read tears across a discontinuity (the audible/visible "glitch").
 *
 * <p>This cursor holds <em>one consumer's</em> own absolute read position and
 * turns the ring into a wrapped FIFO for it: {@link #read} copies the next
 * contiguous samples from the cursor and advances it past them (a consuming
 * read).  If the writer has lapped the cursor - the data at the read position
 * was overwritten before it was read - {@link #read} (and {@link #available})
 * report {@link #OVERRUN} so the consumer can discard its stateful accumulation
 * and re-anchor with {@link #seekToLatest()}; overlap is only valid while the
 * stream has no such breaks.
 *
 * <p>A "latest window" consumer ignores the cursor and uses the
 * {@link #readLatest}/{@link #readEndingAt} delegations, which read relative to
 * the live {@code writePos} and are inherently overrun-safe.  The reader is the
 * <em>only</em> handle a consumer ever holds - the raw {@link SignalBuffer} is
 * fully encapsulated; buffer-level needs are served through the reader (e.g.
 * {@link #frozenSnapshot()} for a freeze copy, {@link #readLatest} for a save).
 *
 * <h2>Threading</h2>
 * <p>One writer, many readers: each consumer holds its own cursor over the
 * shared buffer.  The cursor's read position is owned by a single consumer
 * thread (the only one that calls {@link #read}/{@link #seek}).  The
 * latest-window delegations are stateless pass-throughs to the
 * (internally-synchronised) {@link SignalBuffer}, so the same reader may be
 * shared by several threads for those reads without contention.
 */
public final class SignalBufferReader {

    /** {@link #read}/{@link #available} return value: the cursor has been
     *  overrun - the data it pointed at was overwritten before it was read.
     *  No samples were copied; the consumer must re-anchor (e.g.
     *  {@link #seekToLatest()}) and restart any stateful accumulation. */
    public static final int OVERRUN = -1;

    private final SignalBuffer buffer;
    /** Absolute index of the next sample this consumer will read.
     *  {@code -1} = unanchored; the first {@link #read} anchors it at the
     *  latest written sample.  Volatile so a status reader on another thread
     *  (e.g. the FFT pane's "next frame %") sees a consistent value while the
     *  owning consumer thread advances it. */
    @Getter
    private volatile long readPos = -1;

    public SignalBufferReader(SignalBuffer buffer) {
        if (buffer == null) throw new IllegalArgumentException("buffer must not be null");
        this.buffer = buffer;
    }

    // ─── Delegated latest-window access ─────────────────────────────────────

    public int  getSampleRate() { return buffer.getSampleRate(); }
    public int  getCapacity()   { return buffer.getCapacity(); }
    public long getWritePos()   { return buffer.getWritePos(); }

    /**
     * Whether the stream behind this cursor has ENDED - the device feeding it is
     * gone and nothing will ever be appended again.
     *
     * <p>A reader cannot work this out for itself: from here a dead lane and a
     * silent one look identical, both being "no new samples". So the writer says
     * so ({@link SignalBuffer#finish}) and this passes the answer on, which is
     * what lets a consumer tell the operator instead of drawing a flat line over
     * a device that no longer exists.
     */
    public boolean isFinished()        { return buffer.isFinished(); }

    /** Why the stream ended, machine-readably - the pane localizes it at
     *  display time; {@code null} while the stream is live. */
    public CaptureEndReason getFinishedReason() { return buffer.getFinishedReason(); }

    /** Claims the finished reason for the ONE operator report - first caller
     *  across ALL readers of this capture gets it, everyone after gets
     *  {@code null} and stops silently.
     *  @see SignalBuffer#takeFinishedReasonForReport() */
    public CaptureEndReason takeFinishedReasonForReport() { return buffer.takeFinishedReasonForReport(); }

    /** The most recent sweep-start mark's absolute frame position, or -1 while
     *  none arrived this stream - a remote sweep consumer seeks here.
     *  @see SignalBuffer#getSweepMarkPos() */
    public long getSweepMarkPos() { return buffer.getSweepMarkPos(); }

    /** Blocks until at least {@code count} samples are available past this
     *  cursor, the stream finished, or {@code maxWaitMs} elapsed; returns the
     *  samples now available.  Owning consumer thread only - never the
     *  display thread (the paint path reads non-blocking by design).
     *  @see SignalBuffer#awaitAvailable(long, int, long) */
    public long awaitAvailable(int count, long maxWaitMs) throws InterruptedException {
        return buffer.awaitAvailable(readPos, count, maxWaitMs);
    }

    /** @see SignalBuffer#readLatest(int, double[], double[]) */
    public int readLatest(int count, double[] outLeft, double[] outRight) {
        return buffer.readLatest(count, outLeft, outRight);
    }

    /** @see SignalBuffer#readEndingAt(long, int, double[], double[]) */
    public int readEndingAt(long absoluteEnd, int count, double[] outLeft, double[] outRight) {
        return buffer.readEndingAt(absoluteEnd, count, outLeft, outRight);
    }

    /** Single-precision view of {@link #readLatest(int, double[], double[])}
     *  for the display / WAV paths (which keep their DSP in {@code float}). */
    public int readLatest(int count, float[] outLeft, float[] outRight) {
        return buffer.readLatest(count, outLeft, outRight);
    }

    /** Single-precision view of {@link #readEndingAt(long, int, double[], double[])}. */
    public int readEndingAt(long absoluteEnd, int count, float[] outLeft, float[] outRight) {
        return buffer.readEndingAt(absoluteEnd, count, outLeft, outRight);
    }

    /** Returns a new reader over a standalone, frozen copy of this buffer's
     *  current contents.  No live writer touches the copy, so the snapshot
     *  never changes - the scope uses it to keep showing the last captured
     *  frame after Record stops while the shared device keeps writing for
     *  other consumers.  Keeps the raw {@link SignalBuffer} encapsulated: the
     *  caller gets back a reader, never the buffer. */
    public SignalBufferReader frozenSnapshot() {
        int sr  = buffer.getSampleRate();
        int cap = buffer.getCapacity();
        SignalBuffer frozen = new SignalBuffer(sr, (double) cap / sr);
        double[] l = new double[cap];
        double[] r = new double[cap];
        int n = buffer.readLatest(cap, l, r);
        frozen.appendBatch(l, r, n);
        return new SignalBufferReader(frozen);
    }

    // ─── Contiguous cursor ──────────────────────────────────────────────────

    /** {@code true} once the cursor has been anchored. */
    public boolean isAnchored() { return readPos >= 0; }

    /** Anchors the cursor at the latest written sample, discarding any unread
     *  backlog.  Used on (re)start and after an {@link #OVERRUN}. */
    public void seekToLatest() { readPos = buffer.getWritePos(); }

    /** Anchors the cursor at an explicit absolute position, clamped into the
     *  ring's currently-resident span {@code [writePos − capacity, writePos]}. */
    public void seek(long absolutePos) {
        long write  = buffer.getWritePos();
        long oldest = Math.max(0L, write - buffer.getCapacity());
        readPos = Math.max(oldest, Math.min(absolutePos, write));
    }

    /** Contiguous samples waiting at the cursor right now
     *  ({@code writePos − readPos}), {@link #OVERRUN} if the cursor has been
     *  lapped, or {@code 0} while unanchored. */
    public long available() {
        if (readPos < 0) return 0;
        long write = buffer.getWritePos();
        if (readPos < write - buffer.getCapacity()) return OVERRUN;
        return write - readPos;
    }

    /**
     * Consuming forward read: copies up to {@code maxCount} contiguous samples
     * from the cursor into {@code outLeft}/{@code outRight} (either may be
     * {@code null}), head-aligned to index 0, and <b>advances the cursor</b>
     * past them so the next call continues the stream.  Anchors at the latest
     * written sample on first use.
     *
     * @return number of samples copied - {@code 0..maxCount}, bounded by what
     *         has been written - or {@link #OVERRUN} if the cursor was lapped
     *         (nothing copied; re-anchor and restart any accumulation).
     */
    public int read(int maxCount, double[] outLeft, double[] outRight) {
        return read(maxCount, outLeft, outRight, 0);
    }

    /**
     * {@link #read(int, double[], double[])} writing at {@code outOffset} rather
     * than index 0, so a consumer can GATHER one frame from several consecutive
     * reads.  That is what lets a frame longer than the ring exist at all: a
     * 4 M-point FFT at 48 kS/s spans 87 s of audio, far beyond any sane capture
     * ring, and demanding it in one contiguous read could only ever end in an
     * {@link #OVERRUN} - the cursor would have to stand still for longer than
     * the ring holds.  Consuming each tick's share into the consumer's own
     * buffer keeps the cursor right behind the writer instead.
     */
    public int read(int maxCount, double[] outLeft, double[] outRight, int outOffset) {
        long write = buffer.getWritePos();
        if (readPos < 0) readPos = write;                       // anchor on first use
        if (readPos < write - buffer.getCapacity()) return OVERRUN;
        int n = (int) Math.min((long) maxCount, write - readPos);
        if (n <= 0) return 0;
        buffer.readStartingAt(readPos, n, outLeft, outRight, outOffset);   // forward, wrap-aware copy
        // The copy runs outside the buffer lock.  When the cursor sits close
        // to a full ring behind (large-FFT backlog), the writer can lap into
        // the region being copied DURING the copy - the pre-check above can't
        // see that, and the out arrays would hold a silently torn window.
        // Re-check against the post-copy write position.
        if (readPos < buffer.getWritePos() - buffer.getCapacity()) return OVERRUN;
        readPos += n;                                           // consume
        return n;
    }

    /** Single-precision view of {@link #read(int, double[], double[])} - same
     *  consuming-cursor + overrun semantics, narrowed into {@code float} for the
     *  WAV save path. */
    public int read(int maxCount, float[] outLeft, float[] outRight) {
        long write = buffer.getWritePos();
        if (readPos < 0) readPos = write;
        if (readPos < write - buffer.getCapacity()) return OVERRUN;
        int n = (int) Math.min((long) maxCount, write - readPos);
        if (n <= 0) return 0;
        buffer.readStartingAt(readPos, n, outLeft, outRight);
        if (readPos < buffer.getWritePos() - buffer.getCapacity()) return OVERRUN;
        readPos += n;
        return n;
    }

    // ─── Producer-side factory ──────────────────────────────────────────────

    /** Starts building a standalone reader over a fresh buffer of the given
     *  capacity.  For producers <em>outside</em> the sound package (e.g. a
     *  file loader) that need to fill a buffer and hand consumers a reader,
     *  without ever touching the raw {@link SignalBuffer} themselves. */
    public static Builder builder(int sampleRate, double seconds) {
        return new Builder(sampleRate, seconds);
    }

    /** Fills a standalone {@link SignalBuffer} chunk-by-chunk, then yields a
     *  reader over it.  Mirrors {@link SignalBuffer#appendBatch} so a streaming
     *  decoder can append as it reads. */
    public static final class Builder {
        private final SignalBuffer buffer;

        private Builder(int sampleRate, double seconds) {
            this.buffer = new SignalBuffer(sampleRate, seconds);
        }

        /** Appends {@code count} decoded samples (same contract as
         *  {@link SignalBuffer#appendBatch}). */
        public void append(double[] left, double[] right, int count) {
            buffer.appendBatch(left, right, count);
        }

        /** Single-precision append for {@code float} file decoders. */
        public void append(float[] left, float[] right, int count) {
            buffer.appendBatch(left, right, count);
        }

        /** Returns a reader over the filled buffer. */
        public SignalBufferReader build() {
            return new SignalBufferReader(buffer);
        }
    }
}
