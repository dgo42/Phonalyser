package org.edgo.audio.measure.sound;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.edgo.audio.measure.generator.SignalGenerator;

/**
 * Backend-agnostic stereo PCM playback line. Implementations:
 * {@link JavaSoundGenerator} (default WASAPI playback path) and
 * {@link WdmksGenerator} (PortAudio WDM-KS).
 */
public interface AudioPlayback extends AutoCloseable {

    void open() throws Exception;

    /** Plays a fixed duration, then drains and stops. */
    void play(SignalGenerator generator, int durationSeconds);

    /**
     * Plays continuously until {@code stopFlag} is raised. {@code readyLatch}
     * is counted down after the hardware buffer is fully pre-filled.
     */
    void play(SignalGenerator generator, AtomicBoolean stopFlag, CountDownLatch readyLatch);

    /**
     * Live-updates the TPDF dither resolution.  No-op when the
     * implementation doesn't support live dither changes (the
     * default).  Honoured by the in-process JavaSoundGenerator /
     * WdmksGenerator wired to the GUI.
     */
    default void setDitherBits(int bits) {}

    @Override
    void close();
}
