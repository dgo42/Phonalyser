/*
 * Phonalyser - precision audio measurement workbench.
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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Widget;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.widgets.NumericStepField;
import org.edgo.audio.measure.bind.Property;

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
 * retained; a notification that finds the control disposed removes it as well,
 * so none reaches a disposed widget.  Loop-safety comes from {@link Property#set} (a no-op on an
 * unchanged value) plus the fact that an SWT programmatic {@code select} /
 * {@code setSelection} does not raise {@link SWT#Selection}.
 *
 * <p>UI-only, like the bus: a {@link Property} bound here is written on the
 * display thread and its listeners run inline on it - bindings synchronise
 * nothing.  Whatever must cross a thread does so in a controller, never in a
 * binding.
 *
 * <p>The {@code text} / {@code items} helpers are one-way: they follow
 * {@link I18n#localeProperty()}, so a long-lived widget's translated text
 * changes with the language in place, without rebuilding the widget.
 */
public final class Bindings {

    private Bindings() {}

    /** Two-way binds a {@code READ_ONLY} {@link Combo} whose items are in
     *  {@code values} order to an enum {@link Property}: the selection index is
     *  the value's position in {@code values}, so {@code values} may be a SUBSET
     *  of the enum (a combo offering three of five constants stays in sync).
     *  For a full {@code values()} array the position is the ordinal. */
    public static <T extends Enum<T>> void combo(Combo combo, Property<T> property, T[] values) {
        List<T> items = Arrays.asList(values);
        combo.select(items.indexOf(property.get()));
        combo.addListener(SWT.Selection, e -> {
            int i = combo.getSelectionIndex();
            if (i >= 0) {
                property.set(values[i]);
            }
        });
        onChange(combo, property, v -> {
            // A value the combo does not offer (-1) leaves the selection alone.
            int i = items.indexOf(v);
            if (i >= 0 && combo.getSelectionIndex() != i) {
                combo.select(i);
            }
        });
    }

    /** Two-way binds a checkbox {@link Button} to a {@code Boolean}
     *  {@link Property}. */
    public static void check(Button checkBox, Property<Boolean> property) {
        checkBox.setSelection(property.get());
        checkBox.addListener(SWT.Selection, e -> property.set(checkBox.getSelection()));
        onChange(checkBox, property, v -> {
            if (checkBox.getSelection() != v) {
                checkBox.setSelection(v);
            }
        });
    }

    /** Two-way binds a {@link NumericStepField} (free-text numeric input with
     *  arrow / wheel steppers) to a {@code Double} {@link Property}.  The field
     *  is value-only; its own parser / formatter / clamp behaviour is unchanged
     *  - this only mirrors the committed value to and from the property. */
    public static void stepField(NumericStepField field, Property<Double> property) {
        field.setValue(property.get());
        field.addSelectionListener(e -> property.set(field.getValue()));
        onChange(field, property, v -> {
            if (Double.compare(field.getValue(), v) != 0) {
                field.setValue(v);
            }
        });
    }

    /** Two-way binds a {@link NumericStepField} to an {@code Integer}
     *  {@link Property}.  The widget stores a {@code double}; the committed
     *  value is rounded to the nearest int on the way to the property and
     *  widened back on the way in. */
    public static void stepFieldInt(NumericStepField field, Property<Integer> property) {
        field.setValue(property.get());
        field.addSelectionListener(e -> property.set((int) Math.round(field.getValue())));
        onChange(field, property, v -> {
            if ((int) Math.round(field.getValue()) != v) {
                field.setValue(v);
            }
        });
    }

