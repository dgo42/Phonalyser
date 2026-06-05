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
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Text;
import org.edgo.audio.measure.gui.widgets.IconStepLabel;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.edgo.audio.measure.gui.interfaces.Formatter;
import org.edgo.audio.measure.gui.interfaces.Parser;
import org.edgo.audio.measure.gui.interfaces.Stepper;

/**
 * Editable numeric field used for the generator's frequency and amplitude
 * inputs.  Behaves like a {@code StepSelector} (arrow buttons + mouse wheel)
 * but accepts free-text entry instead of cycling through fixed strings.
 *
 * <p>The widget is value-only and unit-agnostic — parsing the user's text
 * into a {@code double} and formatting the canonical double back into a
 * display string are provided by the caller as {@link Parser} / {@link
 * Formatter} hooks.  This keeps frequency-specific concerns
 * ("100 Hz", "1 kHz") and amplitude-specific concerns
 * ("100 mV", "-6 dBV") out of the widget itself.
 *
 * <p>Mouse wheel and arrow buttons call {@link Stepper#step(double, int)}
 * with a sign of {@code +1} for "up" / {@code -1} for "down" and the
 * caller decides how to adjust the value — percentage step (frequency
 * scroll), unit step (frequency arrows), dB step (amplitude on a dB unit),
 * etc.
 */
public final class NumericStepField extends Composite {

    private static final Pattern NUMERIC_NO_UNIT = Pattern.compile(
            "[+-]?(\\d+([.,]\\d*)?|[.,]\\d*)");
    private static final Pattern NUMERIC_WITH_UNIT = Pattern.compile(
            "[+-]?(\\d+([.,]\\d*)?|[.,]\\d*)\\s*([a-zA-Zµμ°Ω%]*)?");

    private final Text       field;
    private final Canvas     upBtn;
    private final Canvas     downBtn;
    private final Parser     parser;
    private final Formatter  formatter;
    private final int        preferredHeight;
    private final List<Listener> selectionListeners = new ArrayList<>();
    private double           value;

    public NumericStepField(Composite parent,
                            double initialValue,
                            Parser parser,
                            Formatter formatter,
                            Stepper wheelStepper,
                            Stepper arrowStepper,
                            int textWidthHint) {
        super(parent, SWT.BORDER);
        this.parser        = parser;
        this.formatter     = formatter;
        this.value         = initialValue;

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
        upBtn = new IconStepLabel(this, "/icons/sort-up.svg",
                10, 8, new RGB(0x80, 0x80, 0x80));
        GridData ud = new GridData(SWT.FILL, SWT.FILL, false, true);
        ud.widthHint  = 16;
        // GTK won't grow the cell to fit the larger pressed icon —
        // heightHint must clear the 10 px pressed image or it gets
        // clipped to nothing on click.
        ud.heightHint = 12;
        upBtn.setLayoutData(ud);

        downBtn = new IconStepLabel(this, "/icons/sort-down.svg",
                10, 8, new RGB(0x80, 0x80, 0x80));
        GridData dd = new GridData(SWT.FILL, SWT.FILL, false, true);
        dd.widthHint  = 16;
        dd.heightHint = 12;
        downBtn.setLayoutData(dd);

        // Arrow buttons: press-and-hold auto-repeats.  300 ms initial
        // delay then 10 Hz so a quick click is one step but holding
        // sweeps through the range.  See {@link #attachAutoRepeat}.
        attachAutoRepeat(upBtn,   arrowStepper, +1);
        attachAutoRepeat(downBtn, arrowStepper, -1);

        // Mouse wheel → wheel step (caller picks ±5 %, etc.).  Hook every
        // child too so the wheel works regardless of which sub-widget the
        // pointer is over.  Single physical wheel notches can produce
        // multiple SWT MouseWheel events (one per widget along the bubble
        // path: Text → Canvas → Composite), so we dedup on {@code e.time}
        // — every event from the same notch shares a timestamp.  Without
        // this the cycle stepper (averages 2/4/8/16/32/∞) was advancing
        // two slots per notch and the user saw values being skipped.
        final long[] lastWheelTime = { Long.MIN_VALUE };
        Listener wheel = e -> {
            if (!field.isEnabled()) return;
            if (e.time == lastWheelTime[0]) { e.doit = false; return; }
            lastWheelTime[0] = e.time;
            if      (e.count > 0) applyStep(wheelStepper, +1);
            else if (e.count < 0) applyStep(wheelStepper, -1);
            e.doit = false;
        };
        addListener(SWT.MouseWheel, wheel);
        field.addListener(SWT.MouseWheel, wheel);
        upBtn.addListener(SWT.MouseWheel, wheel);
        downBtn.addListener(SWT.MouseWheel, wheel);

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

    /**
     * Attaches a {@link SWT#Verify} filter that rejects every keystroke
     * which would leave a string that is not a parseable decimal number
     * (optionally signed, optionally fractional, optionally with a unit
     * suffix when {@code allowUnit} is true — see {@link #UNIT_TAIL}).
     * Use for fields where the user might otherwise paste / type garbage
     * like "ass24" and have the parser silently drop it.
     */
    public void enableStrictNumericInput(boolean allowUnit) {
        field.addListener(SWT.Verify, e -> {
            String current = field.getText();
            String after   = current.substring(0, e.start) + e.text + current.substring(e.end);
            // Allow empty string mid-edit so the user can clear the field.
            if (after.isEmpty()) return;
            if (!matchesNumeric(after, allowUnit)) e.doit = false;
        });
    }

    private static boolean matchesNumeric(String s, boolean allowUnit) {
        return (allowUnit ? NUMERIC_WITH_UNIT : NUMERIC_NO_UNIT).matcher(s).matches();
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

    public double getValue() { return value; }

    public void setValue(double v) {
        if (Double.compare(value, v) == 0) return;
        value = v;
        refresh();
        fire();
    }

    /** Force a reformat of the current value (e.g. after the formatter changes). */
    public void refresh() {
        field.setText(formatter.format(value));
    }

    /**
     * Wires {@code button} to fire {@code stepper} once on press and
     * then repeat at 10 Hz after a 300 ms hold delay.  Matches typical
     * spinner / scrollbar press-and-hold behaviour.  Repeat stops on
     * mouse-up, on the cursor leaving the button, or on the field
     * being disabled / disposed.
     */
    private void attachAutoRepeat(Control button, Stepper stepper, int direction) {
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
                applyStep(stepper, direction);
                getDisplay().timerExec(repeatPeriodMs, this);
            }
        };

        button.addListener(SWT.MouseDown, e -> {
            if (e.button != 1 || !button.isEnabled()) return;
            active[0] = true;
            applyStep(stepper, direction);
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

    private void applyStep(Stepper stepper, int direction) {
        double next = stepper.step(value, direction);
        if (Double.compare(next, value) != 0) {
            value = next;
            refresh();
            fire();
        }
    }

    private void commitText() {
        Double parsed = parser.parse(field.getText());
        if (parsed == null || parsed.isNaN() || parsed.isInfinite()) {
            // Reject: restore the last good value.
            refresh();
            return;
        }
        // Unchanged value → nothing to commit and NO change event.  Commit runs
        // on every focus-out / Enter, so a bare focus loss with the text
        // untouched parses back to the same value; firing here would spuriously
        // trigger downstream work (e.g. the FFT averaging restart wired to
        // GENERATOR_SIGNAL_CHANGED).
        if (Double.compare(value, parsed) == 0) return;
        value = parsed;
        refresh();
        fire();
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
