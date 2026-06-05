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

package org.edgo.audio.measure.cli;

import org.edgo.audio.measure.cli.util.*;

import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.generator.SignalGenerator;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code --deembed} — separates DAC and ADC contributions to measured harmonic
 * distortion via least-squares fit across multiple drive levels.
 *
 * <p>Takes N ≥ 2 applied_compensation CSVs (captured at distinct ADC fundamental
 * levels with the same DAC drive) and, for every harmonic h ≥ 2, fits the
 * complex model {@code r_h(L) = c_0 + Σ_k c_k · L^{p_k(h)}} where {@code p_k}
 * is the k-th ADC order from {@code --orders} (default {@code "h-1"}).
 * {@code c_0} is the level-independent DAC-fixed term (written to
 * {@code --gen-out}); the remaining {@code c_k} are the level-dependent ADC
 * coefficients (written to {@code --adc-out}).
 *
 * <p>The DAC compensation CSV feeds {@code --signal sine_compensated} to
 * pre-distort the generator; the ADC correction CSV feeds {@code --adc-comp}
 * to subtract ADC contribution from an FFT result post-capture.
 *
 * <p>Requires N ≥ 1 + |orders| CSVs; with more it solves the overdetermined
 * system via normal equations.
 */
@Log4j2
@UtilityClass
public class DeembedMode {

