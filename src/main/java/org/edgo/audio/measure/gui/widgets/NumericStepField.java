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
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Text;
import org.edgo.audio.measure.gui.common.Icon;

/**
 * Editable numeric field with unit handling, stepping and clamping — the one
 * numeric input control of the application (the read-only {@code StepSelector}
 * it also replaces cycled fixed strings).  Free-text entry with a unit suffix,
 * mouse-wheel and arrow-button stepping, commit on Enter / focus-out with
 * revert on invalid input.
 *
 * <p>All behaviour lives in an embedded {@link NumericStepModel}; this class
 * is only the SWT shell.  Configuration is pure data via three constructors —
 * one per stepping policy (fixed increments, value-series jumps, the "careful
 * 10 %" percent walk) — never callbacks: see the model for the policies and
 * {@link UnitFamily} for unit parsing / display switching.
 *
 * <p>The selection listeners fire ONLY when the canonical value actually
 * changed — a bare focus-out, an invalid entry, or a saturated step stays
 * silent, so downstream work wired to value changes (e.g. the FFT averaging
 * restart on {@code GENERATOR_SIGNAL_CHANGED}) is never retriggered
 * spuriously.
 */
public final class NumericStepField extends Composite {

    private final NumericStepModel model;
    private final Text       field;
    private final Canvas     upBtn;
    private final Canvas     downBtn;
    private final int        preferredHeight;
    private final List<Listener> selectionListeners = new ArrayList<>();

    /** FIXED-policy field: wheel adds {@code wheelStep}, arrows add
     *  {@code arrowStep} (canonical units), exactly {@code decimals} decimal
     *  digits are displayed. */
    public NumericStepField(Composite parent, UnitFamily family,
                            double min, double max,
                            double wheelStep, double arrowStep, int decimals,
                            int textWidthHint) {
        this(parent, new NumericStepModel(family, min, max, wheelStep, arrowStep, decimals),
                textWidthHint);
    }

    /** LIST-policy field: wheel and arrows jump along {@code series}; manual
     *  entry between (or beyond) the list points is allowed. */
    public NumericStepField(Composite parent, UnitFamily family,
                            double min, double max,
                            double[] series, int maxDecimals,
                            int textWidthHint) {
        this(parent, new NumericStepModel(family, min, max, series, maxDecimals),
                textWidthHint);
    }

    /** PERCENT-policy field: the "careful 10 %" wheel and ±1
     *  displayed-unit arrows. */
    public NumericStepField(Composite parent, UnitFamily family,
                            double min, double max,
                            int maxDecimals,
                            int textWidthHint) {
        this(parent, new NumericStepModel(family, min, max, maxDecimals),
                textWidthHint);
    }

