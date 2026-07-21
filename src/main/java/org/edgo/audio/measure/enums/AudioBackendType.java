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

import lombok.Getter;

import org.edgo.audio.measure.sound.LibUsb;

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
 *
 * <p>{@link #QA40X} drives a QuantAsylum QA402/QA403 audio analyzer directly
 * over {@code libusb-1.0}, bypassing the vendor software.  Cross-platform
 * wherever the native {@code libusb-1.0} binding loads (its
 * {@link #isAvailable()} probes exactly that), not gated on the host OS like
 * the sound-card backends.
 */
public enum AudioBackendType {
    WASAPI("WASAPI"),
    WDMKS("WDM-KS"),
    COREAUDIO("CoreAudio"),
    JAVASOUND("JavaSound"),
    QA40X("QA40x");

    /** Human-readable name shown in the Preferences dialog. */
    @Getter
    private final String displayName;

    private AudioBackendType(String displayName) {
        this.displayName = displayName;
    }

    public static AudioBackendType fromString(String s) {
        if (s == null) {
            return fromOs();   // no --backend supplied: OS-native default
        }
        AudioBackendType parsed;
        switch (s.toLowerCase()) {
            case "wdmks":
            case "wdm-ks":
            case "ks":
                parsed = WDMKS; break;
            case "wasapi":
                parsed = WASAPI; break;
            case "coreaudio":
            case "ca":
                parsed = COREAUDIO; break;
            case "javasound":
            case "java":
            case "js":
                parsed = JAVASOUND; break;
            case "qa40x":
            case "qa402":
            case "qa403":
                parsed = QA40X; break;
            default:
                throw new IllegalArgumentException(
                        "Unknown --backend: " + s + " (wasapi|wdmks|coreaudio|javasound|qa40x)");
        }
        if (!parsed.isAvailable()) {
            throw new IllegalArgumentException(
                    "--backend " + s + " is not available on "
                            + System.getProperty("os.name"));
        }
        return parsed;
    }

    /** The OS-native default backend: WASAPI on Windows, CoreAudio on macOS,
     *  JavaSound on Linux (and as a fallback elsewhere). */
    public static AudioBackendType fromOs() {
        if (WASAPI.isAvailable())    return WASAPI;
        if (COREAUDIO.isAvailable()) return COREAUDIO;
        return JAVASOUND;
    }

    /** True when this backend can be opened on the running OS — for
     *  {@link #QA40X} that means the {@code libusb-1.0} binding loads (graceful
     *  {@code false} when the native library is absent), independent of the host
     *  OS; the sound-card backends stay OS-gated. */
    public boolean isAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        boolean windows = os.contains("win");
        boolean mac     = os.contains("mac");
        switch (this) {
            case WASAPI:
            case WDMKS:     return windows;
            case COREAUDIO: return mac;
            case JAVASOUND: return !mac;   // hidden on macOS — CoreAudio replaces it
            case QA40X:     return LibUsb.available();
            default:        return false;
        }
    }
}
