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

package org.edgo.audio.measure.gui.sound;

import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.enums.OutputChannels;

/**
 * Everything one {@link GeneratorLane#start(GeneratorRun)} needs, as ONE
 * value: the caller derives it - the generator controller from the
 * preferences, a sweep engine from its own numbers - and the lane runs it,
 * locally by building and rendering the signal source, remotely by commanding
 * the bench's generator.  The lane keeps the last run it was started with, so
 * {@link GeneratorLane#restart()} replays the same session without any caller
 * re-deriving anything.
 *
 * <p>Pane-only tunables (duties, dual-tone split, predistortion paths, the
 * FFT snap grid) are deliberately NOT here: they belong to the
 * preferences-driven forms alone, the lane reads them where it always has,
 * and a sweep engine never touches them.
 *
 * @param form           the waveform; sweeps pass {@code LOG_SWEEP} /
 *                       {@code LINEAR_SWEEP} with {@code sweep} filled
 * @param frequencyHz    tone 1's emit frequency, already snapped/aligned by
 *                       the caller's boundary; ignored by the sweep forms
 * @param amplitudeVrmsLeft  the LEFT lane's commanded amplitude
 * @param amplitudeVrmsRight the RIGHT lane's commanded amplitude; a sweep
 *                       engine commands both lanes alike
 * @param sampleRate     the output rate every sample count is computed against
 * @param bitDepth       the output word length
 * @param ditherBitsLeft  the LEFT lane's TPDF dither depth, 0 = off
 * @param ditherBitsRight the RIGHT lane's TPDF dither depth - its own, since
 *                       a dither entered as a level resolves against each
 *                       lane's full scale
 * @param dacFsVoltageAmplLeft  the LOCAL left DAC full scale (peak) the left
 *                       lane's amplitude is scaled against; a bench converts
 *                       against its own card
 * @param dacFsVoltageAmplRight the LOCAL right DAC full scale (peak), the
 *                       right lane's own
 * @param channels       which DAC lane(s) emit; fixed at open on a bench
 * @param requireGrantedRate whether a bench granting ANOTHER rate than
 *                       {@code sampleRate} REFUSES the start instead of being
 *                       adapted to.  The pane's tone adapts (every derived
 *                       number is recomputed against the grant); a sweep
 *                       consumer refuses - its whole premise is sample counts
 *                       on the asked clock
 * @param sweep          the sweep description, {@code null} for every
 *                       non-sweep form
 */
public record GeneratorRun(
        GenSignalForm form,
        double frequencyHz,
        double amplitudeVrmsLeft,
        double amplitudeVrmsRight,
        int sampleRate,
        int bitDepth,
        double ditherBitsLeft,
        double ditherBitsRight,
        double dacFsVoltageAmplLeft,
        double dacFsVoltageAmplRight,
        OutputChannels channels,
        boolean requireGrantedRate,
        SweepSpec sweep) {

    /**
     * One sweep, in SAMPLES at {@link GeneratorRun#sampleRate} - seconds are
     * the caller's business, because a duration converted at the wrong rate
     * is a sweep of the wrong length.
     *
     * @param leadInSamples silence emitted before the chirp (the frequency
     *                      response's alignment slack; 0 for the pane's sweep
     *                      and the notch loop)
     * @param loop          whether the chirp replays back-to-back (the notch
     *                      tuner's live loop) or plays once (a measurement)
     */
    public record SweepSpec(
            double startHz,
            double stopHz,
            int durationSamples,
            int leadInSamples,
            int fadeInSamples,
            int fadeOutSamples,
            boolean loop) {
    }

    /** The same run on other output lane(s) - what a lane-gate change needs,
     *  since a bench fixes the gate at {@code gen.open} and is therefore
     *  restarted with the changed run. */
    public GeneratorRun withChannels(OutputChannels channels) {
        return new GeneratorRun(form, frequencyHz, amplitudeVrmsLeft, amplitudeVrmsRight,
                sampleRate, bitDepth, ditherBitsLeft, ditherBitsRight,
                dacFsVoltageAmplLeft, dacFsVoltageAmplRight,
                channels, requireGrantedRate, sweep);
    }

    /** The same run with the sweep's start frequency edited - how a LIVE band
     *  change stays in the stored run, so a later restart (a bench lane-gate
     *  reopen above all) replays the band the operator is on, not the one the
     *  session began with.  No-op without a sweep. */
    public GeneratorRun withSweepStart(double hz) {
        if (sweep == null) return this;
        return new GeneratorRun(form, frequencyHz, amplitudeVrmsLeft, amplitudeVrmsRight,
                sampleRate, bitDepth, ditherBitsLeft, ditherBitsRight,
                dacFsVoltageAmplLeft, dacFsVoltageAmplRight,
                channels,
                requireGrantedRate,
                new SweepSpec(hz, sweep.stopHz(), sweep.durationSamples(),
                        sweep.leadInSamples(), sweep.fadeInSamples(),
                        sweep.fadeOutSamples(), sweep.loop()));
    }

    /** The stop-frequency twin of {@link #withSweepStart}. */
    public GeneratorRun withSweepStop(double hz) {
        if (sweep == null) return this;
        return new GeneratorRun(form, frequencyHz, amplitudeVrmsLeft, amplitudeVrmsRight,
                sampleRate, bitDepth, ditherBitsLeft, ditherBitsRight,
                dacFsVoltageAmplLeft, dacFsVoltageAmplRight,
                channels,
                requireGrantedRate,
                new SweepSpec(sweep.startHz(), hz, sweep.durationSamples(),
                        sweep.leadInSamples(), sweep.fadeInSamples(),
                        sweep.fadeOutSamples(), sweep.loop()));
    }
}
