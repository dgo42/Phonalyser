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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import org.edgo.audio.measure.gui.automation.AutomationChecks.CheckResult;
import org.edgo.audio.measure.gui.automation.AutomationChecks.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The check recorder and its result file, with no SWT display anywhere - the
 * comparison logic and the TSV an integration test parses are exactly the
 * parts that must be provable without launching an application.
 *
 * <p>Every verb is checked on BOTH sides of its boundary: a tolerance test
 * that only ever proves the passing case would let an inverted comparison
 * through, and this class is what decides whether a whole scenario is green.
 */
class AutomationChecksTest {

    /** Column indices of the TSV line format. */
    private static final int COL_NAME      = 0;
    private static final int COL_KIND      = 1;
    private static final int COL_PASS      = 2;
    private static final int COL_ACTUAL    = 3;
    private static final int COL_EXPECTED  = 4;
    private static final int COL_TOLERANCE = 5;
    private static final int COL_COUNT     = 7;

    @Test
    void absoluteToleranceHoldsOnBothSidesOfTheBoundary() {
        AutomationChecks checks = new AutomationChecks();
        checks.check("inside", 1000.4, 1000.0, 0.5);
        checks.check("onTheBoundary", 1000.5, 1000.0, 0.5);
        checks.check("outside", 1000.6, 1000.0, 0.5);

        List<CheckResult> results = checks.results();
        assertTrue(results.get(0).pass(), "0.4 off with 0.5 allowed should pass");
        assertTrue(results.get(1).pass(), "exactly the tolerance should pass");
        assertFalse(results.get(2).pass(), "0.6 off with 0.5 allowed should fail");
        assertEquals(Kind.ABS, results.get(0).kind());
        assertFalse(checks.allPassed(), "one red check must sink the run");
    }

    @Test
    void notANumberNeverPasses() {
        AutomationChecks checks = new AutomationChecks();
        checks.check("noResultYet", Double.NaN, 1000.0, 0.5);
        checks.checkDbBelow("noThdYet", Double.NaN, -100.0);
        checks.checkRange("noLevelYet", Double.NaN, -1.0, 1.0);

        for (CheckResult result : checks.results()) {
            assertFalse(result.pass(), result.name() + ": NaN must never satisfy a check");
        }
    }

    @Test
    void relativeToleranceIsPercentOfExpected() {
        AutomationChecks checks = new AutomationChecks();
        checks.checkRelPct("inside", 101.0, 100.0, 2.0);      // 1 % off, 2 % allowed
        checks.checkRelPct("outside", 103.0, 100.0, 2.0);     // 3 % off, 2 % allowed
        checks.checkRelPct("zeroExpected", 0.0, 0.0, 2.0);

        List<CheckResult> results = checks.results();
        assertTrue(results.get(0).pass());
        assertFalse(results.get(1).pass());
        assertFalse(results.get(2).pass(),
                "zero has no percentage to be within - must be red, not a divide by zero");
    }

    @Test
    void dbBelowIsACeiling() {
        AutomationChecks checks = new AutomationChecks();
        checks.checkDbBelow("quiet", -120.0, -100.0);
        checks.checkDbBelow("exactly", -100.0, -100.0);
        checks.checkDbBelow("loud", -80.0, -100.0);

        List<CheckResult> results = checks.results();
        assertTrue(results.get(0).pass(), "-120 dB is below a -100 dB ceiling");
        assertTrue(results.get(1).pass(), "at the ceiling passes");
        assertFalse(results.get(2).pass(), "-80 dB is ABOVE a -100 dB ceiling");
        assertEquals(Kind.DB_BELOW, results.get(0).kind());
    }

    @Test
    void perfectDistortionIsNotAFailure() {
        // thdPct == 0 -> 20*log10(0) == -Infinity, which is what the clean
        // synthetic loopback actually measures.  If this ever goes red, the
        // best possible measurement has become the only failing one.
        AutomationChecks checks = new AutomationChecks();
        checks.checkDbBelow("perfect", Double.NEGATIVE_INFINITY, -100.0);
        checks.checkDbBelow("infinitelyBad", Double.POSITIVE_INFINITY, -100.0);

        List<CheckResult> results = checks.results();
        assertTrue(results.get(0).pass(), "-Infinity dB is below every ceiling");
        assertFalse(results.get(1).pass(), "+Infinity dB is above every ceiling");
    }

    @Test
    void rangeIsClosedAtBothEnds() {
        AutomationChecks checks = new AutomationChecks();
        checks.checkRange("low", -1.0, -1.0, 1.0);
        checks.checkRange("high", 1.0, -1.0, 1.0);
        checks.checkRange("under", -1.1, -1.0, 1.0);
        checks.checkRange("over", 1.1, -1.0, 1.0);

        List<CheckResult> results = checks.results();
        assertTrue(results.get(0).pass());
        assertTrue(results.get(1).pass());
        assertFalse(results.get(2).pass());
        assertFalse(results.get(3).pass());
    }

