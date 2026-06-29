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

package org.edgo.audio.measure.gui.widgets;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.edgo.audio.measure.gui.widgets.UnitFamily.Unit;

import lombok.Getter;

/**
 * The SWT-free brain of a {@link NumericStepField}: canonical value, unit
 * family, bounds, precision and one of three stepping policies.  Pure logic so
 * the wheel walks, unit parsing and clamping are unit-testable headless.
 *
 * <p>The three policies (selected by constructor — configuration is data, not
 * callbacks):
 * <ul>
 *   <li><b>FIXED</b> — wheel and arrows add fixed (possibly different)
 *       increments.</li>
 *   <li><b>LIST</b> — wheel and arrows jump along a value series (1-2-5
 *       scope resolutions, power-of-two averages, …); manual entry between
 *       list points is allowed.</li>
 *   <li><b>PERCENT</b> — the "careful 10 %" wheel: up adds 10 % and floors
 *       the result onto its decade's 10 %-grid (1000 → 1100 → … → 1900 →
 *       2000 → 2200); down steps along the 1-significant-digit grid when
 *       already on it (1000 → 900 → … → 200 → 100 → 90), else to the next
 *       lower 10 %-grid value (1300 → 1200).  Arrows add ±1 in the
 *       <em>displayed</em> unit (±1 kHz at 192 kHz, not ±1 Hz).</li>
 * </ul>
 *
 * <p>Values are clamped to {@code [min, max]} on every commit and step
 * (clamp-on-enter).  An explicitly typed unit suffix (e.g. {@code dBV})
 * becomes sticky for display until the next suffix-less entry; otherwise the
 * family's automatic display-unit switching applies.
 */
public final class NumericStepModel {

    /** Number with an optional trailing unit suffix; decimal comma accepted
     *  (normalised to a dot before matching).  Includes both micro code points
     *  — MICRO SIGN U+00B5 and GREEK SMALL LETTER MU U+03BC — since pasted
     *  scientific text and Greek keyboards produce the latter. */
    private static final Pattern NUMBER_WITH_UNIT =
            Pattern.compile("([+-]?[0-9]*\\.?[0-9]+(?:[eE][+-]?[0-9]+)?)\\s*([%µμ\\w./]*)");
    /** Lenient mid-edit grammar for the SWT Verify filter: any prefix of a
     *  valid number-with-unit (sign alone, dangling separator, exponent
     *  fragment, unit characters incl. µ and the per-div slash, ∞) or of a
     *  named-value label ("Nyquist/2").  {@link #commit} remains the strict
     *  gate. */
    private static final Pattern PARTIAL_INPUT =
            Pattern.compile("[+-]?[0-9]*[.,]?[0-9]*(?:[eE][+-]?[0-9]*)?[\\sa-zA-Z0-9µμ%/∞]*");
    /** Relative tolerance for grid / series membership checks. */
    private static final double REL_EPS = 1e-9;
    /** Significant digits every computed value is rounded to — kills the
     *  binary-float drift that would otherwise accumulate over wheel walks
     *  (1100.0000000001 breaking the next grid snap). */
    private static final int VALUE_SIG_DIGITS = 12;
    /** The PERCENT policy's wheel ratio: one notch targets ±10 %. */
    private static final double PERCENT_STEP_FACTOR = 1.1;
    /** PERCENT-policy wheel step while a log unit (dBV) is displayed: one
     *  notch walks the 10-dB grid (0 → −10 → −20 …) — stepping the linear
     *  value by 10 % would produce 0.83-dB crumbs. */
    private static final double LOG_WHEEL_STEP_DB = 10;
    /** PERCENT policy's nominal wheel step, shown in {@link #stepHint()}. */
    private static final String PERCENT_STEP_LABEL = "10 %";
    /** Language-neutral glyphs for {@link #stepHint()}: mouse wheel, step
     *  arrows, and the separator between LIST values. */
    private static final String WHEEL_GLYPH  = "⟳";
    private static final String ARROWS_GLYPH = "▲▼";
    private static final String SERIES_SEP   = "·";
    /** LIST series longer than this are abbreviated to first·…·last. */
    private static final int SERIES_HINT_MAX = 7;

    private enum Policy {
        FIXED, LIST, PERCENT;

        private Policy() {}
    }

