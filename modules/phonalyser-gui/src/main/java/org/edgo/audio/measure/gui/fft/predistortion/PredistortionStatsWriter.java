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

package org.edgo.audio.measure.gui.fft.predistortion;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.edgo.audio.measure.dsp.HarmonicCompensation.GeneratorCorrections;
import org.edgo.audio.measure.dsp.IntermodCompensation;
import org.edgo.audio.measure.dsp.IntermodCompensation.ProductPhasors;
import org.edgo.audio.measure.fft.FftResult;

import lombok.extern.log4j.Log4j2;

/**
 * Per-session diagnostic record of a DAC-predistortion training run - a plain
 * CSV file, deliberately NOT a logger: it carries measurement data a
 * spreadsheet opens, so it must not compete with the application log's levels,
 * layout or rolling.  One file per training session, named
 * {@code dpd-stats-<timestamp>.csv} in the same directory the application
 * writes its log to.
 *
 * <p><b>Failure policy.</b> Nothing here throws.  The constructor and every
 * record method catch their I/O failure; the first one closes the stream,
 * drops the reference and leaves a single guarded warning behind, after which
 * every further call is a silent no-op.  The training loop therefore needs no
 * exception handling around the record calls and cannot be disturbed by a full
 * or read-only disk.  Every record is flushed as it is written, so the tail of
 * a diverging run survives an abort.
 *
 * <p><b>Layout.</b> One header row, semicolon-separated with German decimal
 * commas (as the {@code applied_compensation} and harmonics exports are
 * written).  A leading type column holds every record kind in one file, each
 * carrying the same {@code hz;mag_db;phase_deg} triple for the fundamental and
 * H2..H9, and five trailing columns
 * ({@code prod_a;prod_b;prod_hz;prod_mag_db;prod_phase_deg}) that only the
 * dual-tone product records fill - a two-tone product grid is keyed by its
 * {@code (a, b)} coefficients and does not fit a block keyed to F1 and H2..H9.
 * Every row carries every column the header names; the unused ones are empty:
 *
 * <ul>
 *   <li>{@code PRODROUND} - per dual-tone round: each product's measured
 *       residual (dBr against that round's raw F1 level, RAW phase), plus a
 *       {@code (0,1)} row carrying the second tone's own frequency, level and
 *       raw phase - the second reference the dual de-rotation needs.</li>
 *   <li>{@code PRODAPPLY} - per dual-tone round: the correction handed to the
 *       generator per product.</li>
 *   <li>{@code FRAME} - one per FFT frame observed while a round collects:
 *       the frame's noise floor and the harmonic levels in dBr, phases in
 *       degrees.  Its {@code hop_samples} column carries the frame spacing
 *       that analysis actually used - under the random overlap the hop DRAWN
 *       for that tick, so the record shows the realized hop sequence.</li>
 *   <li>{@code ROUND} - the same for the round's finalized averaged result,
 *       i.e. the values the correction was computed from.</li>
 *   <li>{@code APPLY} - the correction handed to the generator after that
 *       round: per harmonic the amplitude ratio to the DAC fundamental in dB
 *       and the initial phase in degrees.  The fundamental columns stay
 *       empty; so do harmonics the gate dropped.</li>
 * </ul>
 *
 * <p><b>The dBr reference</b> is the frame's own fundamental level as
 * MEASURED before any {@code .frc} de-embed - the pre-correction peak
 * snapshot when a calibration is loaded, else the measured
 * {@code fundamentalDbFs}, which is then already raw.  That is the exact
 * sibling of the raw harmonic levels the accumulator consumes, so a level and
 * its reference always come from the same (notched) spectrum.  The
 * fundamental's own dBr would be 0 by construction, so its magnitude column
 * carries that reference itself, in dBFS - the file states its own reference
 * per frame.
 *
 * <p><b>Phases are raw</b> - the argument of the phasor the analyser captured
 * before the de-embed, exactly what the accumulator reads.  Each round's
 * analysis window starts at an arbitrary point in the tone, so comparing
 * phases ACROSS rounds requires the same de-rotation the accumulator applies
 * (from the fundamental phase and the harmonic frequency, both recorded);
 * doing it here would hide the raw observation.
 *
 * <p>Not thread-safe: every call comes from the engine's own loop thread.
 */
