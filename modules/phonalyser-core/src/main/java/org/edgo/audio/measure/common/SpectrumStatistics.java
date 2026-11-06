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

package org.edgo.audio.measure.common;

import java.util.Arrays;
import java.util.function.IntToDoubleFunction;

import org.edgo.audio.measure.dsp.FftDsp;
import org.edgo.audio.measure.dsp.FreqRespCalHelper;
import org.edgo.audio.measure.dsp.InverseCascade;
import org.edgo.audio.measure.fft.BinRanges;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.fft.ImdAnalyzer;
import org.edgo.audio.measure.fft.ReadoutSettings;
import org.edgo.audio.measure.fft.SpectralLine;

/**
 * The statistics of one spectrum, calculated in one step: the fundamental,
 * every harmonic and - for a dual tone - every intermodulation product, each
 * measured and de-embedded; THD, SNR, SINAD / THD+N, the A-weighted N+D,
 * ENOB, the noise floor and the fundamental's skirt.  The spectrum is
 * whatever is to be rendered - one raw frame, an average, a spectrum read
 * back from a file - and the statistics go back into it.
 *
 * <p>Every line is created first with its geometry - its bin, the lobe it
 * fills and the floor under it, measured on the spectrum as it arrives - and
 * measured after.  Its level is de-embedded with the {@code .frc}
 * correction's constant value for it ({@link FreqRespCalHelper#gainDbAt}):
 * the with-noise rows' gain at its bin and its own tone's full correction -
 * so a line's de-embedded level stands the same distance above its measured
 * level in every frame, whether the measured level is clamped at the line's
 * floor or not.  The noise sums weight every bin's power with the gain the
 * correction gives that bin as it reshapes the spectrum, which is the sum
 * over the de-embedded spectrum without a second pass.  Every ratio stands
 * on the de-embedded levels.
 *
 * <p>Last, the trace itself is lifted: the {@code .frc} correction, then the
 * lobe of every line whose level the trace must meet - the manual
 * fundamental, a clamped distortion line - with the one lobe lift of
 * {@link FreqRespCalHelper#liftLobe}.  The result leaves ready to draw and to
 * save.
 *
 * <p>Holds the A-weighting table, reusable calculation buffers and what the
 * last result's trace was lifted with, so a recalculation of that result
 * takes exactly that off first.  An instance is used by one thread at a time;
 * each owner keeps its own, so no call is shared and none needs a lock.
 */
public class SpectrumStatistics {

    /** IEC 61672 A-weighting pole frequencies (Hz) of the analog magnitude
     *  response R_A(f), and its 1 kHz normalization:
     *  A(f) = 20·log₁₀ R_A(f) + {@link #AW_NORM_1KHZ_DB}, so A(1 kHz) = 0 dB. */
    private static final double AW_POLE1_HZ     = 20.6;
    private static final double AW_POLE2_HZ     = 107.7;
    private static final double AW_POLE3_HZ     = 737.9;
    private static final double AW_POLE4_HZ     = 12194.0;
    private static final double AW_NORM_1KHZ_DB = 2.00;
    /** {@link #AW_NORM_1KHZ_DB} as a power ratio, folded into aWeightPower. */
    private static final double AW_NORM_POWER   = Math.pow(10.0, AW_NORM_1KHZ_DB / 10.0);

    /** A bin at or below this level (dBFS) holds nothing: its linear
     *  amplitude counts as 0. */
    private static final double EMPTY_BIN_DBFS = -290.0;
    /** The smallest linear amplitude still read as a level. */
    private static final double MIN_LINEAR_AMPLITUDE = 1e-15;
    /** The level (dBFS) a line with no amplitude reads, and the THD (dB) of
     *  no harmonic power. */
    private static final double NO_LEVEL_DB = -300.0;
    /** The ratio (dB) a figure reads whose denominator vanished. */
    private static final double NO_RATIO_DB = 300.0;
    /** The offset of a fundamental's nearest mains intermodulation product,
     *  in Hz: the flanks its floor is read on and its skirt walked within
     *  stop short of that product's own lobe and guard, so the product
     *  neither raises the floor nor passes for skirt. */
    private static final double TONE_FLOOR_LIMIT_HZ = 50.0;

    /** The intermodulation estimator every dual-tone result's {@code imd} is
     *  derived with, from the lines this instance measured. */
    private final ImdAnalyzer imdAnalyzer = new ImdAnalyzer();

    /** Per-bin A-weighting table - entry k = {@link #aWeightPower}(k·freqRes)
     *  for {@link #aWeightFreqRes}.  The noise integrals consult it for every
     *  non-signal bin; without it that is ~halfSize sqrt evaluations per call
     *  for a curve that only changes when the spectrum geometry (fftSize /
     *  sample rate) does. */
    private double[] aWeight;
    private double   aWeightFreqRes;

    /** Reusable calculation buffers, every cell overwritten by the call that
     *  uses it - 8 MB each at fftSize 2 M, which a displayed frame would
     *  otherwise allocate anew: the linear amplitude per bin, the power
     *  average's linear amplitude per bin, the correction's power gain per
     *  bin, the signal-bin mask, and the noise-bin pool of the floor
     *  selection and of a fundamental's flanks. */
    private double[]  amplitudeBuffer;
    private double[]  powerBuffer;
    private double[]  gainBuffer;
    private boolean[] signalMaskBuffer;
    private double[]  noiseBandBuffer;
    /** Reused mask of the bins {@link #globalNoiseFloor} leaves out: every
     *  line over its skirt and both pilot spans. */
    private boolean[] lineMaskBuffer;

