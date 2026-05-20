package org.edgo.audio.measure.gui.fft;

import lombok.experimental.UtilityClass;
import org.edgo.audio.measure.enums.FftMagnitudeUnit;

/**
 * Pure-math unit-conversion and string-formatting helpers for the FFT
 * chart.  Splits the visible numeric range conversion ({@code dBFS ↔ dBV
 * ↔ V}), the frequency / magnitude label formatters, and the magnitude
 * fractional-position kernel out of {@link FftView} so the formulas are
 * unit-testable without instantiating an SWT widget.
 */
@UtilityClass
public class FftFormat {

    /**
     * Converts a magnitude range {@code [oldTop, oldBot]} from one unit
     * to another, keeping the visible signal at the same vertical
     * position so a unit-combo change preserves the on-screen levels.
     *
     * @param fsDbv ADC full-scale RMS voltage expressed in dBV
     *              ({@code 20·log10(adcFsVoltageRms)}); pass 0 when no
     *              calibration is available.
     */
    public double[] convertMagRange(double oldTop, double oldBot,
                                    FftMagnitudeUnit from, FftMagnitudeUnit to,
                                    double fsDbv) {
        if (from == to) return new double[] {oldTop, oldBot};
        double topDbv = toDbv(oldTop, from, fsDbv);
        double botDbv = toDbv(oldBot, from, fsDbv);
        double newTop = fromDbv(topDbv, to, fsDbv);
        double newBot = fromDbv(botDbv, to, fsDbv);
        return new double[] {newTop, newBot};
    }

    /** Converts {@code v} (in {@code unit}) to dBV.  {@code fsDbv} is the
     *  ADC full-scale expressed in dBV — used to anchor dBFS values. */
    public double toDbv(double v, FftMagnitudeUnit unit, double fsDbv) {
        switch (unit) {
            case DBV:       return v;
            case DBFS:      return v + fsDbv;
            case V:         return (v > 0) ? 20 * Math.log10(v) : -300;
            case V_SQRT_HZ: return (v > 0) ? 20 * Math.log10(v) : -300;
            default:        return v;
        }
    }

    /** Converts a dBV value to {@code unit}.  Inverse of {@link #toDbv}. */
    public double fromDbv(double dbv, FftMagnitudeUnit unit, double fsDbv) {
        switch (unit) {
            case DBV:       return dbv;
            case DBFS:      return dbv - fsDbv;
            case V:
            case V_SQRT_HZ: return Math.pow(10, dbv / 20.0);
            default:        return dbv;
        }
    }

    /**
     * Shared mapping kernel: returns the fractional position 0..1 of
     * {@code v} within the {@code [bot, top]} range, where 0 = top and
     * 1 = bot.  No clamping — callers clip after they know the plot
     * geometry.
     */
    public double magToYFraction(double v, double top, double bot, FftMagnitudeUnit unit) {
        if (FftAxisTicks.isMagLog(unit)) {
            double vL  = (v   <= 0) ? Double.NEGATIVE_INFINITY : Math.log10(v);
            double topL = (top <= 0) ? -30 : Math.log10(top);
            double botL = (bot <= 0) ? -30 : Math.log10(bot);
            if (topL <= botL) return 0;
            return (topL - vL) / (topL - botL);
        }
        return (top - v) / (top - bot);
    }

    /** Compact frequency label: "1.0 Hz" / "1.50 kHz". */
    public String formatFrequency(double f) {
        if (!Double.isFinite(f) || f <= 0) return "—";
        if (f >= 1000) return String.format("%.2f kHz", f / 1000);
        return String.format("%.1f Hz", f);
    }

    /** Fine-grained formatter for the crosshair readout and the THD-table
     *  fundamental row — 4 decimal places so the user can read the
     *  sub-Hz refinement that {@code FftAnalyzer.Result#fundamentalHzRefined}
     *  produces.  Switches to kHz at 10 kHz so digits stay aligned. */
    public String formatFrequencyFine(double f) {
        if (!Double.isFinite(f) || f <= 0) return "—";
        if (f >= 10_000) return String.format("%.4f kHz", f / 1000);
        return String.format("%.4f Hz", f);
    }

