package org.edgo.audio.measure.gui.scope;

import lombok.experimental.UtilityClass;

import java.util.Locale;

/**
 * Pure-math number / colour / string helpers used by the oscilloscope's
 * rendering and measurement display.  Decoupled from {@link OscilloscopeView}
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
        return shortNum(v * 1e6) + "u";
    }

    private String shortNum(double v) {
        long iv = Math.round(v);
        if (Math.abs(v - iv) < 1e-4) return Long.toString(iv);
        return String.format(Locale.ROOT, "%.1f", v);
    }

    /**
     * Returns the next "nice" step target strictly above (delta &gt; 0) /
     * below (delta &lt; 0) {@code current}, or {@code current} unchanged
     * if no such target exists in {@code targets} (sorted ascending).
     */
    public double nextTargetFrom(double current, double[] targets, int delta) {
        if (delta > 0) {
            for (double t : targets) {
                if (t > current) return t;
            }
        } else if (delta < 0) {
            for (int i = targets.length - 1; i >= 0; i--) {
                if (targets[i] < current) return targets[i];
            }
        }
        return current;
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
     * Index of the entry in {@code values} whose parsed double is closest
     * to {@code target} — used to seed the hysteresis selector from prefs.
     */
    public int nearestIndex(String[] values, double target) {
        int best = 0;
        double bestDist = Double.POSITIVE_INFINITY;
        for (int i = 0; i < values.length; i++) {
            double v = Double.parseDouble(values[i]);
            double d = Math.abs(v - target);
            if (d < bestDist) { bestDist = d; best = i; }
        }
        return best;
    }

    /**
     * Parses a seconds value accepting an optional trailing "s" and either
     * '.' or ',' as decimal separator.  Returns {@code null} on parse
     * failure or non-positive values.
     */
    public Double parseSeconds(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.endsWith("s")) t = t.substring(0, t.length() - 1).trim();
        if (t.isEmpty()) return null;
        try {
            double v = Double.parseDouble(t.replace(',', '.'));
            return v <= 0 ? null : v;
        } catch (NumberFormatException ex) {
            return null;
        }
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
        if (oldVpdiv <= 0 || newVpdiv <= 0) return offsetFrac;
        return (offsetFrac - 0.5) * oldVpdiv / newVpdiv + 0.5;
    }

    /**
     * "0.0", "0.1", … "5.0" — 0.1-division steps for the trigger-
     * hysteresis selector.  Formatted with {@link Locale#ROOT} so the
     * decimal separator is always a period (matches
     * {@link Double#parseDouble}).
     */
    public String[] hysteresisDivSteps() {
        int count = 51;
        String[] out = new String[count];
        for (int i = 0; i < count; i++) out[i] = String.format(Locale.ROOT, "%.1f", 0.1 * i);
        return out;
    }

    /**
     * Formats {@code v} as plain seconds with up to 3 decimal places,
     * trailing zeros and dot stripped.  Used by the trigger-mode "auto
     * timeout = X s" label.  Distinct from {@link #formatSeconds(double)}
     * which switches to ms / µs / ns prefixes.
     */
    public String formatSecondsTrimmed(double v) {
        String s = String.format(Locale.ROOT, "%.3f", v);
        if (s.contains(".")) {
            int end = s.length();
            while (end > 0 && s.charAt(end - 1) == '0') end--;
            if (end > 0 && s.charAt(end - 1) == '.') end--;
            s = s.substring(0, end);
        }
        return s + " s";
    }
}
