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

package org.edgo.audio.measure.gui.scope;

import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.gui.bind.Bindings;
import org.edgo.audio.measure.gui.common.AbstractMeasurementView;
import org.edgo.audio.measure.gui.common.Fonts;
import org.edgo.audio.measure.gui.common.Icon;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.widgets.ToolButton;
import org.edgo.audio.measure.gui.widgets.Toolbar;
import org.edgo.audio.measure.preferences.Preferences;

/**
 * The amplitude-histogram plot: how often the signal sat at each voltage, drawn
 * as horizontal bars with voltage up the left axis and occupancy along the
 * bottom, both auto-ranging as the counts accumulate.  A sine shows the bathtub
 * of its two turning points, noise a Gaussian bell, a clipped signal a spike
 * where the rail is.
 *
 * <p>A view in its own right, not part of the oscilloscope's: it has its own
 * palette, its own header buttons and its own axes, and shares nothing with the
 * trace but the capture it reads.  {@code ScopeController} owns the tool window
 * this lives in; everything inside the window is owned here.
 *
 * <p>The distribution itself lives in the measurement worker, which bins on the
 * capture thread and publishes snapshots — this only ever reads a snapshot, so
 * it never walks an array being mutated underneath it.
 */
public final class HistogramView extends AbstractMeasurementView {

    /** Plot margins: the left gutter holds the voltage tick labels, the top line
     *  the "V" caption, the bottom line the occupancy ticks. */
    // Wide enough for the longest label the voltage axis can produce — sign, four
    // digits, decimal point and an SI prefix ("-12.5 µ").  At 52 px the leading
    // digit and the minus sign were being clipped off.
    private static final int MARGIN_LEFT   = 68;
    private static final int MARGIN_RIGHT  = 8;
    private static final int MARGIN_TOP    = 16;
    private static final int MARGIN_BOTTOM = 22;
    /** Target major-tick counts on the two auto-ranged axes. */
    private static final int X_TICKS = 5;
    private static final int Y_TICKS = 8;
    /** Gap between the header button row and the plot. */
    private static final int HEADER_GAP = 4;
    /** Radio group of the L/R pair.  A name of its own: the grouping resolves
     *  among siblings of ONE parent, so borrowing the scope header's "channel"
     *  would read as coupling to a toolbar in another window entirely. */
    private static final String CHAN_GROUP = "histChannel";

    /** Owner of the accumulators.  Injected: this view reads a distribution and
     *  can clear it, and knows nothing else about the measurement path. */
    private final ScopeMeasurementWorker worker;
    private final Toolbar    headerBar;
    private final ToolButton leftBtn;
    private final ToolButton rightBtn;

    /** Mirrors {@code oscHistogramChannel} so the pick survives closing the window
     *  and restarting the app.  Held as a field as well because paint reads it on
     *  every frame. */
    private Channel channel = Preferences.instance().getOscHistogramChannel();
    private Font    monoFont;

    public HistogramView(Composite parent, ScopeMeasurementWorker worker) {
        // Dark chrome, matching the scope the distribution comes from.  OVERLAY_BG
        // is black because drawGrid overpaints the axis-unit caption with it, and
        // the shared light-grey default would be a bright patch on this plot.
        super(parent, SWT.DOUBLE_BUFFERED, Map.of(
                ColorRole.BACKGROUND, 0x000000,
                ColorRole.GRID,       0x3C3C3C,
                ColorRole.AXIS,       0x3C3C3C,
                ColorRole.TEXT,       0xF0F0F0,
                ColorRole.OVERLAY_BG, 0x000000));
        this.worker = worker;
        // The trace / mid colours are prefs-driven, so they must exist before the
        // buttons that paint themselves with them.
        syncChannelPalette(null, null);

        Font chanFont = Fonts.instance().channel(getDisplay());
        headerBar = new Toolbar(this, BTN_W, BTN_H);
        headerBar.setLocation(0, 0);
        leftBtn = headerBar.chanButton("L",
                color(ColorRole.LEFT_CHANNEL_MID), color(ColorRole.LEFT_CHANNEL_MID),
                color(ColorRole.LEFT_TRACE), chanFont,
                I18n.t("scope.stats.left.tooltip"), channel == Channel.L, CHAN_GROUP);
        rightBtn = headerBar.chanButton("R",
                color(ColorRole.RIGHT_CHANNEL_MID), color(ColorRole.RIGHT_CHANNEL_MID),
                color(ColorRole.RIGHT_TRACE), chanFont,
                I18n.t("scope.stats.right.tooltip"), channel == Channel.R, CHAN_GROUP);
        headerBar.spacer(2);
        ToolButton resetBtn = headerBar.pushButton(Icon.ROTATE_LEFT_RED, Icon.ROTATE_LEFT_DARK,
                color(ColorRole.TEXT), I18n.t("scope.histogram.reset.tooltip"));
        headerBar.setSize(headerBar.computeSize(SWT.DEFAULT, SWT.DEFAULT));

        leftBtn.addListener(SWT.Selection,  e -> selectChannel(Channel.L, leftBtn));
        rightBtn.addListener(SWT.Selection, e -> selectChannel(Channel.R, rightBtn));
        // Resets ONLY the distribution — the measurement table's statistics are a
        // different question and keep their own reset.
        resetBtn.addListener(SWT.Selection, e -> { worker.resetHistograms(); redraw(); });

        Preferences prefs = Preferences.instance();
        Bindings.onChange(this, prefs.oscLeftChannelColorProperty(),    rgb -> recolor());
        Bindings.onChange(this, prefs.oscRightChannelColorProperty(),   rgb -> recolor());
        Bindings.onChange(this, prefs.oscHistogramChannelProperty(),    ch  -> followChannelPref(ch));
        Bindings.onChange(this, prefs.oscLeftChannelEnabledProperty(),  en  -> syncChannelButtons());
        Bindings.onChange(this, prefs.oscRightChannelEnabledProperty(), en  -> syncChannelButtons());
        syncChannelButtons();

        addPaintListener(this::onPaint);
        addDisposeListener(e -> disposePalette());
    }

