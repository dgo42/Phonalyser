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

package org.edgo.audio.measure.gui.fft;

import org.edgo.audio.measure.enums.AlignGenerator;

/**
 * Thread-safe singleton factory for {@link FrequencyAligner}s: maps an
 * {@link AlignGenerator} mode to a fresh aligner instance.  Access via
 * {@link #instance()}.  Double-checked locking matches the rest of the GUI's
 * singletons (e.g. {@code MessageBus}, {@code Preferences}).
 */
public final class FrequencyAlignerFactory {

    private static volatile FrequencyAlignerFactory instance;

    private FrequencyAlignerFactory() {}

    public static FrequencyAlignerFactory instance() {
        FrequencyAlignerFactory local = instance;
        if (local != null) {
            return local;
        }
        synchronized (FrequencyAlignerFactory.class) {
            if (instance == null) {
                instance = new FrequencyAlignerFactory();
            }
            return instance;
        }
    }

    /** A fresh aligner for {@code mode}, or {@code null} for {@link AlignGenerator#NONE}. */
    public FrequencyAligner create(AlignGenerator mode) {
        return switch (mode) {
            case PID  -> new FrequencyPid();
            case FLL  -> new FrequencyFll();
            case NONE -> null;
        };
    }
}
