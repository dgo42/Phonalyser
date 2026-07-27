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

package org.edgo.audio.measure.sound.qa40x;

import java.util.LinkedHashMap;
import java.util.Map;

import org.edgo.audio.measure.preferences.SubPreferences;

import lombok.Getter;
import lombok.Setter;

/**
 * The QA402/QA403 settings block — the {@code custom.qa40x} section of
 * preferences.yaml.  Owned by {@link Qa40xDeviceManager}, which registers it
 * with {@code Preferences}; the settings dialog edits it and the Preferences
 * dialog's OK commits it.
 *
 * <p>Holds each value twice, per the {@link SubPreferences} contract: the LIVE
 * value the backend runs on and gets saved, and the EDIT value the dialog is
 * changing until OK.
 */
public class Qa40xPreferences implements SubPreferences {

    private static final String KEY     = "qa40x";
    private static final String KEY_I2S = "i2s";

    /** Front-panel I2S expansion port, as the backend currently runs it. */
    @Getter
    private boolean i2sEnabled;
    /** The value the settings dialog is editing; reaches {@link #i2sEnabled}
     *  only through {@link #commitEdit()}. */
    @Getter
    @Setter
    private boolean i2sEnabledEdit;

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(KEY_I2S, i2sEnabled);
        return map;
    }

    @Override
    public void fromMap(Map<?, ?> map) {
        if (map.get(KEY_I2S) instanceof Boolean b) {
            i2sEnabled = b;
        }
        // Keep a dialog opened before this arrives consistent with the file.
        i2sEnabledEdit = i2sEnabled;
    }

    @Override
    public void beginEdit() {
        i2sEnabledEdit = i2sEnabled;
    }

    @Override
    public void commitEdit() {
        i2sEnabled = i2sEnabledEdit;
    }
}
