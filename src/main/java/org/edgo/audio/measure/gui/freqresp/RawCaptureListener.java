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

import org.edgo.audio.measure.cli.util.StereoSamples;

/**
 * Optional hook fired by {@link FreqRespAnalyzer} after the stereo sweep
 * capture finishes and before deconvolution starts.  Lets a caller
 * side-effect on the unprocessed samples — the CLI's {@code --sweep-wav}
 * flag uses this to persist a WAV of the raw recording without dragging
 * file-path concerns into the analyzer's signature.
 *
 * <p>Fires on whatever thread runs the analyzer.  Exceptions thrown from the
 * listener propagate out of {@link FreqRespAnalyzer#run} and abort the
 * measurement before deconvolution.
 */
@FunctionalInterface
public interface RawCaptureListener {

    /**
     * @param samples both channels, normalised to {@code [-1, +1]};
     *                do not mutate
     */
    void onRawCapture(StereoSamples samples) throws Exception;
}
