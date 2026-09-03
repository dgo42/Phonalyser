/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.gui.freqresp.NotchSweepEngine, plus
// the PURE loop geometry of gui.freqresp.TuneNotchWizardDialog (the power-of-two
// loop period, the 25 ms seam fades, the sweep-band widening) exported as
// stateless functions so the Tune-notch wizard UI can drive them. The
// deepest-null and target-attenuation readouts are the wizard's own, read off
// the drawn curve (see the note above the class).
//
// Self-contained CONTINUOUS play+capture for the Tune-notch wizard's live
// sweep loop: start() configures the DDS worklet with a LOOPING Farina
// log-sweep (Hann-faded at each cycle seam) and acquires the shared capture
// ring; the wizard then grabs the most recent one-period window whenever it
// wants a fresh result (≈10 updates/s) - no per-sweep device open/close and no
// real-time recording wait.
//
// No alignment of the grabbed window to the sweep-cycle start is needed - but
// ONLY because the wizard makes the loop period a power of two AT THE CAPTURE
// CLOCK, so computeFromLogSweep's nextPow2(period) FFT is exactly one period: a
// CIRCULAR transform. A window grabbed at any loop phase is then just a
// circular shift, which leaves the magnitude |H(f)| unchanged. (With a
// non-power-of-two period the FFT would be zero-padded/linear, an unaligned
// window would wrap the period across the buffer, and every bin would corrupt
// into a random spike.)
//
// THE TWO CONVERTERS RUN THEIR OWN CONFIGURED FORMATS. The DAC is opened on the
// output rate, the ADC delivers the input rate, and the two need not be equal -
// so the loop is described as a DURATION and each side counts it in its own
// samples: the period is the power of two at the capture clock, the emitted
// loop is that same duration in output samples, and the deconvolution reference
// is rendered at the capture clock because that is the clock the window it
// divides came off.
//
// Intended web adaptations (the Java engine owns JavaSound lines + its own
// ring + a MAX_PRIORITY generator thread; the web has neither raw lines nor
// threads):
//   - Playback is THIS engine's own lane, exactly as Java's NotchSweepEngine drives its own
//     GeneratorLane: start() opens a PlaybackSink of the active backend (the injected
//     openPlayback factory - the same seam GeneratorController opens its tone through), puts a
//     SILENT looping Farina sweep on it, and un-mutes at the real amplitude through the sink's
//     control port once the capture is up; close() closes the lane. The kernel's looping
//     logSweepNext IS the port of the Java generator's, so the seam-fade semantics are
//     identical. The generator pane's own lane and preferences are never touched.
//   - Capture is the injected shared capture (acquire/release): this engine takes its own
//     reader over the ONE ring, as Java's NotchSweepEngine acquires SharedCapture.instance().
//     The live consumers have released the device before the wizard starts, so the session has
//     it to itself. The 22 s SignalBuffer ring stands in for Java's private RING_PERIODS ring;
//     latestPeriod() maps onto readLatest() with a session-start anchor so pre-session audio
//     can never be served. The capture worklet delivers normalised [-1, +1] floats, so
//     Java's raw-ADC-code/halfRange conversion (bitDepth/ditherBits) does not exist here - an
//     intended web divergence (no bit-depth selector).
//   - sweepRef() re-renders the reference locally (renderLogSweep, the port of
//     SignalGenerator#renderLogSweep the Java engine builds its own reference
//     with) instead of taking the generator's live buffer back; on setBand() it
//     re-renders so the reference tracks the played band exactly like Java's
//     rebuilt buffer. It is rendered at the CAPTURE clock and with the capture
//     count, because it divides a window that came off the ADC.
//   - Bit depth and dither do not exist on either side here (Web Audio is
//     float32 throughout), so Java's playbackBitDepth / ditherBits constructor
//     arguments have no web counterpart - the rate pair is the whole format.

