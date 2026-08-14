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
package org.edgo.audio.measure.gui.bus;

import org.edgo.audio.measure.enums.GenChangeCause;

/**
 * Canonical names for every event passed through {@link MessageBus}.
 * Always reference these constants at call sites - never write a string
 * literal - so renaming an event is a single-file change and a typo at
 * the publisher won't silently bypass every subscriber.
 *
 * <p>Each constant documents its payload type so subscribers know what
 * to cast the {@code Consumer} parameter to.
 */
public final class Events {

    /** Fired by the FFT pane when the user picks a new FFT length.
     *  No payload - subscribers read the fresh value from
     *  {@code Preferences.instance().getFftLength()}. */
    public static final String FFT_LENGTH_CHANGED = "fft.length.changed";

    /** Fired once by the Preferences dialog's OK after the working copy is
     *  committed - the audio backend, devices, sample rates or bit depths may
     *  have changed.  No payload - subscribers read the fresh values from
     *  {@code Preferences.instance().current()}.  Used by the numeric fields
     *  whose bounds derive from the audio format (frequency ceilings at
     *  Nyquist, the sweep-points series' sample-rate/2 entry). */
    public static final String AUDIO_FORMAT_CHANGED = "preferences.audioFormat.changed";

    /** Fired once per direction by the Preferences dialog's OK for each
     *  device-provided card whose active full-scale range ACTUALLY changed
     *  (Cancel, or an unchanged range, fires nothing).  Payload:
     *  {@link ActiveRange} - the direction and the newly active range's
     *  label, nothing else.  Device-agnostic: any backend that owns a live range
     *  may subscribe and act on the cards it recognises.  Today's subscriber is
     *  {@code Qa40xRangeController}, which decodes the label and re-ranges the open
     *  device - a live session restart, or the stored range for the next open. */
    public static final String DEVICE_ACTIVE_RANGE_CHANGED = "preferences.device.activeRange.changed";

    /** Fired by the Preferences dialog while a rate or bit-depth combo is being
     *  edited - the user just picked a format for one direction, or a (re)populate
     *  seeded a fresh selection.  Payload: {@link SampleRateChange} (direction +
     *  rate + depth + the edited backend + the resolved card).  Device-agnostic:
     *  any backend or card that constrains its two directions may subscribe and
     *  decide FROM the payload's backend or card whether the change concerns it -
     *  the dialog edits an UNCOMMITTED working copy, so the subscriber gates on the
     *  payload, not live Preferences.  Subscribers today are
     *  {@code Qa40xRateConstraint}, which enforces the QA402/QA403's single shared
     *  reg-9 clock (input rate == output rate) and ignores the depth, and
     *  {@code LoopbackFormatConstraint}, whose backend is one digital format in
     *  both directions and so couples rate AND depth.  Either answers, when the
     *  other direction must follow, with {@link #PREFS_SAMPLE_RATE_SET}. */
    public static final String PREFS_SAMPLE_RATE_CHANGED = "preferences.sampleRate.changed";

    /** The other half of the {@link #PREFS_SAMPLE_RATE_CHANGED} round-trip: fired
     *  by a format-constraint subscriber to tell the Preferences dialog to align
     *  the OTHER direction's combos.  Payload: {@link SampleRateChange} - the
     *  direction to correct, the rate to adopt, the depth to adopt (or
     *  {@link SampleRateChange#NO_BIT_DEPTH} when the constraint leaves the depth
     *  alone), the backend, and the echoed card.  The dialog acts
     *  only while it is still open and the edited backend matches the payload,
     *  selecting the combo item programmatically - a {@code Combo.select} fires no
     *  {@code SWT.Selection}, so the correction does not re-publish
     *  {@link #PREFS_SAMPLE_RATE_CHANGED} and the round-trip ends. */
    public static final String PREFS_SAMPLE_RATE_SET = "preferences.sampleRate.set";

    /** Prefix for pane-title click events.  The full event name is
     *  built by {@link #paneTitleClick(int)} from the ID passed to
     *  {@code PaneTitle}'s constructor.  Subscribers pick their pane
     *  by ID - one subscriber per ID. */
    public static final String PANE_TITLE_CLICK_PREFIX = "paneTitle.click.";

    /** Pane-title IDs.  Each pane picks a distinct value and passes it
     *  to {@code new PaneTitle(...)}; subscribers route by ID. */
    public static final int PANE_ID_GENERATOR = 1;
    public static final int PANE_ID_SCOPE     = 2;
    public static final int PANE_ID_FFT       = 3;
    public static final int PANE_ID_FREQRESP  = 4;

