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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.edgo.audio.measure.bind.Property;
import org.edgo.audio.measure.cli.util.StereoCaptureProgress;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.preferences.Preferences;

/**
 * Controller of the Frequency Response pane: owns the sweep measurement
 * worker's lifecycle and the sweep-timing rules — the lead-in floor and the
 * derived sweep duration that pairs with the chosen FFT size.
 *
 * <p>The constructor subscribes to the preferences the timing rules depend
 * on (FFT size, lead-in) and to audio-format changes, re-deriving and
 * persisting {@code freqRespDurationSec} — the tab control only renders it.
 * Towards the view the controller publishes nothing itself: the worker
 * reports through {@link Events#FREQRESP_MEASUREMENT_STARTED} /
 * {@link Events#FREQRESP_RESULT_AVAILABLE} /
 * {@link Events#FREQRESP_MEASUREMENT_FAILED} /
 * {@link Events#FREQRESP_MEASUREMENT_STOPPED}, and the pane / view
 * subscribe to those.
 */
public final class FreqRespController {

    /** Lead-in floor (s) — shorter lead-ins starve the deconvolution. */
    private static final double MIN_LEAD_IN_SEC = 0.05;
    /** Capture tail (s) the analyzer records past lead-in + sweep. */
    private static final double ANALYZER_TAIL_SEC = 0.5;
    /** Sweep-duration floor (s) — a small FFT size with a long lead-in
     *  must not produce a negative or unworkable sweep. */
    private static final double MIN_SWEEP_SEC = 0.5;

    /** Engine driving the sweep + deconvolution; created lazily on the
     *  first measurement so app startup never opens audio devices. */
    private FreqRespAnalyzerWorker worker;
    /** UI-thread marshaller ({@code display::asyncExec}) handed through to
     *  the worker for its completion / failure publishes. */
    private final Executor uiExecutor;
    /** Detach actions for every Preferences / bus subscription made in the
     *  constructor; run by {@link #shutdown()}. */
    private final List<Runnable> unsubscribes = new ArrayList<>();

    public FreqRespController(Executor uiExecutor) {
        this.uiExecutor = uiExecutor;
        Preferences prefs = Preferences.instance();
        onPref(prefs.freqRespFftSizeProperty(), n -> deriveDuration());
        onPref(prefs.freqRespLeadInSecProperty(), v -> {
            if (v < MIN_LEAD_IN_SEC) {
                prefs.setFreqRespLeadInSec(MIN_LEAD_IN_SEC);
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
     *  live meter.  Completion / failure arrive via the bus events. */
    public synchronized void startMeasurement(StereoCaptureProgress progress) {
        if (isMeasurementRunning()) return;
        if (worker == null) {
            worker = new FreqRespAnalyzerWorker(uiExecutor);
        }
        worker.start(progress);
    }

    /** Cooperative cancel of an in-flight measurement; no-op when idle. */
    public void cancelMeasurement() {
        if (worker != null) worker.cancel();
    }

    /** True while a sweep measurement is in flight. */
    public boolean isMeasurementRunning() {
        return worker != null && worker.isRunning();
    }

    /** Total expected capture time of one sweep — lead-in + sweep + the
     *  analyzer's tail.  Sizes the pane's busy meter time axis. */
    public double expectedMeasurementSeconds() {
        Preferences prefs = Preferences.instance();
        return prefs.getFreqRespLeadInSec()
             + prefs.getFreqRespDurationSec()
             + ANALYZER_TAIL_SEC;
    }

    /** Detaches every subscription and cancels an in-flight measurement —
     *  called from the pane's dispose listener. */
    public void shutdown() {
        for (Runnable r : unsubscribes) r.run();
        unsubscribes.clear();
        cancelMeasurement();
    }

    // -------------------------------------------------------------------------
    // Sweep-timing rules
    // -------------------------------------------------------------------------

    /** Derives and persists the sweep duration that pairs with the chosen
     *  FFT size, so the analyzer's {@code nextPow2(leadIn + sweep + tail)}
     *  lands exactly on {@code fftSize} — no wasted bins.  Clamped to
     *  {@link #MIN_SWEEP_SEC}. */
    private void deriveDuration() {
        Preferences prefs = Preferences.instance();
        int sr = Math.max(1, prefs.current().getInputSampleRate());
        long leadIn   = Math.round(prefs.getFreqRespLeadInSec() * sr);
        long tail     = Math.round(ANALYZER_TAIL_SEC * sr);
        long sweep    = prefs.getFreqRespFftSize() - leadIn - tail;
        long minSweep = Math.round(MIN_SWEEP_SEC * sr);
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
