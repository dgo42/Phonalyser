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

package org.edgo.audio.measure.gui;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.edgo.audio.measure.enums.WindowType;

/**
 * Automatic pilot-tone frequency selection, run as a benchmark.
 *
 * <p>A pilot is a second generated tone whose phase the analysis reads on
 * every analysis cycle as a time-base reference beside a fundamental that
 * sits in a notch.  A frequency is admissible only while nothing the pilot
 * produces - the line itself, its harmonics, their Nyquist folds, its
 * intermodulation with the fundamental - can raise the fundamental's bin or a
 * protected harmonic's, and while nothing else can raise the pilot's own bin.
 * The rules and their reasons are stated in the pilot-tone selection plan;
 * this class is their reference implementation and measures what they cost.
 *
 * <p>Seven knock-out rules decide admissibility, four selection steps reduce
 * the admissible set to exactly one bin:
 *
 * <pre>
 *   rule 1  integer bin (the scan enumerates bins, so it holds by construction)
 *   rule 2  band: above the protected ladder's last lobe, below a third of the
 *           lower converter rate, at or below the chain's bandwidth, never the
 *           Nyquist bin
 *   rule 3  no whole number of samples per period on either converter
 *   rule 4  not within max(lobe guard, quantisation) of a value with one, two
 *           or three significant digits - a lock or a rounded display would
 *           pull such a pilot onto the round value and off its bin
 *   rule 5  no pilot harmonic, folded, inside a protected harmonic's lobe
 *   rule 6  no pilot harmonic above the first and no harmonic of the
 *           fundamental, folded, inside the pilot's own lobe
 *   rule 7  no intermodulation product of total order up to three inside
 *           either lobe
 *
 *   step 1  halfway between two harmonics of the fundamental
 *   step 2  odd bin, so the sample pattern walks the whole frame
 *   step 3  widest clearance at two significant figures
 *   step 4  the highest bin, the quietest phase reference
 * </pre>
 *
 * <p>For a FOLDED line every guard widens by the lock's residual relative
 * error times the sum of the two bin positions: a clock error moves a folded
 * line against its target, not with it.
 *
 * <p>The leakage a window puts into a neighbouring bin is taken from the
 * window's own transform in closed form, so no window array is built and no
 * transform is run, and the curve does not depend on the transform size at
 * all.  For a sum of cosines - which is every window here except the Kaiser
 * and the Dolph-Chebyshev families - a tone sitting exactly on a bin raises
 * the bin m away by the coefficient ratio of that window, and raises nothing
 * beyond the last coefficient; the main lobe's half-width is therefore the
 * number of coefficients.  The Kaiser window has the closed form of its
 * transform, and a Dolph-Chebyshev window is defined by its transform in the
 * first place, with equiripple sidelobes at its design attenuation.
 */
public final class PilotMain {

    /** Protected harmonics of the fundamental when nothing else is asked. */
    private static final int DEFAULT_HARMONICS = 9;
    /** Pilot harmonic orders carried through the rules. */
    private static final int DEFAULT_ORDERS = 8;
    /** Bins added to the window's main-lobe half-width to form the guard. */
    private static final double DEFAULT_GUARD_BINS = 2.0;
    /** The chain's usable bandwidth, Hz - an amplifier's -3 dB corner. */
    private static final double DEFAULT_TOP_HZ = 16000.0;
    /** Capture anti-alias stopband entry as a fraction of the capture rate. */
    private static final double AA_STOPBAND_FRACTION = 0.6;
    /** Coarsest frequency step any path applies to the pilot, Hz. */
    private static final double DEFAULT_QUANT_HZ = 0.001;
    /** The frequency lock's residual relative time-base error after trim. */
    private static final double DEFAULT_LOCK_RESIDUAL = 1e-6;
    /** Phase budget of the pilot readout, degrees. */
    private static final double DEFAULT_PHASE_BUDGET_DEG = 0.01;
    /** The pilot's level at the converter, dBV. */
    private static final double DEFAULT_PILOT_DBV = -90.0;
    /** The fundamental's level before the notch, dBV. */
    private static final double DEFAULT_DRIVE_DBV = 0.0;
    /** The chain's second harmonic, dB relative to its fundamental. */
    private static final double DEFAULT_H2_DB = -77.5;
    /** Further dB per harmonic order beyond the second. */
    private static final double DEFAULT_SLOPE_DB = -6.0;
    /** Playback stopband for a generated line above a third of the rate, dB. */
    private static final double DEFAULT_STOPBAND_DB = -100.0;
    /** Per-bin analysis floor at the protected harmonics, dBV. */
    private static final double DEFAULT_FLOOR_DBV = -150.0;
    /** How far the ladder's level test stays under the floor, dB. */
    private static final double DEFAULT_MARGIN_DB = 10.0;
    /** Intermodulation products sit this far above the chain's H2, dB. */
    private static final double IMD_OVER_H2_DB = 6.0;
    /** Widths tried for the halfway step, as a fraction of the spacing. */
    private static final double[] HALFWAY_WIDTHS = {0.02, 0.05, 0.10};
    /**
     * Level below which a window's own leakage is not modelled, dB.  On the
     * analysis grid a cosine-sum window leaks exactly nothing beyond its last
     * coefficient; built on the symmetric grid the true residue is not quite
     * zero but measures -251 dB at sixteen bins, which no analysis floor and
     * no converter can resolve.  This constant stands in for both.
     */
    private static final double MODELLED_FLOOR_DB = -400.0;
    /** Timed repetitions of the whole selection. */
    private static final int DEFAULT_REPEATS = 5;
    /** Repetitions discarded before measuring, so the code is compiled. */
    private static final int WARMUP_REPEATS = 2;
    /** Runners-up listed with the step that dropped them. */
    private static final int DEFAULT_RUNNERS_UP = 5;

