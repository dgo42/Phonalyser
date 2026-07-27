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

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Device;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;

/**
 * Stamps a rendered screenshot with the centred semi-transparent "Phonalyser"
 * brand watermark and, when supplied, a top-right comment caption.  Drawn by
 * each measurement pane onto its offscreen (headless) render output, so the
 * caption can sit under that pane's own header chrome.
 *
 * <p>Both overlays pick light- or dark-on-contrast ink per region by sampling
 * the underlying luminance, so they stay readable on the dark scope and on the
 * white FFT / frequency-response backgrounds alike.
 */
public final class ScreenshotOverlay {

    private static final String WATERMARK_TEXT = "Phonalyser";
    /** Target watermark text width as a fraction of the image width (~1/5). */
    private static final double WATERMARK_WIDTH_FRACTION = 0.2;
    private static final int    WATERMARK_ALPHA = 77;        // 30 % of 255
    /** Right margin (px) of the comment caption. */
    private static final int    COMMENT_MARGIN_RIGHT = 60;
    /** Comment size (pt) when no explicit font is given. */
    private static final int    DEFAULT_COMMENT_PT = 12;
    /** Overlay ink, chosen per region to contrast its background. */
    private static final int    INK_LIGHT = 0xF0F0F0;
    private static final int    INK_DARK  = 0x101010;
    /** Background luminance (0–255) above which a region counts as "light". */
    private static final int    LUMA_THRESHOLD = 140;
    /** Font height (pt) used to measure the watermark before scaling to width. */
    private static final int    MEASURE_PT = 12;

    private final Display display;

    public ScreenshotOverlay(Display display) {
        this.display = display;
    }

    /**
     * Stamps {@code img} in place.
     *
     * @param img          the rendered screenshot to draw onto.
     * @param commentTopY  top y (px) of the comment caption — set per pane so it
     *                     sits under that pane's header.
     * @param comment      caption text; null/blank draws no caption.
     * @param commentFont  caption font; null falls back to the system font at
     *                     {@link #DEFAULT_COMMENT_PT}.
     */
    public void stamp(Image img, int commentTopY, String comment, FontData commentFont) {
        Rectangle b = img.getBounds();
        ImageData bg = img.getImageData();        // sample BEFORE drawing anything
        GC gc = new GC(img);
        try {
            gc.setAdvanced(true);
            gc.setTextAntialias(SWT.ON);
            Device dev      = gc.getDevice();
            String sysFamily = display.getSystemFont().getFontData()[0].getName();

            drawWatermark(gc, dev, bg, b, sysFamily);

            if (comment != null && !comment.isBlank()) {
                FontData fd = commentFont != null
                        ? commentFont
                        : new FontData(sysFamily, DEFAULT_COMMENT_PT, SWT.NORMAL);
                drawComment(gc, dev, bg, b, comment, fd, commentTopY);
            }
        } finally {
            gc.dispose();
        }
    }

    /** Centred, ~1/5-width, 30 %-opaque brand watermark. */
    private void drawWatermark(GC gc, Device dev, ImageData bg, Rectangle b, String family) {
        Font measure = new Font(dev, family, MEASURE_PT, SWT.BOLD);
        int measuredW;
        try {
            gc.setFont(measure);
            measuredW = gc.textExtent(WATERMARK_TEXT, SWT.DRAW_TRANSPARENT).x;
        } finally {
            measure.dispose();
        }
        if (measuredW <= 0) return;
        int size = Math.max(1, (int) Math.round(MEASURE_PT * (b.width * WATERMARK_WIDTH_FRACTION) / measuredW));
        Font wf = new Font(dev, family, size, SWT.BOLD);
        try {
            gc.setFont(wf);
            Point ext = gc.textExtent(WATERMARK_TEXT, SWT.DRAW_TRANSPARENT);
            int x = (b.width - ext.x) / 2;
            int y = (b.height - ext.y) / 2;
            Color ink = inkFor(dev, bg, x, y, ext.x, ext.y);
            try {
                gc.setForeground(ink);
                gc.setAlpha(WATERMARK_ALPHA);
                gc.drawText(WATERMARK_TEXT, x, y, SWT.DRAW_TRANSPARENT);
                gc.setAlpha(255);
            } finally {
                ink.dispose();
            }
        } finally {
            wf.dispose();
        }
    }

    /** Right-aligned comment caption at {@code topY}. */
    private void drawComment(GC gc, Device dev, ImageData bg, Rectangle b,
                             String comment, FontData fd, int topY) {
        Font cf = new Font(dev, fd);
        try {
            gc.setFont(cf);
            Point ext = gc.textExtent(comment, SWT.DRAW_TRANSPARENT);
            int x = b.width - COMMENT_MARGIN_RIGHT - ext.x;
            Color ink = inkFor(dev, bg, x, topY, ext.x, ext.y);
            try {
                gc.setForeground(ink);
                gc.drawText(comment, x, topY, SWT.DRAW_TRANSPARENT);
            } finally {
                ink.dispose();
            }
        } finally {
            cf.dispose();
        }
    }

    /** Light or dark ink to contrast the mean luminance under the text rect. */
    private Color inkFor(Device dev, ImageData bg, int x0, int y0, int w, int h) {
        long sum = 0;
        int  n   = 0;
        int  stepX = Math.max(1, w / 16);
        int  stepY = Math.max(1, h / 8);
        for (int y = Math.max(0, y0); y < Math.min(bg.height, y0 + h); y += stepY) {
            for (int x = Math.max(0, x0); x < Math.min(bg.width, x0 + w); x += stepX) {
                RGB c = bg.palette.getRGB(bg.getPixel(x, y));
                sum += (c.red * 299 + c.green * 587 + c.blue * 114) / 1000;   // Rec.601 luma
                n++;
            }
        }
        int rgb = (n > 0 && sum / n > LUMA_THRESHOLD) ? INK_DARK : INK_LIGHT;
        return new Color(dev, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }
}