import { GenSignalForm } from '../enums/gen-signal-form.js';
import { nextPow2 } from '../dsp/mathutil.js';
import { renderLogSweep } from './farina-sweep.js';
import { debug } from '../util/debug.js';

// --- Sweep timing (TuneNotchWizardDialog constants) --------------------------
/** Sweep duration (s) the power-of-two loop period is derived from.
 *  Mirrors TuneNotchWizardDialog.SWEEP_DURATION_SEC. */
export const SWEEP_DURATION_SEC = 0.26;
/** Lead-in (s) - the continuous stream uses ZERO lead-in (the loop never
 *  re-emits silence); kept only as the reported sweep parameter.
 *  Mirrors TuneNotchWizardDialog.SWEEP_LEAD_IN_SEC. */
export const SWEEP_LEAD_IN_SEC = 0.05;
/** Per-side Hann fade length (seconds) applied to BOTH the played loop seam
 *  and the deconvolution reference. 25 ms was found on the scope to best
 *  suppress the loop-seam spikes; it overrides the shared 5% default.
 *  Mirrors TuneNotchWizardDialog.FADE_SEC. */
export const FADE_SEC = 0.025;
/** Safety multiplier on the sweep-band margin (see {@link sweepBand}). The
 *  Hann fades drive the sweep energy |X|->0 at the swept ends, so the displayed
 *  band must sit well inside the fully-excited middle; >1 keeps the displayed
 *  edges clear of the fade and the log sweep's 1/f roll-off (which otherwise
 *  flatten the trace at the edges).
 *  Mirrors TuneNotchWizardDialog.SWEEP_MARGIN_SAFETY. */
export const SWEEP_MARGIN_SAFETY = 2.5;

/**
 * The loop period in CAPTURE samples: the power of two NEAREST to
 * SWEEP_DURATION_SEC · sampleRate. The period MUST be a power of two IN CAPTURE
 * SAMPLES - that is the stream the window is grabbed from and the transform is
 * run on; {@link NotchSweepEngine#playbackSamples} converts the same duration to
 * the DAC's own sample count. computeFromLogSweep sizes its FFT to
 * nextPow2(period), so a power-of-two
 * period makes that FFT exactly ONE period - a CIRCULAR transform, not a
 * zero-padded linear one. Only then is a window grabbed at an arbitrary loop
 * phase just a circular shift that leaves |H(f)| unchanged; a linear FFT of an
 * unaligned window wraps the period and corrupts every bin into a spike.
 * Faithful port of the loop-period derivation in
 * TuneNotchWizardDialog#sweepLoop.
 *
 * @param {number} sampleRate capture sample rate (Hz)
 * @returns {number} loop period in samples (a power of two)
 */
export function powerOfTwoSweepSamples(sampleRate) {
  const rawSamples = Math.round(SWEEP_DURATION_SEC * sampleRate);
  // Integer.highestOneBit(max(2, rawSamples)).
  const v = Math.max(2, rawSamples);
  let loPow2 = 1;
  while ((loPow2 << 1) > 0 && (loPow2 << 1) <= v) loPow2 <<= 1;
  return (rawSamples - loPow2 < (loPow2 << 1) - rawSamples) ? loPow2 : loPow2 << 1;
}

/**
 * Per-side seam-fade length in samples ({@link FADE_SEC} at the given rate) -
 * the SAME value must drive the played loop seam and the deconvolution
 * reference. Mirrors {@code fadeSamples} in TuneNotchWizardDialog#sweepLoop.
 *
 * @param {number} sampleRate capture sample rate (Hz)
 * @returns {number} per-side Hann fade length in samples
 */
export function notchFadeSamples(sampleRate) {
  return Math.round(FADE_SEC * sampleRate);
}