    /** Coefficients of the cosine-sum windows, in the analyser's own form:
     *  the first is the constant term, every further one is the amplitude of
     *  the next cosine.  Their ratios ARE the window's leakage on the
     *  analysis grid, so nothing else about the window is needed here. */
    private static final double[] RECT_COEFFS = {1.0};
    private static final double[] HANN_COEFFS = {0.5, 0.5};
    private static final double[] BH4_COEFFS = {0.35875, 0.48829, 0.14128, 0.01168};
    private static final double[] BH7_COEFFS = {0.2712203606, 0.4334446123, 0.2180041184,
                                                0.0657853433, 0.0107618673, 0.0007700127,
                                                0.0000136547};
    private static final double[] FT_COEFFS = {0.21557895, 0.41663158, 0.27726316,
                                               0.08357895, 0.00694737};
    private static final double[] HFT144D_COEFFS = {1.0, -1.96760033, 1.57983607, -0.81123644,
                                                    0.22583558, -0.02773848, 0.00090360};
    private static final double[] HFT248D_COEFFS = {1.0, -1.985844164102, 1.791176438506,
                                                    -1.282075284005, 0.667777530266,
                                                    -0.240160796576, 0.056656381764,
                                                    -0.008134974479, 0.000624544650,
                                                    -0.000019808998, 0.000000132974};

    private final double captureRateHz;
    private final double playbackRateHz;
    private final int fftSize;
    private final double wantedFundamentalHz;
    private final WindowType windowType;
    private final int harmonics;
    private final int orders;
    private final double topHz;
    private final double antiAliasHz;
    private final double quantHz;
    private final double lockResidual;
    private final double guardBins;
    private final double phaseBudgetDeg;
    private final double pilotDbv;
    private final double driveDbv;
    private final double h2Db;
    private final double slopeDb;
    private final double imdDb;
    private final double stopbandDb;
    private final double floorDbv;
    private final double marginDb;
    private final int repeats;
    private final int runnersUp;

    /** How a window's transform is known in closed form. */
    private enum LeakageKind {
        /** A sum of cosines: on the analysis grid the transform IS the
         *  coefficient set, and it is zero beyond the last coefficient. */
        COSINE_SUM,
        /** Kaiser: the closed hyperbolic form of its transform. */
        KAISER,
        /** Dolph-Chebyshev: defined by its transform in the first place. */
        CHEBYSHEV
    }

    /**
     * What a bin-aligned tone puts into a neighbouring bin, in dB relative to
     * its own bin, straight from the window's transform.  {@code lobe} is the
     * main lobe's half-width in bins, the first null - two lines closer than
     * that are one hump and neither can be read.
     */
    private record Leakage(LeakageKind kind, double[] coeffs, double beta, double attenuationDb,
                           int chebyshevOrder, int fftSize, int lobe) {

        double at(int distanceBins) {
            int d = Math.abs(distanceBins);
            if (d == 0) {
                return 0.0;
            }
            switch (kind) {
                case COSINE_SUM:
                    return d < coeffs.length
                            ? decibel(Math.abs(coeffs[d]) / (2.0 * coeffs[0]))
                            : MODELLED_FLOOR_DB;
                case KAISER:
                    return kaiser(d);
                default:
                    return chebyshev(d);
            }
        }

        /** The Kaiser window's transform: hyperbolic inside the main lobe,
         *  trigonometric outside it, normalised to the peak. */
        private double kaiser(int d) {
            double u = Math.PI * d;
            double peak = Math.sinh(beta) / beta;
            double value;
            if (u < beta) {
                double r = Math.sqrt(beta * beta - u * u);
                value = Math.sinh(r) / r;
            } else {
                double r = Math.sqrt(u * u - beta * beta);
                value = r == 0.0 ? 1.0 : Math.sin(r) / r;
            }
            return decibel(Math.abs(value) / peak);
        }

        /** The Dolph-Chebyshev transform, which is the window's definition;
         *  beyond the main lobe every sidelobe peaks at the design
         *  attenuation, and that peak is what a guard must respect. */
        private double chebyshev(int d) {
            if (d >= lobe) {
                return -attenuationDb;
            }
            double x = beta * Math.cos(Math.PI * d / fftSize);
            double value = Math.abs(x) > 1.0
                    ? Math.cosh(chebyshevOrder * Math.log(Math.abs(x) + Math.sqrt(x * x - 1.0)))
                    : Math.cos(chebyshevOrder * Math.acos(x));
            return Math.max(-attenuationDb, decibel(Math.abs(value) / Math.pow(10.0, attenuationDb / 20.0)));
        }

        private double decibel(double ratio) {
            return Math.max(MODELLED_FLOOR_DB, 20.0 * Math.log10(Math.max(ratio, 1e-30)));
        }
    }

    /** One pass of the knock-out rules over the band.  {@code out} marks the
     *  rejected bins, {@code rejected} counts them rule by rule, and the three
     *  arrays carry what the selection steps need: the clearance in bins, the
     *  sample-pattern walk, and the distance from the halfway point. */
    private record Scan(int loBin, int hiBin, boolean[] out, int[] rejected, int[] clearance,
                        int[] walk, double[] offHalfway, boolean[] roundLevelUsed, int admissible) {
    }

    /** The one pilot and the trace of how the steps got there.  {@code lostAt}
     *  holds, per candidate, the step number that dropped it (zero for the
     *  winner), so any runner-up can name its reason. */
    private record Choice(int winnerIndex, byte[] lostAt, int bestClearance, double halfwayWidth,
                          int after1, int after2, int after3) {
    }

