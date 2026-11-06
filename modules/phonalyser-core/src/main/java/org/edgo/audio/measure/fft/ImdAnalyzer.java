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

import org.edgo.audio.measure.dsp.FreqRespCalHelper;
import org.edgo.audio.measure.dsp.InverseCascade;

/**
 * Compiles a {@link ImdResult} from a dual-tone {@link FftResult} and the
 * lines the statistics created for it.
 * {@link org.edgo.audio.measure.common.SpectrumStatistics} owns one instance
 * and calls it whenever it calculates the statistics of a result that carries
 * a second tone; it holds no state - no threads, no widgets.  Every level in
 * and out is full-scale relative (dBFS); the dBV lift belongs to the reader.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li><b>Tone detection</b> - search a small bin window (±
 *       {@value #TONE_SEARCH_BINS}) around each tone's line, pick the bin
 *       with the highest dBFS, refine with quadratic peak
 *       interpolation.</li>
 *   <li><b>IMD product levels</b> - each product line's bin, for every
 *       order from 2 to {@link ImdResult#MAX_ORDER}: {@code f2 - f1} and
 *       {@code f1 + f2}, then {@code (n-1)f1 - (n-2)f2} (lower) and
 *       {@code (n-1)f2 - (n-2)f1} (upper).  DFD2 is the order-2 lower
 *       product, DFD3 the two order-3 ones.  Every product reading is
 *       clamped at the product's band-top from below.</li>
 *   <li><b>De-embedding</b> - every line is read on the spectrum as
 *       measured and de-embedded with the {@code .frc} correction's constant
 *       value for it ({@link FreqRespCalHelper#gainDbAt}), so the
 *       de-embedded level stands the same distance above the measured one
 *       whether the measured level is clamped at the line's floor or not; both
 *       levels travel in the line.</li>
 *   <li><b>Ratios</b> - on the de-embedded levels.  The IMD-product
 *       reference is {@code |F1| + |F2|} (linear-magnitude sum of the
 *       two fundamentals).  Each
 *       dnL / dnH product is expressed as a percent of that
 *       reference; IMDpwr is the RMS sum of all listed products,
 *       same denominator.  TD+N is computed differently: it's the
 *       residual RMS (everything that isn't F1, F2 or one of the phase
 *       alignment's two pilot lines) as a fraction
 *       of the total signal Vrms, derived via Parseval from the
 *       spectrum itself, every bin's power weighted by the correction's
 *       gain there - so it matches what
 *       {@code √(Vrms² − F1² − F2²) / Vrms} would yield if the
 *       analyser had access to the de-embedded time-domain signal.</li>
 * </ol>
 */
public final class ImdAnalyzer {

    /** Half-width of the bin window scanned around each given tone
     *  frequency.  Small enough that we never latch onto an IMD
     *  product as a fundamental, large enough to compensate for
     *  sample-rate / FFT-length rounding when snap is off. */
    private static final int TONE_SEARCH_BINS = 8;

