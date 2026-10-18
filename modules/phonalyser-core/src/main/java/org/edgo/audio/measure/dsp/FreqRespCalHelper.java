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

package org.edgo.audio.measure.dsp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntToDoubleFunction;

import org.edgo.audio.measure.common.FileVersions;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.fft.ImdResult;
import org.edgo.audio.measure.fft.MathUtil;
import org.edgo.audio.measure.preferences.Preferences;
import org.jtransforms.fft.DoubleFFT_1D;

import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;

@Log4j2
@UtilityClass
public class FreqRespCalHelper {

    /** Per-side Hann fade duration as a fraction of the sweep length.
     *  Equivalent to a Tukey window with {@code α = 2 × this} applied to
     *  the played sweep and the deconvolution reference, suppressing the
     *  start/stop spectral leakage (sinc sidelobes at 1/T period in f).
     *  5% per side ≈ 20 dB suppression while keeping the bulk of the
     *  swept band intact. */
    public static final double SWEEP_FADE_FRACTION_PER_SIDE = 0.05;

    /** Fixed silence at the head of every one-shot sweep record, in seconds,
     *  on which each channel's DC offset is measured (4410 samples at
     *  44.1 kHz).  It precedes whatever lead-in the operator configures on
     *  top of it, so the offset window never shrinks with a short lead-in. */
    public static final double OFFSET_LEAD_IN_SEC = 0.1;

    /** Shared per-tone lobe geometry (robust local floor + data-derived lobe
     *  extent) - also used by the manual-fundamental display, so both agree on
     *  where a tone ends and the noise begins. */
    private static final ToneLobeLift LOBE = new ToneLobeLift();

    /** dB per decade of an AMPLITUDE ratio. */
    private static final double AMPLITUDE_DB_FACTOR = 20.0;
    /** dB per decade of a POWER ratio - a squared magnitude is read in this
     *  domain, so no square root is taken to state a gain. */
    private static final double POWER_DB_FACTOR = 10.0;
    /** Below this linear amplitude a bin's level is clamped instead of
     *  logarithmed, so a bin that cancelled to zero cannot read -infinity. */
    private static final double MIN_LINEAR_AMPLITUDE = 1e-15;
    /** The clamp value for a bin below {@link #MIN_LINEAR_AMPLITUDE}. */
    private static final double FLOOR_DB = -300.0;

    /** Compile-time switch for IR-domain time gating after delay
     *  correction.  When on, the deconvolved impulse response is
     *  windowed to {@link #IR_GATE_LENGTH_SEC} around the main peak
     *  before being re-FFTed; the rest of the IR (mostly noise) is
     *  zeroed.  Net effect on the magnitude trace: smooth ≈20 dB of
     *  residual ripple while keeping every real device feature with
     *  decay time shorter than the gate. */
    private static final boolean USE_IR_GATING = false;

    /** One-sided gate length in seconds.  Frequency resolution after
     *  gating is ≈ 1/this - features narrower than that get smoothed.
     *  50 ms preserves any analog feature with Q · f₀ &lt; 1000 (e.g. a
     *  Twin-T at 1 kHz up to Q ≈ 50). */
    private static final double IR_GATE_LENGTH_SEC = 0.25;

    /** Hann taper at the gate edge as a fraction of the gate length.
     *  Smooths the gate's transition from 1 to 0 to avoid spectral
     *  artefacts a hard cutoff would introduce. */
    private static final double IR_GATE_TAPER_FRAC = 0.1;

    /** Compile-time switch for Savitzky-Golay smoothing.  When on, the
     *  magnitude and phase arrays returned by the deconvolution are
     *  passed through an SG filter of {@link #SAVGOL_WINDOW} points and
     *  {@link #SAVGOL_ORDER} polynomial order.  Suppresses the residual
     *  noise ripple while preserving sharp features better than a
     *  moving average.  Independent of {@link #USE_IR_GATING}; both
     *  can be enabled at once. */
    private static final boolean USE_SAVGOL_FILTER = true;

    /** SG window length in output samples.  Must be odd and
     *  {@code > SAVGOL_ORDER}.  Larger window = smoother but blurs
     *  narrower features.  7 points across a typical 200-point
     *  log-spaced output ≈ 3.5% of a decade ≈ 1/12-octave smoothing. */
    private static final int SAVGOL_WINDOW = 7;

    /** SG polynomial order.  Higher = better feature preservation at
     *  the cost of noise sensitivity.  Order 3 preserves Q≈10 notches;
     *  bump to 5 for Q≈20-50 (Twin-T high-Q active variants). */
    private static final int SAVGOL_ORDER = 3;

    /** Returns the per-side Hann fade length in samples for a sweep of
     *  {@code sweepSamples} samples.  Same value must be set on the
     *  {@code SignalGenerator} (so the DAC plays a windowed sweep) AND
     *  passed to {@link #computeFromLogSweep} (so the reference X is
     *  windowed identically); any divergence between the two re-creates
     *  the leakage ripple the window was meant to suppress. */
    public int sweepFadeSamples(int sweepSamples) {
        return (int) (sweepSamples * SWEEP_FADE_FRACTION_PER_SIDE);
    }

    /** Output frequency grid sampled EXACTLY at the deconvolution's FFT bin
     *  centres ({@code k·binHz}) across {@code [startHz, stopHz]}, so
     *  {@link #computeFromLogSweep} reads each bin with fractional offset 0 -
     *  no phase-sensitive complex interpolation between bins (which facets the
     *  trace into a frame-to-frame comb).  {@code binHz} must be
     *  {@code sampleRate / nextPow2(captureLength)} - the spacing of the FFT
     *  {@code computeFromLogSweep} builds.
     *
     *  <p>When the band spans more than {@code maxPoints} bins (a wide band on
     *  a fine grid) the between-bin wiggle is sub-point anyway and a full bin
     *  grid would explode the point count, so it falls back to {@link
     *  #logSpacedFreqs} capped at {@code maxPoints}. */
    public double[] binAlignedFreqs(double startHz, double stopHz, double binHz, int maxPoints) {
        int k0 = Math.max(1, (int) Math.ceil(startHz / binHz));
        int k1 = (int) Math.floor(stopHz / binHz);
        int n  = k1 - k0 + 1;
        if (n < 2 || n > maxPoints) {
            return logSpacedFreqs(startHz, stopHz, Math.min(maxPoints, Math.max(2, n)));
        }
        double[] freqs = new double[n];
        for (int i = 0; i < n; i++) {
            freqs[i] = (k0 + i) * binHz;
        }
        return freqs;
    }

