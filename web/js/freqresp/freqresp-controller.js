/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of gui/freqresp/FreqRespController's sweep-timing rules — the
// lead-in floor and the derived sweep duration that pairs with the chosen FFT
// size, so the analyzer's nextPow2(leadIn + sweep + tail) lands exactly on
// fftSize (no wasted bins). The desktop controller also owns the measurement
// worker's lifecycle; the web sweep is driven directly by FreqRespHost.runSweep
// (no separate worker / bus events), so this port carries only the timing half.
//
// The constructor subscribes to the preferences the timing rules depend on (FFT
// size, lead-in) and re-derives + persists freqRespDurationSec — the tab control
// / label only render it. The desktop also re-derives on AUDIO_FORMAT_CHANGED;
// the web has no such bus, so the rate-change site calls recompute() directly.

export class FreqRespController {
  /** Lead-in floor (s) — shorter lead-ins starve the deconvolution. */
  static MIN_LEAD_IN_SEC = 0.05;
  /** Capture tail (s) the analyzer records past lead-in + sweep. */
  static ANALYZER_TAIL_SEC = 0.5;
  /** Sweep-duration floor (s) — a small FFT size with a long lead-in
   *  must not produce a negative or unworkable sweep. */
  static MIN_SWEEP_SEC = 0.5;

  /**
   * @param {import('../audio/backend.js').AudioEngine} engine the live engine
   *   (the capture/input sample rate drives the derived duration).
   * @param {import('../store/preferences.js').Preferences} prefs Preferences.instance()
   */
  constructor(engine, prefs) {
    this.engine = engine;
    this.prefs = prefs;

    prefs.freqRespFftSize.addListener(() => this.deriveDuration());
    prefs.freqRespLeadInSec.addListener((v) => {
      if (v < FreqRespController.MIN_LEAD_IN_SEC) {
        prefs.freqRespLeadInSec.set(FreqRespController.MIN_LEAD_IN_SEC);
        return;   // the re-set re-enters here with the clamped value
      }
      this.deriveDuration();
    });
    // Initial sync: the persisted durationSec may no longer match the persisted
    // fftSize / leadIn (or vice versa). Re-derive and persist on build so the
    // analyzer + label agree.
    this.deriveDuration();
  }

  /** Re-derives the duration on an audio-format (input sample rate) change.
   *  The desktop rides AUDIO_FORMAT_CHANGED; the web has no such bus, so the
   *  rate-change site calls this directly. */
  recompute() {
    this.deriveDuration();
  }

  /** Total expected capture time of one sweep — lead-in + sweep + the
   *  analyzer's tail. */
  expectedMeasurementSeconds() {
    return this.prefs.freqRespLeadInSec.get()
         + this.prefs.freqRespDurationSec.get()
         + FreqRespController.ANALYZER_TAIL_SEC;
  }

  /** The derived sweep duration (s), already persisted by deriveDuration. */
  durationSec() {
    return this.prefs.freqRespDurationSec.get();
  }

  /** Derives and persists the sweep duration that pairs with the chosen FFT
   *  size, so the analyzer's nextPow2(leadIn + sweep + tail) lands exactly on
   *  fftSize — no wasted bins. Clamped to MIN_SWEEP_SEC. */
  deriveDuration() {
    const prefs = this.prefs;
    const sr = Math.max(1, this.sampleRate());
    const leadIn = Math.round(prefs.freqRespLeadInSec.get() * sr);
    const tail = Math.round(FreqRespController.ANALYZER_TAIL_SEC * sr);
    let sweep = prefs.freqRespFftSize.get() - leadIn - tail;
    const minSweep = Math.round(FreqRespController.MIN_SWEEP_SEC * sr);
    if (sweep < minSweep) sweep = minSweep;
    prefs.freqRespDurationSec.set(sweep / sr);
  }

  /** The capture (input) sample rate the duration is derived against — the
   *  loopback sweep is recorded on the input, so the FFT window is in input
   *  samples (Java getInputSampleRate). */
  sampleRate() {
    return (this.engine.config && this.engine.config.inRate)
        || this.prefs.current().inputSampleRate
        || 384000;
  }
}
