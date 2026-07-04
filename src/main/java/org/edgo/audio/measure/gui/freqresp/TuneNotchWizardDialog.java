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

package org.edgo.audio.measure.gui.freqresp;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import org.edgo.audio.measure.cli.util.StereoSamples;
import org.edgo.audio.measure.common.FreqRespCorrectionStore;
import org.edgo.audio.measure.dsp.FreqRespCalHelper;
import org.edgo.audio.measure.dsp.FreqRespCalibration;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.common.ShellIcons;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.gui.sound.SharedCapture;
import org.edgo.audio.measure.gui.widgets.NumericStepField;
import org.edgo.audio.measure.gui.widgets.UnitFamily;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.DeviceRef;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Modal wizard that continuously re-runs a fast Farina sweep and paints the
 * live frequency response into an embedded {@link FreqRespView}, so the user
 * can tune a notch filter and watch the dip move in real time.
 *
 * <p>The dialog opens, immediately publishes {@link
 * Events#FREQRESP_MEASUREMENT_STARTED} so the scope / FFT / generator / main
 * FreqResp workers release the shared audio device, then spins a daemon thread
 * that re-sweeps until the dialog closes.  Each finished sweep is fed
 * <em>directly</em> into the embedded view (NOT via {@link
 * Events#FREQRESP_RESULT_AVAILABLE}, which would also leak the notch sweeps
 * into the main FreqResp pane).  The first sweep auto-fits the magnitude axis
 * to the measured band.
 *
 * <p>The embedded view runs ISOLATED: the dialog hands it a detached
 * {@link Preferences#copyForDialog()} copy, so every range / channel-visibility
 * edit (axis anchoring, auto-fit, pan / zoom) stays in the copy and the shared
 * main-pane view is never touched — no snapshot/restore needed.  Only the
 * tune-notch parameters (start / stop / amplitude / target) are written back to
 * the real preferences on close.  The worker thread never touches widgets:
 * it reads the field values through a volatile mirror that the UI-thread
 * listeners keep current, so the close-time {@code join()} can't deadlock
 * against a {@code syncExec}.
 */
@Log4j2
public final class TuneNotchWizardDialog {

    // --- Chart geometry / sweep timing ---------------------------------------
    /** Fixed embedded-chart size, per spec: no controls, 600×400. */
    private static final int CHART_WIDTH_PX  = 600;
    private static final int CHART_HEIGHT_PX = 400;
    /** Sweep / lead-in durations.  The continuous stream uses zero lead-in
     *  (the loop never re-emits silence); SWEEP_LEAD_IN_SEC is kept only as the
     *  reported parameter in {@link FreqRespSweepParams}. */
    private static final double SWEEP_DURATION_SEC = 0.26;
    private static final double SWEEP_LEAD_IN_SEC  = 0.05;
    /** Per-side Hann fade length (seconds) applied to BOTH the played loop seam
     *  and the deconvolution reference.  25 ms was found on the scope to best
     *  suppress the loop-seam spikes; it overrides the shared 5% default. */
    private static final double FADE_SEC = 0.025;
    /** Safety multiplier on the sweep-band margin (see {@link #sweepBand}).  The
     *  Hann fades drive the sweep energy |X|→0 at the swept ends, so the
     *  displayed band must sit well inside the fully-excited middle; >1 keeps the
     *  displayed edges clear of the fade and the log sweep's 1/f roll-off (which
     *  otherwise flatten the trace at the edges). */
    private static final double SWEEP_MARGIN_SAFETY = 2.5;
    /** Wait for the overlapping deconvolution executor to drain on close. */
    private static final long   DECONV_SHUTDOWN_TIMEOUT_S = 3L;

    // --- Continuous-stream loop timing ---------------------------------------
    /** Settle poll while the ring fills to one period — re-check this often. */
    private static final long SETTLE_POLL_MS  = 20L;
    /** Status / percentage UI refresh cadence (don't spam the UI thread). */
    private static final long STATUS_TICK_MS  = 30L;
    /** Brief wait when latestPeriod() is momentarily null after settle. */
    private static final long GRAB_RETRY_MS   = 10L;
    private static final double PERCENT_FULL   = 100.0;

    // --- Field defaults / ranges ---------------------------------------------
    private static final double FREQ_MIN_HZ       = 1.0;
    private static final double AMP_MIN_VRMS      = 1e-4;
    private static final int    FREQ_MAX_DECIMALS = 9;
    private static final int    AMP_MAX_DECIMALS  = 5;
    private static final int    FIELD_WIDTH_HINT  = 110;

    // --- Initial / pre-fit magnitude window (dBV) ----------------------------
    private static final double INITIAL_MAG_TOP_DB = 20.0;
    private static final double INITIAL_MAG_BOT_DB = -140.0;
    /** Padding above max / below min applied to the first sweep's auto-fit. */
    private static final double AUTO_FIT_PAD_DB = 2.0;

    // --- Notch readout overlay -----------------------------------------------
    /** Plot left edge in the embedded view (its {@code MARGIN_LEFT}). */
    private static final int NOTCH_TEXT_X_PX = 73;
    private static final int NOTCH_TEXT_Y_PX = 6;
    /** One-pixel white outline drawn around the black readout text. */
    private static final int NOTCH_OUTLINE_PX = 1;

    // --- Vertical-range averaging + target-frequency marker ------------------
    /** Vertical range is set from the rolling average of this many sweeps'
     *  min/max dB, so the axis doesn't jump frame-to-frame. */
    private static final int MAG_AVG_FRAMES = 20;
    // Plot margins — mirror FreqRespView's MARGIN_* so the target marker lands
    // on the same log frequency axis the trace is drawn on.
    private static final int VIEW_MARGIN_LEFT        = 68;
    private static final int VIEW_MARGIN_TOP         = 0;
    private static final int VIEW_MARGIN_BOTTOM      = 28;
    private static final int VIEW_MARGIN_RIGHT_PHASE = 52;
    /** Target-frequency marker: a 3-px dashed vertical line coloured from green
     *  at {@link #TARGET_GREEN_DB} (deep notch on target) to red at
     *  {@link #TARGET_RED_DB} (shallow) by the measured attenuation there. */
    private static final int    TARGET_LINE_WIDTH_PX = 3;
    private static final double TARGET_GREEN_DB      = -90.0;
    private static final double TARGET_RED_DB        = -50.0;

    /** Generous wait for the other panes' workers to release the device. */
    private static final long DEVICE_RELEASE_TIMEOUT_MS = 2000;
    private static final long DEVICE_POLL_INTERVAL_MS   = 20;
    /** Bound on the join when the dialog closes. */
    private static final long THREAD_JOIN_TIMEOUT_MS    = 500;

    private final Shell                   parentShell;
    /** Empty, silent correction store for the embedded view — the notch
     *  session never loads / saves calibrations. */
    private final FreqRespCorrectionStore correctionStore =
            new FreqRespCorrectionStore("TuneNotch", null);

    private Shell            dialog;
    private FreqRespView     view;
    private NumericStepField startField;
    private NumericStepField stopField;
    private NumericStepField ampField;
    private NumericStepField targetField;
    private Label            statusLabel;

    /** UI-thread only: number of sweeps started, shown in the status line. */
    private int             sweepCount;

    // Notch readout, painted as an overlay on the embedded view.  Written on
    // the UI thread (onSweepResult), read on the UI thread (paint listener);
    // volatile so the field model stays consistent with the rest of the class.
    private volatile double  notchHz;
    private volatile double  notchDb;
    private volatile boolean notchValid;

    /** Latest published result, read by the target-marker paint listener to
     *  colour the marker by the attenuation at the target frequency. */
    private volatile FreqRespResult latestResult;

    // Rolling 20-sweep min/max dB ring for the AVERAGED vertical range so it
    // doesn't jump frame-to-frame (UI-thread only — applyAutoMagWindow runs on
    // the UI thread).
    private final double[] magRingMin = new double[MAG_AVG_FRAMES];
    private final double[] magRingMax = new double[MAG_AVG_FRAMES];
    private int magRingPos;
    private int magRingCount;

    // Field values mirrored from the UI-thread listeners so the sweep thread
    // can read them without a syncExec (which would deadlock the close join).
    private volatile double  curStartHz;
    private volatile double  curStopHz;
    private volatile double  curAmpVrms;
    private volatile double  curTargetHz;

    /** Drives the continuous sweep loop; cleared on close. */
    private volatile boolean running;
    private Thread           sweepThread;

    /** Self-contained CONTINUOUS play+capture engine: opens the device once,
     *  streams a looping sweep into a ring buffer, closed once on dialog close. */
    private volatile NotchSweepEngine engine;
    /** Single-thread deconvolution worker so deconv(n) overlaps the capture. */
    private ExecutorService  deconvExecutor;

    /** Detached preferences copy driving the embedded (isolated) view for this
     *  notch session — created in {@link #open()}.  Edits here never reach the
     *  global preferences except the tune-notch fields written by
     *  {@link #saveDialogPrefs()} on close. */
    private Preferences prefs;
    /** Single composite holding ALL dialog widgets, so the help-screenshot
     *  automation can render the window via Control.print (a top-level Shell
     *  prints blank on Windows; a Composite prints its children). */
    @Getter
    private Composite content;

    public TuneNotchWizardDialog(Shell parent) {
        this.parentShell = parent;
    }

    /** Opens the wizard and blocks until the user closes it: builds + shows the
     *  dialog, starts the live sweep, then runs the modal event loop. */
    public void open() {
        buildAndShow();
        startSweepLoop();
        Display d = dialog.getDisplay();
        while (!dialog.isDisposed()) {
            if (!d.readAndDispatch()) d.sleep();
        }
    }

    /** Builds and shows the dialog but does NOT start the live sweep or enter
     *  the modal loop; returns the shell.  {@link #open()} is the normal entry
     *  (build + sweep + block).  The help-screenshot automation calls this
     *  directly to capture the localized dialog without opening an audio device
     *  (no sweep → deterministic, hardware-free, and nothing to tear down). */
    public Shell buildAndShow() {
        // Detached copy: the embedded view + this dialog edit it freely (axis,
        // auto-fit, channel select) with zero effect on the main pane; only the
        // tune-notch fields are copied back on close (see saveDialogPrefs).
        prefs = Preferences.instance().copyForDialog();
        // The tune-notch view NEVER shows the phase trace, independent of how the main
        // FreqResp is configured — force it off on the detached copy (the main pane's
        // freqRespPhaseVisible preference is untouched).
        prefs.setFreqRespPhaseVisible(false);
        dialog = new Shell(parentShell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        ShellIcons.apply(dialog);
        dialog.setText(I18n.t("tuneNotch.title"));
        GridLayout shellLayout = new GridLayout(1, false);
        shellLayout.marginWidth = 0; shellLayout.marginHeight = 0;
        dialog.setLayout(shellLayout);

        // All widgets live in ONE content composite so the help-screenshot
        // automation can render the window itself via Control.print — a
        // top-level Shell prints blank on Windows, a Composite prints its
        // children (see getContent()).
        content = new Composite(dialog, SWT.NONE);
        content.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        GridLayout outer = new GridLayout(1, false);
        outer.marginWidth = 12; outer.marginHeight = 12; outer.verticalSpacing = 10;
        content.setLayout(outer);

        buildFieldsRow();
        applySessionViewPrefs();
        buildChart();
        buildStatusRow();

        dialog.addListener(SWT.Close, e -> stopSweepLoop());

        dialog.pack();
        Dialogs.centerOnParent(dialog);
        dialog.open();
        return dialog;
    }

    /** Starts the live sweep on an already-built dialog (see {@link #buildAndShow()}).
     *  The help-screenshot automation calls this after {@code buildAndShow} so the
     *  chart collects a REAL trace before the capture. */
    public void startSweep() {
        startSweepLoop();
    }

    // -------------------------------------------------------------------------
    // UI construction
    // -------------------------------------------------------------------------

    private void buildFieldsRow() {
        Composite row = new Composite(content, SWT.NONE);
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        // Two rows of label+field pairs: row 1 = start / stop, row 2 = amplitude
        // / target frequency.
        GridLayout gl = new GridLayout(4, false);
        gl.marginWidth = 0; gl.marginHeight = 0;
        gl.horizontalSpacing = 8; gl.verticalSpacing = 6;
        row.setLayout(gl);

        double nyquist = prefs.current().getInputSampleRate() / 2.0;

        addLabel(row, I18n.t("tuneNotch.startHz"));
        startField = new NumericStepField(row, UnitFamily.FREQUENCY,
                FREQ_MIN_HZ, nyquist, FREQ_MAX_DECIMALS, FIELD_WIDTH_HINT);
        startField.setValue(prefs.getTuneNotchStartHz());

        addLabel(row, I18n.t("tuneNotch.stopHz"));
        stopField = new NumericStepField(row, UnitFamily.FREQUENCY,
                FREQ_MIN_HZ, nyquist, FREQ_MAX_DECIMALS, FIELD_WIDTH_HINT);
        stopField.setValue(prefs.getTuneNotchStopHz());

        addLabel(row, I18n.t("tuneNotch.amplitude"));
        ampField = new NumericStepField(row, UnitFamily.AMPLITUDE,
                AMP_MIN_VRMS, prefs.getDacFsVoltageAmpl(), AMP_MAX_DECIMALS, FIELD_WIDTH_HINT);
        ampField.setValue(prefs.getTuneNotchAmplitudeVrms());

        addLabel(row, I18n.t("tuneNotch.targetHz"));
        targetField = new NumericStepField(row, UnitFamily.FREQUENCY,
                FREQ_MIN_HZ, nyquist, FREQ_MAX_DECIMALS, FIELD_WIDTH_HINT);
        targetField.setValue(prefs.getTuneNotchTargetHz());

        // Seed the worker-visible mirror, then keep it current from the UI
        // thread on every edit.  Start/stop also re-anchor the chart axis.
        curStartHz  = startField.getValue();
        curStopHz   = stopField.getValue();
        curAmpVrms  = ampField.getValue();
        curTargetHz = targetField.getValue();

        startField.addSelectionListener(e -> {
            curStartHz = startField.getValue();
            prefs.setTuneNotchStartHz(curStartHz);
            applyFreqAxis();
            retuneEngineBand();
        });
        stopField.addSelectionListener(e -> {
            curStopHz = stopField.getValue();
            prefs.setTuneNotchStopHz(curStopHz);
            applyFreqAxis();
            retuneEngineBand();
        });
        ampField.addSelectionListener(e -> {
            curAmpVrms = ampField.getValue();
            prefs.setTuneNotchAmplitudeVrms(curAmpVrms);
        });
        targetField.addSelectionListener(e -> {
            curTargetHz = targetField.getValue();
            prefs.setTuneNotchTargetHz(curTargetHz);
            if (view != null && !view.isDisposed()) view.redraw();
        });
    }

    private void buildChart() {
        view = new FreqRespView(content, correctionStore, true, prefs);
        view.setHeaderControlsVisible(false);
        GridData gd = new GridData(SWT.CENTER, SWT.CENTER, true, false);
        gd.widthHint  = CHART_WIDTH_PX;
        gd.heightHint = CHART_HEIGHT_PX;
        view.setLayoutData(gd);
        // Overlay the target-frequency marker, then the deepest-notch readout,
        // on top of the live trace (text drawn last so it stays legible).
        view.addPaintListener(this::onTargetPaint);
        view.addPaintListener(this::onNotchPaint);
    }

    /** Status line and Close button share one bottom row: the status label
     *  grabs the width; the Close button sits at the right end.  Merging them
     *  into one row (vs two) is what shortens the window. */
    private void buildStatusRow() {
        Composite row = new Composite(content, SWT.NONE);
        row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 0; gl.marginHeight = 0;
        row.setLayout(gl);

        statusLabel = new Label(row, SWT.NONE);
        statusLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Button close = new Button(row, SWT.PUSH);
        close.setText(I18n.t("tuneNotch.close"));
        close.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        close.addListener(SWT.Selection, e -> dialog.close());
    }

    private void addLabel(Composite parent, String text) {
        Label l = new Label(parent, SWT.NONE);
        l.setText(text);
        l.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
    }

    // -------------------------------------------------------------------------
    // View-pref management (on the dialog's detached prefs copy — never the
    // shared main-pane preferences)
    // -------------------------------------------------------------------------

    /** Points the dialog's detached view prefs at the notch session: show the R
     *  (measurement / ch1) channel, set the frequency axis to [start, stop],
     *  and a wide initial magnitude window until the first sweep auto-fits it.
     *  Applied to the copy only, so the main pane's view is untouched. */
    private void applySessionViewPrefs() {
        prefs.setFreqRespRightVisible(true);
        prefs.setFreqRespLeftVisible(false);
        prefs.setFreqRespFreqMinHz(curStartHz);
        prefs.setFreqRespFreqMaxHz(curStopHz);
        prefs.setFreqRespMagTopDb(INITIAL_MAG_TOP_DB);
        prefs.setFreqRespMagBotDb(INITIAL_MAG_BOT_DB);
    }

    /** Re-anchors the chart's frequency axis to the current [start, stop]. */
    private void applyFreqAxis() {
        prefs.setFreqRespFreqMinHz(curStartHz);
        prefs.setFreqRespFreqMaxHz(curStopHz);
        if (view != null && !view.isDisposed()) view.redraw();
    }

    /** The DDS sweep band: WIDER than the displayed [start, stop] so the Hann
     *  fades — which zero the sweep energy |X| over {@code FADE_SEC /
     *  SWEEP_DURATION_SEC} of the log range at each swept end — fall OUTSIDE the
     *  displayed band, with {@link #SWEEP_MARGIN_SAFETY} headroom for the log
     *  sweep's 1/f roll-off (so the displayed edges aren't flattened).  The view
     *  shows only [start, stop]; the widened ends are cut.  Returns {f0, f1}. */
    private double[] sweepBand(double startHz, double stopHz) {
        double ff = FADE_SEC / SWEEP_DURATION_SEC;
        double marginFrac = SWEEP_MARGIN_SAFETY * ff / (1.0 - 2.0 * ff);
        double m = Math.log(stopHz / startHz) * marginFrac;
        return new double[] { startHz * Math.exp(-m), stopHz * Math.exp(m) };
    }

    /** Live-retunes the streamed sweep to the current [start, stop] without
     *  restarting the device — no-op until the engine is running.  Runs on the
     *  UI thread (field listener); {@code setBand} is thread-safe. */
    private void retuneEngineBand() {
        NotchSweepEngine e = engine;
        if (e == null) return;
        double[] b = sweepBand(curStartHz, curStopHz);
        e.setBand(b[0], b[1]);
    }

    // -------------------------------------------------------------------------
    // Continuous sweep loop
    // -------------------------------------------------------------------------

    private void startSweepLoop() {
        running = true;
        sweepCount = 0;
        magRingPos = 0;
        magRingCount = 0;
        // Publish FIRST (UI thread) so the scope / FFT / generator / main
        // FreqResp workers run their stop logic synchronously before our
        // worker thread tries to open the audio device.
        MessageBus.instance().publish(Events.FREQRESP_MEASUREMENT_STARTED);
        sweepThread = new Thread(this::sweepLoop, "tune-notch-sweep");
        sweepThread.setDaemon(true);
        sweepThread.start();
    }

    private void sweepLoop() {
        // Wait for the other panes' workers to release the device + DAC before
        // we open it ourselves (mirrors FreqRespAnalyzerWorker: capture idle
        // AND generator idle).
        if (!waitForOtherWorkersStopped(DEVICE_RELEASE_TIMEOUT_MS)) {
            log.warn("TuneNotch: timeout waiting for other workers to release the audio device");
        }
        DeviceRef out = resolveDevice(true,  prefs.current().getOutputDeviceName());
        DeviceRef in  = resolveDevice(false, prefs.current().getInputDeviceName());
        if (out == null || in == null) {
            running = false;
            marshal(() -> {
                if (dialog.isDisposed()) return;
                Dialogs.error(dialog, I18n.t("tuneNotch.title"),
                        I18n.t("freqResp.error.noDevice"));
            });
            return;
        }
        int sampleRate = prefs.current().getInputSampleRate();
        int bitDepth   = prefs.current().getInputBitDepth();
        // Read the DAC/ADC voltage references + dither resolution once: they
        // don't change for the lifetime of the session.
        double dacFsVrms = prefs.getDacFsVoltageAmpl();
        double adcFsVrms = prefs.getAdcFsVoltageRms();
        int    ditherBits = prefs.getFreqRespDitherBits();

        engine = new NotchSweepEngine(out, in, sampleRate, bitDepth, ditherBits);
        // Single daemon worker so deconv(n) overlaps the continuous capture.
        deconvExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "tune-notch-deconv");
            t.setDaemon(true);
            return t;
        });

        // The loop period MUST be a power of two: computeFromLogSweep sizes its
        // FFT to nextPow2(period), so a power-of-two period makes that FFT
        // exactly ONE period — a CIRCULAR transform, not a zero-padded linear
        // one.  Only then is a window grabbed at an arbitrary loop phase just a
        // circular shift that leaves |H(f)| unchanged; a linear FFT of an
        // unaligned window wraps the period and corrupts every bin into a spike.
        int rawSamples   = (int) Math.round(SWEEP_DURATION_SEC * sampleRate);
        int loPow2       = Integer.highestOneBit(Math.max(2, rawSamples));
        int sweepSamples = (rawSamples - loPow2 < (loPow2 << 1) - rawSamples) ? loPow2 : loPow2 << 1;
        int fadeSamples  = (int) Math.round(FADE_SEC * sampleRate);
        // One loop period of the captured stream, in ms — the grab cadence and
        // the percentage's denominator.
        long loopPeriodMs = Math.round(sweepSamples / (double) sampleRate * 1000.0);
        // Match the output grid to the deconvolution's FFT bin spacing for ONE
        // captured period: a grid finer than the real resolution makes the
        // bin→grid interpolation facet into the kink + comb (which then jitters
        // with per-capture noise).
        double binHz = engine.deconvBinHz(sweepSamples);

        try {
            // Open playback + capture ONCE; the looping generator streams until
            // close().  There is no per-sweep open/close or recording wait.
            double[] band = sweepBand(curStartHz, curStopHz);
            engine.start(band[0], band[1], curAmpVrms, dacFsVrms, sweepSamples, fadeSamples);
            awaitSettle(sweepSamples);
            if (!running) return;

            while (running) {
                double startHz = curStartHz;
                double stopHz  = curStopHz;
                double ampVrms = curAmpVrms;
                // Sample EXACTLY at the FFT bin centers (k·binHz): computeFromLogSweep
                // then reads each bin with fractional offset 0, i.e. no phase-sensitive
                // complex interpolation BETWEEN bins — which made the trace wiggle
                // frame-to-frame even when the bin magnitudes were identical.  The
                // view interpolates these stable points for the smooth display.
                double[] freqs = FreqRespCalHelper.binAlignedFreqs(startHz, stopHz, binHz, CHART_WIDTH_PX);

                long grabStart = System.nanoTime();
                StereoSamples window = engine.latestPeriod(sweepSamples);
                if (window == null) {
                    Thread.sleep(GRAB_RETRY_MS);
                    continue;
                }
                long grabMs = (System.nanoTime() - grabStart) / 1_000_000L;
                marshal(() -> sweepCount++);

                double[] sweepRef = engine.sweepRef();
                // Hand the deconvolution to the worker and immediately loop back;
                // it overlaps the continuous capture.
                deconvExecutor.submit(() -> deconvolveAndPublish(
                        window, sweepRef, fadeSamples, freqs, sampleRate,
                        startHz, stopHz, ampVrms, adcFsVrms, ditherBits, grabMs));

                // Spread the percentage across the loop period (progress toward
                // the next deconv result) while waiting for the next grab.
                tickStreamingPercent(loopPeriodMs);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            log.error("TuneNotch streaming sweep failed", ex);
        }
    }

    /** Blocks until the ring has buffered one full period (or the session
     *  stops), showing the buffer-fill percentage on the status line. */
    private void awaitSettle(int sweepSamples) throws InterruptedException {
        while (running && engine.availableSamples() < sweepSamples) {
            long avail = engine.availableSamples();
            int pct = (int) Math.min(PERCENT_FULL, avail * PERCENT_FULL / sweepSamples);
            marshal(() -> setStatus(pct + "%"));
            Thread.sleep(SETTLE_POLL_MS);
        }
    }

    /** Sleeps ~one loop period before the next grab, marshalling a rising
     *  percentage (elapsed / loopPeriod) to the status line every
     *  {@link #STATUS_TICK_MS} so the UI thread isn't spammed. */
    private void tickStreamingPercent(long loopPeriodMs) throws InterruptedException {
        long start = System.currentTimeMillis();
        long elapsed = 0;
        while (running && elapsed < loopPeriodMs) {
            int pct = (int) Math.min(PERCENT_FULL, elapsed * PERCENT_FULL / Math.max(1, loopPeriodMs));
            marshal(() -> setStatus(pct + "%"));
            Thread.sleep(Math.min(STATUS_TICK_MS, loopPeriodMs - elapsed));
            elapsed = System.currentTimeMillis() - start;
        }
    }

    /** Deconvolves one grabbed period's L+R channels (off the worker thread, on
     *  the deconv executor), builds the {@link StereoFreqRespResult}, and pushes
     *  the R channel into the embedded view.  The window is one steady-state
     *  period (leadIn 0); |H(f)| is phase-invariant so its alignment to the
     *  sweep-cycle start doesn't matter. */
    private void deconvolveAndPublish(StereoSamples window, double[] sweepRef, int fade,
                                      double[] freqs, int sampleRate,
                                      double startHz, double stopHz,
                                      double ampVrms, double adcFsVrms, int ditherBits, long grabMs) {
        if (!running) return;
        try {
            long decStart = System.nanoTime();
            FreqRespCalibration calL = FreqRespCalHelper.computeFromLogSweep(
                    window.left(),  sweepRef, 0, sampleRate, freqs, ampVrms, adcFsVrms, fade, "L", false);
            FreqRespCalibration calR = FreqRespCalHelper.computeFromLogSweep(
                    window.right(), sweepRef, 0, sampleRate, freqs, ampVrms, adcFsVrms, fade, "R", false);
            long decMs = (System.nanoTime() - decStart) / 1_000_000L;
            if (log.isInfoEnabled()) {
                log.info("TuneNotch update: grab {} ms, deconv {} ms", grabMs, decMs);
            }
            FreqRespSweepParams params = new FreqRespSweepParams(
                    startHz, stopHz, freqs.length,
                    SWEEP_DURATION_SEC, SWEEP_LEAD_IN_SEC, ampVrms, ditherBits);
            FreqRespResult left  = new FreqRespResult(
                    Channel.L, sampleRate, calL.freqs, calL.magLin, calL.phaseRad, params, null, false);
            FreqRespResult right = new FreqRespResult(
                    Channel.R, sampleRate, calR.freqs, calR.magLin, calR.phaseRad, params, null, false);
            StereoFreqRespResult stereo = new StereoFreqRespResult(left, right);
            if (!running) return;
            marshal(() -> onSweepResult(stereo.right()));
        } catch (Exception ex) {
            log.error("TuneNotch deconvolution failed", ex);
        }
    }

    /** Feeds a finished sweep into the embedded view, re-fits the magnitude
     *  axis to the band, and refreshes the notch readout.  Runs on the UI
     *  thread. */
    private void onSweepResult(FreqRespResult right) {
        if (dialog.isDisposed() || view.isDisposed() || right == null) return;
        latestResult = right;
        view.setRightResult(right);
        applyAutoMagWindow(right);
        computeNotch(right);
        view.redraw();
    }

    /** Finds the deepest notch (minimum linear magnitude) and stores its
     *  frequency / depth for the overlay.  Runs on the UI thread. */
    private void computeNotch(FreqRespResult right) {
        double[] mag   = right.getMagLin();
        double[] freqs = right.getFreqs();
        if (mag == null || freqs == null || mag.length == 0 || mag.length != freqs.length) {
            notchValid = false;
            return;
        }
        int idx = 0;
        for (int i = 1; i < mag.length; i++) {
            if (mag[i] < mag[idx]) idx = i;
        }
        // The discrete bin minimum is only accurate to the bin spacing; fit a
        // parabola to the three points around it (in linear magnitude, where the
        // resolution-limited null is a smooth dip) to recover the SUB-bin null
        // frequency and depth — matching the smooth minimum the view draws.
        double f = freqs[idx];
        double m = mag[idx];
        if (idx > 0 && idx < mag.length - 1) {
            double ym = mag[idx - 1], y0 = mag[idx], yp = mag[idx + 1];
            double denom = ym - 2.0 * y0 + yp;
            if (denom > 0.0) {
                double delta = Math.max(-0.5, Math.min(0.5, 0.5 * (ym - yp) / denom));
                f = freqs[idx] + delta * (freqs[idx + 1] - freqs[idx]);
                double v = y0 - 0.25 * (ym - yp) * delta;
                if (v > 0.0) m = v;
            }
        }
        notchHz    = f;
        notchDb    = 20.0 * Math.log10(m);
        notchValid = true;
    }

    /** Sets the magnitude axis from the {@link #MAG_AVG_FRAMES}-sweep rolling
     *  AVERAGE of the per-sweep min/max dB (± {@link #AUTO_FIT_PAD_DB}), so the
     *  vertical range doesn't jump hard frame-to-frame.  Runs on the UI thread,
     *  so the ring is single-threaded. */
    private void applyAutoMagWindow(FreqRespResult right) {
        double[] mag = right.getMagLin();
        if (mag == null || mag.length == 0) return;
        double minDb = Double.POSITIVE_INFINITY;
        double maxDb = Double.NEGATIVE_INFINITY;
        for (double m : mag) {
            if (m <= 0.0) continue;
            double db = 20.0 * Math.log10(m);
            if (db < minDb) minDb = db;
            if (db > maxDb) maxDb = db;
        }
        if (!Double.isFinite(minDb) || !Double.isFinite(maxDb)) return;
        magRingMin[magRingPos] = minDb;
        magRingMax[magRingPos] = maxDb;
        magRingPos = (magRingPos + 1) % MAG_AVG_FRAMES;
        if (magRingCount < MAG_AVG_FRAMES) magRingCount++;
        double sumMin = 0.0, sumMax = 0.0;
        for (int i = 0; i < magRingCount; i++) {
            sumMin += magRingMin[i];
            sumMax += magRingMax[i];
        }
        double avgMin = sumMin / magRingCount;
        double avgMax = sumMax / magRingCount;
        prefs.setFreqRespMagTopDb(avgMax + AUTO_FIT_PAD_DB);
        prefs.setFreqRespMagBotDb(avgMin - AUTO_FIT_PAD_DB);
    }

    // -------------------------------------------------------------------------
    // Status line + notch readout overlay
    // -------------------------------------------------------------------------

    /** Updates the status line: {@code Sweep {0} — {1}} where {0} is the update
     *  counter and {1} is {@code message} (the live percentage: buffer-fill
     *  during the initial settle, then progress toward the next deconv result).
     *  The worker advances {@link #sweepCount} via a marshalled call, so the
     *  number stays on the UI thread.  Runs on the UI thread. */
    private void setStatus(String message) {
        if (statusLabel == null || statusLabel.isDisposed()) return;
        statusLabel.setText(I18n.t("tuneNotch.status", sweepCount, message));
    }

    /** Paints the deepest-notch readout in the plot's top-left corner: black
     *  text with a one-pixel white outline so it reads on either a light or
     *  dark trace.  Runs on the UI thread (the view's paint callback). */
    private void onNotchPaint(PaintEvent e) {
        if (!notchValid) return;
        String s = String.format(Locale.US, "%.4f %s   %.3f %s",
                notchHz, I18n.t("unit.hz"), notchDb, I18n.t("unit.db"));
        GC gc = e.gc;
        gc.setTextAntialias(SWT.ON);
        Display display = e.display;
        Color white = display.getSystemColor(SWT.COLOR_WHITE);
        Color black = display.getSystemColor(SWT.COLOR_BLACK);
        gc.setForeground(white);
        for (int dx = -NOTCH_OUTLINE_PX; dx <= NOTCH_OUTLINE_PX; dx++) {
            for (int dy = -NOTCH_OUTLINE_PX; dy <= NOTCH_OUTLINE_PX; dy++) {
                if (dx == 0 && dy == 0) continue;
                gc.drawString(s, NOTCH_TEXT_X_PX + dx, NOTCH_TEXT_Y_PX + dy, true);
            }
        }
        gc.setForeground(black);
        gc.drawString(s, NOTCH_TEXT_X_PX, NOTCH_TEXT_Y_PX, true);
    }

    /** Paints a {@link #TARGET_LINE_WIDTH_PX}-px dashed vertical marker at the
     *  target frequency, coloured by the measured attenuation AT that frequency:
     *  {@link #TARGET_GREEN_DB} → green, {@link #TARGET_RED_DB} → red, linear
     *  between (a deep notch landed on the target reads green; off-target or
     *  shallow reads red).  Runs on the UI thread (the view's paint callback). */
    private void onTargetPaint(PaintEvent e) {
        FreqRespResult res = latestResult;
        double target = curTargetHz;
        double fMin = prefs.getFreqRespFreqMinHz();
        double fMax = prefs.getFreqRespFreqMaxHz();
        if (res == null || target <= 0.0 || fMin <= 0.0 || fMax <= fMin
                || target < fMin || target > fMax) return;
        double dbAtTarget = dbAt(res, target);
        if (!Double.isFinite(dbAtTarget)) return;

        // Plot rectangle — mirrors FreqRespView's margins so the marker lands on
        // the same log frequency axis the trace is drawn on.
        Rectangle area = view.getClientArea();
        int rightMargin = prefs.isFreqRespPhaseVisible() ? VIEW_MARGIN_RIGHT_PHASE : 0;
        int plotW   = Math.max(1, area.width - VIEW_MARGIN_LEFT - rightMargin);
        int plotTop = VIEW_MARGIN_TOP;
        int plotBot = Math.max(plotTop + 1, area.height - VIEW_MARGIN_BOTTOM);
        double frac = (Math.log(target) - Math.log(fMin)) / (Math.log(fMax) - Math.log(fMin));
        int x = VIEW_MARGIN_LEFT + (int) Math.round(frac * plotW);

        double t = Math.max(0.0, Math.min(1.0,
                (dbAtTarget - TARGET_GREEN_DB) / (TARGET_RED_DB - TARGET_GREEN_DB)));
        int r = (int) Math.round(255.0 * t);
        int g = (int) Math.round(255.0 * (1.0 - t));

        GC gc = e.gc;
        int savedWidth = gc.getLineWidth();
        int savedStyle = gc.getLineStyle();
        Color c = new Color(e.display, r, g, 0);
        gc.setForeground(c);
        gc.setLineWidth(TARGET_LINE_WIDTH_PX);
        gc.setLineStyle(SWT.LINE_DASH);
        gc.drawLine(x, plotTop, x, plotBot);
        gc.setLineWidth(savedWidth);
        gc.setLineStyle(savedStyle);
        c.dispose();   // custom Color — must be freed (unlike a system color)
    }

    /** Linear-interpolates the result's magnitude → dB at frequency {@code f}
     *  (the displayed grid is monotonic ascending). */
    private double dbAt(FreqRespResult res, double f) {
        double[] freqs = res.getFreqs();
        double[] mag   = res.getMagLin();
        if (freqs == null || mag == null || freqs.length < 2 || freqs.length != mag.length) {
            return Double.NaN;
        }
        int hi = 1;
        while (hi < freqs.length - 1 && freqs[hi] < f) hi++;
        int lo = hi - 1;
        double span = freqs[hi] - freqs[lo];
        double w = span > 0.0 ? (f - freqs[lo]) / span : 0.0;
        w = Math.max(0.0, Math.min(1.0, w));
        double m = mag[lo] * (1.0 - w) + mag[hi] * w;
        return m > 0.0 ? 20.0 * Math.log10(m) : Double.NaN;
    }

    // -------------------------------------------------------------------------
    // Close / teardown
    // -------------------------------------------------------------------------

    private void stopSweepLoop() {
        running = false;
        Thread t = sweepThread;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(THREAD_JOIN_TIMEOUT_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        sweepThread = null;
        // Drain the overlapping deconvolution worker before closing the device.
        if (deconvExecutor != null) {
            deconvExecutor.shutdownNow();
            try {
                if (!deconvExecutor.awaitTermination(DECONV_SHUTDOWN_TIMEOUT_S, TimeUnit.SECONDS)) {
                    log.warn("TuneNotch: deconvolution worker did not terminate within {} s",
                            DECONV_SHUTDOWN_TIMEOUT_S);
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            deconvExecutor = null;
        }
        // Close the streaming session ONCE: stop the generator + recording and
        // release both audio lines (the engine held them open for the session).
        NotchSweepEngine e = engine;
        engine = null;
        if (e != null) e.close();
        MessageBus.instance().publish(Events.FREQRESP_MEASUREMENT_STOPPED);
        saveDialogPrefs();
    }

    /** Persists ONLY the tune-notch parameters (start / stop / amplitude /
     *  target) from the detached copy back to the global preferences on close.
     *  The view's range / channel edits are deliberately dropped with the copy. */
    private void saveDialogPrefs() {
        Preferences globPrefs = Preferences.instance();
        globPrefs.setTuneNotchStartHz(prefs.getTuneNotchStartHz());
        globPrefs.setTuneNotchStopHz(prefs.getTuneNotchStopHz());
        globPrefs.setTuneNotchAmplitudeVrms(prefs.getTuneNotchAmplitudeVrms());
        globPrefs.setTuneNotchTargetHz(prefs.getTuneNotchTargetHz());
        globPrefs.save();
    }
    // -------------------------------------------------------------------------
    // Device coordination (mirrors FreqRespAnalyzerWorker)
    // -------------------------------------------------------------------------

    /** Polls {@link SharedCapture#isCapturing()} and the generator's
     *  bus-resolved {@code GENERATOR_RUNNING} responder until both are idle or
     *  the timeout expires. */
    private boolean waitForOtherWorkersStopped(long timeoutMs) {
        MessageBus bus = MessageBus.instance();
        SharedCapture capture = SharedCapture.instance();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!running) return false;
            boolean cap = capture.isCapturing();
            Boolean genRaw = bus.request(Events.GENERATOR_RUNNING);
            boolean gen = Boolean.TRUE.equals(genRaw);
            if (!cap && !gen) return true;
            try {
                Thread.sleep(DEVICE_POLL_INTERVAL_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private DeviceRef resolveDevice(boolean output, String name) {
        if (name == null || name.isEmpty()) return null;
        List<DeviceRef> list = output
                ? AudioBackend.instance().listOutputDevices()
                : AudioBackend.instance().listInputDevices();
        for (DeviceRef d : list) {
            if (name.equals(d.name())) return d;
        }
        return null;
    }

    /** Marshals {@code r} onto the UI thread, ignoring the torn-down display. */
    private void marshal(Runnable r) {
        if (dialog.isDisposed()) return;
        dialog.getDisplay().asyncExec(() -> {
            if (dialog.isDisposed()) return;
            r.run();
        });
    }
}
