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

package org.edgo.audio.measure.gui.registry;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.widgets.Control;

/**
 * Process-wide map of the live UI as an addressable tree — a "virtual
 * component hierarchy".  Each meaningful widget (pane, settings tab, …)
 * registers itself under a slash-separated path during construction; an
 * automation script (or any caller) can then resolve a component <em>by
 * path</em> and act on it — select the tab it represents, maximize the pane,
 * or grab a screenshot of it — without holding a widget reference or knowing
 * the SWT layout.
 *
 * <h2>Paths</h2>
 * <p>Slash-separated, like a filesystem or an XPath: e.g.
 * {@code multifunctional/fft/tabs/thd}.  {@link #resolve} accepts {@code *}
 * as a single-level wildcard ({@code multifunctional/} '*' {@code /tabs/save}
 * matches the Save tab of whichever pane).
 *
 * <h2>Rebuild safety</h2>
 * <p>A language / font change recreates every pane, so the registry is
 * {@link #clear() cleared} at the start of each main-tab build and
 * re-populated as the panes reconstruct.  Callers resolve fresh on every
 * access, so a node never hands back a disposed control.
 *
 * <h2>Threading</h2>
 * <p>Registration runs on the UI thread (widget construction); resolution and
 * actions are marshalled onto the UI thread by the caller (the automation
 * base class uses {@code ui(...)}).  All mutating / reading methods are
 * synchronized on the instance.
 */
public final class UiRegistry {

    private static volatile UiRegistry instance;

    public static UiRegistry instance() {
        UiRegistry local = instance;
        if (local != null) return local;
        synchronized (UiRegistry.class) {
            if (instance == null) instance = new UiRegistry();
            return instance;
        }
    }

    /** Anonymous root; its children are the top-level components. */
    private UiNode root = new UiNode("", null);

    private UiRegistry() {}

    /** Drops the whole tree.  Called before a main-tab rebuild so the panes
     *  re-register fresh controls instead of leaving disposed ones behind. */
    public synchronized void clear() {
        root = new UiNode("", null);
    }

    /** Registers (or returns the existing) node at {@code path} and attaches
     *  {@code control} to it.  Returns the node so the caller can chain
     *  {@code onActivate} / {@code onMaximize} / {@code onRestore}. */
    public synchronized UiNode register(String path, Control control) {
        UiNode node = ensure(path);
        node.setControl(control);
        return node;
    }

    /** Registers (or returns) a grouping node with no control of its own —
     *  e.g. an intermediate {@code .../tabs} level. */
    public synchronized UiNode register(String path) {
        return ensure(path);
    }

    /** Resolves {@code path} to its node, or {@code null} if no node matches.
     *  A {@code *} segment matches any single child (first one wins). */
    public synchronized UiNode resolve(String path) {
        UiNode current = root;
        for (String segment : split(path)) {
            if (current == null) return null;
            current = "*".equals(segment) ? current.firstChild() : current.child(segment);
        }
        return current == root ? null : current;
    }

    /** Every registered component's path (those with a control or a
     *  capability), depth-first — the "lookup" / discovery view, so an
     *  automation author can see what is addressable. */
    public synchronized List<String> componentPaths() {
        List<String> paths = new ArrayList<>();
        root.collectPaths(paths);
        return paths;
    }

    private UiNode ensure(String path) {
        UiNode current = root;
        for (String segment : split(path)) {
            current = current.childOrCreate(segment);
        }
        return current;
    }

    private String[] split(String path) {
        // Trim leading / trailing slashes so "/a/b/" == "a/b".
        String trimmed = path.replaceAll("^/+", "").replaceAll("/+$", "");
        return trimmed.isEmpty() ? new String[0] : trimmed.split("/+");
    }
}
