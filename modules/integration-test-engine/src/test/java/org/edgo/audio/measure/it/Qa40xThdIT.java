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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.stream.Stream;

import org.edgo.audio.measure.it.engine.GuiRun;
import org.edgo.audio.measure.it.engine.ScenarioWorkdir;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.junit.jupiter.api.Timeout;

/**
 * A MEASUREMENT, end to end: the generator plays a 1 kHz sine into the QA40x
 * mock's loopback, the analyzer captures it, and the FFT's own displayed
 * numbers are asserted.
 *
 * <p>This is the scenario the whole engine exists for.  Everything below the
 * JNA seam is the real production stack - device finder, transport, duplex
 * engine, capture ring, FFT worker, coherent averaging - driven through the
 * real UI by a sandboxed script, in a real JVM, with a real (if synthetic)
 * analyzer attached through the PRODUCTION {@code -Dlibusb.path} flag.
 *
 * <p>Deliberately NOT run with {@code noAudio}: a measurement scenario that
 * opened no device would prove nothing.
 *
 * <p>Requires the mock DLL, which Maven never builds - see
 * {@code modules/backend-qa40x/src/test/lib/libusb/build.cmd}.  Its absence
 * fails the scenario with that instruction rather than silently measuring the
 * developer's real hardware.
 *
 * <p><b>Tagged {@code qa40x-mock}, not {@code user-mode}, and therefore NOT in
 * the default tier.</b>  It follows the convention this repository already has
 * for tests that depend on that hand-built DLL - {@code Qa40xMockLoopbackIT}
 * carries the same tag and is excluded from the normal build for the same
 * reason.  The committed DLL is out of date: it exports no
 * {@code libusb_get_configuration}, which {@code Qa40xDeviceFinder} now calls,
 * so the analyzer cannot be opened at all and even the existing loopback test
 * fails.  Run this scenario with {@code -Dit.groups=qa40x-mock} once the mock
 * has been rebuilt.
 */
@Tag("qa40x-mock")
@TestInstance(Lifecycle.PER_CLASS)
class Qa40xThdIT extends ScenarioIT {

    private static final String SCENARIO = "qa40x-thd";
    /** Generous: a cold JVM, the ECJ compile of the script body, the device
     *  open and a 65536-point frame at 48 kHz (~1.37 s) all fit inside. */
    private static final int RUN_TIMEOUT_SECONDS = 120;

    private ScenarioWorkdir workdir;
    private GuiRun          run;

    @BeforeAll
    @Timeout(RUN_TIMEOUT_SECONDS + 60)
    void runScenario() throws Exception {
        workdir = stage(SCENARIO);
        // noAudio false, and the mock injected through the production
        // -Dlibusb.path: this scenario measures, so audio must really run.
        run = runGui(workdir, false, paths().qa40xMockDir(), Map.of(), RUN_TIMEOUT_SECONDS);
    }

    @Test
    void theScenarioTemplateWasActuallyLoaded() {
        // Without this the run could be measuring the DEFAULT backend at the
        // DEFAULT rate with the DEFAULT averaging and still look green - the
        // template is the entire experimental setup here.
        assertTrue(workdir.hasStagedPreferences(),
                "the QA40x template was not staged where the application reads it");
    }

    @TestFactory
    Stream<DynamicTest> everyCheckIsGreen() {
        return run.assertAllChecks();
    }
}