    /** What the last {@link #calculateStat} lifted its result's trace with,
     *  kept so {@link #recalculateStat} takes exactly that off again: the
     *  {@code .frc} correction ({@code null} for none), the lines the lifts
     *  read, by slot of {@link FreqRespCalHelper#toneFrequencies}, and each
     *  line's lift after the correction (0 for none).  {@code null} lines
     *  while nothing is kept. */
    private InverseCascade liftedWith;
    private SpectralLine[] liftedLines;
    private double[]       lineLifts;

    /**
     * Calculates every statistic of {@code r} and writes it back: the
     * fundamental, the harmonic lines and their percentages, THD, THD+N,
     * SNR, SINAD, the A-weighted N+D, ENOB, the noise power and floor, the
     * fundamental's skirt width and, for a dual tone, the intermodulation
     * readout.
     *
     * <p>The inputs are the spectrum ({@code amplitudeDbFs}, the power
     * average where the result carries one, the harmonic ladder placed with
     * {@code settings}' harmonic count), the readout settings - kept in the
     * result with the statistics they produced - and {@code frc}, the
     * {@code .frc} correction the result's spectrum is to be de-embedded
     * with, {@code null} for none.  Last, the trace is lifted
     * ({@link #liftTrace}), so the result leaves ready to draw and to save;
     * what it was lifted with replaces whatever was kept from the result
     * before.
     *
     * @param r        the spectrum as it arrives
     * @param settings the readout settings
     * @param frc      the correction; {@code null} for none
     */
    public void calculateStat(FftResult r, ReadoutSettings settings, InverseCascade frc) {
        r.thdHarmonics        = settings.thdHarmonics();
        r.snrFreqMin          = settings.bandMinHz();
        r.snrFreqMax          = settings.bandMaxHz();
        r.fundamentalTrueDbFs = settings.manualFundamentalDbFs();

        int    halfSize = r.fftSize / 2;
        double freqRes  = r.freqResolution;
        double snrLo    = r.snrFreqMin > 0.0 ? r.snrFreqMin : 0.0;
        double snrHi    = r.snrFreqMax > 0.0 ? r.snrFreqMax : Double.MAX_VALUE;

        // Linear amplitudes from amplitudeDbFs, the source of truth for the
        // level (re / im are unscaled FFT outputs - the analyser-side
        // normFactor and single-sided x2 are folded into amplitudeDbFs, not
        // into re / im).  Every cell is overwritten, so no zero-fill.
        final double[] amplLinear = reuse(amplitudeBuffer, halfSize + 1);
        amplitudeBuffer = amplLinear;
        BinRanges.parallel(halfSize + 1, (lo, hi) -> {
            for (int k = lo; k < hi; k++) {
                amplLinear[k] = r.amplitudeDbFs[k] > EMPTY_BIN_DBFS
                        ? Math.pow(10.0, r.amplitudeDbFs[k] / 20.0)
                        : 0.0;
            }
        });

        // --- Signal-bin mask: fundamental + every harmonic over the window's
        //     main lobe plus the leakage guard, and the pilots' spans ---
        int exclBins = FftDsp.mainLobeBins(r.windowType) + FftDsp.LEAKAGE_GUARD_BINS;
        boolean[] isSignalBin = buildSignalBinMask(r, halfSize, exclBins);

        // --- Every line's geometry, on the spectrum as it arrives ---------
        SpectralLine[] lines = measureLines(r, amplLinear, exclBins);

        // The correction's power gain per bin, read on the spectrum before
        // the correction on the lines' lobes, the way the correction itself
        // reads it - for the noise sums only.  Inside a lobe it is the
        // stretched gain, which falls to 1 for a bin at the lobe's floor, so
        // no line reads its level off it.
        double[] gains = null;
        if (frc != null) {
            gains = reuse(gainBuffer, halfSize + 1);
            gainBuffer = gains;
            FreqRespCalHelper.binPowerGains(r, lines, frc, gains);
        }

        // --- Fundamental ----------------------------------------------------
        // The measured scalars stay as the spectrum carries them; the line
        // carries the level de-embedded too, by the correction's constant
        // value at the fundamental's bin.  The manual fundamental, when
        // set, is the ratio denominator (THD / SNR / THD+N / H%): an external
        // twin-T notch suppresses only H1 by a drift-prone amount, so the
        // measured fundamental is unreliable there while H2..Hn stay valid as
        // measured.  It is already in dBFS, so refLin reads straight off it.
        int    fundBin    = r.fundamentalBin;
        double fundLinear = amplLinear[fundBin];
        double fundDbFs   = r.amplitudeDbFs[fundBin];
        r.fundamentalLinear = fundLinear;
        r.fundamentalDbFs   = fundDbFs;
        double fundGainDb = FreqRespCalHelper.gainDbAt(frc, 0, fundBin);
        r.fundamental = lines[0].withLevels(fundDbFs, fundDbFs - fundGainDb);
        double refLin = 0;
        if (Double.isNaN(r.fundamentalTrueDbFs)) {
            double fundDeembeddedLinear = fundLinear * amplitudeFactor(fundGainDb);
            refLin = fundDeembeddedLinear;
        } else {
            refLin = Math.pow(10.0, r.fundamentalTrueDbFs / 20.0);
        }

        // --- Harmonic lines -------------------------------------------------
        // The Nth harmonic sits at the fractional bin harmonicHz / freqRes,
        // which is N times the fundamental's by construction.  When it falls
        // between two bins its energy splits across both through the window's
        // main lobe, so the two straddling bins are power-weighted by the
        // sub-bin offset: (1 - w)·|X[main]|^2 + w·|X[adj]|^2, w = |offset|.
        // This replaced a local-max search within a few bins, which snapped to
        // noise peaks when a harmonic was buried in the noise.  The measured
        // level is clamped at the harmonic's band-top from below
        // (SpectralLine#clampedAtBandTop): every reading below derives from
        // it, THD among them.  The de-embedded level is that clamped level plus
        // the correction's constant value for the line, so a clamp moves both
        // levels by the same amount.
        int harmonicCount = r.harmonicCount;
        SpectralLine[] harmonics = new SpectralLine[harmonicCount];
        double[] hLinear = new double[harmonicCount];   // de-embedded, linear
        for (int h = 0; h < harmonicCount; h++) {
            SpectralLine line = lines[1 + h];
            double hz = r.harmonicHz[h];
            double kH = freqRes > 0 ? hz / freqRes : 0.0;
            int binMain = (int) Math.round(kH);
            if (r.harmonicBins[h] <= 0 || binMain < 1 || binMain > halfSize) {
                r.harmonicBins[h] = -1;
                r.harmonicPct[h]  = 0.0;
                harmonics[h]      = line.withLevels(NO_LEVEL_DB, NO_LEVEL_DB);
                continue;
            }
            double offset = kH - binMain;
            int binAdj = (offset >= 0.0)
                    ? Math.min(halfSize, binMain + 1)
                    : Math.max(1, binMain - 1);
            double w = Math.abs(offset);
            double pMain = amplLinear[binMain] * amplLinear[binMain];
            double pAdj  = amplLinear[binAdj]  * amplLinear[binAdj];
            double rawLinear        = line.clampedAtBandTop(Math.sqrt((1.0 - w) * pMain + w * pAdj));
            double deembeddedLinear = rawLinear
                    * amplitudeFactor(FreqRespCalHelper.gainDbAt(frc, 1 + h, binMain));
            r.harmonicBins[h] = binMain;
            r.harmonicPct[h]  = refLin > 0 ? deembeddedLinear / refLin * 100.0 : 0.0;
            harmonics[h]      = line.withLevels(levelDbFs(rawLinear), levelDbFs(deembeddedLinear));
            hLinear[h]        = deembeddedLinear;
        }
        r.harmonics = harmonics;

        // --- THD (H2..H(thdHarmonics+1) within the distortion band) ---------
        // awHarmPowerSum carries the same powers through the IEC A-weighting
        // curve for the A-weighted N+D readout.
        int thdHarmonics = r.thdHarmonics;
        double harmPowerSum   = 0.0;
        double awHarmPowerSum = 0.0;
        for (int h = 0; h < Math.min(harmonicCount, thdHarmonics); h++) {
            if (r.harmonicBins[h] > 0) {
                double freq = r.harmonicHz[h];
                if (freq >= snrLo && freq <= snrHi) {
                    double a = hLinear[h];
                    harmPowerSum   += a * a;
                    awHarmPowerSum += a * a * aWeightPower(freq);
                }
            }
        }
        r.thdPct = refLin > 0 ? Math.sqrt(harmPowerSum) / refLin * 100.0 : 0.0;
        r.thdDb  = r.thdPct > 0 ? 20.0 * Math.log10(r.thdPct / 100.0) : NO_LEVEL_DB;

        // --- The de-embedded spectrum the noise is read from ----------------
        // The lines are read; from here on the amplitudes stand de-embedded,
        // each bin times the correction's gain there.  The power average
        // where the result carries one - the coherent mean lets noise, and
        // every part of a tone not coherent with it, fall with the frames
        // held, its power does not - else the spectrum itself, which then is
        // the power reading.  The mask below - the fundamental's skirt and the
        // phase-noise zone - is found on the displayed spectrum, against the
        // floor read on that same spectrum.
        if (gains != null) {
            final double[] g = gains;
            BinRanges.parallel(halfSize + 1, (lo, hi) -> {
                for (int k = lo; k < hi; k++) amplLinear[k] *= Math.sqrt(g[k]);
            });
        }
        final double[] noiseLinear;
        if (r.hasPowerAverage) {
            noiseLinear = reuse(powerBuffer, halfSize + 1);
            powerBuffer = noiseLinear;
            final double[] powDbFs = r.powerAverageDbFs;
            final double[] g = gains;
            BinRanges.parallel(halfSize + 1, (lo, hi) -> {
                for (int k = lo; k < hi; k++) {
                    double v = powDbFs[k] > EMPTY_BIN_DBFS ? Math.pow(10.0, powDbFs[k] / 20.0) : 0.0;
                    noiseLinear[k] = g != null ? v * Math.sqrt(g[k]) : v;
                }
            });
        } else {
            noiseLinear = amplLinear;
        }

        // --- Noise floor, the fundamental's skirt and the phase-noise zone ---
        int skirtBins = lines[0].skirtHalfWidthBins();
        r.fundamentalDynExclusionHz = skirtBins * freqRes;
        // The floor the display is ranged on is the drawn spectrum's: over the
        // same noise bins, read off the displayed levels.
        double floorLin = globalNoiseFloor(r, amplLinear, lines, halfSize);
        double displayMedianPow = floorLin * floorLin;
        extendSignalMask(amplLinear, displayMedianPow, isSignalBin, halfSize, fundBin, exclBins, skirtBins);

        // --- SNR - integrated noise over the distortion band -----------------
        // Signal RMS^2 over the total unweighted noise power, integrated over
        // every non-signal bin in the band - bandwidth-dependent.  Harmonics
        // and pilots are excluded via isSignalBin; the dynamic walk keeps the
        // fundamental's window leakage out of the noise sum.  Signal^2 is
        // refLin^2 so an external twin-T notch on H1 cannot deflate the
        // numerator.
        double[] awTable    = aWeightTable(halfSize, freqRes);
        double noisePower   = 0.0;
        double awNoisePower = 0.0;   // same sum through the A-weighting curve
        int bandLo      = Math.max(1, (int) Math.ceil(snrLo / freqRes));
        int bandHi      = (int) Math.min(halfSize, Math.floor(snrHi / freqRes));
        int bandBins    = Math.max(0, bandHi - bandLo + 1);
        int countedBins = 0;
        for (int k = bandLo; k <= bandHi; k++) {
            if (!isSignalBin[k]) {
                double p = noiseLinear[k] * noiseLinear[k];
                noisePower   += p;
                awNoisePower += p * awTable[k];
                countedBins++;
            }
        }
        // The excluded zones (fundamental skirt, harmonics) held noise too -
        // estimate it as the surrounding floor by rescaling the counted sum to
        // the full band width, instead of silently dropping those slots (which
        // flattered SNR by the excluded fraction; the same effect, with far
        // wider zones, measured 0.8 dB in the vendor QA40x app).  Exact for a
        // flat floor, and a better estimate than zero for a structured one.
        if (countedBins > 0) {
            double rescale = (double) bandBins / countedBins;
            noisePower   *= rescale;
            awNoisePower *= rescale;
        }
        // A windowed periodogram bin holds NENBW bin-widths of broadband power
        // (Hann: 1.5), so summing noise bins as-is overstates the band's noise
        // power by exactly that factor - divide it back out.  Coherent lines
        // (fundamental, harmonics) need NO such correction: a sine's amplitude
        // is already window-normalized via the coherent gain, so refLin^2 and
        // harmPowerSum stay untouched.  Without this, SNR / SINAD / ENOB /
        // THD+N read 10·log10(NENBW) pessimistic (1.76 dB for Hann) - found
        // against a 23-bit-TPDF-dither hardware simulator where QA40x's
        // software matched the constructed theory value and the readout sat
        // exactly one ENBW low.  The factor is the RESULT's, stamped at
        // analysis time: the statistics must match the spectrum they read,
        // whatever window is selected by now.
        if (r.windowNenbwBins > 0) {
            noisePower   /= r.windowNenbwBins;
            awNoisePower /= r.windowNenbwBins;
        }
        r.noisePower   = noisePower;
        r.awNoisePower = awNoisePower;
        r.snrDb        = noisePower <= 0
                ? NO_RATIO_DB
                : 10.0 * Math.log10((refLin * refLin) / noisePower);
        // SINAD: signal RMS^2 / (noise + distortion) - the integrated band
        // noise plus the in-band harmonic power, UNWEIGHTED (the MT-003
        // definition; THD+N is its reciprocal and ENOB derives from it - none
        // of them carries a weighting curve).
        double sinadDenom = noisePower + harmPowerSum;
        r.sinadDb = sinadDenom > 0
                ? 10.0 * Math.log10((refLin * refLin) / sinadDenom)
                : NO_RATIO_DB;
        // A-weighted noise+distortion for the N+D readout ONLY - the one
        // audibility figure (IEC 61672 supplies the curve; the weighting is
        // this readout's own choice, worth ~5.3 dB on an HF-rising Cosmos
        // floor).  Negative dB relative to the signal.
        double awDenom = awNoisePower + awHarmPowerSum;
        r.awThdNDb = awDenom > 0 && refLin > 0
                ? 10.0 * Math.log10(awDenom / (refLin * refLin))
                : NO_RATIO_DB;
        // ENOB basis: the SAME unweighted in-band denominator referred to
        // FULL SCALE - where the signal level cancels exactly, because the
        // denominator is already full-scale-relative power.  No fundamental
        // term appears, so neither the notched bin level nor the
        // manual-fundamental reference can skew the figure under any
        // de-embedding combination.  Not the full Nyquist band: an
        // oversampled sigma-delta parks its shaped noise above the audio
        // band by design, and a full-band integral reads that as bits lost.
        r.enobSinadDb = sinadDenom > 0
                ? -10.0 * Math.log10(sinadDenom)
                : NO_RATIO_DB;
        r.avgNoiseFloorDbFs = displayMedianPow > 0
                ? 20.0 * Math.log10(Math.sqrt(displayMedianPow))
                : fundDbFs + NO_LEVEL_DB;
        // THD+N is the reciprocal of SINAD: (noise + distortion) / signal,
        // expressed in dB -> -sinadDb.  Same band, same numerator definition,
        // unweighted like SINAD.
        r.thdNDb = -r.sinadDb;

        // The intermodulation readout of the tone pair, on the same spectrum,
        // the same lines and the same correction; a NEW ImdResult each call,
        // so a reader may compare the object to tell a fresh readout from the
        // one it shows.
        r.imd = r.fundamental2HzRefined > 0.0
                ? imdAnalyzer.analyze(r, lines, gains, frc)
                : null;

        liftTrace(r, frc);
    }

