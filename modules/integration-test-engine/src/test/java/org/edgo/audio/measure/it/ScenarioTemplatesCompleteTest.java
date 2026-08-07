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
import static org.junit.jupiter.api.DynamicTest.dynamicTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Every committed scenario template that pins a {@code perBackend} block must
 * also name its devices.
 *
 * <p><b>Why this exists.</b> A template that configures a backend but no device
 * fails 30 seconds downstream of its own cause: the application refuses to start
 * either engine ("No output device selected"), no measurement ever arrives, and
 * the scenario dies on a {@code waitUntil} timeout that reads like a
 * measurement that never came. That is exactly how {@code qa40x-thd} was
 * misdiagnosed the first time it ran - the analyzer had never been opened at
 * all. This turns that into a named failure at authoring time, in milliseconds.
 *
 * <p>It is the third guard in the same family, and the family is the point:
 * staging was proven first ({@code SmokeIT}'s load marker), then the bodies were
 * proven to compile ({@code ScenarioBodiesCompileTest}), and this one proves the
 * templates are COMPLETE. Each caught a real defect that the others could not
 * see.
 *
 * <p>Deliberately a plain {@code *Test}: no display, no device, no child
 * process, no {@code -P integration}. It reads text files.
 */
class ScenarioTemplatesCompleteTest {

    private static final String TEMPLATE = "preferences.yaml";

    /** Enumeration of the committed scenario files - shared with the body
     *  guard rather than walked twice. */
    private final ScenarioResources resources = new ScenarioResources();

    /** The block that selects a backend's rates - and the one whose presence
     *  makes the device names mandatory. */
    private static final String PER_BACKEND = "perBackend:";
    private static final String INPUT_DEVICE  = "inputDeviceName:";
    private static final String OUTPUT_DEVICE = "outputDeviceName:";

    @TestFactory
    Stream<DynamicTest> everyPerBackendTemplateNamesItsDevices() throws Exception {
        List<Path> templates =
                resources.find(path -> TEMPLATE.equals(path.getFileName().toString()));
        // A sweep that matched nothing would report green, which for a guard is
        // the one unacceptable outcome.
        Stream<DynamicTest> foundSome = Stream.of(
                dynamicTest("scenario-templates-found", () -> assertTrue(!templates.isEmpty(),
                        "no " + TEMPLATE + " found - this guard would be silently "
                                + "checking nothing")));
        Stream<DynamicTest> perTemplate = templates.stream()
                .map(template -> dynamicTest(resources.scenarioOf(template),
                        () -> assertComplete(template)));
        return Stream.concat(foundSome, perTemplate);
    }

    /** A template that pins {@code perBackend} has committed to a specific
     *  device configuration, and must therefore say WHICH device - otherwise the
     *  application has rates for a backend it will never open anything on. */
    private void assertComplete(Path template) throws Exception {
        String yaml = Files.readString(template, StandardCharsets.UTF_8);
        if (!yaml.contains(PER_BACKEND)) {
            return;   // no backend pinned: the scenario takes what it is given
        }
        assertTrue(yaml.contains(INPUT_DEVICE), template + " pins " + PER_BACKEND
                + " but names no " + INPUT_DEVICE + " - the application will refuse to "
                + "start capture and the scenario will time out far from this cause");
        assertTrue(yaml.contains(OUTPUT_DEVICE), template + " pins " + PER_BACKEND
                + " but names no " + OUTPUT_DEVICE + " - the application will refuse to "
                + "start the generator and the scenario will time out far from this cause");
    }

}
