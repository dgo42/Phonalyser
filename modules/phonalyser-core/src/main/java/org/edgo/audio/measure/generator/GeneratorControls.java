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

import org.edgo.audio.measure.enums.GenSignalForm;

/**
 * Everything that can be changed on a generator that is ALREADY emitting -
 * "apply this to whatever is playing now", one method per live parameter.
 *
 * <p>Two things implement it and they are the two places a generator can be:
 * {@link SignalGenerator}, the DDS in this process, and the optional backend
 * capability for a generator that runs somewhere else (the net bridge's, beside
 * the DAC it drives).  The surface was not invented here - both already had
 * exactly these methods with exactly these signatures; naming it is what lets a
 * caller decide ONCE where its generator is instead of at every setter.
 *
 * <p><b>That is the whole point.</b>  A caller that repeats the choice per method
 * has one routing decision per method to forget, and a forgotten one is not a
 * compile error but a setting that silently never leaves the machine.  A method
 * added here, by contrast, has to be implemented on both sides before anything
 * compiles.
 *
 * <p>Nothing here is a request for a value: state comes back the way each
 * implementation reports it (the local DDS is read by whoever owns it, a remote
 * one pushes its state), never by asking a setter what it was told.
 */
public interface GeneratorControls {

    /** Live-swaps the waveform.  A form whose DDS state cannot be hot-swapped
     *  (the sweeps, the two-tone forms) is the caller's business to restart -
     *  each implementation makes that decision for itself. */
    void setForm(GenSignalForm form);

    /** The NOMINAL tone frequency in Hz - what the operator asked for.  The
     *  frequency-lock loop's absolute correction is a TRIM and never this: an
     *  implementation that keeps the two apart can undo the trim without losing
     *  what was typed. */
    void setFrequency(double hz);

    /** The second tone's frequency in Hz, for the two-tone waveforms. */
    void setDualToneFrequency2(double hz);

    /** The two tones' amplitude percentages; the combined signal keeps the
     *  requested V RMS. */
    void setDualToneAmplitudes(double amp1Pct, double amp2Pct);

    /** The two lanes' amplitudes, volts RMS each - always the pair, so the two
     *  lanes never sit at a transient in-between state; a single-amplitude
     *  driver passes its one value twice. */
    void setAmplitudesVrms(double leftVrms, double rightVrms);

    /** The two lanes' DAC full-scale peak voltages, against which each lane's
     *  own level is computed.  A far-away generator converts against its own
     *  card and ignores them. */
    void setDacFsVoltageAmpls(double leftAmpl, double rightAmpl);

    void setRectangleDuty(double dutyFrac);

    void setTriangleDuty(double dutyFrac);

    void setSweepFreqStart(double hz);

    void setSweepFreqEnd(double hz);

    void setSweepDurationSamples(int samples);

    void setSweepFadeInSamples(int samples);

    void setSweepFadeOutSamples(int samples);

    void setSweepLoop(boolean loop);

    /** Hot-applies harmonic predistortion and switches to compensated sine -
     *  the predistortion wizard's per-round apply, with no audio restart. */
    void applyCompensation(double[] ampRatios, int[] hNums, double[] phiInits);

    /** The two-tone counterpart of {@link #applyCompensation}: one correction
     *  per {@code a·f₁ + b·f₂} intermodulation product. */
    void applyDualToneCompensation(double[] ampRatios, int[] aCoef, int[] bCoef,
            double[] phiInits);

    /** Changes only the named harmonics of the running tone's correction table
     *  - one or more entries, the arrays parallel as for
     *  {@link #applyCompensation} - and leaves every other entry as it is.  The
     *  generator fades each named line from the magnitude and phase it renders
     *  to the new ones: a step in a line is a glitch on the output and a frame
     *  the analysis rejects as a discontinuity. */
    void applyCompensationLines(double[] ampRatios, int[] hNums, double[] phiInits);

    /** The two-tone counterpart of {@link #applyCompensationLines}: one entry per
     *  named {@code a f1 + b f2} product. */
    void applyDualToneCompensationLines(double[] ampRatios, int[] aCoef, int[] bCoef,
            double[] phiInits);

    /** Drops every correction table and returns a compensated form to its plain
     *  tone. */
    void clearCompensation();

    /** Starts - or re-aims - the PILOTS: two generated lines, far above the
     *  harmonics a measurement protects, whose phases the analysis reads on
     *  every FFT frame as a time-base reference.  {@code hz2} NaN asks for one
     *  line alone.  {@code levelDbv} is ABSOLUTE and carries both lines: each
     *  one's own V RMS against the lane's DAC full scale, independent of what
     *  the main tone is dialled to, so the pilots keep their voltage across
     *  every amplitude change.  A re-aim is phase-continuous for both, because
     *  the lines' phases ARE the measurement: the pilots follow every
     *  correction of the main tone, and a re-aim that restarted a phase would
     *  show up as the very drift the alignment reads.
     *
     *  <p>Every implementor answers this one way or the other, and that is why
     *  it lives here: a pilot that quietly went nowhere would leave the
     *  analysis reading the phase of a bin that carries no line, which is not a
     *  failure anything downstream can tell from a bad measurement.  An
     *  implementation that cannot emit one therefore refuses out loud. */
    void setPilotTones(double hz1, double hz2, double levelDbv);

    /** Stops both pilots.  The main waveform is left exactly as it is - the
     *  pilots never entered its level nor its RMS normalisation. */
    void clearPilotTones();
}
