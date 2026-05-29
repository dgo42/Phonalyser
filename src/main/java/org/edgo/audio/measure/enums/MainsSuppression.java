package org.edgo.audio.measure.enums;

/** Mains-hum (50/60 Hz + harmonics) suppression applied to the captured
 *  signal before FFT averaging.  {@link #NONE} = pass-through;
 *  {@link #IIR_COMB} = frequency-tracked IIR comb. */
public enum MainsSuppression {
    NONE,
    IIR_COMB;

    private MainsSuppression() {}

    /** Combo labels, index-aligned with {@link #values()}.  Lives on the
     *  enum (not a pane formatter) so both the FFT and scope panes can use
     *  it without a cross-package dependency. */
    public static final String[] LABELS = { "None", "IIR comb" };

    /** Parses an enum name, falling back to {@code def} on null / unknown. */
    public static MainsSuppression fromNameOr(String name, MainsSuppression def) {
        if (name == null) return def;
        try { return valueOf(name); }
        catch (IllegalArgumentException e) { return def; }
    }
}
