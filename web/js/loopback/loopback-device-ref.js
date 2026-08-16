/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.sound.loopback.LoopbackDeviceRef - the single device
// this backend offers, listed for both directions. There is no hardware behind it: the playback
// lane's quantised samples ARE the capture lane's input, so one ref describes both ends of the
// same digital crossing.
//
// displayName() is Java's DeviceRef interface default, which the web has no interface to hold,
// so it lives on the class - character for character the desktop's label, because the device
// combo, the card resolution and the bench listing all read the same one.

/** The AudioBackendType name this backend is stored and dispatched under (audio-backend-type.js
 *  already carries its display name). */
export const LOOPBACK_BACKEND = 'LOOPBACK';

/** Shown wherever a device name is shown - the backend has exactly one. It deliberately carries
 *  the application name: the seed card in the device catalogue binds by case-insensitive name
 *  SUBSTRING, and a bare Loopback would also match real endpoints that carry the word (the
 *  loopback capture endpoints Windows exposes on WASAPI). */
export const LOOPBACK_DEVICE_NAME = 'Phonalyser Loopback';

export class LoopbackDeviceRef {

  constructor() {
    /** @type {number} index within this backend's listing (the one device is at 0). */
    this.index = 0;
    /** @type {string} */
    this.name = LOOPBACK_DEVICE_NAME;
    Object.freeze(this);
  }

  /** @returns {string} what the device is, as shown next to the name. */
  description() {
    return 'Digital loopback (playback returns to capture)';
  }

  /** @returns {string} the vendor. */
  vendor() {
    return 'Phonalyser';
  }

  /** @returns {string} the AudioBackendType name this handle belongs to. */
  backend() {
    return LOOPBACK_BACKEND;
  }

  /** @returns {boolean} always true - both directions are the same crossing. */
  isInput() {
    return true;
  }

  /** @returns {boolean} always true - both directions are the same crossing. */
  isOutput() {
    return true;
  }

  /** @returns {string} the house device label: [index] name (description) - vendor. */
  displayName() {
    return `[${this.index}] ${this.name} (${this.description()}) - ${this.vendor()}`;
  }

  /** @returns {string} the display name. */
  toString() {
    return this.displayName();
  }
}
