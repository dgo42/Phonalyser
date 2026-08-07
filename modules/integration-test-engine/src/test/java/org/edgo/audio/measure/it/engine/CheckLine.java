/*
 * Phonalyser - precision audio measurement workbench.
 * Copyright (C) 2026  Dimitrij Goldstein <https://github.com/dgo42>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.edgo.audio.measure.it.engine;

/**
 * One check the automation script recorded, as read back from the result file.
 *
 * <p>The VALUES travel, not just the verdict - that is what lets a failing
 * integration test report "1000.83 Hz, expected 1000.0 ± 1.0" instead of
 * "a check failed", and what makes a tolerance worth tuning against evidence.
 */
public record CheckLine(String name, String kind, boolean pass, double actual, double expected,
        double tolerance, String message) {

    /** The failure sentence a red check reports - everything the run knew about
     *  it, so nobody has to open the result file to find out what happened. */
    public String describe() {
        return name + " [" + kind + "] actual=" + actual + " expected=" + expected
                + " tolerance=" + tolerance
                + (message == null || message.isEmpty() ? "" : " (" + message + ")");
    }
}
