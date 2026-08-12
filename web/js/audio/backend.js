/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// AudioEngine - the live measurement pipeline, DECOUPLED exactly like the Java
// desktop (gui.generator.GeneratorPane + gui.sound.SharedCapture +
// gui.scope.ScopeController + gui.fft.FftController):
//
//   - GENERATOR - an independent DDS->DAC lifecycle (startGenerator/stopGenerator);
//     owns the output AudioContext + the dds-processor worklet. Has NOTHING to do
//     with capture (mirrors GeneratorPane / GeneratorController).
//   - SHARED CAPTURE - a single ref-counted ADC capture (mirror SharedCapture):
//     the input AudioContext + capture-processor open on the first acquire and
//     close on the last release; one shared SignalBuffer(inRate, 22 s) ring is fed
//     by the capture handler. Each consumer gets its OWN SignalBufferReader cursor.
//   - SCOPE CONSUMER - "latest window" reader: every batch, readLatest(SCOPE_LEN)
//     of the R channel (ch1) -> onScope (mirror ScopeController, overrun-safe).
//   - FFT CONSUMER - contiguous-cursor reader: every batch, read() the new samples
//     and feed the buffer-and-analyze accumulate+dispatch loop; OVERRUN re-anchors
//     and resets fill (mirror FftController + the cross-tick coherent accumulator).
//
// The FFT worker/pool (the faithful brain) is unchanged:
//        ─-> [FftAnalyzer.analyze in a Worker]  (windowed FFTs + coherent/incoherent
//           cross-frame averaging + fundamental/harmonics/THD/SNR)
//        ─-> FftResult ─-> FLL(steer generator off fundamentalHzRefined)
//        ─-> render-time .frc de-embed (RAW accumulator preserved) ─-> onResult(view)
//
// The accumulator the worker produces is RAW: the measured .frc (loopback /
// filter frequency response) is subtracted at RENDER time from BOTH the trace
// AND the THD/IMD table (fft/fft-compensation.js) - it has NOTHING to do with
// the generator. Default (no .frc loaded) = no correction.
//
// BACKEND DISPATCH - this class is also the web's sound.AudioBackend: the ACTIVE
// backend (Preferences ▸ Audio) decides WHICH device the two seams are given -
// Web Audio (getUserMedia + the worklets) or the QA402/QA403's one always-duplex
// WebUSB session. Java switches on its `active` field inside openCapture() /
// openPlayback() / listInputDevices(); those three switches are scanDevices(),
// _newCaptureSource() and _newPlaybackSink() here, and SharedCapture /
// GeneratorController stay backend-agnostic - neither picks its own device.

import { GenSignalForm } from '../generator/dds-kernel.js';
import { debug } from '../util/debug.js';
import { SharedCapture } from './shared-capture.js';
import { WebAudioCaptureSource } from './web-audio-capture-source.js';
import { DeviceFailureReason } from './device-failure-reason.js';
import { NetRefusal } from '../net/net-connection.js';
import { NetCaptureSource } from '../net/net-capture-source.js';
import { NetPlaybackSink } from '../net/net-playback-sink.js';
import { remoteBackendOf } from '../net/net-device-ref.js';
import { EMBEDDED } from '../shell/build-profile.js';
import { GeneratorController } from '../generator/generator-controller.js';
import { WebAudioPlaybackSink } from '../generator/web-audio-playback-sink.js';
import { ScopeController } from '../scope/scope-controller.js';
import { FftController } from '../fft/fft-controller.js';
import { Qa40xCaptureSource } from '../qa40x/qa40x-capture-source.js';
import { Qa40xPlaybackSink } from '../qa40x/qa40x-playback-sink.js';
import { QA40X_BACKEND } from '../qa40x/qa40x-rate-constraint.js';
import { scanDevicesForBackend } from './devices.js';

const HARMONIC_COUNT = 9;                  // H2..H10 default (overridable via config.harmonicCount)
/** DAC full-scale PEAK voltage (= FS-sine RMS × √2). Default when no preferences anchor. */
const DAC_FS_VOLTAGE_AMPL = Math.sqrt(2.0);
/** AudioBackendType.WEB_AUDIO's name - the backend every browser has, and the one assumed when
 *  no Preferences were injected (the behaviour that predates the backend selector). */
const WEB_AUDIO_BACKEND = 'WEB_AUDIO';



