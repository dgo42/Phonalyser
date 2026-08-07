/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of the per-card device-profile model + store:
//   org.edgo.audio.measure.preferences.AudioDeviceProfile   (the profile / card)
//   org.edgo.audio.measure.preferences.DeviceEndpointConfig (one direction)
//   org.edgo.audio.measure.preferences.DeviceRange          (one attenuator row)
// plus the store operations that live on org.edgo.audio.measure.preferences.Preferences
// (getAudioDeviceProfiles / findAudioDeviceProfile / putAudioDeviceProfile /
// removeAudioDeviceProfile / resolveDeviceProfile / normalizeDeviceName /
// applyInput|OutputDeviceProfile / storeAdc|DacCalibration / the seed-if-absent load +
// once-per-contentVersion upgrade merge). Java line references are cited per member.
//
// Differences from the desktop original, all behaviour-preserving:
//   * Persistence target is localStorage (one JSON string under STORAGE_KEY) instead
//     of a devices.yaml file. The document shape mirrors devices.yaml's vocabulary
//     key-for-key: { formatVersion, contentVersion, audioDevices } with fsVrms always
//     a { left, right } pair (reader tolerates a scalar shorthand), activeRange a
//     scalar row label for LINKED / MONO and a { left, right } map for INDEPENDENT,
//     match omitted when empty, calibrated / calibrationFromDevice serialised only
//     when true.
//   * The bundled classpath seed (devices.yaml) becomes device-catalog.js; the store
//     seeds from it on first run and runs the same content-version-gated merge.
//   * JSON carries no comments, so the seed's leading header comment (which the
//     desktop copies verbatim into the user's devices.yaml) is not reproduced.
//   * The store is constructor-injected with the live Preferences (the apply* setters
//     write the per-channel full-scale scalars), mirroring how the desktop store lives
//     inside Preferences.
//   * The desktop reads the selected device NAME from BackendPrefs; on the web those
//     slots hold Web Audio device IDs, so the caller passes the human device LABEL to
//     resolve* / apply* / store* (the label is what the recognition patterns match).

import { DeviceChannelMode } from './device-enums.js';
import { quarantineStoreEntry } from './store-quarantine.js';

const SQRT2 = Math.sqrt(2.0);
/** Separator of the device->card binding key for a device on a server (Java
 *  Preferences.BINDING_KEY_SEPARATOR). See {@link DeviceProfileStore#deviceBindingKey}. */
const BINDING_KEY_SEPARATOR = '/';

/** localStorage key the device store document lives under. */
export const DEVICES_KEY = 'phonalyser.devices';

/** True when {@code s} is a legal {@link DeviceChannelMode} token. */
function isChannelMode(s) {
  return s === DeviceChannelMode.MONO || s === DeviceChannelMode.LINKED
    || s === DeviceChannelMode.INDEPENDENT;
}

const isNum = (v) => typeof v === 'number' && Number.isFinite(v);

/**
 * One row of a device endpoint's range table - a single attenuator / gain position
 * and the full-scale voltage(s) it calibrates to (volts RMS on disk for both
 * directions). Faithful port of DeviceRange.
 */
export class DeviceRange {
  constructor() {
    /** @type {?string} */ this.label = null;
    this.fsLeft = 0;
    this.fsRight = 0;
    /** True once a real crosshair calibration wrote this row (DeviceRange.calibrated);
     *  the upgrade merge never refreshes a calibrated row's nominal. Serialized only
     *  when true. */
    this.calibrated = false;
    /**
     * Human-facing DISPLAY text for the ranges table when it differs from the
     * {@link #label} KEY - a QA40x input row shows the verbose
     * `N "dBV" real N dBFS or (N−9) dBV` while the key stays plain `N dBV`
     * (DeviceRange.displayLabel).
     *
     * DISPLAY ONLY, and never written out by this client: the key is what the
     * protocol decodes back into a register value, what the radios compare and what
     * a rename edits. A device build re-derives it (Qa40xDeviceManager#buildEndpoint)
     * and the wire may carry it on a BENCH card's content rows, which the reader
     * takes; the local store serialises the key alone.
     * @type {?string}
     */
    this.displayLabel = null;
  }

  /** The text the ranges table shows: {@link #displayLabel} when set, else the plain
   *  {@link #label} key (DeviceRange.displayLabelOrKey). */
  displayLabelOrKey() {
    return this.displayLabel != null ? this.displayLabel : this.label;
  }

  /** A fresh copy of this row (DeviceRange.deepCopy). */
  deepCopy() {
    const c = new DeviceRange();
    c.label = this.label;
    c.fsLeft = this.fsLeft;
    c.fsRight = this.fsRight;
    c.calibrated = this.calibrated;
    // Carried, like every other field: a copy that lost it would print the bare key
    // in the dialog for any card that reached the table through a clone.
    c.displayLabel = this.displayLabel;
    return c;
  }
}

/**
 * One direction (input or output) of a profile: the channel mode plus the range
 * table and the selected active range(s). Faithful port of DeviceEndpointConfig.
 */
export class DeviceEndpointConfig {
  constructor() {
    /** @type {string} DeviceChannelMode token; LINKED by default. */
    this.channels = DeviceChannelMode.LINKED;
    /** @type {DeviceRange[]} */ this.ranges = [];
    /** @type {?string} the selected row's label (LINKED / MONO, and LEFT of INDEPENDENT). */
    this.activeRange = null;
    /** @type {?string} the right channel's active row label; consulted only in INDEPENDENT. */
    this.activeRangeRight = null;
    /** True when this direction's full-scale is OWNED by the device (a QA40x): the
     *  store never writes calibration into its ranges and the seed merge takes them
     *  wholesale. Serialized only when true (DeviceEndpointConfig.calibrationFromDevice). */
    this.calibrationFromDevice = false;
  }

  /** A deep copy (new range-row objects) - DeviceEndpointConfig.deepCopy. */
  deepCopy() {
    const c = new DeviceEndpointConfig();
    c.channels = this.channels;
    c.activeRange = this.activeRange;
    c.activeRangeRight = this.activeRangeRight;
    c.calibrationFromDevice = this.calibrationFromDevice;
    for (const r of this.ranges) c.ranges.push(r.deepCopy());
    return c;
  }
}

/**
 * A logical, backend-independent calibration profile for one physical soundcard.
 * The profile IS the card; {@link #match} is its single list of device-name
 * recognition-and-binding entries. Faithful port of AudioDeviceProfile.
 */
export class AudioDeviceProfile {
  constructor() {
    /** @type {?string} */ this.name = null;
    /** @type {string[]} */ this.match = [];
    this.input = new DeviceEndpointConfig();
    this.output = new DeviceEndpointConfig();
  }

