/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.enums.DeviceFailureReason - the machine-readable
// answer a backend gives when its line refuses to open, so the operator can be told something
// true instead of a driver code. The rule this exists for: a raw native code says the operator
// NOTHING. "NotReadableError" is not a reason; "the device is in use by another application" is.
// The raw text itself keeps going to the log, every time - it is what a developer needs and the
// only place it belongs.
//
// Engine vocabulary, not UI: each value carries an i18n KEY (as machine-readable as the value
// name beside it). The full sentence is produced only at the wording boundaries
// (audio/device-failure-reason.js frames the one detail rule both of them share).

/**
 * The reasons, as value objects carrying their i18n key (Java: the enum constant + i18nKey()).
 * @enum {{name: string, i18nKey: string}}
 */
export const DeviceFailureReason = Object.freeze({
  /** The device is gone - unplugged, or removed by the driver while the application still
   *  had it in its list. */
  DEVICE_DISCONNECTED: Object.freeze({ name: 'DEVICE_DISCONNECTED', i18nKey: 'device.error.reason.disconnected' }),
  /** The device is there and does not respond: the open timed out, or the driver accepted the
   *  call and never came back. */
  DEVICE_NOT_ANSWERING: Object.freeze({ name: 'DEVICE_NOT_ANSWERING', i18nKey: 'device.error.reason.notAnswering' }),
  /** Another application (or another lane of this one) holds the device exclusively. */
  DEVICE_IN_USE: Object.freeze({ name: 'DEVICE_IN_USE', i18nKey: 'device.error.reason.inUse' }),
  /** The configured device is not on this host at all - a card that was remembered from
   *  another machine, or a renamed one. */
  DEVICE_NOT_FOUND: Object.freeze({ name: 'DEVICE_NOT_FOUND', i18nKey: 'device.error.reason.notFound' }),
  /** The device exists and is free, but refuses the asked sample rate or channel count. */
  FORMAT_UNSUPPORTED: Object.freeze({ name: 'FORMAT_UNSUPPORTED', i18nKey: 'device.error.reason.formatUnsupported' }),
  /** The backend could not tell - the mandatory fallback, and never an error in itself. The
   *  log line beside it carries the raw detail. */
  UNKNOWN: Object.freeze({ name: 'UNKNOWN', i18nKey: 'device.error.reason.unknown' }),
});

/**
 * The value `name` names, or UNKNOWN when it names nothing this build knows - the
 * forward-compatible read for a value that arrived from ANOTHER process (a newer peer may send
 * a reason this build has never heard of). Null and blank are UNKNOWN for the same reason.
 * Faithful port of DeviceFailureReason.fromName.
 *
 * @param {?string} name the reason's constant name
 * @returns {{name: string, i18nKey: string}} the reason, never null
 */
export function deviceFailureReasonFromName(name) {
  if (name == null || String(name).trim() === '') return DeviceFailureReason.UNKNOWN;
  // OWN properties only. Java looks the name up in a HashMap, which has no inherited keys; a
  // plain object literal inherits Object.prototype, so a value off the WIRE - which is exactly
  // what this method exists to read - could name 'toString' or 'constructor' and be handed back
  // as a function that no caller could tell from a reason.
  if (!Object.prototype.hasOwnProperty.call(DeviceFailureReason, name)) {
    return DeviceFailureReason.UNKNOWN;
  }
  return DeviceFailureReason[name];
}
