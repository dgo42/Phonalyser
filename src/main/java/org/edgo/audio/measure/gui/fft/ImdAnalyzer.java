package org.edgo.audio.measure.gui.fft;

import org.edgo.audio.measure.fft.FftAnalyzer;

/**
 * Compiles a {@link ImdResult} from a {@link FftAnalyzer.Result} when
 * the generator is in {@code DUAL_TONE} mode.  Lives next to
 * {@code FftAnalyzerWorker} (which invokes it) but stays a pure
 * function — no state, no threads, no widgets.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li><b>Tone detection</b> — search a small bin window (±
 *       {@value #TONE_SEARCH_BINS}) around each commanded frequency
 *       (already snapped to the FFT grid when the user enabled snap),
 *       pick the bin with the highest dBFS, refine with quadratic
 *       peak interpolation.</li>
 *   <li><b>IMD product levels</b> — for each {@code n ∈ [2, 5]} read
 *       the nearest bin to {@code n·f₁ − (n−1)·f₂} (lower) and
 *       {@code n·f₂ − (n−1)·f₁} (upper).  Also read the {@code f₂ − f₁}
 *       bin for DFD2 and the {@code 2f₁ − f₂} / {@code 2f₂ − f₁} bins
 *       for DFD3.</li>
 *   <li><b>Ratios</b> — the IMD-product reference is {@code |F1| +
 *       |F2|} (linear-magnitude sum of the two fundamentals).  Each
 *       dnL / dnH product is expressed as a percent of that
 *       reference; IMDpwr is the RMS sum of all listed products,
 *       same denominator.  TD+N is computed differently: it's the
 *       residual RMS (everything that isn't F1 or F2) as a fraction
 *       of the total signal Vrms, derived via Parseval from the
 *       spectrum itself — so it matches what
 *       {@code √(Vrms² − F1² − F2²) / Vrms} would yield if the
 *       analyser had access to the raw time-domain signal.</li>
 * </ol>
 */
public final class ImdAnalyzer {

    /** Half-width of the bin window scanned around each commanded
     *  frequency.  Small enough that we never latch onto an IMD
     *  product as a fundamental, large enough to compensate for
     *  sample-rate / FFT-length rounding when snap is off. */
    private static final int TONE_SEARCH_BINS = 8;

    private ImdAnalyzer() {}

