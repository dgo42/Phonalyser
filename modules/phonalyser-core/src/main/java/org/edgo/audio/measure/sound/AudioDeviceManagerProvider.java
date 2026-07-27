/*
 * Phonalyser — precision audio measurement workbench.
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

package org.edgo.audio.measure.sound;

import org.edgo.audio.measure.enums.AudioBackendType;

/**
 * Service contract by which a backend module announces itself to
 * {@link AudioBackend}.  Each backend module ships one implementation and
 * registers it in a {@code META-INF/services} file named after this interface;
 * {@code AudioBackend} discovers them with {@code ServiceLoader} and never names
 * a concrete manager.
 *
 * <p>This is what keeps the module graph one-way.  Before it existed,
 * {@code AudioBackend} imported and constructed every device manager directly,
 * which made the core module depend on every backend — the exact inverse of the
 * intended layering, and the reason a headless build had to drag the whole tree
 * along.  Adding a backend is now purely additive: a new module, a provider, a
 * service file.  Nothing in core changes.
 *
 * <h2>Why a provider and not the manager itself</h2>
 * {@code ServiceLoader} instantiates every registered implementation as soon as
 * the loader is iterated.  Registering the managers directly would therefore
 * construct all of them on the first backend lookup, losing the lazy
 * construction {@code AudioBackend} has always had.  A provider is trivially
 * cheap to construct — it holds no state and touches no hardware — so discovery
 * stays free and the real manager is built only when {@link #create()} is
 * called, on first use of that backend.
 *
 * <p>Implementations MUST have a public no-argument constructor and MUST NOT
 * open a device, load a native library or read preferences while constructing;
 * defer all of that to {@link #create()} (and the manager itself defers reaching
 * hardware until a device is actually opened).
 */
public interface AudioDeviceManagerProvider {

    /** The backend this provider supplies.  Exactly one provider may claim a
     *  given type; {@link AudioBackend} logs and ignores later duplicates. */
    AudioBackendType backendType();

    /** Builds the manager.  Called at most once per type — {@link AudioBackend}
     *  caches the result — and never during service discovery. */
    AudioDeviceManager create();
}
