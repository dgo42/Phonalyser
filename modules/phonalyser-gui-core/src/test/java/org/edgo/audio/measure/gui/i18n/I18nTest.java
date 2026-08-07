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

package org.edgo.audio.measure.gui.i18n;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for {@link I18n}.  The contract: when a key is missing, the key
 * itself is returned (rather than throwing) so the GUI doesn't crash on
 * a stale property file.  When args are supplied, MessageFormat
 * placeholders ({@code {0}}, {@code {1,number,#.##}}) substitute.
 */
class I18nTest {

    @Test
    void missingKey_returnsKeyItself() {
        String missing = "test.never.added.to.any.bundle";
        assertEquals(missing, I18n.t(missing));
    }

    @Test
    void missingKey_returnsKey_evenWithArgs() {
        // Args are formatting hints; they shouldn't change the
        // missing-key behaviour.  The args are simply dropped.
        String missing = "another.missing.key.with.placeholders";
        assertEquals(missing, I18n.t(missing, 42, "foo"));
    }

    @Test
    void existingKey_returnsTranslation() {
        // Asserted against the module's OWN test bundle (src/test/resources/i18n),
        // not the shipped translations: a fixture the tests own cannot be changed
        // out from under them by someone editing a real translation.
        String value = I18n.t("test.existing.key");
        assertNotNull(value);
        assertEquals("resolved", value);
    }
}
