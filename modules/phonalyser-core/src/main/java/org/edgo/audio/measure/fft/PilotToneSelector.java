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

import java.util.Arrays;
import java.util.Locale;

import org.edgo.audio.measure.enums.WindowType;

/**
 * Elects the two pilot tones for one analysis configuration.
 *
 * <p>A pilot is a generated tone whose phase the analysis reads on every frame
 * as a time-base reference beside a fundamental that sits in a notch.  Each
 * one is placed on a WHOLE bin of the transform: a line on a whole bin turns
 * by exactly {@code 2 pi x bin x advance / N} between two frames, so its
 * reading fixes the capture's advance modulo its own period and carries
 * nothing of the fundamental's position, and the two lines together settle the
 * whole turns that one alone cannot.
 *
 * <p>A candidate is admissible while nothing it produces - the line itself,
 * its harmonics, their Nyquist folds, its intermodulation with the fundamental
 * (with both tones of a dual-tone signal) - can raise a protected line's bin,
 * and while nothing else can raise the pilot's own bin.  The candidates are
 * tried from the highest under the ceiling down and the first admissible one
 * is elected: the highest pilot is the quietest phase reference.
 *
 * <pre>
 *   band    above the protected ladder's last lobe, at or below the chain's
 *           bandwidth, below a third of the lower converter rate
 *   rule 5  no pilot harmonic, folded, inside a protected line's lobe
 *   rule 6  no pilot harmonic above the first and no harmonic of the
 *           fundamental the converter can deliver, folded, inside the pilot's
 *           own lobe
 *   rule 7  no intermodulation product of the pilot with a fundamental, total
 *           order up to three, inside either lobe
 * </pre>
 *
 * <p>For a FOLDED line every guard widens by the lock's residual relative
 * time-base error times the sum of the two bin positions: a clock error moves
 * a folded line against its target, not with it.
 *
 * <p>The protected ladder is the harmonic orders 1..H of the fundamental,
 * order 1 being the fundamental itself.  With two tones it is the orders
 * 1..H of BOTH tones together with the eight intermodulation products the
 * dual-tone readout evaluates, as coefficient pairs (a, b) of
 * {@code a·f1 + b·f2}: (1, -1), (1, 1), (2, -1), (-1, 2), (3, -2), (-2, 3),
 * (4, -3), (-3, 4) - the difference and the sum, then the sideband pairs
 * (n-1)f1 - (n-2)f2 and (n-1)f2 - (n-2)f1 for n = 3, 4, 5.  A product's line
 * sits at the absolute value of its bin sum, folded like any other.
 *
 * <p>Every candidate is a whole bin, and the rules are evaluated in half-bin
 * units, where every candidate and every line is a whole number and every
 * distance exact.
 *
 * <p>The window's main lobe comes from {@link FftAnalyzer#mainLobeBins}, the
 * analyser that owns the window definitions, so no coefficient table is
 * copied here and the guard follows whatever window the analysis runs.
 *
 * <p>The election is {@link #selectPair()}: every whole bin of the band is a
 * candidate, the knock-outs remove what no line may sit on, four steps order
 * what survives, and the second pilot is then CONSTRUCTED one fundamental bin
 * below the first - the spacing at which the two pilots' phase difference is
 * identically the fundamental's own share of the advance - and admitted with
 * the first protected and outside the window's leakage.  The steps order the
 * survivors that can carry such a companion; the others are ordered only
 * where no survivor can, which leaves one pilot.
 */
public final class PilotToneSelector {