  /** Length of the longest {@link #match} entry that is a case-insensitive substring
   *  of {@code deviceName}, or -1 when none match (AudioDeviceProfile.matchStrength). */
  matchStrength(deviceName) {
    if (deviceName == null) return -1;
    const hay = deviceName.toLowerCase();
    let best = -1;
    for (const entry of this.match) {
      if (entry == null || entry.length === 0) continue;
      if (hay.includes(entry.toLowerCase()) && entry.length > best) best = entry.length;
    }
    return best;
  }

  /** True when any {@link #match} entry is a case-insensitive substring of
   *  {@code deviceName} (AudioDeviceProfile.matches). */
  matches(deviceName) {
    return this.matchStrength(deviceName) >= 0;
  }

  /** Binds this card to {@code deviceName} by appending it to {@link #match}, only
   *  when no existing entry already matches it (idempotent); returns true when an
   *  entry was appended (AudioDeviceProfile.bindDeviceName). */
  bindDeviceName(deviceName) {
    if (deviceName == null || deviceName.length === 0 || this.matches(deviceName)) return false;
    this.match.push(deviceName);
    return true;
  }
}

/**
 * The per-card profile store - the web home of Preferences' device-profile section.
 * Constructor-injected with the live Preferences (the apply* setters write the
 * per-channel full-scale scalars). Persistence is to localStorage under
 * {@link DEVICES_KEY}; on construction it seeds the bundled catalog on first run and
 * runs the once-per-contentVersion upgrade merge (Preferences.loadDevicesFrom).
 */
export class DeviceProfileStore {
  /**
   * @param prefs the live Preferences (apply* / store* write its FS scalars).
   * @param {{storageKey?: string, catalog?: object}} [opts]
   *   - storageKey: the localStorage key (the desktop's devices.yaml path); a test
   *     override, exactly like Preferences.devicesPathOverride.
   *   - catalog: the seed document { formatVersion, contentVersion, audioDevices }, loaded
   *     from the shipped devices.yaml (device-catalog.js). Defaults to null (no seed) - an
   *     absent / null catalog is tolerated: no seed, no merge, the store still loads the
   *     user's persisted cards.
   */
  constructor(prefs, { storageKey = DEVICES_KEY, catalog = null } = {}) {
    this.prefs = prefs;
    this._storageKey = storageKey;
    this._catalog = catalog;
    /** @type {AudioDeviceProfile[]} live profiles, insertion-ordered. */
    this._devices = [];
    /**
     * The SAVED card choice per device: which card a device uses is a
     * DECISION, not a guess, and it must survive restarts. Key: the device NAME; value: the
     * card's logical name. Persisted beside the cards, as devices.yaml carries its `bindings:`
     * block. Consulted BEFORE the match rule by {@link #resolveDeviceProfile} - the
     * substring/longest-match heuristic is what recognises a device nobody has chosen for, and
     * it must never overrule someone who did choose.
     * @type {Map<string, string>}
     */
    this._bindings = new Map();
    /**
     * TRANSIENT cards, by lower-cased logical name - cards that are RESOLVED like any other
     * but belong to no store: today the card of an analyzer on a Phonalyser SERVER, which
     * must never be written into devices storage.
     *
     * WHY AN OVERLAY AND NOT A STORE MEMBER. Such a card is another machine's device, and
     * persisting it is unnecessary as well as wrong: the Preferences dialog re-syncs it on
     * every backend settle / scan, so a reload re-renders it. Keeping it BESIDE {@link
     * #_devices} rather than in them is what makes the collision safe - this installation may
     * have its own card of exactly that name (a QA403 here and a QA403 on the bench), and a
     * store member would have displaced it and needed putting back. Nothing displaces
     * anything here: the overlay is consulted FIRST while it exists, dropped whole when the
     * selection leaves the bench, and never reaches the serializer, which only ever walks
     * {@link #_devices}.
     * @type {Map<string, AudioDeviceProfile>}
     */
    this._overlay = new Map();
    /** The bundled-seed contentVersion this store was last merged against (0 = never). */
    this._recordedContentVersion = 0;
    /** The store's own formatVersion as loaded (0 when never persisted). */
    this._storeFormatVersion = 0;
    this._establish();
  }

  // -------------------------------------------------------------------------
  // establish: seed-if-absent -> read -> once-per-contentVersion merge -> rewrite
  // (Preferences.loadDevicesFrom)
  // -------------------------------------------------------------------------

  /** Establishes the store from localStorage: seeds the whole catalog on first run
   *  (recording its contentVersion, no merge), else reads the stored document and
   *  runs the seed merge when the catalog's contentVersion is greater than the one
   *  the store recorded, rewriting when the merge changed it. */
  _establish() {
    const raw = this._rawStore();
    if (raw == null) {
      // Seed-if-absent: establish from the bundled catalog wholesale, record its
      // version so the merge stays dormant on the next start (Preferences.seedStoreIfAbsent
      // copies the bundle, then readDevicesFile records the bundle's contentVersion).
      this._devices = this._catalogProfiles();
      this._recordedContentVersion = this._catalogContentVersion();
      this._storeFormatVersion = this._catalogFormatVersion();
      this._writeStore();
      return;
    }
    this._readRawInto(raw);
    const seedCv = this._catalogContentVersion();
    if (seedCv > this._recordedContentVersion) {
      this._mergeSeed(this._catalogProfiles());
      this._recordedContentVersion = seedCv;
      this._writeStore();
    }
  }

  _catalogContentVersion() {
    return isNum(this._catalog && this._catalog.contentVersion) ? this._catalog.contentVersion : 0;
  }

  _catalogFormatVersion() {
    return isNum(this._catalog && this._catalog.formatVersion) && this._catalog.formatVersion > 0
      ? this._catalog.formatVersion : 0;
  }

  /** The bundled catalog's cards as fresh profiles, via the shared reader
   *  (Preferences.readSeed's profiles). */
  _catalogProfiles() {
    const out = [];
    const list = this._catalog && Array.isArray(this._catalog.audioDevices) ? this._catalog.audioDevices : [];
    for (const m of list) {
      const p = this._readProfile(m);
      if (p) out.push(p);
    }
    return out;
  }

  // -------------------------------------------------------------------------
  // localStorage read/write (the file mechanism)
  // -------------------------------------------------------------------------

  _rawStore() {
    try {
      return localStorage.getItem(this._storageKey);
    } catch (e) {
      return null;
    }
  }

  /** Parses the stored document into {@link #_devices} + the recorded versions,
   *  tolerantly (Preferences.readDevicesFile): a garbled ENTRY is a guarded warn and skip,
   *  never a throw (a broken store can't stop the app starting). A whole ROOT that will not
   *  parse is QUARANTINED - kept aside under <key>.corrupt - so the next save cannot cement a
   *  defaults-only document over the evidence; this run then starts from the seed. */
  _readRawInto(raw) {
    let root;
    try {
      root = JSON.parse(raw);
    } catch (e) {
      quarantineStoreEntry(this._storageKey, raw, e.toString());
      return;
    }
    if (!root || typeof root !== 'object') {
      quarantineStoreEntry(this._storageKey, raw, 'its content is not a map (empty or truncated entry)');
      return;
    }
    this._readDocInto(root);
  }

