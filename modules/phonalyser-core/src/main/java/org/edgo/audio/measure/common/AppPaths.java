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

package org.edgo.audio.measure.common;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import lombok.Getter;

/**
 * Per-user, writable application directories, resolved per operating system.
 *
 * <p>A packaged macOS {@code .app} bundle (and a {@code Program Files} install
 * on Windows) is read-only, so preferences and logs must live under the user's
 * profile rather than next to the executable - otherwise the save silently
 * fails and, because the log file is in the same read-only place, the failure
 * is invisible.
 *
 * <p>Base directory layout:
 * <ul>
 *   <li>Windows: {@code %APPDATA%\Phonalyser}</li>
 *   <li>macOS:   {@code ~/Library/Application Support/Phonalyser}</li>
 *   <li>Linux:   {@code ~/.config/Phonalyser}</li>
 * </ul>
 * Logs go under a {@code logs/} child.  The base can be overridden with
 * {@code -Dapp.data.dir=<path>} (used by the automation tests).  Singleton -
 * access via {@link #instance()}.
 */
public final class AppPaths {

    private static final String APP_DIR_NAME      = "Phonalyser";
    private static final String DATA_DIR_PROPERTY = "app.data.dir";

    private static final AppPaths INSTANCE = new AppPaths();

    /** The per-user writable base directory for this application. */
    @Getter
    private final Path dataDir;
    /** Directory for rolling log files. */
    @Getter
    private final Path logsDir;

    private AppPaths() {
        this.dataDir = resolveDataDir();
        this.logsDir = resolveLogsDir(dataDir);
        ensureDir(dataDir);
        ensureDir(logsDir);
    }

    public static AppPaths instance() {
        return INSTANCE;
    }

    /** Lazily-obtained logger - AppPaths must NOT hold a class-load
     *  ({@code @Log4j2}) logger: it is touched while {@code GuiMain} is still
     *  computing {@code app.log.dir}, and a class-load logger would initialise
     *  log4j against the wrong (default) log path before the property is set. */
    private Logger log() {
        return LogManager.getLogger(AppPaths.class);
    }

    /** Resolves a file by name inside {@link #getDataDir()}. */
    public Path file(String name) {
        return dataDir.resolve(name);
    }

    /** Per-user writable i18n directory ({@code <dataDir>/i18n}), seeded from
     *  the bundled bundles on first run so translations can be edited here. */
    public Path i18nDir() {
        Path dir = dataDir.resolve("i18n");
        ensureDir(dir);
        return dir;
    }

    /** Per-user writable help directory ({@code <dataDir>/help}), seeded from
     *  the bundled help on first run so help pages can be translated here. */
    public Path helpDir() {
        Path dir = dataDir.resolve("help");
        ensureDir(dir);
        return dir;
    }

    /**
     * Copies the tree under {@code source} into {@code target} only when
     * {@code target} is empty (first run) and {@code source} exists.  Seeds the
     * editable i18n / help directories from the read-only bundled copies; once
     * seeded, user edits are preserved (delete the target to re-seed after an
     * upgrade).
     */
    public void seedDirIfEmpty(Path target, Path source) {
        if (source == null || !Files.isDirectory(source)) return;
        try {
            if (Files.isDirectory(target)) {
                try (Stream<Path> entries = Files.list(target)) {
                    if (entries.findAny().isPresent()) return;   // already populated
                }
            }
            Files.createDirectories(target);
            try (Stream<Path> tree = Files.walk(source)) {
                tree.forEach(src -> copyInto(source, src, target));
            }
            log().info("Seeded {} from bundled {}", target, source);
        } catch (IOException e) {
            log().warn("Could not seed {} from {}: {}", target, source, e.getMessage());
        }
    }

    /** Marker file recording which app version last staged a bundled tree
     *  into its per-user directory - see {@link #stageBundledTree}. */
    private static final String STAGED_MARKER_FILE = ".bundle-version";

