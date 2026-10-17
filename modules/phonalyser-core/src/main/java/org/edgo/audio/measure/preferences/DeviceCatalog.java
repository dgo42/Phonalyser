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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.DeviceRef;

import lombok.extern.log4j.Log4j2;

/**
 * The desktop's device-list snapshot - the server's {@code DeviceCatalog}
 * model brought to the GUI: every available backend is enumerated ONCE, in
 * the background at start-up while no stream holds a device, and every list
 * consumer (the Preferences dialog above all) is served from that snapshot
 * instead of walking drivers on its own.  A walk at consumption time pays
 * seconds of driver I/O on every dialog open - and, worse, asks the hardware
 * while captures and playback may hold it, and a held card answers its
 * format probe empty and drops off the list entirely.
 *
 * <p>The priming mirrors the server's boot order (see {@code ServerMain}):
 * the bench is enumerated FIRST, the backend setup sweep runs after - on one
 * thread, so the two never interleave on the same managers.
 *
 * <p>Re-enumeration happens only where the operator asks for it (the Scan
 * button): {@link #drop} forgets a backend's snapshot and the enumeration
 * caches behind it, and the scan that follows {@link #scan re-walks} the
 * hardware and stores the fresh answer.  A card plugged in since start-up
 * therefore appears on scan - the rule the PortAudio-snapshot backends have
 * always had.
 */
@Log4j2
public final class DeviceCatalog {

    private final AudioBackend audio;
    /** The last scan per backend - written by the priming thread and the
     *  Preferences dialog's scan worker, read by the dialog's fill; the lists
     *  are never mutated after publication. */
    private final Map<AudioBackendType, DeviceLists> lastScan = new ConcurrentHashMap<>();

    /** One backend's device snapshot - both directions of one enumeration. */
    public record DeviceLists(List<DeviceRef> inputs, List<DeviceRef> outputs) { }

    public DeviceCatalog(AudioBackend audio) {
        this.audio = audio;
    }

    /** Primes the catalog on a daemon thread: every available backend is
     *  enumerated, THEN the backend setup sweep is started - the server's
     *  boot order, kept so the enumeration cannot interleave with the safety
     *  sweep on the same managers.  One backend failing must not stop the
     *  others (or the sweep). */
    public void primeInBackground() {
        Thread worker = new Thread(this::primeAll, "device-catalog-prime");
        worker.setDaemon(true);
        worker.start();
    }

    private void primeAll() {
        for (AudioBackendType type : AudioBackendType.values()) {
            // Both halves of availability, exactly as the setup sweep asks
            // them: the OS policy AND the class-path/hardware answer -
            // without the OS half this would construct foreign backends.
            if (!type.isAvailable() || !audio.isAvailable(type)) {
                continue;
            }
            try {
                DeviceLists lists = scan(type);
                if (log.isInfoEnabled()) {
                    log.info("Device catalog primed: {} - {} input / {} output device(s)",
                            type, lists.inputs().size(), lists.outputs().size());
                }
            } catch (Throwable t) {
                log.warn("Device catalog: priming {} failed: {}", type, t.toString());
            }
        }
        audio.setupInBackground();
    }

    /** The snapshot for {@code type}, or {@code null} when none was taken yet
     *  (priming still out, or a {@link #drop} not yet re-scanned). */
    public DeviceLists lists(AudioBackendType type) {
        return lastScan.get(type);
    }

    /** Walks {@code type}'s devices NOW and replaces its snapshot - called on
     *  the priming thread and on the dialog's scan worker, never on the UI
     *  thread (the walk is driver I/O). */
    public DeviceLists scan(AudioBackendType type) {
        DeviceLists lists = new DeviceLists(
                audio.listInputDevices(type), audio.listOutputDevices(type));
        lastScan.put(type, lists);
        return lists;
    }

    /** Forgets {@code type}'s snapshot AND the enumeration caches behind it -
     *  the operator's "I distrust the list" gesture.  The scan that follows
     *  re-walks the hardware and stores the fresh answer. */
    public void drop(AudioBackendType type) {
        lastScan.remove(type);
        audio.refreshDeviceLists(type);
    }
}