  /** Reads a PARSED document into this store - the half of {@link #_readRawInto} past the
   *  parse, split out so an edited document can take the identical path without being
   *  stringified only to be parsed straight back (store/config-port.js). ADDS to whatever is
   *  already here: callers that replace rather than establish must clear first, which is what
   *  {@link #applyConfigDocument} does. */
  _readDocInto(root) {
    if (isNum(root.contentVersion)) this._recordedContentVersion = root.contentVersion;
    if (isNum(root.formatVersion)) this._storeFormatVersion = root.formatVersion;
    // Before the early return below: a store may carry bindings and no cards at all (every
    // binding could name a card the seed supplies). Non-string / empty entries are dropped.
    if (root.bindings && typeof root.bindings === 'object') {
      for (const [k, v] of Object.entries(root.bindings)) {
        if (typeof k === 'string' && typeof v === 'string' && k !== '' && v !== '') {
          this._bindings.set(k, v);
        }
      }
    }
    if (!Array.isArray(root.audioDevices)) return;
    for (const m of root.audioDevices) {
      const p = this._readProfile(m);
      if (p) this._devices.push(p);
    }
  }

  /** Persists the store to localStorage - the policy gate the profile mutators call
   *  (Preferences.saveDevices / writeDevicesTo). formatVersion is copied from the
   *  catalog (single source of truth), falling back to the loaded store's own then 1. */
  saveDevices() {
    this._writeStore();
  }

  /**
   * The LIVE store as a plain object - exactly what {@link #saveDevices} would write, without
   * writing it. The JSON config editor's read half (store/config-port.js), and the only
   * public way to obtain it. {@link #_writeStore} serialises precisely this, so the editor
   * can never show a document that differs from the one on disk.
   *
   * @returns {Object} a fresh plain object; mutating it does not touch this store
   */
  configDocument() {
    const seedFmt = this._catalogFormatVersion();
    const formatVersion = seedFmt > 0 ? seedFmt : (this._storeFormatVersion > 0 ? this._storeFormatVersion : 1);
    const doc = {
      formatVersion,
      contentVersion: this._recordedContentVersion,
      audioDevices: this._devices.map((p) => this._writeProfile(p)),
    };
    // The saved device -> card choices. Omitted entirely while nothing is
    // bound, so a store that never chose a card keeps the document it always had.
    if (this._bindings.size > 0) doc.bindings = Object.fromEntries(this._bindings);
    return doc;
  }

  /**
   * Applies an edited document to the LIVE store and persists it - the JSON config editor's
   * write half (store/config-port.js).
   *
   * THE CLEAR IS THE POINT. {@link #_readDocInto} is written for a store being established
   * and only ever ADDS: run it over a populated store and every card would be duplicated and
   * no deletion would take effect. An edit REPLACES the document, so the cards and bindings
   * are dropped first and rebuilt from the text - which also means removing a card in the
   * editor really removes it.
   *
   * The document goes through the same reader start-up uses, so it meets the same type gates
   * and the same per-profile validation; an entry the reader rejects is skipped exactly as it
   * would be on load, rather than reaching the store unchecked.
   *
   * AND THAT IS WHY IT NEEDS THE FLOOR BELOW. The clear-first rule above is correct, but it
   * makes this store DESTRUCTIVE BY CONSTRUCTION in a way Preferences is not: every
   * assignment in Preferences._fromMap is type-guarded, so a document missing a key simply
   * leaves that value alone, whereas a document missing `audioDevices` clears the cards and
   * then reads none back - every calibration gone, persisted, with nothing having failed.
   * Neither gate upstream can catch it: the quarantine fires only on a PARSE error, and the
   * dialog checks only that the text parses to an object. `{"formatVersion":1}` passes both.
   *
   * So an absent or non-array `audioDevices` is REFUSED here rather than in the dialog, which
   * keeps the floor under every caller this accessor may ever have. Emptying the store on
   * purpose stays possible and stays explicit: `"audioDevices": []` is an array, so it is
   * accepted and really does clear the cards.
   *
   * @param {Object} root the parsed document, already known to be valid JSON
   * @throws {Error} carrying `i18nKey` when the document has no `audioDevices` array
   */
  applyConfigDocument(root) {
    if (!Array.isArray(root.audioDevices)) {
      // The key travels ON the error the way DeviceFailureReason carries one: the wording
      // boundary is the dialog's, but only this line knows WHICH refusal happened.
      const refusal = new Error('the document has no audioDevices array');
      refusal.i18nKey = 'jsonConfig.error.noAudioDevices';
      throw refusal;
    }
    this._devices.length = 0;
    this._bindings.clear();
    this._readDocInto(root);
    this._writeStore();
  }

  _writeStore() {
    try {
      localStorage.setItem(this._storageKey, JSON.stringify(this.configDocument()));
    } catch (e) {
      // Quota / disabled storage - match the desktop "log and continue".
      console.warn('Failed to save device profiles:', e && e.message);
    }
  }

  // -------------------------------------------------------------------------
  // public store operations (Preferences.getAudioDeviceProfiles ... resolveDeviceProfile)
  // -------------------------------------------------------------------------

  /** A defensive deep copy of the profile list, in insertion order
   *  (Preferences.getAudioDeviceProfiles). THIS MACHINE's cards only: the transient overlay
   *  is a bench's card, and the list feeds the LOCAL card combo, which must never offer one. */
  getAudioDeviceProfiles() {
    return this._devices.map((p) => this._copyProfile(p));
  }

  /** The LIVE profile with logical {@code name} (case-insensitive), or null
   *  (Preferences.findAudioDeviceProfile). A TRANSIENT card of that name answers first -
   *  see {@link #_overlay}: while a bench analyzer is the selection, its card is the one in
   *  force, and this machine's own card of the same name is untouched behind it. */
  findAudioDeviceProfile(name) {
    if (name == null) return null;
    const lower = name.toLowerCase();
    const transient = this._overlay.get(lower);
    if (transient != null) return transient;
    for (const p of this._devices) {
      if (p.name != null && p.name.toLowerCase() === lower) return p;
    }
    return null;
  }

  /**
   * Adds {@code p}, or replaces the profile with the same logical name (case-insensitive),
   * then persists (Preferences.putAudioDeviceProfile).
   *
   * A TRANSIENT card is a no-op here - deliberately, and this is the ONE gate that makes the
   * overlay safe. Every route that persists a card ends in this method (the card section's
   * commit, the calibrate writes, the card editor), and a bench card reaching any of them
   * must change the document in no way at all. The caller mutated the object it already
   * holds, which IS the overlay's, so the pick it just made is already visible; there is
   * simply nothing to write.
   */
  putAudioDeviceProfile(p) {
    if (p == null || p.name == null || p.name.length === 0) return;
    if (this._isTransient(p)) return;
    const lower = p.name.toLowerCase();
    this._devices = this._devices.filter((e) => !(e.name != null && e.name.toLowerCase() === lower));
    this._devices.push(p);
    this.saveDevices();
  }

