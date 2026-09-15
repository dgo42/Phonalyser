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

import java.util.Arrays;

import org.edgo.audio.measure.dsp.FreqRespCalHelper;
import org.edgo.audio.measure.dsp.FreqRespCalibration;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.enums.OutputChannels;
import org.edgo.audio.measure.sound.StereoSamples;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * ONE probe of the notch filter's phase at the measured fundamental - the
 * measurement a long coherent FFT average behind a notch needs to keep its
 * time base free of the notch's thermal drift.
 *
 * <p><b>What a probe is.</b>  A short log-sweep session over a narrow band
 * around the fundamental, deconvolved into the filter's complex response
 * H(f), from which ONE number is read: the phase at the fundamental with the
 * band's linear phase (the transport delay and whatever constant offset the
 * session happens to sit at) removed.  That number is the probe's
 * <em>residual</em>.  A residual on its own has no absolute meaning - the
 * de-trend throws the absolute reference away on purpose, because only the
 * CHANGE matters: the caller keeps the residual of the collection's first
 * probe and subtracts it from every later one, and that difference is the
 * fundamental's phase excess the accumulator has to be told about.  A
 * measurement run is one continuous FFT recording; the probe knows nothing
 * about it and holds no state between probes.
 *
 * <p><b>Why the phase and not the magnitude.</b>  At the centre of a deep
 * notch the phase runs about -160 degrees per hertz of centre shift, while
 * the magnitude near the null is flat and noisy.  The notch's centre wanders
 * with its own temperature, so the fundamental (and only the fundamental -
 * the harmonics sit far up the skirt where the same shift is worth
 * thousandths of a degree) acquires a slowly moving phase that the
 * accumulator's tracking loop otherwise misreads as a time slip.
 *
 * <p><b>The sequence.</b>  Open a {@link NotchSweepEngine} session on the
 * configured converters; let the ring fill with one whole loop period; grab
 * three periods, each a whole period after the one before it so no two share
 * a sample; deconvolve each grabbed period of the analysed channel against
 * the session's own reference with the Savitzky-Golay output smoothing OFF
 * (its fixed window rounds the bottom off a deep narrow null); average the
 * three as COMPLEX responses; unwrap, de-trend and read the phase at the
 * fundamental.  Everything - the engine open included - happens inside a
 * wall-clock budget; a probe that runs out of it, or whose engine refuses to
 * start, is abandoned with a warning and returns {@code null}, and the
 * caller simply keeps the phase it last had.
 *
 * <p><b>Threading and the caller's duties.</b>  {@link #run} blocks its
 * caller for up to {@link #BUDGET_SEC} seconds, touches no widget and
 * publishes nothing, so it belongs on a worker thread.  The session claims
 * the output device for its own sweep lane: the caller silences the
 * generator's tone before the call and restores it after, and re-anchors
 * whatever it had streaming.  An interrupt (a recording stopped while a
 * probe runs) ends the probe and closes the session.
 */
@Log4j2
@RequiredArgsConstructor
public final class NotchPhaseProbe {

    /** Half-width of the probe band as a frequency RATIO: the response is read
     *  from f0/1.1 to f0*1.1.  Wide enough that the notch's skirts, where the
     *  phase is smooth, carry the de-trend line, and narrow enough that the
     *  line is a good model of everything but the notch itself. */
    private static final double BAND_RATIO = 1.1;
    /** Target duration of one sweep loop.  2.7 s puts the deconvolution's bin
     *  spacing near 0.37 Hz, so even a band around a few hundred hertz holds
     *  hundreds of bins; three of these periods plus the settle period still
     *  fit inside {@link #BUDGET_SEC}. */
    private static final double LOOP_PERIOD_SEC = 2.7;
    /** Per-side Hann fade on the loop seam, in seconds.  The SAME fade shapes
     *  the deconvolution reference (it is passed into the deconvolution), so
     *  the seam's leakage cancels in the Y/X division; a mismatch between the
     *  two sites re-creates exactly the ripple the fade removes. */
    private static final double FADE_SEC = 0.025;
    /** Periods averaged into one probe.  Three independent windows average
     *  the capture noise down without stretching the probe far enough for the
     *  notch to drift measurably during it. */
    private static final int GRABS = 3;
    /** Wall-clock budget for the WHOLE probe, engine open included.  The
     *  measurement itself costs about four loop periods (settle plus three
     *  spaced grabs) plus the deconvolutions; the rest is headroom for a
     *  device open that has to travel to a server. */
    private static final double BUDGET_SEC = 15.0;
    /** Bins at each band edge whose mean phase anchors the de-trend line.
     *  More than one, because a single outermost bin carries the full
     *  per-bin noise straight into the line's slope. */
    private static final int EDGE_BINS = 5;
    /** Re-check cadence while the ring fills toward the next grab. */
    private static final long POLL_MS = 20L;
    /** Point cap handed to the output-grid builder: none.  The builder falls
     *  back to a log grid when the band holds more bins than the cap, and a
     *  grid off the bin centres is read through a complex interpolation
     *  BETWEEN two bins - a phase-sensitive step this measurement has no
     *  reason to pay. */
    private static final int NO_POINT_CAP = Integer.MAX_VALUE;
    private static final double NANOS_PER_SEC = 1e9;

    /** The ADC's configured rate in Hz: the clock the loop period is counted
     *  in, the clock every grabbed window arrives on, and the clock the
     *  deconvolution reference is rendered on. */
    private final int captureRateHz;
    /** The DAC's configured rate in Hz - the format the sweep lane is opened
     *  with, independent of the capture rate. */
    private final int playbackRateHz;
    /** The DAC's configured word length. */
    private final int playbackBitDepth;
    /** TPDF dither bits applied to the output before quantisation; 0 off. */
    private final int ditherBits;
    /** DAC full-scale RMS voltage of the left lane - what that lane's
     *  rendering of the drive amplitude divides by. */
    private final double dacFsVrmsLeft;
    /** DAC full-scale RMS voltage of the right lane. */
    private final double dacFsVrmsRight;
    /** ADC full-scale RMS voltage of the ANALYSED channel, which normalises
     *  its deconvolved magnitude to a physical transfer function. */
    private final double adcFsVrms;
    /** Drive level of the probe sweep in V RMS.  The notch is probed at the
     *  level the measurement drives it with, because a filter's centre moves
     *  with the power it dissipates. */
    private final double ampVrms;
    /** Which output lane(s) the probe sweep drives - the same gate the
     *  measurement's stimulus uses, so the probe travels the same path. */
    private final OutputChannels outputChannels;
    /** The analysed capture channel: the one the FFT accumulates, and
     *  therefore the one whose notch phase is read. */
    private final Channel analysedChannel;

    /**
     * One completed probe.
     *
     * @param response   the complex average of the three deconvolved periods
     *                   over the read band, on the deconvolution's own bin
     *                   grid.  Its {@code phaseRad} is WRAPPED into
     *                   (-pi, pi], as the deconvolution hands it back
     * @param residualRad the de-trended phase at the fundamental, in radians.
     *                   Absolute value carries no meaning; the caller
     *                   subtracts the residual of the collection's first
     *                   probe to get the phase excess since then
     */
    public record Result(FreqRespCalibration response, double residualRad) { }

    // -------------------------------------------------------------------------
    // The measurement
    // -------------------------------------------------------------------------

    /**
     * Runs one probe around {@code fundamentalHz} and reads its phase there.
     *
     * @return the probe, or {@code null} when the session refused to start or
     *         the budget ran out - in both cases the failure is logged and
     *         nothing else happens, so the measurement continues on the phase
     *         it already had
     */
    public Result run(double fundamentalHz) {
        // Stamped BEFORE anything is opened: a device open that blocks (a
        // server at the far end) is part of the probe's cost, not free time
        // in front of it.
        long deadline = System.nanoTime() + (long) (BUDGET_SEC * NANOS_PER_SEC);
        NotchSweepEngine engine = new NotchSweepEngine(captureRateHz, playbackRateHz,
                playbackBitDepth, ditherBits);
        try {
            // The loop's geometry is the engine's rule, on the engine's own
            // capture clock: the period that keeps the deconvolution circular,
            // and the widening that keeps the band being read clear of the
            // seam fade.
            int sweepSamples  = engine.loopPeriodSamples(LOOP_PERIOD_SEC);
            int fadeSamples   = (int) Math.round(FADE_SEC * captureRateHz);
            double readLoHz   = fundamentalHz / BAND_RATIO;
            double readHiHz   = fundamentalHz * BAND_RATIO;
            double[] swept    = engine.sweptBand(readLoHz, readHiHz,
                    fadeSamples / (double) sweepSamples);

            engine.start(swept[0], swept[1], ampVrms, dacFsVrmsLeft, dacFsVrmsRight,
                    sweepSamples, fadeSamples, outputChannels);
            double[] freqs = FreqRespCalHelper.binAlignedFreqs(readLoHz, readHiHz,
                    engine.deconvBinHz(sweepSamples), NO_POINT_CAP);

            FreqRespCalibration[] grabs = new FreqRespCalibration[GRABS];
            long[] grabbedAt = new long[GRABS];
            // The first grab waits for one whole period of THIS session's
            // samples: older samples in the shared ring predate the sweep.
            long target = sweepSamples;
            for (int i = 0; i < GRABS; i++) {
                StereoSamples window = grab(engine, sweepSamples, target, deadline);
                if (window == null) {
                    if (log.isWarnEnabled()) {
                        log.warn("Notch phase probe at {} Hz abandoned: the {} s budget "
                                + "ran out after {} of {} periods",
                                fundamentalHz, BUDGET_SEC, i, GRABS);
                    }
                    return null;
                }
                grabbedAt[i] = engine.availableSamples();
                // A WHOLE period further on, so the next window shares no
                // sample with this one - three overlapping views of the same
                // samples would be one measurement wearing three hats.
                target = grabbedAt[i] + sweepSamples;
                grabs[i] = FreqRespCalHelper.computeFromLogSweep(
                        analysedChannel == Channel.L ? window.left() : window.right(),
                        engine.sweepRef(), 0, captureRateHz, freqs, ampVrms, adcFsVrms,
                        fadeSamples, analysedChannel.name(), false);
            }

            FreqRespCalibration response = averageComplex(grabs);
            double residualRad = residualAtHz(response, fundamentalHz);
            if (log.isInfoEnabled()) {
                log.info("Notch phase probe at {} Hz: band {}..{} Hz, period {} samples, "
                        + "{} points, grabs at {} captured samples, residual {} deg",
                        fundamentalHz, readLoHz, readHiHz, sweepSamples, freqs.length,
                        Arrays.toString(grabbedAt), Math.toDegrees(residualRad));
            }
            return new Result(response, residualRad);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            if (log.isWarnEnabled()) {
                log.warn("Notch phase probe at {} Hz interrupted", fundamentalHz);
            }
            return null;
        } catch (Exception ex) {
            if (log.isWarnEnabled()) {
                log.warn("Notch phase probe at {} Hz failed", fundamentalHz, ex);
            }
            return null;
        } finally {
            // Every exit path gives the sweep lane and the shared-capture
            // reference back: a session left open holds the DAC against the
            // measurement the probe exists to serve.
            engine.close();
        }
    }

    /**
     * Waits until the session has captured {@code target} samples and hands
     * out the most recent period, or {@code null} when the budget runs out
     * first.  Spacing the grabs on the CAPTURE clock rather than on the wall
     * clock is what makes them independent: the ring's own sample count is
     * the only measure of how much new signal exists.
     */
    private StereoSamples grab(NotchSweepEngine engine, int sweepSamples,
                               long target, long deadline) throws InterruptedException {
        while (System.nanoTime() < deadline) {
            if (engine.availableSamples() >= target) {
                StereoSamples window = engine.latestPeriod(sweepSamples);
                if (window != null) {
                    return window;
                }
            }
            Thread.sleep(POLL_MS);
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // The math - arrays in, numbers out, no device
    // -------------------------------------------------------------------------

    /**
     * The COMPLEX mean of the grabbed responses, point by point on their
     * common grid.  Complex and not dB-and-degrees: averaging magnitudes in
     * dB or phases in degrees breaks down exactly where this measurement
     * lives - at a deep null, where neighbouring readings straddle the
     * +/-180 degree wrap and their arithmetic mean points the wrong way,
     * while the complex mean lands where the phasors really add.
     *
     * <p>The magnitude of the result is therefore the length of the mean
     * phasor: readings that disagree in phase shrink it, which is the honest
     * answer when three windows do not agree.  The phase comes back WRAPPED,
     * the way the deconvolution itself returns it.
     */
    FreqRespCalibration averageComplex(FreqRespCalibration[] grabs) {
        double[] freqs = grabs[0].freqs;
        int n = freqs.length;
        double[] magLin   = new double[n];
        double[] phaseRad = new double[n];
        for (int i = 0; i < n; i++) {
            double re = 0.0;
            double im = 0.0;
            for (FreqRespCalibration grab : grabs) {
                re += grab.magLin[i] * Math.cos(grab.phaseRad[i]);
                im += grab.magLin[i] * Math.sin(grab.phaseRad[i]);
            }
            re /= grabs.length;
            im /= grabs.length;
            magLin[i]   = Math.hypot(re, im);
            phaseRad[i] = Math.atan2(im, re);
        }
        return new FreqRespCalibration(freqs, magLin, phaseRad);
    }

    /**
     * The de-trended phase of {@code response} at {@code atHz}, in radians.
     *
     * <p>Three steps, in order.  The phase is unwrapped across the band, so
     * the notch's own swing is one continuous curve instead of a sawtooth of
     * +/-180 degree jumps.  A straight line is laid through the mean phase of
     * the outermost {@link #EDGE_BINS} bins at each edge, at those bins' mean
     * frequency - the edges sit on the notch's smooth skirts, where the
     * response is all transport delay and constant offset, so that line IS
     * the part of the phase that says nothing about the notch.  The reading
     * is the unwrapped phase at {@code atHz}, linearly interpolated between
     * the two grid points around it, minus that line.
     *
     * <p>Two consequences worth naming.  The reading does not depend on where
     * the unwrap started (a constant on the whole curve moves both edge means
     * and the line by the same constant), which is what lets two probes taken
     * minutes apart be compared at all.  And it does not depend on the
     * session's transport delay, which is a straight line in frequency and
     * goes out with the trend - so a grab at a different loop phase, or a
     * different sub-sample delay estimate, reads the same.
     */
    double residualAtHz(FreqRespCalibration response, double atHz) {
        double[] freqs = response.freqs;
        double[] phase = unwrapped(response.phaseRad);
        int n = freqs.length;
        int edge = Math.min(EDGE_BINS, n / 2);

        double loFreq  = mean(freqs, 0, edge);
        double loPhase = mean(phase, 0, edge);
        double hiFreq  = mean(freqs, n - edge, n);
        double hiPhase = mean(phase, n - edge, n);
        double slope   = (hiPhase - loPhase) / (hiFreq - loFreq);

        return interpolate(freqs, phase, atHz) - (loPhase + slope * (atHz - loFreq));
    }

    /** The phase run as one continuous curve: each step is taken as the
     *  SHORTEST way from the previous point, so a crossing of the +/-180
     *  degree boundary adds no jump of its own. */
    private double[] unwrapped(double[] phaseRad) {
        double[] out = new double[phaseRad.length];
        out[0] = phaseRad[0];
        for (int i = 1; i < phaseRad.length; i++) {
            out[i] = out[i - 1]
                    + Math.IEEEremainder(phaseRad[i] - phaseRad[i - 1], 2.0 * Math.PI);
        }
        return out;
    }

    /** Mean of {@code values} over {@code [from, to)}. */
    private double mean(double[] values, int from, int to) {
        double sum = 0.0;
        for (int i = from; i < to; i++) {
            sum += values[i];
        }
        return sum / (to - from);
    }

    /** {@code ys} at {@code x}, straight-line between the two {@code xs}
     *  around it.  {@code xs} is ascending; the fundamental always lies in
     *  the middle of the probe band, so no end case arises in a real probe. */
    private double interpolate(double[] xs, double[] ys, double x) {
        int hi = 1;
        while (hi < xs.length - 1 && xs[hi] < x) {
            hi++;
        }
        double t = (x - xs[hi - 1]) / (xs[hi] - xs[hi - 1]);
        return ys[hi - 1] + t * (ys[hi] - ys[hi - 1]);
    }
}
