/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.sound.loopback.LoopbackCapture - the capture lane of
// the digital loopback, as the web's CaptureSource (the contract declared in
// audio/shared-capture.js). A THIN client that attaches and detaches the capture lane on the
// manager's one duplex session (loopback-duplex-engine.js) and hands each rendered block to the
// ring.
//
// THIS LANE PRODUCES NOTHING OF ITS OWN, silence included. The known noise floor the backend
// exists for is dither, dither belongs to the playback side, and the session's unattached
// generator lane is what emits it - so a capture with nothing playing receives the same dithered
// blocks it would receive from a playing lane, and there is no second quantiser here to keep in
// step with the first.
//
// The session renders and hands the block over SYNCHRONOUSLY on its own clock, which waits on
// nobody: there is no queue to cross and no timer of this lane's own. A block must therefore be
// read synchronously and never retained - the same contract every capture source states, and the
// reason the session can reuse one block.
//
// WEB DIVERGENCES, all forced by the platform rather than chosen:
//   - NO STALL WATCHDOG. Java arms the base class's delivery deadline nowhere in this backend, so
//     there is nothing to port; and a watchdog would be actively wrong here, because a background
//     tab's timer clamp would false-report a healthy session as a dead device. Nothing can end
//     this stream from below either - there is no device to lose - so the capture-ended callback
//     of the contract is accepted and never invoked.
//   - FLOAT BLOCKS, NOT BYTES. Java's blocks are interleaved PCM bytes that its base class
//     decodes; a web block is already the normalised {l, r, n} batch the ring consumes, so this
//     lane hands one on unchanged.

export class LoopbackCapture {

  /** @type {import('./loopback-device-manager.js').LoopbackDeviceManager} */
  #manager;
  /** @type {function():number} the SELECTED input bit depth, asked at each open(). */
  #depthOf;
  /** @type {?import('./loopback-duplex-engine.js').LoopbackDuplexEngine} */
  #engine = null;
  /** @type {?(batch: import('./loopback-duplex-engine.js').LoopbackBlock) => void} */
  #onBatch = null;
  /** Whether this lane is currently attached to the session - so a second stop() cannot detach a
   *  lane it does not hold. */
  #attached = false;
  /** The session rate for the open lane; 0 while closed. */
  #sampleRate = 0;

  /**
   * @param {import('./loopback-device-manager.js').LoopbackDeviceManager} manager the manager
   *        whose one duplex session both lanes attach to
   * @param {function():number} depthOf the selected input bit depth (16 / 20 / 24 / 32). A
   *        SUPPLIER, read at each open() and not a value frozen at construction: this source
   *        outlives a session (the engine's dispatch keeps one per backend and rebuilds it only
   *        when the backend changes), so a frozen depth would go on asking for the old resolution
   *        after the operator picked a new one.
   */
  constructor(manager, depthOf) {
    if (manager == null) {
      throw new Error('manager');
    }
    if (typeof depthOf !== 'function') {
      throw new Error('depthOf');
    }
    this.#manager = manager;
    this.#depthOf = depthOf;
  }

  /** The captured rate - exactly the requested one: a software lane has no clock of its own to
   *  re-pin the analysis to. 0 while closed. */
  get sampleRate() { return this.#sampleRate; }

  /** True while the lane is held open (Java: while it holds an engine). */
  get isOpen() { return this.#engine != null; }

  /**
   * Acquires the manager's session at this lane's format. The depth is a SESSION boundary value,
   * like the rate beside it - asked here and held for the life of the open lane - and the session
   * MOVES to it, because the loop has one clock and one depth.
   *
   * @param {string} deviceId ignored - the backend has exactly one device, exactly as Java's
   *        openCapture ignores the DeviceRef it is handed
   * @param {number} sampleRateHz the session rate (Hz)
   * @returns {Promise<void>}
   */
  async open(deviceId, sampleRateHz) {
    this.#sampleRate = sampleRateHz;
    this.#engine = this.#manager.acquireEngine(sampleRateHz, this.#depthOf());
  }

  /**
   * Attaches this lane to the session - which STARTS it when no other lane holds it open.
   *
   * @param {(batch: import('./loopback-duplex-engine.js').LoopbackBlock) => void} onBatch the ring
   *        sink; r is ch1, the calibrated/attenuated channel every capture path measures. The
   *        batch carries the session's one reused block, so a consumer must read it synchronously
   *        and must not retain it (the same contract every capture source states).
   * @param {?(reason: {name: string, logText: string}) => void} onCaptureEnded accepted for the
   *        contract and never invoked - see the header: this lane has no device that can die under
   *        it and no stall watchdog to declare one
   * @returns {Promise<void>}
   */
  async start(onBatch, onCaptureEnded) {
    if (this.#engine == null) {
      throw new Error('Call open() before start()');
    }
    this.#onBatch = onBatch;
    this.#attached = true;
    this.#engine.attachCapture(this);
  }

  /**
   * The session's capture sink, on its clock: the block is already the batch shape the ring
   * consumes, so it goes straight on. Synchronous by contract - the block is reused as soon as
   * this returns.
   *
   * @param {import('./loopback-duplex-engine.js').LoopbackBlock} block
   */
  onAudio(block) {
    const sink = this.#onBatch;
    if (sink != null) sink(block);
  }

  /** Detaches the lane, which ends the session only when no other lane holds it open. */
  async stop() {
    if (this.#attached) {
      this.#attached = false;
      this.#engine.detachCapture();
    }
    this.#onBatch = null;
  }

  /** Stops first if still attached, then lets the session go. The lane is never held open across
   *  captures: the next open() acquires again and the session it gets is the format that open
   *  asked for. */
  async close() {
    await this.stop();
    this.#engine = null;
    this.#sampleRate = 0;
  }
}