    /** Log-spaced (geometric) output grid: {@code points} points from
     *  {@code startHz} to {@code stopHz} inclusive. */
    public double[] logSpacedFreqs(double startHz, double stopHz, int points) {
        double[] freqs = new double[points];
        double logStart = Math.log(startHz);
        double logEnd   = Math.log(stopHz);
        for (int i = 0; i < points; i++) {
            double t = i / (double) (points - 1);
            freqs[i] = Math.exp(logStart + (logEnd - logStart) * t);
        }
        return freqs;
    }

    /**
     * Reconstructs the per-frequency filter calibration H(f) from the captured
     * recording of a Farina log-sweep by direct frequency-domain deconvolution:
     * {@code H = Y / X}.  Removes the DAC↔ADC transport delay (linear-phase term
     * located via the impulse-response peak) and normalises magnitude to the DAC
     * drive level so the passband sits at ≈ 1.0.
     *
     *  <p>Every log line is tagged with {@code channelLabel}
     *  (e.g. {@code "L"} / {@code "R"}) so the two parallel deconvolution
     *  threads in {@code FreqRespAnalyzer} produce unambiguous log
     *  output even when their writes interleave.  Pass {@code null} or
     *  empty for no prefix.
     *
     *  <p>{@code fadeSamples} is the per-side Hann fade length applied to
     *  the X reference inside this call - it MUST match the
     *  {@code fadeInSamples}/{@code fadeOutSamples} previously set on
     *  the playing {@code SignalGenerator} (use {@link #sweepFadeSamples}
     *  to compute one value for both sites).  Zero disables the fade and
     *  the reference is used raw. */
    public FreqRespCalibration computeFromLogSweep(
            double[] yRec, double[] sweepRef, int leadInSamples,
            int sampleRate, double[] freqs, double amplitudeVRms, double adcFsVoltageRms,
            int fadeSamples, String channelLabel) {
        return computeFromLogSweep(yRec, sweepRef, leadInSamples, sampleRate, freqs,
                amplitudeVRms, adcFsVoltageRms, fadeSamples, channelLabel, true);
    }