export class AudioEngine {
  /**
   * @param deps {prefs, qa40xManager}
   *   - prefs: the live Preferences. Read ONLY for the active backend (`prefs.backend`); every
   *     other setting still reaches the engine through `config`. Omitted -> WEB_AUDIO.
   *   - qa40xManager: the Qa40xDeviceManager the QA40X backend runs on. Built by the shell - it
   *     needs the device-profile store and the settings-dialog opener, neither of which belongs
   *     in the audio layer - and injected here, so nothing in this module reaches a global. Its
   *     finder MUST be the one the Scan click drives: WebUSB's chooser needs that click's user
   *     activation. Omitted -> the QA40X backend has no device and says so when selected.
   *   - qa40xFinder: THAT finder, handed in a second time as the grant seam - the manager keeps
   *     its finder private and enumerates with it (getDevices(), which never prompts), while a
   *     Scan that carries user activation needs the same instance to raise the chooser once
   *     (see {@link #scanDevices}). Omitted -> an analyzer this origin has never been granted can
   *     never become visible, so the QA40X backend stays empty forever.
   */
  constructor({ prefs = null, qa40xManager = null, qa40xFinder = null, netManager = null } = {}) {
    this._prefs = prefs;
    this._qa40x = qa40xManager;
    this._qa40xFinder = qa40xFinder;
    // The net backend's session + remote catalogue (doc/NET-PROTOCOL.md). Injected exactly as
    // the QA40x manager is, and for the same reason: the shell owns the server list and the
    // dial, neither of which belongs in the audio layer. Omitted -> selecting a server backend
    // fails loudly instead of silently answering "no devices".
    this._net = netManager;
    // The backend the CURRENT wiring runs on, as opposed to the one Preferences now holds: the
    // Backend combo commits its selection LIVE (before OK), so the prefs bracket compares the two
    // to recognise a switch, and phase 2 records the new one once it has re-wired.
    this._appliedBackend = this.activeBackend();
    this.config = {
      inDeviceId: '', inRate: 384000,
      outDeviceId: '', outRate: 384000,
      toneHz: 1000, ampVrms: 0.5,   // canonical V RMS (matches the UI amplitude field + ampVrmsOf); not the legacy ampDbfs

      form: GenSignalForm.SINE, tone2Hz: 1100, amp1Pct: 50, amp2Pct: 50,
      rectDuty: 0.5, triDuty: 0.5, ditherBits: 0, snapToBin: true,
      // Sweep params (LINEAR_SWEEP / LOG_SWEEP) - durations in seconds, freqs in Hz.
      sweepStartHz: 20, sweepEndHz: 20000, sweepDurationSec: 1.0,
      sweepFadeInSec: 0.01, sweepFadeOutSec: 0.01, sweepLoop: true,
      fftSize: 65536, window: 'BH4', averages: 16, overlap: 'PCT_0', coherent: true,
      fftFundFromGenerator: false, snrFreqMin: 0, snrFreqMax: 0,   // THD band (0 = full band)
      stopAfterNEnabled: false, stopAfterN: 10,   // auto-stop once N averages collected (Java FftAnalyzerWorker)
      mainsSuppression: 'NONE',   // None / IIR_COMB / SYNC_SUBTRACT / LMS (Java MainsSuppression)
      threads: Math.max(1, (navigator.hardwareConcurrency || 4) - 2),
      warmupMs: 500, fllOn: true,
      channel: 'L',   // FFT-analyzed ADC channel (Java prefs.getFftChannel default L -> ch0); readConfig overrides from prefs.fftChannel
      // Calibration anchors (Preferences supplies these; defaults until wired).
      dacFsVoltageAmpl: DAC_FS_VOLTAGE_AMPL, harmonicCount: HARMONIC_COUNT,
      adcFsVoltageRms: 1.0, dbvOffsetDb: 0.0,
      // Live scope time/div (s) - sizes the captured scope window (see _scopeWindowLen).
      scopeTimePerDiv: 0.001,
    };
    // Three independent lifecycles (Java: generator running / scope capturing / fft recording),
    // each owned by its module; `running` (getter below) = generator-on OR the shared capture is
    // open (refCount > 0).
    // Generator (gui/generator/GeneratorController): the DDS output path + file player + the
    // analysis freqs. Holds the SHARED config by reference; the engine delegates the public API.
    this._gen = new GeneratorController(this.config, {
      status: (t) => this._status(t),
      // The DAC for the ACTIVE backend, resolved at EVERY start - exactly as Java re-resolves
      // AudioBackend.instance().openPlayback(device, rate, depth, dither) per start, so a backend
      // switch between sessions needs no push-down into the controller.
      openPlayback: (deps) => this._newPlaybackSink(deps),
    });
    // Shared capture (gui/sound/SharedCapture): the one ref-counted ADC device + ring. Consumers
    // (scope / FFT / loopback rec) acquire()/release() their own readers; per batch it fans out to
    // _dispatchBatch so each consumer reads off its own cursor.
    this._capture = new SharedCapture({
      getConfig: () => this.config,
      status: (t) => this._status(t),
      // The live capture publishes CAPTURE_BATCH_AVAILABLE; ScopeController + FftController
      // subscribe and self-feed off their own cursors (no central _dispatchBatch router).
      publishBatch: true,
      computeAnalysisFreqs: () => this._gen.computeAnalysisFreqs(),
      captureSource: this._backendCaptureSource(),
    });
    // DEDICATED MEASUREMENT capture (Java CaptureWithGenerator / NotchSweepEngine each open
    // their OWN device line): the FreqResp sweep + Tune-notch wizard acquire/release THIS
    // instance - a SEPARATE input device line from the scope/FFT _capture above - so a
    // measurement never rides the live consumers' ring. Its batch fan-out is the FreqResp
    // loopback recording tap only (the Tune-notch wizard reads this ring via its own cursor).
    this._measCapture = new SharedCapture({
      getConfig: () => this.config,
      status: (t) => this._status(t),
      onBatch: (d) => this._dispatchMeasBatch(d),
      computeAnalysisFreqs: () => this._gen.computeAnalysisFreqs(),
      // Its OWN device for the active backend - a second input line, device-isolated from the
      // live one above. On the QA40x there is only ONE line: the duplex engine refuses a second
      // capture consumer loudly, which is right, because a measurement is modal and takes the
      // device over only once the live consumers have released it.
      captureSource: this._backendCaptureSource(),
    });
    // Scope consumer (gui/scope/ScopeController): a latest-window reader of the shared capture.
    // Both dual-tone refine seeds read LIVE per access, and they are ASKED for
    // rather than derived: the consumer requests GENERATOR_EMITTED_HZ at its own capture rate and
    // the generator answers (Java ScopeView.dualToneEmittedHz). That is why no generator getter is
    // injected here any more - an analyzer with no generator to ask has no emitted tone to report,
    // not a pair it may re-derive from the same preferences.
    this._scope = new ScopeController(this._capture, this.config);
    // FFT consumer (gui/fft/FftController): the worker pool + cross-frame coherent accumulator +
    // FLL steer + stop-after-N + the render-time .frc/mains de-embed. Reads/steers the generator.
    this._fft = new FftController(this._capture, this._gen, this.config,
      { status: (t) => this._status(t), prefs: this._prefs });

    this.onStatus = null;   // (text) => void  (onResult / onFftAutoStopped delegate to this._fft)

    // Loopback recording tap (freqresp sweep capture): when set, the capture handler appends raw
    // L AND R channel samples to two growing chunk lists until stopCaptureRecording() - the stereo
    // sweep deconvolves both ADC channels against the same reference (Java FreqRespAnalyzer's
    // single stereo pass: rec.left() / rec.right()).
    this._recChunks = null;
    // The measurement ring's own cursor for that recording (Java SweepCapture holds exactly this
    // reader) and the absolute ring position its first frame sits at - together they turn a ring
    // position into an offset into the recording (see dropRecordingBefore).
    this._recReader = null;
    this._recBasePos = 0;

    // App-exit teardown of the active backend's manager (Java AudioBackend.shutdown(), called from
    // the explicit exit code): the QA40x is the one backend whose device carries state across
    // process death, so it must be left parked at maximum input attenuation. Both events are hooked
    // because neither fires reliably alone - a tab discard or a mobile background gives only
    // pagehide, some desktop flows give only beforeunload - and a double call is harmless: the
    // manager's shutdown() serializes and a parked/never-opened device is a no-op. BEST EFFORT by
    // nature: unload does not wait for promises, so the USB writes may not complete. The switch
    // AWAY from QA40X (afterApplyBackendChanges) is the path that parks it deterministically.
    if (this._qa40x != null && typeof window !== 'undefined' && window.addEventListener) {
      const park = () => { this._qa40x.shutdown(); };
      window.addEventListener('pagehide', park);
      window.addEventListener('beforeunload', park);
    }
  }

