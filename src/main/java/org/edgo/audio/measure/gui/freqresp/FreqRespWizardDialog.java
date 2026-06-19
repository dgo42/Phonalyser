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

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

import org.edgo.audio.measure.dsp.FreqRespCalHelper;
import org.edgo.audio.measure.dsp.FreqRespCalibration;
import org.edgo.audio.measure.cli.util.StereoCaptureProgress;
import org.edgo.audio.measure.dsp.StereoFreqRespCalibration;
import org.edgo.audio.measure.common.FreqRespCorrectionStore;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.common.Icon;
import org.edgo.audio.measure.gui.common.IconUtils;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.DeviceRef;

import lombok.extern.log4j.Log4j2;

/**
 * Three-page modal wizard that walks the user through measuring the
 * DAC → ADC loopback transfer (page 1) and then the device under test
 * (page 2), finally saving the result as a calibration file (page 3) and
 * applying it as the active calibration.  Mirrors the standard Wizard /
 * Next / Back / Cancel flow common to installer dialogs.
 *
 * <p>Cancellation discards every measurement taken inside the wizard and
 * restores the {@link FreqRespCorrectionStore} to whatever snapshot it
 * was at the moment {@link #open()} ran.  An "are you sure?" ack-confirm
 * fires on cancel whenever any of the three pages has captured fresh
 * data, regardless of which page the cancel was clicked from.
 */
@Log4j2
public final class FreqRespWizardDialog {

    private final Shell        parentShell;
    private final FreqRespView hostView;
    private final FreqRespCorrectionStore.Snapshot preWizardSnapshot;

    private Shell      dialog;
    private StackLayout stack;
    private Composite  pageHost;
    private Composite  page1, page2, page3;
    private Button     backBtn, nextBtn, cancelBtn;
    private Button     page1Play, page2Play;
    private Button     page3Save, page3Apply;

    private StereoFreqRespResult directResult;
    private StereoFreqRespResult dutResult;
    private String         savedCalPath;
    private boolean        unsavedDirty;
    /** "Please wait" modal shown for the duration of a wizard measurement.
     *  Same shape as the pane's busy shell so the user sees consistent
     *  feedback regardless of which Play button started the sweep. */
    private Shell             busyShell;
    /** Live "level vs. time" meter inside {@link #busyShell}, fed by the
     *  capture-progress callback that the wizard passes through to the
     *  analyzer config.  Null while no busy shell is open. */
    private FreqRespLiveMeter busyMeter;
    private boolean        appliedSuccessfully;
    private int            currentPageIndex;

    public FreqRespWizardDialog(Shell parent, FreqRespView view) {
        this.parentShell       = parent;
        this.hostView          = view;
        this.preWizardSnapshot = hostView.getCorrectionStore().snapshot();
    }

