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

import org.edgo.audio.measure.enums.AlignGenerator;

/**
 * A closed-loop frequency aligner that steers the generator's commanded
 * frequency onto the FFT bin grid by feeding back the per-frame error between
 * the commanded {@code target} and the FFT's refined {@code detected} estimate.
 *
 * <p>The sole implementation is {@link FrequencyFll}.  The view holds one per
 * tone and adds {@link #getCorrection()} to the snap target before publishing the
 * trim.  Not synchronized — called from the SWT UI thread.
 */
public interface FrequencyAligner {

    /** Feeds one FFT measurement in and updates the correction.
     *  {@code absStartSamples}/{@code sampleRate} give the window's absolute
     *  position; {@code fftSize} is the analysis window length; {@code latestSamplePos}
     *  is the live capture {@code writePos} at publish time.  Non-finite inputs and
     *  the first call after a reset are ignored. */
    void update(double target, double detected, long absStartSamples, long latestSamplePos,
                int sampleRate, int fftSize);

    /** Current correction in Hz — add to the snap target before publishing the trim. */
    double getCorrection();

    /** Zeroes the loop state.  Call on Record stop and on user-initiated
     *  generator-frequency / FFT-length changes (both invalidate the lock). */
    void reset();

    AlignGenerator getMode();
}