    /**
     * Calculates the statistics again of the result the last
     * {@link #calculateStat} delivered - a stopped capture whose {@code .frc}
     * rows or readout settings changed, a loaded file whose readout settings
     * changed: the lifts that call put on the trace come off first
     * ({@link #takeLiftsOff}), then {@link #calculateStat} runs on the
     * spectrum as it arrived.
     *
     * @param r        the result the last {@link #calculateStat} delivered
     * @param settings the readout settings
     * @param frc      the correction; {@code null} for none
     */
    public void recalculateStat(FftResult r, ReadoutSettings settings, InverseCascade frc) {
        takeLiftsOff(r);
        calculateStat(r, settings, frc);
    }

    /** Forgets what the last result's trace was lifted with: that result is
     *  no longer shown, so nothing is to be taken off it. */
    public void reset() {
        liftedWith  = null;
        liftedLines = null;
        lineLifts   = null;
    }

    /**
     * Lifts the trace of {@code r}: the {@code .frc} correction first
     * ({@link FreqRespCalHelper#applyInverseInPlace}), then the lobe of each
     * line whose level the trace must meet ({@link #lineLift}) with the one
     * lobe lift ({@link FreqRespCalHelper#liftLobe}), on the floor the line
     * stands on.  What it lifted with is kept for {@link #recalculateStat}.
     *
     * @param r   the result, its statistics calculated
     * @param frc the correction; {@code null} for none
     */
    private void liftTrace(FftResult r, InverseCascade frc) {
        SpectralLine[] lines = r.toneLines();
        if (frc != null) FreqRespCalHelper.applyInverseInPlace(r, lines, frc);
        double[] lifts = new double[lines.length];
        for (int t = 0; t < lines.length; t++) {
            lifts[t] = lineLift(r, lines, t);
            if (lifts[t] > 0.0) FreqRespCalHelper.liftLobe(r, lines[t], lifts[t], 0.0, frc);
        }
        liftedWith  = frc;
        liftedLines = lines;
        lineLifts   = lifts;
    }

