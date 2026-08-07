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

package org.edgo.audio.measure.it.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import lombok.Getter;

/**
 * The reading half of the automation result file - the TSV an
 * {@code AbstractAutomationScript} wrote on its way out.
 *
 * <p>Parsed once, in the constructor, because a completed run's result file
 * does not change afterwards; the object then simply IS what the run recorded.
 *
 * <p>Tolerant on purpose: a header line, a blank line or a line with too few
 * columns is skipped rather than thrown on.  The file is written by a process
 * that may have been dying at the time, and a parser that refused to read a
 * truncated last line would destroy exactly the evidence the failure needs.
 * What it does NOT do is invent success - a missing file is reported as
 * missing, not as a run with no failures.
 */
public final class ResultFile {

    /** Column layout written by AutomationChecks. */
    private static final int COL_NAME      = 0;
    private static final int COL_KIND      = 1;
    private static final int COL_PASS      = 2;
    private static final int COL_ACTUAL    = 3;
    private static final int COL_EXPECTED  = 4;
    private static final int COL_TOLERANCE = 5;
    private static final int COL_MESSAGE   = 6;
    private static final int COL_COUNT     = 7;

    private static final String HEADER_FIRST_COLUMN = "name";
    /** Trailer the script writes when its run() threw. */
    private static final String ERROR_PREFIX = "#error";

    @Getter
    private final Path path;
    /** Whether the script wrote a file at all.  False means the run never got
     *  as far as finishing - which is a finding, not a pass. */
    @Getter
    private final boolean present;
    @Getter
    private final List<CheckLine> checks = new ArrayList<>();
    /** What killed the run, or null when it ended normally. */
    @Getter
    private String error;

    public ResultFile(Path path) throws IOException {
        this.path    = path;
        this.present = Files.isRegularFile(path);
        if (present) {
            parse(Files.readAllLines(path, StandardCharsets.UTF_8));
        }
    }

    /** Every red check, for a failure message that names them all rather than
     *  stopping at the first. */
    public List<CheckLine> failures() {
        return checks.stream().filter(check -> !check.pass()).toList();
    }

    private void parse(List<String> lines) {
        for (String line : lines) {
            if (line.isBlank()) continue;
            String[] columns = line.split("\t", -1);
            // The error trailer and a check that someone NAMED "#error" differ
            // only in shape: the trailer carries two columns, a check always
            // carries seven.  Judging by the prefix alone would let a script
            // hide its own red check by naming it after the trailer.
            if (line.startsWith(ERROR_PREFIX) && columns.length < COL_COUNT) {
                error = columns.length > 1 ? columns[1] : "";
                continue;
            }
            if (columns.length < COL_COUNT) continue;
            if (HEADER_FIRST_COLUMN.equals(columns[COL_NAME])) continue;
            checks.add(new CheckLine(columns[COL_NAME], columns[COL_KIND],
                    Boolean.parseBoolean(columns[COL_PASS]),
                    number(columns[COL_ACTUAL]), number(columns[COL_EXPECTED]),
                    number(columns[COL_TOLERANCE]), columns[COL_MESSAGE]));
        }
    }

    /** A column that is not a number reads as NaN - the value is evidence for a
     *  human, and losing the whole line over it would be worse. */
    private double number(String text) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException notANumber) {
            return Double.NaN;
        }
    }
}
