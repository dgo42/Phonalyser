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

package org.edgo.audio.measure.gui.preferences;

import java.util.List;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.gui.common.RemoteBackendUi;
import org.edgo.audio.measure.preferences.BackendKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the Preferences dialog's backend picker is made of, proved without a
 * display: local backends plus the connected server's, and what happens to the
 * selection when that server goes away.
 *
 * <p>The local half is whatever the running OS offers, so the assertions are
 * about SHAPE and ORDER rather than about a particular backend being present -
 * a test that named WASAPI would fail on the Linux and macOS builds for a reason
 * that has nothing to do with this model.
 */
class BackendChoicesTest {

    private static final String BENCH_A = "b7e0-bench-a";
    private static final String BENCH_B = "b7e0-bench-b";
    private static final String LABEL_A = "Bench QA403 -> QA40x";
    private static final String LABEL_B = "Lab bench -> JavaSound";

    private final BackendChoices choices = new BackendChoices();

    @Test
    void withNoServerTheListIsTheLocalBackendsAndIsNeverEmpty() {
        choices.rebuild(List.of());

        List<String> labels = choices.labels();
        assertFalse(labels.isEmpty(),
                "every OS has at least one local backend, so the combo always has an item");
        for (int i = 0; i < labels.size(); i++) {
            assertFalse(choices.at(i).remote(), "nothing remote without a server");
        }
        assertFalse(labels.contains(AudioBackendType.NET.getDisplayName()),
                "the net carrier is how a remote bench is REACHED, never an item of its own");
    }

    @Test
    void aConnectedServersBackendsAreAppendedAfterTheLocalOnes() {
        choices.rebuild(List.of());
        int locals = choices.labels().size();

        choices.rebuild(List.of(entry(BENCH_A, AudioBackendType.QA40X, LABEL_A),
                entry(BENCH_B, AudioBackendType.JAVASOUND, LABEL_B)));

        List<String> labels = choices.labels();
        assertEquals(locals + 2, labels.size());
        assertEquals(LABEL_A, labels.get(locals), "server entries come after the local ones");
        assertEquals(LABEL_B, labels.get(locals + 1));
        BackendKey remote = choices.at(locals);
        assertTrue(remote.remote());
        assertEquals(BENCH_A, remote.serverId(),
                "the key carries the SERVER, which is what keeps two benches' settings apart");
        assertEquals(AudioBackendType.QA40X, remote.type(),
                "and the backend it IS, which is what a backend-semantic rule keys on");
    }

    @Test
    void aSelectionThatIsStillOfferedIsKept() {
        BackendKey bench = BackendKey.of(BENCH_A, AudioBackendType.QA40X);
        choices.rebuild(List.of(entry(BENCH_A, AudioBackendType.QA40X, LABEL_A)));

        assertEquals(bench, choices.resolve(bench));
        assertEquals(bench, choices.at(choices.indexOf(bench)),
                "labels and keys stay index-aligned, or the combo would select another bench");
    }

    @Test
    void aDisconnectFallsBackToALocalBackendRatherThanToAnEmptyCombo() {
        BackendKey bench = BackendKey.of(BENCH_A, AudioBackendType.QA40X);
        choices.rebuild(List.of(entry(BENCH_A, AudioBackendType.QA40X, LABEL_A)));
        assertEquals(bench, choices.resolve(bench));

        choices.rebuild(List.of());            // the operator disconnected

        assertEquals(-1, choices.indexOf(bench), "its entry is gone with the session");
        BackendKey fallback = choices.resolve(bench);
        assertFalse(fallback.remote(),
                "a bench that cannot be reached cannot stay selected - the dialog EDITS "
                        + "what it shows, so it falls back to a local backend");
        assertEquals(choices.at(0), fallback, "the first local backend, deterministically");
    }

    @Test
    void anUnknownSelectionResolvesRatherThanThrowing() {
        // A preferences.yaml saved on another machine, or a bench that was never
        // connected in this session: the dialog must still open.
        choices.rebuild(List.of());
        assertEquals(choices.at(0), choices.resolve(null));
        assertEquals(choices.at(0),
                choices.resolve(BackendKey.of(BENCH_B, AudioBackendType.QA40X)));
    }

    @Test
    void anEmptyOfferKeepsTheSelectionInsteadOfThrowing() {
        // Nothing has been offered at all - no rebuild has run yet, which is the
        // shape of a build with no local backend and no bench.  This is asked
        // while the dialog is being BUILT, so an exception here would take the
        // whole window with it.
        BackendKey bench = BackendKey.of(BENCH_A, AudioBackendType.QA40X);

        assertTrue(choices.labels().isEmpty());
        assertEquals(bench, choices.resolve(bench), "there is nothing to fall back TO");
        assertNull(choices.resolve(null));
    }

    private RemoteBackendUi.Entry entry(String serverId, AudioBackendType type, String label) {
        return new RemoteBackendUi.Entry(BackendKey.of(serverId, type), label);
    }
}