    /** As above, but {@code applySavGol=false} skips the final Savitzky-Golay
     *  output smoothing.  The Tune-notch wizard disables it: its bin-aligned
     *  output grid is coarse (a few Hz per point), so the fixed
     *  {@link #SAVGOL_WINDOW}-point SG window spans ~15-20 Hz and rounds the
     *  bottom off a deep, narrow notch null (it read ≈9 dB shallow).  A dense
     *  main-pane grid keeps SG on, where the window is sub-Hz and harmless. */
    public FreqRespCalibration computeFromLogSweep(
            double[] yRec, double[] sweepRef, int leadInSamples,
            int sampleRate, double[] freqs, double amplitudeVRms, double adcFsVoltageRms,
            int fadeSamples, String channelLabel, boolean applySavGol) {
        final String tag = (channelLabel == null || channelLabel.isEmpty())
                ? "" : "[" + channelLabel + "] ";
        int xLen   = leadInSamples + sweepRef.length;
        int needed = Math.max(yRec.length, xLen);
        int M      = MathUtil.nextPow2(needed);

        // Build the reference X with the same Hann fade-in/fade-out the
        // SignalGenerator applies to its playback (see sweepEnvelope()),
        // so Y and X carry matching boundary shapes and the start/stop
        // leakage cancels in the Y/X division.
        int n  = sweepRef.length;
        int fS = Math.max(0, Math.min(fadeSamples, n / 2));
        double[] xBuf = new double[M];
        for (int i = 0; i < n; i++) {
            double env;
            if (fS > 0 && i < fS) {
                env = 0.5 * (1.0 - Math.cos(Math.PI * i / fS));
            } else if (fS > 0 && i >= n - fS) {
                int back = n - 1 - i;
                env = 0.5 * (1.0 - Math.cos(Math.PI * back / fS));
            } else {
                env = 1.0;
            }
            xBuf[leadInSamples + i] = sweepRef[i] * env;
        }
        // The record begins with the lead-in: the chirp has not started, so
        // those samples carry nothing but the channel's own offset and noise.
        // Their mean over the fixed OFFSET_LEAD_IN_SEC window is the DC offset
        // - an ADC input sitting off centre, a DUT with an output offset - and
        // it comes out of the whole record before the transform.  Left in, a
        // constant over a one-shot record is a step at both ends whose 1/f
        // leakage, divided by the chirp, ripples the whole low end of the
        // trace.  A record without lead-in (a circular one-period window) has
        // nothing to measure it on and needs nothing: there the offset is
        // bin 0 alone, which is skipped.
        double offset = leadInOffset(yRec, leadInSamples, sampleRate);
        double[] yBuf = new double[M];
        for (int i = 0; i < yRec.length; i++) {
            yBuf[i] = yRec[i] - offset;
        }

        DoubleFFT_1D fft = new DoubleFFT_1D(M);
        fft.realForward(xBuf);
        fft.realForward(yBuf);

        int halfBins = M / 2;
        double[] hRe = new double[halfBins + 1];
        double[] hIm = new double[halfBins + 1];
        for (int k = 0; k <= halfBins; k++) {
            double xr, xi, yr, yi;
            if (k == 0)            { xr = xBuf[0]; xi = 0.0; yr = yBuf[0]; yi = 0.0; }
            else if (k == halfBins){ xr = xBuf[1]; xi = 0.0; yr = yBuf[1]; yi = 0.0; }
            else                   { xr = xBuf[2*k]; xi = xBuf[2*k+1];
                                     yr = yBuf[2*k]; yi = yBuf[2*k+1]; }
            double xMag2 = xr*xr + xi*xi;
            if (xMag2 <= 0.0) {
                hRe[k] = 0.0;
                hIm[k] = 0.0;
            } else {
                hRe[k] = (yr*xr + yi*xi) / xMag2;
                hIm[k] = (yi*xr - yr*xi) / xMag2;
            }
        }

        // The transport delay is read from the EXCITED band ALONE.  Outside
        // the swept band the stimulus is nothing while the converter still
        // delivers its own noise, so H = Y/X there is noise divided by nearly
        // zero - orders of magnitude above the real response.  An impulse
        // response built from every bin is therefore owned by that garbage on
        // any sweep that stops short of the converter's edge: its peak lands
        // wherever the noise happened to add up, and the de-rotation below
        // turns EVERY bin by that wrong delay, which is what makes the phase
        // of a narrow sweep ramp or go to noise.  The caller's output grid is
        // the band it swept (every caller builds the grid from its own sweep
        // band), so only the bins strictly inside that span are read.
        //
        // Inside the band H is taken as an ANALYTIC spectrum - the positive
        // frequencies alone, no negative half - so the inverse transform is
        // complex and its magnitude is the impulse's ENVELOPE.  A real
        // band-limited impulse oscillates at its band centre and its crests
        // stand half a carrier cycle apart, which on a narrow band leaves the
        // peak search a choice between crests; the envelope has one maximum,
        // and the sub-sample fit below runs on it.  (The customary factor of
        // two on the positive bins is omitted: it scales the whole envelope
        // and cannot move its peak.)
        double binHz = sampleRate / (double) M;
        int kLo = Math.max(1, (int) Math.ceil(freqs[0] / binHz));
        int kHi = Math.min(halfBins, (int) Math.floor(freqs[freqs.length - 1] / binHz));
        kHi = Math.max(kHi, kLo);       // a grid narrower than one bin keeps that bin
        double[] analytic = new double[2 * M];
        for (int k = kLo; k <= kHi; k++) {
            analytic[2*k]     = hRe[k];
            analytic[2*k + 1] = hIm[k];
        }
        fft.complexInverse(analytic, true);
        int    peakIdx = 0;
        double peakVal = 0.0;
        for (int i = 0; i < M; i++) {
            double v = envelopeAt(analytic, i);
            if (v > peakVal) { peakVal = v; peakIdx = i; }
        }
        double delaySamples = peakIdx;
        if (peakIdx > 0 && peakIdx < M - 1) {
            double yM = envelopeAt(analytic, peakIdx - 1);
            double y0 = peakVal;
            double yP = envelopeAt(analytic, peakIdx + 1);
            double denom = yM - 2.0 * y0 + yP;
            if (denom != 0.0) {
                delaySamples = peakIdx + 0.5 * (yM - yP) / denom;
            }
        }

        for (int k = 0; k <= halfBins; k++) {
            double theta = 2.0 * Math.PI * k * delaySamples / M;
            double c = Math.cos(theta);
            double s = Math.sin(theta);
            double re = hRe[k] * c - hIm[k] * s;
            double im = hRe[k] * s + hIm[k] * c;
            hRe[k] = re;
            hIm[k] = im;
        }

        // IR-domain time gating.  After delay correction, the impulse
        // sits at sample 0 (with the response extending into positive
        // time and, via the circular IFFT, wrapping a bandlimit pre-echo
        // into the highest samples M-1, M-2, ...).  A symmetric Hann-
        // tapered window of half-width IR_GATE_LENGTH_SEC samples keeps
        // both branches; everything outside (noise tail) is zeroed.
        // Re-FFT updates hRe/hIm in place so the downstream
        // bin-to-output interpolation reads the smoothed H(f).
        if (USE_IR_GATING) {
            double[] gatedIr = new double[M];
            gatedIr[0] = hRe[0];
            gatedIr[1] = hRe[halfBins];
            for (int k = 1; k < halfBins; k++) {
                gatedIr[2*k]     = hRe[k];
                gatedIr[2*k + 1] = hIm[k];
            }
            fft.realInverse(gatedIr, true);

            int gateSamples = Math.max(1,
                    Math.min(M / 2, (int) (IR_GATE_LENGTH_SEC * sampleRate)));
            int taperSamples = Math.max(1, (int) (gateSamples * IR_GATE_TAPER_FRAC));
            int flatEnd = Math.max(0, gateSamples - taperSamples);
            for (int i = 0; i < M; i++) {
                int dist = Math.min(i, M - i);     // circular distance from sample 0
                double env;
                if (dist < flatEnd) {
                    env = 1.0;
                } else if (dist < gateSamples) {
                    int taperPos = dist - flatEnd;
                    env = 0.5 * (1.0 + Math.cos(Math.PI * taperPos / taperSamples));
                } else {
                    env = 0.0;
                }
                gatedIr[i] *= env;
            }

            fft.realForward(gatedIr);
            hRe[0]        = gatedIr[0];
            hIm[0]        = 0.0;
            hRe[halfBins] = gatedIr[1];
            hIm[halfBins] = 0.0;
            for (int k = 1; k < halfBins; k++) {
                hRe[k] = gatedIr[2*k];
                hIm[k] = gatedIr[2*k + 1];
            }
            log.info("{}Filter cal IR gate: ±{} samples (±{} ms), {} sample Hann taper",
                    tag,
                    gateSamples,
                    String.format(Locale.US, "%.3f", 1000.0 * gateSamples / sampleRate),
                    taperSamples);
        }

        int nPoints = freqs.length;
        double[] magLin   = new double[nPoints];
        double[] phaseRad = new double[nPoints];
        for (int i = 0; i < nPoints; i++) {
            double bin = freqs[i] / binHz;
            int k0 = (int) Math.floor(bin);
            int k1 = k0 + 1;
            if (k0 < 0)         k0 = 0;
            if (k0 > halfBins)  k0 = halfBins;
            if (k1 > halfBins)  k1 = halfBins;
            double frac = bin - Math.floor(bin);
            double re = hRe[k0] * (1.0 - frac) + hRe[k1] * frac;
            double im = hIm[k0] * (1.0 - frac) + hIm[k1] * frac;
            magLin[i]   = Math.hypot(re, im);
            phaseRad[i] = Math.atan2(im, re);
        }

        // Normalisation: |Y/X| at unity loopback equals the captured ADC
        // peak in normalised units = amplitudeVrms × √2 / V_ADC_FS_PEAK
        // = amplitudeVrms / adcFsVoltageRms.  Dividing by this gives the
        // physical transfer-function magnitude - exactly 1.0 (= 0 dB)
        // for a calibrated unity-gain loopback.
        //
        // The old form used dacDrivePeak (DAC-side normalised peak), which
        // only happens to equal the ADC-side value when the DAC peak full
        // scale exactly equals the ADC peak full scale.  When they differ,
        // the result picked up an offset (e.g. +0.84 dB for an interface
        // with DAC FS=2.79 V_peak vs ADC FS=2.54 V_peak).
        double adcPeakNormalised = (adcFsVoltageRms > 0.0)
                ? amplitudeVRms / adcFsVoltageRms : 0.0;
        if (adcPeakNormalised > 0.0) {
            for (int i = 0; i < nPoints; i++) {
                magLin[i] /= adcPeakNormalised;
            }
        }

        // Savitzky-Golay smoothing of the final per-output-point arrays.
        // Magnitude is smoothed directly; phase is smoothed in (sin, cos)
        // space and recombined via atan2 to handle ±π wraparound at
        // notches without artefacts.  Boundary samples use reflection.
        if (USE_SAVGOL_FILTER && applySavGol && nPoints >= SAVGOL_WINDOW) {
            double[] coeffs = savGolCoefficients(SAVGOL_WINDOW, SAVGOL_ORDER);
            double[] smoothMag = new double[nPoints];
            double[] sinPh     = new double[nPoints];
            double[] cosPh     = new double[nPoints];
            for (int i = 0; i < nPoints; i++) {
                sinPh[i] = Math.sin(phaseRad[i]);
                cosPh[i] = Math.cos(phaseRad[i]);
            }
            double[] smoothSin = new double[nPoints];
            double[] smoothCos = new double[nPoints];
            for (int i = 0; i < nPoints; i++) {
                smoothMag[i] = applySavGol(magLin, i, coeffs);
                smoothSin[i] = applySavGol(sinPh,  i, coeffs);
                smoothCos[i] = applySavGol(cosPh,  i, coeffs);
            }
            for (int i = 0; i < nPoints; i++) {
                magLin[i]   = smoothMag[i];
                phaseRad[i] = Math.atan2(smoothSin[i], smoothCos[i]);
            }
            log.info("{}Filter cal SG smooth: window={}, order={}",
                    tag, SAVGOL_WINDOW, SAVGOL_ORDER);
        }

        double minDb = Double.POSITIVE_INFINITY, maxDb = -Double.POSITIVE_INFINITY;
        double minF = 0.0, maxF = 0.0;
        for (int k = 0; k < nPoints; k++) {
            if (magLin[k] <= 0.0) continue;
            double db = 20.0 * Math.log10(magLin[k]);
            if (db < minDb) { minDb = db; minF = freqs[k]; }
            if (db > maxDb) { maxDb = db; maxF = freqs[k]; }
        }
        log.info("{}Filter cal: FFT M={} ({} bins, {} Hz/bin); transport delay {} samples ({} ms) "
                        + "read on the excited {}..{} Hz and removed; lead-in offset {} FS removed",
                tag,
                M, halfBins,
                String.format(Locale.US, "%.4f", binHz),
                String.format(Locale.US, "%.3f", delaySamples),
                String.format(Locale.US, "%.4f", 1000.0 * delaySamples / sampleRate),
                String.format(Locale.US, "%.3f", kLo * binHz),
                String.format(Locale.US, "%.3f", kHi * binHz),
                String.format(Locale.US, "%.6f", offset));
        if (Double.isFinite(minDb)) {
            log.info("{}Filter cal: min {} dBFS @ {} Hz   max {} dBFS @ {} Hz",
                    tag,
                    String.format(Locale.US, "%.2f", minDb),
                    String.format(Locale.US, "%.3f", minF),
                    String.format(Locale.US, "%.2f", maxDb),
                    String.format(Locale.US, "%.3f", maxF));
        }

        return new FreqRespCalibration(freqs, magLin, phaseRad);
    }

