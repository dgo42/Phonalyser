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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.gui.common.Icon;
import org.edgo.audio.measure.gui.common.IconUtils;

/**
 * Combo-style selector for {@link GenSignalForm} with per-entry 24×24
 * pictograms.  The closed display is an icon {@link Label} + a text
 * {@link Label} + an SVG caret; the dropdown is a native {@code SWT.POP_UP}
 * {@link Menu} whose {@link MenuItem}s carry each form's pictogram + label.
 *
 * <p>A popup menu (rather than the stock SWT {@code Combo}) is used because
 * menu items show per-item images on <em>every</em> platform — which
 * {@code Combo} cannot — and the menu renders with the native selection
 * colours, hover highlight and keyboard navigation for free, with no
 * owner-drawing.
 */
public final class SignalFormCombo extends Composite {

    /** Display labels in dropdown order. */
    private static final Map<GenSignalForm, String> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put(GenSignalForm.SINE,                  "Sine");
        LABELS.put(GenSignalForm.SINE_COMP,             "Sine (compensated)");
        LABELS.put(GenSignalForm.DUAL_TONE,             "Dual tone");
        LABELS.put(GenSignalForm.DUAL_TONE_COMP, "Dual tone (compensated)");
        LABELS.put(GenSignalForm.TRIANGLE,          "Triangle");
        LABELS.put(GenSignalForm.RECTANGLE,         "Rectangle / pulse");
        LABELS.put(GenSignalForm.WHITE_NOISE,       "White noise");
        LABELS.put(GenSignalForm.PINK_NOISE,        "Pink noise");
        LABELS.put(GenSignalForm.PINK_NOISE_LINEAR, "Pink noise (linear)");
        LABELS.put(GenSignalForm.LINEAR_SWEEP,      "Linear sweep");
        LABELS.put(GenSignalForm.LOG_SWEEP,         "Log sweep (Farina)");
    }

    private final List<Listener> selectionListeners = new ArrayList<>();
    private final Label          iconLabel;
    private final Label          textLabel;
    private final Canvas         dropArrow;
    private GenSignalForm        current;

    public SignalFormCombo(Composite parent, GenSignalForm initial) {
        super(parent, SWT.BORDER);
        this.current = initial != null ? initial : GenSignalForm.SINE;

        GridLayout gl = new GridLayout(3, false);
        gl.marginWidth  = 2;
        gl.marginHeight = 2;
        gl.horizontalSpacing = 4;
        gl.verticalSpacing = 0;
        setLayout(gl);
        // Match the native combo's control-grey background instead of the
        // default white (most visible on macOS); INHERIT_DEFAULT lets the
        // icon/text/caret children pick up the same colour.
        setBackground(getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
        setBackgroundMode(SWT.INHERIT_DEFAULT);

        iconLabel = new Label(this, SWT.NONE);
        // No size hint — the Label sizes to its PNG (each pictogram its own size).
        iconLabel.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, true));
        iconLabel.setImage(IconUtils.icon(getDisplay(), Icon.valueOf("SIGNAL_" + current.name())));

        textLabel = new Label(this, SWT.NONE);
        textLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, true));
        textLabel.setText(LABELS.getOrDefault(current, current.name()));

        // Drop-arrow drawn as an SVG label — the native SWT.ARROW|DOWN
        // Button renders inconsistently across GTK / win32 (a tiny pixel
        // glyph on some Linux themes), so we paint the caret ourselves.
        // No press-state animation in the combo (the popup itself is the
        // visual feedback) — pass equal sizes to disable the swap. #808080
        dropArrow = new IconStepLabel(this, Icon.DROPDOWN, Icon.DROPDOWN);
        GridData arrowGd = new GridData(SWT.FILL, SWT.FILL, false, true);
        arrowGd.widthHint = 18;
        dropArrow.setLayoutData(arrowGd);

        // Any click on the closed display (icon / text / background / caret)
        // opens the dropdown menu; the menu dismisses itself on selection,
        // outside-click or Escape.
        Listener open = e -> openPopup();
        addListener     (SWT.MouseDown, open);
        iconLabel.addListener(SWT.MouseDown, open);
        textLabel.addListener(SWT.MouseDown, open);
        dropArrow.addListener(SWT.MouseDown, open);
    }

    @Override
    public Point computeSize(int wHint, int hHint, boolean changed) {
        // Probe the widest label so the closed display can hold any of them.
        int maxText = 0;
        GC probe = new GC(this);
        try {
            for (String s : LABELS.values()) {
                int w = probe.textExtent(s).x;
                if (w > maxText) maxText = w;
            }
        } finally {
            probe.dispose();
        }
        Image icon = iconLabel.getImage();
        int iconW = icon != null ? icon.getBounds().width  : 0;
        int iconH = icon != null ? icon.getBounds().height : 0;
        int w = iconW + 4 + maxText + 4 + 18 + 6;
        int h = Math.max(iconH + 4, 24);
        return new Point(wHint != SWT.DEFAULT ? wHint : w,
                         hHint != SWT.DEFAULT ? hHint : h);
    }

    public GenSignalForm getSelectedForm() { return current; }

    public void setSelectedForm(GenSignalForm f) {
        if (f == null || f == current) return;
        current = f;
        iconLabel.setImage(IconUtils.icon(getDisplay(), Icon.valueOf("SIGNAL_" + current.name())));
        textLabel.setText(LABELS.getOrDefault(current, current.name()));
        layout();
        fire();
    }

    public void addSelectionListener(Listener l) {
        selectionListeners.add(l);
    }

    private void fire() {
        Event e = new Event();
        e.widget = this;
        e.data   = current;
        for (Listener l : selectionListeners) l.handleEvent(e);
    }

    /**
     * Opens the native pop-up menu just below the closed display.  Each item
     * carries the form's pictogram + label; macOS / Windows / GTK render the
     * selection, hover and keyboard navigation natively.  The menu is disposed
     * once it closes.
     */
    private void openPopup() {
        Display d = getDisplay();
        Menu menu = new Menu(this);
        for (GenSignalForm form : LABELS.keySet()) {
            MenuItem item = new MenuItem(menu, SWT.PUSH);
            item.setText(LABELS.getOrDefault(form, form.name()));
            item.setImage(IconUtils.icon(d, Icon.valueOf("SIGNAL_" + form.name())));
            item.addListener(SWT.Selection, e -> setSelectedForm(form));
        }
        // Dispose after it closes (selection / outside-click / Escape) — async
        // so the item's Selection event is delivered first.
        menu.addListener(SWT.Hide, e -> d.asyncExec(() -> {
            if (!menu.isDisposed()) menu.dispose();
        }));
        Point at = toDisplay(0, getSize().y);
        menu.setLocation(at.x, at.y);
        menu.setVisible(true);
    }

    /** Propagate tooltip to inner children — the Labels and caret Canvas
     *  cover the Composite, so hover events never reach this widget's own
     *  background. */
    @Override
    public void setToolTipText(String text) {
        super.setToolTipText(text);
        iconLabel.setToolTipText(text);
        textLabel.setToolTipText(text);
        dropArrow.setToolTipText(text);
    }

    @Override
    protected void checkSubclass() {
        // SWT forbids subclassing of most widgets by default — opt back in.
    }
}
