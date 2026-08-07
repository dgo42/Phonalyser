/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.sound.qa40x.Qa40xDeviceFinder - it
// enumerates attached QA402/QA403 analyzers and opens exactly one, exclusively.
//
// The Java CONTRACT is kept whole: the model/product-ID map (doc §2), the
// model + identity record, a list() that degrades to [] instead of throwing when
// USB is unavailable, the single-device rule as its own testable requireSingle(),
// and an open() that hands a claimed device to the transport. What is REPLACED is
// only the mechanics - this is the one class where WebUSB genuinely differs from
// libusb:
//
//   - libusb_get_device_list -> navigator.usb.getDevices(), which lists only the
//     devices the ORIGIN HAS ALREADY BEEN GRANTED. A QA40x that is plugged in but
//     never picked is invisible to it, so first contact needs the browser's own
//     chooser, navigator.usb.requestDevice() - and that requires a USER GESTURE.
//     It therefore lives in scan() ALONE (the Preferences ▸ Scan click), never in
//     open(): open() runs lazily from the audio path, where there is no user
//     activation and a chooser would both throw and be user-hostile.
//     scan() is thus an ADDITION with no Java counterpart, not a ported method:
//     libusb hands the desktop every attached analyzer with no grant step at all,
//     so there is nothing there to mirror. Web-only, and load-bearing - without
//     it a fresh browser profile can never see the device it is plugged into.
//   - libusb_reset_device + get/set_configuration + claim_interface(0) ->
//     device.open(), selectConfiguration(1), claimInterface(0). WebUSB has NO
//     reset_device at all, so the Java's 3-attempt / 250 ms retry loop has no
//     counterpart and is DROPPED: it exists solely to escape the macOS
//     re-enumeration race that the reset itself provokes, and with no reset there
//     is no race to escape.
//   - libusb bus/address -> the serial number. WebUSB deliberately exposes neither
//     bus nor address (they fingerprint the host), and the serial is the closest
//     stable per-unit identity it will give.
//
// The WebUSB entry point arrives through the constructor rather than being read
// off `navigator` inside (Java reaches LibUsb.lib() directly), so the device
// manager injects it and a unit test injects a double - or null, which is exactly
// how a browser without WebUSB presents itself.

import { debug } from '../util/debug.js';
import { INTERFACE_0, WebUsbQa40xTransport } from './webusb-qa40x-transport.js';

/** Shared Van Ooijen (V-USB / LibUSB) vendor ID used by every QA40x (doc §2). */
export const QA_VID = 0x16C0;

/** The QA40x's one and only USB configuration, selected explicitly before the claim. */
const ACTIVE_CONFIGURATION = 1;

/**
 * The QA40x models - carried by their enum NAME, the same representation
 * qa40x-protocol.js takes (sampleRatesHz), so a model resolved here feeds the
 * protocol's per-model gates unchanged.
 */
export const Qa40xModel = Object.freeze({
  QA402: 'QA402',
  QA403: 'QA403',
});

/**
 * USB product ID per model (doc §2) - Java's private Qa40xModel.productId field.
 * The QA401's 0x4E27 is absent because that model is deliberately out of scope.
 */
const PRODUCT_ID = Object.freeze({
  [Qa40xModel.QA402]: 0x4E37,
  [Qa40xModel.QA403]: 0x4E39,
});

/** requestDevice() chooser filters - one VID/PID pair per supported model. */
const USB_FILTERS = Object.freeze(Object.values(PRODUCT_ID)
    .map((productId) => Object.freeze({ vendorId: QA_VID, productId })));

/** Rendered in place of a serial the device does not report (WebUSB allows undefined). */
const UNKNOWN_SERIAL = '(no serial)';

/**
 * Resolves the model from a USB product ID - Java's Qa40xModel.fromProductId,
 * with null for the empty Optional.
 * @param {number} pid USB product ID
 * @returns {?string} a Qa40xModel name, or null if the PID is not a QA402/QA403
 */
