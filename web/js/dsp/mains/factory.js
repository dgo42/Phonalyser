/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.dsp.MainsFilters (@UtilityClass).
//
// Builds the time-domain mains filter for a MainsSuppression mode — the single
// place the scope (display + measurement) and the FFT pre-filter map the
// selected mode to a filter instance. All three filters share the same
// contract (Java MainsTimeFilter): track / process / processPreservingDc /
// reset / resetTracking / isTuned / getMainsHz.

import { MainsCombFilter } from './comb-filter.js';
import { MainsSyncSubtractFilter } from './sync-subtract-filter.js';
import { MainsLmsFilter } from './lms-filter.js';

/**
 * A filter for `mode`, or null for 'NONE'. `combNotchBwHz` is the −3 dB notch
 * width used only by the IIR comb.
 * @param {'NONE'|'IIR_COMB'|'SYNC_SUBTRACT'|'LMS'} mode  MainsSuppression enum name
 * @param {number} sampleRate     capture sample rate (Hz), > 0
 * @param {number} combNotchBwHz  −3 dB notch width for the IIR comb (Hz)
 * @returns {MainsCombFilter|MainsSyncSubtractFilter|MainsLmsFilter|null}
 */
export function mainsFilterOf(mode, sampleRate, combNotchBwHz) {
  switch (mode) {
    case 'IIR_COMB':      return new MainsCombFilter(sampleRate, combNotchBwHz);
    case 'SYNC_SUBTRACT': return new MainsSyncSubtractFilter(sampleRate);
    case 'LMS':           return new MainsLmsFilter(sampleRate);
    default:              return null;   // 'NONE'
  }
}
