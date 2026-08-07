/*
 * Phonalyser web - fixed-capacity stereo ring buffer.
 * Faithful port of org.edgo.audio.measure.gui.sound.SignalBuffer: holds the most
 * recent N samples of left + right as normalised Float64 in [-1,+1]. A single
 * writer (the capture handler) calls appendBatch(); readers (scope/FFT, via
 * SignalBufferReader) call readLatest / readEndingAt / readStartingAt. JS is
 * single-threaded so the Java synchronisation collapses to nothing. writePos is the
 * total samples ever written - a JS double, exact to 2^53 (~744 years at 384 kHz).
 * GNU AGPL v3 or later.
 */
import { CaptureEndReason } from './capture-end-reason.js';

export class SignalBuffer {
  /** @param {number} sampleRate @param {number} seconds ring length in seconds. */
  constructor(sampleRate, seconds) {
    if (sampleRate <= 0 || seconds <= 0) throw new Error('sampleRate and seconds must be positive');
    this.sampleRate = sampleRate;
    this.capacity = Math.ceil(sampleRate * seconds);
    this.left = new Float64Array(this.capacity);
    this.right = new Float64Array(this.capacity);
    this.writePos = 0;
    /** Why this stream ended, machine-readably - the UI localizes it at display time; whoever
     *  held the device already put its own words in the log. null while the stream is live. */
    this.finishedReason = null;
    /** Whether one consumer already claimed the reason for the operator report. */
    this.finishReasonReported = false;
    /** Absolute frame position of the most recent sweep-start mark, or -1 while none arrived
     *  this stream - see {@link #markSweepStart}. Per-stream, like every position here: a fresh
     *  capture builds a fresh buffer. A consumer arming BEFORE it commands the bench compares
     *  against its own armed position, so a stale mark from an earlier sweep on the same stream
     *  is never mistaken for the new one. */
    this.sweepMarkPos = -1;
  }

  getSampleRate() { return this.sampleRate; }
  getWritePos() { return this.writePos; }   // total samples ever written
  getCapacity() { return this.capacity; }

  /**
   * Declares this stream over: nothing will ever be appended again, because the device feeding
   * it is gone.
   *
   * The state belongs HERE, on the data path, and not with any consumer. The side that writes
   * into this ring is the side holding the sound card, so it is the only side that can KNOW a
   * stream has ended - a reader can only observe that nothing has arrived lately, which is
   * indistinguishable from a quiet input. Every reader over this buffer shares the one object,
   * so telling it once tells all of them.
   *
   * Terminal and one-way: a capture that comes back builds a NEW buffer (one per open), so
   * nothing ever needs to un-finish this one. Idempotent, because one dead device reports
   * through more than one path - the first reason wins.
   *
   * @param {?{name: string, logText: string}} reason why the stream ended (CaptureEndReason); a
   *        device that died without saying which way is DEVICE_LOST, so a finished stream is
   *        never mistaken for a live one merely because the reason was missing
   */
  finish(reason) {
    if (this.finishedReason != null) return;
    this.finishedReason = reason != null ? reason : CaptureEndReason.DEVICE_LOST;
  }

  /**
   * Records that the NEXT frame appended is a remote generator's sweep sample 0 - a bench that
   * renders the sweep itself marks that position in its capture stream (in-band, spec 5), and
   * the writer calls this at the mark's dispatch, which the stream orders exactly between two
   * batches. Like the finish state, the position belongs HERE, on the shared data, so every
   * reader answers the same one: a sweep consumer seeks its cursor to it and assembles the
   * record the mark bounds.
   */
  markSweepStart() { this.sweepMarkPos = this.writePos; }

  /** The most recent sweep-start mark's absolute frame position, or -1 while none arrived. */
  getSweepMarkPos() { return this.sweepMarkPos; }

  /** Whether the stream has ended - see {@link #finish}. */
  isFinished() { return this.finishedReason != null; }

