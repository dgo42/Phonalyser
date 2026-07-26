/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.sound.qa40x.Qa40xRangeController — the
// subscriber that turns the generic DEVICE_ACTIVE_RANGE_CHANGED bus event into a
// physical re-range of the open QA402/QA403.
//
// Lives in the one QA40x folder (maintainer decision: ALL QA40x specifics in
// sound.qa40x; the bus stays generic infrastructure). The generic event names no
// card and no backend, so this subscriber consults CURRENT state itself: it acts
// only while QA40X is the active backend and the device is open, and routes the
// change to the open device's card — the manager applies it (a live session
// restart, or the stored range for the next open).
//
// DELIBERATE DEVIATION, same guarantee: Java arms this as an eagerly-created
// singleton whose constructor subscribes, so the wiring is live before any
// Preferences OK can publish. Here the device manager constructs ONE instance in
// its own constructor and keeps it in a field — armed at exactly the same moment,
// but an injected dependency instead of a global (CLAUDE.md §6).

import { Events } from '../bus/events.js';
import { MessageBus } from '../bus/message-bus.js';
import { inputRangeDbvValues, outputRangeDbvValues, rangeDbv } from './qa40x-protocol.js';
import { QA40X_BACKEND } from './qa40x-rate-constraint.js';

/**
 * Not a valid full-scale dBV (Java's Integer.MIN_VALUE) — a range label the QA40x
 * code maps don't recognise (e.g. a non-QA40x device card's label) resolves to
 * this and is ignored, since the event is generic and may describe any device.
 */
const NO_RANGE = Number.MIN_SAFE_INTEGER;

/**
 * Payload of DEVICE_ACTIVE_RANGE_CHANGED — the shape of the Java bus record
 * gui.bus.ActiveRange, which stays a bus payload and is therefore NOT re-declared
 * as a class here. Deliberately device-agnostic: it names neither a card nor a
 * backend.
 *
 * @typedef {Object} ActiveRange
 * @property {boolean} input            true for the input (capture) direction, false for output
 * @property {string}  activeRangeLabel the newly active range-row label as shown on the card
 */

/**
 * What this controller needs of its device manager (implemented by
 * Qa40xDeviceManager): the two pieces of CURRENT state the generic event omits,
 * plus the sink that applies the change.
 *
 * @typedef {Object} Qa40xRangeTarget
 * @property {() => string} activeBackend the AudioBackendType name currently in force
 *           (the Java reads AudioBackend.instance().active(); injected here so nothing
 *           reaches through a global)
 * @property {() => ?string} cardName logical name of the open device's card, or null
 *           before the device is opened
 * @property {(activeBackend: string, cardName: string, input: boolean, dbv: number) => void}
 *           applyActiveRangeChange routes the decoded change to the device
 */

export class Qa40xRangeController {

  /** @type {Qa40xRangeTarget} */
  #manager;

  /** Stable listener reference (Java's rangeListener field), so the subscription
   *  can be identified for an unsubscribe. */
  #rangeListener = (change) => this.onActiveRangeChanged(change);

  /**
   * Wires the controller into the shared MessageBus — construction IS the arming,
   * exactly as in Java.
   *
   * @param {Qa40xRangeTarget} manager the open-device state and the range sink
   */
  constructor(manager) {
    this.#manager = manager;
    MessageBus.instance().subscribe(Events.DEVICE_ACTIVE_RANGE_CHANGED, this.#rangeListener);
  }

  /**
   * Decodes one committed active-range change and re-ranges the open analyzer.
   * Java keeps this private; it is public here because the bus SWALLOWS a throwing
   * subscriber, which would hide a fault behind a green "the change was ignored"
   * assertion — the decision must be drivable directly.
   *
   * @param {?ActiveRange} change the direction + range label the dialog committed
   */
  onActiveRangeChanged(change) {
    if (change == null) return;
    const activeBackend = this.#manager.activeBackend();
    if (activeBackend !== QA40X_BACKEND) return;   // only QA40x re-ranges hardware
    const cardName = this.#manager.cardName();
    if (cardName == null) return;                  // device not open — next open reads the store
    const candidates = change.input ? inputRangeDbvValues() : outputRangeDbvValues();
    const dbv = rangeDbv(change.activeRangeLabel, candidates, NO_RANGE);
    if (dbv === NO_RANGE) return;                  // not a QA40x range label
    this.#manager.applyActiveRangeChange(activeBackend, cardName, change.input, dbv);
  }
}
