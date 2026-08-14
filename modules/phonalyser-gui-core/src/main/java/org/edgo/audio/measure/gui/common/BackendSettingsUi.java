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

package org.edgo.audio.measure.gui.common;

import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.preferences.Preferences;

/**
 * A backend's own settings panel - the QA40x front-panel I2S port and its
 * telemetry, for instance - offered to the Preferences dialog by the module that
 * owns that backend's UI.  Implementations are found through the service loader
 * and keyed by {@link #backendType()}; see {@code BackendSettingsRegistry}.
 *
 * <p>This replaces the {@code hasCustomPreferences()} / {@code openCustomPreferences(Shell)}
 * pair that used to sit on the core audio contract.  A device-manager interface has no
 * business naming a UI toolkit: it put SWT in the signature of every backend and
 * made a headless build impossible.  The direction is now inverted - the audio
 * layer knows nothing about a UI, and the UI asks whether one exists.  The
 * {@link #hasCustomPreferences()} below is that question asked of the panel itself,
 * where the answer belongs.
 *
 * <h2>Why this lives in the GUI module and not in core</h2>
 * The methods below traffic in {@code Shell} and {@code Control}.  Declaring the
 * contract in core would put SWT back into core's dependencies, which is exactly
 * the property the module split exists to protect.  Backends stay toolkit-free;
 * their optional UI layer sits above the GUI module and is discovered, never
 * compiled in - which is also why the GUI module must NOT depend on the modules
 * that implement this.
 *
 * <p>Implementations need a public no-argument constructor and must not build
 * widgets while constructing: the service loader instantiates every provider when
 * the registry is first indexed, long before any dialog should exist.  Create
 * widgets in {@link #open} / {@link #showForCapture} instead.
 */
public interface BackendSettingsUi {

    /** The backend these settings belong to.  Exactly one implementation may
     *  claim a given type. */
    AudioBackendType backendType();

    /**
     * Whether this backend has a settings panel at all - what the Preferences
     * dialog asks before it offers the per-backend settings button.
     *
     * <p>Registering a service is not the same as having something to configure:
     * an implementation may exist ONLY for its {@link #start()} wiring, because
     * that hook is where a backend's bus listeners are armed and there is no other
     * seam for them.  Such a backend has no custom preferences, and a button that
     * opened nothing would be worse than no button - so it answers {@code false}
     * and the dialog offers none.
     *
     * <p>Default {@code true}: a service that says nothing is one that came for
     * its panel, which is what every implementation with a panel does.
     */
    default boolean hasCustomPreferences() {
        return true;
    }

    /**
     * One-time bootstrap for this backend's UI layer, called by the registry as
     * it indexes the services - before any dialog exists.
     *
     * <p>Some backends need bus listeners live from startup rather than from the
     * first time a panel is opened.  The QA40x is the case in point: its range
     * bridge and its equal-rate constraint must be subscribed before the
     * Preferences dialog can publish a range change or move a rate combo.  Those
     * listeners used to be armed by the device manager's constructor, which meant
     * the driver had to name UI classes; now the UI layer arms its own.
     *
     * <p>Default no-op - a backend whose panel is purely on-demand implements
     * nothing.  Must not build widgets or touch hardware.
     */
    default void start() { }

    /**
     * The Preferences dialog is SHOWING this backend - restored on open, or just
     * picked in the combo.  Called after the selection's device catalogue is
     * readable and before the device combos and card sections fill from it.
     *
     * <p>This is a READ moment, not a commit: the selection is still staged, so
     * an implementation may align what it knows about the bench (the QA40x
     * mirrors the analyzer's calibration and ranges into its device card here,
     * which is what the card section is about to display) but must not change
     * the bench.  Default no-op.
     *
     * <p><b>Anything it aligns goes into {@code editCopy}, never into the live
     * preferences.</b>  The dialog edits a detached working copy and commits it
     * on OK, so a card written straight into the singleton would be invisible to
     * the very section that is about to read it - the QA40x's synced ranges did
     * not appear until the dialog was closed and reopened - and would survive a
     * Cancel that is supposed to drop it.
     *
     * @param selection which bench of this backend type is shown - local, or on
     *                  a Phonalyser server ({@link BackendKey#remote()})
     * @param editCopy  the dialog's working copy, the one place an alignment may
     *                  be written
     */
    default void onSelected(BackendKey selection, Preferences editCopy) { }

    /**
     * Opens the panel modally over {@code parent} and returns once the user has
     * dismissed it.  Whatever is accepted must stay PENDING - it reaches the live
     * settings and the file only when the Preferences dialog itself is closed with
     * OK, so that dialog's Cancel discards this too.
     *
     * <p>{@code selection} says WHICH bench of this backend type is being edited:
     * one wired to this machine, or the same backend reached on a Phonalyser
     * server ({@link BackendKey#remote()}).  The registry stays keyed by
     * {@link #backendType()} - what the bench IS, since a QA403's settings are a
     * QA403's settings wherever it is plugged in - and this parameter is how the
     * panel knows which one to read from and write to.  An implementation with
     * nothing remote to offer ignores a remote key.
     */
    void open(Shell parent, BackendKey selection);

    /**
     * A new Preferences session is starting: drop whatever the last one left
     * pending, so an edit its Cancel discarded cannot reach the hardware now.
     *
     * <p>Default no-op - a panel that stages its pending value somewhere that
     * already has this lifecycle (a backend's own edit copy) has nothing to do
     * here.
     */
    default void beginEdit() { }

    /**
     * The Preferences dialog was closed with OK - the one moment a panel's
     * pending value may take effect.
     *
     * <p>This exists because "OK was pressed" is a UI event and not a stored
     * setting: a panel whose value lives on the machine it edits (the front-panel
     * port of an analyzer on a Phonalyser server, say) has nothing to persist and
     * must not have to pretend it is a preferences block just to learn that the
     * operator accepted the dialog.
     *
     * <p>Default no-op, and called for EVERY registered panel - a panel with
     * nothing pending simply returns.
     */
    default void commitEdit() { }

    /** Opens the panel WITHOUT a modal loop, fully populated from the live device,
     *  so the screenshot automation can snapshot and dispose it.  Never touches
     *  the stored settings.  Pair with {@link #getContent()} and {@link #close()}. */
    void showForCapture(Shell parent);

    /** The panel's content control, or {@code null} when nothing is open.
     *
     *  <p>Deliberately the CONTENT and not the {@code Shell}: a top-level Shell
     *  prints blank on Windows, so the automation has to snapshot the composite
     *  inside it. */
    Control getContent();

    /** Closes and disposes whatever {@link #showForCapture} opened; no-op when
     *  nothing is open. */
    void close();
}
