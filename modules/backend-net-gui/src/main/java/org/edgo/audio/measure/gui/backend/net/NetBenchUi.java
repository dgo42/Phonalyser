/*
 * Phonalyser - precision audio measurement workbench.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.edgo.audio.measure.gui.backend.net;

import java.net.InetAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;

import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.common.GuiUtil;
import org.edgo.audio.measure.gui.common.RemoteBackendUi;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.net.client.NetCloseReason;
import org.edgo.audio.measure.net.client.NetConnection;
import org.edgo.audio.measure.net.client.NetDeviceManager;
import org.edgo.audio.measure.net.client.NetDeviceRef;
import org.edgo.audio.measure.net.client.NetFaultListener;
import org.edgo.audio.measure.net.client.NetPreferences;
import org.edgo.audio.measure.net.client.NetServerEntry;
import org.edgo.audio.measure.net.client.ScheduledTicker;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetProto;
import org.edgo.audio.measure.net.proto.NetMessage;
import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.preferences.BackendPrefs;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.AudioDeviceManager;
import org.edgo.audio.measure.sound.DeviceRef;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

/**
 * The net bridge's UI side: the one owner of the session with a Phonalyser
 * server, and the only thing the Preferences dialog has to know about remote
 * benches.
 *
 * <p><b>What it owns.</b>  The live {@link NetConnection}, which server it
 * reaches, that server's backends as {@code backend.list} named them, the
 * remembered-server settings block, and the routing of {@link NetDeviceManager}
 * at whichever backend the operator picked.  One instance per process, because
 * every one of those is one per process: the service loader builds it once (see
 * {@code RemoteBackendRegistry}) and the connection then outlives every dialog
 * that opens over it.
 *
 * <p><b>It is also where the SESSION's death reaches the operator.</b>  The
 * manager reports a bench's faults to a {@link NetFaultListener}, and this is
 * that listener.  The measurements themselves stop bottom-up, the same way they
 * do for a local card: a remote capture death finishes the ring buffer (see
 * {@code NetPcmCapture}) and the panes consult their readers; the remote
 * generator's calls throw.  What is left for this listener is the one report no
 * stream owns - the connection itself ended - which this UI, as the session's
 * owner, shows once.
 */
@Log4j2
public final class NetBenchUi implements RemoteBackendUi, NetFaultListener {

    /** How long a connect attempt waits for the socket and the {@code hello}.
     *  Short on purpose - but no longer because of WHERE it runs: the dial is off
     *  the display thread now, on the scheduler for a retry and on a worker for
     *  the operator's own connect.  It stays short because an operator waiting on
     *  a bench wants a fast NO: three seconds of "please wait" and then a reason
     *  is a better answer than ten seconds of the same, and the retry that follows
     *  costs nothing. */
    private static final long CONNECT_TIMEOUT_MS = 3_000;

    /** How long the client waits between attempts to get a session back that
     *  ended without the operator asking it to - long enough that a server which
     *  is down costs nothing, short enough that a restart is over before the
     *  operator has finished reaching for the mouse. */
    private static final long RECONNECT_DELAY_MS = 5_000;

    /** Spec 4.1's {@code client} field, plus the build version when the jar
     *  carries one - what the server logs and what a {@code DEVICE_LOCKED}
     *  names on somebody else's screen. */
    private static final String CLIENT_APP = "Phonalyser";
    private static final String KEEPALIVE_THREAD = "net-client-keepalive";
    /** The i18n key of the warning the analyzers show for a server-side loss
     *  (spec 5's GAP), carried as the payload of {@link Events#FFT_CAPTURE_RESYNC}
     *  exactly as the FFT's own re-sync reasons are. */
    private static final String GAP_BANNER_KEY = "fft.warning.netGap";

    /** Held as a field, not as a method reference at the call site: the same
     *  object has to be handed back to take the subscription away again. */
    private final Consumer<NetCloseReason> closeListener = this::onConnectionClosed;
    /** One thread for every connection's 500 ms keepalive - the callback is a
     *  short JSON write, and there is at most one connection anyway. */
    private final ScheduledExecutorService keepalive = Executors.newSingleThreadScheduledExecutor(
            task -> {
                Thread thread = new Thread(task, KEEPALIVE_THREAD);
                thread.setDaemon(true);
                return thread;
            });
    /** The remembered servers and the proxy to reach them through - registered
     *  here so the block is restored from preferences.yaml the moment this UI
     *  exists, which is before the operator can ask to see the list.  Exposed
     *  because the servers dialog EDITS it: the proxy row writes the edit copy
     *  that this object's own dial paths then read. */
    @Getter
    private final NetPreferences preferences = new NetPreferences();
    @Getter
    private final NetServerList servers = new NetServerList(preferences);
    private final MessageBus bus = MessageBus.instance();
    /** The bridge's one JSON mapper, shared by the connection and by discovery.
     *  Built here rather than injected because the service loader hands this
     *  type a no-argument constructor and there is nobody above it to ask. */
    @Getter
    private final JsonCodec codec = new JsonCodec();

    /** The session, or null while no server is connected.  Volatile: opened and
     *  dropped on the UI thread, read by the connection's reader thread. */
    private volatile NetConnection connection;
    /** The selection {@code backend.select} last committed on the live session,
     *  or null.  What makes {@link #select} idempotent: a re-select of this key
     *  must not run the commit again, because pointing the manager anew tears
     *  down the generator lane a mere Preferences OK has to leave alone. */
    private volatile BackendKey committed;
    /** The server behind {@link #connection}, as its {@code hello} confirmed it. */
    @Getter
    private volatile NetServerEntry connectedServer;
    /** What the backend combo offers for that server; replaced whole. */
    private volatile List<Entry> entries = List.of();
    /** The pending auto-reconnect, or null when none is armed.  A session that
     *  ended by itself comes back by itself (see {@link #armReconnect}); every
     *  manual action on the session cancels it, because the operator's move is
     *  the newer intention. */
    private volatile ScheduledFuture<?> reconnect;
    /** Which retry generation is current.  {@link #cancelReconnect()} bumps it,
     *  and an attempt that finds it changed undoes itself - a future's
     *  {@code cancel} cannot reach an attempt that has already begun, and an
     *  attempt that connected after the operator said Disconnect would hand them
     *  back the session they had just let go of. */
    private final AtomicLong reconnectGeneration = new AtomicLong();
    /** A display to marshal onto, remembered the first time this UI is handed a
     *  widget.  Null in a headless run - see {@link #onUiThread}. */
    private volatile Display uiDisplay;
    /** Where the state half runs when there is no display to run it on.  Null in
     *  production, where {@link #uiDisplay} answers instead; a test sets it to
     *  stand in for the display it cannot create, which is the only way to prove
     *  that the BLOCKING half stays off that thread and the state half lands on
     *  it (see {@link #onUiThread}). */
    @Setter(AccessLevel.PACKAGE)
    private volatile Executor uiExecutor;

