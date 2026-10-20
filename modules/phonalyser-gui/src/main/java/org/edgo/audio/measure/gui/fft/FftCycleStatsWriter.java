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

package org.edgo.audio.measure.gui.fft;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.edgo.audio.measure.dsp.FftDsp;
import org.edgo.audio.measure.enums.WindowType;
import org.edgo.audio.measure.fft.FftResult;

import lombok.extern.log4j.Log4j2;

/**
 * Per-cycle diagnostic record of the coherent cross-cycle FFT accumulator - a
 * plain CSV, deliberately NOT a logger (the same conventions as the
 * predistortion statistics record: one file per accumulator run, named
 * {@code fft-cycles-<timestamp>.csv} in the application's log directory,
 * semicolon-separated with German decimal commas, every row flushed, nothing
 * here ever throws - the first I/O failure closes the stream and turns every
 * further call into a silent no-op).
 *
 * <p>Purpose: numeric offline analysis of how the fundamental's window lobe
 * behaves under overlapped coherent averaging.  Each row carries the
 * de-rotation inputs exactly as the accumulator used them for that analysis
 * cycle (absolute window position, intra-call step, elapsed-sample delta, the
 * phase-lock loop's cumulative correction, the refined fractional bin kappa
 * and the per-bin ramp slope theta) plus two complex spectrum slices around
 * the pinned fundamental bin: the fresh cycle's spectrum BEFORE the
 * cross-cycle rotation, and the accumulated running sum AFTER this cycle was
 * added.  From those slices an analysis script can reconstruct, per lobe
 * bin, the actual cycle-to-cycle phase advance and the carving of the
 * averaged lobe, and test them against a rotation model.
 *
 * <p>Not thread-safe: every record call comes from the analysis worker
 * thread.  A concurrent {@code close()} from another thread at worst trips
 * the failure policy of a record in flight, which disables the file - the
 * accepted cost of a diagnostic record.
 */
@Log4j2
public final class FftCycleStatsWriter {

    private static final String FILE_PREFIX = "fft-cycles-";
    private static final String FILE_SUFFIX = ".csv";
    private static final String SEP         = ";";
    /** Bins recorded either side of the pinned fundamental bin - wide enough
     *  for the widest supported window main lobe (the HFT248D flat top spans
     *  about 7 bins each side) with margin for its first sidelobes. */
    //private static final int LOBE_SLICE_HALF_WIDTH = 12;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final Path file;
    /** {@code null} once closed, failed, or constructed disabled - every
     *  record call then does nothing. */
    private BufferedWriter out;
    /** Row counter since this file was opened (= analysis cycles accumulated). */
    private long cycle;

    /** Creates a DISABLED record: no file is opened and every call returns at
     *  once - the shape the worker holds while the diagnostic switch is off,
     *  so call sites need neither a null check nor a branch. */
    public FftCycleStatsWriter() {
        this.file = null;                                   // out stays null - disabled
    }

    /** Creates and opens this accumulator run's file in {@code logsDir},
     *  writing a configuration line and the column header.  A failure here
     *  disables the record; it never throws. */
    public FftCycleStatsWriter(Path logsDir, int fftSize, int sampleRate, WindowType window, double freqResolution) {
        this.file = logsDir.resolve(FILE_PREFIX + LocalDateTime.now().format(STAMP) + FILE_SUFFIX);
        try {
            Files.createDirectories(logsDir);
            this.out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            disable(e);
            return;
        }
        int mainLobeBins = FftDsp.mainLobeBins(window);
        write("# fftSize=" + fftSize + " sampleRate=" + sampleRate
                + " freqRes=" + String.format(Locale.GERMAN, "%.6f", freqResolution)
                + " lobeHalfWidth=" + mainLobeBins);
        write(header(mainLobeBins));
        if (log.isInfoEnabled()) {
            log.info("FFT cycle statistics file: {}", file);
        }
    }

