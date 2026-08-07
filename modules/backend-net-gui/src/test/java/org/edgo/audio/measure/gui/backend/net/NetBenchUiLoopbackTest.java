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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.gui.common.RemoteBackendRegistry;
import org.edgo.audio.measure.net.client.NetDeviceManager;
import org.edgo.audio.measure.net.client.NetServerEntry;
import org.edgo.audio.measure.net.proto.JsonCodec;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.AudioDeviceManager;
import org.edgo.audio.measure.sound.DeviceRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A backend's OWN requests over a live session: net protocol 4.6, sent by the
 * type that holds the session ({@link NetBenchUi}) against a bench that exists
 * only as the JSON it speaks ({@link BenchSocket}).
 *
 * <p>What this proves: the CLIENT half of the round trip.  {@code call} marshals
 * the caller's fields into a request and binds the answer back to a plain map,
 * and a settings panel reads that map by field name - so a value that came back
 * as the wrong Java type, or under a name that drifted by one character, is a
 * panel showing nothing with no error anywhere.
 *
 * <p><b>Why the far end is a socket and not a server object.</b>  This module is
 * the client's bench UI; what is on the other side is a process that answers
 * JSON, and the wire format is the whole contract between them.  The mock is
 * deliberately not a replay of what the client expects: it enforces the lock
 * rules of spec 4.3 and 4.6, remembers what a write moved, and counts the
 * commits it was asked for - so a client that sent the wrong thing, sent it
 * unlocked, or sent it twice is refused here exactly as the bench would refuse
 * it.
 *
 * <p>Nothing here needs a display and nothing needs an analyzer: no
 * {@code libusb} is loaded anywhere, on any host.
 */
class NetBenchUiLoopbackTest {

    private static final String LOOPBACK = "127.0.0.1";
    /** This bench's installation UUID (spec 2.1) - what the session is keyed on
     *  once the server's own {@code hello} has supplied it. */
    private static final String BENCH_ID = "b7e0c4d2-0000-4000-8000-0000000000a1";
    /** A server id that is not the one on the wire - spec 2.1 keys a bench on its
     *  installation UUID, and this is "some other bench". */
    private static final String FOREIGN_ID = "b7e0c4d2-0000-4000-8000-00000000ffff";
    /** An input range the bench really has and does NOT start on, so a write that
     *  never left is told apart from one that changed nothing. */
    private static final int OTHER_INPUT_DBV = 42;
    /** The two full scales the calibration write carries, unequal so a payload
     *  that crossed the channels fails on the values. */
    private static final double FS_RMS_LEFT = 1.234;
    private static final double FS_RMS_RIGHT = 2.345;
    private static final double EPS = 1e-9;
    private static final long BIND_TIMEOUT_MS = 5_000;
    private static final long STOP_TIMEOUT_MS = 1_000;
    /** What the generator lane is asked for; the bench grants its own rate back,
     *  which is the whole reason {@code gen.open} answers with one. */
    private static final int GEN_RATE_HZ = 48_000;
    private static final int GEN_BITS = 24;

    private BenchSocket socket;
    private NetBenchUi bench;

    @BeforeEach
    void startTheBenchAndConnect() throws IOException {
        // The session's own server entry is remembered LIVE (it is an action, not
        // a setting), so this keeps the developer's preferences.yaml out of it.
        Preferences.instance().setTransientMode(true);
        int benchPort = freePort();
        socket = new BenchSocket(new JsonCodec(), BENCH_ID, benchPort);
        socket.listen(BIND_TIMEOUT_MS);
        bench = (NetBenchUi) RemoteBackendRegistry.instance().getUi();
        // The id is unknown until the server answers - exactly what the dialog's
        // manual entry starts from, and what connect() replaces with the real one.
        assertNull(bench.connect(new NetServerEntry(LOOPBACK, BenchSocket.SERVER_NAME,
                LOOPBACK, benchPort, true)),
                "the loopback bench refused the session");
    }

    /** Every test gets its own bench, so nothing a write moved has to be put
     *  back - the analyzer's state died with the socket it lived on. */
    @AfterEach
    void disconnectAndStop() {
        bench.disconnect();
        socket.shutDown(STOP_TIMEOUT_MS);
    }

