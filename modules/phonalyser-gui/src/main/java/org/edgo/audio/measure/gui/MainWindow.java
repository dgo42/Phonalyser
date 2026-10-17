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
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.edgo.audio.measure.gui.bind.Bindings;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.common.GuiUtil;
import org.edgo.audio.measure.gui.common.Icon;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.common.ShellIcons;
import org.edgo.audio.measure.gui.delay.MeasureDelayDialog;
import org.edgo.audio.measure.gui.freqresp.TuneNotchWizardDialog;
import org.edgo.audio.measure.gui.helpviewer.HelpIndexBuilder;
import org.edgo.audio.measure.gui.helpviewer.HelpUrls;
import org.edgo.audio.measure.gui.helpviewer.HelpViewer;
import org.edgo.audio.measure.gui.helpviewer.UpdateChecker;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.scope.gl.GpuSupport;
import org.edgo.audio.measure.gui.sound.SharedCapture;
import org.edgo.audio.measure.gui.tips.TipOfTheDayDialog;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.gui.preferences.PreferencesDialog;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Application's main window.  Owns the top menu bar (File / Language /
 * Tools / Help), the {@link Shell} itself, and the shell-level concerns
 * (icons, minimum / saved size, in-place language switch).  All
 * measurement panes and tab content live in {@link MainTab}; all
 * {@link Image} resources flow through {@link IconUtils}, whose cache is
 * registered against the shell so disposal is centralised.
 *
 * <p>Menu responsibilities:
 * <ul>
 *   <li><b>File</b> - application exit.</li>
 *   <li><b>Language</b> - runtime locale switch (do-not-translate sentinel,
 *       always rendered as "Language" so users who can't read the current
 *       UI language can still find this menu).</li>
 *   <li><b>Tools</b> - opens the {@code PreferencesDialog}.  On OK, a committed
 *       audio-config change stops live capture + generator playback before the
 *       switch and restarts them after (via
 *       {@link MainTab#beforeApplyBackendChanges()}) so device changes don't
 *       tear running streams.</li>
 *   <li><b>Help</b> - multi-chapter HTML help viewer (F1 / Ctrl+F1),
 *       startup-check toggles, GitHub issue reporting, About dialog.</li>
 * </ul>
 */
@Log4j2
public final class MainWindow {

    /** Minimum shell footprint - a standard 1024×720 window.  The
     *  content's computed natural layout is wider but shorter than this, so the
     *  minimum is clamped to let the window shrink to 1024 wide while keeping at
     *  least 720 tall (and never below the content's own natural height). */
    private static final int MIN_SHELL_WIDTH  = 1024;
    private static final int MIN_SHELL_HEIGHT = 720;

    private final Display display;
    private final Shell   shell;
    /** App-lifetime audio engines (generator / scope / FFT) - created once
     *  here and injected down into the panes; shut down on shell dispose. */
    @Getter
    private final UIEngines engines;
    /** Built once with the shell: a language, font or GPU change applies in
     *  place, so the content is never rebuilt. */
    @Getter
    private final MainTab mainTab;

    public MainWindow(Display display) {
        this.display = display;
        this.shell   = new Shell(display);
        // Published for GuiUtil.marshal callers with no shell of their own - a
        // worker deep below the panes has no widget to reach one through (and
        // Widget.getShell() may only be called on the display thread anyway);
        // this window is the process-wide owner.
        GuiUtil.setMainShell(shell);
        Bindings.text(shell, "app.title", shell::setText);
        shell.setLayout(new FillLayout());
        ShellIcons.apply(shell);

        // Hook the icon cache to this shell so every Image loaded via
        // IconUtils is disposed automatically on shell teardown.
        IconUtils.registerShell(shell);

        // Eager init of the SharedCapture singleton so its MessageBus
        // responder is registered BEFORE any pane fires a CAPTURE_ACQUIRE
        // request - otherwise the request returns null and the user
        // thinks the device failed to open.
        SharedCapture.instance();

        this.engines = new UIEngines();
        shell.addDisposeListener(e -> engines.shutdown());
        // Prime the device catalog off the UI thread - the server's boot order
        // (enumerate first, setup sweep after) on one background thread, so
        // the Preferences dialog opens onto filled combos instead of a driver
        // walk.  The backend setup sweep is started by the priming thread when
        // its enumeration is done (see DeviceCatalog.primeInBackground).
        engines.getDeviceCatalog().primeInBackground();

        buildMenuBar();

        this.mainTab = new MainTab(shell, engines);
        // applyMinimumShellSize MUST run before any saved collapse state is
        // restored: a collapsed pane sets SashForm weights to {N, 28}, and
        // SashForm.computeSize balloons the natural size to whatever total
        // honours that ratio.  See MainTab.applySavedLayoutState javadoc.
        applyMinimumShellSize();
        mainTab.applySavedLayoutState();
    }

    /**
     * Computes the smallest shell size that fits the panes' natural
     * layout and uses that as the shell's minimum size.  The initial
     * size is the saved window dimensions from {@link Preferences} (if
     * any, and at least the minimum), otherwise a sensible default
     * that's also at least the minimum.
     */
    private void applyMinimumShellSize() {
        Point natural = mainTab.computeNaturalShellSize();
        // Clamp the minimum toward a 1280×720 footprint: the content's natural
        // box is over-wide but short, so allow the window narrower than that
        // width yet never shorter than 720 (nor below the content's own height).
        int minW = Math.min(natural.x, MIN_SHELL_WIDTH);
        int minH = Math.max(natural.y, MIN_SHELL_HEIGHT);
        shell.setMinimumSize(minW, minH);

        Preferences prefs = Preferences.instance();
        int savedW = prefs.getWindowWidth();
        int savedH = prefs.getWindowHeight();
        // Restore the saved size, clamped to the minimum (NOT to the natural
        // width - else a saved 1280 would be forced back up to the natural box).
        int w = savedW > 0 ? Math.max(minW, savedW) : Math.max(MIN_SHELL_WIDTH, natural.x);
        int h = savedH > 0 ? Math.max(minH, savedH) : Math.max(800, natural.y);
        shell.setSize(w, h);
    }

    /** Shows the window and logs the start event.  Caller drives the SWT event loop. */
    public void open() {
        shell.open();
        log.info("GUI started.");
        // The SashForms allocate their first pane heights from weights applied
        // during construction - before the shell is realised at its on-screen
        // size - so on macOS a pane can open with its title bar clipped until the
        // first manual resize.  Defer one more deep layout (runs after the shell
        // has its real size) to settle them at startup, the same re-layout a
        // resize triggers.  Deliberate SAME-thread deferral - not a marshal, so
        // not GuiUtil.marshal (which runs in place on the UI thread).
        display.asyncExec(() -> {
            if (shell.isDisposed()) return;
            shell.layout(true, true);
            // On Linux the GL probe needs a realised window, so it was deferred
            // past construction (the scope built on the CPU path).  Now the shell
            // is shown, probe for real exactly once; the scope pane follows
            // GpuSupport.availableProperty and moves onto the GPU surface if the
            // GPU is available and enabled.  Skipping the probe until here is
            // what stops the X11 glXMakeCurrent abort at startup.  Windows and
            // macOS probed already while the pane was built.
            if (!GpuSupport.instance().isProbed()) {
                GpuSupport.instance().isAvailable(shell);
            }
        });
        // Fire the silent update check once the shell is up.  The
        // checker spawns its own daemon thread; doing the check from
        // open() (rather than during shell construction) means a network
        // hiccup can't delay window paint.
        Preferences prefs = Preferences.instance();
        if (prefs.isCheckForUpdatesOnStartup()) {
            UpdateChecker.checkOnStartup(shell, prefs.isIncludeBetaInUpdateChecks());
        }
        if (prefs.isShowTipsAtStartup()) {
            new TipOfTheDayDialog(shell).open();
        }
    }

    /** Stops the live streams before the Preferences dialog commits an audio
     *  change - invoked by its OK handler while the old backend is still active. */
    public void beforeApplyBackendChanges() {
        if (mainTab != null) mainTab.beforeApplyBackendChanges();
    }

    /** Restarts, on the newly-committed backend, the streams stopped by
     *  {@link #beforeApplyBackendChanges()} - invoked by the dialog's OK handler. */
    public void afterApplyBackendChanges() {
        if (mainTab != null) mainTab.afterApplyBackendChanges();
    }

    public boolean isDisposed() {
        return shell.isDisposed();
    }


    /** Loop-driven realtime repaint of the active tab's live views, called once
     *  per frame by the main event loop's render tick (see {@code GuiMain}).
     *  Returns {@code true} while a live view is updating so the loop maintains
     *  the realtime cadence; {@code false} when idle so the loop can truly sleep.
     *  {@code uiLoad} is the loop's measured busy fraction of the UI thread
     *  (0..1), handed down to the view that paces itself against it. */
    public boolean renderRealtimeFrame(double uiLoad) {
        return mainTab != null && mainTab.renderRealtimeFrame(uiLoad);
    }

    /** Closes the shell, ending the SWT event loop - how an automation
     *  run exits the application when its script completes. */
    public void close() {
        if (!shell.isDisposed()) shell.close();
    }

    /** Switches the UI language IN PLACE: the menu bar, the shell title and
     *  every bound text of the content follow the new locale through their
     *  bindings - nothing is disposed or rebuilt, so the panes, the tab
     *  controls and the running measurements carry on untouched.  Translated
     *  labels change the content's natural size, so the whole tree is
     *  re-laid out.  The minimum shell size is deliberately NOT re-fitted:
     *  on the live sash weights SashForm.computeSize inflates the natural
     *  size (see the constructor), which would grow the window.  The
     *  separate help window is its own Shell and is carried to the new
     *  language too (a no-op while help is closed). */
    public void applyLanguage(Locale locale) {
        I18n.setLocale(locale);
        shell.layout(true, true);
        HelpViewer.instance().refreshLanguage();
    }

    // -------------------------------------------------------------------------
    // Menu bar
    // -------------------------------------------------------------------------

    private void buildMenuBar() {
        Preferences prefs = Preferences.instance();
        Menu menuBar = new Menu(shell, SWT.BAR);
        shell.setMenuBar(menuBar);
        // macOS carries About + Settings in the application ("apple") menu, so
        // those entries move out of Tools / Help there (see wireMacSystemMenu).
        boolean mac = "cocoa".equals(SWT.getPlatform());

        // File -> Exit.  On macOS quitting is the application-menu "Quit
        // Phonalyser" item; since Exit is the only File entry, the whole File
        // menu is omitted there.
        if (!mac) {
            MenuItem fileCascade = new MenuItem(menuBar, SWT.CASCADE);
            Bindings.text(fileCascade, "menu.file", fileCascade::setText);
            Menu fileMenu = new Menu(shell, SWT.DROP_DOWN);
            fileCascade.setMenu(fileMenu);
            MenuItem exitItem = new MenuItem(fileMenu, SWT.PUSH);
            Bindings.text(exitItem, "menu.file.exit", exitItem::setText);
            exitItem.addListener(SWT.Selection, e -> shell.close());
        }

        // Top-level Language menu, sitting right after File.  Its label is
        // deliberately NOT translated - it stays "Language" in every
        // locale so a user who can't read the current language can still
        // find this menu and switch to their own.
        MenuItem languageCascade = new MenuItem(menuBar, SWT.CASCADE);
        // do-not-translate - always "Language"
        languageCascade.setText("Language");
        Menu languageMenu = new Menu(shell, SWT.DROP_DOWN);
        languageCascade.setMenu(languageMenu);
        for (String tag : discoverLanguageTags()) {
            addLanguageMenuItem(languageMenu, tag, displayLabel(tag));
        }

        // Tools menu.  "Tune notch" is present on every platform; Preferences
        // is only added on non-macOS - on macOS it is the application-menu
        // "Settings..." item (wired in wireMacSystemMenu).
        MenuItem toolsCascade = new MenuItem(menuBar, SWT.CASCADE);
        Bindings.text(toolsCascade, "menu.tools", toolsCascade::setText);
        Menu toolsMenu = new Menu(shell, SWT.DROP_DOWN);
        toolsCascade.setMenu(toolsMenu);
        MenuItem tuneNotchItem = new MenuItem(toolsMenu, SWT.PUSH);
        Bindings.text(tuneNotchItem, "menu.tools.tuneNotch", tuneNotchItem::setText);
        tuneNotchItem.setImage(IconUtils.icon(shell.getDisplay(), Icon.NOTCH_FILTER));
        tuneNotchItem.addListener(SWT.Selection, e -> openTuneNotchDialog());
        MenuItem measureDelayItem = new MenuItem(toolsMenu, SWT.PUSH);
        Bindings.text(measureDelayItem, "menu.tools.measureDelay", measureDelayItem::setText);
        measureDelayItem.setImage(IconUtils.icon(shell.getDisplay(), Icon.SIGNAL_RECTANGLE));
        measureDelayItem.addListener(SWT.Selection, e -> openMeasureDelayDialog());
        if (!mac) {
            MenuItem preferencesItem = new MenuItem(toolsMenu, SWT.PUSH);
            Bindings.text(preferencesItem, "menu.tools.preferences", preferencesItem::setText);
            preferencesItem.setImage(IconUtils.icon(shell.getDisplay(), Icon.GEAR));
            preferencesItem.addListener(SWT.Selection, e -> openPreferencesDialog());
        }

        MenuItem helpCascade = new MenuItem(menuBar, SWT.CASCADE);
        Bindings.text(helpCascade, "menu.help", helpCascade::setText);
        Menu helpMenu = new Menu(shell, SWT.DROP_DOWN);
        helpCascade.setMenu(helpMenu);

        MenuItem helpShow = new MenuItem(helpMenu, SWT.PUSH);
        Bindings.text(helpShow, "menu.help.show", helpShow::setText);
        helpShow.setAccelerator(SWT.F1);
        helpShow.addListener(SWT.Selection, e -> HelpViewer.instance().show(shell));

        MenuItem helpShowActive = new MenuItem(helpMenu, SWT.PUSH);
        Bindings.text(helpShowActive, "menu.help.showActive", helpShowActive::setText);
        helpShowActive.setAccelerator(SWT.CTRL | SWT.F1);
        helpShowActive.addListener(SWT.Selection, e -> HelpViewer.instance().showForActiveItem(shell));

        new MenuItem(helpMenu, SWT.SEPARATOR);

        MenuItem helpCheckUpdate = new MenuItem(helpMenu, SWT.PUSH);
        Bindings.text(helpCheckUpdate, "menu.help.checkForUpdate", helpCheckUpdate::setText);
        helpCheckUpdate.addListener(SWT.Selection, e -> UpdateChecker.checkNow(shell));

        MenuItem helpCheckOnStartup = new MenuItem(helpMenu, SWT.CHECK);
        Bindings.text(helpCheckOnStartup, "menu.help.checkOnStartup", helpCheckOnStartup::setText);
        helpCheckOnStartup.setSelection(prefs.isCheckForUpdatesOnStartup());
        helpCheckOnStartup.addListener(SWT.Selection, e -> {
            prefs.setCheckForUpdatesOnStartup(helpCheckOnStartup.getSelection());
        });

        MenuItem helpIncludeBeta = new MenuItem(helpMenu, SWT.CHECK);
        Bindings.text(helpIncludeBeta, "menu.help.includeBeta", helpIncludeBeta::setText);
        helpIncludeBeta.setSelection(prefs.isIncludeBetaInUpdateChecks());
        helpIncludeBeta.addListener(SWT.Selection, e -> {
            prefs.setIncludeBetaInUpdateChecks(helpIncludeBeta.getSelection());
        });

        new MenuItem(helpMenu, SWT.SEPARATOR);

        MenuItem helpReport = new MenuItem(helpMenu, SWT.PUSH);
        Bindings.text(helpReport, "menu.help.reportProblem", helpReport::setText);
        helpReport.addListener(SWT.Selection, e -> Program.launch(HelpUrls.ISSUES_URL));

        new MenuItem(helpMenu, SWT.SEPARATOR);

        MenuItem helpTip = new MenuItem(helpMenu, SWT.PUSH);
        Bindings.text(helpTip, "menu.help.tipOfDay", helpTip::setText);
        helpTip.addListener(SWT.Selection, e -> new TipOfTheDayDialog(shell).open());

        MenuItem helpRebuildIndex = new MenuItem(helpMenu, SWT.PUSH);
        Bindings.text(helpRebuildIndex, "menu.help.rebuildIndex", helpRebuildIndex::setText);
        helpRebuildIndex.addListener(SWT.Selection, e -> rebuildHelpIndex());

        // Help -> About.  On macOS this is the application-menu "About Phonalyser"
        // item (wired in wireMacSystemMenu), so it is omitted from Help there.
        if (!mac) {
            new MenuItem(helpMenu, SWT.SEPARATOR);
            MenuItem helpAbout = new MenuItem(helpMenu, SWT.PUSH);
            Bindings.text(helpAbout, "menu.help.about", helpAbout::setText);
            helpAbout.addListener(SWT.Selection, e -> showAboutDialog());
        }

        if (mac) {
            wireMacSystemMenu();
        }
    }

    /** Opens the Preferences ("Settings") dialog.  Streams keep running while it
     *  is up; only a committed AUDIO-config change bounces live playback /
     *  capture; font and GPU changes apply in place.  Shared by the
     *  Tools menu (non-macOS) and the macOS application-menu Settings item. */
    private void openPreferencesDialog() {
        PreferencesDialog dialog = new PreferencesDialog(shell);
        dialog.setMainWindow(this);
        dialog.open();
    }

    /** Opens the Tune-notch wizard - a continuous Farina-sweep frequency
     *  response chart for live notch-filter tuning. */
    private void openTuneNotchDialog() {
        new TuneNotchWizardDialog(shell).open();
    }

    /** Opens the delay tool - one rectangle period through the direct
     *  output-to-input path, timed from render to capture. */
    private void openMeasureDelayDialog() {
        new MeasureDelayDialog(shell).open();
    }

    /** Shows the splash in its "About" mode.  Shared by the Help menu
     *  (non-macOS) and the macOS application-menu About item. */
    private void showAboutDialog() {
        new StartupSplash(shell.getDisplay()).showAsAbout(shell);
    }

    /** macOS only: routes the application ("apple") menu's standard About and
     *  Settings items to the same handlers as the (suppressed) Help -> About and
     *  Tools -> Preferences entries.  Quit / Services / Hide are left as macOS
     *  provides them. */
    private void wireMacSystemMenu() {
        Menu systemMenu = display.getSystemMenu();
        if (systemMenu == null) return;
        for (MenuItem item : systemMenu.getItems()) {
            switch (item.getID()) {
                case SWT.ID_ABOUT       -> item.addListener(SWT.Selection, e -> showAboutDialog());
                case SWT.ID_PREFERENCES -> item.addListener(SWT.Selection, e -> openPreferencesDialog());
                default                 -> { /* leave Quit / Services / Hide untouched */ }
            }
        }
    }

    /** Regenerates the search index ({@code search-index.js} +
     *  {@code help-index.html}) for EVERY help language bundle in one action.
     *  The on-disk help source is located automatically (the app run from the
     *  project root, or derived from the compiled-resources path), so no folder
     *  has to be picked. */
    private void rebuildHelpIndex() {
        Path root = locateHelpSourceRoot();
        List<Path> bundles = (root == null) ? List.of() : helpBundles(root);
        if (bundles.isEmpty()) {
            Dialogs.error(shell, I18n.t("help.index.rebuild.title"),
                    I18n.t("help.index.rebuild.notHelpDir"));
            return;
        }
        int pages = 0;
        int topics = 0;
        int terms = 0;
        try {
            for (Path bundle : bundles) {
                int[] s = new HelpIndexBuilder(bundle).build();
                log.info("Help index rebuilt for {}: pages={} topics={} terms={}",
                        bundle.getFileName(), s[0], s[1], s[2]);
                pages += s[0];
                topics += s[1];
                terms += s[2];
            }
        } catch (IOException ex) {
            log.error("Help index rebuild failed: {}", ex.getMessage(), ex);
            Dialogs.error(shell, I18n.t("help.index.rebuild.title"),
                    I18n.t("help.index.rebuild.failed", ex.getMessage()));
            return;
        }
        Dialogs.info(shell, I18n.t("help.index.rebuild.title"),
                I18n.t("help.index.rebuild.done", pages, topics, terms));
    }

    /** Resolves the on-disk {@code help/} source root that holds the per-language
     *  bundles, or {@code null} when it can't be found (e.g. the app isn't run
     *  from a source checkout).  The index must be written into the SOURCE tree
     *  (not the compiled {@code target/classes} copy), so this prefers
     *  {@code <cwd>/src/main/resources/help} and otherwise derives the source
     *  path from the compiled-resources location. */
    private Path locateHelpSourceRoot() {
        Path fromCwd = Paths.get(System.getProperty("user.dir", "."),
                "src", "main", "resources", "help");
        if (Files.isDirectory(fromCwd)) return fromCwd;
        try {
            URL u = getClass().getClassLoader().getResource("help");
            if (u != null && "file".equals(u.getProtocol())) {
                // <root>/target/classes/help -> <root>/src/main/resources/help
                Path classesHelp = Paths.get(u.toURI());
                Path projectRoot = classesHelp.getParent().getParent().getParent();
                Path src = projectRoot.resolve(Paths.get("src", "main", "resources", "help"));
                if (Files.isDirectory(src)) return src;
            }
        } catch (Exception ex) {
            log.warn("Could not derive help source root from the classpath", ex);
        }
        return null;
    }

    /** Help language bundles under {@code root}: every immediate sub-folder that
     *  holds an {@code index.html} (one per language), or {@code root} itself
     *  when it is a single bundle. */
    private List<Path> helpBundles(Path root) {
        List<Path> bundles = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root, Files::isDirectory)) {
            for (Path sub : ds) {
                if (Files.isRegularFile(sub.resolve("index.html"))) bundles.add(sub);
            }
        } catch (IOException ex) {
            log.warn("Could not enumerate help bundles in {}", root, ex);
        }
        if (bundles.isEmpty() && Files.isRegularFile(root.resolve("index.html"))) {
            bundles.add(root);
        }
        bundles.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return bundles;
    }

    /** Scans the active i18n source for {@code messages*.properties}
     *  bundles and returns their BCP-47 tags, sorted alphabetically.
     *  Prefers the external {@code i18n/} folder resolved by
     *  {@link I18n#externalDir()} so newly-dropped {@code .properties}
     *  files appear without rebuilding; falls back to the classpath in
     *  dev mode. */
    private List<String> discoverLanguageTags() {
        List<String> tags = new ArrayList<>();
        tags.add("en");                                  // default bundle

        Path external = I18n.externalDir();
        if (external != null) {
            try (DirectoryStream<Path> ds =
                         Files.newDirectoryStream(external, "messages_*.properties")) {
                for (Path p : ds) {
                    tags.add(tagFromFileName(p.getFileName().toString()));
                }
                Collections.sort(tags);
                return tags;
            } catch (Exception ex) {
                log.warn("Could not enumerate external i18n bundles in {}", external, ex);
            }
        }

        URL folderUrl = getClass().getClassLoader().getResource("i18n");
        if (folderUrl == null) {
            log.warn("i18n/ resource folder not found on classpath");
            return tags;
        }
        try {
            if ("file".equals(folderUrl.getProtocol())) {
                Path dir = Paths.get(folderUrl.toURI());
                try (DirectoryStream<Path> ds =
                             Files.newDirectoryStream(dir, "messages_*.properties")) {
                    for (Path p : ds) {
                        tags.add(tagFromFileName(p.getFileName().toString()));
                    }
                }
            } else if ("jar".equals(folderUrl.getProtocol())) {
                JarURLConnection conn = (JarURLConnection) folderUrl.openConnection();
                JarFile jar = conn.getJarFile();
                Enumeration<? extends JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.startsWith("i18n/messages_") && name.endsWith(".properties")
                            && name.indexOf('/', "i18n/".length()) < 0) {
                        tags.add(tagFromFileName(name.substring("i18n/".length())));
                    }
                }
            } else {
                log.warn("Unsupported i18n/ URL protocol: {}", folderUrl.getProtocol());
            }
        } catch (Exception ex) {
            log.warn("Could not enumerate language bundles", ex);
        }
        Collections.sort(tags);
        return tags;
    }

    /** {@code messages_zh_TW.properties} -> {@code zh-TW}. */
    private String tagFromFileName(String fileName) {
        String core = fileName.substring("messages_".length(),
                fileName.length() - ".properties".length());
        return core.replace('_', '-');
    }

    /** Native-language label for the BCP-47 tag, with first letter
     *  capitalised (e.g. {@code nb} -> "Norsk bokmål", not "norsk bokmål"). */
    private String displayLabel(String tag) {
        Locale loc = Locale.forLanguageTag(tag);
        String name = loc.getDisplayName(loc);
        if (name == null || name.isEmpty()) return tag;
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** Appends one radio-style entry to the Language submenu.  Selecting
     *  it persists the BCP-47 tag and re-translates the window in place
     *  ({@link #applyLanguage(Locale)}) - no rebuild; the panes and the
     *  running measurements are untouched. */
    private void addLanguageMenuItem(Menu menu, String tag, String label) {
        Preferences prefs = Preferences.instance();
        MenuItem item = new MenuItem(menu, SWT.RADIO);
        item.setText(label);
        boolean alreadyActive = tag.equals(prefs.getUiLanguage());
        item.setSelection(alreadyActive);
        item.addListener(SWT.Selection, e -> {
            // RADIO fires both for the newly-selected item (selection=true)
            // and the previously-selected one (selection=false); only act
            // on the gain event.  Also skip when the user re-clicks the
            // already-active language - saves an unnecessary re-translation.
            if (!item.getSelection()) return;
            if (tag.equals(prefs.getUiLanguage())) return;
            prefs.setUiLanguage(tag);
            applyLanguage(Locale.forLanguageTag(tag));
        });
    }
}