    /** Builds an {@link ImdResult} from {@code r} and its lines.
     *
     * @param r            the spectrum, as measured
     * @param lines        its lines in the slot order of
     *                     {@link FreqRespCalHelper#toneFrequencies}, as the
     *                     statistics created them: the two tones' and every
     *                     product's geometry
     * @param binPowerGain the power gain the {@code .frc} correction gives
     *                     each bin {@code 0 .. fftSize/2} as it reshapes the
     *                     spectrum - 1 where it leaves a bin alone - which
     *                     weights the TD+N residual; {@code null} for a
     *                     spectrum that is not de-embedded
     * @param frc          the {@code .frc} correction whose constant value
     *                     for a line de-embeds the line; {@code null} for none
     * @return the readout; {@code null} when there isn't enough data to
     *         compute (the spectrum array is missing or the tone frequencies
     *         fall outside the analysed band) */
    public ImdResult analyze(FftResult r, SpectralLine[] lines, double[] binPowerGain,
                             InverseCascade frc) {
        if (r == null || r.amplitudeDbFs == null) return null;
        double binBw = r.freqResolution;
        if (!(binBw > 0)) return null;
        // All math runs on the raw dBFS spectrum - peak picking and every
        // figure of merit (dnL/dnH %, DFD2/3 %, IMD-power %, TD+N %) are
        // offset-invariant: a constant dB shift moves every bin equally and
        // cancels in each ratio.  The absolute outputs stay dBFS too, so a
        // reader lifts the dozen scalars it shows instead of a per-bin copy of
        // the spectrum.
        double[] amplitudeDbFs = r.amplitudeDbFs;

        // The lower tone is always F1 even if the tones arrive in the other
        // order, so the IMD sideband labels (dnL = below F1, dnH = above F2)
        // stay meaningful: the fundamental is slot 0, its partner the second
        // tone's slot, either of them the lower.
        int secondSlot = FreqRespCalHelper.secondToneSlot(r);
        boolean fundamentalIsF1 = lines[0].frequencyHz() <= lines[secondSlot].frequencyHz();
        int slotF1 = fundamentalIsF1 ? 0 : secondSlot;
        int slotF2 = fundamentalIsF1 ? secondSlot : 0;
        SpectralLine lineF1 = lines[slotF1];
        SpectralLine lineF2 = lines[slotF2];
        double fLow  = lineF1.frequencyHz();
        double fHigh = lineF2.frequencyHz();

        // --- Detect F1 / F2 (argmax + quadratic refinement; offset-invariant,
        // so it runs on the raw dBFS bins).
        Peak p1 = refinePeak(amplitudeDbFs, binBw, fLow,  TONE_SEARCH_BINS);
        Peak p2 = refinePeak(amplitudeDbFs, binBw, fHigh, TONE_SEARCH_BINS);
        if (p1 == null || p2 == null) return null;

        ImdResult out = new ImdResult();
        // Frequencies are the given ones - the analyzer's clean-frame sub-bin
        // estimates (the same honest method as the single-tone fundamental),
        // NOT the peak of the coherently-collapsed average, which reads the
        // bin centre and hides the real sub-bin offset.  Ordered: without a
        // hint the analyzer's fundamental is the STRONGER tone and its
        // partner may lie below it, while F1 here is always the lower one -
        // a reversed pair gives a negative difference frequency and swaps
        // every dnL / dnH.
        // Each tone's level as measured - which drives the dBFS column and the
        // markers - and de-embedded by the correction's value at its peak bin.
        double f1DeembeddedDbFs = p1.levelDbFs - FreqRespCalHelper.gainDbAt(frc, slotF1, p1.bin);
        double f2DeembeddedDbFs = p2.levelDbFs - FreqRespCalHelper.gainDbAt(frc, slotF2, p2.bin);
        out.f1 = lineF1.withLevels(p1.levelDbFs, f1DeembeddedDbFs);
        out.f2 = lineF2.withLevels(p2.levelDbFs, f2DeembeddedDbFs);
        // Manual fundamental override: the mandatory twin-T notch suppresses the
        // measured tones, so when a true level is supplied it is the TRUE
        // COMBINED level - split across the two tones by their de-embedded
        // ratio (equal tones at 1 V together -> each √(½) = −3.01 dBV).  Only
        // the absolute tone magnitudes are anchored; the spectrum dBFS is left
        // untouched, and the ratio = product/(|F1|+|F2|) stays honest.
        double f1Lvl = f1DeembeddedDbFs;
        double f2Lvl = f2DeembeddedDbFs;
        if (Double.isFinite(r.fundamentalTrueDbFs)) {
            double m1 = Math.pow(10.0, f1DeembeddedDbFs / 20.0);
            double m2 = Math.pow(10.0, f2DeembeddedDbFs / 20.0);
            double mTot = Math.hypot(m1, m2);
            if (mTot > 0.0) {
                f1Lvl = r.fundamentalTrueDbFs + 20.0 * Math.log10(m1 / mTot);
                f2Lvl = r.fundamentalTrueDbFs + 20.0 * Math.log10(m2 / mTot);
            }
        }
        // f1Mag / f2Mag are linear magnitudes relative to full scale.
        out.f1Mag  = Math.pow(10.0, f1Lvl / 20.0);
        out.f2Mag  = Math.pow(10.0, f2Lvl / 20.0);
        out.diffHz = fHigh - fLow;

        // Reference magnitude for ratios = |F1| + |F2|, full-scale relative.
        // Floored at a tiny positive so the % divide doesn't blow up
        // when both tones are muted.
        double refMag = Math.max(1e-12, out.f1Mag + out.f2Mag);

        // --- n-th-order IMD products (n = 2..5), each its line from the
        // second tone's slot on.  CCIF/DIN naming:
        //   d2L = f2 − f1            (2nd-order difference)
        //   d2H = f1 + f2            (2nd-order sum)
        //   dnL = (n−1)·f1 − (n−2)·f2  for n ≥ 3 (sideband below F1)
        //   dnH = (n−1)·f2 − (n−2)·f1  for n ≥ 3 (sideband above F2)
        // So d3L / d3H are the 3rd-order intermod products
        // 2f1 − f2 and 2f2 − f1; d4 is 5th-order; d5 is 7th-order.
        double imdPwrSq = 0.0;
        int slot = secondSlot + 1;
        for (int k = 2; k <= ImdResult.MAX_ORDER; k++) {
            out.dnL[k] = productLine(r, lines[slot], slot, frc);
            slot++;
            out.dnH[k] = productLine(r, lines[slot], slot, frc);
            slot++;
            // A product outside the measurable range is NaN, not a level;
            // the power sum counts only measurable products.
            double magL = deembeddedLinear(out.dnL[k]);
            double magH = deembeddedLinear(out.dnH[k]);
            out.dnLPct[k] = 100.0 * magL / refMag;
            out.dnHPct[k] = 100.0 * magH / refMag;
            if (Double.isFinite(magL)) imdPwrSq += magL * magL;
            if (Double.isFinite(magH)) imdPwrSq += magH * magH;
        }

        // --- DFD2 (= f2 − f1, the order-2 difference) and DFD3 (= 2f1 − f2 /
        // 2f2 − f1, the two order-3 products).
        double dfd2Mag     = deembeddedLinear(out.dnL[2]);
        double dfd3LowMag  = deembeddedLinear(out.dnL[3]);
        double dfd3HighMag = deembeddedLinear(out.dnH[3]);
        // DFD3 is the RMS of its two sidebands; a sideband outside the
        // measurable range (NaN) is skipped so the other still reports -
        // SMPTE-style tone pairs routinely put 2f1 − f2 below DC.  Both
        // absent -> NaN (readout shows "---").
        double dfd3Sq = 0.0;
        int    dfd3N  = 0;
        if (Double.isFinite(dfd3LowMag))  { dfd3Sq += dfd3LowMag  * dfd3LowMag;  dfd3N++; }
        if (Double.isFinite(dfd3HighMag)) { dfd3Sq += dfd3HighMag * dfd3HighMag; dfd3N++; }
        double dfd3Mag = dfd3N > 0 ? Math.sqrt(dfd3Sq) : Double.NaN;
        out.dfd2Pct = 100.0 * dfd2Mag / refMag;
        out.dfd3Pct = 100.0 * dfd3Mag / refMag;
        // Include DFD2 / DFD3 components in the combined IMD power (finite
        // sidebands only; dfd3Sq already holds just the measurable ones).
        if (Double.isFinite(dfd2Mag)) imdPwrSq += dfd2Mag * dfd2Mag;
        imdPwrSq += dfd3Sq;
        out.imdPwrPct = 100.0 * Math.sqrt(imdPwrSq) / refMag;

        // --- TD+N as the scalar drop from total RMS to the
        // fundamentals - (Vrms − √(F1² + F2²)) / Vrms - computed
        // straight from the spectrum and WINDOW-INDEPENDENT.  Σ squared
        // bin voltages over the whole spectrum gives Vrms²; Σ over the
        // fundamental-skirt bins gives F1² + F2².  Both carry the
        // window's equivalent noise bandwidth, which cancels in the
        // ratio - correct for Hann, Blackman-Harris, ... without knowing
        // which window the analyser used.
        //
        // F1 / F2 are stripped over their lines' skirts - the SAME skirt walk
        // the fundamental's line takes, measured on the noise spectrum when
        // the lines were created.  A fixed ±{@code TONE_SEARCH_BINS} window
        // can't track BH7's wide main lobe nor the tones' close-in phase
        // noise; under heavy coherent averaging that leftover skirt sits tens
        // of dB above the averaged-down floor and used to dominate the
        // residual, inflating TD+N.  The walk follows the measured skirt to
        // the floor whatever its width while stopping well short of the IMD
        // products (kHz away), which stay in the residual as they should.
        //
        // Every bin's power comes off the power average where the result
        // carries one: the coherent mean lets the noise and every part of a
        // tone not coherent with it fall with the frames held, its power does
        // not.  Each bin enters the sums with the correction's power gain
        // there, which is what summing the de-embedded spectrum gives.
        double[] powerDbFs = r.hasPowerAverage ? r.powerAverageDbFs : amplitudeDbFs;
        int nBins = powerDbFs.length;
        int lo1 = lineF1.bin() - lineF1.skirtHalfWidthBins();
        int hi1 = lineF1.bin() + lineF1.skirtHalfWidthBins();
        int lo2 = lineF2.bin() - lineF2.skirtHalfWidthBins();
        int hi2 = lineF2.bin() + lineF2.skirtHalfWidthBins();
        // The two pilot lines of the fundamental phase alignment are generated
        // as a time base, not measured: neither is one of the tones nor a
        // distortion product, and at their level they would lay a floor of
        // their own under this residual.  The analyser stamped the bins each
        // one covers - its bin plus the window's main lobe and the leakage
        // guard - and both spans
        // are left out of BOTH sums here, so the reading is the one the same
        // signal without pilots gives.  A span is empty on a result whose
        // matching pilot slot carries none.
        int pilot1Lo = r.pilot1MaskLoBin;
        int pilot1Hi = r.pilot1MaskHiBin;
        int pilot2Lo = r.pilot2MaskLoBin;
        int pilot2Hi = r.pilot2MaskHiBin;
        double sumAll      = 0.0;
        double sumResidual = 0.0;
        for (int b = 1; b < nBins; b++) {
            if (b >= pilot1Lo && b <= pilot1Hi) continue;
            if (b >= pilot2Lo && b <= pilot2Hi) continue;
            // FS-relative bin voltage - TD+N is a ratio.
            double vBin = Math.pow(10.0, powerDbFs[b] / 20.0);
            double sq = vBin * vBin * powerGain(binPowerGain, b);
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
     *  {@code centreHz} and refines its level via the standard
     *  3-point quadratic peak-interpolation formula.  Works on the raw
     *  dBFS spectrum - argmax and the parabola use comparisons and
     *  differences only.  Returns {@code null} when {@code centreHz}
     *  falls outside the representable bin range. */
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
        // Interpolated peak value (Smith & Serra, eqn. 6).
        p.levelDbFs = yC - 0.25 * (yL - yR) * delta;
        p.bin       = kMax;
        return p;
    }

    /** One product line measured: its level at its bin, clamped at its
     *  band-top from below ({@link SpectralLine#clampedAtBandTop}), and that
     *  level de-embedded by the correction's constant value for the line, so a
     *  clamp moves both levels by the same amount.  A product outside the
     *  representable bin range (at or below DC, or beyond the spectrum) gets
     *  {@code NaN} levels - it is not measurable at this sample rate.
     *
     * @param r    the spectrum, as measured
     * @param line the product's line, its geometry measured
     * @param slot the line's slot of {@link FreqRespCalHelper#toneFrequencies}
     * @param frc  the {@code .frc} correction; {@code null} for none
     * @return the product's line with its levels */
    private SpectralLine productLine(FftResult r, SpectralLine line, int slot, InverseCascade frc) {
        if (!line.measurable()) return line.withLevels(Double.NaN, Double.NaN);
        int b = line.bin();
        double rawLinear = line.clampedAtBandTop(Math.pow(10.0, r.amplitudeDbFs[b] / 20.0));
        double rawDbFs   = 20.0 * Math.log10(rawLinear);
        return line.withLevels(rawDbFs, rawDbFs - FreqRespCalHelper.gainDbAt(frc, slot, b));
    }

    /** A line's de-embedded level as a linear magnitude (full scale = 1).
     *
     * @param line the line
     * @return the magnitude; {@code NaN} for a line outside the measurable
     *         range */
    private double deembeddedLinear(SpectralLine line) {
        return Math.pow(10.0, line.deembeddedAmplitudeDbFs() / 20.0);
    }

    /** The correction's power gain at {@code bin}.
     *
     * @param binPowerGain the gains by bin; {@code null} for none
     * @param bin          the bin
     * @return the gain; 1 without a correction, outside the array and where
     *         the correction leaves the bin alone */
    private double powerGain(double[] binPowerGain, int bin) {
        if (binPowerGain == null || bin < 0 || bin >= binPowerGain.length) return 1.0;
        double g = binPowerGain[bin];
        return g > 0.0 ? g : 1.0;
    }

    /** A tone's peak, as {@link #refinePeak} finds it. */
    private static final class Peak {
        double levelDbFs;
        /** The bin the level was read around - where it is de-embedded. */
        int    bin;
    }
}
