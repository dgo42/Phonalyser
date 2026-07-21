/*
 * Phonalyser web — precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of org.edgo.audio.measure.gui.scope.ScopeNav.
//
// The oscilloscope's pan/zoom engine — the single home for every horizontal and
// vertical move/zoom transform and the viewport mapping, as pure SWT-/Preferences-
// free logic so it is fully unit-testable. Callers (the view, pane, tab control)
// read the persisted state, hand it to one of these methods, and write the result
// back.
//
// ── One buffer, three modes ─────────────────────────────────────────────────
// All three modes render the SAME signal buffer; only its growth differs — LIVE
// keeps receiving samples, FROZEN (stopped) and FILE never do. Horizontal
// position is one model in every mode: a `displaySamples`-wide window whose left
// edge is
//     viewLeftAbs = anchorAbs − displaySamples · offsetFrac
// where the *anchor* is the trigger (live/frozen) or the view centre (file, with
// offsetFrac = 0.5). `offsetFrac` is the trigger-position fraction and MAY leave
// [0,1] (a *virtual* offset) — the handle pins to the screen edge but the window,
// and the time-offset readout, follow the real value. Out-of-buffer columns are
// simply not drawn (the renderer blanks them); the zoom/pan anchor never moves to
// compensate.
//
// ── Vertical is independent ─────────────────────────────────────────────────
// Vertical move/zoom never touch any horizontal state, and vice-versa. Both
// channels move/zoom together: a manual (off-1-2-5) channel keeps its proportion
// to the channel that steps the ladder; the move stops when ±FS/2 reaches the
// canvas middle and zoom-out stops when ±FS fills the grid height.

import {
  ceilToStep,
  clampOffsetDelta,
  coupleVoltsPerDivZoom,
  anchorOffsetAfterZoom,
} from './scope-format.js';

/**
 * Where the display window lands inside the read buffer (feeds renderTraces).
 * Mirrors the Java `Viewport` record.
 * @typedef {Object} Viewport
 * @property {number} dispStart         integer local start index in the read buffer
 * @property {number} subSampleOffset   fractional remainder [0,1)
 * @property {number} dispCount         always the full displaySamples
 * @property {number} viewLeftAbs       absolute (fractional) sample at the left edge
 */

/**
 * The file/scroll view-window mapping — read back-offsets for the main + condensed
 * views plus the centre range the nav-slider widget needs. Mirrors the Java
 * `ViewWindow` record.
 * @typedef {Object} ViewWindow
 * @property {number} mainBackOffset      fractional (double) — carries the sub-sample scroll
 * @property {number} condensedBackOffset
 * @property {number} minCentre
 * @property {number} maxCentre
 * @property {number} clampedCentre
 * @property {boolean} followLatest
 */

export class ScopeNav {

  /**
   * @param {number} divisionsX horizontal grid divisions (the window spans this many)
   * @param {number} divisionsY vertical grid divisions
   * @param {number[]} vDivLadder ascending 1-2-5 V/div ladder (OscParse.voltsPerDivTargets())
   */
  constructor(divisionsX, divisionsY, vDivLadder) {
    this.divisionsX = divisionsX;
    this.divisionsY = divisionsY;
    this.vDivLadder = vDivLadder;
  }

  // =====================================================================
  // Horizontal — viewport mapping
  // =====================================================================

  /** Absolute (fractional) sample at the left edge of the display window. */
  viewLeftAbs(anchorAbs, offsetFrac, displaySamples) {
    return anchorAbs - displaySamples * offsetFrac;
  }

  /**
   * Maps the window onto a read buffer that starts at absolute sample `bufStartAbs`.
   * `dispCount` is always the full `displaySamples`; the renderer blanks any column
   * whose sample index falls outside the buffer, so the window can hang off either
   * end without stretching.
   * @returns {Viewport}
   */
  viewport(anchorAbs, offsetFrac, displaySamples, bufStartAbs) {
    const viewLeft = this.viewLeftAbs(anchorAbs, offsetFrac, displaySamples);
    const local = viewLeft - bufStartAbs;
    const dispStart = Math.floor(local);
    return {
      dispStart,
      subSampleOffset: local - dispStart,
      dispCount: displaySamples,
      viewLeftAbs: viewLeft,
    };
  }

