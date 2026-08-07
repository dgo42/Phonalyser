/*
 * Phonalyser web - a server's backend, as a backend.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of org.edgo.audio.measure.net.client.NetDeviceManager (the DEVICE half; the
 * remote generator of spec 4.5 sits beside it, as it does in the Java class) plus the
 * listing glue of gui.backend.net.NetBenchUi - the seam the server-list dialog drives.
 *
 * WHAT IT OWNS. The session pointer, which of the server's backends this manager presents, the
 * parsed catalogue of spec 4.3, and the register of device locks this session holds. What a
 * message MEANS on the wire is NetConnection's; what a stream does with its frames is
 * net-capture-source.js's.
 *
 * THE CATALOGUE IS REPLACED WHOLE, never mutated: `ev.devices.changed` carries the full
 * devices.list payload on every hot-plug and every lock change, so it is taken as it comes -
 * including an array that names no backend at all, which is a server with nothing left to
 * offer and not a message to be second-guessed. It is also where a device in USE is noticed
 * leaving: only this client knows what it holds, so only this client can ask that question.
 *
 * FAILURES ARE NOT CLASSIFIED HERE, and that is the point: a remote device is classified by
 * the backend that owns it, ON THE BENCH, and the answer travels as the reason NAME (spec 4.2
 * on a refusal, 4.3 on ev.device.error). classifyFailure only hands on what the refusal
 * carried - no client ever parses the server's English.
 */
import { MessageType, NetFields, isKnownMessage } from './net-proto.js';
import { NetRefusal } from './net-connection.js';
import { NetCloseReason } from './net-close-reason.js';
import { makeNetDeviceRef, sameNetDevice, refInto, refText, remoteBackendOf } from './net-device-ref.js';
import { isPageOrigin, ServerProber } from './server-prober.js';
import { IntervalTicker } from './ticker.js';
import { deviceFailureReasonFromName, DeviceFailureReason } from '../audio/device-failure-reason.js';
import { MessageBus } from '../bus/message-bus.js';
import { Events } from '../bus/events.js';
import { t } from '../i18n/i18n.js';

/** Fallback channel count for a device format that names none - spec 4.3's streams are
 *  stereo (Java STEREO). */
const STEREO = 2;

/** The i18n key of the warning the analyzers show for a server-side loss (spec 5's GAP),
 *  carried as the payload of FFT_CAPTURE_RESYNC exactly as the FFT's own re-sync reasons are
 *  (Java NetBenchUi.GAP_BANNER_KEY). */
const GAP_BANNER_KEY = 'fft.warning.netGap';

/**
 * How often the CONNECTED server is asked for its peer table - once a second, for as long as
 * the session is open, rather than only when the operator types an address.
 *
 * This is list freshness, not liveness: a background tab that throttles the timer to once a
 * minute simply learns about a new bench a minute later, and nothing depends on the cadence.
 * The keepalive is the thing that must never be late, and it is not a timer at all - it is
 * answered from the socket's own message handler.
 */
const PEER_POLL_MS = 1_000;

export class NetDeviceManager {

  /**
   * @param {Object} [deps]
   * @param {(server: Object) => Promise<Object>} [deps.openConnection] builds and opens a
   *        NetConnection for a remembered server - injected so a test connects without a
   *        socket, exactly as Java injects the codec and the ticker.
   * @param {import('./net-preferences.js').NetPreferences} deps.preferences the remembered
   *        servers (the server-list dialog reads the same block).
   * @param {import('./server-prober.js').ServerProber} [deps.prober] how the peer table is
   *        asked for - injected for the same reason the connection is.
   * @param {{start: Function, stop: Function}} [deps.peerTicker] the 1 s clock behind that
   *        poll; injected so a test advances it instead of waiting.
   */
  constructor({ openConnection, preferences, prober, peerTicker } = {}) {
    this._openConnection = openConnection || null;
    this._preferences = preferences;
    /** The peer table's two collaborators - see {@link #_startPeerPoll}. */
    this._prober = prober || new ServerProber();
    this._peerTicker = peerTicker || new IntervalTicker();
    /** In-flight guard: one GET at a time, whatever the timer does. */
    this._pollingPeers = false;
    /** The session this backend speaks through, or null while no server is connected. */
    this._connection = null;
    /** The remembered-server entry the session is on, or null. */
    this._connectedServer = null;
    /** Which of the server's backends this manager presents, as the wire name of spec 4.3. */
    this._remoteBackend = null;
    /** The catalogue as last heard, replaced whole. @type {Object[]} */
    this._devices = [];
    /** The server's backends as last heard - the combo's synchronous source (see
     *  {@link #refreshRemoteBackends}). @type {Object[]} */
    this._backendEntries = [];
    /** The devices this session holds the exclusive lock of spec 4.3 on. @type {Object[]} */
    this._held = [];
    /** Held as a field, not a fresh closure at the call site: the same listener has to be
     *  removable again (Java's `events` field). */
    this._events = (event) => this._onEvent(event);
    this._onClosed = (reason) => this._sessionEnded(reason);
  }

  // ---------------------------------------------------------------------------
  // The session (the NetBench seam the server-list dialog drives)
  // ---------------------------------------------------------------------------

  getPreferences() { return this._preferences; }

