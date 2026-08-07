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

package org.edgo.audio.measure.gui.backend.qa40x;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.sound.AudioDeviceManager;
import org.edgo.audio.measure.sound.AudioDeviceManagerProvider;

/**
 * Registers {@link StubNetBackend} as this test class path's net carrier, so the
 * card sync can ask the catalogue what the remote analyzer is called.
 *
 * <p>Uncontested: {@code backend-net} is not a dependency of this module, so
 * nothing else provides {@link AudioBackendType#NET} here.
 */
public final class StubNetBackendProvider implements AudioDeviceManagerProvider {

    /** Public and no-argument for the service loader. */
    public StubNetBackendProvider() {
    }

    @Override
    public AudioBackendType backendType() {
        return AudioBackendType.NET;
    }

    @Override
    public AudioDeviceManager create() {
        return new StubNetBackend();
    }
}
