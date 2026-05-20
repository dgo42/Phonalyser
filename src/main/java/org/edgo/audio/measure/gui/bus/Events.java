package org.edgo.audio.measure.gui.bus;

/**
 * Canonical names for every event passed through {@link MessageBus}.
 * Always reference these constants at call sites — never write a string
 * literal — so renaming an event is a single-file change and a typo at
 * the publisher won't silently bypass every subscriber.
 *
 * <p>Each constant documents its payload type so subscribers know what
 * to cast the {@code Consumer} parameter to.
 */
public final class Events {

    private Events() {}

    /** Fired by the FFT pane when the user picks a new FFT length.
     *  No payload — subscribers read the fresh value from
     *  {@code Preferences.instance().getFftLength()}. */
    public static final String FFT_LENGTH_CHANGED = "fft.length.changed";

    /** Prefix for pane-title click events.  The full event name is
     *  built by {@link #paneTitleClick(int)} from the ID passed to
     *  {@code PaneTitle}'s constructor.  Subscribers pick their pane
     *  by ID — one subscriber per ID. */
    public static final String PANE_TITLE_CLICK_PREFIX = "paneTitle.click.";

    /** Event name for a click on the title bar with {@code id}.  Use
     *  this in {@code subscribe} / {@code publish} calls so the
     *  format-string lives in exactly one place. */
    public static String paneTitleClick(int id) {
        return PANE_TITLE_CLICK_PREFIX + id;
    }

    /** Pane-title IDs.  Each pane picks a distinct value and passes it
     *  to {@code new PaneTitle(...)}; subscribers route by ID. */
    public static final int PANE_ID_GENERATOR = 1;
    public static final int PANE_ID_SCOPE     = 2;
    public static final int PANE_ID_FFT       = 3;

    /** Request — opens (or refcount-increments) the shared input capture
     *  device.  Responder: the {@code SharedCapture} singleton.
     *  Response: the live {@code SignalBuffer} on success, or
     *  {@code null} on failure (read
     *  {@code SharedCapture.getLastStartError()} for a human-readable
     *  reason). */
    public static final String CAPTURE_ACQUIRE = "capture.acquire";

    /** Notification — releases one reference on the shared capture
     *  device.  The device is closed only when the last consumer has
     *  released. */
    public static final String CAPTURE_RELEASE = "capture.release";

    /** Request — asks whether the audio generator is currently producing
     *  a signal (either the DDS tone or the WAV file player).
     *  Responder: the generator pane.  Response: {@code Boolean} —
     *  {@code true} when emitting, {@code false} otherwise.  Returns
     *  {@code null} when no responder is registered. */
    public static final String GENERATOR_RUNNING = "generator.running";

    /** Notification — the FFT view's visible freq / magnitude pan
     *  window changed (mouse-wheel zoom, drag, auto-setup, maximize).
     *  No payload — subscribers (the FFT pane's scrollbars) read the
     *  fresh values from {@code Preferences}. */
    public static final String FFT_RANGE_CHANGED = "fft.range.changed";

    /** Notification — the FFT view's analyser auto-stopped because the
     *  configured stop-after-N count was reached.  Subscribers (the FFT
     *  pane) flip the Record button back off and release the shared
     *  capture.  No payload.  Published on the UI thread. */
    public static final String FFT_RECORDING_AUTO_STOPPED = "fft.recording.auto-stopped";
}
