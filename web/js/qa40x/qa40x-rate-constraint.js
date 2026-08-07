/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.sound.qa40x.Qa40xRateConstraint - the rule
// that enforces the QA402/QA403's single shared reg-9 sample-rate clock: its input and
// output rates can never differ (doc/QA40X-PROTOCOL.md §10), so when one direction is
// edited in Preferences the other must follow.
//
// The Preferences dialog edits an UNCOMMITTED working copy, so this cannot read the
// change off live state - it gates on the backend the CHANGE PAYLOAD carries and acts
// only for QA40x. A change on any other backend forgets both recorded rates, so a stale
// pair can never mis-fire once QA40x is chosen again.
//
// It records the last rate seen per direction and, when a change makes the two differ,
// answers with the correction for the OTHER direction AND updates its own record of that
// direction to match. The pair is then equal in its state, so the follow-up change for
// the corrected direction is a no-op: the round-trip terminates by construction (equal
// records => nothing emitted), independently of any programmatic-select-fires-no-event
// behaviour in the dialog.
//
// Like the Java it is a bus-wired singleton: construction subscribes to
// PREFS_SAMPLE_RATE_CHANGED and a correction is published as PREFS_SAMPLE_RATE_SET.
// Qa40xDeviceManager's constructor calls instance() (next to the range controller) so the
// subscription is live from the first QA40x dispatch, before any rate combo can move.
//
// DELIBERATE DEVIATION: onSampleRateChanged() also RETURNS the correction it published (or
// null), which the void Java method cannot. The bus swallows a throwing subscriber, so a
// bus-driven "nothing was published" assertion would stay green over a crash - the
// decision has to stay drivable directly, same reasoning as Qa40xRangeController's public
// listener. Nothing publishes on its behalf: the return value is an answer, not a request.

import { Events } from '../bus/events.js';
import { MessageBus } from '../bus/message-bus.js';

/** No rate recorded yet for a direction - the first change there only syncs the other;
 *  it never compares against a stale value. */
const UNSET = -1;

/** The one backend this rule constrains (AudioBackendType.QA40X's enum name). A change
 *  tagged with any other backend resets the tracking and emits nothing. */
export const QA40X_BACKEND = 'QA40X';

/** Module-scope holder for the singleton - the JS equivalent of the Java static field
 *  (single-threaded, so no double-checked locking is needed). */
let singleton = null;

/**
 * A sample-rate change for ONE direction, as the Preferences dialog is being edited -
 * the shape of the Java record gui.bus.SampleRateChange, which stays a bus payload and
 * is therefore NOT re-declared as a class here.
 *
 * @typedef {Object} SampleRateChange
 * @property {boolean} input        true for the input (capture) direction, false for output
 * @property {number}  sampleRateHz that direction's sample rate in hertz
 * @property {string}  backend      the audio backend the edited direction belongs to
 * @property {?string} card         the resolved card name, or null when the device maps to no card
 */

export class Qa40xRateConstraint {

  /** The shared MessageBus (Java's final bus field). @type {MessageBus} */
  #bus;

  /** Stable listener reference (Java's rateListener field), so the subscription can be
   *  identified for an unsubscribe. */
  #rateListener = (change) => this.onSampleRateChanged(change);

  /** The process-wide instance (Java: the eagerly armed bus-wired singleton). */
  static instance() {
    if (singleton == null) singleton = new Qa40xRateConstraint();
    return singleton;
  }

  /** Wires the singleton into the shared MessageBus - construction IS the arming, exactly
   *  as in Java (whose private constructor subscribes). */
  constructor() {
    /** Last input rate seen for QA40x, or UNSET. @type {number} */
    this.lastInputHz = UNSET;
    /** Last output rate seen for QA40x, or UNSET. @type {number} */
    this.lastOutputHz = UNSET;
    this.#bus = MessageBus.instance();
    this.#bus.subscribe(Events.PREFS_SAMPLE_RATE_CHANGED, this.#rateListener);
  }

  /**
   * Feeds one edited-direction rate change through the constraint (the bus listener's body;
   * Java keeps it package-private, see the header for why it is public and answering here).
   *
   * @param {?SampleRateChange} change the direction that was just edited
   * @returns {?SampleRateChange} the correction the OTHER direction must adopt, already
   *          published as PREFS_SAMPLE_RATE_SET, or null when nothing has to change - the
   *          pair already agrees, the change is not QA40x, or there is no payload.
   */
  onSampleRateChanged(change) {
    if (change == null) return null;
    if (change.backend !== QA40X_BACKEND) {
      this.lastInputHz = UNSET;   // left the constrained backend - drop stale rates
      this.lastOutputHz = UNSET;
      return null;
    }
    const rate = change.sampleRateHz;
    const card = change.card;   // echo the card back so a card-scoped listener can match it
    if (change.input) {
      this.lastInputHz = rate;
      if (this.lastOutputHz !== rate) {
        this.lastOutputHz = rate;   // pair now equal in our state -> the follow-up output change is a no-op
        return this.#correct(false, rate, card);
      }
    } else {
      this.lastOutputHz = rate;
      if (this.lastInputHz !== rate) {
        this.lastInputHz = rate;    // as above, mirrored: a re-announced output rate emits nothing
        return this.#correct(true, rate, card);
      }
    }
    return null;
  }

  /** Publishes the correction for direction `input` and answers the very same object, so the
   *  bus message and the direct caller's answer can never drift apart. */
  #correct(input, rate, card) {
    const set = { input, sampleRateHz: rate, backend: QA40X_BACKEND, card };
    this.#bus.publish(Events.PREFS_SAMPLE_RATE_SET, set);
    return set;
  }
}
