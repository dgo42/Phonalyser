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

package org.edgo.audio.measure.enums;

import java.util.Locale;

/** Window function used by the FFT analyser.  The constant name IS the short token
 *  used at the CLI / yaml edge (parsed back via {@link #valueOf}) and as the compact
 *  tile label; the long display label is i18n via {@link #labelKey()}. */
public enum WindowType {
    RECT,
    HANN,
    BH4,
    BH7,
    FT,
    HFT144D,
    HFT248D,
    KB24,
    KB38,
    DC150,
    DC200,
    DC250,
    DC300;

    private WindowType() {}

    /** i18n key for the long display label (window combo + tile tooltip). */
    public String labelKey() {
        return "fft.window." + name().toLowerCase(Locale.ROOT);
    }
}
