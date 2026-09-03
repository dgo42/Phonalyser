/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.sound.qa40x.FakeTransport (the Java
// TEST double, src/test/java/.../qa40x/FakeTransport.java).
//
// Recording Qa40xTransport test double. It logs every register write in order,
// hands back queued register-read replies, and holds audio transfers until a
// test completes them synchronously - so the engine's async discipline can be
// driven step by step, with NO hardware and NO browser.
//
// TEST HELPER, not a test: it has no assertions and is not named *.test.mjs, so
// `npm test` (node --test test/*.test.mjs) does not pick it up.
//
// Java's methods are void/int and blocking; the web seam's register and teardown
// calls return promises. These stay synchronous - `await` on a plain value is a
// no-op, so a ported engine that awaits registerWrite/registerRead/cancelAll/
// close works unchanged while a test can still drive everything on one turn.
// The two Java completeNextRead overloads collapse into one optional argument.
//
// WEB ADDITION: failNextRead / failNextWrite. The Java double has completion
// drivers only, so its engine test drives the failure path by calling
// transferFailed() on the engine directly. Here the failure path gets the same
// treatment as a completion - the pending transfer is dropped from the device's
// queue and the listener is notified - so a test exercises the failure exactly as
// the real transport delivers it (a cancelAll abort, a stalled endpoint).

import { Qa40xTransport } from '../js/qa40x/qa40x-transport.js';

export class FakeTransport extends Qa40xTransport {

  /** Register writes in submission order: {reg, value} (Java's RegWrite record). */
  registerWrites = [];
  /** Combined ordered op log for sequencing assertions (writes + reads + cancelAll + close). */
  ops = [];
  /** FIFO replies returned by registerRead (e.g. cal words). */
  readReplies = [];
  /** Snapshots (copied to `length`) of every submitted audio write, in order. */
  submittedWrites = [];
  /** Original write buffers awaiting completion. */
  pendingWriteBuffers = [];
  /** Read buffers awaiting completion. */
  pendingReadBuffers = [];

  /** Number of cancelAll() calls. */
  cancelAllCount = 0;

  /** @type {Object|null} */
  listener = null;

  /**
   * @param {number} reg
   * @param {number} value
   */
  registerWrite(reg, value) {
    this.registerWrites.push({ reg, value });
    this.ops.push(`reg=${reg}:${value}`);
  }

  /**
   * @param {number} reg
   * @returns {number} the next scripted reply, or 0 when none is queued
   */
  registerRead(reg) {
    this.ops.push(`read=${reg}`);
    const reply = this.readReplies.shift();
    return reply === undefined ? 0 : reply;
  }

  /**
   * @param {Uint8Array} data
   * @param {number} length
   */
  submitAudioWrite(data, length) {
    this.submittedWrites.push(data.slice(0, length));
    this.pendingWriteBuffers.push(data);
  }

  /** @param {Uint8Array} buffer */
  submitAudioRead(buffer) {
    this.pendingReadBuffers.push(buffer);
  }

  /** @param {Object} listener */
  setListener(listener) {
    this.listener = listener;
  }

  cancelAll() {
    this.cancelAllCount++;
    this.ops.push('cancelAll');
  }

  close() {
    this.ops.push('close');
  }

  // --- test-side drivers ---------------------------------------------------

  /**
   * Completes the oldest pending read. With `payload` (Java's
   * completeNextRead(byte[])) the synthetic audio is copied into the pending
   * buffer and the transferred count is min(payload, buffer); without it (Java's
   * no-arg overload) the buffer completes at its full length, contents as-is.
   * @param {Uint8Array} [payload] synthetic ADC bytes - interleaved LE int32 stereo
   */
  completeNextRead(payload) {
    const buffer = this.pendingReadBuffers.shift();
    if (payload === undefined) {
      this.listener.readCompleted(buffer, buffer.length);
      return;
    }
    const n = Math.min(payload.length, buffer.length);
    buffer.set(payload.subarray(0, n), 0);
    this.listener.readCompleted(buffer, n);
  }

  /** Completes the oldest pending write. */
  completeNextWrite() {
    const buffer = this.pendingWriteBuffers.shift();
    this.listener.writeCompleted(buffer, buffer.length);
  }

  /**
   * FAILS the oldest pending capture transfer - the transport reports a cancelled
   * or stalled read exactly like this (libusb's CANCELLED / a WebUSB rejection).
   * The transfer is gone from the device either way, so its buffer leaves the
   * pending queue and is NOT handed back.
   * @param {string} [detail]
   */
  failNextRead(detail = 'cancelled') {
    this.pendingReadBuffers.shift();
    this.listener.transferFailed(true, detail);
  }

  /**
   * FAILS the oldest pending playback transfer. The engine must free the failed
   * write's in-flight slot here, or its read-clocked pacing wedges at the cap.
   * @param {string} [detail]
   */
  failNextWrite(detail = 'cancelled') {
    this.pendingWriteBuffers.shift();
    this.listener.transferFailed(false, detail);
  }

  /** @returns {number} total bytes across all submitted audio writes. */
  totalWriteBytesSubmitted() {
    let total = 0;
    for (const chunk of this.submittedWrites) {
      total += chunk.length;
    }
    return total;
  }
}
