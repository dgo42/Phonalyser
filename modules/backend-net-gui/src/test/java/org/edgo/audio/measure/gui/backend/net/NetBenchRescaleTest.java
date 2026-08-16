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

package org.edgo.audio.measure.gui.backend.net;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.gui.common.RemoteBackendRegistry;
import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.DeviceCalibration;
import org.edgo.audio.measure.sound.DeviceRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which bench recalibrations this client applies to its own full-scale reference
 * (spec 4.3's {@code cal} on {@code ev.devices.changed}).
 *
 * <p>Two questions, and BOTH have to be asked.  The device must be the one in
 * force - and the modules must be measuring on the bench at all.  A session stays
 * alive, and this listener stays subscribed, while the operator works on a LOCAL
 * backend; that is why the connection-loss box carries the same carrier guard.
 *
 * <p>The failure that makes the second question load-bearing is a NAME COLLISION:
 * a QA40x names itself after its model, so a local QA403 and a bench QA403 are one
 * name (the reason a hot-plug comparison needed more than a name in the first
 * place).  Without the guard, another operator recalibrating the BENCH's analyzer
 * would silently move the dBV reference of a local measurement.
 *
 * <p>No display is needed: what is asserted is the DECISION, which is taken before
 * anything is marshalled.
 */
class NetBenchRescaleTest {

    /** The one name both analyzers answer to - the collision itself. */
    private static final String DEVICE_NAME = "QA403";
    private static final String OTHER_DEVICE = "Cosmos ADC";
    /** A selection ON the bench: a remote key, whose CARRIER is what
     *  {@code AudioBackend.active()} answers ({@code AudioBackendType.NET} is a
     *  carrier, never a selection's type). */
    private static final BackendKey BENCH =
            BackendKey.of("b7e0-bench-uuid", AudioBackendType.QA40X);
    /** And a selection on this machine, the same analyzer model. */
    private static final BackendKey LOCAL = BackendKey.of(AudioBackendType.WASAPI);

    private final NetBenchUi ui = (NetBenchUi) RemoteBackendRegistry.instance().getUi();

    private AudioBackendType backendBefore;

    @BeforeEach
    void selectTheBenchDevice() {
        Preferences prefs = Preferences.instance();
        prefs.setTransientMode(true);
        backendBefore = AudioBackend.instance().active();
        prefs.current().setInputDeviceName(DEVICE_NAME);
        prefs.current().setOutputDeviceName(DEVICE_NAME);
    }

    @AfterEach
    void putTheBackendBack() {
        AudioBackend.instance().setActive(backendBefore);
    }

    @Test
    void aRecalibrationOfTheDeviceInForceOnTheBenchIsApplied() {
        AudioBackend.instance().setActive(BENCH);

        assertTrue(ui.rescales(device(DEVICE_NAME, true)),
                "the modules are on the bench and this IS the input they measure "
                        + "with - the new full scale is what every reading means now");
    }

    /** The collision: same name, but the modules are measuring locally. */
    @Test
    void aBenchRecalibrationNeverMovesALocalSelectionOfTheSameName() {
        AudioBackend.instance().setActive(LOCAL);

        assertFalse(ui.rescales(device(DEVICE_NAME, true)),
                "a local QA403 and a bench QA403 are one NAME and two devices; the "
                        + "bench's full scales are nothing to do with the local one, "
                        + "and nothing on screen would say the axis had moved");
    }

    @Test
    void aDeviceThisClientIsNotMeasuringOnIsLeftAlone() {
        AudioBackend.instance().setActive(BENCH);

        assertFalse(ui.rescales(device(OTHER_DEVICE, true)),
                "its calibration is not in force here; the catalogue already carries "
                        + "the new values for whenever it is");
    }

    /** The direction is part of the question: the same name in the other lane is
     *  the other endpoint, and only the one in force there decides. */
    @Test
    void theDirectionIsPartOfTheMatch() {
        AudioBackend.instance().setActive(BENCH);
        Preferences.instance().current().setOutputDeviceName(OTHER_DEVICE);

        assertTrue(ui.rescales(device(DEVICE_NAME, true)));
        assertFalse(ui.rescales(device(DEVICE_NAME, false)),
                "the output lane is on another device, so the bench's output "
                        + "calibration for this one is not in force here");
    }

    private DeviceRef device(String name, boolean input) {
        return new StubRef(name, input);
    }

    /** A bench device, as little of it as the decision reads. */
    private record StubRef(String name, boolean isInput) implements DeviceRef {

        @Override
        public int index() {
            return 0;
        }

        @Override
        public String description() {
            return "stub";
        }

        @Override
        public String vendor() {
            return "stub";
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

        @Override
        public DeviceCalibration calibration() {
            return new DeviceCalibration(1.0, 1.0);
        }
    }
}
