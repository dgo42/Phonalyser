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

import java.io.IOException;
import java.net.ServerSocket;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.gui.common.RemoteBackendRegistry;
import org.edgo.audio.measure.net.client.NetServerEntry;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.DeviceCalibration;
import org.edgo.audio.measure.sound.DeviceRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which bench recalibrations apply to this client's own full-scale reference
 * (spec 4.3's {@code cal} on {@code ev.devices.changed}, announced on the bus
 * as a calibration change of the device it names): every consumer asks
 * {@link AudioBackend#isActiveDevice} before it acts.
 *
 * <p>Two questions, and BOTH have to be asked.  The device must be the one in
 * force - and the modules must be measuring on the bench at all.  A session
 * stays alive, and its manager keeps announcing, while the operator works on a
 * LOCAL backend.
 *
 * <p>The failure that makes the second question load-bearing is a NAME
 * COLLISION: a QA40x names itself after its model, so a local QA403 and a bench
 * QA403 are one name (the reason a hot-plug comparison needed more than a name
 * in the first place).  Without the carrier in the match, another operator
 * recalibrating the BENCH's analyzer would silently move the dBV reference of a
 * local measurement.
 *
 * <p>The bench is the loopback one of {@link NetBenchUiLoopbackTest}, so the
 * device in force is a real enumeration and not a name typed into a stub.  No
 * display is needed: what is asserted is the DECISION.
 */
class NetBenchRescaleTest {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String BENCH_ID = "b7e0c4d2-0000-4000-8000-0000000000a2";
    /** The one name both analyzers answer to - the collision itself. */
    private static final String DEVICE_NAME = BenchSocket.DEVICE_NAME;
    private static final String OTHER_DEVICE = "Cosmos ADC";
    private static final long BIND_TIMEOUT_MS = 5_000;
    private static final long STOP_TIMEOUT_MS = 1_000;

    private final NetBenchUi ui = (NetBenchUi) RemoteBackendRegistry.instance().getUi();

    private BenchSocket socket;
    private AudioBackendType backendBefore;

    @BeforeEach
    void connectTheBenchAndSelectItsDevice() throws IOException {
        Preferences prefs = Preferences.instance();
        prefs.setTransientMode(true);
        backendBefore = AudioBackend.instance().active();
        int benchPort = freePort();
        socket = new BenchSocket(new JsonCodec(), BENCH_ID, benchPort);
        socket.listen(BIND_TIMEOUT_MS);
        assertNull(ui.connect(new NetServerEntry(LOOPBACK, BenchSocket.SERVER_NAME,
                LOOPBACK, benchPort, true)), "the loopback bench refused the session");
        prefs.current().setInputDeviceName(DEVICE_NAME);
        prefs.current().setOutputDeviceName(DEVICE_NAME);
    }

    @AfterEach
    void putTheBackendBackAndDisconnect() {
        AudioBackend.instance().setActive(backendBefore);
        ui.disconnect();
        socket.shutDown(STOP_TIMEOUT_MS);
    }

    @Test
    void aRecalibrationOfTheDeviceInForceOnTheBenchIsApplied() {
        measureOnTheBench();

        assertTrue(AudioBackend.instance().isActiveDevice(device(DEVICE_NAME, true), true),
                "the modules are on the bench and this IS the input they measure "
                        + "with - the new full scale is what every reading means now");
    }

    /** The collision: same name, other machine.  The match compares the
     *  CARRIER as well as the name, in both directions - here the modules are
     *  on the bench and it is the LOCAL analyzer of the same name that was
     *  recalibrated (this module's test class path carries no local backend to
     *  select, so the mirror case - a local selection, a bench recalibration -
     *  is the same comparison seen from the other side). */
    @Test
    void aRecalibrationOfTheSameNameOnTheOtherMachineNeverMovesTheSelection() {
        measureOnTheBench();

        assertFalse(AudioBackend.instance().isActiveDevice(
                new StubRef(DEVICE_NAME, true, AudioBackendType.WASAPI), true),
                "a local QA403 and a bench QA403 are one NAME and two devices; the "
                        + "local one's full scales are nothing to do with the bench's, "
                        + "and nothing on screen would say the axis had moved");
    }

    @Test
    void aDeviceThisClientIsNotMeasuringOnIsLeftAlone() {
        measureOnTheBench();

        assertFalse(AudioBackend.instance().isActiveDevice(device(OTHER_DEVICE, true), true),
                "its calibration is not in force here; the catalogue already carries "
                        + "the new values for whenever it is");
    }

    /** The direction is part of the question: the same name in the other lane is
     *  the other endpoint, and only the one in force there decides. */
    @Test
    void theDirectionIsPartOfTheMatch() {
        measureOnTheBench();
        Preferences.instance().current().setOutputDeviceName(OTHER_DEVICE);

        assertTrue(AudioBackend.instance().isActiveDevice(device(DEVICE_NAME, true), true));
        assertFalse(AudioBackend.instance().isActiveDevice(device(DEVICE_NAME, false), false),
                "the output lane is on another device, so the bench's output "
                        + "calibration for this one is not in force here");
    }

    /** The bench's analyzer as the selection: the session commits it and hands
     *  its device list to the net manager, and the live backend is pointed at
     *  the bench - the two halves the Preferences dialog's OK does. */
    private void measureOnTheBench() {
        BackendKey bench = BackendKey.of(ui.getConnectedServer().serverId(),
                AudioBackendType.QA40X);
        assertTrue(ui.select(bench), "the bench refused the selection");
        AudioBackend.instance().setActive(bench);
    }

    private DeviceRef device(String name, boolean input) {
        return new StubRef(name, input, AudioBackendType.NET);
    }

    /** A port nobody is listening on, so two runs - or a developer's own server -
     *  never collide.  The probe is closed before the bench binds it. */
    private int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    /** A device as the announcement carries it - as little of it as the
     *  decision reads: its name, its direction and the carrier it is reached
     *  through ({@code NET} for the bench's, a local type for this machine's). */
    private record StubRef(String name, boolean isInput, AudioBackendType carrier)
            implements DeviceRef {

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
        public boolean isOutput() {
            return !isInput;
        }

        @Override
        public DeviceCalibration calibration() {
            return new DeviceCalibration(1.0, 1.0);
        }
    }
}
