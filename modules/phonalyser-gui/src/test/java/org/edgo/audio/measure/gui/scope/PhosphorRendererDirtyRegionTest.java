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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.Arrays;

import org.edgo.audio.measure.gui.common.AlphaImageScratch;
import org.edgo.audio.measure.gui.common.MeasurementPainter;

import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.LineAttributes;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;

import org.junit.jupiter.api.Test;

/**
 * Dirty-region rasterisation of {@link PhosphorRenderer#render} (item 3): only the trace's content
 * bounding box is repainted per frame, yet the blitted coverage image must be BYTE-IDENTICAL to the
 * old whole-buffer flow.  Driven through a recording {@link MeasurementPainter} stub that captures a
 * copy of the {@code drawAlphaImage} buffer (no SWT device, no GL; a {@code null} tint is fine as
 * {@code render} passes it straight through).  The {@link PhosphorRenderer#fullRepaint} test seam
 * forces the whole-buffer flow so BOTH flows run in-test and are compared directly.
 */
class PhosphorRendererDirtyRegionTest {

    private static final int    WIDTH      = 120;
    private static final int    HEIGHT     = 80;
    private static final int    DISP_COUNT = 480;      // 4 samples/px ⇒ dense (phosphor) regime
    private static final double CENTER_Y   = HEIGHT / 2.0;
    private static final double V_SCALE    = 8.0;      // small ⇒ a thin band, a genuine sub-box
    private static final float  LINE_WIDTH = 2f;

    /** A low-amplitude dense trace: the band occupies only a thin horizontal strip near mid-height,
     *  so the dirty region is a real sub-box of the {@code WIDTH×HEIGHT} buffer rather than the whole
     *  thing (otherwise the region bounding would be trivially exercised). */
    private float[] confinedTrace() {
        float[] d = new float[600];
        for (int i = 0; i < d.length; i++) d[i] = (float) (0.35 * Math.sin(i * 0.21));
        return d;
    }

    private byte[] render(boolean fullRepaint, int frames, float[] data) {
        PhosphorRenderer r = new PhosphorRenderer();
        r.fullRepaint = fullRepaint;
        CapturePainter p = new CapturePainter();
        for (int f = 0; f < frames; f++) {
            r.render(p, data, data.length, 0, 0.0, DISP_COUNT, WIDTH, HEIGHT,
                    CENTER_Y, V_SCALE, 0.0, LINE_WIDTH, null);
        }
        return p.alpha;
    }

    @Test
    void dirtyRegionBlitEqualsFullPassByteForByte() {
        float[] data = confinedTrace();
        byte[] full   = render(true,  1, data);   // whole-buffer clear + full-range passes (reference)
        byte[] dirty1 = render(false, 1, data);   // frame 1: whole clear (dims changed) + REGIONAL passes
        byte[] dirty2 = render(false, 2, data);   // frame 2: INCREMENTAL clear + regional passes
        assertArrayEquals(full, dirty1, "regional passes must equal the full passes");
        assertArrayEquals(full, dirty2, "incremental clear + regional passes must equal the full passes");
    }

    @Test
    void previousRegionIsErasedSoAMovedTraceMatchesAFreshRender() {
        // Two traces in DIFFERENT vertical bands.  Rendering B after A on one renderer must leave the
        // SAME bytes as rendering B alone on a fresh renderer — proving A's dirty region (which does
        // not overlap B's) was fully cleared, with no stale coverage bleeding through.
        float[] a = new float[600];
        float[] b = new float[600];
        for (int i = 0; i < a.length; i++) {
            a[i] = (float) (0.30 * Math.sin(i * 0.21) + 0.9);   // upper band
            b[i] = (float) (0.30 * Math.sin(i * 0.21) - 0.9);   // lower band
        }
        PhosphorRenderer moving = new PhosphorRenderer();
        CapturePainter mp = new CapturePainter();
        moving.render(mp, a, a.length, 0, 0.0, DISP_COUNT, WIDTH, HEIGHT, CENTER_Y, V_SCALE, 0.0, LINE_WIDTH, null);
        moving.render(mp, b, b.length, 0, 0.0, DISP_COUNT, WIDTH, HEIGHT, CENTER_Y, V_SCALE, 0.0, LINE_WIDTH, null);

        byte[] fresh = render(false, 1, b);
        assertArrayEquals(fresh, mp.alpha, "the previous frame's region must be fully erased");
    }

    /** Captures a COPY of the coverage buffer the renderer blits (first {@code imgW*imgH} bytes);
     *  {@code getPixelScale} is 1 (logical resolution).  Every other painter method is an inert
     *  no-op — {@code render} calls only these two. */
    private static final class CapturePainter implements MeasurementPainter {
        byte[] alpha;

        @Override public float getPixelScale() { return 1f; }

        @Override public void drawAlphaImage(byte[] a, int imgW, int imgH, int destX, int destY,
                                             int drawW, int drawH, Color tint, AlphaImageScratch scratch) {
            this.alpha = Arrays.copyOf(a, imgW * imgH);
        }

        // --- inert: never exercised by render() ---------------------------------------
        @Override public Color getForeground() { return null; }
        @Override public void  setForeground(Color color) { }
        @Override public Color getBackground() { return null; }
        @Override public void  setBackground(Color color) { }
        @Override public int   getLineWidth() { return 0; }
        @Override public void  setLineWidth(int width) { }
        @Override public int[] getLineDash() { return null; }
        @Override public void  setLineDash(int[] dash) { }
        @Override public int   getLineStyle() { return 0; }
        @Override public void  setLineStyle(int style) { }
        @Override public LineAttributes getLineAttributes() { return null; }
        @Override public void  setLineAttributes(LineAttributes attributes) { }
        @Override public int   getAntialias() { return 0; }
        @Override public void  setAntialias(int mode) { }
        @Override public void  setTextAntialias(int mode) { }
        @Override public void  setAdvanced(boolean advanced) { }
        @Override public Font  getFont() { return null; }
        @Override public void  setFont(Font font) { }
        @Override public Rectangle getClipping() { return null; }
        @Override public void  setClipping(Rectangle rect) { }
        @Override public void  drawLine(int x1, int y1, int x2, int y2) { }
        @Override public void  drawRectangle(int x, int y, int width, int height) { }
        @Override public void  fillRectangle(int x, int y, int width, int height) { }
        @Override public void  drawRoundRectangle(int x, int y, int w, int h, int aw, int ah) { }
        @Override public void  fillRoundRectangle(int x, int y, int w, int h, int aw, int ah) { }
        @Override public void  fillOval(int x, int y, int width, int height) { }
        @Override public void  fillPolygon(int[] pointArray) { }
        @Override public void  drawPolygon(int[] pointArray) { }
        @Override public void  drawImage(Image image, int x, int y) { }
        @Override public void  drawText(String s, int x, int y, boolean transparent) { }
        @Override public void  beginPath() { }
        @Override public void  moveTo(float x, float y) { }
        @Override public void  lineTo(float x, float y) { }
        @Override public void  strokePath() { }
        @Override public Point textExtent(String s) { return null; }
        @Override public int   fontHeight() { return 0; }
    }
}
