/*
 * Phonalyser web - per-consumer read cursor over a shared SignalBuffer.
 * Faithful port of org.edgo.audio.measure.gui.sound.SignalBufferReader.
 *
 * The ring has only an absolute WRITE position. A "latest window" consumer (the
 * scope: "show me now") ignores the cursor and uses readLatest/readEndingAt -
 * inherently overrun-safe. A "contiguous stream" consumer (the FFT cross-tick
 * coherent accumulator, whose every frame must advance by an exact uniform hop or
 * the de-rotation smears the fundamental) uses read(): a consuming forward read
 * that advances readPos, and returns OVERRUN if the writer lapped the cursor - the
 * consumer then discards its accumulation and re-anchors with seekToLatest().
 * Each consumer holds its OWN reader over the one shared buffer.
 * GNU AGPL v3 or later.
 */
import { SignalBuffer } from './signal-buffer.js';

/** read()/available() sentinel: the cursor was overrun (its data was overwritten
 *  before it was read). Nothing copied; re-anchor and restart any accumulation. */
export const OVERRUN = -1;

export class SignalBufferReader {
  constructor(buffer) {
    if (!buffer) throw new Error('buffer must not be null');
    this.buffer = buffer;
    this.readPos = -1;   // -1 = unanchored; first read anchors at the latest sample
  }

  getSampleRate() { return this.buffer.getSampleRate(); }
  getCapacity() { return this.buffer.getCapacity(); }
  getWritePos() { return this.buffer.getWritePos(); }
  getReadPos() { return this.readPos; }

  /**
   * Whether the stream behind this cursor has ENDED - the device feeding it is gone and nothing
   * will ever be appended again.
   *
   * A reader cannot work this out for itself: from here a dead lane and a silent one look
   * identical, both being "no new samples". So the writer says so (SignalBuffer.finish) and this
   * passes the answer on, which is what lets a consumer tell the operator instead of drawing a
   * flat line over a device that no longer exists.
   */
  isFinished() { return this.buffer.isFinished(); }

  /** Why the stream ended, machine-readably - the pane localizes it at display time; null while
   *  the stream is live. Non-claiming. */
  getFinishedReason() { return this.buffer.getFinishedReason(); }

  /** Claims the finished reason for the ONE operator report - the first caller across ALL
   *  readers of this capture gets it, everyone after gets null and stops silently. */
  takeFinishedReasonForReport() { return this.buffer.takeFinishedReasonForReport(); }

  /** The most recent sweep-start mark's absolute frame position, or -1 while none arrived this
   *  stream - a remote sweep consumer seeks here (spec 5's MARKER). */
  getSweepMarkPos() { return this.buffer.getSweepMarkPos(); }

  // ── delegated latest-window access (overrun-safe) ──
  readLatest(count, outLeft, outRight) { return this.buffer.readLatest(count, outLeft, outRight); }
  readEndingAt(absoluteEnd, count, outLeft, outRight) { return this.buffer.readEndingAt(absoluteEnd, count, outLeft, outRight); }

  /** A new reader over a standalone frozen copy of the current contents - the scope
   *  keeps showing the last captured frame after Record stops while the shared device
   *  keeps writing for other consumers. */
  frozenSnapshot() {
    const sr = this.buffer.getSampleRate(), cap = this.buffer.getCapacity();
    const frozen = new SignalBuffer(sr, cap / sr);
    const l = new Float64Array(cap), r = new Float64Array(cap);
    const n = this.buffer.readLatest(cap, l, r);
    frozen.appendBatch(l, r, n);
    return new SignalBufferReader(frozen);
  }

  // ── contiguous consuming cursor ──
  isAnchored() { return this.readPos >= 0; }

  /** Anchor at the latest written sample, discarding the unread backlog (on (re)start
   *  and after an OVERRUN). */
  seekToLatest() { this.readPos = this.buffer.getWritePos(); }

  /** Anchor at an explicit absolute position, clamped into the resident span. */
  seek(absolutePos) {
    const write = this.buffer.getWritePos();
    const oldest = Math.max(0, write - this.buffer.getCapacity());
    this.readPos = Math.max(oldest, Math.min(absolutePos, write));
  }

  /** Contiguous samples waiting now (writePos − readPos), OVERRUN if lapped, 0 if
   *  unanchored. */
  available() {
    if (this.readPos < 0) return 0;
    const write = this.buffer.getWritePos();
    if (this.readPos < write - this.buffer.getCapacity()) return OVERRUN;
    return write - this.readPos;
  }

  /** Consuming forward read: up to maxCount contiguous samples from the cursor into
   *  outLeft/outRight (either may be null), head-aligned to 0, advancing readPos.
   *  Anchors at the latest sample on first use. Returns the copied count (0..maxCount)
   *  or OVERRUN (nothing copied - re-anchor + restart accumulation). */
  read(maxCount, outLeft, outRight) {
    const write = this.buffer.getWritePos();
    if (this.readPos < 0) this.readPos = write;                 // anchor on first use
    if (this.readPos < write - this.buffer.getCapacity()) return OVERRUN;
    const n = Math.min(maxCount, write - this.readPos);
    if (n <= 0) return 0;
    this.buffer.readStartingAt(this.readPos, n, outLeft, outRight);
    if (this.readPos < this.buffer.getWritePos() - this.buffer.getCapacity()) return OVERRUN;
    this.readPos += n;                                          // consume
    return n;
  }
}
