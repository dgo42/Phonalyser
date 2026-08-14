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

package org.edgo.audio.measure.gui;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.swt.widgets.Display;
import org.edgo.audio.measure.cli.util.CliAppPaths;
import org.edgo.audio.measure.common.AppPaths;
import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.gui.automation.AutomationRunner;
import org.edgo.audio.measure.gui.helpviewer.HelpViewer;
import org.edgo.audio.measure.gui.helpviewer.Versions;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.scope.gl.Glfw;
import org.edgo.audio.measure.preferences.BackendKey;
import org.edgo.audio.measure.preferences.BackendPrefs;
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
     *  against the freshly opened window - either a class name already on
     *  the classpath ({@code --automation=<fully.qualified.ClassName>})
     *  or a {@code .java} source file compiled on the fly
     *  ({@code --automation=<path/Script.java>}). */
    private static final String AUTOMATION_ARG_PREFIX = "--automation=";

    /** Process exit code for a scripted run that aborted or recorded a failed
     *  check - the only non-zero code this application produces. */
    private static final int AUTOMATION_FAILED_EXIT = 1;

    private GuiMain() {}

    public static void main(String[] args) {
        // Relocate the log file to the per-user writable data dir - a packaged
        // macOS .app (or a Windows Program Files install) is read-only, so the
        // default 'logs/' next to the executable can't be written and the
        // failure is itself un loggable.  This MUST run before the first logger
        // boots log4j (which resolves the RollingFile path): GuiMain has no
        // class-load @Log4j2 logger and AppPaths uses a lazy logger, so neither
        // boots log4j before this line sets the property.
        System.setProperty("app.log.dir", AppPaths.instance().getLogsDir().toString());

        // First log4j touch - boots the config with app.log.dir already set.
        Logger log = LogManager.getLogger(GuiMain.class);

        // Fat-jar runs: make the bundled csjsound WASAPI-exclusive JavaSound
        // provider loadable BEFORE anything triggers the JVM's first
        // System.loadLibrary (SWT, JNA) - java.library.path is snapshotted
        // exactly once, at that first load.  No-op on non-Windows and on
        // installed layouts (see CsjsoundNativePath).
        CliAppPaths.installCsjsoundLibrary();

        // Route any worker-thread death through log4j.  Without this, an
        // uncaught exception on a background thread (FFT analyser, scope
        // measurement, capture consumer) only prints to stderr - which a
        // windowed launch discards - so the view silently freezes with no
        // trace in logs/phonalyser.log.  Now the stack lands in the file.
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
                log.error("Uncaught exception on thread '{}': {}", t.getName(), e.toString(), e));

        // Bare platform JAR: stage the locale bundles packed inside the fat
        // JAR into <dataDir>/i18n BEFORE the first I18n touch - I18n resolves
        // its external dir once, at class load.  The installer sets i18n.dir
        // ($APPDIR/i18n, extracted from the installation package) and dev runs
        // have target/classes/i18n next to the classes; both skip here.
        AppPaths appPaths = AppPaths.instance();
        if (System.getProperty("i18n.dir") == null && appPaths.appAdjacentDir("i18n") == null) {
            appPaths.stageBundledTree("i18n/", appPaths.i18nDir(), Versions.appVersion());
        }

        // Apply the persisted UI language BEFORE the SWT shell is built -
        // every widget reads its labels via I18n.t() at construction time,
        // and ResourceBundle resolves them against the default Locale.
        Preferences prefs = Preferences.instance();
        String langTag = prefs.getUiLanguage();
        if (langTag != null && !langTag.isEmpty()) {
            I18n.setLocale(Locale.forLanguageTag(langTag));
        }

        // Seed the editable help copy now (i18n already seeds on first use) so
        // translators find <dataDir>/help populated without having to open the
        // Help viewer first.  The installed app sets help.dir (=$APPDIR/help,
        // extracted from the installation package) and seeds from there; a
        // bare platform JAR carries the help bundle inside the JAR instead and
        // stages it into <dataDir>/help once per app version.  Dev runs use
        // the classpath (target/classes/help) - both calls no-op there.
        String helpBundle = System.getProperty("help.dir");
        if (helpBundle != null) {
            AppPaths paths = AppPaths.instance();
            paths.seedDirIfEmpty(paths.helpDir(), Paths.get(helpBundle));
        } else {
            HelpViewer.instance().stageBundledHelp();
        }

        // Synchronise the AudioBackend singleton with the YAML-persisted
        // backend choice before any device-list / capture-open path runs.
        // Without this, the first capture attempt after launch uses the
        // default (WASAPI) backend even if WDM-KS was saved - the device
        // and the active backend disagree and the open fails with a
        // "sample rate not supported" error.  Opening the Preferences dialog
        // happened to fix it as a side effect because the dialog calls
        // setActive() during init/cancel-restore.
        // Sanitise the saved choice: a backend saved on another OS (e.g. a
        // Windows-built preferences file opened on macOS) won't be available
        // here - fall back to the OS-native default (WASAPI on Windows,
        // CoreAudio on macOS, JavaSound on Linux) and rewrite the prefs so the
        // next launch starts clean.  NB: a hardcoded JAVASOUND fallback is
        // wrong on macOS, where JavaSound is unavailable (CoreAudio replaces it).
        // The question is asked of the SELECTION, never of the carrier it is
        // reached through: a bench on a Phonalyser server rides on
        // AudioBackendType.NET, whose isAvailable() is false BY DESIGN (it owns
        // no local hardware), so judging a remote selection by its carrier would
        // declare every remote bench "not available on this OS" and overwrite the
        // saved server key with a plain local backend on the first restart after
        // choosing it.  A remote selection is therefore kept exactly as saved and
        // nothing is activated here - there is no local device to open, and the
        // bench becomes reachable only once its server is connected.
        BackendKey saved = prefs.getSelectedBackend();
        if (saved.remote()) {
            if (log.isInfoEnabled()) {
                log.info("Saved backend is the remote bench {}; kept as saved - it is "
                        + "reached by connecting to its server, not activated here", saved.key());
            }
        } else {
            AudioBackendType local = saved.type();
            // Both halves of availability: the OS policy the enum answers, and
            // the driver's own probe (QA40x: does libusb load) via the provider.
            if (!local.isAvailable() || !AudioBackend.instance().isAvailable(local)) {
                AudioBackendType fallback = AudioBackendType.fromOs();
                if (log.isWarnEnabled()) {
                    log.warn("Saved backend {} is not available here; falling back to {}",
                            local, fallback);
                }
                local = fallback;
                prefs.setBackend(local);
                prefs.save();
            }
            AudioBackend.instance().setActive(local);
        }

        // The SELECTED devices' calibration, into the runtime scalars, before any
        // lane can open.  The backend above is only half the saved selection: the
        // full-scale voltages that turn a commanded level into volts live on the
        // devices' cards, and until this ran nothing applied them at start-up -
        // the Preferences dialog's OK did (applyCommittedProfile), which is why
        // switching the backend away and back "fixed" a session that had been
        // driving the DAC at whatever scalar the defaults left behind.  A
        // preconfigured installation never touches that dialog.
        //
        // By NAME, which is what the saved selection holds and needs no
        // enumeration: the device lists are not scanned yet at this point, and the
        // name lookup is exactly what the dialog falls back to for a device its
        // own enumeration no longer offers.  A remote bench is skipped for the
        // same reason it is not activated above - its calibration lives on the
        // server and arrives with the session.
        if (!saved.remote()) {
            BackendPrefs selection = prefs.current();
            prefs.applyInputDeviceProfile(selection.getInputDeviceName());
            prefs.applyOutputDeviceProfile(selection.getOutputDeviceName());
        }

        // Bring every backend this build offers to a known, safe, idle state -
        // NOT just the active one.  A device can outlive the process that drove
        // it: a QA40x moved between two machines without losing USB power arrives
        // holding the other machine's input sensitivity, and nothing corrects it
        // until something happens to acquire and release it.
        // The analyzer in that case is attached while ANOTHER backend is
        // selected, so sweeping only the active backend would walk straight past
        // the one device that needed it.
        //
        // Started HERE, before the Display: it runs on its own thread and
        // overlaps the splash and the window build, so a backend that is slow or
        // wedged - precisely the hardware this exists for - cannot delay the
        // application appearing.
        AudioBackend.instance().setupInBackground();

        // On a Wayland session SWT's GLCanvas can't obtain a GL context (GLX is
        // X11-only), so the GPU scope is unavailable there.  When GPU acceleration
        // is enabled, force the GTK backend to X11 (XWayland) so the GL path works
        // out of the box.  GTK reads GDK_BACKEND from the C environment at init and
        // the JVM can't set its own env, so call libc setenv via JNA - BEFORE the
        // Display (the first GTK touch) is created below.  Gated to Linux + a
        // Wayland session + not already X11 + GPU enabled; harmless if GTK ignores
        // it (the scope just stays on CPU, as it would have anyway).
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!osName.contains("win") && !osName.contains("mac")
                && System.getenv("WAYLAND_DISPLAY") != null
                && !"x11".equals(System.getenv("GDK_BACKEND"))
                && Preferences.instance().isUseGpuAcceleration()) {
            try {
                Native.load("c", LibC.class).setenv("GDK_BACKEND", "x11", 1);
                log.info("Wayland + GPU enabled: set GDK_BACKEND=x11 for the GL scope.");
            } catch (RuntimeException e) {
                log.warn("Could not set GDK_BACKEND=x11; GPU scope stays on CPU under Wayland: {}",
                        e.toString());
            }
        }

        // A daemon-backed GTK input-method module (ibus / fcitx) whose daemon
        // is NOT running is dysfunctional - and is exactly the configuration
        // that hangs or segfaults GTK in-process (field report: only
        // GTK_IM_MODULE=xim helped).  Pre-flight the configured IM before the
        // Display (the first GTK touch): daemon missing -> force the legacy
        // X11 IM via libc setenv, which costs the user nothing (their IME
        // wasn't functional anyway).  A LIVE daemon is left untouched so
        // working CJK input keeps its IME; unset / xim / the built-in simple
        // context need nothing.
        String imModule = System.getenv("GTK_IM_MODULE");
        if (!osName.contains("win") && !osName.contains("mac")
                && ("ibus".equals(imModule) || "fcitx".equals(imModule)
                        || "fcitx5".equals(imModule))) {
            boolean alive = false;
            try (DirectoryStream<Path> procs =
                    Files.newDirectoryStream(Paths.get("/proc"), "[0-9]*")) {
                for (Path proc : procs) {
                    String comm;
                    try {
                        comm = Files.readString(proc.resolve("comm")).trim();
                    } catch (IOException processExitedMeanwhile) {
                        continue;
                    }
                    if ("ibus".equals(imModule) ? "ibus-daemon".equals(comm)
                                                : comm.startsWith("fcitx")) {
                        alive = true;
                        break;
                    }
                }
            } catch (IOException e) {
                // /proc unreadable - can't judge, so don't touch a possibly
                // working IM.
                alive = true;
            }
            if (alive && "ibus".equals(imModule)) {
                // ibus additionally needs its session bus socket; an empty /
                // missing socket dir means the running daemon is unreachable.
                String xdg = System.getenv("XDG_CONFIG_HOME");
                Path busDir = (xdg != null && !xdg.isEmpty())
                        ? Paths.get(xdg, "ibus", "bus")
                        : Paths.get(System.getProperty("user.home"), ".config", "ibus", "bus");
                try (DirectoryStream<Path> sockets = Files.newDirectoryStream(busDir)) {
                    alive = sockets.iterator().hasNext();
                } catch (IOException noBusDir) {
                    alive = false;
                }
            }
            if (!alive) {
                try {
                    Native.load("c", LibC.class).setenv("GTK_IM_MODULE", "xim", 1);
                    if (log.isInfoEnabled()) {
                        log.info("GTK_IM_MODULE={} has no running daemon - forced xim"
                                + " to avoid the GTK input-method crash.", imModule);
                    }
                } catch (RuntimeException e) {
                    if (log.isWarnEnabled()) {
                        log.warn("GTK_IM_MODULE={} daemon missing, but setenv failed: {}",
                                imModule, e.toString());
                    }
                }
            } else if (log.isInfoEnabled()) {
                log.info("GTK_IM_MODULE={} daemon is running - IM left untouched.", imModule);
            }
        }

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
        // (MainWindow.rebuildContent) - the shell lives for the whole
        // session, so no recreate loop is needed.
        MainWindow window = new MainWindow(display);
        window.open();
        if (splash != null) splash.close();
        // Kept (rather than fire-and-forget) so its verdict can become this
        // process's exit code below - that is what lets an integration test
        // tell a failed scripted run from a successful one.  Null for every
        // ordinary launch, which therefore still exits 0.
        AutomationRunner runner = null;
        for (String arg : args) {
            if (arg.startsWith(AUTOMATION_ARG_PREFIX)) {
                String scriptClass = arg.substring(AUTOMATION_ARG_PREFIX.length()).trim();
                if (!scriptClass.isEmpty()) {
                    runner = new AutomationRunner(display, window, scriptClass);
                    runner.start();
                }
                break;
            }
        }
        // Realtime render loop.  Drain every pending event each pass, then paint
        // the active tab's live views (scope + FFT) in ONE frame - so neither
        // view depends on its own timer / async cadence and the two can't starve
        // each other (the FFT's single coalesced result hand-off otherwise sat a
        // beat behind the scope's repaint, hiding the first average and further
        // updates until Record was toggled).  While a live view is updating, a
        // no-op one-shot timer paces the next frame; when nothing is live no
        // timer is armed and the loop blocks in sleep() until a real event, so an
        // idle app stays cool.
        // Self-re-arming heartbeat rather than a one-shot per iteration: a one-shot
        // timer is consumed (and then lost) when a nested OS modal loop runs - dragging
        // the floating measurement tool window is one - leaving the main loop blocked in
        // sleep() forever and the realtime view frozen.  A timer that re-arms ITSELF keeps
        // firing through the nested loop, so rendering continues during and after it.
        // When no view is live the chain lapses and the loop blocks in sleep(), so an idle
        // app stays cool.
        final int RENDER_FRAME_MS = 1;   // pace the next frame as soon as possible (render time dominates)
        final Runnable[] heartbeat = new Runnable[1];
        final boolean[]  beating   = { false };
        heartbeat[0] = () -> {
            beating[0] = false;
            if (window.isDisposed()) return;
            if (window.renderRealtimeFrame()) {
                beating[0] = true;
                display.timerExec(RENDER_FRAME_MS, heartbeat[0]);
            }
        };
        // macOS GPU scope: the floating GLFW child window's input callbacks fire only
        // during glfwPollEvents, which must run on this (main) thread - and we can't
        // block in Display.sleep() or that polling stops and the child goes
        // unresponsive while the scope is stopped.  So when GLFW is live we poll +
        // short-nap instead of sleeping.  On Windows / Linux (no GLFW) it stays a
        // blocking sleep, so an idle app draws no CPU.
        final Glfw glfw = Glfw.instance();
        final int GLFW_IDLE_NAP_MS = 2;
        while (!window.isDisposed()) {
            while (display.readAndDispatch()) {
                if (window.isDisposed()) break;
            }
            if (window.isDisposed()) break;
            glfw.poll();
            if (!beating[0] && window.renderRealtimeFrame()) {   // a view went live -> start beating
                beating[0] = true;
                display.timerExec(RENDER_FRAME_MS, heartbeat[0]);
            }
            if (glfw.isActive()) {
                try { Thread.sleep(GLFW_IDLE_NAP_MS); } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            } else {
                display.sleep();
            }
        }
        display.dispose();
        // Leave every backend this build offers in a known, safe, idle state -
        // the closing half of the setup sweep above, and for the same reason: the
        // next host to pick up that analyzer inherits whatever this one left.  It
        // covers the ACTIVE backend too (which is how a remote bench gives its
        // lanes and device locks back on the wire), so it replaces the
        // active-only teardown that used to stand here.  Must run HERE
        // explicitly: the halt()/TerminateProcess below skips every hook.
        try {
            AudioBackend.instance().teardown();
        } catch (Throwable ignored) {
            // Exit must never be blocked by audio teardown.
        }
        // Force an immediate process exit.  Letting main() return would run the
        // JVM's GRACEFUL shutdown, which executes every registered shutdown hook
        // to completion - including the native audio hooks (PortAudio
        // Pa_Terminate / WASAPI COM release) that can block for seconds tearing
        // down a stream held open continuously by e.g. a Tune-notch session.
        // The OS reclaims those audio/driver handles on process death anyway;
        // the only hook that carries data is the preferences flush.  So run that
        // one explicitly, then halt(0) - which skips all hooks and non-daemon
        // waits - so the process quits at once instead of waiting on driver
        // teardown.
        Preferences.instance().flush();
        // The scripted run's verdict, as the process exit code - 0 for every
        // ordinary launch (no runner) and for a script that completed with all
        // its checks green, 1 when the script aborted or recorded a red check.
        int exitCode = (runner != null && runner.hasFailed()) ? AUTOMATION_FAILED_EXIT : 0;
        // On Windows, skip the C-runtime exit teardown entirely.  halt(0) still
        // runs atexit / static destructors / DLL detach, which fires Chromium's
        // WindowImpl::UnregisterClassesAtExit (registered via base::AtExitManager
        // by the embedded Edge/WebView2 browser).  That unregister fails - a
        // WebView2 window still exists - and Chromium logs "Failed to unregister
        // class Chrome_WidgetWin_0. Error = 1411" to stderr.  TerminateProcess
        // ends the process without running any of that teardown, so the handler
        // never fires; the OS reclaims every handle regardless.  Falls back to
        // halt on non-Windows / if the native call is unavailable.
        if (osName.contains("win")) {
            try {
                Kernel32 k32 = Native.load("kernel32", Kernel32.class);
                k32.TerminateProcess(k32.GetCurrentProcess(), exitCode);
            } catch (Throwable ignored) {
                // fall through to halt(exitCode)
            }
        }
        Runtime.getRuntime().halt(exitCode);
    }

    /** Minimal libc binding (JNA) for {@code setenv}, used to force GDK_BACKEND=x11
     *  before GTK initialises (see {@link #main}).  Linux only. */
    public interface LibC extends Library {
        int setenv(String name, String value, int overwrite);
    }

    /** Minimal kernel32 binding (JNA) for {@code TerminateProcess} - ends the
     *  process without the C-runtime exit teardown that would otherwise fire
     *  Chromium's window-class unregister (see {@link #main}).  Windows only. */
    public interface Kernel32 extends Library {
        Pointer GetCurrentProcess();
        boolean TerminateProcess(Pointer hProcess, int uExitCode);
    }
}
