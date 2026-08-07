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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * One child JVM running one main class - the piece that actually makes these
 * INTEGRATION tests: the application under test is started the way an operator
 * starts it, in its own process, on its own class path, and is judged by what
 * it leaves behind.
 *
 * <p>Owns the whole lifecycle: the command, the redirected output, the start,
 * the wait and the teardown.  A subclass supplies only what makes it the GUI or
 * the server, through the three methods below - which are asked at
 * {@link #start()} rather than passed to the constructor, so a subclass can
 * hold its own configuration as fields instead of flattening it into argument
 * lists before it has an object to put them on.
 *
 * <p>Both output streams go to files in the scenario's own directory rather
 * than to pipes.  A pipe nobody drains fills its buffer and blocks the child
 * for good, and the GUI is talkative; files also survive the test that failed,
 * which is when their contents are wanted.
 */
@Log4j2
public abstract class JvmProcess {

    /** How long {@link #stop()} lets a child shut down cleanly before it stops
     *  being asked politely. */
    private static final int STOP_GRACE_SECONDS = 5;

    private final Path classpathFile;
    private final Path workdir;

    /** Where this child's stdout went - the port banner is read from here, and
     *  it is the first thing to look at when a scenario fails. */
    @Getter
    private final Path stdoutFile;
    @Getter
    private final Path stderrFile;

    private Process process;

    /**
     * @param classpathFile the file dependency:build-classpath wrote
     * @param workdir       the child's WORKING DIRECTORY as well as where its
     *                      logs go - a script's relative screenshot path
     *                      therefore lands inside the scenario's own directory
     * @param logBaseName   distinguishes this child's two log files from the
     *                      other child's in the same scenario
     */
    protected JvmProcess(Path classpathFile, Path workdir, String logBaseName) {
        this.classpathFile = classpathFile;
        this.workdir       = workdir;
        this.stdoutFile    = workdir.resolve(logBaseName + "-stdout.log");
        this.stderrFile    = workdir.resolve(logBaseName + "-stderr.log");
    }

    /** The class the child JVM starts - named as a STRING by every subclass, so
     *  this engine never compiles against the system under test. */
    protected abstract String mainClass();

    /** {@code -D} flags and other JVM options for the child. */
    protected abstract List<String> jvmArgs();

    /** Arguments passed to the child's {@code main}. */
    protected abstract List<String> programArgs();

    /** Starts the child.  The class path comes from the file Maven wrote, so
     *  the child sees exactly the artefacts the reactor resolved. */
    public void start() throws IOException {
        String main = mainClass();
        List<String> command = new ArrayList<>();
        command.add(javaExecutable().toString());
        command.add("-cp");
        command.add(Files.readString(classpathFile, StandardCharsets.UTF_8).trim());
        command.addAll(jvmArgs());
        command.add(main);
        command.addAll(programArgs());

        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workdir.toFile())
                .redirectOutput(stdoutFile.toFile())
                .redirectError(stderrFile.toFile());
        process = builder.start();
        if (log.isInfoEnabled()) {
            log.info("IT: started {} (pid {}) in {}", main, process.pid(), workdir);
        }
    }

    /**
     * Waits for the child to exit and returns its exit code.
     *
     * <p>A timeout is a FAILURE, not a result: the child is killed and the
     * caller told, because a scenario whose application never finished has
     * proved nothing, and leaving the process behind would poison every
     * scenario after it.
     */
    public int awaitExit(int seconds) throws InterruptedException {
        if (process == null) {
            throw new IllegalStateException("awaitExit before start for " + mainClass());
        }
        if (!process.waitFor(seconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(STOP_GRACE_SECONDS, TimeUnit.SECONDS);
            throw new IllegalStateException(mainClass() + " did not exit within " + seconds
                    + " s and was killed - see " + stdoutFile + " and " + stderrFile);
        }
        int exitCode = process.exitValue();
        if (log.isInfoEnabled()) {
            log.info("IT: {} exited with {}", mainClass(), exitCode);
        }
        return exitCode;
    }

    /** Ends the child if it is still running.  Idempotent, and safe to call
     *  when it was never started - an {@code @AfterAll} must be able to run
     *  unconditionally, including after an assertion blew up mid-scenario. */
    public void stop() {
        if (process == null || !process.isAlive()) return;
        process.destroy();
        try {
            if (!process.waitFor(STOP_GRACE_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        if (log.isInfoEnabled()) {
            log.info("IT: stopped {}", mainClass());
        }
    }

    /** Whether the child is still running.  A readiness poll asks this so a
     *  child that DIED is reported at once, with its logs, instead of being
     *  waited on for the full timeout - the difference between a clear failure
     *  and a slow, uninformative one. */
    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    /** Everything the child has written to stdout so far - empty before the
     *  first flush rather than an error, since callers poll it.
     *
     *  <p>Decoded LENIENTLY, and that is not laziness: a child writes its
     *  console output in the platform encoding, so a log holding one em-dash
     *  makes a strict {@code Files.readString} throw
     *  {@code MalformedInputException} and lose the entire log - including the
     *  lines that would have explained the failure.  Undecodable bytes become
     *  U+FFFD; every ASCII fragment this engine matches on survives intact. */
    public String readStdout() throws IOException {
        if (!Files.isRegularFile(stdoutFile)) return "";
        return new String(Files.readAllBytes(stdoutFile), StandardCharsets.UTF_8);
    }

    /** The JVM running this test, so a child cannot end up on a different Java
     *  than the reactor compiled for. */
    private Path javaExecutable() {
        Path bin = Paths.get(System.getProperty("java.home"), "bin");
        Path windows = bin.resolve("java.exe");
        return Files.isRegularFile(windows) ? windows : bin.resolve("java");
    }
}
