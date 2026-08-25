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

import org.edgo.audio.measure.common.Constants;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                GenSignalForm.SINE, freqHz, sampleRate, vrms, DAC_FS_VRMS);

        double expectedPeak = vrms * Constants.SQRT2 / DAC_FS_VRMS;
        for (int n = 0; n < samples; n++) {
            double actual   = gen.nextSample();
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
                GenSignalForm.SINE, freqHz, sampleRate, vrms, DAC_FS_VRMS);

        double sumSq = 0.0;
        for (int n = 0; n < samples; n++) {
            double s = gen.nextSample();
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
                GenSignalForm.TRIANGLE, freqHz, sampleRate, vrms, DAC_FS_VRMS);

        double sumSq = 0.0;
        for (int n = 0; n < samples; n++) {
            double s = gen.nextSample();
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
                GenSignalForm.RECTANGLE, freqHz, sampleRate, vrms, DAC_FS_VRMS);

        double sumSq = 0.0;
        for (int n = 0; n < samples; n++) {
            double s = gen.nextSample();
            sumSq += s * s;
        }
        double rmsNormalised = Math.sqrt(sumSq / samples);
        double rmsVolts      = rmsNormalised * DAC_FS_VRMS;
        assertEquals(vrms, rmsVolts, vrms * 1e-3,
                "square-wave RMS should equal peak");
    }

    @Test
    void sine_outputClipsWithinNormalisedRange() {
        // nextSample() returns samples in [-1, +1] regardless of input
        // amplitude - clipping the caller's responsibility.  But for a
        // reasonable amplitude (< full-scale) every sample should stay
        // within the normalised range with room to spare.
        SignalGenerator gen = new SignalGenerator(
                GenSignalForm.SINE, 1_000.0, 48_000, 1.0, DAC_FS_VRMS);
        double peak = 0.0;
        for (int n = 0; n < 48_000; n++) {
            double s = gen.nextSample();
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
                    GenSignalForm.DUAL_TONE, f1Hz, sampleRate, vrms, DAC_FS_VRMS);
            gen.setDualToneFrequency2(f2Hz);
            gen.setDualToneAmplitudes(splitPct, 100.0 - splitPct);
            gen.setAmplitudeVrms(vrms);
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
                GenSignalForm.SINE, freqHz, sampleRate, vrms, DAC_FS_VRMS);
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
                GenSignalForm.SINE, freqHz, sampleRate, vrms, DAC_FS_VRMS);
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
                GenSignalForm.SINE, freqHz, sampleRate, vrms, DAC_FS_VRMS);
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
                GenSignalForm.DUAL_TONE, f1Hz, sampleRate, vrms, DAC_FS_VRMS);
        gen.setDualToneFrequency2(f2Hz);
        gen.setDualToneAmplitudes(splitPct, 100.0 - splitPct);
        gen.setAmplitudeVrms(vrms);
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

    /** Renders {@code samples} consecutive samples into a block. */
    private double[] render(SignalGenerator gen, int samples) {
        double[] block = new double[samples];
        for (int n = 0; n < samples; n++) {
            block[n] = gen.nextSample();
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