@Log4j2
public final class PredistortionStatsWriter {

    /** Highest harmonic the file carries a column triple for. */
    private static final int MAX_HARMONIC = 9;

    private static final String FILE_PREFIX = "dpd-stats-";
    private static final String FILE_SUFFIX = ".csv";
    private static final String SEP         = ";";
    private static final String TYPE_PRODROUND = "PRODROUND";
    private static final String TYPE_PRODAPPLY = "PRODAPPLY";
    private static final String TYPE_FRAME  = "FRAME";
    private static final String TYPE_ROUND  = "ROUND";
    private static final String TYPE_APPLY  = "APPLY";

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final Path file;
    /** {@code null} once the file is closed or a write has failed - every
     *  record method then does nothing. */
    private BufferedWriter out;
    /** Analysis-window start of the last frame written, so repeated polls of
     *  the same published result record it only once. */
    private long lastFrameStart = Long.MIN_VALUE;

    /** Creates a DISABLED record: no file is opened and every record call
     *  returns at once.  This is the shape the training loop gets while the
     *  diagnostic switch is off - the same no-op state a failed write leaves
     *  behind, so the call sites need neither a null check nor a branch. */
    public PredistortionStatsWriter() {
        this.file = null;                                   // out stays null - disabled
    }

    /** Creates and opens this session's file in {@code logsDir}, writing the
     *  header row.  A failure here disables the record; it never throws. */
    public PredistortionStatsWriter(Path logsDir) {
        this.file = logsDir.resolve(FILE_PREFIX + LocalDateTime.now().format(STAMP) + FILE_SUFFIX);
        try {
            Files.createDirectories(logsDir);
            this.out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            disable(e);
            return;
        }
        write(header());
        if (log.isInfoEnabled()) {
            log.info("Predistortion statistics file: {}", file);
        }
    }

    // -------------------------------------------------------------------------
    // Records
    // -------------------------------------------------------------------------

    /** Writes a dual-tone round's measured products - one row per grid product,
     *  its magnitude in dBr against this round's raw F1 level and its RAW
     *  measured phase, plus a trailing {@code (0,1)} row carrying the SECOND
     *  TONE itself: its frequency, its level and its raw phase.  That row is
     *  where {@code arg(F2)} lives; {@code arg(F1)} is in the ROUND record of
     *  the same round, and the dual de-rotation needs both. */
    public void productRound(int round, FftResult r, ProductPhasors raw, double[] tone2) {
        if (r == null || raw == null) return;
        double refDbFs = referenceDbFs(r);
        for (int g = 0; g < raw.hz().length; g++) {
            if (!(raw.hz()[g] > 0.0)) continue;
            double mag = Math.hypot(raw.re()[g], raw.im()[g]);
            productRow(TYPE_PRODROUND, round, raw.coefA()[g], raw.coefB()[g], raw.hz()[g],
                    magDb(mag) - refDbFs, phaseDeg(raw.re()[g], raw.im()[g]));
        }
        if (tone2 != null) {
            productRow(TYPE_PRODROUND, round, 0, 1, r.fundamental2HzRefined,
                    magDb(Math.hypot(tone2[0], tone2[1])) - refDbFs,
                    phaseDeg(tone2[0], tone2[1]));
        }
    }

    /** Writes the correction handed to the generator after a dual-tone round -
     *  per product the amplitude ratio in dB and the initial phase in degrees.
     *  The two-tone counterpart of the harmonic APPLY record. */
    public void productCorrection(int round, IntermodCompensation.GeneratorCorrections gc,
                                  double f1Hz, double f2Hz) {
        if (gc == null) return;
        for (int i = 0; i < gc.ampRatios().length; i++) {
            int a = gc.coefA()[i];
            int b = gc.coefB()[i];
            productRow(TYPE_PRODAPPLY, round, a, b, a * f1Hz + b * f2Hz,
                    magDb(gc.ampRatios()[i]), Math.toDegrees(gc.phiInits()[i]));
        }
    }

