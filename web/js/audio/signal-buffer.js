/*
 * Phonalyser web — fixed-capacity stereo ring buffer.
 * Faithful port of org.edgo.audio.measure.gui.sound.SignalBuffer: holds the most
 * recent N samples of left + right as normalised Float64 in [-1,+1]. A single
 * writer (the capture handler) calls appendBatch(); readers (scope/FFT, via
 * SignalBufferReader) call readLatest / readEndingAt / readStartingAt. JS is
 * single-threaded so the Java synchronisation collapses to nothing. writePos is the
 * total samples ever written — a JS double, exact to 2^53 (~744 years at 384 kHz).
 * GNU AGPL v3 or later.
 */
export class SignalBuffer {
  /** @param {number} sampleRate @param {number} seconds ring length in seconds. */
  constructor(sampleRate, seconds) {
    if (sampleRate <= 0 || seconds <= 0) throw new Error('sampleRate and seconds must be positive');
    this.sampleRate = sampleRate;
    this.capacity = Math.ceil(sampleRate * seconds);
    this.left = new Float64Array(this.capacity);
    this.right = new Float64Array(this.capacity);
    this.writePos = 0;
  }

  getSampleRate() { return this.sampleRate; }
  getWritePos() { return this.writePos; }   // total samples ever written
  getCapacity() { return this.capacity; }

  /** Wrap-aware batch write (Java appendBatch). leftValues/rightValues hold ≥ count
   *  samples (any TypedArray — Float32 chunks widen into the Float64 ring on .set). */
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

  /** Most-recent `count` samples → outLeft/outRight (either may be null). Returns the
   *  number actually copied (< count early on). Always overrun-safe — "show me now". */
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
   *  (head-aligned to 0). TypedArray.set converts Float64→Float32 for the scope/WAV paths. */
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
