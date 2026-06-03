package org.edgo.audio.measure.cli.util;

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

import org.edgo.audio.measure.dsp.ToneLobeLift;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.fft.FftResult;
import org.edgo.audio.measure.fft.MathUtil;
import org.edgo.audio.measure.sound.AudioBackend;
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

    /** Shared per-tone lobe lift (floor estimate + data-derived lobe extent +
     *  power-domain proportional scale) — also used by the manual-fundamental
     *  display so both rescale a tone the same way. */
    private static final ToneLobeLift LOBE = new ToneLobeLift();

    /** Compile-time switch for IR-domain time gating after delay
     *  correction.  When on, the deconvolved impulse response is
     *  windowed to {@link #IR_GATE_LENGTH_SEC} around the main peak
     *  before being re-FFTed; the rest of the IR (mostly noise) is
     *  zeroed.  Net effect on the magnitude trace: smooth ≈20 dB of
     *  residual ripple while keeping every real device feature with
     *  decay time shorter than the gate. */
    private static final boolean USE_IR_GATING = false;

    /** One-sided gate length in seconds.  Frequency resolution after
     *  gating is ≈ 1/this — features narrower than that get smoothed.
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

    /**
     * Reconstructs the per-frequency filter calibration H(f) from the captured
     * recording of a Farina log-sweep by direct frequency-domain deconvolution:
     * {@code H = Y / X}.  Removes the DAC↔ADC transport delay (linear-phase term
     * located via the impulse-response peak) and normalises magnitude to the DAC
     * drive level so the passband sits at ≈ 1.0.
     */
    public FreqRespCalibration computeFromLogSweep(
            float[] yRec, float[] sweepRef, int leadInSamples,
            int sampleRate, double[] freqs, double amplitudeVRms) {
        return computeFromLogSweep(yRec, sweepRef, leadInSamples,
                sampleRate, freqs, amplitudeVRms, 0, null);
    }

    /** Overload that tags every log line with {@code channelLabel}
     *  (e.g. {@code "L"} / {@code "R"}) so the two parallel deconvolution
     *  threads in {@code FreqRespAnalyzer} produce unambiguous log
     *  output even when their writes interleave.  Pass {@code null} or
     *  empty for no prefix.
     *
     *  <p>{@code fadeSamples} is the per-side Hann fade length applied to
     *  the X reference inside this call — it MUST match the
     *  {@code fadeInSamples}/{@code fadeOutSamples} previously set on
     *  the playing {@code SignalGenerator} (use {@link #sweepFadeSamples}
     *  to compute one value for both sites).  Zero disables the fade and
     *  the reference is used raw. */
    public FreqRespCalibration computeFromLogSweep(
            float[] yRec, float[] sweepRef, int leadInSamples,
            int sampleRate, double[] freqs, double amplitudeVRms,
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
        double[] yBuf = new double[M];
        for (int i = 0; i < yRec.length; i++) {
            yBuf[i] = yRec[i];
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

        double[] ir = new double[M];
        ir[0] = hRe[0];
        ir[1] = hRe[halfBins];
        for (int k = 1; k < halfBins; k++) {
            ir[2*k]     = hRe[k];
            ir[2*k + 1] = hIm[k];
        }
        fft.realInverse(ir, true);
        int    peakIdx = 0;
        double peakVal = 0.0;
        for (int i = 0; i < M; i++) {
            double v = Math.abs(ir[i]);
            if (v > peakVal) { peakVal = v; peakIdx = i; }
        }
        double delaySamples = peakIdx;
        if (peakIdx > 0 && peakIdx < M - 1) {
            double yM = Math.abs(ir[peakIdx - 1]);
            double y0 = Math.abs(ir[peakIdx]);
            double yP = Math.abs(ir[peakIdx + 1]);
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
        double binHz = sampleRate / (double) M;
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
        // physical transfer-function magnitude — exactly 1.0 (= 0 dB)
        // for a calibrated unity-gain loopback.
        //
        // The old form used dacDrivePeak (DAC-side normalised peak), which
        // only happens to equal the ADC-side value when the DAC peak full
        // scale exactly equals the ADC peak full scale.  When they differ,
        // the result picked up an offset (e.g. +0.84 dB for an interface
        // with DAC FS=2.79 V_peak vs ADC FS=2.54 V_peak).
        double adcFsRms = AudioBackend.getAdcFsVoltageRms();
        double adcPeakNormalised = (adcFsRms > 0.0)
                ? amplitudeVRms / adcFsRms : 0.0;
        if (adcPeakNormalised > 0.0) {
            for (int i = 0; i < nPoints; i++) {
                magLin[i] /= adcPeakNormalised;
            }
        }

        // Savitzky-Golay smoothing of the final per-output-point arrays.
        // Magnitude is smoothed directly; phase is smoothed in (sin, cos)
        // space and recombined via atan2 to handle ±π wraparound at
        // notches without artefacts.  Boundary samples use reflection.
        if (USE_SAVGOL_FILTER && nPoints >= SAVGOL_WINDOW) {
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
        log.info("{}Filter cal: FFT M={} ({} bins, {} Hz/bin); transport delay {} samples ({} ms) removed",
                tag,
                M, halfBins,
                String.format(Locale.US, "%.4f", binHz),
                String.format(Locale.US, "%.3f", delaySamples),
                String.format(Locale.US, "%.4f", 1000.0 * delaySamples / sampleRate));
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

    /** Mutates {@code measured} in-place: divides every magnitude by the
     *  calibration interpolated at the same frequency.  Phase is subtracted
     *  ({@code phase_out = phase_meas − phase_cal}) so the resulting curve
     *  represents the device-under-test alone. */
    public void divideInPlace(FreqRespCalibration measured, FreqRespCalibration calibration) {
        for (int i = 0; i < measured.freqs.length; i++) {
            double[] cal = interpolate(calibration, measured.freqs[i]);
            double calMag = cal[0];
            double calPhi = cal[1];
            if (calMag > 0.0) {
                measured.magLin[i] /= calMag;
            }
            measured.phaseRad[i] -= calPhi;
        }
    }

    /**
     * Writes a stereo filter calibration to disk.  Comma-separated, dot
     * decimal, one row per measured sweep point with five columns:
     *
     * <pre>frequency_hz, mag_left_dB, mag_right_dB, phase_left_deg, phase_right_deg</pre>
     *
     * <p>Magnitudes are stored as <em>relative</em> dB — the calibrated
     * ratio of (ADC voltage) to (DAC drive voltage), so a flat passband
     * sits at 0 dB regardless of absolute DAC or ADC scaling.  The DAC
     * and ADC voltage calibrations are assumed to have been applied
     * before the measurement; nothing in the file depends on them.
     *
     * <p>Header lines (prefixed by {@code #}) record measurement params
     * for round-trip fidelity but the loader only needs the data rows.
     */
    public void saveCsv(StereoFreqRespCalibration stereo, String path,
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
            pw.printf(Locale.US, "# format_version=6%n");
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
        }
    }

    /**
     * Reads a stereo filter calibration file written by {@link #saveCsv}.
     * Expects the 5-column format defined there; throws when the file is
     * empty or rows fewer than 5 columns.  Field separator is comma, but
     * legacy semicolon-separated files (older format) are also accepted
     * so old in-flight measurements still round-trip while migrating.
     */
    public StereoFreqRespCalibration loadCsv(String path) throws IOException {
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
     * Mutates {@code r} in place: divides every FFT bin (within the swept range)
     * by H(f) interpolated from the per-point filter calibration, then calls
     * {@link FftAnalyzer#recomputeStats} so the fundamental level, harmonic table,
     * THD, THD+N, SNR, and noise stats all reflect the corrected spectrum.
     */
    public void applyCompensationInPlace(FftResult r, FreqRespCalibration cal,
                                         boolean correctAllBins) {
        int half = r.fftSize / 2;
        double binWidth = r.sampleRate / (double) r.fftSize;
        double fLo = cal.freqs[0];
        double fHi = cal.freqs[cal.freqs.length - 1];

        double oldFundMag = Math.hypot(r.re[r.fundamentalBin], r.im[r.fundamentalBin]);
        double linPerMag  = oldFundMag > 0.0 ? r.fundamentalLinear / oldFundMag : 0.0;

        // Match the dot-level / THD computation: only correct the two
        // FFT bins straddling the theoretical fundamental / harmonic
        // fractional position (kFractional × N).  The old ±LEAKAGE_BINS
        // (=4) expansion lifted 9 bins per harmonic by 1/H(f); at the
        // notch frequencies in the calibration that's a huge multiplier
        // applied to noise-only neighbours, producing wide spurious
        // humps around each harmonic instead of a single sharp 2-bin
        // peak that reflects the actual harmonic energy.
        int corrected = 0;
        if (correctAllBins) {
            // "With noise": every in-range bin divided by H at its OWN
            // frequency — the whole spectrum, noise included, is corrected.
            for (int k = 1; k <= half; k++) {
                double f = k * binWidth;
                if (f < fLo || f > fHi) continue;
                double[] h = interpolate(cal, f);
                double hMag = h[0];
                if (hMag <= 0.0) continue;
                double hRe = hMag * Math.cos(h[1]), hIm = hMag * Math.sin(h[1]);
                double hMagSq = hMag * hMag;
                double xRe = r.re[k], xIm = r.im[k];
                double zRe = (xRe * hRe + xIm * hIm) / hMagSq;
                double zIm = (xIm * hRe - xRe * hIm) / hMagSq;
                r.re[k] = zRe;
                r.im[k] = zIm;
                double newAmpLin = Math.hypot(zRe, zIm) * linPerMag;
                r.amplitudeDbFs[k] = newAmpLin > 1e-15 ? 20.0 * Math.log10(newAmpLin) : -300.0;
                r.phaseDeg[k] = Math.toDegrees(Math.atan2(zIm, zRe));
                corrected++;
            }
        } else {
            // Per-TONE: a tone is a SINGLE frequency whose energy fills the
            // window's whole main lobe.  Correct the WHOLE lobe (extent found
            // from the data) by ONE cal value at the tone frequency, applied
            // PROPORTIONALLY to the signal above the local noise floor — so the
            // lobe lifts as a unit while its wings stay on the floor.  This
            // replaces lifting only 1-2 bins (a narrow spike on the wider lobe)
            // and the per-bin slope (the split + the lobe-tilt frequency shift).
            boolean[] done = new boolean[half + 1];
            double fundHz = (Double.isFinite(r.fundamentalHzRefined) && r.fundamentalHzRefined > 0.0)
                    ? r.fundamentalHzRefined : r.fundamentalBin * binWidth;
            corrected += correctToneLobe(r, cal, fundHz, half, binWidth, linPerMag, fLo, fHi, done);
            for (int h = 0; h < r.harmonicCount; h++) {
                if (r.harmonicBins[h] > 0) {
                    corrected += correctToneLobe(r, cal, r.harmonicHz[h], half, binWidth, linPerMag, fLo, fHi, done);
                }
            }
            // Second tone (dual-tone) is a fundamental, not a harmonic of F1.
            if (!Double.isNaN(r.fundamental2HzRefined) && r.fundamental2HzRefined > 0.0) {
                corrected += correctToneLobe(r, cal, r.fundamental2HzRefined, half, binWidth, linPerMag, fLo, fHi, done);
            }
        }

        new FftAnalyzer().recomputeStats(r);

        if (!Double.isNaN(cal.adcFsVoltageRms) && cal.adcFsVoltageRms > 0.0) {
            r.fundRefDbV = r.fundamentalDbFs + 20.0 * Math.log10(cal.adcFsVoltageRms);
        }

        log.info("Filter compensation applied to {} bins (out of {}, range {}-{} Hz); "
                        + "fundamental: {} dBFS / {} dBV, SNR: {} dB, THD: {}%",
                corrected, half,
                String.format(Locale.US, "%.3f", fLo),
                String.format(Locale.US, "%.3f", fHi),
                String.format(Locale.US, "%.2f", r.fundamentalDbFs),
                Double.isNaN(r.fundRefDbV) ? "n/a"
                        : String.format(Locale.US, "%.2f", r.fundRefDbV),
                String.format(Locale.US, "%.2f", r.snrDb),
                String.format(Locale.US, "%.6f", r.thdPct));
    }

    /** Applies the cal to a tone's WHOLE main lobe with ONE value taken at the
     *  tone's frequency, in the POWER domain so the lobe keeps its shape where
     *  it stands above the noise yet tapers smoothly back onto the floor at its
     *  wings:
     *  <pre>  |X'| = √( (|X|² − floor²)·(1/|H|)² + floor² )  </pre>
     *  The signal power above the floor is scaled by (1/|H|)² and the noise
     *  floor power is kept, so every well-above-floor bin lifts by the same
     *  1/|H| — the lobe shape is preserved (no per-bin split, no
     *  frequency-shifting tilt, and no narrow spike from lifting the peak more
     *  than the shoulders) — while near-floor bins stay on the floor (no noise
     *  hump).  Phase is rotated by {@code −argH}.  Lobe extent is data-derived
     *  ({@link #lobeEdge}). */
    private int correctToneLobe(FftResult r, FreqRespCalibration cal, double toneHz,
                                int half, double binWidth, double linPerMag,
                                double fLo, double fHi, boolean[] done) {
        if (!(toneHz > 0.0) || toneHz < fLo || toneHz > fHi) return 0;
        int peak = (int) Math.round(toneHz / binWidth);
        if (peak < 1 || peak > half) return 0;
        double[] h = interpolate(cal, toneHz);
        if (h[0] <= 0.0) return 0;
        double invMag = 1.0 / h[0];                          // |1/H| at the tone freq
        double cosC = Math.cos(h[1]), sinC = Math.sin(h[1]); // ·exp(−j·argH)

        IntToDoubleFunction mag = k -> Math.hypot(r.re[k], r.im[k]);
        double floor = LOBE.localFloor(mag, peak, half);
        int[] edges  = LOBE.lobeBins(mag, peak, half, floor);
        double peakMag = mag.applyAsDouble(peak);

        int n = 0;
        for (int k = edges[0]; k <= edges[1]; k++) {
            if (k < 1 || k > half || done[k]) continue;
            double xRe = r.re[k], xIm = r.im[k];
            double m = Math.hypot(xRe, xIm);
            double newMag = LOBE.stretch(m, floor, peakMag, invMag);
            double scale  = m > 0.0 ? newMag / m : 0.0;
            double zRe = (xRe * cosC + xIm * sinC) * scale;  // X·exp(−j·argH)·scale
            double zIm = (xIm * cosC - xRe * sinC) * scale;
            r.re[k] = zRe;
            r.im[k] = zIm;
            double newAmpLin = newMag * linPerMag;
            r.amplitudeDbFs[k] = newAmpLin > 1e-15 ? 20.0 * Math.log10(newAmpLin) : -300.0;
            r.phaseDeg[k] = Math.toDegrees(Math.atan2(zIm, zRe));
            done[k] = true;
            n++;
        }
        return n;
    }

    /**
     * Sets {@code result.fundRefDbV} from the global ADC full-scale RMS voltage
     * when no other source has anchored it.  No-op when fundRefDbV is already set.
     */
    public void applyDefaultDbvScaling(FftResult result) {
        if (Double.isNaN(result.fundRefDbV) && AudioBackend.getAdcFsVoltageRms() > 0.0) {
            result.fundRefDbV = result.fundamentalDbFs
                    + 20.0 * Math.log10(AudioBackend.getAdcFsVoltageRms());
        }
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