  /** The remembered-server entry the session is on, or null (Java NetBenchUi). */
  getConnectedServer() { return this._connectedServer; }

  /** The live session, or null - what the capture source and the generator send through. */
  get connection() { return this._connection; }

  /**
   * The backends the server offers, as the entries the combo lists: one per server backend,
   * named "<server name> -> <backend>" (spec 4.3's explicit listing API). Empty with no session.
   *
   * Fetched here and CACHED: the Preferences backend combo is built synchronously, like every
   * other entry in it, so it reads {@link #getRemoteBackendEntries} - a combo that awaited a
   * round trip would either block its own build or fill in after the operator had already
   * looked at it. Refreshed on connect and on every ev.devices.changed, which spec 4.3 sends
   * on hot-plug and lock change: the moment the bench's offer moves, so does the list.
   */
  async refreshRemoteBackends() {
    const open = this._connection;
    if (open == null || !open.isOpen) { this._setBackendEntries([]); return []; }
    let answer;
    try {
      answer = await open.uiRequest(open.newRequest(MessageType.BACKEND_LIST));
    } catch (e) {
      console.warn(`net backend: the backend.list ask failed: ${e.message}`);
      return this._backendEntries;   // keep what was on offer rather than emptying the combo
    }
    if (answer.ok === false) return this._backendEntries;
    const rows = (answer.data && answer.data[NetFields.BACKENDS]) || [];
    this._setBackendEntries(rows.map((row) => ({
      backend: row[NetFields.BACKEND],
      displayName: `${open.serverName} -> ${row[NetFields.DISPLAY_NAME] || row[NetFields.BACKEND]}`,
      available: row[NetFields.AVAILABLE] === true,
      operational: row[NetFields.OPERATIONAL] === true,
      hasBitDepth: row[NetFields.HAS_BIT_DEPTH] === true,
    })));
    return this._backendEntries;
  }

  /**
   * The one place the offer changes, so no path can move it without saying so: the combo that
   * shows these entries is built SYNCHRONOUSLY and only when something tells it to (Java
   * refreshBackendCombo, called when the server list has been open), and a cache refreshed on
   * connect / ev.devices.changed / disconnect would otherwise never reach the operator's screen.
   * Announced on the bus rather than through a stored callback: the manager must not know what a
   * combo is (house rule - one-way dependencies).
   */
  _setBackendEntries(entries) {
    this._backendEntries = entries;
    MessageBus.instance().publish(Events.NET_BACKENDS_CHANGED, entries);
  }

  /** The cached remote-backend entries - read SYNCHRONOUSLY by the combo build. Empty with no
   *  session, which is exactly what a combo with no server to offer must show. */
  getRemoteBackendEntries() { return this._backendEntries; }

  /** Whether `backend` is one THIS server offers - the dispatch's test for "is the selected
   *  backend remote", answered from the same cache the combo was filled from, so a name can
   *  never be routed at a bench that never offered it. */
  isRemoteBackend(value) {
    const remote = remoteBackendOf(value);
    return remote != null && this._backendEntries.some((entry) => entry.backend === remote);
  }

  /**
   * Opens a session to `server` and returns null, or the sentence to show when it did not
   * happen (the dialog's contract: success is null, a refusal is text, and a cancel is '').
   */
  async connect(server) {
    await this.disconnect();
    if (this._openConnection == null) return t('net.error.noConnector');
    let connection;
    try {
      connection = await this._openConnection(server);
    } catch (e) {
      // A browser blocks ws:// from an https page without ever dialling (spec §3), and that is
      // the one connect failure with an explanation worth giving.
      return this._connectFailureText(server, e);
    }
    this._connection = connection;
    this._connectedServer = server;
    connection.addEventListener(this._events);
    connection.addCloseListener(this._onClosed);
    // Remembered LIVE, not staged: the bench being measured on must not vanish on a Cancel.
    if (this._preferences) {
      this._preferences.putServer({ ...server, name: connection.serverName || server.name,
        serverId: connection.serverId || server.serverId });
      this._preferences.setLastConnected(connection.serverId || server.serverId);
    }
    // Fill the combo's source before anyone reads it: a session is only useful once its
    // backends can be picked, and the entries are what the operator picks from.
    await this.refreshRemoteBackends();
    this._startPeerPoll();
    return null;
  }

  // ---------------------------------------------------------------------------
  // The peer table of the connected server (spec 2.2)
  // ---------------------------------------------------------------------------

  /**
   * Asks the CONNECTED server for its peer table every {@link PEER_POLL_MS} and remembers what
   * it names - which is how "type one address and the room appears" is supposed to work.
   *
   * <p>It used to happen in ONE place only: the operator typing an address into the server
   * dialog and pressing Add (NetServerListDialog.addTypedServer). Connecting - by the Connect
   * button, or by the embedded page auto-connecting to the server that served it - asked for no
   * peer table at all, so a client that never typed an address never learned of a second bench.
   *
   * <p>Started when a session opens and stopped when it ends, so nothing polls while there is
   * nothing to poll: an unconnected client is covered by the probe rounds, which ask the servers
   * it already knows.
   */
  _startPeerPoll() {
    this._peerTicker.start(PEER_POLL_MS, () => {
      this._pollPeers().catch((e) => console.warn('net discovery: the peer poll failed:', e));
    });
  }

