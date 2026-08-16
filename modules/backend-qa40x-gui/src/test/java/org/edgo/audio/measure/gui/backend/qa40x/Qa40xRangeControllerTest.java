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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.gui.bus.ActiveRange;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.RemoteBackendRegistry;
import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.qa40x.Qa40xProtocol;

import static org.edgo.audio.measure.enums.AudioBackendType.QA40X;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Qa40xRangeController} is the LOCAL re-range path, and what is pinned
 * here is what it does NOT do: it puts nothing on the wire.
 *
 * <p>An analyzer on a server is re-ranged by its settings panel's own commit
 * (spec 4.6's range commands, sent once the staged position is known to differ
 * from the one the bench reported in force), so a controller that also sent the
 * event on would move the same attenuator twice.
 *
 * <p>Driven headless through the real {@link MessageBus} with the real
 * {@code RemoteBackendRegistry} - the seam behind it is a recorder
 * ({@link StubRemoteBench}) found by the ordinary service loader, so anything
 * that DID leave this machine would be recorded here.
 *
 * <p>The local re-range itself is deliberately not driven: it needs an open
 * QA40x session, which needs a device.
 */
class Qa40xRangeControllerTest {

    /** The analyzer on a server - {@code type()} QA40X, reached through the net
     *  carrier, which is what the controller has to key on. */
    private static final BackendKey REMOTE_QA40X =
            BackendKey.of("b7e0-bench-uuid", QA40X);
    private static final BackendKey LOCAL_QA40X = BackendKey.of(QA40X);
    /** A position the analyzer really has (doc §6), and not the one a fresh card
     *  starts on, so a write that DID happen cannot pass unnoticed. */
    private static final int INPUT_DBV = 42;
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

    /**
     * The no-double-send guard: with a bench selected and a real attenuator
     * position committed, this controller puts NOTHING on the wire.  The
     * analyzer's settings panel sends that write on the same OK, and a second
     * one from here would move the attenuator twice - the second time from an
     * event that carries no bench of its own.
     */
    @Test
    void aRemoteSelectionIsNotSentFromHere() {
        Preferences.instance().setSelectedBackend(REMOTE_QA40X);

        publish(true, Qa40xProtocol.rangeLabel(INPUT_DBV));

        assertTrue(bench.calls().isEmpty(),
                "the range write for a bench belongs to the settings panel's commit, "
                        + "which knows what that bench reported in force");
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
