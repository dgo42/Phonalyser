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

package org.edgo.audio.measure.gui.scope.gl;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.opengl.GLCanvas;
import org.eclipse.swt.opengl.GLData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.edgo.audio.measure.gui.common.NvgMeasurementPainter;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;

import lombok.Setter;

import static org.lwjgl.nanovg.NanoVG.*;
import static org.lwjgl.nanovg.NanoVGGL2.*;

/**
 * {@link GlScopeSurface} for Windows / Linux: an SWT {@link GLCanvas} bound to an
 * OpenGL context via LWJGL, drawing through NanoVG (GL2 backend).  This is the
 * proven embeddable path (it hits ~2500 fps on Windows); macOS uses the GLFW-child
 * surface instead because SWT's {@code GLCanvas} crashes there.
 *
 * <p>{@code SWT.NO_BACKGROUND} stops SWT erasing the canvas to white between GL
 * swaps (which otherwise flickers).  The GL context and NanoVG are created lazily
 * on the first {@link #render()} (when the context is first current).
 * Rendering is direct on the UI thread — no paint listener — so it is driven by the
 * scope's realtime-frame loop; the double-buffered front buffer holds the last
 * frame between renders, so exposes don't need a repaint.
 */
public final class SwtGlCanvasSurface implements GlScopeSurface {

    private final GLCanvas canvas;
    private long    vg;
    private NvgMeasurementPainter painter;
    private boolean glReady;

    @Setter private GlScopeRenderer renderer;

    public SwtGlCanvasSurface(Composite parent) {
        GLData data = new GLData();
        data.doubleBuffer = true;
        data.stencilSize  = 8;                 // NanoVG anti-aliased stroke path needs stencil
        canvas = new GLCanvas(parent, SWT.NO_BACKGROUND, data);
        // Also render on SWT paint events (initial show, resize, expose) so the
        // scope is drawn when stopped too — the realtime loop only fires while live.
        canvas.addPaintListener(e -> render());
    }

    @Override
    public Control control() {
        return canvas;
    }

    @Override
    public void render() {
        if (canvas.isDisposed() || renderer == null) return;
        canvas.setCurrent();
        if (!glReady) {
            GL.createCapabilities();
            vg = nvgCreate(NVG_ANTIALIAS | NVG_STENCIL_STROKES);
            glReady = true;
        }
        if (vg == 0L) return;

        Rectangle area = canvas.getClientArea();
        int w = area.width;
        int h = area.height;
        if (w <= 0 || h <= 0) return;

        GL11.glViewport(0, 0, w, h);
        GL11.glClearColor(0f, 0f, 0f, 1f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);

        if (painter == null) painter = new NvgMeasurementPainter(vg, canvas.getDisplay());
        nvgBeginFrame(vg, w, h, 1f);
        painter.reset(w, h, 1f);
        renderer.render(painter, w, h);
        nvgEndFrame(vg);

        canvas.swapBuffers();
    }

    @Override
    public void dispose() {
        if (painter != null) {
            painter.dispose();
            painter = null;
        }
        if (!canvas.isDisposed() && vg != 0L) {
            canvas.setCurrent();
            nvgDelete(vg);
            vg = 0L;
        }
    }
}
