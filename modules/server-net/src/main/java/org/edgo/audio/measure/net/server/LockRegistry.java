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

package org.edgo.audio.measure.net.server;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.edgo.audio.measure.enums.AudioBackendType;

import lombok.extern.log4j.Log4j2;

/**
 * Who owns which device.  Spec 4.3: a lock is per
 * {@code (backend, index, direction)}, held by exactly one connection, and
 * every streaming or generator call requires it; a connection's locks are
 * released when it dies (spec 4.1).
 *
 * <p>This map is the ONLY record of that ownership - a session keeps no copy of
 * the locks it holds, so the two can never disagree, and
 * {@link #releaseAll(ClientSession)} on teardown needs nothing but the session
 * itself.
 *
 * <p>One instance per server, created by {@link ServerMain} and handed to every
 * session; it is not a singleton, so a test (and the loopback integration run)
 * can stand up an isolated server.  Every change notifies the registered
 * listeners, which is what makes the {@code ev.devices.changed} broadcast fire
 * on a lock taken, released or lost - clients gray out locked devices live.
 */
@Log4j2
public final class LockRegistry {

    private final Map<DeviceLock, ClientSession> owners = new ConcurrentHashMap<>();
    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();

    /** Registers a listener fired after every ownership change. */
    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }

    /**
     * Fires those same listeners without any lock having moved - for a change
     * that alters what {@code devices.list} SAYS rather than who holds what: the
     * calibration a {@code device.setCalibration} just wrote (spec 4.3).
     *
     * <p>It is here, and not a second notification path of its own, because
     * there is exactly ONE {@code ev.devices.changed} fan-out and it is already
     * subscribed to this list.  A parallel path would have to be subscribed to
     * separately, and the first caller that forgot would write a calibration no
     * other client ever heard about.
     */
    public void notifyDevicesChanged() {
        fireChanged();
    }

    /**
     * Grants {@code session} the exclusive lock on {@code lock}.
     *
     * @return null when the lock is now held by {@code session} (including a
     *         repeated acquire by the same session), otherwise the session that
     *         holds it - the caller answers {@code DEVICE_LOCKED} naming it.
     */
    public ClientSession acquire(DeviceLock lock, ClientSession session) {
        ClientSession current = owners.putIfAbsent(lock, session);
        if (current == null) {
            if (log.isInfoEnabled()) {
                log.info("net lock: {} acquired by {}", lock, session.getClientName());
            }
            fireChanged();
            return null;
        }
        return current == session ? null : current;
    }

    /** Releases a lock {@code session} holds.  {@code false} when it holds no
     *  such lock - the caller answers {@code NOT_LOCKED}. */
    public boolean release(DeviceLock lock, ClientSession session) {
        if (!owners.remove(lock, session)) {
            return false;
        }
        if (log.isInfoEnabled()) {
            log.info("net lock: {} released by {}", lock, session.getClientName());
        }
        fireChanged();
        return true;
    }

    /** Drops every lock {@code session} holds - the teardown path of spec 4.1.
     *
     *  @return how many locks were freed */
    public int releaseAll(ClientSession session) {
        int freed = 0;
        for (Iterator<Map.Entry<DeviceLock, ClientSession>> it =
                owners.entrySet().iterator(); it.hasNext();) {
            Map.Entry<DeviceLock, ClientSession> entry = it.next();
            if (entry.getValue() == session) {
                it.remove();
                freed++;
            }
        }
        if (freed > 0) {
            if (log.isInfoEnabled()) {
                log.info("net lock: {} lock(s) released with session {}",
                        freed, session.getClientName());
            }
            fireChanged();
        }
        return freed;
    }

    /** The session holding {@code lock}, or null when it is free. */
    public ClientSession owner(DeviceLock lock) {
        return owners.get(lock);
    }

    /** True while ANY connection holds any device of {@code backend}, in either
     *  direction - what tells a teardown whether the hardware is nobody's and
     *  may be parked (spec 4.1), see {@link Qa40xGuard#parkIfIdle()}. */
    public boolean anyHeld(AudioBackendType backend) {
        for (DeviceLock lock : owners.keySet()) {
            if (lock.backend() == backend) {
                return true;
            }
        }
        return false;
    }

    /** True while {@code session} itself holds a device of {@code backend}, in
     *  either direction - the gate spec 4.6 puts on the QA40x commands that
     *  WRITE ("requires the QA40x lock (either direction) unless marked
     *  read-only").  The sibling above asks whether ANYBODY holds one, which is
     *  a different question with a different answer: a client may read the
     *  analyzer's telemetry while another client measures on it, and may move its
     *  attenuator only while it is its own. */
    public boolean heldBy(AudioBackendType backend, ClientSession session) {
        for (Map.Entry<DeviceLock, ClientSession> held : owners.entrySet()) {
            if (held.getKey().backend() == backend && held.getValue() == session) {
                return true;
            }
        }
        return false;
    }

    /** How many locks are held right now - the state a test asserts on. */
    public int size() {
        return owners.size();
    }

    /**
     * The fan-out, one guarded listener at a time.
     *
     * <p>A listener fault may not travel back up this call: what fires this is a
     * lock CHANGE, and the caller that changed it is as often as not a teardown
     * that has just freed everything and still has an analyzer to park.  The
     * broadcast this notifies writes to client sockets, so a peer that vanished
     * mid-write is exactly the fault that used to come back here - and one dead
     * socket must not stop the other clients being told, nor cost the caller the
     * rest of its work.
     */
    private void fireChanged() {
        for (Runnable listener : changeListeners) {
            try {
                listener.run();
            } catch (Throwable t) {
                if (log.isWarnEnabled()) {
                    log.warn("net lock: a devices-changed listener failed: {}", t.toString());
                }
            }
        }
    }
}
