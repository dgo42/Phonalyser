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

package org.edgo.audio.measure.gui.generator;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.gui.widgets.IconStepLabel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Combo-style selector for {@link GenSignalForm} with per-entry 24×24
 * pictograms.  Built from native SWT widgets so it inherits the platform
 * look-and-feel:
 * <ul>
 *   <li>Closed display: an icon {@link Label} + a text {@link Label} +
 *       a native {@code SWT.ARROW | SWT.DOWN} {@link Button} on the right
 *       (same arrow style as {@code StepSelector}).</li>
 *   <li>Dropdown: a borderless {@link Shell} containing a {@link Table}
 *       with one column; each {@link TableItem} carries the form's
 *       pictogram + label.  Table provides native scrollbars when the
 *       list overflows.</li>
 * </ul>
 * SWT's stock {@link org.eclipse.swt.widgets.Combo Combo} can't carry
 * per-item images on Windows, hence the custom assembly.
 */
public final class SignalFormCombo extends Composite {

    /** Display labels in dropdown order. */
    private static final Map<GenSignalForm, String> LABELS = new LinkedHashMap<>();
    static {
        LABELS.put(GenSignalForm.SINE,              "Sine");
        LABELS.put(GenSignalForm.SINE_COMPENSATED,  "Sine (compensated)");
        LABELS.put(GenSignalForm.DUAL_TONE,         "Dual tone");
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
    private GenSignalForm           current;
    /** Currently-open dropdown popup (null when closed).  Tracked so a
     *  second click on the closed display closes the popup instead of
     *  losing focus to the previously-focused app while a stale popup
     *  lingers. */
    private Shell                popup;

    public SignalFormCombo(Composite parent, GenSignalForm initial) {
        super(parent, SWT.BORDER);
        this.current = initial != null ? initial : GenSignalForm.SINE;

        GridLayout gl = new GridLayout(3, false);
        gl.marginWidth  = 2;
        gl.marginHeight = 2;
        gl.horizontalSpacing = 4;
        gl.verticalSpacing = 0;
        setLayout(gl);

        iconLabel = new Label(this, SWT.NONE);
        GridData iconGd = new GridData(SWT.LEFT, SWT.CENTER, false, true);
        iconGd.widthHint  = SignalFormIcon.SIZE;
        iconGd.heightHint = SignalFormIcon.SIZE;
        iconLabel.setLayoutData(iconGd);
        iconLabel.setImage(SignalFormIcon.instance().get(getDisplay(), current));

        textLabel = new Label(this, SWT.NONE);
        textLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, true));
        textLabel.setText(labelOf(current));

        // Drop-arrow drawn as an SVG label — the native SWT.ARROW|DOWN
        // Button renders inconsistently across GTK / win32 (a tiny pixel
        // glyph on some Linux themes), so we paint the caret ourselves.
        // No press-state animation in the combo (the popup itself is the
        // visual feedback) — pass equal sizes to disable the swap. #808080
        dropArrow = new IconStepLabel(this, "/icons/caret-down.svg",
                13, 13, new RGB(0x80, 0x80, 0x80));
        GridData arrowGd = new GridData(SWT.FILL, SWT.FILL, false, true);
        arrowGd.widthHint = 18;
        dropArrow.setLayoutData(arrowGd);