    /** Public and no-argument for the service loader (see {@link RemoteBackendUi}). */
    public NetBenchUi() {
        Preferences.instance().registerCustomPreferences(preferences);
    }

    // -------------------------------------------------------------------------
    // The contract the Preferences dialog uses
    // -------------------------------------------------------------------------

    @Override
    public void openServerList(Shell parent) {
        // The one place this service-loaded singleton is ever handed a widget, and
        // therefore where it learns which thread its state belongs to.  A session
        // cannot exist without having come through here - connecting IS this
        // dialog - so anything that later has a session to lose has a display to
        // marshal onto.
        uiDisplay = parent.getDisplay();
        new NetServerListDialog(parent, this).open();
    }

    @Override
    public List<Entry> entries() {
        return entries;
    }

    /**
     * Spec 4.3's {@code backend.select}: the session commits to one of the
     * server's backends, and the device manager is pointed at it - which also
     * drops the catalogue it had cached, so the combos fill from the bench that
     * was just chosen rather than from the one the operator left.
     *
     * <p><b>The answer IS the device list.</b>  Spec 4.3 shapes the response as
     * "the selected backend's full entry in {@code devices.list} shape ... one
     * round-trip fills the device combos", and it is handed straight to the
     * manager.  It used to be dropped and the manager left to fetch its own
     * {@code devices.list} on first use; the gap between the two was a window an
     * {@code ev.devices.changed} could land in, and a server still finishing its
     * first enumeration broadcasts an empty one - which is how a perfectly good
     * selection ended up with empty device combos until it was made a second
     * time.
     */
    @Override
    public boolean select(BackendKey key) {
        NetConnection open = connection;
        NetServerEntry server = connectedServer;
        if (open == null || !open.isOpen() || key == null || !key.remote()
                || server == null || !server.serverId().equals(key.serverId())) {
            return false;
        }
        NetDeviceManager manager = manager();
        if (manager == null) {
            return false;
        }
        if (key.equals(committed) && manager.getConnection() == open) {
            // Already committed on this session: re-affirming it (the Preferences
            // OK does, every time) must not re-point the manager - that starts
            // with a disconnect, and the disconnect closes the generator lane the
            // operator is listening to.  The catalogue is already event-fresh.
            //
            // "On THIS session" is the second half of the question, and it is not
            // rhetorical: a switch to a local backend now tears the manager's
            // routing down (AudioBackend.setActive -> NetDeviceManager.shutdown),
            // so the selection can be committed while the manager holds no session
            // at all - and returning true there would hand the operator a bench
            // that answers nothing.
            return true;
        }
        NetMessage answer;
        try {
            answer = open.uiRequest(open.newRequest(MessageType.BACKEND_SELECT)
                    .put(NetFields.BACKEND, key.type().name()));
        } catch (RuntimeException e) {
            if (log.isWarnEnabled()) {
                log.warn("net bench: backend.select {} failed: {}", key.type(), e.toString());
            }
            return false;
        }
        if (!answer.isOk()) {
            if (log.isWarnEnabled()) {
                log.warn("net bench: the server refused backend.select {}: {}",
                        key.type(), answer.getError());
            }
            return false;
        }
        manager.setFaultListener(this);
        manager.connect(open, answer.getData());
        committed = key;
        return true;
    }

    /**
     * The staged twin of {@link #select} (see {@link RemoteBackendUi#preview}):
     * fills the manager's catalogue for {@code key} from a plain
     * {@code devices.list} read, so the Preferences dialog can show a backend's
     * devices without committing anything - no {@code backend.select}, no
     * re-pointing, and therefore no teardown of whatever is streaming.
     */
    @Override
    public boolean preview(BackendKey key) {
        NetConnection open = connection;
        NetServerEntry server = connectedServer;
        if (open == null || !open.isOpen() || key == null || !key.remote()
                || server == null || !server.serverId().equals(key.serverId())) {
            return false;
        }
        NetDeviceManager manager = manager();
        if (manager == null) {
            return false;
        }
        NetMessage answer;
        try {
            answer = open.uiRequest(open.newRequest(MessageType.DEVICES_LIST));
        } catch (RuntimeException e) {
            if (log.isWarnEnabled()) {
                log.warn("net bench: devices.list for {} failed: {}", key.type(),
                        e.toString());
            }
            return false;
        }
        if (!answer.isOk()) {
            if (log.isWarnEnabled()) {
                log.warn("net bench: the server refused devices.list: {}", answer.getError());
            }
            return false;
        }
        String wanted = key.type().name();
        for (JsonNode backend : answer.getData().path(NetFields.BACKENDS)) {
            if (wanted.equals(backend.path(NetFields.BACKEND).asText())) {
                manager.setFaultListener(this);
                manager.preview(backend);
                return true;
            }
        }
        if (log.isWarnEnabled()) {
            log.warn("net bench: '{}' serves no {} backend to preview", server.name(),
                    wanted);
        }
        return false;
    }

