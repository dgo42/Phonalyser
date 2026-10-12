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

package org.edgo.audio.measure.generator;

import java.util.Arrays;
import java.util.function.Consumer;

import org.edgo.audio.measure.common.Constants;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.generator.SignalGenerator.CompensationTable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour tests for the DDS {@link SignalGenerator}.  Verifies that
 * each waveform's per-sample output matches its analytic form, that
 * RMS amplitudes round-trip via the {@code amplitudeVRms} constructor
 * argument, and that the phase accumulator wraps cleanly at full scale.
 *
 * <p>Audit ranked this as the #1 highest-value test: a regression here
 * silently corrupts every WAV file the user exports, every CLI tone,
 * and every iterative-distortion-compensation pass.
 *
 * <p>The DAC full-scale is a plain constructor argument since the
 * Preferences-injection rework, so the tests run against a literal value
 * with no singleton (and no disk) involved at all.
 */
class SignalGeneratorTest {

    /** DAC full-scale RMS voltage used by every test - a literal, not Preferences. */
    private static final double DAC_FS_VRMS = 2.79351;
    /** Dual-tone splits the correction reference is checked at.  A symmetric
     *  split alone cannot tell "ratio to the F1 tone" from "half of full
     *  scale", so the asymmetric ones carry the proof. */
    private static final double[] DUAL_SPLITS_PCT = { 50.0, 40.0, 75.0 };
    /** Commanded correction amplitude, as a ratio to the tone it corrects -
     *  large enough to measure to a part in 1e6, far below any clipping. */
    private static final double CORRECTION_RATIO = 0.01;
    /** Block length of the bit-exact render pin. */
    private static final int    RENDER_PIN_SAMPLES = 4_800;
    /** {@code Arrays.hashCode} of that block - see
     *  {@link #compensation_renderIsBitIdentical()}. */
    private static final int    SINE_COMP_RENDER_HASH = 356_046_049;

    /** The frame every render here pulls into; both lanes are commanded alike,
     *  so the left lane is what the assertions read. */
    private final double[] frame = new double[2];

    /** The next left-lane sample of {@code gen}, advancing it one step. */
    private double next(SignalGenerator gen) {
        gen.nextFrame(frame);
        return frame[0];
    }

    @Test
    void sine_matchesAnalyticForm() {
        // 1 kHz at 48 kHz, 1 V RMS -> 480 samples = 10 full cycles.
        // The 32-bit phase accumulator + 4096-entry table + 2nd-order
        // Taylor correction should match Math.sin to better than 1e-5.
        int    sampleRate = 48_000;
        double freqHz     = 1_000.0;
        double vrms       = 1.0;
        int    samples    = 480;

        SignalGenerator gen = new SignalGenerator(
                GenSignalForm.SINE, freqHz, sampleRate, vrms, vrms, DAC_FS_VRMS, DAC_FS_VRMS);

        double expectedPeak = vrms * Constants.SQRT2 / DAC_FS_VRMS;
        for (int n = 0; n < samples; n++) {
            double actual   = next(gen);
            double expected = expectedPeak
                    * Math.sin(2.0 * Math.PI * freqHz * n / sampleRate);
            assertEquals(expected, actual, 1e-5,
                    "sine sample " + n + " deviates from analytic form");
        }
    }

    @Test
    void sine_rmsMatchesRequestedAmplitude() {
        // Run for many full periods, compute RMS, expect it to land
        // within 0.1 % of the requested V RMS (after dividing out the
        // DAC full-scale since nextSample returns normalised samples).
        int    sampleRate = 48_000;
        double freqHz     = 1_000.0;
        double vrms       = 0.5;
        int    samples    = sampleRate;  // 1 second -> 1000 full periods

        SignalGenerator gen = new SignalGenerator(
                GenSignalForm.SINE, freqHz, sampleRate, vrms, vrms, DAC_FS_VRMS, DAC_FS_VRMS);

        double sumSq = 0.0;
        for (int n = 0; n < samples; n++) {
            double s = next(gen);
            sumSq += s * s;
        }
        double rmsNormalised = Math.sqrt(sumSq / samples);
        double rmsVolts      = rmsNormalised * DAC_FS_VRMS;
        assertEquals(vrms, rmsVolts, vrms * 1e-3,
                "measured RMS should match the requested amplitude (1 ‰ tolerance)");
    }

    @Test
    void triangle_rmsMatchesAnalyticPeakOverSqrt3() {
        // Symmetric triangle has theoretical RMS = peak / √3.
        // SignalGenerator scales the raw triangle so requesting 1.0 V RMS
        // produces samples whose long-run RMS = 1.0 / dacFsVoltageRms.
        int    sampleRate = 48_000;
        double freqHz     = 1_000.0;
        double vrms       = 1.0;
        int    samples    = sampleRate;

        SignalGenerator gen = new SignalGenerator(
                GenSignalForm.TRIANGLE, freqHz, sampleRate, vrms, vrms, DAC_FS_VRMS, DAC_FS_VRMS);

        double sumSq = 0.0;
        for (int n = 0; n < samples; n++) {
            double s = next(gen);
            sumSq += s * s;
        }
        double rmsNormalised = Math.sqrt(sumSq / samples);
        double rmsVolts      = rmsNormalised * DAC_FS_VRMS;
        assertEquals(vrms, rmsVolts, vrms * 5e-3,
                "triangle RMS should match the requested amplitude (5 ‰ tolerance)");
    }

    @Test
    void rectangle_rmsEqualsPeak() {
        // 50/50 duty rectangle: RMS == peak amplitude.  Over many
        // periods the average sample magnitude should equal the
        // generator's linear-peak factor, and the RMS reading should
        // match the requested vrms exactly (no √2 factor).
        int    sampleRate = 48_000;
        double freqHz     = 1_000.0;
        double vrms       = 0.7;
        int    samples    = sampleRate;

        SignalGenerator gen = new SignalGenerator(
                GenSignalForm.RECTANGLE, freqHz, sampleRate, vrms, vrms, DAC_FS_VRMS, DAC_FS_VRMS);

        double sumSq = 0.0;
        for (int n = 0; n < samples; n++) {
            double s = next(gen);
            sumSq += s * s;
        }
        double rmsNormalised = Math.sqrt(sumSq / samples);
        double rmsVolts      = rmsNormalised * DAC_FS_VRMS;
        assertEquals(vrms, rmsVolts, vrms * 1e-3,
                "square-wave RMS should equal peak");
    }

    @Test
    void sine_outputClipsWithinNormalisedRange() {
        // nextFrame() returns samples in [-1, +1] regardless of input
        // amplitude - clipping the caller's responsibility.  But for a
        // reasonable amplitude (< full-scale) every sample should stay
        // within the normalised range with room to spare.
        SignalGenerator gen = new SignalGenerator(
                GenSignalForm.SINE, 1_000.0, 48_000, 1.0, 1.0, DAC_FS_VRMS, DAC_FS_VRMS);
        double peak = 0.0;
        for (int n = 0; n < 48_000; n++) {
            double s = next(gen);
            assertTrue(s >= -1.0 && s <= 1.0,
                    "sample " + n + " out of normalised range: " + s);
            peak = Math.max(peak, Math.abs(s));
        }
        // Peak should approach (within 1 %) the analytic value
        // vrms * √2 / dacFsVoltageRms = 1.0 * √2 / 2.79351 ≈ 0.5063.
        double expectedPeak = Constants.SQRT2 / DAC_FS_VRMS;
        assertEquals(expectedPeak, peak, expectedPeak * 1e-2);
    }

