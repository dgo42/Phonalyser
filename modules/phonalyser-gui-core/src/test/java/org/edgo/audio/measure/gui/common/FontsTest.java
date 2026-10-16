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

package org.edgo.audio.measure.gui.common;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.eclipse.swt.SWTError;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.widgets.Display;
import org.edgo.audio.measure.preferences.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * {@link Fonts#generationProperty()}: a font-spec change bumps it exactly once,
 * and only after the cache was cleared - a listener that fetches the font gets
 * the new one, not the cached old one.  This ordering is what lets a view
 * re-font itself in place.
 *
 * <p>The fonts are created on a real display on the test thread; a host
 * without a display skips the case.  The preference is restored afterwards.
 */
class FontsTest {

    private static final String SPEC_A = "Courier New|11|bold";
    private static final String SPEC_B = "Courier New|12|normal";

    private Display display;
    private String  savedSpec;

    @BeforeEach
    void displayCreated() {
        savedSpec = Preferences.instance().getUiFontNormal();
        try {
            display = new Display();
        } catch (SWTError | UnsatisfiedLinkError e) {
            Assumptions.assumeTrue(false, "SWT display unavailable: " + e.getMessage());
        }
    }

    @AfterEach
    void specRestoredAndDisplayDisposed() {
        Preferences.instance().setUiFontNormal(savedSpec);
        if (display != null && !display.isDisposed()) display.dispose();
    }

    @Test
    void aSpecChangeIsAnnouncedOnceWithTheNewFontAlreadyServed() {
        Fonts fonts = Fonts.instance();
        Font before = fonts.normal(display);
        int generation = fonts.generationProperty().get();
        List<Font> fetched = new ArrayList<>();
        Consumer<Integer> listener = g -> fetched.add(fonts.normal(display));
        fonts.generationProperty().addListener(listener);
        try {
            Preferences.instance().setUiFontNormal(SPEC_A.equals(savedSpec) ? SPEC_B : SPEC_A);
        } finally {
            fonts.generationProperty().removeListener(listener);
        }

        assertEquals(generation + 1, fonts.generationProperty().get(), "bumped once");
        assertEquals(1, fetched.size(), "one notification");
        assertNotSame(before, fetched.get(0), "the listener is served the new font");
    }
}