    private NumericStepField(Composite parent, NumericStepModel model, int textWidthHint) {
        super(parent, SWT.BORDER);
        this.model = model;

        // Match a system Combo's natural height so neighbours line up.
        Combo probe = new Combo(this, SWT.READ_ONLY);
        this.preferredHeight = probe.computeSize(SWT.DEFAULT, SWT.DEFAULT).y;
        probe.dispose();

        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        gl.horizontalSpacing = 0;
        gl.verticalSpacing = 0;
        setLayout(gl);

        field = new Text(this, SWT.SINGLE | SWT.RIGHT);
        GridData td = new GridData(SWT.FILL, SWT.FILL, true, true, 1, 2);
        td.widthHint = textWidthHint;
        field.setLayoutData(td);

        // Click-able SVG arrows replacing the GTK-inconsistent
        // SWT.ARROW|UP/DOWN buttons.  Borderless, parent background,
        // press grows 6 → 10 px via IconStepLabel (Canvas under the hood
        // so the icon centres horizontally — plain Label ignores SWT.CENTER
        // for images on GTK).
        upBtn = new IconStepLabel(this, Icon.UP_BIG, Icon.UP_SMALL);
        GridData ud = new GridData(SWT.FILL, SWT.FILL, false, true);
        ud.widthHint  = 16;
        // GTK won't grow the cell to fit the larger pressed icon —
        // heightHint must clear the 10 px pressed image or it gets
        // clipped to nothing on click.
        ud.heightHint = 12;
        upBtn.setLayoutData(ud);

        downBtn = new IconStepLabel(this, Icon.DOWN_BIG, Icon.DOWN_SMALL);
        GridData dd = new GridData(SWT.FILL, SWT.FILL, false, true);
        dd.widthHint  = 16;
        dd.heightHint = 12;
        downBtn.setLayoutData(dd);

        // Arrow buttons: press-and-hold auto-repeats.  300 ms initial
        // delay then 10 Hz so a quick click is one step but holding
        // sweeps through the range.  See {@link #attachAutoRepeat}.
        attachAutoRepeat(upBtn,   +1);
        attachAutoRepeat(downBtn, -1);

        // Mouse wheel → model wheel step.  Hook every child too so the
        // wheel works regardless of which sub-widget the pointer is over.
        // Single physical wheel notches can produce multiple SWT MouseWheel
        // events (one per widget along the bubble path: Text → Canvas →
        // Composite), so we dedup on {@code e.time} — every event from the
        // same notch shares a timestamp.  Without this the list stepper
        // (averages 2/4/8/16/32/∞) was advancing two slots per notch and
        // the user saw values being skipped.
        final long[] lastWheelTime = { Long.MIN_VALUE };
        Listener wheel = e -> {
            if (!field.isEnabled()) return;
            if (e.time == lastWheelTime[0]) { e.doit = false; return; }
            lastWheelTime[0] = e.time;
            if      (e.count > 0) stepWheel(+1);
            else if (e.count < 0) stepWheel(-1);
            e.doit = false;
        };
        addListener(SWT.MouseWheel, wheel);
        field.addListener(SWT.MouseWheel, wheel);
        upBtn.addListener(SWT.MouseWheel, wheel);
        downBtn.addListener(SWT.MouseWheel, wheel);

        // Strict typing: every keystroke must leave a valid PREFIX of a
        // number-with-unit (the model's lenient mid-edit grammar, so the
        // strict commit grammar and this filter can't drift apart).  Empty
        // is allowed mid-edit so the field can be cleared.
        field.addListener(SWT.Verify, e -> {
            String current = field.getText();
            String after   = current.substring(0, e.start) + e.text + current.substring(e.end);
            if (!after.isEmpty() && !model.acceptsPartial(after)) e.doit = false;
        });

        // Cursor Up / Down act as clicks on the step buttons — one arrow
        // step per press; the OS key auto-repeat provides the same
        // hold-to-sweep behaviour as the buttons' timer repeat.  Consumed
        // so the caret doesn't jump to the text ends.
        field.addListener(SWT.KeyDown, e -> {
            if      (e.keyCode == SWT.ARROW_UP)   { stepArrow(+1); e.doit = false; }
            else if (e.keyCode == SWT.ARROW_DOWN) { stepArrow(-1); e.doit = false; }
        });

        // Commit-on-Enter and commit-on-focus-lost: parse the text and
        // either accept (reformat to canonical form) or revert.
        field.addListener(SWT.DefaultSelection, e -> commitText());
        field.addListener(SWT.FocusOut,         e -> commitText());

        refresh();
    }

    @Override
    public Point computeSize(int wHint, int hHint, boolean changed) {
        Point natural = super.computeSize(wHint, hHint, changed);
        return new Point(natural.x, hHint != SWT.DEFAULT ? hHint : preferredHeight);
    }

