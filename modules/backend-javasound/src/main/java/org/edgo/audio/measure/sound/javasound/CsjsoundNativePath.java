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

package org.edgo.audio.measure.sound.javasound;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.regex.Pattern;

import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;

/**
 * Makes the bundled csjsound WASAPI-exclusive JavaSound provider loadable from
 * the FAT-JAR distribution.  The provider (com.cleansine) resolves its native
 * with {@code System.loadLibrary("csjsound_" + os.arch)}, which searches
 * {@code java.library.path} ONLY — the installed app ships the DLL in
 * {@code lib\windows} and points the property there, but the bare platform JAR
 * had no such folder, so the provider registered no exclusive-mode mixers and
 * the JavaSound backend stayed at the DirectSound 16-bit ceiling (field
 * report: E-MU 1212M on Win7 x86).
 *
 * <p>The JAR carries the DLL as a classpath resource ({@code win32-x86[-64]/},
 * the same convention as the PortAudio / libusb copies); this helper extracts
 * it into the process's CURRENT DIRECTORY.  That is the only directory that is
 * both guaranteed to be on the library path and stageable at runtime: the
 * Windows JVM launcher appends {@code "."} to {@code java.library.path} at VM
 * init, and since JDK&nbsp;12 the path is snapshotted right there — setting the
 * property later (the first version of this helper) is silently ignored, which
 * left the provider without its native on every bare-JAR run.  The launcher
 * {@code .bat} changes into the JAR's folder first, so the DLL lands next to
 * the JAR and survives for the next start.  No-ops on non-Windows, in the dev
 * tree (code source is a directory — use {@code lib\windows} there), on
 * installed layouts (DLL already reachable via {@code -Djava.library.path}),
 * and on builds without the resource.
 */
@Log4j2
@UtilityClass
public class CsjsoundNativePath {

    /** Stages the csjsound DLL into the current directory (the {@code "."}
     *  entry of {@code java.library.path}) so the provider's
     *  {@code System.loadLibrary} finds it — see the class doc for why no
     *  other location works on a bare-JAR run. */
    public void installForFatJar() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return;
        }
        if (!runningFromJar()) {
            return;                                  // dev tree — lib\windows serves the DLL
        }
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String dll  = "csjsound_" + arch + ".dll";   // the exact name the provider loads
        Path   cwd  = Paths.get("").toAbsolutePath().normalize();
        String libraryPath = System.getProperty("java.library.path", "");
        for (String dir : libraryPath.split(Pattern.quote(File.pathSeparator))) {
            if (dir.isBlank() || !Files.exists(Paths.get(dir, dll))) continue;
            // Reachable in an installed layout / PATH dir — nothing to stage.
            // A copy in the CURRENT dir does not short-circuit: it may be a
            // stale version from an earlier run, so fall through and refresh it.
            if (!Paths.get(dir).toAbsolutePath().normalize().equals(cwd)) {
                return;
            }
        }
        boolean is32 = arch.equals("x86") || arch.contains("i386") || arch.contains("i686");
        String resource = "/" + (is32 ? "win32-x86" : "win32-x86-64") + "/" + dll;
        try (InputStream in = CsjsoundNativePath.class.getResourceAsStream(resource)) {
            if (in == null) {
                return;                              // not a bundled build (or exotic arch)
            }
            Path target = cwd.resolve(dll);
            try {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException locked) {
                // Another (or an earlier) instance holds the DLL mapped — Windows
                // forbids replacing a loaded image.  Reuse the existing copy.
                if (!Files.exists(target)) {
                    throw locked;
                }
            }
            log.info("csjsound native staged for the fat-jar run: {}", target);
        } catch (IOException e) {
            log.warn("Could not stage the csjsound native ({}) into {} — exclusive-mode JavaSound"
                    + " mixers stay unavailable.  Manual fix: extract {} from the JAR next to it"
                    + " (or into any PATH directory).  Cause: {}",
                    resource, cwd, dll, e.toString());
        }
    }

    /** {@code true} when the code source is a JAR file (a packaged run) rather
     *  than the dev tree's classes directory. */
    private boolean runningFromJar() {
        try {
            URI src = CsjsoundNativePath.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            return Files.isRegularFile(Paths.get(src));
        } catch (Throwable ignored) {
            return false;
        }
    }
}
