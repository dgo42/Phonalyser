package org.edgo.audio.measure.gui.scope;

/**
 * Online avg / min / max / variance accumulator (Welford's method) used
 * by the oscilloscope measurement table.  Skips {@link Double#NaN}
 * inputs so missing-cycle samples don't poison the window stats.
 */
public final class MeasurementStats {

    private int count;
    private double mean;
    private double m2;
    private double min = Double.POSITIVE_INFINITY;
    private double max = Double.NEGATIVE_INFINITY;

    public void add(double v) {
        if (Double.isNaN(v)) return;
        count++;
        double delta = v - mean;
        mean += delta / count;
        m2 += delta * (v - mean);
        if (v < min) min = v;
        if (v > max) max = v;
    }

    public double getMean()  { return count > 0 ? mean : Double.NaN; }
    public double getMin()   { return count > 0 ? min  : Double.NaN; }
    public double getMax()   { return count > 0 ? max  : Double.NaN; }
    public double getSigma() { return count > 1 ? Math.sqrt(m2 / (count - 1)) : Double.NaN; }
}