    /** The impulse response's ENVELOPE at sample {@code i}: the magnitude of
     *  the analytic impulse, whose complex samples lie interleaved in
     *  {@code analytic}.  The envelope, not the waveform, is what the delay
     *  search reads - a band-limited impulse's own carrier would otherwise
     *  offer the search a crest every half cycle. */
    private double envelopeAt(double[] analytic, int i) {
        return Math.hypot(analytic[2*i], analytic[2*i + 1]);
    }

    /** The mean of the record's first {@link #OFFSET_LEAD_IN_SEC} of lead-in
     *  samples - the channel's DC offset - or 0 when the record has no lead-in
     *  to read it from. */
    private double leadInOffset(double[] yRec, int leadInSamples, int sampleRate) {
        int window = (int) Math.round(OFFSET_LEAD_IN_SEC * sampleRate);
        int n = Math.min(Math.min(leadInSamples, window), yRec.length);
        if (n <= 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            sum += yRec[i];
        }
        return sum / n;
    }

    /** Computes the central-tap Savitzky-Golay smoothing coefficients
     *  for the given odd window length and polynomial order.  The
     *  central tap of the SG smoother is the first row of
     *  {@code (Vᵀ V)⁻¹ Vᵀ} where {@code V[i][j] = (i − centre)^j}.
     *  Matrix inversion is plain Gauss-Jordan; the matrix is at most
     *  {@code (order+1) × (order+1)} which is tiny so the cost is
     *  negligible. */
    private double[] savGolCoefficients(int window, int order) {
        int m = window / 2;
        int n = order + 1;
        double[][] v = new double[window][n];
        for (int i = 0; i < window; i++) {
            double x = i - m;
            v[i][0] = 1.0;
            for (int j = 1; j < n; j++) v[i][j] = v[i][j - 1] * x;
        }
        double[][] vtv = new double[n][n];
        for (int a = 0; a < n; a++) {
            for (int b = 0; b < n; b++) {
                double s = 0.0;
                for (int i = 0; i < window; i++) s += v[i][a] * v[i][b];
                vtv[a][b] = s;
            }
        }
        double[][] inv = invertSquareMatrix(vtv);
        double[] c = new double[window];
        for (int i = 0; i < window; i++) {
            double s = 0.0;
            for (int j = 0; j < n; j++) s += inv[0][j] * v[i][j];
            c[i] = s;
        }
        return c;
    }

    /** Convolves {@code arr} with {@code coeffs} at index {@code i}.
     *  Out-of-bounds indices are reflected (mirror at boundary) so
     *  edge samples still get smoothed without exotic boundary
     *  conventions. */
    private double applySavGol(double[] arr, int i, double[] coeffs) {
        int half = coeffs.length / 2;
        double sum = 0.0;
        for (int j = -half; j <= half; j++) {
            int idx = i + j;
            if (idx < 0)             idx = -idx;
            if (idx >= arr.length)   idx = 2 * (arr.length - 1) - idx;
            if (idx < 0)             idx = 0;
            if (idx >= arr.length)   idx = arr.length - 1;
            sum += coeffs[j + half] * arr[idx];
        }
        return sum;
    }

    /** Gauss-Jordan inverse for a small square matrix.  Caller owns
     *  the input; this method returns a fresh inverse and does not
     *  mutate {@code a}.  Adequate for the {@code (order+1)²} matrices
     *  used by {@link #savGolCoefficients}; not intended for anything
     *  larger. */
    private double[][] invertSquareMatrix(double[][] a) {
        int n = a.length;
        double[][] m = new double[n][2 * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(a[i], 0, m[i], 0, n);
            m[i][n + i] = 1.0;
        }
        for (int col = 0; col < n; col++) {
            int pivot = col;
            double pivotMag = Math.abs(m[col][col]);
            for (int row = col + 1; row < n; row++) {
                double mag = Math.abs(m[row][col]);
                if (mag > pivotMag) { pivot = row; pivotMag = mag; }
            }
            if (pivot != col) {
                double[] tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp;
            }
            double diag = m[col][col];
            if (diag == 0.0) throw new ArithmeticException("Singular matrix in SG coefficient solve");
            double inv = 1.0 / diag;
            for (int j = 0; j < 2 * n; j++) m[col][j] *= inv;
            for (int row = 0; row < n; row++) {
                if (row == col) continue;
                double factor = m[row][col];
                if (factor == 0.0) continue;
                for (int j = 0; j < 2 * n; j++) m[row][j] -= factor * m[col][j];
            }
        }
        double[][] out = new double[n][n];
        for (int i = 0; i < n; i++) System.arraycopy(m[i], n, out[i], 0, n);
        return out;
    }

