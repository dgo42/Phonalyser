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
import org.edgo.audio.measure.sound.DeviceRef;

/**
 * The single device this backend offers, listed for both directions.  There is
 * no hardware behind it: the playback lane's quantised samples ARE the capture
 * lane's input, so one ref describes both ends of the same digital crossing.
 */
public final class LoopbackDeviceRef implements DeviceRef {

    /** Shown wherever a device name is shown - the backend has exactly one.
     *  Deliberately carries the application name: the seed card in devices.yaml
     *  binds by case-insensitive name SUBSTRING, and a bare "Loopback" would
     *  also match real endpoints that carry the word (the WASAPI loopback
     *  capture endpoints Windows exposes). */
    static final String DEVICE_NAME = "Phonalyser Loopback";

    @Override
    public int index() {
        return 0;
    }

    @Override
    public String name() {
        return DEVICE_NAME;
    }

    @Override
    public String description() {
        return "Digital loopback (playback returns to capture)";
    }

    @Override
    public String vendor() {
        return "Phonalyser";
    }

    @Override
    public AudioBackendType backend() {
        return AudioBackendType.LOOPBACK;
    }

    /** Both directions are the same crossing, so the one device is each. */
    @Override
    public boolean isInput() {
        return true;
    }

    @Override
    public boolean isOutput() {
        return true;
    }
}