/**
 * The DDS sweep band: WIDER than the displayed [start, stop] so the Hann fades
 * - which zero the sweep energy |X| over FADE_SEC / SWEEP_DURATION_SEC of the
 * log range at each swept end - fall OUTSIDE the displayed band, with
 * {@link SWEEP_MARGIN_SAFETY} headroom for the log sweep's 1/f roll-off (so
 * the displayed edges aren't flattened). The view shows only [start, stop];
 * the widened ends are cut. Faithful port of TuneNotchWizardDialog#sweepBand.
 *
 * @param {number} startHz displayed band start (Hz)
 * @param {number} stopHz  displayed band stop (Hz)
 * @returns {[number, number]} the widened [f0, f1] the sweep actually plays
 */
export function sweepBand(startHz, stopHz) {
  const ff = FADE_SEC / SWEEP_DURATION_SEC;
  const marginFrac = SWEEP_MARGIN_SAFETY * ff / (1.0 - 2.0 * ff);
  const m = Math.log(stopHz / startHz) * marginFrac;
  return [startHz * Math.exp(-m), stopHz * Math.exp(m)];
}

// There is deliberately no findDeepestNotch (a sub-bin parabolic depth fit) and no dbAtFrequency
// (a bin interpolation): both readouts come from the DRAWN curve - the wizard asks
// FreqRespView.drawnMinimum / drawnDb, which is the only thing that agrees with what the operator
// sees. A deep null is a V, not a parabola, so the fit reported a depth the curve never reaches.

/**
 * Self-contained continuous play+capture session for the Tune-notch wizard's
 * live sweep loop. Faithful port of gui.freqresp.NotchSweepEngine (see the
 * module header for the documented web adaptations of its JavaSound half).
 */
export class NotchSweepEngine {
  /**
   * @param {Object} deps injected collaborators (the web stand-ins for Java's
   *   DeviceRef pair; the capture bit depth is an intended web divergence)
   * @param {import('../audio/shared-capture.js').SharedCapture} deps.capture
   *   the shared capture (this session's reader is acquired on start, released on close)
   * @param {(deps: Object) => Object} deps.openPlayback the active backend's PlaybackSink
   *   factory (AudioBackend.openPlayback) - this engine opens and closes its OWN lane on it,
   *   as Java's engine drives its own GeneratorLane
   * @param {?string} deps.outputDeviceId the configured output device the lane opens on
   * @param {number} deps.ditherBits the configured generator dither, fixed at the lane's open
   * @param {number} deps.captureRateHz the ADC's configured rate (Hz): the
   *   clock every captured window is counted in, the clock the deconvolution
   *   reads its bins on, and the clock the reference is rendered at
   * @param {number} deps.playbackRateHz the DAC's configured rate (Hz) - the
   *   clock the emitted loop is counted in, independent of the ADC's
   */
  constructor({ capture, openPlayback, outputDeviceId, ditherBits, captureRateHz, playbackRateHz }) {
    this._capture = capture;                 // the shared capture (this session's own reader over it)
    this._openPlayback = openPlayback;       // (deps) => the active backend's PlaybackSink
    this._outputDeviceId = outputDeviceId;   // the configured output device the lane opens on
    this._ditherBits = ditherBits;           // the configured generator dither, fixed at the open
    this._captureRateHz = captureRateHz;
    this._playbackRateHz = playbackRateHz;
    this._sink = null;                       // this session's own playback lane, null while closed
    // Session state, set up in start() and torn down in close().
    this._reader = null;         // SignalBufferReader over the shared ring
    this._startWritePos = 0;     // ring write position at start() - the anchor
    this._sweepSamples = 0;      // one loop period in CAPTURE samples
    this._fadeSamples = 0;       // per-side seam fade in CAPTURE samples
    this._playSamples = 0;       // the SAME period in OUTPUT samples (setBand re-commands it)
    this._sweepRefBuf = null;    // cached one-period reference X(t), at the CAPTURE clock
    this._running = false;
  }

