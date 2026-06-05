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

package org.edgo.audio.measure.gui.preferences;

import lombok.Getter;
import lombok.Setter;

/**
 * Per-backend settings.  Devices are stored by name (string) — the
 * concrete {@code DeviceRef} is re-resolved at dialog open time by
 * matching the saved name against the live device list.
 */
@Getter
@Setter
public class BackendPrefs {
    private String inputDeviceName;
    private String outputDeviceName;
    private int    inputSampleRate  = 384000;
    private int    inputBitDepth    = 24;
    private int    outputSampleRate = 384000;
    private int    outputBitDepth   = 24;

    /** Copies all fields from {@code src} into this instance. */
    public void copyFrom(BackendPrefs src) {
        this.inputDeviceName  = src.inputDeviceName;
        this.outputDeviceName = src.outputDeviceName;
        this.inputSampleRate  = src.inputSampleRate;
        this.inputBitDepth    = src.inputBitDepth;
        this.outputSampleRate = src.outputSampleRate;
        this.outputBitDepth   = src.outputBitDepth;
    }

    /** Returns a snapshot of this instance suitable for later restoration via {@link #copyFrom}. */
    public BackendPrefs snapshot() {
        BackendPrefs copy = new BackendPrefs();
        copy.copyFrom(this);
        return copy;
    }
}