    /** One product row: the fixed component block empty, the five trailing
     *  columns carrying the product.  Shared by every product record kind so
     *  they cannot drift apart in shape. */
    private void productRow(String type, int round, int a, int b,
                            double hz, double magDb, double phaseDeg) {
        StringBuilder sb = head(type, round, 0, 0, Double.NaN);
        for (int c = 0; c < MAX_HARMONIC; c++) {             // the F1 / H2..H9 block stays empty
            component(sb, Double.NaN, Double.NaN, Double.NaN);
        }
        sb.append(SEP).append(a)
          .append(SEP).append(b)
          .append(SEP).append(frequency(hz))
          .append(SEP).append(value(magDb))
          .append(SEP).append(value(phaseDeg));
        write(sb.toString());
    }

    /** Writes one collected FFT frame of {@code round}.  A result already
     *  recorded (the loop polls faster than the analyser publishes) and a
     *  missing result are ignored. */
    public void frame(int round, FftResult r) {
        if (r == null || r.samplesAbsStart == lastFrameStart) return;
        lastFrameStart = r.samplesAbsStart;
        writeRecord(measurement(TYPE_FRAME, round, r));
    }

    /** Writes the round's finalized averaged result - the levels and phases
     *  the round's correction was computed from. */
    public void round(int round, FftResult r) {
        if (r == null) return;
        writeRecord(measurement(TYPE_ROUND, round, r));
    }

    /** Writes the correction handed to the generator after {@code round}:
     *  each harmonic's amplitude ratio in dB and its initial phase in
     *  degrees.  Harmonics the correction does not carry stay empty. */
    public void correction(int round, GeneratorCorrections gc) {
        if (gc == null) return;
        StringBuilder sb = head(TYPE_APPLY, round, 0, 0, Double.NaN);
        component(sb, Double.NaN, Double.NaN, Double.NaN);      // no fundamental correction
        for (int n = 2; n <= MAX_HARMONIC; n++) {
            int i = indexOf(gc.harmonicNumbers(), n);
            double mag   = i >= 0 ? magDb(gc.ampRatios()[i])            : Double.NaN;
            double phase = i >= 0 ? Math.toDegrees(gc.phiInits()[i])    : Double.NaN;
            component(sb, Double.NaN, mag, phase);
        }
        writeRecord(sb);
    }

    /** Closes the file - the training session's last act. */
    public void close() {
        closeStream();
    }

    // -------------------------------------------------------------------------
    // Record building
    // -------------------------------------------------------------------------

    private String header() {
        StringBuilder sb = new StringBuilder(
                "type;round;averages;hop_samples;noise_dbr;f1_hz;f1_mag_db;f1_phase_deg");
        for (int n = 2; n <= MAX_HARMONIC; n++) {
            sb.append(";h").append(n).append("_hz")
              .append(";h").append(n).append("_mag_db")
              .append(";h").append(n).append("_phase_deg");
        }
        sb.append(";prod_a;prod_b;prod_hz;prod_mag_db;prod_phase_deg");
        return sb.toString();
    }

    /** Closes a row that carries no product: the five trailing product columns
     *  stay empty, so every row still has every column the header names. */
    private void writeRecord(StringBuilder sb) {
        sb.append(SEP).append(SEP).append(SEP).append(SEP).append(SEP);
        write(sb.toString());
    }

    /** The five leading columns shared by every record kind.  The hop is the
     *  frame spacing the analysis actually used, in samples - per tick the
     *  DRAWN hop under the random overlap; empty (0) for record kinds that
     *  carry no spectrum. */
    private StringBuilder head(String type, int round, int averages, int hopSamples,
                               double noiseDbr) {
        StringBuilder sb = new StringBuilder(type);
        sb.append(SEP).append(round)
          .append(SEP).append(averages)
          .append(SEP).append(hopSamples > 0 ? String.valueOf(hopSamples) : "")
          .append(SEP).append(value(noiseDbr));
        return sb;
    }