  /**
   * Deconvolution FFT bin spacing (Hz) for a single captured period of
   * {@code sweepSamples} CAPTURE samples - captureRateHz /
   * nextPow2(sweepSamples), the ADC's clock because that is the stream the
   * window is grabbed from and the transform is run on. The wizard
   * matches its output-grid density to this so the bin->grid interpolation
   * never oversamples (oversampling facets the trace into a kink + comb).
   * Reflects the actual yRec length the wizard passes to computeFromLogSweep
   * (one period, leadIn 0).
   *
   * @param {number} sweepSamples loop period in CAPTURE samples
   * @returns {number} bin spacing in Hz
   */
  deconvBinHz(sweepSamples) {
    return this._captureRateHz / nextPow2(sweepSamples);
  }

  /**
   * One loop period in OUTPUT samples: the capture-clock period converted to
   * the DAC's clock, so both converters run their own configured rate over the
   * same stretch of time.
   *
   * <p>The conversion has to come out whole. A fractional output period would
   * make the emitted loop a slightly different duration from the window the
   * deconvolution treats as exactly one period, and the two would walk apart
   * cycle by cycle until the trace is meaningless - so the rate pair is
   * refused, with both settings named, instead of tuning against a drifting
   * reference. Faithful port of NotchSweepEngine#playbackSamples.
   *
   * @param {number} captureSamples one loop period in CAPTURE samples
   * @returns {number} the same period in OUTPUT samples
   * @throws {Error} when the rate pair does not divide out whole
   */
  playbackSamples(captureSamples) {
    const scaled = captureSamples * this._playbackRateHz;
    if (scaled % this._captureRateHz !== 0) {
      throw new Error(`one loop period is ${captureSamples} samples at the configured `
        + `${this._captureRateHz} Hz input rate, which is not a whole number of samples at the `
        + `configured ${this._playbackRateHz} Hz output rate - the looping sweep and the captured `
        + 'period would drift apart, so the tuning session is refused rather than shown wrong');
    }
    return scaled / this._captureRateHz;
  }

  /**
   * The looping sweep's one-period reference X(t) - the buffer
   * computeFromLogSweep deconvolves against, as many samples as the captured
   * window and on the same clock (unwindowed; the deconvolution applies the seam
   * fade itself via its fadeSamples parameter, exactly like the Java generator's
   * raw buffer). It is the model of the identical chirp whichever end emits it.
   *
   * @returns {Float64Array|null} the reference, or null before start()
   */
  sweepRef() {
    return this._sweepRefBuf;
  }

  // ---------------------------------------------------------------------------
  // Session lifecycle: open once, stream forever, close once
  // ---------------------------------------------------------------------------

