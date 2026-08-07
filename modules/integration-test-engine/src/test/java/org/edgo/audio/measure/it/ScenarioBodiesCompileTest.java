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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.edgo.audio.measure.gui.automation.AbstractAutomationScript;
import org.edgo.audio.measure.gui.automation.ScriptCompiler;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Every committed scenario body is compiled - all of them, on every ordinary
 * build.
 *
 * <p><b>Why this exists.</b> A scenario body is only ever compiled when its
 * scenario RUNS, and some scenarios are behind tier gates: {@code qa40x-thd}
 * waits on a mock DLL, {@code fft-gesture} on a focused desktop. A body behind
 * a gate is source code that nothing checks, and exactly that happened -
 * {@code qa40x-thd/script.body} sat in the tree with a stray {@code *&#47;} and
 * five stranded lines of prose outside its comment, unable to compile
 * anywhere, and nothing said a word. A broken body must not be able to hide
 * behind a gate again.
 *
 * <p>It is deliberately a plain {@code *Test}, not an {@code *IT}: it needs no
 * display, no device, no child process and no {@code -P integration} - just
 * the compiler the application itself uses, which is on this module's test
 * class path through phonalyser-app. It runs in the normal build, in
 * milliseconds, and it is the cheapest possible check for the most easily
 * missed kind of breakage.
 *
 * <p>It also proves each body stays inside the SANDBOX, since
 * {@code compileBodyAndLoad} rejects a forbidden construct before it ever
 * reaches the compiler. What it does NOT do is run anything: a body that
 * compiles can still assert the wrong thing.
 */
class ScenarioBodiesCompileTest {

    private static final String BODY_SUFFIX = ".body";

    /** Enumeration of the committed scenario files - shared with the template
     *  guard rather than walked twice. */
    private final ScenarioResources resources = new ScenarioResources();

    @TestFactory
    Stream<DynamicTest> everyScenarioBodyCompiles() throws Exception {
        List<Path> bodies =
                resources.find(path -> path.getFileName().toString().endsWith(BODY_SUFFIX));
        // A factory that found nothing would report green, which for a guard is
        // the one unacceptable outcome - so the emptiness is itself a test.
        Stream<DynamicTest> foundSome = Stream.of(
                dynamicTest("scenario-bodies-found", () -> assertTrue(!bodies.isEmpty(),
                        "no " + BODY_SUFFIX + " files found - this guard would be "
                                + "silently checking nothing")));
        Stream<DynamicTest> perBody = bodies.stream()
                .map(body -> dynamicTest(resources.scenarioOf(body) + "/" + body.getFileName(),
                        () -> {
                            Class<? extends AbstractAutomationScript> loaded =
                                    new ScriptCompiler().compileBodyAndLoad(body);
                            assertNotNull(loaded, "compiled to nothing: " + body);
                        }));
        return Stream.concat(foundSome, perBody);
    }
}
