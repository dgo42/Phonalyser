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

import org.edgo.audio.measure.dsp.FreqRespCalHelper;

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
    /** Floor (s) of the operator's lead-in - the entry field's clamp.  This
     *  silence comes on top of the fixed {@link FreqRespCalHelper#OFFSET_LEAD_IN_SEC}
     *  window the deconvolution measures each channel's DC offset on, so it
     *  only has to let the chain settle. */
    public static final double LEAD_IN_MIN_SEC = 0.05;
    /** Sweep-duration floor (s) - a small FFT size with a long lead-in
     *  must not produce a negative or unworkable sweep. */
    public static final double SWEEP_MIN_SEC = 0.5;

    // --- Swept band versus read band -----------------------------------------
    /** How far past the configured band the sweep is actually EMITTED, as a
     *  frequency ratio at each end: the start is divided by it and the stop
     *  multiplied by it.
     *
     *  <p>The stimulus is Tukey-faded over
     *  {@link FreqRespCalHelper#SWEEP_FADE_FRACTION_PER_SIDE} of the sweep at
     *  each end, and the fade's leakage reaches further than the fade itself,
     *  so the outermost part of a swept band is measured on a stimulus that is
     *  on its way to zero - visible as a wobble at the ends of a deep trace.
     *  Sweeping 20 percent further at each end puts that stretch outside the
     *  band the grid, the result, the file and the view are built from, so
     *  nothing of it is read. */
    public static final double SWEEP_BAND_MARGIN = 1.2;

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

    // --- Swept band versus read band -----------------------------------------

    /** The band the sweep is EMITTED over for a measurement configured to
     *  read {@code startHz..stopHz}: the configured band widened by
     *  {@link #SWEEP_BAND_MARGIN} at each end, never below
     *  {@link #FREQ_MIN_HZ} (a log sweep cannot go under it) and never above
     *  {@link #bandCeilingHz} for the two rates.  Returns {start, stop}.
     *
     *  <p>ONE rule for every party to a sweep: the chirp that is played, the
     *  deconvolution reference that models it, and the progress meter that
     *  maps elapsed time onto frequency all have to agree on this band, and a
     *  chirp played over one band and divided by a reference over another is
     *  a chirp of a different rate - the response then carries a phase ramp
     *  of thousands of degrees and its edges collapse. */
    public double[] sweptBandHz(double startHz, double stopHz,
                                int captureRateHz, int playbackRateHz) {
        return sweptBandHz(startHz, stopHz, captureRateHz, playbackRateHz, SWEEP_BAND_MARGIN);
    }

    /** The same rule with the widening factor named: {@code margin} is the
     *  ratio the band is widened by at each end, {@link #SWEEP_BAND_MARGIN}
     *  for a frequency-response measurement and 1.0 for a sweep that has to
     *  spend its whole length on the band it reads - the floor and the
     *  ceiling still apply. */
    public double[] sweptBandHz(double startHz, double stopHz,
                                int captureRateHz, int playbackRateHz, double margin) {
        double lo = Math.max(FREQ_MIN_HZ, startHz / margin);
        double hi = Math.min(stopHz * margin, bandCeilingHz(captureRateHz, playbackRateHz));
        return new double[] { lo, Math.max(lo, hi) };
    }

    /** The highest frequency a measurement can both emit and observe: half the
     *  LOWER of the two configured rates.  Above the DAC's Nyquist the chirp is
     *  not what leaves the card, above the ADC's it is not what comes back. */
    public double bandCeilingHz(int captureRateHz, int playbackRateHz) {
        return Math.min(captureRateHz, playbackRateHz) / 2.0;
    }
}