    /** Builds an {@link ImdResult} from {@code r} using {@code f1Cmd}
     *  and {@code f2Cmd} as the commanded tone frequencies (Hz).
     *  Returns {@code null} when there isn't enough data to compute
     *  (e.g. spectrum array is missing or the requested frequencies
     *  fall outside the analysed band). */
    public static ImdResult analyze(FftAnalyzer.Result r,
                                    double f1Cmd, double f2Cmd,
                                    double adcFsVoltageRms) {
        if (r == null || r.amplitudeDbFs == null) return null;
        double binBw = r.freqResolution;
        if (!(binBw > 0)) return null;
        // The smaller-index ("lower") fundamental is always F1 even if
        // the user typed them in the other order, so the IMD sideband
        // labels (dnL = below F1, dnH = above F2) stay meaningful.
        double fLow  = Math.min(f1Cmd, f2Cmd);
        double fHigh = Math.max(f1Cmd, f2Cmd);

        // --- Detect F1 / F2.
        Peak p1 = refinePeak(r.amplitudeDbFs, binBw, fLow,  TONE_SEARCH_BINS);
        Peak p2 = refinePeak(r.amplitudeDbFs, binBw, fHigh, TONE_SEARCH_BINS);
        if (p1 == null || p2 == null) return null;

        // dBV anchor: lift dBFs onto the dBV scale.  The offset is a
        // pure hardware-calibration constant {@code 20·log10(FS_Vrms)}
        // — independent of which tone is the "fundamental" and of
        // any single-tone detection logic.  The caller passes
        // {@code adcFsVoltageRms} (= the ADC full-scale RMS voltage
        // in volts) directly so this calculation doesn't have to
        // reach back into the {@code r.fundamentalDbFs /
        // r.fundRefDbV} pair (which is signal-dependent and tied to
        // the FFT's single-tone fundamental detection).
        double dbvAnchor = (adcFsVoltageRms > 0)
                ? 20.0 * Math.log10(adcFsVoltageRms)
                : 0.0;

        ImdResult out = new ImdResult();
        out.f1Hz   = p1.freqHz;
        out.f2Hz   = p2.freqHz;
        out.f1DbFs = p1.levelDbFs;
        out.f2DbFs = p2.levelDbFs;
        out.f1DbV  = p1.levelDbFs + dbvAnchor;
        out.f2DbV  = p2.levelDbFs + dbvAnchor;
        // f1Mag / f2Mag are V_rms in volts (= 10^(dBV/20), since
        // dBV is referenced to 1 V_rms).
        out.f1Mag  = Math.pow(10.0, out.f1DbV / 20.0);
        out.f2Mag  = Math.pow(10.0, out.f2DbV / 20.0);
        out.diffHz = out.f2Hz - out.f1Hz;

        // Reference magnitude for ratios = |F1| + |F2| in V_rms.
        // Floored at a tiny positive so the % divide doesn't blow up
        // when both tones are muted.
        double refMag = Math.max(1e-12, out.f1Mag + out.f2Mag);

        // --- DFD2 (= f2 − f1) and DFD3 (= 2f1 − f2 / 2f2 − f1).
        double dfd2Mag     = readBinVrms(r.amplitudeDbFs, binBw, dbvAnchor, out.f2Hz - out.f1Hz);
        double dfd3LowMag  = readBinVrms(r.amplitudeDbFs, binBw, dbvAnchor, 2.0 * out.f1Hz - out.f2Hz);
        double dfd3HighMag = readBinVrms(r.amplitudeDbFs, binBw, dbvAnchor, 2.0 * out.f2Hz - out.f1Hz);
        double dfd3Mag     = Math.sqrt(dfd3LowMag * dfd3LowMag + dfd3HighMag * dfd3HighMag);
        out.dfd2Pct = 100.0 * dfd2Mag / refMag;
        out.dfd3Pct = 100.0 * dfd3Mag / refMag;

        // --- n-th-order IMD products (n = 2..5).  CCIF/DIN naming:
        //   d2L = f2 − f1            (2nd-order difference)
        //   d2H = f1 + f2            (2nd-order sum)
        //   dnL = (n−1)·f1 − (n−2)·f2  for n ≥ 3 (sideband below F1)
        //   dnH = (n−1)·f2 − (n−2)·f1  for n ≥ 3 (sideband above F2)
        // So d3L / d3H are the 3rd-order intermod products
        // 2f1 − f2 and 2f2 − f1; d4 is 5th-order; d5 is 7th-order.
        double imdPwrSq = 0.0;
        for (int k = 2; k <= ImdResult.MAX_ORDER; k++) {
            double fL, fH;
            if (k == 2) {
                fL = out.f2Hz - out.f1Hz;          // difference
                fH = out.f2Hz + out.f1Hz;          // sum
            } else {
                fL = (k - 1) * out.f1Hz - (k - 2) * out.f2Hz;
                fH = (k - 1) * out.f2Hz - (k - 2) * out.f1Hz;
            }
            double magL = readBinVrms(r.amplitudeDbFs, binBw, dbvAnchor, fL);
            double magH = readBinVrms(r.amplitudeDbFs, binBw, dbvAnchor, fH);
            out.dnLHz[k]  = fL;
            out.dnHHz[k]  = fH;
            out.dnLPct[k] = 100.0 * magL / refMag;
            out.dnHPct[k] = 100.0 * magH / refMag;
            out.dnLDbV[k] = 20.0 * Math.log10(Math.max(1e-30, magL));
            out.dnHDbV[k] = 20.0 * Math.log10(Math.max(1e-30, magH));
            imdPwrSq += magL * magL + magH * magH;
        }
        // Include DFD2 / DFD3 components in the combined IMD power.
        imdPwrSq += dfd2Mag * dfd2Mag + dfd3LowMag * dfd3LowMag + dfd3HighMag * dfd3HighMag;
        out.imdPwrPct = 100.0 * Math.sqrt(imdPwrSq) / refMag;

        // --- TD+N as a scalar residual fraction in V_rms:
        // {@code (Vrms − √(F1² + F2²)) / Vrms}.  Every bin's dBV is
        // linearised to V_rms and squared; for a Hann-windowed
        // signal {@code Σ V_rms,bin² = (3/2) · V_rms,signal²}
        // (NG/cohGain² = 1.5), so the actual signal Vrms is
        // {@code √(Σ / 1.5)}.  Vrms shares the spectrum's averaging
        // and phase-rejection state with F1 / F2, so the two terms
        // stay synchronised tick to tick.
        double sumSqV = 0.0;
        int nBins = r.amplitudeDbFs.length;
        for (int b = 1; b < nBins; b++) {
            double vBin = Math.pow(10.0, (r.amplitudeDbFs[b] + dbvAnchor) / 20.0);
            sumSqV += vBin * vBin;
        }
        // 1.5 = NG/cohGain² for the Hann window the analyser uses;
        // converts Parseval's bin-sum to the actual signal V_rms².
        double vrms = Math.sqrt(sumSqV / 1.5);
        if (vrms > 0) {
            double tonesPythag = Math.sqrt(out.f1Mag * out.f1Mag + out.f2Mag * out.f2Mag);
            out.tdnPct = 100.0 * Math.max(0.0, vrms - tonesPythag) / vrms;
        } else {
            out.tdnPct = 0.0;
        }
        return out;
    }

