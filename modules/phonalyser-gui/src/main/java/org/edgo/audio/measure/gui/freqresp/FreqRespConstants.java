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

package org.edgo.audio.measure.gui.freqresp;

import lombok.experimental.UtilityClass;

/**
 * Constants shared across the FreqResp pane, its analyzer / controller and the
 * Tune-notch wizard.  A limit or geometry value that two measurement paths (or
 * a view and an overlay painted on top of it) must agree on lives HERE, under
 * one name - a second definition drifting out of sync is exactly how the two
 * paths end up measuring or drawing differently.  Constants private to one
 * class stay in that class.
 */
@UtilityClass
public class FreqRespConstants {

    // --- Sweep timing --------------------------------------------------------
    /** Lead-in floor (s) - the entry field's clamp, enforced again by the
     *  analyzer's config validation.  Long enough that the silence before the
     *  chirp measures the capture's own DC offset per channel (4410 samples at
     *  44.1 kHz), which the deconvolution takes out of the record. */
    public static final double LEAD_IN_MIN_SEC = 0.1;
    /** Sweep-duration floor (s) - a small FFT size with a long lead-in
     *  must not produce a negative or unworkable sweep. */
    public static final double SWEEP_MIN_SEC = 0.5;

    // --- Entry-field limits / precision --------------------------------------
    /** Sweep frequency floor - sub-hertz sweep limits are degenerate, and a
     *  log frequency axis cannot represent 0 Hz. */
    public static final double FREQ_MIN_HZ = 1.0;
    /** Amplitude floor (Vrms) for the sweep stimulus entry fields. */
    public static final double AMP_MIN_VRMS = 1e-4;
    // Display precision caps per the numeric-field spec.
    public static final int FREQ_MAX_DECIMALS = 9;
    public static final int AMP_MAX_DECIMALS = 5;

    // --- Magnitude axis ------------------------------------------------------
    /** Hard bottom of the magnitude axis (dB) - the wheel zoom-out limit and
     *  the scrollbar's outer edge share this one value so both reach the same
     *  extreme. */
    public static final double MAG_BOT_MIN_DB = -300.0;

    // --- Response-plot geometry ----------------------------------------------
    // Margins of the plot rectangle inside FreqRespView.  Shared so overlays
    // painted on top of the view (the tune-notch target marker) land on the
    // same axes the trace is drawn on.  Top / right margins are 0 by design -
    // the L/R/phase/max buttons overlay the top of the plot area, no axis
    // ticks live on the right edge unless the phase axis is visible.  When
    // phase IS visible, the right margin grows so the phase tick labels have
    // somewhere to live.
    public static final int PLOT_MARGIN_LEFT_PX = 68;
    public static final int PLOT_MARGIN_TOP_PX = 0;
    public static final int PLOT_MARGIN_BOTTOM_PX = 28;
    public static final int PLOT_MARGIN_RIGHT_NO_PHASE_PX = 0;
    public static final int PLOT_MARGIN_RIGHT_PHASE_PX = 52;
}
