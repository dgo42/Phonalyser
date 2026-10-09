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

import java.util.stream.IntStream;

import lombok.experimental.UtilityClass;

/**
 * The one way a per-bin pass over a spectrum is split across cores: one
 * contiguous chunk per core on the common pool, or the whole range on the
 * caller's thread below {@link #PARALLEL_THRESHOLD}, where the fork and
 * dispatch overhead would dominate the loop.  The chunks are disjoint, so a
 * task that writes only its own [lo, hi) slice needs no synchronisation.
 */
@UtilityClass
public final class BinRanges {

    /** Below this bin count the pass runs serially. */
    private static final int PARALLEL_THRESHOLD = 1 << 16;   // 64k bins

    /** A contiguous half-open [lo, hi) bin range to process. */
    @FunctionalInterface
    public interface RangeTask { void run(int lo, int hi); }

    /** Runs {@code task} over [0, n). */
    public static void parallel(int n, RangeTask task) {
        if (n < PARALLEL_THRESHOLD) { task.run(0, n); return; }
        int chunks    = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
        int chunkSize = (n + chunks - 1) / chunks;
        IntStream.range(0, chunks).parallel().forEach(c -> {
            int lo = c * chunkSize;
            int hi = Math.min(n, lo + chunkSize);
            if (lo < hi) task.run(lo, hi);
        });
    }
}
