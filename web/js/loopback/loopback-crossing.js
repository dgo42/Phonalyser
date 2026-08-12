/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.sound.loopback.LoopbackCrossing - where the playback
// lane hands its quantised blocks to the capture lane. ONE instance per device manager, so the
// two lanes opened from the same manager meet; a playback with no capture (or the reverse)
// simply finds the other end idle.
//
// The queue is a single-producer / single-consumer ring: the playback lane is the only producer
// and the capture lane the only consumer. It is non-blocking on both ends, so pacing is the
// lanes' business and not the ring's - a producer that finds it full has run ahead of the
// capture clock and waits out its own block rather than spinning.
//
// WEB DIVERGENCES, all forced by the platform rather than chosen:
//   - THE RING IS ITS OWN. Java delegates to the shared SpscByteArrayRing. Nothing under web/js
//     carries block REFERENCES: audio/signal-buffer.js is a sample ring with reader cursors, and
//     the worklets hand fresh buffers across a message port. So the 32 slots and the two cursors
//     live here rather than in a reused type.
//   - NO MEMORY MODEL. The Java cursors are volatile because a render thread and a consume
//     thread read each other's writes. The browser runs both lanes on ONE task queue, so the
//     ordering those volatiles buy is free and the cursors are plain numbers.
//   - FLOAT BLOCKS, NOT BYTES. Java moves interleaved PCM bytes and the capture side decodes
//     them; the web capture path has no byte stride at all, so a block is the normalised
//     {l, r, n} batch the capture contract already speaks (loopback-playback.js states why the
//     staging is Float64 and not Float32).

/** Ring slots: blocks in flight between the lanes. A power of two, so a cursor reduces to a
 *  bitwise AND, and deliberately fixed - the capture clock, not the queue depth, decides how
 *  fast blocks move. */
const RING_SLOTS = 32;
const RING_MASK = RING_SLOTS - 1;

/**
 * One block in flight between the lanes - the shape audio/shared-capture.js already dispatches,
 * so the capture lane hands a polled block on unchanged.
 *
 * @typedef {Object} LoopbackBlock
 * @property {Float64Array} l ch0, normalised to -1...+1
 * @property {Float64Array} r ch1, the calibrated/attenuated channel every capture path measures
 * @property {number} n frames carried in this block
 */

export class LoopbackCrossing {

  /** @type {Array<?LoopbackBlock>} */
  #slots = new Array(RING_SLOTS).fill(null);
  /** Producer cursor: total blocks ever offered. */
  #writePos = 0;
  /** Consumer cursor: total blocks ever polled. */
  #readPos = 0;

  /**
   * Offers one block to the capture lane.
   *
   * @param {LoopbackBlock} block the block to hand over
   * @returns {boolean} false when the ring is full - the caller keeps the block, which must not
   *          be recycled while the consumer may still be reading an earlier one
   */
  offer(block) {
    if (this.#writePos - this.#readPos >= RING_SLOTS) {
      return false;
    }
    this.#slots[this.#writePos & RING_MASK] = block;
    this.#writePos++;
    return true;
  }

  /**
   * @returns {?LoopbackBlock} the next block, or null when the playback lane has not produced
   *          one - the capture lane then delivers its own dithered silence for that slot
   */
  poll() {
    if (this.#readPos >= this.#writePos) {
      return null;
    }
    const index = this.#readPos & RING_MASK;
    const block = this.#slots[index];
    this.#slots[index] = null;      // the slot lets go of the block with the read
    this.#readPos++;
    return block;
  }

  /** Drops whatever is in flight, so a capture that starts after an earlier one cannot be
   *  handed the previous run's tail. It DRAINS rather than resetting the cursors: the cursors
   *  are the ring's only ordering, and rewinding them under a lane that is still running would
   *  make a full ring read as empty. */
  clear() {
    while (this.poll() != null) {
      // discard
    }
  }
}
