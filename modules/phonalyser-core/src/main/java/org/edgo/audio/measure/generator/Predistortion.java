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

package org.edgo.audio.measure.generator;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import lombok.extern.log4j.Log4j2;

/**
 * One saved predistortion file ({@code .dpd}) as correction tables - read once,
 * then handed to whichever generator is playing.
 *
 * <p><b>Why it is a type of its own.</b>  The file format is self-describing: a
 * single-tone (harmonic) file's column header begins {@code harmonic;...}, a
 * dual-tone (intermod) one begins {@code a;b;...}, and each parses into a
 * different set of phasors.  That knowledge used to live inside
 * {@link SignalGenerator}, where it could only ever be applied to the DDS in
 * THIS process - so a generator running on a remote bench had no way to be given
 * a saved correction at all, and the one thing standing between the two was a
 * private parser.  It is the tables that are shared, not the DDS, so they are
 * what became the object.
 *
 * <p>{@link #applyTo(GeneratorControls)} is the whole interface to them: the
 * file decides which of the two compensations it is, and the generator - local
 * or a network away - decides what applying one means.
 */
@Log4j2
public final class Predistortion {

    /** Columns a single-tone harmonic row must have before it is read at all. */
    private static final int HARMONIC_MIN_COLUMNS = 5;
    /** From this width the row carries {@code re}/{@code im} in full precision
     *  instead of only a degrees column. */
    private static final int HARMONIC_PHASOR_COLUMNS = 7;
    /** Columns a dual-tone intermod row must have. */
    private static final int INTERMOD_MIN_COLUMNS = 8;
    private static final int COL_HARMONIC = 0;
    private static final int COL_HARMONIC_FREQ = 1;
    private static final int COL_HARMONIC_AMP_PCT = 3;
    private static final int COL_HARMONIC_PHASE_DEG = 4;
    private static final int COL_HARMONIC_RE = 5;
    private static final int COL_HARMONIC_IM = 6;
    private static final int COL_INTERMOD_A = 0;
    private static final int COL_INTERMOD_B = 1;
    private static final int COL_INTERMOD_RE = 6;
    private static final int COL_INTERMOD_IM = 7;
    /** The fundamental's own row - its measured phase is the system-delay
     *  reference, not a correction. */
    private static final int FUNDAMENTAL_HARMONIC = 1;
    private static final String COMMENT_PREFIX = "#";
    private static final String COLUMN_SEPARATOR = ";";
    /** The column header of a dual-tone intermod file. */
    private static final String INTERMOD_HEADER = "a;b";

    /** Which of the two compensations this file carries. */
    private final boolean dualTone;
    /** Per-component amplitude ratios, relative to the fundamental. */
    private final double[] ampRatios;
    /** Single-tone: the harmonic numbers.  Empty for a dual-tone file. */
    private final int[] hNums;
    /** Dual-tone: the {@code a} and {@code b} coefficients of each
     *  {@code a·f₁ + b·f₂} product.  Empty for a single-tone file. */
    private final int[] aCoef;
    private final int[] bCoef;
    /** Per-component initial phase in radians. */
    private final double[] phiInits;

