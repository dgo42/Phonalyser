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

package org.edgo.audio.measure.gui.freqresp;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Immutable snapshot of the sweep parameters that produced a
 * {@link FreqRespResult}.  Saved alongside the magnitude / phase arrays in
 * the on-disk CSV header so a reload knows the source's frequency range,
 * amplitude, lead-in and dither settings — enough to repeat the measurement
 * or to detect a calibration grid mismatch when a calibration file is
 * applied at a different sweep configuration.
 */
@Getter
@RequiredArgsConstructor
public final class FreqRespSweepParams {

    /** Start frequency of the log sweep, in Hz. */
    private final double startHz;

    /** End frequency of the log sweep, in Hz.  Always &gt; {@link #startHz}. */
    private final double stopHz;

    /** Number of log-spaced output frequency points the deconvolution
     *  produces.  Matches the length of the result's
     *  {@link FreqRespResult#getFreqs()} array. */
    private final int sweepPoints;

    /** Sweep duration in seconds, excluding lead-in. */
    private final double durationSec;

    /** Lead-in silence prepended to the sweep, in seconds.  Lets the
     *  capture-and-generator chain settle before the first sweep sample
     *  is emitted. */
    private final double leadInSec;

    /** Generator drive amplitude in V RMS at the DAC output. */
    private final double amplitudeVrms;

    /** TPDF dither resolution applied to the sweep output before
     *  quantisation; 0 disables dither. */
    private final int ditherBits;
}
