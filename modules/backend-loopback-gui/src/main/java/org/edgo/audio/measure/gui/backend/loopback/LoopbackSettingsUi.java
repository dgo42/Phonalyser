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

package org.edgo.audio.measure.gui.backend.loopback;

import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.gui.common.BackendSettingsUi;
import org.edgo.audio.measure.preferences.BackendKey;

/**
 * Arms the digital loopback's UI-side wiring.  The backend has NO settings of its
 * own - it is a software bench with nothing to configure beyond the rate and depth
 * the Preferences dialog already offers - so this service exists for
 * {@link #start()} alone: the format constraint is a bus listener with no other
 * owner, and it must be subscribed before the dialog can move a combo.
 *
 * <p>Arming it from the UI side is the whole point.  The device manager's
 * constructor could not do it without the driver module naming a class that lives
 * here, which is a cycle once the two are separate modules; and a headless build
 * has no Preferences dialog to publish those events, so it needs no listener at
 * all.
 *
 * <p>{@link #hasCustomPreferences()} is therefore {@code false}: the loopback's
 * whole configuration is the rate and depth combos the Preferences dialog already
 * shows, and a settings button that opened nothing would be worse than no button.
 */
public final class LoopbackSettingsUi implements BackendSettingsUi {

    @Override
    public AudioBackendType backendType() {
        return AudioBackendType.LOOPBACK;
    }

    /** {@inheritDoc}
     *
     *  <p>False: this service exists for {@link #start()} alone.  There is nothing
     *  to configure beyond the rate and depth the dialog already offers, so it
     *  offers no button for this backend. */
    @Override
    public boolean hasCustomPreferences() {
        return false;
    }

    /** Subscribes the format constraint - the one thing this service is for. */
    @Override
    public void start() {
        LoopbackFormatConstraint.instance();
    }

    // The four panel methods below are unreachable while hasCustomPreferences()
    // answers false: nothing offers this backend a settings button, so nothing
    // opens, captures or closes a panel that does not exist.

    @Override
    public void open(Shell parent, BackendKey selection) {
        // no panel to open
    }

    @Override
    public void showForCapture(Shell parent) {
        // no panel to capture
    }

    @Override
    public Control getContent() {
        return null;
    }

    @Override
    public void close() {
        // no panel to close
    }
}
