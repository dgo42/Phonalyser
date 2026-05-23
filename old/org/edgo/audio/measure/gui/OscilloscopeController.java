package org.edgo.audio.measure.gui;

import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.sound.AudioBackend;
import org.edgo.audio.measure.sound.AudioCapture;
import org.edgo.audio.measure.sound.DeviceRef;
import org.eclipse.swt.widgets.Display;

import java.util.List;

import javax.sound.sampled.LineUnavailableException;

/**
 * Owns the oscilloscope's audio capture lifecycle and drives periodic
 * repaints of the {@link OscilloscopeView} and {@link CondensedView}.
 *
 * <p>{@link #start()} opens an {@link AudioCapture} on the input device
 * configured in {@link Preferences} (sample rate / bit depth from the
 * current backend's {@code BackendPrefs}), allocates a 11-second
 * {@link SignalBuffer}, wires the device's sample listener to push
 * normalised floats into the buffer, and starts a 30-FPS redraw timer.
 * {@link #stop()} reverses that: stops + closes the capture, releases the
 * buffer, and forces both views to re-render their empty grid.
 *
 * <p>All buffer reads happen on the SWT UI thread inside the views' paint
 * handlers; writes happen on the audio capture thread.  {@link SignalBuffer}
 * synchronises both sides.
 */
@Log4j2
public final class OscilloscopeController {

    /**
     * Ring-buffer length, in seconds.  Sized for at least two display
     * windows at the slowest time/div (1 s/div × 10 = 10 s) plus a small
     * margin so the trigger-position slider can sweep across the full
     * window without the display falling off the buffer.  At 384 kHz this
     * costs ≈ 67 MB of heap for the two channels combined; the trade-off
     * for being able to pan ±10 s either side of the trigger event.
     */
    private static final double BUFFER_SECONDS = 22.0;
    /**
     * Minimum wait between paints in milliseconds.  We re-arm the timer at
     * 1 ms so the actual paint rate is bounded by how fast the view can
     * render a frame, not by a fixed period.  On Windows the OS timer
     * resolution is ~15 ms, so this effectively schedules the next paint
     * "as soon as possible" without busy-spinning the event loop.
     */
    private static final int    REDRAW_PERIOD_MS = 1;

    private final OscilloscopeView mainView;
    private final CondensedView    condensedView;
    private final Display          display;

    private AudioCapture capture;
    private volatile boolean running;
    /** Human-readable message produced by the last failed {@link #start()}, or {@code null} on success / clean state. */
    private String lastStartError;

    public OscilloscopeController(OscilloscopeView mainView, CondensedView condensedView) {
        this.mainView      = mainView;
        this.condensedView = condensedView;
        this.display       = mainView.getDisplay();
    }