    // -------------------------------------------------------------------------
    // Predistortion: what a commanded amplitude ratio is a ratio TO
    // -------------------------------------------------------------------------

    /**
     * A commanded correction is a ratio to the TONE it corrects - the
     * fundamental for a single tone, the F1 tone for two - and the DDS must
     * deliver it at exactly that ratio.  Everything upstream states this
     * convention and computes against it: the accumulators divide the measured
     * product by the DAC fundamental's Vrms on the way out, a saved
     * {@code .dpd} carries the same ratio, and the plant probe measures the
     * chain by dividing the arrival by the arrival the ratio should have
     * produced.  If the DDS delivers a different fraction, the plant absorbs
     * the difference as if it were the chain's, and a run without a probe -
     * the CLI - closes its loop at that gain.
     *
     * <p>The dual-tone case is the sharp one: the two tones carry the per-tone
     * weights, so a correction subtracted at full scale is delivered
     * {@code 1/w1} times too large - and the split is a user setting, so the
     * error is not even a constant.  Measured by quadrature correlation on the
     * rendered block, which is exact here: the block holds a whole number of
     * cycles of both tones and of the product.
     */
    @Test
    void dualToneCompensation_partialArrivesAtTheCommandedRatioToTone1() {
        int    sampleRate = 48_000;
        double f1Hz       = 1_000.0;
        double f2Hz       = 1_100.0;
        double vrms       = 1.0;

        for (double splitPct : DUAL_SPLITS_PCT) {
            SignalGenerator gen = new SignalGenerator(
                    GenSignalForm.DUAL_TONE, f1Hz, sampleRate, vrms, vrms, DAC_FS_VRMS, DAC_FS_VRMS);
            gen.setDualToneFrequency2(f2Hz);
            gen.setDualToneAmplitudes(splitPct, 100.0 - splitPct);
            gen.setAmplitudesVrms(vrms, vrms);
            // One product, at f1 + f2 so it cannot be confused with either tone
            // or with a harmonic of them.
            gen.applyDualToneCompensation(new double[]{ CORRECTION_RATIO },
                    new int[]{ 1 }, new int[]{ 1 }, new double[]{ 0.0 });

            double[] block  = render(gen, sampleRate);
            double   tone1  = componentPeak(block, sampleRate, f1Hz);
            double   partial = componentPeak(block, sampleRate, f1Hz + f2Hz);

            assertEquals(CORRECTION_RATIO, partial / tone1, CORRECTION_RATIO * 1e-3,
                    "at a " + splitPct + " % split the correction must arrive at the "
                            + "commanded ratio to the F1 TONE, not to full scale");
        }
    }

    /** The single-tone convention the dual-tone one has to match: the
     *  fundamental carries the whole raw amplitude, so a correction commanded
     *  at a ratio arrives at exactly that ratio to it. */
    @Test
    void compensation_partialArrivesAtTheCommandedRatioToTheFundamental() {
        int    sampleRate = 48_000;
        double freqHz     = 1_000.0;
        double vrms       = 1.0;
        int    harmonic   = 2;

        SignalGenerator gen = new SignalGenerator(
                GenSignalForm.SINE, freqHz, sampleRate, vrms, vrms, DAC_FS_VRMS, DAC_FS_VRMS);
        gen.applyCompensation(new double[]{ CORRECTION_RATIO },
                new int[]{ harmonic }, new double[]{ 0.0 });

        double[] block   = render(gen, sampleRate);
        double   fund    = componentPeak(block, sampleRate, freqHz);
        double   partial = componentPeak(block, sampleRate, harmonic * freqHz);

        assertEquals(CORRECTION_RATIO, partial / fund, CORRECTION_RATIO * 1e-3,
                "the single-tone correction must arrive at the commanded ratio "
                        + "to the fundamental");
    }

    /** Bit-exact pin on the single-tone compensated kernel: its samples are
     *  pure table + Taylor double arithmetic, so the rendered block is
     *  reproducible to the last bit and any change to that kernel - including
     *  one made while fixing the DUAL-tone reference - shows up here.  Update
     *  the constant only for a deliberate, reviewed change to the single-tone
     *  compensation. */
    @Test
    void compensation_renderIsBitIdentical() {
        int    sampleRate = 48_000;
        double freqHz     = 1_000.0;
        double vrms       = 1.0;
        int    harmonic   = 3;

        SignalGenerator gen = new SignalGenerator(
                GenSignalForm.SINE, freqHz, sampleRate, vrms, vrms, DAC_FS_VRMS, DAC_FS_VRMS);
        gen.applyCompensation(new double[]{ CORRECTION_RATIO },
                new int[]{ harmonic }, new double[]{ 0.25 });

        assertEquals(SINE_COMP_RENDER_HASH, Arrays.hashCode(render(gen, RENDER_PIN_SAMPLES)),
                "the single-tone compensated render changed bit-for-bit");
    }

    // -------------------------------------------------------------------------
    // Predistortion: engaging and disengaging it by a live form switch
    // -------------------------------------------------------------------------

    /**
     * A form flip between a plain waveform and its compensated sibling must
     * take effect on the very next sample, in both directions, with the
     * correction tables surviving the round trip - the operator switches the
     * combo on a RUNNING generator and expects to hear the difference without
     * a stop and start.
     *
     * <p>The tone's own level must not move across the flip either: the pair
     * shares an RMS by construction, so a level jump would mean the switch is
     * re-scaling something it should not and the "no glitch" claim would be
     * false.
     */
    @Test
    void formSwitch_engagesAndDisengagesCompensationLive() {
        int    sampleRate = 48_000;
        double freqHz     = 1_000.0;
        double vrms       = 1.0;
        int    harmonic   = 2;

        SignalGenerator gen = new SignalGenerator(
                GenSignalForm.SINE, freqHz, sampleRate, vrms, vrms, DAC_FS_VRMS, DAC_FS_VRMS);
        // The apply publishes the tables AND flips the form - the wizard's path.
        gen.applyCompensation(new double[]{ CORRECTION_RATIO },
                new int[]{ harmonic }, new double[]{ 0.0 });
        double[] engaged = render(gen, sampleRate);

        gen.setForm(GenSignalForm.SINE);
        double[] plain = render(gen, sampleRate);

        gen.setForm(GenSignalForm.SINE_COMP);
        double[] reEngaged = render(gen, sampleRate);

        double fund = componentPeak(engaged, sampleRate, freqHz);
        assertEquals(CORRECTION_RATIO,
                componentPeak(engaged, sampleRate, harmonic * freqHz) / fund,
                CORRECTION_RATIO * 1e-3, "the compensated form renders the correction");
        assertEquals(0.0, componentPeak(plain, sampleRate, harmonic * freqHz) / fund, 1e-9,
                "the flip to the plain form must stop the correction at once");
        assertEquals(CORRECTION_RATIO,
                componentPeak(reEngaged, sampleRate, harmonic * freqHz) / fund,
                CORRECTION_RATIO * 1e-3,
                "the flip back must re-engage the held tables with no restart");
        assertEquals(fund, componentPeak(plain, sampleRate, freqHz), fund * 1e-9,
                "switching within the pair must not move the tone's own level");
    }