    // capture.acquire / capture.release / capture.batch.available are GONE:
    // the bus is UI-only and carries no device traffic and nothing published
    // from a worker thread - consumers call SharedCapture.instance()
    // .acquire()/.release() directly, and data availability is the ring
    // buffer's own affair.

    /** Request - asks whether the audio generator is currently producing
     *  a signal (either the DDS tone or the WAV file player).
     *  Responder: the generator pane.  Response: {@code Boolean} -
     *  {@code true} when emitting, {@code false} otherwise.  Returns
     *  {@code null} when no responder is registered. */
    public static final String GENERATOR_RUNNING = "generator.running";

    /** Request - the frequencies the generator is actually EMITTING.  Payload:
     *  the analyzer's sample rate ({@code Integer}, used only by the local
     *  answer).  Response: {@code double[]} - tone 1 and tone 2 in Hz, where
     *  {@code 0.0} means "this waveform emits no such tone" (a sweep and the
     *  noise forms have no tone 1; every single-tone form has no tone 2), which
     *  is the same contract the net protocol's {@code emitHz}/{@code emit2Hz}
     *  carry.  Responder: the generator controller, which answers the remote
     *  bench's last pushed generator state when a server drives the DDS and the
     *  local FFT-bin snap otherwise, so every hint, seed and readout follows the
     *  emitted signal instead of re-deriving what it believes was commanded.
     *  Returns {@code null} when no responder is registered. */
    public static final String GENERATOR_EMITTED_HZ = "generator.emitted.hz";

    /** Notification - a generator signal parameter (frequency, amplitude,
     *  waveform, ...) changed.  Payload: {@link GenChangeCause} -
     *  {@code USER_INPUT} when the user moved a control,
     *  {@code FLL_TRIM} when the FFT-side frequency-lock loop applied
     *  a sub-Hz alignment trim.  Subscribers that cache results
     *  derived from the generated signal (e.g. the FFT worker's
     *  per-frame raw-FFT cache + averaging accumulator) MUST treat
     *  {@code USER_INPUT} as "drop everything; restart" but keep their
     *  cache + averaging alive for {@code FLL_TRIM}. */
    public static final String GENERATOR_SIGNAL_CHANGED = "generator.signal.changed";

    /** Notification - the FFT-side frequency-lock loop wants the
     *  generator to adopt a new fundamental frequency without
     *  restarting the FFT averaging accumulator.  Payload: the new
     *  frequency in Hz ({@code Double}).  Subscriber:
     *  {@code GeneratorPane}; on receipt it live-applies the freq to
     *  the DDS and republishes {@link #GENERATOR_SIGNAL_CHANGED} with
     *  cause {@link GenChangeCause#FLL_TRIM} so the FFT worker keeps
     *  its averaging.  Decouples FftView from the GeneratorController
     *  instance owned by GeneratorPane. */
    public static final String GENERATOR_FREQ_TRIM = "generator.freq.trim";

    /** Notification - the FFT-side frequency-lock loop wants the
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

    /** Notification - the FFT-side frequency-lock loops were reset; the
     *  generator must drop any residual FLL trim and return its running
     *  tone(s) to the configured (snapped) frequencies.  Without this, a
     *  loop reset alone leaves the generator at the last published trim:
     *  the fresh loop's "generator = target + correction" assumption is
     *  then wrong by exactly that stale trim and its first correction
     *  overshoots by the same amount - visible as a frequency diff stuck
     *  at ±the stale trim until the generator is restarted.  No payload.
     *  Subscriber: {@code GeneratorPane} (re-applies the snap targets). */
    public static final String GENERATOR_FREQ_TRIM_RESET = "generator.freq.trim.reset";

    /** Notification - the fundamental magnitude(s) moved by more than the
     *  drift threshold between the FIRST result after a generator change
     *  and the result where the frequency lock finished aligning.  The
     *  running average then still contains pre-alignment frames measured
     *  at a depressed level, so the user should reset statistics.
     *  Payload: the largest per-tone delta in dB ({@code Double}).
     *  Subscriber: {@code FftView} (20 s blinking warning banner). */
    public static final String FFT_ALIGN_MAG_DRIFT = "fft.align.mag.drift";

    /** Notification - the FFT view's visible freq / magnitude pan
     *  window changed (mouse-wheel zoom, drag, auto-setup, maximize).
     *  No payload - subscribers (the FFT pane's scrollbars) read the
     *  fresh values from {@code Preferences}. */
    public static final String FFT_RANGE_CHANGED = "fft.range.changed";

