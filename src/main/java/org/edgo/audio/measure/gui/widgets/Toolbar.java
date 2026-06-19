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

package org.edgo.audio.measure.gui.widgets;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.RowData;
import org.eclipse.swt.layout.RowLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.edgo.audio.measure.gui.common.Icon;

/**
 * A transparent button-row container + factory — holds {@link ToolButton}s (and bare
 * {@link TransparentComposite} spacers for wider gaps) in a horizontal {@link RowLayout} at a fixed
 * button size.  It paints nothing itself ({@code SWT.NO_BACKGROUND | SWT.TRANSPARENT},
 * no paint listener): the buttons paint themselves and the plot shows through the gaps.
 */
public final class Toolbar extends TransparentComposite {

    private static final int BUTTON_SPACING = 2;

    private final int buttonWidth;
    private final int buttonHeight;

    public Toolbar(Composite parent, int buttonWidth, int buttonHeight) {
        super(parent);
        this.buttonWidth  = buttonWidth;
        this.buttonHeight = buttonHeight;
        RowLayout layout = new RowLayout(SWT.HORIZONTAL);
        layout.marginWidth  = 0;
        layout.marginHeight = 0;
        layout.spacing      = BUTTON_SPACING;
        layout.wrap         = false;   // single row — never stack buttons underneath
        setLayout(layout);
    }

    /** Re-sizes self (its preferred width changed) via the parent layout, then re-flows
     *  the buttons — call after toggling child visibility/exclusion. */
    public void reflow() {
        getParent().layout(new Control[]{this}, SWT.CHANGED);
        layout(true);
    }

    /** Adds an unconfigured button at the toolbar's fixed button size — the caller sets
     *  its icon/colours/listener. */
    public ToolButton add() {
        ToolButton b = new ToolButton(this);
        b.setLayoutData(new RowData(buttonWidth, buttonHeight));
        return b;
    }

    /** Adds a do-nothing spacer of {@code width} px to widen the gap between groups. */
    public TransparentComposite spacer(int width) {
        TransparentComposite s = new TransparentComposite(this);
        s.setLayoutData(new RowData(width, buttonHeight));
        return s;
    }

    /** Adds a label toggle button to the mutually-exclusive radio {@code group} —
     *  framed in {@code frame} normally, {@code fill}-filled when selected.  The
     *  initial selection is set here (in construction), not in any paint pass. */
    public ToolButton chanButton(String label, Color textColor, Color frame, Color fill,
                                 Font font, String tooltip, boolean selected, String group) {
        ToolButton b = new ToolButton(this);
        b.setLabel(label, textColor);
        b.setColors(frame, fill);
        b.setFont(font);
        b.setToolTipText(tooltip);
        b.setGroup(group);
        b.setToggled(selected);
        b.setLayoutData(new RowData(buttonWidth, buttonHeight));
        return b;
    }

    /** Adds a push button (no frame) — pressing fills it with {@code fill} and shows the
     *  {@code iconInverted} icon; otherwise the {@code icon} icon, transparent. */
    public ToolButton pushButton(Icon normal, Icon active, Color fill, String tooltip) {
        ToolButton b = new ToolButton(this);
        b.setIcon(normal, active);
        b.setColors(null, fill);
        b.setDrawFrame(false);
        b.setToolTipText(tooltip);
        b.setLayoutData(new RowData(buttonWidth, buttonHeight));
        return b;
    }

    /** Adds a toggle button — framed in {@code accent} normally; when on, filled with
     *  {@code accent} and showing the {@code iconInverted} icon.  Latches on mouse-down
     *  and fires {@link SWT#Selection}; the {@code on} initial state is set here. */
    public ToolButton toggleButton(Icon normal, Icon active,
                                   Color accent, String tooltip, boolean on) {
        ToolButton b = new ToolButton(this);
        b.setIcon(normal, active);
        b.setColors(accent, accent);
        b.setToggle(true);
        b.setToolTipText(tooltip);
        b.setToggled(on);
        b.setLayoutData(new RowData(buttonWidth, buttonHeight));
        return b;
    }

}
