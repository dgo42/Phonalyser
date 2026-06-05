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

package org.edgo.audio.measure.cli.util;

/**
 * Per-block progress callback for the stereo sweep-and-capture pipeline.
 * Producers ({@link CaptureWithGenerator#runStereo}) invoke this once per
 * captured audio block — typically every few ms — so consumers can show a
 * live level meter or progress bar while the sweep is in flight.
 *
 * <p>The callback fires on the audio capture thread; consumers that need
 * to touch UI widgets must marshal to their toolkit's UI thread (e.g.
 * {@code Display.asyncExec}).  Producers MUST tolerate a slow consumer
 * (the callback should never block the audio path); short, allocation-free
 * implementations are best practice.
 */
@FunctionalInterface
public interface StereoCaptureProgress {

    /**
     * Fires once per captured block.
     *
     * @param totalSamples cumulative sample count (per channel) at the end
     *                     of this block — combine with the sample rate to
     *                     get an elapsed-time value
     * @param rmsLin       the larger of the L and R channels' RMS amplitude
     *                     for the just-finished block, in linear scale where
     *                     {@code 1.0} is digital full-scale
     */
    void onBlock(int totalSamples, double rmsLin);
}
