/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// AudioEngine - the web's gui.UIEngines: the application-lifetime bundle of the three audio
// engines, composed over the backend seams and DECOUPLED exactly like the Java desktop
// (gui.generator.GeneratorController + gui.sound.SharedCapture + gui.scope.ScopeController +
// gui.fft.FftController):
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
//        ─-> FFT_RESULT_AVAILABLE ─-> render-time .frc de-embed in the pane (RAW accumulator preserved)
//
// The accumulator the worker produces is RAW: the measured .frc (loopback /
// filter frequency response) is subtracted at RENDER time from BOTH the trace
// AND the THD/IMD table (the cascade in dsp/frc.js, applied by
// fft/fft-view-correction.js) - it has NOTHING to do with
// the generator. Default (no .frc loaded) = no correction.
//
// WHICH device each seam gets is the injected AudioBackend's decision (audio/backend.js, the
// web's sound.AudioBackend): the engine asks it for a capture source per SharedCapture and for
// a playback sink per generator start, and never names a backend itself.

import { debug } from '../util/debug.js';
import { SharedCapture } from '../audio/shared-capture.js';
import { GeneratorController } from '../generator/generator-controller.js';
import { ScopeController } from '../scope/scope-controller.js';
import { FftController } from '../fft/fft-controller.js';

export class AudioEngine {
  /**
   * @param deps {prefs, backend}
   *   - prefs: the live Preferences - the ONE settings store every engine reads, live, the way
   *     each Java controller reads Preferences.instance() (there is no engine-side copy of it).
   *   - backend: the AudioBackend (audio/backend.js) that resolves the active backend's capture
   *     source and playback sink per open, enumerates its devices and parks the one being left.
   *     Built by the shell with the managers it owns.
   */
  constructor({ prefs, backend } = {}) {
    this._prefs = prefs;
    this._backend = backend;
    // Three independent lifecycles (Java: generator running / scope capturing / fft recording),
    // each owned by its module; `running` (getter below) = generator-on OR the shared capture is
    // open (refCount > 0).
    // Generator (gui/generator/GeneratorController): the DDS output path + file player + the
    // analysis freqs. The engine delegates the public API.
    this._gen = new GeneratorController(prefs, {
      status: (t) => this._status(t),
      // The DAC for the ACTIVE backend, resolved at EVERY start - exactly as Java re-resolves
      // AudioBackend.instance().openPlayback(device, rate, depth, dither) per start, so a backend
      // switch between sessions needs no push-down into the controller.
      openPlayback: (deps) => this._backend.openPlayback(deps),
    });
    // Shared capture (gui/sound/SharedCapture): the ONE ref-counted ADC device + ring. Every
    // consumer - the scope, the FFT, the FreqResp sweep's recording, the Tune-notch wizard's loop -
    // acquire()s/release()s its own reader over it, exactly as each Java consumer acquires
    // SharedCapture.instance() (ScopeController, FftAnalyzerWorker, SweepCapture,
    // NotchSweepEngine). A measurement is modal: it takes the device over only once the live
    // consumers have released it, so on a single-line device (the QA40x) nothing contends.
    this._capture = new SharedCapture({
      prefs,
      status: (t) => this._status(t),
      // The capture publishes CAPTURE_BATCH_AVAILABLE; ScopeController + FftController subscribe
      // and self-feed off their own cursors (no central router).
      publishBatch: true,
      // The FreqResp loopback recording tap - fed the same batch the ring was (see _recordBatch).
      onBatch: (d) => this._recordBatch(d),
      computeAnalysisFreqs: () => this._gen.computeAnalysisFreqs(),
      captureSource: this._backend.openCapture(),
    });
    // Scope consumer (gui/scope/ScopeController): a latest-window reader of the shared capture.
    // Both dual-tone refine seeds read LIVE per access, and they are ASKED for
    // rather than derived: the consumer requests GENERATOR_EMITTED_HZ at its own capture rate and
    // the generator answers (Java ScopeView.dualToneEmittedHz). That is why no generator getter is
    // injected here any more - an analyzer with no generator to ask has no emitted tone to report,
    // not a pair it may re-derive from the same preferences.
    this._scope = new ScopeController(this._capture, prefs);
    // FFT consumer (gui/fft/FftController): the worker pool + cross-frame coherent accumulator +
    // FLL steer + stop-after-N + the render-time .frc/mains de-embed. Reads/steers the generator.
    this._fft = new FftController(this._capture, this._gen, prefs, { status: (t) => this._status(t) });

    this.onStatus = null;   // (text) => void

    // Loopback recording tap (freqresp sweep capture): when set, the capture handler appends raw
    // L AND R channel samples to two growing chunk lists until stopCaptureRecording() - the stereo
    // sweep deconvolves both ADC channels against the same reference (Java FreqRespAnalyzer's
    // single stereo pass: rec.left() / rec.right()).
    this._recChunks = null;
    // The ring's own cursor for that recording (Java SweepCapture holds exactly this reader) and
    // the absolute ring position its first frame sits at - together they turn a ring position
    // into an offset into the recording (see dropRecordingBefore).
    this._recReader = null;
    this._recBasePos = 0;
  }

