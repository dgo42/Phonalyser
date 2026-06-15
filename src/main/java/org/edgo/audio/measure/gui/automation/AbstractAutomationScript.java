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

package org.edgo.audio.measure.gui.automation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.gui.MainWindow;
import org.edgo.audio.measure.gui.fft.FftPane;
import org.edgo.audio.measure.gui.generator.GeneratorPane;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.preferences.PreferencesDialog;
import org.edgo.audio.measure.gui.registry.UiNode;
import org.edgo.audio.measure.gui.registry.UiRegistry;
import org.edgo.audio.measure.gui.scope.ScopePane;
import org.edgo.audio.measure.preferences.Preferences;

import lombok.extern.log4j.Log4j2;

/**
 * Base class for GUI-automation scripts — sequences that drive the live
 * application unattended to produce documentation screenshots (or to back
 * an integration test).  A script is an ordinary compiled class extending
 * this one and overriding {@link #run()}; it is selected at launch with
 * {@code --automation=<fully.qualified.ClassName>} and executed by
 * {@link AutomationRunner} on its own background thread while the SWT
 * event loop runs undisturbed on the main thread.
 *
 * <p>Threading contract: {@link #run()} executes OFF the UI thread — it
 * may sleep freely ({@link #waitSeconds}).  Every helper that touches
 * widgets marshals itself through {@link #ui(Runnable)}
 * ({@code Display.syncExec}), which blocks until the UI work completed,
 * so script steps stay strictly ordered.  Never call widget methods
 * directly from {@link #run()}.
 *
 * <p>Configuration is NOT the script's job: launch the application from a
 * working directory holding a prepared {@code preferences.yaml} (devices,
 * generator settings, FFT length, window size, …) — the script only
 * sequences: start engines, wait, switch language, snapshot.
 *
 * <p>For reproducible images across machines run with
 * {@code -Dswt.autoScale=100} so OS display scaling doesn't change pixel
 * geometry.
 */
@Log4j2
public abstract class AbstractAutomationScript {

    /** Width/height value for {@link #snapshot(Control, int, int, String)}
     *  meaning "keep the control's current on-screen size". */
    protected static final int KEEP_SIZE = 0;

    private static final long MILLIS_PER_SECOND = 1_000;

    protected final Display    display;
    protected final MainWindow window;

    /** Shell of the Preferences dialog while {@link #openPreferences()} keeps
     *  it up, so {@link #closePreferences()} can dispose it; null otherwise. */
    private Shell preferencesShell;

    protected AbstractAutomationScript(Display display, MainWindow window) {
        this.display = display;
        this.window  = window;
    }

    /** The script body — runs on the automation thread.  Throwing aborts
     *  the run; {@link AutomationRunner} logs the failure and closes the
     *  application either way. */
    protected abstract void run() throws Exception;

    // -------------------------------------------------------------------------
    // Sequencing
    // -------------------------------------------------------------------------

    /** Runs {@code action} on the UI thread and waits for it to complete
     *  ({@code Display.syncExec}) — the only legal way for a script to
     *  touch widgets.  No-op once the display is disposed. */
    protected final void ui(Runnable action) {
        if (display.isDisposed()) return;
        display.syncExec(action);
    }

    /** Sleeps the automation thread; the UI keeps running (measuring,
     *  averaging, repainting) the whole time. */
    protected final void waitSeconds(double seconds) throws InterruptedException {
        Thread.sleep(Math.max(0, Math.round(seconds * MILLIS_PER_SECOND)));
    }

    /** Switches the UI language in place (bundle swap + full content
     *  rebuild).  The persisted language preference is NOT touched — a
     *  doc-generation run must not change the user's configuration.  The
     *  audio engines keep running through the rebuild, so the plotted
     *  data is identical across all languages captured in one run. */
    protected final void language(String tag) {
        ui(() -> {
            I18n.setLocale(Locale.forLanguageTag(tag));
            window.rebuildContent();
        });
        log.info("Automation: language switched to {}", tag);
    }

