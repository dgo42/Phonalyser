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

package org.edgo.audio.measure.cli.util;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.edgo.audio.measure.fft.Fft;
import org.edgo.audio.measure.fft.FftResult;

import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;

@Log4j2
@UtilityClass
public class AdcCorrectionHelper {

    /**
     * Parses an adc_correction CSV (kind=adc_correction) written by the deembed
     * mode.  Supports both the multi-order format with an explicit {@code # orders=…}
     * header and the legacy single-order (h-1) format with a 4-column row.
     */
    public AdcCorrection loadCsv(String path) throws IOException {
        List<OrderToken> orders = null;
        Map<Integer, double[]> m = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#")) {
                    String body = line.substring(1).trim();
                    int eq = body.indexOf('=');
                    if (eq > 0 && body.substring(0, eq).trim().equals("orders")) {
                        orders = OrderToken.parseList(body.substring(eq + 1).trim());
                    }
                    continue;
                }
                if (!Character.isDigit(line.charAt(0))) continue;
                String[] cols = line.split(";");
                if (cols.length < 4) continue;
                int h = Integer.parseInt(cols[0].trim());
                int nCoeffs = (cols.length - 2) / 2;
                double[] ab = new double[2 * nCoeffs];
                for (int k = 0; k < nCoeffs; k++) {
                    ab[2 * k]     = Double.parseDouble(cols[2 + 2 * k].trim().replace(',', '.'));
                    ab[2 * k + 1] = Double.parseDouble(cols[3 + 2 * k].trim().replace(',', '.'));
                }
                m.put(h, ab);
            }
        }
        if (orders == null) orders = OrderToken.parseList("h-1");   // legacy single-order fallback
        return new AdcCorrection(orders, m);
    }

    /**
     * Applies multi-order ADC correction to an FFT result and returns
     * per-harmonic corrected amplitudes plus corrected THD.  Predicted bin
     * contribution is {@code [Σ_k c_k · L^{p_k(h)}] · |R_1| · exp(j·h·(φ₁+π/2))},
     * matching the rotated-frame convention used during calibration.
     */
    public double[] applyToResult(FftResult r, AdcCorrection adc,
                                  double[] outCorrectedAmpLin) {
        double phi1     = Math.atan2(r.im[r.fundamentalBin], r.re[r.fundamentalBin]);
        double r1Amp    = Math.hypot(r.re[r.fundamentalBin], r.im[r.fundamentalBin]);
        double L        = r.fundamentalLinear;
        double sumPow   = 0.0;
        List<OrderToken> orders = adc.getOrders();
        for (int h = 0; h < r.harmonicCount; h++) {
            int    bin = r.harmonicBins[h];
            int    n   = h + 2;
            double[] ab = adc.getCoeffs().get(n);
            if (bin <= 0 || ab == null) {
                outCorrectedAmpLin[h] = bin > 0
                        ? r.fundamentalLinear * r.harmonicPct[h] / 100.0
                        : 0.0;
                if (Math.min(r.harmonicCount, 8) > h) sumPow += outCorrectedAmpLin[h] * outCorrectedAmpLin[h];
                continue;
            }
            double abSumRe = 0.0, abSumIm = 0.0;
            for (int k = 0; k < orders.size(); k++) {
                double lp = Math.pow(L, orders.get(k).resolve(n));
                abSumRe += ab[2 * k]     * lp;
                abSumIm += ab[2 * k + 1] * lp;
            }
            double theta  = n * (phi1 + Math.PI / 2.0);
            double cosT   = Math.cos(theta);
            double sinT   = Math.sin(theta);
            double predRe = r1Amp * (abSumRe * cosT - abSumIm * sinT);
            double predIm = r1Amp * (abSumRe * sinT + abSumIm * cosT);
            double cRe    = r.re[bin] - predRe;
            double cIm    = r.im[bin] - predIm;
            double cAmp   = Math.hypot(cRe, cIm) * L / r1Amp;
            outCorrectedAmpLin[h] = cAmp;
            if (h < Math.min(r.harmonicCount, 8)) sumPow += cAmp * cAmp;
        }
        double thdPct = L > 0 ? Math.sqrt(sumPow) / L * 100.0 : 0.0;
        double thdDb  = thdPct > 0 ? 20.0 * Math.log10(thdPct / 100.0) : -300.0;
        return new double[]{thdPct, thdDb};
    }

    /**
     * Mutates {@code r} in place: replaces every harmonic bin's complex value
     * (and its derived amplitude / phase / harmonic-table entries) with the
     * ADC-corrected residual, and recomputes {@code thdPct} / {@code thdDb}
     * from the corrected harmonic amplitudes.
     */
    public void applyToResultInPlace(FftResult r, AdcCorrection adc) {
        double phi1     = Math.atan2(r.im[r.fundamentalBin], r.re[r.fundamentalBin]);
        double r1Amp    = Math.hypot(r.re[r.fundamentalBin], r.im[r.fundamentalBin]);
        double L        = r.fundamentalLinear;
        double linPerMag = r1Amp > 0 ? L / r1Amp : 0.0;
        List<OrderToken> orders = adc.getOrders();
        double snrLo = r.snrFreqMin > 0.0 ? r.snrFreqMin : 0.0;
        double snrHi = r.snrFreqMax > 0.0 ? r.snrFreqMax : Double.MAX_VALUE;
        double harmPowerSum = 0.0;
        for (int h = 0; h < r.harmonicCount; h++) {
            int    bin = r.harmonicBins[h];
            int    n   = h + 2;
            double[] ab = adc.getCoeffs().get(n);
            if (bin <= 0 || ab == null) {
                if (bin > 0 && h < Math.min(r.harmonicCount, 8)
                        && r.harmonicHz[h] >= snrLo && r.harmonicHz[h] <= snrHi) {
                    double a = L * r.harmonicPct[h] / 100.0;
                    harmPowerSum += a * a;
                }
                continue;
            }
            double abSumRe = 0.0, abSumIm = 0.0;
            for (int k = 0; k < orders.size(); k++) {
                double lp = Math.pow(L, orders.get(k).resolve(n));
                abSumRe += ab[2 * k]     * lp;
                abSumIm += ab[2 * k + 1] * lp;
            }
            double theta  = n * (phi1 + Math.PI / 2.0);
            double cosT   = Math.cos(theta);
            double sinT   = Math.sin(theta);
            double predRe = r1Amp * (abSumRe * cosT - abSumIm * sinT);
            double predIm = r1Amp * (abSumRe * sinT + abSumIm * cosT);
            double newRe  = r.re[bin] - predRe;
            double newIm  = r.im[bin] - predIm;
            r.re[bin]     = newRe;
            r.im[bin]     = newIm;
            double newMag       = Math.hypot(newRe, newIm);
            double newAmplLin   = newMag * linPerMag;
            r.amplitudeDbFs[bin] = newAmplLin > 1e-15
                    ? 20.0 * Math.log10(newAmplLin)
                    : -300.0;
            r.phaseDeg[bin]      = Math.toDegrees(Math.atan2(newIm, newRe));
            r.harmonicDbFs[h]    = r.amplitudeDbFs[bin];
            r.harmonicPct[h]     = L > 0 ? newAmplLin / L * 100.0 : 0.0;
            if (h < Math.min(r.harmonicCount, 8)
                    && r.harmonicHz[h] >= snrLo && r.harmonicHz[h] <= snrHi) {
                harmPowerSum += newAmplLin * newAmplLin;
            }
        }
        r.thdPct = L > 0 ? Math.sqrt(harmPowerSum) / L * 100.0 : 0.0;
        r.thdDb  = r.thdPct > 0 ? 20.0 * Math.log10(r.thdPct / 100.0) : -300.0;
    }

    /**
     * Logs raw vs. ADC-corrected harmonic amplitudes and THD for an FFT result.
     */
    public void logCorrectedHarmonics(FftResult r, AdcCorrection adc) {
        double[] corrAmp = new double[r.harmonicCount];
        double[] thd     = applyToResult(r, adc, corrAmp);
        double L         = r.fundamentalLinear;
        log.info("--- ADC-corrected harmonics --------------------------------");
        for (int h = 0; h < r.harmonicCount; h++) {
            if (r.harmonicBins[h] <= 0) continue;
            double rawAmp  = L * r.harmonicPct[h] / 100.0;
            double rawDb   = rawAmp > 0 ? 20.0 * Math.log10(rawAmp) : -300.0;
            double corrDb  = corrAmp[h] > 0 ? 20.0 * Math.log10(corrAmp[h]) : -300.0;
            double rawPct  = r.harmonicPct[h];
            double corrPct = L > 0 ? corrAmp[h] / L * 100.0 : 0.0;
            log.info("H{}  raw {} dBFS ({} %)   corrected {} dBFS ({} %)",
                    String.format(Locale.US, "%2d", h + 2),
                    String.format(Locale.US, "%8.3f", rawDb),
                    String.format(Locale.US, "%.6f", rawPct),
                    String.format(Locale.US, "%8.3f", corrDb),
                    String.format(Locale.US, "%.6f", corrPct));
        }
        log.info("THD raw       : {} %  ({} dB)",
                String.format(Locale.US, "%.8f", r.thdPct),
                String.format(Locale.US, "%.4f", r.thdDb));
        log.info("THD corrected : {} %  ({} dB)",
                String.format(Locale.US, "%.8f", thd[0]),
                String.format(Locale.US, "%.4f", thd[1]));
    }

    /**
     * Per-frame ADC time-domain distortion subtraction: each frame
     * (HANN-windowed, 0 % overlap, same layout as the analysis FFT) extracts
     * its own {@code phi1} and {@code r1Amp}, then synthesises and subtracts
     * a frame-local correction sinusoid.
     */
    public void subtractDistortionPerFrameInPlace(
            double[] samples, int sampleRate, int fftSize,
            FftResult r, AdcCorrection adc) {
        if (samples.length < fftSize) return;

        double[] window = new double[fftSize];
        double   wsum   = 0.0;
        for (int i = 0; i < fftSize; i++) {
            window[i] = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (fftSize - 1)));
            wsum += window[i];
        }
        double linPerMag = 2.0 / wsum;

        int    fundBin = r.fundamentalBin;
        int    hCount  = r.harmonicCount;
        int[]  hBins   = r.harmonicBins;
        List<OrderToken> orders = adc.getOrders();
        double w       = 2.0 * Math.PI * r.fundamentalHzRefined / sampleRate;

        int frameCount = samples.length / fftSize;
        double[] re = new double[fftSize];
        double[] im = new double[fftSize];
        double[] amp   = new double[hCount];
        double[] phase = new double[hCount];
        boolean[] used = new boolean[hCount];

        double lastPhi1 = 0.0, lastL = 0.0;
        boolean haveLast = false;

        for (int f = 0; f < frameCount; f++) {
            int start = f * fftSize;
            for (int j = 0; j < fftSize; j++) {
                re[j] = samples[start + j] * window[j];
                im[j] = 0.0;
            }
            Fft.forward(re, im);
            double phi1F  = Math.atan2(im[fundBin], re[fundBin]);
            double r1AmpF = Math.hypot(re[fundBin], im[fundBin]);
            double LF     = r1AmpF * linPerMag;
            lastPhi1 = phi1F; lastL = LF; haveLast = true;

            for (int h = 0; h < hCount; h++) {
                int n = h + 2;
                double[] ab = adc.getCoeffs().get(n);
                if (ab == null || hBins[h] <= 0) { used[h] = false; continue; }
                double abSumRe = 0.0, abSumIm = 0.0;
                for (int k = 0; k < orders.size(); k++) {
                    double lp = Math.pow(LF, orders.get(k).resolve(n));
                    abSumRe += ab[2 * k]     * lp;
                    abSumIm += ab[2 * k + 1] * lp;
                }
                amp[h]   = Math.hypot(abSumRe, abSumIm) * LF;
                phase[h] = Math.atan2(abSumIm, abSumRe) + n * (phi1F + Math.PI / 2.0);
                used[h]  = true;
            }
            for (int j = 0; j < fftSize; j++) {
                double corr = 0.0;
                for (int h = 0; h < hCount; h++) {
                    if (!used[h]) continue;
                    int n = h + 2;
                    corr += amp[h] * Math.cos(n * w * j + phase[h]);
                }
                samples[start + j] = samples[start + j] - corr;
            }
        }

        int trailingStart = frameCount * fftSize;
        if (trailingStart < samples.length && haveLast) {
            for (int h = 0; h < hCount; h++) {
                int n = h + 2;
                double[] ab = adc.getCoeffs().get(n);
                if (ab == null || hBins[h] <= 0) { used[h] = false; continue; }
                double abSumRe = 0.0, abSumIm = 0.0;
                for (int k = 0; k < orders.size(); k++) {
                    double lp = Math.pow(lastL, orders.get(k).resolve(n));
                    abSumRe += ab[2 * k]     * lp;
                    abSumIm += ab[2 * k + 1] * lp;
                }
                amp[h]   = Math.hypot(abSumRe, abSumIm) * lastL;
                phase[h] = Math.atan2(abSumIm, abSumRe) + n * (lastPhi1 + Math.PI / 2.0);
                used[h]  = true;
            }
            for (int i = trailingStart; i < samples.length; i++) {
                int j = i - trailingStart;
                double corr = 0.0;
                for (int h = 0; h < hCount; h++) {
                    if (!used[h]) continue;
                    int n = h + 2;
                    corr += amp[h] * Math.cos(n * w * j + phase[h]);
                }
                samples[i] = samples[i] - corr;
            }
        }
    }
}
