package org.edgo.audio.measure.gui;

import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.edgo.audio.measure.sound.AudioBackend;
import lombok.extern.log4j.Log4j2;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseTrackAdapter;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.eclipse.swt.graphics.Path;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.MessageBox;
import org.eclipse.swt.widgets.Sash;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.edgo.audio.measure.gui.fft.FftPane;
import org.edgo.audio.measure.gui.generator.GeneratorPane;
import org.edgo.audio.measure.gui.helpviewer.AboutDialog;
import org.edgo.audio.measure.gui.helpviewer.HelpUrls;
import org.edgo.audio.measure.gui.helpviewer.HelpViewer;
import org.edgo.audio.measure.gui.helpviewer.UpdateChecker;
import org.edgo.audio.measure.gui.scope.AdcCalibrationDialog;
import org.edgo.audio.measure.gui.scope.OscilloscopeController;
import org.edgo.audio.measure.gui.scope.OscilloscopePane;
import org.edgo.audio.measure.gui.scope.OscilloscopeView;
import org.edgo.audio.measure.gui.scope.ScreenshotDialog;
import org.edgo.audio.measure.gui.scope.SignalBuffer;

/**
 * The application's main window.  Owns the menu bar, the
 * generator / oscilloscope / FFT panes, and the per-pane bottom toolbars
 * carrying the LED-style Play / Record toggle buttons (plus the oscilloscope's
 * vertical / horizontal / trigger control groups).
 *
 * <p>Layout:
 * <pre>
 *   ┌──────────────┬───────────────────────┐
 *   │              │     Oscilloscope      │
 *   │  Generator   ├───────────────────────┤
 *   │              │         FFT           │
 *   └──────────────┴───────────────────────┘
 * </pre>
 * The {@link Display} lifecycle stays with the caller ({@link GuiMain}); the
 * window's resources (icons) are released when its shell is disposed.
 */
@Log4j2
public final class MainWindow {

    private static final int ICON_SIZE         = 33;   // play / record LED (large)
    private static final int SMALL_ICON_SIZE   = 26;   // start LED inside trigger group
    private static final int TINY_ICON_SIZE    = 16;   // play LED inside the generator's Play-from row (browse-button height)

    private final Display display;
    private final Shell   shell;
    private SashForm      hSplit;     // generator | (osc / fft)
    private SashForm      vSplit;     // osc / fft
    private OscilloscopeController oscController;
    private OscilloscopePane       oscPaneRef;
    private GeneratorPane          genPane;
    private FftPane                fftPane;
    // References used to compose oscilloscope-pane screenshots at any resolution.
    private Composite          oscPane;
    private OscilloscopeView   oscView;
    /** Image bundle shared between the live OscilloscopePane and the offscreen one used for screenshots. */
    private OscilloscopePane.Images oscImages;
    /** True when the oscilloscope pane is collapsed to just its title bar. */
    private boolean            oscCollapsed;
    /** True when the FFT pane is collapsed to just its title bar. */
    private boolean            fftCollapsed;
    /** True when the generator pane is collapsed to just its narrow title strip. */
    private boolean            genCollapsed;
    /** Generator-pane pixel width remembered at collapse time so {@link #toggleGenCollapse()}
     *  can restore the original width on expand. */
    private int                preCollapseGenWidth;
    /** Per-child visibility/exclude snapshot taken when the generator pane
     *  is collapsed, restored verbatim on expand so mode-specific show/hide
     *  state (e.g. sweep vs non-sweep fields) survives the round-trip. */
    private boolean[]          preCollapseGenChildVisible;
    private boolean[]          preCollapseGenChildExclude;
    /** Same idea for the osc / fft panes — without it, expanding the
     *  oscilloscope pane after a collapse would force every child visible,
     *  including the navSlider and its mutually-exclusive height spacer,
     *  doubling the row beneath the scope canvas. */
    private boolean[]          preCollapseOscChildVisible;
    private boolean[]          preCollapseOscChildExclude;
    private boolean[]          preCollapseFftChildVisible;
    private boolean[]          preCollapseFftChildExclude;
    /** SashForm weights snapshot taken at collapse time so {@link #toggleOscCollapse()} can restore them on expand. */
    private int[]              preCollapseVSplitWeights;
    /** Approximate pixel size of the Group's title bar — used as the SashForm weight for the collapsed pane. */
    private static final int   COLLAPSED_PANE_SIZE = 28;

    public MainWindow(Display display) {
        this.display = display;
        this.shell   = new Shell(display);
        shell.setText(I18n.t("app.title"));
        shell.setLayout(new FillLayout());
        applyShellIcons(shell);

        buildMenuBar();

        // Play icons — play.svg tinted green: #00AA00 inactive, #00FF00
        // active.  renderAtHeight derives the image width from the
        // icon's actual path aspect (~0.94) so the button chrome adds
        // equal padding on all four sides instead of horizontal-only.
        RGB greenDim = new RGB(0x00, 0xAA, 0x00);
        RGB greenLit = new RGB(0x00, 0xFF, 0x00);
        Image playDim     = SvgIcon.renderAtHeight(display, "/icons/play.svg", ICON_SIZE,      greenDim);
        Image playLit     = SvgIcon.renderAtHeight(display, "/icons/play.svg", ICON_SIZE,      greenLit);
        Image tinyPlayDim = SvgIcon.renderAtHeight(display, "/icons/play.svg", TINY_ICON_SIZE, greenDim);
        Image tinyPlayLit = SvgIcon.renderAtHeight(display, "/icons/play.svg", TINY_ICON_SIZE, greenLit);
        Image recordDim   = createRecordLed(200,  40,  40, false, ICON_SIZE);
        Image recordLit   = createRecordLed(255,   0,   0, true,  ICON_SIZE);
        // Smaller play LED for the Start button inside the Trigger group so
        // it fits the same square footprint as the rest of the group's buttons.
        Image bluePlayLit = createPlayLed( 60, 130, 230, true,  SMALL_ICON_SIZE);
        Image cameraIcon  = SvgIcon.render(display, "/icons/camera.svg",
                (int) Math.round(SMALL_ICON_SIZE * 1.27), SMALL_ICON_SIZE);
        Image crosshairIcon = SvgIcon.render(display, "/icons/crosshair.svg",
                SMALL_ICON_SIZE, SMALL_ICON_SIZE);
        // New: floppy-disk for Save / Save-to buttons; folder-open for
        // Open / Browse buttons.  Rendered at a fixed height (icon
        // padding stays consistent across buttons) and width derived
        // from each icon's path aspect ratio (the button chrome adds
        // equal padding on all sides instead of stretching to fit a
        // square image).
        Image floppyDiskIcon = SvgIcon.renderAtHeight(display, "/icons/floppy-disk.svg",
                TINY_ICON_SIZE, null);
        Image folderOpenIcon = SvgIcon.renderAtHeight(display, "/icons/folder-open.svg",
                TINY_ICON_SIZE, null);
        shell.addDisposeListener(e -> {
            playDim.dispose();
            playLit.dispose();
            tinyPlayDim.dispose();
            tinyPlayLit.dispose();
            recordDim.dispose();
            recordLit.dispose();
            bluePlayLit.dispose();
            cameraIcon.dispose();
            crosshairIcon.dispose();
            floppyDiskIcon.dispose();
            folderOpenIcon.dispose();
        });

        // Preload saved generator width BEFORE buildPanes so the hSplit
        // controlListener (registered inside buildPanes) honours it on its
        // very first fire — otherwise the listener derives genPaneWidthPx
        // from the construction-default weights, briefly painting the
        // generator pane at the wrong size before applySavedSashWeights
        // could restore it.
        int savedGenWidth = Preferences.instance().getGenPaneWidth();
        if (savedGenWidth >= GEN_MIN_WIDTH) genPaneWidthPx = savedGenWidth;

        buildPanes(playDim, playLit, tinyPlayDim, tinyPlayLit,
                   recordDim, recordLit, bluePlayLit, cameraIcon, crosshairIcon,
                   floppyDiskIcon, folderOpenIcon);
        applyMinimumShellSize();
        applySavedSashWeights();
        applySavedCollapseStates();
        registerStatePersistence();
    }