    /**
     * Reads the command line.  Every value has a default that matches a
     * realistic bench, so a bare run is meaningful.  Nothing is read from a
     * file and nothing is written to one.
     */
    public PilotMain(String[] args) {
        Map<String, String> opts = options(args);
        captureRateHz = number(opts, "fs", 48000.0);
        playbackRateHz = number(opts, "fs-dac", captureRateHz);
        fftSize = size(opts.getOrDefault("fft", "1M"));
        wantedFundamentalHz = number(opts, "f0", 1003.0);
        windowType = WindowType.valueOf(opts.getOrDefault("window", "BH7").toUpperCase(Locale.ROOT));
        harmonics = (int) number(opts, "harmonics", DEFAULT_HARMONICS);
        orders = Math.max(2, (int) number(opts, "orders", DEFAULT_ORDERS));
        topHz = number(opts, "f-top", DEFAULT_TOP_HZ);
        antiAliasHz = number(opts, "f-aa", AA_STOPBAND_FRACTION * captureRateHz);
        quantHz = number(opts, "q", DEFAULT_QUANT_HZ);
        lockResidual = number(opts, "t-res", DEFAULT_LOCK_RESIDUAL);
        guardBins = number(opts, "guard", DEFAULT_GUARD_BINS);
        phaseBudgetDeg = number(opts, "eps-deg", DEFAULT_PHASE_BUDGET_DEG);
        pilotDbv = number(opts, "pilot-dbv", DEFAULT_PILOT_DBV);
        driveDbv = number(opts, "drive-dbv", DEFAULT_DRIVE_DBV);
        h2Db = number(opts, "h2", DEFAULT_H2_DB);
        slopeDb = number(opts, "hslope", DEFAULT_SLOPE_DB);
        imdDb = number(opts, "imd", h2Db + IMD_OVER_H2_DB);
        stopbandDb = number(opts, "stopband", DEFAULT_STOPBAND_DB);
        floorDbv = number(opts, "floor-dbv", DEFAULT_FLOOR_DBV);
        marginDb = number(opts, "margin", DEFAULT_MARGIN_DB);
        repeats = Math.max(1, (int) number(opts, "repeats", DEFAULT_REPEATS));
        runnersUp = (int) number(opts, "count", DEFAULT_RUNNERS_UP);
    }

    public static void main(String[] args) {
        new PilotMain(args).run();
    }

    /**
     * Selects the pilot, repeats the selection for the timings, then prints
     * the configuration, the knock-out tally, the steps, the chosen pilot with
     * its clearances and levels, the runners-up, and what it all cost.
     */
    public void run() {
        double binHz = captureRateHz / fftSize;
        int half = fftSize / 2;
        int k0 = (int) Math.round(wantedFundamentalHz / binHz);
        if (k0 < 1) {
            System.out.println("the fundamental falls below the first bin");
            return;
        }
        double f0 = k0 * binHz;
        int[] ladder = new int[harmonics];
        for (int n = 1; n <= harmonics; n++) {
            ladder[n - 1] = n * k0;
        }
        if (ladder[harmonics - 1] > half) {
            System.out.printf(Locale.ROOT, "harmonic %d lies above Nyquist at this rate%n", harmonics);
            return;
        }
        int fundamentalOrders = (int) Math.floor(antiAliasHz / f0);

        Leakage leak = buildLeakage();
        double guard = leak.lobe() + guardBins;
        double thirdHz = Math.min(captureRateHz, playbackRateHz) / 3.0;
        double ceilingHz = Math.min(topHz, thirdHz);
        int loBin = ladder[harmonics - 1] + (int) Math.floor(guard) + 1;
        int hiBin = Math.min((int) Math.floor(ceilingHz / binHz), half - 1);
        while (hiBin >= loBin && hiBin * binHz >= thirdHz) {
            hiBin--;
        }

        System.out.printf(Locale.ROOT,
                "grid        : %.0f Hz / %d points = %.11f Hz per bin, Nyquist %.0f Hz%s%n",
                captureRateHz, fftSize, binHz, captureRateHz / 2.0,
                playbackRateHz == captureRateHz ? ""
                        : String.format(Locale.ROOT, ", playback at %.0f Hz", playbackRateHz));
        System.out.printf(Locale.ROOT,
                "fundamental : %.3f Hz wanted -> bin %d = %.7f Hz; protected harmonics 1..%d up to %.3f Hz%n",
                wantedFundamentalHz, k0, f0, harmonics, ladder[harmonics - 1] * binHz);
        System.out.printf(Locale.ROOT,
                "window      : %s, main lobe %d bins = %.3f Hz, guard %.0f bins; "
                        + "leakage %.1f dB at 1 bin, %.1f dB at %d%n",
                windowType, leak.lobe(), leak.lobe() * binHz, guard,
                leak.at(1), leak.at(leak.lobe() - 1), leak.lobe() - 1);
        System.out.printf(Locale.ROOT,
                "fold guard  : + %.2g x (line bin + target bin) for folded lines%n", lockResidual);
        System.out.printf(Locale.ROOT,
                "f0 harmonics: orders 1..%d reach the converter (anti-alias stopband from %.0f Hz)%n",
                fundamentalOrders, antiAliasHz);
        if (hiBin < loBin) {
            System.out.printf(Locale.ROOT,
                    "band        : EMPTY - the ladder ends at %.1f Hz, the ceiling is %.1f Hz%n",
                    ladder[harmonics - 1] * binHz, ceilingHz);
            System.out.println();
            System.out.println("NO PILOT for this configuration.");
            return;
        }
        System.out.printf(Locale.ROOT,
                "band        : bins %d..%d = %.3f..%.3f Hz (ceiling: %s)%n",
                loBin, hiBin, loBin * binHz, hiBin * binHz,
                topHz < thirdHz ? String.format(Locale.ROOT, "the chain's %.0f Hz", topHz)
                        : String.format(Locale.ROOT, "a third of the lower converter rate, %.0f Hz", thirdHz));

        Scan scan = scan(loBin, hiBin, k0, ladder, fundamentalOrders, guard, binHz);
        Choice choice = scan.admissible() == 0 ? null : choose(scan, k0);

        reportScan(scan, binHz, guard);
        if (choice == null) {
            System.out.println();
            System.out.println("NO PILOT for this configuration - every bin of the band fails a rule; "
                    + "no rule is weakened.");
            return;
        }
        reportChoice(scan, choice, k0, ladder, fundamentalOrders, leak, binHz, thirdHz);
        reportTiming(measure(loBin, hiBin, k0, ladder, fundamentalOrders, binHz));
    }

