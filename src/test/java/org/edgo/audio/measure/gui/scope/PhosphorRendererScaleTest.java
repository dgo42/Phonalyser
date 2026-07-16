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

import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * Adaptive device-resolution rasterisation of {@link PhosphorRenderer#render}: the
 * coverage image is built at DEVICE resolution ({@code round(logical·pixelScale)}) and
 * blitted into the LOGICAL draw rectangle, so a HiDPI surface gets crisp 1:1 texels.
 * Driven through a recording {@link MeasurementPainter} stub that captures the final
 * {@code drawAlphaImage} image + draw dimensions (no SWT device, no GL — the stub's
 * methods never touch native SWT; a {@code null} tint is fine as {@code render} passes
 * it straight through).
 */
class PhosphorRendererScaleTest {

    private static final int WIDTH_PX   = 100;
    private static final int HEIGHT_PX  = 60;
    private static final int DISP_COUNT = 400;   // 4 samples/px ⇒ dense (phosphor) regime

    private float[] sine(int n) {
        float[] d = new float[n];
        for (int i = 0; i < n; i++) d[i] = (float) Math.sin(i * 0.3);
        return d;
    }

    private void renderInto(RecordingPainter p) {
        float[] data = sine(500);
        new PhosphorRenderer().render(p, data, data.length, 0, 0.0, DISP_COUNT,
                WIDTH_PX, HEIGHT_PX, HEIGHT_PX / 2.0, 20.0, 0.0, 2f, null);
    }

    /** Scale 1: the image is at logical size and drawn 1:1 — image dims equal the draw
     *  dims (byte-identical to rasterising at logical resolution). */
    @Test
    void scale1KeepsImageAtLogicalSizeAndDrawsOneToOne() {
        RecordingPainter p = new RecordingPainter(1.0f);
        renderInto(p);
        assertEquals(1, p.calls, "one blit per render");
        assertEquals(WIDTH_PX,  p.imgW);
        assertEquals(HEIGHT_PX, p.imgH);
        assertEquals(WIDTH_PX,  p.drawW);
        assertEquals(HEIGHT_PX, p.drawH);
        assertEquals(p.drawW, p.imgW, "scale 1 ⇒ image dims == draw dims");
        assertEquals(p.drawH, p.imgH, "scale 1 ⇒ image dims == draw dims");
    }

    /** Scale 1.5 (HiDPI): the image is at DEVICE resolution {@code round(logical·1.5)},
     *  while the LOGICAL draw rectangle is unchanged (device texels land 1:1). */
    @Test
    void hiDpiScaleSizesImageToDeviceButKeepsLogicalDrawRect() {
        RecordingPainter p = new RecordingPainter(1.5f);
        renderInto(p);
        assertEquals(1, p.calls);
        assertEquals(Math.round(WIDTH_PX  * 1.5f), p.imgW);   // 150
        assertEquals(Math.round(HEIGHT_PX * 1.5f), p.imgH);   //  90
        assertEquals(WIDTH_PX,  p.drawW);                     // logical draw rect unchanged
        assertEquals(HEIGHT_PX, p.drawH);
    }

    /** Records the {@code getPixelScale} the renderer reads and the {@code drawAlphaImage}
     *  image / draw dimensions it emits; every other painter method is an inert no-op
     *  ({@code render} calls only these two). */
    private static final class RecordingPainter implements MeasurementPainter {
        private final float scale;
        int imgW, imgH, drawW, drawH, calls;

        RecordingPainter(float scale) { this.scale = scale; }

        @Override public float getPixelScale() { return scale; }

        @Override public void drawAlphaImage(byte[] alpha, int imgW, int imgH, int destX, int destY,
                                             int drawW, int drawH, Color tint, AlphaImageScratch scratch) {
            this.imgW = imgW; this.imgH = imgH; this.drawW = drawW; this.drawH = drawH;
            calls++;
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
