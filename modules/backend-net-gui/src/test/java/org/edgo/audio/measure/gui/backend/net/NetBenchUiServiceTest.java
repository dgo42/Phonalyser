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

import java.util.Map;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.gui.common.RemoteBackendRegistry;
import org.edgo.audio.measure.gui.common.RemoteBackendUi;
import org.edgo.audio.measure.net.proto.MessageType;
import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.preferences.Preferences;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The discovery wiring itself: the Preferences dialog reaches this module through
 * a service file and nothing else, so a typo in that file's NAME or its content
 * costs the whole feature - silently, with a server button that never appears and
 * no error anywhere.  This is the test that would catch it.
 *
 * <p>No display is needed: the registry only has to FIND the implementation, and
 * everything asserted below is what it answers before a server exists.
 */
class NetBenchUiServiceTest {

    @BeforeEach
    void keepThePreferencesFileOutOfIt() {
        // The service's constructor registers its settings block against the live
        // singleton; transient mode makes sure this test cannot write the file.
        Preferences.instance().setTransientMode(true);
    }

    @Test
    void theServerListIsFoundThroughTheServiceLoader() {
        RemoteBackendUi ui = RemoteBackendRegistry.instance().getUi();
        assertNotNull(ui, "META-INF/services/...RemoteBackendUi must name this module's "
                + "implementation, or the Preferences dialog shows no server button at all");
        assertInstanceOf(NetBenchUi.class, ui);
    }

    @Test
    void withNoServerConnectedThereIsNothingRemoteToOffer() {
        RemoteBackendUi ui = RemoteBackendRegistry.instance().getUi();
        assertTrue(ui.entries().isEmpty(),
                "the combo gets remote entries from a CONNECTED server, never from the "
                        + "remembered list - an entry no device can be opened on is worse "
                        + "than no entry");
        assertFalse(ui.select(BackendKey.of("b7e0-bench-a", AudioBackendType.QA40X)),
                "and a selection cannot be committed on a session that does not exist");
    }

    @Test
    void aBackendsOwnRequestNeedsTheSessionThatReachesThatBench() {
        RemoteBackendUi ui = RemoteBackendRegistry.instance().getUi();
        BackendKey remote = BackendKey.of("b7e0-bench-a", AudioBackendType.QA40X);

        assertNull(ui.call(remote, MessageType.QA40X_INFO.getWire(), Map.of()),
                "net protocol 4.6 over a session that does not exist: a settings panel "
                        + "gets 'cannot read', never a value from somewhere else");
        assertNull(ui.call(BackendKey.of(AudioBackendType.QA40X),
                MessageType.QA40X_INFO.getWire(), Map.of()),
                "a LOCAL selection has no session to send anything over - the panel "
                        + "reads that analyzer through its driver instead");
        assertNull(ui.call(null, MessageType.QA40X_INFO.getWire(), Map.of()));
    }

}