    /** Returns {@code true} if a capture session is currently active. */
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns a human-readable description of the last {@link #start()}
     * failure (or {@code null} if the last start succeeded / wasn't attempted).
     * Cleared on the next successful start.
     */
    public String getLastStartError() {
        return lastStartError;
    }

    /**
     * Opens the audio device, allocates the ring buffer, and begins live
     * sample capture and view repaints.  Logs and bails out if the
     * preferred input device is unavailable or the device fails to open;
     * the reason is also stored in {@link #getLastStartError()} so the UI
     * can show a dialog.
     */
    public void start() {
        if (running) return;
        lastStartError = null;

        Preferences prefs = Preferences.instance();
        Preferences.BackendPrefs bp = prefs.current();
        String deviceName = bp.getInputDeviceName();
        if (deviceName == null) {
            lastStartError = "No input device selected. Open Preferences and pick a capture device.";
            log.warn("Oscilloscope: {}", lastStartError);
            return;
        }
        DeviceRef device = findInputDevice(deviceName);
        if (device == null) {
            lastStartError = "The saved input device \"" + deviceName + "\" is no longer available. "
                           + "Reconnect the device or pick another one in Preferences.";
            log.warn("Oscilloscope: {}", lastStartError);
            return;
        }
        final int sampleRate = bp.getInputSampleRate();
        final int bitDepth   = bp.getInputBitDepth();

        try {
            AudioCapture cap = AudioBackend.instance().openCapture(device, sampleRate, bitDepth);
            cap.open();

            SignalBuffer buf = new SignalBuffer(sampleRate, BUFFER_SECONDS);

            final long unsignedMask = (bitDepth >= 32) ? 0xFFFFFFFFL : ((1L << bitDepth) - 1);
            final double midpoint   = 1L << (bitDepth - 1);
            cap.setSampleListener(samples -> {
                // Convert offset-binary integer samples to normalised float
                // in [-1, +1] and push into the buffer on the capture thread.
                for (var s : samples) {
                    long uL = ((long) s.ch0) & unsignedMask;
                    long uR = ((long) s.ch1) & unsignedMask;
                    float l = (float) ((uL - midpoint) / midpoint);
                    float r = (float) ((uR - midpoint) / midpoint);
                    buf.append(l, r);
                }
            });

            this.capture = cap;
            mainView.setBuffer(buf);
            condensedView.setBuffer(buf);

            cap.startRecording();
            running = true;
            // Measurement compute runs on its own daemon thread so the SWT
            // paint thread doesn't block on the Goertzel scan at high sample
            // rates.  Started after setBuffer so the worker sees the new buf.
            mainView.startMeasurementThread();
            log.info("Oscilloscope started: device={}, sampleRate={} Hz, bitDepth={} bits",
                    device.displayName(), sampleRate, bitDepth);
            scheduleRedraw();
        } catch (Exception ex) {
            lastStartError = translateOpenFailure(ex, device, sampleRate, bitDepth);
            log.error("Oscilloscope: failed to start capture — {}", ex.getMessage(), ex);
            cleanupAfterFailure();
        }
    }

    /**
     * Maps the various low-level capture-open exceptions
     * ({@link javax.sound.sampled.LineUnavailableException} from the
     * csjsound backend, {@link IllegalStateException} from PortAudio's
     * {@code check()} on the WDM-KS backend, etc.) to a human-readable
     * sentence suitable for a UI dialog.  Falls back to the raw exception
     * message when no specific keyword match wins.
     */
    private String translateOpenFailure(Exception ex, DeviceRef device,
                                        int sampleRate, int bitDepth) {
        String raw = ex.getMessage();
        String lower = (raw == null) ? "" : raw.toLowerCase();
        String header = "Cannot start capture on \"" + device.displayName() + "\" at "
                + sampleRate + " Hz / " + bitDepth + " bits.\n\n";
        if (lower.contains("does not support format")
                || lower.contains("invalid sample rate")
                || lower.contains("invalid sample format")
                || lower.contains("paunanticipatedhosterror") && lower.contains("format")) {
            return header + "The selected sample rate or bit depth is not supported by this device. "
                  + "Try a different combination in Preferences (16 / 24 bits at 48 / 96 / 192 / 384 kHz are commonly accepted).";
        }
        if (ex instanceof LineUnavailableException
                || lower.contains("device unavailable")
                || lower.contains("paunanticipatedhosterror")   // WDM-KS exclusive-pin conflict
                || lower.contains("line with the given format is not available")
                || lower.contains("line unavailable")
                || lower.contains("exclusive")
                || lower.contains("in use")
                || lower.contains("busy")) {
            return header + "The device is currently in use by another application "
                  + "(exclusive-mode capture allows only one client at a time). "
                  + "Close other audio apps using this input and try again.";
        }
        if (lower.contains("not found") || lower.contains("no such device")) {
            return header + "The device could not be opened — it may have been unplugged "
                  + "or its driver is not loaded.";
        }
        if (raw != null && !raw.isBlank()) {
            return header + raw;
        }
        return header + ex.getClass().getSimpleName();
    }

    /**
     * Stops the capture but keeps the buffer attached so the last captured
     * frame remains visible in both views (resize / expose events will
     * repaint that frozen signal).  A subsequent {@link #start()} replaces
     * the buffer with a fresh one.
     */
    public void stop() {
        if (!running && capture == null) return;
        running = false;
        // Stop the measurement worker first so it doesn't try to read from
        // the buffer while capture is being torn down.
        if (!mainView.isDisposed()) mainView.stopMeasurementThread();
        if (capture != null) {
            try { capture.stopRecording(); } catch (Exception ignored) {}
            try { capture.close();         } catch (Exception ignored) {}
            capture = null;
        }
        log.info("Oscilloscope stopped.");
    }

    /**
     * Number of main-view redraws between condensed-view redraws.  The
     * condensed strip walks ~1 s of audio (lots of samples per pixel) and
     * its per-paint work would otherwise halve the main view's cap/s.
     * Updating it at ~6 Hz is plenty for the human eye and keeps the main
     * trace at full speed.
     */
    private static final int CONDENSED_DECIMATION = 10;
    private int redrawCounter = 0;

    /** Schedules the next paint pass via {@link Display#timerExec}. */
    private void scheduleRedraw() {
        if (!running) return;
        display.timerExec(REDRAW_PERIOD_MS, () -> {
            if (!running) return;
            if (!mainView.isDisposed()) mainView.redraw();
            if (redrawCounter++ >= CONDENSED_DECIMATION) {
                redrawCounter = 0;
                if (!condensedView.isDisposed()) condensedView.redraw();
            }
            scheduleRedraw();
        });
    }

    private void cleanupAfterFailure() {
        running = false;
        if (capture != null) {
            try { capture.close(); } catch (Exception ignored) {}
            capture = null;
        }
        if (!mainView.isDisposed())      mainView.setBuffer(null);
        if (!condensedView.isDisposed()) condensedView.setBuffer(null);
    }

    private DeviceRef findInputDevice(String name) {
        List<DeviceRef> devices = AudioBackend.instance().listInputDevices();
        for (DeviceRef d : devices) {
            if (name.equals(d.name())) return d;
        }
        return null;
    }
}
