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

package org.edgo.audio.measure.gui.freqresp;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.common.Icon;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.common.ShellIcons;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.preferences.CalibrationEntry;
import org.edgo.audio.measure.preferences.CalibrationList;
import org.edgo.audio.measure.preferences.Preferences;

/**
 * Two-step modal wizard: step 1 measures the DAC -> ADC loopback, step 2 the
 * filter under test, each saved as its own calibration file and applied to
 * the FreqResp calibration list.  The wizard only controls the process: its
 * Play is the pane's Play (lock, progress window with Cancel, the
 * controller's measurement), whose result reaches the view exactly as a pane
 * measurement does; its save is the pane's save of the curves the view
 * shows; Apply writes the calibration list in the preferences, which the
 * calibration tab follows.  The pane, its owner, passes each run's result
 * and end on to the open wizard.
 *
 * <p>Each step runs READY -> MEASURED -> SAVED -> APPLIED: Play measures, then
 * one action button appears (Save calibration... / Apply).  The step's Play
 * button stays available to measure again, and a new result returns the step
 * to MEASURED.  Step 1's save switches every calibration off first, so the
 * loopback is saved raw; its Apply activates the loopback as item 0 and opens
 * step 2.  Step 2's save leaves only item 0 on, so the filter is saved
 * de-embedded by the loopback alone; its Apply puts the filter into item 1
 * beside it and keeps the wizard open for another filter measurement.
 * Closing asks only while a measurement is unsaved or a saved calibration is
 * not applied; nothing the wizard changed is rolled back.
 */
public final class FreqRespWizardDialog {

    private static final int BUTTON_WIDTH = 75;

    /** Where one step stands. */
    private enum StepState {
        READY, MEASURED, SAVED, APPLIED;

        private StepState() {
        }
    }

    /** One step: its page, its action button, its state and the file its last
     *  save wrote.  Its Play button needs no handle - it measures in every
     *  state.  While a run is in flight the pane's modal progress window
     *  keeps it from a second click; once a cancel is pending that window
     *  lets a second close through (the escape hatch for a hung teardown),
     *  and the pane's Play then refuses the second start (it returns false
     *  while a run is in flight). */
    private static final class Step {
        Composite page;
        Button    actionButton;
        StepState state = StepState.READY;
        /** The path the pane's save returned; the item Apply writes. */
        String    savedPath;

        private Step() {
        }
    }

    private final Shell        parentShell;
    /** The owner: its Play and its save run the wizard's measurements, and it
     *  passes each run's result and end on to the wizard. */
    private final FreqRespPane pane;

    private final Step loopback = new Step();
    private final Step filter   = new Step();

    private Shell       dialog;
    private StackLayout stack;
    private Composite   pageHost;
    /** Back / Next / Close - switched off with the step page while a save or
     *  an apply runs. */
    private Composite   buttonBar;
    private Button      backBtn, nextBtn;
    private Step        shownStep;

    /** The step whose sweep is in flight; {@code null} while the wizard runs
     *  none. */
    private Step    measuringStep;
    /** Set when the in-flight run delivered its result; onMeasurementStopped
     *  reads it. */
    private boolean resultArrived;

    public FreqRespWizardDialog(Shell parent, FreqRespPane pane) {
        this.parentShell = parent;
        this.pane        = pane;
    }

    /** Opens the wizard and blocks until the user closes it. */
    public void open() {
        // FIXED size = initial - no RESIZE bit.
        dialog = new Shell(parentShell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL);
        ShellIcons.apply(dialog);
        dialog.setText(I18n.t("freqResp.wizard.title"));
        dialog.setSize(540, 360);
        GridLayout outer = new GridLayout(1, false);
        outer.marginWidth = 12; outer.marginHeight = 12; outer.verticalSpacing = 10;
        dialog.setLayout(outer);

        pageHost = new Composite(dialog, SWT.NONE);
        pageHost.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        stack = new StackLayout();
        pageHost.setLayout(stack);

        buildStepPage(loopback, I18n.t("freqResp.wizard.page1.title"),
                                I18n.t("freqResp.wizard.page1.text"));
        buildStepPage(filter,   I18n.t("freqResp.wizard.page2.title"),
                                I18n.t("freqResp.wizard.page2.text"));

        buildButtonBar();
        showStep(loopback);

        // The Close button, the window X and Escape all arrive here.
        dialog.addListener(SWT.Close, e -> e.doit = confirmClose());

        Dialogs.centerOnParent(dialog);
        dialog.open();
        Display d = dialog.getDisplay();
        while (!dialog.isDisposed()) {
            if (!d.readAndDispatch()) d.sleep();
        }
    }

    // -------------------------------------------------------------------------
    // Pages
    // -------------------------------------------------------------------------