    @Getter
    private final UnitFamily family;
    private final Policy policy;
    private double min;
    private double max;
    private final double wheelStep;   // FIXED only
    private final double arrowStep;   // FIXED only
    private double[] series;          // LIST only, kept sorted
    /** ≥ 0: fixed decimal count; −1: trim mode capped at {@link #maxDecimals}. */
    private final int decimals;
    private final int maxDecimals;
    /** Unit the user last typed explicitly; {@code null} = automatic. */
    private Unit stickyUnit;
    /** Optional value rendered / accepted as a label instead of a number
     *  (the sweep-points "Nyquist/2" entry); NaN = none. */
    private double namedValue = Double.NaN;
    private String namedValueLabel;
    @Getter
    private double value;

    /** FIXED policy: wheel adds {@code wheelStep}, arrows add
     *  {@code arrowStep}, values render with exactly {@code decimals}
     *  decimal digits. */
    public NumericStepModel(UnitFamily family, double min, double max,
                            double wheelStep, double arrowStep, int decimals) {
        this.family     = family;
        this.policy     = Policy.FIXED;
        this.min        = min;
        this.max        = max;
        this.wheelStep  = wheelStep;
        this.arrowStep  = arrowStep;
        this.decimals   = decimals;
        this.maxDecimals = decimals;
        this.value      = min;
    }

    /** LIST policy: wheel and arrows jump along {@code series} in the GIVEN
     *  order (a copy is taken) — pass the intended wheel order, usually
     *  ascending; manual entry off the list is allowed.  Values render with
     *  up to {@code maxDecimals} decimals, trailing zeros trimmed. */
    public NumericStepModel(UnitFamily family, double min, double max,
                            double[] series, int maxDecimals) {
        this.family     = family;
        this.policy     = Policy.LIST;
        this.min        = min;
        this.max        = max;
        this.wheelStep  = 0;
        this.arrowStep  = 0;
        this.series     = series.clone();
        this.decimals   = -1;
        this.maxDecimals = maxDecimals;
        this.value      = min;
    }

    /** PERCENT policy: the "careful 10 %" wheel, ±1 displayed-unit arrows.
     *  Values render with up to {@code maxDecimals} decimals, trailing zeros
     *  trimmed. */
    public NumericStepModel(UnitFamily family, double min, double max,
                            int maxDecimals) {
        this.family     = family;
        this.policy     = Policy.PERCENT;
        this.min        = min;
        this.max        = max;
        this.wheelStep  = 0;
        this.arrowStep  = 0;
        this.decimals   = -1;
        this.maxDecimals = maxDecimals;
        this.value      = min;
    }

    // -------------------------------------------------------------------------
    // Value + bounds
    // -------------------------------------------------------------------------

    /** Sets the canonical value, clamped to {@code [min, max]}.  NaN is
     *  ignored — a poisoned value could never be stepped or committed away. */
    public void setValue(double v) {
        if (Double.isNaN(v)) return;
        value = clamp(roundSig(v));
    }

    /** Updates the lower bound (e.g. a config-driven floor) and re-clamps. */
    public void setMin(double min) {
        this.min = min;
        value = clamp(value);
    }

    /** Updates the upper bound (e.g. Nyquist following the sample rate, the
     *  DAC full-scale following calibration) and re-clamps. */
    public void setMax(double max) {
        this.max = max;
        value = clamp(value);
    }

    /** Replaces the LIST series (e.g. the sweep-points list whose head follows
     *  the sample rate), in the given wheel order.  No-op for other
     *  policies. */
    public void setSeries(double[] series) {
        if (policy == Policy.LIST) this.series = series.clone();
    }

    /** Declares one value that renders and parses as {@code label} instead of
     *  a number — the sweep-points "Nyquist/2" entry, whose numeric value
     *  follows the sample rate.  Stepping treats it as its plain number. */
    public void setNamedValue(double value, String label) {
        this.namedValue = value;
        this.namedValueLabel = label;
    }

    // -------------------------------------------------------------------------
    // Stepping
    // -------------------------------------------------------------------------

    /** One wheel notch: {@code dir} = +1 up / −1 down. */
    public void wheel(int dir) {
        switch (policy) {
            case FIXED:   setValue(value + dir * wheelStep); break;
            case LIST:    setValue(listJump(dir));           break;
            case PERCENT:
                Unit u = currentUnit();
                if (u.log()) {
                    setValue(u.toCanonical(logGridStep(u.fromCanonical(value), dir)));
                } else {
                    setValue(dir > 0 ? percentUp(value) : percentDown(value));
                }
                break;
        }
    }

    /** One arrow-key step: {@code dir} = +1 up / −1 down. */
    public void arrow(int dir) {
        switch (policy) {
            case FIXED:   setValue(value + dir * arrowStep); break;
            case LIST:    setValue(listJump(dir));           break;
            case PERCENT: setValue(plusOneDisplayedUnit(dir)); break;
        }
    }

