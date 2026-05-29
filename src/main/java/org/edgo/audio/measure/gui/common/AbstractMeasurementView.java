package org.edgo.audio.measure.gui.common;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.edgo.audio.measure.enums.FftMagnitudeUnit;

/**
 * Shared base for the project's measurement canvases — oscilloscope, FFT,
 * frequency response, and the condensed multi-view.
 *
 * <p>The base intentionally stays out of paint: each subclass owns its own
 * colour palette, fonts, axis math, and button rendering.  What lives here
 * is the boilerplate that was being copy-pasted across every view:
 *
 * <ul>
 *   <li>A {@link Hotspot} registry — each view registers its clickable
 *       header buttons (rect + click action + tooltip key) once at
 *       construction.  The rect is consulted by reference every mouse
 *       event, so subclasses can mutate it in place from their paint
 *       code (set width/height to zero to hide a conditionally-visible
 *       button).</li>
 *   <li>{@link #hotspotAt(int, int)} — the lookup the subclass's
 *       mouse listeners call to resolve hover state and click dispatch.
 *       Always returns the first visible match in registration order.</li>
 * </ul>
 *
 * <p>This is deliberately minimal: no abstract methods, no mouse
 * listeners installed by the base.  Subclasses keep their existing
 * {@code onMouseMove} / {@code onMouseDown} handlers and just consult
 * {@link #hotspotAt} from inside them.  That avoids fighting the scope's
 * slider machinery and the FFT's distortion-table cursor logic, which
 * both layer their own per-pixel decisions on top of the simple
 * button-or-not test the base performs.
 */
public abstract class AbstractMeasurementView extends Canvas {

    private final List<Hotspot> hotspots = new ArrayList<>();

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

