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

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.eclipse.swt.widgets.Display;
import org.edgo.audio.measure.gui.MainWindow;

import lombok.extern.log4j.Log4j2;

/**
 * Compiles an automation-script <b>body snippet</b> at runtime and loads the
 * resulting {@link AbstractAutomationScript} subclass — so a doc-generation
 * sequence can be edited on disk and re-run without rebuilding the
 * application.  Used by {@link AutomationRunner} when {@code --automation=}
 * points at a {@code .body} file.
 *
 * <h2>Body-only scripts (sandbox)</h2>
 * <p>A script file is <em>only the statements of {@code run()}</em> — no
 * imports, no class or method declaration.  This compiler supplies the whole
 * scaffold (the {@code extends AbstractAutomationScript} class, the
 * constructor and the {@code run()} signature, all referenced by fully
 * qualified name so the generated file needs no imports) and inserts the body
 * verbatim.  Because the body has no imports and is checked by
 * {@link #rejectIfUnsafe} first, it can reference nothing but the methods it
 * <em>inherits</em> from the base class plus {@code java.lang} primitives — it
 * cannot import a type, name one fully, declare a nested type, or reach the
 * dangerous always-available classes ({@code Runtime}, {@code ProcessBuilder},
 * {@code System}, {@code Class}, reflection).  A script's capability surface
 * is therefore exactly the {@code protected} API of
 * {@link AbstractAutomationScript}.
 *
 * <p>The compiler is resolved through the standard {@code javax.tools} API:
 * the bundled ECJ registers itself as a {@link JavaCompiler} service, with the
 * JDK's own system compiler as fallback.
 */
@Log4j2
public final class ScriptCompiler {

    /** Language level the scripts are compiled against — matches the
     *  application's own build level. */
    private static final String RELEASE_LEVEL = "17";

    /** Name of the synthetic class the body is wrapped in. */
    private static final String GENERATED_CLASS = "GeneratedAutomationScript";

    /** Constructs a script body may not contain — each would let it reach
     *  beyond the inherited base-class API.  Checked after comments and
     *  string / char literals are stripped, so a path string or comment can't
     *  trip them.  Parallel with {@link #FORBIDDEN_LABEL}. */
    private static final Pattern[] FORBIDDEN = {
            Pattern.compile("\\bimport\\b"),
            Pattern.compile("\\bpackage\\b"),
            Pattern.compile("\\b(?:class|interface|enum|record)\\s+\\w"),
            Pattern.compile("\\b(?:java|javax|jakarta|sun|jdk|org|com)\\s*\\."),
            Pattern.compile("\\b(?:System|Runtime|ProcessBuilder|ProcessHandle|Thread"
                    + "|Class|ClassLoader|SecurityManager|Unsafe)\\b"),
            Pattern.compile("\\b(?:forName|setAccessible|getMethod|getField|getClass"
                    + "|getDeclared\\w*|invoke)\\b"),
    };
    private static final String[] FORBIDDEN_LABEL = {
            "import statement", "package declaration", "nested type declaration",
            "fully-qualified name", "restricted runtime class", "reflection call",
    };

    /**
     * Reads the body snippet at {@code bodyFile}, rejects it if it steps
     * outside the sandbox, wraps it in the class scaffold, compiles it, and
     * returns the loaded script class.
     *
     * @throws IOException           on a file-system problem
     * @throws IllegalStateException when the body is unsafe, no compiler is
     *         available, the wrapped source doesn't compile (message carries
     *         the diagnostics), or it holds no concrete script class
     */
    public Class<? extends AbstractAutomationScript> compileBodyAndLoad(Path bodyFile) throws IOException {
        if (!Files.isRegularFile(bodyFile)) {
            throw new IllegalStateException("Automation script body not found: " + bodyFile);
        }
        String body = Files.readString(bodyFile, StandardCharsets.UTF_8);
        rejectIfUnsafe(body, bodyFile);

        Path srcDir  = Files.createTempDirectory("phonalyser-automation-src");
        Path srcFile = srcDir.resolve(GENERATED_CLASS + ".java");
        Files.writeString(srcFile, wrapBody(body), StandardCharsets.UTF_8);

        Path outDir = Files.createTempDirectory("phonalyser-automation");
        compileSource(srcFile, outDir, bodyFile);
        return loadScriptClass(outDir);
    }

    /** Scans the body (comments + string / char literals removed) and throws
     *  if it contains any {@link #FORBIDDEN} construct. */
    private void rejectIfUnsafe(String body, Path bodyFile) {
        String cleaned = stripCommentsAndLiterals(body);
        for (int i = 0; i < FORBIDDEN.length; i++) {
            Matcher m = FORBIDDEN[i].matcher(cleaned);
            if (m.find()) {
                throw new IllegalStateException("Automation script " + bodyFile
                        + " is not allowed to use a " + FORBIDDEN_LABEL[i]
                        + " (found \"" + m.group().trim() + "\"). A script body may only call "
                        + "methods inherited from AbstractAutomationScript.");
            }
        }
    }