    /**
     * Reads {@code path}.
     *
     * @param frequency  the fundamental in Hz - the single-tone file's phases
     *                   are corrected for the system delay it was measured at,
     *                   and a dual-tone file ignores it (its products follow the
     *                   generator's live tone frequencies)
     * @param sampleRate the rate the measurement ran at; used only to report the
     *                   delay estimate in samples
     */
    public Predistortion(String path, double frequency, int sampleRate) throws IOException {
        this.dualTone = isIntermodFile(path);
        if (dualTone) {
            List<int[]> coefficients = new ArrayList<>();
            List<double[]> phasors = new ArrayList<>();
            readIntermod(path, coefficients, phasors);
            int n = coefficients.size();
            ampRatios = new double[n];
            phiInits = new double[n];
            aCoef = new int[n];
            bCoef = new int[n];
            hNums = new int[0];
            for (int i = 0; i < n; i++) {
                double re = phasors.get(i)[0];
                double im = phasors.get(i)[1];
                ampRatios[i] = Math.hypot(re, im);
                phiInits[i] = Math.atan2(im, re);
                aCoef[i] = coefficients.get(i)[COL_INTERMOD_A];
                bCoef[i] = coefficients.get(i)[COL_INTERMOD_B];
            }
            if (log.isInfoEnabled()) {
                log.info("Intermod compensation: {} product(s) loaded from {}", n, path);
            }
        } else {
            // [ampRatio, phi_measured (rad), freqHz, harmonicNumber] per harmonic.
            List<double[]> harmonics = new ArrayList<>();
            double phi1 = readHarmonics(path, harmonics);
            int n = harmonics.size();
            ampRatios = new double[n];
            hNums = new int[n];
            phiInits = new double[n];
            aCoef = new int[0];
            bCoef = new int[0];
            // System delay compensation:
            //   DDS sine at n=0 has FFT phase -π/2  ->  phi1_measured = -ωD - π/2
            //   ωD = -(phi1_measured + π/2)
            //
            // For harmonic h the correction must arrive at the ADC with phase
            // phi_h_measured.  It is sent from the DAC at phase phi_init and
            // arrives delayed by h·ωD:
            //   phi_init - h·ωD = phi_h_measured
            //   phi_init = phi_h_measured + h·ωD = phi_h_measured - h·(phi1 + π/2)
            double omegaD = -(phi1 + Math.PI / 2.0);   // ωD in radians at fundamental
            double delayRadPerHz = omegaD / frequency; // per-harmonic delay
            if (log.isInfoEnabled()) {
                log.info("System delay estimate: {} rad ({} samples at {} Hz)",
                        String.format(Locale.US, "%.2f", omegaD),
                        String.format(Locale.US, "%.4f",
                                omegaD / (2.0 * Math.PI * frequency / sampleRate)),
                        (double) sampleRate);
            }
            for (int i = 0; i < n; i++) {
                double[] h = harmonics.get(i);
                double ampRatio = h[0];
                double phiH = h[1];
                double freqHz = h[2];
                int hNumber = (int) h[3];   // harmonic number: 2, 3, 4, ...
                double phiInit = phiH + freqHz * delayRadPerHz;
                ampRatios[i] = ampRatio;
                hNums[i] = hNumber;
                phiInits[i] = phiInit;
                if (log.isInfoEnabled()) {
                    log.info("Harmonic compensation H{}: {} Hz  {} dBFS  phi_meas {} deg  phi_init {} deg",
                            hNumber,
                            String.format(Locale.US, "%.3f", freqHz),
                            String.format(Locale.US, "%.4f", 20.0 * Math.log10(ampRatio)),
                            String.format(Locale.US, "%.2f", Math.toDegrees(phiH)),
                            String.format(Locale.US, "%.2f", Math.toDegrees(phiInit)));
                }
            }
            if (log.isInfoEnabled()) {
                log.info("Harmonic compensation: {} harmonic(s) loaded from {}", n, path);
            }
        }
    }

    /**
     * Hands the tables to {@code target}, which switches to the matching
     * compensated waveform - the same call the predistortion wizard makes with
     * a round's freshly measured corrections, so a file and a live round reach
     * the generator by exactly one path.
     */
    public void applyTo(GeneratorControls target) {
        if (dualTone) {
            target.applyDualToneCompensation(ampRatios, aCoef, bCoef, phiInits);
        } else {
            target.applyCompensation(ampRatios, hNums, phiInits);
        }
    }

