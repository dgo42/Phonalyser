package org.edgo.audio.measure.sound;

import lombok.extern.log4j.Log4j2;
import org.edgo.audio.measure.common.StereoSample;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Stereo PCM capture via {@code javax.sound.sampled.TargetDataLine} — the
 * cross-platform path used by the {@link AudioBackendType#JAVASOUND}
 * backend.  Mirrors {@link WasapiRecorder} / {@link WdmksRecorder}'s public
 * surface so the GUI scope view and CLI tools can drive it identically
 * through {@link AudioBackend}.
 */
@Log4j2
public class JavaSoundRecorder implements AudioCapture {

    private static final int CHANNELS      = 2;
    private static final int BUFFER_FRAMES = 4096;

    private final JavaSoundDeviceManager.JavaSoundDeviceRef device;
    private final int sampleRate;
    private final int sampleBytes;
    private final int frameSize;
    private final AudioFormat format;

    private TargetDataLine line;

    private final AtomicBoolean recording = new AtomicBoolean(false);
    private Thread captureThread;
    private StereoSample[] sampleBuf = new StereoSample[0];

    private Consumer<StereoSample[]> sampleListener;
    private PcmBatchListener         pcmBatchListener;
    private Consumer<byte[]>         rawBytesListener;

    public JavaSoundRecorder(JavaSoundDeviceManager.JavaSoundDeviceRef device,
                             int sampleRate, int bitDepth) {
        this.device      = device;
        this.sampleRate  = sampleRate;
        this.sampleBytes = bitDepth / 8;
        this.frameSize   = sampleBytes * CHANNELS;
        this.format = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate, bitDepth, CHANNELS,
                frameSize, sampleRate, false);
    }

    @Override public void setSampleListener  (Consumer<StereoSample[]> l) { sampleListener   = l; }
    @Override public void setPcmBatchListener(PcmBatchListener l)         { pcmBatchListener = l; }
    @Override public void setRawBytesListener(Consumer<byte[]> l)         { rawBytesListener = l; }

    @Override
    public void open() throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        Mixer mixer = AudioSystem.getMixer(device.mixerInfo());
        if (!mixer.isLineSupported(info)) {
            throw new LineUnavailableException(
                    "Mixer '" + device.name() + "' does not support " + format);
        }
        line = (TargetDataLine) mixer.getLine(info);
        line.open(format, BUFFER_FRAMES * frameSize);
        int hwFrames = line.getBufferSize() / frameSize;
        log.info("JavaSound recorder opened : {}", device.name());
        log.info("Capture format             : {}", format);
        log.info("HW buffer                  : {} frames ({} ms)",
                hwFrames, hwFrames * 1000 / sampleRate);
    }

    @Override
    public void startRecording() {
        if (line == null) {
            throw new IllegalStateException("Call open() before startRecording()");
        }
        recording.set(true);
        line.start();
        captureThread = new Thread(this::captureLoop, "javasound-capture");
        captureThread.setDaemon(true);
        captureThread.setPriority(Thread.MAX_PRIORITY);
        captureThread.start();
        log.info("JavaSound recording started on: {}", device.name());
    }

    @Override
    public void stopRecording() throws InterruptedException {
        recording.set(false);
        if (line != null) {
            try {
                line.stop();
            } catch (Throwable t) {
                log.warn("TargetDataLine.stop threw: {}", t.getMessage());
            }
        }
        if (captureThread != null) captureThread.join(2000);
        log.info("JavaSound recording stopped.");
    }

    private void captureLoop() {
        byte[] heap = new byte[BUFFER_FRAMES * frameSize];
        while (recording.get()) {
            int read = line.read(heap, 0, heap.length);
            if (read <= 0) continue;
            dispatch(heap, read);
        }
    }

    private void dispatch(byte[] buffer, int bytes) {
        if (rawBytesListener != null) {
            byte[] copy = Arrays.copyOf(buffer, bytes);
            rawBytesListener.accept(copy);
        }
        if (pcmBatchListener != null) {
            pcmBatchListener.accept(buffer, bytes);
        } else if (sampleListener != null) {
            int nFrames = bytes / frameSize;
            if (sampleBuf.length != nFrames) {
                sampleBuf = new StereoSample[nFrames];
                for (int i = 0; i < nFrames; i++) sampleBuf[i] = new StereoSample();
            }
            for (int f = 0; f < nFrames; f++) {
                int offset = f * frameSize;
                sampleBuf[f].ch0 = readSample(buffer, offset);
                sampleBuf[f].ch1 = readSample(buffer, offset + sampleBytes);
            }
            sampleListener.accept(sampleBuf);
        }
    }

    /**
     * Offset-binary decoder for JavaSound's signed little-endian PCM —
     * matches the encoding used by {@link WasapiRecorder} and
     * {@link WdmksRecorder} so downstream consumers get identical
     * unsigned samples regardless of the backend in use.
     */
    @Override
    public int readSample(byte[] pcm, int offset) {
        switch (sampleBytes) {
            case 1:
                return (pcm[offset] + (byte) 0x80) & 0xFF;
            case 2:
                return ((short) ((pcm[offset + 1] & 0xFF) << 8 | (pcm[offset] & 0xFF)) + (short) 0x8000) & 0xFFFF;
            case 3:
                return ((((pcm[offset + 2]) << 16)
                     | ((pcm[offset + 1] & 0xFF) << 8)
                     |  (pcm[offset]     & 0xFF)) + 0x800000) & 0xFFFFFF;
            case 4:
                return (((pcm[offset + 3] << 24)
                     | ((pcm[offset + 2] & 0xFF) << 16)
                     | ((pcm[offset + 1] & 0xFF) << 8)
                     |  (pcm[offset]     & 0xFF)) + 0x80000000) & 0xFFFFFFFF;
            default:
                throw new IllegalStateException("Unsupported sampleBytes: " + sampleBytes);
        }
    }

    @Override public AudioFormat getFormat()   { return format; }
    @Override public boolean     isRecording() { return recording.get(); }

    @Override
    public void close() {
        if (recording.get()) {
            try { stopRecording(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (line != null) {
            try { line.close(); } catch (Throwable ignored) {}
            line = null;
        }
    }
}
