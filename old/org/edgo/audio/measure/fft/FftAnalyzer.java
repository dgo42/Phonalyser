package org.edgo.audio.measure.fft;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.edgo.audio.measure.chart.ChartStyle;
import org.edgo.audio.measure.sound.CsjsoundRecorder;
import org.jfree.chart.ChartFactory;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.annotations.XYTextAnnotation;
import org.jfree.chart.axis.AxisLocation;
import org.jfree.chart.axis.AxisState;
import org.jfree.chart.axis.LogAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.NumberTick;
import org.jfree.chart.axis.NumberTickUnit;
import org.jfree.chart.axis.TickType;
import org.jfree.chart.plot.IntervalMarker;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.ui.Layer;
import org.jfree.chart.ui.RectangleEdge;
import org.jfree.chart.ui.TextAnchor;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import lombok.extern.log4j.Log4j2;

/**
 * FFT-based audio analyzer: coherent averaging, fundamental detection, THD,
 * THD+N (IEC 61672:2003 A-weighting), SNR.
 *
 * <p>Supports configurable overlap (0 %, 50 %, 75 %, 87.5 %, 92.75 %) and
 * configurable window function (Rectangular, Hann, Blackman-Harris 4/7,
 * Flat-top, Dolph-Chebyshev 150 dB / 200 dB).
 */
@Log4j2
public class FftAnalyzer {

    // =========================================================================
    // WindowType
    // =========================================================================

    public enum WindowType {
        RECTANGULAR,
        HANN,
        BLACKMAN_HARRIS_4,
        BLACKMAN_HARRIS_7,
        FLAT_TOP,
        DOLPH_CHEBYSHEV_150,
        DOLPH_CHEBYSHEV_200;

        private WindowType() {}

        public static WindowType fromString(String s) {
            switch (s.toUpperCase(Locale.ROOT).replace('-', '_')) {
                case "RECTANGULAR":
                case "RECT":
                    return RECTANGULAR;
                case "HANN":
                    return HANN;
                case "BLACKMAN_HARRIS_4":
                case "BH4":
                    return BLACKMAN_HARRIS_4;
                case "BLACKMAN_HARRIS_7":
                case "BH7":
                    return BLACKMAN_HARRIS_7;
                case "FLAT_TOP":
                case "FLATTOP":
                    return FLAT_TOP;
                case "DOLPH_CHEBYSHEV_150":
                case "DC150":
                case "CHEBYSHEV_150":
                    return DOLPH_CHEBYSHEV_150;
                case "DOLPH_CHEBYSHEV_200":
                case "DC200":
                case "CHEBYSHEV_200":
                    return DOLPH_CHEBYSHEV_200;
                default:
                    throw new IllegalArgumentException("Unknown window type: " + s);
            }
        }
    }

    // =========================================================================
    // Overlap
    // =========================================================================

    public enum Overlap {
        PCT_0(0.0,    "0%"),
        PCT_50(0.5,   "50%"),
        PCT_75(0.75,  "75%"),
        PCT_87_5(0.875,  "87.5%"),
        PCT_92_75(0.9275, "92.75%");

        public final double fraction;
        public final String label;

        private Overlap(double fraction, String label) {
            this.fraction = fraction;
            this.label    = label;
        }

        public static Overlap fromString(String s) {
            String norm = s.trim().replace("%", "").replace(",", ".");
            switch (norm) {
                case "0":
                    return PCT_0;
                case "50":
                    return PCT_50;
                case "75":
                    return PCT_75;
                case "87.5":
                case "87":
                    return PCT_87_5;
                case "92.75":
                case "92":
                case "93":
                    return PCT_92_75;
                default:
                    throw new IllegalArgumentException(
                            "Unknown overlap: " + s + " — use 0, 50, 75, 87.5, or 92.75");
            }
        }
    }

    // =========================================================================
    // Result
    // =========================================================================

    /** Immutable container for all FFT analysis outputs. */
    public class Result {

        /** FFT frame length (power of 2). */
        public final int fftSize;
        /** Sample rate of the analyzed signal in Hz. */
        public final int sampleRate;
        /** Number of frames that were coherently averaged. */
        public final int frameCount;
        /** Frequency resolution in Hz per bin (= sampleRate / fftSize). */
        public final double freqResolution;
        /** Window function used. */
        public final WindowType windowType;
        /** Overlap used. */
        public final Overlap overlap;

        // Single-sided spectrum, bins 0 … fftSize/2
        public final double[] amplitudeDbFs;
        public final double[] phaseDeg;
        public final double[] re;
        public final double[] im;

        // Fundamental — non-final so post-processing (e.g. filter compensation) can mutate.
        public final int    fundamentalBin;
        public final double fundamentalHz;
        /** Phase-difference refined frequency in Hz — sub-bin accurate (~1e-5 bin). */
        public final double fundamentalHzRefined;
        public double fundamentalDbFs;
        public double fundamentalLinear;

        // Harmonics (index 0 = 2nd harmonic, …)
        public final int      harmonicCount;
        public final int[]    harmonicBins;
        public final double[] harmonicHz;
        public final double[] harmonicDbFs;
        public final double[] harmonicPct;

        // Metrics — non-final so post-processing (e.g. ADC/filter correction) can mutate.
        public double thdPct;
        public double thdDb;
        public double thdNDb;
        public double snrDb;
        /** Unweighted SINAD: 10·log10(refLin² / (noisePower + Σ harmonic power)) — basis for ENOB. */
        public double sinadDb;
        /** Lower bound of the frequency range used for SNR (Hz); 0 = no limit. */
        public final double snrFreqMin;
        /** Upper bound of the frequency range used for SNR (Hz); 0 = no limit. */
        public final double snrFreqMax;
        /** True = coherent (complex) averaging; false = incoherent (power) averaging. */
        public final boolean coherentAveraging;
        /** Sum of amplLinear[k]^2 for noise bins inside the SNR band (unweighted). */
        public double noisePower;
        /** A-weighted noise+distortion power (all non-fundamental bins, no band limit). */
        public double awNoisePower;
        /** Average noise floor: RMS amplitude of a single noise bin converted to dBFS. */
        public double avgNoiseFloorDbFs;
        /**
         * Half-width of the dynamic fundamental exclusion zone in Hz (one side).
         * For a clean recording this is a few Hz; a signal interruption causes
         * spectral splatter that broadens this to hundreds or thousands of Hz.
         */
        public final double fundamentalDynExclusionHz;
        /**
         * Reference dBV for the fundamental.  When set, dBV is shown for every bin
         * as {@code amplitudeDbFs[k] + (fundRefDbV - fundamentalDbFs)}; {@link Double#NaN}
         * suppresses the dBV scale.  Sources, in priority order:
         *   1. ADC full-scale voltage (from the cal CSV header or {@code --adc-fs-vrms}):
         *      {@code fundRefDbV = fundamentalDbFs + 20·log10(adcFsVoltageRms)} —
         *      gives every bin its calibrated absolute dBV (e.g. -82 dBFS → -76.93 dBV
         *      with FS = 1.7931 V_rms).  This is the preferred source.
         *   2. {@code --fund-v}/{@code --fund-dbv}: legacy, treats the fundamental's
         *      dBV as fixed at the user-stated value.  Only used when the calibrated
         *      ADC voltage is unknown.
         * Mutable so post-processing (e.g. filter compensation) can refresh it once
         * the corrected fundamentalDbFs is known.
         */
        public double fundRefDbV;
        /**
         * User-supplied true fundamental dBV (from {@code --fund-v} / {@code --fund-dbv}),
         * preserved verbatim and never overwritten by post-processing.  When set:
         * <ul>
         *   <li>THD / SNR / THD+N / harmonic % use this as the ratio denominator,
         *       so an external twin-T notch on H1 cannot poison the metrics.</li>
         *   <li>The chart's fundamental peak marker and info-table dBV column
         *       show this value instead of the (notched-and-cal-converted)
         *       {@code fundamentalDbFs + dbFsToDbV}.</li>
         * </ul>
         * The global dBFS→dBV offset for spectrum/harmonics still comes from
         * {@code fundRefDbV} (cal-CSV-derived when available), which is the
         * correct anchor for bins outside the notch.  {@link Double#NaN} when
         * no user override is supplied.
         */
        public double fundamentalTrueDbV;

        Result(int fftSize, int sampleRate, int frameCount, double freqResolution,
               WindowType windowType, Overlap overlap,
               double[] amplitudeDbFs, double[] phaseDeg, double[] re, double[] im,
               int fundamentalBin, double fundamentalHz, double fundamentalHzRefined,
               double fundamentalDbFs, double fundamentalLinear,
               int harmonicCount, int[] harmonicBins, double[] harmonicHz,
               double[] harmonicDbFs, double[] harmonicPct,
               double thdPct, double thdDb, double thdNDb, double snrDb,
               double snrFreqMin, double snrFreqMax, boolean coherentAveraging,
               double noisePower, double awNoisePower, double fundRefDbV,
               double avgNoiseFloorDbFs, double fundamentalDynExclusionHz) {
            this.fftSize           = fftSize;
            this.sampleRate        = sampleRate;
            this.frameCount        = frameCount;
            this.freqResolution    = freqResolution;
            this.windowType        = windowType;
            this.overlap           = overlap;
            this.amplitudeDbFs     = amplitudeDbFs;
            this.phaseDeg          = phaseDeg;
            this.re                = re;
            this.im                = im;
            this.fundamentalBin        = fundamentalBin;
            this.fundamentalHz         = fundamentalHz;
            this.fundamentalHzRefined  = fundamentalHzRefined;
            this.fundamentalDbFs       = fundamentalDbFs;
            this.fundamentalLinear = fundamentalLinear;
            this.harmonicCount     = harmonicCount;
            this.harmonicBins      = harmonicBins;
            this.harmonicHz        = harmonicHz;
            this.harmonicDbFs      = harmonicDbFs;
            this.harmonicPct       = harmonicPct;
            this.thdPct            = thdPct;
            this.thdDb             = thdDb;
            this.thdNDb            = thdNDb;
            this.snrDb             = snrDb;
            this.snrFreqMin        = snrFreqMin;
            this.snrFreqMax        = snrFreqMax;
            this.coherentAveraging = coherentAveraging;
            this.noisePower           = noisePower;
            this.awNoisePower         = awNoisePower;
            this.fundRefDbV                = fundRefDbV;
            this.fundamentalTrueDbV        = fundRefDbV;   // verbatim user input — cal CSV does not overwrite
            this.avgNoiseFloorDbFs         = avgNoiseFloorDbFs;
            this.fundamentalDynExclusionHz = fundamentalDynExclusionHz;
        }
    }

    // =========================================================================
    // Analysis — backward-compatible overload (Hann, 0 % overlap)
    // =========================================================================

    public Result analyze(float[] samples, int sampleRate,
                                 int fftSize, int harmonicCount) {
        return analyze(samples, sampleRate, fftSize, harmonicCount,
                WindowType.HANN, Overlap.PCT_0, 0.0, 0.0, true, Double.NaN);
    }

    public Result analyze(float[] samples, int sampleRate,
                                 int fftSize, int harmonicCount,
                                 WindowType windowType, Overlap overlap) {
        return analyze(samples, sampleRate, fftSize, harmonicCount,
                windowType, overlap, 0.0, 0.0, true, Double.NaN);
    }

    public Result analyze(float[] samples, int sampleRate,
                                 int fftSize, int harmonicCount,
                                 WindowType windowType, Overlap overlap,
                                 double snrFreqMin, double snrFreqMax,
                                 boolean coherentAveraging) {
        return analyze(samples, sampleRate, fftSize, harmonicCount,
                windowType, overlap, snrFreqMin, snrFreqMax, coherentAveraging, Double.NaN);
    }

    // =========================================================================
    // Analysis — full version
    // =========================================================================

    /**
     * Runs coherent-averaged FFT analysis on a normalized mono signal.
     *
     * @param samples       signal samples, range −1.0 … +1.0
     * @param sampleRate    sample rate in Hz
     * @param fftSize       FFT frame length — must be a power of 2
     * @param harmonicCount number of harmonics to evaluate (2nd … N-th)
     * @param windowType    window function to apply per frame
     * @param overlap       overlap between successive frames
     * @param snrFreqMin         lower bound for SNR noise integration in Hz (0 = no limit)
     * @param snrFreqMax         upper bound for SNR noise integration in Hz (0 = no limit)
     * @param coherentAveraging  true = accumulate complex spectra (noise cancels across frames);
     *                           false = accumulate power per bin (no phase coherence required)
     * @param fundRefDbV    known real level of the fundamental in dBV; {@link Double#NaN} = unknown
     * @return fully populated {@link Result}
     */
    public Result analyze(float[] samples, int sampleRate,
                                 int fftSize, int harmonicCount,
                                 WindowType windowType, Overlap overlap,
                                 double snrFreqMin, double snrFreqMax,
                                 boolean coherentAveraging, double fundRefDbV) {
        return analyze(samples, sampleRate, fftSize, harmonicCount,
                windowType, overlap, snrFreqMin, snrFreqMax, coherentAveraging,
                fundRefDbV, true);
    }

