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

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.LineAttributes;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.lwjgl.nanovg.NVGColor;
import org.lwjgl.nanovg.NVGPaint;
import org.lwjgl.system.MemoryUtil;

import lombok.Getter;
import lombok.Setter;

import static org.lwjgl.nanovg.NanoVG.*;

/**
 * {@link MeasurementPainter} backed by a <b>NanoVG</b> context — the GPU rendering
 * path for the embedded scope.  It translates the GC-mirroring API into NanoVG
 * immediate-mode calls inside an open {@code nvgBeginFrame}/{@code nvgEndFrame}
 * pair (opened and closed by the GL surface, not here).
 *
 * <p>Because NanoVG is immediate-mode and stateless across calls (no getters),
 * this painter <b>tracks the pen state itself</b> — foreground / background,
 * width, cap / join, antialias, dash, font, clip — and applies it on each
 * primitive, so the views' save/restore paint code (which reads state back) works
 * unchanged.  SWT {@link Color}s are converted to NanoVG colours on the way
 * through; fills use the background colour and strokes the foreground, matching
 * {@code GC} semantics.  Dashed lines (no NanoVG primitive) and the {@code GC}
 * path object are emulated.
 *
 * <p><b>Text</b> is rendered through the app's actual SWT {@link Font} (whatever
 * the user configured in Preferences): {@link #textExtent}/{@link #fontHeight} use
 * a real {@code GC} so layout matches the CPU path exactly, and {@link #drawText}
 * rasterises the string with that {@code GC}, tints it to the foreground colour,
 * and uploads it as a NanoVG image — cached (LRU) and keyed by string + font +
 * colour, so the throttled readouts are a cache hit almost every frame.
 *
 * <p>Created per surface and reused across frames; the surface calls
 * {@link #reset(int, int)} after {@code nvgBeginFrame} to clear pen state and
 * record the frame size (the default clip), and {@link #dispose()} on teardown.
 */
public final class NvgMeasurementPainter implements MeasurementPainter {

    private static final int MAX_TEXT_CACHE = 512;

    private final long vg;
    private final Display display;

    // Reusable colour / paint structs (filled per call; never two live at once).
    private final NVGColor strokeCol = NVGColor.create();
    private final NVGColor fillCol   = NVGColor.create();
    private final NVGPaint textPaint = NVGPaint.create();

