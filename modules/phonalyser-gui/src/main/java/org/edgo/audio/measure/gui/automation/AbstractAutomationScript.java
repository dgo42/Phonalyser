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

package org.edgo.audio.measure.gui.automation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
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
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.edgo.audio.measure.enums.AudioBackendType;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.gui.MainWindow;
import org.edgo.audio.measure.gui.common.BackendSettingsRegistry;
import org.edgo.audio.measure.gui.common.BackendSettingsUi;
import org.edgo.audio.measure.gui.common.CalibrationDialog;
import org.edgo.audio.measure.gui.common.RemoteBackendRegistry;
import org.edgo.audio.measure.gui.common.RemoteBackendUi;
import org.edgo.audio.measure.gui.fft.FftPane;
import org.edgo.audio.measure.gui.fft.predistortion.PredistortionWizardDialog;
import org.edgo.audio.measure.gui.freqresp.FreqRespPane;
import org.edgo.audio.measure.gui.freqresp.TuneNotchWizardDialog;
import org.edgo.audio.measure.gui.generator.GeneratorPane;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.preferences.CardEditorDialog;
import org.edgo.audio.measure.gui.preferences.PreferencesDialog;
import org.edgo.audio.measure.gui.registry.UiNode;
import org.edgo.audio.measure.gui.registry.UiRegistry;
import org.edgo.audio.measure.gui.scope.ScopePane;
import org.edgo.audio.measure.preferences.Preferences;

import lombok.extern.log4j.Log4j2;