    @Test
    void predicateAndUnconditionalFailure() {
        AutomationChecks checks = new AutomationChecks();
        checks.checkTrue("connected", true);
        assertTrue(checks.allPassed());

        checks.checkTrue("disconnected", false);
        assertFalse(checks.allPassed());

        checks.failCheck("unreachable", "this branch must never run");
        List<CheckResult> results = checks.results();
        assertEquals(Kind.FAIL, results.get(2).kind());
        assertEquals("this branch must never run", results.get(2).message());
    }

    @Test
    void aRunThatCheckedNothingPassed() {
        assertTrue(new AutomationChecks().allPassed(),
                "a screenshot scenario asserts nothing and is not thereby broken");
    }

    @Test
    void recordedChecksCannotBeEditedAfterTheFact() {
        AutomationChecks checks = new AutomationChecks();
        checks.checkTrue("one", true);
        List<CheckResult> firstLook = checks.results();
        checks.checkTrue("two", true);

        assertEquals(1, firstLook.size(), "the returned list must be a snapshot copy");
        assertEquals(2, checks.results().size());
    }

    @Test
    void resultFileIsParseableTsvWithAHeaderAndTheErrorTrailer(@TempDir Path dir) throws Exception {
        AutomationChecks checks = new AutomationChecks();
        checks.check("fund-freq", 1000.25, 1000.0, 1.0);
        checks.failCheck("deliberate", "this run must fail");
        Path file = dir.resolve("nested").resolve("results.tsv");

        checks.writeTo(file, new IllegalStateException("boom"));

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(4, lines.size(), "header + two checks + the error trailer");
        assertTrue(lines.get(0).startsWith("name\tkind\tpass"), "header first: " + lines.get(0));

        String[] green = lines.get(1).split("\t", -1);
        assertEquals(COL_COUNT, green.length, "every line has all seven columns");
        assertEquals("fund-freq", green[COL_NAME]);
        assertEquals("ABS", green[COL_KIND]);
        assertEquals("true", green[COL_PASS]);
        assertEquals(1000.25, Double.parseDouble(green[COL_ACTUAL]));
        assertEquals(1000.0, Double.parseDouble(green[COL_EXPECTED]));
        assertEquals(1.0, Double.parseDouble(green[COL_TOLERANCE]));

        String[] red = lines.get(2).split("\t", -1);
        assertEquals("deliberate", red[COL_NAME]);
        assertEquals("false", red[COL_PASS]);

        assertTrue(lines.get(3).startsWith("#error\t"), "error trailer: " + lines.get(3));
        assertTrue(lines.get(3).contains("boom"));
    }

    @Test
    void aCleanRunWritesNoErrorTrailer(@TempDir Path dir) throws Exception {
        AutomationChecks checks = new AutomationChecks();
        checks.checkTrue("window-up", true);
        Path file = dir.resolve("results.tsv");

        checks.writeTo(file, null);

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, lines.size(), "header + one check, no trailer");
        assertFalse(lines.get(1).startsWith("#error"));
    }

    @Test
    void tabsAndNewlinesCannotForgeAColumnOrALine(@TempDir Path dir) throws Exception {
        AutomationChecks checks = new AutomationChecks();
        checks.failCheck("na\tme\nwith\rjunk", "line one\nline two");
        Path file = dir.resolve("results.tsv");

        checks.writeTo(file, null);

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(2, lines.size(), "the injected newline must not become a second line");
        String[] columns = lines.get(1).split("\t", -1);
        assertEquals(COL_COUNT, columns.length, "the injected tab must not become a column");
        assertEquals("na me with junk", columns[COL_NAME]);
    }

    @Test
    void numbersAreWrittenLocaleIndependently(@TempDir Path dir) throws Exception {
        Locale original = Locale.getDefault();
        try {
            // German: a locale-sensitive formatter would emit "1000,25" here,
            // and the reading side parses with Double.parseDouble.
            Locale.setDefault(Locale.GERMANY);
            AutomationChecks checks = new AutomationChecks();
            checks.check("fund-freq", 1000.25, 1000.0, 1.0);
            Path file = dir.resolve("results.tsv");

            checks.writeTo(file, null);

            String line = Files.readAllLines(file, StandardCharsets.UTF_8).get(1);
            assertTrue(line.contains("1000.25"), "decimal POINT expected, got: " + line);
            assertFalse(line.contains("1000,25"), "decimal comma would break the reader");
        } finally {
            Locale.setDefault(original);
        }
    }
}
