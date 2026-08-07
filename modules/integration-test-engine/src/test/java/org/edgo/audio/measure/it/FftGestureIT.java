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
 * The application driven AT THE GUI: a rubberband drag and a ctrl+wheel zoom
 * on the FFT plot, synthesized as real OS-level input, asserted by the axis
 * state the view writes in response.
 *
 * <p>Nothing here calls a listener directly. The events go out to the window
 * system and arrive through the same path a hand on a mouse would take, which
 * is the only version of this test worth having: invoking the handler would
 * prove the handler exists, not that the gesture reaches it.
 *
 * <p>The gesture semantics were read out of the view's source rather than
 * assumed - button 1 with no modifier for the rubberband, Ctrl+wheel for the
 * magnitude zoom - and the effects are read back from the four axis
 * preferences the view already writes and saves, so no view was asked for
 * anything it does not already publish.
 *
 * <p>No spectrum is loaded and no audio runs: the zoom machinery acts on the
 * axes, which exist whether or not a trace is drawn on them. (A committed
 * {@code .fft} asset would have been the richer fixture, but none exists - the
 * only spectra in the tree are under the gitignored {@code results/}.)
 *
 * <p><b>This scenario posts real input to the desktop</b>, and that is why it
 * is tagged {@code gui-input} rather than {@code user-mode}: it is NOT in the
 * default tier. Synthesized input is delivered by the window system to whatever
 * is in front, so this scenario is the one thing in the suite that a second
 * window - or a hand on the mouse - can falsify. Run it deliberately:
 *
 * <pre>{@code mvn -P integration verify -pl modules/integration-test-engine -am
 *     -Dit.groups=gui-input -Dskip.jpackage=true}</pre>
 *
 * <p>Observed on this machine: the gestures did not reach the view.
 * The evidence is in the run's own artefacts - {@code fftFreqMaxHz} stayed at
 * 24000.0 and both magnitude bounds were untouched, while {@code fftFreqMinHz}
 * moved only because the application's OWN startup clamp raised it to the bin
 * size. So the events were posted and delivered somewhere else, rather than
 * being delivered and refused. The likely cause is Windows declining to give
 * the foreground to a process launched from a background console, which leaves
 * the injected clicks landing on whatever owns it. Next step for whoever picks
 * this up: have the scenario screenshot the pane immediately before the drag to
 * confirm what is actually on top, and try an activation click that is expected
 * to be swallowed before the real gesture.
 */
@Tag("gui-input")
@TestInstance(Lifecycle.PER_CLASS)
class FftGestureIT extends ScenarioIT {

    private static final String SCENARIO = "fft-gesture";
    /** Gestures are paced by design - each posted event is followed by a short
     *  settle so the OS queue can drain - so this run is slower than the
     *  others by construction. */
    private static final int RUN_TIMEOUT_SECONDS = 150;

    /** The template's deliberately non-default generator frequency. */
    private static final String LOAD_MARKER = "1234.5";
    /** A key the APPLICATION writes on save and the template deliberately does
     *  NOT contain, so it can only appear if the application rewrote the file
     *  it was given. */
    private static final String WRITTEN_ONLY_KEY = "formatVersion";

    private ScenarioWorkdir workdir;
    private GuiRun          run;

    @BeforeAll
    @Timeout(RUN_TIMEOUT_SECONDS + 60)
    void runScenario() throws Exception {
        workdir = stage(SCENARIO);
        run = runGui(workdir, true, null, Map.of(), RUN_TIMEOUT_SECONDS);
    }

    @Test
    void theScenarioTemplateWasActuallyLoaded() throws Exception {
        // The pinned axis window IS the starting point every check is relative
        // to, so this guard matters more here than in any other scenario: a run
        // that fell back to defaults would still zoom, still narrow a window,
        // and still report green - while measuring a different experiment.
        assertTrue(workdir.hasStagedPreferences(),
                "the gesture template was not staged where the application reads it");
        Path written = workdir.getGuiDataDir().resolve("preferences.yaml");
        assertTrue(Files.isRegularFile(written),
                "the application wrote no preferences of its own at " + written);
        String contents = Files.readString(written);
        // Both halves, for the reason SmokeIT documents: this file STARTS as
        // the copied template, so the marker alone proves nothing.
        // WRITTEN_ONLY_KEY appears only when the application rewrote it.
        assertTrue(contents.contains(WRITTEN_ONLY_KEY),
                "the application never rewrote " + written + " (no '" + WRITTEN_ONLY_KEY
                        + "') - it is not using this file as its data directory");
        assertTrue(contents.contains(LOAD_MARKER),
                "the application's own preferences do not carry the template's marker "
                        + LOAD_MARKER + " - the pinned axis window was ignored");
    }

    @TestFactory
    Stream<DynamicTest> everyCheckIsGreen() {
        return run.assertAllChecks();
    }
}