    protected AbstractMeasurementView(Composite parent, int style) {
        this(parent, style, null);
    }

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
     *  the outline colour at the eight ±1-px neighbours then the
     *  original foreground on top; restores the foreground before
     *  returning so the caller's GC state is unchanged. */
    protected final void drawOutlinedText(GC gc, String s, int x, int y) {
        Color fg = gc.getForeground();
        gc.setForeground(color(ColorRole.BACKGROUND));
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx == 0 && dy == 0) continue;
                gc.drawText(s, x + dx, y + dy, true);
            }
        }
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

    /**
     * Registers a clickable hot-spot.  The owning view typically calls
     * this once per button at construction, passing a {@link Rectangle}
     * field it also mutates from its paint code.  Sharing the rectangle
     * by reference means the hotspot stays in sync with the painted
     * button position automatically — no second registration step
     * needed when the layout changes.
     *
     * <p>To hide a hotspot (e.g. a conditionally-visible toggle), set
     * its bounds {@code width} or {@code height} to zero.  The
     * {@link #hotspotAt} lookup skips zero-sized rects.
     *
     * @param bounds      hit-test rectangle — kept by reference; mutate
     *                    in place to relocate / hide
     * @param onClick     fired by {@link #hotspotAt} consumers when the
     *                    user left-clicks inside {@code bounds}
     * @param tooltipKey  i18n key for the hover tooltip, or {@code null}
     *                    for no tooltip
     */
    protected final void registerHotspot(Rectangle bounds, Runnable onClick, String tooltipKey) {
        hotspots.add(new Hotspot(bounds, onClick, tooltipKey));
    }

    /** Overload for hot-spots with no tooltip. */
    protected final void registerHotspot(Rectangle bounds, Runnable onClick) {
        registerHotspot(bounds, onClick, null);
    }

    /**
     * Returns the hotspot covering {@code (x, y)} or {@code null} when
     * no visible hotspot matches.  Visibility is determined by the
     * bounds rectangle's size — zero-width or zero-height rects are
     * treated as hidden.
     *
     * <p>Iteration order is registration order; the first match wins.
     */
    protected final Hotspot hotspotAt(int x, int y) {
        for (Hotspot h : hotspots) {
            Rectangle b = h.bounds;
            if (b != null && b.width > 0 && b.height > 0 && b.contains(x, y)) {
                return h;
            }
        }
        return null;
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

    /** Convenience: returns the system "hand" cursor when a hotspot is
     *  under {@code (x, y)}, the system "arrow" cursor otherwise.
     *  Subclasses use this from {@code onMouseMove} to keep the hover
     *  cursor consistent across all three views.  Returns the bare
     *  cursor ID (SWT constant) so the subclass can layer its own
     *  decisions (e.g. resize cursors over sliders) on top. */
    protected final int hoverCursorId(int x, int y) {
        return (hotspotAt(x, y) != null) ? SWT.CURSOR_HAND : SWT.CURSOR_ARROW;
    }

    /** Immutable view of a registered hot-spot.  The {@code bounds}
     *  field is mutated by the owning view, so callers must treat the
     *  rectangle as read-only on every query. */
    public static final class Hotspot {
        public final Rectangle bounds;
        public final Runnable  onClick;
        public final String    tooltipKey;

        Hotspot(Rectangle bounds, Runnable onClick, String tooltipKey) {
            this.bounds     = bounds;
            this.onClick    = onClick;
            this.tooltipKey = tooltipKey;
        }
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
            // X axis ticks on the bottom edge, pointing down/outward.
            int xBottom = plot.y + plot.height;
            if (majorEdgeMarkPx > 0) {
                for (double v : xMajors) {
                    int px = valueToX(v, x, plot);
                    gc.drawLine(px, xBottom, px, xBottom + majorEdgeMarkPx);
                }
            }
            if (minorEdgeMarkPx > 0) {
                for (double v : xMinors) {
                    int px = valueToX(v, x, plot);
                    gc.drawLine(px, xBottom, px, xBottom + minorEdgeMarkPx);
                }
            }
            // Left Y axis ticks on the left edge, pointing outward (left).
            if (majorEdgeMarkPx > 0) {
                for (double v : yMajors) {
                    int py = valueToY(v, yLeft, plot);
                    gc.drawLine(plot.x - majorEdgeMarkPx, py, plot.x, py);
                }
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
                    for (double v : yrMajors) {
                        int py = valueToY(v, yRight, plot);
                        gc.drawLine(xRight, py, xRight + majorEdgeMarkPx, py);
                    }
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
                double[] xLabelPositions =
                        (x.scale == Scale.LOG) ? adaptiveLogLabels(x.min, x.max) : xMajors;
                for (double v : xLabelPositions) {
                    String s = applyLabelFormat(x.labelFormat, v);
                    int sw   = gc.textExtent(s).x;
                    int px   = valueToX(v, x, plot) - sw / 2;
                    gc.drawText(s, px, plot.y + plot.height + majorEdgeMarkPx + 2,
                                true);
                }
            }
            // Left Y tick labels (to the left of the plot).
            if (yLeft.labelFormat != LabelFormat.NONE) {
                double[] yLabelPositions =
                        (yLeft.scale == Scale.LOG) ? adaptiveLogLabels(yLeft.min, yLeft.max) : yMajors;
                for (double v : yLabelPositions) {
                    String s = applyLabelFormat(yLeft.labelFormat, v);
                    int    sw = gc.textExtent(s).x;
                    int    py = valueToY(v, yLeft, plot)
                              - gc.getFontMetrics().getHeight() / 2;
                    gc.drawText(s, plot.x - majorEdgeMarkPx - sw - 4, py, true);
                }
            }
            // Right Y tick labels (to the right of the plot).
            if (yRight != null && yRight.labelFormat != LabelFormat.NONE) {
                double[] yrLabelPositions =
                        (yRight.scale == Scale.LOG) ? adaptiveLogLabels(yRight.min, yRight.max) : yrMajors;
                for (double v : yrLabelPositions) {
                    String s = applyLabelFormat(yRight.labelFormat, v);
                    int    py = valueToY(v, yRight, plot)
                              - gc.getFontMetrics().getHeight() / 2;
                    gc.drawText(s, plot.x + plot.width + majorEdgeMarkPx + 4, py, true);
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
    private static double[] majorTicks(AxisSpec axis) {
        switch (axis.scale) {
            case LINEAR:      return linearTicks(axis.min, axis.max,
                                                 Math.max(1, axis.divisions));
            case LINEAR_NICE: return niceLinearMajors(axis.min, axis.max,
                                                      Math.max(2, axis.targetCount));
            case LOG:         return logMajorTicks(axis.min, axis.max);
            default:          return new double[0];
        }
    }

    /** Returns the minor tick positions for {@code axis}: empty for
     *  LINEAR, the caller-supplied step for LINEAR_NICE, 2..9 × 10ⁿ
     *  for LOG. */
    private static double[] minorTicks(AxisSpec axis) {
        switch (axis.scale) {
            case LINEAR:      return new double[0];
            case LINEAR_NICE: return (axis.minorStep > 0)
                                     ? niceLinearMinors(axis.min, axis.max, axis.minorStep)
                                     : new double[0];
            case LOG:         return logMinorTicks(axis.min, axis.max);
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
    private static double[] logMajorTicks(double min, double max) {
        double safeMin = Math.max(1e-12, min);
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
    private static double[] logMinorTicks(double min, double max) {
        double safeMin = Math.max(1e-12, min);
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
            double safeMin = Math.max(1e-12, axis.min);
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
     *  and {@code max}.  Step is 1 / 2 / 2.5 / 5 × 10ⁿ.  Port of
     *  {@code FftAxisTicks.niceLinear}. */
    private static double[] niceLinearMajors(double min, double max, int targetCount) {
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
    // wiring a lambda per call site.  FftFormat / FreqRespFormat now
    // hold only domain-specific helpers; none of them duplicate the
    // methods below.
    // =========================================================================

    /** Compact frequency label: "1.0 Hz" / "1.50 kHz". */
    protected String formatFrequency(double f) {
        if (!Double.isFinite(f) || f <= 0) return "—";
        if (f >= 1000) return String.format("%.2f kHz", f / 1000);
        return String.format("%.1f Hz", f);
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
    protected String formatMagnitudeWithUnit(double v, FftMagnitudeUnit unit) {
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

    /** Adaptively-thinned label set for LOG axes: as more decades become
     *  visible we drop more of the sub-decade positions so labels never
     *  overlap.  Grid lines and minor ticks at all 1..9 × 10ⁿ positions
     *  are still drawn — only the set of values that get a printed
     *  label thins.  Port of the {@code labelsOnly} branch of
     *  {@code FftAxisTicks.logFreqAll}. */
    private static double[] adaptiveLogLabels(double min, double max) {
        double safeMin = Math.max(1e-12, min);
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
}
