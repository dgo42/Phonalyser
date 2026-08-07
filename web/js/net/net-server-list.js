/*
 * Phonalyser web - what the server list shows.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of org.edgo.audio.measure.gui.backend.net.NetServerList: every Phonalyser
 * server this installation knows - learned from a peer table (spec 2.2) or typed in by the
 * operator - and which of them answered recently enough to still count as live.
 *
 * THE REMEMBERED SERVERS ARE NOT KEPT HERE. They live in NetPreferences' EDIT copy, which is
 * the whole point of the staging rule: a server added or removed in this list reaches storage
 * only when the Preferences dialog is closed with OK, exactly like the QA40x's ranges. This
 * type adds the one thing that is NOT persisted - when each server was last heard from - and
 * answers the question the widget asks: what to draw, and which rows are grey.
 *
 * TIME COMES IN AS AN ARGUMENT. Expiry is a comparison against a moment, and a moment is
 * exactly what a widget has when it repaints and a test has when it decides what to prove;
 * reading a clock inside would make the offline rule untestable without sleeping through it.
 *
 * No DOM, no sockets: the dialog feeds it answers and asks it for rows. The one Java member
 * without a web counterpart is `heard(Beacon...)` - a browser cannot join the multicast group,
 * so every liveness stamp on the web comes through {@link #alive} (the unicast GET /info the
 * Java prober uses for exactly the rows discovery cannot reach).
 */
import { NetProto } from './net-proto.js';

/** How often every remembered server is asked whether it is still there - one BEACON interval,
 *  the cadence the servers themselves announce at (spec 2.1). The web has no beacon to hear, so
 *  a unicast GET /info at the same period stands in for one. Deliberately the beacon constant
 *  and not the keepalive's: they are equal today and are not the same thing. */
const REFRESH_MS = NetProto.BEACON_INTERVAL_MS;

/** A server counts as live while its last answer is younger than this - four missed
 *  announcements, the same threshold the peer table and the keepalive use (spec 2.2).
 *
 *  It must comfortably cover {@link REFRESH_MS}, or a row would flap grey between two rounds
 *  that both answered: at 4 × the poll period a server survives THREE consecutive missed
 *  rounds before the list gives up on it. The two are one arithmetic on purpose - the watch
 *  below polls at exactly REFRESH_MS, in the dialog and app-wide alike, so there is no second
 *  cadence for this window to be reconciled against. */
const EXPIRY_MS = 4 * REFRESH_MS;

export class NetServerList {

  /** @param {import('./net-preferences.js').NetPreferences} preferences */
  constructor(preferences) {
    this._preferences = preferences;
    /** serverId -> the moment its last answer arrived. Absent = never heard this session,
     *  which is what a remembered server starts as. @type {Map<string, number>} */
    this._lastHeard = new Map();
    /** The probing seam (see {@link #useProber}): what a round asks with, the clock it stamps
     *  with, and the in-flight guard that keeps two rounds off one set of rows. */
    this._prober = null;
    this._now = null;
    this._polling = false;
    /** Which servers the LAST COMPLETED round found - what a row paints as while nothing is
     *  recurring (see {@link #beginLiveRefresh}). @type {Set<string>} */
    this._lastRoundOnline = new Set();
    /** True while a window is refreshing itself, which is the only time a vouch decays. */
    this._refreshing = false;
    /** Repainted-when-something-moved, for whoever is drawing these rows. @type {Function[]} */
    this._listeners = [];
  }

  // ---------------------------------------------------------------------------
  // Liveness: ONE round at load, and the window's own while it is open
  //
  // There is deliberately NO background loop. The app asks the servers it knows about once,
  // when it loads, and then only while the server window is up - no standing task probes them
  // every couple of seconds. Only the CONNECTED server is kept alive continuously, and that
  // one recurring traffic is the session's own keepalive, which belongs to the connection and
  // to nothing here.
  // ---------------------------------------------------------------------------

  /**
   * Installs the seam a round asks through. First caller wins: the shell hands its prober in at
   * boot, and a window that owns its own list hands in its own.
   *
   * @param {Object} deps
   * @param {import('./server-prober.js').ServerProber} deps.prober the /info seam
   * @param {() => number} deps.now the clock the liveness stamps come from
   */
  useProber({ prober, now }) {
    if (this._prober != null) return;
    this._prober = prober;
    this._now = now;
  }