    /** Replaces line / block comments and string / char literals with neutral
     *  text so the sandbox scan only sees executable tokens. */
    private String stripCommentsAndLiterals(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("(?m)//.*$", " ")
                .replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"")
                .replaceAll("'(?:\\\\.|[^'\\\\])*'", "' '");
    }

    /** Wraps a verified body in the class scaffold.  Every type is referenced
     *  by {@link Class#getName()} so the generated file needs no imports — the
     *  body therefore inherits its entire capability surface and nothing
     *  more. */
    private String wrapBody(String body) {
        String base = AbstractAutomationScript.class.getName();
        String disp = Display.class.getName();
        String win  = MainWindow.class.getName();
        String nl = "\n";
        return "public final class " + GENERATED_CLASS + " extends " + base + " {" + nl
             + "    public " + GENERATED_CLASS + "(" + disp + " display, " + win + " window) {" + nl
             + "        super(display, window);" + nl
             + "    }" + nl
             + "    @Override protected void run() throws Exception {" + nl
             + body + nl
             + "    }" + nl
             + "}" + nl;
    }

    /** Compiles {@code srcFile} into {@code outDir}; {@code origin} names the
     *  body file in error messages. */
    private void compileSource(Path srcFile, Path outDir, Path origin) throws IOException {
        JavaCompiler compiler = resolveCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm =
                     compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            List<String> options = List.of(
                    "--release", RELEASE_LEVEL,
                    "-classpath", compilationClasspath(),
                    "-d", outDir.toString());
            Boolean ok = compiler.getTask(null, fm, diagnostics, options, null,
                    fm.getJavaFileObjects(srcFile)).call();
            if (ok == null || !ok) {
                String report = format(diagnostics);
                log.error("Automation script {} failed to compile:\n{}", origin, report);
                throw new IllegalStateException(
                        "Automation script compilation failed:\n" + report);
            }
        }
        if (log.isInfoEnabled()) {
            log.info("Automation: compiled body {} with {}", origin, compiler.getClass().getSimpleName());
        }
    }

    /** The JVM classpath plus the application's own code-source location.
     *  The latter is normally redundant, but launchers that hide the real
     *  classpath behind a manifest-only jar (test runners, some app
     *  starters) would otherwise leave the script unable to resolve
     *  {@link AbstractAutomationScript}. */
    private String compilationClasspath() {
        String cp = System.getProperty("java.class.path", "");
        try {
            CodeSource cs = AbstractAutomationScript.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                cp = cp + File.pathSeparator + Paths.get(cs.getLocation().toURI());
            }
        } catch (Exception ex) {
            log.warn("Automation: could not resolve own code source: {}", ex.getMessage());
        }
        return cp;
    }

    /** The bundled ECJ — preferred so scripts compile identically on a
     *  dev JDK and in the jpackaged (JRE-only) app — with the JDK's own
     *  compiler as fallback.  On a JDK, {@link ServiceLoader} lists the
     *  JDK's module-declared provider BEFORE classpath providers, so the
     *  first provider that is not the system compiler is the bundled one. */
    private JavaCompiler resolveCompiler() {
        JavaCompiler system = ToolProvider.getSystemJavaCompiler();
        for (JavaCompiler provided : ServiceLoader.load(JavaCompiler.class)) {
            if (system == null || provided.getClass() != system.getClass()) {
                return provided;
            }
        }
        if (system == null) {
            throw new IllegalStateException(
                    "No Java compiler available — neither ECJ on the classpath nor a JDK runtime.");
        }
        return system;
    }

    /** Loads every class compiled into {@code outDir} (parented on the
     *  application classloader) and returns the one concrete script
     *  subclass. */
    private Class<? extends AbstractAutomationScript> loadScriptClass(Path outDir) throws IOException {
        // Deliberately not closed: the loaded script class (and any helper
        // classes it references lazily) must stay loadable for the run.
        //@SuppressWarnings("resource")
        URLClassLoader loader = new URLClassLoader(
                new URL[]{ outDir.toUri().toURL() }, getClass().getClassLoader());
        Class<? extends AbstractAutomationScript> found = null;
        for (String name : compiledClassNames(outDir)) {
            Class<?> candidate;
            try {
                candidate = Class.forName(name, false, loader);
            } catch (ClassNotFoundException | LinkageError ex) {
                log.warn("Automation: compiled class {} failed to load: {}", name, ex.getMessage());
                continue;
            }
            if (!AbstractAutomationScript.class.isAssignableFrom(candidate)
                    || Modifier.isAbstract(candidate.getModifiers())) {
                continue;
            }
            if (found != null) {
                throw new IllegalStateException("Script declares more than one concrete "
                        + "automation class: " + found.getName() + " and " + candidate.getName());
            }
            found = candidate.asSubclass(AbstractAutomationScript.class);
        }
        if (found == null) {
            throw new IllegalStateException(
                    "Script contains no concrete subclass of AbstractAutomationScript.");
        }
        return found;
    }

    /** Binary names of all {@code .class} files under {@code outDir}
     *  (relative path, separators → dots, extension stripped). */
    private List<String> compiledClassNames(Path outDir) throws IOException {
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.walk(outDir)) {
            files.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                String rel = outDir.relativize(p).toString();
                names.add(rel.substring(0, rel.length() - ".class".length())
                        .replace('\\', '.').replace('/', '.'));
            });
        }
        return names;
    }

    private String format(DiagnosticCollector<JavaFileObject> diagnostics) {
        StringBuilder sb = new StringBuilder();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            sb.append(d.getKind()).append(' ')
              .append(d.getSource() != null ? d.getSource().getName() : "?")
              .append(':').append(d.getLineNumber()).append(' ')
              .append(d.getMessage(null)).append('\n');
        }
        return sb.toString();
    }
}