    private void recolor() {
        syncChannelPalette(leftBtn, rightBtn);
        redraw();
    }

    /** Radio pick.  Clicking one of the pair fires Selection on BOTH — the button
     *  being switched ON and the one being switched OFF — so only the button that
     *  ended up toggled may change the displayed channel.  Without this guard the
     *  handlers race and the last one to run always wins. */
    private void selectChannel(Channel ch, ToolButton btn) {
        if (!btn.isToggled() || channel == ch) return;
        channel = ch;
        Preferences.instance().setOscHistogramChannel(ch);
        redraw();
    }

    /** Follows the preference when something other than this window's own buttons
     *  changes it — a preset load, or a second histogram window on a rebuilt pane. */
    private void followChannelPref(Channel ch) {
        if (ch == null || ch == channel) return;
        channel = ch;
        syncChannelButtons();
    }

    /** Greys and blocks the button of a channel switched off in the scope, and
     *  moves the selection off it when it was the one being shown. */
    private void syncChannelButtons() {
        Preferences prefs = Preferences.instance();
        boolean left  = prefs.isOscLeftChannelEnabled();
        boolean right = prefs.isOscRightChannelEnabled();
        leftBtn.setEnabled(left);
        rightBtn.setEnabled(right);
        if (channel == Channel.L && !left && right) {
            channel = Channel.R;
        } else if (channel == Channel.R && !right && left) {
            channel = Channel.L;
        }
        // An auto-flip is a pick too — persist it, so re-opening does not put the
        // window back on the channel that is switched off.
        Preferences.instance().setOscHistogramChannel(channel);
        leftBtn.setToggled(channel == Channel.L);
        rightBtn.setToggled(channel == Channel.R);
        redraw();
    }