  /** The AudioBackendType name currently in force (Java AudioBackend.active()). */
  activeBackend() {
    return this._prefs != null ? this._prefs.backend.get() : WEB_AUDIO_BACKEND;
  }

  /** True while the ACTIVE backend runs the generator (and the ADC) on another machine - the one
   *  fact a sweep consumer still needs, because its record then anchors at the bench's in-band
   *  mark instead of the local ready instant (Java GeneratorLane.isRemoteSession, which likewise
   *  asks the BACKEND rather than an open lane: the operator switches backends between
   *  measurements). */
  isRemoteSession() {
    return remoteBackendOf(this.activeBackend()) != null;
  }

  /** Consumer/generator controllers. The engine constructs the shared infrastructure and exposes
   *  the controllers so each pane drives its OWN controller directly (record lifecycle + result
   *  callbacks) rather than through a fat delegating facade (Java: panes own their controllers). */
  get generator() { return this._gen; }
  get capture() { return this._capture; }
  get scope() { return this._scope; }
  get fft() { return this._fft; }

  /** True when the generator is producing a signal OR the shared capture device
   *  is open (any consumer holds a reference). Mirrors SharedCapture.isCapturing
   *  ORed with the generator-running state; freqresp/predistortion gate on it. */
  get running() {
    return this._genOn || this._capture.refCount > 0 || this._measCapture.refCount > 0;
  }

  /** True when BOTH the input and output devices are genuinely free - no consumer holds the
   *  shared capture, the input AudioContext has fully closed, the generator is idle and no
   *  output context (tone / file / sweep) is still open. The measurement idle-wait
   *  (worker-idle.js) polls THIS rather than {@link #running}: stopGenerator()/release() flip
   *  their flags SYNCHRONOUSLY but the ctx.close() that actually releases the OS device resolves
   *  tens of ms later, so a flag-only wait let the takeover reopen the device mid-close ->
   *  NotReadableError (a self-contention). Composed from the two controllers' context state. */
  get deviceIdle() {
    return this._capture.refCount === 0 && !this._capture.contextOpen
      && this._measCapture.refCount === 0 && !this._measCapture.contextOpen
      && !this._gen.running && !this._gen.filePlaying && !this._gen.outputContextOpen;
  }

  /** Forwards a control message to the running DDS generator worklet (freqresp
   *  sweep / predistortion compensation). No-op when the generator isn't running. */
  postGen(msg) {
    if (this._genOn && this.genNode) this.genNode.port.postMessage(msg);
  }

  /** Begins accumulating raw L AND R channel capture samples for a loopback
   *  recording (freqresp stereo sweep). Tapped by the capture handler. If no consumer
   *  holds the shared capture open, acquires its OWN reference for the duration of
   *  the recording (released by stopCaptureRecording) so the loopback is captured
   *  even when neither the scope nor the FFT is recording. */
  async startCaptureRecording() {
    this._recChunks = { l: [], r: [] };
    this._recOwnsCapture = false;
    this._recReader = null;
    // Open our OWN measurement device line (device-isolated from scope/FFT). A measurement is
    // modal, so _measCapture.refCount is 0 here and we always acquire our own reference.
    if (this._measCapture.refCount === 0) {
      const reader = await this._measCapture.acquire();
      this._recOwnsCapture = (reader != null);
      this._recReader = reader;
    }
    this._rebaseRecording();
  }

  /** The measurement ring's cursor for the in-flight recording - the reader Java's SweepCapture
   *  holds: a remote sweep reads the bench's in-band sweep mark and the stream's terminal state
   *  off it (getSweepMarkPos / isFinished). null when nothing is recording, or when the device
   *  could not be opened. The samples themselves still travel through the tap; this cursor is
   *  never read from, so it can never consume what the recording needs. */
  recordingReader() { return this._recReader; }

