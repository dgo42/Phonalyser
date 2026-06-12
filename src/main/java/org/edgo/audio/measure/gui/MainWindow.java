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

import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Shell;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.common.ShellIcons;
import org.edgo.audio.measure.gui.helpviewer.AboutDialog;
import org.edgo.audio.measure.gui.helpviewer.HelpUrls;
import org.edgo.audio.measure.gui.helpviewer.HelpViewer;
import org.edgo.audio.measure.gui.helpviewer.UpdateChecker;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.preferences.BackendPrefs;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.gui.preferences.PreferencesDialog;

import lombok.extern.log4j.Log4j2;

/**
 * Application's main window.  Owns the top menu bar (File / Language /
 * Tools / Help), the {@link Shell} itself, and the shell-level concerns
 * (icons, minimum / saved size, language-switch handshake).  All
 * measurement panes and tab content live in {@link MainTab}; all
 * {@link Image} resources flow through {@link IconUtils}, whose cache is
 * registered against the shell so disposal is centralised.
 *
 * <p>Menu responsibilities:
 * <ul>
 *   <li><b>File</b> — application exit.</li>
 *   <li><b>Language</b> — runtime locale switch (do-not-translate sentinel,
 *       always rendered as "Language" so users who can't read the current
 *       UI language can still find this menu).</li>
 *   <li><b>Tools</b> — opens the {@code PreferencesDialog}.  Pauses live
 *       capture + generator playback around the dialog (via
 *       {@link MainTab#pauseForDialog()}) so device changes don't tear
 *       running streams.</li>
 *   <li><b>Help</b> — multi-chapter HTML help viewer (F1 / Ctrl+F1),
 *       startup-check toggles, GitHub issue reporting, About dialog.</li>
 * </ul>
 */
@Log4j2
public final class MainWindow {

    /** Minimum shell footprint — a standard 1280×720 (720p) window.  The
     *  content's computed natural layout is wider but shorter than this, so the
     *  minimum is clamped to let the window shrink to 1280 wide while keeping at
     *  least 720 tall (and never below the content's own natural height). */
    private static final int MIN_SHELL_WIDTH  = 1280;
    private static final int MIN_SHELL_HEIGHT = 720;

    /** Top-level "please wait" shell shown while a language switch is in
     *  flight.  Static so it survives the disposal of the old MainWindow
     *  and gets closed by the new instance's {@link #open()}.  Only one
     *  may exist at a time. */
    private static Shell languageSwitchShell;

    private final Display display;
    private final Shell   shell;
    /** Rebuilt in place by {@link #rebuildContent()} (language / UI-font
     *  changes) — the shell itself survives. */
    private MainTab mainTab;

    public MainWindow(Display display) {
        this.display = display;
        this.shell   = new Shell(display);
        shell.setText(I18n.t("app.title"));
        shell.setLayout(new FillLayout());
        ShellIcons.apply(shell);

        // Hook the icon cache to this shell so every Image rendered via
        // IconUtils.instance() is disposed automatically on shell teardown.
        IconUtils.instance().registerShell(shell);

        buildMenuBar();

        this.mainTab = new MainTab(shell);
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
        // width — else a saved 1280 would be forced back up to the natural box).
        int w = savedW > 0 ? Math.max(minW, savedW) : Math.max(MIN_SHELL_WIDTH, natural.x);
        int h = savedH > 0 ? Math.max(minH, savedH) : Math.max(800, natural.y);
        shell.setSize(w, h);
    }

    /** Shows the window and logs the start event.  Caller drives the SWT event loop.
     *  Also closes the "Switching language…" loading dialog if one was put
     *  up by the previous instance's {@link #requestRecreate()}. */
    public void open() {
        shell.open();
        closeRebuildSplash();
        log.info("GUI started.");
        // Fire the silent update check once the shell is up.  The
        // checker spawns its own daemon thread; doing the check from
        // open() (rather than during shell construction) means a network
        // hiccup can't delay window paint.
        Preferences prefs = Preferences.instance();
        if (prefs.isCheckForUpdatesOnStartup()) {
            UpdateChecker.checkOnStartup(shell, prefs.isIncludeBetaInUpdateChecks());
        }
    }

    public boolean isDisposed() {
        return shell.isDisposed();
    }

