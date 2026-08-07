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

package org.edgo.audio.measure.gui.sound;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.swt.widgets.Shell;
import org.edgo.audio.measure.gui.common.RemoteBackendUi;
import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.sound.DeviceRef;

import lombok.Getter;
import lombok.Setter;

/**
 * A bench that records what was sent to it and answers whatever the test tells
 * it to - the seam that makes "this reached the wire" and, more importantly,
 * "this did NOT reach the wire" assertable.
 *
 * <p>Registered through {@code META-INF/services} because
 * {@code RemoteBackendRegistry} indexes the ONE implementation the service loader
 * finds and holds it final: what it holds is a live session, not a lookup result,
 * so there is deliberately no setter to swap it mid-run.  The registry is a
 * process singleton and so is this stub - every test that uses it
 * {@link #reset()}s before and after, which is the restore that keeps one test's
 * recorded traffic out of the next one.
 *
 * <p>Only {@code call} / {@code callLocked} do anything; the rest of the
 * interface answers what a build with no bench in the room already answers.
 */
public final class RecordingRemoteBackendUi implements RemoteBackendUi {

    /** One request as it went out - the bench, the message type, the fields with
     *  it, and whether the caller took the device lock for it. */
    public record Sent(BackendKey bench, String request, Map<String, Object> fields,
            boolean locked) {
    }

    @Getter
    private final List<Sent> sent = new ArrayList<>();
    /** What {@code call} / {@code callLocked} answer.  {@code null} is the bench
     *  refusing (or being unreachable), which is the contract both callers read. */
    @Setter
    private Map<String, Object> answer = Map.of();

    /** Wire names this bench answers "no" to, whatever {@link #answer} is - what a
     *  test needs when one message of a SEQUENCE is refused and the rest are not
     *  (a card the bench already has, followed by the values-only fallback). */
    private final Set<String> refusals = new HashSet<>();

    /** The client this bench says is measuring on its devices (spec 4.3's
     *  {@code lock:{by}}), or null while they are free.  One name for every
     *  device: what a test stages is that a holder EXISTS and reaches the
     *  operator, not which of two devices it took. */
    @Setter
    private String lockedBy;

    /** Forgets every recorded request and goes back to accepting - the state a
     *  test must find and must leave behind. */
    public void reset() {
        sent.clear();
        refusals.clear();
        answer = Map.of();
        lockedBy = null;
    }

    /** Makes this bench refuse one message type and go on accepting the rest. */
    public void refuse(String request) {
        refusals.add(request);
    }

    /** Every recorded request of one type, in the order they went out. */
    public List<Sent> sentOf(String request) {
        List<Sent> of = new ArrayList<>();
        for (Sent one : sent) {
            if (one.request().equals(request)) {
                of.add(one);
            }
        }
        return of;
    }

    /** The single request recorded, when exactly one was sent - the usual
     *  assertion; fails loudly rather than picking one otherwise. */
    public Sent onlySent() {
        if (sent.size() != 1) {
            throw new IllegalStateException("expected exactly one request, recorded " + sent);
        }
        return sent.get(0);
    }

    @Override
    public void openServerList(Shell parent) {
        // no server list in a test
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
    public String lockedBy(DeviceRef device) {
        return lockedBy;
    }

    @Override
    public Map<String, Object> call(BackendKey selection, String request,
            Map<String, Object> fields) {
        return record(selection, request, fields, false);
    }

    @Override
    public Map<String, Object> callLocked(BackendKey selection, String request,
            Map<String, Object> fields) {
        return record(selection, request, fields, true);
    }

    /** {@code LinkedHashMap} rather than {@code Map.copyOf}: {@code card: null} is
     *  how spec 4.3 unbinds, and the immutable copy refuses null values. */
    private Map<String, Object> record(BackendKey selection, String request,
            Map<String, Object> fields, boolean locked) {
        sent.add(new Sent(selection, request, new LinkedHashMap<>(fields), locked));
        return refusals.contains(request) ? null : answer;
    }
}
