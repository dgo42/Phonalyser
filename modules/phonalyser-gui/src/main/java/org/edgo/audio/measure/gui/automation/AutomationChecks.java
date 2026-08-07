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

package org.edgo.audio.measure.gui.automation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import lombok.extern.log4j.Log4j2;

/**
 * The checks one automation run recorded, and their machine-readable form.
 *
 * <p>Owned by {@link AbstractAutomationScript}, which holds exactly one of
 * these and delegates its script-facing {@code check...} verbs here - so the
 * comparison, the verdict and the serialization all sit with the state they
 * are about, and can be unit-tested without an SWT display.
 *
 * <p><b>Every verb RECORDS and RETURNS.</b>  A red check never aborts the
 * scenario: an integration run is worth far more when it reports six results
 * of which one is wrong than when it stops at the first surprise.  The run's
 * verdict is asked for once, at the end, through {@link #allPassed()}.
 *
 * <p>The serialized form is TSV - one line per check, a header line first:
 * <pre>
 * name  kind  pass  actual  expected  tolerance  message
 * </pre>
 * chosen because it needs no new dependency in this module, parses in three
 * lines on the reading side, and raises no escaping questions.  Numbers are
 * written with {@code Double.toString}, which is locale-independent (a
 * German-locale run must not emit decimal commas the reader then misparses);
 * an absent number is {@code NaN}.  Names and messages are normalized so a
 * stray tab or newline can never break a line into two.
 *
 * <p>What the numeric columns mean depends on the kind:
 * {@code ABS} actual / expected / absolute tolerance;
 * {@code REL_PCT} actual / expected / tolerance in percent of expected;
 * {@code DB_BELOW} actual dB / the dB ceiling / unused;
 * {@code RANGE} actual / lower bound / upper bound;
 * {@code TRUE} and {@code FAIL} carry no numbers.
 */
@Log4j2
final class AutomationChecks {

    /** Column separator and the guard against a value that would forge one. */
    private static final char   TAB    = '\t';
    private static final String HEADER =
            "name\tkind\tpass\tactual\texpected\ttolerance\tmessage";
    /** Trailer written when the script threw, so a reader can tell "all checks
     *  green but the run died" from "the run completed". */
    private static final String ERROR_PREFIX = "#error";
    private static final double PERCENT      = 100.0;

    /** What a check compared - the reader's hint for interpreting the three
     *  numeric columns, and the script author's hint when a line is red. */
    enum Kind {
        ABS, REL_PCT, DB_BELOW, RANGE, TRUE, FAIL;

        private Kind() {
        }
    }

    /** One recorded check, exactly as one TSV line carries it. */
    record CheckResult(String name, Kind kind, double actual, double expected,
            double tolerance, boolean pass, String message) {
    }

    /** Recorded in call order - the order the result file lists them, which is
     *  the order the scenario performed them. */
    private final List<CheckResult> results = new ArrayList<>();

    /** {@code actual} within {@code absTol} of {@code expected}. */
    void check(String name, double actual, double expected, double absTol) {
        double diff = Math.abs(actual - expected);
        record(name, Kind.ABS, actual, expected, absTol,
                Double.isFinite(diff) && diff <= absTol,
                "|actual - expected| = " + diff);
    }

    /** {@code actual} within {@code relPct} percent OF {@code expected}.  An
     *  expected value of zero has no percentage to be within, so such a check
     *  is red by construction rather than silently dividing by zero. */
    void checkRelPct(String name, double actual, double expected, double relPct) {
        double allowed = Math.abs(expected) * relPct / PERCENT;
        double diff    = Math.abs(actual - expected);
        record(name, Kind.REL_PCT, actual, expected, relPct,
                expected != 0 && Double.isFinite(diff) && diff <= allowed,
                "|actual - expected| = " + diff + ", allowed " + allowed);
    }

