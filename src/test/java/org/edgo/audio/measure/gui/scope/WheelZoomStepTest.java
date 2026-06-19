package org.edgo.audio.measure.gui.scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.edgo.audio.measure.gui.widgets.NumericStepModel;
import org.edgo.audio.measure.gui.widgets.UnitFamily;
import org.junit.jupiter.api.Test;

/**
 * Regression for the scope wheel-zoom stall at 200 µV / 200 µs. The wheel now
 * navigates the V/div, s/div field's OWN value list
 * ({@code NumericStepField.step} → {@link NumericStepModel#wheel}, which
 * identifies the current series entry by tolerance and steps to the next),
 * instead of recomputing the next value with a strict float comparison that
 * returned the parse-rounded 200 µV bucket as a no-op. These drive that list
 * navigation directly (headless — no SWT needed).
 */
class WheelZoomStepTest {

    /** One wheel-down notch from a clean 200 µV must leave the bucket (→ 100 µV). */
    @Test
    void singleDownStepLeaves200uVBucket() {
        NumericStepModel m = new NumericStepModel(
                UnitFamily.VOLTS_PER_DIV, 1e-6, 500, OscParse.voltsPerDivTargets(), 3);
        m.setValue(2e-4);
        m.wheel(-1);
        assertTrue(m.getValue() < 1.5e-4, "expected ~100 µV, got " + m.getValue());
    }

    @Test
    void voltsPerDivWheelDownReaches1uV() {
        assertEquals(1e-6, walk(UnitFamily.VOLTS_PER_DIV, 1e-6, 500,
                OscParse.voltsPerDivTargets(), 2e-4, -1), 1e-12);
    }

    @Test
    void timePerDivWheelDownReaches1us() {
        assertEquals(1e-6, walk(UnitFamily.TIME_PER_DIV, 1e-6, 1.0,
                OscParse.timePerDivTargets(), 2e-4, -1), 1e-12);
    }

    @Test
    void voltsPerDivWheelUpReaches500V() {
        assertEquals(500.0, walk(UnitFamily.VOLTS_PER_DIV, 1e-6, 500,
                OscParse.voltsPerDivTargets(), 2e-4, +1), 1e-9);
    }

    /** Steps the model's value list in {@code dir} (re-reading the value each
     *  notch, exactly as the field's wheel path does) until it stops moving,
     *  and returns where it lands. */
    private double walk(UnitFamily family, double min, double max,
                        double[] targets, double start, int dir) {
        NumericStepModel m = new NumericStepModel(family, min, max, targets, 3);
        m.setValue(start);
        for (int i = 0; i < 100; i++) {
            double before = m.getValue();
            m.wheel(dir);
            if (m.getValue() == before) break;
        }
        return m.getValue();
    }
}