    // -------------------------------------------------------------------------
    // Engine control
    // -------------------------------------------------------------------------

    /** Starts the DDS tone with the Play button + ON-AIR visuals in sync. */
    protected final void startGenerator() {
        ui(() -> genPane().startTone());
    }

    /** Engages the oscilloscope's live capture with the Record LED lit. */
    protected final void startScope() {
        ui(() -> oscPane().engageRecord());
    }

    /** Engages FFT recording with the Record LED lit. */
    protected final void startFft() {
        ui(() -> fftPane().engageRecord());
    }

    // -------------------------------------------------------------------------
    // Snapshots
    // -------------------------------------------------------------------------

    /** Prints {@code control} (any widget — e.g. {@code genPane().getGroup()})
     *  at its current on-screen size into a PNG.  Occlusion-proof: renders
     *  through {@code Control.print}, not a screen copy. */
    protected final void snapshot(Control control, String pngPath) {
        snapshot(control, KEEP_SIZE, KEEP_SIZE, pngPath);
    }

    /** {@link #snapshot(Control, String)} variant that scales the printed
     *  image to exactly {@code width × height} pixels (high-quality
     *  bitmap interpolation).  Pass {@link #KEEP_SIZE} for both to keep
     *  the control's on-screen size.  Note this SCALES pixels — for the
     *  scope / FFT panes prefer {@link #snapshotScopePane} /
     *  {@link #snapshotFftPane}, which re-LAYOUT the pane at the target
     *  size instead, keeping text and chrome crisp. */
    protected final void snapshot(Control control, int width, int height, String pngPath) {
        ImageData[] data = new ImageData[1];
        ui(() -> {
            Point size = control.getSize();
            Image printed = new Image(control.getDisplay(),
                    Math.max(1, size.x), Math.max(1, size.y));
            GC gc = new GC(printed);
            try {
                control.print(gc);
            } finally {
                gc.dispose();
            }
            Image result = printed;
            if (width > 0 && height > 0 && (width != size.x || height != size.y)) {
                result = scaled(printed, width, height);
                printed.dispose();
            }
            data[0] = result.getImageData();
            result.dispose();
        });
        savePng(data[0], pngPath);
    }

    /** High-quality bitmap scale of {@code source} to {@code width × height};
     *  the caller disposes the source.  UI thread only. */
    private Image scaled(Image source, int width, int height) {
        Rectangle bounds = source.getBounds();
        Image out = new Image(source.getDevice(), width, height);
        GC gc = new GC(out);
        try {
            gc.setAntialias(SWT.ON);
            gc.setInterpolation(SWT.HIGH);
            gc.drawImage(source, 0, 0, bounds.width, bounds.height,
                    0, 0, width, height);
        } finally {
            gc.dispose();
        }
        return out;
    }

    /** Renders the oscilloscope pane at exactly {@code width × height}
     *  pixels into a PNG — independent of the live window's sash layout,
     *  so the help images come out the same size on every run. */
    protected final void snapshotScopePane(int width, int height, String pngPath) {
        snapshotRendered(pngPath,
                () -> oscPane().renderOffscreen(display, width, height));
    }

    /** Renders the FFT pane at exactly {@code width × height} pixels into
     *  a PNG (toolbar tab body collapsed, tiles overlaid — same output as
     *  the in-app screenshot dialog). */
    protected final void snapshotFftPane(int width, int height, String pngPath) {
        snapshotRendered(pngPath,
                () -> fftPane().renderOffscreen(display, width, height));
    }

    /** Loads a {@code .fft} spectrum file into the live FFT pane so the next
     *  {@link #snapshotFftPane} renders that real spectrum (no live capture
     *  needed) — used to drive a frequency-zoom animation over a saved IMD /
     *  sweep capture. */
    protected final void loadFftSpectrum(String path) {
        ui(() -> fftPane().loadSpectrum(path));
    }

