/*
 * Phonalyser web - the ONE connection between the live stores and an editable JSON document.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * The design constraint: keep the change uninvasive by having exactly ONE connection that
 * loads preferences/devices from the live app and saves them back into it.
 *
 * This module IS that connection. The JSON editor dialog and the Tools menu know nothing
 * about Preferences or DeviceProfileStore - they are handed a PORT from here and speak only
 * to it. So the whole feature touches the stores in exactly one place, and adding a third
 * editable store later means adding a port, not touching the dialog.
 *
 * The live objects come IN, they are never reached for: the shell already owns the one
 * Preferences singleton and the one DeviceProfileStore it injected everywhere else, and a
 * second store built here would edit a document nothing else is using.
 *
 * THE PORT SHAPE - everything the dialog needs and nothing more:
 *
 *   id            stable identifier, for the menu's data attributes
 *   titleKey      i18n key for the window title
 *   storageKey    the localStorage key, shown to the operator and used to find the
 *                 quarantined copy
 *   schema        JSON Schema driving completion and hover help
 *   loadLive()    -> plain object, read from the LIVE app (never from localStorage)
 *   applyLive(o)  -> apply to the live app AND persist
 *   corruptEntry() -> the kept raw text, or null
 */
import { PREFS_KEY } from './preferences.js';
import { DEVICES_KEY } from './device-profiles.js';
import { PREFERENCES_SCHEMA } from './schema/preferences.schema.js';
import { DEVICES_SCHEMA } from './schema/devices.schema.js';

/**
 * The quarantined copy of `storageKey`, if the app ever kept one.
 *
 * ONE ENTRY, NOT A LIST, and unnumbered.
 * {@link module:store/store-quarantine.quarantineStoreEntry} writes a FIXED `<key>.corrupt`
 * and overwrites it, so a second broken document replaces the first rather than accumulating
 * beside it. There is therefore at most one, and enumerating would be inventing a case the
 * writer cannot produce.
 *
 * @param {string} storageKey the live document's key
 * @returns {?string} the kept text, or null when nothing was ever quarantined
 */
function corruptTextFor(storageKey) {
  try {
    return localStorage.getItem(storageKey + '.corrupt');
  } catch (e) {
    // Storage refused (private mode, disabled): there is nothing to offer, which is exactly
    // what "no corrupt copy" looks like. Never throws - this runs while a menu is opening.
    return null;
  }
}

/**
 * Builds the two ports over the LIVE stores.
 *
 * @param {Object} deps
 * @param {Object} deps.prefs the one Preferences instance (Preferences.instance())
 * @param {Object} deps.deviceStore the one DeviceProfileStore the shell injected
 * @returns {Array<Object>} the ports, in menu order
 */
export function createConfigPorts({ prefs, deviceStore }) {
  return [
    {
      id: 'preferences',
      titleKey: 'jsonConfig.title.preferences',
      storageKey: PREFS_KEY,
      schema: PREFERENCES_SCHEMA,
      loadLive: () => prefs.configDocument(),
      applyLive: (doc) => prefs.applyConfigDocument(doc),
      corruptEntry: () => corruptTextFor(PREFS_KEY),
    },
    {
      id: 'devices',
      titleKey: 'jsonConfig.title.devices',
      storageKey: DEVICES_KEY,
      schema: DEVICES_SCHEMA,
      loadLive: () => deviceStore.configDocument(),
      applyLive: (doc) => deviceStore.applyConfigDocument(doc),
      corruptEntry: () => corruptTextFor(DEVICES_KEY),
    },
  ];
}
