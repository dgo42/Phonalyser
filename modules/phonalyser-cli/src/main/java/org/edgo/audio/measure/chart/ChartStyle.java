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

package org.edgo.audio.measure.chart;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.geom.Ellipse2D;

/** Central chart style constants (fonts, sizes, colors, strokes). */
public final class ChartStyle {

    // -------------------------------------------------------------------------
    // Font families & sizes
    // -------------------------------------------------------------------------
    public static final String ANNOTATION_FAMILY = Font.SANS_SERIF;
    public static final String TABLE_FAMILY       = Font.MONOSPACED;
    public static final String AXIS_FAMILY        = Font.SANS_SERIF;

    public static final int ANNOTATION_SIZE   = 15;
    public static final int TABLE_HEADER_SIZE = 15;
    public static final int TABLE_CELL_SIZE   = 15;
    public static final int AXIS_LABEL_SIZE   = 15;
    public static final int AXIS_TICK_SIZE    = 15;
    public static final int CHART_TITLE_SIZE  = 18;

    public static final Font ANNOTATION_FONT   = new Font(ANNOTATION_FAMILY, Font.PLAIN, ANNOTATION_SIZE);
    public static final Font TABLE_HEADER_FONT = new Font(TABLE_FAMILY,      Font.BOLD,  TABLE_HEADER_SIZE);
    public static final Font TABLE_CELL_FONT   = new Font(TABLE_FAMILY,      Font.PLAIN, TABLE_CELL_SIZE);
    public static final Font AXIS_LABEL_FONT   = new Font(AXIS_FAMILY,       Font.PLAIN, AXIS_LABEL_SIZE);
    public static final Font AXIS_TICK_FONT    = new Font(AXIS_FAMILY,       Font.PLAIN, AXIS_TICK_SIZE);
    public static final Font CHART_TITLE_FONT  = new Font(AXIS_FAMILY,       Font.BOLD,  CHART_TITLE_SIZE);

    // -------------------------------------------------------------------------
    // Plot background & grid
    // -------------------------------------------------------------------------
    public static final Color PLOT_BACKGROUND = Color.WHITE;
    public static final Color GRID_LINE_COLOR = new Color(128, 128, 128);

    // -------------------------------------------------------------------------
    // Spectrum series (series 0)
    // -------------------------------------------------------------------------
    public static final Color       SPECTRUM_COLOR  = new Color(0, 100, 200);
    public static final BasicStroke SPECTRUM_STROKE = new BasicStroke(0.8f);

    // -------------------------------------------------------------------------
    // Harmonic peak markers (series 1)
    // -------------------------------------------------------------------------
    public static final Color            PEAK_COLOR = Color.RED;
    public static final Ellipse2D.Double PEAK_SHAPE = new Ellipse2D.Double(-4.5, -4.5, 9.0, 9.0);

    // -------------------------------------------------------------------------
    // Harmonic annotations
    // -------------------------------------------------------------------------
    public static final Color  FUND_ANNOTATION_COLOR = new Color(180, 0, 0);
    public static final Color  HARM_ANNOTATION_COLOR = new Color(160, 0, 0);
    public static final double ANNOTATION_Y_OFFSET   = 5.0;   // dB above peak

