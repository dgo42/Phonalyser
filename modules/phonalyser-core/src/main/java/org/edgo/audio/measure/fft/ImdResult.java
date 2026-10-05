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

package org.edgo.audio.measure.fft;

/**
 * One slot of intermodulation-distortion measurements computed from a
 * dual-tone FFT spectrum.  Carried by {@link FftResult#imd}: attached
 * whenever the spectrum holds a second tone
 * ({@link FftResult#fundamental2HzRefined} above 0); null otherwise.
 *
 * <p>Two fundamentals F1, F2 (F1 the lower) plus their dBFS levels, each
 * level refined via quadratic peak interpolation around its tone.  Every
 * absolute level here is full-scale relative (dBFS, or linear with full
 * scale = 1); the dBV lift is the reader's, one constant offset per value.
 * IMD products follow the CCIF / DIN difference-frequency convention:
 *
 * <ul>
 *   <li>{@code dfd2Pct} - the {@code f2 − f1} component (2nd-order
 *       difference-frequency distortion).</li>
 *   <li>{@code dfd3Pct} - combined 3rd-order: √(|F(2f₁−f₂)|² +
 *       |F(2f₂−f₁)|²).</li>
 *   <li>{@code dnL[n] / dnH[n]} - n-th sideband below F1 (lower) /
 *       above F2 (upper); n indexes 2..5.  {@code dnL[n] = level at
 *       n·f₁ − (n−1)·f₂},  {@code dnH[n] = level at n·f₂ − (n−1)·f₁}.</li>
 *   <li>{@code imdPwrPct} - combined intermod RMS as a percentage of
 *       the fundamental reference |F1| + |F2| (linear-magnitude sum
 *       of the two fundamentals).</li>
 *   <li>{@code tdnPct} - total distortion + noise as the scalar drop
 *       from total RMS to the fundamentals,
 *       {@code 100·(Vrms − √(F1² + F2²)) / Vrms}, where {@code Vrms²}
 *       and {@code F1² + F2²} are summed squared bin voltages over the
 *       whole spectrum and over the F1 / F2 skirts respectively.
 *       Window-independent (the window's ENBW cancels in the
 *       ratio), so it's correct for Hann, Blackman-Harris, etc.</li>
 * </ul>
 *
 * <p>All arrays sized for {@code n = 0..5} with {@code [0]} / {@code [1]}
 * unused (so {@code dnL[2]} reads naturally as "d₂L").  Frequencies are
 * stored alongside the levels so the view can plot dot annotations
 * without recomputing.
 */
public final class ImdResult {

    /** Max IMD-order index (d2..d5).  Array slots {@code 0..1} unused. */
    public static final int MAX_ORDER = 5;

    /** Refined F1 / F2 frequencies (Hz) - the analyzer's sub-bin tone
     *  estimates, ordered so F1 is always the lower tone. */
    public double f1Hz;
    public double f2Hz;
    /** F1 / F2 linear magnitudes in the FFT's full-scale unit (full
     *  scale = 1).  When a manual fundamental is supplied these carry the
     *  TRUE combined level split across the two tones (the notch-suppressed
     *  measurement can't); otherwise they are the measured level.  The
     *  anchored level in dBFS is {@code 20·log10(f1Mag)}. */
    public double f1Mag;
    public double f2Mag;
    /** F1 / F2 MEASURED level in dBFS (straight off the spectrum peak) - used for
     *  the dBFS column, the spectrum markers and autoscale, so they stay on the
     *  real peak even when {@link #f1Mag} is anchored to a manual fundamental. */
    public double f1DbFs;
    public double f2DbFs;

    /** {@code f2 − f1} (Hz) - the difference frequency tracked by the
     *  DFD2 measurement. */
    public double diffHz;

    /** DFD2 amplitude as a percentage of the fundamental reference.
     *  {@code NaN} when {@code f2 − f1} is outside the measurable range. */
    public double dfd2Pct;
    /** DFD3 amplitude as a percentage of the fundamental reference -
     *  RMS of the measurable sidebands; {@code NaN} when both {@code 2f1 − f2}
     *  and {@code 2f2 − f1} fall outside the spectrum. */
    public double dfd3Pct;

    /** IMD power ratio (combined IM products / fundamentals), in %.
     *  Reference is the linear-magnitude sum |F1| + |F2|. */
    public double imdPwrPct;
    /** Total distortion + noise as the scalar drop from total RMS to
     *  the fundamentals - {@code 100·(Vrms − √(F1² + F2²)) / Vrms} over
     *  squared bin voltages, the F1 / F2 skirts forming the
     *  fundamental term.  Window-independent. */
    public double tdnPct;

    /** Frequencies of the per-order sideband products, Hz.
     *  Indexed by order in [2, {@link #MAX_ORDER}].  Slots 0/1 unused. */
    public final double[] dnLHz = new double[MAX_ORDER + 1];
    public final double[] dnHHz = new double[MAX_ORDER + 1];
    /** Levels of the per-order sideband products, in % of the
     *  fundamental reference |F1| + |F2|.  Slots 0/1 unused.  {@code NaN}
     *  when the product frequency is outside the measurable range (below
     *  DC or beyond the spectrum at this sample rate). */
    public final double[] dnLPct = new double[MAX_ORDER + 1];
    public final double[] dnHPct = new double[MAX_ORDER + 1];
    /** Same levels expressed in dBFS, read off the spectrum bin;
     *  {@code NaN} for unmeasurable products (the readout shows "---"). */
    public final double[] dnLDbFs = new double[MAX_ORDER + 1];
    public final double[] dnHDbFs = new double[MAX_ORDER + 1];

    /** Returns an independent copy: scalars by value, every per-order array
     *  copied, so a later rewrite of this slot does not reach the copy. */
    public ImdResult copy() {
        ImdResult c = new ImdResult();
        c.f1Hz      = f1Hz;
        c.f2Hz      = f2Hz;
        c.f1Mag     = f1Mag;
        c.f2Mag     = f2Mag;
        c.f1DbFs    = f1DbFs;
        c.f2DbFs    = f2DbFs;
        c.diffHz    = diffHz;
        c.dfd2Pct   = dfd2Pct;
        c.dfd3Pct   = dfd3Pct;
        c.imdPwrPct = imdPwrPct;
        c.tdnPct    = tdnPct;
        System.arraycopy(dnLHz,   0, c.dnLHz,   0, dnLHz.length);
        System.arraycopy(dnHHz,   0, c.dnHHz,   0, dnHHz.length);
        System.arraycopy(dnLPct,  0, c.dnLPct,  0, dnLPct.length);
        System.arraycopy(dnHPct,  0, c.dnHPct,  0, dnHPct.length);
        System.arraycopy(dnLDbFs, 0, c.dnLDbFs, 0, dnLDbFs.length);
        System.arraycopy(dnHDbFs, 0, c.dnHDbFs, 0, dnHDbFs.length);
        return c;
    }
}
