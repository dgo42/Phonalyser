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

package org.edgo.audio.measure.gui.fft.predistortion;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.edgo.audio.measure.common.FileVersions;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.fft.ImdResult;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.CorrectionStore;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.common.ShellIcons;
import org.edgo.audio.measure.gui.fft.FftAnalyzerWorker;
import org.edgo.audio.measure.gui.fft.FftController;
import org.edgo.audio.measure.gui.fft.FftView;
import org.edgo.audio.measure.gui.bus.GeneratorCorrectionsRequest;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.widgets.NumericStepField;
import org.edgo.audio.measure.gui.widgets.UnitFamily;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.DeviceCalibration;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Modal wizard that runs the closed-loop DAC predistortion measurement and
 * saves / applies the result.  Drives the already-running generator + FFT
 * through a {@link PredistortionEngine}.
 *
 * <p>Rendering follows the (historic) PID-autotune dialog: an intro that
 * explains the procedure, an FFT-settings group and a progress group of live
 * readouts - all refreshed by a ~{@value #TIMER_MS} ms timer that polls the
 * displayed FFT result.  The engine runs on the FFT worker: Start registers
 * it there, the end of the run and the window's close unregister it.
 *
 * <p>Lifecycle: <b>Start</b> begins the run (becoming <b>Stop</b>); it
 * auto-stops on the target distortion.  <b>Save</b> writes
 * the {@code .dpd} predistortion file; it then becomes <b>Apply</b>, which
 * loads that file onto the generator and switches it to the compensated form.
 * Closing the window any other way after a run puts the generator back on the
 * form the preferences hold: a compensated form gets its saved {@code .dpd}
 * reloaded, a plain form is restarted without corrections.
 */
@Log4j2
public final class PredistortionWizardDialog implements PredistortionEngine.Listener {

    /** One line of the event history: when it happened, the averages the
     *  progress showed then, its message key and
     *  the names of the lines it is about, joined, or empty for a line
     *  without names. */
    private record HistoryEntry(LocalTime time, int averages, String key, String names) {}

    /** Floor of the target THD (%): the run stops at or under half the
     *  target, which a zero target would never let happen. */
    private static final double MIN_TARGET_PCT = 0.1e-6;
    /** Live-refresh cadence (ms) for the polling render - fast enough that the
     *  averages readout climbs smoothly. */
    private static final int    TIMER_MS = 100;
    /** The history's height in lines when the window opens, and the least it
     *  shrinks to. */
    private static final int    HISTORY_LINES = 3;
    /** Between the time, the averages and the event of a history row. */
    private static final int    HISTORY_GAP_PX = 8;
    /** The averages of a history row are never given less room than this
     *  figure, so the events stay put as the count grows. */
    private static final String AVERAGES_RESERVE = "100000";
    /** Before each history line: the time of its event. */
    private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    /** The progress's time: hours, minutes and seconds since Start. */
    private static final String ELAPSED_FORMAT = "%02d:%02d:%02d";
    /** Between the line names of one history entry: "H2, H3". */
    private static final String NAME_SEPARATOR = ", ";

    private final Shell               parentShell;
    private final FftController       fft;
    private final FftView             view;
    /** Loaded {@code .frc} corrections, handed to the engine so it de-embeds
     *  their phase when computing the DAC correction. */
    private final CorrectionStore correctionStore;
    /** The analyser worker the engine runs on: the engine is registered on
     *  it for the run and takes it as the lines' restarts. */
    private final FftAnalyzerWorker worker;

    private Shell            dialog;
    /** The margin composite holding every widget - printed for the help
     *  screenshot (a top-level Shell prints blank on Windows), and the unit
     *  re-laid-out by {@link #retranslate()}. */
    @Getter private Composite content;
    private Label            intro;
    private Group            fftGroup;
    private Group            progressGroup;
    private Label            targetLabel;
    private NumericStepField targetField;
    /** The event history: every event of the runs in this window, each with
     *  its time and averages, the newest last. */
    private Table            historyTable;
    /** The history's one column, as wide as the table: each row draws its
     *  time, averages and event itself. */
    private TableColumn      historyColumn;
    /** Widest time and widest averages figure of the history, in pixels -
     *  where each row's averages end and its event begins. */
    private int              timeWidth, averagesWidth;
    private Label            fftSizeLbl, fftRateLbl, fftWindowLbl;
    private Label            timeLbl, curThdLbl, thdNLbl, snrLbl, sinadLbl, fundLbl, floorLbl, avgLbl;
    private Button           startStopBtn;
    private Button           saveBtn;
    private Button           cancelBtn;

    /** The window's size after the last resize {@link #keepIntroWhole()}
     *  saw - which dimension a resize lowered is read against it. */
    private Point            lastSize;
    /** Set while {@link #keepIntroWhole()} resizes the window itself, so
     *  the resize it causes is not judged again. */
    private boolean          fitting;

    private PredistortionEngine engine;
    /** {@code true} while a measurement loop is running (Lombok exposes
     *  {@code isRunning()} for the automation-capture wait). */
    @Getter private boolean running;
    /** When the last run started and ended - the progress's time; the start
     *  {@code null} before the first run, the end {@code null} while it runs. */
    private Instant runStart, runEnd;
    private boolean timerArmed;
    private String  savedPath;
    private boolean applied;
    /** True once a run has been started in this window - the point from which
     *  the generator and the analyser carry this wizard's state.  A window
     *  closed before that must leave both untouched. */
    private boolean engaged;
    /** The waveform the preferences held when the run started.  A run measures
     *  the converter's own distortion, so it plays the plain sibling of a
     *  compensated form for its whole length - every rebuild of the tone
     *  derives from the preferences, and a compensated form there would load
     *  its saved correction file back under the measurement.  Restored when
     *  the window closes without Apply; Apply sets the compensated form
     *  itself. */
    private GenSignalForm formBeforeRun;
    /** The key of the state the last run ended in, or of the Save after it -
     *  what {@link #isTargetReached} compares. */
    private String  terminalStatusKey = "predistortion.status.idle";
    /** The entries {@link #historyTable} shows, the newest last.  UI-thread
     *  confined. */
    private final List<HistoryEntry> history = new ArrayList<>();
    /** A deep copy of the displayed result as the run ended - the
     *  provenance a Save writes; {@code null} before a run has ended. */
    private FftResult resultAtStop;

    public PredistortionWizardDialog(Shell parent, FftController fft, FftView view,
                                     CorrectionStore correctionStore, FftAnalyzerWorker worker) {
        this.parentShell     = parent;
        this.fft             = fft;
        this.view            = view;
        this.correctionStore = correctionStore;
        this.worker          = worker;
    }

    /** Opens the wizard and blocks until it is dismissed (interactive use). */
    public void open() {
        buildAndShow();
        while (!dialog.isDisposed()) {
            if (!dialog.getDisplay().readAndDispatch()) dialog.getDisplay().sleep();
        }
    }

    /** Builds and shows the wizard non-modally (no blocking loop), returning the
     *  shell - for automation capture, which drives it via {@link #setTargetPct},
     *  {@link #pressStart}, {@link #isTargetReached}, {@link #retranslate} and
     *  {@link #getContent}. */
    public Shell buildAndShow() {
        dialog = new Shell(parentShell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
        ShellIcons.apply(dialog);
        dialog.setText(I18n.t("predistortion.wizard.title"));
        GridLayout shellLayout = new GridLayout(1, false);
        shellLayout.marginWidth = 0; shellLayout.marginHeight = 0;
        dialog.setLayout(shellLayout);

        content = new Composite(dialog, SWT.NONE);
        content.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 12; gl.marginHeight = 12; gl.verticalSpacing = 8;
        content.setLayout(gl);

        intro = new Label(content, SWT.WRAP);
        intro.setText(I18n.t("predistortion.intro"));
        // Never cut below its wrapped text: keepIntroWhole() trades the
        // window's width against its height instead.
        GridData introGd = new GridData(SWT.FILL, SWT.TOP, true, false);
        introGd.widthHint = 405;
        intro.setLayoutData(introGd);

        historyTable = new Table(content, SWT.SINGLE | SWT.V_SCROLL);
        historyColumn = new TableColumn(historyTable, SWT.LEFT);
        // Rows are only read: no selection, no hover highlight.
        historyTable.addListener(SWT.Selection, e -> historyTable.deselectAll());
        historyTable.addListener(SWT.EraseItem, e -> e.detail &= ~(SWT.SELECTED | SWT.HOT | SWT.FOREGROUND));
        historyTable.addListener(SWT.PaintItem, this::paintHistoryRow);
        // The only row that grabs vertical space: a taller window stretches
        // the history, which never shrinks under HISTORY_LINES rows.
        GridData historyGd = new GridData(SWT.FILL, SWT.FILL, true, true);
        historyGd.widthHint     = 405;
        historyGd.heightHint    = HISTORY_LINES * historyTable.getItemHeight();
        // The control's whole height for those rows, its own frame with them.
        historyGd.minimumHeight = historyTable.computeSize(SWT.DEFAULT, historyGd.heightHint).y;
        historyTable.setLayoutData(historyGd);
        historyTable.addListener(SWT.Resize, e -> fitHistoryColumn());
        showHistory();

        buildFftGroup();
        buildProgressGroup();
        buildSettings();
        buildButtonBar();

        dialog.addListener(SWT.Close, e -> e.doit = handleCancel());

        refresh();
        dialog.pack();
        // pack() wraps the intro at its width hint, the column is as wide as
        // its widest row: the height the content needs at the packed width,
        // else the spare height lands in the history as an empty part-row.
        Rectangle trim = dialog.computeTrim(0, 0, 0, 0);
        int width = dialog.getSize().x;
        dialog.setSize(width, contentHeightAt(width - trim.width) + trim.height);
        // The initial size is the minimum.
        dialog.setMinimumSize(dialog.getSize());
        // Bottom-right corner on the main window's, clear of the spectrum.
        Rectangle parentBounds = parentShell.getBounds();
        Point size = dialog.getSize();
        dialog.setLocation(parentBounds.x + parentBounds.width - size.x,
                parentBounds.y + parentBounds.height - size.y);
        lastSize = dialog.getSize();
        dialog.addListener(SWT.Resize, e -> keepIntroWhole());
        dialog.open();
        armTimer();
        return dialog;
    }

    // -------------------------------------------------------------------------
    // Build
    // -------------------------------------------------------------------------

    private void buildFftGroup() {
        fftGroup = new Group(content, SWT.NONE);
        fftGroup.setText(I18n.t("predistortion.group.fft"));
        // Two columns, as the progress group below.
        fftGroup.setLayout(new GridLayout(2, false));
        fftGroup.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        fftSizeLbl   = infoLabel(fftGroup);      // row 0, col 0
        fftRateLbl   = infoLabel(fftGroup);      // row 0, col 1
        fftWindowLbl = infoLabel(fftGroup);      // row 1, col 0
    }

    private void buildProgressGroup() {
        progressGroup = new Group(content, SWT.NONE);
        progressGroup.setText(I18n.t("predistortion.group.progress"));
        progressGroup.setLayout(new GridLayout(2, true));
        progressGroup.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        timeLbl   = infoLabel(progressGroup);
        avgLbl    = infoLabel(progressGroup);
        curThdLbl = infoLabel(progressGroup);
        snrLbl    = infoLabel(progressGroup);
        thdNLbl   = infoLabel(progressGroup);
        sinadLbl  = infoLabel(progressGroup);
        fundLbl   = infoLabel(progressGroup);
        floorLbl  = infoLabel(progressGroup);
    }

    private Label infoLabel(Composite parent) {
        Label l = new Label(parent, SWT.NONE);
        l.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return l;
    }

    private void buildSettings() {
        Composite c = new Composite(content, SWT.NONE);
        c.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 0; gl.marginHeight = 0; gl.horizontalSpacing = 8;
        c.setLayout(gl);

        // Seeded from Preferences so the last-used value survives reopening
        // the wizard AND restarting the app - never reset by a completed run.
        Preferences prefs = Preferences.instance();

        targetLabel = new Label(c, SWT.NONE);
        targetLabel.setText(I18n.t("predistortion.targetThd"));
        // PERCENT policy, like the other % fields.  8 decimals so a sub-ppm
        // target distortion can be entered; the floor of 0.1e-6 % keeps the
        // target above zero, which the stop rule needs.
        targetField = new NumericStepField(c, UnitFamily.PERCENT, MIN_TARGET_PCT, 100.0, 8, 90);
        targetField.setValue(prefs.getPredistortionTargetPct());
        targetField.setToolTipText(I18n.t("predistortion.targetThd.tooltip"));
        targetField.addSelectionListener(e ->
                Preferences.instance().setPredistortionTargetPct(targetField.getValue()));
    }

    private void buildButtonBar() {
        Composite bar = new Composite(content, SWT.NONE);
        bar.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
        GridLayout gl = new GridLayout(3, true);
        gl.marginWidth = 0; gl.marginHeight = 0;
        bar.setLayout(gl);

        startStopBtn = new Button(bar, SWT.PUSH);
        startStopBtn.setText(I18n.t("predistortion.button.start"));
        startStopBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        startStopBtn.addListener(SWT.Selection, e -> onStartStop());

        saveBtn = new Button(bar, SWT.PUSH);
        saveBtn.setText(I18n.t("predistortion.button.save"));
        saveBtn.setEnabled(false);
        saveBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        saveBtn.addListener(SWT.Selection, e -> onSaveOrApply());

        cancelBtn = new Button(bar, SWT.PUSH);
        cancelBtn.setText(I18n.t("common.close"));
        cancelBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        cancelBtn.addListener(SWT.Selection, e -> { if (handleCancel()) dialog.close(); });
    }

    // -------------------------------------------------------------------------
    // Automation capture hooks - drive the wizard from a body script with no
    // modal loop.  An in-place language re-translate leaves the measurement and
    // the FFT averages untouched, so ONE collection yields a screenshot per
    // language.
    // -------------------------------------------------------------------------

    /** Sets the target-THD field (%). */
    public void setTargetPct(double pct) {
        if (targetField != null) targetField.setValue(pct);
    }

    /** Presses Start if idle (no-op while a run is in progress). */
    public void pressStart() {
        if (!running) onStartStop();
    }

    /** {@code true} once a run stopped because it reached the target THD. */
    public boolean isTargetReached() {
        return !running && "predistortion.status.targetReached".equals(terminalStatusKey);
    }

    /** Re-reads every UI label in the current locale, in place, WITHOUT rebuilding
     *  the widgets or the panes behind the wizard - so a language switch during a
     *  capture leaves the measurement and the FFT averages untouched.  The
     *  dynamic labels (FFT settings, metrics) re-translate on the
     *  {@link #refresh()} below and the event history on
     *  {@link #showHistory()}; this re-sets the static ones. */
    public void retranslate() {
        if (dialog == null || dialog.isDisposed()) return;
        dialog.setText(I18n.t("predistortion.wizard.title"));
        intro.setText(I18n.t("predistortion.intro"));
        fftGroup.setText(I18n.t("predistortion.group.fft"));
        progressGroup.setText(I18n.t("predistortion.group.progress"));
        targetLabel.setText(I18n.t("predistortion.targetThd"));
        startStopBtn.setText(I18n.t(running ? "predistortion.button.stop" : "predistortion.button.start"));
        saveBtn.setText(I18n.t(savedPath == null ? "predistortion.button.save" : "predistortion.button.apply"));
        cancelBtn.setText(I18n.t("common.close"));
        showHistory();
        refresh();
        content.layout(true, true);
        keepIntroWhole();
    }

    /**
     * Keeps the wrapped intro whole: when the window's client area is lower
     * than the content needs at its width, the window gives way in the other
     * dimension.  A lowered window widens to the narrowest width at which the
     * wrapped text fits its height - up to the screen's width, beyond which
     * the height grows after all; a narrowed one grows to the height its
     * width needs.  A taller or wider window than needed is left as it is.
     */
    private void keepIntroWhole() {
        if (fitting || dialog.isDisposed()) return;
        Point size = dialog.getSize();
        Rectangle trim = dialog.computeTrim(0, 0, 0, 0);
        int clientWidth  = size.x - trim.width;
        int clientHeight = size.y - trim.height;
        if (contentHeightAt(clientWidth) > clientHeight) {
            boolean lowered = size.y < lastSize.y;
            int maxClientWidth = dialog.getDisplay().getClientArea().width - trim.width;
            int width = lowered ? widthFitting(clientHeight, clientWidth, maxClientWidth) : clientWidth;
            int height = Math.max(clientHeight, contentHeightAt(width));
            fitting = true;
            try {
                dialog.setSize(width + trim.width, height + trim.height);
            } finally {
                fitting = false;
            }
            size = dialog.getSize();
        }
        lastSize = size;
    }

    /** The narrowest client width from {@code from} up to {@code max} at
     *  which the content fits {@code height}, or {@code max} when even that
     *  is too narrow.  The content's height only falls as its width grows,
     *  so a bisection finds it. */
    private int widthFitting(int height, int from, int max) {
        if (contentHeightAt(max) > height) return Math.max(from, max);
        int lo = from;
        int hi = max;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (contentHeightAt(mid) > height) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** The height the content needs at client width {@code width}, the
     *  intro wrapped to it. */
    private int contentHeightAt(int width) {
        return content.computeSize(width, SWT.DEFAULT, true).y;
    }

    // -------------------------------------------------------------------------
    // Live refresh timer (polls the engine - the autotune-dialog render style)
    // -------------------------------------------------------------------------

    private void armTimer() {
        if (timerArmed) return;
        timerArmed = true;
        tick();
    }

    private void tick() {
        if (dialog.isDisposed()) return;
        refresh();
        dialog.getDisplay().timerExec(TIMER_MS, this::tick);
    }

    private void refresh() {
        if (dialog.isDisposed()) return;
        FftResult live = view.getLastResult();
        Preferences prefs = Preferences.instance();

        int    size  = live != null ? live.fftSize    : prefs.getFftLength();
        int    rate  = live != null ? live.sampleRate : prefs.current().getInputSampleRate();
        double hzBin = live != null ? live.freqResolution : (rate > 0 ? rate / (double) size : 0);
        fftSizeLbl  .setText(I18n.t("predistortion.fft.size", size, fmtHz(hzBin)));
        fftRateLbl  .setText(I18n.t("predistortion.fft.rate", rate));
        fftWindowLbl.setText(I18n.t("predistortion.fft.window", prefs.getFftWindow()));

        // Dual-tone signal -> the loop minimises INTERMOD, not THD; show IMD /
        // D+N in place of THD / THD+N.
        // The products ride on the live result; one without them (no second
        // tone found) reads "-" - a THD figure measures a single tone and says
        // nothing about the intermod the loop minimises.
        boolean dual = prefs.getGenSignalForm().isDualTone();
        ImdResult imd = (dual && live != null) ? live.imd : null;

        // Live distortion straight off the running FFT.
        curThdLbl.setText(dual
                ? metric("currentImd", imd != null ? fmtPct(imd.imdPwrPct) : "-")
                : metric("currentThd", live != null ? fmtPct(live.thdPct) : "-"));
        // What is inside the average - the SAME number the FFT pane shows:
        // under the infinite averaging the run sets, the averages performed
        // since the reset at Start, NOT the per-call frameCount (only the 2-3
        // frames of one analyze() segment).
        timeLbl .setText(metric("time", runStart != null ? fmtElapsed(Duration.between(runStart,
                runEnd != null ? runEnd : Instant.now())) : "-"));
        avgLbl  .setText(metric("averages", Integer.toString(fft.averagesInWindow())));
        // Match the FFT pane: the ratio figures (THD+N / TD+N / SNR / SINAD)
        // read plain dB - they measure against the fundamental, so no absolute
        // unit applies; the absolute levels (fundamental, noise floor) get the
        // dBFS->dBV offset and read dBV.
        thdNLbl .setText(dual
                ? metric("d_n",   imd != null ? fmtDb(20.0 * Math.log10(imd.tdnPct / 100.0)) : "-")
                : metric("thd_n", live != null ? fmtDb(-live.sinadDb) : "-"));
        snrLbl  .setText(metric("snr",      live != null ? fmtDb(live.snrDb) : "-"));
        sinadLbl.setText(metric("sinad",    live != null ? fmtDb(live.sinadDb) : "-"));
        double dbvOffsetDb = AudioBackend.instance().analysedChannelDbvOffsetDb();
        fundLbl .setText(metric("fund",     live != null ? fmtDbv(live.fundamentalDbFs + dbvOffsetDb) : "-"));
        floorLbl.setText(metric("floor",    live != null ? fmtDbv(live.noisePeakFloorDbFs() + dbvOffsetDb) : "-"));
    }

    private String metric(String key, String value) {
        return I18n.t("predistortion.metric." + key) + ":  " + value;
    }

    /** A run's length as hh:mm:ss. */
    private String fmtElapsed(Duration d) {
        return String.format(Locale.ROOT, ELAPSED_FORMAT, d.toHours(), d.toMinutesPart(), d.toSecondsPart());
    }

    // -------------------------------------------------------------------------
    // Start / Stop
    // -------------------------------------------------------------------------

    private void onStartStop() {
        if (running) {
            engine.stop();
            worker.unregisterPredistortionEngine();
            startStopBtn.setEnabled(false);   // re-enabled by onFinished
            return;
        }
        // "Is the generator emitting?" - the same bus question the analyser
        // asks; this window holds no generator.
        Boolean emitting = MessageBus.instance().request(Events.GENERATOR_RUNNING);
        if (!Boolean.TRUE.equals(emitting)) {
            Dialogs.error(dialog, I18n.t("predistortion.wizard.title"),
                    I18n.t("predistortion.error.noGenerator"));
            return;
        }
        Preferences prefs = Preferences.instance();
        GenSignalForm form = prefs.getGenSignalForm();
        boolean supported = form == GenSignalForm.SINE
                || form == GenSignalForm.SINE_COMP
                || form.isDualTone();
        if (!supported) {
            // Single tone -> harmonic compensation; dual tone -> intermod
            // compensation.  Every other waveform (noise / sweep / triangle /
            // rectangle) has no meaningful predistortion target.
            Dialogs.error(dialog, I18n.t("predistortion.wizard.title"),
                    I18n.t("predistortion.error.notSupported"));
            return;
        }
        // Settings persist on edit (see buildSettings), independently of this
        // run - Start just reads the current field values.
        double targetThdPct = targetField.getValue();

        running = true;
        runStart = Instant.now();
        runEnd = null;
        engaged = true;
        savedPath = null;
        applied = false;
        resultAtStop = null;
        saveBtn.setEnabled(false);
        saveBtn.setText(I18n.t("predistortion.button.save"));
        targetField.setEnabled(false);
        startStopBtn.setText(I18n.t("predistortion.button.stop"));

        // The run measures the raw converter: a compensated preference form
        // gives way to its plain sibling for the run's length, through the
        // preferences the generator derives every start from (see
        // formBeforeRun).  The generator's form listener switches the running
        // tone live and forgets what was hot-swapped onto it.
        formBeforeRun = form;
        if (form.isCompensated()) {
            prefs.setGenSignalForm(form.plainSibling());
        }
        // The run ends by its own stop rule.  With stop-after-N on, the
        // worker pauses the analysis once N averages are performed, no result
        // reaches the engine any more, and the run would never end; the
        // preference stays off until it is switched on again by hand.
        if (prefs.isFftStopAfterNEnabled()) {
            prefs.setFftStopAfterNEnabled(false);
        }

        // The engine reaches this window through a deferred UI-thread task:
        // its correction requests and callbacks come from the worker thread,
        // and a stop from this thread delivers its end of run after the
        // button handler has returned.
        Display display = dialog.getDisplay();
        engine = new PredistortionEngine(r -> { if (!display.isDisposed()) display.asyncExec(r); },
                MessageBus.instance(), worker, correctionStore, this);
        engine.start(targetThdPct);
        // The worker hands the engine every result, so the analyser runs.
        if (!fft.isRecording()) fft.startRecording();
        // The run begins from zero on the tone the clear left: the statistics
        // are emptied first, so the fresh count carries no frame of a table an
        // earlier run hot-swapped onto the tone.
        fft.resetStatisticsAfterSignalChange();
        worker.registerPredistortionEngine(engine);
    }

    // -------------------------------------------------------------------------
    // Engine callbacks (UI thread)
    // -------------------------------------------------------------------------

    @Override
    public void onStarted() {
        addHistory("predistortion.history.started", List.of());
    }

    @Override
    public void onLinesApplied(List<String> names) {
        addHistory("predistortion.history.applied", names);
    }

    /** Its own history line: the restart follows the apply by the output
     *  latency and one full frame - seconds at the large FFT sizes. */
    @Override
    public void onLinesRestarted(List<String> names) {
        addHistory("predistortion.history.restarted", names);
    }

    @Override
    public void onLinesParked(List<String> names) {
        addHistory("predistortion.history.parked", names);
    }

    @Override
    public void onLinesUnparked(List<String> names) {
        addHistory("predistortion.history.unparked", names);
    }

    @Override
    public void onFinished(PredistortionEngine.StopReason reason, boolean hasResult) {
        worker.unregisterPredistortionEngine();
        if (dialog.isDisposed()) return;
        running = false;
        runEnd = Instant.now();
        // The provenance of a Save: the displayed result as the run ended.
        FftResult last = view.getLastResult();
        resultAtStop = last != null ? last.deepCopy() : null;
        startStopBtn.setEnabled(true);
        startStopBtn.setText(I18n.t("predistortion.button.start"));
        targetField.setEnabled(true);
        // Save writes the displayed result's provenance; without one there is
        // nothing to write the file from.
        if (hasResult && resultAtStop != null) saveBtn.setEnabled(true);
        terminalStatusKey = switch (reason) {
            case TARGET_REACHED -> "predistortion.status.targetReached";
            case ERROR          -> "predistortion.status.error";
            default             -> "predistortion.status.stopped";
        };
        // The target reached with a correction in force asks for the Save;
        // reached without one, nothing is there to save.
        boolean saveAsked = reason == PredistortionEngine.StopReason.TARGET_REACHED && hasResult;
        addHistory(saveAsked ? "predistortion.history.finished" : terminalStatusKey, List.of());
        refresh();
    }

    /** Appends an event to the history - its time now, the averages the
     *  progress shows now, the line names joined.  UI thread. */
    private void addHistory(String key, List<String> names) {
        history.add(new HistoryEntry(LocalTime.now(), fft.averagesInWindow(), key,
                String.join(NAME_SEPARATOR, names)));
        showHistory();
    }

    /** Renders the history in the current locale, each line after the time
     *  and the averages of its event, the newest last and in view; the idle
     *  hint while no run has started. */
    private void showHistory() {
        if (historyTable == null || historyTable.isDisposed()) return;
        historyTable.setRedraw(false);
        try {
            historyTable.removeAll();
            if (history.isEmpty()) {
                new TableItem(historyTable, SWT.NONE).setText(I18n.t("predistortion.status.idle"));
            }
            GC gc = new GC(historyTable);
            try {
                timeWidth = 0;
                averagesWidth = gc.textExtent(AVERAGES_RESERVE).x;
                for (HistoryEntry e : history) {
                    TableItem item = new TableItem(historyTable, SWT.NONE);
                    item.setText(e.names().isEmpty() ? I18n.t(e.key()) : I18n.t(e.key(), e.names()));
                    item.setData(e);
                    timeWidth     = Math.max(timeWidth, gc.textExtent(EVENT_TIME.format(e.time())).x);
                    averagesWidth = Math.max(averagesWidth, gc.textExtent(Integer.toString(e.averages())).x);
                }
            } finally {
                gc.dispose();
            }
            fitHistoryColumn();
            historyTable.showItem(historyTable.getItem(historyTable.getItemCount() - 1));
        } finally {
            historyTable.setRedraw(true);
        }
    }

    /** The one column as wide as the table's client area: never a
     *  sideways scroll. */
    private void fitHistoryColumn() {
        if (historyTable.isDisposed()) return;
        historyColumn.setWidth(historyTable.getClientArea().width);
    }

    /** Draws one history row: the time, the averages right-aligned under
     *  the widest figure, then the event; a row without an entry (the idle
     *  hint) is its text alone across the whole row. */
    private void paintHistoryRow(Event e) {
        TableItem item = (TableItem) e.item;
        int y = e.y + (e.height - e.gc.getFontMetrics().getHeight()) / 2;
        if (!(item.getData() instanceof HistoryEntry entry)) {
            e.gc.drawText(item.getText(), e.x, y, true);
            return;
        }
        String averages = Integer.toString(entry.averages());
        int averagesEnd = e.x + timeWidth + HISTORY_GAP_PX + averagesWidth;
        e.gc.drawText(EVENT_TIME.format(entry.time()), e.x, y, true);
        e.gc.drawText(averages, averagesEnd - e.gc.textExtent(averages).x, y, true);
        e.gc.drawText(item.getText(), averagesEnd + HISTORY_GAP_PX, y, true);
    }

    // -------------------------------------------------------------------------
    // Save / Apply
    // -------------------------------------------------------------------------

    private void onSaveOrApply() {
        if (savedPath == null) doSave();
        else                   doApply();
    }

    private void doSave() {
        FftResult r = resultAtStop;
        boolean dual = engine.isDualTone();
        if (r == null) return;
        if (dual ? engine.getIntermodInForce() == null : engine.getHarmonicsInForce() == null) return;

        FileDialog fd = new FileDialog(dialog, SWT.SAVE);
        fd.setText(I18n.t("predistortion.save.dialog"));
        fd.setFilterExtensions(new String[]{ "*.dpd" });
        fd.setFilterNames(new String[]{ I18n.t("predistortion.save.filter") });
        fd.setOverwrite(true);
        Preferences prefs = Preferences.instance();
        String folder = prefs.getGenDpdFolder();
        if (folder != null) fd.setFilterPath(folder);
        fd.setFileName(defaultDpdFileName(r));
        String picked = fd.open();
        if (picked == null) return;
        try {
            int bitDepth = prefs.current().getOutputBitDepth();
            AudioBackend audio = AudioBackend.instance();
            double amplitudeVrms = audio.calibrationOrDefault(false)
                    .amplitudeVrms(prefs.getGenAmplitudeLeft(), Channel.L);
            double adcFsVrms = audio.calibrationOrDefault(true).fsRms(Channel.L);
            if (dual) {
                engine.getIntermodInForce().writeDpd(Path.of(picked), buildHeader(r),
                        r.fundamentalHzRefined, r.fundamental2HzRefined, r.fundamentalDbFs,
                        r.sampleRate, bitDepth, amplitudeVrms,
                        engine.calResponseFor(r.channelLeft), adcFsVrms,
                        engine.dualToneFundamentalVrms());
            } else {
                engine.getHarmonicsInForce().writeDpd(Path.of(picked), buildHeader(r),
                        r.fundamentalHzRefined, r.fundamentalDbFs,
                        r.sampleRate, bitDepth, amplitudeVrms,
                        engine.calResponseFor(r.channelLeft), adcFsVrms);
            }
            savedPath = picked;
            prefs.setGenDpdFolder(new File(picked).getParent());
            prefs.save();
            saveBtn.setText(I18n.t("predistortion.button.apply"));
            terminalStatusKey = "predistortion.status.saved";
            addHistory(terminalStatusKey, List.of());
            log.info("Predistortion saved to {}", picked);
        } catch (Exception ex) {
            log.warn("Predistortion save failed", ex);
            Dialogs.error(dialog, I18n.t("predistortion.save.dialog"),
                    ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
        }
    }

    /** Suggested {@code .dpd} name encoding the measurement conditions:
     *  {@code predistortion-<THD|IMD>-<freq>[-<freq2>]-<fftSize>-<overlap>-<window>-<distPpm>.dpd}.
     *  Single tone, e.g. {@code predistortion-THD-1003_0518-2M-87_5-BH7-0_83.dpd}
     *  (THD in ppm: 0.83); dual tone carries both tone frequencies, e.g.
     *  {@code predistortion-IMD-19005_0661-20000_0611-2M-87_5-HFT248D-5_05.dpd}
     *  (IMD in ppm: 5.05).  Decimal points become underscores so the name stays
     *  path-friendly.  The random mode's token spells out its draw range from
     *  the enum's own bounds rather than its label, so the name still records
     *  the geometry that produced the measurement and follows those bounds if
     *  they change: {@code Rand_80_90}. */
    private String defaultDpdFileName(FftResult r) {
        boolean dual   = engine.isDualTone();
        String kind    = dual ? "IMD" : "THD";
        String freq    = String.format(Locale.US, "%.4f", r.fundamentalHzRefined).replace('.', '_');
        String freq2   = dual
                ? "-" + String.format(Locale.US, "%.4f", r.fundamental2HzRefined).replace('.', '_')
                : "";
        String size    = humanFftSize(r.fftSize);
        String overlap = r.overlap.isRandom()
                ? "Rand_" + pctToken(FftOverlap.RAND_MIN_OVERLAP_PCT)
                        + "_" + pctToken(FftOverlap.RAND_MAX_OVERLAP_PCT)
                : r.overlap.label.replace("%", "").replace('.', '_');
        String window  = r.windowType.name();
        double distPpm = engine.getHeldDistortionPct() * 1_000_000.0;   // % -> ppm % (THD single-tone / IMD dual-tone)
        String dist    = String.format(Locale.US, "%.2f", distPpm).replace('.', '_');
        return "predistortion-" + kind + "-" + freq + freq2 + "-" + size + "-" + overlap
                + "-" + window + "-" + dist + ".dpd";
    }

    /** An overlap percent as a filename token: the plain integer when the
     *  value is whole (80 -> 80), otherwise two decimals with the point as
     *  an underscore (93.75 -> 93_75). */
    private String pctToken(double pct) {
        return pct == Math.rint(pct)
                ? String.valueOf((int) pct)
                : String.format(Locale.US, "%.2f", pct).replace('.', '_');
    }

    /** Compact power-of-two FFT length: 2097152 -> "2M", 524288 -> "512k". */
    private String humanFftSize(int n) {
        if (n >= 1 << 20 && n % (1 << 20) == 0) return (n >> 20) + "M";
        if (n >= 1 << 10 && n % (1 << 10) == 0) return (n >> 10) + "k";
        return Integer.toString(n);
    }

    private void doApply() {
        if (savedPath == null) return;
        Preferences prefs = Preferences.instance();
        // Apply = LOAD the just-saved .dpd onto the running generator (no
        // restart).  The file is the single source of truth, so the live tone
        // and a later relaunch agree.  Persist the form + corrections path so
        // it survives a restart AND so the generator pane's Corrections field
        // shows the loaded file.
        //
        // The PATH is written before the FORM: switching the form live loads the
        // corrections configured for it, so the path has to be the new one by
        // then - written the other way round, the compensated tone comes up on
        // whatever .dpd was configured before this session.
        //
        // No explicit load of the file here: the run played the PLAIN sibling
        // (see formBeforeRun), so the form written below always differs from
        // the one in force, the generator's form listener fires, switches the
        // tone to the compensated form live and loads the file the path now
        // names - the case of re-applying to the SAME path included.
        GenSignalForm form = engine.isDualTone()
                ? GenSignalForm.DUAL_TONE_COMP : GenSignalForm.SINE_COMP;
        prefs.setGenDpd(form, savedPath);
        prefs.setGenSignalForm(form);
        prefs.save();
        applied = true;
        dialog.close();
    }

    private List<String> buildHeader(FftResult r) {
        Preferences prefs = Preferences.instance();
        List<String> h = new ArrayList<>();
        h.add("# Phonalyser DAC predistortion");
        h.add("# format_version=" + FileVersions.PREDISTORTION);
        h.add("# kind=predistortion");
        h.add("# gen_form="           + prefs.getGenSignalForm());
        if (engine.isDualTone()) {
            h.add("# gen_frequency1_hz=" + fmt(prefs.getGenDualToneFreq1Hz()));
            h.add("# gen_frequency2_hz=" + fmt(prefs.getGenDualToneFreq2Hz()));
        } else {
            h.add("# gen_frequency_hz="   + fmt(prefs.getGenFrequencyHz()));
        }
        DeviceCalibration dac = AudioBackend.instance().calibrationOrDefault(false);
        h.add("# gen_amplitude_vrms=" + fmt(dac.amplitudeVrms(prefs.getGenAmplitudeLeft(), Channel.L)));
        h.add("# gen_dither_bits="    + dac.ditherBits(prefs.getGenDither(), Channel.L));
        h.add("# fft_size="           + r.fftSize);
        h.add("# fft_window="         + prefs.getFftWindow());
        h.add("# fft_overlap="        + prefs.getFftOverlap().label);
        h.add("# fft_max_harmonic="   + prefs.getFftCalcMaxHarmonic());
        h.add("# fft_averages="       + r.frameCount);
        // The run's distortion is the engine's figure, every line held at its
        // local band top; the noise readouts are the displayed result's.
        FftResult live = view.getLastResult();
        h.add("# thd_pct="            + fmt(engine.getHeldDistortionPct()));
        h.add("# thd_plus_n_db="      + fmt(live != null ? -live.sinadDb : Double.NaN));
        h.add("# snr_db="             + fmt(live != null ? live.snrDb : Double.NaN));
        h.add("# dist_min_hz="        + fmt(r.snrFreqMin));
        h.add("# dist_max_hz="        + fmt(r.snrFreqMax));
        return h;
    }

    // -------------------------------------------------------------------------
    // Cancel
    // -------------------------------------------------------------------------

    private boolean handleCancel() {
        if (running) {
            engine.stop();
            worker.unregisterPredistortionEngine();
            running = false;
        }
        // A dialog that never started a run has touched neither the generator
        // nor the analyser, so closing it must leave both exactly as they were -
        // no revert, and above all no statistics reset, which would throw away a
        // deep average the operator collected before opening this window.
        if (!engaged) return true;
        // Undo what the run did to the generator, unless the user committed via
        // Apply.  The run hot-swapped its corrections onto the running tone and
        // played the plain sibling of the configured form, so the state to
        // return to is the form the preferences held before the run - only
        // Apply changes that.  A compensated form IS its saved correction file:
        // writing it back into the preferences makes the generator's form
        // listener switch the tone live, load the file and forget the
        // hot-swapped set.  A plain form carries no corrections at all: the
        // tables are dropped over the bus, and the generator announces that as
        // the signal change the FFT and the scope restart on - no rebuild of
        // the tone is needed for it.  The single-tone .dpd slot keeps its path
        // while the form is plain, so a plain form must never consult it - that
        // is how a closed wizard used to leave an old correction file playing
        // under the plain-sine label.
        // The analyser goes down BEFORE the generator is touched, on the same
        // path the Record button takes, and comes back up on the restored tone
        // below: no tick then analyses the seam in which the tone is rebuilt.
        boolean wasRecording = fft.isRecording();
        if (wasRecording) fft.stopRecording();
        if (!applied) {
            if (formBeforeRun != null && formBeforeRun.isCompensated()) {
                Preferences.instance().setGenSignalForm(formBeforeRun);
            } else {
                MessageBus.instance().publish(Events.GENERATOR_CORRECTIONS,
                        new GeneratorCorrectionsRequest(GeneratorCorrectionsRequest.Kind.CLEAR,
                                null, null, null, null, null));
            }
        }
        // The run left the FFT in infinite coherent averaging with a deep
        // accumulator full of predistortion-run frames (and, when reverting,
        // the signal just changed); clear the statistics + accumulator so the
        // live view restarts clean instead of averaging across the change.
        fft.resetStatistics();
        // Back up on the restored tone - an analyser that was not recording
        // when this window closed stays as it was.
        if (wasRecording) fft.startRecording();
        return true;
    }

    // -------------------------------------------------------------------------
    // Formatting
    // -------------------------------------------------------------------------

    private String fmtPct(double v) {
        return Double.isFinite(v) ? String.format(Locale.US, "%.8f %%", v) : "-";
    }

    /** Absolute-level cell (fundamental, noise floor). */
    private String fmtDbv(double v) {
        return Double.isFinite(v) ? String.format(Locale.US, "%.2f dBV", v) : "-";
    }

    /** Ratio cell (THD+N, TD+N, SNR, SINAD) - dB against the fundamental. */
    private String fmtDb(double v) {
        return Double.isFinite(v) ? String.format(Locale.US, "%.2f dB", v) : "-";
    }

    private String fmtHz(double v) {
        return Double.isFinite(v) ? String.format(Locale.US, "%.4f", v) : "-";
    }

    private String fmt(double v) {
        return String.format(Locale.US, "%.6f", v);
    }
}
