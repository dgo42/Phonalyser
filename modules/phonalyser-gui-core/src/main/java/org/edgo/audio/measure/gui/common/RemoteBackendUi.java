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

package org.edgo.audio.measure.gui.common;

import java.util.List;
import java.util.Map;

import org.eclipse.swt.widgets.Shell;

import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.sound.DeviceRef;

/**
 * Backends that live on ANOTHER machine, offered to the Preferences dialog by
 * the module that owns their UI.  Found through the service loader; see
 * {@code RemoteBackendRegistry}.
 *
 * <p><b>Not {@link BackendSettingsUi}.</b>  That contract answers "does THIS
 * backend have settings of its own" and is keyed by backend type - its button
 * appears and disappears with the combo selection.  This one is about which
 * backends exist at all: its button is permanent (a server is chosen BEFORE any
 * backend of it can be), and its entries are added to the combo rather than
 * hung off an entry already in it.  Two different questions, two contracts.
 *
 * <p><b>Why it lives in the GUI module and not in core</b> - the same reason as
 * {@link BackendSettingsUi}: {@link #openServerList(Shell)} traffics in a
 * toolkit type, and a headless build must not grow one.  The implementing
 * module sits ABOVE this one and is discovered, never compiled in.
 *
 * <p>Implementations need a public no-argument constructor and must not build
 * widgets or open a connection while constructing: the service loader
 * instantiates them when the registry is first indexed, which may be long before
 * the operator asks for a server.  Registering a settings block IS expected
 * there - the remembered servers have to be restored before the list can show
 * them.
 */
public interface RemoteBackendUi {

    /**
     * One backend on one reachable server, as the backend combo shows it.
     *
     * @param key   the selection to store and to enumerate through - a remote
     *              {@link BackendKey}, carrying both the server and the backend
     * @param label what the operator reads, "&lt;server name&gt; -&gt; &lt;backend&gt;"
     */
    record Entry(BackendKey key, String label) {
    }

    /**
     * Opens the server list modally over {@code parent} and returns once the
     * operator has closed it - connecting or disconnecting on the way, which is
     * why the caller must re-read {@link #entries()} afterwards rather than
     * assume the list it drew is still the list that exists.
     */
    void openServerList(Shell parent);

    /** Every remote backend currently reachable - empty when no server is
     *  connected, which is also what a build with no bench in the room sees. */
    List<Entry> entries();

    /**
     * Points the remote backend at {@code key} and drops whatever device
     * catalogue was cached for it, so the next enumeration is a fresh one.  That
     * makes this both "the operator picked this bench" and "re-scan the bench
     * they are already on" - the same round trip either way.
     *
     * <p>Selecting the bench the session is ALREADY committed to is a no-op that
     * answers true: the commit is idempotent so a caller may re-affirm the
     * selection (the Preferences OK does) without tearing down the streams and
     * the generator lane running on it.
     *
     * @return false when the bench could not be reached, in which case the
     *         caller must not leave the selection on it
     */
    boolean select(BackendKey key);

    /**
     * Fills the device catalogue for {@code key} WITHOUT committing the
     * selection - the staged twin of {@link #select}, for the Preferences
     * dialog, whose rule is that nothing the operator does before OK may touch
     * the live session.  The enumeration the catalogue serves afterwards is a
     * snapshot of {@code key}'s devices; the session's committed backend, its
     * open streams and its generator lane are all left exactly as they are.
     *
     * <p>{@link #select} on the dialog's OK is what turns the shown selection
     * into the committed one.
     *
     * @return false when the bench could not be reached or does not serve
     *         {@code key}'s backend, in which case the caller must not leave the
     *         combo on it
     */
    boolean preview(BackendKey key);

