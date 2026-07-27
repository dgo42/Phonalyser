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

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Pool of recycled {@link FftResult} slots.  {@link FftAnalyzer#analyze}
 * writes its four fftSize/2+1 spectrum arrays straight into a slot
 * (via {@link FftResult#ensureArrays}), so reusing slots eliminates
 * ~33 MB/tick (at fftSize 2 M) of humongous-object churn on the live
 * analysis path.
 *
 * <p><b>Ownership rule:</b> a slot leaves the pool via {@link #acquire}
 * and must be {@link #release}d exactly once — by whichever party
 * consumed it last (the producer on a discard path, or the consumer
 * after it deep-copied what it keeps).  A released slot's contents are
 * garbage from that moment on.
 *
 * <h2>Threading</h2>
 * Lock-free; acquire/release may be called from any thread.
 */
public final class FftResultPool {

    private final ConcurrentLinkedQueue<FftResult> free = new ConcurrentLinkedQueue<>();

    /** Returns a recycled slot, or a fresh one when all slots are in
     *  flight (cold start, transient consumer backlog).  The pool size
     *  self-regulates: every release adds the slot back, so circulation
     *  converges on the steady-state in-flight count. */
    public FftResult acquire() {
        FftResult slot = free.poll();
        return slot != null ? slot : new FftResult();
    }

    /** Returns a slot to the pool once its last consumer is done with
     *  it.  Null-tolerant (no-op) so discard paths don't need a guard. */
    public void release(FftResult slot) {
        if (slot != null) free.offer(slot);
    }

    /** Drops every pooled slot so their spectrum arrays become
     *  collectable — e.g. when the analysis stops and ~100 MB of slots
     *  (at fftSize 4 M) would otherwise idle in the pool. */
    public void clear() {
        free.clear();
    }
}
