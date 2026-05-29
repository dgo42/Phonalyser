package org.edgo.audio.measure.enums;

/** Per-channel high-frequency cleanup mode for the oscilloscope.
 *  {@link #NONE} = off; {@link #HZ_80} = 80 kHz Chebyshev low-pass (for
 *  continuous HF content); {@link #DESPIKE} = median de-spike (for
 *  impulsive glitches, no ringing). */
public enum LpfMode {
    NONE(0.0, 0),
    HZ_80(80_000.0, 0),
    DESPIKE(0.0, 7);

    /** −3 dB corner in Hz when this is a low-pass; {@code 0} otherwise. */
    public final double cutoffHz;
    /** Median window in samples when this is a de-spike; {@code 0} otherwise. */
    public final int    window;

    LpfMode(double cutoffHz, int window) {
        this.cutoffHz = cutoffHz;
        this.window   = window;
    }

    /** Combo labels, index-aligned with {@link #values()}. */
    public static final String[] LABELS = { "None", "80kHz", "Despike" };

    /** Parses an enum name, falling back to {@code def} on null / unknown. */
    public static LpfMode fromNameOr(String name, LpfMode def) {
        if (name == null) return def;
        try { return valueOf(name); }
        catch (IllegalArgumentException e) { return def; }
    }
}
