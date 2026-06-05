package org.edgo.audio.measure.enums;

import java.util.Locale;

/** Window function used by the FFT analyser.  The constant name IS the short token
 *  used at the CLI / yaml edge (parsed back via {@link #valueOf}) and as the compact
 *  tile label; the long display label is i18n via {@link #labelKey()}. */
public enum WindowType {
    RECT,
    HANN,
    BH4,
    BH7,
    FT,
    DC150,
    DC200;

    private WindowType() {}

    /** i18n key for the long display label (window combo + tile tooltip). */
    public String labelKey() {
        return "fft.window." + name().toLowerCase(Locale.ROOT);
    }
}
