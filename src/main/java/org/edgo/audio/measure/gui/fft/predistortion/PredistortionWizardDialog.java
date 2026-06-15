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

package org.edgo.audio.measure.gui.fft.predistortion;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import org.edgo.audio.measure.common.FileVersions;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.fft.FftController;
import org.edgo.audio.measure.gui.fft.FftView;
import org.edgo.audio.measure.gui.fft.predistortion.PredistortionEngine.Phase;
import org.edgo.audio.measure.gui.generator.GeneratorController;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.widgets.NumericStepField;
import org.edgo.audio.measure.gui.widgets.UnitFamily;
import org.edgo.audio.measure.preferences.Preferences;

import lombok.extern.log4j.Log4j2;

/**
 * Modal wizard that runs the closed-loop DAC predistortion measurement and
 * saves / applies the result.  Drives the already-running generator + FFT
 * through a {@link PredistortionEngine}.
 *
 * <p>Rendering follows the (historic) PID-autotune dialog: an intro that
 * explains the procedure, an FFT-settings group, a live <b>convergence
 * chart</b> of the distortion falling round by round, and a progress group of
 * live readouts — all refreshed by a ~{@value #TIMER_MS} ms timer that polls
 * the engine's live {@link Phase} and the displayed FFT result, so the user
 * always sees what the loop is doing.
 *
 * <p>Lifecycle: <b>Start</b> begins the loop (becoming <b>Stop</b>); it
 * auto-stops on the target distortion or when it stalls.  <b>Save</b> writes
 * the {@code .dpd} predistortion file; it then becomes <b>Apply</b>, which
 * loads that file onto the generator and switches it to the compensated form.
 * <b>Cancel</b> reverts the generator to the plain tone.
 */
@Log4j2
public final class PredistortionWizardDialog implements PredistortionEngine.Listener {

    /** Minimum averaging duration per round (s) — short runs can't build a
     *  deep-enough coherent average to read the harmonics cleanly. */
    private static final double MIN_DURATION_SEC = 30.0;
    private static final double MAX_DURATION_SEC = 1_000_000.0;
    /** Live-refresh cadence (ms) for the polling render — fast enough that the
     *  per-round progress bar and averages climb smoothly. */
    private static final int    TIMER_MS = 100;

    private final Shell               parentShell;
    private final GeneratorController gen;
    private final FftController       fft;
    private final FftView             view;

    private Shell            dialog;
    private NumericStepField durationField;
    private NumericStepField targetField;
    private Label            statusLabel;
    private Label            fftSizeLbl, fftRateLbl, fftWindowLbl;
    private Label            roundLbl, distLbl, bestLbl, thdNLbl, snrLbl, sinadLbl, fundLbl, floorLbl, avgLbl;
    private Canvas           chart;
    private Button           startStopBtn;
    private Button           saveBtn;
    private Button           cancelBtn;

    private PredistortionEngine engine;
    private boolean running;
    private boolean timerArmed;
    private String  savedPath;
    private boolean applied;
    /** Terminal status key set by {@link #onFinished} and shown while idle. */
    private String  terminalStatusKey = "predistortion.status.idle";

    /** Per-round distortion history (%), grown as rounds complete — the
     *  convergence chart's trace.  UI-thread confined. */
    private double[] distHistory = new double[0];
    /** The most recent completed round's distortion (%); {@code NaN} until the
     *  first round finishes. */
    private double   latestDistPct = Double.NaN;

    public PredistortionWizardDialog(Shell parent, GeneratorController gen,
                                     FftController fft, FftView view) {
        this.parentShell = parent;
        this.gen         = gen;
        this.fft         = fft;
        this.view        = view;
    }

