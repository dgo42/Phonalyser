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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The desktop application as a child JVM, driven by a sandboxed automation
 * script and judged by its exit code and the result file the script wrote.
 *
 * <p>The main class is named as a STRING, not as a class reference, on purpose:
 * this engine must not compile against the system under test.  The application
 * arrives only through the class path file handed to the child.
 *
 * <p>The child's working directory is the scenario directory, so a script's
 * relative screenshot path lands in the scenario's own folder and nowhere else.
 */
public final class GuiProcess extends JvmProcess {

    private static final String MAIN_CLASS = "org.edgo.audio.measure.gui.GuiMain";
    private static final String LOG_BASE   = "gui";

    /** Pixel geometry must not follow the machine's display scaling, or a
     *  screenshot assertion becomes a property of the developer's monitor. */
    private static final String AUTOSCALE_ARG = "-Dswt.autoScale=100";

    private final ScenarioWorkdir     workdir;
    /** True for a scenario that only exercises the UI - it must then open no
     *  audio device at all.  A MEASUREMENT scenario passes false: its whole
     *  point is that real audio runs against the mock. */
    private final boolean             noAudio;
    /** The QA40x mock directory, or null when the scenario has no QA40x. */
    private final Path                libusbDir;
    /** Values the script reads back with {@code param(name)} - the only way to
     *  tell a scenario something not known when it was written, such as an
     *  ephemeral server port. */
    private final Map<String, String> params;

    public GuiProcess(EnginePaths paths, ScenarioWorkdir workdir, boolean noAudio, Path libusbDir,
            Map<String, String> params) {
        super(paths.getClasspathFile(), workdir.getDir(), LOG_BASE);
        this.workdir   = workdir;
        this.noAudio   = noAudio;
        this.libusbDir = libusbDir;
        this.params    = Map.copyOf(params);
    }

    @Override
    protected String mainClass() {
        return MAIN_CLASS;
    }

    @Override
    protected List<String> jvmArgs() {
        List<String> args = new ArrayList<>();
        args.add(AUTOSCALE_ARG);
        args.add("-Dapp.data.dir=" + workdir.getGuiDataDir());
        args.add("-Dphonalyser.automation.results=" + workdir.resultsFile());
        if (noAudio) {
            args.add("-Dphonalyser.automation.noAudio=true");
        }
        if (libusbDir != null) {
            args.add("-Dlibusb.path=" + libusbDir);
        }
        for (Map.Entry<String, String> param : params.entrySet()) {
            args.add("-Dphonalyser.automation.param." + param.getKey() + "=" + param.getValue());
        }
        return args;
    }

    @Override
    protected List<String> programArgs() {
        return List.of("--automation=" + workdir.scriptBody());
    }

    /** Runs the scenario to completion and collects the verdict: the exit code
     *  plus whatever the script recorded. */
    public GuiRun run(int timeoutSeconds) throws IOException, InterruptedException {
        start();
        int exitCode = awaitExit(timeoutSeconds);
        return new GuiRun(exitCode, new ResultFile(workdir.resultsFile()));
    }
}