    // ---------------------------------------------------------------- rules

    /**
     * Applies the knock-out rules to every bin of the band and collects what
     * the selection steps need.  Every rule is evaluated for every candidate,
     * without an early exit, so the tally states how many bins each rule takes
     * on its own and the cost is the honest cost of the whole rule set.
     */
    private Scan scan(int loBin, int hiBin, int k0, int[] ladder, int fundamentalOrders,
                      double guard, double binHz) {
        int count = hiBin - loBin + 1;
        boolean[] out = new boolean[count];
        int[] rejected = new int[6];
        int[] clearance = new int[count];
        int[] walk = new int[count];
        double[] offHalfway = new double[count];
        boolean[] roundLevelUsed = new boolean[3];
        double guardHz = Math.max(guard * binHz, quantHz);

        int[] fundamentalLines = new int[fundamentalOrders];
        long[] fundamentalRaw = new long[fundamentalOrders];
        for (int n = 1; n <= fundamentalOrders; n++) {
            long raw = (long) n * k0;
            fundamentalRaw[n - 1] = raw;
            fundamentalLines[n - 1] = fold(raw);
        }
        int half = fftSize / 2;
        int[] imdA = {1, 1, 1, 1, 2, 2};
        int[] imdB = {1, 1, 2, 2, 1, 1};
        int[] imdSign = {1, -1, 1, -1, 1, -1};

        for (int i = 0; i < count; i++) {
            int k2 = loBin + i;
            double f2 = k2 * binHz;
            int nearest = Integer.MAX_VALUE;

            boolean wholePeriod = fftSize % k2 == 0 || wholePlaybackPeriod(k2);
            boolean round = nearRound(f2, guardHz, roundLevelUsed);
            boolean ladderLobe = false;
            boolean ownLobe = false;
            boolean fundamentalLobe = false;
            boolean imdLobe = false;

            for (int m = 1; m <= orders; m++) {
                long raw = (long) m * k2;
                int line = fold(raw);
                boolean folded = raw > half;
                for (int n = 0; n < ladder.length; n++) {
                    int d = Math.abs(line - ladder[n]);
                    double widen = folded ? lockResidual * (raw + ladder[n]) : 0.0;
                    if (d <= guard + widen) {
                        ladderLobe = true;
                    }
                    if (d < nearest) {
                        nearest = d;
                    }
                }
                if (m >= 2) {
                    int d = Math.abs(line - k2);
                    double widen = folded ? lockResidual * (raw + k2) : 0.0;
                    if (d <= guard + widen) {
                        ownLobe = true;
                    }
                    if (d < nearest) {
                        nearest = d;
                    }
                }
            }
            for (int n = 0; n < fundamentalOrders; n++) {
                int d = Math.abs(fundamentalLines[n] - k2);
                double widen = fundamentalRaw[n] > half ? lockResidual * (fundamentalRaw[n] + k2) : 0.0;
                if (d <= guard + widen) {
                    fundamentalLobe = true;
                }
                if (d < nearest) {
                    nearest = d;
                }
            }
            for (int p = 0; p < imdA.length; p++) {
                long raw = Math.abs((long) imdA[p] * k2 + (long) imdSign[p] * imdB[p] * k0);
                int line = fold(raw);
                boolean folded = raw > half;
                for (int n = 0; n < ladder.length; n++) {
                    int d = Math.abs(line - ladder[n]);
                    double widen = folded ? lockResidual * (raw + ladder[n]) : 0.0;
                    if (d <= guard + widen) {
                        imdLobe = true;
                    }
                    if (d < nearest) {
                        nearest = d;
                    }
                }
                int d = Math.abs(line - k2);
                double widen = folded ? lockResidual * (raw + k2) : 0.0;
                if (d <= guard + widen) {
                    imdLobe = true;
                }
                if (d < nearest) {
                    nearest = d;
                }
            }

            if (wholePeriod) {
                rejected[0]++;
            }
            if (round) {
                rejected[1]++;
            }
            if (ladderLobe) {
                rejected[2]++;
            }
            if (ownLobe) {
                rejected[3]++;
            }
            if (fundamentalLobe) {
                rejected[4]++;
            }
            if (imdLobe) {
                rejected[5]++;
            }
            out[i] = wholePeriod || round || ladderLobe || ownLobe || fundamentalLobe || imdLobe;
            clearance[i] = nearest;
            walk[i] = fftSize / gcd(fftSize, k2);
            offHalfway[i] = Math.abs(k2 % k0 - k0 / 2.0);
        }
        int admissible = 0;
        for (boolean rejectedBin : out) {
            if (!rejectedBin) {
                admissible++;
            }
        }
        return new Scan(loBin, hiBin, out, rejected, clearance, walk, offHalfway, roundLevelUsed, admissible);
    }

    /** Where a line of this many bins appears: wrapped into one period of the
     *  transform and mirrored about Nyquist. */
    private int fold(long rawBins) {
        long b = Math.abs(rawBins) % fftSize;
        return (int) (b > fftSize / 2 ? fftSize - b : b);
    }

