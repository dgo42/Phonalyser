/*
 * Phonalyser web - precision audio measurement workbench (browser port).
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 * GNU Affero General Public License v3 or later.
 */

// Faithful port of the pure NAV math in
// org.edgo.audio.measure.gui.scope.ScopeFormat (the @UtilityClass).
//
// Only the SWT-/Preferences-free transform helpers the pan/zoom engine
// (scope-nav.js) needs are ported here - the V/div ladder math, the
// offset re-anchoring on zoom, and the sample/division mappings. The Java
// class's string-format and SWT-colour helpers (formatVolts, formatSeconds,
// midRgb, shortVoltsPerDiv, ...) belong to the renderer and are intentionally
// NOT ported into this nav-math module.

/** Relative tolerance for matching / comparing a V/div value against a 1-2-5 rung
 *  (ScopeFormat.V_DIV_RULE_TOL). */
const V_DIV_RULE_TOL = 1e-6;

/** Clamps `v` to [0, 1] (ScopeFormat.clamp01). */
export function clamp01(v) {
  if (v < 0) return 0;
  if (v > 1) return 1;
  return v;
}

/**
 * Mean of `data` over [start, start+count) - used for AC-mode DC removal
 * (ScopeFormat.windowMean). Returns 0 for an empty/absent window.
 * @param {Float32Array|Float64Array|number[]} data
 * @param {number} start
 * @param {number} count
 * @returns {number}
 */
export function windowMean(data, start, count) {
  if (data == null || count <= 0) return 0.0;
  let s = 0;
  for (let i = 0; i < count; i++) s += data[start + i];
  return s / count;
}

/**
 * Returns the smallest target >= value, or the largest target if value exceeds
 * them all. `targets` must be sorted ascending (ScopeFormat.ceilToStep).
 * @param {number} value
 * @param {number[]} targets ascending
 * @returns {number}
 */
export function ceilToStep(value, targets) {
  for (const t of targets) if (t >= value) return t;
  return targets[targets.length - 1];
}

/**
 * Number of samples the main view shows for a `timePerDiv` (seconds) and the
 * buffer's `sampleRate` (Hz). The display spans 10 horizontal divisions, so
 * windowSeconds = 10 · timePerDiv -> samples = round(windowSeconds · sampleRate),
 * floored at 2 (ScopeFormat.displaySamplesFor).
 * @param {number} timePerDiv seconds per division
 * @param {number} sampleRate Hz
 * @returns {number}
 */
export function displaySamplesFor(timePerDiv, sampleRate) {
  const windowSeconds = timePerDiv * 10.0;
  return Math.max(2, Math.round(windowSeconds * sampleRate));
}

/**
 * The `offsetFrac` that, after V/div changes from `oldVpdiv` to `newVpdiv`, keeps
 * the voltage under screen fraction `anchorFrac` fixed (0..1 top..bottom). Voltage
 * there = (offsetFrac − anchorFrac)·Y·V/div; holding it constant gives the formula
 * below (ScopeFormat.anchorOffsetAfterZoom). `anchorFrac = 0.5` is the canvas-middle
 * case (preserveCanvasMiddle); the mouse-Y fraction is the ctrl+wheel case.
 * @param {number} offsetFrac
 * @param {number} oldVpdiv
 * @param {number} newVpdiv
 * @param {number} anchorFrac 0..1 top..bottom
 * @returns {number}
 */
export function anchorOffsetAfterZoom(offsetFrac, oldVpdiv, newVpdiv, anchorFrac) {
  if (oldVpdiv <= 0 || newVpdiv <= 0) return offsetFrac;
  return anchorFrac + (offsetFrac - anchorFrac) * oldVpdiv / newVpdiv;
}

/**
 * The `offsetFrac` that, after the V/div changes from `oldVpdiv` to `newVpdiv`,
 * keeps the same voltage at the canvas vertical centre - the anchorFrac = 0.5 case
 * (ScopeFormat.preserveCanvasMiddle).
 * @param {number} offsetFrac
 * @param {number} oldVpdiv
 * @param {number} newVpdiv
 * @returns {number}
 */
