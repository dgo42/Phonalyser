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

import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.LineAttributes;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;

/**
 * The 2-D drawing surface the measurement views (oscilloscope, FFT, frequency
 * response, condensed strip) paint through, so one set of render code can target
 * either an SWT {@code GC} or a GPU canvas (NanoVG) without being duplicated.
 *
 * <p>The two backends are {@link GcMeasurementPainter} (wraps a {@code GC} — used
 * for on-screen CPU rendering, off-screen screenshots, and printing) and
 * {@code NvgMeasurementPainter} (wraps a NanoVG context — used for the embedded
 * GPU scope).  Because every view's {@code paintCanvas} drives the <i>same</i>
 * painter, the live GPU view and the GC-rendered screenshot can never drift.
 *
 * <p>The method set deliberately <b>mirrors the {@code GC} subset the views use</b>
 * — same names, same getter/setter pairs — so the existing save/restore paint
 * code (e.g. {@code int prev = gc.getAntialias(); … ; gc.setAntialias(prev);})
 * converts mechanically by swapping the receiver, and {@code GcMeasurementPainter}
 * stays a pixel-identical pass-through.  Colours and fonts are SWT types because
 * the views already hold them (the palette is SWT {@code Color}s); a NanoVG
 * backend converts them on the way through.
 *
 * <p>The one departure from {@code GC} is the path API ({@link #beginPath()} /
 * {@link #moveTo} / {@link #lineTo} / {@link #strokePath()}): {@code GC} builds a
 * stroked curve through a heap-allocated {@code Path}, while NanoVG builds it
 * statefully — the stateful form fits both, so the GC backend manages the
 * {@code Path} internally.
 */
public interface MeasurementPainter {

    // --- Pen / device state (mirrors GC for mechanical save/restore) ---------

    Color getForeground();
    void  setForeground(Color color);
    Color getBackground();
    void  setBackground(Color color);

    int   getLineWidth();
    void  setLineWidth(int width);
    int[] getLineDash();
    void  setLineDash(int[] dash);
    int   getLineStyle();
    void  setLineStyle(int style);
    LineAttributes getLineAttributes();
    void  setLineAttributes(LineAttributes attributes);

    int   getAntialias();
    void  setAntialias(int mode);
    void  setTextAntialias(int mode);
    void  setAdvanced(boolean advanced);

    Font  getFont();
    void  setFont(Font font);

    Rectangle getClipping();
    void  setClipping(Rectangle rect);

    // --- Primitives ----------------------------------------------------------

    void drawLine(int x1, int y1, int x2, int y2);
    void drawRectangle(int x, int y, int width, int height);
    void fillRectangle(int x, int y, int width, int height);
    void drawRoundRectangle(int x, int y, int width, int height, int arcWidth, int arcHeight);
    void fillRoundRectangle(int x, int y, int width, int height, int arcWidth, int arcHeight);
    void fillOval(int x, int y, int width, int height);
    void fillPolygon(int[] pointArray);
    void drawPolygon(int[] pointArray);
    void drawImage(Image image, int x, int y);

    /** Draws {@code s} at ({@code x}, {@code y}); {@code transparent} leaves the
     *  glyph background unfilled (the GC {@code isTransparent} flag). */
    void drawText(String s, int x, int y, boolean transparent);

    // --- Stroked path (NanoVG-style; GC backend uses an SWT Path internally) --

    void beginPath();
    void moveTo(float x, float y);
    void lineTo(float x, float y);
    void strokePath();

    // --- Measurement ---------------------------------------------------------

    Point textExtent(String s);
    /** Current font's line height — the GC {@code getFontMetrics().getHeight()}. */
    int  fontHeight();
}