    /**
     * Extracts every entry under {@code jarDirPrefix} (e.g. {@code "help/"})
     * of the running fat JAR into {@code target}, once per app
     * {@code version}: a {@code .bundle-version} marker inside {@code target}
     * records the last staged version and short-circuits subsequent starts.
     * The bare-JAR sibling of {@link #seedDirIfEmpty(Path, Path)} - used when
     * no external bundle directory exists to seed from.
     *
     * <p>On a version change the bundled files are re-extracted over the old
     * copy (stale content is worse than lost edits of the staged copy); files
     * the user added are left alone.  No-op in dev mode (the code source is a
     * directory) or when the JAR carries no such entries.  Tolerant - an I/O
     * failure is a guarded warn, never a throw.
     */
    public void stageBundledTree(String jarDirPrefix, Path target, String version) {
        Path jar = codeSourceJar();
        if (jar == null) return;
        Path root = target.toAbsolutePath().normalize();
        Path marker = root.resolve(STAGED_MARKER_FILE);
        try {
            if (Files.isRegularFile(marker)
                    && version.equals(Files.readString(marker).trim())) {
                return;                          // this version already staged
            }
            int files = 0;
            try (JarFile jf = new JarFile(jar.toFile())) {
                Enumeration<JarEntry> entries = jf.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || !name.startsWith(jarDirPrefix)) continue;
                    Path out = root.resolve(name.substring(jarDirPrefix.length())).normalize();
                    if (!out.startsWith(root)) continue;       // zip-slip guard
                    if (out.getParent() != null) Files.createDirectories(out.getParent());
                    try (InputStream in = jf.getInputStream(entry)) {
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                    files++;
                }
            }
            if (files > 0) {
                Files.writeString(marker, version);
                log().info("Staged bundled {} (v{}, {} files) to {}", jarDirPrefix, version, files, root);
            }
        } catch (IOException e) {
            log().warn("Could not stage bundled {} to {}: {}", jarDirPrefix, root, e.getMessage());
        }
    }

    /** An existing directory named {@code name} next to the running JAR (or
     *  next to the classes dir in dev mode), or {@code null} when absent /
     *  the code source is unknown. */
    public Path appAdjacentDir(String name) {
        try {
            URI src = AppPaths.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path here = Paths.get(src);
            Path parent = Files.isDirectory(here) ? here : here.getParent();
            if (parent != null) {
                Path dir = parent.resolve(name);
                if (Files.isDirectory(dir)) return dir;
            }
        } catch (Throwable ignored) {
            // CodeSource may be null for some classloaders.
        }
        return null;
    }

    /** The running fat JAR, or {@code null} when the code source is a
     *  directory (dev mode) or unknown. */
    private Path codeSourceJar() {
        try {
            URI src = AppPaths.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path here = Paths.get(src);
            return Files.isRegularFile(here) ? here : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Copies a single bundled classpath {@code resource} to {@code target} only
     * when {@code target} does not yet exist (first run) and the resource is
     * present.  The single-file, classpath-source sibling of
     * {@link #seedDirIfEmpty(Path, Path)}: {@code devices.yaml} ships as a
     * classpath resource (so it seeds in dev too, where no external bundle dir
     * exists), which the directory-tree copier can't read.  Tolerant - a missing
     * resource or an I/O failure is a guarded warn, never a throw, so a broken
     * seed can't stop the app from starting; once seeded, the user's edits are
     * preserved (delete the target to re-seed after an upgrade).
     */
    public void seedFileFromClasspathIfAbsent(Path target, String resource) {
        if (target == null || resource == null) return;
        if (Files.exists(target)) return;   // already seeded / user-created
        try (var in = AppPaths.class.getResourceAsStream(resource)) {
            if (in == null) {
                log().warn("Seed resource {} not found; {} not seeded", resource, target);
                return;
            }
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            log().info("Seeded {} from bundled {}", target, resource);
        } catch (IOException e) {
            log().warn("Could not seed {} from {}: {}", target, resource, e.getMessage());
        }
    }

    private void copyInto(Path sourceRoot, Path src, Path targetRoot) {
        Path dst = targetRoot.resolve(sourceRoot.relativize(src).toString());
        try {
            if (Files.isDirectory(src)) {
                Files.createDirectories(dst);
            } else {
                Files.createDirectories(dst.getParent());
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log().warn("Seed copy failed for {}: {}", src, e.getMessage());
        }
    }

    private Path resolveDataDir() {
        String override = System.getProperty(DATA_DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        String home = System.getProperty("user.home", ".");
        String os   = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            Path base = (appData != null && !appData.isBlank())
                    ? Paths.get(appData)
                    : Paths.get(home, "AppData", "Roaming");
            return base.resolve(APP_DIR_NAME);
        }
        if (os.contains("mac")) {
            return Paths.get(home, "Library", "Application Support", APP_DIR_NAME);
        }
        return Paths.get(home, ".config", APP_DIR_NAME);
    }

    private Path resolveLogsDir(Path base) {
        String override = System.getProperty(DATA_DIR_PROPERTY);
        boolean overridden = override != null && !override.isBlank();
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!overridden && !os.contains("win") && !os.contains("mac")) {
            // Linux/Unix: prefer the system log dir, but only when it is
            // actually writable (e.g. a .deb that pre-creates it with the
            // right owner) - /var/log needs root, so a plain desktop launch
            // falls back to the per-user data dir rather than losing logs.
            Path systemLogs = Paths.get("/var/log", APP_DIR_NAME.toLowerCase());
            if (isWritableDir(systemLogs)) {
                return systemLogs;
            }
        }
        return base.resolve("logs");
    }

    private boolean isWritableDir(Path dir) {
        try {
            Files.createDirectories(dir);
            return Files.isWritable(dir);
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    private void ensureDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log().warn("Could not create application directory {}: {}", dir, e.getMessage());
        }
    }
}
