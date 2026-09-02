/*
 * Phonalyser web - render-time FFT spectral corrections (the VIEW side).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Port of the FftView render-time transforms that STAY on this side
 * (org.edgo.audio.measure.gui.fft.FftView.finalizeResult): the dual-tone IMD table, computed
 * off the de-embedded spectrum, and the calibration-gain lookup its blue "before-cal" dots
 * read. The engine never touches calibration.
 *
 * PORT DIVERGENCE, deliberate: the desktop's view owns the held .frc cascade and applies the
 * de-embed, the plot-time mains comb and the statistics recompute itself, on the UI thread it
 * has to spare. On the web that thread IS the one the analysis competes with, so those three
 * moved into the analyzer worker (fft/fft-worker.js), which hands over a finished display
 * spectrum. What is left here needs the emitted-tone request, which lives on this side.
 *
 * The blue dots of a SINGLE-tone chart arrive with the result (preCorrectionPeaks). A DUAL-tone
 * chart draws them at the intermod product frequencies, which only the IMD table below knows,
 * so a dual-tone frame with rows loaded carries the cascade's gain per bin and this class holds
 * it for the chart to look products up in.
 */
import { analyzeImd } from './imd-analyzer.js';
import { isDualTone } from '../generator/dds-kernel.js';
import { MessageBus } from '../bus/message-bus.js';
import { Events } from '../bus/events.js';

export class FftViewCorrection {
  /**
   * @param config the shared engine config (read for the IMD tone params + dual-tone form).
   * @param store  the shared FFT CorrectionStore this VIEW READS - the ordered cascade of active
   *   .frc de-embeds owned by the FFT side (Java FftView reads FftController.getCorrectionStore()).
   *   Each entry is {calibration:{left,right}, path, withNoise}, picked left/right per
   *   r.channelLeft (Java FftView.buildCalInverse); withNoise is the per-file "With noise" flag
   *   (every FFT bin incl. the noise floor vs the tone lobes only). The store is mutated by
   *   FftTabControl (the calibration rows); this class never writes it.
   */
  constructor(config, store) {
    this.config = config;
    this.store = store;
    /** The cascade's gain per bin as the analyzer last sampled it, or null when no dual-tone
     *  frame has carried one. Held across frames: the analyzer sends it with every finalized
     *  dual-tone frame that has rows loaded, and it is dropped wherever the cascade behind it is. */
    this._calGainDb = null;
    // A committed audio-format change moves the capture rate the response was sampled at, so the
    // held gain is dropped and the first frame at the new rate carries a fresh one (Java FftView's
    // AUDIO_FORMAT_CHANGED subscriber drops the cascade itself, which is now the analyzer's).
    MessageBus.instance().subscribe(Events.AUDIO_FORMAT_CHANGED, () => this.dropHeld());
    // Every FFT restart-from-zero - the Reset button, a channel switch, a window / averaging /
    // alignment change, a re-calibration, a generated-signal change, a fresh Record session -
    // announces itself on the bus. The analysed channel and the frame's tone positions both move
    // with a restart, so what was sampled from the cascade goes with it.
    MessageBus.instance().subscribe(Events.FFT_STATISTICS_RESET, () => this.dropHeld());
    // The loaded cascade itself changed - a row loaded, cleared, toggled (Java
    // FftView.onCalibrationChanged): what was sampled from the old cascade goes with it.
    MessageBus.instance().subscribe(Events.FFT_CALIBRATION_CHANGED, () => this.dropHeld());
  }

  /** Drops the held calibration gain so the next dual-tone frame supplies a fresh one - on a
   *  store mutation, and on the statistics-reset paths, which move the analysed channel or
   *  the FFT length under it. */
  dropHeld() {
    this._calGainDb = null;
  }

  /** The view-side part of the render-time transforms, once per frame before the FFT view paints.
   *  The de-embed, the mains comb and the statistics recompute already ran in the analyzer; what
   *  is left needs this side:
   *
   *  1. the calibration gain the analyzer sampled for this frame, kept for the chart's dual-tone
   *     blue dots;
   *  2. the dual-tone IMD table, computed off the de-embedded spectrum (null in THD mode). */
  apply(r) {
    if (!r) return;
    const c = this.config;
    if (r.calGainDb) this._calGainDb = r.calGainDb;
    // The IMD product grid is placed at what the generator is EMITTING, asked at this result's
    // rate - never at the raw entered tones, which ignore the bin snap and would put every
    // product a fraction of a bin off (Java FftController.analyzeImd). No responder -> no IMD
    // result at all, which every caller already reads as "no dual-tone result".
    const emitted = MessageBus.instance().request(Events.GENERATOR_EMITTED_HZ, r.sampleRate);
    r.imd = (isDualTone(c.form) && emitted != null)
      ? analyzeImd(r, emitted[0], emitted[1], c.dbvOffsetDb) : null;
  }

  /** The dB the loaded calibration cascade takes off the bin nearest {@code hz} - what a level
   *  measured after the correction has to gain back to read as it was measured. 0 with no gain
   *  held (an uncorrected spectrum's blue dot coincides with the red one) and for a bin no row
   *  reached. Java FftView.calGainDbAt, reading the cascade its own view holds. */
  calGainDbAt(r, hz) {
    if (this._calGainDb == null || !(hz > 0.0)) return 0.0;
    const bin = Math.round(hz / (r.sampleRate / r.fftSize));
    return (bin >= 1 && bin < this._calGainDb.length) ? this._calGainDb[bin] : 0.0;
  }
}