    /** Opens the wizard and blocks until it is dismissed. */
    public void open() {
        dialog = new Shell(parentShell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
        dialog.setText(I18n.t("predistortion.wizard.title"));
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 12; gl.marginHeight = 12; gl.verticalSpacing = 8;
        dialog.setLayout(gl);

        Label intro = new Label(dialog, SWT.WRAP);
        intro.setText(I18n.t("predistortion.intro"));
        GridData introGd = new GridData(SWT.FILL, SWT.TOP, true, false);
        introGd.widthHint = 470;
        intro.setLayoutData(introGd);

        statusLabel = new Label(dialog, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        buildFftGroup();
        buildChart();
        buildProgressGroup();
        buildSettings();
        buildButtonBar();

        dialog.addListener(SWT.Close, e -> e.doit = handleCancel());

        refresh();
        dialog.pack();
        Dialogs.centerOnParent(dialog);
        dialog.open();
        armTimer();
        while (!dialog.isDisposed()) {
            if (!dialog.getDisplay().readAndDispatch()) dialog.getDisplay().sleep();
        }
    }

    // -------------------------------------------------------------------------
    // Build
    // -------------------------------------------------------------------------

    private void buildFftGroup() {
        Group g = new Group(dialog, SWT.NONE);
        g.setText(I18n.t("predistortion.group.fft"));
        g.setLayout(new GridLayout(1, false));
        g.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        fftSizeLbl   = infoLabel(g);
        fftRateLbl   = infoLabel(g);
        fftWindowLbl = infoLabel(g);
    }

    private void buildChart() {
        chart = new Canvas(dialog, SWT.DOUBLE_BUFFERED | SWT.BORDER);
        GridData gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.heightHint = 150;
        gd.widthHint  = 470;
        chart.setLayoutData(gd);
        chart.addPaintListener(e -> paintChart(e.gc, chart.getClientArea()));
    }

    private void buildProgressGroup() {
        Group g = new Group(dialog, SWT.NONE);
        g.setText(I18n.t("predistortion.group.progress"));
        g.setLayout(new GridLayout(2, true));
        g.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        roundLbl = infoLabel(g);
        avgLbl   = infoLabel(g);
        distLbl  = infoLabel(g);
        bestLbl  = infoLabel(g);
        thdNLbl  = infoLabel(g);
        snrLbl   = infoLabel(g);
        sinadLbl = infoLabel(g);
        fundLbl  = infoLabel(g);
        floorLbl = infoLabel(g);
        infoLabel(g);   // spacer to balance the 2-column grid
    }

    private Label infoLabel(Composite parent) {
        Label l = new Label(parent, SWT.NONE);
        l.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        return l;
    }

    private void buildSettings() {
        Composite c = new Composite(dialog, SWT.NONE);
        c.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout gl = new GridLayout(4, false);
        gl.marginWidth = 0; gl.marginHeight = 0; gl.horizontalSpacing = 8;
        c.setLayout(gl);

        // Seed both fields from Preferences so the user's last-used values
        // survive reopening the wizard AND restarting the app — never reset
        // by a completed run.
        Preferences prefs = Preferences.instance();

        Label dl = new Label(c, SWT.NONE);
        dl.setText(I18n.t("predistortion.duration"));
        // FIXED policy: wheel ±30 s, arrows ±1 s, whole seconds.
        durationField = new NumericStepField(c, UnitFamily.TIME,
                MIN_DURATION_SEC, MAX_DURATION_SEC, 30.0, 1.0, 0, 90);
        durationField.setValue(prefs.getPredistortionDurationSec());
        durationField.setToolTipText(I18n.t("predistortion.duration.tooltip"));
        // Persist on EDIT (not at Start/Save) — the bound pref auto-saves, so
        // the value is remembered the moment the user changes it, whether or
        // not they ever run a tune.
        durationField.addSelectionListener(e ->
                Preferences.instance().setPredistortionDurationSec(durationField.getValue()));

        Label tl = new Label(c, SWT.NONE);
        tl.setText(I18n.t("predistortion.targetThd"));
        // PERCENT policy, like the other % fields; 0 = no target.  8 decimals
        // so a sub-ppm target distortion can be entered.
        targetField = new NumericStepField(c, UnitFamily.PERCENT, 0.0, 100.0, 8, 90);
        targetField.setValue(prefs.getPredistortionTargetPct());
        targetField.setToolTipText(I18n.t("predistortion.targetThd.tooltip"));
        targetField.addSelectionListener(e ->
                Preferences.instance().setPredistortionTargetPct(targetField.getValue()));
    }

    private void buildButtonBar() {
        Composite bar = new Composite(dialog, SWT.NONE);
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
    // Live refresh timer (polls the engine — the autotune-dialog render style)
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

        statusLabel.setText(statusText());

        roundLbl.setText(metric("round",    running ? Integer.toString(engine.getCurrentRound() + 1) : "—"));
        // The cross-tick averaging depth — the SAME counter the FFT view shows
        // (climbs over a round, reset each round), NOT the per-call frameCount
        // (only the 2–3 frames of one analyze() segment).
        avgLbl  .setText(metric("averages", Integer.toString(fft.completedAnalyses())));
        distLbl .setText(metric("thd",      fmtPct(latestDistPct)));
        bestLbl .setText(metric("best_thd", bestText()));
        // Match the FFT pane: every dB readout carries the dBV suffix.  Absolute
        // levels (fundamental, noise floor) get the dBFS→dBV offset; the ratio
        // figures (THD+N / SNR / SINAD) are relabelled only, never offset.
        thdNLbl .setText(metric("thd_n",    live != null ? fmtDbv(-live.sinadDb) : "—"));
        snrLbl  .setText(metric("snr",      live != null ? fmtDbv(live.snrDb) : "—"));
        sinadLbl.setText(metric("sinad",    live != null ? fmtDbv(live.sinadDb) : "—"));
        fundLbl .setText(metric("fund",     live != null ? fmtDbv(live.fundamentalDbFs + prefs.getDbvOffsetDb()) : "—"));
        floorLbl.setText(metric("floor",    fmtDbv(noiseFloorDbV(live))));

        chart.redraw();
    }

    /** The live "what's going on" headline, driven by the engine's phase. */
    private String statusText() {
        if (engine == null || engine.getPhase() == Phase.IDLE) {
            return I18n.t(terminalStatusKey);
        }
        int round = engine.getCurrentRound() + 1;
        switch (engine.getPhase()) {
            case ALIGNING:   return I18n.t("predistortion.phase.aligning");
            case COLLECTING: return I18n.t("predistortion.phase.collecting", round,
                                            fmt0(engine.getCollectRemainingSec()));
            case APPLYING:   return I18n.t("predistortion.phase.applying", round);
            case SETTLING:   return I18n.t("predistortion.phase.settling");
            case FINISHED:   return I18n.t(terminalStatusKey);
            default:         return I18n.t(terminalStatusKey);
        }
    }

    private String bestText() {
        double b = engine != null ? engine.getBestThdPct() : Double.NaN;
        return (engine == null || b == Double.MAX_VALUE) ? "—" : fmtPct(b);
    }

    private String metric(String key, String value) {
        return I18n.t("predistortion.metric." + key) + ":  " + value;
    }

    // -------------------------------------------------------------------------
    // Convergence chart
    // -------------------------------------------------------------------------

    private void paintChart(GC gc, Rectangle a) {
        Display d = chart.getDisplay();
        gc.setBackground(d.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
        gc.fillRectangle(a);

        final int L = 50, R = 10, T = 8, B = 18;
        int px = a.x + L, py = a.y + T, pw = a.width - L - R, ph = a.height - T - B;
        if (pw < 20 || ph < 20) return;
        gc.setForeground(d.getSystemColor(SWT.COLOR_GRAY));
        gc.drawRectangle(px, py, pw, ph);

        double[] h = distHistory;
        int n = h.length;
        if (n < 1) {
            gc.setForeground(d.getSystemColor(SWT.COLOR_DARK_GRAY));
            Point ext = gc.textExtent("—");
            gc.drawText("—", px + (pw - ext.x) / 2, py + (ph - ext.y) / 2, true);
            return;
        }

        // Log-% Y range from the data, widened to whole decades, with the
        // target line (if any) kept in view so the user sees the goal.
        double target = targetField != null ? targetField.getValue() : 0.0;
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (double v : h) if (v > 0 && Double.isFinite(v)) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
        if (!(hi > 0)) return;
        if (!(lo > 0) || !Double.isFinite(lo)) lo = hi / 10.0;
        if (target > 0) lo = Math.min(lo, target);
        double yhi = Math.ceil(Math.log10(hi) + 1e-6);
        double ylo = Math.floor(Math.log10(lo) - 1e-6);
        if (yhi - ylo < 1) ylo = yhi - 1;

        // Decade gridlines + labels.
        gc.setForeground(d.getSystemColor(SWT.COLOR_GRAY));
        for (double k = ylo; k <= yhi + 1e-9; k += 1.0) {
            int y = py + (int) Math.round((yhi - k) / (yhi - ylo) * ph);
            gc.drawLine(px, y, px + pw, y);
            String lab = String.format(Locale.US, "%.0e", Math.pow(10.0, k));
            Point ext = gc.textExtent(lab);
            gc.drawText(lab, px - ext.x - 4, y - ext.y / 2, true);
        }

        // Target line — red dashed.
        if (target > 0) {
            int y = py + (int) Math.round((yhi - Math.log10(target)) / (yhi - ylo) * ph);
            if (y >= py && y <= py + ph) {
                gc.setForeground(d.getSystemColor(SWT.COLOR_RED));
                gc.setLineStyle(SWT.LINE_DASH);
                gc.drawLine(px, y, px + pw, y);
                gc.setLineStyle(SWT.LINE_SOLID);
            }
        }

        // Convergence trace + per-round markers.
        gc.setForeground(d.getSystemColor(SWT.COLOR_DARK_BLUE));
        gc.setBackground(d.getSystemColor(SWT.COLOR_DARK_BLUE));
        int prevX = 0, prevY = 0;
        for (int i = 0; i < n; i++) {
            double v = h[i] > 0 ? h[i] : lo;
            int x = px + (n == 1 ? pw / 2 : (int) Math.round(i / (double) (n - 1) * pw));
            int y = py + (int) Math.round((yhi - Math.log10(v)) / (yhi - ylo) * ph);
            if (i > 0) gc.drawLine(prevX, prevY, x, y);
            gc.fillOval(x - 2, y - 2, 4, 4);
            prevX = x; prevY = y;
        }
    }

    // -------------------------------------------------------------------------
    // Start / Stop
    // -------------------------------------------------------------------------

    private void onStartStop() {
        if (running) {
            engine.stop();
            startStopBtn.setEnabled(false);   // re-enabled by onFinished
            return;
        }
        if (!gen.isRunning()) {
            Dialogs.error(dialog, I18n.t("predistortion.wizard.title"),
                    I18n.t("predistortion.error.noGenerator"));
            return;
        }
        GenSignalForm form = Preferences.instance().getGenSignalForm();
        boolean supported = form == GenSignalForm.SINE
                || form == GenSignalForm.SINE_COMPENSATED
                || form.isDualTone();
        if (!supported) {
            // Single tone → harmonic compensation; dual tone → intermod
            // compensation.  Every other waveform (noise / sweep / triangle /
            // rectangle) has no meaningful predistortion target.
            Dialogs.error(dialog, I18n.t("predistortion.wizard.title"),
                    I18n.t("predistortion.error.notSupported"));
            return;
        }
        // Settings persist on edit (see buildSettings), independently of this
        // run — Start just reads the current field values.
        double durationSec  = Math.max(MIN_DURATION_SEC, durationField.getValue());
        double targetThdPct = targetField.getValue();

        running = true;
        savedPath = null;
        applied = false;
        latestDistPct = Double.NaN;
        distHistory = new double[0];
        saveBtn.setEnabled(false);
        saveBtn.setText(I18n.t("predistortion.button.save"));
        durationField.setEnabled(false);
        targetField.setEnabled(false);
        startStopBtn.setText(I18n.t("predistortion.button.stop"));

        engine = new PredistortionEngine(dialog.getDisplay(), gen, fft, view, this);
        engine.start(durationSec, targetThdPct);
    }

    // -------------------------------------------------------------------------
    // Engine callbacks (UI thread) — stash data; the timer renders it.
    // -------------------------------------------------------------------------

    @Override
    public void onAligning() {
        // Rendered by the polling timer via the engine phase.
    }

    @Override
    public void onRound(int round, double thdPct, FftResult r) {
        latestDistPct = thdPct;
        double[] grown = new double[distHistory.length + 1];
        System.arraycopy(distHistory, 0, grown, 0, distHistory.length);
        grown[distHistory.length] = thdPct;
        distHistory = grown;
    }

    @Override
    public void onFinished(PredistortionEngine.StopReason reason, double bestThdPct, boolean hasResult) {
        if (dialog.isDisposed()) return;
        running = false;
        startStopBtn.setEnabled(true);
        startStopBtn.setText(I18n.t("predistortion.button.start"));
        durationField.setEnabled(true);
        targetField.setEnabled(true);
        if (hasResult) saveBtn.setEnabled(true);
        terminalStatusKey = switch (reason) {
            case TARGET_REACHED -> "predistortion.status.targetReached";
            case STALLED        -> "predistortion.status.stalled";
            case ERROR          -> "predistortion.status.error";
            default             -> "predistortion.status.stopped";
        };
        if (reason == PredistortionEngine.StopReason.STALLED) {
            Dialogs.info(dialog, I18n.t("predistortion.wizard.title"),
                    I18n.t("predistortion.warn.stalled"));
        }
        refresh();
    }

    // -------------------------------------------------------------------------
    // Save / Apply
    // -------------------------------------------------------------------------

    private void onSaveOrApply() {
        if (savedPath == null) doSave();
        else                   doApply();
    }

    private void doSave() {
        FftResult r = engine.getBestResult();
        boolean dual = engine.isDualTone();
        if (r == null) return;
        if (dual ? engine.getBestIntermod() == null : engine.getBestApplied() == null) return;

        FileDialog fd = new FileDialog(dialog, SWT.SAVE);
        fd.setText(I18n.t("predistortion.save.dialog"));
        fd.setFilterExtensions(new String[]{ "*.dpd" });
        fd.setFilterNames(new String[]{ I18n.t("predistortion.save.filter") });
        fd.setOverwrite(true);
        Preferences prefs = Preferences.instance();
        String folder = prefs.getGenCorrectionsFolder();
        if (folder != null) fd.setFilterPath(folder);
        fd.setFileName("predistortion_" + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".dpd");
        String picked = fd.open();
        if (picked == null) return;
        try {
            int bitDepth = prefs.current().getOutputBitDepth();
            if (dual) {
                engine.getBestIntermod().writeCsv(Path.of(picked), buildHeader(r),
                        r.fundamentalHzRefined, r.fundamental2HzRefined, r.fundamentalDbFs,
                        r.sampleRate, bitDepth, prefs.getGenAmplitudeVrms());
            } else {
                engine.getBestApplied().writeCsv(Path.of(picked), buildHeader(r),
                        r.fundamentalHzRefined, r.fundamentalDbFs,
                        r.sampleRate, bitDepth, prefs.getGenAmplitudeVrms());
            }
            savedPath = picked;
            prefs.setGenCorrectionsFolder(new File(picked).getParent());
            prefs.save();
            saveBtn.setText(I18n.t("predistortion.button.apply"));
            terminalStatusKey = "predistortion.status.saved";
            statusLabel.setText(I18n.t(terminalStatusKey));
            log.info("Predistortion saved to {}", picked);
        } catch (Exception ex) {
            log.warn("Predistortion save failed", ex);
            Dialogs.error(dialog, I18n.t("predistortion.save.dialog"),
                    ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
        }
    }

    private void doApply() {
        if (savedPath == null) return;
        Preferences prefs = Preferences.instance();
        // Apply = LOAD the just-saved .dpd onto the running generator (no
        // restart).  The file is the single source of truth, so the live tone
        // and a later relaunch agree.  Persist the form + corrections path so
        // it survives a restart AND so the generator pane's Corrections field
        // shows the loaded file.
        prefs.setGenSignalForm(engine.isDualTone()
                ? GenSignalForm.DUAL_TONE_COMPENSATED : GenSignalForm.SINE_COMPENSATED);
        prefs.setGenCorrectionsFile(savedPath);
        gen.loadCorrectionsFromFile(savedPath);
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
        h.add("# gen_amplitude_vrms=" + fmt(prefs.getGenAmplitudeVrms()));
        h.add("# gen_dither_bits="    + prefs.getGenDitherBits());
        h.add("# fft_size="           + r.fftSize);
        h.add("# fft_window="         + prefs.getFftWindow());
        h.add("# fft_overlap="        + prefs.getFftOverlap().label);
        h.add("# fft_max_harmonic="   + prefs.getFftCalcMaxHarmonic());
        h.add("# fft_averages="       + r.frameCount);
        h.add("# thd_pct="            + fmt(r.thdPct));
        h.add("# thd_plus_n_db="      + fmt(-r.sinadDb));
        h.add("# snr_db="             + fmt(r.snrDb));
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
            running = false;
        }
        // Revert the running generator to the plain tone unless the user
        // committed via Apply.
        if (!applied) gen.clearCompensation();
        return true;
    }

    // -------------------------------------------------------------------------
    // Formatting
    // -------------------------------------------------------------------------

    private String fmtPct(double v) {
        return Double.isFinite(v) ? String.format(Locale.US, "%.8f %%", v) : "—";
    }

    private String fmtDbv(double v) {
        return Double.isFinite(v) ? String.format(Locale.US, "%.2f dBV", v) : "—";
    }

    /**
     * Noise floor as the spectrum actually SHOWS it (dBV): the high-percentile
     * "top of the grass" with the fundamental, the harmonics and their skirts
     * removed — the practical limit below which a harmonic can't be measured —
     * rather than the RMS average of a single noise bin (which sits well below
     * the visible peaks).  {@code NaN} when no spectrum is available.
     */
    private double noiseFloorDbV(FftResult r) {
        if (r == null || r.amplitudeDbFs == null || !(r.freqResolution > 0)) return Double.NaN;
        double[] mag = r.amplitudeDbFs;
        int n = mag.length;
        boolean[] excl = new boolean[n];
        int binHz = (int) Math.max(1, Math.ceil(r.fundamentalDynExclusionHz / r.freqResolution));
        markExcl(excl, r.fundamentalBin, binHz);                 // fundamental + its skirt
        int harmHalf = Math.max(3, binHz / 8);
        if (r.harmonicBins != null) {
            for (int i = 0; i < r.harmonicCount && i < r.harmonicBins.length; i++) {
                markExcl(excl, r.harmonicBins[i], harmHalf);     // each harmonic + its skirt
            }
        }
        double[] noise = new double[n];
        int cnt = 0;
        for (int b = 1; b < n; b++) {
            if (!excl[b] && Double.isFinite(mag[b])) noise[cnt++] = mag[b];
        }
        if (cnt == 0) return Double.NaN;
        Arrays.sort(noise, 0, cnt);
        int idx = (int) Math.min(cnt - 1, Math.round(0.999 * (cnt - 1)));   // grass peaks
        return noise[idx] + Preferences.instance().getDbvOffsetDb();
    }

    /** Marks {@code center ± half} bins as signal (excluded from the noise set). */
    private void markExcl(boolean[] excl, int center, int half) {
        if (center <= 0) return;
        int lo = Math.max(0, center - half);
        int hi = Math.min(excl.length - 1, center + half);
        for (int b = lo; b <= hi; b++) excl[b] = true;
    }

    private String fmtHz(double v) {
        return Double.isFinite(v) ? String.format(Locale.US, "%.4f", v) : "—";
    }

    private String fmt0(double v) {
        return String.format(Locale.US, "%.0f", Math.max(0.0, v));
    }

    private String fmt(double v) {
        return String.format(Locale.US, "%.6f", v);
    }
}
