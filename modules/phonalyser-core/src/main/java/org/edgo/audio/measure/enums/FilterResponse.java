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

package org.edgo.audio.measure.enums;

/** Approximation family used to synthesise a filter's magnitude response.
 *  Declaration order IS the Filter-response combo order.
 *  {@link #hasRipple()} distinguishes the equiripple families (whose passband
 *  or stopband ripple the UI must let the user set) from the monotonic ones. */
public enum FilterResponse {
    BESSEL,
    BUTTERWORTH,
    CHEBYSHEV,
    ELLIPTIC,
    INV_CHEBYSHEV;

    private FilterResponse() {}

    /** {@code true} for the families with a user-settable ripple parameter
     *  (Chebyshev I passband ripple, Elliptic passband ripple, and - reusing
     *  the same field - Inverse Chebyshev stopband ripple). */
    public boolean hasRipple() {
        return this == CHEBYSHEV || this == ELLIPTIC || this == INV_CHEBYSHEV;
    }
}
