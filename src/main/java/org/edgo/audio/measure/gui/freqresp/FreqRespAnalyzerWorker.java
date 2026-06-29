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
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.edgo.audio.measure.cli.util.StereoCaptureProgress;
import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.i18n.I18n;
import org.edgo.audio.measure.preferences.Preferences;
import org.edgo.audio.measure.gui.sound.SharedCapture;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.DeviceRef;

import lombok.extern.log4j.Log4j2;

/**
 * Daemon-thread driver for the Frequency Response measurement.  Reads the
 * current sweep configuration from {@link Preferences} and runs ONE
 * stereo sweep via {@link FreqRespAnalyzer}, which captures both ADC
 * channels in a single playback and deconvolves them in parallel.
 *
 * <p>All reporting goes through the bus: {@link
 * Events#FREQRESP_MEASUREMENT_STARTED} on start, {@link
 * Events#FREQRESP_RESULT_AVAILABLE} with the {@link StereoFreqRespResult}
 * on success, {@link Events#FREQRESP_MEASUREMENT_FAILED} with a message on
 * error, and {@link Events#FREQRESP_MEASUREMENT_STOPPED} when the run ends
 * (completion, cancellation, or error).  The host pane locks its controls
 * on STARTED/STOPPED; the FFT / scope / generator panes subscribe so they
 * release the shared capture device and gray their Record / Play buttons
 * for the duration.  FAILED and STOPPED are marshalled through the
 * injected UI executor so widget-touching subscribers run on the SWT
 * thread, exactly as before.
 *
 * <p>Cancellation is cooperative — {@link #cancel()} sets a flag the
 * analyzer polls at every checkpoint and inside the capture sleep loop;
 * an in-flight measurement aborts within ~50 ms.
 */
@Log4j2
public final class FreqRespAnalyzerWorker {

    /** UI-thread marshaller ({@code display::asyncExec}) for the FAILED /
     *  STOPPED publishes — their subscribers touch widgets. */
    private final Executor uiExecutor;

    private volatile Thread             workerThread;
    private final AtomicBoolean         cancelFlag       = new AtomicBoolean(false);
    private volatile StereoCaptureProgress activeProgress;

    public FreqRespAnalyzerWorker(Executor uiExecutor) {
        this.uiExecutor = uiExecutor;
    }

    /** Starts the worker thread (no-op if already running).  See
     *  {@link #start(StereoCaptureProgress)} when a live progress meter
     *  is hooked up. */
    public synchronized void start() {
        start(null);
    }

