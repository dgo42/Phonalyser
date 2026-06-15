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

package org.edgo.audio.measure.gui.common;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.edgo.audio.measure.gui.widgets.PaneTitle;

import lombok.Getter;

/**
 * Common base for the measurement panes — Generator, Oscilloscope, FFT and
 * Frequency Response.  Owns the structure every pane shares:
 *
 * <ul>
 *   <li>the bordered {@link #group} root the pane is built inside (a
 *       {@code SWT.BORDER} composite replaces the legacy {@code Group}, whose
 *       GtkFrame label consumed title-bar clicks on GTK and broke the
 *       collapse-on-title UX; the border preserves the visual frame);</li>
 *   <li>the {@link #title} bar and the collapse machinery
 *       ({@link #setCollapsed}/{@link #isCollapsed}) — hide every child but
 *       the title, snapshotting each one's {@code visible} / {@code exclude}
 *       so per-child state survives the round-trip;</li>
 *   <li>uniform sizing + creation of the pane's primary action buttons
 *       (Record / Play / wizard) so every toolbar's actions line up.</li>
 * </ul>
 *
 * <p>Subclasses set the {@link #group}'s layout, assign {@link #title}, and
 * populate the pane; pane-specific collapse side effects hook in via
 * {@link #onCollapsing()} / {@link #onExpanding()}.
 */
public abstract class AbstractPane {

    /** Icon / LED pixel height to render an action button's glyph at. */
    protected static final int ACTION_ICON_SIZE = 33;
    /** Square action-button box (px) — Record / Play / wizard all share it. */
    protected static final int ACTION_BOX_SIZE  = 48;

    /** The pane's root composite — created here, laid out and populated by
     *  the subclass. */
    @Getter
    protected final Composite group;

    /** The clickable title bar; assigned by the subclass once built.  The
     *  collapse logic keeps it visible while hiding every other child. */
    protected PaneTitle title;

    /** True while the pane is collapsed to its title bar. */
    @Getter
    protected boolean collapsed;
    /** Per-child {@code visible} / {@code GridData.exclude} snapshot taken on
     *  collapse and restored on expand. */
    private boolean[] preCollapseChildVisible;
    private boolean[] preCollapseChildExclude;

    protected AbstractPane(Composite parent) {
        this.group = new Composite(parent, SWT.BORDER);
    }

    /**
     * Hides every child except {@link #title} so the pane collapses to its
     * title bar (or restores).  Each child's pre-collapse {@code visible} and
     * {@code GridData.exclude} are snapshotted so mode-specific visibility
     * survives the round-trip.
     */
    public void setCollapsed(boolean wantCollapsed) {
        if (collapsed == wantCollapsed) return;
        if (group == null || group.isDisposed()) return;
        collapsed = wantCollapsed;
        Control[] children = group.getChildren();
        if (collapsed) {
            onCollapsing();
            preCollapseChildVisible = new boolean[children.length];
            preCollapseChildExclude = new boolean[children.length];
            for (int i = 0; i < children.length; i++) {
                if (children[i] == title) continue;
                preCollapseChildVisible[i] = children[i].getVisible();
                if (children[i].getLayoutData() instanceof GridData gd) {
                    preCollapseChildExclude[i] = gd.exclude;
                    gd.exclude = true;
                }
                children[i].setVisible(false);
            }
            if (title != null) title.setCollapsed(true);
        } else {
            onExpanding();
            if (preCollapseChildVisible != null
                    && preCollapseChildVisible.length == children.length) {
                for (int i = 0; i < children.length; i++) {
                    if (children[i] == title) continue;
                    children[i].setVisible(preCollapseChildVisible[i]);
                    if (children[i].getLayoutData() instanceof GridData gd) {
                        gd.exclude = preCollapseChildExclude[i];
                    }
                }
                preCollapseChildVisible = null;
                preCollapseChildExclude = null;
            }
            if (title != null) title.setCollapsed(false);
        }
        group.layout(true);
    }

    /** Pane-specific side effect at the start of a collapse (e.g. the
     *  generator pane snapshots its pixel width).  Default no-op. */
    protected void onCollapsing() {
        // no-op
    }

    /** Pane-specific side effect at the start of an expand (e.g. the
     *  generator pane restores its pixel width).  Default no-op. */
    protected void onExpanding() {
        // no-op
    }

    /**
     * Creates one of the pane's primary action buttons, sized to the common
     * {@link #ACTION_BOX_SIZE} square and anchored to the top-right of its
     * cell (the standard toolbar action-button slot).  The caller sets the
     * icon (rendered at {@link #ACTION_ICON_SIZE}) and tooltip.
     *
     * @param style {@code SWT.PUSH} for a momentary action, {@code SWT.TOGGLE}
     *              for a latching one (e.g. Record)
     */
    protected Button createActionButton(Composite parent, int style) {
        Button b = new Button(parent, style);
        GridData gd = new GridData(SWT.END, SWT.BEGINNING, false, false);
        gd.widthHint  = ACTION_BOX_SIZE;
        gd.heightHint = ACTION_BOX_SIZE;
        b.setLayoutData(gd);
        return b;
    }
}
