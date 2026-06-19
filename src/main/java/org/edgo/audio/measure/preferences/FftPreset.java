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

package org.edgo.audio.measure.preferences;

import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.MagnitudeUnit;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.WindowType;

import lombok.Data;

/**
 * Snapshot of every FFT-view control whose value is part of a saved
 * preset: chart range (freqMin/Max, magTop/Bottom, magnitude unit,
 * phase visibility, log/lin), every FFT-tab field, and every THD-tab
 * field.  Field names mirror the top-level {@code fftXxx} fields on
 * {@link Preferences} so apply/capture in {@code FftPane} is a straight
 * assignment per field.
 */
@Data
public class FftPreset {
    private Channel channel               = Channel.L;
    private MagnitudeUnit magUnit              = MagnitudeUnit.DBV;
    private boolean logFreqAxis                  = true;
    private double  freqMinHz                    = 20;
    private double  freqMaxHz                    = 20000;
    private double  magTop                       = 10;
    private double  magBottom                    = -150;
    // Settings tab
    private int     fftLength                    = 65536;
    private double  averages                     = 4;
    private boolean stopAfterNEnabled            = false;
    private int     stopAfterN                   = 10;
    private boolean fundFromGenerator            = false;
    private WindowType window                    = WindowType.HANN;
    private FftOverlap overlap                   = FftOverlap.PCT_0;
    private boolean coherentAveraging            = true;
    // THD tab
    private double  distMinHz                    = 20;
    private double  distMaxHz                    = 20000;
    private boolean distMinEnabled               = false;
    private boolean distMaxEnabled               = false;
    private int     thdMaxHarmonic               = 9;
    private int     calcMaxHarmonic              = 9;
    private double  manualFundVrms               = 1.0;
    private boolean manualFundDbvDisplay         = false;
    private boolean manualFundEnabled            = false;
}
