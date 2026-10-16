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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTError;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.edgo.audio.measure.bind.Property;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The language bindings of {@link Bindings}: a bound text is set at bind time
 * and again on every language switch, through the key form and the supplier
 * form alike; nothing is set once the owner widget is disposed; bound combo
 * items follow the language with the selection index kept and no
 * {@link SWT#Selection} raised, so a value property bound to the combo is not
 * touched; a binding whose owner is disposed while a notification is already
 * running is not called by that notification.
 *
 * <p>The widgets run on a real display on the test thread; a host without a
 * display skips the cases.  The second language is the module's private-use
 * "qaa" test bundle, which overrides one key of the root fixture.
 */
class BindingsTest {

    private static final String EXISTING_KEY = "test.existing.key";
    private static final String ROOT_VALUE   = "resolved";
    private static final Locale OTHER        = Locale.forLanguageTag("qaa");
    private static final String OTHER_VALUE  = "resolved-qaa";
    /** An untranslated combo item - stays as it is across a switch. */
    private static final String PLAIN_ITEM   = "plain";
    private static final String SUFFIX       = " (1)";

    private Display display;
    private Shell   shell;
    private Locale  savedLocale;

    /** Creates the display and a shell; skips the case on a host without a
     *  display. */
    @BeforeEach
    void shellCreated() {
        savedLocale = I18n.getLocale();
        try {
            display = new Display();
        } catch (SWTError | UnsatisfiedLinkError e) {
            Assumptions.assumeTrue(false, "SWT display unavailable: " + e.getMessage());
        }
        shell = new Shell(display);
    }

    @AfterEach
    void shellDisposedAndLocaleRestored() {
        if (shell != null && !shell.isDisposed()) shell.dispose();
        I18n.setLocale(savedLocale);
        if (display != null && !display.isDisposed()) display.dispose();
    }

    @Test
    void aBoundTextFollowsALanguageSwitch() {
        Label byKey = new Label(shell, SWT.NONE);
        Label bySupplier = new Label(shell, SWT.NONE);
        Bindings.text(byKey, EXISTING_KEY, byKey::setText);
        Bindings.text(bySupplier, () -> I18n.t(EXISTING_KEY) + SUFFIX, bySupplier::setToolTipText);
        assertEquals(ROOT_VALUE, byKey.getText(), "set at bind time");
        assertEquals(ROOT_VALUE + SUFFIX, bySupplier.getToolTipText(), "set at bind time");

        I18n.setLocale(OTHER);

        assertEquals(OTHER_VALUE, byKey.getText());
        assertEquals(OTHER_VALUE + SUFFIX, bySupplier.getToolTipText());
    }

    @Test
    void nothingIsSetAfterTheOwnerIsDisposed() {
        Label label = new Label(shell, SWT.NONE);
        List<String> shown = new ArrayList<>();
        Bindings.text(label, EXISTING_KEY, shown::add);
        label.dispose();

        I18n.setLocale(OTHER);

        assertEquals(List.of(ROOT_VALUE), shown, "only the bind-time text");
    }

    @Test
    void boundItemsKeepTheSelectionAndRaiseNoSelection() {
        Combo combo = new Combo(shell, SWT.READ_ONLY);
        Bindings.items(combo, () -> new String[] { PLAIN_ITEM, I18n.t(EXISTING_KEY) });
        combo.select(1);
        AtomicInteger selections = new AtomicInteger();
        combo.addListener(SWT.Selection, e -> selections.incrementAndGet());

        I18n.setLocale(OTHER);

        assertEquals(PLAIN_ITEM, combo.getItem(0), "an untranslated item stays");
        assertEquals(OTHER_VALUE, combo.getItem(1));
        assertEquals(1, combo.getSelectionIndex(), "the selection index is kept");
        assertEquals(OTHER_VALUE, combo.getText(), "the selected item shows the new language");
        assertEquals(0, selections.get(), "no Selection event");
    }

    @Test
    void aBindingWhoseOwnerIsDisposedDuringANotificationIsNotCalled() {
        Label disposer = new Label(shell, SWT.NONE);
        Label owner = new Label(shell, SWT.NONE);
        Property<Integer> property = new Property<>(0);
        AtomicInteger calls = new AtomicInteger();
        // Registered first, so it runs first and disposes the second owner
        // while the property is still notifying its listener snapshot.
        Bindings.onChange(disposer, property, v -> owner.dispose());
        Bindings.onChange(owner, property, v -> calls.incrementAndGet());

        property.set(1);

        assertEquals(0, calls.get(), "its owner was disposed before its turn");
    }
}