/**
 * Base class for GUI-automation scripts - sequences that drive the live
 * application unattended to produce documentation screenshots (or to back
 * an integration test).  A script is an ordinary compiled class extending
 * this one and overriding {@link #run()}; it is selected at launch with
 * {@code --automation=<fully.qualified.ClassName>} and executed by
 * {@link AutomationRunner} on its own background thread while the SWT
 * event loop runs undisturbed on the main thread.
 *
 * <p>Threading contract: {@link #run()} executes OFF the UI thread - it
 * may sleep freely ({@link #waitSeconds}).  Every helper that touches
 * widgets marshals itself through {@link #ui(Runnable)}
 * ({@code Display.syncExec}), which blocks until the UI work completed,
 * so script steps stay strictly ordered.  Never call widget methods
 * directly from {@link #run()}.
 *
 * <p>Configuration is NOT the script's job: launch the application from a
 * working directory holding a prepared {@code preferences.yaml} (devices,
 * generator settings, FFT length, window size, ...) - the script only
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

    /** System property naming the TSV file {@link #finishAutomation} writes.
     *  Absent - every ordinary run - nothing is written. */
    private static final String RESULTS_PROPERTY = "phonalyser.automation.results";
    /** System-property prefix behind {@link #param(String)}.  A script body may
     *  not name {@code System} (sandbox rule), so this is the ONE sanctioned
     *  bridge from the launching engine into a script. */
    private static final String PARAM_PREFIX = "phonalyser.automation.param.";
    /** How often {@link #waitUntil} re-evaluates its condition. */
    private static final long WAIT_POLL_MS = 100;

    /** SWT's left mouse button. */
    private static final int MOUSE_BUTTON_LEFT = 1;
    /** Interpolated moves posted between a drag's press and release - enough
     *  for a view tracking a rubberband to see it grow. */
    private static final int DRAG_STEPS = 8;
    /** Pause after each posted input event, letting the OS queue drain into the
     *  application before the next one is posted. */
    private static final long GESTURE_SETTLE_MS = 60;
    /** Stands in for the condition's description when the caller gave none. */
    private static final String UNNAMED_WAIT = "an unnamed condition";

    /** How long {@link #screenshotServerList} lets the server list settle before
     *  printing it: long enough for the discovery probe it fires on opening to be
     *  answered and for a couple of beacon periods to land, so a bench that is
     *  running is actually in the table when the shot is taken. */
    private static final double SERVER_LIST_SETTLE_SECONDS = 3;

    protected final Display    display;
    protected final MainWindow window;

    /** Everything this run checked, and the verdict it adds up to.  One per
     *  script; the {@code check...} verbs below are its whole public face. */
    private final AutomationChecks checks = new AutomationChecks();

    /** Shell of the Preferences dialog while {@link #openPreferences()} keeps
     *  it up, so {@link #closePreferences()} can dispose it; null otherwise. */
    private Shell preferencesShell;
    /** The Preferences dialog instance behind {@link #preferencesShell}, so
     *  {@link #openInputCardEditor()} can drive its card sections; null otherwise. */
    private PreferencesDialog preferencesDialog;
    /** The card editor opened for capture by {@link #openInputCardEditor()};
     *  snapshotted via its content composite and disposed by
     *  {@link #closeInputCardEditor()}; null otherwise. */
    private CardEditorDialog cardEditorDialog;
    /** The shared voltage-calibration dialog opened for capture (DAC or ADC form);
     *  snapshotted via its content composite and disposed by
     *  {@link #closeCalibration()}; null otherwise. */
    private CalibrationDialog calibrationDialog;
    /** A backend's own settings panel opened for capture; snapshotted via its
     *  content and disposed by {@link #closeQa40xSettings()}; null otherwise.
     *  Held through the service interface, never as a concrete dialog: the panel
     *  lives in the module that owns that backend's UI, and THAT module depends on
     *  this one - naming its type here would close the loop. */
    private BackendSettingsUi backendSettingsUi;
    /** Shell of the Tune-notch wizard while {@link #openTuneNotch()} keeps it
     *  up; disposed by {@link #closeTuneNotch()}; null otherwise. */
    private Shell tuneNotchShell;
    /** The wizard's content composite - printed by {@link #screenshotTuneNotch}
     *  (Control.print renders a Composite's children; a Shell prints blank). */
    private Control tuneNotchContent;
    /** The DAC-predistortion wizard while {@link #openPredistortion()} keeps it
     *  up; driven by the set-target / start / wait / re-translate / screenshot
     *  hooks; closed by {@link #closePredistortion()}. */
    private PredistortionWizardDialog predistortionDialog;

    protected AbstractAutomationScript(Display display, MainWindow window) {
        this.display = display;
        this.window  = window;
    }

    /** The script body - runs on the automation thread.  Throwing aborts
     *  the run; {@link AutomationRunner} logs the failure and closes the
     *  application either way. */
    protected abstract void run() throws Exception;

    // -------------------------------------------------------------------------
    // Sequencing
    // -------------------------------------------------------------------------

    /** Runs {@code action} on the UI thread and waits for it to complete
     *  ({@code Display.syncExec}) - the only legal way for a script to
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

    /**
     * Blocks until {@code condition} answers true, or THROWS after
     * {@code timeoutSeconds} - which aborts the run and, through
     * {@link AutomationRunner}, makes the process exit non-zero.  That is the
     * point: an unattended scenario that waited forever for something that
     * never came would hang the whole suite instead of reporting it.
     *
     * <p>The condition is evaluated ON THE AUTOMATION THREAD, once immediately
     * and then every {@value #WAIT_POLL_MS} ms.  It therefore decides its own
     * marshalling: the {@code fft...} accessors below already go through
     * {@link #ui(Runnable)}, and a plain pane getter is safe to read
     * unwrapped, but anything touching a WIDGET must be wrapped by the
     * condition itself.
     *
     * @throws IllegalStateException when the timeout expires
     */
    protected final void waitUntil(BooleanSupplier condition, double timeoutSeconds)
            throws InterruptedException {
        waitUntil(UNNAMED_WAIT, condition, timeoutSeconds);
    }

    /**
     * {@link #waitUntil(BooleanSupplier, double)} with a name for the timeout
     * message.  Worth the extra argument in any scenario with more than one
     * wait: a lambda cannot describe itself, so an unnamed wait that never
     * comes true reports only how long it waited, and the log then does not say
     * WHICH measurement never arrived.
     *
     * @throws IllegalStateException when the timeout expires, naming
     *         {@code what}
     */
    protected final void waitUntil(String what, BooleanSupplier condition, double timeoutSeconds)
            throws InterruptedException {
        long deadline = System.currentTimeMillis()
                + Math.max(0, Math.round(timeoutSeconds * MILLIS_PER_SECOND));
        while (true) {
            if (condition.getAsBoolean()) return;
            if (System.currentTimeMillis() >= deadline) {
                throw new IllegalStateException("Automation: waited " + timeoutSeconds
                        + " s for " + what + ", still false");
            }
            Thread.sleep(WAIT_POLL_MS);
        }
    }

    // -------------------------------------------------------------------------
    // Checks - what turns a scripted run into a TEST.  Every one of them
    // RECORDS AND CONTINUES: a red check must not abort the scenario, so one
    // run reports every value it was asked about.  The verdict is collected
    // once, at the end, by finishAutomation().
    // -------------------------------------------------------------------------

    /** Records that {@code actual} should be within {@code absTol} of
     *  {@code expected}. */
    protected final void check(String name, double actual, double expected, double absTol) {
        checks.check(name, actual, expected, absTol);
    }

    /** Records that {@code actual} should be within {@code relPct} percent of
     *  {@code expected}. */
    protected final void checkRelPct(String name, double actual, double expected,
            double relPct) {
        checks.checkRelPct(name, actual, expected, relPct);
    }

    /** Records a CEILING: {@code actualDb} at or below {@code maxDb}. */
    protected final void checkDbBelow(String name, double actualDb, double maxDb) {
        checks.checkDbBelow(name, actualDb, maxDb);
    }

    /** Records that {@code actual} should lie in {@code [min, max]}. */
    protected final void checkRange(String name, double actual, double min, double max) {
        checks.checkRange(name, actual, min, max);
    }

    /** Records a plain predicate. */
    protected final void checkTrue(String name, boolean condition) {
        checks.checkTrue(name, condition);
    }

    /** Records an unconditional failure with a reason - for a branch the
     *  scenario must never reach. */
    protected final void failCheck(String name, String message) {
        checks.failCheck(name, message);
    }

    /**
     * Closes the run: writes the result file when one was asked for, and
     * answers whether every check passed.  Called by {@link AutomationRunner}
     * once the script returned OR threw, never by the script itself.
     *
     * <p>Writing is skipped when {@value #RESULTS_PROPERTY} is unset, so an
     * ordinary documentation run is completely unaffected.  A file that cannot
     * be written is logged, not thrown: the verdict is what the exit code
     * carries, and losing the detail must not also lose the verdict.
     *
     * @param error the throwable that ended the run, or {@code null}
     * @return true when every recorded check passed
     */
    final boolean finishAutomation(Throwable error) {
        String target = System.getProperty(RESULTS_PROPERTY);
        if (target == null || target.isEmpty()) {
            return checks.allPassed();
        }
        try {
            checks.writeTo(Paths.get(target), error);
            if (log.isInfoEnabled()) {
                log.info("Automation: results written to {}", target);
            }
        } catch (IOException | RuntimeException ex) {
            // RuntimeException as well as IOException, and this is the reason:
            // Paths.get throws an UNCHECKED InvalidPathException on a malformed
            // path.  Escaping here would abort the runner's finally BEFORE it
            // closes the window - the GUI would then sit there forever and the
            // scenario would time out instead of failing, which is the worst of
            // both outcomes.  A run whose results could not be written cannot be
            // verified, so it is failed rather than trusted.
            log.error("Automation: writing results to {} failed", target, ex);
            return false;
        }
        return checks.allPassed();
    }

    /**
     * A parameter the launching engine passed in, as the system property
     * {@value #PARAM_PREFIX}{@code <name>} - or null when it was not set.
     *
     * <p>A sandboxed body may not name {@code System}, so this is the only way
     * a scenario can be told something that is not known when it is written:
     * the port a test server came up on, a path staged for it, a tolerance the
     * harness computed.
     */
    protected final String param(String name) {
        return System.getProperty(PARAM_PREFIX + name);
    }

    /** {@link #param(String)} with a default for the unset case. */
    protected final String param(String name, String fallback) {
        String value = param(name);
        return value == null ? fallback : value;
    }

    /** Switches the UI language in place (bundle swap + full content
     *  rebuild).  The persisted language preference is NOT touched - a
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

    /** Prints {@code control} (any widget - e.g. {@code genPane().getGroup()})
     *  at its current on-screen size into a PNG.  Occlusion-proof: renders
     *  through {@code Control.print}, not a screen copy. */
    protected final void snapshot(Control control, String pngPath) {
        snapshot(control, KEEP_SIZE, KEEP_SIZE, pngPath);
    }

    /** {@link #snapshot(Control, String)} variant that scales the printed
     *  image to exactly {@code width × height} pixels (high-quality
     *  bitmap interpolation).  Pass {@link #KEEP_SIZE} for both to keep
     *  the control's on-screen size.  Note this SCALES pixels - for the
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
     *  pixels into a PNG - independent of the live window's sash layout,
     *  so the help images come out the same size on every run. */
    protected final void snapshotScopePane(int width, int height, String pngPath) {
        snapshotRendered(pngPath,
                () -> oscPane().renderOffscreen(display, width, height));
    }

    /** Renders the FFT pane at exactly {@code width × height} pixels into
     *  a PNG (toolbar tab body collapsed, tiles overlaid - same output as
     *  the in-app screenshot dialog). */
    protected final void snapshotFftPane(int width, int height, String pngPath) {
        snapshotRendered(pngPath,
                () -> fftPane().renderOffscreen(display, width, height));
    }

    /** Loads a {@code .fft} spectrum file into the live FFT pane so the next
     *  {@link #snapshotFftPane} renders that real spectrum (no live capture
     *  needed) - used to drive a frequency-zoom animation over a saved IMD /
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
     *  Runs on the automation thread - encoding multi-MB images must not
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
    // Component registry (address the UI by path - see UiRegistry).  A script
    // can select a tab, maximize a pane, or screenshot any registered
    // component without ever holding a widget reference.
    // -------------------------------------------------------------------------

    /** Lists every registered component path in the log - the "lookup" /
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
            else log.warn("Automation: activate - no activatable component at '{}'", path);
        });
    }

    /** Gives the pane at {@code path} the whole view, if it is maximizable. */
    protected final void maximize(String path) {
        ui(() -> {
            UiNode node = UiRegistry.instance().resolve(path);
            if (node != null && node.getMaximize() != null) node.getMaximize().run();
            else log.warn("Automation: maximize - no maximizable component at '{}'", path);
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
                log.warn("Automation: screenshot - no component / control at '{}'", path);
            }
        });
        savePng(out[0], pngPath);
    }

    // -------------------------------------------------------------------------
    // Gestures - REAL input, synthesized at the OS level through Display.post,
    // so the application is driven the way a hand drives it: the events go out
    // to the window system and come back through the same listeners a user's
    // mouse would reach.  Nothing here calls a handler directly; a test that
    // invoked the listener itself would prove only that the listener exists.
    //
    // Posting happens on the AUTOMATION thread, never inside ui(): post() only
    // queues the event with the OS, and the application's own event loop has to
    // be running to receive it - from inside a syncExec the loop is blocked and
    // the event would never be delivered.  Coordinates, by contrast, must be
    // read on the UI thread, so each gesture resolves its target point through
    // ui() first and then posts.
    //
    // Relative coordinates (0..1) are of the TARGET CONTROL, translated to
    // display coordinates by the control itself, so a gesture is independent of
    // where the window happens to sit on screen.
    // -------------------------------------------------------------------------

    /** Left-clicks the registered component at {@code path}. */
    protected final void clickOn(String path, double relX, double relY) throws InterruptedException {
        Point at = displayPointOn(control(path), relX, relY);
        if (at == null) return;
        postMouseMove(at.x, at.y);
        settle();
        postMouseButton(SWT.MouseDown, MOUSE_BUTTON_LEFT);
        settle();
        postMouseButton(SWT.MouseUp, MOUSE_BUTTON_LEFT);
        settle();
    }

    /** Moves the pointer over the registered component at {@code path} - enough
     *  to raise the hover behaviour a view attaches to mouse movement. */
    protected final void mouseMoveOn(String path, double relX, double relY)
            throws InterruptedException {
        Point at = displayPointOn(control(path), relX, relY);
        if (at == null) return;
        postMouseMove(at.x, at.y);
        settle();
    }

    /** Presses the left button at one point of the component at {@code path},
     *  drags to another and releases - the rubberband gesture. */
    protected final void mouseDragOn(String path, double relX1, double relY1,
            double relX2, double relY2) throws InterruptedException {
        dragOn(control(path), relX1, relY1, relX2, relY2);
    }

    /** Turns the wheel over the component at {@code path}, optionally with Ctrl
     *  and / or Shift held - the modifiers a view reads from the event's state
     *  mask to tell zoom from pan. */
    protected final void mouseWheelOn(String path, double relX, double relY, int ticks,
            boolean ctrl, boolean shift) throws InterruptedException {
        wheelOn(control(path), relX, relY, ticks, ctrl, shift);
    }

    /**
     * The same rubberband drag, aimed at the FFT PLOT CANVAS rather than at a
     * registry path.
     *
     * <p>It exists because {@code multifunctional/fft} registers the whole pane
     * GROUP - title bar, plot, both scrollbars and the tab toolbar - so a
     * relative coordinate on that control lands somewhere unpredictable inside
     * the plot, or outside it entirely.  The canvas is reached through the
     * pane's existing public accessor instead, which needs no change anywhere.
     *
     * <p>Keep the coordinates well inside the canvas: the plot proper is inset
     * by the level-axis gutter on the left and the frequency-axis strip at the
     * bottom, and a drag starting in either is refused outright.
     */
    protected final void dragOnFftPlot(double relX1, double relY1, double relX2, double relY2)
            throws InterruptedException {
        dragOn(fftPlot(), relX1, relY1, relX2, relY2);
    }

    /** {@link #mouseWheelOn} aimed at the FFT plot canvas - see
     *  {@link #dragOnFftPlot} for why the registry path will not do. */
    protected final void wheelOnFftPlot(double relX, double relY, int ticks,
            boolean ctrl, boolean shift) throws InterruptedException {
        wheelOn(fftPlot(), relX, relY, ticks, ctrl, shift);
    }

    /** Brings the main window to the front and gives it focus.  Synthesized
     *  input goes to whatever the window system considers active, so a
     *  scenario that never asked for the foreground would be posting its
     *  gestures at whichever window happened to have it. */
    protected final void focusMainWindow() throws InterruptedException {
        ui(() -> {
            Shell shell = genPane().getGroup().getShell();
            shell.forceActive();
            shell.setActive();
        });
        settle();
    }

    /** The drag itself: down, a handful of interpolated moves, up.  The
     *  intermediate moves are not decoration - a view tracks the rubberband
     *  through MouseMove, and a down-then-up with nothing between leaves it
     *  with a zero-sized selection to act on. */
    private void dragOn(Control target, double relX1, double relY1, double relX2, double relY2)
            throws InterruptedException {
        Point from = displayPointOn(target, relX1, relY1);
        Point to   = displayPointOn(target, relX2, relY2);
        if (from == null || to == null) return;
        postMouseMove(from.x, from.y);
        settle();
        postMouseButton(SWT.MouseDown, MOUSE_BUTTON_LEFT);
        settle();
        for (int step = 1; step <= DRAG_STEPS; step++) {
            postMouseMove(from.x + (to.x - from.x) * step / DRAG_STEPS,
                    from.y + (to.y - from.y) * step / DRAG_STEPS);
            settle();
        }
        postMouseButton(SWT.MouseUp, MOUSE_BUTTON_LEFT);
        settle();
    }

    /** The wheel itself, with the modifier keys held down around it. */
    private void wheelOn(Control target, double relX, double relY, int ticks,
            boolean ctrl, boolean shift) throws InterruptedException {
        Point at = displayPointOn(target, relX, relY);
        if (at == null) return;
        postMouseMove(at.x, at.y);
        settle();
        if (ctrl)  postKey(SWT.KeyDown, SWT.CTRL);
        if (shift) postKey(SWT.KeyDown, SWT.SHIFT);
        settle();
        postWheel(ticks);
        settle();
        if (shift) postKey(SWT.KeyUp, SWT.SHIFT);
        if (ctrl)  postKey(SWT.KeyUp, SWT.CTRL);
        settle();
    }

    /** The FFT plot canvas - the control the gestures above aim at. */
    private Control fftPlot() {
        Control[] out = new Control[1];
        ui(() -> out[0] = fftPane().getView());
        return out[0];
    }

    /** The control registered at {@code path}, or null (logged) when the path
     *  names nothing addressable. */
    private Control control(String path) {
        Control[] out = new Control[1];
        ui(() -> {
            UiNode node = UiRegistry.instance().resolve(path);
            if (node != null && node.getControl() != null && !node.getControl().isDisposed()) {
                out[0] = node.getControl();
            } else {
                log.warn("Automation: gesture - no component / control at '{}'", path);
            }
        });
        return out[0];
    }

    /** Translates a relative point on {@code target} into display coordinates,
     *  on the UI thread (both the size and the translation are widget state). */
    private Point displayPointOn(Control target, double relX, double relY) {
        if (target == null) return null;
        Point[] out = new Point[1];
        ui(() -> {
            if (target.isDisposed()) return;
            Point size = target.getSize();
            out[0] = target.toDisplay((int) Math.round(relX * size.x),
                    (int) Math.round(relY * size.y));
        });
        return out[0];
    }

    private void postMouseMove(int x, int y) {
        Event event = new Event();
        event.type = SWT.MouseMove;
        event.x = x;
        event.y = y;
        display.post(event);
    }

    private void postMouseButton(int type, int button) {
        Event event = new Event();
        event.type = type;
        event.button = button;
        display.post(event);
    }

    private void postKey(int type, int keyCode) {
        Event event = new Event();
        event.type = type;
        event.keyCode = keyCode;
        display.post(event);
    }

    /** Wheel events carry no coordinates - the OS delivers them wherever the
     *  pointer already is, which is why every caller moves it first. */
    private void postWheel(int ticks) {
        Event event = new Event();
        event.type = SWT.MouseWheel;
        event.detail = SWT.SCROLL_LINE;
        event.count = ticks;
        display.post(event);
    }

    /** Lets the OS input queue drain into the application between posted
     *  events.  Synthesized input is asynchronous: without a pause the whole
     *  gesture can reach the application as one indivisible burst, and a view
     *  that never saw an intermediate state cannot react to it. */
    private void settle() throws InterruptedException {
        Thread.sleep(GESTURE_SETTLE_MS);
    }

    // -------------------------------------------------------------------------
    // Plot state a gesture is meant to change.  All four are preferences the
    // views already write, so these are plain reads of existing state - no view
    // is asked for anything it does not already publish.
    // -------------------------------------------------------------------------

    /** Lower edge of the FFT plot's visible frequency window, in Hz. */
    protected final double fftFreqMinHz() {
        return prefsDouble(() -> Preferences.instance().getFftFreqMinHz());
    }

    /** Upper edge of the FFT plot's visible frequency window, in Hz. */
    protected final double fftFreqMaxHz() {
        return prefsDouble(() -> Preferences.instance().getFftFreqMaxHz());
    }

    /** Top of the FFT plot's magnitude axis, in dBFS (the canonical unit the
     *  preference holds, whatever unit the axis is labelled in). */
    protected final double fftMagTopDb() {
        return prefsDouble(() -> Preferences.instance().getFftMagTop());
    }

    /** Bottom of the FFT plot's magnitude axis, in dBFS. */
    protected final double fftMagBottomDb() {
        return prefsDouble(() -> Preferences.instance().getFftMagBottom());
    }

    /** Reads one preference on the UI thread - the same thread the views write
     *  it from, so a gesture's effect is never read half-applied. */
    private double prefsDouble(Supplier<Double> read) {
        double[] out = { Double.NaN };
        ui(() -> out[0] = read.get());
        return out[0];
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
     *  tab and {@link #screenshot}{@code ("preferences", ...)} snapshots the
     *  dialog.  Pair with {@link #closePreferences()}. */
    protected final void openPreferences() {
        ui(() -> {
            preferencesDialog = new PreferencesDialog(genPane().getGroup().getShell());
            preferencesShell  = preferencesDialog.open();
        });
    }

    /**
     * Opens the scope's amplitude-histogram window and leaves it up, by setting the
     * preference the scope watches.  Its plot registers under
     * {@code multifunctional/scope/histogram}, so {@link #screenshot} captures it.
     *
     * <p>Give the scope a few seconds of real capture first: the distribution is
     * empty until samples have been binned, and an empty plot is not worth a
     * screenshot.  That means such a run must NOT set
     * {@code phonalyser.automation.noAudio}.  Pair with {@link #closeHistogram()}.
     */
    protected final void openHistogram() {
        ui(() -> Preferences.instance().setOscShowHistogram(true));
    }

    /** Closes the window opened by {@link #openHistogram()} (no-op if none). */
    protected final void closeHistogram() {
        ui(() -> Preferences.instance().setOscShowHistogram(false));
    }

    /** Closes the dialog opened by {@link #openPreferences()} (no-op if none). */
    protected final void closePreferences() {
        ui(() -> {
            if (preferencesShell != null && !preferencesShell.isDisposed()) {
                preferencesShell.dispose();
            }
            preferencesShell  = null;
            preferencesDialog = null;
        });
    }

    /** Opens the CARD editor on the Preferences Audio tab's INPUT card (resolved
     *  from the seeded {@code devices.yaml}) for capture - requires an open
     *  {@link #openPreferences()} first.  Non-modal: it shows the fully populated
     *  dialog without its blocking loop, so {@link #screenshotCardEditor} can
     *  snapshot it.  Pair with {@link #closeInputCardEditor()}. */
    protected final void openInputCardEditor() {
        ui(() -> cardEditorDialog =
                (preferencesDialog != null) ? preferencesDialog.openInputCardEditorForCapture() : null);
    }

    /** Snapshots the card editor opened by {@link #openInputCardEditor()} to
     *  {@code pngPath} at its on-screen size (no-op if not open). */
    protected final void screenshotCardEditor(String pngPath) {
        if (cardEditorDialog != null) {
            Control content = cardEditorDialog.getContent();
            if (content != null && !content.isDisposed()) {
                snapshot(content, pngPath);
            }
        }
    }

    /** Opens the QA40x backend's own settings dialog for capture - requires the
     *  QA40x backend selected and the analyzer attached, since the panel's values
     *  are read off the device.  Non-modal, so {@link #screenshotQa40xSettings}
     *  can snapshot it.  Pair with {@link #closeQa40xSettings()}. */
    protected final void openQa40xSettings() {
        ui(() -> {
            BackendSettingsUi settings = BackendSettingsRegistry.instance().forBackend(AudioBackendType.QA40X);
            if (settings != null && preferencesShell != null) {
                settings.showForCapture(preferencesShell);
                backendSettingsUi = settings;
            } else {
                backendSettingsUi = null;
            }
        });
    }

    /** Snapshots the QA40x settings dialog opened by {@link #openQa40xSettings()}
     *  to {@code pngPath} at its on-screen size (no-op if not open). */
    protected final void screenshotQa40xSettings(String pngPath) {
        if (backendSettingsUi != null) {
            Control content = backendSettingsUi.getContent();
            if (content != null && !content.isDisposed()) {
                snapshot(content, pngPath);
            }
        }
    }

    /** Closes the QA40x settings dialog opened by {@link #openQa40xSettings()}
     *  (no-op if none). */
    protected final void closeQa40xSettings() {
        ui(() -> {
            if (backendSettingsUi != null) {
                backendSettingsUi.close();
            }
            backendSettingsUi = null;
        });
    }

    /**
     * Opens the network server list and snapshots it to {@code pngPath}, then
     * closes it again.  Populate the list first by having a Phonalyser server
     * running - the window shows what discovery can actually hear.
     *
     * <p>Three things set this apart from the capture helpers above.  It reaches
     * the window through the same service interface the Preferences dialog uses,
     * so this module still never names the net UI's own type.  It opens the
     * window with {@code asyncExec} rather than {@link #ui}, because that window
     * runs its own modal event loop and would never hand control back to a
     * blocking caller - and that loop is then what services this method's later
     * {@code syncExec}s, which is exactly what lets the shot be taken while the
     * window is up.  And it prints the shell's CHILDREN rather than the shell:
     * a Shell prints blank on win32, and this window - unlike every other dialog
     * captured here - exposes no single content composite to print instead, so
     * its children are printed onto its own background at their own bounds.
     *
     * <p>A build shipping no net UI logs and captures nothing.
     */
    protected final void screenshotServerList(String pngPath) throws InterruptedException {
        RemoteBackendUi remote = RemoteBackendRegistry.instance().getUi();
        if (remote == null) {
            log.warn("Automation: this build ships no net UI - no server list to capture");
            return;
        }
        String title = I18n.t("net.servers.title");
        display.asyncExec(() -> remote.openServerList(genPane().getGroup().getShell()));
        waitSeconds(SERVER_LIST_SETTLE_SECONDS);
        ImageData[] data = new ImageData[1];
        ui(() -> {
            for (Shell candidate : display.getShells()) {
                if (!title.equals(candidate.getText())) continue;
                Rectangle area = candidate.getClientArea();
                Image image = new Image(display, Math.max(1, area.width), Math.max(1, area.height));
                GC gc = new GC(image);
                try {
                    gc.setBackground(candidate.getBackground());
                    gc.fillRectangle(0, 0, area.width, area.height);
                    for (Control child : candidate.getChildren()) {
                        Rectangle at = child.getBounds();
                        Image part = new Image(display, Math.max(1, at.width), Math.max(1, at.height));
                        GC partGc = new GC(part);
                        try {
                            child.print(partGc);
                        } finally {
                            partGc.dispose();
                        }
                        gc.drawImage(part, at.x, at.y);
                        part.dispose();
                    }
                } finally {
                    gc.dispose();
                }
                data[0] = image.getImageData();
                image.dispose();
                break;
            }
        });
        savePng(data[0], pngPath);
        ui(() -> {
            for (Shell candidate : display.getShells()) {
                if (title.equals(candidate.getText())) {
                    candidate.close();
                    break;
                }
            }
        });
    }

    /** Closes the card editor opened by {@link #openInputCardEditor()} (no-op if
     *  none) - via {@code close()} so the shell's listeners run. */
    protected final void closeInputCardEditor() {
        ui(() -> {
            if (cardEditorDialog != null && cardEditorDialog.getContent() != null
                    && !cardEditorDialog.getContent().isDisposed()) {
                cardEditorDialog.getContent().getShell().close();
            }
            cardEditorDialog = null;
        });
    }

    /** Opens the shared voltage-calibration dialog in its DAC two-row form (both
     *  channels prefilled at the configured amplitude) for capture - no signal, no
     *  modal loop.  Pair with {@link #closeCalibration()}. */
    protected final void openDacCalibration() {
        ui(() -> calibrationDialog = genPane().openDacCalibrationForCapture());
    }

    /** Opens the shared voltage-calibration dialog in its ADC two-row form (the
     *  analyzed channel prefilled, the other blank/disabled) for capture - no live
     *  measurement, no modal loop.  Pair with {@link #closeCalibration()}. */
    protected final void openAdcCalibration() {
        ui(() -> calibrationDialog = fftPane().openAdcCalibrationForCapture());
    }

    /** Snapshots the calibration dialog opened by {@link #openDacCalibration()} /
     *  {@link #openAdcCalibration()} to {@code pngPath} (no-op if not open). */
    protected final void screenshotCalibration(String pngPath) {
        if (calibrationDialog != null) {
            Control content = calibrationDialog.getContent();
            if (content != null && !content.isDisposed()) {
                snapshot(content, pngPath);
            }
        }
    }

    /** Closes the calibration dialog opened by {@link #openDacCalibration()} /
     *  {@link #openAdcCalibration()} (no-op if none). */
    protected final void closeCalibration() {
        ui(() -> {
            if (calibrationDialog != null && calibrationDialog.getContent() != null
                    && !calibrationDialog.getContent().isDisposed()) {
                calibrationDialog.getContent().getShell().close();
            }
            calibrationDialog = null;
        });
    }

    /** Resizes the open Preferences dialog to an exact pixel size and re-lays it
     *  out (no-op if not open).  The dialog is normally height-capped so its
     *  tallest tab (Audio, with two device cards) scrolls; a capture wants the
     *  full pane, so this grows the shell to let the scroll viewport show
     *  everything before {@link #screenshot} prints the tab folder. */
    protected final void resizePreferences(int width, int height) {
        ui(() -> {
            if (preferencesShell != null && !preferencesShell.isDisposed()) {
                preferencesShell.setSize(width, height);
                preferencesShell.layout(true, true);
            }
        });
    }

    /** Opens the Tune-notch wizard for capture: built + shown, but with NO live
     *  sweep and no modal loop, so it opens no audio device and is fully
     *  deterministic.  Leaves it up; pair with {@link #closeTuneNotch()}.
     *  Call {@link #language} first to capture it in a given language. */
    protected final void openTuneNotch() {
        ui(() -> {
            TuneNotchWizardDialog d = new TuneNotchWizardDialog(genPane().getGroup().getShell());
            tuneNotchShell = d.buildAndShow();
            tuneNotchContent = d.getContent();
            d.startSweep();
        });
    }

    /** Snapshots the wizard opened by {@link #openTuneNotch()} to {@code pngPath}
     *  at its on-screen size (no-op if not open). */
    protected final void screenshotTuneNotch(String pngPath) {
        if (tuneNotchContent != null && !tuneNotchContent.isDisposed()) {
            snapshot(tuneNotchContent, pngPath);
        }
    }

    /** Closes the wizard opened by {@link #openTuneNotch()} (no-op if none).
     *  Uses {@code close()} (not {@code dispose()}) so the shell's SWT.Close
     *  listener runs - that's what tears down the sweep and RELEASES the audio
     *  device; dispose() skips it, leaving the exclusive device held. */
    protected final void closeTuneNotch() {
        ui(() -> {
            if (tuneNotchShell != null && !tuneNotchShell.isDisposed()) {
                tuneNotchShell.close();
            }
            tuneNotchShell = null;
            tuneNotchContent = null;
        });
    }

    /** The freq-resp progress shell caught by {@link #startFreqRespSweep()},
     *  for {@link #screenshotFreqRespBusy(String)} to photograph. */
    private Shell freqRespBusyShell;

    /** Starts a frequency-response sweep on the CONFIGURED devices by firing
     *  the pane's Play control (found by its tooltip - the private handler's
     *  public face) and waits until the modal progress shell is up.  When to
     *  photograph it is the SCRIPT's decision - wait, then call
     *  {@link #screenshotFreqRespBusy(String)}.  Nothing is cancelled: the
     *  sweep runs to its ordinary end and the shell closes itself. */
    protected final void startFreqRespSweep() throws Exception {
        Shell[][] shells = new Shell[2][];
        ui(() -> shells[0] = display.getShells());
        ui(() -> {
            Control play = findByTooltip(freqRespPane().getGroup(),
                    I18n.t("freqResp.button.play.start"));
            if (play == null) {
                log.warn("Automation: freq-resp Play control not found");
                return;
            }
            play.notifyListeners(SWT.Selection, new Event());
        });
        freqRespBusyShell = null;
        waitUntil("freq-resp progress shell", () -> {
            ui(() -> {
                shells[1] = display.getShells();
                for (Shell s : shells[1]) {
                    boolean isNew = true;
                    for (Shell b : shells[0]) {
                        if (b == s) { isNew = false; break; }
                    }
                    if (isNew && !s.isDisposed() && s.isVisible()) {
                        freqRespBusyShell = s;
                        break;
                    }
                }
            });
            return freqRespBusyShell != null;
        }, 15.0);
    }

    /** Photographs the progress shell caught by {@link #startFreqRespSweep()}
     *  from the SHELL itself - a GC on the shell copies its own rendered
     *  client area, so nothing behind or above the dialog can leak into the
     *  image and no screen coordinates are involved.  The shell is raised
     *  first so the window is fully rendered. */
    protected final void screenshotFreqRespBusy(String pngPath) throws InterruptedException {
        ui(() -> {
            Shell s = freqRespBusyShell;
            if (s != null && !s.isDisposed()) s.forceActive();
        });
        waitSeconds(0.3);
        ImageData[] out = new ImageData[1];
        ui(() -> {
            Shell s = freqRespBusyShell;
            if (s == null || s.isDisposed()) return;
            Rectangle b = s.getClientArea();
            Image img = new Image(display, b.width, b.height);
            GC gc = new GC(s);
            gc.copyArea(img, 0, 0);
            gc.dispose();
            out[0] = img.getImageData();
            img.dispose();
        });
        savePng(out[0], pngPath);
    }

    /** Depth-first search for the control carrying {@code tooltip} - how a
     *  script reaches a widget that is a private field of its pane. */
    private Control findByTooltip(Composite root, String tooltip) {
        for (Control c : root.getChildren()) {
            if (tooltip.equals(c.getToolTipText())) return c;
            if (c instanceof Composite inner) {
                Control hit = findByTooltip(inner, tooltip);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /** Opens the DAC-predistortion wizard non-modally for capture - the
     *  generator must already be playing a sine / dual-tone and the FFT
     *  recording, so the closed loop has live data.  Leaves it up; drive it with
     *  {@link #setPredistortionTarget}, {@link #startPredistortion},
     *  {@link #waitPredistortionTarget}; pair with {@link #closePredistortion}. */
    protected final void openPredistortion() {
        ui(() -> predistortionDialog = fftPane().openPredistortionForCapture());
    }

    /** Sets the wizard's Target-THD field (%). */
    protected final void setPredistortionTarget(double pct) {
        ui(() -> { if (predistortionDialog != null) predistortionDialog.setTargetPct(pct); });
    }

    /** Presses the wizard's Start button (begins the closed-loop run). */
    protected final void startPredistortion() {
        ui(() -> { if (predistortionDialog != null) predistortionDialog.pressStart(); });
    }

    /** Blocks (up to {@code maxSeconds}) until the run reaches the target THD or
     *  stops (stalled) - polling the wizard once a second. */
    protected final void waitPredistortionTarget(int maxSeconds) throws InterruptedException {
        for (int i = 0; i < maxSeconds; i++) {
            boolean[] running = new boolean[1];
            boolean[] target  = new boolean[1];
            ui(() -> {
                running[0] = predistortionDialog != null && predistortionDialog.isRunning();
                target[0]  = predistortionDialog != null && predistortionDialog.isTargetReached();
            });
            if (target[0]) return;
            if (i > 3 && !running[0]) return;   // stopped after startup (stalled / done)
            waitSeconds(1);
        }
    }

    /** Switches the UI language for the wizard ONLY - re-translates its labels in
     *  place, with no main-window rebuild - so the measurement, the FFT averages
     *  and the convergence chart stay untouched and one run yields a per-language
     *  shot. */
    protected final void retranslatePredistortion(String tag) {
        ui(() -> {
            I18n.setLocale(Locale.forLanguageTag(tag));
            if (predistortionDialog != null) predistortionDialog.retranslate();
        });
    }

    /** Snapshots the open predistortion wizard to {@code pngPath} (no-op if not
     *  open). */
    protected final void screenshotPredistortion(String pngPath) {
        if (predistortionDialog != null) {
            Control content = predistortionDialog.getContent();
            if (content != null && !content.isDisposed()) {
                snapshot(content, pngPath);
            }
        }
    }

    /** Closes the predistortion wizard - via {@code close()} so its SWT.Close
     *  listener reverts the generator and resets the FFT statistics. */
    protected final void closePredistortion() {
        ui(() -> {
            if (predistortionDialog != null && predistortionDialog.getContent() != null
                    && !predistortionDialog.getContent().isDisposed()) {
                predistortionDialog.getContent().getShell().close();
            }
            predistortionDialog = null;
        });
    }

    // -------------------------------------------------------------------------
    // Window framing and composite capture (used by the help-screenshot
    // scripts; kept here so a body-only script can drive them without
    // touching any application type itself)
    // -------------------------------------------------------------------------

    /** Sets the generator pane's preferred width (px) - its 200 px minimum
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
            oscPane().setTabsCollapsed(true);
            fftPane().setTabsCollapsed(true);
        });
    }

    /** Collapses the Frequency-response settings-tab body to its strip so the
     *  live hero print is the trace, not an expanded settings panel. */
    protected final void collapseFreqRespTabs() {
        ui(() -> freqRespPane().setTabsCollapsed(true));
    }

    /** Re-expands the Frequency-response settings-tab body (undoes
     *  {@link #collapseFreqRespTabs()}) so the per-tab shots show tab content. */
    protected final void expandFreqRespTabs() {
        ui(() -> freqRespPane().setTabsCollapsed(false));
    }

    /** Captures the whole multifunctional tab (generator | scope / fft) - the
     *  smallest composite containing all three panes, so it is independent of
     *  tab orientation - into a PNG. */
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
    // Measured results.  Each one marshals through ui() and returns a
    // primitive, so a script body can compare it without naming a type -
    // and each reads the value the pane is DISPLAYING, never a recomputation
    // of its own.
    //
    // THE DISPOSED-DISPLAY CASE IS DELIBERATE AND UNIFORM: ui() is a no-op once
    // the display is gone, so each accessor keeps the "nothing measured" value
    // it was seeded with - NaN, or false.  That matters because NaN fails every
    // numeric check by construction (see AutomationChecks) and false fails
    // checkTrue: a run whose window died mid-script therefore reports RED, and
    // cannot quietly hand back a value that reads as green.
    // -------------------------------------------------------------------------

    /** Whether the FFT analyzer has published a result yet.  This is the very
     *  state the distortion table paints behind, so it answers exactly "are
     *  there numbers on screen" - the condition to {@link #waitUntil} on
     *  before reading any of the accessors below.  False when the display is
     *  gone, so a wait on it times out rather than passing. */
    protected final boolean fftHasResult() {
        boolean[] out = new boolean[1];
        ui(() -> out[0] = fftPane().getView().getLastResult() != null);
        return out[0];
    }

    /** The THD the FFT distortion table is showing, in dB.  The table itself
     *  prints PERCENT; this is that same figure as
     *  {@code 20·log10(pct / 100)}, which the analyzer computes alongside it.
     *  {@code NaN} with no result yet, and with no display. */
    protected final double fftThdDb() {
        double[] out = { Double.NaN };
        ui(() -> {
            FftResult result = fftPane().getView().getLastResult();
            if (result != null) out[0] = result.thdDb;
        });
        return out[0];
    }

    /** The fundamental frequency the FFT is showing, in Hz (the refined,
     *  sub-bin value the table prints).  {@code NaN} with no result yet, and
     *  with no display. */
    protected final double fftFundamentalHz() {
        double[] out = { Double.NaN };
        ui(() -> out[0] = fftPane().getView().getLastFrequencyHz());
        return out[0];
    }

    /** The fundamental level the FFT is showing, in dBFS - the dBFS column of
     *  the distortion table's header, exactly as measured (the worker has
     *  already applied any loaded {@code .frc} calibration).  {@code NaN} with
     *  no result yet, and with no display.
     *
     *  <p>dBFS and not dBV deliberately: the dBV column is the same figure
     *  plus the channel's ADC offset, and reading it would mean either
     *  duplicating that sum here or changing the view that computes it - and
     *  the view is not this harness's to change. */
    protected final double fftFundamentalDbFs() {
        double[] out = { Double.NaN };
        ui(() -> {
            FftResult result = fftPane().getView().getLastResult();
            if (result != null) out[0] = result.fundamentalDbFs;
        });
        return out[0];
    }

    // -------------------------------------------------------------------------
    // net session.  Reached through the same service interface the Preferences
    // dialog uses, so this module still never names the net types; a build
    // that ships no net UI logs and does nothing.  Marshalled through ui()
    // because that is the thread the operator's own Connect / Disconnect runs
    // on, and the session state these write is read by the servers dialog.
    // -------------------------------------------------------------------------

    /** Connects to a Phonalyser server at {@code hostPort}
     *  ({@code host} or {@code host:port}).
     *
     *  <p>Seeded with a failure and overwritten only by a real answer: null
     *  MEANS SUCCESS here, so a display that died before the work ran would
     *  otherwise report the connect as having succeeded.
     *
     *  @return null when the session is up, else the reason it is not */
    protected final String netConnect(String hostPort) {
        RemoteBackendUi remote = RemoteBackendRegistry.instance().getUi();
        if (remote == null) {
            log.warn("Automation: this build ships no net UI - cannot connect to {}", hostPort);
            return "this build ships no net UI";
        }
        String[] out = { "the display was gone before the connect ran" };
        ui(() -> out[0] = remote.connectByAddress(hostPort));
        return out[0];
    }

    /** Whether a server session is open right now.  False when this build
     *  ships no net UI, and false once the display is gone - never a value
     *  that could be mistaken for a live session. */
    protected final boolean netIsConnected() {
        RemoteBackendUi remote = RemoteBackendRegistry.instance().getUi();
        if (remote == null) return false;
        boolean[] out = new boolean[1];
        ui(() -> out[0] = remote.isSessionConnected());
        return out[0];
    }

    /** Ends the server session (no-op when none is open). */
    protected final void netDisconnect() {
        RemoteBackendUi remote = RemoteBackendRegistry.instance().getUi();
        if (remote == null) {
            log.warn("Automation: this build ships no net UI - nothing to disconnect");
            return;
        }
        ui(remote::disconnectSession);
    }

    // -------------------------------------------------------------------------
    // Pane access (resolved fresh on every call - a language switch
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

    protected final FreqRespPane freqRespPane() {
        return window.getMainTab().getFreqRespPane();
    }
}