  _stopPeerPoll() {
    this._peerTicker.stop();
    this._pollingPeers = false;
  }

  /** One peer-table round against the connected server. Re-entrancy is refused rather than
   *  queued: the round in flight is asking the same question, and each carries its own budget. */
  async _pollPeers() {
    const server = this._connectedServer;
    if (this._pollingPeers || server == null || this._connection == null) return;
    this._pollingPeers = true;
    try {
      for (const peer of await this._prober.probePeers(server.host, server.port)) {
        this._rememberPeer(peer);
      }
    } finally {
      this._pollingPeers = false;
    }
  }

  /**
   * Remembers one peer - by its serverId, which is the identity a server keeps across a rename
   * and a move (spec 2.1). An entry that is already known and unchanged is left alone, so the
   * once-a-second round writes nothing at all on a bench that is not moving.
   *
   * <p>A peer the operator TYPED IN stays manual: being discovered as well does not make it
   * disposable, and only the operator who typed it may take it out again (the rule
   * NetServerList.heard follows).
   */
  _rememberPeer(peer) {
    if (this._preferences == null || peer == null || !peer.serverId) return;
    const known = this._preferences.getServers().get(peer.serverId);
    const entry = { ...peer, manual: known != null && known.manual === true };
    if (known != null && known.host === entry.host && known.port === entry.port
        && known.name === entry.name && known.manual === entry.manual) {
      return;
    }
    this._preferences.putServer(entry);
  }

  /**
   * Points this backend at the backend `selected` names - and fills the catalogue from that
   * same payload. `selected` is the answer to backend.select: one entry in devices.list shape,
   * which spec 4.3 shapes that way precisely so "one round-trip fills the device combos", so
   * the two can never disagree and the selection is usable the moment this returns.
   *
   * @param {string} backend the server's backend enum name
   * @returns {Promise<?string>} null on success, else the refusal's sentence
   */
  async selectBackend(backend) {
    const open = this._requireConnection();
    const request = open.newRequest(MessageType.BACKEND_SELECT);
    request[NetFields.BACKEND] = backend;
    const answer = await open.uiRequest(request);
    if (answer.ok === false) return open.refusal(`backend.select ${backend}`, answer).message;
    this._remoteBackend = (answer.data && answer.data[NetFields.BACKEND]) || backend;
    this._devices = this._devicesOf(answer.data || {}, this._remoteBackend);
    console.info(`net backend: routed at ${this._remoteBackend} on '${open.serverName}' `
      + `with ${this._devices.length} device(s)`);
    return null;
  }

  /**
   * Forgets the server: the catalogue empties, so nothing offers a device that can no longer
   * be opened. Every remaining lock is given back ON THE WIRE first - spec 4.3 keeps a lock
   * once taken, so a lock merely dropped from the register would be a device no other client
   * could free until the session died.
   *
   * The connection is closed here (the web has one session per bench and the dialog owns
   * neither), with BYE so the server releases everything at once.
   */
  async disconnect() {
    this._stopPeerPoll();
    const open = this._connection;
    if (open != null && open.isOpen) {
      await this._releaseHeld();
    }
    if (open != null) {
      open.removeEventListener(this._events);
      open.removeCloseListener(this._onClosed);
      open.close(NetCloseReason.BYE);
    }
    this._connection = null;
    this._connectedServer = null;
    this._remoteBackend = null;
    this._devices = [];
    this._setBackendEntries([]);   // nothing offers a backend that can no longer be selected
    this._held = [];
  }

  /** The session ended under us (keepalive, transport, protocol): the catalogue is not to be
   *  trusted any more and the modules must be told - the same surface a local device error
   *  takes, which is what spec 4.1 asks for ("stop all modules, show the connection error"). */
  _sessionEnded(reason) {
    // Whatever ended it, nothing is left polling a server this client no longer talks to.
    this._stopPeerPoll();
    if (reason === NetCloseReason.BYE) return;   // our own goodbye is not a fault
    console.error(`net backend: the session ended - ${reason.detail}`);
    this._connection = null;
    this._connectedServer = null;
    this._devices = [];
    this._setBackendEntries([]);
    this._held = [];
    MessageBus.instance().publish(Events.AUDIO_DEVICE_ERROR, {
      direction: 'input',
      reason: DeviceFailureReason.DEVICE_DISCONNECTED.name,
      message: t(reason.messageKey),
    });
  }

  // ---------------------------------------------------------------------------
  // What goes wrong on the bench
  // ---------------------------------------------------------------------------

  /**
   * This backend's reading of its own failures - and for once the reading was not done here:
   * all this does is hand on what the refusal carried (Java classifyFailure). Anything that is
   * not a refusal - a socket that died mid-open, a JSON fault - is UNKNOWN: it was never the
   * far device's failure.
   */
  classifyFailure(failure) {
    return (failure instanceof NetRefusal) ? failure.reason : DeviceFailureReason.UNKNOWN;
  }

