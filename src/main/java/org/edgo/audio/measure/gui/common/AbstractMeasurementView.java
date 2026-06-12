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

package org.edgo.audio.measure.gui.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntToDoubleFunction;
import java.util.function.IntUnaryOperator;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.LineAttributes;
import org.eclipse.swt.graphics.Path;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.edgo.audio.measure.enums.MagnitudeUnit;

/**
 * Shared base for the project's measurement canvases — oscilloscope, FFT,
 * frequency response, and the condensed multi-view.
 *
 * <p>The base intentionally stays out of paint: each subclass owns its own
 * colour palette, fonts, axis math, and button widgets.  What lives here is
 * the boilerplate that was being copy-pasted across every view:
 *
 * <ul>
 *   <li>The shared colour palette (role &rarr; {@link Color}) with prefs-driven
 *       overrides and {@link #newColor} for 0xRRGGBB &rarr; Color conversion.</li>
 *   <li>Small paint helpers — {@link #drawCenteredIcon} and {@link #setBounds}
 *       — plus the grid + axis facility used by every chart.</li>
 * </ul>
 *
 * <p>This is deliberately minimal: no abstract methods, no mouse listeners
 * installed by the base.  Header buttons are now {@code ToolButton} widgets in
 * each view, so the old hotspot registry is gone; subclasses keep their own
 * {@code onMouseMove} / {@code onMouseDown} handlers for sliders, crosshairs,
 * and editable labels.
 */
public abstract class AbstractMeasurementView extends Canvas {

    protected static final int MAJOR_TICK_LEN = 6;
    protected static final int MINOR_TICK_LEN = 2;
    private static final int MAJOR_TICK_WIDTH = 2;
    /** Major-tick target count for a LOG axis zoomed inside one decade, where
     *  decade boundaries alone would leave ≤ 1 tick — nice-number linear
     *  stepping takes over with roughly this many labels across the span. */
    private static final int SUB_DECADE_TICK_TARGET = 12;

    // --- Header button geometry (shared by every view's header Toolbar / ToolWindow) ---
    protected static final int BTN_W   = 22;   // header button width
    protected static final int BTN_H   = 22;   // header button height
    protected static final int BTN_TOP = 4;    // header button row's top inset

    // =========================================================================
    // Shared colour palette.
    //
    // Each role (background, grid, …) is keyed by {@link ColorRole};
    // the base owns one Color per role in {@link #palette}, allocated
    // once at construction.  Subclasses access them via
    // {@link #color(ColorRole)}, override defaults by passing a
    // {@code Map<ColorRole, Integer>} of packed RGBs to the
    // overriding constructor, and re-assign prefs-driven entries via
    // {@link #setColor(ColorRole, int)}.  Storing the palette in an
    // {@code EnumMap} keeps the access pattern symbolic and lets the
    // base auto-dispose every role with a single iteration on tear-down.
    // =========================================================================

    /** Named slots for every colour the base palette tracks.
     *  Subclasses look up a colour by role rather than by field name. */
    public enum ColorRole {
        /** Plot background fill. */                                       BACKGROUND,
        /** Grid line colour. */                                           GRID,
        /** Axis-line / frame colour. */                                   AXIS,
        /** Tick-label and small readout text. */                          TEXT,
        /** Cross-hair line colour. */                                     CROSSHAIR,
        /** Tooltip / readout box background. */                           OVERLAY_BG,
        /** Left-channel trace on the chart (prefs-overridable). */        LEFT_TRACE,
        /** Right-channel trace on the chart (prefs-overridable). */       RIGHT_TRACE,
        /** Fill of the "L" header button. */                              LEFT_BTN_CHAN,
        /** Fill of the "R" header button. */                              RIGHT_BTN_CHAN,
        /** Frame / outline for header buttons. */                         BUTTON_FRAME,
        /** Red glyph for reset / clear-stats buttons. */                  RESET,
        /** Bright phase of a two-phase blink overlay. */                  BLINK_LIT,
        /** Dim phase of a two-phase blink overlay. */                     BLINK_DIM,
        /** Lit phase of the warning blink banner — separately configurable
         *  from the neutral {@link #BLINK_LIT} blink. */                   WARNING_LIT,
        /** Dim phase of the warning blink banner. */                      WARNING_DIM,
        /** 65 % attenuation of {@link #LEFT_TRACE} — inactive / unselected
         *  states of L-channel UI elements (scope only). */               LEFT_CHANNEL_MID,
        /** 65 % attenuation of {@link #RIGHT_TRACE} — symmetric to
         *  {@link #LEFT_CHANNEL_MID} (scope only). */                     RIGHT_CHANNEL_MID,
        /** 80 % brightness ("20 % darker") of {@link #LEFT_TRACE} —
         *  used by the dual-tone reconstructed-beat overlay when the
         *  trigger channel is L (scope only). */                          LEFT_BEAT,
        /** 80 % brightness of {@link #RIGHT_TRACE} — symmetric to
         *  {@link #LEFT_BEAT} (scope only). */                            RIGHT_BEAT,
        /** Neutral grey shown in place of a trace when its channel
         *  isn't currently captured (scope only). */                      DISABLED_CHANNEL,
        /** FFT spectrum trace (prefs-driven, FFT only). */                SPECTRUM,
        /** Light-grey fill used to dim spectrum regions outside the
         *  active distortion HP / LP window (FFT only). */                DIM,
        /** Fundamental / harmonic dot markers (FFT only). */              HARMONIC_DOT,
        /** Pre-calibration dot markers (FFT only). */                     BEFORE_CAL_DOT,
        /** Frequency-response calibration trace overlay (FFT only). */    FREQ_RESP_RESPONSE,
        /** Inverted-cascade calibration overlay trace (FFT only). */      CAL_OVERLAY,
        /** Phase trace (prefs-driven, FreqResp only). */                  PHASE_TRACE,
        /** RIAA / reference curve trace (FreqResp only). */               RIAA_TRACE,
        /** Compare-mode trace — dark green (FreqResp only). */            COMPARE_TRACE,
        /** Active state fill of FreqResp toggle buttons. */               BUTTON_ACTIVE,
    }

    /** Packed-RGB defaults for every {@link ColorRole}.  Subclass
     *  constructors pass a small {@code Map<ColorRole, Integer>} to
     *  override individual entries — the merged map is consumed once
     *  at construction so no Color object is ever allocated twice. */
    private static final Map<ColorRole, Integer> DEFAULT_RGB;
    static {
        EnumMap<ColorRole, Integer> m = new EnumMap<>(ColorRole.class);
        m.put(ColorRole.BACKGROUND,      0xFFFFFF);
        m.put(ColorRole.GRID,            0xDCDCDC);
        m.put(ColorRole.AXIS,            0x808080);
        m.put(ColorRole.TEXT,            0x202020);
        m.put(ColorRole.CROSSHAIR,       0x909090);
        m.put(ColorRole.OVERLAY_BG,      0xF6F6F6);
        m.put(ColorRole.LEFT_TRACE,      0x0064C8);
        m.put(ColorRole.RIGHT_TRACE,     0x0064C8);
        m.put(ColorRole.LEFT_BTN_CHAN,   0x0057B7);
        m.put(ColorRole.RIGHT_BTN_CHAN,  0xFFD700);
        m.put(ColorRole.BUTTON_FRAME,    0x606060);
        m.put(ColorRole.RESET,           0xDC1414);
        m.put(ColorRole.BLINK_LIT,       0x000000);
        m.put(ColorRole.BLINK_DIM,       0xC0C0C0);   // light grey ⇒ a clearly-visible black↔grey pulse on white
        m.put(ColorRole.WARNING_LIT,     0xF00000);
        m.put(ColorRole.WARNING_DIM,     0x202020);
        m.put(ColorRole.COMPARE_TRACE,   0x1B5E20);
        m.put(ColorRole.BUTTON_ACTIVE,   0xC0D8F0);
        DEFAULT_RGB = m;
    }

    private final EnumMap<ColorRole, Color> palette = new EnumMap<>(ColorRole.class);

