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

import java.util.Locale;

import org.eclipse.swt.SWT;
import org.eclipse.swt.opengl.GLCanvas;
import org.eclipse.swt.opengl.GLData;
import org.eclipse.swt.widgets.Composite;
import org.lwjgl.nanovg.NanoVGGL3;
import org.lwjgl.opengl.GL;

import lombok.extern.log4j.Log4j2;

import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_STENCIL_BITS;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.nanovg.NanoVGGL2.NVG_ANTIALIAS;
import static org.lwjgl.nanovg.NanoVGGL2.NVG_STENCIL_STROKES;
import static org.lwjgl.nanovg.NanoVGGL2.nvgCreate;
import static org.lwjgl.nanovg.NanoVGGL2.nvgDelete;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Detects, once, whether the embedded GPU scope can actually run on this machine —
 * i.e. whether an SWT {@link GLCanvas} can obtain an OpenGL context and create a
 * NanoVG instance on it.  The result gates the "use GPU acceleration" preference:
 * the checkbox is disabled and the GL surface is never built when this returns
 * false, so a machine without working GL drivers silently stays on the CPU path.
 *
 * <p>The probe is a real round-trip — create a throwaway GL context, bind LWJGL,
 * create a NanoVG context, then tear it all down — guarded so any GL/native failure
 * just reports "unavailable".  Two mechanisms by OS: Windows / Linux use an SWT
 * {@link GLCanvas} (NanoVGGL2); macOS, where SWT's {@code GLCanvas} crashes on
 * Cocoa, uses a hidden GLFW window (NanoVGGL3) — the same path {@code
 * SwtGlChildSurface} drives for real.
 */
@Log4j2
public final class GpuSupport {

    private static final GpuSupport INSTANCE = new GpuSupport();

    /** Null until first probed; then the cached availability for this process. */
    private Boolean available;

    private GpuSupport() {
    }

    public static GpuSupport instance() {
        return INSTANCE;
    }

    /** Probes GPU availability the first time using {@code parent} as a transient
     *  host for the test canvas (any realized {@link Composite} — the main shell at
     *  startup), then returns the cached result on every later call. */
    public boolean isAvailable(Composite parent) {
        if (available == null) {
            // On X11 a GLCanvas needs a REALIZED native window; probing before the
            // shell is shown makes glXMakeCurrent abort the process (BadDrawable).
            // So on Linux, until the shell is visible, report unavailable WITHOUT
            // caching — the post-open re-probe (MainWindow.open) finds the GPU once
            // the window is up.  Windows (GLCanvas) and macOS (GLFW) probe fine
            // pre-realize, so they aren't deferred (no startup CPU→GPU rebuild).
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            boolean linux = !os.contains("win") && !os.contains("mac");
            if (linux && (parent == null || parent.isDisposed()
                    || parent.getShell() == null || !parent.getShell().getVisible())) {
                return false;
            }
            available = probe(parent);
            if (log.isInfoEnabled()) {
                log.info("GPU scope acceleration {}.", available ? "available" : "unavailable");
            }
        }
        return available;
    }

    /** The cached result, or {@code false} if {@link #isAvailable(Composite)} has not
     *  been called yet. */
    public boolean isAvailable() {
        return available != null && available;
    }

    /** Whether a real probe has completed (vs. an early "shell not shown yet" answer
     *  that wasn't cached).  The post-open re-probe uses this to run exactly once. */
    public boolean isProbed() {
        return available != null;
    }

    private boolean probe(Composite parent) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) {
            return probeGlfw();     // SWT GLCanvas crashes on Cocoa — use the GLFW-child path
        }
        // isAvailable() only calls this once the shell is shown, so the GLCanvas
        // gets a REALIZED native window — on X11 an unrealized one makes
        // glXMakeCurrent abort the whole process with an async BadDrawable.
        GLCanvas canvas = null;
        long vg = 0L;
        try {
            GLData data = new GLData();
            data.doubleBuffer = true;
            data.stencilSize  = 8;
            canvas = new GLCanvas(parent, SWT.NONE, data);
            canvas.setCurrent();
            GL.createCapabilities();
            vg = nvgCreate(NVG_ANTIALIAS | NVG_STENCIL_STROKES);
            if (vg == 0L && log.isWarnEnabled()) {
                // GL context came up but NanoVG didn't — log it too, otherwise this
                // failure mode is silent (no exception to land in the catch below).
                log.warn("GPU probe: NanoVG context creation returned 0 (scope stays on CPU).");
            }
            return vg != 0L;
        } catch (Throwable t) {           // any GL / native-link failure ⇒ unavailable
            if (log.isWarnEnabled()) {
                log.warn("GPU probe failed (scope stays on CPU): {}", t.toString(), t);
            }
            return false;
        } finally {
            if (vg != 0L) nvgDelete(vg);
            if (canvas != null && !canvas.isDisposed()) canvas.dispose();
        }
    }

    /** macOS round-trip: init GLFW, create a hidden core-3 window, bind LWJGL, make
     *  a NanoVGGL3 context, tear it all down.  Mirrors {@code SwtGlChildSurface}'s
     *  context so a success here means the real surface will work too. */
    private boolean probeGlfw() {
        if (!Glfw.instance().ensureInit()) return false;
        long win = NULL;
        long vg  = 0L;
        try {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
            glfwWindowHint(GLFW_STENCIL_BITS, 8);
            win = glfwCreateWindow(4, 4, "gpu-probe", NULL, NULL);
            if (win == NULL) return false;
            glfwMakeContextCurrent(win);
            GL.createCapabilities();
            vg = NanoVGGL3.nvgCreate(NVG_ANTIALIAS | NVG_STENCIL_STROKES);
            return vg != 0L;
        } catch (Throwable t) {           // any GL / native-link failure ⇒ unavailable
            if (log.isDebugEnabled()) {
                log.debug("GPU GLFW probe failed: {}", t.toString());
            }
            return false;
        } finally {
            if (win != NULL) {
                if (vg != 0L) {
                    glfwMakeContextCurrent(win);
                    NanoVGGL3.nvgDelete(vg);
                }
                glfwMakeContextCurrent(NULL);
                glfwDestroyWindow(win);
            }
        }
    }
}