    /**
     * Same as the 10-arg overload, plus {@code logSummary}: when {@code false},
     * the trailing THD / THD+N / SNR log lines are suppressed so the caller
     * can mutate the result (e.g. apply ADC correction) and log the final
     * numbers itself without producing a misleading uncorrected line first.
     */
    public Result analyze(float[] samples, int sampleRate,
                                 int fftSize, int harmonicCount,
                                 WindowType windowType, Overlap overlap,
                                 double snrFreqMin, double snrFreqMax,
                                 boolean coherentAveraging, double fundRefDbV,
                                 boolean logSummary) {
        return analyze(samples, sampleRate, fftSize, harmonicCount,
                windowType, overlap, snrFreqMin, snrFreqMax, coherentAveraging,
                fundRefDbV, logSummary, Double.NaN);
    }

    /**
     * Full overload with {@code expectedFundHz}: when set (not {@link Double#NaN}),
     * the fundamental peak search is restricted to a narrow ±10-bin window around
     * the expected frequency instead of the loudest bin in the spectrum.  Use this
     * when the fundamental can be deeply attenuated (e.g. measuring distortion
     * through a notch filter at the notch frequency) — global max would otherwise
     * latch onto a harmonic, mains hum, or a spur and the entire downstream
     * harmonic table would be wrong.  Pass {@link Double#NaN} for the legacy
     * loudest-bin behaviour.
     */
    public Result analyze(float[] samples, int sampleRate,
                                 int fftSize, int harmonicCount,
                                 WindowType windowType, Overlap overlap,
                                 double snrFreqMin, double snrFreqMax,
                                 boolean coherentAveraging, double fundRefDbV,
                                 boolean logSummary, double expectedFundHz) {
        if (Integer.bitCount(fftSize) != 1) {
            throw new IllegalArgumentException("fftSize must be a power of 2, got: " + fftSize);
        }

        int step = Math.max(1, (int) Math.round(fftSize * (1.0 - overlap.fraction)));
        int frameCount = samples.length >= fftSize
                ? (samples.length - fftSize) / step + 1
                : 0;
        if (frameCount == 0) {
            throw new IllegalArgumentException(
                    "Signal too short for fftSize=" + fftSize +
                    ": need at least " + fftSize + " samples, got " + samples.length);
        }

        double freqRes = (double) sampleRate / fftSize;
        log.info("FFT: size={}, frames={}, overlap={}, window={}, averaging={}, freq-res={} Hz, harmonics={}",
                fftSize, frameCount, overlap.label, windowType,
                coherentAveraging ? "coherent" : "incoherent",
                String.format(Locale.US, "%.4f", freqRes), harmonicCount);

        // --- Window + coherent gain ------------------------------------------
        double[] window  = buildWindow(fftSize, windowType);
        double   cohGain = 0.0;
        for (double v : window) {
            cohGain += v;
        }
        cohGain /= fftSize;

        // --- Estimate fundamental fractional bin (always, for both averaging modes) --
        // The true signal frequency is k_f × Fs/N where k_f is non-integer.
        // Using the integer bin for harmonic synthesis causes phase drift of
        // 2π×ε×n/N per sample → complete cancellation failure over long recordings.
        // Phase-difference method: window-independent, accurate to ~1e-5 bins.
        //
        // Strategy: do peak-bin detection on a frame from inside the longest
        // CLEAN segment (re-estimated below), so glitchy frames at the
        // beginning of the recording cannot poison the kFractional estimate.
        int halfSize = fftSize / 2;

        // --- Pre-pass peak-bin estimate (from frame 0; only used to size the
        //     slew threshold — final kFractional is re-estimated post-rejection)
        double[] f0Re = new double[fftSize];
        double[] f0Im = new double[fftSize];
        for (int n = 0; n < fftSize; n++) {
            f0Re[n] = samples[n] * window[n];
        }
        fft(f0Re, f0Im);
        // Skip the DC + ULF zone below 10 Hz when searching for the fundamental:
        // window leakage from a residual DC offset can populate bin 1 at roughly
        // -32 dB (Hann) below DC level, easily beating a deeply notched
        // fundamental at e.g. -100 dBFS.  10 Hz is well below any audio-band
        // fundamental and gives a comfortable margin past the window's main lobe.
        int fundSearchMinBin = Math.max(2, (int) Math.ceil(10.0 / freqRes));
        int intFundBin;
        if (!Double.isNaN(expectedFundHz) && expectedFundHz > 0.0) {
            // Hint provided — search ±10 bins around the expected bin instead of
            // global max, so a deeply notched fundamental is not lost to a louder
            // harmonic / mains spur / noise spike elsewhere in the spectrum.
            int expectedBin = (int) Math.round(expectedFundHz * fftSize / (double) sampleRate);
            int kLo = Math.max(1,        expectedBin - 10);
            int kHi = Math.min(halfSize, expectedBin + 10);
            intFundBin = kLo;
            double peakPow = f0Re[kLo] * f0Re[kLo] + f0Im[kLo] * f0Im[kLo];
            for (int k = kLo + 1; k <= kHi; k++) {
                double p = f0Re[k] * f0Re[k] + f0Im[k] * f0Im[k];
                if (p > peakPow) { peakPow = p; intFundBin = k; }
            }
            log.info("Fundamental seeded from expected freq {} Hz: bin {} (±10-bin window)",
                    String.format(Locale.US, "%.6f", expectedFundHz), intFundBin);
        } else {
            intFundBin = fundSearchMinBin;
            double peakPow = f0Re[fundSearchMinBin] * f0Re[fundSearchMinBin]
                           + f0Im[fundSearchMinBin] * f0Im[fundSearchMinBin];
            for (int k = fundSearchMinBin + 1; k <= halfSize; k++) {
                double p = f0Re[k] * f0Re[k] + f0Im[k] * f0Im[k];
                if (p > peakPow) { peakPow = p; intFundBin = k; }
            }
            log.info("Fundamental search (no hint): scanned bins {}..{} (~{} Hz upward); picked bin {} (~{} Hz)",
                    fundSearchMinBin, halfSize,
                    String.format(Locale.US, "%.2f", fundSearchMinBin * freqRes),
                    intFundBin,
                    String.format(Locale.US, "%.4f", intFundBin * freqRes));
        }

        // --- Frame-rejection threshold (signal-interruption detector) --------
        // Pythagorean sine invariant: for s = A·sin(θ) and k = 2π·f/Fs,
        //   ds/dn = A·k·cos(θ)  ⇒  s² + (ds/dn / k)² ≡ A²
        // For each sample we compute R = √(s² + (Δs/k)²) using a central
        // difference. Clean samples → R ≈ A; glitches violate the invariant.
        // The test is amplitude-relative and phase-aware: a small |Δ| at a
        // peak (where slope ≈ 0) still produces a large R deviation, which the
        // old constant-|Δ| threshold would have missed.
        //
        // peakAmp is RMS-derived (peak = √2·RMS for a sine) so that overshoots
        // from dropouts do not inflate A and mask further glitches.
        double sumSq = 0.0;
        for (float s : samples) {
            sumSq += (double) s * s;
        }
        double rms     = Math.sqrt(sumSq / samples.length);
        double peakAmp = rms * Math.sqrt(2.0);
        double estFundHz = intFundBin * freqRes;
        double omegaPerSample = 2.0 * Math.PI * estFundHz / sampleRate;
        final double R_TOLERANCE = 0.10;      // |R−A|/A > 10 %  → flag sample

        // The R-invariant test assumes s(n) ≈ A·sin(ωn); when the fundamental
        // sine is not the dominant component of the time-domain signal (typical
        // when measuring distortion through a deep filter notch — fundamental
        // at -100 dBFS while DC offset + noise + harmonics dominate the time
        // domain), the invariant cannot hold and the whole recording would be
        // rejected.  Compare the spectrum-bin amplitude at the candidate
        // fundamental against the time-domain peak; skip rejection unless the
        // sine carries at least half the time-domain peak amplitude.
        double specMag = Math.sqrt(f0Re[intFundBin] * f0Re[intFundBin]
                                 + f0Im[intFundBin] * f0Im[intFundBin]);
        double specPeakAmp = specMag * 2.0 / ((double) fftSize * cohGain);
        boolean rejectionFeasible = peakAmp > 0.0 && specPeakAmp >= 0.5 * peakAmp;
        log.info("Frame R-invariant check: peakAmp(RMS-derived)={}, fund-bin peak={} (ratio {} dB), k={}, tolerance={}% (using intBin={}, ~{} Hz)",
                String.format(Locale.US, "%.6e", peakAmp),
                String.format(Locale.US, "%.6e", specPeakAmp),
                peakAmp > 0
                        ? String.format(Locale.US, "%+.1f", 20.0 * Math.log10(specPeakAmp / peakAmp))
                        : "n/a",
                String.format(Locale.US, "%.6f", omegaPerSample),
                String.format(Locale.US, "%.1f", R_TOLERANCE * 100.0),
                intFundBin,
                String.format(Locale.US, "%.4f", estFundHz));
        if (!rejectionFeasible) {
            log.info("Frame R-invariant rejection skipped: fundamental sine buried in noise/spurs "
                    + "(fund-bin peak >20 dB below time-domain peak); accepting all frames");
        }

        // --- Averaging (coherent: complex sum; incoherent: power sum) --------
        double[] sumRe   = new double[fftSize];   // complex Re  OR  power (incoherent)
        double[] sumIm   = new double[fftSize];   // complex Im  OR  unused (incoherent)
        double[] frameRe = new double[fftSize];
        double[] frameIm = new double[fftSize];

        // --- Pass 1: per-sample R scan, then group hits into events ----------
        // Adjacent hits (within GAP samples) belong to the same disturbance, so we
        // collapse them into one "event" with a start/end sample index.
        // Each event is logged with its file-time so it can be cross-checked
        // against an external viewer (e.g. Audacity).
        // Then, for every event, all FFT frames whose sample range overlaps
        // the event are marked dirty. Longest-clean-segment selection follows.
        final int EVENT_GAP_SAMPLES = 16;
        int hitCount = 0;
        double maxRDevSeen = 0.0;             // max |R−A|/A over the whole signal
        List<long[]> events = new ArrayList<>();  // {startSample, endSample, peakDevScaled (×1e9 as long)}
        if (rejectionFeasible) {
            int evStart = -1;
            int evEnd   = -1;
            double evPeak = 0.0;
            for (int n = 1; n < samples.length - 1; n++) {
                double dCentral = ((double) samples[n + 1] - samples[n - 1]) * 0.5;
                double dq       = dCentral / omegaPerSample;
                double r        = Math.sqrt((double) samples[n] * samples[n] + dq * dq);
                double dev      = Math.abs(r - peakAmp) / peakAmp;
                if (dev > maxRDevSeen) maxRDevSeen = dev;
                if (dev > R_TOLERANCE) {
                    hitCount++;
                    if (evStart < 0 || n - evEnd > EVENT_GAP_SAMPLES) {
                        if (evStart >= 0) {
                            events.add(new long[]{evStart, evEnd, Math.round(evPeak * 1e9)});
                        }
                        evStart = n;
                        evPeak  = dev;
                    }
                    evEnd = n;
                    if (dev > evPeak) evPeak = dev;
                }
            }
            if (evStart >= 0) {
                events.add(new long[]{evStart, evEnd, Math.round(evPeak * 1e9)});
            }
        }

        boolean[] frameClean = new boolean[frameCount];
        java.util.Arrays.fill(frameClean, true);
        int rejectedFrames = 0;
        if (!events.isEmpty()) {
            log.warn("R-invariant scan: {} sample hit(s) > {}%, grouped into {} event(s) (max |R−A|/A in signal = {}%)",
                    hitCount,
                    String.format(Locale.US, "%.1f", R_TOLERANCE * 100.0),
                    events.size(),
                    String.format(Locale.US, "%.2f", maxRDevSeen * 100.0));
            for (int e = 0; e < events.size(); e++) {
                long[] ev     = events.get(e);
                int   evS     = (int) ev[0];
                int   evE     = (int) ev[1];
                double evPk   = ev[2] / 1e9;
                double tStart = evS / (double) sampleRate;
                int    width  = evE - evS + 1;
                // Frames whose [base .. base+fftSize-1] window touches [evS .. evE]:
                //   base + fftSize - 1 >= evS  AND  base <= evE
                //   → base in [evE - fftSize + 1 .. evS] / step
                int fLo = Math.max(0, (int) Math.ceil((evE - (long) fftSize + 1) / (double) step));
                int fHi = Math.min(frameCount - 1, evS / step);
                int dirty = 0;
                for (int f = fLo; f <= fHi; f++) {
                    if (frameClean[f]) {
                        frameClean[f] = false;
                        dirty++;
                    }
                }
                log.warn("  event {}/{}: sample {} (t={} s), width={} samples ({} µs), peak |R−A|/A={}% → frames [{}..{}] dirty (+{})",
                        e + 1, events.size(),
                        evS,
                        String.format(Locale.US, "%.6f", tStart),
                        width,
                        String.format(Locale.US, "%.2f", width * 1e6 / sampleRate),
                        String.format(Locale.US, "%.2f", evPk * 100.0),
                        fLo, fHi, dirty);
            }
            for (boolean c : frameClean) if (!c) rejectedFrames++;
        }
        double maxRDevAccepted = events.isEmpty() ? maxRDevSeen : 0.0;
        double maxRDevRejected = events.isEmpty() ? 0.0 : maxRDevSeen;
        if (!events.isEmpty()) {
            // Recompute max accepted R deviation over surviving frames so the
            // log line below stays meaningful when some frames are rejected.
            for (int f = 0; f < frameCount; f++) {
                if (!frameClean[f]) continue;
                int base = f * step;
                int from = Math.max(1, base);
                int to   = Math.min(samples.length - 2, base + fftSize - 1);
                for (int n = from; n <= to; n++) {
                    double dCentral = ((double) samples[n + 1] - samples[n - 1]) * 0.5;
                    double dq       = dCentral / omegaPerSample;
                    double r        = Math.sqrt((double) samples[n] * samples[n] + dq * dq);
                    double dev      = Math.abs(r - peakAmp) / peakAmp;
                    if (dev > maxRDevAccepted) maxRDevAccepted = dev;
                }
            }
        }

        // --- Find longest contiguous clean run -------------------------------
        int bestStart = 0, bestLen = 0;
        int curStart  = 0, curLen  = 0;
        for (int f = 0; f < frameCount; f++) {
            if (frameClean[f]) {
                if (curLen == 0) curStart = f;
                curLen++;
                if (curLen > bestLen) { bestLen = curLen; bestStart = curStart; }
            } else {
                curLen = 0;
            }
        }

        log.info("Frame R-invariant stats: max accepted |R−A|/A={}%, max rejected |R−A|/A={}",
                String.format(Locale.US, "%.2f", maxRDevAccepted * 100.0),
                rejectedFrames > 0 ? String.format(Locale.US, "%.2f%%", maxRDevRejected * 100.0) : "n/a");
        if (rejectedFrames > 0) {
            log.warn("Frame rejection: {} of {} frames R-invariant-rejected; using longest clean segment [{}..{}] ({} frames)",
                    rejectedFrames, frameCount,
                    bestStart, bestStart + bestLen - 1, bestLen);
        }
        if (bestLen == 0) {
            throw new IllegalStateException("All " + frameCount + " frames rejected as corrupt — aborting FFT.");
        }

        // --- Re-estimate kFractional from the clean segment ------------------
        // Glitchy frames at the start of the recording would otherwise poison
        // the phase-difference estimate, leaving a linear residual phase drift
        // across the (clean) segment that smears the fundamental over many bins.
        // We use the first frame of the segment + one frame `step` later when
        // available, otherwise fall back to parabolic interpolation.
        int    segBaseSample = bestStart * step;
        double[] s0Re = new double[fftSize];
        double[] s0Im = new double[fftSize];
        for (int n = 0; n < fftSize; n++) {
            s0Re[n] = samples[segBaseSample + n] * window[n];
        }
        fft(s0Re, s0Im);
        // refine intFundBin within ±2 bins of the pre-pass estimate (in case of drift)
        int refIntBin = intFundBin;
        {
            double peakPow = s0Re[refIntBin] * s0Re[refIntBin] + s0Im[refIntBin] * s0Im[refIntBin];
            int kLo = Math.max(1, intFundBin - 2);
            int kHi = Math.min(halfSize, intFundBin + 2);
            for (int k = kLo; k <= kHi; k++) {
                double p = s0Re[k] * s0Re[k] + s0Im[k] * s0Im[k];
                if (p > peakPow) { peakPow = p; refIntBin = k; }
            }
        }
        double kFractional;
        if (bestLen >= 2) {
            int    estStep = step > 0 ? step : fftSize;
            double[] s1Re = new double[fftSize];
            double[] s1Im = new double[fftSize];
            for (int n = 0; n < fftSize; n++) {
                s1Re[n] = samples[segBaseSample + estStep + n] * window[n];
            }
            fft(s1Re, s1Im);
            double phi0 = Math.atan2(s0Im[refIntBin], s0Re[refIntBin]);
            double phi1 = Math.atan2(s1Im[refIntBin], s1Re[refIntBin]);
            double expectedDiff = 2.0 * Math.PI * refIntBin * estStep / (double) fftSize;
            double rawDiff      = phi1 - phi0;
            long   m            = Math.round((rawDiff - expectedDiff) / (2.0 * Math.PI));
            kFractional = (rawDiff - 2.0 * Math.PI * m) * fftSize / (2.0 * Math.PI * estStep);
        } else {
            kFractional = parabolicBinInterp(s0Re, s0Im, refIntBin, fftSize);
        }
        intFundBin = refIntBin;
        log.info("k_f re-estimate (from clean segment frame {}): intBin={}, k_f={}, refinedHz={}",
                bestStart, intFundBin,
                String.format(Locale.US, "%.6f", kFractional),
                String.format(Locale.US, "%.6f", kFractional * freqRes));

        // --- Pass 2: accumulate frames in the longest clean segment ----------
        // Also store per-frame corrected fundamental complex value so we can
        // detect sub-slew sample drops (which manifest as a phase step in the
        // fundamental that the time-shift correction cannot account for).
        int    intFundBinRounded = (int) Math.round(kFractional);
        double[] fundCorrRe = coherentAveraging ? new double[bestLen] : null;
        double[] fundCorrIm = coherentAveraging ? new double[bestLen] : null;
        int acceptedFrames = 0;
        for (int f = bestStart; f < bestStart + bestLen; f++) {
            int base = f * step;
            for (int n = 0; n < fftSize; n++) {
                frameRe[n] = samples[base + n] * window[n];
                frameIm[n] = 0.0;
            }
            fft(frameRe, frameIm);
            if (coherentAveraging) {
                // DFT time-shift theorem: frame f starting at sample f·step imparts phase
                //   exp(+j · 2π · k_f · f · step / N)
                // to bin k₀ (= round(k_f)).  To align all frames to frame-0 phase, multiply
                // each frame's spectrum by the CONJUGATE:
                //   correction(k) = exp(−j · 2π · k_f · f · step / N)
                // Generalised to bin k (to correct the whole spectrum consistently):
                //   correction(k) = exp(−j · k · 2π · f · step · k_f / (N · k₀))
                // which gives the exact per-bin rotation via an incremental complex phasor.
                double baseAngle  = -2.0 * Math.PI * (long) f * step * kFractional
                                    / ((double) fftSize * intFundBinRounded);   // NEGATIVE: conjugate rotation
                double cosBase = Math.cos(baseAngle);
                double sinBase = Math.sin(baseAngle);
                double corrRe = 1.0, corrIm = 0.0;   // exp(−j·0) = 1, k=0
                for (int k = 0; k < fftSize; k++) {
                    double cRe = frameRe[k] * corrRe - frameIm[k] * corrIm;
                    double cIm = frameRe[k] * corrIm + frameIm[k] * corrRe;
                    sumRe[k] += cRe;
                    sumIm[k] += cIm;
                    if (k == intFundBin) {
                        fundCorrRe[f - bestStart] = cRe;
                        fundCorrIm[f - bestStart] = cIm;
                    }
                    double nextCorrRe = corrRe * cosBase - corrIm * sinBase;
                    corrIm            = corrRe * sinBase + corrIm * cosBase;
                    corrRe            = nextCorrRe;
                }
            } else {
                for (int k = 0; k < fftSize; k++) {
                    sumRe[k] += frameRe[k] * frameRe[k] + frameIm[k] * frameIm[k];
                }
            }
            acceptedFrames++;
        }

        // --- Per-frame fundamental phase coherence check ---------------------
        // After time-shift correction, every frame's fundamental should land at
        // the same phase (to within thermal noise).  A sub-slew sample drop
        // produces a phase step Δφ = 2π·f·Δsamples/Fs that the correction does
        // not account for, so the offending frame stands out as a phase outlier.
        // We reject any frame deviating from the circular median by more than
        // PHASE_COHERENCE_THRESHOLD_RAD, then re-FFT and SUBTRACT each rejected
        // frame's contribution from the accumulator.
        if (coherentAveraging && bestLen >= 4) {
            final double PHASE_COHERENCE_THRESHOLD_RAD = Math.toRadians(3.0);   // 3°
            // Circular median via atan2(median(sin), median(cos)) — robust for
            // tightly clustered phases (the expected case for a coherent signal).
            double[] sins = new double[bestLen];
            double[] coss = new double[bestLen];
            for (int i = 0; i < bestLen; i++) {
                double mag = Math.sqrt(fundCorrRe[i] * fundCorrRe[i] + fundCorrIm[i] * fundCorrIm[i]);
                sins[i] = mag > 0 ? fundCorrIm[i] / mag : 0.0;
                coss[i] = mag > 0 ? fundCorrRe[i] / mag : 0.0;
            }
            double[] sinsSorted = Arrays.copyOf(sins, bestLen);
            double[] cossSorted = Arrays.copyOf(coss, bestLen);
            Arrays.sort(sinsSorted);
            Arrays.sort(cossSorted);
            double medSin = sinsSorted[bestLen / 2];
            double medCos = cossSorted[bestLen / 2];
            double medianPhase = Math.atan2(medSin, medCos);

            int phaseRejected = 0;
            double maxDeviationDeg = 0.0;
            for (int i = 0; i < bestLen; i++) {
                double phi = Math.atan2(fundCorrIm[i], fundCorrRe[i]);
                double dev = phi - medianPhase;
                while (dev >  Math.PI) dev -= 2.0 * Math.PI;
                while (dev < -Math.PI) dev += 2.0 * Math.PI;
                double devAbs = Math.abs(dev);
                if (devAbs > Math.toRadians(maxDeviationDeg)) maxDeviationDeg = Math.toDegrees(devAbs);
                if (devAbs > PHASE_COHERENCE_THRESHOLD_RAD) {
                    // Re-FFT the rejected frame and subtract its corrected spectrum
                    int f    = bestStart + i;
                    int base = f * step;
                    for (int n = 0; n < fftSize; n++) {
                        frameRe[n] = samples[base + n] * window[n];
                        frameIm[n] = 0.0;
                    }
                    fft(frameRe, frameIm);
                    double baseAngle  = -2.0 * Math.PI * (long) f * step * kFractional
                                        / ((double) fftSize * intFundBinRounded);
                    double cosBase = Math.cos(baseAngle);
                    double sinBase = Math.sin(baseAngle);
                    double corrRe = 1.0, corrIm = 0.0;
                    for (int k = 0; k < fftSize; k++) {
                        sumRe[k] -= frameRe[k] * corrRe - frameIm[k] * corrIm;
                        sumIm[k] -= frameRe[k] * corrIm + frameIm[k] * corrRe;
                        double nextCorrRe = corrRe * cosBase - corrIm * sinBase;
                        corrIm            = corrRe * sinBase + corrIm * cosBase;
                        corrRe            = nextCorrRe;
                    }
                    phaseRejected++;
                    acceptedFrames--;
                }
            }
            log.info("Phase coherence: max deviation from median = {} deg (threshold {} deg)",
                    String.format(Locale.US, "%.3f", maxDeviationDeg),
                    String.format(Locale.US, "%.3f", Math.toDegrees(PHASE_COHERENCE_THRESHOLD_RAD)));
            if (phaseRejected > 0) {
                log.warn("Phase coherence rejection: {} of {} segment frames rejected as out-of-phase",
                        phaseRejected, bestLen);
            }
            if (acceptedFrames < 2) {
                throw new IllegalStateException("Only " + acceptedFrames
                        + " frame(s) survived phase coherence check — aborting FFT.");
            }
        }

        frameCount = acceptedFrames;

        // --- Single-sided amplitude & phase spectrum -------------------------
        double   normFactor = 1.0 / ((double) fftSize * cohGain);
        double[] avgRe      = new double[halfSize + 1];
        double[] avgIm      = new double[halfSize + 1];
        double[] amplLinear = new double[halfSize + 1];
        double[] amplDbFs   = new double[halfSize + 1];
        double[] phaseDeg   = new double[halfSize + 1];

        for (int k = 0; k <= halfSize; k++) {
            double mag;
            if (coherentAveraging) {
                avgRe[k] = sumRe[k] / frameCount;
                avgIm[k] = sumIm[k] / frameCount;
                mag = Math.sqrt(avgRe[k] * avgRe[k] + avgIm[k] * avgIm[k]);
                phaseDeg[k] = Math.toDegrees(Math.atan2(avgIm[k], avgRe[k]));
            } else {
                mag = Math.sqrt(sumRe[k] / frameCount);
                avgRe[k] = mag;   // store magnitude in re for export
                avgIm[k] = 0.0;
                phaseDeg[k] = 0.0;
            }
            amplLinear[k] = (k == 0 || k == halfSize)
                    ? mag * normFactor
                    : mag * normFactor * 2.0;
            amplDbFs[k] = amplLinear[k] > 1e-15
                    ? 20.0 * Math.log10(amplLinear[k])
                    : -300.0;
        }

        // --- Fundamental (max bin, skip DC + ULF) ----------------------------
        // When the caller provided expectedFundHz, restrict the search to a
        // ±10-bin window around the expected bin — same reason as the pre-pass:
        // a deeply notched fundamental must not be lost to a louder spur.
        // Without a hint, scan above 10 Hz so DC leakage doesn't win.
        int fundBin;
        if (!Double.isNaN(expectedFundHz) && expectedFundHz > 0.0) {
            int expectedBin = (int) Math.round(expectedFundHz * fftSize / (double) sampleRate);
            int kLo = Math.max(1,        expectedBin - 10);
            int kHi = Math.min(halfSize, expectedBin + 10);
            fundBin = kLo;
            for (int k = kLo + 1; k <= kHi; k++) {
                if (amplLinear[k] > amplLinear[fundBin]) fundBin = k;
            }
        } else {
            fundBin = fundSearchMinBin;
            for (int k = fundSearchMinBin + 1; k <= halfSize; k++) {
                if (amplLinear[k] > amplLinear[fundBin]) fundBin = k;
            }
        }
        double fundHz         = fundBin * freqRes;
        double fundHzRefined  = kFractional * freqRes;   // sub-bin accurate frequency
        double fundDbFs       = amplDbFs[fundBin];
        double fundLinear     = amplLinear[fundBin];

        // refLin is the fundamental amplitude used as the *denominator* for
        // every ratio: THD, SNR, THD+N, harmonic %.  When fundRefDbV is set
        // (e.g. an external twin-T notch suppresses only H1 by a drift-prone
        // amount, so amplLinear[fundBin] is unreliable while H2..Hn stay
        // valid as measured), use the user's true fundamental as the anchor.
        // The measured fundDbFs / fundLinear are still stored verbatim into
        // Result — downstream code (filter-cal scale, chart, cal-CSV anchor)
        // depends on those reflecting the actual FFT measurement.
        // Convert dBV → dBFS-equivalent via the ADC full-scale voltage so
        // refLin lives in the same FS-relative units as amplLinear[k];
        // otherwise harmonic % / THD / SNR scale by an unwanted factor of
        // adcFsVoltageRms (≈ 1.79 for a 1.7931 V_rms FS).
        double refLin;
        if (Double.isNaN(fundRefDbV)) {
            refLin = fundLinear;
        } else {
            double fsV = CsjsoundRecorder.adcFsVoltageRms;
            double trueDbFs = fsV > 0.0 ? fundRefDbV - 20.0 * Math.log10(fsV) : fundRefDbV;
            refLin = Math.pow(10.0, trueDbFs / 20.0);
        }

        if (Double.isNaN(fundRefDbV)) {
            log.info("Fundamental: bin={}, freq={} Hz, refined={} Hz, level={} dBFS",
                    fundBin,
                    String.format(Locale.US, "%.3f", fundHz),
                    String.format(Locale.US, "%.6f", fundHzRefined),
                    String.format(Locale.US, "%.3f", fundDbFs));
        } else {
            log.info("Fundamental: bin={}, freq={} Hz, refined={} Hz, measured={} dBFS, ref={} dBV",
                    fundBin,
                    String.format(Locale.US, "%.3f", fundHz),
                    String.format(Locale.US, "%.6f", fundHzRefined),
                    String.format(Locale.US, "%.3f", fundDbFs),
                    String.format(Locale.US, "%.3f", fundRefDbV));
        }

        // --- Harmonics -------------------------------------------------------
        int[]    hBins = new int[harmonicCount];
        double[] hHz   = new double[harmonicCount];
        double[] hDbFs = new double[harmonicCount];
        double[] hPct  = new double[harmonicCount];

        for (int h = 0; h < harmonicCount; h++) {
            int hNum   = h + 2;
            // Use refined (sub-bin) fundamental to compute nominal harmonic bin —
            // avoids integer rounding error that compounds at higher harmonics.
            int nomBin = (int) Math.round(kFractional * hNum);
            if (nomBin > halfSize) {
                hBins[h] = -1;
                hHz[h]   = fundHzRefined * hNum;
                hDbFs[h] = -300.0;
                hPct[h]  = 0.0;
                continue;
            }
            // Search ±(hNum/2 + 2) bins to account for accumulated bin offset
            // and window main-lobe width at higher harmonics.
            int searchRadius = hNum / 2 + 2;
            int peak = nomBin;
            for (int k = Math.max(1, nomBin - searchRadius);
                     k <= Math.min(halfSize, nomBin + searchRadius); k++) {
                if (amplLinear[k] > amplLinear[peak]) {
                    peak = k;
                }
            }
            hBins[h] = peak;
            hHz[h]   = fundHzRefined * hNum;
            hDbFs[h] = amplDbFs[peak];
            hPct[h]  = refLin > 0 ? amplLinear[peak] / refLin * 100.0 : 0.0;
        }

        double snrLo = snrFreqMin > 0.0 ? snrFreqMin : 0.0;
        double snrHi = snrFreqMax > 0.0 ? snrFreqMax : Double.MAX_VALUE;

        // --- THD — H2..H9 (max 8 harmonics) that fall within dist range ------
        double harmPowerSum = 0.0;
        for (int h = 0; h < Math.min(harmonicCount, 8); h++) {
            if (hBins[h] > 0) {
                double harmFreq = hHz[h];
                if (harmFreq >= snrLo && harmFreq <= snrHi) {
                    double a = amplLinear[hBins[h]];
                    harmPowerSum += a * a;
                }
            }
        }
        double thdPct = refLin > 0
                ? Math.sqrt(harmPowerSum) / refLin * 100.0
                : 0.0;
        double thdDb = thdPct > 0
                ? 20.0 * Math.log10(thdPct / 100.0)
                : -300.0;

        // --- Signal-bin mask -------------------------------------------------
        // isSignalBin: fundamental + all harmonics + dynamic leakage zones.
        //              Excluded from both the noise-floor median and the SNR
        //              peak-spur search, so harmonics do not appear in SNR.
        final int EXCL_BINS = 4;
        boolean[] isSignalBin = new boolean[halfSize + 1];
        for (int d = -EXCL_BINS; d <= EXCL_BINS; d++) {
            int bin = fundBin + d;
            if (bin >= 0 && bin <= halfSize) {
                isSignalBin[bin] = true;
            }
        }
        for (int h = 0; h < harmonicCount; h++) {
            if (hBins[h] > 0) {
                for (int d = -EXCL_BINS; d <= EXCL_BINS; d++) {
                    int bin = hBins[h] + d;
                    if (bin >= 0 && bin <= halfSize) {
                        isSignalBin[bin] = true;
                    }
                }
            }
        }

        // --- SNR — integrated noise over the measurement band ----------------
        // Fundamental and all harmonics are excluded via isSignalBin.
        // SNR = signal_power / sum(noise_bins_in_range) → bandwidth-dependent.

        // Pass 1 — two medians in one scan:
        //   globalMedianNoisePow : full spectrum (no range limit) — used only for the
        //                          dynamic fundamental exclusion walk, so it is never
        //                          contaminated by leakage bins inside a narrow range.
        //   medianNoisePow       : range-restricted — used for spike threshold / SNR.
        int candidateCount = 0;
        int globalCount    = 0;
        for (int k = 1; k <= halfSize; k++) {
            double freq = k * freqRes;
            if (!isSignalBin[k]) {
                globalCount++;
                if (freq >= snrLo && freq <= snrHi) candidateCount++;
            }
        }
        double[] candidatePow = new double[candidateCount];
        double[] globalPow    = new double[globalCount];
        int ci = 0, gi = 0;
        for (int k = 1; k <= halfSize; k++) {
            double freq = k * freqRes;
            if (!isSignalBin[k]) {
                double pow = amplLinear[k] * amplLinear[k];
                globalPow[gi++] = pow;
                if (freq >= snrLo && freq <= snrHi) candidatePow[ci++] = pow;
            }
        }
        Arrays.sort(candidatePow);
        Arrays.sort(globalPow);
        double medianNoisePow       = candidateCount > 0 ? candidatePow[candidateCount / 2]      : 0.0;
        // 10th-percentile of the full-spectrum powers: closer to the true quantization
        // noise floor than the median, which is pulled up by non-harmonic spurs.
        // Used as the walk threshold so the fundamental leakage zone is extended to
        // where the window sidelobe drops to the actual noise floor, not to a
        // spur-inflated level that would leave leakage bins in the search.
        double globalMedianNoisePow = globalCount    > 0 ? globalPow[globalCount / 10]      : 0.0;

        // Inter-pass — dynamic fundamental exclusion at noise floor level.
        // Uses globalMedianNoisePow so the exclusion width is independent of the
        // chosen SNR frequency range (narrow range bins near the fundamental are
        // contaminated by window leakage and would give a falsely short walk).
        int dynWidthBins = EXCL_BINS;
        if (globalMedianNoisePow > 0) {
            int dynLo = fundBin, dynHi = fundBin;
            while (dynLo > 1
                    && amplLinear[dynLo - 1] * amplLinear[dynLo - 1] > globalMedianNoisePow) {
                dynLo--;
            }
            while (dynHi < halfSize - 1
                    && amplLinear[dynHi + 1] * amplLinear[dynHi + 1] > globalMedianNoisePow) {
                dynHi++;
            }
            dynWidthBins = Math.max(fundBin - dynLo, dynHi - fundBin);
            if (dynWidthBins > EXCL_BINS) {
                log.info("Fundamental dynamic exclusion: +/-{} bins ({} Hz each side)",
                        dynWidthBins, String.format(Locale.US, "%.2f", dynWidthBins * freqRes));
                for (int k = dynLo; k <= dynHi; k++) {
                    isSignalBin[k] = true;
                }

                // Pass 2 — refined range-restricted median with updated exclusion
                candidateCount = 0;
                for (int k = 1; k <= halfSize; k++) {
                    double freq = k * freqRes;
                    if (!isSignalBin[k] && freq >= snrLo && freq <= snrHi) {
                        candidateCount++;
                    }
                }
                candidatePow = new double[candidateCount];
                ci = 0;
                for (int k = 1; k <= halfSize; k++) {
                    double freq = k * freqRes;
                    if (!isSignalBin[k] && freq >= snrLo && freq <= snrHi) {
                        candidatePow[ci++] = amplLinear[k] * amplLinear[k];
                    }
                }
                Arrays.sort(candidatePow);
                medianNoisePow = candidateCount > 0 ? candidatePow[candidateCount / 2] : medianNoisePow;
            }
        }

        // Phase-noise exclusion: scan a wide zone around the fundamental and
        // mark any bin above the refined noise-floor median as signal.  The
        // contiguous dynamic walk above terminates on the first sub-floor
        // dip, so isolated phase-noise bumps beyond it would otherwise be
        // counted toward SNR.  Zone is capped at ±fundBin/2 so it cannot
        // reach H2 at 2·fundBin.
        if (medianNoisePow > 0) {
            int phaseLo = Math.max(1, fundBin - fundBin / 2);
            int phaseHi = Math.min(halfSize, fundBin + fundBin / 2);
            for (int k = phaseLo; k <= phaseHi; k++) {
                if (!isSignalBin[k] && amplLinear[k] * amplLinear[k] > medianNoisePow) {
                    isSignalBin[k] = true;
                }
            }
        }

        // SNR: signal RMS² / total unweighted noise power, integrated over
        // every non-signal bin in the SNR range.  Harmonics are excluded via
        // isSignalBin; the dynamic walk above keeps window leakage from the
        // fundamental out of the noise sum.  Signal² is refLin² so an
        // external twin-T notch on H1 cannot deflate the numerator.
        double noisePower = 0.0;
        for (int k = 1; k <= halfSize; k++) {
            double freq = k * freqRes;
            if (!isSignalBin[k] && freq >= snrLo && freq <= snrHi) {
                double pow = amplLinear[k] * amplLinear[k];
                noisePower += pow;
            }
        }
        double snrDb;
        if (noisePower <= 0) {
            log.warn("SNR: no non-signal bins remain in measurement range");
            snrDb = 300.0;
        } else {
            snrDb = 10.0 * Math.log10((refLin * refLin) / noisePower);
        }

        // SINAD: signal RMS² / (noise + distortion) — denominator includes both
        // the integrated noise sum AND the in-band harmonic power, so ENOB
        // = (SINAD − 1.76)/6.02 reflects every non-fundamental contribution.
        double sinadDenom = noisePower + harmPowerSum;
        double sinadDb    = sinadDenom > 0
                ? 10.0 * Math.log10((refLin * refLin) / sinadDenom)
                : 300.0;
        // Average noise floor: median of clean bins converted to dBFS
        double avgNoiseFloorDbFs = medianNoisePow > 0
                ? 20.0 * Math.log10(Math.sqrt(medianNoisePow))
                : fundDbFs - 300.0;

        // THD+N is the reciprocal of SINAD: (noise + distortion) / signal,
        // expressed in dB → −sinadDb.  Same band, same numerator definition.
        double thdNDb = -sinadDb;

        if (logSummary) {
            log.info("THD   : {}%  ({} dB)",
                    String.format(Locale.US, "%.8f", thdPct),
                    String.format(Locale.US, "%.2f", thdDb));
            log.info("THD+N (A-weighted): {} dB", String.format(Locale.US, "%.2f", thdNDb));
            if (snrFreqMin > 0.0 || snrFreqMax > 0.0) {
                log.info("SNR   : {} dB  (range: {}-{} Hz)",
                        String.format(Locale.US, "%.2f", snrDb),
                        String.format(Locale.US, "%.0f", snrLo),
                        String.format(Locale.US, "%.0f", snrHi));
            } else {
                log.info("SNR   : {} dB", String.format(Locale.US, "%.2f", snrDb));
            }
        }

        Result result = new Result(
                fftSize, sampleRate, frameCount, freqRes,
                windowType, overlap,
                amplDbFs, phaseDeg, avgRe, avgIm,
                fundBin, fundHz, fundHzRefined, fundDbFs, fundLinear,
                harmonicCount, hBins, hHz, hDbFs, hPct,
                thdPct, thdDb, thdNDb, snrDb,
                snrFreqMin, snrFreqMax, coherentAveraging,
                noisePower, noisePower, fundRefDbV, avgNoiseFloorDbFs,
                dynWidthBins * freqRes);
        result.sinadDb = sinadDb;
        return result;
    }

