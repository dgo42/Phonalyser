package org.edgo.audio.measure.gui.fft;

/**
 * One slot of intermodulation-distortion measurements computed from a
 * dual-tone FFT spectrum.  Companion to {@code FftResult}:
 * attached when the generator's waveform is
 * {@code GenSignalForm.DUAL_TONE}; null otherwise.
 *
 * <p>Two fundamentals F1, F2 (refined via quadratic peak interpolation
 * around the commanded frequencies) plus their dBFS / dBV levels.
 * IMD products follow the CCIF / DIN difference-frequency convention:
 *
 * <ul>
 *   <li>{@code dfd2Db} — the {@code f2 − f1} component (2nd-order
 *       difference-frequency distortion).</li>
 *   <li>{@code dfd3Db} — combined 3rd-order: √(|F(2f₁−f₂)|² +
 *       |F(2f₂−f₁)|²).</li>
 *   <li>{@code dnL[n] / dnH[n]} — n-th sideband below F1 (lower) /
 *       above F2 (upper); n indexes 2..5.  {@code dnL[n] = level at
 *       n·f₁ − (n−1)·f₂},  {@code dnH[n] = level at n·f₂ − (n−1)·f₁}.</li>
 *   <li>{@code imdPwrPct} — combined intermod RMS as a percentage of
 *       the fundamental reference |F1| + |F2| (linear-magnitude sum
 *       of the two fundamentals).</li>
 *   <li>{@code tdnPct} — total distortion + noise as the scalar drop
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

    /** Refined F1 / F2 frequencies (Hz).  Quadratic-peak-interpolated
     *  from the FFT bins nearest the commanded frequencies. */
    public double f1Hz;
    public double f2Hz;
    /** F1 / F2 linear magnitudes in the FFT's full-scale unit
     *  (0..1).  Convert to dBV via the same anchor the THD path uses. */
    public double f1Mag;
    public double f2Mag;
    /** F1 / F2 dBV values — the canonical level readout for both
     *  tones.  ImdAnalyzer is voltage-domain only, so it never
     *  produces dBFs values; the FFT view derives those at display
     *  time from {@code f1DbV − 20·log10(adcFsVoltageRms)} when it
     *  needs a dBFs column. */
    public double f1DbV;
    public double f2DbV;

    /** {@code f2 − f1} (Hz) — the difference frequency tracked by the
     *  DFD2 measurement. */
    public double diffHz;

    /** DFD2 amplitude as a percentage of the fundamental reference. */
    public double dfd2Pct;
    /** DFD3 amplitude as a percentage of the fundamental reference. */
    public double dfd3Pct;

    /** IMD power ratio (combined IM products / fundamentals), in %.
     *  Reference is the linear-magnitude sum |F1| + |F2|. */
    public double imdPwrPct;
    /** Total distortion + noise as the scalar drop from total RMS to
     *  the fundamentals — {@code 100·(Vrms − √(F1² + F2²)) / Vrms} over
     *  squared bin voltages, the F1 / F2 skirts forming the
     *  fundamental term.  Window-independent. */
    public double tdnPct;

    /** Frequencies of the per-order sideband products, Hz.
     *  Indexed by order in [2, {@link #MAX_ORDER}].  Slots 0/1 unused. */
    public final double[] dnLHz = new double[MAX_ORDER + 1];
    public final double[] dnHHz = new double[MAX_ORDER + 1];
    /** Levels of the per-order sideband products, in % of the
     *  fundamental reference |F1| + |F2|.  Slots 0/1 unused. */
    public final double[] dnLPct = new double[MAX_ORDER + 1];
    public final double[] dnHPct = new double[MAX_ORDER + 1];
    /** Same levels expressed as dBV using the FS-voltage anchor. */
    public final double[] dnLDbV = new double[MAX_ORDER + 1];
    public final double[] dnHDbV = new double[MAX_ORDER + 1];
}