  /**
   * One card as a plain map, in the SAME vocabulary its persisted entry uses (Java
   * Preferences.cardToMap) - what {@code cards.put} carries when a card is handed to the
   * machine the device is plugged into (spec 4.3 v1.1).
   *
   * The wire form IS the store's form on purpose: one codec, so a card that travelled comes
   * back through the reader as the same card. `displayLabel` is not part of it - the writer
   * never emitted it and must not start (it is the dialog's text, re-derived on every device
   * build).
   */
  cardToMap(p) {
    return this._writeProfile(p);
  }

  /**
   * Publishes {@code p} as a TRANSIENT card - resolved like any other, never written to
   * devices storage. Replaces a transient card of the same name; the store's own cards are
   * not touched, so nothing has to be put back when it goes.
   */
  putTransientProfile(p) {
    if (p == null || p.name == null || p.name.length === 0) return;
    this._overlay.set(p.name.toLowerCase(), p);
  }

  /** Drops every transient card - the selection left the bench, or the session ended. What
   *  the store itself holds is what resolves again from here on. */
  clearTransientProfiles() {
    this._overlay.clear();
  }

  /** Whether {@code p} is one of the transient cards - by IDENTITY, so a genuinely local
   *  card that merely shares the name is still stored and persisted as it always was. */
  _isTransient(p) {
    for (const card of this._overlay.values()) {
      if (card === p) return true;
    }
    return false;
  }

  /** Removes the profile with logical {@code name} (case-insensitive), then persists
   *  (Preferences.removeAudioDeviceProfile). */
  removeAudioDeviceProfile(name) {
    if (name == null) return;
    const lower = name.toLowerCase();
    const before = this._devices.length;
    this._devices = this._devices.filter((e) => !(e.name != null && e.name.toLowerCase() === lower));
    if (this._devices.length !== before) this.saveDevices();
  }

  /** The card the user CHOSE for a device, or null when they never chose one - or chose a card
   *  that has since been deleted or renamed, in which case the caller falls through to the
   *  match rule rather than treating the device as unknown: a STALE binding must not
   *  uncalibrate a device, and it is left in the store so it starts working again if a card
   *  with that name reappears (Preferences.boundProfile). */
  _boundProfile(deviceName) {
    const cardName = this._bindings.get(deviceName);
    return cardName == null ? null : this.findAudioDeviceProfile(cardName);
  }

  /** The user's saved device->card choices, as a plain object copy (Java
   *  getDeviceCardBindings). */
  getDeviceCardBindings() {
    return Object.fromEntries(this._bindings);
  }

  /**
   * The binding key for a device on a Phonalyser SERVER: "<serverId>/<device name>" (Java
   * Preferences.deviceBindingKey). Local devices keep the plain name, so nothing about the local
   * store changes - the mirror only has to tell two benches' identically-named analyzers apart.
   *
   * A device name may contain anything, so the SERVER id carries the split point: the net
   * protocol forbids it from containing the separator, and the key is only ever COMPOSED here,
   * never parsed back.
   *
   * @param {?string} serverId the bench's id, or null for a device on this machine
   * @param {string} deviceName the device's name as the catalogue lists it
   * @returns {string} the key {@link #bindDeviceToCard} / {@link #boundCardName} take
   */
  deviceBindingKey(serverId, deviceName) {
    return serverId == null || serverId === '' ? deviceName
      : `${serverId}${BINDING_KEY_SEPARATOR}${deviceName}`;
  }

  /** The logical card name bound to `deviceKey`, or null when nothing is bound (Java
   *  Preferences.boundCardName). Unlike {@link #_boundProfile} this answers the NAME, which is
   *  what a chooser shows even when the card itself lives on the bench. */
  boundCardName(deviceKey) {
    return deviceKey == null ? null : (this._bindings.get(deviceKey) || null);
  }

  /**
   * Records (or, with a blank card name, clears) the user's card choice for a device and
   * persists it - a no-op write when nothing changed (Preferences.bindDeviceToCard).
   *
   * @param {?string} deviceName the live device name (the binding key)
   * @param {?string} cardName the card's logical name; null / '' unbinds
   */
  bindDeviceToCard(deviceName, cardName) {
    if (deviceName == null || deviceName === '') return;
    const wanted = (cardName == null || cardName === '') ? null : cardName;
    const previous = this._bindings.get(deviceName) || null;
    if (previous === wanted) return;
    if (wanted == null) this._bindings.delete(deviceName); else this._bindings.set(deviceName, wanted);
    this.saveDevices();
  }

  /** Resolves a live device name to its owning card: the card the user BOUND to it wins
   *  outright; otherwise a card matches when any of its match entries is a case-insensitive
   *  substring of {@code deviceName}, and on overlap the card with the LONGEST matching entry
   *  wins; null when none match (Preferences.resolveDeviceProfile). */
  resolveDeviceProfile(deviceName) {
    if (deviceName == null) return null;
    const chosen = this._boundProfile(deviceName);
    if (chosen != null) return chosen;
    let best = null;
    let bestStrength = -1;
    // TRANSIENT cards are ranked FIRST, so an equally specific match on a bench card wins the
    // tie against this machine's own (strictly-greater below keeps the first best). While a
    // bench analyzer is the selection its card is the one in force; the local card of the same
    // name is not displaced, only outranked, and resolves again the moment the overlay goes.
    for (const p of [...this._overlay.values(), ...this._devices]) {
      const strength = p.matchStrength(deviceName);
      if (strength > bestStrength) {
        best = p;
        bestStrength = strength;
      }
    }
    return best;
  }

  /** True when {@code deviceLabel} resolves to a bound card whose endpoint for the
   *  given direction ({@code input} true = input) calibrates its two channels
   *  separately - any mode except {@link DeviceChannelMode#MONO}. That is the trigger
   *  for the two-row per-channel calibrate WRITE; a MONO card or an unbound device (no
   *  profile) keeps the shared single-scalar path. Mirrors ScopeTabControl /
   *  FftTabControl.isInputBoundStereo + GeneratorPane.isOutputBoundStereo. */
  isBoundStereo(deviceLabel, input) {
    const p = this.resolveDeviceProfile(deviceLabel);
    if (p == null) return false;
    const ep = input ? p.input : p.output;
    return ep != null && ep.channels !== DeviceChannelMode.MONO;
  }

  /** Strips the backend-specific wrappers a driver puts around the bare card name:
   *  a WASAPI role wrapper (Line/Speakers/Microphone/Headphones "(X)") down to X and
   *  an ALSA "[plughw:x,y]" suffix (Preferences.normalizeDeviceName). */
  normalizeDeviceName(rawName) {
    if (rawName == null) return null;
    let s = rawName.trim();
    const bracket = s.indexOf('[');
    if (bracket > 0) s = s.substring(0, bracket).trim();
    s = this._unwrapRole(s);
    return s.trim();
  }