  /** Re-anchors the recording on the ring: the tap and the ring are fed the SAME batch by the
   *  same call (_onCaptureBatch appends, then dispatches), so the write position at this instant
   *  IS the absolute position of the first frame the tap will hold. */
  _rebaseRecording() {
    this._recBasePos = this._recReader != null ? this._recReader.getWritePos() : 0;
  }

  /**
   * Drops everything recorded BEFORE the absolute ring position {@code absolutePos} - the web's
   * spelling of SweepCapture's {@code reader.seek(mark)}: the record then begins exactly at the
   * bench's sweep sample 0 instead of at the local warmup anchor. Returns the frames dropped
   * (fewer than asked only for a position past what has arrived, which the mark never is).
   */
  dropRecordingBefore(absolutePos) {
    const rec = this._recChunks;
    if (!rec) return 0;
    const wanted = Math.max(0, absolutePos - this._recBasePos);
    const dropped = this._dropFront(rec.l, wanted);
    this._dropFront(rec.r, wanted);
    this._recBasePos += dropped;
    return dropped;
  }

  /** Discards the first {@code count} frames of a growing chunk list - whole chunks first, then a
   *  view over the remainder of the one that straddles the boundary. Returns what was dropped. */
  _dropFront(chunks, count) {
    let left = count;
    while (left > 0 && chunks.length > 0) {
      const c = chunks[0];
      if (c.length <= left) { chunks.shift(); left -= c.length; }
      else { chunks[0] = c.subarray(left); left = 0; }
    }
    return count - left;
  }

  /** Drops the samples accumulated so far in the in-flight loopback recording, keeping the
   *  capture open. The FreqResp sweep calls this AFTER the cold-device warmup so the
   *  recorded (and live-metered) window starts clean at the sweep - the warmup silence is
   *  discarded. Without it that silence sat at the front of the recording, shifting the
   *  busy meter's time axis and pushing the sweep's end past the meter's totalSec span
   *  (the progress trace started too early and never reached the right edge). No-op when no
   *  recording is in flight. */
  resetCaptureRecording() {
    if (this._recChunks) this._recChunks = { l: [], r: [] };
    this._rebaseRecording();
  }

  /** Ends a loopback recording and returns BOTH concatenated ADC channels
   *  ({left, right}: Float64Array) - mirroring StereoSamples from the Java analyzer's
   *  single stereo capture pass. Releases the recording's own capture reference if it
   *  acquired one. */
  async stopCaptureRecording() {
    const rec = this._recChunks || { l: [], r: [] };
    this._recChunks = null;
    this._recReader = null;
    this._recBasePos = 0;
    const concat = (chunks) => {
      let total = 0;
      for (const c of chunks) total += c.length;
      const out = new Float64Array(total);
      let o = 0;
      for (const c of chunks) { out.set(c, o); o += c.length; }
      return out;
    };
    const left = concat(rec.l);
    const right = concat(rec.r);
    if (this._recOwnsCapture) { this._recOwnsCapture = false; await this._measCapture.release(); }
    return { left, right };
  }

  /** Number of frames accumulated so far in the in-flight loopback recording (0 when
   *  not recording). Lets the freqresp busy meter pump the level trace LIVE as the sweep
   *  collects, mirroring the desktop's per-block StereoCaptureProgress cadence
   *  (CaptureWithGenerator.runStereo) - the web records in one shot but the chunks grow
   *  batch-by-batch, so a poller can read the newly-arrived tail as it lands. */
  recordingLength() {
    if (!this._recChunks) return 0;
    let n = 0;
    for (const c of this._recChunks.r) n += c.length;
    return n;
  }

  /** Linear RMS of max(L,R) over the recorded window [fromFrame, fromFrame+count) of the
   *  in-flight loopback recording - the per-block level the desktop feeds its live meter
   *  (CaptureWithGenerator emits Math.max(rmsL, rmsR)). Returns 0 when the window is not
   *  yet fully captured or nothing is recording. Walks the growing chunk lists without
   *  concatenating, so it stays cheap when polled every few ms. */
  recordingRms(fromFrame, count) {
    if (!this._recChunks || count <= 0) return 0;
    let sumL = 0.0, sumR = 0.0, n = 0, pos = 0;
    const L = this._recChunks.l, R = this._recChunks.r;
    for (let ci = 0; ci < R.length; ci++) {
      const rc = R[ci], lc = L[ci], len = rc.length;
      if (pos + len <= fromFrame) { pos += len; continue; }
      const start = Math.max(0, fromFrame - pos);
      const end = Math.min(len, fromFrame + count - pos);
      for (let j = start; j < end; j++) {
        const l = lc[j], r = rc[j];
        sumL += l * l; sumR += r * r; n++;
      }
      pos += len;
      if (pos >= fromFrame + count) break;
    }
    if (n === 0) return 0;
    return Math.max(Math.sqrt(sumL / n), Math.sqrt(sumR / n));
  }

  /** FFT averages accumulated since the last reset (predistortion host). */
  completedAnalyses() { return this._fft.completedAnalyses(); }

  /** Fraction (0..1) of the current FFT frame already captured (−1 on overrun) -
   *  drives the fill-% indicator (Java FftAnalyzerWorker.getNextFrameProgress). */
  nextFrameProgress() { return this._fft.nextFrameProgress(); }

  /** Resets the accumulated-averages counter (predistortion round boundary). */
  resetAnalyses() { this._fft.resetAnalyses(); }

  /** Re-emits the display from the RAW FFT accumulator so a calibration
   *  change re-applies the current correction cascade while STOPPED (FftController). */
  reemitFftDisplay(base) { return this._fft.reemitDisplay(base); }

  // The visible status line was removed; mirror status to the console for debugging (the hidden
  // #status aria-live element is still updated via onStatus for screen readers).
  _status(t) { debug('[audio]', t); if (this.onStatus) this.onStatus(t); }

