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
import org.lwjgl.opengl.GLCapabilities;

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
 * on the first render (when the context is first current).  Display persistence
 * ("digital phosphor") is delegated to the shared {@link ScopePhosphor}; here logical
 * and physical pixels coincide (ratio 1).
 */
public final class SwtGlCanvasSurface implements GlScopeSurface {

    private final GLCanvas canvas;
    private long    vg;
    private NvgMeasurementPainter painter;
    private boolean glReady;
    private boolean fboSupported;
    private ScopePhosphor phosphor;

    @Setter private GlScopeRenderer renderer;

    public SwtGlCanvasSurface(Composite parent) {
        GLData data = new GLData();
        data.doubleBuffer = true;
        data.stencilSize  = 8;                 // NanoVG anti-aliased stroke path needs stencil
        canvas = new GLCanvas(parent, SWT.NO_BACKGROUND, data);
        // Expose / resize: only RE-COMPOSITE the frozen phosphor (no decay / accumulate) so a
        // stopped scope's afterglow doesn't fade every time the window is uncovered or resized.
        canvas.addPaintListener(e -> renderFrame(ScopePhosphor.Kind.COMPOSITE));
        // Free the GL context + framebuffers when the canvas goes away (pane rebuild / close).
        canvas.addDisposeListener(e -> dispose());
    }

    @Override
    public Control control() {
        return canvas;
    }

    @Override
    public void render() {
        renderFrame(ScopePhosphor.Kind.REALTIME);
    }

    @Override
    public void renderInteractive() {
        renderFrame(ScopePhosphor.Kind.RESET);
    }

    private void renderFrame(ScopePhosphor.Kind kind) {
        if (canvas.isDisposed() || renderer == null) return;
        canvas.setCurrent();
        if (!glReady) {
            GLCapabilities caps = GL.createCapabilities();
            fboSupported = caps.OpenGL30;      // raw-GL30 phosphor FBOs need framebuffer objects
            vg = nvgCreate(NVG_ANTIALIAS | NVG_STENCIL_STROKES);
            glReady = true;
        }
        if (vg == 0L) return;

        Rectangle area = canvas.getClientArea();
        int w = area.width;
        int h = area.height;
        if (w <= 0 || h <= 0) return;
        if (painter == null) painter = new NvgMeasurementPainter(vg, canvas.getDisplay());
        if (phosphor == null) {
            phosphor = new ScopePhosphor(vg, painter, (v, t, iw, ih) ->
                    nvglCreateImageFromHandle(v, t, iw, ih,
                            NVG_IMAGE_FLIPY | NVG_IMAGE_PREMULTIPLIED | NVG_IMAGE_NODELETE));
        }

        // Win/Linux: logical and physical pixels coincide, ratio 1.
        GlFrameSize size = new GlFrameSize(w, h, w, h, 1f);
        if (!fboSupported || !phosphor.render(renderer, kind, size)) {
            renderWhole(w, h);                 // persistence off / unsupported: direct full render
        }
        canvas.swapBuffers();
    }

    /** Off path: a single-pass full render straight to the screen (no phosphor buffer). */
    private void renderWhole(int w, int h) {
        GL11.glViewport(0, 0, w, h);
        GL11.glClearColor(0f, 0f, 0f, 1f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        nvgBeginFrame(vg, w, h, 1f);
        painter.reset(w, h, 1f);
        renderer.renderGl(painter, w, h, GlScopeRenderer.Phase.ALL);
        nvgEndFrame(vg);
    }

    @Override
    public void dispose() {
        if (painter != null) {
            painter.dispose();
            painter = null;
        }
        if (vg != 0L) {
            // GL deletes need the context current; if the canvas is already gone the context
            // is too and the driver has freed the GL objects — nvgDelete still frees CPU state.
            if (!canvas.isDisposed()) {
                canvas.setCurrent();
                if (phosphor != null) phosphor.release();
            }
            nvgDelete(vg);
            vg = 0L;
        }
    }
}