  /**
   * WHAT the bench said, when the bench said anything - the sentence NetConnection.refusal()
   * composed out of spec 4.2: the error CODE, the server's own message and, for a
   * DEVICE_LOCKED, the holder ("... (held by Developer's laptop)"). Null for anything that was
   * not a refusal, which is what keeps a driver's raw text out of the operator's face: only a
   * sentence this client built from PROTOCOL fields can ever be shown.
   *
   * It exists because the reason alone is not always an answer. Every code-only refusal
   * (DEVICE_LOCKED, BAD_REQUEST, UNSUPPORTED, DEVICE_STALE, NOT_LOCKED, INTERNAL) carries NO
   * §4.2 `reason` field - the client reads UNKNOWN - and "reason unknown" is precisely the
   * sentence the operator must never be left with.
   */
  refusalText(failure) {
    return (failure instanceof NetRefusal) ? failure.message : null;
  }

  /**
   * Spec 4.3: the bench says a lane died. LOG-ONLY here, exactly as Java's NetBenchUi has it -
   * but NOT because nobody needs it: because each direction is reported by the layer that owns
   * the thing that stopped, one failure to one surface.
   *
   *   INPUT - the same failure reaches the open capture stream, which finishes its ring
   *   (net-capture-source.js); the measuring pane consults its reader and reports.
   *
   *   OUTPUT - the same event reaches the open generator lane (net-playback-sink.js
   *   _onDeviceError), which records it in its claim-once cell; the generator pane's ON-AIR
   *   tick claims it, stops the generator and raises the report.
   *
   * The output half used to be justified as "it comes back out of the remote generator's own
   * calls" - which is FALSE for an ENGAGED generator: gen.start was answered when the lane
   * accepted the tone, and a playing generator makes no further calls, so a mid-play death
   * reached nobody and the play LED stayed lit over a stopped lane, with no error shown at all
   * when a server-side output lane stopped playing.
   *
   * Surfacing it HERE as well would tell the operator twice, and a failure reported twice is a
   * failure nobody trusts (NetPcmCapture's own words).
   */
  _deviceError(direction, detail, reason) {
    console.error(`net backend: the ${direction} lane died (${reason.name}) - ${detail}`);
  }

  /**
   * Spec 5: the server admitted to a loss. The stream carries on, so this is not a device
   * error - but it is not nothing either: the samples on either side of the gap are not
   * adjacent in time, and an analyzer that averages across the splice reports a noise floor
   * and a phase that never existed.
   *
   * So the analyzers are TOLD, on the seam they already use for a capture that had to re-sync
   * - the operator gets the same blinking confession an overrun raises, which is the whole
   * point of the GAP frame (Java NetBenchUi.gap).
   */
  gap(lostFrames) {
    console.warn(`net bench: the server lost ${lostFrames} stereo frame(s)`);
    MessageBus.instance().publish(Events.FFT_CAPTURE_RESYNC, GAP_BANNER_KEY);
  }

  /** A device this session HOLDS is no longer in the bench's catalogue. Logged even with
   *  nothing subscribed, for the same reason a gap is: a measurement that carried on against a
   *  device that is not there must leave a trace somewhere. */
  _deviceGone(ref) {
    console.error(`net backend: ${refText(ref)} is gone from the bench while this client was using it`);
    MessageBus.instance().publish(Events.AUDIO_DEVICE_ERROR, {
      direction: ref.isInput ? 'input' : 'output',
      reason: DeviceFailureReason.DEVICE_DISCONNECTED.name,
      message: t(DeviceFailureReason.DEVICE_DISCONNECTED.i18nKey),
    });
  }

  /** Spec 4.3 / 4.5 events. Others are somebody else's. */
  _onEvent(event) {
    switch (event.t) {
      case MessageType.EV_DEVICES_CHANGED:
        this._cache(event[NetFields.BACKENDS]);
        // The bench's OFFER can move with its devices (a backend whose only card was
        // unplugged), so the combo's source is re-asked on the same event the catalogue is -
        // fire-and-forget, because this runs on the delivery path and nothing here waits.
        this.refreshRemoteBackends().catch(() => { /* the warn is inside */ });
        break;
      case MessageType.EV_DEVICE_ERROR:
        this._deviceError(event[NetFields.DIRECTION], event[NetFields.DETAIL],
          deviceFailureReasonFromName(event[NetFields.REASON]));
        break;
      default:
        break;
    }
  }

  // ---------------------------------------------------------------------------
  // The device contract
  // ---------------------------------------------------------------------------

  /** The operator's Scan, remote twin: one devices.list round-trip, rebuilding the cache
   *  exactly as an ev.devices.changed would. false with no live session - the list cannot be
   *  rebuilt, which is what the answer means. */
  async refreshDeviceList() {
    const open = this._connection;
    if (open == null || !open.isOpen) return false;
    try {
      const answer = await open.uiRequest(open.newRequest(MessageType.DEVICES_LIST));
      if (answer.ok === false) {
        console.warn('net backend: the devices.list re-ask was refused - the catalogue stays as it was');
        return false;
      }
      this._cache(answer.data && answer.data[NetFields.BACKENDS]);
      return true;
    } catch (e) {
      console.warn(`net backend: the devices.list re-ask failed: ${e.message}`);
      return false;
    }
  }

  /** The SPI's rule that enumeration "must degrade gracefully ... never a throw" costs nothing
   *  here: the catalogue is a field, so a manager nobody has connected - and one whose bench
   *  went away - answers the empty list a combo already knows how to show. */
  listInputDevices() { return this._listDevices(true); }