  /** Enumerate the ACTIVE backend's input/output devices, each input carrying its native rate
   *  (Java AudioBackend.listInput/OutputDevices, which switch on the active backend). Device
   *  enumeration is its own layer (devices.js), not part of the capture/generate engine.
   *
   *  @param fromUserGesture true ONLY from the Preferences ▸ Scan click, whose user activation is
   *    what lets the QA40x path raise the WebUSB device chooser - the one way an analyzer this
   *    origin has never been granted can become visible at all. Every other enumeration (app load,
   *    a backend rollback) leaves it false and so lists only already-granted devices: a chooser
   *    without activation would throw, and prompting behind the user's back is user-hostile. */
  async scanDevices(fromUserGesture = false) {
    // `async` so an unwired QA40x manager arrives as a REJECTION, not a synchronous throw: this
    // method has always handed its caller a promise, and a bare .catch() must keep working.
    const backend = this.activeBackend();
    const manager = (backend === QA40X_BACKEND) ? this._requireQa40x() : null;
    // Resolved synchronously, and devices.js spends no await before the chooser either: user
    // activation is a 5-second budget the Web Audio probe (getUserMedia + ~½ s per device) would
    // blow outright, which is why the QA40x branch grants FIRST and never probes.
    const granter = fromUserGesture ? this._qa40xGranter(backend) : null;
    // The net manager is handed on so devices.js can take its NET branch: without it a scan on a
    // server backend falls through to the LOCAL getUserMedia probe and fills the combos with THIS
    // machine's devices while the backend is remote - and the first open then fails on a device
    // name the bench never offered.
    return scanDevicesForBackend(backend, manager, (t) => this._status(t), granter, this._net);
  }

  /** The WebUSB grant seam for a scan that carries user activation: the injected finder on the
   *  QA40X backend, null on any other. A QA40X scan with no finder wired is exactly the regression
   *  this exists to prevent - the chooser unreachable, so a never-granted analyzer stays invisible
   *  - and it is a wiring mistake, not a device state, hence the warning rather than a throw: an
   *  already-granted analyzer still enumerates fine without it. */
  _qa40xGranter(backend) {
    if (backend !== QA40X_BACKEND) {
      return null;
    }
    if (this._qa40xFinder == null) {
      console.warn('QA40x scan carries user activation but no Qa40xDeviceFinder was injected into'
        + ' the AudioEngine - the WebUSB chooser cannot be raised, so only analyzers this origin'
        + ' was already granted can be listed');
      return null;
    }
    return this._qa40xFinder;
  }

  /** The injected QA40x device manager, or a loud failure. Enumerating, capturing and playing on
   *  a backend whose manager the shell never wired must fail visibly (the scan surfaces it, a
   *  start reports it as a device error) - never silently as "no devices". */
  _requireQa40x() {
    if (this._qa40x == null) {
      throw new Error('QA40x backend selected but no Qa40xDeviceManager was injected into the AudioEngine');
    }
    return this._qa40x;
  }

  /** The ACTIVE backend's capture device (Java AudioBackend.openCapture): the Web Audio
   *  getUserMedia + worklet pipeline, or a client of the QA40x duplex engine's capture lane.
   *  Both implement the one CaptureSource contract SharedCapture consumes.
   *
   *  WHERE THE LANE CLIENT IS BORN. Java takes two steps: AudioBackend.openCapture() switches on
   *  the active backend, then Qa40xDeviceManager.openCapture() - whose entire body is
   *  `new Qa40xRecorder(this, sampleRate)` - constructs the client. The web collapses both into
   *  this one method, because the second step carries no decision: the client is built here with
   *  the manager it drives (Java's `this`) and reads the rate at open() instead of at construction.
   *  So Qa40xDeviceManager has NO openCapture/openPlayback, deliberately and permanently - not
   *  pending some later module. */
  _newCaptureSource(backend) {
    if (remoteBackendOf(backend) != null) return this._newNetCaptureSource();
    this._requireNotEmbedded(backend);
    return (backend === QA40X_BACKEND)
      ? new Qa40xCaptureSource(this._requireQa40x())
      : new WebAudioCaptureSource({ status: (t) => this._status(t) });
  }

  /** The EMBEDDED packaging has no local devices at all - the page a server serves is not a
   *  secure context, so getUserMedia and WebUSB are not there to be called. A local backend can
   *  only be reached here through a STORED preference that predates the packaging, and opening
   *  one would fail deep inside the browser with a sentence about permissions. It fails here
   *  instead, saying what is actually true (build-profile.js). */
  _requireNotEmbedded(backend) {
    if (EMBEDDED) {
      throw new Error(`this build serves only Phonalyser server backends - '${backend}' is not one`);
    }
  }

  /** The bench's input lane (doc/NET-PROTOCOL.md §4.4) as a CaptureSource - the same contract
   *  the two local devices implement, which is why nothing above this line changes when the ADC
   *  is on another machine. The device ref comes from the manager's catalogue, resolved by the
   *  configured device NAME: a remote device has no browser deviceId to key on. */
  _newNetCaptureSource() {
    const manager = this._requireNet();
    const device = manager.getDeviceByName(this.config.inDeviceId, false);
    return new NetCaptureSource({ connection: manager.connection, device, owner: manager });
  }

  /** The manager, or a loud failure: capturing or playing on a net backend whose session the
   *  shell never wired must fail visibly, never silently as "no devices". */
  _requireNet() {
    const manager = this._net;
    if (manager == null || manager.connection == null) {
      // A refusal that KNOWS what it is: the session to the bench is gone, which the operator
      // reads as "the connection was lost" rather than as an unexplained failure. A plain Error
      // here is what made a start after a dropped session report UNKNOWN.
      throw new NetRefusal('the session to the bench is gone - reconnect to the server',
        DeviceFailureReason.DEVICE_DISCONNECTED);
    }
    return manager;
  }