    /** Pilot harmonic orders carried through the rules. */
    private static final int PILOT_ORDERS = 8;
    /** Bins added to the window's main-lobe half-width to form the guard. */
    private static final double DEFAULT_GUARD_BINS = 2.0;
    /** Capture anti-alias stopband entry as a fraction of the capture rate. */
    private static final double AA_STOPBAND_FRACTION = 0.6;
    /** The frequency lock's residual relative time-base error after trim. */
    private static final double DEFAULT_LOCK_RESIDUAL = 1e-6;
    /** The bin reported for a pilot that was not elected. */
    public static final int NO_BIN = -1;
    /** Lower edge, Hz, of the band below the fundamental - the band a pilot is
     *  elected from only when nothing above the protected ladder is
     *  admissible.  Under it a pilot would sit in the mains foot and in the
     *  region the readout's own exclusions walk. */
    private static final double LOWER_BAND_EDGE_HZ = 1000.0;
    /** The coarsest step any path applies to a generated frequency, Hz: a
     *  thousandth of a hertz, what the generator's own frequency display
     *  commits.  Rule 4 keeps a candidate further than this from a round
     *  value, so that such a step cannot pull the pilot onto one. */
    private static final double QUANTISATION_STEP_HZ = 0.001;
    /** Significant-digit levels rule 4 calls round: one, two and three. */
    private static final int ROUND_DIGITS = 3;
    /** How far the window's own leakage must be down at the distance between
     *  the two pilots, dB - the pilot-side budget of the selection rules, a
     *  hundredth of a degree of phase error on the line being read. */
    private static final double PILOT_LEAKAGE_DB = 75.0;
    /** Step 1's tolerances as a fraction of the fundamental's bin: the halfway
     *  test is widened in this order and dropped when nothing survives it. */
    private static final double[] HALFWAY_TOLERANCES = { 0.02, 0.05, 0.10 };
    /** Significant figures step 3 compares two clearances at, so that
     *  near-equal margins count as equal and do not decide. */
    private static final int CLEARANCE_FIGURES = 2;
    /** Pilot and fundamental coefficients of the intermodulation products of
     *  rule 7: every {@code a}·pilot ± {@code b}·fundamental of total order up
     *  to three, the sign carried in the fundamental's coefficient. */
    private static final int[] IMD_PILOT_COEF = { 1, 1, 1, 1, 2, 2 };
    private static final int[] IMD_FUND_COEF  = { 1, -1, 2, -2, 1, -1 };

    /** Lower- and upper-tone coefficients of the eight dual-tone products the
     *  readout evaluates - the difference and the sum, then the sidebands
     *  (n-1)f1 - (n-2)f2 and (n-1)f2 - (n-2)f1 for n = 3, 4, 5. */
    private static final int[] DUAL_LOW_COEF  = { 1, 1, 2, -1, 3, -2, 4, -3 };
    private static final int[] DUAL_HIGH_COEF = { -1, 1, -1, 2, -2, 3, -3, 4 };

    /** Owner of the window definitions - consulted for the main lobe. */
    private final FftAnalyzer analyzer;
    private final double captureRateHz;
    private final double playbackRateHz;
    private final int fftSize;
    /** The fundamental's bin, or both tones' bins in dual tone, ascending. */
    private final int[] fundamentalBins;
    /** Highest protected harmonic ORDER of every fundamental: the ladder is
     *  the orders 1..this, order 1 being the fundamental itself.  The
     *  readout's {@link FftResult#harmonicCount} counts from the second
     *  harmonic, so a caller passing that count adds one. */
    private final int highestProtectedOrder;
    private final WindowType windowType;
    /** The chain's usable bandwidth, Hz - an amplifier's -3 dB corner. */
    private final double topHz;
    /** Where the capture anti-alias stopband starts, Hz: above it the device's
     *  own harmonics no longer reach the converter. */
    private final double antiAliasHz;
    /** Bins added to the main lobe before any guard is applied. */
    private final double guardBins = DEFAULT_GUARD_BINS;
    /** Residual relative time-base error the frequency lock leaves. */
    private final double lockResidual = DEFAULT_LOCK_RESIDUAL;

    /**
     * The elected PAIR: each pilot's whole bin on the capture grid and the
     * frequency that bin stands for.  Both pilots sit on whole bins, so every
     * rule is evaluated in whole numbers and every distance is exact.  With
     * only one admissible pilot {@code bin2} is {@link #NO_BIN} and
     * {@code hz2} NaN; with none, {@code bin1} is {@link #NO_BIN} as well.
     * {@code reason} states the rule that emptied the band or the pair, and is
     * {@code null} when both were elected.
     */
    public record PilotPair(int bin1, double hz1, int bin2, double hz2, String reason) {}