    /** Two-way binds a group of mutually-exclusive toggle / radio
     *  {@link Button}s to an enum-like {@link Property}, one button per value.
     *  Each button is selected exactly when the property equals its mapped
     *  value; clicking a button that reports {@code getSelection()} writes its
     *  value.  Mutual exclusion itself (deselecting the siblings) is the
     *  caller's concern - wire it once, e.g. via the pane's dependent-group
     *  helper - so this only carries the value, mirroring the {@code combo}
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
        Consumer<T> onChange = removedWhenDisposed(property,
                () -> options.keySet().stream().anyMatch(Widget::isDisposed), v -> {
                    for (Map.Entry<Button, T> entry : options.entrySet()) {
                        entry.getKey().setSelection(entry.getValue().equals(v));
                    }
                });
        property.addListener(onChange);
        for (Button button : options.keySet()) {
            button.addDisposeListener(e -> property.removeListener(onChange));
        }
    }

    /** Subscribes {@code listener} to {@code property} for the life of
     *  {@code owner} - it fires on every change and is removed when the owner
     *  widget is disposed, or by the first notification that finds the owner
     *  disposed (see {@link #removedWhenDisposed}).  For a widget that reacts to
     *  a parameter it does not itself edit (a view repaint, a tab-tile
     *  refresh). */
    public static <T> void onChange(Widget owner, Property<T> property, Consumer<T> listener) { // static-ok: existing all-static binding namespace
        Consumer<T> guarded = removedWhenDisposed(property, owner::isDisposed, listener);
        property.addListener(guarded);
        owner.addDisposeListener(e -> property.removeListener(guarded));
    }

    /** Wraps {@code listener} so that a notification finding {@code disposed}
     *  true removes it from {@code property} instead of running it.  The owner's
     *  dispose listener removes it too; the guard covers a notification already
     *  in flight when the owner is disposed - {@link Property#set} notifies over
     *  a snapshot of its listener list, so a listener removed during the
     *  notification is still called once. */
    private static <T> Consumer<T> removedWhenDisposed(Property<T> property, BooleanSupplier disposed,
                                                      Consumer<T> listener) {
        return new Consumer<>() {
            @Override
            public void accept(T value) {
                if (disposed.getAsBoolean()) {
                    property.removeListener(this);
                    return;
                }
                listener.accept(value);
            }
        };
    }

    /** Shows the translation of {@code key} through {@code setter}, now and on
     *  every language switch, for the life of {@code owner}. */
    public static void text(Widget owner, String key, Consumer<String> setter) {
        text(owner, () -> I18n.t(key), setter);
    }

    /** As {@code text(owner, key, setter)}, for a text built from more than
     *  one key, from arguments or from state. */
    public static void text(Widget owner, Supplier<String> text, Consumer<String> setter) {
        setter.accept(text.get());
        onChange(owner, I18n.localeProperty(), l -> setter.accept(text.get()));
    }

    /** Translated combo items that follow the language; the selection
     *  index is kept, and a programmatic select raises no Selection, so a
     *  bound value property is not touched. */
    public static void items(Combo combo, Supplier<String[]> items) {
        combo.setItems(items.get());
        onChange(combo, I18n.localeProperty(), l -> {
            int sel = combo.getSelectionIndex();
            combo.setItems(items.get());
            combo.select(sel);
        });
    }

    /** Subscribes {@code listener} to the bus event {@code event} for the life
     *  of {@code owner} - unsubscribed when the owner widget is disposed - and
     *  marshals every delivery onto the owner's display thread: an event the
     *  audio layer publishes ({@code Events.ADC_CALIBRATION_CHANGED} and its
     *  DAC twin) arrives on whatever thread the change happened on.  A
     *  delivery that finds the owner disposed unsubscribes the listener and is
     *  dropped; one already marshalled is dropped when it finds the owner
     *  disposed on the display thread. */
    public static <T> void onEvent(Widget owner, String event, Consumer<T> listener) { // static-ok: existing all-static binding namespace
        MessageBus bus = MessageBus.instance();
        Consumer<T> onEvent = new Consumer<>() {
            @Override
            public void accept(T payload) {
                if (owner.isDisposed()) {
                    bus.unsubscribe(event, this);
                    return;
                }
                Display display = owner.getDisplay();
                if (display.getThread() == Thread.currentThread()) {
                    listener.accept(payload);
                    return;
                }
                display.asyncExec(() -> {
                    if (!owner.isDisposed()) listener.accept(payload);
                });
            }
        };
        bus.subscribe(event, onEvent);
        owner.addDisposeListener(e -> bus.unsubscribe(event, onEvent));
    }
}