  /** The ACTIVE backend's playback device (Java AudioBackend.openPlayback): the output
   *  AudioContext + dds-processor worklet, or a client of the QA40x duplex engine's generator
   *  lane. The QA40x sink takes no `deps` - it has no async device-loss channel to report on
   *  (a wedged transport surfaces as a rejected start) and its status goes to the debug log.
   *
   *  Same collapse as {@link #_newCaptureSource}: Java's AudioBackend.openPlayback() switch plus
   *  Qa40xDeviceManager.openPlayback()'s `new Qa40xGenerator(this, sampleRate, ditherBits)` are
   *  this one line. Rate and dither are not constructor arguments here - the sink reads them from
   *  the start spec (setDitherBits), so a live dither change lands without a reopen. */
  _newPlaybackSink(deps) {
    const backend = this.activeBackend();
    if (remoteBackendOf(backend) != null) {
      const manager = this._requireNet();
      return new NetPlaybackSink({
        connection: manager.connection,
        device: manager.getDeviceByName(this.config.outDeviceId, true),
        owner: manager,
        status: deps && deps.status,
      });
    }
    this._requireNotEmbedded(backend);
    return (backend === QA40X_BACKEND)
      ? new Qa40xPlaybackSink(this._requireQa40x())
      : new WebAudioPlaybackSink(deps);
  }

  /**
   * One CaptureSource per SharedCapture that holds the device of whichever backend was active at
   * its last open(), and replaces it when the active backend has moved since. Java dispatches at
   * the same moment - its SharedCapture.acquire() calls AudioBackend.instance().openCapture(...)
   * per acquire - and resolving at open() is what lets a backend switch land WITHOUT replacing
   * the SharedCapture whose ring and reader cursors the scope/FFT consumers already hold.
   *
   * @returns {import('./shared-capture.js').CaptureSource}
   */
  _backendCaptureSource() {
    let device = null;         // the concrete device currently held (null before the first open)
    let deviceBackend = null;
    return {
      open: (deviceId, sampleRateHz) => {
        const backend = this.activeBackend();
        // Rebuilt when the backend changed - and when the source has gone STALE, which is a
        // bench source bound to a session that has since died or been replaced. Without the
        // second test the cached source went on talking to a closed socket for the life of the
        // page: every start failed with "the connection to ws://... is closed" as UNKNOWN, and
        // only a reload cleared it. A source that cannot say (a local
        // device) is never stale.
        const stale = device != null && typeof device.isStale === 'function' && device.isStale();
        if (device == null || deviceBackend !== backend || stale) {
          device = this._newCaptureSource(backend);
          deviceBackend = backend;
        }
        return device.open(deviceId, sampleRateHz);
      },
      // BOTH callbacks are forwarded: the second one is the capture-ended seam (Java's
      // PcmBatchListener.captureEnded). A wrapper that passed only the batches would leave the
      // ring unable to learn that its device died - the trace would go on drawing a flat line,
      // which is the very thing that seam exists to end.
      start: (onBatch, onCaptureEnded) => device.start(onBatch, onCaptureEnded),
      // Null-guarded because teardown() runs stop-then-close on a FAILED acquire too, and a
      // TypeError here would mask the open failure SharedCapture is in the middle of reporting.
      stop: () => (device != null ? device.stop() : undefined),
      close: () => (device != null ? device.close() : undefined),
      // The optional halves of the contract, forwarded only when the concrete device has them
      // (SharedCapture tests both the same way): the failure classifier of the backend that owns
      // the native error, and the in-band marker seam a bench-rendered sweep needs.
      // UNKNOWN rather than undefined when the concrete device cannot read its own errors: this
      // wrapper always HAS the method, so the caller's "does it have one" test can no longer be
      // the fallback - the SPI default has to be answered here (Java's default classifyFailure).
      classifyFailure: (err) => (device != null && typeof device.classifyFailure === 'function'
        ? device.classifyFailure(err) : DeviceFailureReason.UNKNOWN),
      // The sentence the failure came WITH, when the backend that owns it composed one (a bench's
      // spec-4.2 refusal). Null is the SPI default here for the same reason UNKNOWN is above.
      refusalText: (err) => (device != null && typeof device.refusalText === 'function'
        ? device.refusalText(err) : null),
      setMarkerListener: (listener) => {
        if (device != null && typeof device.setMarkerListener === 'function') {
          device.setMarkerListener(listener);
        }
      },
      /** The concrete per-backend device currently held - the dispatch's one observable. */
      get device() { return device; },
      get sampleRate() { return device != null ? device.sampleRate : 0; },
      get isOpen() { return device != null && device.isOpen; },
    };
  }

  // -------------------------------------------------------------------------
  // GENERATOR - the DDS output path + file player live in GeneratorController
  // (generator-controller.js, this._gen). The engine delegates the public API
  // unchanged so callers (app, freqresp/predistortion hosts) stay untouched; the
  // analysis freqs (snapped/binW) + FLL trim state live on the controller and the
  // FFT/scope consumers read/steer them through the delegating accessors below.
  // -------------------------------------------------------------------------

  /** Recomputes the analysis freqs (snapped/binW/fundBin) on the generator controller. */
  _computeAnalysisFreqs() { this._gen.computeAnalysisFreqs(); }

  get snapped() { return this._gen.snapped; }
  get binW() { return this._gen.binW; }
  get outSampleRate() { return this._gen.outSampleRate; }
  get genNode() { return this._gen.genNode; }
  get _genOn() { return this._gen.running; }

