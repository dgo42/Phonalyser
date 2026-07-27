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

package org.edgo.audio.measure.cli.util;

import org.edgo.audio.measure.sound.javasound.CsjsoundNativePath;
import lombok.experimental.UtilityClass;

/**
 * Startup path setup shared by both front ends.
 *
 * <p>It lives here, in the CLI module, because that is the lowest layer both
 * entry points already build on: the GUI depends on the CLI, so putting it here
 * means one copy rather than one per front end.
 */
@UtilityClass
public final class CliAppPaths {

    /**
     * Stages the csjsound provider's native library so a FAT-JAR run can find it.
     *
     * <p>Must happen before anything triggers the JavaSound service scan: the
     * provider is discovered through that scan and its DLL has to be on the
     * library path by then, and a fat jar has no installer to have put it there.
     * Naming the JavaSound backend is the reason this module depends on it at
     * COMPILE scope while every other backend is runtime-only.
     */
    public void installCsjsoundLibrary() {
        CsjsoundNativePath.installForFatJar();
    }

}
