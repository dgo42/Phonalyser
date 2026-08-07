/*
 * Phonalyser web - the server list.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of org.edgo.audio.measure.gui.backend.net.NetServerListDialog: which
 * Phonalyser servers are in the room, which one this installation is talking to, and how to
 * reach one discovery cannot see. An SWT dialog on the desktop, a Bootstrap modal here - the
 * same mapping card-editor-dialog.js, calibration-dialog.js and the QA40x settings dialog use.
 *
 * IT SHOWS; IT DOES NOT OWN. Which servers exist is NetServerList's, the session is the
 * bench's (net-device-manager.js), and both outlive this window: the operator connects here
 * and then goes on to pick a backend in the Preferences dialog, which is still open behind it.
 * What is genuinely this type's own is the one thing that exists only while the window does -
 * the repaint timer that lets a bench go grey when it stops answering.
 *
 * NOTHING HERE IS SAVED. A server added or removed goes into the settings block's EDIT copy,
 * so the Preferences dialog's Cancel drops it and its OK writes it. A connection, on the other
 * hand, is live the moment it is made: it is an action, not a setting.
 *
 * TWO WEB DIVERGENCES, both browser facts:
 *  - no proxy row (Java has one): a browser's WebSocket follows the system proxy configuration
 *    and gives the page no say, so there is nothing here for the operator to set;
 *  - no multicast discovery: the Add probe asks the typed server's PEER TABLE (spec 2.2) and
 *    remembers every server it names, which is what one typed address buys on the web.
 */
import { t } from '../i18n/i18n.js';
import { NetServerList, SERVER_REFRESH_MS } from './net-server-list.js';
import { ServerProber, parseAddress } from './server-prober.js';

/**
 * The session half this dialog drives - implemented by the net device manager, passed
 * in exactly as Java passes NetBenchUi.
 *
 * @typedef {Object} NetBench
 * @property {() => import('./net-preferences.js').NetPreferences} getPreferences the settings
 *           block holding the remembered servers (live + edit copies)
 * @property {() => ?Object} getConnectedServer the entry the session is on, or null
 * @property {(server: Object) => Promise<?string>} connect opens a session; resolves null on
 *           success, else the sentence to show (never throws for an ordinary refusal)
 * @property {() => Promise<void>} disconnect ends the session - the generator lane and every
 *           device lock go back ON THE WIRE before the farewell
 */

export class NetServerListDialog {

  /**
   * @param {Object} deps
   * @param {NetBench} deps.bench the session half (above)
   * @param {Object} [deps.modal] the bootstrap Modal for #netServersModal; absent in tests
   * @param {ServerProber} [deps.prober] injected for tests (Java takes the codec for this)
   * @param {() => number} [deps.now] the clock, injected so the offline rule is testable
   */
  constructor({ bench, modal, prober, now, servers }) {
    this._bench = bench;
    this._modal = modal || null;
    this._prober = prober || new ServerProber();
    this._now = now || (() => Date.now());
    // The SHARED list when the app owns one (it starts the liveness watch at boot, so the rows
    // are already live when this window opens); otherwise one of this dialog's own, watched
    // from here - which is what a build with no shell, and every test of this class, gets.
    this._servers = servers || new NetServerList(bench.getPreferences());
    // The shell installs its own prober at load; a list of this window's own (a build with no
    // shell, and every test of this class) gets this one. First caller wins either way.
    this._servers.useProber({ prober: this._prober, now: this._now });
    /** The window's own refresh interval - see {@link #startRefresh}. */
    this._timer = null;
    /** Bound once: it is added and removed from the shared model by identity. */
    this._repaint = () => this.refresh();
    this._subscribed = false;
    /** The row the operator has selected, by serverId (a table has no selection of its own). */
    this._selectedId = null;
    this.$ = window.jQuery;
  }

  /** The model, so the owner can stamp liveness from a session it opened elsewhere. */
  get servers() { return this._servers; }

