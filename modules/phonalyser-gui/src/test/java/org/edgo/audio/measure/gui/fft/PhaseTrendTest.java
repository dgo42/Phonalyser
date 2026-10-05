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

package org.edgo.audio.measure.gui.fft;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The trend's prediction: the polynomial through the held readings, read at
 * a later time.  Readings arrive at irregular times, as frames do under a
 * random overlap, so every case spaces them unevenly.
 */
class PhaseTrendTest {

    private static final int    POINTS = 10;
    /** What the fit may miss by on readings it can represent exactly. */
    private static final double TOL    = 1.0e-9;
    /** Ten irregular instants, seconds. */
    private static final double[] TIMES = { 0.0, 0.41, 0.85, 1.3, 1.62, 2.1, 2.44, 2.9, 3.35, 3.7 };
    private static final double   AHEAD = 4.15;

    private double quadratic(double t) {
        return 0.3 + 0.7 * t - 0.2 * t * t;
    }

    private double line(double t) {
        return 0.3 + 0.7 * t;
    }

    /** A line's readings at ten irregular instants are continued exactly
     *  ahead of them - the degree the fit carries. */
    @Test
    void aLineIsContinuedExactly() {
        PhaseTrend trend = new PhaseTrend(POINTS);
        for (double t : TIMES) trend.add(t, line(t));
        assertEquals(line(AHEAD), trend.predict(AHEAD), TOL,
                "the line through the readings, read ahead");
    }

    /** A bend is NOT followed: the fit stops at a line, so a drift's
     *  curvature shows up as a miss inside the window rather than being
     *  spent outside it. */
    @Test
    void aBendIsNotFollowed() {
        PhaseTrend trend = new PhaseTrend(POINTS);
        for (double t : TIMES) trend.add(t, quadratic(t));
        assertTrue(Math.abs(trend.predict(AHEAD) - quadratic(AHEAD)) > TOL, "the bend is not represented");
    }

    /** A cubic's readings are NOT continued exactly: the fit stops at degree
     *  two, so the cubic term shows up as a miss rather than running away
     *  with the time cubed outside the window. */
    @Test
    void aCubicIsNotFollowed() {
        PhaseTrend trend = new PhaseTrend(POINTS);
        for (double t : TIMES) trend.add(t, quadratic(t) + 0.05 * t * t * t);
        double predicted = trend.predict(AHEAD);
        double exact     = quadratic(AHEAD) + 0.05 * AHEAD * AHEAD * AHEAD;
        assertTrue(Math.abs(predicted - exact) > TOL, "the cubic term is not represented");
    }

    /** One reading holds as a constant; two or more make a line, continued
     *  exactly for what a line can represent. */
    @Test
    void fewerReadingsFitALowerDegree() {
        PhaseTrend one = new PhaseTrend(POINTS);
        one.add(1.0, 0.7);
        assertEquals(0.7, one.predict(5.0), TOL, "one reading holds");

        PhaseTrend two = new PhaseTrend(POINTS);
        two.add(1.0, 0.5);
        two.add(2.5, 2.0);
        assertEquals(3.5, two.predict(4.0), TOL, "two readings make a line");

        PhaseTrend three = new PhaseTrend(POINTS);
        for (double t : new double[] { 0.0, 0.7, 1.9 }) three.add(t, 1.0 + 2.0 * t);
        double t = 3.1;
        assertEquals(1.0 + 2.0 * t, three.predict(t), TOL, "three readings make a line as well");
    }

    /** Nothing held predicts nothing, and a reset forgets what was held. */
    @Test
    void nothingHeldPredictsNothing() {
        PhaseTrend trend = new PhaseTrend(POINTS);
        assertTrue(Double.isNaN(trend.predict(1.0)), "empty");
        trend.add(0.0, 1.0);
        assertEquals(1, trend.getCount());
        trend.reset();
        assertEquals(0, trend.getCount());
        assertTrue(Double.isNaN(trend.predict(1.0)), "empty again after the reset");
    }

    /** Beyond the capacity only the newest readings count: a line the
     *  window once held is forgotten once another has filled it. */
    @Test
    void onlyTheNewestReadingsCount() {
        PhaseTrend trend = new PhaseTrend(POINTS);
        for (double t : TIMES) trend.add(t, 5.0 - 3.0 * t);
        for (double t : TIMES) trend.add(10.0 + t, 1.0 + 0.5 * (10.0 + t));
        assertEquals(1.0 + 0.5 * (10.0 + AHEAD), trend.predict(10.0 + AHEAD), TOL,
                "the second line alone, the first having left the window");
    }

    /** Within the horizon the bounded prediction is the plain one; past it
     *  there is none, and none at all while nothing is held. */
    @Test
    void thePredictionStopsAtTheHorizon() {
        PhaseTrend trend = new PhaseTrend(POINTS);
        assertTrue(Double.isNaN(trend.predict(1.0, 5.0)), "empty");
        for (double t : TIMES) trend.add(t, quadratic(t));
        double newest = TIMES[TIMES.length - 1];
        assertEquals(trend.predict(newest + 4.9), trend.predict(newest + 4.9, 5.0), TOL,
                "inside the horizon");
        assertEquals(trend.predict(newest + 5.0), trend.predict(newest + 5.0, 5.0), TOL,
                "at the horizon");
        assertTrue(Double.isNaN(trend.predict(newest + 5.1, 5.0)), "past it");
    }
}
