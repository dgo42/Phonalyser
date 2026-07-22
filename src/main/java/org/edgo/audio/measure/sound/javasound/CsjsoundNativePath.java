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
 * it to a cache dir under the system temp and PREPENDS that dir to
 * {@code java.library.path}.  That works only because the JVM snapshots the
 * property at the process's FIRST {@code System.loadLibrary} — hence the call
 * sits at the top of {@code GuiMain.main}, before SWT / JNA / the provider
 * load anything.  No-ops on non-Windows, on installed layouts (DLL already
 * reachable), and on builds without the resource.
 */
@Log4j2
@UtilityClass
public class CsjsoundNativePath {

    /** Cache dir (under {@code java.io.tmpdir}) the DLL is extracted into. */
    private static final String NATIVES_DIR_NAME = "phonalyser-natives";

    /** Stages the csjsound DLL and prepends its dir to {@code java.library.path};
     *  MUST run before the JVM's first {@code System.loadLibrary} (see class doc). */
    public void installForFatJar() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win")) {
            return;
        }
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String dll  = "csjsound_" + arch + ".dll";   // the exact name the provider loads
        String sep  = File.pathSeparator;
        String libraryPath = System.getProperty("java.library.path", "");
        for (String dir : libraryPath.split(Pattern.quote(sep))) {
            if (!dir.isBlank() && Files.exists(Paths.get(dir, dll))) {
                return;                              // installed layout — already reachable
            }
        }
        boolean is32 = arch.equals("x86") || arch.contains("i386") || arch.contains("i686");
        String resource = "/" + (is32 ? "win32-x86" : "win32-x86-64") + "/" + dll;
        try (InputStream in = CsjsoundNativePath.class.getResourceAsStream(resource)) {
            if (in == null) {
                return;                              // not a bundled build (or exotic arch)
            }
            Path dir = Paths.get(System.getProperty("java.io.tmpdir"), NATIVES_DIR_NAME);
            Files.createDirectories(dir);
            Path target = dir.resolve(dll);
            try {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException locked) {
                // Another (or an earlier) instance holds the DLL mapped — Windows
                // forbids replacing a loaded image.  Reuse the existing copy.
                if (!Files.exists(target)) {
                    throw locked;
                }
            }
            System.setProperty("java.library.path", dir + sep + libraryPath);
            log.info("csjsound native staged for the fat-jar run: {}", target);
        } catch (IOException e) {
            log.warn("Could not stage the csjsound native ({}); exclusive-mode JavaSound mixers stay unavailable: {}",
                    resource, e.toString());
        }
    }
}
