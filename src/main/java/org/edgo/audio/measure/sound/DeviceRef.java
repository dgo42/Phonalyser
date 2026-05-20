package org.edgo.audio.measure.sound;

import org.edgo.audio.measure.enums.AudioBackendType;
/**
 * Backend-agnostic handle to an audio device. Used by {@link AudioBackend}
 * to open capture / playback streams.
 *
 * <p>For {@link AudioBackendType#CSJSOUND} the implementation wraps a
 * {@code javax.sound.sampled.Mixer.Info}; for {@link AudioBackendType#WDMKS}
 * it wraps a PortAudio device index plus the host-API descriptor.
 */
public interface DeviceRef {
    /** Index within its backend's listing (used by {@code --device <i>}). */
    int index();

    String name();

    String description();

    String vendor();

    AudioBackendType backend();

    /** True if this handle is suitable for capture (input). */
    boolean isInput();

    /** True if this handle is suitable for playback (output). */
    boolean isOutput();

    default String displayName() {
        return String.format("[%d] %s (%s) — %s", index(), name(), description(), vendor());
    }
}