    /**
     * The lift of the line at slot {@code t} after the correction: the level
     * its lobe peak must reach over the level its bin holds now.  With a
     * manual fundamental, a single tone's lobe goes to the manual level and a
     * dual tone's two lobes each to its share of it, as the intermodulation
     * readout splits it ({@link FftResult#imd}).  A distortion line whose
     * reading the band-top clamp set ({@link SpectralLine#clamped}) goes to its
     * de-embedded level where that stands above its bin.
     *
     * @param r     the result, its statistics calculated and the correction
     *              applied
     * @param lines its lines by slot
     * @param t     the slot
     * @return the lift factor; 0 for none
     */
    private double lineLift(FftResult r, SpectralLine[] lines, int t) {
        SpectralLine line = lines[t];
        if (line == null || !line.measurable()) return 0.0;
        double binDbFs = r.amplitudeDbFs[line.bin()];
        int secondSlot = FreqRespCalHelper.secondToneSlot(r);
        double targetDbFs;
        if (t == 0 || t == secondSlot) {
            if (!Double.isFinite(r.fundamentalTrueDbFs)) return 0.0;
            targetDbFs = toneTargetDbFs(r, lines, t);
        } else {
            targetDbFs = line.deembeddedAmplitudeDbFs();
            if (!line.clamped() || !(targetDbFs > binDbFs)) return 0.0;
        }
        if (!Double.isFinite(targetDbFs) || !Double.isFinite(binDbFs)) return 0.0;
        return Math.pow(10.0, (targetDbFs - binDbFs) / 20.0);
    }

