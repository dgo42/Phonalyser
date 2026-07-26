/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.sound.qa40x.Qa40xPreferences, plus the
// contract it implements, org.edgo.audio.measure.preferences.SubPreferences.
// JS has no interfaces, so that contract lives here as the SubPreferences JSDoc
// typedef below; its registry is Preferences.registerCustomPreferences /
// beginCustomPreferencesEdit / commitCustomPreferencesEdit in js/store/preferences.js.
//
// The QA402/QA403 settings block — the `custom.qa40x` section of the persisted
// preferences document (preferences.yaml on the desktop, the localStorage JSON here).
// Owned by Qa40xDeviceManager, which registers it with Preferences; the QA40x settings
// dialog edits it and the Preferences dialog's OK commits it.

/** This block's prefix inside the `custom` section — one per owning backend.
 *  Unique across implementations and stable across releases: it is what saved
 *  documents are keyed by. */
const KEY = 'qa40x';
/** The block's only entry: the front-panel I2S expansion port toggle. */
const KEY_I2S = 'i2s';

/**
 * A block of preferences owned by one component rather than by Preferences itself —
 * the settings a single backend has and no other does (the QA40x front-panel I2S
 * port, for instance). The owner registers an implementation with
 * Preferences.registerCustomPreferences, and Preferences then persists it and drives
 * its edit lifecycle without knowing what is inside.
 *
 * **Two values, not one.** An implementation keeps a LIVE value (what the app runs
 * on and what gets saved) and an EDIT value (what the settings dialog is changing).
 * beginEdit() seeds edit from live when the Preferences dialog opens; commitEdit()
 * copies edit into live when that dialog is closed with OK. Cancel simply never
 * commits, so an abandoned edit dies with the dialog — the same contract as
 * Preferences.copyForDialog / applyFromDialog for the ordinary preferences.
 *
 * **Registration can arrive late.** Backend managers are built lazily, so an
 * implementation typically registers long after Preferences.load() has read the
 * document. registerCustomPreferences therefore replays the stored block into
 * fromMap() at registration time; an implementation must tolerate being handed
 * values at any moment.
 *
 * @typedef {Object} SubPreferences
 * @property {() => string} key  the block's prefix inside `custom`; lower-case, no spaces.
 * @property {() => Object<string, *>} toMap  the LIVE values as a serialisable map
 *           (scalars, arrays, nested maps). Called on save; an empty map writes an
 *           empty block.
 * @property {(map: *) => void} fromMap  restores LIVE values from a previously saved
 *           block. Absent or unrecognised entries must keep the current value rather
 *           than throw — the document may come from an older or newer release.
 * @property {() => void} beginEdit  seeds the edit values from the live ones; called
 *           when the Preferences dialog opens, so a previously cancelled edit never
 *           leaks into the next session of the dialog.
 * @property {() => void} commitEdit  copies the edit values into the live ones; called
 *           only when the Preferences dialog is closed with OK.
 */

/**
 * The QA402/QA403 settings block: exactly one setting, held twice per the
 * {@link SubPreferences} contract — the LIVE value the backend runs on and gets
 * saved, and the EDIT value the settings dialog is changing until OK.
 *
 * @implements {SubPreferences}
 */
export class Qa40xPreferences {

  constructor() {
    /** Front-panel I2S expansion port, as the backend currently runs it. @type {boolean} */
    this._i2sEnabled = false;
    /** The value the settings dialog is editing; reaches the live value only
     *  through commitEdit(). @type {boolean} */
    this._i2sEnabledEdit = false;
  }

  /** @returns {boolean} the live I2S port state — what the backend runs on and saves. */
  i2sEnabled() {
    return this._i2sEnabled;
  }

  /** @returns {boolean} the pending I2S port state the settings dialog is editing. */
  i2sEnabledEdit() {
    return this._i2sEnabledEdit;
  }

  /**
   * Records the settings dialog's I2S choice as PENDING; it reaches the live value
   * (and the saved document) only via commitEdit().
   *
   * @param {boolean} enabled
   */
  setI2sEnabledEdit(enabled) {
    // Java's parameter is a primitive boolean; JS has no such guarantee, and a truthy
    // non-boolean stored here would be serialised as-is and then dropped by the
    // boolean type gate in fromMap() — silently losing the setting on the next load.
    this._i2sEnabledEdit = !!enabled;
  }

  /** @returns {string} this block's key inside the `custom` section. */
  key() {
    return KEY;
  }

  /** @returns {Object<string, *>} the LIVE values, as a serialisable map. */
  toMap() {
    return { [KEY_I2S]: this._i2sEnabled };
  }

  /**
   * Restores the LIVE value from a previously saved block, keeping the current value
   * for an absent or non-boolean entry (the document may predate or postdate this
   * release).
   *
   * @param {*} map the stored block
   */
  fromMap(map) {
    const stored = map == null ? undefined : map[KEY_I2S];
    if (typeof stored === 'boolean') {
      this._i2sEnabled = stored;
    }
    // Keep a dialog opened before this arrives consistent with the document.
    this._i2sEnabledEdit = this._i2sEnabled;
  }

  /** Seeds the edit value from the live one (Preferences dialog opening). */
  beginEdit() {
    this._i2sEnabledEdit = this._i2sEnabled;
  }

  /** Copies the edit value into the live one (Preferences dialog closed with OK). */
  commitEdit() {
    this._i2sEnabled = this._i2sEnabledEdit;
  }
}