    /**
     * Writes a stereo filter calibration to disk.  Comma-separated, dot
     * decimal, one row per measured sweep point with five columns:
     *
     * <pre>frequency_hz, mag_left_dB, mag_right_dB, phase_left_deg, phase_right_deg</pre>
     *
     * <p>Magnitudes are stored as <em>relative</em> dB - the calibrated
     * ratio of (ADC voltage) to (DAC drive voltage), so a flat passband
     * sits at 0 dB regardless of absolute DAC or ADC scaling.  The DAC
     * and ADC voltage calibrations are assumed to have been applied
     * before the measurement; nothing in the file depends on them.
     *
     * <p>Header lines (prefixed by {@code #}) record measurement params
     * for round-trip fidelity; {@link #loadFrc} only needs the data rows,
     * while the FreqResp pane's Load-from tab reads the capture rate back from
     * the header block.
     *
     * <p>Once the file is complete (the writer closed) it is reported through
     * the saved-file broadcast of {@link Preferences}, so a calibration list
     * holding a row that references this file notifies its listeners and the
     * row re-reads the new curve - no caller reports the save on its own.
     * Nothing is reported when this method throws (a missing channel, a file
     * that cannot be opened or written).
     */
    public void saveFrc(StereoFreqRespCalibration stereo, String path,
                        int sampleRate,
                        double sweepStart, double sweepEnd,
                        int sweepPoints,
                        double amplitudeVRms)
            throws IOException {
        if (stereo == null || stereo.left() == null || stereo.right() == null) {
            throw new IllegalArgumentException("stereo calibration with both channels is required");
        }
        File outFile = new File(path);
        File parent  = outFile.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        FreqRespCalibration left  = stereo.left();
        FreqRespCalibration right = stereo.right();
        int n = Math.min(left.freqs.length, right.freqs.length);
        try (PrintWriter pw = new PrintWriter(
                new BufferedWriter(new FileWriter(outFile)))) {
            pw.printf(Locale.US, "# kind=filter_calibration%n");
            pw.printf(Locale.US, "# format_version=%d%n", FileVersions.FRC_CALIBRATION);
            pw.printf(Locale.US, "# sample_rate_hz=%d%n",  sampleRate);
            pw.printf(Locale.US, "# sweep_start_hz=%.6f%n", sweepStart);
            pw.printf(Locale.US, "# sweep_end_hz=%.6f%n",   sweepEnd);
            pw.printf(Locale.US, "# sweep_points=%d%n",     sweepPoints);
            pw.printf(Locale.US, "# dac_drive_v_rms=%.6f%n", amplitudeVRms);
            pw.println("frequency_hz,mag_left_dB,mag_right_dB,phase_left_deg,phase_right_deg");
            for (int i = 0; i < n; i++) {
                double magL   = left.magLin[i];
                double magR   = right.magLin[i];
                double dbL    = magL > 0.0 ? 20.0 * Math.log10(magL) : -300.0;
                double dbR    = magR > 0.0 ? 20.0 * Math.log10(magR) : -300.0;
                double phaseL = Math.toDegrees(left.phaseRad[i]);
                double phaseR = Math.toDegrees(right.phaseRad[i]);
                pw.printf(Locale.US, "%.6f,%.6f,%.6f,%.4f,%.4f%n",
                        left.freqs[i], dbL, dbR, phaseL, phaseR);
            }
            // A PrintWriter swallows write errors; checkError flushes the
            // buffer and reports any, so a full disk or a lost share fails the
            // save instead of leaving a truncated file reported as written.
            if (pw.checkError()) {
                throw new IOException("Could not write the calibration file: " + path);
            }
        }
        Preferences.instance().broadcastSavedFile(path);
    }