export function fromProductId(pid) {
  for (const [model, productId] of Object.entries(PRODUCT_ID)) {
    if (productId === pid) {
      return model;
    }
  }
  return null;
}

/** The platform WebUSB entry point, or null where the browser has none (Firefox, Safari). */
function platformUsb() {
  return typeof navigator !== 'undefined' && navigator.usb ? navigator.usb : null;
}

/**
 * An attached QA40x: its model plus the identity WebUSB will give - the serial
 * number where Java's record carries the USB bus/address (see the module note).
 * Immutable, like the Java record.
 */
export class Qa40xDevice {

  /**
   * @param {string} model a Qa40xModel name
   * @param {?string} [serialNumber] USBDevice.serialNumber, absent on a unit that reports none
   */
  constructor(model, serialNumber) {
    /** @type {string} */
    this.model = model;
    /** @type {?string} */
    this.serialNumber = serialNumber == null ? null : serialNumber;
    Object.freeze(this);
  }

  /** @returns {string} e.g. `QA403 @ serial 0A1B2C3D`. */
  toString() {
    return `${this.model} @ serial ${this.serialNumber == null ? UNKNOWN_SERIAL : this.serialNumber}`;
  }
}

export class Qa40xDeviceFinder {

  /** @type {?USB} the WebUSB entry point; null = no USB on this browser. */
  #usb;

  /**
   * @param {?USB} [usb] navigator.usb, a test double, or null for "no WebUSB here"
   */
  constructor(usb = platformUsb()) {
    this.#usb = usb == null ? null : usb;
  }

  /** @returns {boolean} whether this browser has WebUSB at all - Java's LibUsb.available(). */
  available() {
    return this.#usb != null;
  }