export function preserveCanvasMiddle(offsetFrac, oldVpdiv, newVpdiv) {
  return anchorOffsetAfterZoom(offsetFrac, oldVpdiv, newVpdiv, 0.5);
}

/**
 * Whether `v` sits on the 1-2-5 ladder within V_DIV_RULE_TOL
 * (ScopeFormat.onVoltsPerDivRule).
 * @param {number} v
 * @param {number[]} rule ascending 1-2-5 ladder
 * @returns {boolean}
 */
export function onVoltsPerDivRule(v, rule) {
  for (const r of rule) {
    if (Math.abs(v - r) <= V_DIV_RULE_TOL * r) return true;
  }
  return false;
}

/**
 * The next 1-2-5 rung strictly beyond `v` in the zoom direction (dir < 0 = zoom in
 * / smaller V/div, dir > 0 = zoom out / larger). Returns `v` unchanged at the end
 * of the ladder (ScopeFormat.nextVoltsPerDivRung).
 * @param {number} v
 * @param {number} dir
 * @param {number[]} rule ascending 1-2-5 ladder
 * @returns {number}
 */
export function nextVoltsPerDivRung(v, dir, rule) {
  if (dir > 0) {
    for (const r of rule) if (r > v * (1.0 + V_DIV_RULE_TOL)) return r;
    return v;
  }
  for (let i = rule.length - 1; i >= 0; i--) {
    if (rule[i] < v * (1.0 - V_DIV_RULE_TOL)) return rule[i];
  }
  return v;
}

/** Smallest absolute distance from `v` to any rung of `rule` (ScopeFormat.distToRule). */
function distToRule(v, rule) {
  let best = Number.MAX_VALUE;
  for (const r of rule) {
    const d = Math.abs(v - r);
    if (d < best) best = d;
  }
  return best;
}

/** Whether a zoom-out result `v` overshoots the `vDivMax` ceiling (ScopeFormat.exceedsCeil). */
function exceedsCeil(dir, v, vDivMax) {
  return dir > 0 && vDivMax > 0 && v > vDivMax * (1.0 + V_DIV_RULE_TOL);
}

/**
 * Couples both channels' V/div for one zoom step so they ALWAYS keep their ratio:
 * one channel (the base) snaps to its next 1-2-5 rung and the other scales by that
 * same factor (so a follower may land off the rule).
 *   - Both on the 1-2-5 rule -> the base is whichever choice leaves the SCALED
 *     channel nearest a rung (smallest absolute distance).
 *   - One off the rule -> the on-rule channel is the base; both off -> the one
 *     nearest its next rung in the zoom direction (smallest |ln(ratio)|).
 * An inactive channel is signalled by a non-positive V/div and returned unchanged;
 * a blocked zoom-out (would exceed the channel's ceiling) returns the inputs.
 * (ScopeFormat.coupleVoltsPerDivZoom.)
 *
 * Per-channel ceilings (Java ScopeFormat.coupleVoltsPerDivZoom two-ceiling overload):
 * each channel's zoom-out is capped at its OWN FS-fills-height rung - the ceiling is a
 * pure function of that channel's full-scale, so it simply stops being shared; the
 * block stays coupled. `rightMax` defaults to `leftMax` so equal L/R full-scales
 * (LINKED) are byte-for-byte the single-ceiling behaviour.
 * @param {number} leftV   left V/div  (>0, or <=0 when that channel is inactive)
 * @param {number} rightV  right V/div (>0, or <=0 when inactive)
 * @param {number} dir     -1 zoom in (smaller V/div), +1 zoom out (larger)
 * @param {number[]} rule  ascending 1-2-5 ladder
 * @param {number} leftMax  left  zoom-out ceiling (FS fills the full grid height); <=0 = none
 * @param {number} rightMax right zoom-out ceiling; defaults to leftMax (LINKED)
 * @returns {number[]} {newLeftV, newRightV}
 */
