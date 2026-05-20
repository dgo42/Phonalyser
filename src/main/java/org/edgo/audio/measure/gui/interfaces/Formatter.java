package org.edgo.audio.measure.gui.interfaces;

/** Renders a {@code double} into its display string for a step-style
 *  numeric input widget (V/div, frequency, amplitude, etc.). */
@FunctionalInterface
public interface Formatter {
    String format(double value);
}