  /** Removes a leading WASAPI role wrapper - Line/Speakers/Microphone/Headphones
   *  "(X)" -> X; returns {@code s} unchanged when not a recognised wrapper
   *  (Preferences.unwrapRole). */
  _unwrapRole(s) {
    if (!s.endsWith(')')) return s;
    const open = s.indexOf(' (');
    if (open <= 0) return s;
    const role = s.substring(0, open).trim().toLowerCase();
    switch (role) {
      case 'line':
      case 'speakers':
      case 'microphone':
      case 'headphones':
        return s.substring(open + 2, s.length - 1).trim();
      default:
        return s;
    }
  }

  /** True when {@code list} already carries {@code value} (case-insensitive)
   *  (Preferences.containsIgnoreCase). */
  _containsIgnoreCase(list, value) {
    if (value == null) return false;
    const lower = value.toLowerCase();
    for (const s of list) {
      if (s != null && s.toLowerCase() === lower) return true;
    }
    return false;
  }

  // -------------------------------------------------------------------------
  // apply* : resolve the device -> push per-channel full-scale into Preferences
  // (Preferences.applyInput/OutputDeviceProfile)
  // -------------------------------------------------------------------------

  /**
   * The per-channel active-range full-scales (stored RMS) of the card {@code deviceName}
   * resolves to, in the given direction, or null when nothing is bound or the endpoint has no
   * USABLE range row. MONO: the single physical channel's fsLeft fills BOTH values. LINKED:
   * left and right from the SAME active row. INDEPENDENT: left from the activeRange row's
   * fsLeft, right from the activeRangeRight row's fsRight (a dangling right label falls back
   * to the activeRange row's fsRight).
   *
   * "Usable" includes ABOVE ZERO, on both channels. A full scale is the divisor every level is
   * computed against, so a zero is not a quiet device - it is a division by zero, or an
   * infinite reading, depending on which way it is used. A range row that was never calibrated
   * carries exactly that zero, and such a row is an UNCALIBRATED device however it got there:
   * the honest answer is the same null an unbound name gets, and the global fallback stands.
   *
   * Faithful port of Preferences.deviceCalibration.
   *
   * @param {?string} deviceName the live device name
   * @param {boolean} input true for the capture direction
   * @returns {?{fsLeft: number, fsRight: number}} the RMS full scales, or null
   */
  deviceCalibration(deviceName, input) {
    const p = this.resolveDeviceProfile(deviceName);
    if (p == null) return null;
    const ep = input ? p.input : p.output;
    const rowLeft = this._activeRange(ep, 'L');
    if (rowLeft == null) return null;
    const fsLeft = rowLeft.fsLeft;
    const fsRight = this._resolveRightFs(ep, rowLeft, fsLeft);
    if (!(fsLeft > 0) || !(fsRight > 0)) return null;
    return { fsLeft, fsRight };
  }

  /**
   * True when {@code deviceName} would be measured with NO real calibration in this direction
   * - the test behind the warning the Preferences dialog raises when the operator commits a
   * changed backend / device (Java Preferences.isUncalibrated).
   *
   * A calibrationFromDevice endpoint is EXEMPT: the analyzer carries its own factory
   * calibration and there is nothing for the operator to do about it. Checked per DIRECTION and
   * before the store lookup, exactly as Java does - a card can be exempt on input and not on
   * output.
   *
   * @param {?string} deviceName
   * @param {boolean} input
   * @returns {boolean}
   */
  isUncalibrated(deviceName, input) {
    const p = this.resolveDeviceProfile(deviceName);
    if (p != null && (input ? p.input : p.output).calibrationFromDevice) return false;
    return this.deviceCalibration(deviceName, input) == null;
  }

  /** Resolves the input device to its card's per-channel active-range full-scales
   *  and pushes them through Preferences.setAdcFsVoltageRms / ...Right. A no-op leaving the
   *  legacy scalars when unbound or with no usable row - which includes a row whose full
   *  scales are not above zero, i.e. one that was never calibrated (see
   *  {@link #deviceCalibration}) (Preferences.applyInputDeviceProfile). */
  applyInputDeviceProfile(deviceName) {
    const cal = this.deviceCalibration(deviceName, true);
    if (cal == null) return;
    this.applyCalibration(true, cal.fsLeft, cal.fsRight);
  }

  /** The output mirror of {@link #applyInputDeviceProfile}: pushes the peak-amplitude
   *  form (× √2 of the stored RMS) through Preferences.setDacFsVoltageAmpl / ...Right
   *  (Preferences.applyOutputDeviceProfile). */
  applyOutputDeviceProfile(deviceName) {
    const cal = this.deviceCalibration(deviceName, false);
    if (cal == null) return;
    this.applyCalibration(false, cal.fsLeft, cal.fsRight);
  }

  /**
   * Pushes ONE direction's full-scale PAIR (stored RMS volts) into the runtime scalars - the
   * store's single seam from a resolved calibration into what every level is computed against.
   * Input takes the RMS as it stands; output takes its peak-amplitude form (x sqrt(2)). A pair
   * that is not above zero on both channels applies nothing and the standing scalars keep it,
   * the same rule {@link #deviceCalibration} enforces on a card row.
   *
   * <p>Split out of the two resolvers above because a device on a BENCH takes its pair from the
   * SERVER (spec 4.3 `cal`), not from this machine's cards: the values differ - they belong to a
   * different exemplar of possibly the very same model - but the conversion into the scalars is
   * the same one, and having it written twice is how the two would drift.
   */
  applyCalibration(input, fsLeft, fsRight) {
    if (!(fsLeft > 0) || !(fsRight > 0)) return;
    if (input) {
      this.prefs.setAdcFsVoltageRms(fsLeft);
      this.prefs.setAdcFsVoltageRmsRight(fsRight);
    } else {
      this.prefs.setDacFsVoltageAmpl(fsLeft * SQRT2);
      this.prefs.setDacFsVoltageAmplRight(fsRight * SQRT2);
    }
  }

  /** Applies ONLY channel {@code ch} of the input device's card - the per-channel live apply.
   *  On an INDEPENDENT endpoint, 'R' reads ONLY the activeRangeRight row's fsRight and sets ONLY
   *  the right scalar; 'L' reads ONLY the activeRange row's fsLeft and sets ONLY the left scalar
   *  (the untouched channel keeps its calibrated value). LINKED / MONO have one row driving both
   *  scalars, so they delegate to the full {@link #applyInputDeviceProfile}. A no-op when the
   *  device resolves to no card.
   *
   *  WEB-ONLY: Java has no live-apply (the desktop applies both scalars on the Preferences OK);
   *  this per-channel live apply is deliberate on the web - on an INDEPENDENT
   *  endpoint, switching one channel's active range must NOT re-derive the other, untouched one. */
  applyInputDeviceProfileChannel(deviceName, ch) {
    const p = this.resolveDeviceProfile(deviceName);
    if (p == null) return;
    const ep = p.input;
    if (ep.channels !== DeviceChannelMode.INDEPENDENT) { this.applyInputDeviceProfile(deviceName); return; }
    // Zero-guard, per channel: an uncalibrated row applies NO calibration and the standing
    // scalar keeps its value (the same rule {@link #deviceCalibration} enforces on the pair -
    // here only the channel being applied is in play, so only its own full scale is tested).
    if (ch === 'R') {
      const row = this._activeRange(ep, 'R');
      if (row != null && row.fsRight > 0) this.prefs.setAdcFsVoltageRmsRight(row.fsRight);
    } else {
      const row = this._activeRange(ep, 'L');
      if (row != null && row.fsLeft > 0) this.prefs.setAdcFsVoltageRms(row.fsLeft);
    }
  }

