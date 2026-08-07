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

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.sound.AudioDeviceManager;
import org.edgo.audio.measure.sound.AudioDeviceManagerProvider;

/**
 * Announces {@link StubDeviceManager} to {@code AudioBackend} through the same
 * service contract a real backend module uses, so the server talks to its
 * hardware exactly as it does in production - no seam invented for the test.
 *
 * <p>It claims {@link AudioBackendType#QA40X} because that is the backend whose
 * server-side rules the protocol singles out (spec 4.4's equal-rates guard), and
 * because no real backend module is on this module's test class path to contend
 * for the type.  {@link StubSoundCardProvider} adds a SECOND backend to the same
 * bench, which is what the backend-selection tests need: the server serves all
 * of its backends at once and two sessions may pick different ones.
 *
 * <p>Public with a public no-argument constructor: {@code ServiceLoader} accepts
 * nothing else.
 */
public class StubDeviceManagerProvider implements AudioDeviceManagerProvider {

    private final AudioBackendType backend;

    public StubDeviceManagerProvider() {
        this(AudioBackendType.QA40X);
    }

    protected StubDeviceManagerProvider(AudioBackendType backend) {
        this.backend = backend;
    }

    @Override
    public AudioBackendType backendType() {
        return backend;
    }

    @Override
    public AudioDeviceManager create() {
        return new StubDeviceManager(backend);
    }
}
