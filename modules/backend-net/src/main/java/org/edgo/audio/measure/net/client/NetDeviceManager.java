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

package org.edgo.audio.measure.net.client;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.sound.sampled.AudioFormat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.DeviceFailureReason;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.net.proto.NetMessage;
import org.edgo.audio.measure.sound.AudioCapture;
import org.edgo.audio.measure.sound.AudioDeviceManager;
import org.edgo.audio.measure.sound.AudioPlayback;
import org.edgo.audio.measure.sound.DeviceCalibration;
import org.edgo.audio.measure.sound.DeviceRef;
import org.edgo.audio.measure.sound.RemoteGenerator;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

/**
 * The net backend's device manager: everything the rest of the application asks
 * a backend, answered from a server's {@code devices.list} instead of from local
 * hardware.
 *
 * <p><b>One server, one remote backend.</b>  A connection reaches a server that
 * serves several backends at once (spec 4.3), and the operator picks ONE of them
 * - "&lt;server name&gt; -&gt; QA40x" - so this manager is pointed at that pair by
 * {@link #connect(NetConnection, JsonNode)} and filters the catalogue down to it.
 * A device of another backend is not this manager's to open.
 *
 * <p><b>Why the connection arrives after construction.</b>  The runtime SPI
 * discovers backends through a {@code ServiceLoader} provider with a no-argument
 * constructor, and a manager exists long before the operator has chosen a server
 * - so the connection cannot be a constructor argument here, and this type is
 * exactly one server's worth of state that is set, replaced and cleared.
 *
 * <p><b>The cache and where it comes from.</b>  The catalogue arrives with the
 * selection itself: spec 4.3 answers {@code backend.select} with "the selected
 * backend's full entry in {@code devices.list} shape ... one round-trip fills the
 * device combos", and that answer is what {@link #connect(NetConnection, JsonNode)}
 * is handed.  Nothing is fetched afterwards to fill the combos, which is the
 * point - a second request would be a window, and an {@code ev.devices.changed}
 * landing inside it used to decide what the operator saw.  From then on
 * {@code ev.devices.changed} carries the whole new list on every hot-plug and
 * every lock change, so the cache is rebuilt from the EVENT - no request, and
 * nothing stale between a device appearing on the bench and somebody asking
 * about it.
 *
 * <p>Enumeration degrades gracefully as the SPI requires: a manager nobody has
 * connected answers empty lists rather than throwing, because a backend combo
 * being empty is a state the application already knows how to show.
 *
 * <p><b>It is also the bench's fault hub.</b>  Spec 4.3's {@code ev.device.error},
 * spec 4.1's dead session and spec 5's losses all reach the operator through the
 * one {@link NetFaultListener} set here - the manager is the only net type the
 * glue holds (connecting it to a server is what the server-list dialog does), so
 * a seam anywhere else could not be subscribed to at all.
 *
 * <p><b>And it is the bench's generator.</b>  Spec 4.5 runs the DDS beside the
 * DAC, so the desktop's generator is commanded rather than rendered: this manager
 * implements {@link RemoteGenerator}, which is the capability the generator
 * controller looks for on the active backend.  It lives here for the same reason
 * the catalogue does - the session, the selected backend and the output device's
 * lock are all already here, and a generator that owned a second connection could
 * not be told that the bench had gone away.
 */
