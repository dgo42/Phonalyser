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

package org.edgo.audio.measure.gui.bind;

import java.util.Map;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Widget;
import org.edgo.audio.measure.gui.generator.NumericStepField;
import org.edgo.audio.measure.bind.Property;
import org.edgo.audio.measure.gui.widgets.StepSelector;

/**
 * Two-way wiring between SWT controls and {@link Property} values.  Each helper
 * does three things so a control and the model stay in sync without either side
 * referencing the other:
 * <ol>
 *   <li>seeds the control from the property's current value,</li>
 *   <li>writes the property on user input,</li>
 *   <li>updates the control when the property changes from elsewhere
 *       (a preset load, another control on the same property).</li>
 * </ol>
 * The property listener is removed on control dispose, so no disposed widget is
 * retained.  Loop-safety comes from {@link Property#set} (a no-op on an
 * unchanged value) plus the fact that an SWT programmatic {@code select} /
 * {@code setSelection} does not raise {@link SWT#Selection}.
 */
public final class Bindings {

    private Bindings() {}

    /** Two-way binds a {@code READ_ONLY} {@link Combo} whose items are in
     *  {@code values} order to an enum {@link Property} (selection index =
     *  {@link Enum#ordinal()}). */
    public static <T extends Enum<T>> void combo(Combo combo, Property<T> property, T[] values) {
        combo.select(property.get().ordinal());
        combo.addListener(SWT.Selection, e -> {
            int i = combo.getSelectionIndex();
            if (i >= 0) {
                property.set(values[i]);
            }
        });
        Consumer<T> onChange = v -> {
            if (!combo.isDisposed() && combo.getSelectionIndex() != v.ordinal()) {
                combo.select(v.ordinal());
            }
        };
        property.addListener(onChange);
        combo.addDisposeListener(e -> property.removeListener(onChange));
    }

    /** Two-way binds a checkbox {@link Button} to a {@code Boolean}
     *  {@link Property}. */
    public static void check(Button checkBox, Property<Boolean> property) {
        checkBox.setSelection(property.get());
        checkBox.addListener(SWT.Selection, e -> property.set(checkBox.getSelection()));
        Consumer<Boolean> onChange = v -> {
            if (!checkBox.isDisposed() && checkBox.getSelection() != v) {
                checkBox.setSelection(v);
            }
        };
        property.addListener(onChange);
        checkBox.addDisposeListener(e -> property.removeListener(onChange));
    }

    /** Two-way binds a {@link NumericStepField} (free-text numeric input with
     *  arrow / wheel steppers) to a {@code Double} {@link Property}.  The field
     *  is value-only; its own parser / formatter / clamp behaviour is unchanged
     *  — this only mirrors the committed value to and from the property. */
    public static void stepField(NumericStepField field, Property<Double> property) {
        field.setValue(property.get());
        field.addSelectionListener(e -> property.set(field.getValue()));
        Consumer<Double> onChange = v -> {
            if (!field.isDisposed() && Double.compare(field.getValue(), v) != 0) {
                field.setValue(v);
            }
        };
        property.addListener(onChange);
        field.addDisposeListener(e -> property.removeListener(onChange));
    }

    /** Two-way binds a {@link NumericStepField} to an {@code Integer}
     *  {@link Property}.  The widget stores a {@code double}; the committed
     *  value is rounded to the nearest int on the way to the property and
     *  widened back on the way in. */
    public static void stepFieldInt(NumericStepField field, Property<Integer> property) {
        field.setValue(property.get());
        field.addSelectionListener(e -> property.set((int) Math.round(field.getValue())));
        Consumer<Integer> onChange = v -> {
            if (!field.isDisposed() && (int) Math.round(field.getValue()) != v) {
                field.setValue(v);
            }
        };
        property.addListener(onChange);
        field.addDisposeListener(e -> property.removeListener(onChange));
    }

    /** Two-way binds a value-mode {@link StepSelector} (free-form double with
     *  snap-to-targets arrows / wheel) to a {@code Double} {@link Property}.
     *  No-op for index-mode selectors, which carry no comparable double. */
    public static void stepSelector(StepSelector selector, Property<Double> property) {
        selector.setValue(property.get());
        selector.addSelectionListener(e -> property.set(selector.getValue()));
        Consumer<Double> onChange = v -> {
            if (!selector.isDisposed() && Double.compare(selector.getValue(), v) != 0) {
                selector.setValue(v);
            }
        };
        property.addListener(onChange);
        selector.addDisposeListener(e -> property.removeListener(onChange));
    }

    /** Two-way binds a group of mutually-exclusive toggle / radio
     *  {@link Button}s to an enum-like {@link Property}, one button per value.
     *  Each button is selected exactly when the property equals its mapped
     *  value; clicking a button that reports {@code getSelection()} writes its
     *  value.  Mutual exclusion itself (deselecting the siblings) is the
     *  caller's concern — wire it once, e.g. via the pane's dependent-group
     *  helper — so this only carries the value, mirroring the {@code combo}
     *  helper for a button group. */
    public static <T> void radio(Map<Button, T> options, Property<T> property) {
        for (Map.Entry<Button, T> entry : options.entrySet()) {
            Button button = entry.getKey();
            T value = entry.getValue();
            button.setSelection(property.get().equals(value));
            button.addListener(SWT.Selection, e -> {
                if (button.getSelection()) {
                    property.set(value);
                }
            });
        }
        Consumer<T> onChange = v -> {
            for (Map.Entry<Button, T> entry : options.entrySet()) {
                Button button = entry.getKey();
                if (!button.isDisposed()) {
                    button.setSelection(entry.getValue().equals(v));
                }
            }
        };
        property.addListener(onChange);
        for (Button button : options.keySet()) {
            button.addDisposeListener(e -> property.removeListener(onChange));
        }
    }

    /** Subscribes {@code listener} to {@code property} for the life of
     *  {@code owner} — it fires on every change and is removed when the owner
     *  widget is disposed.  For a widget that reacts to a parameter it does not
     *  itself edit (a view repaint, a tab-tile refresh). */
    public static <T> void onChange(Widget owner, Property<T> property, Consumer<T> listener) {
        property.addListener(listener);
        owner.addDisposeListener(e -> property.removeListener(listener));
    }
}
