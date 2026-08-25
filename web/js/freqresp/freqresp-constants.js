/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.gui.freqresp.FreqRespConstants.
//
// Constants shared across the FreqResp view, its controller, the settings
// fields and the Tune-notch wizard. A limit or geometry value that two
// measurement paths (or a view and an overlay painted on top of it) must agree
// on lives HERE, under one name - a second definition drifting out of sync is
// exactly how the two paths end up measuring or drawing differently. Constants
// private to one module stay in that module.
//
// Java packages these as an @UtilityClass of public static finals; the web
// equivalent is a module of exported consts. JS numbers are IEEE-754 binary64,
// the same as Java double, so every value below is bit-identical to its Java
// twin - EXCEPT the plot margins, which carry the WEB view's own pixel
// geometry (see PLOT_MARGIN_* below).

// --- Sweep timing ------------------------------------------------------------
/** Lead-in floor (s) - the entry field's clamp, enforced again by the
 *  controller's lead-in preference listener. */
export const LEAD_IN_MIN_SEC = 0.005;
/** Sweep-duration floor (s) - a small FFT size with a long lead-in must not
 *  produce a negative or unworkable sweep. */
export const SWEEP_MIN_SEC = 0.5;

// --- Entry-field limits / precision ------------------------------------------
/** Sweep frequency floor - sub-hertz sweep limits are degenerate, and a log
 *  frequency axis cannot represent 0 Hz. */
export const FREQ_MIN_HZ = 1.0;
/** Amplitude floor (Vrms) for the sweep stimulus entry fields. */
export const AMP_MIN_VRMS = 1e-4;
/** Display precision caps per the numeric-field spec. */
export const FREQ_MAX_DECIMALS = 9;
export const AMP_MAX_DECIMALS = 5;

// --- Magnitude axis ----------------------------------------------------------
/** Hard bottom of the magnitude axis (dB) - the wheel zoom-out limit and the
 *  scrollbar's outer edge share this one value so both reach the same
 *  extreme. */
export const MAG_BOT_MIN_DB = -300.0;

// --- Response-plot geometry --------------------------------------------------
// Margins of the plot rectangle inside FreqRespView. Shared so overlays painted
// on top of the view (the tune-notch target marker) land on the same axes the
// trace is drawn on. The PIXEL VALUES ARE THE WEB VIEW'S OWN (56/4/18, right
// 6/52) and deliberately differ from the desktop's (68/0/28, right 0/52): the
// canvas draws its axis labels in a smaller font than SWT's GC, and the web's
// L/R/phase/max buttons are a DOM overlay (.lr-tools) rather than an SWT bar
// sunk into the plot, so the top margin is a real 4 px inset instead of 0. When
// the phase axis IS visible the right margin grows so the phase tick labels
// have somewhere to live.
export const PLOT_MARGIN_LEFT_PX = 56;
export const PLOT_MARGIN_TOP_PX = 4;
export const PLOT_MARGIN_BOTTOM_PX = 18;
export const PLOT_MARGIN_RIGHT_NO_PHASE_PX = 6;
export const PLOT_MARGIN_RIGHT_PHASE_PX = 52;