    @Test
    void theAnalyzersTelemetryArrivesFieldByField() {
        Map<String, Object> info = bench.call(analyzer(), MessageType.QA40X_INFO.getWire(),
                Map.of());

        assertNotNull(info, "spec 4.6 marks qa40x.info read-only, so a client may read "
                + "the bench's telemetry without holding it");
        assertEquals(BenchSocket.FIRMWARE, info.get(NetFields.FIRMWARE_VERSION));
        assertEquals(BenchSocket.SERIAL, info.get(NetFields.SERIAL_NUMBER));
        assertEquals(BenchSocket.TEMPERATURE, info.get(NetFields.TEMPERATURE),
                "the strings are the ones the SERVER formatted - nothing is "
                        + "re-interpreted on the way, which is what lets the same panel "
                        + "show a local analyzer and one across the room");
    }

    @Test
    void theRangesAndTheCalibrationPageComeBackWithTheirShape() {
        Map<String, Object> ranges = bench.call(analyzer(),
                MessageType.QA40X_RANGES.getWire(), Map.of());
        assertEquals(BenchSocket.INPUT_RANGES_DBV.length,
                ((List<?>) ranges.get(NetFields.INPUT_DBV)).size());
        assertEquals(BenchSocket.DEFAULT_INPUT_DBV, ranges.get(NetFields.ACTIVE_INPUT_DBV));
        assertEquals(BenchSocket.DEFAULT_OUTPUT_DBV,
                ranges.get(NetFields.ACTIVE_OUTPUT_DBV));

        Map<String, Object> page = bench.call(analyzer(),
                MessageType.QA40X_CALIBRATION.getWire(), Map.of());
        List<?> adc = (List<?>) page.get(NetFields.ADC);
        assertEquals(BenchSocket.INPUT_RANGES_DBV.length, adc.size());
        Map<?, ?> row = (Map<?, ?>) adc.get(0);
        assertEquals(BenchSocket.INPUT_RANGES_DBV[0], row.get(NetFields.DBV));
        assertEquals(BenchSocket.CAL_LEFT, row.get(NetFields.LEFT));
        assertEquals(BenchSocket.CAL_RIGHT, row.get(NetFields.RIGHT),
                "left and right are not interchangeable: a card built from swapped "
                        + "factors mis-scales one channel and nothing says so");
    }

    @Test
    void theFrontPanelPortReadsBackAsABooleanAndNotAsText() {
        Map<String, Object> settings = bench.call(analyzer(),
                MessageType.QA40X_SETTINGS.getWire(), Map.of());

        assertEquals(Boolean.FALSE, settings.get(NetFields.I2S_ENABLED),
                "the panel decides a checkbox from this value, so a String \"false\" "
                        + "would tick the box for a port that is off");
    }

    @Test
    void aRefusedWriteIsANullAndNotAValueFromNowhere() {
        // Spec 4.6: only the reads are read-only.  Nothing here holds the
        // analyzer, so the bench refuses - and the panel must be told nothing
        // rather than told something.
        assertNull(bench.call(analyzer(), MessageType.QA40X_SETTINGS.getWire(),
                Map.of(NetFields.I2S_ENABLED, true)));
        assertFalse(socket.isI2sEnabled(), "and the port really did not move");
    }

    /**
     * The bench repro: connect to a server, pick its QA40x, and the device
     * combos must fill from the answer.
     *
     * <p>It stops at the manager on purpose - the combos themselves need a
     * display.  What it proves is everything below them: the analyzer really is
     * offered as a combo entry ({@code backend.list} says available AND
     * operational), the selection is COMMITTED on the server
     * ({@code backend.select}), and the answer to that one round trip is what the
     * device lists are built from.
     */
    @Test
    void theAnalyzerIsOfferedAsAnEntryAndSelectingItFillsTheDeviceLists() {
        BackendKey analyzer = analyzer();
        assertTrue(bench.entries().stream().anyMatch(e -> analyzer.equals(e.key())),
                "a served backend must reach the combo - an entry dropped as "
                        + "non-operational is a bench the operator cannot pick at all");

        assertTrue(bench.select(analyzer), "the bench refused backend.select QA40X");

        AudioDeviceManager manager = AudioBackend.instance().manager(AudioBackendType.NET);
        assertEquals(1, manager.listInputDevices().size(),
                "the devices of the selected backend arrive with the selection "
                        + "itself, in one round trip");
        assertEquals(BenchSocket.DEVICE_NAME, manager.listInputDevices().get(0).name());
        assertEquals(BenchSocket.DEVICE_NAME, manager.listOutputDevices().get(0).name());
    }