    /**
     * Spec 4.3's per-device lock, as the bench last published it: the name of the
     * client holding {@code device}, or null when it is free, not remote, or this
     * build ships no net backend.
     *
     * <p>A lock this client holds itself is answered as free - the operator is
     * being shown which devices somebody ELSE is using, and their own running
     * scope is not news.  The bench names holders by client name (spec 4.1's
     * {@code name}), which is this machine's, so the comparison is the same one
     * the server made when it wrote the field.
     */
    @Override
    public String lockedBy(DeviceRef device) {
        NetDeviceManager manager = manager();
        if (manager == null) {
            return null;
        }
        String by = manager.lockedBy(device);
        return by == null || by.equals(clientName()) ? null : by;
    }

    /**
     * One of the selected backend's own requests over this session - the QA40x
     * extension of spec 4.6 is what a caller sends here.
     *
     * <p>It refuses anything but the CONNECTED server's own selection: a panel
     * left open across a disconnect, or one editing another bench, must not have
     * its reads answered by whichever server happens to be on the wire now.  A
     * refusal or a dead session is a null rather than a throw, because the caller
     * is a settings panel and an unreachable bench is a value it cannot show, not
     * an error it can fix.
     */
    @Override
    public Map<String, Object> call(BackendKey selection, String request,
            Map<String, Object> fields) {
        NetConnection open = connection;
        NetServerEntry server = connectedServer;
        if (open == null || !open.isOpen() || selection == null || !selection.remote()
                || server == null || !server.serverId().equals(selection.serverId())) {
            return null;
        }
        MessageType type = MessageType.fromWire(request);
        if (type == MessageType.UNKNOWN) {
            if (log.isWarnEnabled()) {
                log.warn("net bench: '{}' is not a message this build knows", request);
            }
            return null;
        }
        try {
            NetMessage message = open.newRequest(type);
            for (Map.Entry<String, Object> field : fields.entrySet()) {
                message.put(field.getKey(), codec.toNode(field.getValue()));
            }
            NetMessage answer = open.uiRequest(message);
            if (!answer.isOk()) {
                if (log.isWarnEnabled()) {
                    log.warn("net bench: the server refused {}: {}", request,
                            answer.getError());
                }
                return null;
            }
            return codec.toMap(answer.getData());
        } catch (RuntimeException e) {
            if (log.isWarnEnabled()) {
                log.warn("net bench: {} failed: {}", request, e.toString());
            }
            return null;
        }
    }

    /**
     * The same round trip with the bench's device held for its duration - what
     * every write the protocol gates on a lock needs.
     *
     * <p><b>Two lock rules, told apart by the request itself.</b>  Spec 4.6 gates
     * a BACKEND's own settings (the QA40x ranges, its front-panel port) on "the
     * QA40x lock (either direction)" - those messages name no device, and any one
     * device of the backend satisfies them.  Spec 4.3's
     * {@code device.setCalibration} names a device ref and is checked against the
     * lock on exactly that {@code (backend, index, direction)}, because it changes
     * what one device MEANS.  A request carrying the ref fields is therefore
     * locked on the device it names; everything else keeps the backend-wide path.
     * Sent the other way round, a DAC calibration would go out under whatever
     * input the client happened to hold and come back {@code NOT_LOCKED} - a
     * value the operator watched succeed and the bench never stored.
     *
     * <p>It is refused for a selection the device manager is not routed at: the
     * lock is taken on a device of the backend {@code backend.select} last
     * committed to, so a panel left open on another of the server's backends
     * would take a lock that does not cover what it is writing - and be answered
     * {@code NOT_LOCKED} anyway, after moving somebody else's device.
     */
    @Override
    public Map<String, Object> callLocked(BackendKey selection, String request,
            Map<String, Object> fields) {
        NetDeviceManager manager = manager();
        if (manager == null || selection == null
                || selection.type() != manager.remoteBackendType()) {
            if (log.isWarnEnabled()) {
                log.warn("net bench: {} is not the selected backend, so {} is not sent",
                        selection == null ? "null" : selection.key(), request);
            }
            return null;
        }
        if (!namesADevice(fields)) {
            return manager.withDeviceLock(() -> call(selection, request, fields));
        }
        NetDeviceRef device = deviceOf(manager, fields);
        if (device == null) {
            // The ref names a device this bench no longer offers.  Falling back to
            // the any-device path would take somebody else's device for a request
            // the server can only answer DEVICE_STALE.
            if (log.isWarnEnabled()) {
                log.warn("net bench: {} names a device that is not on {}, so it is "
                        + "not sent", request, manager.getRemoteBackend());
            }
            return null;
        }
        return manager.withDeviceLock(device, () -> call(selection, request, fields));
    }

    /** Whether a request carries the device ref of spec 4.3 - the four fields a
     *  per-device call names its device by, of which the three below are the ones
     *  that identify it within the selected backend. */
    private boolean namesADevice(Map<String, Object> fields) {
        return fields.get(NetFields.INDEX) instanceof Number
                && fields.get(NetFields.INPUT) instanceof Boolean
                && fields.get(NetFields.NAME) instanceof String;
    }

