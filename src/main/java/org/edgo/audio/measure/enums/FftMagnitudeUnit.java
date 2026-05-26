package org.edgo.audio.measure.enums;

/**
 * Magnitude unit selector for the FFT view's left axis.
 *
 * <p>The {@link org.edgo.audio.measure.fft.FftAnalyzer} always reports
 * magnitudes in dBFS (relative to the ADC full-scale).  This enum
 * converts that into whichever unit the user has picked in the
 * top-right combo of {@code FftPane}.
 *
 * <p>Conversion inputs:
 * <ul>
 *   <li>{@code dbFs}  — the analyser's per-bin amplitude in dBFS.</li>
 *   <li>{@code fundRefDbV} — the dBV value corresponding to 0 dBFS
 *       (typically {@code 20·log10(adcFsVoltageRms / 1 V)}).  When this
 *       isn't known yet the caller may pass {@code 0}, in which case
 *       {@link #DBV} simply equals {@link #DBFS}.</li>
 *   <li>{@code binBw} — bin bandwidth in Hz ({@code sampleRate / fftSize}),
 *       used by {@link #V_SQRT_HZ} for the PSD √Hz scaling.</li>
 * </ul>
 */
public enum FftMagnitudeUnit {
    V        ("V"),
    V_SQRT_HZ("V/√Hz"),
    DBV      ("dBV"),
    DBFS     ("dBFS");

    private final String label;

    private FftMagnitudeUnit(String label) { this.label = label; }

    /** Short display string used in the combo box and axis labels. */
    public String getLabel() { return label; }

    // ─── Cached parallel-array views ────────────────────────────────────
    //
    // The combo box and preference round-trip both need the full set of
    // enum names / labels in declaration order.  Building those arrays
    // once at class-load lets callers reach for them by method call
    // without re-walking values() or maintaining their own hard-coded
    // mirror of this enum.
    private static final String[] NAMES;
    private static final String[] LABELS;
    static {
        FftMagnitudeUnit[] all = values();
        NAMES  = new String[all.length];
        LABELS = new String[all.length];
        for (int i = 0; i < all.length; i++) {
            NAMES[i]  = all[i].name();
            LABELS[i] = all[i].label;
        }
    }

    /** All enum names ({@link #name()}) in declaration order — the form
     *  used as preference values.  Cached; do not mutate. */
    public static String[] names()  { return NAMES;  }

    /** All display labels ({@link #getLabel()}) in declaration order —
     *  the form shown in the combo box.  Cached; do not mutate. */
    public static String[] labels() { return LABELS; }

    /** Converts an analyser dBFS magnitude into this unit. */
    public double convertFromDbFs(double dbFs, double fundRefDbV, double binBw) {
        switch (this) {
            case DBFS:
                return dbFs;
            case DBV:
                return dbFs + fundRefDbV;
            case V: {
                double dbv = dbFs + fundRefDbV;
                return Math.pow(10.0, dbv / 20.0);
            }
            case V_SQRT_HZ: {
                double dbv = dbFs + fundRefDbV;
                double v   = Math.pow(10.0, dbv / 20.0);
                double bw  = (binBw > 0) ? binBw : 1.0;
                return v / Math.sqrt(bw);
            }
            default:
                return dbFs;
        }
    }

    /** Resolves a stored preference string (the enum name) back to the
     *  enum, falling back to {@link #DBV} when the value is unknown. */
    public static FftMagnitudeUnit fromName(String name) {
        if (name == null) return DBV;
        try {
            return FftMagnitudeUnit.valueOf(name);
        } catch (IllegalArgumentException e) {
            return DBV;
        }
    }
}
