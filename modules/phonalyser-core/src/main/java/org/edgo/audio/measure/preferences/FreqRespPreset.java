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

package org.edgo.audio.measure.preferences;

import org.edgo.audio.measure.enums.FilterResponse;
import org.edgo.audio.measure.enums.FilterType;
import org.edgo.audio.measure.enums.UnevenMode;

import lombok.Data;

/**
 * Snapshot of every Frequency-Response Settings-tab control whose value is
 * worth persisting as a named preset.  Field names mirror the top-level
 * {@code freqRespXxx} fields on {@link Preferences} so capture / apply in
 * {@code FreqRespPane} is a straight assignment per field.
 *
 * <p>Mirrors the shape of {@link FftPreset}; the YAML round-trip lives
 * alongside the FFT presets in {@code Preferences#save} / {@code load}.
 */
@Data
public class FreqRespPreset {
    // Sweep
    private double startHz        = 20.0;
    private double stopHz         = 20_000.0;
    private double amplitudeVrms  = 0.5;
    private int    sweepPoints    = 65536;
    private int    fftSize        = 524288;
    private double leadInSec      = 0.2;
    private int    ditherBits     = 0;
    // RIAA / IEC
    private boolean showRiaa      = false;
    private boolean reverseRiaa   = false;
    private boolean iecAmendment  = false;
    private boolean compareMode   = false;
    // Filter overlay
    private boolean        showFilter          = false;
    private boolean        filterCompare       = false;
    private FilterType     filterType          = FilterType.LOW_PASS;
    private FilterResponse filterResponse      = FilterResponse.BUTTERWORTH;
    /** Mode/ripple/atten/edge/order/Q for {@link #filterType}, captured from
     *  the {@code freqRespFilterParamsByType} entry for that type and written
     *  back into it on apply.  Serialised via the same field round-trip as the
     *  map entry (one serialization shape in the whole codebase). */
    private FreqRespFilterTypeParams filterParams = FreqRespFilterTypeParams.fromType(FilterType.LOW_PASS);
    // Unevenness
    private UnevenMode unevenMode = UnevenMode.OFF;
    private boolean unevenNotch   = false;
    private double  unevenDb      = 3.0;
    private double  unevenStartHz = 20.0;
    private double  unevenStopHz  = 20_000.0;
}