    /** Next multiple of {@link #LOG_WHEEL_STEP_DB} from {@code db} in
     *  {@code dir}: 0 → −10 → −20 going down; an off-grid −3.5 snaps to
     *  −10 down and 0 up. */
    private double logGridStep(double db, int dir) {
        double d = roundSig(db) / LOG_WHEEL_STEP_DB;
        double next = (dir > 0)
                ? Math.floor(d + REL_EPS) + 1
                : Math.ceil(d - REL_EPS) - 1;
        return next * LOG_WHEEL_STEP_DB;
    }

    /** ±1 in the unit currently displayed: ±1 kHz at 192 kHz, ±1 mV at
     *  200 mV, ±1 dB when dBV is sticky. */
    private double plusOneDisplayedUnit(int dir) {
        Unit u = currentUnit();
        if (u.log()) {
            return u.toCanonical(u.fromCanonical(value) + dir);
        }
        return value + dir * u.factor();
    }

    /** Up: add 10 % and floor the result onto its decade's 10 %-grid —
     *  1000 → 1100 → … → 1900 → 2000 (2090 floored) → 2200.  The grid never
     *  drops below one display LSB, so steps near the minimum stay visible
     *  (duty 0.001 % steps by 0.001, not by an invisible 0.0001); from 0 —
     *  which sits on no decade grid — the first step is one display LSB. */
    private double percentUp(double v) {
        double lsb = displayLsb();
        if (v <= 0) return lsb;
        double target = v * PERCENT_STEP_FACTOR;
        double g = Math.max(gridStep(target), lsb);
        double r = Math.floor(target / g * (1 + REL_EPS)) * g;
        if (r <= v * (1 + REL_EPS)) r = v + g;   // always make progress
        return r;
    }

    /** Down: along the 1-significant-digit grid when already on it
     *  (1000 → 900 → … → 200 → 100 → 90), else to the next lower
     *  10 %-grid value (1300 → 1200, 2200 → 2100).  Same display-LSB grid
     *  floor as {@link #percentUp}. */
    private double percentDown(double v) {
        if (v <= 0) return v;
        double lsb = displayLsb();
        double decade = Math.pow(10, Math.floor(Math.log10(v) + REL_EPS));
        double mantissa = v / decade;
        long m = Math.round(mantissa);
        if (decade >= lsb && Math.abs(mantissa - m) < REL_EPS * 10) {
            // On the 1-significant-digit grid: m·10^k → (m−1)·10^k, 1 → 9·10^(k−1).
            return m > 1 ? (m - 1) * decade : 9 * decade / 10.0;
        }
        double g = Math.max(gridStep(v), lsb);
        double r = (Math.ceil(v / g * (1 - REL_EPS)) - 1) * g;   // next lower multiple
        if (r >= v * (1 - REL_EPS)) r = v - g;
        return r;
    }

    /** One least-significant display digit in canonical units — the smallest
     *  step that visibly changes the rendered text.  0 (no floor) for the log
     *  unit, whose canonical step has no fixed display LSB, and for FIXED
     *  fields, whose steps are explicit. */
    private double displayLsb() {
        if (decimals >= 0) return 0;
        Unit u = currentUnit();
        if (u.log()) return 0;
        return Math.pow(10, -maxDecimals) * u.factor();
    }

    /** The 10 %-grid spacing at {@code v}: a tenth of its decade
     *  (1000…9999 → 100, 100…999 → 10, …). */
    private double gridStep(double v) {
        return Math.pow(10, Math.floor(Math.log10(v) + REL_EPS)) / 10.0;
    }

    /** Next series entry in {@code dir} from the current value.  A value ON
     *  the list walks it in GIVEN order (the sweep-points list pins the
     *  rate-derived "Nyquist/2" entry first, ahead of numerically smaller
     *  presets); an off-list value jumps to the numerically nearest entry in
     *  the step direction.  The ends saturate — including a manually-entered
     *  value beyond the numeric extremes, which stays put (an up-gesture
     *  must never decrease the value). */
    private double listJump(int dir) {
        for (int i = 0; i < series.length; i++) {
            if (sameValue(series[i], value)) {
                int next = i + dir;
                if (next < 0 || next >= series.length) return value;   // end of list
                return series[next];
            }
        }
        // Off-list: nearest entry in the numeric direction.
        if (dir > 0) {
            double best = Double.POSITIVE_INFINITY;
            for (double s : series) {
                if (s > value * (1 + REL_EPS) + Double.MIN_NORMAL && s < best) best = s;
            }
            return Double.isInfinite(best) && !contains(series, best) ? value : best;
        }
        double best = Double.NEGATIVE_INFINITY;
        for (double s : series) {
            if (s < value * (1 - REL_EPS) - Double.MIN_NORMAL && s > best) best = s;
        }
        return Double.isInfinite(best) ? value : best;
    }

