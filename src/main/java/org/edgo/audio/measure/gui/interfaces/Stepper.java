package org.edgo.audio.measure.gui.interfaces;

/** Computes the next value when the user presses an up / down arrow or
 *  scrolls the mouse wheel on a step-style numeric input widget.
 *  {@code direction} is {@code +1} for a step up and {@code -1} for
 *  a step down. */
@FunctionalInterface
public interface Stepper {
    double step(double current, int direction);
}
