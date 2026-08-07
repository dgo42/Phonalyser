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

package org.edgo.audio.measure.gui.sound;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.edgo.audio.measure.enums.DeviceFailureReason;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every {@link DeviceFailureReason} must have a sentence to be rendered as.
 *
 * <p>The enum is the operator's whole answer to "why would the device not
 * open" - playing or recording - and a value whose key is missing renders as
 * the raw key ({@code I18n.t} falls back to it), so the bench would read
 * "Could not open the output device: device.error.reason.notAnswering".
 * A new enum value added without its 32 translations is exactly the mistake
 * this catches, one build after it is made.
 */
class DeviceFailureReasonKeysTest {

    private static final String DEFAULT_BUNDLE = "/i18n/messages.properties";
    /** The {@code {0}} of these two is a reason, so they must still HAVE a
     *  {@code {0}} - the round that made them parameterless is the bug this
     *  guards against repeating. */
    private static final String BUILD_FAILED_KEY = "generator.error.buildFailed";
    private static final String OPEN_FAILED_KEY = "generator.error.openDeviceFailed";
    /** The remote twin: a bench that refused says why too, and its key is its
     *  own - {@code net.servers.error.title} is a dialog title elsewhere. */
    private static final String REMOTE_REFUSED_KEY = "generator.error.remoteRefused";
    private static final String PLACEHOLDER = "{0}";

    @Test
    void everyReasonHasATranslatedSentence() throws Exception {
        Properties bundle = defaultBundle();

        for (DeviceFailureReason reason : DeviceFailureReason.values()) {
            String key = reason.i18nKey();
            assertNotNull(key, reason + " must carry an i18n key");
            String text = bundle.getProperty(key);
            assertNotNull(text, reason + " has no entry for " + key + " in the default "
                    + "bundle - it would render to the operator as the raw key");
            assertFalse(text.isBlank(), key + " must not be empty");
            assertFalse(text.contains(PLACEHOLDER), key + " is rendered with no "
                    + "arguments (I18n.t skips MessageFormat then), so a placeholder "
                    + "in it would reach the operator verbatim");
        }
    }

    /** The two messages the reason is rendered INTO keep their parameter. */
    @Test
    void theMessagesThatCarryAReasonStillTakeOne() throws Exception {
        Properties bundle = defaultBundle();

        for (String key : new String[] {BUILD_FAILED_KEY, OPEN_FAILED_KEY, REMOTE_REFUSED_KEY}) {
            String text = bundle.getProperty(key);
            assertNotNull(text, key + " must exist");
            assertTrue(text.contains(PLACEHOLDER), key + " must keep its {0}: it is "
                    + "where the localized reason goes, and without it the operator is "
                    + "told a device failed but never why");
        }
    }

    /** An unknown name - a reason from a newer peer over the net bridge - must
     *  degrade to UNKNOWN rather than throw. */
    @Test
    void anUnknownReasonNameReadsAsUnknown() {
        assertEquals(DeviceFailureReason.UNKNOWN,
                DeviceFailureReason.fromName("SOMETHING_A_LATER_BUILD_INVENTED"));
        assertEquals(DeviceFailureReason.UNKNOWN, DeviceFailureReason.fromName(null));
        assertEquals(DeviceFailureReason.UNKNOWN, DeviceFailureReason.fromName(""));
        assertEquals(DeviceFailureReason.DEVICE_NOT_ANSWERING,
                DeviceFailureReason.fromName("DEVICE_NOT_ANSWERING"));
    }

    private Properties defaultBundle() throws Exception {
        Properties bundle = new Properties();
        try (InputStream in = getClass().getResourceAsStream(DEFAULT_BUNDLE)) {
            assertNotNull(in, DEFAULT_BUNDLE + " must ship on the class path");
            bundle.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return bundle;
    }
}
