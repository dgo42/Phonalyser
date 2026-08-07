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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.edgo.audio.measure.it.engine.EnginePaths;
import org.edgo.audio.measure.it.engine.GuiProcess;
import org.edgo.audio.measure.it.engine.GuiRun;
import org.edgo.audio.measure.it.engine.JvmProcess;
import org.edgo.audio.measure.it.engine.ScenarioWorkdir;
import org.junit.jupiter.api.AfterAll;

/**
 * What every scenario test does the same way: resolve the engine's paths, stage
 * a workdir, run a child JVM against it, and take every child down again
 * afterwards.
 *
 * <p>Subclasses keep only what makes them THEIR scenario - which template, what
 * to pass it, and what to assert. The pieces below are the ones that were
 * otherwise copied verbatim into each test, and the teardown in particular is
 * the one nobody should be retyping: an orphaned GUI or server JVM holds a
 * window, a port and a device against every scenario that follows it.
 *
 * <p><b>Teardown is unconditional and null-safe by construction.</b> Every child
 * is registered with {@link #track} the moment it is created, so a scenario that
 * blows up half-way through staging still has exactly the processes it managed
 * to start stopped - in reverse order of starting, so a client is always closed
 * before the server it was talking to.
 *
 * <p>Lifecycle methods here are INSTANCE methods, which requires every subclass
 * to be {@code @TestInstance(PER_CLASS)}. They all are, and deliberately: a
 * scenario is state, and state belongs on an object rather than in statics.
 */
abstract class ScenarioIT {

    /** Resolved once per test class, from the properties the POM sets. */
    private final EnginePaths paths = new EnginePaths();
    /** Every child JVM this scenario started, in the order it started them. */
    private final List<JvmProcess> children = new ArrayList<>();

    /** Where the engine's fixed layout lives - the class path file, the work
     *  root, and the QA40x mock directory. */
    protected final EnginePaths paths() {
        return paths;
    }

    /** Stages {@code scenario}'s committed template into a fresh workdir. */
    protected final ScenarioWorkdir stage(String scenario) throws IOException {
        return new ScenarioWorkdir(paths, scenario);
    }

    /**
     * Runs the desktop application against {@code workdir} and collects the
     * verdict.  The child is tracked for teardown BEFORE it is started, so it
     * cannot be orphaned by a failure during the run.
     *
     * @param noAudio   true for a UI-only scenario, which must then open no
     *                  audio device at all; false for a measurement scenario
     * @param libusbDir the QA40x mock directory, or null for no QA40x
     * @param params    values the script reads back with {@code param(name)}
     */
    protected final GuiRun runGui(ScenarioWorkdir workdir, boolean noAudio, Path libusbDir,
            Map<String, String> params, int timeoutSeconds)
            throws IOException, InterruptedException {
        GuiProcess gui = track(new GuiProcess(paths, workdir, noAudio, libusbDir, params));
        return gui.run(timeoutSeconds);
    }

    /** Registers a child for teardown and hands it back, so a caller that needs
     *  the object itself - a server it must await and address - still gets it
     *  without having to remember to stop it. */
    protected final <T extends JvmProcess> T track(T child) {
        children.add(child);
        return child;
    }

    /** Stops every child, newest first.  Runs whatever happened above it. */
    @AfterAll
    final void stopEveryChild() {
        for (int i = children.size() - 1; i >= 0; i--) {
            children.get(i).stop();
        }
        children.clear();
    }
}
