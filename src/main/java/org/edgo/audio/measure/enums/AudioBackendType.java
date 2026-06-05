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

package org.edgo.audio.measure.enums;

/**
 * Selects which native audio path the application uses for capture and playback.
 *
 * <p>{@link #WASAPI} routes through the OS WASAPI APIs (exclusive mode by
 * default) — the recommended path on modern Windows.
 *
 * <p>{@link #WDMKS} routes through PortAudio's Windows Driver Model Kernel
 * Streaming host API (paWDMKS).  Requires {@code portaudio_x64.dll} on
 * {@code java.library.path}.
 *
 * <p>{@link #JAVASOUND} routes through {@code javax.sound.sampled} mixers —
 * the only cross-platform option (ALSA/Pulse on Linux, CoreAudio on macOS,
 * WDM/WASAPI shared on Windows).  Use this on non-Windows builds; on Windows
 * the WASAPI exclusive path generally gives better latency and bit-exact
 * playback.
 */
public enum AudioBackendType {
    WASAPI,
    WDMKS,
    JAVASOUND;

    private AudioBackendType() {}

    public static AudioBackendType fromString(String s) {
        if (s == null) {
            // No --backend supplied: prefer WASAPI on Windows, JAVASOUND
            // everywhere else (since WASAPI/WDM-KS won't open on non-Windows).
            return WASAPI.isAvailable() ? WASAPI : JAVASOUND;
        }
        AudioBackendType parsed;
        switch (s.toLowerCase()) {
            case "wdmks":
            case "wdm-ks":
            case "ks":
                parsed = WDMKS; break;
            case "wasapi":
                parsed = WASAPI; break;
            case "javasound":
            case "java":
            case "js":
                parsed = JAVASOUND; break;
            default:
                throw new IllegalArgumentException(
                        "Unknown --backend: " + s + " (wasapi|wdmks|javasound)");
        }
        if (!parsed.isAvailable()) {
            throw new IllegalArgumentException(
                    "--backend " + s + " is Windows-only and cannot be used on "
                            + System.getProperty("os.name")
                            + " (try --backend javasound)");
        }
        return parsed;
    }

    /** True when this backend can be opened on the running OS. */
    public boolean isAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        boolean windows = os.contains("win");
        switch (this) {
            case WASAPI:
            case WDMKS:    return windows;
            case JAVASOUND: return true;
            default:       return false;
        }
    }
}