    /** Opens the wizard and blocks until the user finishes / cancels. */
    public void open() {
        dialog = new Shell(parentShell, SWT.DIALOG_TRIM | SWT.APPLICATION_MODAL | SWT.RESIZE);
        dialog.setText(I18n.t("freqResp.wizard.title"));
        dialog.setSize(540, 360);
        GridLayout outer = new GridLayout(1, false);
        outer.marginWidth = 12; outer.marginHeight = 12; outer.verticalSpacing = 10;
        dialog.setLayout(outer);

        pageHost = new Composite(dialog, SWT.NONE);
        pageHost.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        stack = new StackLayout();
        pageHost.setLayout(stack);

        page1 = buildPlayPage(I18n.t("freqResp.wizard.page1.title"),
                              I18n.t("freqResp.wizard.page1.text"),
                              btn -> page1Play = btn);
        page2 = buildPlayPage(I18n.t("freqResp.wizard.page2.title"),
                              I18n.t("freqResp.wizard.page2.text"),
                              btn -> page2Play = btn);
        page3 = buildSavePage();

        page1Play.addListener(SWT.Selection, e -> runMeasurement(true));
        page2Play.addListener(SWT.Selection, e -> runMeasurement(false));

        buildButtonBar();
        showPage(0);

        dialog.addListener(SWT.Close, e -> {
            e.doit = handleCancel();
        });

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

    private Composite buildPlayPage(String titleText, String bodyText, Consumer<Button> playSink) {
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

        Button play = new Button(c, SWT.PUSH);
        // Match the generator pane's main Play button: solid green play LED
        // (no text), with the tooltip carrying the action label.
        Image playIcon = IconUtils.icon(c.getDisplay(), Icon.PLAY_DARK_BIG);
        if (playIcon != null) play.setImage(playIcon);
        play.setToolTipText(I18n.t("freqResp.button.play.start"));
        play.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        playSink.accept(play);
        return c;
    }

    private Composite buildSavePage() {
        Composite c = new Composite(pageHost, SWT.NONE);
        GridLayout gl = new GridLayout(2, false);
        gl.marginWidth = 8; gl.marginHeight = 8;
        gl.horizontalSpacing = 8; gl.verticalSpacing = 12;
        c.setLayout(gl);

        Label heading = new Label(c, SWT.NONE);
        heading.setText(I18n.t("freqResp.wizard.page3.title"));
        GridData hgd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        hgd.horizontalSpan = 2;
        heading.setLayoutData(hgd);

        Label body = new Label(c, SWT.WRAP);
        body.setText(I18n.t("freqResp.wizard.page3.text"));
        GridData bgd = new GridData(SWT.FILL, SWT.FILL, true, true);
        bgd.widthHint = 480;
        bgd.horizontalSpan = 2;
        body.setLayoutData(bgd);

        page3Save = new Button(c, SWT.PUSH);
        page3Save.setText(I18n.t("freqResp.wizard.button.save"));
        page3Save.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        page3Save.addListener(SWT.Selection, e -> doSaveCalibration());

        page3Apply = new Button(c, SWT.PUSH);
        page3Apply.setText(I18n.t("freqResp.wizard.button.apply"));
        page3Apply.setEnabled(false);
        page3Apply.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));
        page3Apply.addListener(SWT.Selection, e -> doApplyCalibration());
        return c;
    }

    private void buildButtonBar() {
        Composite bar = new Composite(dialog, SWT.NONE);
        bar.setLayoutData(new GridData(SWT.END, SWT.CENTER, true, false));
        GridLayout gl = new GridLayout(3, true);
        gl.marginWidth = 0; gl.marginHeight = 0;
        bar.setLayout(gl);

        backBtn = new Button(bar, SWT.PUSH);
        backBtn.setText(I18n.t("freqResp.wizard.button.back"));
        backBtn.addListener(SWT.Selection, e -> { if (currentPageIndex > 0) showPage(currentPageIndex - 1); });

        nextBtn = new Button(bar, SWT.PUSH);
        nextBtn.setText(I18n.t("freqResp.wizard.button.next"));
        nextBtn.addListener(SWT.Selection, e -> { if (currentPageIndex < 2) showPage(currentPageIndex + 1); });

        cancelBtn = new Button(bar, SWT.PUSH);
        cancelBtn.setText(I18n.t("freqResp.wizard.button.cancel"));
        cancelBtn.addListener(SWT.Selection, e -> { if (handleCancel()) dialog.close(); });
    }

    private void showPage(int idx) {
        currentPageIndex = idx;
        switch (idx) {
            case 0:  stack.topControl = page1; break;
            case 1:  stack.topControl = page2; break;
            default: stack.topControl = page3; break;
        }
        pageHost.layout();
        refreshNavEnable();
    }

    private void refreshNavEnable() {
        backBtn.setEnabled(currentPageIndex > 0);
        switch (currentPageIndex) {
            case 0:  nextBtn.setEnabled(directResult != null);   break;
            case 1:  nextBtn.setEnabled(dutResult    != null);   break;
            default: nextBtn.setEnabled(false);                  break;
        }
    }

    // -------------------------------------------------------------------------
    // Measurement
    // -------------------------------------------------------------------------

    private void runMeasurement(boolean directLeg) {
        Preferences prefs = Preferences.instance();
        DeviceRef out = findDevice(true,  prefs.current().getOutputDeviceName());
        DeviceRef in  = findDevice(false, prefs.current().getInputDeviceName());
        if (out == null || in == null) {
            Dialogs.error(dialog, I18n.t("freqResp.wizard.title"),
                    "No output / input device selected.  Open Preferences first.");
            return;
        }
        page1Play.setEnabled(false);
        page2Play.setEnabled(false);
        backBtn.setEnabled(false);
        nextBtn.setEnabled(false);

        int sr = Math.max(1, prefs.current().getInputSampleRate());
        double totalSec = prefs.getFreqRespLeadInSec()
                        + prefs.getFreqRespDurationSec()
                        + 0.5;
        openBusyShell(totalSec);
        MessageBus.instance().publish(Events.FREQRESP_MEASUREMENT_STARTED);
        // Marshal capture-progress to the UI thread so the busy shell's
        // live meter paints level-vs-time as the sweep runs.
        Display d = dialog.getDisplay();
        StereoCaptureProgress progress = (totalSamples, rmsLin) -> {
            double tSec = totalSamples / (double) sr;
            d.asyncExec(() -> {
                if (busyMeter != null && !busyMeter.isDisposed()) {
                    busyMeter.appendSample(tSec, rmsLin);
                }
            });
        };
        Thread t = new Thread(() -> {
            try {
                FreqRespAnalyzerConfig cfg = FreqRespAnalyzerConfig.builder()
                        .outDevice(out).inDevice(in)
                        .sampleRate(prefs.current().getInputSampleRate())
                        .bitDepth(prefs.current().getInputBitDepth())
                        .ditherBits(prefs.getFreqRespDitherBits())
                        .startHz(prefs.getFreqRespStartHz())
                        .stopHz(prefs.getFreqRespStopHz())
                        .sweepPoints(prefs.getFreqRespSweepPoints())
                        .durationSec(prefs.getFreqRespDurationSec())
                        .leadInSec(prefs.getFreqRespLeadInSec())
                        .amplitudeVrms(prefs.getFreqRespAmplitudeVrms())
                        .dacFsVoltageRms(prefs.getDacFsVoltageAmpl())
                        .adcFsVoltageRms(prefs.getAdcFsVoltageRms())
                        .applyCalibration(!directLeg)  // page 2 divides out the page-1 transfer
                        .captureProgress(progress)
                        .build();
                StereoFreqRespResult r = new FreqRespAnalyzer(cfg).run(null, null);
                dialog.getDisplay().asyncExec(() -> onMeasurementDone(directLeg, r, null));
            } catch (Exception ex) {
                log.error("Wizard measurement failed", ex);
                dialog.getDisplay().asyncExec(() -> onMeasurementDone(directLeg, null,
                        ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
            } finally {
                dialog.getDisplay().asyncExec(() ->
                        MessageBus.instance().publish(Events.FREQRESP_MEASUREMENT_STOPPED));
            }
        }, "freqresp-wizard");
        t.setDaemon(true);
        t.start();
    }

    private void onMeasurementDone(boolean directLeg, StereoFreqRespResult r, String error) {
        if (dialog.isDisposed()) return;
        closeBusyShell();
        page1Play.setEnabled(true);
        page2Play.setEnabled(true);
        if (error != null) {
            Dialogs.error(dialog, I18n.t("freqResp.wizard.title"),
                    I18n.t("freqResp.wizard.error.measureFailed").replace("{0}", error));
            refreshNavEnable();
            return;
        }
        if (r == null) { refreshNavEnable(); return; }
        unsavedDirty = true;
        if (directLeg) {
            directResult = r;
            StereoFreqRespCalibration cal = stereoCalFromResult(r);
            // Only the direct (transient) slot — the view applies it
            // automatically alongside the entries list so page 2 gets
            // the page-1 loopback subtracted without polluting the
            // persistent calibration tab.
            hostView.getCorrectionStore().setDirect(cal);
            hostView.setLeftResult(r.left());
            hostView.setRightResult(r.right());
            hostView.setSourceFilePath(null);
            showPage(1);
        } else {
            dutResult = r;
            hostView.setLeftResult(r.left());
            hostView.setRightResult(r.right());
            showPage(2);
        }
    }

    /** Wraps both channels of a {@link StereoFreqRespResult} into a
     *  {@link StereoFreqRespCalibration} suitable for the cal store. */
    private StereoFreqRespCalibration stereoCalFromResult(StereoFreqRespResult r) {
        FreqRespCalibration l = new FreqRespCalibration(
                r.left().getFreqs(), r.left().getMagLin(), r.left().getPhaseRad());
        FreqRespCalibration rR = new FreqRespCalibration(
                r.right().getFreqs(), r.right().getMagLin(), r.right().getPhaseRad());
        return new StereoFreqRespCalibration(l, rR);
    }

    // -------------------------------------------------------------------------
    // Save + Apply
    // -------------------------------------------------------------------------

    private void doSaveCalibration() {
        if (dutResult == null) {
            Dialogs.info(dialog, I18n.t("freqResp.wizard.title"),
                    I18n.t("freqResp.saveTo.error.noResult"));
            return;
        }
        FileDialog fd = new FileDialog(dialog, SWT.SAVE);
        fd.setText(I18n.t("freqResp.saveTo.dialog"));
        fd.setFilterExtensions(new String[]{ "*.frc" });
        fd.setOverwrite(true);
        Preferences prefs = Preferences.instance();
        String memFolder = prefs.getFreqRespSaveFolder();
        if (memFolder != null) fd.setFilterPath(memFolder);
        fd.setFileName("freqresp_cal_" + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".frc");
        String picked = fd.open();
        if (picked == null) return;
        try {
            // The analyzer now always returns the raw deconvolution
            // (loopback × DUT, in page-2 terms).  Save needs the
            // DUT-alone curve, so divide the raw result by the loaded
            // "direct" calibration before writing.  When no direct cal
            // is available (shouldn't happen at page-3 — page 1 always
            // populates it) the raw measurement is saved as-is.
            StereoFreqRespCalibration stereoCal = stereoCalFromResult(dutResult);
            StereoFreqRespCalibration direct =
                    hostView.getCorrectionStore().getDirect();
            if (direct != null) {
                FreqRespCalHelper.divideInPlace(stereoCal.left(),  direct.left());
                FreqRespCalHelper.divideInPlace(stereoCal.right(), direct.right());
            }
            FreqRespSweepParams p = dutResult.left().getSweepParams();
            FreqRespCalHelper.saveCsv(stereoCal,
                    picked, dutResult.left().getSampleRate(),
                    p.getStartHz(), p.getStopHz(), p.getSweepPoints(),
                    p.getAmplitudeVrms());
            savedCalPath = picked;
            prefs.setFreqRespSaveFolder(new File(picked).getParent());
            prefs.save();
            unsavedDirty = false;
            page3Apply.setEnabled(true);
            log.info("Wizard calibration saved to {}", picked);
            // Reload this .frc anywhere it is already loaded as a calibration.
            MessageBus.instance().publish(Events.CALIBRATION_FILE_SAVED, picked);
        } catch (Exception ex) {
            log.warn("Wizard save failed", ex);
            Dialogs.error(dialog, I18n.t("freqResp.saveTo.dialog"),
                    I18n.t("freqResp.error.measurement.save").replace("{0}",
                            ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
        }
    }

    private void doApplyCalibration() {
        if (savedCalPath == null) return;
        try {
            StereoFreqRespCalibration cal = FreqRespCalHelper.loadCsv(savedCalPath);
            hostView.getCorrectionStore().setCurrent(cal, savedCalPath);
            Preferences prefs = Preferences.instance();
            prefs.setFreqRespPrimaryCalibrationPath(savedCalPath);
            prefs.setFreqRespApplyCalibration(true);
            prefs.save();
            unsavedDirty = false;
            appliedSuccessfully = true;  // suppress the "unsaved?" prompt
            dialog.close();
        } catch (Exception ex) {
            log.warn("Wizard apply failed", ex);
            Dialogs.error(dialog, I18n.t("freqResp.wizard.title"),
                    I18n.t("freqResp.error.calibration.load").replace("{0}",
                            ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
        }
    }

    // -------------------------------------------------------------------------
    // Cancel / restore
    // -------------------------------------------------------------------------

    /** Returns {@code true} when the wizard is allowed to actually close. */
    private boolean handleCancel() {
        // After Apply, the calibration has been committed and the dialog
        // closes via dialog.close() — that path is NOT a cancel, so skip
        // the "unsaved?" prompt and skip restoring the pre-wizard snapshot.
        if (appliedSuccessfully) return true;
        if (directResult != null || dutResult != null || unsavedDirty) {
            int code = Dialogs.confirm(dialog,
                    I18n.t("freqResp.wizard.unsaved.title"),
                    I18n.t("freqResp.wizard.unsaved.message"));
            if (code != SWT.YES) return false;
        }
        hostView.getCorrectionStore().restore(preWizardSnapshot);
        return true;
    }

    private void openBusyShell(double totalDurationSec) {
        if (busyShell != null && !busyShell.isDisposed()) return;
        Shell s = new Shell(dialog, SWT.TITLE | SWT.BORDER
                | SWT.APPLICATION_MODAL);
        s.setText(I18n.t("freqResp.busy.title"));
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 12; gl.marginHeight = 10;
        gl.verticalSpacing = 8;
        s.setLayout(gl);
        Label l = new Label(s, SWT.CENTER);
        l.setText(I18n.t("freqResp.busy.message"));
        l.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Sweep geometry feeds the meter's time → instantaneous-frequency
        // mapping, which scales the trace smoothing with the period.
        Preferences prefs = Preferences.instance();
        busyMeter = new FreqRespLiveMeter(s, totalDurationSec,
                prefs.getFreqRespLeadInSec(), prefs.getFreqRespDurationSec(),
                prefs.getFreqRespStartHz(), prefs.getFreqRespStopHz());
        GridData mg = new GridData(SWT.FILL, SWT.CENTER, true, false);
        mg.widthHint  = 520;
        mg.heightHint = 100;
        busyMeter.setLayoutData(mg);

        s.pack();
        Rectangle pb = dialog.getBounds();
        Point sz = s.getSize();
        s.setLocation(pb.x + (pb.width  - sz.x) / 2,
                      pb.y + (pb.height - sz.y) / 2);
        s.open();
        Display d = dialog.getDisplay();
        for (int i = 0; i < 5; i++) if (!d.readAndDispatch()) break;
        busyShell = s;
    }

    private void closeBusyShell() {
        if (busyShell != null && !busyShell.isDisposed()) busyShell.close();
        busyShell = null;
        busyMeter = null;
    }

    private DeviceRef findDevice(boolean output, String name) {
        if (name == null || name.isEmpty()) return null;
        for (DeviceRef d : output
                ? AudioBackend.instance().listOutputDevices()
                : AudioBackend.instance().listInputDevices()) {
            if (name.equals(d.name())) return d;
        }
        return null;
    }
}
