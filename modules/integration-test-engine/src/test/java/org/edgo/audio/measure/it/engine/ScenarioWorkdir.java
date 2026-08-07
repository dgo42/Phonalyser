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
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * One scenario's staging area: {@code target/it/<scenario>}, wiped and rebuilt
 * from the scenario's committed template.
 *
 * <p><b>Isolation is the whole point.</b>  Each scenario gets its own
 * directory, its own {@code app.data.dir} beneath it and its own copy of the
 * template {@code preferences.yaml}, so a run configures the application
 * completely and touches nothing of the developer's - not their preferences,
 * not their devices file, not their logs.  Nothing is ever written outside the
 * module's {@code target/}.
 *
 * <p>The template is read from the CLASS PATH copy of
 * {@code src/test/resources/scenarios/<name>}, which is what Maven has already
 * staged into {@code target/test-classes} - so the source tree is never written
 * to and never read from by a path guess.
 */
@Log4j2
public final class ScenarioWorkdir {

    /** Class-path root under which every scenario template lives. */
    private static final String SCENARIO_RESOURCES = "/scenarios/";

    /** Template files that configure the APPLICATION rather than the scenario,
     *  and must therefore land in the GUI's data directory.
     *
     *  <p>This is load-bearing and was got wrong once: the application reads
     *  its preferences from {@code app.data.dir} (AppPaths.file), NOT from its
     *  working directory.  A template copied to the scenario root is simply
     *  never read - the application starts on defaults and the scenario
     *  silently measures something other than what it says it measures.  Any
     *  scenario asserting a configured value would then be green for the wrong
     *  reason, which is worse than red. */
    private static final List<String> APP_CONFIG_FILES =
            List.of("preferences.yaml", "devices.yaml");
    /** The body file a scenario's GUI run executes, and the file the script
     *  writes its checks to.  Fixed names: a scenario is a directory, not a
     *  naming convention to remember. */
    private static final String SCRIPT_BODY  = "script.body";
    private static final String RESULTS_FILE = "results.tsv";

    @Getter
    private final String scenario;
    /** The scenario's own directory - the GUI child's working directory too. */
    @Getter
    private final Path dir;
    /** Isolated application data directories, one per child JVM: the two must
     *  not share, or the server and the GUI would fight over one preferences
     *  file and one device store. */
    @Getter
    private final Path guiDataDir;
    @Getter
    private final Path serverDataDir;

    public ScenarioWorkdir(EnginePaths paths, String scenario) throws IOException {
        this.scenario      = scenario;
        this.dir           = paths.getWorkRoot().resolve(scenario);
        this.guiDataDir    = dir.resolve("gui-data");
        this.serverDataDir = dir.resolve("server-data");
        stage();
    }

    /** The script body staged for the GUI child. */
    public Path scriptBody() {
        return dir.resolve(SCRIPT_BODY);
    }

    /** Where the script is told to write its checks. */
    public Path resultsFile() {
        return dir.resolve(RESULTS_FILE);
    }

    /** A file inside this scenario's directory - for a screenshot the script
     *  writes by relative name, or an asset the template brought along. */
    public Path file(String name) {
        return dir.resolve(name);
    }

    /** Wipes any previous run and copies the template in.  Deleting first, not
     *  merging: a stale results.tsv or screenshot from the previous run is
     *  exactly the kind of leftover that turns a broken scenario green. */
    private void stage() throws IOException {
        deleteRecursively(dir);
        Files.createDirectories(dir);
        Files.createDirectories(guiDataDir);
        Files.createDirectories(serverDataDir);
        copyTemplate();
        if (log.isInfoEnabled()) {
            log.info("IT: staged scenario '{}' in {}", scenario, dir);
        }
    }

    /** Copies the scenario's committed template in: the application's own
     *  configuration into the GUI data directory where the application will
     *  actually read it (see {@link #APP_CONFIG_FILES}), everything else -
     *  {@code script.body} and any assets - into the scenario directory, which
     *  is the child's working directory. */
    private void copyTemplate() throws IOException {
        Path template = templateDir();
        try (Stream<Path> files = Files.walk(template)) {
            for (Path source : files.filter(Files::isRegularFile).toList()) {
                String relative = template.relativize(source).toString();
                Path target = APP_CONFIG_FILES.contains(relative)
                        ? guiDataDir.resolve(relative)
                        : dir.resolve(relative);
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                Files.copy(source, target);
            }
        }
    }

    /** Whether the application's configuration really was staged where the
     *  application reads it - asserted by the scenarios that depend on it, so
     *  a template that stopped being picked up fails loudly instead of quietly
     *  measuring the defaults. */
    public boolean hasStagedPreferences() {
        return Files.isRegularFile(guiDataDir.resolve(APP_CONFIG_FILES.get(0)));
    }

    private Path templateDir() {
        String resource = SCENARIO_RESOURCES + scenario;
        URL url = getClass().getResource(resource);
        if (url == null) {
            throw new IllegalStateException("No scenario template on the class path at "
                    + resource + " - expected src/test/resources" + resource);
        }
        try {
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Scenario template path is not a file: " + url, e);
        }
    }

    private void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