    /** Notification - the FFT view's analyser auto-stopped because the
     *  configured stop-after-N count was reached.  Subscribers (the FFT
     *  pane) flip the Record button back off and release the shared
     *  capture.  No payload.  Published on the UI thread. */
    public static final String FFT_RECORDING_AUTO_STOPPED = "fft.recording.auto-stopped";

    /** Request - the FFT tab control wants live recording stopped (the user
     *  is loading a static spectrum that must not be overwritten).  No
     *  payload.  Subscriber: the FFT pane, which owns the Record button and
     *  the shared-capture reference; it flips Record off if it was on.
     *  Published on the UI thread. */
    public static final String FFT_RECORDING_STOP_REQUESTED = "fft.recording.stop-requested";

    /** Request - the FFT tab control's Utility-tab camera button was
     *  clicked.  No payload.  Subscriber: the FFT pane, which owns the
     *  screenshot dialog (it clones the whole pane offscreen to render the
     *  snapshot).  Published on the UI thread. */
    public static final String FFT_SCREENSHOT_REQUESTED = "fft.screenshot.requested";

    /** Notification - a fresh FFT analyser result is ready for display.
     *  Payload: the {@code FftResult} slot (may be {@code null}
     *  when the worker just wants to nudge a repaint without new data,
     *  e.g. after {@code resetStatistics}).  Published on the UI thread
     *  by {@code FftAnalyzerWorker} after each successful analysis;
     *  subscribers MUST handle {@code null}. */
    public static final String FFT_RESULT_AVAILABLE = "fft.result.available";

    /** Notification - the capture the FFT analyser is reading is not continuous:
     *  it re-synced its window (a ring overrun, a dropped-sample gap, or a signal
     *  discontinuity), or a remote bench confessed a loss of its own (the net
     *  protocol's GAP frame).  Payload: the i18n message-key (String) the view
     *  shows as a blinking warning, so ONE event carries the distinct overrun,
     *  discontinuity and bench-loss messages.  Published on the UI thread by
     *  {@code FftAnalyzerWorker} and on a connection's reader thread by the net
     *  glue - subscribers marshal. */
    public static final String FFT_CAPTURE_RESYNC = "fft.capture.resync";

    /** Notification - the generator's file-player thread finished
     *  (user stop, EOF without loop, or playback error).  Subscribers
     *  (the generator pane) reset the play-from LED.  No payload.
     *  Published on the play thread - subscribers must marshal to the
     *  UI thread if they touch widgets. */
    public static final String FILE_PLAY_STOPPED = "filePlay.stopped";

    /** Notification - the generator is uploading a file to a network bench.
     *  Subscribers (the generator pane) put a "being uploaded" notice on
     *  screen.  No payload.  Published on the play thread - subscribers
     *  must marshal to the UI thread if they touch widgets.
     *
     *  <p>ALWAYS paired with {@link #FILE_UPLOAD_FINISHED}, on the success
     *  path and on every failure path alike: a notice that outlives its
     *  transfer is worse than none. */
    public static final String FILE_UPLOAD_STARTED = "fileUpload.started";

    /** Notification - the upload above ended, however it ended (the bench has
     *  the file, or it was refused, or the link died).  Subscribers take the
     *  notice down.  No payload; same threading as
     *  {@link #FILE_UPLOAD_STARTED}. */
    public static final String FILE_UPLOAD_FINISHED = "fileUpload.finished";

    /** Notification - the user clicked the scope's Auto-Setup button.
     *  Subscribers (the scope pane) re-fit the vertical / horizontal
     *  scales to the current signal.  No payload.  Published on the
     *  UI thread (the click handler runs there). */
    public static final String SCOPE_AUTO_SETUP = "scope.autoSetup";

    /** Published by {@code ScopeView} on the UI thread when a SINGLE-mode shot
     *  disarms itself because the awaited trigger fired, so the trigger Start
     *  toggle can pop back out.  No payload. */
    public static final String SCOPE_SINGLE_DISARMED = "scope.single.disarmed";

    /** Published by {@code ScopeController} on the UI thread when it stops a
     *  running live capture programmatically (an open-signal load swapping the
     *  buffer out), and - marshalled - when the capture ended from below (the
     *  measurement worker's reader answered terminally), so the pane can pop
     *  its Record toggle back out.  No payload; the pane consults the
     *  controller's recorded capture-end reason to tell the cases apart.
     *  Subscriber: {@code ScopePane}. */
    public static final String SCOPE_RECORDING_STOPPED = "scope.recording.stopped";

