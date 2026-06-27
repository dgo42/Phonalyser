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
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.LineAttributes;
import org.eclipse.swt.graphics.Path;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;

/**
 * {@link MeasurementPainter} backed by an SWT {@code GC} — the CPU rendering path
 * used for on-screen painting, off-screen screenshots ({@code renderToImage}), and
 * printing.  Every call delegates straight to the wrapped {@code GC}, so this is a
 * pixel-identical pass-through: routing the views' paint through this backend
 * changes nothing visible, it only inserts the seam a NanoVG backend slots into.
 *
 * <p>The wrapped {@code GC} is owned by the caller (the paint event, or the
 * {@code GC(Image)} a screenshot opens); this painter never disposes it.  The only
 * resource it owns is the transient {@code Path} built between {@link #beginPath()}
 * and {@link #strokePath()}, which it disposes on stroke.
 */
public final class GcMeasurementPainter implements MeasurementPainter {

    private final GC gc;
    private Path path;          // live only between beginPath() and strokePath()

    public GcMeasurementPainter(GC gc) {
        this.gc = gc;
    }

    // --- Pen / device state --------------------------------------------------

    @Override public Color getForeground()                 { return gc.getForeground(); }
    @Override public void  setForeground(Color color)      { gc.setForeground(color); }
    @Override public Color getBackground()                 { return gc.getBackground(); }
    @Override public void  setBackground(Color color)      { gc.setBackground(color); }

    @Override public int   getLineWidth()                  { return gc.getLineWidth(); }
    @Override public void  setLineWidth(int width)         { gc.setLineWidth(width); }
    @Override public int[] getLineDash()                   { return gc.getLineDash(); }
    @Override public void  setLineDash(int[] dash)         { gc.setLineDash(dash); }
    @Override public int   getLineStyle()                  { return gc.getLineStyle(); }
    @Override public void  setLineStyle(int style)         { gc.setLineStyle(style); }
    @Override public LineAttributes getLineAttributes()    { return gc.getLineAttributes(); }
    @Override public void  setLineAttributes(LineAttributes a) { gc.setLineAttributes(a); }

    @Override public int   getAntialias()                  { return gc.getAntialias(); }
    @Override public void  setAntialias(int mode)          { gc.setAntialias(mode); }
    @Override public void  setTextAntialias(int mode)      { gc.setTextAntialias(mode); }
    @Override public void  setAdvanced(boolean advanced)   { gc.setAdvanced(advanced); }

    @Override public Font  getFont()                       { return gc.getFont(); }
    @Override public void  setFont(Font font)              { gc.setFont(font); }

    @Override public Rectangle getClipping()               { return gc.getClipping(); }
    @Override public void  setClipping(Rectangle rect)     { gc.setClipping(rect); }

    // --- Primitives ----------------------------------------------------------

    @Override public void drawLine(int x1, int y1, int x2, int y2)      { gc.drawLine(x1, y1, x2, y2); }
    @Override public void drawRectangle(int x, int y, int w, int h)     { gc.drawRectangle(x, y, w, h); }
    @Override public void fillRectangle(int x, int y, int w, int h)     { gc.fillRectangle(x, y, w, h); }
    @Override public void drawRoundRectangle(int x, int y, int w, int h, int aw, int ah) { gc.drawRoundRectangle(x, y, w, h, aw, ah); }
    @Override public void fillRoundRectangle(int x, int y, int w, int h, int aw, int ah) { gc.fillRoundRectangle(x, y, w, h, aw, ah); }
    @Override public void fillOval(int x, int y, int w, int h)          { gc.fillOval(x, y, w, h); }
    @Override public void fillPolygon(int[] pointArray)                 { gc.fillPolygon(pointArray); }
    @Override public void drawPolygon(int[] pointArray)                 { gc.drawPolygon(pointArray); }
    @Override public void drawImage(Image image, int x, int y)          { gc.drawImage(image, x, y); }
    @Override public void drawText(String s, int x, int y, boolean t)   { gc.drawText(s, x, y, t); }

    // --- Stroked path --------------------------------------------------------

    @Override public void beginPath()              { path = new Path(gc.getDevice()); }
    @Override public void moveTo(float x, float y) { path.moveTo(x, y); }
    @Override public void lineTo(float x, float y) { path.lineTo(x, y); }
    @Override public void strokePath() {
        gc.drawPath(path);
        path.dispose();
        path = null;
    }

    // --- Measurement ---------------------------------------------------------

    @Override public Point textExtent(String s) { return gc.textExtent(s); }
    @Override public int   fontHeight()         { return gc.getFontMetrics().getHeight(); }
}
