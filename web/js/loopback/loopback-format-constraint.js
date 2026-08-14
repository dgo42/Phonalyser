/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.gui.backend.loopback.LoopbackFormatConstraint - the rule
// that enforces the digital loopback's single format: its playback lane's samples ARE its capture
// lane's samples, so input and output can differ in neither sample rate NOR bit depth. When one
// direction is edited in Preferences the other must follow in both fields.
//
// The analyzer's constraint (qa40x-rate-constraint.js) is the sibling of this one and covers the
// rate alone: a QA40x's two directions share one reg-9 clock but keep their own widths. The
// loopback shares the SAMPLES, which is a stricter thing - a block quantised at one depth cannot
// be delivered as another - so the depth is coupled here as well.
//
// The Preferences dialog edits an UNCOMMITTED working copy, so this cannot read the change off
// live state - it gates on the backend the CHANGE PAYLOAD carries and acts only for the loopback.
// A change on any other backend forgets the recorded pair, so a stale one can never mis-fire once
// the loopback is chosen again.
//
// It records the last format seen per direction and, when a change makes the two differ, answers
// with the correction for the OTHER direction AND updates its own record of that direction to
// match. The pair is then equal in its state, so the follow-up change for the corrected direction
// is a no-op: the round-trip terminates by construction (equal records => nothing emitted),
// independently of any programmatic-select-fires-no-event behaviour in the dialog.
//
// Like the Java it is a bus-wired singleton: construction subscribes to PREFS_SAMPLE_RATE_CHANGED
// and a correction is published as PREFS_SAMPLE_RATE_SET. LoopbackDeviceManager's constructor
// calls instance() - Java arms it from the backend's settings-UI service, which the web does not
// have - so the subscription is live from the first loopback dispatch, before any combo can move.
//
// DELIBERATE DEVIATION, the same one the analyzer's constraint makes: onFormatChanged() also
// RETURNS the correction it published (or null), which the void Java method cannot. The bus
// swallows a throwing subscriber, so a bus-driven "nothing was published" assertion would stay
// green over a crash - the decision has to stay drivable directly. Nothing publishes on its
// behalf: the return value is an answer, not a request.

import { Events } from '../bus/events.js';
import { MessageBus } from '../bus/message-bus.js';
import { LOOPBACK_BACKEND } from './loopback-device-ref.js';

/** Nothing recorded yet for a direction - the first change there only syncs the other; it never
 *  compares against a stale value. */
const UNSET = -1;

/** Module-scope holder for the singleton - the JS equivalent of the Java static field
 *  (single-threaded, so no double-checked locking is needed). */
let singleton = null;

/**
 * A format change for ONE direction, as the Preferences dialog is being edited - the shape of the
 * Java record gui.bus.SampleRateChange, which stays a bus payload and is therefore NOT re-declared
 * as a class here.
 *
 * @typedef {Object} SampleRateChange
 * @property {boolean} input        true for the input (capture) direction, false for output
 * @property {number}  sampleRateHz that direction's sample rate in hertz
 * @property {number}  [bitDepth]   that direction's bit depth; absent or 0 means the payload says
 *                                  nothing about the depth (Java SampleRateChange.NO_BIT_DEPTH)
 * @property {string}  backend      the audio backend the edited direction belongs to
 * @property {?string} card         the resolved card name, or null when the device maps to no card
 */

export class LoopbackFormatConstraint {

  /** The shared MessageBus (Java's final bus field). @type {MessageBus} */
  #bus;

  /** Stable listener reference (Java's formatListener field), so the subscription can be
   *  identified for an unsubscribe. */
  #formatListener = (change) => this.onFormatChanged(change);

  /** The process-wide instance (Java: the eagerly armed bus-wired singleton). */
  static instance() {
    if (singleton == null) singleton = new LoopbackFormatConstraint();
    return singleton;
  }

  /** Wires the singleton into the shared MessageBus - construction IS the arming, exactly as in
   *  Java (whose private constructor subscribes). */
  constructor() {
    /** Last input rate seen for the loopback, or UNSET. @type {number} */
    this.lastInputHz = UNSET;
    /** Last output rate seen for the loopback, or UNSET. @type {number} */
    this.lastOutputHz = UNSET;
    /** Last input depth seen for the loopback, or UNSET. @type {number} */
    this.lastInputBits = UNSET;
    /** Last output depth seen for the loopback, or UNSET. @type {number} */
    this.lastOutputBits = UNSET;
    this.#bus = MessageBus.instance();
    this.#bus.subscribe(Events.PREFS_SAMPLE_RATE_CHANGED, this.#formatListener);
  }

  /**
   * Feeds one edited-direction format change through the constraint (the bus listener's body;
   * Java keeps it package-private, see the header for why it is public and answering here).
   *
   * @param {?SampleRateChange} change the direction that was just edited
   * @returns {?SampleRateChange} the correction the OTHER direction must adopt, already published
   *          as PREFS_SAMPLE_RATE_SET, or null when nothing has to change - the pair already
   *          agrees, the change is not the loopback, or there is no payload.
   */
  onFormatChanged(change) {
    if (change == null) return null;
    if (change.backend !== LOOPBACK_BACKEND) {
      this.#forget();   // left the constrained backend - drop the stale pair
      return null;
    }
    const rate = change.sampleRateHz;
    const bits = change.bitDepth != null ? change.bitDepth : 0;
    const card = change.card;   // echo the card back so a card-scoped listener can match it
    if (change.input) {
      this.lastInputHz = rate;
      this.lastInputBits = bits;
      if (this.lastOutputHz !== rate || this.lastOutputBits !== bits) {
        // Pair now equal in our state -> the follow-up output change is a no-op.
        this.lastOutputHz = rate;
        this.lastOutputBits = bits;
        return this.#correct(false, rate, bits, card);
      }
    } else {
      this.lastOutputHz = rate;
      this.lastOutputBits = bits;
      if (this.lastInputHz !== rate || this.lastInputBits !== bits) {
        this.lastInputHz = rate;
        this.lastInputBits = bits;
        return this.#correct(true, rate, bits, card);
      }
    }
    return null;
  }

  /** Drops the whole recorded pair. A pair kept across a backend switch would compare the new
   *  selection's format against the old one's and skip the correction it still needs. */
  #forget() {
    this.lastInputHz = UNSET;
    this.lastOutputHz = UNSET;
    this.lastInputBits = UNSET;
    this.lastOutputBits = UNSET;
  }

  /** Publishes the correction for direction `input` and answers the very same object, so the bus
   *  message and the direct caller's answer can never drift apart. */
  #correct(input, rate, bits, card) {
    const set = {
      input, sampleRateHz: rate, bitDepth: bits, backend: LOOPBACK_BACKEND, card,
    };
    this.#bus.publish(Events.PREFS_SAMPLE_RATE_SET, set);
    return set;
  }
}
