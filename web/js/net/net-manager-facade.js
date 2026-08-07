/*
 * Phonalyser web - the audio layer's view of the net device manager.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The engine is built BEFORE the session exists (the manager needs the loaded preference store),
 * so it cannot be handed the manager itself - it is handed this forward, which looks the manager
 * up per call. That is the same live-lookup the engine already does for the active backend.
 *
 * It is a hand-written forward, and that is exactly what makes it dangerous: a method added to
 * the manager and called from devices.js or backend.js but not listed here fails at RUNTIME, in
 * a browser, as "manager.<x> is not a function" - with the device combo silently empty, because
 * the scan's catch turns it into a status line. Every node test passes the real manager and sees
 * none of it (that is how lockedBy() once shipped broken, unnoticed until a browser ran it).
 *
 * So the shape is uniform on purpose: EVERY member takes the same absent-manager guard and the
 * SAME ARITY as the manager's own method, and net-manager-facade.test.mjs enumerates both sides
 * and compares them. A drift then fails loudly in the suite instead of quietly in a browser.
 */

import { DeviceFailureReason } from '../audio/device-failure-reason.js';

/** What each member answers when there is no session yet - the "no bench" value of its own type,
 *  never an exception: every one of these is reachable from a UI path that runs before a connect
 *  (a scan on a remembered backend, a combo build, a failed open on a lane already torn down). */
const NO_SESSION = Object.freeze({
  devices: [],
  formats: [],
  refusal: 'no net client',
});

/**
 * @param {() => ?import('./net-device-manager.js').NetDeviceManager} getManager the live lookup -
 *        the manager instance once the shell has built it, null before and after a teardown.
 * @returns {Object} the forward the AudioEngine holds
 */
export function netManagerFacade(getManager) {
  /** The manager or null - one lookup point, so no member can invent its own. */
  const m = () => (typeof getManager === 'function' ? getManager() : null) || null;
  return {
    /** The live session (spec 4), or null while none is open - read per call, never captured. */
    get connection() { const net = m(); return net ? net.connection : null; },

    // ---- the enumeration path (devices.js's net branch, via AudioEngine.scanDevices) ----------
    listInputDevices: () => { const net = m(); return net ? net.listInputDevices() : NO_SESSION.devices; },
    listOutputDevices: () => { const net = m(); return net ? net.listOutputDevices() : NO_SESSION.devices; },
    isRemoteBackend: (value) => { const net = m(); return net ? net.isRemoteBackend(value) : false; },
    selectBackend: (backend) => {
      const net = m();
      return net ? net.selectBackend(backend) : Promise.resolve(NO_SESSION.refusal);
    },
    /** The formats spec 4.3 inlined with the device. ONE argument, as the manager takes: the
     *  direction is the REF's own, and a second one passed by a caller is not read on either side. */
    listSupportedFormats: (device) => {
      const net = m();
      return net ? net.listSupportedFormats(device) : NO_SESSION.formats;
    },
    /** WHO holds a device (spec 4.3's lock overview) - the greyed "in use by" row. */
    lockedBy: (device) => { const net = m(); return net ? net.lockedBy(device) : null; },

    // ---- the open paths ----------------------------------------------------------------------
    /** The catalogue owns the name->ref resolution AND the refusal for a name the bench no longer
     *  offers, so the engine asks rather than searching the listing itself. */
    getDeviceByName: (name, isOutput) => {
      const net = m();
      return net ? net.getDeviceByName(name, isOutput) : null;
    },
    acquireDevice: (ref) => {
      const net = m();
      return net ? net.acquireDevice(ref) : Promise.resolve(new Error(NO_SESSION.refusal));
    },
    releaseDevice: (ref) => { const net = m(); return net ? net.releaseDevice(ref) : Promise.resolve(); },

    // ---- what goes wrong on the bench --------------------------------------------------------
    /** The manager's contract is "never null", so the absent-session answer is the SPI default
     *  UNKNOWN and not a null the wording boundary would then dereference. */
    classifyFailure: (failure) => {
      const net = m();
      return net ? net.classifyFailure(failure) : DeviceFailureReason.UNKNOWN;
    },
    /** The refusal's own sentence beside its reason - what turns a code-only refusal
     *  (DEVICE_LOCKED and its kin) into something the operator can act on. */
    refusalText: (failure) => { const net = m(); return net ? net.refusalText(failure) : null; },
    gap: (lostFrames) => { const net = m(); if (net) net.gap(lostFrames); },
  };
}
