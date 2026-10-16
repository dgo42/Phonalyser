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

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Shell;
import org.edgo.audio.measure.bind.Property;
import org.edgo.audio.measure.dsp.FreqRespCalHelper;
import org.edgo.audio.measure.dsp.FreqRespCalibration;
import org.edgo.audio.measure.dsp.StereoFreqRespCalibration;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.common.Dialogs;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.sound.StereoCaptureProgress;

import lombok.extern.log4j.Log4j2;

/**
 * Controller of the Frequency Response pane: owns the sweep measurement
 * worker's lifecycle and the sweep-timing rules - the lead-in floor and the
 * derived sweep duration that pairs with the chosen FFT size.
 *
 * <p>The constructor subscribes to the preferences the timing rules depend
 * on (FFT size, lead-in) and to audio-format changes, re-deriving and
 * persisting {@code freqRespDurationSec} - the tab control only renders it.
 *
 * <p>The worker's reports - result, error, end of the run - arrive marshalled
 * onto the UI thread ({@link #onResult}, {@link #onFailed}, {@link
 * #onStopped}) and go straight on to the pane that owns this controller.  The
 * UI thread starts them in the order the worker posted them; that start order
 * is the whole guarantee: a modal dialog the pane opens on one report runs the
 * next report inside its event loop.  The controller keeps no result: the
 * view holds the curves.  The end of a run is published on the bus as {@link
 * Events#FREQRESP_MEASUREMENT_STOPPED} before the pane hears of it, so the
 * other modules take the audio devices back at once, even while a dialog of
 * the pane is modal.  Only this controller's own runs reach the pane: the
 * exclusive-measurement events of another module (the Tune-notch wizard, the
 * delay measurement) never wipe the view's curves or unlock the pane.
 *
 * <p>The save of a pair of curves to a {@code .frc} file lives here too
 * ({@link #saveMeasurement}) - the one save for every caller; its dialogs
 * open on the pane's shell.
 */
@Log4j2
public final class FreqRespController {

    /** The pane that owns this controller: the receiver of the measurement
     *  reports and, through its shell, the parent of the save dialogs. */
    private final FreqRespPane pane;
    /** Engine driving the sweep + deconvolution; created lazily on the
     *  first measurement so app startup never opens audio devices. */
    private FreqRespAnalyzerWorker worker;
    /** Detach actions for every Preferences / bus subscription made in the
     *  constructor; run by {@link #shutdown()}. */
    private final List<Runnable> unsubscribes = new ArrayList<>();

    public FreqRespController(FreqRespPane pane) {
        this.pane = pane;
        Preferences prefs = Preferences.instance();
        onPref(prefs.freqRespFftSizeProperty(), n -> deriveDuration());
        onPref(prefs.freqRespLeadInSecProperty(), v -> {
            if (v < FreqRespConstants.LEAD_IN_MIN_SEC) {
                prefs.setFreqRespLeadInSec(FreqRespConstants.LEAD_IN_MIN_SEC);
                return;   // the re-set re-enters here with the clamped value
            }
            deriveDuration();
        });
        // The derived duration also depends on the sample rate.
        Consumer<Void> audioFormatListener = ignored -> deriveDuration();
        onBus(Events.AUDIO_FORMAT_CHANGED, audioFormatListener);
        // Initial sync: the YAML may carry a stale durationSec that no
        // longer matches the persisted fftSize / leadIn (or vice versa).
        // Re-derive and persist on every build so analyzer + label agree.
        deriveDuration();
    }

    // -------------------------------------------------------------------------
    // Measurement lifecycle
    // -------------------------------------------------------------------------

    /** Kicks off one sweep measurement (no-op while one is in flight).
     *  {@code progress} streams each capture block's RMS to the caller's
     *  live meter.  Result, failure and the end of the run come back through
     *  {@link #onResult}, {@link #onFailed} and {@link #onStopped}. */
    public synchronized void startMeasurement(StereoCaptureProgress progress) {
        if (isMeasurementRunning()) return;
        if (worker == null) {
            worker = new FreqRespAnalyzerWorker(this);
        }
        worker.start(progress);
    }