  /**
   * Tells the model that rounds are RECURRING - the server window is up and refreshing itself.
   *
   * It is what switches {@link #rows} from "as of the last completed round" to the expiry
   * clock: a vouch may only decay while something is actually asking again, or a row would go
   * grey for no reason other than time passing. Outside the window nothing recurs - no standing
   * task probes the servers every couple of seconds - so the last answer stands until the next
   * round is run.
   */
  beginLiveRefresh() { this._refreshing = true; }

  endLiveRefresh() { this._refreshing = false; }

  /**
   * One round of unicast liveness over every watched server, now - the ONE probing primitive.
   * Boot runs it once; the open window runs it on its own interval and on open.
   *
   * Re-entrancy is refused rather than queued: the window's on-open round and its interval's
   * own can overlap on a bench that answers slowly (each row carries its own timeout), and
   * probing every server twice would only spend the budget twice - the round in flight is
   * already asking the same question, and its answers land in the same model.
   *
   * @returns {Promise<void>} resolves when this round (or the one already in flight) is done
   */
  async pollNow() {
    if (this._polling || this._prober == null) return;
    this._polling = true;
    try {
      await this._probeAllRows();
    } catch (e) {
      // A prober that throws is not the operator's problem: the rows simply stay as they are.
      console.warn('net discovery: a liveness round failed:', e && e.message);
    } finally {
      this._polling = false;
    }
    this._changed();
  }

  /** Asks each watched server, in order. A row that answers as a DIFFERENT server is a
   *  reinstallation, and vouching for the old id would mark a ghost live (Java
   *  ServerProber.probe). */
  async _probeAllRows() {
    const answered = new Set();
    for (const server of this._targets()) {
      const info = await this._prober.probeInfo(server.host, server.port);
      if (info != null && info.serverId === server.serverId) {
        this.alive(server.serverId, this._now());
        answered.add(server.serverId);
      }
    }
    // What the LAST COMPLETED round found, which is what a row paints as while nothing is
    // recurring - see {@link #beginLiveRefresh} and {@link #rows}.
    this._lastRoundOnline = answered;
  }

  /**
   * WHICH servers this round asks - the COMMITTED list, plus the staged one while a window is
   * showing it.
   *
   * The committed list is the app-wide truth: a host the operator typed into the dialog and
   * then CANCELLED is never committed, and the edit copy keeps it only until the next
   * beginEdit (the dialog reverts lazily) - so a watch reading the edit copy would go on
   * probing a server the operator explicitly refused, every period, for the life of the page.
   *
   * A staged host still has to be probed while the dialog is UP, though: staging the add is
   * what lets the row flip online before the operator commits, which is the whole point of
   * adding it there. A window subscribed for repaints is exactly "the staged list is on
   * screen", so that is the condition - no second flag to keep in step.
   *
   * ONE round over the UNION, deduped by serverId, rather than two rounds: this is guarded
   * against re-entrancy, and two rounds would either double-probe every server that is in both
   * copies (the usual case - one guard cannot tell them apart) or need a second guard.
   *
   * @returns {Object[]} the server entries to ask, each once
   */
  _targets() {
    const byId = new Map(this._preferences.getServers());
    if (this._listeners.length > 0) {
      for (const [id, server] of this._preferences.getEditServers()) {
        if (!byId.has(id)) byId.set(id, server);
      }
    }
    return [...byId.values()];
  }

  /**
   * Tells whoever is drawing these rows that something moved - a liveness stamp, a server
   * remembered or forgotten. The widget repaints from the model; the model knows no widget.
   *
   * <b>Subscribing means "the staged list is on screen".</b> {@link #_targets} reads the
   * listener count as exactly that, and folds the EDIT copy into the probe targets while
   * anything is subscribed - so a subscriber that is NOT a view of the staged rows would put
   * the app-wide watch back to probing a host the operator typed in and cancelled, for the life
   * of the page. Split {@link #_targets} onto its own signal FIRST if you need one.
   */
  addChangeListener(fn) { this._listeners.push(fn); }

  removeChangeListener(fn) { this._listeners = this._listeners.filter((x) => x !== fn); }

  _changed() {
    for (const fn of [...this._listeners]) {
      try { fn(); } catch (e) { console.warn('net discovery: a server-list listener threw:', e); }
    }
  }

