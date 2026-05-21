package org.edgo.audio.measure.gui.freqresp;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.eclipse.swt.widgets.Display;

import org.edgo.audio.measure.gui.bus.Events;
import org.edgo.audio.measure.gui.bus.MessageBus;
import org.edgo.audio.measure.gui.preferences.Preferences;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.DeviceRef;

import lombok.extern.log4j.Log4j2;

/**
 * Daemon-thread driver for the Frequency Response measurement.  Reads the
 * current sweep configuration from {@link Preferences} and runs ONE
 * stereo sweep via {@link FreqRespAnalyzer}, which captures both ADC
 * channels in a single playback and deconvolves them in parallel.  The
 * resulting L and R {@link FreqRespResult}s are posted back to the UI
 * thread via {@link Display#asyncExec(Runnable)}.
 *
 * <p>Publishes {@link Events#FREQRESP_MEASUREMENT_STARTED} on start and
 * {@link Events#FREQRESP_MEASUREMENT_STOPPED} when the run ends (whether
 * by completion, cancellation, or error).  The host pane uses these to
 * lock its own controls; the FFT / scope / generator panes subscribe so
 * they release the shared capture device and gray their Record / Play
 * buttons for the duration.
 *
 * <p>Cancellation is cooperative — {@link #cancel()} sets a flag the
 * analyzer polls at every checkpoint and inside the capture sleep loop;
 * an in-flight measurement aborts within ~50 ms.
 */
@Log4j2
public final class FreqRespAnalyzerWorker {

    private final Display              display;
    private final FreqRespView         view;
    private final Runnable             onComplete;
    private final Consumer<String>     onError;

    private volatile Thread       workerThread;
    private final AtomicBoolean   cancelFlag = new AtomicBoolean(false);

    public FreqRespAnalyzerWorker(Display display, FreqRespView view,
                                  Runnable onComplete, Consumer<String> onError) {
        this.display    = display;
        this.view       = view;
        this.onComplete = onComplete;
        this.onError    = onError;
    }

    /** Starts the worker thread (no-op if already running). */
    public synchronized void start() {
        if (workerThread != null && workerThread.isAlive()) {
            log.warn("FreqResp worker already running — start() ignored");
            return;
        }
        cancelFlag.set(false);
        // Clear previous results from the view so the user sees an empty
        // chart while the sweep runs (avoids confusion about whether the
        // displayed trace is the current or previous measurement).
        display.asyncExec(view::clearResults);
        workerThread = new Thread(this::runMeasurement, "freqresp-worker");
        workerThread.setDaemon(true);
        workerThread.start();
        MessageBus.instance().publish(Events.FREQRESP_MEASUREMENT_STARTED);
    }

    /** Sets the cooperative cancel flag.  The analyzer checks it before
     *  each step; the capture loop polls it every 50 ms. */
    public void cancel() {
        cancelFlag.set(true);
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
            Preferences prefs = Preferences.instance();

            DeviceRef out = resolveDevice(true,  prefs.current().getOutputDeviceName());
            DeviceRef in  = resolveDevice(false, prefs.current().getInputDeviceName());
            if (out == null) {
                reportError("No output device selected.  Open Preferences first.");
                return;
            }
            if (in == null) {
                reportError("No input device selected.  Open Preferences first.");
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
                    .applyCalibration(prefs.isFreqRespApplyCalibration())
                    .build();
            StereoFreqRespResult stereo = new FreqRespAnalyzer(cfg).run(null, cancelFlag::get);
            if (stereo == null) return;

            MessageBus.instance().publish(Events.FREQRESP_RESULT_AVAILABLE, stereo.left());
            MessageBus.instance().publish(Events.FREQRESP_RESULT_AVAILABLE, stereo.right());

            display.asyncExec(() -> {
                view.setLeftResult(stereo.left());
                view.setRightResult(stereo.right());
            });
        } catch (InterruptedException ex) {
            log.info("FreqResp measurement cancelled");
        } catch (Exception ex) {
            log.error("FreqResp measurement failed", ex);
            reportError(ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
        } finally {
            // Publish STOPPED + run the completion callback on the UI
            // thread.  The host pane uses both to re-enable its controls
            // and reset the Play button state.
            display.asyncExec(() -> {
                MessageBus.instance().publish(Events.FREQRESP_MEASUREMENT_STOPPED);
                if (onComplete != null) onComplete.run();
            });
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
        display.asyncExec(() -> { if (onError != null) onError.accept(message); });
    }
}
