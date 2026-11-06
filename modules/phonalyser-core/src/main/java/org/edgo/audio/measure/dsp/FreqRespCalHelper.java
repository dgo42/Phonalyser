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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.edgo.audio.measure.common.FileVersions;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.fft.ImdResult;
import org.edgo.audio.measure.fft.MathUtil;
import org.edgo.audio.measure.fft.SpectralLine;
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
     * {@code H = Y / X}.  Removes the DAC-to-ADC transport delay (linear-phase
     * term located via the impulse-response peak) and normalises magnitude to
     * the DAC drive level so the passband sits at about 1.0.
     *
     *  <p>The output is not smoothed: on the bin-aligned grid
     *  ({@link #binAlignedFreqs}) the trace is already leakage-free, and a
     *  smoothing window of a few points spans several Hz on a coarse grid and
     *  rounds the bottom off a deep, narrow notch null.
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
     * list is never compacted, so a later frame's tone set lines up with an
     * earlier one's slot for slot and can be compared.
     *
     * <p>The dual-tone products are the CCIF/DIN set the IMD analyser
     * measures:
     * <pre>
     *   d2L = f2 - f1,               d2H = f2 + f1
     *   dnL = (n-1)*f1 - (n-2)*f2,   dnH = (n-1)*f2 - (n-2)*f1   (n &gt;= 3)
     * </pre>
     *
     * @param r the spectrum, its harmonic ladder placed
     * @return the tone frequencies by slot, Hz; 0 for a slot not carried
     */
    public double[] toneFrequencies(FftResult r) {
        int harmonics = Math.max(0, r.harmonicCount);
        double[] tones = new double[toneCount(r)];
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

    /** How many slots {@link #toneFrequencies} has: the fundamental, the
     *  harmonics, the second fundamental and the two products of every
     *  intermodulation order from 2 to {@link ImdResult#MAX_ORDER}.
     *
     * @param r the spectrum
     * @return the slot count */
    public int toneCount(FftResult r) {
        return 2 + Math.max(0, r.harmonicCount) + 2 * (ImdResult.MAX_ORDER - 1);
    }

    /** The slot of the second fundamental in {@link #toneFrequencies}: right
     *  after the harmonics; the intermodulation products follow it.
     *
     * @param r the spectrum
     * @return the slot index */
    public int secondToneSlot(FftResult r) {
        return 1 + Math.max(0, r.harmonicCount);
    }

    /** The bin of every tone of {@link #toneFrequencies} on {@code r}'s grid,
     *  slot for slot - the positions a tone-only row's values stand at, so a
     *  held correction fits a later frame exactly while these are the same.
     *
     * @param r the spectrum
     * @return the bins by slot; -1 for a slot not carried or off the grid */
    public int[] toneBins(FftResult r) {
        double[] tones = toneFrequencies(r);
        int half = r.fftSize / 2;
        double binWidth = r.sampleRate / (double) r.fftSize;
        int[] bins = new int[tones.length];
        for (int t = 0; t < tones.length; t++) {
            int bin = tones[t] > 0.0 ? (int) Math.round(tones[t] / binWidth) : -1;
            bins[t] = bin >= 1 && bin <= half ? bin : -1;
        }
        return bins;
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
     * into a {@link LobeCorrection}, applied at correction time across the
     * tone's lobe as the spectrum measured it, with the lobe's stretch - the
     * shaping needs the frame's own bin levels, so it cannot be folded into a
     * per-bin array here.  A tone no tone-only row spans keeps {@code null}.
     *
     * @param withNoiseRows the rows that correct every bin
     * @param toneOnlyRows  the rows that correct the tones only
     * @param tones         the tone frequencies by slot
     *                      ({@link #toneFrequencies}), Hz; 0 for a slot not
     *                      carried
     * @param fftSize       the FFT length, samples
     * @param sampleRate    the capture rate, Hz
     * @return the cascade
     */
    public InverseCascade cascadeInverse(List<FreqRespCalibration> withNoiseRows,
                                         List<FreqRespCalibration> toneOnlyRows,
                                         double[] tones, int fftSize, int sampleRate) {
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
            if (!(hz > 0.0)) continue;
            double[] g = { 0.0, 0.0 };
            double[] gMagSq = { 0.0 };
            for (FreqRespCalibration cal : toneOnlyRows) {
                if (hz < cal.freqs[0] || hz > cal.freqs[cal.freqs.length - 1]) continue;
                double[] h = interpolate(cal, hz);
                if (h[0] <= 0.0) continue;
                multiplyIn(g, gMagSq, h[0], h[1]);
            }
            if (!(gMagSq[0] > 0.0)) continue;
            lobes[t] = new LobeCorrection(g[0] / gMagSq[0], -g[1] / gMagSq[0]);
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
     * Applies {@code inv} to {@code r} in place.  Every bin it reaches has
     * its level and phase restated; every other bin keeps exactly the numbers
     * the analyser wrote, since a recompute would move them by a rounding
     * step.
     *
     * <p>Two passes.  The tone lobes first: each tone's lobe is lifted by its
     * tone-only reciprocal ({@link #liftLobe}).  Then, when a with-noise row
     * is loaded, every bin of the spectrum gets one complex multiply by the
     * per-bin reciprocal; without one that walk is skipped and the
     * correction costs the lobes alone.  The caller guarantees that
     * {@code inv} was sampled on this frame's grid.
     *
     * @param r     the spectrum as it arrived
     * @param lines its lines, in the slot order of {@link #toneFrequencies}
     * @param inv   the correction
     */
    public void applyInverseInPlace(FftResult r, SpectralLine[] lines, InverseCascade inv) {
        liftLobes(r, lines, inv);
        multiplyBins(r, inv);
    }

    /**
     * Takes {@code inv} off a spectrum it was applied to, in place - the
     * reverse of {@link #applyInverseInPlace}: first the with-noise rows'
     * reciprocal per bin, then each tone lobe lowered by its tone-only
     * reciprocal.  A lift leaves every bin at or below the floor where it is
     * and the peak is read as it stands, so each bin's stretch exponent is
     * the one it was lifted with and the spectrum comes back.
     *
     * @param r     the spectrum, corrected with {@code inv}
     * @param lines its lines, in the slot order of {@link #toneFrequencies}
     * @param inv   the correction to take off
     */
    public void removeInverseInPlace(FftResult r, SpectralLine[] lines, InverseCascade inv) {
        InverseCascade back = inv.reciprocal();
        multiplyBins(r, back);
        liftLobes(r, lines, back);
    }

    /**
     * Lifts the main lobe of one line of {@code r} in place by the complex
     * {@code factor} - the one lift every correction of the trace takes: the
     * tone-only rows, the manual fundamental, a clamped distortion line.  Every
     * lobe bin's phase turns by the factor's argument and its magnitude grows
     * by {@code |factor|} raised to the bin's stretch exponent
     * ({@link #lobeScales}), so the lobe grows taller with its feet on the
     * floor.  The lobe and its floor are the line's, measured on the
     * spectrum as it arrived; the peak is read as it stands.  The level, the
     * phasor and the power average are restated together, on the level scale
     * the fundamental bin carries.
     *
     * <p>A spectrum that already carries with-noise rows ({@code carried})
     * has the noise around the line moved by their gain at the line's bin, so
     * the floor is moved by the same gain.
     *
     * @param r        the spectrum
     * @param line     the line whose lobe is lifted
     * @param factorRe real part of the factor
     * @param factorIm imaginary part of the factor
     * @param carried  the correction the spectrum already carries;
     *                 {@code null} for none
     */
    public void liftLobe(FftResult r, SpectralLine line, double factorRe, double factorIm,
                         InverseCascade carried) {
        double full = Math.hypot(factorRe, factorIm);
        if (line == null || !line.measurable() || !(full > 0.0)) return;
        int lo = lobeLo(line);
        double[] scales = lobeScales(r, line, line.bandTopDbFs() + noiseRowGainDb(carried, line.bin()), full);
        double linPerMag = levelPerMagnitude(r);
        double uRe = factorRe / full;
        double uIm = factorIm / full;
        for (int i = 0; i < scales.length; i++) {
            restateBin(r, lo + i, uRe * scales[i], uIm * scales[i], linPerMag);
        }
        followFundamental(r);
    }

    /** Lifts the lobe of every line {@code inv} carries a tone-only
     *  correction for.
     *
     * @param r     the spectrum
     * @param lines the lines, in the slot order of {@link #toneFrequencies}
     * @param inv   the correction */
    private void liftLobes(FftResult r, SpectralLine[] lines, InverseCascade inv) {
        LobeCorrection[] lobes = inv.lobes();
        for (int t = 0; t < lobes.length && t < lines.length; t++) {
            if (lobes[t] != null) liftLobe(r, lines[t], lobes[t].invRe(), lobes[t].invIm(), null);
        }
    }

    /** Multiplies every bin the with-noise rows of {@code inv} reached by its
     *  per-bin reciprocal; nothing without such a row.
     *
     * @param r   the spectrum
     * @param inv the correction */
    private void multiplyBins(FftResult r, InverseCascade inv) {
        if (!inv.hasNoiseRows()) return;
        double[] invRe = inv.invRe();
        double[] invIm = inv.invIm();
        double linPerMag = levelPerMagnitude(r);
        for (int k = 1; k <= r.fftSize / 2; k++) {
            if (invRe[k] == 0.0 && invIm[k] == 0.0) continue;
            restateBin(r, k, invRe[k], invIm[k], linPerMag);
        }
        followFundamental(r);
    }

    /**
     * The power gain {@link #applyInverseInPlace} gives every bin of {@code r}
     * with {@code inv}, written into {@code gains} (bins {@code 0 .. fftSize/2}):
     * {@code |v|^2} of the bin's whole correction - the line lobe's stretched
     * magnitude ({@link #lobeScales}) times the with-noise rows' reciprocal -
     * and 1 where the correction leaves the bin alone.  Read on {@code r} as
     * it arrived, on the same lines the correction reads: a level measured on
     * the spectrum times this gain is the level the corrected spectrum
     * carries, so a sum over measured bins weighted by it is the sum over the
     * corrected spectrum.
     *
     * @param r     the spectrum as it arrived
     * @param lines its lines, in the slot order of {@link #toneFrequencies}
     * @param inv   the correction
     * @param gains the gains by bin, written
     */
    public void binPowerGains(FftResult r, SpectralLine[] lines, InverseCascade inv, double[] gains) {
        int half = r.fftSize / 2;
        Arrays.fill(gains, 0, half + 1, 1.0);
        LobeCorrection[] lobes = inv.lobes();
        for (int t = 0; t < lobes.length && t < lines.length; t++) {
            SpectralLine line = lines[t];
            if (lobes[t] == null || line == null || !line.measurable()) continue;
            int lo = lobeLo(line);
            double[] scales = lobeScales(r, line, line.bandTopDbFs(), lobes[t].magnitude());
            for (int i = 0; i < scales.length; i++) {
                gains[lo + i] = scales[i] * scales[i];
            }
        }
        if (inv.hasNoiseRows()) {
            double[] invRe = inv.invRe();
            double[] invIm = inv.invIm();
            for (int k = 1; k <= half; k++) {
                double vMagSq = invRe[k] * invRe[k] + invIm[k] * invIm[k];
                if (vMagSq > 0.0) gains[k] *= vMagSq;
            }
        }
    }

    /** The magnitude a lift of full magnitude {@code full} scales each bin
     *  of {@code line}'s lobe by: {@code full} raised to the bin's stretch
     *  exponent ({@link ToneLobeLift#stretchExponent}) - in full at the peak,
     *  not at all at or below the floor, the bin's log-height between; the
     *  whole lobe in full where no floor was measured.  The peak is read once,
     *  before any bin changes.
     *
     * @param r         the spectrum
     * @param line      the line
     * @param floorDbFs the floor the stretch stands on, dBFS
     * @param full      the lift's full magnitude
     * @return the scale of every lobe bin, from the lobe's first bin on */
    private double[] lobeScales(FftResult r, SpectralLine line, double floorDbFs, double full) {
        int lo = lobeLo(line);
        int hi = Math.min(r.amplitudeDbFs.length - 1, line.bin() + line.skirtHalfWidthBins());
        double floorLin = linearOf(floorDbFs);
        double peakLin  = linearOf(r.amplitudeDbFs[line.bin()]);
        double[] scales = new double[Math.max(0, hi - lo + 1)];
        for (int i = 0; i < scales.length; i++) {
            double t = ToneLobeLift.stretchExponent(linearOf(r.amplitudeDbFs[lo + i]), floorLin, peakLin);
            scales[i] = Math.pow(full, t);
        }
        return scales;
    }

    /** The first bin of {@code line}'s lobe.
     *
     * @param line the line
     * @return the bin, at least 1 */
    private int lobeLo(SpectralLine line) {
        return Math.max(1, line.bin() - line.skirtHalfWidthBins());
    }

    /** The with-noise rows' gain at {@code bin}, {@code 20*log10|v|}: how
     *  many dB they moved that bin.
     *
     * @param inv the correction; {@code null} for none
     * @param bin the bin
     * @return the gain, dB; 0 without with-noise rows or where they did not
     *         reach */
    private double noiseRowGainDb(InverseCascade inv, int bin) {
        if (inv == null || !inv.hasNoiseRows() || bin < 1 || bin >= inv.invRe().length) return 0.0;
        double vMagSq = inv.invRe()[bin] * inv.invRe()[bin] + inv.invIm()[bin] * inv.invIm()[bin];
        return vMagSq > 0.0 ? POWER_DB_FACTOR * Math.log10(vMagSq) : 0.0;
    }

    /** A level in dBFS as a linear amplitude, full scale 1.
     *
     * @param dbFs the level, dBFS; {@link Double#NaN} stays NaN
     * @return the linear amplitude */
    private double linearOf(double dbFs) {
        return Math.pow(10.0, dbFs / AMPLITUDE_DB_FACTOR);
    }

    /** Linear amplitude per unit of phasor magnitude, read off the
     *  fundamental bin - its level against its phasor - before the pass
     *  changes any bin, so the pass restates the spectrum on one scale.
     *
     * @param r the spectrum
     * @return the scale; 0 where the fundamental bin is silent */
    private double levelPerMagnitude(FftResult r) {
        double fundMag = Math.hypot(r.re[r.fundamentalBin], r.im[r.fundamentalBin]);
        return fundMag > 0.0 ? linearOf(r.amplitudeDbFs[r.fundamentalBin]) / fundMag : 0.0;
    }

    /** The fundamental's level scalars stand for the spectrum as it is: they
     *  follow its bin after a pass.
     *
     * @param r the spectrum */
    private void followFundamental(FftResult r) {
        r.fundamentalDbFs   = r.amplitudeDbFs[r.fundamentalBin];
        r.fundamentalLinear = linearOf(r.fundamentalDbFs);
    }

    /** One bin: {@code Z = X * v}, then the level the display reads; the
     *  phase stays in the phasor, where the save reads it.  A power average
     *  the result carries moves by the same {@code |v|}: it is corrected
     *  exactly where and as much as the spectrum is.
     *
     * @param r         the spectrum
     * @param k         the bin
     * @param vRe       real part of the factor
     * @param vIm       imaginary part of the factor
     * @param linPerMag linear amplitude per unit of phasor magnitude */
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
        if (r.hasPowerAverage && r.powerAverageDbFs[k] > FLOOR_DB) {
            double gain = Math.hypot(vRe, vIm);
            r.powerAverageDbFs[k] = gain > 0.0
                    ? r.powerAverageDbFs[k] + AMPLITUDE_DB_FACTOR * Math.log10(gain) : FLOOR_DB;
        }
    }

    /** The cascade's gain for a line, {@code 20*log10|G|} - how many dB the
     *  correction takes off the line, so a level measured after the
     *  correction plus this value is the level before it: the with-noise
     *  rows' gain at the line's bin plus the tone-only rows' full gain at the
     *  line's own tone.  0 without a correction and for a bin the cascade
     *  never reached.
     *
     * @param inv  the correction; {@code null} for none
     * @param slot the line's slot of {@link #toneFrequencies}; -1 for a bin
     *             no tone stands on
     * @param bin  the line's bin
     * @return the gain, dB */
    public double gainDbAt(InverseCascade inv, int slot, int bin) {
        if (inv == null || bin < 1) return 0.0;
        double gainDb = -noiseRowGainDb(inv, bin);
        LobeCorrection[] lobes = inv.lobes();
        if (slot >= 0 && slot < lobes.length && lobes[slot] != null && lobes[slot].magnitude() > 0.0) {
            gainDb -= AMPLITUDE_DB_FACTOR * Math.log10(lobes[slot].magnitude());
        }
        return gainDb;
    }

    /** {@link #gainDbAt(InverseCascade, int, int)} for a bin, the tone that
     *  owns it found among {@code lines}: the first whose lobe holds the bin.
     *
     * @param lines the lines, in the slot order of {@link #toneFrequencies}
     * @param inv   the correction; {@code null} for none
     * @param bin   the bin
     * @return the gain, dB */
    public double gainDbAt(SpectralLine[] lines, InverseCascade inv, int bin) {
        for (int t = 0; t < lines.length; t++) {
            SpectralLine line = lines[t];
            if (line != null && line.measurable()
                    && Math.abs(bin - line.bin()) <= line.skirtHalfWidthBins()) {
                return gainDbAt(inv, t, bin);
            }
        }
        return gainDbAt(inv, -1, bin);
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
