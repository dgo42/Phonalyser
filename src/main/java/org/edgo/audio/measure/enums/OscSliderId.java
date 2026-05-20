package org.edgo.audio.measure.enums;

/** Identifies which on-canvas slider the user is currently dragging in
 *  the oscilloscope view: a per-channel vertical offset, the trigger
 *  level marker, or the trigger position (time) marker. */
public enum OscSliderId {
    OFFSET,
    TRIGGER_LEVEL,
    TRIGGER_POSITION;

    private OscSliderId() {}
}