    /** Rasterised, tinted string textures, keyed by string + font + colour.  LRU:
     *  the eldest texture is deleted from the NanoVG context when evicted. */
    private final Map<String, CachedText> textCache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, CachedText> eldest) {
            if (size() <= MAX_TEXT_CACHE) return false;
            if (eldest.getValue().image() != 0) nvgDeleteImage(vg, eldest.getValue().image());
            return true;
        }
    };
    /** GL textures for drawn images (toolbar icons), keyed by Image identity —
     *  uploaded once and kept for the context's life (icons are shared, stable
     *  objects).  NanoVG batches draws until endFrame, so the texture must outlive
     *  the frame — deleting it right after the draw call renders nothing. */
    private final Map<Image, Integer> imageCache = new HashMap<>();
    private GC   measureGc;       // lazily created GC for textExtent / rasterising
    private Font measureGcFont;   // font currently set on measureGc

    // --- Tracked pen state ---------------------------------------------------
    @Getter @Setter private Color foreground;
    @Getter @Setter private Color background;
    @Getter private int   antialias = SWT.ON;
    @Getter private int[] lineDash;                         // null ⇒ solid
    @Getter @Setter private int lineStyle = SWT.LINE_SOLID;
    private final LineAttributes lineAttributes =
            new LineAttributes(1f, SWT.CAP_FLAT, SWT.JOIN_MITER);
    @Getter @Setter private Font font;
    private final Rectangle clip = new Rectangle(0, 0, 0, 0);
    /** Device pixels per point (2 on Retina / HiDPI); text rasterises at this zoom. */
    private float pixelScale = 1f;

    public NvgMeasurementPainter(long vg, Display display) {
        this.vg = vg;
        this.display = display;
    }

    /** Clears pen state and records the frame size (the full-canvas default clip)
     *  plus the device pixel scale (1 on a normal display, 2 on Retina / HiDPI) so
     *  text rasterises at native resolution.  Called by the surface right after
     *  {@code nvgBeginFrame}. */
    public void reset(int width, int height, float pixelScale) {
        this.pixelScale = pixelScale;
        antialias = SWT.ON;
        lineDash = null;
        lineStyle = SWT.LINE_SOLID;
        lineAttributes.width = 1f;
        lineAttributes.cap = SWT.CAP_FLAT;
        lineAttributes.join = SWT.JOIN_MITER;
        clip.x = 0; clip.y = 0; clip.width = width; clip.height = height;
        nvgResetScissor(vg);
        nvgShapeAntiAlias(vg, true);
    }

    /** Frees the rasterising GC (the cached NanoVG images go with the context's
     *  {@code nvgDelete}). */
    public void dispose() {
        if (measureGc != null && !measureGc.isDisposed()) measureGc.dispose();
        measureGc = null;
    }

    // --- Pen / device state (non-trivial; simple ones are Lombok above) -------

    @Override public int   getLineWidth()          { return (int) lineAttributes.width; }
    @Override public void  setLineWidth(int width) { lineAttributes.width = width; }
    @Override public void  setLineDash(int[] dash) { lineDash = (dash != null && dash.length > 0) ? dash : null; }

    @Override public LineAttributes getLineAttributes() {
        return new LineAttributes(lineAttributes.width, lineAttributes.cap, lineAttributes.join,
                lineAttributes.style, lineAttributes.dash, lineAttributes.dashOffset, lineAttributes.miterLimit);
    }
    @Override public void setLineAttributes(LineAttributes a) {
        lineAttributes.width = a.width;
        lineAttributes.cap   = a.cap;
        lineAttributes.join  = a.join;
    }

    @Override public void setAntialias(int mode)   { antialias = mode; nvgShapeAntiAlias(vg, mode != SWT.OFF); }
    @Override public void setTextAntialias(int m)  { /* SWT-rasterised text is already antialiased */ }
    @Override public void setAdvanced(boolean a)   { /* NanoVG is always "advanced" */ }

    @Override public Rectangle getClipping() { return new Rectangle(clip.x, clip.y, clip.width, clip.height); }
    @Override public void setClipping(Rectangle r) {
        if (r == null) {
            clip.x = 0; clip.y = 0; clip.width = Integer.MAX_VALUE; clip.height = Integer.MAX_VALUE;
            nvgResetScissor(vg);
        } else {
            clip.x = r.x; clip.y = r.y; clip.width = r.width; clip.height = r.height;
            nvgScissor(vg, r.x, r.y, r.width, r.height);
        }
    }

    // --- Primitives ----------------------------------------------------------

    @Override public void drawLine(int x1, int y1, int x2, int y2) {
        if (lineDash != null) { dashedLine(x1, y1, x2, y2); return; }
        nvgBeginPath(vg);
        nvgMoveTo(vg, x1 + 0.5f, y1 + 0.5f);
        nvgLineTo(vg, x2 + 0.5f, y2 + 0.5f);
        stroke();
    }

    @Override public void drawRectangle(int x, int y, int w, int h) {
        nvgBeginPath(vg);
        nvgRect(vg, x + 0.5f, y + 0.5f, w, h);
        stroke();
    }

    @Override public void fillRectangle(int x, int y, int w, int h) {
        nvgBeginPath(vg);
        nvgRect(vg, x, y, w, h);
        fill();
    }

    @Override public void drawRoundRectangle(int x, int y, int w, int h, int aw, int ah) {
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x + 0.5f, y + 0.5f, w, h, Math.max(aw, ah) / 2f);
        stroke();
    }

    @Override public void fillRoundRectangle(int x, int y, int w, int h, int aw, int ah) {
        nvgBeginPath(vg);
        nvgRoundedRect(vg, x, y, w, h, Math.max(aw, ah) / 2f);
        fill();
    }

    @Override public void fillOval(int x, int y, int w, int h) {
        nvgBeginPath(vg);
        nvgEllipse(vg, x + w / 2f, y + h / 2f, w / 2f, h / 2f);
        fill();
    }

    @Override public void fillPolygon(int[] p) {
        if (p.length < 4) return;
        nvgBeginPath(vg);
        nvgMoveTo(vg, p[0], p[1]);
        for (int i = 2; i + 1 < p.length; i += 2) nvgLineTo(vg, p[i], p[i + 1]);
        nvgClosePath(vg);
        fill();
    }

    @Override public void drawPolygon(int[] p) {
        if (p.length < 4) return;
        nvgBeginPath(vg);
        nvgMoveTo(vg, p[0] + 0.5f, p[1] + 0.5f);
        for (int i = 2; i + 1 < p.length; i += 2) nvgLineTo(vg, p[i] + 0.5f, p[i + 1] + 0.5f);
        nvgClosePath(vg);
        stroke();
    }

    @Override public void drawImage(Image image, int x, int y) {
        if (display == null || image == null) return;
        Integer tex = imageCache.get(image);
        if (tex == null) {
            tex = uploadImage(image);
            imageCache.put(image, tex);
        }
        if (tex == 0) return;
        Rectangle b = image.getBounds();
        nvgImagePattern(vg, x, y, b.width, b.height, 0f, tex, 1f, textPaint);
        nvgBeginPath(vg);
        nvgRect(vg, x, y, b.width, b.height);
        nvgFillPaint(vg, textPaint);
        nvgFill(vg);
    }

    /** Uploads an SWT image as a NanoVG RGBA texture, honouring an alpha channel or a
     *  transparent-pixel index so icon glyphs keep their transparency. */
    private int uploadImage(Image image) {
        ImageData d = image.getImageData();
        int w = d.width;
        int h = d.height;
        ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                int pix = d.getPixel(xx, yy);
                RGB c = d.palette.getRGB(pix);
                int a;
                if (d.alphaData != null)            a = d.getAlpha(xx, yy);
                else if (d.transparentPixel != -1)  a = (pix == d.transparentPixel) ? 0 : 255;
                else                                a = 255;
                buf.put((byte) c.red).put((byte) c.green).put((byte) c.blue).put((byte) a);
            }
        }
        buf.flip();
        int tex = nvgCreateImageRGBA(vg, w, h, 0, buf);
        MemoryUtil.memFree(buf);
        return tex;
    }

    @Override public void drawText(String s, int x, int y, boolean transparent) {
        if (s == null || s.isEmpty() || display == null) return;
        CachedText t = textCache.computeIfAbsent(textKey(s), k -> rasterise(s));
        if (t.image() == 0) return;
        nvgImagePattern(vg, x, y, t.w(), t.h(), 0f, t.image(), 1f, textPaint);
        nvgBeginPath(vg);
        nvgRect(vg, x, y, t.w(), t.h());
        nvgFillPaint(vg, textPaint);
        nvgFill(vg);
    }

    // --- Stroked path --------------------------------------------------------

    @Override public void beginPath()              { nvgBeginPath(vg); }
    @Override public void moveTo(float x, float y) { nvgMoveTo(vg, x, y); }
    @Override public void lineTo(float x, float y) { nvgLineTo(vg, x, y); }
    @Override public void strokePath()             { stroke(); }

    // --- Measurement (real SWT GC ⇒ exact parity with the CPU path) ----------

    @Override public Point textExtent(String s) { return measureGc().textExtent(s); }
    @Override public int   fontHeight()         { return measureGc().getFontMetrics().getHeight(); }

    // --- Internals -----------------------------------------------------------

    private void stroke() {
        nvgStrokeColor(vg, color(foreground, strokeCol));
        nvgStrokeWidth(vg, Math.max(0.1f, lineAttributes.width));
        nvgLineCap(vg, mapCap(lineAttributes.cap));
        nvgLineJoin(vg, mapJoin(lineAttributes.join));
        nvgStroke(vg);
    }

    private void fill() {
        nvgFillColor(vg, color(background, fillCol));
        nvgFill(vg);
    }

    /** Lazily creates the off-screen {@code GC} used for text measurement and
     *  rasterisation, keeping the current {@link #font} set on it. */
    private GC measureGc() {
        if (measureGc == null || measureGc.isDisposed()) {
            measureGc = new GC(display);
            measureGcFont = null;
        }
        if (font != null && font != measureGcFont) {
            measureGc.setFont(font);
            measureGcFont = font;
        }
        return measureGc;
    }

    private String textKey(String s) {
        int rgb = (foreground != null)
                ? (foreground.getRed() << 16) | (foreground.getGreen() << 8) | foreground.getBlue() : 0;
        return s + ' ' + System.identityHashCode(font) + ' ' + rgb + ' ' + Math.round(pixelScale * 100f);
    }

    /** Renders {@code s} with the SWT font as white-on-black, then builds an RGBA
     *  texture tinted to the foreground colour with the glyph coverage as alpha —
     *  antialiased, transparent, exactly the SWT font the CPU path uses. */
    private CachedText rasterise(String s) {
        GC gc = measureGc();
        Point ext = gc.textExtent(s);
        int w = Math.max(1, ext.x);
        int h = Math.max(1, ext.y);

        Image img = new Image(display, w, h);
        GC ig = new GC(img);
        ig.setBackground(display.getSystemColor(SWT.COLOR_BLACK));
        ig.fillRectangle(0, 0, w, h);
        ig.setForeground(display.getSystemColor(SWT.COLOR_WHITE));
        if (font != null) ig.setFont(font);
        ig.setTextAntialias(SWT.ON);
        ig.drawText(s, 0, 0, true);
        // Read the glyph bitmap at the DEVICE zoom (200 on Retina): the no-arg
        // getImageData() returns the downscaled 100% copy, so the texture would be
        // 1× and NanoVG would upscale it to the HiDPI framebuffer → soft / "smashed".
        // The texture is built at this native pixel resolution while the DRAW size
        // stays the point extent (w/h), so NanoVG maps it 1:1 onto device pixels.
        ImageData data = img.getImageData(Math.max(100, Math.round(pixelScale * 100f)));
        ig.dispose();
        img.dispose();

        int tw = data.width;
        int th = data.height;
        int fr = (foreground != null) ? foreground.getRed()   : 0;
        int fgn = (foreground != null) ? foreground.getGreen() : 0;
        int fb = (foreground != null) ? foreground.getBlue()  : 0;
        ByteBuffer buf = MemoryUtil.memAlloc(tw * th * 4);
        for (int yy = 0; yy < th; yy++) {
            for (int xx = 0; xx < tw; xx++) {
                RGB c = data.palette.getRGB(data.getPixel(xx, yy));
                int coverage = (c.red + c.green + c.blue) / 3;       // white glyph ⇒ 255
                buf.put((byte) fr).put((byte) fgn).put((byte) fb).put((byte) coverage);
            }
        }
        buf.flip();
        int image = nvgCreateImageRGBA(vg, tw, th, 0, buf);
        MemoryUtil.memFree(buf);
        return new CachedText(image, w, h);
    }

    /** Strokes a dashed line by walking the {@link #lineDash} on/off pattern —
     *  NanoVG has no native dashing.  Matches the GC behaviour the scope's slider,
     *  trigger and full-scale lines rely on. */
    private void dashedLine(int x1, int y1, int x2, int y2) {
        double dx = x2 - x1, dy = y2 - y1;
        double len = Math.hypot(dx, dy);
        if (len < 1e-6) return;
        double ux = dx / len, uy = dy / len;
        nvgBeginPath(vg);
        double pos = 0;
        int di = 0;
        boolean on = true;
        while (pos < len) {
            double seg = lineDash[di % lineDash.length];
            double end = Math.min(pos + seg, len);
            if (on) {
                nvgMoveTo(vg, (float) (x1 + ux * pos) + 0.5f, (float) (y1 + uy * pos) + 0.5f);
                nvgLineTo(vg, (float) (x1 + ux * end) + 0.5f, (float) (y1 + uy * end) + 0.5f);
            }
            pos = end;
            di++;
            on = !on;
        }
        stroke();
    }

    private NVGColor color(Color c, NVGColor out) {
        if (c == null) return nvgRGBA((byte) 0, (byte) 0, (byte) 0, (byte) 255, out);
        return nvgRGBA((byte) c.getRed(), (byte) c.getGreen(), (byte) c.getBlue(), (byte) c.getAlpha(), out);
    }

    private int mapCap(int swtCap) {
        switch (swtCap) {
            case SWT.CAP_ROUND:  return NVG_ROUND;
            case SWT.CAP_SQUARE: return NVG_SQUARE;
            default:             return NVG_BUTT;
        }
    }

    private int mapJoin(int swtJoin) {
        switch (swtJoin) {
            case SWT.JOIN_ROUND: return NVG_ROUND;
            case SWT.JOIN_BEVEL: return NVG_BEVEL;
            default:             return NVG_MITER;
        }
    }

    /** A rasterised string texture: the NanoVG image handle and its pixel size. */
    private record CachedText(int image, int w, int h) { }
}