    private boolean sameValue(double a, double b) {
        if (a == b) return true;   // covers equal infinities
        // A mixed finite/infinite pair must compare unequal — the relative
        // epsilon below degenerates to ∞ <= ∞ and would match everything.
        if (Double.isInfinite(a) || Double.isInfinite(b)) return false;
        return Math.abs(a - b) <= Math.max(Math.abs(a), Math.abs(b)) * REL_EPS;
    }

    private boolean contains(double[] arr, double v) {
        for (double s : arr) {
            if (sameValue(s, v)) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Text round-trip
    // -------------------------------------------------------------------------

    /** Formats the current value in the current display unit: fixed decimals
     *  for FIXED policy, up-to-{@code maxDecimals} with trailing-zero trim
     *  otherwise.  Infinity renders as {@code ∞}. */
    public String text() {
        if (Double.isInfinite(value)) return "∞";
        if (isNamedValue(value)) return namedValueLabel;
        return formatIn(value, currentUnit());
    }

    /** Formats {@code canonical} in {@code u}: fixed decimals for FIXED policy,
     *  up-to-{@code maxDecimals} with trailing-zero trim otherwise. */
    private String formatIn(double canonical, Unit u) {
        double x = u.fromCanonical(canonical);
        // Locale.ROOT so the decimal separator is always '.', matching the
        // dot-based parser in commit() and the canonical value contract.
        // Without it a comma-decimal UI locale (uk, de, fr, …) renders "0,5",
        // which downstream dot-only parsers reject.
        String num = (decimals >= 0)
                ? String.format(Locale.ROOT, "%." + decimals + "f", x)
                : trimTrailingZeros(String.format(Locale.ROOT, "%." + maxDecimals + "f", x));
        String suffix = u.suffix();
        return suffix.isEmpty() ? num : num + " " + suffix;
    }

    /**
     * Language-neutral, one-line description of what one wheel notch and one
     * arrow step actually do — for the field's tooltip, so the hint can never
     * drift from the real behaviour the way a hand-written tooltip does.
     * Reflects the live policy and display unit:
     * <ul>
     *   <li>FIXED → {@code ⟳ ±<wheelStep>, ▲▼ ±<arrowStep>}</li>
     *   <li>PERCENT (linear) → {@code ⟳ ±10 %, ▲▼ ±1 <unit>}; (dBV) →
     *       {@code ⟳ ±10 dB, ▲▼ ±1 dB}</li>
     *   <li>LIST → {@code ⟳▲▼ <values>} (abbreviated when long)</li>
     * </ul>
     * Glyphs: {@code ⟳} = mouse wheel, {@code ▲▼} = arrows / step buttons.
     */
    public String stepHint() {
        switch (policy) {
            case FIXED:
                return WHEEL_GLYPH + " ±" + formatStep(wheelStep)
                        + ", " + ARROWS_GLYPH + " ±" + formatStep(arrowStep);
            case PERCENT: {
                Unit u = currentUnit();
                if (u.log()) {
                    return WHEEL_GLYPH + " ±" + (int) LOG_WHEEL_STEP_DB + " dB, " + ARROWS_GLYPH + " ±1 dB";
                }
                String suffix = u.suffix();
                return WHEEL_GLYPH + " ±" + PERCENT_STEP_LABEL + ", "
                        + ARROWS_GLYPH + " ±1" + (suffix.isEmpty() ? "" : " " + suffix);
            }
            case LIST:
                return WHEEL_GLYPH + ARROWS_GLYPH + " " + seriesHint();
            default:
                return "";
        }
    }

    /** A FIXED step rendered in its own natural display unit (so a 0.001 s step
     *  reads "1 ms", not "0.001 s"). */
    private String formatStep(double canonicalStep) {
        return formatIn(canonicalStep, family.displayUnit(canonicalStep));
    }

    /** The LIST series for {@link #stepHint()}: every entry when short, else
     *  first·…·last. */
    private String seriesHint() {
        int n = series.length;
        if (n == 0) return "";
        if (n > SERIES_HINT_MAX) {
            return seriesEntry(series[0]) + SERIES_SEP + "…" + SERIES_SEP + seriesEntry(series[n - 1]);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(SERIES_SEP);
            sb.append(seriesEntry(series[i]));
        }
        return sb.toString();
    }

    private String seriesEntry(double v) {
        if (Double.isInfinite(v)) return "∞";
        if (isNamedValue(v)) return namedValueLabel;
        return formatIn(v, family.displayUnit(v));
    }

    /** Parses {@code text} (number + optional unit suffix of this family,
     *  decimal comma accepted, {@code ∞}/{@code inf} when the field is
     *  unbounded above), clamps, and commits.  An explicit suffix becomes the
     *  sticky display unit; suffix-less entry reverts to automatic.
     *
     *  @return {@code false} (value unchanged) when the text is not a valid
     *          number-with-unit of this family */
    public boolean commit(String text) {
        if (text == null) return false;
        // Normalise: decimal comma → dot, Greek mu → micro sign (pasted
        // scientific text and Greek keyboards produce U+03BC), and drop one
        // dangling separator ("200." / "200,") the way Double.parseDouble
        // would have tolerated it.
        String t = text.trim().replace(',', '.').replace('μ', 'µ');
        if (t.isEmpty()) return false;
        if (t.length() > 1 && t.endsWith(".") && Character.isDigit(t.charAt(t.length() - 2))) {
            t = t.substring(0, t.length() - 1);
        }
        if (Double.isInfinite(max)
                && (t.equals("∞") || t.equalsIgnoreCase("inf") || t.equalsIgnoreCase("infinity"))) {
            stickyUnit = null;
            value = Double.POSITIVE_INFINITY;
            return true;
        }
        if (namedValueLabel != null && t.equalsIgnoreCase(namedValueLabel.trim())) {
            stickyUnit = null;
            value = clamp(roundSig(namedValue));
            return true;
        }
        Matcher m = NUMBER_WITH_UNIT.matcher(t);
        if (!m.matches()) return false;
        double num;
        try {
            num = Double.parseDouble(m.group(1));
        } catch (NumberFormatException ex) {
            return false;
        }
        String suffix = m.group(2).trim();
        Unit unit;
        if (suffix.isEmpty()) {
            unit = family.defaultUnit(value);
            stickyUnit = null;
        } else {
            unit = family.match(suffix);
            if (unit == null) return false;
            // Only the log unit (dBV) sticks: the family's range-based display
            // switching can never select it, so an explicit choice must hold.
            // Linear units always re-enter the automatic range switching —
            // typing "499 mV" and stepping past 0.5 V must show volts.
            stickyUnit = unit.log() ? unit : null;
        }
        value = clamp(roundSig(unit.toCanonical(num)));
        return true;
    }

    /** Unit the field currently renders in: the explicitly-typed sticky unit
     *  if any, else the family's automatic choice for the value. */
    public Unit currentUnit() {
        return stickyUnit != null ? stickyUnit : family.displayUnit(value);
    }

    /** True while the log unit (dBV) is the sticky display unit — the one
     *  display choice worth persisting, since the automatic range switching
     *  can never select it. */
    public boolean isLogDisplay() {
        return stickyUnit != null && stickyUnit.log();
    }

    /** Restores ({@code true}) or clears ({@code false}) the log display
     *  unit — the persisted counterpart of typing an explicit dBV suffix.
     *  No-op for families without a log unit. */
    public void setLogDisplay(boolean on) {
        Unit log = family.logUnit();
        if (log == null) return;
        if (on) {
            stickyUnit = log;
        } else if (isLogDisplay()) {
            stickyUnit = null;
        }
    }

    /** Lenient mid-edit acceptance for the SWT Verify filter — any prefix of a
     *  valid entry passes (bare sign, dangling separator, exponent fragment,
     *  unit characters, ∞) so typing is never blocked halfway; {@link #commit}
     *  stays the strict gate.  Shares this class's grammar so the two can't
     *  drift apart. */
    public boolean acceptsPartial(String text) {
        return PARTIAL_INPUT.matcher(text).matches();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private double clamp(double v) {
        return Math.max(min, Math.min(max, v));
    }

    private boolean isNamedValue(double v) {
        return namedValueLabel != null
                && Math.abs(v - namedValue) <= Math.abs(namedValue) * REL_EPS;
    }

    /** Rounds to {@link #VALUE_SIG_DIGITS} significant digits so wheel walks
     *  stay exactly on their grids. */
    private double roundSig(double v) {
        if (v == 0 || !Double.isFinite(v)) return v;
        double scale = Math.pow(10, VALUE_SIG_DIGITS - 1 - Math.floor(Math.log10(Math.abs(v))));
        return Math.round(v * scale) / scale;
    }

    private String trimTrailingZeros(String num) {
        if (num.indexOf('.') < 0 && num.indexOf(',') < 0) return num;
        String s = num;
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".") || s.endsWith(",")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