export function coupleVoltsPerDivZoom(leftV, rightV, dir, rule, leftMax, rightMax = leftMax) {
  const leftOn = leftV > 0;
  const rightOn = rightV > 0;
  if (!leftOn && !rightOn) return [leftV, rightV];
  if (leftOn !== rightOn) {                       // single active channel (XOR)
    const cur = leftOn ? leftV : rightV;
    let next = nextVoltsPerDivRung(cur, dir, rule);
    if (exceedsCeil(dir, next, leftOn ? leftMax : rightMax)) next = cur;
    return [leftOn ? next : leftV, rightOn ? next : rightV];
  }
  const lRule = onVoltsPerDivRule(leftV, rule);
  const rRule = onVoltsPerDivRule(rightV, rule);
  let newL;
  let newR;
  if (lRule && rRule) {                            // both on rule: hold the ratio too
    // Step one channel (the base) to its next rung and scale the other by that
    // factor; pick the base that leaves the SCALED channel nearest a 1-2-5 rung
    // (e.g. 500µV/1mV zoom-in -> 250/500µV, since 250 is 50µV off 200, beating
    // 200/400µV where 400 is 100µV off 500).
    const lNext = nextVoltsPerDivRung(leftV, dir, rule);
    const rNext = nextVoltsPerDivRung(rightV, dir, rule);
    const scaledRight = rightV * (lNext / leftV);    // left as base
    const scaledLeft = leftV * (rNext / rightV);     // right as base
    if (distToRule(scaledRight, rule) <= distToRule(scaledLeft, rule)) {
      newL = lNext;        newR = scaledRight;
    } else {
      newL = scaledLeft;   newR = rNext;
    }
  } else {                                          // proportional coupling
    let refLeft;
    if (lRule !== rRule) {
      refLeft = lRule;                              // the on-rule channel leads
    } else {                                        // both off-rule: nearest its rung leads
      const tL = nextVoltsPerDivRung(leftV, dir, rule);
      const tR = nextVoltsPerDivRung(rightV, dir, rule);
      refLeft = Math.abs(Math.log(tL / leftV)) <= Math.abs(Math.log(tR / rightV));
    }
    const refCur = refLeft ? leftV : rightV;
    const refNext = nextVoltsPerDivRung(refCur, dir, rule);
    const ratio = refNext / refCur;
    newL = refLeft ? refNext : leftV * ratio;
    newR = refLeft ? rightV * ratio : refNext;
  }
  if (exceedsCeil(dir, newL, leftMax) || exceedsCeil(dir, newR, rightMax)) {
    return [leftV, rightV];                         // zoom-out blocked at FS-fills-height
  }
  return [newL, newR];
}

/**
 * The vertical move limit for one channel: the maximum half-range, in offsetFrac
 * units, the zero line may sit from canvas middle before the signal's ±FS extreme
 * reaches the middle. half = peak/(Ydiv·vDiv), floored at 0.5 so the grid edges are
 * always reachable. The allowed band is [0.5 − half, 0.5 + half]
 * (ScopeFormat.offsetMoveHalfRange).
 * @param {number} vDiv
 * @param {number} peakVolts
 * @param {number} divisionsY
 * @returns {number}
 */
export function offsetMoveHalfRange(vDiv, peakVolts, divisionsY) {
  if (vDiv <= 0 || peakVolts <= 0) return 0.5;
  return Math.max(0.5, peakVolts / (divisionsY * vDiv));
}

/**
 * Clamps a vertical-move `delta` (in offsetFrac units) so the channel's zero line
 * stays within its offsetMoveHalfRange band - i.e. you can pan the signal only until
 * its ±FS extreme reaches the canvas middle (ScopeFormat.clampOffsetDelta).
 * @param {number} delta
 * @param {number} offsetFrac
 * @param {number} vDiv
 * @param {number} peakVolts
 * @param {number} divisionsY
 * @returns {number}
 */
export function clampOffsetDelta(delta, offsetFrac, vDiv, peakVolts, divisionsY) {
  if (vDiv <= 0) return delta;
  const half = offsetMoveHalfRange(vDiv, peakVolts, divisionsY);
  const next = offsetFrac + delta;
  if (next < 0.5 - half) return (0.5 - half) - offsetFrac;
  if (next > 0.5 + half) return (0.5 + half) - offsetFrac;
  return delta;
}