    /** Rebuilds the menu bar + tab content INSIDE the live shell — the
     *  window itself (position, size, maximized state) survives.  Used by
     *  the language switch and by UI-font changes.  The live engines are
     *  snapshotted before the teardown and restarted on the rebuilt panes
     *  afterwards, so a font / language change never silences a running
     *  measurement; a brief "Applying settings…" splash covers the work. */
    public void rebuildContent() {
        showRebuildSplash(display);
        try {
            MultifunctionalTab.RunningState running = mainTab.runningState();
            mainTab.stopForRecreate();
            Menu oldMenu = shell.getMenuBar();
            for (Control c : shell.getChildren()) {
                c.dispose();
            }
            if (oldMenu != null) oldMenu.dispose();
            shell.setText(I18n.t("app.title"));
            buildMenuBar();
            mainTab = new MainTab(shell);
            // Recompute only the MINIMUM size — the current window size and
            // position deliberately stay untouched.
            Point natural = mainTab.computeNaturalShellSize();
            shell.setMinimumSize(Math.min(natural.x, MIN_SHELL_WIDTH),
                    Math.max(natural.y, MIN_SHELL_HEIGHT));
            mainTab.applySavedLayoutState();
            shell.layout(true, true);
            mainTab.restoreRunningState(running);
        } finally {
            closeRebuildSplash();
        }
    }

    /** The audio configuration the running streams depend on — backend +
     *  the active backend's devices, sample rates and bit depths.  Compared
     *  before/after the Preferences dialog: only a committed change here
     *  bounces the running playback / capture. */
    private String audioConfigFingerprint(Preferences prefs) {
        BackendPrefs bp = prefs.current();
        return prefs.getBackend() + "|"
                + bp.getInputDeviceName()  + "|" + bp.getInputSampleRate()  + "|" + bp.getInputBitDepth() + "|"
                + bp.getOutputDeviceName() + "|" + bp.getOutputSampleRate() + "|" + bp.getOutputBitDepth();
    }

    /** Builds and shows the brief splash covering a content rebuild. */
    private static void showRebuildSplash(Display d) {
        if (languageSwitchShell != null && !languageSwitchShell.isDisposed()) return;
        Shell ld = new Shell(d, SWT.ON_TOP | SWT.TITLE | SWT.BORDER);
        ld.setText(I18n.t("ui.applying.title"));
        FillLayout fl = new FillLayout();
        fl.marginWidth  = 24;
        fl.marginHeight = 18;
        ld.setLayout(fl);
        Label l = new Label(ld, SWT.CENTER);
        l.setText(I18n.t("ui.applying"));
        ld.pack();
        Rectangle screen = d.getPrimaryMonitor().getBounds();
        Point sz = ld.getSize();
        ld.setLocation(screen.x + (screen.width  - sz.x) / 2,
                       screen.y + (screen.height - sz.y) / 2);
        ld.open();
        // Run a few event-loop ticks so the OS actually paints the shell
        // before the heavy teardown work begins on this same thread.
        for (int i = 0; i < 5; i++) {
            if (!d.readAndDispatch()) break;
        }
        languageSwitchShell = ld;
    }

    private static void closeRebuildSplash() {
        if (languageSwitchShell != null && !languageSwitchShell.isDisposed()) {
            languageSwitchShell.close();
        }
        languageSwitchShell = null;
    }

    // -------------------------------------------------------------------------
    // Menu bar
    // -------------------------------------------------------------------------