  /**
   * Derives the file/scroll ViewWindow from the scroll `centreFrames` (absolute frame
   * under the canvas centre; < 0 = follow latest), the window width, and the buffer.
   * The main view's read ends at the window's right edge; the condensed strip shows a
   * 1 s window centred on the same frame.
   * @returns {ViewWindow}
   */
  fileViewWindow(centreFrames, displaySamples, writePos, capacity, sampleRate) {
    const oldest = Math.max(0, writePos - capacity);
    const minCentre = oldest + Math.trunc(displaySamples / 2);
    const maxCentre = writePos - Math.trunc(displaySamples / 2);
    const followLatest = centreFrames < 0 || maxCentre < minCentre;
    let clampedCentre;
    let mainOffset;
    if (followLatest) {
      clampedCentre = maxCentre;     // slider pinned rightmost
      mainOffset = 0;
    } else {
      clampedCentre = Math.max(minCentre, Math.min(maxCentre, centreFrames));
      // Keep the back-offset FRACTIONAL: a ½-div step is 15.36 samples at 384 kHz,
      // and rounding to a whole sample would quantise the scroll (→ 41.7 µs, not
      // 40 µs). The view carries the fraction into a sub-sample render.
      const viewEndAbs = clampedCentre + displaySamples / 2.0;
      mainOffset = Math.max(0, writePos - viewEndAbs);
    }
    const mainCentre = writePos - Math.round(mainOffset) - Math.trunc(displaySamples / 2);
    let condEnd = mainCentre + Math.trunc(sampleRate / 2);
    condEnd = Math.min(condEnd, writePos);
    condEnd = Math.max(condEnd, Math.min(writePos, oldest + sampleRate));
    const condOffset = Math.max(0, writePos - condEnd);
    return {
      mainBackOffset: mainOffset,
      condensedBackOffset: condOffset,
      minCentre,
      maxCentre,
      clampedCentre,
      followLatest,
    };
  }

  // =====================================================================
  // Horizontal — move
  // =====================================================================

  /** One ½-division horizontal move tick in offsetFrac units (live/frozen). */
  halfDivOffsetStep() {
    return 0.5 / this.divisionsX;
  }

  /** New trigger offset after one ½-div move tick. `dir` > 0 matches the live
   *  trigger-offset wheel sign; the result is unclamped (it may go virtual). */
  moveTriggerOffset(offsetFrac, dir) {
    return offsetFrac + dir * this.halfDivOffsetStep();
  }

  /** Moves the file-mode view centre by `divisions` grid divisions (signed;
   *  negative = toward older samples), clamped so the full window stays inside the
   *  file. `samplesPerDiv` is the EXACT double samples-per-division
   *  (timePerDiv × sampleRate, NOT derived from the int-rounded window width) so
   *  fractional steps — ⅕ div = 1.764 samples at 44.1 kHz — accumulate unrounded. */
  moveFileCentre(centreAbs, divisions, samplesPerDiv, displaySamples, oldest, latest) {
    return this.clampFileCentre(centreAbs + divisions * samplesPerDiv, displaySamples, oldest, latest);
  }

  /**
   * New file view centre after a horizontal zoom around the mouse: the sample under
   * the pointer (screen fraction `mouseFrac`) stays put as the window resizes from
   * `dispOld` to `dispNew` samples. Caller clamps the result with clampFileCentre —
   * once the whole file fits the width the clamp centres it, so the anchor is free to
   * move (per the spec's file limit).
   */
  zoomFileCentre(centreAbs, mouseFrac, dispOld, dispNew) {
    return centreAbs + (mouseFrac - 0.5) * (dispOld - dispNew);
  }

  /** Clamps a file view centre so the whole window stays within [oldest, latest];
   *  if the window is wider than the file the centre snaps to the file's midpoint. */
  clampFileCentre(centreAbs, displaySamples, oldest, latest) {
    const minC = oldest + displaySamples / 2.0;
    const maxC = latest - displaySamples / 2.0;
    if (maxC < minC) return (oldest + latest) / 2.0;
    if (centreAbs < minC) return minC;
    if (centreAbs > maxC) return maxC;
    return centreAbs;
  }

  // =====================================================================
  // Horizontal — zoom
  // =====================================================================

  /**
   * New trigger offset after a horizontal (t/div) zoom.
   * @param {number} offsetOld
   * @param {number} dispOld
   * @param {number} dispNew
   * @param {number} mouseFrac
   * @param {boolean} aroundMouse true for ctrl+shift+wheel (anchor the sample under
   *        the mouse — the offset moves, possibly off-screen); false for the
   *        resolution control (anchor the trigger — the offset is unchanged).
   * @returns {number}
   */
  zoomTriggerOffset(offsetOld, dispOld, dispNew, mouseFrac, aroundMouse) {
    // Use the ACTUAL displaySamples ratio (not the t/div ratio): the render rounds
    // displaySamples to whole samples, so at fine time bases (few samples per screen)
    // the t/div ratio diverges from the rendered ratio and the anchor would drift.
    if (!aroundMouse || dispNew <= 0 || dispOld <= 0) return offsetOld;
    return mouseFrac - (mouseFrac - offsetOld) * (dispOld / dispNew);
  }

