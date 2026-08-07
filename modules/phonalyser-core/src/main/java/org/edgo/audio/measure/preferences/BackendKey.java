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

package org.edgo.audio.measure.preferences;

import org.edgo.audio.measure.enums.AudioBackendType;

/**
 * Which backend the application is set to, in the one form that can be written
 * to a file and used as a map key: a LOCAL backend is its plain enum name
 * ({@code "WASAPI"}), a REMOTE one also names the server it lives on
 * ({@code "net:<serverId>:<QA40X>"}).
 *
 * <p><b>Why the server has to be in the key.</b>  The same backend on two
 * different benches is two different sets of devices, sample rates and sample
 * widths, so {@code perBackend} needs one {@link BackendPrefs} entry per
 * (server, backend) pair - a bare enum could only ever hold the last one.  A
 * file written before remote benches existed carries a plain enum name, which
 * {@link #parse} reads as a local key, so nothing has to be migrated.
 *
 * <p><b>Two different questions, two accessors.</b>  {@link #type()} is what the
 * bench IS (a remote QA403 answers {@code QA40X}), which is what a
 * backend-semantic rule such as the QA40x equal-rate constraint must key on.
 * {@link #carrier()} is what the {@code AudioBackend} dispatcher must be handed
 * to REACH it, which for anything remote is {@link AudioBackendType#NET}.
 *
 * <p>The server id is the installation UUID of the net protocol's discovery
 * datagram; it carries no colon, which is what lets the three fields be split
 * apart again.  That is an INVARIANT, not an expectation - the id arrives over
 * the beacon and from a plain text file on the server, so a key that
 * {@link #parse} could not read back again is refused where it is built rather
 * than becoming an unreadable {@code backend:} line and a null selection later.
 *
 * @param serverId the server the backend runs on, or {@code null} for a local one
 * @param type     the backend itself - a remote selection names the REMOTE backend
 */
public record BackendKey(String serverId, AudioBackendType type) {

    /** What marks a key as remote, and what separates its fields. */
    private static final String REMOTE_PREFIX = "net:";
    private static final String SEPARATOR     = ":";
    /** A remote key is exactly {@code net} + server id + backend name. */
    private static final int REMOTE_PARTS   = 3;
    private static final int SERVER_ID_PART = 1;
    private static final int TYPE_PART      = 2;

    /**
     * Refuses a server id {@link #key()} could not be split apart again: an empty
     * one loses its field, and one containing the separator would move the split
     * point, so {@code parse} would answer a different key - or none at all,
     * leaving the whole selection unreadable.  An id that untrustworthy is a
     * server this installation cannot remember, and saying so here is the only
     * place where the caller still knows which server it was.
     *
     * <p>Also refuses a {@link AudioBackendType#isDualLevel() dual-level} carrier
     * as the TYPE: the two levels of a selection are (how it is reached; what it
     * is), and a carrier can only ever be the first - a key whose "what it is"
     * said {@code NET} would be the exact identity lie this type exists to end.
     */
    public BackendKey {
        if (serverId != null && (serverId.isEmpty() || serverId.contains(SEPARATOR))) {
            throw new IllegalArgumentException(
                    "A server id must not be empty or contain '" + SEPARATOR
                            + "': " + serverId);
        }
        if (type != null && type.isDualLevel()) {
            throw new IllegalArgumentException(
                    type + " is a carrier, not a backend a selection can BE");
        }
    }

    /** A local backend - the machine the application runs on. */
    public static BackendKey of(AudioBackendType type) {
        return new BackendKey(null, type);
    }

    /** One backend on one Phonalyser server, identified by that server's id. */
    public static BackendKey of(String serverId, AudioBackendType type) {
        return new BackendKey(serverId, type);
    }

    /**
     * Reads back what {@link #key()} wrote, and reads a bare enum name (every
     * preferences file written before remote benches existed) as a local key.
     *
     * @return {@code null} when {@code key} names no backend this build knows -
     *         a file from a newer release, or one edited by hand.  Callers keep
     *         their current value then, the way an unreadable enum is handled
     *         everywhere else in the preferences.
     */
    public static BackendKey parse(String key) {
        if (key == null) {
            return null;
        }
        if (!key.startsWith(REMOTE_PREFIX)) {
            AudioBackendType local = fromTypeName(key);
            return local == null ? null : new BackendKey(null, local);
        }
        String[] parts = key.split(SEPARATOR, REMOTE_PARTS);
        if (parts.length < REMOTE_PARTS || parts[SERVER_ID_PART].isEmpty()) {
            return null;
        }
        AudioBackendType remote = fromTypeName(parts[TYPE_PART]);
        return remote == null ? null : new BackendKey(parts[SERVER_ID_PART], remote);
    }

    /** A name that can be the TYPE of a key: known to this build and not a
     *  dual-level carrier (a hand-edited {@code backend: NET} line is as
     *  unreadable as an unknown name - callers keep their current value). */
    private static AudioBackendType fromTypeName(String name) {
        AudioBackendType type = AudioBackendType.fromNameOrNull(name);
        return type == null || type.isDualLevel() ? null : type;
    }

    /** This selection as one string - what preferences.yaml stores and what the
     *  per-backend settings are keyed by. */
    public String key() {
        return remote() ? REMOTE_PREFIX + serverId + SEPARATOR + type.name() : type.name();
    }

    /** Whether this backend lives on a Phonalyser server rather than on this
     *  machine. */
    public boolean remote() {
        return serverId != null;
    }

    /** The backend type the {@code AudioBackend} dispatcher must be handed to
     *  reach this selection: the type itself when local, and the
     *  {@link AudioBackendType#NET} carrier for every remote bench - the manager
     *  behind it knows which server and which remote backend it is routed at. */
    public AudioBackendType carrier() {
        return remote() ? AudioBackendType.NET : type;
    }
}