    /** The level a fundamental's lobe is lifted to under a manual
     *  fundamental: the manual level for a single tone; for a dual tone the
     *  share of it the intermodulation readout gives the tone at slot
     *  {@code t} - F1 the lower tone, F2 the upper.
     *
     * @param r     the result, its statistics calculated
     * @param lines its lines by slot
     * @param t     the fundamental's slot or the second tone's
     * @return the level, dBFS */
    private double toneTargetDbFs(FftResult r, SpectralLine[] lines, int t) {
        if (r.imd == null) return r.fundamentalTrueDbFs;
        int secondSlot = FreqRespCalHelper.secondToneSlot(r);
        boolean fundamentalIsF1 = lines[0].frequencyHz() <= lines[secondSlot].frequencyHz();
        boolean isF1 = (t == 0) == fundamentalIsF1;
        return levelDbFs(isF1 ? r.imd.f1Mag : r.imd.f2Mag);
    }

    /**
     * Takes the lifts {@link #liftTrace} put on {@code r}'s trace off again,
     * in reverse order - each line's lift with its reciprocal, on the floor it
     * stood on, then the correction
     * ({@link FreqRespCalHelper#removeInverseInPlace}) - which gives back the
     * spectrum as it arrived.  Nothing while nothing is kept; what was kept is
     * forgotten.
     *
     * @param r the result the last {@link #calculateStat} delivered
     */
    private void takeLiftsOff(FftResult r) {
        if (liftedLines == null) return;
        for (int t = liftedLines.length - 1; t >= 0; t--) {
            if (lineLifts[t] > 0.0) {
                FreqRespCalHelper.liftLobe(r, liftedLines[t], 1.0 / lineLifts[t], 0.0, liftedWith);
            }
        }
        if (liftedWith != null) FreqRespCalHelper.removeInverseInPlace(r, liftedLines, liftedWith);
        reset();
    }