    public PilotToneSelector(FftAnalyzer analyzer, double captureRateHz, double playbackRateHz,
                             int fftSize, int[] fundamentalBins, int highestProtectedOrder,
                             WindowType windowType, double topHz) {
        this.analyzer        = analyzer;
        this.captureRateHz   = captureRateHz;
        this.playbackRateHz  = playbackRateHz;
        this.fftSize         = fftSize;
        this.fundamentalBins = fundamentalBins.clone();
        Arrays.sort(this.fundamentalBins);
        this.highestProtectedOrder = highestProtectedOrder;
        this.windowType      = windowType;
        this.topHz           = topHz;
        this.antiAliasHz     = AA_STOPBAND_FRACTION * captureRateHz;
    }

    /**
     * Elects the PAIR of pilots for this configuration.  Every whole bin of
     * the band is a candidate - rule 1, the integer bin, holds by construction
     * - the knock-outs of rules 2 to 7 remove what no line may sit on, and
     * steps 1 to 4 order what survives down to one.  The second pilot is not
     * elected but CONSTRUCTED, one fundamental bin below the first, and
     * admitted only if it holds against the ladder the first has joined and
     * lies outside the window's leakage
     * ({@link #companionAtFundamentalSpacing}).  The steps therefore run on
     * the survivors whose companion is admissible ({@link #companionClass}),
     * and on all survivors only where that class is empty.
     *
     * <p>Two bands.  The first is above the protected ladder's last lobe,
     * below a third of the lower converter rate, at or under the chain's
     * bandwidth and never the Nyquist bin.  Only when that band holds no
     * admissible bin at all is the band BELOW the fundamental tried, from
     * {@value #LOWER_BAND_EDGE_HZ} Hz up to the lowest tone's lobe: there is
     * no harmonic spacing to sit halfway in under the fundamental, so step 1
     * is skipped there, while step 4 keeps the highest survivor as it does
     * above - a pilot's own phase noise enters the excess scaled by the tone's
     * bin over the pilot's, so the highest pilot is the quietest reference.
     */
    public PilotPair selectPair() {
        double binHz = captureRateHz / fftSize;
        if (fundamentalBins.length == 0 || fundamentalBins[0] < 1) {
            return nonePair("the fundamental falls below the first bin");
        }
        int[] ladder  = protectedLines();
        int   highest = ladder[ladder.length - 1];
        if (highest > fftSize / 2) {
            return nonePair(String.format(Locale.ROOT,
                    "the protected line at bin %d lies above Nyquist at this rate", highest));
        }
        double guard   = analyzer.mainLobeBins(windowType) + guardBins;
        double thirdHz = Math.min(captureRateHz, playbackRateHz) / 3.0;
        int upperLo = (int) Math.floor(highest + guard) + 1;
        int upperHi = Math.min(Math.min((int) Math.floor(topHz / binHz),
                                        (int) Math.ceil(thirdHz / binHz) - 1),
                               fftSize / 2 - 1);
        boolean lower     = false;
        int[]   survivors = admissible(upperLo, upperHi, ladder, guard);
        if (survivors.length == 0) {
            String upperWhy = bandReason("above the protected ladder", upperLo, upperHi,
                    ladder, guard);
            lower = true;
            int lowerLo = (int) Math.ceil(LOWER_BAND_EDGE_HZ / binHz);
            int lowerHi = (int) Math.ceil(fundamentalBins[0] - guard) - 1;
            survivors   = admissible(lowerLo, lowerHi, ladder, guard);
            if (survivors.length == 0) {
                return nonePair(upperWhy + "; " + bandReason("below the fundamental",
                        lowerLo, lowerHi, ladder, guard));
            }
        }
        int   separation = analyzer.leakageClearBins(windowType, PILOT_LEAKAGE_DB);
        // The alignment runs on the PAIR, so the first pilot is elected among
        // the survivors that can carry a companion.  Ordering all survivors
        // first and constructing the companion afterwards would lose the pair
        // whenever the best single line's companion happens to fall to a rule,
        // while its neighbours' companions stand.
        int[] paired     = companionClass(survivors, ladder, guard, separation);
        int   first      = elect(paired.length > 0 ? paired : survivors, lower, ladder);
        int[] ladderPlus = withLine(ladder, first);
        int   second     = companionAtFundamentalSpacing(first, ladderPlus, guard, separation);
        if (second <= 0) {
            return new PilotPair(first, first * binHz, NO_BIN, Double.NaN,
                    companionSpacingReason(first, ladderPlus, guard, separation));
        }
        return new PilotPair(first, first * binHz, second, second * binHz, null);
    }

