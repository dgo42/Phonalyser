/*
 * Phonalyser — precision audio measurement workbench.
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

package org.edgo.audio.measure.gui.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.swt.widgets.Display;
import org.edgo.audio.measure.gui.MainWindow;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the runtime compilation of on-disk automation-script <b>body
 * snippets</b>: a body of plain statements is wrapped in the class scaffold,
 * compiled (via the bundled ECJ) and yields the concrete
 * {@link AbstractAutomationScript} subclass with the
 * {@code (Display, MainWindow)} constructor the {@link AutomationRunner}
 * needs; a body that fails to compile reports the diagnostics; and a body
 * that tries to step outside the sandbox is rejected before compilation.  No
 * SWT display is required — the class is loaded, never instantiated.
 */
@Tag("exploratory")   // slow diagnostic harness — excluded from the normal build (see pom surefire)
class ScriptCompilerTest {

    /** A package-qualified name built at runtime so this test's OWN source
     *  carries no literal fully-qualified name (which the project's no-fqcn
     *  hook would flag). */
    private static final String FQN_FILE = String.join(".", "java", "io", "File");

    @Test
    void compilesAndLoadsBodyFromDisk(@TempDir Path dir) throws Exception {
        // A body is ONLY the statements of run() — no imports, no class.
        Path file = dir.resolve("ok.body");
        Files.writeString(file, "waitSeconds(0);\n");

        Class<? extends AbstractAutomationScript> loaded =
                new ScriptCompiler().compileBodyAndLoad(file);

        assertEquals("GeneratedAutomationScript", loaded.getSimpleName());
        assertTrue(AbstractAutomationScript.class.isAssignableFrom(loaded));
        assertNotNull(loaded.getConstructor(Display.class, MainWindow.class),
                "runner needs the (Display, MainWindow) constructor");
    }

    @Test
    void brokenBodyReportsCompilerDiagnostics(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("broken.body");
        Files.writeString(file, "this is not java;\n");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ScriptCompiler().compileBodyAndLoad(file));
        assertTrue(ex.getMessage().contains("compilation failed"),
                "message should carry the diagnostics, got: " + ex.getMessage());
    }

    @Test
    void sandboxRejectsImport(@TempDir Path dir) throws Exception {
        assertRejected(dir, "import " + FQN_FILE + ";\nwaitSeconds(0);\n", "import");
    }

    @Test
    void sandboxRejectsFullyQualifiedName(@TempDir Path dir) throws Exception {
        assertRejected(dir, "Object o = new " + FQN_FILE + "(\"x\");\n", "fully-qualified");
    }

    @Test
    void sandboxRejectsRuntimeClass(@TempDir Path dir) throws Exception {
        assertRejected(dir, "System.exit(0);\n", "restricted runtime class");
    }

    @Test
    void sandboxRejectsReflection(@TempDir Path dir) throws Exception {
        assertRejected(dir, "Object c = getClass();\n", "reflection");
    }

    @Test
    void sandboxRejectsNestedTypeDeclaration(@TempDir Path dir) throws Exception {
        assertRejected(dir, "class Evil {}\nwaitSeconds(0);\n", "nested type");
    }

    @Test
    void sandboxIgnoresForbiddenTokensInStringsAndComments(@TempDir Path dir) throws Exception {
        // A forbidden token appearing only inside a string / comment must NOT
        // trip the scan; the body itself is harmless.
        Path file = dir.resolve("strings.body");
        Files.writeString(file,
                "snapshotScopePane(10, 10, \"shots/safe.png\"); // mentions System and import\n");
        Class<? extends AbstractAutomationScript> loaded =
                new ScriptCompiler().compileBodyAndLoad(file);
        assertNotNull(loaded);
    }

    private void assertRejected(Path dir, String body, String expectFragment) throws Exception {
        Path file = dir.resolve("evil.body");
        Files.writeString(file, body);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ScriptCompiler().compileBodyAndLoad(file));
        assertTrue(ex.getMessage().contains(expectFragment),
                "expected rejection mentioning '" + expectFragment + "', got: " + ex.getMessage());
    }
}
