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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;

import lombok.Getter;

/**
 * One completed GUI run: the exit code the process gave, and everything the
 * automation script recorded on its way out.
 *
 * <p>{@link #assertAllChecks()} turns that into individually named JUnit tests
 * - one per check - so a report says WHICH measurement was out and by how much,
 * instead of a single red line for the whole scenario.
 */
public final class GuiRun {

    /** Exit code of a run whose script completed with every check green - see
     *  GuiMain, which is where the runner's verdict becomes this number. */
    private static final int EXIT_OK = 0;

    @Getter
    private final int exitCode;
    @Getter
    private final ResultFile results;

    public GuiRun(int exitCode, ResultFile results) {
        this.exitCode = exitCode;
        this.results  = results;
    }

    /**
     * The complete GREEN verdict, as dynamic tests: the process exited 0, it
     * wrote a result file, that file holds at least one check, the script did
     * not die, and every check passed.
     *
     * <p>The first three exist to make a vacuous pass impossible.  A
     * {@code @TestFactory} that produced no tests at all would be REPORTED AS
     * GREEN, so "the script never ran and wrote nothing" must itself be a named
     * failing test - otherwise the most complete failure there is would be the
     * quietest.
     */
    public Stream<DynamicTest> assertAllChecks() {
        List<CheckLine> checks = results.getChecks();
        Stream<DynamicTest> preconditions = Stream.of(
                dynamicTest("process-exit-code", () -> assertEquals(EXIT_OK, exitCode,
                        "the application should have exited 0; see the run's logs")),
                dynamicTest("results-file-written", () -> assertTrue(results.isPresent(),
                        "no result file at " + results.getPath()
                                + " - the script did not reach the end of the run")),
                dynamicTest("results-file-has-checks", () -> assertTrue(!checks.isEmpty(),
                        "the result file records no checks at all - the scenario "
                                + "asserted nothing")),
                dynamicTest("script-did-not-throw", () -> assertNull(results.getError(),
                        "the script died: " + results.getError())));
        Stream<DynamicTest> perCheck = checks.stream()
                .map(check -> dynamicTest(check.name(), () -> {
                    if (!check.pass()) {
                        fail(check.describe());
                    }
                }));
        return Stream.concat(preconditions, perCheck);
    }
}