    private PilotPair nonePair(String reason) {
        return new PilotPair(NO_BIN, Double.NaN, NO_BIN, Double.NaN, reason);
    }

    /** Every whole bin of {@code [lo, hi]} that passes rules 3 to 7, ascending. */
    private int[] admissible(int lo, int hi, int[] ladder, double guard) {
        if (lo > hi) {
            return new int[0];
        }
        int[] kept = new int[hi - lo + 1];
        int   n    = 0;
        for (int k = lo; k <= hi; k++) {
            if (knockOut(k, ladder, guard) == null) {
                kept[n++] = k;
            }
        }
        return Arrays.copyOf(kept, n);
    }

    /** Why a band yielded nothing: it held no bin at all, or its highest bin
     *  states the rule the band falls to. */
    private String bandReason(String where, int lo, int hi, int[] ladder, double guard) {
        if (lo > hi) {
            return String.format(Locale.ROOT,
                    "rule 2, the band %s is empty (bins %d to %d)", where, lo, hi);
        }
        return String.format(Locale.ROOT,
                "no bin of the band %s is admissible; the highest, bin %d, falls to %s",
                where, hi, knockOut(hi, ladder, guard));
    }

    /** The rule a candidate bin fails, or {@code null} when it passes every
     *  one: rule 3 and rule 4 here, rules 5 to 7 through the same half-bin
     *  arithmetic the odd-multiple election uses - a whole bin is two half
     *  bins, and every protected line is doubled inside. */
    private String knockOut(int k, int[] ladder, double guard) {
        String rule = wholePeriodRule(k);
        if (rule != null) {
            return rule;
        }
        rule = roundValueRule(k, guard);
        if (rule != null) {
            return rule;
        }
        return failedRule(2L * k, ladder, guard);
    }

    /** Rule 3: neither converter may see a whole number of samples per period
     *  of the pilot - such a line renders one short sample pattern for ever,
     *  so the codes it uses and the instants it is sampled at never average
     *  out.  On the capture side that is {@code N mod k}; on the playback side
     *  {@code (fs_d N) mod (k fs_a)}, in whole hertz, which is what a
     *  converter clock is, so the test is exact. */
    private String wholePeriodRule(int k) {
        if (fftSize % k == 0) {
            return "rule 3, a whole number of capture samples per period";
        }
        long capture  = Math.round(captureRateHz);
        long playback = Math.round(playbackRateHz);
        if (capture > 0 && playback > 0
                && (playback * (long) fftSize) % (capture * (long) k) == 0) {
            return "rule 3, a whole number of playback samples per period";
        }
        return null;
    }