  /** True while the generator lane is on air - the publish gate for signal-change
   *  events: an edit with the lane silent changes no emitted signal. */
  get generatorOn() { return this._gen.running; }
  // FLL state (genFreq/fllErrHz/fllLocked/fllStable/rejectedCount) now lives on FftController;
  // the FFT loop publishes GENERATOR_FREQ_TRIM and the generator applies it. Not exposed here -
  // the display reads it off the emitted FftResult.fll.

  async startGenerator() { return this._gen.startGenerator(); }

  async stopGenerator() { return this._gen.stopGenerator(); }
  retuneGenerator() { return this._gen.retuneGenerator(); }
  /** The capture rate the FFT bin grid is built on - what the generator pane's snap brackets
   *  are computed against, so a label and the emitted tone are read off the same grid
   *  (Java GeneratorController.analysisSampleRate). */
  analysisSampleRate() { return this._gen.analysisSampleRate(); }

  /** Claims the playback lane's from-below end for the ONE operator report - null while healthy
   *  and for every caller after the first. Polled by the generator pane's blink tick. */
  async takePlaybackEndedFromBelow() { return this._gen.takePlaybackEndedFromBelow(); }

  // -------------------------------------------------------------------------
  // FILE PLAYER - the monitoring "Load from..." lane lives in GeneratorController.
  // -------------------------------------------------------------------------

  // `file` = the RAW picked file ({bytes, name}), which only the BENCH path uses: a server
  // decodes for itself, so what goes up the wire is the file, not these decoded channels.
  async playFileBuffer(channels, sampleRate, loop, file) { return this._gen.playFileBuffer(channels, sampleRate, loop, file); }
  async openSweepContext(requestedRate, opts) { return this._gen.openSweepContext(requestedRate, opts); }
  async playSweepBuffer(buf, sampleRate, opts) { return this._gen.playSweepBuffer(buf, sampleRate, opts); }
  setFilePlayLoop(loop) { this._gen.setFilePlayLoop(loop); }
  async stopFile() { return this._gen.stopFile(); }
  get filePlaying() { return this._gen.filePlaying; }
  /** Why the last file-playback attempt did not happen, localized - null when it did (Java
   *  GeneratorController.getFilePlayError). The pane's status line reads it after a play. */
  get filePlayError() { return this._gen.filePlayError; }
  get onFileEnded() { return this._gen.onFileEnded; }
  set onFileEnded(fn) { this._gen.onFileEnded = fn; }

  // -------------------------------------------------------------------------
  // CAPTURE orchestration - the device + ring + refcount live in SharedCapture
  // (shared-capture.js, this._capture). What remains here is the engine-side
  // consumer wiring: reopen-on-device-change + the per-batch fan-out to the
  // loopback rec tap / scope / FFT.
  // -------------------------------------------------------------------------

  /** Forces the shared capture device to close and reopen with the CURRENT
   *  config.inDeviceId / inRate, re-issuing fresh readers to every active
   *  consumer (scope / FFT / loopback recording) so a live input-device change
   *  takes effect immediately - no page reload, no stop/start. No-op when the
   *  device isn't open (the NEXT acquire already uses the new id via readConfig).
   *  Mirrors the Java behaviour where reopening the SharedCapture re-attaches the
   *  consumers' SignalBufferReaders to the fresh ring. */
  async reopenCaptureDevice() {
    if (this._capture.refCount === 0) return;   // closed -> next start picks up the new id
    // Remember which consumers were attached; teardown drops the refcount to 0.
    const scopeWas = this._scope.recording, fftWas = this._fft.recording;
    await this._capture.teardown();
    // Re-acquire once per previously-attached consumer so the refcount is restored.
    // Only the LIVE scope/FFT capture is reopened here; a measurement runs on its own device
    // line (_measCapture) and is modal, so it is never live during a device-selector change.
    if (scopeWas) await this._scope.reattach();
    if (fftWas) await this._fft.reattach();
  }

  /** Preferences-dialog bracket, phase 1 (Java MainWindow ->
   *  MultifunctionalTab.beforeApplyBackendChanges): STOP each consumer whose direction CHANGED,
   *  before the commit, while the current devices are still open - so every line closes cleanly.
   *  Gated per direction: the capture consumers (scope + FFT) bounce when captureChanged (each
   *  REALLY releases its own shared-capture ref; the one shared input device closes on the LAST
   *  release - the refcount handles it, no central teardown), and the generator bounces when
   *  outputChanged (it closes its own output context). Web Audio's input and output are SEPARATE
   *  devices, so an input-only change never disturbs the generator and vice-versa. Call BEFORE
   *  committing the new device/rate to config; pair with afterApplyBackendChanges().
   *  A BACKEND change overrides the gating and bounces BOTH directions - see _backendChanged(). */
  async beforeApplyBackendChanges(captureChanged, outputChanged) {
    const backendChanged = this._backendChanged();
    if (captureChanged || backendChanged) { await this._scope.stopCaptureForPrefs(); await this._fft.stopCaptureForPrefs(); }
    if (outputChanged || backendChanged) await this._gen.stopPlayForPrefs();
  }

