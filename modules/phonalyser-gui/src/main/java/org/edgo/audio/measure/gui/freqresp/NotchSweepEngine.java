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

package org.edgo.audio.measure.gui.freqresp;

import org.edgo.audio.measure.sound.StereoSamples;
import org.edgo.audio.measure.dsp.FreqRespCalHelper;
import org.edgo.audio.measure.enums.GenSignalForm;
import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.fft.MathUtil;
import org.edgo.audio.measure.generator.SignalGenerator;
import org.edgo.audio.measure.gui.sound.GeneratorLane;
import org.edgo.audio.measure.gui.sound.GeneratorRun;
import org.edgo.audio.measure.gui.sound.PlaybackLane;
import org.edgo.audio.measure.gui.sound.PlaybackStateEnum;
import org.edgo.audio.measure.gui.sound.SharedCapture;
import org.edgo.audio.measure.gui.sound.SignalBufferReader;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * Self-contained CONTINUOUS play+capture for the Tune-notch wizard's live sweep
 * loop.  The playback lane and the capture are opened ONCE in {@link #start}
 * and held for the whole wizard session: a {@link PlaybackLane} plays a
 * <em>looping</em> Farina log-sweep (Hann-faded at each cycle seam) while the
 * capture streams both ADC channels into the SHARED ring buffer - the one
 * connection between a capture backend and every consumer, so the engine keeps
 * no private duplicate ring of its own, never stopping until {@link #close}.
 *
 * <p>This is the speed win over a per-sweep open/close engine: there is no
 * per-sweep ~0.59 s device open/close overhead and no ~0.86 s real-time
 * recording wait - the wizard simply grabs the most recent one-period window
 * from the ring whenever it wants a fresh result (≈10 updates/s).  It also
 * sidesteps the known "second sweep captures zeros" bug: that came from
 * stop->replay on a held-open line, but a line that streams continuously is
 * never restarted.
 *
 * <p>No alignment of the grabbed window to the sweep-cycle start is needed -
 * but ONLY because the wizard makes the loop period a power of two AT THE
 * CAPTURE CLOCK, so {@code computeFromLogSweep}'s {@code nextPow2(period)} FFT
 * is exactly one period: a CIRCULAR transform.  A window grabbed at any loop
 * phase is then just a circular shift, which leaves the magnitude
 * {@code |H(f)|} unchanged.  (With a non-power-of-two period the FFT would be
 * zero-padded/linear, an unaligned window would wrap the period across the
 * buffer, and every bin would corrupt into a random spike.)
 *
 * <p><b>The two converters run their own configured formats.</b>  The DAC is
 * opened on the output rate and word length, the ADC delivers the input rate,
 * and the two need not be equal - so the loop is described as a DURATION and
 * each side counts it in its own samples: the period is the power of two at the
 * capture clock, the playback buffer is that same duration in output samples,
 * and the deconvolution reference is rendered at the capture clock because that
 * is the clock the window it divides came off.
 */
@Log4j2
public final class NotchSweepEngine implements AutoCloseable {

    /** The ADC's configured rate: the shared ring's clock, the clock every
     *  captured window is counted in, and the clock the deconvolution reads
     *  its bins on. */
    private final int       captureRateHz;
    /** The DAC's configured rate - the format the playback lane is OPENED
     *  with, independent of the ADC's. */
    private final int       playbackRateHz;
    /** The DAC's configured word length, likewise the lane's own. */
    private final int       playbackBitDepth;
    private final int       ditherBits;

    // Session state, set up in start() and torn down in close().
    /** The ONE generator abstraction - local rendering or a bench, ITS
     *  decision; this engine only ever describes the loop as a
     *  {@link GeneratorRun} and drives the lane. */
    private final GeneratorLane lane = new GeneratorLane();
    /** This session's cursor over the SHARED ring; null between sessions. */
    private SignalBufferReader reader;
    /** This session's deconvolution reference: ONE loop period of the same
     *  chirp, rendered at the CAPTURE clock.  It has to be sampled there and
     *  not on the DAC's clock, because it is divided into a window that came
     *  off the ADC - the same period is a different number of samples on the
     *  two clocks, and a longer reference would zero-pad the captured window
     *  inside the transform and destroy the circular premise.  Volatile: the
     *  sweep worker reads it while a band change re-renders it.  Null between
     *  sessions. */
    private volatile SignalGenerator reference;
    /** The ring's write position when this session started or the stimulus
     *  level last changed - fresh samples are counted from here (the ring may
     *  have been running for another consumer long before).  Volatile: the
     *  sweep worker reads it while {@link #setAmplitude} rewinds it from the
     *  amplitude field's listener. */
    private volatile long sessionStart;

    /** The lane gate in force - the one the open ACCEPTED, so a caller that has
     *  to put its own control back where the bench really is can read it (see
     *  {@link #setOutputChannels}).  The looping sweep itself is the LANE's
     *  stored run - the lane replays it whole on a re-gate reopen, band edits
     *  included. */
    @Getter
    private OutputChannels  loopChannels;

    public NotchSweepEngine(int captureRateHz, int playbackRateHz, int playbackBitDepth,
                            int ditherBits) {
        this.captureRateHz    = captureRateHz;
        this.playbackRateHz   = playbackRateHz;
        this.playbackBitDepth = playbackBitDepth;
        this.ditherBits       = ditherBits;
    }

    /**
     * Deconvolution FFT bin spacing (Hz) for a single captured period of
     * {@code sweepSamples} CAPTURE samples - {@code captureRateHz /
     * nextPow2(sweepSamples)}, the ADC's clock because that is the stream the
     * window is grabbed from and the transform is run on.  The
     * wizard matches its output-grid density to this so the bin->grid
     * interpolation never oversamples (oversampling facets the trace into a
     * kink + comb).  Reflects the actual {@code yRec} length the wizard passes
     * to {@link FreqRespCalHelper#computeFromLogSweep} (one period, leadIn 0).
     */
    public double deconvBinHz(int sweepSamples) {
        return captureRateHz / (double) MathUtil.nextPow2(sweepSamples);
    }

    /** The looping sweep's one-period reference X(t) - the buffer
     *  {@link FreqRespCalHelper#computeFromLogSweep} deconvolves against, as
     *  many samples as the captured window and on the same clock.  It is the
     *  model of the identical chirp whichever end emits it: local rendering
     *  and a bench both play the same numbers, and the ADC sees the same
     *  continuous-time signal either way. */
    public double[] sweepRef() {
        SignalGenerator ref = reference;
        return ref == null ? null : ref.getLogSweepBuffer();
    }

    // -------------------------------------------------------------------------
    // Session lifecycle: open once, stream forever, close once
    // -------------------------------------------------------------------------

    /**
     * Acquires the SHARED ring and starts the looping Farina log-sweep through
     * the ONE {@link GeneratorLane} - which renders it here or commands it to
     * a bench; this engine does not know and does not ask.  Returns as soon as
     * the lane reports the loop emitting.
     *
     * <p>{@code sweepSamples} and {@code fadeSamples} are CAPTURE samples - the
     * loop is one duration, and the DAC's own count for it is derived here.
     */
    public void start(double f0, double f1, double ampVrms, double dacFsVrms,
                      int sweepSamples, int fadeSamples,
                      OutputChannels outputChannels) throws Exception {
        loopChannels = outputChannels;
        // Before anything is acquired: the same period on the output clock, or
        // a refusal that costs nothing.
        int playSamples = playbackSamples(sweepSamples);
        int playFade    = (int) Math.round(fadeSamples * (playbackRateHz / (double) captureRateHz));

        try {
            // The SHARED ring is this session's capture - acquired first, so the
            // ring is already taking bytes when the lane's loop starts (that
            // is what the wizard's fill percentage counts, from sessionStart).
            reader = SharedCapture.instance().acquire();
            if (reader == null) {
                throw new IllegalStateException(SharedCapture.instance().getLastStartError());
            }
            sessionStart = reader.getWritePos();
            // The ring carries the GRANTED input rate, and this loop's premise
            // is a power-of-two period AT THE ASKED RATE - refused before any
            // lane opens, so a refusal leaves nothing to give back but the
            // capture reference the catch's close() releases.
            requireGrantedRate(reader.getSampleRate(), captureRateHz, "input stream");

            // The window the deconvolution will divide comes off THIS stream,
            // so its reference is rendered on this clock and with its count.
            reference = new SignalGenerator(f0, f1, sweepSamples, 0,
                    captureRateHz, ampVrms, ampVrms, dacFsVrms, dacFsVrms);

            // Loop ON with Hann fades at each cycle boundary; the SAME fades
            // (the same duration, counted on each side's clock) shape the
            // deconvolution reference.
            GeneratorRun run = new GeneratorRun(GenSignalForm.LOG_SWEEP,
                    0.0, ampVrms, ampVrms,   // both lanes alike
                    playbackRateHz, playbackBitDepth, ditherBits,
                    dacFsVrms, dacFsVrms, outputChannels,
                    true,    // the loop's period is samples on the asked clock
                    new GeneratorRun.SweepSpec(f0, f1, playSamples, 0,
                            playFade, playFade, true));
            PlaybackStateEnum started = lane.start(run);
            if (started != PlaybackStateEnum.STARTED) {
                if (started == PlaybackStateEnum.REMOTE_REFUSED
                        && lane.getLastGrantedRateHz() != playbackRateHz) {
                    requireGrantedRate(lane.getLastGrantedRateHz(), playbackRateHz,
                            "generator lane");
                }
                throw new IllegalStateException("the sweep loop did not start: " + started);
            }
        } catch (Exception ex) {
            // Whatever this start opened goes back.  A start that failed
            // halfway must cost nothing: a lane left behind on a bench holds
            // its DAC against EVERY client until the session dies - the caller
            // here is a wizard that only reports the failure.
            close();
            throw ex;
        }
    }

    /**
     * The granted-rate guard for the remote lane: both {@code capture.open} and
     * {@code gen.open} may answer with a rate other than the one asked for, and
     * this engine's whole premise is that the loop period is a power of two AT
     * THE ASKED CAPTURE RATE and exactly that same duration on the output clock
     * - that is what makes the deconvolution FFT exactly one period and a window
     * grabbed at any loop phase a harmless circular shift.  Off that grid the
     * window wraps the period across the buffer and every bin corrupts, so a
     * mismatch on EITHER side fails the session instead of drawing noise.
     *
     * <p>Strict, with no "0 means the requested rate" reading: the protocol
     * has no such value - the far end answers with a rate - and taking a missing
     * or nonsense one for agreement would run the loop on a clock nothing is
     * counted against.
     */
    private void requireGrantedRate(long granted, int asked, String what) {
        if (granted != asked) {
            throw new IllegalStateException("the bench granted " + granted + " Hz on its "
                    + what + " but the sweep loop was built for " + asked + " Hz - "
                    + "the deconvolution would no longer be circular over one period, so "
                    + "the tuning session is refused rather than shown wrong");
        }
    }

    /**
     * One loop period in OUTPUT samples: the capture-clock period converted to
     * the DAC's clock, so both converters run their own configured rate over the
     * same stretch of time.
     *
     * <p>The conversion has to come out whole.  A fractional output period would
     * make the emitted loop a slightly different duration from the window the
     * deconvolution treats as exactly one period, and the two would walk apart
     * cycle by cycle until the trace is meaningless - so the rate pair is
     * refused, with both settings named, instead of tuning against a drifting
     * reference.
     */
    private int playbackSamples(int captureSamples) {
        long scaled = (long) captureSamples * playbackRateHz;
        if (scaled % captureRateHz != 0) {
            throw new IllegalStateException("one loop period is " + captureSamples
                    + " samples at the configured " + captureRateHz + " Hz input rate, "
                    + "which is not a whole number of samples at the configured "
                    + playbackRateHz + " Hz output rate - the looping sweep and the "
                    + "captured period would drift apart, so the tuning session is "
                    + "refused rather than shown wrong");
        }
        return (int) (scaled / captureRateHz);
    }

    /** Live band change WITHOUT restart: the lane rebuilds the looping buffer
     *  in place AND the deconvolution reference follows on its own clock -
     *  whichever end emits, both halves hear the same band.  A reference left
     *  on the old band would divide the new band's response by the wrong
     *  chirp. */
    public void setBand(double f0, double f1) {
        lane.setSweepFreqStart(f0);
        lane.setSweepFreqEnd(f1);
        SignalGenerator ref = reference;
        if (ref != null) {
            ref.setSweepFreqStart(f0);
            ref.setSweepFreqEnd(f1);
        }
    }

    /** Live amplitude change WITHOUT restart: the new level goes straight to
     *  the running generator.  The session sample counter rewinds so
     *  {@link #latestPeriod} hands out nothing until one full period of
     *  new-level samples exists - the deconvolution divides the whole window
     *  by ONE drive level, and a window spanning the change would draw the
     *  response offset by the level ratio. */
    public void setAmplitude(double ampVrms) {
        lane.setAmplitudesVrms(ampVrms, ampVrms);   // both lanes alike
        SignalBufferReader r = reader;
        if (r != null) sessionStart = r.getWritePos();
    }

    /**
     * Live output-lane change: the gate goes straight to the running playback
     * (honoured by the in-process backends).  No-op after {@link #close} cleared
     * the reference.
     *
     * <p><b>A remote lane is REOPENED instead.</b>  Spec 4.5 fixes the output
     * channels at {@code gen.open} - they belong to the playback line, which the
     * far end opens once - so there is no command that re-gates a running lane,
     * and pushing one would be a setting the operator watched apply and the bench
     * never heard.  The lane is therefore closed and opened again with the new
     * gate, and the same looping sweep is told to it.
     *
     * <p>The capture and the ring are deliberately left running: the stimulus is
     * interrupted for as long as the reopen takes, which is a real gap in the
     * physical signal and not a bookkeeping problem, and the wizard's window
     * heals itself within one period (the deconvolution is circular over exactly
     * one period, so a window that spans the seam is stale for one update at
     * most).
     *
     * <p><b>It blocks and it throws</b>, so the caller keeps it off the display
     * thread - the wizard hands it to the same worker that runs the sweep.  A
     * failure leaves the session with NO lane (the old one was closed to make
     * room), and {@link #getLoopChannels()} then answers the gate that was in
     * force, which is what a control showing the operator's pick has to go back
     * to.
     *
     * @throws IllegalStateException when the bench refuses the new lane - the
     *         close gave its DAC back, so another client may have taken it, and
     *         the rate it grants is checked again.  Raised rather than swallowed:
     *         a silently un-re-gated lane is exactly the defect this reopen
     *         exists to remove, and the tuning session has no stimulus left to
     *         show either way
     */
    public void setOutputChannels(OutputChannels outputChannels) {
        OutputChannels inForce = loopChannels;
        loopChannels = outputChannels;
        lane.setOutputChannels(outputChannels);
        if (lane.getLastStartState() != PlaybackStateEnum.STARTED) {
            // The gate the operator asked for never took effect, so the one this
            // session last ran with is what a control has to go back to showing.
            // (Nothing is EMITTING now - the lane's reopen closed the old gate's
            // lane first - but the session is over either way, and reporting the
            // gate that failed as the one in force would be a second wrong
            // answer on top of the first.)
            loopChannels = inForce;
            throw new IllegalStateException("the bench refused the re-gated lane: "
                    + lane.getLastStartState());
        }
    }

    /** Total samples captured since this session started - drives the settle /
     *  fill percentage.  Counted from {@link #sessionStart}: the shared ring
     *  may have been running for another consumer long before the sweep. */
    public long availableSamples() {
        SignalBufferReader r = reader;
        return r == null ? 0 : Math.max(0, r.getWritePos() - sessionStart);
    }

    /**
     * Copies the most recent {@code n} samples (L and R) from the shared ring
     * into a fresh {@link StereoSamples}.  Returns {@code null} if fewer than
     * {@code n} samples have been captured since this session started (still
     * settling - older samples in the ring predate the sweep).
     */
    public StereoSamples latestPeriod(int n) {
        SignalBufferReader rdr = reader;
        if (rdr == null || rdr.getWritePos() - sessionStart < n) return null;
        double[] l = new double[n];
        double[] r = new double[n];
        int got = rdr.readLatest(n, l, r);
        if (got < n) return null;
        return new StereoSamples(l, r);
    }

    @Override
    public void close() {
        // The lane first - its stop silences whichever end emits (and gives a
        // bench's lane and device lock back) before the capture goes.
        lane.stop();
        reference = null;
        // The shared capture reference goes back last; the device closes only
        // when no other consumer (scope, FFT) still holds it.
        if (reader != null) {
            reader = null;
            SharedCapture.instance().release();
        }
    }
}
