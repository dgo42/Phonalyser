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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.gui.bus.ActiveRange;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.RemoteBackendRegistry;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.net.proto.NetFields;
import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.qa40x.Qa40xProtocol;

import static org.edgo.audio.measure.enums.AudioBackendType.QA40X;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Qa40xRangeController} on the bench it cannot reach by USB: a QA403 on a
 * Phonalyser server, whose attenuator moves over the net protocol's range
 * commands (spec 4.6) instead of over a register write.
 *
 * <p>Driven headless through the real {@link MessageBus} with the real
 * {@code RemoteBackendRegistry} - the seam behind it is a recorder
 * ({@link StubRemoteBench}) found by the ordinary service loader, so what is
 * asserted is exactly what would have gone on the wire.
 *
 * <p>The local branch is deliberately not driven here: it needs an open QA40x
 * session, which needs a device.  What matters for the remote one is that the
 * committed change LEAVES the machine at all - before this, nothing in
 * production ever sent {@code qa40x.setInputRange}, and a range table for a
 * remote analyzer was decoration.
 */
class Qa40xRangeControllerTest {

    /** The analyzer on a server - {@code type()} QA40X, reached through the net
     *  carrier, which is what the controller has to key on. */
    private static final BackendKey REMOTE_QA40X =
            BackendKey.of("b7e0-bench-uuid", QA40X);
    private static final BackendKey LOCAL_QA40X = BackendKey.of(QA40X);
    /** Positions the analyzer really has (doc §6), and not the ones a fresh card
     *  starts on, so a write that never happened cannot pass for one that did. */
    private static final int INPUT_DBV = 42;
    private static final int OUTPUT_DBV = -12;
    /** A row label of some other device's card - the event is generic and may
     *  describe any card at all. */
    private static final String FOREIGN_LABEL = "2 Vrms";

    private final MessageBus bus = MessageBus.instance();
    private final StubRemoteBench bench =
            (StubRemoteBench) RemoteBackendRegistry.instance().getUi();

    @BeforeEach
    void setUp() {
        // The selection is the process-wide singleton's; transient mode keeps the
        // real preferences.yaml out of it.
        Preferences.instance().setTransientMode(true);
        Qa40xRangeController.instance();      // arm the singleton subscriber
        bench.clear();
    }

    @AfterEach
    void tearDown() {
        Preferences.instance().setSelectedBackend(BackendKey.of(AudioBackendType.JAVASOUND));
        bench.clear();
    }

    @Test
    void aCommittedInputRangeReachesTheBenchAsTheProtocolsRangeCommand() {
        Preferences.instance().setSelectedBackend(REMOTE_QA40X);

        publish(true, Qa40xProtocol.rangeLabel(INPUT_DBV));

        List<StubRemoteBench.Call> calls = bench.calls();
        assertEquals(1, calls.size(), "exactly one range write");
        StubRemoteBench.Call call = calls.get(0);
        assertEquals(REMOTE_QA40X, call.selection(),
                "aimed at the bench the operator is on, not at 'the QA40x'");
        assertEquals(MessageType.QA40X_SET_INPUT_RANGE.getWire(), call.request());
        assertEquals(INPUT_DBV, call.fields().get(NetFields.DBV),
                "the label is resolved back to the dBV the analyzer takes");
        assertTrue(call.locked(), "spec 4.6 requires the analyzer's lock for a write, "
                + "and nothing else holds it while Preferences is open");
    }

    @Test
    void aCommittedOutputRangeGoesToTheOutputCommand() {
        Preferences.instance().setSelectedBackend(REMOTE_QA40X);

        publish(false, Qa40xProtocol.rangeLabel(OUTPUT_DBV));

        assertEquals(1, bench.calls().size());
        assertEquals(MessageType.QA40X_SET_OUTPUT_RANGE.getWire(),
                bench.calls().get(0).request());
        assertEquals(OUTPUT_DBV, bench.calls().get(0).fields().get(NetFields.DBV));
    }

    @Test
    void aLabelNoQa40xRangeCarriesIsNotSentAnywhere() {
        Preferences.instance().setSelectedBackend(REMOTE_QA40X);

        publish(true, FOREIGN_LABEL);

        assertTrue(bench.calls().isEmpty(),
                "the event names no card, so a label that is not a QA40x position "
                        + "belongs to some other device's card");
    }

    @Test
    void aLocalAnalyzerIsStillDrivenLocally() {
        Preferences.instance().setSelectedBackend(LOCAL_QA40X);

        publish(true, Qa40xProtocol.rangeLabel(INPUT_DBV));

        assertTrue(bench.calls().isEmpty(),
                "a QA403 wired to this machine is re-ranged over USB - putting it "
                        + "on the wire would send it to a bench nobody selected");
    }

    private void publish(boolean input, String label) {
        bus.publish(Events.DEVICE_ACTIVE_RANGE_CHANGED, new ActiveRange(input, label));
    }
}
