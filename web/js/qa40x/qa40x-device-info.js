/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.sound.qa40x.Qa40xDeviceInfo.
//
// A snapshot of the analyzer's identity and live telemetry registers, already
// decoded to display strings (doc/QA40X-PROTOCOL.md §4 extended register map, §6
// telemetry). Read by Qa40xDeviceManager — which owns the transport — and shown
// read-only by the QA40x settings dialog.
//
// Every field is a STRING rather than a number because each register decodes
// differently (millivolts, tenths of a degree, a packed hex serial) and because a
// value that cannot be read has to render as UNAVAILABLE rather than as a
// misleading zero.
//
// The Java record is immutable; the frozen instance here is the same guarantee,
// and the same shape the sibling Qa40xDevice record in qa40x-device-finder.js
// uses — record components as plain fields, frozen at construction.

export class Qa40xDeviceInfo {

  /** Shown for a register that could not be read, and for the QA403's ISO-supply
   *  current, which only the QA402 has (§6). */
  static UNAVAILABLE = '---';

  /** The all-unavailable snapshot — nothing was read because the device is not
   *  open (or a read failed part-way). A shared constant so every caller shows
   *  the same thing. */
  static NONE = new Qa40xDeviceInfo(
    Qa40xDeviceInfo.UNAVAILABLE, Qa40xDeviceInfo.UNAVAILABLE, Qa40xDeviceInfo.UNAVAILABLE,
    Qa40xDeviceInfo.UNAVAILABLE, Qa40xDeviceInfo.UNAVAILABLE, Qa40xDeviceInfo.UNAVAILABLE,
    Qa40xDeviceInfo.UNAVAILABLE, Qa40xDeviceInfo.UNAVAILABLE);

  /**
   * @param {string} firmwareVersion firmware build number (reg 0x10) as decimal text
   * @param {string} usbVoltage      USB bus voltage, `x.xxx V`
   * @param {string} usbCurrent      USB bus current, `x.xxx A`
   * @param {string} isoCurrent      ISO-supply current, `x.xxx A` — QA402 only, else UNAVAILABLE
   * @param {string} temperature     board temperature, `xx.x °C`
   * @param {string} capability      capability word, `0x…`
   * @param {string} capability2     per-model capability word, `0x…`
   * @param {string} serialNumber    the packed serial as 8 hex digits
   */
  constructor(firmwareVersion, usbVoltage, usbCurrent, isoCurrent,
              temperature, capability, capability2, serialNumber) {
    /** @type {string} */
    this.firmwareVersion = firmwareVersion;
    /** @type {string} */
    this.usbVoltage = usbVoltage;
    /** @type {string} */
    this.usbCurrent = usbCurrent;
    /** @type {string} */
    this.isoCurrent = isoCurrent;
    /** @type {string} */
    this.temperature = temperature;
    /** @type {string} */
    this.capability = capability;
    /** @type {string} */
    this.capability2 = capability2;
    /** @type {string} */
    this.serialNumber = serialNumber;
    Object.freeze(this);
  }
}