    /** Constructor accepting per-role RGB overrides.  For each role the
     *  override (if present) wins over the {@link #DEFAULT_RGB} entry;
     *  roles with neither stay {@code null} in the palette — typical
     *  for the scope-only mid / disabled-channel slots that aren't
     *  meaningful to FFT or FreqResp.  The base allocates exactly one
     *  Color per role using the merged value, so a subclass that needs
     *  a dark theme passes its five or six override RGBs in one map
     *  literal and avoids the dispose-and-reallocate pattern. */
    protected AbstractMeasurementView(Composite parent, int style,
                                      Map<ColorRole, Integer> overrides) {
        super(parent, style);
        for (ColorRole role : ColorRole.values()) {
            Integer rgb = (overrides != null) ? overrides.get(role) : null;
            if (rgb == null) rgb = DEFAULT_RGB.get(role);
            if (rgb == null) continue;     // role unused by this view
            palette.put(role, newColor(rgb));
        }
    }

    /** Returns {@code packedRgb} with each 8-bit channel scaled by
     *  {@code factor}.  Used by the scope to derive its "mid" (~65 %)
     *  channel colours from the user-selected trace colour — pure RGB
     *  math, but kept on the base so subclasses don't reinvent it. */
    protected int attenuate(int packedRgb, double factor) {
        int r = clamp8((int) Math.round(((packedRgb >> 16) & 0xFF) * factor));
        int g = clamp8((int) Math.round(((packedRgb >>  8) & 0xFF) * factor));
        int b = clamp8((int) Math.round(( packedRgb        & 0xFF) * factor));
        return (r << 16) | (g << 8) | b;
    }
    private int clamp8(int v) { return v < 0 ? 0 : (v > 0xFF ? 0xFF : v); }

    /** Returns the live {@link Color} for {@code role} — never null
     *  unless the view has already been disposed. */
    protected final Color color(ColorRole role) {
        return palette.get(role);
    }

    /** Draws {@code s} at ({@code x}, {@code y}) with a 1-px outline
     *  in the view's {@link ColorRole#BACKGROUND} colour behind the
     *  current foreground.  Used by views that paint readouts on top
     *  of a live signal trace (scope, FFT, FreqResp) so the text
     *  stays legible regardless of the colour of the pixels
     *  underneath.  The outline matches the chart background — black
     *  on the scope, white on FFT / FreqResp — giving a background-
     *  coloured halo that always contrasts the foreground.  Stamps
     *  the outline colour at the four N/S/E/W ±1-px neighbours (the
     *  anti-aliased glyph edges fill the diagonals visually — an
     *  8-neighbour halo reads the same at nearly double the GDI text
     *  calls, and the scope's readout table alone is ~50 labels per
     *  paint at 50 fps), then the original foreground on top; restores
     *  the foreground before returning so the caller's GC state is
     *  unchanged. */
    protected final void drawOutlinedText(GC gc, String s, int x, int y) {
        Color fg = gc.getForeground();
        gc.setForeground(color(ColorRole.BACKGROUND));
        gc.drawText(s, x - 1, y, true);
        gc.drawText(s, x + 1, y, true);
        gc.drawText(s, x, y - 1, true);
        gc.drawText(s, x, y + 1, true);
        gc.setForeground(fg);
        gc.drawText(s, x, y, true);
    }

    /** Right-aligned variant of {@link #drawOutlinedText} — places
     *  the text so its right edge lands at {@code rightX}. */
    protected final void drawOutlinedRightAligned(GC gc, String s, int rightX, int y) {
        Point ts = gc.textExtent(s);
        drawOutlinedText(gc, s, rightX - ts.x, y);
    }

    /** Fills the polygon described by {@code poly} with {@code fillColor}
     *  then strokes a 1-px outline in the view's
     *  {@link ColorRole#BACKGROUND} colour around it.  Used for
     *  marker triangles / handles that sit on top of the live
     *  trace, so they remain visible against any signal colour
     *  underneath while still blending with the chart background.
     *  Restores foreground / background. */
    protected final void drawOutlinedFilledPolygon(GC gc, int[] poly, Color fillColor) {
        Color prevBg = gc.getBackground();
        Color prevFg = gc.getForeground();
        gc.setBackground(fillColor);
        gc.fillPolygon(poly);
        gc.setForeground(color(ColorRole.BACKGROUND));
        gc.drawPolygon(poly);
        gc.setBackground(prevBg);
        gc.setForeground(prevFg);
    }

    /** Draws a dashed line from ({@code x1}, {@code y1}) to ({@code x2},
     *  {@code y2}) in {@code color} with a 1-px halo in the view's
     *  {@link ColorRole#BACKGROUND} colour on either side.  Strokes
     *  the same dashed line at line-width 3 in the background colour
     *  first, then at line-width 1 in the caller's colour on top —
     *  the wider background underneath shows as a 1-px edge around
     *  each coloured dash.  Restores line width, dash, and
     *  foreground. */
    protected final void drawOutlinedDashedLine(GC gc, int x1, int y1, int x2, int y2,
                                                int[] dash, Color color) {
        int prevWidth = gc.getLineWidth();
        int[] prevDash = gc.getLineDash();
        Color prevFg = gc.getForeground();
        gc.setLineDash(dash);
        gc.setLineWidth(3);
        gc.setForeground(color(ColorRole.BACKGROUND));
        gc.drawLine(x1, y1, x2, y2);
        gc.setLineWidth(1);
        gc.setForeground(color);
        gc.drawLine(x1, y1, x2, y2);
        gc.setLineDash(prevDash);
        gc.setLineWidth(prevWidth);
        gc.setForeground(prevFg);
    }

    /** Returns the live {@link RGB} for {@code role} — never null
     *  unless the view has already been disposed. */
    protected final RGB rgb(ColorRole role) {
        return palette.get(role).getRGB();
    }

    /** Allocates a fresh colour for {@code role} from {@code packedRgb}
     *  and disposes the previous one (if any).  Cheap to call on every
     *  paint: if the existing palette entry already has the same RGB,
     *  this is a no-op, so subclasses don't need their own
     *  "currentXxxRgb" caches to gate prefs-driven re-syncs. */
    protected final void setColor(ColorRole role, int packedRgb) {
        Color prev = palette.get(role);
        if (prev != null) {
            int prevRgb = (prev.getRed() << 16) | (prev.getGreen() << 8) | prev.getBlue();
            if (prevRgb == packedRgb) return;
            prev.dispose();
        }
        palette.put(role, newColor(packedRgb));
    }

    /** Disposes every palette entry.  Subclasses call this from their
     *  own dispose handler. */
    protected final void disposePalette() {
        for (Color c : palette.values()) {
            if (c != null) c.dispose();
        }
        palette.clear();
    }


    /** Blits {@code icon} centred in the rectangle
     *  {@code (x, y, w, h)}.  No-op when {@code icon} is {@code null} or
     *  disposed.  Shared by every view that paints SVG glyphs inside a
     *  header button frame — the centring math was open-coded in three
     *  places before this helper. */
    protected final void drawCenteredIcon(GC gc, Image icon, int x, int y, int w, int h) {
        if (icon == null || icon.isDisposed()) return;
        Rectangle ib = icon.getBounds();
        gc.drawImage(icon, x + (w - ib.width) / 2, y + (h - ib.height) / 2);
    }

    /** Sets a {@link Rectangle}'s bounds in one call — the mutable-rect helper the
     *  header-layout code in every view uses to position its hit-test boxes. */
    protected final void setBounds(Rectangle r, int x, int y, int w, int h) {
        r.x = x; r.y = y; r.width = w; r.height = h;
    }

    /** Allocates a fresh SWT {@link Color} from a packed 24-bit
     *  {@code 0xRRGGBB} int.  Subclasses use this as the single point of
     *  RGB → Color conversion instead of open-coding the same unpack in
     *  every {@code syncColors} method.  The caller owns the returned
     *  Color and is responsible for disposing it. */
    protected final Color newColor(int packedRgb) {
        return new Color(getDisplay(),
                (packedRgb >> 16) & 0xFF,
                (packedRgb >>  8) & 0xFF,
                 packedRgb        & 0xFF);
    }


    // =========================================================================
    // Grid + axis facility
    //
    // A single drawGrid call paints a 2-D measurement grid plus optional
    // axis tick labels on the left, bottom, and right edges, plus an
    // optional scope-style cross-hair overlay.  Each axis is configured
    // independently — LINEAR or LOG scale, own range, optional label
    // formatter — so the same primitive serves the oscilloscope, FFT,
    // and frequency-response views.
    // =========================================================================

