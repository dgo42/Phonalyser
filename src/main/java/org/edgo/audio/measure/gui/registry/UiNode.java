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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.swt.widgets.Control;

import lombok.Getter;

/**
 * One node of the {@link UiRegistry} tree: a named component with an optional
 * SWT control (for screenshots / bounds) and optional <em>capability</em>
 * callbacks the registrant supplies — {@link #activate} (select / reveal this
 * component, e.g. a settings tab), {@link #maximize} and {@link #restore} (give
 * a pane the whole view, or undo it).
 *
 * <p>The capability setters return {@code this} so registration reads as a
 * one-liner:
 * <pre>
 *   UiRegistry.instance().register("multifunctional/fft/tabs/thd", body)
 *       .onActivate(() -&gt; folder.setSelection(thdItem));
 * </pre>
 *
 * <p>Insertion order of children is preserved (a {@link LinkedHashMap}) so a
 * {@code *} wildcard and any future "list children" view are deterministic.
 */
public final class UiNode {

    @Getter private final String name;
    private final UiNode parent;
    private final Map<String, UiNode> children = new LinkedHashMap<>();

    /** The widget this node represents, or {@code null} for a pure grouping
     *  node (an intermediate path level). */
    @Getter private Control control;
    /** Selects / reveals this component (e.g. select its tab); {@code null}
     *  when the component can't be activated. */
    @Getter private Runnable activate;
    /** Gives this pane the whole view; {@code null} when not a maximizable
     *  pane. */
    @Getter private Runnable maximize;
    /** Undoes {@link #maximize}; {@code null} when not applicable. */
    @Getter private Runnable restore;

    UiNode(String name, UiNode parent) {
        this.name = name;
        this.parent = parent;
    }

    void setControl(Control control) { this.control = control; }

    public UiNode onActivate(Runnable r) { this.activate = r; return this; }
    public UiNode onMaximize(Runnable r) { this.maximize = r; return this; }
    public UiNode onRestore(Runnable r)  { this.restore  = r; return this; }

    UiNode child(String segment)        { return children.get(segment); }
    UiNode firstChild()                 { return children.isEmpty() ? null : children.values().iterator().next(); }

    UiNode childOrCreate(String segment) {
        return children.computeIfAbsent(segment, s -> new UiNode(s, this));
    }

    /** Full slash path from the root, for diagnostics. */
    public String path() {
        if (parent == null || parent.name.isEmpty()) return name;
        return parent.path() + "/" + name;
    }

    /** Appends this node's path (when it carries a control or any capability)
     *  and every descendant's, depth-first.  Used by
     *  {@link UiRegistry#componentPaths()} for discovery. */
    void collectPaths(List<String> out) {
        if (control != null || activate != null || maximize != null) {
            out.add(path());
        }
        for (UiNode c : children.values()) {
            c.collectPaths(out);
        }
    }
}
