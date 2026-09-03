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
import { makeCompensation, makeDualToneComp } from '../generator/dds-kernel.js';
import { isDualTone } from '../enums/gen-signal-form.js';

/** The desktop's "infinite" coherent averaging - a true cumulative mean. The
 *  cross-tick accumulator (FftController) deepens the average tick by tick, so ∞
 *  needs no finite cap; the predistortion run counts depth via completedAnalyses(). */
const INFINITE_AVERAGES = Infinity;

export class PredistortionHost {
  /**
   * @param {import('./audio-engine.js').AudioEngine} engine the live engine
   * @param {object} prefs Preferences.instance()
   * @param {{getResult:()=>?object, restart:()=>Promise<void>,
   *          correctionEntries:Array}} bridge shell-supplied accessors:
   *        getResult -> the shell's latestResult; restart -> stop+start on the written prefs;
   *        correctionEntries -> loaded .frc store ({calibration:{left,right}}[]).
   */
  constructor(engine, prefs, bridge) {
    this.engine = engine;
    this.prefs = prefs;
    this.bridge = bridge;
  }

  // ---- preference anchors ----
  maxHarmonics() { return Math.max(1, this.prefs.fftCalcMaxHarmonic.get()); }
  isDualTone() { return isDualTone(this.prefs.genSignalForm.get()); }
  effectiveFrequency() { return this.engine.snapped || this._toneHz(); }
  /** The entered tone 1 - the dual-tone pair's first tone in a dual-tone form, else the
   *  single-tone frequency (each form reads its own preference). */
  _toneHz() {
    const p = this.prefs;
    return this.isDualTone() ? p.genDualToneFreq1Hz.get() : p.genFrequencyHz.get();
  }
  adcFsVoltageRms() { return this.prefs.adcFsVoltageRms.get(); }
  genAmplitudeVrms() { return this.prefs.genAmplitudeVrms.get(); }
  dualToneSplitPct() { return this.prefs.genDualToneSplitPct.get(); }

  /** Loaded .frc store entries - the engine's calResponseAt reads these. */
  get correctionEntries() { return this.bridge.correctionEntries; }

  /** Infinite coherent generator-locked averaging: coherent on, FLL on, deep averaging window -
   *  written into the preferences the FFT reads, exactly as Java PredistortionEngine sets
   *  setFftCoherentAveraging / setFftAverages(∞) / setFftAlignGenerator(FLL) on the live
   *  Preferences (the strip's controls follow them); then restart the engine so the new window
   *  takes effect. */
  async configureForRun() {
    const p = this.prefs;
    p.fftCoherentAveraging.set(true);
    p.fftAlignGenerator.set('FLL');
    p.fftAverages.set(INFINITE_AVERAGES);
    // The predistortion run does its OWN per-round average counting; the FFT
    // pane's stop-after-N auto-stop must not tear the consumer down mid-round.
    p.fftStopAfterNEnabled.set(false);
    await this.bridge.restart();
    this.engine.resetAnalyses();
  }

  /** The FFT is already recording whenever the engine runs. */
  startRecording() {}

  /** Drop any compensation on the live generator. */
  clearCompensation() {
    this.engine.postGen({ clearCompensation: true });
  }

  /** Load a stored .dpd onto the running generator - the web twin of
   *  GeneratorLane.loadCorrectionsFromFile(path), which parses the file and applies the
   *  tables at the effective frequency. The browser has no OS path, so the per-form
   *  preference keeps the .dpd TEXT and the text travels: the sink's kernel parses it
   *  (loadHarmonics / loadIntermod), which is exactly the message the generator start
   *  sends for a compensated form (GeneratorController.startGenerator, control.dpdText +
   *  dpdFrequency). No-op when nothing is playing - postGen drops the message. */
  loadCorrections(dpdText) {
    if (!dpdText) return;
    this.engine.postGen({ dpdText, dpdFrequency: this.effectiveFrequency() });
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

  /** The dual-tone intermod analysis of `r` - the ONE analyzeImd call site, so the
   *  engine's convergence metric and the wizard's readouts can never disagree
   *  about a frame. null when the result carries no measurable pair.
   *  @returns {?import('../fft/imd-analyzer.js').ImdResult} */
  imdResult(r) {
    return analyzeImd(r, this._toneHz(), this.prefs.genDualToneFreq2Hz.get(), this.prefs.dbvOffsetDb);
  }

  /** Combined intermod % of a dual-tone result - the engine's convergence metric. */
  imdPct(r) {
    const imd = this.imdResult(r);
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
