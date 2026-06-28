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

import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.edgo.audio.measure.preferences.Preferences;

/**
 * An embedded GPU drawing surface for the oscilloscope — it hides the per-OS
 * mechanism that puts OpenGL inside the SWT scope pane behind one interface so the
 * pane stays OS-agnostic.
 *
 * <p>Two backends: {@code SwtGlCanvasSurface} (Windows / Linux — an SWT
 * {@code GLCanvas} + NanoVGGL2) and {@code SwtGlChildSurface} (macOS — a GLFW
 * child window reparented onto the shell + NanoVGGL3, where SWT's own
 * {@code GLCanvas} crashes).  Both own a NanoVG context, expose the SWT
 * {@link #control() control} the pane lays out where the scope canvas sits, and
 * render a frame on demand via {@link #render()}.
 *
 * <p>{@link #render()} runs on the SWT UI thread, driven by the scope's existing
 * realtime-frame loop — the scope's capture/trigger/filter state is UI-thread, so
 * rendering there keeps it thread-safe (no render thread).  The frame draws through
 * the {@link GlScopeRenderer} set via {@link #setRenderer}.
 */
public interface GlScopeSurface {

    /** The GPU surface for this platform as a child of {@code parent}, or
     *  {@code null} when GPU acceleration is unavailable ({@link GpuSupport} — macOS,
     *  or no working GL context) or switched off (the "use GPU acceleration"
     *  preference), in which case the scope falls back to the CPU/GC path. */
    static GlScopeSurface of(Composite parent) {
        // Probe first (caches), so availability is known for the Look & Feel checkbox
        // even when the pref is off; macOS / no-GL machines report unavailable.
        if (!GpuSupport.instance().isAvailable(parent)) return null;
        if (!Preferences.instance().isUseGpuAcceleration()) return null;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("mac") ? new SwtGlChildSurface(parent) : new SwtGlCanvasSurface(parent);
    }

    /** The SWT widget to place in the pane layout where the scope canvas sits.
     *  Windows/Linux: the {@code GLCanvas} itself; macOS: a placeholder the floating
     *  GL child window tracks. */
    Control control();

    /** Sets the per-frame draw callback (typically the {@code ScopeView} itself, which
     *  implements {@link GlScopeRenderer}). */
    void setRenderer(GlScopeRenderer renderer);

    /** Renders one realtime frame now, on the calling (UI) thread.  With persistence on,
     *  this is the path that decays + accumulates a genuinely new captured trace. */
    void render();

    /** Renders one frame in response to a UI gesture / settings change (pan, zoom, V/div,
     *  slider drag) rather than the realtime loop.  With persistence on, the view geometry
     *  changed, so the old afterglow is at stale coordinates — the surface re-renders the
     *  trace and RESETS the phosphor instead of accumulating, so the trace tracks the
     *  gesture rather than smearing.  Default: a plain {@link #render()} (no persistence). */
    default void renderInteractive() { render(); }

    /** Shows or hides the surface when the scope pane expands / collapses.  The
     *  Win/Linux {@code GLCanvas} is a real child that SWT hides with its parent, so
     *  this is a no-op there; the macOS floating child window must be hidden
     *  explicitly or it overlaps whatever expands into the freed space. */
    default void setSurfaceVisible(boolean visible) { }

    /** Releases the GL context and native resources (not the {@link #control()},
     *  which SWT disposes with its parent). */
    void dispose();
}
