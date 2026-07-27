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

import lombok.extern.log4j.Log4j2;

import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwPollEvents;

/**
 * Process-wide GLFW lifecycle for the macOS GPU scope surface ({@link
 * SwtGlChildSurface}).  GLFW is the embedding mechanism only on macOS — Windows /
 * Linux use SWT's own {@code GLCanvas} — so GLFW is initialised lazily, only when
 * that path is taken, and stays uninitialised (every call a no-op) elsewhere.
 *
 * <p>SWT already owns {@code NSApplication} on the main thread; GLFW coexists with
 * it.  {@link #poll()} must run on the main (UI) thread — macOS requires
 * {@code glfwPollEvents} there — and is driven once per turn of the SWT event loop
 * so the child window's input callbacks fire.
 */
@Log4j2
public final class Glfw {

    private static final Glfw INSTANCE = new Glfw();

    private volatile boolean initialized;

    private Glfw() {
    }

    public static Glfw instance() {
        return INSTANCE;
    }

    /** Initialises GLFW once; returns whether it is usable.  Safe to call repeatedly. */
    public synchronized boolean ensureInit() {
        if (!initialized) {
            if (glfwInit()) {
                initialized = true;
            } else if (log.isWarnEnabled()) {
                log.warn("glfwInit failed (NSApplication conflict?); GPU scope unavailable.");
            }
        }
        return initialized;
    }

    /** Pumps GLFW window / input events; a no-op until {@link #ensureInit} has
     *  succeeded, so it is harmless to call unconditionally from the event loop. */
    public void poll() {
        if (initialized) {
            glfwPollEvents();
        }
    }

    /** Whether GLFW is live — the event loop uses this to keep polling (a short nap
     *  instead of blocking in {@code Display.sleep()}) so the child window's input
     *  stays responsive while the scope is stopped. */
    public boolean isActive() {
        return initialized;
    }
}