  /**
   * One peer-table row or probe answer and the address it came from: the server is remembered
   * - or refreshed if it was already known - and counts as live from `nowMs` (Java `heard`,
   * whose beacon argument the web replaces with the entry a probe built).
   *
   * A server that was typed in by the operator and is now also being discovered stays MANUAL:
   * finding it does not make it disposable, and the operator who typed it in is the only one
   * who may take it out again.
   *
   * @param {{serverId: string, name: string, host: string, port: number}} server
   * @param {number} nowMs
   */
  heard(server, nowMs) {
    if (!server || typeof server.serverId !== 'string' || server.serverId === '') return;
    const known = this._preferences.getEditServers().get(server.serverId);
    this._preferences.putEditServer({
      serverId: server.serverId, name: server.name, host: server.host, port: server.port,
      manual: known != null && known.manual,
    });
    this.alive(server.serverId, nowMs);
    this._changed();
  }

  /** Remembers `server` as the operator typed it in. Staged, like every other change here - a
   *  server that was only PROBED is an edit; the one a session is actually running on is
   *  remembered live instead (NetPreferences.putServer). */
  remember(server) {
    this._preferences.putEditServer(server);
    this._changed();   // the first server remembered is what arms the watch
  }

  /**
   * Whether `selected` is a row a Connect would do anything with: there has to BE a selection,
   * and it must not already be the server this installation is talking to - Connect is disabled
   * on the entry that is already connected.
   *
   * It lives here rather than in the widget because it is the same rule in two places - the
   * Connect button's enablement and the double-click that connects a row - and a rule spelled
   * out twice is a rule that will eventually disagree with itself.
   *
   * @param {?Object} selected the row the operator has selected, or null for none
   * @param {?Object} connected the server the session is on, or null when there is none
   */
  connectable(selected, connected) {
    return selected != null && (connected == null || connected.serverId !== selected.serverId);
  }

  /** A unicast liveness answer (GET /info) for `serverId`: the server counts as live from
   *  `nowMs`. On the web this is the ONLY source of liveness - there is no beacon to hear.
   *
   *  It is BOTH kinds of vouch at once: the stamp the expiry clock decays while a window is
   *  refreshing, and the standing answer a row paints as while nothing is asking. */
  alive(serverId, nowMs) {
    this._lastHeard.set(serverId, nowMs);
    this._lastRoundOnline.add(serverId);
  }

  /** Forgets `serverId` - staged, so the Preferences dialog's Cancel brings it back. */
  forget(serverId) {
    this._preferences.removeEditServer(serverId);
    // BOTH vouches go: a row the Preferences Cancel brings back is a server nothing has heard
    // from this session, exactly as it was before it was ever probed.
    this._lastHeard.delete(serverId);
    this._lastRoundOnline.delete(serverId);
  }

  /**
   * Every known server as of `nowMs`, in the order they were remembered.
   *
   * @param {number} nowMs
   * @returns {{server: Object, online: boolean}[]}
   */
  rows(nowMs) {
    const out = [];
    for (const server of this._preferences.getEditServers().values()) {
      out.push({ server, online: this._isOnline(server.serverId, nowMs) });
    }
    return out;
  }

  /**
   * Whether a row draws as live.
   *
   * WHILE ROUNDS RECUR (the window is up and refreshing itself) it is the expiry clock: a
   * server that stops answering goes grey within a period of {@link EXPIRY_MS}, which is what
   * makes the window's own refresh worth running.
   *
   * OTHERWISE it is simply what the last completed round found. Nothing is asking any more, so
   * time passing says nothing about the server - and decaying anyway would grey every row
   * seconds after the load-time round, which is exactly the state the operator opens the window
   * to and complains about.
   */
  _isOnline(serverId, nowMs) {
    if (!this._refreshing) return this._lastRoundOnline.has(serverId);
    const heard = this._lastHeard.get(serverId);
    return heard != null && nowMs - heard < EXPIRY_MS;
  }
}

/** The liveness window and the poll period behind it - exported so a widget can say how long a
 *  row may stay lit without an answer, and a test can prove the two agree. EXPIRY is four
 *  REFRESHes (spec 2.2's four missed announcements), which is what keeps a row from flapping
 *  grey between two rounds that both answered. */
export const SERVER_EXPIRY_MS = EXPIRY_MS;
export const SERVER_REFRESH_MS = REFRESH_MS;
