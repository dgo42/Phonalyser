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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.swt.widgets.Shell;

import org.edgo.audio.measure.gui.common.RemoteBackendUi;
import org.edgo.audio.measure.preferences.BackendKey;

/**
 * A bench on the far end that records instead of sending: the seam this module
 * reaches a remote analyzer through, registered by the ordinary
 * {@code ServiceLoader} contract so {@code RemoteBackendRegistry} finds it.
 *
 * <p>It is a RECORDER and not a mock because what these tests ask is "did the
 * committed change leave this machine, aimed at that bench, as that message":
 * the value, the message name and the selection are all separately wrong-able,
 * and only the recorded call has all three.
 */
public final class StubRemoteBench implements RemoteBackendUi {

    /** One request as it was handed to the seam. */
    public record Call(BackendKey selection, String request, Map<String, Object> fields,
            boolean locked) {
    }

    private final List<Call> calls = new ArrayList<>();
    /** What this bench ANSWERS, by request wire name.  A recorder is enough for
     *  a write, but a read has to have something to say: the QA40x card sync
     *  rebuilds an analyzer's whole range table out of two of these payloads. */
    private final Map<String, Map<String, Object>> answers = new LinkedHashMap<>();

    /** Public and no-argument for the service loader. */
    public StubRemoteBench() {
    }

    /** Every call since the last {@link #clear()}, in order. */
    public List<Call> calls() {
        return List.copyOf(calls);
    }

    /** Seeds what {@code request} is answered with - the bench's own state, as
     *  spec 4.6 shapes it. */
    public void answer(String request, Map<String, Object> payload) {
        answers.put(request, payload);
    }

    public void clear() {
        calls.clear();
        answers.clear();
    }

    @Override
    public void openServerList(Shell parent) {
        throw new UnsupportedOperationException("no server list in a headless test");
    }

    @Override
    public List<Entry> entries() {
        return List.of();
    }

    @Override
    public boolean select(BackendKey key) {
        return true;
    }

    @Override
    public boolean preview(BackendKey key) {
        return true;
    }

    @Override
    public Map<String, Object> call(BackendKey selection, String request,
            Map<String, Object> fields) {
        calls.add(new Call(selection, request, fields, false));
        return answers.getOrDefault(request, Map.of());
    }

    @Override
    public Map<String, Object> callLocked(BackendKey selection, String request,
            Map<String, Object> fields) {
        calls.add(new Call(selection, request, fields, true));
        return Map.of();
    }
}
