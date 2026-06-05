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

package org.edgo.audio.measure.gui.generator;

import lombok.extern.log4j.Log4j2;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.LineAttributes;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Path;
import org.eclipse.swt.graphics.Transform;
import org.eclipse.swt.widgets.Display;
import org.edgo.audio.measure.enums.GenSignalForm;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * 24×24 pictogram for each {@link GenSignalForm}, rendered with SWT primitives
 * into a transparent-background image at first request and cached for the
 * life of the {@link Display}.  Drawing in code (rather than shipping nine
 * SVG files) keeps the icon set easy to tweak and avoids per-icon resource
 * loading.
 *
 * <p>Caller doesn't dispose the returned images — they're owned by the
 * cache and live until {@link #disposeAll(Display)} is called (typically
 * from the owning Shell's dispose listener).
 */
@Log4j2
public final class SignalFormIcon {

    /** Standard pictogram side length. */
    public static final int SIZE = 24;

    /** Extra top padding for icons shown inside dropdown table items —
     *  prevents the glyph from visually overlapping the row's top border. */
    private static final int DROPDOWN_TOP_PAD = 2;

    private static volatile SignalFormIcon instance;

    private final Map<Display, Map<GenSignalForm, Image>> caches         = new HashMap<>();
    private final Map<Display, Map<GenSignalForm, Image>> dropdownCaches = new HashMap<>();

    private SignalFormIcon() {}

    public static SignalFormIcon instance() {
        SignalFormIcon local = instance;
        if (local == null) {
            synchronized (SignalFormIcon.class) {
                local = instance;
                if (local == null) {
                    local = new SignalFormIcon();
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * Returns the cached pictogram for {@code form} on {@code display},
     * rendering it on first request.  All icons are 24×24, line-art style,
     * with the foreground colour set to {@link SWT#COLOR_WIDGET_FOREGROUND}.
     */
    public synchronized Image get(Display display, GenSignalForm form) {
        Map<GenSignalForm, Image> cache = caches.computeIfAbsent(display,
                d -> new EnumMap<>(GenSignalForm.class));
        return cache.computeIfAbsent(form, f -> render(display, f, 0));
    }

    /**
     * Like {@link #get} but with a couple of pixels of transparent padding
     * at the top, so the glyph clears the dropdown row's top border.
     */
    public synchronized Image getForDropdown(Display display, GenSignalForm form) {
        Map<GenSignalForm, Image> cache = dropdownCaches.computeIfAbsent(display,
                d -> new EnumMap<>(GenSignalForm.class));
        return cache.computeIfAbsent(form, f -> render(display, f, DROPDOWN_TOP_PAD));
    }

    /** Disposes all cached icons for {@code display}; safe to call multiple times. */
    public synchronized void disposeAll(Display display) {
        Map<GenSignalForm, Image> cache = caches.remove(display);
        if (cache != null) {
            for (Image img : cache.values()) {
                if (img != null && !img.isDisposed()) img.dispose();
            }
        }
        Map<GenSignalForm, Image> dropCache = dropdownCaches.remove(display);
        if (dropCache != null) {
            for (Image img : dropCache.values()) {
                if (img != null && !img.isDisposed()) img.dispose();
            }
        }
    }

    private Image render(Display display, GenSignalForm form, int topPad) {
        // 32-bit ARGB image started fully transparent — only the strokes
        // we paint pick up alpha=255, untouched pixels stay alpha=0.  This
        // is what makes the dropdown rows read on any background colour
        // (dark theme, selection highlight, etc.) without a visible white
        // square around each glyph.
        int h = SIZE + topPad;
        PaletteData palette = new PaletteData(0x00FF0000, 0x0000FF00, 0x000000FF);
        ImageData transparentData = new ImageData(SIZE, h, 32, palette);
        transparentData.alphaData = new byte[SIZE * h];
        Image img = new Image(display, transparentData);
        GC gc = new GC(img);
        Transform tr = null;
        try {
            gc.setAntialias(SWT.ON);

            if (topPad > 0) {
                tr = new Transform(display);
                tr.translate(0, topPad);
                gc.setTransform(tr);
            }

            Color fg = display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND);
            gc.setForeground(fg);
            gc.setLineAttributes(new LineAttributes(1.5f, SWT.CAP_ROUND, SWT.JOIN_ROUND));
            switch (form) {
                case SINE:              drawSine(gc, false);            break;
                case SINE_COMPENSATED:  drawSine(gc, true);             break;
                case DUAL_TONE:         drawDualTone(gc);               break;
                case TRIANGLE:          drawTriangle(gc);               break;
                case RECTANGLE:         drawRectangle(gc);              break;
                case WHITE_NOISE:       drawNoise(gc, 31, 1);           break;
                case PINK_NOISE:        drawNoise(gc, 17, 2);           break;
                case PINK_NOISE_LINEAR: drawNoise(gc, 17, 3);           break;
                case LINEAR_SWEEP:      drawSweep(gc, false);           break;
                case LOG_SWEEP:         drawSweep(gc, true);            break;
                default:                drawSine(gc, false);            break;
            }
        } finally {
            if (tr != null) {
                gc.setTransform(null);
                tr.dispose();
            }
            gc.dispose();
        }
        return img;
    }

    // -------------------------------------------------------------------------
    // Individual pictograms — each fills a 24×24 cell with ~3 px insets.
    // -------------------------------------------------------------------------

    /** Pictogram for {@link GenSignalForm#DUAL_TONE} — the
     *  amplitude-modulated beat pattern that two close frequencies
     *  produce, matching the time-domain shape the user sees on the
     *  scope when running dual tone.  Drawn as the sum of two sines
     *  at the slightly different rates {@code 5·t} and {@code 6·t};
     *  the slow {@code (6−5)·t/2} envelope is what gives the
     *  characteristic "fading in and out" visual. */
    private void drawDualTone(GC gc) {
        Path p = new Path(gc.getDevice());
        try {
            int steps = 80;
            // Two sines at slightly different frequencies that beat at
            // a rate slow enough to be visible inside the 20-px-wide
            // icon body.  Sum is divided by 2 so the peak fits inside
            // the ±8-px vertical budget.
            for (int i = 0; i <= steps; i++) {
                double t = (double) i / steps;
                float x = 2 + (float) (t * 20);
                double s1 = Math.sin(t * 5.0 * Math.PI);
                double s2 = Math.sin(t * 6.0 * Math.PI);
                float y  = (float) (12 - (s1 + s2) * 4.0);
                if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
            }
            gc.drawPath(p);
        } finally {
            p.dispose();
        }
    }

    private void drawSine(GC gc, boolean compensated) {
        Path p = new Path(gc.getDevice());
        try {
            int steps = 40;
            for (int i = 0; i <= steps; i++) {
                double t = (double) i / steps;
                float x = 2 + (float) (t * 20);
                float y = (float) (12 - Math.sin(t * 2 * Math.PI) * 8);
                if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
            }
            gc.drawPath(p);
        } finally {
            p.dispose();
        }
        if (compensated) {
            // Small "★" hint that the sine has been corrected.
            gc.setLineAttributes(new LineAttributes(1f));
            gc.drawLine(17, 4, 21, 4);
            gc.drawLine(19, 2, 19, 6);
        }
    }

    private void drawRectangle(GC gc) {
        // Two square-wave cycles, 50 % duty.  Vertical edges + horizontal
        // tops + horizontal bottoms.
        gc.drawLine(2,  4,   8,  4);
        gc.drawLine(8,  4,   8, 20);
        gc.drawLine(8, 20,  14, 20);
        gc.drawLine(14, 20, 14,  4);
        gc.drawLine(14, 4,  20,  4);
        gc.drawLine(20, 4,  20, 20);
        gc.drawLine(20, 20, 22, 20);
    }

    private void drawTriangle(GC gc) {
        // Two triangle waves across the cell.
        int[] xs = { 2, 7, 12, 17, 22 };
        int[] ys = {20, 4, 20,  4, 20 };
        for (int i = 1; i < xs.length; i++) {
            gc.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i]);
        }
    }

    private void drawNoise(GC gc, int seed, int variant) {
        // Random-looking scatter, deterministic per variant so each noise
        // form has a distinct fingerprint.
        Random rng = new Random(seed);
        int prevY = 12;
        for (int x = 2; x <= 22; x++) {
            int y = 12 + rng.nextInt(13) - 6;
            // Variant 2 (pink): smooth slightly between adjacent points.
            // Variant 3 (pink linear): smoother still.
            if (variant >= 2) y = (y + prevY) / 2;
            if (variant >= 3) y = (y + prevY) / 2;
            gc.drawLine(x - 1, prevY, x, y);
            prevY = y;
        }
    }

    private void drawSweep(GC gc, boolean logarithmic) {
        // Frequency rises across the cell — period shrinks left→right.
        Path p = new Path(gc.getDevice());
        try {
            int steps = 80;
            for (int i = 0; i <= steps; i++) {
                double t = (double) i / steps;
                // Linear: phase = ∫ f(t) dt grows as t² (linear ramp).
                // Log:    phase grows as exp-ish — faster ramp at the right.
                double phase = logarithmic
                        ? (Math.exp(t * 3.0) - 1.0) * 1.5
                        : (t * t) * 8.0;
                float x = 2 + (float) (t * 20);
                float y = (float) (12 - Math.sin(phase * 2 * Math.PI) * 7);
                if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
            }
            gc.drawPath(p);
        } finally {
            p.dispose();
        }
    }
}
