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

import java.util.List;

import javax.sound.sampled.AudioFormat;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.sound.AudioCapture;
import org.edgo.audio.measure.sound.AudioDeviceManager;
import org.edgo.audio.measure.sound.AudioPlayback;
import org.edgo.audio.measure.sound.DeviceRef;

/**
 * The net carrier's catalogue, and nothing else: one analyzer on a server.
 *
 * <p>The card sync needs it because a card is keyed by the DEVICE's name, and the
 * only place that name exists is the remote catalogue - which is exactly the
 * coupling that makes the synced card findable again by everything downstream
 * (the generator's full scale, the dBV axis, the ranges table).  Nothing here
 * opens a stream: a settings panel never does.
 *
 * <p>The claim on {@link AudioBackendType#NET} is uncontested - {@code
 * backend-net} is not a dependency of this module - so the first-registration
 * rule never has to decide anything.
 */
public final class StubNetBackend implements AudioDeviceManager {

    /** The analyzer's name on the bench - deliberately unlike any seeded card's,
     *  so a card found by this name can only be the one the sync wrote. */
    public static final String DEVICE_NAME = "QA403 (bench stub)";

    private final DeviceRef input = new StubRef(0, DEVICE_NAME, true);
    private final DeviceRef output = new StubRef(0, DEVICE_NAME, false);

    /** Public and no-argument for {@link StubNetBackendProvider}. */
    public StubNetBackend() {
    }

    @Override
    public List<DeviceRef> listInputDevices() {
        return List.of(input);
    }

    @Override
    public List<DeviceRef> listOutputDevices() {
        return List.of(output);
    }

    @Override
    public DeviceRef getDeviceByIndex(int index, boolean isOutput) {
        if (index != 0) {
            throw new IllegalArgumentException("no such device: " + index);
        }
        return isOutput ? output : input;
    }

    @Override
    public List<AudioFormat> listSupportedFormats(DeviceRef device, boolean output) {
        return List.of();
    }

    @Override
    public AudioCapture openCapture(DeviceRef device, int sampleRate, int bitDepth) {
        throw new UnsupportedOperationException("a settings panel opens no stream");
    }

    @Override
    public AudioPlayback openPlayback(DeviceRef device, int sampleRate, int bitDepth,
            double ditherBits) {
        throw new UnsupportedOperationException("a settings panel opens no stream");
    }

    /** The analyzer as the catalogue names it: a QA403 that IS a QA40x, reached
     *  through the net carrier (the dual-level rule). */
    private record StubRef(int index, String name, boolean isInput) implements DeviceRef {

        private static final String DESCRIPTION = "stub";
        private static final String VENDOR = "stub";

        @Override
        public String description() {
            return DESCRIPTION;
        }

        @Override
        public String vendor() {
            return VENDOR;
        }

        @Override
        public AudioBackendType backend() {
            return AudioBackendType.QA40X;
        }

        @Override
        public AudioBackendType carrier() {
            return AudioBackendType.NET;
        }

        @Override
        public boolean isOutput() {
            return !isInput;
        }
    }
}
