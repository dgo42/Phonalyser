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

package org.edgo.audio.measure.gui.scope;

import java.util.function.BooleanSupplier;

import org.edgo.audio.measure.common.Constants;
import org.edgo.audio.measure.enums.LpfMode;
import org.edgo.audio.measure.enums.MainsSuppression;
import org.edgo.audio.measure.gui.sound.SignalBufferReader;
import org.edgo.audio.measure.preferences.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The operator's HF cleanup is a property of the TRACE, and must not reach the
 * measurement table.
 *
 * <p>What the bench saw on a clean 1 kHz sine: Vpp wandering 12 % above its own
 * minimum, rise and fall times up to a quarter longer, duty ±3 %, while Vrms and
 * frequency did not move at all.  That split is the whole diagnosis - every
 * scattering row is derived from {@code min} / {@code max}, the two extreme
 * SINGLE samples of the window (Vpp directly, the mid threshold behind Duty and
 * the whole-period span behind Vmean, the 10 % / 90 % levels behind Tr / Tf), and
 * the two stable rows are the two that integrate instead.  The cause was ours:
 * the measurement worker ran the same 80 kHz Butterworth the view draws through,
 * reset before every pass and therefore measured ringing its way out of a step of
 * up to the full peak-to-peak.  A peak detector does not care that the ringing is
 * over in twenty samples, only that it happened.
 *
 * <p>The fix is not a shorter transient, it is no filter: a setting the operator
 * chooses to make a line readable must not move what the instrument says the
 * signal is.  So these run with the low-pass ON - the case that was broken - and
 * demand the numbers a clean sine has by definition.
 */
class ScopeMeasurementDisplayFilterTest {

    /** A real bench capture rate - and the only regime where the 80 kHz
     *  corner is below Nyquist and therefore actually filtering. */
    private static final int    RATE_HZ    = 192_000;
    private static final double TONE_HZ    = 1_000.0;
    private static final double AMPLITUDE  = 0.5;
    /** Long enough that a pass reads a full measurement window with the warm-up
     *  skip still inside the buffer. */
    private static final double BUFFER_SEC = 1.0;
    private static final long   AWAIT_MS   = 10_000;
    /** Peak-to-peak over AC RMS for a sine: {@code 2A / (A/√2)}. */
    private static final double SINE_VPP_OVER_VRMS = 2.0 * Constants.SQRT2;
    /** Fraction of the 10-90 % band's half-width, as a sine argument: the levels
     *  sit at ∓0.8 of the amplitude about the midpoint. */
    private static final double RISE_LEVEL_FRACTION = 0.8;
    /** Sampling leaves min/max a hair inside the true peak (best of ~500 cycles
     *  at 1.875° per sample), so the ratios are exact to parts in ten thousand.
     *  The defect this catches was 12 %. */
    private static final double RATIO_TOLERANCE = 5e-3;

    private ScopeMeasurementWorker worker;
    private LpfMode previousLeftLpf;
    private LpfMode previousRightLpf;
    private MainsSuppression previousLeftMains;
    private MainsSuppression previousRightMains;

    @BeforeEach
    void buildACleanSine() {
        Preferences prefs = Preferences.instance();
        prefs.setTransientMode(true);
        previousLeftLpf    = prefs.getOscLeftLpf();
        previousRightLpf   = prefs.getOscRightLpf();
        previousLeftMains  = prefs.getOscLeftMainsSuppression();
        previousRightMains = prefs.getOscRightMainsSuppression();
        // The comb is a separate question with rules of its own; this is about
        // the display filter, so take the comb out of the picture.
        prefs.setOscLeftMainsSuppression(MainsSuppression.NONE);
        prefs.setOscRightMainsSuppression(MainsSuppression.NONE);

        // A COSINE, and that is the point of the test rather than a detail.  The
        // measured window is the last 96 000 samples, which at these numbers is
        // exactly 500 periods, so a sine would put the window's first sample on a
        // zero crossing - a filter reset there steps from nothing to nothing and
        // rings not at all.  That is the bench's clean pass, the one where Vpp
        // read its own minimum.  Starting at the crest instead is the worst case
        // and the deterministic one: the reset is a step of the full amplitude.
        int n = (int) (RATE_HZ * BUFFER_SEC);
        float[] left  = new float[n];
        float[] right = new float[n];
        for (int i = 0; i < n; i++) {
            float v = (float) (AMPLITUDE * Math.cos(2.0 * Math.PI * TONE_HZ * i / RATE_HZ));
            left[i]  = v;
            right[i] = v;
        }
        SignalBufferReader.Builder builder = SignalBufferReader.builder(RATE_HZ, BUFFER_SEC);
        builder.append(left, right, n);

        worker = new ScopeMeasurementWorker();
        worker.setBuffer(builder.build());
    }