  /** Wires the buttons once. The rows are rebuilt on every refresh, so their handlers are
   *  delegated from the table body rather than re-bound per row. */
  bind() {
    const $ = this.$;
    $('#netServers').on('click', () => this.open());
    $('#netConnect').on('click', () => this.connectSelected());
    $('#netDisconnect').on('click', () => this.disconnectQuietly().then(() => this.refresh()));
    $('#netRemove').on('click', () => this.removeSelected());
    $('#netAdd').on('click', () => this.addTypedServer());
    $('#netServersRows').on('click', 'tr', (ev) => {
      this._selectedId = ev.currentTarget.dataset.serverId || null;
      this.refresh();
    });
    // Double-click on a row connects it - the SAME code path the Connect button spends, so the
    // staging rules and the refusal message are the same whichever way the operator got there.
    //
    // DELEGATED FROM THE BODY, NOT FROM A ROW, and it takes no row from the event: the first
    // click of the pair repaints the table (and so does the liveness tick), which REPLACES the
    // <tr> the operator clicked. The two clicks then have different targets, the browser
    // dispatches dblclick at their nearest common ancestor - this tbody - and a delegated 'tr'
    // selector matches nothing, so a double-click connected to nothing at all.
    // The row is the one the clicks already selected, which is the row highlighted on
    // screen and the one the Connect button would take.
    $('#netServersRows').on('dblclick', () => this.connectSelected());
    const el = document.getElementById('netServersModal');
    if (el) el.addEventListener('hidden.bs.modal', () => this.stopRefresh());
    return this;
  }

  /**
   * Shows the modal, starts the repaint tick - and asks EVERY remembered server whether it is
   * alive right now: opening this dialog rescans every stored server for its live state.
   *
   * Without that first round the rows opened showing the last session's liveness - which on a
   * fresh page is "nothing has answered yet", i.e. every remembered server grey - until the
   * repaint period came round. The web has no beacon: a unicast `GET /info` IS the liveness
   * (spec 2.2), so the answer has to be asked for, and the natural moment is the one the
   * operator asks the question.
   *
   * NOT awaited: the modal must be on screen before any of it, and each probe carries its own
   * short budget. Rows go live one by one as their servers answer, exactly as they do on the
   * later rounds.
   */
  open() {
    this._error('');
    this.refresh();
    this.startRefresh();
    if (this._modal) this._modal.show();
    // The on-open rescan, on top of the FIRST PAINT above - which already showed what the
    // load-time round found, so the rows are not grey while this one is in flight.
    this._pollRound().catch((e) => console.warn('server probe round failed:', e));
  }

  // ---------------------------------------------------------------------------
  // Actions
  // ---------------------------------------------------------------------------

  /**
   * Adds the typed address - after asking whether a Phonalyser server actually answers there.
   * The probe is what turns an address into an ENTRY: a remembered server is keyed by its
   * installation id (spec 2.1), and only the server can say what its id and its name are.
   *
   * Then its PEER TABLE (spec 2.2): every server it has heard is remembered too, which is the
   * whole of the web's LAN discovery - one reachable address, and the room appears.
   */
  async addTypedServer() {
    const address = parseAddress(this.$('#netHost').val());
    const port = Number(this.$('#netPort').val());
    if (address == null || !(port > 0) || port > 65_535) {
      this._error(t('net.servers.error.address'));
      return;
    }
    // The typed port wins over one inside the host text: the field is what the operator just
    // set, and a stale "host:port" left in the box is not a decision they made twice.
    const host = address.host;
    const info = await this._prober.probeInfo(host, port);
    if (info == null) {
      this._error(t('net.servers.error.noAnswer', `${host}:${port}`));
      return;
    }
    const now = this._now();
    this._servers.heard({ serverId: info.serverId, name: info.name, host, port: info.port }, now);
    // The operator TYPED this one, so it stays until they remove it - heard() preserves the
    // manual flag of an entry that already existed, so it is set here explicitly.
    const typed = this._bench.getPreferences().getEditServers().get(info.serverId);
    this._servers.remember({ ...typed, manual: true });
    for (const peer of await this._prober.probePeers(host, info.port)) {
      this._servers.heard(peer, now);
    }
    this.$('#netHost').val('');
    this._error('');
    this.refresh();
  }

  /** Connects the selected row - from the button, and from a double-click on the row itself. A
   *  row that is already the connected server is not connectable, so neither way re-opens a
   *  session over the live one. */
  async connectSelected() {
    const selected = this._selectedServer();
    if (!this._servers.connectable(selected, this._bench.getConnectedServer())) return;
    let failure;
    try {
      failure = await this._bench.connect(selected);
    } catch (e) {
      console.warn('server connect failed:', e);
      failure = '';   // the operator who cancelled does not need to be told what they just did
    }
    this._error(failure || '');
    this.refresh();
  }

  /** Forgets a server the operator typed in. A discovered one is not removable: the next probe
   *  would put it straight back, so the button would look broken rather than restrictive. */
  async removeSelected() {
    const selected = this._selectedServer();
    if (selected == null || !selected.manual) return;
    const connected = this._bench.getConnectedServer();
    if (connected != null && connected.serverId === selected.serverId) {
      // Forgetting the bench being measured on ends its session first, and that teardown talks
      // to it - see the Disconnect button.
      await this.disconnectQuietly();
    }
    this._servers.forget(selected.serverId);
    if (this._selectedId === selected.serverId) this._selectedId = null;
    this.refresh();
  }