    /** A CEILING: {@code actualDb} must be at or below {@code maxDb} - the
     *  shape every distortion and noise limit takes.
     *
     *  <p>{@code -Infinity} PASSES, and that is not a special case but the
     *  point: a mathematically perfect result has {@code thdPct == 0}, and
     *  {@code 20·log10(0)} is exactly {@code -Infinity}.  Demanding a finite
     *  number here would score the cleanest measurement obtainable - a
     *  synthetic loopback with no distortion at all - as the only red one.
     *  NaN still fails: "not measured" is not "perfect". */
    void checkDbBelow(String name, double actualDb, double maxDb) {
        record(name, Kind.DB_BELOW, actualDb, maxDb, Double.NaN,
                !Double.isNaN(actualDb) && actualDb <= maxDb,
                "ceiling " + maxDb + " dB");
    }

    /** {@code actual} inside the closed interval {@code [min, max]}. */
    void checkRange(String name, double actual, double min, double max) {
        record(name, Kind.RANGE, actual, min, max,
                Double.isFinite(actual) && actual >= min && actual <= max,
                "range " + min + " .. " + max);
    }

    /** A plain predicate - the check for everything that is not a number. */
    void checkTrue(String name, boolean condition) {
        record(name, Kind.TRUE, Double.NaN, Double.NaN, Double.NaN, condition, "");
    }

    /** Unconditionally red, with a reason.  A scenario reaching a branch it
     *  must never reach says so with this. */
    void failCheck(String name, String message) {
        record(name, Kind.FAIL, Double.NaN, Double.NaN, Double.NaN, false, message);
    }

    /** The run's verdict.  A run that recorded NO checks passed: a screenshot
     *  scenario asserts nothing and is not thereby broken. */
    boolean allPassed() {
        for (CheckResult result : results) {
            if (!result.pass()) return false;
        }
        return true;
    }

    /** The recorded checks, in call order - a copy, so a caller cannot edit
     *  the record of what happened. */
    List<CheckResult> results() {
        return List.copyOf(results);
    }

    /**
     * Writes the TSV result file, creating parent directories as needed.
     *
     * @param file  where to write
     * @param error the throwable that ended the run, or {@code null} when the
     *              script returned normally - appended as the {@code #error}
     *              trailer
     */
    void writeTo(Path file, Throwable error) throws IOException {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (CheckResult result : results) {
            sb.append(clean(result.name())).append(TAB)
              .append(result.kind()).append(TAB)
              .append(result.pass()).append(TAB)
              .append(Double.toString(result.actual())).append(TAB)
              .append(Double.toString(result.expected())).append(TAB)
              .append(Double.toString(result.tolerance())).append(TAB)
              .append(clean(result.message())).append('\n');
        }
        if (error != null) {
            sb.append(ERROR_PREFIX).append(TAB).append(clean(error.toString())).append('\n');
        }
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    /** Records one outcome and says so in the log - warn for red so an
     *  unattended run's failure is visible in the log file alone, info for
     *  green so the log tells the whole story either way. */
    private void record(String name, Kind kind, double actual, double expected,
            double tolerance, boolean pass, String message) {
        String cleanName = clean(name);
        results.add(new CheckResult(cleanName, kind, actual, expected, tolerance,
                pass, clean(message)));
        if (pass) {
            if (log.isInfoEnabled()) {
                log.info("Automation check PASS {} [{}] actual={} expected={} tol={} {}",
                        cleanName, kind, actual, expected, tolerance, message);
            }
        } else if (log.isWarnEnabled()) {
            log.warn("Automation check FAIL {} [{}] actual={} expected={} tol={} {}",
                    cleanName, kind, actual, expected, tolerance, message);
        }
    }

    /** A value that cannot break the line format: tabs and line breaks become
     *  single spaces, null becomes empty. */
    private String clean(String value) {
        return value == null ? "" : value.replaceAll("[\\t\\r\\n]+", " ").trim();
    }
}