  listOutputDevices() { return this._listDevices(false); }

  /** The device at `index` of one direction's list - matched on the index the server
   *  published, so a device that vanished fails here rather than handing back its neighbour. */
  getDeviceByIndex(index, isOutput) {
    for (const ref of this._listDevices(!isOutput)) if (ref.index === index) return ref;
    throw new Error(`no remote ${isOutput ? 'output' : 'input'} device with index ${index} `
      + `on ${this._remoteBackend}`);
  }

  /**
   * The device of one direction called `name`, or a REFUSAL naming why - the resolution every
   * open makes, because a remote device has no browser deviceId and the configured selection
   * is its NAME.
   *
   * It answers a {@link NetRefusal} carrying DEVICE_NOT_FOUND rather than a bare Error for the
   * reason this class exists: the operator is told WHY. A plain throw here classified as
   * UNKNOWN, and a generator start against a name this bench no longer offers reported
   * "Could not open the output device: reason unknown" - a sentence that names neither the
   * device nor anything to do about it. DEVICE_NOT_FOUND is
   * precisely this case: "the configured device is not on this host at all - a card that was
   * remembered from another machine, or a renamed one".
   *
   * Matched on the name the server published, never on a near neighbour, for the same reason
   * {@link #getDeviceByIndex} is: opening the wrong device measures the wrong thing.
   */
  getDeviceByName(name, isOutput) {
    const direction = isOutput ? 'output' : 'input';
    // No name is not a name the bench could ever answer to: it means nothing is selected here,
    // and sending it would spend a round trip to be told the server has no device called
    // "null" - which reads as a fault of the bench rather than of this screen.
    if (typeof name !== 'string' || name === '') {
      throw new NetRefusal(`no ${direction} device is selected`,
        DeviceFailureReason.DEVICE_NOT_FOUND);
    }
    for (const ref of this._listDevices(!isOutput)) {
      if (ref.name !== name) continue;
      // Taken by somebody else: refused HERE, with the reason that says so. The catalogue
      // already carries the holder (spec 4.3's lock overview), so an open that could only come
      // back DEVICE_LOCKED is not worth sending - and the operator gets "in use by another
      // application" instead of a generic failure.
      const holder = this.lockedBy(ref);
      if (holder != null) {
        throw new NetRefusal(`${refText(ref)} is in use by ${holder}`,
          DeviceFailureReason.DEVICE_IN_USE);
      }
      return ref;
    }
    throw new NetRefusal(`the server offers no ${direction} device named `
      + `'${name}' on ${this._remoteBackend} - rescan the bench`,
    DeviceFailureReason.DEVICE_NOT_FOUND);
  }

  /** The formats spec 4.3 inlines with the device. The direction is the REF's - a ref names
   *  one direction of one device - so a ref from another backend gets the empty list. */
  listSupportedFormats(device) {
    const remote = this._find(device);
    return remote ? remote.formats : [];
  }

  /** Whether the operator may choose a sample WIDTH, as the SERVER declared it per device: a
   *  QA403 offers 24-bit only until its front-panel I2S port is switched on, and only the
   *  bench knows that. A ref of another backend answers false - no choice to offer.
   *
   *  WITH NO DEVICE it answers for the BACKEND - does any device of this bench declare a
   *  selectable width - which is the question the depth ROW asks (it belongs to the backend,
   *  not to one device), and the shape Java's AudioDeviceManager.hasBitDepth() has. Without
   *  it the row stayed hidden for every server device although the flag is true on all of
   *  them, so no width was shown in either direction. */
  hasBitDepth(device = null) {
    if (device == null) return this._devices.some((d) => d.hasBitDepth);
    const remote = this._find(device);
    return remote ? remote.hasBitDepth : false;
  }

  /**
   * The name of the client holding `device`'s lock on the bench, or null when it is free - kept
   * from the catalogue so a device somebody else is measuring on can be shown as taken instead
   * of being discovered as a DEVICE_LOCKED refusal at open time.
   *
   * <b>OUR OWN lock answers null</b>, exactly as the desktop's seam does
   * (NetBenchUi.lockedBy drops a holder whose name is this client's). The bench reports every
   * lock, including this session's, and the combo turns a holder into "in use by ..." plus a row
   * that cannot be picked - so reporting our own would tell the operator that the device they
   * are measuring on right now is somebody else's, and refuse them the pick of it. The test is
   * this session's own register rather than a name comparison: two clients may run under the
   * same name, and only the register knows which lock is ours.
   */
  lockedBy(device) {
    const remote = this._find(device);
    if (remote == null || remote.lockedBy == null) return null;
    return this.holdsLock(remote.ref) ? null : remote.lockedBy;
  }

  /** The server's stored calibration for a device, or null when it has none - the device is
   *  then uncalibrated on this client, which warns rather than quietly measuring against a
   *  default (spec 4.3 `cal`, v1.1: calibration lives where the device is connected). */
  calibrationOf(device) {
    const remote = this._find(device);
    return remote ? remote.ref.calibration : null;
  }

  /** The logical name of the server card IN FORCE for a device, or null when none correlates
   *  (spec 4.3 `card`, v1.1). */
  boundCardOf(device) {
    const remote = this._find(device);
    return remote ? remote.ref.boundCard : null;
  }