    private void buildStepPage(Step step, String titleText, String bodyText) {
        Composite c = new Composite(pageHost, SWT.NONE);
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 8; gl.marginHeight = 8; gl.verticalSpacing = 12;
        c.setLayout(gl);

        Label heading = new Label(c, SWT.NONE);
        heading.setText(titleText);
        heading.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label body = new Label(c, SWT.WRAP);
        body.setText(bodyText);
        GridData bgd = new GridData(SWT.FILL, SWT.FILL, true, true);
        bgd.widthHint = 480;
        body.setLayoutData(bgd);

        Composite buttons = new Composite(c, SWT.NONE);
        buttons.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        GridLayout bl = new GridLayout(2, false);
        bl.marginWidth = 0; bl.marginHeight = 0; bl.horizontalSpacing = 8;
        buttons.setLayout(bl);

        Button play = new Button(buttons, SWT.PUSH);
        // Match the generator pane's main Play button: solid green play LED
        // (no text), with the tooltip carrying the action label.
        Image playIcon = IconUtils.icon(c.getDisplay(), Icon.PLAY_DARK_BIG);
        if (playIcon != null) play.setImage(playIcon);
        play.setToolTipText(I18n.t("freqResp.button.play.start"));
        play.addListener(SWT.Selection, e -> startMeasurement(step));

        Button action = new Button(buttons, SWT.PUSH);
        action.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        action.addListener(SWT.Selection, e -> onAction(step));

        step.page         = c;
        step.actionButton = action;
        refreshStepButtons(step);
    }

    private void buildButtonBar() {
        Composite bar = new Composite(dialog, SWT.NONE);
        bar.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
        GridLayout gl = new GridLayout(3, true);
        gl.marginWidth = 0; gl.marginHeight = 0;
        bar.setLayout(gl);
        buttonBar = bar;

        backBtn = new Button(bar, SWT.PUSH);
        backBtn.setText(I18n.t("freqResp.wizard.button.back"));
        backBtn.setLayoutData(buttonLayoutData());
        backBtn.addListener(SWT.Selection, e -> showStep(loopback));

        nextBtn = new Button(bar, SWT.PUSH);
        nextBtn.setText(I18n.t("freqResp.wizard.button.next"));
        nextBtn.setLayoutData(buttonLayoutData());
        nextBtn.addListener(SWT.Selection, e -> showStep(filter));

        Button closeBtn = new Button(bar, SWT.PUSH);
        closeBtn.setText(I18n.t("common.close"));
        closeBtn.setLayoutData(buttonLayoutData());
        closeBtn.addListener(SWT.Selection, e -> dialog.close());
    }

    /** A button's layout data: {@link #BUTTON_WIDTH} wide. */
    private GridData buttonLayoutData() {
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.widthHint = BUTTON_WIDTH;
        return gd;
    }

    private void showStep(Step step) {
        shownStep = step;
        stack.topControl = step.page;
        pageHost.layout();
        refreshNavEnable();
    }

    /** Back only from step 2; Next only from step 1 once it is applied. */
    private void refreshNavEnable() {
        backBtn.setEnabled(shownStep == filter);
        nextBtn.setEnabled(shownStep == loopback && loopback.state == StepState.APPLIED);
    }

    /** The action button follows the step's state: hidden in READY (Play is
     *  the step's only action there), Save calibration... (MEASURED), Apply
     *  (SAVED); disabled once applied.  Play measures again in every state. */
    private void refreshStepButtons(Step step) {
        boolean measured = step.state != StepState.READY;
        step.actionButton.setVisible(measured);
        ((GridData) step.actionButton.getLayoutData()).exclude = !measured;
        if (measured) {
            step.actionButton.setText(step.state == StepState.MEASURED
                    ? I18n.t("freqResp.wizard.button.save")
                    : I18n.t("freqResp.wizard.button.apply"));
            step.actionButton.setEnabled(step.state != StepState.APPLIED);
        }
        // From the page down: the button row was sized without the excluded
        // button, and a layout of the row alone keeps that size.
        step.page.layout(new Control[] { step.actionButton });
    }

    /** Save or Apply, with the step's buttons and Back / Next / Close off
     *  until it is done: the save's dialogs open on the main window, not on
     *  this one, so a second press would otherwise start a second save. */
    private void onAction(Step step) {
        setActionRunning(step, true);
        try {
            switch (step.state) {
                case MEASURED: save(step);  break;
                case SAVED:    apply(step); break;
                default:                    break;   // READY: hidden, APPLIED: disabled
            }
        } finally {
            setActionRunning(step, false);
        }
    }

    /** Switches the step's page (Play, Save / Apply) and the button bar off
     *  while a save or apply runs, and on again afterwards; disabling the
     *  composites keeps each button's own enabled state for afterwards. */
    private void setActionRunning(Step step, boolean running) {
        if (dialog.isDisposed()) return;
        step.page.setEnabled(!running);
        buttonBar.setEnabled(!running);
    }

    // -------------------------------------------------------------------------
    // Measurement - the pane's Play; the pane reports the run's result and end
    // -------------------------------------------------------------------------

