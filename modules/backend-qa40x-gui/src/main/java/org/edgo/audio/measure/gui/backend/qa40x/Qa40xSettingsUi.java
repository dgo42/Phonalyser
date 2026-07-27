/*
 * Phonalyser — precision audio measurement workbench.
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

import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.gui.common.BackendSettingsUi;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.AudioDeviceManager;
import org.edgo.audio.measure.sound.qa40x.Qa40xDeviceManager;
import org.edgo.audio.measure.sound.qa40x.Qa40xPreferences;

/**
 * Registers the QA40x settings panel with the Preferences dialog, and owns the
 * pending-edit round trip that {@code Qa40xDeviceManager} used to do.
 *
 * <p>A thin adapter rather than {@link Qa40xSettingsDialog} implementing the
 * service directly: the service loader needs a public no-argument constructor and
 * instantiates every provider when the registry is first indexed, whereas the
 * dialog needs its parent shell and a live device reading before it can exist.
 * Keeping them apart means discovery costs nothing and no widget is built until
 * the user actually asks for the panel.
 */
public final class Qa40xSettingsUi implements BackendSettingsUi {

    /** Non-null only between {@link #showForCapture} and {@link #close} — the
     *  screenshot automation needs the instance to survive across calls.  The
     *  modal {@link #open} path keeps nothing. */
    private Qa40xSettingsDialog capture;

    @Override
    public AudioBackendType backendType() {
        return AudioBackendType.QA40X;
    }

    /**
     * Arms the two loose singletons that make up this backend's UI-side wiring.
     * Both are bus listeners with no other owner: the range bridge routes a
     * Preferences-committed active-range change to the open device, and the rate
     * constraint keeps input and output on the QA40x's one shared clock.
     *
     * <p>The device manager's constructor used to call these, which forced the
     * driver module to name classes that live here — a cycle once the two became
     * separate modules.  Starting them from the UI side is the whole point: a
     * headless build has no Preferences dialog to publish those events, so it
     * needs neither listener.
     */
    @Override
    public void start() {
        Qa40xRangeController.instance();
        Qa40xRateConstraint.instance();
    }

    @Override
    public void open(Shell parent) {
        Qa40xDeviceManager manager = manager();
        if (manager == null) return;
        Qa40xPreferences settings = manager.getSettings();
        // What the user accepts stays PENDING: it reaches the live settings (and
        // the file) only when the Preferences dialog itself is closed with OK, so
        // that dialog's Cancel discards this too.
        settings.setI2sEnabledEdit(
                new Qa40xSettingsDialog(parent, manager.readDeviceInfo())
                        .open(settings.isI2sEnabledEdit()));
    }

    @Override
    public void showForCapture(Shell parent) {
        Qa40xDeviceManager manager = manager();
        if (manager == null) return;
        capture = new Qa40xSettingsDialog(parent, manager.readDeviceInfo());
        // Never writes the settings back — a help capture must not mutate prefs.
        capture.showForCapture(manager.getSettings().isI2sEnabledEdit());
    }

    @Override
    public Control getContent() {
        return capture == null ? null : capture.getContent();
    }

    @Override
    public void close() {
        if (capture != null) {
            Control content = capture.getContent();
            if (content != null && !content.isDisposed()) {
                content.getShell().close();
            }
            capture = null;
        }
    }

    /** The QA40x manager, or {@code null} when this build ships no QA40x backend
     *  — the UI module can be present without its driver. */
    private Qa40xDeviceManager manager() {
        if (!AudioBackend.instance().isAvailable(AudioBackendType.QA40X)) {
            return null;
        }
        AudioDeviceManager m = AudioBackend.instance().manager(AudioBackendType.QA40X);
        return (m instanceof Qa40xDeviceManager qa40x) ? qa40x : null;
    }
}
