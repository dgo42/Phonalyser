package org.edgo.audio.measure.enums;

/** Overlap fraction between successive FFT frames.  Higher overlap
 *  speeds up the analyser update rate at the cost of CPU. */
public enum FftOverlap {
    PCT_0(0.0,    "0%"),
    PCT_50(0.5,   "50%"),
    PCT_75(0.75,  "75%"),
    PCT_87_5(0.875,  "87.5%"),
    PCT_92_75(0.9275, "92.75%");

    public final double fraction;
    public final String label;

    private FftOverlap(double fraction, String label) {
        this.fraction = fraction;
        this.label    = label;
    }

    public static FftOverlap fromString(String s) {
        String norm = s.trim().replace("%", "").replace(",", ".");
        switch (norm) {
            case "0":
                return PCT_0;
            case "50":
                return PCT_50;
            case "75":
                return PCT_75;
            case "87.5":
            case "87":
                return PCT_87_5;
            case "92.75":
            case "92":
            case "93":
                return PCT_92_75;
            default:
                throw new IllegalArgumentException(
                        "Unknown overlap: " + s + " — use 0, 50, 75, 87.5, or 92.75");
        }
    }
}