        // Make the whole closed display behave like a click target — any
        // of (icon / text / background / caret) toggles the popup.  A
        // second click on the combo body now closes the popup cleanly
        // instead of triggering the popup's Deactivate handler with
        // focus already lost to the previously-focused application.
        Listener togglePopup = e -> togglePopup();
        addListener     (SWT.MouseDown, togglePopup);
        iconLabel.addListener(SWT.MouseDown, togglePopup);
        textLabel.addListener(SWT.MouseDown, togglePopup);
        dropArrow.addListener(SWT.MouseDown, togglePopup);
    }

    private void togglePopup() {
        if (popup != null && !popup.isDisposed()) {
            popup.close();
            popup = null;
            return;
        }
        openPopup();
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
        int w = SignalFormIcon.SIZE + 4 + maxText + 4 + 18 + 6;
        int h = Math.max(SignalFormIcon.SIZE + 4, 24);
        return new Point(wHint != SWT.DEFAULT ? wHint : w,
                         hHint != SWT.DEFAULT ? hHint : h);
    }

    public GenSignalForm getSelectedForm() { return current; }

    public void setSelectedForm(GenSignalForm f) {
        if (f == null || f == current) return;
        current = f;
        iconLabel.setImage(SignalFormIcon.instance().get(getDisplay(), current));
        textLabel.setText(labelOf(current));
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

    private void openPopup() {
        final Display d = getDisplay();
        // SWT.BORDER draws a 1 px frame around the popup so the
        // dropdown reads as a distinct surface from whatever is
        // underneath; without it the table edges blur into the host
        // application on Windows.
        popup = new Shell(getShell(), SWT.NO_TRIM | SWT.ON_TOP | SWT.BORDER);
        final Shell popupRef = popup;
        popup.setLayout(new GridLayout(1, false));
        ((GridLayout) popup.getLayout()).marginWidth = 0;
        ((GridLayout) popup.getLayout()).marginHeight = 0;

        Table table = new Table(popup, SWT.SINGLE | SWT.FULL_SELECTION | SWT.V_SCROLL);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        TableColumn col = new TableColumn(table, SWT.NONE);
        GenSignalForm[] forms = LABELS.keySet().toArray(new GenSignalForm[0]);
        int selectedIdx = 0;
        for (int i = 0; i < forms.length; i++) {
            TableItem ti = new TableItem(table, SWT.NONE);
            ti.setImage(SignalFormIcon.instance().getForDropdown(d, forms[i]));
            ti.setText(labelOf(forms[i]));
            if (forms[i] == current) selectedIdx = i;
        }
        table.select(selectedIdx);

        // Size the popup to the closed display's width, height to fit all
        // rows up to a sensible cap (above which the Table's native vertical
        // scrollbar takes over).
        int rowH = table.getItemHeight();
        int rows = Math.min(forms.length, 12);
        int popupW = getSize().x;
        int popupH = rowH * rows + 6;
        Point screen = toDisplay(0, getSize().y);
        popup.setBounds(screen.x, screen.y, popupW, popupH);
        // Stretch the single column to fill the table (minus scrollbar +
        // border) so each row's selection background and image extend
        // across the full popup width.
        int colW = popupW - 2 - table.getVerticalBar().getSize().x;
        if (colW < 1) colW = popupW;
        col.setWidth(colW);

        table.addListener(SWT.MouseUp, e -> {
            int sel = table.getSelectionIndex();
            if (sel >= 0 && sel < forms.length) {
                setSelectedForm(forms[sel]);
                popupRef.close();
            }
        });
        table.addListener(SWT.DefaultSelection, e -> {
            int sel = table.getSelectionIndex();
            if (sel >= 0 && sel < forms.length) {
                setSelectedForm(forms[sel]);
                popupRef.close();
            }
        });

        // Outside-click detection.  We avoid the obvious choice of
        // closing on Shell.Deactivate because on Windows a SWT.ON_TOP
        // shell may never receive stable activation when opened from a
        // click — focus can pingpong back to the previously-active
        // application — and the resulting immediate Deactivate would
        // close the popup before the user ever sees it.  A display
        // filter on SWT.MouseDown works regardless of activation state:
        // we just check whether the click landed inside the popup, the
        // combo body, or somewhere else, and close in the last case.
        final Listener outsideClick = e -> {
            if (popupRef.isDisposed()) return;
            if (!(e.widget instanceof Control)) return;
            Control c = (Control) e.widget;
            // Inside the popup → keep open (Table own MouseUp closes it
            // after a selection).
            Composite p = c instanceof Composite ? (Composite) c : c.getParent();
            while (p != null) {
                if (p == popupRef) return;
                p = p.getParent();
            }
            // Inside the combo body → togglePopup() handles it; ignore
            // here so we don't close-then-reopen on a single click.
            Composite owner = c instanceof Composite ? (Composite) c : c.getParent();
            while (owner != null) {
                if (owner == SignalFormCombo.this) return;
                owner = owner.getParent();
            }
            popupRef.close();
        };
        d.addFilter(SWT.MouseDown, outsideClick);

        // Escape closes the popup without changing the selection.
        Listener escapeFilter = e -> {
            if (popupRef.isDisposed()) return;
            if (e.keyCode == SWT.ESC) popupRef.close();
        };
        d.addFilter(SWT.KeyDown, escapeFilter);

        // Tear down: remove the display filters and clear the tracked
        // reference so a subsequent click on the combo body opens a
        // fresh popup instead of trying to .close() a disposed shell.
        popupRef.addDisposeListener(e -> {
            d.removeFilter(SWT.MouseDown, outsideClick);
            d.removeFilter(SWT.KeyDown,   escapeFilter);
            if (popup == popupRef) popup = null;
        });

        popup.setVisible(true);
        table.setFocus();
    }

    public static String labelOf(GenSignalForm f) {
        return LABELS.getOrDefault(f, f.name());
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
