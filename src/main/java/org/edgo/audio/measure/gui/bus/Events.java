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
    public static final int PANE_ID_FREQRESP  = 4;

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

    /** Notification — the shared capture device just appended a fresh
     *  batch of samples to its {@code SignalBuffer}.  No payload.
     *  Published by {@code SharedCapture} on the audio callback
     *  thread, so subscribers that touch widgets MUST marshal to the
     *  UI thread (typically {@code display.asyncExec(...)}).
     *
     *  <p>Drives the oscilloscope's capture-driven redraw — replacing
     *  a 1 ms polling timer that was flooding the OS message queue
     *  and starving every other {@code asyncExec} in the app
     *  (including the FFT worker's result handoff). */
    public static final String CAPTURE_BATCH_AVAILABLE = "capture.batch.available";

    /** Request — asks whether the audio generator is currently producing
     *  a signal (either the DDS tone or the WAV file player).
     *  Responder: the generator pane.  Response: {@code Boolean} —
     *  {@code true} when emitting, {@code false} otherwise.  Returns
     *  {@code null} when no responder is registered. */
    public static final String GENERATOR_RUNNING = "generator.running";

    /** Notification — a generator signal parameter (frequency, amplitude,
     *  waveform, …) changed.  Payload: {@link GenChangeCause} —
     *  {@code USER_INPUT} when the user moved a control,
     *  {@code FLL_TRIM} when the FFT-side frequency-lock loop applied
     *  a sub-Hz alignment trim.  Subscribers that cache results
     *  derived from the generated signal (e.g. the FFT worker's
     *  per-frame raw-FFT cache + averaging accumulator) MUST treat
     *  {@code USER_INPUT} as "drop everything; restart" but keep their
     *  cache + averaging alive for {@code FLL_TRIM}. */
    public static final String GENERATOR_SIGNAL_CHANGED = "generator.signal.changed";

    /** Notification — the FFT-side frequency-lock loop wants the
     *  generator to adopt a new fundamental frequency without
     *  restarting the FFT averaging accumulator.  Payload: the new
     *  frequency in Hz ({@code Double}).  Subscriber:
     *  {@code GeneratorPane}; on receipt it live-applies the freq to
     *  the DDS and republishes {@link #GENERATOR_SIGNAL_CHANGED} with
     *  cause {@link GenChangeCause#FLL_TRIM} so the FFT worker keeps
     *  its averaging.  Decouples FftView from the GeneratorController
     *  instance owned by GeneratorPane. */
    public static final String GENERATOR_FREQ_TRIM = "generator.freq.trim";

    /** Notification — the FFT-side frequency-lock loop wants the
     *  generator's <strong>second</strong> tone of a DUAL_TONE
     *  waveform to adopt a new frequency.  Payload: the new
     *  frequency in Hz ({@code Double}).  Mirror of
     *  {@link #GENERATOR_FREQ_TRIM} for tone 2.  Subscriber:
     *  {@code GeneratorPane}; on receipt it live-applies the freq via
     *  {@code setDualToneFrequency2} and republishes
     *  {@link #GENERATOR_SIGNAL_CHANGED} with cause
     *  {@link GenChangeCause#FLL_TRIM} so the FFT worker keeps its
     *  averaging accumulator alive. */
    public static final String GENERATOR_FREQ_TRIM_2 = "generator.freq.trim.2";

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

    /** Notification — a fresh FFT analyser result is ready for display.
     *  Payload: the {@code FftResult} slot (may be {@code null}
     *  when the worker just wants to nudge a repaint without new data,
     *  e.g. after {@code resetStatistics}).  Published on the UI thread
     *  by {@code FftAnalyzerWorker} after each successful analysis;
     *  subscribers MUST handle {@code null}. */
    public static final String FFT_RESULT_AVAILABLE = "fft.result.available";

    /** Notification — the FFT analyser re-synced its capture window (a ring
     *  overrun, a dropped-sample gap, or a signal discontinuity); the running
     *  average is kept.  Payload: the i18n message-key (String) the view shows
     *  as a blinking warning, so ONE event carries the distinct overrun vs
     *  discontinuity messages.  Published on the UI thread by
     *  {@code FftAnalyzerWorker}. */
    public static final String FFT_CAPTURE_RESYNC = "fft.capture.resync";

    /** Notification — the generator's file-player thread finished
     *  (user stop, EOF without loop, or playback error).  Subscribers
     *  (the generator pane) reset the play-from LED.  No payload.
     *  Published on the play thread — subscribers must marshal to the
     *  UI thread if they touch widgets. */
    public static final String FILE_PLAY_STOPPED = "filePlay.stopped";

    /** Notification — the user clicked the scope's Auto-Setup button.
     *  Subscribers (the scope pane) re-fit the vertical / horizontal
     *  scales to the current signal.  No payload.  Published on the
     *  UI thread (the click handler runs there). */
    public static final String SCOPE_AUTO_SETUP = "scope.autoSetup";

    /** Notification — the FreqResp view's visible freq / magnitude pan
     *  window changed.  No payload — subscribers read fresh values from
     *  {@code Preferences}.  Mirror of {@link #FFT_RANGE_CHANGED} for the
     *  Frequency Response pane. */
    public static final String FREQRESP_RANGE_CHANGED = "freqresp.range.changed";

    /** Notification — the active Frequency Response calibration changed
     *  (loaded from file, cleared, or replaced by the wizard).  No
     *  payload — subscribers read from {@code FreqRespCalibrationStore}. */
    public static final String FREQRESP_CALIBRATION_CHANGED = "freqresp.calibration.changed";

    /** Notification — the FreqResp pane started a measurement.  No
     *  payload.  Other panes (FFT, scope) subscribe to disable their
     *  Record buttons for the duration so the shared capture device
     *  isn't contended. */
    public static final String FREQRESP_MEASUREMENT_STARTED = "freqresp.measurement.started";

    /** Notification — the FreqResp pane finished (or aborted) a
     *  measurement.  No payload.  Counterpart to
     *  {@link #FREQRESP_MEASUREMENT_STARTED}. */
    public static final String FREQRESP_MEASUREMENT_STOPPED = "freqresp.measurement.stopped";

    /** Notification — a fresh {@code FreqRespResult} is available for
     *  display.  Payload: the {@code FreqRespResult} itself.  Published
     *  by the analyzer worker on the UI thread. */
    public static final String FREQRESP_RESULT_AVAILABLE = "freqresp.result.available";

    /** Notification — a parameter that affects how the compare-mode
     *  curve is derived (e.g. the smoothing window size) changed.
     *  No payload — subscribers (the FreqResp view) re-derive the
     *  smoothed diff, refresh the anchor / min-max table, and
     *  redraw.  Distinct from {@link #FREQRESP_RANGE_CHANGED}
     *  because the visible band itself does not change. */
    public static final String FREQRESP_COMPARE_PARAMS_CHANGED = "freqresp.compare.params.changed";

    /** Notification — the FFT pane's loaded calibration list changed
     *  (file added / removed / replaced / cleared).  No payload —
     *  subscribers read {@code FftCalibrationStore}.  The view re-derives
     *  the calibrated spectrum / harmonic dot positions on next paint. */
    public static final String FFT_CALIBRATION_CHANGED = "fft.calibration.changed";
}