    /**
     * Recomputes the fundamental level, harmonic table, THD, THD+N, SNR, and noise
     * statistics from the (possibly mutated) {@code amplitudeDbFs}/{@code re}/{@code im}
     * arrays in {@code r}.  Use after any post-processing step that mutates the
     * spectrum (filter de-embedding, ADC correction) so all derived fields stay
     * consistent with the corrected bins.
     *
     * <p>The caller is responsible for keeping {@code amplitudeDbFs[]} in sync
     * with {@code re}/{@code im} as it mutates bins; this method trusts
     * {@code amplitudeDbFs[]} as the source of truth for linear amplitude
     * (because {@code re}/{@code im} are unscaled FFT outputs — the
     * analyzer-side normFactor and single-sided ×2 are folded into
     * {@code amplitudeDbFs}, not into {@code re}/{@code im}).
     *
     * <p>{@code fundamentalBin} and {@code harmonicBins[]} are kept unchanged —
     * smooth per-bin scaling does not move peaks, so the originally identified
     * bin positions remain valid.
     *
     * <p>Logic mirrors the stats portion of {@link #analyze}; keep both in sync.
     */
    public void recomputeStats(Result r) {
        int    halfSize = r.fftSize / 2;
        double freqRes  = r.freqResolution;
        double snrLo    = r.snrFreqMin > 0.0 ? r.snrFreqMin : 0.0;
        double snrHi    = r.snrFreqMax > 0.0 ? r.snrFreqMax : Double.MAX_VALUE;

        // Derive properly-scaled linear amplitudes from amplitudeDbFs.
        double[] amplLinear = new double[halfSize + 1];
        for (int k = 0; k <= halfSize; k++) {
            amplLinear[k] = r.amplitudeDbFs[k] > -290.0
                    ? Math.pow(10.0, r.amplitudeDbFs[k] / 20.0)
                    : 0.0;
        }

        // --- Fundamental ----------------------------------------------------
        int    fundBin    = r.fundamentalBin;
        double fundLinear = amplLinear[fundBin];
        double fundDbFs   = r.amplitudeDbFs[fundBin];
        r.fundamentalLinear = fundLinear;
        r.fundamentalDbFs   = fundDbFs;
        // Mirror analyze(): when --fund-v / --fund-dbv was provided, use it
        // as the ratio denominator (THD / SNR / THD+N / H%); the measured
        // fundamental scalars above stay verbatim so downstream cal/chart
        // logic keeps working.  Read fundamentalTrueDbV (not fundRefDbV)
        // because the cal CSV overwrites fundRefDbV with a notched-fundamental
        // anchor before recomputeStats runs in iterative paths.  Convert
        // dBV → dBFS via adcFsVoltageRms so refLin is in the same FS-units
        // as amplLinear[].
        double refLin;
        if (Double.isNaN(r.fundamentalTrueDbV)) {
            refLin = fundLinear;
        } else {
            double fsV = CsjsoundRecorder.adcFsVoltageRms;
            double trueDbFs = fsV > 0.0
                    ? r.fundamentalTrueDbV - 20.0 * Math.log10(fsV)
                    : r.fundamentalTrueDbV;
            refLin = Math.pow(10.0, trueDbFs / 20.0);
        }

        // --- Harmonic table -------------------------------------------------
        int harmonicCount = r.harmonicCount;
        for (int h = 0; h < harmonicCount; h++) {
            int bin = r.harmonicBins[h];
            if (bin <= 0) continue;
            r.harmonicDbFs[h] = r.amplitudeDbFs[bin];
            r.harmonicPct[h]  = refLin > 0 ? amplLinear[bin] / refLin * 100.0 : 0.0;
        }

        // --- THD (H2..H9 within SNR range) ---------------------------------
        double harmPowerSum = 0.0;
        for (int h = 0; h < Math.min(harmonicCount, 8); h++) {
            int hb = r.harmonicBins[h];
            if (hb > 0) {
                double freq = r.harmonicHz[h];
                if (freq >= snrLo && freq <= snrHi) {
                    double a = amplLinear[hb];
                    harmPowerSum += a * a;
                }
            }
        }
        r.thdPct = refLin > 0 ? Math.sqrt(harmPowerSum) / refLin * 100.0 : 0.0;
        r.thdDb  = r.thdPct > 0  ? 20.0 * Math.log10(r.thdPct / 100.0)        : -300.0;

        // --- Signal-bin mask: fundamental + every harmonic +/- EXCL_BINS ---
        final int EXCL_BINS = 4;
        boolean[] isSignalBin = new boolean[halfSize + 1];
        for (int d = -EXCL_BINS; d <= EXCL_BINS; d++) {
            int bin = fundBin + d;
            if (bin >= 0 && bin <= halfSize) isSignalBin[bin] = true;
        }
        for (int h = 0; h < harmonicCount; h++) {
            int hb = r.harmonicBins[h];
            if (hb > 0) {
                for (int d = -EXCL_BINS; d <= EXCL_BINS; d++) {
                    int bin = hb + d;
                    if (bin >= 0 && bin <= halfSize) isSignalBin[bin] = true;
                }
            }
        }

        // --- Noise medians: range-restricted + global (10th percentile) ---
        int candidateCount = 0, globalCount = 0;
        for (int k = 1; k <= halfSize; k++) {
            double freq = k * freqRes;
            if (!isSignalBin[k]) {
                globalCount++;
                if (freq >= snrLo && freq <= snrHi) candidateCount++;
            }
        }
        double[] candidatePow = new double[candidateCount];
        double[] globalPow    = new double[globalCount];
        int ci = 0, gi = 0;
        for (int k = 1; k <= halfSize; k++) {
            double freq = k * freqRes;
            if (!isSignalBin[k]) {
                double pow = amplLinear[k] * amplLinear[k];
                globalPow[gi++] = pow;
                if (freq >= snrLo && freq <= snrHi) candidatePow[ci++] = pow;
            }
        }
        Arrays.sort(candidatePow);
        Arrays.sort(globalPow);
        double medianNoisePow       = candidateCount > 0 ? candidatePow[candidateCount / 2] : 0.0;
        double globalMedianNoisePow = globalCount    > 0 ? globalPow[globalCount / 10]      : 0.0;

        // --- Dynamic fundamental exclusion walk ----------------------------
        int dynWidthBins = EXCL_BINS;
        if (globalMedianNoisePow > 0) {
            int dynLo = fundBin, dynHi = fundBin;
            while (dynLo > 1
                    && amplLinear[dynLo - 1] * amplLinear[dynLo - 1] > globalMedianNoisePow) {
                dynLo--;
            }
            while (dynHi < halfSize - 1
                    && amplLinear[dynHi + 1] * amplLinear[dynHi + 1] > globalMedianNoisePow) {
                dynHi++;
            }
            dynWidthBins = Math.max(fundBin - dynLo, dynHi - fundBin);
            if (dynWidthBins > EXCL_BINS) {
                for (int k = dynLo; k <= dynHi; k++) isSignalBin[k] = true;

                candidateCount = 0;
                for (int k = 1; k <= halfSize; k++) {
                    double freq = k * freqRes;
                    if (!isSignalBin[k] && freq >= snrLo && freq <= snrHi) candidateCount++;
                }
                candidatePow = new double[candidateCount];
                ci = 0;
                for (int k = 1; k <= halfSize; k++) {
                    double freq = k * freqRes;
                    if (!isSignalBin[k] && freq >= snrLo && freq <= snrHi) {
                        candidatePow[ci++] = amplLinear[k] * amplLinear[k];
                    }
                }
                Arrays.sort(candidatePow);
                medianNoisePow = candidateCount > 0 ? candidatePow[candidateCount / 2] : medianNoisePow;
            }
        }

        // Phase-noise exclusion (mirrors analyze()): drop bins above the
        // noise-floor median within ±fundBin/2 of the fundamental.
        if (medianNoisePow > 0) {
            int phaseLo = Math.max(1, fundBin - fundBin / 2);
            int phaseHi = Math.min(halfSize, fundBin + fundBin / 2);
            for (int k = phaseLo; k <= phaseHi; k++) {
                if (!isSignalBin[k] && amplLinear[k] * amplLinear[k] > medianNoisePow) {
                    isSignalBin[k] = true;
                }
            }
        }

        // --- SNR (signal RMS² / total unweighted noise power) ---------------
        double noisePower = 0.0;
        for (int k = 1; k <= halfSize; k++) {
            double freq = k * freqRes;
            if (!isSignalBin[k] && freq >= snrLo && freq <= snrHi) {
                double pow = amplLinear[k] * amplLinear[k];
                noisePower += pow;
            }
        }
        r.noisePower        = noisePower;
        r.snrDb             = noisePower <= 0
                ? 300.0
                : 10.0 * Math.log10((refLin * refLin) / noisePower);
        // SINAD denominator = noise + in-band harmonic power (basis for ENOB).
        double sinadDenom = noisePower + harmPowerSum;
        r.sinadDb = sinadDenom > 0
                ? 10.0 * Math.log10((refLin * refLin) / sinadDenom)
                : 300.0;
        r.avgNoiseFloorDbFs = medianNoisePow > 0
                ? 20.0 * Math.log10(Math.sqrt(medianNoisePow))
                : fundDbFs - 300.0;

        // THD+N is the reciprocal of SINAD: −sinadDb in dB.
        r.awNoisePower = noisePower;
        r.thdNDb       = -r.sinadDb;
    }