    /** The two-tone half of the same contract: both forms run the same pair of
     *  accumulators, so the switch is a hot-swap there too - no rebuild, and
     *  the second tone neither stops nor restarts. */
    @Test
    void dualToneFormSwitch_engagesAndDisengagesCompensationLive() {
        int    sampleRate = 48_000;
        double f1Hz       = 1_000.0;
        double f2Hz       = 1_100.0;
        double vrms       = 1.0;
        double splitPct   = DUAL_SPLITS_PCT[0];

        SignalGenerator gen = new SignalGenerator(
                GenSignalForm.DUAL_TONE, f1Hz, sampleRate, vrms, vrms, DAC_FS_VRMS, DAC_FS_VRMS);
        gen.setDualToneFrequency2(f2Hz);
        gen.setDualToneAmplitudes(splitPct, 100.0 - splitPct);
        gen.setAmplitudesVrms(vrms, vrms);
        gen.applyDualToneCompensation(new double[]{ CORRECTION_RATIO },
                new int[]{ 1 }, new int[]{ 1 }, new double[]{ 0.0 });
        double[] engaged = render(gen, sampleRate);

        gen.setForm(GenSignalForm.DUAL_TONE);
        double[] plain = render(gen, sampleRate);

        gen.setForm(GenSignalForm.DUAL_TONE_COMP);
        double[] reEngaged = render(gen, sampleRate);

        double tone1   = componentPeak(engaged, sampleRate, f1Hz);
        double product = f1Hz + f2Hz;
        assertEquals(CORRECTION_RATIO, componentPeak(engaged, sampleRate, product) / tone1,
                CORRECTION_RATIO * 1e-3, "the compensated two-tone form renders the correction");
        assertEquals(0.0, componentPeak(plain, sampleRate, product) / tone1, 1e-9,
                "the flip to the plain two-tone form must stop the correction at once");
        assertEquals(CORRECTION_RATIO, componentPeak(reEngaged, sampleRate, product) / tone1,
                CORRECTION_RATIO * 1e-3,
                "the flip back must re-engage the held products with no restart");
        assertEquals(tone1, componentPeak(plain, sampleRate, f1Hz), tone1 * 1e-9,
                "switching within the pair must not move tone 1's level");
        assertEquals(componentPeak(engaged, sampleRate, f2Hz),
                componentPeak(plain, sampleRate, f2Hz),
                componentPeak(engaged, sampleRate, f2Hz) * 1e-9,
                "the second tone must survive the switch untouched - no rebuild");
    }

    // -------------------------------------------------------------------------
    // Predistortion: a per-line apply fades only the named lines
    // -------------------------------------------------------------------------

    /** One setting a fade test runs at: the rate, the fundamental (F1 for a
     *  dual tone) and the fade the contract asks for there - ten periods of
     *  the fundamental, rounded up to whole samples. */
    private record Rig(int rate, double f1Hz, int fadeSamples) {
    }

    /** 100 Hz at 48 kHz: 480 samples a period, a fade of 4800. */
    private static final Rig    RIG          = new Rig(48_000, 100.0, 4_800);
    /** 997 Hz at 44.1 kHz: ten periods are 442.3 samples, a fade of 443 - so
     *  neither a fixed length nor a rounding down passes. */
    private static final Rig    OTHER_RIG    = new Rig(44_100, 997.0, 443);
    /** {@link #RIG}'s tones with a product BELOW F1 changing: ten periods of
     *  its own 90 Hz are 5333.3 samples, a fade of 5334 - longer than the 4800
     *  of ten F1 periods, so that is the one it takes. */
    private static final Rig    SLOW_PRODUCT_RIG = new Rig(48_000, 100.0, 5_334);
    /** The index of that product, (2, -1) at 2 F1 - F2. */
    private static final int    SLOW_PRODUCT     = 0;
    /** The second tone of the dual-tone fade test. */
    private static final double FADE_F2_HZ   = 110.0;
    /** Samples rendered before the apply - not a whole period, so the apply
     *  lands at an arbitrary phase of every line. */
    private static final int    FADE_LEAD    = 1_000;
    /** Samples rendered past the fade, compared bit for bit with the target. */
    private static final int    FADE_TAIL    = 960;
    private static final double FADE_VRMS    = 1.0;
    /** The table the fade tests start from: three lines at their own ratios
     *  and phases; the middle one is the line that is changed. */
    private static final double[] OLD_AMPS = { 0.01, 0.01, 0.005 };
    private static final double[] OLD_PHIS = { 0.3, 3.0, -1.0 };
    private static final int      CHANGED  = 1;
    /** The changed line's new target: twice the ratio, at a phase across the
     *  +-pi seam from the old one - the shorter way round is +0.28 rad, the
     *  long way -6 rad. */
    private static final double NEW_AMP = 0.02;
    private static final double NEW_PHI = -3.0;
    /** The single-tone table's harmonics. */
    private static final int[] FADE_HARMONICS = { 2, 3, 4 };
    /** The dual-tone table's products (a, b) at a F1 + b F2: 90, 210 and 120 Hz. */
    private static final int[] PRODUCT_A = { 2, 1, -1 };
    private static final int[] PRODUCT_B = { -1, 1, 2 };
    /** The harmonic the absent-line test adds, with its ratio and phase. */
    private static final int    ADDED_HARMONIC = 5;
    private static final double ADDED_AMP      = 0.01;
    private static final double ADDED_PHI      = 1.0;
    /** The other line a second apply names half-way through the first fade. */
    private static final int    OTHER_LINE     = 0;
    private static final double OTHER_AMP      = 0.015;
    private static final double OTHER_PHI      = -0.5;
    /** The changed line's second target, named half-way through its fade. */
    private static final double RETARGET_AMP   = 0.005;
    private static final double RETARGET_PHI   = 2.0;
    /** A second whole table, every entry other than the first table's. */
    private static final double[] WHOLE_AMPS   = { 0.02, 0.01, 0.003 };
    /** How far a rendered line may sit from its analytic fade, as a fraction
     *  of its scale - rounding, not a budget. */
    private static final double FADE_TOL = 1e-12;

    /**
     * A per-line apply changes the named entry and nothing else: every other
     * entry of the published table is bit for bit what it was, at the same
     * index, and naming a line the table holds adds no entry.
     */
    @Test
    void compensationLines_leaveTheUnnamedEntriesBitIdentical() {
        SignalGenerator gen = harmonicGenerator(RIG, FADE_HARMONICS, OLD_AMPS, OLD_PHIS);
        render(gen, FADE_LEAD);
        CompensationTable before = gen.getComp();
        changeTheMiddleHarmonic(gen);

        assertOnlyTheChangedEntryMoved(before, gen.getComp());
    }

    /**
     * The named harmonic fades over ten fundamental periods - magnitude and
     * phase each linearly, the phase the shorter way across the +-pi seam -
     * with no step, and lands exactly on its target: from the end of the fade
     * on, the output is bit for bit that of a generator holding the new table
     * whole.
     */
    @Test
    void compensationLines_fadeTheNamedHarmonicLinearlyToItsTarget() {
        assertTheMiddleHarmonicFades(RIG);
    }

    /** The same at another fundamental and rate: the fade is ten periods of
     *  THIS fundamental, rounded up to whole samples, and nothing else. */
    @Test
    void compensationLines_fadeOverTenPeriodsOfTheFundamentalAtAnyRate() {
        assertTheMiddleHarmonicFades(OTHER_RIG);
    }

