package org.edgo.audio.measure.gui.sound;

/**
 * Per-consumer read cursor over a shared {@link SignalBuffer}.
 *
 * <p>{@link SignalBuffer} is a single-writer ring with only an absolute
 * <em>write</em> position; it has no notion of "where a given consumer last
 * read".  That's fine for a consumer that always wants the most recent window
 * (the scope: "show me now") — it reads relative to {@code writePos} and can
 * never fall behind.  It is wrong for a consumer that needs a <b>gap-free,
 * contiguous</b> stream — the FFT cross-tick coherent accumulator, where every
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
 * read).  If the writer has lapped the cursor — the data at the read position
 * was overwritten before it was read — {@link #read} (and {@link #available})
 * report {@link #OVERRUN} so the consumer can discard its stateful accumulation
 * and re-anchor with {@link #seekToLatest()}; overlap is only valid while the
 * stream has no such breaks.
 *
 * <p>A "latest window" consumer ignores the cursor and uses the
 * {@link #readLatest}/{@link #readEndingAt} delegations, which read relative to
 * the live {@code writePos} and are inherently overrun-safe.  The reader is the
 * <em>only</em> handle a consumer ever holds — the raw {@link SignalBuffer} is
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
     *  overrun — the data it pointed at was overwritten before it was read.
     *  No samples were copied; the consumer must re-anchor (e.g.
     *  {@link #seekToLatest()}) and restart any stateful accumulation. */
    public static final int OVERRUN = -1;

    private final SignalBuffer buffer;
    /** Absolute index of the next sample this consumer will read.
     *  {@code -1} = unanchored; the first {@link #read} anchors it at the
     *  latest written sample.  Volatile so a status reader on another thread
     *  (e.g. the FFT pane's "next frame %") sees a consistent value while the
     *  owning consumer thread advances it. */
    private volatile long readPos = -1;

    public SignalBufferReader(SignalBuffer buffer) {
        if (buffer == null) throw new IllegalArgumentException("buffer must not be null");
        this.buffer = buffer;
    }

    // ─── Delegated latest-window access ─────────────────────────────────────

    public int  getSampleRate() { return buffer.getSampleRate(); }
    public int  getCapacity()   { return buffer.getCapacity(); }
    public long getWritePos()   { return buffer.getWritePos(); }

    /** @see SignalBuffer#readLatest(int, float[], float[]) */
    public int readLatest(int count, float[] outLeft, float[] outRight) {
        return buffer.readLatest(count, outLeft, outRight);
    }

    /** @see SignalBuffer#readEndingAt(long, int, float[], float[]) */
    public int readEndingAt(long absoluteEnd, int count, float[] outLeft, float[] outRight) {
        return buffer.readEndingAt(absoluteEnd, count, outLeft, outRight);
    }

    /** Returns a new reader over a standalone, frozen copy of this buffer's
     *  current contents.  No live writer touches the copy, so the snapshot
     *  never changes — the scope uses it to keep showing the last captured
     *  frame after Record stops while the shared device keeps writing for
     *  other consumers.  Keeps the raw {@link SignalBuffer} encapsulated: the
     *  caller gets back a reader, never the buffer. */
    public SignalBufferReader frozenSnapshot() {
        int sr  = buffer.getSampleRate();
        int cap = buffer.getCapacity();
        SignalBuffer frozen = new SignalBuffer(sr, (double) cap / sr);
        float[] l = new float[cap];
        float[] r = new float[cap];
        int n = buffer.readLatest(cap, l, r);
        frozen.appendBatch(l, r, n);
        return new SignalBufferReader(frozen);
    }

    // ─── Contiguous cursor ──────────────────────────────────────────────────

    /** Absolute index of the next sample {@link #read} will return, or
     *  {@code -1} while unanchored. */
    public long getReadPos() { return readPos; }

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
     * @return number of samples copied — {@code 0..maxCount}, bounded by what
     *         has been written — or {@link #OVERRUN} if the cursor was lapped
     *         (nothing copied; re-anchor and restart any accumulation).
     */
    public int read(int maxCount, float[] outLeft, float[] outRight) {
        long write = buffer.getWritePos();
        if (readPos < 0) readPos = write;                       // anchor on first use
        if (readPos < write - buffer.getCapacity()) return OVERRUN;
        int n = (int) Math.min((long) maxCount, write - readPos);
        if (n <= 0) return 0;
        buffer.readStartingAt(readPos, n, outLeft, outRight);   // forward, wrap-aware copy
        readPos += n;                                           // consume
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
        public void append(float[] left, float[] right, int count) {
            buffer.appendBatch(left, right, count);
        }

        /** Returns a reader over the filled buffer. */
        public SignalBufferReader build() {
            return new SignalBufferReader(buffer);
        }
    }
}
