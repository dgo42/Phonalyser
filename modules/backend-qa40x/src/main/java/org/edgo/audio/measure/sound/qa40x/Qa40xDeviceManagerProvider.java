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

package org.edgo.audio.measure.sound.qa40x;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.sound.AudioDeviceManager;
import org.edgo.audio.measure.sound.AudioDeviceManagerProvider;

/** Registers the QuantAsylum QA402/QA403 backend with {@code AudioBackend}.
 *  Construction here must stay free of libusb: the manager opens its USB session
 *  lazily, on the first capture. */
public final class Qa40xDeviceManagerProvider implements AudioDeviceManagerProvider {

    @Override
    public AudioBackendType backendType() {
        return AudioBackendType.QA40X;
    }

    @Override
    public AudioDeviceManager create() {
        return new Qa40xDeviceManager();
    }
}