  /**
   * Lists the QA402/QA403 devices this origin may already talk to. Never throws
   * and never prompts: no WebUSB, a rejected enumeration or nothing granted all
   * come back as an empty list, so a caller on the audio path can ask freely.
   * @returns {Promise<Qa40xDevice[]>}
   */
  async list() {
    if (!this.available()) {
      return [];
    }
    try {
      return (await this.#enumerate()).map((found) => found.info);
    } catch (error) {
      console.warn('qa40x-finder: enumeration failed:', error);
      return [];
    }
  }

  /**
   * The GRANT step of the Preferences ▸ Scan click - and the ONLY place the
   * browser's device chooser is opened, because requestDevice() requires user
   * activation (see the module note). Prompts only when nothing is granted AND
   * connected: one grant satisfies the single-device rule for good, so a Scan
   * with a working analyzer never nags.
   *
   * CALLED FROM the QA40x branch of audio/devices.js (scanDevicesForBackend's
   * `qa40xGranter`), which the Scan click reaches through
   * AudioEngine.scanDevices(true) - the ONE path that carries user activation.
   * Every other enumeration goes to list() and prompts for nothing. Keep that
   * chain short: activation is a few-second budget, and awaiting a long operation
   * ahead of the chooser (the Web Audio device probe, say) spends it.
   *
   * The devices found are returned for symmetry with list(), but the caller's
   * reason to be here is the GRANT - after it, plain getDevices() enumeration
   * (Qa40xDeviceManager's) sees the device for good.
   * @returns {Promise<Qa40xDevice[]>} the devices found, empty if the chooser was dismissed
   */
  async scan() {
    if (!this.available()) {
      return [];
    }
    const granted = await this.list();
    if (granted.length > 0) {
      return granted;
    }
    let picked;
    try {
      picked = await this.#usb.requestDevice({ filters: USB_FILTERS });
    } catch (error) {
      debug('[qa40x] device chooser dismissed:', error);   // NotFoundError: the user picked nothing
      return [];
    }
    const info = picked == null ? null : this.#recordOf(picked);
    return info == null ? [] : [info];
  }

  /**
   * Arms a watch for a QA402/QA403 being UNPLUGGED - navigator.usb's 'disconnect'
   * event, filtered to this finder's models. The one signal a dead analyzer gives:
   * WebUSB has no per-transfer timeout, so mid-stream removal otherwise just means
   * transfers stop completing - no error, no event, a measurement app silently
   * reading nothing. The desktop needs no equivalent: libusb transfers FAIL on
   * removal and the recovery rides the error path.
   *
   * A no-op without WebUSB, so arming unconditionally is safe. Foreign devices
   * (same event, any USB gadget) are filtered out by the same model check
   * enumeration uses; the handler receives the matched device's record.
   *
   * @param {(device: Qa40xDevice) => void} handler called once per QA40x unplug
   * @returns {void}
   */
  watchDisconnect(handler) {
    if (!this.available() || typeof this.#usb.addEventListener !== 'function') {
      return;
    }
    this.#usb.addEventListener('disconnect', (event) => {
      const info = event && event.device ? this.#recordOf(event.device) : null;
      if (info != null) {
        handler(info);
      }
    });
  }

  /**
   * Opens the single granted QA40x exclusively - device.open(),
   * selectConfiguration(1), claimInterface(0) (doc §7) - and hands it to a
   * transport. Throws when WebUSB is absent, when no device is granted and
   * connected, or when more than one is (the single-device rule). Never prompts:
   * a device the user has not picked yet is not openable from here.
   * @returns {Promise<WebUsbQa40xTransport>} a transport over the claimed device
   */
  async open() {
    if (!this.available()) {
      throw new Error('WebUSB (navigator.usb) not available - cannot open a QA40x device');
    }
    const found = await this.#enumerate();
    this.requireSingle(found.length);
    const { device, info } = found[0];
    await device.open();
    // The claim is what makes the session exclusive; anything short of it must
    // leave the device CLOSED, or the next open inherits a half-owned handle.
    let claimed = false;
    try {
      // Windows / Linux hand over an already-configured device; macOS does not,
      // and claiming interface 0 of configuration 0 then fails outright. Select
      // the device's only configuration first, skipping a redundant
      // SET_CONFIGURATION where the OS got there first.
      if (device.configuration == null || device.configuration.configurationValue !== ACTIVE_CONFIGURATION) {
        await device.selectConfiguration(ACTIVE_CONFIGURATION);
      }
      await device.claimInterface(INTERFACE_0);
      claimed = true;
    } finally {
      if (!claimed) {
        try {
          await device.close();
        } catch (error) {
          debug('[qa40x] close after a failed claim failed:', error);   // unplugged mid-open
        }
      }
    }
    debug('[qa40x] opened', String(info));
    return new WebUsbQa40xTransport(device);
  }

  /** Enforces the single-device rule (doc §2/§7); pure, so it is unit-tested directly.
   *  @param {number} count devices found */
  requireSingle(count) {
    if (count === 0) {
      throw new Error('No QA402/QA403 found on USB');
    }
    if (count > 1) {
      throw new Error(`${count} QA40x devices attached - connect exactly one `
          + '(the QA40x backend, like ASIO401, drives a single device)');
    }
  }

  /**
   * The granted-and-connected QA40x devices, each paired with the live USBDevice
   * open() needs - the one place enumeration and the VID/PID filter live, shared
   * by list() and open() as Java shares withDeviceList().
   * @returns {Promise<{device: USBDevice, info: Qa40xDevice}[]>}
   */
  async #enumerate() {
    const found = [];
    for (const device of await this.#usb.getDevices()) {
      const info = this.#recordOf(device);
      if (info != null) {
        found.push({ device, info });
      }
    }
    return found;
  }

  /**
   * Maps a USBDevice to its record, or null when it is not a QA402/QA403 -
   * Java's modelOf() over the device descriptor. getDevices() returns everything
   * this origin was ever granted, so the vendor check is not redundant.
   * @param {USBDevice} device
   * @returns {?Qa40xDevice}
   */
  #recordOf(device) {
    if (device.vendorId !== QA_VID) {
      return null;
    }
    const model = fromProductId(device.productId);
    return model == null ? null : new Qa40xDevice(model, device.serialNumber);
  }
}