    /** Variant that wires a per-block capture-progress callback into the
     *  analyzer config so a UI-side meter can paint a live "level vs.
     *  time" curve while the sweep runs. */
    public synchronized void start(StereoCaptureProgress progress) {
        if (workerThread != null && workerThread.isAlive()) {
            log.warn("FreqResp worker already running — start() ignored");
            return;
        }
        cancelFlag.set(false);
        activeProgress = progress;
        // Publish FIRST so subscribers (FFT pane, scope pane, generator
        // pane) run their stop logic synchronously on this thread before
        // the worker thread launches.  The worker still spin-waits for
        // the shared capture device + generator to actually idle before
        // opening the audio device for the sweep — synchronous
        // subscriber returns guarantee they ASKED their workers to
        // stop, not that the worker threads have terminated.
        MessageBus.instance().publish(Events.FREQRESP_MEASUREMENT_STARTED);
        workerThread = new Thread(this::runMeasurement, "freqresp-worker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    /** Sets the cooperative cancel flag.  The analyzer checks it before
     *  each step; the capture loop polls it every 50 ms. */
    public void cancel() {
        cancelFlag.set(true);
    }

    /** Polls {@link SharedCapture#isCapturing()} and the generator's
     *  bus-resolved {@code GENERATOR_RUNNING} responder until both
     *  signal idle, or the timeout expires.  Returns {@code true} when
     *  every other worker has released the audio device + DAC, {@code
     *  false} on timeout / cancel.  Sleeps 20 ms between polls so the
     *  CPU isn't pinned during the typical 50-200 ms teardown window. */
    private boolean waitForOtherWorkersStopped(long timeoutMs) {
        MessageBus bus = MessageBus.instance();
        SharedCapture capture = SharedCapture.instance();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cancelFlag.get()) return false;
            boolean cap = capture.isCapturing();
            Boolean genRaw = bus.request(Events.GENERATOR_RUNNING);
            boolean gen = Boolean.TRUE.equals(genRaw);
            if (!cap && !gen) return true;
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** True while a measurement is in flight. */
    public synchronized boolean isRunning() {
        Thread t = workerThread;
        return t != null && t.isAlive();
    }

    // -------------------------------------------------------------------------
    // Worker thread
    // -------------------------------------------------------------------------

    private void runMeasurement() {
        try {
            // Wait for the other panes' workers to release the audio
            // device before we open it ourselves.  The
            // FREQRESP_MEASUREMENT_STARTED publish (on the UI thread)
            // already called their stop() methods, but those typically
            // signal-and-return; the actual worker threads + the OS
            // device handle release take another few tens of ms.
            // Without this wait the FreqRespAnalyzer's openCapture
            // races the FFT/scope worker's last analyze() and either
            // fails with a "device in use" error or steals samples
            // mid-frame.
            if (!waitForOtherWorkersStopped(2000)) {
                log.warn("FreqResp: timeout waiting for other workers to release the audio device");
            }
            if (cancelFlag.get()) return;
            Preferences prefs = Preferences.instance();

            DeviceRef out = resolveDevice(true,  prefs.current().getOutputDeviceName());
            DeviceRef in  = resolveDevice(false, prefs.current().getInputDeviceName());
            if (out == null) {
                reportError(I18n.t("freqResp.error.noOutputDevice"));
                return;
            }
            if (in == null) {
                reportError(I18n.t("freqResp.error.noInputDevice"));
                return;
            }

            int sampleRate = prefs.current().getInputSampleRate();
            int bitDepth   = prefs.current().getInputBitDepth();

            if (cancelFlag.get()) return;

            // One stereo sweep, two parallel deconvolutions.
            FreqRespAnalyzerConfig cfg = FreqRespAnalyzerConfig.builder()
                    .outDevice(out)
                    .inDevice(in)
                    .sampleRate(sampleRate)
                    .bitDepth(bitDepth)
                    .ditherBits(prefs.getFreqRespDitherBits())
                    .startHz(prefs.getFreqRespStartHz())
                    .stopHz(prefs.getFreqRespStopHz())
                    .sweepPoints(prefs.getFreqRespSweepPoints())
                    .durationSec(prefs.getFreqRespDurationSec())
                    .leadInSec(prefs.getFreqRespLeadInSec())
                    .amplitudeVrms(prefs.getFreqRespAmplitudeVrms())
                    .dacFsVoltageRms(prefs.getDacFsVoltageAmpl())
                    .adcFsVoltageRms(prefs.getAdcFsVoltageRms())
                    .applyCalibration(prefs.isFreqRespApplyCalibration())
                    .captureProgress(activeProgress)
                    .build();
            StereoFreqRespResult stereo = new FreqRespAnalyzer(cfg).run(null, cancelFlag::get);
            if (stereo == null) return;

            MessageBus.instance().publish(Events.FREQRESP_RESULT_AVAILABLE, stereo);
        } catch (InterruptedException ex) {
            log.info("FreqResp measurement cancelled");
        } catch (Exception ex) {
            log.error("FreqResp measurement failed", ex);
            reportError(ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
        } finally {
            // Publish STOPPED on the UI thread.  The host pane re-enables
            // its controls and closes the busy shell on it; the other
            // panes' subscribers restore their Record / Play buttons.
            uiExecutor.execute(() ->
                    MessageBus.instance().publish(Events.FREQRESP_MEASUREMENT_STOPPED));
            synchronized (this) { workerThread = null; }
        }
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

    private void reportError(String message) {
        log.warn("FreqResp worker: {}", message);
        uiExecutor.execute(() ->
                MessageBus.instance().publish(Events.FREQRESP_MEASUREMENT_FAILED, message));
    }
}