    /** Tick-position strategy for an axis.
     *  <ul>
     *    <li>{@link #LINEAR} — evenly-spaced {@code divisions+1} ticks; no minors.</li>
     *    <li>{@link #LINEAR_NICE} — "nice numbers" 1 / 2 / 2.5 / 5 × 10ⁿ majors
     *        targeting ~{@code targetCount} ticks; minors at a caller-supplied
     *        {@code minorStep} (e.g. 5 dB for dB axes).</li>
     *    <li>{@link #LOG} — decade majors (10ⁿ) with 2..9 × 10ⁿ minors.  Major
     *        labels are adaptively thinned based on the decade count visible.</li>
     *  </ul> */
    public enum Scale { LINEAR, LINEAR_NICE, LOG }

    /** Tick-label formatter selector.  Names the formatting style each
     *  axis should use; the {@link #drawGrid} implementation dispatches
     *  to the matching {@code format*} instance method on this base
     *  class so subclasses don't need to wire lambdas. */
    public enum LabelFormat {
        /** No tick labels — only grid lines and (optional) edge marks. */
        NONE,
        /** Compact frequency: {@code "1.0 Hz"} / {@code "1.50 kHz"}.
         *  Use on log frequency axes. */
        FREQ,
        /** Integer-only frequency: {@code "1000 Hz"} / {@code "12 kHz"}.
         *  Use on linear frequency axes. */
        FREQ_INT,
        /** dB value: {@code "%.1f"} (no unit suffix; the unit caption
         *  goes on the axis once via {@link AxisSpec#withUnit(String)}). */
        DB,
        /** Phase: {@code "-180°"} / {@code "90°"} integer degrees. */
        PHASE_DEG,
        /** Voltage with SI prefix: {@code "1.5 V"} / {@code "100 mV"} /
         *  {@code "1 µV"}.  Use on linear voltage axes (V, V/√Hz). */
        VOLTS_SI,
    }

    /** Spec for one axis on the grid.  Build via the static
     *  {@link #linear(double, double, int)} /
     *  {@link #linearNice(double, double, int, double)} /
     *  {@link #log(double, double)} factories; attach a label formatter
     *  with {@link #withFormat(LabelFormat)} and an axis caption with
     *  {@link #withUnit(String)}.  A {@link LabelFormat#NONE} format
     *  means "no tick labels on this axis"; the grid lines are still drawn. */
    public static final class AxisSpec {
        public final Scale scale;
        public final double min, max;
        /** Number of equal divisions for LINEAR axes; produces
         *  {@code divisions + 1} tick positions including both endpoints.
         *  Ignored for LINEAR_NICE and LOG. */
        public final int divisions;
        /** Target number of major ticks for LINEAR_NICE.  The actual count is
         *  rounded to the nearest 1 / 2 / 2.5 / 5 × 10ⁿ step that fits.
         *  Ignored for LINEAR and LOG. */
        public final int targetCount;
        /** Step size for minor ticks on LINEAR_NICE.  Set to 0 (or negative)
         *  to suppress minors.  Ignored for LINEAR and LOG. */
        public final double minorStep;
        /** Which built-in formatter to use for tick labels;
         *  {@link LabelFormat#NONE} suppresses labels on this axis. */
        public final LabelFormat labelFormat;
        /** Optional unit caption ("dB", "Hz", "V", "φ", "V/√Hz", …) painted
         *  adjacent to the axis: top-left of left Y, top-right of right Y,
         *  bottom-right of X.  {@code null} = no caption. */
        public final String unit;

        public AxisSpec(Scale scale, double min, double max,
                        int divisions, int targetCount, double minorStep,
                        LabelFormat labelFormat, String unit) {
            this.scale       = scale;
            this.min         = min;
            this.max         = max;
            this.divisions   = divisions;
            this.targetCount = targetCount;
            this.minorStep   = minorStep;
            this.labelFormat = labelFormat != null ? labelFormat : LabelFormat.NONE;
            this.unit        = unit;
        }

        /** Linear axis with {@code divisions} equal steps; no minors. */
        public static AxisSpec linear(double min, double max, int divisions) {
            return new AxisSpec(Scale.LINEAR, min, max, divisions, 0, 0.0,
                                LabelFormat.NONE, null);
        }
        /** Linear axis with nice-number majors (1 / 2 / 2.5 / 5 × 10ⁿ)
         *  targeting ~{@code targetCount} ticks, plus minor ticks every
         *  {@code minorStep} units. */
        public static AxisSpec linearNice(double min, double max,
                                          int targetCount, double minorStep) {
            return new AxisSpec(Scale.LINEAR_NICE, min, max, 0,
                                Math.max(2, targetCount), minorStep,
                                LabelFormat.NONE, null);
        }
        /** Log axis with decade majors (10ⁿ) and 2..9 × 10ⁿ minors. */
        public static AxisSpec log(double min, double max) {
            return new AxisSpec(Scale.LOG, min, max, 0, 0, 0.0,
                                LabelFormat.NONE, null);
        }
        /** Returns a copy of this spec using the given built-in label format. */
        public AxisSpec withFormat(LabelFormat fmt) {
            return new AxisSpec(scale, min, max, divisions, targetCount, minorStep,
                                fmt, unit);
        }
        /** Returns a copy of this spec with the given unit caption. */
        public AxisSpec withUnit(String unit) {
            return new AxisSpec(scale, min, max, divisions, targetCount, minorStep,
                                labelFormat, unit);
        }
    }

    /** Optional cross-hair overlay drawn on top of the grid — used by
     *  the oscilloscope to mark the centred trigger / offset axes with
     *  a coloured cross plus short sub-division tick marks.  Pass a
     *  {@code CrossHairSpec} to {@link #drawGrid} to enable; pass
     *  {@code null} to skip. */
    public static final class CrossHairSpec {
        /** Cross intersection X position, as a fraction of plot width. */
        public final double xFrac;
        /** Cross intersection Y position, as a fraction of plot height. */
        public final double yFrac;
        /** Cross-hair line colour. */
        public final Color  color;
        /** Sub-tick count per division along each arm; 0 = no sub-ticks. */
        public final int    subTicksPerDivision;
        /** Half-length (px) of each sub-tick mark perpendicular to the arm. */
        public final int    subTickHalfLen;
        /** Number of full grid divisions along the X axis — used only to
         *  size the sub-tick interval; mirrors the AxisSpec.divisions
         *  the caller passed for the X axis. */
        public final int    xDivisions;
        /** Number of full grid divisions along the Y axis — same role
         *  as {@link #xDivisions} for the Y axis. */
        public final int    yDivisions;

        public CrossHairSpec(double xFrac, double yFrac, Color color,
                             int subTicksPerDivision, int subTickHalfLen,
                             int xDivisions, int yDivisions) {
            this.xFrac               = xFrac;
            this.yFrac               = yFrac;
            this.color               = color;
            this.subTicksPerDivision = subTicksPerDivision;
            this.subTickHalfLen      = subTickHalfLen;
            this.xDivisions          = xDivisions;
            this.yDivisions          = yDivisions;
        }
    }

