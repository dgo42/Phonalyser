package org.edgo.audio.measure.sound;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.edgo.audio.measure.generator.SignalGenerator;

/**
 * Backend-agnostic stereo PCM playback line. Implementations:
 * {@link CsjsoundGenerator} (csjsound / JavaSound) and
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

    @Override
    void close();
}
