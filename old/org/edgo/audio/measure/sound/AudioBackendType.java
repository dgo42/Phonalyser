package org.edgo.audio.measure.sound;

/**
 * Selects which native audio path the application uses for capture and playback.
 *
 * <p>{@link #CSJSOUND} routes through {@code javax.sound.sampled} with the
 * csjsound MixerProvider on the classpath (WASAPI exclusive mode).
 *
 * <p>{@link #WDMKS} routes through PortAudio's Windows Driver Model Kernel
 * Streaming host API (paWDMKS). Requires {@code portaudio_x64.dll} on
 * {@code java.library.path}.
 */
public enum AudioBackendType {
    CSJSOUND,
    WDMKS;

    private AudioBackendType() {}

    public static AudioBackendType fromString(String s) {
        if (s == null) return CSJSOUND;
        switch (s.toLowerCase()) {
            case "csjsound":
            case "wasapi":
                return CSJSOUND;
            case "wdmks":
            case "wdm-ks":
            case "ks":
                return WDMKS;
            default:
                throw new IllegalArgumentException("Unknown --backend: " + s + " (csjsound|wdmks)");
        }
    }
}