  // ---------------------------------------------------------------------------
  // Locks
  // ---------------------------------------------------------------------------

  /** Takes `ref`'s exclusive lock (spec 4.3). Returns null on success, else the refusal -
   *  whose DEVICE_LOCKED names the holder, which is what makes it actionable. */
  async acquireDevice(ref) {
    const open = this._requireConnection();
    const answer = await open.request(refInto(ref, open.newRequest(MessageType.DEVICE_ACQUIRE)));
    if (answer.ok === false) return open.refusal(`cannot take ${refText(ref)}`, answer);
    this.lockTaken(ref);
    return null;
  }

  /** Records that this session took `ref`'s lock. The request itself belongs to whoever made
   *  it - a capture has to refuse with the server's own error code - so the register is told
   *  rather than asked. */
  lockTaken(ref) {
    if (!this._held.some((r) => sameNetDevice(r, ref))) this._held.push(ref);
  }

  /** True while this session holds `ref`'s lock. */
  holdsLock(ref) { return this._held.some((r) => sameNetDevice(r, ref)); }

  /** Gives one of this session's device locks back (spec 4.3: the release also closes anything
   *  still open on it), best effort: this is a teardown step, and a lock left behind is a
   *  device no other client can take until the session dies. */
  async releaseDevice(ref) {
    this._held = this._held.filter((r) => !sameNetDevice(r, ref));
    const session = this._connection;
    if (session == null) return;
    try {
      await session.request(refInto(ref, session.newRequest(MessageType.DEVICE_RELEASE)));
    } catch (e) {
      console.warn(`net backend: releasing ${refText(ref)} failed: ${e.message}`);
    }
  }

  /**
   * Runs `work` under `ref`'s lock, taking it and giving it back - or under the lock this
   * session already holds, in which case nothing is taken and nothing is given back. null when
   * the device is not free, which is the caller's "not written".
   *
   * @param {Object} ref @param {() => Promise<*>} work @returns {Promise<*>}
   */
  async withDeviceLock(ref, work) {
    const open = this._connection;
    if (open == null) {
      console.warn(`net backend: no session to write ${refText(ref)} on - nothing is sent`);
      return null;
    }
    if (this.holdsLock(ref)) return work();
    let refusal;
    try {
      refusal = await this.acquireDevice(ref);
    } catch (e) {
      console.warn(`net backend: taking ${refText(ref)} failed: ${e.message}`);
      return null;
    }
    if (refusal != null) {
      console.warn(`net backend: ${refText(ref)} is not free - ${refusal.message}`);
      return null;
    }
    try {
      return await work();
    } finally {
      // Given back even when the write blew up: a lock left behind is a device no other client
      // can ever take again.
      await this.releaseDevice(ref);
    }
  }

  /**
   * Runs `work` under a lock on ANY ONE device of the selected backend - spec 4.6's weaker rule
   * for a BACKEND's own settings ("requires the QA40x lock (either direction)"), whose messages
   * name no device (faithful port of NetDeviceManager.withDeviceLock(Supplier)).
   *
   * A settings panel holds nothing of its own, so every such write used to come back NOT_LOCKED
   * and the operator saw a panel that changed nothing. Taking the lock for the one request is the
   * fix, and it is also what the rule is FOR: it fails honestly while ANOTHER client is measuring
   * on that analyzer instead of moving its attenuator underneath.
   *
   * A lock this session ALREADY holds is used and NOT given back: the modules keep streaming
   * while Preferences is open, the server cannot tell a repeat acquire by the same session from a
   * fresh one, and releasing afterwards would hit spec 4.3's "also closes any open stream" - the
   * running capture torn down under the panes with nothing to say why. Which device it is on does
   * not matter; only that this connection holds one.
   *
   * @param {() => Promise<*>} work @returns {Promise<*>} work's answer, or null when nothing could
   *   be taken - the caller's "not written"
   */
  async withBackendLock(work) {
    if (this._connection == null) {
      console.warn(`net backend: no session to write ${this._remoteBackend} on - nothing is sent`);
      return null;
    }
    if (this._held.length > 0) return work();
    // Every device of the backend in turn: a bench with several inputs has no reason to refuse a
    // write because the FIRST of them happens to be somebody else's.
    for (const ref of [...this._listDevices(true), ...this._listDevices(false)]) {
      let refusal;
      try {
        refusal = await this.acquireDevice(ref);
      } catch (e) {
        // The session itself is in trouble; the next device would fail exactly the same way.
        console.warn(`net backend: taking ${refText(ref)} failed: ${e.message}`);
        return null;
      }
      if (refusal != null) continue;               // not free - try the next device
      try {
        return await work();
      } finally {
        await this.releaseDevice(ref);
      }
    }
    console.warn(`net backend: no device of ${this._remoteBackend} could be taken, so its `
      + 'settings cannot be written');
    return null;
  }

  // ---------------------------------------------------------------------------
  // The selected backend's own requests (spec 4.6) - Java NetBenchUi.call/callLocked
  // ---------------------------------------------------------------------------

