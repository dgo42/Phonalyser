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

import java.nio.file.Paths;
import java.util.Locale;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.eclipse.swt.widgets.Display;
import org.edgo.audio.measure.common.AppPaths;
import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.gui.automation.AutomationRunner;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;


/**
 * SWT entry point for the interactive measurement GUI.  Construction of the
 * actual window (menu bar, panes, toggle buttons, icons) lives in
 * {@link MainWindow}; this class only owns the {@link Display} lifecycle and
 * the SWT event loop.
 */
public final class GuiMain {

    /** CLI switch selecting a {@code gui.automation} script to run
     *  against the freshly opened window — either a class name already on
     *  the classpath ({@code --automation=<fully.qualified.ClassName>})
     *  or a {@code .java} source file compiled on the fly
     *  ({@code --automation=<path/Script.java>}). */
    private static final String AUTOMATION_ARG_PREFIX = "--automation=";

    private GuiMain() {}

    public static void main(String[] args) {
        // Relocate the log file to the per-user writable data dir — a packaged
        // macOS .app (or a Windows Program Files install) is read-only, so the
        // default 'logs/' next to the executable can't be written and the
        // failure is itself un loggable.  This MUST run before the first logger
        // boots log4j (which resolves the RollingFile path): GuiMain has no
        // class-load @Log4j2 logger and AppPaths uses a lazy logger, so neither
        // boots log4j before this line sets the property.
        System.setProperty("app.log.dir", AppPaths.instance().getLogsDir().toString());

        // First log4j touch — boots the config with app.log.dir already set.
        // GUI has no terminal, so detach the Console appender (the File
        // appender stays); the CLI Main loads log4j2.xml unmodified.
        LoggerContext logCtx = (LoggerContext) LogManager.getContext(false);
        logCtx.getConfiguration().getRootLogger().removeAppender("Console");
        logCtx.updateLoggers();
        Logger log = LogManager.getLogger(GuiMain.class);

        // Route any worker-thread death through log4j.  Without this, an
        // uncaught exception on a background thread (FFT analyser, scope
        // measurement, capture consumer) only prints to stderr — which a
        // windowed launch discards — so the view silently freezes with no
        // trace in logs/phonalyser.log.  Now the stack lands in the file.
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
                log.error("Uncaught exception on thread '{}': {}", t.getName(), e.toString(), e));

        // Apply the persisted UI language BEFORE the SWT shell is built —
        // every widget reads its labels via I18n.t() at construction time,
        // and ResourceBundle resolves them against the default Locale.
        Preferences prefs = Preferences.instance();
        String langTag = prefs.getUiLanguage();
        if (langTag != null && !langTag.isEmpty()) {
            I18n.setLocale(Locale.forLanguageTag(langTag));
        }

        // Seed the editable help copy now (i18n already seeds on first use) so
        // translators find <dataDir>/help populated without having to open the
        // Help viewer first.  Only the packaged app sets help.dir (=$APPDIR/help);
        // dev runs use the classpath, so there's nothing to seed.
        String helpBundle = System.getProperty("help.dir");
        if (helpBundle != null) {
            AppPaths paths = AppPaths.instance();
            paths.seedDirIfEmpty(paths.helpDir(), Paths.get(helpBundle));
        }

        // Synchronise the AudioBackend singleton with the YAML-persisted
        // backend choice before any device-list / capture-open path runs.
        // Without this, the first capture attempt after launch uses the
        // default (WASAPI) backend even if WDM-KS was saved — the device
        // and the active backend disagree and the open fails with a
        // "sample rate not supported" error.  Opening the Preferences dialog
        // happened to fix it as a side effect because the dialog calls
        // setActive() during init/cancel-restore.
        // Sanitise the saved choice: a backend saved on another OS (e.g. a
        // Windows-built preferences file opened on macOS) won't be available
        // here — fall back to the OS-native default (WASAPI on Windows,
        // CoreAudio on macOS, JavaSound on Linux) and rewrite the prefs so the
        // next launch starts clean.  NB: a hardcoded JAVASOUND fallback is
        // wrong on macOS, where JavaSound is unavailable (CoreAudio replaces it).
        AudioBackendType saved = prefs.getBackend();
        if (!saved.isAvailable()) {
            AudioBackendType fallback = AudioBackendType.fromOs();
            log.warn("Saved backend {} is not available on this OS; falling back to {}", saved, fallback);
            saved = fallback;
            prefs.setBackend(saved);
            prefs.save();
        }
        AudioBackend.instance().setActive(saved);

        Display display = new Display();
        boolean automation = false;
        for (String arg : args) {
            if (arg.startsWith(AUTOMATION_ARG_PREFIX)) { automation = true; break; }
        }
        // Branded splash while the window is built.  Skipped for automation
        // runs so it never sits in front of a screenshot capture.
        StartupSplash splash = automation ? null : new StartupSplash(display);
        if (splash != null) splash.open();
        // Language / font changes rebuild the window CONTENT in place
        // (MainWindow.rebuildContent) — the shell lives for the whole
        // session, so no recreate loop is needed.
        MainWindow window = new MainWindow(display);
        window.open();
        if (splash != null) splash.close();
        for (String arg : args) {
            if (arg.startsWith(AUTOMATION_ARG_PREFIX)) {
                String scriptClass = arg.substring(AUTOMATION_ARG_PREFIX.length()).trim();
                if (!scriptClass.isEmpty()) {
                    new AutomationRunner(display, window, scriptClass).start();
                }
                break;
            }
        }
        // Realtime render loop.  Drain every pending event each pass, then paint
        // the active tab's live views (scope + FFT) in ONE frame — so neither
        // view depends on its own timer / async cadence and the two can't starve
        // each other (the FFT's single coalesced result hand-off otherwise sat a
        // beat behind the scope's repaint, hiding the first average and further
        // updates until Record was toggled).  While a live view is updating, a
        // no-op one-shot timer paces the next frame; when nothing is live no
        // timer is armed and the loop blocks in sleep() until a real event, so an
        // idle app stays cool.
        final int RENDER_FRAME_MS = 16;
        while (!window.isDisposed()) {
            while (display.readAndDispatch()) {
                if (window.isDisposed()) break;
            }
            if (window.isDisposed()) break;
            boolean active = window.renderRealtimeFrame();
            if (window.isDisposed()) break;
            if (active) display.timerExec(RENDER_FRAME_MS, () -> { /* no-op: only wakes sleep() to drive the next frame */ });
            display.sleep();
        }
        display.dispose();
    }
}
