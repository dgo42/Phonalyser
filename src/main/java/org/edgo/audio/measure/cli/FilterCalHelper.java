package org.edgo.audio.measure.cli;

import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.fft.FftAnalyzer;
import org.edgo.audio.measure.fft.MathUtil;
import org.edgo.audio.measure.generator.SignalGenerator;
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
public class FilterCalHelper {

    /**
     * Reconstructs the per-frequency filter calibration H(f) from the captured
     * recording of a Farina log-sweep by direct frequency-domain deconvolution:
     * {@code H = Y / X}.  Removes the DAC↔ADC transport delay (linear-phase term
     * located via the impulse-response peak) and normalises magnitude to the DAC
     * drive level so the passband sits at ≈ 1.0.
     */
    public FilterCalibration computeFromLogSweep(
            float[] yRec, float[] sweepRef, int leadInSamples,
            int sampleRate, double[] freqs, double amplitudeVRms) {
        int xLen   = leadInSamples + sweepRef.length;
        int needed = Math.max(yRec.length, xLen);
        int M      = MathUtil.nextPow2(needed);

        double[] xBuf = new double[M];
        for (int i = 0; i < sweepRef.length; i++) {
            xBuf[leadInSamples + i] = sweepRef[i];
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

        double dacDrivePeak = amplitudeVRms * Math.sqrt(2.0) / SignalGenerator.FS_VOLTAGE;
        if (dacDrivePeak > 0.0) {
            for (int i = 0; i < nPoints; i++) {
                magLin[i] /= dacDrivePeak;
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
        log.info("Filter cal: FFT M={} ({} bins, {} Hz/bin); transport delay {} samples ({} ms) removed",
                M, halfBins,
                String.format(Locale.US, "%.4f", binHz),
                String.format(Locale.US, "%.3f", delaySamples),
                String.format(Locale.US, "%.4f", 1000.0 * delaySamples / sampleRate));
        if (Double.isFinite(minDb)) {
            log.info("Filter cal: min {} dBFS @ {} Hz   max {} dBFS @ {} Hz",
                    String.format(Locale.US, "%.2f", minDb),
                    String.format(Locale.US, "%.3f", minF),
                    String.format(Locale.US, "%.2f", maxDb),
                    String.format(Locale.US, "%.3f", maxF));
        }

        return new FilterCalibration(freqs, magLin, phaseRad);
    }

    /**
     * Writes a filter calibration to CSV.  One row per measured sweep point.
     * Columns: {@code frequency_hz; magnitude_dbfs; magnitude_dbv; magnitude_db_rel; phase_deg}.
     */
    public void saveCsv(FilterCalibration cal, String path,
                        int sampleRate,
                        double sweepStart, double sweepEnd,
                        int sweepPoints,
                        double amplitudeVRms)
            throws IOException {
        File outFile = new File(path);
        File parent  = outFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        double dacDrivePeak  = amplitudeVRms * Math.sqrt(2.0) / SignalGenerator.FS_VOLTAGE;
        double dacDriveDbFs  = dacDrivePeak > 0.0 ? 20.0 * Math.log10(dacDrivePeak) : 0.0;
        try (PrintWriter pw = new PrintWriter(
                new BufferedWriter(new FileWriter(outFile)))) {
            pw.printf(Locale.US, "# kind=filter_calibration%n");
            pw.printf(Locale.US, "# format_version=4%n");
            pw.printf(Locale.US, "# sample_rate_hz=%d%n",  sampleRate);
            pw.printf(Locale.US, "# sweep_start_hz=%.6f%n", sweepStart);
            pw.printf(Locale.US, "# sweep_end_hz=%.6f%n",   sweepEnd);
            pw.printf(Locale.US, "# sweep_points=%d%n",     sweepPoints);
            pw.printf(Locale.US, "# adc_fs_voltage_rms=%.6f%n", AudioBackend.getAdcFsVoltageRms());
            pw.printf(Locale.US, "# dac_drive_v_rms=%.6f%n",    amplitudeVRms);
            pw.printf(Locale.US, "# dac_drive_dbfs=%.6f%n",     dacDriveDbFs);
            pw.println("frequency_hz;magnitude_dbfs;magnitude_dbv;magnitude_db_rel;phase_deg");
            double dbvScale = AudioBackend.getAdcFsVoltageRms();
            for (int i = 0; i < cal.freqs.length; i++) {
                double relH      = cal.magLin[i];
                double absMag    = relH * dacDrivePeak;
                double dbfs      = absMag > 0.0 ? 20.0 * Math.log10(absMag)              : -300.0;
                double dbv       = absMag > 0.0 ? 20.0 * Math.log10(absMag * dbvScale)   : -300.0;
                double dbRel     = relH   > 0.0 ? 20.0 * Math.log10(relH)                : -300.0;
                double phaseDeg  = Math.toDegrees(cal.phaseRad[i]);
                pw.printf(Locale.GERMAN, "%.6f;%.6f;%.6f;%.6f;%.4f%n",
                        cal.freqs[i], dbfs, dbv, dbRel, phaseDeg);
            }
        }
    }

    /**
     * Loads a per-point filter calibration CSV.  Reads {@code sample_rate_hz}
     * from header comments; row order defines ascending frequency.
     */
    public FilterCalibration loadCsv(String path) throws IOException {
        double adcFsVoltageRms = Double.NaN;
        List<double[]> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#")) {
                    String body = line.substring(1).trim();
                    int eq = body.indexOf('=');
                    if (eq <= 0) continue;
                    String key = body.substring(0, eq).trim();
                    String val = body.substring(eq + 1).trim();
                    if (key.equals("adc_fs_voltage_rms"))
                        adcFsVoltageRms = Double.parseDouble(val.replace(',', '.'));
                    continue;
                }
                if (!Character.isDigit(line.charAt(0))) continue;
                String[] cols = line.split(";");
                if (cols.length < 5) continue;
                double freq     = Double.parseDouble(cols[0].trim().replace(',', '.'));
                double dbRel    = Double.parseDouble(cols[3].trim().replace(',', '.'));
                double magLin   = Math.pow(10.0, dbRel / 20.0);
                double phaseRad = Math.toRadians(Double.parseDouble(cols[4].trim().replace(',', '.')));
                rows.add(new double[]{ freq, magLin, phaseRad });
            }
        }
        if (rows.isEmpty()) {
            throw new IOException("Filter calibration CSV has no data rows: " + path);
        }
        double[] freqs    = new double[rows.size()];
        double[] magLin   = new double[rows.size()];
        double[] phaseRad = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            freqs[i]    = rows.get(i)[0];
            magLin[i]   = rows.get(i)[1];
            phaseRad[i] = rows.get(i)[2];
        }
        FilterCalibration cal = new FilterCalibration(freqs, magLin, phaseRad);
        cal.adcFsVoltageRms = adcFsVoltageRms;
        return cal;
    }

    /**
     * Mutates {@code r} in place: divides every FFT bin (within the swept range)
     * by H(f) interpolated from the per-point filter calibration, then calls
     * {@link FftAnalyzer#recomputeStats} so the fundamental level, harmonic table,
     * THD, THD+N, SNR, and noise stats all reflect the corrected spectrum.
     */
    public void applyCompensationInPlace(FftAnalyzer.Result r, FilterCalibration cal,
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
    public double[][] computeOverlay(FilterCalibration cal, FftAnalyzer.Result result) {
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
    public double[] interpolate(FilterCalibration cal, double freq) {
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
