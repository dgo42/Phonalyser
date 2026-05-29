package org.edgo.audio.measure.gui.fft;

import java.util.Arrays;

import org.edgo.audio.measure.fft.FftResult;

/**
 * Compiles a {@link ImdResult} from a {@link FftResult} when
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
    public static ImdResult analyze(FftResult r,
                                    double f1Cmd, double f2Cmd) {
        if (r == null || r.amplitudeDbV == null) return null;
        double binBw = r.freqResolution;
        if (!(binBw > 0)) return null;
        // ImdAnalyzer is purely voltage-based.  It reads exactly one
        // input from the FFT result — {@code amplitudeDbV} — and
        // produces voltage-domain outputs (V_rms tone amplitudes,
        // V_rms residual, dBV columns, voltage-ratio percents).
        // {@code amplitudeDbFs} is never consulted: the FFT pipeline
        // delivers a dBV-calibrated spectrum here, ImdAnalyzer just
        // measures voltages on top of it.
        double[] amplitudeDbV = r.amplitudeDbV;

        // The smaller-index ("lower") fundamental is always F1 even if
        // the user typed them in the other order, so the IMD sideband
        // labels (dnL = below F1, dnH = above F2) stay meaningful.
        double fLow  = Math.min(f1Cmd, f2Cmd);
        double fHigh = Math.max(f1Cmd, f2Cmd);

        // --- Detect F1 / F2 (argmax + quadratic refinement on dBV).
        Peak p1 = refinePeak(amplitudeDbV, binBw, fLow,  TONE_SEARCH_BINS);
        Peak p2 = refinePeak(amplitudeDbV, binBw, fHigh, TONE_SEARCH_BINS);
        if (p1 == null || p2 == null) return null;

        ImdResult out = new ImdResult();
        // Frequencies come from the analyzer's clean-frame sub-bin estimate
        // (the same honest method as the single-tone fundamental), NOT from
        // the peak of the coherently-collapsed average — which reads the bin
        // centre and hides the real sub-bin offset.  Fall back to the local
        // peak when the estimate is unavailable (e.g. no generator hint).
        out.f1Hz   = (r.fundamentalHzRefined > 0.0)
                ? r.fundamentalHzRefined  : p1.freqHz;
        out.f2Hz   = (!Double.isNaN(r.fundamental2HzRefined) && r.fundamental2HzRefined > 0.0)
                ? r.fundamental2HzRefined : p2.freqHz;
        out.f1DbV  = p1.levelDbV;
        out.f2DbV  = p2.levelDbV;
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
        double dfd2Mag     = readBinVrms(amplitudeDbV, binBw, out.f2Hz - out.f1Hz);
        double dfd3LowMag  = readBinVrms(amplitudeDbV, binBw, 2.0 * out.f1Hz - out.f2Hz);
        double dfd3HighMag = readBinVrms(amplitudeDbV, binBw, 2.0 * out.f2Hz - out.f1Hz);
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
            double magL = readBinVrms(amplitudeDbV, binBw, fL);
            double magH = readBinVrms(amplitudeDbV, binBw, fH);
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

        // --- TD+N as the scalar drop from total RMS to the
        // fundamentals — (Vrms − √(F1² + F2²)) / Vrms — computed
        // straight from the spectrum and WINDOW-INDEPENDENT.  Σ squared
        // bin voltages over the whole spectrum gives Vrms²; Σ over the
        // fundamental-skirt bins gives F1² + F2².  Both carry the
        // window's equivalent noise bandwidth, which cancels in the
        // ratio — correct for Hann, Blackman-Harris, … without knowing
        // which window the analyser used.
        //
        // F1 / F2 are stripped with the SAME dynamic skirt search
        // FftAnalyzer uses for its fundamental-exclusion zone: estimate
        // a leakage-immune noise floor (10th-percentile bin level) and
        // walk outward from each tone's peak while the level stays above
        // it, stopping at the first bin that dips into the noise.  A
        // fixed ±{@code TONE_SEARCH_BINS} window can't track BH7's wide
        // main lobe nor the tones' close-in phase noise; under heavy
        // coherent averaging that leftover skirt sits tens of dB above
        // the averaged-down floor and used to dominate the residual,
        // inflating TD+N.  The walk follows the measured skirt to the
        // floor whatever its width while stopping well short of the IMD
        // products (kHz away), which stay in the residual as they should.
        int nBins = amplitudeDbV.length;
        double floorDbV = noiseFloorDbV(amplitudeDbV);
        int binF1 = (int) Math.round(out.f1Hz / binBw);
        int binF2 = (int) Math.round(out.f2Hz / binBw);
        int lo1 = skirtEdge(amplitudeDbV, binF1, -1, floorDbV);
        int hi1 = skirtEdge(amplitudeDbV, binF1, +1, floorDbV);
        int lo2 = skirtEdge(amplitudeDbV, binF2, -1, floorDbV);
        int hi2 = skirtEdge(amplitudeDbV, binF2, +1, floorDbV);
        double sumAll      = 0.0;
        double sumResidual = 0.0;
        for (int b = 1; b < nBins; b++) {
            double vBin = Math.pow(10.0, amplitudeDbV[b] / 20.0);
            double sq = vBin * vBin;
            sumAll += sq;
            boolean inSkirt = (b >= lo1 && b <= hi1)
                           || (b >= lo2 && b <= hi2);
            if (!inSkirt) {
                sumResidual += sq;
            }
        }
        double vTotal = Math.sqrt(sumAll);
        double vFund  = Math.sqrt(sumAll - sumResidual);
        out.tdnPct = (vTotal > 0)
                ? 100.0 * (Math.sqrt((vTotal * vTotal) - (vFund * vFund))) / vTotal
                : 0.0;
        return out;
    }

    /** Picks the highest-dBV bin within ±{@code searchBins} of
     *  {@code centreHz} and refines its position via the standard
     *  3-point quadratic peak-interpolation formula.  Returns
     *  {@code null} when {@code centreHz} falls outside the
     *  representable bin range. */
    private static Peak refinePeak(double[] amplitudeDbV, double binBw,
                                   double centreHz, int searchBins) {
        int n = amplitudeDbV.length;
        int centre = (int) Math.round(centreHz / binBw);
        if (centre < 1 || centre >= n - 1) return null;
        int lo = Math.max(1, centre - searchBins);
        int hi = Math.min(n - 2, centre + searchBins);
        int kMax = lo;
        double vMax = amplitudeDbV[lo];
        for (int k = lo + 1; k <= hi; k++) {
            if (amplitudeDbV[k] > vMax) { vMax = amplitudeDbV[k]; kMax = k; }
        }
        // Quadratic peak refinement.  Skip if a neighbour is at the
        // array edge or if the parabola is degenerate (denom near 0).
        double yL = amplitudeDbV[kMax - 1];
        double yC = amplitudeDbV[kMax];
        double yR = amplitudeDbV[kMax + 1];
        double denom = (yL - 2 * yC + yR);
        double delta = (Math.abs(denom) < 1e-12) ? 0.0 : 0.5 * (yL - yR) / denom;
        if (delta < -0.5) delta = -0.5;
        if (delta >  0.5) delta =  0.5;
        Peak p = new Peak();
        p.freqHz   = (kMax + delta) * binBw;
        // Interpolated peak value (Smith & Serra, eqn. 6).
        p.levelDbV = yC - 0.25 * (yL - yR) * delta;
        return p;
    }

    /** Returns the V_rms voltage at the bin nearest {@code freqHz},
     *  read directly from a dBV spectrum.  Out-of-range frequencies
     *  return 0. */
    private static double readBinVrms(double[] amplitudeDbV, double binBw, double freqHz) {
        if (!(freqHz > 0)) return 0.0;
        int n = amplitudeDbV.length;
        int b = (int) Math.round(freqHz / binBw);
        if (b < 1 || b >= n) return 0.0;
        return Math.pow(10.0, amplitudeDbV[b] / 20.0);
    }

    /** Leakage-immune noise-floor estimate: the 10th-percentile bin
     *  level (dBV) across the spectrum (DC excluded).  Mirrors the
     *  {@code globalMedianNoisePow} model FftAnalyzer uses to size its
     *  dynamic fundamental-exclusion walk — the 10th percentile sits
     *  below the bulk of spurs and leakage, close to the true
     *  quantization / noise floor.  dBV is monotonic in power, so the
     *  percentile bin is identical whether taken on levels or powers. */
    private static double noiseFloorDbV(double[] amplitudeDbV) {
        int n = amplitudeDbV.length;
        if (n <= 1) return Double.NEGATIVE_INFINITY;
        double[] sorted = new double[n - 1];
        System.arraycopy(amplitudeDbV, 1, sorted, 0, n - 1);
        Arrays.sort(sorted);
        return sorted[sorted.length / 10];
    }

    /** Walks from {@code peakBin} in direction {@code dir} (+1 / −1)
     *  while bins stay above {@code floorDbV}, returning the farthest
     *  bin still on the fundamental's skirt.  This is the same dynamic
     *  exclusion FftAnalyzer performs around its fundamental: follow the
     *  measured skirt (window main lobe + close-in phase noise) down to
     *  the noise floor, whatever its width and whichever window is in
     *  use — no fixed window that BH7's wide lobe could leak past. */
    private static int skirtEdge(double[] amplitudeDbV, int peakBin,
                                 int dir, double floorDbV) {
        int n = amplitudeDbV.length;
        int b = peakBin;
        while (b + dir >= 1 && b + dir < n && amplitudeDbV[b + dir] > floorDbV) {
            b += dir;
        }
        return b;
    }

    private static final class Peak {
        double freqHz;
        double levelDbV;
    }
}