    /** Whether one period of the tone on bin {@code k2} holds a whole number
     *  of PLAYBACK samples.  Exact integer arithmetic where both rates are
     *  whole numbers of hertz, which every converter in use is. */
    private boolean wholePlaybackPeriod(int k2) {
        if (playbackRateHz == captureRateHz) {
            return fftSize % k2 == 0;
        }
        long dac = Math.round(playbackRateHz);
        long adc = Math.round(captureRateHz);
        if (dac != playbackRateHz || adc != captureRateHz) {
            double spp = playbackRateHz * fftSize / (k2 * captureRateHz);
            return Math.abs(spp - Math.rint(spp)) < 1e-9;
        }
        return dac * (long) fftSize % ((long) k2 * adc) == 0;
    }

    /**
     * Whether the frequency lies within the guard of the value it would be
     * written as to one, two or three significant digits.  A digit level whose
     * grid is not more than two guards wide is skipped: there every frequency
     * of the band is equally near a round value, none is special, and applying
     * the level would empty the band.
     */
    private boolean nearRound(double freqHz, double guardHz, boolean[] levelUsed) {
        double decade = Math.floor(Math.log10(freqHz));
        boolean near = false;
        for (int digits = 1; digits <= 3; digits++) {
            double step = Math.pow(10.0, decade - digits + 1);
            if (step / 2.0 <= guardHz) {
                continue;
            }
            levelUsed[digits - 1] = true;
            if (Math.abs(freqHz - Math.round(freqHz / step) * step) <= guardHz) {
                near = true;
            }
        }
        return near;
    }

    private int gcd(int a, int b) {
        int x = a;
        int y = b;
        while (y != 0) {
            int t = x % y;
            x = y;
            y = t;
        }
        return x;
    }

    // ------------------------------------------------------------ selection

    /**
     * Reduces the admissible set to exactly one bin.  Each step keeps the best
     * class and records, for every bin it drops, the number of the step that
     * dropped it.  The halfway step widens twice and is dropped if nothing in
     * the band sits halfway at all; the walk step falls back to the longest
     * walk if no odd bin is left.
     */
    private Choice choose(Scan scan, int k0) {
        int count = scan.out().length;
        byte[] lostAt = new byte[count];
        for (int i = 0; i < count; i++) {
            if (scan.out()[i]) {
                lostAt[i] = -1;
            }
        }

        double width = 0.0;
        for (double tried : HALFWAY_WIDTHS) {
            boolean any = false;
            for (int i = 0; i < count; i++) {
                if (lostAt[i] == 0 && scan.offHalfway()[i] <= tried * k0) {
                    any = true;
                    break;
                }
            }
            if (any) {
                width = tried;
                for (int i = 0; i < count; i++) {
                    if (lostAt[i] == 0 && scan.offHalfway()[i] > tried * k0) {
                        lostAt[i] = 1;
                    }
                }
                break;
            }
        }
        int after1 = alive(lostAt);

        int longestWalk = 0;
        for (int i = 0; i < count; i++) {
            if (lostAt[i] == 0) {
                longestWalk = Math.max(longestWalk, scan.walk()[i]);
            }
        }
        for (int i = 0; i < count; i++) {
            if (lostAt[i] == 0 && scan.walk()[i] != longestWalk) {
                lostAt[i] = 2;
            }
        }
        int after2 = alive(lostAt);

        int best = 0;
        for (int i = 0; i < count; i++) {
            if (lostAt[i] == 0) {
                best = Math.max(best, twoFigures(scan.clearance()[i]));
            }
        }
        for (int i = 0; i < count; i++) {
            if (lostAt[i] == 0 && twoFigures(scan.clearance()[i]) != best) {
                lostAt[i] = 3;
            }
        }
        int after3 = alive(lostAt);

        int winner = -1;
        for (int i = count - 1; i >= 0; i--) {
            if (lostAt[i] == 0) {
                winner = i;
                break;
            }
        }
        for (int i = 0; i < count; i++) {
            if (lostAt[i] == 0 && i != winner) {
                lostAt[i] = 4;
            }
        }
        return new Choice(winner, lostAt, best, width, after1, after2, after3);
    }

    private int alive(byte[] lostAt) {
        int n = 0;
        for (byte step : lostAt) {
            if (step == 0) {
                n++;
            }
        }
        return n;
    }

    /** The value at two significant figures, so that clearances of 2362 and
     *  2359 bins count as the same and do not decide the selection.  A value
     *  exactly halfway rounds to even, so that the selection cannot be tipped
     *  by the direction a half is taken. */
    private int twoFigures(int value) {
        if (value <= 0) {
            return 0;
        }
        int magnitude = (int) Math.pow(10, Math.max(0, (int) Math.floor(Math.log10(value)) - 1));
        return (int) (Math.rint(value / (double) magnitude) * magnitude);
    }

    // --------------------------------------------------------- the leakage

    /**
     * The window's leakage in closed form.  No window is built and no
     * transform is run: a cosine-sum window's transform on the analysis grid
     * is its own coefficient set, the Kaiser window's transform is hyperbolic
     * inside the main lobe and trigonometric outside it, and a
     * Dolph-Chebyshev window is defined by its transform to begin with.
     */
    private Leakage buildLeakage() {
        switch (windowType) {
            case RECT:
                return cosineLeakage(RECT_COEFFS);
            case HANN:
                return cosineLeakage(HANN_COEFFS);
            case BH4:
                return cosineLeakage(BH4_COEFFS);
            case BH7:
                return cosineLeakage(BH7_COEFFS);
            case FT:
                return cosineLeakage(FT_COEFFS);
            case HFT144D:
                return cosineLeakage(HFT144D_COEFFS);
            case HFT248D:
                return cosineLeakage(HFT248D_COEFFS);
            case KB24:
                return kaiserLeakage(24.0);
            case KB38:
                return kaiserLeakage(38.0);
            case DC150:
                return chebyshevLeakage(150.0);
            case DC200:
                return chebyshevLeakage(200.0);
            case DC250:
                return chebyshevLeakage(250.0);
            case DC300:
                return chebyshevLeakage(300.0);
            default:
                throw new IllegalArgumentException("unknown window " + windowType);
        }
    }

