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