    /** Rule 4: a candidate stays clear of every value with one, two or three
     *  significant digits by more than the lobe guard, or the quantisation
     *  step where that is coarser.  A frequency pulled onto a round value by a
     *  re-typed setting or a display's decimals must land OUTSIDE the pilot's
     *  own lobe, so that the pull reads as a lost pilot and not as a valid one
     *  at a wrong bin.  A digit level whose grid is finer than twice that
     *  guard says nothing - every candidate is equally near one of its values
     *  - and is skipped. */
    private String roundValueRule(int k, double guard) {
        double binHz  = captureRateHz / fftSize;
        double f2     = k * binHz;
        double clear  = Math.max(guard * binHz, QUANTISATION_STEP_HZ);
        int    decade = (int) Math.floor(Math.log10(f2));
        for (int digits = 1; digits <= ROUND_DIGITS; digits++) {
            double spacing = Math.pow(10.0, decade - digits + 1.0);
            if (spacing / 2.0 <= clear) {
                continue;
            }
            if (Math.abs(f2 - Math.rint(f2 / spacing) * spacing) <= clear) {
                return "rule 4, within the guard of a value with " + digits
                        + " significant digits";
            }
        }
        return null;
    }

    /** The two families the FIRST pilot adds for the second: its harmonics in
     *  the candidate's lobe (rule 6), and the products of the two pilots up to
     *  total order three in any lobe (rule 7).  The first pilot's own line is
     *  in {@code ladder} already, which is what puts rule 5 against it. */
    private String pairRule(int k, int[] ladder, double guard, int first) {
        long   nyquist = fftSize;                 // in half-bins
        double guard2  = 2.0 * guard;
        long   kHalf   = 2L * k;
        for (int m = 1; m <= PILOT_ORDERS; m++) {
            long raw  = 2L * m * first;
            long line = fold(raw);
            if (Math.abs(line - kHalf) <= guard2 + widening(raw > nyquist, raw, kHalf)) {
                return "rule 6, a harmonic of the first pilot in the pilot's lobe";
            }
        }
        /**
         * Rule 7: no product of the pilot with a fundamental, to total order
         * three, may land in a protected lobe or in the pilot's own.
         *
         * <p>The pair is elected one fundamental bin apart, so each pilot IS the
         * second-order sum of the other with the fundamental and this rule refuses
         * every such pair.  It is {@link #RULE_7_PAUSED} while that spacing is
         * measured on the bench.
        for (int p = 0; p < IMD_PILOT_COEF.length; p++) {
            long    raw    = Math.abs(IMD_PILOT_COEF[p] * kHalf + 2L * IMD_FUND_COEF[p] * first);
            long    line   = fold(raw);
            boolean folded = raw > nyquist;
            // The pair is elected one fundamental bin apart, so its own
            // DIFFERENCE product falls on the fundamental's line by
            // construction - that is what the spacing is for, and it cannot be
            // a reason to refuse the pair.  It is a second-order product of
            // two lines about 110 dB down, far under the fundamental it lands
            // on, and it carries the pilots' difference as its phase, so what
            // it can do is pull the measured drift slightly towards zero.
            boolean ownDifference = IMD_PILOT_COEF[p] == 1 && IMD_FUND_COEF[p] == -1
                    && line == 2L * fundamentalBins[0];
            for (int protectedLine : ladder) {
                long target = 2L * protectedLine;
                if (ownDifference && target == 2L * fundamentalBins[0]) {
                    continue;
                }
                if (Math.abs(line - target) <= guard2 + widening(folded, raw, target)) {
                    return "rule 7, a product of the two pilots in a lobe";
                }
            }
            if (Math.abs(line - kHalf) <= guard2 + widening(folded, raw, kHalf)) {
                return "rule 7, a product of the two pilots in a lobe";
            }
        }
        */
        return null;
    }

    /**
     * The one bin that may stand beside {@code first}: the bin exactly the
     * FUNDAMENTAL'S OWN BIN below it.  At that spacing the two pilots' phase
     * difference is identically the fundamental's share of the advance, so the
     * drift is read straight off it with no whole turns to recover; at any
     * other spacing the difference has to be scaled by a fraction, and the
     * unknown whole turns inside it no longer wrap away.  The companion is
     * therefore constructed, not searched, and it is admitted only if it holds
     * against the ladder the first pilot has joined, stands clear of that
     * pilot's own harmonics and products, and lies outside the window's
     * leakage.
     *
     * <p>Returns {@link #NO_BIN} when the constructed bin fails any of those,
     * which leaves the single-pilot election in force.
     */
    private int companionAtFundamentalSpacing(int first, int[] ladder, double guard,
                                              int separation) {
        int second = first - fundamentalBins[0];
        if (second < 1 || Math.abs(first - second) < separation
                || knockOut(second, ladder, guard) != null
                || pairRule(second, ladder, guard, first) != null) {
            return NO_BIN;
        }
        return second;
    }