    /** Picks the highest-dBFS bin within ±{@code searchBins} of
     *  {@code centreHz} and refines its position via the standard
     *  3-point quadratic peak-interpolation formula.  Returns
     *  {@code null} when {@code centreHz} falls outside the
     *  representable bin range. */
    private static Peak refinePeak(double[] amplitudeDbFs, double binBw,
                                   double centreHz, int searchBins) {
        int n = amplitudeDbFs.length;
        int centre = (int) Math.round(centreHz / binBw);
        if (centre < 1 || centre >= n - 1) return null;
        int lo = Math.max(1, centre - searchBins);
        int hi = Math.min(n - 2, centre + searchBins);
        int kMax = lo;
        double vMax = amplitudeDbFs[lo];
        for (int k = lo + 1; k <= hi; k++) {
            if (amplitudeDbFs[k] > vMax) { vMax = amplitudeDbFs[k]; kMax = k; }
        }
        // Quadratic peak refinement.  Skip if a neighbour is at the
        // array edge or if the parabola is degenerate (denom near 0).
        double yL = amplitudeDbFs[kMax - 1];
        double yC = amplitudeDbFs[kMax];
        double yR = amplitudeDbFs[kMax + 1];
        double denom = (yL - 2 * yC + yR);
        double delta = (Math.abs(denom) < 1e-12) ? 0.0 : 0.5 * (yL - yR) / denom;
        if (delta < -0.5) delta = -0.5;
        if (delta >  0.5) delta =  0.5;
        Peak p = new Peak();
        p.freqHz    = (kMax + delta) * binBw;
        // Interpolated peak value (Smith & Serra, eqn. 6).
        p.levelDbFs = yC - 0.25 * (yL - yR) * delta;
        return p;
    }

    /** Returns the linear magnitude (0..1 full-scale) at the bin
     *  nearest {@code freqHz}.  Out-of-range frequencies return 0. */
    private static double readBinMag(double[] amplitudeDbFs, double binBw, double freqHz) {
        if (!(freqHz > 0)) return 0.0;
        int n = amplitudeDbFs.length;
        int b = (int) Math.round(freqHz / binBw);
        if (b < 1 || b >= n) return 0.0;
        return Math.pow(10.0, amplitudeDbFs[b] / 20.0);
    }

    private static final class Peak {
        double freqHz;
        double levelDbFs;
    }
}