    /**
     * Paints a 2-D measurement grid + optional axis labels + optional
     * unit captions inside {@code plot}.  X axis along the bottom,
     * primary Y along the left, optional secondary Y along the right
     * (with its own scale & range — for e.g. phase ±180° opposite
     * magnitude dB).  Each axis's grid lines are drawn at tick positions
     * computed from its {@link AxisSpec}:
     * <ul>
     *   <li>{@link Scale#LINEAR}: {@code divisions + 1} evenly-spaced ticks
     *       including both endpoints; no minors.</li>
     *   <li>{@link Scale#LINEAR_NICE}: nice-number majors (1 / 2 / 2.5 / 5 × 10ⁿ)
     *       targeting ~{@code targetCount} ticks, plus minors every {@code minorStep}.</li>
     *   <li>{@link Scale#LOG}: decade majors (10ⁿ) plus 2..9 × 10ⁿ minors; major
     *       labels are adaptively thinned based on visible decade count so
     *       narrow zoom levels still show 2× and 5× labels.</li>
     * </ul>
     *
     * <p>Label text is produced by each axis's {@code labelFmt}; a
     * {@code null} formatter suppresses tick labels on that axis.  Labels
     * render outside the plot rectangle — X labels below, primary Y
     * labels left, secondary Y labels right — so the caller is
     * responsible for reserving margin around {@code plot}.
     *
     * <p>An {@link AxisSpec#unit} caption is painted top-anchored near
     * each axis, overpainting a small {@code background-coloured}
     * (passed via the GC's current background) rectangle so it doesn't
     * collide with the topmost tick label.
     *
     * <p>The optional {@link CrossHairSpec} draws a centred (or
     * fractionally-positioned) cross-hair in its own colour with short
     * sub-tick marks along each arm.  Pass {@code null} to skip the
     * overlay entirely (FFT / freq-response leave it off).
     *
     * @param gc                GC to paint into; its current background
     *                          is used for unit-caption over-paint.
     * @param plot              rectangle the grid is drawn inside (absolute pixels)
     * @param x                 X-axis spec (bottom)
     * @param yLeft             primary Y-axis spec (left)
     * @param yRight            secondary Y-axis spec (right) — null for none
     * @param gridColor         colour of the grid lines
     * @param axisColor         colour of the plot frame border (and label text
     *                          when {@code labelColor} is null)
     * @param labelColor        colour of axis tick labels (null ⇒ uses {@code axisColor})
     * @param labelFont         font for axis tick labels (null ⇒ GC's current font)
     * @param majorEdgeMarkPx   length (px) of perpendicular tick mark drawn at
     *                          each major tick on the plot frame; 0 = no marks.
     * @param minorEdgeMarkPx   length (px) of perpendicular tick mark drawn at
     *                          each minor tick on the plot frame; 0 = no marks.
     * @param cross             optional cross-hair overlay (null ⇒ none)
     */
    protected final void drawGrid(GC gc, Rectangle plot,
                                  AxisSpec x, AxisSpec yLeft, AxisSpec yRight,
                                  Color gridColor, Color axisColor,
                                  Color labelColor, Font labelFont,
                                  int majorEdgeMarkPx, int minorEdgeMarkPx,
                                  CrossHairSpec cross) {
        // --- Tick positions ----------------------------------------------
        double[] xMajors = majorTicks(x);
        double[] xMinors = minorTicks(x);
        double[] yMajors = majorTicks(yLeft);
        double[] yMinors = minorTicks(yLeft);
        double[] yrMajors = (yRight != null) ? majorTicks(yRight) : null;

        // --- Grid lines (X) ---------------------------------------------
        gc.setForeground(gridColor);
        for (double v : xMajors) {
            int px = valueToX(v, x, plot);
            gc.drawLine(px, plot.y, px, plot.y + plot.height);
        }
        for (double v : xMinors) {
            int px = valueToX(v, x, plot);
            gc.drawLine(px, plot.y, px, plot.y + plot.height);
        }

        // --- Grid lines (Y) — primary (left) drives the horizontal grid. --
        for (double v : yMajors) {
            int py = valueToY(v, yLeft, plot);
            gc.drawLine(plot.x, py, plot.x + plot.width, py);
        }
        for (double v : yMinors) {
            int py = valueToY(v, yLeft, plot);
            gc.drawLine(plot.x, py, plot.x + plot.width, py);
        }

        // --- Optional cross-hair overlay ----------------------------------
        if (cross != null) {
            int cx = plot.x + (int) Math.round(cross.xFrac * plot.width);
            int cy = plot.y + (int) Math.round(cross.yFrac * plot.height);
            gc.setForeground(cross.color);
            gc.drawLine(cx, plot.y, cx, plot.y + plot.height);
            gc.drawLine(plot.x, cy, plot.x + plot.width, cy);
            if (cross.subTicksPerDivision > 0
                    && cross.xDivisions > 0 && cross.yDivisions > 0) {
                double divW = (double) plot.width  / cross.xDivisions;
                double divH = (double) plot.height / cross.yDivisions;
                double tickX = divW / cross.subTicksPerDivision;
                double tickY = divH / cross.subTicksPerDivision;
                int totalX = cross.xDivisions * cross.subTicksPerDivision;
                int totalY = cross.yDivisions * cross.subTicksPerDivision;
                int total  = Math.max(totalX, totalY);
                for (int i = 0; i <= total; i++) {
                    if (i <= totalY) {
                        int py = plot.y + (int) Math.round(i * tickY);
                        gc.drawLine(cx - cross.subTickHalfLen, py,
                                    cx + cross.subTickHalfLen, py);
                    }
                    if (i <= totalX) {
                        int px = plot.x + (int) Math.round(i * tickX);
                        gc.drawLine(px, cy - cross.subTickHalfLen,
                                    px, cy + cross.subTickHalfLen);
                    }
                }
            }
        }

        // --- Plot frame ---------------------------------------------------
        gc.setForeground(axisColor);
        gc.drawRectangle(plot.x, plot.y, plot.width, plot.height);

        // --- Perpendicular edge tick marks on the frame ------------------
        if (majorEdgeMarkPx > 0 || minorEdgeMarkPx > 0) {
            int prevLineWidth = gc.getLineWidth();
            // X axis ticks on the bottom edge, pointing down/outward.
            int xBottom = plot.y + plot.height;
            if (majorEdgeMarkPx > 0) {
                gc.setLineWidth(MAJOR_TICK_WIDTH);
                for (double v : xMajors) {
                    int px = valueToX(v, x, plot);
                    gc.drawLine(px, xBottom, px, xBottom + majorEdgeMarkPx);
                }
                gc.setLineWidth(prevLineWidth);
            }
            if (minorEdgeMarkPx > 0) {
                for (double v : xMinors) {
                    int px = valueToX(v, x, plot);
                    gc.drawLine(px, xBottom, px, xBottom + minorEdgeMarkPx);
                }
            }
            // Left Y axis ticks on the left edge, pointing outward (left).
            if (majorEdgeMarkPx > 0) {
                gc.setLineWidth(MAJOR_TICK_WIDTH);
                for (double v : yMajors) {
                    int py = valueToY(v, yLeft, plot);
                    gc.drawLine(plot.x - majorEdgeMarkPx, py, plot.x, py);
                }
                gc.setLineWidth(prevLineWidth);
            }
            if (minorEdgeMarkPx > 0) {
                for (double v : yMinors) {
                    int py = valueToY(v, yLeft, plot);
                    gc.drawLine(plot.x - minorEdgeMarkPx, py, plot.x, py);
                }
            }
            // Right Y axis ticks on the right edge, pointing outward (right).
            if (yRight != null) {
                double[] yrMinors = minorTicks(yRight);
                int xRight = plot.x + plot.width;
                if (majorEdgeMarkPx > 0) {
                    gc.setLineWidth(MAJOR_TICK_WIDTH);
                    for (double v : yrMajors) {
                        int py = valueToY(v, yRight, plot);
                        gc.drawLine(xRight, py, xRight + majorEdgeMarkPx, py);
                    }
                    gc.setLineWidth(prevLineWidth);
                }
                if (minorEdgeMarkPx > 0) {
                    for (double v : yrMinors) {
                        int py = valueToY(v, yRight, plot);
                        gc.drawLine(xRight, py, xRight + minorEdgeMarkPx, py);
                    }
                }
            }
        }

        // --- Axis labels + unit captions ---------------------------------
        Font prevFont = gc.getFont();
        Color prevFg  = gc.getForeground();
        if (labelFont != null) gc.setFont(labelFont);
        Color textCol = labelColor != null ? labelColor : axisColor;
        gc.setForeground(textCol);
        try {
            // X tick labels (along the bottom).  For LOG axes the label
            // set is adaptively thinned by adaptiveLogLabels.
            if (x.labelFormat != LabelFormat.NONE) {
                // A log axis zoomed to less than one decade holds at most one
                // decade gridpoint (e.g. only "1000" in a 990–1010 Hz window), so
                // there the axis is ~linear: switch to evenly-spaced round
                // (nice-linear) values formatted finely (Hz with as many decimals
                // as the step needs) instead of a single lonely "1 kHz".
                boolean wideLog = x.scale == Scale.LOG && !isSubDecade(x.min, x.max);
                double[] xLabelPositions = wideLog ? adaptiveLogLabels(x.min, x.max) : xMajors;
                // FREQ labels on a uniform-step axis (linear, or sub-decade log) get a
                // step-aware format so fine zooms read 1.005 kHz / 1.010 kHz instead of
                // several identical "1 kHz"; wide log keeps the decade formatter.
                double fineStep = (x.labelFormat == LabelFormat.FREQ && !wideLog)
                        ? minSpacing(xLabelPositions) : 0.0;
                // Pixel-aware placement: reserve the decade majors (1 / 10 / 100 /
                // 1 kHz…) FIRST so a round decade is never thinned away, then fill the
                // remaining space with the other labels — skipping any whose box would
                // touch one already placed.
                int gap = gc.textExtent("0").x;
                int m = xLabelPositions.length;
                String[] str = new String[m];
                int[] left = new int[m];
                int[] right = new int[m];
                for (int i = 0; i < m; i++) {
                    double v = xLabelPositions[i];
                    str[i]   = fineStep > 0 ? formatFreqTick(v, fineStep)
                                            : applyLabelFormat(x.labelFormat, v);
                    int sw   = gc.textExtent(str[i]).x;
                    left[i]  = valueToX(v, x, plot) - sw / 2;
                    right[i] = left[i] + sw;
                }
                boolean[] drawn = new boolean[m];
                List<int[]> placed = new ArrayList<>();
                for (int pass = 0; pass < 2; pass++) {           // pass 0: decades, pass 1: rest
                    for (int i = 0; i < m; i++) {
                        if (drawn[i] || (pass == 0) != isDecadeValue(xLabelPositions[i])) continue;
                        boolean clash = false;
                        for (int[] o : placed) {
                            if (left[i] < o[1] + gap && right[i] + gap > o[0]) { clash = true; break; }
                        }
                        if (clash) continue;
                        gc.drawText(str[i], left[i], plot.y + plot.height + majorEdgeMarkPx + 2, true);
                        placed.add(new int[]{left[i], right[i]});
                        drawn[i] = true;
                    }
                }
            }
            // Left Y tick labels (to the left of the plot).  Sub-decade log (a zoomed
            // V / V√Hz axis) gets the same fine nice-linear values the grid drew, not the
            // coarse decade-thinned set; a vertical overlap-skip keeps them legible.
            if (yLeft.labelFormat != LabelFormat.NONE) {
                boolean yWideLog = yLeft.scale == Scale.LOG && !isSubDecade(yLeft.min, yLeft.max);
                double[] yLabelPositions = yWideLog ? adaptiveLogLabels(yLeft.min, yLeft.max) : yMajors;
                int fh = gc.getFontMetrics().getHeight();
                int lastPy = Integer.MIN_VALUE;
                for (double v : yLabelPositions) {
                    int py = valueToY(v, yLeft, plot);
                    if (lastPy != Integer.MIN_VALUE && Math.abs(py - lastPy) < fh) continue;
                    String s = applyLabelFormat(yLeft.labelFormat, v);
                    int sw = gc.textExtent(s).x;
                    gc.drawText(s, plot.x - majorEdgeMarkPx - sw - 4, py - fh / 2, true);
                    lastPy = py;
                }
            }
            // Right Y tick labels (to the right of the plot).
            if (yRight != null && yRight.labelFormat != LabelFormat.NONE) {
                boolean yrWideLog = yRight.scale == Scale.LOG && !isSubDecade(yRight.min, yRight.max);
                double[] yrLabelPositions = yrWideLog ? adaptiveLogLabels(yRight.min, yRight.max) : yrMajors;
                int fh = gc.getFontMetrics().getHeight();
                int lastPy = Integer.MIN_VALUE;
                for (double v : yrLabelPositions) {
                    int py = valueToY(v, yRight, plot);
                    if (lastPy != Integer.MIN_VALUE && Math.abs(py - lastPy) < fh) continue;
                    String s = applyLabelFormat(yRight.labelFormat, v);
                    gc.drawText(s, plot.x + plot.width + majorEdgeMarkPx + 4, py - fh / 2, true);
                    lastPy = py;
                }
            }
            // Unit captions — overpaint a small background rectangle on
            // top of the topmost tick label so the caption stays legible.
            // The Y captions sit in the top margin when there's room for
            // them above the plot, otherwise just inside the plot's top edge
            // (e.g. a flush MARGIN_TOP == 0 layout) so they never fall off
            // the top of the canvas.
            int lineH = gc.getFontMetrics().getHeight();
            int capTy = (plot.y >= lineH) ? plot.y - lineH : plot.y + 2;
            Color prevBg = gc.getBackground();
            // Overpaint with the shared light-grey overlay-box colour (the same
            // role the readout/tooltip boxes use) rather than each view's SWT
            // Control background — that is white in FftView (which calls
            // setBackground) but the system widget grey in FreqRespView (which
            // never does), which made the two views' captions look different.
            gc.setBackground(color(ColorRole.OVERLAY_BG));
            if (yLeft.unit != null && !yLeft.unit.isEmpty()) {
                int sw = gc.textExtent(yLeft.unit).x;
                int tx = plot.x - majorEdgeMarkPx - sw - 4;
                // Extend the over-paint to the canvas's left edge so a wide
                // topmost label is fully hidden behind a narrow caption.
                gc.fillRectangle(0, capTy - 1, tx + sw + 2, lineH + 2);
                gc.drawText(yLeft.unit, tx, capTy, true);
            }
            if (yRight != null && yRight.unit != null && !yRight.unit.isEmpty()) {
                int sw = gc.textExtent(yRight.unit).x;
                int tx = plot.x + plot.width + majorEdgeMarkPx + 4;
                gc.fillRectangle(tx - 2, capTy - 1, sw + 4, lineH + 2);
                gc.drawText(yRight.unit, tx, capTy, true);
            }
            if (x.unit != null && !x.unit.isEmpty()) {
                int sw = gc.textExtent(x.unit).x;
                int tx = plot.x + plot.width - sw;
                int ty = plot.y + plot.height + majorEdgeMarkPx + lineH + 2;
                gc.fillRectangle(tx - 2, ty, sw + 4, lineH);
                gc.drawText(x.unit, tx, ty, true);
            }
            gc.setBackground(prevBg);
        } finally {
            if (labelFont != null) gc.setFont(prevFont);
            gc.setForeground(prevFg);
        }
    }

