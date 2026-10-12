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
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.fft.ImdResult;
import org.edgo.audio.measure.gui.common.ShellIcons;
import org.edgo.audio.measure.gui.fft.FftController;
import org.edgo.audio.measure.gui.fft.FftView;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.widgets.NumericStepField;
import org.edgo.audio.measure.gui.widgets.UnitFamily;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;

import lombok.Getter;

/**
 * The DAC predistortion window: an intro that explains the procedure, the
 * event history of the window's runs, an FFT-settings group and a progress
 * group of live readouts - refreshed by a ~{@value #TIMER_MS} ms timer that
 * polls the displayed FFT result - and the target distortion.  No
 * measurement runs from this window yet: Start and Save are disabled.
 */
public final class PredistortionWizardDialog {

    /** One line of the event history: when it happened, the averages the
     *  progress showed then, its message key and
     *  the names of the lines it is about, joined, or empty for a line
     *  without names. */
    private record HistoryEntry(LocalTime time, int averages, String key, String names) {}

    /** Floor of the target THD (%): a zero target could never be reached. */
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

    private final Shell               parentShell;
    private final FftController       fft;
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

    private boolean timerArmed;
    /** The entries {@link #historyTable} shows, the newest last.  UI-thread
     *  confined. */
    private final List<HistoryEntry> history = new ArrayList<>();

    public PredistortionWizardDialog(Shell parent, FftController fft, FftView view) {
        this.parentShell = parent;
        this.fft         = fft;
        this.view        = view;
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
     *  {@link #retranslate} and {@link #getContent}. */
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
        // the wizard AND restarting the app.
        Preferences prefs = Preferences.instance();

        targetLabel = new Label(c, SWT.NONE);
        targetLabel.setText(I18n.t("predistortion.targetThd"));
        // PERCENT policy, like the other % fields.  8 decimals so a sub-ppm
        // target distortion can be entered; the floor of 0.1e-6 % keeps the
        // target above zero.
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
        startStopBtn.setEnabled(false);
        startStopBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        saveBtn = new Button(bar, SWT.PUSH);
        saveBtn.setText(I18n.t("predistortion.button.save"));
        saveBtn.setEnabled(false);
        saveBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        cancelBtn = new Button(bar, SWT.PUSH);
        cancelBtn.setText(I18n.t("common.close"));
        cancelBtn.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        cancelBtn.addListener(SWT.Selection, e -> dialog.close());
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
        startStopBtn.setText(I18n.t("predistortion.button.start"));
        saveBtn.setText(I18n.t("predistortion.button.save"));
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
    // Live refresh timer (polls the displayed result - the autotune-dialog
    // render style)
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
        // No run to time yet.
        timeLbl .setText(metric("time", "-"));
        // What is inside the average - the SAME number the FFT pane shows: the
        // averages performed since the last reset, NOT the per-call frameCount
        // (only the 2-3 frames of one analyze() segment).
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

    // -------------------------------------------------------------------------
    // Event history
    // -------------------------------------------------------------------------

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
}