    /** Sets the FFT plot's visible frequency window (Hz).  The offscreen
     *  renderer re-reads these on every {@link #snapshotFftPane}, so stepping
     *  the window between snapshots animates a pan / zoom across the loaded
     *  spectrum. */
    protected final void setFftFreqRange(double minHz, double maxHz) {
        ui(() -> {
            Preferences prefs = Preferences.instance();
            prefs.setFftFreqMinHz(minHz);
            prefs.setFftFreqMaxHz(maxHz);
        });
    }

    /** Runs a pane's offscreen renderer on the UI thread and saves the
     *  produced image. */
    private void snapshotRendered(String pngPath, Supplier<Image> renderer) {
        ImageData[] data = new ImageData[1];
        ui(() -> {
            Image image = renderer.get();
            try {
                data[0] = image.getImageData();
            } finally {
                image.dispose();
            }
        });
        savePng(data[0], pngPath);
    }

    /** Writes {@code data} as PNG, creating parent directories as needed.
     *  Runs on the automation thread — encoding multi-MB images must not
     *  stall the UI. */
    private void savePng(ImageData data, String pngPath) {
        if (data == null) {
            log.warn("Automation: no image data for {} (display disposed?)", pngPath);
            return;
        }
        try {
            Path out = Paths.get(pngPath);
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            ImageLoader loader = new ImageLoader();
            loader.data = new ImageData[]{ data };
            loader.save(out.toString(), SWT.IMAGE_PNG);
            log.info("Automation: snapshot saved to {}", out.toAbsolutePath());
        } catch (Exception ex) {
            log.error("Automation: saving snapshot {} failed", pngPath, ex);
        }
    }

    // -------------------------------------------------------------------------
    // Component registry (address the UI by path — see UiRegistry).  A script
    // can select a tab, maximize a pane, or screenshot any registered
    // component without ever holding a widget reference.
    // -------------------------------------------------------------------------

    /** Lists every registered component path in the log — the "lookup" /
     *  discovery step: run it once to see what is addressable, then
     *  {@link #screenshot} the paths you want. */
    protected final void logComponents() {
        StringBuilder sb = new StringBuilder("Registered UI components:");
        for (String p : UiRegistry.instance().componentPaths()) {
            sb.append("\n  ").append(p);
        }
        log.info(sb.toString());
    }

    /** Selects / reveals the component at {@code path} (e.g. brings a settings
     *  tab to the front).  No-op when the path is unknown or the component has
     *  no activate capability. */
    protected final void activate(String path) {
        ui(() -> {
            UiNode node = UiRegistry.instance().resolve(path);
            if (node != null && node.getActivate() != null) node.getActivate().run();
            else log.warn("Automation: activate — no activatable component at '{}'", path);
        });
    }

    /** Gives the pane at {@code path} the whole view, if it is maximizable. */
    protected final void maximize(String path) {
        ui(() -> {
            UiNode node = UiRegistry.instance().resolve(path);
            if (node != null && node.getMaximize() != null) node.getMaximize().run();
            else log.warn("Automation: maximize — no maximizable component at '{}'", path);
        });
    }

    /** Undoes a previous {@link #maximize} on the component at {@code path}. */
    protected final void restore(String path) {
        ui(() -> {
            UiNode node = UiRegistry.instance().resolve(path);
            if (node != null && node.getRestore() != null) node.getRestore().run();
        });
    }

    /** Captures the registered component at {@code path} into a PNG.  No-op
     *  (with a warning) when the path is unknown or has no control. */
    protected final void screenshot(String path, String pngPath) {
        ImageData[] out = new ImageData[1];
        ui(() -> {
            UiNode node = UiRegistry.instance().resolve(path);
            if (node != null && node.getControl() != null && !node.getControl().isDisposed()) {
                out[0] = printToImageData(node.getControl());
            } else {
                log.warn("Automation: screenshot — no component / control at '{}'", path);
            }
        });
        savePng(out[0], pngPath);
    }