  /** Why the stream ended, machine-readably; null while it is live. Non-claiming. */
  getFinishedReason() { return this.finishedReason; }

  /**
   * Claims the finished reason for the ONE operator report. The scope and the FFT read the same
   * dead capture through their own readers, and one unplugged device must produce one message,
   * not one per pane - so the claim lives HERE, on the shared object, like the finish state
   * itself. The first consumer to call this gets the reason and shows it; every later caller
   * gets null and stops silently. null also while the stream is still live.
   *
   * @returns {?{name: string, logText: string}}
   */
  takeFinishedReasonForReport() {
    if (this.finishReasonReported) return null;
    this.finishReasonReported = true;
    return this.finishedReason;
  }

  /** Wrap-aware batch write (Java appendBatch). leftValues/rightValues hold ≥ count
   *  samples (any TypedArray - Float32 chunks widen into the Float64 ring on .set). */
  appendBatch(leftValues, rightValues, count) {
    if (count <= 0) return;
    const cap = this.capacity;
    const writeIdx = this.writePos % cap;
    const firstChunk = Math.min(count, cap - writeIdx);
    this.left.set(leftValues.subarray(0, firstChunk), writeIdx);
    this.right.set(rightValues.subarray(0, firstChunk), writeIdx);
    const remaining = count - firstChunk;
    if (remaining > 0) {
      this.left.set(leftValues.subarray(firstChunk, count), 0);
      this.right.set(rightValues.subarray(firstChunk, count), 0);
    }
    this.writePos += count;
  }

  /** Most-recent `count` samples -> outLeft/outRight (either may be null). Returns the
   *  number actually copied (< count early on). Always overrun-safe - "show me now". */
  readLatest(count, outLeft, outRight) {
    const latest = this.writePos;
    const start = Math.max(0, latest - count);
    const available = Math.min(count, latest - start);
    if (available <= 0) return 0;
    this._copyOut(start, available, outLeft, outRight);
    return available;
  }

  /** Up to `count` samples ENDING at absoluteEnd (last copied = absoluteEnd-1),
   *  head-aligned to index 0, clipped to the resident span. */
  readEndingAt(absoluteEnd, count, outLeft, outRight) {
    const cw = this.writePos;
    const oldest = Math.max(0, cw - this.capacity);
    const endExcl = Math.min(absoluteEnd, cw);
    const start = Math.max(absoluteEnd - count, oldest);
    const available = Math.max(0, endExcl - start);
    if (available <= 0) return 0;
    this._copyOut(start, available, outLeft, outRight);
    return available;
  }

  /** Forward read of up to `count` samples STARTING at absoluteStart, head-aligned to
   *  index 0, clipped to the resident span (the primitive a forward cursor walks). */
  readStartingAt(absoluteStart, count, outLeft, outRight) {
    const cw = this.writePos;
    const oldest = Math.max(0, cw - this.capacity);
    const start = Math.max(absoluteStart, oldest);
    const endExcl = Math.min(absoluteStart + count, cw);
    const available = Math.max(0, endExcl - start);
    if (available <= 0) return 0;
    this._copyOut(start, available, outLeft, outRight);
    return available;
  }

  /** Wrap-aware copy of `available` samples from absolute `start` into outLeft/outRight
   *  (head-aligned to 0). TypedArray.set converts Float64->Float32 for the scope/WAV paths. */
  _copyOut(start, available, outLeft, outRight) {
    const cap = this.capacity;
    const srcStart = start % cap;
    const firstChunk = Math.min(available, cap - srcStart);
    if (outLeft) outLeft.set(this.left.subarray(srcStart, srcStart + firstChunk), 0);
    if (outRight) outRight.set(this.right.subarray(srcStart, srcStart + firstChunk), 0);
    const remaining = available - firstChunk;
    if (remaining > 0) {
      if (outLeft) outLeft.set(this.left.subarray(0, remaining), firstChunk);
      if (outRight) outRight.set(this.right.subarray(0, remaining), firstChunk);
    }
  }
}