    private void buildMenuBar() {
        Preferences prefs = Preferences.instance();
        Menu menuBar = new Menu(shell, SWT.BAR);
        shell.setMenuBar(menuBar);

        MenuItem fileCascade = new MenuItem(menuBar, SWT.CASCADE);
        fileCascade.setText(I18n.t("menu.file"));
        Menu fileMenu = new Menu(shell, SWT.DROP_DOWN);
        fileCascade.setMenu(fileMenu);
        MenuItem exitItem = new MenuItem(fileMenu, SWT.PUSH);
        exitItem.setText(I18n.t("menu.file.exit"));
        exitItem.addListener(SWT.Selection, e -> shell.close());

        // Top-level Language menu, sitting right after File.  Its label is
        // deliberately NOT translated — it stays "Language" in every
        // locale so a user who can't read the current language can still
        // find this menu and switch to their own.
        MenuItem languageCascade = new MenuItem(menuBar, SWT.CASCADE);
        // do-not-translate — always "Language"
        languageCascade.setText("Language");
        Menu languageMenu = new Menu(shell, SWT.DROP_DOWN);
        languageCascade.setMenu(languageMenu);
        for (String tag : discoverLanguageTags()) {
            addLanguageMenuItem(languageMenu, tag, displayLabel(tag));
        }

        MenuItem toolsCascade = new MenuItem(menuBar, SWT.CASCADE);
        toolsCascade.setText(I18n.t("menu.tools"));
        Menu toolsMenu = new Menu(shell, SWT.DROP_DOWN);
        toolsCascade.setMenu(toolsMenu);
        MenuItem preferencesItem = new MenuItem(toolsMenu, SWT.PUSH);
        preferencesItem.setText(I18n.t("menu.tools.preferences"));
        preferencesItem.addListener(SWT.Selection, e -> {
            // Streams keep running while the dialog is up.  Only when OK
            // committed a change to the AUDIO configuration are the running
            // playback / capture bounced (stop + restart picks the new
            // backend / device / format up); Cancel leaves the prefs — and
            // therefore the streams — untouched.  Caveat: device enumeration
            // inside the dialog may still disturb a running WDM-KS stream.
            Preferences mwPrefs = Preferences.instance();
            String audioBefore = audioConfigFingerprint(mwPrefs);
            String fontsBefore = mwPrefs.getUiFontNormal() + "/" + mwPrefs.getUiFontBold();
            new PreferencesDialog(shell).open(() -> {
                String fontsAfter = mwPrefs.getUiFontNormal() + "/" + mwPrefs.getUiFontBold();
                if (!fontsAfter.equals(fontsBefore)) {
                    // Fonts are woven into every painter — rebuild the
                    // content in place (panes come back stopped, like a
                    // language switch); an audio change is covered too.
                    rebuildContent();
                } else if (!audioConfigFingerprint(mwPrefs).equals(audioBefore)) {
                    mainTab.pauseForDialog().run();   // stop + restart on the new config
                }
            });
        });

        MenuItem helpCascade = new MenuItem(menuBar, SWT.CASCADE);
        helpCascade.setText(I18n.t("menu.help"));
        Menu helpMenu = new Menu(shell, SWT.DROP_DOWN);
        helpCascade.setMenu(helpMenu);

        MenuItem helpShow = new MenuItem(helpMenu, SWT.PUSH);
        helpShow.setText(I18n.t("menu.help.show"));
        helpShow.setAccelerator(SWT.F1);
        helpShow.addListener(SWT.Selection, e -> HelpViewer.instance().show(shell));

        MenuItem helpShowActive = new MenuItem(helpMenu, SWT.PUSH);
        helpShowActive.setText(I18n.t("menu.help.showActive"));
        helpShowActive.setAccelerator(SWT.CTRL | SWT.F1);
        helpShowActive.addListener(SWT.Selection, e -> HelpViewer.instance().showForActiveItem(shell));

        new MenuItem(helpMenu, SWT.SEPARATOR);

        MenuItem helpCheckUpdate = new MenuItem(helpMenu, SWT.PUSH);
        helpCheckUpdate.setText(I18n.t("menu.help.checkForUpdate"));
        helpCheckUpdate.addListener(SWT.Selection, e -> UpdateChecker.checkNow(shell));

        MenuItem helpCheckOnStartup = new MenuItem(helpMenu, SWT.CHECK);
        helpCheckOnStartup.setText(I18n.t("menu.help.checkOnStartup"));
        helpCheckOnStartup.setSelection(prefs.isCheckForUpdatesOnStartup());
        helpCheckOnStartup.addListener(SWT.Selection, e -> {
            prefs.setCheckForUpdatesOnStartup(helpCheckOnStartup.getSelection());
        });

        MenuItem helpIncludeBeta = new MenuItem(helpMenu, SWT.CHECK);
        helpIncludeBeta.setText(I18n.t("menu.help.includeBeta"));
        helpIncludeBeta.setSelection(prefs.isIncludeBetaInUpdateChecks());
        helpIncludeBeta.addListener(SWT.Selection, e -> {
            prefs.setIncludeBetaInUpdateChecks(helpIncludeBeta.getSelection());
        });

        new MenuItem(helpMenu, SWT.SEPARATOR);

        MenuItem helpReport = new MenuItem(helpMenu, SWT.PUSH);
        helpReport.setText(I18n.t("menu.help.reportProblem"));
        helpReport.addListener(SWT.Selection, e -> Program.launch(HelpUrls.ISSUES_URL));

        new MenuItem(helpMenu, SWT.SEPARATOR);

        MenuItem helpAbout = new MenuItem(helpMenu, SWT.PUSH);
        helpAbout.setText(I18n.t("menu.help.about"));
        helpAbout.addListener(SWT.Selection, e -> AboutDialog.show(shell));
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

    /** {@code messages_zh_TW.properties} → {@code zh-TW}. */
    private String tagFromFileName(String fileName) {
        String core = fileName.substring("messages_".length(),
                fileName.length() - ".properties".length());
        return core.replace('_', '-');
    }

    /** Native-language label for the BCP-47 tag, with first letter
     *  capitalised (e.g. {@code nb} → "Norsk bokmål", not "norsk bokmål"). */
    private String displayLabel(String tag) {
        Locale loc = Locale.forLanguageTag(tag);
        String name = loc.getDisplayName(loc);
        if (name == null || name.isEmpty()) return tag;
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** Appends one radio-style entry to the Language submenu.  Selecting
     *  it persists the BCP-47 tag, applies the locale, and tears down /
     *  rebuilds the shell so every widget picks up the new translations. */
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
            // already-active language — saves an unnecessary shell rebuild.
            if (!item.getSelection()) return;
            if (tag.equals(prefs.getUiLanguage())) return;
            prefs.setUiLanguage(tag);
            I18n.setLocale(Locale.forLanguageTag(tag));
            rebuildContent();
        });
    }
}