    // -------------------------------------------------------------------------
    // Modal dialogs.  A dialog isn't part of the main-tab tree, so a script
    // opens it explicitly; while it's up it self-registers its tabs (e.g.
    // "preferences/tabs/fft"), so the same activate / screenshot registry
    // calls drive it.  Always pair openX with closeX.
    // -------------------------------------------------------------------------

    /** Opens the modal Preferences dialog and leaves it up.  Its tab folder
     *  registers under {@code preferences} (the screenshot target) and each
     *  tab under {@code preferences/tabs/*}, so {@link #activate} selects a
     *  tab and {@link #screenshot}{@code ("preferences", …)} snapshots the
     *  dialog.  Pair with {@link #closePreferences()}. */
    protected final void openPreferences() {
        ui(() -> preferencesShell =
                new PreferencesDialog(genPane().getGroup().getShell()).open());
    }

    /** Closes the dialog opened by {@link #openPreferences()} (no-op if none). */
    protected final void closePreferences() {
        ui(() -> {
            if (preferencesShell != null && !preferencesShell.isDisposed()) {
                preferencesShell.dispose();
            }
            preferencesShell = null;
        });
    }

    // -------------------------------------------------------------------------
    // Window framing and composite capture (used by the help-screenshot
    // scripts; kept here so a body-only script can drive them without
    // touching any application type itself)
    // -------------------------------------------------------------------------

    /** Sets the generator pane's preferred width (px) — its 200 px minimum
     *  is too cramped to read or annotate.  Apply BEFORE {@link #language},
     *  whose content rebuild seeds the pane width from this preference. */
    protected final void setGeneratorPaneWidth(int px) {
        ui(() -> Preferences.instance().setGenPaneWidth(px));
    }

    /** Sets the generator's signal form by enum name (e.g. {@code "DUAL_TONE"},
     *  {@code "LINEAR_SWEEP"}).  The pane configures its per-form controls from
     *  this preference when it is (re)built, so call it BEFORE {@link #language}
     *  (whose content rebuild then comes up in the chosen form). */
    protected final void setGeneratorForm(String formName) {
        ui(() -> Preferences.instance().setGenSignalForm(GenSignalForm.valueOf(formName)));
    }

    /** Forces the main window to an exact pixel size and re-lays it out. */
    protected final void sizeMainWindow(int width, int height) {
        ui(() -> {
            Shell shell = genPane().getGroup().getShell();
            shell.setSize(width, height);
            shell.layout(true, true);
        });
    }

    /** Collapses the oscilloscope + FFT settings-tab bodies so their traces
     *  dominate an overview shot instead of being squeezed by an expanded
     *  settings panel. */
    protected final void collapseScopeAndFftTabs() {
        ui(() -> {
            oscPane().setToolbarTabsCollapsed(true);
            fftPane().setTabsCollapsed(true);
        });
    }

    /** Captures the whole multifunctional tab (generator | scope / fft) — the
     *  smallest composite containing all three panes, so it is independent of
     *  tab orientation — into a PNG. */
    protected final void captureMultifunctional(String pngPath) {
        ImageData[] out = new ImageData[1];
        ui(() -> out[0] = printToImageData(commonAncestorOfPanes()));
        savePng(out[0], pngPath);
    }

    /** Captures the generator pane, cropped just below its last row of
     *  content (the Calibrate-DAC + Play buttons), into a PNG. */
    protected final void captureGeneratorCropped(String pngPath) {
        ImageData[] out = new ImageData[1];
        ui(() -> {
            ImageData full = printToImageData(genPane().getGroup());
            out[0] = cropToContentBottom(full, GEN_CROP_MARGIN_PX);
        });
        savePng(out[0], pngPath);
    }

    /** Margin (px) left below the last content row by
     *  {@link #cropToContentBottom}. */
    private static final int GEN_CROP_MARGIN_PX = 12;