    // -------------------------------------------------------------------------
    // Cal overlay (inverted frequency response, series 2) — shown when --cal used
    // -------------------------------------------------------------------------
    public static final Color       CAL_OVERLAY_COLOR  = new Color(0, 150, 0);
    public static final BasicStroke CAL_OVERLAY_STROKE =
            new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                            0.0f, new float[]{6.0f, 4.0f}, 0.0f);

    // -------------------------------------------------------------------------
    // Pre-correction peak markers (series 3) — H1/Hn positions BEFORE --cal was
    // applied, drawn as dots in a distinct blue alongside the red corrected dots
    // -------------------------------------------------------------------------
    public static final Color            PRE_PEAK_COLOR = new Color(0, 0, 200);
    public static final Ellipse2D.Double PRE_PEAK_SHAPE = new Ellipse2D.Double(-4.5, -4.5, 9.0, 9.0);

    // -------------------------------------------------------------------------
    // SNR dim region (out-of-band overlay)
    // -------------------------------------------------------------------------
    public static final Color DIM_COLOR = new Color(200, 200, 200);
    public static final float DIM_ALPHA = 0.3f;

    // -------------------------------------------------------------------------
    // Info table (top-right overlay)
    // -------------------------------------------------------------------------
    public static final Color TABLE_BG_COLOR     = new Color(255, 255, 255, 128);
    public static final Color TABLE_BORDER_COLOR = new Color(160, 160, 160, 200);
    public static final Color TABLE_HEADER_COLOR = Color.DARK_GRAY;
    public static final Color TABLE_LABEL_COLOR  = new Color(80, 80, 80);
    public static final Color TABLE_VALUE_COLOR  = Color.BLACK;
    public static final int   TABLE_PAD          = 6;
    public static final int   TABLE_MARGIN       = 10;   // distance from chart edge
    public static final int   TABLE_ARC          = 8;

    // -------------------------------------------------------------------------
    // Corner info box (bottom-left overlay)
    // -------------------------------------------------------------------------
    public static final Color CORNER_BG_COLOR     = new Color(255, 255, 255, 200);
    public static final Color CORNER_BORDER_COLOR = new Color(180, 180, 180);
    public static final Color CORNER_TEXT_COLOR   = Color.BLACK;
    public static final int   CORNER_PAD          = 6;
    public static final int   CORNER_ARC          = 6;

    private ChartStyle() {}

    // -------------------------------------------------------------------------
    // Voltage-axis tick spacing
    // -------------------------------------------------------------------------
    /**
     * Picks a "nice" tick step in axis units (1, 2, 2.5, 5, 10 × 10^k) so the
     * resulting ticks land roughly every {@code 40 .. 100} pixels along the
     * voltage axis.  Linear ramp between widths 1920 (40 px / tick) and 5000
     * (100 px / tick); clamped outside that range.
     *
     * @param valueRange total span of the axis (e.g. {@code scaleVolts})
     * @param chartWidth chart width in pixels
     * @return tick step in axis units
     */
    public static double chooseVoltageTickStep(double valueRange, int chartWidth) {
        if (chartWidth <= 0 || valueRange <= 0) return 1.0;
        double pixelsPerTick;
        if (chartWidth <= 1920)      pixelsPerTick = 40.0;
        else if (chartWidth >= 5000) pixelsPerTick = 100.0;
        else                         pixelsPerTick = 40.0
                + (chartWidth - 1920) * (100.0 - 40.0) / (5000.0 - 1920.0);
        double rawStep = valueRange * pixelsPerTick / chartWidth;
        double mag  = Math.pow(10.0, Math.floor(Math.log10(rawStep)));
        double norm = rawStep / mag;
        double niceStep;
        if      (norm < 1.5)  niceStep = 1.0 * mag;
        else if (norm < 2.25) niceStep = 2.0 * mag;
        else if (norm < 3.5)  niceStep = 2.5 * mag;
        else if (norm < 7.5)  niceStep = 5.0 * mag;
        else                  niceStep = 10.0 * mag;
        // JFreeChart's NumberAxis silently hides ALL ticks once
        // floor(range/step) + 1 exceeds ValueAxis.MAXIMUM_TICK_COUNT (500).
        // This bites at very wide charts where pixelsPerTick logic alone
        // produces a sub-limit step.  Bump the step until the visible-tick
        // count is comfortably under the limit (450 leaves margin for the
        // +1 off-by-one in JFreeChart's own count).
        final int MAX_TICKS = 450;
        while (valueRange / niceStep > MAX_TICKS) {
            niceStep *= 2.0;
        }
        return niceStep;
    }
}
