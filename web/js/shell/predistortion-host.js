/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Browser-side PredistortionHost: bridges the faithful PredistortionEngine
// (predistortion/engine.js) to the live AudioEngine + the shell UI. The engine
// drives the closed loop (align -> average -> accumulate -> hot-apply -> settle);
// this host supplies the desktop's SWT collaborators as async methods:
//   - configureForRun -> infinite coherent generator-locked averaging
//   - readResult      -> FftResult.deepCopy of the last analyzed result
//   - imdPct          -> analyzeImd(...).imdPwrPct
//   - applyCompensation / applyDualToneCompensation -> post makeCompensation /
//     makeDualToneComp to the dds-processor
//   - correctionEntries -> loaded .frc store entries (for the .frc de-embed)
//   - anchors          -> Preferences getters

import { analyzeImd } from '../fft/imd-analyzer.js';
import { FftResult } from '../fft/fft-result.js';
import { makeCompensation, makeDualToneComp, isDualTone } from '../generator/dds-kernel.js';

/** The desktop's "infinite" coherent averaging - a true cumulative mean. The
 *  cross-tick accumulator (FftController) deepens the average tick by tick, so ∞
 *  needs no finite cap; the predistortion run counts depth via completedAnalyses(). */
const INFINITE_AVERAGES = Infinity;

export class PredistortionHost {
  /**
   * @param {import('../audio/backend.js').AudioEngine} engine the live engine
   * @param {object} prefs Preferences.instance()
   * @param {{getResult:()=>?object, restart:()=>Promise<void>,
   *          correctionEntries:Array}} bridge shell-supplied accessors:
   *        getResult -> the shell's latestResult; restart -> stop+readConfig+start;
   *        correctionEntries -> loaded .frc store ({calibration:{left,right}}[]).
   */
  constructor(engine, prefs, bridge) {
    this.engine = engine;
    this.prefs = prefs;
    this.bridge = bridge;
  }

  // ---- preference anchors ----
  maxHarmonics() { return Math.max(1, this.prefs.fftCalcMaxHarmonic.get()); }
  isDualTone() { return isDualTone(this.engine.config.form); }
  effectiveFrequency() { return this.engine.snapped || this.engine.config.toneHz; }
  adcFsVoltageRms() { return this.prefs.adcFsVoltageRms.get(); }
  genAmplitudeVrms() { return this.prefs.genAmplitudeVrms.get(); }
  dualToneSplitPct() { return this.prefs.genDualToneSplitPct.get(); }

  /** Loaded .frc store entries - the engine's calResponseAt reads these. */
  get correctionEntries() { return this.bridge.correctionEntries; }

  /** Infinite coherent generator-locked averaging: coherent on, FLL on, deep
   *  averaging window; restart the engine so the new window takes effect. */
  async configureForRun() {
    const c = this.engine.config;
    c.coherent = true;
    c.fllOn = true;
    c.averages = INFINITE_AVERAGES;
    // The predistortion run does its OWN per-round average counting; the FFT
    // pane's stop-after-N auto-stop must not tear the consumer down mid-round.
    c.stopAfterNEnabled = false;
    await this.bridge.restart();
    this.engine.resetAnalyses();
  }

  /** The FFT is already recording whenever the engine runs. */
  startRecording() {}

  /** Drop any compensation on the live generator. */
  clearCompensation() {
    this.engine.postGen({ clearCompensation: true });
  }

  resetStatistics() { this.engine.resetAnalyses(); }
  resetStatisticsAfterSignalChange() { this.engine.resetAnalyses(); }
  completedAnalyses() { return this.engine.completedAnalyses(); }

  /** Deep copy of the last analyzed result, or null. */
  readResult() {
    const r = this.bridge.getResult();
    if (!r) return null;
    // The live result the FFT pane holds may be a PLAIN object (a worker round-trip / the view
    // correction can strip the FftResult class) - it carries the data fields the predistortion
    // reads but no deepCopy METHOD. Re-adopt the FftResult prototype before copying so the
    // methods (deepCopy / rawHarmonicDbFs) are callable; a structuredClone fallback stripped
    // them, which was the very bug.
    return FftResult.adopt(r).deepCopy();
  }

  /** Combined intermod % of a dual-tone result. */
  imdPct(r) {
    const c = this.engine.config;
    const imd = analyzeImd(r, c.toneHz, c.tone2Hz, this.prefs.dbvOffsetDb);
    return imd ? imd.imdPwrPct : NaN;
  }

  /** Hot-apply a single-tone harmonic compensation to the generator. */
  applyCompensation(ampRatios, harmonicNumbers, phiInits) {
    this.engine.postGen({ compensation: makeCompensation(ampRatios, harmonicNumbers, phiInits) });
  }

  /** Hot-apply a dual-tone intermod compensation to the generator. */
  applyDualToneCompensation(ampRatios, coefA, coefB, phiInits) {
    this.engine.postGen({ dualToneCompensation: makeDualToneComp(ampRatios, coefA, coefB, phiInits) });
  }
}
