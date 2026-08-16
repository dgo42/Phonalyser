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

package org.edgo.audio.measure.enums;

import lombok.Getter;

/**
 * Selects which native audio path the application uses for capture and playback.
 *
 * <p>{@link #WASAPI} routes through the OS WASAPI APIs (exclusive mode by
 * default) - the recommended path on modern Windows.
 *
 * <p>{@link #WDMKS} routes through PortAudio's Windows Driver Model Kernel
 * Streaming host API (paWDMKS).  Requires {@code portaudio_x64.dll} on
 * {@code java.library.path}.
 *
 * <p>{@link #JAVASOUND} routes through {@code javax.sound.sampled} mixers -
 * the only cross-platform option (ALSA/Pulse on Linux, CoreAudio on macOS,
 * WDM/WASAPI shared on Windows).  Use this on non-Windows builds; on Windows
 * the WASAPI exclusive path generally gives better latency and bit-exact
 * playback.
 *
 * <p>{@link #QA40X} drives a QuantAsylum QA402/QA403 audio analyzer directly
 * over {@code libusb-1.0}, bypassing the vendor software.  Cross-platform, not
 * gated on the host OS like the sound-card backends; whether the native
 * {@code libusb-1.0} binding actually loads is probed by the driver module
 * through the provider SPI, not by this enum.
 *
 * <p>{@link #NET} is the carrier for a REMOTE backend: the devices of a
 * headless Phonalyser server reached over the network (doc/NET-PROTOCOL.md).
 * It owns no hardware of its own, which is why {@link #isAvailable()} answers
 * false - there is nothing to open until a server has been selected, so it is
 * never offered as a local choice and a server never serves it.  The remote
 * backend the connection is routed at is carried beside the device ref, not by
 * this constant.
 */
public enum AudioBackendType {
    WASAPI("WASAPI", false),
    WDMKS("WDM-KS", false),
    COREAUDIO("CoreAudio", false),
    JAVASOUND("JavaSound", false),
    QA40X("QA40x", false),
    LOOPBACK("Loopback", false),
    NET("Network", true);

    /** Human-readable name shown in the Preferences dialog. */
    @Getter
    private final String displayName;

    @Getter
    private final boolean dualLevel;

    private AudioBackendType(String displayName, boolean dualLevel) {
        this.displayName = displayName;
        this.dualLevel = dualLevel;
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
            // No "net" token: this parses --backend, which names a LOCAL backend,
            // and a remote bench is chosen by connecting to a server (never here).
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

    /** The constant named exactly {@code name}, or {@code null} - the lenient
     *  reader for names that arrive from OUTSIDE this build (a preferences file,
     *  the net protocol's backend fields), where an unknown name means "a newer
     *  release knows this backend" and must not throw.  {@link #fromString}
     *  stays the strict command-line parser. */
    public static AudioBackendType fromNameOrNull(String name) {
        for (AudioBackendType candidate : values()) {
            if (candidate.name().equals(name)) {
                return candidate;
            }
        }
        return null;
    }

    /** True when this backend fits the running OS - pure OS POLICY, the only
     *  question a model enum may answer.  {@link #QA40X} is not OS-gated at
     *  all, so it answers true here; whether the {@code libusb-1.0} binding
     *  actually loads is the DRIVER's answer, asked through
     *  {@code AudioBackend#isAvailable(AudioBackendType)} (the provider SPI),
     *  never probed from the model layer.  A {@link #isDualLevel() dual-level}
     *  carrier answers false everywhere: it has no local hardware of its own
     *  and becomes usable only once a server is connected, so it never joins a
     *  local backend list. */
    public boolean isAvailable() {
        if (dualLevel) {
            return false;   // a carrier for remote backends, not a local device
        }
        String os = System.getProperty("os.name", "").toLowerCase();
        boolean windows = os.contains("win");
        boolean mac     = os.contains("mac");
        switch (this) {
            case WASAPI:
            case WDMKS:     return windows;
            case COREAUDIO: return mac;
            case JAVASOUND: return !mac;   // hidden on macOS - CoreAudio replaces it
            case QA40X:     return true;   // cross-platform; the driver probes libusb
            case LOOPBACK:  return true;   // pure software - no device, so every OS
            default:        return false;
        }
    }
}
