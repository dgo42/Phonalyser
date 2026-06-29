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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import lombok.experimental.UtilityClass;
import lombok.extern.log4j.Log4j2;

/**
 * Asks the Microsoft Store how many package updates are available for THIS app —
 * the "direct" update check used when Phonalyser runs as a Store (MSIX) install
 * instead of polling GitHub.
 *
 * <p>The real query is the WinRT {@code StoreContext.GetAppAndOptionalStorePackageUpdatesAsync()}
 * API, which Java cannot call directly. It is driven through a short Windows
 * PowerShell child process; because a full-trust MSIX app's child processes
 * inherit its package identity, {@code StoreContext.GetDefault()} there resolves
 * to this app. Anything that goes wrong (not packaged, WinRT unavailable, old
 * Windows, timeout) returns {@code -1} so the caller can fall back to simply
 * opening the Store. Only meaningful inside the real Store install.
 */
@Log4j2
@UtilityClass
public class StoreUpdate {

    /** WinRT (StoreContext) query that prints the count of available updates. */
    private static final String PS = String.join("\n",
        "$ErrorActionPreference='Stop'",
        "Add-Type -AssemblyName System.Runtime.WindowsRuntime | Out-Null",
        "$asTask=[System.WindowsRuntimeSystemExtensions].GetMethods()|" +
            "Where-Object{$_.Name-eq'AsTask'-and $_.GetParameters().Count-eq 1-and " +
            "$_.GetParameters()[0].ParameterType.Name-eq'IAsyncOperation`1'}|Select-Object -First 1",
        "function Await($o,$t){$asTask.MakeGenericMethod($t).Invoke($null,@($o)).GetAwaiter().GetResult()}",
        "[void][Windows.Services.Store.StoreContext,Windows.Services.Store,ContentType=WindowsRuntime]",
        "$ctx=[Windows.Services.Store.StoreContext]::GetDefault()",
        "$updates=Await ($ctx.GetAppAndOptionalStorePackageUpdatesAsync()) " +
            "([System.Collections.Generic.IReadOnlyList[Windows.Services.Store.StorePackageUpdate]])",
        "[Console]::Out.WriteLine($updates.Count)");

    /**
     * Number of Store package updates available for this app, or {@code -1} when
     * it could not be determined. Runs the WinRT query in a short PowerShell
     * child; blocking, so call it off the UI thread.
     */
    public int availableUpdateCount() {
        if (!WindowsPackage.isPackaged()) return -1;
        Process p = null;
        try {
            String encoded = Base64.getEncoder()
                    .encodeToString(PS.getBytes(StandardCharsets.UTF_16LE));
            p = new ProcessBuilder("powershell.exe", "-NoProfile",
                    "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
                    .start();
            String last = "";
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    if (!line.isBlank()) last = line.trim();
                }
            }
            if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); return -1; }
            if (p.exitValue() != 0) return -1;
            return Integer.parseInt(last);
        } catch (Exception ex) {
            if (log.isDebugEnabled()) log.debug("Store update query failed: {}", ex.toString());
            if (p != null) p.destroyForcibly();
            return -1;
        }
    }
}
