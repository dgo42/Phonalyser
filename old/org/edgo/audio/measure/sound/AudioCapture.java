package org.edgo.audio.measure.sound;

import java.util.function.Consumer;

import javax.sound.sampled.AudioFormat;

import org.edgo.audio.measure.common.StereoSample;

/**
 * Backend-agnostic stereo PCM capture line. Implementations:
 * {@link CsjsoundRecorder} (csjsound / JavaSound) and
 * {@link WdmksRecorder} (PortAudio WDM-KS).
 */
public interface AudioCapture extends AutoCloseable {

    /** Receives decoded stereo samples on the capture thread. */
    void setSampleListener(Consumer<StereoSample[]> listener);

    /** Receives raw little-endian PCM bytes (interleaved L/R) on the capture thread. */
    void setRawBytesListener(Consumer<byte[]> listener);

    void open() throws Exception;

    void startRecording();

    void stopRecording() throws InterruptedException;

    AudioFormat getFormat();

    boolean isRecording();

    /** Decodes one little-endian sample at the given byte offset. */
    int readSample(byte[] pcm, int offset);

    @Override
    void close();
}