    /** {@code current} if it has the requested length, else a fresh array;
     *  the caller overwrites every cell. */
    private double[] reuse(double[] current, int size) {
        return current != null && current.length == size ? current : new double[size];
    }

    /** The level of a linear amplitude (full scale 1) in dBFS. */
    private double levelDbFs(double linear) {
        return linear > MIN_LINEAR_AMPLITUDE ? 20.0 * Math.log10(linear) : NO_LEVEL_DB;
    }

    /** The factor that de-embeds a line's linear amplitude at a bin the
     *  correction takes {@code gainDb} off ({@link FreqRespCalHelper#gainDbAt});
     *  1 for 0 dB, which is also what no correction gives.
     *
     * @param gainDb the correction's gain at the line's bin, dB
     * @return the factor the line's linear amplitude is multiplied by */
    private double amplitudeFactor(double gainDb) {
        return Math.pow(10.0, -gainDb / 20.0);
    }

    /** IEC 61672 A-weighting at {@code freqHz} as a POWER ratio (1 kHz -> 1.0):
     *  the analog magnitude R_A(f) squared, with the +{@value #AW_NORM_1KHZ_DB} dB
     *  1 kHz normalization folded in.  Weighs the integrals behind the
     *  A-weighted N+D readout so it reflects audibility; SNR and N stay
     *  unweighted. */
    private double aWeightPower(double freqHz) {
        double f2 = freqHz * freqHz;
        double ra = (AW_POLE4_HZ * AW_POLE4_HZ * f2 * f2)
                / ((f2 + AW_POLE1_HZ * AW_POLE1_HZ)
                   * Math.sqrt((f2 + AW_POLE2_HZ * AW_POLE2_HZ) * (f2 + AW_POLE3_HZ * AW_POLE3_HZ))
                   * (f2 + AW_POLE4_HZ * AW_POLE4_HZ));
        return ra * ra * AW_NORM_POWER;
    }

    /** The cached per-bin {@link #aWeightPower} table for (halfSize, freqRes) -
     *  rebuilt only when the spectrum geometry changes (bin 0 evaluates to 0). */
    private double[] aWeightTable(int halfSize, double freqRes) {
        if (aWeight == null || aWeight.length != halfSize + 1 || aWeightFreqRes != freqRes) {
            double[] table = new double[halfSize + 1];
            for (int k = 0; k <= halfSize; k++) {
                table[k] = aWeightPower(k * freqRes);
            }
            aWeight        = table;
            aWeightFreqRes = freqRes;
        }
        return aWeight;
    }

    /** Builds the "signal bin" mask: fundamental bin + all harmonic bins,
     *  each smeared by {@code exclBins} on either side - the window's main
     *  lobe plus the leakage guard - plus the span each pilot line covers, as
     *  the analysis stamped it on the result ({@link FftResult#pilot1MaskLoBin}
     *  .. {@link FftResult#pilot2MaskHiBin}).  A generated pilot is neither
     *  signal nor noise, so leaving one in would raise the noise integral
     *  behind SNR, N+D and ENOB by its whole power.  The starting mask for
     *  the noise sums; {@link #extendSignalMask} then extends it with the 
     *  fundamental's skirt and the phase-noise zone. */
    private boolean[] buildSignalBinMask(FftResult r, int halfSize, int exclBins) {
        // Reused (1 MB at fftSize 2 M, rebuilt per call) - must be re-zeroed
        // since an earlier mask marked other bins.
        boolean[] isSignalBin = signalMaskBuffer != null && signalMaskBuffer.length == halfSize + 1
                ? signalMaskBuffer : new boolean[halfSize + 1];
        signalMaskBuffer = isSignalBin;
        Arrays.fill(isSignalBin, false);
        markBins(isSignalBin, r.fundamentalBin - exclBins, r.fundamentalBin + exclBins);
        for (int h = 0; h < r.harmonicCount; h++) {
            int hb = r.harmonicBins[h];
            if (hb > 0) markBins(isSignalBin, hb - exclBins, hb + exclBins);
        }
        markBins(isSignalBin, r.pilot1MaskLoBin, r.pilot1MaskHiBin);
        markBins(isSignalBin, r.pilot2MaskLoBin, r.pilot2MaskHiBin);
        return isSignalBin;
    }

    /** Marks bins {@code lo .. hi} of {@code mask}, clamped to it; an empty
     *  span ({@code hi < lo}) marks nothing. */
    private void markBins(boolean[] mask, int lo, int hi) {
        for (int bin = Math.max(0, lo), lastBin = Math.min(mask.length - 1, hi); bin <= lastBin; bin++) {
            mask[bin] = true;
        }
    }

