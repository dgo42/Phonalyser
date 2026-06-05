/*
 * Phonalyser — precision audio measurement workbench.
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
import org.edgo.audio.measure.cli.util.FreqRespCalHelper;
import org.edgo.audio.measure.cli.util.FreqRespCalibration;
import org.edgo.audio.measure.cli.util.StereoSamples;
import org.edgo.audio.measure.enums.Channel;
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
 * {@link FreqRespCalibrationStore}.
 *
 * <p>Three pieces of cross-cutting state are pluggable so the analyzer
 * stays unit-testable:
 *
 * <ul>
 *   <li>{@link StereoCaptureProvider} — how the sweep/capture round-trip
 *       runs.  Production uses {@link StereoCaptureProvider#real()}; tests
 *       inject a stub that returns synthetic stereo samples.</li>
 *   <li>{@link ProgressCallback} — optional listener for sub-step progress.</li>
 *   <li>{@link Cancellable} — optional cooperative cancellation token.</li>
 * </ul>
 */
@Log4j2
public final class FreqRespAnalyzer {

    /** Compile-time switch between log- and linear-spaced output grids.
     *  {@code true}  → N geometrically-spaced points from startHz to stopHz
     *                  (good readout density across decades; default).
     *  {@code false} → N evenly-spaced points; step =
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

        double[] freqs        = USE_LOG_GRID ? buildLogSpacedFreqs() : buildLinearFreqs();
        int      sweepSamples = (int) Math.round(cfg.getDurationSec() * cfg.getSampleRate());
        int      leadInSamples = (int) Math.round(cfg.getLeadInSec() * cfg.getSampleRate());
        int      tailSamples  = cfg.getSampleRate() / 2;
        long     totalGenSamples = (long) leadInSamples + sweepSamples + tailSamples;
        int      durationSec  = (int) Math.ceil(totalGenSamples / (double) cfg.getSampleRate());

        log.info("FreqResp analyzer: {} Hz → {} Hz, {} {}-spaced points, sweep {} s, lead-in {} s",
                String.format(Locale.US, "%.3f", cfg.getStartHz()),
                String.format(Locale.US, "%.3f", cfg.getStopHz()),
                cfg.getSweepPoints(),
                USE_LOG_GRID ? "log" : "linear",
                String.format(Locale.US, "%.3f", cfg.getDurationSec()),
                String.format(Locale.US, "%.3f", cfg.getLeadInSec()));

        reportProgress(progress, 0.0, "Preparing sweep");
        SignalGenerator gen = new SignalGenerator(
                cfg.getStartHz(), cfg.getStopHz(), sweepSamples, leadInSamples,
                cfg.getSampleRate(), cfg.getAmplitudeVrms());
        // One-shot sweep: emit silence after the buffer ends instead of looping
        // a second cycle into the capture window (which would alias into the
        // deconvolution and pollute the response).
        //
        // Hann fade-in/fade-out on the sweep boundaries (Tukey window)
        // suppresses the 1/T spectral-leakage ripple in the deconvolved
        // H(f).  The SAME fade length must be applied to the X reference
        // inside computeFromLogSweep — otherwise Y and X carry mismatched
        // boundary shapes and the leakage stays.
        int fadeSamples = FreqRespCalHelper.sweepFadeSamples(sweepSamples);
        gen.setSweepParams(false, fadeSamples, fadeSamples);

        checkCancel(cancel);
        reportProgress(progress, 0.05, "Running sweep");
        StereoSamples rec = cfg.getStereoCaptureProvider().captureWithProgress(
                gen, cfg.getOutDevice(), cfg.getInDevice(),
                cfg.getSampleRate(), cfg.getBitDepth(), cfg.getDitherBits(),
                durationSec,
                cancel == null ? null : cancel::isCancelled,
                cfg.getCaptureProgress());

        if (cfg.getRawCaptureListener() != null) {
            cfg.getRawCaptureListener().onRawCapture(rec);
        }

        checkCancel(cancel);
        reportProgress(progress, 0.50, "Computing transfer function (L + R parallel)");
        // Parallel deconv: both channels share inputs (sweepRef, leadIn,
        // sampleRate, freqs) but compute independently, so they run on
        // separate CompletableFutures.
        float[] sweepRef = gen.getLogSweepBuffer();
        CompletableFuture<FreqRespCalibration> calLFut = CompletableFuture.supplyAsync(
                () -> FreqRespCalHelper.computeFromLogSweep(
                        rec.left(), sweepRef, leadInSamples,
                        cfg.getSampleRate(), freqs, cfg.getAmplitudeVrms(),
                        fadeSamples, "L"));
        CompletableFuture<FreqRespCalibration> calRFut = CompletableFuture.supplyAsync(
                () -> FreqRespCalHelper.computeFromLogSweep(
                        rec.right(), sweepRef, leadInSamples,
                        cfg.getSampleRate(), freqs, cfg.getAmplitudeVrms(),
                        fadeSamples, "R"));
        FreqRespCalibration calL = awaitOrFail(calLFut);
        FreqRespCalibration calR = awaitOrFail(calRFut);

        // Loaded calibration is no longer divided in here — the result
        // carries the raw deconvolution.  Calibration is applied only
        // when the curve is rendered ({@code FreqRespView.applyCurrentCalibration})
        // and at save time, so swapping the loaded calibration retraces
        // the existing measurement without forcing a re-sweep.
        reportProgress(progress, 1.0, "Done");

        FreqRespSweepParams params = new FreqRespSweepParams(
                cfg.getStartHz(), cfg.getStopHz(), cfg.getSweepPoints(),
                cfg.getDurationSec(), cfg.getLeadInSec(),
                cfg.getAmplitudeVrms(), cfg.getDitherBits());

        FreqRespResult left  = new FreqRespResult(
                Channel.L, cfg.getSampleRate(),
                calL.freqs, calL.magLin, calL.phaseRad,
                params, null, false);
        FreqRespResult right = new FreqRespResult(
                Channel.R, cfg.getSampleRate(),
                calR.freqs, calR.magLin, calR.phaseRad,
                params, null, false);
        return new StereoFreqRespResult(left, right);
    }

    private void validate() {
        if (cfg.getOutDevice() == null)              throw new IllegalArgumentException("outDevice is required");
        if (cfg.getInDevice()  == null)              throw new IllegalArgumentException("inDevice is required");
        if (cfg.getSampleRate() <= 0)                throw new IllegalArgumentException("sampleRate must be > 0");
        if (cfg.getBitDepth() != 8 && cfg.getBitDepth() != 16
                && cfg.getBitDepth() != 24 && cfg.getBitDepth() != 32) {
            throw new IllegalArgumentException("bitDepth must be 8/16/24/32");
        }
        if (cfg.getStartHz() <= 0.0 || cfg.getStopHz() <= cfg.getStartHz()) {
            throw new IllegalArgumentException("startHz must be > 0 and < stopHz");
        }
        if (cfg.getSweepPoints() < 4)                throw new IllegalArgumentException("sweepPoints must be >= 4");
        if (cfg.getDurationSec() < 0.5)              throw new IllegalArgumentException("durationSec must be >= 0.5");
        if (cfg.getLeadInSec() < 0.05)               throw new IllegalArgumentException("leadInSec must be >= 0.05");
        if (cfg.getAmplitudeVrms() <= 0.0)           throw new IllegalArgumentException("amplitudeVrms must be > 0");
        if (cfg.getStereoCaptureProvider() == null)  throw new IllegalArgumentException("stereoCaptureProvider is required");
    }

    /** Log-spaced output grid: N points from startHz to stopHz with
     *  geometric spacing.  freqs[0] = startHz, freqs[N-1] = stopHz. */
    private double[] buildLogSpacedFreqs() {
        int n = cfg.getSweepPoints();
        double[] freqs = new double[n];
        double logStart = Math.log(cfg.getStartHz());
        double logEnd   = Math.log(cfg.getStopHz());
        for (int i = 0; i < freqs.length; i++) {
            double t = i / (double) (freqs.length - 1);
            freqs[i] = Math.exp(logStart + (logEnd - logStart) * t);
        }
        return freqs;
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