    /** A measured frame or round: the noise floor and every component
     *  relative to this result's own raw fundamental level. */
    private StringBuilder measurement(String type, int round, FftResult r) {
        double refDbFs = referenceDbFs(r);
        StringBuilder sb = head(type, round, r.frameCount, r.stepSamples,
                r.noisePeakFloorDbFs() - refDbFs);
        component(sb, r.fundamentalHzRefined, refDbFs, phaseDeg(r.rawFundRe, r.rawFundIm));
        for (int h = 0; h < MAX_HARMONIC - 1; h++) {
            if (!hasHarmonic(r, h)) {
                component(sb, Double.NaN, Double.NaN, Double.NaN);
                continue;
            }
            double phase = (r.rawPeakRe != null && r.rawPeakIm != null && h < r.rawPeakRe.length)
                    ? phaseDeg(r.rawPeakRe[h], r.rawPeakIm[h])
                    : Double.NaN;
            component(sb, r.harmonicHz[h], r.rawHarmonicDbFs(h) - refDbFs, phase);
        }
        return sb;
    }

    /** True when harmonic {@code h} was located in this result - an unfound
     *  harmonic (bin 0) is skipped by the accumulator too. */
    private boolean hasHarmonic(FftResult r, int h) {
        return h < r.harmonicCount
                && r.harmonicHz   != null && h < r.harmonicHz.length
                && r.harmonicBins != null && h < r.harmonicBins.length
                && r.harmonicBins[h] > 0;
    }

    /** One component's three columns. */
    private void component(StringBuilder sb, double hz, double magDb, double phaseDeg) {
        sb.append(SEP).append(frequency(hz))
          .append(SEP).append(value(magDb))
          .append(SEP).append(value(phaseDeg));
    }

    /** The level every magnitude on a measured row is relative to: the
     *  fundamental as measured BEFORE the {@code .frc} de-embed - the
     *  pre-correction snapshot when a calibration is loaded, else the
     *  measured level, which is then already raw. */
    private double referenceDbFs(FftResult r) {
        double[][] pre = r.preCorrectionPeaks;
        if (pre != null && pre.length > 1 && pre[1] != null && pre[1].length > 0) {
            return pre[1][0];
        }
        return r.fundamentalDbFs;
    }

    private double phaseDeg(double re, double im) {
        if (re == 0.0 && im == 0.0) return Double.NaN;
        return Math.toDegrees(Math.atan2(im, re));
    }

    private double magDb(double linear) {
        return linear > 0.0 ? 20.0 * Math.log10(linear) : Double.NaN;
    }

    private int indexOf(int[] harmonicNumbers, int n) {
        for (int i = 0; i < harmonicNumbers.length; i++) {
            if (harmonicNumbers[i] == n) return i;
        }
        return -1;
    }

    /** A value that does not exist leaves an EMPTY cell - a spreadsheet then
     *  shows a gap instead of a number that was never measured. */
    private String value(double v) {
        return Double.isFinite(v) ? String.format(Locale.GERMAN, "%.4f", v) : "";
    }

    private String frequency(double hz) {
        return Double.isFinite(hz) ? String.format(Locale.GERMAN, "%.6f", hz) : "";
    }

    // -------------------------------------------------------------------------
    // File
    // -------------------------------------------------------------------------

    /** Appends one record and flushes it, so an aborted run keeps everything
     *  written so far.  The first failure disables the file for good. */
    private void write(String line) {
        BufferedWriter w = out;
        if (w == null) return;
        try {
            w.write(line);
            w.newLine();
            w.flush();
        } catch (IOException e) {
            disable(e);
        }
    }

    private void disable(IOException e) {
        if (log.isWarnEnabled()) {
            log.warn("Predistortion statistics file {} disabled: {}", file, e.getMessage());
        }
        closeStream();
    }

    private void closeStream() {
        BufferedWriter w = out;
        out = null;
        if (w == null) return;
        try {
            w.close();
        } catch (IOException e) {
            // The stream is being abandoned either way - a close failure has
            // nothing left to recover.
        }
    }
}