    // =========================================================================
    // Window functions
    // =========================================================================

    /**
     * Builds a normalized analysis window of length {@code N} for the given type.
     * All windows use (N−1) symmetric normalization except Flat-top which uses
     * periodic (N) normalization for optimal amplitude accuracy.
     */
    double[] buildWindow(int N, WindowType type) {
        double[] w = new double[N];
        switch (type) {
            case RECTANGULAR:
                Arrays.fill(w, 1.0);
                break;

            case HANN:
                for (int n = 0; n < N; n++) {
                    w[n] = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * n / (N - 1)));
                }
                break;

            case BLACKMAN_HARRIS_4: {
                // Harris 1978 "minimum 4-term Blackman-Harris"
                double a0 = 0.35875, a1 = 0.48829, a2 = 0.14128, a3 = 0.01168;
                for (int n = 0; n < N; n++) {
                    double t = 2.0 * Math.PI * n / (N - 1);
                    w[n] = a0 - a1 * Math.cos(t) + a2 * Math.cos(2 * t) - a3 * Math.cos(3 * t);
                }
                break;
            }

            case BLACKMAN_HARRIS_7: {
                // Nuttall 1981 7-term Blackman-Harris
                double[] a = {
                    0.2712203606, 0.4334446123, 0.2180041184, 0.0657853433,
                    0.0107618673, 0.0007700127, 0.0000136547
                };
                for (int n = 0; n < N; n++) {
                    double t   = 2.0 * Math.PI * n / (N - 1);
                    double val = 0.0;
                    for (int k = 0; k < a.length; k++) {
                        val += (k % 2 == 0 ? 1.0 : -1.0) * a[k] * Math.cos(k * t);
                    }
                    w[n] = val;
                }
                break;
            }