  /** The output mirror of {@link #applyInputDeviceProfileChannel}: sets ONLY {@code ch}'s DAC
   *  full-scale (peak amplitude = fs · √2) on an INDEPENDENT endpoint, delegating to the full
   *  {@link #applyOutputDeviceProfile} for LINKED / MONO. WEB-ONLY live apply (see the input
   *  variant's note). */
  applyOutputDeviceProfileChannel(deviceName, ch) {
    const p = this.resolveDeviceProfile(deviceName);
    if (p == null) return;
    const ep = p.output;
    if (ep.channels !== DeviceChannelMode.INDEPENDENT) { this.applyOutputDeviceProfile(deviceName); return; }
    // Zero-guard, per channel - see the input variant.
    if (ch === 'R') {
      const row = this._activeRange(ep, 'R');
      if (row != null && row.fsRight > 0) this.prefs.setDacFsVoltageAmplRight(row.fsRight * SQRT2);
    } else {
      const row = this._activeRange(ep, 'L');
      if (row != null && row.fsLeft > 0) this.prefs.setDacFsVoltageAmpl(row.fsLeft * SQRT2);
    }
  }

  /** The right-channel full-scale for an endpoint whose left active row + fsLeft are
   *  already resolved: INDEPENDENT reads the activeRangeRight row's fsRight, LINKED
   *  the SAME row's fsRight, MONO the single value (the apply* channel rule). */
  _resolveRightFs(ep, rowLeft, fsLeft) {
    switch (ep.channels) {
      case DeviceChannelMode.INDEPENDENT: return this._activeRange(ep, 'R').fsRight;
      case DeviceChannelMode.LINKED: return rowLeft.fsRight;
      default: return fsLeft;   // MONO: one value both sides
    }
  }

  // -------------------------------------------------------------------------
  // store*Calibration : write the active range + apply the scalar(s)
  // (Preferences.storeAdc/DacCalibration)
  // -------------------------------------------------------------------------

  /** Writes {@code fsVrms} as the current input device's ADC calibration (both
   *  channels, LINKED write), auto-creating / binding a card when unbound, then
   *  applies it to both scalars and persists. A device-provided endpoint is a warned
   *  no-op (Preferences.storeAdcCalibration(double)).
   *
   *  {@code deviceLabel} is the web accommodation: the desktop reads the OS device
   *  NAME from BackendPrefs (which the recognition patterns match), but the web's
   *  inputDeviceName slot holds a Web Audio deviceId. Callers pass the human device
   *  LABEL so resolve/seed work; omitted, it falls back to the id slot (the test path,
   *  where the slot already holds a label). */
  storeAdcCalibration(fsVrms, deviceLabel) {
    const deviceName = deviceLabel != null ? deviceLabel : this.prefs.current().inputDeviceName;
    let p = this.resolveDeviceProfile(deviceName);
    if (p == null) p = this._seedProfileFor(true, deviceName);
    if (p.input.calibrationFromDevice) {
      this._warnDeviceProvidedCalibration(p);
      return;
    }
    this._writeActiveRangeFs(p.input, fsVrms);
    this.putAudioDeviceProfile(p);
    this.prefs.setAdcFsVoltageRms(fsVrms);
    this.prefs.setAdcFsVoltageRmsRight(fsVrms);
  }

  /** Per-channel ADC calibrate: on a bound stereo card writes ONLY {@code ch}'s
   *  active-range field and applies only that scalar; on a MONO card both move
   *  together (Preferences.storeAdcCalibration(Channel, double)).
   *  {@code deviceLabel}: see {@link #storeAdcCalibration}. */
  storeAdcCalibrationChannel(ch, fsVrms, deviceLabel) {
    const deviceName = deviceLabel != null ? deviceLabel : this.prefs.current().inputDeviceName;
    let p = this.resolveDeviceProfile(deviceName);
    if (p == null) p = this._seedProfileFor(true, deviceName);
    const ep = p.input;
    if (ep.calibrationFromDevice) {
      this._warnDeviceProvidedCalibration(p);
      return;
    }
    const mono = ep.channels === DeviceChannelMode.MONO;
    this._writeActiveRangeFsChannel(ep, ch, fsVrms);
    this.putAudioDeviceProfile(p);
    if (mono) {
      this.prefs.setAdcFsVoltageRms(fsVrms);
      this.prefs.setAdcFsVoltageRmsRight(fsVrms);
    } else if (ch === 'R') {
      this.prefs.setAdcFsVoltageRmsRight(fsVrms);
    } else {
      this.prefs.setAdcFsVoltageRms(fsVrms);
    }
  }

  /** Writes {@code fsAmpl} (peak amplitude) as the current output device's DAC
   *  calibration, storing the RMS form (÷ √2) for both channels, then applies the
   *  amplitude to both scalars (Preferences.storeDacCalibration(double)).
   *  {@code deviceLabel}: see {@link #storeAdcCalibration} (output device slot). */
  storeDacCalibration(fsAmpl, deviceLabel) {
    const deviceName = deviceLabel != null ? deviceLabel : this.prefs.current().outputDeviceName;
    let p = this.resolveDeviceProfile(deviceName);
    if (p == null) p = this._seedProfileFor(false, deviceName);
    if (p.output.calibrationFromDevice) {
      this._warnDeviceProvidedCalibration(p);
      return;
    }
    this._writeActiveRangeFs(p.output, fsAmpl / SQRT2);
    this.putAudioDeviceProfile(p);
    this.prefs.setDacFsVoltageAmpl(fsAmpl);
    this.prefs.setDacFsVoltageAmplRight(fsAmpl);
  }

