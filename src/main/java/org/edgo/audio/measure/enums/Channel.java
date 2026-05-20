package org.edgo.audio.measure.enums;

/** Stereo capture / playback channel selector — L (left) or R (right).
 *  Used by the oscilloscope trigger, FFT analyser and measurement table
 *  to pick which channel of the shared signal buffer drives them. */
public enum Channel {
    L,
    R;

    private Channel() {}
}