    /**
     * The Preferences dialog's staged read - nothing is applied until OK is
     * clicked: preview fills the device lists from a plain {@code devices.list}
     * read, committing nothing - and select
     * stays the commit, idempotent for the selection the session is already on,
     * because the dialog's OK re-affirms it every time and a real re-select
     * starts by tearing down the generator lane mid-measurement.
     *
     * <p>Both halves are counted rather than asserted by their side effects: the
     * bench remembers how many {@code backend.select} round trips it answered, so
     * "committing nothing" and "no second commit" are the assertions themselves
     * and not a hope about what a device list implies.
     */
    @Test
    void previewFillsTheDeviceListsAndSelectStaysTheIdempotentCommit() {
        assertTrue(bench.preview(analyzer()), "the bench refused devices.list");
        AudioDeviceManager manager = AudioBackend.instance().manager(AudioBackendType.NET);
        assertEquals(BenchSocket.DEVICE_NAME, manager.listInputDevices().get(0).name(),
                "the preview alone fills the lists the combos read");
        assertEquals(0, socket.selects(),
                "and it commits NOTHING - a preview that sent backend.select would "
                        + "re-point the session while the dialog is still open, which is "
                        + "the teardown its whole staging exists to avoid");

        assertTrue(bench.select(analyzer()),
                "the commit still runs after a preview - a preview must not fake it");
        assertEquals(1, socket.selects());
        assertTrue(bench.select(analyzer()),
                "re-affirming the committed selection answers true");
        assertEquals(1, socket.selects(),
                "and does it WITHOUT a second backend.select: re-pointing the manager "
                        + "starts with a disconnect, and the disconnect closes the "
                        + "generator lane the operator is listening to");
    }

    /**
     * The write the settings panel sends on Preferences OK - and the reason it
     * needs its own seam: spec 4.6 requires the analyzer's lock, and nothing holds
     * one while the dialog is open (the modules are stopped).  The locked call
     * takes the device for the request and gives it straight back.
     */
    @Test
    void aLockedWriteReallyMovesTheFrontPanelPort() {
        assertTrue(bench.select(analyzer()));

        Map<String, Object> answer = bench.callLocked(analyzer(),
                MessageType.QA40X_SETTINGS.getWire(), Map.of(NetFields.I2S_ENABLED, true));

        assertNotNull(answer, "the bench answered the write it was locked for");
        assertEquals(Boolean.TRUE, answer.get(NetFields.I2S_ENABLED),
                "and answers the state now in force, so the panel need not guess");
        assertTrue(socket.isI2sEnabled(), "the port moved on the analyzer itself");
    }

    /** The other write with no production sender before this: a committed active
     *  range has to reach the remote attenuator, or the range table is decoration. */
    @Test
    void aLockedRangeWriteReallyMovesTheAttenuator() {
        assertTrue(bench.select(analyzer()));

        assertNotNull(bench.callLocked(analyzer(),
                MessageType.QA40X_SET_INPUT_RANGE.getWire(),
                Map.of(NetFields.DBV, OTHER_INPUT_DBV)));

        assertEquals(OTHER_INPUT_DBV, socket.getActiveInputRangeDbv());
    }

    /** The lock is not KEPT: a client that held the analyzer after its write would
     *  leave the bench unusable to every other client (and to its own capture). */
    @Test
    void theDeviceIsGivenBackAfterTheWrite() {
        assertTrue(bench.select(analyzer()));
        bench.callLocked(analyzer(), MessageType.QA40X_SETTINGS.getWire(),
                Map.of(NetFields.I2S_ENABLED, true));

        Map<String, Object> ranges = bench.call(analyzer(),
                MessageType.QA40X_RANGES.getWire(), Map.of());
        assertNotNull(ranges);
        assertNull(bench.call(analyzer(), MessageType.QA40X_SETTINGS.getWire(),
                Map.of(NetFields.I2S_ENABLED, false)),
                "an unlocked write is refused again - so the lock the previous "
                        + "write took was really released");
    }