  /**
   * Acquires the shared capture ONCE, points the DDS worklet at a looping
   * Farina log-sweep (Hann fades at each cycle boundary: the kernel replays
   * the buffer back-to-back and the per-side fades smooth the loop seam - the
   * SAME fadeSamples must be applied to the deconvolution reference), and
   * anchors the ring so only session samples are ever served.
   *
   * <p>{@code sweepSamples} and {@code fadeSamples} are CAPTURE samples - the
   * loop is one duration, and the DAC's own count for it is derived here.
   *
   * @param {number} f0           sweep start (Hz) - the WIDENED band, see {@link sweepBand}
   * @param {number} f1           sweep stop (Hz)
   * @param {number} ampVrms      drive amplitude (V RMS)
   * @param {number} dacFsVrms    DAC full-scale voltage the amplitude scales against
   * @param {number} sweepSamples loop period in CAPTURE samples (a power of two)
   * @param {number} fadeSamples  per-side Hann seam-fade length in CAPTURE samples
   * @param {string} outputChannels which DAC lane(s) carry the sweep - 'BOTH' (legacy) /
   *   'LEFT' / 'RIGHT'; the un-driven lane is written as digital silence
   * @param {number} rightLaneScale right-lane full-scale scale (= fsLeft/fsRight) so a
   *   LINKED card with distinct DAC full-scales emits the same physical level on both
   *   lanes (Java NotchSweepEngine.start's setChannelScale(1.0, rightLaneScale)); the
   *   left lane is never scaled
   * @throws {Error} when the rate pair leaves a fractional output period, or when
   *   the input device cannot be opened
   */
  async start(f0, f1, ampVrms, dacFsVrms, sweepSamples, fadeSamples,
    outputChannels = 'BOTH', rightLaneScale = 1.0) {
    // Before anything is acquired: the same period on the output clock, or a
    // refusal that costs nothing.
    const playSamples = this.playbackSamples(sweepSamples);
    const playFade = Math.round(fadeSamples * (this._playbackRateHz / this._captureRateHz));

    this._sweepSamples = sweepSamples;
    this._fadeSamples = fadeSamples;
    this._playSamples = playSamples;
    // The window the deconvolution will divide comes off the ADC, so its
    // reference is rendered on that clock and with its count.
    this._sweepRefBuf = renderLogSweep(f0, f1, sweepSamples, this._captureRateHz);

    // THIS engine's own lane on the active backend (Java: its own GeneratorLane), opened at the
    // configured output rate and put on air with a SILENT looping Farina sweep - the un-mute
    // below happens once the capture is up. The counts are the DAC's OWN: the kernel renders on
    // the output clock, so a capture count handed to it would emit a loop of another duration
    // than the one the deconvolution treats as exactly one period.
    const sink = this._openPlayback({
      deviceId: this._outputDeviceId, ditherBits: this._ditherBits,
      status: (text) => debug('[tune-notch]', text),
    });
    const sweep = {
      logSweep: { f0, f1, sweepSamples: playSamples, leadInSamples: 0 },
      sweepParams: { loop: true, fadeInSamples: playFade, fadeOutSamples: playFade },
    };
    try {
      const granted = await sink.open({
        sampleRate: this._playbackRateHz, deviceId: this._outputDeviceId,
        ditherBits: this._ditherBits, outputChannels,
      });
      // The loop period was converted to output samples at the CONFIGURED rate; a lane clocked
      // at another rate emits a loop of another duration and the trace drifts (Java
      // requireGrantedRate on the generator lane), so the session is refused instead.
      if (granted > 0 && granted !== this._playbackRateHz) {
        throw new Error(`the generator lane granted ${granted} Hz where the configured output `
          + `rate is ${this._playbackRateHz} Hz - the looping sweep and the captured period would `
          + 'drift apart, so the tuning session is refused rather than shown wrong');
      }
      // Match the RIGHT lane's physical level to the LEFT-referenced digital amplitude and gate
      // the looping sweep to the selected DAC lane(s) - Java NotchSweepEngine.start's
      // setChannelScale(1.0, rightLaneScale) + setOutputChannels - from block 0.
      await sink.start({
        form: GenSignalForm.LOG_SWEEP, frequency: 0,
        amplitudeVRms: 0,   // start silent - the post below un-mutes with the real amplitude
        dacFsVoltageAmpl: dacFsVrms, ditherBits: this._ditherBits,
        outputChannels, rightLaneScale,
        control: { ...sweep },
      });
    } catch (e) {
      try { await sink.close(); } catch (_) { /* the open failure is the one to report */ }
      throw e;
    }
    this._sink = sink;

    // Take this session's reader over the shared capture (opening the device if nothing holds it).
    this._reader = await this._capture.acquire();
    if (!this._reader) {
      const err = this._capture.getLastStartError() || 'capture start failed';
      await this._closeSink();
      throw new Error(err);
    }
    this._startWritePos = this._reader.getWritePos();

    // Point the now-running DDS at the looping sweep at the real amplitude.
    this._post({
      amplitudeVRms: ampVrms,
      dacFsVoltageAmpl: dacFsVrms,
      ...sweep,
      resetSweepPosition: true,
    });
    this._running = true;
  }

  /** A live-control message to this session's own lane (the dds-processor message protocol);
   *  dropped while no lane is open. */
  _post(msg) {
    const port = this._sink != null ? this._sink.port : null;
    if (port) port.postMessage(msg);
  }