    /**
     * The catalogue's own ref for the device a request names, or null when the
     * bench does not offer it.
     *
     * <p>Looked up rather than rebuilt from the fields: a ref's identity includes
     * its description and vendor, which the request does not carry, so a
     * hand-built one would not match the entry in the lock register and this
     * session would re-acquire a device it already holds - and then release it,
     * closing the capture running on it (spec 4.3).
     */
    private NetDeviceRef deviceOf(NetDeviceManager manager, Map<String, Object> fields) {
        int index = ((Number) fields.get(NetFields.INDEX)).intValue();
        boolean input = (Boolean) fields.get(NetFields.INPUT);
        String name = (String) fields.get(NetFields.NAME);
        List<DeviceRef> devices = input
                ? manager.listInputDevices() : manager.listOutputDevices();
        for (DeviceRef candidate : devices) {
            if (candidate instanceof NetDeviceRef ref && ref.index() == index
                    && name.equals(ref.name())) {
                return ref;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // The session
    // -------------------------------------------------------------------------

    /**
     * Connects to {@code server} and reads its backends, replacing whatever
     * session was open before.
     *
     * @return null when the session is up, else the reason to show the operator
     */
    public String connect(NetServerEntry server) {
        // An operator's move: the pending retry is stale from here on, and an
        // attempt that is already running must not be allowed to hand back the
        // session this call is about to replace.  The retry's own attempt does
        // NOT come through here - it opens the session directly, so that its own
        // connect cannot read as somebody cancelling it.
        cancelReconnect();
        return openSession(server);
    }

    /** The session open itself, without the cancel that makes a connect an
     *  operator's move.  See {@link #connect} and {@link #attemptReconnect}.
     *
     *  <p>Its two halves are separate methods because the retry runs them on
     *  DIFFERENT threads (see {@link #dial}); the operator's connect, already on
     *  the display thread, simply runs them both here. */
    private String openSession(NetServerEntry server) {
        closeSession();
        // The operator pressed Connect: the proxy that applies is the one ON
        // SCREEN, which is the edit copy - they may have typed it a moment ago
        // in the same dialog and not yet pressed OK anywhere.
        Dialled dialled = dial(server, preferences.proxyEdit());
        if (dialled.failure() != null) {
            return dialled.failure();
        }
        commitSession(dialled);
        return null;
    }

    /**
     * The BLOCKING half: open the socket, complete the handshake of spec 1, and
     * ask the bench what it serves.
     *
     * <p><b>It touches no state of this object</b>, which is what lets it run
     * anywhere - and the reconnect runs it on the keepalive scheduler precisely
     * because it blocks: a bench that is switched off refuses in milliseconds,
     * but one that is black-holed (cable out, firewall dropping) answers nothing
     * for the whole {@link #CONNECT_TIMEOUT_MS}, and doing that on the display
     * thread would freeze the workbench for three seconds out of every five for
     * as long as the bench stayed away.
     *
     * @return what was dialled, or the reason to show the operator
     */
    private Dialled dial(NetServerEntry server, Proxy proxy) {
        NetConnection open = new NetConnection(wsUri(server), clientName(), clientApp(),
                codec, new ScheduledTicker(keepalive), proxy);
        try {
            open.open(CONNECT_TIMEOUT_MS);
        } catch (RuntimeException e) {
            // Closed rather than dropped: a refused attempt can still leave the
            // library's reader thread behind, and the operator will try again.
            open.close(NetCloseReason.TRANSPORT_CLOSED);
            if (log.isWarnEnabled()) {
                log.warn("net bench: connecting to {} failed: {}", server.host(),
                        e.toString());
            }
            return new Dialled(null, null, List.of(), connectFailureText(server, e));
        }
        // The id is the server's, never the one we happened to have remembered:
        // spec 2.1 keys a remembered bench on its installation UUID, and a
        // manual entry typed in by address only learns it here.
        String serverId = open.getServerId() == null ? server.serverId() : open.getServerId();
        String name = open.getServerName() == null ? server.name() : open.getServerName();
        NetServerEntry confirmed = new NetServerEntry(serverId, name, server.host(),
                server.port(), server.manual());
        // On this side of the cut as well: it is another round trip, and the
        // entries it answers are a value until the commit adopts them.
        return new Dialled(open, confirmed, readBackends(open, confirmed), null);
    }

    /**
     * The STATE half: everything this object and the preferences remember about
     * the session that was just dialled.  Display thread - the maps it writes are
     * the ones the servers dialog reads and iterates.
     */
    private void commitSession(Dialled dialled) {
        NetConnection open = dialled.open();
        open.addCloseListener(closeListener);
        connection = open;
        connectedServer = dialled.confirmed();
        // Live, not staged: staging exists so a list EDIT can be cancelled, and a
        // session that is already up is not an edit.  Remembering it only in the
        // edit copy would let the Preferences dialog's Cancel take the bench being
        // measured on out of the list - with no beacon to put a routed one back.
        preferences.putServer(dialled.confirmed());
        preferences.setLastConnectedEdit(dialled.confirmed().serverId());
        entries = dialled.entries();
    }

    /** What {@link #dial} brings back for {@link #commitSession} to adopt: a
     *  session that is open but not yet anybody's, the server as its own
     *  {@code hello} confirmed it, and the combo entries it answered - or, when
     *  the dial failed, only the reason. */
    private record Dialled(NetConnection open, NetServerEntry confirmed,
            List<Entry> entries, String failure) {
    }

    /**
     * Asks whether a Phonalyser server answers at {@code candidate}'s address
     * and, if one does, remembers it under the id and name IT reports - which is
     * the only way a typed-in address can become a remembered server, since spec
     * 2.1 keys the list on the installation UUID and an address cannot supply
     * one.
     *
     * <p>The probe session is closed again immediately: validating an address
     * must not take over from the bench the operator is currently measuring on.
     *
     * @return null when the server was remembered, else the reason to show
     */
    public String probe(NetServerEntry candidate) {
        // A dialog path like Connect: the address AND the proxy being validated
        // are both what the operator has on screen (see openSession).
        NetConnection open = new NetConnection(wsUri(candidate), clientName(), clientApp(),
                codec, new ScheduledTicker(keepalive), preferences.proxyEdit());
        try {
            open.open(CONNECT_TIMEOUT_MS);
            String serverId = open.getServerId();
            if (serverId == null || serverId.isEmpty()) {
                return I18n.t("net.servers.error.notAServer");
            }
            String name = open.getServerName() == null ? candidate.host() : open.getServerName();
            servers.remember(new NetServerEntry(serverId, name, candidate.host(),
                    candidate.port(), true));
            return null;
        } catch (RuntimeException e) {
            if (log.isWarnEnabled()) {
                log.warn("net bench: probing {} failed: {}", candidate.host(), e.toString());
            }
            return connectFailureText(candidate, e);
        } finally {
            open.close(NetCloseReason.BYE);
        }
    }

    /** The sentence a failed dial shows.  A dead address is the one failure with
     *  a translation of its own - nothing answered, so there is no diagnostic
     *  string worth quoting; every other failure keeps the server's or the
     *  library's own words inside the translated frame (see {@link #reason}). */
    private String connectFailureText(NetServerEntry server, RuntimeException failure) {
        if (failure instanceof NetConnection.NoAnswerException) {
            return I18n.t("net.servers.error.noAnswer", server.host() + ":" + server.port());
        }
        return I18n.t("net.error.connect", server.host(), reason(failure));
    }

    /** The technical half of a failure sentence - what the session itself said,
     *  or the exception's type when it said nothing.  The sentence AROUND it is
     *  translated; this part is the same diagnostic string the log carries,
     *  because "connection refused" is what the operator has to act on and no
     *  translation of it exists to look up. */
    private String reason(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isEmpty() ? failure.toString() : message;
    }

    /**
     * Ends the session at the operator's request.
     *
     * <p>It goes out through the SAME close listener every other end uses, so the
     * modules are stopped and the combo re-composed here exactly as they are when
     * a bench dies on its own.  Spec 4.1's "stop all modules" is about the session
     * ending, not about how it ended: a scope left showing "recording" over a
     * stream that will never deliver another frame is the same lie whether the
     * bench was switched off or the operator pressed Disconnect.
     *
     * <p>The device manager is told FIRST, while the socket is still open, so its
     * generator lane and its locks are given back on the wire instead of being
     * abandoned to the server's teardown.
     */
    public void disconnect() {
        // The operator letting go: a pending retry dies with it, and one already
        // running is told its result is no longer wanted.
        cancelReconnect();
        closeSession();
    }

    /** The teardown itself, without the cancel that makes a disconnect the
     *  operator's decision - {@link #openSession} replaces a session with it. */
    private void closeSession() {
        NetConnection open = connection;
        NetDeviceManager manager = manager();
        if (manager != null) {
            manager.disconnect();
        }
        connection = null;
        connectedServer = null;
        committed = null;
        entries = List.of();
        if (open != null) {
            open.close(NetCloseReason.BYE);
        }
    }

    // -------------------------------------------------------------------------
    // Getting a session back that ended by itself
    // -------------------------------------------------------------------------

    /**
     * Arms the retry that brings {@code server} back.
     *
     * <p><b>Why this exists.</b>  Restart the server and the client stayed dead
     * until somebody opened the servers dialog and pressed Connect - even though
     * everything worked the moment they did.  Nothing was broken; nothing was
     * TRYING.  A bench that is switched off and on
     * again, or a server the operator restarts to pick up a change, is the
     * ordinary case, and the operator noticing before the software does is the
     * wrong way round.
     *
     * <p><b>What it deliberately does not do.</b>  It does not restart a
     * measurement.  The modules stopped themselves when the session died (each
     * open stream fails on its own path), and starting them again is the
     * operator's move - a scope that resumed by itself would be a bench that
     * started measuring while nobody was looking at it.  The goal is narrower:
     * when they DO press play, it just works.
     *
     * <p>One shot at a time, re-armed by the attempt itself, on the keepalive
     * scheduler that already exists - a bench that is off must not cost a thread.
     */
    private void armReconnect(NetServerEntry server) {
        long generation = reconnectGeneration.get();
        // The attempt DIALS here, on the scheduler, and marshals only what it then
        // has to remember: the state it writes - the remembered-server maps, the
        // last-connected id, the entries - is what the servers dialog reads and
        // iterates on the display thread, while the dial itself may sit for the
        // whole connect timeout against a bench that answers nothing.
        reconnect = keepalive.schedule(() -> attemptReconnect(server, generation),
                RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Runs {@code body} where this UI's state belongs - the display thread when
     * there is a display, inline when there is not.
     *
     * <p>Inline is not a compromise: no display means no server list, so there is
     * nothing to race and nothing to marshal for.  That is a headless run
     * (the loopback tests), and taking a display that does not exist would create
     * one on a scheduler thread nobody ever pumps, which is worse than the race
     * it was meant to avoid.
     */
    private void onUiThread(Runnable body) {
        Display display = uiDisplay;
        if (display != null && !display.isDisposed()) {
            display.asyncExec(body);
            return;
        }
        Executor standIn = uiExecutor;
        if (standIn != null) {
            standIn.execute(body);
            return;
        }
        body.run();
    }

    /**
     * One attempt: DIALS here, on the keepalive thread, and marshals the rest.
     *
     * <p>A refusal is an attempt like any other - a server that answers and says
     * no is not a reason to try harder, so the next try is one delay away and the
     * log line stays at debug: this runs unattended and must not fill the log of
     * a machine whose bench is simply switched off.  Re-arming is done from here
     * too, and deliberately: it touches nothing but the future and the
     * generation, and going through the display thread to ask for another attempt
     * would put the retry's own cadence behind whatever the operator is doing.
     */
    private void attemptReconnect(NetServerEntry server, long generation) {
        if (connection != null || generation != reconnectGeneration.get()) {
            // Somebody got there first, or the operator cancelled between the
            // schedule and this call - the gap a future's cancel cannot close.
            return;
        }
        // Unattended, minutes after any dialog closed: the proxy that applies is
        // the COMMITTED one.  A proxy typed and then cancelled was never the
        // operator's answer, and this retry outlives the dialog that held it.
        Dialled dialled = dial(server, preferences.proxy());
        if (dialled.failure() != null) {
            if (log.isDebugEnabled()) {
                log.debug("net bench: {} is still not answering ({}) - trying again in "
                        + "{} ms", server.host(), dialled.failure(), RECONNECT_DELAY_MS);
            }
            if (generation == reconnectGeneration.get()) {
                armReconnect(server);
            }
            return;
        }
        onUiThread(() -> commitReconnect(server, generation, dialled));
    }

    /**
     * The half that remembers: display thread, and the last place the operator
     * can still have changed their mind.
     *
     * <p>The generation is re-checked HERE because everything that could have
     * overtaken this attempt happened while it was dialling or while this
     * runnable sat in the display queue - and an attempt that lost must UNDO
     * itself: a session opened after a Disconnect is the bench handed back to
     * somebody who let it go, and one opened after a manual Connect elsewhere is
     * a second live {@link NetConnection} whose close listener would later tear
     * down the session that won.  It undoes through {@link #closeSession()} and
     * not through {@code disconnect()}: this is the mechanism giving something
     * back, not the operator deciding anything.
     */
    private void commitReconnect(NetServerEntry server, long generation, Dialled dialled) {
        if (generation != reconnectGeneration.get()) {
            if (log.isInfoEnabled()) {
                log.info("net bench: the reconnect to '{}' was overtaken - giving the "
                        + "session it dialled straight back", server.name());
            }
            dialled.open().close(NetCloseReason.BYE);
            return;
        }
        // Read here rather than before the dial: this is the selection the
        // operator committed on the bench that died, and reading it on the thread
        // that owns it is the same reason the commit is marshalled at all.
        BackendKey selection = Preferences.instance().getSelectedBackend();
        closeSession();
        commitSession(dialled);
        if (log.isInfoEnabled()) {
            log.info("net bench: reconnected to '{}' at {}", server.name(), server.host());
        }
        // The entries are this server's again, so anything showing them re-composes
        // - the same publish a manual connect's caller makes.
        bus.publish(Events.REMOTE_BACKENDS_CHANGED);
        // And the backend the operator had committed is committed again: the
        // session is new, so the server knows nothing of it, and without this the
        // modules would find a connected bench with no backend selected and no
        // device to open on it.  select() refuses a key that is not this server's,
        // which is exactly the guard needed for a selection that has since moved.
        if (selection != null && selection.remote()) {
            reCommit(selection);
        }
    }

    /**
     * Re-commits {@code selection} on the session that was just reconnected -
     * back OFF the display thread, where the dial already ran.
     *
     * <p>{@code backend.select} is a round trip like any other and waits up to the
     * request timeout, and this one is made on a bench that has just come back
     * from the dead: it is precisely the moment a server is still finishing its
     * enumeration and slow to answer.  Run inline here it would hold the display
     * thread for that whole wait - a freeze arriving unannounced, five seconds
     * after a bench the operator may not even have noticed going away, with no
     * button pressed to explain it.
     *
     * <p>Only the ROUND TRIP moves.  Everything {@link #commitReconnect} does to
     * this object's own state stays on the display thread, because that is the
     * thread the servers dialog reads it from.
     */
    private void reCommit(BackendKey selection) {
        keepalive.execute(() -> {
            try {
                if (select(selection) && log.isInfoEnabled()) {
                    log.info("net bench: {} re-committed on the new session",
                            selection.key());
                }
            } catch (Throwable e) {
                // The scheduler thread's boundary: an Error escaping here takes
                // the keepalive down with it, and the client would then believe a
                // dead session is alive.
                if (log.isErrorEnabled()) {
                    log.error("net bench: re-committing {} failed", selection.key(), e);
                }
            }
        });
    }

    /** Drops any pending retry.  Idempotent: every path that changes the session
     *  calls it blindly, and a task that has already run is cancelled harmlessly
     *  (it re-arms itself only when its own attempt failed). */
    private void cancelReconnect() {
        // The generation FIRST: an attempt already running cannot be cancelled,
        // and this is what tells it that its result is no longer wanted.
        reconnectGeneration.incrementAndGet();
        ScheduledFuture<?> pending = reconnect;
        reconnect = null;
        if (pending != null) {
            pending.cancel(false);
        }
    }

    /** Whether a server is connected right now - what the list's Connect /
     *  Disconnect button and the combo composition both ask. */
    public boolean isConnected() {
        NetConnection open = connection;
        return open != null && open.isOpen();
    }

    /**
     * The address form of {@link #connect(NetServerEntry)}, for a caller with
     * no list row to point at (an unattended run).
     *
     * <p>The candidate is built exactly as the dialog's Add builds a typed-in
     * one - id and name standing in as the host until the {@code hello}
     * replaces both, {@code manual} true because nothing discovered it.  It
     * then goes straight to {@code connect}, NOT through {@link #probe} first:
     * probe's whole job is to turn an address into a REMEMBERED entry by
     * opening a session, reading the id and closing again, and connect already
     * does that same handshake - and reports the same failures - while keeping
     * the session it opened.  Probing first would cost a second round trip and
     * tell an automated caller nothing connect will not.
     */
    @Override
    public String connectByAddress(String hostColonPort) {
        String address = hostColonPort == null ? "" : hostColonPort.trim();
        // Split on the LAST colon so a bracketed IPv6 literal survives.
        int colon = address.lastIndexOf(':');
        String host = colon < 0 ? address : address.substring(0, colon);
        int port = NetProto.DEFAULT_PORT;
        if (colon >= 0) {
            try {
                port = Integer.parseInt(address.substring(colon + 1).trim());
            } catch (NumberFormatException notANumber) {
                return I18n.t("net.servers.error.address");
            }
        }
        if (host.isEmpty() || port <= 0) {
            return I18n.t("net.servers.error.address");
        }
        return connect(new NetServerEntry(host, host, host, port, true));
    }

    /** {@link #isConnected()} under the service contract's name. */
    @Override
    public boolean isSessionConnected() {
        return isConnected();
    }

    /** {@link #disconnect()} under the service contract's name. */
    @Override
    public void disconnectSession() {
        disconnect();
    }

    /**
     * Spec 4.3's {@code backend.list}: one combo entry per backend the server
     * can actually operate.
     *
     * <p>A backend that is in the server's build but cannot run on its host
     * (CoreAudio on a Windows bench) is left OUT rather than shown greyed: the
     * combo is a list of things to measure with, and an entry whose every device
     * open would fail is not one.  A backend name this build does not know is
     * skipped for the same reason - a newer server may serve one.
     */
    private List<Entry> readBackends(NetConnection open, NetServerEntry server) {
        NetMessage answer;
        try {
            answer = open.uiRequest(open.newRequest(MessageType.BACKEND_LIST));
        } catch (RuntimeException e) {
            if (log.isWarnEnabled()) {
                log.warn("net bench: backend.list failed: {}", e.toString());
            }
            return List.of();
        }
        if (!answer.isOk()) {
            if (log.isWarnEnabled()) {
                log.warn("net bench: the server refused backend.list: {}", answer.getError());
            }
            return List.of();
        }
        return parseBackends(answer.getData(), server);
    }

    /** The combo entries a {@code backend.list} payload describes - shared by
     *  the connect-time read and the non-blocking refresh, so the two can never
     *  disagree on what counts as offerable. */
    private List<Entry> parseBackends(JsonNode data, NetServerEntry server) {
        List<Entry> found = new ArrayList<>();
        for (JsonNode backend : data.path(NetFields.BACKENDS)) {
            if (!backend.path(NetFields.AVAILABLE).asBoolean()
                    || !backend.path(NetFields.OPERATIONAL).asBoolean()) {
                continue;
            }
            BackendKey key = remoteKey(server.serverId(),
                    backend.path(NetFields.BACKEND).asText());
            if (key == null) {
                continue;
            }
            String displayName = backend.path(NetFields.DISPLAY_NAME).asText(key.type().name());
            found.add(new Entry(key, I18n.t("net.servers.backendLabel", server.name(),
                    displayName)));
        }
        if (log.isInfoEnabled()) {
            log.info("net bench: '{}' offers {} backend(s)", server.name(), found.size());
        }
        return List.copyOf(found);
    }

    /**
     * Spec 4.3's {@code backend.list}, asked again mid-session - see
     * {@link RemoteBackendUi#refreshEntries()}.  The server enumerates its
     * devices fresh on every such request, so this is what makes a bench-side
     * hot-plug (the analyzer appearing on, or leaving, the SERVER's USB) reach
     * this combo without a reconnect.
     *
     * <p>Non-blocking on purpose: the caller is the Preferences dialog opening
     * or a Scan click, and neither may hang on a server that is just dying.
     * The answer lands on the connection's reader thread; the
     * {@link Events#REMOTE_BACKENDS_CHANGED} subscribers marshal, exactly as
     * they do for the connection-death publish.  An unchanged list publishes
     * nothing, so an open dialog is not re-composed for a bench that did not
     * move.
     */
    @Override
    public void refreshEntries() {
        NetConnection open = connection;
        NetServerEntry server = connectedServer;
        if (open == null || !open.isOpen() || server == null) {
            return;
        }
        open.send(open.newRequest(MessageType.BACKEND_LIST)).whenComplete((answer, failure) -> {
            if (failure != null || !answer.isOk()) {
                if (log.isWarnEnabled()) {
                    log.warn("net bench: backend.list refresh failed: {}",
                            failure != null ? failure.toString() : answer.getError());
                }
                return;
            }
            List<Entry> fresh = parseBackends(answer.getData(), server);
            if (!fresh.equals(entries)) {
                entries = fresh;
                bus.publish(Events.REMOTE_BACKENDS_CHANGED);
            }
        });
    }

    /** The selection key for one backend of one server, or null when either half
     *  is unusable - an unknown backend name, or a server id that
     *  {@link BackendKey} could not read back again. */
    private BackendKey remoteKey(String serverId, String wireName) {
        BackendKey type = BackendKey.parse(wireName);
        if (type == null) {
            if (log.isInfoEnabled()) {
                log.info("net bench: backend '{}' is not one this build knows", wireName);
            }
            return null;
        }
        try {
            return BackendKey.of(serverId, type.type());
        } catch (IllegalArgumentException e) {
            if (log.isWarnEnabled()) {
                log.warn("net bench: server id '{}' cannot be remembered: {}", serverId,
                        e.getMessage());
            }
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // What goes wrong on the bench
    // -------------------------------------------------------------------------

    /**
     * Spec 5: the server admitted to a loss.  The stream carries on, so this is
     * not a device error - but it is not nothing either: the samples on either
     * side of the gap are not adjacent in time, and an analyzer that averages
     * across the splice reports a noise floor and a phase that never existed.
     *
     * <p>So the analyzers are TOLD, on the seam they already use for a capture
     * that had to re-sync ({@link Events#FFT_CAPTURE_RESYNC}) - the operator gets
     * the same blinking confession an overrun raises, which is the whole point of
     * the GAP frame ("an explicit confession - the client resets averaging
     * instead of silently splicing").
     */
    @Override
    public void gap(long lostFrames) {
        if (log.isWarnEnabled()) {
            log.warn("net bench: the server lost {} stereo frame(s)", lostFrames);
        }
        bus.publish(Events.FFT_CAPTURE_RESYNC, GAP_BANNER_KEY);
    }

    /** Spec 4.3: the bench says a lane died.  Log-only here: an input-lane
     *  failure also reaches the open capture stream itself, which finishes the
     *  ring buffer ({@code NetPcmCapture.endCapture}) - the measuring pane
     *  consults its reader and reports; an output-lane failure comes back out
     *  of the remote generator's own calls (the SPI half, chunk 5). */
    @Override
    public void deviceError(String direction, String detail) {
        if (log.isWarnEnabled()) {
            log.warn("net bench: the {} lane died - {}", direction, detail);
        }
    }

    /** A device this client was HOLDING has left the bench's catalogue (see
     *  {@link NetFaultListener#deviceGone}).  Log-only: a stream running on it
     *  fails on its own path (the bench confesses, the capture finishes its
     *  ring buffer, the generator's calls throw), and a held-but-idle device
     *  has nothing to stop. */
    @Override
    public void deviceGone(DeviceRef device) {
        if (log.isWarnEnabled()) {
            log.warn("net bench: held device '{}' left the bench's catalogue",
                    device.displayName());
        }
    }

    /**
     * The bench's stored calibration for one of its devices moved (spec 4.3's
     * {@code cal} on {@code ev.devices.changed}) - another client recalibrated it,
     * bound it to another card, moved its active range, or moved the analyzer's
     * attenuator.
     *
     * <p>If that device is the one THIS client is measuring on, the new full scale
     * takes effect at once: every reading on screen is computed against it, and
     * carrying on with the old one would show volts that are simply wrong until
     * the next device open.  It goes through the SAME apply the Preferences dialog
     * uses on OK, so there is one precedence and one arithmetic - the ref carries
     * the new pair, and applying it is all this has to decide.
     *
     * <p>A device that is not the current selection is left alone: its calibration
     * is not in force here, and the catalogue the manager just rebuilt already
     * carries the new values for whenever it is.
     *
     * <p>Marshalled: this arrives on the connection's reader thread, and the
     * scalars are bound to widgets whose listeners fire on the display thread.
     */
    @Override
    public void calibrationChanged(DeviceRef device) {
        if (!rescales(device)) {
            return;
        }
        if (log.isInfoEnabled()) {
            log.info("net bench: '{}' was recalibrated on the bench while it is the "
                    + "selected {} - rescaling", device.name(),
                    device.isInput() ? "input" : "output");
        }
        GuiUtil.marshal(() -> Preferences.instance()
                .applyDeviceProfile(device, device.isInput()));
    }

    /**
     * Whether a bench recalibration of {@code device} is THIS client's to apply:
     * the modules must be measuring on a bench at all, and on that very device in
     * that very direction.
     *
     * <p><b>The carrier check is not a formality.</b>  A session stays alive - and
     * this listener stays subscribed - while the operator measures on a LOCAL
     * backend; that is exactly why the connection-loss box has the same guard.  A
     * QA40x names itself after its model, so a local QA403 and a bench QA403 are
     * one name (which is why a hot-plug comparison needed more than the name,
     * {@code DeviceRef.identity()}).  Without this, another operator recalibrating
     * the BENCH's analyzer would silently move the dBV reference of a local
     * measurement that has nothing to do with it.
     */
    boolean rescales(DeviceRef device) {
        if (AudioBackend.instance().active() != AudioBackendType.NET) {
            return false;
        }
        BackendPrefs current = Preferences.instance().current();
        String inForce = device.isInput()
                ? current.getInputDeviceName() : current.getOutputDeviceName();
        return device.name().equals(inForce);
    }

    /**
     * The session ended - however it ended (spec 4.1: "stop all modules, show the
     * connection error").  The modules stop bottom-up (every open stream fails on
     * its own path), so what remains here is the report no stream owns:
     *
     * <p>This is the ONE place a dead session reaches the operator.  A capture
     * that was open on it finishes its ring buffer - its pane pops Record - but
     * the session's own end is said once, here, whether or not anything was
     * streaming (a bench that dies with nothing streaming still has to say so).
     */
    private void onConnectionClosed(NetCloseReason reason) {
        NetServerEntry was = connectedServer;
        connection = null;
        connectedServer = null;
        committed = null;
        entries = List.of();
        NetDeviceManager manager = manager();
        if (manager != null) {
            manager.disconnect();
        }
        if (log.isWarnEnabled()) {
            log.warn("net bench: the session ended - {}", reason.getDetail());
        }
        // The entries this bench contributed are gone with it, so anything showing
        // them has to re-compose - an open Preferences dialog above all, which
        // would otherwise go on editing a backend nothing can be opened on.
        bus.publish(Events.REMOTE_BACKENDS_CHANGED);
        // A session the OPERATOR ended is not a loss: their Disconnect, a switch
        // to another server, and the app's own shutdown are decisions, so no
        // message exists to show and nothing is worth reconnecting to.  Every
        // other ending is the bench going away underneath them - the one case
        // the operator must be told about and the session is worth getting back.
        if (reason != NetCloseReason.BYE) {
            showConnectionLost(reason);
            if (was != null) {
                armReconnect(was);
            }
        }
    }

    /**
     * Tells the operator the session is gone - but only while the modules are
     * actually ON it: shown while a local backend is active, it would alarm a
     * perfectly healthy local measurement over a server nothing here is using.
     * The carrier answers the question - every remote selection is reached
     * through {@link AudioBackendType#NET}.
     *
     * <p>This UI owns the connection, so this UI reports its death - the same
     * rule that has each pane report its own dead capture.  Arrives on the
     * connection's reader thread; the box is marshalled and needs a shell,
     * so a headless client (the loopback tests) simply logs.
     */
    private void showConnectionLost(NetCloseReason reason) {
        if (AudioBackend.instance().active() != AudioBackendType.NET) {
            if (log.isInfoEnabled()) {
                log.info("net bench: connection loss not surfaced - the modules are "
                        + "measuring on a local backend");
            }
            return;
        }
        String message = I18n.t("net.error.connectionLost", I18n.t(reason.getMessageKey()));
        onUiThread(() -> {
            Display display = uiDisplay;
            if (display == null || display.isDisposed()) return;
            Shell shell = display.getActiveShell();
            if (shell == null || shell.isDisposed()) return;
            Dialogs.error(shell, I18n.t("audio.deviceError.title"), message);
        });
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** The net backend's manager, or null when this build ships the UI without
     *  the client backend behind it. */
    private NetDeviceManager manager() {
        AudioBackend backend = AudioBackend.instance();
        if (!backend.isAvailable(AudioBackendType.NET)) {
            return null;
        }
        AudioDeviceManager found = backend.manager(AudioBackendType.NET);
        return found instanceof NetDeviceManager net ? net : null;
    }

    private URI wsUri(NetServerEntry server) {
        return URI.create("ws://" + server.host() + ":" + server.port());
    }

    /** The user-visible client name of spec 4.1 - this machine, because that is
     *  what tells one bench user from another on somebody else's screen. */
    private String clientName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return CLIENT_APP;
        }
    }

    private String clientApp() {
        String version = getClass().getPackage().getImplementationVersion();
        return version == null || version.isEmpty() ? CLIENT_APP : CLIENT_APP + " " + version;
    }
}