    /** Returns the major tick positions for {@code axis}: evenly-spaced
     *  for LINEAR, nice-number stepping for LINEAR_NICE, decade boundaries
     *  for LOG. */
    private double[] majorTicks(AxisSpec axis) {
        switch (axis.scale) {
            case LINEAR:      return linearTicks(axis.min, axis.max,
                                                 Math.max(1, axis.divisions));
            case LINEAR_NICE: return niceLinearMajors(axis.min, axis.max,
                                                      Math.max(2, axis.targetCount));
            case LOG:         return isSubDecade(axis.min, axis.max)
                                     ? niceLinearMajors(axis.min, axis.max, SUB_DECADE_TICK_TARGET)
                                     : logMajorTicks(axis.min, axis.max);
            default:          return new double[0];
        }
    }

    /** True when a LOG range spans less than one decade — there the 1..9 × 10ⁿ
     *  decade grid holds at most one gridpoint, so ticks fall back to nice-linear. */
    private boolean isSubDecade(double min, double max) {
        double lo = Math.max(1e-15, min);
        double hi = Math.max(lo + 1e-9, max);
        return Math.log10(hi / lo) < 1.0;
    }

    /** True when {@code v} is a 1 × 10ⁿ decade value (1, 10, 100, 1 k…) — these
     *  labels are placed before any others so a round decade is never thinned away. */
    private boolean isDecadeValue(double v) {
        if (!(v > 0)) return false;
        double p = Math.pow(10, Math.round(Math.log10(v)));
        return Math.abs(v - p) <= p * 1e-6;
    }

    /** Fine minor grid positions for a sub-decade LOG zoom: subdivisions of the
     *  nice-linear major step (halves for a step-2 grid, fifths otherwise) minus
     *  the majors — so the fine labels get matching grid lines between them. */
    private double[] subDecadeMinors(double min, double max) {
        double[] majors = niceLinearMajors(min, max, 12);
        if (majors.length < 2) return new double[0];
        double majorStep = majors[1] - majors[0];
        double pow  = Math.pow(10, Math.floor(Math.log10(majorStep)));
        double mant = majorStep / pow;
        double minorStep = majorStep / (Math.abs(mant - 2.0) < 0.1 ? 2 : 5);
        double first = Math.ceil(min / minorStep) * minorStep;
        List<Double> out = new ArrayList<>();
        for (double v = first; v <= max + minorStep * 1e-9; v += minorStep) {
            if (v < min || v > max) continue;
            boolean isMajor = false;
            for (double M : majors) if (Math.abs(v - M) < minorStep * 0.5) { isMajor = true; break; }
            if (!isMajor) out.add(v);
        }
        return toArray(out);
    }

