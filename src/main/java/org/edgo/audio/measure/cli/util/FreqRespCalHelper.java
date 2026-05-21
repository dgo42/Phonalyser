package org.edgo.audio.measure.cli.util;

import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.fft.MathUtil;
import org.edgo.audio.measure.sound.AudioBackend;
import org.jtransforms.fft.DoubleFFT_1D;

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
    public void applyCompensationInPlace(FftAnalyzer.Result r, FreqRespCalibration cal,
                                         boolean correctAllBins) {
        int half = r.fftSize / 2;
        double binWidth = r.sampleRate / (double) r.fftSize;
        double fLo = cal.freqs[0];
        double fHi = cal.freqs[cal.freqs.length - 1];

        double oldFundMag = Math.hypot(r.re[r.fundamentalBin], r.im[r.fundamentalBin]);
        double linPerMag  = oldFundMag > 0.0 ? r.fundamentalLinear / oldFundMag : 0.0;

        final int LEAKAGE_BINS = 4;
        boolean[] correctBin = new boolean[half + 1];
        if (correctAllBins) {
            Arrays.fill(correctBin, true);
        } else {
            for (int d = -LEAKAGE_BINS; d <= LEAKAGE_BINS; d++) {
                int b = r.fundamentalBin + d;
                if (b > 0 && b <= half) correctBin[b] = true;
            }
            for (int hb : r.harmonicBins) {
                if (hb > 0) {
                    for (int d = -LEAKAGE_BINS; d <= LEAKAGE_BINS; d++) {
                        int b = hb + d;
                        if (b > 0 && b <= half) correctBin[b] = true;
                    }
                }
            }
        }

        int corrected = 0;
        for (int k = 1; k <= half; k++) {
            if (!correctBin[k]) continue;
            double f = k * binWidth;
            if (f < fLo || f > fHi) continue;

            double[] h = interpolate(cal, f);
            double hMag = h[0];
            if (hMag <= 0.0) continue;
            double hPhi = h[1];
            double cosP = Math.cos(hPhi);
            double sinP = Math.sin(hPhi);
            double hRe  = hMag * cosP;
            double hIm  = hMag * sinP;
            double hMagSq = hMag * hMag;

            double xRe = r.re[k];
            double xIm = r.im[k];
            double zRe = (xRe * hRe + xIm * hIm) / hMagSq;
            double zIm = (xIm * hRe - xRe * hIm) / hMagSq;
            r.re[k] = zRe;
            r.im[k] = zIm;
            double newAmpLin   = Math.hypot(zRe, zIm) * linPerMag;
            r.amplitudeDbFs[k] = newAmpLin > 1e-15
                    ? 20.0 * Math.log10(newAmpLin)
                    : -300.0;
            r.phaseDeg[k] = Math.toDegrees(Math.atan2(zIm, zRe));
            corrected++;
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

    /**
     * Sets {@code result.fundRefDbV} from the global ADC full-scale RMS voltage
     * when no other source has anchored it.  No-op when fundRefDbV is already set.
     */
    public void applyDefaultDbvScaling(FftAnalyzer.Result result) {
        if (Double.isNaN(result.fundRefDbV) && AudioBackend.getAdcFsVoltageRms() > 0.0) {
            result.fundRefDbV = result.fundamentalDbFs
                    + 20.0 * Math.log10(AudioBackend.getAdcFsVoltageRms());
        }
    }

    /**
     * Snapshots the pre-correction fundamental + harmonic peak levels so the
     * chart can draw blue "before-cal" dots alongside the red "after-cal" dots.
     */
    public double[][] capturePreCorrectionPeaks(FftAnalyzer.Result result) {
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
    public double[][] computeOverlay(FreqRespCalibration cal, FftAnalyzer.Result result) {
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
