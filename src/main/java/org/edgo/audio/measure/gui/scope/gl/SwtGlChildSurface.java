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

import java.lang.reflect.Field;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.edgo.audio.measure.gui.common.NvgMeasurementPainter;

import com.sun.jna.Library;
import com.sun.jna.Native;

import org.lwjgl.glfw.GLFWNativeCocoa;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;

import lombok.Setter;
import lombok.extern.log4j.Log4j2;

import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_DECORATED;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_FOCUS_ON_SHOW;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_STENCIL_BITS;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.glfw.GLFW.glfwGetWindowSize;
import static org.lwjgl.glfw.GLFW.glfwHideWindow;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback;
import static org.lwjgl.glfw.GLFW.glfwSetMouseButtonCallback;
import static org.lwjgl.glfw.GLFW.glfwSetScrollCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowPos;
import static org.lwjgl.glfw.GLFW.glfwSetWindowSize;
import static org.lwjgl.glfw.GLFW.glfwShowWindow;
import static org.lwjgl.glfw.GLFW.glfwSwapBuffers;
import static org.lwjgl.glfw.GLFW.glfwSwapInterval;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.nanovg.NanoVG.NVG_IMAGE_FLIPY;
import static org.lwjgl.nanovg.NanoVG.NVG_IMAGE_PREMULTIPLIED;
import static org.lwjgl.nanovg.NanoVG.nvgBeginFrame;
import static org.lwjgl.nanovg.NanoVG.nvgEndFrame;
import static org.lwjgl.nanovg.NanoVGGL3.NVG_ANTIALIAS;
import static org.lwjgl.nanovg.NanoVGGL3.NVG_IMAGE_NODELETE;
import static org.lwjgl.nanovg.NanoVGGL3.NVG_STENCIL_STROKES;
import static org.lwjgl.nanovg.NanoVGGL3.nvgCreate;
import static org.lwjgl.nanovg.NanoVGGL3.nvgDelete;
import static org.lwjgl.nanovg.NanoVGGL3.nvglCreateImageFromHandle;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * {@link GlScopeSurface} for macOS, where SWT's own {@code GLCanvas} crashes on
 * Cocoa.  A borderless GLFW OpenGL window is reparented as a Cocoa <b>child
 * window</b> of the SWT shell and tracks the on-screen rectangle of an SWT
 * placeholder control ({@link #control()}) that holds the scope's layout cell — so
 * the pane lays out exactly as on Windows / Linux while the GPU draws into the
 * floating child.  Rendering uses LWJGL NanoVG (GL3 core profile).
 *
 * <p>Cross-compiles on every OS: the Cocoa reparent is reached only through
 * reflection (the SWT NSWindow pointer, matched by simple type name) and JNA →
 * libobjc (the {@code addChildWindow:ordered:} message); it executes only on
 * macOS.  GLFW must be initialised first ({@link Glfw}) and the JVM launched with
 * {@code -XstartOnFirstThread}.
 *
 * <p>Unlike the probe it grew from, {@link #render()} runs on the SWT UI thread,
 * driven by the scope's realtime-frame loop — the scope's capture / trigger /
 * filter state is UI-thread, so rendering there keeps it thread-safe.  Because the
 * child window floats above the placeholder, the placeholder never receives native
 * mouse events; instead GLFW's input callbacks (which fire on the UI thread during
 * {@link Glfw#poll()}) are translated into SWT events posted to the placeholder, so
 * the pane's existing pointer / wheel wiring drives the view unchanged.
 */
@Log4j2
public final class SwtGlChildSurface implements GlScopeSurface {

    /** Minimal Objective-C runtime binding (JNA) for the child-window call and the
     *  live modifier-flag query. */
    public interface ObjC extends Library {
        long sel_registerName(String name);
        long objc_getClass(String name);
        long objc_msgSend(long receiver, long selector, long arg1, long arg2);
    }

    private static final long   NS_WINDOW_ABOVE   = 1L;   // NSWindowOrderingMode.NSWindowAbove
    // NSEvent.modifierFlags bits — the floating child never becomes the key window,
    // so GLFW can't see modifiers; query Cocoa for the wheel zoom/pan axis instead.
    private static final long   NS_SHIFT          = 1L << 17;
    private static final long   NS_COMMAND        = 1L << 20;
    private static final int    MIN_CHILD_PX      = 1;
    private static final int    GL_MAJOR          = 3;
    private static final int    GL_MINOR          = 2;
    private static final int    STENCIL_BITS      = 8;
    /** Two presses closer than this (ms / px) count as an SWT double-click — GLFW
     *  reports only individual button presses. */
    private static final long   DOUBLE_CLICK_MS   = 300L;
    private static final int    DOUBLE_CLICK_SLOP = 4;

    private final Composite placeholder;
    private final Shell     shell;

    private long    window;     // GLFW window handle
    private long    vg;
    private NvgMeasurementPainter painter;
    private boolean glReady;
    private boolean fboSupported;
    private ScopePhosphor phosphor;

    private int winW, winH;     // child window size in points (SWT coords)
    private int fbW,  fbH;      // framebuffer size in pixels (Retina-aware)

    // Last placeholder screen rectangle pushed to the child — repositioned only on
    // change, never per frame (moving + resizing a native window 60×/s crawls).
    private int lastX = Integer.MIN_VALUE, lastY, lastW, lastH;

    // Pointer state for SWT-event synthesis (callbacks run on the UI thread).
    private int  cursorX, cursorY;
    private long lastDownNanos;
    private int  lastDownX, lastDownY;

    // Cocoa runtime handles, cached for the live modifier-flag query (wheel axis).
    private ObjC objc;
    private long nsEventClass;
    private long modifierFlagsSel;

    @Setter private GlScopeRenderer renderer;

    public SwtGlChildSurface(Composite parent) {
        placeholder = new Composite(parent, SWT.NO_BACKGROUND);
        shell = parent.getShell();
        createChildWindow();
        if (window != NULL) {
            reparentToShell();
            installInputCallbacks();
            trackPlaceholder();
            placeholder.addListener(SWT.Resize, e -> render());
            placeholder.addDisposeListener(e -> dispose());
        }
    }

    @Override
    public Control control() {
        return placeholder;
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
        if (window == NULL || placeholder.isDisposed() || renderer == null) return;
        trackPlaceholder();
        if (winW <= 0 || winH <= 0) return;

        glfwMakeContextCurrent(window);
        if (!glReady) {
            GLCapabilities caps = GL.createCapabilities();
            fboSupported = caps.OpenGL30;              // raw-GL30 phosphor FBOs (core in this GL3.2 context)
            glfwSwapInterval(0);                       // no vsync: don't block the UI thread on swap
            vg = nvgCreate(NVG_ANTIALIAS | NVG_STENCIL_STROKES);
            glReady = true;
        }
        if (vg == 0L) return;

        float px = (winH > 0) ? (float) fbH / winH : 1f;
        if (painter == null) painter = new NvgMeasurementPainter(vg, placeholder.getDisplay());
        if (phosphor == null) {
            phosphor = new ScopePhosphor(vg, painter, (v, t, iw, ih) ->
                    nvglCreateImageFromHandle(v, t, iw, ih,
                            NVG_IMAGE_FLIPY | NVG_IMAGE_PREMULTIPLIED | NVG_IMAGE_NODELETE));
        }

        // macOS Retina: NanoVG draws in logical points (winW × winH) at ratio px; the GL
        // viewport / framebuffers are the physical pixel size (fbW × fbH).
        GlFrameSize size = new GlFrameSize(winW, winH, fbW, fbH, px);
        if (!fboSupported || !phosphor.render(renderer, kind, size)) {
            renderWhole(px);                           // persistence off / unsupported: direct full render
        }
        glfwSwapBuffers(window);
    }

    /** Off path: a single-pass full render straight to the GLFW window (no phosphor buffer). */
    private void renderWhole(float px) {
        GL11.glViewport(0, 0, fbW, fbH);
        GL11.glClearColor(0f, 0f, 0f, 1f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_STENCIL_BUFFER_BIT);
        nvgBeginFrame(vg, winW, winH, px);
        painter.reset(winW, winH, px);
        renderer.renderGl(painter, winW, winH, GlScopeRenderer.Phase.ALL);
        nvgEndFrame(vg);
    }

    @Override
    public void dispose() {
        if (painter != null) {
            painter.dispose();
            painter = null;
        }
        if (window != NULL) {
            if (vg != 0L) {
                glfwMakeContextCurrent(window);
                if (phosphor != null) phosphor.release();
                nvgDelete(vg);
                vg = 0L;
            }
            glfwMakeContextCurrent(NULL);
            glfwDestroyWindow(window);
            window = NULL;
        }
    }

    @Override
    public void setSurfaceVisible(boolean visible) {
        if (window == NULL) return;
        if (visible) {
            glfwShowWindow(window);
            // orderOut on hide drops the Cocoa child-window relationship, so the
            // re-shown window floats detached / mispositioned — re-establish it.
            reparentToShell();
            lastX = Integer.MIN_VALUE;   // force trackPlaceholder to reposition
            // Re-render once the pane's expand layout has settled — at this point the
            // placeholder isn't resized yet, so render now would track a stale rect.
            // (The realtime loop already covers the live-capture case.)
            if (!placeholder.isDisposed()) placeholder.getDisplay().asyncExec(this::render);
        } else {
            glfwHideWindow(window);
        }
    }

    // ─── GLFW window + Cocoa reparent ───────────────────────────────────────────

    private void createChildWindow() {
        if (!Glfw.instance().ensureInit()) return;   // window stays NULL → CPU fallback
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_DECORATED, GLFW_FALSE);
        glfwWindowHint(GLFW_FOCUS_ON_SHOW, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, GL_MAJOR);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, GL_MINOR);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_STENCIL_BITS, STENCIL_BITS);
        Rectangle b = placeholder.getClientArea();
        window = glfwCreateWindow(Math.max(MIN_CHILD_PX, b.width),
                                  Math.max(MIN_CHILD_PX, b.height), "scope-gl", NULL, NULL);
        if (window == NULL && log.isWarnEnabled()) {
            log.warn("glfwCreateWindow failed; GPU scope unavailable.");
        }
    }

    /** Reparents the GLFW NSWindow as a Cocoa child of the SWT shell's NSWindow so
     *  it moves with the shell and clips to it. */
    private void reparentToShell() {
        try {
            long glfwNs = GLFWNativeCocoa.glfwGetCocoaWindow(window);
            long swtNs  = shellNsWindow();
            objc = Native.load("objc", ObjC.class);
            objc.objc_msgSend(swtNs, objc.sel_registerName("addChildWindow:ordered:"),
                              glfwNs, NS_WINDOW_ABOVE);
            nsEventClass     = objc.objc_getClass("NSEvent");
            modifierFlagsSel = objc.sel_registerName("modifierFlags");
        } catch (RuntimeException | ReflectiveOperationException ex) {
            if (log.isWarnEnabled()) {
                log.warn("Cocoa reparent failed (child window will float): {}", ex.toString());
            }
        }
    }

    /** SWT shell's native NSWindow pointer, via reflection (no cocoa imports): find
     *  the shell's NSView (matched by simple type name), ask it for its window, read
     *  its {@code id}. */
    private long shellNsWindow() throws ReflectiveOperationException {
        Object view = fieldOfSimpleType(shell, "NSView");
        if (view == null) throw new IllegalStateException("SWT NSView not found (not macOS?)");
        Object nsWindow = view.getClass().getMethod("window").invoke(view);
        return nsWindow.getClass().getField("id").getLong(nsWindow);
    }

    private Object fieldOfSimpleType(Object owner, String simpleTypeName) throws IllegalAccessException {
        for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().getSimpleName().equals(simpleTypeName)) {
                    f.setAccessible(true);
                    Object v = f.get(owner);
                    if (v != null) return v;
                }
            }
        }
        return null;
    }

    /** Aligns the child window with the placeholder's current screen rectangle, but
     *  only issues the native move/resize when it actually changed. */
    private void trackPlaceholder() {
        if (placeholder.isDisposed()) return;
        Rectangle b = placeholder.getClientArea();
        Point origin = placeholder.toDisplay(0, 0);
        int w = Math.max(MIN_CHILD_PX, b.width);
        int h = Math.max(MIN_CHILD_PX, b.height);
        if (origin.x != lastX || origin.y != lastY || w != lastW || h != lastH) {
            glfwSetWindowSize(window, w, h);
            glfwSetWindowPos(window, origin.x, origin.y);
            lastX = origin.x; lastY = origin.y; lastW = w; lastH = h;
        }
        int[] a = new int[1];
        int[] c = new int[1];
        glfwGetWindowSize(window, a, c);      winW = a[0]; winH = c[0];
        glfwGetFramebufferSize(window, a, c); fbW  = a[0]; fbH  = c[0];
    }

    // ─── Input: GLFW callbacks → synthetic SWT events on the placeholder ─────────

    private void installInputCallbacks() {
        glfwSetCursorPosCallback(window, (win, x, y) -> {
            cursorX = (int) x;
            cursorY = (int) y;
            postMouse(SWT.MouseMove, 0, 0, 0);
        });
        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            int swtButton = switch (button) {
                case 1  -> 3;   // GLFW right  → SWT button 3
                case 2  -> 2;   // GLFW middle → SWT button 2
                default -> 1;   // GLFW left   → SWT button 1
            };
            if (action == GLFW_PRESS) {
                long now = System.nanoTime();
                boolean dbl = (now - lastDownNanos) < DOUBLE_CLICK_MS * 1_000_000L
                        && Math.abs(cursorX - lastDownX) <= DOUBLE_CLICK_SLOP
                        && Math.abs(cursorY - lastDownY) <= DOUBLE_CLICK_SLOP;
                postMouse(SWT.MouseDown, swtButton, 1, 0);
                if (dbl) {
                    postMouse(SWT.MouseDoubleClick, swtButton, 2, 0);
                    lastDownNanos = 0;
                } else {
                    lastDownNanos = now;
                    lastDownX = cursorX;
                    lastDownY = cursorY;
                }
            } else {
                postMouse(SWT.MouseUp, swtButton, 1, 0);
            }
        });
        glfwSetScrollCallback(window, (win, dx, dy) -> {
            // macOS turns Shift+scroll into a HORIZONTAL scroll (delta in dx, dy=0),
            // so take whichever axis carries the gesture — otherwise Shift+wheel
            // reads count 0 and the view's wheel handler ignores it.
            double d = (Math.abs(dy) >= Math.abs(dx)) ? dy : dx;
            postMouse(SWT.MouseWheel, 0, (int) Math.round(d), wheelStateMask());
        });
    }

    /** Shift / Cmd state for a wheel event, driving the view's pan/zoom axis.
     *  GLFW can't report it — the child never becomes the key window, so
     *  glfwGetKey stays empty — so read Cocoa's live {@code [NSEvent modifierFlags]}.
     *  On macOS the zoom gesture is Cmd (the platform's Ctrl-equivalent), mapped to
     *  SWT.CTRL: Cmd+wheel = V/div, Cmd+Shift+wheel = t/div, plain wheel = pan. */
    private int wheelStateMask() {
        if (objc == null || nsEventClass == 0L) return 0;
        long flags = objc.objc_msgSend(nsEventClass, modifierFlagsSel, 0L, 0L);
        int mask = 0;
        if ((flags & NS_SHIFT)   != 0L) mask |= SWT.SHIFT;
        if ((flags & NS_COMMAND) != 0L) mask |= SWT.CTRL;
        return mask;
    }

    private void postMouse(int type, int button, int count, int stateMask) {
        if (placeholder.isDisposed()) return;
        Display d = placeholder.getDisplay();
        if (d.getThread() != Thread.currentThread()) return;   // callbacks should be on the UI thread
        Event e = new Event();
        e.type = type;
        e.x = cursorX;
        e.y = cursorY;
        e.button = button;
        e.count = count;
        e.stateMask = stateMask;
        placeholder.notifyListeners(type, e);
    }
}