    /** A sum of cosines puts its own coefficients, halved against the constant
     *  term, into the neighbouring bins and nothing at all beyond the last of
     *  them, so its main lobe is exactly as many bins wide as it has
     *  coefficients. */
    private Leakage cosineLeakage(double[] coeffs) {
        return new Leakage(LeakageKind.COSINE_SUM, coeffs, 0.0, 0.0, 0, fftSize, coeffs.length);
    }

    /** The Kaiser window's first null sits where the trigonometric branch of
     *  its transform first vanishes. */
    private Leakage kaiserLeakage(double beta) {
        int lobe = (int) Math.ceil(Math.sqrt(beta * beta + Math.PI * Math.PI) / Math.PI);
        return new Leakage(LeakageKind.KAISER, null, beta, 0.0, 0, fftSize, lobe);
    }

    /** A Dolph-Chebyshev window trades a stated sidelobe attenuation for the
     *  narrowest main lobe that can carry it; the first null follows from the
     *  Chebyshev polynomial's first zero. */
    private Leakage chebyshevLeakage(double attenuationDb) {
        int order = fftSize - 1;
        double beta = Math.cosh(arcCosh(Math.pow(10.0, attenuationDb / 20.0)) / order);
        double firstZero = Math.cos(Math.PI / (2.0 * order)) / beta;
        int lobe = (int) Math.ceil(fftSize * Math.acos(Math.min(1.0, firstZero)) / Math.PI);
        return new Leakage(LeakageKind.CHEBYSHEV, null, beta, attenuationDb, order, fftSize, lobe);
    }

    private double arcCosh(double x) {
        return Math.log(x + Math.sqrt(x * x - 1.0));
    }

    // -------------------------------------------------------------- reports

    private void reportScan(Scan scan, double binHz, double guard) {
        int count = scan.hiBin() - scan.loBin() + 1;
        StringBuilder skipped = new StringBuilder();
        for (int digits = 1; digits <= 3; digits++) {
            if (!scan.roundLevelUsed()[digits - 1]) {
                skipped.append(skipped.length() == 0 ? "" : ",").append(digits);
            }
        }
        System.out.println();
        System.out.printf(Locale.ROOT, "knock-outs over %d bins:%n", count);
        System.out.printf(Locale.ROOT, "  rule 3  whole samples per period          : %7d out%n",
                scan.rejected()[0]);
        System.out.printf(Locale.ROOT, "  rule 4  within %.3f Hz of a round value%s: %7d out%n",
                Math.max(guard * binHz, quantHz),
                skipped.length() == 0 ? "" : " (digit level " + skipped + " skipped)",
                scan.rejected()[1]);
        System.out.printf(Locale.ROOT, "  rule 5  pilot harmonic in a ladder lobe   : %7d out%n",
                scan.rejected()[2]);
        System.out.printf(Locale.ROOT, "  rule 6  own harmonic in the pilot's lobe  : %7d out%n",
                scan.rejected()[3]);
        System.out.printf(Locale.ROOT, "  rule 6  f0 harmonic in the pilot's lobe   : %7d out%n",
                scan.rejected()[4]);
        System.out.printf(Locale.ROOT, "  rule 7  intermodulation in a lobe         : %7d out%n",
                scan.rejected()[5]);
        System.out.printf(Locale.ROOT, "  admissible                                : %7d%n",
                scan.admissible());
    }