    /** Integer-only frequency formatter used on the linear axis labels —
     *  no fractional Hz, kHz with 0 / 1 decimals depending on size. */
    public String formatFrequencyInteger(double f) {
        if (!Double.isFinite(f)) return "—";
        if (f >= 1000) {
            double k = f / 1000;
            if (Math.abs(k - Math.round(k)) < 0.05) return String.format("%d kHz", (long) Math.round(k));
            return String.format("%.1f kHz", k);
        }
        return String.format("%d Hz", (long) Math.round(f));
    }

    /** Magnitude label with unit suffix — used for crosshair readouts. */
    public String formatMagnitude(double v, FftMagnitudeUnit unit) {
        if (!Double.isFinite(v)) return "—";
        switch (unit) {
            case DBFS: return String.format("%.1f dBFS", v);
            case DBV:  return String.format("%.1f dBV",  v);
            case V:    return formatVoltsSi(v) + "V";
            case V_SQRT_HZ: return formatVoltsSi(v) + "V/√Hz";
            default:   return String.format("%g", v);
        }
    }

    /** Same as {@link #formatMagnitude} but without the unit suffix — used
     *  for per-tick axis labels (the unit is drawn once above the axis).
     *  Drops the "+" prefix on positive dB values. */
    public String formatMagnitudeBare(double v, FftMagnitudeUnit unit) {
        if (!Double.isFinite(v)) return "—";
        switch (unit) {
            case DBFS:
            case DBV:  return String.format("%.1f", v);
            case V:
            case V_SQRT_HZ: return formatVoltsSi(v);
            default:   return String.format("%g", v);
        }
    }

    /**
     * Sensible top-of-axis value for a magnitude unit.  dBFS = 0; dBV
     * rounds up the ADC FS dBV to the next 10 dB step; linear amplitude
     * axes use a bit above the ADC FS voltage.
     */
    public double magMaxFor(FftMagnitudeUnit unit, double adcFsVrms) {
        double fs = Math.max(1e-12, adcFsVrms);
        switch (unit) {
            case DBFS:      return 0;
            case DBV: {
                double dbvFs = 20 * Math.log10(fs);
                return Math.ceil((dbvFs + 5) / 10.0) * 10;
            }
            case V:         return Math.ceil(fs + 1);
            case V_SQRT_HZ: return Math.max(1e-3, fs);
            default:        return 0;
        }
    }

    /**
     * Sensible bottom-of-axis value for a magnitude unit.  Linear-amplitude
     * axes (V, V/√Hz) bottom out at 1 fV which is well below the thermal
     * noise floor of any realistic ADC; the per-pane zoom logic can still
     * tighten in beyond this default.
     */
    public double magMinFor(FftMagnitudeUnit unit, double adcFsVrms) {
        switch (unit) {
            case DBFS:      return -300;
            case DBV:       return -300 + 20 * Math.log10(Math.max(1e-12, adcFsVrms));
            case V:
            case V_SQRT_HZ: return 1e-15;
            default:        return -300;
        }
    }

    /** SI-prefix formatter for the V / V/√Hz axes.  Keeps axis labels
     *  compact: 1.5 V instead of 1.500e+00, 100 mV instead of 0.1000. */
    public String formatVoltsSi(double v) {
        if (v == 0) return "0 ";
        double abs = Math.abs(v);
        String prefix;
        double scale;
        if      (abs >= 1e3)  { prefix = "k"; scale = 1e3; }
        else if (abs >= 1)    { prefix = "";  scale = 1;   }
        else if (abs >= 1e-3) { prefix = "m"; scale = 1e-3; }
        else if (abs >= 1e-6) { prefix = "µ"; scale = 1e-6; }
        else if (abs >= 1e-9) { prefix = "n"; scale = 1e-9; }
        else if (abs >= 1e-12){ prefix = "p"; scale = 1e-12; }
        else                  { prefix = "f"; scale = 1e-15; }
        double s = v / scale;
        String m;
        if      (Math.abs(s) >= 100) m = String.format("%.0f", s);
        else if (Math.abs(s) >= 10)  m = String.format("%.1f", s);
        else                          m = String.format("%.2f", s);
        if (m.contains(".")) {
            m = m.replaceAll("0+$", "");
            if (m.endsWith(".")) m = m.substring(0, m.length() - 1);
        }
        return m + " " + prefix;
    }
}