    /**
     * Creates every line of {@code r} with its geometry, in the slot order of
     * {@link FreqRespCalHelper#toneFrequencies}, on the spectrum as it
     * arrives; the levels come after.
     * <ul>
     *   <li>The fundamental and, for a dual tone, the second fundamental: the
     *       band-top of the two flanks beside its lobe ({@link #toneFloor})
     *       is its floor, and its skirt is walked out to that floor within
     *       the same flanks ({@link #walkSkirt}).</li>
     *   <li>Every distortion line - harmonic or intermodulation product: the
     *       top of the noise band beside it ({@link FftDsp#lineFloor}) is
     *       its floor, and its lobe is walked out to that floor
     *       ({@link FftDsp#lobeBins}).</li>
     * </ul>
     * A bin two lobes would share belongs to the earlier slot: a later lobe
     * is narrowed to stop short of it.
     *
     * @param r          the spectrum
     * @param amplLinear its linear amplitude per bin
     * @param exclBins   the window's main lobe plus the leakage guard, bins
     * @return the lines by slot
     */
    private SpectralLine[] measureLines(FftResult r, double[] amplLinear, int exclBins) {
        int halfSize = r.fftSize / 2;
        double freqRes = r.freqResolution;
        double[] tones = FreqRespCalHelper.toneFrequencies(r);
        int secondSlot = FreqRespCalHelper.secondToneSlot(r);
        int toneSpan = toneFloorSpanBins(freqRes, exclBins);
        IntToDoubleFunction mag = k -> amplLinear[k];
        SpectralLine[] lines = new SpectralLine[tones.length];
        for (int t = 0; t < tones.length; t++) {
            double hz = tones[t];
            int bin = freqRes > 0.0 && hz > 0.0 ? (int) Math.round(hz / freqRes) : -1;
            if (bin < 1 || bin > halfSize) {
                lines[t] = SpectralLine.of(hz, -1, exclBins, 0, Double.NaN);
                continue;
            }
            int skirt;
            double floorDbFs;
            if (t == 0 || t == secondSlot) {
                double floorLin = toneFloor(amplLinear, bin, halfSize, exclBins, toneSpan);
                skirt     = floorLin > 0.0
                        ? walkSkirt(amplLinear, bin, halfSize, floorLin, exclBins + toneSpan) : exclBins;
                floorDbFs = floorLin > 0.0 ? 20.0 * Math.log10(floorLin) : Double.NaN;
            } else {
                double floorLin = FftDsp.lineFloor(mag, bin, halfSize, freqRes, r.windowNenbwBins);
                int[] edges = floorLin > 0.0
                        ? FftDsp.lobeBins(mag, bin, halfSize, floorLin, freqRes, r.windowType)
                        : new int[]{ bin, bin };
                skirt     = Math.max(bin - edges[0], edges[1] - bin);
                floorDbFs = floorLin > 0.0 ? 20.0 * Math.log10(floorLin) : Double.NaN;
            }
            lines[t] = SpectralLine.of(hz, bin, exclBins, clearOfEarlierLobes(lines, t, bin, skirt), floorDbFs);
        }
        return lines;
    }

    /** {@code skirt} narrowed so the lobe {@code bin} +- {@code skirt} stops
     *  short of every lobe of an earlier slot; 0 where the bin itself stands
     *  in one.
     *
     * @param lines the lines measured so far
     * @param slot  the line's slot
     * @param bin   the line's bin
     * @param skirt the lobe's half-width as walked, bins
     * @return the half-width the line keeps, bins */
    private int clearOfEarlierLobes(SpectralLine[] lines, int slot, int bin, int skirt) {
        int kept = skirt;
        for (int e = 0; e < slot; e++) {
            SpectralLine earlier = lines[e];
            if (earlier == null || !earlier.measurable()) continue;
            int gap = Math.abs(bin - earlier.bin()) - earlier.skirtHalfWidthBins() - 1;
            kept = Math.min(kept, Math.max(0, gap));
        }
        return kept;
    }

    /** The width of each flank a fundamental's floor is read on and its
     *  skirt walked within: from past the window's main lobe and the leakage
     *  guard out to just before the nearest mains intermodulation product's
     *  own lobe and guard ({@link #TONE_FLOOR_LIMIT_HZ}), so the flank holds
     *  the skirt and the noise beside it and never the product.
     *
     * @param freqRes  the bin width, Hz
     * @param exclBins the window's main lobe plus the leakage guard, bins
     * @return the flank width, bins; 0 where the spectrum is too coarse to
     *         fit a flank between the lobe and the product */
    private int toneFloorSpanBins(double freqRes, int exclBins) {
        if (!(freqRes > 0.0)) return 0;
        return Math.max(0, (int) Math.round(TONE_FLOOR_LIMIT_HZ / freqRes) - 2 * exclBins - 1);
    }

    /** A fundamental's floor: the band-top ({@link FftDsp#bandTop})
     *  of the two flanks beside its lobe, each starting past the window's
     *  main lobe and the leakage guard and {@code span} bins wide.  The
     *  flank bins are gathered in the noise-bin pool.
     *
     * @param linear   the spectrum, linear amplitude per bin
     * @param bin      the fundamental's bin
     * @param halfSize the last bin
     * @param exclBins the window's main lobe plus the leakage guard, bins
     * @param span     the width of each flank, bins
     * @return the band-top, linear amplitude; 0 where no flank bin is in
     *         range */
    private double toneFloor(double[] linear, int bin, int halfSize, int exclBins, int span) {
        ensureNoisePools(halfSize);
        double[] flank = noiseBandBuffer;
        int m = 0;
        for (int k = bin - exclBins - span; k < bin - exclBins; k++) {
            if (k >= 1 && k <= halfSize) flank[m++] = linear[k];
        }
        for (int k = bin + exclBins + 1; k <= bin + exclBins + span; k++) {
            if (k >= 1 && k <= halfSize) flank[m++] = linear[k];
        }
        return m > 0 ? FftDsp.bandTop(flank, m) : 0.0;
    }

