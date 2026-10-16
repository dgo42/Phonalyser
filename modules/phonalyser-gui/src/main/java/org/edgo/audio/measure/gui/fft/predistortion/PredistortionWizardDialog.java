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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
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
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.FftOverlap;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.fft.ImdResult;
import org.edgo.audio.measure.gui.bind.Bindings;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.common.ShellIcons;
import org.edgo.audio.measure.gui.fft.FftController;
import org.edgo.audio.measure.gui.fft.FftView;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.widgets.NumericStepField;
import org.edgo.audio.measure.gui.widgets.UnitFamily;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.DeviceCalibration;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Modal wizard that runs the one-shot DAC predistortion measurement and
 * saves / applies the result.  The measurement is a
 * {@link PredistortionEngine} on the FFT worker: Start registers it there,
 * the end of the run and the window's close unregister it.
 *
 * <p>Rendering follows the (historic) PID-autotune dialog: an intro that
 * explains the procedure, the line table, an FFT-settings group and a
 * progress group of live readouts - all refreshed by a ~{@value #TIMER_MS} ms
 * timer that polls the displayed FFT result and, while a run is on, the
 * engine's line readouts.  The line table is {@value #LINE_ROWS} rows of
 * the lines' cells, two per row, each with the wobble of the line's
 * phase and the limit it has to fall within.
 *
 * <p>Lifecycle: <b>Start</b> begins the run (becoming <b>Stop</b>); the run
 * ends by itself once every gated line's phase has settled.  <b>Save</b>
 * writes the {@code .dpd} predistortion file; it then becomes <b>Apply</b>,
 * which writes the file and the compensated form into the preferences.
 * Closing the window detaches a running engine and changes nothing else.
 */
@Log4j2
public final class PredistortionWizardDialog implements PredistortionEngine.Listener {

    /** Floor of the target THD (%): each line's phase limit follows from the
     *  target, and a zero target would ask every line for a zero wobble. */
    private static final double MIN_TARGET_PCT = 0.1e-6;
    /** The target THD's unit in the suggested file name: millionths of a
     *  percent, so a target of 1e-6 % reads 1. */
    private static final double MILLIONTHS     = 1_000_000.0;
    /** Bounds and wheel step of the least-averages field: at least a full
     *  window of values past the skipped first frames. */
    private static final double MIN_AVERAGES_MIN        = PredistortionEngine.SKIPPED_FRAMES
                                                        + PredistortionEngine.WINDOW_VALUES;
    private static final double MIN_AVERAGES_MAX        = 1_000_000;
    private static final double MIN_AVERAGES_WHEEL_STEP = 100;
    /** The settings row: two label-and-field pairs side by side, and the
     *  room between the pairs. */
    private static final int    SETTINGS_COLUMNS = 4;
    private static final int    SETTINGS_GAP_PX  = 16;
    /** Live-refresh cadence (ms) for the polling render - fast enough that the
     *  averages readout climbs smoothly. */
    private static final int    TIMER_MS = 100;
    /** The progress's time: hours, minutes and seconds since Start. */
    private static final String ELAPSED_FORMAT = "%02d:%02d:%02d";
    /** The table's columns: two line cells side by side in each line row. */
    private static final int    COLUMNS = 2;
    /** The table's rows, every one a line row. */
    private static final int    LINE_ROWS = 4;
    /** The lines the cells show: a single tone's H2 .. H9, or the dual
     *  tone's eight sideband products. */
    private static final int    LINE_CELLS = LINE_ROWS * COLUMNS;
    /** Between a line's name and its value in a line cell:
     *  "H2 - σ 0.012° (42.0 dB, 0.016°)". */
    private static final String CELL_SEPARATOR = " - σ ";
    /** A line's wobble in its cell: three decimals, in degrees. */
    private static final String WOBBLE_FORMAT = "%.3f°";
    /** After the wobble, in one bracket pair: the line's clearance over its
     *  local band top, one decimal, in dB, then after a comma its limit,
     *  three decimals, in degrees. */
    private static final String CLEARANCE_FORMAT = "%.1f dB";
    /** The clearance shown for a line held at its band top. */
    private static final String CLAMPED_CLEARANCE = "0 dB";
    private static final String LIMIT_FORMAT     = ", %.3f°";
    /** The limit shown for a line no phase error can push over its share. */
    private static final String UNLIMITED        = ", ∞";
    /** A line cell's value while the line has none to show. */
    private static final String NO_VALUE = "-";
    /** Between the two cells of a line row in the saved file's header. */
    private static final String HEADER_CELL_GAP = "    ";
    /** The three buttons' fixed width: 1.5 times the 48 the widest text,
     *  "Close", takes at 100 % scaling. */
    private static final int    BUTTON_WIDTH = 72;
    /** The wobble's colour within its limit and over it. */
    private static final int    WITHIN_LIMIT_COLOR = SWT.COLOR_DARK_GREEN;
    private static final int    OVER_LIMIT_COLOR   = SWT.COLOR_RED;

    private final Shell               parentShell;
    private final FftController       controller;
    private final FftView             view;

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
    private Label            minAveragesLabel;
    private NumericStepField minAveragesField;
    /** The line table: the {@value #LINE_CELLS} line cells in
     *  {@value #LINE_ROWS} rows of two. */
    private Table            lineCellTable;
    /** The table's first column: the left line cell of each row. */
    private TableColumn      leftCellColumn;
    /** The table's second column: the right line cell of each row. */
    private TableColumn      rightCellColumn;
    private Label            fftSizeLbl, fftRateLbl, fftWindowLbl;
    private Label            timeLbl, curThdLbl, thdNLbl, snrLbl, sinadLbl, fundLbl, floorLbl, avgLbl;
    private Button           startStopBtn;
    private Button           saveBtn;
    private Button           cancelBtn;

    /** Set while {@link #keepIntroWhole()} resizes the window itself, so
     *  the resize it causes is not judged again. */
    private boolean          fitting;

    private PredistortionEngine engine;
    private RunState state = RunState.IDLE;
    /** When the last run started and ended - the progress's time; the start
     *  {@code null} before the first run, the end {@code null} while it runs. */
    private Instant runStart, runEnd;
    private boolean timerArmed;
    private String  savedPath;
    /** The lines' names, wobbles and bracket texts the line cells show, row
     *  by row and left to right; a cell reads name, {@link #CELL_SEPARATOR},
     *  wobble, brackets.  The wobble's colour per cell, {@code null} for the
     *  table's own while the line has no wobble or no limit to judge it
     *  against.  UI-thread confined. */
    private final String[] lineNames        = new String[LINE_CELLS];
    private final String[] lineWobbles      = new String[LINE_CELLS];
    private final String[] lineBrackets     = new String[LINE_CELLS];
    private final Color[]  lineWobbleColors = new Color[LINE_CELLS];
    /** A deep copy of the displayed result as the run ended - the
     *  provenance a Save writes; {@code null} before a run has ended. */
    private FftResult resultAtStop;

    public PredistortionWizardDialog(Shell parent, FftController controller, FftView view) {
        this.parentShell = parent;
        this.controller  = controller;
        this.view        = view;
    }

    /** Builds and shows the wizard and returns at once, with no event loop of
     *  its own: the application's loop dispatches the window's events, the
     *  shell's modality keeps the rest of the application out.  Returns the
     *  shell - the automation capture drives it via {@link #setTargetPct},
     *  {@link #pressStart}, {@link #isTargetReached}, {@link #retranslate}
     *  and {@link #getContent}. */
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

        lineCellTable = new Table(content, SWT.SINGLE | SWT.V_SCROLL);
        leftCellColumn = new TableColumn(lineCellTable, SWT.LEFT);
        rightCellColumn = new TableColumn(lineCellTable, SWT.LEFT);
        // Rows are only read: no selection.
        lineCellTable.addListener(SWT.Selection, e -> lineCellTable.deselectAll());
        // As high as the line rows, in a taller window too: the height hint
        // is measured on the rendered rows after the first layout, below.
        GridData lineCellGd = new GridData(SWT.FILL, SWT.TOP, true, false);
        lineCellGd.widthHint  = 405;
        lineCellTable.setLayoutData(lineCellGd);
        lineCellTable.addListener(SWT.Resize, e -> fitLineCellColumns());
        // The cells draw their own text, the wobble in its own colour: the
        // table's text drawing is switched off and each cell is painted in
        // three pieces.
        lineCellTable.addListener(SWT.EraseItem, e -> e.detail &= ~SWT.FOREGROUND);
        lineCellTable.addListener(SWT.PaintItem, this::paintLineCell);
        for (int row = 0; row < LINE_ROWS; row++) {
            new TableItem(lineCellTable, SWT.NONE);
        }
        // The engine reaches this window on the UI thread with its lines
        // after every result and with its own end of a run.  It lives as long
        // as the window: created here, it names the lines of the generator's
        // form in the cells before the first Start.  The loaded .frc
        // corrections go with it, so it de-embeds their phase when computing
        // the DAC correction.
        engine = new PredistortionEngine(controller.getCorrectionStore(), this);
        showLineReadouts(engine.readouts());

        buildFftGroup();
        buildProgressGroup();
        buildSettings();
        buildButtonBar();

        dialog.addListener(SWT.Close, e -> handleCancel());

        refresh();
        dialog.pack();
        // The table exactly as high as its rows as they render: the bottom
        // edge of the last row, laid out, is the height hint - the item height
        // the table reports before its first layout is not trusted.  The
        // table's computeSize adds its own frame to the hint, so the trim
        // counts once.  It also adds the height of the horizontal scrollbar a
        // Windows table always carries, though the two columns always fit the
        // client width and the bar never shows: that share - what computeSize
        // adds to a zero hint beyond the frame - comes off the hint.  The
        // content is laid out again on the measured hint.
        Rectangle lastRow = lineCellTable.getItem(LINE_ROWS - 1).getBounds();
        int scrollbarShare = lineCellTable.computeSize(SWT.DEFAULT, 0).y
                - 2 * lineCellTable.getBorderWidth();
        lineCellGd.heightHint = lastRow.y + lastRow.height - scrollbarShare;
        content.layout(true, true);
        // pack() wraps the intro at its width hint, the column is as wide as
        // its widest row: the height the content needs at the packed width,
        // else the spare height is left empty under the buttons.
        Rectangle trim = dialog.computeTrim(0, 0, 0, 0);
        int width = dialog.getSize().x;
        dialog.setSize(width, contentHeightAt(width - trim.width) + trim.height);
        // The packed width is the narrowest the window gets; its height
        // always follows from its width.
        dialog.setMinimumSize(width, 0);
        // Bottom-right corner on the main window's, clear of the spectrum.
        Rectangle parentBounds = parentShell.getBounds();
        Point size = dialog.getSize();
        dialog.setLocation(parentBounds.x + parentBounds.width - size.x,
                parentBounds.y + parentBounds.height - size.y);
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
        // One row: the least averages on the left, the target THD on the right.
        GridLayout gl = new GridLayout(SETTINGS_COLUMNS, false);
        gl.marginWidth = 0; gl.marginHeight = 0; gl.horizontalSpacing = 8;
        c.setLayout(gl);

        minAveragesLabel = new Label(c, SWT.NONE);
        minAveragesLabel.setText(I18n.t("predistortion.minAverages"));
        // A plain count, as the FFT pane's stop-after-N field: the engine
        // settles a run only on a result of at least this many averages.
        minAveragesField = new NumericStepField(c, UnitFamily.NONE, MIN_AVERAGES_MIN, MIN_AVERAGES_MAX,
                MIN_AVERAGES_WHEEL_STEP, 1, 0, 90);
        minAveragesField.setToolTipText(I18n.t("predistortion.minAverages.tooltip"));
        // Two-way bound to their preferences: the last-used values survive
        // reopening the wizard AND restarting the app, never reset by a
        // completed run, and the engine reads them from there at its start.
        Bindings.stepFieldInt(minAveragesField, Preferences.instance().predistortionMinAveragesProperty());

        targetLabel = new Label(c, SWT.NONE);
        GridData targetLabelGd = new GridData(SWT.END, SWT.CENTER, true, false);
        targetLabelGd.horizontalIndent = SETTINGS_GAP_PX;
        targetLabel.setLayoutData(targetLabelGd);
        // PERCENT policy, like the other % fields.  8 decimals so a sub-ppm
        // target distortion can be entered; the floor of 0.1e-6 % keeps the
        // target above zero, which the stop rule needs.
        targetField = new NumericStepField(c, UnitFamily.PERCENT, MIN_TARGET_PCT, 100.0, 8, 90);
        showTargetLabel();
        Bindings.stepField(targetField, Preferences.instance().predistortionTargetPctProperty());
    }

    /** The target field's label and tooltip for the generator's form: the
     *  target IMD for a dual tone, the target THD otherwise. */
    private void showTargetLabel() {
        String key = Preferences.instance().getGenSignalForm().isDualTone()
                ? "predistortion.targetImd" : "predistortion.targetThd";
        targetLabel.setText(I18n.t(key));
        targetField.setToolTipText(I18n.t(key + ".tooltip"));
    }

    private void buildButtonBar() {
        Composite bar = new Composite(content, SWT.NONE);
        bar.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
        GridLayout gl = new GridLayout(3, true);
        gl.marginWidth = 0; gl.marginHeight = 0;
        bar.setLayout(gl);

        startStopBtn = new Button(bar, SWT.PUSH);
        startStopBtn.setText(I18n.t("predistortion.button.start"));
        startStopBtn.setLayoutData(buttonLayoutData());
        startStopBtn.addListener(SWT.Selection, e -> onStartStop());

        saveBtn = new Button(bar, SWT.PUSH);
        saveBtn.setText(I18n.t("predistortion.button.save"));
        saveBtn.setEnabled(false);
        saveBtn.setLayoutData(buttonLayoutData());
        saveBtn.addListener(SWT.Selection, e -> onSaveOrApply());

        cancelBtn = new Button(bar, SWT.PUSH);
        cancelBtn.setText(I18n.t("common.close"));
        cancelBtn.setLayoutData(buttonLayoutData());
        cancelBtn.addListener(SWT.Selection, e -> dialog.close());
    }

    /** A button's layout data: {@link #BUTTON_WIDTH} wide. */
    private GridData buttonLayoutData() {
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.widthHint = BUTTON_WIDTH;
        return gd;
    }

    // -------------------------------------------------------------------------
    // Automation capture hooks - drive the wizard from a body script.  An
    // in-place language re-translate leaves the measurement and
    // the FFT averages untouched, so ONE collection yields a screenshot per
    // language.
    // -------------------------------------------------------------------------

    /** Sets the target-THD field (%). */
    public void setTargetPct(double pct) {
        if (targetField != null) targetField.setValue(pct);
    }

    /** Presses Start if idle (no-op while a run is in progress). */
    public void pressStart() {
        if (state != RunState.RUNNING) onStartStop();
    }

    /** {@code true} once a run ended settled, until a Save of its result. */
    public boolean isTargetReached() {
        return state == RunState.TARGET_REACHED;
    }

    /** Re-reads every UI label in the current locale, in place, WITHOUT rebuilding
     *  the widgets or the panes behind the wizard - so a language switch during a
     *  capture leaves the measurement and the FFT averages untouched.  The
     *  dynamic labels (FFT settings, metrics) re-translate on the
     *  {@link #refresh()} below; the line cells hold no translated text;
     *  this re-sets the static ones. */
    public void retranslate() {
        if (dialog == null || dialog.isDisposed()) return;
        dialog.setText(I18n.t("predistortion.wizard.title"));
        intro.setText(I18n.t("predistortion.intro"));
        fftGroup.setText(I18n.t("predistortion.group.fft"));
        progressGroup.setText(I18n.t("predistortion.group.progress"));
        showTargetLabel();
        minAveragesLabel.setText(I18n.t("predistortion.minAverages"));
        startStopBtn.setText(I18n.t(state == RunState.RUNNING
                ? "predistortion.button.stop" : "predistortion.button.start"));
        saveBtn.setText(I18n.t(savedPath == null ? "predistortion.button.save" : "predistortion.button.apply"));
        cancelBtn.setText(I18n.t("common.close"));
        refresh();
        content.layout(true, true);
        keepIntroWhole();
    }

    /** Keeps the window exactly as high as its content at the window's
     *  width: only the wrapped intro varies, so the height follows from the
     *  width. */
    private void keepIntroWhole() {
        if (fitting || dialog.isDisposed()) return;
        Point size = dialog.getSize();
        Rectangle trim = dialog.computeTrim(0, 0, 0, 0);
        int height = contentHeightAt(size.x - trim.width) + trim.height;
        if (height == size.y) return;
        fitting = true;
        try {
            dialog.setSize(size.x, height);
        } finally {
            fitting = false;
        }
    }

    /** The height the content needs at client width {@code width}, the
     *  intro wrapped to it. */
    private int contentHeightAt(int width) {
        return content.computeSize(width, SWT.DEFAULT, true).y;
    }

    // -------------------------------------------------------------------------
    // Live refresh timer (polls the displayed result and the engine - the
    // autotune-dialog render style)
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

        // Dual-tone signal -> the measurement is the INTERMOD, not THD; show
        // IMD / D+N in place of THD / THD+N.
        // The products ride on the live result; one without them (no second
        // tone found) reads "-" - a THD figure measures a single tone and says
        // nothing about the intermod.
        boolean dual = prefs.getGenSignalForm().isDualTone();
        ImdResult imd = (dual && live != null) ? live.imd : null;

        // Live distortion straight off the running FFT.
        curThdLbl.setText(dual
                ? metric("currentImd", imd != null ? fmtPct(imd.imdPwrPct) : "-")
                : metric("currentThd", live != null ? fmtPct(live.thdPct) : "-"));
        Duration elapsed = runStart != null
                ? Duration.between(runStart, runEnd != null ? runEnd : Instant.now()) : null;
        timeLbl .setText(metric("time", elapsed != null
                ? String.format(Locale.ROOT, ELAPSED_FORMAT,
                        elapsed.toHours(), elapsed.toMinutesPart(), elapsed.toSecondsPart())
                : "-"));
        // What is inside the average - the SAME number the FFT pane shows: the
        // averages performed since the last reset, NOT the per-call frameCount
        // (only the 2-3 frames of one analyze() segment).
        avgLbl  .setText(metric("averages", Integer.toString(controller.averagesInWindow())));
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
        // A dual tone's fundamental is the two tones' combined level: the
        // root of the sum of their squares.
        double fundDbFs = imd != null
                ? 20.0 * Math.log10(Math.hypot(imd.f1Mag, imd.f2Mag))
                : live != null ? live.fundamentalDbFs : Double.NaN;
        fundLbl .setText(metric("fund",     live != null ? fmtDbv(fundDbFs + dbvOffsetDb) : "-"));
        floorLbl.setText(metric("floor",    live != null ? fmtDbv(live.noisePeakFloorDbFs() + dbvOffsetDb) : "-"));
    }

    private String metric(String key, String value) {
        return I18n.t("predistortion.metric." + key) + ":  " + value;
    }

    // -------------------------------------------------------------------------
    // Start / Stop
    // -------------------------------------------------------------------------

    private void onStartStop() {
        if (state == RunState.RUNNING) {
            // The end by hand is this window's own: the worker stops handing
            // the engine results, and the state here is set in place; a
            // snapshot or a settle still on its way from the worker is
            // ignored.  The cells keep the last result's lines.
            controller.unregisterPredistortionEngine();
            endRun(RunState.STOPPED, false);
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
        // The run measures the running FFT; the analyser is started and stopped
        // with the FFT pane's Record button alone, so its state stays in sync.
        if (!controller.isRecording()) {
            Dialogs.error(dialog, I18n.t("predistortion.wizard.title"),
                    I18n.t("predistortion.error.notRecording"));
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

        state = RunState.RUNNING;
        runStart = Instant.now();
        runEnd = null;
        savedPath = null;
        resultAtStop = null;
        saveBtn.setEnabled(false);
        saveBtn.setText(I18n.t("predistortion.button.save"));
        // The target persists on every edit of the field and the engine reads
        // it from the preferences; it stays fixed while the run is on.
        targetField.setEnabled(false);
        minAveragesField.setEnabled(false);
        startStopBtn.setText(I18n.t("predistortion.button.stop"));
        // The form may have changed since the window opened.
        showTargetLabel();

        // The run measures the converter's own distortion: a compensated form
        // gives way to its plain sibling through the preferences before the
        // engine starts, and the generator's form binding switches the running
        // tone to it live - the same tones, no correction table subtracted.
        // Nothing restores the form: Apply sets the compensated one with the
        // new file, Close leaves the plain one.
        if (form.isCompensated()) {
            prefs.setGenSignalForm(form.plainSibling());
        }
        // The run ends on its settle.  With stop-after-N on, the worker
        // pauses the analysis once N averages are performed, no result
        // reaches the engine any more, and the run would never end; the
        // preference stays off until it is switched on again by hand.
        if (prefs.isFftStopAfterNEnabled()) {
            prefs.setFftStopAfterNEnabled(false);
        }

        engine.start();
        // The run begins from zero: the counter and the accumulator are
        // emptied, so the values the engine's windows take come from frames
        // of this run only.
        controller.resetStatisticsAfterSignalChange();
        controller.registerPredistortionEngine(engine);
    }

    // -------------------------------------------------------------------------
    // Engine callbacks (UI thread)
    // -------------------------------------------------------------------------

    @Override
    public void onResult(List<PredistortionEngine.LineReadout> readouts) {
        if (dialog.isDisposed() || state != RunState.RUNNING) return;
        showLineReadouts(readouts);
    }

    @Override
    public void onFinished(PredistortionEngine.StopReason reason, boolean hasResult) {
        if (dialog.isDisposed() || state != RunState.RUNNING) return;
        controller.unregisterPredistortionEngine();
        endRun(switch (reason) {
            case SETTLED -> RunState.TARGET_REACHED;
            case ERROR   -> RunState.ERROR;
        }, hasResult);
    }

    /** The window at the end of a run, by the engine or by hand: {@code end}
     *  as the state, the run's end time, the displayed result as the
     *  provenance of a Save, the buttons and the fields back to the idle
     *  state, Save enabled when the run has a correction to write. */
    private void endRun(RunState end, boolean hasResult) {
        state = end;
        runEnd = Instant.now();
        FftResult last = view.getLastResult();
        resultAtStop = last != null ? last.deepCopy() : null;
        startStopBtn.setEnabled(true);
        startStopBtn.setText(I18n.t("predistortion.button.start"));
        targetField.setEnabled(true);
        minAveragesField.setEnabled(true);
        // Save writes the displayed result's provenance; without one there is
        // nothing to write the file from.
        if (hasResult && resultAtStop != null) saveBtn.setEnabled(true);
        refresh();
    }

    // -------------------------------------------------------------------------
    // Line cells
    // -------------------------------------------------------------------------

    /**
     * Takes the lines' names, wobbles, clearances and limits from
     * {@code readouts}, the engine's snapshot, into the line cells, in
     * place.  A cell shows its line's wobble while the line is gated and the
     * wobble is a number, otherwise {@link #NO_VALUE}; then one bracket pair
     * with the line's clearance over its local band top, 0 dB for a line
     * held at that band top (no positive clearance), and, while the line is
     * gated and the limit is a number, a comma and the limit.  A shown
     * wobble with a limit is green within it and red over it.
     */
    private void showLineReadouts(List<PredistortionEngine.LineReadout> readouts) {
        Display display = lineCellTable.getDisplay();
        for (int cell = 0; cell < LINE_CELLS; cell++) {
            PredistortionEngine.LineReadout line = readouts.get(cell);
            lineNames[cell] = line.name();
            boolean wobbleShown = line.gated() && !Double.isNaN(line.wobbleDeg());
            String wobble = wobbleShown
                    ? String.format(Locale.US, WOBBLE_FORMAT, line.wobbleDeg())
                    : NO_VALUE;
            lineWobbleColors[cell] = !wobbleShown || Double.isNaN(line.limitDeg()) ? null
                    : display.getSystemColor(line.wobbleDeg() <= line.limitDeg()
                            ? WITHIN_LIMIT_COLOR : OVER_LIMIT_COLOR);
            String clearance = line.clearanceDb() > 0.0
                    ? String.format(Locale.US, CLEARANCE_FORMAT, line.clearanceDb())
                    : CLAMPED_CLEARANCE;
            String limit = !line.gated() || Double.isNaN(line.limitDeg()) ? ""
                    : Double.isInfinite(line.limitDeg()) ? UNLIMITED
                    : String.format(Locale.US, LIMIT_FORMAT, line.limitDeg());
            lineWobbles[cell]  = wobble;
            lineBrackets[cell] = " (" + clearance + limit + ")";
        }
        for (int row = 0; row < LINE_ROWS; row++) {
            fillLineRow(lineCellTable.getItem(row), row);
        }
    }

    /** Paints one line cell's text where the table would: the name with
     *  {@link #CELL_SEPARATOR}, the wobble in its colour, the brackets - in
     *  the table's own colour around the wobble, clipped to the cell. */
    private void paintLineCell(Event e) {
        TableItem item = (TableItem) e.item;
        int cell = lineCellTable.indexOf(item) * COLUMNS + e.index;
        Rectangle text = item.getTextBounds(e.index);
        GC gc = e.gc;
        Rectangle clip = gc.getClipping();
        gc.setClipping(clip.intersection(item.getBounds(e.index)));
        String head = lineNames[cell] + CELL_SEPARATOR;
        int x = text.x;
        int y = text.y + (text.height - gc.getFontMetrics().getHeight()) / 2;
        gc.drawText(head, x, y, true);
        x += gc.textExtent(head).x;
        Color plain = gc.getForeground();
        if (lineWobbleColors[cell] != null) gc.setForeground(lineWobbleColors[cell]);
        gc.drawText(lineWobbles[cell], x, y, true);
        x += gc.textExtent(lineWobbles[cell]).x;
        gc.setForeground(plain);
        gc.drawText(lineBrackets[cell], x, y, true);
        gc.setClipping(clip);
    }

    /** Sets the two cells of line row {@code row}. */
    private void fillLineRow(TableItem item, int row) {
        for (int column = 0; column < COLUMNS; column++) {
            item.setText(column, cellText(row * COLUMNS + column));
        }
    }

    /** A line cell's text: the line's name, {@link #CELL_SEPARATOR}, its
     *  wobble and its brackets. */
    private String cellText(int cell) {
        return lineNames[cell] + CELL_SEPARATOR + lineWobbles[cell] + lineBrackets[cell];
    }

    /** The two columns, half the table's client width each and together as
     *  wide as it: never a sideways scroll. */
    private void fitLineCellColumns() {
        if (lineCellTable.isDisposed()) return;
        int clientWidth = lineCellTable.getClientArea().width;
        int halfWidth = clientWidth / COLUMNS;
        leftCellColumn.setWidth(halfWidth);
        rightCellColumn.setWidth(clientWidth - halfWidth);
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
            state = RunState.SAVED;
            if (log.isInfoEnabled()) {
                log.info("Predistortion saved to {}", picked);
            }
        } catch (Exception ex) {
            if (log.isWarnEnabled()) {
                log.warn("Predistortion save failed", ex);
            }
            Dialogs.error(dialog, I18n.t("predistortion.save.dialog"),
                    ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
        }
    }

    /** The displayed result's distortion as the run ended, in %: its THD for
     *  a single tone, its IMD for a dual tone - NaN when the dual-tone result
     *  carries no products. */
    private double displayedDistortionPct(FftResult r) {
        if (!engine.isDualTone()) return r.thdPct;
        return r.imd != null ? r.imd.imdPwrPct : Double.NaN;
    }

    /** Suggested {@code .dpd} name encoding the measurement conditions:
     *  {@code predistortion-<THD|IMD>-<freq>[-<freq2>]-<fftSize>-<overlap>-<window>[-pilot|-ref]-<target>-<averages>.dpd}.
     *  The phase alignment the run used names its mode, pilot tones or the
     *  reference channel, and is left out without one; the target THD is in
     *  millionths of a percent ({@code 1} for 1e-6 %, {@code 0_5} for
     *  0.5e-6 %); the averages are the frames the run ended on.  Single tone,
     *  e.g. {@code predistortion-THD-1003_0518-2M-87_5-BH7-pilot-1-1234.dpd};
     *  dual tone carries both tone frequencies, e.g.
     *  {@code predistortion-IMD-19005_0661-20000_0611-2M-87_5-HFT248D-ref-0_5-2048.dpd}.
     *  Decimal points become underscores so the name stays path-friendly.
     *  The random mode's token spells out its draw range from the enum's own
     *  bounds rather than its label, so the name still records the geometry
     *  that produced the measurement and follows those bounds if they change:
     *  {@code Rand_80_90}. */
    private String defaultDpdFileName(FftResult r) {
        Preferences prefs = Preferences.instance();
        boolean dual   = engine.isDualTone();
        String kind    = dual ? "IMD" : "THD";
        String freq    = String.format(Locale.US, "%.4f", r.fundamentalHzRefined).replace('.', '_');
        String freq2   = dual
                ? "-" + String.format(Locale.US, "%.4f", r.fundamental2HzRefined).replace('.', '_')
                : "";
        String size    = humanFftSize(r.fftSize);
        String overlap = r.overlap.isRandom()
                ? "Rand_" + numberToken(FftOverlap.RAND_MIN_OVERLAP_PCT)
                        + "_" + numberToken(FftOverlap.RAND_MAX_OVERLAP_PCT)
                : r.overlap.label.replace("%", "").replace('.', '_');
        String window  = r.windowType.name();
        String mode    = switch (prefs.getFftFundamentalPhaseAlignment()) {
            case PILOT_TONE        -> "-pilot";
            case REFERENCE_CHANNEL -> "-ref";
            default                -> "";
        };
        String target  = numberToken(prefs.getPredistortionTargetPct() * MILLIONTHS);
        return "predistortion-" + kind + "-" + freq + freq2 + "-" + size + "-" + overlap
                + "-" + window + mode + "-" + target + "-" + r.frameCount + ".dpd";
    }

    /** A number as a filename token: the plain integer when the value is
     *  whole (80 -> 80), otherwise up to three decimals without trailing
     *  zeros, the point as an underscore (93.75 -> 93_75, 0.5 -> 0_5). */
    private String numberToken(double value) {
        if (value == Math.rint(value)) return String.valueOf((long) value);
        String decimals = String.format(Locale.US, "%.3f", value);
        while (decimals.endsWith("0")) {
            decimals = decimals.substring(0, decimals.length() - 1);
        }
        return decimals.replace('.', '_');
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
        // Apply writes the preferences and nothing else: the form + the
        // corrections path, persisted so they survive a restart AND so the
        // generator pane's Corrections field shows the saved file.
        //
        // The PATH is written before the FORM.  The preferences are two-way
        // bound: GeneratorController answers the form write by restarting the
        // tone on the new form, built from the preferences as they then stand
        // - so the path has to be the new one by then, or the compensated tone
        // comes up on whatever .dpd was configured before; it answers the path
        // write by loading the new file onto a tone that is already
        // compensated.
        GenSignalForm form = engine.isDualTone()
                ? GenSignalForm.DUAL_TONE_COMP : GenSignalForm.SINE_COMP;
        prefs.setGenDpd(form, savedPath);
        prefs.setGenSignalForm(form);
        prefs.save();
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
        // The line table as the run ended: each line's phase wobble and its
        // limit in brackets, two lines per row as the window shows them.
        h.add("# phase_wobble: achieved (clearance over the local band top, limit) per line");
        for (int row = 0; row < LINE_ROWS; row++) {
            h.add("# " + cellText(row * COLUMNS) + HEADER_CELL_GAP + cellText(row * COLUMNS + 1));
        }
        // The distortion is the displayed result's as the run ended; the
        // noise readouts are the displayed result's.
        FftResult live = view.getLastResult();
        h.add("# thd_pct="            + fmt(displayedDistortionPct(r)));
        h.add("# thd_plus_n_db="      + fmt(live != null ? -live.sinadDb : Double.NaN));
        h.add("# snr_db="             + fmt(live != null ? live.snrDb : Double.NaN));
        h.add("# dist_min_hz="        + fmt(r.snrFreqMin));
        h.add("# dist_max_hz="        + fmt(r.snrFreqMax));
        return h;
    }

    // -------------------------------------------------------------------------
    // Close
    // -------------------------------------------------------------------------

    /** The window's close, by Close or the close box: a running engine is
     *  detached from the worker.  Nothing else - the generator, the
     *  analyser, the recording and the statistics stay as they are. */
    private void handleCancel() {
        if (state != RunState.RUNNING) return;
        controller.unregisterPredistortionEngine();
        state = RunState.STOPPED;
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