    /**
     * Reads a stereo filter calibration file written by {@link #saveFrc}.
     * Expects the 5-column format defined there; throws when the file is
     * empty or rows fewer than 5 columns.  Field separator is comma, but
     * legacy semicolon-separated files (older format) are also accepted
     * so old in-flight measurements still round-trip while migrating.
     */
    public StereoFreqRespCalibration loadFrc(String path) throws IOException {
        List<double[]> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (!Character.isDigit(line.charAt(0)) && line.charAt(0) != '-') continue;
                String[] cols = line.contains(",") ? line.split(",") : line.split(";");
                if (cols.length < 5) continue;
                double freq   = Double.parseDouble(cols[0].trim().replace(',', '.'));
                double dbL    = Double.parseDouble(cols[1].trim().replace(',', '.'));
                double dbR    = Double.parseDouble(cols[2].trim().replace(',', '.'));
                double phaseL = Math.toRadians(Double.parseDouble(cols[3].trim().replace(',', '.')));
                double phaseR = Math.toRadians(Double.parseDouble(cols[4].trim().replace(',', '.')));
                rows.add(new double[]{ freq,
                        Math.pow(10.0, dbL / 20.0), phaseL,
                        Math.pow(10.0, dbR / 20.0), phaseR });
            }
        }
        if (rows.isEmpty()) {
            throw new IOException("Filter calibration file has no data rows: " + path);
        }
        int n = rows.size();
        double[] freqs    = new double[n];
        double[] magL     = new double[n];
        double[] phaseL   = new double[n];
        double[] magR     = new double[n];
        double[] phaseR   = new double[n];
        for (int i = 0; i < n; i++) {
            double[] r = rows.get(i);
            freqs[i]  = r[0];
            magL[i]   = r[1];
            phaseL[i] = r[2];
            magR[i]   = r[3];
            phaseR[i] = r[4];
        }
        return new StereoFreqRespCalibration(
                new FreqRespCalibration(freqs, magL, phaseL),
                new FreqRespCalibration(freqs, magR, phaseR));
    }

    /**
     * Every tone a tone-only calibration row corrects on {@code r}, in the
     * order the shared lobe bins are claimed: the fundamental (sub-bin refined
     * where the refinement is finite and positive, else the fundamental bin's
     * centre), each harmonic the frame located, the second fundamental of a
     * dual tone and its intermod products of orders 2 to
     * {@link ImdResult#MAX_ORDER}.
     *
     * <p>A position the frame does not carry keeps its SLOT and holds 0 - the
     * list is never compacted, so a later frame's tone set and lobe spans line
     * up with an earlier one's slot for slot and can be compared.
     *
     * <p>The dual-tone products are the CCIF/DIN set the IMD analyser
     * measures:
     * <pre>
     *   d2L = f2 - f1,               d2H = f2 + f1
     *   dnL = (n-1)*f1 - (n-2)*f2,   dnH = (n-1)*f2 - (n-2)*f1   (n &gt;= 3)
     * </pre>
     */
    public double[] toneFrequencies(FftResult r) {
        int harmonics = Math.max(0, r.harmonicCount);
        double[] tones = new double[2 + harmonics + 2 * (ImdResult.MAX_ORDER - 1)];
        double binWidth = r.sampleRate / (double) r.fftSize;
        tones[0] = (Double.isFinite(r.fundamentalHzRefined) && r.fundamentalHzRefined > 0.0)
                ? r.fundamentalHzRefined : r.fundamentalBin * binWidth;
        for (int h = 0; h < harmonics; h++) {
            if (r.harmonicBins != null && r.harmonicBins[h] > 0) {
                tones[1 + h] = r.harmonicHz[h];
            }
        }
        double f2 = r.fundamental2HzRefined;
        if (!Double.isNaN(f2) && f2 > 0.0) {
            // The products are placed on the ORDERED pair, F1 the lower tone,
            // as the IMD analyser orders them: a detected partner can lie
            // below the fundamental, and an unordered pair puts the difference
            // product at a negative frequency.
            double f1 = Math.min(r.fundamentalHzRefined, f2);
            tones[1 + harmonics] = f2;
            f2 = Math.max(r.fundamentalHzRefined, f2);
            int idx = 2 + harmonics;
            for (int n = 2; n <= ImdResult.MAX_ORDER; n++) {
                if (n == 2) {
                    tones[idx++] = f2 - f1;                        // difference
                    tones[idx++] = f2 + f1;                        // sum
                } else {
                    tones[idx++] = (n - 1) * f1 - (n - 2) * f2;
                    tones[idx++] = (n - 1) * f2 - (n - 2) * f1;
                }
            }
        }
        return tones;
    }

    /**
     * The main-lobe geometry of every tone of {@link #toneFrequencies},
     * measured on the frame's RAW bin magnitudes: a tone's energy fills the
     * analysis window's whole main lobe, so a tone-only row's single value
     * {@code H(f_tone)} is written across exactly these bins.
     *
     * <p>All spans come from the frame as it arrives, in one pass, so no span
     * is measured on bins an earlier tone or an earlier row has already
     * rewritten.
     *
     * <p>The calibration's own frequency range is NOT applied here: each
     * loaded row has its own range while this geometry is a property of the
     * frame, shared by every row and compared frame to frame to decide whether
     * the held response still fits.  The range filter therefore sits in
     * {@link #cascadeInverse}, where the row is known.
     *
     * @return one {@link ToneLobe} per tone, in the slot order of
     *         {@link #toneFrequencies}; {@code null} for a tone the frame does
     *         not carry or whose peak falls outside {@code 1 .. fftSize/2}
     */
    public ToneLobe[] toneLobeSpans(FftResult r, double[] tones) {
        int half = r.fftSize / 2;
        double binWidth = r.sampleRate / (double) r.fftSize;
        IntToDoubleFunction mag = k -> Math.hypot(r.re[k], r.im[k]);
        ToneLobe[] lobes = new ToneLobe[tones.length];
        for (int t = 0; t < tones.length; t++) {
            double hz = tones[t];
            if (!(hz > 0.0)) continue;
            int peak = (int) Math.round(hz / binWidth);
            if (peak < 1 || peak > half) continue;
            double floor = LOBE.localFloor(mag, peak, half);
            int[] edges  = LOBE.lobeBins(mag, peak, half, floor);
            lobes[t] = new ToneLobe(peak, edges[0], edges[1], floor, mag.applyAsDouble(peak));
        }
        return lobes;
    }

    /** Whether two lobe sets cover the same bins slot for slot - the fit test
     *  between a held correction and a later frame.  The levels the stretch is
     *  anchored on do not enter it: they move a little with every frame while
     *  the bins the correction stands in do not. */
    public boolean sameGeometry(ToneLobe[] a, ToneLobe[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        for (int t = 0; t < a.length; t++) {
            if (a[t] == null ? b[t] != null : !a[t].sameBins(b[t])) return false;
        }
        return true;
    }

    /**
     * The de-embedding response of every loaded calibration row, cascaded and
     * inverted into the {@link InverseCascade} that {@link #applyInverseInPlace}
     * divides a frame by.
     *
     * <p>The rows are filters in series, so their responses form a COMPLEX
     * PRODUCT - magnitudes multiply, phases add.  The product is commutative,
     * which is why the two groups arrive as two lists and leave as two parts:
     * a with-noise row contributes {@code H(f_k)} at every bin of its swept
     * range (the whole spectrum, noise included), and those rows' product is
     * inverted per bin as {@code conj(G)/|G|^2} - a bin no row reached, and
     * one whose cascade cancelled to zero magnitude, keeps {@code (0, 0)},
     * which cannot arise from a finite response and so marks "leave this bin
     * alone"; no with-noise row at all leaves the arrays {@code null}.  A
     * tone-only row contributes ONE value per tone, {@code H(f_tone)}, and
     * nothing between the tones: the rows' product at each tone is inverted
     * into a {@link LobeCorrection} on that tone's lobe, applied at correction
     * time with the lobe's stretch - the shaping needs the frame's own bin
     * magnitudes, so it cannot be folded into a per-bin array here.  A tone
     * whose lobe no tone-only row spans keeps {@code null}.
     */
    public InverseCascade cascadeInverse(List<FreqRespCalibration> withNoiseRows,
                                         List<FreqRespCalibration> toneOnlyRows,
                                         double[] tones, ToneLobe[] toneLobes,
                                         int fftSize, int sampleRate) {
        int half = fftSize / 2;
        double binWidth = sampleRate / (double) fftSize;
        double[] gRe = null;
        double[] gIm = null;
        if (!withNoiseRows.isEmpty()) {
            gRe = new double[half + 1];
            gIm = new double[half + 1];
            double[] magSq = new double[half + 1];
            for (FreqRespCalibration cal : withNoiseRows) {
                double fLo = cal.freqs[0];
                double fHi = cal.freqs[cal.freqs.length - 1];
                for (int k = 1; k <= half; k++) {
                    double f = k * binWidth;
                    if (f < fLo || f > fHi) continue;
                    double[] h = interpolate(cal, f);
                    if (h[0] <= 0.0) continue;
                    multiplyIn(gRe, gIm, magSq, k, h[0], h[1]);
                }
            }
            for (int k = 1; k <= half; k++) {
                double m = magSq[k];
                if (!(m > 0.0)) {
                    gRe[k] = 0.0;
                    gIm[k] = 0.0;
                    continue;
                }
                double vRe =  gRe[k] / m;
                double vIm = -gIm[k] / m;
                gRe[k] = vRe;
                gIm[k] = vIm;
            }
        }
        LobeCorrection[] lobes = new LobeCorrection[tones.length];
        for (int t = 0; t < tones.length; t++) {
            double hz = tones[t];
            ToneLobe lobe = toneLobes[t];
            if (lobe == null || !(hz > 0.0)) continue;
            double[] g = { 0.0, 0.0 };
            double[] gMagSq = { 0.0 };
            for (FreqRespCalibration cal : toneOnlyRows) {
                if (hz < cal.freqs[0] || hz > cal.freqs[cal.freqs.length - 1]) continue;
                double[] h = interpolate(cal, hz);
                if (h[0] <= 0.0) continue;
                multiplyIn(g, gMagSq, h[0], h[1]);
            }
            if (!(gMagSq[0] > 0.0)) continue;
            lobes[t] = new LobeCorrection(lobe, g[0] / gMagSq[0], -g[1] / gMagSq[0]);
        }
        return new InverseCascade(gRe, gIm, lobes);
    }

    /** {@link #multiplyIn(double[], double[], double[], int, double, double)}
     *  for one tone's single accumulated response: {@code g} holds its real
     *  and imaginary part, {@code magSq} its squared magnitude. */
    private void multiplyIn(double[] g, double[] magSq, double hMag, double phaseRad) {
        double hRe = hMag * Math.cos(phaseRad);
        double hIm = hMag * Math.sin(phaseRad);
        if (magSq[0] > 0.0) {
            double aRe = g[0];
            double aIm = g[1];
            g[0] = aRe * hRe - aIm * hIm;
            g[1] = aRe * hIm + aIm * hRe;
            magSq[0] *= hMag * hMag;
        } else {
            g[0] = hRe;
            g[1] = hIm;
            magSq[0] = hMag * hMag;
        }
    }

    /** Multiplies one row's response at one bin into the cascade.  The first
     *  row to reach a bin writes its response unchanged, so a lone row leaves
     *  no product rounding behind; the squared magnitude is carried as the
     *  product of the rows' own values rather than re-derived from the
     *  accumulated parts, which is what keeps a single row's reciprocal
     *  identical to the one formed straight from its interpolated
     *  magnitude. */
    private void multiplyIn(double[] gRe, double[] gIm, double[] magSq,
                            int k, double hMag, double phaseRad) {
        double hRe = hMag * Math.cos(phaseRad);
        double hIm = hMag * Math.sin(phaseRad);
        if (magSq[k] > 0.0) {
            double aRe = gRe[k];
            double aIm = gIm[k];
            gRe[k]   = aRe * hRe - aIm * hIm;
            gIm[k]   = aRe * hIm + aIm * hRe;
            magSq[k] = magSq[k] * (hMag * hMag);
        } else {
            gRe[k]   = hRe;
            gIm[k]   = hIm;
            magSq[k] = hMag * hMag;
        }
    }

    /**
     * Divides every bin the cascade reached by its response, in place, and
     * returns how many bins were written.  Every such bin has its level and
     * phase restated; every other bin keeps exactly the numbers the analyser
     * wrote, since a recompute would move them by a rounding step.
     *
     * <p>Two passes.  The tone lobes first, on the frame's RAW magnitudes,
     * which the stretch reads: within a lobe the tone-only reciprocal turns
     * every bin by its full phase, while its magnitude is raised to the bin's
     * stretch exponent - in full at the peak, not at all on the floor, the
     * log-height between ({@link ToneLobeLift#stretchExponent}) - so the lobe
     * grows taller with its feet on the floor instead of lifting off it as a
     * flat-topped box.  The first tone of the slot order owns a bin two lobes
     * share.  Then, when a with-noise row is loaded, every bin of the
     * spectrum gets one complex multiply by the stored per-bin reciprocal;
     * without one that walk is skipped and the correction costs the lobes
     * alone.
     *
     * <p>The level scale is read off the fundamental BEFORE any bin is
     * changed, so the whole spectrum is restated on one consistent scale.  The
     * caller guarantees that {@code inv} was sampled on this frame's grid.
     */
    public int applyInverseInPlace(FftResult r, InverseCascade inv) {
        int half = r.fftSize / 2;
        double oldFundMag = Math.hypot(r.re[r.fundamentalBin], r.im[r.fundamentalBin]);
        double linPerMag  = oldFundMag > 0.0 ? r.fundamentalLinear / oldFundMag : 0.0;
        boolean[] written = new boolean[half + 1];
        int corrected = 0;
        for (LobeCorrection lc : inv.lobes()) {
            if (lc == null) continue;
            ToneLobe lobe = lc.lobe();
            double full = lc.magnitude();
            if (!(full > 0.0)) continue;
            double uRe = lc.invRe() / full;
            double uIm = lc.invIm() / full;
            for (int k = Math.max(1, lobe.lo()); k <= Math.min(half, lobe.hi()); k++) {
                if (written[k]) continue;
                double mag = Math.hypot(r.re[k], r.im[k]);
                double t = LOBE.stretchExponent(mag, lobe.floorMag(), lobe.peakMag());
                double scale = Math.pow(full, t);
                restateBin(r, k, uRe * scale, uIm * scale, linPerMag);
                written[k] = true;
                corrected++;
            }
        }
        if (inv.hasNoiseRows()) {
            double[] invRe = inv.invRe();
            double[] invIm = inv.invIm();
            for (int k = 1; k <= half; k++) {
                double vRe = invRe[k];
                double vIm = invIm[k];
                if (vRe == 0.0 && vIm == 0.0) continue;
                restateBin(r, k, vRe, vIm, linPerMag);
                if (!written[k]) corrected++;
            }
        }
        return corrected;
    }

    /** One bin: {@code Z = X * v}, then the level and phase the display and
     *  the statistics read.  A power average the result carries moves by the
     *  same {@code |v|}: it is corrected exactly where and as much as the
     *  spectrum is. */
    private void restateBin(FftResult r, int k, double vRe, double vIm, double linPerMag) {
        double xRe = r.re[k];
        double xIm = r.im[k];
        double zRe = xRe * vRe - xIm * vIm;
        double zIm = xRe * vIm + xIm * vRe;
        r.re[k] = zRe;
        r.im[k] = zIm;
        double newAmpLin = Math.hypot(zRe, zIm) * linPerMag;
        r.amplitudeDbFs[k] = newAmpLin > MIN_LINEAR_AMPLITUDE
                ? AMPLITUDE_DB_FACTOR * Math.log10(newAmpLin) : FLOOR_DB;
        r.phaseDeg[k] = Math.toDegrees(Math.atan2(zIm, zRe));
        if (r.hasPowerAverage && r.powerAverageDbFs[k] > FLOOR_DB) {
            double gain = Math.hypot(vRe, vIm);
            r.powerAverageDbFs[k] = gain > 0.0
                    ? r.powerAverageDbFs[k] + AMPLITUDE_DB_FACTOR * Math.log10(gain) : FLOOR_DB;
        }
    }

    /** The cascade's gain at one bin, {@code 20*log10|G|} - how many dB the
     *  correction takes off that bin, so a level measured after the correction
     *  plus this value is the level before it: the with-noise rows' gain there
     *  plus, inside a tone lobe, the tone-only rows' full gain at the tone (the
     *  peak's, which a tone reading is taken at).  0 for a bin the cascade never
     *  reached and for one outside the sampled grid. */
    public double gainDbAt(InverseCascade inv, int bin) {
        if (inv == null || bin < 1) return 0.0;
        double gainDb = 0.0;
        if (inv.hasNoiseRows() && bin < inv.invRe().length) {
            double vRe = inv.invRe()[bin];
            double vIm = inv.invIm()[bin];
            double vMagSq = vRe * vRe + vIm * vIm;
            if (vMagSq > 0.0) gainDb -= POWER_DB_FACTOR * Math.log10(vMagSq);
        }
        for (LobeCorrection lc : inv.lobes()) {
            if (lc == null || bin < lc.lobe().lo() || bin > lc.lobe().hi()) continue;
            double full = lc.magnitude();
            if (full > 0.0) gainDb -= AMPLITUDE_DB_FACTOR * Math.log10(full);
            break;
        }
        return gainDb;
    }

    /**
     * Snapshots the pre-correction fundamental + harmonic peak levels so the
     * chart can draw blue "before-cal" dots alongside the red "after-cal" dots.
     */
    public double[][] capturePreCorrectionPeaks(FftResult result) {
        int count = 1 + result.harmonicCount;
        double[] freqs = new double[count];
        double[] dbFs  = new double[count];
        freqs[0] = result.fundamentalHz;
        dbFs[0]  = result.fundamentalDbFs;
        for (int h = 0; h < result.harmonicCount; h++) {
            if (result.harmonicBins[h] > 0) {
                freqs[1 + h] = result.harmonicHz[h];
                dbFs[1 + h]  = result.harmonicDbFs[h];
            }
        }
        return new double[][]{ freqs, dbFs };
    }

    /**
     * Builds an inverted-cal overlay for the FFT chart.  Returns a 2-element
     * array {@code [freqs, dbFs]} suitable for the chart's overlay parameters,
     * or {@code null} if there's no H2 reference available.
     */
    public double[][] computeOverlay(FreqRespCalibration cal, FftResult result) {
        if (result.harmonicCount == 0 || result.harmonicBins[0] <= 0) return null;
        double h2Freq = result.harmonicHz[0];
        double h2DbFs = result.harmonicDbFs[0];
        if (!(h2Freq > 0.0)) return null;
        double[] h = interpolate(cal, h2Freq);
        double calDbAtH2 = h[0] > 0.0 ? 20.0 * Math.log10(h[0]) : -300.0;
        double offset   = h2DbFs + calDbAtH2;
        double[] freqs  = cal.freqs.clone();
        double[] dbFs   = new double[cal.magLin.length];
        for (int i = 0; i < cal.magLin.length; i++) {
            double calDb = cal.magLin[i] > 0.0 ? 20.0 * Math.log10(cal.magLin[i]) : -300.0;
            dbFs[i] = -calDb + offset;
        }
        return new double[][]{ freqs, dbFs };
    }

    /**
     * Divides a single complex phasor {@code (re, im)} by the calibration
     * response {@code H = magLin·e^{j·phaseRad}} - i.e. takes the {@code .frc}
     * out of one measured bin, de-embedding BOTH magnitude and phase
     * ({@code X/H = X·e^{−j·phaseRad}/magLin}).  Same divide
     * {@link #applyInverseInPlace} does per bin, exposed for the
     * DAC-predistortion correction which de-embeds the raw fundamental /
     * harmonic / intermod-product phasors one at a time.  A non-positive
     * {@code magLin} (no cal at this frequency) returns the phasor unchanged.
     *
     * @return {@code [re, im]} of {@code X/H}
     */
    public double[] deEmbed(double re, double im, double magLin, double phaseRad) {
        if (!(magLin > 0.0)) return new double[]{ re, im };
        double c = Math.cos(phaseRad), s = Math.sin(phaseRad);
        return new double[]{ (re * c + im * s) / magLin, (im * c - re * s) / magLin };
    }

    /**
     * Interpolates the calibration's H(f) at an arbitrary frequency.  Uses
     * log-frequency as the interpolation variable.  Magnitude in dB; phase via
     * complex unit-phasor interpolation to handle ±180° wraps at notches.
     *
     * @return {@code [magLin, phaseRad]}
     */
    public double[] interpolate(FreqRespCalibration cal, double freq) {
        int lo = 0, hi = cal.freqs.length - 1;
        if (freq <= cal.freqs[lo]) return new double[]{ cal.magLin[lo], cal.phaseRad[lo] };
        if (freq >= cal.freqs[hi]) return new double[]{ cal.magLin[hi], cal.phaseRad[hi] };
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (cal.freqs[mid] <= freq) lo = mid; else hi = mid;
        }
        double f0 = cal.freqs[lo], f1 = cal.freqs[hi];
        double t  = (Math.log(freq) - Math.log(f0)) / (Math.log(f1) - Math.log(f0));

        double m0 = cal.magLin[lo], m1 = cal.magLin[hi];
        double db0 = m0 > 0.0 ? 20.0 * Math.log10(m0) : -300.0;
        double db1 = m1 > 0.0 ? 20.0 * Math.log10(m1) : -300.0;
        double db  = db0 + (db1 - db0) * t;
        double mag = Math.pow(10.0, db / 20.0);

        double re0 = Math.cos(cal.phaseRad[lo]), im0 = Math.sin(cal.phaseRad[lo]);
        double re1 = Math.cos(cal.phaseRad[hi]), im1 = Math.sin(cal.phaseRad[hi]);
        double reT = re0 + (re1 - re0) * t;
        double imT = im0 + (im1 - im0) * t;
        double phi = (reT == 0.0 && imT == 0.0)
                ? cal.phaseRad[lo]
                : Math.atan2(imT, reT);

        return new double[]{ mag, phi };
    }
}
