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

}