    /** The skirt of a fundamental: walked out from {@code bin} to either side
     *  while bins stand above {@code floorLin}, at most {@code maxBins} out -
     *  the far end of the flanks the floor was read on.
     *
     * @param linear   the spectrum, linear amplitude per bin
     * @param bin      the fundamental's bin
     * @param halfSize the last bin
     * @param floorLin the floor the walk stops at, linear amplitude
     * @param maxBins  the walk's reach to either side, bins
     * @return the skirt's half-width, bins - the wider of the two sides */
    private int walkSkirt(double[] linear, int bin, int halfSize, double floorLin, int maxBins) {
        int lo = bin;
        int hi = bin;
        while (lo > 1 && bin - lo < maxBins && linear[lo - 1] > floorLin) lo--;
        while (hi < halfSize && hi - bin < maxBins && linear[hi + 1] > floorLin) hi++;
        return Math.max(bin - lo, hi - bin);
    }

    /** Extends the signal mask by the fundamental's skirt (walked when its
     *  line was created, {@link #walkSkirt}) where it is wider than the main
     *  lobe, and applies the phase-noise exclusion zone within
     *  +-{@code fundBin/2} of the fundamental: every bin there standing above
     *  the noise floor ({@link #globalNoiseFloor}, read on the same
     *  spectrum).  Mutates {@code isSignalBin} in place to mark every bin
     *  that ends up classified as signal (window leakage + phase noise +
     *  already-marked harmonic neighbours).
     *
     * @param amplLinear  the displayed spectrum, linear amplitude per bin
     * @param floorPow    its noise floor, power
     * @param isSignalBin the signal-bin mask, extended in place
     * @param halfSize    the last bin
     * @param fundBin     the fundamental's bin
     * @param exclBins    the window's main lobe plus the leakage guard, bins
     * @param skirtBins   the fundamental's skirt half-width, bins */
    private void extendSignalMask(double[] amplLinear, double floorPow, boolean[] isSignalBin,
            int halfSize, int fundBin, int exclBins, int skirtBins) {
        if (skirtBins > exclBins) {
            markBins(isSignalBin, fundBin - skirtBins, fundBin + skirtBins);
        }

        // Phase-noise exclusion: scan a wide zone around the fundamental and
        // mark any bin above the refined noise-floor median as signal.  The
        // contiguous skirt walk terminates on the first sub-floor stretch, so
        // isolated phase-noise bumps beyond it would otherwise be counted
        // toward SNR.  Zone is capped at +-fundBin/2 so it cannot reach H2 at
        // 2 x fundBin.
        if (floorPow > 0) {
            int phaseLo = Math.max(1, fundBin - fundBin / 2);
            int phaseHi = Math.min(halfSize, fundBin + fundBin / 2);
            for (int k = phaseLo; k <= phaseHi; k++) {
                if (!isSignalBin[k] && amplLinear[k] * amplLinear[k] > floorPow) {
                    isSignalBin[k] = true;
                }
            }
        }
    }

    /** The noise floor of the whole spectrum: the band-top
     *  ({@link FftDsp#bandTop}, median + 4 MAD) of every bin from the first
     *  above DC to Nyquist that no line covers.  Each line - fundamentals,
     *  harmonics, intermodulation products - is left out over its skirt, at
     *  least over its main lobe and the leakage guard, and both pilot spans
     *  are left out.  Weak peaks no line accounts for, mains and its
     *  harmonics among them, stay in: they are few bins against the whole
     *  spectrum, and the median and MAD ride over them.
     *
     * @param r        the spectrum, for its pilot spans
     * @param linear   the spectrum, linear amplitude per bin
     * @param lines    the lines by slot; a {@code null} or unmeasurable slot
     *                 is skipped
     * @param halfSize the last bin
     * @return the band-top, linear amplitude; 0 where every bin is covered */
    private double globalNoiseFloor(FftResult r, double[] linear, SpectralLine[] lines, int halfSize) {
        boolean[] mask = lineMaskBuffer != null && lineMaskBuffer.length == halfSize + 1
                ? lineMaskBuffer : new boolean[halfSize + 1];
        lineMaskBuffer = mask;
        Arrays.fill(mask, false);
        for (SpectralLine line : lines) {
            if (line == null || !line.measurable()) continue;
            int half = Math.max(line.halfWidthBins(), line.skirtHalfWidthBins());
            markBins(mask, line.bin() - half, line.bin() + half);
        }
        markBins(mask, r.pilot1MaskLoBin, r.pilot1MaskHiBin);
        markBins(mask, r.pilot2MaskLoBin, r.pilot2MaskHiBin);
        ensureNoisePools(halfSize);
        double[] noise = noiseBandBuffer;
        int m = 0;
        for (int k = 1; k <= halfSize; k++) {
            if (!mask[k]) noise[m++] = linear[k];
        }
        return m > 0 ? FftDsp.bandTop(noise, m) : 0.0;
    }

    /** Grows the noise-bin pool to hold {@code halfSize} bins.
     *
     * @param halfSize the last bin */
    private void ensureNoisePools(int halfSize) {
        if (noiseBandBuffer == null || noiseBandBuffer.length < halfSize) {
            noiseBandBuffer = new double[halfSize];
        }
    }
}
