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

import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.dsp.FreqRespCalHelper;
import org.edgo.audio.measure.dsp.FreqRespCalibration;
import org.edgo.audio.measure.sound.StereoSamples;
import org.edgo.audio.measure.enums.Channel;
import org.edgo.audio.measure.fft.MathUtil;
import org.edgo.audio.measure.generator.SignalGenerator;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Runs one Farina log-sweep Frequency Response measurement: plays a swept
 * sine through the configured output device, captures both ADC channels in
 * a single stereo pass, then performs direct frequency-domain deconvolution
 * {@code H = Y/X} on each channel in parallel via
 * {@link FreqRespCalHelper#computeFromLogSweep}.  Optionally divides each
 * channel by the matching channel of the calibration in
 * {@code FreqRespCorrectionStore}.
 *
 * <p>Three pieces of cross-cutting state are pluggable so the analyzer
 * stays unit-testable:
 *
 * <ul>
 *   <li>{@link StereoCaptureProvider} - how the sweep/capture round-trip
 *       runs.  Production uses {@link StereoCaptureProvider#real()}; tests
 *       inject a stub that returns synthetic stereo samples.</li>
 *   <li>{@link ProgressCallback} - optional listener for sub-step progress.</li>
 *   <li>{@link Cancellable} - optional cooperative cancellation token.</li>
 * </ul>
 */
@Log4j2
public final class FreqRespAnalyzer {

    /** Compile-time switch between log- and linear-spaced output grids.
     *  {@code true}  -> N geometrically-spaced points from startHz to stopHz
     *                  (good readout density across decades; default).
     *  {@code false} -> N evenly-spaced points; step =
     *                  {@code (stopHz - startHz) / (N - 1)}.  Useful when
     *                  downstream consumers want bins at integer Hz. */
    private static final boolean USE_LOG_GRID = true;

    private final FreqRespAnalyzerConfig cfg;

    public FreqRespAnalyzer(FreqRespAnalyzerConfig cfg) {
        this.cfg = cfg;
    }

    /**
     * Executes the stereo sweep + deconvolution pipeline.  Both callbacks
     * are optional; pass {@code null} when you don't need progress or
     * cancellation.
     */
    public StereoFreqRespResult run(ProgressCallback progress, Cancellable cancel) throws Exception {
        validate();

        // ONE sweep, TWO clocks.  The chirp is a stretch of time - lead-in
        // silence, then the sweep - and each converter counts that same time in
        // its own configured samples: the playback counts describe what leaves
        // the DAC, the record counts describe what the ADC delivered and
        // everything read off it (the deconvolution, its reference, the bin
        // grid, the result's Nyquist).  Deriving both from the SECONDS keeps
        // them equal in time whatever the rate pair is; the two roundings can
        // differ by less than one sample, which the deconvolution's own delay
        // correction absorbs.
        int      playSweepSamples  = (int) Math.round(cfg.getDurationSec() * cfg.getPlaybackRateHz());
        int      playLeadInSamples = (int) Math.round(cfg.getLeadInSec()   * cfg.getPlaybackRateHz());
        int      recSweepSamples   = (int) Math.round(cfg.getDurationSec() * cfg.getCaptureRateHz());
        int      recLeadInSamples  = (int) Math.round(cfg.getLeadInSec()   * cfg.getCaptureRateHz());
        // The record's length is a duration, so neither clock owns it: lead-in
        // plus sweep plus a tail that lets the last swept frequency and the
        // room/DUT ring-out arrive before the capture stops.
        int      durationSec  = (int) Math.ceil(cfg.getLeadInSec() + cfg.getDurationSec()
                + FreqRespConstants.SWEEP_MIN_SEC);

        // The band that is EMITTED, wider at both ends than the one that is
        // read: the Tukey fade takes the stimulus to zero over the first and
        // last stretch of the sweep and its leakage reaches further still, so
        // the outermost part of a swept band is measured on a stimulus on its
        // way out - the wobble at the ends of a deep trace.  Sweeping past the
        // configured band on both sides leaves that stretch outside everything
        // that is read: the grid, the result's parameters, the saved file and
        // the view all keep the configured band, below and further down.
        // Clamped to what the pair of converters can carry, which is also the
        // limit validate() already refuses a configured stop above.  The same
        // rule the capture provider played the chirp by - the reference below
        // has to be that chirp and nothing else.
        double[] swept = FreqRespConstants.sweptBandHz(cfg.getStartHz(), cfg.getStopHz(),
                cfg.getCaptureRateHz(), cfg.getPlaybackRateHz(), cfg.getSweepBandMargin());
        double sweptStartHz = swept[0];
        double sweptStopHz  = swept[1];

        log.info("FreqResp analyzer: {} Hz -> {} Hz read, {} Hz -> {} Hz swept, "
                        + "{} {}-spaced points, sweep {} s, lead-in {} s, "
                        + "DAC {} Hz/{} bit, ADC {} Hz",
                String.format(Locale.US, "%.3f", cfg.getStartHz()),
                String.format(Locale.US, "%.3f", cfg.getStopHz()),
                String.format(Locale.US, "%.3f", sweptStartHz),
                String.format(Locale.US, "%.3f", sweptStopHz),
                cfg.getSweepPoints(),
                USE_LOG_GRID ? "log" : "linear",
                String.format(Locale.US, "%.3f", cfg.getDurationSec()),
                String.format(Locale.US, "%.3f", cfg.getLeadInSec()),
                cfg.getPlaybackRateHz(), cfg.getPlaybackBitDepth(), cfg.getCaptureRateHz());

        reportProgress(progress, 0.0, "Preparing sweep");
        SignalGenerator gen = new SignalGenerator(
                sweptStartHz, sweptStopHz, playSweepSamples, playLeadInSamples,
                cfg.getPlaybackRateHz(), cfg.getAmplitudeVrms(), cfg.getAmplitudeVrms(),
                cfg.getDacFsVoltageRmsLeft(), cfg.getDacFsVoltageRmsRight());
        // One-shot sweep: emit silence after the buffer ends instead of looping
        // a second cycle into the capture window (which would alias into the
        // deconvolution and pollute the response).
        //
        // Hann fade-in/fade-out on the sweep boundaries (Tukey window)
        // suppresses the 1/T spectral-leakage ripple in the deconvolved
        // H(f).  The SAME fade must be applied to the X reference inside
        // computeFromLogSweep - otherwise Y and X carry mismatched boundary
        // shapes and the leakage stays.  It is a FRACTION of the sweep, so the
        // two counts below are the same fade in seconds on the two clocks.
        int playFadeSamples = FreqRespCalHelper.sweepFadeSamples(playSweepSamples);
        int recFadeSamples  = FreqRespCalHelper.sweepFadeSamples(recSweepSamples);
        gen.setSweepParams(false, playFadeSamples, playFadeSamples);

        checkCancel(cancel);
        reportProgress(progress, 0.05, "Running sweep");
        StereoSamples rec = cfg.getStereoCaptureProvider().captureWithProgress(
                gen, cfg.getOutDevice(), cfg.getInDevice(),
                cfg.getCaptureRateHz(), cfg.getPlaybackRateHz(), cfg.getPlaybackBitDepth(),
                cfg.getDitherBits(),
                cfg.getOutputChannels(),
                durationSec,
                cancel == null ? null : cancel::isCancelled,
                cfg.getCaptureProgress());

        if (cfg.getRawCaptureListener() != null) {
            cfg.getRawCaptureListener().onRawCapture(rec);
        }

        // Rail check on the raw capture - this is the one point every sweep
        // passes through, local or remote, and the last one that still sees
        // samples: after the deconvolution a clipped sweep looks like a
        // perfectly plausible (but wrong) transfer function.
        double rawPeakLin = rawPeak(rec);

        // Output grid sampled at the deconvolution's FFT bin centres so each
        // bin is read with fractional offset 0 - no phase-sensitive complex
        // interpolation between bins (which combs the trace).  Built from the
        // ACTUAL capture length so binHz matches computeFromLogSweep's
        // nextPow2(...) FFT; a band with more bins than sweepPoints (wide band
        // / fine grid) falls back to a log grid capped at sweepPoints.
        double[] freqs;
        if (USE_LOG_GRID) {
            int deconvM = MathUtil.nextPow2(Math.max(rec.left().length,
                    recLeadInSamples + recSweepSamples));
            double binHz = cfg.getCaptureRateHz() / (double) deconvM;
            freqs = FreqRespCalHelper.binAlignedFreqs(
                    cfg.getStartHz(), cfg.getStopHz(), binHz, cfg.getSweepPoints());
        } else {
            freqs = buildLinearFreqs();
        }

        checkCancel(cancel);
        reportProgress(progress, 0.50, "Computing transfer function (L + R parallel)");
        // The deconvolution reference X(t): the SAME chirp, rendered on the
        // CAPTURE clock.  It divides a recording that came off the ADC, and
        // computeFromLogSweep sizes one transform over the longer of the two -
        // so a reference on the DAC's faster clock would be a different number
        // of samples for the same seconds, zero-pad the recording inside that
        // transform, and divide the response by a chirp that sweeps at another
        // rate.  The buffer is unit amplitude; the fade is applied inside.
        // The SWEPT band again, not the configured one: X is the model of what
        // was actually played, and a reference over a different band would
        // divide the recording by a chirp that sweeps at another rate.
        double[] sweepRef = new SignalGenerator(
                sweptStartHz, sweptStopHz, recSweepSamples, recLeadInSamples,
                cfg.getCaptureRateHz(), cfg.getAmplitudeVrms(), cfg.getAmplitudeVrms(),
                cfg.getDacFsVoltageRmsLeft(), cfg.getDacFsVoltageRmsRight())
                .getLogSweepBuffer();
        // Parallel deconv: both channels share inputs (sweepRef, leadIn,
        // capture rate, freqs) but compute independently, so they run on
        // separate CompletableFutures.
        // No output smoothing: the bin-aligned grid already gives a
        // leakage-free trace, and a smoothing window would round the bottom
        // off deep, narrow features (e.g. a notch null read shallow).
        FreqRespCalibration calL;
        FreqRespCalibration calR;
        Channel combined = cfg.getCombinedMaster();
        if (combined != null) {
            // Combined: the slave's record is zeros, so the master alone is
            // deconvolved - against its own ADC full scale, the level the
            // combination keeps - and its response stands for both channels.
            boolean leftMaster = combined == Channel.L;
            FreqRespCalibration cal = FreqRespCalHelper.computeFromLogSweep(
                    leftMaster ? rec.left() : rec.right(), sweepRef, recLeadInSamples,
                    cfg.getCaptureRateHz(), freqs, cfg.getAmplitudeVrms(),
                    leftMaster ? cfg.getAdcFsVoltageRmsLeft() : cfg.getAdcFsVoltageRmsRight(),
                    recFadeSamples, leftMaster ? "L" : "R");
            calL = cal;
            calR = cal;
        } else {
            CompletableFuture<FreqRespCalibration> calLFut = CompletableFuture.supplyAsync(
                    () -> FreqRespCalHelper.computeFromLogSweep(
                            rec.left(), sweepRef, recLeadInSamples,
                            cfg.getCaptureRateHz(), freqs, cfg.getAmplitudeVrms(),
                            cfg.getAdcFsVoltageRmsLeft(), recFadeSamples, "L"));
            CompletableFuture<FreqRespCalibration> calRFut = CompletableFuture.supplyAsync(
                    () -> FreqRespCalHelper.computeFromLogSweep(
                            rec.right(), sweepRef, recLeadInSamples,
                            cfg.getCaptureRateHz(), freqs, cfg.getAmplitudeVrms(),
                            cfg.getAdcFsVoltageRmsRight(), recFadeSamples, "R"));
            calL = awaitOrFail(calLFut);
            calR = awaitOrFail(calRFut);
        }

        // Loaded calibration is no longer divided in here - the result
        // carries the raw deconvolution.  Calibration is applied only
        // when the curve is rendered ({@code FreqRespView.applyCurrentCalibration})
        // and at save time, so swapping the loaded calibration retraces
        // the existing measurement without forcing a re-sweep.
        reportProgress(progress, 1.0, "Done");

        FreqRespSweepParams params = new FreqRespSweepParams(
                cfg.getStartHz(), cfg.getStopHz(), cfg.getSweepPoints(),
                cfg.getDurationSec(), cfg.getLeadInSec(),
                cfg.getAmplitudeVrms(), cfg.getDitherBits());

        // The result carries the CAPTURE rate: it is the measurement's own
        // Nyquist, the one every consumer draws and saves the trace against.
        FreqRespResult left  = new FreqRespResult(
                Channel.L, cfg.getCaptureRateHz(),
                calL.freqs, calL.magLin, calL.phaseRad,
                params, null, false);
        FreqRespResult right = new FreqRespResult(
                Channel.R, cfg.getCaptureRateHz(),
                calR.freqs, calR.magLin, calR.phaseRad,
                params, null, false);
        return new StereoFreqRespResult(left, right, rawPeakLin);
    }

    /** Largest absolute sample in the capture across both channels, on the
     *  normalised {@code [-1, +1]} scale the capture path delivers.  One pass
     *  over the two arrays; the deconvolution's own peak search runs on the
     *  impulse response, not on the recording, so there is nothing to reuse. */
    private double rawPeak(StereoSamples rec) {
        double peak = 0.0;
        for (double v : rec.left())  peak = Math.max(peak, Math.abs(v));
        for (double v : rec.right()) peak = Math.max(peak, Math.abs(v));
        return peak;
    }

    private void validate() {
        if (cfg.getOutDevice() == null)              throw new IllegalArgumentException("outDevice is required");
        if (cfg.getInDevice()  == null)              throw new IllegalArgumentException("inDevice is required");
        if (cfg.getCaptureRateHz()  <= 0)            throw new IllegalArgumentException("captureRateHz must be > 0");
        if (cfg.getPlaybackRateHz() <= 0)            throw new IllegalArgumentException("playbackRateHz must be > 0");
        if (cfg.getPlaybackBitDepth() != 8 && cfg.getPlaybackBitDepth() != 16
                && cfg.getPlaybackBitDepth() != 24 && cfg.getPlaybackBitDepth() != 32) {
            throw new IllegalArgumentException("playbackBitDepth must be 8/16/24/32");
        }
        if (cfg.getStartHz() <= 0.0 || cfg.getStopHz() <= cfg.getStartHz()) {
            throw new IllegalArgumentException("startHz must be > 0 and < stopHz");
        }
        // The band has to be BOTH emittable and observable.  Above the DAC's
        // Nyquist the chirp folds back on the way out; above the ADC's it folds
        // back on the way in - and either way the deconvolution reference, which
        // is rendered from the asked band and never folds, divides a signal that
        // did.  The whole trace then comes out wrong while still looking
        // plausible, because the impulse-response peak that sets the delay
        // correction is computed from that same polluted division.  Refused, with
        // both configured rates named, rather than drawn.
        double bandCeilingHz = FreqRespConstants.bandCeilingHz(
                cfg.getCaptureRateHz(), cfg.getPlaybackRateHz());
        if (cfg.getStopHz() > bandCeilingHz) {
            throw new IllegalArgumentException("stopHz " + cfg.getStopHz() + " Hz is above "
                    + bandCeilingHz + " Hz, half the lower of the configured "
                    + cfg.getPlaybackRateHz() + " Hz output and " + cfg.getCaptureRateHz()
                    + " Hz input rates - the sweep would alias and the measurement would be "
                    + "wrong everywhere, not just at the top");
        }
        if (cfg.getSweepPoints() < 4)                throw new IllegalArgumentException("sweepPoints must be >= 4");
        if (cfg.getSweepBandMargin() < 1.0)          throw new IllegalArgumentException("sweepBandMargin must be >= 1.0");
        if (cfg.getDurationSec() < FreqRespConstants.SWEEP_MIN_SEC) {
            throw new IllegalArgumentException("durationSec must be >= " + FreqRespConstants.SWEEP_MIN_SEC);
        }
        if (cfg.getLeadInSec() < FreqRespCalHelper.OFFSET_LEAD_IN_SEC) {
            throw new IllegalArgumentException("leadInSec must be >= " + FreqRespCalHelper.OFFSET_LEAD_IN_SEC);
        }
        if (cfg.getAmplitudeVrms() <= 0.0)           throw new IllegalArgumentException("amplitudeVrms must be > 0");
        if (cfg.getStereoCaptureProvider() == null)  throw new IllegalArgumentException("stereoCaptureProvider is required");
    }

    /** Linearly-spaced output grid: N evenly-spaced points from startHz
     *  to stopHz inclusive.  Step = (stopHz - startHz) / (N - 1). */
    private double[] buildLinearFreqs() {
        int n = cfg.getSweepPoints();
        double start = cfg.getStartHz();
        double stop  = cfg.getStopHz();
        double step  = (stop - start) / (n - 1);
        double[] freqs = new double[n];
        for (int i = 0; i < n; i++) {
            freqs[i] = start + i * step;
        }
        return freqs;
    }

    /** Awaits a CompletableFuture, unwrapping any wrapped exception so the
     *  analyzer's caller sees the original cause instead of an
     *  ExecutionException. */
    private FreqRespCalibration awaitOrFail(CompletableFuture<FreqRespCalibration> fut) throws Exception {
        try {
            return fut.get();
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof Exception ex) throw ex;
            if (cause instanceof Error err)    throw err;
            throw ee;
        }
    }

    private void reportProgress(ProgressCallback progress, double fraction, String message) {
        if (progress != null) progress.onProgress(fraction, message);
    }

    private void checkCancel(Cancellable cancel) throws InterruptedException {
        if (cancel != null && cancel.isCancelled()) {
            throw new InterruptedException("Frequency response measurement cancelled");
        }
    }
}
