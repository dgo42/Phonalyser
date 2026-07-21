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
    RECT   (1.0),
    HANN   (1.5),
    BH4    (2.0044),
    BH7    (2.6303),
    FT     (3.7702),
    HFT144D(4.5386),
    HFT248D(5.6512),
    KB24   (2.8013),
    KB38   (3.5072),
    DC150  (2.3660),
    DC200  (2.7259),
    DC250  (3.0435),
    DC300  (3.3310);

    /** Equivalent noise bandwidth in bins: {@code N·Σw²/(Σw)²} for this window's
     *  actual samples (the cosine-sum windows equal the closed form
     *  {@code (a0² + ½·Σ a_k²)/a0²}; Kaiser/Dolph-Chebyshev computed numerically).
     *  Broadband noise measured through the FFT reads {@code 10·log10(enbw)} dB
     *  above its true level (the tone reads dead-on), so the generator's dither
     *  dBV readout adds that term to stay checkable against the FFT noise floor. */
    private final double enbw;

    private WindowType(double enbw) {
        this.enbw = enbw;
    }

    /** Equivalent noise bandwidth of this window, in bins. */
    public double enbw() {
        return enbw;
    }

    /** i18n key for the long display label (window combo + tile tooltip). */
    public String labelKey() {
        return "fft.window." + name().toLowerCase(Locale.ROOT);
    }
}