    /** Enables / disables both the text field and the arrows together. */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        field.setEnabled(enabled);
        upBtn.setEnabled(enabled);
        downBtn.setEnabled(enabled);
    }

    /** Propagate tooltip to inner children — the Composite itself is fully
     *  covered by its Text / arrow Canvases so hover events never reach
     *  this widget's own background. */
    @Override
    public void setToolTipText(String text) {
        super.setToolTipText(text);
        field.setToolTipText(text);
        upBtn.setToolTipText(text);
        downBtn.setToolTipText(text);
    }

    public void addSelectionListener(Listener l) {
        selectionListeners.add(l);
    }

    public double getValue() {
        return model.getValue();
    }

    /** Sets the canonical value (clamped by the model) — the two-way binding
     *  entry point.  Fires only when the resulting value differs. */
    public void setValue(double v) {
        double before = model.getValue();
        model.setValue(v);
        afterMutation(before, model.isLogDisplay());
    }

    /** Advances one step in {@code direction} (+1 up, −1 down) from the current
     *  value — the programmatic equivalent of one mouse-wheel notch over the
     *  field, so callers that want to step the field (e.g. the scope's
     *  wheel-zoom) reuse the field's OWN list / increment navigation and
     *  clamping instead of recomputing the next value themselves.  Fires the
     *  listeners only when the value actually moved. */
    public void step(int direction) {
        double before = model.getValue();
        boolean logBefore = model.isLogDisplay();
        model.wheel(direction);
        afterMutation(before, logBefore);
    }

    /** Updates the lower bound; the re-clamped value is mirrored to the
     *  display and, when it moved, to the listeners (so a bound preference
     *  follows a tightened range). */
    public void setMin(double min) {
        double before = model.getValue();
        model.setMin(min);
        afterMutation(before, model.isLogDisplay());
    }

    /** Updates the upper bound (e.g. Nyquist after a sample-rate change, the
     *  DAC full-scale after recalibration); see {@link #setMin}. */
    public void setMax(double max) {
        double before = model.getValue();
        model.setMax(max);
        afterMutation(before, model.isLogDisplay());
    }

    /** Replaces a LIST field's series (e.g. the sweep-points list whose head
     *  follows the sample rate); see {@link #setMin}. */
    public void setSeries(double[] series) {
        double before = model.getValue();
        model.setSeries(series);
        afterMutation(before, model.isLogDisplay());
    }

    /** Declares one value that renders and parses as {@code label} instead of
     *  a number (the sweep-points "Nyquist/2" entry). */
    public void setNamedValue(double value, String label) {
        model.setNamedValue(value, label);
        refresh();
    }

    /** True while the field displays in its log unit (dBV) — persisted per
     *  field so a restart restores the user's display choice. */
    public boolean isLogDisplay() {
        return model.isLogDisplay();
    }

    /** Programmatically restores ({@code true}) or clears ({@code false}) the
     *  dBV display — the seed path for a persisted choice; no listener
     *  fires. */
    public void setLogDisplay(boolean on) {
        model.setLogDisplay(on);
        refresh();
    }

    /** Force a reformat of the current value (e.g. after a locale change). */
    public void refresh() {
        field.setText(model.text());
    }

    /**
     * Wires {@code button} to step once on press and then repeat at 10 Hz
     * after a 300 ms hold delay.  Matches typical spinner / scrollbar
     * press-and-hold behaviour.  Repeat stops on mouse-up, on the cursor
     * leaving the button, or on the field being disabled / disposed.
     */
    private void attachAutoRepeat(Control button, int direction) {
        final int   initialDelayMs = 300;
        final int   repeatPeriodMs = 100;     // 10 Hz
        final boolean[] active = { false };

        Runnable repeat = new Runnable() {
            @Override
            public void run() {
                if (!active[0] || button.isDisposed() || !button.isEnabled()) {
                    active[0] = false;
                    return;
                }
                stepArrow(direction);
                getDisplay().timerExec(repeatPeriodMs, this);
            }
        };

        button.addListener(SWT.MouseDown, e -> {
            if (e.button != 1 || !button.isEnabled()) return;
            active[0] = true;
            stepArrow(direction);
            getDisplay().timerExec(initialDelayMs, repeat);
        });
        Listener stop = e -> {
            active[0] = false;
            getDisplay().timerExec(-1, repeat);
        };
        button.addListener(SWT.MouseUp,   stop);
        button.addListener(SWT.MouseExit, stop);
        button.addDisposeListener(e -> {
            active[0] = false;
            getDisplay().timerExec(-1, repeat);
        });
    }

    private void stepWheel(int direction) {
        double before = model.getValue();
        boolean logBefore = model.isLogDisplay();
        model.commit(field.getText());   // step from the ENTERED value, not the last committed
        model.wheel(direction);
        afterMutation(before, logBefore);
    }

    private void stepArrow(int direction) {
        double before = model.getValue();
        boolean logBefore = model.isLogDisplay();
        model.commit(field.getText());   // step from the ENTERED value, not the last committed
        model.arrow(direction);
        afterMutation(before, logBefore);
    }

    private void commitText() {
        double before = model.getValue();
        boolean logBefore = model.isLogDisplay();
        if (!model.commit(field.getText())) {
            // Reject: restore the last good value.
            refresh();
            return;
        }
        afterMutation(before, logBefore);
    }

    /** Refreshes the display and fires the listeners only when the canonical
     *  value — or the persisted-worthy dBV display choice — moved away from
     *  the snapshot; the single funnel every model mutation goes through, so
     *  the no-event-on-unchanged contract can't be missed at one of the call
     *  sites.  (Typing "0.5 V" over a dBV display changes the unit without
     *  the value; the pane's display-unit persistence still needs the
     *  event.) */
    private void afterMutation(double beforeValue, boolean logBefore) {
        refresh();
        if (Double.compare(beforeValue, model.getValue()) != 0
                || logBefore != model.isLogDisplay()) {
            fire();
        }
    }

    private void fire() {
        Event e = new Event();
        e.widget = this;
        for (Listener l : selectionListeners) l.handleEvent(e);
    }

    @Override
    protected void checkSubclass() {
        // SWT forbids subclassing of most widgets by default — opt back in.
    }
}
