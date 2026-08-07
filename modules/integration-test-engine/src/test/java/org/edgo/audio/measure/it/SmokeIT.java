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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
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
 * The application starts, builds its window, renders it and exits cleanly - on
 * a machine whose sound devices it has never heard of.
 *
 * <p>Modest as it sounds, this is the scenario that fails first and loudest
 * when something structural breaks: a missing resource in a module jar, an
 * i18n bundle that no longer resolves, a class path the split left incomplete.
 * It needs no audio and no hardware, which is why it is the one scenario that
 * can run absolutely everywhere.
 *
 * <p>The real evidence is the PNG: a file with genuine PNG magic bytes and a
 * plausible size can only exist if the window was actually built and painted.
 *
 * <p>{@code Lifecycle.PER_CLASS} so the fixtures are instance methods on an
 * instance field - the scenario is state, and it belongs on an object.
 */
@Tag("user-mode")
@TestInstance(Lifecycle.PER_CLASS)
class SmokeIT extends ScenarioIT {

    private static final String SCENARIO = "smoke";
    /** Generous: the first launch compiles the script body with ECJ, and a cold
     *  JVM has every class of the desktop closure still to load. */
    private static final int RUN_TIMEOUT_SECONDS = 120;

    private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 'P', 'N', 'G' };
    /** Even a blank 1366×768 pane compresses to several KB; below this the file
     *  is a truncated or failed write, not a screenshot. */
    private static final int MIN_PNG_BYTES = 500;
    private static final String SCREENSHOT = "smoke.png";
    /** The template's deliberately non-default generator frequency - see the
     *  scenario's preferences.yaml. */
    private static final String LOAD_MARKER = "1234.5";
    /** A key the APPLICATION writes on save and the template deliberately does
     *  NOT contain - so it can only appear if the application rewrote the file
     *  it was given.  The template must stay free of it. */
    private static final String WRITTEN_ONLY_KEY = "formatVersion";

    private ScenarioWorkdir workdir;
    private GuiRun          run;

    @BeforeAll
    @Timeout(RUN_TIMEOUT_SECONDS + 60)
    void runScenario() throws Exception {
        workdir = stage(SCENARIO);
        // noAudio: this scenario is about the UI coming up, and it must not
        // open a device on the developer's machine to prove it.
        run = runGui(workdir, true, null, Map.of(), RUN_TIMEOUT_SECONDS);
    }

    @TestFactory
    Stream<DynamicTest> everyCheckIsGreen() {
        return run.assertAllChecks();
    }

    @Test
    void theScenarioTemplateWasActuallyLoaded() throws Exception {
        // Not ceremony - this caught a real defect.  The template used to be
        // staged in the child's working directory, but the application reads
        // its preferences from app.data.dir, so it was never read at all and
        // every scenario silently ran on defaults.  Nothing failed, because
        // nothing asserted it; a measurement scenario would have "passed"
        // while measuring the wrong configuration entirely.
        assertTrue(workdir.hasStagedPreferences(),
                "the template was not staged where the application reads it");
        Path written = workdir.getGuiDataDir().resolve("preferences.yaml");
        assertTrue(Files.isRegularFile(written),
                "the application wrote no preferences of its own at " + written);
        String contents = Files.readString(written);
        // BOTH halves are needed, and the marker alone is NOT enough: this file
        // starts life as the copied template, which already carries the marker,
        // so an application that ignored app.data.dir entirely would still
        // satisfy it.  WRITTEN_ONLY_KEY is emitted by the application's own
        // save and is deliberately absent from the template, so its presence
        // proves the application really read and rewrote THIS file.
        assertTrue(contents.contains(WRITTEN_ONLY_KEY),
                "the application never rewrote " + written + " (no '" + WRITTEN_ONLY_KEY
                        + "') - it is not using this file as its data directory");
        assertTrue(contents.contains(LOAD_MARKER),
                "the application's own preferences do not carry the template's marker "
                        + LOAD_MARKER + " - the template was ignored and the run used defaults");
    }

    @Test
    void theWindowWasActuallyPainted() throws Exception {
        Path png = workdir.file(SCREENSHOT);
        assertTrue(Files.isRegularFile(png), "no screenshot at " + png
                + " - the script ran but the window produced no image");
        byte[] bytes = Files.readAllBytes(png);
        assertTrue(bytes.length >= MIN_PNG_BYTES,
                png + " is suspiciously small: " + bytes.length + " bytes");
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            assertEquals(PNG_SIGNATURE[i], bytes[i], png + " is not a PNG");
        }
    }
}