  /** Preferences-dialog bracket, phase 2 (Java afterApplyBackendChanges): RESTART exactly what was
   *  running, on the just-committed config. Gated per direction: the capture consumers (scope + FFT)
   *  that were live re-acquire their refs when captureChanged - the shared input device reopens on
   *  the FIRST re-acquire at the committed config.inDeviceId / inRate (refcount handles it, no
   *  central re-open) - and the generator restarts its playing engine (tone or retained file) when
   *  outputChanged, reopening its own output context. Input and output are separate devices, so
   *  each direction re-acquires only its own resource.
   *
   *  A BACKEND change additionally parks the backend being left, BEFORE anything restarts: Java's
   *  AudioBackend.setActive() shuts the deactivated manager down at the switch precisely so its
   *  device never sits in a live state while another backend measures - for the QA40x that is the
   *  safe-state park (input +42 dBV, output −12 dBV, transport released), and it must run after
   *  phase 1 detached the lanes and before the new backend opens anything. */
  async afterApplyBackendChanges(captureChanged, outputChanged) {
    const backendChanged = this._backendChanged();
    const left = this._appliedBackend;
    this._appliedBackend = this.activeBackend();
    if (backendChanged && left === QA40X_BACKEND && this._qa40x != null) await this._qa40x.shutdown();
    if (captureChanged || backendChanged) { await this._scope.startCaptureForPrefs(); await this._fft.startCaptureForPrefs(); }
    if (outputChanged || backendChanged) await this._gen.startPlayForPrefs();
  }

  /** True while Preferences holds a different backend than the one this engine is wired for - in
   *  which case BOTH brackets bounce BOTH directions, whatever the per-direction flags say. The
   *  per-direction gating exists only because Web Audio has a separate input and output device;
   *  the QA40x is ONE always-duplex device whose ADC will not stream unless the DAC is fed (the
   *  duplex engine primes the output past its start threshold to start the stream at all), so a
   *  single-direction bounce there is meaningless: it would leave the other lane attached to the
   *  old backend's device, or the new device half-started. It is also the moment the device the
   *  app is leaving has to be parked, which needs both lanes down. Java has no gating at all here
   *  (MultifunctionalTab bounces all three on any audio change) - this is that behaviour, restored
   *  for exactly the change that needs it. */
  _backendChanged() {
    return this.activeBackend() !== this._appliedBackend;
  }

  /** Per-batch fan-out for the dedicated MEASUREMENT capture (_measCapture): the FreqResp
   *  stereo loopback recording tap - keep BOTH ADC channels (L = ch0, R = ch1) for the
   *  deconvolution. The Tune-notch wizard reads the measurement ring through its own cursor,
   *  so it needs nothing here. */
  _dispatchMeasBatch(d) {
    if (this._recChunks) {
      this._recChunks.l.push(Float64Array.from(d.l.subarray(0, d.n)));
      this._recChunks.r.push(Float64Array.from(d.r.subarray(0, d.n)));
    }
  }

  // -------------------------------------------------------------------------
  // SCOPE - the latest-window consumer lives in ScopeController (engine.scope).
  // The scope pane drives its record lifecycle directly; the engine still owns
  // the streaming-save cursor API + the long measurement/zoom reads below.
  // -------------------------------------------------------------------------

  /** Acquires a fresh forward-read capture cursor over the shared ring for the
   *  scope stream-forward record, opening the device if no consumer holds it yet
   *  (mirror MessageBus.request(CAPTURE_ACQUIRE)). Returns the SignalBufferReader,
   *  or null when the device could not be opened. The caller MUST pair each
   *  successful acquire with {@link #releaseCaptureReader}. */
  async acquireCaptureReader() { return this._capture.acquire(); }

  /** Releases one capture reference taken by {@link #acquireCaptureReader}
   *  (mirror MessageBus.publish(CAPTURE_RELEASE)). */
  async releaseCaptureReader() { return this._capture.release(); }

  /** Acquires a cursor over the DEDICATED MEASUREMENT capture ring (the Tune-notch wizard's
   *  live sweep loop) - its OWN device line, isolated from the scope/FFT capture above.
   *  Returns the SignalBufferReader or null; pair with {@link #releaseMeasurementReader}. */
  async acquireMeasurementReader() { return this._measCapture.acquire(); }

  /** Releases one reference on the measurement capture ({@link #acquireMeasurementReader}). */
  async releaseMeasurementReader() { return this._measCapture.release(); }

  /** The last MEASUREMENT-device open failure message (surfaced when a FreqResp / Tune-notch
   *  capture fails to open) - the measurement capture's error, not the scope/FFT one. */
  getMeasurementStartError() { return this._measCapture.getLastStartError(); }

  /** The shared ring capacity in frames (BUFFER_SECONDS · inRate) - the streaming
   *  dispatch routes a request longer than this to the forward-record path. */
  getCaptureCapacity() { return this._capture.getCapacity(); }

  /** The last input-device open failure message (for the streaming-save error
   *  surface when acquireCaptureReader returns null). */
  getLastStartError() { return this._capture.getLastStartError(); }

  readZoomedWindow() { return this._scope.readZoomedWindow(); }



  // -------------------------------------------------------------------------
  // FFT - the contiguous-cursor consumer (worker pool + accumulator + FLL + the
  // render-time .frc/mains de-embed) lives in FftController (engine.fft). The FFT
  // pane drives its record lifecycle + channel directly; the cross-cutting stats
  // API (completedAnalyses / resetAnalyses / nextFrameProgress / reemitFftDisplay)
  // stays a thin service forward above.
  // -------------------------------------------------------------------------






  // -------------------------------------------------------------------------
  // Fused convenience lifecycle - generator + both consumers in one call. Kept
  // for the freqresp / predistortion hosts and the structural-restart paths that
  // drive the whole pipeline; the per-pane Record controls drive each consumer
  // independently via setScopeRecording / setFftRecording.
  // -------------------------------------------------------------------------

  async start() {
    await this.startGenerator();
    await this._scope.setRecording(true);
    await this._fft.setRecording(true);
  }

  async stop() {
    await this.stopGenerator();
    await this._scope.setRecording(false);
    await this._fft.setRecording(false);
    this._status('stopped.');
  }
}