    /** True when {@code path}'s first non-comment line (the column header) marks
     *  a dual-tone intermod file ({@code a;b;...}) rather than a single-tone
     *  harmonic file ({@code harmonic;...}). */
    private boolean isIntermodFile(String path) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(COMMENT_PREFIX)) continue;
                return line.toLowerCase(Locale.ROOT).startsWith(INTERMOD_HEADER);
            }
        }
        return false;
    }

    /**
     * Parses an {@code fft_harmonics_*.csv} row set into {@code harmonics}.
     * Skips H1 (the fundamental) and returns its measured phase, which is the
     * system-delay reference.  Each phasor is read from the {@code re}/{@code im}
     * columns when present (full double precision), otherwise from
     * {@code phase_deg}.  The correction amplitude comes from
     * {@code amplitude_pct / 100}, so it is relative to the fundamental
     * regardless of the absolute DAC/ADC level.
     */
    private double readHarmonics(String path, List<double[]> harmonics) throws IOException {
        double phi1 = Double.NaN;
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                // skip blank lines, comments, and the header row
                if (line.isEmpty() || !Character.isDigit(line.charAt(0))) continue;
                String[] cols = line.split(COLUMN_SEPARATOR);
                if (cols.length < HARMONIC_MIN_COLUMNS) continue;
                int hIndex = Integer.parseInt(cols[COL_HARMONIC].trim());
                double phi;
                if (cols.length >= HARMONIC_PHASOR_COLUMNS) {
                    double re = number(cols[COL_HARMONIC_RE]);
                    double im = number(cols[COL_HARMONIC_IM]);
                    phi = Math.atan2(im, re);
                } else {
                    phi = Math.toRadians(number(cols[COL_HARMONIC_PHASE_DEG]));
                }
                if (hIndex == FUNDAMENTAL_HARMONIC) {
                    phi1 = phi;   // capture fundamental phase for delay compensation
                    continue;
                }
                double ampPct = number(cols[COL_HARMONIC_AMP_PCT]);
                double freqHz = number(cols[COL_HARMONIC_FREQ]);
                harmonics.add(new double[] {ampPct / 100.0, phi, freqHz, hIndex});
            }
        }
        if (Double.isNaN(phi1)) {
            // H1 row absent - assume DDS sine start phase (-π/2), delay unknown
            // -> no correction
            if (log.isWarnEnabled()) {
                log.warn("H1 row not found in harmonics CSV - system-delay phase "
                        + "correction disabled");
            }
            phi1 = -Math.PI / 2.0;
        }
        return phi1;
    }

    /**
     * Parses a dual-tone intermod-correction file
     * ({@code a;b;frequency_hz;...;re;im}, German-locale decimals) into the
     * {@code [a, b]} coefficient pairs and their {@code [re, im]} phasors.  The
     * stored phasor IS the final correction (the delay is already baked in
     * during accumulation), so amplitude {@code = hypot(re,im)} and phase
     * {@code = atan2(im,re)} apply directly - no delay re-derivation, mirroring
     * the single-tone H1 sentinel.
     */
    private void readIntermod(String path, List<int[]> coefficients, List<double[]> phasors)
            throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(COMMENT_PREFIX)) continue;
                String[] cols = line.split(COLUMN_SEPARATOR);
                if (cols.length < INTERMOD_MIN_COLUMNS) continue;   // header / short rows
                char first = cols[COL_INTERMOD_A].trim().charAt(0);
                if (!Character.isDigit(first) && first != '-') continue;   // header "a;b;..."
                coefficients.add(new int[] {
                    Integer.parseInt(cols[COL_INTERMOD_A].trim()),
                    Integer.parseInt(cols[COL_INTERMOD_B].trim())
                });
                phasors.add(new double[] {
                    number(cols[COL_INTERMOD_RE]), number(cols[COL_INTERMOD_IM])
                });
            }
        }
    }

    /** One decimal column, written with either separator - the files come from
     *  measurement tools that follow the machine's locale. */
    private double number(String column) {
        return Double.parseDouble(column.trim().replace(',', '.'));
    }
}