    /** One row per accumulated analysis cycle.  {@code r} holds the fresh
     *  cycle's spectrum (still un-rotated - the accumulator rotates on the fly
     *  while adding); {@code accumRe/accumIm} the running sum AFTER the add;
     *  {@code theta} the fundamental's rotation angle the add used (the
     *  reference the rest of the family derives from); {@code delta}
     *  includes {@code droppedSamples} (the loop correction), both recorded
     *  so the analysis can separate them. */
    public void cycle(FftResult r, long absStart, double delta, double droppedSamples,
                     double kappa, int k0, double theta,
                     double[] accumRe, double[] accumIm, int accumFramesAfter) {
        if (out == null) return;
        StringBuilder sb = new StringBuilder(2048);
        sb.append(cycle++)
          .append(SEP).append(absStart)
          .append(SEP).append(r.stepSamples)
          .append(SEP).append(r.frameCount)
          .append(SEP).append(value(delta))
          .append(SEP).append(value(droppedSamples))
          .append(SEP).append(value(kappa))
          .append(SEP).append(k0)
          .append(SEP).append(value(theta))
          .append(SEP).append(value(r.fundamentalHzRefined))
          .append(SEP).append(value(r.fundamentalDbFs))
          .append(SEP).append(accumFramesAfter);
        int mainLobeBins = FftDsp.mainLobeBins(r.windowType);
        appendLobe(sb, r.re, r.im, k0, r.fftSize / 2, mainLobeBins);
        appendLobe(sb, accumRe, accumIm, k0, r.fftSize / 2, mainLobeBins);
        write(sb.toString());
    }

    private String header(int mainLobeBins) {
        StringBuilder sb = new StringBuilder(
                "cycle;abs_start;step_samples;frames_in_call;delta;dropped_samples;"
                + "kappa;k0;theta_rad;fund_hz;fund_dbfs;accum_frames");
        appendLobeHeader(sb, "t", mainLobeBins);      // fresh cycle spectrum
        appendLobeHeader(sb, "a", mainLobeBins);      // accumulated running sum
        return sb.toString();
    }

    private void appendLobeHeader(StringBuilder sb, String prefix, int mainLobeBins) {
        for (int m = -mainLobeBins; m <= mainLobeBins; m++) {
            String tag = prefix + (m < 0 ? "m" + (-m) : "p" + m);
            sb.append(SEP).append(tag).append("_re")
              .append(SEP).append(tag).append("_im");
        }
    }

    /** The complex slice {@code [k0 - half .. k0 + half]}; bins outside the
     *  spectrum (or a null array) leave their two cells empty. */
    private void appendLobe(StringBuilder sb, double[] re, double[] im, int k0, int maxBin, int mainLobeBins) {
        for (int m = -mainLobeBins; m <= mainLobeBins; m++) {
            int k = k0 + m;
            if (re == null || im == null || k < 0 || k > maxBin || k >= re.length) {
                sb.append(SEP).append(SEP);
            } else {
                sb.append(SEP).append(value(re[k]))
                  .append(SEP).append(value(im[k]));
            }
        }
    }

    /** Exponent format - the slices span from full scale to deep-average
     *  noise floors; a fixed-point format would flatten the floor to zero.
     *  A value that does not exist leaves an EMPTY cell. */
    private String value(double v) {
        return Double.isFinite(v) ? String.format(Locale.GERMAN, "%.6e", v) : "";
    }

    /** Appends one row and flushes it, so the tail of an aborted run
     *  survives.  The first failure disables the file for good. */
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
            log.warn("FFT cycle statistics disabled ({}): {}", file, e.toString());
        }
        BufferedWriter w = out;
        out = null;
        if (w != null) {
            try {
                w.close();
            } catch (IOException ignored) {
                // already disabling - nothing left to do
            }
        }
    }

    /** Closes the file.  Idempotent; a disabled record no-ops. */
    public void close() {
        BufferedWriter w = out;
        out = null;
        if (w == null) return;
        try {
            w.close();
        } catch (IOException ignored) {
            // closing on teardown - nothing left to do
        }
    }
}
