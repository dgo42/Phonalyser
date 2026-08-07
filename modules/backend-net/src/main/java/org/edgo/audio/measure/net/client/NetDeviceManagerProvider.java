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

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.sound.AudioDeviceManager;
import org.edgo.audio.measure.sound.AudioDeviceManagerProvider;

/**
 * Registers the net backend with {@code AudioBackend}, through the same service
 * contract every local backend uses - which is the whole point: a remote bench
 * reaches the application by the same route a sound card does, and nothing
 * upstream has to know the difference.
 *
 * <p>It claims {@link AudioBackendType#NET}, the carrier the manager is reached
 * through; the backend the connection is actually routed at is a property of the
 * manager, not of the enum (see {@link NetDeviceRef}).
 */
public final class NetDeviceManagerProvider implements AudioDeviceManagerProvider {

    @Override
    public AudioBackendType backendType() {
        return AudioBackendType.NET;
    }

    @Override
    public AudioDeviceManager create() {
        return new NetDeviceManager();
    }
}
