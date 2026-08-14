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

package org.edgo.audio.measure.gui.backend.loopback;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.function.Consumer;

import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Shell;

import org.junit.jupiter.api.Test;

import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.bus.SampleRateChange;
import org.edgo.audio.measure.gui.common.BackendSettingsUi;
import org.edgo.audio.measure.preferences.BackendKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What this backend's settings service claims about itself - no widget and no
 * display anywhere in it, because none of the answers below needs one.
 *
 * <p>The service exists for {@link BackendSettingsUi#start()} alone: it arms the
 * format constraint, which has no other owner.  Registering it must therefore NOT
 * put a settings button on the Preferences dialog, and
 * {@link BackendSettingsUi#hasCustomPreferences()} is the only thing standing
 * between "the constraint is armed" and "a button that opens nothing".
 */
class LoopbackSettingsUiTest {

    /** The resolved card name the constraint echoes into every correction. */
    private static final String CARD = "Phonalyser Loopback";

    @Test
    void theServiceIsRegisteredForTheLoopbackBackend() {
        BackendSettingsUi found = null;
        for (BackendSettingsUi ui : ServiceLoader.load(BackendSettingsUi.class)) {
            if (ui.backendType() == AudioBackendType.LOOPBACK) {
                found = ui;
            }
        }
        assertNotNull(found, "no settings service registered for LOOPBACK - the format "
                + "constraint would never be armed");
        assertFalse(found.hasCustomPreferences(),
                "the registered service must not put a button on the dialog");
    }

    @Test
    void itHasNoCustomPreferences_soTheDialogOffersNoButton() {
        LoopbackSettingsUi ui = new LoopbackSettingsUi();
        assertFalse(ui.hasCustomPreferences(),
                "the loopback has nothing to configure beyond the rate and depth combos");
        // The unreachable side of that answer: there is no panel to hand out.
        assertNull(ui.getContent());
    }

    @Test
    void theSpiDefaultIsTrue_soAServiceWithAPanelSaysNothing() {
        assertTrue(new PanelService().hasCustomPreferences(),
                "a service that says nothing came for its panel");
    }

    /**
     * After {@code start()}, the constraint ANSWERS: a loopback rate change is
     * met with the correction for the other direction.  Asking
     * {@link LoopbackFormatConstraint#instance()} instead would prove nothing -
     * that call arms the subscriber itself, so the assertion would hold over an
     * empty {@code start()}.  Driving the bus is the only way to see the effect
     * the service exists for.
     *
     * <p>The recorded pair is normalised first the way
     * {@code LoopbackFormatConstraintTest} does it - a change on another backend
     * is the subscriber's reset primitive - because the singleton is
     * process-wide and another test may have left a pair behind.
     */
    @Test
    void afterStart_theFormatConstraintAnswers() {
        MessageBus bus = MessageBus.instance();
        List<SampleRateChange> corrections = new ArrayList<>();
        Consumer<SampleRateChange> captor = corrections::add;
        bus.subscribe(Events.PREFS_SAMPLE_RATE_SET, captor);
        try {
            new LoopbackSettingsUi().start();
            // Forget whatever pair the shared subscriber was holding.
            bus.publish(Events.PREFS_SAMPLE_RATE_CHANGED,
                    new SampleRateChange(true, 0, 0, BackendKey.of(AudioBackendType.WASAPI), CARD));
            corrections.clear();

            bus.publish(Events.PREFS_SAMPLE_RATE_CHANGED,
                    new SampleRateChange(true, 96_000, 16, BackendKey.of(AudioBackendType.LOOPBACK), CARD));

            assertEquals(1, corrections.size(),
                    "start() is the whole reason this service exists - with the constraint armed, "
                            + "a loopback rate change is answered");
            assertEquals(new SampleRateChange(false, 96_000, 16,
                            BackendKey.of(AudioBackendType.LOOPBACK), CARD), corrections.get(0),
                    "the one clock: the other direction takes the same rate and depth");
        } finally {
            bus.unsubscribe(Events.PREFS_SAMPLE_RATE_SET, captor);
        }
    }

    /** A service that implements only what the SPI requires - so the default
     *  {@code hasCustomPreferences()} is what answers for it. */
    private static final class PanelService implements BackendSettingsUi {
        @Override
        public AudioBackendType backendType() {
            return AudioBackendType.QA40X;
        }
        @Override
        public void open(Shell parent, BackendKey selection) {
            // a panel this test never opens
        }
        @Override
        public void showForCapture(Shell parent) {
            // a panel this test never opens
        }
        @Override
        public Control getContent() {
            return null;
        }
        @Override
        public void close() {
            // nothing was opened
        }
    }
}