    /** The candidates a pair can be built on: those whose companion, the bin
     *  one fundamental below, is admissible beside them. */
    private int[] companionClass(int[] candidates, int[] ladder, double guard, int separation) {
        int[] kept = new int[candidates.length];
        int   n    = 0;
        for (int k : candidates) {
            if (companionAtFundamentalSpacing(k, withLine(ladder, k), guard, separation) > 0) {
                kept[n++] = k;
            }
        }
        return Arrays.copyOf(kept, n);
    }

    /** Why the bin one fundamental below the first pilot cannot stand beside
     *  it. */
    private String companionSpacingReason(int first, int[] ladder, double guard,
                                          int separation) {
        int second = first - fundamentalBins[0];
        if (second < 1) {
            return String.format(Locale.ROOT,
                    "no second pilot: one fundamental below bin %d falls to bin %d, under the "
                            + "first bin", first, second);
        }
        if (Math.abs(first - second) < separation) {
            return String.format(Locale.ROOT,
                    "no second pilot: the fundamental's %d bins are inside the %d bins the "
                            + "window's own leakage needs", fundamentalBins[0], separation);
        }
        String rule = knockOut(second, ladder, guard);
        if (rule == null) {
            rule = pairRule(second, ladder, guard, first);
        }
        return String.format(Locale.ROOT,
                "no second pilot: bin %d, one fundamental below the first, falls to %s",
                second, rule);
    }

    /** Steps 1 to 4: they only ORDER the survivors, each keeping the best
     *  class and none re-admitting a rejected bin, and the last leaves exactly
     *  one.  Step 1 is skipped below the fundamental. */
    private int elect(int[] candidates, boolean lowerBand, int[] ladder) {
        int[] kept = lowerBand ? candidates : halfwayClass(candidates);
        kept = longestWalkClass(kept);
        kept = widestClearanceClass(kept, ladder);
        int best = kept[0];
        for (int k : kept) {
            if (k > best) {
                best = k;
            }
        }
        return best;
    }

    /** Step 1: the survivors halfway between two harmonics of the
     *  fundamental, to within a tolerance that widens until something
     *  survives.  A product with one pilot factor inherits that offset and
     *  sits halfway too, which is where nothing of the ladder is. */
    private int[] halfwayClass(int[] candidates) {
        int k0 = fundamentalBins[0];
        for (double tolerance : HALFWAY_TOLERANCES) {
            int[] kept = new int[candidates.length];
            int   n    = 0;
            for (int k : candidates) {
                if (Math.abs(k % k0 - k0 / 2.0) <= tolerance * k0) {
                    kept[n++] = k;
                }
            }
            if (n > 0) {
                return Arrays.copyOf(kept, n);
            }
        }
        return candidates;      // the step is dropped, never weakened further
    }

    /** Step 2: the longest sample walk {@code N / gcd(N, k)} - a tone on an
     *  odd bin of a power-of-two transform visits every sample phase before
     *  its pattern repeats and spreads the converter's code errors over all
     *  bins, where an even bin concentrates them on a comb.  Odd bins hold
     *  the longest walk there is, so keeping the longest walk IS keeping the
     *  odd bins wherever one exists. */
    private int[] longestWalkClass(int[] candidates) {
        long best = 0;
        for (int k : candidates) {
            best = Math.max(best, fftSize / gcd(fftSize, k));
        }
        int[] kept = new int[candidates.length];
        int   n    = 0;
        for (int k : candidates) {
            if (fftSize / gcd(fftSize, k) == best) {
                kept[n++] = k;
            }
        }
        return Arrays.copyOf(kept, n);
    }

