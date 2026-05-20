package org.edgo.audio.measure.enums;

/** Audio container format used by the GUI's save-to / load-from
 *  paths (generator export and oscilloscope capture). */
public enum AudioFileFormat {
    WAV,
    FLAC,
    AIFF;

    private AudioFileFormat() {}
}
