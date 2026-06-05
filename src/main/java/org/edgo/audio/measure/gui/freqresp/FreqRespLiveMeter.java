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

package org.edgo.audio.measure.gui.freqresp;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;

import java.util.Locale;

/**
 * Compact live "input-level over time" chart shown inside the FreqResp
 * busy shell while a sweep is in progress.  Inspired by REW's per-sweep
 * level monitor.
 *
 * <p>Axes:
 * <ul>
 *   <li>Horizontal — elapsed time, {@code 0 .. totalDurationSec}.</li>
 *   <li>Vertical — RMS level in dBFS, {@code 0 dB at the top, −100 dB
 *       at the bottom}, labelled every 20 dB.</li>
 * </ul>
 *
 * <p>Caller flow:
 * <ol>
 *   <li>Construct with the parent composite and the expected total
 *       capture duration in seconds (the analyzer's
 *       {@code leadIn + sweep + 0.5 s tail}).</li>
 *   <li>From the capture progress callback (marshalled to the SWT UI
 *       thread), call {@link #appendSample(double, double)} with
 *       elapsed seconds and the block's linear RMS.</li>
 * </ol>
 *
 * <p>Mathematics:
 * <ul>
 *   <li>{@code dbFs = 20·log10(rmsLin)}; values below {@code −100 dB}
 *       are clamped to the floor.</li>
 *   <li>Polyline drawn between successive (time, dB) samples.</li>
 * </ul>
 */
public final class FreqRespLiveMeter extends Canvas {

    /** Top of the dB axis (digital full-scale). */
    private static final double DB_MAX = 0.0;
    /** Bottom of the dB axis (well below typical noise floor). */
    private static final double DB_MIN = -100.0;
    /** Label every multiple of this many dB.  Picks 0, −20, −40, −60, −80, −100. */
    private static final double DB_LABEL_STEP = 20.0;

    private static final int MARGIN_LEFT   = 36;
    private static final int MARGIN_RIGHT  =  6;
    private static final int MARGIN_TOP    =  4;
    /** Bottom margin sized so the {@code "0"} and {@code "21.8 s"} time
     *  labels (drawn just below the plot rectangle) sit fully inside the
     *  canvas, not clipped at the bottom edge. */
    private static final int MARGIN_BOTTOM = 18;

    /** Exponential-moving-average smoothing factor applied to incoming
     *  RMS values.  Closer to 1 → heavier smoothing; closer to 0 → no
     *  smoothing.  0.85 wipes the low-frequency "teeth" from partial-
     *  cycle blocks (each ~1024-sample block at 50 Hz captures a fraction
     *  of one period and its raw RMS swings wildly) without lagging the
     *  trace enough that the user can't see the sweep's amplitude
     *  envelope. */
    private static final double EMA_ALPHA = 0.95;

    private static final int MAX_POINTS = 4096;

    private final Color background;
    private final Color gridColor;
    private final Color axisColor;
    private final Color textColor;
    private final Color traceColor;
    private final Font  labelFont;

    private final double totalDurationSec;

    // Two parallel arrays grown together — avoids boxing.
    private final float[] timesSec = new float[MAX_POINTS];
    private final float[] levelsDb = new float[MAX_POINTS];
    private int           pointCount;

    /** Smoothed RMS state for {@link #EMA_ALPHA}; seeded on the first
     *  incoming sample so the trace starts at the right value instead
     *  of ramping from 0 over several blocks. */
    private double  emaRmsLin;
    private boolean emaSeeded;

    public FreqRespLiveMeter(Composite parent, double totalDurationSec) {
        super(parent, SWT.NO_BACKGROUND | SWT.DOUBLE_BUFFERED);
        this.totalDurationSec = Math.max(0.001, totalDurationSec);
        Display d = getDisplay();

        background = new Color(d, 0xFF, 0xFF, 0xFF);
        gridColor  = new Color(d, 0xD0, 0xD0, 0xD0);
        axisColor  = new Color(d, 0x60, 0x60, 0x60);
        textColor  = new Color(d, 0x20, 0x20, 0x20);
        traceColor = new Color(d, 0x1B, 0x5E, 0x20);  // dark green — same family as the FreqResp compare trace

        FontData fd = getFont().getFontData()[0];
        labelFont = new Font(d, fd.getName(), Math.max(7, fd.getHeight() - 1), SWT.NORMAL);

        addPaintListener(this::onPaint);
        addDisposeListener(e -> {
            background.dispose();
            gridColor.dispose();
            axisColor.dispose();
            textColor.dispose();
            traceColor.dispose();
            if (labelFont != null && !labelFont.isDisposed()) labelFont.dispose();
        });
    }

