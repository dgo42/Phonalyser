/*
 * Phonalyser web — render-time FFT spectral corrections (the VIEW side).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 *
 * Faithful port of the FftView render-time transforms. These belong to the VIEW, not the audio
 * engine: the FFT controller's coherent accumulator stays RAW, and this applies — once per
 * displayed frame, on the controller's per-frame result copy — the loaded .frc de-embed
 * (+ recomputeStats), the tracked IIR-comb mains correction, and the dual-tone IMD table. The
 * IMD is computed off the DE-EMBEDDED spectrum, so the three transforms run together here (the
 * order .frc → mains → IMD is load-bearing). The engine no longer touches calibration.
 */
import { FftAnalyzer } from './fft-analyzer.js';
import { applyCompensationInPlace } from './fft-compensation.js';
import { analyzeImd } from './imd-analyzer.js';
import { isDualTone } from '../generator/dds-kernel.js';
import { interpolate } from '../dsp/frc.js';

export class FftViewCorrection {
  /** @param config the shared engine config (read for the IMD tone params + dual-tone form). */
  constructor(config) {
    this.config = config;
    // Ordered cascade of active .frc de-embeds, each {left, right, correctAllBins}. Multiple loaded
    // files compose in order (mirroring FreqRespCorrectionStore's chain); apply() picks left/right per
    // r.channelLeft (Java FftView:853-859). correctAllBins is the per-file "With noise" flag (every FFT
    // bin incl. the noise floor vs harmonic/dot bins only).
    this.frcEntries = [];
    this._compAnalyzer = new FftAnalyzer();   // used only for recomputeStats on the de-embedded copy
  }

  /** Replaces the active de-embed cascade from the FFT calibration rows / store entries
   *  (shape [{calibration:{left,right}, withNoise}]); each is de-embedded against the analysed
   *  channel (r.channelLeft → left/right), in order, so multiple loaded files compose into one
   *  correction. */
  setFrcEntries(entries) {
    this.frcEntries = (entries || [])
      .filter((e) => e && e.calibration && e.calibration.left)
      .map((e) => ({ left: e.calibration.left, right: e.calibration.right || e.calibration.left, correctAllBins: !!e.withNoise }));
  }

  /** Single-file shim (the calibration wizard / a one-off load); clears with null.
   *  `correctAllBins` is the "With noise" flag. */
  setFrcCalibration(stereo, correctAllBins = false) {
    this.frcEntries = (stereo && stereo.left) ? [{ left: stereo.left, right: stereo.right || stereo.left, correctAllBins }] : [];
  }

  /** Applies the render-time transforms to the RAW result IN PLACE (the worker accumulator stays
   *  raw; this mutates the displayed result only). Call once per frame before the FFT view paints.
   *
   *  1. .frc de-embed — subtract the loaded filter response from BOTH the trace AND the THD/IMD
   *     table; applyCompensationInPlace also recomputes the fundamental/harmonics/THD off the
   *     corrected spectrum.
   *  2. IIR-comb mains — divide the comb response tracked by the FFT controller (attached to the
   *     result as `_mainsComb` / `_mainsF0`) out of the displayed spectrum.
   *  3. Dual-tone IMD — computed off the de-embedded spectrum (null in THD mode). */
  apply(r) {
    if (!r) return;
    const c = this.config;
    // Java FftView:853-859: wantLeft = r.channelLeft; cal = wantLeft ? calibration.left() : right().
    for (const e of this.frcEntries) {
      const cal = r.channelLeft ? e.left : e.right;
      applyCompensationInPlace(r, cal, e.correctAllBins, this._compAnalyzer);
    }
    if (r._mainsF0 > 0 && r._mainsComb && r.freqResolution > 0) {
      r._mainsComb.applySpectrumCorrection(r.amplitudeDbFs, null, r.freqResolution, r._mainsF0);
    }
    r.imd = isDualTone(c.form) ? analyzeImd(r, c.toneHz, c.tone2Hz, c.dbvOffsetDb) : null;
  }

  /** Cumulative calibration dB lift at {@code freqHz} across the active de-embed cascade — how
   *  many dB the correction adds at this freq going raw → corrected. Adding it back to a post-cal
   *  level recovers the pre-cal (BLUE-dot) level. Empty cascade / non-positive freq ⇒ 0 dB. Picks
   *  each entry's left/right cal by {@code wantLeft} (Java FftView.sumCalDbAt:2058-2070). */
  sumCalDbAt(wantLeft, freqHz) {
    if (!(freqHz > 0.0)) return 0.0;
    let sum = 0.0;
    for (const e of this.frcEntries) {
      const cal = wantLeft ? e.left : e.right;
      const m = interpolate(cal, freqHz)[0];
      sum += (m > 0.0) ? 20.0 * Math.log10(m) : -300.0;
    }
    return sum;
  }
}
