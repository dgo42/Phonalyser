/*
 * Phonalyser web — AudioWorklet DDS generator processor.
 * Drives the headless DDS kernel (a faithful port of
 * org.edgo.audio.measure.generator.SignalGenerator) on the realtime audio
 * thread of the OUTPUT AudioContext. The main thread hot-swaps waveform,
 * frequency, amplitude, duty, dual-tone split, sweep params and harmonic /
 * intermod compensation live via the message port; the kernel preserves its
 * phase accumulator across every change so transitions stay phase-continuous —
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
    this.port.onmessage = (e) => this._onMessage(e.data || {});
  }

  /**
   * Live-control protocol. Each message field maps to one DdsKernel setter;
   * absent fields are left unchanged. Compensation may be supplied either as a
   * prebuilt set ({amp,…}) or as a raw .dpd/CSV text body to be parsed here.
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
  }

  process(_inputs, outputs) {
    const out = outputs[0];
    if (!out || out.length === 0) return true;
    const n = out[0].length;
    const k = this._kernel;
    const ch0 = out[0];
    for (let i = 0; i < n; i++) ch0[i] = k.nextSample();
    for (let c = 1; c < out.length; c++) out[c].set(ch0); // same signal on all channels
    return true;
  }
}

registerProcessor('dds-processor', DdsProcessor);
