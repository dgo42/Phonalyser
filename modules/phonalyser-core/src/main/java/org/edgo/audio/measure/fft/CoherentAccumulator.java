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

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.IntToDoubleFunction;

import org.edgo.audio.measure.dsp.ToneLobeLift;
import org.edgo.audio.measure.fft.FftResult.LineState;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * The average of FFT frames over a collection: the per-bin sums, the frames
 * they hold, and the rotation that aligns every frame to the collection's
 * first before it is added.  One instance serves a live collection, fed one
 * frame per analysis cycle, and an offline one, fed the frames of a capture.
 *
 * <h2>Coherent rotation</h2>
 * A frame starting {@code delta} samples after the reference frame carries
 * every line {@code delta} samples further along.  Turning bin {@code k} back
 * by {@code 2π·k·delta/N} undoes that for a line at the bin's own frequency,
 * so the whole spectrum turns in proportion to the bin.  A windowed line does
 * not sit on one bin: the window function spreads it over its main lobe, the
 * hump of {@code lobeHalfBins} bins to either side of the line, and every bin
 * of that hump advances by the LINE's angle, not its own.  Under the
 * proportional rotation the hump's off-centre bins would be mis-turned by
 * {@code 2π·m·delta/N} for a bin {@code m} off the centre, and an overlapped
 * deep average would carve the hump into a comb.  So the bins inside a known
 * line's hump turn by that line's angle instead: {@code h·Φ1} for harmonic
 * {@code h} of the fundamental at bin {@code κ}, {@code a·Φ1 + b·Φ2} for an
 * intermodulation product of a dual pair, where {@code Φ1 = 2π·κ·delta/N} and
 * {@code Φ2} is the second tone's turn.  The known lines are the coherent
 * family: the harmonic ladder of the pinned fundamental and, for a dual pair,
 * the published product grid.  Nothing is read off the spectrum to find them.
 *
 * <p>The fundamental's own hump takes a further {@code -phiF}, the phase
 * excess the notch gives the fundamental and nothing else; the second tone's
 * hump takes {@code -phiF2} the same way.  A pilot line - a generated line
 * on a whole bin that the alignment reads its time base from - is coherent
 * with nothing but itself, so its hump turns by the turn its own line made
 * since the reference frame as the caller read it, or, where no reading
 * placed it, by its bin's turn over the counted advance.  Outside every hump
 * the proportional rotation holds for every window function alike; a line
 * that is stable in the capture's clock and stands on a bin keeps its phase
 * under it, a line that wanders or stands between bins does not and
 * averages away.
 *
 * <h2>The fundamental's fractional bin</h2>
 * One frame pins {@code κ} to the peak's parabola, ~0.01-0.03 bin for a
 * tapered window; a residual {@code δκ} ramps the turned-back fundamental's
 * phase by {@code 2π·δκ·delta/N} and vector-cancels a deep average, harmonic
 * {@code h} {@code h} times faster.  So the fundamental's phase, turned back
 * by the pinned {@code κ} and less its excess, is read on every frame added,
 * and when the measurement closes, after {@link #KAPPA_MEAS_FRAMES} frames,
 * the slope of that phase over the advance - {@code 2π·δκ/N} - corrects
 * {@code κ} once, ~100 times tighter than the parabola; {@code κ} then
 * stands for the rest of the collection.  A slope
 * beyond {@link #KAPPA_REFINE_MAX_BIN} is no tone's residual but the random
 * walk of a noise peak pinned as the fundamental; the measurement is
 * discarded and starts over.  A step across a re-sync of the capture (the
 * advance jumped) is not a measurement either: {@link #reanchorKappa}
 * makes the next frame a fresh anchor.
 *
 * <h2>Lines reset on their own</h2>
 * Beside the global frame count every line of the family but the two tones
 * and the pilots keeps a local count: the frames its local region holds since
 * the line's own last reset, the global frame count while it was never reset.
 * {@link #resetLine} empties one line's lobe and its local count and leaves
 * the rest of the average running; the overlay divides that lobe by the
 * line's local count.  A line is reset only once κ is refined: until then
 * the refinement rebuilds the family on the corrected κ, which moves every
 * lobe.
 *
 * <p>Once {@link #lineBands} lays them out, H2 .. H9 and every product also
 * keep a local band: their own sums of the bins beside the lobe that the
 * line's band top is read from, emptied with the lobe by a reset and
 * otherwise fed, scaled and taken out exactly as the global sums of those
 * bins are.  The band top - the level a line must stand clear of - so
 * restarts and falls with the line instead of carrying the frames from
 * before its reset.
 *
 * <h2>Statistics</h2>
 * Every frame the coherent add takes in leaves one row in the collection's
 * statistics, always and with no bound: the global frame count, and for the
 * tones, H2 .. H9 and every product the frame's and the average's magnitude
 * and phase, and for every line but a tone the band top over the frame's
 * band bins and over the line's band sums.  A restart or a reset drops the
 * rows; they are read by copy or exported to a file
 * ({@link #exportStatistics}).
 *
 * <p>The incoherent average sums each bin's power with no rotation.
 */
@Log4j2
public final class CoherentAccumulator {

    /** Frames of turned-back fundamental phase the κ measurement spans
     *  before it closes - ~24 give κ to ~1e-4 bin at high SNR, landing before
     *  the ramp of the parabola's residual becomes visible (~30 frames). */
    static final int KAPPA_MEAS_FRAMES = 24;
    /** Largest correction a κ re-pin may apply, in bins.  The parabola's
     *  residual is ~0.01-0.03 bin; a slope asking for more than half a bin is
     *  the random-walk phase of a noise peak pinned as the fundamental (no
     *  generator, a muted DUT).  Folding it drove κ toward zero, where the
     *  harmonic ladder h·κ < halfSize runs to millions of members. */
    static final double KAPPA_REFINE_MAX_BIN = 0.5;
    /** The highest harmonic the statistics and the local bands follow beside
     *  the tones and the products: H2 .. H9, the ladder the distortion
     *  readouts and the analyser's harmonic trace are built on.  The ladder
     *  itself runs to the Nyquist bin - over a thousand lines on a low tone,
     *  millions on a noise peak pinned near one bin - and a statistics row
     *  and a band of sums per line over all of them would not fit. */
    static final int STATS_MAX_HARMONIC = 9;
    /** Rows the statistics store is first sized for; it doubles from there. */
    private static final int    STATS_INITIAL_ROWS = 64;
    /** The statistics columns of a tone - raw and accumulated magnitude,
     *  raw and accumulated phase - and of every other line, which adds the
     *  raw and the accumulated band top. */
    private static final int    STATS_TONE_COLUMNS = 4;
    private static final int    STATS_LINE_COLUMNS = 6;
    /** The statistics file's layout: semicolon-separated, decimal comma, an
     *  empty field where a value is not measured. */
    private static final String STATS_SEPARATOR    = ";";
    private static final String STATS_NUMBER       = "0.0000";
    private static final String STATS_FRAMES       = "frames";
    private static final String STATS_LEVEL_UNIT   = " dBFS";
    private static final String STATS_PHASE_UNIT   = " deg";
    /** The dBFS a level of no signal is written as, the overlay's floor. */
    private static final double STATS_NO_SIGNAL_DBFS = -300.0;
    /** The smallest amplitude a level is written for in dBFS. */
    private static final double STATS_MIN_AMPLITUDE  = 1e-15;

    /**
     * The turns one frame is turned back by, radians, and the advance they
     * belong to: {@code delta} is the frame's advance since the reference
     * frame in samples (fractional where a measured advance says so),
     * {@code phi2} the second tone's turn over it, {@code phiF} and
     * {@code phiF2} the two tones' phase excesses taken out of their humps,
     * {@code phiPilot1} and {@code phiPilot2} the two pilot lines' turns.  A
     * turn given as {@link Double#NaN} is counted from the advance - the
     * second tone's from its fractional bin, a pilot's from its whole bin -
     * where no reading of that line placed it.
     */
    public record FrameTurns(double delta, double phi2, double phiF, double phiF2,
                             double phiPilot1, double phiPilot2) {
        /** The turns of an advance of {@code delta} samples with every turn
         *  counted from it and no excess. */
        public static FrameTurns of(double delta) {
            return new FrameTurns(delta, Double.NaN, 0.0, 0.0, Double.NaN, Double.NaN);
        }
    }

    @Getter
    private int      fftSize;
    private int      halfSize;
    @Getter
    private boolean  coherent;
    /** The pinned fundamental's fractional bin; 0 while none is pinned, in
     *  which case a frame is added without any rotation. */
    @Getter
    private double   kappa;
    /** κ as {@link #restart} pinned it: the κ measurement reads every frame's
     *  fundamental phase turned back by THIS value, the one the slope is
     *  measured against. */
    private double   kappaPinned;
    /** True once the κ measurement has closed on {@link #KAPPA_MEAS_FRAMES}
     *  frames; κ then stands for the rest of the collection. */
    @Getter
    private boolean  kappaRefined;
    private int      kappaMeasFrames;   // frames the measurement spans (the anchor + the steps)
    private double   kappaLastDelta;    // advance at the running reference frame
    private double   kappaLastPhase;    // turned-back fundamental phase at that frame
    private double   kappaCumPhase;     // sum of the per-frame phase steps
    private double   kappaCleanSpan;    // sum of the per-frame advance steps (slope denominator)
    private boolean  kappaSkipNext;     // the next frame re-anchors; its step is not folded
    /** The second tone's fractional bin; NaN without a dual pair. */
    @Getter
    private double   kappa2;
    private int[]    productA;
    private int[]    productB;
    /** The two pilot lines' whole bins; 0 or less where none is held. */
    private int      pilotBin1;
    private int      pilotBin2;
    private int      lobeHalfBins;
    /** Per-bin sums over the frames held, bins 0 .. fftSize/2: the complex
     *  spectrum sum of the coherent average (the power sum null), the power
     *  sum of the incoherent one (the complex sums null). */
    @Getter
    private double[] sumRe;
    @Getter
    private double[] sumIm;
    @Getter
    private double[] sumPow;
    /** Frames held - the weight every sum is divided by for the mean.
     *  Volatile: a display thread reads the depth while the analysis thread
     *  adds. */
    @Getter
    private volatile double frames;
    /** True from {@link #restart} until {@link #reset}. */
    @Getter
    private boolean  started;
    /** The coherent family, sorted by position: each member's fractional
     *  bin, its (a, b) turn coefficients, which pilot it is (0 for none, 1 or
     *  2), the whole bins of its hump, and the phasor derived for the frame
     *  being added. */
    private int      memberCount;
    private double[] memberPos   = new double[0];
    private int[]    memberA     = new int[0];
    private int[]    memberB     = new int[0];
    private int[]    memberPilot = new int[0];
    private int[]    memberLo    = new int[0];
    private int[]    memberHi    = new int[0];
    private double[] memberCos   = new double[0];
    private double[] memberSin   = new double[0];
    /** Each family line's local count, index-parallel with the family's
     *  positions: the frames its local region holds since the line's own last
     *  reset, the global frame count while it was never reset - always for
     *  the tones and the pilots, which are never reset. */
    private double[]  localCount   = new double[0];
    /** Each family line's mark waiting for its reset, index-parallel the same
     *  way: set by {@link #markLineWaiting}, cleared by {@link #resetLine}. */
    private boolean[] waitingReset = new boolean[0];
    /** Guards the statistics store below: the analysis thread appends a row
     *  per frame added while another thread may read or export it. */
    private final Object statsLock = new Object();
    /** The collection's statistics, one row of {@link #statsWidth} values per
     *  frame {@link #add} adds, in the column order of
     *  {@link #statisticsColumns}; grown by doubling, dropped by
     *  {@link #restart} and {@link #reset}. */
    private double[]     statsRows = new double[0];
    private int          statsRowCount;
    private int          statsWidth = 1;
    /** The column names, the frame count first, and which columns hold a
     *  phase in degrees rather than a level or the count. */
    private List<String> statsColumns = List.of(STATS_FRAMES);
    private boolean[]    statsPhase   = new boolean[1];
    /** The statistics lines, fixed at the restart on the pinned family: the
     *  tones first ({@link #statsTones} of them, {@link #STATS_TONE_COLUMNS}
     *  columns each), then H2 .. {@link #STATS_MAX_HARMONIC} and every product
     *  in position order ({@link #STATS_LINE_COLUMNS} columns each), and the
     *  row being assembled.  Each line's coefficients, and its index in the
     *  family as it stands - found again by the coefficients after the
     *  family's rebuild at the κ refinement, -1 where it holds the line no
     *  longer.  Not guarded by {@link #statsLock}, which covers the rows,
     *  the column names and the phase flags alone: these arrays are written
     *  by {@link #restart}, {@link #reset} and the refinement inside
     *  {@link #add}, and read by {@link #add}, which all run on the one
     *  thread that feeds the accumulator. */
    private int          statsTones;
    private int[]        statsA     = new int[0];
    private int[]        statsB     = new int[0];
    private int[]        statsIndex = new int[0];
    private double[]     statsRow   = new double[0];
    /** Reads a line's local band top: median + 4 MAD of the band beside the
     *  line's lobe ({@link ToneLobeLift#lineFloor}). */
    private final ToneLobeLift lobe = new ToneLobeLift();
    /** The local bands' geometry as {@link #lineBands} set it: the half-width
     *  of a band window in bins (0 while no band is laid out), and the bin
     *  width and the window's noise bandwidth the band top is read with. */
    private int    bandSpan;
    private double bandBinHz;
    private double bandNenbw;
    /** Each family line's local band, index-parallel with the family's
     *  positions: the first bin of its window, the window's length (0 for a
     *  line with no band) and its offset into {@link #bandRe} /
     *  {@link #bandIm}, which hold the line's own complex sums of the
     *  window's bins since the line's last reset.  Two lines' windows may
     *  share bins; each keeps its own sums of them. */
    private int[]    bandLo  = new int[0];
    private int[]    bandLen = new int[0];
    private int[]    bandOff = new int[0];
    private double[] bandRe  = new double[0];
    private double[] bandIm  = new double[0];
    /** Every bin's contribution of the frame being added where the caller
     *  asks for none back: the local bands take the very contribution the
     *  global sums get. */
    private double[] scratchRe = new double[0];
    private double[] scratchIm = new double[0];

    /**
     * Starts a collection anew: empty sums for {@code fftSize}, the geometry
     * pinned for the whole collection, empty statistics on the lines of the
     * pinned family.
     *
     * @param fftSize      transform length; the sums cover bins 0 .. fftSize/2
     * @param coherent     complex sums with the rotation, or power sums
     * @param kappa        the fundamental's fractional bin, 0 for none
     * @param kappa2       the second tone's fractional bin, NaN for none
     * @param productA     the published product grid's coefficients of the
     *                     fundamental, index-parallel with {@code productB};
     *                     null or empty for none
     * @param productB     the grid's coefficients of the second tone
     * @param lobeHalfBins the window function's main-lobe half-width in bins
     * @param pilotBin1    the first pilot line's whole bin, 0 or less for none
     * @param pilotBin2    the second pilot line's whole bin, 0 or less for none
     */
    public void restart(int fftSize, boolean coherent, double kappa, double kappa2,
                        int[] productA, int[] productB, int lobeHalfBins,
                        int pilotBin1, int pilotBin2) {
        this.fftSize      = fftSize;
        this.halfSize     = fftSize / 2;
        this.coherent     = coherent;
        this.kappa2       = kappa2;
        this.productA     = productA != null ? productA.clone() : new int[0];
        this.productB     = productB != null ? productB.clone() : new int[0];
        this.pilotBin1    = pilotBin1;
        this.pilotBin2    = pilotBin2;
        this.lobeHalfBins = Math.max(0, lobeHalfBins);
        int n = halfSize + 1;
        sumRe  = coherent ? new double[n] : null;
        sumIm  = coherent ? new double[n] : null;
        sumPow = coherent ? null : new double[n];
        frames  = 0.0;
        started = true;
        kappaPinned     = kappa;
        kappaRefined    = false;
        kappaMeasFrames = 0;
        kappaSkipNext   = false;
        clearStats();
        dropBands();
        setKappa(kappa);
        layoutStats();
    }

    /** Re-pins the fundamental's fractional bin and rebuilds the family's
     *  positions on it; the statistics lines find their lines again, and
     *  the local bands, where laid out, move with them. */
    private void setKappa(double kappa) {
        this.kappa = kappa;
        buildFamily();
        mapStatsLines();
        if (bandSpan > 0) layoutBands();
    }

    /** The next frame added re-anchors the κ measurement instead of folding
     *  its step: for a frame whose advance jumped - a re-sync of the capture -
     *  so that a burst can neither poison nor starve the measurement. */
    public void reanchorKappa() {
        kappaSkipNext = true;
    }

    /** Drops the collection: no sums, no geometry, nothing pinned. */
    public void reset() {
        sumRe = sumIm = sumPow = null;
        frames       = 0.0;
        started      = false;
        memberCount  = 0;
        kappa        = 0.0;
        kappa2       = Double.NaN;
        kappaRefined = false;
        clearStats();
        dropBands();
    }

    /**
     * Restarts the average of line ({@code a}, {@code b}) alone: the sums of
     * its lobe - the bins the add turns by the line's own angle
     * ({@link #ownedLo}) - are emptied, its local count is set to zero and
     * its waiting mark is cleared; the global frame count, κ, κ2, the family,
     * the pilots and every other line, a neighbour sharing a lobe bin
     * included, stay as they are.  From here the lobe holds the frames added
     * after the reset alone, and the overlay divides it by the line's own
     * local count.
     *
     * <p>Refused while κ is not refined: the refinement rebuilds the family
     * on the corrected κ, which moves every lobe and starts every local
     * count on the global one again.  A collection dropped by {@link #reset}
     * or not yet refined after a {@link #restart} refuses the same way.
     *
     * @return true when the line was reset, false when refused
     * @throws IllegalArgumentException for the fundamental (1, 0), the second
     *         tone (0, 1) or a pilot (0, 0), always; for a line the refined
     *         family does not hold
     */
    public boolean resetLine(int a, int b) {
        rejectNeverReset(a, b);
        if (refusedBeforeKappaRefined("reset", a, b)) return false;
        int m = familyLine(a, b);
        // κ is refined by the coherent add alone, so only the complex sums
        // can hold a line to reset.
        Arrays.fill(sumRe, ownedLo(m), memberHi[m] + 1, 0.0);
        Arrays.fill(sumIm, ownedLo(m), memberHi[m] + 1, 0.0);
        if (bandLen[m] > 0) {
            Arrays.fill(bandRe, bandOff[m], bandOff[m] + bandLen[m], 0.0);
            Arrays.fill(bandIm, bandOff[m], bandOff[m] + bandLen[m], 0.0);
        }
        localCount[m]   = 0.0;
        waitingReset[m] = false;
        return true;
    }

    /**
     * Marks line ({@code a}, {@code b}) waiting for its reset: its restart is
     * requested but not yet due, and the mark travels on every result the
     * overlay stamps until {@link #resetLine} clears it.  Refused while κ is
     * not refined, as the reset is: a mark set before would not survive the
     * refinement's rebuild of the family.
     *
     * @return true when the line was marked, false when refused
     * @throws IllegalArgumentException as {@link #resetLine} throws
     */
    public boolean markLineWaiting(int a, int b) {
        rejectNeverReset(a, b);
        if (refusedBeforeKappaRefined("waiting mark", a, b)) return false;
        waitingReset[familyLine(a, b)] = true;
        return true;
    }

    /** Throws for the coefficients of a line that is never reset - the
     *  fundamental (1, 0), the second tone (0, 1), a pilot (0, 0) - whatever
     *  the family holds. */
    private void rejectNeverReset(int a, int b) {
        if (neverReset(a, b)) {
            throw new IllegalArgumentException("Line (" + a + ", " + b
                    + ") is a tone or a pilot, which are never reset");
        }
    }

    /** The family index of line ({@code a}, {@code b}), a line other than a
     *  pilot. */
    private int familyLine(int a, int b) {
        int m = lineIndex(a, b);
        if (m < 0) {
            throw new IllegalArgumentException("Line (" + a + ", " + b + ") is not in the coherent family");
        }
        return m;
    }

    /** True for the coefficients of the fundamental (1, 0), the second tone
     *  (0, 1) and a pilot (0, 0): the lines that are never reset. */
    private boolean neverReset(int a, int b) {
        return (a == 1 && b == 0) || (a == 0 && b == 1) || (a == 0 && b == 0);
    }

    /** True for a family line a reset may name: every line but the
     *  fundamental (1, 0), the second tone (0, 1) and the pilots. */
    private boolean resettable(int m) {
        return memberPilot[m] == 0 && !neverReset(memberA[m], memberB[m]);
    }

    /** The lowest bin of family line {@code m}'s lobe that the add turns by
     *  that line's angle: a bin two lobes share goes to the lower line (the
     *  lobe walk in {@link #add}), so the line owns its lobe from just above
     *  the lower neighbour's top on.  The family's tops rise with its sorted
     *  positions, the half-width being common. */
    private int ownedLo(int m) {
        return m > 0 ? Math.max(memberLo[m], memberHi[m - 1] + 1) : memberLo[m];
    }

    /** True, with one INFO line, while κ is not refined and the line
     *  {@code action} on ({@code a}, {@code b}) is refused. */
    private boolean refusedBeforeKappaRefined(String action, int a, int b) {
        if (kappaRefined) return false;
        if (log.isInfoEnabled()) {
            log.info("FFT line {} of ({}, {}) refused: kappa is not refined yet", action, a, b);
        }
        return true;
    }

    /**
     * Lays out each line's local band for the collection.  Every line the
     * statistics follow ({@link #followed}: H2 .. {@link #STATS_MAX_HARMONIC}
     * and every product) gets the window of bins
     * {@link ToneLobeLift#lineFloor} reads its band top from:
     * {@link ToneLobeLift#lineFloorSpanBins} to either side of its centre
     * bin, the lobe exclusion inside it left to the band top to skip.  From
     * here on the line keeps its own sums of that window since its last
     * reset; a line reset empties them with the lobe, and the overlay and the
     * statistics read the line's band top from them.
     *
     * <p>A band starts from the global sums of its bins, which is what a line
     * never reset holds - so the call belongs after {@link #restart}, before
     * any line reset - and the bands are laid out again on the family the κ
     * refinement rebuilds.  A restart or a reset drops them.  Nothing is laid
     * out on an incoherent average or for a bin width that is not positive.
     *
     * @param binHz     the spectrum's bin width in Hz
     * @param nenbwBins the window's equivalent noise bandwidth in bins
     */
    public void lineBands(double binHz, double nenbwBins) {
        if (!started || !coherent || !(binHz > 0.0)) return;
        bandBinHz = binHz;
        bandNenbw = nenbwBins;
        bandSpan  = lobe.lineFloorSpanBins(binHz, nenbwBins);
        layoutBands();
    }

    /** Lays each followed line's band window on the family as it stands,
     *  its sums taken from the global sums of its bins. */
    private void layoutBands() {
        int total = 0;
        for (int m = 0; m < memberCount; m++) {
            bandLen[m] = 0;
            if (!followed(m)) continue;
            int bin = centreBin(m);
            int lo  = Math.max(1, bin - bandSpan);
            int hi  = Math.min(halfSize, bin + bandSpan);
            if (hi < lo) continue;
            bandLo[m]  = lo;
            bandLen[m] = hi - lo + 1;
            bandOff[m] = total;
            total     += bandLen[m];
        }
        bandRe = new double[total];
        bandIm = new double[total];
        for (int m = 0; m < memberCount; m++) {
            if (bandLen[m] == 0) continue;
            System.arraycopy(sumRe, bandLo[m], bandRe, bandOff[m], bandLen[m]);
            System.arraycopy(sumIm, bandLo[m], bandIm, bandOff[m], bandLen[m]);
        }
        if (total > 0 && scratchRe.length != halfSize + 1) {
            scratchRe = new double[halfSize + 1];
            scratchIm = new double[halfSize + 1];
        }
    }

    /** Drops the local bands: none is laid out until the next
     *  {@link #lineBands}. */
    private void dropBands() {
        bandSpan  = 0;
        bandRe    = new double[0];
        bandIm    = new double[0];
        scratchRe = new double[0];
        scratchIm = new double[0];
    }

    /** Folds {@code sign} times the contribution {@code cRe} / {@code cIm}
     *  - every bin's, as the global sums took or gave it back - into each
     *  local band, bin for bin: +1 for a frame added, -1 for a frame taken
     *  out.  A line never reset so keeps its band equal to the global sums
     *  of its bins. */
    private void foldIntoBands(double[] cRe, double[] cIm, double sign) {
        for (int m = 0; m < memberCount; m++) {
            int len = bandLen[m];
            if (len == 0) continue;
            int lo  = bandLo[m];
            int off = bandOff[m];
            for (int j = 0; j < len; j++) {
                bandRe[off + j] += sign * cRe[lo + j];
                bandIm[off + j] += sign * cIm[lo + j];
            }
        }
    }

    /** Family line {@code m}'s accumulated local band top: the band top over
     *  its band sums divided by its local count, at {@code conv} per
     *  magnitude; 0 where the line has no band, holds no frame yet, or the
     *  band top reads none. */
    private double bandTop(int m, double conv) {
        double local = localCount[m];
        if (!(local > 0.0)) return 0.0;
        final double   mean = 1.0 / local;
        final int      lo   = bandLo[m];
        final int      off  = bandOff[m];
        final double[] bRe  = bandRe;
        final double[] bIm  = bandIm;
        return bandFloor(m, k -> amplitude(bRe[off + k - lo], bIm[off + k - lo], mean, conv, k));
    }

    /** Family line {@code m}'s raw local band top: the band top over the
     *  frame {@code re} / {@code im}'s own magnitudes in the line's band
     *  window, in the transform's unit; 0 where the line has no band or the
     *  band top reads none. */
    private double rawBandTop(int m, double[] re, double[] im) {
        return bandFloor(m, k -> amplitude(re[k], im[k], 1.0, 1.0, k));
    }

    /** {@link ToneLobeLift#lineFloor} over the magnitudes {@code mag} gives
     *  for family line {@code m}'s band window: median + 4 MAD of the bins
     *  beside its lobe; 0 where the line has no band. */
    private double bandFloor(int m, IntToDoubleFunction mag) {
        if (bandLen[m] == 0) return 0.0;
        return lobe.lineFloor(mag, centreBin(m), halfSize, bandBinHz, bandNenbw);
    }

    /** The amplitude of a bin's sum {@code sRe}, {@code sIm} times
     *  {@code mean}, at {@code conv} per magnitude - half that at DC and
     *  Nyquist, as the overlay writes the amplitude. */
    private double amplitude(double sRe, double sIm, double mean, double conv, int k) {
        double avgRe = sRe * mean;
        double avgIm = sIm * mean;
        return Math.sqrt(avgRe * avgRe + avgIm * avgIm) * conv * ((k == 0 || k == halfSize) ? 0.5 : 1.0);
    }

    /** True for a family line the statistics and the local bands follow: a
     *  line a reset may name, H2 .. {@link #STATS_MAX_HARMONIC} or a
     *  product. */
    private boolean followed(int m) {
        return resettable(m) && (memberB[m] != 0 || memberA[m] <= STATS_MAX_HARMONIC);
    }

    /** Family line {@code m}'s centre bin: its position rounded, clamped to
     *  the spectrum. */
    private int centreBin(int m) {
        return Math.min(halfSize, Math.max(0, (int) Math.round(memberPos[m])));
    }

    /** The fundamental's turn over an advance of {@code delta} samples,
     *  {@code -2π·κ·delta/N} folded to one turn - the angle the fundamental's
     *  hump is turned back by, and the base of every member's angle. */
    public double fundamentalAngle(double delta) {
        return kappa > 0.0 ? binAngle(kappa, delta) : 0.0;
    }

    /** The second tone's turn over a COUNTED advance of {@code delta} samples,
     *  {@code -2π·κ2·delta/N} folded to one turn - the {@code phi2} of
     *  {@link #add} where no reading of the tone places it; 0 without a
     *  dual pair. */
    private double secondToneAngle(double delta) {
        return kappa2 > 0.0 ? binAngle(kappa2, delta) : 0.0;
    }

    /** The turn a line at fractional bin {@code bin} is turned back by over
     *  an advance of {@code delta} samples, {@code -2π·bin·delta/N} folded to
     *  one turn. */
    private double binAngle(double bin, double delta) {
        return -2.0 * Math.PI * Math.IEEEremainder(delta * bin, fftSize) / fftSize;
    }

    /**
     * One frame's reading for the κ measurement of the class comment: the
     * fundamental bin's phase turned back by the PINNED κ over {@code delta},
     * less the excess {@code phiF}; its step from the previous reading folds
     * into the slope, and when the measurement closes the slope corrects κ
     * once.  Under an advance that was placed by the fundamental's own turn
     * (the aligning modes) the reading stands still and κ stays where it was
     * pinned.
     */
    private void measureKappa(double[] re, double[] im, double delta, double phiF) {
        if (kappaRefined) return;
        int    k0   = Math.min(halfSize, (int) Math.round(kappaPinned));
        double krot = binAngle(kappaPinned, delta);
        double cr   = Math.cos(krot), sr = Math.sin(krot);
        double phase = Math.atan2(re[k0] * sr + im[k0] * cr, re[k0] * cr - im[k0] * sr) - phiF;
        if (kappaMeasFrames == 0 || kappaSkipNext) {
            if (kappaMeasFrames == 0) {
                kappaCumPhase   = 0.0;
                kappaCleanSpan  = 0.0;
                kappaMeasFrames = 1;
            }
            kappaLastPhase = phase;
            kappaLastDelta = delta;
            kappaSkipNext  = false;
            return;
        }
        kappaCumPhase  += Math.IEEEremainder(phase - kappaLastPhase, 2.0 * Math.PI);
        kappaCleanSpan += delta - kappaLastDelta;
        kappaLastPhase  = phase;
        kappaLastDelta  = delta;
        if (++kappaMeasFrames < KAPPA_MEAS_FRAMES || !(kappaCleanSpan > 0.0)) return;
        double dKappa = kappaCumPhase * fftSize / (2.0 * Math.PI * kappaCleanSpan);
        if (Math.abs(dKappa) > KAPPA_REFINE_MAX_BIN) {
            // Not a tone's residual - the measurement starts over; a later
            // clean stretch (the generator coming on) can still correct κ.
            kappaMeasFrames = 0;
            if (log.isInfoEnabled()) {
                log.info("FFT kappa measurement discarded: d_kappa={} bin exceeds {} - no tone at the pinned fundamental",
                        String.format(Locale.US, "%.5f", dKappa), KAPPA_REFINE_MAX_BIN);
            }
            return;
        }
        setKappa(kappaPinned + dKappa);
        kappaRefined = true;
        if (log.isInfoEnabled()) {
            log.info("FFT kappa refined from the phase slope over {} frames: d_kappa={} bin -> kappa={}",
                    kappaMeasFrames, String.format(Locale.US, "%.5f", dKappa),
                    String.format(Locale.US, "%.5f", kappa));
        }
    }

    /**
     * Writes the average the sums hold onto {@code r}: the mean phasor per
     * bin into {@code re} / {@code im} (the mean magnitude into {@code re}
     * for the incoherent average, the analyser's convention), the amplitude
     * in dBFS and the phase in degrees, the fundamental's level scalars from
     * the bin just written, and the frames held as the result's frame count.
     * The amplitude scale is the result's own window gain, so an average and
     * the single frame the analyser wrote read on one scale.  The statistics
     * on the average are then {@link FftAnalyzer#recomputeStats}'s.
     *
     * <p>The lobe of a line reset since the collection began - the bins the
     * add turns by that line's angle - is divided by that line's own local
     * count instead; an emptied lobe, before its first frame, reads as no
     * signal.  Each line a reset may name within the result's harmonic table
     * (the harmonics up to H({@link FftResult#harmonicCount} + 1)) and every
     * intermodulation product is stamped onto {@link FftResult#lineStates}
     * with its coefficients, its centre bin, its local count, its waiting
     * mark and its local band top in dBFS on the result's amplitude scale -
     * {@link Double#NaN} for a line with no band (none laid out, or a
     * harmonic above H9), with no frame since its reset, or whose band leaves
     * too few bins; whether κ is refined is stamped onto
     * {@link FftResult#coherentKappaRefined}.
     */
    public void overlayOnto(FftResult r) {
        if (!started || !(frames > 0.0)) return;
        final int    n     = halfSize + 1;
        final double mean  = 1.0 / frames;
        final double conv  = r.amplitudePerMagnitude();
        BinRanges.parallel(n, (lo, hi) -> writeMean(r, lo, hi, mean, conv));
        // The result's harmonic table runs to H(harmonicCount + 1); a ladder
        // line above it is named by no readout and no correction, and the
        // ladder runs to the Nyquist bin - thousands of lines on a low tone.
        final int topHarmonic = r.harmonicCount + 1;
        r.startLineStates();
        for (int m = 0; m < memberCount; m++) {
            if (!resettable(m)) continue;
            double local = localCount[m];
            if (local != frames) {
                writeMean(r, ownedLo(m), memberHi[m] + 1, local > 0.0 ? 1.0 / local : 0.0, conv);
            }
            if (memberB[m] == 0 && memberA[m] > topHarmonic) continue;
            double top = bandTop(m, conv);
            r.addLineState(new LineState(memberA[m], memberB[m], (int) Math.round(memberPos[m]),
                    (int) Math.round(local), waitingReset[m],
                    top > 0.0 ? 20.0 * Math.log10(top) : Double.NaN));
        }
        r.coherentKappaRefined = kappaRefined;
        int kFund = Math.min(halfSize, Math.max(1, r.fundamentalBin));
        r.fundamentalDbFs   = r.amplitudeDbFs[kFund];
        r.fundamentalLinear = Math.pow(10.0, r.fundamentalDbFs / 20.0);
        r.frameCount        = (int) Math.round(frames);
    }

    /** Writes the mean of the sums over bins {@code lo} .. {@code hi - 1}
     *  onto {@code r}, each sum times {@code mean}: the phasor into
     *  {@code re} / {@code im} (the magnitude into {@code re} for the
     *  incoherent average), the amplitude in dBFS at {@code conv} per
     *  magnitude, and the phase in degrees. */
    private void writeMean(FftResult r, int lo, int hi, double mean, double conv) {
        final double[] re = r.re, im = r.im, dbFs = r.amplitudeDbFs, deg = r.phaseDeg;
        if (coherent) {
            final double[] accRe = sumRe, accIm = sumIm;
            for (int k = lo; k < hi; k++) {
                double avgRe = accRe[k] * mean;
                double avgIm = accIm[k] * mean;
                re[k] = avgRe;
                im[k] = avgIm;
                double mag = Math.sqrt(avgRe * avgRe + avgIm * avgIm);
                double amp = mag * conv * ((k == 0 || k == halfSize) ? 0.5 : 1.0);
                dbFs[k] = amp > 1e-15 ? 20.0 * Math.log10(amp) : -300.0;
                deg[k]  = Math.toDegrees(Math.atan2(avgIm, avgRe));
            }
        } else {
            final double[] acc = sumPow;
            for (int k = lo; k < hi; k++) {
                double mag = Math.sqrt(acc[k] * mean);
                double amp = mag * conv * ((k == 0 || k == halfSize) ? 0.5 : 1.0);
                re[k]   = mag;
                im[k]   = 0.0;
                dbFs[k] = amp > 1e-15 ? 20.0 * Math.log10(amp) : -300.0;
                deg[k]  = 0.0;
            }
        }
    }

    /**
     * Adds one frame to the coherent average, turned back to the reference
     * frame as the class comment states, and appends the frame's row to the
     * statistics.
     *
     * @param re        the frame's real parts, bins 0 .. fftSize/2
     * @param im        the frame's imaginary parts
     * @param weight    the frames this frame counts for
     * @param turns     the frame's advance and the turns it is turned back by
     * @param contribRe where not null, receives every bin's contribution as
     *                  added - weight and rotation applied - so that a caller
     *                  can take this frame out again with {@link #remove}
     * @param contribIm the imaginary parts of the same
     */
    public void add(double[] re, double[] im, double weight, FrameTurns turns,
                    double[] contribRe, double[] contribIm) {
        final int n = halfSize + 1;
        final double[] accRe = sumRe, accIm = sumIm;
        final double delta = turns.delta();
        if (kappa <= 0.0 || memberCount == 0) {
            // No pinned fundamental: nothing to align - the frame is added as
            // it stands, and noise and unlocked lines decay on their own.
            BinRanges.parallel(n, (lo, hi) -> {
                for (int k = lo; k < hi; k++) {
                    double cRe = re[k] * weight;
                    double cIm = im[k] * weight;
                    accRe[k] += cRe;
                    accIm[k] += cIm;
                    if (contribRe != null) {
                        contribRe[k] = cRe;
                        contribIm[k] = cIm;
                    }
                }
            });
            addFrames(weight);
            recordStats(re, im);
            return;
        }
        measureKappa(re, im, delta, turns.phiF());
        final double phi1 = fundamentalAngle(delta);
        final double phi2 = Double.isNaN(turns.phi2()) ? secondToneAngle(delta) : turns.phi2();
        for (int m = 0; m < memberCount; m++) {
            double ang;
            if (memberPilot[m] != 0) {
                double read = memberPilot[m] == 1 ? turns.phiPilot1() : turns.phiPilot2();
                ang = Double.isNaN(read) ? binAngle(memberPos[m], delta) : read;
            } else {
                ang = memberA[m] * phi1 + (memberB[m] != 0 ? memberB[m] * phi2 : 0.0);
                if (memberA[m] == 1 && memberB[m] == 0) ang -= turns.phiF();
                if (memberA[m] == 0 && memberB[m] == 1) ang -= turns.phiF2();
            }
            ang = Math.IEEEremainder(ang, 2.0 * Math.PI);
            memberCos[m] = Math.cos(ang);
            memberSin[m] = Math.sin(ang);
        }
        // The proportional rotation as an incremental phasor: one bin turns
        // by theta more than the bin below it.
        final double theta  = -2.0 * Math.PI * Math.IEEEremainder(delta, fftSize) / fftSize;
        final double stepRe = Math.cos(theta);
        final double stepIm = Math.sin(theta);
        final int    count  = memberCount;
        final double[] mCos = memberCos, mSin = memberSin;
        final int[]    mLo  = memberLo,  mHi  = memberHi;
        // Every bin's contribution goes to the caller's arrays where it asks
        // for it, else to the scratch arrays while local bands are laid out,
        // which take the very same values.
        final boolean  bands = bandRe.length > 0;
        final double[] outRe = contribRe != null ? contribRe : bands ? scratchRe : null;
        final double[] outIm = contribRe != null ? contribIm : bands ? scratchIm : null;
        BinRanges.parallel(n, (kLo, kHi) -> {
            double ang  = Math.IEEEremainder(theta * kLo, 2.0 * Math.PI);
            double phRe = Math.cos(ang);
            double phIm = Math.sin(ang);
            // The member whose hump this chunk may still be inside of, or the
            // next one above: the humps are sorted with their members.
            int m = 0;
            while (m < count && mHi[m] < kLo) m++;
            for (int k = kLo; k < kHi; k++) {
                while (m < count && mHi[m] < k) m++;
                double rotRe, rotIm;
                if (m < count && k >= mLo[m]) {
                    rotRe = mCos[m];
                    rotIm = mSin[m];
                } else {
                    rotRe = phRe;
                    rotIm = phIm;
                }
                double rRe = re[k] * weight;
                double rIm = im[k] * weight;
                double cRe = rRe * rotRe - rIm * rotIm;
                double cIm = rRe * rotIm + rIm * rotRe;
                accRe[k] += cRe;
                accIm[k] += cIm;
                if (outRe != null) {
                    outRe[k] = cRe;
                    outIm[k] = cIm;
                }
                double nextRe = phRe * stepRe - phIm * stepIm;
                phIm = phRe * stepIm + phIm * stepRe;
                phRe = nextRe;
            }
        });
        if (bands) foldIntoBands(outRe, outIm, 1.0);
        addFrames(weight);
        recordStats(re, im);
    }

    /**
     * Adds one frame to the incoherent average: each bin's power, no rotation.
     * The frame is a raw transform, or the analyser's incoherent result with
     * the magnitude in {@code re} and {@code im} zero - the power is the same.
     *
     * @param re         the frame's real parts, bins 0 .. fftSize/2
     * @param im         the frame's imaginary parts
     * @param weight     the frames this frame counts for
     * @param contribPow where not null, receives every bin's contribution as
     *                   added, for {@link #removePower}
     */
    public void addPower(double[] re, double[] im, double weight, double[] contribPow) {
        final int n = halfSize + 1;
        final double[] acc = sumPow;
        for (int k = 0; k < n; k++) {
            double c = (re[k] * re[k] + im[k] * im[k]) * weight;
            acc[k] += c;
            if (contribPow != null) contribPow[k] = c;
        }
        addFrames(weight);
    }

    /** Scales the sums and the frames held by {@code factor} - the step that
     *  turns a running sum into an exponential window. */
    public void scale(double factor) {
        if (coherent) {
            for (int k = 0; k < sumRe.length; k++) {
                sumRe[k] *= factor;
                sumIm[k] *= factor;
            }
        } else {
            for (int k = 0; k < sumPow.length; k++) sumPow[k] *= factor;
        }
        frames *= factor;
        for (int m = 0; m < memberCount; m++) localCount[m] *= factor;
        for (int i = 0; i < bandRe.length; i++) {
            bandRe[i] *= factor;
            bandIm[i] *= factor;
        }
    }

    /** Takes a frame out again: its contribution exactly as {@link #add}
     *  handed it back, and its weight. */
    public void remove(double[] contribRe, double[] contribIm, double weight) {
        for (int k = 0; k < sumRe.length; k++) {
            sumRe[k] -= contribRe[k];
            sumIm[k] -= contribIm[k];
        }
        if (bandRe.length > 0) foldIntoBands(contribRe, contribIm, -1.0);
        removeFrames(weight);
    }

    /** Takes a frame out of the incoherent average again. */
    public void removePower(double[] contribPow, double weight) {
        for (int k = 0; k < sumPow.length; k++) sumPow[k] -= contribPow[k];
        removeFrames(weight);
    }

    /** Counts {@code weight} more frames in the global frame count and in
     *  every line's local count: a line never reset stays on the global
     *  count, bit for bit. */
    private void addFrames(double weight) {
        frames += weight;
        for (int m = 0; m < memberCount; m++) localCount[m] += weight;
    }

    /** Takes {@code weight} frames out of the global frame count and out of
     *  every line's local count, none below zero. */
    private void removeFrames(double weight) {
        frames = Math.max(0.0, frames - weight);
        for (int m = 0; m < memberCount; m++) localCount[m] = Math.max(0.0, localCount[m] - weight);
    }

    /**
     * The rows the statistics hold: one per frame {@link #add} has added
     * since the restart.
     */
    public int statisticsRowCount() {
        synchronized (statsLock) {
            return statsRowCount;
        }
    }

    /**
     * The statistics' column names, unmodifiable: {@code frames}, the global
     * frame count after the add; then per line, named by its coefficients
     * ({@code (2,0)} for H2, {@code (1,1)} for F1 + F2), at the line's centre
     * bin:
     * <ul>
     *   <li>{@code (a,b) raw} - the frame's magnitude as it was added;</li>
     *   <li>{@code (a,b) accumulated} - the average's magnitude after the
     *       add, the line's sum over its own local count;</li>
     *   <li>{@code (a,b) raw phase} - the frame's phase as the add turned
     *       it back to the collection's reference frame, degrees: the phase
     *       of its contribution to the sums, which compares with the
     *       accumulated phase directly;</li>
     *   <li>{@code (a,b) accumulated phase} - the average's phase,
     *       degrees;</li>
     *   <li>for every line but a tone, {@code (a,b) raw band top} - the band
     *       top over the frame's own band bins - and
     *       {@code (a,b) accumulated band top} - the band top over the line's
     *       band sums over its local count, the overlay's local band top;
     *       both {@link Double#NaN} while {@link #lineBands} has laid out no
     *       band.</li>
     * </ul>
     * The lines are the fundamental (1,0), the second tone (0,1) where a
     * pair is pinned, H2 .. H9 and every intermodulation product.
     */
    public List<String> statisticsColumns() {
        synchronized (statsLock) {
            return statsColumns;
        }
    }

    /**
     * One value of the statistics: a magnitude or a band top as the linear
     * magnitude in the transform's own unit (times
     * {@link FftResult#amplitudePerMagnitude} it is the amplitude, full
     * scale 1), a phase in degrees, {@link Double#NaN} where it was not
     * measured - a band top with no band laid out, or a line the family no
     * longer held.
     *
     * @throws IndexOutOfBoundsException outside the rows or the columns held
     */
    public double statistic(int row, int column) {
        synchronized (statsLock) {
            Objects.checkIndex(row, statsRowCount);
            Objects.checkIndex(column, statsWidth);
            return statsRows[row * statsWidth + column];
        }
    }

    /**
     * Writes the statistics to {@code path}: a header row of the column
     * names with their units, then one row per frame; the levels in dBFS on
     * the amplitude scale {@code amplitudePerMagnitude}
     * ({@link FftResult#amplitudePerMagnitude} of the collection's results),
     * semicolon-separated with a decimal comma, an empty field where a value
     * was not measured.  The rows are copied out first, so the file is
     * written while the analysis goes on adding.
     */
    public void exportStatistics(Path path, double amplitudePerMagnitude) throws IOException {
        double[]     rows;
        int          count;
        int          width;
        List<String> columns;
        boolean[]    phase;
        synchronized (statsLock) {
            count   = statsRowCount;
            width   = statsWidth;
            rows    = Arrays.copyOf(statsRows, count * width);
            columns = statsColumns;
            phase   = statsPhase;
        }
        DecimalFormat number = new DecimalFormat(STATS_NUMBER, DecimalFormatSymbols.getInstance(Locale.GERMAN));
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            StringBuilder line = new StringBuilder();
            for (int c = 0; c < width; c++) {
                if (c > 0) line.append(STATS_SEPARATOR);
                line.append(columns.get(c)).append(c == 0 ? "" : phase[c] ? STATS_PHASE_UNIT : STATS_LEVEL_UNIT);
            }
            out.write(line.toString());
            out.newLine();
            for (int row = 0; row < count; row++) {
                line.setLength(0);
                for (int c = 0; c < width; c++) {
                    if (c > 0) line.append(STATS_SEPARATOR);
                    double v = rows[row * width + c];
                    if (c > 0 && !phase[c]) {
                        double amp = v * amplitudePerMagnitude;
                        v = amp > STATS_MIN_AMPLITUDE ? 20.0 * Math.log10(amp)
                                : Double.isNaN(v) ? Double.NaN : STATS_NO_SIGNAL_DBFS;
                    }
                    if (Double.isFinite(v)) line.append(number.format(v));
                }
                out.write(line.toString());
                out.newLine();
            }
        }
    }

    /** Drops the statistics and their layout. */
    private void clearStats() {
        synchronized (statsLock) {
            statsRows     = new double[0];
            statsRowCount = 0;
            statsWidth    = 1;
            statsColumns  = List.of(STATS_FRAMES);
            statsPhase    = new boolean[1];
        }
        statsTones = 0;
        statsA     = new int[0];
        statsB     = new int[0];
        statsIndex = new int[0];
    }

    /** Fixes the statistics lines of a new collection on the family just
     *  built: the fundamental, the second tone where a pair is pinned, then
     *  H2 .. {@link #STATS_MAX_HARMONIC} and every product in position
     *  order. */
    private void layoutStats() {
        int[] a = new int[memberCount];
        int[] b = new int[memberCount];
        int   n = 0;
        if (lineIndex(1, 0) >= 0) {
            a[n]   = 1;
            b[n++] = 0;
        }
        if (lineIndex(0, 1) >= 0) {
            a[n]   = 0;
            b[n++] = 1;
        }
        int tones = n;
        for (int m = 0; m < memberCount; m++) {
            if (followed(m)) {
                a[n]   = memberA[m];
                b[n++] = memberB[m];
            }
        }
        int       width = 1 + STATS_TONE_COLUMNS * tones + STATS_LINE_COLUMNS * (n - tones);
        String[]  names = new String[width];
        boolean[] phase = new boolean[width];
        int       c     = 0;
        names[c++] = STATS_FRAMES;
        for (int i = 0; i < n; i++) {
            String line = "(" + a[i] + "," + b[i] + ")";
            names[c++] = line + " raw";
            names[c++] = line + " accumulated";
            phase[c]   = true;
            names[c++] = line + " raw phase";
            phase[c]   = true;
            names[c++] = line + " accumulated phase";
            if (i >= tones) {
                names[c++] = line + " raw band top";
                names[c++] = line + " accumulated band top";
            }
        }
        statsTones = tones;
        statsA     = Arrays.copyOf(a, n);
        statsB     = Arrays.copyOf(b, n);
        statsIndex = new int[n];
        mapStatsLines();
        synchronized (statsLock) {
            statsColumns = List.of(names);
            statsPhase   = phase;
            statsWidth   = width;
        }
    }

    /** Points each statistics line at its index in the family as it stands,
     *  found by the line's coefficients; -1 where the family holds it no
     *  longer. */
    private void mapStatsLines() {
        for (int i = 0; i < statsA.length; i++) {
            statsIndex[i] = lineIndex(statsA[i], statsB[i]);
        }
    }

    /** The family index of the line ({@code a}, {@code b}) other than a
     *  pilot, -1 where the family holds none. */
    private int lineIndex(int a, int b) {
        for (int m = 0; m < memberCount; m++) {
            if (memberPilot[m] == 0 && memberA[m] == a && memberB[m] == b) return m;
        }
        return -1;
    }

    /** Appends the row of the frame just added from {@code re} /
     *  {@code im}: the global frame count, then per statistics line at its
     *  centre bin the frame's and the average's magnitude (the average over
     *  the line's local count) and phase (the frame's as turned back), and
     *  for a line other than a tone
     *  the band top over the frame's band bins and over the line's band sums.
     *  The row is assembled outside the lock and copied in under it. */
    private void recordStats(double[] re, double[] im) {
        if (statsRow.length != statsWidth) statsRow = new double[statsWidth];
        int c = 0;
        statsRow[c++] = frames;
        for (int i = 0; i < statsA.length; i++) {
            int     m    = statsIndex[i];
            boolean tone = i < statsTones;
            if (m < 0) {
                int columns = tone ? STATS_TONE_COLUMNS : STATS_LINE_COLUMNS;
                for (int j = 0; j < columns; j++) statsRow[c++] = Double.NaN;
                continue;
            }
            int    k     = centreBin(m);
            double local = localCount[m];
            double sRe   = sumRe[k];
            double sIm   = sumIm[k];
            statsRow[c++] = Math.sqrt(re[k] * re[k] + im[k] * im[k]);
            statsRow[c++] = local > 0.0 ? Math.sqrt(sRe * sRe + sIm * sIm) / local : Double.NaN;
            statsRow[c++] = turnedPhaseDeg(m, k, re[k], im[k]);
            statsRow[c++] = Math.toDegrees(Math.atan2(sIm, sRe));
            if (!tone) {
                double raw = rawBandTop(m, re, im);
                double acc = bandTop(m, 1.0);
                statsRow[c++] = raw > 0.0 ? raw : Double.NaN;
                statsRow[c++] = acc > 0.0 ? acc : Double.NaN;
            }
        }
        synchronized (statsLock) {
            int at = statsRowCount * statsWidth;
            if (at + statsWidth > statsRows.length) {
                statsRows = Arrays.copyOf(statsRows,
                        Math.max(STATS_INITIAL_ROWS * statsWidth, 2 * statsRows.length));
            }
            System.arraycopy(statsRow, 0, statsRows, at, statsWidth);
            statsRowCount++;
        }
    }

    /** The phase in degrees of the frame's bin {@code k} - the centre bin of
     *  family line {@code m} - as {@link #add} turned it back: by the angle
     *  of the line the lobe walk gives the bin to, which is {@code m} unless
     *  a lower line's lobe reaches over it.  The very phase the bin's
     *  contribution to the sums carries, so it compares with the
     *  accumulated phase directly.  Valid right after the add, while the
     *  family's phasors still hold that frame's angles. */
    private double turnedPhaseDeg(int m, int k, double re, double im) {
        int owner = m;
        while (owner > 0 && memberHi[owner - 1] >= k) owner--;
        double c = memberCos[owner];
        double s = memberSin[owner];
        return Math.toDegrees(Math.atan2(re * s + im * c, re * c - im * s));
    }

    /** The coherent family on the pinned geometry: the harmonic ladder h·κ
     *  for h = 1 .. halfSize/κ merged, sorted by position, with the product
     *  grid a·κ + b·κ2 - the second tone is its (0, 1) entry - and with each
     *  pilot line held, at its whole bin; each member with the whole bins of
     *  its hump, pos ± lobeHalfBins, clamped to the spectrum.  Nothing below
     *  one bin: a ladder on a fundamental under one bin would be the whole
     *  spectrum, member for bin. */
    private void buildFamily() {
        memberCount = 0;
        if (kappa < 1.0) return;
        int hMax = (int) Math.min(halfSize, halfSize / kappa);
        boolean products = kappa2 > 0.0 && productA.length > 0;
        int cap = hMax + (products ? productA.length : 0) + 2;
        if (memberPos.length < cap) {
            memberPos   = new double[cap];
            memberA     = new int[cap];
            memberB     = new int[cap];
            memberPilot = new int[cap];
            memberLo    = new int[cap];
            memberHi    = new int[cap];
            memberCos   = new double[cap];
            memberSin   = new double[cap];
            localCount   = new double[cap];
            waitingReset = new boolean[cap];
            bandLo       = new int[cap];
            bandLen      = new int[cap];
            bandOff      = new int[cap];
        }
        int n = 0;
        for (int h = 1; h <= hMax; h++) {
            n = addMember(n, h * kappa, h, 0, 0);
        }
        if (products) {
            for (int p = 0; p < productA.length; p++) {
                double pos = productA[p] * kappa + productB[p] * kappa2;
                if (pos < 1.0 || pos > halfSize - 1) continue;
                n = addMember(n, pos, productA[p], productB[p], 0);
            }
        }
        if (pilotBin1 >= 1 && pilotBin1 <= halfSize - 1) n = addMember(n, pilotBin1, 0, 0, 1);
        if (pilotBin2 >= 1 && pilotBin2 <= halfSize - 1) n = addMember(n, pilotBin2, 0, 0, 2);
        // Position sort - a handful of grid entries and pilots land among
        // the ladder.
        for (int i = 1; i < n; i++) {
            double pos = memberPos[i];
            int a = memberA[i], b = memberB[i], p = memberPilot[i];
            int j = i - 1;
            while (j >= 0 && memberPos[j] > pos) {
                memberPos[j + 1]   = memberPos[j];
                memberA[j + 1]     = memberA[j];
                memberB[j + 1]     = memberB[j];
                memberPilot[j + 1] = memberPilot[j];
                j--;
            }
            memberPos[j + 1]   = pos;
            memberA[j + 1]     = a;
            memberB[j + 1]     = b;
            memberPilot[j + 1] = p;
        }
        for (int i = 0; i < n; i++) {
            memberLo[i] = Math.max(0, (int) Math.ceil(memberPos[i] - lobeHalfBins));
            memberHi[i] = Math.min(halfSize, (int) Math.floor(memberPos[i] + lobeHalfBins));
            // Built at the restart and once more when κ is refined, and no
            // line can be reset or marked before that refinement: every line
            // starts on the global frame count, unmarked.
            localCount[i]   = frames;
            waitingReset[i] = false;
            bandLen[i]      = 0;
        }
        memberCount = n;
    }

    /** Writes member {@code n} and returns the next index. */
    private int addMember(int n, double pos, int a, int b, int pilot) {
        memberPos[n]   = pos;
        memberA[n]     = a;
        memberB[n]     = b;
        memberPilot[n] = pilot;
        return n + 1;
    }

}