    private void reportChoice(Scan scan, Choice choice, int k0, int[] ladder, int fundamentalOrders,
                              Leakage leak, double binHz, double thirdHz) {
        int i = choice.winnerIndex();
        int k2 = scan.loBin() + i;
        double f2 = k2 * binHz;
        int half = fftSize / 2;

        System.out.println();
        System.out.println("selection:");
        System.out.printf(Locale.ROOT, "  step 1  halfway between two f0 harmonics  : %s%n",
                choice.halfwayWidth() == 0.0 ? "dropped, nothing halfway in this band"
                        : String.format(Locale.ROOT, "%d left (within %.0f percent of the spacing)",
                                choice.after1(), 100.0 * choice.halfwayWidth()));
        System.out.printf(Locale.ROOT, "  step 2  odd bin, full sample walk         : %d left%n",
                choice.after2());
        System.out.printf(Locale.ROOT, "  step 3  widest clearance (two figures)    : %d left at %d bins%n",
                choice.after3(), choice.bestClearance());
        System.out.println("  step 4  highest bin                       : 1");

        System.out.println();
        System.out.printf(Locale.ROOT, "PILOT: %.6f Hz = bin %d%n", f2, k2);
        System.out.printf(Locale.ROOT,
                "  samples per period: %.6f capture%s; the pattern walks %d samples = %.2f s%n",
                captureRateHz / f2,
                playbackRateHz == captureRateHz ? ""
                        : String.format(Locale.ROOT, ", %.6f playback", playbackRateHz / f2),
                scan.walk()[i], scan.walk()[i] / captureRateHz);
        System.out.printf(Locale.ROOT,
                "  position: %.3f x f0, %.2f percent of the harmonic spacing from the halfway point%n",
                k2 / (double) k0, 100.0 * scan.offHalfway()[i] / k0);

        System.out.println("  clearances, the closest line of each family:");
        int bestM = 0;
        int bestN = 0;
        int bestD = Integer.MAX_VALUE;
        for (int m = 1; m <= orders; m++) {
            int line = fold((long) m * k2);
            for (int n = 0; n < ladder.length; n++) {
                int d = Math.abs(line - ladder[n]);
                if (d < bestD) {
                    bestD = d;
                    bestM = m;
                    bestN = n + 1;
                }
            }
        }
        printClearance(String.format(Locale.ROOT, "pilot harmonic %d to harmonic %d of f0%s",
                bestM, bestN, (long) bestM * k2 > half ? ", folded" : ""), bestD, binHz);
        bestD = Integer.MAX_VALUE;
        bestM = 0;
        for (int m = 2; m <= orders; m++) {
            int d = Math.abs(fold((long) m * k2) - k2);
            if (d < bestD) {
                bestD = d;
                bestM = m;
            }
        }
        printClearance(String.format(Locale.ROOT, "pilot harmonic %d to the pilot's bin%s",
                bestM, (long) bestM * k2 > half ? ", folded" : ""), bestD, binHz);
        bestD = Integer.MAX_VALUE;
        bestN = 0;
        for (int n = 1; n <= fundamentalOrders; n++) {
            int d = Math.abs(fold((long) n * k0) - k2);
            if (d < bestD) {
                bestD = d;
                bestN = n;
            }
        }
        printClearance(String.format(Locale.ROOT, "harmonic %d of f0 to the pilot's bin%s",
                bestN, (long) bestN * k0 > half ? ", folded" : ""), bestD, binHz);
        int[] imdA = {1, 1, 1, 1, 2, 2};
        int[] imdB = {1, 1, 2, 2, 1, 1};
        int[] imdSign = {1, -1, 1, -1, 1, -1};
        int bestP = 0;
        bestN = 0;
        bestD = Integer.MAX_VALUE;
        int bestSelfP = 0;
        int bestSelfD = Integer.MAX_VALUE;
        for (int p = 0; p < imdA.length; p++) {
            int line = fold(Math.abs((long) imdA[p] * k2 + (long) imdSign[p] * imdB[p] * k0));
            for (int n = 0; n < ladder.length; n++) {
                int d = Math.abs(line - ladder[n]);
                if (d < bestD) {
                    bestD = d;
                    bestP = p;
                    bestN = n + 1;
                }
            }
            int d = Math.abs(line - k2);
            if (d < bestSelfD) {
                bestSelfD = d;
                bestSelfP = p;
            }
        }
        printClearance(String.format(Locale.ROOT, "%s to harmonic %d of f0",
                imdName(imdA[bestP], imdSign[bestP], imdB[bestP]), bestN), bestD, binHz);
        printClearance(String.format(Locale.ROOT, "%s to the pilot's bin",
                imdName(imdA[bestSelfP], imdSign[bestSelfP], imdB[bestSelfP])), bestSelfD, binHz);

        reportLevels(scan, choice, k0, ladder, fundamentalOrders, leak, binHz, thirdHz);
        reportRunnersUp(scan, choice, k0, binHz);
    }

    private String imdName(int a, int sign, int b) {
        return String.format(Locale.ROOT, "%d x pilot %s %d x f0", a, sign > 0 ? "+" : "-", b);
    }

    private void printClearance(String what, int distanceBins, double binHz) {
        System.out.printf(Locale.ROOT, "    %-46s %7d bins = %10.3f Hz%n",
                what, distanceBins, distanceBins * binHz);
    }

    /**
     * What the protected harmonics receive from the pilot, what the pilot's
     * own bin receives from everything else, and what the analysis floor does
     * to the pilot's phase.  The ladder side is measured against the floor
     * less the margin; the pilot side against the phase budget, because a line
     * of amplitude ratio r on the pilot's bin turns its phase by up to r
     * radians.
     */
    private void reportLevels(Scan scan, Choice choice, int k0, int[] ladder, int fundamentalOrders,
                              Leakage leak, double binHz, double thirdHz) {
        int i = choice.winnerIndex();
        int k2 = scan.loBin() + i;
        double budgetDb = 20.0 * Math.log10(Math.toRadians(phaseBudgetDeg));

        System.out.println();
        System.out.printf(Locale.ROOT,
                "levels with the pilot at %.0f dBV, the fundamental at %.0f dBV before the notch, "
                        + "chain second harmonic %.1f dB and %.0f dB per order:%n",
                pilotDbv, driveDbv, h2Db, slopeDb);

        double worstLadder = Double.NEGATIVE_INFINITY;
        int worstOrder = 0;
        int worstHarmonic = 0;
        for (int m = 1; m <= orders; m++) {
            long raw = (long) m * k2;
            int line = fold(raw);
            double own = m == 1 ? 0.0 : h2Db + (m - 2) * slopeDb;
            double stop = raw * binHz > thirdHz ? stopbandDb : 0.0;
            for (int n = 0; n < ladder.length; n++) {
                double level = pilotDbv + own + stop + leak.at(Math.abs(line - ladder[n]));
                if (level > worstLadder) {
                    worstLadder = level;
                    worstOrder = m;
                    worstHarmonic = n + 1;
                }
            }
        }
        double limit = floorDbv - marginDb;
        System.out.printf(Locale.ROOT,
                "  ladder side: worst is pilot harmonic %d at harmonic %d of f0, %.1f dBV against "
                        + "floor less margin %.1f dBV -> %s%n",
                worstOrder, worstHarmonic, worstLadder, limit,
                worstLadder < limit ? "ok"
                        : String.format(Locale.ROOT, "TOO LOUD by %.0f dB", worstLadder - limit));

        double worstPilot = Double.NEGATIVE_INFINITY;
        String worstWhat = "nothing";
        for (int m = 2; m <= orders; m++) {
            long raw = (long) m * k2;
            double own = h2Db + (m - 2) * slopeDb;
            double stop = raw * binHz > thirdHz ? stopbandDb : 0.0;
            double level = pilotDbv + own + stop + leak.at(Math.abs(fold(raw) - k2));
            if (level > worstPilot) {
                worstPilot = level;
                worstWhat = "pilot harmonic " + m;
            }
        }
        for (int n = 1; n <= fundamentalOrders; n++) {
            double own = n == 1 ? 0.0 : h2Db + (n - 2) * slopeDb;
            double level = driveDbv + own + leak.at(Math.abs(fold((long) n * k0) - k2));
            if (level > worstPilot) {
                worstPilot = level;
                worstWhat = "harmonic " + n + " of f0";
            }
        }
        int[] imdA = {1, 1, 1, 1, 2, 2};
        int[] imdB = {1, 1, 2, 2, 1, 1};
        int[] imdSign = {1, -1, 1, -1, 1, -1};
        for (int p = 0; p < imdA.length; p++) {
            long raw = Math.abs((long) imdA[p] * k2 + (long) imdSign[p] * imdB[p] * k0);
            double level = pilotDbv + imdDb + leak.at(Math.abs(fold(raw) - k2));
            if (level > worstPilot) {
                worstPilot = level;
                worstWhat = imdName(imdA[p], imdSign[p], imdB[p]);
            }
        }
        double pilotLimit = pilotDbv + budgetDb;
        System.out.printf(Locale.ROOT,
                "  pilot side : worst is %s, %.1f dBV against the phase budget %.2f deg = %.1f dBV -> %s%n",
                worstWhat, worstPilot, phaseBudgetDeg, pilotLimit,
                worstPilot < pilotLimit ? "ok"
                        : String.format(Locale.ROOT, "turns the phase by up to %.3f deg",
                                Math.toDegrees(Math.pow(10.0, (worstPilot - pilotDbv) / 20.0))));

        double snrDb = pilotDbv - floorDbv;
        double sigmaDeg = Math.toDegrees(Math.pow(10.0, -snrDb / 20.0) / Math.sqrt(2.0));
        System.out.printf(Locale.ROOT,
                "  floor noise: %.0f dB above the per-bin floor -> %.4f deg of phase noise per frame, "
                        + "%.4f deg at the fundamental%n",
                snrDb, sigmaDeg, sigmaDeg * k0 / (double) k2);
    }

