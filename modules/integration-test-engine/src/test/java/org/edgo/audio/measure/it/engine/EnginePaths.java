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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import lombok.Getter;

/**
 * Where everything the engine needs lives - resolved once, from the three
 * system properties the POM sets, and handed to the pieces that need it.
 *
 * <p>Properties rather than guesswork: Maven knows its own build directory and
 * its own basedir, and a test that reconstructed them by walking up from a
 * working directory would be wrong the moment it ran from somewhere else.
 *
 * <p>A missing property fails LOUDLY and at once.  The alternative - a default
 * that quietly points somewhere plausible - would let a scenario stage itself
 * outside the module's {@code target/}, which is the one thing the rules here
 * forbid absolutely.
 */
public final class EnginePaths {

    private static final String CLASSPATH_FILE_PROPERTY = "it.classpath.file";
    private static final String WORK_ROOT_PROPERTY      = "it.work.root";
    private static final String REPO_ROOT_PROPERTY      = "it.repo.root";

    /** Where the QA40x user-mode mock's drop-in {@code libusb-1.0.dll} is built,
     *  relative to the repository root.  Injected into a child JVM through the
     *  PRODUCTION {@code -Dlibusb.path} flag - the mock is a drop-in library,
     *  never a code path in the application.  Release first, then Debug: the
     *  same order, and the same two directories, that
     *  {@code Qa40xMockLoopbackIT} resolves. */
    private static final String QA40X_MOCK_RELATIVE =
            "modules/backend-qa40x/src/test/lib/libusb/x64";
    private static final String RELEASE_DIR = "Release";
    private static final String DEBUG_DIR   = "Debug";
    private static final String DLL_FILENAME = "libusb-1.0.dll";

    /** The file dependency:build-classpath wrote - the class path every child
     *  JVM is started with. */
    @Getter
    private final Path classpathFile;
    /** {@code target/it} - every scenario stages itself in a directory under
     *  this one, and nothing is ever written outside it. */
    @Getter
    private final Path workRoot;
    @Getter
    private final Path repoRoot;

    public EnginePaths() {
        classpathFile = required(CLASSPATH_FILE_PROPERTY);
        workRoot      = required(WORK_ROOT_PROPERTY);
        repoRoot      = required(REPO_ROOT_PROPERTY);
    }

    /** The QA40x mock library directory, checked to actually hold the DLL - a
     *  scenario that silently ran without the mock would measure the developer's
     *  real hardware, or nothing at all, and call either a result. */
    public Path qa40xMockDir() {
        Path x64 = repoRoot.resolve(QA40X_MOCK_RELATIVE).normalize();
        Path release = x64.resolve(RELEASE_DIR);
        Path debug   = x64.resolve(DEBUG_DIR);
        if (Files.isRegularFile(release.resolve(DLL_FILENAME))) return release;
        if (Files.isRegularFile(debug.resolve(DLL_FILENAME)))   return debug;
        throw new IllegalStateException("QA40x mock " + DLL_FILENAME + " not found in "
                + release + " or " + debug + " - build it with "
                + "modules\\backend-qa40x\\src\\test\\lib\\libusb\\build.cmd (VS2015)");
    }

    private Path required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("System property " + property + " is not set - run the "
                    + "integration tests through Maven: mvn -P integration verify "
                    + "-pl modules/integration-test-engine -am");
        }
        return Paths.get(value).toAbsolutePath().normalize();
    }
}
