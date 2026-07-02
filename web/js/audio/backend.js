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
import { GeneratorController } from './generator-controller.js';
import { ScopeController } from './scope-controller.js';
import { FftController } from './fft-controller.js';
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
      onBatch: (d) => this._dispatchBatch(d),
      computeAnalysisFreqs: () => this._gen.computeAnalysisFreqs(),
    });
    // Scope consumer (gui/scope/ScopeController): a latest-window reader of the shared capture.
    this._scope = new ScopeController(this._capture, this.config, { getSnapped: () => this._gen.snapped });
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

  /** True when the generator is producing a signal OR the shared capture device
   *  is open (any consumer holds a reference). Mirrors SharedCapture.isCapturing
   *  ORed with the generator-running state; freqresp/predistortion gate on it. */
  get running() {
    return this._genOn || this._capture.refCount > 0;
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
    if (this._capture.refCount === 0) {
      const reader = await this._capture.acquire();
      this._recOwnsCapture = (reader != null);
    }
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
    if (this._recOwnsCapture) { this._recOwnsCapture = false; await this._capture.release(); }
    return { left, right };
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

  /** The per-analysis result callback + the stop-after-N auto-stop notification live on the FFT
   *  controller; forward so callers keep using engine.onResult / engine.onFftAutoStopped. */
  get onResult() { return this._fft.onResult; }
  set onResult(fn) { this._fft.onResult = fn; }
  get onFftAutoStopped() { return this._fft.onFftAutoStopped; }
  set onFftAutoStopped(fn) { this._fft.onFftAutoStopped = fn; }

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
  get genFreq() { return this._gen.genFreq; }
  set genFreq(v) { this._gen.genFreq = v; }
  get fllErrHz() { return this._gen.fllErrHz; }
  set fllErrHz(v) { this._gen.fllErrHz = v; }
  get fllLocked() { return this._gen.fllLocked; }
  set fllLocked(v) { this._gen.fllLocked = v; }
  get fllStable() { return this._gen.fllStable; }
  set fllStable(v) { this._gen.fllStable = v; }
  get rejectedCount() { return this._gen.rejectedCount; }
  set rejectedCount(v) { this._gen.rejectedCount = v; }

  async startGenerator() { return this._gen.startGenerator(); }

  async stopGenerator() { return this._gen.stopGenerator(); }
  retuneGenerator() { return this._gen.retuneGenerator(); }

  // -------------------------------------------------------------------------
  // FILE PLAYER — the monitoring "Load from…" lane lives in GeneratorController.
  // -------------------------------------------------------------------------

  async playFileBuffer(channels, sampleRate, loop) { return this._gen.playFileBuffer(channels, sampleRate, loop); }
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
    const scopeWas = this._scope.recording, fftWas = this._fft.recording, recWas = (this._recChunks != null);
    await this._capture.teardown();
    // Re-acquire once per previously-attached consumer so the refcount is restored.
    if (scopeWas) await this._scope.reattach();
    if (fftWas) await this._fft.reattach();
    if (recWas && this._recOwnsCapture) {
      const r = await this._capture.acquire();
      this._recOwnsCapture = (r != null);
    }
  }

  /** Per-batch consumer fan-out, invoked by SharedCapture after it stages the new samples into the
   *  shared ring: the loopback recording tap keeps BOTH ADC channels (the stereo sweep), then each
   *  active consumer reads off its OWN cursor. */
  _dispatchBatch(d) {
    // Loopback recording tap: keep BOTH ADC channels (L = ch0, R = ch1 = measured signal) so
    // the stereo sweep can deconvolve each side against the same reference.
    if (this._recChunks) {
      this._recChunks.l.push(Float64Array.from(d.l.subarray(0, d.n)));
      this._recChunks.r.push(Float64Array.from(d.r.subarray(0, d.n)));
    }
    if (this._scope.recording) this._scope.feedScope();
    if (this._fft.recording && !this._fft.pausedByStopN) this._fft.feedFft();
  }

  // -------------------------------------------------------------------------
  // SCOPE — the latest-window consumer lives in ScopeController (this._scope).
  // The engine delegates the public API; the capture fan-out drives feedScope().
  // -------------------------------------------------------------------------

  async setScopeRecording(on) { return this._scope.setRecording(on); }

  /** Acquires a fresh forward-read capture cursor over the shared ring for the
   *  scope stream-forward record, opening the device if no consumer holds it yet
   *  (mirror MessageBus.request(CAPTURE_ACQUIRE)). Returns the SignalBufferReader,
   *  or null when the device could not be opened. The caller MUST pair each
   *  successful acquire with {@link #releaseCaptureReader}. */
  async acquireCaptureReader() { return this._capture.acquire(); }

  /** Releases one capture reference taken by {@link #acquireCaptureReader}
   *  (mirror MessageBus.publish(CAPTURE_RELEASE)). */
  async releaseCaptureReader() { return this._capture.release(); }

  /** The shared ring capacity in frames (BUFFER_SECONDS · inRate) — the streaming
   *  dispatch routes a request longer than this to the forward-record path. */
  getCaptureCapacity() { return this._capture.getCapacity(); }

  /** The last input-device open failure message (for the streaming-save error
   *  surface when acquireCaptureReader returns null). */
  getLastStartError() { return this._capture.getLastStartError(); }

  readMeasurementWindow() { return this._scope.readMeasurementWindow(); }
  readMeasurementGap() { return this._scope.readMeasurementGap(); }
  readZoomedWindow() { return this._scope.readZoomedWindow(); }
  get onScope() { return this._scope.onScope; }
  set onScope(fn) { this._scope.onScope = fn; }



  // -------------------------------------------------------------------------
  // FFT — the contiguous-cursor consumer (worker pool + accumulator + FLL + the
  // render-time .frc/mains de-embed) lives in FftController (this._fft). The
  // engine delegates the public API; the capture fan-out drives feedFft().
  // -------------------------------------------------------------------------

  async setFftRecording(on) { return this._fft.setRecording(on); }

  /** Selects which ADC channel the FFT analyzes (L → ch0, R → ch1); a real
   *  change resets the statistics + accumulator (Java FftView button →
   *  setFftChannel → resetStatistics). Sync — delegates to the FFT controller. */
  setFftChannel(ch) { return this._fft.setFftChannel(ch); }






  // -------------------------------------------------------------------------
  // Fused convenience lifecycle — generator + both consumers in one call. Kept
  // for the freqresp / predistortion hosts and the structural-restart paths that
  // drive the whole pipeline; the per-pane Record controls drive each consumer
  // independently via setScopeRecording / setFftRecording.
  // -------------------------------------------------------------------------

  async start() {
    await this.startGenerator();
    await this.setScopeRecording(true);
    await this.setFftRecording(true);
  }

  async stop() {
    await this.stopGenerator();
    await this.setScopeRecording(false);
    await this.setFftRecording(false);
    this._status('stopped.');
  }
}
