/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Port of org.edgo.audio.measure.gui.sound.CalibrationStore - where a measured full scale goes
// when the operator presses Calibrate: into this machine's device cards, or onto the bench that
// owns the device. Calibration lives where the device is connected: a device on this machine is
// calibrated here (the calibration dialog's local write, unchanged), a device on a Phonalyser
// server is the SERVER's to calibrate - the two full scales go up as spec 4.3's
// device.setCalibration, are stored in that machine's devices.yaml and broadcast back, and nothing
// about a foreign device is ever written into this installation's store, where it could only be
// found again by a name collision.
//
// This type is also the ONE place the bench selection is resolved - which server, which of its
// devices the committed name refers to (Java remoteSelection / deviceOf) - so the calibration
// dialog, the scope's calibrate gate and the Preferences dialog's card combo cannot each name the
// bench or the device differently.

import { namesNetDevice, remoteBackendOf } from './net-device-ref.js';

export class CalibrationStore {
  /**
   * @param prefs        Preferences.instance() - the selected backend is read live per call.
   * @param deviceStore  the DeviceProfileStore, answering for every LOCAL selection.
   * @param benchCards   the BenchCards seam (spec 4.3 cards + device.setCalibration).
   * @param netManager   () => the NetDeviceManager, or null before it exists / with no session:
   *                     built after this store, in the shell's modals step, and asked per call.
   */
  constructor(prefs, deviceStore, benchCards, netManager) {
    this._prefs = prefs;
    this._deviceStore = deviceStore;
    this._benchCards = benchCards;
    this._netManager = netManager;
  }

  /** The selected backend's value while it names one of a server's backends, else null - the ONE
   *  test that decides whether a device is somebody else's to calibrate and whether the card
   *  combo shows a bench's cards or this machine's (Java BackendKey.remote()). */
  remote() {
    const backend = this._prefs.backend.get();
    return remoteBackendOf(backend) != null ? backend : null;
  }

  /** The catalogue ref behind what the device combo SHOWS, or null when the bench does not offer
   *  it (Java deviceOf). The card seam is handed the option TEXT, a commit passes a staged device
   *  NAME, and the input combo appends a rate to its text - so the one test that knows all three
   *  spellings does the matching (namesNetDevice). */
  ref(input, device) {
    const manager = this._netManager();
    if (!manager || !device) return null;
    const refs = input ? manager.listInputDevices() : manager.listOutputDevices();
    return refs.find((r) => namesNetDevice(r, device)) || null;
  }

  /** The bench's id - the local card mirror's key half. The connection's own is authoritative
   *  (the server names itself in hello); the remembered entry covers the moment before it
   *  answered. */
  serverId() {
    const manager = this._netManager();
    if (!manager) return null;
    const connection = manager.connection;
    if (connection && connection.serverId) return connection.serverId;
    const server = manager.getConnectedServer();
    return server ? server.serverId : null;
  }

  /** Whether the named selection would be measured with no real calibration in this direction
   *  (Java CalibrationStore.isUncalibrated). The BENCH's own answer first - a device on a server
   *  is calibrated there, and this machine's card of the same name describes a different
   *  exemplar of that model - then this machine's card store. A bench device the catalogue does
   *  not carry at all is uncalibrated too: nothing is left to vouch for it. */
  uncalibrated(input, device) {
    if (this.remote() == null) return this._deviceStore.isUncalibrated(device, input);
    const ref = this.ref(input, device);
    return ref == null || ref.calibration == null;
  }

  /** Spec 4.3's device.setCalibration for the named device, under that device's lock. Answers
   *  whether the bench stored the pair; false for a local selection, which the caller writes
   *  locally. A refusal is the caller's to report - never a fallback to a card of our own, which
   *  would shadow the bench's calibration by name from then on. */
  async send(input, device, fsRmsLeft, fsRmsRight) {
    const value = this.remote();
    if (value == null) return false;
    return this._benchCards.calibrate(value, this.ref(input, device), fsRmsLeft, fsRmsRight);
  }
}