@Log4j2
public final class NetDeviceManager implements AudioDeviceManager, NetFaultListener,
        RemoteGenerator {

    /** Fallback channel count for a device format that names none - spec 4.3's
     *  formats are stereo pairs, and the pipeline is stereo downstream. */
    private static final int STEREO = 2;
    /** Value of {@link #genId} while no remote generator is open - the same
     *  "nothing is open" the server's own session uses. */
    private static final int NO_GEN = 0;

    /** Held as a field, not as a method reference at the call site: the same
     *  object has to be handed back to remove the subscription again. */
    private final Consumer<NetMessage> events = this::onEvent;
    /** The devices this session holds the exclusive lock of spec 4.3 on, by the
     *  ref the lock was taken with.  Every lock this backend takes is registered
     *  here - a capture's and the generator's - because nothing on the wire can
     *  tell a first acquire from a repeat: the server answers a second acquire by
     *  the SAME session with success, so a client that did not remember would
     *  release a device it never took and close the stream running on it.
     *  Concurrent: a capture takes its lock on the caller's thread while the
     *  generator takes its own. */
    private final Set<NetDeviceRef> held = ConcurrentHashMap.newKeySet();

    /** Who is told when something goes wrong on the bench, or null while nobody
     *  has subscribed.  Volatile: set on the operator's thread, read on the
     *  connection's reader thread. */
    @Setter
    private volatile NetFaultListener faultListener;
    /** The session this backend speaks through, or null while no server is
     *  connected.  Volatile: connected and dropped on the operator's thread,
     *  read by whichever thread enumerates or opens a device. */
    @Getter
    private volatile NetConnection connection;
    /** Which of the server's backends this manager presents, as the wire name of
     *  spec 4.3 ({@code "QA40X"}, {@code "JAVASOUND"}).  For the TYPED answer -
     *  what the routed backend IS - ask {@link #remoteBackendType()}. */
    @Getter
    private volatile String remoteBackend;
    /** The catalogue as last heard, replaced whole - never mutated in place, so
     *  a reader always sees one consistent enumeration. */
    private volatile List<RemoteDevice> devices = List.of();
    /** The open generator's handle (spec 4.5), or {@link #NO_GEN}.  Volatile:
     *  opened and closed on the operator's thread, read by every setter the
     *  generator controller drives. */
    private volatile int genId = NO_GEN;
    /** The output device the generator holds, or null - kept so the lock is
     *  given back to the ref it was taken on, whatever the catalogue does
     *  meanwhile. */
    private volatile NetDeviceRef genDevice;
    /** The last {@code ev.gen.state} (spec 4.5), and the ONLY thing
     *  {@link #state()} answers from.  Written on the connection's reader
     *  thread, read by the analyzers. */
    private volatile State genState = State.IDLE;
    /** Why the remote lane ended from BELOW - the bench's output
     *  {@code ev.device.error}, or the session dying with the tone up -
     *  claimed once by {@link #takeEndedFromBelowForReport()}.  Null while
     *  healthy and after a commanded stop/close. */
    private final AtomicReference<Throwable> genEndedBelow = new AtomicReference<>();
    /** The last {@code file} block of {@code ev.gen.state}, and the ONLY thing
     *  {@link #fileState()} answers from.  Written on the connection's reader
     *  thread, read by the generator controller's poll. */
    private volatile FileState genFileState = FileState.IDLE;
    /** Why a file the bench had ACCEPTED stopped playing on its own - claimed
     *  once by {@link #takeFileErrorForReport()}, the file twin of
     *  {@link #genEndedBelow}. */
    private final AtomicReference<Throwable> fileError = new AtomicReference<>();
    /** Where uploads go, or null to use the session's own {@code http://host:port}
     *  - which is what production always does, because one port serves both.  A
     *  TEST bench cannot multiplex the two onto one socket (its WebSocket library
     *  owns that one), so it points this at the port its file endpoint really
     *  listens on.  Package-private for exactly that and nothing else. */
    @Setter(AccessLevel.PACKAGE)
    private volatile URI uploadBase;

    // -------------------------------------------------------------------------
    // The server this backend is pointed at
    // -------------------------------------------------------------------------

    /**
     * Points this backend at the backend {@code selected} names, on
     * {@code connection}, taking over from whatever it was pointed at before -
     * and fills the catalogue from that same payload.
     *
     * <p>{@code selected} is the answer to {@code backend.select}: one entry in
     * {@code devices.list} shape, which spec 4.3 shapes that way precisely so
     * "one round-trip fills the device combos".  Both the backend's wire name and
     * its devices are read from it, so the two can never disagree, and the
     * selection is usable the moment this returns.
     *
     * <p><b>Why it is not fetched afterwards instead.</b>  It used to be: the
     * first enumeration sent its own {@code devices.list}.  Between pointing the
     * manager at the bench and that answer arriving, an {@code ev.devices.changed}
     * could land and be taken for the catalogue - and a server whose first
     * enumeration had not finished broadcast an EMPTY one.  The combos then
     * stayed empty until the operator selected the bench a second time, which is
     * what a QA403 bench showed.  The window is gone because the request is.
     */
    /** The TRUE type of the backend this manager is routed at, or {@code null}
     *  when nothing is selected - or the server serves a backend this build
     *  has no constant for (dual-level rule: identity by true type, reachability
     *  by the net carrier). */
    public AudioBackendType remoteBackendType() {
        String name = remoteBackend;
        return name == null ? null : AudioBackendType.fromNameOrNull(name);
    }

    public void connect(NetConnection connection, JsonNode selected) {
        disconnect();
        String backend = selected.path(NetFields.BACKEND).asText(null);
        this.remoteBackend = backend;
        this.connection = connection;
        connection.addEventListener(events);
        devices = devicesOf(selected, backend);
        if (log.isInfoEnabled()) {
            log.info("net backend: routed at {} on '{}' with {} device(s)", backend,
                    connection.getServerName(), devices.size());
        }
    }

    /**
     * Points the catalogue - and only the catalogue - at the backend
     * {@code selected} names: the staged twin of {@link #connect}, for a
     * Preferences dialog that must show a backend's devices WITHOUT touching the
     * live session (its rule: nothing applies before OK).
     *
     * <p>So this sets what the enumeration answers and which backend's name the
     * settings seams compare against, and deliberately nothing else: no
     * {@code backend.select} went out, the event subscription, the held locks,
     * the open streams and the generator lane all stay as they are.  Until
     * {@link #connect} commits a selection, the catalogue may therefore name a
     * backend the SESSION is not committed to - which is exactly the staged
     * state, and safe because devices are only opened after a commit (the
     * modules restart on the dialog's OK, never while it is up).
     */
    public void preview(JsonNode selected) {
        String backend = selected.path(NetFields.BACKEND).asText(null);
        this.remoteBackend = backend;
        devices = devicesOf(selected, backend);
        if (log.isDebugEnabled()) {
            log.debug("net backend: previewing {} with {} device(s)", backend,
                    devices.size());
        }
    }

    /**
     * Forgets the server: the catalogue empties, so nothing offers a device that
     * can no longer be opened.  Does not close the connection - the session
     * belongs to whoever opened it, and one connection can serve several of a
     * server's backends.
     *
     * <p><b>A generator on a LIVE session is closed first, and every remaining
     * lock is given back.</b>  This is reached two ways.  When the session has
     * already ended, the far end tore everything down itself and the handles are
     * simply forgotten - sending a {@code gen.close} into a dead socket buys
     * nothing, and a stale "running" state would have the pane show a tone
     * nothing is emitting.  But it is also reached while the session is very much
     * alive: re-pointing this backend at another of the server's backends (see
     * {@link #connect}) runs through here, and so does the switch to a LOCAL
     * backend (see {@link #shutdown()}).  Dropping the handles there would
     * abandon the far end's playback lane AND the device locks for the rest of
     * the session - spec 4.3 keeps a lock once taken ("locks already held stay
     * valid"), so nobody could free that DAC or that ADC until the session died.
     * The generator's own device goes back with the lane; the CAPTURE locks used
     * to be dropped from the register with no release on the wire, and then could
     * not be released at all, because {@link #connection} was already null and
     * {@link #releaseDevice} answers a dead session by sending nothing.
     */
    public void disconnect() {
        NetConnection open = connection;
        if (open != null && open.isOpen()) {
            closeGenerator();
            releaseHeld();
        }
        if (open != null) {
            open.removeEventListener(events);
        }
        if (genId != NO_GEN) {
            // Only the DEAD-session path still holds a handle here (a live
            // switch closed the generator above): the tone was up when the
            // session ended - the pane learns bottom-up via the consult, which
            // is how a net-backend disconnect stops playback.
            genEndedBelow.compareAndSet(null, new IllegalStateException(
                    "the session with the server ended while the tone was playing"));
            if (genFileState.playing()) {
                // A FILE was playing on that lane.  Its watcher reads only the
                // pushed state and this claim-once cell, so leaving both as they
                // are would have it spin for ever on a bench that is gone, with
                // the play indicator lit and nothing said - the very "measurement
                // of nothing presented as a measurement" the capture side fixed.
                fileError.compareAndSet(null, new IllegalStateException(
                        "the session with the server ended while a file was playing"));
            }
        }
        connection = null;
        remoteBackend = null;
        devices = List.of();
        held.clear();
        genId = NO_GEN;
        genDevice = null;
        genState = State.IDLE;
        // Finished, not merely idle: the watcher's break reads this as the end of
        // the file, so it leaves even if it never gets to claim the error.
        genFileState = new FileState(false, true);
    }

    /**
     * The SPI's app-exit teardown, which this backend also needs at a BACKEND
     * SWITCH - and that is the only reason it is overridden.
     *
     * <p>{@code AudioBackend.setActive} calls it on the carrier being left, and
     * the default is a no-op because an ordinary sound card's handles die with
     * the process.  A bench's do not: they live on another machine.  Without this
     * override, moving from a server to a local backend left the far end holding
     * this client's device locks and running its generator lane - a DAC no other
     * client could take, still emitting, for as long as the session lived.
     *
     * <p>The teardown is {@link #disconnect()}'s, unchanged: the lane is closed,
     * the locks go back on the wire, the catalogue empties.  The session itself
     * is not closed - it belongs to whoever opened it, and reselecting the bench
     * re-points this manager at it.
     */
    @Override
    public void shutdown() {
        disconnect();
    }

    /** Gives back every device lock this session still holds, on the wire.  A
     *  copy is walked because {@link #releaseDevice} removes from the register as
     *  it goes. */
    private void releaseHeld() {
        for (NetDeviceRef ref : Set.copyOf(held)) {
            releaseDevice(ref);
        }
    }

    /** Spec 4.3: {@code ev.devices.changed} carries the full {@code devices.list}
     *  payload on every hot-plug and every lock change, so the cache is rebuilt
     *  from it directly; {@code ev.device.error} is the bench saying a lane died.
     *  Spec 4.5's {@code ev.gen.state} is the generator saying what it emits.
     *  Other events are somebody else's. */
    private void onEvent(NetMessage event) {
        switch (event.getType()) {
            case EV_DEVICES_CHANGED:
                cache(event.getNode(NetFields.BACKENDS));
                break;
            case EV_DEVICE_ERROR:
                deviceError(event.optString(NetFields.DIRECTION),
                        event.optString(NetFields.DETAIL),
                        DeviceFailureReason.fromName(event.optString(NetFields.REASON)));
                break;
            case EV_GEN_STATE:
                cacheGenState(event);
                break;
            default:
                break;
        }
    }

    // -------------------------------------------------------------------------
    // What goes wrong on the bench
    // -------------------------------------------------------------------------

    /**
     * This backend's reading of its own failures - and for once the reading was
     * not done here.  A remote device is classified by the backend that owns it,
     * on the bench, and the answer travels as the enum's NAME (spec 4.2's
     * optional {@code reason} on a refusal, 4.3's on {@code ev.device.error}).
     * So all this does is hand on what {@link NetConnection.Refusal} carried,
     * which is exactly the point of sending a machine-readable reason instead of
     * a sentence: no client ever parses the server's English.
     *
     * <p>Anything that is not a refusal - a socket that died mid-open, a JSON
     * fault - is {@code UNKNOWN}: it was never the far device's failure.
     */
    @Override
    public DeviceFailureReason classifyFailure(Throwable failure) {
        return (failure instanceof NetConnection.Refusal refused)
                ? refused.getReason() : DeviceFailureReason.UNKNOWN;
    }

    /** Spec 5: a stream's server-side loss, passed on so the averaging is reset.
     *  A gap nobody hears is exactly the silent splice the spec forbids, which is
     *  why the unsubscribed case is a log line and not a no-op. */
    @Override
    public void gap(long lostFrames) {
        NetFaultListener listener = faultListener;
        if (listener == null) {
            if (log.isWarnEnabled()) {
                log.warn("net backend: the bench lost {} stereo frame(s) and nothing is "
                        + "subscribed to say so", lostFrames);
            }
            return;
        }
        listener.gap(lostFrames);
    }

    /** Spec 4.3: "the client surfaces it exactly like a local device error
     *  (message + stop the affected modules)" - this is where the event, a dead
     *  session and a broken stream all converge on their way to the operator. */
    @Override
    public void deviceError(String direction, String detail) {
        deviceError(direction, detail, DeviceFailureReason.UNKNOWN);
    }

    /** The same report for the event that names a REASON (spec 4.3's optional
     *  {@code reason}): the bench already asked the backend that owns the
     *  driver, so nothing here re-reads its English - the enum travels on and
     *  the operator is told why in their own language. */
    private void deviceError(String direction, String detail, DeviceFailureReason reason) {
        if (log.isErrorEnabled()) {
            log.error("net backend: the bench's {} lane failed ({}): {}",
                    direction, reason, detail);
        }
        if (NetFields.OUTPUT.equals(direction) && genId != NO_GEN) {
            // The bench's tone died from below: recorded for the pane's
            // consult (the same claim-once the local lane keeps), never
            // published - see RemoteGenerator#takeEndedFromBelowForReport.
            // A Refusal and not a bare exception, so the consult can ask this
            // manager what it meant and get the bench's own answer back.
            if (genFileState.playing()) {
                // A FILE was on that lane, so the FILE player is the one who
                // reports the death - one lane dying is one thing that happened
                // and must raise ONE dialog, not the tone's and the file's both.
                // The file's own claim-once takes it, and the state is finished so
                // its watcher leaves even if nobody claims.
                fileError.compareAndSet(null, new NetConnection.Refusal(detail, reason));
                genFileState = new FileState(false, true);
            } else {
                genEndedBelow.compareAndSet(null, new NetConnection.Refusal(detail, reason));
            }
        }
        NetFaultListener listener = faultListener;
        if (listener != null) {
            listener.deviceError(direction, detail);
        }
    }

    /** A device this session HOLDS is no longer in the bench's catalogue - see
     *  {@link NetFaultListener#deviceGone}.  Logged even with nobody subscribed,
     *  for the same reason a gap is: a measurement that carried on against a
     *  device that is not there must leave a trace somewhere. */
    @Override
    public void deviceGone(DeviceRef device) {
        if (log.isErrorEnabled()) {
            log.error("net backend: {} is gone from the bench while this client "
                    + "was using it", device);
        }
        NetFaultListener listener = faultListener;
        if (listener != null) {
            listener.deviceGone(device);
        }
    }

    // -------------------------------------------------------------------------
    // The device contract
    // -------------------------------------------------------------------------

    /**
     * The resolve-miss retry ({@code AudioBackend}'s one-rebuild-one-relook)
     * and the operator's Scan, remote twin: re-fetches the catalogue with one
     * {@code devices.list} round-trip - the server treats the ask as the scan
     * gesture and rebuilds its snapshot backends first - and rebuilds the
     * cache from the answer exactly as an {@code ev.devices.changed} would.
     * Without it, a card replugged on the server resolved only after a prefs
     * visit re-selected the backend; now the failing start's own retry re-asks
     * and self-heals.  {@code false} with no live session - the list
     * cannot be rebuilt, which is the interface default's meaning.
     */
    @Override
    public boolean refreshDeviceList() {
        NetConnection open = connection;
        if (open == null || !open.isOpen()) {
            return false;
        }
        try {
            // uiRequest: the re-ask serves a click the operator is waiting on.
            NetMessage answer = open.uiRequest(open.newRequest(MessageType.DEVICES_LIST));
            if (!answer.isOk()) {
                if (log.isWarnEnabled()) {
                    log.warn("net backend: the devices.list re-ask was refused - the "
                            + "catalogue stays as it was");
                }
                return false;
            }
            cache(answer.getData().path(NetFields.BACKENDS));
            return true;
        } catch (RuntimeException e) {
            if (log.isWarnEnabled()) {
                log.warn("net backend: the devices.list re-ask failed: {}", e.toString());
            }
            return false;
        }
    }

    @Override
    public List<DeviceRef> listInputDevices() {
        return listDevices(true);
    }

    @Override
    public List<DeviceRef> listOutputDevices() {
        return listDevices(false);
    }

    /** The device at {@code index} of one direction's list - matched on the
     *  index the server published, so a device that vanished fails here rather
     *  than handing back its neighbour. */
    @Override
    public DeviceRef getDeviceByIndex(int index, boolean isOutput) {
        for (DeviceRef ref : listDevices(!isOutput)) {
            if (ref.index() == index) {
                return ref;
            }
        }
        throw new IllegalArgumentException("no remote " + (isOutput ? "output" : "input")
                + " device with index " + index + " on " + remoteBackend);
    }

    /**
     * The formats spec 4.3 inlines with the device, kept from the catalogue.
     * The direction is the REF's - a ref names one direction of one device - so
     * {@code output} is not consulted; a ref from another backend has no entry
     * here and gets the empty list the SPI asks for.
     */
    @Override
    public List<AudioFormat> listSupportedFormats(DeviceRef device, boolean output) {
        for (RemoteDevice remote : devices) {
            if (remote.ref().equals(device)) {
                return remote.formats();
            }
        }
        return List.of();
    }

    /**
     * Whether the operator may choose a sample WIDTH for {@code device}, as the
     * server declared it per device in {@code devices.list} (spec 4.3).
     *
     * <p>It is the server's answer and not a guess from the format list because
     * the two are different questions: a QA403 offers 24-bit only until its
     * front-panel I2S port is switched on, and only the bench knows that.  A ref
     * of another backend, or one from a catalogue that has not been read yet,
     * answers {@code false} - no choice to offer.
     */
    public boolean hasBitDepth(DeviceRef device) {
        for (RemoteDevice remote : devices) {
            if (remote.ref().equals(device)) {
                return remote.hasBitDepth();
            }
        }
        return false;
    }

    /**
     * The name of the client holding {@code device}'s lock on the bench, or null
     * when it is free - spec 4.3's {@code lock:{by}}, kept from the catalogue so
     * a device somebody else is measuring on can be shown as taken instead of
     * being discovered as a {@code DEVICE_LOCKED} refusal at open time.
     *
     * <p>Locks this session holds itself are still reported: which of the two it
     * is depends on who is asking, and the answer here is simply what the bench
     * said.
     */
    public String lockedBy(DeviceRef device) {
        for (RemoteDevice remote : devices) {
            if (remote.ref().equals(device)) {
                return remote.lockedBy();
            }
        }
        return null;
    }

    @Override
    public AudioCapture openCapture(DeviceRef device, int sampleRate, int bitDepth) {
        return new NetPcmCapture(requireConnection(), remoteRef(device), sampleRate,
                bitDepth, this);
    }

    /** Spec 7: there is no uplink audio in v1 - see {@link NetPcmPlayback} for
     *  why the generator being server-side is what makes it unnecessary. */
    @Override
    public AudioPlayback openPlayback(DeviceRef device, int sampleRate, int bitDepth,
            double ditherBits) {
        return new NetPcmPlayback();
    }

    /**
     * Runs {@code work} while this session holds the exclusive lock (spec 4.3) on
     * ANY one device of the backend this manager is routed at.
     *
     * <p><b>Which of the two lock rules this is.</b>  Spec 4.6 gates a BACKEND's
     * own settings - the QA40x ranges, its front-panel port - on "the QA40x lock
     * (either direction)": one analyzer, so holding any of its devices makes the
     * caller the client whose measurement the change belongs to.  Spec 4.3's
     * {@code device.setCalibration} is the other rule: it changes what one DEVICE
     * means, and the server checks the lock on exactly that device and direction.
     * A caller with a device in hand wants {@link #withDeviceLock(NetDeviceRef,
     * Supplier)} instead - this one would happily take the first free INPUT and
     * have an output write refused {@code NOT_LOCKED}.
     *
     * <p><b>What it is for.</b>  A backend's own settings may only be WRITTEN by
     * the client that holds the device, and a settings panel usually holds
     * nothing, so every such write was answered {@code NOT_LOCKED} and the
     * operator saw a panel that changed nothing.  Taking the lock for the one
     * request is the whole fix, and it is also what the rule is FOR: it fails
     * honestly while ANOTHER client is measuring on that analyzer instead of
     * moving its attenuator underneath.
     *
     * <p><b>A lock this session already holds is used and NOT given back.</b>
     * The modules are not stopped while the Preferences dialog is up - only a
     * committed audio-config change bounces them - so the scope and the FFT are
     * routinely streaming from this bench while a settings panel writes.  Their
     * capture holds the input device, and the server answers a repeat acquire by
     * the same session with success (there is nothing on the wire to tell the two
     * apart), so releasing afterwards would hit spec 4.3's "also closes any open
     * stream/generator on it": the running capture is torn down server-side,
     * orderly enough that no {@code ev.device.error} follows, and the panes
     * freeze with nothing to say why.  Which device the lock is on does not
     * matter - spec 4.6 accepts either direction - only that this connection is
     * the one holding it.
     *
     * <p>Otherwise a device is taken for the one request and given straight back.
     * Every device of the backend is tried in turn: a bench with several inputs
     * has no reason to refuse a write because the FIRST of them happens to be
     * somebody else's.
     *
     * @return {@code work}'s answer, or null when there is no session, no device
     *         to take, or every device is held elsewhere - {@code work} does not
     *         run then, because a write that cannot be locked must not be
     *         attempted.  A bench that vanished mid-request is that same null and
     *         never a throw: the caller is a settings panel on a dialog's OK, and
     *         an unreachable server is a value it cannot write, not a crash
     */
    public <T> T withDeviceLock(Supplier<T> work) {
        NetConnection open = connection;
        if (open == null) {
            if (log.isWarnEnabled()) {
                log.warn("net backend: no session to {} on - the write is not sent",
                        remoteBackend);
            }
            return null;
        }
        if (!held.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("net backend: writing under the {} lock(s) this session already "
                        + "holds - nothing is taken and nothing is given back", held.size());
            }
            return work.get();
        }
        for (DeviceRef candidate : lockCandidates()) {
            if (!(candidate instanceof NetDeviceRef ref)) {
                continue;
            }
            NetMessage taken;
            try {
                taken = open.request(ref.into(open.newRequest(MessageType.DEVICE_ACQUIRE)));
            } catch (RuntimeException e) {
                // The session itself is in trouble; the next device would fail
                // exactly the same way.
                if (log.isWarnEnabled()) {
                    log.warn("net backend: taking {} failed: {}", ref, e.toString());
                }
                return null;
            }
            if (!taken.isOk()) {
                if (log.isInfoEnabled()) {
                    log.info("net backend: {} is not free ({}) - trying the next device",
                            ref, taken.getError());
                }
                continue;
            }
            lockTaken(ref);
            try {
                return work.get();
            } finally {
                // Given back even when the write blew up: a lock left behind is a
                // device no other client can ever take again.
                releaseDevice(ref);
            }
        }
        if (log.isWarnEnabled()) {
            log.warn("net backend: no device of {} could be taken, so its settings "
                    + "cannot be written", remoteBackend);
        }
        return null;
    }

    /**
     * Runs {@code work} while this session holds the exclusive lock on EXACTLY
     * {@code ref} - the rule spec 4.3 puts on {@code device.setCalibration},
     * which "requires the device lock" because it changes what every measurement
     * on THAT device means.
     *
     * <p>The sibling above takes any device of the backend, which is spec 4.6's
     * weaker rule for a backend's own settings.  Used here it would take the
     * first free input and leave an output write to be refused
     * {@code NOT_LOCKED} - a calibration the operator watched succeed and the
     * bench never stored.
     *
     * <p><b>A lock this session already holds is used and NOT given back</b>, for
     * the reason the sibling gives at length: the server answers a repeat acquire
     * by the same session with success, so releasing afterwards would hit spec
     * 4.3's "also closes any open stream/generator on it" and tear down the very
     * capture the operator is calibrating against.  Membership is by ref equality,
     * which deliberately ignores the calibration a ref carries - otherwise this
     * write would stop recognising its own lock the moment the previous write
     * changed the value.
     *
     * @return {@code work}'s answer, or null when there is no session or the
     *         device is held elsewhere - {@code work} does not run then, because a
     *         write that cannot be locked must not be attempted
     */
    public <T> T withDeviceLock(NetDeviceRef ref, Supplier<T> work) {
        NetConnection open = connection;
        if (open == null) {
            if (log.isWarnEnabled()) {
                log.warn("net backend: no session to write {} on - nothing is sent", ref);
            }
            return null;
        }
        if (held.contains(ref)) {
            if (log.isDebugEnabled()) {
                log.debug("net backend: writing under the lock this session already "
                        + "holds on {} - nothing is taken and nothing is given back", ref);
            }
            return work.get();
        }
        NetMessage taken;
        try {
            taken = open.request(ref.into(open.newRequest(MessageType.DEVICE_ACQUIRE)));
        } catch (RuntimeException e) {
            if (log.isWarnEnabled()) {
                log.warn("net backend: taking {} failed: {}", ref, e.toString());
            }
            return null;
        }
        if (!taken.isOk()) {
            if (log.isWarnEnabled()) {
                log.warn("net backend: {} is not free ({}), so it cannot be written",
                        ref, taken.getError());
            }
            return null;
        }
        lockTaken(ref);
        try {
            return work.get();
        } finally {
            // Given back even when the write blew up: a lock left behind is a
            // device no other client can ever take again.
            releaseDevice(ref);
        }
    }

    /** The devices a settings write may take, inputs first: spec 4.6 accepts
     *  either direction, and an input is the one every backend has. */
    private List<DeviceRef> lockCandidates() {
        List<DeviceRef> candidates = new ArrayList<>(listInputDevices());
        candidates.addAll(listOutputDevices());
        return candidates;
    }

    /** Records that this session took {@code ref}'s lock.  The request itself
     *  belongs to whoever made it - a capture has to refuse with the server's own
     *  error code - so the register is told rather than asked. */
    void lockTaken(NetDeviceRef ref) {
        held.add(ref);
    }

    /** Gives one of this session's device locks back (spec 4.3: the release also
     *  closes anything still open on it), best effort: this is a teardown step,
     *  and a lock left behind is a device no other client can take until the
     *  session dies. */
    void releaseDevice(NetDeviceRef ref) {
        held.remove(ref);
        NetConnection session = connection;
        if (session == null) {
            return;
        }
        try {
            session.request(ref.into(session.newRequest(MessageType.DEVICE_RELEASE)));
        } catch (RuntimeException e) {
            if (log.isWarnEnabled()) {
                log.warn("net backend: releasing {} failed: {}", ref, e.toString());
            }
        }
    }

    // -------------------------------------------------------------------------
    // The remote generator - spec 4.5
    // -------------------------------------------------------------------------

    /**
     * Spec 4.5: takes the output device ("all {@code gen.*} require the
     * output-device lock") and opens the server-side playback, silent until
     * {@link #startGenerator()}.
     *
     * <p>The lock is given back before any throw: an open that failed must cost
     * nothing, and a lock left behind is a DAC no other client can ever take
     * again.  A generator that was still open is closed first, so the far end
     * never ends up with two lanes on one connection.
     *
     * <p>The GRANTED rate comes back, exactly as {@link NetPcmCapture#open()}
     * re-pins to the one {@code capture.open} answered with: spec 4.5 puts
     * {@code rate} in the {@code gen.open} response because the bench's DAC has
     * the last word, and a caller that went on snapping and counting sweep
     * samples against the rate it ASKED for would command one grid while the
     * bench emitted another.
     */
    @Override
    public int openGenerator(DeviceRef device, int sampleRate, int bitDepth,
            double ditherBits, OutputChannels channels) {
        NetConnection open = requireConnection();
        NetDeviceRef ref = remoteRef(device);
        closeGenerator();
        NetMessage taken = open.request(
                ref.into(open.newRequest(MessageType.DEVICE_ACQUIRE)));
        if (!taken.isOk()) {
            throw open.refusal("cannot take " + ref, taken);
        }
        lockTaken(ref);
        genDevice = ref;
        NetMessage opened;
        try {
            opened = open.request(ref.into(open.newRequest(MessageType.GEN_OPEN))
                    .put(NetFields.RATE, sampleRate)
                    .put(NetFields.BITS, bitDepth)
                    .put(NetFields.DITHER_BITS, ditherBits)
                    .put(NetFields.OUTPUT_CHANNELS, channels.name()));
        } catch (RuntimeException e) {
            releaseGenDevice();
            throw e;
        }
        if (!opened.isOk()) {
            releaseGenDevice();
            throw open.refusal("cannot open a generator on " + ref, opened);
        }
        genId = opened.getData().path(NetFields.GEN_ID).asInt(NO_GEN);
        genEndedBelow.set(null);   // a fresh lane owes nothing to the old one's death
        genFileState = FileState.IDLE;
        fileError.set(null);
        int granted = opened.getData().path(NetFields.RATE).asInt(sampleRate);
        if (log.isInfoEnabled()) {
            log.info("net gen {}: open on {} at {} Hz / {} bit, dither {} bit, lanes {}",
                    genId, ref, granted, bitDepth, ditherBits, channels);
        }
        return granted;
    }

    /** Spec 4.5: emission begins.  Waited for, unlike the configuration pushes:
     *  the operator pressed Play, and "did the tone start" is the one answer the
     *  pane cannot do without. */
    @Override
    public boolean isGeneratorOpen() {
        return genId != NO_GEN && connection != null;
    }

    @Override
    public void startGenerator() {
        command(MessageType.GEN_START);
    }

    /** Spec 4.5: emission stops; the line stays open. */
    @Override
    public void stopGenerator() {
        command(MessageType.GEN_STOP);
    }

    /**
     * Spec 4.5: stops and gives the far end's playback line back, then the device
     * lock with it.
     *
     * <p>Best effort and idempotent, because it is a teardown step: the close is
     * attempted whatever the state of the session, and the lock is released
     * whether or not the close succeeded - the alternative is a DAC nobody can
     * take until the far end's own keepalive notices.
     */
    @Override
    public void closeGenerator() {
        int open = genId;
        genId = NO_GEN;
        genState = State.IDLE;
        genFileState = FileState.IDLE;
        NetConnection session = connection;
        if (open != NO_GEN && session != null) {
            try {
                session.request(session.newRequest(MessageType.GEN_CLOSE)
                        .put(NetFields.GEN_ID, open));
            } catch (RuntimeException e) {
                if (log.isWarnEnabled()) {
                    log.warn("net gen {}: close failed: {}", open, e.toString());
                }
            }
        }
        releaseGenDevice();
    }

    @Override
    public void setForm(GenSignalForm form) {
        config(NetFields.FORM, form.name());
    }

    @Override
    public void setFrequency(double hz) {
        config(NetFields.FREQUENCY, hz);
    }

    @Override
    public void setDualToneFrequency2(double hz) {
        config(NetFields.DUAL, block().put(NetFields.FREQUENCY2, hz));
    }

    @Override
    public void setDualToneAmplitudes(double amp1Pct, double amp2Pct) {
        config(NetFields.DUAL, block().put(NetFields.AMP1_PCT, amp1Pct)
                .put(NetFields.AMP2_PCT, amp2Pct));
    }

    @Override
    public void setAmplitudeVrms(double vrms) {
        config(NetFields.AMPLITUDE_VRMS, vrms);
    }

    @Override
    public void setDacFsVoltageAmpl(double volts) {
        config(NetFields.DAC_FS_VOLTAGE_AMPL, volts);
    }

    @Override
    public void setRightLaneScale(double scale) {
        config(NetFields.RIGHT_LANE_SCALE, scale);
    }

    @Override
    public void setDitherBits(double bits) {
        config(NetFields.DITHER_BITS, bits);
    }

    @Override
    public void setRectangleDuty(double dutyFrac) {
        config(NetFields.RECTANGLE_DUTY, dutyFrac);
    }

    @Override
    public void setTriangleDuty(double dutyFrac) {
        config(NetFields.TRIANGLE_DUTY, dutyFrac);
    }

    @Override
    public void setSweepFreqStart(double hz) {
        config(NetFields.SWEEP, block().put(NetFields.F0, hz));
    }

    @Override
    public void setSweepFreqEnd(double hz) {
        config(NetFields.SWEEP, block().put(NetFields.F1, hz));
    }

    @Override
    public void setSweepDurationSamples(int samples) {
        config(NetFields.SWEEP, block().put(NetFields.DURATION_SAMPLES, samples));
    }

    @Override
    public void setSweepLeadInSamples(int samples) {
        config(NetFields.SWEEP, block().put(NetFields.LEAD_IN_SAMPLES, samples));
    }

    @Override
    public void setSweepFadeInSamples(int samples) {
        config(NetFields.SWEEP, block().put(NetFields.FADE_IN_SAMPLES, samples));
    }

    @Override
    public void setSweepFadeOutSamples(int samples) {
        config(NetFields.SWEEP, block().put(NetFields.FADE_OUT_SAMPLES, samples));
    }

    @Override
    public void setSweepLoop(boolean loop) {
        config(NetFields.SWEEP, block().put(NetFields.LOOP, loop));
    }

    @Override
    public void applyCompensation(double[] ampRatios, int[] hNums, double[] phiInits) {
        ObjectNode tables = block();
        tables.set(NetFields.AMP_RATIOS, array(ampRatios));
        tables.set(NetFields.H_NUMS, array(hNums));
        tables.set(NetFields.PHI_INITS, array(phiInits));
        config(NetFields.COMPENSATION, tables);
    }

    @Override
    public void applyDualToneCompensation(double[] ampRatios, int[] aCoef, int[] bCoef,
            double[] phiInits) {
        ObjectNode tables = block();
        tables.set(NetFields.AMP_RATIOS, array(ampRatios));
        tables.set(NetFields.A_COEF, array(aCoef));
        tables.set(NetFields.B_COEF, array(bCoef));
        tables.set(NetFields.PHI_INITS, array(phiInits));
        config(NetFields.DUAL_COMPENSATION, tables);
    }

    @Override
    public void clearCompensation() {
        config(NetFields.CLEAR_COMPENSATION, true);
    }

    /** Spec 4.5's {@code gen.fftGrid}: the analyzer's grid, so the far end snaps
     *  with the same math the local path uses. */
    @Override
    public void fftGrid(int fftSize, boolean snapEnabled) {
        NetMessage request = gen(MessageType.GEN_FFT_GRID);
        if (request != null) {
            push(request.put(NetFields.FFT_SIZE, fftSize)
                    .put(NetFields.SNAP_ENABLED, snapEnabled));
        }
    }

    @Override
    public void trim(double hz) {
        NetMessage request = gen(MessageType.GEN_TRIM);
        if (request != null) {
            push(request.put(NetFields.HZ, hz));
        }
    }

    @Override
    public void trim2(double hz) {
        NetMessage request = gen(MessageType.GEN_TRIM2);
        if (request != null) {
            push(request.put(NetFields.HZ, hz));
        }
    }

    @Override
    public void trimReset() {
        push(gen(MessageType.GEN_TRIM_RESET));
    }

    /** Spec 4.5: the last {@code ev.gen.state} and nothing else - see
     *  {@link RemoteGenerator#state()} for why a command is never taken for the
     *  state it asked for. */
    @Override
    public State state() {
        return genState;
    }

    @Override
    public Throwable takeEndedFromBelowForReport() {
        return genEndedBelow.getAndSet(null);
    }

    /* -------------------------- playing a file on the bench -------------------------- */

    /**
     * Spec §3 + 4.5: the bytes go up over {@code PUT /files}, then
     * {@code gen.playFile} tells the lane already open to render them.
     *
     * <p>Two steps, one operator action, so BOTH failures have to arrive as one
     * refusal - the upload's and the command's.  The size gate lives in
     * {@link FileUpload} against the protocol's own constant, and fires before a
     * socket is opened.
     */
    @Override
    public void playFile(byte[] content, String mimeType, boolean loop) {
        NetConnection session = connection;
        int open = genId;
        if (session == null || open == NO_GEN) {
            throw new IllegalStateException(
                    MessageType.GEN_PLAY_FILE.getWire() + " on a bench with no open generator");
        }
        fileError.set(null);
        URI base = uploadBase == null ? session.httpBase() : uploadBase;
        String fileId;
        try {
            fileId = new FileUpload(session.getCodec(), session.getProxy())
                    .put(base, content, mimeType);
        } catch (FileUpload.TooLargeException e) {
            // Over the protocol's limit and refused before a socket was opened -
            // rethrown as the SPI's own so the controller, which sees only
            // RemoteGenerator, can read the two numbers out of it.
            throw new FileTooLargeException(e.getMessage(), e.getBytes(), e.getLimitBytes());
        } catch (IOException e) {
            // The bench's own words go to the log; the caller says it in the
            // operator's language (the device-failure rule).  The base named here
            // is the one the bytes really went to, not the session's - they differ
            // under the test seam, and a log line that lied about which would cost
            // a debugging session.
            log.warn("net gen: uploading the file to {} failed: {}", base, e.toString());
            throw new IllegalStateException(e.getMessage(), e);
        }
        NetMessage answer = session.request(session.newRequest(MessageType.GEN_PLAY_FILE)
                .put(NetFields.GEN_ID, open)
                .put(NetFields.FILE_ID, fileId)
                .put(NetFields.LOOP, loop));
        if (!answer.isOk()) {
            throw session.refusal(MessageType.GEN_PLAY_FILE.getWire()
                    + " failed on " + genDevice, answer);
        }
        // Not a state: the bench's own ev.gen.state is what turns the indicator
        // on, exactly as it is for the tone.  Setting it here would report a file
        // playing before the lane had accepted it.
        if (log.isInfoEnabled()) {
            log.info("net gen {}: playing {} ({} bytes, loop={})", open, fileId,
                    content.length, loop);
        }
    }

    @Override
    public void stopFile() {
        NetMessage request = gen(MessageType.GEN_STOP_FILE);
        NetConnection session = connection;
        if (request == null || session == null) {
            return;                     // nothing open - the idempotent teardown
        }
        NetMessage answer = session.request(request);
        if (!answer.isOk() && log.isWarnEnabled()) {
            log.warn("net gen: the bench refused {}: {}",
                    MessageType.GEN_STOP_FILE.getWire(), answer.getError());
        }
        // The far end pushes the cleared file state; nothing is assumed here.
    }

    /** Spec 4.5's {@code gen.config} partial update, one field: pushed like every
     *  other live setter, because there is nothing in the answer to act on and a
     *  checkbox must not wait on a round trip. */
    @Override
    public void setFileLoop(boolean loop) {
        config(NetFields.FILE_LOOP, loop);
    }

    @Override
    public FileState fileState() {
        return genFileState;
    }

    @Override
    public Throwable takeFileErrorForReport() {
        return fileError.getAndSet(null);
    }

    /** One {@code ev.gen.state} (spec 4.5).  The three tone fields the desktop
     *  reads - what is emitting, and at which frequencies - plus the {@code file}
     *  block, which is how a file playing on the bench reaches the operator's
     *  indicator and how a non-looping one reports that it ran out. */
    private void cacheGenState(NetMessage event) {
        Integer id = event.optInt(NetFields.GEN_ID);
        if (id == null || id != genId) {
            // A state for a generator this manager does not hold: a lane it has
            // already closed, or one the far end tore down.  Taking it would
            // report a tone nobody here asked for.
            return;
        }
        Double emitHz = event.optDouble(NetFields.EMIT_HZ);
        Double emit2Hz = event.optDouble(NetFields.EMIT2_HZ);
        genState = new State(Boolean.TRUE.equals(event.optBoolean(NetFields.RUNNING)),
                emitHz == null ? 0.0 : emitHz, emit2Hz == null ? 0.0 : emit2Hz);
        cacheFileState(event);
        if (log.isDebugEnabled()) {
            log.debug("net gen {}: {} file={}", id, genState, genFileState);
        }
    }

    /** The {@code file} sub-object of {@code ev.gen.state}: absent on a bench
     *  that never played one, which reads as idle rather than as a change. */
    private void cacheFileState(NetMessage event) {
        JsonNode file = event.getNode(NetFields.FILE);
        if (file == null || file.isMissingNode() || file.isNull()) {
            return;
        }
        genFileState = new FileState(file.path(NetFields.PLAYING).asBoolean(false),
                file.path(NetFields.FINISHED).asBoolean(false));
    }

    /** A {@code gen.*} request carrying the open handle, or null when no
     *  generator is open - which is the remote twin of the local controller's
     *  "no-op unless something is playing", and is why every setter above can be
     *  one line. */
    private NetMessage gen(MessageType type) {
        int open = genId;
        NetConnection session = connection;
        if (open == NO_GEN || session == null) {
            return null;
        }
        return session.newRequest(type).put(NetFields.GEN_ID, open);
    }

    /** One top-level {@code gen.config} field (spec 4.5's partial update: only
     *  the fields present are applied). */
    private void config(String field, double value) {
        NetMessage request = gen(MessageType.GEN_CONFIG);
        if (request != null) {
            push(request.put(field, value));
        }
    }

    private void config(String field, boolean value) {
        NetMessage request = gen(MessageType.GEN_CONFIG);
        if (request != null) {
            push(request.put(field, value));
        }
    }

    private void config(String field, String value) {
        NetMessage request = gen(MessageType.GEN_CONFIG);
        if (request != null) {
            push(request.put(field, value));
        }
    }

    /** One {@code gen.config} sub-object - the {@code dual}, {@code sweep} and
     *  compensation blocks, which carry only the fields their setter changed for
     *  exactly the reason the top-level update does: re-sending a sweep's
     *  duration re-renders the chirp and restarts it at sample 0, underneath
     *  whoever was recording it. */
    private void config(String field, ObjectNode value) {
        NetMessage request = gen(MessageType.GEN_CONFIG);
        if (request != null) {
            push(request.put(field, value));
        }
    }

    /**
     * Sends a generator command WITHOUT waiting for its answer, logging a refusal
     * when one comes back.
     *
     * <p>The configuration pushes and the trims run this way on purpose: they are
     * driven by sliders and by the frequency-lock loop, at rates where a blocking
     * round trip would freeze the user interface for the length of the bench's
     * worst reply - and there is nothing in the answer to act on, because the
     * generator's state comes back as {@code ev.gen.state} either way.
     *
     * @param request the message, or null when no generator is open - then
     *                nothing is sent, which is the no-op a local setter performs
     *                when nothing is playing
     */
    private void push(NetMessage request) {
        NetConnection session = connection;
        if (request == null || session == null) {
            return;
        }
        session.send(request).whenComplete((answer, failure) -> {
            if (failure != null) {
                if (log.isWarnEnabled()) {
                    log.warn("net gen: {} was not delivered: {}", request.getT(),
                            failure.toString());
                }
            } else if (!answer.isOk() && log.isWarnEnabled()) {
                log.warn("net gen: the bench refused {}: {}", request.getT(),
                        answer.getError());
            }
        });
    }

    /** {@code gen.start} and {@code gen.stop}: the same message shape with
     *  nothing in it but the handle, and an answer worth waiting for. */
    private void command(MessageType type) {
        NetMessage request = gen(type);
        NetConnection session = connection;
        if (request == null || session == null) {
            throw new IllegalStateException(type.getWire()
                    + " on a bench with no open generator");
        }
        NetMessage answer = session.request(request);
        if (!answer.isOk()) {
            throw session.refusal(type.getWire() + " failed on " + genDevice, answer);
        }
    }

    /** Gives the generator's output device back, best effort and exactly once -
     *  through the one release the whole backend uses, so the lock register and
     *  the wire can never disagree. */
    private void releaseGenDevice() {
        NetDeviceRef device = genDevice;
        genDevice = null;
        if (device != null) {
            releaseDevice(device);
        }
    }

    /** A fresh JSON object for a {@code gen.config} sub-block. */
    private ObjectNode block() {
        return JsonNodeFactory.instance.objectNode();
    }

    private ArrayNode array(double[] values) {
        ArrayNode node = JsonNodeFactory.instance.arrayNode(values.length);
        for (double value : values) {
            node.add(value);
        }
        return node;
    }

    private ArrayNode array(int[] values) {
        ArrayNode node = JsonNodeFactory.instance.arrayNode(values.length);
        for (int value : values) {
            node.add(value);
        }
        return node;
    }

    // -------------------------------------------------------------------------
    // The catalogue
    // -------------------------------------------------------------------------

    /** The SPI's rule that enumeration "must degrade gracefully ... never a throw"
     *  costs nothing here: the catalogue is a field, so a manager nobody has
     *  connected - and one whose bench went away - answers the empty list a
     *  combo already knows how to show, with no request to fail. */
    private List<DeviceRef> listDevices(boolean input) {
        List<DeviceRef> found = new ArrayList<>();
        for (RemoteDevice remote : devices) {
            if (remote.ref().isInput() == input) {
                found.add(remote.ref());
            }
        }
        return found;
    }

    /**
     * Rebuilds the catalogue from a {@code backends} array (spec 4.3), keeping
     * only the backend this manager was pointed at.
     *
     * <p>The event carries the FULL {@code devices.list} payload, so it is taken
     * as it comes - including an array that names no backend at all, which is a
     * server with nothing left to offer and not a message to be second-guessed.
     * (It used to be ignored, because a server still finishing its first
     * enumeration broadcast an empty one; the server now enumerates before its
     * transports accept anything, and the selection no longer waits on this event
     * to fill the combos - {@link #connect} is handed the catalogue.)
     *
     * <p><b>And it is where a device in USE is noticed leaving.</b>  Swapping the
     * list was all this did, at DEBUG, empty array included - so a bench that
     * dropped the very analyzer the operator was measuring on changed nothing on
     * screen, and the generator and the scope carried on against hardware that
     * was gone (the server logged the QA40x session discarded and broadcast the
     * change; the client did nothing).  Only this client knows what it holds,
     * so only this client can ask the question, and
     * it asks it here, of the new list.
     */
    private void cache(JsonNode backends) {
        String selected = remoteBackend;
        if (selected == null) {
            return;
        }
        List<RemoteDevice> found = new ArrayList<>();
        for (JsonNode backend : backends) {
            if (selected.equals(backend.path(NetFields.BACKEND).asText())) {
                found.addAll(devicesOf(backend, selected));
            }
        }
        List<RemoteDevice> previous = devices;
        devices = List.copyOf(found);
        if (log.isDebugEnabled()) {
            log.debug("net backend: {} remote {} device(s)", found.size(), selected);
        }
        reportRecalibrated(previous, found);
        reportHeldButGone(found, selected);
    }

    /**
     * Tells the client half about every device whose stored calibration MOVED
     * between two catalogues - spec 4.3 re-sends the whole list on an accepted
     * {@code device.setCalibration} / {@code device.setCard} / {@code setActiveRange}
     * and on a QA40x range change, so the new pair is already in hand here.
     *
     * <p>Without this the payload only ever refreshed a cache: another operator
     * recalibrating the bench device this client is measuring on, or moving the
     * analyzer's attenuator, would change what every reading MEANS while this
     * client went on scaling by the old full scale until the next device open.
     * That is exactly the failure the broadcast exists to prevent, argued from the
     * server's side and never wired on ours.
     *
     * <p>Only a real CHANGE is reported: a lock change or a hot-plug re-sends the
     * same calibration, and re-applying it on every event would be a listener call
     * per two seconds for nothing.  A device that was not in the previous
     * catalogue is not a change either - nothing was in force for it yet.
     */
    private void reportRecalibrated(List<RemoteDevice> previous, List<RemoteDevice> current) {
        if (previous.isEmpty()) {
            return;
        }
        for (RemoteDevice now : current) {
            for (RemoteDevice before : previous) {
                if (!before.ref().equals(now.ref())
                        || Objects.equals(before.ref().calibration(), now.ref().calibration())) {
                    continue;
                }
                calibrationChanged(now.ref());
                break;
            }
        }
    }

    /** The bench's stored calibration for a device MOVED - see
     *  {@link NetFaultListener#calibrationChanged}.  Logged even with nobody
     *  subscribed: a full scale that changed under a running measurement is the
     *  kind of thing a bench log has to be able to explain afterwards. */
    @Override
    public void calibrationChanged(DeviceRef device) {
        if (log.isInfoEnabled()) {
            log.info("net backend: the bench recalibrated {} - {} now", device,
                    device.calibration());
        }
        NetFaultListener listener = faultListener;
        if (listener != null) {
            listener.calibrationChanged(device);
        }
    }

    /**
     * Reports every device this session holds that the new catalogue no longer
     * offers, and forgets its lock.
     *
     * <p>Only the SELECTED backend's own refs are judged.  A catalogue describes
     * one backend, and this manager's catalogue can legitimately be pointed at
     * another one than the session committed to - {@link #preview} re-points it
     * without sending anything - so a held ref of a different backend is simply
     * not described here, and reading its absence as a death would fire a device
     * error at every operator who browsed the backend combo.
     *
     * <p>The lock goes out of the register but is NOT released on the wire: the
     * device it named does not exist any more, so the release could only be
     * answered {@code DEVICE_STALE}.  Forgetting it matters all the same -
     * {@code withDeviceLock} would otherwise go on believing this session holds a
     * lock, and write a backend's settings under it.
     */
    private void reportHeldButGone(List<RemoteDevice> catalogue, String selected) {
        for (NetDeviceRef ref : Set.copyOf(held)) {
            if (!selected.equals(ref.remoteBackend()) || offers(catalogue, ref)) {
                continue;
            }
            held.remove(ref);
            deviceGone(ref);
        }
    }

    /** Whether {@code catalogue} still names {@code ref} - by the ref's own
     *  identity, which deliberately ignores the calibration and card binding a
     *  refresh may have changed (see {@link NetDeviceRef}). */
    private boolean offers(List<RemoteDevice> catalogue, NetDeviceRef ref) {
        for (RemoteDevice remote : catalogue) {
            if (remote.ref().equals(ref)) {
                return true;
            }
        }
        return false;
    }

    /** The devices of ONE backend entry of spec 4.3 - the shape
     *  {@code backend.select} answers with and the shape each element of a
     *  {@code devices.list} array has, so both paths read it the same way. */
    private List<RemoteDevice> devicesOf(JsonNode backend, String name) {
        List<RemoteDevice> found = new ArrayList<>();
        for (JsonNode device : backend.path(NetFields.DEVICES)) {
            found.add(read(device, name));
        }
        return List.copyOf(found);
    }

    /** One device object of spec 4.3, whole: the inlined formats, the two
     *  per-device flags the payload carries for the UI's sake, and the
     *  calibration and card binding the bench stores for it. */
    private RemoteDevice read(JsonNode device, String backend) {
        NetDeviceRef ref = new NetDeviceRef(device.path(NetFields.INDEX).asInt(),
                device.path(NetFields.NAME).asText(),
                device.path(NetFields.DESCRIPTION).asText(),
                device.path(NetFields.VENDOR).asText(),
                device.path(NetFields.INPUT).asBoolean(), backend,
                calibrationOf(device),
                // JSON null, an absent field (a server older than v1.1) and a
                // blank all mean the same: nobody has chosen a card there.
                emptyToNull(device.path(NetFields.CARD).asText(null)),
                // Absent reads as false - a server that does not say the values are
                // the device's own is a server whose devices are calibrated the
                // ordinary way, which is what every pre-v1.1 bench is.
                device.path(NetFields.CAL_FROM_DEVICE).asBoolean(false));
        List<AudioFormat> formats = new ArrayList<>();
        for (JsonNode format : device.path(NetFields.FORMATS)) {
            int rate = format.path(NetFields.RATE).asInt();
            int bits = format.path(NetFields.BITS).asInt();
            int channels = format.path(NetFields.CHANNELS).asInt(STEREO);
            formats.add(new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, bits,
                    channels, channels * (bits / 8), rate, false));
        }
        JsonNode lock = device.path(NetFields.LOCK);
        return new RemoteDevice(ref, List.copyOf(formats),
                device.path(NetFields.HAS_BIT_DEPTH).asBoolean(),
                lock.isObject() ? lock.path(NetFields.BY).asText(null) : null);
    }

    /** A blank card name is no binding - the one place the wire's "nothing chosen"
     *  answers (null, absent, "") are folded into the one the model uses. */
    private String emptyToNull(String card) {
        return card == null || card.isEmpty() ? null : card;
    }

    /**
     * Spec 4.3's {@code cal}: the full-scale RMS volts the SERVER stores for this
     * device, or null when it has none - the device is then uncalibrated on this
     * client, which warns rather than quietly measuring against a default.
     *
     * <p>Null-safe by the shape of the answer, not by a version check: JSON null
     * (no card), an absent field (a server older than v1.1) and a value that is
     * not an object all mean the same thing here, and none of them may be read as
     * a full scale of zero - which would make every measurement on that device
     * infinite.
     *
     * <p>An object that is EMPTY, or whose two numbers are missing or not above
     * zero, is the same answer for the same reason.  {@code JsonNode.asDouble()}
     * answers 0.0 for an absent or unparsable field, and 0 V full scale is not a
     * calibration - it is the divisor that turns every reading into infinity - so
     * the shape check has to reach the values, not stop at the braces.
     */
    private DeviceCalibration calibrationOf(JsonNode device) {
        JsonNode cal = device.path(NetFields.CAL);
        if (!cal.isObject()) {
            return null;
        }
        double left = cal.path(NetFields.FS_RMS_LEFT).asDouble();
        double right = cal.path(NetFields.FS_RMS_RIGHT).asDouble();
        if (left <= 0.0 || right <= 0.0) {
            if (log.isWarnEnabled()) {
                log.warn("net backend: '{}' came with an unusable calibration ({} / {} "
                        + "Vrms) - falling back to the local defaults",
                        device.path(NetFields.NAME).asText(), left, right);
            }
            return null;
        }
        return new DeviceCalibration(left, right);
    }

    private NetConnection requireConnection() {
        NetConnection open = connection;
        if (open == null) {
            throw new IllegalStateException(
                    "no Phonalyser server is connected, so there is no remote device "
                            + "to open");
        }
        return open;
    }

    private NetDeviceRef remoteRef(DeviceRef device) {
        if (device instanceof NetDeviceRef ref) {
            return ref;
        }
        throw new IllegalArgumentException(device + " is not a remote device");
    }

    /**
     * One device of the remote backend: the ref every request names it with, the
     * formats spec 4.3 inlined with it, whether it lets a client choose a sample
     * width, and who holds its lock ({@code null} when it is free).
     *
     * <p>The last two are kept because this manager is the only holder of the
     * parsed catalogue: the depth selector and the graying of locked devices
     * would otherwise have to walk the payload this method has already walked.
     */
    private record RemoteDevice(NetDeviceRef ref, List<AudioFormat> formats,
            boolean hasBitDepth, String lockedBy) {
    }
}
