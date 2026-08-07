/*
 * Phonalyser web - AudioWorklet DDS generator processor.
 * Drives the headless DDS kernel (a faithful port of
 * org.edgo.audio.measure.generator.SignalGenerator) on the realtime audio
 * thread of the OUTPUT AudioContext. The main thread hot-swaps waveform,
 * frequency, amplitude, duty, dual-tone split, sweep params and harmonic /
 * intermod compensation live via the message port; the kernel preserves its
 * phase accumulator across every change so transitions stay phase-continuous -
 * exactly like the desktop generator. (Does NOT replace generator-processor.js,
 * the minimal sine used by the loopback null test.)
 * GNU AGPL v3 or later.
 */
import {
  DdsKernel,
  GenSignalForm,
  loadHarmonics,
  loadIntermod,
  isDualToneCorrectionFile,
  outputLaneGate,
  tpdfNoise,
} from '../../generator/dds-kernel.js';

class DdsProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super();
    const po = (options && options.processorOptions) || {};
    this._kernel = new DdsKernel({
      form: po.form || GenSignalForm.SINE,
      frequency: po.frequency != null ? po.frequency : 1000,
      sampleRate: sampleRate, // AudioWorkletGlobalScope sample rate
      amplitudeVRms: po.amplitudeVRms != null ? po.amplitudeVRms : 1.0,
      dacFsVoltageAmpl: po.dacFsVoltageAmpl != null ? po.dacFsVoltageAmpl : Math.sqrt(2.0),
    });
    // Output-lane routing (the interleave seam - Java PcmQuantizer). Live-updatable
    // via the port, like every other kernel tunable; the default 'BOTH' + 1.0 scale
    // reproduces the pre-feature both-lanes-identical output.
    this._outputChannels = po.outputChannels != null ? po.outputChannels : 'BOTH';
    this._rightLaneScale = po.rightLaneScale != null ? po.rightLaneScale : 1.0;
    // TPDF dither depth (bits, may be fractional; 0 = Off) applied LIVE to the mono sample before
    // the per-lane scale - Java PcmQuantizer (the generator's live-tunable dither). Live-updatable.
    this._ditherBits = po.ditherBits != null ? po.ditherBits : 0;
    this.port.onmessage = (e) => this._onMessage(e.data || {});
  }

  /**
   * Live-control protocol. Each message field maps to one DdsKernel setter;
   * absent fields are left unchanged. Compensation may be supplied either as a
   * prebuilt set ({amp,...}) or as a raw .dpd/CSV text body to be parsed here.
   */
  _onMessage(d) {
    const k = this._kernel;

    if (d.form != null) k.setForm(d.form);
    if (d.frequency != null) k.setFrequency(d.frequency);
    if (d.frequency2 != null) k.setDualToneFrequency2(d.frequency2);
    if (d.amplitudeVRms != null) k.setAmplitudeVrms(d.amplitudeVRms);
    if (d.dacFsVoltageAmpl != null) k.setDacFsVoltageAmpl(d.dacFsVoltageAmpl);
    if (d.rectDuty != null) k.setRectangleDuty(d.rectDuty);
    if (d.triDuty != null) k.setTriangleDuty(d.triDuty);
    if (d.dualAmp1Pct != null && d.dualAmp2Pct != null) {
      k.setDualToneAmplitudes(d.dualAmp1Pct, d.dualAmp2Pct);
    }

    if (d.linearSweep) {
      const s = d.linearSweep;
      k.configureLinearSweep(s.freqStart, s.freqEnd, s.periodSamples);
      k.setForm(GenSignalForm.LINEAR_SWEEP);
    }
    if (d.logSweep) {
      const s = d.logSweep;
      k.configureLogSweep(s.f0, s.f1, s.sweepSamples, s.leadInSamples);
      k.setForm(GenSignalForm.LOG_SWEEP);
    }
    if (d.sweepParams) {
      const s = d.sweepParams;
      k.setSweepParams(!!s.loop, s.fadeInSamples | 0, s.fadeOutSamples | 0);
    }
    if (d.resetSweepPosition) k.resetSweepPosition();

    // Compensation: prebuilt set or raw .dpd/CSV text.
    if (d.compensation) k.applyCompensation(d.compensation);
    if (d.dualToneCompensation) k.applyDualToneCompensation(d.dualToneCompensation);
    if (d.dpdText != null) {
      if (isDualToneCorrectionFile(d.dpdText)) {
        k.applyDualToneCompensation(loadIntermod(d.dpdText));
      } else {
        const freq = d.dpdFrequency != null ? d.dpdFrequency : (d.frequency != null ? d.frequency : 1000);
        k.applyCompensation(loadHarmonics(d.dpdText, freq));
      }
    }
    if (d.clearCompensation) k.clearCompensation();
    if (d.outputChannels != null) this._outputChannels = d.outputChannels;
    if (d.rightLaneScale != null) this._rightLaneScale = d.rightLaneScale;
    if (d.ditherBits != null) this._ditherBits = d.ditherBits;
  }

  process(_inputs, outputs) {
    const out = outputs[0];
    if (!out || out.length === 0) return true;
    const n = out[0].length;
    const k = this._kernel;
    // Interleave seam: write both lanes explicitly (this is why the node is opened
    // outputChannelCount [2] - a single mono lane up-mixed by the destination cannot
    // express per-lane values). Mirrors PcmQuantizer.encode: left lane = sample unless
    // gated to RIGHT, right lane = sample*rightLaneScale unless gated to LEFT, the
    // un-selected lane digital zero. Gate hoisted once per block (no per-sample alloc).
    const { wantL, wantR } = outputLaneGate(this._outputChannels);
    const scaleR = this._rightLaneScale;
    const dither = this._ditherBits;
    const ch0 = out[0];
    const ch1 = out.length > 1 ? out[1] : null;
    for (let i = 0; i < n; i++) {
      // TPDF dither added to the mono sample BEFORE the per-lane scale (Java PcmQuantizer:
      // dither then scale), so both lanes carry the same physical dither and it lands on the FFT
      // floor where the dBV view sets it. tpdfNoise is 0 for Off - guard to skip the call.
      let s = k.nextSample();
      if (dither > 0) s += tpdfNoise(dither);
      ch0[i] = wantL ? s : 0;
      if (ch1) ch1[i] = wantR ? s * scaleR : 0;
    }
    // Any lanes beyond the stereo pair mirror lane 0 (the pre-feature up-mix behaviour).
    for (let c = 2; c < out.length; c++) out[c].set(ch0);
    return true;
  }
}

registerProcessor('dds-processor', DdsProcessor);
