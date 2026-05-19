package org.edgo.audio.measure.gui.scope;

import org.edgo.audio.measure.gui.Preferences;

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
    /** The currently-open shared {@link SignalBuffer} — owned by the
     *  active capture session.  Read by the FFT pane (which doesn't
     *  go through {@link OscilloscopeView#getBuffer()} any more) so
     *  the scope view can hold a frozen snapshot independent of
     *  whatever the audio device is still writing into. */
    private SignalBuffer sharedBuffer;
    /** True while the scope's own Record button is on — drives the redraw
     *  timer and the {@link #isRunning()} accessor.  Independent of the
     *  capture-device lifecycle (which is reference-counted below so the
     *  FFT pane can keep the shared stream alive without flipping this
     *  flag). */
    private volatile boolean scopeLive;
    /** Reference count for the shared audio capture device.  Incremented
     *  by {@link #start()} (scope record on) and {@link #acquireCapture()}
     *  (FFT record on); decremented by the corresponding stop / release.
     *  The audio device is open while this is &gt; 0. */
    private int captureRefCount;
    /** Human-readable message produced by the last failed {@link #start()}, or {@code null} on success / clean state. */
    private String lastStartError;

    public OscilloscopeController(OscilloscopeView mainView, CondensedView condensedView) {
        this.mainView      = mainView;
        this.condensedView = condensedView;
        this.display       = mainView.getDisplay();
    }

    /** Returns {@code true} if the scope's own Record button is on.  Does
     *  NOT reflect the shared capture device — the FFT pane can hold the
     *  device open via {@link #acquireCapture()} while this stays {@code
     *  false}. */
    public boolean isRunning() {
        return scopeLive;
    }

    /** Returns {@code true} if the shared audio device is currently open
     *  (i.e. at least one of scope-record / FFT-record is active). */
    public boolean isCapturing() {
        return captureRefCount > 0;
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
    public synchronized void start() {
        if (scopeLive) return;
        if (!acquireCaptureInternal()) return;  // sets lastStartError on failure
        scopeLive = true;
        // Attach the views to the shared (live) buffer.  Doing this
        // here — not inside acquireCaptureInternal — keeps the scope
        // views detached when only the FFT pane acquired the device.
        // Without that separation the scope painted whatever the
        // audio thread had just written, even though scopeLive=false.
        if (sharedBuffer != null) {
            mainView.setBuffer(sharedBuffer);
            condensedView.setBuffer(sharedBuffer);
        }
        // Measurement compute runs on its own daemon thread so the SWT
        // paint thread doesn't block on the Goertzel scan at high sample
        // rates.  Started after the buffer is wired so the worker sees it.
        mainView.startMeasurementThread();
        scheduleRedraw();
    }

    /** Returns the live shared capture buffer, or {@code null} when
     *  no capture is currently open.  Used by the FFT pane so it can
     *  read fresh samples even when the scope is idle. */
    public SignalBuffer getSharedBuffer() {
        return sharedBuffer;
    }

    /**
     * Opens the shared audio capture device without flipping the scope's
     * Record state.  Used by the FFT pane so a user can record an FFT
     * without the scope's Record-LED lighting up.  Reference counted with
     * {@link #releaseCapture()}.  Returns {@code true} if the device is
     * available afterwards.
     */
    public synchronized boolean acquireCapture() {
        return acquireCaptureInternal();
    }

    /** Releases one reference on the shared capture device.  The device is
     *  closed only when the last reference is released.  Safe to call when
     *  no reference is held. */
    public synchronized void releaseCapture() {
        releaseCaptureInternal();
    }

    /**
     * Opens the audio device if no reference is held yet, otherwise just
     * increments the count.  Returns {@code true} if the device is open
     * after the call.  Stores the failure reason in {@link #lastStartError}
     * so the UI can show a dialog.
     */
    private boolean acquireCaptureInternal() {
        if (captureRefCount > 0) {
            captureRefCount++;
            return true;
        }
        lastStartError = null;

        Preferences prefs = Preferences.instance();
        Preferences.BackendPrefs bp = prefs.current();
        String deviceName = bp.getInputDeviceName();
        if (deviceName == null) {
            lastStartError = "No input device selected. Open Preferences and pick a capture device.";
            log.warn("Capture: {}", lastStartError);
            return false;
        }
        DeviceRef device = findInputDevice(deviceName);
        if (device == null) {
            lastStartError = "The saved input device \"" + deviceName + "\" is no longer available. "
                           + "Reconnect the device or pick another one in Preferences.";
            log.warn("Capture: {}", lastStartError);
            return false;
        }
        final int sampleRate = bp.getInputSampleRate();
        final int bitDepth   = bp.getInputBitDepth();

        try {
            AudioCapture cap = AudioBackend.instance().openCapture(device, sampleRate, bitDepth);
            cap.open();

            SignalBuffer buf = new SignalBuffer(sampleRate, BUFFER_SECONDS);

            final int sampleBytes = bitDepth / 8;
            final int frameSize   = sampleBytes * 2;   // stereo
            // Math identical to the previous StereoSample-based path: per
            // sample we call cap.readSample (which returns offset-binary
            // unsigned), then go through the (uL − midpoint) / midpoint
            // conversion in double precision.  Skipping the StereoSample
            // intermediate still lets us avoid the per-sample allocations,
            // but keeps the proven decode arithmetic intact.
            final long unsignedMask = (bitDepth >= 32) ? 0xFFFFFFFFL : ((1L << bitDepth) - 1);
            final double midpoint   = 1L << (bitDepth - 1);
            // Reusable per-chunk float[] staging buffers — fed by the capture
            // thread, then pushed in one synchronised appendBatch() call so
            // the UI thread isn't fighting the per-sample monitor entries.
            // Grows lazily on first chunk or if a later chunk is bigger.
            final float[][] convBuf = { new float[1], new float[1] };
            cap.setPcmBatchListener((pcm, validBytes) -> {
                int frames = validBytes / frameSize;
                if (convBuf[0].length < frames) {
                    convBuf[0] = new float[frames];
                    convBuf[1] = new float[frames];
                }
                float[] l = convBuf[0];
                float[] r = convBuf[1];
                for (int f = 0, o = 0; f < frames; f++, o += frameSize) {
                    long uL = ((long) cap.readSample(pcm, o)) & unsignedMask;
                    long uR = ((long) cap.readSample(pcm, o + sampleBytes)) & unsignedMask;
                    l[f] = (float) ((uL - midpoint) / midpoint);
                    r[f] = (float) ((uR - midpoint) / midpoint);
                }
                buf.appendBatch(l, r, frames);
            });

            this.capture       = cap;
            this.sharedBuffer  = buf;
            // The scope views are NOT attached here — the FFT pane
            // can acquire the device on its own (without flipping the
            // scope into recording mode), and the scope views must
            // stay detached in that case so they don't paint live
            // samples while scopeLive=false.  scope.start() attaches
            // them when the user genuinely engages the Record button.

            cap.startRecording();
            captureRefCount = 1;
            log.info("Audio capture started: device={}, sampleRate={} Hz, bitDepth={} bits",
                    device.displayName(), sampleRate, bitDepth);
            return true;
        } catch (Exception ex) {
            lastStartError = translateOpenFailure(ex, device, sampleRate, bitDepth);
            log.error("Capture: failed to start — {}", ex.getMessage(), ex);
            cleanupAfterFailure();
            return false;
        }
    }

    private void releaseCaptureInternal() {
        if (captureRefCount <= 0) return;
        captureRefCount--;
        if (captureRefCount > 0) return;
        if (capture != null) {
            try { capture.stopRecording(); } catch (Exception ignored) {}
            try { capture.close();         } catch (Exception ignored) {}
            capture = null;
        }
        sharedBuffer = null;
        log.info("Audio capture stopped (last reference released).");
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
    public synchronized void stop() {
        if (!scopeLive) return;
        scopeLive = false;
        // Stop the measurement worker first so it doesn't try to read from
        // the buffer while capture is being torn down.
        if (!mainView.isDisposed()) mainView.stopMeasurementThread();
        // Take a snapshot of the live buffer so the scope keeps showing
        // the LAST captured frame after stop.  If we kept the views
        // attached to the shared buffer, any continued writes (e.g.
        // because the FFT pane is still recording) would visually
        // resume the trace whenever a paint event fires — which the
        // user perceived as "the scope captured a signal even though
        // record is off" (paint events come from Alt-key focus
        // changes, resize, expose, etc.).
        if (sharedBuffer != null) {
            SignalBuffer frozen = snapshotBuffer(sharedBuffer);
            if (!mainView.isDisposed())      mainView.setBuffer(frozen);
            if (!condensedView.isDisposed()) condensedView.setBuffer(frozen);
        }
        releaseCaptureInternal();
        log.info("Oscilloscope stopped.");
    }

    /** Copies the current contents of {@code live} into a new
     *  standalone {@link SignalBuffer} that no audio thread writes
     *  to — used by {@link #stop()} so the scope view freezes on
     *  the last frame instead of continuing to pick up writes the
     *  FFT pane's still-running capture pushes into the shared one. */
    private SignalBuffer snapshotBuffer(SignalBuffer live) {
        int sr  = live.getSampleRate();
        int cap = live.getCapacity();
        SignalBuffer s = new SignalBuffer(sr, (double) cap / sr);
        float[] l = new float[cap];
        float[] r = new float[cap];
        int n = live.readLatest(cap, l, r);
        s.appendBatch(l, r, n);
        return s;
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
        if (!scopeLive) return;
        display.timerExec(REDRAW_PERIOD_MS, () -> {
            if (!scopeLive) return;
            if (!mainView.isDisposed()) mainView.redraw();
            if (redrawCounter++ >= CONDENSED_DECIMATION) {
                redrawCounter = 0;
                if (!condensedView.isDisposed()) condensedView.redraw();
            }
            scheduleRedraw();
        });
    }

    private void cleanupAfterFailure() {
        captureRefCount = 0;
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