    /** The dual-tone twin: one product, named by its coefficients on F1 and
     *  F2, fades the same way on its F1-referenced scale and lands on its
     *  target, and the other products' entries stay bit for bit. */
    @Test
    void dualToneCompensationLines_fadeTheNamedProductAndLeaveTheOthers() {
        SignalGenerator faded   = productGenerator(OLD_AMPS, OLD_PHIS);
        SignalGenerator without = productGenerator(withChanged(OLD_AMPS, 0.0), OLD_PHIS);
        SignalGenerator target  = productGenerator(withChanged(OLD_AMPS, NEW_AMP),
                withChanged(OLD_PHIS, NEW_PHI));
        renderAlike(FADE_LEAD, faded, without, target);
        CompensationTable before = faded.getDtComp();
        double productHz = PRODUCT_A[CHANGED] * RIG.f1Hz() + PRODUCT_B[CHANGED] * FADE_F2_HZ;

        assertLineFades(RIG, faded, without, target,
                g -> g.applyDualToneCompensationLines(new double[]{ NEW_AMP },
                        new int[]{ PRODUCT_A[CHANGED] }, new int[]{ PRODUCT_B[CHANGED] },
                        new double[]{ NEW_PHI }),
                FADE_LEAD, productHz, productScale(),
                OLD_AMPS[CHANGED], OLD_PHIS[CHANGED], NEW_AMP, NEW_PHI);
        assertOnlyTheChangedEntryMoved(before, faded.getDtComp());
    }

    /** A product below F1 fades over ten periods of its OWN frequency: those
     *  take longer than ten periods of F1, and the longer of the two is the
     *  fade. */
    @Test
    void dualToneCompensationLines_aProductBelowF1FadesOverTenOfItsOwnPeriods() {
        SignalGenerator faded   = productGenerator(OLD_AMPS, OLD_PHIS);
        SignalGenerator without = productGenerator(withEntry(OLD_AMPS, SLOW_PRODUCT, 0.0),
                OLD_PHIS);
        SignalGenerator target  = productGenerator(withEntry(OLD_AMPS, SLOW_PRODUCT, NEW_AMP),
                withEntry(OLD_PHIS, SLOW_PRODUCT, NEW_PHI));
        renderAlike(FADE_LEAD, faded, without, target);
        double productHz = PRODUCT_A[SLOW_PRODUCT] * RIG.f1Hz()
                + PRODUCT_B[SLOW_PRODUCT] * FADE_F2_HZ;

        assertLineFades(SLOW_PRODUCT_RIG, faded, without, target,
                g -> g.applyDualToneCompensationLines(new double[]{ NEW_AMP },
                        new int[]{ PRODUCT_A[SLOW_PRODUCT] }, new int[]{ PRODUCT_B[SLOW_PRODUCT] },
                        new double[]{ NEW_PHI }),
                FADE_LEAD, productHz, productScale(),
                OLD_AMPS[SLOW_PRODUCT], OLD_PHIS[SLOW_PRODUCT], NEW_AMP, NEW_PHI);
    }

    /**
     * A line the table lacks fades in from zero at its new phase - here on a
     * generator emitting the plain sine, which the apply switches to the
     * compensated form - and, named again with amplitude zero, fades out at its
     * own phase, after which the output is the plain sine bit for bit.
     */
    @Test
    void compensationLines_fadeAnAbsentHarmonicInFromZeroAndOutAgain() {
        SignalGenerator faded   = plainSine(RIG);
        SignalGenerator without = plainSine(RIG);
        SignalGenerator fadedIn = harmonicGenerator(RIG, new int[]{ ADDED_HARMONIC },
                new double[]{ ADDED_AMP }, new double[]{ ADDED_PHI });
        renderAlike(FADE_LEAD, faded, without, fadedIn);
        double lineHz = ADDED_HARMONIC * RIG.f1Hz();

        assertLineFades(RIG, faded, without, fadedIn,
                g -> g.applyCompensationLines(new double[]{ ADDED_AMP },
                        new int[]{ ADDED_HARMONIC }, new double[]{ ADDED_PHI }),
                FADE_LEAD, lineHz, harmonicScale(), 0.0, ADDED_PHI, ADDED_AMP, ADDED_PHI);

        // The fade-out names a phase of 0: an entry named with amplitude zero
        // keeps the phase it has, so it must fade out at ADDED_PHI.
        int afterFadeIn = FADE_LEAD + 1 + RIG.fadeSamples() + FADE_TAIL;
        SignalGenerator fadedOut = plainSine(RIG);
        render(fadedOut, afterFadeIn);
        assertLineFades(RIG, faded, without, fadedOut,
                g -> g.applyCompensationLines(new double[]{ 0.0 },
                        new int[]{ ADDED_HARMONIC }, new double[]{ 0.0 }),
                afterFadeIn, lineHz, harmonicScale(), ADDED_AMP, ADDED_PHI, 0.0, ADDED_PHI);
    }

    /** After a clear the table is gone: a per-line apply then fades the line
     *  it names in from zero, alone - none of the cleared lines comes back. */
    @Test
    void compensationLines_afterAClearFadeTheNamedLineInAlone() {
        SignalGenerator faded   = harmonicGenerator(RIG, FADE_HARMONICS, OLD_AMPS, OLD_PHIS);
        SignalGenerator without = plainSine(RIG);
        SignalGenerator target  = harmonicGenerator(RIG, new int[]{ ADDED_HARMONIC },
                new double[]{ ADDED_AMP }, new double[]{ ADDED_PHI });
        renderAlike(FADE_LEAD, faded, without, target);
        faded.clearCompensation();

        assertLineFades(RIG, faded, without, target,
                g -> g.applyCompensationLines(new double[]{ ADDED_AMP },
                        new int[]{ ADDED_HARMONIC }, new double[]{ ADDED_PHI }),
                FADE_LEAD, ADDED_HARMONIC * RIG.f1Hz(), harmonicScale(),
                0.0, ADDED_PHI, ADDED_AMP, ADDED_PHI);
    }

    /**
     * A second per-line apply half-way through a running fade that names
     * ANOTHER line: the first line carries on with its fade exactly as if
     * nothing had happened - one unbroken linear ramp - while the other line
     * starts a fade of its own.
     */
    @Test
    void compensationLines_aSecondApplyMidFadeLeavesTheRunningFadeUnbroken() {
        int fade = RIG.fadeSamples();
        int half = fade / 2;
        double[] otherAmps = withChanged(OLD_AMPS, NEW_AMP);
        otherAmps[OTHER_LINE] = OTHER_AMP;
        double[] otherPhis = withChanged(OLD_PHIS, NEW_PHI);
        otherPhis[OTHER_LINE] = OTHER_PHI;
        Consumer<SignalGenerator> second = g -> g.applyCompensationLines(
                new double[]{ OTHER_AMP }, new int[]{ FADE_HARMONICS[OTHER_LINE] },
                new double[]{ OTHER_PHI });
        SignalGenerator faded   = harmonicGenerator(RIG, FADE_HARMONICS, OLD_AMPS, OLD_PHIS);
        SignalGenerator without = harmonicGenerator(RIG, FADE_HARMONICS, withChanged(OLD_AMPS, 0.0),
                OLD_PHIS);
        SignalGenerator target  = harmonicGenerator(RIG, FADE_HARMONICS, otherAmps, otherPhis);
        renderAlike(FADE_LEAD, faded, without, target);

        double[] f0 = render(faded, 1);
        double[] w0 = render(without, 1);
        changeTheMiddleHarmonic(faded);
        double[] f1 = render(faded, half);
        double[] w1 = render(without, half);
        // The other line changes in both, so its fade cancels in the difference.
        second.accept(faded);
        second.accept(without);
        double[] f2 = render(faded, fade + FADE_TAIL);
        double[] w2 = render(without, fade + FADE_TAIL);
        render(target, 1 + half);
        double[] t2 = render(target, fade + FADE_TAIL);

        double[] line = diff(concat(f0, f1, f2), concat(w0, w1, w2));
        double[] amps = new double[line.length];
        double[] phis = new double[line.length];
        fillFade(RIG, amps, phis, 0, 1, OLD_AMPS[CHANGED], OLD_PHIS[CHANGED], NEW_AMP, NEW_PHI);
        assertLineIs(RIG, line, FADE_LEAD, FADE_HARMONICS[CHANGED] * RIG.f1Hz(), harmonicScale(),
                amps, phis);
        assertArrayEquals(tail(t2, fade), tail(f2, fade), 0.0,
                "once the second fade is done both lines stand at the new table's values, bit for bit");
    }

