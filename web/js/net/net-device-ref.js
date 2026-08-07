/*
 * Phonalyser web - one device on the far end.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of org.edgo.audio.measure.net.client.NetDeviceRef: a device ref whose four
 * wire fields (spec 4.3 - backend, index, input, name) are exactly what every request naming
 * this device must carry.
 *
 * `backend` answers what the device IS - the TRUE remote type (a QA403 on a bench answers
 * QA40X); `carrier` answers NET. The carrier is what routes every backend dispatch at the net
 * manager instead of at the LOCAL driver of the same name - a QA40x on the bench across the
 * room must not be opened over this machine's USB - while the true type is what type-specific
 * control keys on, local or remote alike. The wire text stays in `remoteBackend`: it is the
 * server's enum name, and a server may know a backend this build does not.
 *
 * IDENTITY IS THE SIX WIRE FIELDS ALONE. `calibration` and `boundCard` are carried but are NOT
 * part of it: they are the server's stored full scale and card choice, which spec 4.3 re-sends
 * with every ev.devices.changed - including the ones a device.setCalibration or a
 * device.setCard triggers. Held locks and open streams are tracked BY REF across those
 * refreshes, so a ref whose identity moved when a value changed would orphan the lock on a
 * device this session is measuring on: the release would name a ref the register no longer
 * contains, and the device would stay taken for the rest of the session.
 */
import { NetFields } from './net-proto.js';

/** The carrier every remote device answers - what routes a dispatch at the net manager. */
export const NET_BACKEND = 'NET';

/**
 * The prefix that makes a SERVER backend's name a distinct choice in the client's backend combo
 * - the web's spelling of Java's carrier/type split (NetDeviceRef: backend() is the true remote
 * type, carrier() is NET).
 *
 * It exists because the two namespaces genuinely collide: a bench's "QA40X" and this machine's
 * "QA40X" are different devices reached over different transports, and a combo value of plain
 * "QA40X" could not say which - a QA40x on the bench across the room must not be opened over
 * this machine's USB. So the stored preference and the combo value carry "net:<remote name>",
 * while everything on the wire keeps the bare name the server knows itself by.
 *
 * AUDITING A RENAME OF THIS PREFIX: `web/.gitignore` lists `test/`, so ripgrep rooted at `web/`
 * SILENTLY SKIPS THE WHOLE TEST TREE - a sweep that came back clean has not looked at
 * `web/test/**` at all. Re-root the grep per directory (`web/js`, then `web/test`), or an old
 * spelling survives there unnoticed, as one did after the uppercase-to-lowercase rename.
 */
export const NET_BACKEND_PREFIX = 'net:';

/** The combo value / stored preference for one of a server's backends. */
export function netBackendValue(remoteBackend) {
  return NET_BACKEND_PREFIX + remoteBackend;
}

/** The server's own backend name behind a combo value, or null when the value names a LOCAL
 *  backend - the one test every dispatch asks. */
export function remoteBackendOf(value) {
  return typeof value === 'string' && value.startsWith(NET_BACKEND_PREFIX)
    ? value.substring(NET_BACKEND_PREFIX.length) : null;
}

/** The backend enum names this build knows, so a server backend it does not can answer NET as
 *  its true type too - the honest "reachable but not identifiable here". */
const KNOWN_BACKENDS = new Set(['WASAPI', 'WDMKS', 'COREAUDIO', 'JAVASOUND', 'QA40X', 'WEB_AUDIO', 'NET']);

/**
 * @param {Object} fields the six wire fields plus the two carried values
 * @returns {Object} the ref
 */
export function makeNetDeviceRef({ index, name, description, vendor, isInput, remoteBackend,
  calibration = null, boundCard = null }) {
  return {
    index, name, description, vendor, isInput, remoteBackend, calibration, boundCard,
    /** The carrier (spec 4.3's routing rule). */
    carrier: NET_BACKEND,
    /** The TRUE type, or NET for a backend this build has no constant for. */
    backend: KNOWN_BACKENDS.has(remoteBackend) ? remoteBackend : NET_BACKEND,
    isOutput: !isInput,
    /** How the device combo names this device - Java DeviceRef.displayName(), character for
     *  character, because the operator is looking at the same bench through both clients.
     *  ALL FOUR WIRE FIELDS, and the NAME among them: a backend's devices routinely SHARE a
     *  description (every Windows DirectSound device of a JavaSound server answers "Direct
     *  Audio Device: DirectSound Capture"), so a label built from the description alone made
     *  every row read identically and the real device name was nowhere on screen. */
    displayName() {
      return `[${index}] ${name} (${description}) - ${vendor}`;
    },
  };
}

/**
 * Whether `text` names `ref` - the test every lookup that starts from a DEVICE COMBO has to
 * make, because what the combo carries is not one value but three:
 *
 *  - the wire NAME (the <select>'s value, and what a staged binding is remembered by);
 *  - the option's TEXT, which is {@link makeNetDeviceRef}'s displayName();
 *  - that text plus the " - <rate> Hz" the input combo appends per device (fillDeviceCombos).
 *
 * The card seam is handed the option TEXT (the local card matcher works on the human label), so
 * a bench lookup that compared only the description or only the name silently found NOTHING for
 * every input device - the card combo then listed the bench's cards but could select none of
 * them, and the calibration-copy offer never fired. The suffix is matched
 * as a PREFIX-plus-separator, never as a loose substring: two devices whose labels differ only
 * past that point must not resolve to each other.
 *
 * @param {Object} ref a ref from {@link makeNetDeviceRef}
 * @param {string} text what the combo carries for the selected device
 */
export function namesNetDevice(ref, text) {
  if (ref == null || typeof text !== 'string' || text === '') return false;
  if (ref.name === text) return true;
  const shown = ref.displayName();
  return text === shown || text.startsWith(`${shown} `);
}

/** The six wire fields and nothing else - see the module comment for why the calibration and
 *  the card binding are deliberately absent (Java NetDeviceRef.equals). */
export function sameNetDevice(a, b) {
  if (a === b) return true;
  if (a == null || b == null) return false;
  return a.index === b.index && a.isInput === b.isInput && a.name === b.name
    && a.description === b.description && a.vendor === b.vendor
    && a.remoteBackend === b.remoteBackend;
}

/** Stamps the device ref of spec 4.3 into a request - the ONE place those four fields are
 *  written, so an acquire, an open and a release can never name the device differently. */
export function refInto(ref, message) {
  message[NetFields.BACKEND] = ref.remoteBackend;
  message[NetFields.INDEX] = ref.index;
  message[NetFields.INPUT] = ref.isInput;
  message[NetFields.NAME] = ref.name;
  return message;
}

/** How the device reads in an error message or a log line. */
export function refText(ref) {
  return `${ref.remoteBackend}[${ref.index}] ${ref.isInput ? 'input' : 'output'} '${ref.name}'`;
}
