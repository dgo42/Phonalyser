package org.edgo.audio.measure.gui.common;

import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;

import lombok.experimental.UtilityClass;

/**
 * Small colour helpers shared across the GUI.
 */
@UtilityClass
public class ColorUtil {

    /** Perceptual-grey (ITU-R BT.601) weights and the dark/light split (50% of 255). */
    private final double GREY_R = 0.299;
    private final double GREY_G = 0.587;
    private final double GREY_B = 0.114;
    private final double DARK_GREY_MAX = 128.0;

    /** Perceptual grey (0..255) of {@code rgb}. */
    public double grey(RGB rgb) {
        return GREY_R * rgb.red + GREY_G * rgb.green + GREY_B * rgb.blue;
    }

    /** True when {@code color} is dark (perceptual grey below 50%) — put light content
     *  on it for contrast; light colours keep dark content. */
    public boolean isDark(Color color) {
        return color != null && grey(color.getRGB()) < DARK_GREY_MAX;
    }
}