    /**
     * A second per-line apply half-way through a running fade that names the
     * SAME line again: the line turns at the seam, with no step, onto a new
     * linear fade - from the magnitude and phase it had reached, not from its
     * old or its first target - to its second target.
     */
    @Test
    void compensationLines_aLineRetargetedMidFadeTurnsFromWhereItIs() {
        int fade = RIG.fadeSamples();
        int half = fade / 2;
        SignalGenerator faded   = harmonicGenerator(RIG, FADE_HARMONICS, OLD_AMPS, OLD_PHIS);
        SignalGenerator without = harmonicGenerator(RIG, FADE_HARMONICS, withChanged(OLD_AMPS, 0.0),
                OLD_PHIS);
        SignalGenerator target  = harmonicGenerator(RIG, FADE_HARMONICS,
                withChanged(OLD_AMPS, RETARGET_AMP), withChanged(OLD_PHIS, RETARGET_PHI));
        renderAlike(FADE_LEAD, faded, without, target);

        double[] f0 = render(faded, 1);
        double[] w0 = render(without, 1);
        changeTheMiddleHarmonic(faded);
        double[] f1 = render(faded, half);
        double[] w1 = render(without, half);
        faded.applyCompensationLines(new double[]{ RETARGET_AMP },
                new int[]{ FADE_HARMONICS[CHANGED] }, new double[]{ RETARGET_PHI });
        double[] f2 = render(faded, fade + FADE_TAIL);
        double[] w2 = render(without, fade + FADE_TAIL);
        render(target, 1 + half);
        double[] t2 = render(target, fade + FADE_TAIL);

        double[] line = diff(concat(f0, f1, f2), concat(w0, w1, w2));
        double[] amps = new double[line.length];
        double[] phis = new double[line.length];
        fillFade(RIG, amps, phis, 0, 1, OLD_AMPS[CHANGED], OLD_PHIS[CHANGED], NEW_AMP, NEW_PHI);
        int seam = 1 + half;
        fillFade(RIG, amps, phis, seam, seam, amps[seam], phis[seam], RETARGET_AMP, RETARGET_PHI);
        assertLineIs(RIG, line, FADE_LEAD, FADE_HARMONICS[CHANGED] * RIG.f1Hz(), harmonicScale(),
                amps, phis);
        assertArrayEquals(tail(t2, fade), tail(f2, fade), 0.0,
                "once the second fade is done the line stands at its second target, bit for bit");
    }

    /**
     * A whole-table apply and a per-line apply with no sample between them: the
     * per-line fade starts from the whole table's values, exactly as if a
     * sample had fallen between the two - the unnamed lines take the whole
     * table's values at once, as a whole apply does, and none fades in from
     * zero.  Bit for bit the output of a generator that held the whole table
     * all along and took the same per-line apply at the same sample.
     */
    @Test
    void compensationLines_rightAfterAWholeApplyFadeFromThatTable() {
        Consumer<SignalGenerator> lines = this::changeTheMiddleHarmonic;
        SignalGenerator faded     = harmonicGenerator(RIG, FADE_HARMONICS, OLD_AMPS, OLD_PHIS);
        SignalGenerator reference = harmonicGenerator(RIG, FADE_HARMONICS, WHOLE_AMPS, OLD_PHIS);
        renderAlike(FADE_LEAD, faded, reference);

        faded.applyCompensation(WHOLE_AMPS, FADE_HARMONICS, OLD_PHIS);
        lines.accept(faded);
        lines.accept(reference);

        assertArrayEquals(render(reference, RIG.fadeSamples() + FADE_TAIL),
                render(faded, RIG.fadeSamples() + FADE_TAIL), 0.0,
                "the per-line fade must take over from the whole table it was derived from");
    }

    /** The middle harmonic of the table fades to its new target on {@code rig}
     *  - see {@link #compensationLines_fadeTheNamedHarmonicLinearlyToItsTarget}. */
    private void assertTheMiddleHarmonicFades(Rig rig) {
        SignalGenerator faded   = harmonicGenerator(rig, FADE_HARMONICS, OLD_AMPS, OLD_PHIS);
        SignalGenerator without = harmonicGenerator(rig, FADE_HARMONICS,
                withChanged(OLD_AMPS, 0.0), OLD_PHIS);
        SignalGenerator target  = harmonicGenerator(rig, FADE_HARMONICS,
                withChanged(OLD_AMPS, NEW_AMP), withChanged(OLD_PHIS, NEW_PHI));
        renderAlike(FADE_LEAD, faded, without, target);

        assertLineFades(rig, faded, without, target, this::changeTheMiddleHarmonic,
                FADE_LEAD, FADE_HARMONICS[CHANGED] * rig.f1Hz(), harmonicScale(),
                OLD_AMPS[CHANGED], OLD_PHIS[CHANGED], NEW_AMP, NEW_PHI);
    }

    /** The per-line apply most tests make: the middle harmonic to its new target. */
    private void changeTheMiddleHarmonic(SignalGenerator gen) {
        gen.applyCompensationLines(new double[]{ NEW_AMP },
                new int[]{ FADE_HARMONICS[CHANGED] }, new double[]{ NEW_PHI });
    }

    /**
     * Renders one sample of each generator, applies {@code apply} to
     * {@code faded}, renders the fade and a tail of each, and asserts the fade
     * on the line's own contribution - {@code faded} minus {@code without},
     * which holds every other line alike - through {@link #assertLineIs}: one
     * linear fade from the old to the new values over the rig's fade length.
     * From the end of the fade on, {@code faded} must be {@code target} bit
     * for bit.
     *
     * @param n0 the absolute index of the sample rendered before the apply
     */
    private void assertLineFades(Rig rig, SignalGenerator faded, SignalGenerator without,
            SignalGenerator target, Consumer<SignalGenerator> apply, int n0, double lineHz,
            double scale, double fromAmp, double fromPhi, double toAmp, double toPhi) {
        int fade = rig.fadeSamples();
        double[] f0 = render(faded, 1);
        double[] w0 = render(without, 1);
        render(target, 1);
        apply.accept(faded);
        double[] f1 = render(faded,   fade + FADE_TAIL);
        double[] w1 = render(without, fade + FADE_TAIL);
        double[] t1 = render(target,  fade + FADE_TAIL);

        double[] line = diff(concat(f0, f1), concat(w0, w1));
        double[] amps = new double[line.length];
        double[] phis = new double[line.length];
        fillFade(rig, amps, phis, 0, 1, fromAmp, fromPhi, toAmp, toPhi);
        assertLineIs(rig, line, n0, lineHz, scale, amps, phis);
        assertArrayEquals(tail(t1, fade), tail(f1, fade), 0.0,
                "from the end of the fade on the output must be the new table's, bit for bit");
    }

    /**
     * Writes into {@code amps} / {@code phis}, from index {@code start} on, a
     * line's magnitude and phase under one fade: index {@code applyAt} is the
     * first sample rendered after the apply, which still renders the from
     * values; the fade reaches its target the rig's fade length later and
     * holds it there, the phase taking the shorter way round.
     */
    private void fillFade(Rig rig, double[] amps, double[] phis, int start, int applyAt,
            double fromAmp, double fromPhi, double toAmp, double toPhi) {
        double dPhi = Math.IEEEremainder(toPhi - fromPhi, 2.0 * Math.PI);   // the shorter way
        for (int m = start; m < amps.length; m++) {
            double frac = Math.min(1.0, Math.max(0, m - applyAt) / (double) rig.fadeSamples());
            amps[m] = fromAmp + (toAmp - fromAmp) * frac;
            phis[m] = fromPhi + dPhi * frac;
        }
    }

