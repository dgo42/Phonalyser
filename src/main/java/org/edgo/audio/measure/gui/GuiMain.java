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

package org.edgo.audio.measure.gui;

import java.util.Locale;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.eclipse.swt.widgets.Display;
import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;

import lombok.extern.log4j.Log4j2;

/**
 * SWT entry point for the interactive measurement GUI.  Construction of the
 * actual window (menu bar, panes, toggle buttons, icons) lives in
 * {@link MainWindow}; this class only owns the {@link Display} lifecycle and
 * the SWT event loop.
 */
@Log4j2
public final class GuiMain {

    private GuiMain() {}

    public static void main(String[] args) {
        // GUI runs without a terminal: detach the Console appender from the
        // root logger so log4j2 writes only to logs/phonalyser.log.
        // The CLI Main still gets console output because it loads log4j2.xml
        // unmodified.
        LoggerContext logCtx = (LoggerContext) LogManager.getContext(false);
        LoggerConfig rootLogger = logCtx.getConfiguration().getRootLogger();
        rootLogger.removeAppender("Console");
        logCtx.updateLoggers();

        // Apply the persisted UI language BEFORE the SWT shell is built —
        // every widget reads its labels via I18n.t() at construction time,
        // and ResourceBundle resolves them against the default Locale.
        Preferences prefs = Preferences.instance();
        String langTag = prefs.getUiLanguage();
        if (langTag != null && !langTag.isEmpty()) {
            I18n.setLocale(Locale.forLanguageTag(langTag));
        }

        // Synchronise the AudioBackend singleton with the YAML-persisted
        // backend choice before any device-list / capture-open path runs.
        // Without this, the first capture attempt after launch uses the
        // default (WASAPI) backend even if WDM-KS was saved — the device
        // and the active backend disagree and the open fails with a
        // "sample rate not supported" error.  Opening the Preferences dialog
        // happened to fix it as a side effect because the dialog calls
        // setActive() during init/cancel-restore.
        // Sanitise the saved choice: if the user is launching a Windows-built
        // preferences file on Linux/macOS, WASAPI/WDM-KS won't work — fall
        // back to JAVASOUND (the only cross-platform backend) and rewrite
        // the prefs so the next launch starts clean.
        AudioBackendType saved = prefs.getBackend();
        if (!saved.isAvailable()) {
            log.warn("Saved backend {} is not available on this OS; falling back to JAVASOUND", saved);
            saved = AudioBackendType.JAVASOUND;
            prefs.setBackend(saved);
            prefs.save();
        }
        AudioBackend.instance().setActive(saved);

        Display display = new Display();
        boolean recreate;
        do {
            MainWindow window = new MainWindow(display);
            window.open();
            while (!window.isDisposed()) {
                if (!display.readAndDispatch()) {
                    display.sleep();
                }
            }
            recreate = window.isRecreateRequested();
        } while (recreate);
        display.dispose();
    }
}
