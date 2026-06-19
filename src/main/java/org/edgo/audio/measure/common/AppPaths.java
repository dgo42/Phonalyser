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

package org.edgo.audio.measure.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import lombok.Getter;

/**
 * Per-user, writable application directories, resolved per operating system.
 *
 * <p>A packaged macOS {@code .app} bundle (and a {@code Program Files} install
 * on Windows) is read-only, so preferences and logs must live under the user's
 * profile rather than next to the executable — otherwise the save silently
 * fails and, because the log file is in the same read-only place, the failure
 * is invisible.
 *
 * <p>Base directory layout:
 * <ul>
 *   <li>Windows: {@code %APPDATA%\Phonalyser}</li>
 *   <li>macOS:   {@code ~/Library/Application Support/Phonalyser}</li>
 *   <li>Linux:   {@code $XDG_CONFIG_HOME/Phonalyser} (or {@code ~/.config/Phonalyser})</li>
 * </ul>
 * Logs go under a {@code logs/} child.  The base can be overridden with
 * {@code -Dapp.data.dir=<path>} (used by the automation tests).  Singleton —
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

    /** Lazily-obtained logger — AppPaths must NOT hold a class-load
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
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = (xdg != null && !xdg.isBlank())
                ? Paths.get(xdg)
                : Paths.get(home, ".config");
        return base.resolve(APP_DIR_NAME);
    }

    private Path resolveLogsDir(Path base) {
        String override = System.getProperty(DATA_DIR_PROPERTY);
        boolean overridden = override != null && !override.isBlank();
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!overridden && !os.contains("win") && !os.contains("mac")) {
            // Linux/Unix: prefer the system log dir, but only when it is
            // actually writable (e.g. a .deb that pre-creates it with the
            // right owner) — /var/log needs root, so a plain desktop launch
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
