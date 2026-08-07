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
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Where the committed scenario templates live on disk, and how to enumerate
 * them - owned in one place because more than one authoring-time guard needs
 * exactly this.
 *
 * <p>The files are read from the CLASS PATH copy under {@code target/test-classes}
 * that Maven has already staged, so the source tree is never read by a path
 * guess and never written to.
 */
final class ScenarioResources {

    /** Class-path root every scenario template lives under. */
    private static final String SCENARIOS = "/scenarios";

    /** Every scenario file matching {@code match}, in stable sorted order so a
     *  failure names the same test on every run. */
    List<Path> find(Predicate<Path> match) throws IOException {
        URL url = getClass().getResource(SCENARIOS);
        if (url == null) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(toPath(url))) {
            return files.filter(Files::isRegularFile).filter(match).sorted().toList();
        }
    }

    /** The scenario directory a file belongs to - the readable name for a
     *  per-file dynamic test. */
    String scenarioOf(Path file) {
        return file.getParent().getFileName().toString();
    }

    private Path toPath(URL url) {
        try {
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("scenario resources are not on disk: " + url, e);
        }
    }
}