    /** Returns the minor tick positions for {@code axis}: empty for
     *  LINEAR, the caller-supplied step for LINEAR_NICE, 2..9 × 10ⁿ
     *  for LOG. */
    private double[] minorTicks(AxisSpec axis) {
        switch (axis.scale) {
            case LINEAR:      return new double[0];
            case LINEAR_NICE: return (axis.minorStep > 0)
                                     ? niceLinearMinors(axis.min, axis.max, axis.minorStep)
                                     : new double[0];
            case LOG:         return isSubDecade(axis.min, axis.max)
                                     ? subDecadeMinors(axis.min, axis.max)
                                     : logMinorTicks(axis.min, axis.max);
            default:          return new double[0];
        }
    }

    /** {@code divisions + 1} evenly-spaced ticks across [min, max]. */
    private static double[] linearTicks(double min, double max, int divisions) {
        double[] out = new double[divisions + 1];
        for (int i = 0; i <= divisions; i++) {
            out[i] = min + (max - min) * i / divisions;
        }
        return out;
    }

    /** Decade boundaries 10ⁿ inside [min, max]. */
    static double[] logMajorTicks(double min, double max) {
        double safeMin = Math.max(1e-15, min);
        // safeMax must MATCH the formula used by AbstractFreqDomainView.freqToX
        // (max(safeMin + ε, max)) — otherwise the labels and trace use
        // different log ranges and visually misalign at narrow zoom
        // (e.g. a 1 kHz peak rendered near the "2 kHz" label when zoomed
        // to less than one decade).
        double safeMax = Math.max(safeMin + 1e-9, max);
        int lo = (int) Math.floor(Math.log10(safeMin));
        int hi = (int) Math.ceil (Math.log10(safeMax));
        List<Double> out = new ArrayList<>();
        for (int e = lo; e <= hi; e++) {
            double v = Math.pow(10, e);
            if (v >= safeMin && v <= safeMax) out.add(v);
        }
        return toArray(out);
    }

    /** Sub-decade ticks 2..9 × 10ⁿ inside (min, max), excluding the
     *  decade boundaries themselves (those are the majors). */
    static double[] logMinorTicks(double min, double max) {
        double safeMin = Math.max(1e-15, min);
        double safeMax = Math.max(safeMin + 1e-9, max);
        int lo = (int) Math.floor(Math.log10(safeMin)) - 1;
        int hi = (int) Math.ceil (Math.log10(safeMax)) + 1;
        List<Double> out = new ArrayList<>();
        for (int e = lo; e <= hi; e++) {
            double base = Math.pow(10, e);
            for (int k = 2; k <= 9; k++) {
                double v = k * base;
                if (v > safeMin && v < safeMax) out.add(v);
            }
        }
        return toArray(out);
    }