    /** Smallest composite that contains all three measurement panes (the
     *  multifunctional tab content), found by walking parent chains. */
    private Composite commonAncestorOfPanes() {
        Composite[] holder = new Composite[1];
        ui(() -> {
            Control gen = genPane().getGroup();
            Control osc = oscPane().getGroup();
            Control fft = fftPane().getGroup();
            holder[0] = commonAncestor(commonAncestor(gen, osc), fft);
        });
        return holder[0];
    }

    private Composite commonAncestor(Control a, Control b) {
        Set<Composite> ancestors = new HashSet<>();
        for (Composite p = a.getParent(); p != null; p = p.getParent()) ancestors.add(p);
        for (Composite p = b.getParent(); p != null; p = p.getParent()) {
            if (ancestors.contains(p)) return p;
        }
        return a.getParent();
    }

    /** Renders a control through {@code Control.print} (occlusion-proof, unlike
     *  a screen grab) and returns its pixels.  UI thread only. */
    private ImageData printToImageData(Control c) {
        Point size = c.getSize();
        Image img = new Image(c.getDisplay(), Math.max(1, size.x), Math.max(1, size.y));
        GC gc = new GC(img);
        try {
            c.print(gc);
        } finally {
            gc.dispose();
        }
        ImageData data = img.getImageData();
        img.dispose();
        return data;
    }

    /** Returns {@code src} cropped to {@code lastContentRow + margin} in height,
     *  trimming the empty pane background below the last control row.  The
     *  background colour is the per-channel median of several deep lower-middle
     *  samples (a single corner pixel can be a 1px white border artifact); the
     *  scan skips the bottom few rows and requires more than a handful of
     *  non-background pixels so a stray border line isn't read as content.
     *  UI thread only. */
    private ImageData cropToContentBottom(ImageData src, int margin) {
        int cx = src.width / 2;
        int[] rs = new int[5], gs = new int[5], bs = new int[5];
        int[] ys = { src.height - 6, src.height - 10, src.height - 15,
                     src.height - 22, src.height - 30 };
        for (int i = 0; i < ys.length; i++) {
            RGB c = src.palette.getRGB(src.getPixel(cx, Math.max(0, ys[i])));
            rs[i] = c.red; gs[i] = c.green; bs[i] = c.blue;
        }
        Arrays.sort(rs); Arrays.sort(gs); Arrays.sort(bs);
        RGB bg = new RGB(rs[2], gs[2], bs[2]);

        int contentBottom = -1;
        int[] row = new int[src.width];
        for (int y = src.height - 6; y >= 0; y--) {
            src.getPixels(0, y, src.width, row, 0);
            int n = 0;
            for (int x = 0; x < src.width; x++) {
                RGB p = src.palette.getRGB(row[x]);
                if (Math.abs(p.red - bg.red) + Math.abs(p.green - bg.green)
                        + Math.abs(p.blue - bg.blue) > 24 && ++n > 6) break;
            }
            if (n > 6) { contentBottom = y; break; }
        }
        if (contentBottom < 0 || contentBottom > src.height - 30) return src;
        int cropH = Math.min(src.height, contentBottom + margin);

        Image full = new Image(display, src);
        Image cut = new Image(display, src.width, cropH);
        GC gc = new GC(cut);
        try {
            gc.drawImage(full, 0, 0, src.width, cropH, 0, 0, src.width, cropH);
        } finally {
            gc.dispose();
        }
        ImageData result = cut.getImageData();
        full.dispose();
        cut.dispose();
        return result;
    }

    // -------------------------------------------------------------------------
    // Pane access (resolved fresh on every call — a language switch
    // rebuilds the panes, invalidating earlier references)
    // -------------------------------------------------------------------------

    protected final GeneratorPane genPane() {
        return window.getMainTab().getGenPane();
    }

    protected final ScopePane oscPane() {
        return window.getMainTab().getOscPane();
    }

    protected final FftPane fftPane() {
        return window.getMainTab().getFftPane();
    }
}
