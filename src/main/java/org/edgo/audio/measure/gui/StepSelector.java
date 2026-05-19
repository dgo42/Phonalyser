package org.edgo.audio.measure.gui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * A small composite that lets the user step through a fixed list of pre-formatted
 * strings (e.g. {@code "10 mV/div", "20 mV/div", ...}) using up/down arrow
 * buttons.  Acts like a {@code Spinner} but over arbitrary text values instead
 * of integers, which {@code Spinner} can't render.
 *
 * <p>Listeners registered via {@link #addSelectionListener(Listener)} receive an
 * {@code SWT.Selection} event after each successful step (no event when the
 * user tries to step past either end).  The selected entry is exposed via
 * {@link #getSelectedIndex()} / {@link #getSelectedValue()}.
 */
public final class StepSelector extends Composite {

    /** Parses both user-typed input and individual list-value strings into a
     *  canonical scalar (e.g. volts for a V/div selector, seconds for t/div).
     *  Used so a typed value like "{@code 5m}" can be matched to the nearest
     *  entry in a pre-formatted list like "{@code 5 mV/div}".  Return
     *  {@code null} for unparseable input. */
    @FunctionalInterface
    public interface Parser    { Double parse(String text); }
    /** Renders a free-form value into the canonical display string for
     *  the field; used by the value-mode constructor. */
    @FunctionalInterface
    public interface Formatter { String format(double value); }

    private final Text       display;
    private final Canvas     upArrow;
    private final Canvas     downArrow;
    private final String[]   values;
    private final Parser     parser;
    private final Formatter  formatter;
    /** Cached parsed value of each list entry, or {@code null} for entries
     *  the parser couldn't read (those are skipped when snapping). */
    private final Double[]   parsedValues;
    /** Sorted-ascending step-target list for value mode; {@code null} in
     *  the older index modes. */
    private final double[]   stepTargets;
    private final boolean    valueMode;
    private final List<Listener> selectionListeners = new ArrayList<>();
    private final int        preferredHeight;
    private int              index;
    /** Current free-form value (value mode only).  In index mode this
     *  field is unused. */
    private double           value = Double.NaN;

    public StepSelector(Composite parent, String[] values, int initialIndex, int textWidthHint) {
        this(parent, values, initialIndex, textWidthHint, null);
    }

    /**
     * Editable variant — when {@code parser} is non-{@code null} the text
     * field accepts free-text entry.  On commit (Enter / focus-out) the
     * input is parsed and the field snaps to the nearest list entry by
     * linear distance.  An unparseable input reverts to the current entry.
     */
    public StepSelector(Composite parent, String[] values, int initialIndex, int textWidthHint,
                        Parser parser) {
        super(parent, SWT.BORDER);
        this.values = values.clone();
        this.parser = parser;
        this.formatter = null;
        this.stepTargets = null;
        this.valueMode = false;
        this.parsedValues = new Double[values.length];
        if (parser != null) {
            for (int i = 0; i < values.length; i++) {
                parsedValues[i] = parser.parse(values[i]);
            }
        }
        this.index  = Math.max(0, Math.min(values.length - 1, initialIndex));

        // Probe a throwaway READ_ONLY Combo to discover the system's natural
        // Combo height, then make this widget the same height so it lines up
        // visually with any neighbouring Combo on the same toolbar row.
        Combo probe = new Combo(this, SWT.READ_ONLY);
        this.preferredHeight = probe.computeSize(SWT.DEFAULT, SWT.DEFAULT).y;
        probe.dispose();

        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        gl.horizontalSpacing = 0;
        gl.verticalSpacing = 0;
        setLayout(gl);

        int displayStyle = SWT.SINGLE | SWT.RIGHT;
        if (parser == null) displayStyle |= SWT.READ_ONLY;
        display = new Text(this, displayStyle);
        GridData td = new GridData(SWT.FILL, SWT.FILL, true, true, 1, 2);
        td.widthHint = textWidthHint;
        display.setLayoutData(td);
        if (parser != null) {
            // Commit on Enter / focus-out: parse the text, find the nearest
            // list entry by linear distance, snap there.  Unparseable input
            // reverts to the current value's pre-formatted label.
            display.addListener(SWT.DefaultSelection, e -> commitText());
            display.addListener(SWT.FocusOut,         e -> commitText());
        }

        upArrow   = makeStepArrow(true,  () -> step(+1));
        downArrow = makeStepArrow(false, () -> step(-1));

        // Mouse wheel cycles through the value list.  Attach to each child
        // widget but NOT to the parent Composite — SWT bubbles mouse-wheel
        // events from child to parent on Windows, so a single notch over
        // the text field would otherwise fire both the child handler and
        // the parent handler, advancing by two list positions per notch
        // (the 1 → 5 → 20 skipping the user noticed).
        Listener wheel = e -> {
            if      (e.count > 0) step(+1);
            else if (e.count < 0) step(-1);
        };
        display.addListener(SWT.MouseWheel, wheel);
        upArrow.addListener(SWT.MouseWheel, wheel);
        downArrow.addListener(SWT.MouseWheel, wheel);

        refresh();
    }

    /**
     * Value-mode constructor — the field holds an arbitrary double instead
     * of an index into a fixed list.  {@code stepTargets} drives arrow /
     * wheel: the next-larger target above the current value moves up, the
     * next-smaller below moves down — typed values are preserved exactly,
     * not snapped, so e.g. "45 mV/div" stays at 45 mV/div until the user
     * deliberately steps.
     *
     * @param stepTargets ascending list of "nice" values the arrow buttons
     *                    and wheel snap to; ignored by manual text entry
     * @param initialValue starting value (any double, not constrained to
     *                    {@code stepTargets})
     * @param parser     parses user-typed input into a double (volts /
     *                   seconds / …); must return non-{@code null} for
     *                   accepted input
     * @param formatter  formats the current double for display in the
     *                   text field (e.g. "45 mV/div")
     */
    public StepSelector(Composite parent, double[] stepTargets, double initialValue,
                        int textWidthHint, Parser parser, Formatter formatter) {
        super(parent, SWT.BORDER);
        this.values = new String[0];
        this.parser = parser;
        this.formatter = formatter;
        this.stepTargets = stepTargets.clone();
        this.valueMode = true;
        this.parsedValues = new Double[0];
        this.index = 0;
        this.value = initialValue;

        Combo probe = new Combo(this, SWT.READ_ONLY);
        this.preferredHeight = probe.computeSize(SWT.DEFAULT, SWT.DEFAULT).y;
        probe.dispose();

        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        gl.horizontalSpacing = 0;
        gl.verticalSpacing = 0;
        setLayout(gl);

        display = new Text(this, SWT.SINGLE | SWT.RIGHT);
        GridData td = new GridData(SWT.FILL, SWT.FILL, true, true, 1, 2);
        td.widthHint = textWidthHint;
        display.setLayoutData(td);
        display.addListener(SWT.DefaultSelection, e -> commitText());
        display.addListener(SWT.FocusOut,         e -> commitText());

        upArrow   = makeStepArrow(true,  () -> step(+1));
        downArrow = makeStepArrow(false, () -> step(-1));

        // Same single-listener attachment policy as the index-mode ctor:
        // only on children, not on `this`, to avoid bubble-double-step.
        Listener wheel = e -> {
            if      (e.count > 0) step(+1);
            else if (e.count < 0) step(-1);
        };
        display.addListener(SWT.MouseWheel, wheel);
        upArrow.addListener(SWT.MouseWheel, wheel);
        downArrow.addListener(SWT.MouseWheel, wheel);

        refresh();
    }

    /** Propagate tooltip to inner children — the Text and arrow Canvases
     *  cover the Composite, so hover events never reach this widget's own
     *  background. */
    @Override
    public void setToolTipText(String text) {
        super.setToolTipText(text);
        display.setToolTipText(text);
        upArrow.setToolTipText(text);
        downArrow.setToolTipText(text);
    }

    @Override
    public Point computeSize(int wHint, int hHint, boolean changed) {
        Point natural = super.computeSize(wHint, hHint, changed);
        return new Point(natural.x, hHint != SWT.DEFAULT ? hHint : preferredHeight);
    }

    public void addSelectionListener(Listener listener) {
        selectionListeners.add(listener);
    }

    public int getSelectedIndex() {
        return index;
    }

    public String getSelectedValue() {
        return values[index];
    }

    public void setSelectedIndex(int newIndex) {
        if (newIndex < 0 || newIndex >= values.length || newIndex == index) {
            return;
        }
        index = newIndex;
        refresh();
        fire();
    }

    /** Parses the text field with {@link #parser}.  Value mode preserves
     *  the parsed value verbatim (no snap to step targets).  Index mode
     *  snaps to the closest list entry by linear distance.  An unparseable
     *  input reverts to the current entry's canonical label. */
    private void commitText() {
        if (parser == null) return;
        Double parsed = parser.parse(display.getText());
        if (parsed == null || parsed.isNaN() || parsed.isInfinite()) {
            refresh();   // revert
            return;
        }
        if (valueMode) {
            double prev = value;
            value = parsed;
            refresh();
            if (Double.compare(prev, value) != 0) fire();
            return;
        }
        double target = parsed;
        int bestIdx = -1;
        double bestDist = Double.POSITIVE_INFINITY;
        for (int i = 0; i < parsedValues.length; i++) {
            Double v = parsedValues[i];
            if (v == null) continue;
            double d = Math.abs(v - target);
            if (d < bestDist) { bestDist = d; bestIdx = i; }
        }
        if (bestIdx < 0) { refresh(); return; }
        if (bestIdx != index) {
            index = bestIdx;
            refresh();
            fire();
        } else {
            refresh();   // restore canonical formatting after edits
        }
    }

    private void step(int delta) {
        if (valueMode) {
            // Arrows / wheel in value mode: find the next step target
            // strictly above (or below) the current value, regardless of
            // where the user typed.
            double next = value;
            if (delta > 0) {
                for (double t : stepTargets) {
                    if (t > value) { next = t; break; }
                }
            } else {
                for (int i = stepTargets.length - 1; i >= 0; i--) {
                    if (stepTargets[i] < value) { next = stepTargets[i]; break; }
                }
            }
            if (Double.compare(next, value) == 0) return;
            value = next;
            refresh();
            fire();
            return;
        }
        int next = index + delta;
        if (next < 0 || next >= values.length) return;
        index = next;
        refresh();
        fire();
    }

    private void refresh() {
        if (valueMode) {
            String text = formatter != null ? formatter.format(value) : Double.toString(value);
            display.setText(text);
        } else {
            display.setText(values[index]);
        }
    }

    private void fire() {
        Event e = new Event();
        e.widget = this;
        if (valueMode) {
            e.data = Double.valueOf(value);
        } else {
            e.index = index;
        }
        for (Listener l : selectionListeners) {
            l.handleEvent(e);
        }
    }

    /** Returns the current free-form value (value-mode only) or
     *  {@code NaN} for index-mode selectors. */
    public double getValue() { return value; }

    /** Sets the current free-form value (value-mode only).  No-op in
     *  index mode and when {@code v} matches the current value. */
    public void setValue(double v) {
        if (!valueMode) return;
        if (Double.compare(value, v) == 0) return;
        value = v;
        refresh();
        fire();
    }

    /** Builds a borderless click-able sort-up / sort-down arrow that
     *  sits flush on the parent's background.  Replaces the native
     *  {@code SWT.ARROW | UP/DOWN} button (rounded chrome on GTK,
     *  inconsistent across themes).  The icon grows from 6 → 10 px while
     *  pressed for click feedback. */
    private Canvas makeStepArrow(boolean up, Runnable onPress) {
        // Width-driven sizing: 10 px wide normal, 8 px wide on press.
        Canvas l = IconStepLabel.create(this,
                up ? "/icons/sort-up.svg" : "/icons/sort-down.svg",
                10, 8, new RGB(0x80, 0x80, 0x80));
        GridData gd = new GridData(SWT.FILL, SWT.FILL, false, true);
        gd.widthHint  = 16;
        gd.heightHint = 12;
        l.setLayoutData(gd);
        l.addListener(SWT.MouseDown, e -> { if (e.button == 1) onPress.run(); });
        return l;
    }

    @Override
    protected void checkSubclass() {
        // SWT forbids subclassing of most widgets by default — opt back in.
    }
}