    private void onPaint(PaintEvent e) {
        GC gc = e.gc;
        Rectangle area = getClientArea();
        gc.setBackground(color(ColorRole.BACKGROUND));
        gc.fillRectangle(area);
        if (monoFont == null) monoFont = Fonts.instance().normal(getDisplay());

        int top = headerBar.getSize().y + HEADER_GAP;
        Rectangle plot = new Rectangle(
                area.x + MARGIN_LEFT,
                area.y + top + MARGIN_TOP,
                Math.max(1, area.width  - MARGIN_LEFT - MARGIN_RIGHT),
                Math.max(1, area.height - top - MARGIN_TOP - MARGIN_BOTTOM));

        // The accumulator is unit-agnostic (normalised); the channel's peak volts
        // turns its bin edges into the voltage on the axis, so a recalibration
        // relabels the plot without disturbing a single count.
        double peakV = Preferences.instance().getAdcPeakVolts(channel);
        AmplitudeHistogram h = worker.getHistogram(channel);
        int lo = (h == null) ? -1 : h.firstOccupied();
        if (h == null || h.getTotal() == 0 || lo < 0) {
            // Nothing binned yet: an empty frame labelled with the converter's own
            // range, and no division by a zero maximum count.
            drawHistogramGrid(gc, plot, -peakV, peakV, 1);
            return;
        }
        int hi = h.lastOccupied();
        // Window the display SYMMETRICALLY about the distribution's own mean, and
        // read the axis relative to it, so the middle line is always 0 V.  The
        // samples were counted exactly as captured, DC offset included; centring on
        // the mean here is what keeps that offset from sliding the picture off the
        // plot — it moves what the axis is measured FROM, not what was counted.
        // An even bar count puts the centre on a bar boundary, not through a bar.
        int centre = (int) Math.round(h.meanBin());
        // Never reach past an end of the accumulator, or the window would stop being
        // symmetric and the mean would drift off the middle line.
        int maxReach = Math.min(centre, h.getMicroBins() - centre);
        int reach = Math.max(1, Math.min(Math.max(centre - lo, hi + 1 - centre), maxReach));
        int from  = centre - reach;
        int to    = centre + reach;
        // Everything on the axis is an offset from the mean, so the centre reads 0 V.
        double zeroRef = h.binValue(centre);
        // The accumulator holds MICRO_PER_BAR micro-bins for every bar, so there is
        // normally far more resolution beneath a bar than it needs.  Clamping to the
        // windowed span is a contract guard for the sparse case (a handful of samples
        // just after a re-range) and keeps aggregate() legal whatever the preference
        // file happens to carry.
        int barCount = Math.min(Preferences.instance().getOscHistogramBins(), to - from);
        barCount = Math.max(2, barCount - (barCount & 1));
        int[] bars = h.aggregate(from, to, barCount);
        int maxCount = 0;
        for (int c : bars) if (c > maxCount) maxCount = c;
        // Crop the tails and re-aggregate: out where the count is so low the bar
        // draws as a single pixel, the rows carry no readable information and only
        // squeeze the part of the distribution that does.  One such bar is kept at
        // each end so the tail still reads as a tail rather than a cut edge.
        int reachKeep = tailReach(bars, maxCount, plot.width);
        int binsPerBar = (to - from) / barCount;
        if (reachKeep > 0 && binsPerBar > 0 && 2 * reachKeep < barCount) {
            from = centre - reachKeep * binsPerBar;
            to   = centre + reachKeep * binsPerBar;
            bars = h.aggregate(from, to, barCount);
            maxCount = 0;
            for (int c : bars) if (c > maxCount) maxCount = c;
        }
        drawHistogramGrid(gc, plot, (h.binLowerEdge(from) - zeroRef) * peakV,
                                    (h.binLowerEdge(to)   - zeroRef) * peakV,
                          Math.max(1, maxCount));
        if (maxCount == 0) return;
        // Bar 0 holds the LOWEST voltage, so it goes at the BOTTOM (screen y grows down).
        gc.setBackground(color(channel == Channel.L ? ColorRole.LEFT_TRACE : ColorRole.RIGHT_TRACE));
        for (int i = 0; i < bars.length; i++) {
            if (bars[i] == 0) continue;
            int yTop = plot.y + (int) Math.round((double) (bars.length - 1 - i) * plot.height / bars.length);
            int yBot = plot.y + (int) Math.round((double) (bars.length - i)     * plot.height / bars.length);
            int barW = (int) Math.round((double) bars[i] / maxCount * plot.width);
            gc.fillRectangle(plot.x + 1, yTop, Math.max(1, barW - 1), Math.max(1, yBot - yTop - 1));
        }
    }

    /**
     * How far out from the centre line the plot needs to reach, in bars, for the
     * distribution to stay readable: the furthest bar that draws wider than a
     * single pixel, plus one so the tail is not cut off flush.  Symmetric, because
     * 0&nbsp;V stays on the centre line.
     *
     * @return {@code 0} when there is nothing worth cropping
     */
    private int tailReach(int[] bars, int maxCount, int plotWidth) {
        if (maxCount <= 0 || plotWidth <= 0) return 0;
        int half  = bars.length / 2;
        int reach = 0;
        for (int i = 0; i < bars.length; i++) {
            // Bar width in px is count / maxCount · plotWidth, so "wider than one
            // pixel" is count · plotWidth > maxCount — no rounding needed.
            if ((long) bars[i] * plotWidth <= maxCount) continue;
            int d = (i >= half) ? i - half + 1 : half - i;
            if (d > reach) reach = d;
        }
        return (reach == 0) ? 0 : Math.min(half, reach + 1);
    }

    /** The two axes: occupancy along the bottom, voltage up the left, each
     *  nice-linear over its auto-ranged span. */
    private void drawHistogramGrid(GC gc, Rectangle plot, double vLo, double vHi, int maxCount) {
        AxisSpec x = AxisSpec.linearNice(0, maxCount, X_TICKS, 0)
                .withFormat(LabelFormat.COUNT);
        AxisSpec y = AxisSpec.linearNice(vLo, vHi, Y_TICKS, 0)
                .withFormat(LabelFormat.VOLTS_SI).withUnit("V");
        drawGrid(gc, plot, x, y, null, color(ColorRole.GRID), color(ColorRole.AXIS),
                 color(ColorRole.TEXT), monoFont, MAJOR_TICK_LEN, MINOR_TICK_LEN, null);
    }

    // ─── Rectangular zoom: not offered ──────────────────────────────────────
    // The base only calls these after installRectZoom, which this view never
    // does — a distribution has no time or frequency axis to pan into, and
    // both of its axes already auto-range onto the data.

    @Override
    protected ZoomState captureZoomState() {
        return null;
    }

    @Override
    protected boolean applyZoomState(ZoomState state) {
        return false;
    }

    @Override
    protected ZoomState zoomStateForRect(Rectangle selection) {
        return null;
    }
}