    public void run(String[] args) throws Exception {
        List<String> compPaths = new ArrayList<>(ArgParser.getArgValues(args, "--comp"));
        String comp1Arg = ArgParser.getArgValue(args, "--comp1");
        String comp2Arg = ArgParser.getArgValue(args, "--comp2");
        if (comp1Arg != null) compPaths.add(0, comp1Arg);
        if (comp2Arg != null) compPaths.add(Math.min(1, compPaths.size()), comp2Arg);

        List<String> levelArgs = new ArrayList<>(ArgParser.getArgValues(args, "--level"));
        String level1Arg = ArgParser.getArgValue(args, "--level1");
        String level2Arg = ArgParser.getArgValue(args, "--level2");
        if (level1Arg != null) levelArgs.add(0, level1Arg);
        if (level2Arg != null) levelArgs.add(Math.min(1, levelArgs.size()), level2Arg);

        String ordersArg = ArgParser.getArgValue(args, "--orders");
        String genOutArg = ArgParser.getArgValue(args, "--gen-out");
        String adcOutArg = ArgParser.getArgValue(args, "--adc-out");

        if (compPaths.size() < 2 || genOutArg == null || adcOutArg == null) {
            log.error("--deembed requires --gen-out, --adc-out and at least 2 --comp <file> CSVs");
            System.exit(1);
        }

        List<OrderToken> orders = OrderToken.parseList(ordersArg != null ? ordersArg : "h-1");
        int nCsv      = compPaths.size();
        int nCoeffs   = 1 + orders.size();
        if (nCsv < nCoeffs) {
            log.error("Need at least {} CSVs for {} coefficients (c_0 + orders={}), got {}",
                    nCoeffs, nCoeffs, orders, nCsv);
            System.exit(1);
        }

        CompCsv[] csvs  = new CompCsv[nCsv];
        double[]  dbFs  = new double[nCsv];
        double[]  L     = new double[nCsv];
        for (int i = 0; i < nCsv; i++) {
            csvs[i] = readCompensationCsv(compPaths.get(i));
            dbFs[i] = i < levelArgs.size() ? Double.parseDouble(levelArgs.get(i))
                                           : csvs[i].getFundamentalDbFs();
            L[i]    = Math.pow(10.0, dbFs[i] / 20.0);
        }

        log.info("Mode      : deembed DAC ↔ ADC (N={}, orders=[{}])", nCsv, OrderToken.renderList(orders));
        for (int i = 0; i < nCsv; i++) {
            log.info("CSV {} : {}  (L = {} dBFS = {})", i + 1, compPaths.get(i),
                    String.format(Locale.US, "%.4f", dbFs[i]),
                    String.format(Locale.US, "%.6f", L[i]));
        }

        double minL = L[0], maxL = L[0];
        for (double v : L) { if (v < minL) minL = v; if (v > maxL) maxL = v; }
        if (maxL - minL < 1e-6) {
            log.error("All fundamental levels are identical — deembedding requires distinct levels");
            System.exit(1);
        }

        @SuppressWarnings("unchecked")
        Map<Integer, CompHarmonic>[] maps = new HashMap[nCsv];
        for (int i = 0; i < nCsv; i++) {
            maps[i] = new HashMap<>();
            for (CompHarmonic h : csvs[i].getHarmonics()) maps[i].put(h.getH(), h);
        }

        List<CompHarmonic>  dacRows     = new ArrayList<>();
        List<double[]>      adcCoeffs   = new ArrayList<>();
        List<Integer>       adcHList    = new ArrayList<>();
        List<Double>        adcFreqList = new ArrayList<>();

        for (CompHarmonic h1 : csvs[0].getHarmonics()) {
            int h = h1.getH();
            boolean present = true;
            for (int i = 1; i < nCsv; i++) if (!maps[i].containsKey(h)) { present = false; break; }
            if (!present) {
                log.warn("H{} missing in at least one CSV — skipped", h);
                continue;
            }

            double[][] A     = new double[nCsv][nCoeffs];
            double[]   yRe   = new double[nCsv];
            double[]   yIm   = new double[nCsv];
            for (int i = 0; i < nCsv; i++) {
                CompHarmonic row = maps[i].get(h);
                A[i][0] = 1.0;
                for (int k = 0; k < orders.size(); k++) {
                    A[i][k + 1] = Math.pow(L[i], orders.get(k).resolve(h));
                }
                yRe[i] = row.getRe();
                yIm[i] = row.getIm();
            }
            double[] cRe = solveLeastSquares(A, yRe);
            double[] cIm = solveLeastSquares(A, yIm);

            dacRows.add(new CompHarmonic(h, h1.getFreqHz(), cRe[0], cIm[0]));
            double[] ab = new double[2 * orders.size()];
            for (int k = 0; k < orders.size(); k++) {
                ab[2 * k]     = cRe[k + 1];
                ab[2 * k + 1] = cIm[k + 1];
            }
            adcCoeffs.add(ab);
            adcHList.add(h);
            adcFreqList.add(h1.getFreqHz());

            double sumResSq = 0.0;
            double peakResMag = 0.0;
            for (int i = 0; i < nCsv; i++) {
                double predRe = 0.0, predIm = 0.0;
                for (int k = 0; k < nCoeffs; k++) {
                    predRe += A[i][k] * cRe[k];
                    predIm += A[i][k] * cIm[k];
                }
                double rRe = yRe[i] - predRe;
                double rIm = yIm[i] - predIm;
                double rMag = Math.hypot(rRe, rIm);
                sumResSq += rMag * rMag;
                if (rMag > peakResMag) peakResMag = rMag;
            }
            double rmsRes = Math.sqrt(sumResSq / nCsv);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.US, "|D|/|r_1|=%.3f", Math.hypot(cRe[0], cIm[0])));
            for (int k = 0; k < orders.size(); k++) {
                double mag = Math.hypot(cRe[k + 1], cIm[k + 1])
                        * Math.pow(L[0], orders.get(k).resolve(h));
                sb.append(String.format(Locale.US, "  |c_%s·L^%d|/|r_1|=%.3f",
                        orders.get(k).getLabel(), orders.get(k).resolve(h), mag));
            }
            double rmsResDb  = 20.0 * Math.log10(Math.max(1e-300, rmsRes));
            double peakResDb = 20.0 * Math.log10(Math.max(1e-300, peakResMag));
            sb.append(String.format(Locale.US, "  resid_rms=%.1fdBc  resid_peak=%.1fdBc",
                    rmsResDb, peakResDb));
            log.info("H{} : {}", h, sb);
        }

        writeDacCompensationCsv(dacRows, csvs[0], genOutArg);
        writeAdcCorrectionCsv(adcHList, adcFreqList, adcCoeffs, orders, csvs[0],
                adcOutArg, dbFs);
        log.info("Wrote DAC compensation : {}", genOutArg);
        log.info("Wrote ADC correction   : {}", adcOutArg);
    }

    /** Reads an applied_compensation CSV.  Tolerates German (comma) decimals. */
    private CompCsv readCompensationCsv(String path) throws IOException {
        SignalGenerator.Metadata meta = SignalGenerator.readAppliedCompensationMetadata(path);
        double fundDbFs = Double.NaN;
        double fundHz   = Double.NaN;
        List<CompHarmonic> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || !Character.isDigit(line.charAt(0))) continue;
                String[] cols = line.split(";");
                if (cols.length < 7) continue;
                int    h    = Integer.parseInt(cols[0].trim());
                double freq = Double.parseDouble(cols[1].trim().replace(',', '.'));
                double re   = Double.parseDouble(cols[5].trim().replace(',', '.'));
                double im   = Double.parseDouble(cols[6].trim().replace(',', '.'));
                if (h == 1) {
                    fundDbFs = Double.parseDouble(cols[2].trim().replace(',', '.'));
                    fundHz   = freq;
                } else {
                    rows.add(new CompHarmonic(h, freq, re, im));
                }
            }
        }
        return new CompCsv(meta, fundDbFs, fundHz, rows);
    }

    /**
     * Solves {@code A·c = y} in the least-squares sense via the normal
     * equations {@code (AᵀA)·c = Aᵀy}.
     */
    private double[] solveLeastSquares(double[][] a, double[] y) {
        int    n   = a.length;
        int    k   = a[0].length;
        double[][] ata = new double[k][k];
        double[]   aty = new double[k];
        for (int i = 0; i < n; i++) {
            for (int p = 0; p < k; p++) {
                aty[p] += a[i][p] * y[i];
                for (int q = 0; q < k; q++) ata[p][q] += a[i][p] * a[i][q];
            }
        }
        for (int i = 0; i < k; i++) {
            int    piv  = i;
            double maxv = Math.abs(ata[i][i]);
            for (int j = i + 1; j < k; j++) {
                if (Math.abs(ata[j][i]) > maxv) { maxv = Math.abs(ata[j][i]); piv = j; }
            }
            if (piv != i) {
                double[] tmp = ata[i]; ata[i] = ata[piv]; ata[piv] = tmp;
                double bt = aty[i]; aty[i] = aty[piv]; aty[piv] = bt;
            }
            double pv = ata[i][i];
            if (Math.abs(pv) < 1e-30) throw new IllegalStateException("Singular matrix in deembed least-squares");
            for (int j = i + 1; j < k; j++) {
                double f = ata[j][i] / pv;
                for (int p = i; p < k; p++) ata[j][p] -= f * ata[i][p];
                aty[j] -= f * aty[i];
            }
        }
        double[] x = new double[k];
        for (int i = k - 1; i >= 0; i--) {
            double s = aty[i];
            for (int j = i + 1; j < k; j++) s -= ata[i][j] * x[j];
            x[i] = s / ata[i][i];
        }
        return x;
    }

    private void writeDacCompensationCsv(List<CompHarmonic> rows, CompCsv source,
                                         String path) throws IOException {
        SignalGenerator.Metadata meta = source.getMeta();
        try (PrintWriter pw = new PrintWriter(
                new BufferedWriter(new FileWriter(path)))) {
            if (meta != null) {
                pw.printf(Locale.US, "# sample_rate_hz=%d%n",   meta.getSampleRateHz());
                pw.printf(Locale.US, "# bit_depth=%d%n",        meta.getBitDepth());
                pw.printf(Locale.US, "# amplitude_vrms=%.10f%n", meta.getAmplitudeVRms());
                pw.printf(Locale.US, "# frequency_hz=%.10f%n",   meta.getFrequencyHz());
            }
            pw.println("harmonic;frequency_hz;amplitude_dbfs;amplitude_pct;phase_deg;re;im");
            pw.printf(Locale.GERMAN, "1;%.6f;%.4f;100.000000;-90.0000;%.10e;%.10e%n",
                    source.getFundamentalHz(), source.getFundamentalDbFs(), 0.0, -1.0);
            for (CompHarmonic r : rows) {
                double amp = Math.hypot(r.getRe(), r.getIm());
                if (amp == 0.0) continue;
                double phaseDeg = Math.toDegrees(Math.atan2(r.getIm(), r.getRe()));
                double ampPct   = amp * 100.0;
                double ampDbFs  = source.getFundamentalDbFs() + 20.0 * Math.log10(amp);
                pw.printf(Locale.GERMAN, "%d;%.6f;%.4f;%.9f;%.4f;%.10e;%.10e%n",
                        r.getH(), r.getFreqHz(), ampDbFs, ampPct, phaseDeg, r.getRe(), r.getIm());
            }
        }
    }

    private void writeAdcCorrectionCsv(List<Integer> hList, List<Double> freqList,
                                       List<double[]> coeffs, List<OrderToken> orders,
                                       CompCsv source, String path,
                                       double[] levelsDbFs) throws IOException {
        SignalGenerator.Metadata meta = source.getMeta();
        try (PrintWriter pw = new PrintWriter(
                new BufferedWriter(new FileWriter(path)))) {
            pw.println("# kind=adc_correction");
            if (meta != null) {
                pw.printf(Locale.US, "# sample_rate_hz=%d%n", meta.getSampleRateHz());
                pw.printf(Locale.US, "# bit_depth=%d%n",      meta.getBitDepth());
            }
            pw.printf(Locale.US, "# fundamental_hz=%.10f%n", source.getFundamentalHz());
            StringBuilder lvl = new StringBuilder();
            for (int i = 0; i < levelsDbFs.length; i++) {
                if (i > 0) lvl.append(',');
                lvl.append(String.format(Locale.US, "%.4f", levelsDbFs[i]));
            }
            pw.printf(Locale.US, "# derived_from_levels_dbfs=%s%n", lvl);
            pw.printf(Locale.US, "# orders=%s%n", OrderToken.renderList(orders));
            pw.println("# Apply: harmonic h's predicted ADC contribution = sum_k (a_k_re + j*a_k_im) * L^p_k(h)");
            pw.println("# where p_k(h) is the k-th token of 'orders' resolved against h (literal or h+/-offset).");
            StringBuilder hdr = new StringBuilder("harmonic;frequency_hz");
            for (int k = 0; k < orders.size(); k++) {
                hdr.append(";a").append(k + 1).append("_re;a").append(k + 1).append("_im");
            }
            pw.println(hdr);
            for (int i = 0; i < hList.size(); i++) {
                StringBuilder row = new StringBuilder();
                row.append(String.format(Locale.GERMAN, "%d;%.6f", hList.get(i), freqList.get(i)));
                double[] ab = coeffs.get(i);
                for (int k = 0; k < orders.size(); k++) {
                    row.append(String.format(Locale.GERMAN, ";%.10e;%.10e", ab[2 * k], ab[2 * k + 1]));
                }
                pw.println(row);
            }
        }
    }
}
