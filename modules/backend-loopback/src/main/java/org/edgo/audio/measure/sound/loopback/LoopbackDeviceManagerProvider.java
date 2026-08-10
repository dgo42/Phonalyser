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

package org.edgo.audio.measure.sound.loopback;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.sound.AudioDeviceManager;
import org.edgo.audio.measure.sound.AudioDeviceManagerProvider;

/**
 * Service entry point for the digital loopback backend.  Constructing it opens
 * nothing, loads no library and reads no preferences, as the contract requires;
 * there is nothing to probe either, so availability is unconditional - the
 * backend is pure software and runs wherever the application does.
 */
public final class LoopbackDeviceManagerProvider implements AudioDeviceManagerProvider {

    @Override
    public AudioBackendType backendType() {
        return AudioBackendType.LOOPBACK;
    }

    @Override
    public AudioDeviceManager create() {
        return new LoopbackDeviceManager();
    }
}
