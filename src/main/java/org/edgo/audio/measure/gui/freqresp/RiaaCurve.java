package org.edgo.audio.measure.gui.freqresp;

import lombok.experimental.UtilityClass;

/**
 * Static evaluator for the RIAA equalisation curves used by the Frequency
 * Response pane.  Three independent flags drive the result:
 *
 * <ul>
 *   <li>{@code reverse} — when {@code false} (the default), returns the
 *       playback (decode) curve — the canonical "RIAA curve" that drops
 *       from +20 dB at 20 Hz to −20 dB at 20 kHz.  When {@code true},
 *       returns the record (encode) curve, the mirror image.</li>
 *   <li>{@code iec} — when {@code true}, applies the IEC subsonic high-pass
 *       amendment (T4 = 7950 µs) on top of whichever direction (record /
 *       playback) is active.  Keeps the math invertible: record-with-IEC is
 *       exactly the inverse of playback-with-IEC, so a flat in-out chain
 *       still reads 0 dB everywhere.</li>
 * </ul>
 *
 * <p>The record transfer function in the s-domain is
 * <pre>
 *     H_record(s) = (s·T1 + 1)(s·T3 + 1) / (s·T2 + 1)
 * </pre>
 * with the four standard time constants:
 * <ul>
 *   <li>T1 = 3180 µs (50.05 Hz bass cut)</li>
 *   <li>T2 =  318 µs (500.5 Hz)</li>
 *   <li>T3 =   75 µs (2122 Hz treble lift)</li>
 *   <li>T4 = 7950 µs (20.02 Hz IEC subsonic high-pass)</li>
 * </ul>
 *
 * <p>All evaluations are normalised so the 1 kHz reading is 0 dB regardless of
 * which flags are active.  Values at {@code fHz ≤ 0} are clamped to a tiny
 * positive frequency to avoid {@code log(0)} blowups.
 */
@UtilityClass
public class RiaaCurve {

    public static final double T1_SEC = 3180e-6;
    public static final double T2_SEC =  318e-6;
    public static final double T3_SEC =   75e-6;
    public static final double T4_SEC = 7950e-6;

    /** Reference frequency at which the normalised curve reads 0 dB. */
    public static final double REF_HZ = 1000.0;

    /**
     * Returns the magnitude of the configured RIAA curve at {@code fHz} in
     * decibels, normalised so |H(1 kHz)| = 0 dB regardless of which flag
     * combination is active.
     *
     * @param fHz     frequency in Hz; values ≤ 0 are clamped to a tiny
     *                positive number so the formula never sees log(0)
     * @param reverse when {@code false} (default), returns the playback
     *                (decode) curve; when {@code true}, the record (encode)
     *                curve, vertically mirrored around 0 dB
     * @param iec     when {@code true}, also applies the IEC subsonic high-pass
     */
    public double evalDb(double fHz, boolean reverse, boolean iec) {
        double f = (fHz > 0.0) ? fHz : 1e-9;
        double db = recordDb(f, iec) - recordDb(REF_HZ, iec);
        return reverse ? db : -db;
    }

    /**
     * Record-curve magnitude in dB before the 1 kHz normalisation step.
     *
     * <p>When IEC is requested we DIVIDE the record magnitude by the IEC
     * single-pole high-pass magnitude (rather than multiply): mathematically
     * this is the inverse of how IEC enters the playback chain, which keeps
     * record⁻¹ · playback = identity even with IEC enabled on both sides.
     */
    private double recordDb(double fHz, boolean iec) {
        double w   = 2.0 * Math.PI * fHz;
        double wt1 = w * T1_SEC;
        double wt2 = w * T2_SEC;
        double wt3 = w * T3_SEC;
        double magSq = (1.0 + wt1 * wt1) * (1.0 + wt3 * wt3) / (1.0 + wt2 * wt2);
        if (iec) {
            double wt4  = w * T4_SEC;
            double hpSq = (wt4 * wt4) / (1.0 + wt4 * wt4);
            if (hpSq > 0.0) magSq /= hpSq;
        }
        return 10.0 * Math.log10(magSq);
    }
}
