package org.edgo.audio.measure.enums;

/** Waveform produced by the audio signal generator. */
public enum GenSignalForm {
    SINE, TRIANGLE, RECTANGLE, WHITE_NOISE, PINK_NOISE, PINK_NOISE_LINEAR, SINE_COMPENSATED,
    LINEAR_SWEEP, LOG_SWEEP, DUAL_TONE;

    private GenSignalForm() {}

    public static GenSignalForm fromString(String s) {
        return switch (s.toLowerCase()) {
            case "sine"                                                 -> SINE;
            case "triangle", "tri"                                      -> TRIANGLE;
            case "rectangle", "rect", "square", "pulse"                 -> RECTANGLE;
            case "white", "white_noise"                                 -> WHITE_NOISE;
            case "pink", "pink_noise"                                   -> PINK_NOISE;
            case "pink_linear", "pink_noise_linear"                     -> PINK_NOISE_LINEAR;
            case "sine_compensated", "sine_hmc"                         -> SINE_COMPENSATED;
            case "linear_sweep", "sweep", "chirp"                       -> LINEAR_SWEEP;
            case "log_sweep", "farina"                                  -> LOG_SWEEP;
            case "dual_tone", "dualtone", "twotone", "two_tone"         -> DUAL_TONE;
            default -> throw new IllegalArgumentException("Unknown signal form: " + s +
                    ". Valid: sine, triangle, rectangle, white_noise, pink_noise, pink_noise_linear, sine_compensated, linear_sweep, log_sweep, dual_tone");
        };
    }
}
