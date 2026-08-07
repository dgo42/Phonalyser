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

package org.edgo.audio.measure.sound.qa40x;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.edgo.audio.measure.common.Constants;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.junit.jupiter.api.Test;

/**
 * Pins the QA40x OUTPUT scaling: a request in volts RMS becomes exactly the
 * int32 sample the analyzer is sent.
 *
 * <p><b>Why this exists.</b> {@code Qa40xThdIT} measures a fundamental
 * {@code √2} louder than the level it asks the generator for, and the obvious
 * readings of that are both wrong: it is neither a production defect nor a mock
 * error. It is device physics - the analyzer's BALANCED output drives a
 * DIFFERENTIAL input, so a 0 dBV (1 Vrms) output presents ±2 V at the input,
 * and the QA "N dBV" INPUT labels are really Vpp-differential dBFS references
 * (see {@code Qa40xProtocol.verboseInputLabel}). The OUTPUT ranges, in
 * contrast, are genuine dBV, and THAT is what this test pins: the output side
 * carries no hidden peak-versus-RMS term, so the whole {@code √2} belongs to
 * the input side of the loop.
 *
 * <p>Pinning it matters because the integration test's expected level rests on
 * it. If this convention ever moved, {@code Qa40xThdIT} would miss by 3.01 dB
 * and read like broken hardware; here it fails in one line of arithmetic with
 * no device in sight - the same service {@code FftDbFsConventionTest} performs
 * for the input side's dBFS reference.
 *
 * <p><b>It exercises the path production actually takes.</b> That distinction
 * cost real time: the obvious reference, {@code Qa40xLevels.dacInt32}, has NO
 * production callers at all - the QA40x signal never goes through it. What runs
 * is {@link SignalGenerator}, which normalises to a peak fraction of the DAC
 * full-scale PEAK voltage, and {@code Qa40xGenerator}, which multiplies that by
 * {@code MAXINT}. So the chain under test here is the real one, and the
 * full-scale voltage is fed in the way {@code Preferences.applyOutputDeviceProfile}
 * feeds it: the card stores RMS, and the generator is handed {@code RMS × √2}.
 */
class Qa40xOutputScalingTest {

    /** The QA403's output full scale on the +18 dBV row, RMS volts, as
     *  {@code Qa40xCalibration} writes it into the device card with a unit cal
     *  factor.  The scenario's analyzer sits on this row. */
    private static final double OUTPUT_FS_RMS = Qa40xLevels.outputFullScaleRmsVolts(18, 1.0);

    private static final int    SAMPLE_RATE = 48_000;
    private static final double FREQ_HZ     = 1_000.0;
    /** One full cycle at 1 kHz / 48 kHz is 48 samples; a few hundred crosses
     *  the peak many times over. */
    private static final int    RENDERED    = 512;
    /** The peak of a sampled sine lands within a fraction of a sample of the
     *  true peak, so the observed maximum is a hair under 1.0. */
    private static final double PEAK_TOLERANCE = 2e-3;
    private static final double MAXINT_TOLERANCE = 5e-3;

    @Test
    void aFullScaleRequestReachesFullScaleAndNoFurther() {
        // THE DECIDER.  Ask for exactly the full-scale RMS the card advertises.
        // Correct scaling puts the sine's PEAK at the DAC's peak full scale, so
        // the normalised peak is 1.0 and the wire sample is MAXINT.  A path that
        // treated the request as a peak would land at 1/√2 = 0.7071 (3.01 dB
        // low); one that applied the peak term twice would demand √2 = 1.4142
        // and clip.
        double peak = renderPeak(OUTPUT_FS_RMS);

        assertEquals(1.0, peak, PEAK_TOLERANCE,
                "a full-scale RMS request must reach exactly full-scale PEAK; "
                        + "0.7071 would mean the RMS was treated as a peak, 1.4142 "
                        + "that the peak term was applied twice");
        assertEquals(Qa40xLevels.MAXINT, peak * Qa40xLevels.MAXINT,
                Qa40xLevels.MAXINT * MAXINT_TOLERANCE,
                "the wire sample for a full-scale request must be MAXINT");
    }

    @Test
    void theScenariosRequestScalesLinearlyBelowFullScale() {
        // The level Qa40xThdIT actually asks for.  Nothing about the scaling is
        // special at full scale, so 0.1 Vrms must land at exactly its fraction
        // of full scale - this is the number the integration test's expected
        // dBFS is built on.
        double peak = renderPeak(0.1);

        assertEquals(0.1 / OUTPUT_FS_RMS, peak, PEAK_TOLERANCE,
                "0.1 Vrms must be 0.1/FS of full scale, with no stray √2");
    }

    @Test
    void halvingTheRequestHalvesTheOutput() {
        // Independent of any reference: proves the mapping is linear in
        // amplitude, so a discrepancy can only be a constant factor.
        double loud  = renderPeak(0.2);
        double quiet = renderPeak(0.1);

        assertEquals(2.0, loud / quiet, PEAK_TOLERANCE,
                "the volts-to-sample mapping must be linear");
    }

    /** Renders a sine at {@code amplitudeVrms} through the production generator
     *  and returns the largest normalised sample seen.  The full-scale voltage
     *  is handed in as PEAK (card RMS × √2), which is what
     *  {@code Preferences.applyOutputDeviceProfile} pushes. */
    private double renderPeak(double amplitudeVrms) {
        SignalGenerator generator = new SignalGenerator(GenSignalForm.SINE, FREQ_HZ, SAMPLE_RATE,
                amplitudeVrms, OUTPUT_FS_RMS * Constants.SQRT2);
        double peak = 0.0;
        for (int i = 0; i < RENDERED; i++) {
            peak = Math.max(peak, Math.abs(generator.nextSample()));
        }
        return peak;
    }
}
