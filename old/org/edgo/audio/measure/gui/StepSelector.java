package org.edgo.audio.measure.gui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
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

    private final Text       display;
    private final String[]   values;
    private final List<Listener> selectionListeners = new ArrayList<>();
    private final int        preferredHeight;
    private int              index;

    public StepSelector(Composite parent, String[] values, int initialIndex, int textWidthHint) {
        super(parent, SWT.BORDER);
        this.values = values.clone();
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

        display = new Text(this, SWT.READ_ONLY | SWT.SINGLE | SWT.RIGHT);
        GridData td = new GridData(SWT.FILL, SWT.FILL, true, true, 1, 2);
        td.widthHint = textWidthHint;
        display.setLayoutData(td);

        Button up = new Button(this, SWT.ARROW | SWT.UP);
        GridData ud = new GridData(SWT.FILL, SWT.FILL, false, true);
        ud.widthHint = 18;
        up.setLayoutData(ud);
        up.addListener(SWT.Selection, e -> step(+1));

        Button down = new Button(this, SWT.ARROW | SWT.DOWN);
        GridData dd = new GridData(SWT.FILL, SWT.FILL, false, true);
        dd.widthHint = 18;
        down.setLayoutData(dd);
        down.addListener(SWT.Selection, e -> step(-1));

        // Mouse wheel cycles through the value list.  Attach to every child
        // widget too so the wheel works whether the pointer is over the text
        // field or either arrow button.
        Listener wheel = e -> {
            if      (e.count > 0) step(+1);
            else if (e.count < 0) step(-1);
        };
        addListener(SWT.MouseWheel, wheel);
        display.addListener(SWT.MouseWheel, wheel);
        up.addListener(SWT.MouseWheel, wheel);
        down.addListener(SWT.MouseWheel, wheel);

        refresh();
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

    private void step(int delta) {
        int next = index + delta;
        if (next < 0 || next >= values.length) return;
        index = next;
        refresh();
        fire();
    }

    private void refresh() {
        display.setText(values[index]);
    }

    private void fire() {
        Event e = new Event();
        e.widget = this;
        e.index  = index;
        for (Listener l : selectionListeners) {
            l.handleEvent(e);
        }
    }

    @Override
    protected void checkSubclass() {
        // SWT forbids subclassing of most widgets by default — opt back in.
    }
}