  /** Disconnect without letting a dead wire throw into a button handler - the round trips are
   *  bounded by the connection's own timeouts. */
  async disconnectQuietly() {
    try { await this._bench.disconnect(); } catch (e) { console.warn('server disconnect failed:', e); }
  }

  // ---------------------------------------------------------------------------
  // Rendering
  // ---------------------------------------------------------------------------

  /**
   * The window's own refresh, alive exactly as long as the window is: a round every
   * {@link SERVER_REFRESH_MS}, so a server that stops answering goes grey while the operator is
   * looking at it, and a repaint whenever a round lands.
   *
   * THIS is the only recurring probing in the app. There is no background loop - the app probes
   * the servers it knows once at load and then only here: no standing task probes the servers
   * every couple of seconds while no window is open. The interval dies with the window, and the
   * decay semantics die with it too: outside this the rows paint what the last round found.
   */
  startRefresh() {
    if (this._subscribed) return;
    this._subscribed = true;
    this._servers.addChangeListener(this._repaint);
    this._servers.beginLiveRefresh();
    this._timer = setInterval(() => this._pollRound(), SERVER_REFRESH_MS);
    // A pending timer holds a Node test process open; browsers have no unref and ignore this.
    if (this._timer && typeof this._timer.unref === 'function') this._timer.unref();
  }

  stopRefresh() {
    if (!this._subscribed) return;
    this._subscribed = false;
    this._servers.removeChangeListener(this._repaint);
    this._servers.endLiveRefresh();
    if (this._timer != null) { clearInterval(this._timer); this._timer = null; }
  }

  /** One round of unicast liveness, on the shared model - the on-open freshener. A round
   *  already in flight satisfies it (the model's own guard), so opening the window during the
   *  watch's tick costs nothing. */
  async _pollRound() {
    await this._servers.pollNow();
  }

  /**
   * Re-paints the rows and re-gates the buttons - IN PLACE while the same servers are listed in
   * the same order, and only then rebuilding.
   *
   * That distinction is the whole point. This runs on every selection click and again on every
   * liveness tick; while it emptied the table each time, the <tr> the operator had just clicked
   * was gone before their second click landed - and a browser only raises `dblclick` when both
   * clicks belong to the same node. So no dblclick event was ever generated: the handler was not
   * bound in the wrong place - there was nothing to hand it. Rebuilding only
   * when the row SET changes also stops the tick from destroying the row mid-gesture, which is
   * the same bug with a 2-second fuse.
   */
  refresh() {
    const $ = this.$;
    const body = $('#netServersRows');
    if (!body.length) return;
    const connected = this._bench.getConnectedServer();
    const rows = [...this._servers.rows(this._now())];
    const existing = body.children('tr');
    const sameSet = existing.length === rows.length
      && rows.every((r, i) => existing.eq(i).attr('data-server-id') === r.server.serverId);
    if (!sameSet) body.empty();
    rows.forEach(({ server, online }, i) => {
      const isConnected = connected != null && connected.serverId === server.serverId;
      const state = isConnected ? t('net.servers.state.connected')
        : online ? t('net.servers.state.online') : t('net.servers.state.offline');
      const row = sameSet ? existing.eq(i) : $('<tr>').attr('data-server-id', server.serverId);
      row.toggleClass('table-active', server.serverId === this._selectedId)
        .toggleClass('text-muted', !online && !isConnected);
      if (sameSet) {
        const cells = row.children('td');
        cells.eq(0).text(server.name);
        cells.eq(1).text(`${server.host}:${server.port}`);
        cells.eq(2).text(state);
        return;
      }
      row.append($('<td>').text(server.name));
      row.append($('<td>').text(`${server.host}:${server.port}`));
      row.append($('<td>').text(state));
      body.append(row);
    });
    this.refreshButtons();
  }

  refreshButtons() {
    const selected = this._selectedServer();
    const connected = this._bench.getConnectedServer();
    this.$('#netConnect').prop('disabled', !this._servers.connectable(selected, connected));
    this.$('#netDisconnect').prop('disabled', connected == null);
    this.$('#netRemove').prop('disabled', selected == null || !selected.manual);
  }

  _selectedServer() {
    if (this._selectedId == null) return null;
    return this._bench.getPreferences().getEditServers().get(this._selectedId) || null;
  }

  _error(text) { this.$('#netServersError').text(text || ''); }
}