  // =====================================================================
  // Vertical — move (both channels together, ±FS/2-at-middle limit)
  // =====================================================================

  /** One ½-division vertical move tick in offsetFrac units. */
  halfDivOffsetStepY() {
    return 0.5 / this.divisionsY;
  }

  /**
   * Moves both active channels by one ½-div tick, locked together, clamped so neither
   * channel's zero line passes its ±FS/2-at-middle limit. `dir` > 0 = wheel up =
   * signal up = offset decreases (the established sign). An inactive channel
   * (on == false) is ignored for both the clamp and the result.
   *
   * Per-channel full-scale (Java ScopeNav.moveVertical two-peak overload): each
   * channel's ±FS/2-at-middle clamp uses its OWN peak — the ADC full-scale plays the
   * same per-channel role the V/div already does. `rightPeak` defaults to `leftPeak`
   * so the LINKED (equal L/R full-scale) case is byte-for-byte the single-peak form.
   * @returns {number[]} {newLeftOffsetFrac, newRightOffsetFrac}
   */
  moveVertical(leftOff, leftVdiv, leftOn, rightOff, rightVdiv, rightOn, dir, leftPeak, rightPeak = leftPeak) {
    let delta = -dir * this.halfDivOffsetStepY();
    if (leftOn) delta = clampOffsetDelta(delta, leftOff, leftVdiv, leftPeak, this.divisionsY);
    if (rightOn) delta = clampOffsetDelta(delta, rightOff, rightVdiv, rightPeak, this.divisionsY);
    return [leftOn ? leftOff + delta : leftOff,
            rightOn ? rightOff + delta : rightOff];
  }

  // =====================================================================
  // Vertical — zoom (coupled V/div + re-anchored offsets)
  // =====================================================================

  /** Largest V/div allowed when zooming out: the smallest 1-2-5 rung at which the ADC
   *  full scale (±peak = 2·peak p-p) still FITS the grid height. The exact-fill value
   *  2·peak/Ydiv usually lands between rungs (e.g. 0.506 V/div for a 2.53 V peak), so
   *  rounding UP makes the rung that shows all of FS without clipping reachable
   *  (1 V/div there) — 500 mV/div would clip the last sliver of FS. */
  zoomOutVoltsPerDivCeiling(peakVolts) {
    return ceilToStep(2.0 * peakVolts / this.divisionsY, this.vDivLadder);
  }

  /**
   * Zooms both active channels' V/div one tick (coupled per the 1-2-5 proportional
   * rule) and re-anchors each channel's offset so the voltage under `anchorFrac`
   * stays put — anchorFrac = 0.5 for the V/div control (canvas middle), mouseY/h for
   * ctrl+wheel. Zoom-out is capped at the FS-fills-height ceiling.
   * Per-channel full-scale (Java ScopeNav.zoomVertical two-peak overload): each
   * channel's zoom-out ceiling ("±FS fills the grid height") is derived from its OWN
   * peak; the block stays coupled (either channel hitting its own ceiling blocks both).
   * `rightPeak` defaults to `leftPeak` so LINKED reproduces the single-peak behaviour.
   * @param {number} dir -1 zoom in (smaller V/div), +1 zoom out (larger)
   * @returns {number[]} {newLeftVdiv, newRightVdiv, newLeftOffset, newRightOffset}
   */
  zoomVertical(leftVdiv, leftOff, leftOn, rightVdiv, rightOff, rightOn, dir, anchorFrac, leftPeak, rightPeak = leftPeak) {
    const lv = leftOn ? leftVdiv : -1.0;
    const rv = rightOn ? rightVdiv : -1.0;
    const v = coupleVoltsPerDivZoom(lv, rv, dir, this.vDivLadder,
        this.zoomOutVoltsPerDivCeiling(leftPeak), this.zoomOutVoltsPerDivCeiling(rightPeak));
    const newLv = leftOn ? v[0] : leftVdiv;
    const newRv = rightOn ? v[1] : rightVdiv;
    const newLo = (leftOn && newLv !== leftVdiv)
        ? anchorOffsetAfterZoom(leftOff, leftVdiv, newLv, anchorFrac) : leftOff;
    const newRo = (rightOn && newRv !== rightVdiv)
        ? anchorOffsetAfterZoom(rightOff, rightVdiv, newRv, anchorFrac) : rightOff;
    return [newLv, newRv, newLo, newRo];
  }
}

/** Horizontal grid divisions the window spans (ScopeNav.DIVISIONS_X). */
export const DIVISIONS_X = 10;

/** Vertical grid divisions (ScopeNav.DIVISIONS_Y). */
export const DIVISIONS_Y = 10;
