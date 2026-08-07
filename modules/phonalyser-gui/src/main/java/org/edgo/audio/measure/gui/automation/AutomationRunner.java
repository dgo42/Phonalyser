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
 * <p>Two - and only two - kinds of {@code <script>} are accepted:
 * <ul>
 *   <li>a path ending in {@code .body}: a <b>sandboxed body-only snippet</b>
 *       compiled on the fly by {@link ScriptCompiler#compileBodyAndLoad},
 *       which can call nothing but the inherited base-class API; and</li>
 *   <li>a bare class name: a script <b>already compiled into the
 *       application</b> (e.g. the bundled doc-screenshot run), loaded
 *       reflectively - {@link Class#forName} resolves against the classpath,
 *       so it can never load arbitrary code from a file.</li>
 * </ul>
 * Compiling an arbitrary {@code .java} <em>source file</em> is deliberately
 * NOT supported - that was an arbitrary-code-execution vector.  A run-time
 * script must be a sandboxed {@code .body} snippet.
 *
 * <p>When the script returns - or throws - the runner closes the main window,
 * ending the SWT event loop, so an unattended doc-generation run exits by
 * itself.
 *
 * <p>The runner owns the run's VERDICT: anything that aborts the script -
 * a resolve / compile failure or a throw out of {@code run()} - marks the
 * run failed, and {@code GuiMain} turns that into the process exit code.
 * The exception is still only logged, never rethrown, so the close-the-window
 * behaviour is unchanged for an unattended documentation run.
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
    /** Script class name - or a {@code .java} source-file path. */
    private final String     script;

    /** Set by the automation thread when the script aborted; read by the
     *  main thread after the event loop ended - hence volatile. */
    private volatile boolean failed;

    public AutomationRunner(Display display, MainWindow window, String script) {
        this.display = display;
        this.window  = window;
        this.script  = script;
    }

    /** Spawns the automation thread (daemon - it must never keep the JVM
     *  alive past the event loop). */
    public void start() {
        Thread t = new Thread(this::runScript, "gui-automation");
        t.setDaemon(true);
        t.start();
    }

    /** Whether the script aborted - the run's verdict, for the caller that
     *  decides the process exit code.  Hand-written rather than generated
     *  because the name is the contract ({@code isFailed} would read wrong
     *  for a verdict). */
    public boolean hasFailed() {
        return failed;
    }

    private void runScript() {
        log.info("Automation: running script {}", script);
        AbstractAutomationScript instance = null;
        Throwable error = null;
        try {
            instance = resolveScriptClass()
                    .getConstructor(Display.class, MainWindow.class)
                    .newInstance(display, window);
            instance.run();
            log.info("Automation: script {} finished.", script);
        } catch (Throwable ex) {
            // Throwable, not Exception: an SWTError (display disposed under the
            // script) or an AssertionError aborts the run just as thoroughly as
            // an exception, and a verdict that called those runs successful
            // would be worthless.  Still only logged, never rethrown.
            error  = ex;
            failed = true;
            log.error("Automation: script {} failed", script, ex);
        } finally {
            // In the finally, and with the error, so a run that DIED still
            // writes down every check it had already recorded plus what killed
            // it - the post-mortem is the only thing the harness gets.  Null
            // only when the script could not be resolved or compiled at all,
            // and then there are no checks and no file to write.
            if (instance != null && !instance.finishAutomation(error)) {
                failed = true;
            }
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