    /** The worker's finished deconvolution of both channels - UI thread. */
    void onResult(StereoFreqRespResult stereo) {
        pane.onMeasurementResult(stereo);
    }

    /** The worker's run ended in an error; {@code message} words it - UI
     *  thread. */
    void onFailed(String message) {
        pane.onMeasurementFailed(message);
    }

    /** The worker's run ended - completed, cancelled or failed - UI thread.
     *  The bus first: the other modules take the audio devices back at once,
     *  even when the pane then opens a modal dialog. */
    void onStopped() {
        MessageBus.instance().publish(Events.FREQRESP_MEASUREMENT_STOPPED);
        pane.onMeasurementStopped();
    }

    /** Cooperative cancel of an in-flight measurement; no-op when idle. */
    public void cancelMeasurement() {
        if (worker != null) worker.cancel();
    }

    /** True while a sweep measurement is in flight. */
    public boolean isMeasurementRunning() {
        return worker != null && worker.isRunning();
    }

    /** Total expected capture time of one sweep - lead-in + sweep + the
     *  analyzer's tail, which is the fixed minimum-sweep span (the decay
     *  ring-out needs the full half second even when the lead-in is short).
     *  Sizes the pane's progress meter time axis. */
    public double expectedMeasurementSeconds() {
        return sweepLeadInSec()
             + Preferences.instance().getFreqRespDurationSec()
             + FreqRespConstants.SWEEP_MIN_SEC;
    }

    /** The silence the sweep emits before the chirp: the fixed
     *  {@link FreqRespCalHelper#OFFSET_LEAD_IN_SEC} window the deconvolution
     *  measures each channel's DC offset on, plus the operator's lead-in. */
    public double sweepLeadInSec() {
        return FreqRespCalHelper.OFFSET_LEAD_IN_SEC
             + Preferences.instance().getFreqRespLeadInSec();
    }

    /** Detaches every subscription and cancels an in-flight measurement -
     *  called from the pane's dispose listener. */
    public void shutdown() {
        for (Runnable r : unsubscribes) r.run();
        unsubscribes.clear();
        cancelMeasurement();
    }

    // -------------------------------------------------------------------------
    // Saving the measurement
    // -------------------------------------------------------------------------