    /** Restores the persisted collapse state of each pane.  Called after
     *  panes are built and saved sash weights are applied so the toggle
     *  helpers see a clean baseline. */
    private void applySavedCollapseStates() {
        Preferences prefs = Preferences.instance();
        // Defensive: both osc and fft collapsed isn't a valid state — prefer osc.
        boolean wantOsc = prefs.isOscPaneCollapsed();
        boolean wantFft = prefs.isFftPaneCollapsed() && !wantOsc;
        if (prefs.isGenPaneCollapsed())    toggleGenCollapse();
        if (wantOsc)                       toggleOscCollapse();
        else if (wantFft)                  toggleFftCollapse();
    }

    /**
     * Computes the smallest shell size that fits the oscilloscope toolbar
     * without clipping and uses that as the shell's minimum size.  The
     * initial size is the saved window dimensions from
     * {@link Preferences} (if any, and at least the minimum), otherwise a
     * sensible default that's also at least the minimum.
     */
    private void applyMinimumShellSize() {
        Point natural = shell.computeSize(SWT.DEFAULT, SWT.DEFAULT, true);
        shell.setMinimumSize(natural);

        Preferences prefs = Preferences.instance();
        int savedW = prefs.getWindowWidth();
        int savedH = prefs.getWindowHeight();
        int w = savedW > 0 ? Math.max(natural.x, savedW) : Math.max(1280, natural.x);
        int h = savedH > 0 ? Math.max(natural.y, savedH) : Math.max(800, natural.y);
        shell.setSize(w, h);
    }

    /** Applies the Multifunctional tab's vertical split weights from prefs.
     *  Generator-pane pixel width is preloaded earlier in the constructor
     *  (see {@link #genPaneWidthPx}), so this method only handles {@code vSplit}. */
    private void applySavedSashWeights() {
        int[] vw = Preferences.instance().getMultiVSplitWeights();
        if (isValidWeights(vw, 2)) vSplit.setWeights(vw);
    }

    private boolean isValidWeights(int[] arr, int expectedLen) {
        if (arr == null || arr.length != expectedLen) return false;
        int sum = 0;
        for (int v : arr) {
            if (v < 0) return false;
            sum += v;
        }
        return sum > 0;
    }

    /**
     * Tracks shell resizes so we always know the latest "normal" (non-maximised /
     * non-minimised) size, and registers a dispose listener that writes the
     * final window size + sash weights to {@link Preferences}.  We don't save
     * on every resize event because the dragging-the-edge case would write
     * hundreds of times per second; the dispose hook covers the user closing
     * the window, which is when the chosen layout is final.
     */
    private void registerStatePersistence() {
        Point initial = shell.getSize();
        int[] lastNormal = { initial.x, initial.y };

        shell.addControlListener(ControlListener.controlResizedAdapter(e -> {
            if (!shell.getMaximized() && !shell.getMinimized()) {
                Point s = shell.getSize();
                lastNormal[0] = s.x;
                lastNormal[1] = s.y;
            }
        }));

        shell.addDisposeListener(e -> {
            Preferences prefs = Preferences.instance();
            prefs.setWindowWidth (lastNormal[0]);
            prefs.setWindowHeight(lastNormal[1]);
            if (genPaneWidthPx >= GEN_MIN_WIDTH) prefs.setGenPaneWidth(genPaneWidthPx);
            if (vSplit != null && !vSplit.isDisposed() && !oscCollapsed && !fftCollapsed) {
                // Only save the vertical split weights when neither pane is
                // collapsed — saving the collapsed weights ({28, N-28}) would
                // overwrite the user's actual desired split.
                prefs.setMultiVSplitWeights(vSplit.getWeights());
            }
            prefs.setGenPaneCollapsed(genCollapsed);
            prefs.setOscPaneCollapsed(oscCollapsed);
            prefs.setFftPaneCollapsed(fftCollapsed);
            prefs.save();
        });
    }

    /** Shows the window and logs the start event.  Caller drives the SWT event loop.
     *  Also closes the "Switching language…" loading dialog if one was put
     *  up by the previous instance's {@link #requestRecreate()} — by the
     *  time {@code shell.open()} returns the new main shell is on-screen,
     *  so the loading marker has served its purpose. */
    public void open() {
        shell.open();
        closeLanguageSwitchDialog();
        log.info("GUI started.");
    }

    public boolean isDisposed() {
        return shell.isDisposed();
    }

    /** True when {@link #requestRecreate()} was invoked — tells the event
     *  loop in {@code GuiMain} to construct a fresh {@code MainWindow}
     *  instead of dropping out of the loop on shell-dispose. */
    public boolean isRecreateRequested() {
        return recreateRequested;
    }
    private boolean recreateRequested;

    /** Tears the current shell down so the {@code GuiMain} loop builds a
     *  fresh one with the just-changed locale.  Running playback / capture
     *  is stopped first (state lives in {@link Preferences}, so the new
     *  shell picks everything else back up from there).  A modal-looking
     *  "Switching language…" Shell is shown first and persists across the
     *  old-shell teardown so the user has feedback while the recreate is
     *  in flight; the new instance's {@link #open()} disposes it. */
    public void requestRecreate() {
        if (recreateRequested) return;
        recreateRequested = true;
        showLanguageSwitchDialog(display);
        if (oscController != null && oscController.isRunning()) oscController.stop();
        if (genPane != null) genPane.pauseAroundDialog();   // returned resume hook is discarded
        if (!shell.isDisposed()) shell.close();
    }

    /** Top-level "please wait" shell shown while a language switch is in
     *  flight.  Static so it survives the disposal of the old MainWindow
     *  and gets closed by the new instance's {@link #open()}.  Only one
     *  may exist at a time. */
    private static Shell languageSwitchShell;

    /** Builds and shows the language-switch dialog.  Owns the layout
     *  itself (a single padded label) so the look is independent of the
     *  rest of the app's styling. */
    private static void showLanguageSwitchDialog(Display d) {
        if (languageSwitchShell != null && !languageSwitchShell.isDisposed()) return;
        Shell ld = new Shell(d, SWT.ON_TOP | SWT.TITLE | SWT.BORDER);
        ld.setText(I18n.t("language.switchingTitle"));
        FillLayout fl = new FillLayout();
        fl.marginWidth  = 24;
        fl.marginHeight = 18;
        ld.setLayout(fl);
        Label l = new Label(ld, SWT.CENTER);
        l.setText(I18n.t("language.switching"));
        ld.pack();
        // Centre on the primary monitor so the user spots it during the
        // brief moment the old shell is being torn down.
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

    private static void closeLanguageSwitchDialog() {
        if (languageSwitchShell != null && !languageSwitchShell.isDisposed()) {
            languageSwitchShell.close();
        }
        languageSwitchShell = null;
    }

    // -------------------------------------------------------------------------
    // Menu bar
    // -------------------------------------------------------------------------

    private void buildMenuBar() {
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
        // find this menu and switch to their own.  Picking a language
        // saves the choice to prefs and applies it via I18n.setLocale().
        MenuItem languageCascade = new MenuItem(menuBar, SWT.CASCADE);
	// do-not-translate — always "Language"
        languageCascade.setText(Language);            
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
            // Device enumeration inside the dialog (especially WDM-KS via
            // PortAudio) can disturb running streams; a backend change
            // additionally needs the *running* playback / capture torn
            // down so it doesn't keep using the old backend after the
            // user switched.  Pause both, then resume on close — the
            // resume path picks up the new active backend.
            boolean oscWasRunning = oscController != null && oscController.isRunning();
            if (oscWasRunning) oscController.stop();
            Runnable resumeGen = (genPane != null) ? genPane.pauseAroundDialog() : () -> {};
            new PreferencesDialog(shell).open(() -> {
                if (oscWasRunning) oscController.start();
                resumeGen.run();
            });
        });