    /**
     * Asserts a line's own contribution sample by sample: {@code line[m]} must
     * be {@code -scale amps[m] cos(2 pi lineHz (n0 + m) / fs + phis[m])}, and
     * no sample may step from the one before by more than the line's own slope
     * at its largest magnitude plus that sample's change of magnitude and
     * phase.  The line is read alone because a step of a 1 % line is smaller
     * than one sample's slope of the fundamental it rides on.
     *
     * @param n0 the absolute sample index of {@code line[0]}
     */
    private void assertLineIs(Rig rig, double[] line, int n0, double lineHz, double scale,
            double[] amps, double[] phis) {
        double maxAmp = 0.0;
        for (double amp : amps) {
            maxAmp = Math.max(maxAmp, amp);
        }
        double slope = 2.0 * Math.PI * lineHz / rig.rate();
        for (int m = 0; m < line.length; m++) {
            double expected = -scale * amps[m]
                    * Math.cos(2.0 * Math.PI * lineHz * (n0 + m) / rig.rate() + phis[m]);
            assertEquals(expected, line[m], scale * FADE_TOL,
                    "sample " + m + " of the fade leaves the linear ramp of magnitude and phase");
            if (m > 0) {
                double dPhi    = Math.abs(Math.IEEEremainder(phis[m] - phis[m - 1], 2.0 * Math.PI));
                double maxStep = scale * (Math.abs(amps[m] - amps[m - 1])
                        + maxAmp * (slope + dPhi) + FADE_TOL);
                double step    = Math.abs(line[m] - line[m - 1]);
                assertTrue(step <= maxStep, "sample " + m + " of the fade steps by " + step
                        + ", more than the line's own slope and ramp " + maxStep);
            }
        }
    }