  /** Per-channel DAC calibrate - the output mirror of
   *  {@link #storeAdcCalibrationChannel} (Preferences.storeDacCalibration(Channel, double)).
   *  {@code deviceLabel}: see {@link #storeAdcCalibration} (output device slot). */
  storeDacCalibrationChannel(ch, fsAmpl, deviceLabel) {
    const deviceName = deviceLabel != null ? deviceLabel : this.prefs.current().outputDeviceName;
    let p = this.resolveDeviceProfile(deviceName);
    if (p == null) p = this._seedProfileFor(false, deviceName);
    const ep = p.output;
    if (ep.calibrationFromDevice) {
      this._warnDeviceProvidedCalibration(p);
      return;
    }
    const mono = ep.channels === DeviceChannelMode.MONO;
    this._writeActiveRangeFsChannel(ep, ch, fsAmpl / SQRT2);
    this.putAudioDeviceProfile(p);
    if (mono) {
      this.prefs.setDacFsVoltageAmpl(fsAmpl);
      this.prefs.setDacFsVoltageAmplRight(fsAmpl);
    } else if (ch === 'R') {
      this.prefs.setDacFsVoltageAmplRight(fsAmpl);
    } else {
      this.prefs.setDacFsVoltageAmpl(fsAmpl);
    }
  }

  /** Guarded warn when a calibrate write targets a device-provided endpoint
   *  (Preferences.warnDeviceProvidedCalibration). */
  _warnDeviceProvidedCalibration(p) {
    console.warn(`Ignoring calibration write - calibration is device-provided for ${p.name}`);
  }

  /** Resolves the card for a first calibrate on an UNBOUND device: a recognised card
   *  is bound in place (its exact device name appended to match when nothing already
   *  matches), else a fresh bare "default"-row card (Preferences.seedProfileFor). */
  _seedProfileFor(input, deviceName) {
    const recognised = this.resolveDeviceProfile(deviceName);
    if (recognised != null) {
      recognised.bindDeviceName(deviceName);
      return recognised;
    }
    return this._createProfileFor(input, deviceName);
  }

  /** Builds a fresh single-row LINKED card bound to the given direction: logical name
   *  = the normalised device name, the exact device name as the sole match entry, one
   *  "default" range row selected active (Preferences.createProfileFor). */
  _createProfileFor(input, deviceName) {
    const p = new AudioDeviceProfile();
    p.name = this.normalizeDeviceName(deviceName);
    if (deviceName != null) p.match.push(deviceName);
    const row = new DeviceRange();
    row.label = 'default';
    const ep = input ? p.input : p.output;
    ep.channels = DeviceChannelMode.LINKED;
    ep.ranges.push(row);
    ep.activeRange = 'default';
    return p;
  }

  /** The active range row for {@code ch}: LEFT (and any MONO / LINKED endpoint) uses
   *  activeRange; RIGHT on an INDEPENDENT endpoint uses activeRangeRight, falling back
   *  to the activeRange row when unset or dangling; both fall back to the first row,
   *  or null when the table is empty (Preferences.activeRange). */
  _activeRange(ep, ch) {
    const rows = ep.ranges;
    if (rows == null || rows.length === 0) return null;
    const label = ep.activeRange;
    if (ch === 'R' && ep.channels === DeviceChannelMode.INDEPENDENT && ep.activeRangeRight != null) {
      for (const r of rows) {
        if (ep.activeRangeRight === r.label) return r;
      }
      // Dangling right label -> fall back to the activeRange (left) row below.
    }
    if (label != null) {
      for (const r of rows) {
        if (label === r.label) return r;
      }
    }
    return rows[0];
  }

  /** Writes {@code fs} into the endpoint's active row for BOTH channels and marks it
   *  calibrated (Preferences.writeActiveRangeFs(ep, fs)). */
  _writeActiveRangeFs(ep, fs) {
    const row = this._seedableActiveRow(ep, 'L');
    row.fsLeft = fs;
    row.fsRight = fs;
    row.calibrated = true;
  }

  /** Writes {@code fs} into ONLY {@code ch}'s active-range field on a bound stereo
   *  endpoint (MONO falls back to the both-equal write) and marks the row calibrated
   *  (Preferences.writeActiveRangeFs(ep, ch, fs)). */
  _writeActiveRangeFsChannel(ep, ch, fs) {
    if (ep.channels === DeviceChannelMode.MONO) {
      this._writeActiveRangeFs(ep, fs);
      return;
    }
    const row = this._seedableActiveRow(ep, ch);
    if (ch === 'R') row.fsRight = fs;
    else row.fsLeft = fs;
    row.calibrated = true;
  }

  /** Resolves {@code ch}'s active range row, seeding a "default" row (and selecting it
   *  active) when the table is empty (Preferences.seedableActiveRow). */
  _seedableActiveRow(ep, ch) {
    let row = this._activeRange(ep, ch);
    if (row == null) {
      row = new DeviceRange();
      row.label = 'default';
      ep.ranges.push(row);
    }
    if (ep.activeRange == null) ep.activeRange = row.label;
    return row;
  }

  // -------------------------------------------------------------------------
  // once-per-contentVersion seed merge (Preferences.mergeSeed ...)
  // -------------------------------------------------------------------------

  /** Folds the bundled catalog into the store: each catalog card is mapped to a store
   *  card by name or match-list overlap - none found -> the whole card is deep-copied
   *  in; found -> its match entries are unioned on and its RANGE table reconciled per
   *  direction, the store card keeping its own name / channel mode / active range
   *  (Preferences.mergeSeed). */
  _mergeSeed(bundle) {
    for (const seed of bundle) {
      if (seed == null || seed.name == null || seed.name.length === 0) continue;
      const store = this._findStoreCardForSeed(seed);
      if (store == null) {
        this._devices.push(this._copyProfile(seed));
      } else {
        this._unionMatch(store.match, seed.match);
        this._mergeSeedRanges(store.input, seed.input);
        this._mergeSeedRanges(store.output, seed.output);
      }
    }
  }

  /** Add-only case-insensitive union of a seed card's match entries into the store
   *  card's list - existing entries keep place and order, no removals
   *  (Preferences.unionMatch). */
  _unionMatch(into, from) {
    for (const s of from) {
      if (s != null && s.length > 0 && !this._containsIgnoreCase(into, s)) into.push(s);
    }
  }

  /** The store card the {@code seed} card maps onto - first by case-insensitive name,
   *  else by match-list overlap; null when the seed card is new to the store
   *  (Preferences.findStoreCardForSeed). */
  _findStoreCardForSeed(seed) {
    const lower = seed.name.toLowerCase();
    for (const p of this._devices) {
      if (p.name != null && p.name.toLowerCase() === lower) return p;
    }
    for (const p of this._devices) {
      if (this._matchOverlap(seed.match, p.match)) return p;
    }
    return null;
  }

  /** True when {@code a} and {@code b} share at least one case-insensitive entry
   *  (Preferences.matchOverlap). */
  _matchOverlap(a, b) {
    for (const s of a) {
      if (this._containsIgnoreCase(b, s)) return true;
    }
    return false;
  }