    private void reportRunnersUp(Scan scan, Choice choice, int k0, double binHz) {
        if (runnersUp <= 0) {
            return;
        }
        System.out.println();
        System.out.println("runners-up (highest first) and where they lost:");
        int shown = 0;
        for (int i = scan.out().length - 1; i >= 0 && shown < runnersUp; i--) {
            if (scan.out()[i] || i == choice.winnerIndex()) {
                continue;
            }
            int k2 = scan.loBin() + i;
            String why;
            switch (choice.lostAt()[i]) {
                case 1:
                    why = String.format(Locale.ROOT,
                            "step 1: %.1f percent of the spacing from the halfway point",
                            100.0 * scan.offHalfway()[i] / k0);
                    break;
                case 2:
                    why = String.format(Locale.ROOT, "step 2: the pattern repeats after %d samples",
                            scan.walk()[i]);
                    break;
                case 3:
                    why = String.format(Locale.ROOT, "step 3: clearance %d bins", scan.clearance()[i]);
                    break;
                default:
                    why = "step 4: lower bin";
                    break;
            }
            System.out.printf(Locale.ROOT, "  %.6f Hz  bin %d  %s%n", k2 * binHz, k2, why);
            shown++;
        }
    }

    // ------------------------------------------------------------ the clock

    /**
     * Repeats the whole selection - everything the analyser would run when a
     * capture starts, from the window's leakage to the one elected bin - and
     * returns how long each run took.  The first runs are discarded so that
     * nothing is believed before the code is compiled.
     */
    private long[] measure(int loBin, int hiBin, int k0, int[] ladder, int fundamentalOrders,
                           double binHz) {
        long[] taken = new long[repeats];
        for (int r = 0; r < repeats + WARMUP_REPEATS; r++) {
            long started = System.nanoTime();
            Leakage leak = buildLeakage();
            Scan scan = scan(loBin, hiBin, k0, ladder, fundamentalOrders,
                    leak.lobe() + guardBins, binHz);
            if (scan.admissible() > 0) {
                choose(scan, k0);
            }
            if (r >= WARMUP_REPEATS) {
                taken[r - WARMUP_REPEATS] = System.nanoTime() - started;
            }
        }
        return taken;
    }

    private void reportTiming(long[] taken) {
        System.out.println();
        System.out.printf(Locale.ROOT,
                "the whole selection takes %.1f ms (median of %d runs, best %.1f ms)%n",
                median(taken) / 1e6, taken.length, min(taken) / 1e6);
    }

    private long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private long min(long[] values) {
        long best = Long.MAX_VALUE;
        for (long v : values) {
            best = Math.min(best, v);
        }
        return best;
    }

    // ------------------------------------------------------------- the line

    /** The command line as a map, every option written as --name value. */
    private Map<String, String> options(String[] args) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (!a.startsWith("--")) {
                continue;
            }
            String name = a.substring(2);
            String value = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "true";
            map.put(name, value);
        }
        return map;
    }

    private double number(Map<String, String> opts, String name, double fallback) {
        String value = opts.get(name);
        return value == null ? fallback : Double.parseDouble(value);
    }

    /** A transform size written as a plain number, or with k or M for the
     *  binary thousand and million the analyser offers. */
    private int size(String text) {
        String t = text.trim().toLowerCase(Locale.ROOT);
        int multiplier = 1;
        if (t.endsWith("k")) {
            multiplier = 1 << 10;
            t = t.substring(0, t.length() - 1);
        } else if (t.endsWith("m")) {
            multiplier = 1 << 20;
            t = t.substring(0, t.length() - 1);
        }
        int value = (int) (Double.parseDouble(t) * multiplier);
        if ((value & (value - 1)) != 0) {
            throw new IllegalArgumentException("the transform size must be a power of two: " + value);
        }
        return value;
    }
}
