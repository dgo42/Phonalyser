package org.edgo.audio.measure.gui.interfaces;

/** Parses a free-form text into a {@code double}, returning {@code null}
 *  for unparseable input.  Shared by every step-style numeric input
 *  widget so callers can pass the same parser to several fields without
 *  importing widget-specific types. */
@FunctionalInterface
public interface Parser {
    Double parse(String text);
}