  /**
   * One of the selected backend's own requests over this session - the QA40x extension of spec
   * 4.6 is what a caller sends here (faithful port of NetBenchUi.call).
   *
   * It refuses anything but the backend this session is ROUTED at: a panel left open across a
   * disconnect, or one editing another bench, must not have its reads answered by whichever
   * server happens to be on the wire now. (Java also compares the selection's serverId; the web
   * has one session at a time and its selection value carries only the backend name, so being
   * routed at that backend IS the whole check.)
   *
   * A refusal or a dead session is null rather than a throw, because the caller is a settings
   * panel: an unreachable bench is a value it cannot show, not an error it can fix.
   *
   * @param {string} backendValue the selected backend, in the combo's "net:<backend>" spelling
   * @param {string} request the wire name of the message (a MessageType value)
   * @param {Object} [fields] the message's own fields
   * @returns {Promise<?Object>} the answer's data, or null
   */
  async call(backendValue, request, fields = {}) {
    const open = this._connection;
    if (open == null || !this._routedAt(backendValue)) return null;
    if (!isKnownMessage(request)) {
      console.warn(`net bench: '${request}' is not a message this build knows`);
      return null;
    }
    try {
      const message = Object.assign(open.newRequest(request), fields);
      const answer = await open.uiRequest(message);
      if (answer.ok === false) {
        console.warn(`net bench: the server refused ${request}:`, answer.error);
        return null;
      }
      return answer.data || {};
    } catch (e) {
      console.warn(`net bench: ${request} failed: ${e.message}`);
      return null;
    }
  }

  /**
   * The same round trip with the bench's device HELD for its duration - what every write the
   * protocol gates on a lock needs (faithful port of NetBenchUi.callLocked).
   *
   * TWO LOCK RULES, told apart by the request itself. Spec 4.6 gates a BACKEND's own settings
   * (the QA40x ranges, its front-panel port) on the analyzer's lock in EITHER direction - those
   * messages name no device, and any one device of the backend satisfies them. Spec 4.3's
   * device.setCalibration / device.setCard name a device ref and are checked against the lock on
   * exactly that (backend, index, direction), because they change what one device MEANS. Sent the
   * other way round, a DAC calibration would go out under whatever input this client happened to
   * hold and come back NOT_LOCKED - a value the operator watched succeed and the bench never
   * stored.
   */
  async callLocked(backendValue, request, fields = {}) {
    if (!this._routedAt(backendValue)) {
      console.warn(`net bench: ${backendValue} is not the selected backend, so ${request} `
        + 'is not sent');
      return null;
    }
    if (!this._namesADevice(fields)) {
      return this.withBackendLock(() => this.call(backendValue, request, fields));
    }
    const device = this._deviceOf(fields);
    if (device == null) {
      // The ref names a device this bench no longer offers. Falling back to the any-device path
      // would take somebody else's device for a request the server can only answer DEVICE_STALE.
      console.warn(`net bench: ${request} names a device that is not on ${this._remoteBackend}, `
        + 'so it is not sent');
      return null;
    }
    return this.withDeviceLock(device, () => this.call(backendValue, request, fields));
  }

  /** Whether a combo/preference value names the backend this session is routed at - the one
   *  selection whose requests this manager may answer. */
  _routedAt(backendValue) {
    const backend = remoteBackendOf(backendValue);
    return backend != null && backend === this._remoteBackend;
  }

  /** Whether a request carries the device ref of spec 4.3 - the three fields that identify a
   *  device WITHIN the selected backend. */
  _namesADevice(fields) {
    return typeof fields[NetFields.INDEX] === 'number'
      && typeof fields[NetFields.INPUT] === 'boolean'
      && typeof fields[NetFields.NAME] === 'string';
  }

  /** The CATALOGUE's own ref for the device a request names, or null when the bench does not
   *  offer it. Looked up rather than rebuilt from the fields: a ref's identity includes its
   *  description and vendor, which the request does not carry, so a hand-built one would not
   *  match the lock register - and this session would re-acquire a device it already holds, then
   *  release it, tearing down the capture running on it. */
  _deviceOf(fields) {
    const index = fields[NetFields.INDEX];
    const name = fields[NetFields.NAME];
    return this._listDevices(fields[NetFields.INPUT] === true)
      .find((ref) => ref.index === index && ref.name === name) || null;
  }

  async _releaseHeld() {
    for (const ref of [...this._held]) await this.releaseDevice(ref);
  }

  // ---------------------------------------------------------------------------
  // The catalogue
  // ---------------------------------------------------------------------------

  _listDevices(input) {
    return this._devices.filter((d) => d.ref.isInput === input).map((d) => d.ref);
  }

  _find(device) {
    return this._devices.find((d) => sameNetDevice(d.ref, device)) || null;
  }

  /** Rebuilds the catalogue from a `backends` array (spec 4.3), keeping only the backend this
   *  manager was pointed at - and reporting every device this session HOLDS that the new list
   *  no longer offers. */
  _cache(backends) {
    const selected = this._remoteBackend;
    if (selected == null || !Array.isArray(backends)) return;
    const found = [];
    for (const backend of backends) {
      if (backend && backend[NetFields.BACKEND] === selected) {
        found.push(...this._devicesOf(backend, selected));
      }
    }
    this._devices = found;
    this._reportHeldButGone(found, selected);
  }

