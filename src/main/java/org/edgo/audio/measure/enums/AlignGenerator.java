package org.edgo.audio.measure.enums;

/**
 * How the FFT view steers the DDS generator onto the FFT bin grid:
 * <ul>
 *   <li>{@link #NONE} — no alignment (the generator runs free),</li>
 *   <li>{@link #PID}  — a tunable PID controller (autotune-capable),</li>
 *   <li>{@link #FLL}  — a gain-ramped, transport-delay-aware integrator.</li>
 * </ul>
 */
public enum AlignGenerator {
    NONE("None"),
    PID("PID"),
    FLL("FLL");

    public final String label;

    private AlignGenerator(String label) {
        this.label = label;
    }

    public static AlignGenerator fromString(String s) {
        if (s == null) {
            return NONE;
        }
        try {
            return AlignGenerator.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