    /** {@code x - y}, sample by sample. */
    private double[] diff(double[] x, double[] y) {
        double[] d = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            d[i] = x[i] - y[i];
        }
        return d;
    }

    /** The blocks one after the other. */
    private double[] concat(double[]... blocks) {
        int n = 0;
        for (double[] block : blocks) {
            n += block.length;
        }
        double[] all = new double[n];
        int at = 0;
        for (double[] block : blocks) {
            System.arraycopy(block, 0, all, at, block.length);
            at += block.length;
        }
        return all;
    }

    /** {@code block} from index {@code from} on. */
    private double[] tail(double[] block, int from) {
        return Arrays.copyOfRange(block, from, block.length);
    }

    /** {@code after} holds {@code before}'s lines at the same indices, every
     *  one but {@link #CHANGED} bit for bit, and that one at its new target. */
    private void assertOnlyTheChangedEntryMoved(CompensationTable before, CompensationTable after) {
        assertEquals(before.amp.length, after.amp.length, "naming a held line adds no entry");
        for (int i = 0; i < before.amp.length; i++) {
            assertEquals(before.a[i], after.a[i], "entry " + i + " names another line");
            assertEquals(before.b[i], after.b[i], "entry " + i + " names another line");
            if (i == CHANGED) continue;
            assertEquals(Double.doubleToRawLongBits(before.amp[i]),
                    Double.doubleToRawLongBits(after.amp[i]),
                    "the unnamed entry " + i + " changed its ratio");
            assertEquals(before.phaseOff64[i], after.phaseOff64[i],
                    "the unnamed entry " + i + " changed its phase");
        }
        assertEquals(NEW_AMP, after.amp[CHANGED], 0.0, "the named entry takes its new ratio");
        assertNotEquals(before.phaseOff64[CHANGED], after.phaseOff64[CHANGED],
                "the named entry takes its new phase");
    }

    /** The plain sine at the rig's fundamental and rate. */
    private SignalGenerator plainSine(Rig rig) {
        return new SignalGenerator(GenSignalForm.SINE, rig.f1Hz(), rig.rate(),
                FADE_VRMS, FADE_VRMS, DAC_FS_VRMS, DAC_FS_VRMS);
    }

    /** The rig's sine holding the harmonics {@code hNums} as a whole table. */
    private SignalGenerator harmonicGenerator(Rig rig, int[] hNums, double[] amps, double[] phis) {
        SignalGenerator gen = plainSine(rig);
        gen.applyCompensation(amps, hNums, phis);
        return gen;
    }

    /** The fade tests' two tones at an even split, holding the products
     *  {@link #PRODUCT_A} / {@link #PRODUCT_B} as a whole table. */
    private SignalGenerator productGenerator(double[] amps, double[] phis) {
        double splitPct = DUAL_SPLITS_PCT[0];
        SignalGenerator gen = new SignalGenerator(GenSignalForm.DUAL_TONE, RIG.f1Hz(), RIG.rate(),
                FADE_VRMS, FADE_VRMS, DAC_FS_VRMS, DAC_FS_VRMS);
        gen.setDualToneFrequency2(FADE_F2_HZ);
        gen.setDualToneAmplitudes(splitPct, 100.0 - splitPct);
        gen.setAmplitudesVrms(FADE_VRMS, FADE_VRMS);
        gen.applyDualToneCompensation(amps, PRODUCT_A, PRODUCT_B, phis);
        return gen;
    }

    /** A harmonic's peak per unit ratio: the sine's own peak, which the ratio
     *  is taken against. */
    private double harmonicScale() {
        return FADE_VRMS * Constants.SQRT2 / DAC_FS_VRMS;
    }

    /** A product's peak per unit ratio: the F1 tone's peak - the lane level,
     *  normalised to the two weights' RMS, times F1's weight. */
    private double productScale() {
        double w1 = DUAL_SPLITS_PCT[0] / 100.0;
        double w2 = 1.0 - w1;
        return FADE_VRMS / (DAC_FS_VRMS * Math.sqrt((w1 * w1 + w2 * w2) / 2.0)) * w1;
    }

    /** {@code values} with the {@link #CHANGED} entry replaced by {@code value}. */
    private double[] withChanged(double[] values, double value) {
        return withEntry(values, CHANGED, value);
    }

    /** {@code values} with entry {@code index} replaced by {@code value}. */
    private double[] withEntry(double[] values, int index, double value) {
        double[] v = values.clone();
        v[index] = value;
        return v;
    }

    /** Renders {@code samples} of each generator, so they stand at one sample
     *  position. */
    private void renderAlike(int samples, SignalGenerator... gens) {
        for (SignalGenerator g : gens) {
            render(g, samples);
        }
    }

    // -------------------------------------------------------------------------
    // Rectangle / triangle duty: whole samples of the period-aligned grid
    // -------------------------------------------------------------------------

    /** 384 kHz at 1 kHz is 384 samples per period - the grid the duty bracket
     *  divides by. */
    private static final int    DUTY_RATE   = 384_000;
    private static final double DUTY_FREQ   = 1_000.0;
    private static final int    DUTY_PERIOD = 384;
    private static final int    DUTY_PERIODS = 10;

    /**
     * The pulse is {@code round(duty · n)} samples of the {@code n}-sample
     * period - the count the duty bracket shows - never fewer than one and
     * never the whole period.  A duty below one sample (0.25 % of 384 samples
     * is 0.96 samples) once left the pulse to the phase accumulator's rounding
     * and vanished; it is the one-sample pulse the bracket promises.
     */
    @Test
    void rectangle_dutyIsWholeSamplesOfThePeriod() {
        SignalGenerator gen = new SignalGenerator(
                GenSignalForm.RECTANGLE, DUTY_FREQ, DUTY_RATE, 1.0, 1.0, DAC_FS_VRMS, DAC_FS_VRMS);

        gen.setRectangleDuty(0.0025);
        assertEquals(1, highSamplesPerPeriod(gen),
                "a sub-sample duty still emits the ONE high sample per period the bracket shows");
        gen.setRectangleDuty(0.5);
        assertEquals(DUTY_PERIOD / 2, highSamplesPerPeriod(gen),
                "50 % of 384 samples is 192 high samples per period");
        gen.setRectangleDuty(0.999);
        assertEquals(DUTY_PERIOD - 1, highSamplesPerPeriod(gen),
                "at most n - 1 high samples - the train never collapses to a DC level");
    }

    /** The triangle's corner lands ON a sample: with a one-sample rise the
     *  second sample of every period is the exact peak. */
    @Test
    void triangle_cornerLandsOnASampleOfThePeriod() {
        SignalGenerator gen = new SignalGenerator(
                GenSignalForm.TRIANGLE, DUTY_FREQ, DUTY_RATE, 1.0, 1.0, DAC_FS_VRMS, DAC_FS_VRMS);
        gen.setTriangleDuty(0.0025);   // 0.96 samples of rise -> one sample
        double peak = 1.0 * Constants.SQRT3 / DAC_FS_VRMS;   // rawRms(TRIANGLE) = 1/√3

        double[] block = render(gen, DUTY_PERIOD * DUTY_PERIODS);
        for (int p = 0; p < DUTY_PERIODS; p++) {
            assertEquals(-peak, block[p * DUTY_PERIOD], peak * 1e-9,
                    "period " + p + " starts at the trough");
            assertEquals(peak, block[p * DUTY_PERIOD + 1], peak * 1e-9,
                    "period " + p + ": the corner is the second sample, exactly at the peak");
        }
    }

    /** High samples per period over {@link #DUTY_PERIODS} periods; every period
     *  must carry the same count, or the pulse is at the mercy of rounding. */
    private int highSamplesPerPeriod(SignalGenerator gen) {
        double[] block = render(gen, DUTY_PERIOD * DUTY_PERIODS);
        int first = -1;
        for (int p = 0; p < DUTY_PERIODS; p++) {
            int high = 0;
            for (int i = 0; i < DUTY_PERIOD; i++) {
                if (block[p * DUTY_PERIOD + i] > 0.0) high++;
            }
            if (first < 0) first = high;
            assertEquals(first, high, "period " + p + " carries a different pulse width");
        }
        return first;
    }

    // -------------------------------------------------------------------------
    // The pilot lines: two generated tones the FFT-side phase alignment reads
    // as a time-base reference
    // -------------------------------------------------------------------------

    /** The first pilot's frequency in the level tests.  15 kHz is a whole
     *  number of cycles in a one-second block at 48 kHz, as is the 1 kHz tone
     *  beside it, so the correlation reads each exactly and neither leaks into
     *  the other. */
    private static final double PILOT_HZ  = 15_000.0;
    /** The second pilot's frequency there - the alignment asks for a PAIR of
     *  lines, each of which pins the elapsed sample count only modulo its own
     *  period.  17.5 kHz is a whole number of cycles in the same block, so the
     *  correlation reads it exactly too, and it lands on no harmonic of the
     *  1 kHz tone. */
    private static final double PILOT_2_HZ = 17_500.0;
    /** The pilots' level - the value the alignment asks for, carried by both
     *  lines. */
    private static final double PILOT_DBV = -100.0;
    /** A pilot's expected peak level on a lane: its own V RMS
     *  ({@code 10^(dBV/20)}) at a sine's peak / RMS ratio, against the DAC
     *  full scale - the main tone's commanded amplitude plays no part. */
    private static final double PILOT_PEAK = Math.pow(10.0, PILOT_DBV / 20.0)
            * Constants.SQRT2 / DAC_FS_VRMS;
    /** How far a pilot's own level may sit from the requested one, dB. */
    private static final double PILOT_TOL_DB = 0.1;
    /** How far the MAIN tone's level may move when the pilots are added, dB -
     *  it must not move at all, so this is a numerical bound, not a budget. */
    private static final double TONE_TOL_DB = 1e-3;
    /** Samples rendered after a pilot is switched on or off before anything is
     *  read: the generator fades a pilot's level with a 50 ms time constant,
     *  and two seconds at 48 kHz are forty of them - the fade's remainder,
     *  e^-40, lies far under the trim test's per-sample 1e-9, and a line fading
     *  out has fallen to exact silence.  A whole number of cycles of both trim
     *  lines, so the trim seam still falls on their crests. */
    private static final int    PILOT_SETTLE_SAMPLES = 96_000;
    /** The first pilot's frequency in the trim test: 80 samples per cycle at
     *  48 kHz, slow enough that a restarted phase is a step no slope can
     *  explain. */
    private static final double TRIM_HZ         = 600.0;
    /** The trimmed value - a 1 % correction, far more than a lock ever asks. */
    private static final double TRIM_TARGET_HZ  = 606.0;
    /** The second pilot's frequency in the trim test, and its trimmed value at
     *  the same 1 %.  3 kHz stands at ITS crest at the seam as well
     *  ({@code 3000 · 96020 / 48000} is a whole number of cycles plus a
     *  quarter), so a restart of either line is a full-amplitude step. */
    private static final double TRIM_2_HZ        = 3_000.0;
    private static final double TRIM_2_TARGET_HZ = 3_030.0;
    /** A quarter cycle of {@link #TRIM_HZ} at 48 kHz.  The trim is placed here
     *  so the seam falls on the crest: a phase restart is then a full-amplitude
     *  step instead of one hidden in the slope at a zero crossing. */
    private static final int    TRIM_SEAM_OFFSET = 20;
    /** Samples checked against the analytic continuation past the trim. */
    private static final int    TRIM_CHECK_SAMPLES = 100;
    /** The sweep the pilot must not reach: 100 Hz to 1 kHz over one second. */
    private static final double SWEEP_START_HZ = 100.0;
    private static final double SWEEP_END_HZ   = 1_000.0;

    /** {@code measured} against {@code expected} in dB - the domain the level
     *  assertions state their tolerance in. */
    private double levelDiffDb(double measured, double expected) {
        return 20.0 * Math.log10(measured / expected);
    }

    /**
     * Both pilots arrive at the requested frequencies and at their own
     * absolute level, and the tone they ride on does not move: a pilot is
     * added after the per-lane scaling, so it enters neither the main tone's
     * level nor the RMS normalisation that level is derived from.
     */
    @Test
    void pilots_arriveAtTheirOwnLevelAndLeaveTheToneAlone() {
        int    sampleRate = 48_000;
        double freqHz     = 1_000.0;
        double vrms       = 1.0;

        SignalGenerator plain = new SignalGenerator(
                GenSignalForm.SINE, freqHz, sampleRate, vrms, vrms, DAC_FS_VRMS, DAC_FS_VRMS);
        SignalGenerator piloted = new SignalGenerator(
                GenSignalForm.SINE, freqHz, sampleRate, vrms, vrms, DAC_FS_VRMS, DAC_FS_VRMS);
        piloted.setPilotTones(PILOT_HZ, PILOT_2_HZ, PILOT_DBV);
        render(piloted, PILOT_SETTLE_SAMPLES);

        double[] without = render(plain, sampleRate);
        double[] with    = render(piloted, sampleRate);

        assertEquals(0.0, levelDiffDb(componentPeak(with, sampleRate, PILOT_HZ), PILOT_PEAK),
                PILOT_TOL_DB, "the first pilot must arrive at the level its dBV names");
        assertEquals(0.0, levelDiffDb(componentPeak(with, sampleRate, PILOT_2_HZ), PILOT_PEAK),
                PILOT_TOL_DB, "the second pilot must arrive at the same level");
        assertEquals(0.0, levelDiffDb(componentPeak(with, sampleRate, freqHz),
                        componentPeak(without, sampleRate, freqHz)),
                TONE_TOL_DB, "adding the pilots must not move the main tone's level");
    }

    /** Clearing the pilots takes both lines out of the frames entirely. */
    @Test
    void pilots_areGoneAfterClear() {
        int    sampleRate = 48_000;
        double freqHz     = 1_000.0;
        double vrms       = 1.0;

        SignalGenerator gen = new SignalGenerator(
                GenSignalForm.SINE, freqHz, sampleRate, vrms, vrms, DAC_FS_VRMS, DAC_FS_VRMS);
        gen.setPilotTones(PILOT_HZ, PILOT_2_HZ, PILOT_DBV);
        render(gen, PILOT_SETTLE_SAMPLES);
        double[] on = render(gen, sampleRate);
        gen.clearPilotTones();
        render(gen, PILOT_SETTLE_SAMPLES);
        double[] off = render(gen, sampleRate);

        assertEquals(0.0, levelDiffDb(componentPeak(on, sampleRate, PILOT_HZ), PILOT_PEAK),
                PILOT_TOL_DB, "the first pilot was running before the clear");
        assertEquals(0.0, levelDiffDb(componentPeak(on, sampleRate, PILOT_2_HZ), PILOT_PEAK),
                PILOT_TOL_DB, "the second pilot was running before the clear");
        assertTrue(componentPeak(off, sampleRate, PILOT_HZ) < PILOT_PEAK * 1e-6,
                "nothing may remain at the first pilot's frequency after the clear");
        assertTrue(componentPeak(off, sampleRate, PILOT_2_HZ) < PILOT_PEAK * 1e-6,
                "nothing may remain at the second pilot's frequency after the clear");
    }

    /**
     * A re-aim writes both pilots' phase increments and their level and never
     * an accumulator, so each line continues from the phase it had reached:
     * past the seam every sample matches the sum of the two analytic
     * continuations at the NEW frequencies, and the seam itself steps by no
     * more than one sample of those two sines' own slopes.  A restarted phase -
     * the failure this guards, in either line - lands a near-full-amplitude
     * step there, because the seam is placed on both crests.
     *
     * <p>The main tone is commanded to 0 V RMS so the frames ARE the pilots; at
     * -100 dBV beside a volt of tone no per-sample assertion could see them.
     */
    @Test
    void pilotReaim_isPhaseContinuousForBothLines() {
        int    sampleRate = 48_000;
        int    beforeLen  = PILOT_SETTLE_SAMPLES + TRIM_SEAM_OFFSET;

        SignalGenerator gen = new SignalGenerator(
                GenSignalForm.SINE, 1_000.0, sampleRate, 0.0, 0.0, DAC_FS_VRMS, DAC_FS_VRMS);
        gen.setPilotTones(TRIM_HZ, TRIM_2_HZ, PILOT_DBV);

        double[] before = render(gen, beforeLen);
        gen.setPilotTones(TRIM_TARGET_HZ, TRIM_2_TARGET_HZ, PILOT_DBV);
        double[] after  = render(gen, sampleRate);

        double seamPhase  = 2.0 * Math.PI * TRIM_HZ * beforeLen / sampleRate;
        double seamPhase2 = 2.0 * Math.PI * TRIM_2_HZ * beforeLen / sampleRate;
        double step       = Math.abs(after[0] - before[beforeLen - 1]);
        double maxStep    = PILOT_PEAK * 2.0
                * (Math.sin(Math.PI * TRIM_TARGET_HZ / sampleRate)
                 + Math.sin(Math.PI * TRIM_2_TARGET_HZ / sampleRate));
        assertTrue(step <= maxStep,
                "the trim stepped the pilot pair by " + step + ", more than one sample's slope "
                        + maxStep + " - a phase was restarted");
        for (int m = 0; m < TRIM_CHECK_SAMPLES; m++) {
            double expected = PILOT_PEAK
                    * (Math.sin(seamPhase + 2.0 * Math.PI * TRIM_TARGET_HZ * m / sampleRate)
                     + Math.sin(seamPhase2 + 2.0 * Math.PI * TRIM_2_TARGET_HZ * m / sampleRate));
            assertEquals(expected, after[m], PILOT_PEAK * 1e-9,
                    "sample " + m + " past the trim leaves the phase-continuous "
                            + "continuation of both lines at their trimmed frequencies");
        }
    }

    /**
     * A sweep carries no pilot: its samples are bit-identical with and without
     * one, because the sweep's own spectrum IS the measurement and an added
     * line in it would be read as the device's.  The request is not lost
     * though - it is remembered and both lines start with the next tonal form.
     */
    @Test
    void sweep_carriesNoPilotUntilATonalFormStarts() {
        int    sampleRate = 48_000;
        double vrms       = 1.0;

        SignalGenerator plain = new SignalGenerator(SWEEP_START_HZ, SWEEP_END_HZ, sampleRate,
                sampleRate, vrms, vrms, DAC_FS_VRMS, DAC_FS_VRMS);
        SignalGenerator piloted = new SignalGenerator(SWEEP_START_HZ, SWEEP_END_HZ, sampleRate,
                sampleRate, vrms, vrms, DAC_FS_VRMS, DAC_FS_VRMS);
        piloted.setPilotTones(PILOT_HZ, PILOT_2_HZ, PILOT_DBV);

        assertArrayEquals(render(plain, sampleRate), render(piloted, sampleRate), 0.0,
                "a sweep's samples must be untouched by a pilot request");

        // The sweep constructor leaves the tone accumulator at a zero
        // increment, so the tonal form that follows is silent apart from the
        // pilots - which are exactly the lines under test.
        piloted.setForm(GenSignalForm.SINE);
        render(piloted, PILOT_SETTLE_SAMPLES);
        double[] tonal = render(piloted, sampleRate);
        assertEquals(0.0, levelDiffDb(componentPeak(tonal, sampleRate, PILOT_HZ), PILOT_PEAK),
                PILOT_TOL_DB, "the remembered first pilot must start with the tonal form");
        assertEquals(0.0, levelDiffDb(componentPeak(tonal, sampleRate, PILOT_2_HZ), PILOT_PEAK),
                PILOT_TOL_DB, "the remembered second pilot must start with it too");
    }

    /** Renders {@code samples} consecutive samples into a block. */
    private double[] render(SignalGenerator gen, int samples) {
        double[] block = new double[samples];
        for (int n = 0; n < samples; n++) {
            block[n] = next(gen);
        }
        return block;
    }

    /** Peak amplitude of the component at {@code freqHz} in {@code block}, by
     *  quadrature correlation.  Exact - not an estimate - when the block holds
     *  a whole number of cycles of every component present, which is what the
     *  callers arrange: the other components are then orthogonal to this one
     *  and contribute nothing to the sums. */
    private double componentPeak(double[] block, int sampleRate, double freqHz) {
        double re = 0.0;
        double im = 0.0;
        for (int n = 0; n < block.length; n++) {
            double w = 2.0 * Math.PI * freqHz * n / sampleRate;
            re += block[n] * Math.cos(w);
            im += block[n] * Math.sin(w);
        }
        return 2.0 * Math.hypot(re, im) / block.length;
    }
}