    /** Play: the pane's Play, with its progress window and Cancel.  It starts
     *  nothing while a run is in flight and returns false then, so the step
     *  is marked only for a run that will end in a stopped report.  The run's
     *  reports reach the wizard later through a marshalled call, never inside
     *  the Play, so marking the step after it is in time. */
    private void startMeasurement(Step step) {
        if (!pane.play()) return;
        measuringStep = step;
        resultArrived = false;
    }

    /** Called by the pane on the UI thread with each run's result: the
     *  deconvolution is done (the view shows it); the step moves on the
     *  stopped report. */
    void onMeasurementResult(StereoFreqRespResult stereo) {
        if (measuringStep == null || stereo == null) return;
        resultArrived = true;
    }

    /** Called by the pane on the UI thread when a run ended - completed,
     *  failed or cancelled.  Only a run that delivered a result moves the
     *  step to MEASURED; a new measurement also invalidates the step's
     *  earlier save, and a new loopback measurement the whole filter step. */
    void onMeasurementStopped() {
        if (measuringStep == null || dialog.isDisposed()) return;
        Step step = measuringStep;
        measuringStep = null;
        if (resultArrived) {
            step.state     = StepState.MEASURED;
            step.savedPath = null;
            if (step == loopback && filter.state != StepState.READY) {
                // The filter step is measured against the applied loopback and
                // saves the curve the view shows: a new loopback measurement
                // replaces both, so the filter step starts over.
                filter.state     = StepState.READY;
                filter.savedPath = null;
                refreshStepButtons(filter);
            }
        }
        resultArrived = false;
        refreshStepButtons(step);
        refreshNavEnable();
    }

    // -------------------------------------------------------------------------
    // Save + Apply
    // -------------------------------------------------------------------------

    /** The pane's save - the view's save of the curves it shows, the same
     *  save as the tab control's: a returned path -> SAVED, null (cancel, no
     *  result, failure) stays MEASURED.  The view divides its curves by every
     *  active calibration, so the calibrations are switched first: the
     *  loopback is saved raw (every item off), the filter de-embedded by the
     *  loopback alone (only item 0 on) - never by an older filter item. */
    private void save(Step step) {
        if (step == loopback) {
            deactivateAllCalibrations();
        } else {
            activateOnlyLoopbackCalibration();
        }
        String path = pane.saveMeasurement();
        if (path == null) return;
        step.savedPath = path;
        step.state     = StepState.SAVED;
        refreshStepButtons(step);
    }

    /** Writes the saved file into the preferences calibration list.  The
     *  apply-calibration flag goes on FIRST: it has no listener of its own,
     *  and the list writes reach the calibration tab, whose store sync makes
     *  the view re-derive its curves, so the flag has to be on by then. */
    private void apply(Step step) {
        Preferences prefs = Preferences.instance();
        prefs.setFreqRespApplyCalibration(true);
        CalibrationList cals = prefs.getFreqRespCalibrations();
        if (step == loopback) {
            // Item 0 = the loopback, activated; every other item is still
            // off from this step's save.
            if (cals.isEmpty()) cals.add(new CalibrationEntry());
            CalibrationEntry first = cals.get(0);
            first.setPath(step.savedPath);
            first.active().set(true);
        } else {
            // Item 1 = the filter, active beside the loopback in item 0.
            while (cals.size() < 2) cals.add(new CalibrationEntry());
            CalibrationEntry second = cals.get(1);
            second.setPath(step.savedPath);
            second.active().set(true);
        }
        step.state = StepState.APPLIED;
        refreshStepButtons(step);
        if (step == loopback) {
            showStep(filter);
        } else {
            refreshNavEnable();
        }
    }

    /** Sets every item of the preferences calibration list inactive. */
    private void deactivateAllCalibrations() {
        for (CalibrationEntry entry : Preferences.instance().getFreqRespCalibrations()) {
            entry.active().set(false);
        }
    }

    /** Leaves only item 0 - the loopback - of the preferences calibration
     *  list active. */
    private void activateOnlyLoopbackCalibration() {
        CalibrationList cals = Preferences.instance().getFreqRespCalibrations();
        for (int i = 0; i < cals.size(); i++) {
            cals.get(i).active().set(i == 0);
        }
    }

    // -------------------------------------------------------------------------
    // Close
    // -------------------------------------------------------------------------

    /** Returns {@code true} when the wizard may close: asks while any step
     *  holds an unsaved measurement, else while any step holds a saved but
     *  unapplied calibration.  Nothing is rolled back. */
    private boolean confirmClose() {
        if (loopback.state == StepState.MEASURED || filter.state == StepState.MEASURED) {
            return Dialogs.confirm(dialog,
                    I18n.t("freqResp.wizard.unsaved.title"),
                    I18n.t("freqResp.wizard.unsaved.message")) == SWT.YES;
        }
        if (loopback.state == StepState.SAVED || filter.state == StepState.SAVED) {
            return Dialogs.confirm(dialog,
                    I18n.t("freqResp.wizard.unapplied.title"),
                    I18n.t("freqResp.wizard.unapplied.message")) == SWT.YES;
        }
        return true;
    }

}
