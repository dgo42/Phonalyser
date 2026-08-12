/*
 * Phonalyser web - canonical names for every event passed through the MessageBus.
 *
 * Faithful port of org.edgo.audio.measure.gui.bus.Events. Always reference these constants at call
 * sites - never a string literal - so renaming an event is a single-file change and a typo at the
 * publisher won't silently bypass every subscriber. Each constant documents its payload. The
 * string values match the Java baseline. (The Java "marshal to the UI thread" caveats don't apply:
 * JS is single-threaded; workers already marshal via postMessage.)
 * GNU Affero General Public License v3 or later.
 */

export const Events = Object.freeze({
  /** Fired by the FFT pane when the user picks a new FFT length. No payload - subscribers read the
   *  fresh value from Preferences.instance().fftLength. */
  FFT_LENGTH_CHANGED: 'fft.length.changed',

  /** Fired once by the Preferences dialog's OK after the working copy is committed - the audio
   *  backend, devices, sample rates or bit depths may have changed. No payload - subscribers read
   *  the fresh values from Preferences. Used by numeric fields whose bounds derive from the audio
   *  format (frequency ceilings at Nyquist, the sweep-points series' sample-rate/2 entry). */
  AUDIO_FORMAT_CHANGED: 'preferences.audioFormat.changed',

  /** Fired once per direction by the Preferences dialog's OK for each device-provided card whose
   *  active full-scale range ACTUALLY changed (Cancel, or an unchanged range, fires nothing).
   *  Payload: ActiveRange - { input: boolean, activeRangeLabel: string }, the direction and the
   *  newly active range's label, nothing else. Device-agnostic: any backend that owns a live range
   *  may subscribe and act on the cards it recognises. Today's subscriber is Qa40xRangeController,
   *  which decodes the label and re-ranges the open device - a live session restart, or the stored
   *  range for the next open. */
  DEVICE_ACTIVE_RANGE_CHANGED: 'preferences.device.activeRange.changed',

  /** Fired by the Preferences dialog while a rate combo is being edited - the user just picked a
   *  sample rate for one direction, or a (re)populate seeded a fresh selection. Payload:
   *  SampleRateChange - { input: boolean, sampleRateHz: number, backend: string, card: ?string }
   *  (direction + rate + the edited backend + the resolved card). Device-agnostic: any backend or
   *  card that constrains its two rates may subscribe and decide FROM the payload's backend or card
   *  whether the change concerns it - the dialog edits an UNCOMMITTED working copy, so the
   *  subscriber gates on the payload, not live Preferences. Today's subscriber is
   *  Qa40xRateConstraint, which enforces the QA402/QA403's single shared reg-9 clock (input rate ==
   *  output rate) and answers, when the other direction must follow, with PREFS_SAMPLE_RATE_SET. */
  PREFS_SAMPLE_RATE_CHANGED: 'preferences.sampleRate.changed',

  /** The other half of the PREFS_SAMPLE_RATE_CHANGED round-trip: fired by a rate-constraint
   *  subscriber to tell the Preferences dialog to align the OTHER direction's rate combo. Payload:
   *  SampleRateChange - the direction to correct, the rate to adopt, the backend, and the echoed
   *  card. The dialog acts only while it is still open and the edited backend matches the payload,
   *  selecting the combo item programmatically - setting a <select>'s value fires no change event
   *  (as SWT's Combo.select fires no Selection), so the correction does not re-publish
   *  PREFS_SAMPLE_RATE_CHANGED and the round-trip ends. */
  PREFS_SAMPLE_RATE_SET: 'preferences.sampleRate.set',

  /** Prefix for pane-title click events. The full name is built by paneTitleClick(id). Subscribers
   *  pick their pane by ID - one subscriber per ID. */
  PANE_TITLE_CLICK_PREFIX: 'paneTitle.click.',

  // capture.acquire / capture.release are GONE, as in the Java baseline: consumers call the
  // shared capture's acquire()/release() directly and hold the reference they took.
  // capture.batch.available below is the deliberate divergence and STAYS: Java wakes its
  // consumers on the ring buffer's own monitor, and a browser has no blocking threads to wake -
  // this event is the web's equivalent of that notification.

  /** Notification - the shared capture device just appended a fresh batch of samples to its
   *  SignalBuffer. No payload. Drives the oscilloscope's capture-driven redraw. */
  CAPTURE_BATCH_AVAILABLE: 'capture.batch.available',

  /** Request - asks whether the audio generator is currently producing a signal (DDS tone or WAV
   *  file player). Responder: the generator pane. Response: boolean (null when no responder). */
  GENERATOR_RUNNING: 'generator.running',

  /** Request - the frequencies the generator is actually EMITTING. Payload: the analyzer's
   *  sample rate (number, used only by the local answer). Response: [tone1Hz, tone2Hz], where
   *  0.0 means "this waveform emits no such tone" (a sweep and the noise forms have no tone 1;
   *  every single-tone form has no tone 2). Responder: the generator controller - so every
   *  hint, seed and readout follows the emitted signal instead of re-deriving what it believes
   *  was commanded. null when no responder is registered, and a consumer must then draw no
   *  hint at all rather than fall back to its own derivation. */
  GENERATOR_EMITTED_HZ: 'generator.emitted.hz',

  /** Notification - a generator signal parameter (frequency, amplitude, waveform, ...) changed.
   *  Payload: GenChangeCause - USER_INPUT when the user moved a control, FLL_TRIM when the FFT-side
   *  frequency-lock loop applied a sub-Hz alignment trim. Subscribers caching results derived from
   *  the generated signal MUST treat USER_INPUT as "drop everything; restart" but keep their cache
   *  + averaging alive for FLL_TRIM. */
  GENERATOR_SIGNAL_CHANGED: 'generator.signal.changed',

  /** Notification - the FFT-side frequency-lock loop wants the generator to adopt a new fundamental
   *  frequency WITHOUT restarting the FFT averaging accumulator. Payload: new frequency in Hz
   *  (number). Subscriber: generator pane; it live-applies the freq and republishes
   *  GENERATOR_SIGNAL_CHANGED with cause FLL_TRIM so the FFT worker keeps its averaging. */
  GENERATOR_FREQ_TRIM: 'generator.freq.trim',

  /** Notification - as GENERATOR_FREQ_TRIM, for the SECOND tone of a DUAL_TONE waveform.
   *  Payload: new frequency in Hz (number). */
  GENERATOR_FREQ_TRIM_2: 'generator.freq.trim.2',

  /** Notification - the FFT-side frequency-lock loops were reset; the generator must drop any
   *  residual FLL trim and return its running tone(s) to the configured (snapped) frequencies.
   *  No payload. Subscriber: generator pane (re-applies the snap targets). */
  GENERATOR_FREQ_TRIM_RESET: 'generator.freq.trim.reset',

  /** Notification - the fundamental magnitude(s) moved by more than the drift threshold between the
   *  first result after a generator change and the result where the frequency lock finished
   *  aligning; the running average still contains pre-alignment frames at a depressed level, so the
   *  user should reset statistics. Payload: largest per-tone delta in dB (number). Subscriber:
   *  FFT view (20 s blinking warning banner). */
  FFT_ALIGN_MAG_DRIFT: 'fft.align.mag.drift',

  /** Notification - the FFT view's visible freq/magnitude pan window changed (wheel zoom, drag,
   *  auto-setup, maximize). No payload - subscribers (the FFT pane's scrollbars) read Preferences. */
  FFT_RANGE_CHANGED: 'fft.range.changed',

  /** Notification - the FFT analyser auto-stopped because the configured stop-after-N count was
   *  reached. Subscribers (the FFT pane) flip Record back off and release the shared capture.
   *  No payload. */
  FFT_RECORDING_AUTO_STOPPED: 'fft.recording.auto-stopped',

  /** Request - the FFT tab control wants live recording stopped (the user is loading a static
   *  spectrum that must not be overwritten). No payload. Subscriber: the FFT pane (owns Record +
   *  the shared-capture reference); flips Record off if it was on. */
  FFT_RECORDING_STOP_REQUESTED: 'fft.recording.stop-requested',

  /** Request - the FFT tab control's Utility-tab camera button was clicked. No payload.
   *  Subscriber: the FFT pane (owns the screenshot dialog). */
  FFT_SCREENSHOT_REQUESTED: 'fft.screenshot.requested',

  /** Notification - a fresh FFT analyser result is ready for display. Payload: the FftResult (may
   *  be null when the worker just wants a repaint without new data, e.g. after resetStatistics).
   *  Subscribers MUST handle null. */
  FFT_RESULT_AVAILABLE: 'fft.result.available',

  /** Notification - the FFT analyser re-synced its capture window (ring overrun, dropped-sample
   *  gap, or signal discontinuity); the running average is kept. Payload: the i18n message-key
   *  (string) the view shows as a blinking warning. */
  FFT_CAPTURE_RESYNC: 'fft.capture.resync',

  /** Notification - the backends a connected Phonalyser server offers changed: a session opened
   *  or ended, or the bench sent ev.devices.changed. The Preferences backend combo rebuilds from
   *  it. Both baselines carry this event under this name and value; the PAYLOAD differs. Here it
   *  is the new entry list (possibly empty) and the handler branches on an empty one - a session
   *  that is gone takes the bench's transient card with it. Java publishes no payload and its
   *  subscriber re-reads the list from the remote-backend UI.
   *
   *  <p>The web also announces MORE moments than Java does: Java re-composes the combo by a
   *  direct call when its server-list dialog closes, whereas a web session can open or end from
   *  elsewhere (an auto-connect, a page served by a server), so every change is announced instead
   *  of assumed. */
  REMOTE_BACKENDS_CHANGED: 'remote.backends.changed',

  /** Notification - the FFT pane's loaded calibration list changed (file added/removed/replaced/
   *  cleared). No payload - subscribers read the correction store; the view re-derives the
   *  calibrated spectrum / harmonic dot positions on next paint. */
  FFT_CALIBRATION_CHANGED: 'fft.calibration.changed',

  /** Notification - the generator's file-player finished (user stop, EOF without loop, or error).
   *  Subscribers (the generator pane) reset the play-from LED. No payload. */
  FILE_PLAY_STOPPED: 'filePlay.stopped',

  /** Notification - the user clicked the scope's Auto-Setup button. Subscribers (the scope pane)
   *  re-fit the vertical/horizontal scales to the current signal. No payload. */
  SCOPE_AUTO_SETUP: 'scope.autoSetup',

  /** Notification - a SINGLE-mode shot disarmed itself because the awaited trigger fired, so the
   *  trigger Start toggle can pop back out. No payload. */
  SCOPE_SINGLE_DISARMED: 'scope.single.disarmed',

  /** Notification - a running live scope capture was stopped programmatically (an open-signal
   *  load swapping the buffer out), so the pane can pop its Record toggle back out. No payload.
   *  (Java: published by ScopeController.openSignalFile; subscriber ScopePane. The web pane
   *  syncs its own LED inline in stopCaptureForFileLoad and publishes this for parity.) */
  SCOPE_RECORDING_STOPPED: 'scope.recording.stopped',

  /** Notification - the FreqResp view's visible freq/magnitude pan window changed. No payload -
   *  subscribers read Preferences. Mirror of FFT_RANGE_CHANGED for the Frequency Response pane. */
  FREQRESP_RANGE_CHANGED: 'freqResp.range.changed',

  /** Notification - the active Frequency Response calibration changed (loaded, cleared, or replaced
   *  by the wizard). No payload - subscribers read the correction store. */
  FREQRESP_CALIBRATION_CHANGED: 'freqResp.calibration.changed',

  /** Notification - the FreqResp pane started a measurement. No payload. Other panes (FFT, scope)
   *  disable their Record buttons for the duration so the shared capture isn't contended. */
  FREQRESP_MEASUREMENT_STARTED: 'freqResp.measurement.started',

  /** Notification - the FreqResp pane finished (or aborted) a measurement. No payload.
   *  Counterpart to FREQRESP_MEASUREMENT_STARTED. */
  FREQRESP_MEASUREMENT_STOPPED: 'freqResp.measurement.stopped',

  /** Notification - a fresh sweep measurement is available for display. Payload: the
   *  StereoFreqRespResult (both channels). */
  FREQRESP_RESULT_AVAILABLE: 'freqResp.result.available',

  /** Notification - a sweep measurement aborted with an error. Payload: the human-readable failure
   *  reason (string). Always followed by FREQRESP_MEASUREMENT_STOPPED. */
  FREQRESP_MEASUREMENT_FAILED: 'freqResp.measurement.failed',

  /** Notification - a parameter affecting how the compare-mode curve is derived (e.g. smoothing
   *  window size) changed. No payload - subscribers (the FreqResp view) re-derive the smoothed
   *  diff, refresh the anchor / min-max table, and redraw. Distinct from FREQRESP_RANGE_CHANGED
   *  because the visible band itself does not change. */
  FREQRESP_COMPARE_PARAMS_CHANGED: 'freqResp.compare.params.changed',

  /** A .frc calibration file was just (over)written to disk. Payload: the saved path (string). The
   *  FFT and Frequency-Response calibration tabs each reload any loaded row referencing the same
   *  file, so a freshly-saved calibration takes effect immediately. */
  CALIBRATION_FILE_SAVED: 'calibration.file.saved',

  /** Notification - the live audio device could not be USED: a device-open rejection, or a lane
   *  that ended under us and whose owning pane has composed the operator's message.
   *
   *  Payload: { direction: 'input'|'output'|null, reason: string, message: string } where
   *  `reason` is a DeviceFailureReason / CaptureEndReason constant NAME (machine-readable, the
   *  same vocabulary the wire's reason field carries) and `message` is the LOCALIZED sentence the
   *  publisher composed - it knows which operation failed, so it owns the wording. Raw driver /
   *  DOMException text is deliberately NOT in the payload: it says the operator nothing and goes
   *  to the log at the failure site.
   *
   *  Subscriber: the shell, which shows the one alert modal; a payload without `message` falls
   *  back to the generic per-direction text. The desktop has no such event - it opens
   *  Dialogs.error on the failing pane's own shell; the web's single alert surface lives in the
   *  shell, so the panes reach it through here. */
  AUDIO_DEVICE_ERROR: 'audio.device.error',
});

/**
 * Payload of GENERATOR_SIGNAL_CHANGED (faithful port of enums/GenChangeCause). Lets subscribers
 * tell a user-initiated generator change from a closed-loop FLL trim: USER_INPUT means "the signal
 * really changed - drop everything; restart", while FLL_TRIM is a sub-Hz alignment tweak that MUST
 * keep any averaging / accumulated statistics alive.
 */
export const GenChangeCause = Object.freeze({ USER_INPUT: 'USER_INPUT', FLL_TRIM: 'FLL_TRIM' });

/** Pane-title IDs. Each pane passes a distinct value to its title bar; subscribers route by ID. */
export const PaneId = Object.freeze({ GENERATOR: 1, SCOPE: 2, FFT: 3, FREQRESP: 4 });

/** Event name for a click on the title bar with `id` - so the format lives in exactly one place. */
export function paneTitleClick(id) { return Events.PANE_TITLE_CLICK_PREFIX + id; }