    /** Saves {@code left} / {@code right} - the curves the caller shows,
     *  already de-embedded by the active calibration - to a {@code .frc} file
     *  the operator picks in a Save-as dialog pre-filled with the last saved
     *  file name.  Either curve may be {@code null} (no measurement on that
     *  channel).  The dialogs open on the pane's shell.
     *  @return the written path; {@code null} when both curves are
     *          {@code null} (an info dialog says so), on cancel, or when the
     *          write failed (an error dialog says so) */
    public String saveMeasurement(FreqRespResult left, FreqRespResult right) {
        Shell shell = pane.getGroup().getShell();
        if (left == null && right == null) {
            Dialogs.info(shell,
                    I18n.t("freqResp.tab.saveTo"),
                    I18n.t("freqResp.saveTo.error.noResult"));
            return null;
        }
        Preferences prefs = Preferences.instance();
        // The Save-as dialog opens on every save so the operator picks (or
        // confirms) the destination; pre-filled with the last saved file
        // name, saving to the same file again is two clicks.
        FileDialog fd = new FileDialog(shell, SWT.SAVE);
        fd.setText(I18n.t("freqResp.saveTo.dialog"));
        fd.setFilterExtensions(new String[]{ "*.frc" });
        fd.setOverwrite(true);
        String memFolder = prefs.getFreqRespSaveFolder();
        if (memFolder != null) fd.setFilterPath(memFolder);
        String lastPath = prefs.getFreqRespSavePath();
        if (lastPath != null && !lastPath.isEmpty()) {
            fd.setFileName(new File(lastPath).getName());
        } else {
            fd.setFileName("freqresp_" + LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".frc");
        }
        String picked = fd.open();
        if (picked == null) return null;
        try {
            // Both channels are always written; the file format is strict
            // stereo (5 columns: f, mag_L_dB, mag_R_dB, phase_L_deg,
            // phase_R_deg).  When one channel is hidden in the view, the
            // visible channel is duplicated into the missing slot so the
            // file stays self-consistent on round-trip.
            // While the inputs are combined, both channels carry the master.
            Channel combined = prefs.getFreqRespCombinedMaster();
            if (combined == Channel.L && left  != null) right = left;
            if (combined == Channel.R && right != null) left  = right;
            FreqRespResult primary = left != null ? left : right;
            FreqRespResult other   = left != null ? right : left;
            if (other == null) other = primary;
            FreqRespCalibration calL = new FreqRespCalibration(
                    primary.getFreqs(), primary.getMagLin(), primary.getPhaseRad());
            FreqRespCalibration calR = new FreqRespCalibration(
                    other.getFreqs(),   other.getMagLin(),   other.getPhaseRad());
            FreqRespSweepParams p = primary.getSweepParams();
            FreqRespCalHelper.saveFrc(
                    new StereoFreqRespCalibration(calL, calR),
                    picked, primary.getSampleRate(),
                    p.getStartHz(), p.getStopHz(), p.getSweepPoints(),
                    p.getAmplitudeVrms());

            prefs.setFreqRespSavePath(picked);
            prefs.setFreqRespSaveFolder(new File(picked).getParent());
            prefs.save();
            if (log.isInfoEnabled()) {
                log.info("FreqResp measurement saved to {}", picked);
            }
            // Every calibration list (FreqResp and FFT) learns of the file
            // through the saved-file broadcast saveFrc sends.
            return picked;
        } catch (Exception ex) {
            if (log.isWarnEnabled()) {
                log.warn("FreqResp save failed: {}", picked, ex);
            }
            // The dialog names the file; the cause is a technical text in
            // English and stays in the log, so the translated dialog shows no
            // untranslated words.
            Dialogs.error(shell,
                    I18n.t("freqResp.saveTo.dialog"),
                    I18n.t("freqResp.error.measurement.save").replace("{0}", picked));
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Sweep-timing rules
    // -------------------------------------------------------------------------

    /** Derives and persists the sweep duration that pairs with the chosen
     *  FFT size, so the analyzer's {@code nextPow2(leadIn + sweep + tail)}
     *  lands exactly on {@code fftSize} - no wasted bins.  The tail the
     *  analyzer records equals the lead-in, so both subtract here.  Clamped
     *  to {@link FreqRespConstants#SWEEP_MIN_SEC}. */
    private void deriveDuration() {
        Preferences prefs = Preferences.instance();
        int sr = Math.max(1, prefs.current().getInputSampleRate());
        long leadIn   = Math.round(sweepLeadInSec() * sr);
        long tail     = Math.round(FreqRespConstants.SWEEP_MIN_SEC * sr);
        long sweep    = prefs.getFreqRespFftSize() - leadIn - tail;
        long minSweep = Math.round(FreqRespConstants.SWEEP_MIN_SEC * sr);
        if (sweep < minSweep) sweep = minSweep;
        prefs.setFreqRespDurationSec(sweep / (double) sr);
    }

    private <T> void onPref(Property<T> property, Consumer<T> action) {
        property.addListener(action);
        unsubscribes.add(() -> property.removeListener(action));
    }

    private <T> void onBus(String eventName, Consumer<T> listener) {
        MessageBus bus = MessageBus.instance();
        bus.subscribe(eventName, listener);
        unsubscribes.add(() -> bus.unsubscribe(eventName, listener));
    }
}
