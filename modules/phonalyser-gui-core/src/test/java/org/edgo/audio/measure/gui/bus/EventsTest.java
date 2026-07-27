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

package org.edgo.audio.measure.gui.bus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke tests for {@link Events}.  Confirms the name-builder helper
 * format and that pane IDs are distinct (a routing bug here would have
 * every pane responding to every other pane's title click).
 */
class EventsTest {

    @Test
    void paneTitleClick_usesPrefixThenId() {
        // Subscribers route by ID; the literal format is part of the
        // contract.  Any change here would silently break subscribers
        // that still listen on the old name.
        assertEquals("paneTitle.click.1", Events.paneTitleClick(1));
        assertEquals("paneTitle.click.42", Events.paneTitleClick(42));
        assertTrue(Events.paneTitleClick(0).startsWith(Events.PANE_TITLE_CLICK_PREFIX));
    }

    @Test
    void paneIds_areDistinct() {
        // Three pane IDs share the same event-name prefix; if any two
        // collide, both panes fire on every click.
        assertNotEquals(Events.PANE_ID_GENERATOR, Events.PANE_ID_SCOPE);
        assertNotEquals(Events.PANE_ID_GENERATOR, Events.PANE_ID_FFT);
        assertNotEquals(Events.PANE_ID_SCOPE,     Events.PANE_ID_FFT);
    }

    @Test
    void eventConstants_areAllDistinct() {
        // Cross-event collision would cause one event's subscribers to
        // fire on another event's publish.  Walk the well-known set
        // and assert no two strings collide.
        String[] all = {
                Events.FFT_LENGTH_CHANGED,
                Events.CAPTURE_ACQUIRE,
                Events.CAPTURE_RELEASE,
                Events.GENERATOR_RUNNING,
                Events.FFT_RANGE_CHANGED,
                Events.FFT_RECORDING_AUTO_STOPPED,
                Events.FILE_PLAY_STOPPED,
                Events.SCOPE_AUTO_SETUP,
        };
        for (int i = 0; i < all.length; i++) {
            for (int j = i + 1; j < all.length; j++) {
                assertNotEquals(all[i], all[j],
                        "event constants must be distinct: " + all[i] + " == " + all[j]);
            }
        }
    }
}