    /** Notification - the FreqResp view's visible freq / magnitude pan
     *  window changed.  No payload - subscribers read fresh values from
     *  {@code Preferences}.  Mirror of {@link #FFT_RANGE_CHANGED} for the
     *  Frequency Response pane. */
    public static final String FREQRESP_RANGE_CHANGED = "freqResp.range.changed";

    /** Notification - the active Frequency Response calibration changed
     *  (loaded from file, cleared, or replaced by the wizard).  No
     *  payload - subscribers read from {@code FreqRespCorrectionStore}. */
    public static final String FREQRESP_CALIBRATION_CHANGED = "freqResp.calibration.changed";

    /** Notification - the FreqResp pane started a measurement.  No
     *  payload.  Other panes (FFT, scope) subscribe to disable their
     *  Record buttons for the duration so the shared capture device
     *  isn't contended. */
    public static final String FREQRESP_MEASUREMENT_STARTED = "freqResp.measurement.started";

    /** Notification - the FreqResp pane finished (or aborted) a
     *  measurement.  No payload.  Counterpart to
     *  {@link #FREQRESP_MEASUREMENT_STARTED}. */
    public static final String FREQRESP_MEASUREMENT_STOPPED = "freqResp.measurement.stopped";

    /** Notification - a fresh sweep measurement is available for display.
     *  Payload: the {@code StereoFreqRespResult} (both channels).
     *  Published by the analyzer worker on its worker thread -
     *  widget-touching subscribers must marshal. */
    public static final String FREQRESP_RESULT_AVAILABLE = "freqResp.result.available";

    /** Notification - a sweep measurement aborted with an error.
     *  Payload: the human-readable failure reason ({@code String}).
     *  Published on the UI thread; always followed by
     *  {@link #FREQRESP_MEASUREMENT_STOPPED}. */
    public static final String FREQRESP_MEASUREMENT_FAILED = "freqResp.measurement.failed";

    /** Notification - a parameter that affects how the compare-mode
     *  curve is derived (e.g. the smoothing window size) changed.
     *  No payload - subscribers (the FreqResp view) re-derive the
     *  smoothed diff, refresh the anchor / min-max table, and
     *  redraw.  Distinct from {@link #FREQRESP_RANGE_CHANGED}
     *  because the visible band itself does not change. */
    public static final String FREQRESP_COMPARE_PARAMS_CHANGED = "freqResp.compare.params.changed";

    /** Notification - the FFT pane's loaded calibration list changed
     *  (file added / removed / replaced / cleared).  No payload -
     *  subscribers read {@code FreqRespCorrectionStore}.  The view re-derives
     *  the calibrated spectrum / harmonic dot positions on next paint. */
    public static final String FFT_CALIBRATION_CHANGED = "fft.calibration.changed";

    /** A {@code .frc} calibration file was just (over)written to disk.  Payload
     *  is the saved {@code String} path.  The FFT and Frequency-Response
     *  calibration tabs each reload any loaded row that references the same
     *  file, so a freshly-saved calibration takes effect immediately without
     *  the user re-browsing for it. */
    public static final String CALIBRATION_FILE_SAVED = "calibration.file.saved";

    // AUDIO_DEVICE_ERROR was deleted with the device-error event machinery:
    // device loss travels bottom-up through the layer that owns it - capture
    // finishes its ring buffer (readers answer terminally and the measuring
    // pane consults + reports), playback throws out of play() (the lane
    // records it and the generator pane polls) - never sideways over the bus.

    /** Notification - the set of REMOTE backends that can be offered at all
     *  changed without anybody asking for it: the session with a Phonalyser
     *  server ended on its own (switched off, network gone), so the entries that
     *  server contributed to the backend picker are no longer selectable.  No
     *  payload - subscribers re-read the list from the remote-backend UI.
     *  Published by the net glue on the connection's own thread, so a subscriber
     *  that touches widgets MUST marshal ({@code Display.asyncExec}).
     *
     *  <p>Subscriber: the Preferences dialog, which re-composes its backend combo
     *  so it can never be left editing a bench that is gone.  The operator-driven
     *  ends (Connect / Disconnect) need no event - the dialog that hosts them
     *  re-composes the combo when the server list closes. */
    public static final String REMOTE_BACKENDS_CHANGED = "remote.backends.changed";

    private Events() {}

    /** Event name for a click on the title bar with {@code id}.  Use
     *  this in {@code subscribe} / {@code publish} calls so the
     *  format-string lives in exactly one place. */
    public static String paneTitleClick(int id) {
        return PANE_TITLE_CLICK_PREFIX + id;
    }
}