  /** Reconciles one direction's range table against the {@code seed} endpoint: each
   *  seed range is added when absent, else replaces the store row in place with the
   *  seed nominal - but a store row the user CALIBRATED carries its measured
   *  fsLeft/fsRight + the calibrated flag into the replacement. A device-provided
   *  endpoint always takes the seed values. Store rows the seed no longer lists stay
   *  (hand-added ranges survive). (Preferences.mergeSeedRanges). */
  _mergeSeedRanges(storeEp, seedEp) {
    if (seedEp == null || storeEp == null) return;
    const deviceOwned = seedEp.calibrationFromDevice;
    const storeRows = storeEp.ranges;
    for (const seedRow of seedEp.ranges) {
      const merged = seedRow.deepCopy();   // nominal seed values + format, calibrated=false
      const idx = this._indexOfLabel(storeRows, seedRow.label);
      if (idx < 0) {
        storeRows.push(merged);
        continue;
      }
      const storeRow = storeRows[idx];
      if (!deviceOwned && storeRow.calibrated) {
        merged.fsLeft = storeRow.fsLeft;
        merged.fsRight = storeRow.fsRight;
        merged.calibrated = true;
      }
      storeRows[idx] = merged;
    }
  }

  /** The index of the first row labelled {@code label} (case-insensitive), or -1
   *  (Preferences.indexOfLabel). */
  _indexOfLabel(rows, label) {
    if (label == null) return -1;
    const lower = label.toLowerCase();
    for (let i = 0; i < rows.length; i++) {
      if (rows[i].label != null && rows[i].label.toLowerCase() === lower) return i;
    }
    return -1;
  }

  /** Deep-copies a profile (new match list + endpoint configs + range rows) - the
   *  state owner performs the copy (Preferences.copyProfile). */
  _copyProfile(src) {
    const c = new AudioDeviceProfile();
    c.name = src.name;
    c.match = src.match.slice();
    c.input = src.input.deepCopy();
    c.output = src.output.deepCopy();
    return c;
  }

  // -------------------------------------------------------------------------
  // document (de)serialisation - the devices.yaml vocabulary in JSON
  // -------------------------------------------------------------------------

  /** Emits one profile as a plain object (Preferences.writeDeviceProfile): match and
   *  the endpoint blocks are omitted when empty. */
  _writeProfile(p) {
    const out = { name: p.name };
    if (p.match.length > 0) out.match = p.match.slice();
    const input = this._writeEndpoint(p.input);
    if (input) out.input = input;
    const output = this._writeEndpoint(p.output);
    if (output) out.output = output;
    return out;
  }

  /** Emits one endpoint block, or undefined when it has no ranges
   *  (Preferences.writeEndpoint). calibrationFromDevice emitted only when true;
   *  activeRange as a scalar (LINKED / MONO) or a { left, right } map (INDEPENDENT). */
  _writeEndpoint(ep) {
    if (ep == null || ep.ranges.length === 0) return undefined;
    const out = { channels: ep.channels };
    if (ep.calibrationFromDevice) out.calibrationFromDevice = true;
    out.ranges = ep.ranges.map((r) => this._writeRange(r));
    if (ep.activeRange != null) out.activeRange = this._writeActiveRange(ep);
    return out;
  }

  /** The activeRange value: an INDEPENDENT endpoint writes the { left, right } map
   *  (an unset right mirrors the left, matching the resolver fallback); LINKED / MONO
   *  keep the scalar row label (Preferences.writeActiveRange). */
  _writeActiveRange(ep) {
    if (ep.channels !== DeviceChannelMode.INDEPENDENT) return ep.activeRange;
    const right = ep.activeRangeRight != null ? ep.activeRangeRight : ep.activeRange;
    return { left: ep.activeRange, right };
  }

  /** Emits one range row - fsVrms ALWAYS the { left, right } pair; calibrated only
   *  when true (Preferences.writeDeviceRange). */
  _writeRange(r) {
    const out = { label: r.label, fsVrms: { left: r.fsLeft, right: r.fsRight } };
    if (r.calibrated) out.calibrated = true;
    return out;
  }

  /** Reads one profile, or null for a garbled entry with no usable name
   *  (Preferences.readDeviceProfile). */
  _readProfile(m) {
    if (!m || typeof m !== 'object') return null;
    if (typeof m.name !== 'string' || m.name.length === 0) {
      console.warn('Skipping device profile with no usable name:', m && m.name);
      return null;
    }
    const p = new AudioDeviceProfile();
    p.name = m.name;
    p.match = this._readMatch(m.match);
    if (m.input && typeof m.input === 'object') p.input = this._readEndpoint(m.input);
    if (m.output && typeof m.output === 'object') p.output = this._readEndpoint(m.output);
    return p;
  }

  /** The match entries as a fresh string list, dropping non-string / empty items
   *  (Preferences.readMatch). */
  _readMatch(raw) {
    const out = [];
    if (Array.isArray(raw)) {
      for (const o of raw) {
        if (typeof o === 'string' && o.length > 0) out.push(o);
      }
    }
    return out;
  }

  /** Reads one endpoint (channel mode defaulting LINKED); accepts activeRange in both
   *  the { left, right } map (INDEPENDENT) and scalar (LINKED / MONO) forms
   *  (Preferences.readEndpoint). */
  _readEndpoint(m) {
    const ep = new DeviceEndpointConfig();
    if (typeof m.channels === 'string' && isChannelMode(m.channels)) ep.channels = m.channels;
    if (typeof m.calibrationFromDevice === 'boolean') ep.calibrationFromDevice = m.calibrationFromDevice;
    if (Array.isArray(m.ranges)) {
      for (const o of m.ranges) {
        const r = this._readRange(o);
        if (r) ep.ranges.push(r);
      }
    }
    const active = m.activeRange;
    if (typeof active === 'string') {
      ep.activeRange = active;
    } else if (active && typeof active === 'object') {
      if (typeof active.left === 'string') ep.activeRange = active.left;
      if (typeof active.right === 'string') ep.activeRangeRight = active.right;
    }
    return ep;
  }

  /** Reads one range row, or null for a garbled row (no label); accepts both the
   *  scalar fsVrms shorthand and the { left, right } map (Preferences.readDeviceRange).
   *
   *  displayLabel is READ but never written (see DeviceRange#displayLabel): a bench
   *  card's content rows carry the verbose dialog text, and taking it is what makes a
   *  remote QA40x's table read exactly like a local one. A persisted document never
   *  has the key, so this is a no-op on the local path. */
  _readRange(m) {
    if (!m || typeof m !== 'object' || typeof m.label !== 'string') {
      console.warn('Skipping device range with no label:', m);
      return null;
    }
    const r = new DeviceRange();
    r.label = m.label;
    const fs = m.fsVrms;
    if (isNum(fs)) {
      r.fsLeft = fs;
      r.fsRight = fs;
    } else if (fs && typeof fs === 'object') {
      if (isNum(fs.left)) r.fsLeft = fs.left;
      if (isNum(fs.right)) r.fsRight = fs.right;
    }
    if (typeof m.calibrated === 'boolean') r.calibrated = m.calibrated;
    if (typeof m.displayLabel === 'string') r.displayLabel = m.displayLabel;
    return r;
  }
}
