/*
 * Phonalyser — precision audio measurement workbench.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.edgo.audio.measure.gui.scope;

import lombok.experimental.UtilityClass;

import java.util.Locale;

/**
 * Pure-math number / colour / string helpers used by the oscilloscope's
 * rendering and measurement display.  Decoupled from {@link ScopeView}
 * so the formulas are unit-testable without instantiating an SWT widget.
 */
@UtilityClass
public class ScopeFormat {

    /**
     * Volts with auto-prefix AND auto-precision based on {@code vpdiv}.
     * The display resolves to ≈ {@code vpdiv / 10} (one 1/10-div tick),
     * so as the user picks a narrower V/div the label gains decimal
     * places to keep the readout meaningful.  Used for the channel
     * edge labels and the trigger-level voltage label.
     */
    public String formatVolts(double v, double vpdiv) {
        double a = Math.abs(v);
        String unit;
        double scaledV;
        double scaledRes;
        if (a >= 1.0)        { unit = "V";  scaledV = v;       scaledRes = vpdiv * 0.1; }
        else if (a >= 1e-3)  { unit = "mV"; scaledV = v * 1e3; scaledRes = vpdiv * 0.1 * 1e3; }
        else if (a >= 1e-6)  { unit = "µV"; scaledV = v * 1e6; scaledRes = vpdiv * 0.1 * 1e6; }
        else if (a == 0)     return "0 V";
        else                 return String.format(Locale.ROOT, "%.2g V", v);
        int dp;
        if (scaledRes >= 1.0)    dp = 1;
        else if (scaledRes > 0)  dp = (int) Math.ceil(-Math.log10(scaledRes)) + 1;
        else                     dp = 3;
        if (dp < 1) dp = 1;
        if (dp > 6) dp = 6;
        return String.format(Locale.ROOT, "%." + dp + "f " + unit, scaledV);
    }

    /** Seconds with auto-prefix (s / ms / μs / ns), 2 significant decimals. */
    public String formatSeconds(double t) {
        double a = Math.abs(t);
        if (a >= 1.0)       return String.format(Locale.ROOT, "%.2f s",  t);
        if (a >= 1e-3)      return String.format(Locale.ROOT, "%.2f ms", t * 1e3);
        if (a >= 1e-6)      return String.format(Locale.ROOT, "%.1f µs", t * 1e6);
        if (a >= 1e-9)      return String.format(Locale.ROOT, "%.1f ns", t * 1e9);
        if (a == 0)         return "0 s";
        return String.format(Locale.ROOT, "%.2g s", t);
    }

