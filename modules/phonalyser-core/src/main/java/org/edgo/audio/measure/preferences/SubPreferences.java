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

package org.edgo.audio.measure.preferences;

import java.util.Map;

/**
 * A block of preferences owned by one component rather than by
 * {@link Preferences} itself — the settings a single backend has and no other
 * does (the QA40x front-panel I2S port, for instance).  The owner registers an
 * implementation with {@link Preferences#registerCustomPreferences}, and
 * {@code Preferences} then persists it and drives its edit lifecycle without
 * knowing what is inside.
 *
 * <p><b>Two values, not one.</b> An implementation keeps a LIVE value (what the
 * app runs on and what gets saved) and an EDIT value (what the settings dialog
 * is changing).  {@link #beginEdit()} seeds edit from live when the Preferences
 * dialog opens; {@link #commitEdit()} copies edit into live when that dialog is
 * closed with OK.  Cancel simply never commits, so an abandoned edit dies with
 * the dialog — the same contract as {@code Preferences.copyForDialog} /
 * {@code applyFromDialog} for the ordinary preferences.
 *
 * <p><b>Registration can arrive late.</b> Backend managers are built lazily, so
 * an implementation typically registers long after {@code Preferences.load()}
 * has read the file.  {@code registerCustomPreferences} therefore replays the
 * stored block into {@link #fromMap} at registration time; an implementation
 * must tolerate being handed values at any moment.
 */
public interface SubPreferences {

    /** This block's prefix inside the {@code custom} section of
     *  preferences.yaml — one per owning backend ({@code qa40x} for the
     *  QA402/QA403).  Unique across implementations and stable across releases:
     *  it is what saved files are keyed by.  Lower-case, no spaces. */
    String key();

    /** The LIVE values, as a YAML-friendly map (scalars, lists, nested maps).
     *  Called on save; an empty map writes an empty block. */
    Map<String, Object> toMap();

    /** Restores LIVE values from a previously saved block.  Absent or
     *  unrecognised entries must keep the current value rather than throw — the
     *  file may come from an older or newer release. */
    void fromMap(Map<?, ?> map);

    /** Seeds the edit values from the live ones; called when the Preferences
     *  dialog opens, so a previously cancelled edit never leaks into the next
     *  session of the dialog. */
    void beginEdit();

    /** Copies the edit values into the live ones; called only when the
     *  Preferences dialog is closed with OK. */
    void commitEdit();
}