    @AfterEach
    void restore() {
        if (worker != null) worker.stop();
        Preferences prefs = Preferences.instance();
        prefs.setOscLeftLpf(previousLeftLpf);
        prefs.setOscRightLpf(previousRightLpf);
        prefs.setOscLeftMainsSuppression(previousLeftMains);
        prefs.setOscRightMainsSuppression(previousRightMains);
    }

    /**
     * With the display filter ON, a sine must still measure as a sine.
     *
     * <p>Both assertions are definitions, not reference values: a sine's
     * peak-to-peak is {@code 2√2} times its RMS whatever its amplitude, and its
     * 10-90 % rise is {@code 2·asin(0.8) / 2πf} whatever its amplitude.  Nothing
     * here is read off a previous run, so the test says what a measurement IS
     * rather than what this code happens to produce.
     */
    @Test
    void aSineStillMeasuresAsASineWithTheDisplayFilterOn() {
        SignalMeasurements m = measureWith(LpfMode.HZ_80);

        assertEquals(SINE_VPP_OVER_VRMS, m.getVpp() / m.getVrms(),
                SINE_VPP_OVER_VRMS * RATIO_TOLERANCE,
                "Vpp is two single samples and the filter's reset transient was one of "
                        + "them - the peak-to-peak no longer matched the RMS of the same sine");

        double analyticRise = 2.0 * Math.asin(RISE_LEVEL_FRACTION) / (2.0 * Math.PI * TONE_HZ);
        assertEquals(analyticRise, m.getRiseTime(), analyticRise * RATIO_TOLERANCE,
                "the 10 % / 90 % levels are taken off min and max, so an inflated "
                        + "peak-to-peak widens the band and the rise time reads long");
    }

    /**
     * And the stronger statement of the same rule: turning a DISPLAY setting on
     * must not move a single measured row.
     */
    @Test
    void theDisplayFilterMovesNoMeasuredRow() {
        SignalMeasurements off = measureWith(LpfMode.NONE);
        SignalMeasurements on  = measureWith(LpfMode.HZ_80);

        assertEquals(off.getVpp(),       on.getVpp(),       off.getVpp() * RATIO_TOLERANCE,       "Vpp");
        assertEquals(off.getVrms(),      on.getVrms(),      off.getVrms() * RATIO_TOLERANCE,      "Vrms");
        assertEquals(off.getRiseTime(),  on.getRiseTime(),  off.getRiseTime() * RATIO_TOLERANCE,  "Tr");
        assertEquals(off.getFallTime(),  on.getFallTime(),  off.getFallTime() * RATIO_TOLERANCE,  "Tf");
        assertEquals(off.getDutyCycle(), on.getDutyCycle(), off.getDutyCycle() * RATIO_TOLERANCE, "duty");
        assertEquals(off.getFrequency(), on.getFrequency(), off.getFrequency() * RATIO_TOLERANCE, "f");
    }

    /** Runs one worker session with {@code mode} on both channels and returns the
     *  right channel's first published snapshot (the bench's measurement channel). */
    private SignalMeasurements measureWith(LpfMode mode) {
        Preferences prefs = Preferences.instance();
        prefs.setOscLeftLpf(mode);
        prefs.setOscRightLpf(mode);
        worker.start();
        awaitTrue(() -> worker.getLastMeasResult(false) != null,
                "the measurement worker published nothing for " + mode);
        SignalMeasurements result = worker.getLastMeasResult(false);
        worker.stop();
        assertNotNull(result);
        assertTrue(result.getVpp() > 0, "a sine has a peak-to-peak");
        return result;
    }

    private void awaitTrue(BooleanSupplier condition, String what) {
        long deadline = System.currentTimeMillis() + AWAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.onSpinWait();
        }
        throw new AssertionError(what);
    }
}
