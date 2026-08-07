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

package org.edgo.audio.measure.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.edgo.audio.measure.it.engine.CheckLine;
import org.edgo.audio.measure.it.engine.GuiRun;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.Timeout;

/**
 * THE META-TEST: proves that a scenario which fails actually turns the build
 * red.
 *
 * <p>Every other integration test in this module asserts that something went
 * right.  None of them is worth anything unless the machinery can also report
 * that something went WRONG - a verdict chain incapable of failing would print
 * exactly the same greens if it were completely broken.  This is the test that
 * makes the other greens mean something, and that is why it is mandatory.
 *
 * <p>It covers both ways a run can fail, because they travel different paths
 * through {@code AutomationRunner}:
 * <ul>
 *   <li><b>a recorded red check</b> - the script stayed in control and reported
 *       a bad measurement, reaching the exit code through the checks list;</li>
 *   <li><b>a throw</b> - the script lost control entirely, reaching it through
 *       the catch.</li>
 * </ul>
 * In both cases the post-mortem must survive: the checks recorded BEFORE the
 * failure are still in the result file.
 *
 * <p>Note what is deliberately NOT used here: {@code GuiRun.assertAllChecks()}
 * asserts the green verdict, and these runs are supposed to be red.
 */
@Tag("user-mode")
@TestInstance(Lifecycle.PER_CLASS)
class VerdictRedIT extends ScenarioIT {

    private static final String RED_SCENARIO   = "verdict-red";
    private static final String THROW_SCENARIO = "verdict-throw";
    private static final int    RUN_TIMEOUT_SECONDS = 120;
    /** GuiMain's exit code for a scenario that failed - see AUTOMATION_FAILED_EXIT. */
    private static final int EXPECTED_FAILURE_EXIT = 1;

    private GuiRun redRun;
    private GuiRun throwRun;

    @BeforeAll
    @Timeout(2 * RUN_TIMEOUT_SECONDS + 120)
    void runBothFailingScenarios() throws Exception {
        // Two scenarios in one class, so this one does NOT use the base's
        // single-run shape - but the staging, the spawning and the teardown are
        // the same work, and it reuses those.
        redRun   = runGui(stage(RED_SCENARIO), true, null, Map.of(), RUN_TIMEOUT_SECONDS);
        throwRun = runGui(stage(THROW_SCENARIO), true, null, Map.of(), RUN_TIMEOUT_SECONDS);
    }

    @Test
    void aRedCheckMakesTheProcessExitNonZero() {
        assertEquals(EXPECTED_FAILURE_EXIT, redRun.getExitCode(),
                "a scenario that recorded a failed check must not exit 0");
    }

    @Test
    void aRedCheckIsReportedWithItsReason() {
        assertTrue(redRun.getResults().isPresent(),
                "the result file must exist even for a failed run");
        List<CheckLine> failures = redRun.getResults().failures();
        assertEquals(1, failures.size(), "exactly the one deliberate failure: " + failures);
        assertEquals("deliberate", failures.get(0).name());
        assertEquals("this run must fail", failures.get(0).message());
    }

    @Test
    void theGreenCheckAroundTheRedOneSurvives() {
        // One red check sinks the run without suppressing what else was
        // recorded - otherwise a failing scenario would lose the context that
        // makes it diagnosable.
        assertEquals(2, redRun.getResults().getChecks().size(),
                "both checks should be recorded: " + redRun.getResults().getChecks());
        assertTrue(redRun.getResults().getChecks().get(0).pass());
    }

    @Test
    void aRedCheckIsNotReportedAsAThrow() {
        assertNull(redRun.getResults().getError(),
                "the script completed normally; only its check failed");
    }

    @Test
    void aThrowingScriptMakesTheProcessExitOne() {
        // EXACTLY 1, not merely non-zero: 1 is the code
        // GuiMain defines for a failed scripted run, and it is the whole
        // contract.  "Non-zero" would also accept a JVM that died of something
        // else entirely - a crash, an OOM, a native abort - and call the
        // verdict chain proven by an accident that bypassed it.
        assertEquals(EXPECTED_FAILURE_EXIT, throwRun.getExitCode(),
                "a scenario whose script threw must exit with the automation "
                        + "failure code, not merely non-zero");
    }

    @Test
    void aThrowIsRecordedInTheResultFileWithTheEvidenceBeforeIt() {
        assertTrue(throwRun.getResults().isPresent(),
                "a run that died must still leave its post-mortem behind");
        assertNotNull(throwRun.getResults().getError(),
                "the #error trailer must name what killed the run");
        assertTrue(throwRun.getResults().getError().contains("boom"),
                "the trailer should carry the exception: " + throwRun.getResults().getError());
        List<CheckLine> checks = throwRun.getResults().getChecks();
        assertEquals(1, checks.size(), "the check recorded before the throw must survive");
        assertTrue(checks.get(0).pass());
        assertFalse(checks.get(0).name().startsWith("#"),
                "the error trailer must not be parsed as a check");
    }
}