    /**
     * The operator pressing Disconnect: the generator lane and the device lock go
     * back ON THE WIRE, before the farewell - not abandoned to the server's own
     * teardown.
     *
     * <p><b>Why it matters even though the server tears down anyway.</b>  The
     * client re-dials five seconds later by itself, and a session whose locks are
     * still being swept up on the far end answers that retry {@code DEVICE_LOCKED}
     * - refused by its own dying predecessor.  Sending the release first closes
     * that window from this end rather than relying on how fast the other one
     * cleans up.
     *
     * <p>Both halves are asserted against what the BENCH saw, because the state
     * they change is torn down either way: a lane still counted here was never
     * given back, and a {@code device.release} in the conversation cannot have
     * been sent after the socket went - the client blocks for its answer.
     */
    @Test
    void anOperatorsDisconnectGivesTheLaneAndTheLockBackBeforeTheFarewell() {
        assertTrue(bench.select(analyzer()));
        NetDeviceManager manager =
                (NetDeviceManager) AudioBackend.instance().manager(AudioBackendType.NET);
        DeviceRef dac = manager.listOutputDevices().get(0);
        assertEquals(GEN_RATE_HZ, manager.openGenerator(dac, GEN_RATE_HZ, GEN_BITS, 0.0,
                OutputChannels.BOTH), "the bench granted its own rate for the lane");
        assertEquals(1, socket.openGenerators(), "the lane really is open on the bench");

        bench.disconnect();

        assertEquals(0, socket.openGenerators(),
                "the lane was closed on the wire - a generator left behind is a DAC "
                        + "still emitting that no other client can take");
        List<MessageType> seen = socket.conversation();
        assertTrue(seen.contains(MessageType.DEVICE_RELEASE),
                "and the device lock was given back: released only after the socket "
                        + "closed, it is a silent no-op and the bench holds the DAC "
                        + "until its own keepalive notices");
        assertTrue(seen.indexOf(MessageType.GEN_CLOSE) < seen.indexOf(MessageType.DEVICE_RELEASE),
                "the lane before the lock - spec 4.3's release also closes whatever "
                        + "is open on the device, so the other order tears the lane down "
                        + "sideways instead of closing it");
    }

    /**
     * The regression this seam exists for: {@code device.setCalibration} (spec
     * 4.3) is locked on the device it NAMES, not on whichever device of the
     * backend happened to be free.
     *
     * <p>Spec 4.6's rule - any one device of the analyzer - is the weaker one, and
     * it is what {@code callLocked} used to apply to everything: the lock hunt
     * tries inputs first, so a write aimed at the OUTPUT went out under the INPUT
     * lock and the server refused it {@code NOT_LOCKED}.  The failure was silent
     * by construction: {@code callLocked} answers null for a refusal, the
     * calibrate dialog had already moved the operator's own display scalars, and
     * the bench kept its old numbers - so the reading looked calibrated on this
     * screen and on nobody else's.
     */
    @Test
    void aCalibrationIsLockedOnTheDeviceItNamesAndNotOnAnyOtherOne() {
        assertTrue(bench.select(analyzer()));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(NetFields.BACKEND, AudioBackendType.QA40X.name());
        fields.put(NetFields.INDEX, 0);
        fields.put(NetFields.INPUT, false);          // the OUTPUT lane
        fields.put(NetFields.NAME, BenchSocket.DEVICE_NAME);
        fields.put(NetFields.FS_RMS_LEFT, FS_RMS_LEFT);
        fields.put(NetFields.FS_RMS_RIGHT, FS_RMS_RIGHT);

        assertNotNull(bench.callLocked(analyzer(),
                MessageType.DEVICE_SET_CALIBRATION.getWire(), fields),
                "the write names the output device, so the output device is what has "
                        + "to be held - locking the first free INPUT instead is the "
                        + "NOT_LOCKED the operator never sees");

        AudioDeviceManager manager = AudioBackend.instance().manager(AudioBackendType.NET);
        DeviceRef stored = manager.listOutputDevices().get(0);
        assertNotNull(stored.calibration(),
                "and the bench stored it, so the broadcast brought it back");
        assertEquals(FS_RMS_LEFT, stored.calibration().fsRmsLeft(), EPS);
        assertEquals(FS_RMS_RIGHT, stored.calibration().fsRmsRight(), EPS);
        assertNull(manager.listInputDevices().get(0).calibration(),
                "the OTHER direction of the same device is untouched - spec 4.3 keys "
                        + "cal on the device AND the direction");
    }

    @Test
    void anotherBenchsSelectionIsNotAnsweredByTheOneOnTheWire() {
        assertNull(bench.call(BackendKey.of(FOREIGN_ID, AudioBackendType.QA40X),
                MessageType.QA40X_INFO.getWire(), Map.of()),
                "a panel left open across a switch of bench must not have its reads "
                        + "answered by whichever server happens to be connected now");
    }

    // -------------------------------------------------------------------------
    // The bench under test
    // -------------------------------------------------------------------------

    /** The selection that names the analyzer on the CONNECTED server - what the
     *  Preferences dialog hands a settings panel. */
    private BackendKey analyzer() {
        return BackendKey.of(bench.getConnectedServer().serverId(), AudioBackendType.QA40X);
    }

    /** A port nobody is listening on, so two runs - or a developer's own server -
     *  never collide.  The probe is closed before the bench binds it. */
    private int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }
}
