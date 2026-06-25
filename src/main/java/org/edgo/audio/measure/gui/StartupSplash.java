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

package org.edgo.audio.measure.gui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.graphics.Resource;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import org.edgo.audio.measure.gui.helpviewer.HelpUrls;
import org.edgo.audio.measure.gui.helpviewer.Versions;
import org.edgo.audio.measure.gui.i18n.I18n;

import java.util.ArrayList;
import java.util.List;

/**
 * Branded launch splash, drawn entirely with the GC (no bitmap asset): a
 * dark gradient backdrop with an audio motif — a glowing composite waveform
 * over an FFT-style spectrum — and the title, version, copyright and project
 * link overlaid.
 *
 * <p>Shown for the brief moment the {@link MainWindow} (menu, panes, audio
 * engines) is being built, then closed once the window is up.  It is a
 * borderless, always-on-top {@link Shell}; {@link #open()} pumps a few event
 * ticks so the OS paints it before the heavy construction blocks the UI
 * thread.
 */
public final class StartupSplash {

    private static final int WIDTH  = 560;
    private static final int HEIGHT = 340;
    private static final int MARGIN = 34;

    /** Footer legal lines — fixed identifiers, not UI prose. */
    private static final String COPYRIGHT = "© 2026 Dimitrij Goldstein";
    private static final String LICENSE   = "GNU Affero GPL v3 — free software, no warranty";

    private final Display display;
    private final List<Resource> resources = new ArrayList<>();

    /** Screen rectangle of the repo URL text, set on each {@link #render}, so
     *  the About presentation can hit-test clicks / hovers against it. */
    private Rectangle urlBounds;

    private Shell shell;
    private Font titleFont;
    private Font taglineFont;
    private Font versionFont;
    private Font smallFont;
    private Color bgTop;
    private Color bgBottom;
    private Color grid;
    private Color wave;
    private Color barTop;
    private Color barBottom;
    private Color textMain;
    private Color textDim;
    private Color accent;
    private Color border;

    public StartupSplash(Display display) {
        this.display = display;
    }

    /** Builds, centres and shows the splash, then pumps a few event-loop
     *  ticks so it is actually painted before the caller's heavy work runs
     *  on this same thread. */
    public void open() {
        shell = new Shell(display, SWT.NO_TRIM | SWT.ON_TOP | SWT.TOOL);
        shell.setLayout(new FillLayout());
        Canvas canvas = new Canvas(shell, SWT.DOUBLE_BUFFERED);
        canvas.addPaintListener(this::paint);

        shell.setSize(WIDTH, HEIGHT);
        Rectangle mon = display.getPrimaryMonitor().getClientArea();
        shell.setLocation(mon.x + (mon.width - WIDTH) / 2, mon.y + (mon.height - HEIGHT) / 2);
        shell.addDisposeListener(e -> disposeResources());
        shell.open();

        for (int i = 0; i < 8; i++) {
            if (!display.readAndDispatch()) break;
        }
    }

    /** Closes the splash (and frees its resources via the dispose listener). */
    public void close() {
        if (shell != null && !shell.isDisposed()) shell.dispose();
    }

    /** Shows the same artwork as the modal About dialog, centred on the
     *  parent: the repo URL is clickable (opens the browser), and a click in
     *  any other free space — or {@code Esc} — dismisses it. */
    public void showAsAbout(Shell parent) {
        // APPLICATION_MODAL only — no ON_TOP, which would force the window
        // above every other application (reads as system-wide modal).
        Shell s = new Shell(parent, SWT.NO_TRIM | SWT.APPLICATION_MODAL);
        s.setLayout(new FillLayout());
        Canvas canvas = new Canvas(s, SWT.DOUBLE_BUFFERED);
        canvas.addPaintListener(this::paint);

        s.setSize(WIDTH, HEIGHT);
        Rectangle pb = parent.getBounds();
        s.setLocation(pb.x + (pb.width - WIDTH) / 2, pb.y + (pb.height - HEIGHT) / 2);
        s.addDisposeListener(e -> disposeResources());

        canvas.addMouseMoveListener(e -> canvas.setCursor(
                overUrl(e.x, e.y) ? display.getSystemCursor(SWT.CURSOR_HAND) : null));
        canvas.addListener(SWT.MouseDown, e -> {
            if (overUrl(e.x, e.y)) Program.launch(HelpUrls.REPO_URL);
            else s.dispose();
        });
        s.addListener(SWT.Traverse, e -> {
            if (e.detail == SWT.TRAVERSE_ESCAPE) s.dispose();
        });
        s.open();
    }

    private boolean overUrl(int x, int y) {
        return urlBounds != null && urlBounds.contains(x, y);
    }

    private void allocate() {
        FontData fd = display.getSystemFont().getFontData()[0];
        String fam = fd.getName();
        titleFont   = font(fam, 30, SWT.BOLD);
        taglineFont = font(fam, 11, SWT.NORMAL);
        versionFont = font(fam, 12, SWT.BOLD);
        smallFont   = font(fam, 8,  SWT.NORMAL);

        bgTop     = color(10, 14, 26);
        bgBottom  = color(18, 40, 62);
        grid      = color(60, 92, 134);
        wave      = color(63, 208, 224);
        barTop    = color(79, 200, 227);
        barBottom = color(227, 200, 79);
        textMain  = color(234, 242, 255);
        textDim   = color(138, 160, 190);
        accent    = color(99, 210, 226);
        border    = color(46, 74, 107);
    }

    private void paint(PaintEvent e) {
        render(e.gc);
    }

