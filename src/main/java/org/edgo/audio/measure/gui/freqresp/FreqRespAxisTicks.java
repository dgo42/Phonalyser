package org.edgo.audio.measure.gui.freqresp;

import lombok.experimental.UtilityClass;
import org.edgo.audio.measure.gui.fft.FftAxisTicks;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-math tick-position calculators for the Frequency Response chart axes.
 * Decoupled from {@link FreqRespView} so the formulas are unit-testable
 * without instantiating an SWT widget.
 *
 * <p>The magnitude axis is always linear in dB (frequency-response curves
 * are inherently dB-scaled), so this class doesn't need the log/linear
 * branching that {@link FftAxisTicks} has.  The frequency axis is always
 * log-scaled.  A phase axis on the right side covers ±180° in linear
 * degree steps.
 *
 * <p>Reuses {@link FftAxisTicks#logFreqAll} and {@link FftAxisTicks#niceLinear}
 * directly so both panes share the same tick-rounding heuristics.
 */
@UtilityClass
public class FreqRespAxisTicks {

    /** Major ticks on the log frequency axis — labelled positions. */
    public double[] freqMajor(double freqMin, double freqMax) {
        return FftAxisTicks.logFreqAll(freqMin, freqMax, true);
    }

    /** Minor ticks on the log frequency axis — positions in
     *  {@link FftAxisTicks#logFreqAll}'s "all" set minus the major
     *  positions.  Used for short tick marks on the axis. */
    public double[] freqMinor(double freqMin, double freqMax) {
        double[] all   = FftAxisTicks.logFreqAll(freqMin, freqMax, false);
        double[] major = FftAxisTicks.logFreqAll(freqMin, freqMax, true);
        return FftAxisTicks.subtract(all, major);
    }

    /** Major (labelled) ticks on the magnitude dB axis.  Picks a step from
     *  the 1/2/2.5/5/10 family targeting ~10 visible ticks. */
    public double[] magMajor(double magBotDb, double magTopDb) {
        return FftAxisTicks.niceLinear(magBotDb, magTopDb, 10);
    }

    /** Minor (unlabelled) ticks on the magnitude dB axis — every 5 dB
     *  minus the major positions, so the column never renders denser than
     *  the 5 dB grid. */
    public double[] magMinor(double magBotDb, double magTopDb) {
        double[] major = magMajor(magBotDb, magTopDb);
        double step = 5.0;
        double first = Math.ceil(magBotDb / step) * step;
        List<Double> out = new ArrayList<>();
        for (double m = first; m <= magTopDb + 1e-9; m += step) {
            if (m < magBotDb || m > magTopDb) continue;
            boolean isMajor = false;
            for (double M : major) {
                if (Math.abs(m - M) < 1e-6) { isMajor = true; break; }
            }
            if (!isMajor) out.add(m);
        }
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = out.get(i);
        return arr;
    }

    /**
     * Major ticks on the phase axis.  Phase is always shown wrapped to
     * ±180°, so the labelled set is the same regardless of zoom:
     * {@code {−180, −135, −90, −45, 0, 45, 90, 135, 180}}.
     */
    public double[] phaseMajor() {
        return new double[]{ -180, -135, -90, -45, 0, 45, 90, 135, 180 };
    }
}
