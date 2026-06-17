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

    /** Builds an {@link ImdResult} from {@code r} using {@code f1Cmd}
     *  and {@code f2Cmd} as the commanded tone frequencies (Hz) and
     *  {@code dbvOffsetDb} as the dBFS→dBV anchor (the caller's cached
     *  Preferences value).  Returns {@code null} when there isn't enough
     *  data to compute (e.g. spectrum array is missing or the requested
     *  frequencies fall outside the analysed band). */
    public ImdResult analyze(FftResult r,
                             double f1Cmd, double f2Cmd, double dbvOffsetDb) {
        if (r == null || r.amplitudeDbFs == null) return null;
        double binBw = r.freqResolution;
        if (!(binBw > 0)) return null;
        // All internal math runs on the raw dBFS spectrum — peak picking, the
        // skirt walk and every figure of merit (dnL/dnH %, DFD2/3 %, IMD-power %,
        // TD+N %) are offset-invariant: a constant dB shift moves every bin
        // equally and cancels in each ratio.  Only the ABSOLUTE outputs — the
        // V_rms tone / product magnitudes and the dBV columns — apply
        // dbvOffsetDb, at the dozen scalars instead of per spectrum bin (the
        // old per-tick full-spectrum dBV copy was multi-MB at large FFTs).
        double[] amplitudeDbFs = r.amplitudeDbFs;

        // The smaller-index ("lower") fundamental is always F1 even if
        // the user typed them in the other order, so the IMD sideband
        // labels (dnL = below F1, dnH = above F2) stay meaningful.
        double fLow  = Math.min(f1Cmd, f2Cmd);
        double fHigh = Math.max(f1Cmd, f2Cmd);

        // --- Detect F1 / F2 (argmax + quadratic refinement; offset-invariant,
        // so it runs on the raw dBFS bins).
        Peak p1 = refinePeak(amplitudeDbFs, binBw, fLow,  TONE_SEARCH_BINS);
        Peak p2 = refinePeak(amplitudeDbFs, binBw, fHigh, TONE_SEARCH_BINS);
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
        // Manual fundamental override: the mandatory twin-T notch suppresses the
        // measured tones, so when the user supplies the true level it is the
        // TRUE COMBINED level — split across the two tones by their measured
        // ratio (equal tones at 1 V together → each √(½) = −3.01 dBV).  Only the
        // absolute dBV / V_rms outputs are anchored; the spectrum dBFS is left
        // untouched, and the ratio = product/(|F1|+|F2|) stays honest.
        out.f1DbFs = p1.levelDbFs;   // measured — drives the dBFS column + markers
        out.f2DbFs = p2.levelDbFs;
        double f1Lvl = p1.levelDbFs;
        double f2Lvl = p2.levelDbFs;
        if (Double.isFinite(r.fundamentalTrueDbFs)) {
            double m1 = Math.pow(10.0, p1.levelDbFs / 20.0);
            double m2 = Math.pow(10.0, p2.levelDbFs / 20.0);
            double mTot = Math.hypot(m1, m2);
            if (mTot > 0.0) {
                f1Lvl = r.fundamentalTrueDbFs + 20.0 * Math.log10(m1 / mTot);
                f2Lvl = r.fundamentalTrueDbFs + 20.0 * Math.log10(m2 / mTot);
            }
        }
        out.f1DbV  = f1Lvl + dbvOffsetDb;
        out.f2DbV  = f2Lvl + dbvOffsetDb;
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
        double dfd2Mag     = readBinVrms(amplitudeDbFs, binBw, out.f2Hz - out.f1Hz, dbvOffsetDb);
        double dfd3LowMag  = readBinVrms(amplitudeDbFs, binBw, 2.0 * out.f1Hz - out.f2Hz, dbvOffsetDb);
        double dfd3HighMag = readBinVrms(amplitudeDbFs, binBw, 2.0 * out.f2Hz - out.f1Hz, dbvOffsetDb);
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
            double magL = readBinVrms(amplitudeDbFs, binBw, fL, dbvOffsetDb);
            double magH = readBinVrms(amplitudeDbFs, binBw, fH, dbvOffsetDb);
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
        int nBins = amplitudeDbFs.length;
        double floorDbFs = noiseFloorDbFs(amplitudeDbFs);
        int binF1 = (int) Math.round(out.f1Hz / binBw);
        int binF2 = (int) Math.round(out.f2Hz / binBw);
        int lo1 = skirtEdge(amplitudeDbFs, binF1, -1, floorDbFs);
        int hi1 = skirtEdge(amplitudeDbFs, binF1, +1, floorDbFs);
        int lo2 = skirtEdge(amplitudeDbFs, binF2, -1, floorDbFs);
        int hi2 = skirtEdge(amplitudeDbFs, binF2, +1, floorDbFs);
        double sumAll      = 0.0;
        double sumResidual = 0.0;
        for (int b = 1; b < nBins; b++) {
            // FS-relative bin voltage — TD+N is a ratio, the dBV offset cancels.
            double vBin = Math.pow(10.0, amplitudeDbFs[b] / 20.0);
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

    /** Picks the highest bin within ±{@code searchBins} of
     *  {@code centreHz} and refines its position via the standard
     *  3-point quadratic peak-interpolation formula.  Works on the raw
     *  dBFS spectrum — argmax and the parabola use comparisons and
     *  differences only, so the dBV offset is irrelevant here.  Returns
     *  {@code null} when {@code centreHz} falls outside the
     *  representable bin range. */
    private Peak refinePeak(double[] amplitudeDbFs, double binBw,
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
        p.freqHz   = (kMax + delta) * binBw;
        // Interpolated peak value (Smith & Serra, eqn. 6).
        p.levelDbFs = yC - 0.25 * (yL - yR) * delta;
        return p;
    }

    /** Returns the V_rms voltage at the bin nearest {@code freqHz}:
     *  the dBFS bin lifted to dBV via {@code dbvOffsetDb}, then to volts.
     *  Out-of-range frequencies return 0. */
    private double readBinVrms(double[] amplitudeDbFs, double binBw, double freqHz,
                               double dbvOffsetDb) {
        if (!(freqHz > 0)) return 0.0;
        int n = amplitudeDbFs.length;
        int b = (int) Math.round(freqHz / binBw);
        if (b < 1 || b >= n) return 0.0;
        return Math.pow(10.0, (amplitudeDbFs[b] + dbvOffsetDb) / 20.0);
    }

    /** Scratch buffer for {@link #noiseFloorDbFs}'s quickselect, grown on
     *  demand and reused across calls — meaningful when the owner keeps one
     *  analyzer instance per view (FftView does), so the per-tick multi-MB
     *  copy allocation disappears after the first call. */
    private double[] floorScratch;

    /** Leakage-immune noise-floor estimate: the 10th-percentile bin
     *  level (dBFS) across the spectrum (DC excluded).  Mirrors the
     *  {@code globalMedianNoisePow} model FftAnalyzer uses to size its
     *  dynamic fundamental-exclusion walk — the 10th percentile sits
     *  below the bulk of spurs and leakage, close to the true
     *  quantization / noise floor.  dB is monotonic in power, so the
     *  percentile bin is identical whether taken on levels or powers. */
    private double noiseFloorDbFs(double[] amplitudeDbFs) {
        int n = amplitudeDbFs.length;
        if (n <= 1) return Double.NEGATIVE_INFINITY;
        int len = n - 1;
        if (floorScratch == null || floorScratch.length < len) {
            floorScratch = new double[len];
        }
        System.arraycopy(amplitudeDbFs, 1, floorScratch, 0, len);
        return selectKth(floorScratch, len, len / 10);
    }

    /** In-place quickselect: partitions {@code a[0..len)} until the
     *  {@code k}-th smallest element sits at index {@code k}, and returns it.
     *  Same result as {@code sort(a)[k]} at O(n) expected instead of
     *  O(n·log n) — the full sort dominated the IMD tick at large FFT
     *  lengths.  Hoare partition with a median-of-three pivot, so the
     *  near-sorted spectra a stable noise floor produces don't degrade it. */
    private double selectKth(double[] a, int len, int k) {
        int lo = 0;
        int hi = len - 1;
        while (lo < hi) {
            double pivot = medianOfThree(a[lo], a[(lo + hi) >>> 1], a[hi]);
            int i = lo;
            int j = hi;
            while (i <= j) {
                while (a[i] < pivot) i++;
                while (a[j] > pivot) j--;
                if (i <= j) {
                    double tmp = a[i]; a[i] = a[j]; a[j] = tmp;
                    i++; j--;
                }
            }
            if (k <= j)      hi = j;
            else if (k >= i) lo = i;
            else             return a[k];
        }
        return a[k];
    }

    /** Middle value of the three — pivot choice for {@link #selectKth}. */
    private double medianOfThree(double a, double b, double c) {
        if (a > b) { double t = a; a = b; b = t; }
        if (b > c) { b = c; }
        return Math.max(a, b);
    }

    /** Walks from {@code peakBin} in direction {@code dir} (+1 / −1)
     *  while bins stay above {@code floorDbFs}, returning the farthest
     *  bin still on the fundamental's skirt.  This is the same dynamic
     *  exclusion FftAnalyzer performs around its fundamental: follow the
     *  measured skirt (window main lobe + close-in phase noise) down to
     *  the noise floor, whatever its width and whichever window is in
     *  use — no fixed window that BH7's wide lobe could leak past. */
    private int skirtEdge(double[] amplitudeDbFs, int peakBin,
                          int dir, double floorDbFs) {
        int n = amplitudeDbFs.length;
        int b = peakBin;
        while (b + dir >= 1 && b + dir < n && amplitudeDbFs[b + dir] > floorDbFs) {
            b += dir;
        }
        return b;
    }

    private static final class Peak {
        double freqHz;
        double levelDbFs;
    }
}