    /** Step 3: the widest clearance, compared at {@value #CLEARANCE_FIGURES}
     *  significant figures so that near-equal margins count as equal and do
     *  not decide. */
    private int[] widestClearanceClass(int[] candidates, int[] ladder) {
        double[] clearance = new double[candidates.length];
        double   best      = -1.0;
        for (int i = 0; i < candidates.length; i++) {
            clearance[i] = significant(clearanceBins(candidates[i], ladder), CLEARANCE_FIGURES);
            best = Math.max(best, clearance[i]);
        }
        int[] kept = new int[candidates.length];
        int   n    = 0;
        for (int i = 0; i < candidates.length; i++) {
            if (clearance[i] == best) {
                kept[n++] = candidates[i];
            }
        }
        return Arrays.copyOf(kept, n);
    }

    /** The smallest distance in bins, after folding, from a candidate to
     *  anything that matters: its own harmonics to every protected line
     *  (family 1) and to its own bin (family 2), the fundamental's harmonics
     *  the converter can deliver to its bin (family 3), and the products of
     *  rule 7 to both (family 4). */
    private double clearanceBins(int k, int[] ladder) {
        double binHz   = captureRateHz / fftSize;
        long   kHalf   = 2L * k;
        long   closest = Long.MAX_VALUE;
        for (int m = 1; m <= PILOT_ORDERS; m++) {
            long line = fold(m * kHalf);
            for (int p : ladder) {
                closest = Math.min(closest, Math.abs(line - 2L * p));
            }
            if (m >= 2) {
                closest = Math.min(closest, Math.abs(line - kHalf));
            }
        }
        for (int tone : fundamentalBins) {
            int orders = (int) Math.floor(antiAliasHz / (tone * binHz));
            for (int n = 1; n <= orders; n++) {
                closest = Math.min(closest, Math.abs(fold(2L * n * tone) - kHalf));
            }
            for (int p = 0; p < IMD_PILOT_COEF.length; p++) {
                long line = fold(Math.abs(IMD_PILOT_COEF[p] * kHalf + 2L * IMD_FUND_COEF[p] * tone));
                for (int q : ladder) {
                    closest = Math.min(closest, Math.abs(line - 2L * q));
                }
                closest = Math.min(closest, Math.abs(line - kHalf));
            }
        }
        return closest / 2.0;
    }

    /** {@code value} rounded to {@code figures} significant figures, halves to
     *  even. */
    private double significant(double value, int figures) {
        if (!(value > 0.0)) {
            return value;
        }
        double scale = Math.pow(10.0, figures - 1 - Math.floor(Math.log10(value)));
        return Math.rint(value * scale) / scale;
    }

    /** The protected lines with one more line among them, ascending. */
    private int[] withLine(int[] ladder, int line) {
        int[] extended = Arrays.copyOf(ladder, ladder.length + 1);
        extended[ladder.length] = line;
        Arrays.sort(extended);
        return extended;
    }

    private long gcd(long a, long b) {
        long x = Math.abs(a);
        long y = Math.abs(b);
        while (y != 0) {
            long r = x % y;
            x = y;
            y = r;
        }
        return x;
    }

    // ---------------------------------------------------------------- rules