    /** Clamps {@code v} to {@code [0, 1]}. */
    public double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }

    /**
     * Returns {@code rgb} with each channel scaled to ≈⅔ brightness —
     * still clearly visible, but visibly less prominent than the fully-
     * bright active variant.  Used for the inactive offset triangle +
     * unselected L/R button.
     */
    public int midRgb(int rgb) {
        int r = (int) Math.round(((rgb >> 16) & 0xFF) * 0.65);
        int g = (int) Math.round(((rgb >>  8) & 0xFF) * 0.65);
        int b = (int) Math.round(( rgb        & 0xFF) * 0.65);
        return (r << 16) | (g << 8) | b;
    }

    /** Mean of {@code data} over {@code [start, start+count)} — used for AC-mode DC removal. */
    public double windowMean(float[] data, int start, int count) {
        if (data == null || count <= 0) return 0.0;
        double s = 0;
        for (int i = 0; i < count; i++) s += data[start + i];
        return s / count;
    }

    /**
     * Formats a volts-per-division value as a compact label (no "V/div"
     * suffix, SI prefix u / m / none).  E.g. {@code 0.020 → "20m"},
     * {@code 0.5 → "500m"}, {@code 1 → "1"}.
     */
    public String shortVoltsPerDiv(double v) {
        return shortSi(v);
    }

    /**
     * Formats a seconds-per-division value as a compact label (no "s/div"
     * suffix, SI prefix n / u / m / none).  E.g. {@code 0.000050 → "50u"},
     * {@code 0.020 → "20m"}, {@code 0.5 → "500m"}, {@code 1 → "1"}.
     */
    public String shortTimePerDiv(double v) {
        if (v > 0 && v < 1e-6) return shortNum(v * 1e9) + "n";
        return shortSi(v);
    }

    private String shortSi(double v) {
        if (v <= 0)       return "0";
        if (v >= 1.0)     return shortNum(v);
        if (v >= 1e-3)    return shortNum(v * 1e3) + "m";
        if (v >= 1e-6)    return shortNum(v * 1e6) + "u";
        return shortNum(v * 1e9) + "n";
    }

    private String shortNum(double v) {
        long iv = Math.round(v);
        if (Math.abs(v - iv) < 1e-4) return Long.toString(iv);
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /**
     * Returns the smallest target {@code >= value}, or the largest target
     * if {@code value} exceeds them all.  {@code targets} must be sorted
     * ascending.
     */
    public double ceilToStep(double value, double[] targets) {
        for (double t : targets) if (t >= value) return t;
        return targets[targets.length - 1];
    }

    /**
     * Number of samples the main view shows for a {@code timePerDiv} (in
     * seconds) and the buffer's {@code sampleRate} (Hz).  The display
     * spans 10 horizontal divisions, so {@code windowSeconds = 10 ·
     * timePerDiv} → samples = round(windowSeconds · sampleRate).
     */
    public int displaySamplesFor(double timePerDiv, int sampleRate) {
        double windowSeconds = timePerDiv * 10.0;
        return Math.max(2, (int) Math.round(windowSeconds * sampleRate));
    }

    /**
     * Returns the {@code offsetFrac} that, after the V/div changes from
     * {@code oldVpdiv} to {@code newVpdiv}, keeps the same voltage at the
     * canvas vertical centre.
     *
     * <p>Voltage at canvas middle = {@code (offsetFrac − 0.5) · Y · V/div}.
     * Holding that constant gives {@code newOffsetFrac = (oldOffsetFrac −
     * 0.5) · oldV / newV + 0.5}.
     */
    public double preserveCanvasMiddle(double offsetFrac, double oldVpdiv, double newVpdiv) {
        return anchorOffsetAfterZoom(offsetFrac, oldVpdiv, newVpdiv, 0.5);
    }

    /**
     * The {@code offsetFrac} that, after V/div changes from {@code oldVpdiv} to
     * {@code newVpdiv}, keeps the voltage under screen fraction {@code anchorFrac}
     * fixed (0..1 top..bottom).  Voltage there = {@code (offsetFrac − anchorFrac)·Y·V/div};
     * holding it constant gives the formula below.  {@code anchorFrac = 0.5} is the
     * canvas-middle case ({@link #preserveCanvasMiddle}); the mouse-Y fraction is the
     * ctrl+wheel case.
     */
    public double anchorOffsetAfterZoom(double offsetFrac, double oldVpdiv,
                                        double newVpdiv, double anchorFrac) {
        if (oldVpdiv <= 0 || newVpdiv <= 0) return offsetFrac;
        return anchorFrac + (offsetFrac - anchorFrac) * oldVpdiv / newVpdiv;
    }

    /** Relative tolerance for matching / comparing a V/div value against a 1-2-5 rung. */
    private final double V_DIV_RULE_TOL = 1e-6;

    /** Whether {@code v} sits on the 1-2-5 ladder (within {@link #V_DIV_RULE_TOL}). */
    public boolean onVoltsPerDivRule(double v, double[] rule) {
        for (double r : rule) {
            if (Math.abs(v - r) <= V_DIV_RULE_TOL * r) return true;
        }
        return false;
    }

    /**
     * The next 1-2-5 rung strictly beyond {@code v} in the zoom direction
     * ({@code dir < 0} = zoom in / smaller V/div, {@code dir > 0} = zoom out /
     * larger).  Returns {@code v} unchanged at the end of the ladder.
     */
    public double nextVoltsPerDivRung(double v, int dir, double[] rule) {
        if (dir > 0) {
            for (double r : rule) if (r > v * (1.0 + V_DIV_RULE_TOL)) return r;
            return v;
        }
        for (int i = rule.length - 1; i >= 0; i--) {
            if (rule[i] < v * (1.0 - V_DIV_RULE_TOL)) return rule[i];
        }
        return v;
    }

    /** Smallest absolute distance from {@code v} to any rung of {@code rule}. */
    private double distToRule(double v, double[] rule) {
        double best = Double.MAX_VALUE;
        for (double r : rule) {
            double d = Math.abs(v - r);
            if (d < best) best = d;
        }
        return best;
    }

    /**
     * Couples both channels' V/div for one zoom step so they ALWAYS keep their
     * ratio: one channel (the base) snaps to its next 1-2-5 rung and the other
     * scales by that same factor (so a follower may land off the rule).
     *
     * <ul>
     *   <li>Both on the 1-2-5 rule → the base is whichever choice leaves the SCALED
     *       channel nearest a rung (smallest {@link #distToRule absolute distance}).</li>
     *   <li>One off the rule → the on-rule channel is the base; both off → the one
     *       nearest its next rung in the zoom direction (smallest |ln(ratio)|).</li>
     * </ul>
     *
     * @param leftV   current left V/div  (&gt;0, or &le;0 when that channel is inactive)
     * @param rightV  current right V/div (&gt;0, or &le;0 when inactive)
     * @param dir     {@code -1} zoom in (smaller V/div), {@code +1} zoom out (larger)
     * @param rule    ascending 1-2-5 ladder, e.g. {@code OscParse.voltsPerDivTargets()}
     * @param vDivMax zoom-out ceiling (FS fills the full grid height); {@code <=0} = none
     * @return {@code {newLeftV, newRightV}}; an inactive channel is returned unchanged,
     *         and a blocked zoom-out (would exceed {@code vDivMax}) returns the inputs.
     */
    public double[] coupleVoltsPerDivZoom(double leftV, double rightV, int dir,
                                          double[] rule, double vDivMax) {
        boolean leftOn  = leftV  > 0;
        boolean rightOn = rightV > 0;
        if (!leftOn && !rightOn) return new double[] { leftV, rightV };
        if (leftOn ^ rightOn) {                       // single active channel
            double cur  = leftOn ? leftV : rightV;
            double next = nextVoltsPerDivRung(cur, dir, rule);
            if (exceedsCeil(dir, next, vDivMax)) next = cur;
            return new double[] { leftOn ? next : leftV, rightOn ? next : rightV };
        }
        boolean lRule = onVoltsPerDivRule(leftV, rule);
        boolean rRule = onVoltsPerDivRule(rightV, rule);
        double newL;
        double newR;
        if (lRule && rRule) {                         // both on rule: hold the ratio too
            // Step one channel (the base) to its next rung and scale the other by that
            // factor; pick the base that leaves the SCALED channel nearest a 1-2-5 rung
            // (e.g. 500µV/1mV zoom-in -> 250/500µV, since 250 is 50µV off 200, beating
            // 200/400µV where 400 is 100µV off 500).
            double lNext = nextVoltsPerDivRung(leftV,  dir, rule);
            double rNext = nextVoltsPerDivRung(rightV, dir, rule);
            double scaledRight = rightV * (lNext / leftV);    // left as base
            double scaledLeft  = leftV  * (rNext / rightV);   // right as base
            if (distToRule(scaledRight, rule) <= distToRule(scaledLeft, rule)) {
                newL = lNext;        newR = scaledRight;
            } else {
                newL = scaledLeft;   newR = rNext;
            }
        } else {                                      // proportional coupling
            boolean refLeft;
            if (lRule != rRule) {
                refLeft = lRule;                      // the on-rule channel leads
            } else {                                  // both off-rule: nearest its rung leads
                double tL = nextVoltsPerDivRung(leftV,  dir, rule);
                double tR = nextVoltsPerDivRung(rightV, dir, rule);
                refLeft = Math.abs(Math.log(tL / leftV)) <= Math.abs(Math.log(tR / rightV));
            }
            double refCur  = refLeft ? leftV : rightV;
            double refNext = nextVoltsPerDivRung(refCur, dir, rule);
            double ratio   = refNext / refCur;
            newL = refLeft ? refNext        : leftV  * ratio;
            newR = refLeft ? rightV * ratio : refNext;
        }
        if (exceedsCeil(dir, newL, vDivMax) || exceedsCeil(dir, newR, vDivMax)) {
            return new double[] { leftV, rightV };    // zoom-out blocked at FS-fills-height
        }
        return new double[] { newL, newR };
    }

    /** Whether a zoom-out result {@code v} overshoots the {@code vDivMax} ceiling. */
    private boolean exceedsCeil(int dir, double v, double vDivMax) {
        return dir > 0 && vDivMax > 0 && v > vDivMax * (1.0 + V_DIV_RULE_TOL);
    }

    /**
     * The vertical move limit for one channel: the maximum half-range, in
     * offsetFrac units, the zero line may sit from canvas middle before the
     * signal's ±FS extreme reaches the middle.  {@code half = peak/(Ydiv·vDiv)},
     * floored at 0.5 so the grid edges are always reachable.  The allowed band is
     * {@code [0.5 - half, 0.5 + half]}.
     */
    public double offsetMoveHalfRange(double vDiv, double peakVolts, int divisionsY) {
        if (vDiv <= 0 || peakVolts <= 0) return 0.5;
        return Math.max(0.5, peakVolts / (divisionsY * vDiv));
    }

    /**
     * Clamps a vertical-move {@code delta} (in offsetFrac units) so the channel's
     * zero line stays within its {@link #offsetMoveHalfRange} band — i.e. you can
     * pan the signal only until its ±FS extreme reaches the canvas middle.
     */
    public double clampOffsetDelta(double delta, double offsetFrac,
                                   double vDiv, double peakVolts, int divisionsY) {
        if (vDiv <= 0) return delta;
        double half = offsetMoveHalfRange(vDiv, peakVolts, divisionsY);
        double next = offsetFrac + delta;
        if (next < 0.5 - half) return (0.5 - half) - offsetFrac;
        if (next > 0.5 + half) return (0.5 + half) - offsetFrac;
        return delta;
    }
}