            case FLAT_TOP: {
                // SRS SR785 5-term flat-top (periodic normalization, N)
                double a0 = 0.21557895, a1 = 0.41663158, a2 = 0.27726316,
                       a3 = 0.08357895, a4 = 0.00694737;
                for (int n = 0; n < N; n++) {
                    double t = 2.0 * Math.PI * n / N;
                    w[n] = a0 - a1 * Math.cos(t) + a2 * Math.cos(2 * t)
                              - a3 * Math.cos(3 * t) + a4 * Math.cos(4 * t);
                }
                break;
            }

            case DOLPH_CHEBYSHEV_150:
                return buildChebyshevWindow(N, 150.0);

            case DOLPH_CHEBYSHEV_200:
                return buildChebyshevWindow(N, 200.0);

            default:
                throw new IllegalArgumentException("Unknown window type: " + type);
        }
        return w;
    }

    /**
     * Builds a Dolph-Chebyshev window via FFT-based IDFT.
     *
     * <ol>
     *   <li>Compute x0 = cosh(acosh(10^(atten/20)) / (N−1))</li>
     *   <li>For k = 0..N−1: W[k] = T_{N−1}(x0 · cos(πk/N)), symmetrically filled</li>
     *   <li>IFFT(W) / N gives the time-domain window</li>
     *   <li>fftshift by N/2 centers the main lobe</li>
     *   <li>Normalize to max = 1</li>
     * </ol>
     */
    double[] buildChebyshevWindow(int N, double attenDb) {
        double beta = Math.pow(10.0, attenDb / 20.0);
        double x0   = Math.cosh(acosh(beta) / (N - 1));

        double[] wRe = new double[N];
        double[] wIm = new double[N];

        // Compute W[k] = T_{N-1}(x0 * cos(π*k/N)) for k=0..N/2, symmetric fill
        for (int k = 0; k <= N / 2; k++) {
            double val = chebyshevT(N - 1, x0 * Math.cos(Math.PI * k / N));
            wRe[k] = val;
            if (k > 0 && k < N - k) {
                wRe[N - k] = val;
            }
        }

        // IFFT via FFT conjugate: IFFT(W) = conj(FFT(conj(W))) / N
        // Since W is real, conj(W) = W, so IFFT = FFT / N
        fft(wRe, wIm);
        for (int i = 0; i < N; i++) {
            wRe[i] /= N;
        }

        // fftshift: rotate by N/2 to center the main lobe
        double[] window = new double[N];
        int half = N / 2;
        for (int i = 0; i < N; i++) {
            window[(i + half) % N] = wRe[i];
        }

        // Normalize to max = 1
        double max = 0.0;
        for (double v : window) {
            if (v > max) {
                max = v;
            }
        }
        if (max > 0.0) {
            for (int i = 0; i < N; i++) {
                window[i] /= max;
            }
        }
        return window;
    }

    /** Chebyshev polynomial of the first kind T_n(x). */
    private double chebyshevT(int n, double x) {
        if (x > 1.0) {
            return Math.cosh(n * acosh(x));
        } else if (x < -1.0) {
            return (n % 2 == 0 ? 1.0 : -1.0) * Math.cosh(n * acosh(-x));
        } else {
            return Math.cos(n * Math.acos(x));
        }
    }

    /** Inverse hyperbolic cosine: acosh(x) = ln(x + √(x²−1)), x ≥ 1. */
    private double acosh(double x) {
        return Math.log(x + Math.sqrt(x * x - 1.0));
    }

    /**
     * Parabolic interpolation on log-power spectrum to refine a peak bin to a
     * fractional bin index.  Uses the three-point formula:
     * <pre>  δ = 0.5 · (α − γ) / (α − 2β + γ)</pre>
     * where α, β, γ are the log-power at bins (peakBin−1), peakBin, (peakBin+1).
     *
     * @return fractional bin index (peakBin + δ)
     */
    private double parabolicBinInterp(double[] re, double[] im,
                                             int peakBin, int fftSize) {
        int halfSize = fftSize / 2;
        int lo = Math.max(1, peakBin - 1);
        int hi = Math.min(halfSize, peakBin + 1);
        double pLo   = Math.log(re[lo] * re[lo] + im[lo] * im[lo] + 1e-30);
        double pMid  = Math.log(re[peakBin] * re[peakBin] + im[peakBin] * im[peakBin] + 1e-30);
        double pHi   = Math.log(re[hi] * re[hi] + im[hi] * im[hi] + 1e-30);
        double denom = pLo - 2.0 * pMid + pHi;
        if (Math.abs(denom) < 1e-15) {
            return peakBin;
        }
        double delta = 0.5 * (pLo - pHi) / denom;
        return peakBin + delta;
    }

    // =========================================================================
    // FFT — Cooley-Tukey radix-2, decimation-in-time, in-place
    // =========================================================================

    public void fft(double[] re, double[] im) {
        int n = re.length;

        // Bit-reversal permutation
        int j = 0;
        for (int i = 1; i < n; i++) {
            int bit = n >> 1;
            while ((j & bit) != 0) {
                j ^= bit;
                bit >>= 1;
            }
            j ^= bit;
            if (i < j) {
                double t;
                t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }

        // Butterfly stages
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            double wRe = Math.cos(ang);
            double wIm = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double curRe = 1.0, curIm = 0.0;
                for (int k = 0; k < len / 2; k++) {
                    int    p  = i + k;
                    int    q  = i + k + len / 2;
                    double uR = re[p];
                    double uI = im[p];
                    double vR = re[q] * curRe - im[q] * curIm;
                    double vI = re[q] * curIm + im[q] * curRe;
                    re[p] = uR + vR;
                    im[p] = uI + vI;
                    re[q] = uR - vR;
                    im[q] = uI - vI;
                    double nextRe = curRe * wRe - curIm * wIm;
                    curIm         = curRe * wIm + curIm * wRe;
                    curRe         = nextRe;
                }
            }
        }
    }

    // =========================================================================
    // Harmonic subtraction
    // =========================================================================

    /**
     * Loads a harmonics CSV file (produced by {@link #exportHarmonicsCsv}) and
     * subtracts the reconstructed sinusoids (H2 and above) from {@code samples}
     * in-place.  The fundamental (H1) is always skipped.
     *
     * <p>Two phase-source modes:
     * <ul>
     *   <li>{@code useReIm=false} (default): amplitude from {@code amplitude_dbfs},
     *       phase from {@code phase_deg} column (4 decimal places).</li>
     *   <li>{@code useReIm=true}: amplitude from {@code amplitude_dbfs},
     *       phase derived via {@code atan2(im, re)} — full double precision.</li>
     * </ul>
     *
     * @param samples    signal buffer to modify in-place
     * @param sampleRate sample rate in Hz
     * @param csvFile    path to the harmonics CSV file
     * @param useReIm    true = derive phase from re/im columns; false = use phase_deg column
     */
    public void subtractHarmonicsCsv(float[] samples, int sampleRate,
                                             String csvFile, boolean useReIm) throws IOException {
        // Parse rows: skip header, skip H1 (fundamental), skip footers
        // Stored as [freqHz, ampLinear, cos(phase), sin(phase)]
        List<double[]> harmonics = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(csvFile))) {
            br.readLine();   // header
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || !Character.isDigit(line.charAt(0))) {
                    continue;   // blank line or footer (THD, SNR, …)
                }
                String[] cols = line.split(";");
                if (cols.length < 5) continue;
                int harmonicIndex = Integer.parseInt(cols[0].trim());
                if (harmonicIndex == 1) {
                    continue;   // skip fundamental
                }
                double freqHz        = Double.parseDouble(cols[1].trim().replace(',', '.'));
                double amplitudeDbFs = Double.parseDouble(cols[2].trim().replace(',', '.'));
                double ampLinear     = Math.pow(10.0, amplitudeDbFs / 20.0);
                double cosPhase, sinPhase;
                if (useReIm && cols.length >= 7) {
                    double re = Double.parseDouble(cols[5].trim().replace(',', '.'));
                    double im = Double.parseDouble(cols[6].trim().replace(',', '.'));
                    double mag = Math.sqrt(re * re + im * im);
                    if (mag > 0) {
                        cosPhase = re / mag;
                        sinPhase = im / mag;
                    } else {
                        cosPhase = 1.0;
                        sinPhase = 0.0;
                    }
                } else {
                    double phaseRad = Math.toRadians(
                            Double.parseDouble(cols[4].trim().replace(',', '.')));
                    cosPhase = Math.cos(phaseRad);
                    sinPhase = Math.sin(phaseRad);
                }
                harmonics.add(new double[]{ freqHz, ampLinear, cosPhase, sinPhase });
            }
        }
        log.info("Subtracting {} harmonic(s) (H2+) using {} from: {}",
                harmonics.size(), useReIm ? "re/im" : "amp+phase", csvFile);

        for (double[] h : harmonics) {
            double freqHz    = h[0];
            double ampLinear = h[1];
            double omega     = 2.0 * Math.PI * freqHz / sampleRate;

            double cosOmega = Math.cos(omega);
            double sinOmega = Math.sin(omega);
            // Start phasor at n=0: exp(j·phase) = cosPhase + j·sinPhase
            double curRe = h[2];   // cos(phase)
            double curIm = h[3];   // sin(phase)

            for (int n = 0; n < samples.length; n++) {
                // A·cos(ω·n + φ) = A · Re(exp(j·(ω·n+φ))) = A · curRe
                samples[n] -= (float) (ampLinear * curRe);
                double nextRe = curRe * cosOmega - curIm * sinOmega;
                curIm         = curRe * sinOmega + curIm * cosOmega;
                curRe         = nextRe;
            }
            log.debug("Subtracted H at {} Hz  {} dBFS", freqHz,
                    String.format(Locale.US, "%.4f", 20.0 * Math.log10(ampLinear)));
        }
    }

    // =========================================================================
    // CSV export
    // =========================================================================

    public String exportFftCsv(Result r, String directory) throws IOException {
        String ts      = ts();
        File   outFile = new File(directory, "fft_spectrum_" + ts + ".csv");
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(outFile)))) {
            pw.println("frequency_hz;amplitude_dbfs;phase_deg;re;im");
            for (int k = 0; k <= r.fftSize / 2; k++) {
                pw.printf(Locale.GERMAN, "%.6f;%.6f;%.4f;%.10e;%.10e%n",
                        k * r.freqResolution,
                        r.amplitudeDbFs[k],
                        r.phaseDeg[k],
                        r.re[k],
                        r.im[k]);
            }
        }
        log.info("FFT spectrum CSV saved: {}", outFile.getAbsolutePath());
        return outFile.getAbsolutePath();
    }

    public String exportHarmonicsCsv(Result r, String directory) throws IOException {
        return exportHarmonicsCsv(r, directory, null);
    }

    public String exportHarmonicsCsv(Result r, String directory, String filePrefix) throws IOException {
        File outFile = (filePrefix != null && !filePrefix.isEmpty())
                ? new File(directory, filePrefix + ".csv")
                : new File(directory, "fft_harmonics_" + ts() + ".csv");
        try (PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(outFile)))) {
            pw.println("harmonic;frequency_hz;amplitude_dbfs;amplitude_pct;phase_deg;re;im");
            pw.printf(Locale.GERMAN, "1;%.6f;%.4f;100.000000;%.4f;%.10e;%.10e%n",
                    r.fundamentalHz, r.fundamentalDbFs,
                    r.phaseDeg[r.fundamentalBin],
                    r.re[r.fundamentalBin], r.im[r.fundamentalBin]);
            for (int h = 0; h < r.harmonicCount; h++) {
                int bin = r.harmonicBins[h];
                double phase = bin > 0 ? r.phaseDeg[bin] : 0.0;
                double re    = bin > 0 ? r.re[bin]        : 0.0;
                double im    = bin > 0 ? r.im[bin]        : 0.0;
                pw.printf(Locale.GERMAN, "%d;%.6f;%.4f;%.9f;%.4f;%.10e;%.10e%n",
                        h + 2, r.harmonicHz[h], r.harmonicDbFs[h], r.harmonicPct[h],
                        phase, re, im);
            }
            pw.println();
            pw.printf(Locale.GERMAN, "THD;;%.6f%%;%.4f dB%n",  r.thdPct,  r.thdDb);
            pw.printf(Locale.GERMAN, "THD+N (A-weighted);;;%.4f dB%n",     r.thdNDb);
            pw.printf(Locale.GERMAN, "SNR;;;%.4f dB%n",                     r.snrDb);
        }
        log.info("FFT harmonics CSV saved: {}", outFile.getAbsolutePath());
        return outFile.getAbsolutePath();
    }

    // =========================================================================
    // Chart export
    // =========================================================================

    public String exportChart(Result r, int width, int height,
                                     String directory) throws IOException {
        return exportChart(r, width, height, directory, null);
    }

    public String exportChart(Result r, int width, int height,
                                     String directory, String comment) throws IOException {
        return exportChart(r, width, height, directory, comment, false);
    }

    public String exportChart(Result r, int width, int height,
                                     String directory, String comment,
                                     boolean harmonicsSubtracted) throws IOException {
        return exportChart(r, width, height, directory, comment, harmonicsSubtracted, null);
    }

    public String exportChart(Result r, int width, int height,
                                     String directory, String comment,
                                     boolean harmonicsSubtracted, String filePrefix) throws IOException {
        return exportChart(r, width, height, directory, comment, harmonicsSubtracted, filePrefix, null);
    }

    public String exportChart(Result r, int width, int height,
                                     String directory, String comment,
                                     boolean harmonicsSubtracted, String filePrefix,
                                     Double genFreqHz) throws IOException {
        return exportChart(r, width, height, directory, comment, harmonicsSubtracted,
                filePrefix, genFreqHz, null, null, null, null);
    }

    public String exportChart(Result r, int width, int height,
                                     String directory, String comment,
                                     boolean harmonicsSubtracted, String filePrefix,
                                     Double genFreqHz,
                                     double[] overlayFreqs, double[] overlayDbFs) throws IOException {
        return exportChart(r, width, height, directory, comment, harmonicsSubtracted,
                filePrefix, genFreqHz, overlayFreqs, overlayDbFs, null, null);
    }

    /**
     * Overload that also draws an overlay series (e.g. the inverted cal filter
     * response) as a dashed green line on top of the spectrum, plus a separate
     * series of blue dots at {@code (preCorrFreqs[i], preCorrDbFs[i])} showing
     * where the fundamental and harmonic peaks sat BEFORE cal correction was
     * applied.  Pass {@code null} for either pair to suppress that layer.
     * Y values are in dBFS; they're shifted to the primary axis units (dBV or
     * dBFS) internally.
     */
    public String exportChart(Result r, int width, int height,
                                     String directory, String comment,
                                     boolean harmonicsSubtracted, String filePrefix,
                                     Double genFreqHz,
                                     double[] overlayFreqs, double[] overlayDbFs,
                                     double[] preCorrFreqs, double[] preCorrDbFs) throws IOException {
        String ts = ts();

        // When a dBV reference is set (--fund-v / --fund-dbv / --cal CSV /
        // --adc-fs-vrms), promote dBV to the primary range axis so horizontal
        // gridlines line up on round dBV values (0, −20, −40 …) instead of on
        // dBFS values that end up offset by 20·log10(adcFsVoltageRms).  All
        // plotted Y values are shifted by {@code dbFsToDbV} so they land on
        // the dBV axis at the correct position; dBFS moves to the secondary
        // axis with its own (unshifted) range.
        boolean hasDbv = !Double.isNaN(r.fundRefDbV);
        double  dbFsToDbV = hasDbv ? (r.fundRefDbV - r.fundamentalDbFs) : 0.0;
        String  primaryAxisLabel = hasDbv ? "Amplitude (dBV)" : "Amplitude (dBFS)";

        // The fundamental peak marker is drawn at the *true* user-stated dBV
        // when supplied (e.g. external twin-T notch makes the measured H1
        // unreliable); otherwise at the cal-converted measured value.
        // The spectrum-line point at fundBin is also forced to this level so
        // the line touches the marker instead of dipping to the notched value.
        double fundDisplayDbV = !Double.isNaN(r.fundamentalTrueDbV)
                ? r.fundamentalTrueDbV
                : r.fundamentalDbFs + dbFsToDbV;

        // --- Series (Y values in primary-axis units = dBV if set, else dBFS) -
        XYSeries spectrum = new XYSeries("Spectrum");
        for (int k = 1; k <= r.fftSize / 2; k++) {
            double freq = k * r.freqResolution;
            if (freq >= 1.0) {
                double y = (k == r.fundamentalBin && !Double.isNaN(r.fundamentalTrueDbV))
                        ? fundDisplayDbV
                        : r.amplitudeDbFs[k] + dbFsToDbV;
                spectrum.add(freq, y);
            }
        }

        XYSeries harmPeaks = new XYSeries("Harmonics");
        harmPeaks.add(r.fundamentalHz, fundDisplayDbV);
        for (int h = 0; h < r.harmonicCount; h++) {
            if (r.harmonicBins[h] > 0) {
                harmPeaks.add(r.harmonicHz[h], r.harmonicDbFs[h] + dbFsToDbV);
            }
        }

        XYSeriesCollection dataset = new XYSeriesCollection();
        dataset.addSeries(spectrum);
        dataset.addSeries(harmPeaks);

        boolean hasOverlay = overlayFreqs != null && overlayDbFs != null
                && overlayFreqs.length == overlayDbFs.length
                && overlayFreqs.length >= 2;
        if (hasOverlay) {
            XYSeries overlay = new XYSeries("Cal (inverted)");
            for (int i = 0; i < overlayFreqs.length; i++) {
                if (overlayFreqs[i] > 0.0) {
                    overlay.add(overlayFreqs[i], overlayDbFs[i] + dbFsToDbV);
                }
            }
            dataset.addSeries(overlay);
        }

        boolean hasPrePeaks = preCorrFreqs != null && preCorrDbFs != null
                && preCorrFreqs.length == preCorrDbFs.length
                && preCorrFreqs.length >= 1;
        if (hasPrePeaks) {
            XYSeries prePeaks = new XYSeries("Before cal");
            for (int i = 0; i < preCorrFreqs.length; i++) {
                if (preCorrFreqs[i] > 0.0) {
                    prePeaks.add(preCorrFreqs[i], preCorrDbFs[i] + dbFsToDbV);
                }
            }
            dataset.addSeries(prePeaks);
        }

        // --- Chart -----------------------------------------------------------
        JFreeChart chart = ChartFactory.createXYLineChart(
                "FFT Analysis", "Frequency (Hz)", primaryAxisLabel,
                dataset,
                PlotOrientation.VERTICAL,
                true, false, false);
        chart.getTitle().setFont(ChartStyle.CHART_TITLE_FONT);

        XYPlot plot = (XYPlot) chart.getPlot();
        plot.setBackgroundPaint(ChartStyle.PLOT_BACKGROUND);
        plot.setDomainGridlinePaint(ChartStyle.GRID_LINE_COLOR);
        plot.setRangeGridlinePaint(ChartStyle.GRID_LINE_COLOR);

        // --- Log-frequency X axis with 1-2-3-5-7×decade ticks ---------------
        final double freqMin = Math.max(1.0, r.freqResolution * 0.5);
        final double freqMax = r.sampleRate / 2.0;
        LogAxis freqAxis = new LogAxis("Frequency (Hz)") {
            @Override
            public List<NumberTick> refreshTicks(Graphics2D g2,
                                               AxisState state,
                                               Rectangle2D dataArea,
                                               RectangleEdge edge) {
                List<NumberTick> ticks = new ArrayList<>();
                double[] multipliers = {1, 2, 3, 5, 7};
                double decade = 1.0;
                while (decade <= getUpperBound() * 1.01) {
                    for (double m : multipliers) {
                        double f = decade * m;
                        if (f >= getLowerBound() && f <= getUpperBound()) {
                            String label = f >= 1000.0
                                    ? String.format(Locale.US, "%.0fk", f / 1000.0)
                                    : String.format(Locale.US, "%.0f", f);
                            ticks.add(new NumberTick(TickType.MAJOR, f, label,
                                    TextAnchor.TOP_CENTER, TextAnchor.CENTER, 0.0));
                        }
                    }
                    decade *= 10.0;
                }
                return ticks;
            }
        };
        freqAxis.setBase(10.0);
        freqAxis.setRange(freqMin, freqMax);
        freqAxis.setLabelFont(ChartStyle.AXIS_LABEL_FONT);
        freqAxis.setTickLabelFont(ChartStyle.AXIS_TICK_FONT);
        plot.setDomainAxis(freqAxis);

        // --- Dim regions outside SNR frequency range -------------------------
        if (r.snrFreqMin > 0.0 && r.snrFreqMin > freqMin) {
            IntervalMarker left = new IntervalMarker(freqMin, r.snrFreqMin);
            left.setPaint(ChartStyle.DIM_COLOR);
            left.setAlpha(ChartStyle.DIM_ALPHA);
            plot.addDomainMarker(left, Layer.FOREGROUND);
        }
        double nyquist = r.sampleRate / 2.0;
        if (r.snrFreqMax > 0.0 && r.snrFreqMax < nyquist) {
            IntervalMarker right = new IntervalMarker(r.snrFreqMax, nyquist);
            right.setPaint(ChartStyle.DIM_COLOR);
            right.setAlpha(ChartStyle.DIM_ALPHA);
            plot.addDomainMarker(right, Layer.FOREGROUND);
        }

        // --- Primary Y axis (dBV when ref is set, else dBFS) ----------------
        // Range is in whichever units the series were built with (dBFS +
        // dbFsToDbV).  TickUnit 20 produces round dBV gridlines at 0, ±20, …
        double yMaxDbFs = 10.0;
        double yMinDbFs = -220.0;
        NumberAxis yAxis = (NumberAxis) plot.getRangeAxis();
        yAxis.setRange(yMinDbFs + dbFsToDbV, yMaxDbFs + dbFsToDbV);
        yAxis.setTickUnit(new NumberTickUnit(20.0));
        yAxis.setLabelFont(ChartStyle.AXIS_LABEL_FONT);
        yAxis.setTickLabelFont(ChartStyle.AXIS_TICK_FONT);

        // --- Secondary dBFS axis, only when primary is dBV ------------------
        if (hasDbv) {
            NumberAxis yAxisDbFs = new NumberAxis("Amplitude (dBFS)");
            yAxisDbFs.setRange(yMinDbFs, yMaxDbFs);
            yAxisDbFs.setTickUnit(new NumberTickUnit(20.0));
            yAxisDbFs.setLabelFont(ChartStyle.AXIS_LABEL_FONT);
            yAxisDbFs.setTickLabelFont(ChartStyle.AXIS_TICK_FONT);
            plot.setRangeAxis(1, yAxisDbFs);
            plot.setRangeAxisLocation(1, AxisLocation.BOTTOM_OR_LEFT);
        }

        // --- Renderer series layout -----------------------------------------
        //   0            = spectrum line
        //   1            = corrected harmonic peaks (red dots)
        //   [hasOverlay] = cal overlay dashed line (green)
        //   [hasPrePeaks]= pre-correction peaks (blue dots)
        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(false, false);
        renderer.setSeriesLinesVisible(0, true);
        renderer.setSeriesShapesVisible(0, false);
        renderer.setSeriesPaint(0, ChartStyle.SPECTRUM_COLOR);
        renderer.setSeriesStroke(0, ChartStyle.SPECTRUM_STROKE);
        renderer.setSeriesLinesVisible(1, false);
        renderer.setSeriesShapesVisible(1, true);
        renderer.setSeriesPaint(1, ChartStyle.PEAK_COLOR);
        renderer.setSeriesShape(1, ChartStyle.PEAK_SHAPE);
        int seriesIdx = 2;
        if (hasOverlay) {
            renderer.setSeriesLinesVisible(seriesIdx, true);
            renderer.setSeriesShapesVisible(seriesIdx, false);
            renderer.setSeriesPaint(seriesIdx, ChartStyle.CAL_OVERLAY_COLOR);
            renderer.setSeriesStroke(seriesIdx, ChartStyle.CAL_OVERLAY_STROKE);
            seriesIdx++;
        }
        if (hasPrePeaks) {
            renderer.setSeriesLinesVisible(seriesIdx, false);
            renderer.setSeriesShapesVisible(seriesIdx, true);
            renderer.setSeriesPaint(seriesIdx, ChartStyle.PRE_PEAK_COLOR);
            renderer.setSeriesShape(seriesIdx, ChartStyle.PRE_PEAK_SHAPE);
        }
        plot.setRenderer(0, renderer);

        // --- Harmonic peak annotations ---------------------------------------
        Font annFont = ChartStyle.ANNOTATION_FONT;

        XYTextAnnotation fundLabel = new XYTextAnnotation(
                String.format(Locale.US, "H1 %.2f Hz", r.fundamentalHz),
                r.fundamentalHz,
                r.fundamentalDbFs + dbFsToDbV + ChartStyle.ANNOTATION_Y_OFFSET);
        fundLabel.setFont(annFont);
        fundLabel.setPaint(ChartStyle.FUND_ANNOTATION_COLOR);
        plot.addAnnotation(fundLabel);

        for (int h = 0; h < r.harmonicCount; h++) {
            if (r.harmonicBins[h] > 0
                    && r.harmonicDbFs[h] > yMinDbFs + ChartStyle.ANNOTATION_Y_OFFSET) {
                XYTextAnnotation ann = new XYTextAnnotation(
                        String.format(Locale.US, "H%d", h + 2),
                        r.harmonicHz[h],
                        r.harmonicDbFs[h] + dbFsToDbV + ChartStyle.ANNOTATION_Y_OFFSET);
                ann.setFont(annFont);
                ann.setPaint(ChartStyle.HARM_ANNOTATION_COLOR);
                plot.addAnnotation(ann);
            }
        }

        // --- Info table overlay (top-right corner of plot area) --------------
        // Computed via a ChartPanel overlay; here we use a custom Title subclass
        // that draws directly onto the chart image after rendering.
        // We paint it onto the BufferedImage after ChartUtils renders the chart.
        BufferedImage chartImage =
                chart.createBufferedImage(width, height);

        // ENOB = (SINAD − 1.76) / 6.02 — uses signal vs noise+distortion (the
        // standard ADC effective-bits definition), not signal/noise alone.
        double enob = (r.sinadDb - 1.76) / 6.02;

        // Real dBV of the fundamental: prefer the user-stated true value
        // (--fund-v / --fund-dbv) so the info table matches the chart's
        // fundamental peak marker; otherwise fall back to the cal-converted
        // value, then to dBFS-as-dBV.
        boolean hasRef = !Double.isNaN(r.fundRefDbV);
        double fundamentalDbV = !Double.isNaN(r.fundamentalTrueDbV)
                ? r.fundamentalTrueDbV
                : (hasRef ? r.fundRefDbV : r.fundamentalDbFs);
        // dBFS→dBV offset (same value already computed above as dbFsToDbV)

        // Noise+Distortion absolute level in dBV (A-weighted)
        double ndDbVA = r.thdNDb + fundamentalDbV;

        // Span = SNR frequency range (snrFreqMin/Max are always set: 0 and Fs/2 when not provided)
        String spanLabel = String.format(Locale.US, "Span: %.0f .. %.0f Hz",
                r.snrFreqMin, r.snrFreqMax);

        // Determine the highest harmonic number (H2..H9 cap) within dist range for the THD label
        double distLo = r.snrFreqMin > 0.0 ? r.snrFreqMin : 0.0;
        double distHi = r.snrFreqMax > 0.0 ? r.snrFreqMax : Double.MAX_VALUE;
        int thdLastHarm = 1;
        for (int h = 0; h < Math.min(r.harmonicCount, 8); h++) {
            if (r.harmonicBins[h] > 0 && r.harmonicHz[h] >= distLo && r.harmonicHz[h] <= distHi) {
                thdLastHarm = h + 2;
            }
        }
        String thdLabel = thdLastHarm >= 2
                ? String.format(Locale.US, "THD H2..%d:", thdLastHarm)
                : "THD:";

        // Build table rows: left-label, left-value, right-label, right-value
        // Row 0: header (fundamental line) — spans full width
        // Harmonics: pair them up 2 per row
        List<String[]> rows = new ArrayList<>();

        // header row: fund freq, dBFS and dBV (dBV shown only when reference is set).
        // When the user supplied --fund-v / --fund-dbv, show the back-converted
        // true dBFS (= trueDbV − dbFsToDbV) instead of the notched measurement,
        // so dBFS and dBV columns are consistent with the chart's lifted peak.
        double fundHeaderDbFs = !Double.isNaN(r.fundamentalTrueDbV)
                ? fundDisplayDbV - dbFsToDbV
                : r.fundamentalDbFs;
        String fundHeader = hasRef
                ? String.format(Locale.US, "%.2f Hz  %.2f dBFS  %.2f dBV",
                        r.fundamentalHz, fundHeaderDbFs, fundamentalDbV)
                : String.format(Locale.US, "%.2f Hz  %.2f dBFS",
                        r.fundamentalHz, fundHeaderDbFs);
        rows.add(new String[]{ fundHeader, null, null, null });

        // span row
        rows.add(new String[]{ spanLabel, null, null, null });

        // metrics rows (2-column layout)
        rows.add(new String[]{
            "N+D:", String.format(Locale.US, "%.1f dBV A", ndDbVA),
            thdLabel,
            String.format(Locale.US, "%.8f %%", r.thdPct)
        });
        rows.add(new String[]{
            "N:", String.format(Locale.US, "%.1f dBV", fundamentalDbV - r.snrDb),
            "THD+N:", String.format(Locale.US, "%.8f %%",
                    Math.pow(10.0, r.thdNDb / 20.0) * 100.0)
        });
        rows.add(new String[]{
            "SNR:", String.format(Locale.US, "%.1f dB", r.snrDb),
            "ENOB:", String.format(Locale.US, "%.1f bits", enob)
        });

        // Clock mismatch row (only when caller provided generator freq).
        // ΔF = measured − requested; ppm = 1e6·ΔF/f_gen.
        // Δosc maps the relative drift back to the master oscillator
        // (22.5792 MHz for 44.1k family, 24.576 MHz for 48k family).
        if (genFreqHz != null && genFreqHz > 0.0) {
            double delta = r.fundamentalHzRefined - genFreqHz;
            double ppm   = 1e6 * delta / genFreqHz;
            double osc;
            String oscName;
            if (r.sampleRate % 44100 == 0) {
                osc = 22.5792e6; oscName = "22.5792 MHz";
            } else if (r.sampleRate % 48000 == 0) {
                osc = 24.576e6;  oscName = "24.576 MHz";
            } else {
                osc = Double.NaN; oscName = "?";
            }
            String text;
            if (Double.isNaN(osc)) {
                text = String.format(Locale.US, "ΔF: %+.6f Hz (%+.2f ppm)", delta, ppm);
            } else {
                double oscDelta = osc * delta / genFreqHz;
                text = String.format(Locale.US,
                        "ΔF: %+.6f Hz (%+.2f ppm)  Δosc: %+.2f Hz @ %s",
                        delta, ppm, oscDelta, oscName);
            }
            rows.add(new String[]{ text, null, null, null });
        }

        // harmonic rows — pair them; each value shows dBFS and percent
        for (int h = 0; h < r.harmonicCount; h += 2) {
            String lLabel = String.format(Locale.US, "H%d:", h + 2);
            String lVal   = hasRef
                    ? String.format(Locale.US, "%.2f dBV  %.8f %%",
                            r.harmonicDbFs[h] + dbFsToDbV, r.harmonicPct[h])
                    : String.format(Locale.US, "%.2f dBFS  %.8f %%",
                            r.harmonicDbFs[h], r.harmonicPct[h]);
            String rLabel = null, rVal = null;
            if (h + 1 < r.harmonicCount) {
                rLabel = String.format(Locale.US, "H%d:", h + 3);
                rVal   = hasRef
                        ? String.format(Locale.US, "%.2f dBV  %.8f %%",
                                r.harmonicDbFs[h + 1] + dbFsToDbV, r.harmonicPct[h + 1])
                        : String.format(Locale.US, "%.2f dBFS  %.8f %%",
                                r.harmonicDbFs[h + 1], r.harmonicPct[h + 1]);
            }
            rows.add(new String[]{ lLabel, lVal, rLabel, rVal });
        }

        drawInfoTable(chartImage, rows, width, height);

        drawCornerInfo(chartImage, comment, harmonicsSubtracted, r.frameCount, width, height);

        // --- Save (from buffered image, not via ChartUtils) ------------------
        File outFile = (filePrefix != null && !filePrefix.isEmpty())
                ? new File(directory, filePrefix + ".png")
                : new File(directory, "fft_chart_" + ts + ".png");
        javax.imageio.ImageIO.write(chartImage, "PNG", outFile);
        log.info("FFT chart saved: {}", outFile.getAbsolutePath());
        return outFile.getAbsolutePath();
    }

    /**
     * Draws a semi-transparent info table in the top-right corner of the image.
     * Each row is String[4]: leftLabel, leftValue, rightLabel, rightValue.
     * If rightLabel is null the left pair spans the full width.
     */
    private void drawInfoTable(BufferedImage img,
                                      List<String[]> rows,
                                      int imgWidth, int imgHeight) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Font headerFont = ChartStyle.TABLE_HEADER_FONT;
        Font cellFont   = ChartStyle.TABLE_CELL_FONT;

        // Measure table width
        FontMetrics hmFm = g.getFontMetrics(headerFont);
        FontMetrics cmFm = g.getFontMetrics(cellFont);
        int lineH   = cmFm.getHeight() + 2;
        int pad     = ChartStyle.TABLE_PAD;

        // Measure span rows and data columns independently to avoid span rows
        // inflating the data column widths and making the box too wide.
        int spanMaxW = 0;
        int colW0 = 0, colW1 = 0, colW2 = 0, colW3 = 0;
        for (String[] row : rows) {
            if (row[1] == null) {
                spanMaxW = Math.max(spanMaxW, hmFm.stringWidth(row[0]));
            } else {
                colW0 = Math.max(colW0, cmFm.stringWidth(row[0]));
                colW1 = Math.max(colW1, cmFm.stringWidth(row[1]));
                if (row[2] != null) {
                    colW2 = Math.max(colW2, cmFm.stringWidth(row[2]));
                    colW3 = Math.max(colW3, cmFm.stringWidth(row[3]));
                }
            }
        }

        int colSep  = pad;
        int halfW   = colW0 + colSep + colW1;
        int dataW   = halfW + colSep + pad + colW2 + colSep + colW3;
        int tableW  = Math.max(dataW, spanMaxW) + pad * 2;
        int tableH  = rows.size() * lineH + pad * 2;

        int x0 = imgWidth  - tableW - ChartStyle.TABLE_MARGIN;
        int y0 = ChartStyle.TABLE_MARGIN;

        // Background
        g.setColor(ChartStyle.TABLE_BG_COLOR);
        g.fillRoundRect(x0, y0, tableW, tableH, ChartStyle.TABLE_ARC, ChartStyle.TABLE_ARC);
        g.setColor(ChartStyle.TABLE_BORDER_COLOR);
        g.drawRoundRect(x0, y0, tableW, tableH, ChartStyle.TABLE_ARC, ChartStyle.TABLE_ARC);

        int textX = x0 + pad;
        int textY = y0 + pad + cmFm.getAscent();

        for (String[] row : rows) {
            if (row[1] == null) {
                // header/span row — centered, bold
                g.setFont(headerFont);
                g.setColor(ChartStyle.TABLE_HEADER_COLOR);
                int sw = hmFm.stringWidth(row[0]);
                g.drawString(row[0], x0 + (tableW - sw) / 2, textY);
            } else {
                // two-column data row
                g.setFont(cellFont);
                // left label (right-aligned to colW0)
                g.setColor(ChartStyle.TABLE_LABEL_COLOR);
                g.drawString(row[0], textX + colW0 - cmFm.stringWidth(row[0]), textY);
                // left value (left-aligned after label)
                g.setColor(ChartStyle.TABLE_VALUE_COLOR);
                g.drawString(row[1], textX + colW0 + colSep, textY);
                if (row[2] != null) {
                    int rx = textX + halfW + pad;
                    g.setColor(ChartStyle.TABLE_LABEL_COLOR);
                    g.drawString(row[2], rx + colW2 - cmFm.stringWidth(row[2]), textY);
                    g.setColor(ChartStyle.TABLE_VALUE_COLOR);
                    g.drawString(row[3], rx + colW2 + colSep, textY);
                }
            }
            textY += lineH;
        }
        g.dispose();
    }


    /**
     * Draws a bottom-left info box with optional comment, "Harmonics compensated",
     * and average cycle count.
     */
    private void drawCornerInfo(BufferedImage img,
                                       String comment, boolean harmonicsSubtracted,
                                       int frameCount, int imgWidth, int imgHeight) {
        List<String> lines = new ArrayList<>();
        if (comment != null && !comment.isBlank()) {
            lines.add(comment);
        }
        if (harmonicsSubtracted) {
            lines.add("Harmonics compensated");
        }
        lines.add(String.format(Locale.US, "Avg cycles: %d", frameCount));

        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,  RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Font font = ChartStyle.TABLE_CELL_FONT;
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();

        int pad   = ChartStyle.CORNER_PAD;
        int lineH = fm.getHeight() + 2;
        int boxH  = lineH * lines.size() + pad * 2;
        int maxW  = lines.stream().mapToInt(fm::stringWidth).max().orElse(0);
        int boxW  = maxW + pad * 2;
        int boxX  = pad;
        int boxY  = imgHeight - boxH - pad;

        g.setColor(ChartStyle.CORNER_BG_COLOR);
        g.fillRoundRect(boxX, boxY, boxW, boxH, ChartStyle.CORNER_ARC, ChartStyle.CORNER_ARC);
        g.setColor(ChartStyle.CORNER_BORDER_COLOR);
        g.drawRoundRect(boxX, boxY, boxW, boxH, ChartStyle.CORNER_ARC, ChartStyle.CORNER_ARC);

        g.setColor(ChartStyle.CORNER_TEXT_COLOR);
        int textY = boxY + pad + fm.getAscent();
        for (String line : lines) {
            g.drawString(line, boxX + pad, textY);
            textY += lineH;
        }
        g.dispose();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private String ts() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
    }
}
