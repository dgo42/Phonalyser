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

import java.util.ArrayList;
import java.util.List;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.gui.common.RemoteBackendUi;
import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.sound.AudioBackend;

/**
 * What the Preferences dialog's backend picker offers, and which of it is
 * selected: the LOCAL backends this OS can open, followed by one entry per
 * backend of the server that is connected.
 *
 * <p>Kept apart from the widget because it is the piece with rules rather than
 * pixels: labels and keys must stay index-aligned (an item's key names the
 * SERVER as well, which is what keeps two benches' device settings apart), and
 * the fallback when a selection disappears - a disconnect, or a restart while
 * the bench is switched off - decides what the whole dialog then edits.  A
 * combo cannot be asked those questions without a display; this can.
 *
 * <p><b>The combo is never empty.</b>  {@link AudioBackendType#NET} answers
 * {@code isAvailable()} false by design (it owns no local hardware), so it never
 * appears bare, and every OS has at least one local backend that does.
 */
final class BackendChoices {

    /** Selectable backends in combo order; index-aligned with {@link #labels}. */
    private final List<BackendKey> keys = new ArrayList<>();
    private final List<String> labels = new ArrayList<>();

    /**
     * Re-composes the list: the local backends first, in enum order, then
     * {@code remote} as the connected server offered it.
     *
     * <p>Locals first on purpose - they are what the dialog falls back to, and a
     * fallback that moved with the server list would not be a fallback.
     */
    void rebuild(List<RemoteBackendUi.Entry> remote) {
        keys.clear();
        labels.clear();
        for (AudioBackendType type : AudioBackendType.values()) {
            if (type.isAvailable() && locallyPresent(type)) {
                keys.add(BackendKey.of(type));
                labels.add(type.getDisplayName());
            }
        }
        for (RemoteBackendUi.Entry entry : remote) {
            keys.add(entry.key());
            labels.add(entry.label());
        }
    }

    /** Whether {@code type} has anything to open here RIGHT NOW.  Only the QA40x
     *  is asked: its {@code isAvailable()} means "the USB binding loads", which
     *  is true with no analyzer on the cable at all - and a backend whose every
     *  open must fail is not a choice but a trap: the local QA40x entry used to
     *  stand while the analyzer was attached to a server VM instead.
     *  The sound-card backends are NOT enumerated here: their availability
     *  already means the host API exists, they virtually always have endpoints,
     *  and enumerating WDM-KS on every recompose is a multi-second stall. */
    private boolean locallyPresent(AudioBackendType type) {
        if (type != AudioBackendType.QA40X) {
            return true;
        }
        return !AudioBackend.instance().listInputDevices(type).isEmpty();
    }

    /** The item labels, in combo order. */
    List<String> labels() {
        return List.copyOf(labels);
    }

    /** The selection behind item {@code index}. */
    BackendKey at(int index) {
        return keys.get(index);
    }

    /** Where {@code key} sits, or -1 when it is not selectable (any more). */
    int indexOf(BackendKey key) {
        return keys.indexOf(key);
    }

    /**
     * The selection to actually show for {@code wanted}: itself while it is
     * still offered, else the first local backend.
     *
     * <p>That substitution is not cosmetic - the dialog EDITS the selection it
     * shows, so a combo left on a backend the dialog is not editing would write
     * the shown backend's devices into the other one's settings.
     *
     * <p>An EMPTY offer keeps {@code wanted} instead: substituting needs
     * something to substitute, and a build that reached this state has no combo
     * to show anyway - a dialog that failed to build is worse than one whose
     * picker is empty.
     */
    BackendKey resolve(BackendKey wanted) {
        if (keys.isEmpty() || (wanted != null && keys.contains(wanted))) {
            return wanted;
        }
        return keys.get(0);
    }
}
