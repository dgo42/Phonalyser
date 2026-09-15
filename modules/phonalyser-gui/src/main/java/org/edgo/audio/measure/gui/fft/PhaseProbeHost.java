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

package org.edgo.audio.measure.gui.fft;

import org.edgo.audio.measure.dsp.FreqRespCalibration;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.gui.freqresp.NotchPhaseProbe;

/**
 * What a notch-phase probe needs that the FFT worker has no business owning.
 *
 * <p>The worker owns the schedule, the pause of its own tick loop and the
 * phase excess a probe yields; it does not own the converter and drive
 * settings a probe is built from, nor the de-embedding the fresh response
 * belongs in.  Those two are named here and injected, so the worker reaches
 * for no singleton of its own.
 *
 * <p>The generator's tone is NOT part of this interface: the worker announces
 * a probe on the bus and the generator subscribes to that announcement, the
 * same seam the frequency-response measurement already uses.
 */
public interface PhaseProbeHost {

    /** A probe built from the converter and drive settings in force - the
     *  sources the tune-notch wizard reads, so the notch is probed at the
     *  level the measurement drives it with and through the same output
     *  path. */
    NotchPhaseProbe newProbe();

    /** Folds a completed probe's response into the de-embedding of
     *  {@code side} between {@code bandLoHz} and {@code bandHiHz} and
     *  publishes the change, so the correction and the phase excess describe
     *  the same filter. */
    void refreshDeEmbedding(Channel side, double bandLoHz, double bandHiHz,
                            FreqRespCalibration measured);
}
