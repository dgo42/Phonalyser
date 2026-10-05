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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