    /** Pushes one (time, RMS) sample into the chart.  Must be called on
     *  the SWT UI thread; the caller is responsible for marshalling from
     *  the audio thread via {@link Display#asyncExec}.
     *
     *  <p>The incoming linear RMS is run through an EMA filter first
     *  ({@link #EMA_ALPHA}).  At low frequencies each captured block
     *  contains only a partial cycle so the raw RMS swings wildly
     *  between near-zero and the peak — without smoothing the trace
     *  paints as a comb of vertical teeth.  EMA collapses that into a
     *  steady envelope and is cheap (one multiply-add per block). */
    public void appendSample(double timeSec, double rmsLin) {
        if (isDisposed()) return;
        if (rmsLin < 0.0 || !Double.isFinite(rmsLin)) rmsLin = 0.0;
        if (!emaSeeded) {
            emaRmsLin = rmsLin;
            emaSeeded = true;
        } else {
            emaRmsLin = EMA_ALPHA * emaRmsLin + (1.0 - EMA_ALPHA) * rmsLin;
        }
        double smoothed = emaRmsLin;
        double db = (smoothed > 0.0) ? 20.0 * Math.log10(smoothed) : DB_MIN;
        if (db < DB_MIN) db = DB_MIN;
        if (db > DB_MAX) db = DB_MAX;
        if (pointCount < MAX_POINTS) {
            timesSec[pointCount] = (float) timeSec;
            levelsDb[pointCount] = (float) db;
            pointCount++;
        } else {
            // Compact: drop every other point so the chart still shows
            // history at half-resolution and stays responsive on very
            // long captures.  Doubles the effective time-density limit.
            int w = 0;
            for (int r = 0; r < MAX_POINTS; r += 2) {
                timesSec[w] = timesSec[r];
                levelsDb[w] = levelsDb[r];
                w++;
            }
            pointCount = w;
            timesSec[pointCount] = (float) timeSec;
            levelsDb[pointCount] = (float) db;
            pointCount++;
        }
        redraw();
    }

    /** Resets the trace.  Used when the same meter widget is reused for
     *  a fresh capture. */
    public void clear() {
        pointCount = 0;
        emaRmsLin  = 0.0;
        emaSeeded  = false;
        if (!isDisposed()) redraw();
    }

    private void onPaint(PaintEvent e) {
        GC gc = e.gc;
        Rectangle area = getClientArea();
        gc.setBackground(background);
        gc.fillRectangle(0, 0, area.width, area.height);
        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);
        gc.setFont(labelFont);

        Rectangle plot = new Rectangle(
                MARGIN_LEFT,
                MARGIN_TOP,
                Math.max(1, area.width  - MARGIN_LEFT - MARGIN_RIGHT),
                Math.max(1, area.height - MARGIN_TOP  - MARGIN_BOTTOM));

        drawGrid(gc, plot);
        drawAxes(gc, plot);
        drawTrace(gc, plot);
    }

    private void drawGrid(GC gc, Rectangle plot) {
        gc.setForeground(gridColor);
        gc.setLineWidth(1);
        for (double db = DB_MAX; db >= DB_MIN - 1e-9; db -= DB_LABEL_STEP) {
            int y = dbToY(db, plot);
            gc.drawLine(plot.x, y, plot.x + plot.width, y);
        }
    }

    private void drawAxes(GC gc, Rectangle plot) {
        gc.setForeground(axisColor);
        gc.drawRectangle(plot.x, plot.y, plot.width, plot.height);

        // ── dB axis labels, every DB_LABEL_STEP dB.
        for (double db = DB_MAX; db >= DB_MIN - 1e-9; db -= DB_LABEL_STEP) {
            int y = dbToY(db, plot);
            gc.setForeground(axisColor);
            gc.drawLine(plot.x - 3, y, plot.x, y);
            String label = String.format(Locale.ROOT, "%.0f", db);
            Point ext = gc.textExtent(label);
            gc.setForeground(textColor);
            gc.drawText(label, plot.x - 5 - ext.x, y - ext.y / 2, true);
        }

        // ── Bottom time-axis: just "0" at the left edge and the total
        // duration label at the right edge.  Saves vertical real-estate
        // in this compact widget.
        int axisY = plot.y + plot.height;
        gc.setForeground(textColor);
        String zero = "0";
        gc.drawText(zero, plot.x, axisY + 1, true);
        String full = String.format(Locale.ROOT, "%.1f s", totalDurationSec);
        Point fe = gc.textExtent(full);
        gc.drawText(full, plot.x + plot.width - fe.x, axisY + 1, true);
    }

    private void drawTrace(GC gc, Rectangle plot) {
        if (pointCount < 1) return;
        gc.setForeground(traceColor);
        gc.setLineWidth(2);
        int prevX = -1, prevY = -1;
        for (int i = 0; i < pointCount; i++) {
            int x = timeToX(timesSec[i], plot);
            int y = dbToY(levelsDb[i], plot);
            if (prevX >= 0) gc.drawLine(prevX, prevY, x, y);
            prevX = x;
            prevY = y;
        }
    }

    private int dbToY(double db, Rectangle plot) {
        double frac = (DB_MAX - db) / (DB_MAX - DB_MIN);
        return plot.y + (int) Math.round(frac * plot.height);
    }

    private int timeToX(double t, Rectangle plot) {
        double frac = t / totalDurationSec;
        if (frac < 0) frac = 0;
        if (frac > 1) frac = 1;
        return plot.x + (int) Math.round(frac * plot.width);
    }
}
