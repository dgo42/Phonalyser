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

import java.nio.file.Paths;

import org.eclipse.swt.widgets.Display;
import org.edgo.audio.measure.gui.MainWindow;

import lombok.extern.log4j.Log4j2;

/**
 * Executes one {@link AbstractAutomationScript} on a background thread.
 * Started by {@code GuiMain} right after the main window opened when the
 * application was launched with {@code --automation=<script>}.
 *
 * <p>Two — and only two — kinds of {@code <script>} are accepted:
 * <ul>
 *   <li>a path ending in {@code .body}: a <b>sandboxed body-only snippet</b>
 *       compiled on the fly by {@link ScriptCompiler#compileBodyAndLoad},
 *       which can call nothing but the inherited base-class API; and</li>
 *   <li>a bare class name: a script <b>already compiled into the
 *       application</b> (e.g. the bundled doc-screenshot run), loaded
 *       reflectively — {@link Class#forName} resolves against the classpath,
 *       so it can never load arbitrary code from a file.</li>
 * </ul>
 * Compiling an arbitrary {@code .java} <em>source file</em> is deliberately
 * NOT supported — that was an arbitrary-code-execution vector.  A run-time
 * script must be a sandboxed {@code .body} snippet.
 *
 * <p>When the script returns — or throws — the runner closes the main window,
 * ending the SWT event loop, so an unattended doc-generation run exits by
 * itself.
 */
@Log4j2
public final class AutomationRunner {

    /** {@code --automation=} values with this suffix are sandboxed body-only
     *  snippets compiled at runtime; a value with no path/extension is a
     *  trusted, already-compiled classpath class. */
    private static final String BODY_SUFFIX = ".body";
    private static final String JAVA_SOURCE_SUFFIX = ".java";

    private final Display    display;
    private final MainWindow window;
    /** Script class name — or a {@code .java} source-file path. */
    private final String     script;

    public AutomationRunner(Display display, MainWindow window, String script) {
        this.display = display;
        this.window  = window;
        this.script  = script;
    }

    /** Spawns the automation thread (daemon — it must never keep the JVM
     *  alive past the event loop). */
    public void start() {
        Thread t = new Thread(this::runScript, "gui-automation");
        t.setDaemon(true);
        t.start();
    }

    private void runScript() {
        log.info("Automation: running script {}", script);
        try {
            AbstractAutomationScript instance = resolveScriptClass()
                    .getConstructor(Display.class, MainWindow.class)
                    .newInstance(display, window);
            instance.run();
            log.info("Automation: script {} finished.", script);
        } catch (Exception ex) {
            log.error("Automation: script {} failed", script, ex);
        } finally {
            if (!display.isDisposed()) {
                display.syncExec(window::close);
            }
        }
    }

    /** A {@code .body} path is sandbox-compiled on the fly; a {@code .java}
     *  path is rejected (the arbitrary-source vector); anything else is a
     *  trusted class name resolved against the classpath. */
    private Class<? extends AbstractAutomationScript> resolveScriptClass() throws Exception {
        if (script.endsWith(BODY_SUFFIX)) {
            return new ScriptCompiler().compileBodyAndLoad(Paths.get(script));
        }
        if (script.endsWith(JAVA_SOURCE_SUFFIX)) {
            throw new IllegalStateException("Full-class automation source files are no longer "
                    + "accepted; provide a sandboxed body-only '.body' snippet instead: " + script);
        }
        return Class.forName(script).asSubclass(AbstractAutomationScript.class);
    }
}