  /** The backend the engine is composed over (Java AudioBackend.instance(), injected here). */
  get backend() { return this._backend; }

  /** The AudioBackendType name currently in force - forwarded to the backend. */
  activeBackend() { return this._backend.activeBackend(); }

  /** True while the ACTIVE backend runs the generator (and the ADC) on another machine -
   *  forwarded to the backend. */
  isRemoteSession() { return this._backend.isRemoteSession(); }

  /** Enumerate the ACTIVE backend's devices - forwarded to the backend (see
   *  AudioBackend.scanDevices for the user-activation rule). */
  async scanDevices(fromUserGesture = false) { return this._backend.scanDevices(fromUserGesture); }

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
    return this._genOn || this._capture.refCount > 0;
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
      && !this._gen.running && !this._gen.filePlaying && !this._gen.outputContextOpen;
  }

  /** Forwards a control message to the running DDS generator worklet (freqresp
   *  sweep / predistortion compensation). No-op when the generator isn't running. */
  postGen(msg) {
    if (this._genOn && this.genNode) this.genNode.port.postMessage(msg);
  }

  /** Begins accumulating raw L AND R channel capture samples for a loopback recording (freqresp
   *  stereo sweep), tapped per batch by _recordBatch. Takes its OWN reference on the shared
   *  capture for the duration of the recording (released by stopCaptureRecording), so the device
   *  opens for the sweep when neither the scope nor the FFT holds it - Java SweepCapture.run
   *  acquires the same way. */
  async startCaptureRecording() {
    this._recChunks = { l: [], r: [] };
    const reader = await this._capture.acquire();
    this._recOwnsCapture = (reader != null);
    this._recReader = reader;
    this._rebaseRecording();
  }

  /** The ring's cursor for the in-flight recording - the reader Java's SweepCapture
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
    if (this._recOwnsCapture) { this._recOwnsCapture = false; await this._capture.release(); }
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

  /** Hands the active .frc calibration rows to the FFT analyzer, which cascades them into the
   *  de-embed it applies to every displayed frame (FftController.setCalibrationEntries). */
  setFftCalibrationEntries(entries) { this._fft.setCalibrationEntries(entries); }

  // The visible status line was removed; mirror status to the console for debugging (the hidden
  // #status aria-live element is still updated via onStatus for screen readers).
  _status(t) { debug('[audio]', t); if (this.onStatus) this.onStatus(t); }

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
  // `bitsPerSample` = the DECODED file's own depth, which the refusal compares against the
  // configured output: dropped here it renders as the word undefined and the depth half of that
  // check is dead in the shipped path.
  async playFileBuffer(channels, sampleRate, loop, file, bitsPerSample) {
    return this._gen.playFileBuffer(channels, sampleRate, loop, file, bitsPerSample);
  }
  async openSweepContext(requestedRate, opts) { return this._gen.openSweepContext(requestedRate, opts); }
  async playSweepBuffer(buf, sampleRate, opts) { return this._gen.playSweepBuffer(buf, sampleRate, opts); }
  setFilePlayLoop(loop) { this._gen.setFilePlayLoop(loop); }
  async stopFile() { return this._gen.stopFile(); }
  get filePlaying() { return this._gen.filePlaying; }
  /** Why the last file-playback attempt did not happen, localized - null when it did (Java
   *  GeneratorController.getFilePlayError). The pane's status line reads it after a play. */
  get filePlayError() { return this._gen.filePlayError; }

  // -------------------------------------------------------------------------
  // CAPTURE orchestration - the device + ring + refcount live in SharedCapture
  // (shared-capture.js, this._capture). What remains here is the engine-side
  // consumer wiring: the Preferences-dialog bracket + the loopback recording tap.
  // -------------------------------------------------------------------------

  /** Preferences-dialog bracket, phase 1 (Java MainWindow ->
   *  MultifunctionalTab.beforeApplyBackendChanges): STOP each consumer whose direction CHANGED,
   *  before the commit, while the current devices are still open - so every line closes cleanly.
   *  Gated per direction: the capture consumers (scope + FFT) bounce when captureChanged (each
   *  REALLY releases its own shared-capture ref; the one shared input device closes on the LAST
   *  release - the refcount handles it, no central teardown), and the generator bounces when
   *  outputChanged (it closes its own output context). Web Audio's input and output are SEPARATE
   *  devices, so an input-only change never disturbs the generator and vice-versa. Call BEFORE
   *  committing the new device/rate to the preferences; pair with afterApplyBackendChanges().
   *  A BACKEND change overrides the gating and bounces BOTH directions - see
   *  AudioBackend.switchPending(). */
  async beforeApplyBackendChanges(captureChanged, outputChanged) {
    const backendChanged = this._backend.switchPending();
    if (captureChanged || backendChanged) { await this._scope.stopCaptureForPrefs(); await this._fft.stopCaptureForPrefs(); }
    if (outputChanged || backendChanged) await this._gen.stopPlayForPrefs();
  }

  /** Preferences-dialog bracket, phase 2 (Java afterApplyBackendChanges): RESTART exactly what was
   *  running, on the just-committed preferences. Gated per direction: the capture consumers (scope + FFT)
   *  that were live re-acquire their refs when captureChanged - the shared input device reopens on
   *  the FIRST re-acquire at the committed input device / rate (refcount handles it, no
   *  central re-open) - and the generator restarts its playing engine (tone or retained file) when
   *  outputChanged, reopening its own output context. Input and output are separate devices, so
   *  each direction re-acquires only its own resource.
   *
   *  A BACKEND change additionally parks the backend being left, BEFORE anything restarts
   *  (AudioBackend.commitSwitch): it must run after phase 1 detached the lanes and before the new
   *  backend opens anything. */
  async afterApplyBackendChanges(captureChanged, outputChanged) {
    const backendChanged = this._backend.switchPending();
    await this._backend.commitSwitch();
    if (captureChanged || backendChanged) { await this._scope.startCaptureForPrefs(); await this._fft.startCaptureForPrefs(); }
    if (outputChanged || backendChanged) await this._gen.startPlayForPrefs();
  }

  /** The FreqResp stereo loopback recording tap, fed every batch the shared capture stages into
   *  its ring (SharedCapture.onBatch): keep BOTH ADC channels (L = ch0, R = ch1) for the
   *  deconvolution. Nothing to do while no recording is in flight. */
  _recordBatch(d) {
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
   *  (mirror SharedCapture.acquire - a direct call, not a bus round trip). Returns
   *  the SignalBufferReader, or null when the device could not be opened. The caller
   *  MUST pair each successful acquire with {@link #releaseCaptureReader}. */
  async acquireCaptureReader() { return this._capture.acquire(); }

  /** Releases one capture reference taken by {@link #acquireCaptureReader}
   *  (mirror SharedCapture.release). */
  async releaseCaptureReader() { return this._capture.release(); }

  /** The shared ring capacity in frames (BUFFER_SECONDS · inRate) - the streaming
   *  dispatch routes a request longer than this to the forward-record path. */
  getCaptureCapacity() { return this._capture.getCapacity(); }

  /** The last input-device open failure message (for the streaming-save error surface when
   *  acquireCaptureReader returns null, and the FreqResp / Tune-notch refusal when their
   *  acquire returns null). */
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
