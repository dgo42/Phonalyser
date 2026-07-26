/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.sound.qa40x.Qa40xTransport.
//
// Transport seam for the QA402/QA403 USB protocol — the byte pipe between the
// measurement engine and the analyzer, with no protocol knowledge above the wire
// level. It moves four kinds of traffic over interface 0's four bulk endpoints
// (doc/QA40X-PROTOCOL.md §3/§4/§5):
//
//   - register WRITES — a 5-byte big-endian frame on EP 0x01 OUT;
//   - register READS  — a 0x80|reg request write followed by a 4-byte big-endian
//                       reply on EP 0x81 IN;
//   - audio PLAYBACK  — async writes on EP 0x02 OUT;
//   - audio CAPTURE   — async reads on EP 0x82 IN.
//
// Register traffic is request/response (Java blocks on a bulk transfer; here the
// same call returns a promise); audio traffic is asynchronous — submitted here
// and completed later, reported back through a TransferListener. The full-duplex
// discipline (>=2 transfers in flight per direction, read-completion clock) lives
// in the engine that drives this seam, not here.
//
// Stop discipline. cancelAll() aborts in-flight transfers and close() tears the
// session down; neither ever pipe-resets or clear-halts an endpoint — doing so
// hangs the next session's first read (doc §3).
//
// Register values are big-endian even though audio samples are little-endian —
// two independent endiannesses that this seam neither imposes nor conflates
// (doc §4/§5).
//
// JS has no interfaces, so the Java interface is mirrored as a base class whose
// methods throw: implementations extend it (WebUsbQa40xTransport) or simply
// duck-type the same method names (the test FakeTransport). Method names and the
// argument order are the Java ones, camelCase unchanged.

/**
 * Callback sink for async audio transfers.
 *
 * Java invokes these on the transport's libusb event thread; the web port
 * invokes them from the transport's per-direction completion pump, i.e. on the
 * one and only JS task queue — but with the same contract: completions arrive in
 * SUBMISSION order per direction, and a listener callback may re-submit (the
 * engine re-arms its read from inside readCompleted).
 */
export class TransferListener {

  /**
   * A playback transfer finished; `transferred` bytes of `buffer` were sent.
   * @param {Uint8Array} buffer the buffer handed to submitAudioWrite
   * @param {number} transferred bytes actually sent
   */
  writeCompleted(buffer, transferred) {
    throw new Error('TransferListener.writeCompleted is not implemented');
  }

  /**
   * A capture transfer finished; `transferred` bytes of `buffer` were filled.
   * @param {Uint8Array} buffer the buffer handed to submitAudioRead, now filled
   * @param {number} transferred bytes actually received
   */
  readCompleted(buffer, transferred) {
    throw new Error('TransferListener.readCompleted is not implemented');
  }

  /**
   * A transfer failed (or was cancelled); `read` tells the direction, `detail`
   * the reason. A transfer cancelled by cancelAll()/close() lands here too — the
   * engine treats that as benign, exactly as it does the libusb CANCELLED status.
   * @param {boolean} read true for a capture transfer, false for playback
   * @param {string} detail human-readable reason
   */
  transferFailed(read, detail) {
    throw new Error('TransferListener.transferFailed is not implemented');
  }
}

/**
 * The transport contract — see the module comment. Every method mirrors
 * org.edgo.audio.measure.sound.qa40x.Qa40xTransport one for one; the only
 * deviation is that the two register calls and the two teardown calls return
 * promises, because a browser cannot block.
 */
export class Qa40xTransport {

  /**
   * Writes `value` to register `reg` — a 5-byte big-endian frame on EP 0x01 OUT.
   * Java returns void from a blocking bulk transfer; here the promise settles
   * when the transfer completes. Callers must serialize register traffic (the
   * engine's ioLock does this) — the seam does not queue it.
   * @param {number} reg
   * @param {number} value
   * @returns {Promise<void>}
   */
  registerWrite(reg, value) {
    throw new Error('Qa40xTransport.registerWrite is not implemented');
  }

  /**
   * Reads register `reg` — sends 0x80|reg, then decodes the 4-byte big-endian
   * reply from EP 0x81 IN into a signed 32-bit word.
   * @param {number} reg
   * @returns {Promise<number>}
   */
  registerRead(reg) {
    throw new Error('Qa40xTransport.registerRead is not implemented');
  }

  /**
   * Submits an async playback transfer of `length` bytes from `data` on
   * EP 0x02 OUT. Returns immediately; completion arrives at
   * TransferListener#writeCompleted with the SAME `data` buffer, so the caller's
   * buffer pool can reclaim it there.
   * @param {Uint8Array} data
   * @param {number} length
   * @returns {void}
   */
  submitAudioWrite(data, length) {
    throw new Error('Qa40xTransport.submitAudioWrite is not implemented');
  }

  /**
   * Submits an async capture transfer filling up to `buffer.length` bytes from
   * EP 0x82 IN. Returns immediately; completion arrives at
   * TransferListener#readCompleted with the SAME buffer, filled.
   * @param {Uint8Array} buffer
   * @returns {void}
   */
  submitAudioRead(buffer) {
    throw new Error('Qa40xTransport.submitAudioRead is not implemented');
  }

  /**
   * Installs the listener that receives audio-transfer completions and failures.
   * @param {TransferListener|Object} listener
   * @returns {void}
   */
  setListener(listener) {
    throw new Error('Qa40xTransport.setListener is not implemented');
  }

  /**
   * Cancels in-flight transfers; never pipe-resets or clear-halts an endpoint
   * (doc §3). The engine calls this strictly BEFORE writing reg 8 = RUN_STOP
   * (doc §7 step 7), so the transport must stay usable for register traffic
   * afterwards.
   * @returns {Promise<void>}
   */
  cancelAll() {
    throw new Error('Qa40xTransport.cancelAll is not implemented');
  }

  /**
   * Releases interface 0 and closes the device handle. Java's AutoCloseable
   * close(); here the promise settles once the session is torn down.
   * @returns {Promise<void>}
   */
  close() {
    throw new Error('Qa40xTransport.close is not implemented');
  }
}