    private static double[] toArray(List<Double> list) {
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    /** Maps a value on the X axis to an absolute pixel column inside
     *  {@code plot}.  Handles both LINEAR and LOG scales. */
    private static int valueToX(double v, AxisSpec axis, Rectangle plot) {
        return plot.x + (int) Math.round(axisFraction(v, axis) * plot.width);
    }

    /** Maps a value on a Y axis to an absolute pixel row inside
     *  {@code plot}.  Y grows downward — {@code v == max} maps to
     *  {@code plot.y}, {@code v == min} maps to {@code plot.y + plot.height}. */
    private static int valueToY(double v, AxisSpec axis, Rectangle plot) {
        double frac = axisFraction(v, axis);
        return plot.y + (int) Math.round((1.0 - frac) * plot.height);
    }

    /** Returns a value's normalised position [0, 1] across the axis,
     *  with the LOG branch matching the {@code log10} convention used
     *  by {@link AbstractFreqDomainView#freqToX}. */
    private static double axisFraction(double v, AxisSpec axis) {
        if (axis.scale == Scale.LOG) {
            double safeMin = Math.max(1e-15, axis.min);
            // Match AbstractFreqDomainView.freqToX so labels and trace
            // span the same log range — a narrow zoom (less than one
            // decade) otherwise puts labels in compressed positions
            // while the trace stretches the full plot width.
            double safeMax = Math.max(safeMin + 1e-9, axis.max);
            double lo = Math.log10(safeMin);
            double hi = Math.log10(safeMax);
            return (Math.log10(Math.max(safeMin, v)) - lo) / (hi - lo);
        }
        return (v - axis.min) / (axis.max - axis.min);
    }

    /** ~{@code targetCount} round-number tick positions between {@code min}
     *  and {@code max}.  Step is 1 / 2 / 2.5 / 5 × 10ⁿ. */
    static double[] niceLinearMajors(double min, double max, int targetCount) {
        double range = Math.max(1e-9, max - min);
        double rough = range / Math.max(1, targetCount);
        double pow   = Math.pow(10, Math.floor(Math.log10(rough)));
        double mant  = rough / pow;
        double step;
        if      (mant < 1.5) step = 1     * pow;
        else if (mant < 3)   step = 2     * pow;
        else if (mant < 4)   step = 2.5   * pow;
        else if (mant < 7)   step = 5     * pow;
        else                 step = 10    * pow;
        double first = Math.ceil(min / step) * step;
        List<Double> out = new ArrayList<>();
        for (double f = first; f <= max + step * 1e-9; f += step) out.add(f);
        return toArray(out);
    }

    /** Fixed-step minor positions inside (min, max) that don't coincide
     *  with the major nice-linear ticks.  Used by LINEAR_NICE axes whose
     *  caller specifies a {@code minorStep} (e.g. 5 dB). */
    private static double[] niceLinearMinors(double min, double max, double minorStep) {
        if (minorStep <= 0) return new double[0];
        double[] majors = niceLinearMajors(min, max, 10);
        double first = Math.ceil(min / minorStep) * minorStep;
        List<Double> out = new ArrayList<>();
        for (double m = first; m <= max + 1e-9; m += minorStep) {
            if (m < min || m > max) continue;
            boolean isMajor = false;
            for (double M : majors) {
                if (Math.abs(m - M) < 1e-6) { isMajor = true; break; }
            }
            if (!isMajor) out.add(m);
        }
        return toArray(out);
    }

    // =========================================================================
    // Shared label formatters.  Single source of truth for tick-label
    // and readout strings across all measurement views.  drawGrid
    // dispatches to these via the LabelFormat on each AxisSpec, so
    // subclasses configure an axis declaratively (enum) instead of
    // wiring a lambda per call site.  FreqRespFormat now holds only
    // domain-specific helpers; none of them duplicate the methods below.
    // =========================================================================

    /** Compact frequency label: "1.0 Hz" / "1.50 kHz". */
    protected String formatFrequency(double f) {
        if (!Double.isFinite(f) || f <= 0) return "—";
        if (f >= 1000) return String.format("%.2f kHz", f / 1000);
        return String.format("%.2f Hz", f);
    }

    /** Frequency tick label for a narrow (sub-decade) zoom: plain Hz with just
     *  enough decimals to resolve {@code step}, so adjacent fine ticks stay
     *  distinct — a {@code %.2f kHz} label would otherwise collapse 1000 and
     *  1002 Hz to the same "1.00 kHz". */
    private static String formatFreqTick(double v, double step) {
        if (!Double.isFinite(v) || v <= 0) return "—";
        if (v >= 1000) {
            double kStep = step / 1000.0;                     // kHz with enough decimals
            int kd = (kStep > 0 && kStep < 1) ? (int) Math.min(4, Math.ceil(-Math.log10(kStep))) : 2;
            return String.format("%." + kd + "f kHz", v / 1000.0);
        }
        int dec = (step > 0 && step < 1) ? (int) Math.min(4, Math.ceil(-Math.log10(step))) : 0;
        return String.format("%." + dec + "f Hz", v);
    }

    /** Smallest gap between consecutive values (0 for fewer than two). */
    private static double minSpacing(double[] vals) {
        double m = Double.POSITIVE_INFINITY;
        for (int i = 1; i < vals.length; i++) m = Math.min(m, Math.abs(vals[i] - vals[i - 1]));
        return Double.isFinite(m) ? m : 0.0;
    }

    /** Fine-grained frequency formatter for crosshair readouts — four
     *  decimal places so sub-Hz refinements stay visible.  Switches to
     *  kHz at 10 kHz so digits stay aligned. */
    protected String formatFrequencyFine(double f) {
        if (!Double.isFinite(f) || f <= 0) return "—";
        if (f >= 10_000) return String.format("%.4f kHz", f / 1000);
        return String.format("%.4f Hz", f);
    }

    /** Integer-only frequency formatter used on linear axis labels — no
     *  fractional Hz, kHz with 0 / 1 decimals depending on size. */
    protected String formatFrequencyInteger(double f) {
        if (!Double.isFinite(f)) return "—";
        if (f >= 1000) {
            double k = f / 1000;
            if (Math.abs(k - Math.round(k)) < 0.05) return String.format("%d kHz", (long) Math.round(k));
            return String.format("%.1f kHz", k);
        }
        return String.format("%d Hz", (long) Math.round(f));
    }

    /** dB tick label without unit suffix — the unit caption is painted
     *  once via {@link AxisSpec#withUnit(String)}.  One decimal. */
    protected String formatDb(double v) {
        if (!Double.isFinite(v)) return "—";
        return String.format(Locale.US, "%.1f", v);
    }

    /** Phase tick label: integer degrees with the ° suffix
     *  ({@code "-180°"}, {@code "0°"}, {@code "180°"}). */
    protected String formatPhaseDeg(double deg) {
        if (!Double.isFinite(deg)) return "—";
        return String.format(Locale.US, "%d°", (int) Math.round(deg));
    }

    /** Voltage tick label with SI prefix: 1.5 V → "1.5 ", 100 mV →
     *  "100 m", 1 µV → "1 µ".  The unit letter (V, V/√Hz, …) is
     *  supplied separately via {@link AxisSpec#withUnit(String)}.
     *  Mantissa trailing zeros are stripped so 1.50 renders as "1.5". */
    protected String formatVoltsSi(double v) {
        if (v == 0) return "0 ";
        double abs = Math.abs(v);
        String prefix;
        double scale;
        if      (abs >= 1e3)  { prefix = "k"; scale = 1e3; }
        else if (abs >= 1)    { prefix = "";  scale = 1;   }
        else if (abs >= 1e-3) { prefix = "m"; scale = 1e-3; }
        else if (abs >= 1e-6) { prefix = "µ"; scale = 1e-6; }
        else if (abs >= 1e-9) { prefix = "n"; scale = 1e-9; }
        else if (abs >= 1e-12){ prefix = "p"; scale = 1e-12; }
        else                  { prefix = "f"; scale = 1e-15; }
        double s = v / scale;
        String m;
        if      (Math.abs(s) >= 100) m = String.format("%.0f", s);
        else if (Math.abs(s) >= 10)  m = String.format("%.1f", s);
        else                          m = String.format("%.2f", s);
        if (m.contains(".")) {
            m = m.replaceAll("0+$", "");
            if (m.endsWith(".")) m = m.substring(0, m.length() - 1);
        }
        return m + " " + prefix;
    }

    /** Magnitude value with the unit suffix glued on — for crosshair
     *  readouts where the user sees the number and unit together.  dB
     *  units use one decimal; linear voltage units route through
     *  {@link #formatVoltsSi}. */
    protected String formatMagnitudeWithUnit(double v, MagnitudeUnit unit) {
        if (!Double.isFinite(v)) return "—";
        switch (unit) {
            case DBFS:      return String.format(Locale.US, "%.1f dBFS", v);
            case DBV:       return String.format(Locale.US, "%.1f dBV",  v);
            case V:         return formatVoltsSi(v) + "V";
            case V_SQRT_HZ: return formatVoltsSi(v) + "V/√Hz";
            default:        return String.format(Locale.US, "%g", v);
        }
    }

    /** Dispatch used by {@link #drawGrid} to render one tick label
     *  according to the axis's {@link LabelFormat}.  Subclasses can
     *  override individual {@code format*} methods to retheme per
     *  view; the dispatch table itself stays fixed. */
    private String applyLabelFormat(LabelFormat fmt, double v) {
        switch (fmt) {
            case FREQ:      return formatFrequency(v);
            case FREQ_INT:  return formatFrequencyInteger(v);
            case DB:        return formatDb(v);
            case PHASE_DEG: return formatPhaseDeg(v);
            case VOLTS_SI:  return formatVoltsSi(v);
            case NONE:
            default:        return "";
        }
    }

    /** Adaptively-thinned label set for LOG axes spanning at least a decade:
     *  as more decades become visible we drop more of the sub-decade positions.
     *  Grid lines and minor ticks at all 1..9 × 10ⁿ positions are still drawn —
     *  only the set of values that get a printed label thins.  (Sub-decade
     *  zooms are handled in {@link #drawGrid} with nice-linear ticks; the
     *  pixel-aware draw loop is the final overlap guard for both.) */
    static double[] adaptiveLogLabels(double min, double max) {
        double safeMin = Math.max(1e-15, min);
        double safeMax = Math.max(safeMin + 1e-9, max);
        int loDec = (int) Math.floor(Math.log10(safeMin));
        int hiDec = (int) Math.ceil (Math.log10(safeMax));
        int decades = hiDec - loDec;
        int[] keep;
        if      (decades <= 1) keep = new int[] {1,2,3,4,5,6,7,8};
        else if (decades <= 2) keep = new int[] {1,2,3,4,5,6,8};
        else if (decades <= 3) keep = new int[] {1,2,3,5,7};
        else if (decades <= 5) keep = new int[] {1,2,5};
        else                   keep = new int[] {1};
        List<Double> out = new ArrayList<>();
        for (int d = loDec; d <= hiDec; d++) {
            double base = Math.pow(10, d);
            for (int k : keep) {
                double v = base * k;
                if (v < safeMin || v > safeMax) continue;
                out.add(v);
            }
        }
        return toArray(out);
    }

    // =========================================================================
    // Shared line-trace renderer — used by every freq / time-domain view.
    // =========================================================================

    /** Renders {@code n} samples as one polyline through a {@link ColumnBucketPainter}
     *  with the given pen — the single rendering path behind every line trace (FFT
     *  spectrum, FreqResp magnitude / phase / RIAA / compare, scope waveform), which
     *  differ only in colour, line style and the per-sample X / Y.  In-range samples are
     *  column-bucketed; samples whose X falls outside the plot become the painter's edge
     *  anchors so the line reaches the plot edges; off-screen segments are
     *  Liang-Barsky-clipped (a far-off coordinate never wraps past GDI's ±32k limit).
     *  Y is carried as a {@code double} so the Pass-2 trace strokes sub-pixel; {@code yAt}
     *  returns {@code NaN} to drop a sample (a gap). */
    protected final void paintPolyline(GC gc, Rectangle plot, Color color,
                                       int lineStyle, int lineWidth,
                                       int n, IntUnaryOperator xAt, IntToDoubleFunction yAt) {
        if (n < 2) return;
        gc.setForeground(color);
        gc.setLineStyle(lineStyle);
        gc.setLineWidth(lineWidth);
        paintPolylineImpl(gc, plot, n, xAt, yAt);
        if (lineStyle != SWT.LINE_SOLID) gc.setLineStyle(SWT.LINE_SOLID);
    }

    /** Float-width variant of {@link #paintPolyline(GC, Rectangle, Color, int, int,
     *  int, IntUnaryOperator, IntToDoubleFunction)} — strokes through the pooled
     *  {@link #traceLineAttributes} (round cap / join), so the preferences' 0.5-px
     *  width steps render sub-pixel and antialiased joins keep full intensity.
     *  Used by every user-visible data trace; the {@code int} overload remains for
     *  fixed-width overlay / debug strokes. */
    protected final void paintPolyline(GC gc, Rectangle plot, Color color,
                                       int lineStyle, float lineWidth,
                                       int n, IntUnaryOperator xAt, IntToDoubleFunction yAt) {
        if (n < 2) return;
        gc.setForeground(color);
        setTraceLineAttributes(gc, lineWidth, lineStyle);
        paintPolylineImpl(gc, plot, n, xAt, yAt);
        if (lineStyle != SWT.LINE_SOLID) gc.setLineStyle(SWT.LINE_SOLID);
    }

    /** Applies the pooled float-width round-cap stroke to {@code gc}.  Exposed for
     *  views that drive a {@link ColumnBucketPainter} directly (the FFT spectrum)
     *  instead of going through {@code paintPolyline}.  The attributes instance is
     *  pooled — at 65+ paints/s a fresh {@code LineAttributes} per frame measurably
     *  loads the young generation. */
    protected final void setTraceLineAttributes(GC gc, float width, int style) {
        traceLineAttributes.width = Math.max(0.5f, width);
        traceLineAttributes.style = style;
        gc.setLineAttributes(traceLineAttributes);
    }

    /** Pooled stroke for {@link #setTraceLineAttributes} — see its pooling note. */
    private final LineAttributes traceLineAttributes =
            new LineAttributes(1.0f, SWT.CAP_ROUND, SWT.JOIN_ROUND);

    private void paintPolylineImpl(GC gc, Rectangle plot,
                                   int n, IntUnaryOperator xAt, IntToDoubleFunction yAt) {
        ColumnBucketPainter painter = new ColumnBucketPainter(plot);
        int right = plot.x + plot.width;
        for (int i = 0; i < n; i++) {
            double y = yAt.applyAsDouble(i);
            if (Double.isNaN(y)) continue;
            int x = xAt.applyAsInt(i);
            if (x < plot.x)      painter.setLeftAnchor(x, y);
            else if (x > right)  painter.setRightAnchor(x, y);
            else                 painter.add(x, y);
        }
        painter.drawTo(gc);
    }

    /** Renders a long sequence of data points as a polyline + envelope bars at no more
     *  than one drawLine per pixel column.  {@link #add} the points (absolute canvas
     *  pixels), optionally set {@link #setLeftAnchor} / {@link #setRightAnchor} for the
     *  off-edge connection, then {@link #drawTo}.  Sharing the bucketing keeps every
     *  trace's rendering identical. */
    public static final class ColumnBucketPainter {

        private final Rectangle plot;
        private final double[] yMins, yMaxs;
        private final int[] cnts;
        /** Optional anchor points just outside the plot rect.  When set, the polyline
         *  extends from {@code leftAnchor*} into the first column and from the last column
         *  out to {@code rightAnchor*}; the clip in {@link #drawTo} hides the out-of-rect
         *  portion so the trace reaches the canvas edges with one extra sample's data. */
        private int    leftAnchorX  = Integer.MIN_VALUE; private double leftAnchorY  = 0;
        private int    rightAnchorX = Integer.MIN_VALUE; private double rightAnchorY = 0;

        public ColumnBucketPainter(Rectangle plot) {
            this.plot  = plot;
            int w      = Math.max(1, plot.width);
            this.yMins = new double[w];
            this.yMaxs = new double[w];
            this.cnts  = new int[w];
            Arrays.fill(yMins, Double.POSITIVE_INFINITY);
            Arrays.fill(yMaxs, Double.NEGATIVE_INFINITY);
        }

        /** Records one data point — absolute canvas pixels, Y kept as a {@code double} so
         *  the Pass-2 trace can stroke sub-pixel.  Points outside the plot rect are silently
         *  dropped — the caller needn't pre-filter. */
        public void add(int xAbs, double yAbs) {
            int x = xAbs - plot.x;
            // A sample landing exactly on the right edge (x == plot.width == cnts.length)
            // maps to the last column — mirroring x == 0 on the left.  Without this the
            // rightmost in-range sample is dropped and the trace stops one sample short
            // of the right edge while the left edge is reached fine.
            if (x == cnts.length) x = cnts.length - 1;
            if (x < 0 || x >= cnts.length) return;
            if (yAbs < yMins[x]) yMins[x] = yAbs;
            if (yAbs > yMaxs[x]) yMaxs[x] = yAbs;
            cnts[x]++;
        }

        /** Sets the left edge anchor — a point at {@code xAbs < plot.x} the first column
         *  connects back to.  Keeps the rightmost candidate (closest to the visible range). */
        public void setLeftAnchor(int xAbs, double yAbs) {
            if (xAbs > leftAnchorX) { leftAnchorX = xAbs; leftAnchorY = yAbs; }
        }

        /** Sets the right edge anchor — a point at {@code xAbs > plot.x + plot.width} the
         *  last column connects out to.  Keeps the leftmost candidate. */
        public void setRightAnchor(int xAbs, double yAbs) {
            if (rightAnchorX == Integer.MIN_VALUE || xAbs < rightAnchorX) {
                rightAnchorX = xAbs; rightAnchorY = yAbs;
            }
        }

        /** Emits vertical envelope bars (dense "1 bin &lt; 1 px" columns, AA off so 1-px
         *  spikes stay sharp) plus a midpoint polyline through every non-empty column
         *  (AA on, sub-pixel).  The caller sets foreground / line width / line style first. */
        public void drawTo(GC gc) {
            Rectangle prevClip = gc.getClipping();
            gc.setClipping(plot);
            int prevAA = gc.getAntialias();
            int yTop = plot.y, yBot = plot.y + plot.height;
            try {
                // Pass 1: vertical envelope bars for the dense "1 bin < 1 px" columns.
                // AA OFF so 1-px spikes stay sharp; rounded to int because a 1-px-wide bar
                // gains nothing from sub-pixel Y.  Y is clamped to the plot (a vertical bar
                // clips exactly by clamping) so an over-range column never pushes GDI past
                // its ±32k coord limit, which would wrap the bar off-screen.
                gc.setAntialias(SWT.OFF);
                for (int x = 0; x < cnts.length; x++) {
                    if (cnts[x] < 2 || yMins[x] == yMaxs[x]) continue;
                    int lo = (int) Math.round(Math.max(yTop, Math.min(yBot, yMins[x])));
                    int hi = (int) Math.round(Math.max(yTop, Math.min(yBot, yMaxs[x])));
                    if (lo == hi) continue;
                    int px = plot.x + x;
                    gc.drawLine(px, lo, px, hi);
                }
                // Pass 2: midpoint trace as ONE anti-aliased Path with FLOAT (sub-pixel) Y,
                // so the curve lands between pixel rows instead of stair-stepping (which also
                // defeats the AA at the joints).  Each segment is Liang-Barsky-clipped to the
                // plot and only its in-bounds part is appended, so every coordinate stays
                // within GDI's ±32k range while the whole curve strokes in a single call with
                // smooth round joins.  Left / right anchors extend it to the edges.
                gc.setAntialias(SWT.ON);
                Path path = new Path(gc.getDevice());
                try {
                    int prevX = leftAnchorX;
                    double prevY = leftAnchorY;
                    boolean penDown = false;
                    for (int x = 0; x < cnts.length; x++) {
                        if (cnts[x] == 0) continue;
                        double midY = (yMins[x] + yMaxs[x]) / 2.0;
                        int px = plot.x + x;
                        if (prevX != Integer.MIN_VALUE) {
                            penDown = clipSegmentToPath(path, prevX, prevY, px, midY, penDown);
                        }
                        prevX = px;
                        prevY = midY;
                    }
                    if (rightAnchorX != Integer.MIN_VALUE && prevX != Integer.MIN_VALUE) {
                        clipSegmentToPath(path, prevX, prevY, rightAnchorX, rightAnchorY, penDown);
                    }
                    gc.drawPath(path);
                } finally {
                    path.dispose();
                }
            } finally {
                gc.setAntialias(prevAA);
                gc.setClipping(prevClip);
            }
        }

        /** Liang-Barsky-clips segment {@code (x0,y0)-(x1,y1)} to {@code plot} and appends
         *  the in-bounds part to {@code path}: continues the current sub-path when this
         *  segment's clipped start meets the previous segment's clipped end (an unclipped
         *  interior vertex), otherwise begins a new sub-path with {@code moveTo}.  Every
         *  coordinate appended is in-bounds, so the Path never hands GDI a value past its
         *  ±32k limit while the whole curve still strokes in one {@code drawPath} with
         *  smooth joins.  Returns whether the path now ends at this segment's true endpoint
         *  (pen still down — the next segment may continue without a new {@code moveTo}). */
        private boolean clipSegmentToPath(Path path, double x0, double y0, double x1, double y1, boolean penDown) {
            double dx = x1 - x0, dy = y1 - y0;
            double u0 = 0.0, u1 = 1.0;
            double[] p = { -dx, dx, -dy, dy };
            double[] q = { x0 - plot.x, (plot.x + plot.width) - x0,
                           y0 - plot.y, (plot.y + plot.height) - y0 };
            for (int i = 0; i < 4; i++) {
                if (p[i] == 0) {
                    if (q[i] < 0) return false;          // parallel to edge i and outside
                } else {
                    double r = q[i] / p[i];
                    if (p[i] < 0) { if (r > u1) return false; if (r > u0) u0 = r; }
                    else          { if (r < u0) return false; if (r < u1) u1 = r; }
                }
            }
            if (!penDown || u0 > 0.0) {
                path.moveTo((float) (x0 + u0 * dx), (float) (y0 + u0 * dy));
            }
            path.lineTo((float) (x0 + u1 * dx), (float) (y0 + u1 * dy));
            return u1 >= 1.0;
        }
    }
}
