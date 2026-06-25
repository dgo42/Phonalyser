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

import java.util.Locale;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;

import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;

/**
 * Detects whether this process is running inside an MSIX / AppX package — i.e.
 * installed from the Microsoft Store. Such installs are updated automatically by
 * the Store, so the app's own GitHub update check must stand down for them (it
 * would otherwise send Store users to a GitHub download that can't update an
 * MSIX install).
 *
 * <p>Uses the Win32 {@code GetCurrentPackageFullName} API (Windows 8+), which
 * returns {@code APPMODEL_ERROR_NO_PACKAGE} when the process has no package
 * identity. Always {@code false} off Windows. Resolved once and cached.
 */
@Log4j2
@UtilityClass
public class WindowsPackage {

    /** Returned by GetCurrentPackageFullName when the process is NOT packaged. */
    private static final int APPMODEL_ERROR_NO_PACKAGE = 15700;

    private Boolean packaged;   // constant per process — resolved once

    private interface Kernel32 extends Library {
        Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);
        int GetCurrentPackageFullName(IntByReference packageFullNameLength, Pointer packageFullName);
    }

    /** True when running as a Microsoft Store (MSIX/AppX) install. */
    public boolean isPackaged() {
        if (packaged == null) packaged = detect();
        return packaged;
    }

    private boolean detect() {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            return false;
        }
        try {
            // null buffer + length 0: returns ERROR_INSUFFICIENT_BUFFER when
            // packaged (and sets the length), APPMODEL_ERROR_NO_PACKAGE when not.
            int rc = Kernel32.INSTANCE.GetCurrentPackageFullName(new IntByReference(0), null);
            return rc != APPMODEL_ERROR_NO_PACKAGE;
        } catch (Throwable t) {     // API absent (pre-Win8) / JNA load failure
            if (log.isDebugEnabled()) log.debug("Package-identity check unavailable: {}", t.toString());
            return false;
        }
    }
}
