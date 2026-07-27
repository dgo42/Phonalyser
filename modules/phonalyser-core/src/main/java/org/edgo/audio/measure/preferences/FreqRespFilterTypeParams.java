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

import org.edgo.audio.measure.enums.FilterType;

import lombok.Data;

/**
 * Per-{@link FilterType} snapshot of every Frequency-Response filter parameter
 * scalar plus the by-order/by-spec mode radio.  {@link Preferences} keeps one of
 * these per filter type in {@code freqRespFilterParamsByType} — the single
 * source of truth for the filter scalars.  The tab control binds its widgets
 * directly to the current type's entry (edit writes through, a type switch
 * reloads the new type's entry), and each {@link FreqRespPreset} embeds one copy
 * for its captured type.
 *
 * <p>The per-type defaults from {@link #fromType(FilterType)} are the pinned
 * edge-semantics-valid seeds — LP needs stop &gt; pass, HP needs stop &lt; pass,
 * BP needs SB &gt; PB, NOTCH needs PB &gt; SB — so a freshly selected type always
 * yields a drawable curve.
 */
@Data
public class FreqRespFilterTypeParams {
    private boolean modeOrder     = false;
    private double  rippleDb      = 1.0;
    private double  stopAttenDb   = 40.0;
    private double  centerHz      = 1000.0;
    private double  passHz        = 1000.0;
    private double  stopHz        = 2000.0;
    private double  orderPassHz   = 1000.0;
    private double  orderRippleDb = 1.0;
    private int     order         = 4;
    private double  q             = 1.0;

    /**
     * Pinned per-type defaults whose passband/stopband edges satisfy each
     * type's edge-ordering semantics (so the by-spec curve always draws).
     * Only the by-spec edge triplet (centerHz / passHz / stopHz) varies by
     * type; the common scalars are shared.
     *
     * <p>The transition is pinned at a 4:1 prototype stop ratio (ws = 4).  A
     * tighter 2:1 default is feasible for every closed-form family but not for
     * Bessel: its soft roll-off cannot reach the default 40 dB stop attenuation
     * at ws = 2 for ANY order (it saturates around 14 dB), so {@code ofSpec}
     * pins Bessel to the design's MAX_ORDER cap.  At ws = 4 every family —
     * Bessel included — resolves to a sensible sub-cap order (Bessel &rarr; 5,
     * the others &le; 4) while the pinned edge ordering holds.
     */
    public static FreqRespFilterTypeParams fromType(FilterType type) {
        FreqRespFilterTypeParams p = new FreqRespFilterTypeParams();
        switch (type) {
            case LOW_PASS -> {
                p.centerHz = 1000.0; // unused for LP
                p.passHz   = 1000.0;
                p.stopHz   = 4000.0; // stop > pass (4:1)
            }
            case HIGH_PASS -> {
                p.centerHz = 1000.0; // unused for HP
                p.passHz   = 1000.0;
                p.stopHz   = 250.0;  // stop < pass (4:1)
            }
            case BAND_PASS -> {
                p.centerHz = 1000.0;
                p.passHz   = 500.0;  // PB
                p.stopHz   = 2000.0; // SB > PB (4:1)
            }
            case NOTCH -> {
                p.centerHz = 1000.0;
                p.passHz   = 1000.0; // PB > SB (4:1)
                p.stopHz   = 250.0;  // SB
            }
        }
        return p;
    }
}