    /**
     * Asks the connected server for its backend list again, WITHOUT blocking
     * the caller: the fresh composition arrives as
     * {@code REMOTE_BACKENDS_CHANGED} - published only when it actually
     * differs - through which every open combo re-composes.  This is how a
     * "Scan devices" click, and the Preferences dialog opening, learn that an
     * analyzer was plugged into the bench (or unplugged) after the session was
     * opened, without the dialog waiting on the wire.
     *
     * <p>A no-op without a session.  Local backends need no counterpart: their
     * device lists are enumerated fresh on every fill.  Default no-op for
     * implementations with nothing remote to ask.
     */
    default void refreshEntries() { }

    /**
     * Connects to the server at {@code hostColonPort} ({@code host} or
     * {@code host:port}, the default port when none is given) - the address
     * form, for a caller that has no list row to point at.
     *
     * <p>This exists for UNATTENDED runs: the servers dialog is how an operator
     * chooses a bench, and a dialog is exactly what a scripted run cannot use.
     * The address is all such a run has, since a remembered server is keyed by
     * an installation UUID only the server itself can supply.
     *
     * @return null when the session is up, else the reason to show - the same
     *         contract the list's Connect answers with
     */
    default String connectByAddress(String hostColonPort) {
        return "this build ships no net session";
    }

    /** Whether a server session is open right now. */
    default boolean isSessionConnected() {
        return false;
    }

    /** Ends the session (no-op when none is open). */
    default void disconnectSession() { }

    /**
     * The name of the client measuring on {@code device} right now, or
     * {@code null} when it is free - or is not a remote device at all.
     *
     * <p>Spec 4.3 sends the lock state with every device and asks clients to show
     * it ("clients gray out locked devices live"), because the alternative is what
     * the operator otherwise gets: a device that looks free, is picked, and only
     * refuses at open time with a code they never asked to see.  The device combos
     * are the one place this belongs - it is a property of the bench, not of the
     * selection.
     */
    default String lockedBy(DeviceRef device) {
        return null;
    }

    /**
     * One of the SELECTED backend's own requests, sent over the session that
     * reaches it - the QA40x extension of the net protocol (spec 4.6: telemetry,
     * ranges, calibration, the front-panel I2S port) is what this exists for.
     *
     * <p><b>Why it is untyped.</b>  The backends' own messages are the backends'
     * own business: a settings panel that knows a QA403 is here, and the session
     * that can reach it is there, and neither may compile against the other -
     * this module would otherwise have to name every backend extension there will
     * ever be.  So the caller names the message and the fields (from the
     * protocol's own constants, never typed by hand) and reads the answer back by
     * the same names.
     *
     * @param selection the remote backend to ask - a local one has no session and
     *                  answers null
     * @param request   the protocol message type, as it goes on the wire
     * @param fields    the request's fields; empty for a message that has none
     * @return the response fields, or {@code null} when there is no live session,
     *         the selection is not on it, or the server refused - a caller shows
     *         what it can and leaves the rest at its last value, exactly as it
     *         does for a device that would not answer
     */
    Map<String, Object> call(BackendKey selection, String request,
            Map<String, Object> fields);

    /**
     * The same request, sent while this client holds the remote backend's device
     * - what a request that CHANGES the hardware needs.
     *
     * <p><b>Why the caller says so and not the callee.</b>  The net protocol
     * requires the device lock for every write of a backend's own settings (spec
     * 4.6: "requires the QA40x lock (either direction) unless marked read-only"),
     * and refuses the write with {@code NOT_LOCKED} otherwise - which is exactly
     * what a settings panel hits, because the modules are stopped while the
     * Preferences dialog is open and nothing else is holding anything.  The seam
     * above is deliberately untyped, so it cannot know which messages are writes;
     * the caller does, and marks them by calling this instead.
     *
     * <p>The lock is taken for the request and given straight back, the way a
     * capture holds it for exactly as long as it streams - a client that kept it
     * would leave the bench's device unusable to everyone else.
     *
     * @return the response fields, or {@code null} when the bench could not be
     *         reached, would not give up the device, or refused the request - the
     *         same "show what you can" contract as {@link #call}
     */
    Map<String, Object> callLocked(BackendKey selection, String request,
            Map<String, Object> fields);
}