    /**
     * The protected lines in bins, ascending and without duplicates: the
     * harmonic orders 1..H of every fundamental tone and, with two tones,
     * every intermodulation product the dual-tone readout evaluates.  A
     * product beyond Nyquist is folded, where the analysis would read it.
     */
    private int[] protectedLines() {
        int tones = fundamentalBins.length;
        int[] lines = new int[tones * highestProtectedOrder
                + (tones == 2 ? DUAL_LOW_COEF.length : 0)];
        int at = 0;
        for (int tone : fundamentalBins) {
            for (int n = 1; n <= highestProtectedOrder; n++) {
                lines[at++] = n * tone;
            }
        }
        if (tones == 2) {
            long low  = fundamentalBins[0];
            long high = fundamentalBins[1];
            for (int p = 0; p < DUAL_LOW_COEF.length; p++) {
                lines[at++] = (int) fold(2L * Math.abs(DUAL_LOW_COEF[p] * low
                        + DUAL_HIGH_COEF[p] * high)) / 2;
            }
        }
        Arrays.sort(lines);
        int kept = 0;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i] > 0 && (kept == 0 || lines[i] != lines[kept - 1])) {
                lines[kept++] = lines[i];
            }
        }
        return Arrays.copyOf(lines, kept);
    }

    /**
     * The rule a candidate at {@code halfBins} half-bins fails, or
     * {@code null} when it passes every one.  Everything is measured in
     * half-bin units - the candidate, the ladder, the device's harmonics, the
     * guard - so that a half-bin candidate is a whole number like every
     * line it is tested against.
     */
    private String failedRule(long halfBins, int[] ladder, double guard) {
        long   nyquist = fftSize;                     // in half-bins
        double guard2  = 2.0 * guard;
        int[]  ladder2 = new int[ladder.length];
        for (int i = 0; i < ladder.length; i++) {
            ladder2[i] = 2 * ladder[i];
        }
        double binHz = captureRateHz / fftSize;

        for (int m = 1; m <= PILOT_ORDERS; m++) {
            long    raw    = m * halfBins;
            long    line   = fold(raw);
            boolean folded = raw > nyquist;
            for (int p : ladder2) {
                if (Math.abs(line - p) <= guard2 + widening(folded, raw, p)) {
                    return "rule 5, a pilot harmonic in a protected lobe";
                }
            }
            if (m >= 2 && Math.abs(line - halfBins) <= guard2 + widening(folded, raw, halfBins)) {
                return "rule 6, a pilot harmonic in the pilot's own lobe";
            }
        }
        for (int tone : fundamentalBins) {
            int orders = (int) Math.floor(antiAliasHz / (tone * binHz));
            for (int n = 1; n <= orders; n++) {
                long raw  = 2L * n * tone;
                long line = fold(raw);
                if (Math.abs(line - halfBins) <= guard2 + widening(raw > nyquist, raw, halfBins)) {
                    return "rule 6, a harmonic of the fundamental in the pilot's lobe";
                }
            }
        }
        /**
         * Rule 7: no product of the pilot with a fundamental, to total order
         * three, may land in a protected lobe or in the pilot's own.
         *
         * <p>The pair is elected one fundamental bin apart, so each pilot IS the
         * second-order sum of the other with the fundamental and this rule refuses
         * every such pair.  It is {@link #RULE_7_PAUSED} while that spacing is
         * measured on the bench.
        for (int tone : fundamentalBins) {
            for (int p = 0; p < IMD_PILOT_COEF.length; p++) {
                long    raw    = Math.abs(IMD_PILOT_COEF[p] * halfBins + 2L * IMD_FUND_COEF[p] * tone);
                long    line   = fold(raw);
                boolean folded = raw > nyquist;
                for (int protectedLine : ladder2) {
                    if (Math.abs(line - protectedLine) <= guard2 + widening(folded, raw, protectedLine)) {
                        return "rule 7, an intermodulation product in a lobe";
                    }
                }
                if (Math.abs(line - halfBins) <= guard2 + widening(folded, raw, halfBins)) {
                    return "rule 7, an intermodulation product in a lobe";
                }
            }
        }
        */
        return null;
    }

    /** Extra guard a FOLDED line needs against a target: a relative time-base
     *  error moves the two towards each other by the error times the SUM of
     *  their positions, where two unfolded lines keep their separation.  In
     *  half-bin units like everything the rules compare. */
    private double widening(boolean folded, long rawHalfBins, long targetHalfBins) {
        return folded ? lockResidual * (rawHalfBins + targetHalfBins) : 0.0;
    }

    /** Where a line of this many half-bins appears: wrapped into one period
     *  of the transform and mirrored about Nyquist, in half-bins. */
    private long fold(long rawHalfBins) {
        long period = 2L * fftSize;
        long b = Math.abs(rawHalfBins) % period;
        return b > fftSize ? period - b : b;
    }
}