        MenuItem helpCascade = new MenuItem(menuBar, SWT.CASCADE);
        helpCascade.setText(I18n.t("menu.help"));
        Menu helpMenu = new Menu(shell, SWT.DROP_DOWN);
        helpCascade.setMenu(helpMenu);

        Preferences prefs = Preferences.instance();

        MenuItem helpShow = new MenuItem(helpMenu, SWT.PUSH);
        helpShow.setText(I18n.t("menu.help.show"));
        helpShow.setAccelerator(SWT.F1);
        helpShow.addListener(SWT.Selection, e -> HelpViewer.show(shell));

        MenuItem helpShowActive = new MenuItem(helpMenu, SWT.PUSH);
        helpShowActive.setText(I18n.t("menu.help.showActive"));
        helpShowActive.setAccelerator(SWT.CTRL | SWT.F1);
        helpShowActive.addListener(SWT.Selection, e -> HelpViewer.showForActiveItem(shell));

        new MenuItem(helpMenu, SWT.SEPARATOR);

        MenuItem helpCheckUpdate = new MenuItem(helpMenu, SWT.PUSH);
        helpCheckUpdate.setText(I18n.t("menu.help.checkForUpdate"));
        helpCheckUpdate.addListener(SWT.Selection, e -> UpdateChecker.checkNow(shell));

        MenuItem helpCheckOnStartup = new MenuItem(helpMenu, SWT.CHECK);
        helpCheckOnStartup.setText(I18n.t("menu.help.checkOnStartup"));
        helpCheckOnStartup.setSelection(prefs.isCheckForUpdatesOnStartup());
        helpCheckOnStartup.addListener(SWT.Selection, e -> {
            prefs.setCheckForUpdatesOnStartup(helpCheckOnStartup.getSelection());
            prefs.save();
        });

