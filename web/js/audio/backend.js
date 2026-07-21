/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// AudioEngine — the live measurement pipeline, DECOUPLED exactly like the Java
// desktop (gui.generator.GeneratorPane + gui.sound.SharedCapture +
// gui.scope.ScopeController + gui.fft.FftController):
//
//   • GENERATOR — an independent DDS→DAC lifecycle (startGenerator/stopGenerator);
//     owns the output AudioContext + the dds-processor worklet. Has NOTHING to do
//     with capture (mirrors GeneratorPane / GeneratorController).
//   • SHARED CAPTURE — a single ref-counted ADC capture (mirror SharedCapture):
//     the input AudioContext + capture-processor open on the first acquire and
//     close on the last release; one shared SignalBuffer(inRate, 22 s) ring is fed
//     by the capture handler. Each consumer gets its OWN SignalBufferReader cursor.
//   • SCOPE CONSUMER — "latest window" reader: every batch, readLatest(SCOPE_LEN)
//     of the R channel (ch1) → onScope (mirror ScopeController, overrun-safe).
//   • FFT CONSUMER — contiguous-cursor reader: every batch, read() the new samples
//     and feed the buffer-and-analyze accumulate+dispatch loop; OVERRUN re-anchors
//     and resets fill (mirror FftController + the cross-tick coherent accumulator).
//
// The FFT worker/pool (the faithful brain) is unchanged:
//        ─→ [FftAnalyzer.analyze in a Worker]  (windowed FFTs + coherent/incoherent
//           cross-frame averaging + fundamental/harmonics/THD/SNR)
//        ─→ FftResult ─→ FLL(steer generator off fundamentalHzRefined)
//        ─→ render-time .frc de-embed (RAW accumulator preserved) ─→ onResult(view)
//
// The accumulator the worker produces is RAW: the measured .frc (loopback /
// filter frequency response) is subtracted at RENDER time from BOTH the trace
// AND the THD/IMD table (fft/fft-compensation.js) — it has NOTHING to do with
// the generator. Default (no .frc loaded) = no correction.

import { GenSignalForm } from '../generator/dds-kernel.js';
import { debug } from '../util/debug.js';
import { SharedCapture } from './shared-capture.js';
import { GeneratorController } from '../generator/generator-controller.js';
import { ScopeController } from '../scope/scope-controller.js';
import { FftController } from '../fft/fft-controller.js';
import { scanDevices as scanAudioDevices } from './devices.js';

const HARMONIC_COUNT = 9;                  // H2..H10 default (overridable via config.harmonicCount)
/** DAC full-scale PEAK voltage (= FS-sine RMS × √2). Default when no preferences anchor. */
const DAC_FS_VOLTAGE_AMPL = Math.sqrt(2.0);



