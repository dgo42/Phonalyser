/*
 * Phonalyser web - the remembered Phonalyser servers.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of org.edgo.audio.measure.net.client.NetPreferences (+ NetServerEntry), as the
 * `custom.net` block of the persisted preferences document - the same SubPreferences seam the
 * QA40x settings use (js/qa40x/qa40x-preferences.js documents the contract).
 *
 * WHY REMEMBERED AT ALL. Discovery (spec 2.1) only covers the local segment, and a browser
 * cannot listen to a multicast beacon at all - so the web reaches a bench by an address the
 * operator typed once, and typing it again after every restart is what this exists to avoid.
 * A server learned from a peer table (spec 2.2) is remembered too, so its name can be shown
 * greyed-out rather than vanishing while it is switched off.
 *
 * KEYED BY serverId, never by address (spec 2.1): an address changes with the DHCP lease, the
 * installation UUID does not - a bench that moved is still the same bench, and its remembered
 * port and name follow it. `manual` separates the two kinds: a typed entry stays until the
 * operator removes it, a discovered one is only as good as the last thing that answered.
 *
 * ONE port: a server serves its HTTP endpoints and its WebSocket upgrade on the same one, so
 * the single `port` field is both what /info is probed at and what the control channel dials.
 *
 * WEB DIVERGENCE: no proxy fields. Java's NetPreferences carries proxyHost/proxyPort because a
 * desktop client dials the socket itself; a browser's WebSocket follows the system/browser
 * proxy configuration and gives the page no say, so there is nothing here for the operator to
 * set.
 */

/** The block's prefix inside `custom` (Java NetPreferences.KEY). */
const KEY = 'net';

const KEY_SERVERS = 'servers';
const KEY_LAST_CONNECTED = 'lastConnected';
const KEY_SERVER_ID = 'serverId';
const KEY_NAME = 'name';
const KEY_HOST = 'host';
const KEY_PORT = 'port';
const KEY_MANUAL = 'manual';

/**
 * One remembered server (Java NetServerEntry): enough to reach it again without waiting for
 * anything, and enough to show it in the list before it has answered.
 *
 * @param {string} serverId the server's installation UUID
 * @param {string} name its operator-visible name, as last heard
 * @param {string} host the address it was last reached at
 * @param {number} port its bound port, as advertised
 * @param {boolean} manual true when the operator typed it in rather than a peer table naming it
 * @returns {{serverId: string, name: string, host: string, port: number, manual: boolean}}
 */
export function makeServerEntry(serverId, name, host, port, manual) {
  return { serverId, name, host, port, manual: !!manual };
}

export class NetPreferences {

  constructor() {
    /** LIVE remembered servers, keyed by serverId. @type {Map<string, Object>} */
    this._servers = new Map();
    /** The list as the OPEN server dialog has it - the edit copy that makes Cancel possible. */
    this._serversEdit = new Map();
    /** serverId of the server the app last connected to, so the next start can offer it. */
    this._lastConnected = null;
    this._lastConnectedEdit = null;
  }

  // --- SubPreferences ------------------------------------------------------

  key() { return KEY; }

  toMap() {
    const rows = [];
    for (const s of this._servers.values()) {
      rows.push({
        [KEY_SERVER_ID]: s.serverId, [KEY_NAME]: s.name, [KEY_HOST]: s.host,
        [KEY_PORT]: s.port, [KEY_MANUAL]: s.manual,
      });
    }
    const map = { [KEY_SERVERS]: rows };
    if (this._lastConnected != null) map[KEY_LAST_CONNECTED] = this._lastConnected;
    return map;
  }

  /** Absent or unrecognised entries keep the current value rather than throw - the document
   *  may come from an older or a newer release (the SubPreferences contract). */
  fromMap(map) {
    if (map == null || typeof map !== 'object') return;
    const rows = map[KEY_SERVERS];
    if (Array.isArray(rows)) {
      this._servers.clear();
      for (const row of rows) {
        if (row == null || typeof row !== 'object') continue;
        const serverId = row[KEY_SERVER_ID];
        const port = Number(row[KEY_PORT]);
        // A row with no id could never be looked up again, and one with no host could never
        // be dialled: both are dropped rather than shown as a server nothing can reach.
        if (typeof serverId !== 'string' || serverId === '') continue;
        if (typeof row[KEY_HOST] !== 'string' || row[KEY_HOST] === '') continue;
        if (!(port > 0)) continue;
        this._servers.set(serverId, makeServerEntry(serverId,
          typeof row[KEY_NAME] === 'string' ? row[KEY_NAME] : serverId,
          row[KEY_HOST], port, row[KEY_MANUAL] === true));
      }
    }
    if (typeof map[KEY_LAST_CONNECTED] === 'string') this._lastConnected = map[KEY_LAST_CONNECTED];
    this.beginEdit();
  }

  beginEdit() {
    this._serversEdit = new Map(this._servers);
    this._lastConnectedEdit = this._lastConnected;
  }

  commitEdit() {
    this._servers = new Map(this._serversEdit);
    this._lastConnected = this._lastConnectedEdit;
  }

  // --- the list ------------------------------------------------------------

  /** The LIVE servers, keyed by serverId - read-only: the list is changed through the edit
   *  copy, which is what makes Cancel possible. */
  getServers() { return new Map(this._servers); }

  /** The servers as the open server list has them. */
  getEditServers() { return new Map(this._serversEdit); }

  /** Adds `server` to the edit copy, or replaces what was remembered under its id - a bench
   *  that answered from a new address, or under a new name, is the same entry with fresher
   *  contents. */
  putEditServer(server) { this._serversEdit.set(server.serverId, server); }

  /** Forgets one server in the edit copy. */
  removeEditServer(serverId) { this._serversEdit.delete(serverId); }

  /**
   * Writes a server into BOTH copies at once: remembering a bench that is already live is not
   * an edit - a Cancel that made the bench currently being measured on vanish from the list
   * would be a lie about the state of the machine, and for a routed server there is no beacon
   * to put it back (Java putServer).
   */
  putServer(server) {
    this._servers.set(server.serverId, server);
    this._serversEdit.set(server.serverId, server);
  }

  /** The serverId last connected to, or null. */
  getLastConnected() { return this._lastConnected; }

  /** Records the connection both ways, for the same reason putServer does. */
  setLastConnected(serverId) {
    this._lastConnected = serverId;
    this._lastConnectedEdit = serverId;
  }
}
