package org.edgo.audio.measure.enums;

import java.util.Locale;

/** Window function used by the FFT analyser. */
public enum WindowType {
    RECTANGULAR,
    HANN,
    BLACKMAN_HARRIS_4,
    BLACKMAN_HARRIS_7,
    FLAT_TOP,
    DOLPH_CHEBYSHEV_150,
    DOLPH_CHEBYSHEV_200;

    private WindowType() {}

    public static WindowType fromString(String s) {
        switch (s.toUpperCase(Locale.ROOT).replace('-', '_')) {
            case "RECTANGULAR":
            case "RECT":
                return RECTANGULAR;
            case "HANN":
                return HANN;
            case "BLACKMAN_HARRIS_4":
            case "BH4":
                return BLACKMAN_HARRIS_4;
            case "BLACKMAN_HARRIS_7":
            case "BH7":
                return BLACKMAN_HARRIS_7;
            case "FLAT_TOP":
            case "FLATTOP":
                return FLAT_TOP;
            case "DOLPH_CHEBYSHEV_150":
            case "DC150":
            case "CHEBYSHEV_150":
                return DOLPH_CHEBYSHEV_150;
            case "DOLPH_CHEBYSHEV_200":
            case "DC200":
            case "CHEBYSHEV_200":
                return DOLPH_CHEBYSHEV_200;
            default:
                throw new IllegalArgumentException("Unknown window type: " + s);
        }
    }
}