  /** Closes this session's own playback lane, if one is open. Errors are swallowed exactly as
   *  a generator stop swallows them: a wedged or unplugged device must never block the close. */
  async _closeSink() {
    const sink = this._sink;
    this._sink = null;
    if (sink) {
      try { await sink.close(); } catch (_) { /* ignore */ }
    }
  }

  /**
   * Live band change WITHOUT restart: reconfigures the looping sweep in place
   * (the kernel re-renders its buffer and rewinds to the new sweep's start,
   * exactly like Java's rebuildLogSweepBuffer) and the deconvolution reference
   * follows on ITS OWN clock - whichever end emits, both halves hear the same
   * band. A reference left on the old band would divide the new band's response
   * by the wrong chirp. No-op before start() / after close().
   *
   * @param {number} f0 new sweep start (Hz, widened band)
   * @param {number} f1 new sweep stop (Hz)
   */
  setBand(f0, f1) {
    if (!this._running) return;
    this._sweepRefBuf = renderLogSweep(f0, f1, this._sweepSamples, this._captureRateHz);
    this._post({ logSweep: { f0, f1, sweepSamples: this._playSamples, leadInSamples: 0 } });
  }

  /**
   * Live amplitude change WITHOUT restart (Java NotchSweepEngine.setAmplitude): the
   * new level goes straight to the running DDS. The session sample counter rewinds
   * so {@link latestPeriod} hands out nothing until one full period of new-level
   * samples exists - the deconvolution divides the whole window by ONE drive level,
   * and a window spanning the change would draw the response offset by the level
   * ratio. No-op before start() / after close().
   *
   * @param {number} ampVrms new stimulus level (Vrms)
   */
  setAmplitude(ampVrms) {
    if (!this._running) return;
    this._post({ amplitudeVRms: ampVrms });
    if (this._reader) this._startWritePos = this._reader.getWritePos();
  }

  /**
   * Live output-lane change WITHOUT restart: pushes the gate straight to the
   * running worklet (Java NotchSweepEngine.setOutputChannels). Only the gate is
   * pushed live - the right-lane scale was fixed at start(). No-op before
   * start() / after close().
   *
   * @param {string} outputChannels 'BOTH' | 'LEFT' | 'RIGHT'
   */
  setOutputChannels(outputChannels) {
    if (!this._running) return;
    this._post({ outputChannels });
  }

  /**
   * Total samples captured since start() - drives the settle / fill
   * percentage.
   *
   * @returns {number} session sample count (0 before start)
   */
  availableSamples() {
    if (!this._reader) return 0;
    return this._reader.getWritePos() - this._startWritePos;
  }

  /**
   * Copies the most recent {@code n} samples (L and R) from the shared ring
   * into fresh Float64Arrays. Returns null if fewer than {@code n} samples
   * have been captured this session (still settling).
   *
   * @param {number} n window length in samples (one loop period)
   * @returns {{left: Float64Array, right: Float64Array}|null} the window,
   *   ending at the latest captured sample
   */
  latestPeriod(n) {
    if (!this._reader || this._reader.getWritePos() - this._startWritePos < n) {
      return null;
    }
    const left = new Float64Array(n);
    const right = new Float64Array(n);
    if (this._reader.readLatest(n, left, right) < n) return null;
    return { left, right };
  }

  /**
   * Ends the streaming session ONCE: releases this session's capture reference
   * (closing the input device if this was the last consumer) and closes this
   * session's own playback lane (Java NotchSweepEngine.close stops its own
   * GeneratorLane). Idempotent; a stray setBand() after close is a no-op.
   */
  async close() {
    if (!this._running && !this._reader && !this._sink) return;
    this._running = false;
    const reader = this._reader;
    this._reader = null;
    this._sweepRefBuf = null;
    if (reader) await this._capture.release();
    await this._closeSink();
  }
}
