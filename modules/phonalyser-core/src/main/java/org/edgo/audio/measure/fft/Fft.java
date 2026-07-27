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

package org.edgo.audio.measure.fft;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import lombok.experimental.UtilityClass;

/**
 * Cooley-Tukey radix-2 decimation-in-time FFT, in-place.  Size must be
 * a power of two.  Pure function on {@code double[]} arrays — no state,
 * no allocation past the input buffers, trivially unit-testable.
 *
 * <p>Extracted from {@link FftAnalyzer} so the spectral primitive can
 * be reused (and tested in isolation) without dragging in the analyser's
 * full averaging + windowing pipeline.
 *
 * <p>Within each butterfly stage the {@code n/len} groups are fully
 * independent (each touches a disjoint {@code [i, i+len)} slice), so for
 * large transforms the groups are split across a small daemon thread pool
 * with a barrier between stages — the big-FFT cost that otherwise can't
 * keep up with the capture rate at high overlap.  The final
 * {@code log2(THREADS)} stages have fewer groups than threads; there the
 * {@code k}-loop inside each group is chunked instead, each chunk seeding
 * its own twiddle with one {@code cos/sin} — without this the largest
 * ~15-20 % of the butterfly work ran serial and Amdahl capped the whole
 * transform at ~4-5× regardless of cores.  Chunk seeding makes those
 * stages no longer bit-identical to the serial path, but numerically
 * equal-or-better: each twiddle recurrence chain is SHORTER than the
 * serial one it replaces.  Below {@link #PARALLEL_THRESHOLD} (or on
 * ≤2-core machines) everything stays serial, where fork/join overhead
 * would dominate.
 */
@UtilityClass
public final class Fft {

    /** Below this transform size the serial path is faster (fork overhead). */
    private static final int PARALLEL_THRESHOLD = 1 << 16;   // 64k points
    /** Minimum butterflies per k-chunk in the final stages — below this the
     *  per-chunk cos/sin seed + fork overhead outweighs the parallelism. */
    private static final int MIN_K_CHUNK = 1 << 14;          // 16k butterflies
    /** Worker threads — leave one core for the rest of the analysis pipeline. */
    private static final int THREADS = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
    /** Shared daemon pool; null (⇒ always serial) on ≤2-core machines. */
    private static final ExecutorService POOL =
            THREADS < 2 ? null : Executors.newFixedThreadPool(THREADS, daemonFactory());

    private static ThreadFactory daemonFactory() {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "fft-worker-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * Forward FFT.  Mutates {@code re} and {@code im} in-place to hold
     * the complex spectrum on return.  Both arrays must have the same
     * length and that length must be a power of two.
     */
    public static void forward(double[] re, double[] im) {
        int n = re.length;
        bitReverse(re, im, n);
        if (n < PARALLEL_THRESHOLD || POOL == null) {
            for (int len = 2; len <= n; len <<= 1) {
                double ang = -2.0 * Math.PI / len;
                stageGroups(re, im, 0, n / len, len, Math.cos(ang), Math.sin(ang));
            }
        } else {
            butterfliesParallel(re, im, n);
        }
    }

    /** In-place bit-reversal permutation (cheap, kept serial). */
    private static void bitReverse(double[] re, double[] im, int n) {
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
    }

    /** Butterfly stages with each stage's groups split across the pool. */
    private static void butterfliesParallel(double[] re, double[] im, int n) {
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * Math.PI / len;
            final double wRe  = Math.cos(ang);
            final double wIm  = Math.sin(ang);
            final int    flen = len;
            int groups = n / len;                       // independent groups this stage
            int tasks  = Math.min(THREADS, groups);     // few/1 for the last log2(THREADS) stages
            List<Callable<Void>> work;
            if (tasks * 2 > THREADS + 1) {
                // Plenty of groups: split across groups (bit-identical
                // butterflies, only partitioned).
                int per = (groups + tasks - 1) / tasks; // groups per task (ceil)
                work = new ArrayList<>(tasks);
                for (int t = 0; t < tasks; t++) {
                    final int gStart = t * per;
                    final int gEnd   = Math.min(groups, gStart + per);
                    if (gStart >= gEnd) break;
                    work.add(() -> { stageGroups(re, im, gStart, gEnd, flen, wRe, wIm); return null; });
                }
            } else {
                // Final stages: fewer groups than threads — chunk each
                // group's k-loop instead, seeding the twiddle per chunk
                // (see the class doc's numerics note).
                int half    = flen >> 1;
                int kChunks = Math.min(Math.max(1, THREADS / groups),
                                       Math.max(1, half / MIN_K_CHUNK));
                if (groups * kChunks <= 1) {
                    stageGroups(re, im, 0, groups, flen, wRe, wIm);
                    continue;
                }
                int kPer = (half + kChunks - 1) / kChunks;
                final double fAng = ang;
                work = new ArrayList<>(groups * kChunks);
                for (int g = 0; g < groups; g++) {
                    final int base = g * flen;
                    for (int c = 0; c < kChunks; c++) {
                        final int kStart = c * kPer;
                        final int kEnd   = Math.min(half, kStart + kPer);
                        if (kStart >= kEnd) break;
                        work.add(() -> {
                            stageKRange(re, im, base, flen, kStart, kEnd, fAng, wRe, wIm);
                            return null;
                        });
                    }
                }
            }
            try {
                for (Future<Void> f : POOL.invokeAll(work)) f.get();   // barrier
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Parallel FFT interrupted", e);
            } catch (Exception e) {
                throw new IllegalStateException("Parallel FFT stage failed", e);
            }
        }
    }

    /** Processes butterflies {@code [kStart, kEnd)} of the single group at
     *  array offset {@code base} in a stage of length {@code len}, seeding
     *  the twiddle recurrence at {@code kStart} with one cos/sin.  Lets the
     *  final stages (few groups, huge halves) parallelise across {@code k}. */
    private static void stageKRange(double[] re, double[] im, int base, int len,
                                    int kStart, int kEnd, double ang,
                                    double wRe, double wIm) {
        int half = len >> 1;
        double curRe = Math.cos(ang * kStart);
        double curIm = Math.sin(ang * kStart);
        for (int k = kStart; k < kEnd; k++) {
            int    p  = base + k;
            int    q  = p + half;
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

    /** Processes butterfly groups {@code [gStart, gEnd)} of one stage. */
    private static void stageGroups(double[] re, double[] im,
                                    int gStart, int gEnd, int len, double wRe, double wIm) {
        int half = len >> 1;
        for (int g = gStart; g < gEnd; g++) {
            int i = g * len;
            double curRe = 1.0, curIm = 0.0;
            for (int k = 0; k < half; k++) {
                int    p  = i + k;
                int    q  = p + half;
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
