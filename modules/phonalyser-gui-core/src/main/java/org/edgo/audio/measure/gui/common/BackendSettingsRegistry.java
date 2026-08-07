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

import java.util.EnumMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Consumer;

import org.edgo.audio.measure.enums.AudioBackendType;

import lombok.extern.log4j.Log4j2;

/**
 * The one place that knows which backends ship a settings panel.  Indexes every
 * {@link BackendSettingsUi} the service loader can find, keyed by backend type.
 *
 * <p>This is deliberately the SINGLE source of truth for "does this backend have
 * settings?".  The old design asked the device manager, which meant a boolean on
 * the audio contract and a dialog reference in the backend; the two could
 * disagree, and a manager answering {@code true} with no UI registered produced a
 * button that opened nothing.  Asking the registry cannot desync: the answer is
 * simply whether a panel registered itself.
 */
@Log4j2
public final class BackendSettingsRegistry {

    private static volatile BackendSettingsRegistry instance;

    private final Map<AudioBackendType, BackendSettingsUi> panels =
            new EnumMap<>(AudioBackendType.class);

    private BackendSettingsRegistry() {
        for (BackendSettingsUi ui : ServiceLoader.load(BackendSettingsUi.class)) {
            BackendSettingsUi previous = panels.putIfAbsent(ui.backendType(), ui);
            if (previous != null) {
                log.warn("Backend settings UI for {}: duplicate {} ignored (kept {})",
                        ui.backendType(), ui.getClass().getName(), previous.getClass().getName());
            }
        }
        log.info("Backend settings panels registered: {}", panels.keySet());
        // Arm each backend's UI-layer wiring once, here, where the set is known.
        // A provider that throws must not take the whole registry (and with it the
        // Preferences dialog) down - log it and carry on without that backend.
        for (BackendSettingsUi ui : panels.values()) {
            try {
                ui.start();
            } catch (RuntimeException ex) {
                log.warn("Backend UI startup failed for {}: {}", ui.backendType(), ex.toString(), ex);
            }
        }
    }

    public static BackendSettingsRegistry instance() {
        BackendSettingsRegistry local = instance;
        if (local == null) {
            synchronized (BackendSettingsRegistry.class) {
                local = instance;
                if (local == null) {
                    local = new BackendSettingsRegistry();
                    instance = local;
                }
            }
        }
        return local;
    }

    /** The panel for {@code type}, or {@code null} when that backend ships none. */
    public BackendSettingsUi forBackend(AudioBackendType type) {
        return panels.get(type);
    }

    /** Starts a Preferences session for every registered panel (see
     *  {@link BackendSettingsUi#beginEdit()}) - the dialog calls this as it
     *  opens. */
    public void beginEdit() {
        each(BackendSettingsUi::beginEdit, "beginEdit");
    }

    /** Tells every registered panel that the dialog was closed with OK (see
     *  {@link BackendSettingsUi#commitEdit()}). */
    public void commitEdit() {
        each(BackendSettingsUi::commitEdit, "commitEdit");
    }

    /** One session hook over every panel, guarded like {@link BackendSettingsUi#start()}
     *  is: a panel that throws must not take the Preferences dialog - or the
     *  commit of every OTHER panel - down with it. */
    private void each(Consumer<BackendSettingsUi> hook, String what) {
        for (BackendSettingsUi ui : panels.values()) {
            try {
                hook.accept(ui);
            } catch (RuntimeException ex) {
                if (log.isWarnEnabled()) {
                    log.warn("Backend settings {} failed for {}: {}", what, ui.backendType(),
                            ex.toString(), ex);
                }
            }
        }
    }

    /** Whether {@code type} ships a settings panel - what the Preferences dialog
     *  asks before showing its settings button. */
    public boolean has(AudioBackendType type) {
        return panels.containsKey(type);
    }
}