    /** Draws the whole splash onto {@code gc} at {@link #WIDTH}×{@link #HEIGHT}.
     *  Lazily allocates its colours/fonts on first use so it can also render
     *  off-screen (e.g. onto an Image) without a live shell. */
    void render(GC gc) {
        if (titleFont == null) allocate();
        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);
        int w = WIDTH;
        int h = HEIGHT;

        // Backdrop gradient.
        gc.setForeground(bgTop);
        gc.setBackground(bgBottom);
        gc.fillGradientRectangle(0, 0, w, h, true);

        drawGrid(gc, w, h);
        drawSpectrum(gc, w, h);
        drawWaveform(gc, w, h);

        // Text overlay.
        gc.setFont(titleFont);
        gc.setForeground(textMain);
        gc.drawText("Phonalyser", MARGIN, 38, SWT.DRAW_TRANSPARENT);

        gc.setFont(taglineFont);
        gc.setForeground(textDim);
        gc.drawText(I18n.t("splash.tagline"), MARGIN + 2, 102, SWT.DRAW_TRANSPARENT);

        gc.setFont(versionFont);
        gc.setForeground(accent);
        String version = "v" + Versions.appVersion();
        Point vs = gc.textExtent(version);
        gc.drawText(version, w - MARGIN - vs.x, 46, SWT.DRAW_TRANSPARENT);

        gc.setFont(smallFont);
        gc.setForeground(textDim);
        gc.drawText(COPYRIGHT, MARGIN, h - 50, SWT.DRAW_TRANSPARENT);
        gc.drawText(LICENSE,   MARGIN, h - 34, SWT.DRAW_TRANSPARENT);
        // Repo URL on the upper footer line (right), clear of the license line.
        String url = HelpUrls.REPO_URL;
        Point us = gc.textExtent(url);
        gc.setForeground(accent);
        int ux = w - MARGIN - us.x;
        int uy = h - 50;
        gc.drawText(url, ux, uy, SWT.DRAW_TRANSPARENT);
        urlBounds = new Rectangle(ux, uy, us.x, us.y);

        gc.setForeground(border);
        gc.drawRectangle(0, 0, w - 1, h - 1);
    }

    /** Faint instrument grid behind the trace. */
    private void drawGrid(GC gc, int w, int h) {
        gc.setForeground(grid);
        gc.setLineWidth(1);
        gc.setAlpha(26);
        for (int x = MARGIN; x < w - MARGIN; x += 26) gc.drawLine(x, 120, x, h - 60);
        for (int y = 120; y < h - 60; y += 26)        gc.drawLine(MARGIN, y, w - MARGIN, y);
        gc.setAlpha(255);
    }

    /** FFT-style magnitude bars: a broadband floor with two resonant peaks,
     *  fully deterministic so the splash looks the same every launch. */
    private void drawSpectrum(GC gc, int w, int h) {
        int left = MARGIN;
        int right = w - MARGIN;
        int baseline = h - 64;
        int span = right - left;
        int bars = 60;
        int gap = 2;
        int bw = Math.max(2, span / bars - gap);
        int maxBar = 90;
        for (int i = 0; i < bars; i++) {
            double f = i / (double) (bars - 1);
            double peak1 = Math.exp(-Math.pow((f - 0.22) / 0.06, 2));
            double peak2 = 0.7 * Math.exp(-Math.pow((f - 0.55) / 0.05, 2));
            double floor = 0.12 + 0.10 * (1 - f);
            double ripple = 0.05 * (0.5 + 0.5 * Math.sin(i * 0.9));
            double mag = Math.min(1.0, floor + peak1 + peak2 + ripple);
            int barH = (int) Math.round(mag * maxBar);
            int x = left + i * (bw + gap);
            gc.setForeground(barTop);
            gc.setBackground(barBottom);
            gc.fillGradientRectangle(x, baseline - barH, bw, barH, true);
        }
        gc.setForeground(border);
        gc.setAlpha(120);
        gc.drawLine(left, baseline, right, baseline);
        gc.setAlpha(255);
    }

    /** Glowing composite waveform (sum of three harmonics) across the panel,
     *  drawn as widening, fading passes for a soft bloom. */
    private void drawWaveform(GC gc, int w, int h) {
        int left = MARGIN;
        int right = w - MARGIN;
        int mid = 152;
        int amp = 34;
        int n = right - left;
        int[] pts = new int[(n + 1) * 2];
        for (int i = 0; i <= n; i++) {
            double t = (i / (double) n) * Math.PI * 2 * 3;
            double v = Math.sin(t) + 0.45 * Math.sin(2 * t + 0.6) + 0.22 * Math.sin(3 * t + 1.2);
            v /= 1.67;
            pts[i * 2] = left + i;
            pts[i * 2 + 1] = (int) Math.round(mid - v * amp);
        }
        gc.setForeground(wave);
        int[] widths = { 7, 4, 2 };
        int[] alphas = { 40, 90, 255 };
        for (int p = 0; p < widths.length; p++) {
            gc.setLineWidth(widths[p]);
            gc.setAlpha(alphas[p]);
            gc.drawPolyline(pts);
        }
        gc.setAlpha(255);
        gc.setLineWidth(1);
    }

    private Font font(String family, int height, int style) {
        Font f = new Font(display, family, height, style);
        resources.add(f);
        return f;
    }

    private Color color(int r, int g, int b) {
        Color c = new Color(display, r, g, b);
        resources.add(c);
        return c;
    }

    private void disposeResources() {
        for (Resource r : resources) {
            if (r != null && !r.isDisposed()) r.dispose();
        }
        resources.clear();
    }
}