  /**
   * Reports every device this session holds that the new catalogue no longer offers, and
   * forgets its lock. Only the SELECTED backend's own refs are judged: a catalogue describes
   * one backend, so a held ref of a different one is simply not described here.
   *
   * The lock goes out of the register but is NOT released on the wire: the device it named
   * does not exist any more, so the release could only be answered DEVICE_STALE. Forgetting it
   * matters all the same - withDeviceLock would otherwise go on believing this session holds
   * a lock, and write a backend's settings under it.
   */
  _reportHeldButGone(catalogue, selected) {
    for (const ref of [...this._held]) {
      if (ref.remoteBackend !== selected) continue;
      if (catalogue.some((d) => sameNetDevice(d.ref, ref))) continue;
      this._held = this._held.filter((r) => !sameNetDevice(r, ref));
      this._deviceGone(ref);
    }
  }

  /** The devices of ONE backend entry of spec 4.3 - the shape backend.select answers with and
   *  the shape each element of a devices.list array has, so both paths read it the same way. */
  _devicesOf(backend, name) {
    const rows = (backend && backend[NetFields.DEVICES]) || [];
    return rows.map((device) => this._read(device, name));
  }

  /** One device object of spec 4.3, whole. */
  _read(device, backend) {
    const ref = makeNetDeviceRef({
      index: Number(device[NetFields.INDEX]) || 0,
      name: device[NetFields.NAME] || '',
      description: device[NetFields.DESCRIPTION] || '',
      vendor: device[NetFields.VENDOR] || '',
      isInput: device[NetFields.INPUT] === true,
      remoteBackend: backend,
      calibration: this._calibrationOf(device),
      // JSON null, an absent field (a server older than v1.1) and a blank all mean the same:
      // nobody has chosen a card there.
      boundCard: device[NetFields.CARD] ? device[NetFields.CARD] : null,
    });
    // `sampleRate` / `bits` are the field names Qa40xAudioFormat uses (the web's stand-in for
    // javax.sound.sampled.AudioFormat) - ONE format shape across the managers, so the
    // Preferences dialog reads a bench's formats through exactly the code that reads the
    // analyzer's, and offers the rates and widths the SERVER declared.
    const formats = ((device[NetFields.FORMATS]) || []).map((f) => ({
      sampleRate: Number(f[NetFields.RATE]) || 0,
      bits: Number(f[NetFields.BITS]) || 0,
      channels: Number(f[NetFields.CHANNELS]) || STEREO,
    }));
    const lock = device[NetFields.LOCK];
    return {
      ref,
      formats,
      hasBitDepth: device[NetFields.HAS_BIT_DEPTH] === true,
      lockedBy: (lock != null && typeof lock === 'object') ? (lock[NetFields.BY] || null) : null,
    };
  }

  /**
   * Spec 4.3's `cal`. Null-safe by the SHAPE of the answer, not by a version check: JSON null
   * (no card), an absent field (a server older than v1.1) and a value that is not an object
   * all mean the same thing, and none of them may be read as a full scale of zero - which
   * would make every measurement on that device infinite. An object whose two numbers are
   * missing or not above zero is the same answer for the same reason.
   */
  _calibrationOf(device) {
    const cal = device[NetFields.CAL];
    if (cal == null || typeof cal !== 'object') return null;
    const left = Number(cal[NetFields.FS_RMS_LEFT]);
    const right = Number(cal[NetFields.FS_RMS_RIGHT]);
    if (!(left > 0) || !(right > 0)) {
      console.warn(`net backend: '${device[NetFields.NAME]}' came with an unusable calibration `
        + `(${left} / ${right} Vrms) - falling back to the local defaults`);
      return null;
    }
    return { fsRmsLeft: left, fsRmsRight: right };
  }

  _requireConnection() {
    const open = this._connection;
    if (open == null) {
      throw new Error('no Phonalyser server is connected, so there is no remote device to open');
    }
    return open;
  }

  /** The sentence a failed dial leaves on screen. A browser blocks ws:// from an https page
   *  BEFORE it dials (spec §3) - the one connect failure with an explanation worth giving, and
   *  the one an operator cannot otherwise diagnose from "connection failed". */
  _connectFailureText(server, error) {
    if (isMixedContentBlock(server)) return t('net.error.mixedContent', `${server.host}:${server.port}`);
    // The Java bundle already carries this exact sentence ({0} = host:port, {1} = the reason),
    // translated into all 31 locales - so the web reuses the key instead of inventing a twin the
    // i18n round would have had to translate a second time.
    return t('net.error.connect', `${server.host}:${server.port}`, error.message || '');
  }
}

/** Whether the page's own scheme forbids the ws:// this entry needs: an https-served page may
 *  only open wss://, or ws:// to localhost (browser rule, spec §3). The net backend is still
 *  offered - the supported web route is loading the app from the server itself. */
export function isMixedContentBlock(server) {
  if (typeof location === 'undefined' || location.protocol !== 'https:') return false;
  const host = (server && server.host) || '';
  // The server that SERVED this https page is dialled wss:// (server-prober.wsUrlOf), so it is
  // not mixed content and must never be explained as such - that would be the one bench an
  // https page CAN reach being told it cannot.
  if (isPageOrigin(host, server && server.port)) return false;
  return !(host === 'localhost' || host === '127.0.0.1' || host === '::1');
}