export class AudioEngine {
  constructor() {
    this.config = {
      inDeviceId: '', inRate: 384000,
      outDeviceId: '', outRate: 384000,
      toneHz: 1000, ampVrms: 0.5,   // canonical V RMS (matches the UI amplitude field + ampVrmsOf); not the legacy ampDbfs

      form: GenSignalForm.SINE, tone2Hz: 1100, amp1Pct: 50, amp2Pct: 50,
      rectDuty: 0.5, triDuty: 0.5, ditherBits: 0, snapToBin: true,
      // Sweep params (LINEAR_SWEEP / LOG_SWEEP) — durations in seconds, freqs in Hz.
      sweepStartHz: 20, sweepEndHz: 20000, sweepDurationSec: 1.0,
      sweepFadeInSec: 0.01, sweepFadeOutSec: 0.01, sweepLoop: true,
      fftSize: 65536, window: 'BH4', averages: 16, overlap: 'PCT_0', coherent: true,
      fftFundFromGenerator: false, snrFreqMin: 0, snrFreqMax: 0,   // THD band (0 = full band)
      stopAfterNEnabled: false, stopAfterN: 10,   // auto-stop once N averages collected (Java FftAnalyzerWorker)
      mainsSuppression: 'NONE',   // None / IIR_COMB / SYNC_SUBTRACT / LMS (Java MainsSuppression)
      threads: Math.max(1, (navigator.hardwareConcurrency || 4) - 2),
      warmupMs: 500, fllOn: true,
      channel: 'L',   // FFT-analyzed ADC channel (Java prefs.getFftChannel default L → ch0); readConfig overrides from prefs.fftChannel
      // Calibration anchors (Preferences supplies these; defaults until wired).
      dacFsVoltageAmpl: DAC_FS_VOLTAGE_AMPL, harmonicCount: HARMONIC_COUNT,
      adcFsVoltageRms: 1.0, dbvOffsetDb: 0.0,
      // Live scope time/div (s) — sizes the captured scope window (see _scopeWindowLen).
      scopeTimePerDiv: 0.001,
    };
    // Three independent lifecycles (Java: generator running / scope capturing / fft recording),
    // each owned by its module; `running` (getter below) = generator-on OR the shared capture is
    // open (refCount > 0).
    // Generator (gui/generator/GeneratorController): the DDS output path + file player + the
    // analysis freqs. Holds the SHARED config by reference; the engine delegates the public API.
    this._gen = new GeneratorController(this.config, { status: (t) => this._status(t) });
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
    });
    // DEDICATED MEASUREMENT capture (Java CaptureWithGenerator / NotchSweepEngine each open
    // their OWN device line): the FreqResp sweep + Tune-notch wizard acquire/release THIS
    // instance — a SEPARATE input device line from the scope/FFT _capture above — so a
    // measurement never rides the live consumers' ring. Its batch fan-out is the FreqResp
    // loopback recording tap only (the Tune-notch wizard reads this ring via its own cursor).
    this._measCapture = new SharedCapture({
      getConfig: () => this.config,
      status: (t) => this._status(t),
      onBatch: (d) => this._dispatchMeasBatch(d),
      computeAnalysisFreqs: () => this._gen.computeAnalysisFreqs(),
    });
    // Scope consumer (gui/scope/ScopeController): a latest-window reader of the shared capture.
    // Both dual-tone refine seeds read LIVE per access — tone 1 via _genEmitFreq(), tone 2 via
    // _genEmitFreq2() — never the cached gen.snapped field (which computeAnalysisFreqs only
    // repopulates at capture/generator start). The seeds must track the live, bound generator
    // settings (user rule: bidi-binding semantics, no caching), closing the stale-seed window
    // that a stopped/edited generator would otherwise leave in gen.snapped. Mirrors Java
    // ScopeMeasurementWorker re-reading the generator prefs live each measurement pass. The
    // cached gen.snapped stays as-is for the FFT/scope geometry consumers.
    this._scope = new ScopeController(this._capture, this.config, {
      getSnapped: () => this._gen._genEmitFreq(),
      getSnapped2: () => this._gen._genEmitFreq2(),
    });
    // FFT consumer (gui/fft/FftController): the worker pool + cross-frame coherent accumulator +
    // FLL steer + stop-after-N + the render-time .frc/mains de-embed. Reads/steers the generator.
    this._fft = new FftController(this._capture, this._gen, this.config, { status: (t) => this._status(t) });

    this.onStatus = null;   // (text) => void  (onResult / onFftAutoStopped delegate to this._fft)

    // Loopback recording tap (freqresp sweep capture): when set, the capture handler appends raw
    // L AND R channel samples to two growing chunk lists until stopCaptureRecording() — the stereo
    // sweep deconvolves both ADC channels against the same reference (Java FreqRespAnalyzer's
    // single stereo pass: rec.left() / rec.right()).
    this._recChunks = null;
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

  /** True when BOTH the input and output devices are genuinely free — no consumer holds the
   *  shared capture, the input AudioContext has fully closed, the generator is idle and no
   *  output context (tone / file / sweep) is still open. The measurement idle-wait
   *  (worker-idle.js) polls THIS rather than {@link #running}: stopGenerator()/release() flip
   *  their flags SYNCHRONOUSLY but the ctx.close() that actually releases the OS device resolves
   *  tens of ms later, so a flag-only wait let the takeover reopen the device mid-close →
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
    // Open our OWN measurement device line (device-isolated from scope/FFT). A measurement is
    // modal, so _measCapture.refCount is 0 here and we always acquire our own reference.
    if (this._measCapture.refCount === 0) {
      const reader = await this._measCapture.acquire();
      this._recOwnsCapture = (reader != null);
    }
  }

  /** Drops the samples accumulated so far in the in-flight loopback recording, keeping the
   *  capture open. The FreqResp sweep calls this AFTER the cold-device warmup so the
   *  recorded (and live-metered) window starts clean at the sweep — the warmup silence is
   *  discarded. Without it that silence sat at the front of the recording, shifting the
   *  busy meter's time axis and pushing the sweep's end past the meter's totalSec span
   *  (the progress trace started too early and never reached the right edge). No-op when no
   *  recording is in flight. */
  resetCaptureRecording() {
    if (this._recChunks) this._recChunks = { l: [], r: [] };
  }

  /** Ends a loopback recording and returns BOTH concatenated ADC channels
   *  ({left, right}: Float64Array) — mirroring StereoSamples from the Java analyzer's
   *  single stereo capture pass. Releases the recording's own capture reference if it
   *  acquired one. */
  async stopCaptureRecording() {
    const rec = this._recChunks || { l: [], r: [] };
    this._recChunks = null;
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
   *  (CaptureWithGenerator.runStereo) — the web records in one shot but the chunks grow
   *  batch-by-batch, so a poller can read the newly-arrived tail as it lands. */
  recordingLength() {
    if (!this._recChunks) return 0;
    let n = 0;
    for (const c of this._recChunks.r) n += c.length;
    return n;
  }

  /** Linear RMS of max(L,R) over the recorded window [fromFrame, fromFrame+count) of the
   *  in-flight loopback recording — the per-block level the desktop feeds its live meter
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

  /** Fraction (0..1) of the current FFT frame already captured (−1 on overrun) —
   *  drives the fill-% indicator (Java FftAnalyzerWorker.getNextFrameProgress). */
  nextFrameProgress() { return this._fft.nextFrameProgress(); }

  /** Resets the accumulated-averages counter (predistortion round boundary). */
  resetAnalyses() { this._fft.resetAnalyses(); }

  /** #24 follow-up: re-emits the display from the RAW FFT accumulator so a calibration
   *  change re-applies the current correction cascade while STOPPED (FftController). */
  reemitFftDisplay(base) { return this._fft.reemitDisplay(base); }

  // The visible status line was removed; mirror status to the console for debugging (the hidden
  // #status aria-live element is still updated via onStatus for screen readers).
  _status(t) { debug('[audio]', t); if (this.onStatus) this.onStatus(t); }

  /** Enumerate input/output devices, each input carrying its probed native rate. Device
   *  enumeration is its own layer (devices.js), not part of the capture/generate engine. */
  scanDevices() { return scanAudioDevices((t) => this._status(t)); }

  // -------------------------------------------------------------------------
  // GENERATOR — the DDS output path + file player live in GeneratorController
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
  // FLL state (genFreq/fllErrHz/fllLocked/fllStable/rejectedCount) now lives on FftController;
  // the FFT loop publishes GENERATOR_FREQ_TRIM and the generator applies it. Not exposed here —
  // the display reads it off the emitted FftResult.fll.

  async startGenerator() { return this._gen.startGenerator(); }

  async stopGenerator() { return this._gen.stopGenerator(); }
  retuneGenerator() { return this._gen.retuneGenerator(); }

  // -------------------------------------------------------------------------
  // FILE PLAYER — the monitoring "Load from…" lane lives in GeneratorController.
  // -------------------------------------------------------------------------

  async playFileBuffer(channels, sampleRate, loop) { return this._gen.playFileBuffer(channels, sampleRate, loop); }
  async openSweepContext(requestedRate) { return this._gen.openSweepContext(requestedRate); }
  async playSweepBuffer(buf, sampleRate, opts) { return this._gen.playSweepBuffer(buf, sampleRate, opts); }
  setFilePlayLoop(loop) { this._gen.setFilePlayLoop(loop); }
  async stopFile() { return this._gen.stopFile(); }
  get filePlaying() { return this._gen.filePlaying; }
  get onFileEnded() { return this._gen.onFileEnded; }
  set onFileEnded(fn) { this._gen.onFileEnded = fn; }

  // -------------------------------------------------------------------------
  // CAPTURE orchestration — the device + ring + refcount live in SharedCapture
  // (shared-capture.js, this._capture). What remains here is the engine-side
  // consumer wiring: reopen-on-device-change + the per-batch fan-out to the
  // loopback rec tap / scope / FFT.
  // -------------------------------------------------------------------------

  /** Forces the shared capture device to close and reopen with the CURRENT
   *  config.inDeviceId / inRate, re-issuing fresh readers to every active
   *  consumer (scope / FFT / loopback recording) so a live input-device change
   *  takes effect immediately — no page reload, no stop/start. No-op when the
   *  device isn't open (the NEXT acquire already uses the new id via readConfig).
   *  Mirrors the Java behaviour where reopening the SharedCapture re-attaches the
   *  consumers' SignalBufferReaders to the fresh ring. */
  async reopenCaptureDevice() {
    if (this._capture.refCount === 0) return;   // closed → next start picks up the new id
    // Remember which consumers were attached; teardown drops the refcount to 0.
    const scopeWas = this._scope.recording, fftWas = this._fft.recording;
    await this._capture.teardown();
    // Re-acquire once per previously-attached consumer so the refcount is restored.
    // Only the LIVE scope/FFT capture is reopened here; a measurement runs on its own device
    // line (_measCapture) and is modal, so it is never live during a device-selector change.
    if (scopeWas) await this._scope.reattach();
    if (fftWas) await this._fft.reattach();
  }

  /** Preferences-dialog bracket, phase 1 (Java MainWindow →
   *  MultifunctionalTab.beforeApplyBackendChanges): STOP each consumer whose direction CHANGED,
   *  before the commit, while the current devices are still open — so every line closes cleanly.
   *  Gated per direction: the capture consumers (scope + FFT) bounce when captureChanged (each
   *  REALLY releases its own shared-capture ref; the one shared input device closes on the LAST
   *  release — the refcount handles it, no central teardown), and the generator bounces when
   *  outputChanged (it closes its own output context). Web Audio's input and output are SEPARATE
   *  devices, so an input-only change never disturbs the generator and vice-versa. Call BEFORE
   *  committing the new device/rate to config; pair with afterApplyBackendChanges(). */
  async beforeApplyBackendChanges(captureChanged, outputChanged) {
    if (captureChanged) { await this._scope.stopCaptureForPrefs(); await this._fft.stopCaptureForPrefs(); }
    if (outputChanged) await this._gen.stopPlayForPrefs();
  }

  /** Preferences-dialog bracket, phase 2 (Java afterApplyBackendChanges): RESTART exactly what was
   *  running, on the just-committed config. Gated per direction: the capture consumers (scope + FFT)
   *  that were live re-acquire their refs when captureChanged — the shared input device reopens on
   *  the FIRST re-acquire at the committed config.inDeviceId / inRate (refcount handles it, no
   *  central re-open) — and the generator restarts its playing engine (tone or retained file) when
   *  outputChanged, reopening its own output context. Input and output are separate devices, so
   *  each direction re-acquires only its own resource. */
  async afterApplyBackendChanges(captureChanged, outputChanged) {
    if (captureChanged) { await this._scope.startCaptureForPrefs(); await this._fft.startCaptureForPrefs(); }
    if (outputChanged) await this._gen.startPlayForPrefs();
  }

  /** Per-batch fan-out for the dedicated MEASUREMENT capture (_measCapture): the FreqResp
   *  stereo loopback recording tap — keep BOTH ADC channels (L = ch0, R = ch1) for the
   *  deconvolution. The Tune-notch wizard reads the measurement ring through its own cursor,
   *  so it needs nothing here. */
  _dispatchMeasBatch(d) {
    if (this._recChunks) {
      this._recChunks.l.push(Float64Array.from(d.l.subarray(0, d.n)));
      this._recChunks.r.push(Float64Array.from(d.r.subarray(0, d.n)));
    }
  }

  // -------------------------------------------------------------------------
  // SCOPE — the latest-window consumer lives in ScopeController (engine.scope).
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
   *  live sweep loop) — its OWN device line, isolated from the scope/FFT capture above.
   *  Returns the SignalBufferReader or null; pair with {@link #releaseMeasurementReader}. */
  async acquireMeasurementReader() { return this._measCapture.acquire(); }

  /** Releases one reference on the measurement capture ({@link #acquireMeasurementReader}). */
  async releaseMeasurementReader() { return this._measCapture.release(); }

  /** The last MEASUREMENT-device open failure message (surfaced when a FreqResp / Tune-notch
   *  capture fails to open) — the measurement capture's error, not the scope/FFT one. */
  getMeasurementStartError() { return this._measCapture.getLastStartError(); }

  /** The shared ring capacity in frames (BUFFER_SECONDS · inRate) — the streaming
   *  dispatch routes a request longer than this to the forward-record path. */
  getCaptureCapacity() { return this._capture.getCapacity(); }

  /** The last input-device open failure message (for the streaming-save error
   *  surface when acquireCaptureReader returns null). */
  getLastStartError() { return this._capture.getLastStartError(); }

  readZoomedWindow() { return this._scope.readZoomedWindow(); }



  // -------------------------------------------------------------------------
  // FFT — the contiguous-cursor consumer (worker pool + accumulator + FLL + the
  // render-time .frc/mains de-embed) lives in FftController (engine.fft). The FFT
  // pane drives its record lifecycle + channel directly; the cross-cutting stats
  // API (completedAnalyses / resetAnalyses / nextFrameProgress / reemitFftDisplay)
  // stays a thin service forward above.
  // -------------------------------------------------------------------------






  // -------------------------------------------------------------------------
  // Fused convenience lifecycle — generator + both consumers in one call. Kept
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