        MenuItem helpIncludeBeta = new MenuItem(helpMenu, SWT.CHECK);
        helpIncludeBeta.setText(I18n.t("menu.help.includeBeta"));
        helpIncludeBeta.setSelection(prefs.isIncludeBetaInUpdateChecks());
        helpIncludeBeta.addListener(SWT.Selection, e -> {
            prefs.setIncludeBetaInUpdateChecks(helpIncludeBeta.getSelection());
            prefs.save();
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

    /** Scan the active i18n source for {@code messages*.properties} bundles
     *  and return their BCP-47 tags, sorted alphabetically.  Prefers the
     *  external {@code i18n/} folder resolved by {@link I18n#externalDir()}
     *  (so newly-dropped {@code .properties} files appear without rebuilding);
     *  falls back to the classpath in dev mode. */
    private List<String> discoverLanguageTags() {
        List<String> tags = new ArrayList<>();
        tags.add("en");                                  // default bundle

        java.nio.file.Path external = I18n.externalDir();
        if (external != null) {
            try (DirectoryStream<java.nio.file.Path> ds =
                         Files.newDirectoryStream(external, "messages_*.properties")) {
                for (java.nio.file.Path p : ds) {
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
                java.nio.file.Path dir = Paths.get(folderUrl.toURI());
                try (DirectoryStream<java.nio.file.Path> ds =
                             Files.newDirectoryStream(dir, "messages_*.properties")) {
                    for (java.nio.file.Path p : ds) {
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

    /** Loads the multi-resolution Phonalyser icon from
     *  {@code /icons/phonalyser-<size>.png} on the classpath and applies
     *  it to the shell.  Windows uses 16×16 in the title bar, ~32×32 in
     *  the taskbar; macOS / Linux pick larger sizes for window decorations
     *  — passing all of them lets the OS choose the sharpest match.
     *  The {@link Image}s are disposed when the shell is disposed. */
    private void applyShellIcons(Shell target) {
        int[] sizes = {16, 24, 32, 48, 64, 128, 256};
        List<Image> images = new ArrayList<>();
        for (int size : sizes) {
            String path = "/icons/phonalyser-" + size + ".png";
            try (InputStream is = MainWindow.class.getResourceAsStream(path)) {
                if (is != null) images.add(new Image(display, is));
            } catch (Exception ex) {
                log.warn("Could not load shell icon {}: {}", path, ex.getMessage());
            }
        }
        if (images.isEmpty()) return;
        Image[] arr = images.toArray(new Image[0]);
        target.setImages(arr);
        target.addDisposeListener(e -> {
            for (Image img : arr) {
                if (!img.isDisposed()) img.dispose();
            }
        });
    }

    /** Appends one radio-style entry to the Language submenu.  Selecting
     *  it persists the BCP-47 tag, applies the locale, and tears down /
     *  rebuilds the shell so every widget picks up the new translations. */
    private void addLanguageMenuItem(Menu menu, String tag, String label) {
        MenuItem item = new MenuItem(menu, SWT.RADIO);
        item.setText(label);
        boolean alreadyActive = tag.equals(Preferences.instance().getUiLanguage());
        item.setSelection(alreadyActive);
        item.addListener(SWT.Selection, e -> {
            // RADIO fires both for the newly-selected item (selection=true)
            // and the previously-selected one (selection=false); only act
            // on the gain event.  Also skip when the user re-clicks the
            // already-active language — saves an unnecessary shell rebuild.
            if (!item.getSelection()) return;
            if (tag.equals(Preferences.instance().getUiLanguage())) return;
            Preferences.instance().setUiLanguage(tag);
            Preferences.instance().save();
            I18n.setLocale(Locale.forLanguageTag(tag));
            requestRecreate();
        });
    }

    // -------------------------------------------------------------------------
    // Panes
    // -------------------------------------------------------------------------

    private void buildPanes(Image playDim, Image playLit,
                            Image tinyPlayDim, Image tinyPlayLit,
                            Image recordDim, Image recordLit,
                            Image bluePlayLit, Image cameraIcon, Image crosshairIcon,
                            Image floppyDiskIcon, Image folderOpenIcon) {
        // Top-level TabFolder hosts the panes — currently a single
        // "Multifunctional" tab carrying the original three-pane layout
        // (generator on the left, oscilloscope / FFT split on the right).
        // Wrapping everything in a tab keeps room for dedicated single-
        // function tabs (FRF, distortion, etc.) without rewiring the
        // SashForm geometry.
        TabFolder tabFolder = new TabFolder(shell, SWT.NONE);
        TabItem multiTab = new TabItem(tabFolder, SWT.NONE);
        multiTab.setText(I18n.t("tab.multifunctional"));
        Composite multiContent = new Composite(tabFolder, SWT.NONE);
        multiContent.setLayout(new FillLayout());
        multiTab.setControl(multiContent);

        // SWT.SMOOTH on a SashForm makes the contained Sash widgets update
        // the content live while the user drags — without it you get the
        // rubber-band ghost outline and the panes only reflow on release.
        hSplit = new SashForm(multiContent, SWT.HORIZONTAL | SWT.SMOOTH);
        hSplit.setSashWidth(4);

        buildGeneratorPane(hSplit, playDim, playLit, tinyPlayDim, tinyPlayLit,
                           floppyDiskIcon, folderOpenIcon);

        vSplit = new SashForm(hSplit, SWT.VERTICAL | SWT.SMOOTH);
        vSplit.setSashWidth(4);
        buildOscilloscopePane(vSplit, recordDim, recordLit, bluePlayLit, cameraIcon, crosshairIcon,
                              floppyDiskIcon, folderOpenIcon);
        fftPane = new FftPane(vSplit, /*liveCapture*/ true,
                recordDim, recordLit, cameraIcon, crosshairIcon,
                // Read the live shared buffer straight from the
                // controller — the scope view holds a FROZEN snapshot
                // after scope.stop(), which the FFT pane must NOT use
                // for analysis (it would re-analyse the same frame
                // forever).
                () -> (oscController == null) ? null : oscController.getSharedBuffer(),
                () -> Preferences.instance().getGenFrequencyHz(),
                () -> genPane != null && genPane.isRunning(),
                this::openFftScreenshotDialog,
                this::openAdcCalibrationDialog,
                // Capture acquire/release: the FFT pane consumes from the
                // same SignalBuffer the scope owns, but the two panes are
                // independent of each other — pressing FFT record does NOT
                // flip the scope's Record-LED.  Reference counted so the
                // audio device only closes when both panes are stopped.
                this::acquireFftCapture,
                this::releaseFftCapture,
                // FFT length change → re-snap the generator frequency
                // so the running sine slides onto a fresh bin without
                // the user having to toggle the snap checkbox.
                () -> { if (genPane != null) genPane.reapplyFrequencySnap(); },
                this::toggleFftCollapse);

        vSplit.setWeights(new int[]{1, 1});
        hSplit.setWeights(new int[]{1, 3});

        // Tint just the splitter bars #808080 with a #C8C8C8 hover state.
        // We do NOT call setBackground on the SashForm itself — that
        // would propagate the dark tint to every child Composite that
        // hasn't set its own background, producing dark panes on GTK.
        //
        // The wiring is deferred to the first event-loop tick (via
        // {@code Display.asyncExec}) because SashForm creates its
        // {@link Sash} children lazily during the first layout pass
        // (which happens after {@code shell.open()}).  Walking
        // {@code hSplit.getChildren()} here in the constructor would
        // return just {generator, vSplit} with no Sash in between, and
        // the tintSash loop would be a no-op.
        Color sashColor      = new Color(display, 0x80, 0x80, 0x80);
        Color sashHoverColor = new Color(display, 0xC8, 0xC8, 0xC8);
        display.asyncExec(() -> {
            if (hSplit == null || hSplit.isDisposed()) return;
            for (Control c : hSplit.getChildren()) {
                if (c instanceof Sash s) tintSash(s, sashColor, sashHoverColor);
            }
            if (vSplit == null || vSplit.isDisposed()) return;
            for (Control c : vSplit.getChildren()) {
                if (c instanceof Sash s) tintSash(s, sashColor, sashHoverColor);
            }
        });
        shell.addDisposeListener(e -> {
            sashColor.dispose();
            sashHoverColor.dispose();
        });

        // While either osc or fft is collapsed the SashForm only knows its
        // weights, not its pixel size — so a window resize would scale the
        // collapsed pane proportionally and slowly grow it.  Re-pin the
        // weights to {COLLAPSED_PANE_SIZE, remaining-pixels} on every resize
        // so the collapsed pane stays at roughly the title-bar height.
        vSplit.addControlListener(ControlListener.controlResizedAdapter(e -> {
            if (oscCollapsed || fftCollapsed) {
                int total = Math.max(2, vSplit.getSize().y);
                int oscW  = oscCollapsed ? COLLAPSED_PANE_SIZE
                                         : Math.max(1, total - COLLAPSED_PANE_SIZE);
                int fftW  = fftCollapsed ? COLLAPSED_PANE_SIZE
                                         : Math.max(1, total - oscW);
                if (oscCollapsed && !fftCollapsed) fftW = Math.max(1, total - oscW);
                if (fftCollapsed && !oscCollapsed) oscW = Math.max(1, total - fftW);
                vSplit.setWeights(new int[]{ oscW, fftW });
            }
        }));
        // Generator pane = fixed pixel width.  We do NOT want the
        // SashForm's default proportional behaviour: resizing the main
        // window should grow / shrink the oscilloscope + FFT side only,
        // leaving the generator at whatever pixel width the user last
        // dragged it to.  Two pieces:
        //
        //   * Display.addFilter(SWT.Selection) — fires BEFORE the Sash
        //     widget's own listener chain, which is the only spot we
        //     can mutate {@code e.x} before SashForm's internal
        //     handler reads it.  An ordinary Sash.Selection listener
        //     runs after that handler and is too late to clamp.  Here
        //     we clamp the drag at {@link #GEN_MIN_WIDTH} and remember
        //     the new generator width for the resize handler.
        //
        //   * hSplit.controlResized — fires when the WINDOW size
        //     changes.  We recompute the weights to preserve the
        //     tracked pixel width (clamped between GEN_MIN_WIDTH and
        //     avail − GEN_MIN_WIDTH so the osc side keeps a sensible
        //     minimum too).
        Listener sashFilter = e -> {
            if (!(e.widget instanceof Sash sash)) return;
            if (sash.getParent() == hSplit) {
                // While the generator pane is collapsed disallow the drag
                // entirely so the user can't accidentally grow it via the
                // splitter — they must click the title Label to expand.
                if (genCollapsed) { e.doit = false; return; }
                if (e.x < GEN_MIN_WIDTH) e.x = GEN_MIN_WIDTH;
                genPaneWidthPx = e.x;
            } else if (sash.getParent() == vSplit) {
                // Same for the vertical split: collapsed osc or fft locks
                // the divider until the user expands the pane again.
                if (oscCollapsed || fftCollapsed) { e.doit = false; }
            }
        };
        display.addFilter(SWT.Selection, sashFilter);
        shell.addDisposeListener(e -> display.removeFilter(SWT.Selection, sashFilter));

        hSplit.addControlListener(ControlListener.controlResizedAdapter(e -> {
            int total = hSplit.getSize().x;
            if (total <= 0) return;
            int sashW = hSplit.getSashWidth();
            int avail = Math.max(2, total - sashW);
            if (avail < GEN_MIN_WIDTH * 2 && !genCollapsed) return;

            // First real resize after construction — derive the pixel
            // width from the (possibly persisted) initial weights so we
            // honour the saved-prefs proportion as the starting point.
            if (genPaneWidthPx < 0) {
                int[] w = hSplit.getWeights();
                long sum = (long) w[0] + (long) w[1];
                genPaneWidthPx = sum > 0
                        ? (int) ((long) avail * w[0] / sum)
                        : GEN_MIN_WIDTH;
            }

            int gen;
            if (genCollapsed) {
                gen = Math.min(COLLAPSED_PANE_SIZE, Math.max(1, avail - 1));
            } else {
                gen = Math.max(GEN_MIN_WIDTH,
                               Math.min(genPaneWidthPx, avail - GEN_MIN_WIDTH));
            }
            int osc = Math.max(1, avail - gen);
            hSplit.setWeights(new int[]{ gen, osc });
        }));
    }

    /** Generator pane's current pixel width.  {@code -1} means: not yet
     *  measured — the first {@code hSplit.controlResized} captures it
     *  from the current (possibly persisted) weights so the initial
     *  proportion is honoured.  After that the value only changes when
     *  the user drags the sash; window resizes keep it constant. */
    private int genPaneWidthPx = -1;

    /** Minimum width (px) of the generator pane in the horizontal split. */
    private static final int GEN_MIN_WIDTH = 200;

    /** Paints a SashForm splitter bar with the resting tint and swaps to
     *  the hover tint while the mouse is over it.  Three lines of defence
     *  because no single API alone is reliable across Win32 and GTK:
     *  <ul>
     *    <li>{@code setBackground} — does the work on Win32 by itself.</li>
     *    <li>{@code SWT.Paint} listener — explicit fill that overrides
     *        theme styling on GTK (and any other platform that ignores
     *        {@code setBackground} on a {@link Sash}).</li>
     *    <li>{@link MouseTrackListener} — the typed mouse-track API.
     *        Sash widgets reliably deliver enter/exit through this
     *        callback; the untyped {@code addListener(SWT.MouseEnter)}
     *        path is sometimes swallowed by the native drag handler on
     *        Win32, so the cursor entering the bar produced no event.</li>
     *  </ul>
     *  Both colours are owned by the caller — this method only assigns
     *  them. */
    private static void tintSash(Sash sash, Color rest, Color hover) {
        sash.setBackground(rest);
        boolean[] hovered = { false };
        sash.addListener(SWT.Paint, e -> {
            Point sz = sash.getSize();
            e.gc.setBackground(hovered[0] ? hover : rest);
            e.gc.fillRectangle(0, 0, sz.x, sz.y);
        });
        sash.addMouseTrackListener(new MouseTrackAdapter() {
            @Override
            public void mouseEnter(MouseEvent e) {
                if (sash.isDisposed()) return;
                hovered[0] = true;
                sash.setBackground(hover);
                sash.redraw();
            }
            @Override
            public void mouseExit(MouseEvent e) {
                if (sash.isDisposed()) return;
                hovered[0] = false;
                sash.setBackground(rest);
                sash.redraw();
            }
        });
    }

    private void buildGeneratorPane(Composite parent,
                                    Image playDim, Image playLit,
                                    Image tinyPlayDim, Image tinyPlayLit,
                                    Image floppyDiskIcon, Image folderOpenIcon) {
        genPane = new GeneratorPane(parent, playDim, playLit, tinyPlayDim, tinyPlayLit,
                                    floppyDiskIcon, folderOpenIcon, this::toggleGenCollapse);
    }

    private void buildOscilloscopePane(Composite parent,
                                       Image recordDim, Image recordLit,
                                       Image bluePlayLit, Image cameraIcon, Image crosshairIcon,
                                       Image floppyDiskIcon, Image folderOpenIcon) {
        this.oscImages = new OscilloscopePane.Images(recordDim, recordLit, bluePlayLit, cameraIcon, crosshairIcon,
                                                     floppyDiskIcon, folderOpenIcon);
        OscilloscopePane osp = new OscilloscopePane(parent, oscImages, /*liveCapture*/ true,
                this::openOscilloscopeScreenshotDialog,
                this::openAdcCalibrationDialog,
                this::toggleOscCollapse);
        this.oscPaneRef    = osp;
        this.oscPane       = osp.getGroup();
        this.oscView       = osp.getView();
        this.oscController = osp.getController();
    }

    /**
     * Acquires a reference on the shared audio capture device for the FFT
     * pane.  Returns {@code true} if the device is open afterwards.  Does
     * NOT touch the scope's Record-LED — the two panes are user-visible
     * independent of each other; only the underlying device is shared.
     */
    private boolean acquireFftCapture() {
        if (oscController == null) return false;
        return oscController.acquireCapture();
    }

    /** Releases the FFT pane's reference on the shared capture device. */
    private void releaseFftCapture() {
        if (oscController == null) return;
        oscController.releaseCapture();
    }

    /**
     * Opens the screenshot dialog for the oscilloscope pane.  Initial
     * width/height come from preferences (last-used) and fall back to the
     * pane's current pixel size; the chosen size is persisted back on Copy
     * or Save.  The Save-As file dialog remembers the most recent folder.
     */
    private void openOscilloscopeScreenshotDialog() {
        if (oscPane == null || oscPane.isDisposed()) return;
        Rectangle b = oscPane.getBounds();
        Preferences prefs = Preferences.instance();
        new ScreenshotDialog(
                shell,
                prefs.getScreenshotWidth(),  prefs.getScreenshotHeight(),
                b.width, b.height,
                prefs.getScreenshotFolder(),
                this::renderOscilloscopePane,
                (w, h) -> {
                    prefs.setScreenshotWidth(w);
                    prefs.setScreenshotHeight(h);
                    prefs.save();
                },
                folder -> {
                    prefs.setScreenshotFolder(folder);
                    prefs.save();
                }
        ).open();
    }

    /** Opens the ScreenshotDialog targeted at the FFT pane.  Renders the
     *  pane via {@link #renderFftPane(Display, int, int)} — an offscreen
     *  FftPane sized to the user's chosen dimensions, with its tab
     *  body collapsed so only the chart + scrollbars + tab strip are
     *  visible.  Mirrors the scope's screenshot path; the layout
     *  reflows at the target size so the spectrum doesn't get
     *  bitmap-stretched. */
    private void openFftScreenshotDialog() {
        if (fftPane == null) return;
        Composite g = fftPane.getGroup();
        if (g == null || g.isDisposed()) return;
        Rectangle b = g.getBounds();
        Preferences prefs = Preferences.instance();
        new ScreenshotDialog(
                shell,
                prefs.getScreenshotWidth(),  prefs.getScreenshotHeight(),
                b.width, b.height,
                prefs.getScreenshotFolder(),
                this::renderFftPane,
                (w, h) -> {
                    prefs.setScreenshotWidth(w);
                    prefs.setScreenshotHeight(h);
                    prefs.save();
                },
                folder -> {
                    prefs.setScreenshotFolder(folder);
                    prefs.save();
                }
        ).open();
    }

    /** Renders the FFT pane offscreen at the requested dimensions
     *  with its toolbar tab body collapsed.  Mirrors {@link
     *  #renderOscilloscopePane} — fresh FftPane in a hidden Shell, no
     *  live capture, no controller worker.  The screenshot pane's
     *  {@link FftController} is injected with the live pane's latest
     *  {@link FftAnalyzer.Result} so the spectrum + THD table render
     *  the same data the user is currently seeing. */
    private Image renderFftPane(Display d, int targetW, int targetH) {
        targetW = Math.max(1, targetW);
        targetH = Math.max(1, targetH);
        Shell offscreen = new Shell(d, SWT.NO_TRIM);
        offscreen.setLayout(new FillLayout());
        Image cameraIcon    = (fftPane == null) ? null : null;   // unused offscreen
        Image crosshairIcon = null;
        Image recordDim     = (oscImages == null) ? null : oscImages.recordDim;
        Image recordLit     = (oscImages == null) ? null : oscImages.recordLit;
        FftPane shotPane = new FftPane(offscreen, /*liveCapture*/ false,
                recordDim, recordLit, cameraIcon, crosshairIcon,
                () -> (oscController == null) ? null : oscController.getSharedBuffer(),
                () -> Preferences.instance().getGenFrequencyHz(),
                () -> genPane != null && genPane.isRunning(),
                () -> { /* no-op offscreen */ },
                () -> { /* no-op offscreen */ },
                () -> false,           // no capture acquire
                () -> { /* no-op */ },
                () -> { /* no-op */ },
                () -> { /* no-op */ });
        Image output = new Image(d, targetW, targetH);
        try {
            // Copy the live spectrum + collapse the tab body so only
            // the chart, scrollbars and tab strip render.
            if (fftPane != null && fftPane.getController() != null) {
                shotPane.getController().setLastResult(
                        fftPane.getController().getLastResult());
            }
            shotPane.setTabsCollapsed(true);
            // The offscreen pane's controller never runs an analysis
            // tick, so the usual onAnalysisPublished → syncScrollbars
            // chain doesn't fire.  Sync the scrollbar thumbs + view
            // explicitly so they match the live pane's state.
            shotPane.refreshFromPrefs();

            offscreen.setSize(targetW, targetH);
            offscreen.setLocation(-10000, -10000);
            offscreen.open();
            while (d.readAndDispatch()) { /* drain */ }
            // Force every Canvas in the tree to redraw + flush so
            // custom-painted widgets (FlatScrollbar, tab tiles, the
            // FftView itself) have painted into their back buffers
            // before Control.print() walks the tree.  Without this,
            // the screenshot was missing the vertical scrollbar and
            // some tile chrome.
            shotPane.getGroup().redraw();
            while (d.readAndDispatch()) { /* drain */ }
            shotPane.getGroup().update();

            GC outGc = new GC(output);
            try {
                shotPane.getGroup().print(outGc);
                // Control.print() captures CTabFolder's native chrome
                // (tab labels + borders) but doesn't fire the user-
                // registered PaintListener we use to draw the tile
                // row.  Re-introduced now that the collapsed strip
                // is the full 46 px (computeTrim(W, 0) → strip +
                // chrome ≈ 48 px) and the tile row no longer overlaps
                // labels.
                shotPane.paintTabTilesInto(outGc);
            } finally {
                outGc.dispose();
            }
            offscreen.setVisible(false);
        } finally {
            offscreen.dispose();
        }
        return output;
    }

    /**
     * Opens the ADC-calibration dialog.  Reads the current Vrms from the
     * scope view's latest measurement, asks the user for the actual signal
     * amplitude (Vrms in mV / V / dBV), and rescales {@link
     * CsjsoundRecorder#adcFsVoltageRms} so the displayed Vrms matches the
     * entered value.  The new calibration is persisted to preferences so it
     * survives across launches.
     */
    private void openAdcCalibrationDialog() {
        // Prefer the scope's live RMS measurement; fall back to the FFT
        // pane's fundamental Vrms when the scope isn't running but the
        // FFT is — covers the "user records via FFT only" case the
        // original "no live Vrms" dialog dead-ended on.
        Double currentVrms = (oscView == null) ? null : oscView.getLastVrms();
        if (currentVrms == null || currentVrms <= 0 || Double.isNaN(currentVrms)) {
            if (fftPane != null && fftPane.getView() != null) {
                currentVrms = fftPane.getView().getLastVrms();
            }
        }
        if (currentVrms == null || currentVrms <= 0 || Double.isNaN(currentVrms)) {
            MessageBox mb = new MessageBox(shell, SWT.ICON_INFORMATION | SWT.OK);
            mb.setText(I18n.t("calibrate.title"));
            mb.setMessage(I18n.t("calibrate.error.noVrms"));
            mb.open();
            return;
        }
        final double measuredVrms = currentVrms;
        new AdcCalibrationDialog(shell, measuredVrms, actualVrms -> {
            double scale = actualVrms / measuredVrms;
            double newFs = AudioBackend.adcFsVoltageRms * scale;
            AudioBackend.adcFsVoltageRms = newFs;
            Preferences.instance().setAdcFsVoltageRms(newFs);
            Preferences.instance().save();
        }).open();
    }


    /**
     * Renders the oscilloscope pane into a fresh image at
     * {@code (targetW, targetH)} by constructing a brand-new
     * {@link OscilloscopePane} inside a hidden offscreen Shell, resizing that
     * Shell to the target dimensions, letting SWT lay out its children at
     * that size, and printing the result.  Because the chrome is laid out by
     * SWT (rather than bitmap-scaled), toolbar buttons stay at their native
     * pixel size with extra space distributed by the layout, and the scope
     * view's canvas claims all the remaining area.  No widget is stretched.
     *
     * <p>The screenshot pane is wired to the live {@link SignalBuffer} via
     * {@link OscilloscopeView#setBuffer(SignalBuffer)} so the trace, the
     * measurement table, and the condensed strip reflect the current input;
     * a no-op camera-button handler keeps the toolbar layout identical to
     * the live pane without re-opening the screenshot dialog.
     */
    private Image renderOscilloscopePane(Display d, int targetW, int targetH) {
        targetW = Math.max(1, targetW);
        targetH = Math.max(1, targetH);
        if (oscImages == null) {
            return new Image(d, targetW, targetH);
        }
        // Hidden Shell sized to the target.  The ordering matters on GTK:
        // {@code setSize} BEFORE {@code setLocation} — calling
        // {@code setLocation} before the shell has a real size causes
        // some WMs to ignore the negative offset and place it at (0,0)
        // anyway.  Also do NOT call {@code offscreen.layout(true, true)}
        // here: a layout pass before {@code open()} can momentarily
        // realise the shell at the default position before the negative
        // location is applied, flashing a frame on screen.
        Shell offscreen = new Shell(d, SWT.NO_TRIM);
        offscreen.setLayout(new FillLayout());
        OscilloscopePane shotPane = new OscilloscopePane(offscreen, oscImages,
                /*liveCapture*/ false, () -> { /* no-op */ }, () -> { /* no-op */ },
                () -> { /* offscreen — no collapse */ });
        Image output = new Image(d, targetW, targetH);
        try {
            // Wire the screenshot pane to the live signal so the trace and
            // measurement table render the same data the user sees.  The
            // measurement worker on the live view already populates the
            // buffer; the screenshot view just reads from it.
            if (oscView != null && !oscView.isDisposed()) {
                SignalBuffer liveBuffer = oscView.getBuffer();
                if (liveBuffer != null) {
                    shotPane.getView().setBuffer(liveBuffer);
                    shotPane.getCondensed().setBuffer(liveBuffer);
                }
                // The screenshot view has no measurement worker of its own
                // (we don't want a second thread reading from the live
                // buffer just to render one frame).  Copy the live view's
                // cached measurement snapshot + history so the screenshot's
                // measurement table renders the same values the user sees.
                shotPane.getView().copyMeasurementsFrom(oscView);
            }
            // Mirror the live Record-toggle visual state so the screenshot
            // doesn't show a dimmed Record LED while recording is active.
            shotPane.setRecordingState(oscController != null && oscController.isRunning());

            // Resize first, THEN move offscreen.  Doing it in this order
            // means the shell never gets a chance to materialise at the
            // default top-left of the screen at the correct size — the
            // first time the WM sees it, it's already at (-10000, -10000).
            offscreen.setSize(targetW, targetH);
            offscreen.setLocation(-10000, -10000);
            offscreen.open();
            // Pump the display event queue so GTK realises and paints the
            // widget tree.  On Linux, Control.print() can only capture a
            // widget that is currently mapped and has run at least one
            // paint cycle — Windows is lax about this, GTK is not.
            while (d.readAndDispatch()) { /* drain */ }
            shotPane.getGroup().update();

            GC outGc = new GC(output);
            try {
                shotPane.getGroup().print(outGc);
            } finally {
                outGc.dispose();
            }
            // Tear down only after print() — calling setVisible(false)
            // first would un-map the widgets and GTK would draw nothing.
            offscreen.setVisible(false);
        } finally {
            offscreen.dispose();
        }
        return output;
    }

    // -------------------------------------------------------------------------
    // Layout / widget helpers
    // -------------------------------------------------------------------------

    /**
     * Collapses or expands the Oscilloscope pane.  When collapsed, the inner
     * children (view, condensed strip, toolbar) are excluded from the layout
     * and the parent {@link SashForm}'s weights are adjusted so the pane
     * shrinks to roughly the height of its native title bar — leaving the
     * "▶ Oscilloscope" header clickable so the user can expand again.  The
     * pre-collapse weights are restored on expand.
     */
    private void toggleOscCollapse() {
        if (vSplit == null || vSplit.isDisposed())   return;
        if (oscPane == null || oscPane.isDisposed()) return;
        // Only one of osc / fft can be collapsed at a time — if the other
        // is currently collapsed and we're about to collapse osc, expand
        // fft first.
        if (!oscCollapsed && fftCollapsed) {
            toggleFftCollapse();
        }
        oscCollapsed = !oscCollapsed;
        Object oscTitle = oscPane.getData("titleLabel");
        org.eclipse.swt.widgets.Control[] oscChildren = oscPane.getChildren();
        if (oscCollapsed) {
            // Snapshot each non-title child's current visible/exclude state
            // before hiding them — without this the expand path would force
            // every child visible, including the navSlider AND its
            // mutually-exclusive height spacer (each row managed by
            // setNavSliderVisible).
            preCollapseOscChildVisible = new boolean[oscChildren.length];
            preCollapseOscChildExclude = new boolean[oscChildren.length];
            for (int i = 0; i < oscChildren.length; i++) {
                if (oscChildren[i] == oscTitle) continue;
                preCollapseOscChildVisible[i] = oscChildren[i].getVisible();
                if (oscChildren[i].getLayoutData() instanceof GridData gd) {
                    preCollapseOscChildExclude[i] = gd.exclude;
                    gd.exclude = true;
                }
                oscChildren[i].setVisible(false);
            }
        } else if (preCollapseOscChildVisible != null
                && preCollapseOscChildVisible.length == oscChildren.length) {
            for (int i = 0; i < oscChildren.length; i++) {
                if (oscChildren[i] == oscTitle) continue;
                oscChildren[i].setVisible(preCollapseOscChildVisible[i]);
                if (oscChildren[i].getLayoutData() instanceof GridData gd) {
                    gd.exclude = preCollapseOscChildExclude[i];
                }
            }
            preCollapseOscChildVisible = null;
            preCollapseOscChildExclude = null;
        }
        if (oscCollapsed) {
            preCollapseVSplitWeights = vSplit.getWeights();
            int total = Math.max(2, vSplit.getSize().y);
            int oscW  = Math.max(1, COLLAPSED_PANE_SIZE);
            int fftW  = Math.max(1, total - oscW);
            vSplit.setWeights(new int[]{ oscW, fftW });
            PaneTitle.setTitle(oscPane, I18n.t("scope.title.collapsed"));
        } else {
            PaneTitle.setTitle(oscPane, I18n.t("scope.title.expanded"));
            int[] restore = preCollapseVSplitWeights;
            if (restore != null && restore.length == 2 && restore[0] > 0 && restore[1] > 0) {
                vSplit.setWeights(restore);
            } else {
                vSplit.setWeights(new int[]{ 1, 1 });
            }
            preCollapseVSplitWeights = null;
        }
        oscPane.layout(true);
        vSplit.layout(true);
    }

    /**
     * Collapses or expands the FFT pane.  Same shape as
     * {@link #toggleOscCollapse()} but operates on the FFT pane and shares the
     * vertical SashForm so both panes can be collapsed independently.  The
     * vSplit controlListener handles weight maintenance on subsequent resizes.
     */
    private void toggleFftCollapse() {
        if (vSplit == null || vSplit.isDisposed())   return;
        if (fftPane == null) return;
        Composite fftGroup = fftPane.getGroup();
        if (fftGroup == null || fftGroup.isDisposed()) return;
        // Only one of osc / fft can be collapsed at a time — if osc is
        // currently collapsed and we're about to collapse fft, expand osc
        // first.
        if (!fftCollapsed && oscCollapsed) {
            toggleOscCollapse();
        }
        fftCollapsed = !fftCollapsed;
        Object fftTitle = fftGroup.getData("titleLabel");
        org.eclipse.swt.widgets.Control[] fftChildren = fftGroup.getChildren();
        if (fftCollapsed) {
            preCollapseFftChildVisible = new boolean[fftChildren.length];
            preCollapseFftChildExclude = new boolean[fftChildren.length];
            for (int i = 0; i < fftChildren.length; i++) {
                if (fftChildren[i] == fftTitle) continue;
                preCollapseFftChildVisible[i] = fftChildren[i].getVisible();
                if (fftChildren[i].getLayoutData() instanceof GridData gd) {
                    preCollapseFftChildExclude[i] = gd.exclude;
                    gd.exclude = true;
                }
                fftChildren[i].setVisible(false);
            }
        } else if (preCollapseFftChildVisible != null
                && preCollapseFftChildVisible.length == fftChildren.length) {
            for (int i = 0; i < fftChildren.length; i++) {
                if (fftChildren[i] == fftTitle) continue;
                fftChildren[i].setVisible(preCollapseFftChildVisible[i]);
                if (fftChildren[i].getLayoutData() instanceof GridData gd) {
                    gd.exclude = preCollapseFftChildExclude[i];
                }
            }
            preCollapseFftChildVisible = null;
            preCollapseFftChildExclude = null;
        }
        int total = Math.max(2, vSplit.getSize().y);
        if (fftCollapsed) {
            int fftW = Math.max(1, COLLAPSED_PANE_SIZE);
            int oscW = Math.max(1, total - fftW);
            vSplit.setWeights(new int[]{ oscW, fftW });
            PaneTitle.setTitle(fftGroup, I18n.t("fft.title.collapsed"));
        } else {
            PaneTitle.setTitle(fftGroup, I18n.t("fft.title.expanded"));
            // Restore an even-ish split; user can drag from there.
            vSplit.setWeights(new int[]{ 1, 1 });
        }
        fftGroup.layout(true);
        vSplit.layout(true);
    }

    /**
     * Collapses or expands the Generator pane.  Lives in the horizontal
     * SashForm, so collapse shrinks the pane's WIDTH (not height) down to
     * roughly the title-bar height — the title text "▶" stays clickable in
     * that narrow strip so the user can expand again.  The hSplit
     * controlListener (which is pixel-pegged via {@link #genPaneWidthPx})
     * checks {@link #genCollapsed} and uses {@link #COLLAPSED_PANE_SIZE}
     * instead while collapsed.
     */
    private void toggleGenCollapse() {
        if (hSplit == null || hSplit.isDisposed()) return;
        if (genPane == null) return;
        Composite genGroup = genPane.getGroup();
        if (genGroup == null || genGroup.isDisposed()) return;
        genCollapsed = !genCollapsed;
        // Snapshot each child's current visible/exclude state when collapsing
        // and restore that exact state when expanding — this hides the
        // children cleanly (no peek-through past the narrow title strip)
        // while preserving per-mode show/hide (sweep vs non-sweep fields)
        // that lives outside this method.
        org.eclipse.swt.widgets.Control[] children = genGroup.getChildren();
        Object genTitle = genGroup.getData("titleLabel");
        if (genCollapsed) {
            preCollapseGenChildVisible = new boolean[children.length];
            preCollapseGenChildExclude = new boolean[children.length];
            for (int i = 0; i < children.length; i++) {
                // Keep the clickable title Label visible — without it the
                // user can no longer expand the pane.
                if (children[i] == genTitle) continue;
                preCollapseGenChildVisible[i] = children[i].getVisible();
                if (children[i].getLayoutData() instanceof GridData gd) {
                    preCollapseGenChildExclude[i] = gd.exclude;
                    gd.exclude = true;
                }
                children[i].setVisible(false);
            }
        } else if (preCollapseGenChildVisible != null
                && preCollapseGenChildVisible.length == children.length) {
            for (int i = 0; i < children.length; i++) {
                if (children[i] == genTitle) continue;
                children[i].setVisible(preCollapseGenChildVisible[i]);
                if (children[i].getLayoutData() instanceof GridData gd) {
                    gd.exclude = preCollapseGenChildExclude[i];
                }
            }
            preCollapseGenChildVisible = null;
            preCollapseGenChildExclude = null;
        }
        int total = Math.max(2, hSplit.getSize().x);
        int sashW = hSplit.getSashWidth();
        int avail = Math.max(2, total - sashW);
        if (genCollapsed) {
            if (genPaneWidthPx >= GEN_MIN_WIDTH) preCollapseGenWidth = genPaneWidthPx;
            int gen = Math.min(COLLAPSED_PANE_SIZE, Math.max(1, avail - 1));
            int osc = Math.max(1, avail - gen);
            hSplit.setWeights(new int[]{ gen, osc });
            PaneTitle.setTitle(genGroup, I18n.t("generator.title.collapsed"));
        } else {
            PaneTitle.setTitle(genGroup, I18n.t("generator.title.expanded"));
            if (preCollapseGenWidth >= GEN_MIN_WIDTH) {
                genPaneWidthPx = preCollapseGenWidth;
            } else if (genPaneWidthPx < GEN_MIN_WIDTH) {
                genPaneWidthPx = GEN_MIN_WIDTH;
            }
            int gen = Math.max(GEN_MIN_WIDTH,
                               Math.min(genPaneWidthPx, avail - GEN_MIN_WIDTH));
            int osc = Math.max(1, avail - gen);
            hSplit.setWeights(new int[]{ gen, osc });
        }
        genGroup.layout(true);
        hSplit.layout(true);
    }

    // -------------------------------------------------------------------------
    // Icon painting
    // -------------------------------------------------------------------------

    /**
     * Builds an {@code n × n} transparent image with alpha channel ready to
     * be painted by the LED creators.  Returned image owns its pixel buffer;
     * dispose at shell shutdown.
     */
    private Image newTransparentIcon(int size) {
        PaletteData palette = new PaletteData(0xFF0000, 0x00FF00, 0x0000FF);
        ImageData data = new ImageData(size, size, 32, palette);
        data.alphaData = new byte[size * size]; // fully transparent
        return new Image(display, data);
    }

    /**
     * Draws a round LED at the given pixel {@code size} with vertical-gradient
     * shading (lighter top → darker bottom), a darker outer rim, and (when
     * {@code lit}) a small white specular highlight near the top-left to
     * suggest a 3D dome.
     */
    private Image createRecordLed(int r, int g, int b, boolean lit, int size) {
        Image img = newTransparentIcon(size);
        GC gc = new GC(img);
        gc.setAdvanced(true);
        gc.setAntialias(SWT.ON);

        int dim = lit ? 0 : 50;            // "off" LED is darkened to ~50 % intensity
        int br = scale(r, 100 - dim);
        int bg = scale(g, 100 - dim);
        int bb = scale(b, 100 - dim);

        Color rim = new Color(display, scale(br, 30), scale(bg, 30), scale(bb, 30));
        gc.setBackground(rim);
        gc.fillOval(0, 0, size, size);
        rim.dispose();

        int inset = 2;
        int diam  = size - 2 * inset;
        Path clip = new Path(display);
        clip.addArc(inset, inset, diam, diam, 0, 360);
        gc.setClipping(clip);
        Color top = new Color(display, lighten(br, 60), lighten(bg, 60), lighten(bb, 60));
        Color bot = new Color(display, scale(br, 60),   scale(bg, 60),   scale(bb, 60));
        gc.setForeground(top);
        gc.setBackground(bot);
        gc.fillGradientRectangle(inset, inset, diam, diam, true);
        top.dispose();
        bot.dispose();
        gc.setClipping((Path) null);
        clip.dispose();

        if (lit) {
            Color spec = new Color(display, 255, 255, 255);
            gc.setBackground(spec);
            gc.setAlpha(180);
            gc.fillOval(size / 4, size / 5, size / 3, size / 5);
            gc.setAlpha(255);
            spec.dispose();
        }

        gc.dispose();
        return img;
    }

    /**
     * Draws a right-pointing triangle "play" arrow at the given pixel
     * {@code size} with edge highlights and shadows to suggest 3D bevelling.
     * Same dim/lit semantics as {@link #createRecordLed}.
     */
    private Image createPlayLed(int r, int g, int b, boolean lit, int size) {
        Image img = newTransparentIcon(size);
        GC gc = new GC(img);
        gc.setAdvanced(true);
        gc.setAntialias(SWT.ON);

        int dim = lit ? 0 : 50;
        int br = scale(r, 100 - dim);
        int bg = scale(g, 100 - dim);
        int bb = scale(b, 100 - dim);

        // Scale margins down for smaller icons so the triangle still fills nicely.
        int marginL = Math.max(2, size / 5);
        int marginV = Math.max(2, size / 8);
        int tipX    = size - Math.max(2, size / 8);
        int midY    = size / 2;
        int[] body  = { marginL, marginV,
                        marginL, size - marginV,
                        tipX,    midY };

        Color base = new Color(display, br, bg, bb);
        gc.setBackground(base);
        gc.fillPolygon(body);
        base.dispose();

        Path clip = new Path(display);
        clip.moveTo(body[0], body[1]);
        clip.lineTo(body[4], body[5]);
        clip.lineTo(body[0] + (body[4] - body[0]) / 2, body[5]);
        clip.close();
        gc.setClipping(clip);
        Color hi = new Color(display, lighten(br, 60), lighten(bg, 60), lighten(bb, 60));
        gc.setBackground(hi);
        gc.fillPolygon(body);
        hi.dispose();
        gc.setClipping((Path) null);
        clip.dispose();

        gc.setLineWidth(2);
        Color edgeHi  = new Color(display, lighten(br, 80), lighten(bg, 80), lighten(bb, 80));
        Color edgeLow = new Color(display, scale(br, 30),   scale(bg, 30),   scale(bb, 30));
        gc.setForeground(edgeHi);
        gc.drawLine(body[0], body[1], body[4], body[5]);
        gc.setForeground(edgeLow);
        gc.drawLine(body[2], body[3], body[4], body[5]);
        gc.drawLine(body[0], body[1], body[2], body[3]);
        edgeHi.dispose();
        edgeLow.dispose();

        gc.dispose();
        return img;
    }

    /** Linear-scale an 8-bit channel by {@code pct} percent (clamped 0..255). */
    private int scale(int channel, int pct) {
        int v = channel * pct / 100;
        return Math.max(0, Math.min(255, v));
    }

    /** Brighten an 8-bit channel toward 255 by {@code pct} percent of the gap. */
    private int lighten(int channel, int pct) {
        return Math.max(0, Math.min(255, channel + (255 - channel) * pct / 100));
    }
}
